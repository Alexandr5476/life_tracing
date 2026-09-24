package com.alexandr5476.lifetracing.statistics

import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class StatisticsSeriesDetailRouteSessionOwnerTest {
    @Test
    fun sessionCapturesExactSeriesAndOverviewPeriodAndReleaseClosesController() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = StatisticsSeriesDetailRouteSessionOwner()
        val selected = StatisticsPeriod.Custom(LocalDate.parse("2026-03-02"), LocalDate.parse("2026-03-18"))
        val other = StatisticsPeriod.Day(LocalDate.parse("2026-03-05"))
        val id = StatisticsSeriesId("stable-series-id")
        val overview = StatisticsController(scope, selected) { error("overview read failure") }
        var reads = 0
        try {
            val session =
                owner.acquire(id, selected) { seriesId, period ->
                    assertEquals(id, seriesId)
                    assertEquals(selected, period)
                    StatisticsSeriesDetailController(scope, seriesId, period) { _, _ ->
                        reads++
                        error("read failure")
                    }
                }
            assertSame(session, owner.sessionFor(id))
            assertEquals(selected, session.initialPeriod)
            session.controller.selectPeriod(other)
            assertEquals(selected, session.initialPeriod)
            assertEquals(selected, overview.state.value.selectedPeriod)
            owner.release(session)
            assertNull(owner.activeSession)
            session.controller.refresh()
            assertEquals(2, reads)
            overview.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun differentSeriesCannotReuseActiveController() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = StatisticsSeriesDetailRouteSessionOwner()
        val initial = StatisticsPeriod.AllTime
        val firstId = StatisticsSeriesId("first")
        val secondId = StatisticsSeriesId("second")
        var created = 0

        fun create(
            id: StatisticsSeriesId,
            period: StatisticsPeriod,
        ) = StatisticsSeriesDetailController(scope, id, period) { _, _ -> error("read failure") }.also { created++ }
        try {
            val first = owner.acquire(firstId, initial, ::create)
            val second = owner.acquire(secondId, initial, ::create)
            assertNotSame(first.controller, second.controller)
            assertNull(owner.sessionFor(firstId))
            assertSame(second, owner.sessionFor(secondId))
            assertEquals(2, created)
            assertTrue(first.controller.state.value.load is StatisticsSeriesDetailLoadState.Failure)
        } finally {
            scope.cancel()
        }
    }
}
