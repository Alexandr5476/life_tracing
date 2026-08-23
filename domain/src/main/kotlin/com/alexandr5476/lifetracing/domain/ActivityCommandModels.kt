package com.alexandr5476.lifetracing.domain

import java.time.Instant
import java.time.ZoneId

sealed interface ActivityEntrySource {
    data class Template(
        val id: ActivityTemplateId,
    ) : ActivityEntrySource

    data class Plan(
        val id: PlanEntryId,
    ) : ActivityEntrySource

    data class OneOff(
        val draft: ActivitySnapshotDraft,
    ) : ActivityEntrySource
}

sealed interface ActivityEntryFieldReference {
    data class Template(
        val id: ActivityTemplateFieldId,
    ) : ActivityEntryFieldReference

    data class Snapshot(
        val id: ActivitySnapshotFieldId,
    ) : ActivityEntryFieldReference

    data class OneOff(
        val key: String,
    ) : ActivityEntryFieldReference
}

sealed interface ActivityEntryOptionReference {
    data class Template(
        val id: CategoryOptionId,
    ) : ActivityEntryOptionReference

    data class Snapshot(
        val id: ActivitySnapshotCategoryOptionId,
    ) : ActivityEntryOptionReference

    data class OneOff(
        val key: String,
    ) : ActivityEntryOptionReference
}

sealed interface ActivityEntryValue {
    data object Missing : ActivityEntryValue

    data class Number(
        val scaledValue: Long,
    ) : ActivityEntryValue

    data class Category(
        val option: ActivityEntryOptionReference,
    ) : ActivityEntryValue

    data class Text(
        val value: String,
    ) : ActivityEntryValue
}

data class ActivityEntryValueOverride(
    val field: ActivityEntryFieldReference,
    val value: ActivityEntryValue,
)

sealed interface ActivityHistoryTimeCorrection {
    data class Timed(
        val startedAt: Instant,
        val completedAt: Instant,
    ) : ActivityHistoryTimeCorrection

    data class NoLive(
        val completedAt: Instant,
    ) : ActivityHistoryTimeCorrection
}

data class ActivityHistoryCorrection(
    val expectedUpdatedAt: Instant,
    val time: ActivityHistoryTimeCorrection,
    val eventZoneId: ZoneId,
    val values: List<ActivityExecutionFieldValue>,
    val shortComment: String?,
)

data class ActivityHistoryItem(
    val execution: ActivityExecution,
    val snapshot: ActivityConfigSnapshot,
)

class ExpiredFinishTimerDecisionRequiredException :
    IllegalStateException(
        "Backdated FINISH Timer already reached zero; product behavior is unresolved",
    )
