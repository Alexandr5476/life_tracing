package com.alexandr5476.lifetracing.domain

import java.time.Instant

object SequenceChildHistoryDeletionPolicy {
    fun delete(
        execution: SequenceExecution,
        snapshot: SequenceConfigSnapshot,
        children: List<SequenceHistoryChildExecution>,
        command: SequenceChildHistoryDeletionCommand,
        deletedAt: Instant,
    ): SequenceChildHistoryDeletionResult {
        require(execution.updatedAt == command.expectedUpdatedAt.toPersistenceMillis()) {
            "Sequence history was changed before this deletion"
        }
        require(execution.status in TERMINAL_STATUSES) { "Only terminal Sequence history can be changed" }
        SequenceExecutionValidator.requireValid(execution, snapshot)
        children.forEach { ActivityExecutionValidator.requireValid(it.execution, it.snapshot) }
        SequenceHistoricalTimingGraphValidator.requireValid(execution, snapshot, children)

        val occurrence =
            requireNotNull(execution.occurrences.singleOrNull { it.id == command.occurrenceId }) {
                "Unknown Sequence occurrence: ${command.occurrenceId.value}"
            }
        require(occurrence.status == RuntimeOccurrenceStatus.COMPLETED && !occurrence.isDeletedFromHistory) {
            "Only a retained completed occurrence can become a tombstone"
        }
        val child =
            requireNotNull(children.singleOrNull { it.execution.id == command.childExecutionId }) {
                "Unknown Sequence child: ${command.childExecutionId.value}"
            }.execution
        require(child.sequenceOccurrenceId == occurrence.id) {
            "Sequence child does not belong to the requested occurrence"
        }
        require(child.deletedAt == null && child.status == ActivityExecutionStatus.COMPLETED) {
            "Only a non-deleted completed Sequence child can be deleted"
        }

        val mutationTime = deletedAt.toPersistenceMillis()
        require(mutationTime > execution.updatedAt && mutationTime > child.updatedAt) {
            "Historical deletion time must advance both root and child mutation tokens"
        }
        val deletedChild = child.copy(deletedAt = mutationTime, updatedAt = mutationTime)
        val correctedExecution =
            execution.copy(
                updatedAt = mutationTime,
                occurrences =
                    execution.occurrences.map {
                        if (it.id == occurrence.id) it.copy(status = RuntimeOccurrenceStatus.DELETED_EXECUTION) else it
                    },
            )
        val correctedChildren =
            children.map { if (it.execution.id == deletedChild.id) it.copy(execution = deletedChild) else it }
        SequenceExecutionValidator.requireValid(correctedExecution, snapshot)
        SequenceHistoricalTimingGraphValidator.requireValid(correctedExecution, snapshot, correctedChildren)
        return SequenceChildHistoryDeletionResult(correctedExecution, deletedChild)
    }

    private fun Instant.toPersistenceMillis(): Instant = Instant.ofEpochMilli(toEpochMilli())

    private val TERMINAL_STATUSES = setOf(SequenceExecutionStatus.COMPLETED, SequenceExecutionStatus.ENDED_EARLY)
}
