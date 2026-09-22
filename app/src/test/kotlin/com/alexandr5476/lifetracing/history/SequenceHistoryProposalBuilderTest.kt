@file:Suppress("LargeClass", "LongMethod", "LongParameterList", "MaxLineLength")

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
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.ConcurrentModificationException

class SequenceHistoryProposalBuilderTest {
    @Test
    fun timingBuildsExactChangedFactsAndCompleteIntervalGraph() {
        val detail = detail()
        val original = SequenceHistoryProposalBuilder.timingDraft(detail)
        val edits =
            mapOf(
                SequenceHistoryTimestampTarget.RootEndedAt to minute(49),
                SequenceHistoryTimestampTarget.OccurrenceEnteredAt(TARGET) to minute(1),
                SequenceHistoryTimestampTarget.ChildStartedAt(TARGET) to minute(1),
                SequenceHistoryTimestampTarget.IntervalStartedAt(TARGET_INTERVAL) to minute(1),
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
        assertEquals(minute(49), proposal.correction.endedAt)
        assertEquals(listOf(TARGET), proposal.correction.occurrenceTimings.map { it.occurrenceId })
        assertEquals(
            ActivityHistoryTimeCorrection.Timed(minute(1), minute(10)),
            proposal.correction.childTimings
                .single()
                .time,
        )
        assertEquals(detail.intervals.map { it.id }, proposal.correction.finalIntervals?.map { it.id })
        assertEquals(
            minute(1),
            proposal.correction.finalIntervals
                ?.single { it.id == TARGET_INTERVAL }
                ?.startedAt,
        )
        assertFalse(proposal.hasIntervalOverlap)
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
                            SequenceHistoryTimestampTarget.IntervalStartedAt(OWNERLESS) to
                                SequenceHistoryTimestampDraft("2026-01-01T00:05:00")
                        ),
            )
        val proposal =
            (
                SequenceHistoryProposalBuilder.timing(detail, overlapDraft) as
                    SequenceHistoryTimingBuildResult.Ready
            ).proposal
        assertFalse(
            requireNotNull(proposal.correction.finalIntervals)
                .filter { it.kind == SequenceIntervalKind.ACTIVE_STEP }
                .zipWithNext()
                .any { (first, second) -> second.startedAt < requireNotNull(first.endedAt) },
        )
        assertTrue(proposal.hasIntervalOverlap)
        assertEquals(
            HistoricalLocalDateTimeResolution.Nonexistent,
            resolveHistoricalLocalDateTime("2026-03-29T02:30", BERLIN, null),
        )
        assertEquals(
            HistoricalLocalDateTimeResolution.Invalid,
            resolveHistoricalLocalDateTime("not-a-local-date-time", BERLIN, null),
        )
        val ambiguous = resolveHistoricalLocalDateTime("2026-10-25T02:30", BERLIN, null)
        assertTrue(ambiguous is HistoricalLocalDateTimeResolution.Ambiguous)
        val offsets = (ambiguous as HistoricalLocalDateTimeResolution.Ambiguous).offsets
        assertEquals(2, offsets.size)
        offsets.forEach { offset ->
            assertEquals(
                LocalDateTime.parse("2026-10-25T02:30").toInstant(offset),
                (
                    resolveHistoricalLocalDateTime("2026-10-25T02:30", BERLIN, offset) as
                        HistoricalLocalDateTimeResolution.Resolved
                ).instant,
            )
        }
    }

    @Test
    fun unchangedTimingIsNoChangeEvenWhenCanonicalIntervalsAlreadyOverlap() {
        val base = detail()
        val detail =
            base.copy(
                intervals =
                    base.intervals.map {
                        if (it.id == LATER_INTERVAL) it.copy(startedAt = minute(5)) else it
                    },
            )
        val draft = SequenceHistoryProposalBuilder.timingDraft(detail)
        val formattingOnly =
            draft.copy(
                timestamps =
                    draft.timestamps +
                        (
                            SequenceHistoryTimestampTarget.RootEndedAt to
                                requireNotNull(draft.timestamps[SequenceHistoryTimestampTarget.RootEndedAt])
                                    .copy(text = "2026-01-01T00:50:00.000")
                        ),
            )

        assertTrue(
            SequenceHistoryProposalBuilder.timing(
                detail,
                formattingOnly,
            ) is SequenceHistoryTimingBuildResult.NoChange,
        )
    }

    @Test
    fun deletedChildTombstoneKeepsMutationFactsButHasNoOrdinaryOccurrenceTimingTargets() =
        runBlocking {
            val detail = detail()
            val tombstone = detail.occurrences.single { it.occurrenceId == LATER }
            val draft = SequenceHistoryProposalBuilder.timingDraft(detail)

            assertNull(tombstone.child)
            assertEquals(CHILD_LATER, tombstone.childMutationFacts?.executionId)
            assertFalse(draft.timestamps.containsKey(SequenceHistoryTimestampTarget.OccurrenceEnteredAt(LATER)))
            assertFalse(draft.timestamps.containsKey(SequenceHistoryTimestampTarget.OccurrenceCompletedAt(LATER)))

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller =
                SequenceHistoryMutationController(
                    scope,
                    SEQUENCE,
                    { detail },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ -> error("unexpected") },
                )
            awaitLoaded(controller)
            controller.dispatch(SequenceHistoryMutationAction.BeginStructuralRemoval(LATER))
            assertEquals(LATER, controller.state.value.structuralTarget)
            controller.close()
            scope.cancel()
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
        assertEquals(
            SequenceHistoryOwnerlessPlacementChoice(
                OWNERLESS,
                SequenceIntervalKind.EXPLICIT_PAUSE,
                minute(35),
                minute(37),
                minute(25),
                minute(27),
                null,
            ),
            unresolved.ownerlessChoices.single(),
        )

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
        assertEquals(OwnerlessIntervalPlacement.TRANSLATED, close.ownerlessChoices.single().selectedPlacement)
        assertEquals(minute(40), command.finalEndedAt)
        assertEquals(
            detail.intervals.filter { it.occurrenceId != TARGET }.map { it.id },
            command.finalIntervals.map { it.id },
        )
        assertEquals(minute(25), command.finalIntervals.single { it.id == OWNERLESS }.startedAt)
        assertEquals(
            SequenceHistoryPreviewChange.OwnerlessPlacement(
                OWNERLESS,
                SequenceIntervalKind.EXPLICIT_PAUSE,
                OwnerlessIntervalPlacement.TRANSLATED,
                minute(25),
                minute(27),
            ),
            close.changes.filterIsInstance<SequenceHistoryPreviewChange.OwnerlessPlacement>().single(),
        )
        assertEquals(listOf(LATER, SECOND_LATER), command.occurrenceTimings.map { it.occurrenceId })
        assertEquals(listOf(minute(0), minute(10)), command.occurrenceTimings.map { it.enteredAt })
        assertEquals(listOf(minute(10), minute(20)), command.occurrenceTimings.map { it.completedAt })
        assertEquals(listOf(CHILD_LATER, CHILD_SECOND_LATER), command.childTimings.map { it.executionId })
        assertEquals(
            listOf(minute(0), minute(10)),
            command.childTimings.map { (it.time as ActivityHistoryTimeCorrection.Timed).startedAt },
        )
        assertEquals(
            listOf(minute(10), minute(20)),
            command.childTimings.map { (it.time as ActivityHistoryTimeCorrection.Timed).completedAt },
        )
        assertEquals(
            listOf(minute(2), minute(12)),
            command.childTimings.map { it.pauses.single().startedAt },
        )
        assertEquals(
            listOf(minute(3), minute(13)),
            command.childTimings.map { it.pauses.single().endedAt },
        )
        assertEquals(
            detail.intervals.associate { it.id to it.kind },
            (
                command.finalIntervals +
                    detail.intervals.single {
                        it.id == TARGET_INTERVAL
                    }
            ).associate { it.id to it.kind },
        )
        assertEquals(
            detail.intervals.associate { it.id to it.occurrenceId },
            (command.finalIntervals + detail.intervals.single { it.id == TARGET_INTERVAL })
                .associate { it.id to it.occurrenceId },
        )
        assertEquals(minute(0), command.finalIntervals.single { it.id == FORCED_FIXED }.startedAt)
        assertEquals(minute(0), command.finalIntervals.single { it.id == LATER_INTERVAL }.startedAt)
        assertEquals(minute(10), command.finalIntervals.single { it.id == SECOND_LATER_INTERVAL }.startedAt)
        assertEquals(minute(20), command.finalIntervals.single { it.id == COUNTDOWN_INTERVAL }.startedAt)
        val pauseChanges = close.changes.filterIsInstance<SequenceHistoryPreviewChange.ChildPauseTimestamp>()
        assertEquals(listOf(ActivityExecutionPauseId("pause-second-later")), pauseChanges.map { it.pauseId })
        assertEquals(SECOND_LATER, pauseChanges.single().occurrence.occurrenceId)
        assertEquals(minute(22), pauseChanges.single().beforeStartedAt)
        assertEquals(minute(12), pauseChanges.single().afterStartedAt)
        assertFalse(
            close.changes
                .filterIsInstance<SequenceHistoryPreviewChange.Timestamp>()
                .any {
                    it.target == SequenceHistoryTimestampTarget.ChildStartedAt(LATER) ||
                        it.target == SequenceHistoryTimestampTarget.ChildCompletedAt(LATER)
                },
        )
        assertEquals(
            "target",
            close.changes
                .filterIsInstance<SequenceHistoryPreviewChange.RemovedOccurrence>()
                .single()
                .occurrence
                .activityTitle,
        )
        val fixedChoice =
            requireNotNull(
                requireNotNull(
                    SequenceHistoryProposalBuilder.structural(
                        detail,
                        TARGET,
                        SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                        mapOf(OWNERLESS to OwnerlessIntervalPlacement.FIXED),
                    ),
                ).command,
            )
        assertEquals(minute(35), fixedChoice.finalIntervals.single { it.id == OWNERLESS }.startedAt)
        val fixedPreview =
            requireNotNull(
                SequenceHistoryProposalBuilder.structural(
                    detail,
                    TARGET,
                    SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                    mapOf(OWNERLESS to OwnerlessIntervalPlacement.FIXED),
                ),
            )
        assertEquals(
            SequenceHistoryPreviewChange.OwnerlessPlacement(
                OWNERLESS,
                SequenceIntervalKind.EXPLICIT_PAUSE,
                OwnerlessIntervalPlacement.FIXED,
                minute(35),
                minute(37),
            ),
            fixedPreview.changes.filterIsInstance<SequenceHistoryPreviewChange.OwnerlessPlacement>().single(),
        )
        assertEquals(OwnerlessIntervalPlacement.FIXED, fixedPreview.ownerlessChoices.single().selectedPlacement)
        assertEquals(
            detail.intervals.single { it.id == OWNERLESS }.let { it.id to (it.kind to it.occurrenceId) },
            requireNotNull(fixedPreview.command).finalIntervals.single { it.id == OWNERLESS }.let {
                it.id to (it.kind to it.occurrenceId)
            },
        )
        assertNull(detail.occurrences.single { it.occurrenceId == SKIPPED }.childMutationFacts)
    }

    @Test
    fun structuralMixedKindOverlapWarnsWithoutBlockingCloseGapAndCancellationDoesNotWrite() =
        runBlocking {
            val canonical = structuralMixedOverlapDetail()
            val overlap =
                requireNotNull(
                    SequenceHistoryProposalBuilder.structural(
                        canonical,
                        TARGET,
                        SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                        mapOf(OWNERLESS to OwnerlessIntervalPlacement.TRANSLATED),
                    ),
                )
            assertTrue(overlap.hasIntervalOverlap)
            assertFalse(
                requireNotNull(overlap.command)
                    .finalIntervals
                    .filter { it.kind == SequenceIntervalKind.ACTIVE_STEP }
                    .zipWithNext()
                    .any { (first, second) -> second.startedAt < requireNotNull(first.endedAt) },
            )
            assertTrue(
                requireNotNull(
                    SequenceHistoryProposalBuilder.structural(
                        canonical,
                        TARGET,
                        SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                    ),
                ).hasIntervalOverlap,
            )

            var writes = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller =
                SequenceHistoryMutationController(
                    scope,
                    SEQUENCE,
                    { canonical },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ -> writes++ },
                )
            awaitLoaded(controller)

            fun review() {
                controller.dispatch(SequenceHistoryMutationAction.BeginStructuralRemoval(TARGET))
                controller.dispatch(
                    SequenceHistoryMutationAction.ChooseStructuralMode(SequenceHistoryStructuralRemovalMode.CLOSE_GAP),
                )
                controller.dispatch(
                    SequenceHistoryMutationAction.PlaceOwnerlessInterval(
                        OWNERLESS,
                        OwnerlessIntervalPlacement.TRANSLATED,
                    ),
                )
            }

            review()
            assertTrue(controller.state.value.overlapWarning)
            controller.dispatch(SequenceHistoryMutationAction.ConfirmStructuralRemoval)
            controller.dispatch(SequenceHistoryMutationAction.CancelOverlap)
            assertEquals(0, writes)
            assertNull(controller.state.value.structuralProposal)

            review()
            controller.dispatch(SequenceHistoryMutationAction.ProceedOverlap)
            controller.dispatch(SequenceHistoryMutationAction.ConfirmStructuralRemoval)
            withTimeout(2_000) { controller.state.first { it.refreshGeneration == 1L } }
            assertEquals(1, writes)
            controller.close()
            scope.cancel()
        }

    @Test
    fun closeGapMovesNoLiveChildWithoutFabricatingTimedFactsAndTranslatesNotStartedCountdown() {
        val base = detail()
        val noLive =
            base.copy(
                occurrences =
                    base.occurrences
                        .map { occurrence ->
                            if (occurrence.occurrenceId != SECOND_LATER) {
                                occurrence
                            } else {
                                occurrence.copy(
                                    activity =
                                        occurrence.activity.copy(
                                            timeTrackingMode =
                                                TimeTrackingMode.NO_LIVE_TRACKING,
                                        ),
                                    child =
                                        requireNotNull(
                                            occurrence.child,
                                        ).copy(startedAt = null, activeDuration = null),
                                    childMutationFacts =
                                        requireNotNull(occurrence.childMutationFacts).copy(
                                            startedAt = null,
                                            pauses = emptyList(),
                                        ),
                                )
                            }
                        }.map { occurrence ->
                            if (occurrence.occurrenceId == SKIPPED) {
                                occurrence.copy(
                                    sourceSequenceSnapshotNodeId = SequenceSnapshotNodeId("source-skipped"),
                                    status = RuntimeOccurrenceStatus.NOT_STARTED,
                                )
                            } else {
                                occurrence
                            }
                        },
            )
        val command =
            requireNotNull(
                requireNotNull(
                    SequenceHistoryProposalBuilder.structural(
                        noLive,
                        TARGET,
                        SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                        mapOf(OWNERLESS to OwnerlessIntervalPlacement.TRANSLATED),
                    ),
                ).command,
            )
        val movedNoLive = command.childTimings.single { it.executionId == CHILD_SECOND_LATER }
        assertEquals(ActivityHistoryTimeCorrection.NoLive(minute(20)), movedNoLive.time)
        assertTrue(movedNoLive.pauses.isEmpty())
        assertEquals(
            SECOND_LATER,
            command.occurrenceTimings.single { it.occurrenceId == SECOND_LATER }.occurrenceId,
        )
        assertFalse(command.occurrenceTimings.any { it.occurrenceId == SKIPPED })
        assertEquals(
            minute(20),
            command.finalIntervals.single { it.id == COUNTDOWN_INTERVAL }.startedAt,
        )
        assertEquals(
            minute(22),
            command.finalIntervals.single { it.id == COUNTDOWN_INTERVAL }.endedAt,
        )
    }

    @Test
    fun leaveGapPreviewListsExactlyEveryTargetOwnedIntervalWithOriginalFacts() {
        val base = detail()
        val targetPause = interval("target-pause", SequenceIntervalKind.STEP_PAUSE, minute(2), minute(3), TARGET)
        val detail = base.copy(intervals = base.intervals + targetPause)

        val proposal =
            requireNotNull(
                SequenceHistoryProposalBuilder.structural(
                    detail,
                    TARGET,
                    SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                ),
            )

        assertEquals(detail.root.completedAt, requireNotNull(proposal.command).finalEndedAt)
        assertEquals(
            listOf(
                SequenceHistoryIntervalDescriptor(
                    TARGET_INTERVAL,
                    SequenceIntervalKind.ACTIVE_STEP,
                    minute(0),
                    minute(10),
                ),
                SequenceHistoryIntervalDescriptor(
                    targetPause.id,
                    targetPause.kind,
                    targetPause.startedAt,
                    requireNotNull(targetPause.endedAt),
                ),
            ),
            proposal.changes.filterIsInstance<SequenceHistoryPreviewChange.RemovedInterval>().map { it.interval },
        )
        assertTrue(
            proposal.changes
                .filterIsInstance<SequenceHistoryPreviewChange.RemovedInterval>()
                .none { it.interval.intervalId == OWNERLESS || it.interval.intervalId == LATER_INTERVAL },
        )
    }

    @Test
    fun unchangedTimingReviewCannotWriteOrRefreshEvenWithExistingOverlap() =
        runBlocking {
            val base = detail()
            val canonical =
                base.copy(
                    intervals =
                        base.intervals.map {
                            if (it.id == LATER_INTERVAL) it.copy(startedAt = minute(5)) else it
                        },
                )
            var commands = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller =
                SequenceHistoryMutationController(
                    scope,
                    SEQUENCE,
                    { canonical },
                    { _, _, _ -> commands++ },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ -> error("unexpected") },
                )
            awaitLoaded(controller)

            controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            controller.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            controller.dispatch(SequenceHistoryMutationAction.ProceedOverlap)
            controller.dispatch(SequenceHistoryMutationAction.ConfirmTiming)

            assertEquals(0, commands)
            assertEquals(0, controller.state.value.refreshGeneration)
            assertNull(controller.state.value.timingProposal)
            assertFalse(controller.state.value.overlapWarning)
            controller.close()
            scope.cancel()
        }

    @Test
    fun changedMixedKindOverlapRequiresProceedAndCommitsExactlyOnce() =
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
                )
            awaitLoaded(controller)
            controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            controller.dispatch(
                SequenceHistoryMutationAction.EditTimestamp(
                    SequenceHistoryTimestampTarget.IntervalStartedAt(OWNERLESS),
                    "2026-01-01T00:05:00",
                ),
            )
            controller.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            assertTrue(controller.state.value.overlapWarning)
            controller.dispatch(SequenceHistoryMutationAction.ConfirmTiming)
            assertEquals(0, commands)
            controller.dispatch(SequenceHistoryMutationAction.CancelOverlap)
            controller.dispatch(SequenceHistoryMutationAction.ConfirmTiming)
            assertEquals(0, commands)

            controller.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            controller.dispatch(SequenceHistoryMutationAction.ProceedOverlap)
            controller.dispatch(SequenceHistoryMutationAction.ConfirmTiming)
            withTimeout(2_000) { controller.state.first { it.refreshGeneration == 1L } }
            assertEquals(1, commands)
            controller.close()
            scope.cancel()
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
            controller.dispatch(
                SequenceHistoryMutationAction.EditTimestamp(
                    SequenceHistoryTimestampTarget.RootEndedAt,
                    "2026-01-01T00:49:00",
                ),
            )
            controller.dispatch(SequenceHistoryMutationAction.ReviewTiming)
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
            stale.dispatch(
                SequenceHistoryMutationAction.EditTimestamp(
                    SequenceHistoryTimestampTarget.RootEndedAt,
                    "2026-01-01T00:49:00",
                ),
            )
            stale.dispatch(SequenceHistoryMutationAction.ReviewTiming)
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
    fun backConsumesEveryTransientStageAndBlocksInFlightWithoutWriting() =
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
            assertTrue(controller.handleBack())

            controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            controller.dispatch(
                SequenceHistoryMutationAction.EditTimestamp(
                    SequenceHistoryTimestampTarget.RootEndedAt,
                    "2026-01-01T00:49:00",
                ),
            )
            controller.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            assertTrue(controller.handleBack())

            controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            controller.dispatch(
                SequenceHistoryMutationAction.EditTimestamp(
                    SequenceHistoryTimestampTarget.IntervalStartedAt(LATER_INTERVAL),
                    "2026-01-01T00:05:00",
                ),
            )
            controller.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            assertTrue(controller.handleBack())

            controller.dispatch(SequenceHistoryMutationAction.RequestChildDeletion(TARGET))
            assertTrue(controller.handleBack())
            controller.dispatch(SequenceHistoryMutationAction.BeginStructuralRemoval(TARGET))
            assertTrue(controller.handleBack())
            controller.dispatch(SequenceHistoryMutationAction.BeginStructuralRemoval(TARGET))
            controller.dispatch(
                SequenceHistoryMutationAction.ChooseStructuralMode(
                    SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                ),
            )
            assertFalse(requireNotNull(controller.state.value.structuralProposal).isConfirmable)
            assertTrue(controller.handleBack())
            assertEquals(0, deletions)

            controller.dispatch(SequenceHistoryMutationAction.RequestChildDeletion(TARGET))
            controller.dispatch(SequenceHistoryMutationAction.ConfirmChildDeletion)
            assertTrue(controller.handleBack())
            assertTrue(controller.state.value.isMutating)
            assertEquals(1, deletions)
            gate.complete(Unit)
            withTimeout(2_000) { controller.state.first { !it.isMutating } }
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
            commandFailure.dispatch(
                SequenceHistoryMutationAction.EditTimestamp(
                    SequenceHistoryTimestampTarget.RootEndedAt,
                    "2026-01-01T00:49:00",
                ),
            )
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

    @Test
    fun repositoryValidationRejectionKeepsProposalVisibleAndDoesNotRefresh() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller =
                SequenceHistoryMutationController(
                    scope,
                    SEQUENCE,
                    { detail() },
                    { _, _, _ -> throw IllegalArgumentException("canonical validation") },
                    { _, _, _ -> error("unexpected") },
                    { _, _, _ -> error("unexpected") },
                )
            awaitLoaded(controller)
            controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            controller.dispatch(
                SequenceHistoryMutationAction.EditTimestamp(
                    SequenceHistoryTimestampTarget.RootEndedAt,
                    "2026-01-01T00:49:00",
                ),
            )
            controller.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            controller.dispatch(SequenceHistoryMutationAction.ConfirmTiming)
            withTimeout(2_000) {
                controller.state.first { it.issue == SequenceHistoryMutationIssue.INVALID_PROPOSAL }
            }

            assertTrue(controller.state.value.timingProposal != null)
            assertEquals(0, controller.state.value.refreshGeneration)
            assertFalse(controller.state.value.isMutating)
            controller.close()
            scope.cancel()
        }

    private suspend fun awaitLoaded(controller: SequenceHistoryMutationController) {
        withTimeout(2_000) { controller.state.first { it.load is HistoryDetailLoad.Content } }
    }

    private fun detail(): SequenceHistoryDetail {
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

    private fun structuralMixedOverlapDetail(): SequenceHistoryDetail {
        val base = detail()
        return base.copy(
            intervals =
                base.intervals.map {
                    if (it.id == OWNERLESS) it.copy(startedAt = minute(20), endedAt = minute(22)) else it
                },
        )
    }

    companion object {
        private val BASE = Instant.parse("2026-01-01T00:00:00Z")
        private val TOKEN = BASE.plusSeconds(3_600)
        private val BERLIN = ZoneId.of("Europe/Berlin")
        private val SEQUENCE = SequenceExecutionId("sequence")
        private val TARGET = SequenceOccurrenceId("target")
        private val LATER = SequenceOccurrenceId("later")
        private val SECOND_LATER = SequenceOccurrenceId("second-later")
        private val SKIPPED = SequenceOccurrenceId("skipped")
        private val CHILD_TARGET = ActivityExecutionId("child-target")
        private val CHILD_LATER = ActivityExecutionId("child-later")
        private val CHILD_SECOND_LATER = ActivityExecutionId("child-second-later")
        private val TARGET_INTERVAL = SequenceIntervalId("target")
        private val LATER_INTERVAL = SequenceIntervalId("later")
        private val SECOND_LATER_INTERVAL = SequenceIntervalId("second-later")
        private val COUNTDOWN_INTERVAL = SequenceIntervalId("countdown")
        private val FORCED_FIXED = SequenceIntervalId("fixed")
        private val OWNERLESS = SequenceIntervalId("ownerless")
    }
}
