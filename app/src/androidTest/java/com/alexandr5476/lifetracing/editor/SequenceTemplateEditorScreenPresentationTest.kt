package com.alexandr5476.lifetracing.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.SequenceTemplate
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

    private fun text(id: Int) = composeTestRule.activity.getString(id)
}
