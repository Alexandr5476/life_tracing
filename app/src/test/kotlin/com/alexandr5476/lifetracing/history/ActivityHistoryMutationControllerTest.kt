package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class ActivityHistoryMutationControllerTest : ActivityHistoryMutationTestFixture() {
    @Test
    fun correctionUsesCanonicalTokenZoneSnapshotIdsAndPreservesMissingDistinctions() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            val draft = requireNotNull(fixture.controller.state.value.draft)
            assertEquals("2026-01-15T10:00:00", draft.startedText)
            assertEquals("2026-01-15T11:00:00", draft.completedText)

            fixture.controller.dispatch(ActivityHistoryMutationAction.EditNumber(numberId, "0"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.SetMissing(textId, false))
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditText(textId, ""))
            fixture.controller.dispatch(ActivityHistoryMutationAction.SelectCategory(categoryId, optionB))
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            fixture.awaitRefresh()

            val correction = fixture.corrections.single()
            assertEquals(token, correction.expectedUpdatedAt)
            assertEquals(berlin, correction.eventZoneId)
            assertEquals(
                ActivityHistoryTimeCorrection.Timed(
                    Instant.parse("2026-01-15T09:00:00Z"),
                    Instant.parse("2026-01-15T10:00:00Z"),
                ),
                correction.time,
            )
            assertEquals(
                listOf(
                    NumberExecutionValue(numberId, 0),
                    CategoryExecutionValue(categoryId, optionB),
                    TextExecutionValue(textId, ""),
                ),
                correction.values,
            )
            assertEquals("changed", correction.shortComment)
            fixture.close()
        }

    @Test
    fun noLiveHasNoStartAndPlanLinkedDetailIsDeleteOnly() =
        runBlocking {
            val noLive = fixture(detail(mode = TimeTrackingMode.NO_LIVE_TRACKING, startedAt = null))
            noLive.awaitLoaded()
            noLive.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            assertNull(
                noLive.controller.state.value.draft
                    ?.startedText,
            )
            noLive.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            noLive.controller.dispatch(ActivityHistoryMutationAction.Save)
            noLive.awaitRefresh()
            assertTrue(noLive.corrections.single().time is ActivityHistoryTimeCorrection.NoLive)
            noLive.close()

            val planned = fixture(detail(planEntryId = PlanEntryId("plan")))
            planned.awaitLoaded()
            planned.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            assertNull(planned.controller.state.value.draft)
            planned.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            assertTrue(planned.controller.state.value.deleteConfirmation)
            planned.close()
        }

    @Test
    fun correctionEntryCannotReplaceAnOpenDraftOrPendingOverlap() =
        runBlocking {
            val fixture = fixture(detail(), overlaps = { _, _, _ -> true })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("keep draft"))
            val draft = fixture.controller.state.value.draft
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            assertSame(draft, fixture.controller.state.value.draft)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            withTimeout(2_000) { fixture.controller.state.first { it.overlapWarning } }
            val pending = fixture.controller.state.value.draft
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            assertSame(pending, fixture.controller.state.value.draft)
            fixture.controller.dispatch(ActivityHistoryMutationAction.ProceedOverlap)
            fixture.awaitRefresh()
            assertEquals("keep draft", fixture.corrections.single().shortComment)
            fixture.close()
        }

    @Test
    fun currentNoOpStillUsesRepositoryValidationAndReloadsCanonicalDetail() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            fixture.awaitRefresh()

            assertEquals(1, fixture.correctAttempts)
            assertEquals(token, fixture.corrections.single().expectedUpdatedAt)
            assertEquals(token, (fixture.controller.state.value.load as HistoryDetailLoad.Content).value.updatedAt)
            fixture.close()
        }

    @Test
    fun staleNoOpReloadsCompetingCanonicalDetail() =
        runBlocking {
            val loaded = detail()
            val competing = loaded.copy(updatedAt = token.plusSeconds(1))
            val reads = ArrayDeque(listOf(loaded, competing))
            val fixture =
                fixture(
                    loaded,
                    read = { reads.removeFirst() },
                    correct = { _, _, _ -> throw ConcurrentModificationException() },
                )
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)

            withTimeout(2_000) {
                fixture.controller.state.first {
                    it.issue == ActivityHistoryMutationIssue.STALE &&
                        (it.load as? HistoryDetailLoad.Content)?.value?.updatedAt == competing.updatedAt
                }
            }
            assertEquals(1, fixture.correctAttempts)
            assertNull(fixture.controller.state.value.draft)
            fixture.close()
        }

    @Test
    fun strictGapOverlapOrderingAndFutureValidationUsePersistedEventZone() =
        runBlocking {
            val fixture =
                fixture(
                    detail(
                        startedAt = Instant.parse("2026-03-29T00:30:00Z"),
                        completedAt = Instant.parse("2026-03-29T01:30:00Z"),
                    ),
                )
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditStarted("2026-02-30 10:00"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertEquals(ActivityHistoryMutationIssue.INVALID_DATE_TIME, fixture.controller.state.value.issue)

            fixture.controller.dispatch(ActivityHistoryMutationAction.EditStarted("2026-03-29 02:30"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertEquals(ActivityHistoryMutationIssue.NONEXISTENT_LOCAL_TIME, fixture.controller.state.value.issue)

            fixture.controller.dispatch(ActivityHistoryMutationAction.EditStarted("2026-10-25 02:30"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditCompleted("2026-10-25 03:30"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            val overlap = requireNotNull(fixture.controller.state.value.draft)
            assertEquals(listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)), overlap.startedOffsets)
            assertEquals(ActivityHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME, fixture.controller.state.value.issue)
            fixture.controller.dispatch(ActivityHistoryMutationAction.SelectStartedOffset(ZoneOffset.ofHours(1)))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            fixture.awaitRefresh()
            assertEquals(
                Instant.parse("2026-10-25T01:30:00Z"),
                (fixture.corrections.single().time as ActivityHistoryTimeCorrection.Timed).startedAt,
            )
            fixture.close()
        }

    @Test
    fun correctionAcceptsOnlyMillisecondRepresentableWallClockPrecision() =
        runBlocking {
            val accepted =
                listOf(
                    "2026-01-15 10:15" to Instant.parse("2026-01-15T09:15:00Z"),
                    "2026-01-15 10:15:30" to Instant.parse("2026-01-15T09:15:30Z"),
                    "2026-01-15 10:15:30.1" to Instant.parse("2026-01-15T09:15:30.100Z"),
                    "2026-01-15 10:15:30.12" to Instant.parse("2026-01-15T09:15:30.120Z"),
                    "2026-01-15 10:15:30.123" to Instant.parse("2026-01-15T09:15:30.123Z"),
                )
            accepted.forEach { (text, expected) ->
                val fixture = fixture(detail())
                fixture.awaitLoaded()
                fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
                fixture.controller.dispatch(ActivityHistoryMutationAction.EditStarted(text))
                fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
                fixture.awaitRefresh()
                assertEquals(
                    expected,
                    (fixture.corrections.single().time as ActivityHistoryTimeCorrection.Timed).startedAt,
                )
                fixture.close()
            }

            listOf("2026-01-15 10:15:30.1234", "2026-01-15 10:15.1", "2026-02-30 10:15").forEach { text ->
                val fixture = fixture(detail())
                fixture.awaitLoaded()
                fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
                fixture.controller.dispatch(ActivityHistoryMutationAction.EditStarted(text))
                fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
                assertEquals(ActivityHistoryMutationIssue.INVALID_DATE_TIME, fixture.controller.state.value.issue)
                assertEquals(0, fixture.correctAttempts)
                fixture.close()
            }
        }

    @Test
    fun persistedMillisecondTimestampInitializesAndParsesWithoutLoss() =
        runBlocking {
            val persisted = Instant.parse("2026-01-15T09:00:00.123Z")
            val fixture = fixture(detail(startedAt = persisted))
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            assertEquals(
                "2026-01-15T10:00:00.123",
                fixture.controller.state.value.draft
                    ?.startedText,
            )
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            fixture.awaitRefresh()
            assertEquals(
                persisted,
                (fixture.corrections.single().time as ActivityHistoryTimeCorrection.Timed).startedAt,
            )
            fixture.close()
        }

    @Test
    fun timedOverlapWarnsThenCancelDoesNotWriteAndProceedCommitsTheValidatedCorrectionOnce() =
        runBlocking {
            val fixture =
                fixture(detail(), overlaps = { _, _, excluding -> excluding == ActivityExecutionId("execution") })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)

            withTimeout(2_000) { fixture.controller.state.first { it.overlapWarning } }
            assertEquals(1, fixture.overlapCalls.size)
            assertEquals(ActivityExecutionId("execution"), fixture.overlapCalls.single().excludingExecutionId)
            assertEquals(0, fixture.correctAttempts)
            fixture.controller.dispatch(ActivityHistoryMutationAction.CancelOverlap)
            assertFalse(fixture.controller.state.value.overlapWarning)
            assertEquals(0, fixture.correctAttempts)

            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            withTimeout(2_000) { fixture.controller.state.first { it.overlapWarning } }
            fixture.controller.dispatch(ActivityHistoryMutationAction.ProceedOverlap)
            fixture.awaitRefresh()
            assertEquals(1, fixture.correctAttempts)
            assertEquals(token, fixture.corrections.single().expectedUpdatedAt)
            fixture.close()
        }

    @Test
    fun timedNonOverlapCommitsWithoutWarning() =
        runBlocking {
            val fixture = fixture(detail(), overlaps = { _, _, _ -> false })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            fixture.awaitRefresh()

            assertEquals(1, fixture.overlapCalls.size)
            assertFalse(fixture.controller.state.value.overlapWarning)
            assertEquals(1, fixture.correctAttempts)
            fixture.close()
        }

    @Test
    fun persistedFallBackOccurrencesAreRetainedUntilTheTimestampIsEdited() =
        runBlocking {
            val early = Instant.parse("2026-10-25T00:30:00Z")
            val late = Instant.parse("2026-10-25T01:30:00Z")
            val timed = fixture(detail(startedAt = early, completedAt = late))
            timed.awaitLoaded()
            timed.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            val draft = requireNotNull(timed.controller.state.value.draft)
            assertEquals(ZoneOffset.ofHours(2), draft.startedOffset)
            assertEquals(ZoneOffset.ofHours(1), draft.completedOffset)
            assertEquals(listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)), draft.startedOffsets)
            assertEquals(listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)), draft.completedOffsets)
            timed.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            timed.controller.dispatch(ActivityHistoryMutationAction.Save)
            timed.awaitRefresh()
            assertEquals(ActivityHistoryTimeCorrection.Timed(early, late), timed.corrections.single().time)
            timed.close()

            listOf(early, late).forEach { completedAt ->
                val noLive = fixture(detail(TimeTrackingMode.NO_LIVE_TRACKING, null, completedAt))
                noLive.awaitLoaded()
                noLive.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
                assertEquals(
                    completedAt.atZone(berlin).offset,
                    noLive.controller.state.value.draft
                        ?.completedOffset,
                )
                noLive.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
                noLive.controller.dispatch(ActivityHistoryMutationAction.Save)
                noLive.awaitRefresh()
                assertEquals(ActivityHistoryTimeCorrection.NoLive(completedAt), noLive.corrections.single().time)
                noLive.close()
            }

            val edited = fixture(detail(startedAt = early, completedAt = late))
            edited.awaitLoaded()
            edited.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            edited.controller.dispatch(ActivityHistoryMutationAction.EditStarted("2026-10-25 02:45"))
            edited.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertEquals(ActivityHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME, edited.controller.state.value.issue)
            assertEquals(0, edited.correctAttempts)
            edited.close()
        }

    @Test
    fun deleteFailureKeepsConfirmationForRetryOrCancel() =
        runBlocking {
            var fail = true
            val fixture = fixture(detail(), delete = { _, _, _ -> if (fail) error("writer failed") })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            withTimeout(2_000) {
                fixture.controller.state.first { it.issue == ActivityHistoryMutationIssue.DELETE_FAILURE }
            }
            assertTrue(fixture.controller.state.value.deleteConfirmation)
            fail = false
            fixture.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            withTimeout(2_000) { fixture.controller.state.first { it.deleted } }
            assertEquals(2, fixture.deleteAttempts)
            fixture.close()

            val cancelled = fixture(detail(), delete = { _, _, _ -> error("writer failed") })
            cancelled.awaitLoaded()
            cancelled.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            cancelled.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            withTimeout(2_000) {
                cancelled.controller.state.first { it.issue == ActivityHistoryMutationIssue.DELETE_FAILURE }
            }
            cancelled.controller.dispatch(ActivityHistoryMutationAction.Cancel)
            assertFalse(cancelled.controller.state.value.deleteConfirmation)
            assertEquals(1, cancelled.deleteAttempts)
            cancelled.close()
        }

    @Test
    fun staleCorrectionDropsDraftReloadsCanonicalAndNeverReplays() =
        runBlocking {
            val canonical = detail()
            val fresh = canonical.copy(updatedAt = token.plusSeconds(1))
            val reads = ArrayDeque(listOf(canonical, fresh, fresh))
            val fixture =
                fixture(canonical, read = { reads.removeFirst() }, correct = {
                    _,
                    _,
                    _,
                    ->
                    if (reads.size == 2) throw ConcurrentModificationException()
                })
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("stale draft"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            withTimeout(2_000) {
                fixture.controller.state.first {
                    it.issue == ActivityHistoryMutationIssue.STALE &&
                        (it.load as? HistoryDetailLoad.Content)?.value?.updatedAt == fresh.updatedAt
                }
            }
            assertNull(fixture.controller.state.value.draft)
            assertEquals(1, fixture.correctAttempts)
            yield()
            assertEquals(1, fixture.correctAttempts)
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("reviewed"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            fixture.awaitRefresh()
            assertEquals(2, fixture.correctAttempts)
            assertEquals(fresh.updatedAt, fixture.corrections.last().expectedUpdatedAt)
            fixture.close()
        }

    @Test
    fun staleCorrectionOrDeleteThatLosesToSoftDeleteReloadsUnavailableAndRetryStaysUnavailable() =
        runBlocking {
            val correctionReads = ArrayDeque<ActivityHistoryDetail?>(listOf(detail(), null, null))
            val correction =
                fixture(
                    detail(),
                    read = { correctionReads.removeFirst() },
                    correct = { _, _, _ -> throw ConcurrentModificationException() },
                )
            correction.awaitLoaded()
            correction.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            correction.controller.dispatch(ActivityHistoryMutationAction.Save)
            withTimeout(2_000) {
                correction.controller.state.first {
                    it.issue == ActivityHistoryMutationIssue.STALE && it.load is HistoryDetailLoad.Unavailable
                }
            }
            correction.controller.dispatch(ActivityHistoryMutationAction.Retry)
            withTimeout(2_000) { correction.controller.state.first { it.load is HistoryDetailLoad.Unavailable } }
            assertEquals(1, correction.correctAttempts)
            correction.close()

            val deleteReads = ArrayDeque<ActivityHistoryDetail?>(listOf(detail(), null, null))
            val delete =
                fixture(
                    detail(),
                    read = { deleteReads.removeFirst() },
                    delete = { _, _, _ -> throw ConcurrentModificationException() },
                )
            delete.awaitLoaded()
            delete.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            delete.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            withTimeout(2_000) {
                delete.controller.state.first {
                    it.issue == ActivityHistoryMutationIssue.STALE && it.load is HistoryDetailLoad.Unavailable
                }
            }
            delete.controller.dispatch(ActivityHistoryMutationAction.Retry)
            withTimeout(2_000) { delete.controller.state.first { it.load is HistoryDetailLoad.Unavailable } }
            assertEquals(1, delete.deleteAttempts)
            delete.close()
        }

    @Test
    fun committedCorrectionCannotBeRedispatchedWhenCanonicalReloadFails() =
        runBlocking {
            var reads = 0
            val fixture =
                fixture(
                    detail(),
                    read = {
                        if (reads++ == 0) detail() else error("read failed after commit")
                    },
                )
            fixture.awaitLoaded()
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            withTimeout(2_000) { fixture.controller.state.first { it.load is HistoryDetailLoad.Failure } }

            assertEquals(1, fixture.correctAttempts)
            assertNull(fixture.controller.state.value.draft)
            assertEquals(1, fixture.controller.state.value.refreshGeneration)
            fixture.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertEquals(1, fixture.correctAttempts)
            fixture.close()
        }

    @Test
    fun routeSessionDeliversCommittedEffectsExactlyOnce() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            val session = ActivityHistoryMutationRouteSession(detail().root.executionId, fixture.controller)
            var refreshes = 0
            var deletes = 0

            session.deliverRefresh(1) { refreshes++ }
            session.deliverRefresh(1) { refreshes++ }
            session.deliverDelete { deletes++ }
            session.deliverDelete { deletes++ }

            assertEquals(1, refreshes)
            assertEquals(1, deletes)
            fixture.close()
        }

    @Test
    fun retainedOwnerKeepsDraftAcrossReacquisitionAndBackCancelsWithoutWriting() =
        runBlocking {
            val fixture = fixture(detail())
            fixture.awaitLoaded()
            val owner = ActivityHistoryMutationRouteSessionOwner()
            val first = owner.acquire(detail().root.executionId) { fixture.controller }
            fixture.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            fixture.controller.dispatch(ActivityHistoryMutationAction.EditComment("draft"))

            val reacquired = owner.acquire(detail().root.executionId) { error("must retain") }
            assertSame(first, reacquired)
            assertEquals(
                "draft",
                reacquired.controller.state.value.draft
                    ?.shortComment,
            )
            assertTrue(reacquired.controller.handleBack())
            assertNull(reacquired.controller.state.value.draft)
            assertEquals(0, fixture.correctAttempts)

            reacquired.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            assertTrue(reacquired.controller.handleBack())
            assertFalse(reacquired.controller.state.value.deleteConfirmation)
            assertEquals(0, fixture.deleteAttempts)
            owner.release(reacquired)
            fixture.scope.cancel()
        }

    @Test
    fun inFlightCorrectionAndDeleteAdmitOneWriteAndBackCannotCancelOwnership() =
        runBlocking {
            val correctionGate = CompletableDeferred<Unit>()
            val correction = fixture(detail(), correct = { _, _, _ -> correctionGate.await() })
            correction.awaitLoaded()
            correction.controller.dispatch(ActivityHistoryMutationAction.BeginCorrection)
            correction.controller.dispatch(ActivityHistoryMutationAction.EditComment("changed"))
            correction.controller.dispatch(ActivityHistoryMutationAction.Save)
            correction.controller.dispatch(ActivityHistoryMutationAction.Save)
            assertTrue(correction.controller.handleBack())
            assertTrue(correction.controller.state.value.isMutating)
            assertEquals(1, correction.correctAttempts)
            correctionGate.complete(Unit)
            correction.awaitRefresh()
            correction.close()

            val deleteGate = CompletableDeferred<Unit>()
            val deleting = fixture(detail(), delete = { _, _, _ -> deleteGate.await() })
            deleting.awaitLoaded()
            deleting.controller.dispatch(ActivityHistoryMutationAction.RequestDelete)
            deleting.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            deleting.controller.dispatch(ActivityHistoryMutationAction.ConfirmDelete)
            assertTrue(deleting.controller.handleBack())
            assertEquals(1, deleting.deleteAttempts)
            deleteGate.complete(Unit)
            withTimeout(2_000) { deleting.controller.state.first { it.deleted } }
            deleting.close()
        }
}
