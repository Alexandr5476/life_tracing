package com.alexandr5476.lifetracing.statistics

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.alexandr5476.lifetracing.DailyRoot
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.SettingsRoot
import com.alexandr5476.lifetracing.StatisticsRoot
import com.alexandr5476.lifetracing.StatisticsSeriesDetailRoot
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.GlobalStatistics
import com.alexandr5476.lifetracing.domain.StatisticsOverview
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesPeriodSummary
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSummary
import com.alexandr5476.lifetracing.handleStatisticsSeriesDetailBack
import com.alexandr5476.lifetracing.openStatisticsSeriesDetail
import com.alexandr5476.lifetracing.ownsStatisticsSeriesDetail
import com.alexandr5476.lifetracing.removeStatistics
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class StatisticsScreenPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun existingCustomRangeIsVisibleAndCanBeReapplied() {
        val period = StatisticsPeriod.Custom(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-10"))
        val selected = mutableListOf<StatisticsPeriod>()
        compose.setContent {
            LifeTracingTheme { StatisticsScreen(StatisticsPresentationState(period), { selected += it }, {}) }
        }
        compose.onNodeWithTag("statistics-custom-start").assertTextContains("2026-08-03")
        compose.onNodeWithTag("statistics-custom-end").assertTextContains("2026-08-10")
        compose.onNodeWithTag("statistics-custom-apply").performClick()
        assertEquals(listOf(period), selected)
    }

    @Test
    fun systemOneOffHeadingUsesRussianResourceInsteadOfPersistedEnglishName() {
        val configuration =
            Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("ru"))
            }
        val russianContext = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides russianContext, LocalConfiguration provides configuration) {
                LifeTracingTheme {
                    StatisticsScreen(
                        StatisticsPresentationState(
                            StatisticsPeriod.AllTime,
                            StatisticsLoadState.Content(
                                overview(
                                    listOf(
                                        summary(
                                            "system",
                                            StatisticsSeriesKind.ONE_OFF_BUCKET,
                                            StatisticsSeriesSourceState.SYSTEM_ONE_OFF,
                                            1,
                                            0,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        {},
                        {},
                    )
                }
            }
        }
        compose.onNodeWithTag("statistics-series-system").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(russianContext.getString(R.string.statistics_kind_one_off)).assertCountEquals(2)
        compose.onAllNodesWithText("One-off activities").assertCountEquals(0)
    }

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
        compose.onNodeWithTag("statistics-series-zero-activity").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("statistics-series-zero-one-off").performScrollTo().assertIsDisplayed()
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
            compose.onNodeWithTag("statistics-series-$it").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Display activity").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag("statistics-series-archived")
            .performScrollTo()
            .assertTextContains(
                compose.activity.getString(
                    R.string.statistics_duration_total,
                    compose.activity.getString(R.string.statistics_no_duration_samples),
                ),
            )
        compose
            .onNodeWithTag("statistics-series-sampled-zero")
            .performScrollTo()
            .assertTextContains(compose.activity.getString(R.string.statistics_duration_total, "0:00:00"))
        compose
            .onNodeWithTag("statistics-series-archived")
            .performScrollTo()
            .assertTextContains(compose.activity.getString(R.string.statistics_source_archived))
        compose
            .onNodeWithTag("statistics-series-missing")
            .performScrollTo()
            .assertTextContains(compose.activity.getString(R.string.statistics_source_missing))
        compose
            .onNodeWithText(compose.activity.getString(R.string.statistics_source_system))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun activityAndSequenceRowsPassStableIdsAndSelectedPeriodButOneOffHasNoAction() {
        val period = StatisticsPeriod.Custom(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-09"))
        val state =
            mutableStateOf(StatisticsPresentationState(period, StatisticsLoadState.Content(overview(seriesRows))))
        val opened = mutableListOf<Pair<StatisticsSeriesId, StatisticsPeriod>>()
        var refreshed = 0
        compose.setContent {
            LifeTracingTheme {
                StatisticsScreen(state.value, {}, {}, onRefresh = { refreshed++ }, onOpenSeries = { id, selected ->
                    opened +=
                        id to selected
                })
            }
        }
        compose.onNodeWithTag("statistics-series-activity").performScrollTo().performClick()
        compose.onNodeWithTag("statistics-series-sequence").performScrollTo().performClick()
        compose.onNodeWithTag("statistics-series-system").assertHasNoClickAction()
        compose.onNodeWithTag("statistics-refresh").performScrollTo().performClick()
        assertEquals(listOf(StatisticsSeriesId("activity") to period, StatisticsSeriesId("sequence") to period), opened)
        assertEquals(1, refreshed)
    }

    @Test
    fun nativeActionsRetireAfterEqualPublicationAndLifecyclePause() {
        val period = StatisticsPeriod.AllTime
        val state =
            mutableStateOf(
                StatisticsPresentationState(period, StatisticsLoadState.Content(overview(seriesRows))),
                referentialEqualityPolicy(),
            )
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        val selected = mutableListOf<StatisticsPeriod>()
        compose.setContent {
            LifeTracingTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    val presentation = state.value
                    StatisticsScreen(
                        presentation,
                        { selected += it },
                        {},
                        admission = rememberStatisticsActionAdmission(presentation, { state.value }),
                    )
                }
            }
        }
        val original = nativeAction("statistics-period-year")
        compose.runOnIdle {
            val equalPublication = state.value.copy()
            assertEquals(state.value, equalPublication)
            state.value = equalPublication
            assertFalse(original())
        }
        val beforePause = nativeAction("statistics-period-year")
        compose.runOnIdle {
            assertFalse(original())
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            assertFalse(beforePause())
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            assertFalse(beforePause())
            assertTrue(selected.isEmpty())
        }
        compose.runOnIdle { assertFalse(beforePause()) }
        val resumed = nativeAction("statistics-period-year")
        compose.runOnIdle {
            assertTrue(resumed())
            assertEquals(1, selected.size)
        }
    }

    @Test
    @Suppress("LongMethod")
    fun retainedNativeActionsRespectNavigationAndSameSeriesSessionReplacement() {
        val routes = RouteFixture()
        routes.setContent()
        val earlyActions = routes.overviewActions() + nativeAction("statistics-retry")
        val retry = earlyActions.last()
        compose.runOnIdle {
            routes.failOverview = false
            assertTrue(retry())
            earlyActions.forEach { assertFalse(it()) }
            assertEquals(listOf(routes.period, routes.period), routes.overviewReads)
            assertSame(StatisticsRoot, routes.stack.last())
            assertNull(routes.sessions.activeSession)
        }

        compose.onNodeWithTag("statistics-series-system").assertHasNoClickAction()
        val activity = nativeAction("statistics-series-activity")
        val hiddenOverview = routes.overviewActions() + activity + nativeAction("statistics-series-sequence")
        val draft = nativeAction("statistics-period-custom")
        compose.runOnIdle {
            assertTrue(draft())
            assertTrue(activity())
            hiddenOverview.forEach { assertFalse(it()) }
            val session = requireNotNull(routes.sessions.activeSession)
            assertEquals(StatisticsSeriesId("activity"), session.expectedSeriesId)
            assertEquals(routes.period, session.initialPeriod)
            assertEquals(listOf(StatisticsSeriesId("activity") to routes.period), routes.detailAcquisitions)
        }

        val obsoleteDetail = routes.detailActions()
        val retryDetail = obsoleteDetail.last()
        compose.runOnIdle {
            val session = requireNotNull(routes.sessions.activeSession)
            val obsoletePublication = session.controller.state.value
            assertTrue(retryDetail())
            assertTrue(obsoletePublication !== session.controller.state.value)
            obsoleteDetail.forEach { assertFalse(it()) }
            assertSame(session, routes.sessions.activeSession)
            assertEquals(2, routes.detailReads.size)
        }

        val priorSessionActions = routes.detailActions()
        compose.runOnIdle {
            val session = requireNotNull(routes.sessions.activeSession)
            val route = routes.stack.last()
            routes.sessions.release(session)
            val replacement = routes.sessions.acquire(session.expectedSeriesId, routes.period, routes.createDetail)
            priorSessionActions.forEach { assertFalse(it()) }
            assertSame(replacement, routes.sessions.activeSession)
            assertSame(route, routes.stack.last())
            assertEquals(3, routes.detailReads.size)
        }
        val hiddenDetail = routes.detailActions()
        compose.runOnIdle {
            routes.stack.add(SettingsRoot)
            hiddenDetail.forEach { assertFalse(it()) }
            assertEquals(3, routes.detailReads.size)
        }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.runOnIdle { routes.stack.removeAt(routes.stack.lastIndex) }
        val periodChange = nativeAction("statistics-detail-period-all_time")
        compose.runOnIdle {
            hiddenDetail.forEach { assertFalse(it()) }
            priorSessionActions.forEach { assertFalse(it()) }
            assertTrue(periodChange())
            assertEquals(StatisticsPeriod.AllTime, routes.detailReads.last().second)
        }
        val refresh = nativeAction("statistics-detail-refresh")
        compose.runOnIdle {
            assertTrue(refresh())
            assertEquals(StatisticsPeriod.AllTime, routes.detailReads.last().second)
        }
        val outgoingDetail = routes.detailActions()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("statistics-refresh").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            hiddenOverview.forEach { assertFalse(it()) }
            outgoingDetail.forEach { assertFalse(it()) }
            assertNull(routes.sessions.activeSession)
            assertSame(StatisticsRoot, routes.stack.last())
            assertEquals(routes.period, routes.overviewController.state.value.selectedPeriod)
            assertEquals(1, routes.overviewAcquisitions)
        }

        val sequence = nativeAction("statistics-series-sequence")
        compose.runOnIdle {
            assertTrue(sequence())
            assertEquals(StatisticsSeriesId("sequence") to routes.period, routes.detailAcquisitions.last())
        }
        compose.onNodeWithTag("statistics-detail-back").performClick()
        val selectYear = nativeAction("statistics-period-year")
        compose.runOnIdle {
            assertTrue(selectYear())
            assertEquals(StatisticsPeriod.Year(java.time.Year.of(2026)), routes.overviewReads.last())
        }
        val overviewBack = nativeAction("statistics-back")
        val outgoingOverview = routes.overviewActions() + nativeAction("statistics-series-activity")
        compose.runOnIdle {
            assertTrue(overviewBack())
            outgoingOverview.forEach { assertFalse(it()) }
            assertSame(DailyRoot, routes.stack.last())
            assertNull(routes.sessions.activeSession)
        }
        compose.waitForIdle()
        assertEquals(1, routes.overviewAcquisitions)
        assertEquals(3, routes.detailAcquisitions.size)
    }

    private fun nativeAction(tag: String): () -> Boolean =
        requireNotNull(
            compose
                .onNodeWithTag(tag)
                .performScrollTo()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action,
        )

    private inner class RouteFixture {
        val stack = mutableStateListOf<NavKey>(DailyRoot, StatisticsRoot)
        val period = StatisticsPeriod.Month(YearMonth.of(2026, 8))
        val sessions = StatisticsSeriesDetailRouteSessionOwner()
        val overviewReads = mutableListOf<StatisticsPeriod>()
        val detailReads = mutableListOf<Pair<StatisticsSeriesId, StatisticsPeriod>>()
        val detailAcquisitions = mutableListOf<Pair<StatisticsSeriesId, StatisticsPeriod>>()
        private val scope = CoroutineScope(Dispatchers.Unconfined)
        private val owner = StatisticsControllerOwner()
        var failOverview = true
        var overviewAcquisitions = 0
            private set
        val overviewController get() = owner.get { createOverview() }
        val createDetail = { id: StatisticsSeriesId, initial: StatisticsPeriod ->
            detailAcquisitions += id to initial
            StatisticsSeriesDetailController(scope, id, initial) { series, selected ->
                detailReads += series to selected
                error("Detail read failure")
            }
        }

        private fun createOverview(): StatisticsController {
            overviewAcquisitions++
            return StatisticsController(scope, period) { selected ->
                overviewReads += selected
                check(!failOverview)
                val rows =
                    seriesRows.take(2).map { it.copy(series = it.series.copy(displayName = "Duplicate name")) }
                overview(rows + seriesRows.single { it.series.kind == StatisticsSeriesKind.ONE_OFF_BUCKET })
            }
        }

        fun overviewActions() =
            listOf(
                "statistics-period-day",
                "statistics-next",
                "statistics-refresh",
                "statistics-back",
            ).map(::nativeAction)

        fun detailActions() =
            listOf(
                "statistics-detail-period-year",
                "statistics-detail-refresh",
                "statistics-detail-back",
                "statistics-detail-retry",
            ).map(::nativeAction)

        fun setContent() =
            compose.setContent {
                LifeTracingTheme {
                    NavDisplay(
                        backStack = stack,
                        onBack = {
                            val route = stack.last() as StatisticsSeriesDetailRoot
                            val session = requireNotNull(sessions.sessionFor(StatisticsSeriesId(route.seriesId)))
                            stack.handleStatisticsSeriesDetailBack(route, session, sessions)
                        },
                        entryProvider =
                            entryProvider {
                                entry<DailyRoot> { Text("Daily") }
                                entry<SettingsRoot> { Text("Settings") }
                                entry<StatisticsRoot> {
                                    val visible = stack.lastOrNull() === StatisticsRoot
                                    val controller =
                                        owner.controllerForRoute(visible) { createOverview() } ?: return@entry
                                    StatisticsRoute(
                                        controller,
                                        onOpenSeries = { id, selected ->
                                            stack.openStatisticsSeriesDetail(id, selected, sessions, createDetail)
                                        },
                                        isRouteVisible = visible,
                                        ownsRoute = { stack.lastOrNull() === StatisticsRoot && owner.owns(controller) },
                                        onBack = {
                                            owner.onRouteExited()
                                            stack.removeStatistics()
                                        },
                                    )
                                }
                                entry<StatisticsSeriesDetailRoot> { route ->
                                    val id = StatisticsSeriesId(route.seriesId)
                                    val session = sessions.sessionFor(id) ?: return@entry
                                    StatisticsSeriesDetailRoute(
                                        session.controller,
                                        isRouteVisible = stack.lastOrNull() === route,
                                        ownsRoute = { stack.ownsStatisticsSeriesDetail(route, session, sessions) },
                                        onBack = { stack.handleStatisticsSeriesDetailBack(route, session, sessions) },
                                    )
                                }
                            },
                    )
                }
            }
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
            if (kind == StatisticsSeriesKind.ONE_OFF_BUCKET) "One-off activities" else "Display $id",
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
