package com.alexandr5476.lifetracing.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StepActivityDraft
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

class SequenceTemplateEditorScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compactRowsKeepPendingAndLocalStepsIdentifiable() {
        val choices =
            listOf(
                choice("bench", "Bench press", TimeTrackingMode.STOPWATCH),
                choice("rest", "Rest", TimeTrackingMode.TIMER, Duration.ofSeconds(90)),
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
    ) = SequenceEditorActivityChoice(ActivityTemplateId(id), name, mode, timer)

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
