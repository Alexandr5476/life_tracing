@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package com.alexandr5476.lifetracing.plan

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOption
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.history.HistoricalLocalDateTimeResolution
import com.alexandr5476.lifetracing.history.resolveHistoricalLocalDateTime
import com.alexandr5476.lifetracing.launcher.formatLauncherCountdown
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingLinearProgressIndicator
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@Composable
internal fun PlanExecutionRoute(
    session: PlanExecutionRouteSession,
    onBack: () -> Unit,
    onReloadOrigin: () -> Unit,
    onCommitted: (PlanExecutionCommit) -> Unit,
) {
    val state by session.controller.state.collectAsState()
    val exit = {
        if (state.command == PlanExecutionCommandState.Stale) {
            onReloadOrigin()
        } else {
            session.exitPolicy.requestExit(session.controller, onBack, onCommitted)
        }
    }
    BackHandler(enabled = true, onBack = exit)
    LaunchedEffect(state.command) { session.exitPolicy.onCommand(state.command, onCommitted) }
    val prepared = (state.prepared as? PlanExecutionLoad.Content)?.value
    LaunchedEffect(prepared) {
        val activity = (prepared?.action?.snapshot as? FocusedPlanAction.Snapshot.Activity)?.value
        if (activity != null) session.prepareQuickDraft(activity)
    }
    PlanExecutionScreen(state, session, exit, onReloadOrigin)
}

@Composable
internal fun PlanExecutionScreen(
    state: PlanExecutionState,
    session: PlanExecutionRouteSession,
    onBack: () -> Unit,
    onReloadOrigin: () -> Unit,
) {
    val backFocus = remember(session) { FocusRequester() }
    val timeEntryOpen = session.timeDraft != null
    var hadTimeEntry by remember(session) { mutableStateOf(false) }
    LaunchedEffect(timeEntryOpen) {
        if (hadTimeEntry && !timeEntryOpen) backFocus.requestFocus()
        hadTimeEntry = timeEntryOpen
    }
    LifeTracingAccessibleTargets {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .semantics { isTraversalGroup = true }
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.spacing.xLarge),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
            ) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.plan_execution_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    TextButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus)) {
                        Text(stringResource(R.string.plan_back))
                    }
                }
                when (val prepared = state.prepared) {
                    PlanExecutionLoad.Loading ->
                        if (state.command !=
                            PlanExecutionCommandState.Stale
                        ) {
                            StatusText(stringResource(R.string.plan_execution_loading))
                        }
                    is PlanExecutionLoad.Failure ->
                        FailureCard(R.string.plan_execution_load_failed) {
                            session.controller.retryPreparation()
                        }
                    is PlanExecutionLoad.Content -> PreparedContent(prepared.value, state.command, session)
                }
                CommandContent(state.command, session.controller::retryPreparation, onBack, onReloadOrigin)
            }
        }
    }
}

@Composable
private fun PreparedContent(
    prepared: PreparedPlanExecution,
    command: PlanExecutionCommandState,
    session: PlanExecutionRouteSession,
) {
    val idle = command == PlanExecutionCommandState.Idle
    FrozenPlanContext(prepared.action)
    when (val snapshot = prepared.action.snapshot) {
        is FocusedPlanAction.Snapshot.Activity -> {
            snapshot.value.shortComment?.let { Text(it) }
            if (session.timeDraft != null) {
                TimeEntryEditor(snapshot.value, session, command)
            } else {
                if (!prepared.isLive) {
                    NoLiveEditor(snapshot.value, session, idle)
                } else {
                    LifeTracingPrimaryButton(
                        enabled = idle,
                        onClick = session.controller::launch,
                        modifier = Modifier.testTag("plan-execution-submit"),
                    ) {
                        Text(stringResource(R.string.plan_start_action))
                    }
                }
                LifeTracingSecondaryButton(
                    onClick = { session.openTimeEntry(snapshot.value) },
                    enabled = idle,
                    modifier = Modifier.testTag("plan-execution-time-entry"),
                ) { Text(stringResource(R.string.plan_time_entry_action)) }
            }
        }
        is FocusedPlanAction.Snapshot.Sequence -> {
            snapshot.value.shortComment?.let { Text(it) }
            LifeTracingPrimaryButton(
                enabled = idle,
                onClick = session.controller::launch,
                modifier = Modifier.testTag("plan-execution-submit"),
            ) {
                Text(stringResource(R.string.plan_start_action))
            }
        }
    }
}

@Composable
private fun FrozenPlanContext(action: FocusedPlanAction) {
    val title =
        when (val snapshot = action.snapshot) {
            is FocusedPlanAction.Snapshot.Activity -> snapshot.value.name
            is FocusedPlanAction.Snapshot.Sequence -> snapshot.value.name
        }
    Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    FrozenPlanStatus(action.identity.status)
    Text(
        stringResource(
            when (val snapshot = action.snapshot) {
                is FocusedPlanAction.Snapshot.Sequence -> R.string.plan_execution_sequence
                is FocusedPlanAction.Snapshot.Activity ->
                    if (snapshot.value.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                        R.string.plan_execution_no_live
                    } else {
                        R.string.plan_execution_timed
                    }
            },
        ),
    )
    FrozenTargetContext(action.identity.target)
    Text(
        stringResource(
            when (action.sourceState) {
                PlanSourceState.CURRENT -> R.string.plan_source_current
                PlanSourceState.CHANGED -> R.string.plan_source_changed
                PlanSourceState.ARCHIVED -> R.string.plan_source_archived
                PlanSourceState.UNAVAILABLE -> R.string.plan_source_unavailable
            },
        ),
    )
}

@Composable
private fun FrozenPlanStatus(status: PlanEntryStatus) {
    val label =
        stringResource(
            when (status) {
                PlanEntryStatus.PLANNED -> R.string.plan_planned
                PlanEntryStatus.FULFILLED -> R.string.plan_fulfilled
                PlanEntryStatus.CANCELLED -> R.string.daily_plan_cancelled
            },
        )
    Text(stringResource(R.string.plan_execution_status, label))
}

@Composable
private fun FrozenTargetContext(target: PlanTarget) {
    when (target) {
        is PlanTarget.FloatingDay -> Text(stringResource(R.string.plan_execution_day, target.date.toString()))
        is PlanTarget.Week -> Text(stringResource(R.string.plan_execution_week, target.weekStart.toString()))
        is PlanTarget.ExactDay -> {
            Text(stringResource(R.string.plan_execution_exact, target.scheduledAt.toString()))
            Text(
                target.creationZoneId?.let { zone ->
                    val local = target.scheduledAt.atZone(zone)
                    stringResource(
                        R.string.plan_execution_creation_zone,
                        zone.id,
                        local.toLocalDateTime().toString(),
                        if (local.offset.totalSeconds ==
                            0
                        ) {
                            "+00:00"
                        } else {
                            local.offset.id
                        },
                    )
                } ?: stringResource(R.string.plan_execution_legacy_zone),
            )
        }
        is PlanTarget.Month -> Unit
    }
}

@Composable
private fun NoLiveEditor(
    snapshot: ActivityConfigSnapshot,
    session: PlanExecutionRouteSession,
    enabled: Boolean,
) {
    val draft = session.quickDraft ?: return
    PlanValuesEditor(snapshot, draft, session, enabled)
    LifeTracingPrimaryButton(
        enabled = enabled && draft.invalid.isEmpty(),
        onClick = { session.controller.launch(draft.overrides(snapshot.fields)) },
        modifier = Modifier.testTag("plan-execution-submit"),
    ) { Text(stringResource(R.string.plan_complete_action)) }
}

@Composable
private fun PlanValuesEditor(
    snapshot: ActivityConfigSnapshot,
    draft: PlanQuickCompletionDraft,
    session: PlanExecutionRouteSession,
    enabled: Boolean,
) {
    snapshot.fields
        .sortedWith(compareByDescending<ActivitySnapshotField> { it.isMainValue }.thenBy { it.position })
        .forEach { field ->
            val fieldLabel = field.accessibleLabel()
            val valueState =
                stringResource(
                    when {
                        field.id in draft.invalid -> R.string.plan_time_entry_invalid_number
                        draft.values[field.id] == null -> R.string.plan_execution_missing
                        field.id !in draft.changed -> R.string.launcher_value_default
                        else -> R.string.launcher_value_edited
                    },
                )
            when (field.type) {
                CustomFieldType.NUMBER ->
                    LifeTracingOutlinedTextField(
                        value = draft.numberTexts[field.id].orEmpty(),
                        onValueChange = { session.editNumber(field, it) },
                        modifier =
                            Modifier.fillMaxWidth().semantics { stateDescription = valueState }.testTag(
                                "plan-value-${field.id.value}",
                            ),
                        label = { Text(fieldLabel) },
                        supportingText = {
                            if (field.id in draft.invalid) Text(stringResource(R.string.plan_time_entry_invalid_number))
                        },
                        isError = field.id in draft.invalid,
                        enabled = enabled,
                    )
                CustomFieldType.TEXT ->
                    LifeTracingOutlinedTextField(
                        value = (draft.values[field.id] as? TextExecutionValue)?.value.orEmpty(),
                        onValueChange = { session.editText(field, it) },
                        modifier =
                            Modifier.fillMaxWidth().semantics { stateDescription = valueState }.testTag(
                                "plan-value-${field.id.value}",
                            ),
                        label = { Text(fieldLabel) },
                        enabled = enabled,
                    )
                CustomFieldType.CATEGORY -> CategoryEditor(field, draft.values[field.id], valueState, enabled, session)
            }
            TextButton(
                onClick = { session.markMissing(field) },
                enabled = enabled,
                modifier = Modifier.testTag("plan-missing-${field.id.value}"),
            ) { Text(stringResource(R.string.plan_execution_field_missing, fieldLabel)) }
        }
}

@Composable
private fun TimeEntryEditor(
    snapshot: ActivityConfigSnapshot,
    session: PlanExecutionRouteSession,
    command: PlanExecutionCommandState,
) {
    val draft = session.timeDraft ?: return
    val editable =
        command is PlanExecutionCommandState.Idle ||
            command is PlanExecutionCommandState.Overlap ||
            command is PlanExecutionCommandState.Rejected ||
            command is PlanExecutionCommandState.Conflict ||
            command == PlanExecutionCommandState.ExpiredFinish ||
            command == PlanExecutionCommandState.ZoneChanged
    val firstFocus = remember(session) { FocusRequester() }
    val firstInView = remember(session) { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        firstInView.bringIntoView()
        firstFocus.requestFocus()
    }
    Text(
        stringResource(R.string.plan_time_entry_title),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
    Text(stringResource(R.string.plan_time_entry_zone, draft.zoneId.id))
    if (snapshot.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
        LifeTracingOutlinedTextField(
            value = draft.startedText,
            onValueChange = session::editStarted,
            enabled = editable,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(
                        firstFocus,
                    ).bringIntoViewRequester(firstInView)
                    .testTag("plan-time-start"),
            label = { Text(stringResource(R.string.plan_time_entry_start)) },
            supportingText = { Text(stringResource(R.string.manual_history_datetime_hint)) },
        )
        TimeOffsetChoices(draft.startedText, draft.zoneId, draft.startedOffset, editable, true, session)
    }
    LifeTracingOutlinedTextField(
        value = draft.completedText,
        onValueChange = session::editCompleted,
        enabled = editable,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (snapshot.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                        Modifier.focusRequester(firstFocus).bringIntoViewRequester(firstInView)
                    } else {
                        Modifier
                    },
                ).testTag("plan-time-end"),
        label = {
            Text(
                stringResource(
                    if (snapshot.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                        R.string.plan_time_entry_completion
                    } else {
                        R.string.plan_time_entry_optional_end
                    },
                ),
            )
        },
        supportingText = { Text(stringResource(R.string.manual_history_datetime_hint)) },
    )
    if (draft.completedText.isNotBlank()) {
        TimeOffsetChoices(draft.completedText, draft.zoneId, draft.completedOffset, editable, false, session)
    }
    TimeEntryIssueMessage(draft, command, session, editable)
    session.quickDraft?.let { PlanValuesEditor(snapshot, it, session, editable) }
    if (command is PlanExecutionCommandState.Overlap) {
        StatusText(
            stringResource(
                R.string.plan_execution_overlap_interval,
                requireNotNull(command.request.startedAt).atZone(command.request.zoneId).toString(),
                requireNotNull(command.request.completedAt).atZone(command.request.zoneId).toString(),
            ),
        )
        LifeTracingPrimaryButton(
            enabled = editable,
            onClick = { session.submitTimeEntry(snapshot, true) },
            modifier = Modifier.testTag("plan-time-confirm-overlap"),
        ) { Text(stringResource(R.string.plan_time_entry_confirm)) }
        LifeTracingSecondaryButton(onClick = session.controller::resetTimeEntryIssue, enabled = editable) {
            Text(stringResource(R.string.plan_execution_cancel_overlap))
        }
    } else {
        LifeTracingPrimaryButton(
            enabled = editable && session.quickDraft?.invalid?.isEmpty() == true,
            onClick = { session.submitTimeEntry(snapshot) },
            modifier = Modifier.testTag("plan-time-save"),
        ) { Text(stringResource(R.string.plan_time_entry_save)) }
    }
    LifeTracingSecondaryButton(
        enabled = editable,
        onClick = session::closeTimeEntry,
    ) { Text(stringResource(R.string.plan_time_entry_back)) }
}

@Composable
private fun TimeOffsetChoices(
    text: String,
    zone: java.time.ZoneId,
    selected: ZoneOffset?,
    enabled: Boolean,
    started: Boolean,
    session: PlanExecutionRouteSession,
) {
    val resolution = resolveHistoricalLocalDateTime(text, zone, null)
    val offsets = (resolution as? HistoricalLocalDateTimeResolution.Ambiguous)?.offsets ?: return
    val context = stringResource(if (started) R.string.plan_time_entry_start else R.string.plan_time_entry_completion)
    Text(stringResource(R.string.plan_execution_offset_prompt, context))
    offsets.forEachIndexed { index, offset ->
        LifeTracingChoiceButton(
            selected = selected == offset,
            enabled = enabled,
            onClick = {
                if (started) session.selectStartedOffset(offset) else session.selectCompletedOffset(offset)
            },
            modifier =
                Modifier
                    .semantics { this.selected = selected == offset }
                    .testTag("plan-time-" + (if (started) "start" else "end") + "-offset-" + index),
        ) {
            val label =
                if (index ==
                    0
                ) {
                    R.string.manual_history_first_occurrence
                } else {
                    R.string.manual_history_second_occurrence
                }
            val offsetLabel = stringResource(R.string.plan_execution_offset, context, stringResource(label, offset.id))
            Text(
                if (selected == offset) {
                    stringResource(R.string.plan_time_entry_selected_offset, offsetLabel)
                } else {
                    offsetLabel
                },
            )
        }
    }
}

@Composable
private fun TimeEntryIssueMessage(
    draft: PlanTimeEntryDraft,
    command: PlanExecutionCommandState,
    session: PlanExecutionRouteSession,
    editable: Boolean,
) {
    val issue =
        draft.issue ?: if (command == PlanExecutionCommandState.ZoneChanged) PlanTimeEntryIssue.ZONE_CHANGED else null
    issue?.let {
        StatusText(stringResource(it.label()))
        if (it == PlanTimeEntryIssue.ZONE_CHANGED) {
            LifeTracingSecondaryButton(
                onClick = session::reviewDeviceZone,
                enabled = editable,
            ) { Text(stringResource(R.string.plan_time_entry_review_zone)) }
        }
    }
}

private fun PlanTimeEntryIssue.label(): Int =
    when (this) {
        PlanTimeEntryIssue.INVALID_TIME -> R.string.plan_time_entry_invalid
        PlanTimeEntryIssue.NONEXISTENT_TIME -> R.string.plan_time_entry_nonexistent
        PlanTimeEntryIssue.AMBIGUOUS_START -> R.string.plan_execution_ambiguous_start
        PlanTimeEntryIssue.AMBIGUOUS_END -> R.string.plan_execution_ambiguous_end
        PlanTimeEntryIssue.FUTURE -> R.string.plan_time_entry_future
        PlanTimeEntryIssue.REVERSED -> R.string.plan_time_entry_reversed
        PlanTimeEntryIssue.EXPIRED_FINISH -> R.string.plan_time_entry_expired_finish
        PlanTimeEntryIssue.ZONE_CHANGED -> R.string.plan_time_entry_zone_changed
        PlanTimeEntryIssue.INVALID_NUMBER -> R.string.plan_time_entry_invalid_number
    }

@Composable
private fun CategoryEditor(
    field: ActivitySnapshotField,
    value: ActivityExecutionFieldValue?,
    valueState: String,
    enabled: Boolean,
    session: PlanExecutionRouteSession,
) {
    var open by remember(field.id) { mutableStateOf(false) }
    val selected = (value as? CategoryExecutionValue)?.optionId
    val label = field.accessibleLabel()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    Box {
        LifeTracingSecondaryButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.semantics { stateDescription = valueState }.testTag("plan-value-${field.id.value}"),
        ) {
            Text(
                stringResource(
                    R.string.plan_execution_category_value,
                    label,
                    field.categoryOptions.singleOrNull { it.id == selected }?.label()
                        ?: stringResource(R.string.plan_execution_missing),
                ),
            )
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            // Native windows install platform locals; retain this route's effective locale and scale.
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalDensity provides density,
            ) {
                LifeTracingAccessibleTargets {
                    DropdownMenuItem(
                        text = {
                            val missing = stringResource(R.string.plan_execution_field_missing, label)
                            Text(
                                if (selected == null) {
                                    stringResource(R.string.plan_execution_selected_category, missing)
                                } else {
                                    missing
                                },
                            )
                        },
                        modifier =
                            Modifier.minimumInteractiveComponentSize().semantics {
                                this.selected =
                                    selected == null
                            },
                        onClick = {
                            open = false
                            session.markMissing(field)
                        },
                    )
                    field.categoryOptions.sortedBy { it.position }.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                val optionLabel =
                                    stringResource(R.string.plan_execution_category_value, label, option.label())
                                Text(
                                    if (selected == option.id) {
                                        stringResource(R.string.plan_execution_selected_category, optionLabel)
                                    } else {
                                        optionLabel
                                    },
                                )
                            },
                            modifier =
                                Modifier.minimumInteractiveComponentSize().semantics {
                                    this.selected =
                                        selected == option.id
                                },
                            onClick = {
                                open = false
                                session.editCategory(field, option.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandContent(
    command: PlanExecutionCommandState,
    retry: () -> Unit,
    cancel: () -> Unit,
    reload: () -> Unit,
) {
    when (command) {
        PlanExecutionCommandState.Idle -> Unit
        is PlanExecutionCommandState.Checking -> MessageCard(R.string.plan_execution_checking)
        is PlanExecutionCommandState.Preflight -> PreflightCard(command, cancel)
        is PlanExecutionCommandState.Overlap -> Unit
        is PlanExecutionCommandState.Committing -> MessageCard(R.string.plan_execution_committing)
        PlanExecutionCommandState.Stale ->
            FailureCard(R.string.plan_execution_stale, R.string.plan_execution_reload, reload)
        is PlanExecutionCommandState.Conflict -> FailureCard(R.string.plan_execution_conflict, action = retry)
        is PlanExecutionCommandState.Rejected -> FailureCard(R.string.plan_execution_rejected, action = retry)
        PlanExecutionCommandState.ExpiredFinish -> MessageCard(R.string.plan_time_entry_expired_finish)
        PlanExecutionCommandState.ZoneChanged -> Unit
        is PlanExecutionCommandState.Committed -> MessageCard(R.string.plan_execution_committed)
        is PlanExecutionCommandState.CommittedCoordinationFailure ->
            MessageCard(R.string.plan_execution_committed_coordination)
    }
}

@Composable
private fun PreflightCard(
    state: PlanExecutionCommandState.Preflight,
    cancel: () -> Unit,
) {
    var elapsed by remember(state.attemptId) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val elapsedStart = remember(state.attemptId) { elapsed }
    val wallStart = remember(state.attemptId) { Instant.now() }
    LaunchedEffect(state.attemptId) {
        while (true) {
            delay(250)
            elapsed = SystemClock.elapsedRealtime()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        StatusText(stringResource(R.string.plan_execution_preflight))
        PlanPreflightCountdown(state, wallStart.plusMillis(elapsed - elapsedStart))
        LifeTracingSecondaryButton(onClick = cancel) { Text(stringResource(R.string.plan_execution_cancel)) }
    }
}

/** Passive clock output. Only the existing display loop supplies time; never a live region. */
@Composable
internal fun PlanPreflightCountdown(
    state: PlanExecutionCommandState.Preflight,
    now: Instant,
) {
    val remaining = Duration.between(now, state.endsAt).coerceAtLeast(Duration.ZERO)
    val formatted = formatLauncherCountdown(remaining)
    val description = stringResource(R.string.plan_execution_remaining, formatted)
    Text(
        stringResource(R.string.plan_execution_countdown, formatted),
        modifier = Modifier.semantics { contentDescription = description },
    )
    val progress = 1f - remaining.toMillis().toFloat() / state.duration.toMillis().coerceAtLeast(1L)
    LifeTracingLinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.clearAndSetSemantics { hideFromAccessibility() },
    )
}

@Composable
private fun StatusText(message: String) {
    Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun ActivitySnapshotField.accessibleLabel(): String =
    if (isMainValue) stringResource(R.string.plan_execution_main_value, label()) else label()

@Composable
private fun FailureCard(
    message: Int,
    actionLabel: Int = R.string.plan_execution_retry,
    action: () -> Unit,
) = MessageCard(message) {
    LifeTracingSecondaryButton(onClick = action) { Text(stringResource(actionLabel)) }
}

@Composable
private fun MessageCard(
    message: Int,
    vararg formatArgs: Any,
    content: @Composable () -> Unit = {},
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            StatusText(stringResource(message, *formatArgs))
            content()
        }
    }
}

private fun ActivitySnapshotField.label(): String =
    listOfNotNull(localNameOverride ?: nameAtCreation, unit).joinToString(" ")

private fun ActivitySnapshotCategoryOption.label(): String = localLabelOverride ?: labelAtCreation
