package com.alexandr5476.lifetracing.launcher

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.alexandr5476.lifetracing.domain.ActivityLaunchMainValue
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.time.Duration

class StartActivityScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun immediateSelectionUsesOneSelectThenLaunchWithoutConfirmation() {
        val actions = mutableListOf<StartActivityAction>()
        val recent = trackable("Recent Activity")
        var state by mutableStateOf(homeState(recent = listOf(recent)))
        composeTestRule.setContent {
            LifeTracingTheme { StartActivityScreen(state, actions::add) }
        }

        composeTestRule.onNodeWithText("Recent Activity").performClick()
        assertEquals(listOf(StartActivityAction.Select(recent.id)), actions)

        state = state.copy(selected = LauncherLoad.Content(activityTarget(recent.id)))
        composeTestRule.runOnIdle {
            assertEquals(
                listOf(StartActivityAction.Select(recent.id), StartActivityAction.Launch()),
                actions,
            )
        }
    }

    @Test
    fun noLiveMainValueStaysCompactAndSearchStartsIdle() {
        val actions = mutableListOf<StartActivityAction>()
        val trackable = trackable("Count glasses")
        var state by mutableStateOf(homeState(recent = listOf(trackable)))
        composeTestRule.setContent {
            LifeTracingTheme { StartActivityScreen(state, actions::add) }
        }

        composeTestRule.onNodeWithText("Count glasses").performClick()
        state =
            state.copy(
                selected =
                    LauncherLoad.Content(
                        activityTarget(
                            trackable.id,
                            mainValue =
                                ActivityLaunchMainValue(
                                    ActivityTemplateFieldId("water"),
                                    "Glasses",
                                    "cups",
                                    0,
                                    2_000,
                                ),
                        ),
                    ),
            )
        composeTestRule.onNodeWithText("Glasses cups").assertIsDisplayed()
        composeTestRule.onNodeWithText("Complete").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertFalse(actions.any { it is StartActivityAction.Launch })
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Search library").performTextInput("water")
        composeTestRule.runOnIdle {
            assertEquals(StartActivityAction.Search("water"), actions.last())
        }
    }

    private fun homeState(recent: List<LibraryTrackable>) =
        StartActivityState(
            home = LauncherLoad.Content(LauncherHome(recent, listOf(trackable("Pinned Sequence", sequence = true)))),
        )

    private fun trackable(
        name: String,
        sequence: Boolean = false,
    ): LibraryTrackable {
        val id =
            if (sequence) {
                LibraryTemplateId.Sequence(
                    com.alexandr5476.lifetracing.domain
                        .SequenceTemplateId(name),
                )
            } else {
                LibraryTemplateId.Activity(ActivityTemplateId(name))
            }
        return LibraryTrackable(id, name, null, null, emptySet(), null, null, null)
    }

    private fun activityTarget(
        id: LibraryTemplateId,
        mainValue: ActivityLaunchMainValue? = null,
    ) = LibraryLaunchTarget.Activity(
        id as LibraryTemplateId.Activity,
        id.value,
        Duration.ZERO,
        TimeTrackingMode.NO_LIVE_TRACKING,
        mainValue,
    )
}
