@file:Suppress("LongParameterList", "MaxLineLength")

package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPause
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
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
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.ConcurrentModificationException

class SequenceHistoryProposalBuilderTest {
    @Test
    fun timingBuildsExactChangedFactsAndCompleteIntervalGraph() {
        val detail = detail()
        val original = SequenceHistoryProposalBuilder.timingDraft(detail)
        val edits =
            mapOf(
                SequenceHistoryTimestampTarget.RootEndedAt to minute(39),
                SequenceHistoryTimestampTarget.OccurrenceEnteredAt(LATER) to minute(11),
                SequenceHistoryTimestampTarget.ChildStartedAt(TARGET) to minute(1),
                SequenceHistoryTimestampTarget.IntervalStartedAt(LATER_INTERVAL) to minute(11),
            )
        val draft =
            original.copy(
                timestamps =
                    original.timestamps.mapValues { (target, value) ->
                        edits[target]?.let { value.copy(text = it.toString().removeSuffix("Z")) } ?: value
                    },
            )

        val proposal =
            (
                SequenceHistoryProposalBuilder.timing(
                    detail,
                    draft,
                ) as SequenceHistoryTimingBuildResult.Ready
            ).proposal

        assertEquals(detail.updatedAt, proposal.correction.expectedUpdatedAt)
        assertEquals(minute(39), proposal.correction.endedAt)
        assertEquals(listOf(LATER), proposal.correction.occurrenceTimings.map { it.occurrenceId })
        assertEquals(
            ActivityHistoryTimeCorrection.Timed(minute(1), minute(10)),
            proposal.correction.childTimings
                .single()
                .time,
        )
        assertEquals(detail.intervals.map { it.id }, proposal.correction.finalIntervals?.map { it.id })
        assertEquals(
            minute(11),
            proposal.correction.finalIntervals
                ?.single { it.id == LATER_INTERVAL }
                ?.startedAt,
        )
        assertFalse(proposal.hasActiveIntervalOverlap)
    }

    @Test
    fun overlapAndDstOutcomesAreExplicit() {
        val detail = detail()
        val original = SequenceHistoryProposalBuilder.timingDraft(detail)
        val overlapDraft =
            original.copy(
                timestamps =
                    original.timestamps +
                        (
                            SequenceHistoryTimestampTarget.IntervalStartedAt(LATER_INTERVAL) to
                                SequenceHistoryTimestampDraft("2026-01-01T00:05:00")
                        ),
            )
        assertTrue(
            (SequenceHistoryProposalBuilder.timing(detail, overlapDraft) as SequenceHistoryTimingBuildResult.Ready)
                .proposal.hasActiveIntervalOverlap,
        )
        assertEquals(
            HistoricalLocalDateTimeResolution.Nonexistent,
            resolveHistoricalLocalDateTime("2026-03-29T02:30", BERLIN, null),
        )
        val ambiguous = resolveHistoricalLocalDateTime("2026-10-25T02:30", BERLIN, null)
        assertTrue(ambiguous is HistoricalLocalDateTimeResolution.Ambiguous)
        assertEquals(2, (ambiguous as HistoricalLocalDateTimeResolution.Ambiguous).offsets.size)
    }

    @Test
    fun leaveAndCloseGapPreserveIdentityAndRequireAmbiguousOwnerlessChoice() {
        val detail = detail()
        val leave =
            requireNotNull(
                SequenceHistoryProposalBuilder.structural(
                    detail,
                    TARGET,
                    SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                ),
            )
        assertTrue(leave.isConfirmable)
        assertEquals(detail.root.completedAt, leave.command?.finalEndedAt)
        assertEquals(detail.intervals.filter { it.occurrenceId != TARGET }, leave.command?.finalIntervals)

        val unresolved =
            requireNotNull(
                SequenceHistoryProposalBuilder.structural(
                    detail,
                    TARGET,
                    SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                ),
            )
        assertFalse(unresolved.isConfirmable)
        assertEquals(mapOf(OWNERLESS to null), unresolved.ownerlessPlacements)

        val close =
            requireNotNull(
                SequenceHistoryProposalBuilder.structural(
                    detail,
                    TARGET,
                    SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                    mapOf(OWNERLESS to OwnerlessIntervalPlacement.TRANSLATED),
                ),
            )
        val command = requireNotNull(close.command)
        assertEquals(minute(30), command.finalEndedAt)
        assertEquals(
            detail.intervals.filter { it.occurrenceId != TARGET }.map { it.id },
            command.finalIntervals.map { it.id },
        )
        assertEquals(minute(15), command.finalIntervals.single { it.id == OWNERLESS }.startedAt)
        assertEquals(minute(0), command.occurrenceTimings.single().enteredAt)
        assertEquals(CHILD_LATER, command.childTimings.single().executionId)
        assertEquals(
            minute(2),
            command.childTimings
                .single()
                .pauses
                .single()
                .startedAt,
        )
        assertNull(detail.occurrences.single { it.occurrenceId == SKIPPED }.childMutationFacts)
    }

    @Test
    fun controllerReloadsAfterSuccessAndStaleWithoutRetrying() =
        runBlocking {
            val canonical = detail()
            val refreshed = canonical.copy(updatedAt = TOKEN.plusSeconds(1))
            val reads = ArrayDeque(listOf(canonical, refreshed))
            var commands = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller =
                SequenceHistoryMutationController(
                    scope,
                    SEQUENCE,
                    { reads.removeFirst() },
                    { _, _, _ -> commands++ },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ -> error("unexpected") },
                    { TOKEN.plusSeconds(2) },
                )
            awaitLoaded(controller)
            controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            controller.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            controller.dispatch(SequenceHistoryMutationAction.ProceedOverlap)
            controller.dispatch(SequenceHistoryMutationAction.ConfirmTiming)
            withTimeout(
                2_000,
            ) { controller.state.first { it.refreshGeneration == 1L && it.load is HistoryDetailLoad.Content } }
            assertEquals(1, commands)
            assertEquals(refreshed, (controller.state.value.load as HistoryDetailLoad.Content).value)
            controller.close()
            scope.cancel()

            val staleReads = ArrayDeque(listOf(canonical, refreshed))
            val staleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val stale =
                SequenceHistoryMutationController(
                    staleScope,
                    SEQUENCE,
                    { staleReads.removeFirst() },
                    { _, _, _ ->
                        commands++
                        throw ConcurrentModificationException()
                    },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ -> error("unexpected") },
                )
            awaitLoaded(stale)
            stale.dispatch(SequenceHistoryMutationAction.BeginTiming)
            stale.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            stale.dispatch(SequenceHistoryMutationAction.ProceedOverlap)
            stale.dispatch(SequenceHistoryMutationAction.ConfirmTiming)
            withTimeout(2_000) {
                stale.state.first {
                    it.issue == SequenceHistoryMutationIssue.STALE &&
                        it.load is HistoryDetailLoad.Content
                }
            }
            assertEquals(2, commands)
            assertNull(stale.state.value.timingDraft)
            assertEquals(0, stale.state.value.refreshGeneration)
            stale.close()
            staleScope.cancel()
        }

    @Test
    fun cancellationWritesNothingAndInFlightRejectsDuplicateAndConflictingActions() =
        runBlocking {
            val canonical = detail()
            val gate = CompletableDeferred<Unit>()
            var deletions = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller =
                SequenceHistoryMutationController(
                    scope,
                    SEQUENCE,
                    { canonical },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ ->
                        deletions++
                        gate.await()
                    },
                    { _, _, _ -> error("unexpected") },
                )
            awaitLoaded(controller)

            controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            controller.dispatch(SequenceHistoryMutationAction.Cancel)
            controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            controller.dispatch(
                SequenceHistoryMutationAction.EditTimestamp(
                    SequenceHistoryTimestampTarget.IntervalStartedAt(LATER_INTERVAL),
                    "2026-01-01T00:05:00",
                ),
            )
            controller.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            assertTrue(controller.state.value.overlapWarning)
            controller.dispatch(SequenceHistoryMutationAction.CancelOverlap)
            controller.dispatch(SequenceHistoryMutationAction.Cancel)
            controller.dispatch(SequenceHistoryMutationAction.RequestChildDeletion(TARGET))
            controller.dispatch(SequenceHistoryMutationAction.Cancel)
            controller.dispatch(SequenceHistoryMutationAction.BeginStructuralRemoval(TARGET))
            controller.dispatch(
                SequenceHistoryMutationAction.ChooseStructuralMode(
                    SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                ),
            )
            controller.dispatch(SequenceHistoryMutationAction.Cancel)
            assertEquals(0, deletions)

            controller.dispatch(SequenceHistoryMutationAction.RequestChildDeletion(TARGET))
            controller.dispatch(SequenceHistoryMutationAction.ConfirmChildDeletion)
            assertTrue(controller.state.value.isMutating)
            controller.dispatch(SequenceHistoryMutationAction.ConfirmChildDeletion)
            controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            assertEquals(1, deletions)
            assertNull(controller.state.value.timingDraft)
            gate.complete(Unit)
            withTimeout(2_000) {
                controller.state.first { it.refreshGeneration == 1L && !it.isMutating }
            }
            assertEquals(1, deletions)
            controller.close()
            scope.cancel()
        }

    @Test
    fun staleDeletionAndStructuralCommandsClearProposalReloadAndNeverRetry() =
        runBlocking {
            suspend fun verify(
                prepare: (SequenceHistoryMutationController) -> Unit,
                deleteFailure: Boolean,
            ) {
                val canonical = detail()
                val refreshed = canonical.copy(updatedAt = TOKEN.plusSeconds(1))
                val reads = ArrayDeque(listOf(canonical, refreshed))
                var attempts = 0
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val controller =
                    SequenceHistoryMutationController(
                        scope,
                        SEQUENCE,
                        { reads.removeFirst() },
                        { _, _, _ -> error("unexpected") },
                        { _, _, _ ->
                            if (deleteFailure) {
                                attempts++
                                throw ConcurrentModificationException()
                            }
                            error("unexpected")
                        },
                        { _, _, _ ->
                            if (!deleteFailure) {
                                attempts++
                                throw ConcurrentModificationException()
                            }
                            error("unexpected")
                        },
                    )
                awaitLoaded(controller)
                prepare(controller)
                withTimeout(2_000) {
                    controller.state.first {
                        it.issue == SequenceHistoryMutationIssue.STALE &&
                            it.load is HistoryDetailLoad.Content
                    }
                }
                assertEquals(1, attempts)
                assertNull(controller.state.value.childDeletionProposal)
                assertNull(controller.state.value.structuralProposal)
                assertEquals(0, controller.state.value.refreshGeneration)
                controller.close()
                scope.cancel()
            }

            verify(
                {
                    it.dispatch(SequenceHistoryMutationAction.RequestChildDeletion(TARGET))
                    it.dispatch(SequenceHistoryMutationAction.ConfirmChildDeletion)
                },
                deleteFailure = true,
            )
            verify(
                {
                    it.dispatch(SequenceHistoryMutationAction.BeginStructuralRemoval(TARGET))
                    it.dispatch(
                        SequenceHistoryMutationAction.ChooseStructuralMode(
                            SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                        ),
                    )
                    it.dispatch(SequenceHistoryMutationAction.ConfirmStructuralRemoval)
                },
                deleteFailure = false,
            )
        }

    @Test
    fun genericReadAndCommandFailuresStayDistinctAndSessionDeliversEachGenerationOnce() =
        runBlocking {
            val failedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val readFailure =
                SequenceHistoryMutationController(
                    failedScope,
                    SEQUENCE,
                    { error("read") },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ -> error("unexpected") },
                )
            withTimeout(2_000) {
                readFailure.state.first { it.issue == SequenceHistoryMutationIssue.READ_FAILURE }
            }
            readFailure.close()
            failedScope.cancel()

            val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val commandFailure =
                SequenceHistoryMutationController(
                    commandScope,
                    SEQUENCE,
                    { detail() },
                    { _, _, _ -> error("command") },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ -> error("unexpected") },
                )
            awaitLoaded(commandFailure)
            commandFailure.dispatch(SequenceHistoryMutationAction.BeginTiming)
            commandFailure.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            commandFailure.dispatch(SequenceHistoryMutationAction.ConfirmTiming)
            withTimeout(2_000) {
                commandFailure.state.first { it.issue == SequenceHistoryMutationIssue.TIMING_FAILURE }
            }
            assertTrue(commandFailure.state.value.load is HistoryDetailLoad.Content)

            var deliveries = 0
            val session = SequenceHistoryMutationRouteSession(SEQUENCE, commandFailure)
            session.deliverRefresh(1) { deliveries++ }
            session.deliverRefresh(1) { deliveries++ }
            session.deliverRefresh(2) { deliveries++ }
            assertEquals(2, deliveries)
            commandFailure.close()
            commandScope.cancel()
        }

    private suspend fun awaitLoaded(controller: SequenceHistoryMutationController) {
        withTimeout(2_000) { controller.state.first { it.load is HistoryDetailLoad.Content } }
    }

    private fun detail(): SequenceHistoryDetail {
        val target = occurrence(TARGET, 0, minute(0), minute(10), CHILD_TARGET, deleted = false)
        val tombstone = occurrence(LATER, 1, minute(10), minute(20), CHILD_LATER, deleted = true)
        val skipped =
            SequenceHistoryOccurrence(
                SKIPPED,
                2,
                ActivitySnapshotId("snapshot-skipped"),
                null,
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
                minute(40),
                minute(0),
                SequenceExecutionStatus.COMPLETED,
                Duration.ofMinutes(20),
                Duration.ofMinutes(20),
                Duration.ofMinutes(40),
                null,
                "Sequence",
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
            listOf(target, tombstone, skipped),
            listOf(
                interval("target", SequenceIntervalKind.ACTIVE_STEP, minute(0), minute(10), TARGET),
                interval("later", SequenceIntervalKind.ACTIVE_STEP, minute(10), minute(20), LATER),
                interval("countdown", SequenceIntervalKind.TRANSITION_COUNTDOWN, minute(20), minute(22), SKIPPED),
                interval("fixed", SequenceIntervalKind.IMPLICIT_IDLE, minute(0), minute(2), null),
                interval("ownerless", SequenceIntervalKind.EXPLICIT_PAUSE, minute(25), minute(27), null),
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
            null,
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

    companion object {
        private val BASE = Instant.parse("2026-01-01T00:00:00Z")
        private val TOKEN = BASE.plusSeconds(3_600)
        private val BERLIN = ZoneId.of("Europe/Berlin")
        private val SEQUENCE = SequenceExecutionId("sequence")
        private val TARGET = SequenceOccurrenceId("target")
        private val LATER = SequenceOccurrenceId("later")
        private val SKIPPED = SequenceOccurrenceId("skipped")
        private val CHILD_TARGET = ActivityExecutionId("child-target")
        private val CHILD_LATER = ActivityExecutionId("child-later")
        private val LATER_INTERVAL = SequenceIntervalId("later")
        private val OWNERLESS = SequenceIntervalId("ownerless")
    }
}
