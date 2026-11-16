@file:Suppress("MaxLineLength", "LargeClass", "LongMethod", "LongParameterList")

package com.alexandr5476.lifetracing.history

import androidx.navigation3.runtime.NavKey
import com.alexandr5476.lifetracing.HistoryRoot
import com.alexandr5476.lifetracing.SequenceHistoryDetailRoot
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPause
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryChildActivity
import com.alexandr5476.lifetracing.domain.SequenceHistoryChildMutationFacts
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrence
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrenceActivity
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalMode
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.handleSequenceHistoryDetailBack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ConcurrentModificationException
import kotlin.coroutines.CoroutineContext

class SequenceHistoryAdmissionTest {
    @Test
    fun equalReplacementCannotUseOldPublication() {
        val f = Fixture()
        val old = f.admission().action(SequenceHistoryMutationAction.BeginTiming)
        f.state = f.state.copy()
        old()
        assertTrue(f.actions.isEmpty())
        f.admission().action(SequenceHistoryMutationAction.BeginTiming)()
        assertEquals(listOf(SequenceHistoryMutationAction.BeginTiming), f.actions)
    }

    @Test
    fun lossPauseAndDisposalPermanentlyRevokeActionsAndBack() {
        val f = Fixture()
        val old = f.admission()
        var backs = 0
        f.owns = false
        old.action(SequenceHistoryMutationAction.BeginTiming)()
        f.owns = true
        old.action(SequenceHistoryMutationAction.BeginTiming)()
        old.back { backs++ }()
        val paused = f.admission()
        paused.revoke()
        paused.action(SequenceHistoryMutationAction.BeginTiming)()
        val disposed = f.admission()
        disposed.attached = false
        disposed.action(SequenceHistoryMutationAction.BeginTiming)()
        assertEquals(0, backs)
        assertTrue(f.actions.isEmpty())
        f.admission().action(SequenceHistoryMutationAction.BeginTiming)()
        assertEquals(1, f.actions.size)
    }

    @Test
    fun mismatchedNonterminalLoadingAndPendingCannotMutate() {
        val f = Fixture()
        val d = sequenceAdmissionDetail()
        listOf(
            f.state.copy(load = HistoryDetailLoad.Loading),
            f.state.copy(isMutating = true),
            f.state.copy(
                load =
                    HistoryDetailLoad.Content(
                        d.copy(root = d.root.copy(executionId = SequenceExecutionId("other"))),
                    ),
            ),
            f.state.copy(
                load = HistoryDetailLoad.Content(d.copy(root = d.root.copy(status = SequenceExecutionStatus.RUNNING))),
            ),
        ).forEach {
            f.state = it
            assertFalse(f.admission().enabled(SequenceHistoryMutationAction.BeginTiming))
            f.admission().action(SequenceHistoryMutationAction.BeginTiming)()
        }
        assertTrue(f.actions.isEmpty())
    }

    @Test
    fun inputAdvancesOnlyItsOwnCursorAndStopsAfterCancellation() {
        val f = Fixture()
        f.state = f.state.copy(timingDraft = SequenceHistoryProposalBuilder.timingDraft(sequenceAdmissionDetail()))
        val admission = f.admission()
        val input = admission.input(SequenceHistoryTimestampTarget.RootEndedAt)
        val sibling = admission.input(SequenceHistoryTimestampTarget.RootStartedAt)
        f.onDispatch = { f.state = f.state.copy(issue = SequenceHistoryMutationIssue.INVALID_DATE_TIME) }
        assertTrue(input("first"))
        f.onDispatch = { f.state = f.state.copy(issue = null) }
        assertTrue(input("second"))
        assertFalse(sibling("stale"))
        admission.action(SequenceHistoryMutationAction.ReviewTiming)()
        assertEquals(2, f.actions.size)
        f.state = f.state.copy(timingDraft = null)
        assertFalse(input("cancelled"))
    }

    @Test
    fun recoveryRetryIsReadOnlyAndIdleCannotConfirm() {
        val f = Fixture()
        listOf(
            SequenceHistoryMutationAction.Retry,
            SequenceHistoryMutationAction.Cancel,
            SequenceHistoryMutationAction.ConfirmTiming,
            SequenceHistoryMutationAction.ConfirmChildDeletion,
            SequenceHistoryMutationAction.ConfirmStructuralRemoval,
            SequenceHistoryMutationAction.ProceedOverlap,
        ).forEach { assertFalse(f.admission().enabled(it)) }
        f.state = f.state.copy(load = HistoryDetailLoad.Failure("read"), refreshGeneration = 1)
        assertTrue(f.admission().enabled(SequenceHistoryMutationAction.Retry))
        assertFalse(f.admission().enabled(SequenceHistoryMutationAction.BeginTiming))
    }

    @Test
    fun typedTargetsIgnoreDuplicateTitlesAndTimesAndRejectAbsentDeletedAndMismatchedChildren() {
        val base = SequenceAdmissionFacts.detail()
        val first = base.occurrences.first()
        val duplicate =
            first.copy(
                occurrenceId = SequenceOccurrenceId("duplicate"),
                child = null,
                childMutationFacts =
                    first.childMutationFacts!!.copy(
                        executionId = ActivityExecutionId("duplicate-child"),
                    ),
            )
        ControllerFixture(base.copy(occurrences = base.occurrences + duplicate)).use { f ->
            f.send(SequenceHistoryMutationAction.RequestChildDeletion(duplicate.occurrenceId))
            assertEquals(
                duplicate.occurrenceId,
                f.controller.state.value.childDeletionProposal
                    ?.occurrenceId,
            )
            assertEquals(
                ActivityExecutionId("duplicate-child"),
                f.controller.state.value.childDeletionProposal
                    ?.childExecutionId,
            )
            f.send(SequenceHistoryMutationAction.Cancel)
            f.send(SequenceHistoryMutationAction.RequestChildDeletion(SequenceOccurrenceId("absent")))
            assertNull(f.controller.state.value.childDeletionProposal)
            f.send(SequenceHistoryMutationAction.RequestChildDeletion(SequenceOccurrenceId("later")))
            assertNull(f.controller.state.value.childDeletionProposal)
            f.send(SequenceHistoryMutationAction.BeginStructuralRemoval(SequenceOccurrenceId("later")))
            assertEquals(SequenceOccurrenceId("later"), f.controller.state.value.structuralTarget)
            assertNull(f.controller.state.value.structuralProposal)
        }
        val invalid = base.copy(occurrences = listOf(first.copy(isDeletedFromHistory = true)))
        ControllerFixture(invalid).use { f ->
            assertFalse(f.admission().enabled(SequenceHistoryMutationAction.BeginStructuralRemoval(first.occurrenceId)))
        }
        val mismatch =
            base.copy(
                occurrences =
                    listOf(
                        first.copy(
                            childMutationFacts =
                                first.childMutationFacts!!.copy(
                                    executionId = ActivityExecutionId("wrong"),
                                ),
                        ),
                    ),
            )
        ControllerFixture(mismatch).use { f ->
            assertTrue(f.controller.state.value.load is HistoryDetailLoad.Unavailable)
            assertFalse(f.admission().enabled(SequenceHistoryMutationAction.BeginTiming))
        }
    }

    @Test
    fun timingReviewCancellationAndEditedOffsetsRevokeOldAuthority() {
        ControllerFixture().use { f ->
            f.send(SequenceHistoryMutationAction.BeginTiming)
            val target = SequenceHistoryTimestampTarget.RootEndedAt
            val input = f.admission().input(target)
            assertTrue(input("2026-01-01T00:49:00"))
            assertTrue(input("2026-01-01T00:48:00"))
            f.send(SequenceHistoryMutationAction.ReviewTiming)
            val confirm = f.admission().action(SequenceHistoryMutationAction.ConfirmTiming)
            assertFalse(input("2026-01-01T00:47:00"))
            f.send(SequenceHistoryMutationAction.Cancel)
            f.send(SequenceHistoryMutationAction.BeginTiming)
            confirm()
            assertEquals(0, f.writes)
            f.send(SequenceHistoryMutationAction.ReviewTiming)
            assertTrue(f.controller.state.value.noTimingChanges)
            assertFalse(f.admission().enabled(SequenceHistoryMutationAction.ConfirmTiming))
        }
        val f = Fixture()
        val target = SequenceHistoryTimestampTarget.RootEndedAt
        f.state =
            f.state.copy(
                timingDraft =
                    SequenceHistoryProposalBuilder.timingDraft(sequenceAdmissionDetail()).let {
                        it.copy(
                            timestamps =
                                it.timestamps +
                                    (
                                        target to
                                            SequenceHistoryTimestampDraft(
                                                "2026-10-25T02:30",
                                                validOffsets = listOf(ZoneOffset.ofHours(1), ZoneOffset.ofHours(2)),
                                            )
                                    ),
                        )
                    },
            )
        val offset =
            f.admission().action(
                SequenceHistoryMutationAction.SelectTimestampOffset(target, ZoneOffset.ofHours(1)),
            )
        f.state = f.state.copy(timingDraft = f.state.timingDraft!!.copy(timestamps = emptyMap()))
        offset()
        assertTrue(f.actions.isEmpty())
    }

    @Test
    fun dismissedDeletionAndStructuralReviewsCannotConfirmReplacement() {
        ControllerFixture().use { f ->
            f.send(SequenceHistoryMutationAction.RequestChildDeletion(SequenceOccurrenceId("target")))
            val delete = f.admission().action(SequenceHistoryMutationAction.ConfirmChildDeletion)
            f.send(SequenceHistoryMutationAction.Cancel)
            f.send(SequenceHistoryMutationAction.RequestChildDeletion(SequenceOccurrenceId("second-later")))
            delete()
            assertEquals(0, f.writes)
            f.send(SequenceHistoryMutationAction.Cancel)
            f.send(SequenceHistoryMutationAction.BeginStructuralRemoval(SequenceOccurrenceId("target")))
            val mode =
                f.admission().action(
                    SequenceHistoryMutationAction.ChooseStructuralMode(SequenceHistoryStructuralRemovalMode.LEAVE_GAP),
                )
            mode()
            val confirm = f.admission().action(SequenceHistoryMutationAction.ConfirmStructuralRemoval)
            f.send(SequenceHistoryMutationAction.Cancel)
            f.send(SequenceHistoryMutationAction.BeginStructuralRemoval(SequenceOccurrenceId("second-later")))
            mode()
            confirm()
            assertNull(f.controller.state.value.structuralProposal)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun ownerlessPlacementRequiresOutstandingTypedIntervalAndCompleteReview() {
        ControllerFixture().use { f ->
            f.send(SequenceHistoryMutationAction.BeginStructuralRemoval(SequenceOccurrenceId("target")))
            f.send(SequenceHistoryMutationAction.ChooseStructuralMode(SequenceHistoryStructuralRemovalMode.CLOSE_GAP))
            assertFalse(f.admission().enabled(SequenceHistoryMutationAction.ConfirmStructuralRemoval))
            assertFalse(
                f.admission().enabled(
                    SequenceHistoryMutationAction.PlaceOwnerlessInterval(
                        SequenceIntervalId("absent"),
                        OwnerlessIntervalPlacement.FIXED,
                    ),
                ),
            )
            val placement =
                f.admission().action(
                    SequenceHistoryMutationAction.PlaceOwnerlessInterval(
                        SequenceIntervalId("ownerless"),
                        OwnerlessIntervalPlacement.FIXED,
                    ),
                )
            placement()
            assertTrue(f.admission().enabled(SequenceHistoryMutationAction.ConfirmStructuralRemoval))
            f.send(SequenceHistoryMutationAction.Cancel)
            f.send(SequenceHistoryMutationAction.BeginStructuralRemoval(SequenceOccurrenceId("target")))
            f.send(SequenceHistoryMutationAction.ChooseStructuralMode(SequenceHistoryStructuralRemovalMode.CLOSE_GAP))
            placement()
            assertFalse(f.admission().enabled(SequenceHistoryMutationAction.ConfirmStructuralRemoval))
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun overlapApprovalCannotSurviveDismissalAndNewReview() {
        val base = SequenceAdmissionFacts.detail()
        ControllerFixture(
            base.copy(
                intervals =
                    base.intervals.map {
                        if (it.id ==
                            SequenceIntervalId("ownerless")
                        ) {
                            it.copy(startedAt = Instant.parse("2026-01-01T00:20:00Z"))
                        } else {
                            it
                        }
                    },
            ),
        ).use { f ->
            fun review() {
                f.send(SequenceHistoryMutationAction.BeginStructuralRemoval(SequenceOccurrenceId("target")))
                f.send(
                    SequenceHistoryMutationAction.ChooseStructuralMode(SequenceHistoryStructuralRemovalMode.LEAVE_GAP),
                )
            }
            review()
            assertTrue(f.controller.state.value.overlapWarning)
            assertFalse(f.admission().enabled(SequenceHistoryMutationAction.ConfirmStructuralRemoval))
            val approve = f.admission().action(SequenceHistoryMutationAction.ProceedOverlap)
            f.send(SequenceHistoryMutationAction.CancelOverlap)
            review()
            approve()
            assertTrue(f.controller.state.value.overlapWarning)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun pendingConfirmationBackAndRefreshUseSameExactSessionGate() {
        ControllerFixture().use { f ->
            val gate = CompletableDeferred<Unit>()
            f.write = { gate.await() }
            f.send(SequenceHistoryMutationAction.RequestChildDeletion(SequenceOccurrenceId("target")))
            val confirm = f.admission().action(SequenceHistoryMutationAction.ConfirmChildDeletion)
            confirm()
            confirm()
            val stack: MutableList<NavKey> = mutableListOf(HistoryRoot, SequenceHistoryDetailRoot("sequence"))
            var refreshes = 0
            stack.handleSequenceHistoryDetailBack("sequence", f.owner, { refreshes++ }, f.session)
            assertSame(f.session, f.owner.activeSession)
            assertEquals(2, stack.size)
            assertEquals(1, f.writes)
            gate.complete(Unit)
            stack.handleSequenceHistoryDetailBack("sequence", f.owner, { refreshes++ }, f.session)
            f.session.deliverRefresh(1) { refreshes++ }
            stack.handleSequenceHistoryDetailBack("sequence", f.owner, { refreshes++ }, f.session)
            assertEquals(1, refreshes)
            assertNull(f.owner.activeSession)
            assertEquals(listOf(HistoryRoot), stack)
        }
    }

    @Test
    fun sessionReplacementCannotNavigateAndReopeningHasFreshAuthority() {
        ControllerFixture().use { f ->
            var backs = 0
            val old = f.admission()
            f.owner.release(f.session)
            val replacement = f.owner.acquire(f.controller.executionId) { f.createController() }
            val stack: MutableList<NavKey> = mutableListOf(HistoryRoot, SequenceHistoryDetailRoot("sequence"))
            old.back { backs++ }()
            old.action(SequenceHistoryMutationAction.BeginTiming)()
            stack.handleSequenceHistoryDetailBack("sequence", f.owner, {}, f.session)
            assertEquals(0, backs)
            assertEquals(2, stack.size)
            assertSame(replacement, f.owner.activeSession)
            SequenceHistoryAdmission(
                replacement.executionId,
                replacement.controller.state.value,
                { replacement.controller.state.value },
                { f.owner.activeSession === replacement },
                replacement.controller::dispatch,
            ).action(SequenceHistoryMutationAction.BeginTiming)()
            assertNotNull(replacement.controller.state.value.timingDraft)
            f.owner.release(replacement)
        }
    }

    @Test
    fun postcommitReadFailureRetriesOnlyReadAndStaleRejectionDoesNotRefresh() {
        ControllerFixture().use { f ->
            f.write = { f.read = { error("read failed after commit") } }
            f.send(SequenceHistoryMutationAction.RequestChildDeletion(SequenceOccurrenceId("target")))
            val confirm = f.admission().action(SequenceHistoryMutationAction.ConfirmChildDeletion)
            confirm()
            assertEquals(1L, f.controller.state.value.refreshGeneration)
            assertEquals(SequenceHistoryMutationIssue.READ_FAILURE, f.controller.state.value.issue)
            assertTrue(f.admission().enabled(SequenceHistoryMutationAction.Retry))
            f.read = { SequenceAdmissionFacts.detail() }
            f.send(SequenceHistoryMutationAction.Retry)
            confirm()
            assertEquals(1, f.writes)
            assertTrue(f.controller.state.value.load is HistoryDetailLoad.Content)
            var deliveries = 0
            repeat(2) { f.session.deliverRefresh(1) { deliveries++ } }
            assertEquals(1, deliveries)
        }
        ControllerFixture().use { f ->
            f.write = { throw ConcurrentModificationException() }
            f.send(SequenceHistoryMutationAction.RequestChildDeletion(SequenceOccurrenceId("target")))
            f.send(SequenceHistoryMutationAction.ConfirmChildDeletion)
            assertEquals(0L, f.controller.state.value.refreshGeneration)
            assertEquals(SequenceHistoryMutationIssue.STALE, f.controller.state.value.issue)
        }
    }

    @Test
    fun nativeOffsetAuthorityIsClearedByNewTimestampText() {
        val base = SequenceAdmissionFacts.detail()
        ControllerFixture(base.copy(originalZoneId = ZoneId.of("Europe/Berlin"))).use { f ->
            val target = SequenceHistoryTimestampTarget.RootEndedAt
            f.send(SequenceHistoryMutationAction.BeginTiming)
            f.send(SequenceHistoryMutationAction.EditTimestamp(target, "2026-10-25T02:30"))
            f.send(SequenceHistoryMutationAction.ReviewTiming)
            assertEquals(SequenceHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME, f.controller.state.value.issue)
            val offsets =
                f.controller.state.value.timingDraft!!
                    .timestamps
                    .getValue(target)
                    .validOffsets
            assertEquals(2, offsets.size)
            assertFalse(
                f.admission().enabled(SequenceHistoryMutationAction.SelectTimestampOffset(target, ZoneOffset.UTC)),
            )
            f.send(SequenceHistoryMutationAction.SelectTimestampOffset(target, offsets.first()))
            val oldOffset =
                f.admission().action(
                    SequenceHistoryMutationAction.SelectTimestampOffset(target, offsets.last()),
                )
            val input = f.admission().input(target)
            assertTrue(input("2026-10-25T03:30"))
            oldOffset()
            val edited =
                f.controller.state.value.timingDraft!!
                    .timestamps
                    .getValue(target)
            assertNull(edited.selectedOffset)
            assertTrue(edited.validOffsets.isEmpty())
            assertFalse(
                f.admission().enabled(SequenceHistoryMutationAction.SelectTimestampOffset(target, offsets.last())),
            )
            assertFalse(
                f.admission().input(
                    SequenceHistoryTimestampTarget.IntervalEndedAt(SequenceIntervalId("absent")),
                )("stale"),
            )
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun transientBackConsumesEditorAndHiddenBackCannotPopForeground() {
        ControllerFixture().use { f ->
            val stack: MutableList<NavKey> = mutableListOf(HistoryRoot, SequenceHistoryDetailRoot("sequence"))
            f.send(SequenceHistoryMutationAction.BeginTiming)
            stack.handleSequenceHistoryDetailBack("sequence", f.owner, {}, f.session)
            assertNull(f.controller.state.value.timingDraft)
            assertSame(f.session, f.owner.activeSession)
            assertEquals(2, stack.size)
            stack.add(HistoryRoot)
            stack.handleSequenceHistoryDetailBack("sequence", f.owner, {}, f.session)
            assertEquals(3, stack.size)
            assertSame(f.session, f.owner.activeSession)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun entireActionFamilyRejectsLoadingAndWriterInFlight() {
        val actions =
            listOf(
                SequenceHistoryMutationAction.BeginTiming,
                SequenceHistoryMutationAction.EditTimestamp(
                    SequenceHistoryTimestampTarget.RootEndedAt,
                    "2026-01-01T00:49",
                ),
                SequenceHistoryMutationAction.SelectTimestampOffset(
                    SequenceHistoryTimestampTarget.RootEndedAt,
                    ZoneOffset.UTC,
                ),
                SequenceHistoryMutationAction.ReviewTiming,
                SequenceHistoryMutationAction.ProceedOverlap,
                SequenceHistoryMutationAction.CancelOverlap,
                SequenceHistoryMutationAction.ConfirmTiming,
                SequenceHistoryMutationAction.RequestChildDeletion(SequenceOccurrenceId("target")),
                SequenceHistoryMutationAction.ConfirmChildDeletion,
                SequenceHistoryMutationAction.BeginStructuralRemoval(SequenceOccurrenceId("target")),
                SequenceHistoryMutationAction.ChooseStructuralMode(SequenceHistoryStructuralRemovalMode.LEAVE_GAP),
                SequenceHistoryMutationAction.PlaceOwnerlessInterval(
                    SequenceIntervalId("ownerless"),
                    OwnerlessIntervalPlacement.FIXED,
                ),
                SequenceHistoryMutationAction.ConfirmStructuralRemoval,
                SequenceHistoryMutationAction.Cancel,
                SequenceHistoryMutationAction.Retry,
            )
        val f = Fixture()
        for (state in listOf(SequenceHistoryMutationState(), f.state.copy(isMutating = true))) {
            f.state = state
            val admission = f.admission()
            for (action in actions) {
                assertFalse(admission.enabled(action), action.toString())
                admission.action(action)()
            }
        }
        assertTrue(f.actions.isEmpty())
    }

    @Test
    fun immediateRepeatedReadFailurePublishesFreshRetryAuthority() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fail = true
        val controller =
            SequenceHistoryMutationController(
                scope,
                SequenceExecutionId("sequence"),
                { if (fail) error("read failure") else sequenceAdmissionDetail() },
                { _, _, _ -> error("unexpected writer") },
                { _, _, _ -> error("unexpected writer") },
                { _, _, _ -> error("unexpected writer") },
            )
        try {
            var presented = controller.state.value
            val observerDispatcher = QueuedDispatcher()
            val observerScope = CoroutineScope(scope.coroutineContext + observerDispatcher)
            observerScope.launch { controller.state.collect { presented = it } }
            observerDispatcher.drain()
            val first = presented
            val retry =
                SequenceHistoryAdmission(
                    controller.executionId,
                    first,
                    { controller.state.value },
                    { true },
                    controller::dispatch,
                )
            retry.action(SequenceHistoryMutationAction.Retry)()
            observerDispatcher.drain()
            assertNotSame(first, presented, "An equal-looking failed retry must publish fresh native controls")
            fail = false
            val fresh =
                SequenceHistoryAdmission(
                    controller.executionId,
                    presented,
                    { controller.state.value },
                    { true },
                    controller::dispatch,
                )
            fresh.action(SequenceHistoryMutationAction.Retry)()
            assertTrue(controller.state.value.load is HistoryDetailLoad.Content)
            assertEquals(0L, controller.state.value.refreshGeneration)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.add(block)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class ControllerFixture(
        private val canonical: SequenceHistoryDetail = SequenceAdmissionFacts.detail(),
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var read: suspend () -> SequenceHistoryDetail? = { canonical }
        var write: suspend () -> Unit = {}
        var writes = 0
        val owner = SequenceHistoryMutationRouteSessionOwner()
        val session = owner.acquire(canonical.root.executionId) { createController() }
        val controller get() = session.controller

        fun createController() =
            SequenceHistoryMutationController(
                scope,
                canonical.root.executionId,
                { read() },
                { _, _, _ ->
                    writes++
                    write()
                },
                { _, _, _ ->
                    writes++
                    write()
                },
                { _, _, _ ->
                    writes++
                    write()
                },
            )

        fun admission() =
            SequenceHistoryAdmission(
                controller.executionId,
                controller.state.value,
                { controller.state.value },
                { owner.activeSession === session },
                controller::dispatch,
            )

        fun send(action: SequenceHistoryMutationAction) = admission().action(action)()

        override fun close() {
            owner.activeSession?.let(owner::release)
            scope.cancel()
        }
    }

    private class Fixture {
        var state = SequenceHistoryMutationState(load = HistoryDetailLoad.Content(sequenceAdmissionDetail()))
        var owns = true
        var onDispatch: () -> Unit = {}
        val actions = mutableListOf<SequenceHistoryMutationAction>()

        fun admission() =
            SequenceHistoryAdmission(
                SequenceExecutionId("sequence"),
                state,
                { state },
                { owns },
                {
                    actions += it
                    onDispatch()
                },
            )
    }
}

internal fun sequenceAdmissionDetail() =
    SequenceHistoryDetail(
        CompletedSequenceHistoryRoot(
            SequenceExecutionId("sequence"),
            SequenceSnapshotId("snapshot"),
            LocalDate.parse("2026-01-01"),
            Instant.parse("2026-01-01T00:10:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"),
            SequenceExecutionStatus.COMPLETED,
            Duration.ofMinutes(10),
            Duration.ZERO,
            Duration.ofMinutes(10),
            null,
            "Sequence",
            null,
            java.time.ZoneOffset.UTC,
            null,
        ),
        Instant.parse("2026-01-01T01:00:00Z"),
        ZoneOffset.UTC,
        SequenceSnapshotSettings(
            true,
            Duration.ZERO,
            Duration.ZERO,
            true,
            true,
            false,
            true,
            true,
            NoLiveTimeAccounting.ACTIVE,
        ),
        emptyList(),
        emptyList(),
        emptyList(),
    )

private object SequenceAdmissionFacts {
    fun detail(): SequenceHistoryDetail {
        val target = occurrence(TARGET, 0, minute(0), minute(10), CHILD_TARGET, deleted = false)
        val tombstone = occurrence(LATER, 1, minute(10), minute(20), CHILD_LATER, deleted = true)
        val secondLater = occurrence(SECOND_LATER, 2, minute(20), minute(30), CHILD_SECOND_LATER, deleted = false)
        val skipped =
            SequenceHistoryOccurrence(
                SKIPPED,
                3,
                ActivitySnapshotId("snapshot-skipped"),
                SequenceSnapshotNodeId("source-skipped"),
                null,
                null,
                false,
                false,
                RuntimeOccurrenceStatus.SKIPPED,
                null,
                null,
                null,
                activity("skipped"),
                null,
            )
        return SequenceHistoryDetail(
            CompletedSequenceHistoryRoot(
                SEQUENCE,
                SequenceSnapshotId("snapshot"),
                LocalDate.parse("2026-01-01"),
                minute(50),
                minute(0),
                SequenceExecutionStatus.COMPLETED,
                Duration.ofMinutes(30),
                Duration.ofMinutes(20),
                Duration.ofMinutes(50),
                null,
                "Sequence",
                null,
                java.time.ZoneOffset.UTC,
                null,
            ),
            TOKEN,
            ZoneId.of("UTC"),
            SequenceSnapshotSettings(
                true,
                Duration.ZERO,
                Duration.ZERO,
                true,
                true,
                false,
                true,
                true,
                NoLiveTimeAccounting.ACTIVE,
            ),
            emptyList(),
            listOf(target, tombstone, secondLater, skipped),
            listOf(
                interval("target", SequenceIntervalKind.ACTIVE_STEP, minute(0), minute(10), TARGET),
                interval("later", SequenceIntervalKind.ACTIVE_STEP, minute(10), minute(20), LATER),
                interval("second-later", SequenceIntervalKind.ACTIVE_STEP, minute(20), minute(30), SECOND_LATER),
                interval("countdown", SequenceIntervalKind.TRANSITION_COUNTDOWN, minute(30), minute(32), SKIPPED),
                interval("fixed", SequenceIntervalKind.IMPLICIT_IDLE, minute(0), minute(0), null),
                interval("ownerless", SequenceIntervalKind.EXPLICIT_PAUSE, minute(35), minute(37), null),
            ),
        )
    }

    private fun occurrence(
        id: SequenceOccurrenceId,
        position: Int,
        entered: Instant,
        completed: Instant,
        childId: ActivityExecutionId,
        deleted: Boolean,
    ): SequenceHistoryOccurrence {
        val facts =
            SequenceHistoryChildMutationFacts(
                childId,
                entered,
                completed,
                listOf(
                    ActivityExecutionPause(
                        ActivityExecutionPauseId("pause-${id.value}"),
                        entered.plusSeconds(120),
                        entered.plusSeconds(180),
                    ),
                ),
            )
        return SequenceHistoryOccurrence(
            id,
            position,
            ActivitySnapshotId("snapshot-${id.value}"),
            SequenceSnapshotNodeId("source-${id.value}"),
            null,
            null,
            false,
            false,
            if (deleted) RuntimeOccurrenceStatus.DELETED_EXECUTION else RuntimeOccurrenceStatus.COMPLETED,
            entered,
            completed,
            null,
            activity(id.value),
            if (deleted) {
                null
            } else {
                SequenceHistoryChildActivity(
                    childId,
                    ActivityExecutionStatus.COMPLETED,
                    entered,
                    completed,
                    Duration.ofMinutes(9),
                    emptyList(),
                    java.time.ZoneOffset.UTC,
                    null,
                )
            },
            facts,
        )
    }

    private fun activity(id: String) =
        SequenceHistoryOccurrenceActivity(
            ActivitySnapshotId("snapshot-$id"),
            id,
            null,
            TimeTrackingMode.STOPWATCH,
            null,
            ActivityTemplateSettings(),
            null,
        )

    private fun interval(
        id: String,
        kind: SequenceIntervalKind,
        start: Instant,
        end: Instant,
        owner: SequenceOccurrenceId?,
    ) = SequenceInterval(SequenceIntervalId(id), kind, start, end, owner)

    private fun minute(value: Long): Instant = BASE.plusSeconds(value * 60)

    private val BASE = Instant.parse("2026-01-01T00:00:00Z")
    private val TOKEN = BASE.plusSeconds(3_600)

    private val SEQUENCE = SequenceExecutionId("sequence")
    private val TARGET = SequenceOccurrenceId("target")
    private val LATER = SequenceOccurrenceId("later")
    private val SECOND_LATER = SequenceOccurrenceId("second-later")
    private val SKIPPED = SequenceOccurrenceId("skipped")
    private val CHILD_TARGET = ActivityExecutionId("child-target")
    private val CHILD_LATER = ActivityExecutionId("child-later")
    private val CHILD_SECOND_LATER = ActivityExecutionId("child-second-later")
}
