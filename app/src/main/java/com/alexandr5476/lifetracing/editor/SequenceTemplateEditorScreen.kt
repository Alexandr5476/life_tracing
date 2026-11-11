@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "TooManyFunctions",
    "UnusedParameter",
)

package com.alexandr5476.lifetracing.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.time.Duration

@Composable
fun SequenceTemplateEditorRoute(
    controller: SequenceTemplateEditorController,
    onCommitted: (() -> Unit)? = null,
    onApplied: ((Long) -> Unit)? = null,
    isRouteVisible: Boolean = true,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    var delivered by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(state.save) {
        if (state.save is SequenceTemplateEditorSave.Committed && !delivered) {
            delivered = true
            (onCommitted ?: onBack)()
        }
    }
    LaunchedEffect(state.appliedGeneration) {
        if (state.appliedGeneration > 0) onApplied?.invoke(state.appliedGeneration)
    }
    BackHandler(enabled = isRouteVisible) { controller.requestBack(onBack) }
    SequenceTemplateEditorScreen(state, controller, onBack, isRouteVisible)
}

@Composable
internal fun SequenceTemplateEditorScreen(
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    onBack: () -> Unit,
    isRouteVisible: Boolean = true,
) {
    val recoveryPending = (state.save as? SequenceTemplateEditorSave.Failure)?.committedAwaitingReload == true
    val phase =
        when (state.load) {
            SequenceTemplateEditorLoad.Loading -> 0
            is SequenceTemplateEditorLoad.Failure -> 1
            is SequenceTemplateEditorLoad.Ready -> if (recoveryPending) SEQUENCE_RECOVERY_FOCUS_PHASE else 2
        }
    val editable =
        state.save !is SequenceTemplateEditorSave.Saving &&
            state.save !is SequenceTemplateEditorSave.Committed
    val focus =
        rememberEditorFocus(
            phase,
            state.generation,
            state.discardConfirmationVisible,
            editable,
            isRouteVisible,
            keyboardOnly = true,
        )
    val title =
        stringResource(
            if (controller.target is SequenceTemplateEditorTarget.New) {
                R.string.sequence_editor_new_context
            } else {
                R.string.sequence_editor_existing_context
            },
        )
    // Disclosure survives canonical publication; callback authority still ends with its load generation.
    val expandedSteps = rememberSaveable(controller) { mutableStateOf(emptySet<String>()) }
    val ready = state.load as? SequenceTemplateEditorLoad.Ready
    val description =
        stringResource(
            if (ready != null &&
                state.isDirty(ready)
            ) {
                R.string.activity_editor_modified
            } else {
                R.string.activity_editor_pristine
            },
        )
    CompositionLocalProvider(
        LocalEditorFocus provides focus,
        LocalEditorVisible provides isRouteVisible,
        LocalEditorTargetExists provides { true },
    ) {
        LifeTracingAccessibleTargets {
            Surface(
                modifier =
                    focus.root.semantics {
                        paneTitle = title
                        stateDescription = description
                        isTraversalGroup = true
                    },
                color = MaterialTheme.colorScheme.background,
            ) {
                key(controller, state.generation) {
                    val actions = rememberSequenceEditorActions(controller, state)
                    CompositionLocalProvider(LocalSequenceEditorActions provides actions) {
                        when (val load = state.load) {
                            SequenceTemplateEditorLoad.Loading ->
                                SequenceEditorPage(landingIndex = 2) {
                                    item { SequenceEditorHeading(title) }
                                    item { SequenceEditorStatus(R.string.sequence_editor_loading) }
                                    item {
                                        SequenceEditorBack(
                                            controller,
                                            onBack,
                                            Modifier.focusRequester(focus.landing),
                                        )
                                    }
                                }
                            is SequenceTemplateEditorLoad.Failure ->
                                SequenceEditorPage(landingIndex = 2) {
                                    item { SequenceEditorHeading(title) }
                                    item { SequenceEditorStatus(R.string.sequence_editor_load_failure) }
                                    item {
                                        LifeTracingPrimaryButton(
                                            onClick = { actions.invoke(controller::retry) },
                                            modifier = editorFocusTarget().focusRequester(focus.landing),
                                        ) { Text(stringResource(R.string.sequence_editor_retry)) }
                                        SequenceEditorBack(controller, onBack)
                                    }
                                }
                            is SequenceTemplateEditorLoad.Ready ->
                                SequenceEditorForm(load.draft, state, controller, onBack, title, expandedSteps)
                        }
                    }
                }
            }
            if (state.discardConfirmationVisible && isRouteVisible) {
                SequenceEditorDiscard(controller, onBack, state)
            }
        }
    }
}

@Composable
private fun SequenceEditorForm(
    draft: SequenceTemplateDraft,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    onBack: () -> Unit,
    title: String,
    expandedSteps: MutableState<Set<String>>,
) {
    val actions = LocalSequenceEditorActions.current
    val focus = LocalEditorFocus.current
    val recoveryPending = (state.save as? SequenceTemplateEditorSave.Failure)?.committedAwaitingReload == true
    val editable =
        state.save !is SequenceTemplateEditorSave.Saving &&
            state.save !is SequenceTemplateEditorSave.Committed &&
            !recoveryPending &&
            !state.discardConfirmationVisible
    val formEditable = editable && state.manipulation == null
    val manipulating = state.manipulation != null
    val visible = LocalEditorVisible.current
    val window = LocalWindowInfo.current
    val inputMode = LocalInputModeManager.current
    // Mode leases replace native controls. Recover only if that replacement lost the owner.
    LaunchedEffect(manipulating, visible, window.isWindowFocused, inputMode.inputMode, editable) {
        withFrameNanos { }
        val ownsWindow = visible && window.isWindowFocused && inputMode.inputMode == InputMode.Keyboard
        if (editable && ownsWindow && !focus.hasFocus) focus.land()
    }
    val settingsExpanded = rememberSaveable { mutableStateOf(false) }
    var settingsPresentation by remember { mutableStateOf(0L) }
    val formActions = rememberSequenceEditorActions(controller, state, normalModeOnly = true)
    // This lifetime belongs to the disclosure, not to its lazy viewport attachment.
    val settingsActions =
        key(settingsPresentation) {
            rememberSequenceEditorActions(
                controller,
                state,
                normalModeOnly = true,
                disclosure = settingsExpanded,
            )
        }
    LaunchedEffect(settingsExpanded.value) { focus.observeLogicalTargets() }
    var pickerOffer by remember { mutableStateOf<SequenceStepInsertionOffer?>(null) }
    var pickerOrigin by remember { mutableStateOf<EditorFocusHandle?>(null) }
    var pickerRecovery by remember { mutableStateOf(false) }
    val pickerOwnsKeyboardWindow = visible && window.isWindowFocused && inputMode.inputMode == InputMode.Keyboard
    LaunchedEffect(pickerOffer, pickerOwnsKeyboardWindow, formEditable) {
        val restoringPicker = pickerOffer == null && pickerRecovery
        if (restoringPicker && pickerOwnsKeyboardWindow && formEditable) {
            withFrameNanos { }
            if (!focus.hasFocus) {
                val origin = pickerOrigin
                if (origin?.attached != true ||
                    origin.logicallyExists?.invoke() == false ||
                    !origin.requester.requestFocus()
                ) {
                    focus.land()
                }
            }
            pickerRecovery = false
        }
    }
    val choices = remember(state.availableActivities) { state.availableActivities.associateBy { it.id } }
    val rows = remember(draft.nodes) { draft.sequenceEditorRows() }
    val nodeContexts = sequenceManipulationContexts(draft, choices)
    val currentNodeContexts = rememberUpdatedState(nodeContexts)
    LaunchedEffect(rows) {
        val surviving = rows.filterIsInstance<SequenceEditorRow.Step>().map { it.step.identity.editorKey() }.toSet()
        expandedSteps.value = expandedSteps.value.intersect(surviving)
    }
    val topEnd = remember(draft.nodes.size) { SequenceEditorRow.TopEnd(draft.nodes.size) }
    val dropState = remember { SequenceEditorDropState() }
    val listState = rememberLazyListState()
    DisposableEffect(focus, listState, recoveryPending, manipulating) {
        focus.revealLanding = {
            val index =
                when {
                    recoveryPending -> (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                    manipulating -> 0
                    else -> 1
                }
            listState.scrollToItem(index)
        }
        onDispose { focus.revealLanding = {} }
    }
    dropState.visibleRowKeys = { listState.layoutInfo.visibleItemsInfo.map { it.key } }
    dropState.firstRowKey = rows.firstOrNull()?.key ?: topEnd.key
    dropState.lastRowKey = topEnd.key
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val scroll = with(density) { 32.dp.toPx() }
    val awayThreshold = with(density) { 1.dp.toPx() }
    val autoScroll = { pointerY: Float, movementY: Float, uptimeMillis: Long ->
        val delta = dropState.autoScroll(pointerY, movementY, awayThreshold, edge, scroll, uptimeMillis)
        if (delta != 0f) listState.dispatchRawDelta(delta)
    }
    SequenceEditorPage(
        state = listState,
        modifier =
            Modifier
                .testTag("sequence-editor-page")
                .onGloballyPositioned { dropState.viewport = it.boundsInRoot() },
    ) {
        item {
            key(actions) {
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val manipulation = state.manipulation
                    if (manipulation == null) {
                        SequenceEditorHeading(title)
                        CompositionLocalProvider(LocalSequenceEditorActions provides formActions) {
                            SequenceEditorBack(controller, onBack, enabled = editable)
                        }
                    } else {
                        val cancel = stringResource(R.string.sequence_editor_manipulation_cancel)
                        TextButton(
                            onClick = { actions.invoke(controller::discardManipulation) },
                            modifier = editorFocusTarget().semantics { contentDescription = cancel },
                            enabled = editable,
                        ) {
                            Text("\u00D7")
                        }
                        TextButton(
                            onClick = { actions.invoke { controller.undoManipulation() } },
                            modifier = editorFocusTarget(),
                            enabled = editable && manipulation.canUndo,
                        ) {
                            Text(stringResource(R.string.sequence_editor_undo))
                        }
                        TextButton(
                            onClick = { actions.invoke { controller.redoManipulation() } },
                            modifier = editorFocusTarget(),
                            enabled = editable && manipulation.canRedo,
                        ) {
                            Text(stringResource(R.string.sequence_editor_redo))
                        }
                        key(recoveryPending) {
                            TextButton(
                                onClick = {
                                    actions.invoke {
                                        if (recoveryPending) {
                                            controller.retry()
                                        } else if (controller.state.value.manipulation != null) {
                                            controller.applyManipulation()
                                        }
                                    }
                                },
                                modifier = editorFocusTarget(),
                                enabled = recoveryPending || editable && !state.hasInvalidInput(draft),
                            ) {
                                Text(
                                    stringResource(
                                        if (recoveryPending) {
                                            R.string.sequence_editor_retry
                                        } else {
                                            R.string.sequence_editor_apply
                                        },
                                    ),
                                )
                            }
                        }
                        SequenceManipulationControls(state, controller, nodeContexts, editable)
                    }
                }
            }
        }
        item {
            key(formActions) {
                LifeTracingOutlinedTextField(
                    value = draft.name,
                    onValueChange = { name -> formActions.update { it.copy(name = name) } },
                    modifier =
                        editorInputFocusTarget()
                            .then(if (recoveryPending) Modifier else Modifier.focusRequester(focus.landing))
                            .fillMaxWidth(),
                    label = { Text(stringResource(R.string.sequence_editor_name)) },
                    enabled = formEditable,
                )
                LifeTracingOutlinedTextField(
                    value = draft.shortComment.orEmpty(),
                    onValueChange = { value -> formActions.update { it.copy(shortComment = value.ifBlank { null }) } },
                    modifier = editorInputFocusTarget().fillMaxWidth(),
                    label = {
                        Text(stringResource(R.string.sequence_editor_short_comment))
                    },
                    enabled = formEditable,
                )
            }
        }
        item {
            key(formActions) {
                val expanded =
                    stringResource(
                        if (settingsExpanded.value) {
                            R.string.sequence_editor_expanded
                        } else {
                            R.string.sequence_editor_collapsed
                        },
                    )
                LifeTracingSecondaryButton(
                    onClick = {
                        formActions.invoke {
                            settingsActions.attached = false
                            focus.observeLogicalTargets()
                            settingsPresentation++
                            settingsExpanded.value = !settingsExpanded.value
                        }
                    },
                    modifier = editorFocusTarget().semantics { stateDescription = expanded },
                    enabled = formEditable,
                ) { Text(stringResource(R.string.sequence_editor_settings)) }
            }
        }
        if (settingsExpanded.value) {
            item {
                key(settingsActions) {
                    CompositionLocalProvider(
                        LocalSequenceEditorActions provides settingsActions,
                        LocalEditorTargetExists provides { settingsActions.attached && settingsExpanded.value },
                    ) { SequenceSettings(draft, state, controller, formEditable) }
                }
            }
        }
        item { key(formActions) { SequenceFields(draft, controller, formActions, formEditable) } }
        item { Text(stringResource(R.string.sequence_editor_steps), style = MaterialTheme.typography.titleMedium) }
        items(rows, key = SequenceEditorRow::key) { row ->
            val identity =
                when (row) {
                    is SequenceEditorRow.Step -> row.step.identity
                    is SequenceEditorRow.RepeatHeader -> row.repeat.identity
                    is SequenceEditorRow.RepeatAdd -> row.repeat.identity
                    is SequenceEditorRow.TopEnd -> null
                }
            CompositionLocalProvider(
                LocalEditorTargetExists provides { identity == null || identity in currentNodeContexts.value },
            ) {
                SequenceDropTarget(row, dropState) {
                    when (row) {
                        is SequenceEditorRow.Step ->
                            StepCard(
                                row.step,
                                nodeContexts.getValue(row.step.identity),
                                expandedSteps,
                                state,
                                controller,
                                row.repeat,
                                row.repeatCount,
                                choices[row.templateId],
                                editable,
                                draft,
                                dropState,
                                autoScroll,
                            )
                        is SequenceEditorRow.RepeatHeader ->
                            RepeatHeader(
                                row.repeat,
                                nodeContexts.getValue(row.repeat.identity),
                                state,
                                controller,
                                editable,
                                draft,
                                dropState,
                                autoScroll,
                            )
                        is SequenceEditorRow.RepeatAdd ->
                            key(formActions, formEditable) {
                                AddStepAction(
                                    row.repeat.repeatCount,
                                    nodeContexts.getValue(row.repeat.identity),
                                    formEditable,
                                ) {
                                    formActions.invoke {
                                        pickerOrigin = focus.owner
                                        pickerRecovery = true
                                        pickerOffer =
                                            controller.offerStepInsertion(row.repeat.identity)
                                    }
                                }
                            }
                        is SequenceEditorRow.TopEnd -> Unit
                    }
                }
            }
        }
        item(key = topEnd.key) {
            SequenceDropTarget(topEnd, dropState) {
                key(formActions, formEditable) {
                    AddTopLevel(draft, controller, formEditable) {
                        formActions.invoke {
                            pickerOrigin = focus.owner
                            pickerRecovery = true
                            pickerOffer =
                                controller.offerStepInsertion(null)
                        }
                    }
                }
            }
        }
        item {
            if (state.save is SequenceTemplateEditorSave.Failure) {
                val message =
                    when {
                        state.sourceAttempt != null -> sourceFailureMessage(state.save)
                        state.save.isConflict -> R.string.sequence_editor_conflict
                        else -> R.string.sequence_editor_save_failure
                    }
                SequenceEditorStatus(
                    if (recoveryPending) R.string.sequence_editor_reload_failure else message,
                )
                if (!recoveryPending && (state.save.isConflict || state.sourceAttempt != null)) {
                    key(state.save, state.sourceActionEpoch) {
                        val reloadActions =
                            rememberSequenceEditorActions(controller, state, available = {
                                controller.state.value.save === state.save &&
                                    controller.state.value.sourceActionEpoch == state.sourceActionEpoch
                            })
                        LifeTracingSecondaryButton(
                            onClick = { reloadActions.invoke(controller::retry) },
                            modifier = editorFocusTarget(),
                        ) { Text(stringResource(R.string.activity_editor_reload)) }
                    }
                }
            }
            if (state.save is SequenceTemplateEditorSave.Committed) {
                SequenceEditorStatus(
                    R.string.activity_editor_committed,
                )
            }
            if (state.save is SequenceTemplateEditorSave.Saving) SequenceEditorStatus(R.string.sequence_editor_saving)
            if (recoveryPending || state.manipulation == null) {
                // Save and canonical-read Retry have different authority even in the same load generation.
                key(actions, recoveryPending) {
                    LifeTracingPrimaryButton(
                        onClick = {
                            if (recoveryPending) {
                                actions.invoke(
                                    controller::retry,
                                )
                            } else {
                                formActions.invoke(controller::save)
                            }
                        },
                        modifier =
                            editorFocusTarget()
                                .then(if (recoveryPending) Modifier.focusRequester(focus.landing) else Modifier)
                                .fillMaxWidth(),
                        enabled =
                            recoveryPending ||
                                editable &&
                                state.save !is SequenceTemplateEditorSave.Committed &&
                                !state.hasInvalidInput(draft),
                    ) {
                        val label =
                            when {
                                recoveryPending -> R.string.sequence_editor_retry
                                state.save is SequenceTemplateEditorSave.Saving -> R.string.sequence_editor_saving
                                else -> R.string.sequence_editor_done
                            }
                        Text(
                            stringResource(label),
                        )
                    }
                }
            }
        }
    }
    pickerOffer?.let { offer ->
        if (visible && formEditable && controller.ownsStepInsertion(offer)) {
            key(offer) {
                SequenceAddStepPicker(offer, state, controller, nodeContexts[offer.parent]) {
                    pickerOffer =
                        null
                }
            }
        } else {
            LaunchedEffect(offer) {
                controller.dismissStepInsertion(offer)
                pickerOffer = null
            }
        }
    }
}

@Composable
private fun SequenceSettings(
    draft: SequenceTemplateDraft,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) = SequenceCard {
    val actions = LocalSequenceEditorActions.current
    SequenceEditorHeading(stringResource(R.string.sequence_editor_settings))
    ToggleRow(
        R.string.sequence_editor_no_live_pause,
        draft.noLiveTimeAccounting == NoLiveTimeAccounting.PAUSE,
        editable,
    ) {
        actions.update {
            it.copy(
                noLiveTimeAccounting =
                    if (it.noLiveTimeAccounting ==
                        NoLiveTimeAccounting.PAUSE
                    ) {
                        NoLiveTimeAccounting.ACTIVE
                    } else {
                        NoLiveTimeAccounting.PAUSE
                    },
            )
        }
    }
    ToggleRow(R.string.sequence_editor_auto_advance, draft.settings.autoAdvance, editable) {
        actions.update { it.copy(settings = it.settings.copy(autoAdvance = !it.settings.autoAdvance)) }
    }
    ToggleRow(R.string.sequence_editor_transition_sound, draft.settings.transitionSound, editable) {
        actions.update { it.copy(settings = it.settings.copy(transitionSound = !it.settings.transitionSound)) }
    }
    ToggleRow(R.string.sequence_editor_transition_vibration, draft.settings.transitionVibration, editable) {
        actions.update {
            it.copy(settings = it.settings.copy(transitionVibration = !it.settings.transitionVibration))
        }
    }
    ToggleRow(R.string.sequence_editor_keep_awake, draft.settings.keepScreenAwake, editable) {
        actions.update { it.copy(settings = it.settings.copy(keepScreenAwake = !it.settings.keepScreenAwake)) }
    }
    ToggleRow(R.string.sequence_editor_confirm_jump, draft.settings.confirmJump, editable) {
        actions.update { it.copy(settings = it.settings.copy(confirmJump = !it.settings.confirmJump)) }
    }
    ToggleRow(R.string.sequence_editor_confirm_early_end, draft.settings.confirmEarlyEnd, editable) {
        actions.update { it.copy(settings = it.settings.copy(confirmEarlyEnd = !it.settings.confirmEarlyEnd)) }
    }
    DurationField(
        R.string.sequence_editor_start_countdown,
        SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN,
        draft.settings.sequenceStartCountdown,
        state,
        controller,
        editable,
        actions = actions,
    ) { value ->
        actions.update {
            it.copy(settings = it.settings.copy(sequenceStartCountdown = value))
        }
    }
    DurationField(
        R.string.sequence_editor_before_step_countdown,
        SequenceEditorInputKey.BEFORE_STEP_COUNTDOWN,
        draft.settings.beforeEachStepCountdown,
        state,
        controller,
        editable,
        actions = actions,
    ) { value ->
        actions.update {
            it.copy(settings = it.settings.copy(beforeEachStepCountdown = value))
        }
    }
}

@Composable
private fun AddTopLevel(
    draft: SequenceTemplateDraft,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
    openPicker: () -> Unit,
) = SequenceCard {
    val actions = LocalSequenceEditorActions.current
    LifeTracingSecondaryButton(onClick = openPicker, modifier = editorFocusTarget(), enabled = editable) {
        Text(stringResource(R.string.sequence_editor_add_step))
    }
    LifeTracingSecondaryButton(onClick = {
        actions.update {
            it.copy(
                nodes =
                    it.nodes +
                        SequenceNodeDraft.Repeat(
                            SequenceRepeatBlockDraft(controller.newKey("repeat"), it.nodes.size, 1, emptyList()),
                        ),
            )
        }
    }, enabled = editable) { Text(stringResource(R.string.sequence_editor_add_repeat)) }
}

@Composable
private fun RepeatHeader(
    repeat: SequenceRepeatBlockDraft,
    context: String,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
    draft: SequenceTemplateDraft,
    dropState: SequenceEditorDropState,
    autoScroll: (Float, Float, Long) -> Unit,
) = SequenceCard(selected = state.manipulation?.selected == repeat.identity) {
    Text(
        stringResource(R.string.sequence_editor_repeat_label, repeat.repeatCount),
        style = MaterialTheme.typography.titleSmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        if (state.manipulation != null) {
            ManipulationHandle(
                R.string.sequence_editor_move_repeat,
                repeat.identity,
                editable,
                controller::selectManipulation,
                draft,
                dropState,
                autoScroll,
            ) { destination -> controller.moveManipulation(repeat.identity, destination) }
        }
        SequenceNodeManipulationAction(repeat.identity, context, state, controller, editable)
    }
    val actions =
        rememberSequenceEditorActions(controller, state, normalModeOnly = true, available = {
            controller.state.value.readyDraft()?.nodes?.any {
                it is SequenceNodeDraft.Repeat &&
                    it.identity == repeat.identity
            } ==
                true
        })
    DurationField(
        R.string.sequence_editor_repeat_count,
        SequenceEditorInputKey.repeatCount(repeat.identity),
        Duration.ofSeconds(repeat.repeatCount.toLong()),
        state,
        controller,
        editable && state.manipulation == null,
        labelIsSeconds = false,
        actions = actions,
        context = context,
    ) { count ->
        actions.update {
            it.withRepeat(repeat.identity) { value -> value.copy(repeatCount = count.seconds.toInt()) }
        }
    }
}

@Composable
private fun AddStepAction(
    repeatCount: Int,
    context: String,
    editable: Boolean,
    openPicker: () -> Unit,
) = LifeTracingSecondaryButton(
    onClick = openPicker,
    modifier =
        editorFocusTarget(stringResource(R.string.sequence_editor_add_step_to_repeat, context))
            .padding(start = 16.dp)
            .fillMaxWidth(),
    enabled = editable,
) {
    Text(
        stringResource(
            R.string.sequence_editor_add_step_to_repeat,
            stringResource(R.string.sequence_editor_repeat_label, repeatCount),
        ),
    )
}

@Composable
@Suppress("CyclomaticComplexMethod") // One expanded Step surface keeps all controls on the same draft boundary.
private fun StepCard(
    step: ActivityStepDraft,
    context: String,
    expandedSteps: MutableState<Set<String>>,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
    repeatCount: Int?,
    choice: SequenceEditorActivityChoice?,
    editable: Boolean,
    draft: SequenceTemplateDraft,
    dropState: SequenceEditorDropState,
    autoScroll: (Float, Float, Long) -> Unit,
) {
    val stepKey = step.identity.editorKey()
    val expanded = stepKey in expandedSteps.value
    val disclosureEpoch = remember(step.identity) { mutableStateOf(0L) }
    val epoch = disclosureEpoch.value
    val disclosureActions =
        key(epoch) {
            rememberSequenceEditorActions(controller, state, available = {
                controller.presentationEditable() &&
                    disclosureEpoch.value == epoch &&
                    controller.state.value
                        .presentation()
                        ?.steps
                        ?.containsKey(step.identity) == true
            })
        }
    val actions = LocalSequenceEditorActions.current
    val contentLease =
        key(epoch) {
            rememberSequenceEditorActions(controller, state, available = {
                disclosureEpoch.value == epoch &&
                    stepKey in expandedSteps.value &&
                    controller.state.value
                        .presentation()
                        ?.steps
                        ?.containsKey(step.identity) == true
            })
        }
    val stepActions =
        remember(controller, contentLease, step.identity) {
            SequenceStepActions(controller, contentLease, step.identity)
        }
    val focus = LocalEditorFocus.current
    LaunchedEffect(expanded, step.activity) { focus.observeLogicalTargets() }
    val manipulation = state.manipulation
    val selected = manipulation?.selected == step.identity
    val expandedDescription = stringResource(R.string.sequence_editor_expanded)
    val collapsedDescription = stringResource(R.string.sequence_editor_collapsed)
    val shape = MaterialTheme.shapes.medium
    val modifier =
        (if (repeat == null) Modifier else Modifier.padding(start = 16.dp))
            .clip(shape)
            .pointerInput(step.identity, editable, actions, manipulation != null) {
                detectTapGestures(
                    onTap = {
                        if (editable &&
                            manipulation != null
                        ) {
                            actions.invoke { controller.selectManipulation(step.identity) }
                        }
                    },
                    onLongPress = { if (editable) actions.invoke { controller.enterManipulation(step.identity) } },
                )
            }.semantics { this.selected = selected }
    SequenceCard(modifier = modifier, selected = selected) {
        val pendingDuplicate = step.activity is StepActivityDraft.Duplicate
        val local =
            when (val activity = step.activity) {
                is StepActivityDraft.Existing -> activity.configuration
                is StepActivityDraft.Local -> activity.configuration
                is StepActivityDraft.Duplicate -> activity.configuration
                else -> null
            }
        val stepMode = local?.timeTrackingMode ?: choice?.timeTrackingMode
        val configurationState =
            stringResource(
                when (val activity = step.activity) {
                    is StepActivityDraft.Local -> R.string.sequence_step_local
                    is StepActivityDraft.Existing ->
                        if (activity.sourceTemplateId ==
                            null
                        ) {
                            R.string.sequence_step_local
                        } else {
                            R.string.sequence_step_linked
                        }
                    is StepActivityDraft.FromTemplate -> R.string.sequence_step_pending
                    is StepActivityDraft.Duplicate -> R.string.sequence_editor_pending_duplicate
                },
            )
        repeatCount?.let {
            Text(
                stringResource(
                    R.string.sequence_editor_repeat_child_label,
                    stringResource(R.string.sequence_editor_repeat_label, it),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            local?.name ?: choice?.name ?: stringResource(R.string.sequence_editor_unavailable_activity),
            modifier =
                Modifier.semantics {
                    heading()
                    contentDescription = context
                    stateDescription = configurationState
                    this.selected = selected
                },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(local?.compactFact() ?: choice?.compactFact().orEmpty(), style = MaterialTheme.typography.bodySmall)
        if (manipulation != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                ManipulationHandle(
                    R.string.sequence_editor_move_step,
                    step.identity,
                    editable,
                    controller::selectManipulation,
                    draft,
                    dropState,
                    autoScroll,
                ) { destination -> controller.moveManipulation(step.identity, destination) }
                if (step.identity is DraftIdentity.Existing && step.activity is StepActivityDraft.Existing) {
                    ManipulationHandle(
                        R.string.sequence_editor_duplicate_step,
                        step.identity,
                        editable,
                        controller::selectManipulation,
                        draft,
                        dropState,
                        autoScroll,
                        duplicate = true,
                    ) { destination -> controller.duplicateManipulation(step.identity, destination) }
                }
            }
        }
        if (pendingDuplicate) {
            SequenceNodeManipulationAction(step.identity, context, state, controller, editable)
            Text(stringResource(R.string.sequence_editor_pending_duplicate), style = MaterialTheme.typography.bodySmall)
            return@SequenceCard
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            SequenceNodeManipulationAction(step.identity, context, state, controller, editable)
            key(disclosureActions, editable) {
                CompositionLocalProvider(
                    LocalEditorTargetExists provides {
                        disclosureEpoch.value == epoch && stepActions.exists()
                    },
                ) {
                    LifeTracingSecondaryButton(
                        onClick = {
                            if (editable) {
                                disclosureActions.invoke {
                                    disclosureEpoch.value++
                                    expandedSteps.value =
                                        if (expanded) expandedSteps.value - stepKey else expandedSteps.value + stepKey
                                }
                            }
                        },
                        modifier =
                            editorFocusTarget(
                                stringResource(
                                    R.string.activity_editor_control_context,
                                    stringResource(
                                        if (expanded) {
                                            R.string.sequence_editor_close_step
                                        } else {
                                            R.string.sequence_editor_edit_step
                                        },
                                    ),
                                    context,
                                ),
                            ).semantics {
                                stateDescription = if (expanded) expandedDescription else collapsedDescription
                            },
                        enabled = editable,
                    ) {
                        Text(
                            stringResource(
                                if (expanded) {
                                    R.string.sequence_editor_close_step
                                } else {
                                    R.string.sequence_editor_edit_step
                                },
                            ),
                        )
                    }
                }
            }
        }
        if (!expanded) return@SequenceCard
        key(contentLease, editable) {
            CompositionLocalProvider(
                LocalSequenceEditorActions provides contentLease,
                LocalSequenceStepActions provides stepActions,
                LocalSequenceStepContext provides context,
                LocalEditorTargetExists provides {
                    disclosureEpoch.value == epoch && stepKey in expandedSteps.value && stepActions.exists()
                },
            ) {
                if (local != null) {
                    LocalActivityDetails(step, local, state, controller, editable)
                } else if (step.activity is StepActivityDraft.FromTemplate) {
                    Text(
                        stringResource(R.string.sequence_editor_pending_source_configuration),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val committedStepId = (step.identity as? DraftIdentity.Existing)?.id
                if (committedStepId != null) {
                    StepSourceActions(
                        committedStepId,
                        state,
                        controller,
                    )
                }
                OverrideToggle(
                    R.string.sequence_editor_override_sound,
                    step.overrides.timerEndSound,
                    editable,
                ) { value ->
                    contentLease.update {
                        it.updateStep(
                            step.identity,
                        ) { current -> current.copy(overrides = current.overrides.copy(timerEndSound = value)) }
                    }
                }
                OverrideToggle(
                    R.string.sequence_editor_override_vibration,
                    step.overrides.timerEndVibration,
                    editable,
                ) { value ->
                    contentLease.update {
                        it.updateStep(
                            step.identity,
                        ) { current -> current.copy(overrides = current.overrides.copy(timerEndVibration = value)) }
                    }
                }
                OverrideToggle(
                    R.string.sequence_editor_override_awake,
                    step.overrides.keepScreenAwake,
                    editable,
                ) { value ->
                    contentLease.update {
                        it.updateStep(
                            step.identity,
                        ) { current -> current.copy(overrides = current.overrides.copy(keepScreenAwake = value)) }
                    }
                }
                if (stepMode == TimeTrackingMode.TIMER) {
                    val timerOverrides =
                        rememberSequenceEditorActions(controller, state, available = {
                            stepActions.available() && stepActions.trackingMode() == TimeTrackingMode.TIMER
                        })
                    CompositionLocalProvider(
                        LocalSequenceEditorActions provides timerOverrides,
                        LocalSequenceStepContext provides
                            stepControlDescription(R.string.sequence_step_override_timer_behavior),
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                            ChoiceButton(selected = step.overrides.timerZeroBehavior == null, onClick = {
                                contentLease.update {
                                    it.updateStep(step.identity) { current ->
                                        current.copy(overrides = current.overrides.copy(timerZeroBehavior = null))
                                    }
                                }
                            }, enabled = editable) { Text(stringResource(R.string.sequence_editor_inherit)) }
                            TimerZeroBehavior.entries.forEach { behavior ->
                                ChoiceButton(selected = step.overrides.timerZeroBehavior == behavior, onClick = {
                                    contentLease.update {
                                        it.updateStep(step.identity) { current ->
                                            current.copy(
                                                overrides = current.overrides.copy(timerZeroBehavior = behavior),
                                            )
                                        }
                                    }
                                }, enabled = editable) { Text(stringResource(timerZeroBehaviorLabel(behavior))) }
                            }
                        }
                    }
                }
                DurationField(
                    R.string.sequence_editor_override_countdown,
                    SequenceEditorInputKey.stepCountdown(step.identity),
                    step.overrides.startCountdown ?: Duration.ZERO,
                    state,
                    controller,
                    editable,
                    inherited = step.overrides.startCountdown == null,
                ) { value ->
                    contentLease.update {
                        it.updateStep(
                            step.identity,
                        ) { current -> current.copy(overrides = current.overrides.copy(startCountdown = value)) }
                    }
                }
                if (step.overrides.startCountdown != null) {
                    LifeTracingSecondaryButton(
                        onClick = {
                            stepActions.invoke {
                                controller.clearNumberInput(SequenceEditorInputKey.stepCountdown(step.identity))
                                stepActions.step { current ->
                                    current.copy(overrides = current.overrides.copy(startCountdown = null))
                                }
                            }
                        },
                        modifier =
                            editorFocusTarget(
                                stepControlDescription(R.string.sequence_editor_inherit),
                            ),
                        enabled = editable,
                    ) {
                        Text(stringResource(R.string.sequence_editor_inherit))
                    }
                } else {
                    Text(stringResource(R.string.sequence_editor_inherit), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ManipulationHandle(
    description: Int,
    identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    enabled: Boolean,
    select: (DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>) -> Unit,
    draft: SequenceTemplateDraft,
    dropState: SequenceEditorDropState,
    autoScroll: (Float, Float, Long) -> Unit,
    duplicate: Boolean = false,
    commit: (SequenceDropDestination) -> Unit,
) {
    val label = stringResource(description)
    val currentDraft by rememberUpdatedState(draft)
    var coordinates by remember(identity, duplicate) { mutableStateOf<LayoutCoordinates?>(null) }
    Text(
        if (duplicate) "\u29C9\u2195" else "\u2195",
        modifier =
            Modifier
                .testTag("sequence-${if (duplicate) "duplicate" else "move"}-${identity.editorKey()}")
                .semantics { contentDescription = label }
                .onGloballyPositioned { coordinates = it }
                .pointerInput(identity, enabled, duplicate) {
                    var pointerY = 0f
                    detectDragGestures(
                        onDragStart = { position ->
                            if (enabled) {
                                select(identity)
                                dropState.start(currentDraft, identity, duplicate)
                                coordinates?.localToRoot(position)?.y?.let {
                                    pointerY = it
                                    dropState.update(it)
                                }
                            }
                        },
                        onDrag = { change, amount ->
                            if (enabled) {
                                change.consume()
                                pointerY += amount.y
                                dropState.update(pointerY)
                                autoScroll(pointerY, amount.y, change.uptimeMillis)
                            }
                        },
                        onDragEnd = {
                            if (enabled) dropState.finish()?.let(commit)
                        },
                        onDragCancel = dropState::cancel,
                    )
                }.padding(MaterialTheme.spacing.small),
    )
}

@Composable
private fun LocalActivityDetails(
    step: ActivityStepDraft,
    activity: ActivitySnapshotDraft,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) {
    val actions = LocalSequenceStepActions.current
    LifeTracingOutlinedTextField(
        value = activity.name,
        onValueChange = { name -> if (editable) actions.activity { it.copy(name = name) } },
        modifier = editorInputFocusTarget(stepControlDescription(R.string.sequence_editor_name)).fillMaxWidth(),
        label = { Text(stringResource(R.string.sequence_editor_name)) },
        enabled = editable,
    )
    LifeTracingOutlinedTextField(
        value = activity.shortComment.orEmpty(),
        onValueChange = { comment ->
            if (editable) actions.activity { it.copy(shortComment = comment.ifBlank { null }) }
        },
        modifier =
            editorInputFocusTarget(
                stepControlDescription(R.string.sequence_editor_short_comment),
            ).fillMaxWidth(),
        label = { Text(stringResource(R.string.sequence_editor_short_comment)) },
        enabled = editable,
    )
    SequenceEditorHeading(stringResource(R.string.activity_editor_tracking))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        TimeTrackingMode.entries.forEach { mode ->
            ChoiceButton(
                selected = activity.timeTrackingMode == mode,
                onClick = {
                    actions.invoke {
                        if (mode == TimeTrackingMode.TIMER && actions.configuration()?.timerTarget == null) {
                            controller.updateNumberInput(
                                SequenceEditorInputKey.timerTarget(step.identity),
                                "0",
                                0,
                                1,
                            ) {}
                        } else if (mode != TimeTrackingMode.TIMER) {
                            controller.clearNumberInput(SequenceEditorInputKey.timerTarget(step.identity))
                        }
                        actions.activity {
                            it.copy(
                                timeTrackingMode = mode,
                                timerTarget =
                                    if (mode ==
                                        TimeTrackingMode.TIMER
                                    ) {
                                        it.timerTarget
                                    } else {
                                        null
                                    },
                            )
                        }
                    }
                },
                enabled = editable,
                description =
                    stepControlDescription(
                        if (mode ==
                            TimeTrackingMode.NO_LIVE_TRACKING
                        ) {
                            R.string.expanded_sequence_no_live
                        } else {
                            timeTrackingModeLabel(mode)
                        },
                    ),
            ) { Text(stringResource(timeTrackingModeLabel(mode))) }
        }
    }
    if (activity.timeTrackingMode == TimeTrackingMode.TIMER) {
        val timerLease =
            rememberSequenceEditorActions(controller, state, available = {
                actions.available() && actions.configuration()?.timeTrackingMode == TimeTrackingMode.TIMER
            })
        CompositionLocalProvider(LocalSequenceEditorActions provides timerLease) {
            DurationField(
                R.string.activity_editor_timer_seconds,
                SequenceEditorInputKey.timerTarget(step.identity),
                activity.timerTarget ?: Duration.ZERO,
                state,
                controller,
                editable,
                minimum = 1,
            ) { target ->
                actions.activity {
                    it.copy(timerTarget = target)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                TimerZeroBehavior.entries.forEach { behavior ->
                    ChoiceButton(selected = activity.settings.timerZeroBehavior == behavior, onClick = {
                        actions.activity {
                            it.copy(settings = it.settings.copy(timerZeroBehavior = behavior))
                        }
                    }, enabled = editable) { Text(stringResource(timerZeroBehaviorLabel(behavior))) }
                }
            }
        }
    }
    ToggleRow(R.string.sequence_editor_local_show_seconds, activity.settings.showSeconds, editable) {
        actions.activity {
            it.copy(settings = it.settings.copy(showSeconds = !it.settings.showSeconds))
        }
    }
    ToggleRow(R.string.sequence_editor_local_timer_sound, activity.settings.timerEndSound, editable) {
        actions.activity {
            it.copy(settings = it.settings.copy(timerEndSound = !it.settings.timerEndSound))
        }
    }
    ToggleRow(R.string.sequence_editor_local_timer_vibration, activity.settings.timerEndVibration, editable) {
        actions.activity {
            it.copy(settings = it.settings.copy(timerEndVibration = !it.settings.timerEndVibration))
        }
    }
    ToggleRow(R.string.sequence_editor_local_keep_awake, activity.settings.keepScreenAwake, editable) {
        actions.activity {
            it.copy(settings = it.settings.copy(keepScreenAwake = !it.settings.keepScreenAwake))
        }
    }
    ToggleRow(R.string.sequence_editor_local_confirm_finish, activity.settings.confirmManualFinish, editable) {
        actions.activity {
            it.copy(settings = it.settings.copy(confirmManualFinish = !it.settings.confirmManualFinish))
        }
    }
    DurationField(
        R.string.sequence_editor_local_start_countdown,
        SequenceEditorInputKey.activityCountdown(step.identity),
        activity.settings.startCountdown,
        state,
        controller,
        editable,
    ) { countdown ->
        actions.activity {
            it.copy(settings = it.settings.copy(startCountdown = countdown))
        }
    }
    LocalActivityFields(step, activity, controller, editable)
}

@Composable internal fun ToggleRow(
    label: Int,
    checked: Boolean,
    enabled: Boolean,
    change: () -> Unit,
) = FlowRow {
    val description = stepControlDescription(label)
    val actions = LocalSequenceEditorActions.current
    Checkbox(
        checked = checked,
        onCheckedChange = { if (enabled) actions.invoke(change) },
        modifier = editorFocusTarget(description),
        enabled = enabled,
    )
    Text(stringResource(label), modifier = Modifier.padding(top = MaterialTheme.spacing.small))
}

@Composable private fun OverrideToggle(
    label: Int,
    value: Boolean?,
    enabled: Boolean,
    change: (Boolean?) -> Unit,
) {
    val description = stepControlDescription(label)
    CompositionLocalProvider(LocalSequenceStepContext provides description) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            Text(stringResource(label))
            ChoiceButton(selected = value == null, onClick = { change(null) }, enabled = enabled) {
                Text(stringResource(R.string.sequence_editor_inherit))
            }
            ChoiceButton(selected = value == true, onClick = { change(true) }, enabled = enabled) {
                Text(stringResource(R.string.sequence_editor_on))
            }
            ChoiceButton(selected = value == false, onClick = { change(false) }, enabled = enabled) {
                Text(stringResource(R.string.sequence_editor_off))
            }
        }
    }
}

@Composable
internal fun ChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    description: String? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val actions = LocalSequenceEditorActions.current
    val context = LocalSequenceStepContext.current
    val modifier =
        editorFocusTarget(description).semantics {
            stateDescription = context
            this.selected = selected
            role = Role.RadioButton
        }
    LifeTracingChoiceButton(selected, { if (enabled) actions.invoke(onClick) }, modifier, enabled, content)
}

@Composable private fun DurationField(
    label: Int,
    inputKey: String,
    duration: Duration,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    enabled: Boolean,
    labelIsSeconds: Boolean = true,
    minimum: Long = if (labelIsSeconds) 0 else 1,
    actions: SequenceTemplateEditorActions = LocalSequenceEditorActions.current,
    context: String = LocalSequenceStepContext.current,
    inherited: Boolean = false,
    change: (Duration) -> Unit,
) {
    val text = state.inputText(inputKey, if (inherited) "" else duration.seconds.toString())
    val invalid = state.inputIsInvalid(inputKey)
    LifeTracingOutlinedTextField(
        text,
        { value ->
            val update = {
                controller.updateNumberInput(
                    inputKey,
                    value,
                    duration.seconds,
                    minimum,
                    if (labelIsSeconds) Long.MAX_VALUE else Int.MAX_VALUE.toLong(),
                ) { change(Duration.ofSeconds(it)) }
            }
            if (enabled) actions.invoke(update)
        },
        editorInputFocusTarget(stepControlDescription(label, context)).fillMaxWidth(),
        label = { Text(stringResource(label)) },
        enabled = enabled,
        isError = invalid,
        supportingText =
            if (invalid) {
                (
                    {
                        Text(
                            stringResource(
                                when {
                                    label == R.string.activity_editor_timer_seconds ->
                                        R.string.activity_editor_timer_guidance
                                    !labelIsSeconds -> R.string.sequence_step_repeat_guidance
                                    else -> R.string.sequence_editor_invalid_number
                                },
                            ),
                        )
                    }
                )
            } else {
                null
            },
    )
}

@Composable private fun SequenceEditorPage(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    landingIndex: Int? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val focus = LocalEditorFocus.current
    if (landingIndex != null) {
        DisposableEffect(focus, state, landingIndex) {
            focus.revealLanding = { state.scrollToItem(landingIndex) }
            onDispose { focus.revealLanding = {} }
        }
    }
    LazyColumn(
        modifier = modifier.padding(MaterialTheme.spacing.xLarge),
        state = state,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        content = content,
    )
}

@Composable internal fun SequenceCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) = androidx.compose.material3.Card(
    modifier = modifier.fillMaxWidth(),
    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    colors =
        CardDefaults.cardColors(
            containerColor =
                if (selected) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
        ),
) {
    Column(
        modifier = Modifier.padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        content = content,
    )
}

private fun SequenceTemplateDraft.addTopLevelStep(
    identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    activity: StepActivityDraft,
) = copy(
    nodes =
        nodes + SequenceNodeDraft.Step(ActivityStepDraft(identity, nodes.size, activity)),
)

private sealed interface SequenceEditorRow {
    val key: String

    data class Step(
        val step: ActivityStepDraft,
        val repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
        val repeatCount: Int? = null,
    ) : SequenceEditorRow {
        override val key = "step:${step.identity.editorKey()}"
        val templateId = (step.activity as? StepActivityDraft.FromTemplate)?.templateId
    }

    data class RepeatHeader(
        val repeat: SequenceRepeatBlockDraft,
    ) : SequenceEditorRow {
        override val key = "repeat:${repeat.identity.editorKey()}"
    }

    data class RepeatAdd(
        val repeat: SequenceRepeatBlockDraft,
    ) : SequenceEditorRow {
        override val key = "repeat-add:${repeat.identity.editorKey()}"
    }

    data class TopEnd(
        val position: Int,
    ) : SequenceEditorRow {
        override val key = "top-end"
    }
}

private fun SequenceTemplateDraft.sequenceEditorRows(): List<SequenceEditorRow> =
    buildList {
        nodes.forEach { node ->
            when (node) {
                is SequenceNodeDraft.Step -> add(SequenceEditorRow.Step(node.value, null))
                is SequenceNodeDraft.Repeat -> {
                    add(SequenceEditorRow.RepeatHeader(node.value))
                    add(SequenceEditorRow.RepeatAdd(node.value))
                    node.value.children.forEach {
                        add(SequenceEditorRow.Step(it, node.identity, node.value.repeatCount))
                    }
                }
            }
        }
    }

@Composable
private fun SequenceDropTarget(
    row: SequenceEditorRow,
    state: SequenceEditorDropState,
    content: @Composable () -> Unit,
) {
    val owner = remember(row.key) { Any() }
    DisposableEffect(row.key, owner) { onDispose { state.remove(row.key, owner) } }
    Box(
        Modifier
            .fillMaxWidth()
            .testTag("sequence-drop-${row.key}")
            .onGloballyPositioned { state.put(row, it.boundsInRoot(), owner) },
    ) { content() }
}

private class SequenceEditorDropState {
    private val visible = mutableMapOf<String, SequenceEditorVisibleRow>()
    private var source: SequenceEditorDragSource? = null
    private var destination: SequenceDropDestination? = null
    private var lastAutoScrollAtMillis: Long? = null
    var viewport: Rect? = null
    var firstRowKey = ""
    var lastRowKey = ""
    var visibleRowKeys: () -> List<Any> = { emptyList() }

    fun put(
        row: SequenceEditorRow,
        bounds: Rect,
        owner: Any,
    ) {
        visible[row.key] = SequenceEditorVisibleRow(row, bounds, owner)
    }

    fun remove(
        key: String,
        owner: Any,
    ) {
        if (visible[key]?.owner === owner) visible.remove(key)
    }

    fun start(
        draft: SequenceTemplateDraft,
        identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
        duplicate: Boolean,
    ) {
        source = draft.dragSource(identity, duplicate)
        destination = null
        lastAutoScrollAtMillis = null
    }

    fun update(pointerY: Float) {
        val dragSource = source ?: return
        val visibleKeys = visibleRowKeys()
        val candidates = visible.values.filter { it.row.key in visibleKeys }
        if (candidates.isEmpty()) return
        val candidate =
            candidates.minBy { (_, bounds) ->
                when {
                    pointerY < bounds.top -> bounds.top - pointerY
                    pointerY > bounds.bottom -> pointerY - bounds.bottom
                    else -> 0f
                }
            }
        destination = candidate.row.destination(pointerY, candidate.bounds, dragSource)
    }

    fun finish(): SequenceDropDestination? = destination.also { this.clear() }

    fun cancel() = clear()

    fun autoScroll(
        pointerY: Float,
        movementY: Float,
        awayThreshold: Float,
        edge: Float,
        amount: Float,
        uptimeMillis: Long,
    ): Float {
        val bounds = viewport ?: return 0f
        val direction =
            when {
                pointerY < bounds.top + edge -> -1f
                pointerY > bounds.bottom - edge -> 1f
                else -> 0f
            }
        val movingAwayFromEdge =
            direction < 0f && movementY > awayThreshold || direction > 0f && movementY < -awayThreshold
        val boundaryKey = if (direction < 0) firstRowKey else lastRowKey
        val boundaryReached =
            visible[boundaryKey]
                ?.takeIf { boundaryKey in visibleRowKeys() }
                ?.let {
                    if (direction < 0) it.bounds.top >= bounds.top else it.bounds.bottom <= bounds.bottom
                } == true
        val rateLimited =
            lastAutoScrollAtMillis?.let { uptimeMillis - it < AUTO_SCROLL_INTERVAL_MILLIS } == true
        return when {
            direction == 0f -> 0f
            movingAwayFromEdge -> 0f
            boundaryReached -> 0f
            rateLimited -> 0f
            else -> {
                lastAutoScrollAtMillis = uptimeMillis
                direction * amount
            }
        }
    }

    private fun clear() {
        source = null
        destination = null
        lastAutoScrollAtMillis = null
    }
}

private data class SequenceEditorVisibleRow(
    val row: SequenceEditorRow,
    val bounds: Rect,
    val owner: Any,
)

private data class SequenceEditorDragSource(
    val repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
    val position: Int,
    val isRepeat: Boolean,
    val duplicate: Boolean,
) {
    fun at(
        repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
        boundary: Int,
    ) = SequenceDropDestination(
        repeat,
        boundary - if (!duplicate && this.repeat == repeat && position < boundary) 1 else 0,
    )
}

private fun SequenceTemplateDraft.dragSource(
    identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    duplicate: Boolean,
): SequenceEditorDragSource? =
    nodes.firstNotNullOfOrNull { node ->
        when {
            node.identity == identity ->
                SequenceEditorDragSource(null, node.position, node is SequenceNodeDraft.Repeat, duplicate)
            node is SequenceNodeDraft.Repeat ->
                node.value.children
                    .indexOfFirst { it.identity == identity }
                    .takeIf { it >= 0 }
                    ?.let { SequenceEditorDragSource(node.identity, it, false, duplicate) }
            else -> null
        }
    }

private fun SequenceEditorRow.destination(
    pointerY: Float,
    bounds: Rect,
    source: SequenceEditorDragSource,
): SequenceDropDestination? =
    if (source.isRepeat) {
        repeatDestination(pointerY, bounds)?.let { source.at(null, it) }
    } else {
        when (this) {
            is SequenceEditorRow.Step ->
                source.at(repeat, step.position + if (pointerY >= bounds.center.y) 1 else 0)
            is SequenceEditorRow.RepeatHeader ->
                if (source.repeat != null && source.repeat != repeat.identity) {
                    source.at(repeat.identity, 0)
                } else if (pointerY < bounds.top + bounds.height * REPEAT_HEADER_TOP_LEVEL_FRACTION) {
                    source.at(null, repeat.position)
                } else {
                    source.at(repeat.identity, 0)
                }
            is SequenceEditorRow.RepeatAdd -> source.at(repeat.identity, 0)
            is SequenceEditorRow.TopEnd -> source.at(null, position)
        }
    }

private fun SequenceEditorRow.repeatDestination(
    pointerY: Float,
    bounds: Rect,
): Int? =
    when (this) {
        is SequenceEditorRow.Step ->
            step.position
                .takeIf { repeat == null }
                ?.plus(if (pointerY >= bounds.center.y) 1 else 0)
        is SequenceEditorRow.RepeatHeader -> repeat.position + if (pointerY >= bounds.center.y) 1 else 0
        is SequenceEditorRow.RepeatAdd -> repeat.position + 1
        is SequenceEditorRow.TopEnd -> position
    }

private const val REPEAT_HEADER_TOP_LEVEL_FRACTION = 0.25f
private const val AUTO_SCROLL_INTERVAL_MILLIS = 16L

internal fun SequenceTemplateDraft.insertStep(
    parent: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
    identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    activity: StepActivityDraft,
): SequenceTemplateDraft =
    if (parent ==
        null
    ) {
        addTopLevelStep(identity, activity)
    } else {
        addRepeatStep(parent, identity, activity)
    }

@Composable
private fun ActivitySnapshotDraft.compactFact(): String {
    val mainValue = fields.singleOrNull { it.isMainValue && it.type == CustomFieldType.NUMBER }
    return compactActivityFact(
        timeTrackingMode,
        timerTarget,
        mainValue?.localNameOverride ?: mainValue?.nameAtCreation,
        mainValue?.unit,
        mainValue?.displayPrecision,
        mainValue?.defaultNumberScaled,
    )
}

@Composable
internal fun SequenceEditorActivityChoice.compactFact(): String =
    compactActivityFact(
        timeTrackingMode,
        timerTarget,
        mainValueName,
        mainValueUnit,
        mainValueDisplayPrecision,
        mainValueDefaultNumberScaled,
    )

@Composable
private fun compactActivityFact(
    mode: TimeTrackingMode,
    timerTarget: Duration?,
    mainValueName: String?,
    mainValueUnit: String?,
    mainValueDisplayPrecision: Int?,
    mainValueDefaultNumberScaled: Long?,
): String =
    when {
        mode == TimeTrackingMode.TIMER ->
            timerTarget?.let(::formatEditorDuration)
                ?: stringResource(R.string.activity_editor_timer)
        mainValueName != null -> {
            val value = formatLauncherNumber(mainValueDefaultNumberScaled, mainValueDisplayPrecision)
            listOf(value, mainValueUnit ?: mainValueName).filter(String::isNotBlank).joinToString(" ")
        }
        mode == TimeTrackingMode.STOPWATCH -> stringResource(R.string.activity_editor_stopwatch)
        else -> stringResource(R.string.sequence_editor_complete_only)
    }

@Suppress("MagicNumber")
private fun formatEditorDuration(duration: Duration): String {
    val seconds = duration.seconds
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val remainder = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%d:%02d".format(minutes, remainder)
}

private fun SequenceTemplateDraft.withRepeat(
    identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    transform: (SequenceRepeatBlockDraft) -> SequenceRepeatBlockDraft,
) = copy(
    nodes =
        nodes.map { node ->
            if (node is SequenceNodeDraft.Repeat &&
                node.identity == identity
            ) {
                SequenceNodeDraft.Repeat(transform(node.value))
            } else {
                node
            }
        },
)

private fun SequenceTemplateDraft.addRepeatStep(
    repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    activity: StepActivityDraft,
) = withRepeat(repeat) {
    it.copy(
        children =
            it.children + ActivityStepDraft(identity, it.children.size, activity),
    )
}

internal fun sequenceFieldTypeLabel(type: CustomFieldType) =
    when (type) {
        CustomFieldType.NUMBER -> R.string.activity_editor_number
        CustomFieldType.CATEGORY -> R.string.activity_editor_category
        CustomFieldType.TEXT -> R.string.activity_editor_text
    }

private fun timeTrackingModeLabel(mode: TimeTrackingMode) =
    when (mode) {
        TimeTrackingMode.STOPWATCH -> R.string.activity_editor_stopwatch
        TimeTrackingMode.TIMER -> R.string.activity_editor_timer
        TimeTrackingMode.NO_LIVE_TRACKING -> R.string.activity_editor_none
    }

private fun timerZeroBehaviorLabel(behavior: TimerZeroBehavior) =
    when (behavior) {
        TimerZeroBehavior.FINISH -> R.string.sequence_editor_timer_zero_finish
        TimerZeroBehavior.OVERTIME -> R.string.sequence_editor_timer_zero_overtime
    }

private const val SEQUENCE_RECOVERY_FOCUS_PHASE = 3
