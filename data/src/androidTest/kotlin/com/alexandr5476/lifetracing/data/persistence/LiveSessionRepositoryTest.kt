package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class LiveSessionRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var repository: LiveSessionRepository

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        seed(database)
        repository = repository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun standaloneStartPauseResumeCompleteIsOneCoordinatedState() {
        val execution =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("stopwatch"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        assertEquals(
            "RUNNING",
            database
                .activeSessionDao()
                .get()
                ?.state
                ?.name,
        )

        repository.pauseActiveActivity(ActivityExecutionPauseId("manual-pause"), instant(10))
        assertEquals(ActivityExecutionStatus.PAUSED, repositoryExecution(execution.id.value).status)
        assertEquals(
            "PAUSED",
            database
                .activeSessionDao()
                .get()
                ?.state
                ?.name,
        )

        repository.resumeActiveActivity(instant(20))
        repository.completeActiveActivity(instant(30))

        val completed = repositoryExecution(execution.id.value)
        assertEquals(ActivityExecutionStatus.COMPLETED, completed.status)
        assertEquals(20_000L, completed.activeDuration?.toMillis())
        assertNull(database.activeSessionDao().get())
    }

    @Test
    fun timerReconcilesAtExactDeadlineAndSecondStartCreatesNothing() {
        val execution =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("timer"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        assertThrows(IllegalArgumentException::class.java) {
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("stopwatch"),
                instant(1),
                instant(1),
                ZoneOffset.UTC,
            )
        }
        assertNull(database.activityExecutionDao().getById("activity-2"))

        val firstDelivery = repository.reconcileActiveSession(instant(100))
        val duplicateDelivery = repository.reconcileActiveSession(instant(100))

        assertEquals(instant(60), repositoryExecution(execution.id.value).completedAt)
        assertEquals(
            listOf(RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO),
            firstDelivery.appliedEvents.map { it.deadline.kind },
        )
        assertEquals(
            instant(60),
            firstDelivery.appliedEvents
                .single()
                .deadline.at,
        )
        assertEquals(emptyList<Any>(), duplicateDelivery.appliedEvents)
        assertNull(repository.getActiveSession())
    }

    @Test
    fun standaloneTimerPauseOvertimeAndStopwatchPoliciesAreDeterministic() {
        val pausedTimer =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("timer"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.reconcileActiveSession(instant(10))
        assertEquals(ActivityExecutionStatus.RUNNING, repositoryExecution(pausedTimer.id.value).status)
        repository.pauseActiveActivity(ActivityExecutionPauseId("timer-pause"), instant(20))
        repository.reconcileActiveSession(instant(200))
        assertEquals(ActivityExecutionStatus.PAUSED, repositoryExecution(pausedTimer.id.value).status)
        repository.resumeActiveActivity(instant(220))
        repository.reconcileActiveSession(instant(259))
        assertEquals(ActivityExecutionStatus.RUNNING, repositoryExecution(pausedTimer.id.value).status)
        repository.reconcileActiveSession(instant(260))
        assertEquals(instant(260), repositoryExecution(pausedTimer.id.value).completedAt)

        val overtime =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("timer-overtime"),
                instant(300),
                instant(300),
                ZoneOffset.UTC,
            )
        repository.reconcileActiveSession(instant(1_000))
        assertEquals(ActivityExecutionStatus.RUNNING, repositoryExecution(overtime.id.value).status)
        repository.completeActiveActivity(instant(1_000))

        val stopwatch =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("stopwatch"),
                instant(1_100),
                instant(1_100),
                ZoneOffset.UTC,
            )
        repository.reconcileActiveSession(instant(10_000))
        assertEquals(ActivityExecutionStatus.RUNNING, repositoryExecution(stopwatch.id.value).status)
    }

    @Test
    fun pausingExpiredTimerCommitsNaturalCompletionInstead() {
        val timer =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("timer"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )

        repository.pauseActiveActivity(ActivityExecutionPauseId("too-late"), instant(100))

        assertEquals(instant(60), repositoryExecution(timer.id.value).completedAt)
        assertNull(repository.getActiveSession())
    }

    @Test
    fun sequenceStartTransitionPauseResumeAndNaturalFinalCompletionStayAtomic() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val first = started.execution.currentOccurrenceId!!
        assertEquals(
            RuntimeOccurrenceStatus.CURRENT,
            started.execution.occurrences
                .first()
                .status,
        )
        assertNotNull(started.currentChild)
        assertEquals(1, started.execution.intervals.size)

        val transition = repository.completeCurrentSequenceStep(first, instant(5))
        assertEquals(
            SequenceIntervalKind.TRANSITION_COUNTDOWN,
            transition.execution.intervals
                .single {
                    it.endedAt ==
                        null
                }.kind,
        )
        repository.pauseActiveSequence(instant(10))
        assertEquals("PAUSED", repository.getActiveSession()?.state?.name)
        repository.resumeActiveSequence(instant(20))
        repository.reconcileActiveSession(instant(25))

        val running = repositorySequence(started.execution.id.value)
        assertEquals(instant(25), running.occurrences[1].enteredAt)
        val finished = repository.completeCurrentSequenceStep(running.currentOccurrenceId!!, instant(30))
        assertEquals(SequenceExecutionStatus.COMPLETED, finished.execution.status)
        assertNull(repository.getActiveSession())
        assertEquals(10_000L, finished.execution.activeDuration?.toMillis())
        assertEquals(20_000L, finished.execution.pauseDuration?.toMillis())
    }

    @Test
    fun sequenceStartModesAndEmptySequenceUseOnlyDurableRuntimeStates() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-empty"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        }
        assertNull(repository.getActiveSession())

        val timer =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-one-timer"),
                instant(10),
                instant(10),
                ZoneOffset.UTC,
            )
        assertEquals(
            RuntimeOccurrenceStatus.CURRENT,
            timer.execution.occurrences
                .single()
                .status,
        )
        assertEquals(
            SequenceIntervalKind.ACTIVE_STEP,
            timer.execution.intervals
                .single()
                .kind,
        )
        assertNotNull(timer.currentChild)
        repository.completeCurrentSequenceStep(timer.execution.currentOccurrenceId!!, instant(20))

        val noLiveActive =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-no-live-active"),
                instant(30),
                instant(30),
                ZoneOffset.UTC,
            )
        assertEquals(
            SequenceIntervalKind.ACTIVE_STEP,
            noLiveActive.execution.intervals
                .single()
                .kind,
        )
        assertNull(noLiveActive.currentChild)
        repository.completeCurrentSequenceStep(noLiveActive.execution.currentOccurrenceId!!, instant(40))

        val noLivePause =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-no-live-pause"),
                instant(50),
                instant(50),
                ZoneOffset.UTC,
            )
        assertEquals(
            SequenceIntervalKind.STEP_PAUSE,
            noLivePause.execution.intervals
                .single()
                .kind,
        )
        assertNull(noLivePause.currentChild)
        assertEquals(1, noLivePause.execution.intervals.size)
    }

    @Test
    fun staleCompletionCommitsDueNaturalTransitionWithoutTouchingNextStep() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-timer-direct"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val original = started.execution.currentOccurrenceId!!

        assertThrows(IllegalArgumentException::class.java) {
            repository.completeCurrentSequenceStep(original, instant(100))
        }

        val reconciled = repositorySequence(started.execution.id.value)
        assertEquals(instant(60), reconciled.occurrences[0].completedAt)
        assertEquals(instant(60), reconciled.occurrences[1].enteredAt)
        assertEquals(RuntimeOccurrenceStatus.CURRENT, reconciled.occurrences[1].status)
    }

    @Test
    fun persistedFileReopenCatchesUpTimerCountdownToHistoricalStopwatchStart() {
        withReopenedSequence("sequence-timer", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertEquals(instant(60), restored.occurrences[0].completedAt)
            assertEquals(instant(90), restored.occurrences[1].enteredAt)
            assertEquals(instant(90), repositoryExecution("activity-101").startedAt)
        }
    }

    @Test
    fun persistedFileReopenCatchesUpTimerDirectlyToStopwatch() {
        withReopenedSequence("sequence-timer-direct", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertEquals(instant(60), restored.occurrences[0].completedAt)
            assertEquals(instant(60), restored.occurrences[1].enteredAt)
        }
    }

    @Test
    fun persistedFileReopenCatchesUpTwoTimersAndCompletesSequence() {
        withReopenedSequence("sequence-timers", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertEquals(instant(60), restored.occurrences[0].completedAt)
            assertEquals(instant(90), restored.occurrences[1].enteredAt)
            assertEquals(instant(150), restored.occurrences[1].completedAt)
            assertEquals(instant(150), restored.endedAt)
            assertNull(repository.getActiveSession())
        }
    }

    @Test
    fun persistedFileReopenStopsCatchUpAtNoLiveStep() {
        withReopenedSequence("sequence-timer-no-live", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertEquals(instant(60), restored.occurrences[1].enteredAt)
            assertEquals(RuntimeOccurrenceStatus.CURRENT, restored.occurrences[1].status)
            assertNull(database.activityExecutionDao().getAggregateByOccurrence(restored.occurrences[1].id.value))
        }
    }

    @Test
    fun persistedFileReopenStopsAtWaitingNextWhenAutoAdvanceIsOff() {
        withReopenedSequence("sequence-waiting", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertNull(restored.currentOccurrenceId)
            assertEquals("WAITING_NEXT", repository.getActiveSession()?.state?.name)
            assertEquals(instant(60), restored.intervals.single { it.endedAt == null }.startedAt)
        }
    }

    @Test
    fun persistedFileReopenLeavesOvertimeTimerCurrent() {
        withReopenedSequence("sequence-overtime", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertEquals(RuntimeOccurrenceStatus.CURRENT, restored.occurrences[0].status)
            assertNull(restored.occurrences[0].completedAt)
            assertEquals(restored.occurrences[0].id, restored.currentOccurrenceId)
        }
    }

    @Test
    fun intervalIdCollisionRollsBackWithoutReparentingHistory() {
        val intervalIds = ArrayDeque(listOf("collision-interval", "b-initial", "collision-interval"))
        repository = repository(database, intervalIds = { SequenceIntervalId(intervalIds.removeFirst()) })
        val historical =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-one-timer"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(historical.execution.currentOccurrenceId!!, instant(10))
        val historicalInterval = database.sequenceExecutionDao().getIntervals(historical.execution.id.value).single()
        val active =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(20),
                instant(20),
                ZoneOffset.UTC,
            )
        val before = repositorySequence(active.execution.id.value)
        val sessionBefore = database.activeSessionDao().get()

        assertThrows(RuntimeException::class.java) {
            repository.completeCurrentSequenceStep(active.execution.currentOccurrenceId!!, instant(25))
        }

        assertEquals(
            historicalInterval,
            database.sequenceExecutionDao().getIntervals(historical.execution.id.value).single(),
        )
        assertEquals(before, repositorySequence(active.execution.id.value))
        assertEquals(sessionBefore, database.activeSessionDao().get())
    }

    @Test
    fun childExecutionIdCollisionRollsBackWithoutConvertingStandaloneHistory() {
        LiveRuntimeTestFixtures(database).standaloneExecution(id = "collision-child")
        val childIds = ArrayDeque(listOf("initial-child", "collision-child"))
        repository = repository(database, childIds = { ActivityExecutionId(childIds.removeFirst()) })
        val unrelated = database.activityExecutionDao().getAggregate("collision-child")
        val active =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-timer-direct"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val before = repositorySequence(active.execution.id.value)
        val sessionBefore = database.activeSessionDao().get()

        assertThrows(RuntimeException::class.java) {
            repository.completeCurrentSequenceStep(active.execution.currentOccurrenceId!!, instant(10))
        }

        assertEquals(unrelated, database.activityExecutionDao().getAggregate("collision-child"))
        assertEquals(before, repositorySequence(active.execution.id.value))
        assertEquals(sessionBefore, database.activeSessionDao().get())
    }

    @Test
    fun childPauseIdCollisionRollsBackWithoutReparentingForeignPause() {
        LiveRuntimeTestFixtures(database).standaloneExecution(
            id = "foreign-paused",
            status = "PAUSED",
            pauseId = "collision-pause",
        )
        repository = repository(database, pauseIds = { ActivityExecutionPauseId("collision-pause") })
        val foreign = database.activityExecutionDao().getAggregate("foreign-paused")
        val active =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val before = repositorySequence(active.execution.id.value)
        val childBefore =
            database.activityExecutionDao().getAggregateByOccurrence(
                active.execution.currentOccurrenceId!!.value,
            )
        val sessionBefore = database.activeSessionDao().get()

        assertThrows(RuntimeException::class.java) { repository.pauseActiveSequence(instant(10)) }

        assertEquals(foreign, database.activityExecutionDao().getAggregate("foreign-paused"))
        assertEquals(before, repositorySequence(active.execution.id.value))
        assertEquals(
            childBefore,
            database.activityExecutionDao().getAggregateByOccurrence(active.execution.currentOccurrenceId!!.value),
        )
        assertEquals(sessionBefore, database.activeSessionDao().get())
    }

    @Test
    fun pauseAfterDueTimerCommitsWaitingNextThenReportsRejection() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-waiting"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )

        assertThrows(IllegalArgumentException::class.java) { repository.pauseActiveSequence(instant(70)) }

        val persisted = repositorySequence(started.execution.id.value)
        assertEquals(instant(60), persisted.occurrences[0].completedAt)
        assertEquals(RuntimeOccurrenceStatus.COMPLETED, persisted.occurrences[0].status)
        assertEquals(SequenceExecutionStatus.RUNNING, persisted.status)
        assertNull(persisted.currentOccurrenceId)
        assertEquals(SequenceIntervalKind.IMPLICIT_IDLE, persisted.intervals.single { it.endedAt == null }.kind)
        assertEquals(instant(60), persisted.intervals.single { it.endedAt == null }.startedAt)
        assertEquals("WAITING_NEXT", repository.getActiveSession()?.state?.name)
        assertEquals(0, persisted.intervals.count { it.kind == SequenceIntervalKind.EXPLICIT_PAUSE })
        assertEquals(0, database.activityExecutionDao().getPauses("activity-1").count { it.endedAtMs == null })

        repository.reconcileActiveSession(instant(70))
        assertEquals(persisted, repositorySequence(started.execution.id.value))
    }

    @Test
    fun oneStepDeltaUpdatesOnlyTheRowsThatChanged() {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE mutation_audit (table_name TEXT NOT NULL)")
        sql.execSQL(
            "CREATE TRIGGER audit_occurrence_update AFTER UPDATE ON sequence_occurrences " +
                "BEGIN INSERT INTO mutation_audit VALUES ('occurrence'); END",
        )
        sql.execSQL(
            "CREATE TRIGGER audit_interval_update AFTER UPDATE ON sequence_intervals " +
                "BEGIN INSERT INTO mutation_audit VALUES ('interval'); END",
        )
        sql.execSQL(
            "CREATE TRIGGER audit_sequence_update AFTER UPDATE ON sequence_executions " +
                "BEGIN INSERT INTO mutation_audit VALUES ('sequence'); END",
        )
        sql.execSQL(
            "CREATE TRIGGER audit_child_update AFTER UPDATE ON activity_executions " +
                "BEGIN INSERT INTO mutation_audit VALUES ('child'); END",
        )
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        sql.execSQL("DELETE FROM mutation_audit")

        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(5))

        assertEquals(1, auditCount("occurrence"))
        assertEquals(1, auditCount("interval"))
        assertEquals(1, auditCount("sequence"))
        assertEquals(1, auditCount("child"))
        assertNotNull(repository.getActiveSession())
    }

    private fun inMemoryDatabase() =
        LifeTracingDatabase
            .inMemoryBuilder(ApplicationProvider.getApplicationContext())
            .allowMainThreadQueries()
            .build()

    private fun seed(database: LifeTracingDatabase) {
        val fixtures = LiveRuntimeTestFixtures(database)
        fixtures.seedSeries()
        fixtures.activity("stopwatch", "STOPWATCH")
        fixtures.activity("timer", "TIMER", 60_000)
        fixtures.activity("timer-overtime", "TIMER", 60_000, "OVERTIME")
        fixtures.activity("no-live", "NO_LIVE_TRACKING")
        fixtures.sequence("sequence", listOf("stopwatch", "stopwatch"), countdownMs = 10_000)
        fixtures.sequence("sequence-timer", listOf("timer", "stopwatch"), countdownMs = 30_000)
        fixtures.sequence("sequence-timer-direct", listOf("timer", "stopwatch"))
        fixtures.sequence("sequence-timers", listOf("timer", "timer"), countdownMs = 30_000)
        fixtures.sequence("sequence-timer-no-live", listOf("timer", "no-live"))
        fixtures.sequence("sequence-waiting", listOf("timer", "stopwatch"), autoAdvance = false)
        fixtures.sequence("sequence-overtime", listOf("timer-overtime", "stopwatch"))
        fixtures.sequence("sequence-empty", emptyList())
        fixtures.sequence("sequence-one-timer", listOf("timer"))
        fixtures.sequence("sequence-no-live-active", listOf("no-live"))
        fixtures.sequence("sequence-no-live-pause", listOf("no-live"), noLiveAccounting = "PAUSE")
    }

    private fun withReopenedSequence(
        snapshotId: String,
        reconcileAtSeconds: Long,
        verify: (SequenceExecutionId) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "live-runtime-reopen-${System.nanoTime()}"
        database.close()
        context.deleteDatabase(name)
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            seed(database)
            repository = repository(database)
            val started =
                repository.startSequenceFromSnapshot(
                    SequenceSnapshotId(snapshotId),
                    instant(0),
                    instant(0),
                    ZoneOffset.UTC,
                )
            database.close()

            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = repository(database, 100)
            repository.reconcileActiveSession(instant(reconcileAtSeconds))
            verify(started.execution.id)
        } finally {
            database.close()
            context.deleteDatabase(name)
            database = inMemoryDatabase()
        }
    }

    private fun repository(
        database: LifeTracingDatabase,
        offset: Int = 0,
        childIds: (() -> ActivityExecutionId)? = null,
        pauseIds: (() -> ActivityExecutionPauseId)? = null,
        intervalIds: (() -> SequenceIntervalId)? = null,
    ): LiveSessionRepository {
        var activity = offset
        var pause = offset
        var sequence = offset
        var occurrence = offset
        var interval = offset
        return LiveSessionRepository(
            database,
            childIds ?: { ActivityExecutionId("activity-${++activity}") },
            pauseIds ?: { ActivityExecutionPauseId("pause-${++pause}") },
            { SequenceExecutionId("sequence-${++sequence}") },
            { SequenceOccurrenceId("occurrence-${++occurrence}") },
            intervalIds ?: { SequenceIntervalId("interval-${++interval}") },
        )
    }

    private fun repositoryExecution(id: String) =
        requireNotNull(database.activityExecutionDao().getAggregate(id)).toDomain()

    private fun repositorySequence(id: String) =
        requireNotNull(database.sequenceExecutionDao().getAggregate(id)).toDomain()

    private fun auditCount(table: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM mutation_audit WHERE table_name = ?", arrayOf(table))
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }

    private fun instant(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}
