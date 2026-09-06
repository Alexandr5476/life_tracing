package com.alexandr5476.lifetracing.launcher

import java.math.BigDecimal
import java.math.RoundingMode

private const val STORAGE_SCALE = 3

internal fun formatLauncherNumber(
    scaledValue: Long?,
    displayPrecision: Int?,
): String =
    scaledValue
        ?.let { value ->
            val precision = displayPrecision?.coerceIn(0, STORAGE_SCALE) ?: STORAGE_SCALE
            BigDecimal
                .valueOf(value, STORAGE_SCALE)
                .let { number ->
                    runCatching { number.setScale(precision, RoundingMode.UNNECESSARY) }.getOrDefault(number)
                }.stripTrailingZeros()
                .toPlainString()
        }.orEmpty()

/** Parses the domain's fixed 1/1000 storage convention without introducing a UI-only scale. */
@Suppress("ReturnCount")
internal fun parseLauncherNumber(
    text: String,
    displayPrecision: Int?,
): Long? {
    val normalized = text.trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    val precision = displayPrecision?.coerceIn(0, STORAGE_SCALE) ?: STORAGE_SCALE
    return runCatching {
        val number = BigDecimal(normalized)
        if (number.scale().coerceAtLeast(0) > precision) return null
        number.movePointRight(STORAGE_SCALE).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    }.getOrNull()
}
