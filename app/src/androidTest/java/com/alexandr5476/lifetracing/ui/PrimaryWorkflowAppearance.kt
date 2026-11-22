package com.alexandr5476.lifetracing.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.Locale

@Suppress("MagicNumber")
internal enum class PrimaryWorkflowAppearance(
    private val language: String,
    private val systemFont: Float,
    val displayMultiplier: Float,
    val preferences: AppearancePreferences,
) {
    RussianMaximum("ru", 1.4f, 1.2f, AppearancePreferences(ThemeMode.DARK, AccentPaletteId.SLATE, 120, 130)),
    EnglishMinimum("en", 1f, 1f, AppearancePreferences(ThemeMode.LIGHT, AccentPaletteId.DEFAULT, 90, 90)),
    ;

    fun configuration(base: Configuration): Configuration =
        Configuration(base).apply {
            setLocale(Locale.forLanguageTag(language))
            fontScale = systemFont
        }

    fun resourcesContext(base: Context): Context =
        base.createConfigurationContext(configuration(base.resources.configuration))

    fun inheritedDensity(baseDensity: Float): Density = Density(baseDensity * displayMultiplier, systemFont)

    @Composable
    fun Content(content: @Composable () -> Unit) {
        val baseContext = LocalContext.current
        val baseConfiguration = LocalConfiguration.current
        val configuration = remember(this, baseConfiguration) { configuration(baseConfiguration) }
        val localized = remember(baseContext, configuration) { baseContext.createConfigurationContext(configuration) }
        CompositionLocalProvider(
            LocalContext provides localized,
            LocalConfiguration provides configuration,
            LocalDensity provides inheritedDensity(LocalDensity.current.density),
        ) {
            LifeTracingAppearance(preferences, content = content)
        }
    }
}

internal fun SemanticsNodeInteraction.renderedTextLayout(): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    return layouts.single()
}

internal fun TextLayoutResult.assertTextFitsLayout() {
    val text = layoutInput.text
    assertFalse("Text exceeds its line limit: $text", multiParagraph.didExceedMaxLines)
    // The String Text semantics action rebuilds a MultiParagraph at the parent's
    // available width. The measured node can be narrower than that paragraph.
    for (line in 0 until lineCount) {
        assertFalse("Text is ellipsized on line $line: $text", isLineEllipsized(line))
        val left = getLineLeft(line)
        val right = getLineRight(line)
        val bottom = getLineBottom(line)
        assertTrue(
            "Text line $line exceeds measured size $size ($left, $right, $bottom): $text",
            left >= -1f && right <= size.width + 1f && bottom <= size.height + 1f,
        )
    }
}

/** Inspect the actual paragraph and viewport after scroll reveals the text node. */
internal fun SemanticsNodeInteraction.assertTextFullyVisible(): TextLayoutResult {
    performScrollTo().assertIsDisplayed()
    val layout = renderedTextLayout()
    val bounds = fetchSemanticsNode().boundsInRoot
    val text = layout.layoutInput.text
    layout.assertTextFitsLayout()
    assertTrue("Viewport clips $text: $bounds, layout ${layout.size}", bounds.width + 1f >= layout.size.width)
    assertTrue("Viewport clips $text: $bounds, layout ${layout.size}", bounds.height + 1f >= layout.size.height)
    return layout
}
