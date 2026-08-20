@file:Suppress(
    "LongMethod",
    "LongParameterList",
    "MaxLineLength",
) // Explicit owner-scoped SQL and its atomic delta stay visible at the persistence boundary.

package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshotValidator
import com.alexandr5476.lifetracing.domain.SequenceExecutionValidator
import com.alexandr5476.lifetracing.domain.TimeTrackingMode

internal data class SequenceExecutionAggregateEntity(
    val execution: SequenceExecutionEntity,
    val occurrences: List<SequenceOccurrenceEntity> = emptyList(),
    val intervals: List<SequenceIntervalEntity> = emptyList(),
    val values: List<SequenceExecutionFieldValueEntity> = emptyList(),
)

@Dao
@Suppress("TooManyFunctions") // One focused DAO owns one bounded aggregate and its validation metadata.
internal abstract class SequenceExecutionDao {
    @Query("SELECT * FROM sequence_executions WHERE id = :id")
    abstract fun getById(id: String): SequenceExecutionEntity?

    @Query(
        "SELECT * FROM sequence_occurrences WHERE sequence_execution_id = :executionId ORDER BY runtime_position, id",
    )
    abstract fun getOccurrences(executionId: String): List<SequenceOccurrenceEntity>

    @Query("SELECT * FROM sequence_intervals WHERE sequence_execution_id = :executionId ORDER BY started_at_ms, id")
    abstract fun getIntervals(executionId: String): List<SequenceIntervalEntity>

    @Query(
        "SELECT * FROM sequence_execution_field_values WHERE sequence_execution_id = :executionId ORDER BY snapshot_field_id",
    )
    abstract fun getValues(executionId: String): List<SequenceExecutionFieldValueEntity>

    @Query("SELECT * FROM sequence_snapshots WHERE id = :id")
    protected abstract fun getSnapshot(id: String): SequenceSnapshotEntity?

    @Query("SELECT * FROM sequence_snapshot_settings WHERE sequence_snapshot_id = :id")
    protected abstract fun getSnapshotSettings(id: String): SequenceSnapshotSettingsEntity?

    @Query("SELECT * FROM sequence_snapshot_fields WHERE sequence_snapshot_id = :id ORDER BY position, id")
    protected abstract fun getSnapshotFields(id: String): List<SequenceSnapshotFieldEntity>

    @Query(
        "SELECT options.* FROM sequence_snapshot_category_options AS options INNER JOIN sequence_snapshot_fields AS fields ON fields.id = options.sequence_snapshot_field_id WHERE fields.sequence_snapshot_id = :id ORDER BY fields.position, options.position, options.id",
    )
    protected abstract fun getSnapshotOptions(id: String): List<SequenceSnapshotCategoryOptionEntity>

    @Query(
        "SELECT * FROM sequence_snapshot_nodes WHERE sequence_snapshot_id = :id ORDER BY parent_repeat_node_id, position, id",
    )
    protected abstract fun getSnapshotNodes(id: String): List<SequenceSnapshotNodeEntity>

    @Query(
        "SELECT overrides.* FROM sequence_snapshot_step_overrides AS overrides INNER JOIN sequence_snapshot_nodes AS nodes ON nodes.id = overrides.sequence_snapshot_node_id WHERE nodes.sequence_snapshot_id = :id ORDER BY overrides.sequence_snapshot_node_id",
    )
    protected abstract fun getSnapshotOverrides(id: String): List<SequenceSnapshotStepOverrideEntity>

    @Query("SELECT id, time_tracking_mode FROM activity_snapshots WHERE id IN (:ids)")
    protected abstract fun activitySnapshotModes(ids: List<String>): List<ActivitySnapshotModeRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertExecutionUnchecked(execution: SequenceExecutionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertOccurrencesUnchecked(occurrences: List<SequenceOccurrenceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertIntervalsUnchecked(intervals: List<SequenceIntervalEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertValuesUnchecked(values: List<SequenceExecutionFieldValueEntity>)

    @Upsert
    protected abstract fun upsertValueUnchecked(value: SequenceExecutionFieldValueEntity)

    @Query(
        "UPDATE sequence_executions SET status = :status, ended_at_ms = :endedAtMs, " +
            "active_duration_ms = :activeDurationMs, pause_duration_ms = :pauseDurationMs, " +
            "wall_duration_ms = :wallDurationMs, current_occurrence_id = :currentOccurrenceId, " +
            "updated_at_ms = :updatedAtMs WHERE id = :id",
    )
    protected abstract fun updateRuntimeRootUnchecked(
        id: String,
        status: String,
        endedAtMs: Long?,
        activeDurationMs: Long?,
        pauseDurationMs: Long?,
        wallDurationMs: Long?,
        currentOccurrenceId: String?,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE sequence_occurrences SET status = :status, entered_at_ms = :enteredAtMs, " +
            "completed_at_ms = :completedAtMs, completion_reason = :completionReason " +
            "WHERE id = :id AND sequence_execution_id = :executionId",
    )
    protected abstract fun updateRuntimeOccurrenceUnchecked(
        id: String,
        executionId: String,
        status: String,
        enteredAtMs: Long?,
        completedAtMs: Long?,
        completionReason: String?,
    ): Int

    @Query(
        "UPDATE sequence_intervals SET ended_at_ms = :endedAtMs " +
            "WHERE id = :id AND sequence_execution_id = :executionId AND ended_at_ms IS NULL",
    )
    protected abstract fun closeRuntimeIntervalUnchecked(
        id: String,
        executionId: String,
        endedAtMs: Long,
    ): Int

    @Query(
        "UPDATE sequence_executions SET current_occurrence_id = :occurrenceId WHERE id = :executionId AND current_occurrence_id IS NULL",
    )
    protected abstract fun setCurrentOccurrenceUnchecked(
        executionId: String,
        occurrenceId: String,
    ): Int

    @Query("DELETE FROM sequence_executions WHERE id = :id")
    abstract fun hardDelete(id: String): Int

    @Transaction
    open fun insertAggregate(aggregate: SequenceExecutionAggregateEntity) {
        require(aggregate.occurrences.all { it.isRuntimeAdded || it.sourceSequenceSnapshotNodeId != null }) {
            "New snapshot-derived occurrences require their frozen source Step"
        }
        require(
            aggregate.intervals.all {
                (it.kind != "ACTIVE_STEP" && it.kind != "STEP_PAUSE") || it.occurrenceId != null
            },
        ) { "New Step-classified intervals require an occurrence" }
        requireValidAggregate(aggregate)
        val current = aggregate.execution.currentOccurrenceId
        insertExecutionUnchecked(aggregate.execution.copy(currentOccurrenceId = null))
        if (aggregate.occurrences.isNotEmpty()) insertOccurrencesUnchecked(aggregate.occurrences)
        if (current != null) check(setCurrentOccurrenceUnchecked(aggregate.execution.id, current) == 1)
        if (aggregate.intervals.isNotEmpty()) insertIntervalsUnchecked(aggregate.intervals)
        if (aggregate.values.isNotEmpty()) insertValuesUnchecked(aggregate.values)
    }

    @Transaction
    open fun getAggregate(id: String): SequenceExecutionAggregateEntity? {
        val execution = getById(id) ?: return null
        return SequenceExecutionAggregateEntity(
            execution,
            getOccurrences(id),
            getIntervals(id),
            getValues(id),
        ).also(::requireValidAggregate)
    }

    @Transaction
    open fun persistRuntimeDelta(
        before: SequenceExecutionAggregateEntity,
        after: SequenceExecutionAggregateEntity,
    ) {
        requireValidAggregate(after)
        require(before.execution.stableIdentity() == after.execution.stableIdentity()) {
            "Runtime root identity cannot change"
        }
        val beforeOccurrences = before.occurrences.associateBy(SequenceOccurrenceEntity::id)
        require(beforeOccurrences.keys == after.occurrences.map(SequenceOccurrenceEntity::id).toSet()) {
            "Runtime transitions cannot add or remove occurrences"
        }
        after.occurrences.sortedBy(SequenceOccurrenceEntity::runtimePosition).forEach { occurrence ->
            val previous = beforeOccurrences.getValue(occurrence.id)
            require(previous.identity() == occurrence.identity()) { "Runtime occurrence identity cannot change" }
            if (previous != occurrence) {
                check(
                    updateRuntimeOccurrenceUnchecked(
                        occurrence.id,
                        occurrence.sequenceExecutionId,
                        occurrence.status,
                        occurrence.enteredAtMs,
                        occurrence.completedAtMs,
                        occurrence.completionReason,
                    ) == 1,
                )
            }
        }
        val beforeIntervals = before.intervals.associateBy(SequenceIntervalEntity::id)
        after.intervals.forEach { interval ->
            val previous = beforeIntervals[interval.id]
            if (previous == null) {
                insertIntervalsUnchecked(listOf(interval))
            } else {
                require(previous.identity() == interval.identity()) { "Runtime interval identity cannot change" }
                if (previous != interval) {
                    require(previous.endedAtMs == null && interval.endedAtMs != null) {
                        "Existing runtime intervals may only be closed"
                    }
                    check(
                        closeRuntimeIntervalUnchecked(
                            interval.id,
                            interval.sequenceExecutionId,
                            requireNotNull(interval.endedAtMs),
                        ) == 1,
                    )
                }
            }
        }
        require(
            after.intervals
                .map(SequenceIntervalEntity::id)
                .toSet()
                .containsAll(beforeIntervals.keys),
        ) {
            "Runtime transitions cannot remove intervals"
        }
        val beforeValues = before.values.associateBy(SequenceExecutionFieldValueEntity::snapshotFieldId)
        require(beforeValues.keys == after.values.map(SequenceExecutionFieldValueEntity::snapshotFieldId).toSet()) {
            "Runtime transitions cannot add or remove Sequence values"
        }
        after.values.filter { beforeValues[it.snapshotFieldId] != it }.forEach(::upsertValueUnchecked)
        if (before.execution != after.execution) {
            val execution = after.execution
            check(
                updateRuntimeRootUnchecked(
                    execution.id,
                    execution.status,
                    execution.endedAtMs,
                    execution.activeDurationMs,
                    execution.pauseDurationMs,
                    execution.wallDurationMs,
                    execution.currentOccurrenceId,
                    execution.updatedAtMs,
                ) == 1,
            )
        }
    }

    private fun requireValidAggregate(aggregate: SequenceExecutionAggregateEntity) {
        val id = aggregate.execution.id
        require(aggregate.occurrences.all { it.sequenceExecutionId == id }) { "Occurrence owner mismatch" }
        require(aggregate.intervals.all { it.sequenceExecutionId == id }) { "Interval owner mismatch" }
        require(aggregate.values.all { it.sequenceExecutionId == id }) { "Sequence value owner mismatch" }
        val snapshotId = aggregate.execution.snapshotId
        val snapshotAggregate =
            SequenceSnapshotAggregateEntity(
                requireNotNull(getSnapshot(snapshotId)) { "Unknown Sequence snapshot: $snapshotId" },
                requireNotNull(
                    getSnapshotSettings(snapshotId),
                ) { "Sequence snapshot is missing settings: $snapshotId" },
                getSnapshotFields(snapshotId),
                getSnapshotOptions(snapshotId),
                getSnapshotNodes(snapshotId),
                getSnapshotOverrides(snapshotId),
            )
        val snapshot = snapshotAggregate.toDomain()
        val activitySnapshotIds =
            snapshotAggregate.nodes.mapNotNull(SequenceSnapshotNodeEntity::activitySnapshotId).distinct()
        val activitySnapshotModes =
            activitySnapshotModes(activitySnapshotIds).associate { row ->
                ActivitySnapshotId(row.id) to TimeTrackingMode.valueOf(row.timeTrackingMode)
            }
        SequenceConfigSnapshotValidator.requireValid(snapshot, activitySnapshotModes)
        SequenceExecutionValidator.requireValid(aggregate.toDomain(), snapshot)
    }
}

private fun SequenceExecutionEntity.stableIdentity(): List<Any?> =
    listOf(
        id,
        snapshotId,
        planEntryId,
        statisticsSeriesId,
        startedAtMs,
        originalZoneId,
        originalUtcOffsetMinutes,
        primaryLocalDate,
        createdAtMs,
    )

private fun SequenceOccurrenceEntity.identity(): List<Any?> =
    listOf(
        id,
        sequenceExecutionId,
        sourceSequenceSnapshotNodeId,
        activitySnapshotId,
        runtimePosition,
        repeatSourceSnapshotNodeId,
        repeatIteration,
        isRuntimeAdded,
        isDeletedFromHistory,
    )

private fun SequenceIntervalEntity.identity(): List<Any?> =
    listOf(id, sequenceExecutionId, kind, startedAtMs, occurrenceId)
