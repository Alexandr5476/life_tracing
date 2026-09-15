package com.alexandr5476.lifetracing.plan

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.PlanReadRepository
import com.alexandr5476.lifetracing.data.persistence.PlanRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.executePlanMutation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@RunWith(AndroidJUnit4::class)
class PlanControllerPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Europe/Moscow")
    private val monday = LocalDate.of(2036, 2, 4).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val selected = monday.plusDays(1)

    @Test
    fun realRepositoriesCreateActivitySequenceAndExactDayThroughTheProductionControllerBoundary() =
        runBlocking {
            val suffix = System.nanoTime().toString()
            val authoring = TemplateAuthoringRepository.create(context)
            val library = LibraryRepository.create(context)
            val plans = PlanRepository.create(context) { zone }
            val reads = PlanReadRepository.create(context) { zone }
            var clock = selected.atTime(10, 0).atZone(zone).toInstant()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = controller(scope, library, plans, reads) { clock }
            try {
                controller.awaitWeek()
                val timer =
                    authoring.createActivityTemplate(
                        ActivityTemplateDraft(
                            "S4C2 timer $suffix",
                            "frozen timer",
                            TimeTrackingMode.TIMER,
                            Duration.ofSeconds(90),
                        ),
                        createdAt = clock,
                    )
                assertNull(library.getRecent(100).firstOrNull { it.id == LibraryTemplateId.Activity(timer.id) })

                controller.selectCatalog(LibraryTemplateId.Activity(timer.id), timer.name)
                controller.dispatch(PlanAction.SubmitForm)
                val timerRow = controller.awaitIdleRow(timer.name, inWeek = false)
                assertEquals(PlanTarget.FloatingDay(selected), timerRow.plan.target)
                assertEquals(Duration.ofSeconds(90), timerRow.activityMetadata?.timerTarget)
                assertNull(library.getRecent(100).firstOrNull { it.id == LibraryTemplateId.Activity(timer.id) })

                clock = clock.plusSeconds(1)
                val sequence =
                    authoring.createSequenceTemplate(
                        SequenceTemplateDraft("S4C2 sequence $suffix", "frozen sequence"),
                        createdAt = clock,
                    )
                controller.selectCatalog(LibraryTemplateId.Sequence(sequence.id), sequence.name)
                controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.WEEK))
                controller.dispatch(PlanAction.SubmitForm)
                val sequenceRow = controller.awaitIdleRow(sequence.name, inWeek = true)
                assertEquals(PlanTarget.Week(monday), sequenceRow.plan.target)
                assertTrue(
                    reads
                        .getWeek(
                            com.alexandr5476.lifetracing.domain
                                .WeekPlanQuery(monday, selected, clock),
                        ).selectedDayPlans
                        .none { it.plan.id == sequenceRow.plan.id },
                )

                clock = clock.plusSeconds(1)
                val exact =
                    authoring.createActivityTemplate(
                        ActivityTemplateDraft("S4C2 exact $suffix", null, TimeTrackingMode.STOPWATCH, null),
                        createdAt = clock,
                    )
                controller.selectCatalog(LibraryTemplateId.Activity(exact.id), exact.name)
                controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.EXACT_DAY))
                controller.dispatch(PlanAction.ChangeScheduleDate(selected.toString()))
                controller.dispatch(PlanAction.ChangeScheduleTime("15:45"))
                controller.dispatch(PlanAction.SubmitForm)
                val exactRow = controller.awaitIdleRow(exact.name, inWeek = false)
                val exactTarget = exactRow.plan.target as PlanTarget.ExactDay
                assertEquals(selected.atTime(15, 45).atZone(zone).toInstant(), exactTarget.scheduledAt)
                assertEquals(zone, exactTarget.creationZoneId)
                assertEquals(java.time.LocalTime.of(15, 45), exactRow.exactLocalTime)
                assertEquals(
                    listOf(PlanScheduleKind.FLOATING_DAY, PlanScheduleKind.EXACT_DAY, PlanScheduleKind.WEEK),
                    PlanScheduleKind.entries,
                )
            } finally {
                controller.close()
                scope.cancel()
            }
        }

    @Test
    @Suppress("LongMethod") // One real Plan identity is followed across every management command.
    fun realRepositoriesRescheduleCancelRestoreAndUpdateUsingFreshCanonicalIdentities() =
        runBlocking {
            val suffix = System.nanoTime().toString()
            val authoring = TemplateAuthoringRepository.create(context)
            val library = LibraryRepository.create(context)
            val plans = PlanRepository.create(context) { zone }
            val reads = PlanReadRepository.create(context) { zone }
            var clock = selected.atTime(10, 0).atZone(zone).toInstant()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = controller(scope, library, plans, reads) { clock }
            try {
                controller.awaitWeek()
                val template =
                    authoring.createActivityTemplate(
                        ActivityTemplateDraft("S4C2 managed $suffix", "before", TimeTrackingMode.STOPWATCH, null),
                        createdAt = clock,
                    )
                controller.selectCatalog(LibraryTemplateId.Activity(template.id), template.name)
                controller.dispatch(PlanAction.SubmitForm)
                var row = controller.awaitIdleRow(template.name, inWeek = false)

                clock = clock.plusSeconds(1)
                val nextDay = selected.plusDays(1)
                controller.dispatch(PlanAction.Reschedule(row))
                controller.dispatch(PlanAction.ChangeScheduleDate(nextDay.toString()))
                controller.dispatch(PlanAction.SubmitForm)
                controller.awaitIdle()
                assertTrue(
                    reads.getWeek(weekQuery(selected, clock)).selectedDayPlans.none { it.plan.id == row.plan.id },
                )
                controller.dispatch(PlanAction.SelectDate(nextDay))
                row = controller.awaitRow(template.name, inWeek = false)
                assertEquals(PlanTarget.FloatingDay(nextDay), row.plan.target)

                clock = clock.plusSeconds(1)
                controller.dispatch(PlanAction.Reschedule(row))
                controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.WEEK))
                controller.dispatch(PlanAction.SubmitForm)
                row = controller.awaitIdleRow(template.name, inWeek = true)
                assertEquals(PlanTarget.Week(monday), row.plan.target)

                clock = clock.plusSeconds(1)
                controller.dispatch(PlanAction.Reschedule(row))
                controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.FLOATING_DAY))
                controller.dispatch(PlanAction.ChangeScheduleDate(selected.toString()))
                controller.dispatch(PlanAction.SubmitForm)
                controller.awaitIdle()
                controller.dispatch(PlanAction.SelectDate(selected))
                row = controller.awaitRow(template.name, inWeek = false)

                clock =
                    selected
                        .plusDays(2)
                        .atTime(10, 0)
                        .atZone(zone)
                        .toInstant()
                controller.dispatch(PlanAction.Refresh)
                row = controller.awaitRow(template.name, inWeek = false)
                assertTrue(row.overdue)
                val friday = selected.plusDays(3)
                controller.dispatch(PlanAction.Reschedule(row))
                controller.dispatch(PlanAction.ChangeScheduleDate(friday.toString()))
                controller.dispatch(PlanAction.SubmitForm)
                controller.awaitIdle()
                controller.dispatch(PlanAction.SelectDate(friday))
                row = controller.awaitRow(template.name, inWeek = false)
                assertFalse(row.overdue)

                val retained = row.plan
                clock = clock.plusSeconds(1)
                controller.dispatch(PlanAction.Cancel(row))
                controller.awaitIdle()
                assertTrue(controller.week().selectedDayPlans.none { it.plan.id == retained.id })
                val cancelled =
                    controller.state.value.cancelledItems
                        .single { it.plan.id == retained.id }
                assertEquals(PlanEntryStatus.CANCELLED, cancelled.plan.status)
                assertEquals(retained.activitySnapshotId, cancelled.plan.activitySnapshotId)
                assertEquals(retained.sourceActivityTemplateId, cancelled.plan.sourceActivityTemplateId)

                controller.dispatch(PlanAction.OpenCancelled)
                controller.awaitCancelled()
                clock = clock.plusSeconds(1)
                controller.dispatch(
                    PlanAction.Restore(
                        controller.state.value.cancelledItems.single {
                            it.plan.id ==
                                retained.id
                        },
                    ),
                )
                row = controller.awaitIdleRow(template.name, inWeek = false)
                assertEquals(retained.target, row.plan.target)
                assertEquals(retained.activitySnapshotId, row.plan.activitySnapshotId)

                clock = clock.plusSeconds(1)
                val changed =
                    authoring.saveActivityTemplate(
                        template.id,
                        template.revision,
                        template.toAuthoringDraft().copy(name = "S4C2 updated $suffix", shortComment = "after"),
                        clock,
                    )
                controller.dispatch(PlanAction.Refresh)
                row = controller.awaitRow(template.name, inWeek = false)
                assertEquals(PlanSourceState.CHANGED, row.sourceState)
                val oldSnapshot = row.plan.activitySnapshotId
                clock = clock.plusSeconds(1)
                controller.dispatch(PlanAction.UpdateFromTemplate(row))
                val updated = controller.awaitIdleRow(changed.name, inWeek = false)
                assertEquals(PlanSourceState.CURRENT, updated.sourceState)
                assertEquals("after", updated.shortComment)
                assertNotEquals(oldSnapshot, updated.plan.activitySnapshotId)
            } finally {
                controller.close()
                scope.cancel()
            }
        }

    @Test
    fun archivedOrUnavailableSourceAfterCatalogSelectionRejectsWithoutPlanOrSnapshot() =
        runBlocking {
            val suffix = System.nanoTime().toString()
            val authoring = TemplateAuthoringRepository.create(context)
            val library = LibraryRepository.create(context)
            val plans = PlanRepository.create(context) { zone }
            val reads = PlanReadRepository.create(context) { zone }
            var clock = selected.atTime(10, 0).atZone(zone).toInstant()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = controller(scope, library, plans, reads) { clock }
            try {
                controller.awaitWeek()
                val archived =
                    authoring.createActivityTemplate(
                        ActivityTemplateDraft("S4C2 archived $suffix", null, TimeTrackingMode.STOPWATCH, null),
                        createdAt = clock,
                    )
                controller.selectCatalog(LibraryTemplateId.Activity(archived.id), archived.name)
                library.archiveActivityTemplate(archived.id, clock.plusSeconds(1))
                controller.dispatch(PlanAction.SubmitForm)
                controller.awaitActionUnavailable()
                assertEquals(0, count("plan_entries", archived.id.value))
                assertEquals(0, count("activity_snapshots", archived.id.value))

                clock = clock.plusSeconds(2)
                val unavailable =
                    authoring.createActivityTemplate(
                        ActivityTemplateDraft("S4C2 unavailable $suffix", null, TimeTrackingMode.STOPWATCH, null),
                        createdAt = clock,
                    )
                controller.selectCatalog(LibraryTemplateId.Activity(unavailable.id), unavailable.name)
                deleteActivityTemplate(unavailable.id.value)
                controller.dispatch(PlanAction.SubmitForm)
                controller.awaitActionUnavailable()
                assertEquals(0, count("plan_entries", unavailable.id.value))
                assertEquals(0, count("activity_snapshots", unavailable.id.value))
            } finally {
                controller.close()
                scope.cancel()
            }
        }

    private fun controller(
        scope: CoroutineScope,
        library: LibraryRepository,
        plans: PlanRepository,
        reads: PlanReadRepository,
        now: () -> Instant,
    ) = PlanController(
        scope,
        reads::getWeek,
        library::getReusablePlanCatalog,
        reads::getCancelledPage,
        { executePlanMutation(it, plans) },
        now,
        { zone },
    )

    private suspend fun PlanController.selectCatalog(
        id: LibraryTemplateId,
        name: String,
    ) {
        dispatch(PlanAction.OpenCatalog)
        dispatch(PlanAction.SearchCatalog(name))
        val item =
            withTimeout(5_000) {
                state.first { it.catalog?.loading == false }.catalog!!.items.single {
                    it.id ==
                        id
                }
            }
        dispatch(PlanAction.SelectCatalogItem(item))
    }

    private suspend fun PlanController.awaitWeek() = withTimeout(5_000) { state.first { it.week is PlanLoad.Content } }

    private suspend fun PlanController.awaitIdle() =
        withTimeout(5_000) { state.first { !it.isMutating && it.week is PlanLoad.Content } }

    private suspend fun PlanController.awaitRow(
        name: String,
        inWeek: Boolean,
    ) = withTimeout(5_000) {
        state
            .first { presentation ->
                val read = (presentation.week as? PlanLoad.Content)?.value
                (if (inWeek) read?.weekPlans else read?.selectedDayPlans)?.any { it.title == name } == true
            }.let { presentation ->
                val read = (presentation.week as PlanLoad.Content).value
                (if (inWeek) read.weekPlans else read.selectedDayPlans).single { it.title == name }
            }
    }

    private suspend fun PlanController.awaitIdleRow(
        name: String,
        inWeek: Boolean,
    ) = withTimeout(5_000) {
        state
            .first { presentation ->
                val read = (presentation.week as? PlanLoad.Content)?.value
                !presentation.isMutating &&
                    (if (inWeek) read?.weekPlans else read?.selectedDayPlans)?.any { it.title == name } == true
            }.let { presentation ->
                val read = (presentation.week as PlanLoad.Content).value
                (if (inWeek) read.weekPlans else read.selectedDayPlans).single { it.title == name }
            }
    }

    private suspend fun PlanController.awaitCancelled() =
        withTimeout(5_000) { state.first { it.cancelled is PlanLoad.Content } }

    private suspend fun PlanController.awaitActionUnavailable() =
        withTimeout(5_000) {
            state.first { !it.isMutating && it.mutationFailure == PlanMessage.ACTION_UNAVAILABLE }
        }

    private fun PlanController.week() = (state.value.week as PlanLoad.Content).value

    private fun weekQuery(
        date: LocalDate,
        now: Instant,
    ) = com.alexandr5476.lifetracing.domain
        .WeekPlanQuery(monday, date, now)

    private fun count(
        table: String,
        sourceId: String,
    ): Int {
        val column = if (table == "plan_entries") "source_activity_template_id" else "source_template_id"
        return SQLiteDatabase
            .openDatabase(context.getDatabasePath("lifetracing.db").path, null, SQLiteDatabase.OPEN_READONLY)
            .use { database ->
                database.rawQuery("SELECT COUNT(*) FROM $table WHERE $column = ?", arrayOf(sourceId)).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
            }
    }

    private fun deleteActivityTemplate(id: String) {
        SQLiteDatabase
            .openDatabase(context.getDatabasePath("lifetracing.db").path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { it.delete("activity_templates", "id = ?", arrayOf(id)) }
    }
}
