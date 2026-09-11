package com.alexandr5476.lifetracing.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateAuthoringState
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
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

class SequenceTemplateEditorScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compactRowsKeepPendingAndLocalStepsIdentifiable() {
        val choices =
            listOf(
                choice("bench", "Bench press", TimeTrackingMode.STOPWATCH),
                choice(
                    "pushups",
                    "Push-ups",
                    TimeTrackingMode.STOPWATCH,
                    mainValue = "Reps",
                    unit = "reps",
                    value = 15_000,
                ),
                choice("rest", "Rest", TimeTrackingMode.TIMER, Duration.ofSeconds(90)),
                choice("stretch", "Stretch", TimeTrackingMode.NO_LIVE_TRACKING),
                choice(
                    "read",
                    "Read",
                    TimeTrackingMode.NO_LIVE_TRACKING,
                    mainValue = "Pages",
                    unit = "pages",
                    value = 20_000,
                ),
                choice("count", "Count", TimeTrackingMode.NO_LIVE_TRACKING, mainValue = "Reps", unit = "reps"),
            )
        val controller = controller(choices)
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)

        composeTestRule.runOnIdle {
            controller.updateDraft {
                it.copy(
                    nodes =
                        listOf(
                            step(
                                "bench-step",
                                0,
                                StepActivityDraft.FromTemplate(ActivityTemplateId("bench")),
                            ),
                        ),
                )
            }
        }
        composeTestRule.onNodeWithText("Bench press").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stopwatch").assertIsDisplayed()

        showPending(controller, "pushups", "pushups-step")
        composeTestRule.onNodeWithText("Push-ups").assertIsDisplayed()
        composeTestRule.onNodeWithText("15 reps").assertIsDisplayed()

        composeTestRule.runOnIdle {
            controller.updateDraft {
                it.copy(
                    nodes =
                        listOf(
                            step(
                                "rest-step",
                                0,
                                StepActivityDraft.FromTemplate(ActivityTemplateId("rest")),
                            ),
                        ),
                )
            }
        }
        composeTestRule.onNodeWithText("Rest").assertIsDisplayed()
        composeTestRule.onNodeWithText("1:30").assertIsDisplayed()

        showPending(controller, "stretch", "stretch-step")
        composeTestRule.onNodeWithText("Stretch").assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.sequence_editor_complete_only)).assertIsDisplayed()

        showPending(controller, "read", "read-step")
        composeTestRule.onNodeWithText("Read").assertIsDisplayed()
        composeTestRule.onNodeWithText("20 pages").assertIsDisplayed()

        showPending(controller, "count", "count-step")
        composeTestRule.onNodeWithText("Count").assertIsDisplayed()
        composeTestRule.onNodeWithText("reps").assertIsDisplayed()
        composeTestRule.onNodeWithText("0 reps").assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.sequence_editor_edit_step)).performClick()
        composeTestRule
            .onNodeWithText(text(R.string.sequence_editor_pending_source_configuration))
            .assertIsDisplayed()
        assertEquals(
            3,
            composeTestRule
                .onAllNodes(hasText(text(R.string.sequence_editor_inherit)) and isSelected())
                .fetchSemanticsNodes()
                .size,
        )

        composeTestRule.runOnIdle {
            controller.updateDraft {
                it.copy(
                    nodes =
                        listOf(
                            step(
                                "frozen-main",
                                0,
                                StepActivityDraft.Existing(
                                    ActivitySnapshotId("frozen-main-snapshot"),
                                    ActivitySnapshotDraft(
                                        "Frozen push-ups",
                                        null,
                                        TimeTrackingMode.STOPWATCH,
                                        null,
                                        fields =
                                            listOf(
                                                ActivitySnapshotFieldDraft(
                                                    DraftIdentity.Existing(ActivitySnapshotFieldId("frozen-reps")),
                                                    null,
                                                    0,
                                                    "Reps",
                                                    type = CustomFieldType.NUMBER,
                                                    unit = "reps",
                                                    displayPrecision = 0,
                                                    defaultNumberScaled = 18_000,
                                                    isMainValue = true,
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        ),
                )
            }
        }
        composeTestRule.onNodeWithText("Frozen push-ups").assertIsDisplayed()
        composeTestRule.onNodeWithText("18 reps").assertIsDisplayed()

        composeTestRule.runOnIdle {
            controller.updateDraft {
                it.copy(
                    nodes =
                        listOf(
                            step(
                                "existing-step",
                                0,
                                StepActivityDraft.Existing(
                                    ActivitySnapshotId("frozen"),
                                    ActivitySnapshotDraft(
                                        "Frozen stretch",
                                        null,
                                        TimeTrackingMode.NO_LIVE_TRACKING,
                                        null,
                                    ),
                                ),
                            ),
                        ),
                )
            }
        }
        composeTestRule.onNodeWithText("Frozen stretch").assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.sequence_editor_complete_only)).assertIsDisplayed()

        composeTestRule.runOnIdle {
            controller.updateDraft {
                it.copy(
                    nodes =
                        listOf(
                            step(
                                "local-step",
                                0,
                                StepActivityDraft.Local(
                                    ActivitySnapshotDraft(
                                        "Local stretch",
                                        null,
                                        TimeTrackingMode.NO_LIVE_TRACKING,
                                        null,
                                    ),
                                ),
                            ),
                        ),
                )
            }
        }
        composeTestRule.onNodeWithText("Local stretch").assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.sequence_editor_complete_only)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.sequence_editor_linked_step)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.sequence_editor_local_step)).assertDoesNotExist()
        controller.close()
    }

    @Test
    fun oneCompactPickerServesTopLevelAndDoesNotRenderItsCatalogUntilOpened() {
        val choice = choice("foldered", "Foldered activity", TimeTrackingMode.TIMER, Duration.ofSeconds(45))
        val controller = controller(listOf(choice))
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)

        composeTestRule.onNodeWithText("Foldered activity").assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.sequence_editor_add_step)).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Foldered activity").assertIsDisplayed().performClick()

        composeTestRule.runOnIdle {
            val activity =
                (
                    controller.state.value
                        .readyDraft()!!
                        .nodes
                        .single() as SequenceNodeDraft.Step
                ).value
                    .activity
            assertEquals(StepActivityDraft.FromTemplate(ActivityTemplateId("foldered")), activity)
        }
        composeTestRule.onNodeWithText("Foldered activity").performScrollTo().assertIsDisplayed()
        controller.close()
    }

    @Test
    fun settingsAreDisclosedAndInvalidCountdownRemainsVisible() {
        val controller = controller(emptyList())
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)

        val countdown = text(R.string.sequence_editor_start_countdown)
        composeTestRule.onNode(hasText(countdown) and hasSetTextAction()).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.sequence_editor_settings)).performClick()
        composeTestRule
            .onNode(hasText(countdown) and hasSetTextAction())
            .performTextReplacement("-1")
        composeTestRule.onNodeWithText("-1").assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.sequence_editor_invalid_number)).assertIsDisplayed()
        assertTrue(controller.state.value.inputIsInvalid(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN))
        controller.close()
    }

    @Test
    fun largeRepeatChildrenRemainLazy() {
        val choices = List(200) { choice("activity-$it", "Activity $it", TimeTrackingMode.STOPWATCH) }
        val controller = controller(choices)
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)
        composeTestRule.runOnIdle {
            controller.updateDraft {
                it.copy(
                    nodes =
                        listOf(
                            SequenceNodeDraft.Repeat(
                                SequenceRepeatBlockDraft(
                                    DraftIdentity.New("repeat"),
                                    0,
                                    2,
                                    choices.mapIndexed { index, choice ->
                                        ActivityStepDraft(
                                            DraftIdentity.New("step-$index"),
                                            index,
                                            StepActivityDraft.FromTemplate(choice.id),
                                        )
                                    },
                                ),
                            ),
                        ),
                )
            }
        }

        composeTestRule.onNodeWithText("Activity 199").assertDoesNotExist()
        composeTestRule.onNodeWithText("Repeat ×2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Inside Repeat ×2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add step to Repeat ×2").performScrollTo().assertIsDisplayed()
        controller.close()
    }

    @Test
    fun topLevelAndRepeatAddStepShareTheBoundedPicker() {
        val choice = choice("shared", "Shared activity", TimeTrackingMode.STOPWATCH)
        val controller = controller(listOf(choice))
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)
        composeTestRule.runOnIdle {
            controller.updateDraft {
                it.copy(
                    nodes =
                        listOf(
                            SequenceNodeDraft.Repeat(
                                SequenceRepeatBlockDraft(DraftIdentity.New("repeat"), 0, 3, emptyList()),
                            ),
                        ),
                )
            }
        }

        composeTestRule.onNodeWithText("Shared activity").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add step to Repeat ×3").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Shared activity").assertIsDisplayed().performClick()
        composeTestRule.runOnIdle {
            val repeat =
                controller.state.value
                    .readyDraft()!!
                    .nodes
                    .single() as SequenceNodeDraft.Repeat
            assertEquals(
                StepActivityDraft.FromTemplate(choice.id),
                repeat.value.children
                    .single()
                    .activity,
            )
        }
        controller.close()
    }

    @Test
    fun finiteChoicesExposeTheirCurrentSelectedValue() {
        val controller = controller(emptyList())
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)
        composeTestRule.runOnIdle {
            controller.updateDraft {
                it.copy(
                    fields =
                        listOf(
                            SequenceFieldDraft(
                                DraftIdentity.New("sequence-category"),
                                0,
                                "Mood",
                                CustomFieldType.CATEGORY,
                                defaultCategoryOption = DraftIdentity.New("calm"),
                                categoryOptions =
                                    listOf(
                                        SequenceCategoryOptionDraft(DraftIdentity.New("calm"), 0, "Calm"),
                                    ),
                            ),
                        ),
                    nodes =
                        listOf(
                            step(
                                "timer-step",
                                0,
                                StepActivityDraft.Local(
                                    ActivitySnapshotDraft(
                                        "Intervals",
                                        null,
                                        TimeTrackingMode.TIMER,
                                        Duration.ofSeconds(30),
                                        settings =
                                            com.alexandr5476.lifetracing.domain.ActivityTemplateSettings(
                                                timerZeroBehavior = TimerZeroBehavior.OVERTIME,
                                            ),
                                        fields =
                                            listOf(
                                                ActivitySnapshotFieldDraft(
                                                    DraftIdentity.New("local-text"),
                                                    null,
                                                    0,
                                                    "Notes",
                                                    type = CustomFieldType.TEXT,
                                                ),
                                                ActivitySnapshotFieldDraft(
                                                    DraftIdentity.New("local-category"),
                                                    null,
                                                    1,
                                                    "Effort",
                                                    type = CustomFieldType.CATEGORY,
                                                    categoryOptions =
                                                        listOf(
                                                            ActivitySnapshotCategoryOptionDraft(
                                                                DraftIdentity.New("easy"),
                                                                null,
                                                                0,
                                                                "Easy",
                                                            ),
                                                        ),
                                                ),
                                            ),
                                    ),
                                ),
                            ).let { node ->
                                SequenceNodeDraft.Step(
                                    node.value.copy(
                                        overrides =
                                            SequenceStepOverrides(
                                                timerZeroBehavior = TimerZeroBehavior.FINISH,
                                                timerEndSound = false,
                                                timerEndVibration = true,
                                                keepScreenAwake = null,
                                            ),
                                    ),
                                )
                            },
                        ),
                )
            }
        }

        composeTestRule.onNode(hasText("Category") and isSelected()).assertIsSelected()
        composeTestRule
            .onNode(hasText(text(R.string.activity_editor_default)) and isSelected())
            .assertIsSelected()
        composeTestRule.runOnIdle { controller.updateDraft { it.copy(fields = emptyList()) } }
        composeTestRule.onNodeWithText(text(R.string.sequence_editor_edit_step)).performScrollTo().performClick()
        composeTestRule
            .onNode(hasText(text(R.string.sequence_editor_no_default)) and isSelected())
            .assertIsSelected()
        composeTestRule.onNode(hasText("Timer") and isSelected()).performScrollTo().assertIsSelected()
        composeTestRule
            .onNode(hasText(text(R.string.sequence_editor_timer_zero_overtime)) and isSelected())
            .performScrollTo()
            .assertIsSelected()
        composeTestRule.onNode(hasText("Text") and isSelected()).performScrollTo().assertIsSelected()
        composeTestRule
            .onNode(hasText(text(R.string.sequence_editor_timer_zero_finish)) and isSelected())
            .performScrollTo()
            .assertIsSelected()
        assertTrue(
            composeTestRule
                .onAllNodes(
                    hasText(text(R.string.sequence_editor_off)) and isSelected(),
                ).fetchSemanticsNodes()
                .isNotEmpty(),
        )
        assertTrue(
            composeTestRule
                .onAllNodes(
                    hasText(text(R.string.sequence_editor_on)) and isSelected(),
                ).fetchSemanticsNodes()
                .isNotEmpty(),
        )
        assertTrue(
            composeTestRule
                .onAllNodes(
                    hasText(text(R.string.sequence_editor_inherit)) and isSelected(),
                ).fetchSemanticsNodes()
                .isNotEmpty(),
        )
        controller.close()
    }

    @Test
    fun longPressShowsAccessibleHandlesAndPointerDragMovesTopLevelStepIntoRepeat() {
        val firstSnapshot = ActivitySnapshotId("first-snapshot")
        val childSnapshot = ActivitySnapshotId("child-snapshot")
        val authoring =
            SequenceTemplateAuthoringState(
                SequenceTemplate(
                    SequenceTemplateId("existing"),
                    "Workout",
                    null,
                    StatisticsSeriesId("sequence-series"),
                    revision = 4,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    nodes =
                        listOf(
                            ActivityStep(SequenceNodeId("first"), 0, firstSnapshot),
                            SequenceRepeatBlock(
                                SequenceNodeId("repeat"),
                                1,
                                2,
                                listOf(ActivityStep(SequenceNodeId("child"), 0, childSnapshot)),
                            ),
                        ),
                ),
                mapOf(
                    firstSnapshot to snapshot(firstSnapshot, "Warmup"),
                    childSnapshot to snapshot(childSnapshot, "Rest"),
                ),
            )
        val controller =
            SequenceTemplateEditorController(
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                SequenceTemplateEditorTarget.Existing(authoring.sequence.id),
                { authoring },
                { emptyList() },
                { _, _, _ -> error("Create is not used") },
                { _, _, _, _ -> error("Save is not used") },
                Instant::now,
            )
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)

        composeTestRule.onNodeWithText("Warmup").performTouchInput { longClick() }

        composeTestRule.onNode(hasText("Warmup") and isSelected()).assertIsSelected()
        assertEquals(
            1,
            composeTestRule
                .onAllNodesWithContentDescription(text(R.string.sequence_editor_move_step))
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            1,
            composeTestRule
                .onAllNodesWithContentDescription(text(R.string.sequence_editor_duplicate_step))
                .fetchSemanticsNodes()
                .size,
        )
        composeTestRule.onNodeWithContentDescription(text(R.string.sequence_editor_move_repeat)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.sequence_editor_apply)).assertIsDisplayed()

        drag(
            moveTag("first"),
            dropTarget(repeatDropTag("repeat")),
        )
        composeTestRule.runOnIdle {
            val draft = requireNotNull(controller.state.value.readyDraft())
            assertTrue(draft.nodes.first() is SequenceNodeDraft.Repeat)
            assertEquals(2, (draft.nodes.first() as SequenceNodeDraft.Repeat).value.children.size)
            assertEquals(
                1,
                controller.state.value.manipulation
                    ?.operationCount,
            )
        }
        controller.close()
    }

    @Test
    fun pointerDragReordersTopLevelAndSelectsTheDraggedRow() {
        val controller =
            existingController(
                pointerAuthoring(
                    ActivityStep(SequenceNodeId("a"), 0, ActivitySnapshotId("snapshot-a")),
                    ActivityStep(SequenceNodeId("b"), 1, ActivitySnapshotId("snapshot-b")),
                ),
            )
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)
        composeTestRule.onNodeWithText("Top A").performScrollTo().performTouchInput { longClick() }

        drag(moveTag("b"), dropTarget(stepDropTag("a")), 0.1f)

        composeTestRule.runOnIdle {
            val draft = requireNotNull(controller.state.value.readyDraft())
            assertEquals(listOf("b", "a"), draft.nodes.map { it.identity.existingValue() })
            assertEquals(
                DraftIdentity.Existing(SequenceNodeId("b")),
                controller.state.value.manipulation
                    ?.selected,
            )
            assertEquals(
                1,
                controller.state.value.manipulation
                    ?.operationCount,
            )
        }
        controller.close()
    }

    @Test
    fun pointerDragMovesRepeatChildToTopLevel() {
        val controller =
            existingController(
                pointerAuthoring(
                    SequenceRepeatBlock(
                        SequenceNodeId("r1"),
                        0,
                        2,
                        listOf(ActivityStep(SequenceNodeId("c"), 0, ActivitySnapshotId("snapshot-c"))),
                    ),
                    ActivityStep(SequenceNodeId("a"), 1, ActivitySnapshotId("snapshot-a")),
                ),
            )
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)
        composeTestRule.onNodeWithText("Child C").performScrollTo().performTouchInput { longClick() }

        drag(moveTag("c"), dropTarget(repeatDropTag("r1")), 0.1f)
        composeTestRule.runOnIdle {
            val draft = requireNotNull(controller.state.value.readyDraft())
            assertEquals(
                "c",
                draft.nodes
                    .first()
                    .identity
                    .existingValue(),
            )
            assertEquals(
                1,
                controller.state.value.manipulation
                    ?.operationCount,
            )
        }
        controller.close()
    }

    @Test
    fun pointerDragMovesStepFromOneRepeatToAnotherEmptyRepeat() {
        val controller =
            existingController(
                pointerAuthoring(
                    SequenceRepeatBlock(
                        SequenceNodeId("r1"),
                        0,
                        2,
                        listOf(ActivityStep(SequenceNodeId("c"), 0, ActivitySnapshotId("snapshot-c"))),
                    ),
                    SequenceRepeatBlock(SequenceNodeId("r2"), 1, 3, emptyList()),
                ),
            )
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)
        composeTestRule.onNodeWithText("Child C").performScrollTo().performTouchInput { longClick() }
        autoScrollDrag(moveTag("c"))
        composeTestRule.runOnIdle {
            val draft = requireNotNull(controller.state.value.readyDraft())
            assertEquals(
                1,
                controller.state.value.manipulation
                    ?.operationCount,
            )
            val secondRepeat =
                draft.nodes.filterIsInstance<SequenceNodeDraft.Repeat>().single {
                    it.identity.existingValue() ==
                        "r2"
                }
            val placements =
                draft.nodes.joinToString { node ->
                    when (node) {
                        is SequenceNodeDraft.Step -> "top:${node.identity.existingValue()}"
                        is SequenceNodeDraft.Repeat -> {
                            val children = node.value.children.joinToString { it.identity.existingValue() }
                            "${node.identity.existingValue()}:$children"
                        }
                    }
                }
            assertEquals(placements, listOf("c"), secondRepeat.value.children.map { it.identity.existingValue() })
        }
        controller.close()
    }

    @Test
    fun pointerDuplicateMovesDirectlyAcrossContainersAsOneOperation() {
        val controller =
            existingController(
                pointerAuthoring(
                    ActivityStep(SequenceNodeId("a"), 0, ActivitySnapshotId("snapshot-a")),
                    SequenceRepeatBlock(SequenceNodeId("r2"), 1, 3, emptyList()),
                ),
            )
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)
        composeTestRule.onNodeWithText("Top A").performScrollTo().performTouchInput { longClick() }

        drag(
            duplicateTag("a"),
            dropTarget(repeatDropTag("r2")),
        )

        composeTestRule.runOnIdle {
            val draft = requireNotNull(controller.state.value.readyDraft())
            val secondRepeat =
                draft.nodes.filterIsInstance<SequenceNodeDraft.Repeat>().single {
                    it.identity.existingValue() ==
                        "r2"
                }
            val duplicate = secondRepeat.value.children.first()
            assertEquals(StepActivityDraft.Duplicate(SequenceNodeId("a")), duplicate.activity)
            assertEquals(
                1,
                controller.state.value.manipulation
                    ?.operationCount,
            )
            assertEquals(1, draft.nodes.flatMap { it.stepsForTest() }.count { it.identity is DraftIdentity.Existing })
        }
        controller.close()
    }

    @Test
    fun cancelledDragAddsNoOperationAndRepeatHasMoveOnly() {
        val controller =
            existingController(
                pointerAuthoring(
                    ActivityStep(SequenceNodeId("a"), 0, ActivitySnapshotId("snapshot-a")),
                    SequenceRepeatBlock(
                        SequenceNodeId("r1"),
                        1,
                        2,
                        listOf(ActivityStep(SequenceNodeId("c"), 0, ActivitySnapshotId("snapshot-c"))),
                    ),
                ),
            )
        composeTestRule.setContent { LifeTracingTheme { SequenceTemplateEditorRoute(controller) {} } }
        awaitReady(controller)
        composeTestRule.onNodeWithText("Top A").performScrollTo().performTouchInput { longClick() }

        composeTestRule.onNodeWithTag(moveTag("r1"), useUnmergedTree = true).performTouchInput {
            down(center)
            moveTo(center.copy(y = center.y - 200f))
            cancel()
        }
        composeTestRule.onNodeWithTag(moveTag("r1"), useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(duplicateTag("r1"), useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.runOnIdle {
            assertEquals(
                0,
                controller.state.value.manipulation
                    ?.operationCount,
            )
        }

        drag(moveTag("r1"), dropTarget(stepDropTag("a")), 0.1f)
        composeTestRule.runOnIdle {
            val draft = requireNotNull(controller.state.value.readyDraft())
            assertEquals(
                "r1",
                draft.nodes
                    .first()
                    .identity
                    .existingValue(),
            )
            assertEquals(
                1,
                controller.state.value.manipulation
                    ?.operationCount,
            )
        }
        controller.close()
    }

    private fun controller(choices: List<SequenceEditorActivityChoice>) =
        SequenceTemplateEditorController(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            SequenceTemplateEditorTarget.New,
            { null },
            { choices },
            { draft, _, at -> template(draft, at) },
            { _, _, draft, at -> template(draft, at) },
            Instant::now,
        )

    private fun existingController(authoring: SequenceTemplateAuthoringState) =
        SequenceTemplateEditorController(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            SequenceTemplateEditorTarget.Existing(authoring.sequence.id),
            { authoring },
            { emptyList() },
            { _, _, _ -> error("Create is not used") },
            { _, _, _, _ -> error("Save is not used") },
            Instant::now,
        )

    private fun drag(
        sourceTag: String,
        target: SemanticsNodeInteraction,
        targetYFraction: Float = 0.5f,
    ) {
        val source = composeTestRule.onNodeWithTag(sourceTag, useUnmergedTree = true)
        composeTestRule.waitForIdle()
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val targetBounds = target.fetchSemanticsNode().boundsInRoot
        val targetPoint = targetBounds.center.copy(y = targetBounds.top + targetBounds.height * targetYFraction)
        source.performTouchInput {
            swipe(center, targetPoint - sourceBounds.topLeft, 400)
        }
        composeTestRule.waitForIdle()
    }

    private fun autoScrollDrag(sourceTag: String) {
        val source = composeTestRule.onNodeWithTag(sourceTag, useUnmergedTree = true)
        val page = composeTestRule.onNodeWithTag("sequence-editor-page", useUnmergedTree = true)
        composeTestRule.waitForIdle()
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val pageBounds = page.fetchSemanticsNode().boundsInRoot
        source.performTouchInput {
            val edge = center.copy(y = pageBounds.bottom - sourceBounds.top - 8f)
            down(center)
            moveTo(edge)
            repeat(5) { moveTo(edge.copy(y = edge.y - (it % 2))) }
            up()
        }
        composeTestRule.waitForIdle()
    }

    private fun pointerAuthoring(
        vararg nodes: com.alexandr5476.lifetracing.domain.SequenceNode,
    ): SequenceTemplateAuthoringState {
        val snapshots =
            listOf("a", "b", "c").associate { value ->
                val id = ActivitySnapshotId("snapshot-$value")
                id to snapshot(id, mapOf("a" to "Top A", "b" to "Top B", "c" to "Child C").getValue(value))
            }
        return SequenceTemplateAuthoringState(
            SequenceTemplate(
                SequenceTemplateId("pointer-sequence"),
                "Pointer workout",
                null,
                StatisticsSeriesId("pointer-series"),
                revision = 1,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                nodes = nodes.toList(),
            ),
            snapshots,
        )
    }

    private fun moveTag(id: String) = "sequence-move-${DraftIdentity.Existing(SequenceNodeId(id)).editorKey()}"

    private fun duplicateTag(id: String) =
        "sequence-duplicate-${DraftIdentity.Existing(SequenceNodeId(id)).editorKey()}"

    private fun dropTarget(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)

    private fun stepDropTag(id: String) = dropTag("step", id)

    private fun repeatDropTag(id: String) = dropTag("repeat", id)

    private fun dropTag(
        prefix: String,
        id: String,
    ) = "sequence-drop-$prefix:${DraftIdentity.Existing(SequenceNodeId(id)).editorKey()}"

    private fun awaitReady(controller: SequenceTemplateEditorController) {
        composeTestRule.waitUntil { controller.state.value.load is SequenceTemplateEditorLoad.Ready }
    }

    private fun choice(
        id: String,
        name: String,
        mode: TimeTrackingMode,
        timer: Duration? = null,
        mainValue: String? = null,
        unit: String? = null,
        value: Long? = null,
    ) = SequenceEditorActivityChoice(ActivityTemplateId(id), name, mode, timer, mainValue, unit, 0, value)

    private fun showPending(
        controller: SequenceTemplateEditorController,
        templateId: String,
        stepKey: String,
    ) {
        composeTestRule.runOnIdle {
            controller.updateDraft {
                it.copy(
                    nodes =
                        listOf(
                            step(
                                stepKey,
                                0,
                                StepActivityDraft.FromTemplate(ActivityTemplateId(templateId)),
                            ),
                        ),
                )
            }
        }
    }

    private fun step(
        key: String,
        position: Int,
        activity: StepActivityDraft,
    ) = SequenceNodeDraft.Step(ActivityStepDraft(DraftIdentity.New(key), position, activity))

    private fun template(
        draft: SequenceTemplateDraft,
        at: Instant,
    ) = SequenceTemplate(
        SequenceTemplateId("saved"),
        draft.name,
        draft.shortComment,
        StatisticsSeriesId("series"),
        createdAt = at,
        updatedAt = at,
    )

    private fun snapshot(
        id: ActivitySnapshotId,
        name: String,
    ) = ActivityConfigSnapshot(
        id,
        name,
        null,
        TimeTrackingMode.STOPWATCH,
        null,
        null,
        null,
        null,
        false,
        Instant.EPOCH,
    )

    private fun text(
        id: Int,
        vararg arguments: Any,
    ) = composeTestRule.activity.getString(id, *arguments)

    private fun DraftIdentity<SequenceNodeId>.existingValue() = (this as DraftIdentity.Existing).id.value

    private fun SequenceNodeDraft.stepsForTest() =
        when (this) {
            is SequenceNodeDraft.Step -> listOf(value)
            is SequenceNodeDraft.Repeat -> value.children
        }
}
