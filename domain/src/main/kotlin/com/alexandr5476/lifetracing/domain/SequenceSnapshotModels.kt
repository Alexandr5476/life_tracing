@file:Suppress("CyclomaticComplexMethod", "LongMethod") // Explicit snapshot copying keeps identity remapping visible.

package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant

@JvmInline
value class SequenceSnapshotId(
    val value: String,
)

@JvmInline
value class SequenceSnapshotFieldId(
    val value: String,
)

@JvmInline
value class SequenceSnapshotCategoryOptionId(
    val value: String,
)

@JvmInline
value class SequenceSnapshotNodeId(
    val value: String,
)

data class SequenceSnapshotSettings(
    val autoAdvance: Boolean,
    val sequenceStartCountdown: Duration,
    val beforeEachStepCountdown: Duration,
    val transitionSound: Boolean,
    val transitionVibration: Boolean,
    val keepScreenAwake: Boolean,
    val confirmJump: Boolean,
    val confirmEarlyEnd: Boolean,
    val noLiveTimeAccounting: NoLiveTimeAccounting,
)

data class SequenceSnapshotCategoryOption(
    val id: SequenceSnapshotCategoryOptionId,
    val sourceOptionId: SequenceTemplateCategoryOptionId?,
    val position: Int,
    val labelAtCreation: String,
    val localLabelOverride: String? = null,
)

data class SequenceSnapshotField(
    val id: SequenceSnapshotFieldId,
    val sourceFieldId: SequenceTemplateFieldId?,
    val position: Int,
    val nameAtCreation: String,
    val localNameOverride: String? = null,
    val type: CustomFieldType,
    val unit: String? = null,
    val displayPrecision: Int? = null,
    val defaultNumberScaled: Long? = null,
    val defaultCategoryOptionId: SequenceSnapshotCategoryOptionId? = null,
    val defaultText: String? = null,
    val isMainValue: Boolean = false,
    val categoryOptions: List<SequenceSnapshotCategoryOption> = emptyList(),
)

sealed interface SequenceSnapshotNode {
    val id: SequenceSnapshotNodeId
    val position: Int
}

data class SequenceSnapshotActivityStep(
    override val id: SequenceSnapshotNodeId,
    override val position: Int,
    val activitySnapshotId: ActivitySnapshotId,
    val overrides: SequenceStepOverrides = SequenceStepOverrides(),
) : SequenceSnapshotNode

data class SequenceSnapshotRepeatBlock(
    override val id: SequenceSnapshotNodeId,
    override val position: Int,
    val repeatCount: Int,
    val children: List<SequenceSnapshotActivityStep>,
) : SequenceSnapshotNode

data class SequenceConfigSnapshot(
    val id: SequenceSnapshotId,
    val name: String,
    val shortComment: String?,
    val sourceTemplateId: SequenceTemplateId?,
    val sourceRevision: Long?,
    val statisticsSeriesId: StatisticsSeriesId?,
    val createdAt: Instant,
    val settings: SequenceSnapshotSettings,
    val fields: List<SequenceSnapshotField> = emptyList(),
    val nodes: List<SequenceSnapshotNode> = emptyList(),
)

object SequenceConfigSnapshotValidator {
    fun requireValid(
        snapshot: SequenceConfigSnapshot,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot> = emptyMap(),
    ) {
        if (snapshot.sourceTemplateId != null) {
            require(snapshot.sourceRevision != null && snapshot.sourceRevision >= 1) {
                "Source-linked snapshot revision must be at least 1"
            }
            require(snapshot.statisticsSeriesId != null) {
                "Source-linked snapshot must retain its Statistics Series"
            }
        }
        require(
            !snapshot.settings.sequenceStartCountdown.isNegative,
        ) { "Sequence start countdown must not be negative" }
        require(!snapshot.settings.beforeEachStepCountdown.isNegative) { "Before-step countdown must not be negative" }
        require(
            snapshot.fields
                .map(SequenceSnapshotField::id)
                .distinct()
                .size == snapshot.fields.size,
        ) {
            "Sequence snapshot field identities must be unique"
        }
        snapshot.fields.forEach(::requireValidField)
        require(snapshot.fields.count { it.isMainValue } <= 1) { "Sequence snapshot may have at most one Main Value" }
        requireValidNodes(snapshot.nodes, activitySnapshots)
    }

    fun requireValidField(field: SequenceSnapshotField) {
        require(
            field.categoryOptions
                .map(SequenceSnapshotCategoryOption::id)
                .distinct()
                .size == field.categoryOptions.size,
        ) {
            "Sequence snapshot option identities must be unique"
        }
        when (field.type) {
            CustomFieldType.NUMBER ->
                require(
                    field.defaultCategoryOptionId == null &&
                        field.defaultText == null &&
                        field.categoryOptions.isEmpty(),
                ) { "NUMBER snapshot field has incompatible payload" }
            CustomFieldType.CATEGORY -> {
                require(
                    field.unit == null &&
                        field.displayPrecision == null &&
                        field.defaultNumberScaled == null &&
                        field.defaultText == null,
                ) { "CATEGORY snapshot field has incompatible payload" }
                field.defaultCategoryOptionId?.let { id ->
                    require(
                        field.categoryOptions.any { it.id == id },
                    ) { "Category default must belong to the same snapshot field" }
                }
            }
            CustomFieldType.TEXT ->
                require(
                    field.unit == null &&
                        field.displayPrecision == null &&
                        field.defaultNumberScaled == null &&
                        field.defaultCategoryOptionId == null &&
                        field.categoryOptions.isEmpty(),
                ) { "TEXT snapshot field has incompatible payload" }
        }
        require(
            !field.isMainValue || field.type == CustomFieldType.NUMBER,
        ) { "Main Value must be a NUMBER snapshot field" }
    }

    private fun requireValidNodes(
        nodes: List<SequenceSnapshotNode>,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ) {
        val all =
            nodes + nodes.filterIsInstance<SequenceSnapshotRepeatBlock>().flatMap(SequenceSnapshotRepeatBlock::children)
        require(all.map(SequenceSnapshotNode::id).distinct().size == all.size) {
            "Sequence snapshot node identities must be unique"
        }
        requireUniquePositions(nodes, "top-level")
        nodes.filterIsInstance<SequenceSnapshotRepeatBlock>().forEach { repeat ->
            require(repeat.repeatCount > 0) { "Repeat count must be positive" }
            requireUniquePositions(repeat.children, "Repeat ${repeat.id.value}")
        }
        all.filterIsInstance<SequenceSnapshotActivityStep>().forEach { step ->
            require(step.overrides.startCountdown?.isNegative != true) { "Step start countdown must not be negative" }
            if (step.overrides.timerZeroBehavior != null && activitySnapshots.isNotEmpty()) {
                require(activitySnapshots[step.activitySnapshotId]?.timeTrackingMode == TimeTrackingMode.TIMER) {
                    "Timer zero behavior override requires a TIMER Step"
                }
            }
        }
    }

    private fun requireUniquePositions(
        nodes: List<SequenceSnapshotNode>,
        label: String,
    ) {
        require(nodes.map(SequenceSnapshotNode::position).distinct().size == nodes.size) {
            "$label node positions must be unique"
        }
    }
}

class SequenceSnapshotFactory(
    private val nextSnapshotId: () -> SequenceSnapshotId,
    private val nextFieldId: () -> SequenceSnapshotFieldId,
    private val nextOptionId: () -> SequenceSnapshotCategoryOptionId,
    private val nextNodeId: () -> SequenceSnapshotNodeId,
) {
    fun fromTemplate(
        template: SequenceTemplate,
        createdAt: Instant,
    ): SequenceConfigSnapshot {
        SequenceTemplateValidator.requireValid(template)
        val fields =
            template.fields.filter { it.deletedAt == null }.map { field ->
                val options =
                    field.categoryOptions.filterNot { it.isArchived }.map { option ->
                        SequenceSnapshotCategoryOption(nextOptionId(), option.id, option.position, option.label)
                    }
                SequenceSnapshotField(
                    id = nextFieldId(),
                    sourceFieldId = field.id,
                    position = field.position,
                    nameAtCreation = field.name,
                    type = field.type,
                    unit = field.unit,
                    displayPrecision = field.displayPrecision,
                    defaultNumberScaled = field.defaultNumberScaled,
                    defaultCategoryOptionId =
                        field.defaultCategoryOptionId?.let { source ->
                            options
                                .single {
                                    it.sourceOptionId ==
                                        source
                                }.id
                        },
                    defaultText = field.defaultText,
                    isMainValue = field.isMainValue,
                    categoryOptions = options,
                )
            }
        val nodes =
            template.nodes.map { node ->
                when (node) {
                    is ActivityStep ->
                        SequenceSnapshotActivityStep(
                            nextNodeId(),
                            node.position,
                            node.activitySnapshotId,
                            node.overrides,
                        )
                    is SequenceRepeatBlock -> {
                        val repeatId = nextNodeId()
                        SequenceSnapshotRepeatBlock(
                            repeatId,
                            node.position,
                            node.repeatCount,
                            node.children.map { step ->
                                SequenceSnapshotActivityStep(
                                    nextNodeId(),
                                    step.position,
                                    step.activitySnapshotId,
                                    step.overrides,
                                )
                            },
                        )
                    }
                }
            }
        return SequenceConfigSnapshot(
            nextSnapshotId(),
            template.name,
            template.shortComment,
            template.id,
            template.revision,
            template.statisticsSeriesId,
            createdAt,
            SequenceSnapshotSettings(
                template.settings.autoAdvance,
                template.settings.sequenceStartCountdown,
                template.settings.beforeEachStepCountdown,
                template.settings.transitionSound,
                template.settings.transitionVibration,
                template.settings.keepScreenAwake,
                template.settings.confirmJump,
                template.settings.confirmEarlyEnd,
                template.noLiveTimeAccounting,
            ),
            fields,
            nodes,
        ).also(SequenceConfigSnapshotValidator::requireValid)
    }
}

object SequenceSnapshotDisplayResolver {
    fun fieldName(
        field: SequenceSnapshotField,
        currentSourceName: String?,
    ): String = field.localNameOverride ?: currentSourceName ?: field.nameAtCreation

    fun optionLabel(
        option: SequenceSnapshotCategoryOption,
        currentSourceLabel: String?,
    ): String = option.localLabelOverride ?: currentSourceLabel ?: option.labelAtCreation
}
