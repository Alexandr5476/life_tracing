package com.alexandr5476.lifetracing

import androidx.navigation3.runtime.NavKey
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.history.HistoryDetailLoad
import com.alexandr5476.lifetracing.history.SequenceHistoryMutationAction
import com.alexandr5476.lifetracing.history.SequenceHistoryMutationController
import com.alexandr5476.lifetracing.history.SequenceHistoryMutationRouteSessionOwner
import com.alexandr5476.lifetracing.history.SequenceHistoryTimestampTarget
import com.alexandr5476.lifetracing.plan.PlanExecutionOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MainActivityNavigationTest {
    @Test
    fun launcherUsesTheSingleDailyBackStackAndOnlyPopsItsOwnEntry() {
        val backStack = dailyInitialBackStack.toMutableList()

        backStack.openStartActivity()
        backStack.openStartActivity()

        assertEquals(listOf(DailyRoot, StartActivityRoot), backStack)
        backStack.removeStartActivity()
        assertEquals(listOf(DailyRoot), backStack)
    }

    @Test
    fun committedReturnSelectsTodayBeforeRemovingOnlyTheLauncher() {
        val backStack: MutableList<NavKey> = mutableListOf(DailyRoot, StartActivityRoot)
        val events = mutableListOf<String>()

        backStack.completeStartActivity { events += "today" }

        assertEquals(listOf("today"), events)
        assertEquals(listOf(DailyRoot), backStack)
    }

    @Test
    fun restoredLauncherWithoutItsRetainedSessionReturnsToCanonicalDaily() {
        val backStack: MutableList<NavKey> = mutableListOf(DailyRoot, StartActivityRoot)

        backStack.normalizeRestoredStartActivity()

        assertEquals(listOf(DailyRoot), backStack)
    }

    @Test
    fun libraryUsesTheExistingDailyBackStackWithoutChangingLauncherSemantics() {
        val backStack = dailyInitialBackStack.toMutableList()

        backStack.openLibrary()
        backStack.openLibrary()

        assertEquals(listOf(DailyRoot, LibraryRoot), backStack)
        backStack.removeLibrary()
        assertEquals(listOf(DailyRoot), backStack)
    }

    @Test
    fun planUsesOneDailyOwnedDestinationAndBackOnlyPopsPlan() {
        val backStack = dailyInitialBackStack.toMutableList()

        backStack.openPlan()
        backStack.openPlan()

        assertEquals(listOf(DailyRoot, PlanRoot), backStack)
        backStack.removePlan()
        assertEquals(listOf(DailyRoot), backStack)
    }

    @Test
    fun historyAndDurableDetailRoutesUseTheExistingDailyBackStack() {
        val backStack = dailyInitialBackStack.toMutableList()

        backStack.openHistory()
        backStack.openHistory()
        backStack.openActivityHistoryDetail("activity")
        backStack.openActivityHistoryDetail("other")
        backStack.removeActivityHistoryDetail("stale")
        assertEquals(listOf(DailyRoot, HistoryRoot, ActivityHistoryDetailRoot("activity")), backStack)
        backStack.removeActivityHistoryDetail("activity")
        assertEquals(listOf(DailyRoot, HistoryRoot), backStack)
        backStack.openSequenceHistoryDetail("sequence")
        backStack.openSequenceHistoryDetail("other")
        backStack.removeSequenceHistoryDetail("other")

        assertEquals(listOf(DailyRoot, HistoryRoot, SequenceHistoryDetailRoot("sequence")), backStack)
        backStack.removeSequenceHistoryDetail("sequence")
        backStack.removeHistory()
        assertEquals(listOf(DailyRoot), backStack)
    }

    @Test
    fun statisticsUsesDailyStackOpenIsIdempotentAndBackRemovesOnlyStatistics() {
        val backStack = dailyInitialBackStack.toMutableList()
        backStack.openLibrary()
        backStack.openStatistics()
        backStack.openStatistics()
        assertEquals(listOf(DailyRoot, LibraryRoot, StatisticsRoot), backStack)

        backStack.removeStatistics()
        assertEquals(listOf(DailyRoot, LibraryRoot), backStack)
        backStack.removeLibrary()
        backStack.openPlan()
        backStack.openStatistics()
        backStack.removeStatistics()
        assertEquals(listOf(DailyRoot, PlanRoot), backStack)
        backStack.removePlan()
        backStack.openHistory()
        backStack.openStatistics()
        backStack.removeStatistics()
        assertEquals(listOf(DailyRoot, HistoryRoot), backStack)
    }

    @Test
    fun sequenceHistoryBackConsumesTransientThenReleasesIdleSessionAndReopensFresh() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val detail = sequenceDetail()
            val owner = SequenceHistoryMutationRouteSessionOwner()
            val executionId = detail.root.executionId
            var created = 0

            fun acquire() =
                owner.acquire(executionId) {
                    created++
                    sequenceController(scope, detail)
                }
            val first = acquire()
            withTimeout(2_000) { first.controller.state.first { it.load is HistoryDetailLoad.Content } }
            val backStack: MutableList<NavKey> =
                mutableListOf(DailyRoot, HistoryRoot, SequenceHistoryDetailRoot(executionId.value))

            first.controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            backStack.handleSequenceHistoryDetailBack(executionId.value, owner)
            assertEquals(3, backStack.size)
            assertTrue(owner.activeSession === first)
            assertNull(first.controller.state.value.timingDraft)

            backStack.handleSequenceHistoryDetailBack(executionId.value, owner)
            assertEquals(listOf(DailyRoot, HistoryRoot), backStack)
            assertNull(owner.activeSession)
            backStack.handleSequenceHistoryDetailBack(executionId.value, owner)
            assertEquals(listOf(DailyRoot, HistoryRoot), backStack)
            assertNull(owner.activeSession)
            backStack.openSequenceHistoryDetail(executionId.value)
            val reopened = acquire()
            assertFalse(reopened === first)
            assertEquals(2, created)
            owner.release(reopened)
            scope.cancel()
        }

    @Test
    fun sequenceHistoryBackDoesNotPopOrReleaseWhileMutationIsInFlight() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val detail = sequenceDetail()
            val gate = CompletableDeferred<Unit>()
            val owner = SequenceHistoryMutationRouteSessionOwner()
            val session =
                owner.acquire(detail.root.executionId) {
                    sequenceController(scope, detail) { gate.await() }
                }
            withTimeout(2_000) { session.controller.state.first { it.load is HistoryDetailLoad.Content } }
            val backStack: MutableList<NavKey> =
                mutableListOf(DailyRoot, HistoryRoot, SequenceHistoryDetailRoot(detail.root.executionId.value))
            session.controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            session.controller.dispatch(
                SequenceHistoryMutationAction.EditTimestamp(
                    SequenceHistoryTimestampTarget.RootEndedAt,
                    "2026-01-01T00:11:00",
                ),
            )
            session.controller.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            session.controller.dispatch(SequenceHistoryMutationAction.ConfirmTiming)
            assertTrue(session.controller.state.value.isMutating)

            backStack.handleSequenceHistoryDetailBack(detail.root.executionId.value, owner)

            assertEquals(3, backStack.size)
            assertTrue(owner.activeSession === session)
            gate.complete(Unit)
            withTimeout(2_000) { session.controller.state.first { !it.isMutating } }
            owner.release(session)
            scope.cancel()
        }

    @Test
    fun manualHistoryRouteBackRestoreAndSuccessfulDeliveryAreExactlyOnce() {
        val backStack: MutableList<NavKey> = mutableListOf(DailyRoot, HistoryRoot)
        var reloads = 0

        backStack.openManualActivityEntry()
        backStack.openManualActivityEntry()
        assertEquals(listOf(DailyRoot, HistoryRoot, ManualActivityEntryRoot), backStack)
        backStack.completeManualActivityEntry { reloads++ }
        backStack.completeManualActivityEntry { reloads++ }
        assertEquals(1, reloads)
        assertEquals(listOf(DailyRoot, HistoryRoot), backStack)

        backStack.openManualActivityEntry()
        backStack.normalizeRestoredManualActivityEntry()
        assertEquals(listOf(DailyRoot, HistoryRoot), backStack)
        assertEquals(1, reloads)
    }

    @Test
    fun planExecutionKeepsOneExactRouteAndRestoredRouteNormalizesToItsOrigin() {
        val backStack: MutableList<NavKey> = mutableListOf(DailyRoot, PlanRoot)
        val first = planIdentity("plan-a")
        val second = planIdentity("plan-b")

        backStack.openPlanExecution(first, PlanExecutionOrigin.PLAN)
        backStack.openPlanExecution(second, PlanExecutionOrigin.PLAN)
        assertEquals(
            listOf(DailyRoot, PlanRoot, PlanExecutionRoot(first.routeIdentity(), PlanExecutionOrigin.PLAN.name)),
            backStack,
        )

        backStack.removePlanExecution(second, PlanExecutionOrigin.PLAN)
        assertEquals(
            listOf(DailyRoot, PlanRoot, PlanExecutionRoot(first.routeIdentity(), PlanExecutionOrigin.PLAN.name)),
            backStack,
        )
        backStack.normalizeRestoredPlanExecution()
        assertEquals(listOf(DailyRoot, PlanRoot), backStack)
    }

    @Test
    fun planExecutionRouteMatchesTheFullIdentityAndOrigin() {
        val identity = planIdentity("plan")
        val route = PlanExecutionRoot(identity.routeIdentity(), PlanExecutionOrigin.DAILY.name)

        assertTrue(route.matches(identity, PlanExecutionOrigin.DAILY))
        assertFalse(
            route.matches(identity.copy(updatedAt = identity.updatedAt.plusSeconds(1)), PlanExecutionOrigin.DAILY),
        )
        assertFalse(
            route.matches(
                identity.copy(target = PlanTarget.Week(LocalDate.parse("2026-09-14"))),
                PlanExecutionOrigin.DAILY,
            ),
        )
        assertFalse(route.matches(identity, PlanExecutionOrigin.PLAN))
    }

    @Test
    fun restoredPlanExecutionWithoutRetainedSessionNormalizesWithoutAcquisition() {
        val identity = planIdentity("restored")
        val backStack: MutableList<NavKey> =
            mutableListOf(
                DailyRoot,
                PlanExecutionRoot(identity.routeIdentity(), PlanExecutionOrigin.DAILY.name),
            )

        backStack.normalizeRestoredPlanExecution()

        assertEquals(listOf(DailyRoot), backStack)
    }

    @Test
    fun libraryQuickStartKeepsTheRetainedLibraryEntryUnderTheLauncher() {
        val backStack: MutableList<NavKey> = mutableListOf(DailyRoot, LibraryRoot)

        backStack.openStartActivity()
        backStack.completeStartActivity {}

        assertEquals(listOf(DailyRoot, LibraryRoot), backStack)
    }

    @Test
    fun committedEditorRefreshesAndPopsExactlyOnce() {
        val backStack: MutableList<NavKey> =
            mutableListOf(DailyRoot, LibraryRoot, ExistingActivityTemplateEditor("activity"))
        var refreshes = 0

        backStack.completeActivityTemplateEditor { refreshes++ }

        assertEquals(1, refreshes)
        assertEquals(listOf(DailyRoot, LibraryRoot), backStack)
        backStack.completeActivityTemplateEditor { refreshes++ }
        assertEquals(1, refreshes)
        assertEquals(listOf(DailyRoot, LibraryRoot), backStack)
    }

    @Test
    fun restoredEditorWithoutAnInMemorySessionReturnsToLibraryInsteadOfCreatingABlankDraft() {
        val backStack: MutableList<NavKey> = mutableListOf(DailyRoot, LibraryRoot, NewActivityTemplateEditor)

        backStack.normalizeRestoredActivityTemplateEditor()

        assertEquals(listOf(DailyRoot, LibraryRoot), backStack)
    }

    @Test
    fun sequenceEditorUsesDistinctRoutesAndRestoresToLibraryWithoutADraft() {
        val backStack: MutableList<NavKey> = mutableListOf(DailyRoot, LibraryRoot)

        backStack.openNewSequenceTemplateEditor()
        assertEquals(listOf(DailyRoot, LibraryRoot, NewSequenceTemplateEditor), backStack)
        backStack.normalizeRestoredSequenceTemplateEditor()
        backStack.openExistingSequenceTemplateEditor("sequence")
        assertEquals(listOf(DailyRoot, LibraryRoot, ExistingSequenceTemplateEditor("sequence")), backStack)
        backStack.normalizeRestoredSequenceTemplateEditor()
        assertEquals(listOf(DailyRoot, LibraryRoot), backStack)
    }

    @Test
    fun committedSequenceEditorRefreshesAndPopsOnce() {
        val backStack: MutableList<NavKey> =
            mutableListOf(DailyRoot, LibraryRoot, ExistingSequenceTemplateEditor("sequence"))
        var refreshes = 0

        backStack.completeSequenceTemplateEditor { refreshes++ }
        backStack.completeSequenceTemplateEditor { refreshes++ }

        assertEquals(1, refreshes)
        assertEquals(listOf(DailyRoot, LibraryRoot), backStack)
    }

    @Test
    fun expandedSequenceRouteRetainsItsConcreteExecutionIdentity() {
        val backStack = dailyInitialBackStack.toMutableList()

        backStack.openExpandedLiveSequence("sequence-a")
        backStack.openExpandedLiveSequence("sequence-b")

        assertEquals(listOf(DailyRoot, ExpandedLiveSequenceRoot("sequence-a")), backStack)
    }

    @Test
    fun staleExpandedRouteCanOnlyRemoveItsOwnExecutionEntry() {
        val backStack: MutableList<NavKey> = mutableListOf(DailyRoot, ExpandedLiveSequenceRoot("sequence-b"))

        backStack.removeExpandedLiveSequence("sequence-a")
        assertEquals(listOf(DailyRoot, ExpandedLiveSequenceRoot("sequence-b")), backStack)

        backStack.removeExpandedLiveSequence("sequence-b")
        assertEquals(listOf(DailyRoot), backStack)
    }

    private fun planIdentity(id: String) =
        PlanActionIdentity(
            PlanEntryId(id),
            PlanTrackableKind.ACTIVITY,
            ActivitySnapshotId("snapshot-$id"),
            null,
            PlanTarget.FloatingDay(LocalDate.parse("2026-09-15")),
            PlanEntryStatus.PLANNED,
            1,
            Instant.parse("2026-09-15T10:00:00Z"),
        )

    private fun sequenceController(
        scope: CoroutineScope,
        detail: SequenceHistoryDetail,
        correctTiming: suspend () -> Unit = {},
    ) = SequenceHistoryMutationController(
        scope,
        detail.root.executionId,
        { detail },
        { _, _, _ -> correctTiming() },
        { _, _, _ -> error("unexpected") },
        { _, _, _ -> error("unexpected") },
    )

    private fun sequenceDetail() =
        SequenceHistoryDetail(
            CompletedSequenceHistoryRoot(
                SequenceExecutionId("sequence-history"),
                SequenceSnapshotId("snapshot"),
                LocalDate.parse("2026-01-01"),
                Instant.parse("2026-01-01T00:10:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                SequenceExecutionStatus.COMPLETED,
                Duration.ofMinutes(10),
                Duration.ZERO,
                Duration.ofMinutes(10),
                null,
                "Sequence",
                null,
            ),
            Instant.parse("2026-01-01T01:00:00Z"),
            ZoneOffset.UTC,
            SequenceSnapshotSettings(
                true,
                Duration.ZERO,
                Duration.ZERO,
                true,
                true,
                false,
                true,
                true,
                NoLiveTimeAccounting.ACTIVE,
            ),
            emptyList(),
            emptyList(),
            emptyList(),
        )
}
