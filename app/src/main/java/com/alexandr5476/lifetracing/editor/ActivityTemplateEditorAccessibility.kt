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
import androidx.compose.ui.input.InputMode
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

/** Lazy detachment alone does not mean a logically surviving control was removed. */
internal val LocalEditorTargetExists = staticCompositionLocalOf<(() -> Boolean)?> { null }

/** Native focus bookkeeping only; accessibility inspection and focus never author data. */
internal class EditorFocus {
    val landing = FocusRequester()
    var dialogOrigin: EditorFocusHandle? = null
    private var dialogOpen = false

    fun observeDialog(open: Boolean) {
        if (open && !dialogOpen) dialogOrigin = owner
        dialogOpen = open
    }

    var revealLanding: suspend () -> Unit = {}

    suspend fun land(): Boolean {
        revealLanding()
        withFrameNanos { }
        return landing.requestFocus()
    }

    fun observeLogicalTargets() {
        if (owner?.logicallyExists?.invoke() == false) removed = true
    }

    var owner: EditorFocusHandle? = null
    var removed by mutableStateOf(false)
    var hasFocus by mutableStateOf(false)
    val root: Modifier get() = Modifier.onFocusChanged { hasFocus = it.hasFocus }.focusGroup()
}

internal class EditorFocusHandle {
    var logicallyExists: (() -> Boolean)? = null
    val requester = FocusRequester()
    var attached = true
}

@Composable
internal fun rememberEditorFocus(
    state: ActivityTemplateEditorState,
    visible: Boolean,
): EditorFocus {
    val phase =
        when (state.load) {
            ActivityTemplateEditorLoad.Loading -> 0
            is ActivityTemplateEditorLoad.Failure -> 1
            is ActivityTemplateEditorLoad.Ready -> 2
        }
    return rememberEditorFocus(
        phase,
        state.generation,
        state.discardConfirmationVisible,
        state.save !is ActivityTemplateEditorSave.Saving && state.save !is ActivityTemplateEditorSave.Committed,
        visible,
    )
}

@Composable
@Suppress("LongParameterList") // Shared phase state and Sequence native-input admission remain explicit.
internal fun rememberEditorFocus(
    phase: Int,
    generation: Long,
    discardConfirmationVisible: Boolean,
    editable: Boolean,
    visible: Boolean,
    keyboardOnly: Boolean = false,
): EditorFocus {
    val focus = remember { EditorFocus() }
    focus.observeDialog(discardConfirmationVisible)
    val window = LocalWindowInfo.current
    val inputMode = LocalInputModeManager.current.inputMode
    val admitsFocus = !keyboardOnly || inputMode == InputMode.Keyboard
    val ownsFocus = visible && window.isWindowFocused && admitsFocus
    var dialogWasOpen by remember { mutableStateOf(false) }
    var landingPending by remember { mutableStateOf(true) }
    LaunchedEffect(visible, phase, generation) { landingPending = visible }
    LaunchedEffect(discardConfirmationVisible) {
        if (discardConfirmationVisible) dialogWasOpen = true
    }
    LaunchedEffect(
        visible,
        phase,
        generation,
        discardConfirmationVisible,
        window.isWindowFocused,
        inputMode,
        editable,
        landingPending,
    ) {
        if (ownsFocus && editable) {
            if (discardConfirmationVisible) {
                dialogWasOpen = true
            } else {
                withFrameNanos { }
                if (dialogWasOpen) {
                    val target = focus.dialogOrigin
                    val restored = target?.attached == true && target.requester.requestFocus()
                    if (restored || focus.land()) {
                        dialogWasOpen = false
                        landingPending = false
                    }
                } else if (landingPending || !focus.hasFocus) {
                    landingPending = !focus.land()
                }
            }
        }
    }
    val canRecover = ownsFocus && editable && !discardConfirmationVisible
    LaunchedEffect(canRecover, inputMode, focus.removed) {
        if (canRecover && focus.removed) {
            withFrameNanos { }
            // Detachment may assign framework fallback focus before this deferred recovery.
            if (focus.hasFocus || focus.land()) focus.removed = false
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
    val targetExists = LocalEditorTargetExists.current
    handle.logicallyExists = targetExists
    return Modifier
        .onFocusTargetAttachmentChanged { attached ->
            handle.attached = attached
            if (!attached && focus.owner === handle && targetExists?.invoke() != true) focus.removed = true
        }.focusRequester(handle.requester)
        .onFocusChanged {
            if (it.isFocused) {
                focus.owner = handle
            }
        }.semantics { if (description != null) contentDescription = description }
}
