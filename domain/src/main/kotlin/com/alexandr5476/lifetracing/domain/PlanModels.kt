package com.alexandr5476.lifetracing.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

@JvmInline
value class PlanEntryId(
    val value: String,
)

enum class PlanTrackableKind {
    ACTIVITY,
    SEQUENCE,
}

enum class PlanningPrecision {
    DAY,
    WEEK,
    MONTH,
}

enum class PlanEntryStatus {
    PLANNED,
    FULFILLED,
    CANCELLED,
}

sealed interface PlanTarget {
    val precision: PlanningPrecision

    data class FloatingDay(
        val date: LocalDate,
    ) : PlanTarget {
        override val precision = PlanningPrecision.DAY
    }

    data class ExactDay(
        val scheduledAt: Instant,
        val creationZoneId: ZoneId?,
    ) : PlanTarget {
        override val precision = PlanningPrecision.DAY
    }

    data class Week(
        val weekStart: LocalDate,
    ) : PlanTarget {
        override val precision = PlanningPrecision.WEEK
    }

    data class Month(
        val month: YearMonth,
    ) : PlanTarget {
        override val precision = PlanningPrecision.MONTH
    }
}

data class PlanEntry(
    val id: PlanEntryId,
    val kind: PlanTrackableKind,
    val sourceActivityTemplateId: ActivityTemplateId?,
    val sourceSequenceTemplateId: SequenceTemplateId?,
    val sourceRevision: Long?,
    val activitySnapshotId: ActivitySnapshotId?,
    val sequenceSnapshotId: SequenceSnapshotId?,
    val target: PlanTarget,
    val status: PlanEntryStatus,
    val fulfilledActivityExecutionId: ActivityExecutionId?,
    val fulfilledSequenceExecutionId: SequenceExecutionId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val cancelledAt: Instant?,
    val fulfilledAt: Instant?,
)

object PlanEntryValidator {
    fun requireValid(plan: PlanEntry) {
        require(plan.updatedAt >= plan.createdAt) { "Plan update cannot precede creation" }
        requireValidSource(plan)
        requireValidKind(plan)
        requireValidTarget(plan)
        requireValidStatus(plan)
    }

    private fun requireValidSource(plan: PlanEntry) {
        if (plan.sourceActivityTemplateId != null || plan.sourceSequenceTemplateId != null) {
            require(plan.sourceRevision != null && plan.sourceRevision >= 1) {
                "Source-linked Plan revision must be at least 1"
            }
        } else if (plan.sourceRevision != null) {
            require(plan.sourceRevision >= 1) { "Retained source revision must be at least 1" }
        }
    }

    private fun requireValidKind(plan: PlanEntry) {
        when (plan.kind) {
            PlanTrackableKind.ACTIVITY ->
                require(
                    plan.activitySnapshotId != null &&
                        plan.sequenceSnapshotId == null &&
                        plan.sourceSequenceTemplateId == null &&
                        plan.fulfilledSequenceExecutionId == null,
                ) { "Activity Plan must contain only Activity identities" }
            PlanTrackableKind.SEQUENCE ->
                require(
                    plan.sequenceSnapshotId != null &&
                        plan.activitySnapshotId == null &&
                        plan.sourceActivityTemplateId == null &&
                        plan.fulfilledActivityExecutionId == null,
                ) { "Sequence Plan must contain only Sequence identities" }
        }
        require(
            plan.fulfilledActivityExecutionId == null || plan.fulfilledSequenceExecutionId == null,
        ) { "Plan cannot link both fulfillment kinds" }
    }

    private fun requireValidTarget(plan: PlanEntry) {
        if (plan.target is PlanTarget.Week) {
            require(plan.target.weekStart.dayOfWeek == DayOfWeek.MONDAY) { "Plan week must start on Monday" }
        }
    }

    private fun requireValidStatus(plan: PlanEntry) {
        when (plan.status) {
            PlanEntryStatus.PLANNED ->
                require(
                    plan.cancelledAt == null &&
                        plan.fulfilledAt == null &&
                        plan.fulfilledActivityExecutionId == null &&
                        plan.fulfilledSequenceExecutionId == null,
                ) { "Planned Plan cannot contain terminal data" }
            PlanEntryStatus.CANCELLED ->
                require(
                    plan.cancelledAt != null &&
                        plan.fulfilledAt == null &&
                        plan.fulfilledActivityExecutionId == null &&
                        plan.fulfilledSequenceExecutionId == null,
                ) { "Cancelled Plan requires only cancellation data" }
            PlanEntryStatus.FULFILLED ->
                require(plan.cancelledAt == null && plan.fulfilledAt != null) {
                    "Fulfilled Plan requires fulfillment time and no cancellation"
                }
        }
    }
}

object PlanEntryTransitions {
    fun cancel(
        plan: PlanEntry,
        at: Instant,
    ): PlanEntry =
        plan
            .requirePlanned(at)
            .copy(
                status = PlanEntryStatus.CANCELLED,
                cancelledAt = at,
                updatedAt = at,
            ).validated()

    fun restore(
        plan: PlanEntry,
        at: Instant,
    ): PlanEntry {
        require(plan.status == PlanEntryStatus.CANCELLED) { "Only a cancelled Plan can be restored" }
        require(at >= plan.updatedAt) { "Plan update time is out of order" }
        return plan.copy(status = PlanEntryStatus.PLANNED, cancelledAt = null, updatedAt = at).validated()
    }

    fun reschedule(
        plan: PlanEntry,
        target: PlanTarget,
        at: Instant,
    ): PlanEntry = plan.requirePlanned(at).copy(target = target, updatedAt = at).validated()

    fun fulfill(
        plan: PlanEntry,
        activityExecutionId: ActivityExecutionId?,
        sequenceExecutionId: SequenceExecutionId?,
        at: Instant,
    ): PlanEntry =
        plan
            .requirePlanned(at)
            .copy(
                status = PlanEntryStatus.FULFILLED,
                fulfilledActivityExecutionId = activityExecutionId,
                fulfilledSequenceExecutionId = sequenceExecutionId,
                fulfilledAt = at,
                updatedAt = at,
            ).validated()

    private fun PlanEntry.requirePlanned(at: Instant): PlanEntry {
        require(status == PlanEntryStatus.PLANNED) { "Plan is not planned" }
        require(at >= updatedAt) { "Plan update time is out of order" }
        return this
    }

    private fun PlanEntry.validated() = also(PlanEntryValidator::requireValid)
}

object PlanOverdueCalculator {
    private const val DAYS_PER_WEEK = 7L

    fun isOverdue(
        plan: PlanEntry,
        now: Instant,
        zoneId: ZoneId,
    ): Boolean {
        if (plan.status != PlanEntryStatus.PLANNED) return false
        val today = now.atZone(zoneId).toLocalDate()
        return when (val target = plan.target) {
            is PlanTarget.ExactDay -> now > target.scheduledAt
            is PlanTarget.FloatingDay -> today > target.date
            is PlanTarget.Week -> today >= target.weekStart.plusDays(DAYS_PER_WEEK)
            is PlanTarget.Month -> YearMonth.from(today) > target.month
        }
    }
}

enum class PlanSourceState {
    CURRENT,
    CHANGED,
    ARCHIVED,
    UNAVAILABLE,
}

object PlanSourceStateResolver {
    fun resolve(
        sourceRevision: Long?,
        currentRevision: Long?,
        archived: Boolean,
    ): PlanSourceState =
        when {
            currentRevision == null -> PlanSourceState.UNAVAILABLE
            archived -> PlanSourceState.ARCHIVED
            currentRevision == sourceRevision -> PlanSourceState.CURRENT
            else -> PlanSourceState.CHANGED
        }
}

data class PlanCalendarEntry(
    val plan: PlanEntry,
    val effectiveLocalDate: LocalDate,
    val exactLocalTime: LocalTime?,
)

data class PlanListEntry(
    val plan: PlanEntry,
    val name: String,
    val shortComment: String?,
    val sourceState: PlanSourceState,
)

fun interface CurrentZoneIdProvider {
    fun currentZoneId(): ZoneId
}
