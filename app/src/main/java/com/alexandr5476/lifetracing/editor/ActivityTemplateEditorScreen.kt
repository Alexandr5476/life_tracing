@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.launcher.parseLauncherNumber
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing

@Composable
fun ActivityTemplateEditorRoute(
    controller: ActivityTemplateEditorController,
    onCommitted: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    var committedDelivered by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(state.save) {
        if (state.save is ActivityTemplateEditorSave.Committed && !committedDelivered) {
            committedDelivered = true
            (onCommitted ?: onBack)()
        }
    }
    BackHandler { controller.requestBack(onBack) }
    ActivityTemplateEditorScreen(state, controller, onBack)
}

@Composable
internal fun ActivityTemplateEditorScreen(
    state: ActivityTemplateEditorState,
    controller: ActivityTemplateEditorController,
    onBack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        when (val load = state.load) {
            ActivityTemplateEditorLoad.Loading -> EditorPage { Text(stringResource(R.string.activity_editor_loading)) }
            is ActivityTemplateEditorLoad.Failure ->
                EditorPage {
                    Text(stringResource(R.string.activity_editor_load_failure))
                    LifeTracingPrimaryButton(
                        onClick = controller::retry,
                    ) { Text(stringResource(R.string.activity_editor_retry)) }
                    LifeTracingSecondaryButton(onClick = onBack) { Text(stringResource(R.string.activity_editor_back)) }
                }
            is ActivityTemplateEditorLoad.Ready -> EditorForm(load.draft, state, controller, onBack)
        }
    }
    if (state.discardConfirmationVisible) {
        AlertDialog(
            onDismissRequest = controller::dismissDiscard,
            title = { Text(stringResource(R.string.activity_editor_discard_title)) },
            text = { Text(stringResource(R.string.activity_editor_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = { controller.discard(onBack) },
                ) { Text(stringResource(R.string.activity_editor_discard)) }
            },
            dismissButton = {
                TextButton(
                    onClick = controller::dismissDiscard,
                ) { Text(stringResource(R.string.activity_editor_continue_editing)) }
            },
        )
    }
}

@Composable
private fun EditorForm(
    draft: ActivityTemplateDraft,
    state: ActivityTemplateEditorState,
    controller: ActivityTemplateEditorController,
    onBack: () -> Unit,
) = EditorPage {
    val editable = state.save !is ActivityTemplateEditorSave.Saving
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.activity_editor_title), style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = { controller.requestBack(onBack) }, enabled = editable) {
            Text(stringResource(R.string.activity_editor_back))
        }
    }
    LifeTracingOutlinedTextField(
        value = draft.name,
        onValueChange = { value -> controller.updateDraft { it.copy(name = value) } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_editor_name)) },
        enabled = editable,
    )
    LifeTracingOutlinedTextField(
        value = draft.shortComment.orEmpty(),
        onValueChange = { value -> controller.updateDraft { it.copy(shortComment = value.ifBlank { null }) } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_editor_short_comment)) },
        enabled = editable,
    )
    EditorCard {
        Text(stringResource(R.string.activity_editor_tracking), style = MaterialTheme.typography.titleMedium)
        TimeTrackingMode.entries.forEach { mode ->
            val label =
                stringResource(
                    when (mode) {
                        TimeTrackingMode.STOPWATCH -> R.string.activity_editor_stopwatch
                        TimeTrackingMode.TIMER -> R.string.activity_editor_timer
                        TimeTrackingMode.NO_LIVE_TRACKING -> R.string.activity_editor_none
                    },
                )
            if (draft.timeTrackingMode == mode) {
                LifeTracingPrimaryButton(
                    onClick = { controller.setTimeTrackingMode(mode) },
                    enabled = editable,
                ) { Text(label) }
            } else {
                LifeTracingSecondaryButton(
                    onClick = { controller.setTimeTrackingMode(mode) },
                    enabled = editable,
                ) { Text(label) }
            }
        }
        if (draft.timeTrackingMode == TimeTrackingMode.TIMER) {
            LifeTracingOutlinedTextField(
                value = state.timerTargetText,
                onValueChange = controller::setTimerTargetSeconds,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.activity_editor_timer_seconds)) },
                enabled = editable,
            )
            if (state.timerTargetError) {
                Text(
                    stringResource(R.string.activity_editor_timer_invalid),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    EditorCard {
        Text(stringResource(R.string.activity_editor_fields), style = MaterialTheme.typography.titleMedium)
        draft.fields.forEachIndexed { index, field -> FieldEditor(index, field, state, controller) }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            CustomFieldType.entries.forEach { type ->
                LifeTracingSecondaryButton(
                    onClick = {
                        controller.updateDraft {
                            it.copy(
                                fields =
                                    it.fields +
                                        newField(type, nextEditorPosition(it.fields.map(ActivityFieldDraft::position))),
                            )
                        }
                    },
                    enabled = editable,
                ) {
                    Text(stringResource(fieldTypeLabel(type)))
                }
            }
        }
    }
    when (val save = state.save) {
        is ActivityTemplateEditorSave.Failure ->
            EditorCard {
                val message =
                    if (save.isConflict) R.string.activity_editor_conflict else R.string.activity_editor_save_failure
                Text(
                    stringResource(message),
                    color = MaterialTheme.colorScheme.error,
                )
                if (save.isConflict) {
                    LifeTracingSecondaryButton(onClick = controller::retry) {
                        Text(stringResource(R.string.activity_editor_reload))
                    }
                }
            }
        else -> Unit
    }
    LifeTracingPrimaryButton(
        onClick = controller::save,
        modifier = Modifier.fillMaxWidth(),
        enabled =
            state.save !is ActivityTemplateEditorSave.Saving &&
                state.save !is ActivityTemplateEditorSave.Committed &&
                !state.timerTargetError &&
                state.invalidNumberFields.isEmpty(),
    ) {
        val label =
            if (state.save is ActivityTemplateEditorSave.Saving) {
                R.string.activity_editor_saving
            } else {
                R.string.activity_editor_done
            }
        Text(
            stringResource(label),
        )
    }
}

@Composable
private fun FieldEditor(
    index: Int,
    field: ActivityFieldDraft,
    state: ActivityTemplateEditorState,
    controller: ActivityTemplateEditorController,
) = EditorCard {
    val editable = state.save !is ActivityTemplateEditorSave.Saving
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.activity_editor_field), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = {
            controller.updateDraft {
                it.copy(
                    fields =
                        it.fields.filterIndexed { current, _ ->
                            current !=
                                index
                        },
                )
            }
        }, enabled = editable) {
            Text(stringResource(R.string.activity_editor_remove))
        }
    }
    LifeTracingOutlinedTextField(
        value = field.name,
        onValueChange = { value -> controller.updateDraft { it.withFieldAt(index) { field.copy(name = value) } } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_editor_field_name)) },
        enabled = editable,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        CustomFieldType.entries.forEach { type ->
            val selected = field.type == type
            val action = { controller.updateDraft { it.withFieldAt(index) { value -> value.copy(type = type) } } }
            if (selected) {
                LifeTracingPrimaryButton(
                    onClick = action,
                    enabled = editable,
                ) { Text(stringResource(fieldTypeLabel(type))) }
            } else {
                LifeTracingSecondaryButton(
                    onClick = action,
                    enabled = editable,
                ) { Text(stringResource(fieldTypeLabel(type))) }
            }
        }
    }
    when (field.type) {
        CustomFieldType.NUMBER -> NumberFieldEditor(index, field, state, controller)
        CustomFieldType.CATEGORY -> CategoryFieldEditor(index, field, state, controller)
        CustomFieldType.TEXT ->
            LifeTracingOutlinedTextField(
                value = field.defaultText.orEmpty(),
                onValueChange = { value ->
                    controller.updateDraft {
                        it.withFieldAt(
                            index,
                        ) { field.copy(defaultText = value.ifBlank { null }) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.activity_editor_default)) },
                enabled = editable,
            )
    }
}

@Composable
private fun NumberFieldEditor(
    index: Int,
    field: ActivityFieldDraft,
    state: ActivityTemplateEditorState,
    controller: ActivityTemplateEditorController,
) {
    val editable = state.save !is ActivityTemplateEditorSave.Saving
    LifeTracingOutlinedTextField(
        value = field.unit.orEmpty(),
        onValueChange = { unit ->
            controller.updateDraft { it.withFieldAt(index) { field.copy(unit = unit.ifBlank { null }) } }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_editor_unit)) },
        enabled = editable,
    )
    LifeTracingOutlinedTextField(
        value = field.displayPrecision?.toString().orEmpty(),
        onValueChange = { controller.setDisplayPrecision(field, it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_editor_precision)) },
        enabled = editable,
    )
    val key = field.identity.editorKey()
    LifeTracingOutlinedTextField(
        value =
            state.numberDefaultTexts[key] ?: formatLauncherNumber(
                field.defaultNumberScaled,
                field.displayPrecision,
            ),
        onValueChange = { controller.setNumberDefault(field, it, ::parseLauncherNumber) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_editor_default)) },
        enabled = editable,
    )
    if (key in
        state.invalidNumberFields
    ) {
        Text(stringResource(R.string.activity_editor_number_invalid), color = MaterialTheme.colorScheme.error)
    }
    Row {
        Checkbox(
            checked = field.isMainValue,
            onCheckedChange = { checked ->
                controller.updateDraft { draft ->
                    draft.copy(
                        fields =
                            draft.fields.mapIndexed { current, value ->
                                value.copy(
                                    isMainValue =
                                        current == index && checked,
                                )
                            },
                    )
                }
            },
            enabled = editable,
        )
        Text(stringResource(R.string.activity_editor_main_value))
    }
}

@Composable
private fun CategoryFieldEditor(
    index: Int,
    field: ActivityFieldDraft,
    state: ActivityTemplateEditorState,
    controller: ActivityTemplateEditorController,
) {
    val editable = state.save !is ActivityTemplateEditorSave.Saving
    field.categoryOptions.forEachIndexed { optionIndex, option ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LifeTracingOutlinedTextField(
                value = option.label,
                onValueChange = { value ->
                    controller.updateDraft { draft ->
                        draft.withFieldAt(index) { current ->
                            current.copy(
                                categoryOptions =
                                    current.categoryOptions.mapIndexed { currentIndex, candidate ->
                                        if (currentIndex == optionIndex) candidate.copy(label = value) else candidate
                                    },
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.activity_editor_option)) },
                enabled = editable,
            )
            TextButton(onClick = {
                controller.updateDraft { draft ->
                    draft.withFieldAt(index) { current ->
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
            }, enabled = editable) { Text(stringResource(R.string.activity_editor_remove)) }
        }
        LifeTracingSecondaryButton(onClick = {
            controller.updateDraft { draft ->
                draft.withFieldAt(index) {
                    it.copy(
                        defaultCategoryOption =
                            option.identity.takeUnless { identity -> identity == field.defaultCategoryOption },
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
    }
    LifeTracingSecondaryButton(onClick = {
        controller.updateDraft { draft ->
            draft.withFieldAt(index) { current ->
                val position = nextEditorPosition(current.categoryOptions.map(ActivityCategoryOptionDraft::position))
                current.copy(
                    categoryOptions =
                        current.categoryOptions +
                            ActivityCategoryOptionDraft(DraftIdentity.New("option-${System.nanoTime()}"), position, ""),
                )
            }
        }
    }, enabled = editable) { Text(stringResource(R.string.activity_editor_add_option)) }
}

@Composable
private fun EditorPage(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.xLarge),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        content = content,
    )

@Composable
private fun EditorCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            content = content,
        )
    }

private fun newField(
    type: CustomFieldType,
    position: Int,
) = ActivityFieldDraft(DraftIdentity.New("field-${System.nanoTime()}"), position, "", type)

internal fun nextEditorPosition(positions: Iterable<Int>): Int {
    val used = positions.toSet()
    val last = used.maxOrNull()
    if (last == null || last < Int.MAX_VALUE) return (last ?: -1) + 1
    return generateSequence(0) { value -> value.takeUnless { it == Int.MAX_VALUE }?.plus(1) }
        .first { it !in used }
}

private fun fieldTypeLabel(type: CustomFieldType) =
    when (type) {
        CustomFieldType.NUMBER -> R.string.activity_editor_number
        CustomFieldType.CATEGORY -> R.string.activity_editor_category
        CustomFieldType.TEXT -> R.string.activity_editor_text
    }
