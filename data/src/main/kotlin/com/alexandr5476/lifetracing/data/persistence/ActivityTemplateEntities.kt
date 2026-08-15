package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "activity_templates",
    foreignKeys = [
        ForeignKey(
            entity = StatisticsSeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["statistics_series_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["deleted_at_ms", "name"], name = "activity_templates_deleted_name"),
        Index(value = ["folder_id", "deleted_at_ms"], name = "activity_templates_folder_deleted"),
        Index(value = ["statistics_series_id"], name = "activity_templates_series"),
    ],
)
internal data class ActivityTemplateEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "short_comment") val shortComment: String?,
    @ColumnInfo(name = "time_tracking_mode") val timeTrackingMode: String,
    @ColumnInfo(name = "timer_target_ms") val timerTargetMs: Long?,
    @ColumnInfo(name = "statistics_series_id") val statisticsSeriesId: String,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long?,
    @ColumnInfo(name = "folder_id") val folderId: String?,
)

@Entity(
    tableName = "activity_template_settings",
    foreignKeys = [
        ForeignKey(
            entity = ActivityTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ActivityTemplateSettingsEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "activity_template_id")
    val activityTemplateId: String,
    @ColumnInfo(name = "show_seconds", defaultValue = "1") val showSeconds: Boolean = true,
    @ColumnInfo(name = "start_countdown_ms", defaultValue = "0") val startCountdownMs: Long = 0,
    @ColumnInfo(name = "timer_zero_behavior", defaultValue = "'FINISH'")
    val timerZeroBehavior: String = "FINISH",
    @ColumnInfo(name = "timer_end_sound", defaultValue = "1") val timerEndSound: Boolean = true,
    @ColumnInfo(name = "timer_end_vibration", defaultValue = "1") val timerEndVibration: Boolean = true,
    @ColumnInfo(name = "keep_screen_awake", defaultValue = "0") val keepScreenAwake: Boolean = false,
    @ColumnInfo(name = "confirm_manual_finish", defaultValue = "0")
    val confirmManualFinish: Boolean = false,
)

@Entity(
    tableName = "activity_template_user_state",
    foreignKeys = [
        ForeignKey(
            entity = ActivityTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["pinned_rank"], name = "activity_user_state_pinned"),
        Index(
            value = ["last_used_at_ms"],
            orders = [Index.Order.DESC],
            name = "activity_user_state_recent",
        ),
    ],
)
internal data class ActivityTemplateUserStateEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "activity_template_id")
    val activityTemplateId: String,
    @ColumnInfo(name = "pinned_rank") val pinnedRank: Int?,
    @ColumnInfo(name = "last_used_at_ms") val lastUsedAtMs: Long?,
)

@Entity(
    tableName = "activity_template_fields",
    foreignKeys = [
        ForeignKey(
            entity = ActivityTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["activity_template_id", "deleted_at_ms", "position"],
            name = "activity_template_fields_owner_active_position",
        ),
        Index(
            value = ["activity_template_id"],
            name = "idx_activity_template_one_main_field",
            unique = true,
        ),
    ],
)
internal data class ActivityTemplateFieldEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "activity_template_id") val activityTemplateId: String,
    val position: Int,
    val name: String,
    @ColumnInfo(name = "field_type") val fieldType: String,
    val unit: String?,
    @ColumnInfo(name = "display_precision") val displayPrecision: Int?,
    @ColumnInfo(name = "default_number_scaled") val defaultNumberScaled: Long?,
    @ColumnInfo(name = "default_category_option_id") val defaultCategoryOptionId: String?,
    @ColumnInfo(name = "default_text") val defaultText: String?,
    @ColumnInfo(name = "is_main_value", defaultValue = "0") val isMainValue: Boolean = false,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long?,
)

@Entity(
    tableName = "activity_template_category_options",
    foreignKeys = [
        ForeignKey(
            entity = ActivityTemplateFieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_template_field_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["activity_template_field_id"],
            name = "activity_template_category_options_field",
        ),
    ],
)
internal data class ActivityTemplateCategoryOptionEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "activity_template_field_id") val activityTemplateFieldId: String,
    val position: Int,
    val label: String,
    @ColumnInfo(name = "is_archived", defaultValue = "0") val isArchived: Boolean = false,
)

@Entity(
    tableName = "activity_template_tags",
    primaryKeys = ["activity_template_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = ActivityTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag_id"], name = "activity_template_tags_tag")],
)
internal data class ActivityTemplateTagEntity(
    @ColumnInfo(name = "activity_template_id") val activityTemplateId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
)
