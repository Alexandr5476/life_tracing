@file:Suppress("ReturnCount") // Reject invalid sources before constructing any destination authority.

package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft

internal enum class SequenceDestinationOperation { Move, Duplicate }

/** Structural authority only; no independent Activity configuration graph is retained. */
internal data class SequenceStructureNode(
    val identity: DraftIdentity<SequenceNodeId>,
    val position: Int,
    val children: List<Pair<DraftIdentity<SequenceNodeId>, Int>>? = null,
)

internal fun SequenceTemplateDraft.manipulationStructure() =
    nodes.map { node ->
        SequenceStructureNode(
            node.identity,
            node.position,
            (node as? SequenceNodeDraft.Repeat)?.value?.children?.map { it.identity to it.position },
        )
    }

internal fun SequenceTemplateDraft.manipulationContains(identity: DraftIdentity<SequenceNodeId>) =
    nodes.any { node ->
        node.identity == identity ||
            node is SequenceNodeDraft.Repeat &&
            node.value.children.any { it.identity == identity }
    }

internal fun SequenceTemplateDraft.manipulationStep(identity: DraftIdentity<SequenceNodeId>) =
    nodes.firstNotNullOfOrNull { node ->
        when (node) {
            is SequenceNodeDraft.Step -> node.value.takeIf { it.identity == identity }
            is SequenceNodeDraft.Repeat -> node.value.children.firstOrNull { it.identity == identity }
        }
    }

internal fun SequenceTemplateDraft.canDuplicateManipulation(
    identity: DraftIdentity<SequenceNodeId>,
    original: SequenceTemplateDraft,
): Boolean {
    if (identity !is DraftIdentity.Existing) return false
    val current = manipulationStep(identity)?.activity as? StepActivityDraft.Existing ?: return false
    val owned = original.manipulationStep(identity)?.activity as? StepActivityDraft.Existing ?: return false
    return current.snapshotId == owned.snapshotId
}

internal data class SequenceDestinationChoice(
    val destination: SequenceDropDestination,
    val previous: DraftIdentity<SequenceNodeId>?,
    val next: DraftIdentity<SequenceNodeId>?,
)

/** Final positions: Move removes the source; Duplicate keeps it. O(nodes + slots). */
internal fun sequenceManipulationDestinations(
    draft: SequenceTemplateDraft,
    source: DraftIdentity<SequenceNodeId>,
    operation: SequenceDestinationOperation,
): List<SequenceDestinationChoice> {
    if (!draft.manipulationContains(source)) return emptyList()
    if (operation == SequenceDestinationOperation.Duplicate &&
        (source !is DraftIdentity.Existing || draft.manipulationStep(source)?.activity !is StepActivityDraft.Existing)
    ) {
        return emptyList()
    }
    val repeatSource = draft.nodes.any { it.identity == source && it is SequenceNodeDraft.Repeat }
    if (repeatSource && operation == SequenceDestinationOperation.Duplicate) return emptyList()
    val omitted = source.takeIf { operation == SequenceDestinationOperation.Move }
    return buildList {
        fun slots(
            container: DraftIdentity<SequenceNodeId>?,
            identities: List<DraftIdentity<SequenceNodeId>>,
        ) {
            val remaining = identities.filterNot { it == omitted }
            for (position in 0..remaining.size) {
                add(
                    SequenceDestinationChoice(
                        SequenceDropDestination(container, position),
                        remaining.getOrNull(position - 1),
                        remaining.getOrNull(position),
                    ),
                )
            }
        }
        slots(null, draft.nodes.map { it.identity })
        if (!repeatSource) {
            draft.nodes.filterIsInstance<SequenceNodeDraft.Repeat>().forEach { repeat ->
                slots(repeat.identity, repeat.value.children.map { it.identity })
            }
        }
    }
}

/** Controller-issued single-use operation. Dismissal retires this exact instance. */
internal class SequenceDestinationOffer(
    val source: DraftIdentity<SequenceNodeId>,
    val operation: SequenceDestinationOperation,
    val structure: List<SequenceStructureNode>,
    val choices: List<SequenceDestinationChoice>,
)
