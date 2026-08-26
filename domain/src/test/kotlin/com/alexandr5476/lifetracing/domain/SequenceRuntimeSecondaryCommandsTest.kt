@file:Suppress("LongMethod", "MaxLineLength") // Scenario setup stays beside the asserted runtime transition.

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

    private fun engine(): SequenceRuntimeEngine {
        var execution = 0
        var occurrence = 0
        var child = 0
        var pause = 0
        var interval = 0
        return SequenceRuntimeEngine(
            SequenceExecutionFactory({
                SequenceExecutionId("execution-${++execution}")
            }, RuntimeOccurrenceMaterializer { SequenceOccurrenceId("occurrence-${++occurrence}") }),
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

    private fun at(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}
