package com.alexandr5476.lifetracing.statistics

import com.alexandr5476.lifetracing.domain.GlobalStatistics
import com.alexandr5476.lifetracing.domain.StatisticsOverview
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesPeriodSummary
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

class StatisticsControllerTest {
    @Test
    fun routeOwnerStartsAtCurrentLocalMonthAndCreatesFreshControllerAfterExit() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = StatisticsControllerOwner()
        val createdPeriods = mutableListOf<StatisticsPeriod>()
        var reads = 0
        val first =
            owner.get { period ->
                createdPeriods += period
                StatisticsController(scope, period) {
                    reads++
                    overview(1)
                }
            }
        assertEquals(StatisticsPeriod.Month(YearMonth.now()), createdPeriods.single())
        assertEquals(1, reads)

        owner.onRouteExited()
        val second =
            owner.get { period ->
                createdPeriods += period
                StatisticsController(scope, period) {
                    reads++
                    overview(2)
                }
            }
        assertNotSame(first, second)
        assertEquals(2, reads)
        assertEquals(
            listOf(StatisticsPeriod.Month(YearMonth.now()), StatisticsPeriod.Month(YearMonth.now())),
            createdPeriods,
        )
        owner.onRouteExited()
        scope.cancel()
    }

    @Test
    fun initialReadPublishesContentAndPassesCanonicalSeriesThrough() =
        runBlocking {
            val expected = overview(1)
            var passed: StatisticsPeriod? = null
            val fixture =
                controller(StatisticsPeriod.AllTime) {
                    passed = it
                    expected
                }
            val controller = fixture.controller
            try {
                val loaded = controller.awaitState { it.load !is StatisticsLoadState.Loading }
                assertEquals(StatisticsPeriod.AllTime, passed)
                assertSame(expected, assertInstanceOf(StatisticsLoadState.Content::class.java, loaded.load).overview)
                assertEquals(expected.series, (loaded.load as StatisticsLoadState.Content).overview.series)
            } finally {
                fixture.scope.cancel()
            }
        }

    @Test
    fun emptyAndFailureAreDistinctAndRetryRecovers() =
        runBlocking {
            var reads = 0
            val emptyOverview = overview(0)
            val fixture =
                controller(StatisticsPeriod.AllTime) {
                    when (reads++) {
                        0 -> emptyOverview
                        1 -> error("offline")
                        else -> overview(1)
                    }
                }
            val controller = fixture.controller
            try {
                val empty =
                    assertInstanceOf(
                        StatisticsLoadState.Empty::class.java,
                        controller
                            .awaitState {
                                it.load is StatisticsLoadState.Empty
                            }.load,
                    )
                assertSame(emptyOverview, empty.overview)
                assertEquals(emptyOverview.series, empty.overview.series)
                assertEquals(
                    listOf(
                        Triple(
                            StatisticsSeriesId("one-off"),
                            StatisticsSeriesKind.ONE_OFF_BUCKET,
                            StatisticsSeriesSourceState.SYSTEM_ONE_OFF,
                        ),
                        Triple(
                            StatisticsSeriesId("archived-activity"),
                            StatisticsSeriesKind.ACTIVITY,
                            StatisticsSeriesSourceState.ARCHIVED_SOURCE,
                        ),
                    ),
                    empty.overview.series.map { Triple(it.series.id, it.series.kind, it.series.sourceState) },
                )
                controller.refresh()
                assertInstanceOf(
                    StatisticsLoadState.Failure::class.java,
                    controller
                        .awaitState {
                            it.load is StatisticsLoadState.Failure
                        }.load,
                )
                controller.retry()
                assertInstanceOf(
                    StatisticsLoadState.Content::class.java,
                    controller
                        .awaitState {
                            it.load is StatisticsLoadState.Content
                        }.load,
                )
            } finally {
                fixture.scope.cancel()
            }
            Unit
        }

    @Test
    fun supportedPeriodVariantsReachReadBoundaryUnchangedAndRefreshRereadsSelectedPeriod() =
        runBlocking {
            val initial = StatisticsPeriod.Day(LocalDate.parse("2026-08-20"))
            val periods =
                listOf(
                    StatisticsPeriod.Week(LocalDate.parse("2026-08-17")),
                    StatisticsPeriod.Month(YearMonth.of(2026, 8)),
                    StatisticsPeriod.Year(Year.of(2026)),
                    StatisticsPeriod.Custom(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31")),
                    StatisticsPeriod.AllTime,
                )
            val passed = mutableListOf<StatisticsPeriod>()
            var read = 0L
            val fixture =
                controller(initial) { period ->
                    passed += period
                    overview(++read)
                }
            val controller = fixture.controller
            try {
                controller.awaitState { it.load is StatisticsLoadState.Content }
                periods.forEach { period ->
                    controller.selectPeriod(period)
                    controller.awaitState { it.load is StatisticsLoadState.Content && it.selectedPeriod == period }
                }
                val refreshedPeriod = periods.last()
                controller.selectPeriod(refreshedPeriod)
                controller.refresh()
                val refreshed =
                    controller.awaitState {
                        it.load is StatisticsLoadState.Content &&
                            (it.load as StatisticsLoadState.Content).overview.global.topLevelExecutionCount == 7L
                    }
                assertEquals(refreshedPeriod, refreshed.selectedPeriod)
                controller.retry()
                controller.awaitState {
                    (it.load as? StatisticsLoadState.Content)?.overview?.global?.topLevelExecutionCount == 8L
                }
                assertEquals(listOf(initial) + periods + listOf(refreshedPeriod, refreshedPeriod), passed)
            } finally {
                fixture.scope.cancel()
            }
        }

    @Test
    fun oldSuccessCannotPublishWhenPeriodChangesDuringResultClassification() {
        val pending = CompletableDeferred<StatisticsOverview>()
        val current = CompletableDeferred<StatisticsOverview>()
        val initial = StatisticsPeriod.AllTime
        val replacement = StatisticsPeriod.Day(LocalDate.parse("2026-08-20"))
        val fixture = controller(initial) { if (it == initial) pending.await() else current.await() }
        val controller = fixture.controller
        try {
            val initialLoading = controller.state.value
            var replacementLoading: StatisticsPresentationState? = null
            pending.complete(
                overviewDuringClassification {
                    controller.selectPeriod(replacement)
                    replacementLoading = controller.state.value
                },
            )

            assertEquals(replacement, controller.state.value.selectedPeriod)
            assertSame(StatisticsLoadState.Loading, controller.state.value.load)
            assertSame(replacementLoading, controller.state.value)
            assertNotSame(initialLoading, controller.state.value)

            val expected = overview(8)
            current.complete(expected)
            assertContent(controller.state.value, replacement, expected)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun closeDuringResultClassificationRetainsTheExactLoadingPublication() {
        val pending = CompletableDeferred<StatisticsOverview>()
        val period = StatisticsPeriod.AllTime
        val fixture = controller(period) { pending.await() }
        val controller = fixture.controller
        try {
            val loading = controller.state.value
            pending.complete(overviewDuringClassification(controller::close))

            assertEquals(period, controller.state.value.selectedPeriod)
            assertSame(StatisticsLoadState.Loading, controller.state.value.load)
            assertSame(loading, controller.state.value)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun closureRejectsQueuedReadAndSubsequentCommands() =
        runBlocking {
            val periods = mutableListOf<StatisticsPeriod>()
            val initial = StatisticsPeriod.AllTime
            val replacement = StatisticsPeriod.Day(LocalDate.parse("2026-08-20"))
            val controller =
                StatisticsController(this, initial) { period ->
                    periods += period
                    overview(1)
                }
            yield()
            assertEquals(listOf(initial), periods)

            controller.selectPeriod(replacement)
            val loading = controller.state.value
            controller.close()
            controller.selectPeriod(initial)
            controller.refresh()
            controller.retry()
            yield()

            assertEquals(listOf(initial), periods)
            assertEquals(replacement, controller.state.value.selectedPeriod)
            assertSame(StatisticsLoadState.Loading, controller.state.value.load)
            assertSame(loading, controller.state.value)
        }

    @Test
    fun staleFailureCannotPublishForTheNewPeriod() {
        val old = CompletableDeferred<StatisticsOverview>()
        val current = CompletableDeferred<StatisticsOverview>()
        val initial = StatisticsPeriod.AllTime
        val replacement = StatisticsPeriod.Day(LocalDate.parse("2026-08-20"))
        val periods = mutableListOf<StatisticsPeriod>()
        val fixture =
            controller(initial) { period ->
                periods += period
                if (period == initial) old.await() else current.await()
            }
        val controller = fixture.controller
        val emissions = fixture.recordPublications()
        try {
            val initialLoading = controller.state.value
            controller.selectPeriod(replacement)
            val replacementLoading = controller.state.value

            old.completeExceptionally(IllegalStateException("obsolete"))
            assertEquals(replacement, controller.state.value.selectedPeriod)
            assertSame(StatisticsLoadState.Loading, controller.state.value.load)
            assertSame(replacementLoading, controller.state.value)
            assertEquals(listOf(initialLoading, replacementLoading), emissions)

            val expected = overview(8)
            current.complete(expected)
            val result = controller.state.value
            assertContent(result, replacement, expected)
            assertEquals(listOf(initialLoading, replacementLoading, result), emissions)
            assertEquals(listOf(initial, replacement), periods)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun identicalRefreshesKeepTheNewerLoadingAndRevokeThePriorPublication() {
        val period = StatisticsPeriod.AllTime
        val expected = overview(2)
        val older = CompletableDeferred<StatisticsOverview>()
        val newer = CompletableDeferred<StatisticsOverview>()
        val reads = listOf(CompletableDeferred(expected), older, newer).iterator()
        val periods = mutableListOf<StatisticsPeriod>()
        val fixture =
            controller(period) {
                periods += it
                reads.next().await()
            }
        val controller = fixture.controller
        val emissions = fixture.recordPublications()
        try {
            val initial = controller.state.value
            assertContent(initial, period, expected)
            controller.refresh()
            val olderLoading = controller.state.value
            controller.refresh()
            val newerLoading = controller.state.value
            assertNotSame(olderLoading, newerLoading)

            older.complete(overview(5))
            assertEquals(period, controller.state.value.selectedPeriod)
            assertSame(StatisticsLoadState.Loading, controller.state.value.load)
            assertSame(newerLoading, controller.state.value)
            assertEquals(listOf(initial, olderLoading, newerLoading), emissions)

            newer.complete(expected)
            val result = controller.state.value
            assertContent(result, period, expected)
            assertNotSame(initial, result)
            assertEquals(listOf(initial, olderLoading, newerLoading, result), emissions)
            assertEquals(listOf(period, period, period), periods)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun failureFromAnInFlightReadCannotPublishAfterClose() {
        val pending = CompletableDeferred<StatisticsOverview>()
        val period = StatisticsPeriod.AllTime
        val fixture = controller(period) { pending.await() }
        val controller = fixture.controller
        val emissions = fixture.recordPublications()
        try {
            val loading = controller.state.value
            controller.close()
            pending.completeExceptionally(IllegalStateException("obsolete"))

            assertEquals(period, controller.state.value.selectedPeriod)
            assertSame(StatisticsLoadState.Loading, controller.state.value.load)
            assertSame(loading, controller.state.value)
            assertEquals(listOf(loading), emissions)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun cancelledReadDoesNotPublishFailure() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<StatisticsOverview>()
        val controller = StatisticsController(scope, StatisticsPeriod.AllTime) { pending.await() }
        try {
            scope.cancel()
            assertEquals(StatisticsLoadState.Loading, controller.state.value.load)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun routeExitSuppressesPendingSuccessAndFailureAfterReentry() {
        for (fails in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val owner = StatisticsControllerOwner()
            val oldPeriods = mutableListOf<StatisticsPeriod>()
            val pending = CompletableDeferred<StatisticsOverview>()
            try {
                val old =
                    owner.get {
                        StatisticsController(scope, it) { period ->
                            oldPeriods += period
                            pending.await()
                        }
                    }
                val oldLoading = old.state.value
                owner.onRouteExited()
                val expected = overview(2)
                val current = owner.get { StatisticsController(scope, it) { expected } }
                val currentPublication = current.state.value
                if (fails) {
                    pending.completeExceptionally(
                        IllegalStateException("obsolete failure"),
                    )
                } else {
                    pending.complete(overview(9))
                }
                old.selectPeriod(StatisticsPeriod.AllTime)
                old.refresh()
                old.retry()
                assertNotSame(old, current)
                assertSame(StatisticsLoadState.Loading, old.state.value.load)
                assertSame(oldLoading, old.state.value)
                assertEquals(listOf(oldLoading.selectedPeriod), oldPeriods)
                assertContent(current.state.value, oldLoading.selectedPeriod, expected)
                assertSame(currentPublication, current.state.value)
            } finally {
                owner.onRouteExited()
                scope.cancel()
            }
        }
    }

    private suspend fun StatisticsController.awaitState(
        predicate: (StatisticsPresentationState) -> Boolean,
    ): StatisticsPresentationState = withTimeout(3_000) { state.first(predicate) }

    private fun controller(
        initial: StatisticsPeriod,
        reader: suspend (StatisticsPeriod) -> StatisticsOverview,
    ): ControllerFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return ControllerFixture(scope, StatisticsController(scope, initial, reader))
    }

    private data class ControllerFixture(
        val scope: CoroutineScope,
        val controller: StatisticsController,
    ) {
        fun recordPublications(): List<StatisticsPresentationState> {
            val publications = mutableListOf<StatisticsPresentationState>()
            scope.launch { controller.state.collect { publications += it } }
            return publications
        }
    }

    private fun assertContent(
        state: StatisticsPresentationState,
        period: StatisticsPeriod,
        expected: StatisticsOverview,
    ) {
        assertEquals(period, state.selectedPeriod)
        assertSame(expected, assertInstanceOf(StatisticsLoadState.Content::class.java, state.load).overview)
    }

    private fun overviewDuringClassification(action: () -> Unit): StatisticsOverview {
        val empty = overview(0)
        val series =
            empty.series.map {
                if (it.series.kind == StatisticsSeriesKind.ACTIVITY) it.copy(executionCount = 1) else it
            }
        return empty.copy(
            series =
                object : List<StatisticsSeriesPeriodSummary> by series {
                    // With zero top-level executions, determining emptiness checks the Series catalog.
                    override fun iterator(): Iterator<StatisticsSeriesPeriodSummary> {
                        action()
                        return series.iterator()
                    }
                },
        )
    }

    private fun overview(count: Long): StatisticsOverview {
        val oneOffSeries =
            StatisticsSeriesPeriodSummary(
                StatisticsSeriesSummary(
                    StatisticsSeriesId("one-off"),
                    StatisticsSeriesKind.ONE_OFF_BUCKET,
                    "One-off activities",
                    null,
                    StatisticsSeriesSourceState.SYSTEM_ONE_OFF,
                ),
                count,
                0,
                Duration.ZERO,
                null,
                0,
            )
        val zeroCountSeries =
            StatisticsSeriesPeriodSummary(
                StatisticsSeriesSummary(
                    StatisticsSeriesId("archived-activity"),
                    StatisticsSeriesKind.ACTIVITY,
                    "Archived activity",
                    null,
                    StatisticsSeriesSourceState.ARCHIVED_SOURCE,
                ),
                0,
                0,
                Duration.ZERO,
                null,
                0,
            )
        return StatisticsOverview(
            GlobalStatistics(
                Duration.ZERO,
                count,
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
            listOf(oneOffSeries, zeroCountSeries),
        )
    }
}
