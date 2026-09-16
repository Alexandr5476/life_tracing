@file:Suppress("FunctionNaming", "LongMethod", "MaxLineLength")

package com.alexandr5476.lifetracing.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing

@Composable
fun ManualActivityEntryRoute(
    controller: ManualActivityEntryController,
    onBack: () -> Unit,
    onCommitted: () -> Unit,
) {
    val state by controller.state.collectAsState()
    LaunchedEffect(state.command) { if (state.command is ManualEntryCommand.Committed) onCommitted() }
    ManualActivityEntryScreen(state, controller::dispatch, onBack)
}

@Composable
internal fun ManualActivityEntryScreen(
    state: ManualActivityEntryState,
    onAction: (ManualActivityEntryAction) -> Unit,
    onBack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.manual_history_title), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text(stringResource(R.string.history_back)) }
            }
            Catalog(state.catalog, state.canLoadMore, onAction)
            when (val selected = state.selected) {
                ManualEntryLoad.Idle -> Unit
                ManualEntryLoad.Loading -> Text(stringResource(R.string.manual_history_template_loading))
                is ManualEntryLoad.Failure -> Text(selected.message, color = MaterialTheme.colorScheme.error)
                is ManualEntryLoad.Content -> EntryForm(selected.value, state, onAction)
            }
            Command(state.command, onAction)
        }
    }
}

@Composable
private fun Catalog(
    catalog: ManualEntryLoad<List<com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem>>,
    canLoadMore: Boolean,
    onAction: (ManualActivityEntryAction) -> Unit,
) {
    Text(stringResource(R.string.manual_history_choose_activity), style = MaterialTheme.typography.titleMedium)
    when (catalog) {
        ManualEntryLoad.Idle, ManualEntryLoad.Loading -> Text(stringResource(R.string.manual_history_loading))
        is ManualEntryLoad.Failure -> {
            Text(catalog.message, color = MaterialTheme.colorScheme.error)
            LifeTracingSecondaryButton(onClick = {
                onAction(ManualActivityEntryAction.RetryCatalog)
            }) { Text(stringResource(R.string.history_retry)) }
        }
        is ManualEntryLoad.Content -> {
            catalog.value.forEach { item ->
                TextButton(
                    onClick = { onAction(ManualActivityEntryAction.Select(item.id)) },
                    modifier = Modifier.fillMaxWidth().testTag("manual-history-activity-${item.id.value}"),
                ) { Text(item.name) }
            }
            if (canLoadMore) {
                LifeTracingSecondaryButton(onClick = {
                    onAction(ManualActivityEntryAction.LoadMore)
                }) { Text(stringResource(R.string.manual_history_load_more)) }
            }
        }
    }
}

@Composable
private fun EntryForm(
    template: ActivityTemplate,
    state: ManualActivityEntryState,
    onAction: (ManualActivityEntryAction) -> Unit,
) {
    Text(template.name, style = MaterialTheme.typography.titleLarge)
    if (template.timeTrackingMode == TimeTrackingMode.TIMER) {
        Text(stringResource(R.string.manual_history_timer_target, template.timerTarget.toString()))
    }
    if (template.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
        TimeInput(
            R.string.manual_history_started,
            state.startedText,
        ) { onAction(ManualActivityEntryAction.EditStarted(it)) }
    }
    TimeInput(R.string.manual_history_completed, state.completedText) {
        onAction(ManualActivityEntryAction.EditCompleted(it))
    }
    template.fields.filter { it.deletedAt == null }.forEach { field ->
        Field(field, state.values.getValue(field.id), onAction)
    }
    LifeTracingPrimaryButton(
        onClick = { onAction(ManualActivityEntryAction.Save) },
        enabled = state.command !is ManualEntryCommand.Committing,
        modifier = Modifier.testTag("manual-history-save"),
    ) { Text(stringResource(R.string.manual_history_save)) }
}

@Composable
private fun TimeInput(
    label: Int,
    value: String,
    onValueChange: (String) -> Unit,
) = LifeTracingOutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = Modifier.fillMaxWidth(),
    label = { Text(stringResource(label)) },
    supportingText = { Text(stringResource(R.string.manual_history_datetime_hint)) },
)

@Composable
private fun Field(
    field: ActivityTemplateField,
    draft: ManualEntryFieldDraft,
    onAction: (ManualActivityEntryAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        Text(field.name, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.manual_history_configured, configured(field)),
            style = MaterialTheme.typography.bodySmall,
        )
        when (field.type) {
            CustomFieldType.NUMBER ->
                LifeTracingOutlinedTextField(
                    draft.numberText,
                    { onAction(ManualActivityEntryAction.EditNumber(field.id, it)) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.manual_history_actual)) },
                    enabled = !draft.missing,
                )
            CustomFieldType.TEXT ->
                LifeTracingOutlinedTextField(
                    draft.text,
                    { onAction(ManualActivityEntryAction.EditText(field.id, it)) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.manual_history_actual)) },
                    enabled = !draft.missing,
                )
            CustomFieldType.CATEGORY ->
                field.categoryOptions.filterNot { it.isArchived }.forEach { option ->
                    TextButton(
                        onClick = { onAction(ManualActivityEntryAction.SelectCategory(field.id, option.id)) },
                        modifier = Modifier.testTag("manual-history-option-${option.id.value}"),
                    ) {
                        Text(
                            if (draft.selectedOptionId == option.id &&
                                !draft.missing
                            ) {
                                "✓ ${option.label}"
                            } else {
                                option.label
                            },
                        )
                    }
                }
        }
        LifeTracingSecondaryButton(onClick = { onAction(ManualActivityEntryAction.SetMissing(field.id)) }) {
            Text(stringResource(R.string.manual_history_set_missing))
        }
    }
}

private fun configured(field: ActivityTemplateField): String =
    when (field.type) {
        CustomFieldType.NUMBER ->
            formatLauncherNumber(
                field.defaultNumberScaled,
                field.displayPrecision,
            ).ifBlank { "—" }
        CustomFieldType.CATEGORY ->
            field.categoryOptions.firstOrNull { it.id == field.defaultCategoryOptionId }?.label
                ?: "—"
        CustomFieldType.TEXT -> field.defaultText ?: "—"
    }

@Composable
private fun Command(
    command: ManualEntryCommand,
    onAction: (ManualActivityEntryAction) -> Unit,
) {
    when (command) {
        ManualEntryCommand.Idle -> Unit
        is ManualEntryCommand.Invalid -> Text(command.message, color = MaterialTheme.colorScheme.error)
        ManualEntryCommand.Committing -> Text(stringResource(R.string.manual_history_saving))
        is ManualEntryCommand.Failure -> {
            Text(command.message, color = MaterialTheme.colorScheme.error)
            LifeTracingSecondaryButton(onClick = {
                onAction(ManualActivityEntryAction.Save)
            }) { Text(stringResource(R.string.history_retry)) }
        }
        is ManualEntryCommand.Overlap -> {
            Text(stringResource(R.string.manual_history_overlap), color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                LifeTracingSecondaryButton(onClick = {
                    onAction(ManualActivityEntryAction.CancelOverlap)
                }) { Text(stringResource(R.string.manual_history_cancel)) }
                LifeTracingPrimaryButton(onClick = {
                    onAction(ManualActivityEntryAction.ProceedOverlap)
                }) { Text(stringResource(R.string.manual_history_proceed)) }
            }
        }
        is ManualEntryCommand.Committed -> Unit
    }
}
