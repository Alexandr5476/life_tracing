@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.live

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActiveSequenceState
import com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequence
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequenceOccurrence
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline
import com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Duration

@Composable
internal fun ExpandedLiveSequenceRoute(
    controller: ExpandedLiveSequenceController,
    onBack: () -> Unit,
    onStale: () -> Unit,
) {
    val state by controller.state.collectAsState()
    LaunchedEffect(state.stale) {
        if (state.stale) onStale()
    }
    ExpandedLiveSequenceScreen(state, controller, onBack)
}

@Composable
internal fun ExpandedLiveSequenceScreen(
    state: ExpandedLiveSequenceState,
    controller: ExpandedLiveSequenceController,
    onBack: () -> Unit,
    displayElapsedRealtimeMs: Long? = null,
) {
    val sequence = state.sequence
    val elapsedNow = displayTick(state.displayBaseline, displayElapsedRealtimeMs)
    var addOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        if (sequence == null) {
            Column(
                Modifier.fillMaxSize().padding(MaterialTheme.spacing.xLarge),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.expanded_sequence_back)) }
                Text(
                    if (state.readFailure != null) {
                        stringResource(R.string.expanded_sequence_read_failure)
                    } else {
                        stringResource(R.string.expanded_sequence_loading)
                    },
                )
                if (state.readFailure != null) {
                    LifeTracingPrimaryButton(onClick = controller::refresh) {
                        Text(stringResource(R.string.expanded_sequence_retry))
                    }
                }
            }
            return@Surface
        }
        val actions = expandedActions(sequence.state)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item {
                SequenceHeader(sequence, state.displayBaseline, elapsedNow, onBack)
            }
            state.commandFailure?.let { failure ->
                item { CommandFailure(failure) }
            }
            item {
                PrimaryControls(sequence, state.commandInFlight, controller)
            }
            item {
                GlobalActions(
                    actions,
                    state.commandInFlight,
                    onRuntimeAdd = {
                        controller.loadRuntimeAddCatalog()
                        addOpen = true
                    },
                    onEndEarly = controller::requestEndEarly,
                )
            }
            items(
                sequence.occurrences,
                key = { it.occurrence.id.value },
                contentType = { it.occurrence.status },
            ) { row ->
                OccurrenceRow(
                    sequence,
                    row,
                    state.currentValueDraft,
                    state.displayBaseline,
                    elapsedNow,
                    state.commandInFlight,
                    controller,
                )
            }
        }
    }
    ConfirmationDialog(state.confirmation, controller)
    if (addOpen && sequence != null) {
        RuntimeAddDialog(
            state,
            validRuntimePlacements(sequence.state),
            controller,
            onDismiss = { addOpen = false },
        )
    }
}

@Composable
private fun GlobalActions(
    actions: ExpandedSequenceActions,
    inFlight: Boolean,
    onRuntimeAdd: () -> Unit,
    onEndEarly: () -> Unit,
) {
    var open by remember(actions) { mutableStateOf(false) }
    val description = stringResource(R.string.expanded_sequence_global_actions)
    Box(Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.xLarge)) {
        TextButton(
            enabled = !inFlight,
            onClick = { open = true },
            modifier =
                Modifier
                    .testTag("expanded-sequence-global-actions")
                    .semantics { contentDescription = description },
        ) { Text(stringResource(R.string.expanded_sequence_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (actions.runtimeAdd) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.expanded_sequence_runtime_add)) },
                    onClick = {
                        open = false
                        onRuntimeAdd()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.expanded_sequence_end_early)) },
                onClick = {
                    open = false
                    onEndEarly()
                },
            )
        }
    }
}

@Composable
private fun SequenceHeader(
    sequence: ExpandedLiveSequence,
    baseline: RuntimeDisplayBaseline?,
    elapsedNow: Long,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(MaterialTheme.spacing.xLarge),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.expanded_sequence_back)) }
        Text(sequence.runtime.snapshot.name, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large)) {
            Text(
                stringResource(
                    R.string.expanded_sequence_active_time,
                    baseline?.activeElapsed(elapsedNow)?.durationText()
                        ?: stringResource(R.string.expanded_sequence_timing_unavailable),
                ),
            )
            Text(
                stringResource(
                    R.string.expanded_sequence_pause_time,
                    baseline?.pauseElapsed(elapsedNow)?.durationText()
                        ?: stringResource(R.string.expanded_sequence_timing_unavailable),
                ),
            )
        }
        if (sequence.state == ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN ||
            sequence.state == ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
        ) {
            Text(
                stringResource(
                    R.string.expanded_sequence_transition_remaining,
                    baseline?.transitionCountdownRemaining(elapsedNow)?.durationText()
                        ?: stringResource(R.string.expanded_sequence_timing_unavailable),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun PrimaryControls(
    sequence: ExpandedLiveSequence,
    inFlight: Boolean,
    controller: ExpandedLiveSequenceController,
) {
    val actions = expandedActions(sequence.state)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.xLarge),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        when {
            actions.completeCurrent ->
                LifeTracingPrimaryButton(onClick = controller::completeCurrent, enabled = !inFlight) {
                    Text(stringResource(R.string.expanded_sequence_complete))
                }
            actions.startNext ->
                LifeTracingPrimaryButton(onClick = controller::startNext, enabled = !inFlight) {
                    Text(stringResource(R.string.expanded_sequence_start_next))
                }
            actions.resume ->
                LifeTracingPrimaryButton(onClick = controller::resume, enabled = !inFlight) {
                    Text(stringResource(R.string.expanded_sequence_resume))
                }
        }
        if (actions.pause) {
            LifeTracingSecondaryButton(onClick = controller::pause, enabled = !inFlight) {
                Text(stringResource(R.string.expanded_sequence_pause))
            }
        }
    }
}

@Composable
private fun OccurrenceRow(
    sequence: ExpandedLiveSequence,
    row: ExpandedLiveSequenceOccurrence,
    draft: CurrentValueDraft?,
    baseline: RuntimeDisplayBaseline?,
    elapsedNow: Long,
    inFlight: Boolean,
    controller: ExpandedLiveSequenceController,
) {
    val current = row.occurrence.status == RuntimeOccurrenceStatus.CURRENT
    val container =
        when (row.occurrence.status) {
            RuntimeOccurrenceStatus.CURRENT -> MaterialTheme.colorScheme.primaryContainer
            RuntimeOccurrenceStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceContainerLow
            RuntimeOccurrenceStatus.DELETED_EXECUTION -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainer
        }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.xLarge)
                .testTag("expanded-sequence-occurrence-${row.occurrence.id.value}"),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.activity.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    row.activity.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                OccurrenceMenu(sequence, row, inFlight, controller)
            }
            Text(occurrenceState(row.occurrence.status), style = MaterialTheme.typography.labelLarge)
            row.occurrence.repeatIteration?.let {
                Text(stringResource(R.string.expanded_sequence_repeat_iteration, it))
            }
            if (row.occurrence.isRuntimeAdded) Text(stringResource(R.string.expanded_sequence_runtime_added))
            Text(
                activityTimingText(row, baseline, elapsedNow),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.occurrence.status != RuntimeOccurrenceStatus.DELETED_EXECUTION) {
                ValueSummary(row, draft.takeIf { current })
            }
            if (current &&
                expandedActions(sequence.state).editCurrentValues &&
                draft?.occurrenceId == row.occurrence.id
            ) {
                CurrentValueEditor(row, draft, inFlight, controller)
            }
        }
    }
}

@Composable
private fun OccurrenceMenu(
    sequence: ExpandedLiveSequence,
    row: ExpandedLiveSequenceOccurrence,
    inFlight: Boolean,
    controller: ExpandedLiveSequenceController,
) {
    val actions = expandedActions(sequence.state)
    val go = actions.goNow && row.occurrence.status == RuntimeOccurrenceStatus.NOT_STARTED
    val next = actions.makeNext && row.occurrence.status == RuntimeOccurrenceStatus.NOT_STARTED
    val again = actions.doAgain && row.occurrence.status == RuntimeOccurrenceStatus.COMPLETED
    if (!go && !next && !again) return
    var open by remember(row.occurrence.id) { mutableStateOf(false) }
    val actionsDescription = stringResource(R.string.expanded_sequence_row_actions, row.activity.name)
    Box {
        TextButton(
            enabled = !inFlight,
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = actionsDescription },
        ) { Text(stringResource(R.string.expanded_sequence_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (go) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.expanded_sequence_go_now)) },
                    onClick = {
                        open = false
                        controller.requestGoNow(row.occurrence.id)
                    },
                )
            }
            if (next) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.expanded_sequence_make_next)) },
                    onClick = {
                        open = false
                        controller.makeNext(row.occurrence.id)
                    },
                )
            }
            if (again) {
                validRuntimePlacements(sequence.state).forEach { placement ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.expanded_sequence_do_again_placement,
                                    placementLabel(placement),
                                ),
                            )
                        },
                        onClick = {
                            open = false
                            controller.doAgain(row.occurrence.id, placement)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentValueEditor(
    row: ExpandedLiveSequenceOccurrence,
    draft: CurrentValueDraft,
    inFlight: Boolean,
    controller: ExpandedLiveSequenceController,
) {
    row.activity.fields.sortedBy(ActivitySnapshotField::position).forEach { field ->
        when (field.type) {
            CustomFieldType.NUMBER ->
                LifeTracingOutlinedTextField(
                    value = draft.numberTexts[field.id].orEmpty(),
                    onValueChange = { controller.editNumber(field.id, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(field.label()) },
                    isError = field.id in draft.invalidNumberFields,
                )
            CustomFieldType.TEXT ->
                LifeTracingOutlinedTextField(
                    value = (draft.values[field.id] as? TextExecutionValue)?.value.orEmpty(),
                    onValueChange = { controller.editText(field.id, it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(field.label()) },
                )
            CustomFieldType.CATEGORY -> CategoryEditor(field, draft.values[field.id], controller)
        }
        TextButton(onClick = { controller.markMissing(field.id) }) {
            Text(stringResource(R.string.expanded_sequence_set_missing))
        }
    }
    if (row.activity.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING && row.activity.fields.isNotEmpty()) {
        LifeTracingSecondaryButton(
            enabled = !inFlight && draft.invalidNumberFields.isEmpty(),
            onClick = controller::saveCurrentValues,
        ) { Text(stringResource(R.string.expanded_sequence_save_values)) }
    }
}

@Composable
private fun CategoryEditor(
    field: ActivitySnapshotField,
    value: ActivityExecutionFieldValue?,
    controller: ExpandedLiveSequenceController,
) {
    var open by remember(field.id) { mutableStateOf(false) }
    val selected = (value as? CategoryExecutionValue)?.optionId
    Box {
        LifeTracingSecondaryButton(onClick = { open = true }) {
            Text(
                field.categoryOptions.singleOrNull { it.id == selected }?.label()
                    ?: stringResource(R.string.expanded_sequence_missing),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            field.categoryOptions.sortedBy { it.position }.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        open = false
                        controller.editCategory(field.id, option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ValueSummary(
    row: ExpandedLiveSequenceOccurrence,
    draft: CurrentValueDraft?,
) {
    val values = draft?.values ?: row.displayValues()
    row.activity.fields.sortedBy(ActivitySnapshotField::position).forEach { field ->
        Text(
            stringResource(R.string.expanded_sequence_field_value, field.label(), field.valueText(values[field.id])),
            Modifier.testTag("expanded-sequence-value-${row.occurrence.id.value}-${field.id.value}"),
        )
    }
}

private fun ExpandedLiveSequenceOccurrence.displayValues(): Map<ActivitySnapshotFieldId, ActivityExecutionFieldValue?> {
    val actual = childExecution?.values?.associateBy(ActivityExecutionFieldValue::snapshotFieldId)
    if (occurrence.status == RuntimeOccurrenceStatus.COMPLETED ||
        occurrence.status == RuntimeOccurrenceStatus.CURRENT
    ) {
        return actual.orEmpty()
    }
    return activity.fields.associate { field ->
        field.id to
            when (field.type) {
                CustomFieldType.NUMBER -> field.defaultNumberScaled?.let { NumberExecutionValue(field.id, it) }
                CustomFieldType.CATEGORY -> field.defaultCategoryOptionId?.let { CategoryExecutionValue(field.id, it) }
                CustomFieldType.TEXT -> field.defaultText?.let { TextExecutionValue(field.id, it) }
            }
    }
}

@Composable
private fun ActivitySnapshotField.valueText(value: ActivityExecutionFieldValue?): String =
    when (value) {
        is NumberExecutionValue ->
            com.alexandr5476.lifetracing.launcher.formatLauncherNumber(
                value.scaledValue,
                displayPrecision,
            )
        is CategoryExecutionValue ->
            categoryOptions.singleOrNull { it.id == value.optionId }?.label()
                ?: stringResource(R.string.expanded_sequence_missing)
        is TextExecutionValue -> value.value
        null -> stringResource(R.string.expanded_sequence_missing)
    }

@Composable
private fun activityTimingText(
    row: ExpandedLiveSequenceOccurrence,
    baseline: RuntimeDisplayBaseline?,
    elapsedNow: Long,
): String {
    val completedDuration = row.childExecution?.activeDuration
    return when {
        row.occurrence.status == RuntimeOccurrenceStatus.CURRENT &&
            row.activity.timeTrackingMode == TimeTrackingMode.STOPWATCH ->
            baseline?.currentStepStopwatchElapsed(elapsedNow)?.durationText()
                ?: stringResource(R.string.expanded_sequence_timing_unavailable)
        row.occurrence.status == RuntimeOccurrenceStatus.CURRENT &&
            row.activity.timeTrackingMode == TimeTrackingMode.TIMER -> {
            val overtime = baseline?.timerOvertime(elapsedNow)
            if (overtime != null && !overtime.isZero) {
                stringResource(R.string.expanded_sequence_overtime, overtime.durationText())
            } else {
                baseline?.timerRemaining(elapsedNow)?.durationText()
                    ?: stringResource(R.string.expanded_sequence_timing_unavailable)
            }
        }
        completedDuration != null -> completedDuration.durationText()
        row.activity.timeTrackingMode == TimeTrackingMode.TIMER ->
            row.activity.timerTarget?.durationText()
                ?: stringResource(R.string.expanded_sequence_timing_unavailable)
        row.activity.timeTrackingMode == TimeTrackingMode.STOPWATCH ->
            stringResource(
                R.string.expanded_sequence_stopwatch,
            )
        else -> stringResource(R.string.expanded_sequence_no_live)
    }
}

@Composable
private fun ConfirmationDialog(
    confirmation: ExpandedSequenceConfirmation?,
    controller: ExpandedLiveSequenceController,
) {
    if (confirmation == null) return
    AlertDialog(
        onDismissRequest = controller::dismissConfirmation,
        title = {
            Text(
                stringResource(
                    if (confirmation is ExpandedSequenceConfirmation.GoNow) {
                        R.string.expanded_sequence_confirm_jump_title
                    } else {
                        R.string.expanded_sequence_confirm_end_title
                    },
                ),
            )
        },
        text = { Text(stringResource(R.string.expanded_sequence_confirm_detail)) },
        confirmButton = {
            TextButton(
                onClick = controller::confirmPending,
            ) { Text(stringResource(R.string.expanded_sequence_confirm)) }
        },
        dismissButton = {
            TextButton(
                onClick = controller::dismissConfirmation,
            ) { Text(stringResource(R.string.expanded_sequence_cancel)) }
        },
    )
}

@Composable
private fun RuntimeAddDialog(
    state: ExpandedLiveSequenceState,
    placements: Set<RuntimeInsertionPlacement>,
    controller: ExpandedLiveSequenceController,
    onDismiss: () -> Unit,
) {
    var placement by remember(placements) { mutableStateOf(placements.first()) }
    var oneOffName by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(TimeTrackingMode.NO_LIVE_TRACKING) }
    var timerSeconds by remember { mutableStateOf("60") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expanded_sequence_runtime_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    placements.forEach { candidate ->
                        TextButton(onClick = { placement = candidate }) {
                            Text((if (candidate == placement) "✓ " else "") + placementLabel(candidate))
                        }
                    }
                }
                Text(stringResource(R.string.expanded_sequence_reusable_activity))
                if (state.catalogFailure != null) Text(stringResource(R.string.expanded_sequence_catalog_failure))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    items(state.catalog.orEmpty(), key = { it.id.value }) { item ->
                        TextButton(
                            onClick = {
                                controller.runtimeAddTemplate(item.id, placement)
                                onDismiss()
                            },
                        ) { Text(item.name) }
                    }
                }
                Text(stringResource(R.string.expanded_sequence_one_off))
                LifeTracingOutlinedTextField(
                    value = oneOffName,
                    onValueChange = { oneOffName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.expanded_sequence_name)) },
                )
                Row {
                    TimeTrackingMode.entries.forEach { candidate ->
                        TextButton(onClick = { mode = candidate }) {
                            Text((if (mode == candidate) "✓ " else "") + modeLabel(candidate))
                        }
                    }
                }
                if (mode == TimeTrackingMode.TIMER) {
                    LifeTracingOutlinedTextField(
                        value = timerSeconds,
                        onValueChange = { timerSeconds = it },
                        label = { Text(stringResource(R.string.expanded_sequence_timer_seconds)) },
                    )
                }
            }
        },
        confirmButton = {
            val seconds = timerSeconds.toLongOrNull()
            TextButton(
                enabled = oneOffName.isNotBlank() && (mode != TimeTrackingMode.TIMER || seconds != null && seconds > 0),
                onClick = {
                    controller.runtimeAddOneOff(
                        ActivitySnapshotDraft(
                            oneOffName.trim(),
                            null,
                            mode,
                            if (mode == TimeTrackingMode.TIMER) Duration.ofSeconds(requireNotNull(seconds)) else null,
                        ),
                        placement,
                    )
                    onDismiss()
                },
            ) { Text(stringResource(R.string.expanded_sequence_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.expanded_sequence_cancel)) } },
    )
}

@Composable
private fun occurrenceState(status: RuntimeOccurrenceStatus): String =
    stringResource(
        when (status) {
            RuntimeOccurrenceStatus.NOT_STARTED -> R.string.expanded_sequence_upcoming
            RuntimeOccurrenceStatus.CURRENT -> R.string.expanded_sequence_current
            RuntimeOccurrenceStatus.COMPLETED -> R.string.expanded_sequence_completed
            RuntimeOccurrenceStatus.SKIPPED -> R.string.expanded_sequence_skipped
            RuntimeOccurrenceStatus.DELETED_EXECUTION -> R.string.expanded_sequence_deleted
        },
    )

@Composable
private fun placementLabel(placement: RuntimeInsertionPlacement): String =
    stringResource(
        when (placement) {
            RuntimeInsertionPlacement.TO_END -> R.string.expanded_sequence_to_end
            RuntimeInsertionPlacement.AFTER_CURRENT -> R.string.expanded_sequence_after_current
            RuntimeInsertionPlacement.START_NOW -> R.string.expanded_sequence_start_now
        },
    )

@Composable
private fun modeLabel(mode: TimeTrackingMode): String =
    stringResource(
        when (mode) {
            TimeTrackingMode.STOPWATCH -> R.string.expanded_sequence_stopwatch
            TimeTrackingMode.TIMER -> R.string.expanded_sequence_timer
            TimeTrackingMode.NO_LIVE_TRACKING -> R.string.expanded_sequence_no_live
        },
    )

private fun ActivitySnapshotField.label(): String =
    listOfNotNull(localNameOverride ?: nameAtCreation, unit).joinToString(" ")

private fun com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOption.label(): String =
    localLabelOverride ?: labelAtCreation

private fun Duration.durationText(): String = DateUtils.formatElapsedTime(seconds.coerceAtLeast(0))

@Composable
private fun CommandFailure(failure: ExpandedSequenceFailure) {
    Text(
        text =
            stringResource(
                when (failure) {
                    is ExpandedSequenceFailure.Rejected -> R.string.expanded_sequence_command_rejected
                    is ExpandedSequenceFailure.StaleTarget -> R.string.expanded_sequence_target_stale
                    is ExpandedSequenceFailure.Coordination -> R.string.expanded_sequence_coordination_failed
                },
            ),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xLarge),
    )
}

@Composable
private fun displayTick(
    baseline: RuntimeDisplayBaseline?,
    fixed: Long?,
): Long {
    if (fixed != null) return fixed
    var elapsed by remember(baseline?.identity) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(baseline?.identity) {
        if (baseline == null) return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            elapsed = SystemClock.elapsedRealtime()
        }
    }
    return elapsed
}
