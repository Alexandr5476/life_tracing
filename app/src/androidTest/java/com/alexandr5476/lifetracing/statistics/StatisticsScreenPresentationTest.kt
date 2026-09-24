package com.alexandr5476.lifetracing.statistics

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.GlobalStatistics
import com.alexandr5476.lifetracing.domain.StatisticsOverview
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesPeriodSummary
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSummary
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class StatisticsScreenPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loading_failure_retry_empty_and_zero_count_series_are_presented() {
        val state = mutableStateOf(StatisticsPresentationState(StatisticsPeriod.Month(YearMonth.of(2026, 8))))
        var retries = 0
        compose.setContent { LifeTracingTheme { StatisticsScreen(state.value, {}, { retries++ }) } }
        compose.onNodeWithTag("statistics-loading").assertIsDisplayed()
        state.value = state.value.copy(load = StatisticsLoadState.Failure)
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_failure)).assertIsDisplayed()
        compose.onNodeWithTag("statistics-retry").performClick()
        assertEquals(1, retries)
        state.value = state.value.copy(load = StatisticsLoadState.Empty(overview(zeroSeries)))
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_empty)).assertIsDisplayed()
        compose.onNodeWithTag("statistics-series-zero-activity").assertIsDisplayed()
        compose.onNodeWithTag("statistics-series-zero-one-off").assertIsDisplayed()
    }

    @Test
    fun canonical_series_metadata_and_duration_sample_states_are_shown() {
        val state =
            mutableStateOf(
                StatisticsPresentationState(
                    StatisticsPeriod.Month(YearMonth.of(2026, 8)),
                    StatisticsLoadState.Content(overview(seriesRows)),
                ),
            )
        compose.setContent { LifeTracingTheme { StatisticsScreen(state.value, {}, {}) } }
        listOf("activity", "sequence", "archived", "missing", "system", "unsampled", "sampled-zero").forEach {
            compose.onNodeWithTag("statistics-series-$it").assertIsDisplayed()
        }
        compose.onNodeWithText("Display activity").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_no_duration_samples)).assertIsDisplayed()
        compose.onNodeWithText("0:00:00").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_source_archived)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_source_missing)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_source_system)).assertIsDisplayed()
    }

    @Test
    fun all_period_kinds_dispatch_values_and_week_is_monday_anchored() {
        val initial = StatisticsPeriod.Month(YearMonth.of(2026, 8))
        val state = mutableStateOf(StatisticsPresentationState(initial))
        val dispatched = mutableListOf<StatisticsPeriod>()
        compose.setContent { LifeTracingTheme { StatisticsScreen(state.value, { dispatched += it }, {}) } }
        val label = { id: Int -> compose.activity.getString(id) }
        compose.onNodeWithText(label(R.string.statistics_period_day)).performClick()
        compose.onNodeWithTag("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Day(LocalDate.parse("2026-08-01")), dispatched.last())
        compose.onNodeWithText(label(R.string.statistics_period_week)).performClick()
        compose.onNodeWithTag("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Week(LocalDate.parse("2026-07-27")), dispatched.last())
        compose.onNodeWithText(label(R.string.statistics_period_month)).performClick()
        compose.onNodeWithTag("statistics-period-apply").performClick()
        assertEquals(initial, dispatched.last())
        compose.onNodeWithText(label(R.string.statistics_period_year)).performClick()
        compose.onNodeWithTag("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Year(java.time.Year.of(2026)), dispatched.last())
        compose.onNodeWithText(label(R.string.statistics_period_custom)).performClick()
        compose.onNodeWithTag("statistics-custom-start").performTextInput("2026-08-03")
        compose.onNodeWithTag("statistics-custom-end").performTextInput("2026-08-10")
        compose.onNodeWithTag("statistics-custom-apply").performClick()
        assertEquals(
            StatisticsPeriod.Custom(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-10")),
            dispatched.last(),
        )
        compose.onNodeWithText(label(R.string.statistics_period_all_time)).performClick()
        assertEquals(StatisticsPeriod.AllTime, dispatched.last())
        assertEquals(6, dispatched.map { it::class }.distinct().size)
    }

    @Test
    fun malformed_custom_dates_do_not_dispatch() {
        val state = mutableStateOf(StatisticsPresentationState(StatisticsPeriod.Month(YearMonth.of(2026, 8))))
        val dispatched = mutableListOf<StatisticsPeriod>()
        compose.setContent { LifeTracingTheme { StatisticsScreen(state.value, { dispatched += it }, {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_period_custom)).performClick()
        compose.onNodeWithTag("statistics-custom-start").performTextInput("bad")
        compose.onNodeWithTag("statistics-custom-end").performTextInput("2026-08-01")
        compose.onNodeWithTag("statistics-custom-apply").performClick()
        assertTrue(dispatched.isEmpty())
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_invalid_range)).assertIsDisplayed()
    }

    @Test
    fun reversed_custom_dates_do_not_dispatch() {
        val state = mutableStateOf(StatisticsPresentationState(StatisticsPeriod.Month(YearMonth.of(2026, 8))))
        val dispatched = mutableListOf<StatisticsPeriod>()
        compose.setContent { LifeTracingTheme { StatisticsScreen(state.value, { dispatched += it }, {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_period_custom)).performClick()
        compose.onNodeWithTag("statistics-custom-apply").performClick()
        assertTrue(dispatched.isEmpty())
        compose.onNodeWithTag("statistics-custom-start").performTextInput("2026-08-10")
        compose.onNodeWithTag("statistics-custom-end").performTextInput("2026-08-01")
        compose.onNodeWithTag("statistics-custom-apply").performClick()
        assertTrue(dispatched.isEmpty())
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_invalid_range)).assertIsDisplayed()
    }

    private fun overview(rows: List<StatisticsSeriesPeriodSummary>) =
        StatisticsOverview(
            GlobalStatistics(
                Duration.ZERO,
                rows.sumOf {
                    it.executionCount
                },
                0,
                null,
                null,
                Duration.ZERO,
                0,
                Duration.ZERO,
                null,
                null,
                null,
                null,
            ),
            rows,
        )

    private val zeroSeries =
        listOf(
            summary("zero-activity", StatisticsSeriesKind.ACTIVITY, StatisticsSeriesSourceState.ACTIVE_SOURCE, 0, 0),
            summary(
                "zero-one-off",
                StatisticsSeriesKind.ONE_OFF_BUCKET,
                StatisticsSeriesSourceState.SYSTEM_ONE_OFF,
                0,
                0,
            ),
        )
    private val seriesRows =
        listOf(
            summary("activity", StatisticsSeriesKind.ACTIVITY, StatisticsSeriesSourceState.ACTIVE_SOURCE, 1, 1),
            summary("sequence", StatisticsSeriesKind.SEQUENCE, StatisticsSeriesSourceState.ACTIVE_SOURCE, 1, 1),
            summary("archived", StatisticsSeriesKind.ACTIVITY, StatisticsSeriesSourceState.ARCHIVED_SOURCE, 1, 0),
            summary("missing", StatisticsSeriesKind.ACTIVITY, StatisticsSeriesSourceState.NO_CURRENT_SOURCE, 1, 0),
            summary("system", StatisticsSeriesKind.ONE_OFF_BUCKET, StatisticsSeriesSourceState.SYSTEM_ONE_OFF, 1, 0),
            summary("unsampled", StatisticsSeriesKind.ACTIVITY, StatisticsSeriesSourceState.ACTIVE_SOURCE, 2, 0),
            summary("sampled-zero", StatisticsSeriesKind.ACTIVITY, StatisticsSeriesSourceState.ACTIVE_SOURCE, 1, 1),
        )

    private fun summary(
        id: String,
        kind: StatisticsSeriesKind,
        source: StatisticsSeriesSourceState,
        count: Long,
        samples: Long,
    ) = StatisticsSeriesPeriodSummary(
        StatisticsSeriesSummary(
            StatisticsSeriesId(id),
            kind,
            "Display $id",
            if (source ==
                StatisticsSeriesSourceState.ARCHIVED_SOURCE
            ) {
                Instant.EPOCH
            } else {
                null
            },
            source,
        ),
        count,
        samples,
        Duration.ZERO,
        if (samples == 0L) null else ExactValue.of(0, 1),
        if (count == 0L) 0 else 1,
    )
}
