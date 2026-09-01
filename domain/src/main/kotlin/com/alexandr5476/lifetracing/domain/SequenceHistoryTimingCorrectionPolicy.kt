package com.alexandr5476.lifetracing.domain

import java.time.Duration
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
        validateChildren(children, execution.id, occurrences, childTimingById.keys)
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
        SequenceHistoricalTimingGraphValidator.requireValid(
            correctedExecution,
            correctedChildren,
            childTimingById.keys,
        )
        SequenceExecutionValidator.requireValid(correctedExecution, snapshot)
        correctedChildren.forEach { ActivityExecutionValidator.requireValid(it.execution, it.snapshot) }
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

    private fun validateChildren(
        children: List<SequenceHistoryChildExecution>,
        sequenceExecutionId: SequenceExecutionId,
        occurrences: List<RuntimeOccurrence>,
        correctedChildIds: Set<ActivityExecutionId>,
    ) {
        val occurrenceById = occurrences.associateBy(RuntimeOccurrence::id)
        require(children.map { it.execution.sequenceOccurrenceId }.distinct().size == children.size) {
            "At most one child execution may belong to an occurrence"
        }
        children.forEach { child ->
            val execution = child.execution
            require(execution.context == ActivityExecutionContext.SEQUENCE_CHILD) {
                "Corrected Activity must be a Sequence child"
            }
            require(execution.sequenceExecutionId == sequenceExecutionId) {
                "Child execution must belong to the supplied Sequence"
            }
            val occurrence =
                requireNotNull(occurrenceById[execution.sequenceOccurrenceId]) {
                    "Child execution occurrence must belong to the supplied Sequence"
                }
            require(
                execution.snapshotId == child.snapshot.id && execution.snapshotId == occurrence.activitySnapshotId,
            ) {
                "Child execution snapshot must match its occurrence"
            }
            if (execution.id in correctedChildIds) {
                require(execution.status == ActivityExecutionStatus.COMPLETED && execution.deletedAt == null) {
                    "Only completed non-deleted Sequence children can be corrected"
                }
            }
            ActivityExecutionValidator.requireValid(execution, child.snapshot)
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

private object SequenceHistoricalTimingGraphValidator {
    fun requireValid(
        execution: SequenceExecution,
        children: List<SequenceHistoryChildExecution>,
        correctedChildIds: Set<ActivityExecutionId>,
    ) {
        val occurrences = execution.occurrences.associateBy(RuntimeOccurrence::id)
        val activeIntervalsByOccurrence = hashMapOf<SequenceOccurrenceId, MutableList<SequenceInterval>>()
        execution.intervals.forEach { interval ->
            val occurrence = interval.occurrenceId?.let(occurrences::get)
            when (interval.kind) {
                SequenceIntervalKind.ACTIVE_STEP,
                SequenceIntervalKind.STEP_PAUSE,
                -> {
                    val performed = requireNotNull(occurrence) { "Step interval requires an execution occurrence" }
                    val enteredAt =
                        requireNotNull(performed.enteredAt) { "Step interval requires a performed occurrence" }
                    val completedAt =
                        requireNotNull(performed.completedAt) { "Step interval requires a completed occurrence" }
                    val endedAt = requireNotNull(interval.endedAt) { "Historical interval must be closed" }
                    require(interval.startedAt >= enteredAt && endedAt <= completedAt) {
                        "Step interval must stay inside its occurrence span"
                    }
                    if (interval.kind == SequenceIntervalKind.ACTIVE_STEP) {
                        activeIntervalsByOccurrence.getOrPut(performed.id) { ArrayList() }.add(interval)
                    }
                }
                SequenceIntervalKind.TRANSITION_COUNTDOWN ->
                    require(occurrence != null) { "Transition countdown requires an occurrence" }
                SequenceIntervalKind.EXPLICIT_PAUSE,
                SequenceIntervalKind.IMPLICIT_IDLE,
                -> Unit
            }
        }
        children.forEach { child ->
            if (child.execution.id !in correctedChildIds) return@forEach
            val occurrence =
                requireNotNull(occurrences[child.execution.sequenceOccurrenceId]) {
                    "Corrected child occurrence must belong to the Sequence"
                }
            requireChildCoherence(
                child.execution,
                child.snapshot,
                occurrence,
                activeIntervalsByOccurrence[occurrence.id],
            )
        }
    }

    private fun requireChildCoherence(
        child: ActivityExecution,
        snapshot: ActivityConfigSnapshot,
        occurrence: RuntimeOccurrence,
        activeIntervals: List<SequenceInterval>?,
    ) {
        val completedAt = requireNotNull(occurrence.completedAt) { "Corrected child requires a completed occurrence" }
        when (snapshot.timeTrackingMode) {
            TimeTrackingMode.STOPWATCH,
            TimeTrackingMode.TIMER,
            -> {
                require(child.startedAt == occurrence.enteredAt && child.completedAt == completedAt) {
                    "Timed child boundaries must match its occurrence"
                }
                val activeMillis =
                    activeIntervals.orEmpty().fold(0L) { total, interval ->
                        Math.addExact(
                            total,
                            Math.subtractExact(
                                requireNotNull(interval.endedAt).toEpochMilli(),
                                interval.startedAt.toEpochMilli(),
                            ),
                        )
                    }
                require(child.activeDuration == Duration.ofMillis(activeMillis)) {
                    "Timed child active duration must match its active Sequence intervals"
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
}
