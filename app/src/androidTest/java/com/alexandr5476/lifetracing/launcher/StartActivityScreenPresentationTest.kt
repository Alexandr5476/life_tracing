package com.alexandr5476.lifetracing.launcher

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityLaunchMainValue
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryOption
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.onLazyNode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class StartActivityScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val keyboardInput = KeyboardInputFixture(composeTestRule)

    @Test
    fun launcherHeadingsAndOneOffSelectedModeExposeNativeSemantics() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        var state by mutableStateOf(homeState(emptyList(), emptyList()))
        composeTestRule.setContent {
            LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) }
        }
        listOf(
            R.string.launcher_title,
            R.string.launcher_recent,
            R.string.launcher_pinned,
            R.string.launcher_search,
            R.string.launcher_browse,
        ).forEach { id ->
            composeTestRule
                .onLazyNode(hasText(text(id)))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
        state = state.copy(oneOff = OneOffDraft(name = "One off", mode = TimeTrackingMode.TIMER))
        composeTestRule
            .onLazyNode(
                hasText(text(R.string.launcher_one_off_timer), substring = true) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(StartActivityAction.EditOneOffMode(TimeTrackingMode.TIMER), actions.single())
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_one_off_stopwatch)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test
    fun oneOffAffordanceAndTimerValidationUseSeparateLauncherForm() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        var state by mutableStateOf(homeState(recent = listOf(trackable("Sequence", sequence = true))))
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) } }

        composeTestRule.onLazyNode(hasTestTag("launcher-new-one-off")).performClick()
        assertEquals(StartActivityAction.OpenOneOff, actions.last())
        state = state.copy(oneOff = OneOffDraft())
        composeTestRule.onLazyNode(hasTestTag("launcher-one-off-execute")).assertIsNotEnabled()
        state = state.copy(oneOff = OneOffDraft(name = "Run", mode = TimeTrackingMode.TIMER))
        composeTestRule.onLazyNode(hasTestTag("launcher-one-off-timer")).assertIsDisplayed()
        composeTestRule.onLazyNode(hasTestTag("launcher-one-off-execute")).assertIsNotEnabled()
        state = state.copy(oneOff = OneOffDraft(name = "Run", mode = TimeTrackingMode.TIMER, timerMinutes = "5"))
        composeTestRule.onLazyNode(hasTestTag("launcher-one-off-execute")).assertIsEnabled()
        composeTestRule.onLazyNode(hasTestTag("launcher-one-off-execute")).performClick()
        assertEquals(StartActivityAction.ExecuteOneOff, actions.last())
        state = state.copy(command = LauncherCommandState.Conflict("active"))
        composeTestRule.onLazyNode(hasTestTag("launcher-one-off-execute")).performClick()
        assertEquals(StartActivityAction.RetryLaunch, actions.last())
    }

    @Test
    fun immediateSelectionUsesOneSelectThenLaunchWithoutConfirmation() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        val recent = trackable("Recent Activity")
        var state by mutableStateOf(homeState(recent = listOf(recent)))
        var hostGeneration by mutableIntStateOf(0)
        composeTestRule.setContent {
            key(hostGeneration) {
                LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) }
            }
        }

        composeTestRule.onNodeWithText("Recent Activity").performClick()
        assertEquals(listOf(StartActivityAction.Select(recent.id)), actions)

        state = state.copy(selected = LauncherLoad.Loading)
        hostGeneration++
        state =
            state.copy(
                selected =
                    LauncherLoad.Content(
                        activityTarget(trackable("Wrong").id, mode = TimeTrackingMode.STOPWATCH),
                    ),
            )
        composeTestRule.runOnIdle {
            assertFalse(actions.any { it is StartActivityAction.Launch })
        }
        state = state.copy(selected = LauncherLoad.Failure("failed"))
        hostGeneration++
        composeTestRule.onNodeWithText(text(com.alexandr5476.lifetracing.R.string.launcher_retry)).performClick()
        assertEquals(StartActivityAction.Retry, actions.last())
        state =
            state.copy(
                selected = LauncherLoad.Content(activityTarget(recent.id, mode = TimeTrackingMode.STOPWATCH)),
            )
        composeTestRule.runOnIdle {
            assertEquals(
                1,
                actions.count { it is StartActivityAction.Launch },
            )
        }
        hostGeneration++
        composeTestRule.runOnIdle { assertEquals(1, actions.count { it is StartActivityAction.Launch }) }
    }

    @Test
    fun catalogLoadingFailureEmptySearchBrowseAndMixedRowsAreVisibleAndActionable() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        var state by mutableStateOf(StartActivityState())
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) } }

        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_loading)))
            .assertIsDisplayed()
        state = state.copy(home = LauncherLoad.Failure("failed"))
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_home_failure)))
            .assertIsDisplayed()
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_retry)))
            .performClick()
        assertEquals(StartActivityAction.Retry, actions.last())

        state = state.copy(home = LauncherLoad.Content(LauncherHome(emptyList(), emptyList())))
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_recent_empty)))
            .assertIsDisplayed()
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_pinned_empty)))
            .assertIsDisplayed()

        val activity = trackable("Mixed activity")
        val sequence = trackable("Mixed sequence", sequence = true)
        state =
            state.copy(
                home = LauncherLoad.Content(LauncherHome(listOf(activity, sequence), emptyList())),
                search = LauncherLoad.Loading,
                browse = LauncherLoad.Loading,
            )
        composeTestRule
            .onLazyNode(hasText("Mixed activity"))
            .assertIsDisplayed()
        composeTestRule
            .onLazyNode(hasText("Mixed sequence"))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(
                text(R.string.launcher_activity),
            ).assertCountEquals(1)
        composeTestRule
            .onAllNodesWithText(
                text(R.string.launcher_sequence),
            ).assertCountEquals(1)
        composeTestRule
            .onNodeWithText(text(R.string.launcher_recent_empty))
            .assertDoesNotExist()
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_pinned_empty)))
            .assertIsDisplayed()
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_search_loading)))
            .assertIsDisplayed()
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_browse_loading)))
            .performScrollTo()
            .assertIsDisplayed()

        state = state.copy(search = LauncherLoad.Content(listOf(trackable("Search result"))))
        composeTestRule
            .onLazyNode(hasText("Search result"))
            .assertIsDisplayed()
        state = state.copy(search = LauncherLoad.Content(emptyList()), browse = LauncherLoad.Content(emptyContents()))
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_search_empty)))
            .assertIsDisplayed()
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_browse_empty)))
            .assertIsDisplayed()

        state = state.copy(search = LauncherLoad.Failure("search"), browse = LauncherLoad.Failure("browse"))
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_search_failure)))
            .assertIsDisplayed()
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_browse_failure)))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun browseRootNestedFolderTargetLoadingAndSequenceSelectionUseTheirExactIdentities() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        var routeBacks = 0
        val folder = Folder(FolderId("nested"), "Nested", null, Instant.EPOCH, Instant.EPOCH)
        val sequence = trackable("Run sequence", sequence = true)
        var state by mutableStateOf(homeState(recent = listOf(sequence), pinned = emptyList()))
        var hostGeneration by mutableIntStateOf(0)
        composeTestRule.setContent {
            key(hostGeneration) {
                LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) { routeBacks++ } }
            }
        }

        composeTestRule.onNodeWithText(text(com.alexandr5476.lifetracing.R.string.launcher_browse_open)).performClick()
        assertEquals(StartActivityAction.Browse(null), actions.last())
        state = state.copy(browse = LauncherLoad.Content(LibraryContents(listOf(folder), emptyList(), emptyList())))
        composeTestRule.onNodeWithText("Nested").performClick()
        assertEquals(StartActivityAction.Browse(folder.id), actions.last())
        state = state.copy(browseFolderId = folder.id, browse = LauncherLoad.Content(emptyContents()))
        hostGeneration++
        composeTestRule.onNodeWithText("Nested").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(text(com.alexandr5476.lifetracing.R.string.launcher_browse_open))
            .assertDoesNotExist()
        composeTestRule.onNodeWithText(text(com.alexandr5476.lifetracing.R.string.launcher_browse_back)).performClick()
        assertEquals(StartActivityAction.Browse(null), actions.last())
        assertEquals(0, routeBacks)

        composeTestRule.onNodeWithText("Run sequence").performClick()
        assertEquals(StartActivityAction.Select(sequence.id), actions.last())
        state = state.copy(selected = LauncherLoad.Loading)
        hostGeneration++
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_target_loading),
            ).assertIsDisplayed()
        state = state.copy(selected = LauncherLoad.Failure("target"))
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_target_failure),
            ).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(com.alexandr5476.lifetracing.R.string.launcher_retry)).performClick()
        assertEquals(StartActivityAction.Retry, actions.last())
        state = state.copy(selected = LauncherLoad.Content(sequenceTarget(sequence.id)))
        composeTestRule.runOnIdle { assertEquals(StartActivityAction.Launch(), actions.last()) }
    }

    @Test
    fun preflightConflictRejectedAndCommitStatesExposeOnlyTheirAcceptedActions() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        var state by mutableStateOf(homeState(recent = emptyList(), pinned = emptyList()))
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) } }

        state =
            state.copy(
                command =
                    LauncherCommandState.Preflight(
                        1,
                        LibraryTemplateId.Activity(ActivityTemplateId("activity")),
                        Duration.ofSeconds(5),
                        Instant.now(),
                        Instant.now().plusSeconds(5),
                    ),
            )
        composeTestRule.onNodeWithText(text(com.alexandr5476.lifetracing.R.string.launcher_cancel)).performClick()
        assertEquals(StartActivityAction.CancelPreflight, actions.last())

        state = state.copy(command = LauncherCommandState.Conflict("conflict"))
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_conflict),
            ).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(com.alexandr5476.lifetracing.R.string.launcher_cancel)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(com.alexandr5476.lifetracing.R.string.launcher_retry)).performClick()
        assertEquals(StartActivityAction.RetryLaunch, actions.last())

        state = state.copy(command = LauncherCommandState.Rejected("rejected"))
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_rejected),
            ).assertIsDisplayed()
        state = state.copy(command = LauncherCommandState.Committing(1))
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_committing),
            ).assertIsDisplayed()
    }

    @Test
    fun noLiveMainValueStaysCompactAndSearchStartsIdle() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        val trackable = trackable("Count glasses")
        var state by mutableStateOf(homeState(recent = listOf(trackable)))
        var hostGeneration by mutableIntStateOf(0)
        composeTestRule.setContent {
            key(hostGeneration) {
                LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) }
            }
        }

        composeTestRule.onNodeWithText("Count glasses").performClick()
        state =
            state.copy(
                selected =
                    LauncherLoad.Content(
                        activityTarget(
                            trackable.id,
                            mainValue =
                                ActivityLaunchMainValue(
                                    ActivityTemplateFieldId("water"),
                                    "Glasses",
                                    "cups",
                                    0,
                                    2_000,
                                ),
                        ),
                    ),
            )
        composeTestRule.onNodeWithText("Glasses cups").assertIsDisplayed()
        composeTestRule.onNodeWithText("Complete").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertFalse(actions.any { it is StartActivityAction.Launch })
        }

        val mainValueInput = composeTestRule.onNodeWithText("2")
        mainValueInput.performTextClearance()
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("-1.25")
        hostGeneration++
        composeTestRule.onNodeWithText("-1.25").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        hostGeneration++
        composeTestRule.onNodeWithText("Complete").assertDoesNotExist()
        composeTestRule.onNodeWithText("Search library").performTextInput("water")
        composeTestRule.runOnIdle {
            assertEquals(StartActivityAction.Search("water"), actions.last())
        }
    }

    @Test
    fun roundedDefaultRemainsEnabledAndPinnedMoveUsesTheCompleteOrder() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        val activity = trackable("Precise value")
        val sequence = trackable("Pinned sequence", sequence = true)
        val pinnedActivity = trackable("Pinned activity")
        var state by mutableStateOf(homeState(recent = listOf(activity), pinned = listOf(sequence, pinnedActivity)))
        composeTestRule.setContent {
            LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) }
        }

        composeTestRule
            .onNodeWithContentDescription("Move Pinned sequence down")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(
            StartActivityAction.ReorderPinned(listOf(pinnedActivity.id, sequence.id)),
            actions.last(),
        )

        state = state.copy(organizationInFlight = true)
        composeTestRule
            .onNodeWithContentDescription("Move Pinned activity up")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        state = state.copy(organizationInFlight = false, organizationFailure = "failed")
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_pinned_failure),
            ).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Move Pinned sequence down").assertIsEnabled()
        state =
            state.copy(
                home = LauncherLoad.Content(LauncherHome(listOf(activity), listOf(pinnedActivity, sequence))),
                organizationFailure = null,
            )
        composeTestRule.onNodeWithContentDescription("Move Pinned activity down").assertIsEnabled()

        composeTestRule.onNodeWithText("Precise value").performClick()
        state =
            state.copy(
                selected =
                    LauncherLoad.Content(
                        activityTarget(
                            activity.id,
                            mainValue =
                                ActivityLaunchMainValue(
                                    ActivityTemplateFieldId("precise"),
                                    "Value",
                                    null,
                                    2,
                                    1_234,
                                ),
                        ),
                    ),
            )
        composeTestRule.onNodeWithText("Complete").assertIsEnabled().performClick()
        composeTestRule.runOnIdle {
            assertEquals(StartActivityAction.Launch(), actions.last())
        }
    }

    @Test
    fun pointerDragFollowsIdentityAcrossMultipleMovesAndCancellationRestoresOrder() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        val sequence = trackable("Drag sequence", sequence = true)
        val activity = trackable("Drag activity")
        val third = trackable("Drag third", sequence = true)
        composeTestRule.setContent {
            LifeTracingTheme {
                StartActivityScreen(
                    homeState(recent = emptyList(), pinned = listOf(sequence, activity, third)),
                    actions::add,
                    interaction,
                )
            }
        }

        composeTestRule
            .onAllNodesWithContentDescription(
                text(com.alexandr5476.lifetracing.R.string.launcher_drag_handle),
            )[0]
            .performTouchInput {
                down(center)
                advanceEventTime(1_000)
                moveBy(Offset(0f, 100f))
                moveBy(Offset(0f, 100f))
                cancel()
            }

        composeTestRule.runOnIdle {
            assertFalse(actions.any { it is StartActivityAction.ReorderPinned })
            assertFalse(actions.any { it is StartActivityAction.Select })
        }
        composeTestRule.onNodeWithContentDescription("Move Drag sequence down").assertIsEnabled()

        composeTestRule
            .onAllNodesWithContentDescription(
                text(com.alexandr5476.lifetracing.R.string.launcher_drag_handle),
            )[0]
            .performTouchInput {
                down(center)
                advanceEventTime(1_000)
                moveBy(Offset(0f, 100f))
                moveBy(Offset(0f, 100f))
                up()
            }
        assertEquals(
            listOf(StartActivityAction.ReorderPinned(listOf(activity.id, third.id, sequence.id))),
            actions,
        )
    }

    @Test
    fun secondaryStartOptionsOpensWithoutChangingOneActionPrimarySelection() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        val activity = trackable("Options activity")
        val sequence = trackable("Sequence", sequence = true)
        var state by mutableStateOf(homeState(recent = listOf(activity, sequence)))
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) } }

        composeTestRule.onNodeWithTag("launcher-options-${activity.id.value}").performClick()
        assertEquals(listOf(StartActivityAction.OpenOptions(ActivityTemplateId(activity.id.value))), actions)
        assertFalse(actions.any { it is StartActivityAction.Launch })
        composeTestRule.onNodeWithTag("launcher-options-${sequence.id.value}").assertDoesNotExist()

        val template =
            ActivityTemplate(
                ActivityTemplateId(activity.id.value),
                activity.name,
                null,
                TimeTrackingMode.STOPWATCH,
                null,
                StatisticsSeriesId("series"),
                1,
                Instant.parse("2026-08-20T10:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
            )
        val draft = template.initialStartOptions(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC)
        state = state.copy(options = LauncherLoad.Content(draft.copy(issue = StartOptionsIssue.EXPIRED_FINISH)))
        composeTestRule.onNodeWithTag("launcher-options-save").assertIsDisplayed().assertIsEnabled()
        state =
            state.copy(
                options = LauncherLoad.Content(draft.copy(issue = StartOptionsIssue.STALE_TEMPLATE, stale = true)),
            )
        composeTestRule.onNodeWithTag("launcher-options-save").assertDoesNotExist()
        composeTestRule
            .onNodeWithText(text(com.alexandr5476.lifetracing.R.string.manual_history_review_current))
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(StartActivityAction.ReviewOptionsTemplate, actions.last())
        state =
            state.copy(
                options =
                    LauncherLoad.Content(
                        draft.copy(
                            overlap =
                                StartOptionsProposal(
                                    com.alexandr5476.lifetracing.domain.ActivityEntrySource
                                        .Template(template.id),
                                    template.revision,
                                    Instant.parse("2026-08-20T08:00:00Z"),
                                    Instant.parse("2026-08-20T09:00:00Z"),
                                    Instant.parse("2026-08-20T10:00:00Z"),
                                    ZoneOffset.UTC,
                                    emptyList(),
                                    0,
                                ),
                        ),
                    ),
            )
        composeTestRule.onNodeWithTag("launcher-options-save").assertDoesNotExist()
        state = state.copy(command = LauncherCommandState.Committing(1))
        composeTestRule
            .onNodeWithText(text(com.alexandr5476.lifetracing.R.string.manual_history_proceed))
            .assertIsNotEnabled()
    }

    @Test
    fun ambiguousOffsetShowsSelectedIndicatorOnlyOnChosenOffset() {
        val zone = ZoneId.of("Europe/Berlin")
        val local = LocalDateTime.parse("2026-10-25T02:30:00")
        val offsets = zone.rules.getValidOffsets(local)
        assertEquals(2, offsets.size)
        val draft =
            optionsTemplate().initialStartOptions(Instant.parse("2026-10-25T10:00:00Z"), zone).copy(
                startedText = "2026-10-25 02:30",
                startedOffsets = offsets,
                startedOffset = offsets[1],
            )
        var state by mutableStateOf(StartActivityState(options = LauncherLoad.Content(draft)))
        val interaction = StartActivityRouteInteraction()
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, {}, interaction) } }

        val firstLabel =
            composeTestRule.activity.getString(
                com.alexandr5476.lifetracing.R.string.manual_history_first_occurrence,
                offsets[0].id,
            )
        val secondLabel =
            composeTestRule.activity.getString(
                com.alexandr5476.lifetracing.R.string.manual_history_second_occurrence,
                offsets[1].id,
            )
        composeTestRule.onNodeWithText(secondLabel + " \u2713").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(firstLabel).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(firstLabel + " \u2713").assertDoesNotExist()
        composeTestRule.onNodeWithText(secondLabel).assertDoesNotExist()
        composeTestRule.onAllNodesWithText("\u2713", substring = true).assertCountEquals(1)

        state =
            state.copy(
                options =
                    LauncherLoad.Content(
                        draft.copy(
                            startedOffsets = emptyList(),
                            completedText = "2026-10-25 02:30",
                            completedOffsets = offsets,
                            completedOffset = offsets[0],
                        ),
                    ),
            )
        composeTestRule.onNodeWithText(firstLabel + " \u2713").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(secondLabel).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(secondLabel + " \u2713").assertDoesNotExist()
        composeTestRule.onNodeWithText(firstLabel).assertDoesNotExist()
        composeTestRule.onAllNodesWithText("\u2713", substring = true).assertCountEquals(1)
    }

    @Test
    fun categoryShowsSelectedIndicatorOnlyOnNonMissingChosenOption() {
        val now = Instant.parse("2026-08-20T10:00:00Z")
        val chosen = CategoryOption(CategoryOptionId("chosen"), 1, "Chosen option")
        val other = CategoryOption(CategoryOptionId("other"), 0, "Other option")
        val field =
            ActivityTemplateField(
                id = ActivityTemplateFieldId("category"),
                position = 0,
                name = "Category field",
                type = CustomFieldType.CATEGORY,
                defaultCategoryOptionId = chosen.id,
                createdAt = now,
                updatedAt = now,
                categoryOptions = listOf(other, chosen),
            )
        val draft = optionsTemplate(listOf(field)).initialStartOptions(now, ZoneOffset.UTC)
        assertEquals(chosen.id, draft.values.getValue(field.id).selectedOptionId)
        assertFalse(draft.values.getValue(field.id).missing)
        var state by mutableStateOf(StartActivityState(options = LauncherLoad.Content(draft)))
        val interaction = StartActivityRouteInteraction()
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, {}, interaction) } }

        composeTestRule.onNodeWithText(chosen.label + " \u2713").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(other.label).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(other.label + " \u2713").assertDoesNotExist()
        composeTestRule.onNodeWithText(chosen.label).assertDoesNotExist()
        composeTestRule.onAllNodesWithText("\u2713", substring = true).assertCountEquals(1)

        state =
            state.copy(
                options =
                    LauncherLoad.Content(
                        draft.copy(values = mapOf(field.id to draft.values.getValue(field.id).copy(missing = true))),
                    ),
            )
        composeTestRule.onNodeWithText(chosen.label).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(other.label).performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("\u2713", substring = true).assertCountEquals(0)
    }

    @Test
    fun typedDuplicateNamesKeepPrimaryAndOptionsActionsIndependentInBothTrees() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        val activity = trackable("Same")
        val sequence = trackable("Same", sequence = true)
        var state by mutableStateOf(homeState(listOf(activity, sequence), emptyList()))
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) } }
        keyboardInput.enter()
        val activityAction = hasText("Same") and hasText(text(R.string.launcher_activity)) and hasClickAction()
        val sequenceAction = hasText("Same") and hasText(text(R.string.launcher_sequence)) and hasClickAction()
        composeTestRule.onNode(activityAction).performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        composeTestRule.runOnIdle { assertEquals(emptyList<StartActivityAction>(), actions) }
        composeTestRule.onNode(activityAction).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNode(sequenceAction).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(StartActivityAction.Select(activity.id), StartActivityAction.Select(sequence.id)), actions)
        val options = composeTestRule.activity.getString(R.string.launcher_options_for, "Same")
        for (unmerged in listOf(false, true)) {
            val primary = hasClickAction() and (hasText("Same") or hasAnyDescendant(hasText("Same")))
            composeTestRule.onAllNodes(primary, useUnmergedTree = unmerged).assertCountEquals(2)
            composeTestRule
                .onAllNodes(hasContentDescription(options) and hasClickAction(), useUnmergedTree = unmerged)
                .assertCountEquals(1)
        }
        composeTestRule.onNodeWithContentDescription(options).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(StartActivityAction.OpenOptions(ActivityTemplateId(activity.id.value)), actions.last())
        for (blocked in listOf(
            state.copy(organizationInFlight = true),
            state.copy(selected = LauncherLoad.Loading),
            state.copy(options = LauncherLoad.Loading),
            state.copy(oneOff = OneOffDraft()),
            state.copy(command = LauncherCommandState.Checking(1)),
            state.copy(command = LauncherCommandState.Committing(1)),
        )) {
            state = blocked
            composeTestRule
                .onLazyNode(activityAction)
                .assertIsNotEnabled()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
            composeTestRule.onLazyNode(hasContentDescription(options)).assertIsNotEnabled()
        }
        assertEquals(3, actions.size)
    }

    @Test
    fun pinnedBoundaryMovesAndDragMetadataDoNotOfferImpossibleCommands() {
        val first = trackable("First", sequence = true)
        val middle = trackable("Middle")
        val last = trackable("Last", sequence = true)
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        var state by mutableStateOf(homeState(emptyList(), emptyList()))
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) } }
        composeTestRule.onAllNodes(hasContentDescription("Move", substring = true)).assertCountEquals(0)
        state = homeState(emptyList(), listOf(first))
        composeTestRule.onLazyNode(hasContentDescription("Move First up")).assertIsNotEnabled()
        composeTestRule.onLazyNode(hasContentDescription("Move First down")).assertIsNotEnabled()
        state = homeState(emptyList(), listOf(first, middle, last))
        composeTestRule.onLazyNode(hasContentDescription("Move First up")).assertIsNotEnabled()
        composeTestRule.onLazyNode(hasContentDescription("Move Last down")).assertIsNotEnabled()
        composeTestRule
            .onLazyNode(hasContentDescription("Move Middle up"))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(StartActivityAction.ReorderPinned(listOf(middle.id, first.id, last.id)), actions.single())
        state = state.copy(organizationInFlight = true)
        composeTestRule.onLazyNode(hasContentDescription("Move Middle down")).assertIsNotEnabled()
        state = state.copy(organizationInFlight = false, organizationFailure = "failure")
        composeTestRule.onLazyNode(hasContentDescription("Move First up")).assertIsNotEnabled()
        state = homeState(emptyList(), listOf(last, middle, first))
        composeTestRule.onLazyNode(hasContentDescription("Move Last up")).assertIsNotEnabled()
        composeTestRule
            .onAllNodesWithContentDescription(text(R.string.launcher_drag_handle))[0]
            .performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun quickEditableValueDistinguishesDefaultZeroMissingNegativeAndInvalidWithoutFocusCommit() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        val row = trackable("Quick")
        val field = ActivityLaunchMainValue(ActivityTemplateFieldId("exact-field"), "Quantity", null, 2, 1234)
        var state by mutableStateOf(homeState(listOf(row), emptyList()))
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) } }
        keyboardInput.enter()
        composeTestRule.onNodeWithText("Quick").performSemanticsAction(SemanticsActions.OnClick)
        state = state.copy(selected = LauncherLoad.Content(activityTarget(row.id, field)))
        val input = composeTestRule.onNode(hasText("Quantity") and hasSetTextAction())
        input.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.launcher_value_default)),
        )
        input.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        assertEquals(1, actions.size)
        input.performSemanticsAction(
            SemanticsActions.SetText,
        ) {
            it(
                androidx.compose.ui.text
                    .AnnotatedString("invalid"),
            )
        }
        input.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        composeTestRule.onNodeWithText(text(R.string.launcher_complete)).assertIsNotEnabled()
        for (value in listOf("0", "", "-1.25")) {
            input.performSemanticsAction(
                SemanticsActions.SetText,
            ) {
                it(
                    androidx.compose.ui.text
                        .AnnotatedString(value),
                )
            }
            composeTestRule.onNodeWithText(text(R.string.launcher_complete)).assertIsEnabled()
            val editor = requireNotNull(interaction.quickEditor)
            assertEquals(row.id, editor.targetId)
            assertEquals(field.fieldId, editor.fieldId)
            assertEquals(value, editor.text)
        }
        composeTestRule
            .onNodeWithText(
                text(R.string.launcher_complete),
            ).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(
            StartActivityAction.Launch(QuickMainValueOverride(field.fieldId, QuickMainValue.Number(-1250))),
            actions.last(),
        )
        composeTestRule.runOnIdle {
            interaction.select(row.id)
        }
        composeTestRule.waitUntil { interaction.quickEditor != null }
        composeTestRule.onNodeWithText(text(R.string.launcher_cancel)).performSemanticsAction(SemanticsActions.OnClick)
        state = state.copy(searchQuery = "recomposition")
        composeTestRule.onNodeWithText(text(R.string.launcher_browse_back)).assertIsFocused()
        composeTestRule.runOnIdle {
            assertEquals(1, actions.count { it is StartActivityAction.Launch })
            assertEquals(null, interaction.quickEditor)
            assertEquals(null, interaction.pendingSelectionId)
        }
    }

    @Test
    fun historicalFieldsKeepNativeSetTextAndExactCategoryIdentityWithMissingSelection() {
        val now = Instant.parse("2026-08-20T10:00:00Z")
        val chosen = CategoryOption(CategoryOptionId("chosen-id"), 1, "Chosen")
        val other = CategoryOption(CategoryOptionId("other-id"), 0, "Other")
        val fields =
            listOf(
                ActivityTemplateField(
                    ActivityTemplateFieldId("number-id"),
                    0,
                    "Weight",
                    CustomFieldType.NUMBER,
                    displayPrecision = 2,
                    createdAt = now,
                    updatedAt = now,
                ),
                ActivityTemplateField(
                    ActivityTemplateFieldId("text-id"),
                    1,
                    "Note",
                    CustomFieldType.TEXT,
                    createdAt = now,
                    updatedAt = now,
                ),
                ActivityTemplateField(
                    ActivityTemplateFieldId("category-id"),
                    2,
                    "Mood",
                    CustomFieldType.CATEGORY,
                    defaultCategoryOptionId = chosen.id,
                    createdAt = now,
                    updatedAt = now,
                    categoryOptions = listOf(other, chosen),
                ),
            )
        val draft = optionsTemplate(fields).initialStartOptions(now, ZoneOffset.UTC)
        var state by mutableStateOf(StartActivityState(options = LauncherLoad.Content(draft)))
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) } }
        fields.take(2).forEach { field ->
            val label = composeTestRule.activity.getString(R.string.launcher_field_actual, field.name)
            composeTestRule
                .onLazyNode(hasText(label) and hasSetTextAction())
                .performSemanticsAction(SemanticsActions.SetText) {
                    it(
                        androidx.compose.ui.text
                            .AnnotatedString("0"),
                    )
                }
        }
        assertEquals(
            listOf(
                StartActivityAction.EditOptionNumber(fields[0].id, "0"),
                StartActivityAction.EditOptionText(fields[1].id, "0"),
            ),
            actions,
        )
        composeTestRule
            .onLazyNode(hasText(chosen.label, substring = true) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeTestRule
            .onLazyNode(hasText(other.label) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(StartActivityAction.ChooseOptionCategory(fields[2].id, other.id), actions.last())
        state =
            state.copy(
                options =
                    LauncherLoad.Content(
                        draft.copy(
                            values =
                                draft.values +
                                    (fields[2].id to draft.values.getValue(fields[2].id).copy(missing = true)),
                        ),
                    ),
            )
        composeTestRule
            .onLazyNode(hasText(chosen.label) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        val missing =
            composeTestRule.activity.getString(
                R.string.launcher_field_action,
                "Mood",
                text(R.string.manual_history_restore_value),
            )
        composeTestRule.onLazyNode(hasContentDescription(missing)).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(StartActivityAction.SetOptionMissing(fields[2].id, false), actions.last())
        state =
            state.copy(
                options =
                    LauncherLoad.Content(
                        draft.copy(
                            values =
                                draft.values + (
                                    fields[0].id to
                                        draft.values
                                            .getValue(
                                                fields[0].id,
                                            ).copy(numberText = "invalid", missing = false)
                                ),
                        ),
                    ),
            )
        val numberLabel = composeTestRule.activity.getString(R.string.launcher_field_actual, fields[0].name)
        composeTestRule
            .onLazyNode(hasText(numberLabel) and hasSetTextAction())
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        state = state.copy(command = LauncherCommandState.Committing(1))
        composeTestRule.onLazyNode(hasText(other.label) and hasClickAction()).assertIsNotEnabled()
        composeTestRule
            .onLazyNode(
                hasText(text(R.string.manual_history_save)) and hasClickAction(),
            ).assertIsNotEnabled()
    }

    @Test
    fun bothAmbiguousTimestampsRequireExplicitOffsetSemanticActivation() {
        val zone = ZoneId.of("Europe/Berlin")
        val offsets = zone.rules.getValidOffsets(LocalDateTime.parse("2026-10-25T02:30"))
        val draft =
            optionsTemplate().initialStartOptions(Instant.parse("2026-10-25T10:00:00Z"), zone).copy(
                startedText = "2026-10-25 02:10",
                completedText = "2026-10-25 02:30",
                startedOffset = null,
                completedOffset = null,
                startedOffsets = offsets,
                completedOffsets = offsets,
                draftVersion = 42,
            )
        var state by mutableStateOf(StartActivityState(options = LauncherLoad.Content(draft)))
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) } }
        keyboardInput.enter()
        for ((timestamp, start) in listOf(
            R.string.manual_history_started to true,
            R.string.manual_history_completed to false,
        )) {
            offsets.forEachIndexed { index, offset ->
                val occurrence =
                    composeTestRule.activity.getString(
                        if (index ==
                            0
                        ) {
                            R.string.manual_history_first_occurrence
                        } else {
                            R.string.manual_history_second_occurrence
                        },
                        offset.id,
                    )
                val label =
                    composeTestRule.activity.getString(
                        R.string.launcher_offset_choice,
                        text(timestamp),
                        occurrence,
                    )
                val node = composeTestRule.onLazyNode(hasContentDescription(label))
                node
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
                    .performSemanticsAction(SemanticsActions.RequestFocus)
                    .assertIsFocused()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
                assertEquals(if (start) index else index + 2, actions.size)
                node.performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(
                    if (start) {
                        StartActivityAction.ChooseOptionStartOffset(
                            offset,
                        )
                    } else {
                        StartActivityAction.ChooseOptionEndOffset(offset)
                    },
                    actions.last(),
                )
            }
        }
        assertEquals(42L, (state.options as LauncherLoad.Content).value.draftVersion)
        state =
            state.copy(
                options = LauncherLoad.Content(draft.copy(startedOffset = offsets[1], completedOffset = offsets[0])),
            )
        val startSecond =
            composeTestRule.activity.getString(
                R.string.launcher_offset_choice,
                text(R.string.manual_history_started),
                composeTestRule.activity.getString(R.string.manual_history_second_occurrence, offsets[1].id),
            )
        composeTestRule
            .onLazyNode(hasContentDescription(startSecond))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        state = state.copy(command = LauncherCommandState.Checking(1))
        composeTestRule.onLazyNode(hasContentDescription(startSecond)).assertIsNotEnabled()
    }

    @Test
    fun commandFeedbackIsDistinctAndPreflightTicksHaveStableNoninteractiveSemantics() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        var state by mutableStateOf(homeState(emptyList(), emptyList()))
        composeTestRule.setContent { LifeTracingTheme { StartActivityScreen(state, actions::add, interaction) } }
        val result =
            LauncherCommit.Activity(
                com.alexandr5476.lifetracing.domain
                    .ActivityExecutionId("persisted"),
                true,
            )
        for ((command, label) in listOf(
            LauncherCommandState.Checking(1) to R.string.launcher_checking,
            LauncherCommandState.Committing(1) to R.string.launcher_committing,
            LauncherCommandState.Conflict("busy") to R.string.launcher_conflict,
            LauncherCommandState.Rejected("invalid") to R.string.launcher_rejected,
            LauncherCommandState.Committed(result) to R.string.launcher_committed,
            LauncherCommandState.CommittedCoordinationFailure(result, "coordination") to
                R.string.launcher_committed_coordination,
        )) {
            state = state.copy(command = command)
            composeTestRule
                .onLazyNode(hasText(text(label)))
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.LiveRegion,
                        androidx.compose.ui.semantics.LiveRegionMode.Polite,
                    ),
                )
            composeTestRule.onNodeWithText(text(R.string.launcher_cancel)).assertDoesNotExist()
        }
        val at = Instant.now()
        state =
            state.copy(
                command =
                    LauncherCommandState.Preflight(
                        7,
                        LibraryTemplateId.Activity(ActivityTemplateId("preflight")),
                        Duration.ofSeconds(30),
                        at,
                        at.plusSeconds(30),
                    ),
            )
        val progress =
            composeTestRule.onNodeWithContentDescription(
                composeTestRule.activity.getString(R.string.launcher_preflight_progress, "0:30"),
            )
        val before = progress.fetchSemanticsNode().config
        composeTestRule.mainClock.advanceTimeBy(1_000)
        progress
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        assertEquals(
            before[SemanticsProperties.ProgressBarRangeInfo],
            progress.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo],
        )
        assertEquals(emptyList<StartActivityAction>(), actions)
        composeTestRule.onNodeWithText(text(R.string.launcher_cancel)).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(StartActivityAction.CancelPreflight), actions)
    }

    @Test
    fun nativeBackAndToolbarBackShareFolderAndNestedEditorContext() {
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        var exits = 0
        val folder = Folder(FolderId("nested"), "Nested", null, Instant.EPOCH, Instant.EPOCH)
        interaction.openFolder(folder)
        var state by mutableStateOf(homeState(emptyList(), emptyList()))
        composeTestRule.setContent {
            LifeTracingTheme {
                StartActivityScreen(
                    state,
                    actions::add,
                    interaction,
                ) { exits++ }
            }
        }
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(StartActivityAction.Browse(null), actions.single())
        assertEquals(0, exits)
        state = state.copy(oneOff = OneOffDraft())
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(StartActivityAction.CloseOneOff, actions.last())
        state = state.copy(oneOff = null, options = LauncherLoad.Loading)
        composeTestRule
            .onLazyNode(hasText(text(R.string.launcher_browse_back)) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(StartActivityAction.CloseOptions, actions.last())
        assertEquals(0, exits)
    }

    @Test
    fun englishRussianLabelsAndIndependentActionsReflowAtCombinedScaleExtremes() {
        val locale = mutableStateOf(java.util.Locale.US)
        val font = mutableStateOf(2f)
        val appearance =
            mutableStateOf(
                com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences(
                    interfaceScalePercent = 120,
                    textScalePercent = 130,
                ),
            )
        val row = trackable("Scaled Activity")
        var state by mutableStateOf(homeState(listOf(row), emptyList()))
        val actions = mutableListOf<StartActivityAction>()
        val interaction = StartActivityRouteInteraction()
        composeTestRule.setContent {
            val config =
                android.content.res.Configuration(composeTestRule.activity.resources.configuration).apply {
                    setLocale(locale.value)
                    fontScale = font.value
                }
            val context = composeTestRule.activity.createConfigurationContext(config)
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides context,
                androidx.compose.ui.platform.LocalConfiguration provides config,
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit
                        .Density(density.density, config.fontScale),
            ) {
                com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance(appearance.value) {
                    StartActivityScreen(state, actions::add, interaction)
                }
            }
        }
        keyboardInput.enter()
        for (language in listOf(java.util.Locale.US, java.util.Locale.forLanguageTag("ru"))) {
            for (small in listOf(false, true)) {
                composeTestRule.runOnIdle {
                    locale.value = language
                    font.value = if (small) 1f else 2f
                    appearance.value =
                        com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences(
                            interfaceScalePercent = if (small) 90 else 120,
                            textScalePercent = if (small) 90 else 130,
                        )
                    state = homeState(listOf(row), emptyList())
                }

                fun label(
                    id: Int,
                    vararg args: Any,
                ): String {
                    val config = android.content.res.Configuration(composeTestRule.activity.resources.configuration)
                    config.setLocale(language)
                    return composeTestRule.activity.createConfigurationContext(config).getString(id, *args)
                }
                val primary = composeTestRule.onLazyNode(hasText(row.name) and hasClickAction())
                primary.assertIsDisplayed()
                val primaryBounds = primary.fetchSemanticsNode().touchBoundsInRoot
                val options =
                    composeTestRule.onLazyNode(
                        hasContentDescription(label(R.string.launcher_options_for, row.name)),
                    )
                options.assertIsDisplayed()
                val optionsBounds = options.fetchSemanticsNode().touchBoundsInRoot
                val minimum = 48 * composeTestRule.activity.resources.displayMetrics.density
                assertTrue(primaryBounds.height >= minimum - 1)
                assertTrue(optionsBounds.height >= minimum - 1)
                assertSeparateTargets(primary, options)
                options.performSemanticsAction(SemanticsActions.RequestFocus)
                options.assertIsFocused()
                options.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
                assertEquals(StartActivityAction.OpenOptions(ActivityTemplateId(row.id.value)), actions.last())
                state = state.copy(oneOff = OneOffDraft(name = "Scaled one-off"))
                val mode = composeTestRule.onLazyNode(hasText(label(R.string.launcher_one_off_no_live)))
                mode.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(StartActivityAction.EditOneOffMode(TimeTrackingMode.NO_LIVE_TRACKING), actions.last())
                state =
                    state.copy(oneOff = OneOffDraft(name = "Scaled one-off", mode = TimeTrackingMode.NO_LIVE_TRACKING))
                composeTestRule
                    .onLazyNode(hasText(label(R.string.launcher_complete)) and hasClickAction())
                    .assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(StartActivityAction.ExecuteOneOff, actions.last())
                state = homeState(listOf(row), emptyList())
                composeTestRule
                    .onLazyNode(hasText(row.name) and hasClickAction())
                    .performSemanticsAction(SemanticsActions.OnClick)
                val value = ActivityLaunchMainValue(ActivityTemplateFieldId("scaled-value"), "Quantity", null, 2, 1234)
                state = state.copy(selected = LauncherLoad.Content(activityTarget(row.id, value)))
                val complete =
                    composeTestRule.onLazyNode(
                        hasText(label(R.string.launcher_complete)) and hasClickAction(),
                    )
                val cancel = composeTestRule.onLazyNode(hasText(label(R.string.launcher_cancel)) and hasClickAction())
                assertSeparateTargets(complete, cancel)
                cancel.performSemanticsAction(SemanticsActions.OnClick)
                composeTestRule.onNodeWithText(label(R.string.launcher_browse_back)).assertIsFocused()

                val zone = ZoneId.of("Europe/Berlin")
                val now = Instant.parse("2026-11-01T12:00:00Z")
                val offsets = zone.rules.getValidOffsets(LocalDateTime.parse("2026-10-25T02:10"))
                val draft =
                    optionsTemplate().initialStartOptions(now, zone).copy(
                        startedText = "2026-10-25 02:10",
                        startedOffsets = offsets,
                        startedOffset = null,
                    )
                state = homeState(emptyList(), emptyList()).copy(options = LauncherLoad.Content(draft))

                fun offsetLabel(index: Int): String {
                    val occurrence =
                        if (index == 0) {
                            R.string.manual_history_first_occurrence
                        } else {
                            R.string.manual_history_second_occurrence
                        }
                    return label(
                        R.string.launcher_offset_choice,
                        label(R.string.manual_history_started),
                        label(occurrence, offsets[index].id),
                    )
                }
                val firstOffset = composeTestRule.onLazyNode(hasContentDescription(offsetLabel(0)))
                val secondOffset = composeTestRule.onLazyNode(hasContentDescription(offsetLabel(1)))
                assertSeparateTargets(firstOffset, secondOffset)
                secondOffset.performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(StartActivityAction.ChooseOptionStartOffset(offsets[1]), actions.last())
                val save = composeTestRule.onLazyNode(hasText(label(R.string.manual_history_save)) and hasClickAction())
                val close = composeTestRule.onLazyNode(hasText(label(R.string.launcher_cancel)) and hasClickAction())
                assertSeparateTargets(save, close)
                val reviewed = draft.copy(startedOffset = offsets[1], completedText = "2026-10-25 03:00")
                val proposal = (reviewed.validate(now, zone) as StartOptionsValidation.Valid).proposal
                state = state.copy(options = LauncherLoad.Content(reviewed.copy(overlap = proposal)))
                val proceed =
                    composeTestRule.onLazyNode(
                        hasText(label(R.string.manual_history_proceed)) and hasClickAction(),
                    )
                val cancelOverlap =
                    composeTestRule.onLazyNode(
                        hasText(label(R.string.launcher_cancel_overlap)) and hasClickAction(),
                    )
                assertSeparateTargets(proceed, cancelOverlap)
                proceed.performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(StartActivityAction.ConfirmOptionsOverlap, actions.last())
                state = homeState(emptyList(), listOf(row, trackable("Scaled Sequence", sequence = true)))
                val up = composeTestRule.onLazyNode(hasContentDescription(label(R.string.launcher_move_up, row.name)))
                val down =
                    composeTestRule.onLazyNode(
                        hasContentDescription(label(R.string.launcher_move_down, row.name)),
                    )
                up.assertIsNotEnabled()
                assertSeparateTargets(up, down)
                down.performSemanticsAction(SemanticsActions.OnClick)
                val sequenceId = LibraryTemplateId.Sequence(SequenceTemplateId("Scaled Sequence"))
                assertEquals(StartActivityAction.ReorderPinned(listOf(sequenceId, row.id)), actions.last())
            }
        }
    }

    private fun assertSeparateTargets(
        first: androidx.compose.ui.test.SemanticsNodeInteraction,
        second: androidx.compose.ui.test.SemanticsNodeInteraction,
    ) {
        val minimum = 48 * composeTestRule.activity.resources.displayMetrics.density
        for (target in listOf(first, second)) {
            target.performScrollTo().assertIsDisplayed()
            val touch = target.fetchSemanticsNode().touchBoundsInRoot
            assertTrue(touch.height >= minimum - 1)
            assertTrue(touch.width >= minimum - 1)
        }
        val a = first.getUnclippedBoundsInRoot()
        val b = second.getUnclippedBoundsInRoot()
        assertTrue(
            "Interactive targets must not overlap",
            a.bottom <= b.top || b.bottom <= a.top || a.right <= b.left || b.right <= a.left,
        )
    }

    private fun optionsTemplate(fields: List<ActivityTemplateField> = emptyList()) =
        ActivityTemplate(
            id = ActivityTemplateId("options-activity"),
            name = "Options activity",
            shortComment = null,
            timeTrackingMode = TimeTrackingMode.STOPWATCH,
            timerTarget = null,
            statisticsSeriesId = StatisticsSeriesId("series"),
            createdAt = Instant.parse("2026-08-20T10:00:00Z"),
            updatedAt = Instant.parse("2026-08-20T10:00:00Z"),
            fields = fields,
        )

    private fun homeState(
        recent: List<LibraryTrackable>,
        pinned: List<LibraryTrackable> = listOf(trackable("Pinned Sequence", sequence = true)),
    ) = StartActivityState(
        home = LauncherLoad.Content(LauncherHome(recent, pinned)),
    )

    private fun trackable(
        name: String,
        sequence: Boolean = false,
    ): LibraryTrackable {
        val id =
            if (sequence) {
                LibraryTemplateId.Sequence(
                    com.alexandr5476.lifetracing.domain
                        .SequenceTemplateId(name),
                )
            } else {
                LibraryTemplateId.Activity(ActivityTemplateId(name))
            }
        return LibraryTrackable(id, name, null, null, emptySet(), null, null, null)
    }

    private fun activityTarget(
        id: LibraryTemplateId,
        mainValue: ActivityLaunchMainValue? = null,
        mode: TimeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING,
    ) = LibraryLaunchTarget.Activity(
        id as LibraryTemplateId.Activity,
        id.value,
        Duration.ZERO,
        mode,
        mainValue,
    )

    private fun sequenceTarget(id: LibraryTemplateId) =
        LibraryLaunchTarget.Sequence(id as LibraryTemplateId.Sequence, id.value, Duration.ZERO)

    private fun emptyContents() = LibraryContents(emptyList(), emptyList(), emptyList())

    private fun text(id: Int): String = composeTestRule.activity.getString(id)
}
