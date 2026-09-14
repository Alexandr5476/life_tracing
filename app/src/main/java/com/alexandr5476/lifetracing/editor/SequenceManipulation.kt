@file:Suppress("ReturnCount", "TooManyFunctions")

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
    private val undo = ArrayDeque<SequenceManipulationHistoryEntry>()
    private val redo = ArrayDeque<SequenceManipulationHistoryEntry>()
    private val pendingStructures = ArrayDeque<SequenceManipulationStructure>()
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
        pendingStructures.addLast(
            SequenceManipulationStructure.Move(
                identity,
                requireNotNull(draft.locationOf(identity)),
                destination,
            ),
        )
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
        pendingStructures.addLast(SequenceManipulationStructure.Duplicate(duplicate, destination))
        selected = duplicateIdentity
        return changed
    }

    fun record(
        before: SequenceManipulationSnapshot,
        after: SequenceManipulationSnapshot,
    ) {
        if (before == after) return
        val structure = pendingStructures.removeFirstOrNull()
        undo.addLast(SequenceManipulationHistoryEntry.create(before, after, structure))
        redo.clear()
    }

    fun undo(current: SequenceManipulationSnapshot): SequenceManipulationSnapshot? {
        val entry = undo.removeLastOrNull() ?: return null
        redo.addLast(entry)
        selected = entry.beforeSelected
        return entry.undo(current)
    }

    fun redo(current: SequenceManipulationSnapshot): SequenceManipulationSnapshot? {
        val entry = redo.removeLastOrNull() ?: return null
        undo.addLast(entry)
        selected = entry.afterSelected
        return entry.redo(current)
    }

    fun uiState() = SequenceManipulationUiState(selected, undo.isNotEmpty(), redo.isNotEmpty(), undo.size)

    internal fun retainedHistoryStepCount(): Int = undo.sumOf(SequenceManipulationHistoryEntry::retainedStepCount)
}

internal data class SequenceManipulationSnapshot(
    val draft: SequenceTemplateDraft,
    val textInputs: Map<String, SequenceEditorTextInput>,
    val selected: DraftIdentity<SequenceNodeId>,
)

private data class SequenceManipulationHistoryEntry(
    val beforeSelected: DraftIdentity<SequenceNodeId>,
    val afterSelected: DraftIdentity<SequenceNodeId>,
    val undoDelta: SequenceManipulationDelta,
    val redoDelta: SequenceManipulationDelta,
) {
    fun undo(current: SequenceManipulationSnapshot) = undoDelta.apply(current).copy(selected = beforeSelected)

    fun redo(current: SequenceManipulationSnapshot) = redoDelta.apply(current).copy(selected = afterSelected)

    fun retainedStepCount() = undoDelta.retainedStepCount() + redoDelta.retainedStepCount()

    companion object {
        fun create(
            before: SequenceManipulationSnapshot,
            after: SequenceManipulationSnapshot,
            structure: SequenceManipulationStructure?,
        ) = SequenceManipulationHistoryEntry(
            before.selected,
            after.selected,
            structure?.undoDelta() ?: SequenceManipulationDelta.between(after, before),
            structure?.redoDelta() ?: SequenceManipulationDelta.between(before, after),
        )
    }
}

private sealed interface SequenceManipulationStructure {
    fun undoDelta(): SequenceManipulationDelta

    fun redoDelta(): SequenceManipulationDelta

    data class Move(
        val identity: DraftIdentity<SequenceNodeId>,
        val from: SequenceDropDestination,
        val to: SequenceDropDestination,
    ) : SequenceManipulationStructure {
        override fun undoDelta() =
            SequenceManipulationDelta.Structural(SequenceManipulationOperation.Move(identity, from))

        override fun redoDelta() =
            SequenceManipulationDelta.Structural(SequenceManipulationOperation.Move(identity, to))
    }

    data class Duplicate(
        val step: ActivityStepDraft,
        val destination: SequenceDropDestination,
    ) : SequenceManipulationStructure {
        override fun undoDelta() =
            SequenceManipulationDelta.Structural(SequenceManipulationOperation.Remove(step.identity))

        override fun redoDelta() =
            SequenceManipulationDelta.Structural(SequenceManipulationOperation.Insert(step, destination))
    }
}

private sealed interface SequenceManipulationOperation {
    fun apply(draft: SequenceTemplateDraft): SequenceTemplateDraft

    data class Move(
        val identity: DraftIdentity<SequenceNodeId>,
        val destination: SequenceDropDestination,
    ) : SequenceManipulationOperation {
        override fun apply(draft: SequenceTemplateDraft) = requireNotNull(draft.move(identity, destination))
    }

    data class Insert(
        val step: ActivityStepDraft,
        val destination: SequenceDropDestination,
    ) : SequenceManipulationOperation {
        override fun apply(draft: SequenceTemplateDraft) = requireNotNull(draft.insert(step, destination))
    }

    data class Remove(
        val identity: DraftIdentity<SequenceNodeId>,
    ) : SequenceManipulationOperation {
        override fun apply(draft: SequenceTemplateDraft) = requireNotNull(draft.removeStep(identity))
    }
}

private sealed interface SequenceManipulationDelta {
    fun apply(snapshot: SequenceManipulationSnapshot): SequenceManipulationSnapshot

    fun retainedStepCount(): Int

    data class Structural(
        val operation: SequenceManipulationOperation,
    ) : SequenceManipulationDelta {
        override fun apply(snapshot: SequenceManipulationSnapshot): SequenceManipulationSnapshot =
            snapshot.copy(
                draft = operation.apply(snapshot.draft),
            )

        override fun retainedStepCount() = (operation as? SequenceManipulationOperation.Insert)?.step?.let { 1 } ?: 0
    }

    data class NonStructural(
        val draft: SequenceDraftDelta,
        val textInputs: Map<String, ValueChange<SequenceEditorTextInput?>>,
    ) : SequenceManipulationDelta {
        override fun apply(snapshot: SequenceManipulationSnapshot) =
            snapshot.copy(
                draft = draft.apply(snapshot.draft),
                textInputs =
                    textInputs.entries.fold(snapshot.textInputs) { values, (key, change) ->
                        change.value?.let { values + (key to it) } ?: values - key
                    },
            )

        override fun retainedStepCount() = draft.steps.size
    }

    companion object {
        fun between(
            before: SequenceManipulationSnapshot,
            after: SequenceManipulationSnapshot,
        ): SequenceManipulationDelta =
            NonStructural(
                SequenceDraftDelta.between(before.draft, after.draft),
                changes(before.textInputs, after.textInputs),
            )
    }
}

private data class SequenceDraftDelta(
    val name: ValueChange<String>?,
    val shortComment: ValueChange<String?>?,
    val noLiveTimeAccounting: ValueChange<com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting>?,
    val settings: ValueChange<com.alexandr5476.lifetracing.domain.SequenceTemplateSettings>?,
    val fields: ValueChange<List<com.alexandr5476.lifetracing.domain.SequenceFieldDraft>>?,
    val steps: Map<DraftIdentity<SequenceNodeId>, ValueChange<ActivityStepDraft>>,
    val repeatCounts: Map<DraftIdentity<SequenceNodeId>, ValueChange<Int>>,
) {
    fun apply(draft: SequenceTemplateDraft): SequenceTemplateDraft =
        draft.copy(
            name = name?.value ?: draft.name,
            shortComment = if (shortComment != null) shortComment.value else draft.shortComment,
            noLiveTimeAccounting = noLiveTimeAccounting?.value ?: draft.noLiveTimeAccounting,
            settings = settings?.value ?: draft.settings,
            fields = fields?.value ?: draft.fields,
            nodes =
                draft.nodes.map { node ->
                    when (node) {
                        is SequenceNodeDraft.Step ->
                            steps[node.identity]?.value?.let(SequenceNodeDraft::Step) ?: node
                        is SequenceNodeDraft.Repeat ->
                            SequenceNodeDraft.Repeat(
                                node.value.copy(
                                    repeatCount = repeatCounts[node.identity]?.value ?: node.value.repeatCount,
                                    children =
                                        node.value.children.map { step ->
                                            steps[step.identity]?.value ?: step
                                        },
                                ),
                            )
                    }
                },
        )

    companion object {
        fun between(
            before: SequenceTemplateDraft,
            after: SequenceTemplateDraft,
        ): SequenceDraftDelta {
            require(before.hasSameStructureAs(after)) { "Structural manipulation must use a structural delta" }
            return SequenceDraftDelta(
                change(before.name, after.name),
                change(before.shortComment, after.shortComment),
                change(before.noLiveTimeAccounting, after.noLiveTimeAccounting),
                change(before.settings, after.settings),
                change(before.fields, after.fields),
                changedValues(before.stepsByIdentity(), after.stepsByIdentity()),
                changedValues(before.repeatCounts(), after.repeatCounts()),
            )
        }
    }
}

private data class ValueChange<T>(
    val value: T,
)

private fun <T> change(
    before: T,
    after: T,
): ValueChange<T>? = after.takeIf { it != before }?.let(::ValueChange)

private fun <K, V> changes(
    before: Map<K, V>,
    after: Map<K, V>,
): Map<K, ValueChange<V?>> =
    (before.keys + after.keys)
        .associateWith { key -> ValueChange(after[key]) }
        .filter { (key, change) -> before[key] != change.value }

private fun <K, V> changedValues(
    before: Map<K, V>,
    after: Map<K, V>,
): Map<K, ValueChange<V>> =
    after
        .mapNotNull { (key, value) -> if (before[key] != value) key to ValueChange(value) else null }
        .toMap()

private fun SequenceTemplateDraft.hasSameStructureAs(other: SequenceTemplateDraft) =
    nodes.map { it.identity to it.position } == other.nodes.map { it.identity to it.position } &&
        nodes.filterIsInstance<SequenceNodeDraft.Repeat>().map { repeat ->
            repeat.identity to repeat.value.children.map { child -> child.identity to child.position }
        } ==
        other.nodes.filterIsInstance<SequenceNodeDraft.Repeat>().map { repeat ->
            repeat.identity to repeat.value.children.map { child -> child.identity to child.position }
        }

private fun SequenceTemplateDraft.stepsByIdentity(): Map<DraftIdentity<SequenceNodeId>, ActivityStepDraft> =
    nodes
        .flatMap { node ->
            when (node) {
                is SequenceNodeDraft.Step -> listOf(node.value)
                is SequenceNodeDraft.Repeat -> node.value.children
            }
        }.associateBy(ActivityStepDraft::identity)

private fun SequenceTemplateDraft.repeatCounts(): Map<DraftIdentity<SequenceNodeId>, Int> =
    nodes.filterIsInstance<SequenceNodeDraft.Repeat>().associate { it.identity to it.value.repeatCount }

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
