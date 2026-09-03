@file:Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "TooManyFunctions")

package com.alexandr5476.lifetracing.domain

import java.time.Instant

object SequenceHistoryStructuralRemovalPolicy {
    fun remove(
        execution: SequenceExecution,
        snapshot: SequenceConfigSnapshot,
        children: List<SequenceHistoryChildExecution>,
        command: SequenceHistoryStructuralRemovalCommand,
        removedAt: Instant,
    ): SequenceHistoryStructuralRemovalResult {
        require(execution.updatedAt == command.expectedUpdatedAt.toPersistenceMillis()) {
            "Sequence history was changed before this structural removal"
        }
        require(execution.status in TERMINAL_STATUSES) { "Only terminal Sequence history can be changed" }
        SequenceExecutionValidator.requireValid(execution, snapshot)
        children.forEach { ActivityExecutionValidator.requireValid(it.execution, it.snapshot) }
        SequenceHistoricalTimingGraphValidator.requireValid(execution, snapshot, children)

        val target =
            requireNotNull(execution.occurrences.singleOrNull { it.id == command.occurrenceId }) {
                "Unknown Sequence occurrence: ${command.occurrenceId.value}"
            }
        require(!target.isDeletedFromHistory && target.status in PERFORMED_STATUSES) {
            "Only a retained performed occurrence can be structurally removed"
        }
        val targetChild =
            requireNotNull(children.singleOrNull { it.execution.id == command.childExecutionId }) {
                "Unknown Sequence child: ${command.childExecutionId.value}"
            }
        require(targetChild.execution.sequenceOccurrenceId == target.id) {
            "Sequence child does not belong to the requested occurrence"
        }

        val mutationTime = removedAt.toPersistenceMillis()
        require(mutationTime > execution.updatedAt) { "Structural mutation time must advance the root token" }
        val normalized = command.normalized()
        require(normalized.finalEndedAt in execution.startedAt..mutationTime) {
            "Structural final end must be ordered and historical"
        }
        return when (command.mode) {
            SequenceHistoryStructuralRemovalMode.LEAVE_GAP ->
                leaveGap(execution, snapshot, children, target, targetChild, normalized, mutationTime)
            SequenceHistoryStructuralRemovalMode.CLOSE_GAP ->
                closeGap(execution, snapshot, children, target, targetChild, normalized, mutationTime)
        }
    }

    private fun leaveGap(
        execution: SequenceExecution,
        snapshot: SequenceConfigSnapshot,
        children: List<SequenceHistoryChildExecution>,
        target: RuntimeOccurrence,
        targetChild: SequenceHistoryChildExecution,
        command: SequenceHistoryStructuralRemovalCommand,
        mutationTime: Instant,
    ): SequenceHistoryStructuralRemovalResult {
        require(command.finalEndedAt == execution.endedAt) { "Leave gap must preserve the Sequence end" }
        require(command.occurrenceTimings.isEmpty() && command.childTimings.isEmpty()) {
            "Leave gap cannot move unrelated historical facts"
        }
        val finalIntervals = execution.intervals.filter { it.occurrenceId != target.id }
        require(command.finalIntervals == finalIntervals) {
            "Leave gap must remove only intervals belonging to the target occurrence"
        }
        val finalChildren = deleteTargetChild(children, targetChild, mutationTime)
        return validateResult(
            execution,
            snapshot,
            finalChildren,
            target,
            requireNotNull(execution.endedAt),
            finalIntervals,
            mutationTime,
        )
    }

    private fun closeGap(
        execution: SequenceExecution,
        snapshot: SequenceConfigSnapshot,
        children: List<SequenceHistoryChildExecution>,
        target: RuntimeOccurrence,
        targetChild: SequenceHistoryChildExecution,
        command: SequenceHistoryStructuralRemovalCommand,
        mutationTime: Instant,
    ): SequenceHistoryStructuralRemovalResult {
        val oldEnd = requireNotNull(execution.endedAt)
        val finalEnd = command.finalEndedAt
        require(finalEnd >= execution.startedAt && finalEnd < oldEnd && finalEnd <= mutationTime) {
            "Close gap must explicitly shorten the terminal Sequence span"
        }
        val shiftMillis = Math.subtractExact(oldEnd.toEpochMilli(), finalEnd.toEpochMilli())
        val removedSpanMillis =
            Math.subtractExact(
                requireNotNull(target.completedAt).toEpochMilli(),
                requireNotNull(target.enteredAt).toEpochMilli(),
            )
        require(shiftMillis == removedSpanMillis) {
            "Close gap must shorten the root by the removed performed occurrence span"
        }
        val occurrenceCorrections = command.occurrenceTimings.uniqueBy(SequenceOccurrenceTimingCorrection::occurrenceId)
        require(target.id !in occurrenceCorrections) { "Removed occurrence timing must remain historical" }
        val laterOccurrences =
            execution.occurrences.filter {
                !it.isDeletedFromHistory &&
                    it.runtimePosition > target.runtimePosition
            }
        val laterPerformedOccurrences = laterOccurrences.filter { it.status in PERFORMED_STATUSES }
        require(occurrenceCorrections.keys == laterPerformedOccurrences.mapTo(hashSetOf(), RuntimeOccurrence::id)) {
            "Close gap requires the complete later performed occurrence suffix"
        }
        val correctedOccurrences =
            execution.occurrences.map { occurrence ->
                if (occurrence.id == target.id) {
                    occurrence.structurallyRemoved()
                } else {
                    occurrenceCorrections[occurrence.id]?.let { correction ->
                        requireTranslatedOccurrence(occurrence, correction, shiftMillis)
                    } ?: occurrence
                }
            }

        val oldIntervals = execution.intervals.filter { it.occurrenceId != target.id }
        val finalIntervals = command.finalIntervals
        val finalIntervalById = finalIntervals.uniqueBy(SequenceInterval::id)
        require(finalIntervalById.keys == oldIntervals.mapTo(hashSetOf(), SequenceInterval::id)) {
            "Close gap must retain every non-target interval identity"
        }
        val laterIntervalOwnerIds = laterOccurrences.mapTo(hashSetOf(), RuntimeOccurrence::id)
        oldIntervals.forEach { old ->
            val final = finalIntervalById.getValue(old.id)
            require(old.kind == final.kind && old.occurrenceId == final.occurrenceId) {
                "Close gap cannot change retained interval kind or ownership"
            }
            require(durationMillis(old) == durationMillis(final)) {
                "Close gap must preserve retained interval duration"
            }
            when {
                old.occurrenceId in laterIntervalOwnerIds -> require(final == old.translatedEarlier(shiftMillis))
                old.occurrenceId != null -> require(final == old) { "Earlier owned intervals must remain unchanged" }
                else -> {
                    require(final == old || final == old.translatedEarlier(shiftMillis)) {
                        "Explicit ownerless intervals may only remain fixed or join the same suffix translation"
                    }
                    require(final == old || old.startedAt >= requireNotNull(target.enteredAt)) {
                        "Facts earlier than the removed structural region must remain unchanged"
                    }
                }
            }
        }

        val childCorrections = command.childTimings.uniqueBy(SequenceStructuralChildTimingCorrection::executionId)
        val childrenByOccurrence = children.associateBy { requireNotNull(it.execution.sequenceOccurrenceId) }
        val movedChildIds =
            laterPerformedOccurrences.mapTo(hashSetOf()) { childrenByOccurrence.getValue(it.id).execution.id }
        require(childCorrections.keys == movedChildIds) {
            "Close gap requires an explicit timing and pause translation for every moved child"
        }
        val correctedChildren =
            deleteTargetChild(children, targetChild, mutationTime).map { child ->
                childCorrections[child.execution.id]?.let { correction ->
                    child.copy(execution = translateChild(child, correction, shiftMillis, mutationTime))
                } ?: child
            }

        return validateResult(
            execution.copy(occurrences = correctedOccurrences),
            snapshot,
            correctedChildren,
            target,
            finalEnd,
            finalIntervals,
            mutationTime,
        )
    }

    private fun validateResult(
        execution: SequenceExecution,
        snapshot: SequenceConfigSnapshot,
        children: List<SequenceHistoryChildExecution>,
        target: RuntimeOccurrence,
        endedAt: Instant,
        intervals: List<SequenceInterval>,
        mutationTime: Instant,
    ): SequenceHistoryStructuralRemovalResult {
        val occurrences =
            execution.occurrences.map { occurrence ->
                if (occurrence.id == target.id) occurrence.structurallyRemoved() else occurrence
            }
        val durations = SequenceTimelineCalculator.calculate(execution.startedAt, endedAt, intervals)
        val corrected =
            execution.copy(
                endedAt = endedAt,
                activeDuration = durations.active,
                pauseDuration = durations.pause,
                wallDuration = durations.wall,
                updatedAt = mutationTime,
                occurrences = occurrences,
                intervals = intervals,
            )
        val childExecutions = children.map(SequenceHistoryChildExecution::execution)
        SequenceHistoricalRootSpanValidator.requireFactsWithinRoot(corrected, childExecutions)
        SequenceExecutionValidator.requireValid(corrected, snapshot)
        children.forEach { ActivityExecutionValidator.requireValid(it.execution, it.snapshot) }
        SequenceHistoricalTimingGraphValidator.requireValid(corrected, snapshot, children)
        return SequenceHistoryStructuralRemovalResult(corrected, childExecutions)
    }

    private fun deleteTargetChild(
        children: List<SequenceHistoryChildExecution>,
        targetChild: SequenceHistoryChildExecution,
        mutationTime: Instant,
    ): List<SequenceHistoryChildExecution> {
        val child = targetChild.execution
        if (child.deletedAt != null) return children
        require(mutationTime > child.updatedAt) { "Structural deletion time must advance the child token" }
        val deleted = child.copy(deletedAt = mutationTime, updatedAt = mutationTime)
        return children.map { if (it.execution.id == deleted.id) it.copy(execution = deleted) else it }
    }

    private fun requireTranslatedOccurrence(
        occurrence: RuntimeOccurrence,
        correction: SequenceOccurrenceTimingCorrection,
        shiftMillis: Long,
    ): RuntimeOccurrence {
        require(
            correction.enteredAt == requireNotNull(occurrence.enteredAt).minusMillis(shiftMillis) &&
                correction.completedAt == requireNotNull(occurrence.completedAt).minusMillis(shiftMillis),
        ) { "Every moved occurrence must use the one Close gap suffix translation" }
        return occurrence.copy(enteredAt = correction.enteredAt, completedAt = correction.completedAt)
    }

    private fun translateChild(
        child: SequenceHistoryChildExecution,
        correction: SequenceStructuralChildTimingCorrection,
        shiftMillis: Long,
        mutationTime: Instant,
    ): ActivityExecution {
        val execution = child.execution
        require(mutationTime > execution.updatedAt) { "Moved child mutation time must advance" }
        return when (val time = correction.time) {
            is ActivityHistoryTimeCorrection.Timed -> {
                require(child.snapshot.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING)
                require(
                    time.startedAt == requireNotNull(execution.startedAt).minusMillis(shiftMillis) &&
                        time.completedAt == requireNotNull(execution.completedAt).minusMillis(shiftMillis),
                ) { "Moved timed child must use the Close gap suffix translation" }
                val oldPauses = execution.pauses.associateBy(ActivityExecutionPause::id)
                val finalPauses = correction.pauses.uniqueBy(ActivityExecutionPause::id)
                require(oldPauses.keys == finalPauses.keys)
                oldPauses.forEach { (id, pause) ->
                    require(finalPauses.getValue(id) == pause.translatedEarlier(shiftMillis)) {
                        "Moved child pauses must preserve identity, duration, and translation"
                    }
                }
                val pauses = correction.pauses
                val activeDuration =
                    ActivityExecutionDurationCalculator.calculate(
                        time.startedAt,
                        time.completedAt,
                        pauses,
                    )
                require(
                    activeDuration == execution.activeDuration,
                ) { "Moved child active duration must remain unchanged" }
                execution.copy(
                    startedAt = time.startedAt,
                    completedAt = time.completedAt,
                    activeDuration = activeDuration,
                    originalUtcOffsetMinutes =
                        time.startedAt
                            .atZone(execution.originalZoneId)
                            .offset.totalSeconds / SECONDS_PER_MINUTE,
                    primaryLocalDate = time.startedAt.atZone(execution.originalZoneId).toLocalDate(),
                    updatedAt = mutationTime,
                    pauses = pauses,
                )
            }
            is ActivityHistoryTimeCorrection.NoLive -> {
                require(child.snapshot.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING)
                require(time.completedAt == requireNotNull(execution.completedAt).minusMillis(shiftMillis)) {
                    "Moved no-live child must use the Close gap suffix translation"
                }
                require(correction.pauses.isEmpty()) { "No-live child cannot acquire pauses" }
                execution.copy(
                    completedAt = time.completedAt,
                    originalUtcOffsetMinutes =
                        time.completedAt
                            .atZone(execution.originalZoneId)
                            .offset.totalSeconds / SECONDS_PER_MINUTE,
                    primaryLocalDate = time.completedAt.atZone(execution.originalZoneId).toLocalDate(),
                    updatedAt = mutationTime,
                )
            }
        }
    }

    private fun SequenceHistoryStructuralRemovalCommand.normalized() =
        copy(
            expectedUpdatedAt = expectedUpdatedAt.toPersistenceMillis(),
            finalEndedAt = finalEndedAt.toPersistenceMillis(),
            finalIntervals = finalIntervals.map { it.toPersistenceMillis() },
            occurrenceTimings =
                occurrenceTimings.map {
                    it.copy(
                        enteredAt = it.enteredAt?.toPersistenceMillis(),
                        completedAt = it.completedAt?.toPersistenceMillis(),
                    )
                },
            childTimings =
                childTimings.map { correction ->
                    correction.copy(
                        time = correction.time.toPersistenceMillis(),
                        pauses = correction.pauses.map { it.toPersistenceMillis() },
                    )
                },
        )

    private fun ActivityHistoryTimeCorrection.toPersistenceMillis(): ActivityHistoryTimeCorrection =
        when (this) {
            is ActivityHistoryTimeCorrection.Timed ->
                copy(startedAt = startedAt.toPersistenceMillis(), completedAt = completedAt.toPersistenceMillis())
            is ActivityHistoryTimeCorrection.NoLive -> copy(completedAt = completedAt.toPersistenceMillis())
        }

    private fun RuntimeOccurrence.structurallyRemoved() =
        copy(status = RuntimeOccurrenceStatus.DELETED_EXECUTION, isDeletedFromHistory = true)

    private fun SequenceInterval.translatedEarlier(shiftMillis: Long) =
        copy(startedAt = startedAt.minusMillis(shiftMillis), endedAt = endedAt?.minusMillis(shiftMillis))

    private fun ActivityExecutionPause.translatedEarlier(shiftMillis: Long) =
        copy(startedAt = startedAt.minusMillis(shiftMillis), endedAt = endedAt?.minusMillis(shiftMillis))

    private fun SequenceInterval.toPersistenceMillis() =
        copy(startedAt = startedAt.toPersistenceMillis(), endedAt = endedAt?.toPersistenceMillis())

    private fun ActivityExecutionPause.toPersistenceMillis() =
        copy(startedAt = startedAt.toPersistenceMillis(), endedAt = endedAt?.toPersistenceMillis())

    private fun durationMillis(interval: SequenceInterval) =
        Math.subtractExact(requireNotNull(interval.endedAt).toEpochMilli(), interval.startedAt.toEpochMilli())

    private fun <K, V> List<V>.uniqueBy(key: (V) -> K): Map<K, V> =
        associateBy(key).also { require(it.size == size) { "Structural correction targets must be unique" } }

    private fun Instant.toPersistenceMillis(): Instant = Instant.ofEpochMilli(toEpochMilli())

    private val TERMINAL_STATUSES = setOf(SequenceExecutionStatus.COMPLETED, SequenceExecutionStatus.ENDED_EARLY)
    private val PERFORMED_STATUSES = setOf(RuntimeOccurrenceStatus.COMPLETED, RuntimeOccurrenceStatus.DELETED_EXECUTION)
    private const val SECONDS_PER_MINUTE = 60
}
