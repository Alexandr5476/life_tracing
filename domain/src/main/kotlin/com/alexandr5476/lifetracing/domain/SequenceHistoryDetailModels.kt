package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant

data class SequenceHistoryDetail(
    val root: CompletedSequenceHistoryRoot,
    val settings: SequenceSnapshotSettings,
    val fields: List<SequenceHistoryField>,
    val occurrences: List<SequenceHistoryOccurrence>,
    val intervals: List<SequenceInterval>,
)

data class SequenceHistoryField(
    val id: SequenceSnapshotFieldId,
    val name: String,
    val type: CustomFieldType,
    val unit: String?,
    val displayPrecision: Int?,
    val configuredValue: SequenceHistoryConfiguredValue,
    val actualValue: SequenceHistoryActualValue,
    val categoryOptions: List<SequenceHistoryCategoryOption>,
)

data class SequenceHistoryCategoryOption(
    val id: SequenceSnapshotCategoryOptionId,
    val label: String,
)

sealed interface SequenceHistoryConfiguredValue {
    data object Missing : SequenceHistoryConfiguredValue

    data class Number(
        val scaledValue: Long,
    ) : SequenceHistoryConfiguredValue

    data class Category(
        val optionId: SequenceSnapshotCategoryOptionId,
    ) : SequenceHistoryConfiguredValue

    data class Text(
        val value: String,
    ) : SequenceHistoryConfiguredValue
}

sealed interface SequenceHistoryActualValue {
    data object Missing : SequenceHistoryActualValue

    data class Number(
        val scaledValue: Long,
    ) : SequenceHistoryActualValue

    data class Category(
        val optionId: SequenceSnapshotCategoryOptionId,
        val label: String,
    ) : SequenceHistoryActualValue

    data class Text(
        val value: String,
    ) : SequenceHistoryActualValue
}

data class SequenceHistoryOccurrence(
    val occurrenceId: SequenceOccurrenceId,
    val runtimePosition: Int,
    val activitySnapshotId: ActivitySnapshotId,
    val sourceSequenceSnapshotNodeId: SequenceSnapshotNodeId?,
    val repeatSourceSnapshotNodeId: SequenceSnapshotNodeId?,
    val repeatIteration: Int?,
    val isRuntimeAdded: Boolean,
    val isDeletedFromHistory: Boolean,
    val status: RuntimeOccurrenceStatus,
    val enteredAt: Instant?,
    val completedAt: Instant?,
    val completionReason: OccurrenceCompletionReason?,
    val activity: SequenceHistoryOccurrenceActivity,
    val child: SequenceHistoryChildActivity?,
)

data class SequenceHistoryOccurrenceActivity(
    val snapshotId: ActivitySnapshotId,
    val title: String,
    val shortComment: String?,
    val timeTrackingMode: TimeTrackingMode,
    val timerTarget: Duration?,
    val settings: ActivityTemplateSettings,
)

data class SequenceHistoryChildActivity(
    val executionId: ActivityExecutionId,
    val status: ActivityExecutionStatus,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val activeDuration: Duration?,
    val fields: List<ActivityHistoryField>,
)
