package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class LiveRuntimePoliciesTest {
    @Test
    fun activeSessionValidatorRejectsInvalidOwnershipAndWaitingActivity() {
        val at = instant(0)
        ActiveSessionValidator.requireValid(
            ActiveSession(
                ActiveSessionKind.SEQUENCE,
                ActiveSessionState.WAITING_NEXT,
                null,
                SequenceExecutionId("sequence"),
                at,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ActiveSessionValidator.requireValid(
                ActiveSession(
                    ActiveSessionKind.ACTIVITY,
                    ActiveSessionState.WAITING_NEXT,
                    ActivityExecutionId("activity"),
                    null,
                    at,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActiveSessionValidator.requireValid(
                ActiveSession(
                    ActiveSessionKind.SEQUENCE,
                    ActiveSessionState.RUNNING,
                    ActivityExecutionId("activity"),
                    SequenceExecutionId("sequence"),
                    at,
                ),
            )
        }
    }

    @Test
    fun timerDeadlineUsesClosedPausesAndOvertimeNeverCompletes() {
        val snapshot = activity("timer", TimeTrackingMode.TIMER, 60)
        val factory = ActivityExecutionFactory { ActivityExecutionId("execution") }
        val started = factory.startTimed(snapshot, instant(0), instant(0), ZoneOffset.UTC)
        val paused = ActivityExecutionTransitions.pause(started, ActivityExecutionPauseId("pause"), instant(20))
        val resumed = ActivityExecutionTransitions.resume(paused, instant(30))

        assertEquals(
            instant(70),
            TimerDeadlineCalculator.deadline(resumed, Duration.ofSeconds(60), TimerZeroBehavior.FINISH),
        )
        assertNull(TimerDeadlineCalculator.deadline(resumed, Duration.ofSeconds(60), TimerZeroBehavior.OVERTIME))
        assertNull(TimerDeadlineCalculator.deadline(paused, Duration.ofSeconds(60), TimerZeroBehavior.FINISH))
    }

    @Test
    fun catchUpProcessesTimerCountdownTimerThenStopsAtNoLive() {
        val activities =
            listOf(
                activity("timer-a", TimeTrackingMode.TIMER, 60),
                activity("timer-b", TimeTrackingMode.TIMER, 30),
                activity("no-live", TimeTrackingMode.NO_LIVE_TRACKING),
            ).associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(activities.keys.toList(), autoAdvance = true, countdownSeconds = 10)
        val runtime = engine()
        val state = runtime.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC)

        val caughtUp = runtime.reconcile(state, snapshot, activities, instant(500))

        assertEquals(instant(60), caughtUp.execution.occurrences[0].completedAt)
        assertEquals(OccurrenceCompletionReason.NATURAL_TIMER_END, caughtUp.execution.occurrences[0].completionReason)
        assertEquals(instant(70), caughtUp.execution.occurrences[1].enteredAt)
        assertEquals(instant(100), caughtUp.execution.occurrences[1].completedAt)
        assertEquals(instant(110), caughtUp.execution.occurrences[2].enteredAt)
        assertEquals(RuntimeOccurrenceStatus.CURRENT, caughtUp.execution.occurrences[2].status)
        assertNull(caughtUp.currentChild)
        assertEquals(2, caughtUp.children.size)
        assertEquals(
            SequenceIntervalKind.ACTIVE_STEP,
            caughtUp.execution.intervals
                .single { it.endedAt == null }
                .kind,
        )
    }

    @Test
    fun autoAdvanceOffWaitsAndRejectsStaleCompletion() {
        val activities =
            listOf(
                activity("timer", TimeTrackingMode.TIMER, 10),
                activity("stopwatch", TimeTrackingMode.STOPWATCH),
            ).associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(activities.keys.toList(), autoAdvance = false, countdownSeconds = 10)
        val runtime = engine()
        val started = runtime.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC)
        val firstId = started.execution.currentOccurrenceId!!
        val waiting = runtime.reconcile(started, snapshot, activities, instant(20))

        assertNull(waiting.execution.currentOccurrenceId)
        assertEquals(
            SequenceIntervalKind.IMPLICIT_IDLE,
            waiting.execution.intervals
                .single { it.endedAt == null }
                .kind,
        )
        assertThrows(IllegalArgumentException::class.java) {
            runtime.completeCurrent(waiting, firstId, instant(20), snapshot, activities)
        }
        val transitioning = runtime.startNext(waiting, instant(30), snapshot, activities)
        assertNull(transitioning.execution.currentOccurrenceId)
        assertEquals(
            SequenceIntervalKind.TRANSITION_COUNTDOWN,
            transitioning.execution.intervals
                .single { it.endedAt == null }
                .kind,
        )
        val next = runtime.reconcile(transitioning, snapshot, activities, instant(40))
        assertEquals(instant(40), next.execution.occurrences[1].enteredAt)
    }

    @Test
    fun pauseResumeCurrentTimerExtendsItsNaturalDeadline() {
        val timer = activity("timer", TimeTrackingMode.TIMER, 60)
        val activities = mapOf(timer.id to timer)
        val snapshot = sequence(listOf(timer.id), autoAdvance = true)
        val runtime = engine()
        val started = runtime.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC)
        val paused = runtime.pause(started, instant(20), snapshot, activities)
        val resumed = runtime.resume(paused, instant(30), snapshot, activities)

        assertEquals(ActivityExecutionStatus.PAUSED, paused.currentChild?.status)
        assertEquals(ActivityExecutionStatus.RUNNING, resumed.currentChild?.status)
        assertEquals(
            SequenceExecutionStatus.RUNNING,
            runtime.reconcile(resumed, snapshot, activities, instant(69)).execution.status,
        )
        val finished = runtime.reconcile(resumed, snapshot, activities, instant(70))
        assertEquals(instant(70), finished.execution.endedAt)
        assertEquals(
            OccurrenceCompletionReason.NATURAL_TIMER_END,
            finished.execution.occurrences
                .single()
                .completionReason,
        )
    }

    @Test
    fun pauseResumeNoLiveRestoresItsFrozenAccountingClassification() {
        NoLiveTimeAccounting.entries.forEach { accounting ->
            val noLive = activity("no-live-$accounting", TimeTrackingMode.NO_LIVE_TRACKING)
            val activities = mapOf(noLive.id to noLive)
            val snapshot = sequence(listOf(noLive.id), autoAdvance = true, noLiveAccounting = accounting)
            val runtime = engine()
            val started = runtime.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC)
            val paused = runtime.pause(started, instant(10), snapshot, activities)
            val resumed = runtime.resume(paused, instant(20), snapshot, activities)

            assertNull(paused.currentChild)
            assertEquals(
                SequenceIntervalKind.EXPLICIT_PAUSE,
                paused.execution.intervals
                    .single { it.endedAt == null }
                    .kind,
            )
            assertEquals(
                stepIntervalKind(noLive, accounting),
                resumed.execution.intervals
                    .single { it.endedAt == null }
                    .kind,
            )
        }
    }

    @Test
    fun pauseResumeFreezesTransitionCountdownAcrossMultipleSegments() {
        val activities =
            listOf(
                activity("stopwatch", TimeTrackingMode.STOPWATCH),
                activity("next", TimeTrackingMode.STOPWATCH),
            ).associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(activities.keys.toList(), autoAdvance = true, countdownSeconds = 30)
        val runtime = engine()
        val started = runtime.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC)
        val transitioning =
            runtime.completeCurrent(
                started,
                started.execution.currentOccurrenceId!!,
                instant(10),
                snapshot,
                activities,
            )
        val pausedOnce = runtime.pause(transitioning, instant(20), snapshot, activities)
        val resumedOnce = runtime.resume(pausedOnce, instant(100), snapshot, activities)
        val pausedTwice = runtime.pause(resumedOnce, instant(105), snapshot, activities)
        val resumedTwice = runtime.resume(pausedTwice, instant(200), snapshot, activities)

        val before = runtime.reconcile(resumedTwice, snapshot, activities, instant(214))
        assertNull(before.execution.currentOccurrenceId)
        val finished = runtime.reconcile(before, snapshot, activities, instant(215))
        assertEquals(instant(215), finished.execution.occurrences[1].enteredAt)
        assertEquals(
            30_000L,
            finished.execution.intervals.filter { it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN }.sumOf {
                Duration.between(it.startedAt, requireNotNull(it.endedAt)).toMillis()
            },
        )
    }

    @Test
    fun noLivePauseAccountingAndFinalCompletionUseParentTimeline() {
        val noLive = activity("no-live", TimeTrackingMode.NO_LIVE_TRACKING)
        val activities = mapOf(noLive.id to noLive)
        val snapshot = sequence(listOf(noLive.id), autoAdvance = true, noLiveAccounting = NoLiveTimeAccounting.PAUSE)
        val runtime = engine()
        val started = runtime.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC)
        assertEquals(
            SequenceIntervalKind.STEP_PAUSE,
            started.execution.intervals
                .single()
                .kind,
        )

        val finished =
            runtime.completeCurrent(
                started,
                started.execution.currentOccurrenceId!!,
                instant(20),
                snapshot,
                activities,
            )

        assertEquals(SequenceExecutionStatus.COMPLETED, finished.execution.status)
        assertEquals(Duration.ZERO, finished.execution.activeDuration)
        assertEquals(Duration.ofSeconds(20), finished.execution.pauseDuration)
        assertEquals(
            ActivityExecutionStatus.COMPLETED,
            finished.children.values
                .single()
                .status,
        )
        assertNull(
            finished.children.values
                .single()
                .activeDuration,
        )
    }

    @Test
    fun zeroCountdownChainTerminatesWithSemanticProgress() {
        val activities =
            (1..4).map { activity("timer-$it", TimeTrackingMode.TIMER, 1) }.associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(activities.keys.toList(), autoAdvance = true)
        val runtime = engine()
        val finished =
            runtime.reconcile(
                runtime.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC),
                snapshot,
                activities,
                instant(10),
            )

        assertEquals(SequenceExecutionStatus.COMPLETED, finished.execution.status)
        assertTrue(finished.execution.occurrences.all { it.status == RuntimeOccurrenceStatus.COMPLETED })
        assertEquals(Duration.ofSeconds(4), finished.execution.wallDuration)
    }

    @Test
    fun largeZeroCountdownTimerCatchUpCompletesInOneDeterministicPass() {
        val count = 750
        val timer = activity("timer", TimeTrackingMode.TIMER, 1)
        val activities = mapOf(timer.id to timer)
        val snapshot = sequence(List(count) { timer.id }, autoAdvance = true)
        val runtime = engine()

        val finished =
            runtime.reconcile(
                runtime.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC),
                snapshot,
                activities,
                instant(count.toLong()),
            )

        assertEquals(SequenceExecutionStatus.COMPLETED, finished.execution.status)
        finished.execution.occurrences.forEachIndexed { index, occurrence ->
            assertEquals(instant(index.toLong()), occurrence.enteredAt)
            assertEquals(instant(index + 1L), occurrence.completedAt)
            assertEquals(OccurrenceCompletionReason.NATURAL_TIMER_END, occurrence.completionReason)
        }
        assertEquals(instant(count.toLong()), finished.execution.endedAt)
        assertEquals(Duration.ofSeconds(count.toLong()), finished.execution.activeDuration)
        assertEquals(Duration.ZERO, finished.execution.pauseDuration)
        assertEquals(Duration.ofSeconds(count.toLong()), finished.execution.wallDuration)
    }

    @Test
    fun zeroWallSequenceIsRepresentableAndDeadlineArithmeticIsChecked() {
        val noLive = activity("no-live-zero", TimeTrackingMode.NO_LIVE_TRACKING)
        val activities = mapOf(noLive.id to noLive)
        val snapshot = sequence(listOf(noLive.id), autoAdvance = true)
        val runtime = engine()
        val started = runtime.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC)
        val finished =
            runtime.completeCurrent(
                started,
                started.execution.currentOccurrenceId!!,
                instant(0),
                snapshot,
                activities,
            )

        assertEquals(Duration.ZERO, finished.execution.wallDuration)
        val latestPersisted = Instant.ofEpochMilli(Long.MAX_VALUE)
        val timer = activity("overflowing-timer", TimeTrackingMode.TIMER, 1)
        val overflowing =
            ActivityExecutionFactory { ActivityExecutionId("overflow") }
                .startTimed(
                    timer,
                    latestPersisted,
                    latestPersisted,
                    ZoneOffset.UTC,
                )
        assertThrows(ArithmeticException::class.java) {
            TimerDeadlineCalculator.deadline(overflowing, Duration.ofMillis(1), TimerZeroBehavior.FINISH)
        }
    }

    private fun engine(): SequenceRuntimeEngine {
        var execution = 0
        var occurrence = 0
        var child = 0
        var pause = 0
        var interval = 0
        return SequenceRuntimeEngine(
            SequenceExecutionFactory(
                { SequenceExecutionId("execution-${++execution}") },
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
        autoAdvance: Boolean,
        countdownSeconds: Long = 0,
        noLiveAccounting: NoLiveTimeAccounting = NoLiveTimeAccounting.ACTIVE,
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
            Duration.ofSeconds(99),
            Duration.ofSeconds(countdownSeconds),
            true,
            true,
            false,
            true,
            true,
            noLiveAccounting,
        ),
        nodes =
            activityIds.mapIndexed { index, id ->
                SequenceSnapshotActivityStep(SequenceSnapshotNodeId("step-$index"), index, id)
            },
    )

    private fun instant(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}
