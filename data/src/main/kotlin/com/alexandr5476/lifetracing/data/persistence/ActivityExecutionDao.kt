package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.alexandr5476.lifetracing.domain.ActivityCompletionReason
import com.alexandr5476.lifetracing.domain.ActivityExecutionDurationCalculator
import com.alexandr5476.lifetracing.domain.ActivityExecutionPause
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import java.time.Instant

internal data class ActivityExecutionAggregateEntity(
    val execution: ActivityExecutionEntity,
    val pauses: List<ActivityExecutionPauseEntity> = emptyList(),
    val values: List<ActivityExecutionFieldValueEntity> = emptyList(),
)

@Dao
@Suppress("TooManyFunctions") // Atomic aggregate state transitions belong together.
internal abstract class ActivityExecutionDao {
    @Query("SELECT * FROM activity_executions WHERE id = :id")
    abstract fun getById(id: String): ActivityExecutionEntity?

    @Query("SELECT * FROM activity_execution_pauses WHERE execution_id = :executionId ORDER BY started_at_ms, id")
    abstract fun getPauses(executionId: String): List<ActivityExecutionPauseEntity>

    @Query("SELECT * FROM activity_execution_field_values WHERE execution_id = :executionId ORDER BY snapshot_field_id")
    abstract fun getValues(executionId: String): List<ActivityExecutionFieldValueEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertExecution(execution: ActivityExecutionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertPauses(pauses: List<ActivityExecutionPauseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertValues(values: List<ActivityExecutionFieldValueEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertPause(pause: ActivityExecutionPauseEntity)

    @Upsert
    abstract fun upsertValue(value: ActivityExecutionFieldValueEntity)

    @Query(
        "UPDATE activity_executions SET status = :status, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND status = :expectedStatus",
    )
    abstract fun updateStatus(
        id: String,
        expectedStatus: String,
        status: String,
        updatedAtMs: Long,
    ): Int

    @Query("UPDATE activity_execution_pauses SET ended_at_ms = :endedAtMs WHERE id = :id AND ended_at_ms IS NULL")
    abstract fun closePause(
        id: String,
        endedAtMs: Long,
    ): Int

    @Query(
        "UPDATE activity_executions SET status = 'COMPLETED', completed_at_ms = :completedAtMs, " +
            "active_duration_ms = :activeDurationMs, completion_reason = :completionReason, " +
            "updated_at_ms = :completedAtMs WHERE id = :id AND status IN ('RUNNING', 'PAUSED')",
    )
    abstract fun markCompleted(
        id: String,
        completedAtMs: Long,
        activeDurationMs: Long,
        completionReason: String?,
    ): Int

    @Query("UPDATE activity_executions SET deleted_at_ms = :deletedAtMs, updated_at_ms = :deletedAtMs WHERE id = :id")
    abstract fun softDelete(
        id: String,
        deletedAtMs: Long,
    ): Int

    @Query("UPDATE activity_executions SET deleted_at_ms = NULL, updated_at_ms = :restoredAtMs WHERE id = :id")
    abstract fun restore(
        id: String,
        restoredAtMs: Long,
    ): Int

    @Query("DELETE FROM activity_executions WHERE id = :id")
    abstract fun hardDelete(id: String): Int

    @Transaction
    open fun insertAggregate(aggregate: ActivityExecutionAggregateEntity) {
        insertExecution(aggregate.execution)
        if (aggregate.pauses.isNotEmpty()) insertPauses(aggregate.pauses)
        if (aggregate.values.isNotEmpty()) insertValues(aggregate.values)
    }

    @Transaction
    open fun getAggregate(id: String): ActivityExecutionAggregateEntity? {
        val execution = getById(id) ?: return null
        return ActivityExecutionAggregateEntity(execution, getPauses(id), getValues(id))
    }

    @Transaction
    open fun pause(
        id: String,
        pauseId: String,
        atMs: Long,
    ) {
        val execution = requireNotNull(getById(id)) { "Unknown execution: $id" }
        require(execution.status == "RUNNING" && execution.startedAtMs != null) { "Only a running execution can pause" }
        require(atMs >= execution.startedAtMs && atMs >= execution.updatedAtMs) { "Pause time is out of order" }
        require(getPauses(id).none { it.endedAtMs == null }) { "Execution already has an open pause" }
        insertPause(ActivityExecutionPauseEntity(pauseId, id, atMs, null))
        check(updateStatus(id, "RUNNING", "PAUSED", atMs) == 1)
    }

    @Transaction
    open fun resume(
        id: String,
        atMs: Long,
    ) {
        val execution = requireNotNull(getById(id)) { "Unknown execution: $id" }
        require(execution.status == "PAUSED" && atMs >= execution.updatedAtMs) {
            "Only a paused execution can resume in order"
        }
        val pause = getPauses(id).single { it.endedAtMs == null }
        require(atMs >= pause.startedAtMs) { "Resume cannot precede pause" }
        check(closePause(pause.id, atMs) == 1)
        check(updateStatus(id, "PAUSED", "RUNNING", atMs) == 1)
    }

    @Transaction
    open fun complete(
        id: String,
        atMs: Long,
        completionReason: ActivityCompletionReason? = null,
    ) {
        val execution = requireNotNull(getById(id)) { "Unknown execution: $id" }
        val startedAtMs = requireNotNull(execution.startedAtMs) { "Timed completion requires a start" }
        require(execution.status == "RUNNING" || execution.status == "PAUSED") { "Execution is not active" }
        require(atMs >= execution.updatedAtMs && atMs >= startedAtMs) { "Completion time is out of order" }
        getPauses(id).singleOrNull { it.endedAtMs == null }?.let { pause -> check(closePause(pause.id, atMs) == 1) }
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
        check(markCompleted(id, atMs, duration.toMillis(), completionReason?.name) == 1)
    }
}
