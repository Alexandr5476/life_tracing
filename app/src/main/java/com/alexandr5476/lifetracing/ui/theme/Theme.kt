@file:Suppress("MagicNumber")

package com.alexandr5476.lifetracing.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.appearance.resolveDarkTheme

@Composable
@Suppress("FunctionNaming")
fun LifeTracingTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentPaletteId: AccentPaletteId = AccentPaletteId.DEFAULT,
    systemIsDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val accentPalette = remember(accentPaletteId) { accentPaletteId.toAccentPalette() }
    val colorScheme =
        if (resolveDarkTheme(themeMode, systemIsDark)) {
            darkLifeTracingColorScheme(accentPalette)
        } else {
            lightLifeTracingColorScheme(accentPalette)
        }

    CompositionLocalProvider(LocalLifeTracingSpacing provides LifeTracingSpacing()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LifeTracingTypography,
            shapes = LifeTracingShapes,
            content = content,
        )
    }
}

private fun lightLifeTracingColorScheme(accent: AccentPalette): ColorScheme =
    lightColorScheme(
        primary = accent.light.primary,
        onPrimary = accent.light.onPrimary,
        primaryContainer = accent.light.primaryContainer,
        onPrimaryContainer = accent.light.onPrimaryContainer,
        inversePrimary = accent.light.inversePrimary,
        secondary = Color(0xFF4B6172),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCBE3F7),
        onSecondaryContainer = Color(0xFF4F6576),
        tertiary = Color(0xFF714800),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF8E5F15),
        onTertiaryContainer = Color(0xFFFFE5C8),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        background = Color(0xFFF7F9FF),
        onBackground = Color(0xFF181C20),
        surface = Color(0xFFF7F9FF),
        onSurface = Color(0xFF181C20),
        surfaceVariant = Color(0xFFE0E3E8),
        onSurfaceVariant = Color(0xFF40484E),
        surfaceTint = accent.light.primary,
        inverseSurface = Color(0xFF2D3135),
        inverseOnSurface = Color(0xFFEEF1F6),
        outline = Color(0xFF71787F),
        outlineVariant = Color(0xFFC0C7CF),
        primaryFixed = accent.light.primaryFixed,
        primaryFixedDim = accent.light.primaryFixedDim,
        onPrimaryFixed = accent.light.onPrimaryFixed,
        onPrimaryFixedVariant = accent.light.onPrimaryFixedVariant,
        secondaryFixed = Color(0xFFCEE5F9),
        secondaryFixedDim = Color(0xFFB2C9DD),
        onSecondaryFixed = Color(0xFF051E2C),
        onSecondaryFixedVariant = Color(0xFF334959),
        tertiaryFixed = Color(0xFFFFDDB5),
        tertiaryFixedDim = Color(0xFFF8BB6A),
        onTertiaryFixed = Color(0xFF2A1800),
        onTertiaryFixedVariant = Color(0xFF643F00),
        surfaceDim = Color(0xFFD7DADF),
        surfaceBright = Color(0xFFF7F9FF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF1F4F9),
        surfaceContainer = Color(0xFFEBEEF3),
        surfaceContainerHigh = Color(0xFFE5E8EE),
        surfaceContainerHighest = Color(0xFFE0E3E8),
    )

private fun darkLifeTracingColorScheme(accent: AccentPalette): ColorScheme =
    darkColorScheme(
        primary = accent.dark.primary,
        onPrimary = accent.dark.onPrimary,
        primaryContainer = accent.dark.primaryContainer,
        onPrimaryContainer = accent.dark.onPrimaryContainer,
        inversePrimary = accent.dark.inversePrimary,
        secondary = Color(0xFFB2C9DD),
        onSecondary = Color(0xFF1C3343),
        secondaryContainer = Color(0xFF334959),
        onSecondaryContainer = Color(0xFFCEE5F9),
        tertiary = Color(0xFFF8BB6A),
        onTertiary = Color(0xFF442B00),
        tertiaryContainer = Color(0xFF643F00),
        onTertiaryContainer = Color(0xFFFFDDB5),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF101418),
        onBackground = Color(0xFFE0E3E8),
        surface = Color(0xFF101418),
        onSurface = Color(0xFFE0E3E8),
        surfaceVariant = Color(0xFF40484E),
        onSurfaceVariant = Color(0xFFC0C7CF),
        surfaceTint = accent.dark.primary,
        inverseSurface = Color(0xFFE0E3E8),
        inverseOnSurface = Color(0xFF2D3135),
        outline = Color(0xFF899097),
        outlineVariant = Color(0xFF40484E),
        primaryFixed = accent.dark.primaryFixed,
        primaryFixedDim = accent.dark.primaryFixedDim,
        onPrimaryFixed = accent.dark.onPrimaryFixed,
        onPrimaryFixedVariant = accent.dark.onPrimaryFixedVariant,
        secondaryFixed = Color(0xFFCEE5F9),
        secondaryFixedDim = Color(0xFFB2C9DD),
        onSecondaryFixed = Color(0xFF051E2C),
        onSecondaryFixedVariant = Color(0xFF334959),
        tertiaryFixed = Color(0xFFFFDDB5),
        tertiaryFixedDim = Color(0xFFF8BB6A),
        onTertiaryFixed = Color(0xFF2A1800),
        onTertiaryFixedVariant = Color(0xFF643F00),
        surfaceDim = Color(0xFF101418),
        surfaceBright = Color(0xFF363A3E),
        surfaceContainerLowest = Color(0xFF0B0F12),
        surfaceContainerLow = Color(0xFF191D21),
        surfaceContainer = Color(0xFF1D2125),
        surfaceContainerHigh = Color(0xFF272B2F),
        surfaceContainerHighest = Color(0xFF32363A),
    )
