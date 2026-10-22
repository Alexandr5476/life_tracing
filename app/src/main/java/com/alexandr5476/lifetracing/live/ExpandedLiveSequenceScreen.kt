@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.live

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
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
    isRouteVisible: Boolean = true,
) {
    val state by controller.state.collectAsState()
    LaunchedEffect(state.stale) {
        if (state.stale) onStale()
    }
    ExpandedLiveSequenceScreen(state, controller, onBack, isRouteVisible = isRouteVisible)
}

@Composable
internal fun ExpandedLiveSequenceScreen(
    state: ExpandedLiveSequenceState,
    controller: ExpandedLiveSequenceController,
    onBack: () -> Unit,
    displayElapsedRealtimeMs: Long? = null,
    isRouteVisible: Boolean = true,
) {
    LifeTracingAccessibleTargets {
        ExpandedSequenceContent(state, controller, onBack, displayElapsedRealtimeMs, isRouteVisible)
    }
}

// Native windows install their own platform locals. Keep the route's effective
// locale and appearance while retaining each window's native View and focus owner.
private class SequenceWindowPresentation(
    private val context: Context,
    private val configuration: Configuration,
    private val density: Density,
) {
    @Composable
    fun Content(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides density,
            content = content,
        )
    }
}

@Composable
private fun sequenceWindowPresentation() =
    SequenceWindowPresentation(LocalContext.current, LocalConfiguration.current, LocalDensity.current)

@Composable
private fun ExpandedSequenceContent(
    state: ExpandedLiveSequenceState,
    controller: ExpandedLiveSequenceController,
    onBack: () -> Unit,
    displayElapsedRealtimeMs: Long?,
    isRouteVisible: Boolean,
) {
    // A retained projection cannot expose commands while its canonical replacement is unconfirmed.
    val sequence = state.confirmedSequence
    val elapsedNow = displayTick(state.displayBaseline.takeIf { sequence != null }, displayElapsedRealtimeMs)
    // Retain ephemeral input, never command authority. Changed current/state, failed reads
    // and stale routes permanently invalidate the context; loading alone does not.
    val ui = rememberSequenceUiContext(state)
    val runtime = state.sequence?.runtime
    val suspended by rememberUpdatedState(sequence == null || !isRouteVisible)
    val backFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val pane = stringResource(R.string.expanded_sequence_pane)
    LaunchedEffect(runtime?.execution?.id, state.stale, isRouteVisible) {
        if (isRouteVisible && !state.stale) {
            if (sequence != null) listState.scrollToItem(0)
            backFocus.requestFocus()
        }
    }
    Surface(modifier = Modifier.semantics { paneTitle = pane }, color = MaterialTheme.colorScheme.background) {
        if (sequence == null) {
            SequenceRecoveryContent(state, controller, onBack, backFocus)
            return@Surface
        }
        val actions = expandedActions(sequence.state)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item {
                SequenceHeader(sequence, state.displayBaseline, elapsedNow, onBack, backFocus)
            }
            item { RouteFeedback(state, controller) }
            state.commandFailure?.let { failure ->
                item { CommandFailure(failure) }
            }
            item {
                PrimaryControls(sequence, state.currentValueDraft, state.commandInFlight, controller)
            }
            item {
                GlobalActions(
                    actions,
                    state.commandInFlight,
                    onRuntimeAdd = {
                        controller.loadRuntimeAddCatalog()
                        ui.runtimeAdd = RuntimeAddDraft(validRuntimePlacements(sequence.state).first())
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
                    ui.editorFocus,
                    isSuspended = { suspended },
                )
            }
        }
    }
    if (sequence != null) ConfirmationDialog(state, sequence, controller)
    val addDraft = ui.runtimeAdd
    if (addDraft != null && sequence != null && addDraft.placement in validRuntimePlacements(sequence.state)) {
        RuntimeAddDialog(
            state,
            validRuntimePlacements(sequence.state),
            controller,
            addDraft,
            isSuspended = { suspended },
            onDismiss = { ui.runtimeAdd = null },
        )
    }
}

@Composable
private fun SequenceRecoveryContent(
    state: ExpandedLiveSequenceState,
    controller: ExpandedLiveSequenceController,
    onBack: () -> Unit,
    backFocus: FocusRequester,
) {
    Column(
        Modifier.fillMaxSize().padding(MaterialTheme.spacing.xLarge),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus)) {
            Text(stringResource(R.string.expanded_sequence_back))
        }
        FeedbackText(
            if (state.stale) {
                stringResource(R.string.expanded_sequence_stale)
            } else if (state.readFailure != null) {
                stringResource(R.string.expanded_sequence_read_failure)
            } else {
                stringResource(R.string.expanded_sequence_loading)
            },
        )
        if (state.readFailure != null && !state.stale) {
            LifeTracingPrimaryButton(onClick = controller::refresh) {
                Text(stringResource(R.string.expanded_sequence_retry))
            }
        }
    }
}

@Composable
private fun rememberSequenceUiContext(state: ExpandedLiveSequenceState): ExpandedSequenceUiContext {
    val execution = state.sequence?.runtime?.execution
    return remember(
        execution?.id,
        execution?.currentOccurrenceId,
        state.sequence?.state,
        state.stale,
        state.readFailure != null,
    ) { ExpandedSequenceUiContext() }
}

private class ExpandedSequenceUiContext {
    var runtimeAdd by mutableStateOf<RuntimeAddDraft?>(null)
    val editorFocus = EphemeralEditorFocus()
}

private class RuntimeAddDraft(
    initialPlacement: RuntimeInsertionPlacement,
) {
    var placement by mutableStateOf(initialPlacement)
    var oneOffName by mutableStateOf("")
    var mode by mutableStateOf(TimeTrackingMode.NO_LIVE_TRACKING)
    var timerSeconds by mutableStateOf("60")
    val scroll = ScrollState(0)
    val focus = EphemeralEditorFocus()

    fun isValid(): Boolean =
        oneOffName.isNotBlank() && (mode != TimeTrackingMode.TIMER || validTimerSeconds(timerSeconds) != null)
}

// Disposed callbacks cannot mutate a surviving draft or submit against a newer projection.
private class ComposedActions {
    var active = true

    fun runIfActive(action: () -> Unit) {
        if (active) action()
    }
}

@Composable
private fun rememberComposedActions(): ComposedActions {
    val callbacks = remember { ComposedActions() }
    DisposableEffect(callbacks) { onDispose { callbacks.active = false } }
    return callbacks
}

private class EphemeralEditorFocus {
    var focused: String? = null
}

@Composable
private fun Modifier.restoreEditorFocus(
    focus: EphemeralEditorFocus,
    target: String,
    enabled: Boolean,
    isSuspended: () -> Boolean,
): Modifier {
    val requester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(requester) {
        // Only a revalidated, authorized editor is composed here. Requesting focus
        // never invokes an edit callback or a runtime command.
        if (enabled && !isSuspended() && focus.focused == target) requester.requestFocus()
    }
    return this.focusRequester(requester).onFocusChanged {
        if (it.isFocused) {
            focus.focused = target
        } else if (hadFocus && !isSuspended() && focus.focused == target) {
            focus.focused = null
        }
        hadFocus = it.isFocused
    }
}

@Composable
private fun GlobalActions(
    actions: ExpandedSequenceActions,
    inFlight: Boolean,
    onRuntimeAdd: () -> Unit,
    onEndEarly: () -> Unit,
) {
    val window = sequenceWindowPresentation()
    var open by remember(actions, inFlight) { mutableStateOf(false) }
    val description = stringResource(R.string.expanded_sequence_global_actions)
    val callbacks = rememberComposedActions()
    Box(Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.xLarge)) {
        TextButton(
            enabled = !inFlight,
            onClick = { open = true },
            modifier =
                Modifier
                    .testTag("expanded-sequence-global-actions")
                    .semantics { contentDescription = description },
        ) { Text(stringResource(R.string.expanded_sequence_more)) }
        DropdownMenu(expanded = open && !inFlight, onDismissRequest = { open = false }) {
            window.Content {
                if (actions.runtimeAdd) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.expanded_sequence_runtime_add)) },
                        onClick = {
                            callbacks.runIfActive {
                                open = false
                                onRuntimeAdd()
                            }
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.expanded_sequence_end_early)) },
                    onClick = {
                        callbacks.runIfActive {
                            open = false
                            onEndEarly()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SequenceHeader(
    sequence: ExpandedLiveSequence,
    baseline: RuntimeDisplayBaseline?,
    elapsedNow: Long,
    onBack: () -> Unit,
    backFocus: FocusRequester,
) {
    val runtimeState = sequenceState(sequence.state)
    val completed =
        remember(sequence.occurrences) {
            sequence.occurrences.count { it.occurrence.status == RuntimeOccurrenceStatus.COMPLETED }
        }
    Column(
        Modifier.fillMaxWidth().padding(MaterialTheme.spacing.xLarge),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.focusRequester(backFocus)) {
            Text(stringResource(R.string.expanded_sequence_back))
        }
        Text(
            sequence.runtime.snapshot.name,
            modifier =
                Modifier.semantics {
                    heading()
                    stateDescription = runtimeState
                },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(runtimeState, Modifier.testTag("expanded-sequence-state"))
        Text(stringResource(R.string.expanded_sequence_progress, completed, sequence.occurrences.size))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large)) {
            TimingText(R.string.expanded_sequence_active_time, baseline?.activeElapsed(elapsedNow))
            TimingText(R.string.expanded_sequence_pause_time, baseline?.pauseElapsed(elapsedNow))
        }
        if (sequence.state == ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN ||
            sequence.state == ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
        ) {
            TimingText(
                R.string.expanded_sequence_transition_remaining,
                baseline?.transitionCountdownRemaining(elapsedNow),
            )
        }
    }
}

@Composable
private fun PrimaryControls(
    sequence: ExpandedLiveSequence,
    draft: CurrentValueDraft?,
    inFlight: Boolean,
    controller: ExpandedLiveSequenceController,
) {
    val actions = expandedActions(sequence.state)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.xLarge),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        when {
            actions.completeCurrent && sequence.runtime.execution.currentOccurrenceId != null ->
                LifeTracingPrimaryButton(
                    onClick = controller::completeCurrent,
                    enabled = !inFlight && draft?.invalidNumberFields.isNullOrEmpty(),
                ) {
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
    editorFocus: EphemeralEditorFocus,
    isSuspended: () -> Boolean,
) {
    val identity = occurrenceIdentity(row)
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
                        modifier =
                            Modifier.semantics {
                                heading()
                                contentDescription = identity
                            },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    row.activity.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                OccurrenceMenu(sequence, row, inFlight, controller, editorFocus, isSuspended)
            }
            Text(stringResource(R.string.expanded_sequence_position, row.occurrence.runtimePosition + 1))
            Text(occurrenceState(row.occurrence.status), style = MaterialTheme.typography.labelLarge)
            row.occurrence.repeatIteration?.let {
                Text(stringResource(R.string.expanded_sequence_repeat_iteration, it))
            }
            if (row.occurrence.isRuntimeAdded) Text(stringResource(R.string.expanded_sequence_runtime_added))
            val spokenTiming = activityTimingText(row, baseline, elapsedNow, spoken = true)
            Text(
                activityTimingText(row, baseline, elapsedNow),
                modifier = Modifier.semantics { contentDescription = spokenTiming },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.occurrence.status != RuntimeOccurrenceStatus.DELETED_EXECUTION) {
                ValueSummary(row, draft?.takeIf { current && it.occurrenceId == row.occurrence.id })
            }
            if (current &&
                expandedActions(sequence.state).editCurrentValues &&
                draft?.occurrenceId == row.occurrence.id
            ) {
                CurrentValueEditor(row, draft, inFlight, controller, editorFocus, isSuspended)
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
    editorFocus: EphemeralEditorFocus,
    isSuspended: () -> Boolean,
) {
    val window = sequenceWindowPresentation()
    val actions = expandedActions(sequence.state)
    val go = actions.goNow && row.occurrence.status == RuntimeOccurrenceStatus.NOT_STARTED
    val next = actions.makeNext && row.occurrence.status == RuntimeOccurrenceStatus.NOT_STARTED
    val again = actions.doAgain && row.occurrence.status == RuntimeOccurrenceStatus.COMPLETED
    if (!go && !next && !again) return
    val callbacks = rememberComposedActions()
    var open by remember(row.occurrence.id, sequence.state, row.occurrence.status, inFlight) { mutableStateOf(false) }
    val actionsDescription = stringResource(R.string.expanded_sequence_row_actions, occurrenceIdentity(row))
    Box {
        TextButton(
            enabled = !inFlight,
            onClick = { open = true },
            modifier =
                Modifier
                    .semantics { contentDescription = actionsDescription }
                    .restoreEditorFocus(editorFocus, "occurrence-${row.occurrence.id.value}", !inFlight, isSuspended),
        ) { Text(stringResource(R.string.expanded_sequence_more)) }
        DropdownMenu(expanded = open && !inFlight, onDismissRequest = { open = false }) {
            window.Content {
                if (go) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.expanded_sequence_go_now)) },
                        onClick = {
                            callbacks.runIfActive {
                                open = false
                                controller.requestGoNow(row.occurrence.id)
                            }
                        },
                    )
                }
                if (next) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.expanded_sequence_make_next)) },
                        onClick = {
                            callbacks.runIfActive {
                                open = false
                                controller.makeNext(row.occurrence.id)
                            }
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
                                callbacks.runIfActive {
                                    open = false
                                    controller.doAgain(row.occurrence.id, placement)
                                }
                            },
                        )
                    }
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
    editorFocus: EphemeralEditorFocus,
    isSuspended: () -> Boolean,
) {
    val callbacks = rememberComposedActions()
    row.activity.fields.sortedBy(ActivitySnapshotField::position).forEach { field ->
        when (field.type) {
            CustomFieldType.NUMBER ->
                LifeTracingOutlinedTextField(
                    value = draft.numberTexts[field.id].orEmpty(),
                    onValueChange = { if (callbacks.active) controller.editNumber(field.id, it) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("expanded-sequence-current-value-${field.id.value}")
                            .restoreEditorFocus(editorFocus, field.id.value, !inFlight, isSuspended),
                    label = { Text(field.label()) },
                    isError = field.id in draft.invalidNumberFields,
                    enabled = !inFlight,
                )
            CustomFieldType.TEXT ->
                LifeTracingOutlinedTextField(
                    value = (draft.values[field.id] as? TextExecutionValue)?.value.orEmpty(),
                    onValueChange = { if (callbacks.active) controller.editText(field.id, it) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("expanded-sequence-current-value-${field.id.value}")
                            .restoreEditorFocus(editorFocus, field.id.value, !inFlight, isSuspended),
                    label = { Text(field.label()) },
                    enabled = !inFlight,
                )
            CustomFieldType.CATEGORY -> CategoryEditor(field, draft.values[field.id], inFlight, controller)
        }
        val missingLabel = stringResource(R.string.expanded_sequence_missing_field, field.label())
        TextButton(
            onClick = { controller.markMissing(field.id) },
            enabled = !inFlight,
            modifier =
                Modifier.testTag("expanded-sequence-current-missing-${field.id.value}").semantics {
                    contentDescription = missingLabel
                },
        ) {
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
    inFlight: Boolean,
    controller: ExpandedLiveSequenceController,
) {
    val window = sequenceWindowPresentation()
    var open by remember(field.id, inFlight) { mutableStateOf(false) }
    val selected = (value as? CategoryExecutionValue)?.optionId
    val valueLabel =
        field.categoryOptions.singleOrNull { it.id == selected }?.label()
            ?: stringResource(R.string.expanded_sequence_missing)
    val fieldLabel = stringResource(R.string.expanded_sequence_field_value, field.label(), valueLabel)
    Box {
        LifeTracingSecondaryButton(
            onClick = { open = true },
            enabled = !inFlight,
            modifier =
                Modifier.testTag("expanded-sequence-current-value-${field.id.value}").semantics {
                    contentDescription = fieldLabel
                    stateDescription = valueLabel
                },
        ) {
            Text(
                field.categoryOptions.singleOrNull { it.id == selected }?.label()
                    ?: stringResource(R.string.expanded_sequence_missing),
            )
        }
        DropdownMenu(expanded = open && !inFlight, onDismissRequest = { open = false }) {
            window.Content {
                field.categoryOptions.sortedBy { it.position }.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label()) },
                        modifier = Modifier.semantics { this.selected = option.id == selected },
                        onClick = {
                            open = false
                            controller.editCategory(field.id, option.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ValueSummary(
    row: ExpandedLiveSequenceOccurrence,
    draft: CurrentValueDraft?,
) {
    val values = draft?.values ?: row.displayValues()
    val projectionLabel =
        stringResource(
            when {
                draft != null -> R.string.expanded_sequence_draft_values
                row.occurrence.status == RuntimeOccurrenceStatus.COMPLETED -> R.string.expanded_sequence_actual_values
                row.occurrence.status == RuntimeOccurrenceStatus.CURRENT && row.childExecution != null ->
                    R.string.expanded_sequence_actual_values
                else -> R.string.expanded_sequence_configured_values
            },
        )
    if (row.activity.fields.isNotEmpty()) Text(projectionLabel)
    row.activity.fields.sortedBy(ActivitySnapshotField::position).forEach { field ->
        val fieldName = field.label()
        val type =
            stringResource(
                when (field.type) {
                    CustomFieldType.NUMBER -> R.string.activity_editor_number
                    CustomFieldType.CATEGORY -> R.string.activity_editor_category
                    CustomFieldType.TEXT -> R.string.activity_editor_text
                },
            )
        val value = field.valueText(values[field.id])
        val description =
            stringResource(
                R.string.expanded_sequence_field_summary,
                projectionLabel,
                fieldName,
                type,
                value,
            )
        Text(
            stringResource(R.string.expanded_sequence_field_value, fieldName, value),
            Modifier.testTag("expanded-sequence-value-${row.occurrence.id.value}-${field.id.value}").semantics {
                contentDescription = description
            },
        )
    }
}

private fun ExpandedLiveSequenceOccurrence.displayValues(): Map<ActivitySnapshotFieldId, ActivityExecutionFieldValue?> {
    val actual = childExecution?.values?.associateBy(ActivityExecutionFieldValue::snapshotFieldId)
    if (occurrence.status == RuntimeOccurrenceStatus.COMPLETED ||
        occurrence.status == RuntimeOccurrenceStatus.CURRENT &&
        childExecution != null
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
    spoken: Boolean = false,
): String {
    val completedDuration = row.childExecution?.activeDuration
    return when {
        row.occurrence.status == RuntimeOccurrenceStatus.CURRENT &&
            row.activity.timeTrackingMode == TimeTrackingMode.STOPWATCH ->
            stringResource(
                R.string.expanded_sequence_stopwatch_elapsed,
                baseline?.currentStepStopwatchElapsed(elapsedNow)?.accessibleDuration(spoken)
                    ?: stringResource(R.string.expanded_sequence_timing_unavailable),
            )
        row.occurrence.status == RuntimeOccurrenceStatus.CURRENT &&
            row.activity.timeTrackingMode == TimeTrackingMode.TIMER -> {
            val overtime = baseline?.timerOvertime(elapsedNow)
            if (overtime != null && !overtime.isZero) {
                stringResource(R.string.expanded_sequence_overtime, overtime.accessibleDuration(spoken))
            } else {
                stringResource(
                    R.string.expanded_sequence_timer_remaining,
                    baseline?.timerRemaining(elapsedNow)?.accessibleDuration(spoken)
                        ?: stringResource(R.string.expanded_sequence_timing_unavailable),
                )
            }
        }
        completedDuration != null ->
            stringResource(
                R.string.expanded_sequence_actual_duration,
                modeLabel(row.activity.timeTrackingMode),
                completedDuration.accessibleDuration(spoken),
            )
        row.activity.timeTrackingMode == TimeTrackingMode.TIMER ->
            stringResource(
                R.string.expanded_sequence_timer_target,
                row.activity.timerTarget?.accessibleDuration(spoken)
                    ?: stringResource(R.string.expanded_sequence_timing_unavailable),
            )
        row.activity.timeTrackingMode == TimeTrackingMode.STOPWATCH ->
            stringResource(
                R.string.expanded_sequence_stopwatch,
            )
        else -> stringResource(R.string.expanded_sequence_no_live)
    }
}

@Composable
private fun SequenceDialogHeading(title: String) {
    Text(
        title,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ConfirmationDialog(
    state: ExpandedLiveSequenceState,
    sequence: ExpandedLiveSequence,
    controller: ExpandedLiveSequenceController,
) {
    val window = sequenceWindowPresentation()
    val confirmation = state.confirmation ?: return
    val target =
        if (confirmation is ExpandedSequenceConfirmation.GoNow) {
            sequence.occurrences.singleOrNull { it.occurrence.id == confirmation.occurrenceId }
        } else {
            null
        }
    val available =
        when (confirmation) {
            is ExpandedSequenceConfirmation.GoNow ->
                expandedActions(sequence.state).goNow &&
                    target?.occurrence?.status == RuntimeOccurrenceStatus.NOT_STARTED
            ExpandedSequenceConfirmation.EndEarly -> expandedActions(sequence.state).endEarly
        }
    if (!available) {
        LaunchedEffect(confirmation) { controller.dismissConfirmation() }
        return
    }
    val title =
        stringResource(
            if (confirmation is ExpandedSequenceConfirmation.GoNow) {
                R.string.expanded_sequence_confirm_jump_title
            } else {
                R.string.expanded_sequence_confirm_end_title
            },
        )
    AlertDialog(
        onDismissRequest = controller::dismissConfirmation,
        text = {
            window.Content {
                // Keep the heading in the weighted, scrollable body so long localized
                // titles cannot consume the space reserved for both dialog actions.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                ) {
                    SequenceDialogHeading(title)
                    Column {
                        Text(target?.let { occurrenceIdentity(it) } ?: sequence.runtime.snapshot.name)
                        Text(stringResource(R.string.expanded_sequence_confirm_detail))
                    }
                }
            }
        },
        confirmButton = {
            window.Content {
                TextButton(
                    enabled = !state.commandInFlight,
                    onClick = controller::confirmPending,
                ) { Text(stringResource(R.string.expanded_sequence_confirm)) }
            }
        },
        dismissButton = {
            window.Content {
                TextButton(onClick = controller::dismissConfirmation) {
                    Text(stringResource(R.string.expanded_sequence_cancel))
                }
            }
        },
    )
}

@Composable
private fun RuntimeAddDialog(
    state: ExpandedLiveSequenceState,
    placements: Set<RuntimeInsertionPlacement>,
    controller: ExpandedLiveSequenceController,
    draft: RuntimeAddDraft,
    isSuspended: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val window = sequenceWindowPresentation()
    var placement by draft::placement
    val canAdd = !state.commandInFlight && placement in placements
    var oneOffName by draft::oneOffName
    var mode by draft::mode
    var timerSeconds by draft::timerSeconds
    val callbacks = rememberComposedActions()
    val dismiss = { if (callbacks.active) onDismiss() }
    val title = stringResource(R.string.expanded_sequence_runtime_add)
    AlertDialog(
        onDismissRequest = dismiss,
        text = {
            window.Content {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(draft.scroll),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    SequenceDialogHeading(title)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                        placements.forEach { candidate ->
                            LifeTracingChoiceButton(
                                selected = candidate == placement,
                                onClick = { if (callbacks.active) placement = candidate },
                                enabled = !state.commandInFlight,
                                modifier =
                                    Modifier.semantics {
                                        selected = candidate == placement
                                        role = Role.RadioButton
                                    },
                            ) { Text(placementLabel(candidate)) }
                        }
                    }
                    RuntimeAddCatalog(state, canAdd, placement, controller, callbacks, dismiss)
                    Text(stringResource(R.string.expanded_sequence_one_off))
                    LifeTracingOutlinedTextField(
                        value = oneOffName,
                        onValueChange = { if (callbacks.active) oneOffName = it },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .restoreEditorFocus(draft.focus, "name", !state.commandInFlight, isSuspended),
                        label = { Text(stringResource(R.string.expanded_sequence_name)) },
                        enabled = !state.commandInFlight,
                    )
                    FlowRow {
                        TimeTrackingMode.entries.forEach { candidate ->
                            LifeTracingChoiceButton(
                                selected = mode == candidate,
                                onClick = { if (callbacks.active) mode = candidate },
                                enabled = !state.commandInFlight,
                                modifier =
                                    Modifier.semantics {
                                        selected = mode == candidate
                                        role = Role.RadioButton
                                    },
                            ) { Text(modeLabel(candidate)) }
                        }
                    }
                    if (mode == TimeTrackingMode.TIMER) {
                        LifeTracingOutlinedTextField(
                            value = timerSeconds,
                            onValueChange = { if (callbacks.active) timerSeconds = it },
                            modifier =
                                Modifier.restoreEditorFocus(
                                    draft.focus,
                                    "timer",
                                    !state.commandInFlight,
                                    isSuspended,
                                ),
                            label = { Text(stringResource(R.string.expanded_sequence_timer_seconds)) },
                            enabled = !state.commandInFlight,
                            isError = validTimerSeconds(timerSeconds) == null,
                            supportingText = { Text(stringResource(R.string.expanded_sequence_timer_validation)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            window.Content {
                val seconds = validTimerSeconds(timerSeconds)
                TextButton(
                    enabled = canAdd && draft.isValid(),
                    onClick = {
                        if (!callbacks.active) return@TextButton
                        controller.runtimeAddOneOff(
                            ActivitySnapshotDraft(
                                oneOffName.trim(),
                                null,
                                mode,
                                if (mode == TimeTrackingMode.TIMER) {
                                    Duration.ofSeconds(requireNotNull(seconds))
                                } else {
                                    null
                                },
                            ),
                            placement,
                        )
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.expanded_sequence_add)) }
            }
        },
        dismissButton = {
            window.Content {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.expanded_sequence_cancel)) }
            }
        },
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

@Composable
private fun ActivitySnapshotField.label(): String {
    val name = listOfNotNull(localNameOverride ?: nameAtCreation, unit).joinToString(" ")
    return if (isMainValue) stringResource(R.string.expanded_sequence_main_value_field, name) else name
}

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
        modifier =
            Modifier
                .padding(
                    horizontal = MaterialTheme.spacing.xLarge,
                ).semantics { liveRegion = LiveRegionMode.Polite },
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

@Composable
private fun FeedbackText(text: String) {
    Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun occurrenceIdentity(row: ExpandedLiveSequenceOccurrence): String =
    stringResource(
        R.string.expanded_sequence_occurrence_identity,
        row.activity.name,
        row.occurrence.runtimePosition + 1,
    )

@Composable
private fun sequenceState(state: ActiveSequenceState): String =
    stringResource(
        when (state) {
            ActiveSequenceState.RUNNING_CURRENT -> R.string.expanded_sequence_running_current
            ActiveSequenceState.PAUSED_CURRENT -> R.string.expanded_sequence_paused_current
            ActiveSequenceState.WAITING_NEXT -> R.string.expanded_sequence_waiting_next
            ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN -> R.string.expanded_sequence_running_countdown
            ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN -> R.string.expanded_sequence_paused_countdown
        },
    )

@Composable
private fun RouteFeedback(
    state: ExpandedLiveSequenceState,
    controller: ExpandedLiveSequenceController,
) {
    val message =
        when {
            state.readFailure != null -> R.string.expanded_sequence_read_failure
            state.commandInFlight -> R.string.expanded_sequence_command_in_flight
            state.loading -> R.string.expanded_sequence_refreshing
            else -> return
        }
    Column(Modifier.padding(horizontal = MaterialTheme.spacing.xLarge)) {
        FeedbackText(stringResource(message))
        if (state.readFailure != null) {
            TextButton(onClick = controller::refresh, enabled = !state.loading) {
                Text(stringResource(R.string.expanded_sequence_retry))
            }
        }
    }
}

@Composable
private fun RuntimeAddCatalog(
    state: ExpandedLiveSequenceState,
    canAdd: Boolean,
    placement: RuntimeInsertionPlacement,
    controller: ExpandedLiveSequenceController,
    callbacks: ComposedActions,
    onDismiss: () -> Unit,
) {
    Text(stringResource(R.string.expanded_sequence_reusable_activity))
    if (state.catalogLoading) FeedbackText(stringResource(R.string.expanded_sequence_catalog_loading))
    if (state.catalogFailure != null) {
        FeedbackText(stringResource(R.string.expanded_sequence_catalog_failure))
        TextButton(
            onClick = {
                if (state.catalog == null) {
                    controller.loadRuntimeAddCatalog()
                } else {
                    controller.loadMoreRuntimeAddCatalog()
                }
            },
            enabled = !state.catalogLoading && !state.commandInFlight,
        ) { Text(stringResource(R.string.expanded_sequence_retry)) }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp).testTag("expanded-sequence-add-catalog")) {
        itemsIndexed(state.catalog.orEmpty(), key = { _, item -> item.id.value }) { index, item ->
            // Appending a page preserves existing positions. Dispatch still captures the Template ID.
            val identity =
                stringResource(
                    R.string.expanded_sequence_catalog_identity,
                    item.name,
                    modeLabel(item.timeTrackingMode),
                    index + 1,
                )
            TextButton(
                modifier = Modifier.semantics { contentDescription = identity },
                enabled = canAdd,
                onClick = {
                    if (!callbacks.active) return@TextButton
                    controller.runtimeAddTemplate(item.id, placement)
                    onDismiss()
                },
            ) { Text(item.name) }
        }
        if (state.catalogCanLoadMore) {
            item {
                TextButton(
                    onClick = controller::loadMoreRuntimeAddCatalog,
                    enabled = !state.catalogLoading && !state.commandInFlight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (state.catalogLoading) {
                                R.string.sequence_editor_saving
                            } else {
                                R.string.sequence_editor_load_more
                            },
                        ),
                    )
                }
            }
        }
    }
}

private fun validTimerSeconds(text: String): Long? = text.toLongOrNull()?.takeIf { it in 1..(Long.MAX_VALUE / 1_000) }

@Composable
private fun TimingText(
    label: Int,
    duration: Duration?,
) {
    val unavailable = stringResource(R.string.expanded_sequence_timing_unavailable)
    val description = stringResource(label, duration?.accessibleDuration(spoken = true) ?: unavailable)
    Text(
        stringResource(label, duration?.durationText() ?: unavailable),
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun Duration.accessibleDuration(spoken: Boolean): String {
    if (!spoken) return durationText()
    val totalSeconds = seconds.coerceAtLeast(0)
    return stringResource(
        R.string.daily_duration_seconds,
        totalSeconds / 3_600,
        totalSeconds / 60 % 60,
        totalSeconds % 60,
    )
}
