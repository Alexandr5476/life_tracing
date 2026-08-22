package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

class StatisticsModelsTest {
    @Test
    fun periodsResolveInclusiveCalendarRanges() {
        val monday = LocalDate.of(2026, 8, 17)
        assertEquals(StatisticsDateRange(monday, monday), StatisticsPeriod.Day(monday).dateRangeOrNull())
        assertEquals(StatisticsDateRange(monday, monday.plusDays(6)), StatisticsPeriod.Week(monday).dateRangeOrNull())
        assertEquals(
            StatisticsDateRange(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)),
            StatisticsPeriod.Month(YearMonth.of(2024, 2)).dateRangeOrNull(),
        )
        assertEquals(
            StatisticsDateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
            StatisticsPeriod.Year(Year.of(2024)).dateRangeOrNull(),
        )
        assertEquals(366, StatisticsPeriod.Year(Year.of(2024)).dateRangeOrNull()?.calendarDayCount)
        assertNull(StatisticsPeriod.AllTime.dateRangeOrNull())
    }

    @Test
    fun invalidWeekAndCustomRangeAreRejected() {
        val tuesday = LocalDate.of(2026, 8, 18)
        assertEquals(DayOfWeek.TUESDAY, tuesday.dayOfWeek)
        assertThrows(IllegalArgumentException::class.java) { StatisticsPeriod.Week(tuesday) }
        assertThrows(IllegalArgumentException::class.java) {
            StatisticsPeriod.Custom(tuesday, tuesday.minusDays(1))
        }
    }

    @Test
    fun durationDistributionKeepsNoLiveOutAndPreservesExactMidpoints() {
        val empty = StatisticsDistributionCalculator.durations(emptyList())
        assertEquals(0, empty.sampleCount)
        assertEquals(Duration.ZERO, empty.total)
        assertNull(empty.averageMilliseconds)
        assertNull(empty.medianMilliseconds)
        assertNull(empty.minimum)

        val odd = StatisticsDistributionCalculator.durations(listOf(100_000, 10_000, 20_000))
        assertEquals(Duration.ofMillis(130_000), odd.total)
        assertEquals(ExactValue.of(130_000, 3), odd.averageMilliseconds)
        assertEquals(ExactValue.of(20_000, 1), odd.medianMilliseconds)

        val even = StatisticsDistributionCalculator.durations(listOf(100_000, 30_000, 10_000, 20_000))
        assertEquals(ExactValue.of(25_000, 1), even.medianMilliseconds)
        assertThrows(IllegalArgumentException::class.java) {
            StatisticsDistributionCalculator.durations(listOf(-1))
        }
    }

    @Test
    fun numberDistributionAndRatiosRemainExact() {
        val values = StatisticsDistributionCalculator.numbers(listOf(10, 20, 30, 101))
        assertEquals(BigInteger.valueOf(161), values.totalScaled)
        assertEquals(ExactValue.of(161, 4), values.averageScaled)
        assertEquals(ExactValue.of(25, 1), values.medianScaled)
        assertEquals(ExactValue.of(3, 4), CountRatio(3, 4).exactValue)
        assertNull(CountRatio(0, 0).exactValue)
        assertThrows(IllegalArgumentException::class.java) { CountRatio(2, 1) }
    }
}
