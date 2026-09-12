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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.SequenceCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.launcher.parseLauncherNumber
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.time.Duration

@Composable
fun SequenceTemplateEditorRoute(
    controller: SequenceTemplateEditorController,
    onCommitted: (() -> Unit)? = null,
    onApplied: (() -> Unit)? = null,
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
        if (state.appliedGeneration > 0) onApplied?.invoke()
    }
    BackHandler { controller.requestBack(onBack) }
    SequenceTemplateEditorScreen(state, controller, onBack)
}

@Composable
internal fun SequenceTemplateEditorScreen(
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    onBack: () -> Unit,
) {
    when (val load = state.load) {
        SequenceTemplateEditorLoad.Loading ->
            SequenceEditorPage {
                item { Text(stringResource(R.string.sequence_editor_loading)) }
            }
        is SequenceTemplateEditorLoad.Failure ->
            SequenceEditorPage {
                item {
                    Text(stringResource(R.string.sequence_editor_load_failure))
                    LifeTracingPrimaryButton(
                        onClick = controller::retry,
                    ) { Text(stringResource(R.string.sequence_editor_retry)) }
                    LifeTracingSecondaryButton(onClick = onBack) { Text(stringResource(R.string.sequence_editor_back)) }
                }
            }
        is SequenceTemplateEditorLoad.Ready -> SequenceEditorForm(load.draft, state, controller, onBack)
    }
    if (state.discardConfirmationVisible) {
        AlertDialog(
            onDismissRequest = controller::dismissDiscard,
            title = { Text(stringResource(R.string.sequence_editor_discard_title)) },
            text = { Text(stringResource(R.string.sequence_editor_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = { controller.discard(onBack) },
                ) { Text(stringResource(R.string.sequence_editor_discard)) }
            },
            dismissButton = {
                TextButton(
                    onClick = controller::dismissDiscard,
                ) { Text(stringResource(R.string.sequence_editor_continue)) }
            },
        )
    }
}

@Composable
private fun SequenceEditorForm(
    draft: SequenceTemplateDraft,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    onBack: () -> Unit,
) {
    val editable = state.save !is SequenceTemplateEditorSave.Saving
    val formEditable = editable && state.manipulation == null
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf<SequencePickerTarget?>(null) }
    val choices = remember(state.availableActivities) { state.availableActivities.associateBy { it.id } }
    val rows = remember(draft.nodes) { draft.sequenceEditorRows() }
    val topEnd = remember(draft.nodes.size) { SequenceEditorRow.TopEnd(draft.nodes.size) }
    val dropState = remember { SequenceEditorDropState() }
    val listState = rememberLazyListState()
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val manipulation = state.manipulation
                if (manipulation == null) {
                    Text(stringResource(R.string.sequence_editor_title), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = { controller.requestBack(onBack) }, enabled = editable) {
                        Text(stringResource(R.string.sequence_editor_back))
                    }
                } else {
                    val cancel = stringResource(R.string.sequence_editor_manipulation_cancel)
                    TextButton(
                        onClick = controller::discardManipulation,
                        modifier = Modifier.semantics { contentDescription = cancel },
                        enabled = editable,
                    ) {
                        Text("×")
                    }
                    TextButton(
                        onClick = { controller.undoManipulation() },
                        enabled = editable && manipulation.canUndo,
                    ) {
                        Text(stringResource(R.string.sequence_editor_undo))
                    }
                    TextButton(
                        onClick = { controller.redoManipulation() },
                        enabled = editable && manipulation.canRedo,
                    ) {
                        Text(stringResource(R.string.sequence_editor_redo))
                    }
                    TextButton(
                        onClick = controller::applyManipulation,
                        enabled = editable && !state.hasInvalidInput(draft),
                    ) { Text(stringResource(R.string.sequence_editor_apply)) }
                }
            }
        }
        item {
            LifeTracingOutlinedTextField(
                value = draft.name,
                onValueChange = { name -> controller.updateDraft { it.copy(name = name) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sequence_editor_name)) },
                enabled = formEditable,
            )
            LifeTracingOutlinedTextField(
                value = draft.shortComment.orEmpty(),
                onValueChange = { value -> controller.updateDraft { it.copy(shortComment = value.ifBlank { null }) } },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(R.string.sequence_editor_short_comment))
                },
                enabled = formEditable,
            )
        }
        item {
            LifeTracingSecondaryButton(
                onClick = { settingsExpanded = !settingsExpanded },
                enabled = formEditable,
            ) { Text(stringResource(R.string.sequence_editor_settings)) }
        }
        if (settingsExpanded) item { SequenceSettings(draft, state, controller, formEditable) }
        item { SequenceFields(draft, controller, formEditable) }
        item { Text(stringResource(R.string.sequence_editor_steps), style = MaterialTheme.typography.titleMedium) }
        items(rows, key = SequenceEditorRow::key) { row ->
            SequenceDropTarget(row, dropState) {
                when (row) {
                    is SequenceEditorRow.Step ->
                        StepCard(
                            row.step,
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
                        RepeatHeader(row.repeat, state, controller, editable, draft, dropState, autoScroll)
                    is SequenceEditorRow.RepeatAdd ->
                        AddStepAction(row.repeat.repeatCount, formEditable) {
                            pickerTarget = SequencePickerTarget.Repeat(row.repeat.identity)
                        }
                    is SequenceEditorRow.TopEnd -> Unit
                }
            }
        }
        item(key = topEnd.key) {
            SequenceDropTarget(topEnd, dropState) {
                AddTopLevel(draft, controller, formEditable) { pickerTarget = SequencePickerTarget.TopLevel }
            }
        }
        item {
            if (state.save is SequenceTemplateEditorSave.Failure) {
                val message =
                    if (state.save.isConflict) {
                        R.string.sequence_editor_conflict
                    } else {
                        R.string.sequence_editor_save_failure
                    }
                Text(
                    stringResource(message),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.manipulation == null) {
                LifeTracingPrimaryButton(
                    onClick = controller::save,
                    modifier = Modifier.fillMaxWidth(),
                    enabled =
                        editable &&
                            state.save !is SequenceTemplateEditorSave.Committed &&
                            !state.hasInvalidInput(draft),
                ) {
                    val label =
                        if (state.save is SequenceTemplateEditorSave.Saving) {
                            R.string.sequence_editor_saving
                        } else {
                            R.string.sequence_editor_done
                        }
                    Text(
                        stringResource(label),
                    )
                }
            }
        }
    }
    pickerTarget?.let { target ->
        AddStepPicker(
            state.availableActivities,
            formEditable,
            onDismiss = { pickerTarget = null },
            onSelect = { activity ->
                controller.updateDraft { it.insertStep(target, controller.newKey("step"), activity) }
                pickerTarget = null
            },
        )
    }
}

@Composable
private fun SequenceSettings(
    draft: SequenceTemplateDraft,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) = SequenceCard {
    Text(stringResource(R.string.sequence_editor_settings), style = MaterialTheme.typography.titleMedium)
    ToggleRow(
        R.string.sequence_editor_no_live_pause,
        draft.noLiveTimeAccounting == NoLiveTimeAccounting.PAUSE,
        editable,
    ) {
        controller.updateDraft {
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
        controller.updateDraft { it.copy(settings = it.settings.copy(autoAdvance = !it.settings.autoAdvance)) }
    }
    ToggleRow(R.string.sequence_editor_transition_sound, draft.settings.transitionSound, editable) {
        controller.updateDraft { it.copy(settings = it.settings.copy(transitionSound = !it.settings.transitionSound)) }
    }
    ToggleRow(R.string.sequence_editor_transition_vibration, draft.settings.transitionVibration, editable) {
        controller.updateDraft {
            it.copy(settings = it.settings.copy(transitionVibration = !it.settings.transitionVibration))
        }
    }
    ToggleRow(R.string.sequence_editor_keep_awake, draft.settings.keepScreenAwake, editable) {
        controller.updateDraft { it.copy(settings = it.settings.copy(keepScreenAwake = !it.settings.keepScreenAwake)) }
    }
    ToggleRow(R.string.sequence_editor_confirm_jump, draft.settings.confirmJump, editable) {
        controller.updateDraft { it.copy(settings = it.settings.copy(confirmJump = !it.settings.confirmJump)) }
    }
    ToggleRow(R.string.sequence_editor_confirm_early_end, draft.settings.confirmEarlyEnd, editable) {
        controller.updateDraft { it.copy(settings = it.settings.copy(confirmEarlyEnd = !it.settings.confirmEarlyEnd)) }
    }
    DurationField(
        R.string.sequence_editor_start_countdown,
        SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN,
        draft.settings.sequenceStartCountdown,
        state,
        controller,
        editable,
    ) { value ->
        controller.updateDraft {
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
    ) { value ->
        controller.updateDraft {
            it.copy(settings = it.settings.copy(beforeEachStepCountdown = value))
        }
    }
}

@Composable
private fun SequenceFields(
    draft: SequenceTemplateDraft,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) = SequenceCard {
    Text(stringResource(R.string.sequence_editor_fields), style = MaterialTheme.typography.titleMedium)
    draft.fields.forEachIndexed { index, field ->
        LifeTracingOutlinedTextField(field.name, { name ->
            controller.updateDraft {
                it.copy(
                    fields =
                        it.fields.mapIndexed { current, value ->
                            if (current ==
                                index
                            ) {
                                value.copy(name = name)
                            } else {
                                value
                            }
                        },
                )
            }
        }, Modifier.fillMaxWidth(), label = {
            Text(
                stringResource(R.string.sequence_editor_field_name),
            )
        }, enabled = editable)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            CustomFieldType.entries.forEach { type ->
                ChoiceButton(selected = field.type == type, onClick = {
                    controller.updateDraft {
                        it.copy(
                            fields =
                                it.fields.mapIndexed { current, value ->
                                    if (current ==
                                        index
                                    ) {
                                        value.copy(type = type).normalized()
                                    } else {
                                        value
                                    }
                                },
                        )
                    }
                }, enabled = editable) { Text(stringResource(sequenceFieldTypeLabel(type))) }
            }
            LifeTracingSecondaryButton(onClick = {
                controller.updateDraft {
                    it.copy(
                        fields =
                            it.fields
                                .filterIndexed { current, _ ->
                                    current !=
                                        index
                                }.mapIndexed { position, value -> value.copy(position = position) },
                    )
                }
            }, enabled = editable) { Text(stringResource(R.string.sequence_editor_remove)) }
        }
        if (field.type ==
            CustomFieldType.NUMBER
        ) {
            ToggleRow(R.string.sequence_editor_main_value, field.isMainValue, editable) {
                controller.updateDraft {
                    it.copy(
                        fields =
                            it.fields.mapIndexed { current, value ->
                                value.copy(
                                    isMainValue =
                                        current == index && !field.isMainValue,
                                )
                            },
                    )
                }
            }
        }
        SequenceFieldDetails(index, field, controller, editable)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        CustomFieldType.entries.forEach { type ->
            LifeTracingSecondaryButton(onClick = {
                controller.updateDraft {
                    it.copy(
                        fields =
                            it.fields +
                                SequenceFieldDraft(
                                    controller.newKey("field"),
                                    nextEditorPosition(it.fields.map(SequenceFieldDraft::position)),
                                    "",
                                    type,
                                ).normalized(),
                    )
                }
            }, enabled = editable) { Text(stringResource(sequenceFieldTypeLabel(type))) }
        }
    }
}

@Composable
private fun SequenceFieldDetails(
    index: Int,
    field: SequenceFieldDraft,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) {
    when (field.type) {
        CustomFieldType.NUMBER -> NumberSequenceFieldDetails(index, field, controller, editable)
        CustomFieldType.CATEGORY -> CategorySequenceFieldDetails(index, field, controller, editable)
        CustomFieldType.TEXT ->
            LifeTracingOutlinedTextField(
                value = field.defaultText.orEmpty(),
                onValueChange = { text ->
                    controller.updateDraft {
                        it.withSequenceField(index) { value -> value.copy(defaultText = text.ifBlank { null }) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.activity_editor_default)) },
                enabled = editable,
            )
    }
}

@Composable
private fun NumberSequenceFieldDetails(
    index: Int,
    field: SequenceFieldDraft,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) {
    LifeTracingOutlinedTextField(
        value = field.unit.orEmpty(),
        onValueChange = { unit ->
            controller.updateDraft { it.withSequenceField(index) { value -> value.copy(unit = unit.ifBlank { null }) } }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_editor_unit)) },
        enabled = editable,
    )
    LifeTracingOutlinedTextField(
        value = field.displayPrecision?.toString().orEmpty(),
        onValueChange = { text ->
            if (text.isBlank() || text.toIntOrNull() in 0..3) {
                controller.updateDraft {
                    it.withSequenceField(index) { value -> value.copy(displayPrecision = text.toIntOrNull()) }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_editor_precision)) },
        enabled = editable,
    )
    LifeTracingOutlinedTextField(
        value = formatLauncherNumber(field.defaultNumberScaled, field.displayPrecision),
        onValueChange = { text ->
            val number = text.takeUnless(String::isBlank)?.let { parseLauncherNumber(it, field.displayPrecision) }
            if (text.isBlank() || number != null) {
                controller.updateDraft {
                    it.withSequenceField(index) { value -> value.copy(defaultNumberScaled = number) }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_editor_default)) },
        enabled = editable,
    )
}

@Composable
private fun CategorySequenceFieldDetails(
    index: Int,
    field: SequenceFieldDraft,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) {
    ChoiceButton(
        selected = field.defaultCategoryOption == null,
        onClick = {
            controller.updateDraft {
                it.withSequenceField(index) { current -> current.copy(defaultCategoryOption = null) }
            }
        },
        enabled = editable,
    ) { Text(stringResource(R.string.sequence_editor_no_default)) }
    field.categoryOptions.forEachIndexed { optionIndex, option ->
        LifeTracingOutlinedTextField(
            value = option.label,
            onValueChange = { label ->
                controller.updateDraft {
                    it.withSequenceField(index) { current ->
                        current.copy(
                            categoryOptions =
                                current.categoryOptions.mapIndexed { currentIndex, candidate ->
                                    if (currentIndex == optionIndex) candidate.copy(label = label) else candidate
                                },
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.activity_editor_option)) },
            enabled = editable,
        )
        ChoiceButton(selected = field.defaultCategoryOption == option.identity, onClick = {
            controller.updateDraft {
                it.withSequenceField(index) { current ->
                    current.copy(defaultCategoryOption = option.identity)
                }
            }
        }, enabled = editable) {
            Text(stringResource(R.string.activity_editor_default))
        }
        LifeTracingSecondaryButton(onClick = {
            controller.updateDraft {
                it.withSequenceField(index) { current ->
                    val remaining =
                        current.categoryOptions.filterIndexed { currentIndex, _ ->
                            currentIndex !=
                                optionIndex
                        }
                    current.copy(
                        categoryOptions =
                            remaining.mapIndexed {
                                position,
                                candidate,
                                ->
                                candidate.copy(position = position)
                            },
                        defaultCategoryOption = current.defaultCategoryOption.takeIf { it != option.identity },
                    )
                }
            }
        }, enabled = editable) { Text(stringResource(R.string.sequence_editor_remove)) }
    }
    LifeTracingSecondaryButton(onClick = {
        controller.updateDraft {
            it.withSequenceField(index) { current ->
                current.copy(
                    categoryOptions =
                        current.categoryOptions +
                            SequenceCategoryOptionDraft(
                                controller.newKey("option"),
                                nextEditorPosition(current.categoryOptions.map(SequenceCategoryOptionDraft::position)),
                                "",
                            ),
                )
            }
        }
    }, enabled = editable) { Text(stringResource(R.string.activity_editor_add_option)) }
}

@Composable
private fun AddTopLevel(
    draft: SequenceTemplateDraft,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
    openPicker: () -> Unit,
) = SequenceCard {
    LifeTracingSecondaryButton(onClick = openPicker, enabled = editable) {
        Text(stringResource(R.string.sequence_editor_add_step))
    }
    LifeTracingSecondaryButton(onClick = {
        controller.updateDraft {
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
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
    draft: SequenceTemplateDraft,
    dropState: SequenceEditorDropState,
    autoScroll: (Float, Float, Long) -> Unit,
) = SequenceCard {
    Text(
        stringResource(R.string.sequence_editor_repeat_label, repeat.repeatCount),
        style = MaterialTheme.typography.titleSmall,
    )
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
    DurationField(
        R.string.sequence_editor_repeat_count,
        SequenceEditorInputKey.repeatCount(repeat.identity),
        Duration.ofSeconds(repeat.repeatCount.toLong()),
        state,
        controller,
        editable && state.manipulation == null,
        labelIsSeconds = false,
    ) { count ->
        controller.updateDraft {
            it.withRepeat(repeat.identity) { value -> value.copy(repeatCount = count.seconds.toInt()) }
        }
    }
}

@Composable
private fun AddStepAction(
    repeatCount: Int,
    editable: Boolean,
    openPicker: () -> Unit,
) = LifeTracingSecondaryButton(
    onClick = openPicker,
    modifier = Modifier.padding(start = 16.dp).fillMaxWidth(),
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
private fun AddStepPicker(
    choices: List<SequenceEditorActivityChoice>,
    editable: Boolean,
    onDismiss: () -> Unit,
    onSelect: (StepActivityDraft) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sequence_editor_pick_activity)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(choices, key = { it.id.value }) { choice ->
                    TextButton(
                        onClick = { onSelect(StepActivityDraft.FromTemplate(choice.id)) },
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(choice.name)
                            Text(choice.compactFact(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            onSelect(
                                StepActivityDraft.Local(
                                    ActivitySnapshotDraft("", null, TimeTrackingMode.STOPWATCH, null),
                                ),
                            )
                        },
                        enabled = editable,
                    ) {
                        Text(stringResource(R.string.sequence_editor_add_local_step))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sequence_editor_cancel)) }
        },
    )
}

@Composable
@Suppress("CyclomaticComplexMethod") // One expanded Step surface keeps all controls on the same draft boundary.
private fun StepCard(
    step: ActivityStepDraft,
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
    val manipulation = state.manipulation
    val selected = manipulation?.selected == step.identity
    val shape = MaterialTheme.shapes.medium
    val modifier =
        (if (repeat == null) Modifier else Modifier.padding(start = 16.dp))
            .clip(shape)
            .combinedClickable(
                enabled = editable,
                onClick = { if (manipulation != null) controller.selectManipulation(step.identity) },
                onLongClick = { controller.enterManipulation(step.identity) },
            ).semantics { this.selected = selected }
    SequenceCard(modifier = modifier, selected = selected) {
        var expanded by rememberSaveable(step.identity.editorKey()) { mutableStateOf(false) }
        val pendingDuplicate = step.activity is StepActivityDraft.Duplicate
        val local =
            when (val activity = step.activity) {
                is StepActivityDraft.Existing -> activity.configuration
                is StepActivityDraft.Local -> activity.configuration
                is StepActivityDraft.Duplicate -> manipulation?.duplicatePreviews?.get(activity.sourceStepId)
                else -> null
            }
        val stepMode = local?.timeTrackingMode ?: choice?.timeTrackingMode
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
            Text(stringResource(R.string.sequence_editor_pending_duplicate), style = MaterialTheme.typography.bodySmall)
            return@SequenceCard
        }
        LifeTracingSecondaryButton(onClick = { expanded = !expanded }, enabled = editable) {
            Text(
                stringResource(
                    if (expanded) R.string.sequence_editor_close_step else R.string.sequence_editor_edit_step,
                ),
            )
        }
        if (!expanded) return@SequenceCard
        if (local != null) {
            LocalActivityDetails(step, repeat, local, state, controller, editable)
        } else if (step.activity is StepActivityDraft.FromTemplate) {
            Text(
                stringResource(R.string.sequence_editor_pending_source_configuration),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OverrideToggle(
            R.string.sequence_editor_override_sound,
            step.overrides.timerEndSound,
            editable,
        ) { value ->
            controller.updateDraft {
                it.updateStep(
                    step.identity,
                    repeat,
                ) { current -> current.copy(overrides = current.overrides.copy(timerEndSound = value)) }
            }
        }
        OverrideToggle(
            R.string.sequence_editor_override_vibration,
            step.overrides.timerEndVibration,
            editable,
        ) { value ->
            controller.updateDraft {
                it.updateStep(
                    step.identity,
                    repeat,
                ) { current -> current.copy(overrides = current.overrides.copy(timerEndVibration = value)) }
            }
        }
        OverrideToggle(
            R.string.sequence_editor_override_awake,
            step.overrides.keepScreenAwake,
            editable,
        ) { value ->
            controller.updateDraft {
                it.updateStep(
                    step.identity,
                    repeat,
                ) { current -> current.copy(overrides = current.overrides.copy(keepScreenAwake = value)) }
            }
        }
        if (stepMode == TimeTrackingMode.TIMER) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                ChoiceButton(selected = step.overrides.timerZeroBehavior == null, onClick = {
                    controller.updateDraft {
                        it.updateStep(step.identity, repeat) { current ->
                            current.copy(overrides = current.overrides.copy(timerZeroBehavior = null))
                        }
                    }
                }, enabled = editable) { Text(stringResource(R.string.sequence_editor_inherit)) }
                TimerZeroBehavior.entries.forEach { behavior ->
                    ChoiceButton(selected = step.overrides.timerZeroBehavior == behavior, onClick = {
                        controller.updateDraft {
                            it.updateStep(step.identity, repeat) { current ->
                                current.copy(overrides = current.overrides.copy(timerZeroBehavior = behavior))
                            }
                        }
                    }, enabled = editable) { Text(stringResource(timerZeroBehaviorLabel(behavior))) }
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
        ) { value ->
            controller.updateDraft {
                it.updateStep(
                    step.identity,
                    repeat,
                ) { current -> current.copy(overrides = current.overrides.copy(startCountdown = value)) }
            }
        }
        if (step.overrides.startCountdown != null) {
            LifeTracingSecondaryButton(onClick = {
                controller.clearNumberInput(SequenceEditorInputKey.stepCountdown(step.identity))
                controller.updateDraft {
                    it.updateStep(step.identity, repeat) { current ->
                        current.copy(overrides = current.overrides.copy(startCountdown = null))
                    }
                }
            }, enabled = editable) { Text(stringResource(R.string.sequence_editor_inherit)) }
        } else {
            Text(stringResource(R.string.sequence_editor_inherit), style = MaterialTheme.typography.bodySmall)
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
    var coordinates by remember(identity, duplicate) { mutableStateOf<LayoutCoordinates?>(null) }
    Text(
        if (duplicate) "⧉↕" else "↕",
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
                                dropState.start(draft, identity, duplicate)
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
    repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
    activity: ActivitySnapshotDraft,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) {
    LifeTracingOutlinedTextField(
        value = activity.name,
        onValueChange = { name -> controller.updateStepActivity(step.identity, repeat) { it.copy(name = name) } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.sequence_editor_name)) },
        enabled = editable,
    )
    LifeTracingOutlinedTextField(
        value = activity.shortComment.orEmpty(),
        onValueChange = { comment ->
            controller.updateStepActivity(step.identity, repeat) { it.copy(shortComment = comment.ifBlank { null }) }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.sequence_editor_short_comment)) },
        enabled = editable,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        TimeTrackingMode.entries.forEach { mode ->
            ChoiceButton(selected = activity.timeTrackingMode == mode, onClick = {
                if (mode == TimeTrackingMode.TIMER && activity.timerTarget == null) {
                    controller.updateNumberInput(
                        SequenceEditorInputKey.timerTarget(step.identity),
                        "0",
                        0,
                        1,
                    ) {}
                } else if (mode != TimeTrackingMode.TIMER) {
                    controller.clearNumberInput(SequenceEditorInputKey.timerTarget(step.identity))
                }
                controller.updateStepActivity(step.identity, repeat) {
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
            }, enabled = editable) { Text(stringResource(timeTrackingModeLabel(mode))) }
        }
    }
    if (activity.timeTrackingMode == TimeTrackingMode.TIMER) {
        DurationField(
            R.string.activity_editor_timer_seconds,
            SequenceEditorInputKey.timerTarget(step.identity),
            activity.timerTarget ?: Duration.ZERO,
            state,
            controller,
            editable,
            minimum = 1,
        ) { target ->
            controller.updateStepActivity(step.identity, repeat) {
                it.copy(timerTarget = target)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            TimerZeroBehavior.entries.forEach { behavior ->
                ChoiceButton(selected = activity.settings.timerZeroBehavior == behavior, onClick = {
                    controller.updateStepActivity(step.identity, repeat) {
                        it.copy(settings = it.settings.copy(timerZeroBehavior = behavior))
                    }
                }, enabled = editable) { Text(stringResource(timerZeroBehaviorLabel(behavior))) }
            }
        }
    }
    ToggleRow(R.string.sequence_editor_local_show_seconds, activity.settings.showSeconds, editable) {
        controller.updateStepActivity(step.identity, repeat) {
            it.copy(settings = it.settings.copy(showSeconds = !it.settings.showSeconds))
        }
    }
    ToggleRow(R.string.sequence_editor_local_timer_sound, activity.settings.timerEndSound, editable) {
        controller.updateStepActivity(step.identity, repeat) {
            it.copy(settings = it.settings.copy(timerEndSound = !it.settings.timerEndSound))
        }
    }
    ToggleRow(R.string.sequence_editor_local_timer_vibration, activity.settings.timerEndVibration, editable) {
        controller.updateStepActivity(step.identity, repeat) {
            it.copy(settings = it.settings.copy(timerEndVibration = !it.settings.timerEndVibration))
        }
    }
    ToggleRow(R.string.sequence_editor_local_keep_awake, activity.settings.keepScreenAwake, editable) {
        controller.updateStepActivity(step.identity, repeat) {
            it.copy(settings = it.settings.copy(keepScreenAwake = !it.settings.keepScreenAwake))
        }
    }
    ToggleRow(R.string.sequence_editor_local_confirm_finish, activity.settings.confirmManualFinish, editable) {
        controller.updateStepActivity(step.identity, repeat) {
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
        controller.updateStepActivity(step.identity, repeat) {
            it.copy(settings = it.settings.copy(startCountdown = countdown))
        }
    }
    LocalActivityFields(step, repeat, activity, controller, editable)
}

@Composable
private fun LocalActivityFields(
    step: ActivityStepDraft,
    repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
    activity: ActivitySnapshotDraft,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) = SequenceCard {
    Text(stringResource(R.string.sequence_editor_local_fields), style = MaterialTheme.typography.titleSmall)
    activity.fields.forEachIndexed { index, field ->
        LifeTracingOutlinedTextField(
            value = field.localNameOverride ?: field.nameAtCreation,
            onValueChange = { name ->
                controller.updateStepActivity(step.identity, repeat) {
                    it.withSnapshotField(index) { current ->
                        if (current.sourceFieldId == null) {
                            current.copy(nameAtCreation = name)
                        } else {
                            current.copy(localNameOverride = name.ifBlank { null })
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.activity_editor_field_name)) },
            enabled = editable,
        )
        if (field.sourceFieldId == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                CustomFieldType.entries.forEach { type ->
                    ChoiceButton(selected = field.type == type, onClick = {
                        controller.updateStepActivity(step.identity, repeat) {
                            it.withSnapshotField(index) { value ->
                                value
                                    .withCompatibleTypeReplacement(
                                        type,
                                        controller.newKey("local-field-replacement"),
                                    ).normalized()
                            }
                        }
                    }, enabled = editable) { Text(stringResource(sequenceFieldTypeLabel(type))) }
                }
            }
        }
        LocalSnapshotFieldDetails(step, repeat, index, field, controller, editable)
        LifeTracingSecondaryButton(onClick = {
            controller.updateStepActivity(step.identity, repeat) {
                it.copy(
                    fields =
                        it.fields
                            .filterIndexed { current, _ -> current != index }
                            .mapIndexed { position, value -> value.copy(position = position) },
                )
            }
        }, enabled = editable) { Text(stringResource(R.string.sequence_editor_remove)) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        CustomFieldType.entries.forEach { type ->
            LifeTracingSecondaryButton(onClick = {
                controller.updateStepActivity(step.identity, repeat) {
                    it.copy(
                        fields =
                            it.fields +
                                ActivitySnapshotFieldDraft(
                                    controller.newKey("local-field"),
                                    null,
                                    nextEditorPosition(it.fields.map(ActivitySnapshotFieldDraft::position)),
                                    "",
                                    type = type,
                                ),
                    )
                }
            }, enabled = editable) { Text(stringResource(sequenceFieldTypeLabel(type))) }
        }
    }
}

@Composable
private fun LocalSnapshotFieldDetails(
    step: ActivityStepDraft,
    repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
    index: Int,
    field: ActivitySnapshotFieldDraft,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) {
    when (field.type) {
        CustomFieldType.NUMBER -> {
            LifeTracingOutlinedTextField(
                value = field.unit.orEmpty(),
                onValueChange = { unit ->
                    controller.updateStepActivity(step.identity, repeat) {
                        it.withSnapshotField(index) { value ->
                            value.withCompatibleUnitReplacement(
                                unit.ifBlank { null },
                                controller.newKey("local-field-replacement"),
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.activity_editor_unit)) },
                enabled = editable,
            )
            LifeTracingOutlinedTextField(
                value = field.displayPrecision?.toString().orEmpty(),
                onValueChange = { text ->
                    if (text.isBlank() || text.toIntOrNull() in 0..3) {
                        controller.updateStepActivity(step.identity, repeat) {
                            it.withSnapshotField(index) { value -> value.copy(displayPrecision = text.toIntOrNull()) }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(R.string.activity_editor_precision))
                },
                enabled = editable,
            )
            LifeTracingOutlinedTextField(
                value = formatLauncherNumber(field.defaultNumberScaled, field.displayPrecision),
                onValueChange = { text ->
                    val number =
                        text
                            .takeUnless(
                                String::isBlank,
                            )?.let { parseLauncherNumber(it, field.displayPrecision) }
                    if (text.isBlank() ||
                        number != null
                    ) {
                        controller.updateStepActivity(step.identity, repeat) {
                            it.withSnapshotField(index) { value -> value.copy(defaultNumberScaled = number) }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(R.string.activity_editor_default))
                },
                enabled = editable,
            )
            ToggleRow(R.string.sequence_editor_main_value, field.isMainValue, editable) {
                controller.updateStepActivity(step.identity, repeat) {
                    it.copy(
                        fields =
                            it.fields.mapIndexed { current, value ->
                                value.copy(
                                    isMainValue =
                                        current == index && !field.isMainValue,
                                )
                            },
                    )
                }
            }
        }
        CustomFieldType.CATEGORY -> {
            ChoiceButton(
                selected = field.defaultCategoryOption == null,
                onClick = {
                    controller.updateStepActivity(step.identity, repeat) {
                        it.withSnapshotField(index) { current -> current.copy(defaultCategoryOption = null) }
                    }
                },
                enabled = editable,
            ) { Text(stringResource(R.string.sequence_editor_no_default)) }
            field.categoryOptions.forEachIndexed { optionIndex, option ->
                LifeTracingOutlinedTextField(
                    value = option.localLabelOverride ?: option.labelAtCreation,
                    onValueChange = { label ->
                        controller.updateStepActivity(step.identity, repeat) {
                            it.withSnapshotField(index) { current ->
                                current.copy(
                                    categoryOptions =
                                        current.categoryOptions.mapIndexed { currentIndex, value ->
                                            if (currentIndex != optionIndex) {
                                                value
                                            } else if (value.sourceOptionId == null) {
                                                value.copy(labelAtCreation = label)
                                            } else {
                                                value.copy(localLabelOverride = label.ifBlank { null })
                                            }
                                        },
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.activity_editor_option)) },
                    enabled = editable,
                )
                ChoiceButton(selected = field.defaultCategoryOption == option.identity, onClick = {
                    controller.updateStepActivity(step.identity, repeat) {
                        it.withSnapshotField(index) { current ->
                            current.copy(defaultCategoryOption = option.identity)
                        }
                    }
                }, enabled = editable) { Text(stringResource(R.string.activity_editor_default)) }
                LifeTracingSecondaryButton(onClick = {
                    controller.updateStepActivity(step.identity, repeat) {
                        it.withSnapshotField(index) { current ->
                            current.copy(
                                categoryOptions =
                                    current.categoryOptions
                                        .filterIndexed { currentIndex, _ -> currentIndex != optionIndex }
                                        .mapIndexed { position, value -> value.copy(position = position) },
                                defaultCategoryOption =
                                    current.defaultCategoryOption.takeIf { it != option.identity },
                            )
                        }
                    }
                }, enabled = editable) { Text(stringResource(R.string.sequence_editor_remove)) }
            }
            LifeTracingSecondaryButton(onClick = {
                controller.updateStepActivity(step.identity, repeat) {
                    it.withSnapshotField(index) { current ->
                        current.copy(
                            categoryOptions =
                                current.categoryOptions +
                                    ActivitySnapshotCategoryOptionDraft(
                                        controller.newKey("local-option"),
                                        null,
                                        nextEditorPosition(
                                            current.categoryOptions.map(ActivitySnapshotCategoryOptionDraft::position),
                                        ),
                                        "",
                                    ),
                        )
                    }
                }
            }, enabled = editable) { Text(stringResource(R.string.activity_editor_add_option)) }
        }
        CustomFieldType.TEXT ->
            LifeTracingOutlinedTextField(
                value = field.defaultText.orEmpty(),
                onValueChange = { text ->
                    controller.updateStepActivity(step.identity, repeat) {
                        it.withSnapshotField(index) { value -> value.copy(defaultText = text.ifBlank { null }) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(stringResource(R.string.activity_editor_default))
                },
                enabled = editable,
            )
    }
}

@Composable private fun ToggleRow(
    label: Int,
    checked: Boolean,
    enabled: Boolean,
    change: () -> Unit,
) = Row {
    Checkbox(checked = checked, onCheckedChange = { change() }, enabled = enabled)
    Text(stringResource(label), modifier = Modifier.padding(top = MaterialTheme.spacing.small))
}

@Composable private fun OverrideToggle(
    label: Int,
    value: Boolean?,
    enabled: Boolean,
    change: (Boolean?) -> Unit,
) {
    val description = stringResource(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        Text(description)
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

@Composable
private fun ChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val modifier =
        Modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
        }
    if (selected) {
        LifeTracingPrimaryButton(onClick, modifier, enabled, content)
    } else {
        LifeTracingSecondaryButton(onClick, modifier, enabled, content)
    }
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
    change: (Duration) -> Unit,
) {
    val text = state.inputText(inputKey, duration.seconds.toString())
    val invalid = state.inputIsInvalid(inputKey)
    LifeTracingOutlinedTextField(
        text,
        { value ->
            controller.updateNumberInput(
                inputKey,
                value,
                duration.seconds,
                minimum,
                if (labelIsSeconds) Long.MAX_VALUE else Int.MAX_VALUE.toLong(),
            ) { change(Duration.ofSeconds(it)) }
        },
        Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        enabled = enabled,
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.sequence_editor_invalid_number)) }) else null,
    )
}

@Composable private fun SequenceEditorPage(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) = LazyColumn(
    modifier = modifier.padding(MaterialTheme.spacing.xLarge),
    state = state,
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
    content = content,
)

@Composable private fun SequenceCard(
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

private fun SequenceFieldDraft.normalized() =
    when (type) {
        CustomFieldType.NUMBER -> copy(defaultCategoryOption = null, defaultText = null, categoryOptions = emptyList())
        CustomFieldType.CATEGORY ->
            copy(
                unit = null,
                displayPrecision = null,
                defaultNumberScaled = null,
                defaultText = null,
                isMainValue = false,
            )
        CustomFieldType.TEXT ->
            copy(
                unit = null,
                displayPrecision = null,
                defaultNumberScaled = null,
                defaultCategoryOption = null,
                categoryOptions = emptyList(),
                isMainValue = false,
            )
    }

private fun SequenceTemplateDraft.addTopLevelStep(
    identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    activity: StepActivityDraft,
) = copy(
    nodes =
        nodes + SequenceNodeDraft.Step(ActivityStepDraft(identity, nodes.size, activity)),
)

private fun SequenceTemplateDraft.withSequenceField(
    index: Int,
    transform: (SequenceFieldDraft) -> SequenceFieldDraft,
) = copy(
    fields = fields.mapIndexed { current, field -> if (current == index) transform(field) else field },
)

private fun ActivitySnapshotDraft.withSnapshotField(
    index: Int,
    transform: (ActivitySnapshotFieldDraft) -> ActivitySnapshotFieldDraft,
) = copy(
    fields = fields.mapIndexed { current, field -> if (current == index) transform(field) else field },
)

private fun ActivitySnapshotFieldDraft.normalized() =
    when (type) {
        CustomFieldType.NUMBER -> copy(defaultCategoryOption = null, defaultText = null, categoryOptions = emptyList())
        CustomFieldType.CATEGORY ->
            copy(
                unit = null,
                displayPrecision = null,
                defaultNumberScaled = null,
                defaultText = null,
                isMainValue = false,
            )
        CustomFieldType.TEXT ->
            copy(
                unit = null,
                displayPrecision = null,
                defaultNumberScaled = null,
                defaultCategoryOption = null,
                categoryOptions = emptyList(),
                isMainValue = false,
            )
    }

private sealed interface SequencePickerTarget {
    data object TopLevel : SequencePickerTarget

    data class Repeat(
        val identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    ) : SequencePickerTarget
}

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

private fun SequenceTemplateDraft.insertStep(
    target: SequencePickerTarget,
    identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    activity: StepActivityDraft,
): SequenceTemplateDraft =
    when (target) {
        SequencePickerTarget.TopLevel -> addTopLevelStep(identity, activity)
        is SequencePickerTarget.Repeat -> addRepeatStep(target.identity, identity, activity)
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
private fun SequenceEditorActivityChoice.compactFact(): String =
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

private fun SequenceTemplateDraft.updateStep(
    identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
    transform: (ActivityStepDraft) -> ActivityStepDraft,
) = if (repeat ==
    null
) {
    copy(
        nodes =
            nodes.map { node ->
                if (node is SequenceNodeDraft.Step &&
                    node.identity == identity
                ) {
                    SequenceNodeDraft.Step(transform(node.value))
                } else {
                    node
                }
            },
    )
} else {
    withRepeat(repeat) { value ->
        value.copy(
            children =
                value.children.map {
                    if (it.identity ==
                        identity
                    ) {
                        transform(it)
                    } else {
                        it
                    }
                },
        )
    }
}

private fun StepActivityDraft.withConfiguration(transform: (ActivitySnapshotDraft) -> ActivitySnapshotDraft) =
    when (this) {
        is StepActivityDraft.Existing -> copy(configuration = transform(configuration))
        is StepActivityDraft.Local -> copy(configuration = transform(configuration))
        else -> this
    }

private fun SequenceTemplateEditorController.updateStepActivity(
    identity: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>,
    repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
    transform: (ActivitySnapshotDraft) -> ActivitySnapshotDraft,
) {
    updateDraft {
        it.updateStep(identity, repeat) { step ->
            step.copy(activity = step.activity.withConfiguration(transform))
        }
    }
}

private fun sequenceFieldTypeLabel(type: CustomFieldType) =
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
