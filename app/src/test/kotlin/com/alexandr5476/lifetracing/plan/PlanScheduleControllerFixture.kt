package com.alexandr5476.lifetracing.plan

import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal class PlanScheduleControllerFixture(
    scope: CoroutineScope,
    commit: (PlanMutation) -> Unit = {},
) {
    var zone: ZoneId = ZoneId.of("Europe/Berlin")
    val signal = MutableStateFlow(0L)
    val mutations = mutableListOf<PlanMutation>()
    var readCount = 0
    val controller =
        PlanController(
            scope,
            {
                readCount++
                WeekPlanRead(it.weekStart, it.selectedDate, emptyList(), emptyList(), emptyList())
            },
            { _, _, _ ->
                listOf(
                    ReusablePlanCatalogItem(
                        LibraryTemplateId.Activity(ActivityTemplateId("activity")),
                        "Template",
                        null,
                        null,
                    ),
                    ReusablePlanCatalogItem(
                        LibraryTemplateId.Sequence(SequenceTemplateId("sequence")),
                        "Template",
                        null,
                        null,
                    ),
                )
            },
            { CancelledPlanPage(emptyList(), false) },
            {
                mutations += it
                commit(it)
            },
            { Instant.parse("2026-10-24T10:00:00Z") },
            { zone },
            signal,
            LocalDateBoundaryScheduler { _, _, _, _ -> },
        )
    val form get() = requireNotNull(controller.state.value.form)
    val schedule get() =
        when (val mutation = mutations.last()) {
            is PlanMutation.CreateActivity -> mutation.schedule
            is PlanMutation.CreateSequence -> mutation.schedule
            is PlanMutation.Reschedule -> mutation.schedule
            else -> error("Expected new temporal input")
        } as PlanSchedule.ExactDay

    suspend fun newExact(
        sequence: Boolean,
        date: String = "2026-10-25",
        time: String = "02:30",
    ) {
        val id =
            if (sequence) {
                LibraryTemplateId.Sequence(
                    SequenceTemplateId("sequence"),
                )
            } else {
                LibraryTemplateId.Activity(ActivityTemplateId("activity"))
            }
        controller.dispatch(PlanAction.OpenCatalog)
        val catalog =
            kotlinx.coroutines.withTimeout(2_000) {
                controller.state.first { it.catalog?.loading == false }.catalog!!
            }
        controller.dispatch(PlanAction.SelectCatalogItem(catalog.items.single { it.id == id }, catalog.context))
        controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.EXACT_DAY, controller.formId))
        controller.dispatch(PlanAction.ChangeScheduleDate(date, controller.formId))
        controller.dispatch(PlanAction.ChangeScheduleTime(time, controller.formId))
    }

    fun selection(offset: ZoneOffset) =
        PlanAction.SelectScheduleOffset(
            offset,
            form.date,
            form.time,
            form.schedulingZoneId,
            form.inputRevision,
            form.formId,
        )

    fun choose(offset: ZoneOffset) = controller.dispatch(selection(offset))

    suspend fun ready() = withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }

    suspend fun submit() {
        controller.dispatch(PlanAction.SubmitForm(controller.formId, controller.formRevision))
        withTimeout(2_000) { controller.state.first { !it.isMutating } }
    }
}

internal fun existingExactPlanRow(): PlanReadRow {
    val plan =
        PlanEntry(
            PlanEntryId("plan"),
            PlanTrackableKind.ACTIVITY,
            ActivityTemplateId("activity"),
            null,
            7,
            ActivitySnapshotId("frozen"),
            null,
            PlanTarget.ExactDay(Instant.parse("2026-10-25T01:30:27.123Z"), ZoneId.of("Europe/Berlin")),
            PlanEntryStatus.PLANNED,
            null,
            null,
            Instant.EPOCH,
            Instant.EPOCH,
            null,
            null,
        )
    return PlanReadRow(
        plan,
        LocalDate.parse("2026-10-25"),
        null,
        "Frozen",
        null,
        PlanSourceState.CURRENT,
        false,
        false,
        null,
    )
}
