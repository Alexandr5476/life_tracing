package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceCategoryOptionDraft
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
    private val stepFields =
        steps.mapValues { (_, step) ->
            step
                .configuration()
                ?.fields
                .orEmpty()
                .associate { it.identity to SnapshotFieldPresentation(it) }
        }
    private val sequenceOptions =
        fields.mapValues { (_, field) -> field.categoryOptions.mapTo(hashSetOf()) { it.identity } }

    fun fieldExists(
        step: DraftIdentity<SequenceNodeId>,
        captured: ActivitySnapshotFieldDraft,
    ): Boolean = stepFields[step]?.get(captured.identity)?.field?.matches(captured) == true

    fun optionExists(
        step: DraftIdentity<SequenceNodeId>,
        captured: ActivitySnapshotFieldDraft,
        option: ActivitySnapshotCategoryOptionDraft,
    ): Boolean =
        stepFields[step]?.get(captured.identity)?.let {
            it.field.matches(captured) && (option.identity to option.sourceOptionId) in it.options
        } == true

    fun optionExists(
        captured: SequenceFieldDraft,
        option: SequenceCategoryOptionDraft,
    ): Boolean =
        fields[captured.identity]?.type == captured.type &&
            sequenceOptions[captured.identity]?.contains(option.identity) == true
}

private class SnapshotFieldPresentation(
    val field: ActivitySnapshotFieldDraft,
) {
    val options = field.categoryOptions.mapTo(hashSetOf()) { it.identity to it.sourceOptionId }
}
