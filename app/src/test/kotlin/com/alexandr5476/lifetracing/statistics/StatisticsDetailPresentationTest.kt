package com.alexandr5476.lifetracing.statistics

import com.alexandr5476.lifetracing.domain.CountRatio
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

class StatisticsDetailPresentationTest {
    @Test
    fun overviewPreservesFractionalDurationInThePresentationLocale() {
        val duration = Duration.ofHours(12_345).plusMillis(125)
        assertEquals("12345:00:00.125", statisticsDuration(duration, Locale.US))
        assertEquals("12345:00:00,125", statisticsDuration(duration, Locale.forLanguageTag("ru")))
    }

    @Test
    fun overviewGroupsLargeCountsWithoutRounding() {
        assertEquals("9,223,372,036,854,775,807", statisticsCount(Long.MAX_VALUE, Locale.US))
    }

    @Test
    fun appliedPeriodDateUsesPresentationLocale() {
        assertEquals("Nov 7, 2027", statisticsPeriodDate(LocalDate.of(2027, 11, 7), Locale.US))
        assertEquals("0000-01-02", statisticsPeriodDate(LocalDate.of(0, 1, 2), Locale.US))
    }

    @Test
    fun exactTotalsCrossBothLongBoundariesAndPreserveCancellation() {
        val positive = StatisticsDistributionCalculator.numbers(listOf(Long.MAX_VALUE, Long.MAX_VALUE))
        val negative = StatisticsDistributionCalculator.numbers(listOf(Long.MIN_VALUE, Long.MIN_VALUE))
        val cancelled = StatisticsDistributionCalculator.numbers(listOf(Long.MAX_VALUE, Long.MIN_VALUE, 1))
        assertEquals("18,446,744,073,709,551.614", statisticsScaledInteger(positive.totalScaled, Locale.US))
        assertEquals("-18,446,744,073,709,551.616", statisticsScaledInteger(negative.totalScaled, Locale.US))
        assertEquals("0", statisticsScaledInteger(cancelled.totalScaled, Locale.US))

        val russian = statisticsScaledInteger(negative.totalScaled, Locale.forLanguageTag("ru"))
        assertEquals("-18446744073709551,616", russian.filterNot(Char::isWhitespace))
    }

    @Test
    fun exactRationalValuesKeepSignAndConfiguredPrecision() {
        val distribution = StatisticsDistributionCalculator.numbers(listOf(-2_001, -1_000, 0, 47))
        val average = requireNotNull(distribution.averageScaled)
        val median = requireNotNull(distribution.medianScaled)
        assertEquals("-0.74", statisticsScaledExact(average, 2, Locale.US))
        assertEquals("-0.50", statisticsScaledExact(median, 2, Locale.US))
        assertEquals("-0,50", statisticsScaledExact(median, 2, Locale.forLanguageTag("ru")))
        assertEquals("-0.00", statisticsScaledExact(ExactValue.of(-1, 2), 2, Locale.US))
        assertEquals("0.00", statisticsScaledExact(ExactValue.of(0, 1), 2, Locale.US))
    }

    @Test
    fun unavailableShareAndRecordedZeroStayDistinct() {
        assertNull(statisticsPercent(CountRatio(0, 0), Locale.US))
        assertEquals("0%", statisticsPercent(CountRatio(0, 3), Locale.US))
        assertEquals("33.3%", statisticsPercent(CountRatio(1, 3), Locale.US))
        assertEquals("0:00:00", statisticsDuration(Duration.ZERO, Locale.US))
        assertEquals("0:00:00.001", statisticsDuration(Duration.ofMillis(1), Locale.US))
    }
}
