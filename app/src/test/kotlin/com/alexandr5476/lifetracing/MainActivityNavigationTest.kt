package com.alexandr5476.lifetracing

import androidx.navigation3.runtime.NavKey
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.plan.PlanExecutionOrigin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

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
}
