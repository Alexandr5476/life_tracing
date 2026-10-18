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
    fun orderedDurationsConsumeOnePassAndMatchExactReferenceDistributions() {
        val fixtures =
            listOf(
                emptyList(),
                listOf(0L),
                listOf(17L),
                listOf(0L, 0L, 3L),
                listOf(0L, 2L, 2L, 9L),
                listOf(1L, 20L, 300L, 4_000L, 50_000L),
                listOf(Long.MAX_VALUE),
                listOf(1L, Long.MAX_VALUE - 1),
            )
        fixtures.forEach { values ->
            val distribution =
                StatisticsDistributionCalculator.orderedDurations(values.size.toLong(), onePass(values))
            assertEquals(StatisticsDistributionCalculator.durations(values.reversed()), distribution)
        }
        val even = StatisticsDistributionCalculator.orderedDurations(4, onePass(listOf(0L, 2L, 3L, 9L)))
        assertEquals(Duration.ofMillis(14), even.total)
        assertEquals(ExactValue.of(7, 2), even.averageMilliseconds)
        assertEquals(ExactValue.of(5, 2), even.medianMilliseconds)
        assertEquals(Duration.ZERO, even.minimum)
        assertEquals(Duration.ofMillis(9), even.maximum)
        val boundary =
            StatisticsDistributionCalculator.orderedDurations(2, onePass(listOf(1L, Long.MAX_VALUE - 1)))
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), boundary.total)
        assertEquals(ExactValue.of(Long.MAX_VALUE, 2), boundary.medianMilliseconds)
    }

    @Test
    fun orderedDurationsRejectInvalidCountsNegativeAndUnorderedSamples() {
        listOf(-1L to emptyList(), 0L to listOf(1L), 2L to listOf(1L), 1L to listOf(1L, 2L))
            .forEach { (count, values) ->
                assertThrows(IllegalArgumentException::class.java) {
                    StatisticsDistributionCalculator.orderedDurations(count, onePass(values))
                }
            }
        for (values in listOf(listOf(-1L), listOf(2L, 1L))) {
            assertThrows(IllegalArgumentException::class.java) {
                StatisticsDistributionCalculator.orderedDurations(values.size.toLong(), onePass(values))
            }
        }
    }

    @Test
    fun orderedDurationsAccumulateBeforeNarrowingAndRejectPositiveTotalOverflow() {
        for (values in listOf(listOf(1L, Long.MAX_VALUE), listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE))) {
            // The second fixture overflows a Long midpoint sum; total overflow must still reject it.
            assertThrows(ArithmeticException::class.java) {
                StatisticsDistributionCalculator.orderedDurations(2, onePass(values))
            }
        }
    }

    @Test
    fun orderedDurationsAcceptGeneratedHistoryWithoutRandomAccessOrRepeatedTraversal() {
        var next = 0L
        val samples =
            object : Iterator<Long> {
                override fun hasNext() = next < 100_000

                override fun next(): Long {
                    if (!hasNext()) throw NoSuchElementException()
                    return next++
                }
            }
        val distribution = StatisticsDistributionCalculator.orderedDurations(100_000, samples)
        assertEquals(100_000L, next)
        assertEquals(100_000L, distribution.sampleCount)
        assertEquals(Duration.ofMillis(4_999_950_000), distribution.total)
        assertEquals(ExactValue.of(99_999, 2), distribution.medianMilliseconds)
    }

    private fun onePass(values: List<Long>): Iterator<Long> =
        sequence {
            values.forEach { yield(it) }
        }.constrainOnce().iterator()

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
