package com.alexandr5476.lifetracing.domain

import java.time.Instant

@JvmInline
value class ActivitySnapshotId(
    val value: String,
)

@JvmInline
value class ActivitySnapshotFieldId(
    val value: String,
)

@JvmInline
value class ActivitySnapshotCategoryOptionId(
    val value: String,
)

data class ActivitySnapshotCategoryOption(
    val id: ActivitySnapshotCategoryOptionId,
    val sourceOptionId: CategoryOptionId?,
    val position: Int,
    val labelAtCreation: String,
    val localLabelOverride: String? = null,
)

data class ActivitySnapshotField(
    val id: ActivitySnapshotFieldId,
    val sourceFieldId: ActivityTemplateFieldId?,
    val position: Int,
    val nameAtCreation: String,
    val localNameOverride: String? = null,
    val type: CustomFieldType,
    val unit: String? = null,
    val displayPrecision: Int? = null,
    val defaultNumberScaled: Long? = null,
    val defaultCategoryOptionId: ActivitySnapshotCategoryOptionId? = null,
    val defaultText: String? = null,
    val isMainValue: Boolean = false,
    val categoryOptions: List<ActivitySnapshotCategoryOption> = emptyList(),
)

data class ActivityConfigSnapshot(
    val id: ActivitySnapshotId,
    val name: String,
    val shortComment: String?,
    val timeTrackingMode: TimeTrackingMode,
    val timerTarget: java.time.Duration?,
    val sourceTemplateId: ActivityTemplateId?,
    val sourceRevision: Long?,
    val statisticsSeriesId: StatisticsSeriesId?,
    val locallyModified: Boolean,
    val createdAt: Instant,
    val settings: ActivityTemplateSettings = ActivityTemplateSettings(),
    val fields: List<ActivitySnapshotField> = emptyList(),
)

object ActivityConfigSnapshotValidator {
    fun requireValid(snapshot: ActivityConfigSnapshot) {
        ActivityTemplateValidator.requireValidTracking(snapshot.timeTrackingMode, snapshot.timerTarget)
        require(!snapshot.settings.startCountdown.isNegative) { "Start countdown must not be negative" }
        if (snapshot.sourceTemplateId != null) {
            require(snapshot.sourceRevision != null && snapshot.sourceRevision >= 1) {
                "Source-linked snapshot revision must be at least 1"
            }
            require(snapshot.statisticsSeriesId != null) {
                "Source-linked snapshot must retain its Statistics Series"
            }
        }
        snapshot.fields.forEach(::requireValidField)
        require(snapshot.fields.count { it.isMainValue } <= 1) {
            "Activity snapshot may have at most one Main Value"
        }
    }

    fun requireValidField(field: ActivitySnapshotField) {
        when (field.type) {
            CustomFieldType.NUMBER -> {
                require(field.defaultCategoryOptionId == null && field.defaultText == null) {
                    "NUMBER snapshot field cannot contain Category or Text defaults"
                }
                require(field.categoryOptions.isEmpty()) {
                    "NUMBER snapshot field cannot contain Category options"
                }
            }
            CustomFieldType.CATEGORY -> {
                require(
                    field.unit == null &&
                        field.displayPrecision == null &&
                        field.defaultNumberScaled == null &&
                        field.defaultText == null,
                ) { "CATEGORY snapshot field cannot contain Number or Text metadata" }
                field.defaultCategoryOptionId?.let { defaultId ->
                    require(field.categoryOptions.any { it.id == defaultId }) {
                        "Category default must belong to the same snapshot field"
                    }
                }
            }
            CustomFieldType.TEXT -> {
                require(
                    field.unit == null &&
                        field.displayPrecision == null &&
                        field.defaultNumberScaled == null &&
                        field.defaultCategoryOptionId == null,
                ) { "TEXT snapshot field cannot contain Number or Category metadata" }
                require(field.categoryOptions.isEmpty()) {
                    "TEXT snapshot field cannot contain Category options"
                }
            }
        }
        require(!field.isMainValue || field.type == CustomFieldType.NUMBER) {
            "Main Value must be a NUMBER snapshot field"
        }
    }
}

class ActivitySnapshotFactory(
    private val nextSnapshotId: () -> ActivitySnapshotId,
    private val nextFieldId: () -> ActivitySnapshotFieldId,
    private val nextOptionId: () -> ActivitySnapshotCategoryOptionId,
) {
    fun fromTemplate(
        template: ActivityTemplate,
        createdAt: Instant,
    ): ActivityConfigSnapshot {
        ActivityTemplateValidator.requireValid(template)
        val snapshotId = nextSnapshotId()
        val fields =
            template.fields.filter { it.deletedAt == null }.map { sourceField ->
                val options =
                    sourceField.categoryOptions.filterNot { it.isArchived }.map { sourceOption ->
                        ActivitySnapshotCategoryOption(
                            id = nextOptionId(),
                            sourceOptionId = sourceOption.id,
                            position = sourceOption.position,
                            labelAtCreation = sourceOption.label,
                        )
                    }
                ActivitySnapshotField(
                    id = nextFieldId(),
                    sourceFieldId = sourceField.id,
                    position = sourceField.position,
                    nameAtCreation = sourceField.name,
                    type = sourceField.type,
                    unit = sourceField.unit,
                    displayPrecision = sourceField.displayPrecision,
                    defaultNumberScaled = sourceField.defaultNumberScaled,
                    defaultCategoryOptionId =
                        sourceField.defaultCategoryOptionId?.let { sourceDefault ->
                            options.single { it.sourceOptionId == sourceDefault }.id
                        },
                    defaultText = sourceField.defaultText,
                    isMainValue = sourceField.isMainValue,
                    categoryOptions = options,
                )
            }
        return ActivityConfigSnapshot(
            id = snapshotId,
            name = template.name,
            shortComment = template.shortComment,
            timeTrackingMode = template.timeTrackingMode,
            timerTarget = template.timerTarget,
            sourceTemplateId = template.id,
            sourceRevision = template.revision,
            statisticsSeriesId = template.statisticsSeriesId,
            locallyModified = false,
            createdAt = createdAt,
            settings = template.settings,
            fields = fields,
        ).also(ActivityConfigSnapshotValidator::requireValid)
    }
}

object ActivitySnapshotDisplayResolver {
    fun fieldName(
        field: ActivitySnapshotField,
        currentSourceName: String?,
    ): String = field.localNameOverride ?: currentSourceName ?: field.nameAtCreation

    fun optionLabel(
        option: ActivitySnapshotCategoryOption,
        currentSourceLabel: String?,
    ): String = option.localLabelOverride ?: currentSourceLabel ?: option.labelAtCreation
}
