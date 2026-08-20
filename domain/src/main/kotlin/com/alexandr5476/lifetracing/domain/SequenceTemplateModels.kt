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

data class SequenceStepOverrides(
    val startCountdown: Duration? = null,
    val timerZeroBehavior: TimerZeroBehavior? = null,
    val timerEndSound: Boolean? = null,
    val timerEndVibration: Boolean? = null,
    val keepScreenAwake: Boolean? = null,
) {
    val isEmpty: Boolean
        get() =
            startCountdown == null &&
                timerZeroBehavior == null &&
                timerEndSound == null &&
                timerEndVibration == null &&
                keepScreenAwake == null
}

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
    val overrides: SequenceStepOverrides = SequenceStepOverrides(),
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
        allNodes.filterIsInstance<ActivityStep>().forEach { step ->
            require(step.overrides.startCountdown?.isNegative != true) {
                "Step start countdown must not be negative"
            }
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

object SequenceTemplateCategoryOptionEvolution {
    fun requireSameIdentityCompatible(
        previousOwner: SequenceTemplateFieldId,
        previous: SequenceTemplateCategoryOption,
        updatedOwner: SequenceTemplateFieldId,
        updated: SequenceTemplateCategoryOption,
    ) {
        if (previous.id != updated.id) return
        require(previousOwner == updatedOwner) {
            "Category option owner Field is immutable for the same option identity"
        }
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
    STEP_OVERRIDES(true),
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
        val topLevelStep = nodes.filterIsInstance<ActivityStep>().singleOrNull { it.id == stepId }
        val sourceRepeat =
            nodes.filterIsInstance<SequenceRepeatBlock>().singleOrNull { repeat ->
                repeat.children.any { it.id == stepId }
            }
        require((topLevelStep != null) xor (sourceRepeat != null)) { "Unknown or non-Step node: ${stepId.value}" }
        val moved = topLevelStep ?: sourceRepeat!!.children.single { it.id == stepId }

        var result =
            if (topLevelStep != null) {
                nodes.filterNot { it.id == stepId }.reindexNodes()
            } else {
                nodes.map { node ->
                    if (node.id == sourceRepeat!!.id) {
                        sourceRepeat.copy(children = sourceRepeat.children.filterNot { it.id == stepId }.reindexSteps())
                    } else {
                        node
                    }
                }
            }

        result =
            if (destinationRepeatId == null) {
                require(position in 0..result.size) { "Destination position is out of bounds" }
                result.toMutableList().apply { add(position, moved) }.reindexNodes()
            } else {
                var found = false
                result
                    .map { node ->
                        if (node is SequenceRepeatBlock && node.id == destinationRepeatId) {
                            found = true
                            require(position in 0..node.children.size) { "Destination position is out of bounds" }
                            node.copy(
                                children =
                                    node.children
                                        .toMutableList()
                                        .apply { add(position, moved) }
                                        .reindexSteps(),
                            )
                        } else {
                            node
                        }
                    }.also { require(found) { "Unknown destination Repeat: ${destinationRepeatId.value}" } }
            }
        return result.also(SequenceTemplateValidator::requireValidNodes)
    }

    fun moveTopLevelNode(
        nodes: List<SequenceNode>,
        nodeId: SequenceNodeId,
        position: Int,
    ): List<SequenceNode> {
        val moved = requireNotNull(nodes.singleOrNull { it.id == nodeId }) { "Only top-level nodes can be reordered" }
        val remaining = nodes.filterNot { it.id == nodeId }
        require(position in 0..remaining.size) { "Destination position is out of bounds" }
        return remaining
            .toMutableList()
            .apply { add(position, moved) }
            .reindexNodes()
            .also(SequenceTemplateValidator::requireValidNodes)
    }

    private fun List<SequenceNode>.reindexNodes(): List<SequenceNode> =
        mapIndexed { position, node -> node.withPosition(position) }

    private fun List<ActivityStep>.reindexSteps(): List<ActivityStep> =
        mapIndexed { position, step -> step.copy(position = position) }

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
        require(replacement.sourceTemplateId == previous.sourceTemplateId) {
            "Local Step replacement must preserve source Template identity"
        }
        require(replacement.sourceRevision == previous.sourceRevision) {
            "Local Step replacement must preserve source revision"
        }
        require(replacement.statisticsSeriesId == previous.statisticsSeriesId) {
            "Local Step replacement must preserve Statistics Series identity"
        }
        ActivityConfigSnapshotValidator.requireValid(replacement)
        return step.copy(activitySnapshotId = replacement.id)
    }
}

data class EffectiveSequenceStepSettings(
    val startCountdown: Duration,
    val timerZeroBehavior: TimerZeroBehavior?,
    val timerEndSound: Boolean,
    val timerEndVibration: Boolean,
    val keepScreenAwake: Boolean,
)

object EffectiveSequenceStepSettingsResolver {
    fun resolve(
        step: ActivityStep,
        activitySnapshot: ActivityConfigSnapshot,
        sequenceSettings: SequenceTemplateSettings,
        isFirstStep: Boolean,
    ): EffectiveSequenceStepSettings =
        resolveOverrides(
            step.activitySnapshotId,
            step.overrides,
            activitySnapshot,
            sequenceSettings.sequenceStartCountdown,
            sequenceSettings.beforeEachStepCountdown,
            sequenceSettings.transitionSound,
            sequenceSettings.transitionVibration,
            sequenceSettings.keepScreenAwake,
            isFirstStep,
        )

    fun resolve(
        step: SequenceSnapshotActivityStep,
        activitySnapshot: ActivityConfigSnapshot,
        sequenceSettings: SequenceSnapshotSettings,
        isFirstStep: Boolean,
    ): EffectiveSequenceStepSettings =
        resolveOverrides(
            step.activitySnapshotId,
            step.overrides,
            activitySnapshot,
            sequenceSettings.sequenceStartCountdown,
            sequenceSettings.beforeEachStepCountdown,
            sequenceSettings.transitionSound,
            sequenceSettings.transitionVibration,
            sequenceSettings.keepScreenAwake,
            isFirstStep,
        )

    @Suppress("LongParameterList") // These are the inherited setting values, kept unbundled to avoid a new abstraction.
    private fun resolveOverrides(
        activitySnapshotId: ActivitySnapshotId,
        overrides: SequenceStepOverrides,
        activitySnapshot: ActivityConfigSnapshot,
        sequenceStartCountdown: Duration,
        beforeEachStepCountdown: Duration,
        transitionSound: Boolean,
        transitionVibration: Boolean,
        keepScreenAwake: Boolean,
        isFirstStep: Boolean,
    ): EffectiveSequenceStepSettings {
        require(activitySnapshotId == activitySnapshot.id) { "ActivitySnapshot must belong to the Step" }
        require(overrides.startCountdown?.isNegative != true) { "Step start countdown must not be negative" }
        require(
            activitySnapshot.timeTrackingMode == TimeTrackingMode.TIMER || overrides.timerZeroBehavior == null,
        ) {
            "Timer zero behavior override requires a TIMER Step"
        }
        return EffectiveSequenceStepSettings(
            startCountdown =
                overrides.startCountdown
                    ?: if (isFirstStep) {
                        sequenceStartCountdown
                    } else {
                        beforeEachStepCountdown
                    },
            timerZeroBehavior =
                if (activitySnapshot.timeTrackingMode == TimeTrackingMode.TIMER) {
                    overrides.timerZeroBehavior ?: activitySnapshot.settings.timerZeroBehavior
                } else {
                    null
                },
            timerEndSound = overrides.timerEndSound ?: transitionSound,
            timerEndVibration = overrides.timerEndVibration ?: transitionVibration,
            keepScreenAwake = overrides.keepScreenAwake ?: keepScreenAwake,
        )
    }
}
