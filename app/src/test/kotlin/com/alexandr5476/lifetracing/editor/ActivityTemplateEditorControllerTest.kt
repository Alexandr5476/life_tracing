package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.TemplateLibraryPlacement
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ActivityTemplateEditorControllerTest {
    @Test
    fun repeatedSaveDuringOneCreateCommitsExactlyOnce() =
        runBlocking {
            val releaseWrite = CompletableDeferred<Unit>()
            val committed = CompletableDeferred<Unit>()
            var writes = 0
            val controller =
                controller(
                    target = ActivityTemplateEditorTarget.New,
                    create = { draft, _, _ ->
                        writes++
                        releaseWrite.await()
                        template(draft)
                    },
                    onCommitted = { committed.complete(Unit) },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "Walk") }

            controller.save()
            controller.save()
            releaseWrite.complete(Unit)
            committed.await()

            assertEquals(1, writes)
            controller.close()
        }

    @Test
    fun dirtyBackRequiresDiscardButCleanBackDoesNot() =
        runBlocking {
            val controller = controller()
            controller.awaitReady()
            var exits = 0

            controller.requestBack { exits++ }
            assertEquals(1, exits)
            controller.updateDraft { it.copy(name = "Walk") }
            controller.requestBack { exits++ }

            assertTrue(controller.state.value.discardConfirmationVisible)
            controller.dismissDiscard()
            assertFalse(controller.state.value.discardConfirmationVisible)
            assertEquals("Walk", requireNotNull(controller.state.value.readyDraft()).name)
            controller.close()
        }

    @Test
    fun switchingAwayFromTimerClearsTheTargetWithoutWriting() =
        runBlocking {
            var writes = 0
            val controller =
                controller(
                    create = { draft, _, _ ->
                        writes++
                        template(draft)
                    },
                )
            controller.awaitReady()

            controller.setTimeTrackingMode(TimeTrackingMode.TIMER)
            controller.setTimerTargetSeconds("125")
            controller.setTimeTrackingMode(TimeTrackingMode.NO_LIVE_TRACKING)

            val draft = requireNotNull(controller.state.value.readyDraft())
            assertEquals(TimeTrackingMode.NO_LIVE_TRACKING, draft.timeTrackingMode)
            assertEquals(null, draft.timerTarget)
            assertEquals(0, writes)
            controller.close()
        }

    private fun CoroutineScope.controller(
        target: ActivityTemplateEditorTarget = ActivityTemplateEditorTarget.New,
        create: suspend (
            ActivityTemplateDraft,
            TemplateLibraryPlacement,
            Instant,
        ) -> ActivityTemplate = { draft, _, _ ->
            template(draft)
        },
        onCommitted: () -> Unit = {},
    ): ActivityTemplateEditorController =
        ActivityTemplateEditorController(
            this,
            target,
            { null },
            create,
            { _, _, draft, _ -> template(draft) },
            { Instant.EPOCH },
            onCommitted,
        )

    private suspend fun ActivityTemplateEditorController.awaitReady() =
        withTimeout(2_000) {
            state.first { it.load is ActivityTemplateEditorLoad.Ready }
        }

    private fun template(draft: ActivityTemplateDraft) =
        ActivityTemplate(
            ActivityTemplateId("template"),
            draft.name,
            draft.shortComment,
            draft.timeTrackingMode,
            draft.timerTarget,
            com.alexandr5476.lifetracing.domain
                .StatisticsSeriesId("series"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            settings = draft.settings,
        )
}
