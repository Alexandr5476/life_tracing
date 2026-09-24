package com.alexandr5476.lifetracing.statistics

import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface StatisticsSeriesDetailLoadState {
    data object Loading : StatisticsSeriesDetailLoadState

    data class Content(
        val detail: StatisticsSeriesDetail,
    ) : StatisticsSeriesDetailLoadState

    data object Failure : StatisticsSeriesDetailLoadState
}

data class StatisticsSeriesDetailPresentationState(
    val selectedPeriod: StatisticsPeriod,
    val load: StatisticsSeriesDetailLoadState = StatisticsSeriesDetailLoadState.Loading,
)

class StatisticsSeriesDetailController internal constructor(
    private val scope: CoroutineScope,
    private val seriesId: StatisticsSeriesId,
    initialPeriod: StatisticsPeriod,
    private val readDetail: suspend (StatisticsSeriesId, StatisticsPeriod) -> StatisticsSeriesDetail,
) {
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private val mutableState = MutableStateFlow(StatisticsSeriesDetailPresentationState(initialPeriod))
    val state: StateFlow<StatisticsSeriesDetailPresentationState> = mutableState

    init {
        reload(initialPeriod)
    }

    fun selectPeriod(period: StatisticsPeriod) {
        synchronized(lock) {
            if (!closed && period != mutableState.value.selectedPeriod) reload(period)
        }
    }

    fun refresh() {
        synchronized(lock) {
            if (!closed) reload(mutableState.value.selectedPeriod)
        }
    }

    fun retry() = refresh()

    fun close() {
        synchronized(lock) {
            closed = true
            generation++
        }
    }

    private fun reload(period: StatisticsPeriod) {
        val request = ++generation
        mutableState.value = StatisticsSeriesDetailPresentationState(period)
        scope.launch {
            val result =
                try {
                    StatisticsSeriesDetailLoadState.Content(readDetail(seriesId, period))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    StatisticsSeriesDetailLoadState.Failure
                }
            synchronized(lock) {
                if (!closed && generation == request) {
                    mutableState.value = StatisticsSeriesDetailPresentationState(period, result)
                }
            }
        }
    }
}
