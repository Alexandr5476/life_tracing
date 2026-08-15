@file:Suppress("TooManyFunctions") // Explicit aggregate mappers keep each persisted code and owner visible.

package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.SequenceNode
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOption
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateField
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.SequenceTemplateUserState
import com.alexandr5476.lifetracing.domain.SequenceTemplateValidator
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import java.time.Duration
import java.time.Instant

internal fun SequenceTemplate.toEntityAggregate(): SequenceTemplateAggregateEntity {
    SequenceTemplateValidator.requireValid(this)
    return SequenceTemplateAggregateEntity(
        template =
            SequenceTemplateEntity(
                id = id.value,
                name = name,
                shortComment = shortComment,
                statisticsSeriesId = statisticsSeriesId.value,
                revision = revision,
                createdAtMs = createdAt.toEpochMilli(),
                updatedAtMs = updatedAt.toEpochMilli(),
                deletedAtMs = deletedAt?.toEpochMilli(),
                folderId = folderId?.value,
                noLiveTimeAccounting = noLiveTimeAccounting.toStorageCode(),
            ),
        settings = settings.toEntity(id),
        userState = userState.toEntity(id),
        fields = fields.map { it.toEntity(id) },
        options = fields.flatMap { field -> field.categoryOptions.map { it.toEntity(field.id) } },
        tags = tagIds.map { SequenceTemplateTagEntity(id.value, it.value) },
        nodes = nodes.flatMap { it.toEntities(id) },
        stepOverrides =
            nodes
                .flatMap { node ->
                    when (node) {
                        is ActivityStep -> listOf(node)
                        is SequenceRepeatBlock -> node.children
                    }
                }.filterNot { it.overrides.isEmpty }
                .map { it.overrides.toEntity(it.id) },
    )
}

internal fun SequenceTemplateAggregateEntity.toDomain(): SequenceTemplate {
    val optionsByField = options.groupBy(SequenceTemplateCategoryOptionEntity::sequenceTemplateFieldId)
    requireValidPersistedAggregateShape()
    val overridesByNode = stepOverrides.associateBy(SequenceStepOverrideEntity::sequenceNodeId)
    val domainNodes = nodes.toDomainNodes(overridesByNode)
    return SequenceTemplate(
        id = SequenceTemplateId(template.id),
        name = template.name,
        shortComment = template.shortComment,
        statisticsSeriesId = StatisticsSeriesId(template.statisticsSeriesId),
        revision = template.revision,
        createdAt = Instant.ofEpochMilli(template.createdAtMs),
        updatedAt = Instant.ofEpochMilli(template.updatedAtMs),
        deletedAt = template.deletedAtMs?.let(Instant::ofEpochMilli),
        folderId = template.folderId?.let(::FolderId),
        noLiveTimeAccounting = template.noLiveTimeAccounting.toNoLiveTimeAccounting(),
        settings = settings.toDomain(),
        userState = userState.toDomain(),
        fields = fields.map { it.toDomain(optionsByField[it.id].orEmpty()) },
        tagIds = tags.mapTo(linkedSetOf()) { TagId(it.tagId) },
        nodes = domainNodes,
    ).also(SequenceTemplateValidator::requireValid)
}

private fun SequenceTemplateAggregateEntity.requireValidPersistedAggregateShape() {
    require(settings.sequenceTemplateId == template.id) { "Settings owner mismatch" }
    require(userState.sequenceTemplateId == template.id) { "User-state owner mismatch" }
    require(fields.all { it.sequenceTemplateId == template.id }) { "Field owner mismatch" }
    require(tags.all { it.sequenceTemplateId == template.id }) { "Tag owner mismatch" }
    require(nodes.all { it.sequenceTemplateId == template.id }) { "Node owner mismatch" }
    require(nodes.map { it.id }.distinct().size == nodes.size) { "Sequence node identities must be unique" }
    val rowsById = nodes.associateBy(SequenceNodeEntity::id)
    val overridesByNode = stepOverrides.associateBy(SequenceStepOverrideEntity::sequenceNodeId)
    require(overridesByNode.size == stepOverrides.size) { "Step override owners must be unique" }
    stepOverrides.forEach { override ->
        val owner = requireNotNull(rowsById[override.sequenceNodeId]) { "Step override owner must exist" }
        require(owner.nodeType == "STEP") { "Only a Step can own execution-setting overrides" }
    }
    nodes.forEach { row ->
        when (row.nodeType) {
            "STEP" -> {
                require(
                    row.activitySnapshotId != null && row.repeatCount == null,
                ) { "Invalid STEP row shape: ${row.id}" }
                row.parentRepeatNodeId?.let { parentId ->
                    val parent = requireNotNull(rowsById[parentId]) { "Step parent must resolve inside its aggregate" }
                    require(parent.sequenceTemplateId == row.sequenceTemplateId && parent.nodeType == "REPEAT") {
                        "Step parent must be a same-Template Repeat"
                    }
                }
            }
            "REPEAT" ->
                require(
                    row.activitySnapshotId == null &&
                        row.repeatCount != null &&
                        row.repeatCount > 0 &&
                        row.parentRepeatNodeId == null,
                ) { "Invalid REPEAT row shape: ${row.id}" }
            else -> error("Unknown sequence node type code: ${row.nodeType}")
        }
    }
}

private fun List<SequenceNodeEntity>.toDomainNodes(
    overridesByNode: Map<String, SequenceStepOverrideEntity>,
): List<SequenceNode> {
    val childRows = filter { it.parentRepeatNodeId != null }.groupBy(SequenceNodeEntity::parentRepeatNodeId)
    val domainNodes =
        this
            .filter { it.parentRepeatNodeId == null }
            .map { row ->
                when (row.nodeType) {
                    "STEP" -> row.toStep(overridesByNode[row.id])
                    "REPEAT" ->
                        SequenceRepeatBlock(
                            id = SequenceNodeId(row.id),
                            position = row.position,
                            repeatCount = requireNotNull(row.repeatCount),
                            children =
                                childRows[row.id]
                                    .orEmpty()
                                    .map { it.toStep(overridesByNode[it.id]) }
                                    .sortedBy(ActivityStep::position),
                        )
                    else -> error("Unknown sequence node type code: ${row.nodeType}")
                }
            }.sortedBy(SequenceNode::position)
    val representedIds =
        domainNodes.flatMap { node ->
            when (node) {
                is ActivityStep -> listOf(node.id.value)
                is SequenceRepeatBlock -> listOf(node.id.value) + node.children.map { it.id.value }
            }
        }
    require(representedIds.size == size && representedIds.toSet() == mapTo(hashSetOf()) { it.id }) {
        "Sequence node mapping must represent every persisted row exactly once"
    }
    return domainNodes
}

private fun SequenceTemplateSettings.toEntity(id: SequenceTemplateId) =
    SequenceTemplateSettingsEntity(
        sequenceTemplateId = id.value,
        autoAdvance = autoAdvance,
        sequenceStartCountdownMs = sequenceStartCountdown.toMillis(),
        beforeEachStepCountdownMs = beforeEachStepCountdown.toMillis(),
        transitionSound = transitionSound,
        transitionVibration = transitionVibration,
        keepScreenAwake = keepScreenAwake,
        confirmJump = confirmJump,
        confirmEarlyEnd = confirmEarlyEnd,
    )

private fun SequenceTemplateSettingsEntity.toDomain() =
    SequenceTemplateSettings(
        autoAdvance = autoAdvance,
        sequenceStartCountdown = Duration.ofMillis(sequenceStartCountdownMs),
        beforeEachStepCountdown = Duration.ofMillis(beforeEachStepCountdownMs),
        transitionSound = transitionSound,
        transitionVibration = transitionVibration,
        keepScreenAwake = keepScreenAwake,
        confirmJump = confirmJump,
        confirmEarlyEnd = confirmEarlyEnd,
    )

private fun SequenceTemplateUserState.toEntity(id: SequenceTemplateId) =
    SequenceTemplateUserStateEntity(id.value, pinnedRank, lastUsedAt?.toEpochMilli())

private fun SequenceTemplateUserStateEntity.toDomain() =
    SequenceTemplateUserState(pinnedRank, lastUsedAtMs?.let(Instant::ofEpochMilli))

private fun SequenceTemplateField.toEntity(id: SequenceTemplateId) =
    SequenceTemplateFieldEntity(
        id = this.id.value,
        sequenceTemplateId = id.value,
        position = position,
        name = name,
        fieldType = type.toStorageCode(),
        unit = unit,
        displayPrecision = displayPrecision,
        defaultNumberScaled = defaultNumberScaled,
        defaultCategoryOptionId = defaultCategoryOptionId?.value,
        defaultText = defaultText,
        isMainValue = isMainValue,
        createdAtMs = createdAt.toEpochMilli(),
        updatedAtMs = updatedAt.toEpochMilli(),
        deletedAtMs = deletedAt?.toEpochMilli(),
    )

private fun SequenceTemplateFieldEntity.toDomain(options: List<SequenceTemplateCategoryOptionEntity>) =
    SequenceTemplateField(
        id = SequenceTemplateFieldId(id),
        position = position,
        name = name,
        type = fieldType.toCustomFieldType(),
        unit = unit,
        displayPrecision = displayPrecision,
        defaultNumberScaled = defaultNumberScaled,
        defaultCategoryOptionId = defaultCategoryOptionId?.let(::SequenceTemplateCategoryOptionId),
        defaultText = defaultText,
        isMainValue = isMainValue,
        createdAt = Instant.ofEpochMilli(createdAtMs),
        updatedAt = Instant.ofEpochMilli(updatedAtMs),
        deletedAt = deletedAtMs?.let(Instant::ofEpochMilli),
        categoryOptions = options.map(SequenceTemplateCategoryOptionEntity::toDomain),
    )

private fun SequenceTemplateCategoryOption.toEntity(fieldId: SequenceTemplateFieldId) =
    SequenceTemplateCategoryOptionEntity(id.value, fieldId.value, position, label, isArchived)

private fun SequenceTemplateCategoryOptionEntity.toDomain() =
    SequenceTemplateCategoryOption(SequenceTemplateCategoryOptionId(id), position, label, isArchived)

private fun SequenceNode.toEntities(templateId: SequenceTemplateId): List<SequenceNodeEntity> =
    when (this) {
        is ActivityStep -> listOf(toEntity(templateId, null))
        is SequenceRepeatBlock ->
            listOf(
                SequenceNodeEntity(id.value, templateId.value, "REPEAT", null, position, null, repeatCount),
            ) + children.map { it.toEntity(templateId, id) }
    }

private fun ActivityStep.toEntity(
    templateId: SequenceTemplateId,
    parentId: SequenceNodeId?,
) = SequenceNodeEntity(id.value, templateId.value, "STEP", parentId?.value, position, activitySnapshotId.value, null)

private fun SequenceNodeEntity.toStep(override: SequenceStepOverrideEntity?): ActivityStep {
    require(nodeType == "STEP" && repeatCount == null) { "Invalid STEP row shape: $id" }
    return ActivityStep(
        SequenceNodeId(id),
        position,
        ActivitySnapshotId(requireNotNull(activitySnapshotId)),
        override?.toDomain() ?: SequenceStepOverrides(),
    )
}

private fun SequenceStepOverrides.toEntity(nodeId: SequenceNodeId) =
    SequenceStepOverrideEntity(
        sequenceNodeId = nodeId.value,
        startCountdownMs = startCountdown?.toMillis(),
        timerZeroBehavior = timerZeroBehavior?.name,
        timerEndSound = timerEndSound,
        timerEndVibration = timerEndVibration,
        keepScreenAwake = keepScreenAwake,
    )

private fun SequenceStepOverrideEntity.toDomain() =
    SequenceStepOverrides(
        startCountdown = startCountdownMs?.let(Duration::ofMillis),
        timerZeroBehavior =
            timerZeroBehavior?.let { code ->
                when (code) {
                    "FINISH" -> TimerZeroBehavior.FINISH
                    "OVERTIME" -> TimerZeroBehavior.OVERTIME
                    else -> error("Unknown Step timer zero behavior code: $code")
                }
            },
        timerEndSound = timerEndSound,
        timerEndVibration = timerEndVibration,
        keepScreenAwake = keepScreenAwake,
    ).also { overrides ->
        require(overrides.startCountdown?.isNegative != true) { "Step start countdown must not be negative" }
    }

private fun NoLiveTimeAccounting.toStorageCode() = name

private fun String.toNoLiveTimeAccounting() =
    when (this) {
        "ACTIVE" -> NoLiveTimeAccounting.ACTIVE
        "PAUSE" -> NoLiveTimeAccounting.PAUSE
        else -> error("Unknown no-live time accounting code: $this")
    }

private fun CustomFieldType.toStorageCode() = name

private fun String.toCustomFieldType() =
    when (this) {
        "NUMBER" -> CustomFieldType.NUMBER
        "CATEGORY" -> CustomFieldType.CATEGORY
        "TEXT" -> CustomFieldType.TEXT
        else -> error("Unknown custom field type code: $this")
    }
