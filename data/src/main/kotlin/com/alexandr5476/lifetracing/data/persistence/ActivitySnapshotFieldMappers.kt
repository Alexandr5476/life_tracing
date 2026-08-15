package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOption
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType

internal fun ActivitySnapshotField.toEntity(snapshotId: ActivitySnapshotId) =
    ActivitySnapshotFieldEntity(
        id = id.value,
        snapshotId = snapshotId.value,
        sourceFieldId = sourceFieldId?.value,
        position = position,
        nameAtCreation = nameAtCreation,
        localNameOverride = localNameOverride,
        fieldType = type.toSnapshotStorageCode(),
        unit = unit,
        displayPrecision = displayPrecision,
        defaultNumberScaled = defaultNumberScaled,
        defaultCategoryOptionId = defaultCategoryOptionId?.value,
        defaultText = defaultText,
        isMainValue = isMainValue,
    )

internal fun ActivitySnapshotFieldEntity.toDomain(options: List<ActivitySnapshotCategoryOptionEntity>) =
    ActivitySnapshotField(
        id = ActivitySnapshotFieldId(id),
        sourceFieldId = sourceFieldId?.let(::ActivityTemplateFieldId),
        position = position,
        nameAtCreation = nameAtCreation,
        localNameOverride = localNameOverride,
        type = fieldType.toSnapshotCustomFieldType(),
        unit = unit,
        displayPrecision = displayPrecision,
        defaultNumberScaled = defaultNumberScaled,
        defaultCategoryOptionId = defaultCategoryOptionId?.let(::ActivitySnapshotCategoryOptionId),
        defaultText = defaultText,
        isMainValue = isMainValue,
        categoryOptions = options.map(ActivitySnapshotCategoryOptionEntity::toDomain),
    )

internal fun ActivitySnapshotCategoryOption.toEntity(fieldId: ActivitySnapshotFieldId) =
    ActivitySnapshotCategoryOptionEntity(
        id = id.value,
        snapshotFieldId = fieldId.value,
        sourceOptionId = sourceOptionId?.value,
        position = position,
        labelAtCreation = labelAtCreation,
        localLabelOverride = localLabelOverride,
    )

internal fun ActivitySnapshotCategoryOptionEntity.toDomain() =
    ActivitySnapshotCategoryOption(
        id = ActivitySnapshotCategoryOptionId(id),
        sourceOptionId = sourceOptionId?.let(::CategoryOptionId),
        position = position,
        labelAtCreation = labelAtCreation,
        localLabelOverride = localLabelOverride,
    )

private fun CustomFieldType.toSnapshotStorageCode() =
    when (this) {
        CustomFieldType.NUMBER -> "NUMBER"
        CustomFieldType.CATEGORY -> "CATEGORY"
        CustomFieldType.TEXT -> "TEXT"
    }

private fun String.toSnapshotCustomFieldType() =
    when (this) {
        "NUMBER" -> CustomFieldType.NUMBER
        "CATEGORY" -> CustomFieldType.CATEGORY
        "TEXT" -> CustomFieldType.TEXT
        else -> error("Unknown snapshot custom field type code: $this")
    }
