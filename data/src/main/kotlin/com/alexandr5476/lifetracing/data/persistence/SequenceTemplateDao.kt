package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldEvolution
import kotlinx.coroutines.flow.Flow

internal data class SequenceTemplateAggregateEntity(
    val template: SequenceTemplateEntity,
    val settings: SequenceTemplateSettingsEntity,
    val userState: SequenceTemplateUserStateEntity,
    val fields: List<SequenceTemplateFieldEntity> = emptyList(),
    val options: List<SequenceTemplateCategoryOptionEntity> = emptyList(),
    val tags: List<SequenceTemplateTagEntity> = emptyList(),
    val nodes: List<SequenceNodeEntity> = emptyList(),
)

internal data class SequenceTemplateSemanticUpdate(
    val template: SequenceTemplateEntity,
    val settings: SequenceTemplateSettingsEntity,
    val fields: List<SequenceTemplateFieldEntity> = emptyList(),
    val options: List<SequenceTemplateCategoryOptionEntity> = emptyList(),
)

@Dao
@Suppress("TooManyFunctions") // One internal DAO owns the bounded aggregate and its transactions.
internal abstract class SequenceTemplateDao {
    @Query("SELECT * FROM sequence_templates WHERE id = :id")
    abstract fun getById(id: String): SequenceTemplateEntity?

    @Query("SELECT * FROM sequence_templates WHERE deleted_at_ms IS NULL ORDER BY name, id")
    abstract fun observeActive(): Flow<List<SequenceTemplateEntity>>

    @Query("SELECT * FROM sequence_templates WHERE deleted_at_ms IS NOT NULL ORDER BY name, id")
    abstract fun observeArchived(): Flow<List<SequenceTemplateEntity>>

    @Query("SELECT * FROM sequence_template_settings WHERE sequence_template_id = :templateId")
    abstract fun getSettings(templateId: String): SequenceTemplateSettingsEntity?

    @Query("SELECT * FROM sequence_template_user_state WHERE sequence_template_id = :templateId")
    abstract fun getUserState(templateId: String): SequenceTemplateUserStateEntity?

    @Query(
        "SELECT * FROM sequence_template_fields " +
            "WHERE sequence_template_id = :templateId AND deleted_at_ms IS NULL ORDER BY position, id",
    )
    abstract fun getActiveFields(templateId: String): List<SequenceTemplateFieldEntity>

    @Query("SELECT * FROM sequence_template_fields WHERE sequence_template_id = :templateId ORDER BY position, id")
    abstract fun getAllFields(templateId: String): List<SequenceTemplateFieldEntity>

    @Query(
        "SELECT options.* FROM sequence_template_category_options AS options " +
            "INNER JOIN sequence_template_fields AS fields ON fields.id = options.sequence_template_field_id " +
            "WHERE fields.sequence_template_id = :templateId ORDER BY fields.position, options.position, options.id",
    )
    abstract fun getOptions(templateId: String): List<SequenceTemplateCategoryOptionEntity>

    @Query("SELECT * FROM sequence_template_tags WHERE sequence_template_id = :templateId ORDER BY tag_id")
    abstract fun getTags(templateId: String): List<SequenceTemplateTagEntity>

    @Query(
        "SELECT * FROM sequence_nodes WHERE sequence_template_id = :templateId " +
            "ORDER BY parent_repeat_node_id, position, id",
    )
    abstract fun getNodes(templateId: String): List<SequenceNodeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertTemplateUnchecked(template: SequenceTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertSettingsUnchecked(settings: SequenceTemplateSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertUserStateUnchecked(userState: SequenceTemplateUserStateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertFieldsUnchecked(fields: List<SequenceTemplateFieldEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertOptionsUnchecked(options: List<SequenceTemplateCategoryOptionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertTagLinksUnchecked(tags: List<SequenceTemplateTagEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertNodesUnchecked(nodes: List<SequenceNodeEntity>)

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun updateTemplateUnchecked(template: SequenceTemplateEntity): Int

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun updateSettingsUnchecked(settings: SequenceTemplateSettingsEntity): Int

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun updateUserStateUnchecked(userState: SequenceTemplateUserStateEntity): Int

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun updateFieldsUnchecked(fields: List<SequenceTemplateFieldEntity>): Int

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun updateOptionsUnchecked(options: List<SequenceTemplateCategoryOptionEntity>): Int

    @Query("DELETE FROM sequence_nodes WHERE sequence_template_id = :templateId")
    protected abstract fun deleteNodesUnchecked(templateId: String): Int

    @Query("DELETE FROM sequence_nodes WHERE id = :nodeId")
    protected abstract fun deleteNodeUnchecked(nodeId: String): Int

    @Query("DELETE FROM sequence_template_tags WHERE sequence_template_id = :templateId")
    protected abstract fun deleteTagLinksUnchecked(templateId: String): Int

    @Query(
        "UPDATE sequence_nodes SET activity_snapshot_id = :snapshotId " +
            "WHERE id = :nodeId AND node_type = 'STEP'",
    )
    protected abstract fun updateStepSnapshotUnchecked(
        nodeId: String,
        snapshotId: String,
    ): Int

    @Query(
        "UPDATE sequence_templates SET revision = revision + 1, updated_at_ms = :updatedAtMs " +
            "WHERE id = :templateId AND revision = :expectedRevision",
    )
    protected abstract fun incrementRevisionUnchecked(
        templateId: String,
        expectedRevision: Long,
        updatedAtMs: Long,
    ): Int

    @Query("SELECT id FROM activity_snapshots WHERE id IN (:ids)")
    protected abstract fun getExistingSnapshotIds(ids: List<String>): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM sequence_nodes WHERE activity_snapshot_id = :snapshotId LIMIT 1)")
    protected abstract fun hasSequenceNodeReference(snapshotId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM activity_executions WHERE snapshot_id = :snapshotId LIMIT 1)")
    protected abstract fun hasActivityExecutionReference(snapshotId: String): Boolean

    @Query("DELETE FROM activity_snapshots WHERE id = :snapshotId")
    protected abstract fun deleteActivitySnapshotUnchecked(snapshotId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertActivitySnapshotUnchecked(snapshot: ActivitySnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertActivitySnapshotSettingsUnchecked(settings: ActivitySnapshotSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertActivitySnapshotFieldsUnchecked(fields: List<ActivitySnapshotFieldEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertActivitySnapshotOptionsUnchecked(options: List<ActivitySnapshotCategoryOptionEntity>)

    @Query("UPDATE sequence_templates SET deleted_at_ms = :deletedAtMs WHERE id = :id")
    abstract fun archive(
        id: String,
        deletedAtMs: Long,
    ): Int

    @Query("UPDATE sequence_templates SET deleted_at_ms = NULL WHERE id = :id")
    abstract fun restore(id: String): Int

    @Query("UPDATE sequence_templates SET folder_id = :folderId WHERE id = :id")
    abstract fun updateFolder(
        id: String,
        folderId: String?,
    ): Int

    @Query("UPDATE sequence_template_fields SET name = :name, updated_at_ms = :updatedAtMs WHERE id = :id")
    abstract fun updateFieldDisplayName(
        id: String,
        name: String,
        updatedAtMs: Long,
    ): Int

    @Query("UPDATE sequence_template_category_options SET label = :label WHERE id = :id")
    abstract fun updateOptionDisplayLabel(
        id: String,
        label: String,
    ): Int

    @Transaction
    open fun getAggregate(id: String): SequenceTemplateAggregateEntity? {
        val template = getById(id) ?: return null
        return SequenceTemplateAggregateEntity(
            template = template,
            settings = checkNotNull(getSettings(id)) { "SequenceTemplate $id is missing settings" },
            userState = checkNotNull(getUserState(id)) { "SequenceTemplate $id is missing user state" },
            fields = getAllFields(id),
            options = getOptions(id),
            tags = getTags(id),
            nodes = getNodes(id),
        )
    }

    @Transaction
    open fun insertAggregate(aggregate: SequenceTemplateAggregateEntity) {
        requireValidAggregate(aggregate)
        insertTemplateUnchecked(aggregate.template)
        insertSettingsUnchecked(aggregate.settings)
        insertUserStateUnchecked(aggregate.userState)
        if (aggregate.fields.isNotEmpty()) insertFieldsUnchecked(aggregate.fields)
        if (aggregate.options.isNotEmpty()) insertOptionsUnchecked(aggregate.options)
        if (aggregate.tags.isNotEmpty()) insertTagLinksUnchecked(aggregate.tags)
        if (aggregate.nodes.isNotEmpty()) {
            insertNodesUnchecked(aggregate.nodes.sortedBy { it.parentRepeatNodeId != null })
        }
    }

    @Transaction
    open fun updateSemanticAggregate(update: SequenceTemplateSemanticUpdate) {
        val current =
            requireNotNull(getAggregate(update.template.id)) { "Unknown SequenceTemplate: ${update.template.id}" }
        require(update.template.revision == current.template.revision + 1) {
            "A semantic aggregate update must increment revision exactly once"
        }
        val proposed =
            current.copy(
                template = update.template,
                settings = update.settings,
                fields = update.fields,
                options = update.options,
            )
        requireValidAggregate(proposed)
        require(current.fields.map { it.id }.all(update.fields.map { it.id }.toSet()::contains)) {
            "Existing Field identities must be retained and archived instead of removed"
        }
        require(current.options.map { it.id }.all(update.options.map { it.id }.toSet()::contains)) {
            "Existing Category option identities must be retained and archived instead of removed"
        }
        val previousDomainFields = current.toDomain().fields.associateBy { it.id }
        proposed.toDomain().fields.forEach { updated ->
            previousDomainFields[updated.id]?.let { previous ->
                SequenceTemplateFieldEvolution.requireSameIdentityCompatible(previous, updated)
            }
        }
        check(updateTemplateUnchecked(update.template) == 1)
        check(updateSettingsUnchecked(update.settings) == 1)
        val existingFields = current.fields.mapTo(hashSetOf()) { it.id }
        val existingOptions = current.options.mapTo(hashSetOf()) { it.id }
        update.fields.partition { it.id in existingFields }.let { (old, new) ->
            if (old.isNotEmpty()) check(updateFieldsUnchecked(old) == old.size)
            if (new.isNotEmpty()) insertFieldsUnchecked(new)
        }
        update.options.partition { it.id in existingOptions }.let { (old, new) ->
            if (old.isNotEmpty()) check(updateOptionsUnchecked(old) == old.size)
            if (new.isNotEmpty()) insertOptionsUnchecked(new)
        }
    }

    @Transaction
    open fun updateUserState(userState: SequenceTemplateUserStateEntity) {
        check(updateUserStateUnchecked(userState) == 1) { "Unknown SequenceTemplate: ${userState.sequenceTemplateId}" }
    }

    @Transaction
    open fun replaceTags(
        templateId: String,
        tags: List<SequenceTemplateTagEntity>,
    ) {
        require(tags.all { it.sequenceTemplateId == templateId }) { "Tag links must belong to the SequenceTemplate" }
        deleteTagLinksUnchecked(templateId)
        if (tags.isNotEmpty()) insertTagLinksUnchecked(tags)
    }

    @Transaction
    open fun replaceNodeStructure(
        templateId: String,
        nodes: List<SequenceNodeEntity>,
        expectedRevision: Long,
        updatedAtMs: Long,
    ) {
        requireNotNull(getById(templateId)) { "Unknown SequenceTemplate: $templateId" }
        require(nodes.all { it.sequenceTemplateId == templateId }) { "Nodes must belong to the SequenceTemplate" }
        requireValidNodes(nodes)
        val oldSnapshotIds = getNodes(templateId).mapNotNull(SequenceNodeEntity::activitySnapshotId).distinct()
        deleteNodesUnchecked(templateId)
        if (nodes.isNotEmpty()) insertNodesUnchecked(nodes.sortedBy { it.parentRepeatNodeId != null })
        check(incrementRevisionUnchecked(templateId, expectedRevision, updatedAtMs) == 1) {
            "SequenceTemplate revision changed concurrently"
        }
        oldSnapshotIds.forEach(::pruneSnapshotIfUnreferenced)
    }

    @Transaction
    open fun removeNode(
        templateId: String,
        nodeId: String,
        expectedRevision: Long,
        updatedAtMs: Long,
    ) {
        val current = getNodes(templateId)
        require(current.any { it.id == nodeId }) { "Unknown Sequence node: $nodeId" }
        val removedSnapshotIds =
            current
                .filter { it.id == nodeId || it.parentRepeatNodeId == nodeId }
                .mapNotNull(SequenceNodeEntity::activitySnapshotId)
                .distinct()
        check(deleteNodeUnchecked(nodeId) == 1)
        check(incrementRevisionUnchecked(templateId, expectedRevision, updatedAtMs) == 1) {
            "SequenceTemplate revision changed concurrently"
        }
        removedSnapshotIds.forEach(::pruneSnapshotIfUnreferenced)
    }

    @Transaction
    open fun replaceStepSnapshot(
        nodeId: String,
        replacement: ActivitySnapshotAggregateEntity,
        expectedRevision: Long,
        updatedAtMs: Long,
    ) {
        val node = requireNotNull(findNode(nodeId)) { "Unknown Sequence node: $nodeId" }
        require(node.nodeType == "STEP") { "Only a Step can replace its ActivitySnapshot" }
        val oldSnapshotId = requireNotNull(node.activitySnapshotId)
        require(replacement.snapshot.id != oldSnapshotId) { "Snapshot replacement requires a new identity" }
        require(replacement.snapshot.locallyModified) { "Local Step replacement must be locally modified" }
        requireValidSnapshotAggregate(replacement)
        insertActivitySnapshotUnchecked(replacement.snapshot)
        insertActivitySnapshotSettingsUnchecked(replacement.settings)
        if (replacement.fields.isNotEmpty()) insertActivitySnapshotFieldsUnchecked(replacement.fields)
        if (replacement.options.isNotEmpty()) insertActivitySnapshotOptionsUnchecked(replacement.options)
        check(updateStepSnapshotUnchecked(nodeId, replacement.snapshot.id) == 1)
        check(incrementRevisionUnchecked(node.sequenceTemplateId, expectedRevision, updatedAtMs) == 1) {
            "SequenceTemplate revision changed concurrently"
        }
        pruneSnapshotIfUnreferenced(oldSnapshotId)
    }

    @Query("SELECT * FROM sequence_nodes WHERE id = :id")
    protected abstract fun findNode(id: String): SequenceNodeEntity?

    private fun requireValidAggregate(aggregate: SequenceTemplateAggregateEntity) {
        val templateId = aggregate.template.id
        require(aggregate.settings.sequenceTemplateId == templateId) { "Settings owner mismatch" }
        require(aggregate.userState.sequenceTemplateId == templateId) { "User-state owner mismatch" }
        require(aggregate.fields.all { it.sequenceTemplateId == templateId }) { "Field owner mismatch" }
        require(aggregate.tags.all { it.sequenceTemplateId == templateId }) { "Tag owner mismatch" }
        require(aggregate.nodes.all { it.sequenceTemplateId == templateId }) { "Node owner mismatch" }
        val fieldIds = aggregate.fields.mapTo(hashSetOf()) { it.id }
        require(aggregate.options.all { it.sequenceTemplateFieldId in fieldIds }) { "Category option owner mismatch" }
        requireValidNodes(aggregate.nodes)
        aggregate.toDomain()
    }

    private fun requireValidNodes(nodes: List<SequenceNodeEntity>) {
        require(nodes.map { it.id }.distinct().size == nodes.size) { "Sequence node identities must be unique" }
        val rowsById = nodes.associateBy { it.id }
        nodes.forEach { node ->
            when (node.nodeType) {
                "STEP" -> {
                    require(node.activitySnapshotId != null && node.repeatCount == null) { "Invalid STEP row shape" }
                    node.parentRepeatNodeId?.let { parentId ->
                        val parent =
                            requireNotNull(rowsById[parentId]) { "Step parent must exist in the same SequenceTemplate" }
                        require(parent.sequenceTemplateId == node.sequenceTemplateId && parent.nodeType == "REPEAT") {
                            "Step parent must be a Repeat in the same SequenceTemplate"
                        }
                    }
                }
                "REPEAT" ->
                    require(
                        node.activitySnapshotId == null &&
                            node.repeatCount != null &&
                            node.repeatCount > 0 &&
                            node.parentRepeatNodeId == null,
                    ) { "Invalid REPEAT row shape" }
                else -> throw IllegalArgumentException("Unknown sequence node type code: ${node.nodeType}")
            }
        }
        nodes.groupBy { it.sequenceTemplateId to it.parentRepeatNodeId }.values.forEach { siblings ->
            require(siblings.map { it.position }.distinct().size == siblings.size) {
                "Sibling node positions must be unique"
            }
        }
        val snapshotIds = nodes.mapNotNull(SequenceNodeEntity::activitySnapshotId).distinct()
        require(getExistingSnapshotIds(snapshotIds).toSet() == snapshotIds.toSet()) {
            "Every Step must reference an existing ActivitySnapshot"
        }
    }

    private fun requireValidSnapshotAggregate(aggregate: ActivitySnapshotAggregateEntity) {
        require(aggregate.settings.snapshotId == aggregate.snapshot.id) { "Snapshot settings owner mismatch" }
        require(aggregate.fields.all { it.snapshotId == aggregate.snapshot.id }) { "Snapshot Field owner mismatch" }
        val fieldIds = aggregate.fields.mapTo(hashSetOf()) { it.id }
        require(aggregate.options.all { it.snapshotFieldId in fieldIds }) { "Snapshot option owner mismatch" }
        aggregate.toDomain()
    }

    private fun pruneSnapshotIfUnreferenced(snapshotId: String) {
        if (!hasSequenceNodeReference(snapshotId) && !hasActivityExecutionReference(snapshotId)) {
            check(deleteActivitySnapshotUnchecked(snapshotId) == 1)
        }
    }
}
