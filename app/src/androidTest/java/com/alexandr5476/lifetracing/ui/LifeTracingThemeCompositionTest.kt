package com.alexandr5476.lifetracing.ui

import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import com.alexandr5476.lifetracing.ui.theme.toAccentPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@Suppress("MagicNumber")
class LifeTracingThemeCompositionTest {
    @get:Rule
    val compose = createComposeRule()

    private data class ConfigurationCase(
        val mode: ThemeMode,
        val systemDark: Boolean,
        val expectedDark: Boolean,
    )

    @Test
    fun productionSchemeFollowsAndroidAndExplicitModeWithBothAccents() {
        val cases =
            listOf(
                ConfigurationCase(ThemeMode.SYSTEM, systemDark = false, expectedDark = false),
                ConfigurationCase(ThemeMode.SYSTEM, systemDark = true, expectedDark = true),
                ConfigurationCase(ThemeMode.LIGHT, systemDark = true, expectedDark = false),
                ConfigurationCase(ThemeMode.DARK, systemDark = false, expectedDark = true),
            )
        val configurationCase = mutableStateOf(cases.first())
        val accent = mutableStateOf(AccentPaletteId.DEFAULT)
        var verify: (() -> Unit)? = null
        val buttonText = mutableMapOf<String, Color>()
        var clicks = 0
        compose.setContent {
            val current = configurationCase.value
            val system =
                Configuration(LocalConfiguration.current).apply {
                    val nightMode =
                        if (current.systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                }
            CompositionLocalProvider(LocalConfiguration provides system) {
                val palette = accent.value
                LifeTracingTheme(current.mode, palette) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect { verify = { assertScheme(scheme, current.expectedDark, palette) } }
                    LifeTracingPrimaryButton(onClick = { clicks++ }) {
                        val color = LocalContentColor.current
                        SideEffect { buttonText["primary"] = color }
                        Text("Primary")
                    }
                    LifeTracingSecondaryButton(onClick = { clicks++ }) {
                        val color = LocalContentColor.current
                        SideEffect { buttonText["secondary"] = color }
                        Text("Secondary")
                    }
                    LifeTracingChoiceButton(selected = true, onClick = { clicks++ }) {
                        val color = LocalContentColor.current
                        SideEffect { buttonText["selected"] = color }
                        Text("Selected")
                    }
                    LifeTracingChoiceButton(
                        selected = true,
                        enabled = false,
                        modifier = Modifier.testTag("disabled-choice"),
                        onClick = { clicks++ },
                    ) {
                        val color = LocalContentColor.current
                        SideEffect { buttonText["disabled"] = color }
                        Text("Disabled")
                    }
                }
            }
        }
        for (case in cases) {
            for (palette in AccentPaletteId.entries) {
                compose.runOnIdle {
                    configurationCase.value = case
                    accent.value = palette
                }
                compose.onNodeWithTag("disabled-choice").assertIsNotEnabled()
                compose.runOnIdle {
                    requireNotNull(verify).invoke()
                    val colors = palette.toAccentPalette().let { if (case.expectedDark) it.dark else it.light }
                    val onSurfaceVariant = if (case.expectedDark) Color(0xFFC0C7CF) else Color(0xFF40484E)
                    assertEquals(colors.onPrimary, buttonText.getValue("primary"))
                    assertEquals(colors.primary, buttonText.getValue("secondary"))
                    assertEquals(colors.onPrimary, buttonText.getValue("selected"))
                    // Material's filled button uses the secondary surface foreground for disabled labels.
                    assertEquals(onSurfaceVariant.copy(alpha = 0.38f), buttonText.getValue("disabled"))
                    assertEquals(0, clicks)
                }
            }
        }
    }

    private fun assertScheme(
        scheme: ColorScheme,
        dark: Boolean,
        palette: AccentPaletteId,
    ) {
        val colors = palette.toAccentPalette().let { if (dark) it.dark else it.light }
        assertEquals(colors.primary, scheme.primary)
        assertEquals(colors.onPrimary, scheme.onPrimary)
        assertEquals(colors.primaryContainer, scheme.primaryContainer)
        assertEquals(colors.onPrimaryContainer, scheme.onPrimaryContainer)
        assertEquals(colors.surfaceTint, scheme.surfaceTint)
        assertEquals(colors.inversePrimary, scheme.inversePrimary)
        assertEquals(colors.primaryFixed, scheme.primaryFixed)
        assertEquals(colors.primaryFixedDim, scheme.primaryFixedDim)
        assertEquals(colors.onPrimaryFixed, scheme.onPrimaryFixed)
        assertEquals(colors.onPrimaryFixedVariant, scheme.onPrimaryFixedVariant)
        assertEquals(if (dark) Color(0xFF101418) else Color(0xFFF7F9FF), scheme.background)
        assertEquals(scheme.background, scheme.surface)
        assertEquals(if (dark) Color(0xFF899097) else Color(0xFF71787F), scheme.outline)
        assertEquals(if (dark) Color(0xFF40484E) else Color(0xFFC0C7CF), scheme.outlineVariant)

        assertTextContrast("primary action", scheme.onPrimary, scheme.primary)
        assertTextContrast("active content", scheme.onPrimaryContainer, scheme.primaryContainer)
        assertTextContrast("secondary action", scheme.primary, scheme.surfaceContainerLow)
        assertTextContrast("selected filter", scheme.onSecondaryContainer, scheme.secondaryContainer)
        assertTextContrast("outlined field error", scheme.error, scheme.surfaceContainerLow)
        assertTextContrast("Archive error", scheme.error, scheme.background)
        assertTextContrast("error feedback", scheme.onErrorContainer, scheme.errorContainer)
        assertTextContrast("coordination feedback", scheme.onTertiaryContainer, scheme.tertiaryContainer)
        val surfaces =
            listOf(
                scheme.surface,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
            )
        for (surface in surfaces) {
            assertTextContrast("standard text", scheme.onSurface, surface)
            assertTextContrast("secondary text", scheme.onSurfaceVariant, surface)
        }
    }

    private fun assertTextContrast(
        role: String,
        foreground: Color,
        background: Color,
    ) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        assertTrue("$role contrast was $ratio: $foreground over $background", ratio >= 4.5f)
    }
}
