package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityExecutionValueOverride
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@RunWith(Parameterized::class)
class StandaloneActivityRecoveryTest(
    private val origin: Instant,
    private val eventZone: ZoneId,
) {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "standalone-recovery-" + System.nanoTime()
    private val observedSql = CopyOnWriteArrayList<String>()
    private var databaseReference: LifeTracingDatabase? = null
    private var repositoryReference: LiveSessionRepository? = null
    private val database get() = requireNotNull(databaseReference)
    private val repository get() = requireNotNull(repositoryReference)
    private val previousZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(eventZone))
        open()
        LiveRuntimeTestFixtures(database).seedSeries()
    }

    @After
    fun tearDown() {
        try {
            discard()
            context.deleteDatabase(databaseName)
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun emptyDatabaseReopensWithoutAnActiveRuntime() {
        reopen()
        assertNoActiveRuntime()
    }

    @Test
    fun missingPointerDoesNotSynthesizeRuntimeFromUnfinishedHistory() {
        val started = start(snapshot(TimeTrackingMode.TIMER))
        // Representable persisted orphan: unfinished rows are not the v1 active-session policy guard.
        assertEquals(1, database.activeSessionDao().clear())
        reopen()

        assertNoActiveRuntime()
        assertEquals(started, execution(started))
    }

    @Test
    fun runningStopwatchReopensWithTheSameIdentityAndAccruesDowntime() {
        val before = runningWithClosedPause(TimeTrackingMode.STOPWATCH)
        reopen()

        assertEquals(before, active())
        val result = repository.reconcileActiveSession(at(1_000))

        assertEquals(before.session, result.finalSession)
        assertTrue(result.appliedEvents.isEmpty())
        assertEquals(before, active())
        assertNull(NextRuntimeDeadlineResolver.resolve(active()))
        assertEquals(Duration.ofSeconds(980), display(active(), 1_000).activeElapsed(0))
        assertOnlyOriginalExecution(before.execution)
    }

    @Test
    fun pausedStopwatchReopensPausedAndExcludesDowntime() {
        assertPausedRecovery(TimeTrackingMode.STOPWATCH)
    }

    @Test
    fun finishTimerBeforeDeadlineReopensWithFrozenSnapshotAndPauseAdjustedDeadline() {
        val before = runningWithClosedPause(TimeTrackingMode.TIMER)
        val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(before))
        assertEquals(at(80), deadline.at)
        mutateSourceTemplate()
        reopen()

        assertEquals(before, active())
        assertEquals(ActivityTemplateId("source"), active().snapshot.sourceTemplateId)
        assertEquals(1L, active().snapshot.sourceRevision)
        assertEquals(StatisticsSeriesId("activity-series"), active().execution.statisticsSeriesId)
        assertEquals(deadline, NextRuntimeDeadlineResolver.resolve(active()))
        assertEquals(Duration.ofSeconds(1), display(active(), 79).timerRemaining(0))
        val result = repository.reconcileActiveSession(at(79))
        assertEquals(before.session, result.finalSession)
        assertTrue(result.appliedEvents.isEmpty())
        assertEquals(before, active())
        assertOnlyOriginalExecution(before.execution)
        assertFalse(observedSql.any { it.contains("FROM activity_templates", ignoreCase = true) })
    }

    @Test
    fun finishTimerExactlyAtDeadlineCompletesOnceAtLogicalDeadline() {
        assertFinishRecovery(80)
    }

    @Test
    fun finishTimerAfterDeadlineCompletesOnceAtLogicalDeadline() {
        assertFinishRecovery(1_000)
    }

    @Test
    fun pausedFinishTimerReopensPausedAndExcludesDowntime() {
        assertPausedRecovery(TimeTrackingMode.TIMER)
    }

    @Test
    fun overtimeTimerReopensAfterNominalZeroAndRemainsLive() {
        val before = runningWithClosedPause(TimeTrackingMode.TIMER, TimerZeroBehavior.OVERTIME)
        mutateSourceTemplate()
        reopen()

        assertEquals(before, active())
        val result = repository.reconcileActiveSession(at(100))

        assertEquals(before.session, result.finalSession)
        assertTrue(result.appliedEvents.isEmpty())
        assertEquals(before, active())
        assertNull(NextRuntimeDeadlineResolver.resolve(active()))
        val progress = display(active(), 100)
        assertEquals(Duration.ofSeconds(80), progress.activeElapsed(0))
        assertEquals(Duration.ZERO, progress.timerRemaining(0))
        assertEquals(Duration.ofSeconds(20), progress.timerOvertime(0))
        assertOnlyOriginalExecution(before.execution)
    }

    @Test
    fun planLinkedFinishRecoveryReloadsAsOneCanonicalTerminalFact() {
        val before = runningWithClosedPause(TimeTrackingMode.TIMER, planLinked = true)
        mutateSourceTemplate()
        reopen()

        assertEquals(before, active())
        assertNaturalCompletion(before, 1_000)
        assertCanonicalTerminalFact(before)
    }

    @Test
    fun latePlanFulfillmentFailureRollsBackNaturalCompletionAndRetryCommitsOnce() {
        val before = runningWithClosedPause(TimeTrackingMode.TIMER, planLinked = true)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_recovery_fulfillment BEFORE UPDATE ON plan_entries " +
                "WHEN NEW.status = 'FULFILLED' BEGIN SELECT RAISE(IGNORE); END",
        )
        val planBefore = database.planEntryDao().getById("plan")
        reopen()

        val failure =
            assertThrows(IllegalStateException::class.java) {
                repository.reconcileActiveSession(at(1_000))
            }
        assertEquals("Linked Activity Plan cannot be fulfilled", failure.message)
        // Reopen again to prove rollback durably, rather than observing a cached aggregate.
        reopen()
        assertEquals(before, active())
        assertEquals(planBefore, database.planEntryDao().getById("plan"))
        assertOnlyOriginalExecution(before.execution)

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_recovery_fulfillment")
        assertNaturalCompletion(before, 1_000)
        assertCanonicalTerminalFact(before)
    }

    @Test
    fun millisecondDeadlineBoundarySurvivesRelocationAndColdRecovery() {
        val before = runningWithClosedPause(TimeTrackingMode.TIMER)
        val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(before))
        reopen()
        assertTrue(repository.reconcileActiveSession(deadline.at.minusMillis(1)).appliedEvents.isEmpty())
        assertEquals(before, active())
        val display =
            RuntimeDisplayBaseline.capture(active(), WallMonotonicAnchor(deadline.at.minusMillis(1), 0), 0)
        assertEquals(Duration.ofMillis(1), display.timerRemaining(0))
        assertEquals(listOf(deadline), repository.reconcileActiveSession(deadline.at).appliedEvents.map { it.deadline })
        val completed = execution(before.execution)
        assertEquals(deadline.at, completed.completedAt)
        assertEquals(Duration.ofSeconds(60), completed.activeDuration)
        assertEquals(before.execution.originalZoneId, completed.originalZoneId)
        assertEquals(before.execution.originalUtcOffsetMinutes, completed.originalUtcOffsetMinutes)
        assertEquals(before.execution.primaryLocalDate, completed.primaryLocalDate)
        assertTrue(repository.reconcileActiveSession(deadline.at.plusMillis(1)).appliedEvents.isEmpty())
        reopen()
        assertNoActiveRuntime()
        assertEquals(completed, execution(completed))
        assertOnlyOriginalExecution(completed)
    }

    @Test
    fun terminalActivityCannotRewindOrBecomeLiveAfterClockRollback() {
        val started = start(snapshot(TimeTrackingMode.TIMER))
        repository.completeActiveActivity(at(30))
        val completed = execution(started)
        reopen()
        assertTrue(repository.reconcileActiveSession(at(10)).appliedEvents.isEmpty())
        assertNull(repository.getActiveRuntime())
        assertEquals(completed, execution(started))
        assertOnlyOriginalExecution(completed)
    }

    @Test
    fun missingActualValueSurvivesColdTemporalRecoveryAsMissing() {
        assertActualValueRecovery(null)
    }

    @Test
    fun zeroActualValueSurvivesColdTemporalRecoveryAsZero() {
        assertActualValueRecovery(0)
    }

    @Test
    fun exactPlanRetainsInstantAndFrozenHistoryWhileCalendarUsesTheCurrentZone() {
        val frozen = snapshot(TimeTrackingMode.TIMER)
        database.planEntryDao().insert(
            plan(frozen).copy(
                plannedDay = null,
                scheduledInstantMs = at(0).toEpochMilli(),
                creationZoneId = eventZone.id,
            ),
        )
        val started = repository.startActivityFromPlan(PlanEntryId("plan"), at(0), at(0), eventZone)
        val originalPlan = readPlan(eventZone)
        val before = active()
        reopen()
        val relocated = ZoneId.of("America/New_York")
        TimeZone.setDefault(TimeZone.getTimeZone(relocated))
        assertTrue(repository.reconcileActiveSession(at(60).minusMillis(1)).appliedEvents.isEmpty())
        assertEquals(before, active())
        assertEquals(originalPlan, readPlan(relocated))
        assertEquals(at(0), (readPlan(relocated).target as PlanTarget.ExactDay).scheduledAt)
        repository.reconcileActiveSession(at(100))
        reopen()
        val fulfilled = readPlan(relocated)
        assertEquals(originalPlan.target, fulfilled.target)
        assertEquals(originalPlan.activitySnapshotId, fulfilled.activitySnapshotId)
        assertEquals(PlanEntryStatus.FULFILLED, fulfilled.status)
        val detail = requireNotNull(HistoryReadRepository(database).getActivityDetail(started.id))
        assertEquals(eventZone, detail.root.originalZoneId)
        assertEquals(started.originalUtcOffsetMinutes, detail.root.originalUtcOffsetMinutes)
        assertEquals(started.primaryLocalDate, detail.root.primaryLocalDate)
        assertEquals(started.statisticsSeriesId, execution(started).statisticsSeriesId)
        assertOnlyOriginalExecution(execution(started))
    }

    private fun readPlan(zone: ZoneId) =
        at(0).atZone(zone).toLocalDate().let { date ->
            PlanReadRepository(database, CurrentZoneIdProvider { zone })
                .getWeek(WeekPlanQuery(date.with(DayOfWeek.MONDAY), date, at(100)))
                .selectedDayPlans
                .single()
                .plan
        }

    private fun assertActualValueRecovery(numberScaled: Long?) {
        val started = start(snapshot(TimeTrackingMode.TIMER), numberScaled = numberScaled)
        val before = active()
        reopen()
        assertEquals(before, active())
        repository.reconcileActiveSession(at(100))
        reopen()
        val detail = requireNotNull(HistoryReadRepository(database).getActivityDetail(started.id))
        assertEquals(
            numberScaled?.let(ActivityHistoryActualValue::Number) ?: ActivityHistoryActualValue.Missing,
            detail.fields.single().actualValue,
        )
        assertEquals(started.snapshotId, detail.root.snapshotId)
        assertEquals(started.statisticsSeriesId, execution(started).statisticsSeriesId)
        assertEquals(eventZone, detail.root.originalZoneId)
        assertOnlyOriginalExecution(execution(started))
        assertNull(repository.getActiveRuntime())
    }

    private fun assertPausedRecovery(mode: TimeTrackingMode) {
        start(snapshot(mode))
        repository.pauseActiveActivity(ActivityExecutionPauseId("open-pause"), at(20))
        val before = active()
        reopen()

        assertEquals(before, active())
        val result = repository.reconcileActiveSession(at(1_000))
        assertEquals(before.session, result.finalSession)
        assertTrue(result.appliedEvents.isEmpty())
        assertEquals(before, active())
        assertEquals(ActivityExecutionStatus.PAUSED, active().execution.status)
        assertEquals(Duration.ofSeconds(20), display(active(), 1_000).activeElapsed(0))
        assertNull(NextRuntimeDeadlineResolver.resolve(active()))
        val openPause = active().execution.pauses.single()
        assertNull(openPause.endedAt)
        assertOnlyOriginalExecution(before.execution)

        repository.resumeActiveActivity(at(1_000))
        if (mode == TimeTrackingMode.TIMER) {
            assertEquals(at(1_040), NextRuntimeDeadlineResolver.resolve(active())?.at)
            assertTrue(repository.reconcileActiveSession(at(1_039)).appliedEvents.isEmpty())
            assertEquals(1, repository.reconcileActiveSession(at(1_040)).appliedEvents.size)
        } else {
            repository.completeActiveActivity(at(1_040))
        }
        val completed = execution(before.execution)
        assertEquals(Duration.ofSeconds(60), completed.activeDuration)
        assertEquals(at(1_000), completed.pauses.single().endedAt)
        val originalPause = before.execution.pauses.single()
        assertEquals(originalPause.id, completed.pauses.single().id)
        assertNull(repository.getActiveRuntime())
    }

    private fun assertFinishRecovery(reconcileAt: Long) {
        val before = runningWithClosedPause(TimeTrackingMode.TIMER)
        reopen()
        assertEquals(before, active())

        assertNaturalCompletion(before, reconcileAt)
    }

    private fun assertNaturalCompletion(
        before: ActiveActivityRuntime,
        reconcileAt: Long,
    ) {
        val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(before))
        val result = repository.reconcileActiveSession(at(reconcileAt))
        assertNull(result.finalSession)
        val event = result.appliedEvents.single()
        assertEquals(deadline, event.deadline)
        assertEquals(RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO, event.deadline.kind)
        assertEquals(before.snapshot.settings.timerEndSound, event.soundEnabled)
        assertEquals(before.snapshot.settings.timerEndVibration, event.vibrationEnabled)
        val completed = execution(before.execution)
        assertEquals(
            before.execution.copy(
                status = ActivityExecutionStatus.COMPLETED,
                completedAt = at(80),
                activeDuration = Duration.ofSeconds(60),
                updatedAt = at(80),
            ),
            completed,
        )
        assertEquals(before.snapshot, snapshotFromStorage(before.snapshot.id))
        assertNull(repository.getActiveSession())
        assertOnlyOriginalExecution(completed)
        val fulfilledPlan = database.planEntryDao().getById("plan")
        if (before.execution.planEntryId != null) {
            assertEquals("FULFILLED", fulfilledPlan?.status)
            assertEquals(completed.id.value, fulfilledPlan?.fulfilledActivityExecutionId)
            assertEquals(completed.completedAt?.toEpochMilli(), fulfilledPlan?.fulfilledAtMs)
        }

        val repeated = repository.reconcileActiveSession(at(reconcileAt + 1))
        assertTrue(repeated.appliedEvents.isEmpty())
        assertNull(repeated.finalSession)
        assertEquals(completed, execution(completed))
        assertEquals(fulfilledPlan, database.planEntryDao().getById("plan"))
        reopen()
        assertNoActiveRuntime()
        assertEquals(completed, execution(completed))
        assertEquals(fulfilledPlan, database.planEntryDao().getById("plan"))
    }

    private fun assertCanonicalTerminalFact(before: ActiveActivityRuntime) {
        val date = at(0).atZone(ZoneOffset.UTC).toLocalDate()
        val zone = CurrentZoneIdProvider { ZoneOffset.UTC }
        val planRow =
            PlanReadRepository(database, zone)
                .getWeek(WeekPlanQuery(date.with(DayOfWeek.MONDAY), date, at(1_000)))
                .selectedDayPlans
                .single()
        assertEquals(PlanEntryId("plan"), planRow.plan.id)
        assertEquals(PlanEntryStatus.FULFILLED, planRow.plan.status)
        assertEquals(at(80), planRow.plan.fulfilledAt)
        assertEquals(before.execution.id, planRow.plan.fulfilledActivityExecutionId)
        assertEquals(before.snapshot.id, planRow.plan.activitySnapshotId)
        assertFalse(planRow.engaged)

        val daily = DailyReadRepository(database, zone).getDaily(DailyQuery(date, at(1_000), 10))
        assertNull(daily.active)
        assertEquals(planRow.plan, daily.dayPlans.single().plan)
        val history = HistoryReadRepository(database)
        val root =
            history.getCompletedRoots(CompletedHistoryQuery(HistoryDateRange(date, date), 10)).single()
                as CompletedActivityHistoryRoot
        assertEquals(listOf(root), daily.completedHistory)
        assertEquals(before.execution.id, root.executionId)
        assertEquals(before.snapshot.id, root.snapshotId)
        assertEquals(before.execution.planEntryId, root.planEntryId)
        assertEquals(at(80), root.completedAt)
        assertEquals(Duration.ofSeconds(60), root.activeDuration)
        assertEquals(before.snapshot.name, root.title)
        val detail = requireNotNull(history.getActivityDetail(before.execution.id))
        assertEquals(root, detail.root)
        assertEquals(ActivityHistoryActualValue.Number(7_000), detail.fields.single().actualValue)

        val statistics = StatisticsRepository(database) { StatisticsSeriesId("unused") }
        val global = statistics.global(StatisticsPeriod.AllTime)
        assertEquals(1L, global.topLevelExecutionCount)
        assertEquals(Duration.ofSeconds(60), global.totalTrackedDuration)
        val series = statistics.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime)
        assertEquals(1L, series.executionCount)
        assertEquals(1L, series.durations.sampleCount)
        assertEquals(Duration.ofSeconds(60), series.durations.total)
    }

    private fun runningWithClosedPause(
        mode: TimeTrackingMode,
        zeroBehavior: TimerZeroBehavior = TimerZeroBehavior.FINISH,
        planLinked: Boolean = false,
    ): ActiveActivityRuntime {
        start(snapshot(mode, zeroBehavior), planLinked)
        repository.pauseActiveActivity(ActivityExecutionPauseId("closed-pause"), at(10))
        repository.resumeActiveActivity(at(30))
        return active()
    }

    private fun start(
        snapshot: ActivityConfigSnapshot,
        planLinked: Boolean = false,
        numberScaled: Long? = 7_000,
    ): ActivityExecution {
        val fieldId = snapshot.fields.single().id
        val actual = numberScaled?.let { NumberExecutionValue(fieldId, it) }
        val values = listOf(ActivityExecutionValueOverride(fieldId, actual))
        val execution =
            if (planLinked) {
                database.planEntryDao().insert(plan(snapshot))
                repository.startActivityFromPlan(PlanEntryId("plan"), at(0), at(0), eventZone, values)
            } else {
                repository.startStandaloneTimedActivityFromSnapshot(snapshot.id, at(0), at(0), eventZone, values)
            }
        assertEquals(listOfNotNull(actual), execution.values)
        return execution
    }

    private fun snapshot(
        mode: TimeTrackingMode,
        zeroBehavior: TimerZeroBehavior = TimerZeroBehavior.FINISH,
    ): ActivityConfigSnapshot {
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    id = "source",
                    name = "Frozen activity",
                    shortComment = "Frozen comment",
                    timeTrackingMode = mode.name,
                    timerTargetMs = if (mode == TimeTrackingMode.TIMER) 60_000 else null,
                    statisticsSeriesId = "activity-series",
                    revision = 1,
                    createdAtMs = 0,
                    updatedAtMs = 0,
                    deletedAtMs = null,
                    folderId = null,
                ),
                ActivityTemplateSettingsEntity("source", timerZeroBehavior = zeroBehavior.name),
                fields =
                    listOf(
                        ActivityTemplateFieldEntity(
                            id = "source-number",
                            activityTemplateId = "source",
                            position = 0,
                            name = "Number",
                            fieldType = "NUMBER",
                            unit = null,
                            displayPrecision = 0,
                            defaultNumberScaled = 5_000,
                            defaultCategoryOptionId = null,
                            defaultText = null,
                            isMainValue = true,
                            createdAtMs = 0,
                            updatedAtMs = 0,
                            deletedAtMs = null,
                        ),
                    ),
                userState = ActivityTemplateUserStateEntity("source", null, null),
            ),
        )
        val factory =
            ActivitySnapshotFactory(
                { ActivitySnapshotId("frozen") },
                { ActivitySnapshotFieldId("frozen-number") },
                { ActivitySnapshotCategoryOptionId("unused-option") },
            )
        val template = requireNotNull(database.activityTemplateDao().getAggregate("source")).toDomain()
        val snapshot = factory.fromTemplate(template, at(0))
        database.activitySnapshotDao().insertAggregate(snapshot.toEntityAggregate())
        return snapshot
    }

    private fun plan(snapshot: ActivityConfigSnapshot) =
        PlanEntryEntity(
            id = "plan",
            trackableKind = "ACTIVITY",
            sourceActivityTemplateId = "source",
            sourceSequenceTemplateId = null,
            sourceRevision = 1,
            activitySnapshotId = snapshot.id.value,
            sequencePlanSnapshotId = null,
            precision = "DAY",
            plannedDay = at(0).atZone(eventZone).toLocalDate().toString(),
            plannedWeekStart = null,
            plannedMonth = null,
            scheduledInstantMs = null,
            creationZoneId = null,
            status = "PLANNED",
            fulfilledActivityExecutionId = null,
            fulfilledSequenceExecutionId = null,
            createdAtMs = 0,
            updatedAtMs = 0,
            cancelledAtMs = null,
            fulfilledAtMs = null,
        )

    private fun mutateSourceTemplate() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_templates SET name = 'Changed source', time_tracking_mode = 'NO_LIVE_TRACKING', " +
                "timer_target_ms = NULL, revision = 2, updated_at_ms = 40000 WHERE id = 'source'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_template_settings SET timer_zero_behavior = 'OVERTIME' " +
                "WHERE activity_template_id = 'source'",
        )
    }

    private fun active() = repository.getActiveRuntime() as ActiveActivityRuntime

    private fun execution(original: ActivityExecution) =
        requireNotNull(database.activityExecutionDao().getAggregate(original.id.value)).toDomain()

    private fun snapshotFromStorage(id: ActivitySnapshotId) =
        requireNotNull(database.activitySnapshotDao().getAggregate(id.value)).toDomain()

    private fun display(
        runtime: ActiveActivityRuntime,
        observedSeconds: Long,
    ) = RuntimeDisplayBaseline.capture(runtime, WallMonotonicAnchor(at(observedSeconds), 0), 0)

    private fun assertNoActiveRuntime() {
        observedSql.clear()
        assertNull(repository.getActiveSession())
        assertNull(repository.getActiveRuntime())
        val result = repository.reconcileActiveSession(at(1_000))
        assertNull(result.finalSession)
        assertTrue(result.appliedEvents.isEmpty())
        assertFalse(observedSql.any { it.contains("FROM activity_executions", ignoreCase = true) })
    }

    private fun assertOnlyOriginalExecution(original: ActivityExecution) {
        database.openHelper.readableDatabase.query("SELECT id FROM activity_executions").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(original.id.value, cursor.getString(0))
        }
    }

    private fun reopen() {
        discard()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        assertNull(databaseReference)
        assertNull(repositoryReference)
        open()
        observedSql.clear()
    }

    private fun discard() {
        // Only immutable expected facts survive. No repository, Room connection, or timer baseline is retained.
        repositoryReference = null
        databaseReference?.close()
        databaseReference = null
    }

    private fun open() {
        databaseReference =
            LifeTracingDatabase
                .builder(context, databaseName)
                .setQueryCallback({ sql, _ -> observedSql += sql }, Executor { it.run() })
                .allowMainThreadQueries()
                .build()
        repositoryReference = LiveSessionRepository.create(database)
    }

    private fun at(seconds: Long): Instant = origin.plusSeconds(seconds)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "context={index}")
        fun temporalContexts(): List<Array<Any>> =
            listOf(
                arrayOf(Instant.EPOCH, ZoneOffset.UTC),
                arrayOf(Instant.parse("2026-03-29T00:59:30Z"), ZoneId.of("Europe/Berlin")),
                arrayOf(Instant.parse("2026-10-25T00:59:30Z"), ZoneId.of("Europe/Berlin")),
            )
    }
}
