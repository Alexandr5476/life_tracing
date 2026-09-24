package com.alexandr5476.lifetracing.statistics

import com.alexandr5476.lifetracing.domain.ActivitySeriesStatistics
import com.alexandr5476.lifetracing.domain.DurationDistribution
import com.alexandr5476.lifetracing.domain.SequenceSeriesStatistics
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

class StatisticsSeriesDetailControllerTest {
    private val id = StatisticsSeriesId("series")
    private val initial = StatisticsPeriod.AllTime
    private val day = StatisticsPeriod.Day(LocalDate.parse("2026-08-20"))

    @Test
    fun initialPeriodChangesRefreshAndRetryPassThroughWithActivityAndSequenceContent() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val passed = mutableListOf<Pair<StatisticsSeriesId, StatisticsPeriod>>()
            val activity = activityDetail()
            val sequence = sequenceDetail()
            var calls = 0
            val controller =
                StatisticsSeriesDetailController(scope, id, initial) { seriesId, period ->
                    passed += seriesId to period
                    when (calls++) {
                        0 -> activity
                        1 -> error("read failed")
                        else -> sequence
                    }
                }
            try {
                assertSame(activity, (controller.state.value.load as StatisticsSeriesDetailLoadState.Content).detail)
                controller.selectPeriod(day)
                assertEquals(StatisticsSeriesDetailLoadState.Failure, controller.state.value.load)
                controller.retry()
                assertSame(sequence, (controller.state.value.load as StatisticsSeriesDetailLoadState.Content).detail)
                controller.refresh()
                assertEquals(listOf(id to initial, id to day, id to day, id to day), passed)
                assertSame(sequence, (controller.state.value.load as StatisticsSeriesDetailLoadState.Content).detail)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun staleSuccessAndFailureCannotReplaceNewerRefreshAndCloseBlocksPublicationAndRequests() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val oldSuccess = CompletableDeferred<StatisticsSeriesDetail>()
            val oldFailure = CompletableDeferred<StatisticsSeriesDetail>()
            val current = CompletableDeferred<StatisticsSeriesDetail>()
            var calls = 0
            val controller =
                StatisticsSeriesDetailController(scope, id, initial) { _, _ ->
                    when (calls++) {
                        0 -> oldSuccess.await()
                        1 -> oldFailure.await()
                        else -> current.await()
                    }
                }
            try {
                controller.selectPeriod(day)
                controller.refresh()
                val expected = sequenceDetail()
                current.complete(expected)
                controller.awaitContent(expected)
                oldSuccess.complete(activityDetail())
                oldFailure.completeExceptionally(IllegalStateException("stale"))
                assertSame(expected, (controller.state.value.load as StatisticsSeriesDetailLoadState.Content).detail)
                assertEquals(day, controller.state.value.selectedPeriod)
                controller.close()
                controller.selectPeriod(initial)
                controller.refresh()
                controller.retry()
                assertEquals(3, calls)
                assertSame(expected, (controller.state.value.load as StatisticsSeriesDetailLoadState.Content).detail)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun closeInvalidatesInFlightRead() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val pending = CompletableDeferred<StatisticsSeriesDetail>()
            val controller = StatisticsSeriesDetailController(scope, id, initial) { _, _ -> pending.await() }
            controller.close()
            pending.complete(activityDetail())
            assertEquals(StatisticsSeriesDetailLoadState.Loading, controller.state.value.load)
            scope.cancel()
        }

    @Test
    fun cancelledReadDoesNotPublishFailure() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val pending = CompletableDeferred<StatisticsSeriesDetail>()
            val controller = StatisticsSeriesDetailController(scope, id, initial) { _, _ -> pending.await() }
            scope.cancel()
            assertEquals(StatisticsSeriesDetailLoadState.Loading, controller.state.value.load)
        }

    private suspend fun StatisticsSeriesDetailController.awaitContent(expected: StatisticsSeriesDetail) =
        withTimeout(2_000) {
            state.first { (it.load as? StatisticsSeriesDetailLoadState.Content)?.detail === expected }
        }

    private fun activityDetail(): StatisticsSeriesDetail.Activity =
        StatisticsSeriesDetail.Activity(
            ActivitySeriesStatistics(summary(StatisticsSeriesKind.ACTIVITY), 1, emptyDurations(), 1, null, null, null),
            emptyList(),
        )

    private fun sequenceDetail(): StatisticsSeriesDetail.Sequence =
        StatisticsSeriesDetail.Sequence(
            SequenceSeriesStatistics(
                summary(StatisticsSeriesKind.SEQUENCE),
                1,
                emptyDurations(),
                Duration.ZERO,
                null,
                1,
                null,
                null,
                null,
            ),
            emptyList(),
        )

    private fun summary(kind: StatisticsSeriesKind) =
        StatisticsSeriesSummary(id, kind, "Series", null, StatisticsSeriesSourceState.NO_CURRENT_SOURCE)

    private fun emptyDurations() = DurationDistribution(0, Duration.ZERO, null, null, null, null)
}
