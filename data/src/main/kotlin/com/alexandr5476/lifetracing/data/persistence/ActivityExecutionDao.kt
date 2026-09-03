@file:Suppress(
    "LongMethod",
    "LongParameterList",
) // Explicit owner identity is safer than bundling generated runtime-row updates.

package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.alexandr5476.lifetracing.domain.ActivityExecutionDurationCalculator
import com.alexandr5476.lifetracing.domain.ActivityExecutionPause
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import com.alexandr5476.lifetracing.domain.ActivityHistoricalSnapshotPolicy
import java.time.Instant
import java.util.ConcurrentModificationException

internal data class ActivityExecutionAggregateEntity(
    val execution: ActivityExecutionEntity,
    val pauses: List<ActivityExecutionPauseEntity> = emptyList(),
    val values: List<ActivityExecutionFieldValueEntity> = emptyList(),
)

internal data class SequenceOccurrenceLinkRow(
    val id: String,
    @androidx.room.ColumnInfo(name = "sequence_execution_id") val sequenceExecutionId: String,
    @androidx.room.ColumnInfo(name = "activity_snapshot_id") val activitySnapshotId: String,
)

internal data class ActivitySnapshotExecutionMetadataRow(
    val id: String,
    @androidx.room.ColumnInfo(name = "time_tracking_mode") val timeTrackingMode: String,
    @androidx.room.ColumnInfo(name = "statistics_series_id") val statisticsSeriesId: String?,
)

internal data class ActivitySnapshotFieldValueMetadataRow(
    val id: String,
    @androidx.room.ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @androidx.room.ColumnInfo(name = "field_type") val fieldType: String,
)

internal data class ActivitySnapshotOptionValueMetadataRow(
    val id: String,
    @androidx.room.ColumnInfo(name = "snapshot_field_id") val snapshotFieldId: String,
)

internal data class HistoricalSequenceChildValidationScope(
    val snapshots: Map<String, ActivitySnapshotExecutionMetadataRow>,
    val occurrences: Map<String, SequenceOccurrenceLinkRow>,
    val fields: Map<String, ActivitySnapshotFieldValueMetadataRow>,
    val options: Map<String, ActivitySnapshotOptionValueMetadataRow>,
)

internal data class ActivityHistoryRootEntity(
    val id: String,
    @androidx.room.ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @androidx.room.ColumnInfo(name = "plan_entry_id") val planEntryId: String?,
    @androidx.room.ColumnInfo(name = "started_at_ms") val startedAtMs: Long?,
    @androidx.room.ColumnInfo(name = "completed_at_ms") val completedAtMs: Long,
    @androidx.room.ColumnInfo(name = "active_duration_ms") val activeDurationMs: Long?,
    @androidx.room.ColumnInfo(name = "primary_local_date") val primaryLocalDate: String,
)

internal data class ExecutionPlanLinkRow(
    @androidx.room.ColumnInfo(name = "trackable_kind") val trackableKind: String,
    @androidx.room.ColumnInfo(name = "activity_snapshot_id") val activitySnapshotId: String?,
    @androidx.room.ColumnInfo(name = "sequence_plan_snapshot_id") val sequencePlanSnapshotId: String?,
    val status: String,
    @androidx.room.ColumnInfo(name = "fulfilled_activity_execution_id") val fulfilledActivityExecutionId: String?,
)

@Dao
@Suppress("LargeClass", "TooManyFunctions") // Atomic aggregate state transitions belong together.
internal abstract class ActivityExecutionDao {
    @Query("SELECT * FROM activity_executions WHERE id = :id")
    abstract fun getById(id: String): ActivityExecutionEntity?

    @Query(
        "SELECT id, snapshot_id, plan_entry_id, started_at_ms, completed_at_ms, active_duration_ms, " +
            "primary_local_date FROM activity_executions " +
            "WHERE context_type = 'STANDALONE' AND status = 'COMPLETED' AND deleted_at_ms IS NULL " +
            "AND primary_local_date BETWEEN :startDate AND :endDate " +
            "ORDER BY primary_local_date DESC, completed_at_ms DESC, id ASC LIMIT :limit",
    )
    abstract fun getCompletedStandaloneHistoryRoots(
        startDate: String,
        endDate: String,
        limit: Int,
    ): List<ActivityHistoryRootEntity>

    @Query("SELECT * FROM activity_executions WHERE sequence_occurrence_id = :occurrenceId")
    protected abstract fun getByOccurrence(occurrenceId: String): ActivityExecutionEntity?

    @Query(
        "SELECT * FROM activity_executions WHERE context_type = 'SEQUENCE_CHILD' " +
            "AND sequence_execution_id = :sequenceExecutionId ORDER BY sequence_occurrence_id, id",
    )
    protected abstract fun getSequenceChildren(sequenceExecutionId: String): List<ActivityExecutionEntity>

    @Query(
        "SELECT * FROM activity_execution_pauses " +
            "WHERE activity_execution_id = :executionId ORDER BY started_at_ms, id",
    )
    abstract fun getPauses(executionId: String): List<ActivityExecutionPauseEntity>

    @Query(
        "SELECT * FROM activity_execution_field_values " +
            "WHERE activity_execution_id = :executionId ORDER BY snapshot_field_id",
    )
    abstract fun getValues(executionId: String): List<ActivityExecutionFieldValueEntity>

    @Query(
        "SELECT * FROM activity_execution_pauses WHERE activity_execution_id IN (:executionIds) " +
            "ORDER BY activity_execution_id, started_at_ms, id",
    )
    protected abstract fun getPausesForExecutions(executionIds: List<String>): List<ActivityExecutionPauseEntity>

    @Query(
        "SELECT * FROM activity_execution_field_values WHERE activity_execution_id IN (:executionIds) " +
            "ORDER BY activity_execution_id, snapshot_field_id",
    )
    protected abstract fun getValuesForExecutions(executionIds: List<String>): List<ActivityExecutionFieldValueEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM activity_executions " +
            "WHERE context_type = 'STANDALONE' AND status = 'COMPLETED' AND deleted_at_ms IS NULL " +
            "AND started_at_ms IS NOT NULL AND completed_at_ms IS NOT NULL " +
            "AND started_at_ms <= :endMs AND completed_at_ms >= :startMs " +
            "AND (:excludeId IS NULL OR id != :excludeId) LIMIT 1)",
    )
    abstract fun overlapsCompletedStandalone(
        startMs: Long,
        endMs: Long,
        excludeId: String? = null,
    ): Boolean

    @Query(
        "SELECT field_type FROM activity_snapshot_fields " +
            "WHERE snapshot_id = :snapshotId AND id = :snapshotFieldId",
    )
    protected abstract fun getSnapshotFieldType(
        snapshotId: String,
        snapshotFieldId: String,
    ): String?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM activity_snapshot_category_options " +
            "WHERE snapshot_field_id = :snapshotFieldId AND id = :categoryOptionId)",
    )
    protected abstract fun categoryOptionBelongsToField(
        snapshotFieldId: String,
        categoryOptionId: String,
    ): Boolean

    @Query(
        "SELECT id, snapshot_id, field_type FROM activity_snapshot_fields " +
            "WHERE snapshot_id = :snapshotId AND id IN (:fieldIds)",
    )
    protected abstract fun getSnapshotFieldValueMetadata(
        snapshotId: String,
        fieldIds: List<String>,
    ): List<ActivitySnapshotFieldValueMetadataRow>

    @Query(
        "SELECT options.id, options.snapshot_field_id FROM activity_snapshot_category_options AS options " +
            "INNER JOIN activity_snapshot_fields AS fields ON fields.id = options.snapshot_field_id " +
            "WHERE fields.snapshot_id = :snapshotId AND options.id IN (:optionIds)",
    )
    protected abstract fun getSnapshotOptionValueMetadata(
        snapshotId: String,
        optionIds: List<String>,
    ): List<ActivitySnapshotOptionValueMetadataRow>

    @Query("SELECT id, time_tracking_mode, statistics_series_id FROM activity_snapshots WHERE id = :snapshotId")
    protected abstract fun getSnapshotExecutionMetadata(snapshotId: String): ActivitySnapshotExecutionMetadataRow?

    @Query("SELECT id, time_tracking_mode, statistics_series_id FROM activity_snapshots WHERE id IN (:snapshotIds)")
    protected abstract fun getSnapshotExecutionMetadataForIds(
        snapshotIds: List<String>,
    ): List<ActivitySnapshotExecutionMetadataRow>

    @Query("SELECT id, sequence_execution_id, activity_snapshot_id FROM sequence_occurrences WHERE id = :id")
    protected abstract fun getSequenceOccurrenceLink(id: String): SequenceOccurrenceLinkRow?

    @Query("SELECT id, sequence_execution_id, activity_snapshot_id FROM sequence_occurrences WHERE id IN (:ids)")
    protected abstract fun getSequenceOccurrenceLinks(ids: List<String>): List<SequenceOccurrenceLinkRow>

    @Query("SELECT id, snapshot_id, field_type FROM activity_snapshot_fields WHERE id IN (:ids)")
    protected abstract fun getSnapshotFieldValueMetadataForIds(
        ids: List<String>,
    ): List<ActivitySnapshotFieldValueMetadataRow>

    @Query("SELECT id, snapshot_field_id FROM activity_snapshot_category_options WHERE id IN (:ids)")
    protected abstract fun getSnapshotOptionValueMetadataForIds(
        ids: List<String>,
    ): List<ActivitySnapshotOptionValueMetadataRow>

    @Query(
        "SELECT trackable_kind, activity_snapshot_id, sequence_plan_snapshot_id, status, " +
            "fulfilled_activity_execution_id FROM plan_entries WHERE id = :id",
    )
    protected abstract fun getPlanLink(id: String): ExecutionPlanLinkRow?

    @Query("SELECT * FROM activity_snapshots WHERE id = :id")
    protected abstract fun getSnapshotForCorrectionValidation(id: String): ActivitySnapshotEntity?

    @Query("SELECT * FROM activity_snapshot_settings WHERE snapshot_id = :id")
    protected abstract fun getSnapshotSettingsForCorrectionValidation(id: String): ActivitySnapshotSettingsEntity?

    @Query("SELECT * FROM activity_snapshot_fields WHERE snapshot_id = :id ORDER BY position, id")
    protected abstract fun getSnapshotFieldsForCorrectionValidation(id: String): List<ActivitySnapshotFieldEntity>

    @Query(
        "SELECT options.* FROM activity_snapshot_category_options AS options " +
            "INNER JOIN activity_snapshot_fields AS fields ON fields.id = options.snapshot_field_id " +
            "WHERE fields.snapshot_id = :id ORDER BY fields.position, options.position, options.id",
    )
    protected abstract fun getSnapshotOptionsForCorrectionValidation(
        id: String,
    ): List<ActivitySnapshotCategoryOptionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertExecutionUnchecked(execution: ActivityExecutionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertPausesUnchecked(pauses: List<ActivityExecutionPauseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertValuesUnchecked(values: List<ActivityExecutionFieldValueEntity>)

    @Query("DELETE FROM activity_execution_field_values WHERE activity_execution_id = :executionId")
    protected abstract fun deleteValuesUnchecked(executionId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertPauseUnchecked(pause: ActivityExecutionPauseEntity)

    @Upsert
    protected abstract fun upsertValueUnchecked(value: ActivityExecutionFieldValueEntity)

    @Query(
        "UPDATE activity_executions SET status = :status, completed_at_ms = :completedAtMs, " +
            "active_duration_ms = :activeDurationMs, completion_reason = :completionReason, " +
            "deleted_at_ms = :deletedAtMs, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND context_type = 'SEQUENCE_CHILD' AND sequence_execution_id = :sequenceExecutionId " +
            "AND sequence_occurrence_id = :sequenceOccurrenceId AND snapshot_id = :snapshotId",
    )
    protected abstract fun updateSequenceChildUnchecked(
        id: String,
        sequenceExecutionId: String,
        sequenceOccurrenceId: String,
        snapshotId: String,
        status: String,
        completedAtMs: Long?,
        activeDurationMs: Long?,
        completionReason: String?,
        deletedAtMs: Long?,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE activity_execution_pauses SET ended_at_ms = :endedAtMs " +
            "WHERE id = :id AND activity_execution_id = :executionId AND ended_at_ms IS NULL",
    )
    protected abstract fun closeOwnedPauseUnchecked(
        id: String,
        executionId: String,
        endedAtMs: Long,
    ): Int

    @Query(
        "UPDATE activity_executions SET status = :status, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND status = :expectedStatus",
    )
    protected abstract fun updateStatusUnchecked(
        id: String,
        expectedStatus: String,
        status: String,
        updatedAtMs: Long,
    ): Int

    @Query("UPDATE activity_execution_pauses SET ended_at_ms = :endedAtMs WHERE id = :id AND ended_at_ms IS NULL")
    protected abstract fun closePauseUnchecked(
        id: String,
        endedAtMs: Long,
    ): Int

    @Query(
        "UPDATE activity_executions SET status = 'COMPLETED', completed_at_ms = :completedAtMs, " +
            "active_duration_ms = :activeDurationMs, completion_reason = NULL, " +
            "updated_at_ms = :completedAtMs WHERE id = :id AND status IN ('RUNNING', 'PAUSED')",
    )
    protected abstract fun markCompletedUnchecked(
        id: String,
        completedAtMs: Long,
        activeDurationMs: Long,
    ): Int

    @Query("UPDATE activity_executions SET deleted_at_ms = :deletedAtMs, updated_at_ms = :deletedAtMs WHERE id = :id")
    protected abstract fun softDeleteUnchecked(
        id: String,
        deletedAtMs: Long,
    ): Int

    @Query(
        "UPDATE activity_executions SET snapshot_id = :snapshotId, started_at_ms = :startedAtMs, " +
            "completed_at_ms = :completedAtMs, active_duration_ms = :activeDurationMs, " +
            "original_zone_id = :originalZoneId, original_utc_offset_minutes = :originalUtcOffsetMinutes, " +
            "primary_local_date = :primaryLocalDate, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND context_type = 'STANDALONE' AND status = 'COMPLETED' " +
            "AND deleted_at_ms IS NULL AND updated_at_ms = :expectedUpdatedAtMs " +
            "AND snapshot_id = :expectedSnapshotId",
    )
    protected abstract fun correctCompletedStandaloneUnchecked(
        id: String,
        expectedUpdatedAtMs: Long,
        expectedSnapshotId: String,
        snapshotId: String,
        startedAtMs: Long?,
        completedAtMs: Long,
        activeDurationMs: Long?,
        originalZoneId: String,
        originalUtcOffsetMinutes: Int,
        primaryLocalDate: String,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE activity_executions SET started_at_ms = :startedAtMs, completed_at_ms = :completedAtMs, " +
            "active_duration_ms = :activeDurationMs, original_utc_offset_minutes = :originalUtcOffsetMinutes, " +
            "primary_local_date = :primaryLocalDate, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND context_type = 'SEQUENCE_CHILD' AND sequence_execution_id = :sequenceExecutionId " +
            "AND sequence_occurrence_id = :sequenceOccurrenceId AND snapshot_id = :snapshotId " +
            "AND statistics_series_id IS :statisticsSeriesId AND plan_entry_id IS :planEntryId " +
            "AND status = 'COMPLETED' AND completion_reason IS :completionReason AND deleted_at_ms IS NULL " +
            "AND original_zone_id = :originalZoneId AND created_at_ms = :createdAtMs " +
            "AND updated_at_ms = :expectedUpdatedAtMs",
    )
    protected abstract fun correctSequenceChildTimingUnchecked(
        id: String,
        sequenceExecutionId: String,
        sequenceOccurrenceId: String,
        snapshotId: String,
        statisticsSeriesId: String?,
        planEntryId: String?,
        completionReason: String?,
        originalZoneId: String,
        createdAtMs: Long,
        expectedUpdatedAtMs: Long,
        startedAtMs: Long?,
        completedAtMs: Long,
        activeDurationMs: Long?,
        originalUtcOffsetMinutes: Int,
        primaryLocalDate: String,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE activity_executions SET started_at_ms = :startedAtMs, completed_at_ms = :completedAtMs, " +
            "active_duration_ms = :activeDurationMs, original_utc_offset_minutes = :originalUtcOffsetMinutes, " +
            "primary_local_date = :primaryLocalDate, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND context_type = 'SEQUENCE_CHILD' AND sequence_execution_id = :sequenceExecutionId " +
            "AND sequence_occurrence_id = :sequenceOccurrenceId AND snapshot_id = :snapshotId " +
            "AND statistics_series_id IS :statisticsSeriesId AND plan_entry_id IS :planEntryId " +
            "AND status = 'COMPLETED' AND completion_reason IS :completionReason " +
            "AND deleted_at_ms IS :deletedAtMs AND original_zone_id = :originalZoneId " +
            "AND started_at_ms IS :expectedStartedAtMs AND completed_at_ms = :expectedCompletedAtMs " +
            "AND active_duration_ms IS :expectedActiveDurationMs " +
            "AND original_utc_offset_minutes IS :expectedOriginalUtcOffsetMinutes " +
            "AND primary_local_date = :expectedPrimaryLocalDate AND created_at_ms = :createdAtMs " +
            "AND updated_at_ms = :expectedUpdatedAtMs",
    )
    protected abstract fun structurallyCorrectSequenceChildUnchecked(
        id: String,
        sequenceExecutionId: String,
        sequenceOccurrenceId: String,
        snapshotId: String,
        statisticsSeriesId: String?,
        planEntryId: String?,
        completionReason: String?,
        deletedAtMs: Long?,
        originalZoneId: String,
        expectedStartedAtMs: Long?,
        expectedCompletedAtMs: Long,
        expectedActiveDurationMs: Long?,
        expectedOriginalUtcOffsetMinutes: Int?,
        expectedPrimaryLocalDate: String,
        createdAtMs: Long,
        expectedUpdatedAtMs: Long,
        startedAtMs: Long?,
        completedAtMs: Long,
        activeDurationMs: Long?,
        originalUtcOffsetMinutes: Int?,
        primaryLocalDate: String,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE activity_execution_pauses SET started_at_ms = :startedAtMs, ended_at_ms = :endedAtMs " +
            "WHERE id = :id AND activity_execution_id = :executionId " +
            "AND started_at_ms = :expectedStartedAtMs AND ended_at_ms IS :expectedEndedAtMs",
    )
    protected abstract fun structurallyCorrectSequenceChildPauseUnchecked(
        id: String,
        executionId: String,
        expectedStartedAtMs: Long,
        expectedEndedAtMs: Long?,
        startedAtMs: Long,
        endedAtMs: Long?,
    ): Int

    @Query(
        "UPDATE activity_executions SET deleted_at_ms = :deletedAtMs, updated_at_ms = :deletedAtMs " +
            "WHERE id = :id AND context_type = 'STANDALONE' AND status = 'COMPLETED' " +
            "AND deleted_at_ms IS NULL AND updated_at_ms = :expectedUpdatedAtMs",
    )
    protected abstract fun softDeleteCompletedStandaloneUnchecked(
        id: String,
        expectedUpdatedAtMs: Long,
        deletedAtMs: Long,
    ): Int

    @Query(
        "UPDATE activity_executions SET deleted_at_ms = :deletedAtMs, updated_at_ms = :deletedAtMs " +
            "WHERE id = :id AND snapshot_id = :snapshotId AND context_type = 'SEQUENCE_CHILD' " +
            "AND sequence_execution_id = :sequenceExecutionId AND sequence_occurrence_id = :sequenceOccurrenceId " +
            "AND plan_entry_id IS NULL AND statistics_series_id IS :statisticsSeriesId AND status = 'COMPLETED' " +
            "AND started_at_ms IS :startedAtMs AND completed_at_ms = :completedAtMs " +
            "AND active_duration_ms IS :activeDurationMs AND original_zone_id = :originalZoneId " +
            "AND original_utc_offset_minutes IS :originalUtcOffsetMinutes AND primary_local_date = :primaryLocalDate " +
            "AND completion_reason IS NULL AND deleted_at_ms IS NULL AND created_at_ms = :createdAtMs " +
            "AND updated_at_ms = :expectedUpdatedAtMs",
    )
    protected abstract fun softDeleteCompletedSequenceChildUnchecked(
        id: String,
        snapshotId: String,
        sequenceExecutionId: String,
        sequenceOccurrenceId: String,
        statisticsSeriesId: String?,
        startedAtMs: Long?,
        completedAtMs: Long,
        activeDurationMs: Long?,
        originalZoneId: String,
        originalUtcOffsetMinutes: Int?,
        primaryLocalDate: String,
        createdAtMs: Long,
        expectedUpdatedAtMs: Long,
        deletedAtMs: Long,
    ): Int

    @Query("UPDATE activity_executions SET deleted_at_ms = NULL, updated_at_ms = :restoredAtMs WHERE id = :id")
    protected abstract fun restoreUnchecked(
        id: String,
        restoredAtMs: Long,
    ): Int

    @Query("DELETE FROM activity_executions WHERE id = :id")
    protected abstract fun hardDeleteUnchecked(id: String): Int

    @Transaction
    open fun insertAggregate(aggregate: ActivityExecutionAggregateEntity) {
        insertExecutionUnchecked(aggregate.execution)
        requireValidAggregate(aggregate)
        if (aggregate.pauses.isNotEmpty()) insertPausesUnchecked(aggregate.pauses)
        if (aggregate.values.isNotEmpty()) insertValuesUnchecked(aggregate.values)
    }

    @Transaction
    open fun upsertValue(value: ActivityExecutionFieldValueEntity) {
        val execution =
            requireNotNull(getById(value.activityExecutionId)) {
                "Unknown execution: ${value.activityExecutionId}"
            }
        requireValidValue(execution.snapshotId, value)
        upsertValueUnchecked(value)
    }

    @Transaction
    open fun getAggregate(id: String): ActivityExecutionAggregateEntity? {
        val execution = getById(id) ?: return null
        return ActivityExecutionAggregateEntity(execution, getPauses(id), getValues(id)).also(::requireValidAggregate)
    }

    @Transaction
    open fun getAggregateByOccurrence(occurrenceId: String): ActivityExecutionAggregateEntity? =
        getByOccurrence(occurrenceId)?.let { execution ->
            ActivityExecutionAggregateEntity(
                execution,
                getPauses(execution.id),
                getValues(execution.id),
            ).also(::requireValidAggregate)
        }

    @Transaction
    open fun getSequenceChildAggregates(sequenceExecutionId: String): List<ActivityExecutionAggregateEntity> {
        val executions = getSequenceChildren(sequenceExecutionId)
        if (executions.isEmpty()) return emptyList()
        val ids = executions.map(ActivityExecutionEntity::id)
        val pauses =
            ids
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(::getPausesForExecutions)
                .groupBy(ActivityExecutionPauseEntity::activityExecutionId)
        val values =
            ids
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMap(::getValuesForExecutions)
                .groupBy(ActivityExecutionFieldValueEntity::activityExecutionId)
        return executions.map { execution ->
            ActivityExecutionAggregateEntity(execution, pauses[execution.id].orEmpty(), values[execution.id].orEmpty())
        }
    }

    fun historicalSequenceChildValidationScope(
        aggregates: List<ActivityExecutionAggregateEntity>,
    ): HistoricalSequenceChildValidationScope {
        val snapshotIds = aggregates.map { it.execution.snapshotId }.distinct()
        val occurrenceIds = aggregates.mapNotNull { it.execution.sequenceOccurrenceId }.distinct()
        val fieldIds =
            aggregates
                .flatMap { aggregate ->
                    aggregate.values.map(ActivityExecutionFieldValueEntity::snapshotFieldId)
                }.distinct()
        val optionIds =
            aggregates
                .flatMap { aggregate ->
                    aggregate.values.mapNotNull(ActivityExecutionFieldValueEntity::categoryOptionId)
                }.distinct()
        return HistoricalSequenceChildValidationScope(
            snapshots =
                snapshotIds.chunked(SQLITE_BIND_CHUNK_SIZE).flatMap(::getSnapshotExecutionMetadataForIds).associateBy {
                    it.id
                },
            occurrences =
                occurrenceIds
                    .chunked(
                        SQLITE_BIND_CHUNK_SIZE,
                    ).flatMap(::getSequenceOccurrenceLinks)
                    .associateBy { it.id },
            fields =
                fieldIds.chunked(SQLITE_BIND_CHUNK_SIZE).flatMap(::getSnapshotFieldValueMetadataForIds).associateBy {
                    it.id
                },
            options =
                optionIds.chunked(SQLITE_BIND_CHUNK_SIZE).flatMap(::getSnapshotOptionValueMetadataForIds).associateBy {
                    it.id
                },
        )
    }

    @Transaction
    open fun persistSequenceChildDelta(
        before: ActivityExecutionAggregateEntity,
        after: ActivityExecutionAggregateEntity,
    ) {
        require(after.execution.contextType == "SEQUENCE_CHILD") {
            "Coordinated child persistence only accepts SEQUENCE_CHILD"
        }
        requireValidAggregate(after)
        require(before.execution.stableIdentity() == after.execution.stableIdentity()) {
            "Sequence child identity cannot change"
        }
        val beforePauses = before.pauses.associateBy(ActivityExecutionPauseEntity::id)
        after.pauses.forEach { pause ->
            val previous = beforePauses[pause.id]
            if (previous == null) {
                insertPauseUnchecked(pause)
            } else {
                require(
                    previous.activityExecutionId == pause.activityExecutionId &&
                        previous.startedAtMs == pause.startedAtMs,
                ) { "Sequence child pause identity cannot change" }
                if (previous != pause) {
                    require(previous.endedAtMs == null && pause.endedAtMs != null) {
                        "Existing Sequence child pauses may only be closed"
                    }
                    check(
                        closeOwnedPauseUnchecked(
                            pause.id,
                            pause.activityExecutionId,
                            requireNotNull(pause.endedAtMs),
                        ) ==
                            1,
                    )
                }
            }
        }
        require(
            after.pauses
                .map(ActivityExecutionPauseEntity::id)
                .toSet()
                .containsAll(beforePauses.keys),
        ) {
            "Sequence child transitions cannot remove pauses"
        }
        val beforeValues = before.values.associateBy(ActivityExecutionFieldValueEntity::snapshotFieldId)
        require(beforeValues.keys == after.values.map(ActivityExecutionFieldValueEntity::snapshotFieldId).toSet()) {
            "Sequence child transitions cannot add or remove values"
        }
        after.values.filter { beforeValues[it.snapshotFieldId] != it }.forEach(::upsertValueUnchecked)
        if (before.execution != after.execution) {
            val execution = after.execution
            check(
                updateSequenceChildUnchecked(
                    execution.id,
                    requireNotNull(execution.sequenceExecutionId),
                    requireNotNull(execution.sequenceOccurrenceId),
                    execution.snapshotId,
                    execution.status,
                    execution.completedAtMs,
                    execution.activeDurationMs,
                    execution.completionReason,
                    execution.deletedAtMs,
                    execution.updatedAtMs,
                ) == 1,
            )
        }
    }

    @Transaction
    open fun correctCompletedStandalone(
        expectedUpdatedAtMs: Long,
        expectedSnapshotId: String,
        after: ActivityExecutionAggregateEntity,
    ) {
        val current = requireNotNull(getAggregate(after.execution.id)) { "Unknown execution: ${after.execution.id}" }
        require(current.execution.contextType == "STANDALONE") {
            "Sequence child history requires coordinated Sequence correction"
        }
        require(current.execution.status == "COMPLETED" && current.execution.deletedAtMs == null) {
            "Only non-deleted completed history can be corrected"
        }
        if (
            current.execution.updatedAtMs != expectedUpdatedAtMs ||
            current.execution.snapshotId != expectedSnapshotId
        ) {
            throw ConcurrentModificationException("Activity history changed concurrently")
        }
        require(after.execution.updatedAtMs > current.execution.updatedAtMs) {
            "Historical correction time must advance"
        }
        require(after.pauses == current.pauses) { "Historical correction cannot edit pause rows" }
        require(after.execution.correctionIdentity() == current.execution.correctionIdentity()) {
            "Historical correction cannot change execution identity or frozen linkage"
        }
        if (after.execution.snapshotId != current.execution.snapshotId) {
            require(
                ActivityHistoricalSnapshotPolicy.isCommentOnlyReplacement(
                    loadSnapshotForCorrectionValidation(current.execution.snapshotId),
                    loadSnapshotForCorrectionValidation(after.execution.snapshotId),
                ),
            ) { "Historical correction may only replace a snapshot's Short Comment" }
        }
        requireValidAggregate(after)
        val row = after.execution
        if (
            correctCompletedStandaloneUnchecked(
                row.id,
                expectedUpdatedAtMs,
                expectedSnapshotId,
                row.snapshotId,
                row.startedAtMs,
                requireNotNull(row.completedAtMs),
                row.activeDurationMs,
                row.originalZoneId,
                requireNotNull(row.originalUtcOffsetMinutes),
                row.primaryLocalDate,
                row.updatedAtMs,
            ) != 1
        ) {
            throw ConcurrentModificationException("Activity history changed concurrently")
        }
        if (after.values != current.values) {
            deleteValuesUnchecked(row.id)
            if (after.values.isNotEmpty()) insertValuesUnchecked(after.values)
        }
    }

    @Transaction
    open fun correctSequenceChildTiming(
        before: ActivityExecutionAggregateEntity,
        after: ActivityExecutionAggregateEntity,
        validationScope: HistoricalSequenceChildValidationScope? = null,
    ) {
        requireValidAggregate(after, validationScope)
        require(before.execution.sequenceHistoryIdentity() == after.execution.sequenceHistoryIdentity()) {
            "Sequence history correction cannot change child identity or frozen linkage"
        }
        require(before.pauses == after.pauses) { "Sequence timing correction cannot edit child pauses" }
        require(before.values == after.values) { "Sequence timing correction cannot edit child values" }
        require(after.execution.updatedAtMs > before.execution.updatedAtMs) {
            "Corrected child mutation time must advance"
        }
        val execution = after.execution
        if (
            correctSequenceChildTimingUnchecked(
                execution.id,
                requireNotNull(execution.sequenceExecutionId),
                requireNotNull(execution.sequenceOccurrenceId),
                execution.snapshotId,
                execution.statisticsSeriesId,
                execution.planEntryId,
                execution.completionReason,
                execution.originalZoneId,
                execution.createdAtMs,
                before.execution.updatedAtMs,
                execution.startedAtMs,
                requireNotNull(execution.completedAtMs),
                execution.activeDurationMs,
                requireNotNull(execution.originalUtcOffsetMinutes),
                execution.primaryLocalDate,
                execution.updatedAtMs,
            ) != 1
        ) {
            throw ConcurrentModificationException("Sequence child history changed concurrently")
        }
    }

    @Transaction
    open fun correctSequenceChildStructuralTiming(
        before: ActivityExecutionAggregateEntity,
        after: ActivityExecutionAggregateEntity,
        validationScope: HistoricalSequenceChildValidationScope? = null,
    ) {
        requireValidAggregate(after, validationScope)
        require(before.execution.sequenceHistoryIdentity() == after.execution.sequenceHistoryIdentity()) {
            "Structural correction cannot change child identity or frozen linkage"
        }
        require(before.values == after.values) { "Structural correction cannot edit child values" }
        require(
            before.execution.copy(
                startedAtMs = after.execution.startedAtMs,
                completedAtMs = after.execution.completedAtMs,
                activeDurationMs = after.execution.activeDurationMs,
                originalUtcOffsetMinutes = after.execution.originalUtcOffsetMinutes,
                primaryLocalDate = after.execution.primaryLocalDate,
                updatedAtMs = after.execution.updatedAtMs,
            ) == after.execution,
        ) { "Structural correction changed a preserved child fact" }
        require(after.execution.activeDurationMs == before.execution.activeDurationMs) {
            "Structural correction cannot change child duration"
        }
        require(after.execution.updatedAtMs > before.execution.updatedAtMs) {
            "Structural child mutation time must advance"
        }
        val beforePauses = before.pauses.associateBy(ActivityExecutionPauseEntity::id)
        val afterPauses = after.pauses.associateBy(ActivityExecutionPauseEntity::id)
        require(beforePauses.keys == afterPauses.keys && afterPauses.size == after.pauses.size) {
            "Structural correction cannot add or remove child pauses"
        }
        beforePauses.forEach { (id, previous) ->
            val final = afterPauses.getValue(id)
            require(previous.activityExecutionId == final.activityExecutionId)
            require(
                requireNotNull(previous.endedAtMs) - previous.startedAtMs ==
                    requireNotNull(final.endedAtMs) - final.startedAtMs,
            ) { "Structural correction must preserve pause duration" }
        }

        val previous = before.execution
        val final = after.execution
        if (
            structurallyCorrectSequenceChildUnchecked(
                previous.id,
                requireNotNull(previous.sequenceExecutionId),
                requireNotNull(previous.sequenceOccurrenceId),
                previous.snapshotId,
                previous.statisticsSeriesId,
                previous.planEntryId,
                previous.completionReason,
                previous.deletedAtMs,
                previous.originalZoneId,
                previous.startedAtMs,
                requireNotNull(previous.completedAtMs),
                previous.activeDurationMs,
                previous.originalUtcOffsetMinutes,
                previous.primaryLocalDate,
                previous.createdAtMs,
                previous.updatedAtMs,
                final.startedAtMs,
                requireNotNull(final.completedAtMs),
                final.activeDurationMs,
                final.originalUtcOffsetMinutes,
                final.primaryLocalDate,
                final.updatedAtMs,
            ) != 1
        ) {
            throw ConcurrentModificationException("Sequence child history changed concurrently")
        }
        after.pauses.forEach { pause ->
            val old = beforePauses.getValue(pause.id)
            if (old == pause) return@forEach
            if (
                structurallyCorrectSequenceChildPauseUnchecked(
                    old.id,
                    old.activityExecutionId,
                    old.startedAtMs,
                    old.endedAtMs,
                    pause.startedAtMs,
                    pause.endedAtMs,
                ) != 1
            ) {
                throw ConcurrentModificationException("Sequence child pause history changed concurrently")
            }
        }
    }

    @Transaction
    open fun softDeleteCompletedStandalone(
        id: String,
        expectedUpdatedAtMs: Long,
        deletedAtMs: Long,
    ) {
        val current = requireNotNull(getById(id)) { "Unknown execution: $id" }
        require(current.contextType == "STANDALONE") {
            "Sequence child history requires coordinated Sequence deletion"
        }
        require(current.status == "COMPLETED" && current.deletedAtMs == null) {
            "Only non-deleted completed history can be deleted"
        }
        if (current.updatedAtMs != expectedUpdatedAtMs) {
            throw ConcurrentModificationException("Activity history changed concurrently")
        }
        require(deletedAtMs > current.updatedAtMs) { "Historical deletion time must advance" }
        if (softDeleteCompletedStandaloneUnchecked(id, expectedUpdatedAtMs, deletedAtMs) != 1) {
            throw ConcurrentModificationException("Activity history changed concurrently")
        }
    }

    @Transaction
    open fun softDeleteCompletedSequenceChild(
        before: ActivityExecutionAggregateEntity,
        after: ActivityExecutionAggregateEntity,
        validationScope: HistoricalSequenceChildValidationScope? = null,
    ) {
        requireValidAggregate(after, validationScope)
        require(before.pauses == after.pauses && before.values == after.values) {
            "Sequence child deletion cannot change pauses or values"
        }
        require(
            before.execution.copy(
                deletedAtMs = after.execution.deletedAtMs,
                updatedAtMs = after.execution.updatedAtMs,
            ) ==
                after.execution,
        ) { "Sequence child deletion may only set deletion metadata" }
        val deletedAtMs = requireNotNull(after.execution.deletedAtMs)
        require(deletedAtMs == after.execution.updatedAtMs && deletedAtMs > before.execution.updatedAtMs) {
            "Sequence child deletion time must advance"
        }
        val row = before.execution
        if (
            softDeleteCompletedSequenceChildUnchecked(
                row.id,
                row.snapshotId,
                requireNotNull(row.sequenceExecutionId),
                requireNotNull(row.sequenceOccurrenceId),
                row.statisticsSeriesId,
                row.startedAtMs,
                requireNotNull(row.completedAtMs),
                row.activeDurationMs,
                row.originalZoneId,
                row.originalUtcOffsetMinutes,
                row.primaryLocalDate,
                row.createdAtMs,
                row.updatedAtMs,
                deletedAtMs,
            ) != 1
        ) {
            throw ConcurrentModificationException("Sequence child history changed concurrently")
        }
    }

    @Transaction
    open fun softDelete(
        id: String,
        deletedAtMs: Long,
    ): Int {
        requireStandaloneMutation(id) ?: return 0
        return softDeleteUnchecked(id, deletedAtMs)
    }

    @Transaction
    open fun restore(
        id: String,
        restoredAtMs: Long,
    ): Int {
        requireStandaloneMutation(id) ?: return 0
        return restoreUnchecked(id, restoredAtMs)
    }

    @Transaction
    open fun hardDelete(id: String): Int {
        requireStandaloneMutation(id) ?: return 0
        return hardDeleteUnchecked(id)
    }

    @Transaction
    open fun pause(
        id: String,
        pauseId: String,
        atMs: Long,
    ) {
        val execution = requireNotNull(getById(id)) { "Unknown execution: $id" }
        requireStandaloneMutation(execution)
        requireTimedSnapshot(execution.snapshotId)
        require(execution.status == "RUNNING" && execution.startedAtMs != null) { "Only a running execution can pause" }
        require(atMs >= execution.startedAtMs && atMs >= execution.updatedAtMs) { "Pause time is out of order" }
        require(getPauses(id).none { it.endedAtMs == null }) { "Execution already has an open pause" }
        insertPauseUnchecked(ActivityExecutionPauseEntity(pauseId, id, atMs, null))
        check(updateStatusUnchecked(id, "RUNNING", "PAUSED", atMs) == 1)
    }

    @Transaction
    open fun resume(
        id: String,
        atMs: Long,
    ) {
        val execution = requireNotNull(getById(id)) { "Unknown execution: $id" }
        requireStandaloneMutation(execution)
        requireTimedSnapshot(execution.snapshotId)
        require(execution.status == "PAUSED" && atMs >= execution.updatedAtMs) {
            "Only a paused execution can resume in order"
        }
        val pause = getPauses(id).single { it.endedAtMs == null }
        require(atMs >= pause.startedAtMs) { "Resume cannot precede pause" }
        check(closePauseUnchecked(pause.id, atMs) == 1)
        check(updateStatusUnchecked(id, "PAUSED", "RUNNING", atMs) == 1)
    }

    @Transaction
    open fun complete(
        id: String,
        atMs: Long,
    ) {
        val execution = requireNotNull(getById(id)) { "Unknown execution: $id" }
        requireStandaloneMutation(execution)
        requireTimedSnapshot(execution.snapshotId)
        val startedAtMs = requireNotNull(execution.startedAtMs) { "Timed completion requires a start" }
        require(execution.status == "RUNNING" || execution.status == "PAUSED") { "Execution is not active" }
        require(atMs >= execution.updatedAtMs && atMs >= startedAtMs) { "Completion time is out of order" }
        getPauses(id).singleOrNull { it.endedAtMs == null }?.let { pause ->
            check(closePauseUnchecked(pause.id, atMs) == 1)
        }
        val pauses =
            getPauses(id).map { pause ->
                ActivityExecutionPause(
                    ActivityExecutionPauseId(pause.id),
                    Instant.ofEpochMilli(pause.startedAtMs),
                    pause.endedAtMs?.let(Instant::ofEpochMilli),
                )
            }
        val duration =
            ActivityExecutionDurationCalculator.calculate(
                Instant.ofEpochMilli(startedAtMs),
                Instant.ofEpochMilli(atMs),
                pauses,
            )
        check(markCompletedUnchecked(id, atMs, duration.toMillis()) == 1)
    }

    private fun requireValidAggregate(
        aggregate: ActivityExecutionAggregateEntity,
        validationScope: HistoricalSequenceChildValidationScope? = null,
    ) {
        val execution = aggregate.execution
        requireValidContext(execution, validationScope)
        requireValidOwnedRows(aggregate, validationScope)
        val snapshot =
            requireNotNull(
                validationScope?.snapshots?.get(execution.snapshotId)
                    ?: getSnapshotExecutionMetadata(execution.snapshotId),
            ) {
                "Unknown snapshot: ${execution.snapshotId}"
            }
        requireValidStatisticsSeries(execution, snapshot)
        when (snapshot.timeTrackingMode) {
            "STOPWATCH", "TIMER" -> requireValidTimedAggregate(execution, aggregate.pauses)
            "NO_LIVE_TRACKING" ->
                require(
                    execution.status == "COMPLETED" &&
                        execution.startedAtMs == null &&
                        execution.completedAtMs != null &&
                        execution.activeDurationMs == null &&
                        aggregate.pauses.isEmpty(),
                ) { "NO_LIVE_TRACKING requires an immediate completed execution without duration or pauses" }
            else -> throw IllegalArgumentException("Unknown snapshot time tracking mode: ${snapshot.timeTrackingMode}")
        }
    }

    private fun requireValidContext(
        execution: ActivityExecutionEntity,
        validationScope: HistoricalSequenceChildValidationScope? = null,
    ) {
        when (execution.contextType) {
            "STANDALONE" -> {
                require(execution.sequenceExecutionId == null && execution.sequenceOccurrenceId == null) {
                    "Standalone execution cannot reference Sequence ownership"
                }
                execution.planEntryId?.let { planId ->
                    val plan = requireNotNull(getPlanLink(planId)) { "Unknown Plan: $planId" }
                    require(validActivityPlanLink(execution, plan)) {
                        "ActivityExecution Plan linkage must match kind and snapshot"
                    }
                }
            }
            "SEQUENCE_CHILD" -> {
                val sequenceExecutionId =
                    requireNotNull(execution.sequenceExecutionId) { "Sequence child requires its parent execution" }
                val occurrenceId =
                    requireNotNull(execution.sequenceOccurrenceId) { "Sequence child requires its occurrence" }
                require(execution.completionReason == null) {
                    "Normal Sequence child completion reason belongs to the occurrence"
                }
                require(execution.planEntryId == null) { "Sequence child cannot link a Plan directly" }
                val occurrence =
                    requireNotNull(
                        validationScope?.occurrences?.get(occurrenceId) ?: getSequenceOccurrenceLink(occurrenceId),
                    ) { "Unknown Sequence occurrence: $occurrenceId" }
                require(
                    occurrence.sequenceExecutionId == sequenceExecutionId &&
                        occurrence.activitySnapshotId == execution.snapshotId,
                ) { "Sequence child must match its occurrence parent and Activity snapshot" }
            }
            else -> throw IllegalArgumentException("Unknown execution context: ${execution.contextType}")
        }
    }

    private fun requireValidStatisticsSeries(
        execution: ActivityExecutionEntity,
        snapshot: ActivitySnapshotExecutionMetadataRow,
    ) {
        val expectedStatisticsSeriesId =
            if (execution.contextType == "SEQUENCE_CHILD") {
                snapshot.statisticsSeriesId
            } else {
                snapshot.statisticsSeriesId ?: ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value
            }
        require(execution.statisticsSeriesId == expectedStatisticsSeriesId) {
            "Execution StatisticsSeries must match its snapshot and context"
        }
    }

    private fun validActivityPlanLink(
        execution: ActivityExecutionEntity,
        plan: ExecutionPlanLinkRow,
    ): Boolean =
        plan.trackableKind == "ACTIVITY" &&
            plan.activitySnapshotId?.let { frozenSnapshotId ->
                frozenSnapshotId == execution.snapshotId ||
                    (
                        plan.status == "FULFILLED" &&
                            plan.fulfilledActivityExecutionId == execution.id &&
                            ActivityHistoricalSnapshotPolicy.isCommentOnlyReplacement(
                                loadSnapshotForCorrectionValidation(frozenSnapshotId),
                                loadSnapshotForCorrectionValidation(execution.snapshotId),
                            )
                    )
            } == true

    private fun loadSnapshotForCorrectionValidation(id: String) =
        ActivitySnapshotAggregateEntity(
            requireNotNull(getSnapshotForCorrectionValidation(id)) { "Unknown snapshot: $id" },
            requireNotNull(getSnapshotSettingsForCorrectionValidation(id)) { "Snapshot $id is missing settings" },
            getSnapshotFieldsForCorrectionValidation(id),
            getSnapshotOptionsForCorrectionValidation(id),
        ).toDomain()

    private fun requireValidOwnedRows(
        aggregate: ActivityExecutionAggregateEntity,
        validationScope: HistoricalSequenceChildValidationScope? = null,
    ) {
        aggregate.pauses.forEach { pause ->
            require(pause.activityExecutionId == aggregate.execution.id) {
                "Execution pause must belong to the inserted execution"
            }
        }
        val values = aggregate.values
        values.forEach { value ->
            require(value.activityExecutionId == aggregate.execution.id) {
                "Execution value must belong to the inserted execution"
            }
        }
        val fieldIds = values.map(ActivityExecutionFieldValueEntity::snapshotFieldId)
        require(fieldIds.distinct().size == fieldIds.size) { "Execution values must target unique snapshot Fields" }
        if (fieldIds.isEmpty()) return
        val fields =
            validationScope?.fields
                ?: fieldIds
                    .distinct()
                    .chunked(SQLITE_BIND_CHUNK_SIZE)
                    .flatMap { getSnapshotFieldValueMetadata(aggregate.execution.snapshotId, it) }
                    .associateBy(ActivitySnapshotFieldValueMetadataRow::id)
        require(fieldIds.all(fields::contains)) {
            "Execution value field must belong to its execution snapshot"
        }
        val optionIds = values.mapNotNull(ActivityExecutionFieldValueEntity::categoryOptionId).distinct()
        val options =
            validationScope?.options
                ?: optionIds
                    .chunked(SQLITE_BIND_CHUNK_SIZE)
                    .flatMap { getSnapshotOptionValueMetadata(aggregate.execution.snapshotId, it) }
                    .associateBy(ActivitySnapshotOptionValueMetadataRow::id)
        values.forEach { value ->
            require(fields.getValue(value.snapshotFieldId).snapshotId == aggregate.execution.snapshotId) {
                "Execution value field must belong to its execution snapshot"
            }
            requireValidValue(
                value,
                fields.getValue(value.snapshotFieldId).fieldType,
                value.categoryOptionId?.let(options::get)?.snapshotFieldId,
            )
        }
    }

    private fun requireValidTimedAggregate(
        execution: ActivityExecutionEntity,
        pauses: List<ActivityExecutionPauseEntity>,
    ) {
        val startedAtMs = requireNotNull(execution.startedAtMs) { "Timed execution requires a start" }
        val openPauses = pauses.count { it.endedAtMs == null }
        when (execution.status) {
            "RUNNING" ->
                require(execution.completedAtMs == null && execution.activeDurationMs == null && openPauses == 0) {
                    "Running timed execution cannot have completion data or an open pause"
                }
            "PAUSED" ->
                require(execution.completedAtMs == null && execution.activeDurationMs == null && openPauses == 1) {
                    "Paused timed execution requires exactly one open pause and no completion data"
                }
            "COMPLETED" ->
                require(execution.completedAtMs != null && execution.activeDurationMs != null && openPauses == 0) {
                    "Completed timed execution requires completion, duration, and no open pause"
                }
            else -> throw IllegalArgumentException("Unknown execution status: ${execution.status}")
        }
        val timelineEndMs = execution.completedAtMs ?: execution.updatedAtMs
        val duration =
            ActivityExecutionDurationCalculator
                .calculate(
                    Instant.ofEpochMilli(startedAtMs),
                    Instant.ofEpochMilli(timelineEndMs),
                    pauses.map { pause ->
                        ActivityExecutionPause(
                            ActivityExecutionPauseId(pause.id),
                            Instant.ofEpochMilli(pause.startedAtMs),
                            Instant.ofEpochMilli(pause.endedAtMs ?: timelineEndMs),
                        )
                    },
                ).toMillis()
        if (execution.status == "COMPLETED") {
            require(execution.activeDurationMs == duration) {
                "Stored active duration must match elapsed time minus pauses"
            }
        }
    }

    private fun requireTimedSnapshot(snapshotId: String) {
        val mode = getSnapshotExecutionMetadata(snapshotId)?.timeTrackingMode
        require(mode == "STOPWATCH" || mode == "TIMER") {
            "Live transitions require a timed snapshot"
        }
    }

    private fun requireStandaloneMutation(id: String): ActivityExecutionEntity? =
        getById(id)?.also(::requireStandaloneMutation)

    private fun requireStandaloneMutation(execution: ActivityExecutionEntity) {
        require(execution.contextType == "STANDALONE") {
            "Sequence child mutations require a coordinated Sequence transaction"
        }
    }

    private fun requireValidValue(
        snapshotId: String,
        value: ActivityExecutionFieldValueEntity,
    ) {
        val fieldType =
            requireNotNull(getSnapshotFieldType(snapshotId, value.snapshotFieldId)) {
                "Execution value field must belong to its execution snapshot"
            }
        val categoryOptionFieldId =
            value.categoryOptionId
                ?.takeIf { categoryOptionBelongsToField(value.snapshotFieldId, it) }
                ?.let { value.snapshotFieldId }
        requireValidValue(value, fieldType, categoryOptionFieldId)
    }

    private fun requireValidValue(
        value: ActivityExecutionFieldValueEntity,
        fieldType: String,
        categoryOptionFieldId: String?,
    ) {
        when {
            value.numberScaled != null && value.categoryOptionId == null && value.textValue == null ->
                require(fieldType == "NUMBER") { "Number value requires a NUMBER snapshot field" }
            value.numberScaled == null && value.categoryOptionId != null && value.textValue == null -> {
                require(fieldType == "CATEGORY") { "Category value requires a CATEGORY snapshot field" }
                require(categoryOptionFieldId == value.snapshotFieldId) {
                    "Category option must belong to the value snapshot field"
                }
            }
            value.numberScaled == null && value.categoryOptionId == null && value.textValue != null ->
                require(fieldType == "TEXT") { "Text value requires a TEXT snapshot field" }
            else -> throw IllegalArgumentException("Execution field value must contain exactly one typed value")
        }
    }

    private companion object {
        const val SQLITE_BIND_CHUNK_SIZE = 900
    }
}

private fun ActivityExecutionEntity.stableIdentity(): List<Any?> =
    listOf(
        id,
        snapshotId,
        contextType,
        sequenceExecutionId,
        sequenceOccurrenceId,
        planEntryId,
        statisticsSeriesId,
        startedAtMs,
        originalZoneId,
        originalUtcOffsetMinutes,
        primaryLocalDate,
        createdAtMs,
    )

private fun ActivityExecutionEntity.correctionIdentity(): List<Any?> =
    listOf(
        id,
        contextType,
        sequenceExecutionId,
        sequenceOccurrenceId,
        planEntryId,
        statisticsSeriesId,
        status,
        completionReason,
        deletedAtMs,
        createdAtMs,
    )

private fun ActivityExecutionEntity.sequenceHistoryIdentity(): List<Any?> =
    listOf(
        id,
        snapshotId,
        contextType,
        sequenceExecutionId,
        sequenceOccurrenceId,
        planEntryId,
        statisticsSeriesId,
        status,
        originalZoneId,
        completionReason,
        deletedAtMs,
        createdAtMs,
    )
