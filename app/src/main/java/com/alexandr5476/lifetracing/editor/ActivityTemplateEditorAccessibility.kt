@file:Suppress("CyclomaticComplexMethod")

package com.alexandr5476.lifetracing.editor

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged

internal val LocalEditorFocus = staticCompositionLocalOf<EditorFocus> { error("Editor focus missing") }
internal val LocalEditorVisible = staticCompositionLocalOf { false }

/** Native focus bookkeeping only; accessibility inspection and focus never author data. */
internal class EditorFocus {
    val landing = FocusRequester()
    var owner: EditorFocusHandle? = null
    var removed by mutableStateOf(false)
    var hasFocus by mutableStateOf(false)
    val root: Modifier get() = Modifier.onFocusChanged { hasFocus = it.hasFocus }.focusGroup()
}

internal class EditorFocusHandle {
    val requester = FocusRequester()
    var attached = true
}

@Composable
internal fun rememberEditorFocus(
    state: ActivityTemplateEditorState,
    visible: Boolean,
): EditorFocus {
    val focus = remember { EditorFocus() }
    val window = LocalWindowInfo.current
    val inputMode = LocalInputModeManager.current.inputMode
    val phase =
        when (state.load) {
            ActivityTemplateEditorLoad.Loading -> 0
            is ActivityTemplateEditorLoad.Failure -> 1
            is ActivityTemplateEditorLoad.Ready -> 2
        }
    val editable =
        state.save !is ActivityTemplateEditorSave.Saving && state.save !is ActivityTemplateEditorSave.Committed
    var dialogWasOpen by remember { mutableStateOf(false) }
    var landingPending by remember { mutableStateOf(true) }
    LaunchedEffect(visible, phase, state.generation) { landingPending = visible }
    LaunchedEffect(state.discardConfirmationVisible) {
        if (state.discardConfirmationVisible) dialogWasOpen = true
    }
    LaunchedEffect(
        visible,
        phase,
        state.generation,
        state.discardConfirmationVisible,
        window.isWindowFocused,
        inputMode,
        editable,
        landingPending,
    ) {
        if (visible && window.isWindowFocused && editable) {
            if (state.discardConfirmationVisible) {
                dialogWasOpen = true
            } else {
                withFrameNanos { }
                if (dialogWasOpen) {
                    val target = focus.owner
                    val restored = target?.attached == true && target.requester.requestFocus()
                    if (restored || focus.landing.requestFocus()) {
                        dialogWasOpen = false
                        landingPending = false
                    }
                } else if (landingPending || !focus.hasFocus) {
                    landingPending = !focus.landing.requestFocus()
                }
            }
        }
    }
    val canRecover = visible && window.isWindowFocused && editable && !state.discardConfirmationVisible
    LaunchedEffect(canRecover, inputMode, focus.removed) {
        if (canRecover && focus.removed) {
            withFrameNanos { }
            // Detachment may assign framework fallback focus before this deferred recovery.
            if (focus.removed && focus.landing.requestFocus()) focus.removed = false
        }
    }
    return focus
}

/** Tab traverses the form instead of inserting draft text in a multiline Material editor. */
@Composable
internal fun editorInputFocusTarget(description: String? = null): Modifier {
    val manager = LocalFocusManager.current
    return editorFocusTarget(description).onPreviewKeyEvent {
        if (it.key == Key.Tab) {
            if (it.type == KeyEventType.KeyDown) {
                manager.moveFocus(if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
            }
            true
        } else {
            false
        }
    }
}

@Composable
internal fun editorFocusTarget(description: String? = null): Modifier {
    val focus = LocalEditorFocus.current
    val handle = remember { EditorFocusHandle() }
    return Modifier
        .onFocusTargetAttachmentChanged { attached ->
            handle.attached = attached
            if (!attached && focus.owner === handle) focus.removed = true
        }.focusRequester(handle.requester)
        .onFocusChanged {
            if (it.isFocused) {
                focus.owner = handle
            }
        }.semantics { if (description != null) contentDescription = description }
}
