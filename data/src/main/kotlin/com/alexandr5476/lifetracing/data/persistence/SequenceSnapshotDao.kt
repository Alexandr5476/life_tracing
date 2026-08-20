@file:Suppress("MaxLineLength") // Bounded SQL queries remain readable as single statements.

package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

internal data class SequenceSnapshotAggregateEntity(
    val snapshot: SequenceSnapshotEntity,
    val settings: SequenceSnapshotSettingsEntity,
    val fields: List<SequenceSnapshotFieldEntity> = emptyList(),
    val options: List<SequenceSnapshotCategoryOptionEntity> = emptyList(),
    val nodes: List<SequenceSnapshotNodeEntity> = emptyList(),
    val stepOverrides: List<SequenceSnapshotStepOverrideEntity> = emptyList(),
)

@Dao
// One immutable aggregate has a small, bounded persistence surface.
@Suppress("TooManyFunctions", "CyclomaticComplexMethod")
internal abstract class SequenceSnapshotDao {
    @Query("SELECT * FROM sequence_snapshots WHERE id = :id")
    abstract fun getById(id: String): SequenceSnapshotEntity?

    @Query("SELECT * FROM sequence_snapshot_settings WHERE sequence_snapshot_id = :snapshotId")
    abstract fun getSettings(snapshotId: String): SequenceSnapshotSettingsEntity?

    @Query("SELECT * FROM sequence_snapshot_fields WHERE sequence_snapshot_id = :snapshotId ORDER BY position, id")
    abstract fun getFields(snapshotId: String): List<SequenceSnapshotFieldEntity>

    @Query(
        "SELECT options.* FROM sequence_snapshot_category_options AS options INNER JOIN sequence_snapshot_fields AS fields ON fields.id = options.sequence_snapshot_field_id WHERE fields.sequence_snapshot_id = :snapshotId ORDER BY fields.position, options.position, options.id",
    )
    abstract fun getOptions(snapshotId: String): List<SequenceSnapshotCategoryOptionEntity>

    @Query(
        "SELECT * FROM sequence_snapshot_nodes WHERE sequence_snapshot_id = :snapshotId ORDER BY parent_repeat_node_id, position, id",
    )
    abstract fun getNodes(snapshotId: String): List<SequenceSnapshotNodeEntity>

    @Query(
        "SELECT overrides.* FROM sequence_snapshot_step_overrides AS overrides INNER JOIN sequence_snapshot_nodes AS nodes ON nodes.id = overrides.sequence_snapshot_node_id WHERE nodes.sequence_snapshot_id = :snapshotId ORDER BY overrides.sequence_snapshot_node_id",
    )
    abstract fun getStepOverrides(snapshotId: String): List<SequenceSnapshotStepOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertSnapshotUnchecked(snapshot: SequenceSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertSettingsUnchecked(settings: SequenceSnapshotSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertFieldsUnchecked(fields: List<SequenceSnapshotFieldEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertOptionsUnchecked(options: List<SequenceSnapshotCategoryOptionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertNodesUnchecked(nodes: List<SequenceSnapshotNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertOverridesUnchecked(overrides: List<SequenceSnapshotStepOverrideEntity>)

    @Query("SELECT id FROM activity_snapshots WHERE id IN (:ids)")
    protected abstract fun existingActivitySnapshots(ids: List<String>): List<String>

    @Query("SELECT id, time_tracking_mode FROM activity_snapshots WHERE id IN (:ids)")
    protected abstract fun activitySnapshotModes(ids: List<String>): List<ActivitySnapshotModeRow>

    @Query("DELETE FROM sequence_snapshots WHERE id = :snapshotId")
    protected abstract fun deleteSnapshotUnchecked(snapshotId: String): Int

    @Query(
        "SELECT DISTINCT activity_snapshot_id FROM sequence_snapshot_nodes WHERE sequence_snapshot_id = :snapshotId AND activity_snapshot_id IS NOT NULL",
    )
    protected abstract fun stepActivitySnapshotIds(snapshotId: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM sequence_nodes WHERE activity_snapshot_id = :snapshotId LIMIT 1)")
    protected abstract fun hasMutableStepReference(snapshotId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sequence_snapshot_nodes WHERE activity_snapshot_id = :snapshotId LIMIT 1)")
    protected abstract fun hasFrozenStepReference(snapshotId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM activity_executions WHERE snapshot_id = :snapshotId LIMIT 1)")
    protected abstract fun hasExecutionReference(snapshotId: String): Boolean

    @Query("DELETE FROM activity_snapshots WHERE id = :snapshotId")
    protected abstract fun deleteActivitySnapshotUnchecked(snapshotId: String): Int

    @Transaction
    open fun insertAggregate(aggregate: SequenceSnapshotAggregateEntity) {
        requireValidAggregate(aggregate)
        insertSnapshotUnchecked(aggregate.snapshot)
        insertSettingsUnchecked(aggregate.settings)
        if (aggregate.fields.isNotEmpty()) insertFieldsUnchecked(aggregate.fields)
        if (aggregate.options.isNotEmpty()) insertOptionsUnchecked(aggregate.options)
        if (aggregate.nodes.isNotEmpty()) {
            insertNodesUnchecked(
                aggregate.nodes.sortedBy { it.parentRepeatNodeId != null },
            )
        }
        val overrides = aggregate.stepOverrides.filterNot { it.isEmpty() }
        if (overrides.isNotEmpty()) insertOverridesUnchecked(overrides)
    }

    @Transaction
    open fun getAggregate(id: String): SequenceSnapshotAggregateEntity? {
        val snapshot = getById(id) ?: return null
        return SequenceSnapshotAggregateEntity(
            snapshot,
            checkNotNull(getSettings(id)) {
                "Snapshot $id is missing settings"
            },
            getFields(id),
            getOptions(id),
            getNodes(id),
            getStepOverrides(id),
        ).also(::requireValidAggregate)
    }

    @Transaction
    open fun hardDeleteAndPruneOwnedActivitySnapshots(snapshotId: String) {
        val children = stepActivitySnapshotIds(snapshotId)
        if (deleteSnapshotUnchecked(snapshotId) == 0) return
        children.forEach { child ->
            if (!hasMutableStepReference(child) && !hasFrozenStepReference(child) && !hasExecutionReference(child)) {
                check(deleteActivitySnapshotUnchecked(child) == 1)
            }
        }
    }

    private fun requireValidAggregate(aggregate: SequenceSnapshotAggregateEntity) {
        val id = aggregate.snapshot.id
        require(aggregate.settings.sequenceSnapshotId == id) { "Settings owner mismatch" }
        require(aggregate.fields.all { it.sequenceSnapshotId == id }) { "Field owner mismatch" }
        val fields = aggregate.fields.mapTo(hashSetOf()) { it.id }
        require(aggregate.options.all { it.sequenceSnapshotFieldId in fields }) { "Category option owner mismatch" }
        require(aggregate.nodes.all { it.sequenceSnapshotId == id }) { "Node owner mismatch" }
        require(
            aggregate.nodes
                .map {
                    it.id
                }.distinct()
                .size == aggregate.nodes.size,
        ) { "Sequence snapshot node identities must be unique" }
        val nodes = aggregate.nodes.associateBy(SequenceSnapshotNodeEntity::id)
        aggregate.nodes.forEach { node ->
            when (node.nodeType) {
                "STEP" -> {
                    require(node.activitySnapshotId != null && node.repeatCount == null) { "Invalid STEP row shape" }
                    node.parentRepeatNodeId?.let { parent ->
                        require(nodes[parent]?.nodeType == "REPEAT") { "Step parent must be a same-snapshot Repeat" }
                    }
                }
                "REPEAT" ->
                    require(
                        node.activitySnapshotId == null &&
                            node.repeatCount != null &&
                            node.repeatCount > 0 &&
                            node.parentRepeatNodeId == null,
                    ) { "Invalid REPEAT row shape" }
                else -> throw IllegalArgumentException("Unknown sequence snapshot node type code: ${node.nodeType}")
            }
        }
        aggregate.nodes.groupBy { it.parentRepeatNodeId }.values.forEach { siblings ->
            require(
                siblings.map { it.position }.distinct().size == siblings.size,
            ) { "Sibling node positions must be unique" }
        }
        val ids = aggregate.nodes.mapNotNull(SequenceSnapshotNodeEntity::activitySnapshotId).distinct()
        require(existingActivitySnapshots(ids).toSet() == ids.toSet()) {
            "Every Step must reference an existing ActivitySnapshot"
        }
        val modes = activitySnapshotModes(ids).associateBy(ActivitySnapshotModeRow::id)
        aggregate.stepOverrides.forEach { override ->
            val owner = requireNotNull(nodes[override.sequenceSnapshotNodeId]) { "Step override owner must exist" }
            require(owner.nodeType == "STEP") { "Only a Step can own execution-setting overrides" }
            if (override.isEmpty()) return@forEach
            require(override.startCountdownMs == null || override.startCountdownMs >= 0) {
                "Step start countdown must not be negative"
            }
            override.timerZeroBehavior?.let { code ->
                require(code == "FINISH" || code == "OVERTIME") { "Unknown Step timer zero behavior code: $code" }
                require(modes[owner.activitySnapshotId]?.timeTrackingMode == "TIMER") {
                    "Timer zero behavior override requires a TIMER Step"
                }
            }
        }
        aggregate.toDomain()
    }

    private fun SequenceSnapshotStepOverrideEntity.isEmpty() =
        startCountdownMs == null &&
            timerZeroBehavior == null &&
            timerEndSound == null &&
            timerEndVibration == null &&
            keepScreenAwake == null
}
