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
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionValidator
import com.alexandr5476.lifetracing.domain.SequenceHistoryActualValue
import com.alexandr5476.lifetracing.domain.SequenceHistoryCategoryOption
import com.alexandr5476.lifetracing.domain.SequenceHistoryChildActivity
import com.alexandr5476.lifetracing.domain.SequenceHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryField
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrence
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrenceActivity
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Callable

@Suppress("TooManyFunctions") // Immutable root/detail mapping keeps the read boundary self-contained.
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

    @Suppress("LongMethod") // Point detail intentionally composes one owned historical graph.
    fun getSequenceDetail(id: SequenceExecutionId): SequenceHistoryDetail? =
        transaction {
            val execution =
                database.sequenceExecutionDao().getHistoryAggregate(id.value)?.toDomain() ?: return@transaction null
            require(
                execution.status == SequenceExecutionStatus.COMPLETED ||
                    execution.status == SequenceExecutionStatus.ENDED_EARLY,
            ) { "Sequence detail is available only for terminal history" }
            val snapshot =
                requireNotNull(database.sequenceSnapshotDao().getAggregate(execution.snapshotId.value)) {
                    "Sequence history references a missing snapshot: ${execution.snapshotId.value}"
                }.toDomain()
            SequenceExecutionValidator.requireValid(execution, snapshot)
            val activitySnapshots =
                execution.occurrences
                    .map { it.activitySnapshotId.value }
                    .distinct()
                    .chunked(SQLITE_BIND_CHUNK_SIZE)
                    .flatMap(database.activitySnapshotDao()::getAggregates)
                    .map(ActivitySnapshotAggregateEntity::toDomain)
                    .associateBy(ActivityConfigSnapshot::id)
            require(activitySnapshots.keys.containsAll(execution.occurrences.map { it.activitySnapshotId })) {
                "Sequence occurrence references a missing Activity snapshot"
            }
            val childAggregates =
                database
                    .activityExecutionDao()
                    .getSequenceChildAggregates(id.value)
            val children =
                childAggregates
                    .map(ActivityExecutionAggregateEntity::toDomain)
                    .associateBy { child ->
                        require(child.context == ActivityExecutionContext.SEQUENCE_CHILD) {
                            "Sequence child query returned a non-child execution"
                        }
                        require(child.sequenceExecutionId == id) {
                            "Sequence child belongs to another Sequence execution"
                        }
                        requireNotNull(child.sequenceOccurrenceId) {
                            "Sequence child is missing its occurrence linkage"
                        }
                    }
            val occurrencesById = execution.occurrences.associateBy { it.id }
            require(children.size == childAggregates.size) {
                "Multiple children reference one Sequence occurrence"
            }
            children.forEach { (occurrenceId, child) ->
                val occurrence =
                    requireNotNull(occurrencesById[occurrenceId]) {
                        "Sequence child references an occurrence outside its parent Sequence"
                    }
                require(child.snapshotId == occurrence.activitySnapshotId) {
                    "Sequence child Activity snapshot does not match its occurrence"
                }
                require(child.status == ActivityExecutionStatus.COMPLETED) {
                    "Terminal Sequence history cannot contain a live child Activity execution"
                }
                ActivityExecutionValidator.requireValid(child, activitySnapshots.getValue(child.snapshotId))
            }
            val displayMetadata = loadActivityDisplayMetadata(activitySnapshots.values)
            SequenceHistoryDetail(
                root = execution.toHistoryRoot(snapshot),
                settings = snapshot.settings,
                fields = snapshot.toHistoryFields(execution.values),
                occurrences =
                    execution.occurrences.sortedBy { it.runtimePosition }.map { occurrence ->
                        val activity = activitySnapshots.getValue(occurrence.activitySnapshotId)
                        SequenceHistoryOccurrence(
                            occurrence.id,
                            occurrence.runtimePosition,
                            occurrence.activitySnapshotId,
                            occurrence.sourceSequenceSnapshotNodeId,
                            occurrence.repeatSourceSnapshotNodeId,
                            occurrence.repeatIteration,
                            occurrence.isRuntimeAdded,
                            occurrence.isDeletedFromHistory,
                            occurrence.status,
                            occurrence.enteredAt,
                            occurrence.completedAt,
                            occurrence.completionReason,
                            SequenceHistoryOccurrenceActivity(
                                activity.id,
                                activity.name,
                                activity.shortComment,
                                activity.timeTrackingMode,
                                activity.timerTarget,
                                activity.settings,
                            ),
                            children[occurrence.id]?.toHistoryChild(activity, displayMetadata),
                        )
                    },
                intervals = execution.intervals,
            )
        }

    private fun ActivityConfigSnapshot.toHistoryFields(
        values: List<com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue>,
    ): List<ActivityHistoryField> = toHistoryFields(values, loadActivityDisplayMetadata(listOf(this)))

    private fun ActivityConfigSnapshot.toHistoryFields(
        values: List<com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue>,
        displayMetadata: ActivityDisplayMetadata,
    ): List<ActivityHistoryField> {
        val valuesByField = values.associateBy { it.snapshotFieldId }
        return fields.map { field ->
            val options =
                field.categoryOptions.map { option ->
                    ActivityHistoryCategoryOption(
                        option.id,
                        option.localLabelOverride ?: displayMetadata.optionLabels[option.sourceOptionId?.value]
                            ?: option.labelAtCreation,
                    )
                }
            val optionLabels = options.associateBy(ActivityHistoryCategoryOption::id)
            ActivityHistoryField(
                id = field.id,
                name =
                    field.localNameOverride ?: displayMetadata.fieldNames[field.sourceFieldId?.value]
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

    private fun loadActivityDisplayMetadata(snapshots: Collection<ActivityConfigSnapshot>): ActivityDisplayMetadata {
        val sourceFieldIds =
            snapshots
                .flatMap(ActivityConfigSnapshot::fields)
                .filter { it.localNameOverride == null }
                .mapNotNull { it.sourceFieldId?.value }
                .distinct()
        val sourceOptionIds =
            snapshots
                .flatMap(ActivityConfigSnapshot::fields)
                .flatMap { field -> field.categoryOptions }
                .filter { it.localLabelOverride == null }
                .mapNotNull { it.sourceOptionId?.value }
                .distinct()
        return ActivityDisplayMetadata(
            sourceFieldIds
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.activityTemplateDao()::getAvailableFieldDisplayMetadata)
                .associate { it.id to it.name },
            sourceOptionIds
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(database.activityTemplateDao()::getAvailableOptionDisplayMetadata)
                .associate { it.id to it.label },
        )
    }

    private fun ActivityExecution.toHistoryChild(
        snapshot: ActivityConfigSnapshot,
        displayMetadata: ActivityDisplayMetadata,
    ) = SequenceHistoryChildActivity(
        id,
        status,
        startedAt,
        completedAt,
        activeDuration,
        snapshot.toHistoryFields(values, displayMetadata),
    )

    private fun SequenceConfigSnapshot.toHistoryFields(
        values: List<com.alexandr5476.lifetracing.domain.SequenceExecutionFieldValue>,
    ): List<SequenceHistoryField> {
        val valuesByField = values.associateBy { it.snapshotFieldId }
        return fields.map { field ->
            val options =
                field.categoryOptions.map { option ->
                    SequenceHistoryCategoryOption(option.id, option.localLabelOverride ?: option.labelAtCreation)
                }
            val optionLabels = options.associateBy(SequenceHistoryCategoryOption::id)
            SequenceHistoryField(
                field.id,
                field.localNameOverride ?: field.nameAtCreation,
                field.type,
                field.unit,
                field.displayPrecision,
                field.configuredValue(),
                valuesByField[field.id].toActualValue(optionLabels),
                options,
            )
        }
    }

    private fun com.alexandr5476.lifetracing.domain.SequenceSnapshotField.configuredValue() =
        when (type) {
            CustomFieldType.NUMBER ->
                defaultNumberScaled?.let(SequenceHistoryConfiguredValue::Number)
                    ?: SequenceHistoryConfiguredValue.Missing
            CustomFieldType.CATEGORY ->
                defaultCategoryOptionId?.let(SequenceHistoryConfiguredValue::Category)
                    ?: SequenceHistoryConfiguredValue.Missing
            CustomFieldType.TEXT ->
                defaultText?.let(SequenceHistoryConfiguredValue::Text)
                    ?: SequenceHistoryConfiguredValue.Missing
        }

    private fun com.alexandr5476.lifetracing.domain.SequenceExecutionFieldValue?.toActualValue(
        optionLabels: Map<
            com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId,
            SequenceHistoryCategoryOption,
        >,
    ): SequenceHistoryActualValue =
        when (this) {
            null -> SequenceHistoryActualValue.Missing
            is com.alexandr5476.lifetracing.domain.NumberSequenceExecutionValue ->
                SequenceHistoryActualValue.Number(scaledValue)
            is com.alexandr5476.lifetracing.domain.CategorySequenceExecutionValue -> {
                val option =
                    requireNotNull(optionLabels[optionId]) {
                        "Sequence Category value references a missing snapshot option"
                    }
                SequenceHistoryActualValue.Category(option.id, option.label)
            }
            is com.alexandr5476.lifetracing.domain.TextSequenceExecutionValue ->
                SequenceHistoryActualValue.Text(value)
        }

    private fun com.alexandr5476.lifetracing.domain.SequenceExecution.toHistoryRoot(snapshot: SequenceConfigSnapshot) =
        CompletedSequenceHistoryRoot(
            id,
            snapshotId,
            primaryLocalDate,
            requireNotNull(endedAt),
            startedAt,
            status.also {
                require(it == SequenceExecutionStatus.COMPLETED || it == SequenceExecutionStatus.ENDED_EARLY)
            },
            requireTerminalDuration(activeDuration, "active"),
            requireTerminalDuration(pauseDuration, "pause"),
            requireTerminalDuration(wallDuration, "wall"),
            planEntryId,
            snapshot.name,
            snapshot.shortComment,
        )

    private fun requireTerminalDuration(
        duration: Duration?,
        name: String,
    ): Duration =
        requireNotNull(duration) { "Terminal Sequence history is missing $name duration" }
            .also { require(!it.isNegative) { "Terminal Sequence history has a negative $name duration" } }

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
            activeDuration = terminalDuration(activeDurationMs, "active"),
            pauseDuration = terminalDuration(pauseDurationMs, "pause"),
            wallDuration = terminalDuration(wallDurationMs, "wall"),
            planEntryId = planEntryId?.let(::PlanEntryId),
            title = snapshot.name,
            shortComment = snapshot.shortComment,
        )

    private fun terminalDuration(
        milliseconds: Long?,
        name: String,
    ): Duration =
        Duration.ofMillis(
            requireNotNull(milliseconds) { "Terminal Sequence root is missing $name duration" }
                .also { require(it >= 0) { "Terminal Sequence root has a negative $name duration" } },
        )

    private fun String.toTimeTrackingMode() =
        when (this) {
            "STOPWATCH" -> TimeTrackingMode.STOPWATCH
            "TIMER" -> TimeTrackingMode.TIMER
            "NO_LIVE_TRACKING" -> TimeTrackingMode.NO_LIVE_TRACKING
            else -> error("Unknown snapshot time tracking mode code: $this")
        }

    private fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable(block))

    private data class ActivityDisplayMetadata(
        val fieldNames: Map<String, String>,
        val optionLabels: Map<String, String>,
    )

    companion object {
        const val MAXIMUM_RESULT_LIMIT = 500
        private const val SQLITE_BIND_CHUNK_SIZE = 900

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
