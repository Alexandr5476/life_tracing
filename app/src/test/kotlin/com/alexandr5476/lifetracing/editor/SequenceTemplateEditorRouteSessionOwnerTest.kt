package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant

class SequenceTemplateEditorRouteSessionOwnerTest {
    @Test
    fun dirtyDraftSurvivesRecreationUntilTheRouteReleasesIt() =
        runBlocking {
            val owner = SequenceTemplateEditorRouteSessionOwner()
            val session = owner.acquire(SequenceTemplateEditorTarget.New) { controller(this) }
            withTimeout(2_000) { session.controller.state.first { it.load is SequenceTemplateEditorLoad.Ready } }
            session.controller.updateDraft { it.copy(name = "Workout") }
            session.controller.updateNumberInput(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN, "", 0, 0) {}

            val recreated =
                owner.acquire(SequenceTemplateEditorTarget.New) { error("A recreated route must retain its session") }

            assertSame(session, recreated)
            val state = recreated.controller.state.value
            val draft = state.readyDraft()
            assertEquals("Workout", draft?.name)
            assertEquals("", state.inputText(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN, "0"))
            owner.release(recreated)
        }

    @Test
    fun manipulationDraftSelectionAndUndoRedoSurviveRouteRecreation() =
        runBlocking {
            val owner = SequenceTemplateEditorRouteSessionOwner()
            val session = owner.acquire(SequenceTemplateEditorTarget.New) { controller(this) }
            withTimeout(2_000) { session.controller.state.first { it.load is SequenceTemplateEditorLoad.Ready } }
            val a = DraftIdentity.New("a")
            val b = DraftIdentity.New("b")
            val c = DraftIdentity.New("c")
            session.controller.updateDraft {
                it.copy(
                    name = "Workout",
                    nodes = listOf(step(a, 0), step(b, 1), step(c, 2)),
                )
            }
            session.controller.enterManipulation(a)
            session.controller.moveManipulation(c, SequenceDropDestination(position = 0))
            session.controller.moveManipulation(b, SequenceDropDestination(position = 0))
            session.controller.undoManipulation()
            session.controller.selectManipulation(c)
            val before = session.controller.state.value

            val recreated =
                owner.acquire(SequenceTemplateEditorTarget.New) { error("Recreation must retain manipulation") }

            assertSame(session, recreated)
            assertEquals(
                before.readyDraft(),
                recreated.controller.state.value
                    .readyDraft(),
            )
            assertEquals(
                c,
                recreated.controller.state.value.manipulation
                    ?.selected,
            )
            assertEquals(
                true,
                recreated.controller.state.value.manipulation
                    ?.canUndo,
            )
            assertEquals(
                true,
                recreated.controller.state.value.manipulation
                    ?.canRedo,
            )
            owner.release(recreated)
        }

    private fun controller(scope: kotlinx.coroutines.CoroutineScope) =
        SequenceTemplateEditorController(
            scope,
            SequenceTemplateEditorTarget.New,
            { null },
            { emptyList() },
            { _, _, _ -> error("Save is not used") },
            { _, _, _, _ -> error("Save is not used") },
            { Instant.EPOCH },
        )

    private fun step(
        identity: DraftIdentity.New,
        position: Int,
    ) = SequenceNodeDraft.Step(
        ActivityStepDraft(
            identity,
            position,
            StepActivityDraft.Local(ActivitySnapshotDraft(identity.key, null, TimeTrackingMode.STOPWATCH, null)),
        ),
    )
}
