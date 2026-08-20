package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "sequence_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = SequenceTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_template_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = StatisticsSeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["statistics_series_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["source_template_id"], name = "sequence_snapshots_source_template_id"),
        Index(value = ["statistics_series_id"], name = "sequence_snapshots_statistics_series_id"),
    ],
)
internal data class SequenceSnapshotEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "short_comment") val shortComment: String?,
    @ColumnInfo(name = "source_template_id") val sourceTemplateId: String?,
    @ColumnInfo(name = "source_revision") val sourceRevision: Long?,
    @ColumnInfo(name = "statistics_series_id") val statisticsSeriesId: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Entity(
    tableName = "sequence_snapshot_settings",
    foreignKeys = [
        ForeignKey(
            entity = SequenceSnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_snapshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SequenceSnapshotSettingsEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "sequence_snapshot_id") val sequenceSnapshotId: String,
    @ColumnInfo(name = "auto_advance") val autoAdvance: Boolean,
    @ColumnInfo(name = "sequence_start_countdown_ms") val sequenceStartCountdownMs: Long,
    @ColumnInfo(name = "before_each_step_countdown_ms") val beforeEachStepCountdownMs: Long,
    @ColumnInfo(name = "transition_sound") val transitionSound: Boolean,
    @ColumnInfo(name = "transition_vibration") val transitionVibration: Boolean,
    @ColumnInfo(name = "keep_screen_awake") val keepScreenAwake: Boolean,
    @ColumnInfo(name = "confirm_jump") val confirmJump: Boolean,
    @ColumnInfo(name = "confirm_early_end") val confirmEarlyEnd: Boolean,
    @ColumnInfo(name = "no_live_time_accounting") val noLiveTimeAccounting: String,
)

@Entity(
    tableName = "sequence_snapshot_fields",
    foreignKeys = [
        ForeignKey(
            entity = SequenceSnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_snapshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SequenceTemplateFieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_field_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["sequence_snapshot_id", "position"], name = "sequence_snapshot_fields_owner_position"),
        Index(value = ["source_field_id"], name = "sequence_snapshot_fields_source_field_id"),
        Index(value = ["sequence_snapshot_id"], name = "idx_one_sequence_main_snapshot_field", unique = true),
    ],
)
internal data class SequenceSnapshotFieldEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "sequence_snapshot_id") val sequenceSnapshotId: String,
    @ColumnInfo(name = "source_field_id") val sourceFieldId: String?,
    val position: Int,
    @ColumnInfo(name = "name_at_creation") val nameAtCreation: String,
    @ColumnInfo(name = "local_name_override") val localNameOverride: String?,
    @ColumnInfo(name = "field_type") val fieldType: String,
    val unit: String?,
    @ColumnInfo(name = "display_precision") val displayPrecision: Int?,
    @ColumnInfo(name = "default_number_scaled") val defaultNumberScaled: Long?,
    @ColumnInfo(name = "default_category_option_id") val defaultCategoryOptionId: String?,
    @ColumnInfo(name = "default_text") val defaultText: String?,
    @ColumnInfo(name = "is_main_value", defaultValue = "0") val isMainValue: Boolean = false,
)

@Entity(
    tableName = "sequence_snapshot_category_options",
    foreignKeys = [
        ForeignKey(
            entity = SequenceSnapshotFieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_snapshot_field_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SequenceTemplateCategoryOptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_option_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["sequence_snapshot_field_id", "position"], name = "sequence_snapshot_options_owner_position"),
        Index(value = ["source_option_id"], name = "sequence_snapshot_options_source_option_id"),
    ],
)
internal data class SequenceSnapshotCategoryOptionEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "sequence_snapshot_field_id") val sequenceSnapshotFieldId: String,
    @ColumnInfo(name = "source_option_id") val sourceOptionId: String?,
    val position: Int,
    @ColumnInfo(name = "label_at_creation") val labelAtCreation: String,
    @ColumnInfo(name = "local_label_override") val localLabelOverride: String?,
)

@Entity(
    tableName = "sequence_snapshot_nodes",
    foreignKeys = [
        ForeignKey(
            entity = SequenceSnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_snapshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SequenceSnapshotNodeEntity::class,
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
        Index(
            value = ["sequence_snapshot_id", "parent_repeat_node_id", "position"],
            name = "sequence_snapshot_nodes_parent_position",
        ),
        Index(value = ["parent_repeat_node_id"], name = "sequence_snapshot_nodes_parent_repeat_node_id"),
        Index(value = ["activity_snapshot_id"], name = "sequence_snapshot_nodes_activity_snapshot_id"),
    ],
)
internal data class SequenceSnapshotNodeEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "sequence_snapshot_id") val sequenceSnapshotId: String,
    @ColumnInfo(name = "node_type") val nodeType: String,
    @ColumnInfo(name = "parent_repeat_node_id") val parentRepeatNodeId: String?,
    val position: Int,
    @ColumnInfo(name = "activity_snapshot_id") val activitySnapshotId: String?,
    @ColumnInfo(name = "repeat_count") val repeatCount: Int?,
)

@Entity(
    tableName = "sequence_snapshot_step_overrides",
    foreignKeys = [
        ForeignKey(
            entity = SequenceSnapshotNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_snapshot_node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SequenceSnapshotStepOverrideEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "sequence_snapshot_node_id") val sequenceSnapshotNodeId: String,
    @ColumnInfo(name = "start_countdown_ms") val startCountdownMs: Long?,
    @ColumnInfo(name = "timer_zero_behavior") val timerZeroBehavior: String?,
    @ColumnInfo(name = "timer_end_sound") val timerEndSound: Boolean?,
    @ColumnInfo(name = "timer_end_vibration") val timerEndVibration: Boolean?,
    @ColumnInfo(name = "keep_screen_awake") val keepScreenAwake: Boolean?,
)
