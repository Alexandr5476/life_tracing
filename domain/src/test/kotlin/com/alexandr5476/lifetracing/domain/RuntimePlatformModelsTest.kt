package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
        val initiallyPaused =
            engine.pause(countdown, instant(12), snapshot, mapOf(first.id to first, second.id to second))
        val resumed =
            engine.resume(initiallyPaused, instant(20), snapshot, mapOf(first.id to first, second.id to second))
        val runtime = sequenceRuntime(resumed, snapshot, mapOf(first.id to first, second.id to second))

        val deadline = NextRuntimeDeadlineResolver.resolve(runtime)

        assertEquals(RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN, deadline?.kind)
        assertEquals(instant(23), deadline?.at)
        val baseline = RuntimeDisplayBaseline.capture(runtime, WallMonotonicAnchor(instant(20), 1_000), 1_000)
        assertEquals(Duration.ofSeconds(3), baseline.transitionCountdownRemaining(1_000))
        assertEquals(Duration.ZERO, baseline.transitionCountdownRemaining(4_000))

        val paused = engine.pause(resumed, instant(21), snapshot, mapOf(first.id to first, second.id to second))
        val pausedRuntime = sequenceRuntime(paused, snapshot, mapOf(first.id to first, second.id to second))
        val pausedBaseline =
            RuntimeDisplayBaseline.capture(pausedRuntime, WallMonotonicAnchor(instant(21), 2_000), 2_000)
        assertEquals(
            pausedRuntime.transitionCountdownTargetId,
            pausedBaseline.identity.let {
                (it as RuntimeDisplayIdentity.Sequence).transitionCountdownTargetId
            },
        )
        assertEquals(Duration.ofSeconds(2), pausedBaseline.transitionCountdownRemaining(2_000))
        assertEquals(Duration.ofSeconds(2), pausedBaseline.transitionCountdownRemaining(2_000_000))
    }

    @Test
    fun overtimeAndBackwardWallNeverProduceNegativeDisplayDurations() {
        val timer = activity("timer", TimeTrackingMode.TIMER, 10, TimerZeroBehavior.OVERTIME)
        val execution =
            ActivityExecutionFactory { ActivityExecutionId("execution") }
                .startTimed(timer, instant(100), instant(100), ZoneOffset.UTC)
        val runtime = ActiveActivityRuntime(activitySession(execution), execution, timer)

        val baseline = RuntimeDisplayBaseline.capture(runtime, WallMonotonicAnchor(instant(100), 1_000), 1_000)
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
                20_000,
            )
        assertEquals(Duration.ofSeconds(5), paused.timerRemaining(20_000))
        assertEquals(Duration.ofSeconds(5), paused.timerRemaining(120_000))
    }

    @Test
    fun delayedStandaloneStartUsesTheBaselineCaptureObservationAsItsTimeOrigin() {
        val stopwatch = activity("watch", TimeTrackingMode.STOPWATCH)
        val execution =
            ActivityExecutionFactory { ActivityExecutionId("execution") }
                .startTimed(stopwatch, instant(100), instant(100), ZoneOffset.UTC)
        val runtime = ActiveActivityRuntime(activitySession(execution), execution, stopwatch)

        val baseline = RuntimeDisplayBaseline.capture(runtime, WallMonotonicAnchor(instant(0), 0), 100_000)

        assertEquals(Duration.ZERO, baseline.activeElapsed(100_000))
        assertEquals(Duration.ofSeconds(1), baseline.activeElapsed(101_000))
        assertEquals(Duration.ofSeconds(10), baseline.activeElapsed(110_000))
    }

    @Test
    fun sequenceTransitionBaselineDoesNotDoubleCountTimeBeforeCurrentStep() {
        val first = activity("first", TimeTrackingMode.TIMER, 10)
        val second = activity("second", TimeTrackingMode.STOPWATCH)
        val snapshot = sequence(listOf(first.id, second.id), countdownSeconds = 0)
        val engine = engine()
        val started =
            engine.start(
                snapshot,
                mapOf(first.id to first, second.id to second),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val transitioned =
            engine.reconcile(started, snapshot, mapOf(first.id to first, second.id to second), instant(10))
        val runtime = sequenceRuntime(transitioned, snapshot, mapOf(first.id to first, second.id to second))

        val baseline = RuntimeDisplayBaseline.capture(runtime, WallMonotonicAnchor(instant(0), 0), 10_000)

        assertEquals(Duration.ofSeconds(10), baseline.activeElapsed(10_000))
        assertEquals(Duration.ofSeconds(11), baseline.activeElapsed(11_000))
        assertEquals(Duration.ofSeconds(15), baseline.activeElapsed(15_000))
        assertEquals(Duration.ZERO, baseline.currentStepStopwatchElapsed(10_000))
        assertEquals(Duration.ofSeconds(5), baseline.currentStepStopwatchElapsed(15_000))
        assertNull(baseline.timerRemaining(15_000))
        assertNull(baseline.timerOvertime(15_000))
    }

    @Test
    fun pausedCurrentStopwatchKeepsChildElapsedDistinctFromSequenceTotal() {
        val first = activity("first", TimeTrackingMode.TIMER, 10)
        val second = activity("second", TimeTrackingMode.STOPWATCH)
        val snapshot = sequence(listOf(first.id, second.id), countdownSeconds = 0)
        val engine = engine()
        val started =
            engine.start(
                snapshot,
                mapOf(first.id to first, second.id to second),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val running = engine.reconcile(started, snapshot, mapOf(first.id to first, second.id to second), instant(10))
        val paused = engine.pause(running, instant(12), snapshot, mapOf(first.id to first, second.id to second))
        val runtime = sequenceRuntime(paused, snapshot, mapOf(first.id to first, second.id to second))
        val baseline = RuntimeDisplayBaseline.capture(runtime, WallMonotonicAnchor(instant(12), 1_000), 1_000)

        assertEquals(running.execution.currentOccurrenceId, paused.execution.currentOccurrenceId)
        assertEquals(running.currentChild?.id, paused.currentChild?.id)
        assertEquals(Duration.ofSeconds(12), baseline.activeElapsed(1_000))
        assertEquals(Duration.ofSeconds(2), baseline.currentStepStopwatchElapsed(1_000))
        assertEquals(Duration.ofSeconds(12), baseline.activeElapsed(1_000_000))
        assertEquals(Duration.ofSeconds(2), baseline.currentStepStopwatchElapsed(1_000_000))
    }

    @Test
    fun currentTimerUsesChildRemainingAndNoLiveAndWaitingHaveNoTimedDisplay() {
        val first = activity("first", TimeTrackingMode.TIMER, 5)
        val second = activity("second", TimeTrackingMode.TIMER, 10, TimerZeroBehavior.OVERTIME)
        val activities = mapOf(first.id to first, second.id to second)
        val snapshot = sequence(listOf(first.id, second.id), countdownSeconds = 0)
        val engine = engine()
        val started = engine.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC)
        val timerRuntime =
            sequenceRuntime(engine.reconcile(started, snapshot, activities, instant(5)), snapshot, activities)
        val timer = RuntimeDisplayBaseline.capture(timerRuntime, WallMonotonicAnchor(instant(7), 1_000), 1_000)

        assertEquals(Duration.ofSeconds(7), timer.activeElapsed(1_000))
        assertEquals(Duration.ofSeconds(8), timer.timerRemaining(1_000))
        assertEquals(Duration.ZERO, timer.timerOvertime(1_000))
        assertEquals(Duration.ZERO, timer.timerRemaining(11_000))
        assertEquals(Duration.ofSeconds(2), timer.timerOvertime(11_000))

        val noLive = activity("no-live", TimeTrackingMode.NO_LIVE_TRACKING)
        val noLiveSnapshot = sequence(listOf(noLive.id), countdownSeconds = 0)
        val noLiveState =
            engine().start(
                noLiveSnapshot,
                mapOf(noLive.id to noLive),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val noLiveBaseline =
            RuntimeDisplayBaseline.capture(
                sequenceRuntime(noLiveState, noLiveSnapshot, mapOf(noLive.id to noLive)),
                WallMonotonicAnchor(instant(3), 1_000),
                1_000,
            )
        assertNull(noLiveBaseline.currentStepStopwatchElapsed(1_000))
        assertNull(noLiveBaseline.timerRemaining(1_000))
        assertNull(noLiveBaseline.timerOvertime(1_000))

        val waitingSnapshot = sequence(listOf(first.id, second.id), countdownSeconds = 0, autoAdvance = false)
        val waitingStarted = engine().start(waitingSnapshot, activities, instant(0), instant(0), ZoneOffset.UTC)
        val waiting =
            engine().completeCurrent(
                waitingStarted,
                requireNotNull(waitingStarted.execution.currentOccurrenceId),
                instant(1),
                waitingSnapshot,
                activities,
            )
        val waitingBaseline =
            RuntimeDisplayBaseline.capture(
                sequenceRuntime(waiting, waitingSnapshot, activities),
                WallMonotonicAnchor(instant(1), 1_000),
                1_000,
            )
        assertNull(waitingBaseline.currentStepStopwatchElapsed(2_000))
        assertNull(waitingBaseline.timerRemaining(2_000))
        assertNull(waitingBaseline.transitionCountdownRemaining(2_000))
    }

    @Test
    fun baselineIdentityRejectsAChangedSequenceOccurrenceOrCountdownTarget() {
        val first = activity("first", TimeTrackingMode.TIMER, 5)
        val second = activity("second", TimeTrackingMode.STOPWATCH)
        val activities = mapOf(first.id to first, second.id to second)
        val snapshot = sequence(listOf(first.id, second.id), countdownSeconds = 3)
        val engine = engine()
        val started = engine.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC)
        val baseline =
            RuntimeDisplayBaseline.capture(
                sequenceRuntime(started, snapshot, activities),
                WallMonotonicAnchor(instant(0), 1_000),
                1_000,
            )
        val transitioned = engine.reconcile(started, snapshot, activities, instant(5))
        val transitionedRuntime = sequenceRuntime(transitioned, snapshot, activities)

        assertTrue(baseline.matches(sequenceRuntime(started, snapshot, activities)))
        assertFalse(baseline.matches(transitionedRuntime))
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
                5_000,
            )

        repeat(10_000) {
            baseline.activeElapsed(5_000L + it)
            baseline.currentStepStopwatchElapsed(5_000L + it)
        }

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
    ): ActiveSequenceRuntime {
        val sessionState =
            when {
                state.execution.status == SequenceExecutionStatus.PAUSED -> ActiveSessionState.PAUSED
                state.execution.currentOccurrenceId == null &&
                    state.execution.intervals
                        .singleOrNull { it.endedAt == null }
                        ?.kind ==
                    SequenceIntervalKind.IMPLICIT_IDLE -> ActiveSessionState.WAITING_NEXT
                else -> ActiveSessionState.RUNNING
            }
        return ActiveSequenceRuntime(
            ActiveSession(
                ActiveSessionKind.SEQUENCE,
                sessionState,
                null,
                state.execution.id,
                state.execution.updatedAt,
            ),
            state.execution,
            snapshot,
            activities,
            state.currentChild,
            if (sessionState == ActiveSessionState.WAITING_NEXT) {
                null
            } else {
                state.execution.currentOccurrenceId?.let { null } ?: nextRemainingOccurrence(state.execution)?.id
            },
        )
    }

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
            { SequenceOccurrenceId("occurrence-${++occurrence}") },
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
        autoAdvance: Boolean = true,
    ) = SequenceConfigSnapshot(
        SequenceSnapshotId("sequence"),
        "Sequence",
        null,
        null,
        null,
        null,
        instant(0),
        SequenceSnapshotSettings(
            autoAdvance,
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
