package com.alexandr5476.lifetracing.history

import androidx.navigation3.runtime.NavKey
import com.alexandr5476.lifetracing.ActivityHistoryDetailRoot
import com.alexandr5476.lifetracing.HistoryRoot
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.handleActivityHistoryDetailBack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class ActivityCorrectionAdmissionTest : ActivityHistoryMutationTestFixture() {
    @Test
    fun nativeInputAcceptsConsecutiveEventsButCancelRevokesEveryOldEditorCallback() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            val owner = fixture.admission()
            val input = owner.input { ActivityHistoryMutationAction.EditComment(it) }
            val oldSave = owner.action(ActivityHistoryMutationAction.Save)
            input("a")
            input("ab")
            assertEquals(
                "ab",
                fixture.controller.state.value.draft
                    ?.shortComment,
            )
            fixture.controller.dispatch(ActivityHistoryMutationAction.Cancel)
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            val fresh = fixture.controller.state.value.draft
            input("obsolete")
            oldSave()
            assertSame(fresh, fixture.controller.state.value.draft)
            assertEquals(0, fixture.correctAttempts)
            fixture.close()
        }

    @Test
    fun nativeAdmissionRequiresExactExecutionForegroundPublicationAndAttachment() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            var foreground = true
            val owner = fixture.admission { foreground }
            val begin = owner.action(ActivityHistoryMutationAction.BeginCorrection)
            foreground = false
            begin()
            assertNull(fixture.controller.state.value.draft)
            foreground = true
            owner.attached = false
            begin()
            assertNull(fixture.controller.state.value.draft)
            val wrong =
                ActivityCorrectionAdmission(
                    ActivityExecutionId("different-execution"),
                    fixture.controller.state.value,
                    { fixture.controller.state.value },
                    { true },
                    fixture.controller::dispatch,
                )
            wrong.action(ActivityHistoryMutationAction.BeginCorrection)()
            assertNull(fixture.controller.state.value.draft)
            fixture.admission().action(ActivityHistoryMutationAction.BeginCorrection)()
            begin()
            assertEquals(1L, fixture.controller.state.value.draftGeneration)
            fixture.close()
        }

    @Test
    fun nativePlanLinkedNoOpAndCommentActionsNeverAcquireCorrectionAuthority() =
        runBlocking {
            val fixture = fixture(detail(planEntryId = PlanEntryId("fulfilled")))
            fixture.awaitLoaded()
            val owner = fixture.admission()
            owner.action(ActivityHistoryMutationAction.BeginCorrection)()
            owner.input { ActivityHistoryMutationAction.EditComment(it) }("changed")
            owner.action(ActivityHistoryMutationAction.Save)()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertNull(fixture.controller.state.value.draft)
            assertEquals(0, fixture.correctAttempts)
            fixture.close()
        }

    @Test
    fun nativeFieldChoicesRejectForeignOptionsAndTypesAndValidationIdentifiesTheField() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            val owner = fixture.admission()
            val draft = fixture.controller.state.value.draft
            owner.action(ActivityHistoryMutationAction.SelectCategory(numberId, optionA))()
            owner.action(
                ActivityHistoryMutationAction.SelectCategory(categoryId, ActivitySnapshotCategoryOptionId("foreign")),
            )()
            owner.input { ActivityHistoryMutationAction.EditNumber(textId, it) }("0")
            assertSame(draft, fixture.controller.state.value.draft)
            owner.input { ActivityHistoryMutationAction.EditNumber(numberId, it) }("bad")
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            assertEquals(numberId, fixture.controller.state.value.issueFieldId)
            assertEquals(ActivityHistoryMutationIssue.INVALID_NUMBER, fixture.controller.state.value.issue)
            assertEquals(0, fixture.correctAttempts)
            fixture.close()
        }

    @Test
    fun oldOverlapApprovalCannotApproveASecondProposalAndDuplicateProceedWritesOnce() =
        runBlocking {
            val fixture = fixture(detail(), overlaps = { _, _, _ -> true })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            withTimeout(2_000) { fixture.controller.state.first { it.overlapWarning } }
            val oldProceed = fixture.admission().action(ActivityHistoryMutationAction.ProceedOverlap)
            fixture.admission().action(ActivityHistoryMutationAction.CancelOverlap)()
            fixture.admission().input { ActivityHistoryMutationAction.EditCompleted(it) }("2026-01-15 12:00")
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            withTimeout(2_000) { fixture.controller.state.first { it.overlapWarning } }
            oldProceed()
            assertEquals(0, fixture.correctAttempts)
            val proceed = fixture.admission().action(ActivityHistoryMutationAction.ProceedOverlap)
            proceed()
            proceed()
            fixture.awaitRefresh()
            assertEquals(1, fixture.correctAttempts)
            assertEquals(2, fixture.overlapCalls.size)
            assertTrue(fixture.overlapCalls.all { it.excludingExecutionId == fixture.controller.executionId })
            fixture.close()
        }

    @Test
    fun ordinaryFailureKeepsDraftAndOnlyCurrentExplicitRetryWritesAgain() =
        runBlocking {
            var fail = true
            val fixture = fixture(detail(), correct = { _, _, _ -> if (fail) error("ordinary failure") })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            val save = fixture.admission().action(ActivityHistoryMutationAction.Save)
            save()
            withTimeout(
                2_000,
            ) { fixture.controller.state.first { it.issue == ActivityHistoryMutationIssue.SAVE_FAILURE } }
            assertFalse(fixture.controller.state.value.correctionCommitted)
            assertTrue(fixture.controller.state.value.draft != null)
            save()
            assertEquals(1, fixture.correctAttempts)
            fail = false
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            fixture.awaitRefresh()
            assertEquals(2, fixture.correctAttempts)
            assertTrue(fixture.controller.state.value.correctionCommitted)
            fixture.close()
        }

    @Test
    fun repeatedImmediateReadFailuresPublishFreshRetryAuthority() =
        runBlocking {
            var reads = 0
            val fixture =
                fixture(detail(), read = {
                    reads++
                    error("read failed")
                })
            assertTrue(fixture.controller.state.value.load is HistoryDetailLoad.Failure)
            val first = fixture.admission().action(ActivityHistoryMutationAction.Retry)
            first()
            val second = fixture.admission().action(ActivityHistoryMutationAction.Retry)
            first()
            assertEquals(2, reads)
            second()
            assertEquals(3, reads)
            fixture.close()
        }

    @Test
    fun postCommitFailureRetryOnlyReadsAndPreservesExactlyOnceRefresh() =
        runBlocking {
            var reads = 0
            val fixture = fixture(detail(), read = { if (++reads == 2) error("canonical read failed") else detail() })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            val save = fixture.admission().action(ActivityHistoryMutationAction.Save)
            save()
            withTimeout(2_000) { fixture.controller.state.first { it.load is HistoryDetailLoad.Failure } }
            assertTrue(fixture.controller.state.value.correctionCommitted)
            assertNull(fixture.controller.state.value.draft)
            save()
            fixture.admission().action(ActivityHistoryMutationAction.Retry)()
            fixture.awaitLoaded()
            assertTrue(fixture.controller.state.value.correctionCommitted)
            assertEquals(1, fixture.correctAttempts)
            assertEquals(1L, fixture.controller.state.value.refreshGeneration)
            assertEquals(3, reads)
            fixture.close()
        }

    @Test
    fun mismatchedCanonicalDetailFailsAtReadBoundaryWithoutAnExecutableCorrection() =
        runBlocking {
            val initial = detail()
            val fixture =
                fixture(
                    initial,
                    read = { initial.copy(root = initial.root.copy(executionId = ActivityExecutionId("other"))) },
                )
            assertTrue(fixture.controller.state.value.load is HistoryDetailLoad.Failure)
            fixture.admission().action(ActivityHistoryMutationAction.BeginCorrection)()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            assertNull(fixture.controller.state.value.draft)
            assertEquals(0, fixture.correctAttempts)
            fixture.close()
        }

    @Test
    fun unchangedNativeCommentEventPreservesTheOriginalHistoricalComment() =
        runBlocking {
            val initial = detail().let { it.copy(root = it.root.copy(shortComment = "  ")) }
            val fixture = fixture(initial)
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.admission().input { ActivityHistoryMutationAction.EditComment(it) }("  ")
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            fixture.awaitRefresh()
            assertEquals("  ", fixture.corrections.single().shortComment)
            fixture.close()
        }

    @Test
    fun backAfterCommitBeforeCanonicalReloadDrainsRefreshExactlyOnceBeforeReleasingTheSession() =
        runBlocking {
            val reload = CompletableDeferred<ActivityHistoryDetail?>()
            var reads = 0
            val fixture = fixture(detail(), read = { if (++reads == 1) detail() else reload.await() })
            fixture.awaitLoaded()
            val owner = ActivityHistoryMutationRouteSessionOwner()
            val session = owner.acquire(fixture.controller.executionId) { fixture.controller }
            val backStack = mutableListOf<NavKey>(HistoryRoot, ActivityHistoryDetailRoot(session.executionId.value))
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertTrue(fixture.controller.state.value.correctionCommitted)
            assertEquals(HistoryDetailLoad.Loading, fixture.controller.state.value.load)
            var refreshes = 0
            backStack.handleActivityHistoryDetailBack("obsolete-route", owner) { refreshes++ }
            assertSame(session, owner.activeSession)
            backStack.handleActivityHistoryDetailBack(session.executionId.value, owner) { refreshes++ }
            assertEquals(1, refreshes)
            assertNull(owner.activeSession)
            assertEquals(HistoryRoot, backStack.last())
            session.deliverRefresh(fixture.controller.state.value.refreshGeneration) { refreshes++ }
            assertEquals(1, refreshes)
            assertEquals(1, fixture.correctAttempts)
            fixture.close()
        }

    @Test
    fun futureAndReversedIntervalsIdentifyTheRelevantNativeEndpointWithoutWriting() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.admission().input { ActivityHistoryMutationAction.EditCompleted(it) }("2026-12-02 12:00")
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            assertEquals(ActivityHistoryMutationIssue.FUTURE_COMPLETION, fixture.controller.state.value.issue)
            assertEquals(ActivityCorrectionEndpoint.COMPLETED, fixture.controller.state.value.issueEndpoint)
            fixture.admission().input { ActivityHistoryMutationAction.EditCompleted(it) }("2026-01-15 09:00")
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            assertEquals(ActivityHistoryMutationIssue.REVERSED_INTERVAL, fixture.controller.state.value.issue)
            assertEquals(ActivityCorrectionEndpoint.STARTED, fixture.controller.state.value.issueEndpoint)
            assertEquals(0, fixture.correctAttempts)
            fixture.close()
        }

    @Test
    fun editedOverlapEndpointsRequireIndependentFreshChoicesAndRetainMillisecondPrecision() =
        runBlocking {
            val initial =
                detail(
                    startedAt = Instant.parse("2026-10-25T00:10:00.123Z"),
                    completedAt = Instant.parse("2026-10-25T01:40:00.456Z"),
                )
            val canonical =
                initial.copy(
                    root = initial.root.copy(primaryLocalDate = java.time.LocalDate.parse("2026-10-25")),
                    updatedAt = correctionCommandTime.minusSeconds(1),
                )
            val fixture = fixture(canonical)
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.admission().input { ActivityHistoryMutationAction.EditStarted(it) }("2026-10-25 02:20:00.123")
            fixture.admission().input { ActivityHistoryMutationAction.EditCompleted(it) }("2026-10-25 02:40:00.456")
            assertNull(
                fixture.controller.state.value.draft
                    ?.startedOffset,
            )
            assertNull(
                fixture.controller.state.value.draft
                    ?.completedOffset,
            )
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            assertEquals(ActivityCorrectionEndpoint.COMPLETED, fixture.controller.state.value.issueEndpoint)
            fixture.admission().action(ActivityHistoryMutationAction.SelectCompletedOffset(ZoneOffset.ofHours(1)))()
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            assertEquals(ActivityCorrectionEndpoint.STARTED, fixture.controller.state.value.issueEndpoint)
            assertEquals(0, fixture.correctAttempts)
            fixture.admission().action(ActivityHistoryMutationAction.SelectStartedOffset(ZoneOffset.ofHours(2)))()
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            fixture.awaitRefresh()
            assertEquals(
                ActivityHistoryTimeCorrection.Timed(
                    Instant.parse("2026-10-25T00:20:00.123Z"),
                    Instant.parse("2026-10-25T01:40:00.456Z"),
                ),
                fixture.corrections.single().time,
            )
            fixture.close()
        }

    @Test
    fun untouchedHistoricalNumberKeepsStoredPrecisionAcrossMissingRestoreAndCommentCorrection() =
        runBlocking {
            val initial =
                detail().let { detail ->
                    detail.copy(
                        fields =
                            detail.fields.map { field ->
                                if (field.id == numberId) {
                                    field.copy(
                                        displayPrecision = 1,
                                        actualValue = ActivityHistoryActualValue.Number(12555),
                                    )
                                } else {
                                    field
                                }
                            },
                    )
                }
            val fixture = fixture(initial)
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            val displayed =
                fixture.controller.state.value.draft!!
                    .values
                    .getValue(numberId)
                    .numberText
            fixture.admission().input { ActivityHistoryMutationAction.EditNumber(numberId, it) }(displayed)
            fixture.admission().action(ActivityHistoryMutationAction.SetMissing(numberId, true))()
            fixture.admission().action(ActivityHistoryMutationAction.SetMissing(numberId, false))()
            fixture.admission().input { ActivityHistoryMutationAction.EditComment(it) }("comment only")
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            fixture.awaitRefresh()
            assertEquals(
                12555L,
                fixture.corrections
                    .single()
                    .values
                    .filterIsInstance<NumberExecutionValue>()
                    .single()
                    .scaledValue,
            )
            fixture.close()
        }

    @Test
    fun editedHistoricalNumberStillUsesCanonicalFrozenPrecisionValidation() =
        runBlocking {
            val initial =
                detail().let { detail ->
                    detail.copy(
                        fields =
                            detail.fields.map { field ->
                                if (field.id ==
                                    numberId
                                ) {
                                    field.copy(
                                        displayPrecision = 1,
                                        actualValue = ActivityHistoryActualValue.Number(12555),
                                    )
                                } else {
                                    field
                                }
                            },
                    )
                }
            val fixture = fixture(initial)
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.admission().input { ActivityHistoryMutationAction.EditNumber(numberId, it) }("12.555")
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            assertEquals(ActivityHistoryMutationIssue.INVALID_NUMBER, fixture.controller.state.value.issue)
            assertEquals(0, fixture.correctAttempts)
            fixture.admission().input { ActivityHistoryMutationAction.EditNumber(numberId, it) }("12.6")
            fixture.admission().action(ActivityHistoryMutationAction.Save)()
            fixture.awaitRefresh()
            assertEquals(
                12600L,
                fixture.corrections
                    .single()
                    .values
                    .filterIsInstance<NumberExecutionValue>()
                    .single()
                    .scaledValue,
            )
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
