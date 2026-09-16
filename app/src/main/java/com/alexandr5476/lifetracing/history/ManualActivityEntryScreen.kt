@file:Suppress("FunctionNaming", "LongMethod", "MaxLineLength", "TooManyFunctions")

package com.alexandr5476.lifetracing.history

import android.text.format.DateUtils
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
import com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.time.Duration
import java.time.ZoneOffset

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
                is ManualEntryLoad.Failure -> IssueText(selected.issue)
                is ManualEntryLoad.Content -> EntryForm(selected.value, state, onAction)
            }
            Command(state.command, state.startedIssue, state.completedIssue, onAction)
        }
    }
}

@Composable
private fun Catalog(
    catalog: ManualEntryLoad<List<ReusableActivityCatalogItem>>,
    canLoadMore: Boolean,
    onAction: (ManualActivityEntryAction) -> Unit,
) {
    Text(stringResource(R.string.manual_history_choose_activity), style = MaterialTheme.typography.titleMedium)
    when (catalog) {
        ManualEntryLoad.Idle, ManualEntryLoad.Loading -> Text(stringResource(R.string.manual_history_loading))
        is ManualEntryLoad.Failure -> {
            IssueText(catalog.issue)
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
        Text(stringResource(R.string.manual_history_timer_target, requireNotNull(template.timerTarget).durationText()))
    }
    if (template.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
        TimeInput(R.string.manual_history_started, state.startedText) {
            onAction(ManualActivityEntryAction.EditStarted(it))
        }
        state.startedIssue?.let { IssueText(it) }
        state.startedAmbiguity?.let { AmbiguityChoices(it, true, onAction) }
    }
    TimeInput(R.string.manual_history_completed, state.completedText) {
        onAction(ManualActivityEntryAction.EditCompleted(it))
    }
    state.completedIssue?.let { IssueText(it) }
    state.completedAmbiguity?.let { AmbiguityChoices(it, false, onAction) }
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
private fun AmbiguityChoices(
    ambiguity: ManualTimeAmbiguity,
    started: Boolean,
    onAction: (ManualActivityEntryAction) -> Unit,
) {
    Text(stringResource(R.string.manual_history_ambiguous_choose), color = MaterialTheme.colorScheme.error)
    ambiguity.offsets.forEachIndexed { index, offset ->
        LifeTracingSecondaryButton(
            onClick = {
                onAction(
                    if (started) {
                        ManualActivityEntryAction.SelectStartedOffset(offset)
                    } else {
                        ManualActivityEntryAction.SelectCompletedOffset(offset)
                    },
                )
            },
            modifier = Modifier.testTag("manual-history-${if (started) "started" else "completed"}-offset-$index"),
        ) {
            Text(
                stringResource(
                    if (index ==
                        0
                    ) {
                        R.string.manual_history_first_occurrence
                    } else {
                        R.string.manual_history_second_occurrence
                    },
                    offset.utcLabel(),
                ),
            )
        }
    }
}

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
        if (draft.missing) Text(stringResource(R.string.manual_history_missing_value))
        when (field.type) {
            CustomFieldType.NUMBER ->
                LifeTracingOutlinedTextField(
                    draft.numberText,
                    { onAction(ManualActivityEntryAction.EditNumber(field.id, it)) },
                    Modifier.fillMaxWidth().testTag("manual-history-field-${field.id.value}-actual"),
                    label = { Text(stringResource(R.string.manual_history_actual)) },
                )
            CustomFieldType.TEXT ->
                LifeTracingOutlinedTextField(
                    draft.text,
                    { onAction(ManualActivityEntryAction.EditText(field.id, it)) },
                    Modifier.fillMaxWidth().testTag("manual-history-field-${field.id.value}-actual"),
                    label = { Text(stringResource(R.string.manual_history_actual)) },
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
        LifeTracingSecondaryButton(
            onClick = {
                onAction(
                    if (draft.missing) {
                        ManualActivityEntryAction.SetPresent(field.id)
                    } else {
                        ManualActivityEntryAction.SetMissing(field.id)
                    },
                )
            },
            modifier = Modifier.testTag("manual-history-field-${field.id.value}-missing"),
        ) {
            Text(
                stringResource(
                    if (draft.missing) R.string.manual_history_restore_value else R.string.manual_history_set_missing,
                ),
            )
        }
    }
}

@Composable
private fun configured(field: ActivityTemplateField): String =
    when (field.type) {
        CustomFieldType.NUMBER ->
            formatLauncherNumber(field.defaultNumberScaled, field.displayPrecision)
                .ifBlank { stringResource(R.string.manual_history_missing_value) }
        CustomFieldType.CATEGORY ->
            field.categoryOptions.firstOrNull { it.id == field.defaultCategoryOptionId }?.label
                ?: stringResource(R.string.manual_history_missing_value)
        CustomFieldType.TEXT -> field.defaultText ?: stringResource(R.string.manual_history_missing_value)
    }

@Composable
private fun Command(
    command: ManualEntryCommand,
    startedIssue: ManualEntryIssue?,
    completedIssue: ManualEntryIssue?,
    onAction: (ManualActivityEntryAction) -> Unit,
) {
    when (command) {
        ManualEntryCommand.Idle -> Unit
        is ManualEntryCommand.Invalid ->
            if (command.issue != startedIssue && command.issue != completedIssue) IssueText(command.issue)
        ManualEntryCommand.Committing -> Text(stringResource(R.string.manual_history_saving))
        is ManualEntryCommand.Failure -> {
            IssueText(command.issue)
            LifeTracingSecondaryButton(
                onClick = {
                    onAction(
                        if (command.issue == ManualEntryIssue.TEMPLATE_STALE) {
                            ManualActivityEntryAction.ReviewStaleTemplate
                        } else {
                            ManualActivityEntryAction.Save
                        },
                    )
                },
            ) {
                Text(
                    stringResource(
                        if (command.issue == ManualEntryIssue.TEMPLATE_STALE) {
                            R.string.manual_history_review_current
                        } else {
                            R.string.history_retry
                        },
                    ),
                )
            }
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

@Composable
private fun IssueText(issue: ManualEntryIssue) {
    val resource =
        when (issue) {
            ManualEntryIssue.INVALID_DATE_TIME -> R.string.manual_history_invalid_datetime
            ManualEntryIssue.NONEXISTENT_LOCAL_TIME -> R.string.manual_history_nonexistent_time
            ManualEntryIssue.AMBIGUOUS_LOCAL_TIME -> R.string.manual_history_ambiguous_time
            ManualEntryIssue.FUTURE_COMPLETION -> R.string.manual_history_future_completion
            ManualEntryIssue.REVERSED_INTERVAL -> R.string.manual_history_reversed_interval
            ManualEntryIssue.INVALID_NUMBER -> R.string.manual_history_invalid_number
            ManualEntryIssue.INVALID_CATEGORY -> R.string.manual_history_invalid_category
            ManualEntryIssue.TEMPLATE_UNAVAILABLE -> R.string.manual_history_template_unavailable
            ManualEntryIssue.TEMPLATE_STALE -> R.string.manual_history_template_stale
            ManualEntryIssue.CATALOG_READ_FAILURE -> R.string.manual_history_catalog_failure
            ManualEntryIssue.TEMPLATE_READ_FAILURE -> R.string.manual_history_template_failure
            ManualEntryIssue.SAVE_FAILURE -> R.string.manual_history_save_failure
        }
    Text(stringResource(resource), color = MaterialTheme.colorScheme.error)
}

private fun Duration.durationText(): String = DateUtils.formatElapsedTime(seconds.coerceAtLeast(0))

private fun ZoneOffset.utcLabel(): String = "UTC${if (this == ZoneOffset.UTC) "+00:00" else id}"
