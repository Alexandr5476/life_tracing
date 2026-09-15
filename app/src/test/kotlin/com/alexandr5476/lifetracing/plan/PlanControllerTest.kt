package com.alexandr5476.lifetracing.plan

import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanDayPresence
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.StalePlanActionException
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun staleRescheduleCannotReuseRejectedIdentityUntilWinningWeekReloadPublishes() =
        runBlocking {
            val recoveryEntered = CompletableDeferred<Unit>()
            val releaseRecovery = CompletableDeferred<Unit>()
            val mutations = mutableListOf<PlanMutation>()
            val oldRow = row("plan", 1)
            val freshRow = row("plan", 2)
            var reads = 0
            val controller =
                controller(
                    readWeek = { query ->
                        reads++
                        if (reads == 2) {
                            recoveryEntered.complete(Unit)
                            releaseRecovery.await()
                        }
                        week(query, if (reads == 1) oldRow else freshRow)
                    },
                    commit = {
                        mutations += it
                        throw StalePlanActionException()
                    },
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                controller.dispatch(PlanAction.Reschedule(oldRow))
                controller.dispatch(PlanAction.SubmitForm)
                withTimeout(2_000) { recoveryEntered.await() }

                assertTrue(controller.state.value.isMutating)
                assertNull(controller.state.value.form)
                assertEquals(PlanMessage.ACTION_UNAVAILABLE, controller.state.value.mutationFailure)
                controller.dispatch(PlanAction.Reschedule(oldRow))
                controller.dispatch(PlanAction.SubmitForm)
                assertEquals(1, mutations.size)

                releaseRecovery.complete(Unit)
                withTimeout(2_000) { controller.state.first { !it.isMutating } }
                controller.dispatch(PlanAction.Reschedule(freshRow))
                controller.dispatch(PlanAction.SubmitForm)
                withTimeout(2_000) { controller.state.first { mutations.size == 2 } }
                assertEquals(freshRow.plan.updatedAt, (mutations.last() as PlanMutation.Reschedule).identity.updatedAt)
            } finally {
                releaseRecovery.complete(Unit)
                controller.close()
            }
        }

    @Test
    fun staleRestoreWaitsForFreshCancelledPageBeforeFreshIdentityCanDispatch() =
        runBlocking {
            val recoveryEntered = CompletableDeferred<Unit>()
            val releaseRecovery = CompletableDeferred<Unit>()
            val oldRow = row("cancelled", 1, PlanEntryStatus.CANCELLED)
            val freshRow = row("cancelled", 2, PlanEntryStatus.CANCELLED)
            val mutations = mutableListOf<PlanMutation>()
            var cancelledReads = 0
            val controller =
                controller(
                    readCancelled = {
                        cancelledReads++
                        if (cancelledReads == 2) {
                            recoveryEntered.complete(Unit)
                            releaseRecovery.await()
                        }
                        CancelledPlanPage(listOf(if (cancelledReads == 1) oldRow else freshRow), false)
                    },
                    commit = {
                        mutations += it
                        throw StalePlanActionException()
                    },
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                controller.dispatch(PlanAction.OpenCancelled)
                withTimeout(2_000) { controller.state.first { it.cancelledItems == listOf(oldRow) } }
                controller.dispatch(PlanAction.Restore(oldRow))
                withTimeout(2_000) { recoveryEntered.await() }

                assertTrue(controller.state.value.isMutating)
                controller.dispatch(PlanAction.Restore(oldRow))
                assertEquals(1, mutations.size)

                releaseRecovery.complete(Unit)
                withTimeout(2_000) { controller.state.first { !it.isMutating } }
                assertEquals(listOf(freshRow), controller.state.value.cancelledItems)
                controller.dispatch(PlanAction.Restore(freshRow))
                withTimeout(2_000) { controller.state.first { mutations.size == 2 } }
                assertEquals(freshRow.plan.updatedAt, (mutations.last() as PlanMutation.Restore).identity.updatedAt)
            } finally {
                releaseRecovery.complete(Unit)
                controller.close()
            }
        }

    @Test
    fun routeExitDropsTransientWorkflowsAndReentryRetainsContextWhileRefreshing() =
        runBlocking {
            var reads = 0
            val controller = controller(readWeek = { week(it).also { reads++ } })
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                val selected =
                    controller.state.value.selectedDate
                        .plusDays(1)
                controller.dispatch(PlanAction.SelectDate(selected))
                controller.dispatch(PlanAction.OpenCatalog)
                withTimeout(2_000) { controller.state.first { it.catalog?.loading == false } }
                controller.dispatch(PlanAction.SelectCatalogItem(source))
                controller.dispatch(PlanAction.OpenCancelled)
                withTimeout(2_000) { controller.state.first { it.cancelled is PlanLoad.Content } }

                controller.onRouteExited()

                assertNull(controller.state.value.catalog)
                assertNull(controller.state.value.form)
                assertFalse(controller.state.value.cancelledOpen)
                val beforeReentry = reads
                controller.onRouteEntered()
                withTimeout(2_000) { controller.state.first { reads > beforeReentry && it.week is PlanLoad.Content } }
                assertEquals(selected, controller.state.value.selectedDate)
            } finally {
                controller.close()
            }
        }

    @Test
    @Suppress("LongMethod") // One pair of delayed reads proves both generation-owned surfaces.
    fun delayedCatalogQueryAndDismissedCancelledReadCannotPublishIntoNewOwnership() =
        runBlocking {
            val oldCatalogEntered = CompletableDeferred<Unit>()
            val releaseOldCatalog = CompletableDeferred<Unit>()
            val oldCatalogFinished = CompletableDeferred<Unit>()
            val cancelledEntered = CompletableDeferred<Unit>()
            val releaseCancelled = CompletableDeferred<Unit>()
            val cancelledFinished = CompletableDeferred<Unit>()
            val newer = source.copy(name = "Newer")
            val controller =
                controller(
                    readCatalog = { query, _, _ ->
                        if (query == "old") {
                            oldCatalogEntered.complete(Unit)
                            releaseOldCatalog.await()
                            oldCatalogFinished.complete(Unit)
                        }
                        listOf(if (query == "new") newer else source)
                    },
                    readCancelled = {
                        cancelledEntered.complete(Unit)
                        releaseCancelled.await()
                        cancelledFinished.complete(Unit)
                        CancelledPlanPage(listOf(row("cancelled", 1, PlanEntryStatus.CANCELLED)), false)
                    },
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                controller.dispatch(PlanAction.OpenCatalog)
                withTimeout(2_000) { controller.state.first { it.catalog?.loading == false } }
                controller.dispatch(PlanAction.SearchCatalog("old"))
                withTimeout(2_000) { oldCatalogEntered.await() }
                controller.dispatch(PlanAction.SearchCatalog("new"))
                withTimeout(2_000) { controller.state.first { it.catalog?.items == listOf(newer) } }
                releaseOldCatalog.complete(Unit)
                withTimeout(2_000) { oldCatalogFinished.await() }
                assertEquals(
                    "new",
                    controller.state.value.catalog
                        ?.query,
                )
                assertEquals(
                    listOf(newer),
                    controller.state.value.catalog
                        ?.items,
                )

                controller.dispatch(PlanAction.OpenCancelled)
                withTimeout(2_000) { cancelledEntered.await() }
                controller.dispatch(PlanAction.DismissCancelled)
                releaseCancelled.complete(Unit)
                withTimeout(2_000) { cancelledFinished.await() }
                assertFalse(controller.state.value.cancelledOpen)
                assertNull(controller.state.value.cancelled)
                assertTrue(
                    controller.state.value.cancelledItems
                        .isEmpty(),
                )
            } finally {
                releaseOldCatalog.complete(Unit)
                releaseCancelled.complete(Unit)
                controller.close()
            }
        }

    private fun kotlinx.coroutines.CoroutineScope.controller(
        readWeek: suspend (WeekPlanQuery) -> WeekPlanRead = ::week,
        readCatalog: suspend (String, Int, ReusablePlanCatalogItem?) -> List<ReusablePlanCatalogItem> =
            { _, _, _ -> listOf(source) },
        readCancelled: suspend (com.alexandr5476.lifetracing.domain.CancelledPlanPageQuery) -> CancelledPlanPage = {
            CancelledPlanPage(emptyList(), false)
        },
        commit: suspend (PlanMutation) -> Unit = {},
    ) = PlanController(
        this,
        readWeek,
        readCatalog,
        readCancelled,
        commit,
        { Instant.parse("2026-09-15T10:00:00Z") },
        { ZoneOffset.UTC },
    )

    private fun week(
        query: WeekPlanQuery,
        row: PlanReadRow? = null,
    ) = WeekPlanRead(
        query.weekStart,
        query.selectedDate,
        listOfNotNull(row),
        emptyList(),
        (0L..6L).map { PlanDayPresence(query.weekStart.plusDays(it), if (it == 1L && row != null) 1 else 0) },
    )

    private fun row(
        id: String,
        revision: Long,
        status: PlanEntryStatus = PlanEntryStatus.PLANNED,
    ): PlanReadRow {
        val at = Instant.ofEpochSecond(revision)
        val plan =
            PlanEntry(
                PlanEntryId(id),
                PlanTrackableKind.ACTIVITY,
                ActivityTemplateId("activity"),
                null,
                1,
                ActivitySnapshotId("snapshot"),
                null,
                PlanTarget.FloatingDay(LocalDate.parse("2026-09-15")),
                status,
                null,
                null,
                Instant.EPOCH,
                at,
                at.takeIf { status == PlanEntryStatus.CANCELLED },
                null,
            )
        return PlanReadRow(
            plan,
            LocalDate.parse("2026-09-15"),
            null,
            "Activity",
            null,
            PlanSourceState.CURRENT,
            false,
            false,
            null,
        )
    }

    private companion object {
        val source =
            ReusablePlanCatalogItem(LibraryTemplateId.Activity(ActivityTemplateId("activity")), "Activity", null, null)
    }
}
