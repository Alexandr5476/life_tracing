package com.alexandr5476.lifetracing.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.alexandr5476.lifetracing.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal data class HistoricalTimestamp(
    val localText: String,
    val utcOffset: String,
    val zoneId: String,
)

internal fun historicalTimestampParts(
    instant: Instant,
    zoneId: ZoneId,
    locale: Locale,
    includeDate: Boolean,
): HistoricalTimestamp {
    val zoned = instant.atZone(zoneId)
    val formatter =
        if (includeDate) {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        } else {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        }
    return HistoricalTimestamp(
        formatter.withLocale(locale).format(zoned),
        zoned.offset.id.takeUnless { it == "Z" } ?: "+00:00",
        zoneId.id,
    )
}

@Composable
internal fun historicalTimestamp(
    instant: Instant,
    zoneId: ZoneId,
    includeDate: Boolean,
): String {
    val parts = historicalTimestampParts(instant, zoneId, LocalConfiguration.current.locales[0], includeDate)
    return historicalTimestamp(parts)
}

internal fun historicalTimestampRangeParts(
    startedAt: Instant,
    completedAt: Instant,
    zoneId: ZoneId,
    locale: Locale,
): Pair<HistoricalTimestamp, HistoricalTimestamp> {
    val includeDate = startedAt.atZone(zoneId).toLocalDate() != completedAt.atZone(zoneId).toLocalDate()
    return historicalTimestampParts(startedAt, zoneId, locale, includeDate) to
        historicalTimestampParts(completedAt, zoneId, locale, includeDate)
}

@Composable
internal fun historicalTimestampRange(
    startedAt: Instant,
    completedAt: Instant,
    zoneId: ZoneId,
): String {
    val (start, end) =
        historicalTimestampRangeParts(startedAt, completedAt, zoneId, LocalConfiguration.current.locales[0])
    return stringResource(R.string.daily_time_range, historicalTimestamp(start), historicalTimestamp(end))
}

@Composable
private fun historicalTimestamp(parts: HistoricalTimestamp): String =
    stringResource(R.string.history_timestamp_context, parts.localText, parts.utcOffset, parts.zoneId)
