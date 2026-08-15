@file:Suppress("MagicNumber")

package com.alexandr5476.lifetracing.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class AccentPaletteId {
    DEFAULT,
    SLATE,
    ;

    companion object {
        fun fromStorage(value: String?): AccentPaletteId = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

@Immutable
data class AccentPalette(
    val id: AccentPaletteId,
    val light: AccentColors,
    val dark: AccentColors,
)

@Immutable
data class AccentColors(
    val primary: Color,
    val surfaceTint: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val primaryFixed: Color,
    val primaryFixedDim: Color,
    val onPrimaryFixed: Color,
    val onPrimaryFixedVariant: Color,
)

internal fun AccentPaletteId.toAccentPalette(): AccentPalette =
    when (this) {
        AccentPaletteId.DEFAULT -> defaultAccentPalette
        AccentPaletteId.SLATE -> slateAccentPalette
    }

private val defaultAccentPalette =
    AccentPalette(
        id = AccentPaletteId.DEFAULT,
        light =
            AccentColors(
                primary = Color(0xFF00567C),
                surfaceTint = Color(0xFF1A648C),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFF2A6F97),
                onPrimaryContainer = Color(0xFFD7ECFF),
                inversePrimary = Color(0xFF8FCEFA),
                primaryFixed = Color(0xFFC8E6FF),
                primaryFixedDim = Color(0xFF8FCEFA),
                onPrimaryFixed = Color(0xFF001E2F),
                onPrimaryFixedVariant = Color(0xFF004C6E),
            ),
        dark =
            AccentColors(
                primary = Color(0xFF8FCEFA),
                surfaceTint = Color(0xFF8FCEFA),
                onPrimary = Color(0xFF00344D),
                primaryContainer = Color(0xFF004C6E),
                onPrimaryContainer = Color(0xFFC8E6FF),
                inversePrimary = Color(0xFF00567C),
                primaryFixed = Color(0xFFC8E6FF),
                primaryFixedDim = Color(0xFF8FCEFA),
                onPrimaryFixed = Color(0xFF001E2F),
                onPrimaryFixedVariant = Color(0xFF004C6E),
            ),
    )

private val slateAccentPalette =
    AccentPalette(
        id = AccentPaletteId.SLATE,
        light =
            AccentColors(
                primary = Color(0xFF315F7C),
                surfaceTint = Color(0xFF315F7C),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFCFE5F7),
                onPrimaryContainer = Color(0xFF163A52),
                inversePrimary = Color(0xFF9DCBEB),
                primaryFixed = Color(0xFFCFE5F7),
                primaryFixedDim = Color(0xFF9DCBEB),
                onPrimaryFixed = Color(0xFF001E2F),
                onPrimaryFixedVariant = Color(0xFF254B63),
            ),
        dark =
            AccentColors(
                primary = Color(0xFF9DCBEB),
                surfaceTint = Color(0xFF9DCBEB),
                onPrimary = Color(0xFF00344C),
                primaryContainer = Color(0xFF254B63),
                onPrimaryContainer = Color(0xFFCFE5F7),
                inversePrimary = Color(0xFF315F7C),
                primaryFixed = Color(0xFFCFE5F7),
                primaryFixedDim = Color(0xFF9DCBEB),
                onPrimaryFixed = Color(0xFF001E2F),
                onPrimaryFixedVariant = Color(0xFF254B63),
            ),
    )
