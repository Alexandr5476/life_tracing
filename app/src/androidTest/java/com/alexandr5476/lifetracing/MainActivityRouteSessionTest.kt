package com.alexandr5476.lifetracing

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSession
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class MainActivityRouteSessionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun productionLauncherSessionSurvivesActivityRecreationAndIsReleasedOnlyWhenPopped() {
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_start_activity)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession != null
        }
        val first = requireNotNull(composeTestRule.activity.startActivityRouteSessions.activeSession)

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        assertSame(first, composeTestRule.activity.startActivityRouteSessions.activeSession)
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession == null
        }
        assertNull(composeTestRule.activity.startActivityRouteSessions.activeSession)

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_start_activity)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession != null
        }
        val next: StartActivityRouteSession =
            requireNotNull(composeTestRule.activity.startActivityRouteSessions.activeSession)
        assertNotSame(first, next)

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.launcher_browse_back)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession == null
        }
    }
}
