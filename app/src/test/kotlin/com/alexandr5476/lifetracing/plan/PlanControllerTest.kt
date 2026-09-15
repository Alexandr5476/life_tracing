package com.alexandr5476.lifetracing.plan

import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanDayPresence
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PlanControllerTest {
    @Test
    fun staleWeekReadDoesNotReplaceNewlySelectedDateAndDuplicateSubmitCommitsOnce() =
        runBlocking {
            val firstReadEntered = CompletableDeferred<Unit>()
            val releaseFirstRead = CompletableDeferred<Unit>()
            val mutations = mutableListOf<PlanMutation>()
            val today = LocalDate.parse("2026-09-15")
            val controller =
                PlanController(
                    this,
                    { query ->
                        if (query.selectedDate == today) {
                            firstReadEntered.complete(Unit)
                            releaseFirstRead.await()
                        }
                        week(query)
                    },
                    { _, _, _ -> listOf(source) },
                    { CancelledPlanPage(emptyList(), false) },
                    { mutations += it },
                    { Instant.parse("2026-09-15T10:00:00Z") },
                    { ZoneOffset.UTC },
                )
            try {
                withTimeout(2_000) { firstReadEntered.await() }
                controller.dispatch(PlanAction.SelectDate(today.plusDays(1)))
                withTimeout(2_000) {
                    controller.state.first {
                        it.week is PlanLoad.Content &&
                            it.selectedDate == today.plusDays(1)
                    }
                }
                releaseFirstRead.complete(Unit)
                controller.dispatch(PlanAction.OpenCatalog)
                withTimeout(2_000) { controller.state.first { it.catalog?.items == listOf(source) } }
                controller.dispatch(PlanAction.SelectCatalogItem(source))
                controller.dispatch(PlanAction.SubmitForm)
                controller.dispatch(PlanAction.SubmitForm)
                withTimeout(2_000) { controller.state.first { !it.isMutating && mutations.isNotEmpty() } }

                assertEquals(1, mutations.size)
                assertTrue(mutations.single() is PlanMutation.CreateActivity)
            } finally {
                releaseFirstRead.complete(Unit)
                controller.close()
            }
        }

    private fun week(query: WeekPlanQuery) =
        WeekPlanRead(
            query.weekStart,
            query.selectedDate,
            emptyList(),
            emptyList(),
            (0L..6L).map { PlanDayPresence(query.weekStart.plusDays(it), 0) },
        )

    private companion object {
        val source =
            ReusablePlanCatalogItem(LibraryTemplateId.Activity(ActivityTemplateId("activity")), "Activity", null, null)
    }
}
