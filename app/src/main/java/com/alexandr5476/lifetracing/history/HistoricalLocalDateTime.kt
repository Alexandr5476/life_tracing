@file:Suppress("MagicNumber", "ReturnCount")

package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.LocalDateTimeResolution
import com.alexandr5476.lifetracing.domain.LocalDateTimeResolutionException
import com.alexandr5476.lifetracing.domain.StrictLocalDateTimeResolver
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

internal data class HistoricalLocalDateTime(
    val text: String,
    val selectedOffset: ZoneOffset?,
    val validOffsets: List<ZoneOffset>,
)

internal sealed interface HistoricalLocalDateTimeResolution {
    data class Resolved(
        val instant: Instant,
    ) : HistoricalLocalDateTimeResolution

    data object Invalid : HistoricalLocalDateTimeResolution

    data object Nonexistent : HistoricalLocalDateTimeResolution

    data class Ambiguous(
        val offsets: List<ZoneOffset>,
    ) : HistoricalLocalDateTimeResolution
}

internal fun Instant.toHistoricalLocalDateTime(zone: ZoneId): HistoricalLocalDateTime {
    val zoned = atZone(zone)
    val offsets = zone.rules.getValidOffsets(zoned.toLocalDateTime())
    return HistoricalLocalDateTime(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(zoned),
        zoned.offset.takeIf { it in offsets },
        offsets.takeIf { it.size == 2 }.orEmpty(),
    )
}

internal fun resolveHistoricalLocalDateTime(
    text: String,
    zone: ZoneId,
    selectedOffset: ZoneOffset?,
): HistoricalLocalDateTimeResolution {
    val local =
        try {
            LocalDateTime.parse(text.trim().replace(' ', 'T'), HISTORICAL_DATE_TIME_FORMATTER)
        } catch (_: DateTimeParseException) {
            return HistoricalLocalDateTimeResolution.Invalid
        }
    val offsets = zone.rules.getValidOffsets(local)
    // Retain the historical editor contract: only a valid overlap choice is meaningful input.
    val overlapChoice = selectedOffset?.takeIf { offsets.size == 2 && it in offsets }
    return when (val result = StrictLocalDateTimeResolver.resolve(local, zone, overlapChoice)) {
        is LocalDateTimeResolution.Resolved -> HistoricalLocalDateTimeResolution.Resolved(result.instant)
        LocalDateTimeResolution.Nonexistent -> HistoricalLocalDateTimeResolution.Nonexistent
        is LocalDateTimeResolution.Ambiguous -> HistoricalLocalDateTimeResolution.Ambiguous(result.validOffsets)
        is LocalDateTimeResolution.Failure -> throw LocalDateTimeResolutionException(result)
    }
}

private val HISTORICAL_DATE_TIME_FORMATTER =
    DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm")
        .optionalStart()
        .appendPattern(":ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 3, true)
        .optionalEnd()
        .optionalEnd()
        .toFormatter()
        .withResolverStyle(ResolverStyle.STRICT)
