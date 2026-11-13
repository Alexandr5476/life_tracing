@file:Suppress("FunctionNaming", "LongMethod", "MaxLineLength", "TooManyFunctions", "LongParameterList")

package com.alexandr5476.lifetracing.history

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
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
    isRouteVisible: Boolean = true,
    ownsRoute: () -> Boolean = { isRouteVisible },
) {
    key(controller) {
        val state by controller.state.collectAsState()
        val complete by rememberUpdatedState(onCommitted)
        val owner by rememberUpdatedState(ownsRoute)
        LifecycleResumeEffect(controller) {
            controller.refreshZoneContext()
            onPauseOrDispose { }
        }
        LaunchedEffect(controller, state.command, isRouteVisible) {
            if (isRouteVisible && owner() && state.command is ManualEntryCommand.Committed) complete()
        }
        ManualActivityEntryScreen(
            state,
            controller::dispatch,
            onBack,
            isRouteVisible,
            readCurrent = { controller.state.value },
            ownsRoute = ownsRoute,
        )
    }
}

@Composable
internal fun ManualActivityEntryScreen(
    state: ManualActivityEntryState,
    onAction: (ManualActivityEntryAction) -> Unit,
    onBack: () -> Unit,
    isRouteVisible: Boolean = true,
    readCurrent: (() -> ManualActivityEntryState)? = null,
    ownsRoute: () -> Boolean = { isRouteVisible },
) {
    val current by rememberUpdatedState(state)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val interactive = isRouteVisible && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val admission =
        rememberManualEntryAdmission(state, readCurrent ?: { current }, {
            ownsRoute() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }, interactive, onAction)
    val focus =
        rememberHistoryFocus(
            interactive,
            state.catalog is ManualEntryLoad.Loading || state.selected is ManualEntryLoad.Loading,
            state.catalog is ManualEntryLoad.Failure,
            ownsRoute,
        )
    LifeTracingAccessibleTargets {
        CompositionLocalProvider(LocalManualEntryAdmission provides admission, LocalHistoryFocus provides focus) {
            EntrySurface(state, admission.back(onBack))
        }
    }
}

@Composable
private fun EntrySurface(
    state: ManualActivityEntryState,
    onBack: () -> Unit,
) {
    val enabled = !state.blocksExit && LocalManualEntryAdmission.current.canFocus()
    val recovery = remember { FocusRequester() }
    val sourceRecovery = remember { FocusRequester() }
    EntryRecoveryFocus(state, recovery, sourceRecovery)
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                Text(
                    stringResource(R.string.manual_history_title),
                    Modifier.semantics {
                        heading()
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                val label = stringResource(R.string.history_back)
                LifeTracingSecondaryButton(
                    onClick = onBack,
                    enabled = enabled,
                    modifier =
                        historyFocusTarget(LocalHistoryFocus.current.back, enabled)
                            .testTag("manual-history-back")
                            .historyActionSemantics(onBack, label),
                ) { Text(label) }
            }
            Catalog(state, enabled)
            EntrySelection(state, enabled, recovery, sourceRecovery)
            Command(state.command, state.startedIssue, state.completedIssue, recovery)
        }
    }
}

@Composable
private fun EntrySelection(
    state: ManualActivityEntryState,
    enabled: Boolean,
    recovery: FocusRequester,
    sourceRecovery: FocusRequester,
) {
    if (state.selected !is ManualEntryLoad.Content) {
        state.selectedTemplateId?.let {
            Text(stringResource(R.string.manual_history_selected_source, it.value), Modifier.semantics { heading() })
        }
    }
    when (val source = state.selected) {
        ManualEntryLoad.Idle -> Status(R.string.manual_history_no_selection)
        ManualEntryLoad.Loading -> Status(R.string.manual_history_template_loading)
        is ManualEntryLoad.Failure -> {
            if ((state.command as? ManualEntryCommand.Failure)?.issue != source.issue) {
                IssueText(source.issue)
            }
            if (source.issue == ManualEntryIssue.TEMPLATE_READ_FAILURE) {
                state.selectedTemplateId?.let {
                    EntryButton(
                        ManualActivityEntryAction.Select(it),
                        stringResource(R.string.history_retry),
                        enabled,
                        tag = "manual-history-source-retry",
                        description = stringResource(R.string.manual_history_retry_source, it.value),
                        requester = sourceRecovery,
                    )
                }
            }
        }
        is ManualEntryLoad.Content -> key(source.value.id) { EntryForm(source.value, state, enabled, recovery) }
    }
}

@Composable
private fun EntryRecoveryFocus(
    state: ManualActivityEntryState,
    recovery: FocusRequester,
    sourceRecovery: FocusRequester,
) {
    val catalogRecovery = LocalHistoryFocus.current.retry
    val window = LocalWindowInfo.current
    val admission by rememberUpdatedState(LocalManualEntryAdmission.current)
    LaunchedEffect(state.command) {
        if (state.command is ManualEntryCommand.Overlap ||
            state.command is ManualEntryCommand.Failure ||
            state.command is ManualEntryCommand.Invalid
        ) {
            withFrameNanos { }
            if (admission.canFocus() && window.isWindowFocused) recovery.requestFocus()
        }
    }
    LaunchedEffect(state.selected, state.catalog) {
        val target =
            when {
                (state.selected as? ManualEntryLoad.Failure)?.issue == ManualEntryIssue.TEMPLATE_READ_FAILURE ->
                    sourceRecovery
                state.catalog is ManualEntryLoad.Failure -> catalogRecovery
                else -> null
            }
        if (target != null) {
            withFrameNanos { }
            if (admission.canFocus() && window.isWindowFocused) target.requestFocus()
        }
    }
}

@Composable
private fun Catalog(
    state: ManualActivityEntryState,
    enabled: Boolean,
) {
    Text(
        stringResource(R.string.manual_history_choose_activity),
        Modifier.semantics {
            heading()
        },
        style = MaterialTheme.typography.titleMedium,
    )
    when (val catalog = state.catalog) {
        ManualEntryLoad.Idle, ManualEntryLoad.Loading ->
            Status(
                if (state.catalogReplacing) {
                    R.string.manual_history_loading_replacement
                } else {
                    R.string.manual_history_loading
                },
            )
        is ManualEntryLoad.Failure -> {
            IssueText(catalog.issue)
            EntryButton(
                ManualActivityEntryAction.RetryCatalog,
                stringResource(R.string.history_retry),
                enabled,
                tag = "manual-history-catalog-retry",
                description = stringResource(R.string.manual_history_retry_catalog),
                requester = LocalHistoryFocus.current.retry,
            )
        }
        is ManualEntryLoad.Content -> {
            Text(stringResource(R.string.manual_history_page, state.catalogPageNumber))
            if (catalog.value.isEmpty()) Status(R.string.manual_history_catalog_empty)
            val selectedId = (state.selected as? ManualEntryLoad.Content)?.value?.id
            catalog.value.forEach { item ->
                key(item.id) {
                    EntryButton(
                        ManualActivityEntryAction.Select(item.id),
                        item.name,
                        enabled && state.selected !is ManualEntryLoad.Loading,
                        tag = "manual-history-activity-${item.id.value}",
                        description = stringResource(R.string.manual_history_catalog_item, item.name, item.id.value),
                        selected = selectedId == item.id,
                    )
                }
            }
            if (state.canLoadMore) {
                Status(R.string.manual_history_more_pages)
                EntryButton(
                    ManualActivityEntryAction.LoadMore,
                    stringResource(R.string.manual_history_load_more),
                    enabled,
                    tag = "manual-history-load-more",
                )
            } else if (catalog.value.isNotEmpty()) {
                Status(R.string.manual_history_catalog_end)
            }
        }
    }
}

@Composable
private fun EntryForm(
    template: ActivityTemplate,
    state: ManualActivityEntryState,
    enabled: Boolean,
    recovery: FocusRequester,
) {
    Text(
        stringResource(R.string.manual_history_selected, template.name, template.id.value),
        Modifier.semantics {
            heading()
        },
        style = MaterialTheme.typography.titleLarge,
    )
    Text(stringResource(R.string.manual_history_zone, state.entryZoneId.id), Modifier.testTag("manual-history-zone"))
    if (template.timeTrackingMode == TimeTrackingMode.TIMER) {
        Text(stringResource(R.string.manual_history_timer_target, requireNotNull(template.timerTarget).durationText()))
    }
    if (template.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
        TimeInput(R.string.manual_history_started, state.startedText, state.startedIssue, enabled, "started") {
            ManualActivityEntryAction.EditStarted(it)
        }
        state.startedAmbiguity?.let { AmbiguityChoices(it, true, enabled) }
    }
    TimeInput(R.string.manual_history_completed, state.completedText, state.completedIssue, enabled, "completed") {
        ManualActivityEntryAction.EditCompleted(it)
    }
    state.completedAmbiguity?.let { AmbiguityChoices(it, false, enabled) }
    val invalid = state.command as? ManualEntryCommand.Invalid
    template.fields.filter { it.deletedAt == null }.forEachIndexed { index, field ->
        key(field.id) {
            Field(
                field,
                index,
                state.values.getValue(field.id),
                invalid?.takeIf { it.fieldId == field.id }?.issue,
                enabled,
            )
        }
    }
    EntryButton(
        ManualActivityEntryAction.Save,
        stringResource(R.string.manual_history_save),
        enabled,
        primary = true,
        tag = "manual-history-save",
        requester = recovery.takeIf { invalid != null },
    )
}

@Composable
private fun TimeInput(
    label: Int,
    value: String,
    issue: ManualEntryIssue?,
    enabled: Boolean,
    endpoint: String,
    action: (String) -> ManualActivityEntryAction,
) {
    val input = LocalManualEntryAdmission.current.input(action)
    LifeTracingOutlinedTextField(
        value,
        input,
        enabled = enabled,
        isError = issue != null,
        modifier =
            manualEntryInputTarget(enabled)
                .fillMaxWidth()
                .testTag("manual-history-$endpoint")
                .semantics {
                    setText {
                        input(it.text)
                        true
                    }
                },
        label = { Text(stringResource(label)) },
        supportingText = {
            if (issue !=
                null
            ) {
                IssueText(issue)
            } else {
                Text(stringResource(R.string.manual_history_datetime_hint))
            }
        },
    )
}

@Composable
private fun AmbiguityChoices(
    ambiguity: ManualTimeAmbiguity,
    started: Boolean,
    enabled: Boolean,
) {
    Status(R.string.manual_history_ambiguous_choose)
    val endpoint = stringResource(if (started) R.string.manual_history_started else R.string.manual_history_completed)
    ambiguity.offsets.forEachIndexed { index, offset ->
        key(offset) {
            val label =
                stringResource(
                    if (index ==
                        0
                    ) {
                        R.string.manual_history_first_occurrence
                    } else {
                        R.string.manual_history_second_occurrence
                    },
                    offset.utcLabel(),
                )
            EntryButton(
                if (started) {
                    ManualActivityEntryAction.SelectStartedOffset(
                        offset,
                    )
                } else {
                    ManualActivityEntryAction.SelectCompletedOffset(offset)
                },
                label,
                enabled,
                tag = "manual-history-${if (started) "started" else "completed"}-offset-$index",
                description = stringResource(R.string.manual_history_choice_context, endpoint, label),
                selected = ambiguity.selectedOffset == offset,
            )
        }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod") // Declarative presentation of the three canonical Field types.
private fun Field(
    field: ActivityTemplateField,
    index: Int,
    draft: ManualEntryFieldDraft,
    issue: ManualEntryIssue?,
    enabled: Boolean,
) {
    val context = stringResource(R.string.manual_history_field_context, index + 1, field.name)
    val type =
        stringResource(
            when (field.type) {
                CustomFieldType.NUMBER -> R.string.activity_editor_number
                CustomFieldType.CATEGORY -> R.string.activity_editor_category
                CustomFieldType.TEXT -> R.string.activity_editor_text
            },
        )
    val presence =
        stringResource(
            when {
                draft.missing -> R.string.manual_history_missing_value
                field.type == CustomFieldType.TEXT && draft.text.isEmpty() -> R.string.manual_history_present_empty
                else -> R.string.manual_history_present_value
            },
        )
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        Text(context, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        Text(type)
        if (field.type == CustomFieldType.NUMBER) {
            Text(
                stringResource(
                    R.string.manual_history_number_format,
                    field.unit ?: stringResource(R.string.manual_history_no_unit),
                    field.displayPrecision ?: DEFAULT_NUMBER_PRECISION,
                ),
            )
        }
        Text(
            stringResource(R.string.manual_history_configured, configured(field)),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(presence, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        when (field.type) {
            CustomFieldType.NUMBER, CustomFieldType.TEXT -> {
                val input =
                    LocalManualEntryAdmission.current.input {
                        if (field.type == CustomFieldType.NUMBER) {
                            ManualActivityEntryAction.EditNumber(field.id, it)
                        } else {
                            ManualActivityEntryAction.EditText(field.id, it)
                        }
                    }
                LifeTracingOutlinedTextField(
                    if (field.type == CustomFieldType.NUMBER) draft.numberText else draft.text,
                    input,
                    enabled = enabled,
                    isError = issue != null,
                    modifier =
                        manualEntryInputTarget(
                            enabled,
                        ).fillMaxWidth()
                            .testTag("manual-history-field-${field.id.value}-actual")
                            .semantics {
                                stateDescription = presence
                                setText {
                                    input(it.text)
                                    true
                                }
                            },
                    label = { Text(stringResource(R.string.manual_history_actual_context, context)) },
                    supportingText = issue?.let { { IssueText(it) } },
                )
            }
            CustomFieldType.CATEGORY -> {
                field.categoryOptions.filterNot { it.isArchived }.forEachIndexed { optionIndex, option ->
                    key(option.id) {
                        EntryButton(
                            ManualActivityEntryAction.SelectCategory(field.id, option.id),
                            option.label,
                            enabled,
                            tag = "manual-history-option-${option.id.value}",
                            selected =
                                !draft.missing && draft.selectedOptionId == option.id,
                            description =
                                stringResource(
                                    R.string.manual_history_option_context,
                                    context,
                                    optionIndex + 1,
                                    option.label,
                                ),
                        )
                    }
                }
                issue?.let { IssueText(it) }
            }
        }
        EntryButton(
            if (draft.missing) {
                ManualActivityEntryAction.SetPresent(
                    field.id,
                )
            } else {
                ManualActivityEntryAction.SetMissing(field.id)
            },
            stringResource(
                if (draft.missing) R.string.manual_history_restore_value else R.string.manual_history_set_missing,
            ),
            enabled,
            tag = "manual-history-field-${field.id.value}-missing",
            description =
                stringResource(
                    R.string.manual_history_choice_context,
                    context,
                    stringResource(
                        if (draft.missing) {
                            R.string.manual_history_restore_value
                        } else {
                            R.string.manual_history_set_missing
                        },
                    ),
                ),
            valueState = presence,
        )
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
        CustomFieldType.TEXT ->
            field.defaultText?.ifEmpty { stringResource(R.string.manual_history_present_empty) }
                ?: stringResource(R.string.manual_history_missing_value)
    }

@Composable
private fun Command(
    command: ManualEntryCommand,
    startedIssue: ManualEntryIssue?,
    completedIssue: ManualEntryIssue?,
    recovery: FocusRequester,
) {
    when (command) {
        ManualEntryCommand.Idle -> Unit
        is ManualEntryCommand.Invalid ->
            if (command.issue != startedIssue &&
                command.issue != completedIssue &&
                command.fieldId == null
            ) {
                IssueText(command.issue)
            }
        ManualEntryCommand.Committing -> Status(R.string.manual_history_saving)
        is ManualEntryCommand.Failure -> {
            IssueText(command.issue)
            val stale = command.issue == ManualEntryIssue.TEMPLATE_STALE
            EntryButton(
                if (stale) ManualActivityEntryAction.ReviewStaleTemplate else ManualActivityEntryAction.Save,
                stringResource(if (stale) R.string.manual_history_review_current else R.string.history_retry),
                true,
                tag = "manual-history-command-retry",
                description =
                    stringResource(
                        if (stale) R.string.manual_history_review_current else R.string.manual_history_retry_save,
                    ),
                requester = recovery,
            )
        }
        is ManualEntryCommand.Overlap -> {
            Status(R.string.manual_history_overlap)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                EntryButton(
                    ManualActivityEntryAction.CancelOverlap,
                    stringResource(R.string.manual_history_cancel),
                    true,
                    tag = "manual-history-overlap-cancel",
                    requester = recovery,
                )
                EntryButton(
                    ManualActivityEntryAction.ProceedOverlap,
                    stringResource(R.string.manual_history_proceed),
                    true,
                    primary = true,
                    tag = "manual-history-overlap-proceed",
                )
            }
        }
        is ManualEntryCommand.Committed -> Status(R.string.manual_history_committed)
    }
}

@Composable
private fun EntryButton(
    action: ManualActivityEntryAction,
    label: String,
    enabled: Boolean,
    primary: Boolean = false,
    tag: String = "",
    description: String? = null,
    selected: Boolean? = null,
    valueState: String? = null,
    requester: FocusRequester? = null,
) {
    val owner = LocalManualEntryAdmission.current
    val actionable = enabled && owner.canFocus()
    val click = owner.action(action)
    val modifier =
        historyFocusTarget(requester, actionable)
            .fillMaxWidth()
            .testTag(tag)
            .historyActionSemantics(click, description ?: label)
            .semantics {
                if (description != null) contentDescription = description
                if (selected != null) {
                    this.selected = selected
                    role = Role.RadioButton
                }
                if (valueState != null) stateDescription = valueState
            }
    if (selected != null) {
        LifeTracingChoiceButton(selected, click, modifier, actionable) { Text(label) }
    } else if (primary) {
        LifeTracingPrimaryButton(click, modifier, actionable) { Text(label) }
    } else {
        LifeTracingSecondaryButton(click, modifier, actionable) { Text(label) }
    }
}

@Composable
private fun Status(resource: Int) {
    Text(stringResource(resource), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
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
    Text(
        stringResource(resource),
        Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
        },
        color = MaterialTheme.colorScheme.error,
    )
}

private const val DEFAULT_NUMBER_PRECISION = 3

private fun Duration.durationText(): String = DateUtils.formatElapsedTime(seconds.coerceAtLeast(0))

private fun ZoneOffset.utcLabel(): String = "UTC${if (this == ZoneOffset.UTC) "+00:00" else id}"
