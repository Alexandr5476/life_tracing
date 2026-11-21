@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:Suppress("FunctionNaming", "LongParameterList")

package com.alexandr5476.lifetracing.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.ui.appearance.AppLanguage
import com.alexandr5476.lifetracing.ui.appearance.AppLanguageController
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferencesRepository
import com.alexandr5476.lifetracing.ui.appearance.AppearanceScalePolicy
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import com.alexandr5476.lifetracing.ui.theme.spacing

@Composable
internal fun SettingsRoute(
    repository: AppearancePreferencesRepository,
    appearanceMutations: SettingsAppearanceMutations,
    isRouteVisible: Boolean,
    ownsRoute: () -> Boolean,
    onBack: () -> Unit,
    onArchivedTemplates: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller =
        remember(repository, scope, appearanceMutations) {
            SettingsController(
                repository.preferences,
                RepositorySettingsWriter(repository),
                AppLanguageController::current,
                AppLanguageController::apply,
                scope,
                appearanceMutations,
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
        isRouteVisible,
        ownsRoute,
        readCurrent = { controller.state.value },
    )
}

@Composable
internal fun SettingsScreen(
    state: SettingsState,
    onChange: (SettingsChange) -> Unit,
    onRetryMutation: () -> Unit,
    onRetryRead: () -> Unit,
    onBack: () -> Unit,
    onArchivedTemplates: () -> Unit = {},
    isRouteVisible: Boolean = true,
    ownsRoute: () -> Boolean = { true },
    readCurrent: () -> SettingsState,
) {
    val presentation = rememberSettingsPresentation(isRouteVisible, ownsRoute)
    val admission = presentation.rememberAdmission(state, readCurrent)
    val focus = rememberSettingsFocus(state, presentation, admission)
    val pageSemantics =
        if (isRouteVisible) {
            Modifier.semantics { isTraversalGroup = true }
        } else {
            Modifier.clearAndSetSemantics { hideFromAccessibility() }
        }
    LifeTracingAccessibleTargets {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier =
                    Modifier
                        .testTag("settings-page")
                        .then(pageSemantics)
                        .settingsKeyboardBack(presentation, onBack)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacing.xLarge),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
            ) {
                SettingsHeading(focus)
                SettingsActionButton(
                    presentation::isCurrent,
                    onBack,
                    focus.target(SETTINGS_BACK).testTag(SETTINGS_BACK),
                ) {
                    Text(stringResource(R.string.settings_back))
                }
                SettingsActionButton(
                    presentation::isCurrent,
                    onClick = {
                        focus.preferArchive()
                        onArchivedTemplates()
                    },
                    modifier = focus.target(SETTINGS_ARCHIVE).testTag(SETTINGS_ARCHIVE),
                ) {
                    Text(stringResource(R.string.archived_templates_title))
                }
                SettingsFeedback(state, admission, focus, onRetryMutation, onRetryRead)
                SettingsPreferenceGroups(state, admission, focus, onChange)
            }
        }
    }
}

@Composable
private fun SettingsHeading(focus: SettingsFocus) {
    val interactions = remember { MutableInteractionSource() }
    Text(
        stringResource(R.string.settings_title),
        modifier =
            focus
                .target(SETTINGS_HEADING)
                .testTag(SETTINGS_HEADING)
                .semantics { heading() }
                .indication(interactions, ripple())
                .focusable(interactionSource = interactions),
        style = MaterialTheme.typography.headlineSmall,
    )
}

@Composable
private fun SettingsPreferenceGroups(
    state: SettingsState,
    admission: SettingsActionAdmission,
    focus: SettingsFocus,
    onChange: (SettingsChange) -> Unit,
) {
    val appearance = state.appearance ?: return
    val language = state.language ?: return
    val enabled = state.ready && state.pending == null
    SettingsOptions(
        admission,
        focus,
        R.string.settings_theme,
        ThemeMode.entries,
        appearance.themeMode,
        enabled,
        "settings-theme",
        { stringResource(it.labelResource()) },
    ) { onChange(SettingsChange.Theme(it)) }
    SettingsOptions(
        admission,
        focus,
        R.string.settings_accent,
        AccentPaletteId.entries,
        appearance.accentPaletteId,
        enabled,
        "settings-accent",
        { stringResource(it.labelResource()) },
    ) { onChange(SettingsChange.Accent(it)) }
    SettingsOptions(
        admission,
        focus,
        R.string.settings_language,
        AppLanguage.entries,
        language,
        enabled,
        "settings-language",
        { stringResource(it.labelResource()) },
    ) { onChange(SettingsChange.Language(it)) }
    SettingsOptions(
        admission,
        focus,
        R.string.settings_interface_scale,
        AppearanceScalePolicy.interfacePercentages,
        appearance.interfaceScalePercent,
        enabled,
        "settings-interface",
        { stringResource(R.string.settings_percent, it) },
    ) { onChange(SettingsChange.InterfaceScale(it)) }
    SettingsOptions(
        admission,
        focus,
        R.string.settings_text_scale,
        AppearanceScalePolicy.textPercentages,
        appearance.textScalePercent,
        enabled,
        "settings-text",
        { stringResource(R.string.settings_percent, it) },
    ) { onChange(SettingsChange.TextScale(it)) }
}

@Composable
private fun <T> SettingsOptions(
    admission: SettingsActionAdmission,
    focus: SettingsFocus,
    titleResource: Int,
    values: List<T>,
    selected: T,
    enabled: Boolean,
    tagPrefix: String,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    val title = stringResource(titleResource)
    Column(
        modifier = Modifier.testTag("$tagPrefix-group").selectableGroup().semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            values.forEach { value ->
                val text = label(value)
                val description = stringResource(R.string.settings_option_description, title, text)
                val tag = "$tagPrefix-$value"
                val action = rememberSettingsAction(admission::isCurrent, enabled) { onSelect(value) }
                FilterChip(
                    selected = value == selected,
                    onClick = { action() },
                    enabled = enabled,
                    modifier =
                        focus
                            .target(tag, enabled, admission)
                            .testTag(tag)
                            .settingsActionSemantics(action)
                            .semantics {
                                contentDescription = description
                                role = Role.RadioButton
                            },
                    label = { Text(text, Modifier.clearAndSetSemantics {}) },
                )
            }
        }
    }
}

internal fun ThemeMode.labelResource(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_system
        ThemeMode.LIGHT -> R.string.settings_light
        ThemeMode.DARK -> R.string.settings_dark
    }

internal fun AccentPaletteId.labelResource(): Int =
    when (this) {
        AccentPaletteId.DEFAULT -> R.string.settings_accent_default
        AccentPaletteId.SLATE -> R.string.settings_accent_slate
    }

internal fun AppLanguage.labelResource(): Int =
    when (this) {
        AppLanguage.SYSTEM -> R.string.settings_system
        AppLanguage.ENGLISH -> R.string.settings_english
        AppLanguage.RUSSIAN -> R.string.settings_russian
    }
