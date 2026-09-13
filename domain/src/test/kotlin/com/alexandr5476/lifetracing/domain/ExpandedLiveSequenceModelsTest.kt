package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ExpandedLiveSequenceModelsTest {
    @Test
    fun canonicalResolverClassifiesAllFivePersistableActiveStates() {
        val activities = activities()
        val waitingSnapshot = snapshot(autoAdvance = false, beforeEachCountdown = Duration.ZERO)
        val waitingEngine = engine()
        val running = waitingEngine.start(waitingSnapshot, activities, at(0), at(0), ZoneOffset.UTC)
        assertEquals(
            ActiveSequenceState.RUNNING_CURRENT,
            ActiveSequenceStateResolver.resolve(
                runtime(running, waitingSnapshot, activities, ActiveSessionState.RUNNING),
            ),
        )

        val paused = waitingEngine.pause(running, at(1), waitingSnapshot, activities)
        assertEquals(
            ActiveSequenceState.PAUSED_CURRENT,
            ActiveSequenceStateResolver.resolve(
                runtime(paused, waitingSnapshot, activities, ActiveSessionState.PAUSED),
            ),
        )

        val resumed = waitingEngine.resume(paused, at(2), waitingSnapshot, activities)
        val waiting =
            waitingEngine.completeCurrent(
                resumed,
                requireNotNull(resumed.execution.currentOccurrenceId),
                at(3),
                waitingSnapshot,
                activities,
            )
        assertEquals(
            ActiveSequenceState.WAITING_NEXT,
            ActiveSequenceStateResolver.resolve(
                runtime(waiting, waitingSnapshot, activities, ActiveSessionState.WAITING_NEXT),
            ),
        )

        val countdownSnapshot = snapshot(autoAdvance = true, beforeEachCountdown = Duration.ofSeconds(10))
        val countdownEngine = engine()
        val countdownStart = countdownEngine.start(countdownSnapshot, activities, at(0), at(0), ZoneOffset.UTC)
        val countdown =
            countdownEngine.completeCurrent(
                countdownStart,
                requireNotNull(countdownStart.execution.currentOccurrenceId),
                at(1),
                countdownSnapshot,
                activities,
            )
        assertEquals(
            ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN,
            ActiveSequenceStateResolver.resolve(
                runtime(countdown, countdownSnapshot, activities, ActiveSessionState.RUNNING),
            ),
        )
        val pausedCountdown = countdownEngine.pause(countdown, at(2), countdownSnapshot, activities)
        assertEquals(
            ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
            ActiveSequenceStateResolver.resolve(
                runtime(pausedCountdown, countdownSnapshot, activities, ActiveSessionState.PAUSED),
            ),
        )
    }

    @Test
    @Suppress("LongMethod") // Representative graph assertions intentionally stay in one behavioral test.
    fun expandedProjectionKeepsConfiguredActualProvenanceAndEffectiveSettingsSeparate() {
        val activities = activities()
        val snapshot = snapshot(autoAdvance = false, beforeEachCountdown = Duration.ofSeconds(3))
        val engine = engine()
        val started = engine.start(snapshot, activities, at(0), at(0), ZoneOffset.UTC)
        val firstId = requireNotNull(started.execution.currentOccurrenceId)
        val firstChild =
            requireNotNull(started.currentChild).copy(
                values = listOf(NumberExecutionValue(ActivitySnapshotFieldId("a-default"), 0)),
            )
        val withZero = started.copy(children = started.children + (firstId to firstChild))
        val waiting = engine.completeCurrent(withZero, firstId, at(1), snapshot, activities)
        val transitioning = engine.startNext(waiting, at(2), snapshot, activities)
        val current = engine.reconcile(transitioning, snapshot, activities, at(9))
        val addedActivity = activity("runtime", defaultNumber = 9)
        val withAdded =
            engine.addRuntimeOccurrence(
                current,
                addedActivity,
                RuntimeInsertionPlacement.TO_END,
                at(10),
                snapshot,
                activities + (addedActivity.id to addedActivity),
            )
        val allActivities = activities + (addedActivity.id to addedActivity)
        val runtime = runtime(withAdded, snapshot, allActivities, ActiveSessionState.RUNNING)

        val expanded = ExpandedLiveSequenceProjector.project(runtime, withAdded.children.values.toList())

        assertEquals(ActiveSequenceState.RUNNING_CURRENT, expanded.state)
        assertEquals(listOf(0, 1, 2, 3), expanded.occurrences.map { it.occurrence.runtimePosition })
        assertEquals(Duration.ofSeconds(2), expanded.occurrences[0].effectiveSettings.startCountdown)
        assertEquals(Duration.ofSeconds(7), expanded.occurrences[1].effectiveSettings.startCountdown)
        assertEquals(Duration.ofSeconds(7), expanded.occurrences[2].effectiveSettings.startCountdown)
        assertEquals(Duration.ofSeconds(3), expanded.occurrences[3].effectiveSettings.startCountdown)
        assertEquals(
            0L,
            (
                expanded.occurrences[0]
                    .childExecution
                    ?.values
                    ?.single() as NumberExecutionValue
            ).scaledValue,
        )
        assertEquals(
            5L,
            expanded.occurrences[0]
                .activity.fields[0]
                .defaultNumberScaled,
        )
        assertNull(
            expanded.occurrences[0]
                .activity.fields[1]
                .defaultNumberScaled,
        )
        assertNull(expanded.occurrences[2].childExecution)
        assertNull(expanded.occurrences[3].childExecution)
        assertTrue(expanded.occurrences[1].occurrence.repeatIteration == 1)
        assertTrue(expanded.occurrences[2].occurrence.repeatIteration == 2)
        assertTrue(expanded.occurrences[3].occurrence.isRuntimeAdded)
        assertNull(expanded.occurrences[3].occurrence.sourceSequenceSnapshotNodeId)
        assertEquals(
            setOf("a", "b", "runtime"),
            expanded.runtime.activitySnapshots.keys
                .map { it.value }
                .toSet(),
        )
    }

    @Test
    fun expandedProjectionRejectsMismatchedDuplicateAndMissingGraphEvidence() {
        val activities = activities()
        val snapshot = snapshot(autoAdvance = false, beforeEachCountdown = Duration.ZERO)
        val state = engine().start(snapshot, activities, at(0), at(0), ZoneOffset.UTC)
        val runtime = runtime(state, snapshot, activities, ActiveSessionState.RUNNING)
        val child = requireNotNull(state.currentChild)

        assertThrows(IllegalArgumentException::class.java) {
            ExpandedLiveSequenceProjector.project(
                runtime,
                listOf(child.copy(sequenceExecutionId = SequenceExecutionId("another-root"))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExpandedLiveSequenceProjector.project(
                runtime,
                listOf(child, child.copy(id = ActivityExecutionId("duplicate"))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExpandedLiveSequenceProjector.project(
                runtime.copy(activitySnapshots = activities - ActivitySnapshotId("b")),
                listOf(child),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActiveSequenceStateResolver.resolve(
                runtime.copy(session = runtime.session.copy(state = ActiveSessionState.WAITING_NEXT)),
            )
        }
    }

    private fun runtime(
        state: SequenceRuntimeState,
        snapshot: SequenceConfigSnapshot,
        activities: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
        sessionState: ActiveSessionState,
    ): ActiveSequenceRuntime {
        val frontier = nextRemainingOccurrence(state.execution)
        val target =
            if (state.execution.currentOccurrenceId == null && sessionState != ActiveSessionState.WAITING_NEXT) {
                frontier?.id
            } else {
                null
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
            target,
        )
    }

    private fun snapshot(
        autoAdvance: Boolean,
        beforeEachCountdown: Duration,
    ) = SequenceConfigSnapshot(
        SequenceSnapshotId("sequence"),
        "Frozen sequence",
        null,
        null,
        null,
        null,
        at(0),
        SequenceSnapshotSettings(
            autoAdvance,
            Duration.ofSeconds(5),
            beforeEachCountdown,
            true,
            true,
            false,
            true,
            true,
            NoLiveTimeAccounting.ACTIVE,
        ),
        fields =
            listOf(
                SequenceSnapshotField(
                    SequenceSnapshotFieldId("sequence-value"),
                    null,
                    0,
                    "Effort",
                    type = CustomFieldType.NUMBER,
                    defaultNumberScaled = 0,
                ),
            ),
        nodes =
            listOf(
                SequenceSnapshotActivityStep(
                    SequenceSnapshotNodeId("step-a"),
                    0,
                    ActivitySnapshotId("a"),
                    SequenceStepOverrides(startCountdown = Duration.ofSeconds(2)),
                ),
                SequenceSnapshotRepeatBlock(
                    SequenceSnapshotNodeId("repeat"),
                    1,
                    2,
                    listOf(
                        SequenceSnapshotActivityStep(
                            SequenceSnapshotNodeId("step-b"),
                            0,
                            ActivitySnapshotId("b"),
                            SequenceStepOverrides(startCountdown = Duration.ofSeconds(7)),
                        ),
                    ),
                ),
            ),
    )

    private fun activities() = listOf(activity("a", 5), activity("b", null)).associateBy(ActivityConfigSnapshot::id)

    private fun activity(
        id: String,
        defaultNumber: Long?,
    ) = ActivityConfigSnapshot(
        ActivitySnapshotId(id),
        "Frozen $id",
        null,
        TimeTrackingMode.STOPWATCH,
        null,
        null,
        null,
        null,
        false,
        at(0),
        fields =
            listOf(
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("$id-default"),
                    null,
                    0,
                    "Value",
                    type = CustomFieldType.NUMBER,
                    defaultNumberScaled = defaultNumber,
                ),
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("$id-missing"),
                    null,
                    1,
                    "Missing",
                    type = CustomFieldType.NUMBER,
                ),
            ),
    )

    private fun engine(): SequenceRuntimeEngine {
        var child = 0
        var pause = 0
        var interval = 0
        var occurrence = 0
        return SequenceRuntimeEngine(
            SequenceExecutionFactory(
                { SequenceExecutionId("execution") },
                RuntimeOccurrenceMaterializer { SequenceOccurrenceId("occurrence-${++occurrence}") },
            ),
            ActivityExecutionFactory { ActivityExecutionId("child-${++child}") },
            { ActivityExecutionPauseId("pause-${++pause}") },
            { SequenceIntervalId("interval-${++interval}") },
            { SequenceOccurrenceId("runtime-${++occurrence}") },
        )
    }

    private fun at(seconds: Long): Instant = Instant.EPOCH.plusSeconds(seconds)
}
