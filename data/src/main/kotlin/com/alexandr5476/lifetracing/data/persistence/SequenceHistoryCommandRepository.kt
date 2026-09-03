package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionCommand
import com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionPolicy
import com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionResult
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceHistoryChildExecution
import com.alexandr5476.lifetracing.domain.SequenceHistoryTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceHistoryTimingCorrectionPolicy
import com.alexandr5476.lifetracing.domain.SequenceHistoryTimingCorrectionResult
import java.time.Instant
import java.util.ConcurrentModificationException
import java.util.concurrent.Callable

class SequenceHistoryCommandRepository internal constructor(
    private val database: LifeTracingDatabase,
) {
    fun deleteChildHistory(
        id: SequenceExecutionId,
        command: SequenceChildHistoryDeletionCommand,
        deletedAt: Instant,
    ): SequenceChildHistoryDeletionResult =
        transaction {
            val beforeEntity =
                requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(id.value)) {
                    "Unknown Sequence history: ${id.value}"
                }
            require(database.activeSessionDao().get()?.sequenceExecutionId != id) {
                "Active Sequence history cannot be changed"
            }
            val before = beforeEntity.toDomain()
            val expectedUpdatedAt = Instant.ofEpochMilli(command.expectedUpdatedAt.toEpochMilli())
            if (before.updatedAt != expectedUpdatedAt) {
                throw ConcurrentModificationException("Sequence history changed concurrently")
            }
            val snapshot =
                requireNotNull(database.sequenceSnapshotDao().getAggregate(before.snapshotId.value)) {
                    "Unknown SequenceSnapshot: ${before.snapshotId.value}"
                }.toDomain()
            val childEntities = database.activityExecutionDao().getSequenceChildAggregates(id.value)
            val childSnapshots = loadChildSnapshots(childEntities)
            val children =
                childEntities.map { child ->
                    val execution = child.toDomain()
                    SequenceHistoryChildExecution(
                        execution,
                        requireNotNull(childSnapshots[execution.snapshotId]) {
                            "Unknown child ActivitySnapshot: ${execution.snapshotId.value}"
                        },
                    )
                }
            before.planEntryId?.let { requireCoherentPlan(beforeEntity.execution, it.value) }
            val result =
                SequenceChildHistoryDeletionPolicy.delete(
                    before,
                    snapshot,
                    children,
                    command.copy(expectedUpdatedAt = expectedUpdatedAt),
                    deletedAt,
                )
            database.sequenceExecutionDao().persistHistoricalChildDeletion(
                beforeEntity,
                result.execution.toEntityAggregate(),
                command.occurrenceId.value,
            )
            val beforeChild =
                requireNotNull(childEntities.singleOrNull { it.execution.id == command.childExecutionId.value }) {
                    "Unknown Sequence child: ${command.childExecutionId.value}"
                }
            database.activityExecutionDao().softDeleteCompletedSequenceChild(
                beforeChild,
                result.child.toEntityAggregate(),
            )
            result
        }

    fun correctTiming(
        id: SequenceExecutionId,
        correction: SequenceHistoryTimingCorrection,
        correctedAt: Instant,
    ): SequenceHistoryTimingCorrectionResult =
        transaction {
            val beforeEntity =
                requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(id.value)) {
                    "Unknown Sequence history: ${id.value}"
                }
            require(database.activeSessionDao().get()?.sequenceExecutionId != id) {
                "Active Sequence history cannot be corrected"
            }
            val before = beforeEntity.toDomain()
            val expectedUpdatedAt = Instant.ofEpochMilli(correction.expectedUpdatedAt.toEpochMilli())
            if (before.updatedAt != expectedUpdatedAt) {
                throw ConcurrentModificationException("Sequence history changed concurrently")
            }
            val snapshot =
                requireNotNull(database.sequenceSnapshotDao().getAggregate(before.snapshotId.value)) {
                    "Unknown SequenceSnapshot: ${before.snapshotId.value}"
                }.toDomain()
            val childEntities = database.activityExecutionDao().getSequenceChildAggregates(id.value)
            val childSnapshots = loadChildSnapshots(childEntities)
            val children =
                childEntities.map { child ->
                    val execution = child.toDomain()
                    SequenceHistoryChildExecution(
                        execution,
                        requireNotNull(childSnapshots[execution.snapshotId]) {
                            "Unknown child ActivitySnapshot: ${execution.snapshotId.value}"
                        },
                    )
                }
            val plan = before.planEntryId?.let { requireCoherentPlan(beforeEntity.execution, it.value) }
            val result =
                SequenceHistoryTimingCorrectionPolicy.correct(
                    before,
                    snapshot,
                    children,
                    correction,
                    correctedAt,
                )

            database.sequenceExecutionDao().persistHistoricalTimingCorrection(
                beforeEntity,
                result.execution.toEntityAggregate(),
            )
            val childBeforeById = childEntities.associateBy { it.execution.id }
            result.children
                .map { it.toEntityAggregate() }
                .filter { childBeforeById.getValue(it.execution.id) != it }
                .forEach { after ->
                    database.activityExecutionDao().correctSequenceChildTiming(
                        childBeforeById.getValue(after.execution.id),
                        after,
                    )
                }
            synchronizePlan(plan, beforeEntity.execution, result.execution.endedAt, result.execution.updatedAt)
            result
        }

    private fun loadChildSnapshots(children: List<ActivityExecutionAggregateEntity>) =
        children
            .map { it.execution.snapshotId }
            .distinct()
            .chunked(SQLITE_SAFE_BIND_COUNT)
            .flatMap(database.activitySnapshotDao()::getAggregates)
            .map(ActivitySnapshotAggregateEntity::toDomain)
            .associateBy { it.id }

    private fun requireCoherentPlan(
        execution: SequenceExecutionEntity,
        planId: String,
    ): PlanEntry =
        requireNotNull(database.planEntryDao().getById(planId)) { "Unknown Plan: $planId" }
            .toDomain()
            .also { plan ->
                require(
                    plan.kind == PlanTrackableKind.SEQUENCE &&
                        plan.status == PlanEntryStatus.FULFILLED &&
                        plan.fulfilledSequenceExecutionId?.value == execution.id &&
                        plan.sequenceSnapshotId?.value == execution.snapshotId &&
                        plan.fulfilledAt?.toEpochMilli() == execution.endedAtMs,
                ) { "Fulfilled Sequence Plan linkage is incoherent" }
            }

    private fun synchronizePlan(
        plan: PlanEntry?,
        before: SequenceExecutionEntity,
        correctedEnd: Instant?,
        correctedAt: Instant,
    ) {
        if (plan == null || correctedEnd?.toEpochMilli() == before.endedAtMs) return
        val end = requireNotNull(correctedEnd)
        val updatedAtMs = correctedAt.toEpochMilli()
        require(updatedAtMs > plan.updatedAt.toEpochMilli()) { "Plan correction time must advance" }
        if (
            database.planEntryDao().correctFulfilledSequenceHistory(
                plan.id.value,
                requireNotNull(plan.sequenceSnapshotId).value,
                before.id,
                requireNotNull(plan.fulfilledAt).toEpochMilli(),
                plan.updatedAt.toEpochMilli(),
                end.toEpochMilli(),
                updatedAtMs,
            ) != 1
        ) {
            throw ConcurrentModificationException("Fulfilled Sequence Plan changed concurrently")
        }
    }

    private fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable(block))

    companion object {
        fun create(context: Context): SequenceHistoryCommandRepository =
            SequenceHistoryCommandRepository(
                LifeTracingDatabase.builder(context.applicationContext, DATABASE_NAME).build(),
            )

        private const val DATABASE_NAME = "lifetracing.db"
        private const val SQLITE_SAFE_BIND_COUNT = 900
    }
}
