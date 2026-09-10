@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "TooManyFunctions", "UnusedParameter")

package com.alexandr5476.lifetracing.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
) = SequenceEditorPage {
    val editable = state.save !is SequenceTemplateEditorSave.Saving
    item {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.sequence_editor_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = {
                controller.requestBack(onBack)
            }, enabled = editable) { Text(stringResource(R.string.sequence_editor_back)) }
        }
    }
    item {
        LifeTracingOutlinedTextField(
            value = draft.name,
            onValueChange = { name -> controller.updateDraft { it.copy(name = name) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.sequence_editor_name)) },
            enabled = editable,
        )
        LifeTracingOutlinedTextField(
            value = draft.shortComment.orEmpty(),
            onValueChange = { value -> controller.updateDraft { it.copy(shortComment = value.ifBlank { null }) } },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(stringResource(R.string.sequence_editor_short_comment))
            },
            enabled = editable,
        )
    }
    item { SequenceSettings(draft, controller, editable) }
    item { SequenceFields(draft, controller, editable) }
    item { Text(stringResource(R.string.sequence_editor_steps), style = MaterialTheme.typography.titleMedium) }
    items(draft.nodes, key = { it.identity.editorKey() }) { node ->
        when (node) {
            is SequenceNodeDraft.Step -> StepCard(node.value, controller, null, state.availableActivities, editable)
            is SequenceNodeDraft.Repeat -> RepeatCard(node.value, controller, state.availableActivities, editable)
        }
    }
    item { AddTopLevel(draft, controller, state.availableActivities, editable) }
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
        LifeTracingPrimaryButton(
            onClick = controller::save,
            modifier = Modifier.fillMaxWidth(),
            enabled =
                editable && state.save !is SequenceTemplateEditorSave.Committed,
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

@Composable
private fun SequenceSettings(
    draft: SequenceTemplateDraft,
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
        draft.settings.sequenceStartCountdown,
        editable,
    ) { value ->
        controller.updateDraft {
            it.copy(settings = it.settings.copy(sequenceStartCountdown = value))
        }
    }
    DurationField(
        R.string.sequence_editor_before_step_countdown,
        draft.settings.beforeEachStepCountdown,
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
                LifeTracingSecondaryButton(onClick = {
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
                            it.fields.filterIndexed { current, _ ->
                                current !=
                                    index
                            },
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
                                SequenceFieldDraft(controller.newKey("field"), it.fields.size, "", type).normalized(),
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
        LifeTracingSecondaryButton(onClick = {
            controller.updateDraft {
                it.withSequenceField(index) { current ->
                    current.copy(
                        defaultCategoryOption =
                            option.identity.takeUnless {
                                it ==
                                    current.defaultCategoryOption
                            },
                    )
                }
            }
        }, enabled = editable) {
            Text(
                stringResource(
                    if (field.defaultCategoryOption ==
                        option.identity
                    ) {
                        R.string.activity_editor_clear_default
                    } else {
                        R.string.activity_editor_make_default
                    },
                ),
            )
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
    choices: List<SequenceEditorActivityChoice>,
    editable: Boolean,
) = SequenceCard {
    Text(stringResource(R.string.sequence_editor_add_step), style = MaterialTheme.typography.titleMedium)
    choices.forEach { choice ->
        LifeTracingSecondaryButton(onClick = {
            controller.updateDraft {
                it.addTopLevelStep(controller.newKey("step"), StepActivityDraft.FromTemplate(choice.id))
            }
        }, enabled = editable) { Text(choice.name) }
    }
    LifeTracingSecondaryButton(onClick = {
        controller.updateDraft {
            it.addTopLevelStep(controller.newKey("step"), StepActivityDraft.Local(controller.newLocalActivityDraft()))
        }
    }, enabled = editable) { Text(stringResource(R.string.sequence_editor_add_local_step)) }
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
private fun RepeatCard(
    repeat: SequenceRepeatBlockDraft,
    controller: SequenceTemplateEditorController,
    choices: List<SequenceEditorActivityChoice>,
    editable: Boolean,
) = SequenceCard {
    DurationField(
        R.string.sequence_editor_repeat_count,
        Duration.ofSeconds(repeat.repeatCount.toLong()),
        editable,
        labelIsSeconds = false,
    ) { count ->
        controller.updateDraft {
            it.withRepeat(repeat.identity) { value -> value.copy(repeatCount = count.seconds.toInt()) }
        }
    }
    repeat.children.forEach { StepCard(it, controller, repeat.identity, choices, editable) }
    choices.forEach { choice ->
        LifeTracingSecondaryButton(onClick = {
            controller.updateDraft {
                it.addRepeatStep(repeat.identity, controller.newKey("step"), StepActivityDraft.FromTemplate(choice.id))
            }
        }, enabled = editable) { Text(choice.name) }
    }
    LifeTracingSecondaryButton(onClick = {
        controller.updateDraft {
            it.addRepeatStep(
                repeat.identity,
                controller.newKey("step"),
                StepActivityDraft.Local(controller.newLocalActivityDraft()),
            )
        }
    }, enabled = editable) { Text(stringResource(R.string.sequence_editor_add_local_step)) }
}

@Composable
private fun StepCard(
    step: ActivityStepDraft,
    controller: SequenceTemplateEditorController,
    repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
    choices: List<SequenceEditorActivityChoice>,
    editable: Boolean,
) = SequenceCard {
    val local =
        when (val activity = step.activity) {
            is StepActivityDraft.Existing -> activity.configuration
            is StepActivityDraft.Local -> activity.configuration
            else -> null
        }
    Text(
        if (local ==
            null
        ) {
            stringResource(R.string.sequence_editor_linked_step)
        } else {
            stringResource(R.string.sequence_editor_local_step)
        },
        style = MaterialTheme.typography.titleSmall,
    )
    if (local != null) {
        LocalActivityDetails(step, repeat, local, controller, editable)
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
    if (local?.timeTrackingMode == TimeTrackingMode.TIMER) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            LifeTracingSecondaryButton(onClick = {
                controller.updateDraft {
                    it.updateStep(step.identity, repeat) { current ->
                        current.copy(overrides = current.overrides.copy(timerZeroBehavior = null))
                    }
                }
            }, enabled = editable) { Text(stringResource(R.string.sequence_editor_inherit)) }
            TimerZeroBehavior.entries.forEach { behavior ->
                LifeTracingSecondaryButton(onClick = {
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
        step.overrides.startCountdown ?: Duration.ZERO,
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
            controller.updateDraft {
                it.updateStep(step.identity, repeat) { current ->
                    current.copy(overrides = current.overrides.copy(startCountdown = null))
                }
            }
        }, enabled = editable) { Text(stringResource(R.string.sequence_editor_inherit)) }
    }
}

@Composable
private fun LocalActivityDetails(
    step: ActivityStepDraft,
    repeat: DraftIdentity<com.alexandr5476.lifetracing.domain.SequenceNodeId>?,
    activity: ActivitySnapshotDraft,
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
            LifeTracingSecondaryButton(onClick = {
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
            activity.timerTarget ?: Duration.ZERO,
            editable,
        ) { target ->
            controller.updateStepActivity(step.identity, repeat) {
                it.copy(
                    timerTarget =
                        target.takeIf {
                            it >
                                Duration.ZERO
                        },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            TimerZeroBehavior.entries.forEach { behavior ->
                LifeTracingSecondaryButton(onClick = {
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
        activity.settings.startCountdown,
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
                    LifeTracingSecondaryButton(onClick = {
                        controller.updateStepActivity(step.identity, repeat) {
                            it.withSnapshotField(index) { value -> value.copy(type = type).normalized() }
                        }
                    }, enabled = editable) { Text(stringResource(sequenceFieldTypeLabel(type))) }
                }
            }
        }
        LocalSnapshotFieldDetails(step, repeat, index, field, controller, editable)
        LifeTracingSecondaryButton(onClick = {
            controller.updateStepActivity(step.identity, repeat) {
                it.copy(fields = it.fields.filterIndexed { current, _ -> current != index })
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
                                    it.fields.size,
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
                        it.withSnapshotField(index) { value -> value.copy(unit = unit.ifBlank { null }) }
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
                LifeTracingSecondaryButton(onClick = {
                    controller.updateStepActivity(step.identity, repeat) {
                        it.withSnapshotField(index) { current ->
                            current.copy(
                                defaultCategoryOption =
                                    option.identity.takeUnless {
                                        it ==
                                            current.defaultCategoryOption
                                    },
                            )
                        }
                    }
                }, enabled = editable) { Text(stringResource(R.string.activity_editor_default)) }
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
) = FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
    Text(stringResource(label))
    LifeTracingSecondaryButton(onClick = { change(null) }, enabled = enabled) {
        Text(stringResource(R.string.sequence_editor_inherit))
    }
    LifeTracingSecondaryButton(onClick = { change(true) }, enabled = enabled) {
        Text(stringResource(R.string.sequence_editor_on))
    }
    LifeTracingSecondaryButton(onClick = { change(false) }, enabled = enabled) {
        Text(stringResource(R.string.sequence_editor_off))
    }
}

@Composable private fun DurationField(
    label: Int,
    duration: Duration,
    enabled: Boolean,
    labelIsSeconds: Boolean = true,
    change: (Duration) -> Unit,
) {
    var text by remember(duration) {
        mutableStateOf(if (labelIsSeconds) duration.seconds.toString() else duration.seconds.toString())
    }
    LifeTracingOutlinedTextField(text, { value ->
        text =
            value
        ; value.toLongOrNull()?.takeIf { it >= if (labelIsSeconds) 0 else 1 }?.let { change(Duration.ofSeconds(it)) }
    }, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, enabled = enabled)
}

@Composable private fun SequenceEditorPage(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) =
    LazyColumn(
        modifier = Modifier.padding(MaterialTheme.spacing.xLarge),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        content = content,
    )

@Composable private fun SequenceCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
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
