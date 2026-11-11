@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

package com.alexandr5476.lifetracing.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing

internal val LocalSequenceStepActions = staticCompositionLocalOf<SequenceStepActions> { error("Step actions missing") }
internal val LocalSequenceStepContext = staticCompositionLocalOf { "" }

@Composable
internal fun stepControlDescription(
    label: Int,
    context: String = LocalSequenceStepContext.current,
): String =
    if (context.isEmpty()) {
        stringResource(label)
    } else {
        stringResource(
            R.string.activity_editor_control_context,
            stringResource(label),
            context,
        )
    }

@Composable
@Suppress("CyclomaticComplexMethod") // One Field row keeps its type, provenance and logical lease together.
internal fun LocalActivityFields(
    step: ActivityStepDraft,
    activity: ActivitySnapshotDraft,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) = SequenceCard {
    val actions = LocalSequenceStepActions.current
    val state by controller.state.collectAsState()
    val stepContext = LocalSequenceStepContext.current
    val parentExists = LocalEditorTargetExists.current
    SequenceEditorHeading(stringResource(R.string.sequence_editor_local_fields))
    activity.fields.forEachIndexed { ordinal, field ->
        key(field.identity, field.type) {
            val lease =
                rememberSequenceEditorActions(controller, state, available = {
                    actions.available() && actions.fieldExists(field)
                })
            val fieldActions =
                remember(controller, lease, step.identity) { SequenceStepActions(controller, lease, step.identity) }
            val context =
                stringResource(
                    R.string.activity_editor_control_context,
                    stringResource(
                        R.string.activity_editor_field_context,
                        stringResource(sequenceFieldTypeLabel(field.type)),
                        ordinal + 1,
                        field.localNameOverride ?: field.nameAtCreation,
                    ),
                    stepContext,
                )
            CompositionLocalProvider(
                LocalSequenceStepContext provides context,
                LocalSequenceEditorActions provides lease,
                LocalSequenceStepActions provides fieldActions,
                LocalEditorTargetExists provides { parentExists?.invoke() != false && actions.fieldExists(field) },
            ) {
                SequenceEditorHeading(context)
                SnapshotInput(
                    field.localNameOverride ?: field.nameAtCreation,
                    R.string.activity_editor_field_name,
                    editable,
                ) { name ->
                    fieldActions.field(field) { current ->
                        if (current.sourceFieldId == null) {
                            current.copy(nameAtCreation = name)
                        } else {
                            current.copy(localNameOverride = name.ifBlank { null })
                        }
                    }
                }
                if (field.sourceFieldId == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                        CustomFieldType.entries.forEach { type ->
                            ChoiceButton(field.type == type, {
                                fieldActions.field(field) { current ->
                                    if (current.sourceFieldId == null) {
                                        current
                                            .withCompatibleTypeReplacement(
                                                type,
                                                controller.newKey("local-field-replacement"),
                                            ).normalized()
                                    } else {
                                        current
                                    }
                                }
                            }, editable) { Text(stringResource(sequenceFieldTypeLabel(type))) }
                        }
                    }
                }
                when (field.type) {
                    CustomFieldType.NUMBER -> SnapshotNumberField(field, controller, editable)
                    CustomFieldType.CATEGORY -> SnapshotCategoryField(field, controller, editable)
                    CustomFieldType.TEXT ->
                        SnapshotInput(
                            field.defaultText.orEmpty(),
                            R.string.activity_editor_default,
                            editable,
                        ) { text ->
                            fieldActions.field(field) { it.copy(defaultText = text.ifBlank { null }) }
                        }
                }
                LifeTracingSecondaryButton(
                    onClick = { if (editable) fieldActions.removeField(field) },
                    modifier = editorFocusTarget(stepControlDescription(R.string.sequence_editor_remove)),
                    enabled = editable,
                ) { Text(stringResource(R.string.sequence_editor_remove)) }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        CustomFieldType.entries.forEach { type ->
            val description =
                stringResource(
                    R.string.activity_editor_control_context,
                    stringResource(
                        R.string.activity_editor_add_field_context,
                        stringResource(sequenceFieldTypeLabel(type)),
                    ),
                    stepContext,
                )
            LifeTracingSecondaryButton(
                onClick = {
                    if (editable) {
                        actions.activity { current ->
                            current.copy(
                                fields =
                                    current.fields +
                                        ActivitySnapshotFieldDraft(
                                            controller.newKey("local-field"),
                                            null,
                                            nextEditorPosition(
                                                current.fields.map(ActivitySnapshotFieldDraft::position),
                                            ),
                                            "",
                                            type = type,
                                        ),
                            )
                        }
                    }
                },
                modifier = editorFocusTarget(description),
                enabled = editable,
            ) { Text(stringResource(sequenceFieldTypeLabel(type))) }
        }
    }
}

@Composable
private fun SnapshotNumberField(
    field: ActivitySnapshotFieldDraft,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) {
    val actions = LocalSequenceStepActions.current
    val state by controller.state.collectAsState()
    val defaultKey = actions.numberDefaultKey(field)
    val precisionKey = actions.numberPrecisionKey(field)
    val numberRejected = state.inputIsInvalid(defaultKey)
    val precisionRejected = state.inputIsInvalid(precisionKey)
    SnapshotInput(field.unit.orEmpty(), R.string.activity_editor_unit, editable) { unit ->
        actions.field(field) {
            it.withCompatibleUnitReplacement(unit.ifBlank { null }, controller.newKey("local-field-replacement"))
        }
    }
    SnapshotInput(
        state.inputText(precisionKey, field.displayPrecision?.toString().orEmpty()),
        R.string.activity_editor_precision,
        editable,
        precisionRejected,
        R.string.activity_editor_precision_guidance,
    ) { text ->
        actions.numberPrecision(field, text)
    }
    SnapshotInput(
        state.inputText(defaultKey, formatLauncherNumber(field.defaultNumberScaled, field.displayPrecision)),
        R.string.activity_editor_default,
        editable,
        numberRejected,
        R.string.activity_editor_number_invalid.takeIf { numberRejected },
    ) { text -> actions.numberDefault(field, text) }
    FlowRow {
        SequenceMainValueCheckbox(
            checked = field.isMainValue,
            onCheckedChange = { if (editable) actions.mainValue(field, it) },
            modifier = editorFocusTarget(stepControlDescription(R.string.sequence_editor_main_value)),
            enabled = editable,
        )
        Text(stringResource(R.string.sequence_editor_main_value))
    }
}

@Composable
private fun SnapshotCategoryField(
    field: ActivitySnapshotFieldDraft,
    controller: SequenceTemplateEditorController,
    editable: Boolean,
) {
    val actions = LocalSequenceStepActions.current
    val state by controller.state.collectAsState()
    ChoiceButton(field.defaultCategoryOption == null, {
        actions.field(field) { it.copy(defaultCategoryOption = null) }
    }, editable) { Text(stringResource(R.string.sequence_editor_no_default)) }
    val fieldContext = LocalSequenceStepContext.current
    val parentExists = LocalEditorTargetExists.current
    field.categoryOptions.forEachIndexed { ordinal, option ->
        key(option.identity) {
            val lease =
                rememberSequenceEditorActions(controller, state, available = {
                    actions.available() && actions.optionExists(field, option)
                })
            val optionActions = remember(actions, lease) { actions.scoped(lease) }
            val context =
                stringResource(
                    R.string.activity_editor_option_context,
                    fieldContext,
                    ordinal + 1,
                    option.localLabelOverride ?: option.labelAtCreation,
                )
            CompositionLocalProvider(
                LocalSequenceStepContext provides context,
                LocalSequenceEditorActions provides lease,
                LocalEditorTargetExists provides
                    { parentExists?.invoke() != false && actions.optionExists(field, option) },
            ) {
                SnapshotInput(
                    option.localLabelOverride ?: option.labelAtCreation,
                    R.string.activity_editor_option,
                    editable,
                ) { label ->
                    optionActions.option(field, option) { current, target ->
                        current.copy(
                            categoryOptions =
                                current.categoryOptions.map {
                                    if (it.identity != target.identity) {
                                        it
                                    } else if (it.sourceOptionId == null) {
                                        it.copy(labelAtCreation = label)
                                    } else {
                                        it.copy(localLabelOverride = label.ifBlank { null })
                                    }
                                },
                        )
                    }
                }
                ChoiceButton(field.defaultCategoryOption == option.identity, {
                    optionActions.option(field, option) { current, target ->
                        current.copy(defaultCategoryOption = target.identity)
                    }
                }, editable, description = stepControlDescription(R.string.activity_editor_default)) {
                    Text(stringResource(R.string.activity_editor_default))
                }
                LifeTracingSecondaryButton(
                    onClick = {
                        if (editable) {
                            optionActions.option(field, option) { current, target ->
                                current.copy(
                                    categoryOptions =
                                        current.categoryOptions
                                            .filterNot {
                                                it.identity == target.identity
                                            }.mapIndexed { position, value -> value.copy(position = position) },
                                    defaultCategoryOption =
                                        current.defaultCategoryOption.takeUnless {
                                            it == target.identity
                                        },
                                )
                            }
                        }
                    },
                    modifier = editorFocusTarget(stepControlDescription(R.string.sequence_editor_remove)),
                    enabled = editable,
                ) { Text(stringResource(R.string.sequence_editor_remove)) }
            }
        }
    }
    LifeTracingSecondaryButton(
        onClick = {
            if (editable) {
                actions.field(field) { current ->
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
        },
        modifier = editorFocusTarget(stepControlDescription(R.string.activity_editor_add_option)),
        enabled = editable,
    ) { Text(stringResource(R.string.activity_editor_add_option)) }
}

@Composable
private fun SnapshotInput(
    value: String,
    label: Int,
    editable: Boolean,
    invalid: Boolean = false,
    guidance: Int? = null,
    change: (String) -> Unit,
) = LifeTracingOutlinedTextField(
    value,
    { if (editable) change(it) },
    editorInputFocusTarget(stepControlDescription(label)).fillMaxWidth(),
    label = { Text(stringResource(label)) },
    enabled = editable,
    isError = invalid,
    supportingText = guidance?.let { { Text(stringResource(it)) } },
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
