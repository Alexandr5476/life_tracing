package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.alexandr5476.lifetracing.domain.ActivityExecutionDurationCalculator
import com.alexandr5476.lifetracing.domain.ActivityExecutionPause
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import java.time.Instant

internal data class ActivityExecutionAggregateEntity(
    val execution: ActivityExecutionEntity,
    val pauses: List<ActivityExecutionPauseEntity> = emptyList(),
    val values: List<ActivityExecutionFieldValueEntity> = emptyList(),
)

internal data class SequenceOccurrenceLinkRow(
    @androidx.room.ColumnInfo(name = "sequence_execution_id") val sequenceExecutionId: String,
    @androidx.room.ColumnInfo(name = "activity_snapshot_id") val activitySnapshotId: String,
)

internal data class ActivitySnapshotExecutionMetadataRow(
    val id: String,
    @androidx.room.ColumnInfo(name = "time_tracking_mode") val timeTrackingMode: String,
    @androidx.room.ColumnInfo(name = "statistics_series_id") val statisticsSeriesId: String?,
)

@Dao
@Suppress("TooManyFunctions") // Atomic aggregate state transitions belong together.
internal abstract class ActivityExecutionDao {
    @Query("SELECT * FROM activity_executions WHERE id = :id")
    abstract fun getById(id: String): ActivityExecutionEntity?

    @Query("SELECT * FROM activity_executions WHERE sequence_occurrence_id = :occurrenceId")
    protected abstract fun getByOccurrence(occurrenceId: String): ActivityExecutionEntity?

    @Query(
        "SELECT * FROM activity_execution_pauses " +
            "WHERE activity_execution_id = :executionId ORDER BY started_at_ms, id",
    )
    abstract fun getPauses(executionId: String): List<ActivityExecutionPauseEntity>

    @Query(
        "SELECT * FROM activity_execution_field_values " +
            "WHERE activity_execution_id = :executionId ORDER BY snapshot_field_id",
    )
    abstract fun getValues(executionId: String): List<ActivityExecutionFieldValueEntity>

    @Query(
        "SELECT field_type FROM activity_snapshot_fields " +
            "WHERE snapshot_id = :snapshotId AND id = :snapshotFieldId",
    )
    protected abstract fun getSnapshotFieldType(
        snapshotId: String,
        snapshotFieldId: String,
    ): String?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM activity_snapshot_category_options " +
            "WHERE snapshot_field_id = :snapshotFieldId AND id = :categoryOptionId)",
    )
    protected abstract fun categoryOptionBelongsToField(
        snapshotFieldId: String,
        categoryOptionId: String,
    ): Boolean

    @Query("SELECT id, time_tracking_mode, statistics_series_id FROM activity_snapshots WHERE id = :snapshotId")
    protected abstract fun getSnapshotExecutionMetadata(snapshotId: String): ActivitySnapshotExecutionMetadataRow?

    @Query("SELECT sequence_execution_id, activity_snapshot_id FROM sequence_occurrences WHERE id = :id")
    protected abstract fun getSequenceOccurrenceLink(id: String): SequenceOccurrenceLinkRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertExecutionUnchecked(execution: ActivityExecutionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertPausesUnchecked(pauses: List<ActivityExecutionPauseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertValuesUnchecked(values: List<ActivityExecutionFieldValueEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertPauseUnchecked(pause: ActivityExecutionPauseEntity)

    @Upsert
    protected abstract fun upsertValueUnchecked(value: ActivityExecutionFieldValueEntity)

    @Upsert
    protected abstract fun upsertExecutionUnchecked(execution: ActivityExecutionEntity)

    @Upsert
    protected abstract fun upsertPauseUnchecked(pause: ActivityExecutionPauseEntity)

    @Query(
        "UPDATE activity_executions SET status = :status, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND status = :expectedStatus",
    )
    protected abstract fun updateStatusUnchecked(
        id: String,
        expectedStatus: String,
        status: String,
        updatedAtMs: Long,
    ): Int

    @Query("UPDATE activity_execution_pauses SET ended_at_ms = :endedAtMs WHERE id = :id AND ended_at_ms IS NULL")
    protected abstract fun closePauseUnchecked(
        id: String,
        endedAtMs: Long,
    ): Int

    @Query(
        "UPDATE activity_executions SET status = 'COMPLETED', completed_at_ms = :completedAtMs, " +
            "active_duration_ms = :activeDurationMs, completion_reason = NULL, " +
            "updated_at_ms = :completedAtMs WHERE id = :id AND status IN ('RUNNING', 'PAUSED')",
    )
    protected abstract fun markCompletedUnchecked(
        id: String,
        completedAtMs: Long,
        activeDurationMs: Long,
    ): Int

    @Query("UPDATE activity_executions SET deleted_at_ms = :deletedAtMs, updated_at_ms = :deletedAtMs WHERE id = :id")
    protected abstract fun softDeleteUnchecked(
        id: String,
        deletedAtMs: Long,
    ): Int

    @Query("UPDATE activity_executions SET deleted_at_ms = NULL, updated_at_ms = :restoredAtMs WHERE id = :id")
    protected abstract fun restoreUnchecked(
        id: String,
        restoredAtMs: Long,
    ): Int

    @Query("DELETE FROM activity_executions WHERE id = :id")
    protected abstract fun hardDeleteUnchecked(id: String): Int

    @Transaction
    open fun insertAggregate(aggregate: ActivityExecutionAggregateEntity) {
        insertExecutionUnchecked(aggregate.execution)
        requireValidAggregate(aggregate)
        if (aggregate.pauses.isNotEmpty()) insertPausesUnchecked(aggregate.pauses)
        if (aggregate.values.isNotEmpty()) insertValuesUnchecked(aggregate.values)
    }

    @Transaction
    open fun upsertValue(value: ActivityExecutionFieldValueEntity) {
        val execution =
            requireNotNull(getById(value.activityExecutionId)) {
                "Unknown execution: ${value.activityExecutionId}"
            }
        requireValidValue(execution.snapshotId, value)
        upsertValueUnchecked(value)
    }

    @Transaction
    open fun getAggregate(id: String): ActivityExecutionAggregateEntity? {
        val execution = getById(id) ?: return null
        return ActivityExecutionAggregateEntity(execution, getPauses(id), getValues(id)).also(::requireValidAggregate)
    }

    @Transaction
    open fun getAggregateByOccurrence(occurrenceId: String): ActivityExecutionAggregateEntity? =
        getByOccurrence(occurrenceId)?.let { execution ->
            ActivityExecutionAggregateEntity(
                execution,
                getPauses(execution.id),
                getValues(execution.id),
            ).also(::requireValidAggregate)
        }

    @Transaction
    open fun upsertSequenceChildAggregate(aggregate: ActivityExecutionAggregateEntity) {
        require(aggregate.execution.contextType == "SEQUENCE_CHILD") {
            "Coordinated child persistence only accepts SEQUENCE_CHILD"
        }
        requireValidAggregate(aggregate)
        upsertExecutionUnchecked(aggregate.execution)
        aggregate.pauses.forEach(::upsertPauseUnchecked)
        aggregate.values.forEach(::upsertValueUnchecked)
    }

    @Transaction
    open fun softDelete(
        id: String,
        deletedAtMs: Long,
    ): Int {
        requireStandaloneMutation(id) ?: return 0
        return softDeleteUnchecked(id, deletedAtMs)
    }

    @Transaction
    open fun restore(
        id: String,
        restoredAtMs: Long,
    ): Int {
        requireStandaloneMutation(id) ?: return 0
        return restoreUnchecked(id, restoredAtMs)
    }

    @Transaction
    open fun hardDelete(id: String): Int {
        requireStandaloneMutation(id) ?: return 0
        return hardDeleteUnchecked(id)
    }

    @Transaction
    open fun pause(
        id: String,
        pauseId: String,
        atMs: Long,
    ) {
        val execution = requireNotNull(getById(id)) { "Unknown execution: $id" }
        requireStandaloneMutation(execution)
        requireTimedSnapshot(execution.snapshotId)
        require(execution.status == "RUNNING" && execution.startedAtMs != null) { "Only a running execution can pause" }
        require(atMs >= execution.startedAtMs && atMs >= execution.updatedAtMs) { "Pause time is out of order" }
        require(getPauses(id).none { it.endedAtMs == null }) { "Execution already has an open pause" }
        insertPauseUnchecked(ActivityExecutionPauseEntity(pauseId, id, atMs, null))
        check(updateStatusUnchecked(id, "RUNNING", "PAUSED", atMs) == 1)
    }

    @Transaction
    open fun resume(
        id: String,
        atMs: Long,
    ) {
        val execution = requireNotNull(getById(id)) { "Unknown execution: $id" }
        requireStandaloneMutation(execution)
        requireTimedSnapshot(execution.snapshotId)
        require(execution.status == "PAUSED" && atMs >= execution.updatedAtMs) {
            "Only a paused execution can resume in order"
        }
        val pause = getPauses(id).single { it.endedAtMs == null }
        require(atMs >= pause.startedAtMs) { "Resume cannot precede pause" }
        check(closePauseUnchecked(pause.id, atMs) == 1)
        check(updateStatusUnchecked(id, "PAUSED", "RUNNING", atMs) == 1)
    }

    @Transaction
    open fun complete(
        id: String,
        atMs: Long,
    ) {
        val execution = requireNotNull(getById(id)) { "Unknown execution: $id" }
        requireStandaloneMutation(execution)
        requireTimedSnapshot(execution.snapshotId)
        val startedAtMs = requireNotNull(execution.startedAtMs) { "Timed completion requires a start" }
        require(execution.status == "RUNNING" || execution.status == "PAUSED") { "Execution is not active" }
        require(atMs >= execution.updatedAtMs && atMs >= startedAtMs) { "Completion time is out of order" }
        getPauses(id).singleOrNull { it.endedAtMs == null }?.let { pause ->
            check(closePauseUnchecked(pause.id, atMs) == 1)
        }
        val pauses =
            getPauses(id).map { pause ->
                ActivityExecutionPause(
                    ActivityExecutionPauseId(pause.id),
                    Instant.ofEpochMilli(pause.startedAtMs),
                    pause.endedAtMs?.let(Instant::ofEpochMilli),
                )
            }
        val duration =
            ActivityExecutionDurationCalculator.calculate(
                Instant.ofEpochMilli(startedAtMs),
                Instant.ofEpochMilli(atMs),
                pauses,
            )
        check(markCompletedUnchecked(id, atMs, duration.toMillis()) == 1)
    }

    private fun requireValidAggregate(aggregate: ActivityExecutionAggregateEntity) {
        val execution = aggregate.execution
        when (execution.contextType) {
            "STANDALONE" ->
                require(execution.sequenceExecutionId == null && execution.sequenceOccurrenceId == null) {
                    "Standalone execution cannot reference Sequence ownership"
                }
            "SEQUENCE_CHILD" -> {
                val sequenceExecutionId =
                    requireNotNull(execution.sequenceExecutionId) { "Sequence child requires its parent execution" }
                val occurrenceId =
                    requireNotNull(execution.sequenceOccurrenceId) { "Sequence child requires its occurrence" }
                require(execution.completionReason == null) {
                    "Normal Sequence child completion reason belongs to the occurrence"
                }
                val occurrence =
                    requireNotNull(
                        getSequenceOccurrenceLink(occurrenceId),
                    ) { "Unknown Sequence occurrence: $occurrenceId" }
                require(
                    occurrence.sequenceExecutionId == sequenceExecutionId &&
                        occurrence.activitySnapshotId == execution.snapshotId,
                ) { "Sequence child must match its occurrence parent and Activity snapshot" }
            }
            else -> throw IllegalArgumentException("Unknown execution context: ${execution.contextType}")
        }
        requireValidOwnedRows(aggregate)
        val snapshot =
            requireNotNull(getSnapshotExecutionMetadata(execution.snapshotId)) {
                "Unknown snapshot: ${execution.snapshotId}"
            }
        requireValidStatisticsSeries(execution, snapshot)
        when (snapshot.timeTrackingMode) {
            "STOPWATCH", "TIMER" -> requireValidTimedAggregate(execution, aggregate.pauses)
            "NO_LIVE_TRACKING" ->
                require(
                    execution.status == "COMPLETED" &&
                        execution.startedAtMs == null &&
                        execution.completedAtMs != null &&
                        execution.activeDurationMs == null &&
                        aggregate.pauses.isEmpty(),
                ) { "NO_LIVE_TRACKING requires an immediate completed execution without duration or pauses" }
            else -> throw IllegalArgumentException("Unknown snapshot time tracking mode: ${snapshot.timeTrackingMode}")
        }
    }

    private fun requireValidStatisticsSeries(
        execution: ActivityExecutionEntity,
        snapshot: ActivitySnapshotExecutionMetadataRow,
    ) {
        val expectedStatisticsSeriesId =
            if (execution.contextType == "SEQUENCE_CHILD") {
                snapshot.statisticsSeriesId
            } else {
                snapshot.statisticsSeriesId ?: ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value
            }
        require(execution.statisticsSeriesId == expectedStatisticsSeriesId) {
            "Execution StatisticsSeries must match its snapshot and context"
        }
    }

    private fun requireValidOwnedRows(aggregate: ActivityExecutionAggregateEntity) {
        aggregate.pauses.forEach { pause ->
            require(pause.activityExecutionId == aggregate.execution.id) {
                "Execution pause must belong to the inserted execution"
            }
        }
        aggregate.values.forEach { value ->
            require(value.activityExecutionId == aggregate.execution.id) {
                "Execution value must belong to the inserted execution"
            }
            requireValidValue(aggregate.execution.snapshotId, value)
        }
    }

    private fun requireValidTimedAggregate(
        execution: ActivityExecutionEntity,
        pauses: List<ActivityExecutionPauseEntity>,
    ) {
        val startedAtMs = requireNotNull(execution.startedAtMs) { "Timed execution requires a start" }
        val openPauses = pauses.count { it.endedAtMs == null }
        when (execution.status) {
            "RUNNING" ->
                require(execution.completedAtMs == null && execution.activeDurationMs == null && openPauses == 0) {
                    "Running timed execution cannot have completion data or an open pause"
                }
            "PAUSED" ->
                require(execution.completedAtMs == null && execution.activeDurationMs == null && openPauses == 1) {
                    "Paused timed execution requires exactly one open pause and no completion data"
                }
            "COMPLETED" ->
                require(execution.completedAtMs != null && execution.activeDurationMs != null && openPauses == 0) {
                    "Completed timed execution requires completion, duration, and no open pause"
                }
            else -> throw IllegalArgumentException("Unknown execution status: ${execution.status}")
        }
        val timelineEndMs = execution.completedAtMs ?: execution.updatedAtMs
        val duration =
            ActivityExecutionDurationCalculator
                .calculate(
                    Instant.ofEpochMilli(startedAtMs),
                    Instant.ofEpochMilli(timelineEndMs),
                    pauses.map { pause ->
                        ActivityExecutionPause(
                            ActivityExecutionPauseId(pause.id),
                            Instant.ofEpochMilli(pause.startedAtMs),
                            Instant.ofEpochMilli(pause.endedAtMs ?: timelineEndMs),
                        )
                    },
                ).toMillis()
        if (execution.status == "COMPLETED") {
            require(execution.activeDurationMs == duration) {
                "Stored active duration must match elapsed time minus pauses"
            }
        }
    }

    private fun requireTimedSnapshot(snapshotId: String) {
        val mode = getSnapshotExecutionMetadata(snapshotId)?.timeTrackingMode
        require(mode == "STOPWATCH" || mode == "TIMER") {
            "Live transitions require a timed snapshot"
        }
    }

    private fun requireStandaloneMutation(id: String): ActivityExecutionEntity? =
        getById(id)?.also(::requireStandaloneMutation)

    private fun requireStandaloneMutation(execution: ActivityExecutionEntity) {
        require(execution.contextType == "STANDALONE") {
            "Sequence child mutations require a coordinated Sequence transaction"
        }
    }

    private fun requireValidValue(
        snapshotId: String,
        value: ActivityExecutionFieldValueEntity,
    ) {
        val fieldType =
            requireNotNull(getSnapshotFieldType(snapshotId, value.snapshotFieldId)) {
                "Execution value field must belong to its execution snapshot"
            }
        when {
            value.numberScaled != null && value.categoryOptionId == null && value.textValue == null ->
                require(fieldType == "NUMBER") { "Number value requires a NUMBER snapshot field" }
            value.numberScaled == null && value.categoryOptionId != null && value.textValue == null -> {
                require(fieldType == "CATEGORY") { "Category value requires a CATEGORY snapshot field" }
                require(categoryOptionBelongsToField(value.snapshotFieldId, value.categoryOptionId)) {
                    "Category option must belong to the value snapshot field"
                }
            }
            value.numberScaled == null && value.categoryOptionId == null && value.textValue != null ->
                require(fieldType == "TEXT") { "Text value requires a TEXT snapshot field" }
            else -> throw IllegalArgumentException("Execution field value must contain exactly one typed value")
        }
    }
}
