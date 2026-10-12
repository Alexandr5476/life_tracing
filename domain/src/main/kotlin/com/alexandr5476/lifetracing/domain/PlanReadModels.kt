package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private const val WEEK_LAST_DAY_OFFSET = 6L

/** Ephemeral attribution shared by every part of one calendar projection. */
data class CalendarTemporalContext(
    val now: Instant,
    val zoneId: ZoneId,
) {
    val today: LocalDate get() = now.atZone(zoneId).toLocalDate()
}

data class WeekPlanQuery(
    val weekStart: LocalDate,
    val selectedDate: LocalDate,
    val now: Instant,
    val zoneId: ZoneId? = null,
) {
    init {
        require(weekStart.dayOfWeek == java.time.DayOfWeek.MONDAY) { "Plan week must start on Monday" }
        require(selectedDate in weekStart..weekStart.plusDays(WEEK_LAST_DAY_OFFSET)) {
            "Selected date must belong to its Plan week"
        }
    }
}

data class WeekPlanRead(
    val weekStart: LocalDate,
    val selectedDate: LocalDate,
    val selectedDayPlans: List<PlanReadRow>,
    val weekPlans: List<PlanReadRow>,
    val dayPresence: List<PlanDayPresence>,
    // Nullable for legacy read adapters; canonical repositories always supply their captured context.
    val temporalContext: CalendarTemporalContext? = null,
)

data class PlanDayPresence(
    val date: LocalDate,
    val count: Int,
)

data class PlanReadRow(
    val plan: PlanEntry,
    val effectiveLocalDate: LocalDate?,
    val exactLocalTime: LocalTime?,
    val title: String,
    val shortComment: String?,
    val sourceState: PlanSourceState,
    val engaged: Boolean,
    val overdue: Boolean,
    val activityMetadata: PlanActivityRowMetadata?,
    val exactUtcOffset: ZoneOffset? = null,
)

data class PlanActivityRowMetadata(
    val timeTrackingMode: TimeTrackingMode,
    val timerTarget: Duration?,
)

data class PlanActionIdentity(
    val planEntryId: PlanEntryId,
    val kind: PlanTrackableKind,
    val activitySnapshotId: ActivitySnapshotId?,
    val sequenceSnapshotId: SequenceSnapshotId?,
    val target: PlanTarget,
    val status: PlanEntryStatus,
    val sourceRevision: Long?,
    val updatedAt: Instant,
)

class StalePlanActionException : IllegalStateException("Plan action is stale")

class PlanHistoricalOverlapException : IllegalStateException("Historical Plan interval overlaps completed History")

fun PlanEntry.actionIdentity() =
    PlanActionIdentity(
        id,
        kind,
        activitySnapshotId,
        sequenceSnapshotId,
        target,
        status,
        sourceRevision,
        updatedAt,
    )

data class FocusedPlanAction(
    val identity: PlanActionIdentity,
    val sourceState: PlanSourceState,
    val engaged: Boolean,
    val snapshot: Snapshot,
) {
    sealed interface Snapshot {
        data class Activity(
            val value: ActivityConfigSnapshot,
        ) : Snapshot

        data class Sequence(
            val value: SequenceConfigSnapshot,
            val activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
        ) : Snapshot
    }
}

data class CancelledPlanPageQuery(
    val offset: Int,
    val limit: Int,
) {
    init {
        require(offset >= 0) { "Cancelled Plan page offset cannot be negative" }
        require(limit in 1..MAX_LIMIT) { "Cancelled Plan page limit must be 1..$MAX_LIMIT" }
    }

    companion object {
        const val MAX_LIMIT = 100
    }
}

data class CancelledPlanPage(
    val items: List<PlanReadRow>,
    val hasNextPage: Boolean,
)
