@file:Suppress("MagicNumber", "ReturnCount")

package com.alexandr5476.lifetracing.history

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
    if (offsets.isEmpty()) return HistoricalLocalDateTimeResolution.Nonexistent
    if (offsets.size > 2) error("ZoneRules returned ${offsets.size} valid offsets")
    if (offsets.size == 2 && (selectedOffset == null || selectedOffset !in offsets)) {
        return HistoricalLocalDateTimeResolution.Ambiguous(offsets)
    }
    return HistoricalLocalDateTimeResolution.Resolved(local.toInstant(selectedOffset ?: offsets.single()))
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
