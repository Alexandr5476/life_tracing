package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RuntimePlatformModelsTest {
    @Test
    fun wallAnchorUsesOnlyMonotonicProgressUntilRebuilt() {
        val anchor = WallMonotonicAnchor(instant(100), 1_000)

        assertEquals(instant(105), anchor.wallAt(6_000))
        assertEquals(6_000, anchor.elapsedAt(instant(105)))
        assertThrows(ArithmeticException::class.java) { anchor.wallAt(Long.MIN_VALUE) }

        val rebuilt = WallMonotonicAnchor(instant(1_000), 6_000)
        assertEquals(instant(1_001), rebuilt.wallAt(7_000))
    }

    @Test
    fun standaloneDeadlineReusesTimerPolicyAndExcludesPausedOvertimeAndStopwatch() {
        val timer = activity("timer", TimeTrackingMode.TIMER, 60)
        val execution =
            ActivityExecutionFactory { ActivityExecutionId("execution") }
                .startTimed(timer, instant(0), instant(0), ZoneOffset.UTC)
        val running = ActiveActivityRuntime(activitySession(execution), execution, timer)

        assertEquals(instant(60), NextRuntimeDeadlineResolver.resolve(running)?.at)
        assertNull(
            NextRuntimeDeadlineResolver.resolve(
                running.copy(
                    session = running.session.copy(state = ActiveSessionState.PAUSED),
                    execution =
                        ActivityExecutionTransitions.pause(
                            execution,
                            ActivityExecutionPauseId("pause"),
                            instant(10),
                        ),
                ),
            ),
        )
        assertNull(
            NextRuntimeDeadlineResolver.resolve(
                running.copy(
                    snapshot =
                        timer.copy(
                            settings = timer.settings.copy(timerZeroBehavior = TimerZeroBehavior.OVERTIME),
                        ),
                ),
            ),
        )
        assertNull(
            NextRuntimeDeadlineResolver.resolve(running.copy(snapshot = activity("watch", TimeTrackingMode.STOPWATCH))),
        )
    }

    @Test
    fun transitionDeadlineIncludesConsumedCountdownSegments() {
        val first = activity("first", TimeTrackingMode.TIMER, 10)
        val second = activity("second", TimeTrackingMode.STOPWATCH)
        val snapshot = sequence(listOf(first.id, second.id), countdownSeconds = 5)
        val engine = engine()
        val started =
            engine.start(
                snapshot,
                mapOf(first.id to first, second.id to second),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val countdown = engine.reconcile(started, snapshot, mapOf(first.id to first, second.id to second), instant(10))
        val paused = engine.pause(countdown, instant(12), snapshot, mapOf(first.id to first, second.id to second))
        val resumed = engine.resume(paused, instant(20), snapshot, mapOf(first.id to first, second.id to second))
        val runtime = sequenceRuntime(resumed, snapshot, mapOf(first.id to first, second.id to second))

        val deadline = NextRuntimeDeadlineResolver.resolve(runtime)

        assertEquals(RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN, deadline?.kind)
        assertEquals(instant(23), deadline?.at)
        val baseline = RuntimeDisplayBaseline.capture(runtime, WallMonotonicAnchor(instant(20), 1_000))
        assertEquals(Duration.ofSeconds(3), baseline.transitionCountdownRemaining(1_000))
    }

    @Test
    fun overtimeAndBackwardWallNeverProduceNegativeDisplayDurations() {
        val timer = activity("timer", TimeTrackingMode.TIMER, 10, TimerZeroBehavior.OVERTIME)
        val execution =
            ActivityExecutionFactory { ActivityExecutionId("execution") }
                .startTimed(timer, instant(100), instant(100), ZoneOffset.UTC)
        val runtime = ActiveActivityRuntime(activitySession(execution), execution, timer)

        val baseline = RuntimeDisplayBaseline.capture(runtime, WallMonotonicAnchor(instant(100), 1_000))
        assertEquals(Duration.ZERO, baseline.activeElapsed(1_000))
        assertEquals(Duration.ZERO, baseline.timerOvertime(6_000))
        assertEquals(Duration.ofSeconds(5), baseline.timerOvertime(16_000))

        val pausedExecution =
            ActivityExecutionTransitions.pause(execution, ActivityExecutionPauseId("pause"), instant(105))
        val paused =
            RuntimeDisplayBaseline.capture(
                runtime.copy(
                    session = runtime.session.copy(state = ActiveSessionState.PAUSED),
                    execution = pausedExecution,
                ),
                WallMonotonicAnchor(instant(110), 20_000),
            )
        assertEquals(Duration.ofSeconds(5), paused.timerRemaining(20_000))
        assertEquals(Duration.ofSeconds(5), paused.timerRemaining(120_000))
    }

    @Test
    fun largeHistoryIsConsumedOnlyWhenBuildingConstantInputTickBaseline() {
        val stopwatch = activity("watch", TimeTrackingMode.STOPWATCH)
        val snapshot = sequence(listOf(stopwatch.id), countdownSeconds = 0)
        val started = engine().start(snapshot, mapOf(stopwatch.id to stopwatch), instant(0), instant(0), ZoneOffset.UTC)
        val occurrenceId = requireNotNull(started.execution.currentOccurrenceId)
        val intervals =
            (0 until 2_000).map { index ->
                SequenceInterval(
                    SequenceIntervalId("closed-$index"),
                    SequenceIntervalKind.ACTIVE_STEP,
                    instant(index.toLong()),
                    instant(index + 1L),
                    occurrenceId,
                )
            } +
                SequenceInterval(
                    SequenceIntervalId("open"),
                    SequenceIntervalKind.ACTIVE_STEP,
                    instant(2_000),
                    null,
                    occurrenceId,
                )
        val state =
            started.copy(
                execution = started.execution.copy(intervals = intervals, updatedAt = instant(2_000)),
            )
        val baseline =
            RuntimeDisplayBaseline.capture(
                sequenceRuntime(state, snapshot, mapOf(stopwatch.id to stopwatch)),
                WallMonotonicAnchor(instant(2_010), 5_000),
            )

        repeat(10_000) { baseline.activeElapsed(5_000L + it) }

        assertEquals(Duration.ofSeconds(2_020), baseline.activeElapsed(15_000))
    }

    private fun activitySession(execution: ActivityExecution) =
        ActiveSession(
            ActiveSessionKind.ACTIVITY,
            ActiveSessionState.RUNNING,
            execution.id,
            null,
            execution.updatedAt,
        )

    private fun sequenceRuntime(
        state: SequenceRuntimeState,
        snapshot: SequenceConfigSnapshot,
        activities: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ) = ActiveSequenceRuntime(
        ActiveSession(
            ActiveSessionKind.SEQUENCE,
            if (state.execution.status ==
                SequenceExecutionStatus.PAUSED
            ) {
                ActiveSessionState.PAUSED
            } else {
                ActiveSessionState.RUNNING
            },
            null,
            state.execution.id,
            state.execution.updatedAt,
        ),
        state.execution,
        snapshot,
        activities,
        state.currentChild,
    )

    private fun engine(): SequenceRuntimeEngine {
        var occurrence = 0
        var child = 0
        var pause = 0
        var interval = 0
        return SequenceRuntimeEngine(
            SequenceExecutionFactory(
                { SequenceExecutionId("sequence-execution") },
                RuntimeOccurrenceMaterializer { SequenceOccurrenceId("occurrence-${++occurrence}") },
            ),
            ActivityExecutionFactory { ActivityExecutionId("child-${++child}") },
            { ActivityExecutionPauseId("pause-${++pause}") },
            { SequenceIntervalId("interval-${++interval}") },
        )
    }

    private fun activity(
        id: String,
        mode: TimeTrackingMode,
        timerSeconds: Long? = null,
        zeroBehavior: TimerZeroBehavior = TimerZeroBehavior.FINISH,
    ) = ActivityConfigSnapshot(
        ActivitySnapshotId(id),
        id,
        null,
        mode,
        timerSeconds?.let(Duration::ofSeconds),
        null,
        null,
        null,
        false,
        instant(0),
        ActivityTemplateSettings(timerZeroBehavior = zeroBehavior),
    )

    private fun sequence(
        activityIds: List<ActivitySnapshotId>,
        countdownSeconds: Long,
    ) = SequenceConfigSnapshot(
        SequenceSnapshotId("sequence"),
        "Sequence",
        null,
        null,
        null,
        null,
        instant(0),
        SequenceSnapshotSettings(
            true,
            Duration.ZERO,
            Duration.ofSeconds(countdownSeconds),
            true,
            true,
            false,
            true,
            true,
            NoLiveTimeAccounting.ACTIVE,
        ),
        nodes =
            activityIds.mapIndexed {
                index,
                id,
                ->
                SequenceSnapshotActivityStep(SequenceSnapshotNodeId("step-$index"), index, id)
            },
    )

    private fun instant(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}
