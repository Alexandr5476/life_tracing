package com.alexandr5476.lifetracing

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.alexandr5476.lifetracing.daily.DailyAction
import com.alexandr5476.lifetracing.daily.DailyDateRelation
import com.alexandr5476.lifetracing.daily.DailyLoadState
import com.alexandr5476.lifetracing.daily.DailyPresentationState
import com.alexandr5476.lifetracing.daily.DailyScreen
import com.alexandr5476.lifetracing.domain.DailyRead
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class LifeTracingAppTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun production_shell_displays_daily_root() {
        composeTestRule.setContent { LifeTracingApp() }

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.daily_title))
            .assertIsDisplayed()
    }

    @Test
    fun past_daily_keeps_header_and_dispatches_date_navigation() {
        val actions = mutableListOf<DailyAction>()
        composeTestRule.setContent {
            LifeTracingTheme {
                DailyScreen(
                    state =
                        DailyPresentationState(
                            selectedDate = LocalDate.parse("2026-08-19"),
                            dateRelation = DailyDateRelation.PAST,
                            load = DailyLoadState.Empty(DailyRead(emptyList(), emptyList(), emptyList(), null)),
                        ),
                    onAction = actions::add,
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(composeTestRule.activity.getString(R.string.daily_previous_day))
            .performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_return_today)).performClick()

        assertEquals(listOf(DailyAction.PreviousDay, DailyAction.Today), actions)
    }
}
