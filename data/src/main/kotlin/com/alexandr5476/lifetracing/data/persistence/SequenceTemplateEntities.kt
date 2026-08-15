package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "sequence_templates",
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
        Index(value = ["deleted_at_ms", "name"], name = "sequence_templates_deleted_name"),
        Index(value = ["folder_id", "deleted_at_ms"], name = "sequence_templates_folder_deleted"),
        Index(value = ["statistics_series_id"], name = "sequence_templates_series"),
    ],
)
internal data class SequenceTemplateEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "short_comment") val shortComment: String?,
    @ColumnInfo(name = "statistics_series_id") val statisticsSeriesId: String,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long?,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    @ColumnInfo(name = "no_live_time_accounting", defaultValue = "'ACTIVE'")
    val noLiveTimeAccounting: String = "ACTIVE",
)

@Entity(
    tableName = "sequence_template_settings",
    foreignKeys = [
        ForeignKey(
            entity = SequenceTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SequenceTemplateSettingsEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "sequence_template_id")
    val sequenceTemplateId: String,
    @ColumnInfo(name = "auto_advance", defaultValue = "1") val autoAdvance: Boolean = true,
    @ColumnInfo(name = "sequence_start_countdown_ms", defaultValue = "0")
    val sequenceStartCountdownMs: Long = 0,
    @ColumnInfo(name = "before_each_step_countdown_ms", defaultValue = "0")
    val beforeEachStepCountdownMs: Long = 0,
    @ColumnInfo(name = "transition_sound", defaultValue = "1") val transitionSound: Boolean = true,
    @ColumnInfo(name = "transition_vibration", defaultValue = "1") val transitionVibration: Boolean = true,
    @ColumnInfo(name = "keep_screen_awake", defaultValue = "0") val keepScreenAwake: Boolean = false,
    @ColumnInfo(name = "confirm_jump", defaultValue = "1") val confirmJump: Boolean = true,
    @ColumnInfo(name = "confirm_early_end", defaultValue = "1") val confirmEarlyEnd: Boolean = true,
)

@Entity(
    tableName = "sequence_template_user_state",
    foreignKeys = [
        ForeignKey(
            entity = SequenceTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["pinned_rank"], name = "sequence_user_state_pinned"),
        Index(value = ["last_used_at_ms"], orders = [Index.Order.DESC], name = "sequence_user_state_recent"),
    ],
)
internal data class SequenceTemplateUserStateEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "sequence_template_id")
    val sequenceTemplateId: String,
    @ColumnInfo(name = "pinned_rank") val pinnedRank: Int?,
    @ColumnInfo(name = "last_used_at_ms") val lastUsedAtMs: Long?,
)

@Entity(
    tableName = "sequence_template_fields",
    foreignKeys = [
        ForeignKey(
            entity = SequenceTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["sequence_template_id", "deleted_at_ms", "position"],
            name = "sequence_template_fields_owner_active_position",
        ),
        Index(value = ["sequence_template_id"], name = "idx_one_sequence_template_main_field", unique = true),
    ],
)
internal data class SequenceTemplateFieldEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "sequence_template_id") val sequenceTemplateId: String,
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
    tableName = "sequence_template_category_options",
    foreignKeys = [
        ForeignKey(
            entity = SequenceTemplateFieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_template_field_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sequence_template_field_id"], name = "sequence_template_category_options_field")],
)
internal data class SequenceTemplateCategoryOptionEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "sequence_template_field_id") val sequenceTemplateFieldId: String,
    val position: Int,
    val label: String,
    @ColumnInfo(name = "is_archived", defaultValue = "0") val isArchived: Boolean = false,
)

@Entity(
    tableName = "sequence_template_tags",
    primaryKeys = ["sequence_template_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = SequenceTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag_id"], name = "sequence_template_tags_tag")],
)
internal data class SequenceTemplateTagEntity(
    @ColumnInfo(name = "sequence_template_id") val sequenceTemplateId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
)

@Entity(
    tableName = "sequence_nodes",
    foreignKeys = [
        ForeignKey(
            entity = SequenceTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SequenceNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_repeat_node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ActivitySnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_snapshot_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sequence_template_id"], name = "sequence_nodes_sequence"),
        Index(
            value = ["sequence_template_id", "parent_repeat_node_id", "position"],
            name = "sequence_nodes_parent_position",
        ),
        Index(value = ["parent_repeat_node_id"], name = "sequence_nodes_parent_repeat_node_id"),
        Index(value = ["activity_snapshot_id"], name = "sequence_nodes_activity_snapshot_id"),
    ],
)
internal data class SequenceNodeEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "sequence_template_id") val sequenceTemplateId: String,
    @ColumnInfo(name = "node_type") val nodeType: String,
    @ColumnInfo(name = "parent_repeat_node_id") val parentRepeatNodeId: String?,
    val position: Int,
    @ColumnInfo(name = "activity_snapshot_id") val activitySnapshotId: String?,
    @ColumnInfo(name = "repeat_count") val repeatCount: Int?,
)
