package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant

@JvmInline
value class ActivityTemplateId(
    val value: String,
)

@JvmInline
value class ActivityTemplateFieldId(
    val value: String,
)

@JvmInline
value class CategoryOptionId(
    val value: String,
)

enum class TimeTrackingMode {
    STOPWATCH,
    TIMER,
    NO_LIVE_TRACKING,
}

enum class CustomFieldType {
    NUMBER,
    CATEGORY,
    TEXT,
}

enum class TimerZeroBehavior {
    FINISH,
    OVERTIME,
}

data class ActivityTemplateSettings(
    val showSeconds: Boolean = true,
    val startCountdown: Duration = Duration.ZERO,
    val timerZeroBehavior: TimerZeroBehavior = TimerZeroBehavior.FINISH,
    val timerEndSound: Boolean = true,
    val timerEndVibration: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val confirmManualFinish: Boolean = false,
)

data class ActivityTemplateUserState(
    val pinnedRank: Int? = null,
    val lastUsedAt: Instant? = null,
)

data class CategoryOption(
    val id: CategoryOptionId,
    val position: Int,
    val label: String,
    val isArchived: Boolean = false,
)

data class ActivityTemplateField(
    val id: ActivityTemplateFieldId,
    val position: Int,
    val name: String,
    val type: CustomFieldType,
    val unit: String? = null,
    val displayPrecision: Int? = null,
    val defaultNumberScaled: Long? = null,
    val defaultCategoryOptionId: CategoryOptionId? = null,
    val defaultText: String? = null,
    val isMainValue: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val categoryOptions: List<CategoryOption> = emptyList(),
)

data class ActivityTemplate(
    val id: ActivityTemplateId,
    val name: String,
    val shortComment: String?,
    val timeTrackingMode: TimeTrackingMode,
    val timerTarget: Duration?,
    val statisticsSeriesId: StatisticsSeriesId,
    val revision: Long = ActivityTemplateRevisionPolicy.INITIAL_REVISION,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val folderId: FolderId? = null,
    val settings: ActivityTemplateSettings = ActivityTemplateSettings(),
    val fields: List<ActivityTemplateField> = emptyList(),
    val tagIds: Set<TagId> = emptySet(),
)

object ActivityTemplateValidator {
    fun requireValid(template: ActivityTemplate) {
        require(template.revision >= ActivityTemplateRevisionPolicy.INITIAL_REVISION) {
            "ActivityTemplate revision must be at least 1"
        }
        requireValidTracking(template.timeTrackingMode, template.timerTarget)
        require(!template.settings.startCountdown.isNegative) { "Start countdown must not be negative" }
        template.fields.forEach(::requireValidField)

        val activeMainValues = template.fields.filter { it.deletedAt == null && it.isMainValue }
        require(activeMainValues.size <= 1) { "ActivityTemplate may have at most one active Main Value" }
    }

    fun requireValidTracking(
        mode: TimeTrackingMode,
        timerTarget: Duration?,
    ) {
        when (mode) {
            TimeTrackingMode.TIMER ->
                require(timerTarget != null && timerTarget > Duration.ZERO) {
                    "Timer target must be positive in TIMER mode"
                }
            TimeTrackingMode.STOPWATCH,
            TimeTrackingMode.NO_LIVE_TRACKING,
            -> require(timerTarget == null) { "Timer target must be null outside TIMER mode" }
        }
    }

    fun requireValidField(field: ActivityTemplateField) {
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
                    require(field.categoryOptions.any { it.id == defaultId }) {
                        "Category default must belong to the same field"
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
}

object ActivityTemplateFieldEvolution {
    fun requireSameIdentityCompatible(
        previous: ActivityTemplateField,
        updated: ActivityTemplateField,
    ) {
        if (previous.id != updated.id) return
        require(previous.type == updated.type) { "Field type is immutable for the same field identity" }
        require(previous.unit == updated.unit) { "Field unit is immutable for the same field identity" }
    }
}

enum class ActivityTemplateEdit(
    val isSemantic: Boolean,
) {
    NAME(true),
    SHORT_COMMENT(true),
    TRACKING_CONFIGURATION(true),
    SETTINGS(true),
    FIELD_SCHEMA(true),
    FIELD_DISPLAY_NAME(false),
    CATEGORY_OPTION_DISPLAY_NAME(false),
    FOLDER(false),
    TAGS(false),
    USER_STATE(false),
}

object ActivityTemplateRevisionPolicy {
    const val INITIAL_REVISION = 1L

    fun after(
        currentRevision: Long,
        vararg edits: ActivityTemplateEdit,
    ): Long {
        require(currentRevision >= INITIAL_REVISION) { "Current revision must be at least 1" }
        return if (edits.any(ActivityTemplateEdit::isSemantic)) currentRevision + 1 else currentRevision
    }
}

object ActivityTemplateLifecycle {
    fun archive(
        template: ActivityTemplate,
        archivedAt: Instant,
    ): ActivityTemplate = template.copy(deletedAt = archivedAt)

    fun restore(template: ActivityTemplate): ActivityTemplate = template.copy(deletedAt = null)
}
