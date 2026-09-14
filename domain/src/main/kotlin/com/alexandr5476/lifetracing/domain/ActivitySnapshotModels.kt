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

data class OneOffActivitySnapshot(
    val snapshot: ActivityConfigSnapshot,
    val fieldIdsByKey: Map<String, ActivitySnapshotFieldId>,
    val optionIdsByKey: Map<String, ActivitySnapshotCategoryOptionId>,
)

data class ActivitySnapshotReplacement(
    val snapshot: ActivityConfigSnapshot,
    val fieldIds: Map<ActivitySnapshotFieldId, ActivitySnapshotFieldId>,
    val optionIds: Map<ActivitySnapshotCategoryOptionId, ActivitySnapshotCategoryOptionId>,
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

object ActivityHistoricalSnapshotPolicy {
    fun isCommentOnlyReplacement(
        previous: ActivityConfigSnapshot,
        replacement: ActivityConfigSnapshot,
    ): Boolean = previous.historicalSemantic() == replacement.historicalSemantic()
}

private data class HistoricalActivitySnapshotSemantic(
    val snapshot: ActivityConfigSnapshot,
    val fields: Map<HistoricalActivitySnapshotFieldSemantic, Int>,
)

private data class HistoricalActivitySnapshotFieldSemantic(
    val field: ActivitySnapshotField,
    val defaultCategoryOption: ActivitySnapshotCategoryOption?,
    val categoryOptions: Map<ActivitySnapshotCategoryOption, Int>,
)

private fun ActivityConfigSnapshot.historicalSemantic() =
    HistoricalActivitySnapshotSemantic(
        snapshot =
            copy(
                id = ActivitySnapshotId(""),
                shortComment = null,
                createdAt = Instant.EPOCH,
                fields = emptyList(),
            ),
        fields = fields.map(ActivitySnapshotField::historicalSemantic).groupingBy { it }.eachCount(),
    )

private fun ActivitySnapshotField.historicalSemantic(): HistoricalActivitySnapshotFieldSemantic {
    val optionSemantics = categoryOptions.associate { it.id to it.withoutPersistenceId() }
    return HistoricalActivitySnapshotFieldSemantic(
        field =
            copy(
                id = ActivitySnapshotFieldId(""),
                defaultCategoryOptionId = null,
                categoryOptions = emptyList(),
            ),
        defaultCategoryOption = defaultCategoryOptionId?.let(optionSemantics::getValue),
        categoryOptions = categoryOptions.map { it.withoutPersistenceId() }.groupingBy { it }.eachCount(),
    )
}

private fun ActivitySnapshotCategoryOption.withoutPersistenceId() = copy(id = ActivitySnapshotCategoryOptionId(""))

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

    fun duplicate(
        source: ActivityConfigSnapshot,
        createdAt: Instant,
    ): ActivityConfigSnapshot {
        val fieldIds = source.fields.associate { it.id to nextFieldId() }
        val optionIds =
            source.fields.flatMap(ActivitySnapshotField::categoryOptions).associate { it.id to nextOptionId() }
        return source
            .copy(
                id = nextSnapshotId(),
                createdAt = Instant.ofEpochMilli(createdAt.toEpochMilli()),
                fields =
                    source.fields.map { field ->
                        field.copy(
                            id = fieldIds.getValue(field.id),
                            defaultCategoryOptionId = field.defaultCategoryOptionId?.let(optionIds::getValue),
                            categoryOptions =
                                field.categoryOptions.map { option -> option.copy(id = optionIds.getValue(option.id)) },
                        )
                    },
            ).also(ActivityConfigSnapshotValidator::requireValid)
    }

    @Suppress("LongMethod") // Field and option IDs are allocated together so defaults cannot drift.
    fun fromOneOff(
        draft: ActivitySnapshotDraft,
        createdAt: Instant,
    ): OneOffActivitySnapshot {
        val fieldIds = linkedMapOf<String, ActivitySnapshotFieldId>()
        val optionIds = linkedMapOf<String, ActivitySnapshotCategoryOptionId>()
        val fields =
            draft.fields.map { field ->
                val fieldKey = requireNewKey(field.identity, "One-off Field")
                require(field.sourceFieldId == null) { "One-off Field cannot claim source identity" }
                val fieldId = nextFieldId()
                require(fieldIds.put(fieldKey, fieldId) == null) { "One-off Field keys must be unique" }
                val options =
                    field.categoryOptions.map { option ->
                        val optionKey = requireNewKey(option.identity, "One-off Category option")
                        require(option.sourceOptionId == null) { "One-off option cannot claim source identity" }
                        val optionId = nextOptionId()
                        require(optionIds.put(optionKey, optionId) == null) {
                            "One-off Category option keys must be unique"
                        }
                        ActivitySnapshotCategoryOption(
                            optionId,
                            null,
                            option.position,
                            option.labelAtCreation,
                            option.localLabelOverride,
                        )
                    }
                ActivitySnapshotField(
                    fieldId,
                    null,
                    field.position,
                    field.nameAtCreation,
                    field.localNameOverride,
                    field.type,
                    field.unit,
                    field.displayPrecision,
                    field.defaultNumberScaled,
                    field.defaultCategoryOption?.let {
                        optionIds[requireNewKey(it, "One-off Category default")]
                            ?: error("One-off Category default must belong to its Field")
                    },
                    field.defaultText,
                    field.isMainValue,
                    options,
                )
            }
        val snapshot =
            ActivityConfigSnapshot(
                nextSnapshotId(),
                draft.name,
                draft.shortComment,
                draft.timeTrackingMode,
                draft.timerTarget,
                null,
                null,
                null,
                false,
                Instant.ofEpochMilli(createdAt.toEpochMilli()),
                draft.settings,
                fields,
            ).also(ActivityConfigSnapshotValidator::requireValid)
        return OneOffActivitySnapshot(snapshot, fieldIds, optionIds)
    }

    fun replaceShortComment(
        source: ActivityConfigSnapshot,
        shortComment: String?,
        createdAt: Instant,
    ): ActivitySnapshotReplacement {
        val fieldIds = source.fields.associate { it.id to nextFieldId() }
        val optionIds =
            source.fields
                .flatMap(ActivitySnapshotField::categoryOptions)
                .associate { it.id to nextOptionId() }
        val replacement =
            source
                .copy(
                    id = nextSnapshotId(),
                    shortComment = shortComment,
                    createdAt = Instant.ofEpochMilli(createdAt.toEpochMilli()),
                    fields =
                        source.fields.map { field ->
                            field.copy(
                                id = fieldIds.getValue(field.id),
                                defaultCategoryOptionId = field.defaultCategoryOptionId?.let(optionIds::getValue),
                                categoryOptions =
                                    field.categoryOptions.map { option ->
                                        option.copy(id = optionIds.getValue(option.id))
                                    },
                            )
                        },
                ).also(ActivityConfigSnapshotValidator::requireValid)
        return ActivitySnapshotReplacement(replacement, fieldIds, optionIds)
    }
}

private fun requireNewKey(
    identity: DraftIdentity<*>,
    label: String,
): String = (identity as? DraftIdentity.New)?.key ?: throw IllegalArgumentException("$label must use a new draft key")

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
