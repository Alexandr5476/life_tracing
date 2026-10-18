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
    fun durationSummaryPreservesExactReferenceDistributions() {
        val fixtures =
            listOf(
                emptyList(),
                listOf(0L),
                listOf(17L),
                listOf(0L, 0L, 3L),
                listOf(0L, 2L, 3L, 9L),
                listOf(1L, 20L, 300L, 4_000L, 50_000L),
                listOf(Long.MAX_VALUE),
                listOf(1L, Long.MAX_VALUE - 1),
            )
        fixtures.forEach { values ->
            val endpoints =
                when {
                    values.isEmpty() -> emptyList()
                    values.size % 2 == 1 -> listOf(values[values.size / 2])
                    else -> listOf(values[values.size / 2 - 1], values[values.size / 2])
                }
            val total = values.fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value) }
            val actual =
                StatisticsDistributionCalculator.durations(
                    values.size.toLong(),
                    total,
                    values.firstOrNull(),
                    values.lastOrNull(),
                    endpoints,
                )
            assertEquals(StatisticsDistributionCalculator.durations(values.reversed()), actual)
        }
        val even = StatisticsDistributionCalculator.durations(4, BigInteger.valueOf(14), 0, 9, listOf(2, 3))
        assertEquals(ExactValue.of(7, 2), even.averageMilliseconds)
        assertEquals(ExactValue.of(5, 2), even.medianMilliseconds)
        assertEquals(Duration.ZERO, even.minimum)
        assertEquals(Duration.ofMillis(9), even.maximum)
        val boundary =
            StatisticsDistributionCalculator.durations(
                2,
                BigInteger.valueOf(Long.MAX_VALUE),
                1,
                Long.MAX_VALUE - 1,
                listOf(1, Long.MAX_VALUE - 1),
            )
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), boundary.total)
        assertEquals(ExactValue.of(Long.MAX_VALUE, 2), boundary.medianMilliseconds)
    }

    @Test
    fun durationSummaryRejectsInvalidCountsAndEndpoints() {
        assertThrows(IllegalArgumentException::class.java) {
            StatisticsDistributionCalculator.durations(-1, BigInteger.ZERO, null, null, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            StatisticsDistributionCalculator.durations(0, BigInteger.ONE, null, null, emptyList())
        }
        for (endpoints in listOf(emptyList(), listOf(1L), listOf(2L, 1L), listOf(0L, 4L), listOf(1L, 2L, 3L))) {
            assertThrows(IllegalArgumentException::class.java) {
                StatisticsDistributionCalculator.durations(2, BigInteger.valueOf(4), 1, 3, endpoints)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            StatisticsDistributionCalculator.durations(1, BigInteger.ONE, null, 1, listOf(1))
        }
    }

    @Test
    fun durationSummaryRejectsNegativeDurationsAndInconsistentTotals() {
        assertThrows(IllegalArgumentException::class.java) {
            StatisticsDistributionCalculator.durations(3, BigInteger.ONE, -1, 2, listOf(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StatisticsDistributionCalculator.durations(3, BigInteger.valueOf(100), 1, 3, listOf(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StatisticsDistributionCalculator.durations(1, BigInteger.valueOf(-1), -1, -1, listOf(-1))
        }
    }

    @Test
    fun durationSummaryRejectsTotalAndMidpointOverflowWithoutNarrowing() {
        val total = BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.valueOf(2)
        assertThrows(ArithmeticException::class.java) {
            StatisticsDistributionCalculator.durations(
                2,
                total,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                listOf(Long.MAX_VALUE, Long.MAX_VALUE),
            )
        }
    }

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
    fun durationTotalsPreserveTheLongBoundaryAndRejectOverflow() {
        assertEquals(Duration.ZERO, StatisticsDistributionCalculator.durations(listOf(0L, 0L)).total)
        assertEquals(
            Duration.ofMillis(Long.MAX_VALUE),
            StatisticsDistributionCalculator.durations(listOf(Long.MAX_VALUE - 1, 1L)).total,
        )
        assertThrows(ArithmeticException::class.java) {
            StatisticsDistributionCalculator.durations(listOf(Long.MAX_VALUE, 1L))
        }
        assertThrows(ArithmeticException::class.java) {
            StatisticsDistributionCalculator.durations(listOf(Long.MAX_VALUE, Long.MAX_VALUE))
        }
    }

    @Test
    fun numberTotalsRemainExactBeyondTheLongRange() {
        for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val distribution = StatisticsDistributionCalculator.numbers(listOf(value, value))
            assertEquals(BigInteger.valueOf(value) * BigInteger.valueOf(2), distribution.totalScaled)
            assertEquals(ExactValue.of(value, 1), distribution.averageScaled)
            assertEquals(ExactValue.of(value, 1), distribution.medianScaled)
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
