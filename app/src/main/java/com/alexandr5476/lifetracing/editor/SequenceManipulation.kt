@file:Suppress("ReturnCount")

package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft

data class SequenceDropDestination(
    val repeat: DraftIdentity<SequenceNodeId>? = null,
    val position: Int,
)

data class SequenceManipulationUiState(
    val selected: DraftIdentity<SequenceNodeId>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val operationCount: Int,
    val duplicatePreviews: Map<SequenceNodeId, ActivitySnapshotDraft>,
)

/** One baseline plus small reversible deltas; history never retains a draft graph per operation. */
internal class SequenceManipulationSession(
    val baseline: SequenceTemplateDraft,
    selected: DraftIdentity<SequenceNodeId>,
    duplicateSources: SequenceTemplateDraft = baseline,
) {
    private val undo = ArrayDeque<SequenceStructuralEdit>()
    private val redo = ArrayDeque<SequenceStructuralEdit>()
    var selected = selected
        private set

    val duplicatePreviews =
        duplicateSources.nodes
            .flatMap(SequenceNodeDraft::steps)
            .mapNotNull { step ->
                val id = (step.identity as? DraftIdentity.Existing)?.id ?: return@mapNotNull null
                val activity = step.activity as? StepActivityDraft.Existing ?: return@mapNotNull null
                id to activity.configuration
            }.toMap()

    fun select(identity: DraftIdentity<SequenceNodeId>) {
        selected = identity
    }

    fun move(
        draft: SequenceTemplateDraft,
        identity: DraftIdentity<SequenceNodeId>,
        destination: SequenceDropDestination,
    ): SequenceTemplateDraft? {
        val source = draft.locationOf(identity) ?: return null
        val moved = draft.move(identity, destination) ?: return null
        if (moved == draft) {
            selected = identity
            return draft
        }
        record(SequenceStructuralEdit.Move(identity, source, destination, selected))
        selected = identity
        return moved
    }

    fun duplicate(
        draft: SequenceTemplateDraft,
        sourceIdentity: DraftIdentity<SequenceNodeId>,
        duplicateIdentity: DraftIdentity.New,
        destination: SequenceDropDestination,
    ): SequenceTemplateDraft? {
        val source = draft.step(sourceIdentity) ?: return null
        val sourceId = (source.identity as? DraftIdentity.Existing)?.id ?: return null
        if (source.activity !is StepActivityDraft.Existing) return null
        val duplicate =
            ActivityStepDraft(
                duplicateIdentity,
                destination.position,
                StepActivityDraft.Duplicate(sourceId),
                source.overrides,
            )
        val changed = draft.insert(duplicate, destination) ?: return null
        record(SequenceStructuralEdit.Duplicate(duplicate, destination, selected))
        selected = duplicateIdentity
        return changed
    }

    fun undo(draft: SequenceTemplateDraft): SequenceTemplateDraft? {
        val edit = undo.removeLastOrNull() ?: return null
        val changed =
            edit.undo(draft) ?: run {
                undo.addLast(edit)
                return null
            }
        redo.addLast(edit)
        selected = edit.selectionBefore
        return changed
    }

    fun redo(draft: SequenceTemplateDraft): SequenceTemplateDraft? {
        val edit = redo.removeLastOrNull() ?: return null
        val changed =
            edit.redo(draft) ?: run {
                redo.addLast(edit)
                return null
            }
        undo.addLast(edit)
        selected = edit.identity
        return changed
    }

    fun uiState() =
        SequenceManipulationUiState(selected, undo.isNotEmpty(), redo.isNotEmpty(), undo.size, duplicatePreviews)

    private fun record(edit: SequenceStructuralEdit) {
        undo.addLast(edit)
        redo.clear()
    }
}

private sealed interface SequenceStructuralEdit {
    val identity: DraftIdentity<SequenceNodeId>
    val selectionBefore: DraftIdentity<SequenceNodeId>

    fun undo(draft: SequenceTemplateDraft): SequenceTemplateDraft?

    fun redo(draft: SequenceTemplateDraft): SequenceTemplateDraft?

    data class Move(
        override val identity: DraftIdentity<SequenceNodeId>,
        val from: SequenceDropDestination,
        val to: SequenceDropDestination,
        override val selectionBefore: DraftIdentity<SequenceNodeId>,
    ) : SequenceStructuralEdit {
        override fun undo(draft: SequenceTemplateDraft) = draft.move(identity, from)

        override fun redo(draft: SequenceTemplateDraft) = draft.move(identity, to)
    }

    data class Duplicate(
        val step: ActivityStepDraft,
        val destination: SequenceDropDestination,
        override val selectionBefore: DraftIdentity<SequenceNodeId>,
    ) : SequenceStructuralEdit {
        override val identity = step.identity

        override fun undo(draft: SequenceTemplateDraft) = draft.removeStep(identity)

        override fun redo(draft: SequenceTemplateDraft) = draft.insert(step, destination)
    }
}

private fun SequenceTemplateDraft.locationOf(identity: DraftIdentity<SequenceNodeId>): SequenceDropDestination? {
    nodes.forEachIndexed { position, node ->
        if (node.identity == identity) return SequenceDropDestination(position = position)
        if (node is SequenceNodeDraft.Repeat) {
            val child = node.value.children.indexOfFirst { it.identity == identity }
            if (child >= 0) return SequenceDropDestination(node.identity, child)
        }
    }
    return null
}

private fun SequenceTemplateDraft.step(identity: DraftIdentity<SequenceNodeId>): ActivityStepDraft? =
    nodes.firstNotNullOfOrNull { node ->
        when (node) {
            is SequenceNodeDraft.Step -> node.value.takeIf { it.identity == identity }
            is SequenceNodeDraft.Repeat -> node.value.children.singleOrNull { it.identity == identity }
        }
    }

private fun SequenceTemplateDraft.move(
    identity: DraftIdentity<SequenceNodeId>,
    destination: SequenceDropDestination,
): SequenceTemplateDraft? {
    val topLevel = nodes.singleOrNull { it.identity == identity }
    if (topLevel is SequenceNodeDraft.Repeat && destination.repeat != null) return null
    if (topLevel != null) {
        val remaining = nodes.filterNot { it.identity == identity }.reindexNodes()
        if (topLevel is SequenceNodeDraft.Repeat) {
            if (destination.position !in 0..remaining.size) return null
            return copy(
                nodes = remaining.toMutableList().apply { add(destination.position, topLevel) }.reindexNodes(),
            )
        }
    }
    val step = step(identity) ?: return null
    val removed = removeStep(identity) ?: return null
    return removed.insert(step, destination)
}

private fun SequenceTemplateDraft.insert(
    step: ActivityStepDraft,
    destination: SequenceDropDestination,
): SequenceTemplateDraft? {
    val duplicateIdentity =
        nodes.any { it.identity == step.identity } ||
            nodes.any {
                it is SequenceNodeDraft.Repeat &&
                    it.value.children.any { child -> child.identity == step.identity }
            }
    if (duplicateIdentity) {
        return null
    }
    if (destination.repeat == null) {
        if (destination.position !in 0..nodes.size) return null
        val node = SequenceNodeDraft.Step(step)
        return copy(nodes = nodes.toMutableList().apply { add(destination.position, node) }.reindexNodes())
    }
    var found = false
    val changed =
        nodes.map { node ->
            if (node is SequenceNodeDraft.Repeat && node.identity == destination.repeat) {
                if (destination.position !in 0..node.value.children.size) return null
                found = true
                SequenceNodeDraft.Repeat(
                    node.value.copy(
                        children =
                            node.value.children
                                .toMutableList()
                                .apply { add(destination.position, step) }
                                .reindexSteps(),
                    ),
                )
            } else {
                node
            }
        }
    return changed.takeIf { found }?.let { copy(nodes = it.reindexNodes()) }
}

private fun SequenceTemplateDraft.removeStep(identity: DraftIdentity<SequenceNodeId>): SequenceTemplateDraft? {
    if (nodes.any { it is SequenceNodeDraft.Step && it.identity == identity }) {
        return copy(nodes = nodes.filterNot { it.identity == identity }.reindexNodes())
    }
    var found = false
    val changed =
        nodes.map { node ->
            if (node is SequenceNodeDraft.Repeat && node.value.children.any { it.identity == identity }) {
                found = true
                SequenceNodeDraft.Repeat(
                    node.value.copy(
                        children =
                            node.value.children
                                .filterNot { it.identity == identity }
                                .reindexSteps(),
                    ),
                )
            } else {
                node
            }
        }
    return changed.takeIf { found }?.let { copy(nodes = it.reindexNodes()) }
}

private fun List<SequenceNodeDraft>.reindexNodes(): List<SequenceNodeDraft> =
    mapIndexed { position, node ->
        when (node) {
            is SequenceNodeDraft.Step -> SequenceNodeDraft.Step(node.value.copy(position = position))
            is SequenceNodeDraft.Repeat -> SequenceNodeDraft.Repeat(node.value.copy(position = position))
        }
    }

private fun List<ActivityStepDraft>.reindexSteps() = mapIndexed { position, step -> step.copy(position = position) }

private fun SequenceNodeDraft.steps(): List<ActivityStepDraft> =
    when (this) {
        is SequenceNodeDraft.Step -> listOf(value)
        is SequenceNodeDraft.Repeat -> value.children
    }
