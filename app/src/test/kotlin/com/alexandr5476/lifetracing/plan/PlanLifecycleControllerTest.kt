package com.alexandr5476.lifetracing.plan

import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.CancelledPlanPageQuery
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.Executor

class PlanLifecycleControllerTest {
    @Test
    fun dismissedRestoreCannotAcquireAuthorityFromReopenedIdenticalCollection() {
        val tasks = ArrayDeque<Runnable>()
        val scope = CoroutineScope(SupervisorJob() + Executor { tasks.addLast(it) }.asCoroutineDispatcher())
        val row = cancelledRow("one")
        val writes = mutableListOf<PlanMutation>()
        val controller =
            PlanController(
                scope,
                { WeekPlanRead(it.weekStart, it.selectedDate, emptyList(), emptyList(), emptyList()) },
                { _, _, _ -> emptyList() },
                { CancelledPlanPage(listOf(row), false) },
                { writes += it },
                { Instant.parse("2026-09-15T10:00:00Z") },
                { ZoneOffset.UTC },
            )

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
        try {
            drain()
            controller.dispatch(PlanAction.OpenCancelled)
            drain()
            val obsolete = PlanAction.Restore(row, requireNotNull(controller.state.value.cancelledContext))
            controller.dispatch(PlanAction.DismissCancelled(requireNotNull(controller.state.value.cancelledContext)))
            controller.dispatch(PlanAction.OpenCancelled)
            drain()
            controller.dispatch(obsolete)
            drain()
            assertEquals(emptyList<PlanMutation>(), writes)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun obsoleteCloseMoreRetryAndRestoreCannotDismissOrReplaceNewDialogOrOtherModal() {
        Fixture().use { f ->
            f.open()
            val context = f.context
            val obsolete =
                listOf(
                    PlanAction.DismissCancelled(context),
                    PlanAction.LoadMoreCancelled(context, 30),
                    PlanAction.RetryCancelled(context, 0),
                    PlanAction.Restore(f.rows.first(), context),
                )
            f.controller.dispatch(PlanAction.DismissCancelled(context))
            f.open()
            val fresh = f.controller.state.value
            obsolete.forEach(f.controller::dispatch)
            f.drain()
            assertEquals(fresh, f.controller.state.value)
            f.controller.dispatch(PlanAction.DismissCancelled(f.context))
            f.controller.dispatch(PlanAction.OpenCatalog)
            f.drain()
            val catalog = f.controller.state.value.catalog
            obsolete.forEach(f.controller::dispatch)
            f.controller.dispatch(PlanAction.OpenCancelled)
            f.drain()
            assertEquals(catalog, f.controller.state.value.catalog)
            assertFalse(f.controller.state.value.cancelledOpen)
            f.controller.dispatch(PlanAction.DismissCatalog(requireNotNull(catalog).context))
            val active =
                cancelledRow("form").let {
                    it.copy(plan = it.plan.copy(status = PlanEntryStatus.PLANNED, cancelledAt = null))
                }
            f.controller.dispatch(PlanAction.Reschedule(active))
            val form = f.controller.state.value.form
            assertTrue(form != null)
            obsolete.forEach(f.controller::dispatch)
            f.controller.dispatch(PlanAction.OpenCancelled)
            assertEquals(form, f.controller.state.value.form)
            assertFalse(f.controller.state.value.cancelledOpen)
            f.controller.onRouteExited()
            obsolete.forEach(f.controller::dispatch)
            f.controller.dispatch(PlanAction.OpenCancelled)
            f.controller.onRouteEntered()
            f.drain()
            assertFalse(f.controller.state.value.cancelledOpen)
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    fun dismissedPendingFirstPageAndAppendCannotPublishAcrossRouteReentry() {
        for (append in listOf(false, true)) {
            Fixture().use { f ->
                val gate = CompletableDeferred<Unit>()
                f.reader = { query ->
                    if (!append || query.offset > 0) gate.await()
                    CancelledPlanPage(f.rows.take(30), true)
                }
                f.open()
                if (append) {
                    f.controller.dispatch(PlanAction.LoadMoreCancelled(f.context, 30))
                    f.drain()
                    assertEquals(30, f.controller.state.value.cancelledItems.size)
                }
                val context = f.context
                f.controller.dispatch(PlanAction.DismissCancelled(context))
                f.controller.onRouteExited()
                gate.complete(Unit)
                f.drain()
                assertNull(f.controller.state.value.cancelled)
                f.controller.onRouteEntered()
                f.open()
                assertEquals(30, f.controller.state.value.cancelledItems.size)
                val fresh = f.controller.state.value
                f.controller.dispatch(PlanAction.DismissCancelled(context))
                assertEquals(fresh, f.controller.state.value)
                assertTrue(f.writes.isEmpty())
            }
        }
    }

    @Test
    fun boundedAppendFailureRetryPreservesRowsOffsetsAndRejectsConcurrentOrObsoletePageActions() {
        Fixture().use { f ->
            f.rows = (0..64).map { cancelledRow("plan-$it") }
            val gate = CompletableDeferred<Unit>()
            var fail = true
            f.reader = { query ->
                if (query.offset == 30) {
                    gate.await()
                    if (fail) error("append failed")
                }
                CancelledPlanPage(f.rows.drop(query.offset).take(query.limit), query.offset + query.limit < f.rows.size)
            }
            f.open()
            val first = f.controller.state.value.cancelledItems
            val more = PlanAction.LoadMoreCancelled(f.context, 30)
            f.controller.dispatch(more)
            f.controller.dispatch(more)
            f.drain()
            assertEquals(first, f.controller.state.value.cancelledItems)
            assertTrue(f.controller.state.value.cancelled is PlanLoad.Loading)
            assertEquals(listOf(0, 30), f.queries.map { it.offset })
            gate.complete(Unit)
            f.drain()
            assertEquals(first, f.controller.state.value.cancelledItems)
            assertTrue(f.controller.state.value.cancelled is PlanLoad.Failure)
            val retry = PlanAction.RetryCancelled(f.context, 30)
            fail = false
            f.controller.dispatch(retry)
            f.drain()
            assertEquals(f.rows.take(60), f.controller.state.value.cancelledItems)
            val completed = f.controller.state.value
            f.controller.dispatch(more)
            f.controller.dispatch(retry)
            f.drain()
            assertEquals(completed, f.controller.state.value)
            assertEquals(listOf(0, 30, 30), f.queries.map { it.offset })
            assertTrue(f.controller.state.value.cancelledHasNextPage)
            f.controller.dispatch(PlanAction.LoadMoreCancelled(f.context, 60))
            f.drain()
            assertEquals(f.rows, f.controller.state.value.cancelledItems)
            assertEquals(listOf(0, 30, 30, 60), f.queries.map { it.offset })
            assertTrue(f.queries.all { it.limit == 30 })
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    fun exactPublishedRestoreDuringAppendInvalidatesLatePageAndOldCollectionWithoutReplayingWriter() {
        Fixture().use { f ->
            val gate = CompletableDeferred<Unit>()
            f.reader = { query ->
                if (query.offset > 0) gate.await()
                CancelledPlanPage(f.rows.drop(query.offset).take(30), query.offset == 0)
            }
            f.open()
            val row =
                f.controller.state.value.cancelledItems
                    .first()
            val action = PlanAction.Restore(row, f.context)
            f.controller.dispatch(PlanAction.Restore(row.copy(), f.context)) // Unpublished copy has no row authority.
            assertTrue(f.writes.isEmpty())
            f.controller.dispatch(PlanAction.LoadMoreCancelled(f.context, 30))
            f.drain()
            f.commit = { f.rows = f.rows.filterNot { it.plan.id == row.plan.id } }
            f.controller.dispatch(action)
            f.drain()
            assertEquals(row.plan.id, (f.writes.single() as PlanMutation.Restore).identity.planEntryId)
            assertFalse(f.controller.state.value.isMutating)
            val canonical = f.controller.state.value
            gate.complete(Unit)
            f.drain()
            f.controller.dispatch(action)
            f.drain()
            assertEquals(canonical, f.controller.state.value)
            assertEquals(1, f.writes.size)
            assertFalse(
                f.controller.state.value.cancelledItems
                    .any { it.plan.id == row.plan.id },
            )
        }
    }

    @Test
    fun initialFailureRetryAndConfirmedEmptyRemainDistinctAndOldRetryCannotRefreshSuccess() {
        Fixture().use { f ->
            f.reader = { error("read failed") }
            f.open()
            assertTrue(f.controller.state.value.cancelled is PlanLoad.Failure)
            val retry = PlanAction.RetryCancelled(f.context, 0)
            f.reader = { CancelledPlanPage(emptyList(), false) }
            f.controller.dispatch(retry)
            f.drain()
            assertTrue(f.controller.state.value.cancelled is PlanLoad.Content)
            assertTrue(
                f.controller.state.value.cancelledItems
                    .isEmpty(),
            )
            val queries = f.queries.size
            f.controller.dispatch(retry)
            f.drain()
            assertEquals(queries, f.queries.size)
        }
    }

    @Test
    fun activeLifecycleActionsRequireVisibleCalendarAndExactPublishedRowAndRespectMutationLock() {
        Fixture().use { f ->
            val row =
                cancelledRow("active").let {
                    it.copy(
                        plan = it.plan.copy(status = PlanEntryStatus.PLANNED, cancelledAt = null),
                        sourceState = PlanSourceState.CHANGED,
                    )
                }
            f.active = listOf(row)
            f.controller.dispatch(PlanAction.Refresh)
            f.drain()
            val cancel = PlanAction.Cancel(row)
            val update = PlanAction.UpdateFromTemplate(row)
            f.open()
            f.controller.dispatch(cancel)
            f.controller.dispatch(update)
            assertTrue(f.writes.isEmpty())
            f.controller.dispatch(PlanAction.DismissCancelled(f.context))
            f.controller.onRouteExited()
            f.controller.dispatch(cancel)
            f.controller.dispatch(update)
            f.controller.dispatch(PlanAction.OpenCancelled)
            assertTrue(f.writes.isEmpty())
            f.controller.onRouteEntered()
            f.drain()
            f.controller.dispatch(PlanAction.Cancel(row.copy()))
            assertTrue(f.writes.isEmpty())
            val gate = CompletableDeferred<Unit>()
            f.commit = { gate.await() }
            f.controller.dispatch(update)
            f.controller.dispatch(update)
            f.controller.dispatch(cancel)
            f.drain()
            assertEquals(1, f.writes.size)
            assertEquals(row.plan.id, (f.writes.single() as PlanMutation.Update).identity.planEntryId)
            gate.complete(Unit)
            f.drain()
            assertFalse(f.controller.state.value.isMutating)
        }
    }

    @Test
    fun admittedRestoreDismissalAndDifferentRecoveryFailuresRequireBothFreshSurfacesWithoutWriterReplay() {
        Fixture().use { f ->
            f.open()
            val row = f.rows.first()
            val restore = PlanAction.Restore(row, f.context)
            val gate = CompletableDeferred<Unit>()
            f.commit = { gate.await() }
            f.controller.dispatch(restore)
            f.drain()
            f.controller.dispatch(PlanAction.DismissCancelled(f.context))
            f.reader = { error("cancelled canonical read failed") }
            gate.complete(Unit)
            f.drain()
            assertTrue(f.controller.state.value.isMutating)
            assertEquals(PlanMessage.LOAD_FAILED, f.controller.state.value.recoveryFailure)
            f.controller.dispatch(restore)
            f.controller.onRouteExited()
            f.reader = { CancelledPlanPage(emptyList(), false) }
            f.failWeek = true
            f.controller.onRouteEntered()
            f.drain()
            assertTrue(f.controller.state.value.isMutating)
            assertTrue(f.controller.state.value.cancelled is PlanLoad.Content)
            f.failWeek = false
            f.controller.dispatch(PlanAction.Refresh)
            f.drain()
            assertFalse(f.controller.state.value.isMutating)
            assertEquals(1, f.writes.size)
            assertFalse(f.controller.state.value.cancelledOpen)
        }
    }

    @Test
    fun canonicalReplacementInvalidatesOldRestoreMoreAndRetryEvenWhenReaderReturnsSameRowObjects() {
        Fixture().use { f ->
            f.open()
            val row = f.rows.first()
            val context = f.context
            val old =
                listOf(
                    PlanAction.Restore(row, context),
                    PlanAction.LoadMoreCancelled(context, 30),
                    PlanAction.RetryCancelled(context, 0),
                )
            // A rejected current mutation still requires canonical refresh, revoking the old collection.
            f.commit = { error("writer rejected") }
            f.controller.dispatch(PlanAction.Restore(row, context))
            f.drain()
            assertEquals(PlanMessage.PERSISTENCE_FAILED, f.controller.state.value.mutationFailure)
            assertTrue(context != f.context)
            val canonical = f.controller.state.value
            old.forEach(f.controller::dispatch)
            f.drain()
            assertEquals(canonical, f.controller.state.value)
            assertEquals(1, f.writes.size)
            f.controller.dispatch(PlanAction.Restore(row, f.context))
            f.drain()
            assertEquals(2, f.writes.size)
        }
    }

    @Test
    fun overlappingPageRetainsUniquePlanIdsAndAdvancesByRawConsumedOffset() {
        Fixture().use { f ->
            f.reader = { query ->
                when (query.offset) {
                    0 -> CancelledPlanPage(f.rows.take(30), true)
                    30 -> CancelledPlanPage(listOf(f.rows.first()) + f.rows.drop(30), true)
                    else -> CancelledPlanPage(emptyList(), false)
                }
            }
            f.open()
            f.controller.dispatch(PlanAction.LoadMoreCancelled(f.context, 30))
            f.drain()
            assertEquals(f.rows, f.controller.state.value.cancelledItems)
            assertEquals(36, f.controller.state.value.cancelledOffset)
            f.controller.dispatch(PlanAction.LoadMoreCancelled(f.context, 36))
            f.drain()
            assertEquals(listOf(0, 30, 36), f.queries.map { it.offset })
            assertEquals(
                35,
                f.controller.state.value.cancelledItems
                    .map { it.plan.id }
                    .distinct()
                    .size,
            )
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    @Suppress("LongMethod") // Synchronous publication and the gated second surface form one regression.
    fun synchronousWeekRetryKeepsRecoveryLockedUntilFreshCancelledReadPublishes() {
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val row = cancelledRow("restore")
        var weekReads = 0
        var cancelledReads = 0
        var writes = 0
        var failWeek = false
        val controller =
            PlanController(
                scope,
                {
                    weekReads++
                    if (failWeek) error("Week recovery failed")
                    WeekPlanRead(it.weekStart, it.selectedDate, emptyList(), emptyList(), emptyList())
                },
                { _, _, _ -> emptyList() },
                {
                    cancelledReads++
                    if (cancelledReads == 3) gate.await()
                    CancelledPlanPage(if (writes == 0) listOf(row) else emptyList(), false)
                },
                { writes++ },
                { Instant.parse("2026-09-15T10:00:00Z") },
                { ZoneOffset.UTC },
                boundaryScheduler =
                    com.alexandr5476.lifetracing.daily
                        .LocalDateBoundaryScheduler { _, _, _, _ -> },
            )
        try {
            controller.dispatch(PlanAction.OpenCancelled)
            val old = requireNotNull(controller.state.value.cancelledContext)
            failWeek = true
            controller.dispatch(PlanAction.Restore(row, old))
            assertEquals(2, cancelledReads)
            assertTrue(controller.state.value.isMutating)
            assertEquals(PlanMessage.LOAD_FAILED, controller.state.value.recoveryFailure)
            failWeek = false
            val retry = PlanAction.RetryCancelled(requireNotNull(controller.state.value.cancelledContext), 0)
            controller.dispatch(retry)
            assertEquals(3, weekReads)
            assertEquals(3, cancelledReads)
            assertTrue(controller.state.value.isMutating)
            assertTrue(controller.state.value.cancelled is PlanLoad.Loading)
            controller.dispatch(retry)
            controller.dispatch(PlanAction.Restore(row, old))
            assertEquals(1, writes)
            gate.complete(Unit)
            assertFalse(controller.state.value.isMutating)
            assertTrue(controller.state.value.cancelled is PlanLoad.Content)
            assertTrue(
                controller.state.value.cancelledItems
                    .isEmpty(),
            )
            assertEquals(1, writes)
        } finally {
            gate.complete(Unit)
            controller.close()
            scope.cancel()
        }
    }

    private inner class Fixture : AutoCloseable {
        val tasks = ArrayDeque<Runnable>()
        private val scope = CoroutineScope(SupervisorJob() + Executor { tasks.addLast(it) }.asCoroutineDispatcher())
        var rows = (0..34).map { cancelledRow("plan-$it") }
        var active = emptyList<PlanReadRow>()
        var failWeek = false
        val queries = mutableListOf<CancelledPlanPageQuery>()
        val writes = mutableListOf<PlanMutation>()
        var reader: suspend (CancelledPlanPageQuery) -> CancelledPlanPage = {
            CancelledPlanPage(rows.drop(it.offset).take(it.limit), it.offset + it.limit < rows.size)
        }
        var commit: suspend (PlanMutation) -> Unit = {}
        val controller =
            PlanController(
                scope,
                {
                    if (failWeek) error("week failed")
                    WeekPlanRead(it.weekStart, it.selectedDate, active, emptyList(), emptyList())
                },
                { _, _, _ -> emptyList() },
                {
                    queries += it
                    reader(it)
                },
                {
                    writes += it
                    commit(it)
                },
                { Instant.parse("2026-09-15T10:00:00Z") },
                { ZoneOffset.UTC },
                boundaryScheduler =
                    com.alexandr5476.lifetracing.daily
                        .LocalDateBoundaryScheduler { _, _, _, _ -> },
            )
        val context get() = requireNotNull(controller.state.value.cancelledContext)

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }

        fun open() {
            controller.dispatch(PlanAction.OpenCancelled)
            drain()
        }

        override fun close() {
            controller.close()
            scope.cancel()
        }
    }

    private fun cancelledRow(id: String): PlanReadRow {
        val at = Instant.EPOCH
        return PlanReadRow(
            PlanEntry(
                PlanEntryId(id),
                PlanTrackableKind.ACTIVITY,
                null,
                null,
                null,
                ActivitySnapshotId("snapshot-$id"),
                null,
                PlanTarget.FloatingDay(LocalDate.parse("2026-09-15")),
                PlanEntryStatus.CANCELLED,
                null,
                null,
                at,
                at,
                at,
                null,
            ),
            null,
            null,
            "Same",
            "Frozen",
            PlanSourceState.UNAVAILABLE,
            false,
            false,
            null,
        )
    }
}
