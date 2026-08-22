package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanEntryValidator
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal fun PlanEntry.toEntity(): PlanEntryEntity {
    PlanEntryValidator.requireValid(this)
    return PlanEntryEntity(
        id.value,
        kind.name,
        sourceActivityTemplateId?.value,
        sourceSequenceTemplateId?.value,
        sourceRevision,
        activitySnapshotId?.value,
        sequenceSnapshotId?.value,
        target.precision.name,
        (target as? PlanTarget.FloatingDay)?.date?.toString(),
        (target as? PlanTarget.Week)?.weekStart?.toString(),
        (target as? PlanTarget.Month)?.month?.toString(),
        (target as? PlanTarget.ExactDay)?.scheduledAt?.toEpochMilli(),
        (target as? PlanTarget.ExactDay)?.creationZoneId?.id,
        status.name,
        fulfilledActivityExecutionId?.value,
        fulfilledSequenceExecutionId?.value,
        createdAt.toEpochMilli(),
        updatedAt.toEpochMilli(),
        cancelledAt?.toEpochMilli(),
        fulfilledAt?.toEpochMilli(),
    )
}

internal fun PlanEntryEntity.toDomain(): PlanEntry =
    PlanEntry(
        PlanEntryId(id),
        enumValue<PlanTrackableKind>(trackableKind, "trackable kind"),
        sourceActivityTemplateId?.let(::ActivityTemplateId),
        sourceSequenceTemplateId?.let(::SequenceTemplateId),
        sourceRevision,
        activitySnapshotId?.let(::ActivitySnapshotId),
        sequencePlanSnapshotId?.let(::SequenceSnapshotId),
        toTarget(),
        enumValue<PlanEntryStatus>(status, "status"),
        fulfilledActivityExecutionId?.let(::ActivityExecutionId),
        fulfilledSequenceExecutionId?.let(::SequenceExecutionId),
        Instant.ofEpochMilli(createdAtMs),
        Instant.ofEpochMilli(updatedAtMs),
        cancelledAtMs?.let(Instant::ofEpochMilli),
        fulfilledAtMs?.let(Instant::ofEpochMilli),
    ).also(PlanEntryValidator::requireValid)

private fun PlanEntryEntity.toTarget(): PlanTarget =
    when (precision) {
        "DAY" -> toDayTarget()
        "WEEK" -> {
            require(
                plannedWeekStart != null &&
                    plannedDay == null &&
                    plannedMonth == null &&
                    scheduledInstantMs == null &&
                    creationZoneId == null,
            ) { "Week Plan has invalid persisted temporal shape" }
            PlanTarget.Week(LocalDate.parse(plannedWeekStart))
        }
        "MONTH" -> {
            require(
                plannedMonth != null &&
                    plannedDay == null &&
                    plannedWeekStart == null &&
                    scheduledInstantMs == null &&
                    creationZoneId == null,
            ) { "Month Plan has invalid persisted temporal shape" }
            PlanTarget.Month(YearMonth.parse(plannedMonth))
        }
        else -> throw IllegalArgumentException("Unknown Plan precision code: $precision")
    }

private fun PlanEntryEntity.toDayTarget(): PlanTarget =
    if (scheduledInstantMs != null) {
        require(plannedDay == null && plannedWeekStart == null && plannedMonth == null) {
            "Exact Day Plan has invalid persisted temporal shape"
        }
        PlanTarget.ExactDay(Instant.ofEpochMilli(scheduledInstantMs), creationZoneId?.let(ZoneId::of))
    } else {
        require(plannedDay != null && plannedWeekStart == null && plannedMonth == null) {
            "Floating Day Plan has invalid persisted temporal shape"
        }
        require(creationZoneId == null) { "Floating Day Plan cannot retain a creation zone" }
        PlanTarget.FloatingDay(LocalDate.parse(plannedDay))
    }

private inline fun <reified T : Enum<T>> enumValue(
    value: String,
    label: String,
): T =
    enumValues<T>().singleOrNull { it.name == value }
        ?: throw IllegalArgumentException("Unknown Plan $label code: $value")
