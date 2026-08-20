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
