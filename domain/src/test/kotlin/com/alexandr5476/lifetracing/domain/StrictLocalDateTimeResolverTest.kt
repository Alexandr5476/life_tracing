package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class StrictLocalDateTimeResolverTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val overlap = LocalDateTime.parse("2026-10-25T02:30")

    @Test
    fun `gap is nonexistent even with a supplied offset`() {
        assertEquals(
            LocalDateTimeResolution.Nonexistent,
            StrictLocalDateTimeResolver.resolve(LocalDateTime.parse("2026-03-29T02:30"), berlin),
        )
        assertEquals(
            LocalDateTimeResolution.Nonexistent,
            StrictLocalDateTimeResolver.resolve(LocalDateTime.parse("2026-03-29T02:30"), berlin, ZoneOffset.ofHours(1)),
        )
    }

    @Test
    fun `unselected overlap reports both offsets and command conversion rejects it`() {
        val expected = LocalDateTimeResolution.Ambiguous(listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)))
        assertEquals(expected, StrictLocalDateTimeResolver.resolve(overlap, berlin))
        val error =
            assertThrows(LocalDateTimeResolutionException::class.java) {
                PlanSchedule.ExactDay(overlap, berlin).toTarget()
            }
        assertEquals(expected, error.failure)
    }

    @Test
    fun `earlier overlap selection resolves the exact chosen instant`() {
        assertEquals(
            PlanTarget.ExactDay(Instant.parse("2026-10-25T00:30:00Z"), berlin),
            PlanSchedule.ExactDay(overlap, berlin, ZoneOffset.ofHours(2)).toTarget(),
        )
    }

    @Test
    fun `later overlap selection resolves the exact chosen instant`() {
        assertEquals(
            PlanTarget.ExactDay(Instant.parse("2026-10-25T01:30:00Z"), berlin),
            PlanSchedule.ExactDay(overlap, berlin, ZoneOffset.ofHours(1)).toTarget(),
        )
    }

    @Test
    fun `invalid overlap selection exposes valid alternatives`() {
        val invalid = ZoneOffset.ofHours(3)
        assertEquals(
            LocalDateTimeResolution.InvalidOffset(invalid, listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1))),
            StrictLocalDateTimeResolver.resolve(overlap, berlin, invalid),
        )
        assertThrows(LocalDateTimeResolutionException::class.java) {
            PlanSchedule.ExactDay(overlap, berlin, invalid).toTarget()
        }
    }

    @Test
    fun `ordinary tuple uses unique offset but rejects an obsolete explicit choice`() {
        val local = LocalDateTime.parse("2026-10-25T03:30")
        assertEquals(
            PlanTarget.ExactDay(Instant.parse("2026-10-25T02:30:00Z"), berlin),
            PlanSchedule.ExactDay(local, berlin).toTarget(),
        )
        assertEquals(
            PlanSchedule.ExactDay(local, berlin).toTarget(),
            PlanSchedule.ExactDay(local, berlin, ZoneOffset.ofHours(1)).toTarget(),
        )
        val error =
            assertThrows(LocalDateTimeResolutionException::class.java) {
                PlanSchedule.ExactDay(local, berlin, ZoneOffset.ofHours(2)).toTarget()
            }
        assertEquals(
            LocalDateTimeResolution.InvalidOffset(ZoneOffset.ofHours(2), listOf(ZoneOffset.ofHours(1))),
            error.failure,
        )
    }
}
