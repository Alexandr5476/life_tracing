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
import androidx.compose.ui.platform.LocalWindowInfo

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
    var hasFocus by mutableStateOf(false)

    val root: Modifier
        get() = Modifier.onFocusChanged { hasFocus = it.hasFocus }.focusGroup()
}

@Composable
internal fun rememberLibraryFocusRecovery(isVisible: Boolean): LibraryFocusRecovery {
    val recovery = remember { LibraryFocusRecovery() }
    val window = LocalWindowInfo.current
    LaunchedEffect(isVisible) {
        if (isVisible) {
            recovery.removedOwner = null
            withFrameNanos { }
            recovery.landing.requestFocus()
        }
    }
    LaunchedEffect(isVisible, window.isWindowFocused, recovery.removedOwner) {
        if (isVisible && window.isWindowFocused && recovery.removedOwner != null) {
            // Disposal and native focus clearing settle before considering a landing.
            withFrameNanos { }
            if (!recovery.hasFocus) recovery.landing.requestFocus()
            recovery.removedOwner = null
        }
    }
    return recovery
}

/** Call inside the typed row key so a surviving target keeps its native focus node. */
@Composable
internal fun libraryFocusTarget(): Modifier {
    val recovery = LocalLibraryFocus.current
    val token = remember { Any() }
    DisposableEffect(recovery, token) {
        onDispose {
            if (recovery.owner === token) recovery.removedOwner = token
        }
    }
    return Modifier.onFocusChanged {
        if (it.isFocused) {
            recovery.owner = token
            recovery.removedOwner = null
        }
    }
}
