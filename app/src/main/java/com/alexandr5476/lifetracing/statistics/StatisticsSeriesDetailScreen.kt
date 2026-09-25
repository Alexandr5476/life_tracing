@file:Suppress("FunctionNaming", "LongMethod", "TooManyFunctions", "MaxLineLength", "CyclomaticComplexMethod")

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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.DurationDistribution
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.NumberFieldStatistics
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsFieldDetail
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.dateRangeOrNull
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_MINUTE = 60L
private const val ZERO_DECIMAL_SCALE = 0
private const val NUMBER_STORAGE_SCALE = 1000
private const val NUMBER_DISPLAY_SCALE = 3
private const val RATIO_DISPLAY_SCALE = 4
private const val MAX_PERCENT_FRACTION_DIGITS = 1

@Composable
internal fun StatisticsSeriesDetailRoute(
    controller: StatisticsSeriesDetailController,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    BackHandler(onBack = onBack)
    StatisticsSeriesDetailScreen(state, controller::selectPeriod, controller::refresh, onBack)
}

@Composable
internal fun StatisticsSeriesDetailScreen(
    state: StatisticsSeriesDetailPresentationState,
    onSelectPeriod: (StatisticsPeriod) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
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
        mutableStateOf(
            (state.selectedPeriod as? StatisticsPeriod.Custom)?.startDate?.toString() ?: "",
        )
    }
    var customEnd by remember(state.selectedPeriod) {
        mutableStateOf(
            (state.selectedPeriod as? StatisticsPeriod.Custom)?.endDateInclusive?.toString() ?: "",
        )
    }
    var invalid by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.statistics_detail_title), style = MaterialTheme.typography.headlineSmall)
                Row {
                    TextButton(onClick = onRefresh, modifier = Modifier.testTag("statistics-detail-refresh")) {
                        Text(stringResource(R.string.statistics_refresh))
                    }
                    TextButton(onClick = onBack) { Text(stringResource(R.string.statistics_back)) }
                }
            }
            Text(stringResource(R.string.statistics_period_title), style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                StatisticsPeriodKind.entries.forEach { item ->
                    LifeTracingSecondaryButton(
                        onClick = {
                            kind = item
                            invalid = false
                            item.toPeriod(day, week, month, year)?.let(onSelectPeriod)
                        },
                        enabled =
                            item != kind,
                        modifier = Modifier.testTag("statistics-detail-period-${item.name.lowercase()}"),
                    ) { Text(stringResource(item.label)) }
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
                    PeriodField(week, {
                        week = it
                    }, R.string.statistics_week_date) {
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
                            invalid = false
                        },
                        R.string.statistics_custom_start,
                        modifier =
                            Modifier.testTag(
                                "statistics-detail-custom-start",
                            ),
                        showApply = false,
                    ) {
                    }
                    PeriodField(
                        customEnd,
                        {
                            customEnd = it
                            invalid = false
                        },
                        R.string.statistics_custom_end,
                        modifier =
                            Modifier.testTag(
                                "statistics-detail-custom-end",
                            ),
                        showApply = false,
                    ) {}
                    LifeTracingSecondaryButton(onClick = {
                        val start = parseDate(customStart)
                        val end = parseDate(customEnd)
                        invalid =
                            start == null ||
                            end == null ||
                            end < start
                        if (!invalid) {
                            onSelectPeriod(
                                StatisticsPeriod.Custom(requireNotNull(start), requireNotNull(end)),
                            )
                        }
                    }, modifier = Modifier.testTag("statistics-detail-custom-apply")) {
                        Text(stringResource(R.string.statistics_apply_range))
                    }
                    if (invalid) {
                        Text(
                            stringResource(R.string.statistics_invalid_range),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                StatisticsPeriodKind.ALL_TIME -> Unit
            }
            if (kind != StatisticsPeriodKind.CUSTOM &&
                kind != StatisticsPeriodKind.ALL_TIME
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    LifeTracingSecondaryButton(
                        onClick = {
                            navigate(state.selectedPeriod, -1)?.let(onSelectPeriod)
                        },
                        modifier =
                            Modifier.testTag(
                                "statistics-detail-previous",
                            ),
                    ) { Text(stringResource(R.string.statistics_previous)) }
                    LifeTracingSecondaryButton(
                        onClick = {
                            navigate(state.selectedPeriod, 1)?.let(onSelectPeriod)
                        },
                        modifier =
                            Modifier.testTag(
                                "statistics-detail-next",
                            ),
                    ) { Text(stringResource(R.string.statistics_next)) }
                }
            }
            when (val load = state.load) {
                StatisticsSeriesDetailLoadState.Loading ->
                    Text(
                        stringResource(R.string.statistics_loading),
                        modifier = Modifier.testTag("statistics-detail-loading"),
                    )
                StatisticsSeriesDetailLoadState.Failure -> {
                    Text(stringResource(R.string.statistics_failure), color = MaterialTheme.colorScheme.error)
                    LifeTracingSecondaryButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("statistics-detail-retry"),
                    ) {
                        Text(stringResource(R.string.statistics_retry))
                    }
                }
                is StatisticsSeriesDetailLoadState.Content -> DetailContent(load.detail)
            }
        }
    }
}

@Composable
private fun DetailContent(detail: StatisticsSeriesDetail) {
    val series =
        when (detail) {
            is StatisticsSeriesDetail.Activity -> detail.statistics.series
            is StatisticsSeriesDetail.Sequence -> detail.statistics.series
        }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text(series.displayName, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(sourceLabel(series.sourceState)))
                when (detail) {
                    is StatisticsSeriesDetail.Activity -> {
                        val s = detail.statistics
                        Metric(R.string.statistics_executions, s.executionCount.toString())
                        DurationMetrics(s.durations)
                        Metric(R.string.statistics_active_days, s.activeDayCount.toString())
                        Metric(
                            R.string.statistics_average_per_active_day_count,
                            s.averageExecutionsPerActiveDay?.let(::exact) ?: unavailable(),
                        )
                        Performed(R.string.statistics_first_performed, s.firstPerformedAt)
                        Performed(R.string.statistics_last_performed, s.lastPerformedAt)
                    }
                    is StatisticsSeriesDetail.Sequence -> {
                        val s = detail.statistics
                        Metric(R.string.statistics_executions, s.executionCount.toString())
                        DurationMetrics(s.activeDurations)
                        Metric(R.string.statistics_total_pause_idle, duration(s.totalPauseIdleDuration))
                        Metric(
                            R.string.statistics_average_pause_idle,
                            s.averagePauseIdleMilliseconds?.let(::averageDuration) ?: unavailable(),
                        )
                        Metric(R.string.statistics_active_days, s.activeDayCount.toString())
                        Metric(
                            R.string.statistics_average_per_active_day_count,
                            s.averageExecutionsPerActiveDay?.let(::exact) ?: unavailable(),
                        )
                        Performed(R.string.statistics_first_performed, s.firstPerformedAt)
                        Performed(R.string.statistics_last_performed, s.lastPerformedAt)
                    }
                }
            }
        }
        if (detail.fields.isNotEmpty()) {
            Text(stringResource(R.string.statistics_field_title), style = MaterialTheme.typography.titleMedium)
            detail.fields.forEach { field -> key(field.field.id) { FieldContent(field) } }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun DurationMetrics(distribution: DurationDistribution) {
    Metric(
        R.string.statistics_duration_total,
        if (distribution.sampleCount ==
            0L
        ) {
            stringResource(R.string.statistics_no_duration_samples)
        } else {
            duration(distribution.total)
        },
    )
    Metric(
        R.string.statistics_duration_average,
        distribution.averageMilliseconds?.let(::averageDuration) ?: unavailable(),
    )
    Metric(
        R.string.statistics_duration_median,
        distribution.medianMilliseconds?.let(::averageDuration) ?: unavailable(),
    )
    Metric(R.string.statistics_duration_minimum, distribution.minimum?.let(::duration) ?: unavailable())
    Metric(R.string.statistics_duration_maximum, distribution.maximum?.let(::duration) ?: unavailable())
}

@Composable
private fun FieldContent(field: StatisticsFieldDetail) {
    Card(Modifier.fillMaxWidth().testTag("statistics-field-${fieldTag(field.field.id)}")) {
        Column(
            Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(field.field.displayName, style = MaterialTheme.typography.titleSmall)
            when (field) {
                is StatisticsFieldDetail.Number -> {
                    val s: NumberFieldStatistics = field.statistics
                    Metric(R.string.statistics_field_recorded, s.recordedCount.toString())
                    Metric(R.string.statistics_field_missing, s.missingCount.toString())
                    Metric(R.string.statistics_field_coverage, ratio(s.coverage) ?: unavailable())
                    Metric(
                        R.string.statistics_number_total,
                        if (s.values.sampleCount ==
                            0L
                        ) {
                            unavailable()
                        } else {
                            scaled(s.values.totalScaled, field.field.displayPrecision, field.field.unit)
                        },
                    )
                    Metric(
                        R.string.statistics_number_average,
                        s.values.averageScaled?.let { scaled(it, field.field.displayPrecision, field.field.unit) }
                            ?: unavailable(),
                    )
                    Metric(
                        R.string.statistics_number_median,
                        s.values.medianScaled?.let { scaled(it, field.field.displayPrecision, field.field.unit) }
                            ?: unavailable(),
                    )
                    Metric(
                        R.string.statistics_number_minimum,
                        s.values.minimumScaled?.let {
                            scaled(
                                BigInteger.valueOf(it),
                                field.field.displayPrecision,
                                field.field.unit,
                            )
                        }
                            ?: unavailable(),
                    )
                    Metric(
                        R.string.statistics_number_maximum,
                        s.values.maximumScaled?.let {
                            scaled(
                                BigInteger.valueOf(it),
                                field.field.displayPrecision,
                                field.field.unit,
                            )
                        }
                            ?: unavailable(),
                    )
                }
                is StatisticsFieldDetail.Category -> {
                    val s = field.statistics
                    Metric(R.string.statistics_field_recorded, s.recordedCount.toString())
                    Metric(R.string.statistics_field_missing, s.missingCount.toString())
                    Metric(R.string.statistics_field_coverage, ratio(s.coverage) ?: unavailable())
                    Text(
                        stringResource(R.string.statistics_category_values),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    s.values.forEach { value ->
                        key(value.id) {
                            Text(
                                stringResource(
                                    R.string.statistics_category_count_share,
                                    value.displayLabel,
                                    value.count.toString(),
                                    ratio(value.recordedShare) ?: unavailable(),
                                ),
                                modifier =
                                    Modifier.testTag(
                                        "statistics-category-${fieldTag(field.field.id)}-${categoryTag(value.id)}",
                                    ),
                            )
                        }
                    }
                }
                is StatisticsFieldDetail.Text -> Text(stringResource(R.string.statistics_field_text))
            }
        }
    }
}

@Composable private fun Performed(
    label: Int,
    value: Instant?,
) = Metric(label, value?.let(::performed) ?: unavailable())

@Composable private fun Metric(
    label: Int,
    value: String,
) {
    Text(stringResource(label, value))
}

@Composable private fun unavailable() = stringResource(R.string.statistics_unavailable)

private fun sourceLabel(state: StatisticsSeriesSourceState) =
    when (state) {
        StatisticsSeriesSourceState.ACTIVE_SOURCE -> R.string.statistics_source_active
        StatisticsSeriesSourceState.ARCHIVED_SOURCE -> R.string.statistics_source_archived
        StatisticsSeriesSourceState.NO_CURRENT_SOURCE -> R.string.statistics_source_missing
        StatisticsSeriesSourceState.SYSTEM_ONE_OFF -> R.string.statistics_source_system
    }

private fun fieldTag(id: StatisticsFieldId): String =
    when (id) {
        is StatisticsFieldId.Activity -> "activity-${id.id.value}"
        is StatisticsFieldId.Sequence -> "sequence-${id.id.value}"
    }

private fun categoryTag(id: StatisticsCategoryOptionId): String =
    when (id) {
        is StatisticsCategoryOptionId.ActivitySource -> "activity-${id.id.value}"
        is StatisticsCategoryOptionId.SequenceSource -> "sequence-${id.id.value}"
        is StatisticsCategoryOptionId.SnapshotFallback -> "snapshot-${id.value}"
    }

private fun duration(value: Duration): String =
    "%d:%02d:%02d".format(
        value.seconds / SECONDS_PER_HOUR,
        value.seconds / SECONDS_PER_MINUTE % SECONDS_PER_MINUTE,
        value.seconds % SECONDS_PER_MINUTE,
    )

private fun averageDuration(value: ExactValue): String =
    duration(
        Duration.ofMillis(
            BigDecimal(
                value.numerator,
            ).divide(BigDecimal(value.denominator), ZERO_DECIMAL_SCALE, RoundingMode.HALF_UP).longValueExact(),
        ),
    )

private fun exact(value: ExactValue): String =
    decimal(
        BigDecimal(value.numerator).divide(BigDecimal(value.denominator), NUMBER_DISPLAY_SCALE, RoundingMode.HALF_UP),
    )

private fun scaled(
    value: BigInteger,
    precision: Int?,
    unit: String?,
): String =
    withUnit(
        decimal(
            BigDecimal(value).divide(
                BigDecimal(NUMBER_STORAGE_SCALE),
                precision ?: NUMBER_DISPLAY_SCALE,
                RoundingMode.HALF_UP,
            ),
        ),
        unit,
    )

private fun scaled(
    value: ExactValue,
    precision: Int?,
    unit: String?,
): String =
    withUnit(
        decimal(
            BigDecimal(value.numerator).divide(
                BigDecimal(value.denominator).multiply(BigDecimal(NUMBER_STORAGE_SCALE)),
                precision ?: NUMBER_DISPLAY_SCALE,
                RoundingMode.HALF_UP,
            ),
        ),
        unit,
    )

private fun decimal(value: BigDecimal): String =
    NumberFormat
        .getNumberInstance(Locale.getDefault())
        .apply {
            maximumFractionDigits =
                value.scale().coerceIn(ZERO_DECIMAL_SCALE, NUMBER_DISPLAY_SCALE)
            ; minimumFractionDigits = ZERO_DECIMAL_SCALE
        }.format(value.stripTrailingZeros())

private fun withUnit(
    value: String,
    unit: String?,
): String = if (unit.isNullOrBlank()) value else "$value $unit"

private fun ratio(value: com.alexandr5476.lifetracing.domain.CountRatio): String? =
    value.exactValue?.let {
        NumberFormat
            .getPercentInstance(Locale.getDefault())
            .apply {
                maximumFractionDigits =
                    MAX_PERCENT_FRACTION_DIGITS
            }.format(
                BigDecimal(
                    it.numerator,
                ).divide(BigDecimal(it.denominator), RATIO_DISPLAY_SCALE, RoundingMode.HALF_UP),
            )
    }

private fun performed(value: Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(
            FormatStyle.MEDIUM,
        ).withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(value)
