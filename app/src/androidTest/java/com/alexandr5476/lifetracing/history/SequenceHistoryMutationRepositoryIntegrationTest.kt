package com.alexandr5476.lifetracing.history

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.LifeTracingRuntimeGraph
import com.alexandr5476.lifetracing.MainActivity
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.data.persistence.DailyReadRepository
import com.alexandr5476.lifetracing.data.persistence.HistoryReadRepository
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.PlanReadRepository
import com.alexandr5476.lifetracing.data.persistence.PlanRepository
import com.alexandr5476.lifetracing.data.persistence.StatisticsRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalMode
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class SequenceHistoryMutationRepositoryIntegrationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectedFallBackOccurrencePersistsAndReloadsAsTheSameInstant() =
        runBlocking {
            val context = composeTestRule.activity
            clearActiveSession(context)
            val suffix = System.nanoTime().toString()
            val execution =
                completedSequence(
                    context,
                    "$suffix-dst-review",
                    Instant.parse("2025-10-26T00:20:00Z"),
                    listOf(600),
                    zoneId = ZoneId.of("Europe/Berlin"),
                )
            val history = HistoryReadRepository.create(context)
            val controller = LifeTracingRuntimeGraph.from(context).createSequenceHistoryDetailController(execution)
            val before = controller.awaitDetail()
            val target = SequenceHistoryTimestampTarget.RootEndedAt
            controller.dispatch(SequenceHistoryMutationAction.BeginTiming)
            val draft = requireNotNull(controller.state.value.timingDraft).timestamps.getValue(target)

            assertEquals(Instant.parse("2025-10-26T00:30:00Z"), before.root.completedAt)
            assertEquals(listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)), draft.validOffsets)
            controller.dispatch(
                SequenceHistoryMutationAction.SelectTimestampOffset(target, ZoneOffset.ofHours(1)),
            )
            controller.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            assertEquals(
                Instant.parse("2025-10-26T01:30:00Z"),
                controller.state.value.timingProposal
                    ?.correction
                    ?.endedAt,
            )
            controller.dispatch(SequenceHistoryMutationAction.ConfirmTiming)
            val after =
                try {
                    controller.awaitRefresh()
                } catch (failure: Exception) {
                    controller.close()
                    throw AssertionError("Correction did not reload: ${controller.state.value}", failure)
                }

            assertEquals(Instant.parse("2025-10-26T01:30:00Z"), after.root.completedAt)
            assertEquals(after, history.getSequenceDetail(execution))
            controller.close()
        }

    @Test
    fun productionControllerPersistsAndCanonicallyReloadsEverySequenceHistoryTransition() =
        runBlocking {
            val context = composeTestRule.activity
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
            val editedEnd = LocalDateTime.parse(endDraft.text).plusNanos(1_000_000)
            timingController.dispatch(
                SequenceHistoryMutationAction.EditTimestamp(
                    endTarget,
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(editedEnd),
                ),
            )
            timingController.dispatch(SequenceHistoryMutationAction.ReviewTiming)
            timingController.dispatch(SequenceHistoryMutationAction.ConfirmTiming)
            val timingAfter = timingController.awaitRefresh()
            assertEquals(timingBefore.root.completedAt.plusMillis(1), timingAfter.root.completedAt)
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

    @Test
    fun productionRoutePersistsTimingDeletionTombstoneLeaveGapAndCloseGap() {
        val context = composeTestRule.activity
        clearActiveSession(context)
        val suffix = System.nanoTime().toString()
        val base = Instant.now().minusSeconds(1_000)
        val history = HistoryReadRepository.create(context)
        val timing = completedSequence(context, "$suffix-route-timing", base, listOf(10))
        val deletion = completedSequence(context, "$suffix-route-delete", base.plusSeconds(30), listOf(10))
        val close = completedSequence(context, "$suffix-route-close", base.plusSeconds(60), listOf(10, 10))

        enterHistory()
        openSequence(timing)
        val timingBefore = requireNotNull(history.getSequenceDetail(timing))
        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().performClick()
        replaceTimestamp(
            "sequence-history-timestamp-root-ended",
            timingBefore.root.completedAt.plusSeconds(1),
            timingBefore.originalZoneId,
        )
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").performScrollTo().performClick()
        composeTestRule.waitUntil(5_000) {
            history.getSequenceDetail(timing)?.root?.completedAt == timingBefore.root.completedAt.plusSeconds(1)
        }
        backToHistory()

        openSequence(deletion)
        val deletionBefore = requireNotNull(history.getSequenceDetail(deletion))
        val deletionTarget = deletionBefore.occurrences.single().occurrenceId
        composeTestRule
            .onNodeWithTag("sequence-history-delete-child-${deletionTarget.value}")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").performScrollTo().performClick()
        composeTestRule.waitUntil(5_000) {
            history
                .getSequenceDetail(deletion)
                ?.occurrences
                ?.singleOrNull()
                ?.status ==
                RuntimeOccurrenceStatus.DELETED_EXECUTION
        }
        composeTestRule
            .onNodeWithTag("sequence-history-remove-occurrence-${deletionTarget.value}")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("sequence-history-leave-gap").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-structural").performScrollTo().performClick()
        composeTestRule.waitUntil(5_000) { history.getSequenceDetail(deletion)?.occurrences?.isEmpty() == true }
        backToHistory()

        openSequence(close)
        val closeBefore = requireNotNull(history.getSequenceDetail(close))
        val removed = closeBefore.occurrences.first()
        val retained = closeBefore.occurrences.last()
        val removedSpan =
            requireNotNull(removed.completedAt).toEpochMilli() - requireNotNull(removed.enteredAt).toEpochMilli()
        composeTestRule
            .onNodeWithTag("sequence-history-remove-occurrence-${removed.occurrenceId.value}")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("sequence-history-close-gap").performScrollTo().performClick()
        resolveOwnerlessIntervals()
        composeTestRule.onNodeWithTag("sequence-history-confirm-structural").performScrollTo().performClick()
        composeTestRule.waitUntil(5_000) {
            history.getSequenceDetail(close)?.root?.completedAt == closeBefore.root.completedAt.minusMillis(removedSpan)
        }
        val closeAfter = requireNotNull(history.getSequenceDetail(close))
        assertEquals(listOf(retained.occurrenceId), closeAfter.occurrences.map { it.occurrenceId })
        assertEquals(
            requireNotNull(retained.enteredAt).minusMillis(removedSpan),
            closeAfter.occurrences.single().enteredAt,
        )
    }

    @Test
    fun productionControllerCloseGapPersistsMovedNoLiveChildWithoutTimedFacts() =
        runBlocking {
            val context = composeTestRule.activity
            clearActiveSession(context)
            val execution =
                completedSequence(
                    context,
                    "${System.nanoTime()}-no-live",
                    Instant.now().minusSeconds(1_000),
                    listOf(10, 10),
                    timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING,
                    nodeCount = 3,
                    endEarly = true,
                )
            val graph = LifeTracingRuntimeGraph.from(context)
            val history = HistoryReadRepository.create(context)
            val controller = graph.createSequenceHistoryDetailController(execution)
            val before = controller.awaitDetail()
            assertEquals(SequenceExecutionStatus.ENDED_EARLY, before.root.status)
            val removed = before.occurrences.first()
            val retained = before.occurrences[1]
            val retainedChild = requireNotNull(retained.child)
            assertNull(retainedChild.startedAt)
            assertNull(retainedChild.activeDuration)

            controller.dispatch(SequenceHistoryMutationAction.BeginStructuralRemoval(removed.occurrenceId))
            controller.dispatch(
                SequenceHistoryMutationAction.ChooseStructuralMode(SequenceHistoryStructuralRemovalMode.CLOSE_GAP),
            )
            requireNotNull(controller.state.value.structuralProposal).ownerlessPlacements.keys.forEach { id ->
                controller.dispatch(
                    SequenceHistoryMutationAction.PlaceOwnerlessInterval(id, OwnerlessIntervalPlacement.TRANSLATED),
                )
            }
            val proposal = requireNotNull(controller.state.value.structuralProposal)
            val moved =
                requireNotNull(proposal.command)
                    .childTimings
                    .single { it.executionId == retainedChild.executionId }
            assertTrue(moved.time is ActivityHistoryTimeCorrection.NoLive)
            assertTrue(moved.pauses.isEmpty())
            assertEquals(
                retained.occurrenceId,
                proposal.command.occurrenceTimings
                    .single { it.occurrenceId == retained.occurrenceId }
                    .occurrenceId,
            )

            controller.dispatch(SequenceHistoryMutationAction.ConfirmStructuralRemoval)
            val after = controller.awaitRefresh()
            val reloaded = requireNotNull(history.getSequenceDetail(execution))
            val movedOccurrence = after.occurrences.single { it.occurrenceId == retained.occurrenceId }
            val movedChild = requireNotNull(movedOccurrence.child)
            assertEquals(after, reloaded)
            assertEquals(retained.occurrenceId, movedOccurrence.occurrenceId)
            assertEquals(retainedChild.executionId, movedChild.executionId)
            assertNull(movedChild.startedAt)
            assertNull(movedChild.activeDuration)
            controller.close()
        }

    @Test
    fun productionRouteOverlapUsesIntervalUnionAndDateMoveRefreshesCanonicalProjections() {
        val context = composeTestRule.activity
        clearActiveSession(context)
        val suffix = System.nanoTime().toString()
        val history = HistoryReadRepository.create(context)
        val statistics = StatisticsRepository.create(context)
        val overlap = completedSequence(context, "$suffix-overlap", Instant.now().minusSeconds(800), listOf(10, 10))
        val utcDate = LocalDate.now(ZoneOffset.UTC).minusDays(1)
        val midnight = utcDate.atStartOfDay(ZoneOffset.UTC).toInstant()
        val moved = completedSequence(context, "$suffix-date", midnight.plusSeconds(30), listOf(20, 20))
        val currentDayBefore = statistics.global(StatisticsPeriod.Day(utcDate)).topLevelExecutionCount
        val previousDayBefore = statistics.global(StatisticsPeriod.Day(utcDate.minusDays(1))).topLevelExecutionCount

        enterHistory()
        openSequence(overlap)
        val overlapBefore = requireNotNull(history.getSequenceDetail(overlap))
        val later = overlapBefore.occurrences.last()
        val earlierStart = requireNotNull(later.enteredAt).minusSeconds(5)
        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().performClick()
        replaceTimestamp(
            "sequence-history-timestamp-occurrence-${later.occurrenceId.value}-entered",
            earlierStart,
            overlapBefore.originalZoneId,
        )
        replaceTimestamp(
            "sequence-history-timestamp-child-${later.occurrenceId.value}-started",
            earlierStart,
            overlapBefore.originalZoneId,
        )
        val laterInterval = overlapBefore.intervals.single { it.occurrenceId == later.occurrenceId }
        replaceTimestamp(
            "sequence-history-timestamp-interval-${laterInterval.id.value}-started",
            earlierStart,
            overlapBefore.originalZoneId,
        )
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-overlap-cancel").performScrollTo().performClick()
        assertEquals(overlapBefore.updatedAt, history.getSequenceDetail(overlap)?.updatedAt)
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-overlap-proceed").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").performScrollTo().performClick()
        composeTestRule.waitUntil(5_000) { history.getSequenceDetail(overlap)?.updatedAt != overlapBefore.updatedAt }
        val overlapAfter = requireNotNull(history.getSequenceDetail(overlap))
        assertEquals(java.time.Duration.ofSeconds(20), overlapAfter.root.activeDuration)
        assertTrue(
            overlapAfter.occurrences
                .mapNotNull { it.child?.activeDuration }
                .fold(java.time.Duration.ZERO) { total, duration -> total.plus(duration) } >
                overlapAfter.root.activeDuration,
        )
        backToHistory()

        openSequence(moved)
        val movedBefore = requireNotNull(history.getSequenceDetail(moved))
        assertEquals(utcDate, movedBefore.root.primaryLocalDate)
        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().performClick()
        replaceTimestamp(
            "sequence-history-timestamp-root-started",
            midnight.minusSeconds(30),
            movedBefore.originalZoneId,
        )
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").performScrollTo().performClick()
        composeTestRule.waitUntil(5_000) {
            history.getSequenceDetail(moved)?.root?.primaryLocalDate == utcDate.minusDays(1)
        }
        val movedAfter = requireNotNull(history.getSequenceDetail(moved))
        assertEquals(utcDate.minusDays(1), movedAfter.root.primaryLocalDate)
        assertTrue(historyRoots(context, utcDate).none { it.executionId == moved })
        assertTrue(historyRoots(context, utcDate.minusDays(1)).any { it.executionId == moved })
        assertTrue(
            DailyReadRepository
                .create(context)
                .getDaily(DailyQuery(utcDate.minusDays(1), Instant.now(), 100))
                .completedHistory
                .filterIsInstance<CompletedSequenceHistoryRoot>()
                .any { it.executionId == moved },
        )
        assertEquals(
            currentDayBefore - 1,
            statistics.global(StatisticsPeriod.Day(utcDate)).topLevelExecutionCount,
        )
        assertEquals(
            previousDayBefore + 1,
            statistics.global(StatisticsPeriod.Day(utcDate.minusDays(1))).topLevelExecutionCount,
        )
    }

    @Test
    fun productionRouteStructuralOverlapWarnsCancelsWithoutWritesAndCommitsCloseGapWithUnionDuration() {
        val context = composeTestRule.activity
        clearActiveSession(context)
        val history = HistoryReadRepository.create(context)
        val execution =
            completedSequence(
                context,
                "${System.nanoTime()}-structural-overlap",
                Instant.now().minusSeconds(1_000),
                listOf(10, 10, 10),
            )

        enterHistory()
        openSequence(execution)
        val original = requireNotNull(history.getSequenceDetail(execution))
        val second = original.occurrences[1]
        val third = original.occurrences[2]
        val overlappingStart = requireNotNull(second.enteredAt).plusSeconds(5)
        val thirdInterval = original.intervals.single { it.occurrenceId == third.occurrenceId }
        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().performClick()
        replaceTimestamp(
            "sequence-history-timestamp-occurrence-${third.occurrenceId.value}-entered",
            overlappingStart,
            original.originalZoneId,
        )
        replaceTimestamp(
            "sequence-history-timestamp-child-${third.occurrenceId.value}-started",
            overlappingStart,
            original.originalZoneId,
        )
        replaceTimestamp(
            "sequence-history-timestamp-interval-${thirdInterval.id.value}-started",
            overlappingStart,
            original.originalZoneId,
        )
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-overlap-proceed").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").performScrollTo().performClick()
        composeTestRule.waitUntil(5_000) { history.getSequenceDetail(execution)?.updatedAt != original.updatedAt }

        val beforeStructural = requireNotNull(history.getSequenceDetail(execution))
        val target = beforeStructural.occurrences.first()
        composeTestRule
            .onNodeWithTag("sequence-history-remove-occurrence-${target.occurrenceId.value}")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("sequence-history-close-gap").performScrollTo().performClick()
        resolveOwnerlessIntervals()
        composeTestRule
            .onNodeWithText(
                composeTestRule.activity.getString(R.string.history_overlap_warning),
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-history-overlap-cancel").performScrollTo().performClick()
        assertEquals(beforeStructural.updatedAt, history.getSequenceDetail(execution)?.updatedAt)

        composeTestRule
            .onNodeWithTag("sequence-history-remove-occurrence-${target.occurrenceId.value}")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("sequence-history-close-gap").performScrollTo().performClick()
        resolveOwnerlessIntervals()
        composeTestRule.onNodeWithTag("sequence-history-overlap-proceed").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-structural").performScrollTo().performClick()
        composeTestRule.waitUntil(5_000) { history.getSequenceDetail(execution)?.occurrences?.size == 2 }
        val after = requireNotNull(history.getSequenceDetail(execution))
        assertEquals(java.time.Duration.ofSeconds(20), after.root.activeDuration)
        assertEquals(after, HistoryReadRepository.create(context).getSequenceDetail(execution))
    }

    @Test
    fun productionRouteKeepsFulfilledPlanIdentityAndSynchronizesCorrectedEnd() {
        val context = composeTestRule.activity
        clearActiveSession(context)
        val suffix = System.nanoTime().toString()
        val startedAt = Instant.now().minusSeconds(300)
        val authoring = TemplateAuthoringRepository.create(context)
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Plan history step $suffix", null, TimeTrackingMode.STOPWATCH, null),
                createdAt = startedAt.minusSeconds(3),
            )
        val sequence =
            authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Plan history sequence $suffix",
                    null,
                    nodes =
                        listOf(
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    DraftIdentity.New("plan-$suffix"),
                                    0,
                                    StepActivityDraft.FromTemplate(activity.id),
                                ),
                            ),
                        ),
                ),
                createdAt = startedAt.minusSeconds(2),
            )
        val plans = PlanRepository.create(context)
        val plan =
            plans.createSequencePlanFromTemplate(
                sequence.id,
                PlanSchedule.FloatingDay(startedAt.atZone(ZoneOffset.UTC).toLocalDate()),
                startedAt.minusSeconds(1),
            )
        val live = LiveSessionRepository.create(context)
        val running =
            live.startSequenceFromPlan(
                PlanReadRepository.create(context).getFocusedAction(plan.id).identity,
                startedAt,
                startedAt,
                ZoneOffset.UTC,
            )
        val completed =
            live.completeCurrentSequenceStep(
                requireNotNull(running.execution.currentOccurrenceId),
                startedAt.plusSeconds(20),
            )
        val executionId = completed.execution.id
        val beforePlan = requireNotNull(plans.getPlan(plan.id))
        val before = requireNotNull(HistoryReadRepository.create(context).getSequenceDetail(executionId))

        enterHistory()
        openSequence(executionId)
        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().performClick()
        replaceTimestamp(
            "sequence-history-timestamp-root-ended",
            before.root.completedAt.plusSeconds(1),
            before.originalZoneId,
        )
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").performScrollTo().performClick()
        composeTestRule.waitUntil(
            5_000,
        ) { plans.getPlan(plan.id)?.fulfilledAt == before.root.completedAt.plusSeconds(1) }

        val afterPlan = requireNotNull(plans.getPlan(plan.id))
        val afterExecution = requireNotNull(HistoryReadRepository.create(context).getSequenceDetail(executionId))
        assertEquals(plan.id, afterPlan.id)
        assertEquals(PlanEntryStatus.FULFILLED, afterPlan.status)
        assertEquals(beforePlan.fulfilledSequenceExecutionId, afterPlan.fulfilledSequenceExecutionId)
        assertEquals(executionId, afterPlan.fulfilledSequenceExecutionId)
        assertEquals(afterExecution.root.completedAt, afterPlan.fulfilledAt)
    }

    private fun clearActiveSession(context: Context) {
        val live = LiveSessionRepository.create(context)
        when (live.getActiveSession()?.kind) {
            ActiveSessionKind.ACTIVITY -> live.completeActiveActivity(Instant.now())
            ActiveSessionKind.SEQUENCE -> live.endSequenceEarly(Instant.now())
            null -> Unit
        }
    }

    private fun enterHistory() {
        val label = composeTestRule.activity.getString(R.string.daily_history)
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(label).performClick()
        val activity = composeTestRule.activity
        val controller =
            activity.historyControllerOwner.get {
                LifeTracingRuntimeGraph.from(activity).createHistoryController()
            }
        composeTestRule.waitUntil(5_000) { controller.state.value.load !is HistoryRootsLoad.Loading }
    }

    private fun openSequence(id: com.alexandr5476.lifetracing.domain.SequenceExecutionId) {
        val tag = "history-sequence-${id.value}"
        val loadMoreLabel = composeTestRule.activity.getString(R.string.manual_history_load_more)
        while (composeTestRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            val loadMore = composeTestRule.onAllNodesWithText(loadMoreLabel).fetchSemanticsNodes()
            check(loadMore.isNotEmpty()) { "History entry ${id.value} is not in the current browse window" }
            composeTestRule.onNodeWithText(loadMoreLabel).performScrollTo().performClick()
            composeTestRule.waitUntil(5_000) {
                composeTestRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                    composeTestRule.onAllNodesWithText(loadMoreLabel).fetchSemanticsNodes().isEmpty()
            }
        }
        composeTestRule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.sequenceHistoryMutationRouteSessions.activeSession
                ?.executionId == id
        }
    }

    private fun backToHistory() {
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.history_back))
            .performClick()
    }

    private fun replaceTimestamp(
        tag: String,
        instant: Instant,
        zoneId: ZoneId,
    ) {
        composeTestRule
            .onNodeWithTag(tag)
            .performScrollTo()
            .performTextReplacement(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.ofInstant(instant, zoneId)),
            )
    }

    private fun resolveOwnerlessIntervals() {
        val proposal =
            requireNotNull(
                composeTestRule.activity.sequenceHistoryMutationRouteSessions.activeSession
                    ?.controller
                    ?.state
                    ?.value
                    ?.structuralProposal,
            )
        proposal.ownerlessPlacements.filterValues { it == null }.keys.forEach { id ->
            composeTestRule
                .onNodeWithTag("sequence-history-ownerless-${id.value}-translated")
                .performScrollTo()
                .performClick()
        }
    }

    private fun historyRoots(
        context: Context,
        date: LocalDate,
    ): List<CompletedSequenceHistoryRoot> =
        HistoryReadRepository
            .create(context)
            .getCompletedRoots(CompletedHistoryQuery(HistoryDateRange(date, date), 100))
            .filterIsInstance<CompletedSequenceHistoryRoot>()

    private fun completedSequence(
        context: Context,
        suffix: String,
        startedAt: Instant,
        stepSeconds: List<Long>,
        timeTrackingMode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        nodeCount: Int = stepSeconds.size,
        endEarly: Boolean = false,
        zoneId: ZoneId = ZoneOffset.UTC,
    ): com.alexandr5476.lifetracing.domain.SequenceExecutionId {
        val authoring = TemplateAuthoringRepository.create(context)
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("History step $suffix", null, timeTrackingMode, null),
                createdAt = startedAt.minusSeconds(2),
            )
        val sequence =
            authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "History sequence $suffix",
                    null,
                    nodes =
                        (0 until nodeCount).map { position ->
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
                    zoneId,
                    sequence.revision,
                )
        var at = startedAt
        stepSeconds.forEach { seconds ->
            at = at.plusSeconds(seconds)
            runtime = live.completeCurrentSequenceStep(requireNotNull(runtime.execution.currentOccurrenceId), at)
        }
        if (endEarly) live.endSequenceEarly(at)
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
