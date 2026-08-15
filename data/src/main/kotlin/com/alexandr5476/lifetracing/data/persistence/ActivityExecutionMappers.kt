package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivityCompletionReason
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionContext
import com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPause
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityExecutionValidator
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun ActivityExecution.toEntityAggregate(): ActivityExecutionAggregateEntity {
    ActivityExecutionValidator.requireValidState(this)
    require(context == ActivityExecutionContext.STANDALONE) {
        "Sequence ownership is staged in schema v4 but is not a domain behavior yet"
    }
    return ActivityExecutionAggregateEntity(
        execution =
            ActivityExecutionEntity(
                id = id.value,
                snapshotId = snapshotId.value,
                contextType = context.name,
                sequenceExecutionId = null,
                sequenceOccurrenceId = null,
                planEntryId = null,
                statisticsSeriesId = statisticsSeriesId?.value,
                status = status.name,
                startedAtMs = startedAt?.toEpochMilli(),
                completedAtMs = completedAt?.toEpochMilli(),
                activeDurationMs = activeDuration?.toMillis(),
                originalZoneId = originalZoneId.id,
                originalUtcOffsetMinutes = originalUtcOffsetMinutes,
                primaryLocalDate = primaryLocalDate.toString(),
                completionReason = completionReason?.name,
                deletedAtMs = deletedAt?.toEpochMilli(),
                createdAtMs = createdAt.toEpochMilli(),
                updatedAtMs = updatedAt.toEpochMilli(),
            ),
        pauses =
            pauses.map { pause ->
                ActivityExecutionPauseEntity(
                    id = pause.id.value,
                    activityExecutionId = id.value,
                    startedAtMs = pause.startedAt.toEpochMilli(),
                    endedAtMs = pause.endedAt?.toEpochMilli(),
                )
            },
        values = values.map { it.toEntity(id) },
    )
}

internal fun ActivityExecutionAggregateEntity.toDomain(): ActivityExecution =
    ActivityExecution(
        id = ActivityExecutionId(execution.id),
        snapshotId = ActivitySnapshotId(execution.snapshotId),
        context = ActivityExecutionContext.valueOf(execution.contextType),
        statisticsSeriesId = execution.statisticsSeriesId?.let(::StatisticsSeriesId),
        status = ActivityExecutionStatus.valueOf(execution.status),
        startedAt = execution.startedAtMs?.let(Instant::ofEpochMilli),
        completedAt = execution.completedAtMs?.let(Instant::ofEpochMilli),
        activeDuration = execution.activeDurationMs?.let(Duration::ofMillis),
        originalZoneId = ZoneId.of(execution.originalZoneId),
        originalUtcOffsetMinutes = execution.originalUtcOffsetMinutes,
        primaryLocalDate = LocalDate.parse(execution.primaryLocalDate),
        completionReason = execution.completionReason?.let(ActivityCompletionReason::valueOf),
        deletedAt = execution.deletedAtMs?.let(Instant::ofEpochMilli),
        createdAt = Instant.ofEpochMilli(execution.createdAtMs),
        updatedAt = Instant.ofEpochMilli(execution.updatedAtMs),
        pauses =
            pauses.map { pause ->
                ActivityExecutionPause(
                    id = ActivityExecutionPauseId(pause.id),
                    startedAt = Instant.ofEpochMilli(pause.startedAtMs),
                    endedAt = pause.endedAtMs?.let(Instant::ofEpochMilli),
                )
            },
        values = values.map(ActivityExecutionFieldValueEntity::toDomain),
    ).also(ActivityExecutionValidator::requireValidState)

private fun ActivityExecutionFieldValue.toEntity(executionId: ActivityExecutionId) =
    when (this) {
        is NumberExecutionValue ->
            ActivityExecutionFieldValueEntity(executionId.value, snapshotFieldId.value, scaledValue, null, null)
        is CategoryExecutionValue ->
            ActivityExecutionFieldValueEntity(executionId.value, snapshotFieldId.value, null, optionId.value, null)
        is TextExecutionValue ->
            ActivityExecutionFieldValueEntity(executionId.value, snapshotFieldId.value, null, null, value)
    }

private fun ActivityExecutionFieldValueEntity.toDomain(): ActivityExecutionFieldValue =
    when {
        numberScaled != null -> NumberExecutionValue(ActivitySnapshotFieldId(snapshotFieldId), numberScaled)
        categoryOptionId != null ->
            CategoryExecutionValue(
                ActivitySnapshotFieldId(snapshotFieldId),
                ActivitySnapshotCategoryOptionId(categoryOptionId),
            )
        textValue != null -> TextExecutionValue(ActivitySnapshotFieldId(snapshotFieldId), textValue)
        else -> error("Execution field value has no typed value")
    }
