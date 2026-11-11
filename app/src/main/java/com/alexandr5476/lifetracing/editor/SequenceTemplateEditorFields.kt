@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

package com.alexandr5476.lifetracing.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.SequenceCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing

@Composable
internal fun SequenceFields(
    draft: SequenceTemplateDraft,
    controller: SequenceTemplateEditorController,
    parentActions: SequenceTemplateEditorActions,
    editable: Boolean,
) = Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
    val state by controller.state.collectAsState()
    SequenceEditorHeading(stringResource(R.string.sequence_editor_fields))
    draft.fields.forEachIndexed { ordinal, field ->
        val epoch = state.sequenceFieldEpochs[field.identity] ?: 0
        key(field.identity, epoch) {
            val actions = remember(parentActions, epoch) { parentActions.forSequenceFields(epoch, field.identity) }
            DisposableEffect(actions) { onDispose { actions.attached = false } }
            CompositionLocalProvider(LocalEditorTargetExists provides { actions.fieldExists(field) }) {
                val context =
                    stringResource(
                        R.string.activity_editor_field_context,
                        stringResource(sequenceFieldTypeLabel(field.type)),
                        ordinal + 1,
                        field.name,
                    )
                SequenceEditorHeading(context)
                SequenceFieldInput(field.name, R.string.sequence_editor_field_name, context, editable) { name ->
                    actions.field(field) { it.copy(name = name) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    CustomFieldType.entries.forEach { type ->
                        val description =
                            stringResource(
                                R.string.activity_editor_change_type_context,
                                stringResource(sequenceFieldTypeLabel(type)),
                                context,
                            )
                        LifeTracingChoiceButton(
                            selected = field.type == type,
                            onClick = { actions.field(field) { it.copy(type = type).normalized() } },
                            modifier = editorFocusTarget(description).semantics { selected = field.type == type },
                            enabled = editable,
                        ) { Text(stringResource(sequenceFieldTypeLabel(type))) }
                    }
                }
                LifeTracingSecondaryButton(
                    onClick = { actions.removeField(field) },
                    modifier = editorFocusTarget(sequenceFieldDescription(R.string.sequence_editor_remove, context)),
                    enabled = editable,
                ) { Text(stringResource(R.string.sequence_editor_remove)) }
                key(field.type) {
                    CompositionLocalProvider(
                        LocalEditorTargetExists provides { actions.fieldExists(field, requireType = true) },
                    ) {
                        when (field.type) {
                            CustomFieldType.NUMBER -> SequenceNumberField(field, context, controller, actions, editable)
                            CustomFieldType.CATEGORY ->
                                SequenceCategoryField(
                                    field,
                                    context,
                                    controller,
                                    actions,
                                    editable,
                                )
                            CustomFieldType.TEXT ->
                                SequenceFieldInput(
                                    field.defaultText.orEmpty(),
                                    R.string.activity_editor_default,
                                    context,
                                    editable,
                                ) { text -> actions.field(field) { it.copy(defaultText = text.ifBlank { null }) } }
                        }
                    }
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        CustomFieldType.entries.forEach { type ->
            LifeTracingSecondaryButton(
                onClick = {
                    parentActions.update { current ->
                        current.copy(
                            fields =
                                current.fields +
                                    SequenceFieldDraft(
                                        controller.newKey("field"),
                                        nextEditorPosition(current.fields.map(SequenceFieldDraft::position)),
                                        "",
                                        type,
                                    ).normalized(),
                        )
                    }
                },
                modifier =
                    editorFocusTarget(
                        stringResource(
                            R.string.activity_editor_add_field_context,
                            stringResource(sequenceFieldTypeLabel(type)),
                        ),
                    ),
                enabled = editable,
            ) { Text(stringResource(sequenceFieldTypeLabel(type))) }
        }
    }
}

@Composable
private fun SequenceNumberField(
    field: SequenceFieldDraft,
    context: String,
    controller: SequenceTemplateEditorController,
    actions: SequenceTemplateEditorActions,
    editable: Boolean,
) {
    val state by controller.state.collectAsState()
    val defaultKey = SequenceNumberInputKey.default(field)
    val precisionKey = SequenceNumberInputKey.precision(field)
    val numberRejected = state.inputIsInvalid(defaultKey)
    val precisionRejected = state.inputIsInvalid(precisionKey)
    SequenceFieldInput(field.unit.orEmpty(), R.string.activity_editor_unit, context, editable) { unit ->
        actions.field(field) { it.copy(unit = unit.ifBlank { null }) }
    }
    SequenceFieldInput(
        state.inputText(precisionKey, field.displayPrecision?.toString().orEmpty()),
        R.string.activity_editor_precision,
        context,
        editable,
        precisionRejected,
        R.string.activity_editor_precision_guidance,
    ) { text ->
        actions.numberPrecision(field, text)
    }
    SequenceFieldInput(
        state.inputText(defaultKey, formatLauncherNumber(field.defaultNumberScaled, field.displayPrecision)),
        R.string.activity_editor_default,
        context,
        editable,
        numberRejected,
        R.string.activity_editor_number_invalid.takeIf { numberRejected },
    ) { text ->
        actions.numberDefault(field, text)
    }
    FlowRow {
        SequenceMainValueCheckbox(
            checked = field.isMainValue,
            onCheckedChange = { checked ->
                actions.update { draft ->
                    if (draft.fields.none { it.identity == field.identity && it.type == CustomFieldType.NUMBER }) {
                        draft
                    } else {
                        draft.copy(
                            fields =
                                draft.fields.map {
                                    when {
                                        checked -> it.copy(isMainValue = it.identity == field.identity)
                                        it.identity == field.identity -> it.copy(isMainValue = false)
                                        else -> it
                                    }
                                },
                        )
                    }
                }
            },
            modifier = editorFocusTarget(sequenceFieldDescription(R.string.sequence_editor_main_value, context)),
            enabled = editable,
        )
        Text(stringResource(R.string.sequence_editor_main_value))
    }
}

@Composable
private fun SequenceCategoryField(
    field: SequenceFieldDraft,
    context: String,
    controller: SequenceTemplateEditorController,
    actions: SequenceTemplateEditorActions,
    editable: Boolean,
) {
    LifeTracingChoiceButton(
        selected = field.defaultCategoryOption == null,
        onClick = { actions.field(field) { it.copy(defaultCategoryOption = null) } },
        modifier =
            editorFocusTarget(sequenceFieldDescription(R.string.sequence_editor_no_default, context))
                .semantics { selected = field.defaultCategoryOption == null },
        enabled = editable,
    ) { Text(stringResource(R.string.sequence_editor_no_default)) }
    field.categoryOptions.forEachIndexed { ordinal, option ->
        key(option.identity) {
            CompositionLocalProvider(LocalEditorTargetExists provides { actions.optionExists(field, option) }) {
                val optionContext =
                    stringResource(R.string.activity_editor_option_context, context, ordinal + 1, option.label)
                SequenceFieldInput(option.label, R.string.activity_editor_option, optionContext, editable) { label ->
                    actions.option(field, option) { current, _ ->
                        current.copy(
                            categoryOptions =
                                current.categoryOptions.map {
                                    if (it.identity == option.identity) it.copy(label = label) else it
                                },
                        )
                    }
                }
                LifeTracingChoiceButton(
                    selected = field.defaultCategoryOption == option.identity,
                    onClick = {
                        actions.option(field, option) { current, _ ->
                            current.copy(defaultCategoryOption = option.identity)
                        }
                    },
                    modifier =
                        editorFocusTarget(sequenceFieldDescription(R.string.activity_editor_default, optionContext))
                            .semantics { selected = field.defaultCategoryOption == option.identity },
                    enabled = editable,
                ) { Text(stringResource(R.string.activity_editor_default)) }
                LifeTracingSecondaryButton(
                    onClick = {
                        actions.option(field, option) { current, _ ->
                            current.copy(
                                categoryOptions =
                                    current.categoryOptions
                                        .filterNot { it.identity == option.identity }
                                        .mapIndexed { position, value -> value.copy(position = position) },
                                defaultCategoryOption =
                                    current.defaultCategoryOption.takeUnless {
                                        it == option.identity
                                    },
                            )
                        }
                    },
                    modifier =
                        editorFocusTarget(
                            sequenceFieldDescription(R.string.sequence_editor_remove, optionContext),
                        ),
                    enabled = editable,
                ) { Text(stringResource(R.string.sequence_editor_remove)) }
            }
        }
    }
    LifeTracingSecondaryButton(
        onClick = {
            actions.field(field) { current ->
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
        },
        modifier = editorFocusTarget(sequenceFieldDescription(R.string.activity_editor_add_option, context)),
        enabled = editable,
    ) { Text(stringResource(R.string.activity_editor_add_option)) }
}

@Composable
private fun SequenceFieldInput(
    value: String,
    label: Int,
    context: String,
    editable: Boolean,
    invalid: Boolean = false,
    guidance: Int? = null,
    onChange: (String) -> Unit,
) = LifeTracingOutlinedTextField(
    value,
    onChange,
    editorInputFocusTarget(sequenceFieldDescription(label, context)).fillMaxWidth(),
    label = { Text(stringResource(label)) },
    enabled = editable,
    isError = invalid,
    supportingText = guidance?.let { { Text(stringResource(it)) } },
)

@Composable
private fun sequenceFieldDescription(
    operation: Int,
    context: String,
) = stringResource(R.string.activity_editor_control_context, stringResource(operation), context)

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
