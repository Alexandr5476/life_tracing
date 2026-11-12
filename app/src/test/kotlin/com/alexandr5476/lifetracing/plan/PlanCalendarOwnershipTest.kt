package com.alexandr5476.lifetracing.plan

import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import com.alexandr5476.lifetracing.domain.actionIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.Executor

class PlanCalendarOwnershipTest {
    @Test
    fun hiddenCommandsKeepContextAndReentryRemainsUsable() {
        Fixture().use { f ->
            f.controller.dispatch(PlanAction.NextWeek)
            f.drain()
            val retained = f.controller.state.value
            val reads = f.weekReads
            f.controller.onRouteExited()
            f.navigate()
            f.controller.dispatch(PlanAction.OpenCatalog)
            f.drain()
            assertEquals(retained.weekStart, f.controller.state.value.weekStart)
            assertEquals(retained.selectedDate, f.controller.state.value.selectedDate)
            assertEquals(reads, f.weekReads)
            assertEquals(0, f.catalogReads)
            assertNull(f.controller.state.value.catalog)
            f.controller.onRouteEntered()
            f.drain()
            assertEquals(retained.selectedDate, f.controller.state.value.selectedDate)
            assertEquals(reads + 1, f.weekReads)
            f.controller.dispatch(PlanAction.PreviousWeek)
            f.drain()
            assertEquals(retained.weekStart.minusWeeks(1), f.controller.state.value.weekStart)
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    fun catalogOwnsCalendarAndAddWhileSelectionKeepsOriginalDefault() {
        Fixture().use { f ->
            f.controller.dispatch(PlanAction.OpenCatalog)
            f.drain()
            val owned = f.controller.state.value
            val catalog = requireNotNull(owned.catalog)
            val reads = f.weekReads
            f.navigate()
            f.controller.dispatch(PlanAction.OpenCatalog)
            f.drain()
            assertEquals(owned, f.controller.state.value)
            assertEquals(reads, f.weekReads)
            assertEquals(1, f.catalogReads)
            f.controller.dispatch(PlanAction.SelectCatalogItem(f.source, catalog.context))
            val form = requireNotNull(f.controller.state.value.form)
            assertEquals(owned.selectedDate.toString(), form.date)
            assertTrue(f.writes.isEmpty())
            f.controller.dispatch(PlanAction.SubmitForm(form.formId, form.inputRevision))
            f.drain()
            assertEquals(
                PlanSchedule.FloatingDay(owned.selectedDate),
                (f.writes.single() as PlanMutation.CreateActivity).schedule,
            )
        }
    }

    @Test
    fun scheduleOwnsCalendarAndAddPreservingUuidRawInputAndRevision() {
        Fixture().use { f ->
            f.controller.dispatch(PlanAction.OpenCatalog)
            f.drain()
            f.controller.dispatch(
                PlanAction.SelectCatalogItem(f.source, requireNotNull(f.controller.state.value.catalog).context),
            )
            val id = requireNotNull(f.controller.state.value.form).formId
            f.controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.EXACT_DAY, id))
            f.controller.dispatch(PlanAction.ChangeScheduleDate("raw-date", id))
            f.controller.dispatch(PlanAction.ChangeScheduleTime("11:", id))
            val owned = f.controller.state.value
            val reads = f.weekReads
            f.navigate()
            f.controller.dispatch(PlanAction.OpenCatalog)
            f.drain()
            assertEquals(owned, f.controller.state.value)
            assertEquals(reads, f.weekReads)
            assertEquals(1, f.catalogReads)
            assertTrue(f.writes.isEmpty())
            f.controller.dispatch(PlanAction.DismissForm(id))
            f.controller.dispatch(PlanAction.OpenCatalog)
            f.drain()
            assertEquals(2, f.catalogReads)
            assertTrue(f.controller.state.value.catalog != null)
        }
    }

    @Test
    fun cancelledOwnsCalendarAndFailedReadRecoveryRemainsUsable() {
        Fixture().use { f ->
            f.controller.dispatch(PlanAction.OpenCancelled)
            f.drain()
            val owned = f.controller.state.value
            val reads = f.weekReads
            f.navigate()
            f.controller.dispatch(PlanAction.OpenCatalog)
            f.drain()
            assertEquals(owned, f.controller.state.value)
            assertEquals(reads, f.weekReads)
            assertEquals(0, f.catalogReads)
            f.controller.dispatch(PlanAction.DismissCancelled(requireNotNull(owned.cancelledContext)))
            f.failRead = true
            f.controller.dispatch(PlanAction.Refresh)
            f.drain()
            assertTrue(f.controller.state.value.week is PlanLoad.Failure)
            f.failRead = false
            f.controller.dispatch(PlanAction.Refresh)
            f.drain()
            assertTrue(f.controller.state.value.week is PlanLoad.Content)
            f.controller.dispatch(PlanAction.NextWeek)
            f.drain()
            assertEquals(owned.weekStart.plusWeeks(1), f.controller.state.value.weekStart)
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    fun executeRequiresExactPublicationAndModalOrHiddenCallbacksCannotNavigate() {
        Fixture().use { f ->
            val original = existingExactPlanRow()
            val duplicate = original.copy(plan = original.plan.copy(id = PlanEntryId("duplicate")))
            f.rows = listOf(original, duplicate)
            f.controller.dispatch(PlanAction.Refresh)
            f.drain()
            val first = (f.controller.state.value.week as PlanLoad.Content).value.selectedDayPlans[0]
            val second = (f.controller.state.value.week as PlanLoad.Content).value.selectedDayPlans[1]
            val navigated = mutableListOf<PlanActionIdentity>()
            f.controller.executeIfOwned(first.copy(), navigated::add)
            assertTrue(navigated.isEmpty())
            f.controller.executeIfOwned(second, navigated::add)
            assertEquals(listOf(duplicate.plan.actionIdentity()), navigated)
            navigated.clear()
            f.controller.dispatch(PlanAction.Refresh)
            f.drain()
            f.controller.executeIfOwned(first, navigated::add)
            f.controller.executeIfOwned(second, navigated::add)
            assertTrue(navigated.isEmpty())
            val current = (f.controller.state.value.week as PlanLoad.Content).value.selectedDayPlans.first()
            f.controller.dispatch(PlanAction.OpenCatalog)
            f.controller.executeIfOwned(current, navigated::add)
            f.drain()
            val catalog = requireNotNull(f.controller.state.value.catalog)
            f.controller.dispatch(PlanAction.SelectCatalogItem(f.source, catalog.context))
            f.controller.executeIfOwned(current, navigated::add)
            f.controller.dispatch(PlanAction.DismissForm(requireNotNull(f.controller.state.value.form).formId))
            f.controller.dispatch(PlanAction.OpenCancelled)
            f.controller.executeIfOwned(current, navigated::add)
            f.controller.onRouteExited()
            f.controller.executeIfOwned(current, navigated::add)
            assertTrue(navigated.isEmpty())
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    fun engagedAndTerminalRowsCannotExecuteEvenWhenPublished() {
        Fixture().use { f ->
            val row = existingExactPlanRow()
            val navigated = mutableListOf<PlanActionIdentity>()
            for (unavailable in listOf(
                row.copy(engaged = true),
                row.copy(plan = row.plan.copy(status = PlanEntryStatus.FULFILLED)),
            )) {
                f.rows = listOf(unavailable)
                f.controller.dispatch(PlanAction.Refresh)
                f.drain()
                val current = (f.controller.state.value.week as PlanLoad.Content).value.selectedDayPlans.single()
                f.controller.executeIfOwned(current, navigated::add)
            }
            assertTrue(navigated.isEmpty())
            assertTrue(f.writes.isEmpty())
        }
    }

    private class Fixture : AutoCloseable {
        private val tasks = ArrayDeque<Runnable>()
        private val scope = CoroutineScope(SupervisorJob() + Executor { tasks.addLast(it) }.asCoroutineDispatcher())
        val source =
            ReusablePlanCatalogItem(LibraryTemplateId.Activity(ActivityTemplateId("source")), "Same name", null, null)
        var rows = emptyList<PlanReadRow>()
        var weekReads = 0
        var catalogReads = 0
        var failRead = false
        val writes = mutableListOf<PlanMutation>()
        val controller =
            PlanController(
                scope,
                {
                    weekReads++
                    if (failRead) error("read failed")
                    WeekPlanRead(
                        it.weekStart,
                        it.selectedDate,
                        rows.map { row ->
                            row.copy()
                        },
                        emptyList(),
                        emptyList(),
                    )
                },
                { _, _, _ ->
                    catalogReads++
                    listOf(source)
                },
                { CancelledPlanPage(emptyList(), false) },
                { writes += it },
                { Instant.parse("2026-09-15T10:00:00Z") },
                { ZoneOffset.UTC },
            )

        init {
            drain()
        }

        fun navigate() {
            val other =
                controller.state.value.weekStart
                    .plusDays(2)
            listOf(
                PlanAction.PreviousWeek,
                PlanAction.NextWeek,
                PlanAction.Today,
                PlanAction.SelectDate(other),
            ).forEach { action ->
                val owned = controller.state.value
                controller.dispatch(action)
                assertEquals(owned, controller.state.value, action.toString())
            }
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }

        override fun close() {
            controller.close()
            scope.cancel()
            drain()
        }
    }
}
