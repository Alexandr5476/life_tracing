package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "activity_executions",
    foreignKeys = [
        ForeignKey(
            entity = ActivitySnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshot_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StatisticsSeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["statistics_series_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["snapshot_id"], name = "activity_executions_snapshot_id"),
        Index(value = ["statistics_series_id", "completed_at_ms"], name = "activity_executions_series_completed"),
        Index(value = ["context_type", "completed_at_ms"], name = "activity_executions_context_completed"),
        Index(value = ["sequence_execution_id"], name = "activity_executions_sequence_execution_id"),
        Index(value = ["sequence_occurrence_id"], name = "activity_executions_sequence_occurrence_id"),
        Index(value = ["plan_entry_id"], name = "activity_executions_plan_entry_id"),
        Index(value = ["deleted_at_ms"], name = "activity_executions_deleted_at"),
        Index(value = ["primary_local_date", "deleted_at_ms"], name = "activity_executions_primary_date_deleted"),
        Index(value = ["sequence_occurrence_id"], name = "idx_one_child_execution_per_occurrence", unique = true),
    ],
)
internal data class ActivityExecutionEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "context_type") val contextType: String,
    @ColumnInfo(name = "sequence_execution_id") val sequenceExecutionId: String?,
    @ColumnInfo(name = "sequence_occurrence_id") val sequenceOccurrenceId: String?,
    @ColumnInfo(name = "plan_entry_id") val planEntryId: String?,
    @ColumnInfo(name = "statistics_series_id") val statisticsSeriesId: String?,
    val status: String,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long?,
    @ColumnInfo(name = "completed_at_ms") val completedAtMs: Long?,
    @ColumnInfo(name = "active_duration_ms") val activeDurationMs: Long?,
    @ColumnInfo(name = "original_zone_id") val originalZoneId: String,
    @ColumnInfo(name = "original_utc_offset_minutes") val originalUtcOffsetMinutes: Int?,
    @ColumnInfo(name = "primary_local_date") val primaryLocalDate: String,
    @ColumnInfo(name = "completion_reason") val completionReason: String?,
    @ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(
    tableName = "activity_execution_pauses",
    foreignKeys = [
        ForeignKey(
            entity = ActivityExecutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["execution_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["execution_id", "started_at_ms"], name = "activity_execution_pauses_owner_started"),
    ],
)
internal data class ActivityExecutionPauseEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "execution_id") val executionId: String,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long?,
)

@Entity(
    tableName = "activity_execution_field_values",
    primaryKeys = ["execution_id", "snapshot_field_id"],
    foreignKeys = [
        ForeignKey(
            entity = ActivityExecutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["execution_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ActivitySnapshotFieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshot_field_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ActivitySnapshotCategoryOptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_option_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["snapshot_field_id"], name = "activity_execution_values_snapshot_field_id"),
        Index(value = ["number_value_scaled"], name = "activity_execution_values_number"),
        Index(value = ["category_option_id"], name = "activity_execution_values_category"),
    ],
)
internal data class ActivityExecutionFieldValueEntity(
    @ColumnInfo(name = "execution_id") val executionId: String,
    @ColumnInfo(name = "snapshot_field_id") val snapshotFieldId: String,
    @ColumnInfo(name = "number_value_scaled") val numberValueScaled: Long?,
    @ColumnInfo(name = "category_option_id") val categoryOptionId: String?,
    @ColumnInfo(name = "text_value") val textValue: String?,
)
