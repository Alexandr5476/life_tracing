@file:Suppress("ReturnCount")

package com.alexandr5476.lifetracing.editor

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
)

internal class SequenceManipulationSession(
    val baseline: SequenceTemplateDraft,
    selected: DraftIdentity<SequenceNodeId>,
    private val duplicateSources: SequenceTemplateDraft = baseline,
) {
    private val undo = ArrayDeque<SequenceManipulationSnapshot>()
    private val redo = ArrayDeque<SequenceManipulationSnapshot>()
    var selected = selected
        private set

    fun select(identity: DraftIdentity<SequenceNodeId>) {
        selected = identity
    }

    fun move(
        draft: SequenceTemplateDraft,
        identity: DraftIdentity<SequenceNodeId>,
        destination: SequenceDropDestination,
    ): SequenceTemplateDraft? {
        draft.locationOf(identity) ?: return null
        val moved = draft.move(identity, destination) ?: return null
        if (moved == draft) {
            selected = identity
            return draft
        }
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
        val sourceActivity = source.activity as? StepActivityDraft.Existing ?: return null
        val originalConfiguration =
            (duplicateSources.step(sourceIdentity)?.activity as? StepActivityDraft.Existing)?.configuration
        val duplicate =
            ActivityStepDraft(
                duplicateIdentity,
                destination.position,
                StepActivityDraft.Duplicate(
                    sourceId,
                    sourceActivity.configuration,
                    sourceActivity.sourceTemplateId,
                    sourceActivity.sourceRevision,
                    sourceActivity.statisticsSeriesId,
                    sourceActivity.locallyModified || sourceActivity.configuration != originalConfiguration,
                ),
                source.overrides,
            )
        val changed = draft.insert(duplicate, destination) ?: return null
        selected = duplicateIdentity
        return changed
    }

    fun record(
        before: SequenceManipulationSnapshot,
        after: SequenceManipulationSnapshot,
    ) {
        if (before == after) return
        undo.addLast(before)
        redo.clear()
    }

    fun undo(current: SequenceManipulationSnapshot): SequenceManipulationSnapshot? {
        val target = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        selected = target.selected
        return target
    }

    fun redo(current: SequenceManipulationSnapshot): SequenceManipulationSnapshot? {
        val target = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        selected = target.selected
        return target
    }

    fun uiState() = SequenceManipulationUiState(selected, undo.isNotEmpty(), redo.isNotEmpty(), undo.size)
}

internal data class SequenceManipulationSnapshot(
    val draft: SequenceTemplateDraft,
    val textInputs: Map<String, SequenceEditorTextInput>,
    val selected: DraftIdentity<SequenceNodeId>,
)

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
