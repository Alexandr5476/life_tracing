package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionTransitions
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceCardinalityPolicy
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceMaterializer
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceExecutionFactory
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceRuntimeState
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SequenceRuntimeChildValidationTest {
    @Test
    fun childAndCurrentValidationReadsGrowLinearlyThroughTheOccurrenceLimit() {
        val reads =
            listOf(128, 512, RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES).map { count ->
                val (state, activities) = fixture(count)
                val occurrences = CountingList(state.execution.occurrences)

                requireValidSequenceRuntimeChildren(
                    state.copy(execution = state.execution.copy(occurrences = occurrences)),
                    activities,
                )

                assertTrue(
                    occurrences.reads <= 2L * count,
                    "Occurrence reads: ${occurrences.reads} for $count children",
                )
                occurrences.reads
            }

        assertTrue(reads[1] <= reads[0] * 4 + 2, "Four times the children must not cause quadratic lookup work")
    }

    @Test
    fun validationRejectsForeignOwnershipAndMissingCurrentChild() {
        val (state, activities) = fixture(2)
        val currentId = requireNotNull(state.execution.currentOccurrenceId)
        val child = state.children.getValue(currentId)
        val completed = ActivityExecutionTransitions.complete(child, Instant.ofEpochMilli(2))
        val foreignSequenceChild = child.copy(sequenceExecutionId = SequenceExecutionId("foreign"))
        val foreignOccurrenceChild = child.copy(sequenceOccurrenceId = SequenceOccurrenceId("foreign"))
        val invalidStates =
            listOf(
                state.copy(children = state.children - currentId),
                state.copy(children = state.children + (currentId to completed)),
                state.copy(children = state.children + (currentId to foreignSequenceChild)),
                state.copy(children = state.children + (currentId to foreignOccurrenceChild)),
                state.copy(
                    children = state.children + (currentId to child.copy(snapshotId = ActivitySnapshotId("foreign"))),
                ),
            )
        invalidStates.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                requireValidSequenceRuntimeChildren(invalid, activities)
            }
        }
        assertThrows(NoSuchElementException::class.java) {
            requireValidSequenceRuntimeChildren(
                state.copy(children = state.children + (SequenceOccurrenceId("absent") to child)),
                activities,
            )
        }
    }

    @Suppress("LongMethod") // The explicit fixture supplies every child and occurrence prerequisite.
    private fun fixture(count: Int): Pair<SequenceRuntimeState, Map<ActivitySnapshotId, ActivityConfigSnapshot>> {
        val activity =
            ActivityConfigSnapshot(
                ActivitySnapshotId("activity"),
                "Timer",
                null,
                TimeTrackingMode.TIMER,
                Duration.ofMillis(1),
                null,
                null,
                null,
                false,
                Instant.EPOCH,
                ActivityTemplateSettings(),
            )
        val snapshot =
            SequenceConfigSnapshot(
                SequenceSnapshotId("snapshot"),
                "Sequence",
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                SequenceSnapshotSettings(
                    true,
                    Duration.ZERO,
                    Duration.ZERO,
                    true,
                    true,
                    false,
                    true,
                    true,
                    NoLiveTimeAccounting.ACTIVE,
                ),
                nodes =
                    listOf(
                        SequenceSnapshotRepeatBlock(
                            SequenceSnapshotNodeId("repeat"),
                            0,
                            count,
                            listOf(SequenceSnapshotActivityStep(SequenceSnapshotNodeId("step"), 0, activity.id)),
                        ),
                    ),
            )
        var occurrenceId = 0
        val execution =
            SequenceExecutionFactory(
                { SequenceExecutionId("execution") },
                RuntimeOccurrenceMaterializer { SequenceOccurrenceId("occurrence-${++occurrenceId}") },
            ).start(snapshot, Instant.EPOCH, Instant.EPOCH, ZoneOffset.UTC)
        var childId = 0
        val factory = ActivityExecutionFactory { ActivityExecutionId("child-${++childId}") }
        val children =
            execution.occurrences.associate { occurrence ->
                val at = Instant.ofEpochMilli(occurrence.runtimePosition.toLong())
                val child =
                    factory.startSequenceChildTimed(activity, execution.id, occurrence.id, at, at, ZoneOffset.UTC)
                occurrence.id to
                    if (occurrence.runtimePosition == count - 1) {
                        child
                    } else {
                        ActivityExecutionTransitions.complete(child, at.plusMillis(1))
                    }
            }
        val occurrences =
            execution.occurrences.map { occurrence ->
                val child = children.getValue(occurrence.id)
                occurrence.copy(
                    status =
                        if (child.completedAt == null) {
                            RuntimeOccurrenceStatus.CURRENT
                        } else {
                            RuntimeOccurrenceStatus.COMPLETED
                        },
                    enteredAt = child.startedAt,
                    completedAt = child.completedAt,
                )
            }
        val state =
            SequenceRuntimeState(
                execution.copy(occurrences = occurrences, currentOccurrenceId = occurrences.last().id),
                children,
            )
        return state to
            mapOf(activity.id to activity)
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
}
