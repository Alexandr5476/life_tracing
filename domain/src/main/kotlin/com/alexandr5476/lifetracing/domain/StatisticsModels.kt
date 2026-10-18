package com.alexandr5476.lifetracing.domain

import java.math.BigInteger
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

sealed interface StatisticsPeriod {
    data class Day(
        val date: LocalDate,
    ) : StatisticsPeriod

    data class Week(
        val weekStart: LocalDate,
    ) : StatisticsPeriod {
        init {
            require(weekStart.dayOfWeek == DayOfWeek.MONDAY) { "Statistics week must start on Monday" }
        }
    }

    data class Month(
        val month: YearMonth,
    ) : StatisticsPeriod

    data class Year(
        val year: java.time.Year,
    ) : StatisticsPeriod

    data class Custom(
        val startDate: LocalDate,
        val endDateInclusive: LocalDate,
    ) : StatisticsPeriod {
        init {
            require(endDateInclusive >= startDate) { "Statistics range end cannot precede its start" }
        }
    }

    data object AllTime : StatisticsPeriod
}

data class StatisticsDateRange(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
) {
    init {
        require(endDateInclusive >= startDate) { "Statistics range end cannot precede its start" }
    }

    val calendarDayCount: Long = Math.addExact(ChronoUnit.DAYS.between(startDate, endDateInclusive), 1)
}

fun StatisticsPeriod.dateRangeOrNull(): StatisticsDateRange? =
    when (this) {
        is StatisticsPeriod.Day -> StatisticsDateRange(date, date)
        is StatisticsPeriod.Week -> StatisticsDateRange(weekStart, weekStart.plusDays(DAYS_AFTER_MONDAY_IN_WEEK))
        is StatisticsPeriod.Month -> StatisticsDateRange(month.atDay(1), month.atEndOfMonth())
        is StatisticsPeriod.Year -> StatisticsDateRange(year.atDay(1), year.atMonth(LAST_MONTH).atEndOfMonth())
        is StatisticsPeriod.Custom -> StatisticsDateRange(startDate, endDateInclusive)
        StatisticsPeriod.AllTime -> null
    }

private const val DAYS_AFTER_MONDAY_IN_WEEK = 6L
private const val LAST_MONTH = 12

data class ExactValue(
    val numerator: BigInteger,
    val denominator: BigInteger,
) {
    init {
        require(denominator.signum() > 0) { "Exact-value denominator must be positive" }
        require(numerator.gcd(denominator) == BigInteger.ONE) { "Exact value must be reduced" }
    }

    companion object {
        fun of(
            numerator: Long,
            denominator: Long,
        ): ExactValue = of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator))

        fun of(
            numerator: BigInteger,
            denominator: BigInteger,
        ): ExactValue {
            require(denominator.signum() != 0) { "Exact-value denominator cannot be zero" }
            val sign = if (denominator.signum() < 0) BigInteger.valueOf(-1) else BigInteger.ONE
            val signedNumerator = numerator * sign
            val positiveDenominator = denominator * sign
            val divisor = signedNumerator.gcd(positiveDenominator)
            return ExactValue(signedNumerator / divisor, positiveDenominator / divisor)
        }
    }
}

data class CountRatio(
    val numerator: Long,
    val denominator: Long,
) {
    init {
        require(numerator >= 0 && denominator >= 0 && numerator <= denominator) {
            "Count ratio requires 0 <= numerator <= denominator"
        }
    }

    val exactValue: ExactValue?
        get() = if (denominator == 0L) null else ExactValue.of(numerator, denominator)
}

data class DurationDistribution(
    val sampleCount: Long,
    val total: Duration,
    val averageMilliseconds: ExactValue?,
    val medianMilliseconds: ExactValue?,
    val minimum: Duration?,
    val maximum: Duration?,
)

data class NumberDistribution(
    val sampleCount: Long,
    val totalScaled: BigInteger,
    val averageScaled: ExactValue?,
    val medianScaled: ExactValue?,
    val minimumScaled: Long?,
    val maximumScaled: Long?,
)

/** Exact two's-complement sum. Each entry counts set bits at its position, from 0 to 63. */
object ExactSignedLongSum {
    fun fromBitCounts(
        sampleCount: Long,
        setBitCounts: List<Long>,
    ): BigInteger {
        require(sampleCount >= 0) { "Negative Number sample count" }
        require(setBitCounts.size == Long.SIZE_BITS) { "Number sum requires all 64 bit counts" }
        return setBitCounts.foldIndexed(BigInteger.ZERO) { bit, total, count ->
            require(count in 0..sampleCount) { "Invalid Number bit count" }
            val contribution = BigInteger.valueOf(count).shiftLeft(bit)
            if (bit == Long.SIZE_BITS - 1) total - contribution else total + contribution
        }
    }
}

object StatisticsDistributionCalculator {
    /** Builds a signed, exact Number distribution from bounded aggregate/selection facts. */
    fun numbers(
        sampleCount: Long,
        totalScaled: BigInteger,
        minimumScaled: Long?,
        maximumScaled: Long?,
        medianEndpoints: List<Long>,
    ): NumberDistribution {
        require(sampleCount >= 0) { "Negative Number sample count" }
        if (sampleCount == 0L) {
            require(totalScaled == BigInteger.ZERO && minimumScaled == null && maximumScaled == null)
            require(medianEndpoints.isEmpty()) { "Empty Number summary has median samples" }
            return NumberDistribution(0, BigInteger.ZERO, null, null, null, null)
        }
        val minimum = requireNotNull(minimumScaled) { "Missing Number minimum" }
        val maximum = requireNotNull(maximumScaled) { "Missing Number maximum" }
        require(maximum >= minimum) { "Invalid Number bounds" }
        val endpointCount = if (sampleCount % 2 == 0L) 2 else 1
        require(medianEndpoints.size == endpointCount) { "Number median selection disagrees with sample count" }
        val lower = medianEndpoints.first()
        val upper = medianEndpoints.last()
        require(lower >= minimum && upper >= lower && upper <= maximum) { "Invalid Number median endpoints" }
        val count = BigInteger.valueOf(sampleCount)
        require(totalScaled >= BigInteger.valueOf(minimum) * count)
        require(totalScaled <= BigInteger.valueOf(maximum) * count)
        val midpointTotal = medianEndpoints.fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value) }
        if (sampleCount <= 2) {
            require(lower == minimum && upper == maximum) { "Small Number endpoints disagree with bounds" }
            require(totalScaled == midpointTotal) { "Small Number total disagrees with endpoints" }
        } else {
            val (smallestTotal, largestTotal) = numberTotalBounds(sampleCount, minimum, maximum, lower, upper)
            require(totalScaled in smallestTotal..largestTotal) { "Number total contradicts order statistics" }
        }
        return NumberDistribution(
            sampleCount,
            totalScaled,
            ExactValue.of(totalScaled, count),
            ExactValue.of(midpointTotal, BigInteger.valueOf(endpointCount.toLong())),
            minimum,
            maximum,
        )
    }

    // Extremes each occur at least once; sorted positions on either side of the median
    // constrain the remaining sum more tightly than sampleCount * min/max alone.
    private fun numberTotalBounds(
        count: Long,
        minimum: Long,
        maximum: Long,
        lower: Long,
        upper: Long,
    ): Pair<BigInteger, BigInteger> {
        val half = BigInteger.valueOf(count / 2)
        val min = BigInteger.valueOf(minimum)
        val max = BigInteger.valueOf(maximum)
        val low = BigInteger.valueOf(lower)
        val high = BigInteger.valueOf(upper)
        return if (count % 2 == 1L) {
            (half * min + half * low + max) to (min + half * low + half * max)
        } else {
            val side = half - BigInteger.ONE
            (side * min + low + side * high + max) to (min + side * low + high + side * max)
        }
    }

    /** Builds an exact duration result from an integral summary and at most two median endpoints. */
    fun durations(
        sampleCount: Long,
        totalMilliseconds: BigInteger,
        minimumMilliseconds: Long?,
        maximumMilliseconds: Long?,
        medianEndpoints: List<Long>,
    ): DurationDistribution {
        require(sampleCount >= 0 && totalMilliseconds.signum() >= 0) { "Invalid duration summary" }
        if (sampleCount == 0L) {
            require(totalMilliseconds == BigInteger.ZERO && minimumMilliseconds == null && maximumMilliseconds == null)
            require(medianEndpoints.isEmpty()) { "Empty duration summary has median samples" }
            return durationDistribution(ScalarDistribution.EMPTY)
        }
        val minimum = requireNotNull(minimumMilliseconds) { "Missing duration minimum" }
        val maximum = requireNotNull(maximumMilliseconds) { "Missing duration maximum" }
        require(minimum >= 0 && maximum >= minimum) { "Invalid duration bounds" }
        val endpointCount = if (sampleCount % 2 == 0L) 2 else 1
        require(medianEndpoints.size == endpointCount) { "Duration median selection disagrees with sample count" }
        val lower = medianEndpoints.first()
        val upper = medianEndpoints.last()
        require(lower >= minimum && upper >= lower && upper <= maximum) { "Invalid duration median endpoints" }
        val count = BigInteger.valueOf(sampleCount)
        require(totalMilliseconds >= BigInteger.valueOf(minimum) * count)
        require(totalMilliseconds <= BigInteger.valueOf(maximum) * count)
        if (sampleCount <= 2) {
            require(lower == minimum && upper == maximum) { "Small distribution endpoints disagree with bounds" }
            val expectedTotal =
                if (sampleCount == 1L) {
                    BigInteger.valueOf(lower)
                } else {
                    BigInteger.valueOf(lower) + BigInteger.valueOf(upper)
                }
            require(totalMilliseconds == expectedTotal) { "Small distribution total disagrees with endpoints" }
        }
        val midpointTotal = medianEndpoints.fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value) }
        return durationDistribution(
            ScalarDistribution(
                sampleCount,
                totalMilliseconds,
                ExactValue.of(totalMilliseconds, count),
                ExactValue.of(midpointTotal, BigInteger.valueOf(endpointCount.toLong())),
                minimum,
                maximum,
            ),
        )
    }

    fun durations(milliseconds: Iterable<Long>): DurationDistribution {
        val values = milliseconds.sorted()
        values.forEach { require(it >= 0) { "Duration samples cannot be negative" } }
        return durationDistribution(scalar(values))
    }

    private fun durationDistribution(scalar: ScalarDistribution): DurationDistribution {
        // Samples are nonnegative; check the upper bound before narrowing on older Android runtimes.
        if (scalar.total > BigInteger.valueOf(Long.MAX_VALUE)) {
            throw ArithmeticException("Duration total overflows Long milliseconds")
        }
        return DurationDistribution(
            scalar.sampleCount,
            Duration.ofMillis(scalar.total.toLong()),
            scalar.average,
            scalar.median,
            scalar.minimum?.let(Duration::ofMillis),
            scalar.maximum?.let(Duration::ofMillis),
        )
    }

    fun numbers(scaledValues: Iterable<Long>): NumberDistribution {
        val scalar = scalar(scaledValues.sorted())
        return NumberDistribution(
            scalar.sampleCount,
            scalar.total,
            scalar.average,
            scalar.median,
            scalar.minimum,
            scalar.maximum,
        )
    }

    private fun scalar(values: List<Long>): ScalarDistribution {
        if (values.isEmpty()) return ScalarDistribution.EMPTY
        val count = values.size.toLong()
        val total = values.fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value) }
        val middle = values.size / 2
        val median =
            if (values.size % 2 == 1) {
                ExactValue.of(values[middle], 1)
            } else {
                ExactValue.of(
                    BigInteger.valueOf(values[middle - 1]) + BigInteger.valueOf(values[middle]),
                    BigInteger.valueOf(2),
                )
            }
        return ScalarDistribution(
            count,
            total,
            ExactValue.of(total, BigInteger.valueOf(count)),
            median,
            values.first(),
            values.last(),
        )
    }

    private data class ScalarDistribution(
        val sampleCount: Long,
        val total: BigInteger,
        val average: ExactValue?,
        val median: ExactValue?,
        val minimum: Long?,
        val maximum: Long?,
    ) {
        companion object {
            val EMPTY = ScalarDistribution(0, BigInteger.ZERO, null, null, null, null)
        }
    }
}

enum class StatisticsSeriesSourceState {
    ACTIVE_SOURCE,
    ARCHIVED_SOURCE,
    NO_CURRENT_SOURCE,
    SYSTEM_ONE_OFF,
}

data class StatisticsSeriesSummary(
    val id: StatisticsSeriesId,
    val kind: StatisticsSeriesKind,
    val displayName: String,
    val archivedAt: Instant?,
    val sourceState: StatisticsSeriesSourceState,
)

data class GlobalStatistics(
    val totalTrackedDuration: Duration,
    val topLevelExecutionCount: Long,
    val activeDayCount: Long,
    val averageTrackedMillisecondsPerActiveDay: ExactValue?,
    val averageTopLevelExecutionsPerActiveDay: ExactValue?,
    val totalSequencePauseIdleDuration: Duration,
    val oneOffActivityExecutionCount: Long,
    val oneOffActivityTrackedDuration: Duration,
    val firstPrimaryDate: LocalDate?,
    val lastPrimaryDate: LocalDate?,
    val calendarDayCount: Long?,
    val averageTrackedMillisecondsPerCalendarDay: ExactValue?,
)

data class StatisticsSeriesPeriodSummary(
    val series: StatisticsSeriesSummary,
    val executionCount: Long,
    val durationSampleCount: Long,
    val totalDuration: Duration,
    val averageDurationMilliseconds: ExactValue?,
    val activeDayCount: Long,
)

data class StatisticsOverview(
    val global: GlobalStatistics,
    val series: List<StatisticsSeriesPeriodSummary>,
) {
    val isEmpty: Boolean
        get() = global.topLevelExecutionCount == 0L && series.all { it.executionCount == 0L }
}

data class ActivitySeriesStatistics(
    val series: StatisticsSeriesSummary,
    val executionCount: Long,
    val durations: DurationDistribution,
    val activeDayCount: Long,
    val averageExecutionsPerActiveDay: ExactValue?,
    val firstPerformedAt: Instant?,
    val lastPerformedAt: Instant?,
)

data class SequenceSeriesStatistics(
    val series: StatisticsSeriesSummary,
    val executionCount: Long,
    val activeDurations: DurationDistribution,
    val totalPauseIdleDuration: Duration,
    val averagePauseIdleMilliseconds: ExactValue?,
    val activeDayCount: Long,
    val averageExecutionsPerActiveDay: ExactValue?,
    val firstPerformedAt: Instant?,
    val lastPerformedAt: Instant?,
)

sealed interface StatisticsFieldId {
    val value: String

    data class Activity(
        val id: ActivityTemplateFieldId,
    ) : StatisticsFieldId {
        override val value: String = id.value
    }

    data class Sequence(
        val id: SequenceTemplateFieldId,
    ) : StatisticsFieldId {
        override val value: String = id.value
    }
}

data class StatisticsFieldDescriptor(
    val id: StatisticsFieldId,
    val type: CustomFieldType,
    val displayName: String,
    val unit: String?,
    val displayPrecision: Int?,
)

sealed interface StatisticsCategoryOptionId {
    val value: String

    data class ActivitySource(
        val id: CategoryOptionId,
    ) : StatisticsCategoryOptionId {
        override val value: String = id.value
    }

    data class SequenceSource(
        val id: SequenceTemplateCategoryOptionId,
    ) : StatisticsCategoryOptionId {
        override val value: String = id.value
    }

    data class SnapshotFallback(
        override val value: String,
    ) : StatisticsCategoryOptionId
}

data class NumberFieldStatistics(
    val field: StatisticsFieldDescriptor,
    val relevantExecutionCount: Long,
    val recordedCount: Long,
    val missingCount: Long,
    val coverage: CountRatio,
    val values: NumberDistribution,
)

data class CategoryValueStatistics(
    val id: StatisticsCategoryOptionId,
    val displayLabel: String,
    val count: Long,
    val recordedShare: CountRatio,
)

data class CategoryFieldStatistics(
    val field: StatisticsFieldDescriptor,
    val relevantExecutionCount: Long,
    val recordedCount: Long,
    val missingCount: Long,
    val coverage: CountRatio,
    val values: List<CategoryValueStatistics>,
)

sealed interface StatisticsFieldDetail {
    val field: StatisticsFieldDescriptor

    data class Number(
        val statistics: NumberFieldStatistics,
    ) : StatisticsFieldDetail {
        override val field: StatisticsFieldDescriptor = statistics.field
    }

    data class Category(
        val statistics: CategoryFieldStatistics,
    ) : StatisticsFieldDetail {
        override val field: StatisticsFieldDescriptor = statistics.field
    }

    data class Text(
        override val field: StatisticsFieldDescriptor,
    ) : StatisticsFieldDetail
}

sealed interface StatisticsSeriesDetail {
    val fields: List<StatisticsFieldDetail>

    data class Activity(
        val statistics: ActivitySeriesStatistics,
        override val fields: List<StatisticsFieldDetail>,
    ) : StatisticsSeriesDetail

    data class Sequence(
        val statistics: SequenceSeriesStatistics,
        override val fields: List<StatisticsFieldDetail>,
    ) : StatisticsSeriesDetail
}
