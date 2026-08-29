package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyActiveSequenceState
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanningPrecision
import com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DailyReadRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var live: LiveSessionRepository
    private lateinit var daily: DailyReadRepository
    private var currentZone: ZoneId = ZoneOffset.UTC
    private val observedSql = Collections.synchronizedList(mutableListOf<String>())
    private var fileDatabaseName: String? = null

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .setQueryCallback({ sql, _ -> observedSql += sql }, Executor { it.run() })
                .allowMainThreadQueries()
                .build()
        val fixtures = LiveRuntimeTestFixtures(database)
        fixtures.seedSeries()
        fixtures.activity("stopwatch", "STOPWATCH")
        fixtures.activity("timer", "TIMER", 60_000)
        fixtures.activity("no-live", "NO_LIVE_TRACKING")
        fixtures.sequence("sequence-one", listOf("stopwatch"))
        fixtures.sequence("sequence-three", listOf("stopwatch", "timer", "no-live"), autoAdvance = false)
        fixtures.sequence("sequence-no-live", listOf("no-live", "stopwatch"), autoAdvance = false)
        fixtures.sequence("sequence-countdown", listOf("stopwatch", "timer"), countdownMs = 5_000)
        live = liveRepository()
        daily = DailyReadRepository(database, CurrentZoneIdProvider { currentZone }, live)
    }

    @After
    fun tearDown() {
        database.close()
        fileDatabaseName?.let(ApplicationProvider.getApplicationContext<Context>()::deleteDatabase)
    }

    @Test
    fun oneReadCombinesPlansBoundedHistoryAndCanonicalActiveWithZoneCorrectPlacement() {
        database.planEntryDao().insert(plan("floating", activity = "no-live", day = "2026-08-20"))
        database.planEntryDao().insert(
            plan(
                "exact",
                activity = "stopwatch",
                scheduledAt = Instant.parse("2026-08-20T23:30:00Z"),
            ),
        )
        database.planEntryDao().insert(plan("week", sequence = "sequence-one", precision = "WEEK", week = "2026-08-17"))
        database.planEntryDao().insert(plan("month", activity = "no-live", precision = "MONTH", month = "2026-08"))
        val completed =
            live.completeNoLiveActivityFromSnapshot(
                snapshotId = ActivitySnapshotId("no-live"),
                completedAt = Instant.parse("2026-08-20T10:00:00Z"),
                zoneId = ZoneOffset.UTC,
                createdAt = Instant.parse("2026-08-20T10:00:00Z"),
            )
        val active =
            live.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("stopwatch"),
                Instant.parse("2026-08-20T11:00:00Z"),
                Instant.parse("2026-08-20T11:00:00Z"),
                ZoneOffset.UTC,
            )

        val utc = read("2026-08-20")

        assertEquals(listOf("exact", "floating"), utc.dayPlans.map { it.plan.id.value }.sorted())
        assertEquals(listOf("week"), utc.weekPlans.map { it.plan.id.value })
        assertEquals(
            PlanningPrecision.WEEK,
            utc.weekPlans
                .single()
                .plan.target.precision,
        )
        assertEquals(
            "2026-08-17",
            (
                utc.weekPlans
                    .single()
                    .plan.target as com.alexandr5476.lifetracing.domain.PlanTarget.Week
            ).weekStart.toString(),
        )
        assertEquals(completed.id, (utc.completedHistory.single() as CompletedActivityHistoryRoot).executionId)
        assertEquals(active.id, (utc.active as DailyActive.Activity).runtime.execution.id)
        assertTrue(utc.dayPlans.none { it.plan.id.value == "month" })

        currentZone = ZoneOffset.ofHours(2)
        assertEquals(listOf("floating"), read("2026-08-20").dayPlans.map { it.plan.id.value })
        val moved = read("2026-08-21").dayPlans.single { it.plan.id.value == "exact" }
        assertEquals("exact", moved.plan.id.value)
        assertEquals(
            Instant.parse("2026-08-20T23:30:00Z"),
            (moved.plan.target as com.alexandr5476.lifetracing.domain.PlanTarget.ExactDay).scheduledAt,
        )
    }

    @Test
    fun frozenPlanMetadataSourceStateOverdueAndBatchHydrationStayCanonical() {
        insertSourceTemplate()
        insertLinkedSnapshot()
        repeat(3) { index ->
            database.planEntryDao().insert(
                plan(
                    "source-plan-$index",
                    activity = "linked-snapshot",
                    day = "2026-08-20",
                    source = "source",
                    revision = 1,
                ),
            )
        }
        val template = requireNotNull(database.activityTemplateDao().getById("source"))
        database.activityTemplateDao().updateTemplate(
            template.copy(name = "Renamed source", shortComment = "Renamed note", revision = 2, updatedAtMs = 2),
        )

        val changed =
            daily.getDaily(
                DailyQuery(LocalDate.parse("2026-08-20"), Instant.parse("2026-08-21T00:00:00Z"), 10),
            )

        assertTrue(changed.dayPlans.all { it.overdue && it.plan.status == PlanEntryStatus.PLANNED })
        assertTrue(
            changed.dayPlans.all {
                it.snapshot.title == "Frozen title" &&
                    it.snapshot.shortComment == "Frozen note"
            },
        )
        assertTrue(changed.dayPlans.all { it.sourceState == PlanSourceState.CHANGED })
        database.activityTemplateDao().archive("source", 3)
        assertTrue(read("2026-08-20").dayPlans.all { it.sourceState == PlanSourceState.ARCHIVED })

        observedSql.clear()
        read("2026-08-20")
        val sql = observedSql.map(String::lowercase)
        assertEquals(1, sql.count { "from activity_templates where id in" in it })
        assertEquals(1, sql.count { "from activity_snapshots where id in" in it })
        assertEquals(1, sql.count { "from activity_snapshot_settings where snapshot_id in" in it })
    }

    @Test
    fun planLinkedActivityProjectsEngagementFulfillmentHistoryAndSoftDeleteWithoutReopening() {
        database.planEntryDao().insert(plan("activity-plan", activity = "stopwatch", day = "2026-08-20"))
        val started =
            live.startActivityFromPlan(
                PlanEntryId("activity-plan"),
                Instant.parse("2026-08-20T10:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
                ZoneOffset.UTC,
            )

        val engaged = read("2026-08-20")
        assertTrue(engaged.dayPlans.single().engaged)
        assertEquals(
            PlanEntryStatus.PLANNED,
            engaged.dayPlans
                .single()
                .plan.status,
        )
        assertEquals(
            PlanEntryId("activity-plan"),
            (engaged.active as DailyActive.Activity).runtime.execution.planEntryId,
        )
        assertTrue(engaged.completedHistory.isEmpty())

        live.pauseActiveActivity(ActivityExecutionPauseId("activity-pause"), Instant.parse("2026-08-20T10:30:00Z"))
        assertEquals(
            ActiveSessionState.PAUSED,
            (read("2026-08-20").active as DailyActive.Activity).runtime.session.state,
        )
        live.resumeActiveActivity(Instant.parse("2026-08-20T10:40:00Z"))
        live.completeActiveActivity(Instant.parse("2026-08-20T11:00:00Z"))
        val fulfilled = read("2026-08-20")
        assertEquals(
            PlanEntryStatus.FULFILLED,
            fulfilled.dayPlans
                .single()
                .plan.status,
        )
        assertEquals(started.id, (fulfilled.completedHistory.single() as CompletedActivityHistoryRoot).executionId)
        assertNull(fulfilled.active)

        val execution = requireNotNull(database.activityExecutionDao().getAggregate(started.id.value)).toDomain()
        activityCommands().softDeleteHistory(started.id, execution.updatedAt, Instant.parse("2026-08-20T12:00:00Z"))
        val deleted = read("2026-08-20")
        assertEquals(
            PlanEntryStatus.FULFILLED,
            deleted.dayPlans
                .single()
                .plan.status,
        )
        assertEquals(
            started.id,
            deleted.dayPlans
                .single()
                .plan.fulfilledActivityExecutionId,
        )
        assertTrue(deleted.completedHistory.isEmpty())
    }

    @Test
    fun naturalAndEarlySequenceCompletionRemainFulfilledTerminalHistoryFacts() {
        database.planEntryDao().insert(plan("natural", sequence = "sequence-one", day = "2026-08-20"))
        val natural =
            live.startSequenceFromPlan(
                PlanEntryId("natural"),
                Instant.parse("2026-08-20T08:00:00Z"),
                Instant.parse("2026-08-20T08:00:00Z"),
                ZoneOffset.UTC,
            )
        live.completeCurrentSequenceStep(natural.execution.currentOccurrenceId!!, Instant.parse("2026-08-20T09:00:00Z"))
        val completed = read("2026-08-20")
        assertEquals(
            PlanEntryStatus.FULFILLED,
            completed.dayPlans
                .single()
                .plan.status,
        )
        assertEquals("COMPLETED", (completed.completedHistory.single() as CompletedSequenceHistoryRoot).status.name)
        assertNull(completed.active)

        database.planEntryDao().insert(plan("early", sequence = "sequence-one", day = "2026-08-20"))
        live.startSequenceFromPlan(
            PlanEntryId("early"),
            Instant.parse("2026-08-20T10:00:00Z"),
            Instant.parse("2026-08-20T10:00:00Z"),
            ZoneOffset.UTC,
        )
        live.endSequenceEarly(Instant.parse("2026-08-20T10:30:00Z"))
        val early = read("2026-08-20")
        assertEquals(
            PlanEntryStatus.FULFILLED,
            early.dayPlans
                .single { it.plan.id.value == "early" }
                .plan.status,
        )
        assertTrue(early.completedHistory.any { it is CompletedSequenceHistoryRoot && it.status.name == "ENDED_EARLY" })
        assertNull(early.active)
    }

    @Test
    fun activeSequenceCurrentPausedWaitingAndRuntimeOrderMetadataAreDistinct() {
        val started =
            live.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-three"),
                Instant.parse("2026-08-20T10:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
                ZoneOffset.UTC,
            )
        val third = started.execution.occurrences[2]
        live.makeNext(third.id, Instant.parse("2026-08-20T10:01:00Z"))
        val running = readSequence()
        assertEquals(DailyActiveSequenceState.RUNNING_CURRENT, running.state)
        assertEquals(started.execution.currentOccurrenceId, running.current?.occurrence?.id)
        assertEquals(third.id, running.next?.occurrence?.id)
        assertEquals("no-live", running.next?.activity?.name)
        assertFalse(requireNotNull(running.next).occurrence.isRuntimeAdded)

        live.pauseActiveSequence(Instant.parse("2026-08-20T10:02:00Z"))
        val paused = readSequence()
        assertEquals(DailyActiveSequenceState.PAUSED_CURRENT, paused.state)
        assertEquals(running.current?.occurrence?.id, paused.current?.occurrence?.id)
        live.resumeActiveSequence(Instant.parse("2026-08-20T10:03:00Z"))
        live.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, Instant.parse("2026-08-20T10:04:00Z"))
        val waiting = readSequence()
        assertEquals(DailyActiveSequenceState.WAITING_NEXT, waiting.state)
        assertNull(waiting.current)
        assertEquals(third.id, waiting.next?.occurrence?.id)
    }

    @Test
    fun noLiveAndRuntimeAddedCurrentUseOccurrenceSnapshotsAndCanonicalEffectiveSettings() {
        val started =
            live.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-no-live"),
                Instant.parse("2026-08-20T10:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
                ZoneOffset.UTC,
            )
        val noLive = readSequence()
        assertEquals(started.execution.currentOccurrenceId, noLive.current?.occurrence?.id)
        assertNull(noLive.runtime.currentChild)
        live.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, Instant.parse("2026-08-20T10:01:00Z"))
        val added =
            live
                .runtimeAdd(
                    ActivityEntrySource.OneOff(oneOff()),
                    RuntimeInsertionPlacement.START_NOW,
                    Instant.parse("2026-08-20T10:02:00Z"),
                ).execution.occurrences
                .single { it.isRuntimeAdded }

        val projected = readSequence()
        assertEquals(added.id, projected.current?.occurrence?.id)
        assertEquals("Runtime one-off", projected.current?.activity?.name)
        assertTrue(requireNotNull(projected.current).occurrence.isRuntimeAdded)
        assertNull(projected.current?.occurrence?.sourceSequenceSnapshotNodeId)
        assertEquals(Duration.ZERO, projected.current?.effectiveSettings?.startCountdown)
    }

    @Test
    fun runningAndPausedTransitionCountdownRemainDistinctAndDailyNeverReconcilesStaleState() {
        val started =
            live.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-countdown"),
                Instant.parse("2026-08-20T10:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
                ZoneOffset.UTC,
            )
        live.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, Instant.parse("2026-08-20T10:01:00Z"))
        val before = database.sequenceExecutionDao().getAggregate(started.execution.id.value)

        val running =
            daily
                .getDaily(
                    DailyQuery(LocalDate.parse("2026-08-20"), Instant.parse("2026-08-21T00:00:00Z"), 10),
                ).active as DailyActive.Sequence
        assertEquals(DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN, running.state)
        assertNull(running.current)
        assertEquals(started.execution.occurrences[1].id, running.next?.occurrence?.id)
        assertEquals(before, database.sequenceExecutionDao().getAggregate(started.execution.id.value))

        live.pauseActiveSequence(Instant.parse("2026-08-20T10:01:01Z"))
        val paused = readSequence()
        assertEquals(DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN, paused.state)
        assertNull(paused.current)
        assertEquals(running.next?.occurrence?.id, paused.next?.occurrence?.id)
    }

    @Test
    fun mixedQueryShapeIsBoundedBatchedCanonicalAndReadOnly() {
        database.planEntryDao().insert(plan("activity-plan", activity = "no-live", day = "2026-08-20"))
        database.planEntryDao().insert(plan("sequence-plan", sequence = "sequence-one", day = "2026-08-20"))
        live.completeNoLiveActivityFromSnapshot(
            snapshotId = ActivitySnapshotId("no-live"),
            completedAt = Instant.parse("2026-08-20T08:00:00Z"),
            zoneId = ZoneOffset.UTC,
            createdAt = Instant.parse("2026-08-20T08:00:00Z"),
        )
        val sequence =
            live.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-one"),
                Instant.parse("2026-08-20T09:00:00Z"),
                Instant.parse("2026-08-20T09:00:00Z"),
                ZoneOffset.UTC,
            )
        live.completeCurrentSequenceStep(
            sequence.execution.currentOccurrenceId!!,
            Instant.parse("2026-08-20T09:30:00Z"),
        )
        live.startStandaloneTimedActivityFromSnapshot(
            ActivitySnapshotId("stopwatch"),
            Instant.parse("2026-08-20T10:00:00Z"),
            Instant.parse("2026-08-20T10:00:00Z"),
            ZoneOffset.UTC,
        )
        observedSql.clear()

        val result = read("2026-08-20")

        assertEquals(2, result.dayPlans.size)
        assertEquals(2, result.completedHistory.size)
        assertTrue(result.active is DailyActive.Activity)
        val sql = observedSql.map(String::lowercase)
        assertEquals(3, sql.count { "from plan_entries" in it && "status in ('planned', 'fulfilled')" in it })
        assertTrue(sql.any { "from activity_executions" in it && "limit ?" in it })
        assertTrue(sql.any { "from sequence_executions" in it && "limit ?" in it })
        assertTrue(sql.any { "from active_session" in it && "singleton_id" in it })
        assertFalse(sql.any { "from sequence_occurrences" in it })
        assertFalse(
            sql.any { statement ->
                statement.trimStart().startsWith("insert ") ||
                    statement.trimStart().startsWith("update ") ||
                    statement.trimStart().startsWith("delete ")
            },
        )
    }

    @Test
    fun projectedLivePlanWithoutCanonicalPointerFailsInsteadOfInventingAnActiveSlice() {
        database.planEntryDao().insert(plan("orphan", activity = "stopwatch", day = "2026-08-20"))
        live.startActivityFromPlan(
            PlanEntryId("orphan"),
            Instant.parse("2026-08-20T10:00:00Z"),
            Instant.parse("2026-08-20T10:00:00Z"),
            ZoneOffset.UTC,
        )
        database.openHelper.writableDatabase.execSQL("DELETE FROM active_session")

        assertThrows(IllegalArgumentException::class.java) { read("2026-08-20") }
    }

    @Test
    fun oneReadTransactionCannotMixTwoSidesOfAtomicPlanFulfillment() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "daily-snapshot-${System.nanoTime()}"
        fileDatabaseName = name
        database.close()
        context.deleteDatabase(name)
        val historyReached = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        var blockHistory = false
        database =
            LifeTracingDatabase
                .builder(context, name)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCallback(
                    { sql, _ ->
                        if (blockHistory && "context_type = 'STANDALONE' AND status = 'COMPLETED'" in sql) {
                            historyReached.countDown()
                            check(writerFinished.await(10, TimeUnit.SECONDS))
                        }
                    },
                    Executor { it.run() },
                ).allowMainThreadQueries()
                .build()
        LiveRuntimeTestFixtures(database).apply {
            seedSeries()
            activity("stopwatch", "STOPWATCH")
        }
        live = liveRepository()
        daily = DailyReadRepository(database, CurrentZoneIdProvider { ZoneOffset.UTC }, live)
        database.planEntryDao().insert(plan("plan", activity = "stopwatch", day = "2026-08-20"))
        live.startActivityFromPlan(
            PlanEntryId("plan"),
            Instant.parse("2026-08-20T10:00:00Z"),
            Instant.parse("2026-08-20T10:00:00Z"),
            ZoneOffset.UTC,
        )
        val writerDatabase =
            LifeTracingDatabase
                .builder(context, name)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .allowMainThreadQueries()
                .build()
        val readerExecutor = Executors.newSingleThreadExecutor()
        try {
            blockHistory = true
            val result = readerExecutor.submit<com.alexandr5476.lifetracing.domain.DailyRead> { read("2026-08-20") }
            assertTrue(historyReached.await(10, TimeUnit.SECONDS))
            LiveSessionRepository.create(writerDatabase).completeActiveActivity(Instant.parse("2026-08-20T11:00:00Z"))
            writerFinished.countDown()

            val coherentOldSide = result.get(10, TimeUnit.SECONDS)
            assertEquals(
                PlanEntryStatus.PLANNED,
                coherentOldSide.dayPlans
                    .single()
                    .plan.status,
            )
            assertTrue(coherentOldSide.dayPlans.single().engaged)
            assertTrue(coherentOldSide.active is DailyActive.Activity)
            assertTrue(coherentOldSide.completedHistory.isEmpty())

            blockHistory = false
            val committed = read("2026-08-20")
            assertEquals(
                PlanEntryStatus.FULFILLED,
                committed.dayPlans
                    .single()
                    .plan.status,
            )
            assertNull(committed.active)
            assertEquals(1, committed.completedHistory.size)
        } finally {
            writerFinished.countDown()
            readerExecutor.shutdownNow()
            writerDatabase.close()
        }
    }

    private fun read(date: String) =
        daily.getDaily(
            DailyQuery(
                LocalDate.parse(date),
                LocalDate
                    .parse(date)
                    .plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant(),
                20,
            ),
        )

    private fun readSequence() = read("2026-08-20").active as DailyActive.Sequence

    private fun plan(
        id: String,
        activity: String? = null,
        sequence: String? = null,
        precision: String = "DAY",
        day: String? = null,
        week: String? = null,
        month: String? = null,
        scheduledAt: Instant? = null,
        source: String? = null,
        revision: Long? = null,
    ) = PlanEntryEntity(
        id,
        if (activity != null) "ACTIVITY" else "SEQUENCE",
        source.takeIf { activity != null },
        source.takeIf { sequence != null },
        revision,
        activity,
        sequence,
        precision,
        day.takeIf { scheduledAt == null },
        week,
        month,
        scheduledAt?.toEpochMilli(),
        scheduledAt?.let { "UTC" },
        "PLANNED",
        null,
        null,
        0,
        0,
        null,
        null,
    )

    private fun liveRepository(): LiveSessionRepository {
        var activity = 0
        var pause = 0
        var sequence = 0
        var occurrence = 0
        var interval = 0
        var snapshot = 0
        var field = 0
        var option = 0
        return LiveSessionRepository(
            database,
            { ActivityExecutionId("daily-activity-${++activity}") },
            { ActivityExecutionPauseId("daily-pause-${++pause}") },
            { SequenceExecutionId("daily-sequence-${++sequence}") },
            { SequenceOccurrenceId("daily-occurrence-${++occurrence}") },
            { SequenceIntervalId("daily-interval-${++interval}") },
            ActivitySnapshotFactory(
                { ActivitySnapshotId("daily-runtime-snapshot-${++snapshot}") },
                { ActivitySnapshotFieldId("daily-runtime-field-${++field}") },
                { ActivitySnapshotCategoryOptionId("daily-runtime-option-${++option}") },
            ),
        )
    }

    private fun activityCommands() =
        ActivityCommandRepository(
            database,
            live,
            ActivitySnapshotFactory(
                { ActivitySnapshotId("unused-snapshot") },
                { ActivitySnapshotFieldId("unused-field") },
                { ActivitySnapshotCategoryOptionId("unused-option") },
            ),
            { ActivityExecutionId("unused-execution") },
        )

    private fun oneOff() =
        ActivitySnapshotDraft(
            "Runtime one-off",
            "Runtime note",
            TimeTrackingMode.STOPWATCH,
            null,
            fields =
                listOf(
                    ActivitySnapshotFieldDraft(
                        DraftIdentity.New("value"),
                        null,
                        0,
                        "Value",
                        type = CustomFieldType.NUMBER,
                    ),
                ),
        )

    private fun insertSourceTemplate() {
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    "source",
                    "Source title",
                    "Source note",
                    "NO_LIVE_TRACKING",
                    null,
                    "activity-series",
                    1,
                    0,
                    0,
                    null,
                    null,
                ),
                ActivityTemplateSettingsEntity("source"),
                userState = ActivityTemplateUserStateEntity("source", null, null),
            ),
        )
    }

    private fun insertLinkedSnapshot() {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    "linked-snapshot",
                    "Frozen title",
                    "Frozen note",
                    "NO_LIVE_TRACKING",
                    null,
                    "source",
                    1,
                    "activity-series",
                    false,
                    0,
                ),
                ActivitySnapshotSettingsEntity("linked-snapshot"),
            ),
        )
    }
}
