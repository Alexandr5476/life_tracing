package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.SequenceCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft

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
        controller.state.value.readyDraft()?.fields?.any {
            it.identity == captured.identity && (!requireType || it.type == captured.type)
        } == true

    fun optionExists(
        captured: SequenceFieldDraft,
        option: SequenceCategoryOptionDraft,
    ): Boolean =
        controller.state.value
            .readyDraft()
            ?.fields
            ?.firstOrNull {
                it.identity == captured.identity && it.type == captured.type
            }?.categoryOptions
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
