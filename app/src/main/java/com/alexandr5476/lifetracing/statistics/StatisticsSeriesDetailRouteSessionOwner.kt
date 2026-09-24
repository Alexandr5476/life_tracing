package com.alexandr5476.lifetracing.statistics

import androidx.lifecycle.ViewModel
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId

internal data class StatisticsSeriesDetailRouteSession(
    val expectedSeriesId: StatisticsSeriesId,
    val initialPeriod: StatisticsPeriod,
    val controller: StatisticsSeriesDetailController,
)

internal class StatisticsSeriesDetailRouteSessionOwner : ViewModel() {
    var activeSession: StatisticsSeriesDetailRouteSession? = null
        private set

    fun acquire(
        seriesId: StatisticsSeriesId,
        initialPeriod: StatisticsPeriod,
        create: (StatisticsSeriesId, StatisticsPeriod) -> StatisticsSeriesDetailController,
    ): StatisticsSeriesDetailRouteSession {
        activeSession?.takeIf { it.expectedSeriesId == seriesId }?.let { return it }
        activeSession?.controller?.close()
        return StatisticsSeriesDetailRouteSession(seriesId, initialPeriod, create(seriesId, initialPeriod))
            .also { activeSession = it }
    }

    fun sessionFor(seriesId: StatisticsSeriesId): StatisticsSeriesDetailRouteSession? =
        activeSession?.takeIf { it.expectedSeriesId == seriesId }

    fun release(session: StatisticsSeriesDetailRouteSession) {
        if (activeSession === session) {
            session.controller.close()
            activeSession = null
        }
    }

    override fun onCleared() {
        activeSession?.controller?.close()
        activeSession = null
    }
}
