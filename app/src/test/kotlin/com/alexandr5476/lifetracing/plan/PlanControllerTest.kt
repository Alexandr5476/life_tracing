@file:Suppress("LargeClass")

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.util.ArrayDeque
import java.util.concurrent.Executor

class PlanControllerTest {
    @Test
    fun rescheduleRejectsUnpublishedRowsDuringAndAfterDayOrWeekNavigation() =
        runBlocking {
            val navigation =
                listOf(
                    PlanAction.NextWeek,
                    PlanAction.PreviousWeek,
                    PlanAction.SelectDate(LocalDate.parse("2026-09-16")),
                )
            for (navigate in navigation) {
                val writes = mutableListOf<PlanMutation>()
                val controller =
                    controller(readWeek = { query ->
                        week(query, row("published", 1).takeIf { query.selectedDate == LocalDate.parse("2026-09-15") })
                    }, commit = { writes += it })
                try {
                    val original = withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                    val obsolete = (original.week as PlanLoad.Content).value.selectedDayPlans.single()
                    controller.dispatch(navigate)
                    controller.dispatch(PlanAction.Reschedule(obsolete))
                    assertNull(controller.state.value.form, "Loading calendar must not admit the previous row")
                    withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                    controller.dispatch(PlanAction.Reschedule(obsolete))
                    assertNull(controller.state.value.form, "Off-screen row must not own a form")
                    assertTrue(writes.isEmpty())
                } finally {
                    controller.close()
                }
            }
        }

    @Test
    fun rescheduleRequiresExactPublishedRowAndCannotReplaceAnotherModal() =
        runBlocking {
            val writes = mutableListOf<PlanMutation>()
            val controller = controller(readWeek = { week(it, row("published", 1)) }, commit = { writes += it })
            try {
                val initial = withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                val obsolete = (initial.week as PlanLoad.Content).value.selectedDayPlans.single()
                controller.dispatch(PlanAction.Refresh)
                val refreshed = withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                val current = (refreshed.week as PlanLoad.Content).value.selectedDayPlans.single()
                // Equal values and the same writer token do not renew publication ownership.
                assertEquals(obsolete, current)
                for (unpublished in listOf(obsolete, current.copy(), row("absent", 1))) {
                    controller.dispatch(PlanAction.Reschedule(unpublished))
                    assertNull(controller.state.value.form)
                }
                controller.dispatch(PlanAction.OpenCatalog)
                val catalog = withTimeout(2_000) { controller.state.first { it.catalog?.loading == false }.catalog!! }
                controller.dispatch(PlanAction.Reschedule(current))
                assertEquals(catalog, controller.state.value.catalog)
                assertNull(controller.state.value.form)
                controller.dispatch(PlanAction.SelectCatalogItem(source, catalog.context))
                val creation = requireNotNull(controller.state.value.form)
                controller.dispatch(PlanAction.Reschedule(current))
                assertEquals(creation, controller.state.value.form)
                controller.dispatch(PlanAction.DismissForm(creation.formId))
                controller.dispatch(PlanAction.OpenCancelled)
                val cancelled = controller.state.value
                controller.dispatch(PlanAction.Reschedule(current))
                assertEquals(cancelled, controller.state.value)
                controller.dispatch(PlanAction.DismissCancelled(requireNotNull(cancelled.cancelledContext)))
                controller.dispatch(PlanAction.Reschedule(current))
                val reschedule = requireNotNull(controller.state.value.form)
                assertEquals(current.plan.id, reschedule.identity?.planEntryId)
                controller.dispatch(PlanAction.Reschedule(current))
                assertEquals(reschedule, controller.state.value.form)
                controller.dispatch(PlanAction.DismissForm(reschedule.formId))
                assertTrue(writes.isEmpty())
            } finally {
                controller.close()
            }
        }

    @Test
    fun delayedSearchAndRetryCannotOverwriteANewerAcceptedQueryOrReplacementDialog() =
        runBlocking {
            val writes = mutableListOf<PlanMutation>()
            val requests = mutableListOf<String>()
            val controller =
                controller(readCatalog = { query, _, _ ->
                    requests += query
                    listOf(source)
                }, commit = { writes += it })
            try {
                controller.dispatch(PlanAction.OpenCatalog)
                val first = withTimeout(2_000) { controller.state.first { it.catalog?.loading == false }.catalog!! }
                val delayed = PlanAction.SearchCatalog("obsolete", first.context, editSequence = 100)
                val retry = PlanAction.SearchCatalog(first.query, first.context, retry = true)
                controller.dispatch(PlanAction.SearchCatalog("a", first.context))
                val edited = requireNotNull(controller.state.value.catalog)
                controller.dispatch(PlanAction.SearchCatalog("ab", edited.context))
                val accepted = requireNotNull(controller.state.value.catalog)
                controller.dispatch(delayed)
                controller.dispatch(retry)
                assertEquals(accepted, controller.state.value.catalog)
                withTimeout(2_000) { controller.state.first { it.catalog?.loading == false } }
                assertEquals(listOf("", "a", "ab"), requests)
                controller.dispatch(PlanAction.DismissCatalog(accepted.context))
                controller.dispatch(PlanAction.OpenCatalog)
                val replacement = requireNotNull(controller.state.value.catalog)
                controller.dispatch(delayed)
                controller.dispatch(retry)
                assertEquals(replacement, controller.state.value.catalog)
                assertNull(controller.state.value.form)
                assertTrue(writes.isEmpty())
            } finally {
                controller.close()
            }
        }

    @Test
    fun reentryConsumesQueuedInvalidationWithoutRepeatingItsBlockedRead() {
        val tasks = ArrayDeque<Runnable>()
        val dispatcher = Executor { tasks.addLast(it) }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val semantic = MutableStateFlow(0L)
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val controller =
            PlanController(
                scope,
                { query ->
                    if (++reads == 2) release.await()
                    week(query)
                },
                { _, _, _ -> emptyList() },
                { CancelledPlanPage(emptyList(), false) },
                {},
                { Instant.parse("2026-09-15T10:00:00Z") },
                { ZoneOffset.UTC },
                semantic,
                FakeBoundary(),
                initiallyVisible = false,
            )

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
        try {
            controller.onRouteEntered()
            drain()
            assertEquals(1, reads)
            controller.onRouteExited()
            semantic.value++
            controller.onRouteEntered()
            drain()
            assertEquals(2, reads)
            assertTrue(controller.state.value.week is PlanLoad.Loading)
            release.complete(Unit)
            drain()
            assertEquals(2, reads)
            assertTrue(controller.state.value.week is PlanLoad.Content)
            semantic.value++
            drain()
            assertEquals(3, reads)
            semantic.value++
            controller.dispatch(PlanAction.Refresh)
            drain()
            assertEquals(4, reads)
        } finally {
            release.complete(Unit)
            controller.close()
            scope.cancel()
            drain()
        }
    }

    @Test
    fun visiblePlanRefreshesAfterRuntimeSemanticInvalidationAndReprojectsExactDay() =
        runBlocking {
            val semanticGeneration = MutableStateFlow(0L)
            var completed = false
            var zone = ZoneOffset.UTC
            val exact = Instant.parse("2026-09-15T23:30:00Z")
            val controller =
                PlanController(
                    this,
                    { query ->
                        val row = exactRow(exact, completed, exact.atZone(zone).toLocalDate())
                        week(query, row)
                    },
                    { _, _, _ -> emptyList() },
                    { CancelledPlanPage(emptyList(), false) },
                    {},
                    { Instant.parse("2026-09-15T10:00:00Z") },
                    { zone },
                    semanticGeneration,
                    FakeBoundary(),
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                completed = true
                zone = ZoneOffset.ofHours(2)
                semanticGeneration.value++
                withTimeout(2_000) {
                    controller.state.first {
                        (it.week as? PlanLoad.Content)
                            ?.value
                            ?.selectedDayPlans
                            ?.singleOrNull()
                            ?.let { row ->
                                row.plan.status == PlanEntryStatus.FULFILLED &&
                                    row.effectiveLocalDate == LocalDate.parse("2026-09-16")
                            } == true
                    }
                }
            } finally {
                controller.close()
            }
        }

    @Test
    fun exactPlanBoundaryRefreshesBeforeExactlyAtAndAfterTheScheduledInstant() =
        runBlocking {
            val clock = arrayOf(Instant.parse("2026-09-15T09:59:59Z"))
            val scheduledAt = Instant.parse("2026-09-15T10:00:00Z")
            val boundary = FakeBoundary()
            val controller =
                PlanController(
                    this,
                    { query -> week(query, exactRow(scheduledAt, now = query.now)) },
                    { _, _, _ -> emptyList() },
                    { CancelledPlanPage(emptyList(), false) },
                    {},
                    { clock[0] },
                    { ZoneOffset.UTC },
                    MutableStateFlow(0L),
                    boundary,
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                assertEquals(scheduledAt.plusMillis(1), boundary.arms.last().exactBoundary)
                assertFalse(
                    (controller.state.value.week as PlanLoad.Content)
                        .value.selectedDayPlans
                        .single()
                        .overdue,
                )

                clock[0] = scheduledAt
                boundary.fire()
                withTimeout(2_000) {
                    controller.state.first {
                        (it.week as? PlanLoad.Content)
                            ?.value
                            ?.selectedDayPlans
                            ?.single()
                            ?.overdue == false
                    }
                }

                clock[0] = scheduledAt.plusMillis(1)
                boundary.fire()
                withTimeout(2_000) {
                    controller.state.first {
                        (it.week as? PlanLoad.Content)
                            ?.value
                            ?.selectedDayPlans
                            ?.single()
                            ?.overdue == true
                    }
                }
            } finally {
                controller.close()
            }
        }

    @Test
    fun midnightBoundaryRefreshesVisibleFloatingAndWeekOverdueState() =
        runBlocking {
            val clock = arrayOf(Instant.parse("2026-09-14T23:59:59Z"))
            val boundary = FakeBoundary()
            val floatingDate = LocalDate.parse("2026-09-14")
            val weekStart = LocalDate.parse("2026-09-08")
            val controller =
                PlanController(
                    this,
                    { query ->
                        val overdue = query.now >= Instant.parse("2026-09-15T00:00:00Z")
                        WeekPlanRead(
                            query.weekStart,
                            query.selectedDate,
                            listOf(rowForTarget(PlanTarget.FloatingDay(floatingDate), overdue)),
                            listOf(rowForTarget(PlanTarget.Week(weekStart), overdue)),
                            (0L..6L).map { PlanDayPresence(query.weekStart.plusDays(it), 0) },
                        )
                    },
                    { _, _, _ -> emptyList() },
                    { CancelledPlanPage(emptyList(), false) },
                    {},
                    { clock[0] },
                    { ZoneOffset.UTC },
                    MutableStateFlow(0L),
                    boundary,
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                assertNull(boundary.arms.last().exactBoundary)
                clock[0] = Instant.parse("2026-09-15T00:00:00Z")
                boundary.fire()
                withTimeout(2_000) {
                    controller.state.first {
                        (it.week as? PlanLoad.Content)?.value?.let { read ->
                            read.selectedDayPlans.single().overdue && read.weekPlans.single().overdue
                        } == true
                    }
                }
            } finally {
                controller.close()
            }
        }

    @Test
    fun delayedExactReadCrossingBoundaryImmediatelyRereadsFromPublishedTimestamp() =
        runBlocking {
            val scheduledAt = Instant.parse("2026-09-15T10:00:00Z")
            val clock = arrayOf(Instant.parse("2026-09-15T09:59:59Z"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val boundary = ImmediatePastBoundary()
            var reads = 0
            val controller =
                PlanController(
                    this,
                    { query ->
                        if (++reads == 1) {
                            entered.complete(Unit)
                            release.await()
                        }
                        week(query, exactRow(scheduledAt, now = query.now))
                    },
                    { _, _, _ -> emptyList() },
                    { CancelledPlanPage(emptyList(), false) },
                    {},
                    { clock[0] },
                    { ZoneOffset.UTC },
                    MutableStateFlow(0L),
                    boundary,
                )
            try {
                withTimeout(2_000) { entered.await() }
                clock[0] = scheduledAt.plusMillis(1)
                release.complete(Unit)

                withTimeout(2_000) {
                    controller.state.first {
                        reads == 2 &&
                            (it.week as? PlanLoad.Content)
                                ?.value
                                ?.selectedDayPlans
                                ?.single()
                                ?.overdue == true
                    }
                }
                assertTrue(boundary.arms.any { it.exactBoundary == scheduledAt.plusMillis(1) })
            } finally {
                release.complete(Unit)
                controller.close()
            }
        }

    @Test
    fun delayedReadCrossingLocalMidnightImmediatelyRereadsFloatingOverdueState() =
        runBlocking {
            val midnight = Instant.parse("2026-09-15T00:00:00Z")
            val clock = arrayOf(midnight.minusMillis(1))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val boundary = ImmediatePastBoundary()
            var reads = 0
            val controller =
                PlanController(
                    this,
                    { query ->
                        if (++reads == 1) {
                            entered.complete(Unit)
                            release.await()
                        }
                        week(
                            query,
                            rowForTarget(
                                PlanTarget.FloatingDay(LocalDate.parse("2026-09-14")),
                                query.now >= midnight,
                            ),
                        )
                    },
                    { _, _, _ -> emptyList() },
                    { CancelledPlanPage(emptyList(), false) },
                    {},
                    { clock[0] },
                    { ZoneOffset.UTC },
                    MutableStateFlow(0L),
                    boundary,
                )
            try {
                withTimeout(2_000) { entered.await() }
                clock[0] = midnight
                release.complete(Unit)

                withTimeout(2_000) {
                    controller.state.first {
                        reads == 2 &&
                            (it.week as? PlanLoad.Content)
                                ?.value
                                ?.selectedDayPlans
                                ?.single()
                                ?.overdue == true
                    }
                }
            } finally {
                release.complete(Unit)
                controller.close()
            }
        }

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
                controller.dispatch(
                    PlanAction.SelectCatalogItem(source, requireNotNull(controller.state.value.catalog).context),
                )
                controller.dispatch(PlanAction.SubmitForm(controller.formId, controller.formRevision))
                controller.dispatch(PlanAction.SubmitForm(controller.formId, controller.formRevision))
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
                controller.dispatch(PlanAction.SubmitForm(controller.formId, controller.formRevision))
                withTimeout(2_000) { recoveryEntered.await() }

                assertTrue(controller.state.value.isMutating)
                assertNull(controller.state.value.form)
                assertEquals(PlanMessage.ACTION_UNAVAILABLE, controller.state.value.mutationFailure)
                controller.dispatch(PlanAction.Reschedule(oldRow))
                controller.dispatch(PlanAction.SubmitForm(controller.formId, controller.formRevision))
                assertEquals(1, mutations.size)

                releaseRecovery.complete(Unit)
                withTimeout(2_000) { controller.state.first { !it.isMutating } }
                controller.dispatch(PlanAction.Reschedule(freshRow))
                controller.dispatch(PlanAction.SubmitForm(controller.formId, controller.formRevision))
                withTimeout(2_000) { controller.state.first { mutations.size == 2 } }
                assertEquals(
                    freshRow.plan.updatedAt,
                    (mutations.last() as PlanMutation.PreserveSchedule).identity.updatedAt,
                )
            } finally {
                releaseRecovery.complete(Unit)
                controller.close()
            }
        }

    @Test
    fun failedWeekRecoveryExposesRetryAndOnlyFreshIdentityReenablesMutation() =
        runBlocking {
            val oldRow = row("plan", 1)
            val freshRow = row("plan", 2)
            val mutations = mutableListOf<PlanMutation>()
            var reads = 0
            val controller =
                controller(
                    readWeek = { query ->
                        reads++
                        when (reads) {
                            1 -> week(query, oldRow)
                            2 -> error("week recovery failed")
                            else -> week(query, freshRow)
                        }
                    },
                    commit = {
                        mutations += it
                        throw StalePlanActionException()
                    },
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                controller.dispatch(PlanAction.Reschedule(oldRow))
                controller.dispatch(PlanAction.SubmitForm(controller.formId, controller.formRevision))
                withTimeout(2_000) {
                    controller.state.first {
                        it.week is PlanLoad.Failure && it.recoveryFailure == PlanMessage.LOAD_FAILED
                    }
                }
                controller.dispatch(PlanAction.Reschedule(oldRow))
                assertEquals(1, mutations.size)

                controller.dispatch(PlanAction.Refresh)
                withTimeout(2_000) { controller.state.first { !it.isMutating } }
                controller.dispatch(PlanAction.Reschedule(freshRow))
                controller.dispatch(PlanAction.SubmitForm(controller.formId, controller.formRevision))
                withTimeout(2_000) { controller.state.first { mutations.size == 2 } }
                assertEquals(
                    freshRow.plan.updatedAt,
                    (mutations.last() as PlanMutation.PreserveSchedule).identity.updatedAt,
                )
            } finally {
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
                controller.dispatch(PlanAction.Restore(oldRow, controller.cancelledContext))
                withTimeout(2_000) { recoveryEntered.await() }

                assertTrue(controller.state.value.isMutating)
                controller.dispatch(PlanAction.Restore(oldRow, controller.cancelledContext))
                assertEquals(1, mutations.size)

                releaseRecovery.complete(Unit)
                withTimeout(2_000) { controller.state.first { !it.isMutating } }
                assertEquals(listOf(freshRow), controller.state.value.cancelledItems)
                controller.dispatch(PlanAction.Restore(freshRow, controller.cancelledContext))
                withTimeout(2_000) { controller.state.first { mutations.size == 2 } }
                assertEquals(freshRow.plan.updatedAt, (mutations.last() as PlanMutation.Restore).identity.updatedAt)
            } finally {
                releaseRecovery.complete(Unit)
                controller.close()
            }
        }

    @Test
    fun hiddenCancelledRecoveryFailureStaysLockedAndExposesRetryUntilBothSurfacesPublish() =
        runBlocking {
            val row = row("plan", 1)
            var cancelledReads = 0
            var mutations = 0
            val controller =
                controller(
                    readWeek = { week(it, row) },
                    readCancelled = {
                        cancelledReads++
                        if (cancelledReads == 1) error("hidden cancelled reload failed")
                        CancelledPlanPage(emptyList(), false)
                    },
                    commit = { mutations++ },
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                controller.dispatch(PlanAction.Cancel(row))
                withTimeout(2_000) { controller.state.first { it.recoveryFailure == PlanMessage.LOAD_FAILED } }

                assertTrue(controller.state.value.isMutating)
                assertFalse(controller.state.value.cancelledOpen)
                controller.dispatch(PlanAction.Cancel(row))
                assertEquals(1, mutations)

                controller.dispatch(PlanAction.Refresh)
                withTimeout(2_000) { controller.state.first { !it.isMutating } }
                assertNull(controller.state.value.recoveryFailure)
                assertEquals(2, cancelledReads)
            } finally {
                controller.close()
            }
        }

    @Test
    fun failedStaleRestoreRecoveryKeepsOldIdentityBlockedUntilCancelledRetryPublishesFreshIdentity() =
        runBlocking {
            val oldRow = row("cancelled", 1, PlanEntryStatus.CANCELLED)
            val freshRow = row("cancelled", 2, PlanEntryStatus.CANCELLED)
            val mutations = mutableListOf<PlanMutation>()
            var cancelledReads = 0
            val controller =
                controller(
                    readCancelled = {
                        cancelledReads++
                        when (cancelledReads) {
                            1 -> CancelledPlanPage(listOf(oldRow), false)
                            2 -> error("cancelled recovery failed")
                            else -> CancelledPlanPage(listOf(freshRow), false)
                        }
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
                controller.dispatch(PlanAction.Restore(oldRow, controller.cancelledContext))
                withTimeout(2_000) {
                    controller.state.first {
                        it.cancelled is PlanLoad.Failure && it.recoveryFailure == PlanMessage.LOAD_FAILED
                    }
                }

                controller.dispatch(PlanAction.Restore(oldRow, controller.cancelledContext))
                assertEquals(1, mutations.size)
                controller.dispatch(PlanAction.RetryCancelled(controller.cancelledContext, 0))
                withTimeout(2_000) { controller.state.first { !it.isMutating } }
                assertEquals(listOf(freshRow), controller.state.value.cancelledItems)

                controller.dispatch(PlanAction.Restore(freshRow, controller.cancelledContext))
                withTimeout(2_000) { controller.state.first { mutations.size == 2 } }
                assertEquals(freshRow.plan.updatedAt, (mutations.last() as PlanMutation.Restore).identity.updatedAt)
            } finally {
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
                controller.dispatch(
                    PlanAction.SelectCatalogItem(source, requireNotNull(controller.state.value.catalog).context),
                )
                controller.state.value.form
                    ?.let { controller.dispatch(PlanAction.DismissForm(it.formId)) }
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
    fun cancelRecoveryResumesBothCanonicalReadsAfterRouteExit() =
        runBlocking {
            val staleWeekEntered = CompletableDeferred<Unit>()
            val staleCancelledEntered = CompletableDeferred<Unit>()
            val releaseStaleReads = CompletableDeferred<Unit>()
            val staleWeekFinished = CompletableDeferred<Unit>()
            val staleCancelledFinished = CompletableDeferred<Unit>()
            val planned = row("plan", 1)
            val cancelled = row("plan", 2, PlanEntryStatus.CANCELLED)
            var durableCancelled = false
            var weekReads = 0
            var cancelledReads = 0
            val controller =
                controller(
                    readWeek = { query ->
                        if (++weekReads == 2) {
                            staleWeekEntered.complete(Unit)
                            releaseStaleReads.await()
                            staleWeekFinished.complete(Unit)
                        }
                        week(query, planned.takeUnless { durableCancelled })
                    },
                    readCancelled = {
                        if (++cancelledReads == 1) {
                            staleCancelledEntered.complete(Unit)
                            releaseStaleReads.await()
                            staleCancelledFinished.complete(Unit)
                        }
                        CancelledPlanPage(listOfNotNull(cancelled.takeIf { durableCancelled }), false)
                    },
                    commit = { durableCancelled = true },
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                controller.dispatch(PlanAction.Cancel(planned))
                withTimeout(2_000) { staleWeekEntered.await() }
                withTimeout(2_000) { staleCancelledEntered.await() }

                controller.onRouteExited()
                releaseStaleReads.complete(Unit)
                withTimeout(2_000) { staleWeekFinished.await() }
                withTimeout(2_000) { staleCancelledFinished.await() }
                assertTrue(durableCancelled)
                assertTrue(controller.state.value.isMutating)
                assertTrue(
                    controller.state.value
                        .cancelledItems
                        .isEmpty(),
                )

                controller.onRouteEntered()
                withTimeout(2_000) { controller.state.first { !it.isMutating } }

                assertEquals(3, weekReads)
                assertEquals(2, cancelledReads)
                assertFalse(controller.state.value.cancelledOpen)
                assertEquals(listOf(cancelled), controller.state.value.cancelledItems)
            } finally {
                releaseStaleReads.complete(Unit)
                controller.close()
            }
        }

    @Test
    fun restoreRecoveryStartedWhileRouteIsHiddenResumesOnReentry() =
        runBlocking {
            val commitEntered = CompletableDeferred<Unit>()
            val releaseCommit = CompletableDeferred<Unit>()
            val cancelled = row("plan", 1, PlanEntryStatus.CANCELLED)
            val restored = row("plan", 2)
            var durableRestored = false
            var weekReads = 0
            var cancelledReads = 0
            val controller =
                controller(
                    readWeek = { query ->
                        weekReads++
                        week(query, restored.takeIf { durableRestored })
                    },
                    readCancelled = {
                        cancelledReads++
                        CancelledPlanPage(listOfNotNull(cancelled.takeUnless { durableRestored }), false)
                    },
                    commit = {
                        commitEntered.complete(Unit)
                        releaseCommit.await()
                        durableRestored = true
                    },
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                controller.dispatch(PlanAction.OpenCancelled)
                withTimeout(2_000) { controller.state.first { it.cancelledItems == listOf(cancelled) } }
                controller.dispatch(PlanAction.Restore(cancelled, controller.cancelledContext))
                withTimeout(2_000) { commitEntered.await() }

                controller.onRouteExited()
                releaseCommit.complete(Unit)
                withTimeout(2_000) { while (!durableRestored) kotlinx.coroutines.yield() }
                assertTrue(controller.state.value.isMutating)

                controller.onRouteEntered()
                withTimeout(2_000) { controller.state.first { !it.isMutating } }

                assertEquals(2, weekReads)
                assertEquals(2, cancelledReads)
                assertFalse(controller.state.value.cancelledOpen)
                assertEquals(
                    listOf(restored),
                    (controller.state.value.week as PlanLoad.Content).value.selectedDayPlans,
                )
                assertTrue(
                    controller.state.value
                        .cancelledItems
                        .isEmpty(),
                )
            } finally {
                releaseCommit.complete(Unit)
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
                controller.dispatch(
                    PlanAction.SearchCatalog("old", requireNotNull(controller.state.value.catalog).context),
                )
                withTimeout(2_000) { oldCatalogEntered.await() }
                controller.dispatch(
                    PlanAction.SearchCatalog("new", requireNotNull(controller.state.value.catalog).context),
                )
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

                controller.dispatch(PlanAction.DismissCatalog(requireNotNull(controller.state.value.catalog).context))
                controller.dispatch(PlanAction.OpenCancelled)
                withTimeout(2_000) { cancelledEntered.await() }
                controller.dispatch(PlanAction.DismissCancelled(controller.cancelledContext))
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

    @Test
    @Suppress("LongMethod") // Two ownership resets share one deterministic delayed append fixture.
    fun delayedCatalogAppendCannotPublishAfterQueryResetOrRouteExit() =
        runBlocking {
            val appendEntered = CompletableDeferred<Unit>()
            val releaseAppend = CompletableDeferred<Unit>()
            val appendFinished = CompletableDeferred<Unit>()
            val exitAppendEntered = CompletableDeferred<Unit>()
            val releaseExitAppend = CompletableDeferred<Unit>()
            val exitAppendFinished = CompletableDeferred<Unit>()
            val pageOne =
                (1..31).map {
                    source.copy(
                        id = LibraryTemplateId.Activity(ActivityTemplateId("page-$it")),
                        name = "A %02d".format(it),
                    )
                }
            val queryB = source.copy(name = "B")
            var appendCalls = 0
            val controller =
                controller(
                    readCatalog = { query, _, after ->
                        when {
                            query == "A" && after == null -> pageOne
                            query == "A" -> {
                                appendCalls++
                                if (appendCalls == 1) {
                                    appendEntered.complete(Unit)
                                    releaseAppend.await()
                                    appendFinished.complete(Unit)
                                } else {
                                    exitAppendEntered.complete(Unit)
                                    releaseExitAppend.await()
                                    exitAppendFinished.complete(Unit)
                                }
                                listOf(source.copy(name = "stale append"))
                            }
                            query == "B" -> listOf(queryB)
                            else -> emptyList()
                        }
                    },
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                controller.dispatch(PlanAction.OpenCatalog)
                withTimeout(2_000) { controller.state.first { it.catalog?.loading == false } }
                controller.dispatch(
                    PlanAction.SearchCatalog("A", requireNotNull(controller.state.value.catalog).context),
                )
                withTimeout(2_000) { controller.state.first { it.catalog?.hasNextPage == true } }
                controller.dispatch(PlanAction.LoadMoreCatalog(requireNotNull(controller.state.value.catalog).context))
                withTimeout(2_000) { appendEntered.await() }
                controller.dispatch(
                    PlanAction.SearchCatalog("B", requireNotNull(controller.state.value.catalog).context),
                )
                withTimeout(2_000) { controller.state.first { it.catalog?.items == listOf(queryB) } }
                releaseAppend.complete(Unit)
                withTimeout(2_000) { appendFinished.await() }
                assertEquals(
                    listOf(queryB),
                    controller.state.value.catalog
                        ?.items,
                )

                controller.dispatch(
                    PlanAction.SearchCatalog("A", requireNotNull(controller.state.value.catalog).context),
                )
                withTimeout(2_000) { controller.state.first { it.catalog?.hasNextPage == true } }
                controller.dispatch(PlanAction.LoadMoreCatalog(requireNotNull(controller.state.value.catalog).context))
                withTimeout(2_000) { exitAppendEntered.await() }
                controller.onRouteExited()
                releaseExitAppend.complete(Unit)
                withTimeout(2_000) { exitAppendFinished.await() }
                assertNull(controller.state.value.catalog)
            } finally {
                releaseAppend.complete(Unit)
                releaseExitAppend.complete(Unit)
                controller.close()
            }
        }

    @Test
    fun dismissedCatalogCannotBeReopenedOrSelectedByObsoleteCallbacks() =
        runBlocking {
            val mutations = mutableListOf<PlanMutation>()
            val controller = controller(commit = { mutations += it })
            try {
                controller.dispatch(PlanAction.OpenCatalog)
                withTimeout(2_000) { controller.state.first { it.catalog?.loading == false } }
                val context = requireNotNull(controller.state.value.catalog).context
                controller.dispatch(PlanAction.DismissCatalog(context))
                controller.dispatch(PlanAction.SearchCatalog("obsolete", context))
                controller.dispatch(PlanAction.SelectCatalogItem(source, context))
                assertNull(controller.state.value.catalog)
                assertNull(controller.state.value.form)
                assertTrue(mutations.isEmpty())
            } finally {
                controller.close()
            }
        }

    @Test
    @Suppress("LongMethod") // Each obsolete action is replayed across independently established ownership boundaries.
    fun obsoleteCatalogActionsCannotOwnNewQueryDialogFormOrHiddenRoute() =
        runBlocking {
            val mutations = mutableListOf<PlanMutation>()
            val controller = controller(readWeek = { week(it, row("existing", 1)) }, commit = { mutations += it })
            try {
                controller.dispatch(PlanAction.OpenCatalog)
                val first = withTimeout(2_000) { controller.state.first { it.catalog?.loading == false }.catalog!! }
                val obsoleteResults =
                    listOf(
                        PlanAction.SelectCatalogItem(source, first.context),
                        PlanAction.LoadMoreCatalog(first.context),
                        PlanAction.SearchCatalog("stale retry", first.context, retry = true),
                    )
                val obsolete =
                    obsoleteResults +
                        listOf(
                            PlanAction.SearchCatalog("stale", first.context),
                            PlanAction.DismissCatalog(first.context),
                        )
                controller.dispatch(PlanAction.SearchCatalog("new", first.context))
                val next = withTimeout(2_000) { controller.state.first { it.catalog?.loading == false }.catalog!! }
                assertEquals(first.items, next.items) // Even the same exact identity cannot renew an old callback.
                obsoleteResults.forEach(controller::dispatch)
                assertEquals(next, controller.state.value.catalog)
                assertNull(controller.state.value.form)
                controller.dispatch(PlanAction.DismissCatalog(next.context))
                obsolete.forEach(controller::dispatch)
                assertNull(controller.state.value.catalog)
                assertNull(controller.state.value.form)
                controller.dispatch(PlanAction.OpenCatalog)
                val reopened = withTimeout(2_000) { controller.state.first { it.catalog?.loading == false }.catalog!! }
                obsolete.forEach(controller::dispatch)
                controller.dispatch(PlanAction.DismissCatalog(next.context))
                assertEquals(reopened, controller.state.value.catalog)
                controller.dispatch(PlanAction.SelectCatalogItem(source, reopened.context))
                val form = controller.state.value.form
                obsolete.forEach(controller::dispatch)
                assertEquals(form, controller.state.value.form)
                assertNull(controller.state.value.catalog)
                controller.dispatch(PlanAction.OpenCatalog)
                val replaced = withTimeout(2_000) { controller.state.first { it.catalog?.loading == false }.catalog!! }
                controller.dispatch(PlanAction.DismissCatalog(replaced.context))
                val published = (controller.state.value.week as PlanLoad.Content).value.selectedDayPlans.single()
                controller.dispatch(PlanAction.Reschedule(published))
                val reschedule = controller.state.value.form
                controller.dispatch(PlanAction.SelectCatalogItem(source, replaced.context))
                controller.dispatch(PlanAction.SearchCatalog("obsolete replacement", replaced.context))
                assertEquals(reschedule, controller.state.value.form)
                assertNull(controller.state.value.catalog)
                controller.onRouteExited()
                (obsolete + PlanAction.SelectCatalogItem(source, reopened.context)).forEach(controller::dispatch)
                controller.dispatch(PlanAction.OpenCatalog)
                assertNull(controller.state.value.catalog)
                assertNull(controller.state.value.form)
                controller.onRouteEntered()
                assertNull(controller.state.value.catalog)
                assertTrue(mutations.isEmpty())
            } finally {
                controller.close()
            }
        }

    @Test
    fun pendingCatalogDismissalRejectsReadsAndInteractionsAfterReentry() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val mutations = mutableListOf<PlanMutation>()
            val controller =
                controller(readCatalog = { _, _, _ ->
                    entered.complete(Unit)
                    release.await()
                    finished.complete(Unit)
                    listOf(source)
                }, commit = { mutations += it })
            try {
                controller.dispatch(PlanAction.OpenCatalog)
                withTimeout(2_000) { entered.await() }
                val context = requireNotNull(controller.state.value.catalog).context
                controller.dispatch(PlanAction.DismissCatalog(context))
                controller.onRouteExited()
                controller.dispatch(PlanAction.SearchCatalog("stale", context))
                controller.dispatch(PlanAction.SelectCatalogItem(source, context))
                release.complete(Unit)
                withTimeout(2_000) { finished.await() }
                controller.onRouteEntered()
                assertNull(controller.state.value.catalog)
                assertNull(controller.state.value.form)
                assertTrue(mutations.isEmpty())
            } finally {
                release.complete(Unit)
                controller.close()
            }
        }

    @Test
    @Suppress("LongMethod") // Bounded first/append failure recovery and cursor identity form one catalog journey.
    fun boundedTypedCatalogPagesRetryWithoutLosingSourcesOrSelecting() =
        runBlocking {
            val activity = source.copy(name = "Same")
            val sequence =
                activity.copy(
                    id =
                        LibraryTemplateId.Sequence(
                            com.alexandr5476.lifetracing.domain
                                .SequenceTemplateId(activity.id.value),
                        ),
                )
            val page =
                listOf(activity, sequence) +
                    (1..29).map {
                        source.copy(
                            id = LibraryTemplateId.Activity(ActivityTemplateId("extra-$it")),
                            name = "Z %02d".format(it),
                        )
                    }
            val requests = mutableListOf<Triple<String, Int, ReusablePlanCatalogItem?>>()
            var failFirst = true
            var failAppend = true
            val mutations = mutableListOf<PlanMutation>()
            val controller =
                controller(readCatalog = { query, limit, after ->
                    requests += Triple(query, limit, after)
                    if (after == null && failFirst) {
                        failFirst = false
                        error("first page")
                    }
                    if (after != null && failAppend) {
                        failAppend = false
                        error("append")
                    }
                    if (after == null) page else listOf(page.last(), page.last())
                }, commit = { mutations += it })
            try {
                controller.dispatch(PlanAction.OpenCatalog)
                val failed = withTimeout(2_000) { controller.state.first { it.catalog?.failure != null }.catalog!! }
                assertTrue(failed.items.isEmpty())
                controller.dispatch(PlanAction.SearchCatalog("  raw query  ", failed.context))
                val loaded = withTimeout(2_000) { controller.state.first { it.catalog?.loading == false }.catalog!! }
                assertEquals(page.take(30), loaded.items)
                assertEquals("  raw query  ", loaded.query)
                controller.dispatch(PlanAction.LoadMoreCatalog(loaded.context))
                val appendFailed =
                    withTimeout(2_000) { controller.state.first { it.catalog?.failure != null }.catalog!! }
                assertEquals(loaded.items, appendFailed.items)
                assertEquals(loaded.context, appendFailed.context)
                controller.dispatch(PlanAction.LoadMoreCatalog(appendFailed.context))
                val all = withTimeout(2_000) { controller.state.first { it.catalog?.loading == false }.catalog!! }
                assertEquals(page, all.items)
                assertEquals(page.map { it.id }.toSet().size, all.items.size)
                assertFalse(all.hasNextPage)
                assertEquals(listOf(31, 31, 31, 31), requests.map { it.second })
                assertEquals(listOf(null, null, page[29], page[29]), requests.map { it.third })
                assertEquals(listOf("", "  raw query  ", "  raw query  ", "  raw query  "), requests.map { it.first })
                assertNull(controller.state.value.form)
                assertTrue(mutations.isEmpty())
            } finally {
                controller.close()
            }
        }

    @Test
    fun duplicateNamesAndCollidingIdValuesHandoffExactTypedSourceToFreshUntouchedForms() =
        runBlocking {
            val activity = source.copy(name = "Same")
            val sequence =
                activity.copy(
                    id =
                        LibraryTemplateId.Sequence(
                            com.alexandr5476.lifetracing.domain
                                .SequenceTemplateId(activity.id.value),
                        ),
                )
            val mutations = mutableListOf<PlanMutation>()
            val controller =
                controller(readCatalog = { _, _, _ -> listOf(activity, sequence) }, commit = {
                    mutations +=
                        it
                })
            val forms = mutableListOf<PlanScheduleForm>()
            try {
                val selected = LocalDate.parse("2026-09-17")
                controller.dispatch(PlanAction.SelectDate(selected))
                for (item in listOf(activity, sequence)) {
                    controller.dispatch(PlanAction.OpenCatalog)
                    val catalog =
                        withTimeout(2_000) { controller.state.first { it.catalog?.loading == false }.catalog!! }
                    controller.dispatch(
                        PlanAction.SelectCatalogItem(
                            item.copy(id = LibraryTemplateId.Activity(ActivityTemplateId("absent"))),
                            catalog.context,
                        ),
                    )
                    assertNull(controller.state.value.form)
                    controller.dispatch(PlanAction.SelectCatalogItem(item, catalog.context))
                    val form = requireNotNull(controller.state.value.form)
                    forms += form
                    assertNull(controller.state.value.catalog)
                    assertEquals(item, form.source)
                    assertEquals(item.id, form.source?.id)
                    assertNull(form.identity)
                    assertEquals(PlanScheduleKind.FLOATING_DAY, form.kind)
                    assertEquals(selected.toString(), form.date)
                    assertEquals(ZoneOffset.UTC, form.schedulingZoneId)
                    assertFalse(form.scheduleEdited)
                    assertEquals(0L, form.inputRevision)
                    controller.dispatch(PlanAction.SelectCatalogItem(item, catalog.context))
                    assertEquals(form, controller.state.value.form)
                }
                assertEquals(2, forms.map { it.formId }.toSet().size)
                assertTrue(mutations.isEmpty())
            } finally {
                controller.close()
            }
        }

    @Test
    fun currentLoadedSourceRemainsSelectableDuringAppendAndAppendCannotReopenFormHandoff() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val page = (1..31).map { source.copy(id = LibraryTemplateId.Activity(ActivityTemplateId("page-$it"))) }
            val mutations = mutableListOf<PlanMutation>()
            val controller =
                controller(readCatalog = { _, _, after ->
                    if (after == null) {
                        page
                    } else {
                        entered.complete(Unit)
                        release.await()
                        finished.complete(Unit)
                        listOf(page.last())
                    }
                }, commit = { mutations += it })
            try {
                controller.dispatch(PlanAction.OpenCatalog)
                val catalog = withTimeout(2_000) { controller.state.first { it.catalog?.loading == false }.catalog!! }
                controller.dispatch(PlanAction.LoadMoreCatalog(catalog.context))
                withTimeout(2_000) { entered.await() }
                controller.dispatch(PlanAction.SelectCatalogItem(page.first(), catalog.context))
                val form = requireNotNull(controller.state.value.form)
                assertEquals(page.first().id, form.source?.id)
                release.complete(Unit)
                withTimeout(2_000) { finished.await() }
                assertNull(controller.state.value.catalog)
                assertEquals(form, controller.state.value.form)
                assertTrue(mutations.isEmpty())
            } finally {
                release.complete(Unit)
                controller.close()
            }
        }

    @Test
    fun survivingSearchAndCloseCallbacksKeepDialogOwnershipWhileResultsRequireCurrentCollection() =
        runBlocking {
            val mutations = mutableListOf<PlanMutation>()
            val controller = controller(commit = { mutations += it })
            try {
                controller.dispatch(PlanAction.OpenCatalog)
                val first = withTimeout(2_000) { controller.state.first { it.catalog?.loading == false }.catalog!! }
                controller.dispatch(PlanAction.SearchCatalog("a", first.context, editSequence = 1))
                controller.dispatch(PlanAction.SearchCatalog("ab", first.context, editSequence = 2))
                controller.dispatch(PlanAction.SearchCatalog("delayed a", first.context, editSequence = 1))
                assertEquals(
                    "ab",
                    controller.state.value.catalog
                        ?.query,
                )
                controller.dispatch(PlanAction.SelectCatalogItem(source, first.context))
                controller.dispatch(PlanAction.LoadMoreCatalog(first.context))
                assertNull(controller.state.value.form)
                controller.dispatch(PlanAction.DismissCatalog(first.context))
                assertNull(controller.state.value.catalog)
                assertTrue(mutations.isEmpty())
            } finally {
                controller.close()
            }
        }

    @Test
    fun dismissedPendingSaveCommitsOnceAndCanonicalRetryNeverReplaysItsWriter() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val mutations = mutableListOf<PlanMutation>()
            var rejectRead = false
            val controller =
                controller(
                    readWeek = {
                        if (rejectRead) error("canonical read failed after commit")
                        week(it, row("pending", 1))
                    },
                    commit = {
                        mutations += it
                        entered.complete(Unit)
                        release.await()
                    },
                )
            try {
                withTimeout(2_000) { controller.state.first { it.week is PlanLoad.Content } }
                val published = (controller.state.value.week as PlanLoad.Content).value.selectedDayPlans.single()
                controller.dispatch(PlanAction.Reschedule(published))
                val form = requireNotNull(controller.state.value.form)
                val save = PlanAction.SubmitForm(form.formId, form.inputRevision)
                controller.dispatch(save)
                withTimeout(2_000) { entered.await() }
                controller.dispatch(save)
                controller.dispatch(PlanAction.DismissForm(form.formId))
                assertNull(controller.state.value.form)
                controller.dispatch(save)
                controller.dispatch(PlanAction.Reschedule(row("replacement", 1)))
                assertNull(controller.state.value.form)
                rejectRead = true
                release.complete(Unit)
                withTimeout(2_000) { controller.state.first { it.recoveryFailure == PlanMessage.LOAD_FAILED } }
                assertEquals(1, mutations.size)
                assertTrue(controller.state.value.isMutating)
                rejectRead = false
                controller.dispatch(PlanAction.Refresh)
                withTimeout(2_000) { controller.state.first { !it.isMutating } }
                controller.dispatch(save)
                assertNull(controller.state.value.form)
                assertEquals(1, mutations.size)
            } finally {
                release.complete(Unit)
                controller.close()
            }
        }

    private val PlanController.cancelledContext get() = requireNotNull(state.value.cancelledContext)

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

    private fun exactRow(
        scheduledAt: Instant,
        completed: Boolean = false,
        effectiveDate: LocalDate = scheduledAt.atZone(ZoneOffset.UTC).toLocalDate(),
        now: Instant = Instant.EPOCH,
    ): PlanReadRow {
        val status = if (completed) PlanEntryStatus.FULFILLED else PlanEntryStatus.PLANNED
        return PlanReadRow(
            PlanEntry(
                PlanEntryId("exact"),
                PlanTrackableKind.ACTIVITY,
                ActivityTemplateId("activity"),
                null,
                1,
                ActivitySnapshotId("snapshot"),
                null,
                PlanTarget.ExactDay(scheduledAt, ZoneOffset.UTC),
                status,
                null,
                null,
                Instant.EPOCH,
                now,
                null,
                now.takeIf { status == PlanEntryStatus.FULFILLED },
            ),
            effectiveDate,
            scheduledAt.atZone(ZoneOffset.UTC).toLocalTime(),
            "Activity",
            null,
            PlanSourceState.CURRENT,
            false,
            !completed && now > scheduledAt,
            null,
        )
    }

    private fun rowForTarget(
        target: PlanTarget,
        overdue: Boolean,
    ): PlanReadRow =
        PlanReadRow(
            PlanEntry(
                PlanEntryId(target.toString()),
                PlanTrackableKind.ACTIVITY,
                ActivityTemplateId("activity"),
                null,
                1,
                ActivitySnapshotId("snapshot"),
                null,
                target,
                PlanEntryStatus.PLANNED,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                null,
            ),
            (target as? PlanTarget.FloatingDay)?.date,
            null,
            "Activity",
            null,
            PlanSourceState.CURRENT,
            false,
            overdue,
            null,
        )

    private open class FakeBoundary : com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler {
        data class Arm(
            val exactBoundary: Instant?,
            val callback: () -> Unit,
        )

        val arms = mutableListOf<Arm>()

        override fun arm(
            now: Instant,
            zoneId: java.time.ZoneId,
            exactBoundary: Instant?,
            onBoundary: () -> Unit,
        ) {
            arms += Arm(exactBoundary, onBoundary)
        }

        fun fire() = arms.last().callback()
    }

    private class ImmediatePastBoundary : FakeBoundary() {
        override fun arm(
            now: Instant,
            zoneId: java.time.ZoneId,
            exactBoundary: Instant?,
            onBoundary: () -> Unit,
        ) {
            super.arm(now, zoneId, exactBoundary, onBoundary)
            if (exactBoundary != null && exactBoundary <= now) onBoundary()
        }
    }

    private companion object {
        val source =
            ReusablePlanCatalogItem(LibraryTemplateId.Activity(ActivityTemplateId("activity")), "Activity", null, null)
    }
}
