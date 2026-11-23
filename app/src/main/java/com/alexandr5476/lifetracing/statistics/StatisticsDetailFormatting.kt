package com.alexandr5476.lifetracing.statistics

import com.alexandr5476.lifetracing.domain.CountRatio
import com.alexandr5476.lifetracing.domain.ExactValue
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal const val DETAIL_NUMBER_STORAGE_SCALE = 3
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_MINUTE = 60L
private const val RATIO_DISPLAY_SCALE = 4
private const val MAX_PERCENT_FRACTION_DIGITS = 1

internal fun statisticsScaledInteger(
    value: BigInteger,
    locale: Locale,
): String = statisticsDecimal(BigDecimal(value, DETAIL_NUMBER_STORAGE_SCALE).stripTrailingZeros(), locale)

internal fun statisticsScaledExact(
    value: ExactValue,
    precision: Int?,
    locale: Locale,
): String {
    val digits = precision?.coerceIn(0, DETAIL_NUMBER_STORAGE_SCALE) ?: DETAIL_NUMBER_STORAGE_SCALE
    val divisor = BigDecimal(value.denominator).movePointRight(DETAIL_NUMBER_STORAGE_SCALE)
    val decimal = BigDecimal(value.numerator).divide(divisor, digits, RoundingMode.HALF_UP)
    val formatted = statisticsDecimal(if (precision == null) decimal.stripTrailingZeros() else decimal, locale)
    return if (decimal.signum() == 0 && value.numerator.signum() < 0) {
        DecimalFormatSymbols.getInstance(locale).minusSign + formatted
    } else {
        formatted
    }
}

internal fun statisticsExact(
    value: ExactValue,
    locale: Locale,
): String =
    statisticsDecimal(
        BigDecimal(value.numerator)
            .divide(BigDecimal(value.denominator), DETAIL_NUMBER_STORAGE_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros(),
        locale,
    )

private fun statisticsDecimal(
    value: BigDecimal,
    locale: Locale,
): String =
    NumberFormat
        .getNumberInstance(locale)
        .apply {
            maximumFractionDigits = value.scale().coerceAtLeast(0)
            minimumFractionDigits = maximumFractionDigits
        }.format(value)

internal fun statisticsPercent(
    value: CountRatio,
    locale: Locale,
): String? =
    value.exactValue?.let {
        val decimal =
            BigDecimal(
                it.numerator,
            ).divide(BigDecimal(it.denominator), RATIO_DISPLAY_SCALE, RoundingMode.HALF_UP)
        NumberFormat
            .getPercentInstance(
                locale,
            ).apply { maximumFractionDigits = MAX_PERCENT_FRACTION_DIGITS }
            .format(decimal)
    }

internal fun statisticsDuration(
    value: Duration,
    locale: Locale,
): String {
    val seconds = value.seconds
    val clock =
        String.format(
            locale,
            "%d:%02d:%02d",
            seconds / SECONDS_PER_HOUR,
            seconds / SECONDS_PER_MINUTE % SECONDS_PER_MINUTE,
            seconds % SECONDS_PER_MINUTE,
        )
    val fraction = BigDecimal.valueOf(value.nano.toLong(), NANO_DECIMAL_SCALE).stripTrailingZeros()
    return if (value.nano == 0) clock else clock + statisticsDecimal(fraction, locale).drop(1)
}

internal fun statisticsCount(
    value: Long,
    locale: Locale,
): String = NumberFormat.getIntegerInstance(locale).format(value)

internal fun statisticsPeriodDate(
    value: LocalDate,
    locale: Locale,
): String =
    if (value.year <= 0) {
        // The localized medium pattern can omit the era. Keep the signed ISO year unambiguous.
        value.toString()
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(value)
    }

private const val NANO_DECIMAL_SCALE = 9

internal fun statisticsAverageDuration(
    value: ExactValue,
    locale: Locale,
): String {
    val milliseconds = BigDecimal(value.numerator).divide(BigDecimal(value.denominator), 0, RoundingMode.HALF_UP)
    return statisticsDuration(Duration.ofMillis(milliseconds.longValueExact()), locale)
}
