package com.alexandr5476.lifetracing.editor

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

            val recreated =
                owner.acquire(SequenceTemplateEditorTarget.New) { error("A recreated route must retain its session") }

            assertSame(session, recreated)
            val state = recreated.controller.state.value
            val draft = state.readyDraft()
            assertEquals("Workout", draft?.name)
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
}
