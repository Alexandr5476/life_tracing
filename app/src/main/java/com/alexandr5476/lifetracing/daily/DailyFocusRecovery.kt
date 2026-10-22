package com.alexandr5476.lifetracing.daily

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.alexandr5476.lifetracing.domain.DailyRead
import java.time.LocalDate

internal data class DailyFocusRecovery(
    val landing: FocusRequester,
    val runtime: Modifier,
    val surviving: Modifier,
)

/** Native keyboard focus only; no runtime commands or accessibility virtual-focus claims. */
@Composable
internal fun rememberDailyFocusRecovery(
    selectedDate: LocalDate,
    isRouteVisible: Boolean,
    canonical: DailyRead?,
): DailyFocusRecovery {
    val landing = remember { FocusRequester() }
    var hadActive by remember(selectedDate, isRouteVisible) { mutableStateOf(false) }
    var runtimeOwnedFocus by remember(selectedDate, isRouteVisible) { mutableStateOf(false) }
    val runtimePresent by rememberUpdatedState(canonical?.active != null)
    // Capture before disposal. Preserve ownership across a transient Loading read;
    // a surviving control receiving native focus cancels that pending recovery.
    val recover = hadActive && canonical != null && canonical.active == null && runtimeOwnedFocus
    val runtime =
        Modifier
            .onFocusChanged {
                if (it.hasFocus || runtimePresent) runtimeOwnedFocus = it.hasFocus
            }.focusGroup()
    val surviving =
        Modifier
            .onFocusChanged {
                if (it.hasFocus) runtimeOwnedFocus = false
            }.focusGroup()
    LaunchedEffect(isRouteVisible) { if (isRouteVisible) landing.requestFocus() }
    LaunchedEffect(selectedDate, isRouteVisible, canonical != null, canonical?.active != null) {
        if (canonical != null) {
            if (isRouteVisible && recover) landing.requestFocus()
            hadActive = canonical.active != null
            if (!hadActive) runtimeOwnedFocus = false
        }
    }
    return DailyFocusRecovery(landing, runtime, surviving)
}
