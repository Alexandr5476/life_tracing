@file:Suppress("TooGenericExceptionCaught")

package com.alexandr5476.lifetracing.history

import androidx.lifecycle.ViewModel
import com.alexandr5476.lifetracing.domain.CompletedHistoryCursor
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedHistoryRoot
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.cursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

data class HistoryBrowseWindow(
    val startDate: LocalDate,
    val endDate: LocalDate,
) {
    init {
        require(startDate <= endDate) { "History window must be ordered" }
    }

    fun query(continuation: CompletedHistoryCursor?): CompletedHistoryQuery =
        CompletedHistoryQuery(HistoryDateRange(startDate, endDate), HISTORY_RESULT_LIMIT + 1, continuation)

    fun older(): HistoryBrowseWindow =
        HistoryBrowseWindow(startDate.minusDays(HISTORY_WINDOW_DAYS), startDate.minusDays(1))

    fun newer(upperBound: LocalDate): HistoryBrowseWindow? {
        if (endDate >= upperBound) return null
        val start = endDate.plusDays(1)
        return HistoryBrowseWindow(start, minOf(start.plusDays(HISTORY_WINDOW_DAYS - 1), upperBound))
    }
}

sealed interface HistoryRootsLoad {
    data object Loading : HistoryRootsLoad

    data class Content(
        val roots: List<CompletedHistoryRoot>,
    ) : HistoryRootsLoad

    data object Empty : HistoryRootsLoad

    data class Failure(
        val message: String,
    ) : HistoryRootsLoad
}

data class HistoryPresentationState(
    val window: HistoryBrowseWindow,
    val load: HistoryRootsLoad = HistoryRootsLoad.Loading,
    val canNavigateNewer: Boolean = false,
    val canLoadMore: Boolean = false,
)

sealed interface HistoryAction {
    data object Older : HistoryAction

    data object Newer : HistoryAction

    data object LoadMore : HistoryAction

    data object Retry : HistoryAction
}

class HistoryController internal constructor(
    private val scope: CoroutineScope,
    private val readRoots: suspend (CompletedHistoryQuery) -> List<CompletedHistoryRoot>,
    private val now: () -> Instant = Instant::now,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val readLatestDate: suspend () -> LocalDate? = { null },
) {
    private val generation = AtomicLong()
    private val initialToday = now().atZone(zoneId()).toLocalDate()
    private val mutableState = MutableStateFlow(HistoryPresentationState(initialWindow(initialToday)))
    val state: StateFlow<HistoryPresentationState> = mutableState

    @Volatile
    private var closed = false

    @Volatile
    private var routeActive = false

    @Volatile
    private var retryRequest: HistoryRequest? = null

    private var nextCursor: CompletedHistoryCursor? = null
    private var upperBound = initialToday

    fun dispatch(action: HistoryAction) {
        when (action) {
            HistoryAction.Older -> load(mutableState.value.window.older())
            HistoryAction.Newer ->
                mutableState.value.window
                    .newer(upperBound)
                    ?.let(::load)
            HistoryAction.LoadMore -> nextCursor?.let { load(mutableState.value.window, it) }
            HistoryAction.Retry ->
                if (retryDiscovery) {
                    discoverAndLoad()
                } else {
                    retryRequest?.let { load(it.window, it.continuation) }
                }
        }
    }

    fun onRouteEntered() {
        if (closed || routeActive) return
        routeActive = true
        discoverAndLoad()
    }

    fun onRouteExited() {
        routeActive = false
        retryDiscovery = false
        generation.incrementAndGet()
    }

    fun close() {
        closed = true
        routeActive = false
        retryDiscovery = false
        generation.incrementAndGet()
    }

    private fun discoverAndLoad() {
        if (closed || !routeActive) return
        val request = generation.incrementAndGet()
        retryRequest = null
        retryDiscovery = true
        mutableState.update {
            it.copy(
                load = HistoryRootsLoad.Loading,
                canNavigateNewer = false,
                canLoadMore = false,
            )
        }
        scope.launch {
            try {
                val latest = readLatestDate()
                if (!closed && routeActive && generation.get() == request) {
                    val previousUpperBound = upperBound
                    upperBound = maxOf(today(), latest ?: today())
                    val window =
                        mutableState.value.window.takeUnless { it.endDate == previousUpperBound }
                            ?: initialWindow(upperBound)
                    retryDiscovery = false
                    load(window)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed && routeActive && generation.get() == request) {
                    mutableState.update { it.copy(load = HistoryRootsLoad.Failure(failure.message ?: "Unknown error")) }
                }
            }
        }
    }

    private fun load(
        window: HistoryBrowseWindow,
        continuation: CompletedHistoryCursor? = null,
    ) {
        if (closed) return
        val request = generation.incrementAndGet()
        val admittedRequest = HistoryRequest(window, continuation)
        retryRequest = admittedRequest
        retryDiscovery = false
        val canNavigateNewer = window.endDate < upperBound
        mutableState.update {
            it.copy(
                window = window,
                load = HistoryRootsLoad.Loading,
                canNavigateNewer = canNavigateNewer,
                canLoadMore = false,
            )
        }
        scope.launch {
            try {
                val roots = readRoots(admittedRequest.window.query(admittedRequest.continuation))
                if (!closed && generation.get() == request) {
                    val page = roots.take(HISTORY_RESULT_LIMIT)
                    nextCursor = page.lastOrNull()?.cursor()?.takeIf { roots.size > HISTORY_RESULT_LIMIT }
                    mutableState.update {
                        it.copy(
                            load = if (page.isEmpty()) HistoryRootsLoad.Empty else HistoryRootsLoad.Content(page),
                            canLoadMore = nextCursor != null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed && generation.get() == request) {
                    mutableState.update { it.copy(load = HistoryRootsLoad.Failure(failure.message ?: "Unknown error")) }
                }
            }
        }
    }

    private fun today(): LocalDate = now().atZone(zoneId()).toLocalDate()

    private fun initialWindow(today: LocalDate): HistoryBrowseWindow =
        HistoryBrowseWindow(today.minusDays(HISTORY_WINDOW_DAYS - 1), today)

    @Volatile
    private var retryDiscovery = false

    private data class HistoryRequest(
        val window: HistoryBrowseWindow,
        val continuation: CompletedHistoryCursor?,
    )
}

sealed interface HistoryDetailLoad<out T> {
    data object Loading : HistoryDetailLoad<Nothing>

    data class Content<T>(
        val value: T,
    ) : HistoryDetailLoad<T>

    data object Unavailable : HistoryDetailLoad<Nothing>

    data class Failure(
        val message: String,
    ) : HistoryDetailLoad<Nothing>
}

class HistoryDetailController<T> internal constructor(
    private val scope: CoroutineScope,
    private val readDetail: suspend () -> T?,
) {
    private val generation = AtomicLong()
    private val mutableState = MutableStateFlow<HistoryDetailLoad<T>>(HistoryDetailLoad.Loading)
    val state: StateFlow<HistoryDetailLoad<T>> = mutableState

    @Volatile
    private var closed = false

    init {
        reload()
    }

    fun reload() {
        if (closed) return
        val request = generation.incrementAndGet()
        mutableState.value = HistoryDetailLoad.Loading
        scope.launch {
            try {
                val detail = readDetail()
                if (!closed && generation.get() == request) {
                    mutableState.value = detail?.let(HistoryDetailLoad<T>::Content) ?: HistoryDetailLoad.Unavailable
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed && generation.get() == request) {
                    mutableState.value = HistoryDetailLoad.Failure(failure.message ?: "Unknown error")
                }
            }
        }
    }

    fun close() {
        closed = true
        generation.incrementAndGet()
    }
}

internal class HistoryControllerOwner : ViewModel() {
    private var controller: HistoryController? = null

    fun get(create: () -> HistoryController): HistoryController = controller ?: create().also { controller = it }

    fun onRouteExited() {
        controller?.onRouteExited()
    }

    override fun onCleared() {
        controller?.close()
        controller = null
    }
}

const val HISTORY_WINDOW_DAYS = 28L
const val HISTORY_RESULT_LIMIT = 100
