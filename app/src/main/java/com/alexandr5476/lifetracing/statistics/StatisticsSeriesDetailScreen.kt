@file:Suppress("FunctionNaming", "LongParameterList", "TooManyFunctions")

package com.alexandr5476.lifetracing.statistics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSummary
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.theme.spacing

internal val DetailSeriesIdentity = SemanticsPropertyKey<StatisticsSeriesId>("Statistics Series identity")
internal val DetailFieldIdentity = SemanticsPropertyKey<StatisticsFieldId>("Statistics Field identity")
internal val DetailOptionIdentity =
    SemanticsPropertyKey<StatisticsCategoryOptionId>("Statistics Category option identity")

@Composable
internal fun StatisticsSeriesDetailRoute(
    controller: StatisticsSeriesDetailController,
    isRouteVisible: Boolean = true,
    ownsRoute: () -> Boolean = { true },
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val admission = rememberStatisticsActionAdmission(state, { controller.state.value }, isRouteVisible, ownsRoute)
    val back = admission.action(onClick = onBack)
    BackHandler(enabled = admission.isCurrent()) { back() }
    key(controller) {
        StatisticsSeriesDetailScreen(state, controller::selectPeriod, controller::refresh, onBack, admission)
    }
}

@Composable
internal fun StatisticsSeriesDetailScreen(
    state: StatisticsSeriesDetailPresentationState,
    onSelectPeriod: (StatisticsPeriod) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    admission: StatisticsActionAdmission = rememberStatisticsActionAdmission(state, { state }),
) {
    val editor = remember(state.selectedPeriod) { StatisticsPeriodEditor(state.selectedPeriod) }
    val periodFocus = remember { StatisticsPeriodFocus() }
    val focus = remember { StatisticsOverviewFocus() }
    val list = rememberLazyListState()
    val detail = (state.load as? StatisticsSeriesDetailLoadState.Content)?.detail
    val rows = remember(detail?.let(::StatisticsIdentityKey)) { StatisticsDetailRows(detail) }
    RestoreStatisticsDetailFocus(state, rows, list, admission, focus)
    LifeTracingAccessibleTargets {
        Surface(color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().testTag("statistics-detail-list"),
                contentPadding = PaddingValues(MaterialTheme.spacing.large),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                item(DetailAnchor.HEADER) {
                    StatisticsHeader(
                        R.string.statistics_detail_title,
                        "statistics-detail",
                        admission,
                        onRefresh,
                        onBack,
                        focus.target(DetailAnchor.HEADER).focusGroup(),
                    )
                }
                item(DetailAnchor.STATUS) {
                    DetailStatus(state, admission, focus, onRefresh)
                }
                item(DetailAnchor.PERIOD) {
                    StatisticsPeriodControls(
                        editor,
                        periodFocus,
                        admission,
                        "statistics-detail",
                        onSelectPeriod,
                        focus.target(DetailAnchor.PERIOD).focusGroup(),
                    )
                }
                if (detail != null) detailResults(detail, rows, focus, admission)
            }
        }
    }
}

private fun LazyListScope.detailResults(
    detail: StatisticsSeriesDetail,
    rows: StatisticsDetailRows,
    focus: StatisticsOverviewFocus,
    admission: StatisticsActionAdmission,
) {
    item(DetailAnchor.SERIES) { SeriesHeading(detail.seriesSummary(), focus, admission) }
    item(DetailAnchor.METRICS) {
        DetailSectionHeading(R.string.statistics_detail_metrics, DetailAnchor.METRICS, focus, admission)
    }
    items(rows.metrics, key = { it }) { metric ->
        Text(seriesMetric(detail, metric), detailReadTarget(focus, metric, admission))
    }
    if (rows.fields.isNotEmpty()) {
        item(DetailAnchor.FIELDS) {
            DetailSectionHeading(R.string.statistics_field_title, DetailAnchor.FIELDS, focus, admission)
        }
        items(rows.fields, key = { it.key }, contentType = { it is DetailFieldRow.Option }) { row ->
            when (row) {
                is DetailFieldRow.Field -> FieldContent(row, focus, admission)
                is DetailFieldRow.Option -> CategoryContent(row, focus, admission)
            }
        }
    }
}

@Composable
private fun DetailStatus(
    state: StatisticsSeriesDetailPresentationState,
    admission: StatisticsActionAdmission,
    focus: StatisticsOverviewFocus,
    onRefresh: () -> Unit,
) {
    val (message, tag) =
        when (state.load) {
            StatisticsSeriesDetailLoadState.Loading -> R.string.statistics_loading to "statistics-detail-loading"
            StatisticsSeriesDetailLoadState.Failure -> R.string.statistics_failure to "statistics-detail-failure"
            is StatisticsSeriesDetailLoadState.Content -> R.string.statistics_loaded to "statistics-detail-content"
        }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        StatisticsResultStatus(
            message,
            statisticsPeriodDescription(state.selectedPeriod),
            detailReadTarget(focus, DetailAnchor.STATUS, admission).testTag(tag),
        )
        if (state.load == StatisticsSeriesDetailLoadState.Failure) {
            StatisticsSecondaryButton(
                admission,
                onRefresh,
                focus.target(DetailAnchor.RETRY).testTag("statistics-detail-retry"),
            ) {
                Text(stringResource(R.string.statistics_retry))
            }
        }
    }
}

@Composable
private fun SeriesHeading(
    series: StatisticsSeriesSummary,
    focus: StatisticsOverviewFocus,
    admission: StatisticsActionAdmission,
) {
    val kind =
        when (series.kind) {
            StatisticsSeriesKind.ACTIVITY -> R.string.statistics_kind_activity
            StatisticsSeriesKind.SEQUENCE -> R.string.statistics_kind_sequence
            StatisticsSeriesKind.ONE_OFF_BUCKET -> R.string.statistics_kind_one_off
        }
    val source =
        when (series.sourceState) {
            StatisticsSeriesSourceState.ACTIVE_SOURCE -> R.string.statistics_source_active
            StatisticsSeriesSourceState.ARCHIVED_SOURCE -> R.string.statistics_source_archived
            StatisticsSeriesSourceState.NO_CURRENT_SOURCE -> R.string.statistics_source_missing
            StatisticsSeriesSourceState.SYSTEM_ONE_OFF -> R.string.statistics_source_system
        }
    Column(
        detailReadTarget(focus, DetailAnchor.SERIES, admission)
            .testTag("statistics-detail-series-${series.id.value}")
            .semantics(mergeDescendants = true) {
                heading()
                this[DetailSeriesIdentity] = series.id
            },
    ) {
        Text(series.displayName, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.statistics_detail_kind, stringResource(kind)))
        Text(stringResource(source))
    }
}

@Composable
private fun DetailSectionHeading(
    label: Int,
    anchor: DetailAnchor,
    focus: StatisticsOverviewFocus,
    admission: StatisticsActionAdmission,
) {
    Text(
        stringResource(label),
        detailReadTarget(focus, anchor, admission).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
internal fun detailReadTarget(
    focus: StatisticsOverviewFocus,
    id: Any,
    admission: StatisticsActionAdmission,
    context: String? = null,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    return focus
        .target(id)
        .onFocusChanged { focused = it.isFocused }
        .focusProperties { canFocus = admission.isCurrent() }
        .focusable(enabled = admission.isCurrent())
        .fillMaxWidth()
        .then(if (focused) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
        .semantics { if (context != null) stateDescription = context }
}

private fun StatisticsSeriesDetail.seriesSummary(): StatisticsSeriesSummary =
    when (this) {
        is StatisticsSeriesDetail.Activity -> statistics.series
        is StatisticsSeriesDetail.Sequence -> statistics.series
    }
