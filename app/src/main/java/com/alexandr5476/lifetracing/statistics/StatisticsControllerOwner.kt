package com.alexandr5476.lifetracing.statistics

import androidx.lifecycle.ViewModel
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import java.time.LocalDate
import java.time.YearMonth

internal class StatisticsControllerOwner : ViewModel() {
    private var controller: StatisticsController? = null
    val returnFocus = StatisticsOverviewReturnFocus()

    fun get(create: (StatisticsPeriod) -> StatisticsController): StatisticsController =
        controller ?: create(StatisticsPeriod.Month(YearMonth.from(LocalDate.now()))).also { controller = it }

    fun controllerForRoute(
        isRouteVisible: Boolean,
        create: (StatisticsPeriod) -> StatisticsController,
    ): StatisticsController? = if (isRouteVisible) get(create) else controller

    fun owns(expected: StatisticsController): Boolean = controller === expected

    fun onRouteExited() {
        returnFocus.series = null
        controller?.close()
        controller = null
    }

    override fun onCleared() = onRouteExited()
}
