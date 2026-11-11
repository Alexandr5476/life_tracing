@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod") // Bounded native form presentation.

package com.alexandr5476.lifetracing.plan

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
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
import androidx.compose.ui.semantics.testTag
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged
import com.alexandr5476.lifetracing.ui.theme.spacing

private class ScheduleFocus {
    val date = FocusRequester()
    val close = FocusRequester()
    var owner: Any? = null
    var removed by mutableStateOf(false)
}

@Composable
private fun scheduleFocusTarget(focus: ScheduleFocus): Modifier {
    val token = remember { Any() }
    return Modifier
        .onFocusTargetAttachmentChanged { attached ->
            if (!attached && focus.owner === token) focus.removed = true
        }.onFocusChanged {
            if (it.isFocused && !focus.removed) focus.owner = token
        }
}

/** Native fields retain literal input; Tab never becomes a character in that input. */
@Composable
private fun Modifier.scheduleTabTraversal(): Modifier {
    val manager = LocalFocusManager.current
    return onPreviewKeyEvent {
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

internal fun Modifier.planFeedback(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** The supplied form is a presentation of controller authority, including callback ownership. */
@Composable
internal fun PlanScheduleDialog(
    form: PlanScheduleForm,
    isMutating: Boolean,
    onAction: (PlanAction) -> Unit,
) {
    val focus = remember { ScheduleFocus() }
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val present: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides density,
        ) { LifeTracingAccessibleTargets(content) }
    }
    val dismiss = { onAction(PlanAction.DismissForm(form.formId)) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            present {
                Text(
                    stringResource(if (form.identity == null) R.string.plan_schedule else R.string.plan_reschedule),
                    Modifier.semantics { heading() },
                )
            }
        },
        text = {
            present {
                val window = LocalWindowInfo.current
                val mode = LocalInputModeManager.current.inputMode
                var landing by remember { mutableStateOf(true) }
                LaunchedEffect(window.isWindowFocused, mode, landing, focus.removed, isMutating) {
                    val needsFocus = landing || focus.removed || isMutating
                    if (window.isWindowFocused && needsFocus) {
                        if (!isMutating) scroll.scrollTo(0)
                        withFrameNanos { }
                        focus.removed = false
                        focus.owner = null
                        landing = !(if (isMutating) focus.close else focus.date).requestFocus()
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .focusGroup()
                        .semantics { testTag = "plan-schedule-form" },
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    ScheduleContext(form)
                    FlowRow(Modifier.selectableGroup()) {
                        PlanScheduleKind.entries.forEach { kind ->
                            LifeTracingChoiceButton(
                                selected = form.kind == kind,
                                enabled = !isMutating,
                                onClick = { onAction(PlanAction.ChangeScheduleKind(kind, form.formId)) },
                                modifier =
                                    scheduleFocusTarget(focus).semantics {
                                        role = Role.RadioButton
                                        selected = form.kind == kind
                                        testTag = "plan-schedule-kind-$kind"
                                    },
                            ) { Text(kind.label()) }
                        }
                    }
                    val dateError =
                        form.error
                            .takeIf {
                                it == PlanMessage.INVALID_DATE || it == PlanMessage.INVALID_SCHEDULE
                            }?.label()
                    LifeTracingOutlinedTextField(
                        form.date,
                        { onAction(PlanAction.ChangeScheduleDate(it, form.formId)) },
                        modifier =
                            scheduleFocusTarget(focus)
                                .focusRequester(focus.date)
                                .scheduleTabTraversal()
                                .semantics {
                                    testTag = "plan-schedule-date"
                                    dateError?.let { error(it) }
                                },
                        enabled = !isMutating,
                        isError = dateError != null,
                        label = { Text(stringResource(R.string.plan_date)) },
                    )
                    Text(stringResource(R.string.plan_scheduling_zone, form.schedulingZoneId.id))
                    if (form.kind == PlanScheduleKind.EXACT_DAY) {
                        val timeError =
                            form.error
                                .takeIf {
                                    it != PlanMessage.INVALID_DATE &&
                                        it != PlanMessage.INVALID_SCHEDULE &&
                                        it != PlanMessage.ZONE_CHANGED
                                }?.label()
                        LifeTracingOutlinedTextField(
                            form.time,
                            { onAction(PlanAction.ChangeScheduleTime(it, form.formId)) },
                            modifier =
                                scheduleFocusTarget(focus).scheduleTabTraversal().semantics {
                                    testTag = "plan-schedule-time"
                                    timeError?.let { error(it) }
                                },
                            enabled = !isMutating,
                            isError = timeError != null,
                            label = { Text(stringResource(R.string.plan_time)) },
                        )
                        Column(Modifier.selectableGroup()) {
                            form.offsetChoices.forEachIndexed { index, offset ->
                                val label =
                                    stringResource(
                                        if (index ==
                                            0
                                        ) {
                                            R.string.plan_first_occurrence
                                        } else {
                                            R.string.plan_second_occurrence
                                        },
                                        form.time,
                                        stringResource(
                                            R.string.plan_utc_offset,
                                            if (offset.totalSeconds ==
                                                0
                                            ) {
                                                "+00:00"
                                            } else {
                                                offset.id
                                            },
                                        ),
                                    )
                                LifeTracingChoiceButton(
                                    selected = form.selectedOffset == offset,
                                    enabled = !isMutating,
                                    onClick = {
                                        onAction(
                                            PlanAction.SelectScheduleOffset(
                                                offset,
                                                form.date,
                                                form.time,
                                                form.schedulingZoneId,
                                                form.inputRevision,
                                                form.formId,
                                            ),
                                        )
                                    },
                                    modifier =
                                        scheduleFocusTarget(focus).fillMaxWidth().semantics {
                                            selected = form.selectedOffset == offset
                                            role = Role.RadioButton
                                            contentDescription = label
                                            testTag = "plan-schedule-offset-$index"
                                        },
                                ) { Text(label) }
                            }
                        }
                        if (form.zoneReviewRequired) {
                            TextButton(
                                enabled = !isMutating,
                                modifier =
                                    scheduleFocusTarget(
                                        focus,
                                    ).semantics { testTag = "plan-schedule-zone-review" },
                                onClick = {
                                    onAction(PlanAction.RevalidateScheduleZone(form.formId, form.inputRevision))
                                },
                            ) { Text(stringResource(R.string.plan_review_current_zone)) }
                        }
                    }
                    form.error?.let {
                        Text(
                            it.label(),
                            Modifier.planFeedback().semantics { testTag = "plan-schedule-error" },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (isMutating) Text(stringResource(R.string.plan_execution_committing), Modifier.planFeedback())
                }
            }
        },
        confirmButton = {
            present {
                TextButton(
                    enabled = !isMutating && form.error == null && !form.zoneReviewRequired,
                    modifier = scheduleFocusTarget(focus).semantics { testTag = "plan-schedule-save" },
                    onClick = { onAction(PlanAction.SubmitForm(form.formId, form.inputRevision)) },
                ) { Text(stringResource(R.string.plan_save)) }
            }
        },
        dismissButton = {
            present {
                TextButton(
                    onClick = dismiss,
                    modifier =
                        scheduleFocusTarget(focus)
                            .focusRequester(focus.close)
                            .semantics { testTag = "plan-schedule-close" },
                ) { Text(stringResource(R.string.plan_close)) }
            }
        },
    )
}

@Composable
private fun ScheduleContext(form: PlanScheduleForm) {
    val kind =
        form.existingKind ?: if (form.source?.id is LibraryTemplateId.Sequence) {
            PlanTrackableKind.SEQUENCE
        } else {
            PlanTrackableKind.ACTIVITY
        }
    val title = form.source?.name ?: form.existingTitle.orEmpty()
    val kindLabel =
        stringResource(
            if (kind ==
                PlanTrackableKind.ACTIVITY
            ) {
                R.string.plan_execution_activity_kind
            } else {
                R.string.plan_execution_sequence
            },
        )
    Text(
        stringResource(
            if (form.identity ==
                null
            ) {
                R.string.plan_schedule_create_context
            } else {
                R.string.plan_schedule_reschedule_context
            },
            title,
            kindLabel,
        ),
        Modifier.semantics { testTag = "plan-schedule-context" },
    )
    form.identity?.let {
        val target = PlanExecutionActionTarget(title, kind, it.target, false, null, null, null)
        Text(stringResource(R.string.plan_schedule_original, target.scheduleDescription()))
    }
    Text(stringResource(R.string.plan_schedule_precision, form.kind.label()))
    Text(
        stringResource(
            when {
                form.preservesExistingSchedule -> R.string.plan_schedule_untouched
                form.scheduleEdited -> R.string.plan_schedule_edited
                else -> R.string.plan_schedule_new
            },
        ),
    )
    if (form.preservesExistingSchedule && form.kind == PlanScheduleKind.EXACT_DAY) {
        Text(stringResource(R.string.plan_preserved_schedule))
    }
}
