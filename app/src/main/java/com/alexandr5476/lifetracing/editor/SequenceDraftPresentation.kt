package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft

/** One view per immutable Ready draft, shared by all native liveness inspections.
 * Values reference the current draft; this view is never mutation or persistence authority.
 */
internal class SequenceDraftPresentation(
    draft: SequenceTemplateDraft,
) {
    val steps: Map<DraftIdentity<SequenceNodeId>, ActivityStepDraft> =
        buildMap {
            draft.nodes.forEach { node ->
                when (node) {
                    is SequenceNodeDraft.Step -> put(node.identity, node.value)
                    is SequenceNodeDraft.Repeat -> node.value.children.forEach { put(it.identity, it) }
                }
            }
        }
    val fields: Map<DraftIdentity<*>, SequenceFieldDraft> = draft.fields.associateBy { it.identity }
    val numberInputKeys: Set<String> = draft.activeNumberInputKeys()
}
