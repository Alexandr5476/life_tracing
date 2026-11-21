@file:Suppress("FunctionNaming")

package com.alexandr5476.lifetracing.statistics

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Remembers only a focus identity while results are temporarily absent. Header and period
 * controls keep their own focus, including the shared period editor's recovery behavior.
 */
internal class StatisticsDetailFocus {
    class SuspendedFocus(
        val target: Any?,
    )

    private var pending: Any? = null
    private var suspendedFocus: SuspendedFocus? = null

    fun suspendFocus(previous: Any?) {
        // Disabling controls can move native focus again before the route resumes.
        if (suspendedFocus == null) suspendedFocus = SuspendedFocus(previous)
    }

    fun takeSuspendedFocus(): SuspendedFocus? = suspendedFocus.also { suspendedFocus = null }

    fun target(
        previous: Any?,
        load: StatisticsSeriesDetailLoadState,
        rows: StatisticsDetailRows,
    ): Any? {
        if (previous == DetailAnchor.HEADER || previous == DetailAnchor.PERIOD) {
            pending = null
            return null
        }
        val resultTarget = previous?.takeUnless { it == DetailAnchor.STATUS || it == DetailAnchor.RETRY }
        return when (load) {
            StatisticsSeriesDetailLoadState.Loading -> {
                pending = resultTarget ?: pending
                DetailAnchor.STATUS
            }
            StatisticsSeriesDetailLoadState.Failure -> {
                pending = resultTarget ?: pending
                DetailAnchor.RETRY
            }
            is StatisticsSeriesDetailLoadState.Content -> {
                rows.survivingTarget(resultTarget ?: pending ?: DetailAnchor.STATUS)
            }
        }
    }

    fun reached(
        target: Any,
        load: StatisticsSeriesDetailLoadState,
    ) {
        if (target != DetailAnchor.STATUS && load is StatisticsSeriesDetailLoadState.Content) pending = null
    }
}

@Composable
internal fun RestoreStatisticsDetailFocus(
    state: StatisticsSeriesDetailPresentationState,
    rows: StatisticsDetailRows,
    list: LazyListState,
    admission: StatisticsActionAdmission,
    focus: StatisticsOverviewFocus,
) {
    val foreground = LocalWindowInfo.current.isWindowFocused
    val recovery = remember { StatisticsDetailFocus() }
    // Snapshot the outgoing target before Compose can detach its lazy item.
    val captured = remember(StatisticsIdentityKey(state), admission, foreground) { focus.active ?: focus.last }
    LaunchedEffect(admission, foreground) {
        if (!foreground || !admission.isCurrent()) {
            recovery.suspendFocus(captured)
            return@LaunchedEffect
        }
        val suspended = recovery.takeSuspendedFocus()
        val previous = if (suspended == null) captured else suspended.target
        if (suspended == null && rows.shouldKeepActiveFocus(previous, focus.active, state.load)) return@LaunchedEffect
        val target = recovery.target(previous, state.load, rows) ?: return@LaunchedEffect
        val key =
            when (target) {
                DetailAnchor.RETRY -> DetailAnchor.STATUS
                is DetailFieldMetricKey -> target.field
                else -> target
            }
        focus.move(target, requireNotNull(rows.index(target)), list, admission, expectedKey = key)
        if (admission.isCurrent() && focus.active == target) recovery.reached(target, state.load)
    }
}

private fun StatisticsDetailRows.shouldKeepActiveFocus(
    previous: Any?,
    active: Any?,
    load: StatisticsSeriesDetailLoadState,
): Boolean {
    if (active == null || active == previous) return false
    // Compose can move focus during removal. Complete the transfer if the outgoing target disappeared.
    return when (previous) {
        null -> true
        DetailAnchor.RETRY -> load == StatisticsSeriesDetailLoadState.Failure
        else -> index(previous) != null
    }
}
