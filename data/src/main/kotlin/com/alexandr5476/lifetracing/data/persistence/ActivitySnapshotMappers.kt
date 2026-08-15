package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshotValidator
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import java.time.Duration
import java.time.Instant

internal fun ActivityConfigSnapshot.toEntityAggregate(): ActivitySnapshotAggregateEntity {
    ActivityConfigSnapshotValidator.requireValid(this)
    return ActivitySnapshotAggregateEntity(
        snapshot =
            ActivitySnapshotEntity(
                id = id.value,
                name = name,
                shortComment = shortComment,
                timeTrackingMode = timeTrackingMode.toSnapshotStorageCode(),
                timerTargetMs = timerTarget?.toMillis(),
                sourceTemplateId = sourceTemplateId?.value,
                sourceRevision = sourceRevision,
                statisticsSeriesId = statisticsSeriesId?.value,
                locallyModified = locallyModified,
                createdAtMs = createdAt.toEpochMilli(),
            ),
        settings = settings.toSnapshotEntity(id),
        fields = fields.map { it.toEntity(id) },
        options = fields.flatMap { field -> field.categoryOptions.map { it.toEntity(field.id) } },
    )
}

internal fun ActivitySnapshotAggregateEntity.toDomain(): ActivityConfigSnapshot {
    val optionsByField = options.groupBy(ActivitySnapshotCategoryOptionEntity::snapshotFieldId)
    return ActivityConfigSnapshot(
        id = ActivitySnapshotId(snapshot.id),
        name = snapshot.name,
        shortComment = snapshot.shortComment,
        timeTrackingMode = snapshot.timeTrackingMode.toSnapshotTimeTrackingMode(),
        timerTarget = snapshot.timerTargetMs?.let(Duration::ofMillis),
        sourceTemplateId = snapshot.sourceTemplateId?.let(::ActivityTemplateId),
        sourceRevision = snapshot.sourceRevision,
        statisticsSeriesId = snapshot.statisticsSeriesId?.let(::StatisticsSeriesId),
        locallyModified = snapshot.locallyModified,
        createdAt = Instant.ofEpochMilli(snapshot.createdAtMs),
        settings = settings.toDomainSettings(),
        fields = fields.map { field -> field.toDomain(optionsByField[field.id].orEmpty()) },
    ).also(ActivityConfigSnapshotValidator::requireValid)
}

private fun ActivityTemplateSettings.toSnapshotEntity(snapshotId: ActivitySnapshotId) =
    ActivitySnapshotSettingsEntity(
        snapshotId = snapshotId.value,
        showSeconds = showSeconds,
        startCountdownMs = startCountdown.toMillis(),
        timerZeroBehavior = timerZeroBehavior.toSnapshotStorageCode(),
        timerEndSound = timerEndSound,
        timerEndVibration = timerEndVibration,
        keepScreenAwake = keepScreenAwake,
        confirmManualFinish = confirmManualFinish,
    )

private fun ActivitySnapshotSettingsEntity.toDomainSettings() =
    ActivityTemplateSettings(
        showSeconds = showSeconds,
        startCountdown = Duration.ofMillis(startCountdownMs),
        timerZeroBehavior = timerZeroBehavior.toSnapshotTimerZeroBehavior(),
        timerEndSound = timerEndSound,
        timerEndVibration = timerEndVibration,
        keepScreenAwake = keepScreenAwake,
        confirmManualFinish = confirmManualFinish,
    )

private fun TimeTrackingMode.toSnapshotStorageCode() =
    when (this) {
        TimeTrackingMode.STOPWATCH -> "STOPWATCH"
        TimeTrackingMode.TIMER -> "TIMER"
        TimeTrackingMode.NO_LIVE_TRACKING -> "NO_LIVE_TRACKING"
    }

private fun String.toSnapshotTimeTrackingMode() =
    when (this) {
        "STOPWATCH" -> TimeTrackingMode.STOPWATCH
        "TIMER" -> TimeTrackingMode.TIMER
        "NO_LIVE_TRACKING" -> TimeTrackingMode.NO_LIVE_TRACKING
        else -> error("Unknown snapshot time tracking mode code: $this")
    }

private fun TimerZeroBehavior.toSnapshotStorageCode() =
    when (this) {
        TimerZeroBehavior.FINISH -> "FINISH"
        TimerZeroBehavior.OVERTIME -> "OVERTIME"
    }

private fun String.toSnapshotTimerZeroBehavior() =
    when (this) {
        "FINISH" -> TimerZeroBehavior.FINISH
        "OVERTIME" -> TimerZeroBehavior.OVERTIME
        else -> error("Unknown snapshot timer zero behavior code: $this")
    }
