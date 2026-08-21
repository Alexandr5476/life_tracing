@file:Suppress("LongParameterList", "MaxLineLength", "TooManyFunctions")

package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

internal data class PlanSnapshotSummaryRow(
    val id: String,
    val name: String,
    @androidx.room.ColumnInfo(name = "short_comment") val shortComment: String?,
)

internal data class PlanSourceMetadataRow(
    val id: String,
    val revision: Long,
    @androidx.room.ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long?,
)

@Dao
internal abstract class PlanEntryDao {
    @Query("SELECT * FROM plan_entries WHERE id = :id")
    abstract fun getById(id: String): PlanEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insert(plan: PlanEntryEntity)

    @Query(
        "SELECT * FROM plan_entries WHERE status = 'PLANNED' AND precision = 'DAY' AND scheduled_instant_ms IS NULL AND planned_day BETWEEN :startDate AND :endDate ORDER BY planned_day, created_at_ms, id",
    )
    abstract fun getFloatingDays(
        startDate: String,
        endDate: String,
    ): List<PlanEntryEntity>

    @Query(
        "SELECT * FROM plan_entries WHERE status = 'PLANNED' AND scheduled_instant_ms >= :startMs AND scheduled_instant_ms < :endMs ORDER BY scheduled_instant_ms, created_at_ms, id",
    )
    abstract fun getExactDays(
        startMs: Long,
        endMs: Long,
    ): List<PlanEntryEntity>

    @Query(
        "SELECT * FROM plan_entries WHERE status = 'PLANNED' AND precision = 'WEEK' AND planned_week_start = :weekStart ORDER BY created_at_ms, id",
    )
    abstract fun getWeek(weekStart: String): List<PlanEntryEntity>

    @Query(
        "SELECT * FROM plan_entries WHERE status = 'PLANNED' AND precision = 'MONTH' AND planned_month = :month ORDER BY created_at_ms, id",
    )
    abstract fun getMonth(month: String): List<PlanEntryEntity>

    @Query("SELECT * FROM plan_entries WHERE status = 'CANCELLED' ORDER BY cancelled_at_ms DESC, id")
    abstract fun getCancelled(): List<PlanEntryEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM activity_executions WHERE plan_entry_id = :id AND context_type = 'STANDALONE' AND status IN ('RUNNING', 'PAUSED') LIMIT 1)",
    )
    abstract fun hasLiveActivity(id: String): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM sequence_executions WHERE plan_entry_id = :id AND status IN ('RUNNING', 'PAUSED') LIMIT 1)",
    )
    abstract fun hasLiveSequence(id: String): Boolean

    @Query(
        "UPDATE plan_entries SET status = 'CANCELLED', cancelled_at_ms = :atMs, fulfilled_at_ms = NULL, updated_at_ms = :atMs WHERE id = :id AND status = 'PLANNED'",
    )
    abstract fun cancel(
        id: String,
        atMs: Long,
    ): Int

    @Query(
        "UPDATE plan_entries SET status = 'PLANNED', cancelled_at_ms = NULL, fulfilled_at_ms = NULL, updated_at_ms = :atMs WHERE id = :id AND status = 'CANCELLED'",
    )
    abstract fun restore(
        id: String,
        atMs: Long,
    ): Int

    @Query(
        "UPDATE plan_entries SET precision = :precision, planned_day = :plannedDay, planned_week_start = :plannedWeekStart, planned_month = :plannedMonth, scheduled_instant_ms = :scheduledInstantMs, creation_zone_id = :creationZoneId, updated_at_ms = :atMs WHERE id = :id AND status = 'PLANNED'",
    )
    abstract fun reschedule(
        id: String,
        precision: String,
        plannedDay: String?,
        plannedWeekStart: String?,
        plannedMonth: String?,
        scheduledInstantMs: Long?,
        creationZoneId: String?,
        atMs: Long,
    ): Int

    @Query(
        "UPDATE plan_entries SET activity_snapshot_id = :snapshotId, source_revision = :sourceRevision, updated_at_ms = :atMs WHERE id = :id AND status = 'PLANNED' AND activity_snapshot_id = :expectedSnapshotId",
    )
    abstract fun replaceActivitySnapshot(
        id: String,
        expectedSnapshotId: String,
        snapshotId: String,
        sourceRevision: Long,
        atMs: Long,
    ): Int

    @Query(
        "UPDATE plan_entries SET sequence_plan_snapshot_id = :snapshotId, source_revision = :sourceRevision, updated_at_ms = :atMs WHERE id = :id AND status = 'PLANNED' AND sequence_plan_snapshot_id = :expectedSnapshotId",
    )
    abstract fun replaceSequenceSnapshot(
        id: String,
        expectedSnapshotId: String,
        snapshotId: String,
        sourceRevision: Long,
        atMs: Long,
    ): Int

    @Query(
        "UPDATE plan_entries SET status = 'FULFILLED', fulfilled_activity_execution_id = :executionId, fulfilled_sequence_execution_id = NULL, fulfilled_at_ms = :atMs, cancelled_at_ms = NULL, updated_at_ms = :atMs WHERE id = :id AND status = 'PLANNED' AND trackable_kind = 'ACTIVITY' AND activity_snapshot_id = :snapshotId",
    )
    abstract fun fulfillActivity(
        id: String,
        snapshotId: String,
        executionId: String,
        atMs: Long,
    ): Int

    @Query(
        "UPDATE plan_entries SET status = 'FULFILLED', fulfilled_sequence_execution_id = :executionId, fulfilled_activity_execution_id = NULL, fulfilled_at_ms = :atMs, cancelled_at_ms = NULL, updated_at_ms = :atMs WHERE id = :id AND status = 'PLANNED' AND trackable_kind = 'SEQUENCE' AND sequence_plan_snapshot_id = :snapshotId",
    )
    abstract fun fulfillSequence(
        id: String,
        snapshotId: String,
        executionId: String,
        atMs: Long,
    ): Int

    @Query("SELECT EXISTS(SELECT 1 FROM plan_entries WHERE activity_snapshot_id = :id LIMIT 1)")
    abstract fun hasActivityPlanReference(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM plan_entries WHERE sequence_plan_snapshot_id = :id LIMIT 1)")
    abstract fun hasSequencePlanReference(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sequence_nodes WHERE activity_snapshot_id = :id LIMIT 1)")
    abstract fun hasSequenceNodeReference(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sequence_snapshot_nodes WHERE activity_snapshot_id = :id LIMIT 1)")
    abstract fun hasSequenceSnapshotNodeReference(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM activity_executions WHERE snapshot_id = :id LIMIT 1)")
    abstract fun hasActivityExecutionReference(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sequence_occurrences WHERE activity_snapshot_id = :id LIMIT 1)")
    abstract fun hasSequenceOccurrenceReference(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sequence_executions WHERE snapshot_id = :id LIMIT 1)")
    abstract fun hasSequenceExecutionReference(id: String): Boolean

    @Query("SELECT id, name, short_comment FROM activity_snapshots WHERE id IN (:ids)")
    abstract fun activitySummaries(ids: List<String>): List<PlanSnapshotSummaryRow>

    @Query("SELECT id, name, short_comment FROM sequence_snapshots WHERE id IN (:ids)")
    abstract fun sequenceSummaries(ids: List<String>): List<PlanSnapshotSummaryRow>

    @Query("SELECT id, revision, deleted_at_ms FROM activity_templates WHERE id IN (:ids)")
    abstract fun activitySources(ids: List<String>): List<PlanSourceMetadataRow>

    @Query("SELECT id, revision, deleted_at_ms FROM sequence_templates WHERE id IN (:ids)")
    abstract fun sequenceSources(ids: List<String>): List<PlanSourceMetadataRow>

    @Query("UPDATE activity_template_user_state SET last_used_at_ms = :atMs WHERE activity_template_id = :id")
    abstract fun touchActivitySource(
        id: String,
        atMs: Long,
    ): Int

    @Query("UPDATE sequence_template_user_state SET last_used_at_ms = :atMs WHERE sequence_template_id = :id")
    abstract fun touchSequenceSource(
        id: String,
        atMs: Long,
    ): Int
}
