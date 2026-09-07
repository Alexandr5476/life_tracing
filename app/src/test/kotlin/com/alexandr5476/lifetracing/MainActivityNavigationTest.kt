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
}
