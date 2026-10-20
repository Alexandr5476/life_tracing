@file:Suppress("LongMethod", "MagicNumber")

package com.alexandr5476.lifetracing.live

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
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
import com.alexandr5476.lifetracing.ui.onLazyNode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
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

    @get:Rule
    val keyboardInput =
        com.alexandr5476.lifetracing.ui
            .KeyboardInputFixture(composeRule)

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

        composeRule.onLazyNode(hasText("Activity 0")).assertIsDisplayed()
        composeRule
            .onNodeWithTag("expanded-sequence-value-occurrence-0-value", useUnmergedTree = true)
            .performScrollTo()
            .assertTextEquals(string(R.string.expanded_sequence_field_value, "Value", "0"))
            .assertIsDisplayed()
        composeRule.onLazyNode(hasText("Activity 1")).assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.expanded_sequence_repeat_iteration, 2))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onLazyNode(hasText("Activity 2")).assertIsDisplayed()
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
            composeRule
                .onLazyNode(hasTestTag("expanded-sequence-current-value-${field.id.value}"))
                .assertIsNotEnabled()
            composeRule
                .onLazyNode(hasTestTag("expanded-sequence-current-missing-${field.id.value}"))
                .assertIsNotEnabled()
        }
    }

    @Test
    fun staleProjectionHasNoExecutableSequenceControls() {
        val expanded = expanded(5)
        composeRule.setContent {
            LifeTracingTheme {
                ExpandedLiveSequenceScreen(
                    ExpandedLiveSequenceState(sequence = expanded, stale = true, loading = false),
                    controller(expanded),
                    {},
                    0,
                )
            }
        }
        composeRule.onNodeWithTag("expanded-sequence-global-actions").assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_complete)).assertDoesNotExist()
    }

    @Test
    fun runtimeAddClosesWhenPlacementsBecomeEmpty() {
        val expanded = expanded(5)
        val state = mutableStateOf(ExpandedLiveSequenceState(sequence = expanded, loading = false))
        val controller = controller(expanded)
        composeRule.setContent {
            LifeTracingTheme { ExpandedLiveSequenceScreen(state.value, controller, {}, 0) }
        }
        composeRule.onNodeWithTag("expanded-sequence-global-actions").performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_runtime_add)).performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_one_off)).assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = state.value.copy(sequence = expanded.copy(state = ActiveSequenceState.PAUSED_CURRENT))
        }
        composeRule.onNodeWithText(string(R.string.expanded_sequence_add)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_one_off)).assertDoesNotExist()
    }

    @Test
    fun alreadyOpenGlobalMenuCannotExecuteDuringCommand() {
        val expanded = expanded(5)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val state = mutableStateOf(ExpandedLiveSequenceState(sequence = expanded, loading = false))
        val controller = controller(expanded, commands)
        composeRule.setContent {
            LifeTracingTheme { ExpandedLiveSequenceScreen(state.value, controller, {}, 0) }
        }
        composeRule.onNodeWithTag("expanded-sequence-global-actions").performClick()
        composeRule.runOnIdle { state.value = state.value.copy(commandInFlight = true) }
        composeRule.onNodeWithText(string(R.string.expanded_sequence_end_early)).assertDoesNotExist()
        composeRule.onNodeWithTag("expanded-sequence-global-actions").assertIsNotEnabled()
        assertTrue(commands.isEmpty())
    }

    @Test
    fun runningCurrentHasExplicitStateAndCompleteAndPause() =
        assertState(
            ActiveSequenceState.RUNNING_CURRENT,
            R.string.expanded_sequence_running_current,
            R.string.expanded_sequence_complete,
            R.string.expanded_sequence_pause,
        )

    @Test
    fun pausedCurrentHasExplicitStateAndOnlyResume() =
        assertState(
            ActiveSequenceState.PAUSED_CURRENT,
            R.string.expanded_sequence_paused_current,
            R.string.expanded_sequence_resume,
        )

    @Test
    fun waitingNextHasExplicitNoCurrentStateAndStartNext() =
        assertState(
            ActiveSequenceState.WAITING_NEXT,
            R.string.expanded_sequence_waiting_next,
            R.string.expanded_sequence_start_next,
        )

    @Test
    fun runningCountdownHasExplicitNoCurrentStateAndPause() =
        assertState(
            ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN,
            R.string.expanded_sequence_running_countdown,
            R.string.expanded_sequence_pause,
        )

    @Test
    fun pausedCountdownHasExplicitNoCurrentStateAndResume() =
        assertState(
            ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
            R.string.expanded_sequence_paused_countdown,
            R.string.expanded_sequence_resume,
        )

    private fun assertState(
        runtimeState: ActiveSequenceState,
        label: Int,
        vararg controls: Int,
    ) {
        val expanded = expanded(5).copy(state = runtimeState)
        val state = mutableStateOf(ExpandedLiveSequenceState(sequence = expanded, loading = false))
        val controller = controller(expanded)
        composeRule.setContent {
            LifeTracingTheme { ExpandedLiveSequenceScreen(state.value, controller, {}, 0) }
        }
        composeRule.onNodeWithTag("expanded-sequence-state").assertTextEquals(string(label))
        val primary =
            listOf(
                R.string.expanded_sequence_complete,
                R.string.expanded_sequence_pause,
                R.string.expanded_sequence_resume,
                R.string.expanded_sequence_start_next,
            )
        primary.forEach {
            if (it in controls) {
                composeRule.onNodeWithText(string(it)).assertIsEnabled()
            } else {
                composeRule.onNodeWithText(string(it)).assertDoesNotExist()
            }
        }
        composeRule.runOnIdle { state.value = state.value.copy(commandInFlight = true) }
        controls.forEach { composeRule.onNodeWithText(string(it)).assertIsNotEnabled() }
    }

    @Test
    fun missingCurrentCannotExposeCompleteAndMissingBaselineIsExplicit() {
        val expanded = expanded(1)
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
        composeRule.onNodeWithText(string(R.string.expanded_sequence_complete)).assertDoesNotExist()
        composeRule
            .onNodeWithText(
                string(R.string.expanded_sequence_active_time, string(R.string.expanded_sequence_timing_unavailable)),
            ).assertIsDisplayed()
    }

    @Test
    fun occurrenceMenusDistinguishRepeatedNamesAndForwardOriginalIds() {
        val original = expanded(5)
        val expanded =
            original.copy(
                occurrences = original.occurrences.map { it.copy(activity = it.activity.copy(name = "Repeated")) },
            )
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceRoute(controller, {}, {}) } }
        composeRule
            .onLazyNode(
                hasContentDescription(
                    string(
                        R.string.expanded_sequence_row_actions,
                        string(R.string.expanded_sequence_occurrence_identity, "Repeated", 3),
                    ),
                ),
            ).performClick()
        assertTrue(commands.isEmpty())
        composeRule.onNodeWithText(string(R.string.expanded_sequence_make_next)).performClick()
        assertEquals(
            expanded.occurrences[2].occurrence.id,
            (commands.single() as ExpandedSequenceCommand.MakeNext).occurrenceId,
        )
    }

    @Test
    fun jumpConfirmationCancelAndConfirmRetainOriginalTarget() {
        val expanded = expanded(5)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceRoute(controller, {}, {}) } }

        fun openJump() {
            composeRule
                .onLazyNode(
                    hasContentDescription(
                        string(
                            R.string.expanded_sequence_row_actions,
                            string(R.string.expanded_sequence_occurrence_identity, "Activity 2", 3),
                        ),
                    ),
                ).performClick()
            composeRule.onNodeWithText(string(R.string.expanded_sequence_go_now)).performClick()
        }
        openJump()
        assertTrue(commands.isEmpty())
        composeRule
            .onNodeWithText(
                string(R.string.expanded_sequence_occurrence_identity, "Activity 2", 3),
            ).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_cancel)).performClick()
        assertTrue(commands.isEmpty())
        openJump()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_confirm)).performClick()
        assertEquals(
            expanded.occurrences[2].occurrence.id,
            (commands.single() as ExpandedSequenceCommand.GoNow).occurrenceId,
        )
        composeRule.onNodeWithText(string(R.string.expanded_sequence_confirm)).assertDoesNotExist()
    }

    @Test
    fun confirmationCannotExecuteDuringCommandOrAfterTargetBecomesStale() {
        val expanded = expanded(5)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        val state =
            mutableStateOf(
                ExpandedLiveSequenceState(
                    sequence = expanded,
                    loading = false,
                    confirmation = ExpandedSequenceConfirmation.GoNow(expanded.occurrences[2].occurrence.id),
                ),
            )
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceScreen(state.value, controller, {}, 0) } }
        composeRule.runOnIdle { state.value = state.value.copy(commandInFlight = true) }
        composeRule.onNodeWithText(string(R.string.expanded_sequence_confirm)).assertIsNotEnabled()
        composeRule.runOnIdle {
            state.value =
                state.value.copy(
                    sequence = expanded.copy(state = ActiveSequenceState.PAUSED_CURRENT),
                    commandInFlight = false,
                )
        }
        composeRule.onNodeWithText(string(R.string.expanded_sequence_confirm)).assertDoesNotExist()
        assertTrue(commands.isEmpty())
    }

    @Test
    fun currentNumberSetTextMissingAndZeroUseExactFieldAndBlockInvalidComplete() {
        val expanded = expanded(2)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceRoute(controller, {}, {}) } }
        val field =
            expanded.occurrences[1]
                .activity.fields
                .single()
        composeRule
            .onLazyNode(hasTestTag("expanded-sequence-current-value-${field.id.value}"))
            .performTextReplacement("invalid")
        composeRule.onLazyNode(hasText(string(R.string.expanded_sequence_complete))).assertIsNotEnabled()
        composeRule
            .onLazyNode(hasText(string(R.string.expanded_sequence_save_values)))
            .assertIsNotEnabled()
        assertTrue(commands.isEmpty())
        composeRule
            .onLazyNode(hasContentDescription(string(R.string.expanded_sequence_missing_field, "Value")))
            .performClick()
        assertEquals(
            null,
            controller.state.value.currentValueDraft!!
                .values[field.id],
        )
        composeRule
            .onLazyNode(hasTestTag("expanded-sequence-current-value-${field.id.value}"))
            .performTextReplacement("0")
        assertTrue(commands.isEmpty())
        composeRule.onLazyNode(hasText(string(R.string.expanded_sequence_save_values))).performClick()
        val command = commands.single() as ExpandedSequenceCommand.SaveValues
        assertEquals(expanded.occurrences[1].occurrence.id, command.occurrenceId)
        assertEquals(NumberExecutionValue(field.id, 0), command.values.single().value)
    }

    @Test
    fun runtimeAddSelectionsHaveNativeSelectedStateAndInvalidTimerCannotAdd() {
        val expanded = expanded(5)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceRoute(controller, {}, {}) } }
        composeRule.onNodeWithTag("expanded-sequence-global-actions").performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_runtime_add)).performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_to_end)).assertIsSelected()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_after_current)).performClick().assertIsSelected()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_name)).performTextReplacement("One-off")
        composeRule.onNodeWithText(string(R.string.expanded_sequence_timer)).performClick().assertIsSelected()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_timer_seconds)).performTextReplacement("-1")
        composeRule.onNodeWithText(string(R.string.expanded_sequence_add)).assertIsNotEnabled()
        assertTrue(commands.isEmpty())
        composeRule.onNodeWithText(string(R.string.expanded_sequence_no_live)).performClick().assertIsSelected()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_add)).performClick()
        val command = commands.single() as ExpandedSequenceCommand.RuntimeAdd
        assertEquals(com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement.AFTER_CURRENT, command.placement)
        assertTrue(command.source is com.alexandr5476.lifetracing.domain.ActivityEntrySource.OneOff)
    }

    @Test
    fun textAndCategoryEditorsKeepExactFieldAndOptionIdsUntilExplicitComplete() {
        val number =
            ActivitySnapshotField(
                ActivitySnapshotFieldId("number"),
                null,
                0,
                "Weight",
                type = com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                unit = "kg",
                displayPrecision = 0,
            )
        val text =
            ActivitySnapshotField(
                ActivitySnapshotFieldId("text"),
                null,
                1,
                "Notes",
                type = com.alexandr5476.lifetracing.domain.CustomFieldType.TEXT,
            )
        val option =
            com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOption(
                com.alexandr5476.lifetracing.domain
                    .ActivitySnapshotCategoryOptionId("selected-option"),
                null,
                0,
                "Choice",
            )
        val category =
            ActivitySnapshotField(
                ActivitySnapshotFieldId("category"),
                null,
                2,
                "Effort",
                type = com.alexandr5476.lifetracing.domain.CustomFieldType.CATEGORY,
                categoryOptions = listOf(option),
            )
        val expanded = expanded(2, listOf(number, text, category))
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceRoute(controller, {}, {}) } }
        composeRule
            .onLazyNode(hasTestTag("expanded-sequence-current-value-text"))
            .performTextReplacement("Recorded note")
        composeRule.onLazyNode(hasTestTag("expanded-sequence-current-value-category")).performClick()
        assertTrue(commands.isEmpty())
        composeRule.onNode(hasText("Choice") and hasAnyAncestor(isPopup())).performClick()
        composeRule
            .onLazyNode(hasContentDescription(string(R.string.expanded_sequence_field_value, "Effort", "Choice")))
            .performClick()
        composeRule.onNode(hasText("Choice") and hasAnyAncestor(isPopup())).assertIsSelected()
        composeRule.onNode(hasText("Choice") and hasAnyAncestor(isPopup())).performClick()
        composeRule
            .onLazyNode(hasContentDescription(string(R.string.expanded_sequence_missing_field, "Effort")))
            .performClick()
        assertEquals(
            null,
            controller.state.value.currentValueDraft!!
                .values[category.id],
        )
        composeRule.onLazyNode(hasTestTag("expanded-sequence-current-value-category")).performClick()
        composeRule.onNode(hasText("Choice") and hasAnyAncestor(isPopup())).performClick()
        composeRule
            .onLazyNode(hasTestTag("expanded-sequence-current-value-number"))
            .performTextReplacement("0")
        assertTrue(commands.isEmpty())
        composeRule.onLazyNode(hasText(string(R.string.expanded_sequence_complete))).performClick()
        val values = (commands.single() as ExpandedSequenceCommand.Complete).values.associateBy { it.snapshotFieldId }
        assertEquals(
            com.alexandr5476.lifetracing.domain
                .TextExecutionValue(text.id, "Recorded note"),
            values[text.id]!!.value,
        )
        assertEquals(
            com.alexandr5476.lifetracing.domain
                .CategoryExecutionValue(category.id, option.id),
            values[category.id]!!.value,
        )
        assertEquals(NumberExecutionValue(number.id, 0), values[number.id]!!.value)
    }

    @Test
    fun childlessNoLiveWithMainValueUsesDraftAndNeverExposesStandaloneSave() = assertNoLive(true)

    @Test
    fun childlessNoLiveWithoutMainValueHasCompleteAndNoFabricatedDuration() = assertNoLive(false)

    private fun assertNoLive(withMainValue: Boolean) {
        val original = expanded(2)
        val rows =
            original.occurrences.map { row ->
                if (row.occurrence.status == RuntimeOccurrenceStatus.CURRENT) {
                    row.copy(
                        activity =
                            row.activity.copy(
                                timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING,
                                fields =
                                    if (withMainValue) {
                                        row.activity.fields.map {
                                            it.copy(
                                                isMainValue = true,
                                            )
                                        }
                                    } else {
                                        emptyList()
                                    },
                            ),
                        childExecution = null,
                    )
                } else {
                    row
                }
            }
        val expanded = original.copy(occurrences = rows)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceRoute(controller, {}, {}) } }
        composeRule.onNodeWithText(string(R.string.expanded_sequence_complete)).assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_save_values)).assertDoesNotExist()
        composeRule.onLazyNode(hasText(string(R.string.expanded_sequence_no_live))).assertIsDisplayed()
        if (withMainValue) {
            composeRule.onLazyNode(hasTestTag("expanded-sequence-current-value-value")).assertTextContains("6")
        }
        assertTrue(commands.isEmpty())
    }

    @Test
    fun doAgainForwardsEachAuthorizedPlacementWithoutOpeningOldExecution() {
        val base = expanded(5)
        val state = mutableStateOf(ExpandedLiveSequenceState(sequence = base, loading = false))
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(base, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceScreen(state.value, controller, {}, 0) } }
        for (placement in validRuntimePlacements(ActiveSequenceState.RUNNING_CURRENT)) {
            composeRule
                .onLazyNode(
                    hasContentDescription(
                        string(
                            R.string.expanded_sequence_row_actions,
                            string(R.string.expanded_sequence_occurrence_identity, "Activity 0", 1),
                        ),
                    ),
                ).performClick()
            assertEquals(commands.size, commands.filterIsInstance<ExpandedSequenceCommand.DoAgain>().size)
            val label =
                if (placement == com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement.TO_END) {
                    R.string.expanded_sequence_to_end
                } else {
                    R.string.expanded_sequence_after_current
                }
            composeRule
                .onNodeWithText(
                    string(R.string.expanded_sequence_do_again_placement, string(label)),
                ).performClick()
            val command = commands.last() as ExpandedSequenceCommand.DoAgain
            assertEquals(base.occurrences[0].occurrence.id, command.occurrenceId)
            assertEquals(placement, command.placement)
        }
    }

    @Test
    fun alreadyOpenOccurrenceMenuIsDismissedWhenStateAuthorizationChanges() {
        val base = expanded(5)
        val state = mutableStateOf(ExpandedLiveSequenceState(sequence = base, loading = false))
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(base, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceScreen(state.value, controller, {}, 0) } }
        composeRule
            .onLazyNode(
                hasContentDescription(
                    string(
                        R.string.expanded_sequence_row_actions,
                        string(R.string.expanded_sequence_occurrence_identity, "Activity 2", 3),
                    ),
                ),
            ).performClick()
        composeRule.runOnIdle {
            state.value =
                state.value.copy(sequence = base.copy(state = ActiveSequenceState.WAITING_NEXT))
        }
        composeRule.onNodeWithText(string(R.string.expanded_sequence_make_next)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_go_now)).assertDoesNotExist()
        assertTrue(commands.isEmpty())
    }

    @Test
    fun routeLoadingReadFailureRetryAndStaleExitNeverReplayCommands() {
        val base = expanded(5)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        val commands = mutableListOf<ExpandedSequenceCommand>()
        var stale = false
        var exits = 0
        val controller =
            ExpandedLiveSequenceController(
                CoroutineScope(Dispatchers.Unconfined),
                base.runtime.execution.id,
                {
                    gate.await()
                    if (++reads == 1) error("recoverable")
                    if (stale) ExpandedLiveSequenceRead.StaleOrInactive else ExpandedLiveSequenceRead.Active(base)
                },
                { commands += it },
                {},
                MutableStateFlow(0L),
                { null },
                { emptyList() },
                { Instant.EPOCH },
            )
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceRoute(controller, {}, { exits++ }) } }
        composeRule.onNodeWithText(string(R.string.expanded_sequence_loading)).assertIsDisplayed()
        composeRule.runOnIdle { gate.complete(Unit) }
        composeRule.onNodeWithText(string(R.string.expanded_sequence_read_failure)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_retry)).performClick()
        composeRule.onNodeWithTag("expanded-sequence-state").assertExists()
        composeRule.runOnIdle {
            stale = true
            controller.refresh()
        }
        composeRule.onNodeWithTag("expanded-sequence-global-actions").assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_complete)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, exits) }
        assertTrue(commands.isEmpty())
    }

    @Test
    fun doAgainStartNowIsAvailableOnlyWithoutCurrentAndForwardsCompletedId() {
        val base = expanded(5)
        val expanded =
            base.copy(
                state = ActiveSequenceState.WAITING_NEXT,
                runtime = base.runtime.copy(execution = base.runtime.execution.copy(currentOccurrenceId = null)),
            )
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceRoute(controller, {}, {}) } }
        composeRule
            .onLazyNode(
                hasContentDescription(
                    string(
                        R.string.expanded_sequence_row_actions,
                        string(R.string.expanded_sequence_occurrence_identity, "Activity 0", 1),
                    ),
                ),
            ).performClick()
        composeRule
            .onNodeWithText(
                string(
                    R.string.expanded_sequence_do_again_placement,
                    string(R.string.expanded_sequence_after_current),
                ),
            ).assertDoesNotExist()
        composeRule
            .onNodeWithText(
                string(
                    R.string.expanded_sequence_do_again_placement,
                    string(R.string.expanded_sequence_start_now),
                ),
            ).performClick()
        val command = commands.single() as ExpandedSequenceCommand.DoAgain
        assertEquals(base.occurrences[0].occurrence.id, command.occurrenceId)
        assertEquals(com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement.START_NOW, command.placement)
    }

    @Test
    fun disabledConfirmationPoliciesDispatchExistingCommandsDirectly() {
        val base = expanded(5)
        val expanded =
            base.copy(
                runtime =
                    base.runtime.copy(
                        snapshot =
                            base.runtime.snapshot.copy(
                                settings =
                                    base.runtime.snapshot.settings
                                        .copy(confirmJump = false, confirmEarlyEnd = false),
                            ),
                    ),
            )
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceRoute(controller, {}, {}) } }
        composeRule
            .onLazyNode(
                hasContentDescription(
                    string(
                        R.string.expanded_sequence_row_actions,
                        string(R.string.expanded_sequence_occurrence_identity, "Activity 2", 3),
                    ),
                ),
            ).performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_go_now)).performClick()
        assertEquals(
            base.occurrences[2].occurrence.id,
            (commands.single() as ExpandedSequenceCommand.GoNow).occurrenceId,
        )
        composeRule.onNodeWithText(string(R.string.expanded_sequence_confirm)).assertDoesNotExist()
        composeRule.onLazyNode(hasTestTag("expanded-sequence-global-actions")).performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_end_early)).performClick()
        assertTrue(commands.last() is ExpandedSequenceCommand.EndEarly)
        assertEquals(2, commands.size)
    }

    @Test
    fun englishAndRussianControlsReflowAtCombinedSystemAndAppScales() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val language = mutableStateOf("en")
        val appearance =
            mutableStateOf(
                com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences(
                    interfaceScalePercent = 120,
                    textScalePercent = 130,
                ),
            )
        val expanded = expanded(5)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        composeRule.setContent {
            val configuration =
                android.content.res.Configuration(original.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(language.value))
                    fontScale = 2f
                }
            val context = original.createConfigurationContext(configuration)
            val parent = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides context,
                androidx.compose.ui.platform.LocalConfiguration provides configuration,
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit
                        .Density(parent.density, 2f),
            ) {
                com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance(appearance.value) {
                    ExpandedLiveSequenceRoute(controller, {}, {})
                }
            }
        }
        for (locale in listOf("en", "ru")) {
            composeRule.runOnIdle { language.value = locale }
            val configuration =
                android.content.res.Configuration(original.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(locale))
                }
            val context = original.createConfigurationContext(configuration)

            fun assertConfirmationActions() {
                val confirm = composeRule.onNodeWithText(context.getString(R.string.expanded_sequence_confirm))
                val cancel = composeRule.onNodeWithText(context.getString(R.string.expanded_sequence_cancel))
                confirm.assertIsDisplayed().assertIsEnabled()
                cancel.assertIsDisplayed().assertIsEnabled()
                val confirmBounds = confirm.fetchSemanticsNode().touchBoundsInRoot
                val cancelBounds = cancel.fetchSemanticsNode().touchBoundsInRoot
                assertTrue(
                    "Confirmation actions must have separate accessible targets",
                    !confirmBounds.overlaps(cancelBounds),
                )
            }

            val complete =
                composeRule
                    .onLazyNode(hasText(context.getString(R.string.expanded_sequence_complete)))
            complete.assertIsDisplayed()
            val pause =
                composeRule
                    .onLazyNode(hasText(context.getString(R.string.expanded_sequence_pause)))
            pause.assertIsDisplayed()
            val completeBounds = complete.fetchSemanticsNode().touchBoundsInRoot
            val pauseBounds = pause.fetchSemanticsNode().touchBoundsInRoot
            assertTrue(completeBounds.bottom <= pauseBounds.top || completeBounds.right <= pauseBounds.left)
            composeRule
                .onLazyNode(hasTestTag("expanded-sequence-global-actions"))
                .assertIsDisplayed()
                .assertIsEnabled()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            composeRule
                .onNode(
                    hasText(context.getString(R.string.expanded_sequence_runtime_add)) and hasAnyAncestor(isPopup()),
                ).assertIsDisplayed()
                .assertIsEnabled()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            composeRule
                .onNodeWithText(context.getString(R.string.expanded_sequence_name))
                .performScrollTo()
                .assertIsDisplayed()
            composeRule
                .onNodeWithText(context.getString(R.string.expanded_sequence_cancel))
                .assertIsDisplayed()
                .assertIsEnabled()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            composeRule.onNodeWithText(context.getString(R.string.expanded_sequence_name)).assertDoesNotExist()
            composeRule.onNode(isPopup()).assertDoesNotExist()
            composeRule
                .onLazyNode(hasTestTag("expanded-sequence-global-actions"))
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            composeRule
                .onNode(hasText(context.getString(R.string.expanded_sequence_end_early)) and hasAnyAncestor(isPopup()))
                .assertIsDisplayed()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            composeRule
                .onNodeWithText(context.getString(R.string.expanded_sequence_confirm_end_title))
                .assertIsDisplayed()
                .assert(
                    androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                        androidx.compose.ui.semantics.SemanticsProperties.Heading,
                    ),
                )
            assertConfirmationActions()
            composeRule
                .onNodeWithText(context.getString(R.string.expanded_sequence_confirm_detail))
                .performScrollTo()
                .assertIsDisplayed()
            assertConfirmationActions()
            composeRule
                .onNodeWithText(context.getString(R.string.expanded_sequence_cancel))
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            composeRule.onNodeWithText(context.getString(R.string.expanded_sequence_confirm)).assertDoesNotExist()
            composeRule
                .onLazyNode(
                    hasContentDescription(
                        context.getString(
                            R.string.expanded_sequence_row_actions,
                            context.getString(R.string.expanded_sequence_occurrence_identity, "Activity 2", 3),
                        ),
                    ),
                ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            composeRule
                .onNode(hasText(context.getString(R.string.expanded_sequence_make_next)) and hasAnyAncestor(isPopup()))
                .assertIsDisplayed()
                .assertIsEnabled()
            composeRule
                .onNode(hasText(context.getString(R.string.expanded_sequence_go_now)) and hasAnyAncestor(isPopup()))
                .assertIsDisplayed()
                .assertIsEnabled()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            composeRule
                .onNodeWithText(context.getString(R.string.expanded_sequence_confirm_jump_title))
                .assertIsDisplayed()
            assertConfirmationActions()
            composeRule
                .onNodeWithText(context.getString(R.string.expanded_sequence_occurrence_identity, "Activity 2", 3))
                .performScrollTo()
                .assertIsDisplayed()
            assertConfirmationActions()
            composeRule
                .onNodeWithText(context.getString(R.string.expanded_sequence_cancel))
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            composeRule.onNodeWithText(context.getString(R.string.expanded_sequence_confirm)).assertDoesNotExist()
            composeRule.onNode(isPopup()).assertDoesNotExist()
            assertTrue(commands.isEmpty())
        }
        composeRule.runOnIdle {
            appearance.value =
                com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences(
                    interfaceScalePercent = 90,
                    textScalePercent = 90,
                )
        }
        val minimum = 48 * original.resources.displayMetrics.density
        val menu =
            composeRule
                .onLazyNode(hasTestTag("expanded-sequence-global-actions"))
                .assertIsDisplayed()
                .assertIsEnabled()
        val bounds = menu.fetchSemanticsNode().touchBoundsInRoot
        assertTrue(
            "Global menu touch height must be at least 48 Android dp: $bounds",
            bounds.height >= minimum - 1,
        )
        assertTrue(
            "Global menu touch width must be at least 48 Android dp: $bounds",
            bounds.width >= minimum - 1,
        )
        menu.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        composeRule.onNode(isPopup()).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        composeRule.onNode(isPopup()).assertDoesNotExist()
        assertTrue(commands.isEmpty())
    }

    @Test
    fun runtimeCatalogFailureRetryLoadingAndPagingKeepExactTemplateIdentity() {
        val expanded = expanded(5)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val page = (0 until 50).map { catalogItem("item-$it", "Catalog ${it.toString().padStart(2, '0')}") }
        val last = catalogItem("later-template", "Later page activity")
        val secondPage =
            kotlinx.coroutines
                .CompletableDeferred<List<com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem>>()
        val cursors = mutableListOf<com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem?>()
        val controller =
            controller(expanded, commands) { after ->
                cursors += after
                when {
                    cursors.size == 1 -> error("recoverable catalog")
                    after == null -> page
                    else -> secondPage.await()
                }
            }
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceRoute(controller, {}, {}) } }
        composeRule.onNodeWithTag("expanded-sequence-global-actions").performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_runtime_add)).performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_catalog_failure)).assertExists()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_retry)).performClick()
        composeRule.onNodeWithTag("expanded-sequence-add-catalog").performScrollToNode(
            hasText(string(R.string.sequence_editor_load_more)),
        )
        composeRule.onNodeWithText(string(R.string.sequence_editor_load_more)).performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_catalog_loading)).assertExists()
        assertTrue(commands.isEmpty())
        composeRule.runOnIdle { secondPage.complete(listOf(last)) }
        composeRule.onNodeWithTag("expanded-sequence-add-catalog").performScrollToNode(hasText(last.name))
        composeRule.onNodeWithText(last.name).performClick()
        assertEquals(listOf(null, null, page.last()), cursors)
        val command = commands.single() as ExpandedSequenceCommand.RuntimeAdd
        assertEquals(
            com.alexandr5476.lifetracing.domain.ActivityEntrySource
                .Template(last.id),
            command.source,
        )
    }

    @Test
    fun runtimeAddAllEditorsAndWritersAreDisabledDuringAdmittedCommand() {
        val expanded = expanded(5)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val state =
            mutableStateOf(
                ExpandedLiveSequenceState(
                    sequence = expanded,
                    loading = false,
                    catalog = listOf(catalogItem("template", "Reusable target")),
                ),
            )
        val controller = controller(expanded, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceScreen(state.value, controller, {}, 0) } }
        composeRule.onNodeWithTag("expanded-sequence-global-actions").performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_runtime_add)).performClick()
        composeRule
            .onNodeWithText(
                string(R.string.expanded_sequence_name),
            ).performScrollTo()
            .performTextReplacement("One-off")
        composeRule.runOnIdle { state.value = state.value.copy(commandInFlight = true) }
        composeRule.onNodeWithText(string(R.string.expanded_sequence_add)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_name)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_to_end)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_stopwatch)).assertIsNotEnabled()
        composeRule.onNodeWithText("Reusable target").assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_cancel)).performClick()
        assertTrue(commands.isEmpty())
    }

    @Test
    fun rejectionStaleTargetAndCommittedCoordinationFeedbackRemainDistinct() {
        val expanded = expanded(5)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val state = mutableStateOf(ExpandedLiveSequenceState(sequence = expanded, loading = false))
        val controller = controller(expanded, commands)
        composeRule.setContent { LifeTracingTheme { ExpandedLiveSequenceScreen(state.value, controller, {}, 0) } }
        val failures =
            listOf(
                ExpandedSequenceFailure.Rejected("rejected") to R.string.expanded_sequence_command_rejected,
                ExpandedSequenceFailure.StaleTarget("stale") to R.string.expanded_sequence_target_stale,
                ExpandedSequenceFailure.Coordination("committed") to R.string.expanded_sequence_coordination_failed,
            )
        for ((failure, label) in failures) {
            composeRule.runOnIdle { state.value = state.value.copy(commandFailure = failure) }
            composeRule.onLazyNode(hasText(string(label))).assert(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                    androidx.compose.ui.semantics.LiveRegionMode.Polite,
                ),
            )
        }
        assertTrue(commands.isEmpty())
    }

    private fun catalogItem(
        id: String,
        name: String,
    ) = com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem(
        com.alexandr5476.lifetracing.domain
            .ActivityTemplateId(id),
        name,
        TimeTrackingMode.STOPWATCH,
        null,
        null,
        null,
        null,
        null,
    )

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun keyboardFocusTimerRecompositionAndBackDismissalNeverDispatchCommands() {
        val expanded = expanded(5)
        val commands = mutableListOf<ExpandedSequenceCommand>()
        val controller = controller(expanded, commands)
        val tick = androidx.compose.runtime.mutableLongStateOf(0)
        composeRule.setContent {
            val state by controller.state.collectAsState()
            LifeTracingTheme { ExpandedLiveSequenceScreen(state, controller, {}, tick.longValue) }
        }
        keyboardInput.enter()
        val menu = composeRule.onNodeWithTag("expanded-sequence-global-actions")
        menu.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        menu.assertIsFocused()
        composeRule.runOnIdle { tick.longValue = 60_000 }
        menu.assertIsFocused()
        menu.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        composeRule.onNodeWithText(string(R.string.expanded_sequence_end_early)).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        composeRule.onNodeWithText(string(R.string.expanded_sequence_end_early)).assertDoesNotExist()
        assertTrue(commands.isEmpty())
        menu.performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_end_early)).performClick()
        composeRule.onNodeWithText(string(R.string.expanded_sequence_confirm_end_title)).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        composeRule.onNodeWithText(string(R.string.expanded_sequence_confirm)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(null, controller.state.value.confirmation) }
        assertTrue(commands.isEmpty())
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
        readCatalog: suspend (com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem?) ->
        List<com.alexandr5476.lifetracing.domain.ReusableActivityCatalogItem> = { emptyList() },
    ) = ExpandedLiveSequenceController(
        CoroutineScope(Dispatchers.Unconfined),
        expanded.runtime.execution.id,
        { ExpandedLiveSequenceRead.Active(expanded) },
        { commands += it },
        {},
        MutableStateFlow(0L),
        { null },
        readCatalog,
        { Instant.EPOCH },
    )

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
}
