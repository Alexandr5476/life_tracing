package com.alexandr5476.lifetracing.library

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
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

@Immutable
internal data class LibraryRetryReads(
    val browse: Boolean,
    val search: Boolean,
    val organization: Boolean,
)

internal class LibraryRetryFeedback {
    var reads by mutableStateOf<LibraryRetryReads?>(null)
    var succeeded by mutableStateOf(false)

    fun request(state: LibraryPresentationState) {
        succeeded = false
        reads =
            LibraryRetryReads(
                state.query.isBlank() && state.browse is LibraryLoad.Failure,
                state.query.isNotBlank() && state.search is LibraryLoad.Failure,
                state.organization is LibraryLoad.Failure,
            ).takeIf { it.browse || it.search || it.organization }
    }
}

@Composable
internal fun rememberLibraryRetryFeedback(state: LibraryPresentationState): LibraryRetryFeedback {
    val feedback = remember(state.folderId, state.query, state.filter) { LibraryRetryFeedback() }
    val browseReady = state.browse is LibraryLoad.Content
    val searchReady = state.search is LibraryLoad.Content
    val organizationReady = state.organization is LibraryLoad.Content
    LaunchedEffect(feedback.reads, browseReady, searchReady, organizationReady) {
        feedback.reads?.let { reads ->
            val browseComplete = !reads.browse || browseReady
            val searchComplete = !reads.search || searchReady
            val organizationComplete = !reads.organization || organizationReady
            if (browseComplete && searchComplete && organizationComplete) {
                feedback.reads = null
                feedback.succeeded = true
            }
        }
    }
    return feedback
}

internal val LocalLibraryFocus = staticCompositionLocalOf<LibraryFocusRecovery> { error("Library focus owner missing") }
internal val LocalLibraryVisible = staticCompositionLocalOf { false }

/** Ephemeral native keyboard focus; never selects a Template or dispatches a command. */
internal class LibraryFocusRecovery {
    val landing = FocusRequester()
    var owner: Any? = null
    var removedOwner by mutableStateOf<Any?>(null)
    var restoreTarget by mutableStateOf<LibraryFocusHandle?>(null)
    var deletionOrigin by mutableStateOf<LibraryDeletionOrigin?>(null)
    var lifecycleOutcome by mutableStateOf<LibraryLifecycleOutcome?>(null)

    val root: Modifier
        get() = Modifier.focusGroup()
}

@Composable
internal fun rememberLibraryFocusRecovery(isVisible: Boolean): LibraryFocusRecovery {
    val recovery = remember { LibraryFocusRecovery() }
    val window = LocalWindowInfo.current
    val inputMode = LocalInputModeManager.current.inputMode
    var landingPending by remember { mutableStateOf(isVisible) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            recovery.removedOwner = null
            recovery.restoreTarget = null
            landingPending = true
        }
    }
    LaunchedEffect(isVisible, window.isWindowFocused, inputMode, landingPending) {
        if (isVisible && window.isWindowFocused && landingPending) {
            withFrameNanos { }
            landingPending = !recovery.landing.requestFocus()
        }
    }
    LaunchedEffect(isVisible, window.isWindowFocused, inputMode, recovery.removedOwner, recovery.restoreTarget) {
        val removed = recovery.removedOwner
        val pending = removed != null && recovery.restoreTarget == null
        if (isVisible && window.isWindowFocused && pending) recovery.recoverRemoved(removed)
    }
    val restore = recovery.restoreTarget
    LaunchedEffect(isVisible, window.isWindowFocused, inputMode, restore, restore?.attached, restore?.enabled) {
        if (isVisible && window.isWindowFocused && restore != null) {
            if (!restore.attached || restore.enabled) {
                recovery.restore(restore)
            }
        }
    }
    return recovery
}

private suspend fun LibraryFocusRecovery.recoverRemoved(removed: Any?) {
    withFrameNanos { }
    // Detachment may assign framework fallback focus before this deferred recovery.
    val unchanged = removedOwner === removed && restoreTarget == null
    if (unchanged && landing.requestFocus()) removedOwner = null
}

private suspend fun LibraryFocusRecovery.restore(target: LibraryFocusHandle) {
    withFrameNanos { }
    val restored = target.attached && target.requester.requestFocus()
    if (restored || landing.requestFocus()) {
        restoreTarget = null
        removedOwner = null
    }
}

internal class LibraryFocusHandle {
    val requester = FocusRequester()
    var attached by mutableStateOf(true)
    var enabled by mutableStateOf(true)
}

@Composable
internal fun rememberLibraryFocusHandle(enabled: Boolean): LibraryFocusHandle {
    val handle = remember { LibraryFocusHandle() }
    handle.enabled = enabled
    DisposableEffect(handle) { onDispose { handle.attached = false } }
    return handle
}

@Composable
internal fun rememberLibraryMutationFeedback(state: LibraryPresentationState): Boolean {
    var generation by remember { mutableStateOf(state.mutationSuccessGeneration) }
    var pending by remember { mutableStateOf(false) }
    var succeeded by remember { mutableStateOf(false) }
    val browseReady = state.browse is LibraryLoad.Content
    val searchReady = state.query.isBlank() || state.search is LibraryLoad.Content
    val organizationReady = state.organization is LibraryLoad.Content
    LaunchedEffect(
        state.mutationSuccessGeneration,
        state.isMutating,
        state.mutationFailure,
        browseReady,
        searchReady,
        organizationReady,
    ) {
        if (state.isMutating) succeeded = false
        if (state.mutationSuccessGeneration != generation) {
            generation = state.mutationSuccessGeneration
            succeeded = false
            pending = true
        }
        if (state.mutationFailure != null) {
            pending = false
            succeeded = false
        }
        val ready = browseReady && searchReady && organizationReady
        if (pending && ready && !state.isMutating) {
            pending = false
            succeeded = true
        }
    }
    return succeeded && state.mutationSuccessGeneration == generation && state.mutationFailure == null
}

/** Call inside the typed row key so a surviving target keeps its native focus node. */
@Composable
internal fun libraryFocusTarget(): Modifier {
    val recovery = LocalLibraryFocus.current
    val token = remember { Any() }
    return Modifier
        .onFocusTargetAttachmentChanged { attached ->
            if (!attached && recovery.owner === token) recovery.removedOwner = token
        }.onFocusChanged {
            if (it.isFocused) {
                recovery.owner = token
            }
        }
}
