@file:Suppress("FunctionNaming", "LongParameterList")

package com.alexandr5476.lifetracing.statistics

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivitySeriesStatistics
import com.alexandr5476.lifetracing.domain.DurationDistribution
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.SequenceSeriesStatistics
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun seriesMetric(
    detail: StatisticsSeriesDetail,
    metric: DetailSeriesMetric,
): String {
    val locale = LocalConfiguration.current.locales[0]
    val unavailable = stringResource(R.string.statistics_unavailable)
    val noSamples = stringResource(R.string.statistics_no_duration_samples)
    val value =
        when (metric) {
            DetailSeriesMetric.EXECUTIONS ->
                statisticsCount(detail.fact({ executionCount }, { executionCount }), locale)
            DetailSeriesMetric.ACTIVE_DAYS ->
                statisticsCount(detail.fact({ activeDayCount }, { activeDayCount }), locale)
            DetailSeriesMetric.FREQUENCY ->
                detail
                    .fact({ averageExecutionsPerActiveDay }, { averageExecutionsPerActiveDay })
                    ?.let { statisticsExact(it, locale) } ?: unavailable
            DetailSeriesMetric.FIRST_PERFORMED ->
                performed(detail.fact({ firstPerformedAt }, { firstPerformedAt }), locale)
            DetailSeriesMetric.LAST_PERFORMED ->
                performed(detail.fact({ lastPerformedAt }, { lastPerformedAt }), locale)
            DetailSeriesMetric.PAUSE_TOTAL -> {
                val sequence = (detail as StatisticsSeriesDetail.Sequence).statistics
                statisticsDuration(sequence.totalPauseIdleDuration, locale)
            }
            DetailSeriesMetric.PAUSE_AVERAGE -> {
                val sequence = (detail as StatisticsSeriesDetail.Sequence).statistics
                averageDuration(sequence.averagePauseIdleMilliseconds, locale)
            }
            else -> durationMetric(detail.fact({ durations }, { activeDurations }), metric, locale, noSamples)
        }
    val label = if (detail is StatisticsSeriesDetail.Sequence) metric.sequenceLabel else metric.activityLabel
    return stringResource(label, value ?: unavailable)
}

private inline fun <T> StatisticsSeriesDetail.fact(
    activity: ActivitySeriesStatistics.() -> T,
    sequence: SequenceSeriesStatistics.() -> T,
): T =
    when (this) {
        is StatisticsSeriesDetail.Activity -> activity(statistics)
        is StatisticsSeriesDetail.Sequence -> sequence(statistics)
    }

private fun durationMetric(
    distribution: DurationDistribution,
    metric: DetailSeriesMetric,
    locale: Locale,
    noSamples: String,
): String? =
    when (metric) {
        DetailSeriesMetric.DURATION_SAMPLES -> statisticsCount(distribution.sampleCount, locale)
        DetailSeriesMetric.DURATION_TOTAL ->
            if (distribution.sampleCount == 0L) noSamples else statisticsDuration(distribution.total, locale)
        DetailSeriesMetric.DURATION_AVERAGE -> averageDuration(distribution.averageMilliseconds, locale)
        DetailSeriesMetric.DURATION_MEDIAN -> averageDuration(distribution.medianMilliseconds, locale)
        DetailSeriesMetric.DURATION_MINIMUM -> distribution.minimum?.let { statisticsDuration(it, locale) }
        DetailSeriesMetric.DURATION_MAXIMUM -> distribution.maximum?.let { statisticsDuration(it, locale) }
        else -> error("Expected a duration metric, found $metric")
    }

private fun averageDuration(
    value: ExactValue?,
    locale: Locale,
): String? = value?.let { statisticsAverageDuration(it, locale) }

private fun performed(
    value: Instant?,
    locale: Locale,
): String? =
    value?.let {
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
            .format(it)
    }
