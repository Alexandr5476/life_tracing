package com.alexandr5476.lifetracing

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.runtime.RuntimeIsolationApplication
import com.alexandr5476.lifetracing.statistics.DetailFieldIdentity
import com.alexandr5476.lifetracing.statistics.DetailOptionIdentity
import com.alexandr5476.lifetracing.statistics.StatisticsController
import com.alexandr5476.lifetracing.statistics.StatisticsLoadState
import com.alexandr5476.lifetracing.statistics.StatisticsSeriesDetailController
import com.alexandr5476.lifetracing.statistics.StatisticsSeriesDetailLoadState
import com.alexandr5476.lifetracing.statistics.StatisticsSeriesDetailRouteSession
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Duration
import java.util.Locale

/** File-backed history drives the real runtime graph, controller owners and Navigation3 entries. */
class MainActivityStatisticsJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun reopenedHistoryDrivesKeyboardNavigationAndCommittedRefresh() =
        withReopenedHistory { journey ->
            val overview = journey.enterDay()
            val opening = journey.openActivityWithKeyboard(overview)
            journey.assertActivityFacts(opening)
            journey.changePeriodAndRecreate(opening)
            journey.correctAndRefresh(overview, opening)
            journey.deleteAndRefresh(opening)
            journey.inspectSequenceAndNormalizeRestoredRoute(overview)
        }

    @Test
    fun retryAndSupersededReadsHonorTheCurrentProductionEntry() =
        withReopenedHistory { journey ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                journey.retryOverviewAndSupersedeRefresh(scope)
                journey.retryReplacementDetailAndSupersedeFailure(scope)
            } finally {
                scope.cancel()
            }
        }

    private fun withReopenedHistory(body: (Journey) -> Unit) {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<RuntimeIsolationApplication>()
            app.withDisposableRuntime {
                val history = RuntimeRecoveryStore(app, DATABASE).use(StatisticsJourneyHistory::seed)
                RuntimeRecoveryStore(app, DATABASE).use { reopened ->
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        body(Journey(scenario, reopened, history))
                    }
                }
            }
        }
    }

    private inner class Journey(
        val scenario: ActivityScenario<MainActivity>,
        val store: RuntimeRecoveryStore,
        val history: StatisticsJourneyHistory,
    ) {
        private val day = StatisticsJourneyHistory.day
        private val all = StatisticsPeriod.AllTime

        fun enterDay(): StatisticsController {
            enterStatistics()
            val overview = overviewController()
            await { overview.state.value.load is StatisticsLoadState.Empty }
            val oldRefresh = action(overviewNode("statistics-refresh"))
            activate(overviewNode("statistics-period-day"))
            await {
                val state = overview.state.value
                state.selectedPeriod is StatisticsPeriod.Day && state.load is StatisticsLoadState.Empty
            }
            setDate(overviewNode("statistics-period-input"))
            activate(overviewNode("statistics-period-apply"))
            val before = assertOverview(overview, "0:35:00", executions = "4")
            compose.runOnIdle {
                assertFalse(oldRefresh())
                assertSame(before, overview.state.value.load)
            }
            val oneOff = series(ActivityExecutionStatistics.ONE_OFF_BUCKET_ID)
            oneOff.assertHasNoClickAction().assertTextContains(label(R.string.statistics_duration_total, "0:05:00"))
            assertNull(sessionOrNull())
            return overview
        }

        fun openActivityWithKeyboard(overview: StatisticsController): StatisticsSeriesDetailRouteSession {
            val hiddenRefresh = action(overviewNode("statistics-refresh"))
            val hiddenBack = action(overviewNode("statistics-back"))
            val otherSeries = action(series(history.sequenceId))
            val row = series(history.activityId)
            row
                .assertTextContains(StatisticsJourneyHistory.SERIES_NAME)
                .assertTextContains(label(R.string.statistics_source_archived))
                .assertTextContains(label(R.string.statistics_executions, "3"))
                .assertTextContains(label(R.string.statistics_duration_total, "0:30:00"))
            val oldSeries = action(row)
            val before = overview.state.value
            keyboard.enter(row)
            row.performSemanticsAction(SemanticsActions.RequestFocus)
            keyboard.awaitFocus(row)
            assertSame(before, overview.state.value)
            keyboard.sendKey(row, KeyEvent.KEYCODE_ENTER)

            val session = requireNotNull(sessionOrNull())
            assertEquals(history.activityId, session.expectedSeriesId)
            assertEquals(day, session.initialPeriod)
            reject(hiddenRefresh, hiddenBack, otherSeries, oldSeries)
            assertSame(before, overview.state.value)
            assertSame(session, sessionOrNull())
            return session
        }

        fun assertActivityFacts(session: StatisticsSeriesDetailRouteSession) {
            val detail = canonicalDetail(session, day) as StatisticsSeriesDetail.Activity
            assertEquals(3L, detail.statistics.executionCount)
            assertEquals(2L, detail.statistics.durations.sampleCount)
            assertEquals(Duration.ofMinutes(30), detail.statistics.durations.total)
            detailNode("statistics-detail-series-${history.activityId.value}").assertIsDisplayed()
            detailText(R.string.statistics_duration_samples, "2").assertIsDisplayed()
            detailText(R.string.statistics_duration_median, "0:15:00").assertIsDisplayed()
            val numberId = StatisticsFieldId.Activity(history.mainNumber.id)
            detailNode("statistics-field-activity-${numberId.value}")
                .assert(SemanticsMatcher.expectValue(DetailFieldIdentity, numberId))
            val total = numberText("18446744073709551.614")
            detailText(R.string.statistics_number_total, "$total km").assertIsDisplayed()
            detailText(R.string.statistics_number_minimum, "0 km").assertIsDisplayed()
            detailText(R.string.statistics_number_total, label(R.string.statistics_unavailable)).assertIsDisplayed()
            detailText(R.string.statistics_detail_precision, "2").assertIsDisplayed()
            detailText(R.string.statistics_field_text).assertIsDisplayed()
            assertCategories()
        }

        private fun assertCategories() {
            val categoryId = StatisticsFieldId.Activity(history.category.id)
            val coverage = label(R.string.statistics_detail_ratio, "2", "3", percent(2.0 / 3))
            detailText(R.string.statistics_field_coverage, coverage).assertIsDisplayed()
            detailText(R.string.statistics_field_missing, "1").assertIsDisplayed()
            for (option in history.category.categoryOptions) {
                val optionId = StatisticsCategoryOptionId.ActivitySource(option.id)
                val matcher = SemanticsMatcher.expectValue(DetailOptionIdentity, optionId)
                val title = label(R.string.statistics_detail_duplicate_name, option.label, option.id.value)
                val share = label(R.string.statistics_detail_ratio, "1", "2", percent(0.5))
                reveal(DETAIL, matcher)
                    .assert(SemanticsMatcher.expectValue(DetailFieldIdentity, categoryId))
                    .assertTextContains(label(R.string.statistics_category_recorded_share, title, "1", share))
            }
        }

        fun changePeriodAndRecreate(session: StatisticsSeriesDetailRouteSession) {
            val obsoleteRefresh = action(detailNode("statistics-detail-refresh"))
            activate(detailNode("statistics-detail-period-all_time"))
            canonicalDetail(session, all)
            val state = session.controller.state.value
            reject(obsoleteRefresh)
            val pausedRefresh = action(detailNode("statistics-detail-refresh"))
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.onActivity { assertFalse(pausedRefresh()) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            reject(pausedRefresh)
            assertSame(state, session.controller.state.value)

            val oldBack = action(detailNode("statistics-detail-back"))
            scenario.recreate()
            detailNode("statistics-detail-series-${history.activityId.value}").assertIsDisplayed()
            reject(oldBack)
            assertSame(session, sessionOrNull())
            assertSame(state, session.controller.state.value)
        }

        fun correctAndRefresh(
            overview: StatisticsController,
            detail: StatisticsSeriesDetailRouteSession,
        ) {
            val unchanged = store.statistics.seriesDetail(history.sequenceId, day)
            val before = overview.state.value
            history.correctStandalone(store)
            val moved = store.statistics.overview(StatisticsJourneyHistory.nextDay)
            assertEquals(Duration.ofMinutes(25), moved.global.totalTrackedDuration)
            assertEquals(1L, moved.global.topLevelExecutionCount)
            assertEquals(unchanged, store.statistics.seriesDetail(history.sequenceId, day))

            activate(detailNode("statistics-detail-refresh"))
            canonicalDetail(detail, all)
            detailText(R.string.statistics_duration_total, "0:35:00").assertIsDisplayed()
            detailText(R.string.statistics_number_minimum, "${numberText("-1.25")} km").assertIsDisplayed()
            val outgoing = action(detailNode("statistics-detail-refresh"))
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                assertFalse(outgoing())
            }
            awaitFocus(seriesTag(history.activityId))
            assertSame(overview, overviewController())
            assertSame(before, overview.state.value)
            assertEquals(day, overview.state.value.selectedPeriod)

            activate(overviewNode("statistics-refresh"))
            assertOverview(overview, "0:15:00", executions = "3")
            series(history.activityId)
                .assertTextContains(label(R.string.statistics_executions, "2"))
                .assertTextContains(label(R.string.statistics_duration_total, "0:10:00"))
        }

        fun deleteAndRefresh(previous: StatisticsSeriesDetailRouteSession) {
            activate(series(history.activityId))
            val current = requireNotNull(sessionOrNull())
            assertNotSame(previous, current)
            canonicalDetail(current, day)
            activate(detailNode("statistics-detail-period-all_time"))
            canonicalDetail(current, all)
            detailText(R.string.statistics_executions, "3").assertIsDisplayed()
            history.deleteStandalone(store)
            assertTrue(store.statistics.overview(StatisticsJourneyHistory.nextDay).isEmpty)
            activate(detailNode("statistics-detail-refresh"))
            canonicalDetail(current, all)
            detailText(R.string.statistics_executions, "2").assertIsDisplayed()
            detailText(R.string.statistics_duration_samples, "1").assertIsDisplayed()
            detailText(R.string.statistics_number_total, "${numberText("9223372036854775.807")} km").assertIsDisplayed()
            activate(detailNode("statistics-detail-back"))
        }

        fun inspectSequenceAndNormalizeRestoredRoute(overview: StatisticsController) {
            val retainedOverview = overview.state.value
            val sequence = series(history.sequenceId)
            sequence
                .assertTextContains(StatisticsJourneyHistory.SERIES_NAME)
                .assertTextContains(label(R.string.statistics_kind_sequence))
            activate(sequence)
            val session = requireNotNull(sessionOrNull())
            assertEquals(history.sequenceId, session.expectedSeriesId)
            canonicalDetail(session, day)
            detailText(R.string.statistics_active_duration_total, "0:10:00").assertIsDisplayed()
            detailText(R.string.statistics_total_pause_idle, "0:03:00").assertIsDisplayed()

            // Save the real detail route first. Drop only its retained session as the old Activity is destroyed.
            scenario.onActivity { activity ->
                val owner = activity.statisticsSeriesDetailRouteSessions
                activity.lifecycle.addObserver(
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_DESTROY) owner.release(session)
                    },
                )
            }
            scenario.recreate()
            awaitFocus(seriesTag(history.sequenceId))
            assertNull(sessionOrNull())
            assertSame(overview, overviewController())
            assertSame(retainedOverview, overview.state.value)

            val outgoing = action(overviewNode("statistics-refresh"))
            val back = action(overviewNode("statistics-back"))
            compose.runOnIdle {
                assertTrue(back())
                assertFalse(outgoing())
                assertFalse(back())
            }
            compose.onNodeWithText(label(R.string.daily_statistics)).performScrollTo().assertIsDisplayed()
            assertSame(retainedOverview, overview.state.value)
        }

        fun retryOverviewAndSupersedeRefresh(scope: CoroutineScope) {
            val held = HeldRead()
            val reads = mutableListOf<StatisticsPeriod>()
            scenario.onActivity { activity ->
                activity.statisticsControllerOwner.get {
                    StatisticsController(scope, all) { period ->
                        reads += period
                        val request = reads.size
                        if (request == 1) error("Injected overview failure")
                        val result = withContext(Dispatchers.IO) { store.statistics.overview(period) }
                        if (request == 3) held.suspendBeforePublishing()
                        result
                    }
                }
            }
            enterStatistics()
            val controller = overviewController()
            val retry = action(overviewNode("statistics-retry"))
            compose.runOnIdle {
                assertTrue(retry())
                assertFalse(retry())
            }
            await { controller.state.value.load is StatisticsLoadState.Content }
            reveal(OVERVIEW, hasText(label(R.string.statistics_tracked_time, "0:35:00"))).assertIsDisplayed()
            activate(overviewNode("statistics-refresh"))
            held.awaitEntry()
            history.correctStandalone(store)
            activate(overviewNode("statistics-refresh"))
            await { controller.state.value.load is StatisticsLoadState.Content }
            val refreshed = controller.state.value
            assertEquals(store.statistics.overview(all), (refreshed.load as StatisticsLoadState.Content).overview)
            held.finish()
            assertSame(refreshed, controller.state.value)
            reveal(OVERVIEW, hasText(label(R.string.statistics_tracked_time, "0:40:00"))).assertIsDisplayed()
            compose.runOnIdle { assertEquals(List(4) { all }, reads) }
        }

        fun retryReplacementDetailAndSupersedeFailure(scope: CoroutineScope) {
            val open = action(series(history.activityId))
            scenario.onActivity {
                assertTrue(open())
                assertFalse(open())
                it.onBackPressedDispatcher.onBackPressed()
                assertEquals(history.activityId, it.statisticsSeriesDetailRouteSessions.activeSession?.expectedSeriesId)
            }
            val original = requireNotNull(sessionOrNull())
            canonicalDetail(original, all)
            val previousBack = action(detailNode("statistics-detail-back"))
            val previousRefresh = action(detailNode("statistics-detail-refresh"))
            val held = HeldRead()
            val reads = mutableListOf<StatisticsPeriod>()
            scenario.onActivity { activity ->
                val owner = activity.statisticsSeriesDetailRouteSessions
                owner.release(original)
                val replacement =
                    owner.acquire(original.expectedSeriesId, all) { id, initial ->
                        StatisticsSeriesDetailController(scope, id, initial) { seriesId, period ->
                            reads += period
                            when (reads.size) {
                                1 -> error("Injected detail failure")
                                3 -> {
                                    held.suspendBeforePublishing()
                                    error("Superseded detail failure")
                                }
                                else -> withContext(Dispatchers.IO) { store.statistics.seriesDetail(seriesId, period) }
                            }
                        }
                    }
                assertFalse(previousBack())
                assertFalse(previousRefresh())
                assertSame(replacement, owner.activeSession)
            }
            val session = requireNotNull(sessionOrNull())
            val retry = action(detailNode("statistics-detail-retry"))
            compose.runOnIdle {
                assertTrue(retry())
                assertFalse(retry())
            }
            canonicalDetail(session, all)
            activate(detailNode("statistics-detail-refresh"))
            held.awaitEntry()
            val selected = StatisticsPeriod.Custom(day.date, day.date)
            activate(detailNode("statistics-detail-period-custom"))
            setDate(detailNode("statistics-detail-custom-start"))
            setDate(detailNode("statistics-detail-custom-end"))
            activate(detailNode("statistics-detail-custom-apply"))
            canonicalDetail(session, selected)
            val latest = session.controller.state.value
            held.finish()
            assertSame(latest, session.controller.state.value)
            assertSame(session, sessionOrNull())
            detailText(R.string.statistics_duration_total, "0:10:00").assertIsDisplayed()
            compose.runOnIdle { assertEquals(listOf(all, all, all, selected), reads) }
        }

        // Exercise accessibility text entry without requesting focus and starting an asynchronous IME session.
        private fun setDate(input: SemanticsNodeInteraction) {
            input.performSemanticsAction(SemanticsActions.SetText) {
                assertTrue(it(AnnotatedString(day.date.toString())))
            }
        }

        private fun enterStatistics() {
            activate(compose.onNodeWithText(label(R.string.daily_statistics)).performScrollTo())
            compose.onNodeWithTag(OVERVIEW).assertIsDisplayed()
        }

        private fun assertOverview(
            overview: StatisticsController,
            duration: String,
            executions: String,
        ): StatisticsLoadState.Content {
            await {
                val state = overview.state.value
                state.selectedPeriod == day && state.load is StatisticsLoadState.Content
            }
            val state = overview.state.value.load as StatisticsLoadState.Content
            assertEquals(store.statistics.overview(day), state.overview)
            reveal(OVERVIEW, hasText(label(R.string.statistics_tracked_time, duration))).assertIsDisplayed()
            reveal(OVERVIEW, hasText(label(R.string.statistics_top_level_executions, executions))).assertIsDisplayed()
            reveal(OVERVIEW, hasText(label(R.string.statistics_sequence_pause_idle, "0:03:00"))).assertIsDisplayed()
            return state
        }

        private fun canonicalDetail(
            session: StatisticsSeriesDetailRouteSession,
            period: StatisticsPeriod,
        ): StatisticsSeriesDetail {
            await {
                val state = session.controller.state.value
                state.selectedPeriod == period && state.load is StatisticsSeriesDetailLoadState.Content
            }
            val published = (session.controller.state.value.load as StatisticsSeriesDetailLoadState.Content).detail
            assertEquals(store.statistics.seriesDetail(session.expectedSeriesId, period), published)
            return published
        }

        private fun sessionOrNull(): StatisticsSeriesDetailRouteSession? {
            var session: StatisticsSeriesDetailRouteSession? = null
            scenario.onActivity { session = it.statisticsSeriesDetailRouteSessions.activeSession }
            return session
        }

        private fun overviewController(): StatisticsController {
            var controller: StatisticsController? = null
            scenario.onActivity { activity ->
                controller = activity.statisticsControllerOwner.get { error("Overview controller must exist") }
            }
            return requireNotNull(controller)
        }

        private fun label(
            @StringRes id: Int,
            vararg args: String,
        ): String {
            var text: String? = null
            scenario.onActivity { text = it.getString(id, *args) }
            return requireNotNull(text)
        }

        private fun locale(): Locale {
            var result: Locale? = null
            scenario.onActivity { result = it.resources.configuration.locales[0] }
            return requireNotNull(result)
        }

        private fun numberText(decimal: String): String =
            NumberFormat.getNumberInstance(locale()).apply { maximumFractionDigits = 3 }.format(BigDecimal(decimal))

        private fun percent(value: Double): String =
            NumberFormat.getPercentInstance(locale()).apply { maximumFractionDigits = 1 }.format(value)

        private fun detailText(
            @StringRes id: Int,
            vararg args: String,
        ) = reveal(DETAIL, hasText(label(id, *args)))

        private fun overviewNode(tag: String) = reveal(OVERVIEW, hasTestTag(tag))

        private fun detailNode(tag: String) = reveal(DETAIL, hasTestTag(tag))

        private fun series(id: StatisticsSeriesId) = overviewNode(seriesTag(id))

        private fun seriesTag(id: StatisticsSeriesId) = "statistics-series-${id.value}"
    }

    private class HeldRead {
        private val job = CompletableDeferred<Job>()
        private val release = CompletableDeferred<Unit>()

        suspend fun suspendBeforePublishing() {
            job.complete(currentCoroutineContext().job)
            release.await()
        }

        fun awaitEntry() = runBlocking { withTimeout(10_000) { job.await() } }

        fun finish() =
            runBlocking {
                withTimeout(10_000) {
                    release.complete(Unit)
                    job.await().join()
                }
            }
    }

    private fun reveal(
        list: String,
        matcher: SemanticsMatcher,
    ): SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }

    private fun activate(node: SemanticsNodeInteraction) =
        node.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }

    private fun action(node: SemanticsNodeInteraction): () -> Boolean =
        requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)

    private fun reject(vararg callbacks: () -> Boolean) {
        compose.runOnIdle { callbacks.forEach { assertFalse(it()) } }
    }

    private fun awaitFocus(tag: String) {
        await { compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().size == 1 }
        keyboard.awaitFocus(compose.onNodeWithTag(tag))
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000, condition = condition)

    private companion object {
        const val DATABASE = "lifetracing.db"
        const val OVERVIEW = "statistics-list"
        const val DETAIL = "statistics-detail-list"
    }
}
