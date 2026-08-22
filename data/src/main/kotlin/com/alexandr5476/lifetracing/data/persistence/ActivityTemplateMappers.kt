@file:Suppress("TooManyFunctions")

package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.ActivityTemplateUserState
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import java.time.Duration
import java.time.Instant

internal fun ActivityTemplate.toEntity() =
    ActivityTemplateEntity(
        id = id.value,
        name = name,
        shortComment = shortComment,
        timeTrackingMode = timeTrackingMode.toStorageCode(),
        timerTargetMs = timerTarget?.toMillis(),
        statisticsSeriesId = statisticsSeriesId.value,
        revision = revision,
        createdAtMs = createdAt.toEpochMilli(),
        updatedAtMs = updatedAt.toEpochMilli(),
        deletedAtMs = deletedAt?.toEpochMilli(),
        folderId = folderId?.value,
    )

internal fun ActivityTemplateEntity.toDomain(
    settings: ActivityTemplateSettings,
    fields: List<ActivityTemplateField>,
    tagIds: Set<TagId>,
) = ActivityTemplate(
    id = ActivityTemplateId(id),
    name = name,
    shortComment = shortComment,
    timeTrackingMode = timeTrackingMode.toTimeTrackingMode(),
    timerTarget = timerTargetMs?.let(Duration::ofMillis),
    statisticsSeriesId = StatisticsSeriesId(statisticsSeriesId),
    revision = revision,
    createdAt = Instant.ofEpochMilli(createdAtMs),
    updatedAt = Instant.ofEpochMilli(updatedAtMs),
    deletedAt = deletedAtMs?.let(Instant::ofEpochMilli),
    folderId = folderId?.let(::FolderId),
    settings = settings,
    fields = fields,
    tagIds = tagIds,
)

internal fun ActivityTemplateAggregateEntity.toDomain(): ActivityTemplate {
    val options = options.groupBy(ActivityTemplateCategoryOptionEntity::activityTemplateFieldId)
    return template
        .toDomain(
            settings.toDomain(),
            fields.map { field ->
                field.toDomain(options[field.id].orEmpty().map(ActivityTemplateCategoryOptionEntity::toDomain))
            },
            tags.map { TagId(it.tagId) }.toSet(),
        ).also(com.alexandr5476.lifetracing.domain.ActivityTemplateValidator::requireValid)
}

internal fun ActivityTemplateSettings.toEntity(templateId: ActivityTemplateId) =
    ActivityTemplateSettingsEntity(
        activityTemplateId = templateId.value,
        showSeconds = showSeconds,
        startCountdownMs = startCountdown.toMillis(),
        timerZeroBehavior = timerZeroBehavior.toStorageCode(),
        timerEndSound = timerEndSound,
        timerEndVibration = timerEndVibration,
        keepScreenAwake = keepScreenAwake,
        confirmManualFinish = confirmManualFinish,
    )

internal fun ActivityTemplateSettingsEntity.toDomain() =
    ActivityTemplateSettings(
        showSeconds = showSeconds,
        startCountdown = Duration.ofMillis(startCountdownMs),
        timerZeroBehavior = timerZeroBehavior.toTimerZeroBehavior(),
        timerEndSound = timerEndSound,
        timerEndVibration = timerEndVibration,
        keepScreenAwake = keepScreenAwake,
        confirmManualFinish = confirmManualFinish,
    )

internal fun ActivityTemplateUserState.toEntity(templateId: ActivityTemplateId) =
    ActivityTemplateUserStateEntity(templateId.value, pinnedRank, lastUsedAt?.toEpochMilli())

internal fun ActivityTemplateUserStateEntity.toDomain() =
    ActivityTemplateUserState(pinnedRank, lastUsedAtMs?.let(Instant::ofEpochMilli))

private fun TimeTrackingMode.toStorageCode() =
    when (this) {
        TimeTrackingMode.STOPWATCH -> "STOPWATCH"
        TimeTrackingMode.TIMER -> "TIMER"
        TimeTrackingMode.NO_LIVE_TRACKING -> "NO_LIVE_TRACKING"
    }

private fun String.toTimeTrackingMode() =
    when (this) {
        "STOPWATCH" -> TimeTrackingMode.STOPWATCH
        "TIMER" -> TimeTrackingMode.TIMER
        "NO_LIVE_TRACKING" -> TimeTrackingMode.NO_LIVE_TRACKING
        else -> error("Unknown time tracking mode code: $this")
    }

private fun TimerZeroBehavior.toStorageCode() =
    when (this) {
        TimerZeroBehavior.FINISH -> "FINISH"
        TimerZeroBehavior.OVERTIME -> "OVERTIME"
    }

private fun String.toTimerZeroBehavior() =
    when (this) {
        "FINISH" -> TimerZeroBehavior.FINISH
        "OVERTIME" -> TimerZeroBehavior.OVERTIME
        else -> error("Unknown timer zero behavior code: $this")
    }
