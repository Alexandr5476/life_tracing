package com.alexandr5476.lifetracing.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private const val WEEK_LAST_DAY_OFFSET = 6L

data class WeekPlanQuery(
    val weekStart: LocalDate,
    val selectedDate: LocalDate,
    val now: Instant,
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
