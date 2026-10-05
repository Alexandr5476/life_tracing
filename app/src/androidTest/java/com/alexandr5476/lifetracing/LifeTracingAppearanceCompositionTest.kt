package com.alexandr5476.lifetracing

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LifeTracingAppearanceCompositionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun inherited_system_components_survive_recomposition_and_preference_changes() {
        val parent = mutableStateOf(Density(density = 2.75f, fontScale = 1.4f))
        val appearance = mutableStateOf(AppearancePreferences(interfaceScalePercent = 115, textScalePercent = 130))
        val systemIsDark = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides parent.value) {
                LifeTracingAppearance(appearance.value, systemIsDark = systemIsDark.value) {
                    Box(Modifier.testTag("density-probe")) { Text("Scale") }
                }
            }
        }
        assertDensity(2.75f * 1.15f, 1.4f * 1.3f)
        repeat(3) {
            compose.runOnIdle { systemIsDark.value = !systemIsDark.value }
            assertDensity(2.75f * 1.15f, 1.4f * 1.3f)
        }
        compose.runOnIdle { appearance.value = AppearancePreferences() }
        assertDensity(2.75f, 1.4f)
        compose.runOnIdle { parent.value = Density(density = 3.25f, fontScale = 1.65f) }
        assertDensity(3.25f, 1.65f)
        compose.runOnIdle {
            appearance.value = AppearancePreferences(interfaceScalePercent = 90, textScalePercent = 125)
        }
        assertDensity(3.25f * 0.9f, 1.65f * 1.25f)
    }

    private fun assertDensity(
        density: Float,
        fontScale: Float,
    ) {
        val effective =
            compose
                .onNodeWithTag("density-probe")
                .fetchSemanticsNode()
                .layoutInfo
                .density
        assertEquals(density, effective.density, 0f)
        assertEquals(fontScale, effective.fontScale, 0f)
    }
}
