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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

class StatisticsControllerTest {
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
                controller.refresh()
                val refreshed =
                    controller.awaitState {
                        it.load is StatisticsLoadState.Content &&
                            (it.load as StatisticsLoadState.Content).overview.global.topLevelExecutionCount == 7L
                    }
                assertEquals(refreshedPeriod, refreshed.selectedPeriod)
                assertEquals(listOf(initial) + periods + refreshedPeriod, passed)
            } finally {
                fixture.scope.cancel()
            }
        }

    @Test
    fun staleSuccessAndFailureCannotReplaceNewerPeriodResult() =
        runBlocking {
            val oldSuccess = CompletableDeferred<StatisticsOverview>()
            val oldFailure = CompletableDeferred<StatisticsOverview>()
            val current = CompletableDeferred<StatisticsOverview>()
            var calls = 0
            val fixture =
                controller(StatisticsPeriod.AllTime) {
                    when (calls++) {
                        0 -> oldSuccess.await()
                        1 -> oldFailure.await()
                        else -> current.await()
                    }
                }
            val controller = fixture.controller
            try {
                controller.selectPeriod(StatisticsPeriod.Day(LocalDate.parse("2026-08-20")))
                controller.refresh()
                val newest = overview(8)
                current.complete(newest)
                controller.awaitState { it.load is StatisticsLoadState.Content }
                oldSuccess.complete(overview(2))
                oldFailure.completeExceptionally(IllegalStateException("stale"))
                withTimeout(2_000) {
                    controller.awaitState {
                        it.load is StatisticsLoadState.Content &&
                            (it.load as StatisticsLoadState.Content).overview === newest
                    }
                }
                assertEquals(StatisticsPeriod.Day(LocalDate.parse("2026-08-20")), controller.state.value.selectedPeriod)
            } finally {
                fixture.scope.cancel()
            }
        }

    private fun controller(
        initial: StatisticsPeriod,
        reader: suspend (StatisticsPeriod) -> StatisticsOverview,
    ): ControllerFixture {
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        return ControllerFixture(scope, StatisticsController(scope, initial, reader))
    }

    private suspend fun StatisticsController.awaitState(
        predicate: (StatisticsPresentationState) -> Boolean,
    ): StatisticsPresentationState = withTimeout(3_000) { state.first(predicate) }

    private data class ControllerFixture(
        val scope: CoroutineScope,
        val controller: StatisticsController,
    )

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
