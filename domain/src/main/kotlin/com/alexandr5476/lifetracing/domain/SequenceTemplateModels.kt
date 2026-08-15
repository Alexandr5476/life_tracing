package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant

@JvmInline
value class SequenceTemplateId(
    val value: String,
)

@JvmInline
value class SequenceTemplateFieldId(
    val value: String,
)

@JvmInline
value class SequenceTemplateCategoryOptionId(
    val value: String,
)

@JvmInline
value class SequenceNodeId(
    val value: String,
)

enum class NoLiveTimeAccounting {
    ACTIVE,
    PAUSE,
}

data class SequenceTemplateSettings(
    val autoAdvance: Boolean = true,
    val sequenceStartCountdown: Duration = Duration.ZERO,
    val beforeEachStepCountdown: Duration = Duration.ZERO,
    val transitionSound: Boolean = true,
    val transitionVibration: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val confirmJump: Boolean = true,
    val confirmEarlyEnd: Boolean = true,
)

data class SequenceTemplateUserState(
    val pinnedRank: Int? = null,
    val lastUsedAt: Instant? = null,
)

data class SequenceTemplateCategoryOption(
    val id: SequenceTemplateCategoryOptionId,
    val position: Int,
    val label: String,
    val isArchived: Boolean = false,
)

data class SequenceTemplateField(
    val id: SequenceTemplateFieldId,
    val position: Int,
    val name: String,
    val type: CustomFieldType,
    val unit: String? = null,
    val displayPrecision: Int? = null,
    val defaultNumberScaled: Long? = null,
    val defaultCategoryOptionId: SequenceTemplateCategoryOptionId? = null,
    val defaultText: String? = null,
    val isMainValue: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val categoryOptions: List<SequenceTemplateCategoryOption> = emptyList(),
)

sealed interface SequenceNode {
    val id: SequenceNodeId
    val position: Int
}

data class ActivityStep(
    override val id: SequenceNodeId,
    override val position: Int,
    val activitySnapshotId: ActivitySnapshotId,
) : SequenceNode

data class SequenceRepeatBlock(
    override val id: SequenceNodeId,
    override val position: Int,
    val repeatCount: Int,
    val children: List<ActivityStep>,
) : SequenceNode

data class SequenceTemplate(
    val id: SequenceTemplateId,
    val name: String,
    val shortComment: String?,
    val statisticsSeriesId: StatisticsSeriesId,
    val revision: Long = SequenceTemplateRevisionPolicy.INITIAL_REVISION,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val folderId: FolderId? = null,
    val noLiveTimeAccounting: NoLiveTimeAccounting = NoLiveTimeAccounting.ACTIVE,
    val settings: SequenceTemplateSettings = SequenceTemplateSettings(),
    val userState: SequenceTemplateUserState = SequenceTemplateUserState(),
    val fields: List<SequenceTemplateField> = emptyList(),
    val tagIds: Set<TagId> = emptySet(),
    val nodes: List<SequenceNode> = emptyList(),
)

object SequenceTemplateValidator {
    fun requireValid(template: SequenceTemplate) {
        require(template.revision >= SequenceTemplateRevisionPolicy.INITIAL_REVISION) {
            "SequenceTemplate revision must be at least 1"
        }
        require(!template.settings.sequenceStartCountdown.isNegative) {
            "Sequence start countdown must not be negative"
        }
        require(!template.settings.beforeEachStepCountdown.isNegative) {
            "Before-step countdown must not be negative"
        }
        template.fields.forEach(::requireValidField)
        require(template.fields.count { it.deletedAt == null && it.isMainValue } <= 1) {
            "SequenceTemplate may have at most one active Main Value"
        }
        requireValidNodes(template.nodes)
    }

    fun requireValidField(field: SequenceTemplateField) {
        when (field.type) {
            CustomFieldType.NUMBER -> {
                require(field.defaultCategoryOptionId == null && field.defaultText == null) {
                    "NUMBER field cannot contain Category or Text defaults"
                }
                require(field.categoryOptions.isEmpty()) { "NUMBER field cannot contain Category options" }
            }
            CustomFieldType.CATEGORY -> {
                require(
                    field.unit == null &&
                        field.displayPrecision == null &&
                        field.defaultNumberScaled == null &&
                        field.defaultText == null,
                ) { "CATEGORY field cannot contain Number or Text metadata" }
                field.defaultCategoryOptionId?.let { defaultId ->
                    require(field.categoryOptions.any { it.id == defaultId && !it.isArchived }) {
                        "Category default must belong to an active option of the same field"
                    }
                }
            }
            CustomFieldType.TEXT -> {
                require(
                    field.unit == null &&
                        field.displayPrecision == null &&
                        field.defaultNumberScaled == null &&
                        field.defaultCategoryOptionId == null,
                ) { "TEXT field cannot contain Number or Category metadata" }
                require(field.categoryOptions.isEmpty()) { "TEXT field cannot contain Category options" }
            }
        }
        require(!field.isMainValue || field.type == CustomFieldType.NUMBER) {
            "Main Value must be a NUMBER field"
        }
    }

    fun requireValidNodes(nodes: List<SequenceNode>) {
        val allNodes = nodes + nodes.filterIsInstance<SequenceRepeatBlock>().flatMap(SequenceRepeatBlock::children)
        require(allNodes.map(SequenceNode::id).toSet().size == allNodes.size) {
            "Sequence node identities must be unique"
        }
        requireOrderedUniquePositions(nodes, "top-level")
        nodes.filterIsInstance<SequenceRepeatBlock>().forEach { repeat ->
            require(repeat.repeatCount > 0) { "Repeat count must be positive" }
            requireOrderedUniquePositions(repeat.children, "Repeat ${repeat.id.value}")
        }
    }

    private fun requireOrderedUniquePositions(
        nodes: List<SequenceNode>,
        container: String,
    ) {
        val positions = nodes.map(SequenceNode::position)
        require(positions == positions.sorted() && positions.distinct().size == positions.size) {
            "$container node positions must be ordered and unique"
        }
    }
}

object SequenceTemplateFieldEvolution {
    fun requireSameIdentityCompatible(
        previous: SequenceTemplateField,
        updated: SequenceTemplateField,
    ) {
        if (previous.id != updated.id) return
        require(previous.type == updated.type) { "Field type is immutable for the same field identity" }
        require(previous.unit == updated.unit) { "Field unit is immutable for the same field identity" }
    }
}

enum class SequenceTemplateEdit(
    val isSemantic: Boolean,
) {
    NAME(true),
    SHORT_COMMENT(true),
    NO_LIVE_TIME_ACCOUNTING(true),
    SETTINGS(true),
    FIELD_SCHEMA(true),
    STRUCTURE(true),
    STEP_SNAPSHOT(true),
    FIELD_DISPLAY_NAME(false),
    CATEGORY_OPTION_DISPLAY_NAME(false),
    FOLDER(false),
    TAGS(false),
    USER_STATE(false),
}

object SequenceTemplateRevisionPolicy {
    const val INITIAL_REVISION = 1L

    fun after(
        currentRevision: Long,
        vararg edits: SequenceTemplateEdit,
    ): Long {
        require(currentRevision >= INITIAL_REVISION) { "Current revision must be at least 1" }
        return if (edits.any(SequenceTemplateEdit::isSemantic)) currentRevision + 1 else currentRevision
    }
}

object SequenceTemplateLifecycle {
    fun archive(
        template: SequenceTemplate,
        archivedAt: Instant,
    ): SequenceTemplate = template.copy(deletedAt = archivedAt)

    fun restore(template: SequenceTemplate): SequenceTemplate = template.copy(deletedAt = null)
}

object SequenceStructureEditor {
    fun reorderTopLevel(
        nodes: List<SequenceNode>,
        orderedIds: List<SequenceNodeId>,
    ): List<SequenceNode> {
        require(orderedIds.size == nodes.size && orderedIds.toSet() == nodes.mapTo(hashSetOf()) { it.id }) {
            "Top-level reorder must contain every node exactly once"
        }
        return orderedIds
            .mapIndexed { position, id -> nodes.single { it.id == id }.withPosition(position) }
            .also(SequenceTemplateValidator::requireValidNodes)
    }

    fun reorderRepeatChildren(
        nodes: List<SequenceNode>,
        repeatId: SequenceNodeId,
        orderedStepIds: List<SequenceNodeId>,
    ): List<SequenceNode> {
        val repeat = nodes.filterIsInstance<SequenceRepeatBlock>().singleOrNull { it.id == repeatId }
        requireNotNull(repeat) { "Unknown Repeat: ${repeatId.value}" }
        require(
            orderedStepIds.size == repeat.children.size &&
                orderedStepIds.toSet() == repeat.children.mapTo(hashSetOf()) { it.id },
        ) { "Repeat reorder must contain every child Step exactly once" }
        return nodes
            .map { node ->
                if (node.id == repeatId) {
                    repeat.copy(
                        children =
                            orderedStepIds.mapIndexed { position, id ->
                                repeat.children.single { it.id == id }.copy(position = position)
                            },
                    )
                } else {
                    node
                }
            }.also(SequenceTemplateValidator::requireValidNodes)
    }

    fun moveStep(
        nodes: List<SequenceNode>,
        stepId: SequenceNodeId,
        destinationRepeatId: SequenceNodeId?,
        position: Int,
    ): List<SequenceNode> {
        val matches =
            nodes.filterIsInstance<ActivityStep>().filter { it.id == stepId } +
                nodes.filterIsInstance<SequenceRepeatBlock>().flatMap { it.children }.filter { it.id == stepId }
        val moved =
            requireNotNull(matches.singleOrNull()) {
                "Unknown or non-Step node: ${stepId.value}"
            }.copy(position = position)
        var result =
            nodes.mapNotNull { node ->
                when (node) {
                    is ActivityStep -> node.takeUnless { it.id == stepId }
                    is SequenceRepeatBlock -> node.copy(children = node.children.filterNot { it.id == stepId })
                }
            }
        result =
            if (destinationRepeatId == null) {
                result + moved
            } else {
                var found = false
                result
                    .map { node ->
                        if (node is SequenceRepeatBlock && node.id == destinationRepeatId) {
                            found = true
                            node.copy(children = node.children + moved)
                        } else {
                            node
                        }
                    }.also { require(found) { "Unknown destination Repeat: ${destinationRepeatId.value}" } }
            }
        return result.sortedStructure().also(SequenceTemplateValidator::requireValidNodes)
    }

    fun moveTopLevelNode(
        nodes: List<SequenceNode>,
        nodeId: SequenceNodeId,
        position: Int,
    ): List<SequenceNode> {
        require(nodes.any { it.id == nodeId }) { "Only top-level nodes can be reordered" }
        val moved =
            nodes
                .map { node ->
                    if (node.id != nodeId) {
                        node
                    } else {
                        when (node) {
                            is ActivityStep -> node.copy(position = position)
                            is SequenceRepeatBlock -> node.copy(position = position)
                        }
                    }
                }.sortedStructure()
        return moved.also(SequenceTemplateValidator::requireValidNodes)
    }

    private fun List<SequenceNode>.sortedStructure(): List<SequenceNode> =
        map { node ->
            if (node is SequenceRepeatBlock) {
                node.copy(
                    children = node.children.sortedBy(ActivityStep::position),
                )
            } else {
                node
            }
        }.sortedBy(SequenceNode::position)

    private fun SequenceNode.withPosition(position: Int): SequenceNode =
        when (this) {
            is ActivityStep -> copy(position = position)
            is SequenceRepeatBlock -> copy(position = position)
        }
}

object ActivityStepSnapshotPolicy {
    fun isSourceDiverged(
        snapshot: ActivityConfigSnapshot,
        currentSourceRevision: Long?,
    ): Boolean =
        snapshot.sourceTemplateId != null &&
            currentSourceRevision != null &&
            snapshot.sourceRevision != currentSourceRevision

    fun replaceLocally(
        step: ActivityStep,
        previous: ActivityConfigSnapshot,
        replacement: ActivityConfigSnapshot,
    ): ActivityStep {
        require(step.activitySnapshotId == previous.id) { "Previous snapshot must belong to the Step" }
        require(replacement.id != previous.id) { "Snapshot replacement requires a new identity" }
        require(replacement.locallyModified) { "Local Step replacement must be locally modified" }
        ActivityConfigSnapshotValidator.requireValid(replacement)
        return step.copy(activitySnapshotId = replacement.id)
    }
}
