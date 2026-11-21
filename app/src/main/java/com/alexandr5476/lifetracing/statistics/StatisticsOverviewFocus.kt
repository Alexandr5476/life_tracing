@file:Suppress("FunctionNaming", "LongParameterList")

package com.alexandr5476.lifetracing.statistics

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import com.alexandr5476.lifetracing.domain.StatisticsOverview
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged
import kotlinx.coroutines.flow.first

/** Owns one return target while the overview entry is covered by Series Detail. */
internal class StatisticsOverviewReturnFocus {
    var series: StatisticsSeriesId? = null
}

internal enum class StatisticsOverviewFocusAnchor {
    OTHER,
    STATUS,
    RETRY,
}

/** Observes native focus and only attaches a requester to the target of a recovery. */
internal class StatisticsOverviewFocus {
    private class Request(
        val target: Any,
    )

    private val requester = FocusRequester()
    private var requested by mutableStateOf<Request?>(null)
    private var attached by mutableStateOf<Any?>(null)
    var last: Any? = null
        private set
    var active: Any? = null
        private set
    var pendingSeries: StatisticsSeriesId? = null

    fun otherTarget(): Modifier = target(StatisticsOverviewFocusAnchor.OTHER)

    fun target(id: Any): Modifier {
        val observer =
            Modifier.onFocusChanged {
                if (it.hasFocus) {
                    last = id
                    active = id
                } else if (active == id) {
                    active = null
                }
            }
        return if (requested?.target == id) {
            Modifier
                .focusRequester(requester)
                .onFocusTargetAttachmentChanged { isAttached ->
                    if (isAttached) {
                        attached = id
                    } else if (attached == id) {
                        attached = null
                    }
                }.then(observer)
        } else {
            observer
        }
    }

    suspend fun move(
        id: Any,
        index: Int,
        list: LazyListState,
        admission: StatisticsActionAdmission,
        expectedKey: Any? = null,
    ) {
        if (!admission.isCurrent()) return
        val isVisible =
            list.layoutInfo.visibleItemsInfo.any {
                it.index == index && (expectedKey == null || it.key == expectedKey)
            }
        if (active == id && (expectedKey == null || isVisible)) return
        val previousFocus = active
        val request = Request(id)
        requested = request
        try {
            if (!isVisible) list.scrollToItem(index)
            snapshotFlow { attached }.first { it == id }
            withFrameNanos { }
            val stillRecovering = active == null || active == previousFocus || active == id
            if (attached == id && admission.isCurrent() && stillRecovering) {
                requester.requestFocus()
            }
        } finally {
            if (requested === request) requested = null
        }
    }
}

@Composable
internal fun RestoreStatisticsOverviewFocus(
    state: StatisticsPresentationState,
    overview: StatisticsOverview?,
    admission: StatisticsActionAdmission,
    list: LazyListState,
    focus: StatisticsOverviewFocus,
    returnFocus: StatisticsOverviewReturnFocus,
) {
    val foreground = LocalWindowInfo.current.isWindowFocused
    // Capture focus before the new publication detaches Retry or replaces the catalog.
    val previousFocus = remember(StatisticsIdentityKey(state)) { focus.active ?: focus.last }
    LaunchedEffect(state, admission, foreground) {
        if (!foreground || !admission.isCurrent()) return@LaunchedEffect
        val seriesId =
            returnFocus.series ?: when (previousFocus) {
                StatisticsOverviewFocusAnchor.STATUS, StatisticsOverviewFocusAnchor.RETRY -> focus.pendingSeries
                is StatisticsSeriesId -> previousFocus
                else -> null
            }
        returnFocus.series = null
        val status = StatisticsOverviewItem.STATUS.ordinal
        val failed = state.load == StatisticsLoadState.Failure
        if (seriesId != null) {
            focus.pendingSeries = seriesId
            if (overview != null) {
                focus.restoreSeries(seriesId, overview, list, admission)
                focus.pendingSeries = null
            } else {
                val anchor = if (failed) StatisticsOverviewFocusAnchor.RETRY else StatisticsOverviewFocusAnchor.STATUS
                focus.move(anchor, status, list, admission)
            }
        } else {
            focus.pendingSeries = null
            if (!failed && previousFocus == StatisticsOverviewFocusAnchor.RETRY) {
                focus.move(StatisticsOverviewFocusAnchor.STATUS, status, list, admission)
            }
        }
    }
}

private suspend fun StatisticsOverviewFocus.restoreSeries(
    id: StatisticsSeriesId,
    overview: StatisticsOverview,
    list: LazyListState,
    admission: StatisticsActionAdmission,
) {
    val catalogStart = StatisticsOverviewItem.entries.size
    val key = StatisticsSeriesItemKey(id)
    val visibleItems = list.layoutInfo.visibleItemsInfo
    val visibleIndex = visibleItems.firstOrNull { it.key == key }?.index
    val hint = visibleIndex?.minus(catalogStart)
    val index =
        hint?.takeIf {
            val row = overview.series.getOrNull(it)
            row?.series?.id == id
        } ?: overview.series.indexOfFirst { it.series.id == id }
    if (index >= 0) {
        move(id, catalogStart + index, list, admission)
    } else {
        move(StatisticsOverviewFocusAnchor.STATUS, StatisticsOverviewItem.STATUS.ordinal, list, admission)
    }
}
