@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions")

package com.alexandr5476.lifetracing.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.stateDescription
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.time.ZoneOffset

@Composable
internal fun ActivityCorrectionEditor(
    detail: ActivityHistoryDetail,
    state: ActivityHistoryMutationState,
) {
    val draft = requireNotNull(state.draft)
    val enabled = !state.isMutating && LocalActivityCorrectionAdmission.current.canFocus()
    val initial = remember { FocusRequester() }
    val recovery = remember { FocusRequester() }
    CorrectionRecoveryTarget(initial)
    CorrectionEditorFocus(state, initial, recovery)
    Text(detail.root.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.history_activity_record, detail.root.executionId.value))
    Text(stringResource(R.string.history_event_zone, draft.eventZoneId.id))
    draft.startedText?.let { started ->
        CorrectionInput(
            started,
            ActivityHistoryMutationAction::EditStarted,
            stringResource(R.string.history_correction_started),
            "history-correction-started",
            enabled,
            state.issue.takeIf { state.issueEndpoint == ActivityCorrectionEndpoint.STARTED },
            initial,
        )
        CorrectionOffsets(draft.startedOffsets, draft.startedOffset, true, enabled)
    }
    CorrectionInput(
        draft.completedText,
        ActivityHistoryMutationAction::EditCompleted,
        stringResource(R.string.history_correction_completed),
        "history-correction-completed",
        enabled,
        state.issue.takeIf { state.issueEndpoint == ActivityCorrectionEndpoint.COMPLETED },
        initial.takeIf { draft.startedText == null },
    )
    CorrectionOffsets(draft.completedOffsets, draft.completedOffset, false, enabled)
    CorrectionInput(
        draft.shortComment,
        ActivityHistoryMutationAction::EditComment,
        stringResource(R.string.history_short_comment),
        "history-correction-comment",
        enabled,
    )
    detail.fields.forEachIndexed { index, field ->
        key(field.id) {
            CorrectionField(field, index, draft.values.getValue(field.id), state, enabled)
        }
    }
    state.issue?.let { MutationIssue(it) }
    if (state.isMutating) CorrectionStatus(R.string.history_saving)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        CorrectionButton(
            ActivityHistoryMutationAction.Cancel,
            stringResource(R.string.manual_history_cancel),
            "history-correction-cancel",
            enabled,
        )
        CorrectionButton(
            ActivityHistoryMutationAction.Save,
            stringResource(
                if (state.isMutating) {
                    R.string.history_saving
                } else if (state.issue ==
                    ActivityHistoryMutationIssue.SAVE_FAILURE
                ) {
                    R.string.history_correction_retry_save
                } else {
                    R.string.history_save_correction
                },
            ),
            "history-correction-save",
            enabled,
            primary = true,
            requester = recovery,
        )
    }
}

@Composable
private fun CorrectionEditorFocus(
    state: ActivityHistoryMutationState,
    initial: FocusRequester,
    recovery: FocusRequester,
) {
    val admission by rememberUpdatedState(LocalActivityCorrectionAdmission.current)
    val window = LocalWindowInfo.current
    LaunchedEffect(state.draftGeneration) {
        withFrameNanos { }
        if (admission.canFocus() && window.isWindowFocused && !state.isMutating) initial.requestFocus()
    }
    LaunchedEffect(state.issue) {
        if (state.issue != null) {
            withFrameNanos { }
            if (admission.canFocus() && window.isWindowFocused) recovery.requestFocus()
        }
    }
}

@Composable
private fun CorrectionInput(
    value: String,
    action: (String) -> ActivityHistoryMutationAction,
    label: String,
    tag: String,
    enabled: Boolean,
    issue: ActivityHistoryMutationIssue? = null,
    requester: FocusRequester? = null,
    presence: String? = null,
) {
    val input = LocalActivityCorrectionAdmission.current.input(action)
    val target = requester?.let { Modifier.focusRequester(it) } ?: Modifier
    LifeTracingOutlinedTextField(
        value,
        { input(it) },
        modifier =
            target.then(manualEntryInputTarget(enabled)).fillMaxWidth().testTag(tag).semantics {
                if (presence != null) stateDescription = presence
                setText { enabled && input(it.text) }
            },
        label = { Text(label) },
        enabled = enabled,
        isError = issue != null,
        supportingText = issue?.let { { MutationIssue(it) } },
    )
}

@Composable
private fun CorrectionField(
    field: ActivityHistoryField,
    index: Int,
    draft: ActivityHistoryFieldDraft,
    state: ActivityHistoryMutationState,
    enabled: Boolean,
) {
    val context = stringResource(R.string.manual_history_field_context, index + 1, field.name)
    val presence =
        stringResource(
            when {
                draft.missing -> R.string.manual_history_missing_value
                field.type == CustomFieldType.TEXT && draft.text.isEmpty() -> R.string.manual_history_present_empty
                else -> R.string.manual_history_present_value
            },
        )
    val issue = state.issue.takeIf { state.issueFieldId == field.id }
    val categoryError =
        if (issue ==
            ActivityHistoryMutationIssue.INVALID_CATEGORY
        ) {
            stringResource(R.string.manual_history_invalid_category)
        } else {
            null
        }
    Column(
        modifier =
            Modifier.testTag("history-correction-field-${field.id.value}-group").semantics {
                if (categoryError !=
                    null
                ) {
                    error(categoryError)
                }
            },
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Text(context, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(
                when (field.type) {
                    CustomFieldType.NUMBER -> R.string.activity_editor_number
                    CustomFieldType.CATEGORY -> R.string.activity_editor_category
                    CustomFieldType.TEXT -> R.string.activity_editor_text
                },
            ),
        )
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
            stringResource(
                R.string.history_configured_value,
                historyConfigured(
                    field.configuredValue,
                    field.categoryOptions.associate { it.id to it.label },
                    field.displayPrecision,
                    field.unit,
                ),
            ),
        )
        Text(presence, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        when (field.type) {
            CustomFieldType.NUMBER, CustomFieldType.TEXT ->
                CorrectionInput(
                    if (field.type == CustomFieldType.NUMBER) draft.numberText else draft.text,
                    {
                        if (field.type ==
                            CustomFieldType.NUMBER
                        ) {
                            ActivityHistoryMutationAction.EditNumber(field.id, it)
                        } else {
                            ActivityHistoryMutationAction.EditText(field.id, it)
                        }
                    },
                    stringResource(R.string.manual_history_actual_context, context),
                    "history-correction-field-${field.id.value}",
                    enabled,
                    issue,
                    presence = presence,
                )
            CustomFieldType.CATEGORY -> {
                Text(stringResource(R.string.manual_history_actual_context, context))
                field.categoryOptions.forEachIndexed { optionIndex, option ->
                    key(option.id) {
                        CorrectionButton(
                            ActivityHistoryMutationAction.SelectCategory(field.id, option.id),
                            option.label,
                            "history-correction-option-${option.id.value}",
                            enabled,
                            selected = !draft.missing && draft.selectedOptionId == option.id,
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
                issue?.let { MutationIssue(it) }
            }
        }
        val label =
            stringResource(
                if (draft.missing) R.string.manual_history_restore_value else R.string.manual_history_set_missing,
            )
        CorrectionButton(
            ActivityHistoryMutationAction.SetMissing(field.id, !draft.missing),
            label,
            "history-correction-field-${field.id.value}-missing",
            enabled,
            description = stringResource(R.string.manual_history_choice_context, context, label),
            presence = presence,
        )
    }
}

@Composable
private fun CorrectionOffsets(
    offsets: List<ZoneOffset>,
    selected: ZoneOffset?,
    started: Boolean,
    enabled: Boolean,
) {
    if (offsets.size != 2) return
    val endpoint =
        stringResource(if (started) R.string.history_correction_started else R.string.history_correction_completed)
    offsets.forEachIndexed { index, offset ->
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
                    "UTC${if (offset == ZoneOffset.UTC) "+00:00" else offset.id}",
                )
            CorrectionButton(
                if (started) {
                    ActivityHistoryMutationAction.SelectStartedOffset(
                        offset,
                    )
                } else {
                    ActivityHistoryMutationAction.SelectCompletedOffset(offset)
                },
                label,
                "history-correction-${if (started) "started" else "completed"}-offset-$index",
                enabled,
                selected = offset == selected,
                description = stringResource(R.string.manual_history_choice_context, endpoint, label),
            )
        }
    }
}

@Composable
internal fun CorrectionOverlapWarning(state: ActivityHistoryMutationState) {
    val cancel = remember { FocusRequester() }
    CorrectionRecoveryTarget(cancel)
    val admission by rememberUpdatedState(LocalActivityCorrectionAdmission.current)
    val window = LocalWindowInfo.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (admission.canFocus() && window.isWindowFocused) cancel.requestFocus()
    }
    (state.load as? HistoryDetailLoad.Content)?.value?.let { detail ->
        Text(detail.root.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.history_activity_record, detail.root.executionId.value))
    }
    state.draft?.let { draft ->
        Text(stringResource(R.string.history_event_zone, draft.eventZoneId.id))
        draft.startedText?.let {
            Text(
                stringResource(
                    R.string.manual_history_choice_context,
                    stringResource(R.string.history_correction_started),
                    it,
                ),
            )
        }
        Text(
            stringResource(
                R.string.manual_history_choice_context,
                stringResource(R.string.history_correction_completed),
                draft.completedText,
            ),
        )
    }
    CorrectionStatus(R.string.history_overlap_warning)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        CorrectionButton(
            ActivityHistoryMutationAction.CancelOverlap,
            stringResource(R.string.manual_history_cancel),
            "history-overlap-cancel",
            !state.isMutating,
            requester = cancel,
        )
        CorrectionButton(
            ActivityHistoryMutationAction.ProceedOverlap,
            stringResource(R.string.history_overlap_proceed),
            "history-overlap-proceed",
            !state.isMutating,
            primary = true,
        )
    }
}

@Composable
internal fun CorrectionButton(
    action: ActivityHistoryMutationAction,
    label: String,
    tag: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    selected: Boolean? = null,
    description: String? = null,
    presence: String? = null,
    requester: FocusRequester? = null,
) {
    val owner = LocalActivityCorrectionAdmission.current
    val actionable = enabled && owner.canFocus()
    val click = owner.action(action)
    val modifier =
        historyFocusTarget(requester, actionable)
            .testTag(tag)
            .historyActionSemantics(
                click,
                description ?: label,
            ).semantics {
                if (description != null) contentDescription = description
                if (selected != null) {
                    this.selected = selected
                    role = Role.RadioButton
                }
                if (presence != null) stateDescription = presence
            }
    if (selected != null) {
        LifeTracingChoiceButton(selected, click, modifier.fillMaxWidth(), actionable) { Text(label) }
    } else if (primary) {
        LifeTracingPrimaryButton(click, modifier, actionable) { Text(label) }
    } else {
        LifeTracingSecondaryButton(click, modifier, actionable) { Text(label) }
    }
}

@Composable
private fun CorrectionStatus(resource: Int) {
    Text(stringResource(resource), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private const val DEFAULT_NUMBER_PRECISION = 3

@Composable
private fun CorrectionRecoveryTarget(requester: FocusRequester) {
    val focus = LocalHistoryFocus.current
    DisposableEffect(focus, requester) {
        focus.recoveryTarget = requester
        onDispose { if (focus.recoveryTarget === requester) focus.recoveryTarget = null }
    }
}
