package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TemplateLibraryPlacement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant

class ActivityTemplateEditorRouteSessionOwnerTest {
    @Test
    fun unsavedDraftSurvivesHostRecreationUntilTheEditorRoutePops() =
        runBlocking {
            val owner = ActivityTemplateEditorRouteSessionOwner()
            val first = owner.acquire(ActivityTemplateEditorTarget.New) { controller(this) }
            first.controller.awaitReady()
            first.controller.updateDraft { it.copy(name = "Walk") }

            val recreated =
                owner.acquire(
                    ActivityTemplateEditorTarget.New,
                ) { error("A recreated route must reuse its draft") }

            assertSame(first, recreated)
            assertEquals(
                "Walk",
                recreated.controller.state.value
                    .readyDraft()
                    ?.name,
            )
            owner.release(recreated)
        }

    @Test
    fun inFlightNewSaveSurvivesRecreationAndDeliversOneCommitWithoutABlankSecondEditor() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val durableTemplates = mutableListOf<ActivityTemplateDraft>()
            val owner = ActivityTemplateEditorRouteSessionOwner()
            val session =
                owner.acquire(ActivityTemplateEditorTarget.New) {
                    controller(this) { draft, _, _ ->
                        started.complete(Unit)
                        release.await()
                        durableTemplates += draft
                        template(draft)
                    }
                }
            session.controller.awaitReady()
            session.controller.updateDraft { it.copy(name = "Walk") }
            session.controller.save()
            started.await()

            val recreated =
                owner.acquire(
                    ActivityTemplateEditorTarget.New,
                ) { error("A save must not recreate a blank editor") }
            assertSame(session, recreated)
            release.complete(Unit)
            session.controller.awaitCommitted()
            var commitDeliveries = 0
            recreated.exitPolicy.deliverCommitted { commitDeliveries++ }
            recreated.exitPolicy.deliverCommitted { commitDeliveries++ }

            assertEquals(listOf("Walk"), durableTemplates.map(ActivityTemplateDraft::name))
            assertEquals(1, commitDeliveries)
            assertSame(
                session,
                owner.acquire(ActivityTemplateEditorTarget.New) {
                    error("Committed route is still active until popped")
                },
            )
            owner.release(session)
        }

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        create: suspend (
            ActivityTemplateDraft,
            TemplateLibraryPlacement,
            Instant,
        ) -> ActivityTemplate = { draft, _, _ -> template(draft) },
    ) = ActivityTemplateEditorController(
        scope,
        ActivityTemplateEditorTarget.New,
        { null },
        create,
        { _, _, draft, _ -> template(draft) },
        { Instant.EPOCH },
    )

    private suspend fun ActivityTemplateEditorController.awaitReady() {
        withTimeout(2_000) { state.first { it.load is ActivityTemplateEditorLoad.Ready } }
    }

    private suspend fun ActivityTemplateEditorController.awaitCommitted() {
        withTimeout(2_000) { state.first { it.save is ActivityTemplateEditorSave.Committed } }
    }

    private fun template(draft: ActivityTemplateDraft) =
        ActivityTemplate(
            ActivityTemplateId("template"),
            draft.name,
            draft.shortComment,
            draft.timeTrackingMode,
            draft.timerTarget,
            StatisticsSeriesId("series"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            settings = draft.settings,
        )
}
