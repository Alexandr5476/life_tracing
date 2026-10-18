@file:Suppress("TooManyFunctions", "LargeClass", "LongMethod", "MaxLineLength")
// Physical v8 sources exercise the production v8 -> v9 -> v10 path and fresh canonical readers.

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceState
import com.alexandr5476.lifetracing.domain.ActiveSequenceStateResolver
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequenceRead
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryActualValue
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class HistoricalActiveProductionMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "s3-active-v8-" + System.nanoTime()
    private var reference: LifeTracingDatabase? = null
    private val database get() = requireNotNull(reference)
    private var liveReference: LiveSessionRepository? = null
    private val live get() = requireNotNull(liveReference)
    private var original = emptyMap<String, List<List<String?>>>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @After
    fun cleanUp() {
        close()
        context.deleteDatabase(name)
    }

    @Test
    fun noActiveV8SourceRemainsEmptyAfterUpgradeAndColdRecovery() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.EMPTY))
        assertTrue(live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.isEmpty())
        reopen()
        assertRecovered(fixture)
    }

    @Test
    fun unownedUnfinishedV8ExecutionNeverSynthesizesAnActivePointer() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.UNOWNED))
        assertEquals(fixture.standalone, standalone())
        assertTrue(live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.isEmpty())
        reopen()
        assertRecovered(fixture)
        assertEquals(fixture.standalone, standalone())
    }

    @Test
    fun runningV8TimerRecoversAndCompletesExactlyAtPauseAdjustedMillisecondDeadline() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.ACTIVITY_RUNNING))
        assertEquals(Duration.ofSeconds(29), display(fixture.runtime!!, fixture.at(39)).activeElapsed(0))
        assertActivityCompletion(fixture, fixture.at(40))
    }

    @Test
    fun lateV8TimerRecoveryKeepsMissingActualAndUsesLogicalCompletionTime() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.ACTIVITY_RUNNING, zeroActual = false))
        assertActivityCompletion(fixture, fixture.at(1_000))
    }

    @Test
    fun pausedV8ActivityPreservesOpenPauseAndAdvancesOnlyAfterExplicitResume() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.ACTIVITY_PAUSED))
        assertNull(NextRuntimeDeadlineResolver.resolve(fixture.runtime!!))
        assertEquals(Duration.ofSeconds(10), display(fixture.runtime, fixture.at(1_000)).activeElapsed(0))
        assertTrue(live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.isEmpty())
        reopen()
        assertRecovered(fixture)
        live.resumeActiveActivity(fixture.at(100))
        val resumed = live.getActiveRuntime() as ActiveActivityRuntime
        assertEquals(
            fixture.at(100),
            resumed.execution.pauses
                .single()
                .endedAt,
        )
        assertEquals(
            fixture.standalone.pauses
                .single()
                .id,
            resumed.execution.pauses
                .single()
                .id,
        )
        assertEquals(fixture.at(120), NextRuntimeDeadlineResolver.resolve(resumed)?.at)
        assertTrue(live.reconcileActiveSession(fixture.at(120).minusMillis(1)).appliedEvents.isEmpty())
        assertEquals(1, live.reconcileActiveSession(fixture.at(120)).appliedEvents.size)
        val expected =
            resumed.execution.copy(
                status = ActivityExecutionStatus.COMPLETED,
                completedAt = fixture.at(120),
                activeDuration = Duration.ofSeconds(30),
                updatedAt = fixture.at(120),
            )
        assertEquals(expected, standalone())
        assertStableTerminal(fixture.at(1_000))
        assertActivityHistoryAndStatistics(fixture, expected)
    }

    @Test
    fun frozenV8OvertimeTimerRemainsRunningBeyondZero() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.ACTIVITY_RUNNING, overtime = true))
        assertNull(NextRuntimeDeadlineResolver.resolve(fixture.runtime!!))
        assertTrue(live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.isEmpty())
        assertEquals(Duration.ofSeconds(990), display(fixture.runtime, fixture.at(1_000)).activeElapsed(0))
        assertEquals(Duration.ofSeconds(960), display(fixture.runtime, fixture.at(1_000)).timerOvertime(0))
        reopen()
        assertRecovered(fixture)
    }

    @Test
    fun runningV8SequencePreservesChildAndReconcilesEachBoundaryOnceThroughTerminalHistory() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.SEQUENCE_RUNNING))
        assertEquals(fixture.at(70), NextRuntimeDeadlineResolver.resolve(fixture.runtime!!)?.at)
        assertTrue(live.reconcileActiveSession(fixture.at(70).minusMillis(1)).appliedEvents.isEmpty())
        assertRecovered(fixture)
        val events = live.reconcileActiveSession(fixture.at(70)).appliedEvents
        assertEquals(listOf(fixture.at(70)), events.map { it.deadline.at })
        assertEquals(RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO, events.single().deadline.kind)
        val advanced = live.getActiveRuntime() as ActiveSequenceRuntime
        assertNull(advanced.currentChild)
        assertNull(advanced.execution.currentOccurrenceId)
        assertEquals(SequenceOccurrenceId("occurrence-2"), advanced.transitionCountdownTargetId)
        assertEquals(fixture.at(70), child("child-1").completedAt)
        assertEquals(OccurrenceCompletionReason.NATURAL_TIMER_END, advanced.execution.occurrences[1].completionReason)
        assertAdvancedStable(advanced, fixture.at(70).plusMillis(1))
        val caughtUp = live.reconcileActiveSession(fixture.at(1_000)).appliedEvents
        assertEquals(listOf(80L, 110L, 115L, 145L).map(fixture::at), caughtUp.map { it.deadline.at })
        assertSequenceTerminal(fixture, 145)
    }

    @Test
    fun pausedV8SequenceKeepsCurrentChildPauseAndItsSuspendedTimerDeadline() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.SEQUENCE_PAUSED))
        assertNull(NextRuntimeDeadlineResolver.resolve(fixture.runtime!!))
        assertEquals(Duration.ofSeconds(40), display(fixture.runtime, fixture.at(1_000)).activeElapsed(0))
        assertTrue(live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.isEmpty())
        reopen()
        assertRecovered(fixture)
        live.resumeActiveSequence(fixture.at(100))
        val resumed = live.getActiveRuntime() as ActiveSequenceRuntime
        assertEquals(fixture.currentChild!!.id, resumed.currentChild?.id)
        assertEquals(
            fixture.currentChild.pauses
                .single()
                .id,
            resumed.currentChild
                ?.pauses
                ?.single()
                ?.id,
        )
        assertEquals(
            fixture.at(100),
            resumed.currentChild
                ?.pauses
                ?.single()
                ?.endedAt,
        )
        assertEquals(fixture.at(120), NextRuntimeDeadlineResolver.resolve(resumed)?.at)
        assertTrue(live.reconcileActiveSession(fixture.at(120).minusMillis(1)).appliedEvents.isEmpty())
        assertEquals(
            listOf(fixture.at(120)),
            live.reconcileActiveSession(fixture.at(120)).appliedEvents.map {
                it.deadline.at
            },
        )
        live.reconcileActiveSession(fixture.at(1_000))
        assertSequenceTerminal(fixture, 195)
    }

    @Test
    fun waitingNextV8SequenceKeepsGlobalIdleAndCanContinueInFrozenRepeatOrder() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.WAITING_NEXT))
        assertNull(NextRuntimeDeadlineResolver.resolve(fixture.runtime!!))
        assertTrue(live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.isEmpty())
        reopen()
        assertRecovered(fixture)
        for ((start, end) in listOf(100L to 140L, 200L to 240L, 300L to 335L)) {
            live.startNextSequenceStep(fixture.at(start))
            live.reconcileActiveSession(fixture.at(end))
            if (end != 335L) {
                val waiting = live.getActiveRuntime() as ActiveSequenceRuntime
                assertEquals(ActiveSequenceState.WAITING_NEXT, ActiveSequenceStateResolver.resolve(waiting))
                assertNull(waiting.currentChild)
                assertEquals(
                    SequenceIntervalKind.IMPLICIT_IDLE,
                    waiting.execution.intervals
                        .single {
                            it.endedAt == null
                        }.kind,
                )
                assertAdvancedStable(waiting, fixture.at(end + 1))
            }
        }
        assertSequenceTerminal(fixture, 335)
    }

    @Test
    fun noCurrentV8CountdownStartsOneChildExactlyAtItsLogicalDeadline() = assertCountdownRecovery(40)

    @Test
    fun lateNoCurrentV8CountdownStartsOneChildAtLogicalTimeAndDoesNotReplay() = assertCountdownRecovery(45)

    @Test
    fun pausedNoCurrentV8CountdownRetainsTargetAndConsumedProgressUntilResume() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.PAUSED_TRANSITION))
        assertNull(NextRuntimeDeadlineResolver.resolve(fixture.runtime!!))
        assertTrue(live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.isEmpty())
        reopen()
        assertRecovered(fixture)
        live.resumeActiveSequence(fixture.at(100))
        val resumed = live.getActiveRuntime() as ActiveSequenceRuntime
        assertNull(resumed.currentChild)
        assertEquals(SequenceOccurrenceId("occurrence-1"), resumed.transitionCountdownTargetId)
        // Four of ten seconds were consumed in the authentic source.
        assertEquals(fixture.at(106), NextRuntimeDeadlineResolver.resolve(resumed)?.at)
        assertTrue(live.reconcileActiveSession(fixture.at(106).minusMillis(1)).appliedEvents.isEmpty())
        assertEquals(
            listOf(fixture.at(106)),
            live.reconcileActiveSession(fixture.at(106)).appliedEvents.map {
                it.deadline.at
            },
        )
        val advanced = live.getActiveRuntime() as ActiveSequenceRuntime
        assertEquals(fixture.at(106), advanced.currentChild?.startedAt)
        assertAdvancedStable(advanced, fixture.at(106).plusMillis(1))
        live.reconcileActiveSession(fixture.at(1_000))
        assertSequenceTerminal(fixture, 211)
    }

    @Test
    fun lateRecoveredTerminalSlotFailureRollsBackRootChildrenOccurrencesAndIntervalsBeforeRetry() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.SEQUENCE_RUNNING))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_s3_terminal BEFORE DELETE ON active_session BEGIN SELECT RAISE(ABORT, 's3 late slot failure'); END",
        )
        val failure =
            assertThrows(SQLiteConstraintException::class.java) { live.reconcileActiveSession(fixture.at(1_000)) }
        assertTrue(failure.message.orEmpty().contains("s3 late slot failure"))
        reopen()
        assertRecovered(fixture)
        // Entire historical fact comparison also proves no new child, interval or occurrence survived.
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_s3_terminal")
        val retried = live.reconcileActiveSession(fixture.at(1_000)).appliedEvents
        assertEquals(listOf(70L, 80L, 110L, 115L, 145L).map(fixture::at), retried.map { it.deadline.at })
        assertSequenceTerminal(fixture, 145)
    }

    @Test
    fun lateRecoveredCountdownSlotFailureRollsBackNewChildAndPreservesNoCurrentOwnership() {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.TRANSITION))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_s3_advance BEFORE UPDATE ON active_session BEGIN SELECT RAISE(ABORT, 's3 late advance failure'); END",
        )
        val failure =
            assertThrows(SQLiteConstraintException::class.java) { live.reconcileActiveSession(fixture.at(40)) }
        assertTrue(failure.message.orEmpty().contains("s3 late advance failure"))
        reopen()
        assertRecovered(fixture)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_s3_advance")
        assertEquals(1, live.reconcileActiveSession(fixture.at(40)).appliedEvents.size)
        val advanced = live.getActiveRuntime() as ActiveSequenceRuntime
        assertEquals(SequenceOccurrenceId("occurrence-1"), advanced.currentChild?.sequenceOccurrenceId)
        assertEquals(2, database.activityExecutionDao().getSequenceChildAggregates("sequence").size)
        assertAdvancedStable(advanced, fixture.at(40).plusMillis(1))
    }

    private fun assertCountdownRecovery(delivery: Long) {
        val fixture = recover(HistoricalActiveV8Fixture(HistoricalActiveV8State.TRANSITION))
        val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(fixture.runtime!!))
        assertEquals(fixture.at(40), deadline.at)
        assertEquals(SequenceOccurrenceId("occurrence-1"), deadline.expectedOccurrenceId)
        assertEquals(RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN, deadline.kind)
        assertTrue(live.reconcileActiveSession(fixture.at(40).minusMillis(1)).appliedEvents.isEmpty())
        assertRecovered(fixture)
        assertEquals(
            listOf(deadline),
            live.reconcileActiveSession(fixture.at(delivery)).appliedEvents.map {
                it.deadline
            },
        )
        val advanced = live.getActiveRuntime() as ActiveSequenceRuntime
        assertEquals(SequenceOccurrenceId("occurrence-1"), advanced.execution.currentOccurrenceId)
        assertEquals(fixture.at(40), advanced.currentChild?.startedAt)
        assertEquals(fixture.completedChild, child("child-0"))
        assertEquals(2, database.activityExecutionDao().getSequenceChildAggregates("sequence").size)
        assertAdvancedStable(advanced, fixture.at(delivery).plusMillis(1))
        live.reconcileActiveSession(fixture.at(1_000))
        assertSequenceTerminal(fixture, 145)
    }

    private fun assertActivityCompletion(
        fixture: HistoricalActiveV8Fixture,
        delivery: Instant,
    ) {
        val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(fixture.runtime!!))
        assertEquals(fixture.at(40), deadline.at)
        assertTrue(live.reconcileActiveSession(deadline.at.minusMillis(1)).appliedEvents.isEmpty())
        assertRecovered(fixture)
        val event = live.reconcileActiveSession(delivery).appliedEvents.single()
        assertEquals(deadline, event.deadline)
        assertFalse(event.soundEnabled)
        assertTrue(event.vibrationEnabled)
        val expected =
            fixture.standalone.copy(
                status = ActivityExecutionStatus.COMPLETED,
                completedAt = fixture.at(40),
                activeDuration = Duration.ofSeconds(30),
                updatedAt = fixture.at(40),
            )
        assertEquals(expected, standalone())
        assertStableTerminal(delivery.plusMillis(1))
        assertEquals(expected, standalone())
        assertEquals(
            1,
            database.openHelper.readableDatabase
                .factRows("activity_executions")
                .size,
        )
        assertFrozenFacts()
        assertActivityHistoryAndStatistics(fixture, expected)
    }

    private fun assertActivityHistoryAndStatistics(
        fixture: HistoricalActiveV8Fixture,
        execution: ActivityExecution,
    ) {
        val history = HistoryReadRepository(database)
        val root =
            history
                .getCompletedRoots(
                    CompletedHistoryQuery(HistoryDateRange(fixture.date, fixture.date), 5),
                ).single() as CompletedActivityHistoryRoot
        assertEquals(execution.id, root.executionId)
        assertEquals(execution.snapshotId, root.snapshotId)
        assertEquals(execution.completedAt, root.completedAt)
        assertEquals(fixture.zone, root.originalZoneId)
        assertEquals(540, root.originalUtcOffsetMinutes)
        assertEquals(fixture.date, root.primaryLocalDate)
        val detail = requireNotNull(history.getActivityDetail(execution.id))
        val actual = (execution.values.singleOrNull() as? NumberExecutionValue)?.scaledValue
        assertEquals(
            actual?.let(ActivityHistoryActualValue::Number) ?: ActivityHistoryActualValue.Missing,
            detail.fields[0].actualValue,
        )
        assertEquals(ActivityHistoryActualValue.Missing, detail.fields[1].actualValue)
        assertEquals(ActivityHistoryActualValue.Missing, detail.fields[2].actualValue)
        val statistics = statistics()
        assertEquals(1L, statistics.global(StatisticsPeriod.AllTime).topLevelExecutionCount)
        assertEquals(Duration.ofSeconds(30), statistics.global(StatisticsPeriod.AllTime).totalTrackedDuration)
        assertEquals(
            1L,
            statistics.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime).executionCount,
        )
        val number =
            statistics.numberFieldStatistics(
                StatisticsSeriesId("activity-series"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId("number")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(if (actual == null) 0L else 1L, number.recordedCount)
        assertEquals(if (actual == null) 1L else 0L, number.missingCount)
        assertEquals(actual, number.values.minimumScaled)
        assertEquals(listOf(root), daily(fixture).completedHistory)
    }

    private fun assertSequenceTerminal(
        fixture: HistoricalActiveV8Fixture,
        end: Long,
    ) {
        val terminal = requireNotNull(database.sequenceExecutionDao().getAggregate("sequence")).toDomain()
        assertEquals(SequenceExecutionStatus.COMPLETED, terminal.status)
        assertEquals(fixture.sequenceId, terminal.id)
        assertEquals(fixture.sequence.id, terminal.snapshotId)
        assertEquals(fixture.at(end), terminal.endedAt)
        assertEquals(Duration.ofSeconds(120), terminal.activeDuration)
        assertEquals(Duration.ofSeconds(end - 120), terminal.pauseDuration)
        assertEquals(Duration.ofSeconds(end), terminal.wallDuration)
        assertNull(terminal.currentOccurrenceId)
        assertEquals(
            (fixture.runtime as ActiveSequenceRuntime).execution.occurrences.map {
                it.copy(
                    status = RuntimeOccurrenceStatus.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                    completionReason = null,
                )
            },
            terminal.occurrences.map {
                it.copy(
                    status = RuntimeOccurrenceStatus.NOT_STARTED,
                    enteredAt = null,
                    completedAt = null,
                    completionReason = null,
                )
            },
        )
        assertTrue(terminal.occurrences.all { it.status == RuntimeOccurrenceStatus.COMPLETED })
        assertTrue(terminal.occurrences.all { it.completionReason == OccurrenceCompletionReason.NATURAL_TIMER_END })
        assertTrue(terminal.intervals.none { it.endedAt == null })
        val children = database.activityExecutionDao().getSequenceChildAggregates("sequence").map { it.toDomain() }
        assertEquals(4, children.size)
        assertEquals(4, children.map { it.id }.distinct().size)
        assertEquals(terminal.occurrences.map { it.id }.toSet(), children.map { it.sequenceOccurrenceId }.toSet())
        assertEquals(fixture.completedChild, children.single { it.id == fixture.completedChild.id })
        fixture.currentChild?.let {
            assertEquals(
                it.id,
                children
                    .single { child ->
                        child.sequenceOccurrenceId ==
                            it.sequenceOccurrenceId
                    }.id,
            )
        }
        children.forEach { child ->
            val occurrence = terminal.occurrences.single { it.id == child.sequenceOccurrenceId }
            assertEquals(fixture.sequenceId, child.sequenceExecutionId)
            assertEquals(occurrence.activitySnapshotId, child.snapshotId)
            assertEquals(occurrence.enteredAt, child.startedAt)
            assertEquals(occurrence.completedAt, child.completedAt)
            assertEquals(Duration.ofSeconds(30), child.activeDuration)
            assertEquals(fixture.zone, child.originalZoneId)
            assertEquals(540, child.originalUtcOffsetMinutes)
            assertEquals(fixture.date, child.primaryLocalDate)
        }
        assertStableTerminal(fixture.at(1_000))
        assertEquals(terminal, database.sequenceExecutionDao().getAggregate("sequence")?.toDomain())
        assertEquals(
            children,
            database.activityExecutionDao().getSequenceChildAggregates("sequence").map {
                it.toDomain()
            },
        )
        assertFrozenFacts()
        val history = HistoryReadRepository(database)
        val root =
            history
                .getCompletedRoots(
                    CompletedHistoryQuery(HistoryDateRange(fixture.date, fixture.date), 5),
                ).single() as CompletedSequenceHistoryRoot
        assertEquals(fixture.sequenceId, root.executionId)
        assertEquals(terminal.endedAt, root.completedAt)
        assertEquals(fixture.zone, root.originalZoneId)
        assertEquals(540, root.originalUtcOffsetMinutes)
        val detail = requireNotNull(history.getSequenceDetail(fixture.sequenceId))
        assertEquals(terminal.intervals, detail.intervals)
        assertEquals(fixture.sequence.settings, detail.settings)
        assertEquals(SequenceHistoryActualValue.Number(0), detail.fields[0].actualValue)
        assertEquals(SequenceHistoryActualValue.Missing, detail.fields[1].actualValue)
        assertEquals(children.map { it.id }.toSet(), detail.occurrences.map { it.child?.executionId }.toSet())
        assertEquals(listOf(root), daily(fixture).completedHistory)
        val statistics = statistics()
        val global = statistics.global(StatisticsPeriod.AllTime)
        assertEquals(1L, global.topLevelExecutionCount)
        assertEquals(Duration.ofSeconds(120), global.totalTrackedDuration)
        assertEquals(terminal.pauseDuration, global.totalSequencePauseIdleDuration)
        assertEquals(
            4L,
            statistics.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime).executionCount,
        )
        assertEquals(
            Duration.ofSeconds(120),
            statistics.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime).durations.total,
        )
        assertEquals(
            1L,
            statistics.sequenceSeries(StatisticsSeriesId("sequence-series"), StatisticsPeriod.AllTime).executionCount,
        )
    }

    private fun recover(fixture: HistoricalActiveV8Fixture): HistoricalActiveV8Fixture {
        LifeTracingMigrationTestDatabaseFactory.createVersion8(helper, name).use { source ->
            assertEquals(8, source.version)
            source.execSQL("PRAGMA foreign_keys = ON")
            source.beginTransaction()
            try {
                fixture.seed(source)
                source.setTransactionSuccessful()
            } finally {
                source.endTransaction()
            }
            assertForeignKeys(source)
            assertTrue(
                source.query("SELECT name FROM sqlite_master WHERE name = 'plan_entries'").use {
                    !it.moveToFirst()
                },
            )
            original = source.readCurrentIntegrity().keys.associateWith { source.factRows(it) }
        }
        // Historical connection is closed before the registered production migration chain executes.
        reopen()
        assertOriginalFacts()
        close()
        // Drop both Room and repository owners after upgrading the physical file.
        reopen()
        assertRecovered(fixture)
        return fixture
    }

    private fun assertRecovered(fixture: HistoricalActiveV8Fixture) {
        assertEquals(fixture.runtime, live.getActiveRuntime())
        assertEquals(fixture.runtime?.session, live.getActiveSession())
        val projection = daily(fixture)
        assertEquals(fixture.runtime, projection.active?.runtime)
        assertTrue(projection.dayPlans.isEmpty())
        assertTrue(projection.weekPlans.isEmpty())
        if (fixture.runtime is ActiveSequenceRuntime) {
            val expectedState =
                when (fixture.state) {
                    HistoricalActiveV8State.SEQUENCE_RUNNING -> ActiveSequenceState.RUNNING_CURRENT
                    HistoricalActiveV8State.SEQUENCE_PAUSED -> ActiveSequenceState.PAUSED_CURRENT
                    HistoricalActiveV8State.WAITING_NEXT -> ActiveSequenceState.WAITING_NEXT
                    HistoricalActiveV8State.TRANSITION -> ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN
                    HistoricalActiveV8State.PAUSED_TRANSITION -> ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
                    else -> error("Not a Sequence fixture")
                }
            val collapsed = projection.active as DailyActive.Sequence
            assertEquals(expectedState, collapsed.state)
            assertEquals(fixture.runtime.execution.currentOccurrenceId, collapsed.current?.occurrence?.id)
            assertEquals(
                if (fixture.hasCurrent) SequenceOccurrenceId("occurrence-2") else SequenceOccurrenceId("occurrence-1"),
                collapsed.next?.occurrence?.id,
            )
            val expanded = (live.getExpandedSequence(fixture.sequenceId) as ExpandedLiveSequenceRead.Active).value
            assertEquals(fixture.runtime, expanded.runtime)
            assertEquals(expectedState, expanded.state)
            assertEquals(fixture.runtime.execution.occurrences, expanded.occurrences.map { it.occurrence })
            assertEquals(fixture.completedChild, expanded.occurrences[0].childExecution)
            assertEquals(fixture.currentChild, expanded.occurrences[1].childExecution)
            assertNull(expanded.occurrences[2].childExecution)
            assertNull(expanded.occurrences[3].childExecution)
            assertEquals(Duration.ofSeconds(10), expanded.occurrences[1].effectiveSettings.startCountdown)
            assertTrue(expanded.occurrences[3].occurrence.isRuntimeAdded)
            assertNull(expanded.occurrences[3].occurrence.sourceSequenceSnapshotNodeId)
        }
        // These reads deliberately occur well after stored deadlines, yet are write-free.
        assertOriginalFacts()
        assertForeignKeys(database.openHelper.readableDatabase)
    }

    private fun assertAdvancedStable(
        runtime: ActiveRuntime,
        now: Instant,
    ) {
        val facts = currentFacts()
        assertTrue(live.reconcileActiveSession(now).appliedEvents.isEmpty())
        assertEquals(facts, currentFacts())
        reopen()
        assertEquals(runtime, live.getActiveRuntime())
        assertEquals(facts, currentFacts())
        assertTrue(live.reconcileActiveSession(now).appliedEvents.isEmpty())
        assertEquals(facts, currentFacts())
    }

    private fun assertStableTerminal(now: Instant) {
        val facts = currentFacts()
        assertNull(live.getActiveRuntime())
        assertNull(live.getActiveSession())
        assertTrue(live.reconcileActiveSession(now).appliedEvents.isEmpty())
        assertEquals(facts, currentFacts())
        reopen()
        assertNull(live.getActiveRuntime())
        assertNull(live.getActiveSession())
        assertTrue(live.reconcileActiveSession(now).appliedEvents.isEmpty())
        assertEquals(facts, currentFacts())
    }

    private fun daily(fixture: HistoricalActiveV8Fixture) =
        DailyReadRepository(
            database,
            CurrentZoneIdProvider {
                fixture.zone
            },
        ).getDaily(DailyQuery(fixture.date, fixture.at(1_000), 5))

    private fun display(
        runtime: ActiveRuntime,
        now: Instant,
    ) = RuntimeDisplayBaseline.capture(runtime, WallMonotonicAnchor(now, 0), 0)

    private fun standalone() = child("standalone")

    private fun child(id: String) = requireNotNull(database.activityExecutionDao().getAggregate(id)).toDomain()

    private fun statistics() = StatisticsRepository(database) { error("Read must not create a Series") }

    private fun assertOriginalFacts() {
        original.forEach { (table, rows) ->
            assertEquals(
                "Historical facts changed in $table",
                rows,
                database.openHelper.readableDatabase.factRows(table),
            )
        }
        assertTrue(
            database.openHelper.readableDatabase
                .factRows("plan_entries")
                .isEmpty(),
        )
    }

    private fun assertFrozenFacts() {
        original.filterKeys { it !in mutableRuntimeTables }.forEach { (table, rows) ->
            assertEquals("Frozen facts changed in $table", rows, database.openHelper.readableDatabase.factRows(table))
        }
    }

    private fun currentFacts() =
        (original.keys + "plan_entries").associateWith {
            database.openHelper.readableDatabase.factRows(it)
        }

    private fun SupportSQLiteDatabase.factRows(table: String): List<List<String?>> =
        query("SELECT * FROM `$table` ORDER BY 1, 2").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        List(cursor.columnCount) {
                            if (cursor.isNull(it)) null else cursor.getString(it)
                        },
                    )
                }
            }
        }

    private fun assertForeignKeys(sqlite: SupportSQLiteDatabase) {
        sqlite.query("PRAGMA foreign_key_check").use { assertFalse("Foreign-key violations", it.moveToFirst()) }
    }

    private fun close() {
        liveReference = null
        reference?.close()
        reference = null
    }

    private fun reopen() {
        close()
        reference = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
        assertTrue(context.getDatabasePath(name).isFile)
        database.openHelper.writableDatabase.assertCurrentIntegrity()
        liveReference = LiveSessionRepository.create(database)
    }

    private companion object {
        val mutableRuntimeTables =
            setOf(
                "active_session",
                "activity_executions",
                "activity_execution_pauses",
                "activity_execution_field_values",
                "sequence_executions",
                "sequence_occurrences",
                "sequence_intervals",
                "sequence_execution_field_values",
            )
    }
}
