package com.alexandr5476.lifetracing

import androidx.navigation3.runtime.NavKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
    fun planExecutionKeepsOneExactRouteAndRestoredRouteNormalizesToItsOrigin() {
        val backStack: MutableList<NavKey> = mutableListOf(DailyRoot, PlanRoot)

        backStack.openPlanExecution("plan-a")
        backStack.openPlanExecution("plan-b")
        assertEquals(listOf(DailyRoot, PlanRoot, PlanExecutionRoot("plan-a")), backStack)

        backStack.removePlanExecution("plan-b")
        assertEquals(listOf(DailyRoot, PlanRoot, PlanExecutionRoot("plan-a")), backStack)
        backStack.normalizeRestoredPlanExecution()
        assertEquals(listOf(DailyRoot, PlanRoot), backStack)
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
}
