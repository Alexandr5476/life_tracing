package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "plan_entries",
    foreignKeys = [
        ForeignKey(
            entity = ActivityTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_activity_template_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = SequenceTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_sequence_template_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ActivitySnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_snapshot_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SequenceSnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_plan_snapshot_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ActivityExecutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["fulfilled_activity_execution_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = SequenceExecutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["fulfilled_sequence_execution_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["status", "planned_day"], name = "plan_entries_status_planned_day"),
        Index(value = ["status", "planned_week_start"], name = "plan_entries_status_planned_week_start"),
        Index(value = ["status", "planned_month"], name = "plan_entries_status_planned_month"),
        Index(value = ["status", "scheduled_instant_ms"], name = "plan_entries_status_scheduled_instant"),
        Index(value = ["source_activity_template_id"], name = "plan_entries_source_activity_template_id"),
        Index(value = ["source_sequence_template_id"], name = "plan_entries_source_sequence_template_id"),
        Index(value = ["activity_snapshot_id"], name = "plan_entries_activity_snapshot_id"),
        Index(value = ["sequence_plan_snapshot_id"], name = "plan_entries_sequence_plan_snapshot_id"),
        Index(value = ["fulfilled_activity_execution_id"], name = "plan_entries_fulfilled_activity_execution_id"),
        Index(value = ["fulfilled_sequence_execution_id"], name = "plan_entries_fulfilled_sequence_execution_id"),
    ],
)
internal data class PlanEntryEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "trackable_kind") val trackableKind: String,
    @ColumnInfo(name = "source_activity_template_id") val sourceActivityTemplateId: String?,
    @ColumnInfo(name = "source_sequence_template_id") val sourceSequenceTemplateId: String?,
    @ColumnInfo(name = "source_revision") val sourceRevision: Long?,
    @ColumnInfo(name = "activity_snapshot_id") val activitySnapshotId: String?,
    @ColumnInfo(name = "sequence_plan_snapshot_id") val sequencePlanSnapshotId: String?,
    val precision: String,
    @ColumnInfo(name = "planned_day") val plannedDay: String?,
    @ColumnInfo(name = "planned_week_start") val plannedWeekStart: String?,
    @ColumnInfo(name = "planned_month") val plannedMonth: String?,
    @ColumnInfo(name = "scheduled_instant_ms") val scheduledInstantMs: Long?,
    @ColumnInfo(name = "creation_zone_id") val creationZoneId: String?,
    val status: String,
    @ColumnInfo(name = "fulfilled_activity_execution_id") val fulfilledActivityExecutionId: String?,
    @ColumnInfo(name = "fulfilled_sequence_execution_id") val fulfilledSequenceExecutionId: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo(name = "cancelled_at_ms") val cancelledAtMs: Long?,
    @ColumnInfo(name = "fulfilled_at_ms") val fulfilledAtMs: Long?,
)
