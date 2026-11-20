package com.alexandr5476.lifetracing.statistics

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
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
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun existingCustomRangeIsVisibleAndCanBeReapplied() {
        val period = StatisticsPeriod.Custom(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-10"))
        val selected = mutableListOf<StatisticsPeriod>()
        compose.setContent {
            LifeTracingTheme { StatisticsScreen(StatisticsPresentationState(period), { selected += it }, {}) }
        }
        compose.onNodeWithTag("statistics-custom-start").assertTextContains("2026-08-03")
        compose.onNodeWithTag("statistics-custom-end").assertTextContains("2026-08-10")
        overviewNode("statistics-custom-apply").performClick()
        assertEquals(listOf(period), selected)
    }

    @Test
    fun systemOneOffHeadingUsesRussianResourceInsteadOfPersistedEnglishName() {
        val longName = "Очень длинное название серии для проверки переноса строк при увеличенном размере текста"
        val activity =
            summary("long-name", StatisticsSeriesKind.ACTIVITY, StatisticsSeriesSourceState.ACTIVE_SOURCE, 1, 0)
        val configuration =
            Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("ru"))
                fontScale = 1.5f
            }
        val russianContext = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            val systemDensity = Density(LocalDensity.current.density, configuration.fontScale)
            CompositionLocalProvider(
                LocalContext provides russianContext,
                LocalConfiguration provides configuration,
                LocalDensity provides systemDensity,
            ) {
                LifeTracingAppearance(AppearancePreferences(interfaceScalePercent = 120, textScalePercent = 130)) {
                    StatisticsScreen(
                        StatisticsPresentationState(
                            StatisticsPeriod.AllTime,
                            StatisticsLoadState.Content(
                                overview(
                                    listOf(
                                        activity.copy(series = activity.series.copy(displayName = longName)),
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
        series("long-name").assertTextContains(longName).assertIsDisplayed()
        series("system").assertIsDisplayed()
        compose.onAllNodesWithText(russianContext.getString(R.string.statistics_kind_one_off)).assertCountEquals(1)
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
        series("zero-activity").assertIsDisplayed()
        series("zero-one-off").assertIsDisplayed()
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
            series(it).assertIsDisplayed()
        }
        series("activity").assertTextContains("Display activity")
        series("archived")
            .assertTextContains(
                compose.activity.getString(
                    R.string.statistics_duration_total,
                    compose.activity.getString(R.string.statistics_no_duration_samples),
                ),
            )
        series("sampled-zero")
            .assertTextContains(compose.activity.getString(R.string.statistics_duration_total, "0:00:00"))
        series("archived")
            .assertTextContains(compose.activity.getString(R.string.statistics_source_archived))
        series("missing")
            .assertTextContains(compose.activity.getString(R.string.statistics_source_missing))
        series("system").assertTextContains(compose.activity.getString(R.string.statistics_source_system))
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
        series("activity").performClick()
        series("sequence").performClick()
        series("system").assertHasNoClickAction()
        overviewNode("statistics-refresh").performClick()
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

        series("system").assertHasNoClickAction()
        val hiddenOverview = routes.overviewActions() + nativeAction("statistics-series-sequence")
        val boundedApply = nativeAction("statistics-period-apply")
        val draft = nativeAction("statistics-period-custom")
        compose.runOnIdle {
            assertTrue(draft())
            assertFalse(boundedApply())
        }
        val activity = nativeAction("statistics-series-activity")
        compose.runOnIdle {
            assertTrue(activity())
            assertFalse(activity())
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
        overviewNode("statistics-refresh").assertIsDisplayed()
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
        val outgoingOverview = routes.overviewActions() + nativeAction("statistics-series-activity")
        val overviewBack = nativeAction("statistics-back")
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

    private fun series(id: String) =
        compose
            .onNodeWithTag("statistics-list")
            .performScrollToKey(StatisticsSeriesItemKey(StatisticsSeriesId(id)))
            .let { compose.onNodeWithTag("statistics-series-$id") }

    private fun overviewNode(tag: String) =
        if (tag.startsWith("statistics-series-")) {
            series(tag.removePrefix("statistics-series-"))
        } else {
            val key =
                when (tag) {
                    "statistics-refresh", "statistics-back" -> StatisticsOverviewItem.HEADER
                    "statistics-retry", "statistics-loading", "statistics-failure" -> StatisticsOverviewItem.STATUS
                    "statistics-content", "statistics-empty" -> StatisticsOverviewItem.STATUS
                    else -> StatisticsOverviewItem.PERIOD
                }
            compose.onNodeWithTag("statistics-list").performScrollToKey(key)
            compose.onNodeWithTag(tag).performScrollTo()
        }

    private fun nativeAction(tag: String): () -> Boolean {
        val node =
            if (tag.startsWith("statistics-detail-")) {
                compose.onNodeWithTag(tag).performScrollTo()
            } else {
                overviewNode(tag)
            }
        return requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    }

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
                                        returnFocus = owner.returnFocus,
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
        compose.onNodeWithText(label(R.string.statistics_period_day)).performScrollTo().performClick()
        overviewNode("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Day(LocalDate.parse("2026-08-01")), dispatched.last())
        compose.onNodeWithText(label(R.string.statistics_period_week)).performScrollTo().performClick()
        overviewNode("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Week(LocalDate.parse("2026-07-27")), dispatched.last())
        compose.onNodeWithText(label(R.string.statistics_period_month)).performScrollTo().performClick()
        overviewNode("statistics-period-apply").performClick()
        assertEquals(initial, dispatched.last())
        compose.onNodeWithText(label(R.string.statistics_period_year)).performScrollTo().performClick()
        overviewNode("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Year(java.time.Year.of(2026)), dispatched.last())
        compose.onNodeWithText(label(R.string.statistics_period_custom)).performScrollTo().performClick()
        overviewNode("statistics-custom-start").performTextInput("2026-08-03")
        overviewNode("statistics-custom-end").performTextInput("2026-08-10")
        overviewNode("statistics-custom-apply").performClick()
        assertEquals(
            StatisticsPeriod.Custom(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-10")),
            dispatched.last(),
        )
        compose.onNodeWithText(label(R.string.statistics_period_all_time)).performScrollTo().performClick()
        assertEquals(StatisticsPeriod.AllTime, dispatched.last())
        assertEquals(6, dispatched.map { it::class }.distinct().size)
    }

    @Test
    fun malformed_custom_dates_do_not_dispatch() {
        val state = mutableStateOf(StatisticsPresentationState(StatisticsPeriod.Month(YearMonth.of(2026, 8))))
        val dispatched = mutableListOf<StatisticsPeriod>()
        compose.setContent { LifeTracingTheme { StatisticsScreen(state.value, { dispatched += it }, {}) } }
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.statistics_period_custom),
            ).performScrollTo()
            .performClick()
        overviewNode("statistics-custom-start").performTextInput("bad")
        overviewNode("statistics-custom-end").performTextInput("2026-08-01")
        overviewNode("statistics-custom-apply").performClick()
        assertTrue(dispatched.isEmpty())
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_invalid_range)).assertIsDisplayed()
    }

    @Test
    fun reversed_custom_dates_do_not_dispatch() {
        val state = mutableStateOf(StatisticsPresentationState(StatisticsPeriod.Month(YearMonth.of(2026, 8))))
        val dispatched = mutableListOf<StatisticsPeriod>()
        compose.setContent { LifeTracingTheme { StatisticsScreen(state.value, { dispatched += it }, {}) } }
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.statistics_period_custom),
            ).performScrollTo()
            .performClick()
        overviewNode("statistics-custom-apply").performClick()
        assertTrue(dispatched.isEmpty())
        overviewNode("statistics-custom-start").performTextInput("2026-08-10")
        overviewNode("statistics-custom-end").performTextInput("2026-08-01")
        overviewNode("statistics-custom-apply").performClick()
        assertTrue(dispatched.isEmpty())
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_invalid_range)).assertIsDisplayed()
    }

    @Test
    fun offscreenDuplicateSeriesAreLazyAndKeyboardActivatesTheExactNextId() {
        val rows = largeCatalog()
        val period = StatisticsPeriod.AllTime
        val reads = mutableListOf<StatisticsPeriod>()
        val opens = mutableListOf<Pair<StatisticsSeriesId, StatisticsPeriod>>()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller =
            StatisticsController(scope, period) {
                reads += it
                overview(rows)
            }
        try {
            compose.setContent {
                LifeTracingTheme {
                    StatisticsRoute(controller, onOpenSeries = {
                        id,
                        selected,
                        ->
                        opens += id to selected
                    }) {}
                }
            }
            compose.onNodeWithTag("statistics-series-catalog-200").assertDoesNotExist()
            keyboard.enter()
            val scrolledAwayAction = nativeAction("statistics-series-catalog-20")
            series(
                "catalog-200",
            ).assertTextContains("Duplicate name").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.runOnIdle { assertFalse(scrolledAwayAction()) }
            compose.onNodeWithTag("statistics-series-catalog-212").assertDoesNotExist()
            repeat(12) { press(Key.Tab) }
            compose
                .onNodeWithTag("statistics-series-catalog-212")
                .assertIsFocused()
                .assertIsDisplayed()
                .assertTextContains("Duplicate name")
            press(Key.Enter)
            assertEquals(listOf(StatisticsSeriesId("catalog-212") to period), opens)
            assertEquals(listOf(period), reads)
            compose.onNodeWithTag("statistics-series-catalog-299").assertDoesNotExist()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun refreshRestoresSurvivingRowAndFallsBackForRemovedRow() {
        val rows = largeCatalog()
        val surviving = "catalog-200"
        val reads = mutableListOf<StatisticsPeriod>()
        val firstRefresh = CompletableDeferred<StatisticsOverview>()
        val secondRefresh = CompletableDeferred<StatisticsOverview>()
        val period = StatisticsPeriod.AllTime
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller =
            StatisticsController(scope, period) {
                reads += it
                when (reads.size) {
                    1 -> overview(rows)
                    2 -> firstRefresh.await()
                    else -> secondRefresh.await()
                }
            }
        try {
            compose.setContent { LifeTracingTheme { StatisticsRoute(controller) {} } }
            keyboard.enter()
            series(surviving).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag("statistics-series-$surviving").assertIsFocused()
            compose.runOnIdle { controller.refresh() }
            keyboard.awaitFocus(compose.onNodeWithTag("statistics-loading"))
            compose.runOnIdle { firstRefresh.complete(overview(rows.reversed())) }
            keyboard.awaitFocus(compose.onNodeWithTag("statistics-series-$surviving"))
            compose.runOnIdle { controller.refresh() }
            keyboard.awaitFocus(compose.onNodeWithTag("statistics-loading"))
            compose.runOnIdle { secondRefresh.complete(overview(rows.filterNot { it.series.id.value == surviving })) }
            keyboard.awaitFocus(compose.onNodeWithTag("statistics-content"))
            assertEquals(listOf(period, period, period), reads)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun returnFromDetailRestoresKeyboardFocusToTheExactDuplicateNameRow() {
        val routes = RouteFixture().apply { failOverview = false }
        routes.setContent()
        keyboard.enter()
        series("sequence").performSemanticsAction(SemanticsActions.RequestFocus)
        press(Key.Enter)
        compose.onNodeWithTag("statistics-detail-back").performClick()
        compose.onNodeWithTag("statistics-series-sequence").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(StatisticsSeriesId("sequence") to routes.period), routes.detailAcquisitions)
            assertEquals(listOf(routes.period), routes.overviewReads)
        }
    }

    @Test
    fun publicationStatusAndGlobalMetricsExposeCanonicalContextWithNoRecompositionRead() {
        val period = StatisticsPeriod.Month(YearMonth.of(2026, 8))
        val next = StatisticsPeriod.Month(YearMonth.of(2026, 9))
        val load = CompletableDeferred<StatisticsOverview>()
        val reads = mutableListOf<StatisticsPeriod>()
        val global =
            overview(emptyList()).global.copy(
                totalTrackedDuration = Duration.ofMinutes(10),
                topLevelExecutionCount = 4,
                activeDayCount = 2,
                averageTrackedMillisecondsPerActiveDay = ExactValue.of(300_000, 1),
                totalSequencePauseIdleDuration = Duration.ZERO,
            )
        val unrelated = mutableStateOf(0)
        var backValue = 0
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller =
            StatisticsController(scope, period) {
                reads += it
                if (it == next) load.await() else StatisticsOverview(global, seriesRows)
            }
        try {
            compose.setContent {
                val current = unrelated.value
                LifeTracingTheme { StatisticsRoute(controller) { backValue = current } }
            }
            val month = compose.activity.getString(R.string.statistics_period_month)
            val august =
                compose.activity.getString(
                    R.string.statistics_period_date_range,
                    month,
                    "2026-08-01",
                    "2026-08-31",
                )
            val september =
                compose.activity.getString(
                    R.string.statistics_period_date_range,
                    month,
                    "2026-09-01",
                    "2026-09-30",
                )
            val original = status("statistics-content", august).fetchSemanticsNode().id
            compose.runOnIdle { unrelated.value = 1 }
            assertEquals(original, status("statistics-content", august).fetchSemanticsNode().id)
            compose.onNodeWithTag("statistics-list").performScrollToKey(StatisticsOverviewItem.GLOBAL)
            val metrics =
                mapOf(
                    R.string.statistics_tracked_time to "0:10:00",
                    R.string.statistics_top_level_executions to "4",
                    R.string.statistics_active_days to "2",
                    R.string.statistics_average_per_active_day to "0:05:00",
                    R.string.statistics_sequence_pause_idle to "0:00:00",
                )
            metrics.forEach { (resource, value) ->
                compose.onNodeWithText(compose.activity.getString(resource, value)).assertExists()
            }
            overviewNode("statistics-next").performClick()
            status("statistics-loading", september)
            compose.runOnIdle { load.complete(StatisticsOverview(global, seriesRows)) }
            status("statistics-content", september)
            overviewNode("statistics-back").performClick()
            assertEquals(1, backValue)
            assertEquals(listOf(period, next), reads)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retryFocusMovesToStableStatusWhenLoadingReplacesRetry() {
        val pending = CompletableDeferred<StatisticsOverview>()
        val reads = mutableListOf<StatisticsPeriod>()
        val period = StatisticsPeriod.AllTime
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val controller =
            StatisticsController(scope, period) {
                reads += it
                if (reads.size == 1) error("offline") else pending.await()
            }
        try {
            compose.setContent { LifeTracingTheme { StatisticsRoute(controller) {} } }
            keyboard.enter()
            val retry = overviewNode("statistics-retry")
            retry.performSemanticsAction(SemanticsActions.RequestFocus)
            retry.assertIsFocused()
            press(Key.Enter)
            keyboard.awaitFocus(compose.onNodeWithTag("statistics-loading"))
            compose.runOnIdle { pending.complete(overview(zeroSeries)) }
            compose.onNodeWithTag("statistics-empty").assertIsFocused()
            series("zero-activity").assertTextContains(compose.activity.getString(R.string.statistics_executions, "0"))
            assertEquals(listOf(period, period), reads)
        } finally {
            scope.cancel()
        }
    }

    private fun largeCatalog() =
        List(300) { index ->
            val row =
                summary(
                    "catalog-$index",
                    StatisticsSeriesKind.ACTIVITY,
                    StatisticsSeriesSourceState.ACTIVE_SOURCE,
                    1,
                    0,
                )
            row.copy(series = row.series.copy(displayName = "Duplicate name"))
        }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
    }

    private fun status(
        tag: String,
        period: String,
    ) = overviewNode(tag)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, period))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

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
