package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SequenceManipulationDestinationsTest {
    @Test
    // Exhaustive bounded source/operation/slot matrix.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
    fun everyFinalSlotMatchesStructuralCommandsAndExactHierarchyForMoveAndDuplicate() {
        val draft = draft()
        val initial = hierarchy(draft)
        for (operation in SequenceDestinationOperation.entries) {
            for (source in listOf("a", "b", "c", "d", "r1", "r2")) {
                val repeat = source.startsWith("r")
                val offered = sequenceManipulationDestinations(draft, id(source), operation)
                if (repeat && operation == SequenceDestinationOperation.Duplicate) {
                    assertTrue(offered.isEmpty())
                    continue
                }
                val remaining = initial.mapValues { (_, values) -> values.toMutableList() }.toMutableMap()
                if (operation == SequenceDestinationOperation.Move) remaining.values.forEach { it.remove(source) }
                val containers = if (repeat) listOf("top") else listOf("top", "r1", "r2")
                val expectedSlots =
                    containers.flatMap { container ->
                        (0..remaining.getValue(container).size).map {
                            container to
                                it
                        }
                    }
                assertEquals(
                    expectedSlots,
                    offered.map {
                        (it.destination.repeat?.let(::name) ?: "top") to
                            it.destination.position
                    },
                )
                for (choice in offered) {
                    val session = SequenceManipulationSession(draft, id(source))
                    val duplicateIdentity = DraftIdentity.New("copy")
                    val result =
                        requireNotNull(
                            when (operation) {
                                SequenceDestinationOperation.Move -> session.move(draft, id(source), choice.destination)
                                SequenceDestinationOperation.Duplicate ->
                                    session.duplicate(
                                        draft,
                                        id(source),
                                        duplicateIdentity,
                                        choice.destination,
                                    )
                            },
                        )
                    val container = choice.destination.repeat?.let(::name) ?: "top"
                    val siblings = remaining.getValue(container)
                    assertEquals(siblings.getOrNull(choice.destination.position - 1), choice.previous?.let(::name))
                    assertEquals(siblings.getOrNull(choice.destination.position), choice.next?.let(::name))
                    val expected = remaining.mapValues { (_, values) -> values.toMutableList() }.toMutableMap()
                    expected.getValue(container).add(
                        choice.destination.position,
                        if (operation ==
                            SequenceDestinationOperation.Move
                        ) {
                            source
                        } else {
                            "copy"
                        },
                    )
                    assertEquals(expected, hierarchy(result), "$operation $source $choice")
                    assertEquals(result.nodes.indices.toList(), result.nodes.map { it.position })
                    result.nodes.filterIsInstance<SequenceNodeDraft.Repeat>().forEach {
                        assertEquals(
                            it.value.children.indices
                                .toList(),
                            it.value.children.map { child ->
                                child.position
                            },
                        )
                    }
                    session.record(
                        SequenceManipulationSnapshot(draft, emptyMap(), id(source)),
                        SequenceManipulationSnapshot(result, emptyMap(), session.selected),
                    )
                    if (draft != result) {
                        val undone =
                            requireNotNull(
                                session.undo(SequenceManipulationSnapshot(result, emptyMap(), session.selected)),
                            )
                        assertEquals(draft, undone.draft)
                        assertEquals(result, session.redo(undone)?.draft)
                    } else {
                        assertFalse(session.uiState().canUndo)
                    }
                }
            }
        }
    }

    @Test
    fun sameContainerMoveHasOneFewerSlotThanDuplicateAndUsesPostRemovalNeighbors() {
        val draft = draft()
        val move =
            sequenceManipulationDestinations(draft, id("b"), SequenceDestinationOperation.Move).filter {
                it.destination.repeat ==
                    id("r1")
            }
        val duplicate =
            sequenceManipulationDestinations(draft, id("b"), SequenceDestinationOperation.Duplicate).filter {
                it.destination.repeat ==
                    id("r1")
            }
        assertEquals(listOf(0, 1), move.map { it.destination.position })
        assertEquals(listOf(0, 1, 2), duplicate.map { it.destination.position })
        assertEquals(id("c"), move.last().previous)
        assertEquals(id("b"), duplicate[1].previous)
        assertEquals(id("c"), duplicate[1].next)
    }

    @Test
    fun missingSourcesAndWholeRepeatCopiesOfferNothing() {
        for (operation in SequenceDestinationOperation.entries) {
            assertTrue(sequenceManipulationDestinations(draft(), id("removed"), operation).isEmpty())
        }
        assertTrue(
            sequenceManipulationDestinations(draft(), id("r1"), SequenceDestinationOperation.Duplicate).isEmpty(),
        )
    }

    @Test
    @Suppress("LongMethod") // Rejected boundary cases share an unchanged draft and empty history.
    fun invalidPositionsRemovedContainersForeignAndStaleSourcesNeverAppendHistory() {
        val draft = draft()
        val session = SequenceManipulationSession(draft, id("a"))
        for (destination in listOf(
            SequenceDropDestination(position = -1),
            SequenceDropDestination(position = 99),
            SequenceDropDestination(id("r1"), -1),
            SequenceDropDestination(id("r1"), 99),
            SequenceDropDestination(id("removed-repeat"), 0),
            SequenceDropDestination(id("a"), 0),
        )) {
            assertNull(session.move(draft, id("a"), destination))
            assertNull(session.duplicate(draft, id("a"), DraftIdentity.New("copy"), destination))
            assertEquals(id("a"), session.selected)
            assertFalse(session.uiState().canUndo)
            assertEquals(0, session.uiState().operationCount)
        }
        assertNull(session.move(draft, id("r1"), SequenceDropDestination(id("r2"), 0)))
        assertNull(session.move(draft, id("removed-source"), SequenceDropDestination(position = 0)))
        val foreign = draft.copy(nodes = draft.nodes + SequenceNodeDraft.Step(step("foreign", 4)))
        assertNull(
            session.duplicate(foreign, id("foreign"), DraftIdentity.New("copy"), SequenceDropDestination(position = 0)),
        )
        val stale =
            draft.copy(
                nodes =
                    draft.nodes.map { node ->
                        if (node is SequenceNodeDraft.Step && node.identity == id("a")) {
                            SequenceNodeDraft.Step(
                                node.value.copy(
                                    activity =
                                        (node.value.activity as StepActivityDraft.Existing).copy(
                                            snapshotId = ActivitySnapshotId("replaced"),
                                        ),
                                ),
                            )
                        } else {
                            node
                        }
                    },
            )
        assertNull(session.duplicate(stale, id("a"), DraftIdentity.New("copy"), SequenceDropDestination(position = 0)))
        val pending =
            draft.copy(
                nodes =
                    draft.nodes +
                        SequenceNodeDraft.Step(
                            ActivityStepDraft(
                                DraftIdentity.New("pending"),
                                4,
                                StepActivityDraft.FromTemplate(
                                    com.alexandr5476.lifetracing.domain
                                        .ActivityTemplateId("template"),
                                ),
                            ),
                        ),
            )
        assertTrue(
            sequenceManipulationDestinations(
                pending,
                DraftIdentity.New("pending"),
                SequenceDestinationOperation.Duplicate,
            ).isEmpty(),
        )
        assertNull(
            session.duplicate(
                pending,
                DraftIdentity.New("pending"),
                DraftIdentity.New("copy"),
                SequenceDropDestination(position = 0),
            ),
        )
        assertEquals(draft, session.baseline)
        assertFalse(session.uiState().canUndo)
    }

    private fun draft() =
        SequenceTemplateDraft(
            "Sequence",
            null,
            nodes =
                listOf(
                    SequenceNodeDraft.Step(step("a", 0)),
                    SequenceNodeDraft.Repeat(
                        SequenceRepeatBlockDraft(id("r1"), 1, 2, listOf(step("b", 0), step("c", 1))),
                    ),
                    SequenceNodeDraft.Step(step("d", 2)),
                    SequenceNodeDraft.Repeat(SequenceRepeatBlockDraft(id("r2"), 3, 2, emptyList())),
                ),
        )

    private fun step(
        value: String,
        position: Int,
    ) = ActivityStepDraft(
        id(value),
        position,
        StepActivityDraft.Existing(
            ActivitySnapshotId("snapshot-$value"),
            ActivitySnapshotDraft("Same", null, TimeTrackingMode.STOPWATCH, null),
        ),
    )

    private fun id(value: String) = DraftIdentity.Existing(SequenceNodeId(value))

    private fun name(identity: DraftIdentity<SequenceNodeId>) =
        when (identity) {
            is DraftIdentity.Existing -> identity.id.value
            is DraftIdentity.New -> identity.key
        }

    private fun hierarchy(draft: SequenceTemplateDraft): Map<String, List<String>> =
        buildMap {
            put("top", draft.nodes.map { name(it.identity) })
            draft.nodes.filterIsInstance<SequenceNodeDraft.Repeat>().forEach { repeat ->
                put(name(repeat.identity), repeat.value.children.map { name(it.identity) })
            }
        }
}
