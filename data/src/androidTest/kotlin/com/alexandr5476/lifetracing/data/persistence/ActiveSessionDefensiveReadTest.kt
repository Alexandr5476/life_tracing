package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ActiveSessionDefensiveReadTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var fixtures: LiveRuntimeTestFixtures
    private lateinit var repository: LiveSessionRepository

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        fixtures = LiveRuntimeTestFixtures(database)
        fixtures.seedSeries()
        fixtures.activity("stopwatch", "STOPWATCH")
        fixtures.sequence("sequence", listOf("stopwatch", "stopwatch"), countdownMs = 10_000)
        fixtures.sequence("sequence-off", listOf("stopwatch", "stopwatch"), autoAdvance = false)
        fixtures.sequence("sequence-three", listOf("stopwatch", "stopwatch", "stopwatch"), countdownMs = 10_000)
        fixtures.sequence(
            "sequence-three-off",
            listOf("stopwatch", "stopwatch", "stopwatch"),
            autoAdvance = false,
            countdownMs = 10_000,
        )
        repository = repository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun activityPointerToSequenceChildIsRejected() {
        val state =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        val childId = requireNotNull(state.currentChild).id.value
        database.activeSessionDao().clear()
        sql("INSERT INTO active_session VALUES (1, 'ACTIVITY', '$childId', NULL, 'RUNNING', 0)")

        assertCorrupt()
    }

    @Test
    fun activitySessionStateMustMatchExecution() {
        fixtures.standaloneExecution(status = "PAUSED")
        database.activeSessionDao().insert(activitySession(ActiveSessionState.RUNNING))
        assertCorrupt()

        database.activeSessionDao().clear()
        sql("UPDATE activity_execution_pauses SET ended_at_ms = 1 WHERE id = 'pause'")
        sql("UPDATE activity_executions SET status = 'RUNNING', updated_at_ms = 1 WHERE id = 'activity-execution'")
        database.activeSessionDao().insert(activitySession(ActiveSessionState.PAUSED))
        assertCorrupt()
    }

    @Test
    fun runningSessionCannotPointToPausedSequenceRoot() {
        repository.startSequenceFromSnapshot(
            SequenceSnapshotId("sequence"),
            Instant.EPOCH,
            Instant.EPOCH,
            ZoneOffset.UTC,
        )
        sql("UPDATE sequence_executions SET status = 'PAUSED' WHERE id = 'sequence-1'")

        assertCorrupt()
    }

    @Test
    fun pausedSequenceRequiresOpenExplicitPause() {
        repository.startSequenceFromSnapshot(
            SequenceSnapshotId("sequence"),
            Instant.EPOCH,
            Instant.EPOCH,
            ZoneOffset.UTC,
        )
        sql("UPDATE sequence_executions SET status = 'PAUSED' WHERE id = 'sequence-1'")
        sql("UPDATE active_session SET state = 'PAUSED' WHERE singleton_id = 1")

        assertCorrupt()
    }

    @Test
    fun waitingNextRejectsCurrentOrTransitionCountdown() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        sql("UPDATE active_session SET state = 'WAITING_NEXT' WHERE singleton_id = 1")
        assertCorrupt()

        sql("UPDATE active_session SET state = 'RUNNING' WHERE singleton_id = 1")
        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(1))
        sql("UPDATE active_session SET state = 'WAITING_NEXT' WHERE singleton_id = 1")
        assertCorrupt()
    }

    @Test
    fun runningWithoutCurrentRejectsImplicitIdle() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-off"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(1))
        sql("UPDATE active_session SET state = 'RUNNING' WHERE singleton_id = 1")

        assertCorrupt()
    }

    @Test
    fun transitionCountdownTargetMustBelongToActiveExecution() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(1))
        fixtures.sequenceExecution("foreign", "sequence")
        sql("UPDATE sequence_intervals SET occurrence_id = 'foreign-occurrence' WHERE ended_at_ms IS NULL")

        assertCorrupt()
    }

    @Test
    fun transitionCountdownMustTargetEarliestRemainingOccurrence() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-three"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        val transitioning = repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(1))
        val later =
            transitioning.execution.occurrences[2]
                .id.value
        sql("UPDATE sequence_intervals SET occurrence_id = '$later' WHERE ended_at_ms IS NULL")

        assertCorrupt()
    }

    @Test
    fun currentOccurrenceCannotSkipAnEarlierRemainingOccurrence() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-three"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        val first =
            started.execution.occurrences[0]
                .id.value
        val second =
            started.execution.occurrences[1]
                .id.value
        val sequenceId = started.execution.id.value
        sql("UPDATE sequence_occurrences SET status = 'NOT_STARTED', entered_at_ms = NULL WHERE id = '$first'")
        sql("UPDATE sequence_occurrences SET status = 'CURRENT', entered_at_ms = 0 WHERE id = '$second'")
        sql("UPDATE sequence_executions SET current_occurrence_id = '$second' WHERE id = '$sequenceId'")
        sql("UPDATE sequence_intervals SET occurrence_id = '$second' WHERE ended_at_ms IS NULL")

        assertCorrupt()
    }

    @Test
    fun waitingNextImplicitIdleMustBeSequenceGlobal() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-off"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        val waiting = repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(1))
        val next =
            waiting.execution.occurrences[1]
                .id.value
        sql("UPDATE sequence_intervals SET occurrence_id = '$next' WHERE ended_at_ms IS NULL")

        assertCorrupt()
    }

    @Test
    fun pausedCountdownRejectsOldUnrelatedProgressAndResumeLeavesRowsUntouched() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-three"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        val firstTransition =
            repository.completeCurrentSequenceStep(
                started.execution.currentOccurrenceId!!,
                instant(1),
            )
        val paused = repository.pauseActiveSequence(instant(2))
        repository.resumeActiveSequence(instant(3))
        repository.reconcileActiveSession(instant(12))
        repository.completeCurrentSequenceStep(paused.execution.occurrences[1].id, instant(13))
        sql("UPDATE sequence_intervals SET kind = 'EXPLICIT_PAUSE', occurrence_id = NULL WHERE ended_at_ms IS NULL")
        sql("UPDATE sequence_executions SET status = 'PAUSED' WHERE id = '${firstTransition.execution.id.value}'")
        sql("UPDATE active_session SET state = 'PAUSED' WHERE singleton_id = 1")
        val before = database.sequenceExecutionDao().getAggregate(firstTransition.execution.id.value)

        assertCorrupt()
        assertThrows(IllegalArgumentException::class.java) { repository.resumeActiveSequence(instant(20)) }
        assertEquals(before, database.sequenceExecutionDao().getAggregate(firstTransition.execution.id.value))
    }

    @Test
    fun pausedCountdownRejectsProgressForLaterRemainingOccurrence() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-three-off"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        val waiting = repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(1))
        val later =
            waiting.execution.occurrences[2]
                .id.value
        val sequenceId = waiting.execution.id.value
        sql(
            "INSERT INTO sequence_intervals " +
                "(id, sequence_execution_id, kind, started_at_ms, ended_at_ms, occurrence_id) " +
                "VALUES ('later-countdown', '$sequenceId', 'TRANSITION_COUNTDOWN', 1000, 2000, '$later')",
        )
        sql(
            "UPDATE sequence_intervals SET kind = 'EXPLICIT_PAUSE', started_at_ms = 2000, occurrence_id = NULL " +
                "WHERE ended_at_ms IS NULL",
        )
        sql("UPDATE sequence_executions SET status = 'PAUSED', updated_at_ms = 2000 WHERE id = '$sequenceId'")
        sql("UPDATE active_session SET state = 'PAUSED', updated_at_ms = 2000 WHERE singleton_id = 1")

        assertCorrupt()
    }

    @Test
    fun explicitPauseMustNotPointAtAnOccurrence() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        val paused = repository.pauseActiveSequence(instant(1))
        sql(
            "UPDATE sequence_intervals SET occurrence_id = '${paused.execution.currentOccurrenceId!!.value}' " +
                "WHERE ended_at_ms IS NULL",
        )

        assertCorrupt()
    }

    private fun activitySession(state: ActiveSessionState) =
        ActiveSession(
            ActiveSessionKind.ACTIVITY,
            state,
            ActivityExecutionId("activity-execution"),
            null,
            Instant.EPOCH,
        )

    private fun repository(database: LifeTracingDatabase): LiveSessionRepository {
        var activity = 0
        var pause = 0
        var sequence = 0
        var occurrence = 0
        var interval = 0
        return LiveSessionRepository(
            database,
            { ActivityExecutionId("activity-${++activity}") },
            { ActivityExecutionPauseId("pause-${++pause}") },
            { SequenceExecutionId("sequence-${++sequence}") },
            { SequenceOccurrenceId("occurrence-${++occurrence}") },
            { SequenceIntervalId("interval-${++interval}") },
        )
    }

    private fun assertCorrupt() {
        assertThrows(IllegalArgumentException::class.java) { repository.getActiveSession() }
    }

    private fun sql(statement: String) = database.openHelper.writableDatabase.execSQL(statement)

    private fun instant(seconds: Long) = Instant.ofEpochSecond(seconds)
}
