package com.alexandr5476.lifetracing.history

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrence
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrenceActivity
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
        setDetail { SequenceDetail(completed) }
        composeTestRule.onNodeWithText(text(R.string.history_completed)).assertIsDisplayed()
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
