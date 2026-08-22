package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionEvolution
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
    val stepOverrides: List<SequenceStepOverrideEntity> = emptyList(),
)

internal data class SequenceTemplateSemanticUpdate(
    val expectedRevision: Long,
    val template: SequenceTemplateEntity,
    val settings: SequenceTemplateSettingsEntity,
    val fields: List<SequenceTemplateFieldEntity> = emptyList(),
    val options: List<SequenceTemplateCategoryOptionEntity> = emptyList(),
    val nodes: List<SequenceNodeEntity> = emptyList(),
    val stepOverrides: List<SequenceStepOverrideEntity> = emptyList(),
    val stepSnapshotReplacements: List<SequenceStepSnapshotReplacement> = emptyList(),
)

internal data class SequenceStepSnapshotReplacement(
    val sequenceNodeId: String,
    val replacement: ActivitySnapshotAggregateEntity,
)

internal data class ActivitySnapshotModeRow(
    val id: String,
    @ColumnInfo(name = "time_tracking_mode") val timeTrackingMode: String,
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

    @Query("SELECT * FROM sequence_step_overrides WHERE sequence_node_id = :nodeId")
    abstract fun getStepOverride(nodeId: String): SequenceStepOverrideEntity?

    @Query(
        "SELECT overrides.* FROM sequence_step_overrides AS overrides " +
            "INNER JOIN sequence_nodes AS nodes ON nodes.id = overrides.sequence_node_id " +
            "WHERE nodes.sequence_template_id = :templateId ORDER BY overrides.sequence_node_id",
    )
    abstract fun getStepOverrides(templateId: String): List<SequenceStepOverrideEntity>

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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertStepOverridesUnchecked(overrides: List<SequenceStepOverrideEntity>)

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun updateSettingsUnchecked(settings: SequenceTemplateSettingsEntity): Int

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun updateUserStateUnchecked(userState: SequenceTemplateUserStateEntity): Int

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun updateFieldsUnchecked(fields: List<SequenceTemplateFieldEntity>): Int

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun updateOptionsUnchecked(options: List<SequenceTemplateCategoryOptionEntity>): Int

    @Query(
        "UPDATE sequence_templates SET name = :name, short_comment = :shortComment, " +
            "no_live_time_accounting = :noLiveTimeAccounting, revision = :newRevision, " +
            "updated_at_ms = :updatedAtMs WHERE id = :templateId AND revision = :expectedRevision",
    )
    @Suppress("LongParameterList") // Room query parameters intentionally mirror the explicit persisted columns.
    protected abstract fun updateSemanticTemplateUnchecked(
        templateId: String,
        expectedRevision: Long,
        newRevision: Long,
        name: String,
        shortComment: String?,
        noLiveTimeAccounting: String,
        updatedAtMs: Long,
    ): Int

    @Query("DELETE FROM sequence_nodes WHERE sequence_template_id = :templateId")
    protected abstract fun deleteNodesUnchecked(templateId: String): Int

    @Query("DELETE FROM sequence_template_tags WHERE sequence_template_id = :templateId")
    protected abstract fun deleteTagLinksUnchecked(templateId: String): Int

    @Query("SELECT id FROM activity_snapshots WHERE id IN (:ids)")
    protected abstract fun getExistingSnapshotIds(ids: List<String>): List<String>

    @Query("SELECT id, time_tracking_mode FROM activity_snapshots WHERE id IN (:ids)")
    protected abstract fun getSnapshotModes(ids: List<String>): List<ActivitySnapshotModeRow>

    @Query("SELECT * FROM activity_snapshots WHERE id = :id")
    protected abstract fun getActivitySnapshot(id: String): ActivitySnapshotEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM sequence_nodes WHERE activity_snapshot_id = :snapshotId LIMIT 1)")
    protected abstract fun hasSequenceNodeReference(snapshotId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM activity_executions WHERE snapshot_id = :snapshotId LIMIT 1)")
    protected abstract fun hasActivityExecutionReference(snapshotId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sequence_snapshot_nodes WHERE activity_snapshot_id = :snapshotId LIMIT 1)")
    protected abstract fun hasSequenceSnapshotNodeReference(snapshotId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sequence_occurrences WHERE activity_snapshot_id = :snapshotId LIMIT 1)")
    protected abstract fun hasSequenceOccurrenceReference(snapshotId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM plan_entries WHERE activity_snapshot_id = :snapshotId LIMIT 1)")
    protected abstract fun hasPlanReference(snapshotId: String): Boolean

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

    @Query(
        "UPDATE sequence_templates SET statistics_series_id = :newSeriesId, revision = revision + 1, " +
            "updated_at_ms = :updatedAtMs WHERE id = :id AND statistics_series_id = :oldSeriesId " +
            "AND revision = :expectedRevision AND deleted_at_ms IS NULL",
    )
    abstract fun startNewStatisticsSeries(
        id: String,
        oldSeriesId: String,
        expectedRevision: Long,
        newSeriesId: String,
        updatedAtMs: Long,
    ): Int

    @Query("UPDATE statistics_series SET display_name = :displayName WHERE id = :seriesId")
    protected abstract fun updateSeriesDisplayName(
        seriesId: String,
        displayName: String,
    ): Int

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
            stepOverrides = getStepOverrides(id),
        ).also { aggregate ->
            requireValidStepOverrides(aggregate.nodes, aggregate.stepOverrides)
            aggregate.toDomain()
        }
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
        val overrides = aggregate.stepOverrides.filterNot { it.isEmpty() }
        if (overrides.isNotEmpty()) insertStepOverridesUnchecked(overrides)
    }

    @Transaction
    open fun updateSemanticAggregate(update: SequenceTemplateSemanticUpdate) {
        val current =
            requireNotNull(getAggregate(update.template.id)) { "Unknown SequenceTemplate: ${update.template.id}" }
        val canonicalOverrides = update.stepOverrides.filterNot { it.isEmpty() }
        val proposed =
            current.copy(
                template = update.template,
                settings = update.settings,
                fields = update.fields,
                options = update.options,
                nodes = update.nodes,
                stepOverrides = canonicalOverrides,
            )
        requireValidSemanticUpdateHeader(current, update)
        requireValidStepSnapshotReplacements(current, proposed.nodes, update.stepSnapshotReplacements)
        update.stepSnapshotReplacements.forEach { insertSnapshotAggregate(it.replacement) }
        requireValidSemanticUpdateBody(current, proposed)
        updateTemplateWithRevisionCheck(update)
        if (update.template.name != current.template.name) {
            check(updateSeriesDisplayName(current.template.statisticsSeriesId, update.template.name) == 1)
        }
        check(updateSettingsUnchecked(update.settings) == 1)
        persistFieldsAndOptions(current, update)
        replaceStructureAndPrune(current, update.nodes, canonicalOverrides)
    }

    private fun requireValidSemanticUpdateHeader(
        current: SequenceTemplateAggregateEntity,
        update: SequenceTemplateSemanticUpdate,
    ) {
        require(current.template.revision == update.expectedRevision) {
            "SequenceTemplate revision changed concurrently"
        }
        require(update.template.revision == update.expectedRevision + 1) {
            "A semantic aggregate update must increment revision exactly once"
        }
        require(
            update.template.id == current.template.id &&
                update.template.statisticsSeriesId == current.template.statisticsSeriesId &&
                update.template.createdAtMs == current.template.createdAtMs &&
                update.template.deletedAtMs == current.template.deletedAtMs &&
                update.template.folderId == current.template.folderId,
        ) { "Semantic commit cannot change Sequence identity, ownership, lifecycle, or Library metadata" }
    }

    private fun requireValidSemanticUpdateBody(
        current: SequenceTemplateAggregateEntity,
        proposed: SequenceTemplateAggregateEntity,
    ) {
        requireValidAggregate(proposed)
        require(current.fields.map { it.id }.all(proposed.fields.map { it.id }.toSet()::contains)) {
            "Existing Field identities must be retained and archived instead of removed"
        }
        require(current.options.map { it.id }.all(proposed.options.map { it.id }.toSet()::contains)) {
            "Existing Category option identities must be retained and archived instead of removed"
        }
        requireCompatibleFieldAndOptionEvolution(current, proposed)
    }

    private fun requireValidStepSnapshotReplacements(
        current: SequenceTemplateAggregateEntity,
        proposedNodes: List<SequenceNodeEntity>,
        replacements: List<SequenceStepSnapshotReplacement>,
    ) {
        require(replacements.map { it.sequenceNodeId }.distinct().size == replacements.size) {
            "At most one snapshot replacement is allowed per Step"
        }
        require(replacements.map { it.replacement.snapshot.id }.distinct().size == replacements.size) {
            "Each staged replacement must have a unique snapshot identity"
        }
        val currentById = current.nodes.associateBy(SequenceNodeEntity::id)
        val proposedById = proposedNodes.associateBy(SequenceNodeEntity::id)
        val replacementsByNode = replacements.associateBy(SequenceStepSnapshotReplacement::sequenceNodeId)
        replacements.forEach { descriptor ->
            val previousNode =
                requireNotNull(currentById[descriptor.sequenceNodeId]) {
                    "Snapshot replacement owner must be an existing Step"
                }
            require(previousNode.nodeType == "STEP") { "Snapshot replacement owner must be an existing Step" }
            val proposedNode =
                requireNotNull(proposedById[descriptor.sequenceNodeId]) {
                    "Snapshot replacement cannot target a removed Step"
                }
            require(proposedNode.nodeType == "STEP") { "Snapshot replacement cannot target a Repeat" }
            val oldSnapshotId = requireNotNull(previousNode.activitySnapshotId)
            val replacement = descriptor.replacement
            require(replacement.snapshot.id != oldSnapshotId) { "Snapshot replacement requires a new identity" }
            require(proposedNode.activitySnapshotId == replacement.snapshot.id) {
                "Proposed Step must reference its staged replacement"
            }
            requireValidLocalSnapshotReplacement(oldSnapshotId, replacement)
        }
        current.nodes.filter { it.nodeType == "STEP" }.forEach { previousNode ->
            val proposedNode = proposedById[previousNode.id]
            if (
                proposedNode?.nodeType == "STEP" &&
                proposedNode.activitySnapshotId != previousNode.activitySnapshotId
            ) {
                require(previousNode.id in replacementsByNode) {
                    "An existing Step snapshot may change only through an explicit local replacement"
                }
            }
        }
    }

    private fun requireValidLocalSnapshotReplacement(
        oldSnapshotId: String,
        replacement: ActivitySnapshotAggregateEntity,
    ) {
        val previous =
            requireNotNull(getActivitySnapshot(oldSnapshotId)) { "Step snapshot is missing: $oldSnapshotId" }
        requireValidSnapshotAggregate(replacement)
        require(replacement.snapshot.locallyModified) { "Local Step replacement must be locally modified" }
        require(replacement.snapshot.sourceTemplateId == previous.sourceTemplateId) {
            "Local Step replacement must preserve source Template identity"
        }
        require(replacement.snapshot.sourceRevision == previous.sourceRevision) {
            "Local Step replacement must preserve source revision"
        }
        require(replacement.snapshot.statisticsSeriesId == previous.statisticsSeriesId) {
            "Local Step replacement must preserve Statistics Series identity"
        }
    }

    private fun insertSnapshotAggregate(aggregate: ActivitySnapshotAggregateEntity) {
        insertActivitySnapshotUnchecked(aggregate.snapshot)
        insertActivitySnapshotSettingsUnchecked(aggregate.settings)
        if (aggregate.fields.isNotEmpty()) insertActivitySnapshotFieldsUnchecked(aggregate.fields)
        if (aggregate.options.isNotEmpty()) insertActivitySnapshotOptionsUnchecked(aggregate.options)
    }

    private fun requireCompatibleFieldAndOptionEvolution(
        current: SequenceTemplateAggregateEntity,
        proposed: SequenceTemplateAggregateEntity,
    ) {
        val currentDomain = current.toDomain()
        val proposedDomain = proposed.toDomain()
        val previousDomainFields = currentDomain.fields.associateBy { it.id }
        proposedDomain.fields.forEach { updated ->
            previousDomainFields[updated.id]?.let { previous ->
                SequenceTemplateFieldEvolution.requireSameIdentityCompatible(previous, updated)
            }
        }
        val previousOptions =
            currentDomain.fields
                .flatMap { field ->
                    field.categoryOptions.map { option ->
                        option.id to
                            (field.id to option)
                    }
                }.toMap()
        proposedDomain.fields.forEach { field ->
            field.categoryOptions.forEach { option ->
                previousOptions[option.id]?.let { (previousOwner, previous) ->
                    SequenceTemplateCategoryOptionEvolution.requireSameIdentityCompatible(
                        previousOwner,
                        previous,
                        field.id,
                        option,
                    )
                }
            }
        }
    }

    private fun updateTemplateWithRevisionCheck(update: SequenceTemplateSemanticUpdate) {
        check(
            updateSemanticTemplateUnchecked(
                update.template.id,
                update.expectedRevision,
                update.template.revision,
                update.template.name,
                update.template.shortComment,
                update.template.noLiveTimeAccounting,
                update.template.updatedAtMs,
            ) == 1,
        ) { "SequenceTemplate revision changed concurrently" }
    }

    private fun persistFieldsAndOptions(
        current: SequenceTemplateAggregateEntity,
        update: SequenceTemplateSemanticUpdate,
    ) {
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

    private fun replaceStructureAndPrune(
        current: SequenceTemplateAggregateEntity,
        nodes: List<SequenceNodeEntity>,
        overrides: List<SequenceStepOverrideEntity>,
    ) {
        val oldSnapshotIds = current.nodes.mapNotNull(SequenceNodeEntity::activitySnapshotId).distinct()
        deleteNodesUnchecked(current.template.id)
        if (nodes.isNotEmpty()) insertNodesUnchecked(nodes.sortedBy { it.parentRepeatNodeId != null })
        if (overrides.isNotEmpty()) insertStepOverridesUnchecked(overrides)
        oldSnapshotIds.forEach(::pruneSnapshotIfUnreferenced)
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
        val current = requireNotNull(getAggregate(templateId)) { "Unknown SequenceTemplate: $templateId" }
        val retainedNodeIds = nodes.mapTo(hashSetOf()) { it.id }
        updateSemanticAggregate(
            SequenceTemplateSemanticUpdate(
                expectedRevision = expectedRevision,
                template = current.template.copy(revision = expectedRevision + 1, updatedAtMs = updatedAtMs),
                settings = current.settings,
                fields = current.fields,
                options = current.options,
                nodes = nodes,
                stepOverrides = current.stepOverrides.filter { it.sequenceNodeId in retainedNodeIds },
            ),
        )
    }

    @Transaction
    open fun removeNode(
        templateId: String,
        nodeId: String,
        expectedRevision: Long,
        updatedAtMs: Long,
    ) {
        val current = requireNotNull(getAggregate(templateId)) { "Unknown SequenceTemplate: $templateId" }
        require(current.nodes.any { it.id == nodeId }) { "Unknown Sequence node: $nodeId" }
        replaceNodeStructure(
            templateId,
            current.nodes.filterNot { it.id == nodeId || it.parentRepeatNodeId == nodeId },
            expectedRevision,
            updatedAtMs,
        )
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
        val current =
            requireNotNull(getAggregate(node.sequenceTemplateId)) {
                "Unknown SequenceTemplate: ${node.sequenceTemplateId}"
            }
        updateSemanticAggregate(
            SequenceTemplateSemanticUpdate(
                expectedRevision = expectedRevision,
                template = current.template.copy(revision = expectedRevision + 1, updatedAtMs = updatedAtMs),
                settings = current.settings,
                fields = current.fields,
                options = current.options,
                nodes =
                    current.nodes.map { currentNode ->
                        if (currentNode.id == nodeId) {
                            currentNode.copy(activitySnapshotId = replacement.snapshot.id)
                        } else {
                            currentNode
                        }
                    },
                stepOverrides = current.stepOverrides,
                stepSnapshotReplacements = listOf(SequenceStepSnapshotReplacement(nodeId, replacement)),
            ),
        )
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
        requireValidStepOverrides(aggregate.nodes, aggregate.stepOverrides)
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

    private fun requireValidStepOverrides(
        nodes: List<SequenceNodeEntity>,
        overrides: List<SequenceStepOverrideEntity>,
    ) {
        val nodesById = nodes.associateBy(SequenceNodeEntity::id)
        val modesBySnapshot =
            getSnapshotModes(nodes.mapNotNull(SequenceNodeEntity::activitySnapshotId).distinct())
                .associateBy(ActivitySnapshotModeRow::id)
        overrides.forEach { override ->
            val node = requireNotNull(nodesById[override.sequenceNodeId]) { "Step override owner must exist" }
            require(node.nodeType == "STEP") { "Only a Step can own execution-setting overrides" }
            require(override.startCountdownMs == null || override.startCountdownMs >= 0) {
                "Step start countdown must not be negative"
            }
            if (override.timerZeroBehavior != null) {
                require(override.timerZeroBehavior == "FINISH" || override.timerZeroBehavior == "OVERTIME") {
                    "Unknown Step timer zero behavior code: ${override.timerZeroBehavior}"
                }
                require(modesBySnapshot[node.activitySnapshotId]?.timeTrackingMode == "TIMER") {
                    "Timer zero behavior override requires a TIMER Step"
                }
            }
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
        val isReferenced =
            hasSequenceNodeReference(snapshotId) ||
                hasSequenceSnapshotNodeReference(snapshotId) ||
                hasActivityExecutionReference(snapshotId) ||
                hasSequenceOccurrenceReference(snapshotId) ||
                hasPlanReference(snapshotId)
        if (!isReferenced) check(deleteActivitySnapshotUnchecked(snapshotId) == 1)
    }

    private fun SequenceStepOverrideEntity.isEmpty(): Boolean =
        startCountdownMs == null &&
            timerZeroBehavior == null &&
            timerEndSound == null &&
            timerEndVibration == null &&
            keepScreenAwake == null
}
