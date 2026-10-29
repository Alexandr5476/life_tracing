package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft

/** A presentation lease, not an authoring command or identity allocator. */
internal class ActivityTemplateEditorActions(
    private val controller: ActivityTemplateEditorController,
    private val generation: Long,
) {
    var attached = true
    var visible = true

    fun invoke(action: () -> Unit) {
        if (attached && visible && controller.ownsGeneration(generation)) action()
    }

    fun update(transform: (ActivityTemplateDraft) -> ActivityTemplateDraft) =
        invoke {
            val draft = controller.state.value.readyDraft() ?: return@invoke
            val proposed = transform(draft)
            if (proposed != draft) controller.updateDraft { current -> if (current === draft) proposed else current }
        }

    fun field(
        captured: ActivityFieldDraft,
        transform: (ActivityFieldDraft) -> ActivityFieldDraft,
    ) = update { draft ->
        draft.copy(
            fields =
                draft.fields.map {
                    if (it.identity == captured.identity && it.type == captured.type) transform(it) else it
                },
        )
    }
}
