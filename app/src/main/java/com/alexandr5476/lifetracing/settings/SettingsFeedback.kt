@file:Suppress("FunctionNaming")

package com.alexandr5476.lifetracing.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R

@Composable
internal fun SettingsFeedback(
    state: SettingsState,
    admission: SettingsActionAdmission,
    focus: SettingsFocus,
    onRetryMutation: () -> Unit,
    onRetryRead: () -> Unit,
) {
    SettingsReadFeedback(state, admission, focus, onRetryRead)
    SettingsMutationFeedback(state, admission, focus, onRetryMutation)
}

@Composable
private fun SettingsReadFeedback(
    state: SettingsState,
    admission: SettingsActionAdmission,
    focus: SettingsFocus,
    onRetryRead: () -> Unit,
) {
    val readStatus =
        when {
            state.appearanceReadFailed && state.languageReadFailed -> R.string.settings_read_failure
            state.appearanceReadFailed -> R.string.settings_appearance_read_failure
            state.languageReadFailed -> R.string.settings_language_read_failure
            !state.ready -> R.string.settings_loading
            else -> null
        }
    if (readStatus != null) {
        SettingsStatus(stringResource(readStatus), "settings-read-status", state.readFailed)
    }
    if (state.readFailed) {
        SettingsActionButton(
            admission::isCurrent,
            onRetryRead,
            focus.target(SETTINGS_RELOAD, admission = admission).testTag(SETTINGS_RELOAD),
        ) {
            Text(stringResource(R.string.settings_reload))
        }
    }
}

@Composable
private fun SettingsMutationFeedback(
    state: SettingsState,
    admission: SettingsActionAdmission,
    focus: SettingsFocus,
    onRetryMutation: () -> Unit,
) {
    val change = state.pending ?: state.failure ?: return
    val mutationStatus =
        when {
            state.failure != null -> R.string.settings_write_failure
            state.writeCompleted -> R.string.settings_awaiting_observation
            state.retryingMutation -> R.string.settings_retrying
            change is SettingsChange.Language -> R.string.settings_applying_language
            else -> R.string.settings_saving
        }
    val choice = change.choiceDescription()
    SettingsStatus(stringResource(mutationStatus, choice), "settings-mutation-status", state.failure != null)
    if (state.failure != null) {
        val enabled = state.ready && state.pending == null
        val retryDescription = stringResource(R.string.settings_retry_description, choice)
        SettingsActionButton(
            admission::isCurrent,
            onRetryMutation,
            focus.target(change.retryFocusKey(), enabled, admission).testTag(SETTINGS_RETRY).semantics {
                contentDescription = retryDescription
            },
            enabled = enabled,
        ) {
            Text(stringResource(R.string.settings_retry), Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun SettingsStatus(
    text: String,
    tag: String,
    failed: Boolean,
) {
    Text(
        text,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun SettingsChange.choiceDescription(): String {
    val (group, option) =
        when (this) {
            is SettingsChange.Theme -> R.string.settings_theme to stringResource(value.labelResource())
            is SettingsChange.Accent -> R.string.settings_accent to stringResource(value.labelResource())
            is SettingsChange.Language -> R.string.settings_language to stringResource(value.labelResource())
            is SettingsChange.InterfaceScale ->
                R.string.settings_interface_scale to stringResource(R.string.settings_percent, value)
            is SettingsChange.TextScale ->
                R.string.settings_text_scale to stringResource(R.string.settings_percent, value)
        }
    return stringResource(R.string.settings_option_description, stringResource(group), option)
}
