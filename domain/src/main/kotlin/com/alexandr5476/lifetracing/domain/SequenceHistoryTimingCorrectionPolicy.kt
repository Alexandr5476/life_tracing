package com.alexandr5476.lifetracing.domain

import java.time.Instant

object SequenceHistoryTimingCorrectionPolicy {
    fun correct(
        execution: SequenceExecution,
        snapshot: SequenceConfigSnapshot,
        children: List<SequenceHistoryChildExecution>,
        correction: SequenceHistoryTimingCorrection,
        correctedAt: Instant,
    ): SequenceHistoryTimingCorrectionResult {
        require(execution.updatedAt == correction.expectedUpdatedAt.toPersistenceMillis()) {
            "Sequence history was changed before this correction"
        }
        require(execution.status in TERMINAL_STATUSES) { "Only terminal Sequence history can be corrected" }
        val correctionTime = correctedAt.toPersistenceMillis()
        require(correctionTime > execution.updatedAt) { "Historical correction time must advance" }

        val occurrences = correctOccurrences(execution.occurrences, correction.occurrenceTimings)
        val childById = children.associateBy { it.execution.id }
        require(childById.size == children.size) { "Child execution identities must be unique" }
        val childTimingById = correction.childTimings.associateBy(SequenceChildTimingCorrection::executionId)
        require(childTimingById.size == correction.childTimings.size) { "Child timing targets must be unique" }
        require(childTimingById.keys.all(childById::contains)) { "Child correction must target a supplied child" }
        childTimingById.keys.forEach { childId ->
            val child = childById.getValue(childId).execution
            require(child.status == ActivityExecutionStatus.COMPLETED && child.deletedAt == null) {
                "Only completed non-deleted Sequence children can be corrected"
            }
        }
        val correctedChildren = correctChildren(childById, childTimingById, correctionTime)
        val startedAt = correction.startedAt?.toPersistenceMillis() ?: execution.startedAt
        val endedAt = correction.endedAt?.toPersistenceMillis() ?: requireNotNull(execution.endedAt)
        require(endedAt >= startedAt && endedAt <= correctionTime) {
            "Corrected Sequence span must be ordered and historical"
        }
        val intervals = correction.finalIntervals?.map { it.toPersistenceMillis() } ?: execution.intervals
        val durations = SequenceTimelineCalculator.calculate(startedAt, endedAt, intervals)
        val correctedExecution =
            execution.copy(
                startedAt = startedAt,
                endedAt = endedAt,
                activeDuration = durations.active,
                pauseDuration = durations.pause,
                wallDuration = durations.wall,
                originalUtcOffsetMinutes =
                    if (correction.startedAt == null) {
                        execution.originalUtcOffsetMinutes
                    } else {
                        startedAt.atZone(execution.originalZoneId).offset.totalSeconds / SECONDS_PER_MINUTE
                    },
                primaryLocalDate =
                    if (correction.startedAt == null) {
                        execution.primaryLocalDate
                    } else {
                        startedAt.atZone(execution.originalZoneId).toLocalDate()
                    },
                updatedAt = correctionTime,
                occurrences = occurrences,
                intervals = intervals,
            )
        requireFactsWithinRoot(correctedExecution, correctedChildren.map(SequenceHistoryChildExecution::execution))
        SequenceExecutionValidator.requireValid(correctedExecution, snapshot)
        correctedChildren.forEach { ActivityExecutionValidator.requireValid(it.execution, it.snapshot) }
        SequenceHistoricalTimingGraphValidator.requireValid(correctedExecution, snapshot, correctedChildren)
        return SequenceHistoryTimingCorrectionResult(
            correctedExecution,
            correctedChildren.map(SequenceHistoryChildExecution::execution),
        )
    }

    private fun correctOccurrences(
        occurrences: List<RuntimeOccurrence>,
        corrections: List<SequenceOccurrenceTimingCorrection>,
    ): List<RuntimeOccurrence> {
        val correctionById = corrections.associateBy(SequenceOccurrenceTimingCorrection::occurrenceId)
        require(correctionById.size == corrections.size) { "Occurrence timing targets must be unique" }
        val occurrenceIds = occurrences.mapTo(hashSetOf(), RuntimeOccurrence::id)
        require(correctionById.keys.all(occurrenceIds::contains)) { "Occurrence correction must target this Sequence" }
        return occurrences.map { occurrence ->
            correctionById[occurrence.id]?.let { correction ->
                occurrence.copy(
                    enteredAt = correction.enteredAt?.toPersistenceMillis(),
                    completedAt = correction.completedAt?.toPersistenceMillis(),
                )
            } ?: occurrence
        }
    }

    private fun correctChildren(
        children: Map<ActivityExecutionId, SequenceHistoryChildExecution>,
        corrections: Map<ActivityExecutionId, SequenceChildTimingCorrection>,
        correctedAt: Instant,
    ): List<SequenceHistoryChildExecution> =
        children.values.map { child ->
            corrections[child.execution.id]?.let { correction ->
                child.copy(execution = correctChild(child, correction.time, correctedAt))
            } ?: child
        }

    private fun correctChild(
        child: SequenceHistoryChildExecution,
        time: ActivityHistoryTimeCorrection,
        correctedAt: Instant,
    ): ActivityExecution {
        val execution = child.execution
        require(correctedAt > execution.updatedAt) { "Corrected child mutation time must advance" }
        return when (time) {
            is ActivityHistoryTimeCorrection.Timed -> {
                require(child.snapshot.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
                    "Timed child correction requires a timed snapshot"
                }
                val startedAt = time.startedAt.toPersistenceMillis()
                val completedAt = time.completedAt.toPersistenceMillis()
                require(startedAt <= completedAt && completedAt <= correctedAt) {
                    "Corrected child interval must be ordered and historical"
                }
                execution.copy(
                    startedAt = startedAt,
                    completedAt = completedAt,
                    activeDuration =
                        ActivityExecutionDurationCalculator.calculate(
                            startedAt,
                            completedAt,
                            execution.pauses,
                        ),
                    originalUtcOffsetMinutes =
                        startedAt.atZone(execution.originalZoneId).offset.totalSeconds / SECONDS_PER_MINUTE,
                    primaryLocalDate = startedAt.atZone(execution.originalZoneId).toLocalDate(),
                    updatedAt = correctedAt,
                )
            }
            is ActivityHistoryTimeCorrection.NoLive -> {
                require(child.snapshot.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                    "No-live child correction requires a no-live snapshot"
                }
                val completedAt = time.completedAt.toPersistenceMillis()
                require(completedAt <= correctedAt) { "Corrected child completion must be historical" }
                execution.copy(
                    startedAt = null,
                    completedAt = completedAt,
                    activeDuration = null,
                    originalUtcOffsetMinutes =
                        completedAt.atZone(execution.originalZoneId).offset.totalSeconds / SECONDS_PER_MINUTE,
                    primaryLocalDate = completedAt.atZone(execution.originalZoneId).toLocalDate(),
                    updatedAt = correctedAt,
                )
            }
        }
    }

    private fun requireFactsWithinRoot(
        execution: SequenceExecution,
        children: List<ActivityExecution>,
    ) {
        val start = execution.startedAt.toEpochMilli()
        val end = requireNotNull(execution.endedAt).toEpochMilli()

        fun requireInside(at: Instant) =
            require(at.toEpochMilli() in start..end) {
                "Historical fact must be inside the corrected Sequence span"
            }
        execution.occurrences.forEach { occurrence ->
            occurrence.enteredAt?.let(::requireInside)
            occurrence.completedAt?.let(::requireInside)
        }
        execution.intervals.forEach { interval ->
            requireInside(interval.startedAt)
            requireInside(requireNotNull(interval.endedAt))
        }
        children.forEach { child ->
            child.startedAt?.let(::requireInside)
            child.completedAt?.let(::requireInside)
        }
    }

    private fun SequenceInterval.toPersistenceMillis() =
        copy(startedAt = startedAt.toPersistenceMillis(), endedAt = endedAt?.toPersistenceMillis())

    private fun Instant.toPersistenceMillis(): Instant = Instant.ofEpochMilli(toEpochMilli())

    private val TERMINAL_STATUSES = setOf(SequenceExecutionStatus.COMPLETED, SequenceExecutionStatus.ENDED_EARLY)
    private const val SECONDS_PER_MINUTE = 60
}

object SequenceHistoricalTimingGraphValidator {
    fun requireValid(
        execution: SequenceExecution,
        snapshot: SequenceConfigSnapshot,
        children: List<SequenceHistoryChildExecution>,
    ) {
        val occurrences = execution.occurrences.associateBy(RuntimeOccurrence::id)
        val childrenByOccurrence = requireValidChildren(execution.id, children, occurrences)
        requireOccurrenceChildStates(occurrences.values, childrenByOccurrence)
        val activeRangesByOccurrence =
            requireValidIntervals(
                execution.intervals,
                occurrences,
                childrenByOccurrence,
                snapshot.settings.noLiveTimeAccounting,
            )
        childrenByOccurrence.forEach { (occurrenceId, child) ->
            requireChildCoherence(
                child.execution,
                child.snapshot,
                occurrences.getValue(occurrenceId),
                activeRangesByOccurrence[occurrenceId],
            )
        }
    }

    private fun requireValidChildren(
        sequenceExecutionId: SequenceExecutionId,
        children: List<SequenceHistoryChildExecution>,
        occurrences: Map<SequenceOccurrenceId, RuntimeOccurrence>,
    ): Map<SequenceOccurrenceId, SequenceHistoryChildExecution> {
        val childrenByOccurrence = hashMapOf<SequenceOccurrenceId, SequenceHistoryChildExecution>()
        val childOccurrenceIds = hashSetOf<SequenceOccurrenceId?>()
        children.forEach { child ->
            val childExecution = child.execution
            require(childOccurrenceIds.add(childExecution.sequenceOccurrenceId)) {
                "At most one child execution may belong to an occurrence"
            }
            require(childExecution.context == ActivityExecutionContext.SEQUENCE_CHILD) {
                "Corrected Activity must be a Sequence child"
            }
            require(childExecution.sequenceExecutionId == sequenceExecutionId) {
                "Child execution must belong to the supplied Sequence"
            }
            val occurrence =
                requireNotNull(occurrences[childExecution.sequenceOccurrenceId]) {
                    "Child execution occurrence must belong to the supplied Sequence"
                }
            require(
                childExecution.snapshotId == child.snapshot.id &&
                    childExecution.snapshotId == occurrence.activitySnapshotId,
            ) {
                "Child execution snapshot must match its occurrence"
            }
            require(childExecution.status == ActivityExecutionStatus.COMPLETED) {
                "Historical Sequence child must be completed"
            }
            childrenByOccurrence[occurrence.id] = child
        }
        return childrenByOccurrence
    }

    private fun requireOccurrenceChildStates(
        occurrences: Collection<RuntimeOccurrence>,
        children: Map<SequenceOccurrenceId, SequenceHistoryChildExecution>,
    ) {
        occurrences.forEach { occurrence ->
            val child = children[occurrence.id]?.execution
            when (occurrence.status) {
                RuntimeOccurrenceStatus.COMPLETED ->
                    require(child != null && child.deletedAt == null) {
                        "Completed occurrence requires one retained child execution"
                    }
                RuntimeOccurrenceStatus.DELETED_EXECUTION ->
                    require(child?.deletedAt != null) {
                        "Deleted execution occurrence requires its logically deleted child"
                    }
                RuntimeOccurrenceStatus.NOT_STARTED,
                RuntimeOccurrenceStatus.SKIPPED,
                RuntimeOccurrenceStatus.CURRENT,
                ->
                    require(child == null) {
                        "Unperformed occurrence cannot retain a child execution"
                    }
            }
        }
    }

    private fun requireValidIntervals(
        intervals: List<SequenceInterval>,
        occurrences: Map<SequenceOccurrenceId, RuntimeOccurrence>,
        children: Map<SequenceOccurrenceId, SequenceHistoryChildExecution>,
        noLiveTimeAccounting: NoLiveTimeAccounting,
    ): Map<SequenceOccurrenceId, List<Pair<Long, Long>>> {
        val activeRangesByOccurrence = hashMapOf<SequenceOccurrenceId, MutableList<Pair<Long, Long>>>()
        intervals.forEach { interval ->
            val occurrence = interval.occurrenceId?.let(occurrences::get)
            when (interval.kind) {
                SequenceIntervalKind.ACTIVE_STEP -> {
                    val (performed, endedAt) = requireValidStepInterval(interval, occurrence)
                    requireExpectedStepKind(interval.kind, performed.id, children, noLiveTimeAccounting)
                    activeRangesByOccurrence
                        .getOrPut(performed.id) { ArrayList() }
                        .add(interval.startedAt.toEpochMilli() to endedAt.toEpochMilli())
                }
                SequenceIntervalKind.STEP_PAUSE -> {
                    val performed = requireValidStepInterval(interval, occurrence).first
                    requireExpectedStepKind(interval.kind, performed.id, children, noLiveTimeAccounting)
                }
                SequenceIntervalKind.TRANSITION_COUNTDOWN ->
                    require(occurrence != null) { "Transition countdown requires an occurrence" }
                SequenceIntervalKind.EXPLICIT_PAUSE,
                SequenceIntervalKind.IMPLICIT_IDLE,
                -> Unit
            }
        }
        return activeRangesByOccurrence
    }

    private fun requireExpectedStepKind(
        actual: SequenceIntervalKind,
        occurrenceId: SequenceOccurrenceId,
        children: Map<SequenceOccurrenceId, SequenceHistoryChildExecution>,
        noLiveTimeAccounting: NoLiveTimeAccounting,
    ) {
        children[occurrenceId]?.let { child ->
            require(actual == stepIntervalKind(child.snapshot, noLiveTimeAccounting)) {
                "Step interval kind must match the frozen Sequence accounting settings"
            }
        }
    }

    private fun requireValidStepInterval(
        interval: SequenceInterval,
        occurrence: RuntimeOccurrence?,
    ): Pair<RuntimeOccurrence, Instant> {
        val performed = requireNotNull(occurrence) { "Step interval requires an execution occurrence" }
        val enteredAt = requireNotNull(performed.enteredAt) { "Step interval requires a performed occurrence" }
        val completedAt =
            requireNotNull(performed.completedAt) { "Step interval requires a completed occurrence" }
        val endedAt = requireNotNull(interval.endedAt) { "Historical interval must be closed" }
        require(interval.startedAt >= enteredAt && endedAt <= completedAt) {
            "Step interval must stay inside its occurrence span"
        }
        return performed to endedAt
    }

    private fun requireChildCoherence(
        child: ActivityExecution,
        snapshot: ActivityConfigSnapshot,
        occurrence: RuntimeOccurrence,
        activeRanges: List<Pair<Long, Long>>?,
    ) {
        val completedAt = requireNotNull(occurrence.completedAt) { "Corrected child requires a completed occurrence" }
        when (snapshot.timeTrackingMode) {
            TimeTrackingMode.STOPWATCH,
            TimeTrackingMode.TIMER,
            -> {
                require(child.startedAt == occurrence.enteredAt && child.completedAt == completedAt) {
                    "Timed child boundaries must match its occurrence"
                }
                var rangeStart = requireNotNull(child.startedAt).toEpochMilli()
                val expectedRanges =
                    buildList {
                        child.pauses.sortedBy { it.startedAt }.forEach { pause ->
                            add(rangeStart to pause.startedAt.toEpochMilli())
                            rangeStart = requireNotNull(pause.endedAt).toEpochMilli()
                        }
                        add(rangeStart to completedAt.toEpochMilli())
                    }
                require(normalize(expectedRanges) == normalize(activeRanges.orEmpty())) {
                    "Timed child active ranges must match its active Sequence intervals"
                }
            }
            TimeTrackingMode.NO_LIVE_TRACKING -> {
                require(child.completedAt == completedAt) {
                    "No-live child completion must match its occurrence"
                }
                require(child.startedAt == null && child.activeDuration == null && child.pauses.isEmpty()) {
                    "No-live child must remain immediate without duration or pauses"
                }
            }
        }
    }

    private fun normalize(ranges: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val ordered = ranges.filter { it.first < it.second }.sortedBy { it.first }
        if (ordered.isEmpty()) return emptyList()
        val normalized = ArrayList<Pair<Long, Long>>()
        var current = ordered.first()
        ordered.drop(1).forEach { next ->
            if (next.first <= current.second) {
                current = current.first to maxOf(current.second, next.second)
            } else {
                normalized.add(current)
                current = next
            }
        }
        normalized.add(current)
        return normalized
    }
}
