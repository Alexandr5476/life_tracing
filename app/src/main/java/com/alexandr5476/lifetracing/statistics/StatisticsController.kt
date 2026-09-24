package com.alexandr5476.lifetracing.statistics

import com.alexandr5476.lifetracing.domain.StatisticsOverview
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

sealed interface StatisticsLoadState {
    data object Loading : StatisticsLoadState

    data class Content(
        val overview: StatisticsOverview,
    ) : StatisticsLoadState

    data object Empty : StatisticsLoadState

    data object Failure : StatisticsLoadState
}

data class StatisticsPresentationState(
    val selectedPeriod: StatisticsPeriod,
    val load: StatisticsLoadState = StatisticsLoadState.Loading,
)

class StatisticsController internal constructor(
    private val scope: CoroutineScope,
    initialPeriod: StatisticsPeriod,
    private val readOverview: suspend (StatisticsPeriod) -> StatisticsOverview,
) {
    private val generation = AtomicLong()
    private val mutableState = MutableStateFlow(StatisticsPresentationState(initialPeriod))
    val state: StateFlow<StatisticsPresentationState> = mutableState

    init {
        reload(initialPeriod)
    }

    fun selectPeriod(period: StatisticsPeriod) {
        if (period == mutableState.value.selectedPeriod) return
        reload(period)
    }

    fun refresh() = reload(mutableState.value.selectedPeriod)

    fun retry() = refresh()

    private fun reload(period: StatisticsPeriod) {
        val request = generation.incrementAndGet()
        mutableState.update { it.copy(selectedPeriod = period, load = StatisticsLoadState.Loading) }
        scope.launch {
            try {
                val overview = readOverview(period)
                if (generation.get() == request) {
                    mutableState.update {
                        it.copy(
                            load =
                                if (overview.isEmpty) {
                                    StatisticsLoadState.Empty
                                } else {
                                    StatisticsLoadState.Content(
                                        overview,
                                    )
                                },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation.get() == request) {
                    mutableState.update { it.copy(load = StatisticsLoadState.Failure) }
                }
            }
        }
    }
}
