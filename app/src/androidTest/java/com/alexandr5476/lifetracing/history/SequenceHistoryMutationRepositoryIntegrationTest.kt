package com.alexandr5476.lifetracing.history

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.LifeTracingRuntimeGraph
import com.alexandr5476.lifetracing.data.persistence.HistoryReadRepository
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalMode
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class SequenceHistoryMutationRepositoryIntegrationTest {
    @Test
    fun productionControllerPersistsAndCanonicallyReloadsEverySequenceHistoryTransition() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val suffix = System.nanoTime().toString()
            val base = Instant.now().minusSeconds(1_000)
            val live = LiveSessionRepository.create(context)
            when (live.getActiveSession()?.kind) {
                ActiveSessionKind.ACTIVITY -> live.completeActiveActivity(Instant.now())
                ActiveSessionKind.SEQUENCE -> live.endSequenceEarly(Instant.now())
                null -> Unit
            }
            val graph = LifeTracingRuntimeGraph.from(context)
            val history = HistoryReadRepository.create(context)

            val timing = completedSequence(context, "$suffix-timing", base, listOf(10))
            val timingController = graph.createSequenceHistoryDetailController(timing)
            val timingBefore = timingController.awaitDetail()
            timingController.dispatch(SequenceHistoryMutationAction.BeginTiming)
            val endTarget = SequenceHistoryTimestampTarget.RootEndedAt
            val endDraft = requireNotNull(timingController.state.value.timingDraft).timestamps.getValue(endTarget)
            val editedEnd = LocalDateTime.parse(endDraft.text).plusSeconds(1)
            timingController.dispatch(
                SequenceHistoryMutationAction.EditTimestamp(
                    endTarget,
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(editedEnd),
                ),
            )
            timingController.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            timingController.dispatch(SequenceHistoryMutationAction.ConfirmTiming)
            val timingAfter = timingController.awaitRefresh()
            assertEquals(timingBefore.root.completedAt.plusSeconds(1), timingAfter.root.completedAt)
            assertEquals(timingAfter, history.getSequenceDetail(timing))
            timingController.close()

            val deletion = completedSequence(context, "$suffix-delete", base.plusSeconds(30), listOf(10))
            val deletionController = graph.createSequenceHistoryDetailController(deletion)
            val deletionBefore = deletionController.awaitDetail()
            val deletionTarget = deletionBefore.occurrences.single().occurrenceId
            deletionController.dispatch(SequenceHistoryMutationAction.RequestChildDeletion(deletionTarget))
            deletionController.dispatch(SequenceHistoryMutationAction.ConfirmChildDeletion)
            val deletionAfter = deletionController.awaitRefresh()
            val tombstone = deletionAfter.occurrences.single()
            assertEquals(RuntimeOccurrenceStatus.DELETED_EXECUTION, tombstone.status)
            assertNull(tombstone.child)
            assertNotNull(tombstone.childMutationFacts)
            assertEquals(deletionAfter, history.getSequenceDetail(deletion))
            deletionController.dispatch(SequenceHistoryMutationAction.BeginStructuralRemoval(tombstone.occurrenceId))
            deletionController.dispatch(
                SequenceHistoryMutationAction.ChooseStructuralMode(
                    SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                ),
            )
            assertEquals(
                tombstone.childMutationFacts?.executionId,
                deletionController.state.value.structuralProposal
                    ?.command
                    ?.childExecutionId,
            )
            deletionController.dispatch(SequenceHistoryMutationAction.ConfirmStructuralRemoval)
            val deletionStructurallyRemoved = deletionController.awaitRefresh(2)
            assertTrue(deletionStructurallyRemoved.occurrences.isEmpty())
            assertEquals(deletionStructurallyRemoved, history.getSequenceDetail(deletion))
            deletionController.close()

            val leave = completedSequence(context, "$suffix-leave", base.plusSeconds(60), listOf(10))
            val leaveController = graph.createSequenceHistoryDetailController(leave)
            val leaveTarget =
                leaveController
                    .awaitDetail()
                    .occurrences
                    .single()
                    .occurrenceId
            leaveController.dispatch(SequenceHistoryMutationAction.BeginStructuralRemoval(leaveTarget))
            leaveController.dispatch(
                SequenceHistoryMutationAction.ChooseStructuralMode(
                    SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                ),
            )
            leaveController.dispatch(SequenceHistoryMutationAction.ConfirmStructuralRemoval)
            val leaveAfter = leaveController.awaitRefresh()
            assertTrue(leaveAfter.occurrences.isEmpty())
            assertEquals(leaveAfter, history.getSequenceDetail(leave))
            leaveController.close()

            val close = completedSequence(context, "$suffix-close", base.plusSeconds(90), listOf(10, 10))
            val closeController = graph.createSequenceHistoryDetailController(close)
            val closeBefore = closeController.awaitDetail()
            val removed = closeBefore.occurrences.first()
            val retained = closeBefore.occurrences.last()
            val removedSpan =
                requireNotNull(removed.completedAt).toEpochMilli() - requireNotNull(removed.enteredAt).toEpochMilli()
            closeController.dispatch(SequenceHistoryMutationAction.BeginStructuralRemoval(removed.occurrenceId))
            closeController.dispatch(
                SequenceHistoryMutationAction.ChooseStructuralMode(
                    SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                ),
            )
            requireNotNull(closeController.state.value.structuralProposal)
                .ownerlessPlacements.keys
                .forEach {
                    closeController.dispatch(
                        SequenceHistoryMutationAction.PlaceOwnerlessInterval(
                            it,
                            OwnerlessIntervalPlacement.TRANSLATED,
                        ),
                    )
                }
            closeController.dispatch(SequenceHistoryMutationAction.ConfirmStructuralRemoval)
            val closeAfter = closeController.awaitRefresh()
            assertEquals(listOf(retained.occurrenceId), closeAfter.occurrences.map { it.occurrenceId })
            assertEquals(closeBefore.root.completedAt.minusMillis(removedSpan), closeAfter.root.completedAt)
            assertEquals(
                requireNotNull(retained.enteredAt).minusMillis(removedSpan),
                closeAfter.occurrences.single().enteredAt,
            )
            assertEquals(closeAfter, history.getSequenceDetail(close))
            closeController.close()
        }

    private fun completedSequence(
        context: Context,
        suffix: String,
        startedAt: Instant,
        stepSeconds: List<Long>,
    ): com.alexandr5476.lifetracing.domain.SequenceExecutionId {
        val authoring = TemplateAuthoringRepository.create(context)
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("History step $suffix", null, TimeTrackingMode.STOPWATCH, null),
                createdAt = startedAt.minusSeconds(2),
            )
        val sequence =
            authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "History sequence $suffix",
                    null,
                    nodes =
                        stepSeconds.indices.map { position ->
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    DraftIdentity.New("$suffix-$position"),
                                    position,
                                    StepActivityDraft.FromTemplate(activity.id),
                                ),
                            )
                        },
                ),
                createdAt = startedAt.minusSeconds(1),
            )
        val live = LiveSessionRepository.create(context)
        var runtime =
            LibraryRepository
                .create(context)
                .startSequenceFromTemplate(
                    sequence.id,
                    startedAt,
                    startedAt,
                    ZoneOffset.UTC,
                    sequence.revision,
                )
        var at = startedAt
        stepSeconds.forEach { seconds ->
            at = at.plusSeconds(seconds)
            runtime = live.completeCurrentSequenceStep(requireNotNull(runtime.execution.currentOccurrenceId), at)
        }
        return runtime.execution.id
    }

    private suspend fun SequenceHistoryMutationController.awaitDetail(): SequenceHistoryDetail =
        withTimeout(5_000) {
            (state.first { it.load is HistoryDetailLoad.Content }.load as HistoryDetailLoad.Content).value
        }

    private suspend fun SequenceHistoryMutationController.awaitRefresh(generation: Long = 1): SequenceHistoryDetail =
        withTimeout(5_000) {
            val refreshed = state.first { it.refreshGeneration == generation && it.load is HistoryDetailLoad.Content }
            (refreshed.load as HistoryDetailLoad.Content).value
        }
}
