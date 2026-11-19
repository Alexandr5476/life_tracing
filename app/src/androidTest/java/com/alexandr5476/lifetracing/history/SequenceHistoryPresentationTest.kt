@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions", "LargeClass", "MaxLineLength")

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.alexandr5476.lifetracing.history

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCategoryOption
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryActualValue
import com.alexandr5476.lifetracing.domain.SequenceHistoryCategoryOption
import com.alexandr5476.lifetracing.domain.SequenceHistoryChildActivity
import com.alexandr5476.lifetracing.domain.SequenceHistoryChildMutationFacts
import com.alexandr5476.lifetracing.domain.SequenceHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryField
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrence
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrenceActivity
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalMode
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged
import com.alexandr5476.lifetracing.ui.exactHistoricalTimestamp
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class SequenceHistoryPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun rootsExposeExecutionSnapshotPlanAndExactOverlapEndpoints() {
        val current = mutableStateOf(detail(), referentialEqualityPolicy())
        compose.setContent { TestTheme { SequenceDetail(current.value) } }
        for (status in listOf(SequenceExecutionStatus.COMPLETED, SequenceExecutionStatus.ENDED_EARLY)) {
            compose.runOnIdle { current.value = current.value.copy(root = current.value.root.copy(status = status)) }
            text(R.string.sequence_history_execution_id, "root").assertExists()
            text(R.string.sequence_history_snapshot_id, "frozen-root").assertExists()
            text(R.string.sequence_history_plan_id, "plan").assertExists()
            compose.onNodeWithText("Frozen root").assertExists()
            compose.onNodeWithText("Frozen comment").assertExists()
            text(
                if (status ==
                    SequenceExecutionStatus.COMPLETED
                ) {
                    R.string.history_completed
                } else {
                    R.string.history_ended_early
                },
            ).assertExists()
            val root = compose.onNodeWithTag("sequence-history-root")
            root.assert(hasText("2026-10-25T02:30:12.123+02:00[Europe/Berlin]", substring = true))
            root.assert(hasText("2026-10-25T02:30:12.124+01:00[Europe/Berlin]", substring = true))
            text(R.string.history_active_duration, "00:30").assertExists()
            text(R.string.history_pause_duration, "59:30").assertExists()
            text(R.string.history_wall_duration, "1:00:00").assertExists()
        }
    }

    @Test
    fun crossDaySpringTransitionPreservesBothRootDatesAndOffsets() {
        val base = detail()
        val first = Instant.parse("2026-03-28T22:59:59.999Z")
        val last = Instant.parse("2026-03-29T01:30:00.001Z")
        compose.setContent {
            TestTheme { SequenceDetail(base.copy(root = base.root.copy(startedAt = first, completedAt = last))) }
        }
        val root = compose.onNodeWithTag("sequence-history-root")
        root.assert(hasText(exactHistoricalTimestamp(first, zone), substring = true))
        root.assert(hasText(exactHistoricalTimestamp(last, zone), substring = true))
    }

    @Test
    fun identicalOccurrencesExposeIndependentIdentityFrozenSourceRepeatAndEveryReason() {
        val reasons = OccurrenceCompletionReason.entries + null
        val occurrences = reasons.mapIndexed { index, reason -> occurrence(index).copy(completionReason = reason) }
        compose.setContent { TestTheme { SequenceDetail(detail(occurrences)) } }
        val labels =
            listOf(
                R.string.sequence_history_reason_timer,
                R.string.sequence_history_reason_manual,
                R.string.sequence_history_reason_advance,
                R.string.sequence_history_reason_jump,
                R.string.sequence_history_reason_early,
                R.string.sequence_history_reason_missing,
            )
        reasons.indices.forEach { index ->
            scroll("occurrence:o$index")
            text(R.string.sequence_history_occurrence_id, "o$index").assertExists()
            text(R.string.sequence_history_activity_snapshot_id, "same-snapshot").assertExists()
            text(R.string.sequence_history_source_id, "frozen-step").assertExists()
            text(R.string.sequence_history_repeat_id, "frozen-repeat").assertExists()
            text(R.string.history_repeat_iteration, 1).assertExists()
            text(R.string.sequence_history_not_runtime_added).assertExists()
            text(R.string.sequence_history_completion_reason, compose.activity.getString(labels[index])).assertExists()
        }
    }

    @Test
    fun unperformedOccurrencesKeepConfiguredMainValueWithoutInventingChildFacts() {
        val main = activityField().copy(configuredValue = ActivityHistoryConfiguredValue.Number(0))
        val occurrences =
            listOf(RuntimeOccurrenceStatus.SKIPPED, RuntimeOccurrenceStatus.NOT_STARTED).mapIndexed { index, status ->
                occurrence(index, status).copy(
                    sourceSequenceSnapshotNodeId = null,
                    repeatSourceSnapshotNodeId = null,
                    repeatIteration = null,
                    isRuntimeAdded = true,
                    activity = occurrence(index).activity.copy(mainValue = main),
                )
            }
        compose.setContent { TestTheme { SequenceDetail(detail(occurrences)) } }
        occurrences.forEach { occurrence ->
            scroll("occurrence:${occurrence.occurrenceId.value}")
            text(R.string.history_runtime_added).assertExists()
            text(
                R.string.sequence_history_source_id,
                compose.activity.getString(R.string.history_missing_value),
            ).assertExists()
            description(R.string.history_configured_value, "0").assertExists()
            compose.onNodeWithContentDescription("actual:", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("sequence-child-child-${occurrence.occurrenceId.value}").assertDoesNotExist()
            // These summaries carry no entered/completed facts, even though the root has endpoints.
            compose
                .onNodeWithTag("sequence-occurrence-summary-${occurrence.occurrenceId.value}")
                .assert(
                    SemanticsMatcher("no invented entered boundary") {
                        it.config
                            .getOrElse(SemanticsProperties.Text) { emptyList() }
                            .none { value -> value.text.contains("2026-") }
                    },
                )
        }
    }

    @Test
    fun childOwnZoneNoLiveMissingAndTimedZeroRemainDistinctFromTombstone() {
        val noLive =
            occurrence(0).let {
                it.copy(
                    activity = it.activity.copy(timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING),
                    child = it.child!!.copy(startedAt = null, activeDuration = null),
                )
            }
        val optionA = ActivitySnapshotCategoryOptionId("child-a")
        val optionB = ActivitySnapshotCategoryOptionId("child-b")
        val textField =
            ActivityHistoryField(
                ActivitySnapshotFieldId("child-text"),
                "Same field",
                CustomFieldType.TEXT,
                null,
                null,
                false,
                ActivityHistoryConfiguredValue.Text(""),
                ActivityHistoryActualValue.Text(""),
                emptyList(),
            )
        val categoryField =
            ActivityHistoryField(
                ActivitySnapshotFieldId("child-category"),
                "Same field",
                CustomFieldType.CATEGORY,
                null,
                null,
                false,
                ActivityHistoryConfiguredValue.Category(optionA),
                ActivityHistoryActualValue.Category(optionB, "Same option"),
                listOf(
                    ActivityHistoryCategoryOption(optionA, "Same option"),
                    ActivityHistoryCategoryOption(optionB, "Same option"),
                ),
            )
        val zero =
            occurrence(1).let {
                it.copy(
                    child =
                        it.child!!.copy(
                            activeDuration = Duration.ZERO,
                            fields = listOf(activityField(), textField, categoryField),
                        ),
                )
            }
        val fractional = occurrence(3).let { it.copy(child = it.child!!.copy(activeDuration = Duration.ofMillis(1))) }
        val deleted = occurrence(2).copy(status = RuntimeOccurrenceStatus.DELETED_EXECUTION, child = null)
        compose.setContent { TestTheme { SequenceDetail(detail(listOf(noLive, zero, deleted, fractional))) } }
        scroll("occurrence:o0")
        text(R.string.sequence_history_child_id, "child-o0").assertExists()
        text(R.string.sequence_history_zone, "Asia/Tokyo").assertExists()
        text(
            R.string.history_actual_duration,
            compose.activity.getString(R.string.history_missing_value),
        ).assertExists()
        compose
            .onNode(
                hasText(exactHistoricalTimestamp(end, ZoneId.of("Asia/Tokyo")), substring = true) and scope(),
            ).assertExists()
        scroll("occurrence:o1")
        text(R.string.history_actual_duration, "00:00").assertExists()
        compose
            .onAllNodes(
                hasText(compose.activity.getString(R.string.history_stopwatch)) and scope(),
            ).assertCountEquals(1)
        description(
            R.string.history_actual_field,
            "Same field",
            compose.activity.getString(R.string.manual_history_present_empty),
        ).assertExists()
        description(R.string.sequence_history_configured_option, "child-a").assertExists()
        description(R.string.sequence_history_actual_option, "child-b").assertExists()
        description(R.string.history_actual_field, "Same field", "7").assertExists()
        scroll("occurrence:o2")
        text(R.string.history_deleted_child).assertExists()
        text(R.string.sequence_history_deleted_detail).assertExists()
        text(R.string.sequence_history_deleted_child_id, "child-o2").assertExists()
        compose.onNode(hasContentDescription("actual:", substring = true) and scope()).assertDoesNotExist()
        compose.onNodeWithTag("sequence-child-child-o2").assertDoesNotExist()
        scroll("occurrence:o3")
        text(R.string.history_actual_duration, "00:00.001").assertExists()
    }

    @Test
    fun sequenceFieldsKeepMissingZeroEmptyAndDuplicateFieldAndOptionIdentities() {
        val optionA = SequenceSnapshotCategoryOptionId("a")
        val optionB = SequenceSnapshotCategoryOptionId("b")
        val fields =
            listOf(
                field(
                    "number",
                    CustomFieldType.NUMBER,
                    SequenceHistoryConfiguredValue.Number(3_000),
                    SequenceHistoryActualValue.Number(0),
                ),
                field(
                    "number-missing",
                    CustomFieldType.NUMBER,
                    SequenceHistoryConfiguredValue.Missing,
                    SequenceHistoryActualValue.Missing,
                ),
                field(
                    "category-missing",
                    CustomFieldType.CATEGORY,
                    SequenceHistoryConfiguredValue.Missing,
                    SequenceHistoryActualValue.Missing,
                ),
                field(
                    "missing",
                    CustomFieldType.TEXT,
                    SequenceHistoryConfiguredValue.Missing,
                    SequenceHistoryActualValue.Missing,
                ),
                field(
                    "empty",
                    CustomFieldType.TEXT,
                    SequenceHistoryConfiguredValue.Text(""),
                    SequenceHistoryActualValue.Text(""),
                ),
                field(
                    "text",
                    CustomFieldType.TEXT,
                    SequenceHistoryConfiguredValue.Text("Default"),
                    SequenceHistoryActualValue.Text("Recorded"),
                ),
                field(
                    "category",
                    CustomFieldType.CATEGORY,
                    SequenceHistoryConfiguredValue.Category(optionA),
                    SequenceHistoryActualValue.Category(optionB, "Same option"),
                ).copy(
                    categoryOptions =
                        listOf(
                            SequenceHistoryCategoryOption(optionA, "Same option"),
                            SequenceHistoryCategoryOption(optionB, "Same option"),
                        ),
                ),
            )
        compose.setContent { TestTheme { SequenceDetail(detail().copy(fields = fields)) } }
        fields.forEach { field ->
            scroll("sequence-field:${field.id.value}")
            description(R.string.sequence_history_field_id, "Same field", field.id.value).assertExists()
        }
        scroll("sequence-field:number")
        description(R.string.history_actual_field, "Same field", "0").assertExists()
        description(R.string.history_configured_value, "3").assertExists()
        for (id in listOf("number-missing", "category-missing")) {
            scroll("sequence-field:$id")
            description(
                R.string.history_actual_field,
                "Same field",
                compose.activity.getString(R.string.history_missing_value),
            ).assertExists()
            description(
                R.string.history_configured_value,
                compose.activity.getString(R.string.history_missing_value),
            ).assertExists()
        }
        scroll("sequence-field:empty")
        description(
            R.string.history_actual_field,
            "Same field",
            compose.activity.getString(R.string.manual_history_present_empty),
        ).assertExists()
        description(
            R.string.history_configured_value,
            compose.activity.getString(R.string.manual_history_present_empty),
        ).assertExists()
        scroll("sequence-field:missing")
        description(
            R.string.history_actual_field,
            "Same field",
            compose.activity.getString(R.string.history_missing_value),
        ).assertExists()
        scroll("sequence-field:text")
        description(R.string.history_actual_field, "Same field", "Recorded").assertExists()
        description(R.string.history_configured_value, "Default").assertExists()
        scroll("sequence-field:category")
        description(R.string.sequence_history_configured_option, "a").assertExists()
        description(R.string.sequence_history_actual_option, "b").assertExists()
        description(R.string.sequence_history_option_id, "Same option", "a").assertExists()
        description(R.string.sequence_history_option_id, "Same option", "b").assertExists()
    }

    @Test
    fun intervalsExposeEveryKindExactIdentityOwnershipAndEndpointPrecision() {
        val paused =
            occurrence(1).let {
                it.copy(
                    activity = it.activity.copy(timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING),
                    child = it.child!!.copy(startedAt = null, activeDuration = null),
                )
            }
        val occurrences =
            listOf(
                occurrence(0),
                paused,
                occurrence(2, RuntimeOccurrenceStatus.SKIPPED),
                occurrence(3, RuntimeOccurrenceStatus.NOT_STARTED),
            )
        val intervals =
            SequenceIntervalKind.entries.mapIndexed { index, kind ->
                val owner =
                    when (kind) {
                        SequenceIntervalKind.ACTIVE_STEP -> occurrences[0].occurrenceId
                        SequenceIntervalKind.STEP_PAUSE -> occurrences[1].occurrenceId
                        SequenceIntervalKind.TRANSITION_COUNTDOWN -> occurrences[2].occurrenceId
                        else -> null
                    }
                SequenceInterval(SequenceIntervalId("i$index"), kind, start, end, owner)
            } +
                SequenceInterval(
                    SequenceIntervalId("i5"),
                    SequenceIntervalKind.TRANSITION_COUNTDOWN,
                    start,
                    end,
                    occurrences[3].occurrenceId,
                )
        val selected =
            detail(occurrences).let {
                it.copy(
                    intervals = intervals,
                    settings = it.settings.copy(noLiveTimeAccounting = NoLiveTimeAccounting.PAUSE),
                )
            }
        compose.setContent { TestTheme { SequenceDetail(selected) } }
        val labels =
            listOf(
                R.string.history_interval_active_step,
                R.string.history_interval_step_pause,
                R.string.history_interval_explicit_pause,
                R.string.history_interval_implicit_idle,
                R.string.history_interval_transition_countdown,
                R.string.history_interval_transition_countdown,
            )
        intervals.forEachIndexed { index, interval ->
            scroll("interval:i$index")
            text(labels[index]).assertExists()
            text(R.string.sequence_history_interval_id, "i$index").assertExists()
            if (interval.occurrenceId == null) {
                text(R.string.sequence_history_ownerless).assertExists()
            } else {
                text(R.string.sequence_history_owner_id, requireNotNull(interval.occurrenceId).value).assertExists()
            }
            text(R.string.history_interval_started, exactHistoricalTimestamp(start, zone)).assertExists()
            text(R.string.history_interval_ended, exactHistoricalTimestamp(end, zone)).assertExists()
        }
    }

    @Test
    fun tenThousandRowsStayBoundedAndReusedNativeControlsKeepExactTargets() {
        val detail = detail(List(10_000) { occurrence(it) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var reads = 0
        var writes = 0
        val controller =
            SequenceHistoryMutationController(
                scope,
                detail.root.executionId,
                {
                    reads++
                    detail
                },
                { _, _, _ -> writes++ },
                { _, _, _ -> writes++ },
                { _, _, _ -> writes++ },
            )
        try {
            compose.setContent {
                TestTheme {
                    SequenceHistoryDetailRoute(
                        SequenceHistoryMutationRouteSession(detail.root.executionId, controller),
                        {},
                        {},
                    )
                }
            }
            compose.waitUntil { controller.state.value.load is HistoryDetailLoad.Content }
            compose.onNodeWithTag("history-occurrence-o9999").assertDoesNotExist()
            compose.onNodeWithTag("history-interval-i4").assertDoesNotExist()
            for (index in listOf(0, 5_000, 9_999, 0)) {
                scroll("occurrence:o$index")
                text(R.string.sequence_history_occurrence_id, "o$index").assertExists()
                compose.onNodeWithTag("sequence-history-delete-child-o$index").performScrollTo().performClick()
                compose.runOnIdle {
                    assertEquals(
                        SequenceOccurrenceId("o$index"),
                        controller.state.value.childDeletionProposal
                            ?.occurrenceId,
                    )
                }
                compose.onNodeWithTag("sequence-child-deletion-cancel").performScrollTo().performClick()
            }
            scroll("interval:i4")
            text(R.string.sequence_history_interval_id, "i4").assertExists()
            compose.runOnIdle {
                assertEquals(1, reads)
                assertEquals(0, writes)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun keyboardSummaryFocusSurvivesPublicationAndRecoversAfterCanonicalRemoval() {
        val publication =
            mutableStateOf(
                SequenceHistoryMutationState(load = HistoryDetailLoad.Content(detail())),
                referentialEqualityPolicy(),
            )
        compose.setContent {
            TestTheme {
                val focus = rememberHistoryFocus(true, false, false, { true })
                CompositionLocalProvider(LocalHistoryFocus provides focus) {
                    SequenceHistoryDetailSurface(publication.value, {}, {}, publication.value.load, {}, true, false) {}
                }
            }
        }
        keyboard.enter(text(R.string.history_back))
        keyboard.awaitFocus(text(R.string.history_back))
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        keyboard.awaitFocus(compose.onNodeWithTag("sequence-history-root"))
        scroll("occurrence:o0")
        val target = compose.onNodeWithTag("sequence-occurrence-summary-o0")
        target.performSemanticsAction(SemanticsActions.RequestFocus)
        target.assertIsFocused()
        compose.runOnIdle {
            publication.value =
                publication.value.copy(
                    publication = publication.value.publication + 1,
                )
        }
        target.assertIsFocused()
        compose.runOnIdle {
            val current = (publication.value.load as HistoryDetailLoad.Content).value
            publication.value =
                publication.value.copy(
                    load =
                        HistoryDetailLoad.Content(
                            current.copy(
                                root = current.root.copy(title = "Reloaded root"),
                                occurrences = emptyList(),
                                intervals = emptyList(),
                            ),
                        ),
                )
        }
        selectedKey = null
        keyboard.awaitFocus(text(R.string.history_back))
        compose.onNodeWithTag("sequence-occurrence-summary-o0").assertDoesNotExist()
        scroll("sequence:root")
        compose.onNodeWithText("Reloaded root").assertExists()
        compose.onNodeWithText("Frozen root").assertDoesNotExist()
    }

    @Test
    fun removedChildSummaryRecoversWithRetainedTombstoneOccurrence() {
        nestedRemovalRecovers("sequence-child-child-o0") { current ->
            current.copy(status = RuntimeOccurrenceStatus.DELETED_EXECUTION, child = null)
        }
    }

    @Test
    fun removedChildFieldRecoversWithRetainedPerformedOccurrence() {
        nestedRemovalRecovers("sequence-history-field-frozen-child-field") { current ->
            current.copy(child = requireNotNull(current.child).copy(fields = emptyList()))
        }
    }

    private fun nestedRemovalRecovers(
        tag: String,
        replace: (SequenceHistoryOccurrence) -> SequenceHistoryOccurrence,
    ) {
        val publication =
            mutableStateOf(
                SequenceHistoryMutationState(load = HistoryDetailLoad.Content(detail())),
                referentialEqualityPolicy(),
            )
        var actions = 0
        compose.setContent { TestTheme { CanonicalSurface(publication) { actions++ } } }
        val back = compose.onNodeWithText(compose.activity.getString(R.string.history_back))
        keyboard.enter(back)
        scroll("occurrence:o0")
        val delete = compose.onNodeWithTag("sequence-history-delete-child-o0").performScrollTo()
        val staleAction = requireNotNull(delete.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val target = compose.onNodeWithTag(tag).performScrollTo()
        target.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(target)
        compose.runOnIdle {
            val canonical = (publication.value.load as HistoryDetailLoad.Content).value
            publication.value =
                publication.value.copy(
                    load =
                        HistoryDetailLoad.Content(
                            canonical.copy(
                                updatedAt = canonical.updatedAt.plusMillis(1),
                                occurrences = listOf(replace(canonical.occurrences.single())),
                            ),
                        ),
                )
        }
        target.assertDoesNotExist()
        keyboard.awaitFocus(back)
        compose.runOnIdle {
            staleAction()
            assertEquals(0, actions)
        }
        scroll("occurrence:o0")
        compose.onNodeWithTag("sequence-occurrence-summary-o0").assertExists()
        if (tag.startsWith("sequence-child")) {
            compose.onNodeWithTag("sequence-deleted-o0").performScrollTo().assertExists()
        }
    }

    @Composable
    private fun CanonicalSurface(
        publication: State<SequenceHistoryMutationState>,
        dispatch: (SequenceHistoryMutationAction) -> Unit,
    ) {
        val state = publication.value
        val canonical = (state.load as HistoryDetailLoad.Content).value
        val admission =
            rememberSequenceHistoryAdmission(
                canonical.root.executionId,
                state,
                { publication.value },
                { true },
                true,
                dispatch,
            )
        val focus = rememberHistoryFocus(true, false, false, remember(admission) { admission::isCurrent })
        CompositionLocalProvider(LocalHistoryFocus provides focus, LocalSequenceHistoryAdmission provides admission) {
            SequenceHistoryDetailSurface(state, { admission.action(it)() }, {}, state.load, {}, true, false) {}
        }
    }

    @Test
    fun nestedFocusSurvivesCanonicalReplacementAndOffscreenDisposalDoesNotRecover() {
        val canonical = mutableStateOf(detail(List(100) { occurrence(it) }), referentialEqualityPolicy())
        val focus = HistoryFocus()
        compose.setContent {
            TestTheme {
                Column(Modifier.fillMaxSize()) {
                    TextButton({}, Modifier.testTag("outside-detail-focus")) { Text("Outside detail") }
                    Box(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalHistoryFocus provides focus) {
                            SequenceDetail(canonical.value)
                        }
                    }
                }
            }
        }
        scroll("occurrence:o0")
        val child = compose.onNodeWithTag("sequence-child-child-o0").performScrollTo()
        keyboard.enter(child)
        child.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(child)
        compose.runOnIdle {
            canonical.value = canonical.value.copy(root = canonical.value.root.copy(title = "Unrelated root change"))
        }
        child.assertIsFocused()
        // Hand native focus to an attached target outside the lazy layout. History deliberately
        // retains its last semantic owner so ordinary disposal must not mark that owner removed.
        val outside = compose.onNodeWithTag("outside-detail-focus")
        outside.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(outside)
        compose.runOnIdle { assertEquals("occurrence:o0", focus.lazyOwner) }
        scroll("occurrence:o99")
        child.assertDoesNotExist()
        outside.assertIsFocused()
        compose.runOnIdle { assertEquals(null, focus.removed) }
        scroll("occurrence:o0")
        child.assertExists()
    }

    @Test
    fun queuedOrdinalJumpCannotUseReplacedCanonicalListOrRemovedFocusTarget() {
        queuedCanonicalReplacement(afterScrollFrame = true)
    }

    @Test
    fun queuedOrdinalJumpIsDiscardedBeforeScrollOnCanonicalReplacement() {
        queuedCanonicalReplacement(afterScrollFrame = false)
    }

    private fun queuedCanonicalReplacement(afterScrollFrame: Boolean) {
        val publication =
            mutableStateOf(
                SequenceHistoryMutationState(load = HistoryDetailLoad.Content(detail(List(50) { occurrence(it) }))),
                referentialEqualityPolicy(),
            )
        var actions = 0
        compose.setContent { TestTheme { CanonicalSurface(publication) { actions++ } } }
        scroll("sequence:navigation")
        compose.onNodeWithTag("sequence-history-go-position").performTextReplacement("50")
        val go = compose.onNodeWithTag("sequence-history-go")
        val click = requireNotNull(go.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val list = compose.onNodeWithTag("sequence-history-detail-list")
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { click() }
        if (afterScrollFrame) compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread {
            val canonical = (publication.value.load as HistoryDetailLoad.Content).value
            publication.value =
                publication.value.copy(
                    load =
                        HistoryDetailLoad.Content(
                            canonical.copy(occurrences = listOf(occurrence(0))),
                        ),
                )
        }
        compose.mainClock.autoAdvance = true
        selectedKey = null
        val back = text(R.string.history_back)
        keyboard.enter(back)
        back.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(back)
        compose.onNodeWithTag("sequence-occurrence-summary-o49").assertDoesNotExist()
        if (!afterScrollFrame) {
            assertEquals(before, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        }
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test
    fun ordinalNavigationUsesCanonicalOrderDespiteUnrelatedRuntimePositions() {
        val selected =
            detail(listOf(occurrence(80).copy(runtimePosition = 3), occurrence(10).copy(runtimePosition = 999)))
        compose.setContent { TestTheme { SequenceDetail(selected) } }
        scroll("sequence:navigation")
        compose.onNodeWithTag("sequence-history-go-position").performTextReplacement("2")
        val go = compose.onNodeWithTag("sequence-history-go").performScrollTo()
        keyboard.enter(go)
        go.performSemanticsAction(SemanticsActions.OnClick)
        keyboard.awaitFocus(compose.onNodeWithTag("sequence-occurrence-summary-o10"))
    }

    @Test
    fun capturedReadOnlySectionActionCannotReviveAfterHiddenRoundTrip() {
        val selected = detail(List(20) { occurrence(it) })
        val visible = mutableStateOf(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var reads = 0
        var writes = 0
        val controller =
            SequenceHistoryMutationController(
                scope,
                selected.root.executionId,
                {
                    reads++
                    selected
                },
                { _, _, _ -> writes++ },
                { _, _, _ -> writes++ },
                { _, _, _ -> writes++ },
            )
        val session = SequenceHistoryMutationRouteSession(selected.root.executionId, controller)
        try {
            compose.setContent {
                TestTheme { SequenceHistoryDetailRoute(session, {}, {}, visible.value, { visible.value }) }
            }
            compose.waitUntil { controller.state.value.load is HistoryDetailLoad.Content }
            scroll("sequence:navigation")
            selectedKey = null
            val section = text(R.string.history_intervals)
            val click = requireNotNull(section.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            compose.onNodeWithTag("sequence-history-go-interval-position").performScrollTo().performTextReplacement("5")
            val intervalJump = compose.onNodeWithTag("sequence-history-go-interval").performScrollTo()
            val jumpClick = requireNotNull(intervalJump.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            val inputFocus =
                requireNotNull(
                    compose
                        .onNodeWithTag("sequence-history-go-position")
                        .fetchSemanticsNode()
                        .config[SemanticsActions.RequestFocus]
                        .action,
                )
            val list = compose.onNodeWithTag("sequence-history-detail-list")
            val position = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            compose.runOnIdle {
                visible.value = false
                click()
                jumpClick()
                assertFalse(inputFocus())
                visible.value = true
                click()
                jumpClick()
                assertFalse(inputFocus())
            }
            compose.waitForIdle()
            assertEquals(
                position,
                list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(),
            )
            compose.runOnIdle {
                assertEquals(1, reads)
                assertEquals(0, writes)
            }
            section.performScrollTo()
            keyboard.enter(section)
            section.performClick()
            keyboard.awaitFocus(
                compose.onNode(
                    hasText(compose.activity.getString(R.string.history_intervals)) and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
                ),
            )
            scroll("sequence:navigation")
            compose.onNodeWithTag("sequence-history-go-interval-position").performTextReplacement("5")
            val freshJump = compose.onNodeWithTag("sequence-history-go-interval").performScrollTo()
            keyboard.enter(freshJump)
            freshJump.performSemanticsAction(SemanticsActions.OnClick)
            keyboard.awaitFocus(compose.onNodeWithTag("sequence-interval-summary-i4"))
            compose.runOnIdle {
                assertEquals(1, reads)
                assertEquals(0, writes)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun keyboardTraversesReadOnlyHeadingAndNativeMutationControlWithoutInvokingActions() {
        val selected = detail()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var reads = 0
        var writes = 0
        val controller =
            SequenceHistoryMutationController(
                scope,
                selected.root.executionId,
                {
                    reads++
                    selected
                },
                { _, _, _ -> writes++ },
                { _, _, _ -> writes++ },
                { _, _, _ -> writes++ },
            )
        val session = SequenceHistoryMutationRouteSession(selected.root.executionId, controller)
        try {
            compose.setContent { TestTheme { SequenceHistoryDetailRoute(session, {}, {}) } }
            compose.waitUntil { controller.state.value.load is HistoryDetailLoad.Content }
            keyboard.enter(text(R.string.history_back))
            keyboard.awaitFocus(text(R.string.history_back))
            compose.onRoot().performKeyInput { pressKey(Key.Tab) }
            keyboard.awaitFocus(compose.onNodeWithTag("sequence-history-root"))
            scroll("sequence:timing")
            val timing = compose.onNodeWithTag("sequence-history-timing")
            timing.performSemanticsAction(SemanticsActions.RequestFocus)
            keyboard.awaitFocus(timing)
            compose.onRoot().performKeyInput { pressKey(Key.Tab) }
            val heading =
                compose.onNode(
                    hasText(compose.activity.getString(R.string.history_sequence_fields)) and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
                )
            keyboard.awaitFocus(heading)
            compose.onRoot().performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                keyUp(Key.ShiftLeft)
            }
            keyboard.awaitFocus(timing)
            compose.runOnIdle {
                assertEquals(null, controller.state.value.timingDraft)
                assertEquals(1, reads)
                assertEquals(0, writes)
            }
            compose.onRoot().performKeyInput { pressKey(Key.Enter) }
            compose.waitUntil { controller.state.value.timingDraft != null }
            compose.runOnIdle { assertEquals(0, writes) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun ordinalAndSectionNavigationReachOffscreenContentWithoutCommands() {
        val detail = detail(List(10_000) { occurrence(it) })
        compose.setContent { TestTheme { SequenceDetail(detail) } }
        scroll("sequence:navigation")
        compose.onNodeWithTag("sequence-history-go-position").performTextReplacement("5001")
        val go = compose.onNodeWithTag("sequence-history-go").performScrollTo()
        keyboard.enter(go)
        go.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(go)
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        keyboard.awaitFocus(compose.onNodeWithTag("sequence-occurrence-summary-o5000"))
        scroll("sequence:navigation")
        selectedKey = null
        text(R.string.history_intervals).performClick()
        keyboard.awaitFocus(
            compose.onNode(
                hasText(compose.activity.getString(R.string.history_intervals)) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            ),
        )
        compose.onNodeWithTag("sequence-interval-summary-i0").assertExists()
    }

    @Test
    fun unavailableCanonicalReadKeepsBackAndStatusReachable() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller =
            SequenceHistoryMutationController(
                scope,
                SequenceExecutionId("root"),
                { null },
                { _, _, _ -> },
                { _, _, _ -> },
                { _, _, _ -> },
            )
        try {
            compose.setContent {
                TestTheme {
                    SequenceHistoryDetailRoute(
                        SequenceHistoryMutationRouteSession(controller.executionId, controller),
                        {},
                        {},
                    )
                }
            }
            compose.waitUntil { controller.state.value.load is HistoryDetailLoad.Unavailable }
            text(R.string.history_unavailable).assertExists()
            text(R.string.history_back).assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loadingFailureRetryAndReplacementContentReadOncePerRequestedLoad() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val controller =
            SequenceHistoryMutationController(scope, SequenceExecutionId("root"), {
                reads++
                if (reads == 1) {
                    gate.await()
                    error("read failure")
                }
                detail()
            }, { _, _, _ -> }, { _, _, _ -> }, { _, _, _ -> })
        try {
            compose.setContent {
                TestTheme {
                    SequenceHistoryDetailRoute(
                        SequenceHistoryMutationRouteSession(controller.executionId, controller),
                        {},
                        {},
                    )
                }
            }
            text(R.string.history_detail_loading).assertExists()
            compose.runOnIdle { gate.complete(Unit) }
            compose.waitUntil { controller.state.value.load is HistoryDetailLoad.Failure }
            compose.onNodeWithTag("history-retry").performClick()
            compose.waitUntil { controller.state.value.load is HistoryDetailLoad.Content }
            text(R.string.sequence_history_execution_id, "root").assertExists()
            compose.runOnIdle { assertEquals(2, reads) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun englishRussianAndScaleExtremesReachOffscreenSummariesAndNativeControls() {
        val scale = mutableStateOf(0)
        val cases =
            listOf(
                Triple(Locale.US, .9f, .9f),
                Triple(Locale.US, 1.2f, 1.3f),
                Triple(Locale.forLanguageTag("ru"), .9f, .9f),
                Triple(Locale.forLanguageTag("ru"), 1.2f, 1.3f),
            )
        compose.setContent {
            val (locale, interfaceScale, textScale) = cases[scale.value]
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            val context = LocalContext.current.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context,
                LocalDensity provides Density(density.density * interfaceScale, 1.5f * textScale / interfaceScale),
            ) {
                TestTheme { SequenceDetail(detail(), onTiming = {}) }
            }
        }
        cases.indices.forEach { index ->
            compose.runOnIdle { scale.value = index }
            scroll("sequence:navigation")
            val intervalPosition = compose.onNodeWithTag("sequence-history-go-interval-position").performScrollTo()
            intervalPosition.performTextReplacement("5")
            val goInterval = compose.onNodeWithTag("sequence-history-go-interval").performScrollTo()
            val goLabel = if (cases[index].first.language == "ru") "Перейти к интервалу" else "Go to interval"
            goInterval.assertIsDisplayed().assert(hasText(goLabel))
            for ((key, tag) in listOf(
                "sequence:root" to "sequence-history-root",
                "sequence:timing" to "sequence-history-timing",
                "occurrence:o0" to "sequence-occurrence-summary-o0",
                "interval:i4" to "sequence-interval-summary-i4",
            )) {
                scroll(key)
                val bounds =
                    compose
                        .onNodeWithTag(tag)
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInRoot
                val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= viewport.left - 1)
                assertTrue(bounds.right <= viewport.right + 1)
            }
            val interval = compose.onNodeWithTag("sequence-interval-summary-i4")
            val occurrence = compose.onNodeWithTag("sequence-occurrence-summary-o0")
            val returnLabel =
                if (cases[index].first.language == "ru") {
                    "Вернуться к навигации по последовательности"
                } else {
                    "Return to Sequence navigation"
                }
            keyboard.enter(interval)
            val intervalReturn = interval.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            assertEquals(returnLabel, intervalReturn.label)
            compose.runOnIdle { assertTrue(intervalReturn.action()) }
            keyboard.awaitFocus(intervalPosition)
            intervalPosition.assertIsDisplayed()
            scroll("occurrence:o0")
            val occurrenceReturn = occurrence.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            assertEquals(returnLabel, occurrenceReturn.label)
            compose.runOnIdle { assertTrue(occurrenceReturn.action()) }
            keyboard.awaitFocus(compose.onNodeWithTag("sequence-history-go-position"))
        }
    }

    @Test
    fun largeTimingEditorUsesBoundedNativeInputsAndReadOnlyEarlyMiddleLateNavigation() {
        val selected =
            detail(
                List(10_000) { index ->
                    occurrence(index).let { row ->
                        if (index == 5_000) {
                            row.copy(
                                sourceSequenceSnapshotNodeId = null,
                                repeatSourceSnapshotNodeId = null,
                                repeatIteration = null,
                                isRuntimeAdded = true,
                            )
                        } else {
                            row
                        }
                    }
                },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var reads = 0
        var writes = 0
        val visible = mutableStateOf(true)
        val controller =
            SequenceHistoryMutationController(
                scope,
                selected.root.executionId,
                {
                    reads++
                    selected
                },
                { _, _, _ -> writes++ },
                { _, _, _ -> writes++ },
                { _, _, _ -> writes++ },
            )
        val session = SequenceHistoryMutationRouteSession(selected.root.executionId, controller)
        try {
            compose.setContent {
                TestTheme {
                    SequenceHistoryDetailRoute(
                        session,
                        {},
                        {},
                        visible.value,
                        { visible.value },
                    )
                }
            }
            compose.waitUntil { controller.state.value.load is HistoryDetailLoad.Content }
            scroll("sequence:timing")
            compose.onNodeWithTag("sequence-history-timing").performClick()
            val targets =
                controller.state.value.timingDraft!!
                    .timestamps.keys
                    .toList()
            for (index in listOf(0, targets.size / 2, targets.lastIndex)) {
                compose.onNodeWithTag("sequence-timing-ordinal").performTextReplacement((index + 1).toString())
                compose.onNodeWithTag("sequence-timing-go").performClick()
                val field = compose.onNodeWithTag("sequence-history-timestamp-${timestampTargetKey(targets[index])}")
                field.assertIsDisplayed()
                assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size < 15)
            }
            compose.onNodeWithTag("sequence-timing-section").performSemanticsAction(SemanticsActions.OnClick)
            compose
                .onNodeWithText(
                    compose.activity.getString(R.string.sequence_timing_section_occurrences),
                ).performClick()
            compose.onNodeWithTag("sequence-timing-ordinal").performTextReplacement("1")
            compose.onNodeWithTag("sequence-timing-go").performSemanticsAction(SemanticsActions.OnClick)
            compose
                .onNodeWithTag("sequence-history-timestamp-occurrence-o0-entered")
                .assert(
                    hasContentDescription(
                        compose.activity.getString(R.string.history_repeat_iteration, 1),
                        substring = true,
                    ),
                ).assert(
                    hasContentDescription(compose.activity.getString(R.string.history_source_step), substring = true),
                )
            compose.onNodeWithTag("sequence-timing-section").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText(compose.activity.getString(R.string.sequence_timing_section_children)).performClick()
            compose.onNodeWithTag("sequence-timing-ordinal").performTextReplacement("5001")
            compose.onNodeWithTag("sequence-timing-go").performSemanticsAction(SemanticsActions.OnClick)
            compose
                .onNodeWithTag("sequence-history-timestamp-child-o5000-started")
                .assertIsDisplayed()
                .assert(hasContentDescription("Europe/Berlin", substring = true))
                .assert(hasContentDescription("Asia/Tokyo", substring = true))
                .assert(hasContentDescription("child-o5000", substring = true))
                .assert(
                    hasContentDescription(compose.activity.getString(R.string.history_runtime_added), substring = true),
                )
            val child = compose.onNodeWithTag("sequence-history-timestamp-child-o5000-started")
            keyboard.enter(child)
            child.performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onRoot().performKeyInput { pressKey(Key.F6) }
            keyboard.awaitFocus(compose.onNodeWithTag("sequence-timing-section"))
            compose.onRoot().performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.F6)
                keyUp(Key.ShiftLeft)
            }
            keyboard.awaitFocus(compose.onNodeWithTag("sequence-history-review-timing"))
            assertEquals(null, controller.state.value.timingProposal)
            val oldFocus =
                compose
                    .onNodeWithTag(
                        "sequence-history-review-timing",
                    ).fetchSemanticsNode()
                    .config[SemanticsActions.RequestFocus]
                    .action!!
            compose.onNodeWithTag("sequence-timing-section").performSemanticsAction(SemanticsActions.RequestFocus)
            keyboard.awaitFocus(compose.onNodeWithTag("sequence-timing-section"))
            compose.runOnIdle {
                visible.value = false
                assertFalse(oldFocus())
                visible.value = true
                assertFalse(oldFocus())
            }
            compose
                .onNodeWithTag(
                    "sequence-history-review-timing",
                ).performSemanticsAction(SemanticsActions.RequestFocus)
            keyboard.awaitFocus(compose.onNodeWithTag("sequence-history-review-timing"))
            compose.runOnIdle {
                assertEquals(1, reads)
                assertEquals(0, writes)
            }
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun largeTimingReviewKeepsExactNativeFactsAndActionsReadableAtEnglishRussianScaleExtremes() {
        val selected = detail(List(10_000) { occurrence(it) })
        val original = SequenceHistoryProposalBuilder.timingDraft(selected)
        val edited =
            original.copy(
                timestamps =
                    original.timestamps.mapValues { (_, value) ->
                        value.copy(
                            text =
                                java.time.LocalDateTime
                                    .parse(value.text)
                                    .plusSeconds(1)
                                    .toString(),
                        )
                    },
            )
        val proposal =
            (
                SequenceHistoryProposalBuilder.timing(
                    selected,
                    edited,
                ) as SequenceHistoryTimingBuildResult.Ready
            ).proposal
        val publication =
            SequenceHistoryMutationState(
                load = HistoryDetailLoad.Content(selected),
                timingDraft = edited,
                timingProposal = proposal,
                overlapWarning = proposal.hasIntervalOverlap,
            )
        val scale = mutableStateOf(0)
        val reviewing = mutableStateOf(false)
        val editorPublication =
            SequenceHistoryMutationState(load = HistoryDetailLoad.Content(selected), timingDraft = original)
        val targets = original.timestamps.keys.toList()
        val cases =
            listOf(
                Triple(Locale.US, .9f, .9f),
                Triple(Locale.US, 1.2f, 1.3f),
                Triple(Locale.forLanguageTag("ru"), .9f, .9f),
                Triple(Locale.forLanguageTag("ru"), 1.2f, 1.3f),
            )
        var actions = 0
        compose.setContent {
            val (locale, interfaceScale, textScale) = cases[scale.value]
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            val context = LocalContext.current.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context,
                LocalDensity provides Density(density.density * interfaceScale, 1.5f * textScale / interfaceScale),
            ) {
                TestTheme {
                    val current = if (reviewing.value) publication else editorPublication
                    val admission =
                        rememberSequenceHistoryAdmission(
                            selected.root.executionId,
                            current,
                            { current },
                            { true },
                            true,
                            { actions++ },
                        )
                    CompositionLocalProvider(LocalSequenceHistoryAdmission provides admission) {
                        Column(Modifier.fillMaxSize()) {
                            if (reviewing.value) {
                                SequenceTimingReview(selected, current) { actions++ }
                            } else {
                                SequenceTimingEditor(selected, current) { actions++ }
                            }
                        }
                    }
                }
            }
        }
        for (case in cases.indices) {
            compose.runOnIdle {
                scale.value = case
                reviewing.value = false
            }
            for (index in listOf(0, targets.size / 2, targets.lastIndex)) {
                compose.onNodeWithTag("sequence-timing-ordinal").performTextReplacement((index + 1).toString())
                compose.onNodeWithTag("sequence-timing-go").performSemanticsAction(SemanticsActions.OnClick)
                compose
                    .onNodeWithTag(
                        "sequence-history-timestamp-${timestampTargetKey(targets[index])}",
                    ).assertIsDisplayed()
                assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size < 15)
                compose.onNodeWithTag("sequence-history-review-timing").assertIsDisplayed()
                compose
                    .onNodeWithText(
                        compose.activity
                            .createConfigurationContext(
                                Configuration(compose.activity.resources.configuration).apply {
                                    setLocale(cases[case].first)
                                },
                            ).getString(R.string.manual_history_cancel),
                    ).assertIsDisplayed()
            }
            compose.runOnIdle { reviewing.value = true }
            for (index in listOf(
                0,
                proposal.changes.size / 2,
                proposal.changes.size / 2 + 2,
                proposal.changes.lastIndex,
            )) {
                compose.onNodeWithTag("sequence-timing-ordinal").performTextReplacement((index + 1).toString())
                compose.onNodeWithTag("sequence-timing-go").performClick()
                val change = proposal.changes[index] as SequenceHistoryPreviewChange.Timestamp
                val previewZone =
                    if (change.target is SequenceHistoryTimestampTarget.ChildStartedAt ||
                        change.target is SequenceHistoryTimestampTarget.ChildCompletedAt
                    ) {
                        ZoneId.of("Asia/Tokyo")
                    } else {
                        zone
                    }
                val summary = compose.onNodeWithTag("sequence-history-change-${timestampTargetKey(change.target)}")
                summary
                    .assertIsDisplayed()
                    .assert(
                        hasText(exactHistoricalTimestamp(change.before, previewZone), substring = true),
                    ).assert(hasText(exactHistoricalTimestamp(change.after, previewZone), substring = true))
                summary.performSemanticsAction(SemanticsActions.RequestFocus)
                compose.onRoot().performKeyInput { pressKey(Key.Tab) }
                assertTrue(
                    compose.onAllNodes(hasTestTag("sequence-history-timing-review-list")).fetchSemanticsNodes().size ==
                        1,
                )
                val rows =
                    compose
                        .onAllNodes(
                            SemanticsMatcher("timing review row") {
                                it.config
                                    .getOrElse(
                                        SemanticsProperties.TestTag,
                                    ) { "" }
                                    .startsWith("sequence-history-change-")
                            },
                        ).fetchSemanticsNodes()
                assertTrue(rows.size < 10)
                compose.onNodeWithTag("sequence-history-overlap-proceed").assertIsDisplayed()
                compose.onNodeWithTag("sequence-history-overlap-cancel").assertIsDisplayed()
            }
        }
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test
    fun replacementNativeAttachmentObserverReceivesCurrentStateWithoutReattachment() {
        val observer = mutableStateOf(0)
        val showing = mutableStateOf(true)
        val events = listOf(mutableListOf<Boolean>(), mutableListOf<Boolean>())
        compose.setContent {
            val current = observer.value
            if (showing.value) {
                Box(Modifier.fillMaxSize().onFocusTargetAttachmentChanged { events[current] += it })
            }
        }
        compose.runOnIdle {
            assertEquals(listOf(true), events[0])
            assertTrue(events[1].isEmpty())
            observer.value = 1
        }
        compose.runOnIdle {
            assertEquals("Updating the callback must not detach the native node", listOf(true), events[0])
            assertEquals(listOf(true), events[1])
            showing.value = false
        }
        compose.runOnIdle { assertEquals(listOf(true, false), events[1]) }
    }

    @Test
    fun intervalTimingEditorAndReviewDistinguishExactOwnersAndOwnerlessInEnglishAndRussian() {
        val performed = occurrence(0)
        val unperformed = occurrence(1, RuntimeOccurrenceStatus.SKIPPED)
        val notStarted = occurrence(2, RuntimeOccurrenceStatus.NOT_STARTED)
        val selected =
            detail(listOf(performed, unperformed, notStarted)).copy(
                intervals =
                    listOf(
                        SequenceInterval(
                            SequenceIntervalId("owned"),
                            SequenceIntervalKind.ACTIVE_STEP,
                            start,
                            end,
                            performed.occurrenceId,
                        ),
                        SequenceInterval(
                            SequenceIntervalId("countdown"),
                            SequenceIntervalKind.TRANSITION_COUNTDOWN,
                            start,
                            end,
                            unperformed.occurrenceId,
                        ),
                        SequenceInterval(
                            SequenceIntervalId("not-started-countdown"),
                            SequenceIntervalKind.TRANSITION_COUNTDOWN,
                            start,
                            end,
                            notStarted.occurrenceId,
                        ),
                        SequenceInterval(
                            SequenceIntervalId("ownerless"),
                            SequenceIntervalKind.EXPLICIT_PAUSE,
                            start,
                            end,
                            null,
                        ),
                    ),
            )
        val original = SequenceHistoryProposalBuilder.timingDraft(selected)
        val intervalTargets =
            original.timestamps.keys.filter {
                it is SequenceHistoryTimestampTarget.IntervalStartedAt ||
                    it is SequenceHistoryTimestampTarget.IntervalEndedAt
            }
        val edited =
            original.copy(
                timestamps =
                    original.timestamps.mapValues { (target, value) ->
                        if (target in intervalTargets) {
                            value.copy(
                                text =
                                    java.time.LocalDateTime
                                        .parse(value.text)
                                        .plusSeconds(1)
                                        .toString(),
                            )
                        } else {
                            value
                        }
                    },
            )
        val proposal =
            (
                SequenceHistoryProposalBuilder.timing(
                    selected,
                    edited,
                ) as SequenceHistoryTimingBuildResult.Ready
            ).proposal
        val reviewing = mutableStateOf(false)
        val russian = mutableStateOf(false)
        var actions = 0
        compose.setContent {
            val locale = if (russian.value) Locale.forLanguageTag("ru") else Locale.US
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            val context = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context) {
                TestTheme {
                    val state =
                        remember(reviewing.value) {
                            SequenceHistoryMutationState(
                                load = HistoryDetailLoad.Content(selected),
                                timingDraft = original,
                                timingProposal = proposal.takeIf { reviewing.value },
                            )
                        }
                    val admission =
                        rememberSequenceHistoryAdmission(
                            selected.root.executionId,
                            state,
                            { state },
                            { true },
                            true,
                            { actions++ },
                        )
                    CompositionLocalProvider(LocalSequenceHistoryAdmission provides admission) {
                        Column(Modifier.fillMaxSize()) {
                            if (reviewing.value) {
                                SequenceTimingReview(selected, state) { actions++ }
                            } else {
                                SequenceTimingEditor(selected, state) { actions++ }
                            }
                        }
                    }
                }
            }
        }
        keyboard.enter(compose.onNodeWithTag("sequence-timing-section"))
        for (ru in listOf(false, true)) {
            compose.runOnIdle { russian.value = ru }
            val resources =
                compose.activity.createConfigurationContext(
                    Configuration(compose.activity.resources.configuration).apply {
                        setLocale(if (ru) Locale.forLanguageTag("ru") else Locale.US)
                    },
                )
            for (review in listOf(false, true)) {
                compose.runOnIdle { reviewing.value = review }
                val targets =
                    if (review) {
                        proposal.changes.map { (it as SequenceHistoryPreviewChange.Timestamp).target }
                    } else {
                        original.timestamps.keys.toList()
                    }
                for (target in intervalTargets) {
                    val intervalId =
                        when (target) {
                            is SequenceHistoryTimestampTarget.IntervalStartedAt -> target.intervalId
                            is SequenceHistoryTimestampTarget.IntervalEndedAt -> target.intervalId
                            else -> error("Interval endpoint required")
                        }
                    val interval = selected.intervals.single { it.id == intervalId }
                    compose
                        .onNodeWithTag(
                            "sequence-timing-ordinal",
                        ).performTextReplacement((targets.indexOf(target) + 1).toString())
                    compose.onNodeWithTag("sequence-timing-go").performSemanticsAction(SemanticsActions.OnClick)
                    val node =
                        compose.onNodeWithTag(
                            "sequence-history-${if (review) "change" else "timestamp"}-${timestampTargetKey(target)}",
                        )
                    node.assertIsDisplayed()
                    keyboard.awaitFocus(node)
                    val expected =
                        mutableListOf(
                            resources.getString(
                                if (target is SequenceHistoryTimestampTarget.IntervalStartedAt) {
                                    R.string.sequence_timing_interval_started
                                } else {
                                    R.string.sequence_timing_interval_ended
                                },
                            ),
                            resources.getString(intervalKindResource(interval.kind)),
                            resources.getString(R.string.sequence_timing_interval_id, interval.id.value),
                            resources.getString(
                                R.string.sequence_history_execution_id,
                                selected.root.executionId.value,
                            ),
                        )
                    val owner = interval.occurrenceId
                    expected +=
                        if (owner == null) {
                            resources.getString(R.string.sequence_history_ownerless)
                        } else {
                            resources.getString(R.string.sequence_timing_occurrence_id, owner.value)
                        }
                    expected.forEach { text ->
                        node.assert(
                            if (review) {
                                hasText(
                                    text,
                                    substring = true,
                                )
                            } else {
                                hasContentDescription(text, substring = true)
                            },
                        )
                    }
                    if (review) {
                        val change =
                            proposal.changes.filterIsInstance<SequenceHistoryPreviewChange.Timestamp>().single {
                                it.target ==
                                    target
                            }
                        node.assert(hasText(exactHistoricalTimestamp(change.before, zone), substring = true))
                        node.assert(hasText(exactHistoricalTimestamp(change.after, zone), substring = true))
                        val comparison =
                            resources
                                .getString(
                                    R.string.sequence_timing_before_after,
                                    "",
                                    exactHistoricalTimestamp(change.before, zone),
                                    exactHistoricalTimestamp(change.after, zone),
                                ).removePrefix(". ")
                        node.assert(hasText(comparison, substring = true))
                    }
                    val rendered =
                        if (review) {
                            node.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
                        } else {
                            node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
                        }
                    if (owner == null) {
                        selected.occurrences.forEach {
                            assertFalse(
                                rendered.contains(
                                    resources.getString(R.string.sequence_timing_occurrence_id, it.occurrenceId.value),
                                ),
                            )
                        }
                    } else {
                        assertFalse(rendered.contains(resources.getString(R.string.sequence_history_ownerless)))
                    }
                }
            }
        }
        for (owner in listOf(unperformed, notStarted)) {
            assertEquals(null, owner.enteredAt)
            assertEquals(null, owner.completedAt)
            assertEquals(null, owner.child)
            assertFalse(SequenceHistoryTimestampTarget.ChildCompletedAt(owner.occurrenceId) in original.timestamps)
            assertFalse(SequenceHistoryTimestampTarget.OccurrenceEnteredAt(owner.occurrenceId) in original.timestamps)
        }
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test
    fun timingEditorQueuedJumpRejectsForegroundSessionPublicationCanonicalAndStageLoss() {
        queuedTimingJump(review = false, afterScroll = false)
    }

    @Test
    fun timingReviewQueuedJumpRejectsForegroundSessionPublicationCanonicalAndProposalLoss() {
        queuedTimingJump(review = true, afterScroll = false)
    }

    @Test
    fun timingReviewPendingFocusCannotTransferToReplacedProposalOrDismissedStage() {
        queuedTimingJump(review = true, afterScroll = true)
    }

    @Test
    fun timingEditorPendingFocusCannotSurviveAuthorityLossOrStageDismissal() {
        queuedTimingJump(review = false, afterScroll = true)
    }

    private fun queuedTimingJump(
        review: Boolean,
        afterScroll: Boolean,
    ) {
        val selected = detail(List(50) { occurrence(it) })
        val draft = SequenceHistoryProposalBuilder.timingDraft(selected)
        val edited =
            draft.copy(
                timestamps =
                    draft.timestamps.mapValues { (_, value) ->
                        value.copy(
                            text =
                                java.time.LocalDateTime
                                    .parse(value.text)
                                    .plusSeconds(1)
                                    .toString(),
                        )
                    },
            )
        val proposal =
            (
                SequenceHistoryProposalBuilder.timing(
                    selected,
                    edited,
                ) as SequenceHistoryTimingBuildResult.Ready
            ).proposal
        val initial =
            SequenceHistoryMutationState(
                load = HistoryDetailLoad.Content(selected),
                timingDraft = draft,
                timingProposal = proposal.takeIf { review },
            )
        val publication = mutableStateOf(initial, referentialEqualityPolicy())
        val visible = mutableStateOf(true)
        val session = Any()
        val activeSession = mutableStateOf(session, referentialEqualityPolicy())
        val showing = mutableStateOf(true)
        var actions = 0
        compose.setContent {
            TestTheme {
                val state = publication.value
                val canonical = (state.load as HistoryDetailLoad.Content).value
                val admission =
                    rememberSequenceHistoryAdmission(
                        canonical.root.executionId,
                        state,
                        { publication.value },
                        { visible.value && activeSession.value === session && showing.value },
                        visible.value,
                        { actions++ },
                    )
                Column(Modifier.fillMaxSize()) {
                    TextButton({}, Modifier.testTag("timing-foreign-focus")) { Text("Foreground control") }
                    CompositionLocalProvider(LocalSequenceHistoryAdmission provides admission) {
                        if (showing.value) {
                            if (review) {
                                SequenceTimingReview(canonical, state) { actions++ }
                            } else {
                                SequenceTimingEditor(canonical, state) { actions++ }
                            }
                        }
                    }
                }
            }
        }
        val targets =
            if (review) {
                proposal.changes.map { (it as SequenceHistoryPreviewChange.Timestamp).target }
            } else {
                draft.timestamps.keys.toList()
            }
        val listTag = if (review) "sequence-history-timing-review-list" else "sequence-history-timing-list"
        for (loss in listOf(
            "foreground",
            "session",
            "publication",
            "canonical",
            "proposal",
            "equal-proposal",
            "stage",
            "pause",
        )) {
            compose.runOnIdle {
                visible.value = true
                activeSession.value = session
                showing.value = true
                publication.value = initial.copy(publication = publication.value.publication + 1)
            }
            // Start every case at the root with an independent current publication.
            compose.onNodeWithTag("sequence-timing-ordinal").performTextReplacement("1")
            compose.onNodeWithTag("sequence-timing-go").performClick()
            compose.onNodeWithTag("sequence-timing-ordinal").performTextReplacement(targets.size.toString())
            val click =
                requireNotNull(
                    compose
                        .onNodeWithTag(
                            "sequence-timing-go",
                        ).fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                        .action,
                )
            val list = compose.onNodeWithTag(listTag)
            val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val foreign = compose.onNodeWithTag("timing-foreign-focus")
            keyboard.enter(foreign)
            val focusForeign = requireNotNull(foreign.fetchSemanticsNode().config[SemanticsActions.RequestFocus].action)
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { click() }
            if (afterScroll) compose.mainClock.advanceTimeByFrame()
            if (loss == "pause") {
                compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            } else {
                compose.runOnUiThread {
                    when (loss) {
                        "foreground" -> visible.value = false
                        "session" -> activeSession.value = Any()
                        "publication" ->
                            publication.value =
                                publication.value.copy(publication = publication.value.publication + 1)
                        "canonical" ->
                            publication.value =
                                publication.value.copy(load = HistoryDetailLoad.Content(selected.copy()))
                        "proposal" ->
                            publication.value =
                                if (review) {
                                    publication.value.copy(
                                        timingProposal = proposal.copy(changes = proposal.changes.reversed()),
                                    )
                                } else {
                                    publication.value.copy(timingDraft = draft.copy())
                                }
                        "equal-proposal" ->
                            publication.value =
                                if (review) {
                                    publication.value.copy(timingProposal = proposal.copy())
                                } else {
                                    publication.value.copy(timingDraft = draft.copy())
                                }
                        "stage" -> showing.value = false
                    }
                    androidx.compose.runtime.snapshots.Snapshot
                        .sendApplyNotifications()
                    if (loss == "foreground") visible.value = true // No frame between hide and reentry.
                }
            }
            // Establish competing native focus before releasing the queued scroll/focus frames.
            compose.runOnUiThread { focusForeign() }
            compose.mainClock.autoAdvance = true
            keyboard.awaitFocus(foreign)
            if (!afterScroll && loss != "stage") {
                assertEquals(
                    before,
                    list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(),
                )
            }
            if (loss == "stage") compose.onNodeWithTag(listTag).assertDoesNotExist()
            if (loss in listOf("canonical", "proposal", "equal-proposal")) {
                // The replacement itself must be usable, including a distinct but equal canonical/proposal.
                compose.onNodeWithTag("sequence-timing-go").performClick()
                val replacementTarget = if (review && loss == "proposal") targets.first() else targets.last()
                val replacement =
                    compose.onNodeWithTag(
                        "sequence-history-${if (review) "change" else "timestamp"}-${timestampTargetKey(
                            replacementTarget,
                        )}",
                    )
                replacement.assertIsDisplayed()
                keyboard.awaitFocus(replacement)
            }
            compose.runOnIdle {
                showing.value = true
                activeSession.value = session
                publication.value = initial.copy(publication = publication.value.publication + 1)
            }
            // A retained callback cannot borrow the new publication, even after foreground reentry.
            foreign.performSemanticsAction(SemanticsActions.RequestFocus)
            compose.runOnIdle { click() }
            keyboard.awaitFocus(foreign)
            compose.onNodeWithTag("sequence-timing-ordinal").performTextReplacement(targets.size.toString())
            compose.onNodeWithTag("sequence-timing-go").performClick()
            val target =
                compose.onNodeWithTag(
                    "sequence-history-${if (review) "change" else "timestamp"}-${timestampTargetKey(targets.last())}",
                )
            target.assertIsDisplayed()
            keyboard.awaitFocus(target)
            compose.runOnIdle { assertEquals(0, actions) }
        }
    }

    @Test
    fun timingSynchronousScrollDoesNotWaitForContendedScrollOrCompleteAfterRevocation() {
        class ScrollHarness(
            val list: LazyListState,
            val scope: CoroutineScope,
        ) {
            val lease = SequenceHistoryNavigation({ true })
            val navigation = TimingNavigation(lease) { 90 }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            fun contend() {
                scope.launch {
                    list.scroll(MutatePriority.PreventUserInput) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            }

            fun go() {
                navigation.go(SequenceHistoryTimestampTarget.RootEndedAt, scope) { list.requestScrollToItem(it) }
            }
        }
        val generation = mutableStateOf(0)
        var harness: ScrollHarness? = null
        compose.setContent {
            TestTheme {
                key(generation.value) {
                    val list = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    val current = remember { ScrollHarness(list, scope) }
                    androidx.compose.runtime.SideEffect { harness = current }
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        items(1_000) { Text("Row $it", Modifier.testTag("contended-row-$it")) }
                    }
                }
            }
        }
        for (revoke in listOf(true, false)) {
            compose.runOnIdle { generation.value++ }
            val current = compose.runOnIdle { requireNotNull(harness).also { it.contend() } }
            compose.waitUntil { current.entered.isCompleted }
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { current.go() }
            if (revoke) compose.runOnIdle { current.lease.revoke() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.autoAdvance = true
            if (revoke) {
                compose.runOnIdle { current.release.complete(Unit) }
                compose.onNodeWithTag("contended-row-0").assertIsDisplayed()
                compose.runOnIdle { assertEquals(null, current.navigation.pending) }
            } else {
                // The mutex holder remains gated while the synchronous request reaches the target.
                compose.onNodeWithTag("contended-row-90").assertIsDisplayed()
                compose.runOnIdle {
                    assertEquals(90, current.list.firstVisibleItemIndex)
                    assertFalse(current.release.isCompleted)
                    current.release.complete(Unit)
                }
            }
        }
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        val focus = remember { HistoryFocus() }
        CompositionLocalProvider(LocalHistoryFocus provides focus) {
            LifeTracingAccessibleTargets { LifeTracingTheme(content = content) }
        }
    }

    @Test
    fun intervalOrdinalsFocusExactCanonicalEndpointsWithBoundedComposition() {
        val owner = occurrence(0)
        val intervals =
            List(6_000) { index ->
                SequenceInterval(
                    SequenceIntervalId("jump-$index"),
                    if (index % 2 == 0) SequenceIntervalKind.ACTIVE_STEP else SequenceIntervalKind.IMPLICIT_IDLE,
                    start.plusMillis(index.toLong()),
                    end,
                    owner.occurrenceId.takeIf { index % 2 == 0 },
                )
            }
        val canonical = mutableStateOf(detail(listOf(owner)).copy(intervals = intervals), referentialEqualityPolicy())
        compose.setContent { TestTheme { SequenceDetail(canonical.value) } }

        fun jump(
            ordinal: Int,
            expected: SequenceInterval,
            useKeyboard: Boolean,
        ) {
            scroll("sequence:navigation")
            compose
                .onNodeWithTag("sequence-history-go-interval-position")
                .performScrollTo()
                .performTextReplacement(ordinal.toString())
            val go = compose.onNodeWithTag("sequence-history-go-interval").performScrollTo()
            keyboard.enter(go)
            if (useKeyboard) {
                go.performSemanticsAction(SemanticsActions.RequestFocus)
                keyboard.awaitFocus(go)
                compose.onRoot().performKeyInput { pressKey(Key.Enter) }
            } else {
                go.performSemanticsAction(SemanticsActions.OnClick)
            }
            val summary = compose.onNodeWithTag("sequence-interval-summary-${expected.id.value}")
            keyboard.awaitFocus(summary)
            val context = compose.activity
            summary.assert(hasText(context.getString(R.string.sequence_history_interval_id, expected.id.value)))
            val started = exactHistoricalTimestamp(expected.startedAt, zone)
            val ended = exactHistoricalTimestamp(requireNotNull(expected.endedAt), zone)
            summary.assert(hasText(context.getString(R.string.history_interval_started, started)))
            summary.assert(hasText(context.getString(R.string.history_interval_ended, ended)))
            val intervalSummary =
                SemanticsMatcher("interval summary") {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag].startsWith("sequence-interval-summary-")
                }
            val visibleIntervals = compose.onAllNodes(intervalSummary).fetchSemanticsNodes().size
            assertTrue("Composed $visibleIntervals of ${intervals.size} intervals", visibleIntervals in 1..40)
        }

        jump(1, intervals.first(), useKeyboard = true)
        jump(3_001, intervals[3_000], useKeyboard = false)
        jump(6_000, intervals.last(), useKeyboard = true)
        compose.runOnIdle { canonical.value = canonical.value.copy(intervals = intervals.reversed()) }
        jump(6_000, intervals.first(), useKeyboard = false)
    }

    @Test
    fun distantIntervalNavigationReturnsThroughNativeActionsAndRevokesObsoleteActions() {
        val owner = occurrence(0)
        val intervals =
            List(6_000) { index ->
                SequenceInterval(
                    SequenceIntervalId("round-trip-$index"),
                    SequenceIntervalKind.ACTIVE_STEP,
                    start.plusMillis(index.toLong()),
                    end.minusMillis(index.toLong()),
                    owner.occurrenceId,
                )
            }
        val original = detail(listOf(owner)).copy(intervals = intervals)
        val reordered = original.copy(intervals = intervals.reversed())
        var canonical = original
        var reads = 0
        var writes = 0
        var refreshes = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun newSession(): SequenceHistoryMutationRouteSession {
            val id = original.root.executionId
            return SequenceHistoryMutationRouteSession(
                id,
                SequenceHistoryMutationController(
                    scope,
                    id,
                    {
                        assertEquals(id, it)
                        reads++
                        canonical
                    },
                    { _, _, _ -> writes++ },
                    { _, _, _ -> writes++ },
                    { _, _, _ -> writes++ },
                ),
            )
        }

        val initial = newSession()
        val mounted = mutableStateOf(true)
        val visible = mutableStateOf(true)
        val presented = mutableStateOf(initial)
        val active = mutableStateOf(initial)
        val ordinal = compose.onNodeWithTag("sequence-history-go-interval-position")
        val occurrenceOrdinal = compose.onNodeWithTag("sequence-history-go-position")
        val go = compose.onNodeWithTag("sequence-history-go-interval")
        val intervalSummary =
            SemanticsMatcher("interval summary") {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag].startsWith("sequence-interval-summary-")
            }

        fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

        fun openOrdinalsFromRoot() {
            val root = compose.onNodeWithTag("sequence-history-root")
            keyboard.enter(root)
            root.performSemanticsAction(SemanticsActions.RequestFocus)
            keyboard.awaitFocus(root)
            press(Key.F6)
            keyboard.awaitFocus(occurrenceOrdinal)
            press(Key.Tab)
            keyboard.awaitFocus(ordinal)
        }

        fun jump(
            position: Int,
            expected: SequenceInterval,
            useKeyboard: Boolean = true,
        ) = compose.onNodeWithTag("sequence-interval-summary-${expected.id.value}").also { summary ->
            ordinal.performTextReplacement(position.toString())
            press(Key.Tab)
            keyboard.awaitFocus(go)
            if (useKeyboard) press(Key.Enter) else go.performSemanticsAction(SemanticsActions.OnClick)
            keyboard.awaitFocus(summary)
            summary.assertIsDisplayed()
            val context = compose.activity
            summary.assert(hasText(context.getString(R.string.sequence_history_interval_id, expected.id.value)))
            val started = exactHistoricalTimestamp(expected.startedAt, zone)
            val ended = exactHistoricalTimestamp(requireNotNull(expected.endedAt), zone)
            summary.assert(hasText(context.getString(R.string.history_interval_started, started)))
            summary.assert(hasText(context.getString(R.string.history_interval_ended, ended)))
            val composed = compose.onAllNodes(intervalSummary).fetchSemanticsNodes().size
            assertTrue("Composed $composed of ${intervals.size} intervals", composed in 1..40)
        }

        fun returnAction(summary: SemanticsNodeInteraction): () -> Boolean {
            val label = compose.activity.getString(R.string.sequence_history_return_to_ordinals)
            val actions = summary.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            return actions.single { it.label == label }.action
        }

        fun returnByAccessibility(summary: SemanticsNodeInteraction) {
            val action = returnAction(summary)
            compose.runOnIdle { assertTrue(action()) }
            keyboard.awaitFocus(ordinal)
            ordinal.assertIsDisplayed()
        }

        try {
            compose.setContent {
                TestTheme {
                    if (mounted.value) {
                        val session = presented.value
                        SequenceHistoryDetailRoute(
                            session,
                            onBack = {},
                            onRefresh = { refreshes++ },
                            isRouteVisible = visible.value,
                            ownsRoute = { visible.value && active.value === session },
                        )
                    }
                }
            }
            compose.waitUntil { initial.controller.state.value.load is HistoryDetailLoad.Content }
            val publication = initial.controller.state.value
            openOrdinalsFromRoot()
            val middle = jump(3_001, intervals[3_000])
            returnByAccessibility(middle)
            jump(19, intervals[18], useKeyboard = false)
            press(Key.F6)
            keyboard.awaitFocus(ordinal)
            val last = jump(6_000, intervals.last())
            val obsolete = returnAction(last)
            compose.runOnIdle {
                assertSame(publication, initial.controller.state.value)
                assertSame(original, canonical)
                assertEquals(1, reads)
                assertEquals(0, writes)
                assertEquals(0, refreshes)
                visible.value = false
                assertFalse(obsolete())
            }
            compose.runOnIdle {
                visible.value = true
                assertFalse(obsolete())
            }
            val beforePause = returnAction(last)
            val list = compose.onNodeWithTag("sequence-history-detail-list")
            val scrollRange = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val positionBeforePause = scrollRange.value()
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { assertTrue(beforePause()) }
                compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
                compose.runOnUiThread { assertFalse(beforePause()) }
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.runOnIdle { assertFalse(beforePause()) }
            assertEquals(
                positionBeforePause,
                list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(),
            )
            returnByAccessibility(last)
            val latest = jump(6_000, intervals.last())
            val beforeReplacement = returnAction(latest)
            compose.runOnIdle {
                canonical = reordered
                initial.controller.dispatch(SequenceHistoryMutationAction.Retry)
                assertFalse(beforeReplacement())
            }
            compose.waitUntil {
                (initial.controller.state.value.load as? HistoryDetailLoad.Content)?.value === reordered
            }
            // The visible interval exposes a fresh action after the reorder.
            returnByAccessibility(compose.onAllNodes(intervalSummary)[0])
            val reorderedPublication = initial.controller.state.value
            val reorderedLast = jump(6_000, intervals.first())
            val beforeDisposal = returnAction(reorderedLast)
            compose.runOnIdle {
                assertSame(reorderedPublication, initial.controller.state.value)
                assertEquals(2, reads)
                mounted.value = false
            }
            compose.onNodeWithTag("sequence-history-detail-list").assertDoesNotExist()
            compose.runOnIdle {
                assertFalse(beforeDisposal())
                mounted.value = true
            }
            openOrdinalsFromRoot()
            val remounted = jump(6_000, intervals.first())
            val beforeSessionReplacement = returnAction(remounted)
            val next = compose.runOnIdle { newSession().also { active.value = it } }
            compose.runOnIdle {
                assertFalse(beforeSessionReplacement())
                presented.value = next
            }
            compose.waitUntil { next.controller.state.value.load is HistoryDetailLoad.Content }
            val nextPublication = next.controller.state.value
            openOrdinalsFromRoot()
            returnByAccessibility(jump(6_000, intervals.first()))
            compose.runOnIdle {
                assertFalse(beforeSessionReplacement())
                assertFalse(beforeDisposal())
                assertSame(nextPublication, next.controller.state.value)
                assertSame(reordered, (next.controller.state.value.load as HistoryDetailLoad.Content).value)
                assertEquals(3, reads)
                assertEquals(0, writes)
                assertEquals(0, refreshes)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun closeGapReviewExposesMovedIntervalOwnersAndExactEndpointsInBothLanguages() {
        val selected = closeGapIntervalDetail()
        val target = selected.occurrences.first()
        val unperformed = selected.occurrences.drop(2)
        val locale = mutableStateOf(Locale.US)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var writes = 0
        val controller =
            SequenceHistoryMutationController(
                scope,
                selected.root.executionId,
                { selected },
                { _, _, _ -> writes++ },
                { _, _, _ -> writes++ },
                { _, _, _ -> writes++ },
            )
        val session = SequenceHistoryMutationRouteSession(selected.root.executionId, controller)
        try {
            compose.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale.value) }
                val context = LocalContext.current.createConfigurationContext(configuration)
                CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context) {
                    TestTheme { SequenceHistoryDetailRoute(session, {}, {}) }
                }
            }
            compose.waitUntil { controller.state.value.load is HistoryDetailLoad.Content }
            scroll("occurrence:${target.occurrenceId.value}")
            compose
                .onNodeWithTag("sequence-history-remove-occurrence-${target.occurrenceId.value}")
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("sequence-history-close-gap").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("sequence-structural-placements-list").performScrollToKey("choice:ownerless")
            compose
                .onNodeWithTag("sequence-history-ownerless-ownerless-translated")
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)

            val proposal = requireNotNull(controller.state.value.structuralProposal)
            val command = requireNotNull(proposal.command)
            val performed = selected.occurrences[1]
            assertEquals(SequenceHistoryStructuralRemovalMode.CLOSE_GAP, command.mode)
            assertEquals(performed.occurrenceId, command.occurrenceTimings.single().occurrenceId)
            assertEquals(performed.child?.executionId, command.childTimings.single().executionId)
            assertTrue(
                proposal.changes.none { change ->
                    change is SequenceHistoryPreviewChange.Timestamp &&
                        unperformed.any { owner ->
                            val key = timestampTargetKey(change.target)
                            key.startsWith("occurrence-${owner.occurrenceId.value}-") ||
                                key.startsWith("child-${owner.occurrenceId.value}-")
                        }
                },
            )
            val list = compose.onNodeWithTag("sequence-structural-review-list")
            val confirm = compose.onNodeWithTag("sequence-history-confirm-structural")
            val retainedConfirm = requireNotNull(confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action)

            val russian = Locale.forLanguageTag("ru")
            for (language in listOf(Locale.US, russian)) {
                compose.runOnIdle { locale.value = language }
                val configuration = Configuration(compose.activity.resources.configuration)
                configuration.setLocale(language)
                val context = compose.activity.createConfigurationContext(configuration)
                for (interval in selected.intervals.drop(1)) {
                    val owner = selected.occurrences.firstOrNull { it.occurrenceId == interval.occurrenceId }
                    val ownerText =
                        if (owner == null) {
                            context.getString(R.string.sequence_history_ownerless)
                        } else {
                            val ownerId =
                                context.getString(R.string.sequence_history_owner_id, owner.occurrenceId.value)
                            val status = context.getString(occurrenceStatusResource(owner.status))
                            val statusLabel = if (language == russian) "Статус владельца" else "Owner status"
                            "$ownerId. $statusLabel: $status"
                        }
                    val kind = context.getString(intervalKindResource(interval.kind))
                    val endpoints = listOf("started" to interval.startedAt, "ended" to requireNotNull(interval.endedAt))
                    for ((endpoint, before) in endpoints) {
                        val key = "timestamp:interval-${interval.id.value}-$endpoint"
                        list.performScrollToKey(key)
                        val row = compose.onNodeWithTag("sequence-structural-change-$key")
                        val label =
                            context.getString(
                                if (endpoint == "started") {
                                    R.string.sequence_history_interval_started
                                } else {
                                    R.string.sequence_history_interval_ended
                                },
                                interval.id.value,
                            )
                        val expected =
                            context.getString(
                                R.string.sequence_timing_before_after,
                                "$label. $kind. $ownerText",
                                exactHistoricalTimestamp(before, zone),
                                exactHistoricalTimestamp(before.minusSeconds(60), zone),
                            )
                        row.assertIsDisplayed().assert(hasText(expected))
                    }
                }
            }
            compose.onNodeWithTag("sequence-structural-cancel").performSemanticsAction(SemanticsActions.OnClick)
            compose.runOnIdle {
                retainedConfirm()
                assertEquals(null, controller.state.value.structuralProposal)
                assertEquals(0, writes)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun closeGapIntervalDetail(): SequenceHistoryDetail {
        fun timed(
            index: Int,
            startSeconds: Long,
            endSeconds: Long,
        ): SequenceHistoryOccurrence {
            val base = occurrence(index)
            val entered = start.plusSeconds(startSeconds)
            val completed = start.plusSeconds(endSeconds)
            return base.copy(
                enteredAt = entered,
                completedAt = completed,
                child =
                    requireNotNull(base.child).copy(
                        startedAt = entered,
                        completedAt = completed,
                        activeDuration = Duration.between(entered, completed),
                    ),
                childMutationFacts =
                    requireNotNull(base.childMutationFacts).copy(startedAt = entered, completedAt = completed),
            )
        }
        val target = timed(0, 10, 70)
        val performed = timed(1, 1_600, 1_660)
        val notStarted = occurrence(2, RuntimeOccurrenceStatus.NOT_STARTED)
        val skipped = occurrence(3, RuntimeOccurrenceStatus.SKIPPED)
        return detail(listOf(target, performed, notStarted, skipped)).copy(
            intervals =
                listOf(
                    SequenceInterval(
                        SequenceIntervalId("target"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        requireNotNull(target.enteredAt),
                        target.completedAt,
                        target.occurrenceId,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("active"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        requireNotNull(performed.enteredAt),
                        performed.completedAt,
                        performed.occurrenceId,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("not-started-countdown"),
                        SequenceIntervalKind.TRANSITION_COUNTDOWN,
                        start.plusSeconds(1_800),
                        start.plusSeconds(1_810),
                        notStarted.occurrenceId,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("skipped-countdown"),
                        SequenceIntervalKind.TRANSITION_COUNTDOWN,
                        start.plusSeconds(1_820),
                        start.plusSeconds(1_830),
                        skipped.occurrenceId,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("ownerless"),
                        SequenceIntervalKind.EXPLICIT_PAUSE,
                        start.plusSeconds(1_900),
                        start.plusSeconds(1_910),
                        null,
                    ),
                ),
        )
    }

    private var selectedKey: String? = null

    private fun scroll(key: String) {
        selectedKey = key
        compose.onNodeWithTag("sequence-history-detail-list").performScrollToKey(key)
    }

    private fun scope(): SemanticsMatcher {
        val tag =
            when {
                selectedKey?.startsWith(
                    "occurrence:",
                ) == true -> "history-occurrence-${selectedKey!!.substringAfter(':')}"
                selectedKey?.startsWith("interval:") == true -> "history-interval-${selectedKey!!.substringAfter(':')}"
                selectedKey?.startsWith(
                    "sequence-field:",
                ) == true -> "sequence-history-field-${selectedKey!!.substringAfter(':')}"
                else -> null
            }
        return if (tag == null) {
            androidx.compose.ui.test
                .SemanticsMatcher("any") { true }
        } else {
            hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))
        }
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ) = compose.onNode(
        hasText(compose.activity.getString(id, *args)) and scope(),
    )

    private fun description(
        id: Int,
        vararg args: Any,
    ) = compose.onNode(
        hasContentDescription(compose.activity.getString(id, *args), substring = true) and scope(),
    )

    private fun field(
        id: String,
        type: CustomFieldType,
        configured: SequenceHistoryConfiguredValue,
        actual: SequenceHistoryActualValue,
    ) = SequenceHistoryField(
        SequenceSnapshotFieldId(id),
        "Same field",
        type,
        null,
        if (type ==
            CustomFieldType.NUMBER
        ) {
            0
        } else {
            null
        },
        false,
        configured,
        actual,
        emptyList(),
    )

    private fun activityField() =
        ActivityHistoryField(
            ActivitySnapshotFieldId("frozen-child-field"),
            "Same field",
            CustomFieldType.NUMBER,
            null,
            0,
            true,
            ActivityHistoryConfiguredValue.Number(2_000),
            ActivityHistoryActualValue.Number(7_000),
            emptyList(),
        )

    private fun occurrence(
        index: Int,
        status: RuntimeOccurrenceStatus = RuntimeOccurrenceStatus.COMPLETED,
    ): SequenceHistoryOccurrence {
        val id = SequenceOccurrenceId("o$index")
        val performed = status == RuntimeOccurrenceStatus.COMPLETED
        val childId = ActivityExecutionId("child-o$index")
        return SequenceHistoryOccurrence(
            id,
            index,
            ActivitySnapshotId("same-snapshot"),
            SequenceSnapshotNodeId("frozen-step"),
            SequenceSnapshotNodeId("frozen-repeat"),
            1,
            false,
            false,
            status,
            if (performed) start else null,
            if (performed) end else null,
            null,
            SequenceHistoryOccurrenceActivity(
                ActivitySnapshotId("same-snapshot"),
                "Same activity",
                "Frozen child",
                TimeTrackingMode.STOPWATCH,
                null,
                ActivityTemplateSettings(),
                null,
            ),
            if (performed) {
                SequenceHistoryChildActivity(
                    childId,
                    ActivityExecutionStatus.COMPLETED,
                    start,
                    end,
                    Duration.ofSeconds(30),
                    listOf(activityField()),
                    ZoneId.of("Asia/Tokyo"),
                    540,
                )
            } else {
                null
            },
            if (performed) SequenceHistoryChildMutationFacts(childId, start, end, emptyList()) else null,
        )
    }

    private fun detail(occurrences: List<SequenceHistoryOccurrence> = listOf(occurrence(0))) =
        SequenceHistoryDetail(
            CompletedSequenceHistoryRoot(
                SequenceExecutionId("root"),
                SequenceSnapshotId("frozen-root"),
                LocalDate.parse("2026-10-25"),
                end,
                start,
                SequenceExecutionStatus.COMPLETED,
                Duration.ofSeconds(30),
                Duration.ofMinutes(59).plusSeconds(30),
                Duration.ofHours(1),
                PlanEntryId("plan"),
                "Frozen root",
                "Frozen comment",
                zone,
                120,
            ),
            end,
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
            SequenceIntervalKind.entries.mapIndexed {
                index,
                kind,
                ->
                SequenceInterval(SequenceIntervalId("i$index"), kind, start, end, null)
            },
        )

    private companion object {
        val zone: ZoneId = ZoneId.of("Europe/Berlin")
        val start: Instant = Instant.parse("2026-10-25T00:30:12.123Z")
        val end: Instant = Instant.parse("2026-10-25T01:30:12.124Z")
    }
}
