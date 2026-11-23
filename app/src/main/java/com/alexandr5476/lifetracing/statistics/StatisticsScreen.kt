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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.GlobalStatistics
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesPeriodSummary
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSummary
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.io.Serializable

@Composable
internal fun StatisticsRoute(
    controller: StatisticsController,
    onOpenSeries: (StatisticsSeriesId, StatisticsPeriod) -> Unit = { _, _ -> },
    isRouteVisible: Boolean = true,
    ownsRoute: () -> Boolean = { true },
    returnFocus: StatisticsOverviewReturnFocus = remember { StatisticsOverviewReturnFocus() },
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val admission = rememberStatisticsActionAdmission(state, { controller.state.value }, isRouteVisible, ownsRoute)
    val exit = {
        onBack()
        if (!ownsRoute()) admission.revoke()
    }
    val back = admission.action(onClick = exit)
    BackHandler(enabled = admission.isCurrent()) { back() }
    StatisticsScreen(
        state,
        controller::selectPeriod,
        controller::retry,
        exit,
        controller::refresh,
        { id, period ->
            onOpenSeries(id, period)
            if (!ownsRoute()) {
                returnFocus.series = id
                admission.revoke()
            }
        },
        admission,
        returnFocus,
    )
}

/** Bundle-compatible lazy identity backed by the canonical typed Series ID. */
internal data class StatisticsSeriesItemKey(
    val id: StatisticsSeriesId,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

internal enum class StatisticsOverviewItem {
    HEADER,
    STATUS,
    PERIOD,
    GLOBAL,
    SERIES_HEADING,
}

@Composable
internal fun StatisticsScreen(
    state: StatisticsPresentationState,
    onSelectPeriod: (StatisticsPeriod) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = onRetry,
    onOpenSeries: (StatisticsSeriesId, StatisticsPeriod) -> Unit = { _, _ -> },
    admission: StatisticsActionAdmission = rememberStatisticsActionAdmission(state, { state }),
    returnFocus: StatisticsOverviewReturnFocus = remember { StatisticsOverviewReturnFocus() },
) {
    val appliedPeriod = state.selectedPeriod
    val editor = remember(appliedPeriod) { StatisticsPeriodEditor(appliedPeriod) }
    val periodFocus = remember { StatisticsPeriodFocus() }
    val listState = rememberLazyListState()
    val focus = remember { StatisticsOverviewFocus() }
    val overview =
        when (val load = state.load) {
            is StatisticsLoadState.Content -> load.overview
            is StatisticsLoadState.Empty -> load.overview
            else -> null
        }
    RestoreStatisticsOverviewFocus(state, overview, admission, listState, focus, returnFocus)
    val periodDescription = statisticsPeriodDescription(appliedPeriod)
    val oneOffName = stringResource(R.string.statistics_kind_one_off)
    val seriesNameCounts =
        remember(StatisticsIdentityKey(state), oneOffName) {
            overview
                ?.series
                .orEmpty()
                .groupingBy { it.series.overviewDisplayName(oneOffName) }
                .eachCount()
        }
    LifeTracingAccessibleTargets {
        Surface(color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("statistics-list"),
                contentPadding = PaddingValues(MaterialTheme.spacing.large),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                item(StatisticsOverviewItem.HEADER) {
                    StatisticsHeader(
                        R.string.statistics_title,
                        "statistics",
                        admission,
                        onRefresh,
                        onBack,
                        focus.otherTarget().focusGroup(),
                    )
                }
                item(StatisticsOverviewItem.STATUS) {
                    OverviewStatus(state.load, periodDescription, admission, focus, onRetry)
                }
                item(StatisticsOverviewItem.PERIOD) {
                    StatisticsPeriodControls(
                        editor,
                        periodFocus,
                        admission,
                        "statistics",
                        onSelectPeriod,
                        focus.otherTarget().focusGroup(),
                    )
                }
                if (overview != null) {
                    item(StatisticsOverviewItem.GLOBAL) { GlobalMetrics(overview.global) }
                    item(StatisticsOverviewItem.SERIES_HEADING) {
                        Text(
                            stringResource(R.string.statistics_series_title),
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(overview.series, key = { StatisticsSeriesItemKey(it.series.id) }) { row ->
                        val name = row.series.overviewDisplayName(oneOffName)
                        val title =
                            if (seriesNameCounts.getValue(name) > 1) {
                                stringResource(R.string.statistics_detail_duplicate_name, name, row.series.id.value)
                            } else {
                                name
                            }
                        SeriesRow(row, title, appliedPeriod, periodDescription, admission, focus, onOpenSeries)
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatisticsHeader(
    title: Int,
    tag: String,
    admission: StatisticsActionAdmission,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        Text(
            stringResource(title),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            StatisticsTextButton(admission, onRefresh, Modifier.testTag("$tag-refresh")) {
                Text(stringResource(R.string.statistics_refresh))
            }
            StatisticsTextButton(admission, onBack, Modifier.testTag("$tag-back")) {
                Text(stringResource(R.string.statistics_back))
            }
        }
    }
}

@Composable
internal fun StatisticsResultStatus(
    message: Int,
    periodDescription: String,
    modifier: Modifier,
) {
    Text(
        stringResource(message),
        modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            stateDescription = periodDescription
        },
        color =
            if (message == R.string.statistics_failure) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onBackground
            },
    )
}

@Composable
private fun OverviewStatus(
    load: StatisticsLoadState,
    periodDescription: String,
    admission: StatisticsActionAdmission,
    focus: StatisticsOverviewFocus,
    onRetry: () -> Unit,
) {
    val (label, tag) =
        when (load) {
            StatisticsLoadState.Loading -> R.string.statistics_loading to "statistics-loading"
            StatisticsLoadState.Failure -> R.string.statistics_failure to "statistics-failure"
            is StatisticsLoadState.Empty -> R.string.statistics_empty to "statistics-empty"
            is StatisticsLoadState.Content -> R.string.statistics_loaded to "statistics-content"
        }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        StatisticsResultStatus(
            label,
            periodDescription,
            focus
                .target(StatisticsOverviewFocusAnchor.STATUS)
                .focusable()
                .testTag(tag),
        )
        if (load == StatisticsLoadState.Failure) {
            StatisticsSecondaryButton(
                admission,
                onRetry,
                focus.target(StatisticsOverviewFocusAnchor.RETRY).testTag("statistics-retry"),
            ) { Text(stringResource(R.string.statistics_retry)) }
        }
    }
}

@Composable
private fun GlobalMetrics(global: GlobalStatistics) {
    val locale = LocalConfiguration.current.locales[0]
    Card(Modifier.fillMaxWidth().testTag("statistics-global")) {
        Column(
            Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(
                stringResource(R.string.statistics_global_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Metric(R.string.statistics_tracked_time, statisticsDuration(global.totalTrackedDuration, locale))
            Metric(R.string.statistics_top_level_executions, statisticsCount(global.topLevelExecutionCount, locale))
            Metric(R.string.statistics_active_days, statisticsCount(global.activeDayCount, locale))
            Metric(
                R.string.statistics_average_per_active_day,
                global.averageTrackedMillisecondsPerActiveDay?.let { statisticsAverageDuration(it, locale) }
                    ?: stringResource(R.string.statistics_unavailable),
            )
            Metric(
                R.string.statistics_sequence_pause_idle,
                statisticsDuration(global.totalSequencePauseIdleDuration, locale),
            )
        }
    }
}

@Composable
private fun SeriesRow(
    summary: StatisticsSeriesPeriodSummary,
    title: String,
    period: StatisticsPeriod,
    periodDescription: String,
    admission: StatisticsActionAdmission,
    focus: StatisticsOverviewFocus,
    onOpenSeries: (StatisticsSeriesId, StatisticsPeriod) -> Unit,
) {
    val series = summary.series
    val locale = LocalConfiguration.current.locales[0]
    val kind =
        when (series.kind) {
            StatisticsSeriesKind.ACTIVITY -> R.string.statistics_kind_activity
            StatisticsSeriesKind.SEQUENCE -> R.string.statistics_kind_sequence
            StatisticsSeriesKind.ONE_OFF_BUCKET -> R.string.statistics_kind_one_off
        }
    val oneOff = series.kind == StatisticsSeriesKind.ONE_OFF_BUCKET
    val modifier =
        Modifier
            .fillMaxWidth()
            .testTag("statistics-series-${series.id.value}")
            .then(focus.target(series.id))
            .semantics(mergeDescendants = true) { stateDescription = periodDescription }
    val content: @Composable () -> Unit = {
        Column(
            Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!oneOff) Text(stringResource(kind))
            Text(stringResource(sourceLabel(series.sourceState)))
            Metric(R.string.statistics_executions, statisticsCount(summary.executionCount, locale))
            Metric(
                R.string.statistics_duration_total,
                if (summary.durationSampleCount == 0L) {
                    stringResource(R.string.statistics_no_duration_samples)
                } else {
                    statisticsDuration(summary.totalDuration, locale)
                },
            )
            Metric(
                R.string.statistics_duration_average,
                summary.averageDurationMilliseconds?.let { statisticsAverageDuration(it, locale) }
                    ?: stringResource(R.string.statistics_unavailable),
            )
            Metric(R.string.statistics_active_days, statisticsCount(summary.activeDayCount, locale))
        }
    }
    if (oneOff) {
        Card(modifier.focusable()) { content() }
    } else {
        val action = admission.action { onOpenSeries(series.id, period) }
        Card(
            onClick = { action() },
            modifier =
                modifier
                    .semantics { role = Role.Button }
                    .statisticsActionSemantics(action),
            enabled = admission.isCurrent(),
        ) { content() }
    }
}

private fun StatisticsSeriesSummary.overviewDisplayName(oneOffName: String): String =
    if (kind == StatisticsSeriesKind.ONE_OFF_BUCKET) oneOffName else displayName

private fun sourceLabel(state: StatisticsSeriesSourceState) =
    when (state) {
        StatisticsSeriesSourceState.ACTIVE_SOURCE -> R.string.statistics_source_active
        StatisticsSeriesSourceState.ARCHIVED_SOURCE -> R.string.statistics_source_archived
        StatisticsSeriesSourceState.NO_CURRENT_SOURCE -> R.string.statistics_source_missing
        StatisticsSeriesSourceState.SYSTEM_ONE_OFF -> R.string.statistics_source_system
    }

@Composable
private fun Metric(
    label: Int,
    value: String,
) {
    Text(stringResource(label, value))
}
