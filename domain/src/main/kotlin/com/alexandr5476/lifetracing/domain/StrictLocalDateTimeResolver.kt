package com.alexandr5476.lifetracing.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

sealed interface LocalDateTimeResolution {
    data class Resolved(
        val instant: Instant,
        val offset: ZoneOffset,
    ) : LocalDateTimeResolution

    sealed interface Failure : LocalDateTimeResolution

    data object Nonexistent : Failure

    data class Ambiguous(
        val validOffsets: List<ZoneOffset>,
    ) : Failure

    data class InvalidOffset(
        val selectedOffset: ZoneOffset,
        val validOffsets: List<ZoneOffset>,
    ) : Failure

    data class UnsupportedOffsets(
        val validOffsets: List<ZoneOffset>,
    ) : Failure
}

class LocalDateTimeResolutionException(
    val failure: LocalDateTimeResolution.Failure,
) : IllegalArgumentException("Local date-time resolution failed: $failure")

/** Resolves newly supplied local input; persisted absolute Instants do not pass through this policy. */
object StrictLocalDateTimeResolver {
    fun resolve(
        localDateTime: LocalDateTime,
        zoneId: ZoneId,
        selectedOffset: ZoneOffset? = null,
    ): LocalDateTimeResolution {
        val offsets = zoneId.rules.getValidOffsets(localDateTime).toList()
        return when {
            offsets.isEmpty() -> LocalDateTimeResolution.Nonexistent
            offsets.size > MAX_SUPPORTED_OFFSETS -> LocalDateTimeResolution.UnsupportedOffsets(offsets)
            selectedOffset != null && selectedOffset !in offsets ->
                LocalDateTimeResolution.InvalidOffset(selectedOffset, offsets)
            offsets.size == MAX_SUPPORTED_OFFSETS && selectedOffset == null ->
                LocalDateTimeResolution.Ambiguous(offsets)
            else -> {
                val offset = selectedOffset ?: offsets.single()
                LocalDateTimeResolution.Resolved(localDateTime.toInstant(offset), offset)
            }
        }
    }

    fun requireInstant(
        localDateTime: LocalDateTime,
        zoneId: ZoneId,
        selectedOffset: ZoneOffset? = null,
    ): Instant =
        when (val result = resolve(localDateTime, zoneId, selectedOffset)) {
            is LocalDateTimeResolution.Resolved -> result.instant
            is LocalDateTimeResolution.Failure -> throw LocalDateTimeResolutionException(result)
        }

    private const val MAX_SUPPORTED_OFFSETS = 2
}
