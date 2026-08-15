package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryOption
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import java.time.Instant

internal fun ActivityTemplateField.toEntity(templateId: ActivityTemplateId) =
    ActivityTemplateFieldEntity(
        id = id.value,
        activityTemplateId = templateId.value,
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

internal fun ActivityTemplateFieldEntity.toDomain(options: List<CategoryOption>) =
    ActivityTemplateField(
        id = ActivityTemplateFieldId(id),
        position = position,
        name = name,
        type = fieldType.toCustomFieldType(),
        unit = unit,
        displayPrecision = displayPrecision,
        defaultNumberScaled = defaultNumberScaled,
        defaultCategoryOptionId = defaultCategoryOptionId?.let(::CategoryOptionId),
        defaultText = defaultText,
        isMainValue = isMainValue,
        createdAt = Instant.ofEpochMilli(createdAtMs),
        updatedAt = Instant.ofEpochMilli(updatedAtMs),
        deletedAt = deletedAtMs?.let(Instant::ofEpochMilli),
        categoryOptions = options,
    )

internal fun CategoryOption.toEntity(fieldId: ActivityTemplateFieldId) =
    ActivityTemplateCategoryOptionEntity(id.value, fieldId.value, position, label, isArchived)

internal fun ActivityTemplateCategoryOptionEntity.toDomain() =
    CategoryOption(CategoryOptionId(id), position, label, isArchived)

private fun CustomFieldType.toStorageCode() =
    when (this) {
        CustomFieldType.NUMBER -> "NUMBER"
        CustomFieldType.CATEGORY -> "CATEGORY"
        CustomFieldType.TEXT -> "TEXT"
    }

private fun String.toCustomFieldType() =
    when (this) {
        "NUMBER" -> CustomFieldType.NUMBER
        "CATEGORY" -> CustomFieldType.CATEGORY
        "TEXT" -> CustomFieldType.TEXT
        else -> error("Unknown custom field type code: $this")
    }
