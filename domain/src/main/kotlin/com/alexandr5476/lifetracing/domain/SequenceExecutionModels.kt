package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@JvmInline
value class SequenceExecutionId(
    val value: String,
)

@JvmInline
value class SequenceOccurrenceId(
    val value: String,
)

@JvmInline
value class SequenceIntervalId(
    val value: String,
)

enum class SequenceExecutionStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    ENDED_EARLY,
}

enum class RuntimeOccurrenceStatus {
    NOT_STARTED,
    CURRENT,
    COMPLETED,
    SKIPPED,
    DELETED_EXECUTION,
}

enum class OccurrenceCompletionReason {
    NATURAL_TIMER_END,
    MANUAL_FINISH,
    ADVANCED_TO_NEXT,
    JUMP,
    SEQUENCE_ENDED_EARLY,
}

enum class SequenceIntervalKind {
    ACTIVE_STEP,
    STEP_PAUSE,
    EXPLICIT_PAUSE,
    IMPLICIT_IDLE,
    TRANSITION_COUNTDOWN,
}

data class RuntimeOccurrence(
    val id: SequenceOccurrenceId,
    val sourceSequenceSnapshotNodeId: SequenceSnapshotNodeId?,
    val activitySnapshotId: ActivitySnapshotId,
    val runtimePosition: Int,
    val repeatSourceSnapshotNodeId: SequenceSnapshotNodeId?,
    val repeatIteration: Int?,
    val status: RuntimeOccurrenceStatus,
    val enteredAt: Instant?,
    val completedAt: Instant?,
    val completionReason: OccurrenceCompletionReason?,
    val isRuntimeAdded: Boolean,
    val isDeletedFromHistory: Boolean,
)

data class SequenceInterval(
    val id: SequenceIntervalId,
    val kind: SequenceIntervalKind,
    val startedAt: Instant,
    val endedAt: Instant?,
    val occurrenceId: SequenceOccurrenceId?,
)

sealed interface SequenceExecutionFieldValue {
    val snapshotFieldId: SequenceSnapshotFieldId
}

data class NumberSequenceExecutionValue(
    override val snapshotFieldId: SequenceSnapshotFieldId,
    val scaledValue: Long,
) : SequenceExecutionFieldValue

data class CategorySequenceExecutionValue(
    override val snapshotFieldId: SequenceSnapshotFieldId,
    val optionId: SequenceSnapshotCategoryOptionId,
) : SequenceExecutionFieldValue

data class TextSequenceExecutionValue(
    override val snapshotFieldId: SequenceSnapshotFieldId,
    val value: String,
) : SequenceExecutionFieldValue

data class SequenceExecution(
    val id: SequenceExecutionId,
    val snapshotId: SequenceSnapshotId,
    val statisticsSeriesId: StatisticsSeriesId?,
    val status: SequenceExecutionStatus,
    val startedAt: Instant,
    val endedAt: Instant?,
    val activeDuration: Duration?,
    val pauseDuration: Duration?,
    val wallDuration: Duration?,
    val originalZoneId: ZoneId,
    val originalUtcOffsetMinutes: Int?,
    val primaryLocalDate: LocalDate,
    val currentOccurrenceId: SequenceOccurrenceId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val occurrences: List<RuntimeOccurrence> = emptyList(),
    val intervals: List<SequenceInterval> = emptyList(),
    val values: List<SequenceExecutionFieldValue> = emptyList(),
)

data class SequenceTimelineDurations(
    val active: Duration,
    val pause: Duration,
    val wall: Duration,
)
