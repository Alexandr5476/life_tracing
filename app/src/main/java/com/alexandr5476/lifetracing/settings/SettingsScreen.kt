@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:Suppress("FunctionNaming", "LongParameterList")

package com.alexandr5476.lifetracing.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.ui.appearance.AppLanguage
import com.alexandr5476.lifetracing.ui.appearance.AppLanguageController
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferencesRepository
import com.alexandr5476.lifetracing.ui.appearance.AppearanceScalePolicy
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun SettingsRoute(
    repository: AppearancePreferencesRepository,
    appearanceMutationScope: CoroutineScope,
    onBack: () -> Unit,
    onArchivedTemplates: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val controller =
        remember(repository, scope, appearanceMutationScope) {
            SettingsController(
                repository.preferences,
                RepositorySettingsWriter(repository),
                AppLanguageController::current,
                AppLanguageController::apply,
                scope,
                appearanceMutationScope,
            )
        }
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration) { controller.refreshLanguage() }
    val state by controller.state.collectAsState()
    SettingsScreen(
        state,
        controller::change,
        controller::retryMutation,
        controller::retryRead,
        onBack,
        onArchivedTemplates,
    )
}

@Composable
@Suppress("LongMethod")
internal fun SettingsScreen(
    state: SettingsState,
    onChange: (SettingsChange) -> Unit,
    onRetryMutation: () -> Unit,
    onRetryRead: () -> Unit,
    onBack: () -> Unit,
    onArchivedTemplates: () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .testTag("settings-page")
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            Text(
                stringResource(R.string.settings_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
            LifeTracingSecondaryButton(onClick = onBack, modifier = Modifier.testTag("settings-back")) {
                Text(stringResource(R.string.settings_back))
            }
            LifeTracingSecondaryButton(
                onClick = onArchivedTemplates,
                modifier = Modifier.testTag("settings-archived-templates"),
            ) {
                Text(stringResource(R.string.archived_templates_title))
            }
            SettingsFeedback(state, onRetryMutation, onRetryRead)
            val appearance = state.appearance
            val language = state.language
            if (appearance != null && language != null) {
                val enabled = state.ready && state.pending == null
                SettingsOptions(
                    R.string.settings_theme,
                    ThemeMode.entries,
                    appearance.themeMode,
                    enabled,
                    "settings-theme",
                    { stringResource(it.labelResource()) },
                ) { onChange(SettingsChange.Theme(it)) }
                SettingsOptions(
                    R.string.settings_accent,
                    AccentPaletteId.entries,
                    appearance.accentPaletteId,
                    enabled,
                    "settings-accent",
                    { stringResource(it.labelResource()) },
                ) { onChange(SettingsChange.Accent(it)) }
                SettingsOptions(
                    R.string.settings_language,
                    AppLanguage.entries,
                    language,
                    enabled,
                    "settings-language",
                    { stringResource(it.labelResource()) },
                ) { onChange(SettingsChange.Language(it)) }
                SettingsOptions(
                    R.string.settings_interface_scale,
                    AppearanceScalePolicy.interfacePercentages,
                    appearance.interfaceScalePercent,
                    enabled,
                    "settings-interface",
                    { stringResource(R.string.settings_percent, it) },
                ) { onChange(SettingsChange.InterfaceScale(it)) }
                SettingsOptions(
                    R.string.settings_text_scale,
                    AppearanceScalePolicy.textPercentages,
                    appearance.textScalePercent,
                    enabled,
                    "settings-text",
                    { stringResource(R.string.settings_percent, it) },
                ) { onChange(SettingsChange.TextScale(it)) }
            }
        }
    }
}

@Composable
private fun SettingsFeedback(
    state: SettingsState,
    onRetryMutation: () -> Unit,
    onRetryRead: () -> Unit,
) {
    if (state.appearanceReadFailed || state.languageReadFailed) {
        Text(stringResource(R.string.settings_read_failure))
        LifeTracingSecondaryButton(onClick = onRetryRead, modifier = Modifier.testTag("settings-reload")) {
            Text(stringResource(R.string.settings_reload))
        }
    } else if (state.appearance == null || state.language == null) {
        Text(stringResource(R.string.settings_loading))
    }
    if (state.pending != null) {
        Text(
            stringResource(R.string.settings_saving),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.failure != null) {
        Text(
            stringResource(R.string.settings_write_failure),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
        )
        LifeTracingSecondaryButton(onClick = onRetryMutation, modifier = Modifier.testTag("settings-retry")) {
            Text(stringResource(R.string.settings_retry))
        }
    }
}

@Composable
private fun <T> SettingsOptions(
    titleResource: Int,
    values: List<T>,
    selected: T,
    enabled: Boolean,
    tagPrefix: String,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    val title = stringResource(titleResource)
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            values.forEach { value ->
                val text = label(value)
                val description = stringResource(R.string.settings_option_description, title, text)
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    modifier =
                        Modifier
                            .testTag("$tagPrefix-$value")
                            .semantics { contentDescription = description },
                    label = { Text(text) },
                )
            }
        }
    }
}

private fun ThemeMode.labelResource(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_system
        ThemeMode.LIGHT -> R.string.settings_light
        ThemeMode.DARK -> R.string.settings_dark
    }

private fun AccentPaletteId.labelResource(): Int =
    when (this) {
        AccentPaletteId.DEFAULT -> R.string.settings_accent_default
        AccentPaletteId.SLATE -> R.string.settings_accent_slate
    }

private fun AppLanguage.labelResource(): Int =
    when (this) {
        AppLanguage.SYSTEM -> R.string.settings_system
        AppLanguage.ENGLISH -> R.string.settings_english
        AppLanguage.RUSSIAN -> R.string.settings_russian
    }
