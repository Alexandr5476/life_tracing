@file:Suppress("FunctionNaming")

package com.alexandr5476.lifetracing.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryRootKind
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged

internal data class HistoryRootIdentity(
    val kind: CompletedHistoryRootKind,
    val executionId: String,
)

internal fun CompletedHistoryRoot.identity(): HistoryRootIdentity =
    when (this) {
        is CompletedActivityHistoryRoot -> HistoryRootIdentity(CompletedHistoryRootKind.ACTIVITY, executionId.value)
        is CompletedSequenceHistoryRoot -> HistoryRootIdentity(CompletedHistoryRootKind.SEQUENCE, executionId.value)
    }

/** Reference ownership avoids list equality work for every control on a bounded page. */
private class HistoryPublicationKey(
    private val value: Any,
) {
    override fun equals(other: Any?): Boolean = other is HistoryPublicationKey && value === other.value

    override fun hashCode(): Int = System.identityHashCode(value)
}

/** One rendered publication may submit once. A new read, hidden route or disposal revokes it. */
internal class HistoryNavigationAdmission(
    private val publication: Any,
    private val current: () -> Any,
    private val ownsRoute: () -> Boolean,
    private val onInvalidated: () -> Unit = {},
) {
    var attached = true
    private var submitted = false

    private fun ownsActionRoute(): Boolean {
        if (!attached || submitted) return false
        if (!ownsRoute()) {
            // A rejected outgoing callback cannot revive when ownership returns before a hidden frame.
            submitted = true
            onInvalidated()
        }
        return !submitted
    }

    fun callback(
        enabled: Boolean = true,
        block: () -> Unit,
    ): () -> Unit =
        {
            if (ownsActionRoute() && enabled && current() === publication) {
                submitted = true
                onInvalidated()
                block()
            }
        }
}

internal val LocalHistoryAdmission =
    staticCompositionLocalOf<HistoryNavigationAdmission> {
        error("History action owner missing")
    }

@Composable
internal fun rememberHistoryAdmission(
    publication: Any,
    current: () -> Any,
    ownsRoute: () -> Boolean,
    visible: Boolean,
): HistoryNavigationAdmission {
    val readCurrent by rememberUpdatedState(current)
    val owner by rememberUpdatedState(ownsRoute)
    var submittedGeneration by remember { mutableStateOf(0L) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val admission =
        remember(HistoryPublicationKey(publication), visible, configuration, density, submittedGeneration) {
            HistoryNavigationAdmission(publication, { readCurrent() }, { visible && owner() }) {
                submittedGeneration++
            }
        }
    DisposableEffect(admission) { onDispose { admission.attached = false } }
    return admission
}

/** Override the native action handler, preserving its single role, enabled state and focus node. */
internal fun Modifier.historyActionSemantics(
    action: () -> Unit,
    label: String? = null,
): Modifier =
    semantics {
        onClick(label) {
            action()
            true
        }
    }

internal class HistoryFocus {
    val back = FocusRequester()
    val retry = FocusRequester()
    var recoveryTarget: FocusRequester? = null
    var owner: Any? = null
    var removed by mutableStateOf<Any?>(null)
    var awaitingRead by mutableStateOf(false)
    var recovering = false
    var landingPending by mutableStateOf(true)
}

internal val LocalHistoryFocus = staticCompositionLocalOf { HistoryFocus() }

/** Native focus only. These effects never invoke a route action or writer. */
@Composable
internal fun rememberHistoryFocus(
    visible: Boolean,
    loading: Boolean,
    failed: Boolean,
    ownsRoute: () -> Boolean,
    backEnabled: Boolean = true,
): HistoryFocus {
    val focus = remember { HistoryFocus() }
    val owner by rememberUpdatedState(ownsRoute)
    val window = LocalWindowInfo.current
    val mode = LocalInputModeManager.current.inputMode
    val foreground = visible && window.isWindowFocused
    LaunchedEffect(visible) {
        focus.removed = null
        focus.awaitingRead = false
        focus.landingPending = visible
    }
    // Availability changes retry a pending native landing after a session first appears while saving.
    LaunchedEffect(visible, window.isWindowFocused, mode, focus.landingPending, backEnabled) {
        if (foreground && owner()) {
            withFrameNanos { }
            if (owner() && focus.landingPending) focus.landingPending = !focus.back.requestFocus()
        }
    }
    LaunchedEffect(visible, window.isWindowFocused, mode, focus.removed, loading, failed) {
        val needsRecovery = focus.removed != null || focus.awaitingRead && !loading
        if (foreground && owner() && needsRecovery) {
            withFrameNanos { }
            if (owner()) {
                focus.recovering = true
                (if (failed) focus.retry else focus.recoveryTarget ?: focus.back).requestFocus()
                focus.recovering = false
                focus.removed = null
                focus.awaitingRead = loading
            }
        }
    }
    return focus
}

/** Call inside the execution-identity key; reference tokens cannot migrate between rows. */
@Composable
internal fun historyFocusTarget(
    requester: FocusRequester? = null,
    enabled: Boolean = true,
): Modifier {
    val focus = LocalHistoryFocus.current
    val token = remember { Any() }
    LaunchedEffect(enabled) {
        if (!enabled && focus.owner === token) focus.removed = token
    }
    val target = requester?.let { Modifier.focusRequester(it) } ?: Modifier
    return target
        .onFocusTargetAttachmentChanged { attached ->
            if (!attached && focus.owner === token) focus.removed = token
        }.onFocusChanged {
            if (it.isFocused) {
                focus.landingPending = false
                focus.owner = token
                if (!focus.recovering && focus.removed == null) focus.awaitingRead = false
            }
        }
}
