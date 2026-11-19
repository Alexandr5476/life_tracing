@file:Suppress("FunctionNaming")

package com.alexandr5476.lifetracing.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

/** One bounded read-only request; no command, repository or formatted identity is held here. */
internal class StructuralListJump(
    val index: Int,
    val scope: CoroutineScope,
) {
    var scrolled by mutableStateOf(false)
}

internal class StructuralListNavigation(
    private val lease: SequenceHistoryNavigation,
) {
    private var attached = true
    private var request by mutableStateOf<StructuralListJump?>(null)
    val pending: StructuralListJump? get() = request?.takeIf(::isPending)
    val requester = FocusRequester()

    fun dispose() {
        attached = false
        request = null
    }

    fun isPending(jump: StructuralListJump): Boolean =
        attached && request === jump && jump.scope.isActive && lease.isCurrent()

    fun go(
        index: Int,
        scope: CoroutineScope,
        scroll: (Int) -> Unit,
    ) {
        if (!attached || !scope.isActive || !lease.isCurrent()) return
        val jump = StructuralListJump(index, scope)
        request = jump
        lease.launch(scope) {
            withFrameNanos { }
            if (isPending(jump)) {
                scroll(index)
                if (isPending(jump)) jump.scrolled = true
            }
        }
    }

    fun focus(
        jump: StructuralListJump,
        attached: Boolean,
        available: Boolean,
        focus: () -> Boolean,
    ): Boolean {
        val ready = isPending(jump) && jump.scrolled
        if (!ready || !attached || !available) return false
        return focus().also { if (it && request === jump) request = null }
    }
}

@Composable
internal fun structuralFactFocus(
    navigation: StructuralListNavigation,
    index: Int,
    lease: SequenceHistoryNavigation,
): Modifier {
    var attached by remember { mutableStateOf(false) }
    val window = LocalWindowInfo.current
    val mode = LocalInputModeManager.current.inputMode
    val jump = navigation.pending?.takeIf { it.index == index && it.scrolled }
    val historyFocus = LocalHistoryFocus.current
    LaunchedEffect(navigation, jump, attached, window.isWindowFocused, mode) {
        val available = attached && window.isWindowFocused
        if (jump != null && available && lease.isCurrent()) {
            withFrameNanos { }
            navigation.focus(jump, attached, window.isWindowFocused) {
                navigation.requester.requestFocus().also { focused ->
                    if (focused) {
                        historyFocus.removed = null
                        historyFocus.awaitingRead = false
                        historyFocus.landingPending = false
                    }
                }
            }
        }
    }
    return historyFocusTarget(if (jump != null) navigation.requester else null, lease.isCurrent())
        .onFocusTargetAttachmentChanged { attached = it }
        .focusProperties { canFocus = lease.canFocus() }
}
