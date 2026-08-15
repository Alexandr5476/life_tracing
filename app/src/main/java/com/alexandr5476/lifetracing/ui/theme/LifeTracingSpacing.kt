package com.alexandr5476.lifetracing.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class LifeTracingSpacing(
    val xSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val xLarge: Dp = 24.dp,
    val xxLarge: Dp = 32.dp,
)

internal val LocalLifeTracingSpacing = staticCompositionLocalOf { LifeTracingSpacing() }

val MaterialTheme.spacing: LifeTracingSpacing
    @Composable get() = LocalLifeTracingSpacing.current
