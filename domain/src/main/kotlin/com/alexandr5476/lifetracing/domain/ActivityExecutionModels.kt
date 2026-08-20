package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@JvmInline
value class ActivityExecutionId(
    val value: String,
)

@JvmInline
value class ActivityExecutionPauseId(
    val value: String,
)

enum class ActivityExecutionContext {
    STANDALONE,
    SEQUENCE_CHILD,
}

enum class ActivityExecutionStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
}

enum class ActivityCompletionReason {
    MANUAL_HISTORY_ENTRY,
}

data class ActivityExecutionPause(
    val id: ActivityExecutionPauseId,
    val startedAt: Instant,
    val endedAt: Instant?,
)

sealed interface ActivityExecutionFieldValue {
    val snapshotFieldId: ActivitySnapshotFieldId
}

data class NumberExecutionValue(
    override val snapshotFieldId: ActivitySnapshotFieldId,
    val scaledValue: Long,
) : ActivityExecutionFieldValue

data class CategoryExecutionValue(
    override val snapshotFieldId: ActivitySnapshotFieldId,
    val optionId: ActivitySnapshotCategoryOptionId,
) : ActivityExecutionFieldValue

data class TextExecutionValue(
    override val snapshotFieldId: ActivitySnapshotFieldId,
    val value: String,
) : ActivityExecutionFieldValue

data class ActivityExecution(
    val id: ActivityExecutionId,
    val snapshotId: ActivitySnapshotId,
    val context: ActivityExecutionContext,
    val statisticsSeriesId: StatisticsSeriesId?,
    val status: ActivityExecutionStatus,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val activeDuration: Duration?,
    val originalZoneId: ZoneId,
    val originalUtcOffsetMinutes: Int?,
    val primaryLocalDate: LocalDate,
    val completionReason: ActivityCompletionReason?,
    val deletedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sequenceExecutionId: SequenceExecutionId? = null,
    val sequenceOccurrenceId: SequenceOccurrenceId? = null,
    val pauses: List<ActivityExecutionPause> = emptyList(),
    val values: List<ActivityExecutionFieldValue> = emptyList(),
)

object ActivityExecutionStatistics {
    val ONE_OFF_BUCKET_ID = StatisticsSeriesId("system:statistics-series:one-off-activities")
}
