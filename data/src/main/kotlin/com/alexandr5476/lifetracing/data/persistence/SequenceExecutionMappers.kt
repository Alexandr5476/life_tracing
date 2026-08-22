@file:Suppress("TooManyFunctions") // Explicit aggregate mappers keep persisted codes and owners visible.

package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CategorySequenceExecutionValue
import com.alexandr5476.lifetracing.domain.NumberSequenceExecutionValue
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.RuntimeOccurrence
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecution
import com.alexandr5476.lifetracing.domain.SequenceExecutionFieldValue
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionValidator
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TextSequenceExecutionValue
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun SequenceExecution.toEntityAggregate(): SequenceExecutionAggregateEntity =
    SequenceExecutionAggregateEntity(
        SequenceExecutionEntity(
            id.value,
            snapshotId.value,
            planEntryId?.value,
            statisticsSeriesId?.value,
            status.name,
            startedAt.toEpochMilli(),
            endedAt?.toEpochMilli(),
            activeDuration?.toMillis(),
            pauseDuration?.toMillis(),
            wallDuration?.toMillis(),
            originalZoneId.id,
            originalUtcOffsetMinutes,
            primaryLocalDate.toString(),
            currentOccurrenceId?.value,
            createdAt.toEpochMilli(),
            updatedAt.toEpochMilli(),
        ),
        occurrences.map { it.toEntity(id) },
        intervals.map { it.toEntity(id) },
        values.map { it.toEntity(id) },
    )

internal fun SequenceExecutionAggregateEntity.toDomain(): SequenceExecution =
    SequenceExecution(
        SequenceExecutionId(execution.id),
        SequenceSnapshotId(execution.snapshotId),
        execution.statisticsSeriesId?.let(::StatisticsSeriesId),
        execution.status.toExecutionStatus(),
        Instant.ofEpochMilli(execution.startedAtMs),
        execution.endedAtMs?.let(Instant::ofEpochMilli),
        execution.activeDurationMs?.let(Duration::ofMillis),
        execution.pauseDurationMs?.let(Duration::ofMillis),
        execution.wallDurationMs?.let(Duration::ofMillis),
        ZoneId.of(execution.originalZoneId),
        execution.originalUtcOffsetMinutes,
        LocalDate.parse(execution.primaryLocalDate),
        execution.currentOccurrenceId?.let(::SequenceOccurrenceId),
        Instant.ofEpochMilli(execution.createdAtMs),
        Instant.ofEpochMilli(execution.updatedAtMs),
        occurrences.map(SequenceOccurrenceEntity::toDomain),
        intervals.map(SequenceIntervalEntity::toDomain),
        values.map(SequenceExecutionFieldValueEntity::toDomain),
        execution.planEntryId?.let(::PlanEntryId),
    ).also(SequenceExecutionValidator::requireRootState)

private fun RuntimeOccurrence.toEntity(executionId: SequenceExecutionId) =
    SequenceOccurrenceEntity(
        id.value,
        executionId.value,
        sourceSequenceSnapshotNodeId?.value,
        activitySnapshotId.value,
        runtimePosition,
        repeatSourceSnapshotNodeId?.value,
        repeatIteration,
        status.name,
        enteredAt?.toEpochMilli(),
        completedAt?.toEpochMilli(),
        completionReason?.name,
        isRuntimeAdded,
        isDeletedFromHistory,
    )

private fun SequenceOccurrenceEntity.toDomain() =
    RuntimeOccurrence(
        SequenceOccurrenceId(id),
        sourceSequenceSnapshotNodeId?.let(::SequenceSnapshotNodeId),
        ActivitySnapshotId(activitySnapshotId),
        runtimePosition,
        repeatSourceSnapshotNodeId?.let(::SequenceSnapshotNodeId),
        repeatIteration,
        status.toOccurrenceStatus(),
        enteredAtMs?.let(Instant::ofEpochMilli),
        completedAtMs?.let(Instant::ofEpochMilli),
        completionReason?.toOccurrenceCompletionReason(),
        isRuntimeAdded,
        isDeletedFromHistory,
    )

private fun SequenceInterval.toEntity(executionId: SequenceExecutionId) =
    SequenceIntervalEntity(
        id.value,
        executionId.value,
        kind.name,
        startedAt.toEpochMilli(),
        endedAt?.toEpochMilli(),
        occurrenceId?.value,
    )

private fun SequenceIntervalEntity.toDomain() =
    SequenceInterval(
        SequenceIntervalId(id),
        kind.toIntervalKind(),
        Instant.ofEpochMilli(startedAtMs),
        endedAtMs?.let(Instant::ofEpochMilli),
        occurrenceId?.let(::SequenceOccurrenceId),
    )

private fun SequenceExecutionFieldValue.toEntity(executionId: SequenceExecutionId) =
    when (this) {
        is NumberSequenceExecutionValue ->
            SequenceExecutionFieldValueEntity(executionId.value, snapshotFieldId.value, scaledValue, null, null)
        is CategorySequenceExecutionValue ->
            SequenceExecutionFieldValueEntity(executionId.value, snapshotFieldId.value, null, optionId.value, null)
        is TextSequenceExecutionValue ->
            SequenceExecutionFieldValueEntity(executionId.value, snapshotFieldId.value, null, null, value)
    }

private fun SequenceExecutionFieldValueEntity.toDomain(): SequenceExecutionFieldValue =
    when {
        numberScaled != null -> NumberSequenceExecutionValue(SequenceSnapshotFieldId(snapshotFieldId), numberScaled)
        categoryOptionId != null ->
            CategorySequenceExecutionValue(
                SequenceSnapshotFieldId(snapshotFieldId),
                SequenceSnapshotCategoryOptionId(categoryOptionId),
            )
        textValue != null -> TextSequenceExecutionValue(SequenceSnapshotFieldId(snapshotFieldId), textValue)
        else -> error("Sequence execution field value has no typed value")
    }

private fun String.toExecutionStatus() =
    when (this) {
        "RUNNING" -> SequenceExecutionStatus.RUNNING
        "PAUSED" -> SequenceExecutionStatus.PAUSED
        "COMPLETED" -> SequenceExecutionStatus.COMPLETED
        "ENDED_EARLY" -> SequenceExecutionStatus.ENDED_EARLY
        else -> error("Unknown Sequence execution status code: $this")
    }

private fun String.toOccurrenceStatus() =
    when (this) {
        "NOT_STARTED" -> RuntimeOccurrenceStatus.NOT_STARTED
        "CURRENT" -> RuntimeOccurrenceStatus.CURRENT
        "COMPLETED" -> RuntimeOccurrenceStatus.COMPLETED
        "SKIPPED" -> RuntimeOccurrenceStatus.SKIPPED
        "DELETED_EXECUTION" -> RuntimeOccurrenceStatus.DELETED_EXECUTION
        else -> error("Unknown occurrence status code: $this")
    }

private fun String.toOccurrenceCompletionReason() =
    when (this) {
        "NATURAL_TIMER_END" -> OccurrenceCompletionReason.NATURAL_TIMER_END
        "MANUAL_FINISH" -> OccurrenceCompletionReason.MANUAL_FINISH
        "ADVANCED_TO_NEXT" -> OccurrenceCompletionReason.ADVANCED_TO_NEXT
        "JUMP" -> OccurrenceCompletionReason.JUMP
        "SEQUENCE_ENDED_EARLY" -> OccurrenceCompletionReason.SEQUENCE_ENDED_EARLY
        else -> error("Unknown occurrence completion reason code: $this")
    }

private fun String.toIntervalKind() =
    when (this) {
        "ACTIVE_STEP" -> SequenceIntervalKind.ACTIVE_STEP
        "STEP_PAUSE" -> SequenceIntervalKind.STEP_PAUSE
        "EXPLICIT_PAUSE" -> SequenceIntervalKind.EXPLICIT_PAUSE
        "IMPLICIT_IDLE" -> SequenceIntervalKind.IMPLICIT_IDLE
        "TRANSITION_COUNTDOWN" -> SequenceIntervalKind.TRANSITION_COUNTDOWN
        else -> error("Unknown Sequence interval kind code: $this")
    }
