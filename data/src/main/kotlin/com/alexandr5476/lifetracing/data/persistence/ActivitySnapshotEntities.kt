package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "activity_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = ActivityTemplateEntity::class,
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
        Index(value = ["source_template_id"], name = "activity_snapshots_source_template_id"),
        Index(value = ["statistics_series_id"], name = "activity_snapshots_statistics_series_id"),
    ],
)
internal data class ActivitySnapshotEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "short_comment") val shortComment: String?,
    @ColumnInfo(name = "time_tracking_mode") val timeTrackingMode: String,
    @ColumnInfo(name = "timer_target_ms") val timerTargetMs: Long?,
    @ColumnInfo(name = "source_template_id") val sourceTemplateId: String?,
    @ColumnInfo(name = "source_revision") val sourceRevision: Long?,
    @ColumnInfo(name = "statistics_series_id") val statisticsSeriesId: String?,
    @ColumnInfo(name = "locally_modified", defaultValue = "0") val locallyModified: Boolean = false,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Entity(
    tableName = "activity_snapshot_settings",
    foreignKeys = [
        ForeignKey(
            entity = ActivitySnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ActivitySnapshotSettingsEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String,
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
    tableName = "activity_snapshot_fields",
    foreignKeys = [
        ForeignKey(
            entity = ActivitySnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ActivityTemplateFieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_field_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["snapshot_id", "position"], name = "activity_snapshot_fields_owner_position"),
        Index(value = ["source_field_id"], name = "activity_snapshot_fields_source_field_id"),
        Index(value = ["snapshot_id"], name = "idx_one_activity_main_snapshot_field", unique = true),
    ],
)
internal data class ActivitySnapshotFieldEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
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
    tableName = "activity_snapshot_category_options",
    foreignKeys = [
        ForeignKey(
            entity = ActivitySnapshotFieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshot_field_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ActivityTemplateCategoryOptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_option_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(
            value = ["snapshot_field_id", "position"],
            name = "activity_snapshot_options_owner_position",
        ),
        Index(value = ["source_option_id"], name = "activity_snapshot_options_source_option_id"),
    ],
)
internal data class ActivitySnapshotCategoryOptionEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "snapshot_field_id") val snapshotFieldId: String,
    @ColumnInfo(name = "source_option_id") val sourceOptionId: String?,
    val position: Int,
    @ColumnInfo(name = "label_at_creation") val labelAtCreation: String,
    @ColumnInfo(name = "local_label_override") val localLabelOverride: String?,
)
