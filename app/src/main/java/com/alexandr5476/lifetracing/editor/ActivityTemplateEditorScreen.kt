@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:Suppress(
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "LongMethod",
    "MagicNumber",
    "TooManyFunctions",
    "LongParameterList",
)

package com.alexandr5476.lifetracing.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing

@Composable
fun ActivityTemplateEditorRoute(
    controller: ActivityTemplateEditorController,
    onCommitted: (() -> Unit)? = null,
    isRouteVisible: Boolean = true,
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
    BackHandler(enabled = isRouteVisible) { controller.requestBack(onBack) }
    ActivityTemplateEditorScreen(state, controller, onBack, isRouteVisible)
}

@Composable
internal fun ActivityTemplateEditorScreen(
    state: ActivityTemplateEditorState,
    controller: ActivityTemplateEditorController,
    onBack: () -> Unit,
    isRouteVisible: Boolean = true,
) {
    val focus = rememberEditorFocus(state, isRouteVisible)
    val title =
        stringResource(
            if (controller.target is ActivityTemplateEditorTarget.New) {
                R.string.activity_editor_new_context
            } else {
                R.string.activity_editor_existing_context
            },
        )
    val ready = state.load as? ActivityTemplateEditorLoad.Ready
    val dirty =
        ready != null &&
            (
                ready.draft != ready.original ||
                    state.invalidNumberFields.isNotEmpty() ||
                    (state.timerTargetError && state.timerTargetText.isNotEmpty())
            )
    val draftDescription =
        stringResource(if (dirty) R.string.activity_editor_modified else R.string.activity_editor_pristine)
    CompositionLocalProvider(LocalEditorFocus provides focus, LocalEditorVisible provides isRouteVisible) {
        LifeTracingAccessibleTargets {
            Surface(
                modifier =
                    focus.root.semantics {
                        paneTitle = title
                        stateDescription = draftDescription
                        isTraversalGroup = true
                    },
                color = MaterialTheme.colorScheme.background,
            ) {
                key(state.generation) {
                    val actions = rememberEditorActions(controller, state)
                    when (val load = state.load) {
                        ActivityTemplateEditorLoad.Loading ->
                            EditorPage {
                                EditorHeading(title)
                                EditorStatus(R.string.activity_editor_loading)
                                EditorBack(controller, onBack, Modifier.focusRequester(focus.landing))
                            }
                        is ActivityTemplateEditorLoad.Failure ->
                            EditorPage {
                                EditorHeading(title)
                                EditorStatus(R.string.activity_editor_load_failure)
                                LifeTracingPrimaryButton(
                                    onClick = { actions.invoke(controller::retry) },
                                    modifier = editorFocusTarget().focusRequester(focus.landing),
                                ) { Text(stringResource(R.string.activity_editor_retry)) }
                                EditorBack(controller, onBack)
                            }
                        is ActivityTemplateEditorLoad.Ready ->
                            EditorForm(
                                load.draft,
                                state,
                                controller,
                                actions,
                                title,
                                onBack,
                            )
                    }
                }
            }
            if (state.discardConfirmationVisible && isRouteVisible) {
                EditorDiscard(controller, onBack, title, state)
            }
        }
    }
}

@Composable
private fun EditorBack(
    controller: ActivityTemplateEditorController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val visible = LocalEditorVisible.current
    TextButton(
        onClick = { if (visible) controller.requestBack(onBack) },
        modifier = editorFocusTarget().then(modifier),
        enabled = enabled,
    ) { Text(stringResource(R.string.activity_editor_back)) }
}

@Composable
private fun EditorForm(
    draft: ActivityTemplateDraft,
    state: ActivityTemplateEditorState,
    controller: ActivityTemplateEditorController,
    actions: ActivityTemplateEditorActions,
    title: String,
    onBack: () -> Unit,
) = EditorPage {
    val editable =
        state.save !is ActivityTemplateEditorSave.Saving && state.save !is ActivityTemplateEditorSave.Committed
    EditorHeading(title)
    EditorBack(controller, onBack, enabled = editable)
    LifeTracingOutlinedTextField(
        value = draft.name,
        onValueChange = { value -> actions.update { it.copy(name = value) } },
        modifier = editorInputFocusTarget().focusRequester(LocalEditorFocus.current.landing).fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_editor_name)) },
        enabled = editable,
    )
    LifeTracingOutlinedTextField(
        value = draft.shortComment.orEmpty(),
        onValueChange = { value -> actions.update { it.copy(shortComment = value.ifBlank { null }) } },
        modifier = editorInputFocusTarget().fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_editor_short_comment)) },
        enabled = editable,
    )
    EditorCard {
        EditorHeading(stringResource(R.string.activity_editor_tracking))
        TimeTrackingMode.entries.forEach { mode ->
            val label =
                stringResource(
                    when (mode) {
                        TimeTrackingMode.STOPWATCH -> R.string.activity_editor_stopwatch
                        TimeTrackingMode.TIMER -> R.string.activity_editor_timer
                        TimeTrackingMode.NO_LIVE_TRACKING -> R.string.activity_editor_none
                    },
                )
            LifeTracingChoiceButton(
                selected = draft.timeTrackingMode == mode,
                onClick = { actions.invoke { controller.setTimeTrackingMode(mode) } },
                modifier = editorFocusTarget().semantics { selected = draft.timeTrackingMode == mode },
                enabled = editable,
            ) { Text(label) }
        }
        if (draft.timeTrackingMode == TimeTrackingMode.TIMER) {
            LifeTracingOutlinedTextField(
                value = state.timerTargetText,
                onValueChange = { value -> actions.invoke { controller.setTimerTargetSeconds(value) } },
                modifier = editorInputFocusTarget().fillMaxWidth(),
                label = { Text(stringResource(R.string.activity_editor_timer_seconds)) },
                enabled = editable,
                isError = state.timerTargetError,
                supportingText = {
                    Text(
                        stringResource(
                            if (state.timerTargetError) {
                                R.string.activity_editor_timer_invalid
                            } else {
                                R.string.activity_editor_timer_guidance
                            },
                        ),
                    )
                },
            )
        }
    }
    EditorCard {
        EditorHeading(stringResource(R.string.activity_editor_fields))
        draft.fields.forEachIndexed { index, field ->
            key(field.identity) { FieldEditor(index, field, state, controller, actions, editable) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            CustomFieldType.entries.forEach { type ->
                val description =
                    stringResource(R.string.activity_editor_add_field_context, stringResource(fieldTypeLabel(type)))
                LifeTracingSecondaryButton(
                    onClick = {
                        actions.update {
                            it.copy(
                                fields =
                                    it.fields +
                                        newField(type, nextEditorPosition(it.fields.map(ActivityFieldDraft::position))),
                            )
                        }
                    },
                    modifier = editorFocusTarget(description),
                    enabled = editable,
                ) { Text(stringResource(fieldTypeLabel(type))) }
            }
        }
    }
    when (val save = state.save) {
        is ActivityTemplateEditorSave.Failure ->
            EditorCard {
                EditorStatus(
                    if (save.isConflict) R.string.activity_editor_conflict else R.string.activity_editor_save_failure,
                )
                if (save.isConflict) {
                    LifeTracingSecondaryButton(
                        onClick = { actions.invoke(controller::retry) },
                        modifier = editorFocusTarget(),
                    ) {
                        Text(stringResource(R.string.activity_editor_reload))
                    }
                }
            }
        ActivityTemplateEditorSave.Committed -> EditorStatus(R.string.activity_editor_committed)
        ActivityTemplateEditorSave.Saving -> EditorStatus(R.string.activity_editor_saving)
        ActivityTemplateEditorSave.Idle -> Unit
    }
    LifeTracingPrimaryButton(
        onClick = { actions.invoke(controller::save) },
        modifier = editorFocusTarget().fillMaxWidth(),
        enabled =
            editable &&
                !state.discardConfirmationVisible &&
                !state.timerTargetError &&
                state.invalidNumberFields.isEmpty(),
    ) {
        Text(
            stringResource(
                if (state.save is ActivityTemplateEditorSave.Saving) {
                    R.string.activity_editor_saving
                } else {
                    R.string.activity_editor_done
                },
            ),
        )
    }
}

@Composable
private fun FieldEditor(
    index: Int,
    field: ActivityFieldDraft,
    state: ActivityTemplateEditorState,
    controller: ActivityTemplateEditorController,
    actions: ActivityTemplateEditorActions,
    editable: Boolean,
) = EditorCard {
    val context =
        stringResource(
            R.string.activity_editor_field_context,
            stringResource(fieldTypeLabel(field.type)),
            index + 1,
            field.name,
        )
    EditorHeading(context)
    val remove = editorDescription(R.string.activity_editor_remove, context)
    TextButton(
        onClick = {
            actions.update {
                it.copy(
                    fields =
                        it.fields.filterNot { candidate ->
                            candidate.identity ==
                                field.identity
                        },
                )
            }
        },
        modifier = editorFocusTarget(remove),
        enabled = editable,
    ) { Text(stringResource(R.string.activity_editor_remove)) }
    EditorInput(field.name, R.string.activity_editor_field_name, context, editable) { value ->
        actions.field(field) { it.copy(name = value) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        CustomFieldType.entries.forEach { type ->
            val change =
                stringResource(
                    R.string.activity_editor_change_type_context,
                    stringResource(fieldTypeLabel(type)),
                    context,
                )
            LifeTracingChoiceButton(
                selected = field.type == type,
                onClick = { actions.field(field) { it.copy(type = type) } },
                modifier = editorFocusTarget(change).semantics { selected = field.type == type },
                enabled = editable,
            ) { Text(stringResource(fieldTypeLabel(type))) }
        }
    }
    when (field.type) {
        CustomFieldType.NUMBER -> NumberFieldEditor(field, context, state, controller, actions, editable)
        CustomFieldType.CATEGORY -> CategoryFieldEditor(field, context, actions, editable)
        CustomFieldType.TEXT ->
            EditorInput(field.defaultText.orEmpty(), R.string.activity_editor_default, context, editable) { value ->
                actions.field(field) { it.copy(defaultText = value.ifBlank { null }) }
            }
    }
}

@Composable
private fun NumberFieldEditor(
    field: ActivityFieldDraft,
    context: String,
    state: ActivityTemplateEditorState,
    controller: ActivityTemplateEditorController,
    actions: ActivityTemplateEditorActions,
    editable: Boolean,
) {
    EditorInput(field.unit.orEmpty(), R.string.activity_editor_unit, context, editable) { value ->
        actions.field(field) { it.copy(unit = value.ifBlank { null }) }
    }
    EditorInput(
        field.displayPrecision?.toString().orEmpty(),
        R.string.activity_editor_precision,
        context,
        editable,
        guidance = R.string.activity_editor_precision_guidance,
    ) { value -> actions.invoke { controller.setDisplayPrecision(field, value) } }
    val fieldKey = field.identity.editorKey()
    EditorInput(
        state.numberDefaultTexts[fieldKey] ?: formatLauncherNumber(field.defaultNumberScaled, field.displayPrecision),
        R.string.activity_editor_default,
        context,
        editable,
        invalid = fieldKey in state.invalidNumberFields,
        guidance = R.string.activity_editor_number_invalid.takeIf { fieldKey in state.invalidNumberFields },
    ) { value -> actions.invoke { controller.setNumberDefault(field, value, ::parseLauncherNumber) } }
    FlowRow {
        Checkbox(
            checked = field.isMainValue,
            onCheckedChange = { checked ->
                actions.update { draft ->
                    if (draft.fields.none { it.identity == field.identity && it.type == CustomFieldType.NUMBER }) {
                        draft
                    } else {
                        draft.copy(
                            fields =
                                draft.fields.map {
                                    it.copy(
                                        isMainValue =
                                            it.identity == field.identity && checked,
                                    )
                                },
                        )
                    }
                }
            },
            modifier = editorFocusTarget(editorDescription(R.string.activity_editor_main_value, context)),
            enabled = editable,
        )
        Text(stringResource(R.string.activity_editor_main_value))
    }
}

@Composable
private fun CategoryFieldEditor(
    field: ActivityFieldDraft,
    context: String,
    actions: ActivityTemplateEditorActions,
    editable: Boolean,
) {
    field.categoryOptions.forEachIndexed { ordinal, option ->
        key(option.identity) {
            val optionContext =
                stringResource(R.string.activity_editor_option_context, context, ordinal + 1, option.label)
            EditorInput(option.label, R.string.activity_editor_option, optionContext, editable) { value ->
                actions.field(field) { current ->
                    current.copy(
                        categoryOptions =
                            current.categoryOptions.map {
                                if (it.identity == option.identity) it.copy(label = value) else it
                            },
                    )
                }
            }
            TextButton(
                onClick = {
                    actions.field(field) { current ->
                        if (current.categoryOptions.none { it.identity == option.identity }) return@field current
                        current.copy(
                            categoryOptions =
                                current.categoryOptions
                                    .filterNot { it.identity == option.identity }
                                    .mapIndexed { position, candidate -> candidate.copy(position = position) },
                            defaultCategoryOption = current.defaultCategoryOption.takeUnless { it == option.identity },
                        )
                    }
                },
                modifier = editorFocusTarget(editorDescription(R.string.activity_editor_remove, optionContext)),
                enabled = editable,
            ) { Text(stringResource(R.string.activity_editor_remove)) }
            val selected = field.defaultCategoryOption == option.identity
            val label = if (selected) R.string.activity_editor_clear_default else R.string.activity_editor_make_default
            LifeTracingChoiceButton(
                selected = selected,
                onClick = {
                    actions.field(field) { current ->
                        if (current.categoryOptions.none { it.identity == option.identity }) {
                            current
                        } else {
                            current.copy(
                                defaultCategoryOption =
                                    option.identity.takeUnless {
                                        it ==
                                            current.defaultCategoryOption
                                    },
                            )
                        }
                    }
                },
                modifier =
                    editorFocusTarget(editorDescription(label, optionContext)).semantics {
                        this.selected =
                            selected
                    },
                enabled = editable,
            ) { Text(stringResource(label)) }
        }
    }
    LifeTracingSecondaryButton(
        onClick = {
            actions.field(field) { current ->
                val position = nextEditorPosition(current.categoryOptions.map(ActivityCategoryOptionDraft::position))
                current.copy(
                    categoryOptions =
                        current.categoryOptions +
                            ActivityCategoryOptionDraft(DraftIdentity.New("option-${System.nanoTime()}"), position, ""),
                )
            }
        },
        modifier = editorFocusTarget(editorDescription(R.string.activity_editor_add_option, context)),
        enabled = editable,
    ) { Text(stringResource(R.string.activity_editor_add_option)) }
}

@Composable
private fun EditorInput(
    value: String,
    label: Int,
    context: String,
    editable: Boolean,
    invalid: Boolean = false,
    guidance: Int? = null,
    onChange: (String) -> Unit,
) = LifeTracingOutlinedTextField(
    value = value,
    onValueChange = onChange,
    modifier = editorInputFocusTarget(editorDescription(label, context)).fillMaxWidth(),
    label = { Text(stringResource(label)) },
    enabled = editable,
    isError = invalid,
    supportingText = guidance?.let { { Text(stringResource(it)) } },
)

@Composable
private fun editorDescription(
    operation: Int,
    context: String,
) = stringResource(R.string.activity_editor_control_context, stringResource(operation), context)

@Composable
private fun EditorHeading(title: String) =
    Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)

@Composable
private fun EditorStatus(message: Int) {
    val visible = LocalEditorVisible.current
    Text(stringResource(message), modifier = Modifier.semantics { if (visible) liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun EditorDiscard(
    controller: ActivityTemplateEditorController,
    onBack: () -> Unit,
    title: String,
    state: ActivityTemplateEditorState,
) {
    val focus = remember { FocusRequester() }
    val lease = rememberEditorActions(controller, state)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val locals: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides density,
        ) {
            LifeTracingAccessibleTargets { content() }
        }
    }
    val dialogTitle =
        stringResource(
            R.string.activity_editor_control_context,
            stringResource(R.string.activity_editor_discard_title),
            title,
        )
    AlertDialog(
        onDismissRequest = { lease.invoke(controller::dismissDiscard) },
        title = {
            locals {
                Text(
                    stringResource(R.string.activity_editor_discard_title),
                    Modifier.semantics {
                        heading()
                        contentDescription =
                            dialogTitle
                    },
                )
            }
        },
        text = {
            locals {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                ) { Text(stringResource(R.string.activity_editor_discard_message)) }
            }
        },
        confirmButton = {
            locals {
                TextButton(
                    onClick = { lease.invoke { controller.discard(onBack) } },
                ) { Text(stringResource(R.string.activity_editor_discard)) }
            }
        },
        dismissButton = {
            locals {
                val window = LocalWindowInfo.current
                val inputMode = LocalInputModeManager.current.inputMode
                var initialFocusPending by remember { mutableStateOf(true) }
                LaunchedEffect(window.isWindowFocused, inputMode, initialFocusPending, lease.visible) {
                    if (window.isWindowFocused && initialFocusPending) {
                        withFrameNanos { }
                        lease.invoke { initialFocusPending = !focus.requestFocus() }
                    }
                }
                TextButton(
                    onClick = {
                        lease.invoke(controller::dismissDiscard)
                    },
                    modifier =
                        Modifier.focusRequester(
                            focus,
                        ),
                ) { Text(stringResource(R.string.activity_editor_continue_editing)) }
            }
        },
    )
}

@Composable
private fun rememberEditorActions(
    controller: ActivityTemplateEditorController,
    state: ActivityTemplateEditorState,
): ActivityTemplateEditorActions {
    val actions = remember(controller, state.generation) { ActivityTemplateEditorActions(controller, state.generation) }
    actions.visible = LocalEditorVisible.current
    DisposableEffect(actions) { onDispose { actions.attached = false } }
    return actions
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
