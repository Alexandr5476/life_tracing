package com.alexandr5476.lifetracing.launcher

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.alexandr5476.lifetracing.domain.ActivityLaunchMainValue
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant

class StartActivityScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

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

        composeTestRule.onNodeWithText(text(com.alexandr5476.lifetracing.R.string.launcher_loading)).assertIsDisplayed()
        state = state.copy(home = LauncherLoad.Failure("failed"))
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_home_failure),
            ).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(com.alexandr5476.lifetracing.R.string.launcher_retry)).performClick()
        assertEquals(StartActivityAction.Retry, actions.last())

        state = state.copy(home = LauncherLoad.Content(LauncherHome(emptyList(), emptyList())))
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_recent_empty),
            ).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_pinned_empty),
            ).assertIsDisplayed()

        val activity = trackable("Mixed activity")
        val sequence = trackable("Mixed sequence", sequence = true)
        state =
            state.copy(
                home = LauncherLoad.Content(LauncherHome(listOf(activity, sequence), emptyList())),
                search = LauncherLoad.Loading,
                browse = LauncherLoad.Loading,
            )
        composeTestRule.onNodeWithText("Mixed activity").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mixed sequence").assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_activity),
            ).assertCountEquals(1)
        composeTestRule
            .onAllNodesWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_sequence),
            ).assertCountEquals(1)
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_recent_empty),
            ).assertDoesNotExist()
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_pinned_empty),
            ).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_search_loading),
            ).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_browse_loading),
            ).assertIsDisplayed()

        state = state.copy(search = LauncherLoad.Content(listOf(trackable("Search result"))))
        composeTestRule.onNodeWithText("Search result").assertIsDisplayed()
        state = state.copy(search = LauncherLoad.Content(emptyList()), browse = LauncherLoad.Content(emptyContents()))
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_search_empty),
            ).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_browse_empty),
            ).assertIsDisplayed()

        state = state.copy(search = LauncherLoad.Failure("search"), browse = LauncherLoad.Failure("browse"))
        composeTestRule
            .onNodeWithText(
                text(com.alexandr5476.lifetracing.R.string.launcher_search_failure),
            ).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(text(com.alexandr5476.lifetracing.R.string.launcher_browse_failure))
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
