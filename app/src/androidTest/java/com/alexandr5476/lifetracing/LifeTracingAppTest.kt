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
    fun placeholder_is_displayed() {
        composeTestRule.setContent { LifeTracingApp() }

        composeTestRule.onNodeWithText("LifeTracing").assertIsDisplayed()
    }
}
