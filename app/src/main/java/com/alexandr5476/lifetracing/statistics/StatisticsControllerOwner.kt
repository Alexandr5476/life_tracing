package com.alexandr5476.lifetracing.statistics

import androidx.lifecycle.ViewModel
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import java.time.LocalDate
import java.time.YearMonth

internal class StatisticsControllerOwner : ViewModel() {
    private var controller: StatisticsController? = null

    fun get(create: (StatisticsPeriod) -> StatisticsController): StatisticsController =
        controller ?: create(StatisticsPeriod.Month(YearMonth.from(LocalDate.now()))).also { controller = it }

    fun onRouteExited() {
        controller?.close()
        controller = null
    }

    override fun onCleared() = onRouteExited()
}
