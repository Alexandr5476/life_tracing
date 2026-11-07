package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.launcher.formatLauncherNumber
import com.alexandr5476.lifetracing.launcher.parseLauncherNumber

private const val MAX_NUMBER_FIELD_PRECISION = 3

internal object SequenceNumberInputKey {
    fun default(field: SequenceFieldDraft): String = "default:${owner(field)}"

    fun precision(field: SequenceFieldDraft): String = "precision:${owner(field)}"

    fun default(
        field: ActivitySnapshotFieldDraft,
        step: DraftIdentity<SequenceNodeId>,
    ): String = "default:${owner(field, step)}"

    fun precision(
        field: ActivitySnapshotFieldDraft,
        step: DraftIdentity<SequenceNodeId>,
    ): String = "precision:${owner(field, step)}"

    private fun owner(field: SequenceFieldDraft): String = "sequence-field:${part(field.identity)}:${part(field.unit)}"

    private fun owner(
        field: ActivitySnapshotFieldDraft,
        step: DraftIdentity<SequenceNodeId>,
    ): String = "step-field:${part(step)}:${part(field.identity)}:${part(field.sourceFieldId)}:${part(field.unit)}"

    private fun part(value: Any?): String = value?.toString()?.let { "${it.length}:$it" } ?: "null"
}

internal fun SequenceTemplateDraft.sameSequenceFieldSchema(other: SequenceTemplateDraft): Boolean =
    fields === other.fields ||
        fields.map { Triple(it.identity, it.type, it.unit) } ==
        other.fields.map {
            Triple(it.identity, it.type, it.unit)
        }

/** Keep survivor lifetimes stable; use the monotonic command epoch only for changed/new owners. */
internal fun SequenceTemplateEditorState.sequenceFieldEpochsAfter(
    before: SequenceTemplateDraft,
    after: SequenceTemplateDraft,
): Map<DraftIdentity<SequenceTemplateFieldId>, Long> {
    if (before.sameSequenceFieldSchema(after)) return sequenceFieldEpochs
    val previous = before.fields.associateBy { it.identity }
    return after.fields.associate { field ->
        val original = previous[field.identity]
        val unchanged = original != null && original.type == field.type && original.unit == field.unit
        field.identity to if (unchanged) sequenceFieldEpochs[field.identity] ?: 0 else sequenceFieldEpoch + 1
    }
}

internal fun Map<String, SequenceEditorTextInput>.retireFieldInputs(
    draft: SequenceTemplateDraft,
): Map<String, SequenceEditorTextInput> {
    val active = draft.activeNumberInputKeys()
    return filterKeys { !it.startsWith("default:") && !it.startsWith("precision:") || it in active }
}

internal data class SequenceNumberFieldValue(
    val number: Long?,
    val precision: Int?,
    val valid: Boolean,
)

internal data class SequenceNumberFieldEdit(
    val value: SequenceNumberFieldValue,
    val inputs: Map<String, SequenceEditorTextInput>,
)

/** Activity-editor precision policy: preserve the literal, revalidate, never round a proposed default. */
@Suppress("LongParameterList") // Both identity-scoped controls share one atomic raw/semantic edit.
internal fun sequenceNumberFieldEdit(
    inputs: Map<String, SequenceEditorTextInput>,
    defaultKey: String,
    precisionKey: String,
    number: Long?,
    currentPrecision: Int?,
    text: String,
    editingPrecision: Boolean,
): SequenceNumberFieldEdit {
    val originalDefault = formatLauncherNumber(number, currentPrecision)

    fun input(
        key: String,
        literal: String,
        original: String,
        valid: Boolean,
    ) = SequenceEditorTextInput(literal, inputs[key]?.originalText ?: original, valid)
    val proposedPrecision =
        if (editingPrecision) {
            text.toIntOrNull()?.takeIf {
                it in 0..MAX_NUMBER_FIELD_PRECISION
            }
        } else {
            currentPrecision
        }
    if (editingPrecision && text.isNotBlank() && proposedPrecision == null) {
        return SequenceNumberFieldEdit(
            SequenceNumberFieldValue(number, currentPrecision, false),
            mapOf(precisionKey to input(precisionKey, text, currentPrecision?.toString().orEmpty(), false)),
        )
    }
    val visibleDefault = if (editingPrecision) inputs[defaultKey]?.text ?: originalDefault else text
    val parsed = visibleDefault.takeUnless(String::isBlank)?.let { parseLauncherNumber(it, proposedPrecision) }
    val valid = visibleDefault.isBlank() || parsed != null
    return SequenceNumberFieldEdit(
        SequenceNumberFieldValue(if (valid) parsed else number, proposedPrecision, valid),
        buildMap {
            put(defaultKey, input(defaultKey, visibleDefault, originalDefault, valid))
            if (editingPrecision) {
                put(precisionKey, input(precisionKey, text, currentPrecision?.toString().orEmpty(), true))
            }
        },
    )
}

internal data class SequenceNumberDraftEdit(
    val draft: SequenceTemplateDraft,
    val inputs: Map<String, SequenceEditorTextInput>,
)

/** Generic draft edits obey the native precision policy, using canonical command-local ownership. */
internal fun revalidateSequenceNumberPrecision(
    before: SequenceTemplateDraft,
    after: SequenceTemplateDraft,
    inputs: Map<String, SequenceEditorTextInput>,
): SequenceNumberDraftEdit = SequenceNumberPrecisionRevalidation(inputs).revalidate(before, after)

private class SequenceNumberPrecisionRevalidation(
    private val inputs: Map<String, SequenceEditorTextInput>,
) {
    private var changedInputs: MutableMap<String, SequenceEditorTextInput>? = null

    fun revalidate(
        before: SequenceTemplateDraft,
        after: SequenceTemplateDraft,
    ): SequenceNumberDraftEdit {
        val fields = sequenceFields(before.fields, after.fields)
        val nodes =
            if (before.nodes === after.nodes) {
                after.nodes
            } else {
                // Build from canonical current ownership; the presentation cache never authorizes edits.
                val originalSteps =
                    buildMap {
                        before.nodes.forEach { node ->
                            when (node) {
                                is SequenceNodeDraft.Step -> put(node.identity, node.value)
                                is SequenceNodeDraft.Repeat -> node.value.children.forEach { put(it.identity, it) }
                            }
                        }
                    }

                fun changed(current: ActivityStepDraft) = step(originalSteps[current.identity], current)
                after.nodes.map { node ->
                    when (node) {
                        is SequenceNodeDraft.Step -> SequenceNodeDraft.Step(changed(node.value))
                        is SequenceNodeDraft.Repeat ->
                            SequenceNodeDraft.Repeat(
                                node.value.copy(children = node.value.children.map(::changed)),
                            )
                    }
                }
            }
        val draft =
            if (fields == after.fields &&
                nodes == after.nodes
            ) {
                after
            } else {
                after.copy(fields = fields, nodes = nodes)
            }
        return SequenceNumberDraftEdit(draft, changedInputs?.toMap() ?: inputs)
    }

    private fun sequenceFields(
        before: List<SequenceFieldDraft>,
        after: List<SequenceFieldDraft>,
    ): List<SequenceFieldDraft> {
        if (before === after) return after
        val byId = before.associateBy { it.identity }
        return after.map { field ->
            val original =
                byId[field.identity]?.takeIf {
                    it.type == CustomFieldType.NUMBER && it.type == field.type && it.unit == field.unit
                }
            if (original != null && original.displayPrecision != field.displayPrecision) {
                field.copy(
                    defaultNumberScaled =
                        defaultValue(
                            SequenceNumberInputKey.default(field),
                            original.defaultNumberScaled,
                            original.displayPrecision,
                            field.displayPrecision,
                        ),
                )
            } else {
                field
            }
        }
    }

    @Suppress("ReturnCount") // Exit at canonical ownership and unchanged owned Fields before allocating.
    private fun step(before: ActivityStepDraft?, current: ActivityStepDraft): ActivityStepDraft {
        val original = before?.configuration() ?: return current
        val configuration = current.configuration() ?: return current
        if (original.fields === configuration.fields) return current
        val byId = original.fields.associateBy { it.identity }
        val fields =
            configuration.fields.map { field ->
                val previous = byId[field.identity]?.takeIf { it.type == CustomFieldType.NUMBER && field.matches(it) }
                if (previous != null && previous.displayPrecision != field.displayPrecision) {
                    field.copy(
                        defaultNumberScaled =
                            defaultValue(
                                SequenceNumberInputKey.default(field, current.identity),
                                previous.defaultNumberScaled,
                                previous.displayPrecision,
                                field.displayPrecision,
                            ),
                    )
                } else {
                    field
                }
            }
        if (fields == configuration.fields) return current
        val updated = configuration.copy(fields = fields)
        return current.copy(
            activity =
                when (val activity = current.activity) {
                    is StepActivityDraft.Local -> activity.copy(configuration = updated)
                    is StepActivityDraft.Existing -> activity.copy(configuration = updated)
                    else -> activity
                },
        )
    }

    private fun defaultValue(
        key: String,
        number: Long?,
        oldPrecision: Int?,
        precision: Int?,
    ): Long? {
        val originalText = formatLauncherNumber(number, oldPrecision)
        val previous = inputs[key]
        val text = previous?.text ?: originalText
        val parsed = text.takeUnless(String::isBlank)?.let { parseLauncherNumber(it, precision) }
        val valid = text.isBlank() || parsed != null
        val values = changedInputs ?: inputs.toMutableMap().also { changedInputs = it }
        values[key] = SequenceEditorTextInput(text, previous?.originalText ?: originalText, valid)
        return if (valid) parsed else number
    }
}
