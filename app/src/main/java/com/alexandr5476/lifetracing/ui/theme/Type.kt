package com.alexandr5476.lifetracing.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Inter remains the reference typeface. System sans-serif is used until a reproducible, licensed font source is added.
 */
private val displayTextStyle =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.64).sp,
    )

private val headlineTextStyle =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    )

private val titleTextStyle =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )

private val bodyLargeTextStyle =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    )

private val bodyMediumTextStyle =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    )

private val labelMediumTextStyle =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.24.sp,
    )

private val labelSmallTextStyle =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.11.sp,
    )

val LifeTracingTypography =
    Typography(
        displayLarge = displayTextStyle,
        displayMedium = displayTextStyle,
        displaySmall = displayTextStyle,
        headlineLarge = headlineTextStyle,
        headlineMedium = headlineTextStyle,
        headlineSmall = headlineTextStyle,
        titleLarge = titleTextStyle,
        titleMedium = titleTextStyle,
        titleSmall = titleTextStyle,
        bodyLarge = bodyLargeTextStyle,
        bodyMedium = bodyMediumTextStyle,
        bodySmall = bodyMediumTextStyle,
        labelLarge = labelMediumTextStyle,
        labelMedium = labelMediumTextStyle,
        labelSmall = labelSmallTextStyle,
    )
