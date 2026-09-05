package com.alexandr5476.lifetracing.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class DailyQuery(
    val selectedDate: LocalDate,
    val now: Instant,
    val completedHistoryLimit: Int,
) {
    init {
        require(completedHistoryLimit > 0) { "Daily completed-history limit must be positive" }
    }
}

data class DailyRead(
    val dayPlans: List<DailyPlan>,
    val weekPlans: List<DailyPlan>,
    val completedHistory: List<CompletedHistoryRoot>,
    val active: DailyActive?,
)

data class DailyPlan(
    val plan: PlanEntry,
    val effectiveLocalDate: LocalDate,
    val exactLocalTime: LocalTime?,
    val snapshot: DailyPlanSnapshot,
    val sourceState: PlanSourceState,
    val engaged: Boolean,
    val overdue: Boolean,
)

sealed interface DailyPlanSnapshot {
    val title: String
    val shortComment: String?

    data class Activity(
        val value: ActivityConfigSnapshot,
    ) : DailyPlanSnapshot {
        override val title = value.name
        override val shortComment = value.shortComment
    }

    data class Sequence(
        val value: DailySequencePlanMetadata,
    ) : DailyPlanSnapshot {
        override val title = value.name
        override val shortComment = value.shortComment
    }
}

data class DailySequencePlanMetadata(
    val id: SequenceSnapshotId,
    val name: String,
    val shortComment: String?,
    val sourceTemplateId: SequenceTemplateId?,
    val sourceRevision: Long?,
)

sealed interface DailyActive {
    val runtime: ActiveRuntime

    data class Activity(
        override val runtime: ActiveActivityRuntime,
    ) : DailyActive

    data class Sequence(
        override val runtime: ActiveSequenceRuntime,
        val state: DailyActiveSequenceState,
        val current: DailySequenceOccurrence?,
        val next: DailySequenceOccurrence?,
    ) : DailyActive
}

enum class DailyActiveSequenceState {
    RUNNING_CURRENT,
    PAUSED_CURRENT,
    WAITING_NEXT,
    RUNNING_TRANSITION_COUNTDOWN,
    PAUSED_TRANSITION_COUNTDOWN,
}

data class DailySequenceOccurrence(
    val occurrence: RuntimeOccurrence,
    val activity: ActivityConfigSnapshot,
    val effectiveSettings: EffectiveSequenceStepSettings,
)
