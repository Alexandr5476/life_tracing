package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.handleActivityHistoryDetailBack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActivityDeletionAdmissionTest : ActivityHistoryMutationTestFixture() {
    @Test
    fun confirmationIsRequiredAndCancellationRevokesWriterAuthority() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            assertEquals(0, fixture.deleteAttempts)
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            assertTrue(fixture.controller.state.value.deleteConfirmation)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Cancel)
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            assertEquals(0, fixture.deleteAttempts)
            fixture.close()
        }

    @Test
    fun deleteCannotDiscardCorrectionDraftOrOverlapProposal() =
        runBlocking {
            val fixture = fixture(detail(), overlaps = { _, _, _ -> true })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("retain"))
            val draft = fixture.controller.state.value.draft
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            assertSame(draft, fixture.controller.state.value.draft)
            assertFalse(fixture.controller.state.value.deleteConfirmation)
            assertEquals(0, fixture.deleteAttempts)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertTrue(fixture.controller.state.value.overlapWarning)
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            assertTrue(fixture.controller.state.value.overlapWarning)
            assertEquals(0, fixture.deleteAttempts)
            fixture.close()
        }

    @Test
    fun acceptedDeleteBlocksReleaseReentryAndAllPostCommitMutations() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fixture =
                fixture(detail(), delete = { id, expected, _ ->
                    assertEquals(detail().root.executionId, id)
                    assertEquals(token, expected)
                    gate.await()
                })
            fixture.awaitLoaded()
            val owner = ActivityHistoryMutationRouteSessionOwner()
            val session = owner.acquire(fixture.controller.executionId) { fixture.controller }
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Cancel)
            owner.release(session)
            assertSame(session, owner.activeSession)
            assertTrue(fixture.controller.handleBack())
            assertEquals(1, fixture.deleteAttempts)
            gate.complete(Unit)
            assertTrue(fixture.controller.state.value.deleted)
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Retry)
            assertFalse(fixture.controller.state.value.deleteConfirmation)
            assertNull(fixture.controller.state.value.draft)
            assertEquals(1, fixture.deleteAttempts)
            assertEquals(1L, fixture.controller.state.value.refreshGeneration)
            owner.release(session)
            fixture.close()
        }

    @Test
    fun staleConfirmationReloadsNewTokenAndRequiresExplicitNewRequest() =
        runBlocking {
            val initial = detail()
            val newer = initial.copy(updatedAt = token.plusSeconds(1))
            var canonical = initial
            val fixture =
                fixture(initial, read = { canonical }, delete = { _, _, _ ->
                    canonical = newer
                    throw ConcurrentModificationException()
                })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            val retained = fixture.admission().action(ActivityHistoryMutationAction.ConfirmDelete)
            retained()
            assertEquals(ActivityHistoryMutationIssue.STALE, fixture.controller.state.value.issue)
            assertEquals(newer, (fixture.controller.state.value.load as HistoryDetailLoad.Content).value)
            assertFalse(fixture.controller.state.value.deleteConfirmation)
            retained()
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            assertEquals(1, fixture.deleteAttempts)
            assertEquals(1L, fixture.controller.state.value.refreshGeneration)
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            assertTrue(fixture.controller.state.value.deleteConfirmation)
            fixture.close()
        }

    @Test
    fun retainedNativeConfirmationCannotSurviveCancelReplacementHideOrDisposal() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            val cancelled = fixture.admission().action(ActivityHistoryMutationAction.ConfirmDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Cancel)
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            cancelled()
            var visible = true
            val owner = fixture.admission { visible }
            val hidden = owner.action(ActivityHistoryMutationAction.ConfirmDelete)
            visible = false
            hidden()
            visible = true
            hidden()
            assertEquals(0, fixture.deleteAttempts)
            owner.attached = false
            hidden()
            assertEquals(0, fixture.deleteAttempts)
            fixture.admission().action(ActivityHistoryMutationAction.ConfirmDelete)()
            assertEquals(1, fixture.deleteAttempts)
            cancelled()
            assertEquals(1, fixture.deleteAttempts)
            fixture.close()
        }

    @Test
    fun plannedActivityAdmitsDeleteButNeverCorrectionAndUnavailableAdmitsNeither() =
        runBlocking {
            val planned = fixture(detail(planEntryId = PlanEntryId("fulfilled")))
            planned.awaitLoaded()
            planned.admission().action(ActivityHistoryMutationAction.BeginCorrection)()
            assertNull(planned.controller.state.value.draft)
            planned.admission().action(ActivityHistoryMutationAction.RequestDelete)()
            planned.admission().action(ActivityHistoryMutationAction.ConfirmDelete)()
            assertEquals(1, planned.deleteAttempts)
            assertEquals(0, planned.correctAttempts)
            planned.close()
            val unavailable = fixture(detail(), read = { null })
            unavailable.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            unavailable.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            unavailable.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            assertEquals(0, unavailable.deleteAttempts)
            assertNull(unavailable.controller.state.value.draft)
            unavailable.close()
        }

    @Test
    fun backImmediatelyAfterCommitDrainsRefreshOnceAndNeverPopsSibling() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            val owner = ActivityHistoryMutationRouteSessionOwner()
            val session = owner.acquire(fixture.controller.executionId) { fixture.controller }
            val stack: MutableList<androidx.navigation3.runtime.NavKey> =
                mutableListOf(
                    com.alexandr5476.lifetracing.DailyRoot,
                    com.alexandr5476.lifetracing.HistoryRoot,
                    com.alexandr5476.lifetracing.ActivityHistoryDetailRoot(fixture.controller.executionId.value),
                )
            var refreshes = 0
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            assertTrue(fixture.controller.state.value.deleted)
            stack.handleActivityHistoryDetailBack(fixture.controller.executionId.value, owner) { refreshes++ }
            session.deliverRefresh(fixture.controller.state.value.refreshGeneration) { refreshes++ }
            stack.add(com.alexandr5476.lifetracing.ActivityHistoryDetailRoot("sibling"))
            stack.handleActivityHistoryDetailBack(fixture.controller.executionId.value, owner) { refreshes++ }
            assertEquals(1, refreshes)
            assertEquals(
                "sibling",
                (stack.last() as com.alexandr5476.lifetracing.ActivityHistoryDetailRoot).executionId,
            )
            assertEquals(1, fixture.deleteAttempts)
            fixture.close()
        }

    @Test
    fun mismatchedDetailAndNativeExecutionNeverAcquireDeleteAuthority() =
        runBlocking {
            val original = detail()
            val foreign =
                original.copy(
                    root =
                        original.root.copy(
                            executionId =
                                com.alexandr5476.lifetracing.domain
                                    .ActivityExecutionId("sibling"),
                        ),
                )
            val fixture = fixture(original, read = { foreign })
            assertTrue(fixture.controller.state.value.load is HistoryDetailLoad.Failure)
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            assertEquals(0, fixture.deleteAttempts)
            fixture.close()
            val valid = fixture(original)
            valid.awaitLoaded()
            valid.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            ActivityCorrectionAdmission(
                foreign.root.executionId,
                valid.controller.state.value,
                {
                    valid.controller.state.value
                },
                { true },
                valid.controller::dispatch,
            ).action(ActivityHistoryMutationAction.ConfirmDelete)()
            assertEquals(0, valid.deleteAttempts)
            valid.close()
        }

    @Test
    fun deleteRequestDuringCorrectionWriterCannotAcquireConfirmation() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fixture = fixture(detail(), correct = { _, _, _ -> gate.await() })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            assertFalse(fixture.controller.state.value.deleteConfirmation)
            assertEquals(0, fixture.deleteAttempts)
            assertEquals(1, fixture.correctAttempts)
            gate.complete(Unit)
            fixture.close()
        }

    private fun Fixture.admission(ownsRoute: () -> Boolean = { true }) =
        ActivityCorrectionAdmission(
            controller.executionId,
            controller.state.value,
            { controller.state.value },
            ownsRoute,
            controller::dispatch,
        )
}
