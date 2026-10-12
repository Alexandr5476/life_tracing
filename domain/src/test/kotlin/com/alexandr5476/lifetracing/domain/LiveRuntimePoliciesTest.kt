package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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

    @Test
    fun repeatTimerCountdownCatchUpReadsAggregateListsLinearlyThroughTheOccurrenceLimit() {
        val reads =
            listOf(512, 2_048, RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES).map { count ->
                val timer = activity("timer", TimeTrackingMode.TIMER, 1)
                val activities = mapOf(timer.id to timer)
                val base = sequence(List(count / 2) { timer.id }, autoAdvance = true, countdownSeconds = 1)
                val children = CountingList(base.nodes.filterIsInstance<SequenceSnapshotActivityStep>())
                val snapshot =
                    base.copy(
                        nodes = listOf(SequenceSnapshotRepeatBlock(SequenceSnapshotNodeId("repeat"), 0, 2, children)),
                    )
                val runtime = engine()
                val started = runtime.start(snapshot, activities, instant(0), instant(0), ZoneOffset.UTC)
                val occurrences = CountingList(started.execution.occurrences)
                val intervals = CountingList(started.execution.intervals)
                children.reads = 0
                val hydratedExecution = started.execution.copy(occurrences = occurrences, intervals = intervals)

                val finished =
                    runtime.reconcile(
                        started.copy(execution = hydratedExecution),
                        snapshot,
                        activities,
                        instant(3L * count),
                    )

                assertEquals(SequenceExecutionStatus.COMPLETED, finished.execution.status)
                assertEquals(instant(2L * count - 1), finished.execution.endedAt)
                assertEquals(count, finished.children.size)
                val firstOccurrenceId = requireNotNull(started.execution.currentOccurrenceId)
                assertEquals(started.currentChild?.id, finished.children.getValue(firstOccurrenceId).id)
                val totalReads = children.reads + occurrences.reads + intervals.reads
                assertTrue(totalReads <= 8L * count, "Aggregate reads: $totalReads for $count occurrences")
                totalReads
            }

        assertTrue(reads[1] <= reads[0] * 4 + 8, "Four times the due suffix must not cause quadratic hydration work")
    }

    @Test
    @Suppress("LongMethod") // Keep each logical boundary and its resulting frozen facts in one scenario.
    fun realBerlinDstChainsAgreeWithStepwiseAbsoluteReconciliation() {
        val berlin = ZoneId.of("Europe/Berlin")
        for (start in listOf(
            Instant.parse("2026-03-29T00:59:30Z"),
            Instant.parse("2026-10-25T00:59:30Z"),
        )) {
            val timer = activity("dst-timer", TimeTrackingMode.TIMER, 60)
            val activities = mapOf(timer.id to timer)
            val base = sequence(listOf(timer.id), autoAdvance = true, countdownSeconds = 10)
            val snapshot =
                base.copy(
                    nodes =
                        listOf(
                            SequenceSnapshotRepeatBlock(
                                SequenceSnapshotNodeId("dst-repeat"),
                                0,
                                3,
                                base.nodes.filterIsInstance<SequenceSnapshotActivityStep>(),
                            ),
                        ),
                )
            val stepwiseEngine = engine()
            val delayedEngine = engine()
            var stepwise = stepwiseEngine.start(snapshot, activities, start, start, berlin)
            val initial = delayedEngine.start(snapshot, activities, start, start, berlin)
            for (seconds in listOf(60L, 70L, 130L, 140L, 200L)) {
                val deadline = start.plusSeconds(seconds)
                assertEquals(
                    stepwise,
                    stepwiseEngine.reconcile(stepwise, snapshot, activities, deadline.minusMillis(1)),
                )
                stepwise = stepwiseEngine.reconcile(stepwise, snapshot, activities, deadline)
                assertEquals(stepwise, stepwiseEngine.reconcile(stepwise, snapshot, activities, deadline.plusMillis(1)))
            }
            val delayed = delayedEngine.reconcile(initial, snapshot, activities, start.plusSeconds(7_200))
            assertEquals(stepwise, delayed)
            assertEquals(
                listOf(0L, 70L, 140L).map(start::plusSeconds),
                delayed.execution.occurrences.map { it.enteredAt },
            )
            assertEquals(
                listOf(60L, 130L, 200L).map(start::plusSeconds),
                delayed.execution.occurrences.map { it.completedAt },
            )
            assertEquals(listOf(1, 2, 3), delayed.execution.occurrences.map { it.repeatIteration })
            assertTrue(
                delayed.execution.occurrences.all {
                    it.repeatSourceSnapshotNodeId == SequenceSnapshotNodeId("dst-repeat") &&
                        it.sourceSequenceSnapshotNodeId == SequenceSnapshotNodeId("step-0") &&
                        !it.isRuntimeAdded
                },
            )
            assertEquals(initial.execution.occurrences.map { it.id }, delayed.execution.occurrences.map { it.id })
            assertEquals(
                initial.currentChild!!.id,
                delayed.children.values
                    .minBy { requireNotNull(it.startedAt) }
                    .id,
            )
            assertEquals(Duration.ofSeconds(180), delayed.execution.activeDuration)
            assertEquals(Duration.ofSeconds(20), delayed.execution.pauseDuration)
            assertEquals(start.plusSeconds(200), delayed.execution.endedAt)
            assertEquals(berlin, delayed.execution.originalZoneId)
            assertEquals(initial.execution.originalUtcOffsetMinutes, delayed.execution.originalUtcOffsetMinutes)
            assertEquals(initial.execution.primaryLocalDate, delayed.execution.primaryLocalDate)
            assertEquals(delayed, delayedEngine.reconcile(delayed, snapshot, activities, start.minusSeconds(1)))
        }
    }

    @Test
    fun repeatedBerlin0230LabelsDoNotRepeatTimerProgressOrHistoricalAttribution() {
        val berlin = ZoneId.of("Europe/Berlin")
        val first = Instant.parse("2026-10-25T00:30:00Z")
        val second = Instant.parse("2026-10-25T01:30:00Z")
        assertEquals(first.atZone(berlin).toLocalDateTime(), second.atZone(berlin).toLocalDateTime())
        val timer = activity("overlap", TimeTrackingMode.TIMER, 3_600)
        val started =
            ActivityExecutionFactory { ActivityExecutionId("overlap-execution") }
                .startTimed(timer, first, first, berlin)
        assertEquals(second, TimerDeadlineCalculator.deadline(started, timer.timerTarget!!, TimerZeroBehavior.FINISH))
        val completed = ActivityExecutionTransitions.complete(started, second)
        assertEquals(Duration.ofHours(1), completed.activeDuration)
        assertEquals(120, completed.originalUtcOffsetMinutes)
        assertEquals(ZoneOffset.ofHours(1), second.atZone(berlin).offset)
        assertEquals(started.snapshotId, completed.snapshotId)
        assertEquals(started.primaryLocalDate, completed.primaryLocalDate)
    }

    @Test
    fun pauseAcrossDstUsesPersistedDurationAndDisplayUsesOnlyItsCurrentAnchor() {
        val berlin = ZoneId.of("Europe/Berlin")
        for (start in listOf(
            Instant.parse("2026-03-29T00:59:30Z"),
            Instant.parse("2026-10-25T00:59:30Z"),
        )) {
            val timer = activity("paused-dst", TimeTrackingMode.TIMER, 60)
            val started =
                ActivityExecutionFactory { ActivityExecutionId("paused-dst") }
                    .startTimed(timer, start, start, berlin)
            val paused =
                ActivityExecutionTransitions.pause(started, ActivityExecutionPauseId("pause"), start.plusSeconds(10))
            assertNull(TimerDeadlineCalculator.deadline(paused, timer.timerTarget!!, TimerZeroBehavior.FINISH))
            val resumed = ActivityExecutionTransitions.resume(paused, start.plusSeconds(7_200))
            val deadline = start.plusSeconds(7_250)
            assertEquals(
                deadline,
                TimerDeadlineCalculator.deadline(resumed, timer.timerTarget, TimerZeroBehavior.FINISH),
            )
            val runtime =
                ActiveActivityRuntime(
                    ActiveSession(
                        ActiveSessionKind.ACTIVITY,
                        ActiveSessionState.RUNNING,
                        resumed.id,
                        null,
                        resumed.updatedAt,
                    ),
                    resumed,
                    timer,
                )
            val anchor = WallMonotonicAnchor(start.plusSeconds(7_200), 500_000)
            val display = RuntimeDisplayBaseline.capture(runtime, anchor, 500_000)
            assertEquals(Duration.ofSeconds(10), display.activeElapsed(500_000))
            assertEquals(Duration.ofSeconds(11), display.activeElapsed(501_000))
            assertEquals(Duration.ofSeconds(49), display.timerRemaining(501_000))
            assertEquals(550_000L, anchor.elapsedAt(deadline))
            val replacement = WallMonotonicAnchor(start.plusSeconds(7_210), 501_000)
            val fresh = RuntimeDisplayBaseline.capture(runtime, replacement, 501_000)
            assertEquals(Duration.ofSeconds(20), fresh.activeElapsed(501_000))
            assertEquals(Duration.ofSeconds(11), display.activeElapsed(501_000))
            assertEquals(resumed.pauses, runtime.execution.pauses)
        }
    }

    @Test
    fun transitionCountdownDeadlineCrossesRealDstWithoutACurrentChildUntilExactlyDue() {
        val berlin = ZoneId.of("Europe/Berlin")
        for (start in listOf(
            Instant.parse("2026-03-29T00:59:57Z"),
            Instant.parse("2026-10-25T00:59:57Z"),
        )) {
            val timer = activity("short", TimeTrackingMode.TIMER, 1)
            val stopwatch = activity("target", TimeTrackingMode.STOPWATCH)
            val activities = listOf(timer, stopwatch).associateBy { it.id }
            val snapshot = sequence(activities.keys.toList(), autoAdvance = true, countdownSeconds = 10)
            val runtime = engine()
            val started = runtime.start(snapshot, activities, start, start, berlin)
            val countdown = runtime.reconcile(started, snapshot, activities, start.plusSeconds(1))
            val target =
                countdown.execution.occurrences
                    .last()
                    .id
            assertNull(countdown.execution.currentOccurrenceId)
            assertNull(countdown.currentChild)
            val deadline = start.plusSeconds(11)
            assertEquals(countdown, runtime.reconcile(countdown, snapshot, activities, deadline.minusMillis(1)))
            val entered = runtime.reconcile(countdown, snapshot, activities, deadline)
            assertEquals(target, entered.execution.currentOccurrenceId)
            assertEquals(deadline, entered.currentChild!!.startedAt)
            assertEquals(2, entered.children.size)
            assertEquals(entered, runtime.reconcile(entered, snapshot, activities, deadline.plusMillis(1)))
            val delayedEngine = engine()
            val delayedInitial = delayedEngine.start(snapshot, activities, start, start, berlin)
            assertEquals(
                entered,
                delayedEngine.reconcile(delayedInitial, snapshot, activities, deadline.plusSeconds(7_200)),
            )
            val interval = entered.execution.intervals.single { it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN }
            assertEquals(start.plusSeconds(1), interval.startedAt)
            assertEquals(deadline, interval.endedAt)
            assertEquals(target, interval.occurrenceId)
        }
    }

    private class CountingList<T>(
        private val items: List<T>,
    ) : AbstractList<T>() {
        var reads = 0L
        override val size: Int
            get() = items.size

        override fun get(index: Int): T {
            reads++
            return items[index]
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
