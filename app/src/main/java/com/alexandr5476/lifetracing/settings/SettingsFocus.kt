@file:Suppress("FunctionNaming")

package com.alexandr5476.lifetracing.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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

internal const val SETTINGS_HEADING = "settings-heading"
internal const val SETTINGS_BACK = "settings-back"
internal const val SETTINGS_ARCHIVE = "settings-archived-templates"
internal const val SETTINGS_RELOAD = "settings-reload"
internal const val SETTINGS_RETRY = "settings-retry"

internal fun SettingsChange.retryFocusKey(): String = "$SETTINGS_RETRY:${optionFocusKey()}"

private fun SettingsChange.optionFocusKey(): String =
    when (this) {
        is SettingsChange.Theme -> "settings-theme-$value"
        is SettingsChange.Accent -> "settings-accent-$value"
        is SettingsChange.Language -> "settings-language-$value"
        is SettingsChange.InterfaceScale -> "settings-interface-$value"
        is SettingsChange.TextScale -> "settings-text-$value"
    }

@Composable
internal fun rememberSettingsFocus(
    state: SettingsState,
    presentation: SettingsPresentation,
    admission: SettingsActionAdmission,
): SettingsFocus {
    val preferred = rememberSaveable { mutableStateOf<String?>(null) }
    val currentPresentation = rememberUpdatedState(presentation)
    val focus = remember(preferred) { SettingsFocus(preferred, currentPresentation) }
    val foreground = LocalWindowInfo.current.isWindowFocused
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(admission, foreground, keyboard) {
        if (foreground && keyboard) {
            // Material disables a pending choice and detaches Retry before recovery can focus its replacement.
            withFrameNanos { }
            if (admission.isCurrent()) focus.restore(state)
        }
    }
    return focus
}

internal class SettingsFocus(
    private val preferred: MutableState<String?>,
    private val presentation: State<SettingsPresentation>,
) {
    // Only composed controls register here. The saved value is a logical target, never an action.
    private val attached = mutableMapOf<String, FocusRequester>()
    private var active: String? = null
    private var restoring: String? = null

    // A recreated window can focus a visible control when it enters keyboard mode.
    private var restoringSavedFocus = preferred.value != null

    @Composable
    fun target(
        id: String,
        enabled: Boolean = true,
        admission: SettingsActionAdmission? = null,
    ): Modifier {
        val requester = remember(id) { FocusRequester() }
        val attachment =
            remember(id, requester) {
                { isAttached: Boolean ->
                    if (isAttached) {
                        attached[id] = requester
                    } else if (attached[id] === requester) {
                        attached.remove(id)
                    }
                    Unit
                }
            }
        return Modifier
            .focusProperties { canFocus = enabled && (admission?.isCurrent() ?: presentation.value.isCurrent()) }
            .focusRequester(requester)
            .onFocusTargetAttachmentChanged(attachment)
            .onFocusChanged {
                if (it.isFocused) {
                    active = id
                    // Compose can land on the heading when a pending write disables its options.
                    if (id != SETTINGS_HEADING && restoring != id && !restoringSavedFocus) preferred.value = id
                } else if (active == id) {
                    active = null
                }
            }
    }

    fun restore(state: SettingsState) {
        val previous = preferred.value ?: SETTINGS_HEADING
        val target = state.focusAfterPublication(previous).takeIf(attached::containsKey) ?: SETTINGS_HEADING
        restoringSavedFocus = false
        if (active == target) return
        restoring = target
        try {
            attached[target]?.requestFocus()
        } finally {
            restoring = null
        }
    }

    fun preferArchive() {
        preferred.value = SETTINGS_ARCHIVE
    }
}

private fun SettingsState.focusAfterPublication(previous: String): String {
    val retry = failure?.retryFocusKey()
    return when {
        previous == SETTINGS_BACK || previous == SETTINGS_ARCHIVE || previous == SETTINGS_HEADING -> previous
        readFailed -> SETTINGS_RELOAD
        !ready || pending != null -> SETTINGS_HEADING
        previous == SETTINGS_RELOAD || previous.startsWith("$SETTINGS_RETRY:") ->
            previous.takeIf { it == retry } ?: SETTINGS_HEADING
        retry != null && failure.optionFocusKey() == previous -> retry
        else -> previous
    }
}
