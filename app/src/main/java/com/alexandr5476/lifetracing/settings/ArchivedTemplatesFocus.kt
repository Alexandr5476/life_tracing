@file:Suppress("FunctionNaming")

package com.alexandr5476.lifetracing.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged
import kotlinx.coroutines.flow.first

internal const val ARCHIVE_HEADING = "archived-heading"
internal const val ARCHIVE_BACK = "archived-back"
internal const val ARCHIVE_RELOAD = "archived-reload"
internal const val ARCHIVE_RETRY = "archived-retry"

@Composable
internal fun rememberArchiveFocus(
    state: ArchivedTemplatesState,
    restoreStatus: ArchivedRestoreStatus,
    presentation: SettingsPresentation,
    admission: SettingsActionAdmission,
    list: LazyListState,
): ArchiveFocus {
    val preferred = rememberSaveable { mutableStateOf(ARCHIVE_BACK) }
    val currentPresentation = rememberUpdatedState(presentation)
    val focus = remember(preferred) { ArchiveFocus(preferred, currentPresentation) }
    val foreground = LocalWindowInfo.current.isWindowFocused
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(admission, restoreStatus, foreground, keyboard) {
        if (foreground && keyboard && admission.isCurrent()) {
            focus.restore(state, restoreStatus, list, admission)
        }
    }
    return focus
}

/** Only attached controls are registered. Saved focus is a logical key, without publication or action ownership. */
internal class ArchiveFocus(
    private val preferred: MutableState<String>,
    private val presentation: State<SettingsPresentation>,
) {
    private val attached = mutableStateMapOf<String, FocusRequester>()
    private var active: String? = null
    private var moving = false
    private var awaitingLanding = true

    @Composable
    fun target(
        key: String,
        enabled: Boolean = true,
        preferredKey: String = key,
    ): Modifier {
        val requester = remember(key) { FocusRequester() }
        val attachment =
            remember(key, requester) {
                { isAttached: Boolean ->
                    if (isAttached) {
                        attached[key] = requester
                    } else if (attached[key] === requester) {
                        attached.remove(key)
                    }
                    Unit
                }
            }
        // A reused lazy slot can update its attachment observer without detaching the modifier node.
        DisposableEffect(key, requester) { onDispose { attachment(false) } }
        return Modifier
            // Action callbacks are publication-bound. A surviving keyboard target belongs to the route.
            .focusProperties { canFocus = enabled && presentation.value.isCurrent() }
            .focusRequester(requester)
            .onFocusTargetAttachmentChanged(attachment)
            .onFocusChanged {
                if (it.isFocused) {
                    active = key
                    // Compose can focus the heading when a pending Restore disables its button.
                    // Keep the row's key for Retry or Reload unless the user navigates through Back.
                    val headingFallback = key == ARCHIVE_HEADING && preferred.value != ARCHIVE_BACK
                    if (!moving && !awaitingLanding && !headingFallback) preferred.value = preferredKey
                } else if (active == key) {
                    active = null
                }
            }
    }

    suspend fun restore(
        state: ArchivedTemplatesState,
        restoreStatus: ArchivedRestoreStatus,
        list: LazyListState,
        admission: SettingsActionAdmission,
    ) {
        val previous = preferred.value
        // Let the new lazy layout place the keyed item before trusting its current focus.
        withFrameNanos { }
        if (preferred.value != previous || !admission.isCurrent()) return
        val target = state.focusTarget(previous, restoreStatus)
        val index = target.rowIndex
        val visible = index == null || list.layoutInfo.visibleItemsInfo.any { it.key == target.key }
        if (target.key == active && visible && attached.containsKey(target.key)) {
            landed(target)
            return
        }
        if (index != null && !visible) list.scrollToItem(index)
        val requester = snapshotFlow { attached[target.key] }.first { it != null }
        withFrameNanos { }
        if (preferred.value == previous && attached[target.key] === requester && admission.isCurrent()) {
            moving = true
            try {
                if (requireNotNull(requester).requestFocus()) landed(target)
            } finally {
                moving = false
            }
        }
    }

    private fun landed(target: ArchiveFocusTarget) {
        awaitingLanding = false
        if (target.resetPreference) preferred.value = ARCHIVE_HEADING
    }
}

private data class ArchiveFocusTarget(
    val key: String,
    val rowIndex: Int? = null,
    val resetPreference: Boolean = false,
)

private fun ArchivedTemplatesState.focusTarget(
    previous: String,
    restores: ArchivedRestoreStatus,
): ArchiveFocusTarget =
    when {
        previous == ARCHIVE_BACK || previous == ARCHIVE_HEADING -> ArchiveFocusTarget(previous)
        readFailed -> ArchiveFocusTarget(ARCHIVE_RELOAD)
        !canRestore || archiveCompletion != restores.completion -> ArchiveFocusTarget(ARCHIVE_HEADING)
        else -> canonicalFocusTarget(previous, restores)
    }

private fun ArchivedTemplatesState.canonicalFocusTarget(
    previous: String,
    restores: ArchivedRestoreStatus,
): ArchiveFocusTarget {
    val index = items.orEmpty().indexOfFirst { it.id.archivedRowKey() == previous }
    val row = items?.getOrNull(index)
    return when {
        row == null -> ArchiveFocusTarget(ARCHIVE_HEADING, resetPreference = true)
        row.id in restores.pending -> ArchiveFocusTarget(ARCHIVE_HEADING)
        row.id == failedRestore -> ArchiveFocusTarget(ARCHIVE_RETRY)
        else -> ArchiveFocusTarget(previous, rowIndex = index)
    }
}
