package com.alexandr5476.lifetracing.plan

import com.alexandr5476.lifetracing.domain.LocalDateTimeResolution
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.StrictLocalDateTimeResolver
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** The identity owns the persisted target; display text only becomes input after an actual edit. */
data class PlanScheduleForm(
    val source: ReusablePlanCatalogItem?,
    val identity: PlanActionIdentity?,
    val kind: PlanScheduleKind,
    val date: String,
    val time: String,
    val schedulingZoneId: ZoneId = ZoneOffset.UTC,
    val scheduleEdited: Boolean = false,
    val selectedOffset: ZoneOffset? = null,
    val offsetChoices: List<ZoneOffset> = emptyList(),
    val zoneReviewRequired: Boolean = false,
    val error: PlanMessage? = null,
    val inputRevision: Long = 0,
    val formId: UUID,
) {
    val preservesExistingSchedule: Boolean get() = identity != null && !scheduleEdited
}

/** Form preflight consumes the domain policy. Commands still carry the complete original tuple. */
internal object PlanScheduleInput {
    fun checkZone(
        form: PlanScheduleForm,
        currentZone: ZoneId,
    ): PlanScheduleForm =
        if (requiresZoneReview(form, currentZone)) {
            form.copy(
                zoneReviewRequired = true,
                selectedOffset = null,
                offsetChoices = emptyList(),
                error = PlanMessage.ZONE_CHANGED,
                inputRevision = form.inputRevision + if (form.zoneReviewRequired) 0 else 1,
            )
        } else {
            form
        }

    private fun requiresZoneReview(
        form: PlanScheduleForm,
        currentZone: ZoneId,
    ): Boolean {
        val newLocalInput = form.kind == PlanScheduleKind.EXACT_DAY && !form.preservesExistingSchedule
        val changedZone = form.zoneReviewRequired || form.schedulingZoneId != currentZone
        return newLocalInput && changedZone
    }

    fun evaluate(form: PlanScheduleForm): PlanScheduleForm =
        when {
            form.preservesExistingSchedule -> form.copy(error = null, offsetChoices = emptyList())
            form.kind == PlanScheduleKind.EXACT_DAY && form.zoneReviewRequired ->
                form.copy(
                    error = PlanMessage.ZONE_CHANGED,
                )
            else -> evaluateInput(form)
        }

    private fun evaluateInput(form: PlanScheduleForm): PlanScheduleForm {
        val date =
            runCatching { LocalDate.parse(form.date) }.getOrNull()
                ?: return form.copy(error = PlanMessage.INVALID_DATE, offsetChoices = emptyList())
        return if (form.kind == PlanScheduleKind.EXACT_DAY) {
            evaluateExact(form, date)
        } else {
            val invalidWeek = form.kind == PlanScheduleKind.WEEK && date.dayOfWeek != DayOfWeek.MONDAY
            form.copy(error = if (invalidWeek) PlanMessage.INVALID_SCHEDULE else null, offsetChoices = emptyList())
        }
    }

    private fun evaluateExact(
        form: PlanScheduleForm,
        date: LocalDate,
    ): PlanScheduleForm {
        val time =
            runCatching { LocalTime.parse(form.time) }.getOrNull()
                ?: return form.copy(error = PlanMessage.INVALID_TIME, offsetChoices = emptyList())
        val local = LocalDateTime.of(date, time)
        val schedule = PlanSchedule.ExactDay(local, form.schedulingZoneId, form.selectedOffset)
        return if (!schedule.isMillisecondRepresentable) {
            form.copy(error = PlanMessage.UNSUPPORTED_TIME_PRECISION, offsetChoices = emptyList())
        } else {
            evaluateOffsets(form, local)
        }
    }

    private fun evaluateOffsets(
        form: PlanScheduleForm,
        local: LocalDateTime,
    ): PlanScheduleForm {
        val unselected = StrictLocalDateTimeResolver.resolve(local, form.schedulingZoneId)
        val choices =
            (unselected as? LocalDateTimeResolution.Ambiguous)?.validOffsets.orEmpty().sortedBy {
                local.toInstant(it)
            }
        val result = StrictLocalDateTimeResolver.resolve(local, form.schedulingZoneId, form.selectedOffset)
        return form.copy(
            offsetChoices = choices,
            error =
                if (form.error == PlanMessage.INVALID_OFFSET &&
                    form.selectedOffset == null
                ) {
                    form.error
                } else {
                    (result as? LocalDateTimeResolution.Failure)?.message()
                },
        )
    }

    fun failed(
        form: PlanScheduleForm,
        failure: LocalDateTimeResolution.Failure,
    ): PlanScheduleForm = evaluate(form.copy(selectedOffset = null)).copy(error = failure.message())

    fun schedule(form: PlanScheduleForm): PlanSchedule =
        when (form.kind) {
            PlanScheduleKind.FLOATING_DAY -> PlanSchedule.FloatingDay(LocalDate.parse(form.date))
            PlanScheduleKind.WEEK -> PlanSchedule.Week(LocalDate.parse(form.date))
            PlanScheduleKind.EXACT_DAY ->
                PlanSchedule.ExactDay(
                    LocalDateTime.of(LocalDate.parse(form.date), LocalTime.parse(form.time)),
                    form.schedulingZoneId,
                    form.selectedOffset,
                )
        }

    private fun LocalDateTimeResolution.Failure.message(): PlanMessage =
        when (this) {
            LocalDateTimeResolution.Nonexistent -> PlanMessage.NONEXISTENT_TIME
            is LocalDateTimeResolution.Ambiguous -> PlanMessage.AMBIGUOUS_TIME
            is LocalDateTimeResolution.InvalidOffset -> PlanMessage.INVALID_OFFSET
            is LocalDateTimeResolution.UnsupportedOffsets -> PlanMessage.INVALID_SCHEDULE
        }
}
