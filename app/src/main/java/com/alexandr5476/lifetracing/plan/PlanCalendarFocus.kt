package com.alexandr5476.lifetracing.plan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged

/** Ephemeral native keyboard ownership. No Plan identity, command, or virtual-focus authority. */
internal class PlanCalendarFocus {
    val back = FocusRequester()
    val today = FocusRequester()
    val selectedDay = FocusRequester()
    val retry = FocusRequester()
    var owner: Any? = null
    var removed by mutableStateOf<Any?>(null)
    var awaitingRead by mutableStateOf(false)
    var recovering = false
}

internal val LocalPlanCalendarFocus = staticCompositionLocalOf { PlanCalendarFocus() }

@Composable
internal fun rememberPlanCalendarFocus(
    state: PlanPresentationState,
    isVisible: Boolean,
): PlanCalendarFocus {
    val focus = remember { PlanCalendarFocus() }
    val window = LocalWindowInfo.current
    val mode = LocalInputModeManager.current.inputMode
    val hasDialog = state.hasCalendarDialog()
    val interactive = isVisible && !hasDialog
    var landingPending by remember { mutableStateOf(isVisible) }
    LaunchedEffect(isVisible) {
        focus.owner = null
        focus.removed = null
        focus.awaitingRead = false
        landingPending = isVisible
    }
    LaunchedEffect(interactive, window.isWindowFocused, mode, landingPending) {
        if (interactive && window.isWindowFocused && landingPending) {
            withFrameNanos { }
            landingPending = !focus.back.requestFocus()
        }
    }
    val loading = state.week is PlanLoad.Loading
    val failure = state.week is PlanLoad.Failure
    val hasSelectedDay =
        (state.week as? PlanLoad.Content)?.value?.let { read ->
            read.dayPresence.any { it.date == read.selectedDate }
        } == true
    LaunchedEffect(interactive, window.isWindowFocused, mode, focus.removed, loading, failure, hasSelectedDay) {
        val needsRecovery = focus.removed != null || focus.awaitingRead && !loading
        if (!interactive) {
            focus.removed = null
            focus.awaitingRead = false
        } else if (window.isWindowFocused && needsRecovery) {
            withFrameNanos { }
            focus.recovering = true
            val target =
                when {
                    failure -> focus.retry
                    hasSelectedDay -> focus.selectedDay
                    else -> focus.today
                }
            target.requestFocus()
            focus.recovering = false
            focus.removed = null
            focus.awaitingRead = loading
        }
    }
    return focus
}

private fun PlanPresentationState.hasCalendarDialog() = form != null || catalog != null || cancelledOpen

/** Remember inside a date/PlanEntryId key so reordering cannot reuse another row's focus node. */
@Composable
internal fun planCalendarFocusTarget(): Modifier {
    val focus = LocalPlanCalendarFocus.current
    val token = remember { Any() }
    return Modifier
        .onFocusTargetAttachmentChanged { attached ->
            if (!attached && focus.owner === token) focus.removed = token
        }.onFocusChanged {
            if (it.isFocused) {
                focus.owner = token
                if (!focus.recovering && focus.removed == null) focus.awaitingRead = false
            }
        }
}
