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

class PlanControllerTest {
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
                controller.dispatch(PlanAction.SubmitForm)
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
                controller.dispatch(PlanAction.SubmitForm)
                withTimeout(2_000) { controller.state.first { mutations.size == 2 } }
                assertEquals(freshRow.plan.updatedAt, (mutations.last() as PlanMutation.Reschedule).identity.updatedAt)
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
                controller.dispatch(PlanAction.Restore(oldRow))
                withTimeout(2_000) {
                    controller.state.first {
                        it.cancelled is PlanLoad.Failure && it.recoveryFailure == PlanMessage.LOAD_FAILED
                    }
                }

                controller.dispatch(PlanAction.Restore(oldRow))
                assertEquals(1, mutations.size)
                controller.dispatch(PlanAction.OpenCancelled)
                withTimeout(2_000) { controller.state.first { !it.isMutating } }
                assertEquals(listOf(freshRow), controller.state.value.cancelledItems)

                controller.dispatch(PlanAction.Restore(freshRow))
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
            val pageOne = (1..31).map { source.copy(name = "A %02d".format(it)) }
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
                controller.dispatch(PlanAction.SearchCatalog("A"))
                withTimeout(2_000) { controller.state.first { it.catalog?.hasNextPage == true } }
                controller.dispatch(PlanAction.LoadMoreCatalog)
                withTimeout(2_000) { appendEntered.await() }
                controller.dispatch(PlanAction.SearchCatalog("B"))
                withTimeout(2_000) { controller.state.first { it.catalog?.items == listOf(queryB) } }
                releaseAppend.complete(Unit)
                withTimeout(2_000) { appendFinished.await() }
                assertEquals(
                    listOf(queryB),
                    controller.state.value.catalog
                        ?.items,
                )

                controller.dispatch(PlanAction.SearchCatalog("A"))
                withTimeout(2_000) { controller.state.first { it.catalog?.hasNextPage == true } }
                controller.dispatch(PlanAction.LoadMoreCatalog)
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

    private class FakeBoundary : com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler {
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

    private companion object {
        val source =
            ReusablePlanCatalogItem(LibraryTemplateId.Activity(ActivityTemplateId("activity")), "Activity", null, null)
    }
}
