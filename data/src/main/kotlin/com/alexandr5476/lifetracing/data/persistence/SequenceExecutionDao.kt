@file:Suppress(
    "LongMethod",
    "LongParameterList",
    "MaxLineLength",
) // Explicit owner-scoped SQL and its atomic delta stay visible at the persistence boundary.

package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshotValidator
import com.alexandr5476.lifetracing.domain.SequenceExecutionValidator
import com.alexandr5476.lifetracing.domain.TimeTrackingMode

internal data class SequenceExecutionAggregateEntity(
    val execution: SequenceExecutionEntity,
    val occurrences: List<SequenceOccurrenceEntity> = emptyList(),
    val intervals: List<SequenceIntervalEntity> = emptyList(),
    val values: List<SequenceExecutionFieldValueEntity> = emptyList(),
)

internal data class SequenceHistoryRootEntity(
    val id: String,
    @androidx.room.ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @androidx.room.ColumnInfo(name = "plan_entry_id") val planEntryId: String?,
    val status: String,
    @androidx.room.ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @androidx.room.ColumnInfo(name = "ended_at_ms") val endedAtMs: Long,
    @androidx.room.ColumnInfo(name = "active_duration_ms") val activeDurationMs: Long?,
    @androidx.room.ColumnInfo(name = "pause_duration_ms") val pauseDurationMs: Long?,
    @androidx.room.ColumnInfo(name = "wall_duration_ms") val wallDurationMs: Long?,
    @androidx.room.ColumnInfo(name = "primary_local_date") val primaryLocalDate: String,
)

@Dao
@Suppress("TooManyFunctions") // One focused DAO owns one bounded aggregate and its validation metadata.
internal abstract class SequenceExecutionDao {
    @Query("SELECT * FROM sequence_executions WHERE id = :id")
    abstract fun getById(id: String): SequenceExecutionEntity?

    @Query(
        "SELECT id, snapshot_id, plan_entry_id, status, started_at_ms, ended_at_ms, active_duration_ms, " +
            "pause_duration_ms, wall_duration_ms, primary_local_date FROM sequence_executions " +
            "WHERE status IN ('COMPLETED', 'ENDED_EARLY') " +
            "AND primary_local_date BETWEEN :startDate AND :endDate " +
            "ORDER BY primary_local_date DESC, ended_at_ms DESC, id ASC LIMIT :limit",
    )
    abstract fun getTerminalHistoryRoots(
        startDate: String,
        endDate: String,
        limit: Int,
    ): List<SequenceHistoryRootEntity>

    @Query(
        "SELECT * FROM sequence_occurrences WHERE sequence_execution_id = :executionId ORDER BY runtime_position, id",
    )
    abstract fun getOccurrences(executionId: String): List<SequenceOccurrenceEntity>

    @Query("SELECT * FROM sequence_intervals WHERE sequence_execution_id = :executionId ORDER BY started_at_ms, id")
    abstract fun getIntervals(executionId: String): List<SequenceIntervalEntity>

    @Query(
        "SELECT * FROM sequence_execution_field_values WHERE sequence_execution_id = :executionId ORDER BY snapshot_field_id",
    )
    abstract fun getValues(executionId: String): List<SequenceExecutionFieldValueEntity>

    @Query("SELECT * FROM sequence_snapshots WHERE id = :id")
    protected abstract fun getSnapshot(id: String): SequenceSnapshotEntity?

    @Query("SELECT * FROM sequence_snapshot_settings WHERE sequence_snapshot_id = :id")
    protected abstract fun getSnapshotSettings(id: String): SequenceSnapshotSettingsEntity?

    @Query("SELECT * FROM sequence_snapshot_fields WHERE sequence_snapshot_id = :id ORDER BY position, id")
    protected abstract fun getSnapshotFields(id: String): List<SequenceSnapshotFieldEntity>

    @Query(
        "SELECT options.* FROM sequence_snapshot_category_options AS options INNER JOIN sequence_snapshot_fields AS fields ON fields.id = options.sequence_snapshot_field_id WHERE fields.sequence_snapshot_id = :id ORDER BY fields.position, options.position, options.id",
    )
    protected abstract fun getSnapshotOptions(id: String): List<SequenceSnapshotCategoryOptionEntity>

    @Query(
        "SELECT * FROM sequence_snapshot_nodes WHERE sequence_snapshot_id = :id ORDER BY parent_repeat_node_id, position, id",
    )
    protected abstract fun getSnapshotNodes(id: String): List<SequenceSnapshotNodeEntity>

    @Query(
        "SELECT overrides.* FROM sequence_snapshot_step_overrides AS overrides INNER JOIN sequence_snapshot_nodes AS nodes ON nodes.id = overrides.sequence_snapshot_node_id WHERE nodes.sequence_snapshot_id = :id ORDER BY overrides.sequence_snapshot_node_id",
    )
    protected abstract fun getSnapshotOverrides(id: String): List<SequenceSnapshotStepOverrideEntity>

    @Query("SELECT id, time_tracking_mode FROM activity_snapshots WHERE id IN (:ids)")
    protected abstract fun activitySnapshotModes(ids: List<String>): List<ActivitySnapshotModeRow>

    @Query(
        "SELECT trackable_kind, activity_snapshot_id, sequence_plan_snapshot_id, status, " +
            "fulfilled_activity_execution_id FROM plan_entries WHERE id = :id",
    )
    protected abstract fun getPlanLink(id: String): ExecutionPlanLinkRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertExecutionUnchecked(execution: SequenceExecutionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertOccurrencesUnchecked(occurrences: List<SequenceOccurrenceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertIntervalsUnchecked(intervals: List<SequenceIntervalEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertValuesUnchecked(values: List<SequenceExecutionFieldValueEntity>)

    @Upsert
    protected abstract fun upsertValueUnchecked(value: SequenceExecutionFieldValueEntity)

    @Query(
        "UPDATE sequence_executions SET status = :status, ended_at_ms = :endedAtMs, " +
            "active_duration_ms = :activeDurationMs, pause_duration_ms = :pauseDurationMs, " +
            "wall_duration_ms = :wallDurationMs, current_occurrence_id = :currentOccurrenceId, " +
            "updated_at_ms = :updatedAtMs WHERE id = :id",
    )
    protected abstract fun updateRuntimeRootUnchecked(
        id: String,
        status: String,
        endedAtMs: Long?,
        activeDurationMs: Long?,
        pauseDurationMs: Long?,
        wallDurationMs: Long?,
        currentOccurrenceId: String?,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE sequence_occurrences SET status = :status, entered_at_ms = :enteredAtMs, " +
            "completed_at_ms = :completedAtMs, completion_reason = :completionReason " +
            "WHERE id = :id AND sequence_execution_id = :executionId",
    )
    protected abstract fun updateRuntimeOccurrenceUnchecked(
        id: String,
        executionId: String,
        status: String,
        enteredAtMs: Long?,
        completedAtMs: Long?,
        completionReason: String?,
    ): Int

    @Query(
        "UPDATE sequence_occurrences SET runtime_position = :runtimePosition " +
            "WHERE id = :id AND sequence_execution_id = :executionId",
    )
    protected abstract fun updateRuntimePositionUnchecked(
        id: String,
        executionId: String,
        runtimePosition: Int,
    ): Int

    @Query(
        "UPDATE sequence_intervals SET ended_at_ms = :endedAtMs " +
            "WHERE id = :id AND sequence_execution_id = :executionId AND ended_at_ms IS NULL",
    )
    protected abstract fun closeRuntimeIntervalUnchecked(
        id: String,
        executionId: String,
        endedAtMs: Long,
    ): Int

    @Query(
        "UPDATE sequence_executions SET current_occurrence_id = :occurrenceId WHERE id = :executionId AND current_occurrence_id IS NULL",
    )
    protected abstract fun setCurrentOccurrenceUnchecked(
        executionId: String,
        occurrenceId: String,
    ): Int

    @Query("DELETE FROM sequence_executions WHERE id = :id")
    abstract fun hardDelete(id: String): Int

    @Transaction
    open fun insertAggregate(aggregate: SequenceExecutionAggregateEntity) {
        require(aggregate.occurrences.all { it.isRuntimeAdded || it.sourceSequenceSnapshotNodeId != null }) {
            "New snapshot-derived occurrences require their frozen source Step"
        }
        require(
            aggregate.intervals.all {
                (it.kind != "ACTIVE_STEP" && it.kind != "STEP_PAUSE") || it.occurrenceId != null
            },
        ) { "New Step-classified intervals require an occurrence" }
        requireValidAggregate(aggregate)
        val current = aggregate.execution.currentOccurrenceId
        insertExecutionUnchecked(aggregate.execution.copy(currentOccurrenceId = null))
        if (aggregate.occurrences.isNotEmpty()) insertOccurrencesUnchecked(aggregate.occurrences)
        if (current != null) check(setCurrentOccurrenceUnchecked(aggregate.execution.id, current) == 1)
        if (aggregate.intervals.isNotEmpty()) insertIntervalsUnchecked(aggregate.intervals)
        if (aggregate.values.isNotEmpty()) insertValuesUnchecked(aggregate.values)
    }

    @Transaction
    open fun getAggregate(id: String): SequenceExecutionAggregateEntity? {
        val execution = getById(id) ?: return null
        return SequenceExecutionAggregateEntity(
            execution,
            getOccurrences(id),
            getIntervals(id),
            getValues(id),
        ).also(::requireValidAggregate)
    }

    @Transaction
    open fun persistRuntimeDelta(
        before: SequenceExecutionAggregateEntity,
        after: SequenceExecutionAggregateEntity,
    ) {
        requireValidAggregate(after)
        require(before.execution.stableIdentity() == after.execution.stableIdentity()) {
            "Runtime root identity cannot change"
        }
        val beforeOccurrences = before.occurrences.associateBy(SequenceOccurrenceEntity::id)
        val afterOccurrences = after.occurrences.associateBy(SequenceOccurrenceEntity::id)
        require(afterOccurrences.keys.containsAll(beforeOccurrences.keys)) {
            "Runtime transitions cannot remove occurrences"
        }
        beforeOccurrences.forEach { (id, previous) ->
            val occurrence = afterOccurrences.getValue(id)
            require(previous.identity() == occurrence.identity()) { "Runtime occurrence identity cannot change" }
        }
        val newOccurrences = after.occurrences.filter { it.id !in beforeOccurrences }
        require(
            newOccurrences.all {
                if (it.isRuntimeAdded) {
                    it.sourceSequenceSnapshotNodeId == null &&
                        it.repeatSourceSnapshotNodeId == null &&
                        it.repeatIteration == null
                } else {
                    it.sourceSequenceSnapshotNodeId != null
                }
            },
        ) { "New runtime occurrences require valid runtime-added or frozen source identity" }

        persistRuntimePositions(before.occurrences, afterOccurrences)
        after.occurrences
            .filter { occurrence ->
                beforeOccurrences[occurrence.id]?.let { it.runtimeState() != occurrence.runtimeState() } == true
            }.sortedBy { if (it.status == "CURRENT") 1 else 0 }
            .forEach { occurrence ->
                check(
                    updateRuntimeOccurrenceUnchecked(
                        occurrence.id,
                        occurrence.sequenceExecutionId,
                        occurrence.status,
                        occurrence.enteredAtMs,
                        occurrence.completedAtMs,
                        occurrence.completionReason,
                    ) == 1,
                )
            }
        if (newOccurrences.isNotEmpty()) insertOccurrencesUnchecked(newOccurrences)
        val beforeIntervals = before.intervals.associateBy(SequenceIntervalEntity::id)
        after.intervals.forEach { interval ->
            val previous = beforeIntervals[interval.id]
            if (previous == null) {
                insertIntervalsUnchecked(listOf(interval))
            } else {
                require(previous.identity() == interval.identity()) { "Runtime interval identity cannot change" }
                if (previous != interval) {
                    require(previous.endedAtMs == null && interval.endedAtMs != null) {
                        "Existing runtime intervals may only be closed"
                    }
                    check(
                        closeRuntimeIntervalUnchecked(
                            interval.id,
                            interval.sequenceExecutionId,
                            requireNotNull(interval.endedAtMs),
                        ) == 1,
                    )
                }
            }
        }
        require(
            after.intervals
                .map(SequenceIntervalEntity::id)
                .toSet()
                .containsAll(beforeIntervals.keys),
        ) {
            "Runtime transitions cannot remove intervals"
        }
        val beforeValues = before.values.associateBy(SequenceExecutionFieldValueEntity::snapshotFieldId)
        require(beforeValues.keys == after.values.map(SequenceExecutionFieldValueEntity::snapshotFieldId).toSet()) {
            "Runtime transitions cannot add or remove Sequence values"
        }
        after.values.filter { beforeValues[it.snapshotFieldId] != it }.forEach(::upsertValueUnchecked)
        if (before.execution != after.execution) {
            val execution = after.execution
            check(
                updateRuntimeRootUnchecked(
                    execution.id,
                    execution.status,
                    execution.endedAtMs,
                    execution.activeDurationMs,
                    execution.pauseDurationMs,
                    execution.wallDurationMs,
                    execution.currentOccurrenceId,
                    execution.updatedAtMs,
                ) == 1,
            )
        }
    }

    private fun persistRuntimePositions(
        before: List<SequenceOccurrenceEntity>,
        after: Map<String, SequenceOccurrenceEntity>,
    ) {
        val current = before.associate { it.id to it.runtimePosition }.toMutableMap()
        val occupants = before.associate { it.runtimePosition to it.id }.toMutableMap()
        val targets = before.associate { it.id to after.getValue(it.id).runtimePosition }
        val targetOwners = targets.entries.associate { (id, position) -> position to id }
        val remaining = current.keys.filterTo(linkedSetOf()) { current.getValue(it) != targets.getValue(it) }
        if (remaining.isEmpty()) return

        val usedPositions = (current.values + after.values.map(SequenceOccurrenceEntity::runtimePosition)).toHashSet()
        var scratch = 0
        while (scratch in usedPositions) {
            require(scratch < Int.MAX_VALUE) { "No collision-free runtime position is available" }
            scratch++
        }

        fun move(
            id: String,
            position: Int,
        ) {
            val old = current.getValue(id)
            check(position !in occupants)
            check(updateRuntimePositionUnchecked(id, after.getValue(id).sequenceExecutionId, position) == 1)
            check(occupants.remove(old) == id)
            occupants[position] = id
            current[id] = position
        }

        val movable = java.util.ArrayDeque(remaining.filter { targets.getValue(it) !in occupants })
        while (movable.isNotEmpty()) {
            val id = movable.removeFirst()
            if (id !in remaining || targets.getValue(id) in occupants) continue
            val vacated = current.getValue(id)
            move(id, targets.getValue(id))
            remaining.remove(id)
            targetOwners[vacated]?.takeIf(remaining::contains)?.let(movable::addLast)
        }

        while (remaining.isNotEmpty()) {
            val start = remaining.first()
            var vacant = current.getValue(start)
            move(start, scratch)
            while (true) {
                val id = checkNotNull(targetOwners[vacant]) { "Invalid runtime-position cycle" }
                if (id == start) {
                    move(start, vacant)
                    remaining.remove(start)
                    break
                }
                check(id in remaining) { "Invalid runtime-position cycle" }
                val nextVacant = current.getValue(id)
                move(id, vacant)
                remaining.remove(id)
                vacant = nextVacant
            }
        }
    }

    private fun requireValidAggregate(aggregate: SequenceExecutionAggregateEntity) {
        val id = aggregate.execution.id
        require(aggregate.occurrences.all { it.sequenceExecutionId == id }) { "Occurrence owner mismatch" }
        require(aggregate.intervals.all { it.sequenceExecutionId == id }) { "Interval owner mismatch" }
        require(aggregate.values.all { it.sequenceExecutionId == id }) { "Sequence value owner mismatch" }
        val snapshotId = aggregate.execution.snapshotId
        aggregate.execution.planEntryId?.let { planId ->
            val plan = requireNotNull(getPlanLink(planId)) { "Unknown Plan: $planId" }
            require(plan.trackableKind == "SEQUENCE" && plan.sequencePlanSnapshotId == snapshotId) {
                "SequenceExecution Plan linkage must match kind and snapshot"
            }
        }
        val snapshotAggregate =
            SequenceSnapshotAggregateEntity(
                requireNotNull(getSnapshot(snapshotId)) { "Unknown Sequence snapshot: $snapshotId" },
                requireNotNull(
                    getSnapshotSettings(snapshotId),
                ) { "Sequence snapshot is missing settings: $snapshotId" },
                getSnapshotFields(snapshotId),
                getSnapshotOptions(snapshotId),
                getSnapshotNodes(snapshotId),
                getSnapshotOverrides(snapshotId),
            )
        val snapshot = snapshotAggregate.toDomain()
        val activitySnapshotIds =
            (
                snapshotAggregate.nodes.mapNotNull(SequenceSnapshotNodeEntity::activitySnapshotId) +
                    aggregate.occurrences.map(SequenceOccurrenceEntity::activitySnapshotId)
            ).distinct()
        val activitySnapshotModes =
            activitySnapshotIds.chunked(SQLITE_SAFE_BIND_COUNT).flatMap(::activitySnapshotModes).associate { row ->
                ActivitySnapshotId(row.id) to TimeTrackingMode.valueOf(row.timeTrackingMode)
            }
        require(activitySnapshotModes.keys == activitySnapshotIds.mapTo(hashSetOf(), ::ActivitySnapshotId)) {
            "Sequence occurrence references missing Activity snapshot metadata"
        }
        SequenceConfigSnapshotValidator.requireValid(snapshot, activitySnapshotModes)
        SequenceExecutionValidator.requireValid(aggregate.toDomain(), snapshot)
    }

    private companion object {
        const val SQLITE_SAFE_BIND_COUNT = 900
    }
}

private fun SequenceExecutionEntity.stableIdentity(): List<Any?> =
    listOf(
        id,
        snapshotId,
        planEntryId,
        statisticsSeriesId,
        startedAtMs,
        originalZoneId,
        originalUtcOffsetMinutes,
        primaryLocalDate,
        createdAtMs,
    )

private fun SequenceOccurrenceEntity.identity(): List<Any?> =
    listOf(
        id,
        sequenceExecutionId,
        sourceSequenceSnapshotNodeId,
        activitySnapshotId,
        repeatSourceSnapshotNodeId,
        repeatIteration,
        isRuntimeAdded,
        isDeletedFromHistory,
    )

private fun SequenceOccurrenceEntity.runtimeState(): List<Any?> =
    listOf(status, enteredAtMs, completedAtMs, completionReason)

private fun SequenceIntervalEntity.identity(): List<Any?> =
    listOf(id, sequenceExecutionId, kind, startedAtMs, occurrenceId)
