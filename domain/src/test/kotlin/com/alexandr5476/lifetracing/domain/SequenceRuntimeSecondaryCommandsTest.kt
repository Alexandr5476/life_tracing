@file:Suppress("LargeClass", "LongMethod", "MaxLineLength")

// Scenario setup stays beside the asserted runtime transition.

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

class SequenceRuntimeSecondaryCommandsTest {
    @Test
    fun goNowFinalizesTimedCurrentSkipsUntouchedAndStartsTargetImmediately() {
        val activities =
            listOf(
                activity("first", TimeTrackingMode.TIMER, 60),
                activity("middle", TimeTrackingMode.STOPWATCH),
                activity("target", TimeTrackingMode.STOPWATCH),
            ).associateBy(ActivityConfigSnapshot::id)
        val runtime = engine()
        val started = runtime.start(sequence(activities.keys.toList()), activities, at(0), at(0), ZoneOffset.UTC)

        val jumped =
            runtime.goNow(
                started,
                started.execution.occurrences[2].id,
                at(10),
                sequence(activities.keys.toList()),
                activities,
            )

        assertEquals(OccurrenceCompletionReason.JUMP, jumped.execution.occurrences[0].completionReason)
        assertEquals(
            Duration.ofSeconds(10),
            jumped.children.getValue(started.execution.occurrences[0].id).activeDuration,
        )
        assertEquals(RuntimeOccurrenceStatus.SKIPPED, jumped.execution.occurrences[1].status)
        assertFalse(jumped.children.containsKey(started.execution.occurrences[1].id))
        assertEquals(
            started.execution.occurrences[2].activitySnapshotId,
            jumped.execution.occurrences[2].activitySnapshotId,
        )
        assertEquals(jumped.execution.occurrences[2].id, jumped.execution.currentOccurrenceId)
        assertEquals(at(10), jumped.execution.occurrences[2].enteredAt)
        assertEquals(
            SequenceIntervalKind.ACTIVE_STEP,
            jumped.execution.intervals
                .single { it.endedAt == null }
                .kind,
        )
    }

    @Test
    fun goNowFinalizesNoLiveWithoutInventingDuration() {
        val activities =
            listOf(
                activity("no-live", TimeTrackingMode.NO_LIVE_TRACKING),
                activity("target", TimeTrackingMode.STOPWATCH),
            ).associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(activities.keys.toList())
        val runtime = engine()
        val started = runtime.start(snapshot, activities, at(0), at(0), ZoneOffset.UTC)

        val jumped = runtime.goNow(started, started.execution.occurrences[1].id, at(10), snapshot, activities)

        val completed = jumped.children.getValue(started.execution.occurrences[0].id)
        assertEquals(ActivityExecutionStatus.COMPLETED, completed.status)
        assertNull(completed.activeDuration)
        assertEquals(OccurrenceCompletionReason.JUMP, jumped.execution.occurrences[0].completionReason)
        assertEquals(jumped.execution.occurrences[1].id, jumped.execution.currentOccurrenceId)
    }

    @Test
    fun secondaryCommandsReconcileBeforeCheckingEligibility() {
        val activities =
            listOf(
                activity("timer", TimeTrackingMode.TIMER, 10),
                activity("next", TimeTrackingMode.STOPWATCH),
            ).associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(activities.keys.toList())
        val runtime = engine()
        val started = runtime.start(snapshot, activities, at(0), at(0), ZoneOffset.UTC)

        assertThrows(IllegalArgumentException::class.java) {
            runtime.goNow(started, started.execution.occurrences[1].id, at(20), snapshot, activities)
        }
    }

    @Test
    fun makeNextOnlyReordersFutureOccurrences() {
        val activities =
            (1..4)
                .map {
                    activity(
                        "step-$it",
                        TimeTrackingMode.STOPWATCH,
                    )
                }.associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(activities.keys.toList())
        val runtime = engine()
        val started = runtime.start(snapshot, activities, at(0), at(0), ZoneOffset.UTC)
        val history = started.execution.intervals
        val children = started.children

        val reordered = runtime.makeNext(started, started.execution.occurrences[3].id, at(5), snapshot, activities)

        assertEquals(
            listOf(0, 3, 1, 2),
            reordered.execution.occurrences.map {
                started.execution.occurrences.indexOfFirst { old ->
                    old.id ==
                        it.id
                }
            },
        )
        assertEquals((0..3).toList(), reordered.execution.occurrences.map(RuntimeOccurrence::runtimePosition))
        assertEquals(history, reordered.execution.intervals)
        assertEquals(children, reordered.children)
        assertEquals(started.execution.currentOccurrenceId, reordered.execution.currentOccurrenceId)
    }

    @Test
    fun runtimeAddSupportsEndAfterCurrentAndStartNowOnlyFromWaitingNext() {
        val base =
            listOf(
                activity("first", TimeTrackingMode.STOPWATCH),
                activity("next", TimeTrackingMode.STOPWATCH),
            ).associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(base.keys.toList(), autoAdvance = false)
        val runtime = engine()
        val started = runtime.start(snapshot, base, at(0), at(0), ZoneOffset.UTC)
        val addedEnd = activity("end", TimeTrackingMode.STOPWATCH)
        val atEnd =
            runtime.addRuntimeOccurrence(
                started,
                addedEnd,
                RuntimeInsertionPlacement.TO_END,
                at(1),
                snapshot,
                base,
            )
        assertRuntimeAdded(atEnd.execution.occurrences.last(), addedEnd)

        val addedAfter = activity("after", TimeTrackingMode.STOPWATCH)
        val afterCurrent =
            runtime.addRuntimeOccurrence(
                started,
                addedAfter,
                RuntimeInsertionPlacement.AFTER_CURRENT,
                at(1),
                snapshot,
                base,
            )
        assertRuntimeAdded(afterCurrent.execution.occurrences[1], addedAfter)
        assertEquals(started.execution.intervals, afterCurrent.execution.intervals)

        assertThrows(IllegalArgumentException::class.java) {
            runtime.addRuntimeOccurrence(
                started,
                activity("invalid", TimeTrackingMode.STOPWATCH),
                RuntimeInsertionPlacement.START_NOW,
                at(1),
                snapshot,
                base,
            )
        }
        val waiting = runtime.completeCurrent(started, started.execution.currentOccurrenceId!!, at(2), snapshot, base)
        val now = activity("now", TimeTrackingMode.STOPWATCH)
        val startedNow =
            runtime.addRuntimeOccurrence(
                waiting,
                now,
                RuntimeInsertionPlacement.START_NOW,
                at(3),
                snapshot,
                base,
            )
        val occurrence = startedNow.execution.occurrences.single { it.activitySnapshotId == now.id }
        assertEquals(occurrence.id, startedNow.execution.currentOccurrenceId)
        assertTrue(startedNow.children.containsKey(occurrence.id))
        assertEquals(
            SequenceExecutionStatus.RUNNING,
            runtime.reconcile(startedNow, snapshot, base + (now.id to now), at(4)).execution.status,
        )
    }

    @Test
    fun runtimeAddedTimerStopwatchAndNoLiveUseInheritedSettingsWithoutSourceStep() {
        val base =
            listOf(
                activity("first", TimeTrackingMode.STOPWATCH),
                activity("next", TimeTrackingMode.STOPWATCH),
            ).associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(base.keys.toList(), autoAdvance = false)
        val runtime = engine()
        val started = runtime.start(snapshot, base, at(0), at(0), ZoneOffset.UTC)
        val waiting = runtime.completeCurrent(started, started.execution.currentOccurrenceId!!, at(1), snapshot, base)
        val timer = activity("timer", TimeTrackingMode.TIMER, 5)
        val timerState =
            runtime.addRuntimeOccurrence(
                waiting,
                timer,
                RuntimeInsertionPlacement.START_NOW,
                at(2),
                snapshot,
                base,
            )
        val timerActivities = base + (timer.id to timer)
        assertEquals(
            SequenceExecutionStatus.RUNNING,
            runtime.reconcile(timerState, snapshot, timerActivities, at(7)).execution.status,
        )

        val noLive = activity("no-live", TimeTrackingMode.NO_LIVE_TRACKING)
        val noLiveState =
            runtime.addRuntimeOccurrence(
                waiting,
                noLive,
                RuntimeInsertionPlacement.START_NOW,
                at(2),
                snapshot,
                base,
            )
        val noLiveActivities = base + (noLive.id to noLive)
        val currentId = noLiveState.execution.currentOccurrenceId!!
        val completed = runtime.completeCurrent(noLiveState, currentId, at(3), snapshot, noLiveActivities)
        assertNull(completed.children.getValue(currentId).activeDuration)
        assertEquals(
            OccurrenceCompletionReason.MANUAL_FINISH,
            completed.execution.occurrences
                .single {
                    it.id ==
                        currentId
                }.completionReason,
        )
    }

    @Test
    fun startNowInterruptsTransitionCountdownAndReturnsToItsConsumedFrontier() {
        val first = activity("first", TimeTrackingMode.STOPWATCH)
        val target = activity("target", TimeTrackingMode.STOPWATCH)
        val added = activity("added", TimeTrackingMode.STOPWATCH)
        val activities = listOf(first, target).associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(activities.keys.toList(), countdownSeconds = 10)
        val runtime = engine()
        val started = runtime.start(snapshot, activities, at(0), at(0), ZoneOffset.UTC)
        val countdown =
            runtime.completeCurrent(
                started,
                started.execution.currentOccurrenceId!!,
                at(1),
                snapshot,
                activities,
            )
        val targetOccurrence = countdown.execution.occurrences[1]

        val inserted =
            runtime.addRuntimeOccurrence(
                countdown,
                added,
                RuntimeInsertionPlacement.START_NOW,
                at(4),
                snapshot,
                activities,
            )
        val addedOccurrence = inserted.execution.occurrences.single { it.activitySnapshotId == added.id }

        assertEquals(
            at(4),
            inserted.execution.intervals
                .single { it.occurrenceId == targetOccurrence.id }
                .endedAt,
        )
        assertEquals(
            RuntimeOccurrenceStatus.NOT_STARTED,
            inserted.execution.occurrences
                .single {
                    it.id ==
                        targetOccurrence.id
                }.status,
        )
        assertFalse(inserted.children.containsKey(targetOccurrence.id))
        assertEquals(addedOccurrence.id, inserted.execution.currentOccurrenceId)

        val resumedCountdown =
            runtime.completeCurrent(
                inserted,
                addedOccurrence.id,
                at(6),
                snapshot,
                activities + (added.id to added),
            )
        val staleDeadline = runtime.reconcile(resumedCountdown, snapshot, activities + (added.id to added), at(11))
        assertNull(staleDeadline.execution.currentOccurrenceId)
        assertEquals(
            RuntimeOccurrenceStatus.NOT_STARTED,
            staleDeadline.execution.occurrences
                .single {
                    it.id ==
                        targetOccurrence.id
                }.status,
        )
        val resumed = runtime.reconcile(staleDeadline, snapshot, activities + (added.id to added), at(13))
        assertEquals(targetOccurrence.id, resumed.execution.currentOccurrenceId)
    }

    @Test
    fun startNowAtTransitionBoundaryReconcilesTargetBeforeRejecting() {
        val activities =
            listOf(
                activity("first", TimeTrackingMode.STOPWATCH),
                activity("target", TimeTrackingMode.STOPWATCH),
            ).associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(activities.keys.toList(), countdownSeconds = 10)
        val runtime = engine()
        val started = runtime.start(snapshot, activities, at(0), at(0), ZoneOffset.UTC)
        val countdown =
            runtime.completeCurrent(started, started.execution.currentOccurrenceId!!, at(1), snapshot, activities)

        assertThrows(IllegalArgumentException::class.java) {
            runtime.addRuntimeOccurrence(
                countdown,
                activity("added", TimeTrackingMode.STOPWATCH),
                RuntimeInsertionPlacement.START_NOW,
                at(11),
                snapshot,
                activities,
            )
        }
        assertEquals(
            countdown.execution.occurrences[1].id,
            runtime.reconcile(countdown, snapshot, activities, at(11)).execution.currentOccurrenceId,
        )
    }

    @Test
    fun secondaryInsertionAllowsTheTenthousandthOccurrenceAndRejectsFurtherAddOrReplayBeforeIdGeneration() {
        var generatedIds = 0
        val activity = activity("step", TimeTrackingMode.STOPWATCH)
        val activities = mapOf(activity.id to activity)
        val snapshot =
            sequenceWithNodes(
                listOf(
                    SequenceSnapshotRepeatBlock(
                        SequenceSnapshotNodeId("repeat"),
                        0,
                        RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES - 1,
                        listOf(SequenceSnapshotActivityStep(SequenceSnapshotNodeId("step"), 0, activity.id)),
                    ),
                ),
            )
        val runtime = engine { SequenceOccurrenceId("occurrence-${++generatedIds}") }
        val started = runtime.start(snapshot, activities, at(0), at(0), ZoneOffset.UTC)

        val atLimit =
            runtime.addRuntimeOccurrence(
                started,
                activity("added", TimeTrackingMode.STOPWATCH),
                RuntimeInsertionPlacement.TO_END,
                at(1),
                snapshot,
                activities,
            )

        assertEquals(
            RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES,
            atLimit.execution.occurrences.size,
        )
        val completed =
            runtime.completeCurrent(
                atLimit,
                atLimit.execution.currentOccurrenceId!!,
                at(2),
                snapshot,
                activities,
            )
        val beforeRejectedIds = generatedIds

        assertThrows(IllegalArgumentException::class.java) {
            runtime.addRuntimeOccurrence(
                atLimit,
                activity("rejected", TimeTrackingMode.STOPWATCH),
                RuntimeInsertionPlacement.TO_END,
                at(2),
                snapshot,
                activities,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.doAgain(
                completed,
                atLimit.execution.occurrences
                    .first()
                    .id,
                RuntimeInsertionPlacement.AFTER_CURRENT,
                at(2),
                snapshot,
                activities,
            )
        }

        assertEquals(
            beforeRejectedIds,
            generatedIds,
        )
    }

    @Test
    fun doAgainPreservesFrozenTimerOverrideDuringTransitionCountdown() {
        val timer = activity("timer", TimeTrackingMode.TIMER, 5)
        val next = activity("next", TimeTrackingMode.STOPWATCH)
        val activities = listOf(timer, next).associateBy(ActivityConfigSnapshot::id)
        val stepId = SequenceSnapshotNodeId("timer-step")
        val snapshot =
            sequenceWithNodes(
                listOf(
                    SequenceSnapshotActivityStep(
                        stepId,
                        0,
                        timer.id,
                        SequenceStepOverrides(timerZeroBehavior = TimerZeroBehavior.OVERTIME),
                    ),
                    SequenceSnapshotActivityStep(SequenceSnapshotNodeId("next-step"), 1, next.id),
                ),
                countdownSeconds = 10,
            )
        val runtime = engine()
        val started = runtime.start(snapshot, activities, at(0), at(0), ZoneOffset.UTC)
        val original = started.execution.occurrences.first()
        val countdown = runtime.completeCurrent(started, original.id, at(1), snapshot, activities)

        val replayed =
            runtime.doAgain(
                countdown,
                original.id,
                RuntimeInsertionPlacement.START_NOW,
                at(2),
                snapshot,
                activities,
            )
        val replay = replayed.execution.occurrences.single { it.id != original.id && it.activitySnapshotId == timer.id }

        assertTrue(replay.id != original.id)
        assertEquals(timer.id, replay.activitySnapshotId)
        assertEquals(stepId, replay.sourceSequenceSnapshotNodeId)
        assertFalse(replay.isRuntimeAdded)
        assertEquals(replay.id, replayed.execution.currentOccurrenceId)
        assertEquals(
            replay.id,
            runtime.reconcile(replayed, snapshot, activities, at(100)).execution.currentOccurrenceId,
        )
    }

    @Test
    fun doAgainPreservesRepeatStepAndIterationProvenance() {
        val repeated = activity("repeated", TimeTrackingMode.STOPWATCH)
        val activities = mapOf(repeated.id to repeated)
        val repeatId = SequenceSnapshotNodeId("repeat")
        val stepId = SequenceSnapshotNodeId("repeat-step")
        val snapshot =
            sequenceWithNodes(
                listOf(
                    SequenceSnapshotRepeatBlock(
                        repeatId,
                        0,
                        2,
                        listOf(SequenceSnapshotActivityStep(stepId, 0, repeated.id)),
                    ),
                ),
                autoAdvance = false,
            )
        val runtime = engine()
        val started = runtime.start(snapshot, activities, at(0), at(0), ZoneOffset.UTC)
        val original = started.execution.occurrences.first()
        val waiting = runtime.completeCurrent(started, original.id, at(1), snapshot, activities)

        val replayed =
            runtime.doAgain(
                waiting,
                original.id,
                RuntimeInsertionPlacement.START_NOW,
                at(2),
                snapshot,
                activities,
            )
        val replay =
            replayed.execution.occurrences.single {
                it.id != original.id &&
                    it.status == RuntimeOccurrenceStatus.CURRENT
            }

        assertEquals(stepId, replay.sourceSequenceSnapshotNodeId)
        assertEquals(repeatId, replay.repeatSourceSnapshotNodeId)
        assertEquals(1, replay.repeatIteration)
        assertFalse(replay.isRuntimeAdded)
    }

    @Test
    fun doAgainCreatesFreshOccurrenceAndChildFromSnapshotDefaults() {
        val repeated = activity("repeated", TimeTrackingMode.STOPWATCH, defaultNumber = 7)
        val next = activity("next", TimeTrackingMode.STOPWATCH)
        val activities = listOf(repeated, next).associateBy(ActivityConfigSnapshot::id)
        val snapshot = sequence(activities.keys.toList(), autoAdvance = false)
        val runtime = engine()
        val started = runtime.start(snapshot, activities, at(0), at(0), ZoneOffset.UTC)
        val oldId = started.execution.currentOccurrenceId!!
        val changedOld =
            started.copy(
                children =
                    started.children +
                        (
                            oldId to
                                ActivityExecutionValuePolicy.apply(
                                    started.children.getValue(oldId),
                                    repeated,
                                    listOf(
                                        ActivityExecutionValueOverride(
                                            ActivitySnapshotFieldId("field"),
                                            NumberExecutionValue(ActivitySnapshotFieldId("field"), 9),
                                        ),
                                    ),
                                )
                        ),
            )
        val waiting = runtime.completeCurrent(changedOld, oldId, at(1), snapshot, activities)

        val repeatedState =
            runtime.doAgain(
                waiting,
                oldId,
                RuntimeInsertionPlacement.START_NOW,
                at(2),
                snapshot,
                activities,
            )
        val newId = repeatedState.execution.currentOccurrenceId!!
        assertTrue(newId != oldId)
        assertEquals(
            repeated.id,
            repeatedState.execution.occurrences
                .single { it.id == newId }
                .activitySnapshotId,
        )
        assertEquals(
            9,
            (
                repeatedState.children
                    .getValue(oldId)
                    .values
                    .single() as NumberExecutionValue
            ).scaledValue,
        )
        assertEquals(
            7,
            (
                repeatedState.children
                    .getValue(newId)
                    .values
                    .single() as NumberExecutionValue
            ).scaledValue,
        )
        assertTrue(repeatedState.children.getValue(newId).id != repeatedState.children.getValue(oldId).id)
    }

    @Test
    fun endEarlyFinalizesTimedAndNoLiveCurrentAndLeavesRemainingUntouched() {
        val timed =
            listOf(
                activity("timed", TimeTrackingMode.TIMER, 60),
                activity("remaining", TimeTrackingMode.STOPWATCH),
            ).associateBy(ActivityConfigSnapshot::id)
        val timedSnapshot = sequence(timed.keys.toList())
        val runtime = engine()
        val timedEnded =
            runtime.endEarly(
                runtime.start(timedSnapshot, timed, at(0), at(0), ZoneOffset.UTC),
                at(10),
                timedSnapshot,
                timed,
            )
        assertEquals(SequenceExecutionStatus.ENDED_EARLY, timedEnded.execution.status)
        assertEquals(
            OccurrenceCompletionReason.SEQUENCE_ENDED_EARLY,
            timedEnded.execution.occurrences[0].completionReason,
        )
        assertEquals(Duration.ofSeconds(10), timedEnded.execution.activeDuration)
        assertTrue(timedEnded.execution.intervals.none { it.endedAt == null })
        assertFalse(timedEnded.children.containsKey(timedEnded.execution.occurrences[1].id))

        val noLive =
            listOf(
                activity("no-live", TimeTrackingMode.NO_LIVE_TRACKING),
                activity("remaining", TimeTrackingMode.STOPWATCH),
            ).associateBy(ActivityConfigSnapshot::id)
        val noLiveSnapshot = sequence(noLive.keys.toList(), noLiveAccounting = NoLiveTimeAccounting.PAUSE)
        val noLiveEnded =
            runtime.endEarly(
                runtime.start(noLiveSnapshot, noLive, at(0), at(0), ZoneOffset.UTC),
                at(10),
                noLiveSnapshot,
                noLive,
            )
        assertNull(
            noLiveEnded.children.values
                .single()
                .activeDuration,
        )
        assertEquals(Duration.ZERO, noLiveEnded.execution.activeDuration)
        assertEquals(Duration.ofSeconds(10), noLiveEnded.execution.pauseDuration)
    }

    @Test
    fun endEarlyClosesWaitingAndTransitionWithoutSyntheticChildAndRejectsTerminalCommands() {
        val activities =
            listOf(
                activity("first", TimeTrackingMode.STOPWATCH),
                activity("next", TimeTrackingMode.STOPWATCH),
            ).associateBy(ActivityConfigSnapshot::id)
        val runtime = engine()
        val waitingSnapshot = sequence(activities.keys.toList(), autoAdvance = false)
        val waitingStart = runtime.start(waitingSnapshot, activities, at(0), at(0), ZoneOffset.UTC)
        val waiting =
            runtime.completeCurrent(
                waitingStart,
                waitingStart.execution.currentOccurrenceId!!,
                at(1),
                waitingSnapshot,
                activities,
            )
        val waitingEnded = runtime.endEarly(waiting, at(2), waitingSnapshot, activities)
        assertEquals(SequenceExecutionStatus.ENDED_EARLY, waitingEnded.execution.status)
        assertEquals(1, waitingEnded.children.size)

        val transitionSnapshot = sequence(activities.keys.toList(), countdownSeconds = 30)
        val transitionStart = runtime.start(transitionSnapshot, activities, at(0), at(0), ZoneOffset.UTC)
        val transition =
            runtime.completeCurrent(
                transitionStart,
                transitionStart.execution.currentOccurrenceId!!,
                at(1),
                transitionSnapshot,
                activities,
            )
        val transitionEnded = runtime.endEarly(transition, at(2), transitionSnapshot, activities)
        assertEquals(1, transitionEnded.children.size)
        assertTrue(transitionEnded.execution.intervals.none { it.endedAt == null })
        assertThrows(IllegalArgumentException::class.java) {
            runtime.endEarly(transitionEnded, at(3), transitionSnapshot, activities)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.makeNext(
                transitionEnded,
                transitionEnded.execution.occurrences[1].id,
                at(3),
                transitionSnapshot,
                activities,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.goNow(waiting, waiting.execution.occurrences[0].id, at(3), waitingSnapshot, activities)
        }
    }

    private fun assertRuntimeAdded(
        occurrence: RuntimeOccurrence,
        activity: ActivityConfigSnapshot,
    ) {
        assertTrue(occurrence.isRuntimeAdded)
        assertEquals(activity.id, occurrence.activitySnapshotId)
        assertNull(occurrence.sourceSequenceSnapshotNodeId)
        assertNull(occurrence.repeatSourceSnapshotNodeId)
        assertNull(occurrence.repeatIteration)
    }

    private fun engine(nextOccurrenceId: (() -> SequenceOccurrenceId)? = null): SequenceRuntimeEngine {
        var execution = 0
        var occurrence = 0
        var child = 0
        var pause = 0
        var interval = 0
        return SequenceRuntimeEngine(
            SequenceExecutionFactory(
                {
                    SequenceExecutionId("execution-${++execution}")
                },
                RuntimeOccurrenceMaterializer {
                    nextOccurrenceId?.invoke() ?: SequenceOccurrenceId("occurrence-${++occurrence}")
                },
            ),
            ActivityExecutionFactory { ActivityExecutionId("child-${++child}") },
            { ActivityExecutionPauseId("pause-${++pause}") },
            { SequenceIntervalId("interval-${++interval}") },
            {
                nextOccurrenceId?.invoke() ?: SequenceOccurrenceId("occurrence-${++occurrence}")
            },
        )
    }

    private fun activity(
        id: String,
        mode: TimeTrackingMode,
        timerSeconds: Long? = null,
        defaultNumber: Long? = null,
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
        at(0),
        fields =
            defaultNumber?.let {
                listOf(
                    ActivitySnapshotField(
                        ActivitySnapshotFieldId("field"),
                        null,
                        0,
                        "Field",
                        type = CustomFieldType.NUMBER,
                        defaultNumberScaled = it,
                    ),
                )
            }
                ?: emptyList(),
    )

    private fun sequence(
        activityIds: List<ActivitySnapshotId>,
        autoAdvance: Boolean = true,
        countdownSeconds: Long = 0,
        noLiveAccounting: NoLiveTimeAccounting = NoLiveTimeAccounting.ACTIVE,
    ) = SequenceConfigSnapshot(
        SequenceSnapshotId("sequence"),
        "Sequence",
        null,
        null,
        null,
        null,
        at(0),
        SequenceSnapshotSettings(
            autoAdvance,
            Duration.ZERO,
            Duration.ofSeconds(countdownSeconds),
            true,
            true,
            false,
            true,
            true,
            noLiveAccounting,
        ),
        nodes =
            activityIds.mapIndexed {
                index,
                id,
                ->
                SequenceSnapshotActivityStep(SequenceSnapshotNodeId("step-$index"), index, id)
            },
    )

    private fun sequenceWithNodes(
        nodes: List<SequenceSnapshotNode>,
        autoAdvance: Boolean = true,
        countdownSeconds: Long = 0,
    ) = SequenceConfigSnapshot(
        SequenceSnapshotId("sequence"),
        "Sequence",
        null,
        null,
        null,
        null,
        at(0),
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
        nodes = nodes,
    )

    private fun at(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}
