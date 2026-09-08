package com.alexandr5476.lifetracing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.time.Instant

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
        val pending = LibraryTemplateId.Activity(ActivityTemplateId("pending"))
        composeTestRule.runOnUiThread { first.interaction.select(pending) }

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        assertSame(first, composeTestRule.activity.startActivityRouteSessions.activeSession)
        assertEquals(pending, first.interaction.pendingSelectionId)
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
        assertNull(next.interaction.pendingSelectionId)
        assertNull(next.interaction.quickEditor)
        assertEquals(0, next.interaction.browsePath.size)

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.launcher_browse_back)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession == null
        }
    }

    @Test
    fun productionLibraryEntersAndPopsOnTheExistingDailyStack() {
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule
            .onAllNodesWithText(composeTestRule.activity.getString(R.string.library_title))[0]
            .assertIsDisplayed()

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).assertIsDisplayed()
    }

    @Test
    fun productionEditorReturnRefreshesTheRetainedFilteredSearchFromCanonicalStorage() {
        val prefix = "S2C2-${System.nanoTime()}"
        val query = "$prefix match"
        val activityName = "$query activity"
        val sequenceName = "$query sequence"
        val renamed = "$prefix renamed"
        val newMatchingName = "$query new"
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val createdAt = Instant.now().minusSeconds(10)
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(activityName, null, TimeTrackingMode.STOPWATCH, null),
                createdAt = createdAt,
            )
        authoring.createSequenceTemplate(
            SequenceTemplateDraft(sequenceName, null),
            createdAt = createdAt.plusMillis(1),
        )

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(activityName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onNode(hasText(composeTestRule.activity.getString(R.string.library_search_hint)) and hasSetTextAction())
            .performTextInput(query)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_filter_activities))
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(activityName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(sequenceName).assertDoesNotExist()

        composeTestRule.onNodeWithText(activityName).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(hasText(activityName) and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasText(activityName) and hasSetTextAction()).performTextReplacement(renamed)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.activity_editor_done))
            .performScrollTo()
            .performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodesWithText(composeTestRule.activity.getString(R.string.library_search_empty))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(query).assertIsDisplayed()
        composeTestRule.onNodeWithText(activityName).assertDoesNotExist()
        composeTestRule.onNodeWithText(sequenceName).assertDoesNotExist()
        assertEquals(renamed, authoring.getActivityTemplate(activity.id)?.name)

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_new_activity))
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNode(hasText(composeTestRule.activity.getString(R.string.activity_editor_name)) and hasSetTextAction())
            .performTextInput(newMatchingName)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.activity_editor_done))
            .performScrollTo()
            .performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(newMatchingName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(newMatchingName).assertIsDisplayed()
        composeTestRule.onNodeWithText(sequenceName).assertDoesNotExist()
    }
}
