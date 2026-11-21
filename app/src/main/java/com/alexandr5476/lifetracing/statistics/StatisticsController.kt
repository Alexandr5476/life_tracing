package com.alexandr5476.lifetracing.statistics

import com.alexandr5476.lifetracing.domain.StatisticsOverview
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface StatisticsLoadState {
    data object Loading : StatisticsLoadState

    data class Content(
        val overview: StatisticsOverview,
    ) : StatisticsLoadState

    data class Empty(
        val overview: StatisticsOverview,
    ) : StatisticsLoadState

    data object Failure : StatisticsLoadState
}

data class StatisticsPresentationState(
    val selectedPeriod: StatisticsPeriod,
    val load: StatisticsLoadState = StatisticsLoadState.Loading,
    // StateFlow must deliver a fresh publication even when its rendered contents compare equal.
    private val publicationIdentity: Any = Any(),
)

class StatisticsController internal constructor(
    private val scope: CoroutineScope,
    initialPeriod: StatisticsPeriod,
    private val readOverview: suspend (StatisticsPeriod) -> StatisticsOverview,
) {
    private val lock = Any()
    private var closed = false
    private val mutableState = MutableStateFlow(StatisticsPresentationState(initialPeriod))
    val state: StateFlow<StatisticsPresentationState> = mutableState

    init {
        refresh()
    }

    fun selectPeriod(period: StatisticsPeriod) = reload(period)

    fun refresh() = reload()

    fun retry() = refresh()

    fun close() {
        synchronized(lock) {
            closed = true
        }
    }

    private fun reload(newPeriod: StatisticsPeriod? = null) {
        val request =
            synchronized(lock) {
                if (closed) return
                val selectedPeriod = mutableState.value.selectedPeriod
                if (newPeriod == selectedPeriod) return
                // Each Loading publication identifies one request, including consecutive refreshes.
                StatisticsPresentationState(newPeriod ?: selectedPeriod).also { mutableState.value = it }
            }
        scope.launch {
            // The scope can dispatch this coroutine after the controller has closed.
            if (synchronized(lock) { closed }) return@launch
            val load =
                try {
                    val overview = readOverview(request.selectedPeriod)
                    if (overview.isEmpty) StatisticsLoadState.Empty(overview) else StatisticsLoadState.Content(overview)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    StatisticsLoadState.Failure
                }
            synchronized(lock) {
                if (!closed && mutableState.value === request) {
                    mutableState.value = StatisticsPresentationState(request.selectedPeriod, load)
                }
            }
        }
    }
}
