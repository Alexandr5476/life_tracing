@file:Suppress("TooManyFunctions") // Explicit aggregate mappers keep each persisted code and owner visible.

package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshotValidator
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOption
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotField
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNode
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import java.time.Duration
import java.time.Instant

internal fun SequenceConfigSnapshot.toEntityAggregate(): SequenceSnapshotAggregateEntity {
    SequenceConfigSnapshotValidator.requireValid(this)
    return SequenceSnapshotAggregateEntity(
        SequenceSnapshotEntity(
            id.value,
            name,
            shortComment,
            sourceTemplateId?.value,
            sourceRevision,
            statisticsSeriesId?.value,
            createdAt.toEpochMilli(),
        ),
        settings.toEntity(id),
        fields.map { it.toEntity(id) },
        fields.flatMap { field -> field.categoryOptions.map { it.toEntity(field.id) } },
        nodes.flatMap { it.toEntities(id) },
        nodes
            .flatMap { node ->
                when (node) {
                    is SequenceSnapshotActivityStep -> listOf(node)
                    is SequenceSnapshotRepeatBlock -> node.children
                }
            }.filterNot { it.overrides.isEmpty }
            .map { it.overrides.toEntity(it.id) },
    )
}

internal fun SequenceSnapshotAggregateEntity.toDomain(): SequenceConfigSnapshot {
    requireValidPersistedShape()
    val optionsByField = options.groupBy(SequenceSnapshotCategoryOptionEntity::sequenceSnapshotFieldId)
    val overrides = stepOverrides.associateBy(SequenceSnapshotStepOverrideEntity::sequenceSnapshotNodeId)
    return SequenceConfigSnapshot(
        SequenceSnapshotId(snapshot.id),
        snapshot.name,
        snapshot.shortComment,
        snapshot.sourceTemplateId?.let(::SequenceTemplateId),
        snapshot.sourceRevision,
        snapshot.statisticsSeriesId?.let(::StatisticsSeriesId),
        Instant.ofEpochMilli(snapshot.createdAtMs),
        settings.toDomain(),
        fields.map { it.toDomain(optionsByField[it.id].orEmpty()) },
        nodes.toDomainNodes(overrides),
    ).also(SequenceConfigSnapshotValidator::requireValid)
}

private fun SequenceSnapshotAggregateEntity.requireValidPersistedShape() {
    require(settings.sequenceSnapshotId == snapshot.id) { "Settings owner mismatch" }
    require(fields.all { it.sequenceSnapshotId == snapshot.id }) { "Field owner mismatch" }
    val fieldsById = fields.associateBy(SequenceSnapshotFieldEntity::id)
    require(options.all { it.sequenceSnapshotFieldId in fieldsById }) { "Category option owner mismatch" }
    require(nodes.all { it.sequenceSnapshotId == snapshot.id }) { "Node owner mismatch" }
    require(nodes.map(SequenceSnapshotNodeEntity::id).distinct().size == nodes.size) {
        "Sequence snapshot node identities must be unique"
    }
    val nodesById = nodes.associateBy(SequenceSnapshotNodeEntity::id)
    require(
        stepOverrides.map(SequenceSnapshotStepOverrideEntity::sequenceSnapshotNodeId).distinct().size ==
            stepOverrides.size,
    ) { "Step override owners must be unique" }
    stepOverrides.forEach { override ->
        require(nodesById[override.sequenceSnapshotNodeId]?.nodeType == "STEP") {
            "Only a Step can own execution-setting overrides"
        }
    }
    nodes.forEach { node ->
        when (node.nodeType) {
            "STEP" -> {
                require(
                    node.activitySnapshotId != null && node.repeatCount == null,
                ) { "Invalid STEP row shape: ${node.id}" }
                node.parentRepeatNodeId?.let { parent ->
                    require(nodesById[parent]?.nodeType == "REPEAT") { "Step parent must be a same-snapshot Repeat" }
                }
            }
            "REPEAT" ->
                require(
                    node.activitySnapshotId == null &&
                        node.repeatCount != null &&
                        node.repeatCount > 0 &&
                        node.parentRepeatNodeId == null,
                ) { "Invalid REPEAT row shape: ${node.id}" }
            else -> error("Unknown sequence snapshot node type code: ${node.nodeType}")
        }
    }
}

private fun SequenceSnapshotSettings.toEntity(id: SequenceSnapshotId) =
    SequenceSnapshotSettingsEntity(
        id.value,
        autoAdvance,
        sequenceStartCountdown.toMillis(),
        beforeEachStepCountdown.toMillis(),
        transitionSound,
        transitionVibration,
        keepScreenAwake,
        confirmJump,
        confirmEarlyEnd,
        noLiveTimeAccounting.name,
    )

private fun SequenceSnapshotSettingsEntity.toDomain() =
    SequenceSnapshotSettings(
        autoAdvance,
        Duration.ofMillis(sequenceStartCountdownMs),
        Duration.ofMillis(beforeEachStepCountdownMs),
        transitionSound,
        transitionVibration,
        keepScreenAwake,
        confirmJump,
        confirmEarlyEnd,
        when (noLiveTimeAccounting) {
            "ACTIVE" -> NoLiveTimeAccounting.ACTIVE
            "PAUSE" -> NoLiveTimeAccounting.PAUSE
            else -> error("Unknown no-live time accounting code: $noLiveTimeAccounting")
        },
    )

private fun SequenceSnapshotField.toEntity(id: SequenceSnapshotId) =
    SequenceSnapshotFieldEntity(
        this.id.value,
        id.value,
        sourceFieldId?.value,
        position,
        nameAtCreation,
        localNameOverride,
        type.name,
        unit,
        displayPrecision,
        defaultNumberScaled,
        defaultCategoryOptionId?.value,
        defaultText,
        isMainValue,
    )

private fun SequenceSnapshotFieldEntity.toDomain(options: List<SequenceSnapshotCategoryOptionEntity>) =
    SequenceSnapshotField(
        SequenceSnapshotFieldId(id),
        sourceFieldId?.let(::SequenceTemplateFieldId),
        position,
        nameAtCreation,
        localNameOverride,
        when (fieldType) {
            "NUMBER" -> CustomFieldType.NUMBER
            "CATEGORY" -> CustomFieldType.CATEGORY
            "TEXT" -> CustomFieldType.TEXT
            else -> error("Unknown snapshot field type code: $fieldType")
        },
        unit,
        displayPrecision,
        defaultNumberScaled,
        defaultCategoryOptionId?.let(::SequenceSnapshotCategoryOptionId),
        defaultText,
        isMainValue,
        options.map(SequenceSnapshotCategoryOptionEntity::toDomain),
    )

private fun SequenceSnapshotCategoryOption.toEntity(fieldId: SequenceSnapshotFieldId) =
    SequenceSnapshotCategoryOptionEntity(
        id.value,
        fieldId.value,
        sourceOptionId?.value,
        position,
        labelAtCreation,
        localLabelOverride,
    )

private fun SequenceSnapshotCategoryOptionEntity.toDomain() =
    SequenceSnapshotCategoryOption(
        SequenceSnapshotCategoryOptionId(id),
        sourceOptionId?.let(::SequenceTemplateCategoryOptionId),
        position,
        labelAtCreation,
        localLabelOverride,
    )

private fun SequenceSnapshotNode.toEntities(snapshotId: SequenceSnapshotId): List<SequenceSnapshotNodeEntity> =
    when (this) {
        is SequenceSnapshotActivityStep ->
            listOf(
                SequenceSnapshotNodeEntity(
                    id.value,
                    snapshotId.value,
                    "STEP",
                    null,
                    position,
                    activitySnapshotId.value,
                    null,
                ),
            )
        is SequenceSnapshotRepeatBlock ->
            listOf(
                SequenceSnapshotNodeEntity(id.value, snapshotId.value, "REPEAT", null, position, null, repeatCount),
            ) +
                children.map {
                    SequenceSnapshotNodeEntity(
                        it.id.value,
                        snapshotId.value,
                        "STEP",
                        id.value,
                        it.position,
                        it.activitySnapshotId.value,
                        null,
                    )
                }
    }

private fun List<SequenceSnapshotNodeEntity>.toDomainNodes(
    overrides: Map<String, SequenceSnapshotStepOverrideEntity>,
): List<SequenceSnapshotNode> {
    val children = filter { it.parentRepeatNodeId != null }.groupBy(SequenceSnapshotNodeEntity::parentRepeatNodeId)
    val result =
        filter { it.parentRepeatNodeId == null }
            .map { row ->
                when (row.nodeType) {
                    "STEP" -> row.toStep(overrides[row.id])
                    "REPEAT" ->
                        SequenceSnapshotRepeatBlock(
                            SequenceSnapshotNodeId(
                                row.id,
                            ),
                            row.position,
                            requireNotNull(row.repeatCount),
                            children[row.id]
                                .orEmpty()
                                .map {
                                    it.toStep(overrides[it.id])
                                }.sortedBy(SequenceSnapshotActivityStep::position),
                        )
                    else -> error("Unknown sequence snapshot node type code: ${row.nodeType}")
                }
            }.sortedBy(SequenceSnapshotNode::position)
    val ids =
        result.flatMap {
            if (it is SequenceSnapshotRepeatBlock) {
                listOf(it.id.value) +
                    it.children.map { child -> child.id.value }
            } else {
                listOf(it.id.value)
            }
        }
    require(
        ids.size == size &&
            ids.toSet() ==
            mapTo(hashSetOf()) {
                it.id
            },
    ) { "Sequence snapshot mapping must represent every persisted row exactly once" }
    return result
}

private fun SequenceSnapshotNodeEntity.toStep(override: SequenceSnapshotStepOverrideEntity?) =
    SequenceSnapshotActivityStep(
        SequenceSnapshotNodeId(id),
        position,
        ActivitySnapshotId(requireNotNull(activitySnapshotId)),
        override?.toDomain() ?: SequenceStepOverrides(),
    )

private fun SequenceStepOverrides.toEntity(id: SequenceSnapshotNodeId) =
    SequenceSnapshotStepOverrideEntity(
        id.value,
        startCountdown?.toMillis(),
        timerZeroBehavior?.name,
        timerEndSound,
        timerEndVibration,
        keepScreenAwake,
    )

private fun SequenceSnapshotStepOverrideEntity.toDomain() =
    SequenceStepOverrides(
        startCountdownMs?.let(Duration::ofMillis),
        timerZeroBehavior?.let {
            when (it) {
                "FINISH" -> TimerZeroBehavior.FINISH
                "OVERTIME" -> TimerZeroBehavior.OVERTIME
                else -> error("Unknown Step timer zero behavior code: $it")
            }
        },
        timerEndSound,
        timerEndVibration,
        keepScreenAwake,
    )
