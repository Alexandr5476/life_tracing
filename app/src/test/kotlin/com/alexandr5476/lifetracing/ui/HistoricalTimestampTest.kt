package com.alexandr5476.lifetracing.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

class HistoricalTimestampTest {
    @Test
    fun repeatedClocksKeepDistinctOffsetsAcrossDeviceZoneChanges() {
        val previousZone = TimeZone.getDefault()
        try {
            val berlin = ZoneId.of("Europe/Berlin")
            for (deviceZone in listOf("Asia/Tokyo", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(deviceZone))
                for (locale in listOf(Locale.US, Locale.forLanguageTag("ru"))) {
                    val first = historicalTimestampParts(Instant.parse("2026-10-25T00:30:00Z"), berlin, locale, false)
                    val second = historicalTimestampParts(Instant.parse("2026-10-25T01:30:00Z"), berlin, locale, false)
                    assertEquals(first.localText, second.localText)
                    assertEquals("+02:00", first.utcOffset)
                    assertEquals("+01:00", second.utcOffset)
                    assertEquals("Europe/Berlin", first.zoneId)
                    assertNotEquals(first, second)
                }
            }
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun springTransitionAndCrossMidnightEndpointsUseTheirOwnLocalDateAndOffset() {
        val zone = ZoneId.of("Europe/Berlin")
        val before =
            historicalTimestampParts(Instant.parse("2026-03-29T00:30:00Z"), zone, Locale.forLanguageTag("ru"), true)
        val after =
            historicalTimestampParts(Instant.parse("2026-03-29T01:30:00Z"), zone, Locale.forLanguageTag("ru"), true)
        assertEquals("+01:00", before.utcOffset)
        assertEquals("+02:00", after.utcOffset)
        assertEquals(true, before.localText.contains("1:30"))
        assertEquals(true, after.localText.contains("3:30"))
        val start = historicalTimestampParts(Instant.parse("2026-10-24T21:30:00Z"), zone, Locale.US, true)
        val end = historicalTimestampParts(Instant.parse("2026-10-25T01:30:00Z"), zone, Locale.US, true)
        assertEquals(true, start.localText.contains("Oct 24"))
        assertEquals(true, end.localText.contains("Oct 25"))
        assertEquals("+00:00", historicalTimestampParts(Instant.EPOCH, ZoneId.of("UTC"), Locale.US, false).utcOffset)
    }
}
