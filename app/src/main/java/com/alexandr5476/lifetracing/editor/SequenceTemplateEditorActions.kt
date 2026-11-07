package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId

/** Ephemeral presentation authority; the controller remains the command boundary. */
internal class SequenceTemplateEditorActions(
    private val controller: SequenceTemplateEditorController,
    private val generation: Long,
    private val discardGeneration: Long? = null,
    private val normalModeOnly: Boolean = false,
    private val available: () -> Boolean = { true },
) {
    private val modeGeneration = controller.presentationModeGeneration
    var attached = true
    var visible = true

    fun forSequenceFields(
        epoch: Long,
        identity: DraftIdentity<SequenceTemplateFieldId>? = null,
    ): SequenceTemplateEditorActions =
        SequenceTemplateEditorActions(controller, generation, discardGeneration, normalModeOnly) {
            val state = controller.state.value
            val currentEpoch =
                if (identity ==
                    null
                ) {
                    state.sequenceFieldEpoch
                } else {
                    state.sequenceFieldEpochs[identity] ?: 0
                }
            val ownsIdentity = identity == null || state.presentation()?.fields?.containsKey(identity) == true
            isAvailable && ownsIdentity && currentEpoch == epoch
        }

    val isAvailable: Boolean
        get() {
            var admitted = false
            invoke { admitted = true }
            return admitted
        }

    fun numberDefault(
        captured: SequenceFieldDraft,
        text: String,
    ) = invoke { controller.setSequenceFieldNumber(captured, text) }

    fun numberPrecision(
        captured: SequenceFieldDraft,
        text: String,
    ) = invoke { controller.setSequenceFieldNumber(captured, text, precision = true) }

    fun invoke(action: () -> Unit) {
        if (attached && visible) {
            controller.withPresentationGeneration(generation, discardGeneration) {
                val ownsMode =
                    controller.presentationModeGeneration == modeGeneration &&
                        (!normalModeOnly || controller.state.value.manipulation == null)
                if (ownsMode && attached && visible) {
                    if (available()) action()
                }
            }
        }
    }

    fun update(transform: (SequenceTemplateDraft) -> SequenceTemplateDraft) =
        invoke {
            controller.updateDraft { current -> transform(current) }
        }

    fun fieldExists(
        captured: SequenceFieldDraft,
        requireType: Boolean = false,
    ): Boolean =
        controller.state.value.presentation()?.fields?.get(captured.identity)?.let {
            !requireType || it.type == captured.type
        } == true

    fun optionExists(
        captured: SequenceFieldDraft,
        option: SequenceCategoryOptionDraft,
    ): Boolean =
        controller.state.value
            .presentation()
            ?.fields
            ?.get(captured.identity)
            ?.takeIf { it.type == captured.type }
            ?.categoryOptions
            ?.any { it.identity == option.identity } == true

    fun removeField(captured: SequenceFieldDraft) =
        update { draft ->
            if (draft.fields.none { it.identity == captured.identity }) {
                draft
            } else {
                draft.copy(
                    fields =
                        draft.fields
                            .filterNot { it.identity == captured.identity }
                            .mapIndexed { position, field -> field.copy(position = position) },
                )
            }
        }

    fun option(
        captured: SequenceFieldDraft,
        option: SequenceCategoryOptionDraft,
        transform: (SequenceFieldDraft, SequenceCategoryOptionDraft) -> SequenceFieldDraft,
    ) = field(captured) { current ->
        current.categoryOptions
            .firstOrNull { it.identity == option.identity }
            ?.let { transform(current, it) } ?: current
    }

    fun field(
        captured: SequenceFieldDraft,
        transform: (SequenceFieldDraft) -> SequenceFieldDraft,
    ) = update { draft ->
        draft.copy(
            fields =
                draft.fields.map {
                    if (it.identity == captured.identity && it.type == captured.type) transform(it) else it
                },
        )
    }
}
