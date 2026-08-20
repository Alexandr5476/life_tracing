package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "sequence_executions",
    foreignKeys = [
        ForeignKey(
            entity = SequenceSnapshotEntity::class,
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
        ForeignKey(
            entity = SequenceOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["current_occurrence_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["statistics_series_id", "ended_at_ms"], name = "sequence_executions_series_ended"),
        Index(value = ["plan_entry_id"], name = "sequence_executions_plan_entry_id"),
        Index(value = ["primary_local_date"], name = "sequence_executions_primary_local_date"),
        Index(value = ["snapshot_id"], name = "sequence_executions_snapshot_id"),
        Index(value = ["current_occurrence_id"], name = "sequence_executions_current_occurrence_id"),
    ],
)
internal data class SequenceExecutionEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "plan_entry_id") val planEntryId: String?,
    @ColumnInfo(name = "statistics_series_id") val statisticsSeriesId: String?,
    val status: String,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long?,
    @ColumnInfo(name = "active_duration_ms") val activeDurationMs: Long?,
    @ColumnInfo(name = "pause_duration_ms") val pauseDurationMs: Long?,
    @ColumnInfo(name = "wall_duration_ms") val wallDurationMs: Long?,
    @ColumnInfo(name = "original_zone_id") val originalZoneId: String,
    @ColumnInfo(name = "original_utc_offset_minutes") val originalUtcOffsetMinutes: Int?,
    @ColumnInfo(name = "primary_local_date") val primaryLocalDate: String,
    @ColumnInfo(name = "current_occurrence_id") val currentOccurrenceId: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(
    tableName = "sequence_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = SequenceExecutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_execution_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SequenceSnapshotNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_sequence_snapshot_node_id"],
            onDelete = ForeignKey.SET_NULL,
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
            value = ["sequence_execution_id", "runtime_position"],
            name = "sequence_occurrences_execution_position",
            unique = true,
        ),
        Index(value = ["sequence_execution_id", "status"], name = "sequence_occurrences_execution_status"),
        Index(value = ["activity_snapshot_id"], name = "sequence_occurrences_activity_snapshot_id"),
        Index(value = ["source_sequence_snapshot_node_id"], name = "sequence_occurrences_source_node_id"),
        Index(value = ["sequence_execution_id"], name = "idx_one_current_occurrence", unique = true),
    ],
)
internal data class SequenceOccurrenceEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "sequence_execution_id") val sequenceExecutionId: String,
    @ColumnInfo(name = "source_sequence_snapshot_node_id") val sourceSequenceSnapshotNodeId: String?,
    @ColumnInfo(name = "activity_snapshot_id") val activitySnapshotId: String,
    @ColumnInfo(name = "runtime_position") val runtimePosition: Int,
    @ColumnInfo(name = "repeat_source_snapshot_node_id") val repeatSourceSnapshotNodeId: String?,
    @ColumnInfo(name = "repeat_iteration") val repeatIteration: Int?,
    val status: String,
    @ColumnInfo(name = "entered_at_ms") val enteredAtMs: Long?,
    @ColumnInfo(name = "completed_at_ms") val completedAtMs: Long?,
    @ColumnInfo(name = "completion_reason") val completionReason: String?,
    @ColumnInfo(name = "is_runtime_added", defaultValue = "0") val isRuntimeAdded: Boolean = false,
    @ColumnInfo(name = "is_deleted_from_history", defaultValue = "0") val isDeletedFromHistory: Boolean = false,
)

@Entity(
    tableName = "sequence_intervals",
    foreignKeys = [
        ForeignKey(
            entity = SequenceExecutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_execution_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SequenceOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrence_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["sequence_execution_id", "started_at_ms"], name = "sequence_intervals_execution_started"),
        Index(
            value = ["sequence_execution_id", "kind", "started_at_ms"],
            name = "sequence_intervals_execution_kind_started",
        ),
        Index(value = ["occurrence_id"], name = "sequence_intervals_occurrence_id"),
    ],
)
internal data class SequenceIntervalEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "sequence_execution_id") val sequenceExecutionId: String,
    val kind: String,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long?,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String?,
)

@Entity(
    tableName = "sequence_execution_field_values",
    primaryKeys = ["sequence_execution_id", "snapshot_field_id"],
    foreignKeys = [
        ForeignKey(
            entity = SequenceExecutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_execution_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SequenceSnapshotFieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshot_field_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SequenceSnapshotCategoryOptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_option_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["snapshot_field_id"], name = "sequence_execution_values_snapshot_field_id"),
        Index(value = ["number_scaled"], name = "sequence_execution_values_number"),
        Index(value = ["category_option_id"], name = "sequence_execution_values_category"),
    ],
)
internal data class SequenceExecutionFieldValueEntity(
    @ColumnInfo(name = "sequence_execution_id") val sequenceExecutionId: String,
    @ColumnInfo(name = "snapshot_field_id") val snapshotFieldId: String,
    @ColumnInfo(name = "number_scaled") val numberScaled: Long?,
    @ColumnInfo(name = "category_option_id") val categoryOptionId: String?,
    @ColumnInfo(name = "text_value") val textValue: String?,
)
