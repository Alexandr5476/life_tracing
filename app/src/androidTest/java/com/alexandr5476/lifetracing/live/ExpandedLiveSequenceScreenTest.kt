@file:Suppress("LongMethod", "MagicNumber")

package com.alexandr5476.lifetracing.live

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceState
import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionTransitions
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.EffectiveSequenceStepSettings
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequence
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequenceOccurrence
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequenceRead
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.RuntimeOccurrence
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceExecution
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ExpandedLiveSequenceScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun completeRuntimeOrderAndProvenanceUseFrozenActivityPresentation() {
        val expanded = expanded(5)
        composeRule.setContent {
            LifeTracingTheme {
                ExpandedLiveSequenceScreen(
                    ExpandedLiveSequenceState(sequence = expanded, loading = false),
                    controller(expanded),
                    {},
                    0,
                )
            }
        }

        composeRule.onNodeWithText("Activity 0").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag("expanded-sequence-value-occurrence-0-value", useUnmergedTree = true)
            .performScrollTo()
            .assertTextEquals(string(R.string.expanded_sequence_field_value, "Value", "0"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Activity 1").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.expanded_sequence_repeat_iteration, 2))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Activity 2").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.expanded_sequence_runtime_added))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("expanded-sequence-value-occurrence-2-value", useUnmergedTree = true)
            .performScrollTo()
            .assertTextEquals(string(R.string.expanded_sequence_field_value, "Value", "5"))
            .assertIsDisplayed()
        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("expanded-sequence-occurrence-occurrence-3"))
        composeRule.onNodeWithText("Activity 3").assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.expanded_sequence_skipped))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("expanded-sequence-occurrence-occurrence-4"))
        composeRule.onNodeWithText("Activity 4").assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.expanded_sequence_deleted))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun tenThousandOccurrenceGraphOnlyComposesVisibleLazyRows() {
        val expanded = expanded(10_000)
        composeRule.setContent {
            LifeTracingTheme {
                ExpandedLiveSequenceScreen(
                    ExpandedLiveSequenceState(sequence = expanded, loading = false),
                    controller(expanded),
                    {},
                    0,
                )
            }
        }

        composeRule.onNodeWithText("Activity 0").assertIsDisplayed()
        composeRule.onNodeWithText("Activity 9999").assertDoesNotExist()
    }

    @Test
    fun globalSecondaryOverflowIsStateAwareAndNeverPermanentlyRendersItsActions() {
        val expanded = expanded(1)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        val uiState = mutableStateOf(ExpandedLiveSequenceState(sequence = expanded, loading = false))
        composeRule.setContent {
            LifeTracingTheme {
                ExpandedLiveSequenceScreen(uiState.value, controller, {}, 0)
            }
        }
        val cases =
            listOf(
                ActiveSequenceState.RUNNING_CURRENT to true,
                ActiveSequenceState.PAUSED_CURRENT to false,
                ActiveSequenceState.WAITING_NEXT to true,
                ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN to true,
                ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN to false,
            )

        cases.forEach { (sequenceState, runtimeAdd) ->
            composeRule.runOnIdle {
                uiState.value =
                    ExpandedLiveSequenceState(sequence = expanded.copy(state = sequenceState), loading = false)
            }
            composeRule.onNodeWithText(string(R.string.expanded_sequence_runtime_add)).assertDoesNotExist()
            composeRule.onNodeWithText(string(R.string.expanded_sequence_end_early)).assertDoesNotExist()
            composeRule.onNodeWithTag("expanded-sequence-global-actions").performClick()
            composeRule.onNodeWithText(string(R.string.expanded_sequence_end_early)).assertIsDisplayed()
            if (runtimeAdd) {
                composeRule.onNodeWithText(string(R.string.expanded_sequence_runtime_add)).assertIsDisplayed()
            } else {
                composeRule.onNodeWithText(string(R.string.expanded_sequence_runtime_add)).assertDoesNotExist()
            }
        }
        assertTrue(commands.isEmpty())
    }

    @Test
    fun globalOverflowPreservesEarlyEndConfirmationAndRuntimeAddDialog() {
        val expanded = expanded(1)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        composeRule.setContent {
            LifeTracingTheme {
                ExpandedLiveSequenceRoute(controller, {}, {})
            }
        }

        composeRule.onNodeWithTag("expanded-sequence-global-actions").performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_end_early)).performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_confirm_end_title)).assertIsDisplayed()
        assertTrue(commands.isEmpty())
        composeRule.onNodeWithText(string(R.string.expanded_sequence_cancel)).performClick()

        composeRule.onNodeWithTag("expanded-sequence-global-actions").performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_runtime_add)).performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_reusable_activity)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_one_off)).assertIsDisplayed()
        assertTrue(commands.isEmpty())
    }

    @Test
    fun currentValueControlsAreDisabledWhileACommandIsInFlight() {
        val fields =
            listOf(
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("number"),
                    null,
                    0,
                    "Number",
                    type = com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                    displayPrecision = 0,
                ),
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("text"),
                    null,
                    1,
                    "Text",
                    type = com.alexandr5476.lifetracing.domain.CustomFieldType.TEXT,
                ),
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("category"),
                    null,
                    2,
                    "Category",
                    type = com.alexandr5476.lifetracing.domain.CustomFieldType.CATEGORY,
                    categoryOptions = emptyList(),
                ),
            )
        val expanded = expanded(2, fields)
        val current = expanded.occurrences.single { it.occurrence.status == RuntimeOccurrenceStatus.CURRENT }
        val draft = CurrentValueDraft(current.occurrence.id, emptyMap(), emptyMap())
        composeRule.setContent {
            LifeTracingTheme {
                ExpandedLiveSequenceScreen(
                    ExpandedLiveSequenceState(
                        sequence = expanded,
                        loading = false,
                        commandInFlight = true,
                        currentValueDraft = draft,
                    ),
                    controller(expanded),
                    {},
                    0,
                )
            }
        }

        fields.forEach { field ->
            composeRule.onNodeWithTag("expanded-sequence-current-value-${field.id.value}").assertIsNotEnabled()
            composeRule.onNodeWithTag("expanded-sequence-current-missing-${field.id.value}").assertIsNotEnabled()
        }
    }

    private fun expanded(
        count: Int,
        fields: List<ActivitySnapshotField>? = null,
    ): ExpandedLiveSequence {
        val valueField =
            ActivitySnapshotField(
                ActivitySnapshotFieldId("value"),
                null,
                0,
                "Value",
                type = com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                displayPrecision = 0,
                defaultNumberScaled = 5,
            )
        val activities =
            (0 until count).associate { index ->
                val activity =
                    ActivityConfigSnapshot(
                        ActivitySnapshotId("activity-$index"),
                        "Activity $index",
                        if (index == 0) "Frozen note" else null,
                        TimeTrackingMode.STOPWATCH,
                        null,
                        null,
                        null,
                        null,
                        false,
                        Instant.EPOCH,
                        fields =
                            fields
                                ?: listOf(
                                    valueField.copy(
                                        defaultNumberScaled = if (index == 2) 5_000 else 6_000,
                                    ),
                                ),
                    )
                activity.id to activity
            }
        val occurrences =
            activities.values.mapIndexed { index, activity ->
                RuntimeOccurrence(
                    SequenceOccurrenceId("occurrence-$index"),
                    SequenceSnapshotNodeId("step-$index"),
                    activity.id,
                    index,
                    SequenceSnapshotNodeId("repeat").takeIf { index == 1 },
                    2.takeIf { index == 1 },
                    when (index) {
                        0 -> RuntimeOccurrenceStatus.COMPLETED
                        1 -> RuntimeOccurrenceStatus.CURRENT
                        2 -> RuntimeOccurrenceStatus.NOT_STARTED
                        3 -> RuntimeOccurrenceStatus.SKIPPED
                        4 -> RuntimeOccurrenceStatus.DELETED_EXECUTION
                        else -> RuntimeOccurrenceStatus.NOT_STARTED
                    },
                    Instant.EPOCH.takeIf { index <= 1 || index == 4 },
                    Instant.EPOCH.plusSeconds(1).takeIf { index == 0 || index == 4 },
                    null,
                    isRuntimeAdded = index == 2,
                    isDeletedFromHistory = index == 4,
                )
            }
        val firstActivity = activities.values.first()
        val firstStarted =
            ActivityExecutionFactory { ActivityExecutionId("child") }
                .startSequenceChildTimed(
                    firstActivity,
                    SequenceExecutionId("sequence"),
                    occurrences.first().id,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    ZoneOffset.UTC,
                ).copy(values = listOf(NumberExecutionValue(valueField.id, 0)))
        val completedChild = ActivityExecutionTransitions.complete(firstStarted, Instant.EPOCH.plusSeconds(1))
        val execution =
            SequenceExecution(
                SequenceExecutionId("sequence"),
                SequenceSnapshotId("snapshot"),
                null,
                SequenceExecutionStatus.RUNNING,
                Instant.EPOCH,
                null,
                null,
                null,
                null,
                ZoneOffset.UTC,
                0,
                LocalDate.ofEpochDay(0),
                occurrences.getOrNull(1)?.id,
                Instant.EPOCH,
                Instant.EPOCH,
                occurrences,
                listOf(
                    SequenceInterval(
                        SequenceIntervalId("interval"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        Instant.EPOCH,
                        null,
                        occurrences.getOrNull(1)?.id,
                    ),
                ),
            )
        val snapshot =
            SequenceConfigSnapshot(
                SequenceSnapshotId("snapshot"),
                "Expanded sequence",
                null,
                null,
                null,
                null,
                Instant.EPOCH,
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
            )
        val runtime =
            ActiveSequenceRuntime(
                ActiveSession(
                    ActiveSessionKind.SEQUENCE,
                    ActiveSessionState.RUNNING,
                    null,
                    execution.id,
                    Instant.EPOCH,
                ),
                execution,
                snapshot,
                activities,
                null,
                null,
            )
        val settings = EffectiveSequenceStepSettings(Duration.ZERO, TimerZeroBehavior.FINISH, true, true, false)
        return ExpandedLiveSequence(
            runtime,
            ActiveSequenceState.RUNNING_CURRENT,
            occurrences.map { occurrence ->
                ExpandedLiveSequenceOccurrence(
                    occurrence,
                    activities.getValue(occurrence.activitySnapshotId),
                    completedChild.takeIf { occurrence.runtimePosition == 0 },
                    settings,
                )
            },
        )
    }

    private fun controller(
        expanded: ExpandedLiveSequence,
        commands: MutableList<ExpandedSequenceCommand> = mutableListOf(),
    ) = ExpandedLiveSequenceController(
        CoroutineScope(Dispatchers.Unconfined),
        expanded.runtime.execution.id,
        { ExpandedLiveSequenceRead.Active(expanded) },
        { commands += it },
        {},
        MutableStateFlow(0L),
        { null },
        { emptyList() },
        { Instant.EPOCH },
    )

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
}
