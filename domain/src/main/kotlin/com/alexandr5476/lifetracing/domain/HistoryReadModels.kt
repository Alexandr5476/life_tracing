package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class HistoryDateRange(
    val startDate: LocalDate,
    val endDate: LocalDate,
) {
    init {
        require(startDate <= endDate) { "History date range must be ordered" }
    }
}

data class CompletedHistoryQuery(
    val dateRange: HistoryDateRange,
    val limit: Int,
) {
    init {
        require(limit > 0) { "History result limit must be positive" }
    }
}

sealed interface CompletedHistoryRoot {
    val primaryLocalDate: LocalDate
    val completedAt: Instant
}

data class CompletedActivityHistoryRoot(
    val executionId: ActivityExecutionId,
    val snapshotId: ActivitySnapshotId,
    override val primaryLocalDate: LocalDate,
    override val completedAt: Instant,
    val startedAt: Instant?,
    val activeDuration: Duration?,
    val planEntryId: PlanEntryId?,
    val title: String,
    val shortComment: String?,
    val timeTrackingMode: TimeTrackingMode,
    val timerTarget: Duration?,
) : CompletedHistoryRoot

data class CompletedSequenceHistoryRoot(
    val executionId: SequenceExecutionId,
    val snapshotId: SequenceSnapshotId,
    override val primaryLocalDate: LocalDate,
    override val completedAt: Instant,
    val startedAt: Instant,
    val status: SequenceExecutionStatus,
    val activeDuration: Duration,
    val pauseDuration: Duration,
    val wallDuration: Duration,
    val planEntryId: PlanEntryId?,
    val title: String,
    val shortComment: String?,
) : CompletedHistoryRoot

data class ActivityHistoryDetail(
    val root: CompletedActivityHistoryRoot,
    val settings: ActivityTemplateSettings,
    val fields: List<ActivityHistoryField>,
)

data class ActivityHistoryField(
    val id: ActivitySnapshotFieldId,
    val name: String,
    val type: CustomFieldType,
    val unit: String?,
    val displayPrecision: Int?,
    val configuredValue: ActivityHistoryConfiguredValue,
    val actualValue: ActivityHistoryActualValue,
    val categoryOptions: List<ActivityHistoryCategoryOption>,
)

data class ActivityHistoryCategoryOption(
    val id: ActivitySnapshotCategoryOptionId,
    val label: String,
)

sealed interface ActivityHistoryConfiguredValue {
    data object Missing : ActivityHistoryConfiguredValue

    data class Number(
        val scaledValue: Long,
    ) : ActivityHistoryConfiguredValue

    data class Category(
        val optionId: ActivitySnapshotCategoryOptionId,
    ) : ActivityHistoryConfiguredValue

    data class Text(
        val value: String,
    ) : ActivityHistoryConfiguredValue
}

sealed interface ActivityHistoryActualValue {
    data object Missing : ActivityHistoryActualValue

    data class Number(
        val scaledValue: Long,
    ) : ActivityHistoryActualValue

    data class Category(
        val optionId: ActivitySnapshotCategoryOptionId,
        val label: String,
    ) : ActivityHistoryActualValue

    data class Text(
        val value: String,
    ) : ActivityHistoryActualValue
}
