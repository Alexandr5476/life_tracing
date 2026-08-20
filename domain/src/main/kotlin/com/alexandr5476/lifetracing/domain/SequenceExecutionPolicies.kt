package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class RuntimeOccurrenceMaterializer(
    private val nextOccurrenceId: () -> SequenceOccurrenceId,
) {
    fun materialize(snapshot: SequenceConfigSnapshot): List<RuntimeOccurrence> {
        SequenceConfigSnapshotValidator.requireValid(snapshot)
        val count =
            snapshot.nodes.fold(0L) { total, node ->
                val produced =
                    when (node) {
                        is SequenceSnapshotActivityStep -> 1L
                        is SequenceSnapshotRepeatBlock ->
                            Math.multiplyExact(node.repeatCount.toLong(), node.children.size.toLong())
                    }
                Math.addExact(total, produced)
            }
        require(count <= Int.MAX_VALUE) { "Runtime occurrence count exceeds supported positions" }

        val ids = HashSet<SequenceOccurrenceId>(count.toInt())
        return buildList(count.toInt()) {
            snapshot.nodes.sortedBy(SequenceSnapshotNode::position).forEach { node ->
                when (node) {
                    is SequenceSnapshotActivityStep -> addOccurrence(node, null, null, ids)
                    is SequenceSnapshotRepeatBlock -> {
                        val children = node.children.sortedBy(SequenceSnapshotActivityStep::position)
                        for (iteration in 1..node.repeatCount) {
                            children.forEach { child ->
                                addOccurrence(child, node.id, iteration, ids)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun MutableList<RuntimeOccurrence>.addOccurrence(
        step: SequenceSnapshotActivityStep,
        repeatId: SequenceSnapshotNodeId?,
        repeatIteration: Int?,
        ids: MutableSet<SequenceOccurrenceId>,
    ) {
        val id = nextOccurrenceId()
        require(ids.add(id)) { "Generated occurrence identities must be unique" }
        add(
            RuntimeOccurrence(
                id,
                step.id,
                step.activitySnapshotId,
                size,
                repeatId,
                repeatIteration,
                RuntimeOccurrenceStatus.NOT_STARTED,
                null,
                null,
                null,
                isRuntimeAdded = false,
                isDeletedFromHistory = false,
            ),
        )
    }
}

object SequenceTimelineCalculator {
    fun calculate(
        startedAt: Instant,
        endedAt: Instant,
        intervals: List<SequenceInterval>,
    ): SequenceTimelineDurations {
        val startMs = startedAt.toEpochMilli()
        val endMs = endedAt.toEpochMilli()
        require(endMs >= startMs) { "Sequence end must not precede start" }
        val ranges =
            intervals
                .map { interval ->
                    val intervalEnd =
                        requireNotNull(interval.endedAt) { "Completed timeline cannot contain an open interval" }
                    val intervalStartMs = interval.startedAt.toEpochMilli()
                    val intervalEndMs = intervalEnd.toEpochMilli()
                    require(intervalEndMs >= intervalStartMs) { "Interval end must not precede start" }
                    require(intervalStartMs >= startMs && intervalEndMs <= endMs) {
                        "Interval must be inside the Sequence wall span"
                    }
                    Triple(interval.kind, intervalStartMs, intervalEndMs)
                }.filter { it.first == SequenceIntervalKind.ACTIVE_STEP }
                .sortedBy { it.second }

        var activeMs = 0L
        var mergedStart = 0L
        var mergedEnd = 0L
        ranges.forEachIndexed { index, range ->
            if (index == 0) {
                mergedStart = range.second
                mergedEnd = range.third
            } else if (range.second <= mergedEnd) {
                mergedEnd = maxOf(mergedEnd, range.third)
            } else {
                activeMs = Math.addExact(activeMs, Math.subtractExact(mergedEnd, mergedStart))
                mergedStart = range.second
                mergedEnd = range.third
            }
        }
        if (ranges.isNotEmpty()) {
            activeMs = Math.addExact(activeMs, Math.subtractExact(mergedEnd, mergedStart))
        }
        val wallMs = Math.subtractExact(endMs, startMs)
        val pauseMs = maxOf(0L, Math.subtractExact(wallMs, activeMs))
        return SequenceTimelineDurations(
            Duration.ofMillis(activeMs),
            Duration.ofMillis(pauseMs),
            Duration.ofMillis(wallMs),
        )
    }
}

object SequenceExecutionValidator {
    fun requireValid(
        execution: SequenceExecution,
        snapshot: SequenceConfigSnapshot,
    ) {
        SequenceConfigSnapshotValidator.requireValid(snapshot)
        require(execution.snapshotId == snapshot.id) { "Execution must reference the supplied Sequence snapshot" }
        require(execution.statisticsSeriesId == snapshot.statisticsSeriesId) {
            "Execution must preserve the Sequence snapshot Statistics Series"
        }
        require(execution.updatedAt.toEpochMilli() >= execution.createdAt.toEpochMilli()) {
            "Updated time must not precede creation"
        }
        execution.originalUtcOffsetMinutes?.let { offset ->
            require(offset in -MAX_UTC_OFFSET_MINUTES..MAX_UTC_OFFSET_MINUTES) {
                "UTC offset must be within the valid ZoneOffset range"
            }
        }
        val zonedStart = execution.startedAt.atZone(execution.originalZoneId)
        require(execution.primaryLocalDate == zonedStart.toLocalDate()) {
            "Primary local date must derive from the original Sequence timezone"
        }
        execution.originalUtcOffsetMinutes?.let { offset ->
            require(offset == zonedStart.offset.totalSeconds / SECONDS_PER_MINUTE) {
                "Stored UTC offset must match the original Sequence timezone at start"
            }
        }
        requireRootState(execution)
        requireOccurrences(execution, snapshot)
        requireIntervals(execution)
        requireValues(execution.values, snapshot)
    }

    fun requireRootState(execution: SequenceExecution) {
        when (execution.status) {
            SequenceExecutionStatus.RUNNING,
            SequenceExecutionStatus.PAUSED,
            ->
                require(
                    execution.endedAt == null &&
                        execution.activeDuration == null &&
                        execution.pauseDuration == null &&
                        execution.wallDuration == null,
                ) { "Live Sequence cannot have completion caches" }
            SequenceExecutionStatus.COMPLETED,
            SequenceExecutionStatus.ENDED_EARLY,
            -> {
                val endedAt = requireNotNull(execution.endedAt) { "Completed Sequence requires an end" }
                require(
                    execution.currentOccurrenceId == null,
                ) { "Completed Sequence cannot retain a current occurrence" }
                val calculated = SequenceTimelineCalculator.calculate(execution.startedAt, endedAt, execution.intervals)
                require(
                    execution.activeDuration == calculated.active &&
                        execution.pauseDuration == calculated.pause &&
                        execution.wallDuration == calculated.wall,
                ) { "Stored Sequence duration caches must match the interval timeline" }
            }
        }
    }

    private fun requireOccurrences(
        execution: SequenceExecution,
        snapshot: SequenceConfigSnapshot,
    ) {
        require(
            execution.occurrences
                .map(RuntimeOccurrence::id)
                .distinct()
                .size == execution.occurrences.size,
        ) {
            "Occurrence identities must be unique"
        }
        require(
            execution.occurrences
                .map(RuntimeOccurrence::runtimePosition)
                .distinct()
                .size == execution.occurrences.size,
        ) {
            "Runtime positions must be unique"
        }
        val sourceSteps =
            snapshot.nodes
                .flatMap { node ->
                    when (node) {
                        is SequenceSnapshotActivityStep -> listOf(node)
                        is SequenceSnapshotRepeatBlock -> node.children
                    }
                }.associateBy(SequenceSnapshotActivityStep::id)
        val repeats =
            snapshot.nodes.filterIsInstance<SequenceSnapshotRepeatBlock>().associateBy(
                SequenceSnapshotRepeatBlock::id,
            )
        val topLevelStepIds =
            snapshot.nodes.filterIsInstance<SequenceSnapshotActivityStep>().mapTo(hashSetOf()) { it.id }
        execution.occurrences.forEach { occurrence ->
            require(occurrence.runtimePosition >= 0) { "Runtime position must not be negative" }
            requireOccurrenceState(occurrence)
            requireOccurrenceSource(occurrence, topLevelStepIds, sourceSteps, repeats)
        }
        val current = execution.occurrences.filter { it.status == RuntimeOccurrenceStatus.CURRENT }
        require(current.size <= 1) { "Sequence may have at most one current occurrence" }
        require(current.singleOrNull()?.id == execution.currentOccurrenceId) {
            "Root current occurrence pointer must match occurrence state"
        }
    }

    private fun requireOccurrenceSource(
        occurrence: RuntimeOccurrence,
        topLevelStepIds: Set<SequenceSnapshotNodeId>,
        sourceSteps: Map<SequenceSnapshotNodeId, SequenceSnapshotActivityStep>,
        repeats: Map<SequenceSnapshotNodeId, SequenceSnapshotRepeatBlock>,
    ) {
        if (occurrence.isRuntimeAdded) {
            require(
                occurrence.sourceSequenceSnapshotNodeId == null &&
                    occurrence.repeatSourceSnapshotNodeId == null &&
                    occurrence.repeatIteration == null,
            ) { "Runtime-added occurrence cannot claim frozen source metadata" }
            return
        }
        val repeatId = occurrence.repeatSourceSnapshotNodeId
        val repeat =
            repeatId?.let {
                requireNotNull(repeats[it]) { "Repeat source must exist in the Sequence snapshot" }.also { source ->
                    require(occurrence.repeatIteration in 1..source.repeatCount) {
                        "Repeat iteration must be 1-based and within the source Repeat count"
                    }
                }
            }
        val sourceId =
            occurrence.sourceSequenceSnapshotNodeId ?: run {
                require(repeatId != null || occurrence.repeatIteration == null) {
                    "Top-level occurrence cannot carry Repeat iteration metadata"
                }
                return // SET NULL preserves historical rows.
            }
        val step =
            requireNotNull(sourceSteps[sourceId]) {
                "Occurrence source must be a Step in the execution Sequence snapshot"
            }
        require(occurrence.activitySnapshotId == step.activitySnapshotId) {
            "Occurrence Activity snapshot must match its source Step"
        }
        if (repeat == null) {
            require(occurrence.repeatIteration == null && step.id in topLevelStepIds) {
                "Top-level Step occurrence cannot carry Repeat metadata"
            }
        } else {
            require(repeat.children.any { it.id == step.id }) {
                "Occurrence Step must belong to its Repeat source"
            }
        }
    }

    private fun requireOccurrenceState(occurrence: RuntimeOccurrence) {
        when (occurrence.status) {
            RuntimeOccurrenceStatus.NOT_STARTED,
            RuntimeOccurrenceStatus.SKIPPED,
            ->
                require(
                    occurrence.enteredAt == null &&
                        occurrence.completedAt == null &&
                        occurrence.completionReason == null,
                ) { "Untouched occurrence cannot contain runtime completion data" }
            RuntimeOccurrenceStatus.CURRENT ->
                require(
                    occurrence.enteredAt != null &&
                        occurrence.completedAt == null &&
                        occurrence.completionReason == null,
                ) { "Current occurrence requires entry and no completion" }
            RuntimeOccurrenceStatus.COMPLETED -> {
                val entered = requireNotNull(occurrence.enteredAt) { "Completed occurrence requires entry" }
                val completed = requireNotNull(occurrence.completedAt) { "Completed occurrence requires completion" }
                require(completed.toEpochMilli() >= entered.toEpochMilli()) {
                    "Occurrence completion must not precede entry"
                }
            }
            RuntimeOccurrenceStatus.DELETED_EXECUTION -> Unit
        }
    }

    private fun requireIntervals(execution: SequenceExecution) {
        require(
            execution.intervals
                .map(SequenceInterval::id)
                .distinct()
                .size == execution.intervals.size,
        ) {
            "Sequence interval identities must be unique"
        }
        val occurrenceIds = execution.occurrences.mapTo(hashSetOf(), RuntimeOccurrence::id)
        execution.intervals.forEach { interval ->
            val end = interval.endedAt
            require(end == null || end.toEpochMilli() >= interval.startedAt.toEpochMilli()) {
                "Interval end must not precede start"
            }
            interval.occurrenceId?.let {
                require(
                    it in occurrenceIds,
                ) { "Interval occurrence must belong to the Sequence" }
            }
        }
        if (execution.status == SequenceExecutionStatus.RUNNING || execution.status == SequenceExecutionStatus.PAUSED) {
            require(execution.intervals.count { it.endedAt == null } <= 1) {
                "Live Sequence may contain at most one open classification interval"
            }
        } else {
            require(
                execution.intervals.none { it.endedAt == null },
            ) { "Completed Sequence cannot contain open intervals" }
        }
    }

    private fun requireValues(
        values: List<SequenceExecutionFieldValue>,
        snapshot: SequenceConfigSnapshot,
    ) {
        require(values.map(SequenceExecutionFieldValue::snapshotFieldId).distinct().size == values.size) {
            "Sequence execution may contain at most one value per snapshot field"
        }
        val fields = snapshot.fields.associateBy(SequenceSnapshotField::id)
        values.forEach { value ->
            val field =
                requireNotNull(fields[value.snapshotFieldId]) { "Sequence value field must belong to its snapshot" }
            when (value) {
                is NumberSequenceExecutionValue -> require(field.type == CustomFieldType.NUMBER)
                is CategorySequenceExecutionValue -> {
                    require(field.type == CustomFieldType.CATEGORY)
                    require(field.categoryOptions.any { it.id == value.optionId }) {
                        "Sequence category option must belong to the value field"
                    }
                }
                is TextSequenceExecutionValue -> require(field.type == CustomFieldType.TEXT)
            }
        }
    }
}

class SequenceExecutionFactory(
    private val nextExecutionId: () -> SequenceExecutionId,
    private val occurrenceMaterializer: RuntimeOccurrenceMaterializer,
) {
    fun start(
        snapshot: SequenceConfigSnapshot,
        startedAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
    ): SequenceExecution {
        SequenceConfigSnapshotValidator.requireValid(snapshot)
        val start = startedAt.toPersistenceInstant()
        val creation = createdAt.toPersistenceInstant()
        require(start <= creation) { "Sequence start must not be in the future" }
        return SequenceExecution(
            nextExecutionId(),
            snapshot.id,
            snapshot.statisticsSeriesId,
            SequenceExecutionStatus.RUNNING,
            start,
            null,
            null,
            null,
            null,
            zoneId,
            start.atZone(zoneId).offset.totalSeconds / SECONDS_PER_MINUTE,
            start.atZone(zoneId).toLocalDate(),
            null,
            creation,
            creation,
            occurrenceMaterializer.materialize(snapshot),
            values = snapshot.materializedDefaults(),
        ).also { SequenceExecutionValidator.requireValid(it, snapshot) }
    }
}

private fun SequenceConfigSnapshot.materializedDefaults(): List<SequenceExecutionFieldValue> =
    fields.mapNotNull { field ->
        when (field.type) {
            CustomFieldType.NUMBER -> field.defaultNumberScaled?.let { NumberSequenceExecutionValue(field.id, it) }
            CustomFieldType.CATEGORY ->
                field.defaultCategoryOptionId?.let { CategorySequenceExecutionValue(field.id, it) }
            CustomFieldType.TEXT -> field.defaultText?.let { TextSequenceExecutionValue(field.id, it) }
        }
    }

private fun Instant.toPersistenceInstant(): Instant = Instant.ofEpochMilli(toEpochMilli())

private const val MAX_UTC_OFFSET_MINUTES = 1_080
private const val SECONDS_PER_MINUTE = 60
