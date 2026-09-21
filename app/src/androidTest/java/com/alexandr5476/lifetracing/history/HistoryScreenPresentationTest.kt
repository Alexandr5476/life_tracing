package com.alexandr5476.lifetracing.history

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCategoryOption
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryChildActivity
import com.alexandr5476.lifetracing.domain.SequenceHistoryChildMutationFacts
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrence
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrenceActivity
import com.alexandr5476.lifetracing.domain.SequenceHistoryTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.ConcurrentModificationException
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

class HistoryScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun activityDetailUsesHistoricalZoneAndPreservesFrozenConfigurationAndValues() {
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        try {
            val zone = ZoneId.of("America/Los_Angeles")
            val detail = activityDetail(TimeTrackingMode.TIMER, Duration.ofMinutes(5), Duration.ofMinutes(2), zone)
            setDetail { ActivityDetail(detail) }

            composeTestRule
                .onNodeWithText(
                    historicalTime(detail.root.startedAt!!, zone),
                    substring = true,
                ).assertIsDisplayed()
            composeTestRule
                .onNodeWithText(
                    historicalTime(requireNotNull(detail.root.startedAt), ZoneId.of("Asia/Tokyo")),
                    substring = true,
                ).assertDoesNotExist()
            composeTestRule.onNodeWithText(text(R.string.history_active_duration, "02:00")).assertIsDisplayed()
            composeTestRule.onNodeWithText(text(R.string.history_timer_target, "05:00")).assertIsDisplayed()
            composeTestRule
                .onNodeWithText(text(R.string.history_field_value, "Weight", "0 kg"))
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithText(text(R.string.history_configured_value, "12.5 kg"))
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithText(text(R.string.history_field_value, "Effort", "Hard"))
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithText(text(R.string.history_configured_value, "Easy"))
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithText(
                    text(R.string.history_field_value, "Notes", text(R.string.history_missing_value)),
                ).performScrollTo()
                .assertIsDisplayed()
            composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(0)
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun noLiveActivityShowsMissingDurationWithoutMutationControls() {
        setDetail {
            ActivityDetail(activityDetail(TimeTrackingMode.NO_LIVE_TRACKING, null, null, ZoneId.of("UTC")))
        }

        composeTestRule
            .onNodeWithText(text(R.string.history_active_duration, text(R.string.history_missing_value)))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_no_live)).assertIsDisplayed()
        composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun productionActivityDetailShowsCorrectionOnlyForNonPlanAndDeleteForBoth() {
        setMutationDetail(activityDetail())
        composeTestRule.onNodeWithTag("history-correct").assertIsDisplayed()
        composeTestRule.onNodeWithTag("history-delete").assertIsDisplayed()
        composeTestRule.onNodeWithTag("history-correct").performClick()
        composeTestRule.onNodeWithTag("history-correction-started").assertIsDisplayed()
    }

    @Test
    fun productionPlanLinkedActivityDetailShowsDeleteWithoutCorrection() {
        val standalone = activityDetail()
        setMutationDetail(
            standalone.copy(root = standalone.root.copy(planEntryId = PlanEntryId("fulfilled-plan"))),
        )
        composeTestRule.onNodeWithTag("history-correct").assertDoesNotExist()
        composeTestRule.onNodeWithTag("history-delete").assertIsDisplayed()
    }

    @Test
    fun deleteFailureStaysInConfirmationAndCanRetryOrCancel() {
        val attempts = AtomicInteger()
        val controller =
            setMutationDetail(activityDetail()) { _, _, _ ->
                attempts.incrementAndGet()
                error("delete failed")
            }
        composeTestRule.onNodeWithTag("history-delete").performClick()
        composeTestRule.onNodeWithTag("history-delete-confirm").performClick()
        composeTestRule.waitUntil(2_000) {
            controller.state.value.issue == ActivityHistoryMutationIssue.DELETE_FAILURE
        }
        composeTestRule.onNodeWithText(text(R.string.history_delete_failure)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("history-delete-confirm").assertIsDisplayed().performClick()
        composeTestRule.waitUntil(2_000) { attempts.get() == 2 }
        composeTestRule.onNodeWithText(text(R.string.manual_history_cancel)).performClick()
        composeTestRule.onNodeWithTag("history-delete-confirm").assertDoesNotExist()
        assertEquals(2, attempts.get())
    }

    @Test
    fun sequenceDetailShowsCanonicalGraphStatesProvenanceAndTombstoneInOrder() {
        val zone = ZoneId.of("America/Los_Angeles")
        val detail = sequenceDetail(SequenceExecutionStatus.ENDED_EARLY, zone)
        setDetail { SequenceDetail(detail) }

        composeTestRule.onNodeWithText(text(R.string.history_ended_early)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_active_duration, "10:00")).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_pause_duration, "02:00")).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_wall_duration, "15:00")).assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(
                historicalTime(detail.root.startedAt, zone),
                substring = true,
            )[0]
            .assertIsDisplayed()

        val performed = hasAnyAncestor(hasTestTag("history-occurrence-performed"))
        composeTestRule.onNode(hasText(text(R.string.history_occurrence_performed)) and performed).performScrollTo()
        composeTestRule.onNode(hasText(text(R.string.history_source_step)) and performed).assertExists()
        composeTestRule.onNode(hasText(text(R.string.history_repeat_iteration, 2)) and performed).assertExists()
        composeTestRule.onNode(hasText(text(R.string.history_runtime_added)) and performed).assertExists()
        composeTestRule.onNode(hasText(text(R.string.history_timer_target, "00:30")) and performed).assertExists()
        composeTestRule.onNode(hasText(text(R.string.history_actual_duration, "01:00")) and performed).assertExists()
        composeTestRule
            .onNode(hasText(text(R.string.history_field_value, "Reps", "7 reps")) and performed)
            .assertExists()

        composeTestRule.onNodeWithTag("history-occurrence-skipped").performScrollTo()
        composeTestRule
            .onNode(
                hasText(text(R.string.history_occurrence_skipped)) and
                    hasAnyAncestor(hasTestTag("history-occurrence-skipped")),
            ).assertExists()
        composeTestRule.onNodeWithTag("history-occurrence-not-started").performScrollTo()
        composeTestRule
            .onNode(
                hasText(text(R.string.history_occurrence_not_started)) and
                    hasAnyAncestor(hasTestTag("history-occurrence-not-started")),
            ).assertExists()
        composeTestRule.onNodeWithTag("history-occurrence-tombstone").performScrollTo()
        composeTestRule
            .onNode(
                hasText(text(R.string.history_deleted_child)) and
                    hasAnyAncestor(hasTestTag("history-occurrence-tombstone")),
            ).assertExists()
        composeTestRule.onNodeWithText("Structurally removed").assertDoesNotExist()

        val intervalLabels =
            listOf(
                R.string.history_interval_active_step,
                R.string.history_interval_step_pause,
                R.string.history_interval_explicit_pause,
                R.string.history_interval_implicit_idle,
                R.string.history_interval_transition_countdown,
            )
        val intervalTops =
            detail.intervals.map { interval ->
                composeTestRule
                    .onNodeWithTag("history-interval-${interval.id.value}")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        assertEquals(intervalTops.sorted(), intervalTops)
        detail.intervals.zip(intervalLabels).forEach { (interval, label) ->
            composeTestRule
                .onNodeWithTag("history-interval-${interval.id.value}")
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule
                .onNode(
                    hasText(text(label)) and
                        hasAnyAncestor(hasTestTag("history-interval-${interval.id.value}")),
                ).assertExists()
        }
        composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun completedSequenceStatusIsPresented() {
        val completed = sequenceDetail(SequenceExecutionStatus.COMPLETED, ZoneId.of("UTC"))
        setSequenceMutationDetail(completed)
        composeTestRule.onNodeWithText(text(R.string.history_completed)).assertIsDisplayed()
    }

    @Test
    fun endedEarlySequenceUsesMutationAwareRoute() {
        setSequenceMutationDetail(
            sequenceMutationDetail().copy(
                root = sequenceMutationDetail().root.copy(status = SequenceExecutionStatus.ENDED_EARLY),
            ),
        )
        composeTestRule.onNodeWithText(text(R.string.history_ended_early)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sequenceTimingCorrectionUsesControllerDraftAndCancelWritesNothing() {
        var timingCommands = 0
        val detail = sequenceMutationDetail()
        setSequenceMutationDetail(detail, correct = { _, _, _ -> timingCommands++ })

        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().performClick()
        composeTestRule
            .onNodeWithTag("sequence-history-timestamp-root-ended")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_active_duration, "10:00")).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.manual_history_cancel)).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().assertIsDisplayed()
        assertEquals(0, timingCommands)
    }

    @Test
    fun timingEditorPresentsSyntaxDstNoChangeAndExplicitOffsetStates() {
        val detail = sequenceMutationDetail().copy(originalZoneId = ZoneId.of("Europe/Berlin"))
        setSequenceMutationDetail(detail, correct = { _, _, _ -> error("unexpected") })
        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().performClick()
        val end = composeTestRule.onNodeWithTag("sequence-history-timestamp-root-ended")

        end.performScrollTo().performTextReplacement("not-a-date")
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithText(text(R.string.manual_history_invalid_datetime)).assertIsDisplayed()

        end.performScrollTo().performTextReplacement("2026-03-29T02:30:00")
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithText(text(R.string.manual_history_nonexistent_time)).assertIsDisplayed()

        end.performScrollTo().performTextReplacement("2026-10-25T02:30:00")
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule
            .onAllNodesWithText(text(R.string.manual_history_ambiguous_time))[0]
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(text(R.string.manual_history_second_occurrence, "UTC+01:00"))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithText(text(R.string.sequence_history_timing_review)).assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.manual_history_cancel)).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithText(text(R.string.sequence_history_no_timing_changes)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").assertDoesNotExist()
    }

    @Test
    fun timingValidationGenericAndStaleFailuresRemainVisibleInReview() {
        var failure: Exception = IllegalArgumentException("validation")
        val controller =
            setSequenceMutationDetail(
                sequenceMutationDetail(),
                correct = { _, _, _ -> throw failure },
            )
        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().performClick()
        val draft =
            requireNotNull(controller.state.value.timingDraft)
                .timestamps
                .getValue(SequenceHistoryTimestampTarget.RootEndedAt)
        composeTestRule
            .onNodeWithTag("sequence-history-timestamp-root-ended")
            .performScrollTo()
            .performTextReplacement(LocalDateTime.parse(draft.text).plusSeconds(1).toString())
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()

        fun confirmFailure(
            expectedIssue: SequenceHistoryMutationIssue,
            expectedMessage: Int,
        ) {
            composeTestRule.onNodeWithTag("sequence-history-confirm-timing").performScrollTo().performClick()
            composeTestRule.waitUntil(2_000) { controller.state.value.issue == expectedIssue }
            composeTestRule.onNodeWithText(text(expectedMessage)).performScrollTo().assertIsDisplayed()
        }

        confirmFailure(SequenceHistoryMutationIssue.INVALID_PROPOSAL, R.string.sequence_history_invalid_proposal)
        failure = IllegalStateException("persistence")
        confirmFailure(SequenceHistoryMutationIssue.TIMING_FAILURE, R.string.sequence_history_timing_failure)
        failure = ConcurrentModificationException("stale")
        confirmFailure(SequenceHistoryMutationIssue.STALE, R.string.history_changed_review)
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").assertDoesNotExist()
    }

    @Test
    fun destructiveSurfacesIdentifyOccurrenceAndCloseGapReviewHidesDeletedChildFacts() {
        val detail = structuralMutationDetail()
        var deletions = 0
        setSequenceMutationDetail(
            detail,
            delete = { _, _, _ -> deletions++ },
        )
        val targetText = text(R.string.history_occurrence, 1, "Performed")

        composeTestRule.onNodeWithTag("sequence-history-delete-child-performed").performScrollTo().performClick()
        composeTestRule.onNodeWithText(targetText).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_repeat_iteration, 2)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_runtime_added)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.manual_history_cancel)).performClick()
        assertEquals(0, deletions)

        composeTestRule.onNodeWithTag("sequence-history-remove-occurrence-performed").performScrollTo().performClick()
        composeTestRule.onNodeWithText(targetText).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-history-close-gap").performScrollTo().performClick()
        composeTestRule
            .onNodeWithText(
                text(
                    R.string.sequence_history_ownerless_interval,
                    text(R.string.history_interval_explicit_pause),
                    "ownerless",
                ),
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasTestTag("sequence-history-ownerless-ownerless-fixed") and
                    hasText(
                        text(
                            R.string.sequence_history_ownerless_fixed_choice,
                            historicalInterval(START.plusSeconds(300), START.plusSeconds(310), detail.originalZoneId),
                        ),
                    ),
            ).assertIsDisplayed()
        composeTestRule
            .onNode(
                hasTestTag("sequence-history-ownerless-ownerless-translated") and
                    hasText(
                        text(
                            R.string.sequence_history_ownerless_translated_choice,
                            historicalInterval(START.plusSeconds(240), START.plusSeconds(250), detail.originalZoneId),
                        ),
                    ),
            ).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag("sequence-history-ownerless-ownerless-fixed")
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNodeWithTag("sequence-history-ownerless-ownerless-2-translated")
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithText(text(R.string.sequence_history_structural_review)).assertIsDisplayed()
        composeTestRule.onNodeWithText(targetText).assertIsDisplayed()
        composeTestRule.onNodeWithText("pause-retained", substring = true).performScrollTo().assertIsDisplayed()
        val deletedTarget = text(R.string.history_occurrence, 2, "Deleted later")
        composeTestRule
            .onNodeWithText(text(R.string.sequence_history_child_started, deletedTarget))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(text(R.string.sequence_history_child_completed, deletedTarget))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(
                text(
                    R.string.sequence_history_ownerless_selection,
                    text(R.string.history_interval_explicit_pause),
                    "ownerless",
                    text(R.string.sequence_history_fixed),
                    historicalInterval(START.plusSeconds(300), START.plusSeconds(310), detail.originalZoneId),
                ),
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                text(
                    R.string.sequence_history_ownerless_selection,
                    text(R.string.history_interval_implicit_idle),
                    "ownerless-2",
                    text(R.string.sequence_history_translated),
                    historicalInterval(START.plusSeconds(270), START.plusSeconds(280), detail.originalZoneId),
                ),
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun leaveGapReviewShowsEveryTargetOwnedIntervalRemovalBeforeConfirmation() {
        val detail = structuralMutationDetail()
        setSequenceMutationDetail(detail)

        composeTestRule.onNodeWithTag("sequence-history-remove-occurrence-performed").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-leave-gap").performScrollTo().performClick()

        composeTestRule
            .onNodeWithText(
                text(
                    R.string.sequence_history_removed_interval,
                    text(R.string.history_interval_active_step),
                    "performed",
                    historicalInterval(START.plusSeconds(10), START.plusSeconds(70), detail.originalZoneId),
                ),
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-history-confirm-structural").assertIsDisplayed()
    }

    @Test
    fun readDeletionAndStructuralFailuresHaveDistinctActiveSurfaceMessages() {
        val detail = structuralMutationDetail()
        var readFails = true
        val controller =
            SequenceHistoryMutationController(
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                detail.root.executionId,
                { if (readFails) error("read") else detail },
                { _, _, _ -> },
                { _, _, _ -> error("delete") },
                { _, _, _ -> error("structural") },
            )
        composeTestRule.setContent {
            LifeTracingTheme {
                SequenceHistoryDetailRoute(
                    SequenceHistoryMutationRouteSession(detail.root.executionId, controller),
                    {},
                    {},
                )
            }
        }
        composeTestRule.waitUntil(2_000) { controller.state.value.issue == SequenceHistoryMutationIssue.READ_FAILURE }
        composeTestRule.onNodeWithText(text(R.string.history_read_failure)).assertIsDisplayed()
        readFails = false
        controller.dispatch(SequenceHistoryMutationAction.Retry)
        composeTestRule.waitUntil(2_000) { controller.state.value.load is HistoryDetailLoad.Content }

        composeTestRule.onNodeWithTag("sequence-history-delete-child-performed").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").performScrollTo().performClick()
        composeTestRule.waitUntil(2_000) {
            controller.state.value.issue == SequenceHistoryMutationIssue.CHILD_DELETION_FAILURE
        }
        composeTestRule.onNodeWithText(text(R.string.sequence_history_child_deletion_failure)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.manual_history_cancel)).performScrollTo().performClick()

        composeTestRule.onNodeWithTag("sequence-history-remove-occurrence-performed").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-leave-gap").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-structural").performScrollTo().performClick()
        composeTestRule.waitUntil(2_000) {
            controller.state.value.issue == SequenceHistoryMutationIssue.STRUCTURAL_FAILURE
        }
        composeTestRule.onNodeWithText(text(R.string.sequence_history_structural_failure)).assertIsDisplayed()
    }

    @Test
    fun inFlightConfirmationIsDisabledUntilRepositoryReturns() {
        val gate = CompletableDeferred<Unit>()
        val controller =
            setSequenceMutationDetail(
                sequenceMutationDetail(),
                correct = { _, _, _ -> gate.await() },
            )
        composeTestRule.onNodeWithTag("sequence-history-timing").performScrollTo().performClick()
        val target = SequenceHistoryTimestampTarget.RootEndedAt
        val draft = requireNotNull(controller.state.value.timingDraft).timestamps.getValue(target)
        composeTestRule
            .onNodeWithTag("sequence-history-timestamp-root-ended")
            .performScrollTo()
            .performTextReplacement(LocalDateTime.parse(draft.text).plusSeconds(1).toString())
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").performScrollTo().performClick()
        composeTestRule.waitUntil(2_000) { controller.state.value.isMutating }
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").assertIsNotEnabled()
        gate.complete(Unit)
    }

    @Test
    fun completedSequenceStatusAndSummaryOnlyMixedListArePresentedWithoutCurrentZoneClocks() {
        val suppliedDate = LocalDate.parse("2001-02-03")
        val activity = activityDetail().root.copy(primaryLocalDate = suppliedDate, title = "Activity summary")
        val sequence =
            sequenceDetail(SequenceExecutionStatus.COMPLETED, ZoneId.of("UTC"))
                .root
                .copy(primaryLocalDate = suppliedDate, title = "Sequence summary")
        composeTestRule.setContent {
            LifeTracingTheme {
                HistoryScreen(
                    HistoryPresentationState(
                        HistoryBrowseWindow(suppliedDate.minusDays(1), suppliedDate.plusDays(1)),
                        HistoryRootsLoad.Content(listOf(activity, sequence)),
                    ),
                    {},
                )
            }
        }

        composeTestRule.onNodeWithText(localDate(suppliedDate)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_completed)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("history-activity-${activity.executionId.value}").assertExists()
        composeTestRule.onNodeWithTag("history-sequence-${sequence.executionId.value}").assertExists()
        composeTestRule
            .onNodeWithText(historicalTime(activity.completedAt, ZoneId.systemDefault()), substring = true)
            .assertDoesNotExist()
        assertTrue(activity.completedAt.toString().startsWith("2026"))
    }

    private fun setDetail(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            LifeTracingTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { content() }
            }
        }
    }

    private fun setMutationDetail(
        detail: ActivityHistoryDetail,
        delete: suspend (ActivityExecutionId, Instant, Instant) -> Unit = { _, _, _ -> },
    ): ActivityHistoryMutationController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller =
            ActivityHistoryMutationController(
                scope,
                detail.root.executionId,
                { detail },
                { _, _, _ -> },
                delete,
                { END.plusSeconds(1) },
            )
        val session = ActivityHistoryMutationRouteSession(detail.root.executionId, controller)
        composeTestRule.setContent {
            LifeTracingTheme {
                ActivityHistoryDetailRoute(session, {}, {}, {})
            }
        }
        composeTestRule.waitUntil(2_000) {
            controller.state.value.load is HistoryDetailLoad.Content
        }
        return controller
    }

    private fun setSequenceMutationDetail(
        detail: SequenceHistoryDetail,
        correct: suspend (
            SequenceExecutionId,
            SequenceHistoryTimingCorrection,
            Instant,
        ) -> Unit = { _, _, _ -> },
        delete: suspend (
            SequenceExecutionId,
            com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionCommand,
            Instant,
        ) -> Unit = { _, _, _ -> },
        remove: suspend (
            SequenceExecutionId,
            com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalCommand,
            Instant,
        ) -> Unit = { _, _, _ -> },
    ): SequenceHistoryMutationController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller =
            SequenceHistoryMutationController(
                scope,
                detail.root.executionId,
                { detail },
                correct,
                delete,
                remove,
            )
        composeTestRule.setContent {
            LifeTracingTheme {
                SequenceHistoryDetailRoute(
                    SequenceHistoryMutationRouteSession(detail.root.executionId, controller),
                    onBack = {},
                    onRefresh = {},
                )
            }
        }
        composeTestRule.waitUntil(2_000) { controller.state.value.load is HistoryDetailLoad.Content }
        return controller
    }

    private fun text(
        id: Int,
        vararg arguments: Any,
    ): String = composeTestRule.activity.getString(id, *arguments)

    private fun historicalTime(
        instant: Instant,
        zoneId: ZoneId,
    ): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(composeTestRule.activity.resources.configuration.locales[0])
            .format(instant.atZone(zoneId))

    private fun historicalInterval(
        startedAt: Instant,
        endedAt: Instant,
        zoneId: ZoneId,
    ): String = "${historicalTime(startedAt, zoneId)} – ${historicalTime(endedAt, zoneId)}"

    private fun localDate(date: LocalDate): String =
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(composeTestRule.activity.resources.configuration.locales[0])
            .format(date)

    private fun activityDetail(
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        target: Duration? = null,
        duration: Duration? = Duration.ofMinutes(2),
        zone: ZoneId = ZoneId.of("UTC"),
    ): ActivityHistoryDetail {
        val easy = ActivitySnapshotCategoryOptionId("easy")
        val hard = ActivitySnapshotCategoryOptionId("hard")
        return ActivityHistoryDetail(
            CompletedActivityHistoryRoot(
                ActivityExecutionId("activity"),
                ActivitySnapshotId("activity-snapshot"),
                LocalDate.parse("2026-01-01"),
                END,
                START,
                duration,
                null,
                "Historical activity",
                "Frozen comment",
                mode,
                target,
            ),
            END,
            zone,
            ActivityTemplateSettings(),
            listOf(
                ActivityHistoryField(
                    ActivitySnapshotFieldId("weight"),
                    "Weight",
                    CustomFieldType.NUMBER,
                    "kg",
                    1,
                    true,
                    ActivityHistoryConfiguredValue.Number(12_500),
                    ActivityHistoryActualValue.Number(0),
                    emptyList(),
                ),
                ActivityHistoryField(
                    ActivitySnapshotFieldId("effort"),
                    "Effort",
                    CustomFieldType.CATEGORY,
                    null,
                    null,
                    false,
                    ActivityHistoryConfiguredValue.Category(easy),
                    ActivityHistoryActualValue.Category(hard, "Hard"),
                    listOf(ActivityHistoryCategoryOption(easy, "Easy"), ActivityHistoryCategoryOption(hard, "Hard")),
                ),
                ActivityHistoryField(
                    ActivitySnapshotFieldId("notes"),
                    "Notes",
                    CustomFieldType.TEXT,
                    null,
                    null,
                    false,
                    ActivityHistoryConfiguredValue.Text("Default"),
                    ActivityHistoryActualValue.Missing,
                    emptyList(),
                ),
            ),
        )
    }

    private fun sequenceDetail(
        status: SequenceExecutionStatus,
        zone: ZoneId,
    ): SequenceHistoryDetail {
        val performedActivity = occurrenceActivity("Performed", TimeTrackingMode.TIMER, Duration.ofSeconds(30))
        val childField =
            ActivityHistoryField(
                ActivitySnapshotFieldId("reps"),
                "Reps",
                CustomFieldType.NUMBER,
                "reps",
                0,
                true,
                ActivityHistoryConfiguredValue.Missing,
                ActivityHistoryActualValue.Number(7_000),
                emptyList(),
            )
        val occurrences =
            listOf(
                SequenceHistoryOccurrence(
                    SequenceOccurrenceId("performed"),
                    0,
                    performedActivity.snapshotId,
                    SequenceSnapshotNodeId("source-step"),
                    SequenceSnapshotNodeId("repeat"),
                    2,
                    true,
                    false,
                    RuntimeOccurrenceStatus.COMPLETED,
                    START.plusSeconds(10),
                    START.plusSeconds(70),
                    null,
                    performedActivity.copy(
                        mainValue = childField.copy(actualValue = ActivityHistoryActualValue.Missing),
                    ),
                    SequenceHistoryChildActivity(
                        ActivityExecutionId("child"),
                        ActivityExecutionStatus.COMPLETED,
                        START.plusSeconds(10),
                        START.plusSeconds(70),
                        Duration.ofMinutes(1),
                        listOf(childField),
                    ),
                ),
                occurrence("skipped", 1, RuntimeOccurrenceStatus.SKIPPED),
                occurrence("not-started", 2, RuntimeOccurrenceStatus.NOT_STARTED),
                occurrence("tombstone", 3, RuntimeOccurrenceStatus.DELETED_EXECUTION),
            )
        val intervals =
            SequenceIntervalKind.entries.mapIndexed { index, kind ->
                SequenceInterval(
                    SequenceIntervalId("interval-$index"),
                    kind,
                    START.plusSeconds(index * 10L),
                    START.plusSeconds(index * 10L + 5),
                    occurrences.getOrNull(index)?.occurrenceId,
                )
            }
        return SequenceHistoryDetail(
            CompletedSequenceHistoryRoot(
                SequenceExecutionId("sequence"),
                SequenceSnapshotId("sequence-snapshot"),
                LocalDate.parse("2026-01-01"),
                END,
                START,
                status,
                Duration.ofMinutes(10),
                Duration.ofMinutes(2),
                Duration.ofMinutes(15),
                null,
                "Historical sequence",
                "Frozen sequence comment",
            ),
            END,
            zone,
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
            occurrences,
            intervals,
        )
    }

    private fun sequenceMutationDetail(): SequenceHistoryDetail {
        val base = sequenceDetail(SequenceExecutionStatus.COMPLETED, ZoneId.of("America/Los_Angeles"))
        return base.copy(
            occurrences =
                base.occurrences.map { occurrence ->
                    occurrence.takeIf { it.occurrenceId.value == "performed" }?.let {
                        val child = requireNotNull(it.child)
                        it.copy(
                            childMutationFacts =
                                SequenceHistoryChildMutationFacts(
                                    child.executionId,
                                    child.startedAt,
                                    requireNotNull(child.completedAt),
                                    emptyList(),
                                ),
                        )
                    } ?: occurrence
                },
        )
    }

    private fun structuralMutationDetail(): SequenceHistoryDetail {
        val base = sequenceMutationDetail()
        val performed = base.occurrences.first()

        fun performedOccurrence(
            id: String,
            position: Int,
            title: String,
            start: Instant,
            end: Instant,
            deleted: Boolean,
        ): SequenceHistoryOccurrence {
            val activity = occurrenceActivity(title)
            val childId = ActivityExecutionId("child-$id")
            return SequenceHistoryOccurrence(
                SequenceOccurrenceId(id),
                position,
                activity.snapshotId,
                null,
                null,
                null,
                false,
                false,
                if (deleted) RuntimeOccurrenceStatus.DELETED_EXECUTION else RuntimeOccurrenceStatus.COMPLETED,
                start,
                end,
                null,
                activity,
                if (deleted) {
                    null
                } else {
                    SequenceHistoryChildActivity(
                        childId,
                        ActivityExecutionStatus.COMPLETED,
                        start,
                        end,
                        Duration.between(start, end),
                        emptyList(),
                    )
                },
                SequenceHistoryChildMutationFacts(
                    childId,
                    start,
                    end,
                    listOf(
                        com.alexandr5476.lifetracing.domain.ActivityExecutionPause(
                            com.alexandr5476.lifetracing.domain
                                .ActivityExecutionPauseId("pause-$id"),
                            start.plusSeconds(10),
                            start.plusSeconds(20),
                        ),
                    ),
                ),
            )
        }
        val deleted =
            performedOccurrence(
                "deleted-later",
                1,
                "Deleted later",
                START.plusSeconds(70),
                START.plusSeconds(130),
                true,
            )
        val retained =
            performedOccurrence("retained", 2, "Retained later", START.plusSeconds(130), START.plusSeconds(190), false)
        return base.copy(
            occurrences = listOf(performed, deleted, retained),
            intervals =
                listOf(
                    SequenceInterval(
                        SequenceIntervalId("performed"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        START.plusSeconds(10),
                        START.plusSeconds(70),
                        performed.occurrenceId,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("deleted-later"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        START.plusSeconds(70),
                        START.plusSeconds(130),
                        deleted.occurrenceId,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("retained"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        START.plusSeconds(130),
                        START.plusSeconds(190),
                        retained.occurrenceId,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("ownerless"),
                        SequenceIntervalKind.EXPLICIT_PAUSE,
                        START.plusSeconds(300),
                        START.plusSeconds(310),
                        null,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("ownerless-2"),
                        SequenceIntervalKind.IMPLICIT_IDLE,
                        START.plusSeconds(330),
                        START.plusSeconds(340),
                        null,
                    ),
                ),
        )
    }

    private fun occurrence(
        id: String,
        position: Int,
        status: RuntimeOccurrenceStatus,
    ) = SequenceHistoryOccurrence(
        SequenceOccurrenceId(id),
        position,
        ActivitySnapshotId("snapshot-$id"),
        null,
        null,
        null,
        false,
        false,
        status,
        null,
        null,
        null,
        occurrenceActivity(id),
        null,
    )

    private fun occurrenceActivity(
        title: String,
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        target: Duration? = null,
    ) = SequenceHistoryOccurrenceActivity(
        ActivitySnapshotId("snapshot-${title.lowercase()}"),
        title,
        null,
        mode,
        target,
        ActivityTemplateSettings(),
        null,
    )

    companion object {
        private val START = Instant.parse("2026-01-02T01:00:00Z")
        private val END = START.plusSeconds(900)
    }
}
