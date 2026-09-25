@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "LongParameterList",
)

package com.alexandr5476.lifetracing.statistics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.StatisticsOverview
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesPeriodSummary
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.dateRangeOrNull
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Composable
fun StatisticsRoute(
    controller: StatisticsController,
    onOpenSeries: (StatisticsSeriesId, StatisticsPeriod) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    BackHandler(onBack = onBack)
    StatisticsScreen(state, controller::selectPeriod, controller::retry, onBack, controller::refresh, onOpenSeries)
}

@Composable
internal fun StatisticsScreen(
    state: StatisticsPresentationState,
    onSelectPeriod: (StatisticsPeriod) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = onRetry,
    onOpenSeries: (StatisticsSeriesId, StatisticsPeriod) -> Unit = { _, _ -> },
) {
    var kind by remember(state.selectedPeriod) { mutableStateOf(StatisticsPeriodKind.from(state.selectedPeriod)) }
    var day by remember(state.selectedPeriod) {
        mutableStateOf(
            state.selectedPeriod
                .dateRangeOrNull()
                ?.startDate
                ?.toString() ?: LocalDate.now().toString(),
        )
    }
    var week by remember(state.selectedPeriod) {
        mutableStateOf(
            state.selectedPeriod
                .dateRangeOrNull()
                ?.startDate
                ?.toString()
                ?: LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
        )
    }
    var month by remember(state.selectedPeriod) {
        mutableStateOf(
            YearMonth
                .from(
                    state.selectedPeriod.dateRangeOrNull()?.startDate ?: LocalDate.now(),
                ).toString(),
        )
    }
    var year by remember(state.selectedPeriod) {
        mutableStateOf(
            (
                state.selectedPeriod
                    .dateRangeOrNull()
                    ?.startDate
                    ?.year
                    ?: LocalDate.now().year
            ).toString(),
        )
    }
    var customStart by remember(state.selectedPeriod) {
        mutableStateOf((state.selectedPeriod as? StatisticsPeriod.Custom)?.startDate?.toString() ?: "")
    }
    var customEnd by remember(state.selectedPeriod) {
        mutableStateOf((state.selectedPeriod as? StatisticsPeriod.Custom)?.endDateInclusive?.toString() ?: "")
    }
    var invalidCustom by remember { mutableStateOf(false) }
    val select: (StatisticsPeriodKind) -> Unit = { selected ->
        kind = selected
        invalidCustom = false
        runCatching { selected.toPeriod(day, week, month, year) }.getOrNull()?.let(onSelectPeriod)
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.statistics_title), style = MaterialTheme.typography.headlineSmall)
                Row {
                    TextButton(onClick = onRefresh, modifier = Modifier.testTag("statistics-refresh")) {
                        Text(stringResource(R.string.statistics_refresh))
                    }
                    TextButton(onClick = onBack) { Text(stringResource(R.string.statistics_back)) }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                StatisticsPeriodKind.entries.forEach { item ->
                    LifeTracingSecondaryButton(onClick = { select(item) }, enabled = item != kind) {
                        Text(stringResource(item.label))
                    }
                }
            }
            when (kind) {
                StatisticsPeriodKind.DAY ->
                    PeriodField(day, {
                        day = it
                    }, R.string.statistics_day_date) {
                        parseDate(
                            day,
                        )?.let { onSelectPeriod(StatisticsPeriod.Day(it)) }
                    }
                StatisticsPeriodKind.WEEK ->
                    PeriodField(week, { week = it }, R.string.statistics_week_date) {
                        parseDate(week)?.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))?.let {
                            onSelectPeriod(StatisticsPeriod.Week(it))
                        }
                    }
                StatisticsPeriodKind.MONTH ->
                    PeriodField(month, {
                        month = it
                    }, R.string.statistics_month_date) {
                        runCatching {
                            YearMonth.parse(
                                month,
                            )
                        }.getOrNull()?.let { onSelectPeriod(StatisticsPeriod.Month(it)) }
                    }
                StatisticsPeriodKind.YEAR ->
                    PeriodField(year, {
                        year = it
                    }, R.string.statistics_year_date) {
                        runCatching { Year.parse(year) }.getOrNull()?.let { onSelectPeriod(StatisticsPeriod.Year(it)) }
                    }
                StatisticsPeriodKind.CUSTOM -> {
                    PeriodField(
                        customStart,
                        {
                            customStart = it
                            invalidCustom = false
                        },
                        R.string.statistics_custom_start,
                        modifier =
                            Modifier.testTag(
                                "statistics-custom-start",
                            ),
                        showApply = false,
                    ) {}
                    PeriodField(
                        customEnd,
                        {
                            customEnd = it
                            invalidCustom = false
                        },
                        R.string.statistics_custom_end,
                        modifier =
                            Modifier.testTag(
                                "statistics-custom-end",
                            ),
                        showApply = false,
                    ) {}
                    LifeTracingSecondaryButton(
                        onClick = {
                            val start = parseDate(customStart)
                            val end = parseDate(customEnd)
                            if (start == null || end == null || end < start) {
                                invalidCustom = true
                            } else {
                                invalidCustom = false
                                onSelectPeriod(StatisticsPeriod.Custom(start, end))
                            }
                        },
                        modifier =
                            Modifier.testTag(
                                "statistics-custom-apply",
                            ),
                    ) { Text(stringResource(R.string.statistics_apply_range)) }
                    if (invalidCustom) {
                        Text(
                            stringResource(R.string.statistics_invalid_range),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                StatisticsPeriodKind.ALL_TIME -> Unit
            }
            if (kind != StatisticsPeriodKind.CUSTOM && kind != StatisticsPeriodKind.ALL_TIME) {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    LifeTracingSecondaryButton(
                        onClick = {
                            navigate(state.selectedPeriod, -1)?.let(onSelectPeriod)
                        },
                        modifier =
                            Modifier.testTag(
                                "statistics-previous",
                            ),
                    ) { Text(stringResource(R.string.statistics_previous)) }
                    LifeTracingSecondaryButton(
                        onClick = {
                            navigate(state.selectedPeriod, 1)?.let(onSelectPeriod)
                        },
                        modifier =
                            Modifier.testTag(
                                "statistics-next",
                            ),
                    ) { Text(stringResource(R.string.statistics_next)) }
                }
            }
            when (val load = state.load) {
                StatisticsLoadState.Loading ->
                    Text(
                        stringResource(R.string.statistics_loading),
                        modifier = Modifier.testTag("statistics-loading"),
                    )
                StatisticsLoadState.Failure -> {
                    Text(stringResource(R.string.statistics_failure), color = MaterialTheme.colorScheme.error)
                    LifeTracingSecondaryButton(onClick = onRetry, modifier = Modifier.testTag("statistics-retry")) {
                        Text(stringResource(R.string.statistics_retry))
                    }
                }
                is StatisticsLoadState.Empty -> {
                    Text(stringResource(R.string.statistics_empty), modifier = Modifier.testTag("statistics-empty"))
                    OverviewContent(load.overview, state.selectedPeriod, onOpenSeries)
                }
                is StatisticsLoadState.Content -> OverviewContent(load.overview, state.selectedPeriod, onOpenSeries)
            }
        }
    }
}

internal enum class StatisticsPeriodKind(
    val label: Int,
) {
    DAY(R.string.statistics_period_day),
    WEEK(R.string.statistics_period_week),
    MONTH(R.string.statistics_period_month),
    YEAR(R.string.statistics_period_year),
    CUSTOM(R.string.statistics_period_custom),
    ALL_TIME(R.string.statistics_period_all_time),
    ;

    fun toPeriod(
        day: String,
        week: String,
        month: String,
        year: String,
    ): StatisticsPeriod? =
        when (this) {
            DAY -> parseDate(day)?.let { StatisticsPeriod.Day(it) }
            WEEK ->
                parseDate(
                    week,
                )?.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))?.let { StatisticsPeriod.Week(it) }
            MONTH -> runCatching { YearMonth.parse(month) }.getOrNull()?.let { StatisticsPeriod.Month(it) }
            YEAR -> runCatching { Year.parse(year) }.getOrNull()?.let { StatisticsPeriod.Year(it) }
            CUSTOM -> null
            ALL_TIME -> StatisticsPeriod.AllTime
        }

    companion object {
        fun from(period: StatisticsPeriod) =
            when (period) {
                is StatisticsPeriod.Day -> DAY
                is StatisticsPeriod.Week -> WEEK
                is StatisticsPeriod.Month -> MONTH
                is StatisticsPeriod.Year -> YEAR
                is StatisticsPeriod.Custom -> CUSTOM
                StatisticsPeriod.AllTime -> ALL_TIME
            }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun PeriodField(
    value: String,
    onValue: (String) -> Unit,
    label: Int,
    modifier: Modifier = Modifier,
    showApply: Boolean = true,
    onApply: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        LifeTracingOutlinedTextField(value, onValue, Modifier.weight(1f).then(modifier), label = {
            Text(stringResource(label))
        })
        if (showApply) {
            LifeTracingSecondaryButton(
                onClick = onApply,
                modifier = Modifier.testTag("statistics-period-apply"),
            ) {
                Text(stringResource(R.string.statistics_apply))
            }
        }
    }
}

@Composable
private fun OverviewContent(
    overview: StatisticsOverview,
    period: StatisticsPeriod,
    onOpenSeries: (StatisticsSeriesId, StatisticsPeriod) -> Unit,
) {
    val global = overview.global
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Metric(R.string.statistics_tracked_time, duration(global.totalTrackedDuration))
            Metric(R.string.statistics_top_level_executions, global.topLevelExecutionCount.toString())
            Metric(R.string.statistics_active_days, global.activeDayCount.toString())
            Metric(
                R.string.statistics_average_per_active_day,
                global.averageTrackedMillisecondsPerActiveDay?.let(::averageDuration)
                    ?: stringResource(R.string.statistics_unavailable),
            )
            Metric(R.string.statistics_sequence_pause_idle, duration(global.totalSequencePauseIdleDuration))
            Text(stringResource(R.string.statistics_series_title), style = MaterialTheme.typography.titleMedium)
            overview.series.forEach { SeriesRow(it, period, onOpenSeries) }
        }
    }
}

@Composable
private fun SeriesRow(
    summary: StatisticsSeriesPeriodSummary,
    period: StatisticsPeriod,
    onOpenSeries: (StatisticsSeriesId, StatisticsPeriod) -> Unit,
) {
    val series = summary.series
    val drillable = series.kind == StatisticsSeriesKind.ACTIVITY || series.kind == StatisticsSeriesKind.SEQUENCE
    val content: @Composable () -> Unit = {
        Column(
            Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(
                if (series.kind == StatisticsSeriesKind.ONE_OFF_BUCKET) {
                    stringResource(R.string.statistics_kind_one_off)
                } else {
                    series.displayName
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(
                    if (series.kind ==
                        StatisticsSeriesKind.ONE_OFF_BUCKET
                    ) {
                        R.string.statistics_kind_one_off
                    } else if (series.kind ==
                        StatisticsSeriesKind.SEQUENCE
                    ) {
                        R.string.statistics_kind_sequence
                    } else {
                        R.string.statistics_kind_activity
                    },
                ),
            )
            Text(stringResource(sourceLabel(series.sourceState)))
            Metric(R.string.statistics_executions, summary.executionCount.toString())
            Metric(
                R.string.statistics_duration_total,
                if (summary.durationSampleCount ==
                    0L
                ) {
                    stringResource(R.string.statistics_no_duration_samples)
                } else {
                    duration(summary.totalDuration)
                },
            )
            Metric(
                R.string.statistics_duration_average,
                summary.averageDurationMilliseconds?.let(::averageDuration)
                    ?: stringResource(R.string.statistics_unavailable),
            )
            Metric(R.string.statistics_active_days, summary.activeDayCount.toString())
        }
    }
    if (drillable) {
        Card(
            onClick = { onOpenSeries(series.id, period) },
            modifier = Modifier.fillMaxWidth().testTag("statistics-series-${series.id.value}"),
        ) { content() }
    } else {
        Card(Modifier.fillMaxWidth().testTag("statistics-series-${series.id.value}")) { content() }
    }
}

private fun sourceLabel(state: StatisticsSeriesSourceState) =
    when (state) {
        StatisticsSeriesSourceState.ACTIVE_SOURCE -> R.string.statistics_source_active
        StatisticsSeriesSourceState.ARCHIVED_SOURCE -> R.string.statistics_source_archived
        StatisticsSeriesSourceState.NO_CURRENT_SOURCE -> R.string.statistics_source_missing
        StatisticsSeriesSourceState.SYSTEM_ONE_OFF -> R.string.statistics_source_system
    }

@Composable private fun Metric(
    label: Int,
    value: String,
) {
    Text(stringResource(label, value))
}

internal fun navigate(
    period: StatisticsPeriod,
    amount: Long,
): StatisticsPeriod? =
    try {
        when (period) {
            is StatisticsPeriod.Day -> StatisticsPeriod.Day(period.date.plusDays(amount))
            is StatisticsPeriod.Week -> StatisticsPeriod.Week(period.weekStart.plusWeeks(amount))
            is StatisticsPeriod.Month -> StatisticsPeriod.Month(period.month.plusMonths(amount))
            is StatisticsPeriod.Year -> StatisticsPeriod.Year(period.year.plusYears(amount))
            is StatisticsPeriod.Custom, StatisticsPeriod.AllTime -> null
        }
    } catch (_: Exception) {
        null
    }

internal fun parseDate(value: String): LocalDate? =
    runCatching {
        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()

private fun duration(value: Duration): String =
    "%d:%02d:%02d".format(
        value.seconds / 3600,
        value.seconds / 60 % 60,
        value.seconds % 60,
    )

private fun averageDuration(value: ExactValue): String =
    duration(
        Duration.ofMillis(
            BigDecimal(value.numerator).divide(BigDecimal(value.denominator), 0, RoundingMode.HALF_UP).longValueExact(),
        ),
    )
