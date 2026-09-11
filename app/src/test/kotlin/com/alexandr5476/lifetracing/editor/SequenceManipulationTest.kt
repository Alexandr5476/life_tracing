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

class SequenceManipulationTest {
    @Test
    fun allStepMoveShapesAndTopLevelRepeatReorderStayCanonical() {
        var draft = draft()
        val session = SequenceManipulationSession(draft, id("a"))

        draft = requireNotNull(session.move(draft, id("a"), SequenceDropDestination(position = 1)))
        assertEquals(listOf("r1", "a", "d", "r2"), draft.topIds())

        draft = requireNotNull(session.move(draft, id("c"), SequenceDropDestination(id("r1"), 0)))
        assertEquals(listOf("c", "b"), draft.children("r1"))

        draft = requireNotNull(session.move(draft, id("a"), SequenceDropDestination(id("r2"), 0)))
        assertEquals(listOf("a"), draft.children("r2"))

        draft = requireNotNull(session.move(draft, id("b"), SequenceDropDestination(position = 1)))
        assertEquals(listOf("r1", "b", "d", "r2"), draft.topIds())

        draft = requireNotNull(session.move(draft, id("c"), SequenceDropDestination(id("r2"), 1)))
        assertEquals(listOf("a", "c"), draft.children("r2"))

        draft = requireNotNull(session.move(draft, id("r2"), SequenceDropDestination(position = 0)))
        assertEquals("r2", draft.topIds().first())
        assertCanonical(draft)
    }

    @Test
    fun nestedRepeatIsRejectedWithoutChangingDraft() {
        val draft = draft()
        val session = SequenceManipulationSession(draft, id("a"))

        assertNull(session.move(draft, id("r1"), SequenceDropDestination(id("r2"), 0)))
        assertEquals(draft, session.baseline)
        assertFalse(session.uiState().canUndo)
    }

    @Test
    fun duplicateUndoRedoReusesTransientIdentityAndSelectionChangesKeepHistory() {
        val baseline = draft().copy(name = "ordinary unsaved name")
        val session = SequenceManipulationSession(baseline, id("a"))
        val duplicateIdentity = DraftIdentity.New("duplicate-once")
        var draft =
            requireNotNull(
                session.duplicate(
                    baseline,
                    id("a"),
                    duplicateIdentity,
                    SequenceDropDestination(id("r2"), 0),
                ),
            )

        assertEquals(listOf("duplicate-once"), draft.children("r2"))
        val duplicate = draft.step("duplicate-once")
        assertEquals(StepActivityDraft.Duplicate(SequenceNodeId("a")), duplicate.activity)
        assertTrue(session.uiState().canUndo)
        session.select(id("d"))
        assertTrue(session.uiState().canUndo)

        draft = requireNotNull(session.undo(draft))
        assertTrue(draft.children("r2").isEmpty())
        assertEquals(id("a"), session.uiState().selected)
        draft = requireNotNull(session.redo(draft))
        assertEquals(duplicateIdentity, draft.step("duplicate-once").identity)

        draft = requireNotNull(session.undo(draft))
        draft = requireNotNull(session.move(draft, id("d"), SequenceDropDestination(position = 0)))
        assertFalse(session.uiState().canRedo)
        assertEquals("ordinary unsaved name", session.baseline.name)
    }

    @Test
    fun mixedMovesAndDuplicateUndoRedoRestoreEveryExactContainerAndPosition() {
        val original = draft()
        val session = SequenceManipulationSession(original, id("a"))
        val duplicateIdentity = DraftIdentity.New("same-duplicate")

        val afterCrossContainerMove =
            requireNotNull(session.move(original, id("c"), SequenceDropDestination(id("r2"), 0)))
        val afterDuplicate =
            requireNotNull(
                session.duplicate(
                    afterCrossContainerMove,
                    id("a"),
                    duplicateIdentity,
                    SequenceDropDestination(id("r1"), 1),
                ),
            )
        val afterMoveOut =
            requireNotNull(session.move(afterDuplicate, id("b"), SequenceDropDestination(position = 2)))

        assertEquals(afterDuplicate, session.undo(afterMoveOut))
        assertEquals(afterCrossContainerMove, session.undo(afterDuplicate))
        assertEquals(original, session.undo(afterCrossContainerMove))
        assertEquals(afterCrossContainerMove, session.redo(original))
        val duplicateRedo = requireNotNull(session.redo(afterCrossContainerMove))
        assertEquals(afterDuplicate, duplicateRedo)
        assertEquals(duplicateIdentity, duplicateRedo.step("same-duplicate").identity)
        assertEquals(afterMoveOut, session.redo(duplicateRedo))
        assertCanonical(afterMoveOut)
    }

    @Test
    fun dropAtCurrentLogicalLocationIsNoOpWithoutHistory() {
        val draft = draft()
        val session = SequenceManipulationSession(draft, id("b"))

        assertEquals(draft, session.move(draft, id("b"), SequenceDropDestination(id("r1"), 0)))
        assertEquals(0, session.uiState().operationCount)
        assertFalse(session.uiState().canUndo)
    }

    @Test
    fun newStepCannotBeDuplicatedAndHistoryEntriesAreDeltas() {
        val newStep =
            ActivityStepDraft(
                DraftIdentity.New("new-step"),
                4,
                StepActivityDraft.Local(ActivitySnapshotDraft("New", null, TimeTrackingMode.STOPWATCH, null)),
            )
        val baseline = draft()
        val draft = baseline.copy(nodes = baseline.nodes + SequenceNodeDraft.Step(newStep))
        val session = SequenceManipulationSession(draft, newStep.identity)

        assertNull(
            session.duplicate(
                draft,
                newStep.identity,
                DraftIdentity.New("invalid-copy"),
                SequenceDropDestination(position = 0),
            ),
        )
        assertEquals(0, session.uiState().operationCount)
    }

    private fun draft() =
        SequenceTemplateDraft(
            "Workout",
            null,
            nodes =
                listOf(
                    SequenceNodeDraft.Step(step("a", 0)),
                    SequenceNodeDraft.Repeat(
                        SequenceRepeatBlockDraft(id("r1"), 1, 2, listOf(step("b", 0), step("c", 1))),
                    ),
                    SequenceNodeDraft.Step(step("d", 2)),
                    SequenceNodeDraft.Repeat(SequenceRepeatBlockDraft(id("r2"), 3, 3, emptyList())),
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
            ActivitySnapshotDraft(value, null, TimeTrackingMode.STOPWATCH, null),
        ),
    )

    private fun id(value: String) = DraftIdentity.Existing(SequenceNodeId(value))

    private fun SequenceTemplateDraft.topIds() = nodes.map { it.identity.text() }

    private fun SequenceTemplateDraft.children(repeat: String) =
        nodes
            .filterIsInstance<SequenceNodeDraft.Repeat>()
            .single { it.identity == id(repeat) }
            .value.children
            .map { it.identity.text() }

    private fun SequenceTemplateDraft.step(value: String) =
        nodes
            .flatMap {
                when (it) {
                    is SequenceNodeDraft.Step -> listOf(it.value)
                    is SequenceNodeDraft.Repeat -> it.value.children
                }
            }.single { it.identity.text() == value }

    private fun DraftIdentity<SequenceNodeId>.text() =
        when (this) {
            is DraftIdentity.Existing -> id.value
            is DraftIdentity.New -> key
        }

    private fun assertCanonical(draft: SequenceTemplateDraft) {
        assertEquals(draft.nodes.indices.toList(), draft.nodes.map(SequenceNodeDraft::position))
        draft.nodes.filterIsInstance<SequenceNodeDraft.Repeat>().forEach {
            assertEquals(
                it.value.children.indices
                    .toList(),
                it.value.children.map(ActivityStepDraft::position),
            )
        }
        val identities =
            draft.nodes.flatMap {
                listOf(it.identity) +
                    if (it is SequenceNodeDraft.Repeat) {
                        it.value.children.map(ActivityStepDraft::identity)
                    } else {
                        emptyList()
                    }
            }
        assertEquals(identities.size, identities.distinct().size)
    }
}
