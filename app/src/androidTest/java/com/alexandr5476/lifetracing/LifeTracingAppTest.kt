package com.alexandr5476.lifetracing

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class LifeTracingAppTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun design_system_preview_is_displayed() {
        composeTestRule.setContent { LifeTracingApp() }

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.design_preview_heading))
            .assertIsDisplayed()
    }
}
