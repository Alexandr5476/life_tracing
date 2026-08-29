package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionContext
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityExecutionValidator
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCategoryOption
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Callable

class HistoryReadRepository internal constructor(
    private val database: LifeTracingDatabase,
) {
    fun getCompletedRoots(query: CompletedHistoryQuery): List<CompletedHistoryRoot> {
        require(query.limit <= MAXIMUM_RESULT_LIMIT) { "History result limit exceeds $MAXIMUM_RESULT_LIMIT" }
        return transaction {
            val startDate = query.dateRange.startDate.toString()
            val endDate = query.dateRange.endDate.toString()
            val activityRows =
                database.activityExecutionDao().getCompletedStandaloneHistoryRoots(startDate, endDate, query.limit)
            val sequenceRows = database.sequenceExecutionDao().getTerminalHistoryRoots(startDate, endDate, query.limit)
            val activitySnapshots =
                activityRows
                    .map(ActivityHistoryRootEntity::snapshotId)
                    .distinct()
                    .takeIf(List<String>::isNotEmpty)
                    ?.let(database.activitySnapshotDao()::getSummaries)
                    .orEmpty()
                    .associateBy(ActivitySnapshotSummaryEntity::id)
            val sequenceSnapshots =
                sequenceRows
                    .map(SequenceHistoryRootEntity::snapshotId)
                    .distinct()
                    .takeIf(List<String>::isNotEmpty)
                    ?.let(database.sequenceSnapshotDao()::getSummaries)
                    .orEmpty()
                    .associateBy(SequenceSnapshotSummaryEntity::id)
            require(activitySnapshots.keys.containsAll(activityRows.map(ActivityHistoryRootEntity::snapshotId))) {
                "History Activity root references a missing snapshot"
            }
            require(sequenceSnapshots.keys.containsAll(sequenceRows.map(SequenceHistoryRootEntity::snapshotId))) {
                "History Sequence root references a missing snapshot"
            }
            (
                activityRows.map { row -> row.toDomain(activitySnapshots.getValue(row.snapshotId)) } +
                    sequenceRows.map { row -> row.toDomain(sequenceSnapshots.getValue(row.snapshotId)) }
            ).sortedWith(HISTORY_ORDER)
                .take(query.limit)
        }
    }

    fun getActivityDetail(id: ActivityExecutionId): ActivityHistoryDetail? =
        transaction {
            val execution =
                database.activityExecutionDao().getAggregate(id.value)?.toDomain() ?: return@transaction null
            require(
                execution.context == ActivityExecutionContext.STANDALONE &&
                    execution.status == ActivityExecutionStatus.COMPLETED &&
                    execution.deletedAt == null,
            ) { "Activity detail is available only for non-deleted completed standalone history" }
            val snapshot =
                requireNotNull(database.activitySnapshotDao().getAggregate(execution.snapshotId.value)) {
                    "Activity history references a missing snapshot: ${execution.snapshotId.value}"
                }.toDomain()
            ActivityExecutionValidator.requireValid(execution, snapshot)
            ActivityHistoryDetail(
                root = execution.toHistoryRoot(snapshot),
                settings = snapshot.settings,
                fields = snapshot.toHistoryFields(execution.values),
            )
        }

    private fun ActivityConfigSnapshot.toHistoryFields(
        values: List<com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue>,
    ): List<ActivityHistoryField> {
        val sourceFieldNames =
            fields
                .mapNotNull { it.sourceFieldId?.value }
                .distinct()
                .takeIf(List<String>::isNotEmpty)
                ?.let(database.activityTemplateDao()::getAvailableFieldDisplayMetadata)
                .orEmpty()
                .associate { it.id to it.name }
        val sourceOptionLabels =
            fields
                .flatMap { field -> field.categoryOptions.mapNotNull { it.sourceOptionId?.value } }
                .distinct()
                .takeIf(List<String>::isNotEmpty)
                ?.let(database.activityTemplateDao()::getAvailableOptionDisplayMetadata)
                .orEmpty()
                .associate { it.id to it.label }
        val valuesByField = values.associateBy { it.snapshotFieldId }
        return fields.map { field ->
            val options =
                field.categoryOptions.map { option ->
                    ActivityHistoryCategoryOption(
                        option.id,
                        option.localLabelOverride ?: sourceOptionLabels[option.sourceOptionId?.value]
                            ?: option.labelAtCreation,
                    )
                }
            val optionLabels = options.associateBy(ActivityHistoryCategoryOption::id)
            ActivityHistoryField(
                id = field.id,
                name =
                    field.localNameOverride ?: sourceFieldNames[field.sourceFieldId?.value]
                        ?: field.nameAtCreation,
                type = field.type,
                unit = field.unit,
                displayPrecision = field.displayPrecision,
                configuredValue = field.configuredValue(),
                actualValue = valuesByField[field.id].toActualValue(optionLabels),
                categoryOptions = options,
            )
        }
    }

    private fun com.alexandr5476.lifetracing.domain.ActivitySnapshotField.configuredValue() =
        when (type) {
            CustomFieldType.NUMBER ->
                defaultNumberScaled?.let(ActivityHistoryConfiguredValue::Number)
                    ?: ActivityHistoryConfiguredValue.Missing
            CustomFieldType.CATEGORY ->
                defaultCategoryOptionId?.let(ActivityHistoryConfiguredValue::Category)
                    ?: ActivityHistoryConfiguredValue.Missing
            CustomFieldType.TEXT ->
                defaultText?.let(ActivityHistoryConfiguredValue::Text)
                    ?: ActivityHistoryConfiguredValue.Missing
        }

    private fun com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue?.toActualValue(
        optionLabels:
            Map<com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId, ActivityHistoryCategoryOption>,
    ): ActivityHistoryActualValue =
        when (this) {
            null -> ActivityHistoryActualValue.Missing
            is com.alexandr5476.lifetracing.domain.NumberExecutionValue ->
                ActivityHistoryActualValue.Number(
                    scaledValue,
                )
            is com.alexandr5476.lifetracing.domain.CategoryExecutionValue -> {
                val option =
                    requireNotNull(
                        optionLabels[optionId],
                    ) { "Execution Category value references a missing snapshot option" }
                ActivityHistoryActualValue.Category(option.id, option.label)
            }
            is com.alexandr5476.lifetracing.domain.TextExecutionValue -> ActivityHistoryActualValue.Text(value)
        }

    private fun ActivityExecution.toHistoryRoot(snapshot: ActivityConfigSnapshot) =
        CompletedActivityHistoryRoot(
            executionId = id,
            snapshotId = snapshotId,
            primaryLocalDate = primaryLocalDate,
            completedAt = requireNotNull(completedAt),
            startedAt = startedAt,
            activeDuration = activeDuration,
            planEntryId = planEntryId,
            title = snapshot.name,
            shortComment = snapshot.shortComment,
            timeTrackingMode = snapshot.timeTrackingMode,
            timerTarget = snapshot.timerTarget,
        )

    private fun ActivityHistoryRootEntity.toDomain(snapshot: ActivitySnapshotSummaryEntity) =
        CompletedActivityHistoryRoot(
            executionId = ActivityExecutionId(id),
            snapshotId = ActivitySnapshotId(snapshotId),
            primaryLocalDate = LocalDate.parse(primaryLocalDate),
            completedAt = Instant.ofEpochMilli(completedAtMs),
            startedAt = startedAtMs?.let(Instant::ofEpochMilli),
            activeDuration = activeDurationMs?.let(Duration::ofMillis),
            planEntryId = planEntryId?.let(::PlanEntryId),
            title = snapshot.name,
            shortComment = snapshot.shortComment,
            timeTrackingMode = snapshot.timeTrackingMode.toTimeTrackingMode(),
            timerTarget = snapshot.timerTargetMs?.let(Duration::ofMillis),
        )

    private fun SequenceHistoryRootEntity.toDomain(snapshot: SequenceSnapshotSummaryEntity) =
        CompletedSequenceHistoryRoot(
            executionId = SequenceExecutionId(id),
            snapshotId = SequenceSnapshotId(snapshotId),
            primaryLocalDate = LocalDate.parse(primaryLocalDate),
            completedAt = Instant.ofEpochMilli(endedAtMs),
            startedAt = Instant.ofEpochMilli(startedAtMs),
            status =
                SequenceExecutionStatus.valueOf(status).also {
                    require(it == SequenceExecutionStatus.COMPLETED || it == SequenceExecutionStatus.ENDED_EARLY)
                },
            activeDuration = activeDurationMs?.let(Duration::ofMillis),
            pauseDuration = pauseDurationMs?.let(Duration::ofMillis),
            wallDuration = wallDurationMs?.let(Duration::ofMillis),
            planEntryId = planEntryId?.let(::PlanEntryId),
            title = snapshot.name,
            shortComment = snapshot.shortComment,
        )

    private fun String.toTimeTrackingMode() =
        when (this) {
            "STOPWATCH" -> TimeTrackingMode.STOPWATCH
            "TIMER" -> TimeTrackingMode.TIMER
            "NO_LIVE_TRACKING" -> TimeTrackingMode.NO_LIVE_TRACKING
            else -> error("Unknown snapshot time tracking mode code: $this")
        }

    private fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable(block))

    companion object {
        const val MAXIMUM_RESULT_LIMIT = 500

        private val HISTORY_ORDER =
            compareByDescending<CompletedHistoryRoot> { it.primaryLocalDate }
                .thenByDescending { it.completedAt }
                .thenBy { if (it is CompletedActivityHistoryRoot) 0 else 1 }
                .thenBy {
                    when (it) {
                        is CompletedActivityHistoryRoot -> it.executionId.value
                        is CompletedSequenceHistoryRoot -> it.executionId.value
                    }
                }

        fun create(context: Context): HistoryReadRepository =
            HistoryReadRepository(LifeTracingDatabase.builder(context.applicationContext, "lifetracing.db").build())
    }
}
