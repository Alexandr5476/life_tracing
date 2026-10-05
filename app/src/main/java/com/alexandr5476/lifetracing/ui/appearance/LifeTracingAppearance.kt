package com.alexandr5476.lifetracing.ui.appearance

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme

internal fun AppearancePreferences.scaledDensity(parentDensity: Density): Density =
    Density(
        density = parentDensity.density * (interfaceScalePercent.toFloat() / AppearanceScalePolicy.DEFAULT_PERCENT),
        fontScale = parentDensity.fontScale * (textScalePercent.toFloat() / AppearanceScalePolicy.DEFAULT_PERCENT),
    )

/** Called once at the app root, outside the provider so every recomposition reads the system parent. */
@Composable
@Suppress("FunctionNaming")
internal fun LifeTracingAppearance(
    appearance: AppearancePreferences,
    systemIsDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val density = appearance.scaledDensity(LocalDensity.current)
    CompositionLocalProvider(LocalDensity provides density) {
        LifeTracingTheme(
            themeMode = appearance.themeMode,
            accentPaletteId = appearance.accentPaletteId,
            systemIsDark = systemIsDark,
            content = content,
        )
    }
}
