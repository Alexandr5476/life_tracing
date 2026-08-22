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

object StatisticsDistributionCalculator {
    fun durations(milliseconds: Iterable<Long>): DurationDistribution {
        val values = milliseconds.sorted()
        values.forEach { require(it >= 0) { "Duration samples cannot be negative" } }
        val scalar = scalar(values)
        return DurationDistribution(
            scalar.sampleCount,
            Duration.ofMillis(scalar.total.longValueExact()),
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
                    BigInteger.TWO,
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
