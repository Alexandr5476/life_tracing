package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

object ActivityExecutionDurationCalculator {
    fun calculate(
        startedAt: Instant,
        completedAt: Instant,
        pauses: List<ActivityExecutionPause>,
    ): Duration {
        val startedAtMs = startedAt.toEpochMilli()
        val completedAtMs = completedAt.toEpochMilli()
        require(completedAtMs >= startedAtMs) { "Completion must not precede start" }
        val ordered = pauses.sortedBy { it.startedAt.toEpochMilli() }
        var previousEndMs = startedAtMs
        var pausedMillis = 0L
        ordered.forEach { pause ->
            val endedAt = requireNotNull(pause.endedAt) { "Completed execution cannot contain an open pause" }
            val pauseStartedAtMs = pause.startedAt.toEpochMilli()
            val pauseEndedAtMs = endedAt.toEpochMilli()
            require(pauseStartedAtMs >= startedAtMs && pauseEndedAtMs <= completedAtMs) {
                "Pause must be inside the execution interval"
            }
            require(pauseEndedAtMs >= pauseStartedAtMs) { "Pause end must not precede pause start" }
            require(pauseStartedAtMs >= previousEndMs) { "Pauses must not overlap" }
            pausedMillis = Math.addExact(pausedMillis, pauseEndedAtMs - pauseStartedAtMs)
            previousEndMs = pauseEndedAtMs
        }
        val elapsedMillis = completedAtMs - startedAtMs
        require(pausedMillis <= elapsedMillis) { "Paused duration cannot exceed elapsed duration" }
        return Duration.ofMillis(elapsedMillis - pausedMillis)
    }
}

object ActivityExecutionValidator {
    fun requireValid(
        execution: ActivityExecution,
        snapshot: ActivityConfigSnapshot,
    ) {
        require(execution.snapshotId == snapshot.id) { "Execution must reference the supplied snapshot" }
        when (execution.context) {
            ActivityExecutionContext.STANDALONE ->
                require(execution.sequenceExecutionId == null && execution.sequenceOccurrenceId == null) {
                    "Standalone execution cannot reference Sequence ownership"
                }
            ActivityExecutionContext.SEQUENCE_CHILD ->
                require(
                    execution.sequenceExecutionId != null &&
                        execution.sequenceOccurrenceId != null &&
                        execution.planEntryId == null &&
                        execution.completionReason == null,
                ) { "Sequence child requires both parent links and no Activity completion reason" }
        }
        val expectedStatisticsSeriesId =
            when (execution.context) {
                ActivityExecutionContext.STANDALONE ->
                    snapshot.statisticsSeriesId ?: ActivityExecutionStatistics.ONE_OFF_BUCKET_ID
                ActivityExecutionContext.SEQUENCE_CHILD -> snapshot.statisticsSeriesId
            }
        require(execution.statisticsSeriesId == expectedStatisticsSeriesId) {
            "Execution StatisticsSeries must match its snapshot and context"
        }
        require(execution.updatedAt.toEpochMilli() >= execution.createdAt.toEpochMilli()) {
            "Updated time must not precede creation"
        }
        execution.originalUtcOffsetMinutes?.let { offset ->
            require(offset in -MAX_UTC_OFFSET_MINUTES..MAX_UTC_OFFSET_MINUTES) {
                "UTC offset must be within the valid ZoneOffset range"
            }
        }
        requireValidState(execution)
        requireValidMode(execution, snapshot.timeTrackingMode)
        val primaryInstant = execution.startedAt ?: requireNotNull(execution.completedAt)
        val zonedPrimaryInstant = primaryInstant.atZone(execution.originalZoneId)
        require(execution.primaryLocalDate == zonedPrimaryInstant.toLocalDate()) {
            "Primary local date must derive from the original event timezone"
        }
        execution.originalUtcOffsetMinutes?.let { offset ->
            require(offset == zonedPrimaryInstant.offset.totalSeconds / SECONDS_PER_MINUTE) {
                "Stored UTC offset must match the original event timezone"
            }
        }
        requireValidValues(execution.values, snapshot)
    }

    fun requireValidState(execution: ActivityExecution) {
        val openPauses = execution.pauses.count { it.endedAt == null }
        when (execution.status) {
            ActivityExecutionStatus.RUNNING -> {
                require(execution.startedAt != null && execution.completedAt == null) {
                    "Running execution requires a start and no completion"
                }
                require(execution.activeDuration == null && execution.completionReason == null && openPauses == 0) {
                    "Running execution cannot have a duration, completion reason, or open pause"
                }
            }
            ActivityExecutionStatus.PAUSED -> {
                require(execution.startedAt != null && execution.completedAt == null) {
                    "Paused execution requires a start and no completion"
                }
                require(execution.activeDuration == null && execution.completionReason == null && openPauses == 1) {
                    "Paused execution requires exactly one open pause and no completion data"
                }
            }
            ActivityExecutionStatus.COMPLETED -> {
                require(execution.completedAt != null && openPauses == 0) {
                    "Completed execution requires a completion and no open pause"
                }
                if (execution.startedAt == null) {
                    require(execution.activeDuration == null && execution.pauses.isEmpty()) {
                        "Immediate execution cannot have duration or pauses"
                    }
                } else {
                    require(
                        execution.activeDuration ==
                            ActivityExecutionDurationCalculator.calculate(
                                execution.startedAt,
                                execution.completedAt,
                                execution.pauses,
                            ),
                    ) { "Stored active duration must match elapsed time minus pauses" }
                }
            }
        }
    }

    private fun requireValidMode(
        execution: ActivityExecution,
        mode: TimeTrackingMode,
    ) {
        when (mode) {
            TimeTrackingMode.STOPWATCH,
            TimeTrackingMode.TIMER,
            -> {
                require(execution.startedAt != null) { "Timed snapshot execution requires a start" }
                if (execution.status == ActivityExecutionStatus.COMPLETED) {
                    require(execution.completedAt != null && execution.activeDuration != null) {
                        "Completed timed snapshot execution requires completion and active duration"
                    }
                } else {
                    require(execution.completedAt == null && execution.activeDuration == null) {
                        "Active timed snapshot execution cannot have completion data"
                    }
                }
            }
            TimeTrackingMode.NO_LIVE_TRACKING ->
                require(
                    execution.status == ActivityExecutionStatus.COMPLETED &&
                        execution.startedAt == null &&
                        execution.completedAt != null &&
                        execution.activeDuration == null &&
                        execution.pauses.isEmpty(),
                ) { "NO_LIVE_TRACKING requires an immediate completed execution without duration or pauses" }
        }
    }

    private fun requireValidValues(
        values: List<ActivityExecutionFieldValue>,
        snapshot: ActivityConfigSnapshot,
    ) {
        require(values.map { it.snapshotFieldId }.distinct().size == values.size) {
            "Execution may contain at most one value per snapshot field"
        }
        val fields = snapshot.fields.associateBy(ActivitySnapshotField::id)
        values.forEach { value ->
            val field =
                requireNotNull(fields[value.snapshotFieldId]) {
                    "Execution value field must belong to its snapshot"
                }
            when (value) {
                is NumberExecutionValue ->
                    require(field.type == CustomFieldType.NUMBER) {
                        "Number value requires a NUMBER field"
                    }
                is CategoryExecutionValue -> {
                    require(field.type == CustomFieldType.CATEGORY) { "Category value requires a CATEGORY field" }
                    require(field.categoryOptions.any { it.id == value.optionId }) {
                        "Category option must belong to the value field"
                    }
                }
                is TextExecutionValue ->
                    require(field.type == CustomFieldType.TEXT) {
                        "Text value requires a TEXT field"
                    }
            }
        }
    }
}

class ActivityExecutionFactory(
    private val nextExecutionId: () -> ActivityExecutionId,
) {
    fun startTimed(
        snapshot: ActivityConfigSnapshot,
        startedAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
        planEntryId: PlanEntryId? = null,
    ): ActivityExecution {
        require(snapshot.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
            "NO_LIVE_TRACKING snapshots cannot start a timed execution"
        }
        val persistedStart = startedAt.toPersistenceInstant()
        val persistedCreation = createdAt.toPersistenceInstant()
        require(persistedStart <= persistedCreation) { "Start must not be in the future" }
        return base(snapshot, persistedStart, persistedCreation, zoneId, planEntryId = planEntryId)
            .copy(
                status = ActivityExecutionStatus.RUNNING,
                startedAt = persistedStart,
                primaryLocalDate = persistedStart.atZone(zoneId).toLocalDate(),
            ).validatedAgainst(snapshot)
    }

    fun createManualTimed(
        snapshot: ActivityConfigSnapshot,
        startedAt: Instant,
        completedAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
    ): ActivityExecution {
        require(snapshot.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
            "NO_LIVE_TRACKING snapshots require an immediate manual entry"
        }
        val persistedStart = startedAt.toPersistenceInstant()
        val persistedCompletion = completedAt.toPersistenceInstant()
        val persistedCreation = createdAt.toPersistenceInstant()
        require(persistedStart <= persistedCompletion && persistedCompletion <= persistedCreation) {
            "Manual interval must be ordered and not in the future"
        }
        return base(snapshot, persistedStart, persistedCreation, zoneId)
            .copy(
                status = ActivityExecutionStatus.COMPLETED,
                startedAt = persistedStart,
                completedAt = persistedCompletion,
                activeDuration =
                    ActivityExecutionDurationCalculator.calculate(
                        persistedStart,
                        persistedCompletion,
                        emptyList(),
                    ),
                primaryLocalDate = persistedStart.atZone(zoneId).toLocalDate(),
                completionReason = ActivityCompletionReason.MANUAL_HISTORY_ENTRY,
            ).validatedAgainst(snapshot)
    }

    fun completeNoLiveNow(
        snapshot: ActivityConfigSnapshot,
        at: Instant,
        zoneId: ZoneId,
        planEntryId: PlanEntryId? = null,
        createdAt: Instant = at,
    ): ActivityExecution {
        require(snapshot.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
            "Quick completion requires NO_LIVE_TRACKING"
        }
        val persistedAt = at.toPersistenceInstant()
        return base(snapshot, persistedAt, createdAt.toPersistenceInstant(), zoneId, planEntryId = planEntryId)
            .copy(
                status = ActivityExecutionStatus.COMPLETED,
                completedAt = persistedAt,
                primaryLocalDate = persistedAt.atZone(zoneId).toLocalDate(),
            ).validatedAgainst(snapshot)
    }

    @Suppress("LongParameterList") // Child creation needs both parent links plus the normal execution timestamps.
    fun startSequenceChildTimed(
        snapshot: ActivityConfigSnapshot,
        sequenceExecutionId: SequenceExecutionId,
        occurrenceId: SequenceOccurrenceId,
        startedAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
    ): ActivityExecution {
        require(snapshot.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
            "NO_LIVE_TRACKING child cannot start a timed execution"
        }
        val persistedStart = startedAt.toPersistenceInstant()
        val persistedCreation = createdAt.toPersistenceInstant()
        require(persistedStart <= persistedCreation) { "Start must not be in the future" }
        return base(
            snapshot,
            persistedStart,
            persistedCreation,
            zoneId,
            ActivityExecutionContext.SEQUENCE_CHILD,
            sequenceExecutionId,
            occurrenceId,
        ).copy(
            status = ActivityExecutionStatus.RUNNING,
            startedAt = persistedStart,
            primaryLocalDate = persistedStart.atZone(zoneId).toLocalDate(),
        ).validatedAgainst(snapshot)
    }

    fun completeSequenceChildNoLive(
        snapshot: ActivityConfigSnapshot,
        sequenceExecutionId: SequenceExecutionId,
        occurrenceId: SequenceOccurrenceId,
        at: Instant,
        zoneId: ZoneId,
    ): ActivityExecution {
        require(snapshot.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
            "Immediate child completion requires NO_LIVE_TRACKING"
        }
        val persistedAt = at.toPersistenceInstant()
        return base(
            snapshot,
            persistedAt,
            persistedAt,
            zoneId,
            ActivityExecutionContext.SEQUENCE_CHILD,
            sequenceExecutionId,
            occurrenceId,
        ).copy(
            status = ActivityExecutionStatus.COMPLETED,
            completedAt = persistedAt,
            primaryLocalDate = persistedAt.atZone(zoneId).toLocalDate(),
        ).validatedAgainst(snapshot)
    }

    fun createManualNoLiveHistory(
        snapshot: ActivityConfigSnapshot,
        completedAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
    ): ActivityExecution {
        require(snapshot.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
            "Immediate manual entry requires NO_LIVE_TRACKING"
        }
        val persistedCompletion = completedAt.toPersistenceInstant()
        val persistedCreation = createdAt.toPersistenceInstant()
        require(persistedCompletion <= persistedCreation) { "Manual completion must not be in the future" }
        return base(snapshot, persistedCompletion, persistedCreation, zoneId)
            .copy(
                status = ActivityExecutionStatus.COMPLETED,
                completedAt = persistedCompletion,
                primaryLocalDate = persistedCompletion.atZone(zoneId).toLocalDate(),
                completionReason = ActivityCompletionReason.MANUAL_HISTORY_ENTRY,
            ).validatedAgainst(snapshot)
    }

    @Suppress("LongParameterList") // Keeping the two optional parent links explicit avoids a second construction model.
    private fun base(
        snapshot: ActivityConfigSnapshot,
        eventAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
        context: ActivityExecutionContext = ActivityExecutionContext.STANDALONE,
        sequenceExecutionId: SequenceExecutionId? = null,
        sequenceOccurrenceId: SequenceOccurrenceId? = null,
        planEntryId: PlanEntryId? = null,
    ): ActivityExecution {
        ActivityConfigSnapshotValidator.requireValid(snapshot)
        val persistedEvent = eventAt.toPersistenceInstant()
        val persistedCreation = createdAt.toPersistenceInstant()
        return ActivityExecution(
            id = nextExecutionId(),
            snapshotId = snapshot.id,
            context = context,
            statisticsSeriesId =
                if (context == ActivityExecutionContext.SEQUENCE_CHILD) {
                    snapshot.statisticsSeriesId
                } else {
                    snapshot.statisticsSeriesId ?: ActivityExecutionStatistics.ONE_OFF_BUCKET_ID
                },
            status = ActivityExecutionStatus.RUNNING,
            startedAt = null,
            completedAt = null,
            activeDuration = null,
            originalZoneId = zoneId,
            originalUtcOffsetMinutes = persistedEvent.atZone(zoneId).offset.totalSeconds / SECONDS_PER_MINUTE,
            primaryLocalDate = persistedEvent.atZone(zoneId).toLocalDate(),
            completionReason = null,
            deletedAt = null,
            createdAt = persistedCreation,
            updatedAt = persistedCreation,
            sequenceExecutionId = sequenceExecutionId,
            sequenceOccurrenceId = sequenceOccurrenceId,
            planEntryId = planEntryId,
            values = snapshot.materializedDefaults(),
        )
    }
}

object ActivityExecutionTransitions {
    fun pause(
        execution: ActivityExecution,
        pauseId: ActivityExecutionPauseId,
        at: Instant,
    ): ActivityExecution {
        val persistedAt = at.toPersistenceInstant()
        require(execution.status == ActivityExecutionStatus.RUNNING) { "Only a running execution can pause" }
        require(persistedAt.toEpochMilli() >= requireNotNull(execution.startedAt).toEpochMilli()) {
            "Pause cannot precede execution start"
        }
        require(persistedAt.toEpochMilli() >= execution.updatedAt.toEpochMilli()) {
            "Pause cannot precede the previous update"
        }
        require(execution.pauses.none { it.endedAt == null }) { "Execution already has an open pause" }
        return execution.copy(
            status = ActivityExecutionStatus.PAUSED,
            updatedAt = persistedAt,
            pauses = execution.pauses + ActivityExecutionPause(pauseId, persistedAt, null),
        )
    }

    fun resume(
        execution: ActivityExecution,
        at: Instant,
    ): ActivityExecution {
        val persistedAt = at.toPersistenceInstant()
        require(execution.status == ActivityExecutionStatus.PAUSED) { "Only a paused execution can resume" }
        val openPause = execution.pauses.single { it.endedAt == null }
        require(persistedAt.toEpochMilli() >= openPause.startedAt.toEpochMilli()) { "Resume cannot precede pause" }
        require(persistedAt.toEpochMilli() >= execution.updatedAt.toEpochMilli()) {
            "Resume cannot precede the previous update"
        }
        return execution.copy(
            status = ActivityExecutionStatus.RUNNING,
            updatedAt = persistedAt,
            pauses = execution.pauses.map { if (it.id == openPause.id) it.copy(endedAt = persistedAt) else it },
        )
    }

    fun complete(
        execution: ActivityExecution,
        at: Instant,
    ): ActivityExecution {
        val persistedAt = at.toPersistenceInstant()
        require(execution.status != ActivityExecutionStatus.COMPLETED) { "Execution is already completed" }
        val startedAt = requireNotNull(execution.startedAt)
        require(persistedAt.toEpochMilli() >= startedAt.toEpochMilli()) { "Completion cannot precede start" }
        require(persistedAt.toEpochMilli() >= execution.updatedAt.toEpochMilli()) {
            "Completion cannot precede the previous update"
        }
        val pauses =
            execution.pauses.map { pause ->
                if (pause.endedAt == null) {
                    require(persistedAt.toEpochMilli() >= pause.startedAt.toEpochMilli()) {
                        "Completion cannot precede pause"
                    }
                    pause.copy(endedAt = persistedAt)
                } else {
                    pause
                }
            }
        return execution.copy(
            status = ActivityExecutionStatus.COMPLETED,
            completedAt = persistedAt,
            activeDuration = ActivityExecutionDurationCalculator.calculate(startedAt, persistedAt, pauses),
            completionReason = null,
            updatedAt = persistedAt,
            pauses = pauses,
        )
    }
}

private fun ActivityConfigSnapshot.materializedDefaults(): List<ActivityExecutionFieldValue> =
    fields.mapNotNull { field ->
        when (field.type) {
            CustomFieldType.NUMBER -> field.defaultNumberScaled?.let { NumberExecutionValue(field.id, it) }
            CustomFieldType.CATEGORY -> field.defaultCategoryOptionId?.let { CategoryExecutionValue(field.id, it) }
            CustomFieldType.TEXT -> field.defaultText?.let { TextExecutionValue(field.id, it) }
        }
    }

private fun ActivityExecution.validatedAgainst(snapshot: ActivityConfigSnapshot) =
    also { ActivityExecutionValidator.requireValid(it, snapshot) }

private const val MAX_UTC_OFFSET_MINUTES = 1_080
private const val SECONDS_PER_MINUTE = 60

private fun Instant.toPersistenceInstant(): Instant = Instant.ofEpochMilli(toEpochMilli())
