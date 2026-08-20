@file:Suppress("MaxLineLength") // Bounded ownership queries are clearer inline.

package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
