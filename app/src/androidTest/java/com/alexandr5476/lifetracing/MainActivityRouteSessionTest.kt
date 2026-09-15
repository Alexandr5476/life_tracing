package com.alexandr5476.lifetracing

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.alexandr5476.lifetracing.daily.DailyLoadState
import com.alexandr5476.lifetracing.data.persistence.DailyReadRepository
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.PlanReadRepository
import com.alexandr5476.lifetracing.data.persistence.PlanRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.editor.SequenceDropDestination
import com.alexandr5476.lifetracing.editor.SequenceEditorInputKey
import com.alexandr5476.lifetracing.editor.SequenceTemplateEditorLoad
import com.alexandr5476.lifetracing.editor.inputIsInvalid
import com.alexandr5476.lifetracing.editor.inputText
import com.alexandr5476.lifetracing.editor.readyDraft
import com.alexandr5476.lifetracing.launcher.LauncherCommandState
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSession
import com.alexandr5476.lifetracing.library.LibraryLoad
import com.alexandr5476.lifetracing.plan.PlanExecutionController
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class MainActivityRouteSessionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // One production route is exercised across recreation, exit, refresh, and all transients.
    fun productionPlanAffordanceUsesOneDestinationBackAndFreshTransientRouteState() {
        val planLabel = composeTestRule.activity.getString(R.string.daily_plan)
        val planTitle = composeTestRule.activity.getString(R.string.plan_title)
        val addLabel = composeTestRule.activity.getString(R.string.plan_add)
        val chooseLabel = composeTestRule.activity.getString(R.string.plan_choose_template)
        val closeLabel = composeTestRule.activity.getString(R.string.plan_close)
        val rescheduleLabel = composeTestRule.activity.getString(R.string.plan_reschedule)
        val cancelledLabel = composeTestRule.activity.getString(R.string.plan_cancelled)

        composeTestRule.onNodeWithText(planLabel).assertIsDisplayed().performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(planTitle).fetchSemanticsNodes().size == 1
        }
        val controller =
            composeTestRule.activity.planControllerOwner.get {
                error("Plan route must initialize the retained controller")
            }
        val selected =
            if (controller.state.value.selectedDate.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
                controller.state.value.selectedDate
                    .minusDays(1)
            } else {
                controller.state.value.selectedDate
                    .plusDays(1)
            }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(hasTestTag("plan-day-$selected")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasTestTag("plan-day-$selected")).performClick()
        composeTestRule.waitUntil(5_000) { controller.state.value.selectedDate == selected }
        composeTestRule.activityRule.scenario.recreate()
        assertSame(
            controller,
            composeTestRule.activity.planControllerOwner.get { error("Plan controller must survive recreation") },
        )
        assertEquals(selected, controller.state.value.selectedDate)
        composeTestRule.onNodeWithText(addLabel).performScrollTo().performClick()
        composeTestRule.onNodeWithText(chooseLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(closeLabel).performClick()
        composeTestRule.onNodeWithText(chooseLabel).assertDoesNotExist()

        // No dialog is present: this exercises the production route Back callback itself.
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).assertIsDisplayed()
        composeTestRule.onNodeWithText(addLabel).assertDoesNotExist()

        val suffix = System.nanoTime().toString()
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val template =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Plan route refresh $suffix", null, TimeTrackingMode.STOPWATCH, null),
                createdAt = Instant.now(),
            )
        PlanRepository
            .create(composeTestRule.activity)
            .createActivityPlanFromTemplate(template.id, PlanSchedule.FloatingDay(selected), Instant.now())

        composeTestRule.onNodeWithText(planLabel).assertIsDisplayed().performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(template.name).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(selected, controller.state.value.selectedDate)

        composeTestRule.onNodeWithText(addLabel).performScrollTo().performClick()
        composeTestRule.onNodeWithText(chooseLabel).assertIsDisplayed()
        composeTestRule.runOnUiThread {
            controller.onRouteExited()
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.onNodeWithText(planLabel).performClick()
        composeTestRule.onNodeWithText(chooseLabel).assertDoesNotExist()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(template.name).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule
            .onAllNodesWithText(composeTestRule.activity.getString(R.string.plan_reschedule))[0]
            .performClick()
        composeTestRule.onAllNodesWithText(rescheduleLabel).assertCountEquals(2)
        composeTestRule.runOnUiThread {
            controller.onRouteExited()
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.onNodeWithText(planLabel).performClick()
        composeTestRule.onAllNodesWithText(rescheduleLabel).assertCountEquals(1)

        composeTestRule.onNodeWithText(cancelledLabel).performClick()
        composeTestRule.runOnUiThread {
            controller.onRouteExited()
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.onNodeWithText(planLabel).performClick()

        composeTestRule.onNodeWithText(chooseLabel).assertDoesNotExist()
        composeTestRule.onAllNodesWithText(rescheduleLabel).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(cancelledLabel).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(planTitle).assertCountEquals(1)
        assertEquals(selected, controller.state.value.selectedDate)
    }

    @Test
    fun dailyNoLivePlanUsesProductionRouteAndPreservesAnUnrelatedLiveSession() {
        val suffix = System.nanoTime().toString()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val library = LibraryRepository.create(composeTestRule.activity)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        val plans = PlanRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        val unrelated =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Unrelated live $suffix", null, TimeTrackingMode.STOPWATCH, null),
                createdAt = now,
            )
        val planned =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Daily no-live plan $suffix",
                    null,
                    TimeTrackingMode.NO_LIVE_TRACKING,
                    null,
                    fields =
                        listOf(
                            ActivityFieldDraft(
                                DraftIdentity.New("zero"),
                                0,
                                "Zero $suffix",
                                CustomFieldType.NUMBER,
                                displayPrecision = 0,
                                defaultNumberScaled = 0,
                            ),
                            ActivityFieldDraft(
                                DraftIdentity.New("missing"),
                                1,
                                "Missing $suffix",
                                CustomFieldType.NUMBER,
                                displayPrecision = 3,
                            ),
                            ActivityFieldDraft(
                                DraftIdentity.New("main"),
                                2,
                                "Main $suffix",
                                CustomFieldType.NUMBER,
                                displayPrecision = 3,
                                defaultNumberScaled = 12_345,
                                isMainValue = true,
                            ),
                            ActivityFieldDraft(
                                DraftIdentity.New("category"),
                                3,
                                "Category $suffix",
                                CustomFieldType.CATEGORY,
                                defaultCategoryOption = DraftIdentity.New("hard"),
                                categoryOptions =
                                    listOf(
                                        ActivityCategoryOptionDraft(DraftIdentity.New("easy"), 0, "Easy $suffix"),
                                        ActivityCategoryOptionDraft(DraftIdentity.New("hard"), 1, "Hard $suffix"),
                                    ),
                            ),
                            ActivityFieldDraft(
                                DraftIdentity.New("text"),
                                4,
                                "Text $suffix",
                                CustomFieldType.TEXT,
                                defaultText = "Default $suffix",
                            ),
                        ),
                ),
                createdAt = now.plusMillis(1),
            )
        val plan =
            plans.createActivityPlanFromTemplate(
                planned.id,
                PlanSchedule.FloatingDay(today),
                now.plusMillis(2),
            )
        val frozen =
            (
                PlanReadRepository.create(composeTestRule.activity).getFocusedAction(plan.id).snapshot as
                    FocusedPlanAction.Snapshot.Activity
            ).value
        val zero = frozen.fields.single { it.nameAtCreation.startsWith("Zero ") }
        val missing = frozen.fields.single { it.nameAtCreation.startsWith("Missing ") }
        val main = frozen.fields.single { it.nameAtCreation.startsWith("Main ") }
        val category = frozen.fields.single { it.nameAtCreation.startsWith("Category ") }
        val text = frozen.fields.single { it.nameAtCreation.startsWith("Text ") }
        val easy = category.categoryOptions.single { it.labelAtCreation.startsWith("Easy ") }
        val unrelatedExecution = library.startActivityFromTemplate(unrelated.id, now, now.plusMillis(3), zone)
        composeTestRule.runOnUiThread {
            LifeTracingRuntimeGraph
                .from(composeTestRule.activity)
                .dailyController
                .dispatch(com.alexandr5476.lifetracing.daily.DailyAction.Retry)
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(planned.name).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule
            .onNodeWithTag("daily-plan-action-${plan.id.value}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession
                ?.quickDraft != null
        }
        val retained =
            requireNotNull(
                composeTestRule.activity.planExecutionRouteSessions.activeSession,
            )
        val draft = requireNotNull(retained.quickDraft)
        assertEquals(4, draft.values.values.count { it != null })
        assertEquals("0", draft.numberTexts[zero.id])
        assertNull(draft.values[missing.id])
        assertEquals("12.345", draft.numberTexts[main.id])
        assertEquals(category.defaultCategoryOptionId, (draft.values[category.id] as CategoryExecutionValue).optionId)
        assertEquals("Default $suffix", (draft.values[text.id] as TextExecutionValue).value)

        composeTestRule.onNodeWithTag("plan-value-${missing.id.value}").performTextReplacement("invalid")
        composeTestRule.onNodeWithTag("plan-execution-submit").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithTag("plan-value-${missing.id.value}").performTextReplacement("7.5")
        composeTestRule.onNodeWithTag("plan-missing-${main.id.value}").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("plan-value-${category.id.value}").performScrollTo().performClick()
        composeTestRule.onNodeWithText(easy.labelAtCreation).performClick()
        composeTestRule
            .onNodeWithTag("plan-value-${text.id.value}")
            .performScrollTo()
            .performTextReplacement("Edited $suffix")
        composeTestRule.onNodeWithTag("plan-execution-submit").performScrollTo().assertIsEnabled()

        composeTestRule.activityRule.scenario.recreate()
        assertSame(retained, composeTestRule.activity.planExecutionRouteSessions.activeSession)
        val recreatedDraft = requireNotNull(retained.quickDraft)
        assertEquals("7.5", recreatedDraft.numberTexts[missing.id])
        assertNull(recreatedDraft.values[main.id])
        assertEquals(easy.id, (recreatedDraft.values[category.id] as CategoryExecutionValue).optionId)
        assertEquals("Edited $suffix", (recreatedDraft.values[text.id] as TextExecutionValue).value)

        composeTestRule.onNodeWithTag("plan-execution-submit").performScrollTo().performClick()
        composeTestRule.runOnUiThread {
            retained.controller.launch(recreatedDraft.overrides(frozen.fields))
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession == null
        }

        assertEquals(PlanEntryStatus.FULFILLED, plans.getPlan(plan.id)?.status)
        val persisted = executionValues(plan.id.value).associateBy(PersistedExecutionValue::fieldId)
        assertEquals(4, persisted.size)
        assertEquals(0L, persisted.getValue(zero.id.value).numberScaled)
        assertEquals(7_500L, persisted.getValue(missing.id.value).numberScaled)
        assertTrue(main.id.value !in persisted)
        assertEquals(easy.id.value, persisted.getValue(category.id.value).categoryOptionId)
        assertEquals("Edited $suffix", persisted.getValue(text.id.value).textValue)
        assertEquals(1, planExecutionCount(plan.id.value))
        assertEquals(unrelatedExecution.id, live.getActiveSession()?.activityExecutionId)
        assertEquals(
            1,
            daily().completedHistory.count {
                it is CompletedActivityHistoryRoot && it.title == planned.name
            },
        )
        clearLiveSession(live)
    }

    @Test
    fun productionBackWaitsForBlockedPlanCommitBeforeDeliveringAndReleasing() {
        val suffix = System.nanoTime().toString()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        val plans = PlanRepository.create(composeTestRule.activity)
        val reads = PlanReadRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        val template =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Blocked Plan commit $suffix", null, TimeTrackingMode.NO_LIVE_TRACKING, null),
                createdAt = now,
            )
        val plan =
            plans.createActivityPlanFromTemplate(template.id, PlanSchedule.FloatingDay(today), now.plusMillis(1))
        val identity = reads.getFocusedAction(plan.id).identity
        val entered = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val graph = LifeTracingRuntimeGraph.from(composeTestRule.activity)
        val controller =
            PlanExecutionController(
                graph.scope,
                identity,
                reads::getFocusedAction,
                { false },
                { command ->
                    entered.complete(Unit)
                    releaseCommit.await()
                    executePlanCommand(command, live)
                },
                {},
                WallClock(Instant::now),
                { zone },
                object : PreflightScheduler {
                    override fun schedule(
                        duration: Duration,
                        onBoundary: () -> Unit,
                    ): PreflightHandle = error("No-live completion has no preflight")
                },
                graph.coordinator.mutationGate,
            )
        val retained =
            requireNotNull(
                composeTestRule.activity.planExecutionRouteSessions.acquire(
                    identity,
                    com.alexandr5476.lifetracing.plan.PlanExecutionOrigin.DAILY,
                ) { controller },
            )

        openDailyPlan(plan.id, template.name)
        composeTestRule.onNodeWithTag("plan-execution-submit").performClick()
        composeTestRule.waitUntil(5_000) {
            entered.isCompleted &&
                retained.controller.state.value.command is
                    com.alexandr5476.lifetracing.plan.PlanExecutionCommandState.Committing
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
        assertSame(retained, composeTestRule.activity.planExecutionRouteSessions.activeSession)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.plan_execution_committing))
            .assertIsDisplayed()
        assertEquals(0, planExecutionCount(plan.id.value))

        releaseCommit.complete(Unit)
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession == null
        }
        assertEquals(PlanEntryStatus.FULFILLED, plans.getPlan(plan.id)?.status)
        assertEquals(1, planExecutionCount(plan.id.value))
    }

    @Test
    fun timedPlanPreflightBackCreatesNoRuntimeAndRetainsTheSameSessionAcrossRecreation() {
        val suffix = System.nanoTime().toString()
        val now = Instant.now()
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        val plans = PlanRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        val template =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Timed plan preflight $suffix",
                    null,
                    TimeTrackingMode.STOPWATCH,
                    null,
                    ActivityTemplateSettings(startCountdown = Duration.ofDays(1)),
                ),
                createdAt = now,
            )
        val plan =
            plans.createActivityPlanFromTemplate(
                template.id,
                PlanSchedule.FloatingDay(today),
                now.plusMillis(1),
            )
        composeTestRule.runOnUiThread {
            LifeTracingRuntimeGraph
                .from(composeTestRule.activity)
                .dailyController
                .dispatch(com.alexandr5476.lifetracing.daily.DailyAction.Retry)
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(template.name).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onNodeWithTag("daily-plan-action-${plan.id.value}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession
                ?.controller
                ?.state
                ?.value
                ?.prepared is com.alexandr5476.lifetracing.plan.PlanExecutionLoad.Content
        }
        composeTestRule.onNodeWithTag("plan-execution-submit").performScrollTo().performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession
                ?.controller
                ?.state
                ?.value
                ?.command is com.alexandr5476.lifetracing.plan.PlanExecutionCommandState.Preflight
        }
        val retained = requireNotNull(composeTestRule.activity.planExecutionRouteSessions.activeSession)
        composeTestRule.activityRule.scenario.recreate()
        assertSame(retained, composeTestRule.activity.planExecutionRouteSessions.activeSession)

        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession == null
        }

        assertNull(live.getActiveSession())
        assertEquals(PlanEntryStatus.PLANNED, plans.getPlan(plan.id)?.status)
        assertTrue(
            daily().completedHistory.none {
                it is CompletedActivityHistoryRoot && it.title == template.name
            },
        )
    }

    @Test
    fun nonZeroTimedPlanCompletesThroughProductionPreflightOnce() {
        val suffix = System.nanoTime().toString()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        val plans = PlanRepository.create(composeTestRule.activity)
        val library = LibraryRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        val template =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Timed production preflight $suffix",
                    null,
                    TimeTrackingMode.STOPWATCH,
                    null,
                    ActivityTemplateSettings(startCountdown = Duration.ofSeconds(1)),
                ),
                createdAt = now,
            )
        val plan =
            plans.createActivityPlanFromTemplate(
                template.id,
                PlanSchedule.FloatingDay(today),
                now.plusMillis(1),
            )

        openDailyPlan(plan.id, template.name)
        composeTestRule.onNodeWithTag("plan-execution-submit").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession
                ?.controller
                ?.state
                ?.value
                ?.command is com.alexandr5476.lifetracing.plan.PlanExecutionCommandState.Preflight
        }
        assertNull(live.getActiveSession())
        assertEquals(0, planExecutionCount(plan.id.value))

        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession == null
        }
        val active =
            requireNotNull(
                live.getActiveRuntime(),
            ) as com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
        assertEquals(plan.id, active.execution.planEntryId)
        assertEquals(plan.activitySnapshotId, active.snapshot.id)
        assertEquals(1, planExecutionCount(plan.id.value))
        assertEquals(PlanEntryStatus.PLANNED, plans.getPlan(plan.id)?.status)
        assertTrue(PlanReadRepository.create(composeTestRule.activity).getFocusedAction(plan.id).engaged)
        assertTrue(library.getRecent(100).any { it.id == LibraryTemplateId.Activity(template.id) })
        clearLiveSession(live)
    }

    @Test
    @Suppress("LongMethod")
    fun planOriginDeliversLiveToDailyAndNoLiveBackToRefreshedPlan() {
        val suffix = System.nanoTime().toString()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        val plans = PlanRepository.create(composeTestRule.activity)
        val reads = PlanReadRepository.create(composeTestRule.activity)
        val library = LibraryRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        val timed =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Plan origin timed $suffix",
                    null,
                    TimeTrackingMode.TIMER,
                    Duration.ofMinutes(5),
                ),
                createdAt = now,
            )
        val noLive =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Plan origin no-live $suffix", null, TimeTrackingMode.NO_LIVE_TRACKING, null),
                createdAt = now.plusMillis(1),
            )
        val timedPlan =
            plans.createActivityPlanFromTemplate(timed.id, PlanSchedule.FloatingDay(today), now.plusMillis(2))
        val noLivePlan =
            plans.createActivityPlanFromTemplate(noLive.id, PlanSchedule.FloatingDay(today), now.plusMillis(3))
        val expectedTimedIdentity = reads.getFocusedAction(timedPlan.id).identity

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_plan)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(timed.name).fetchSemanticsNodes().isNotEmpty()
        }
        val timedAction = composeTestRule.onNodeWithTag("plan-plan-action-${timedPlan.id.value}")
        timedAction.performScrollTo()
        composeTestRule.waitForIdle()
        timedAction.performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession != null
        }
        assertEquals(
            expectedTimedIdentity,
            composeTestRule.activity.planExecutionRouteSessions.activeSession
                ?.expectedIdentity,
        )
        composeTestRule.onNodeWithTag("plan-execution-submit").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession == null
        }

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_title)).assertIsDisplayed()
        val active =
            requireNotNull(
                live.getActiveRuntime(),
            ) as com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
        assertEquals(timedPlan.id, active.execution.planEntryId)
        assertEquals(timedPlan.activitySnapshotId, active.snapshot.id)
        assertEquals(PlanEntryStatus.PLANNED, plans.getPlan(timedPlan.id)?.status)
        assertTrue(reads.getFocusedAction(timedPlan.id).engaged)
        assertTrue(library.getRecent(100).any { it.id == LibraryTemplateId.Activity(timed.id) })
        live.completeActiveActivity(Instant.now())

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_plan)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(noLive.name).fetchSemanticsNodes().isNotEmpty()
        }
        val noLiveAction = composeTestRule.onNodeWithTag("plan-plan-action-${noLivePlan.id.value}")
        noLiveAction.performScrollTo()
        composeTestRule.waitForIdle()
        noLiveAction.performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession
                ?.quickDraft != null
        }
        composeTestRule.onNodeWithTag("plan-execution-submit").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession == null &&
                plans.getPlan(noLivePlan.id)?.status == PlanEntryStatus.FULFILLED
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodes(hasTestTag("plan-plan-action-${noLivePlan.id.value}"))
                .fetchSemanticsNodes()
                .isEmpty()
        }

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.plan_title)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(composeTestRule.activity.getString(R.string.plan_title)).assertCountEquals(1)
        composeTestRule.onNodeWithTag("plan-plan-action-${noLivePlan.id.value}").assertDoesNotExist()
        assertNull(live.getActiveSession())
        assertEquals(1, planExecutionCount(noLivePlan.id.value))
    }

    @Test
    fun lateLiveConflictThroughProductionRoutePreservesTheUnrelatedRuntime() {
        val suffix = System.nanoTime().toString()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val library = LibraryRepository.create(composeTestRule.activity)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        val plans = PlanRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        val planned =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Late conflict plan $suffix",
                    null,
                    TimeTrackingMode.STOPWATCH,
                    null,
                    ActivityTemplateSettings(startCountdown = Duration.ofSeconds(1)),
                ),
                createdAt = now,
            )
        val unrelated =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Late conflict active $suffix", null, TimeTrackingMode.STOPWATCH, null),
                createdAt = now.plusMillis(1),
            )
        val plan =
            plans.createActivityPlanFromTemplate(planned.id, PlanSchedule.FloatingDay(today), now.plusMillis(2))

        openDailyPlan(plan.id, planned.name)
        composeTestRule.onNodeWithTag("plan-execution-submit").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession
                ?.controller
                ?.state
                ?.value
                ?.command is com.alexandr5476.lifetracing.plan.PlanExecutionCommandState.Preflight
        }
        val unrelatedStartedAt = Instant.now()
        val unrelatedExecution =
            library.startActivityFromTemplate(unrelated.id, unrelatedStartedAt, unrelatedStartedAt, zone)
        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodesWithText(
                    composeTestRule.activity.getString(R.string.plan_execution_conflict),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }

        assertEquals(0, planExecutionCount(plan.id.value))
        assertEquals(unrelatedExecution.id, live.getActiveSession()?.activityExecutionId)
        assertFalse(library.getRecent(100).any { it.id == LibraryTemplateId.Activity(planned.id) })
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession == null
        }
        clearLiveSession(live)
    }

    @Test
    @Suppress("LongMethod")
    fun productionRouteRejectsStalePlanButKeepsFrozenSourceDivergenceExecutable() {
        val suffix = System.nanoTime().toString()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        val plans = PlanRepository.create(composeTestRule.activity)
        val reads = PlanReadRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        val staleTemplate =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Stale production plan $suffix", null, TimeTrackingMode.STOPWATCH, null),
                createdAt = now,
            )
        val stalePlan =
            plans.createActivityPlanFromTemplate(
                staleTemplate.id,
                PlanSchedule.FloatingDay(today),
                now.plusMillis(1),
            )
        val staleIdentity = reads.getFocusedAction(stalePlan.id).identity

        openDailyPlan(stalePlan.id, staleTemplate.name)
        val moved =
            plans.reschedulePlanEntry(
                staleIdentity,
                PlanSchedule.ExactDay(today.atTime(23, 59), zone),
                Instant.now().plusSeconds(1),
            )
        composeTestRule.onNodeWithTag("plan-execution-submit").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodesWithText(
                    composeTestRule.activity.getString(R.string.plan_execution_stale),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(0, planExecutionCount(stalePlan.id.value))
        assertEquals(moved.actionIdentity(), plans.getPlan(stalePlan.id)?.actionIdentity())
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.plan_execution_reload))
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession == null &&
                composeTestRule
                    .onAllNodes(hasTestTag("daily-plan-action-${stalePlan.id.value}"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }

        val sourceAt = Instant.now()
        val sourceTemplate =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Frozen source plan $suffix", null, TimeTrackingMode.STOPWATCH, null),
                createdAt = sourceAt,
            )
        val sourcePlan =
            plans.createActivityPlanFromTemplate(
                sourceTemplate.id,
                PlanSchedule.FloatingDay(today),
                sourceAt.plusMillis(1),
            )
        val original = reads.getFocusedAction(sourcePlan.id)
        val originalSnapshot = (original.snapshot as FocusedPlanAction.Snapshot.Activity).value
        openDailyPlan(sourcePlan.id, sourceTemplate.name)
        authoring.saveActivityTemplate(
            sourceTemplate.id,
            sourceTemplate.revision,
            sourceTemplate.toAuthoringDraft().copy(name = "Changed source $suffix"),
            Instant.now(),
        )
        composeTestRule.onNodeWithTag("plan-execution-submit").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession == null
        }

        val active =
            requireNotNull(
                live.getActiveRuntime(),
            ) as com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
        assertEquals(sourcePlan.id, active.execution.planEntryId)
        assertEquals(originalSnapshot.id, active.snapshot.id)
        assertEquals(originalSnapshot.name, active.snapshot.name)
        val retained = (reads.getFocusedAction(sourcePlan.id).snapshot as FocusedPlanAction.Snapshot.Activity).value
        assertEquals(originalSnapshot, retained)
        clearLiveSession(live)
    }

    @Test
    fun sequencePlanStartUsesProductionRouteAndReturnsToUsableDailyRuntime() {
        val suffix = System.nanoTime().toString()
        val now = Instant.now()
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        val plans = PlanRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Planned sequence step $suffix", null, TimeTrackingMode.STOPWATCH, null),
                createdAt = now,
            )
        val sequence =
            authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Planned sequence $suffix",
                    null,
                    nodes =
                        listOf(
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    DraftIdentity.New("step"),
                                    0,
                                    StepActivityDraft.FromTemplate(activity.id),
                                ),
                            ),
                        ),
                ),
                createdAt = now.plusMillis(1),
            )
        val plan =
            plans.createSequencePlanFromTemplate(
                sequence.id,
                PlanSchedule.FloatingDay(today),
                now.plusMillis(2),
            )
        composeTestRule.runOnUiThread {
            LifeTracingRuntimeGraph
                .from(composeTestRule.activity)
                .dailyController
                .dispatch(com.alexandr5476.lifetracing.daily.DailyAction.Retry)
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(sequence.name).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule
            .onNodeWithTag("daily-plan-action-${plan.id.value}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession
                ?.controller
                ?.state
                ?.value
                ?.prepared is com.alexandr5476.lifetracing.plan.PlanExecutionLoad.Content
        }
        composeTestRule.onNodeWithTag("plan-execution-submit").performScrollTo().performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.planExecutionRouteSessions.activeSession == null
        }

        val active = requireNotNull(daily().active) as DailyActive.Sequence
        assertEquals(plan.id, active.runtime.execution.planEntryId)
        assertEquals(PlanEntryStatus.PLANNED, plans.getPlan(plan.id)?.status)
        assertTrue(
            DailyReadRepository
                .create(composeTestRule.activity)
                .getDaily(
                    com.alexandr5476.lifetracing.domain
                        .DailyQuery(today, Instant.now(), 100),
                ).dayPlans
                .single { it.plan.id == plan.id }
                .engaged,
        )
        clearLiveSession(live)
    }

    @Test
    fun productionExpandedSequenceSystemBackReleasesExactSessionAndAllowsTheNextExecution() {
        val suffix = System.nanoTime().toString()
        val name = "Expanded route $suffix"
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        val createdAt = Instant.now()
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft("Expanded step $suffix", null, TimeTrackingMode.STOPWATCH, null),
                createdAt = createdAt,
            )
        val sequence =
            authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    name,
                    null,
                    nodes =
                        listOf(
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    DraftIdentity.New("step"),
                                    0,
                                    StepActivityDraft.FromTemplate(activity.id),
                                ),
                            ),
                        ),
                ),
                createdAt = createdAt.plusMillis(1),
            )
        val started =
            LibraryRepository.create(composeTestRule.activity).startSequenceFromTemplate(
                sequence.id,
                createdAt.plusMillis(2),
                createdAt.plusMillis(2),
                ZoneId.systemDefault(),
                sequence.revision,
            )

        composeTestRule.activityRule.scenario.recreate()
        expandSequence()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.expandedLiveSequenceRouteSessions.activeSession != null
        }
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.expanded_sequence_back))
            .assertIsDisplayed()
        val firstSession = requireNotNull(composeTestRule.activity.expandedLiveSequenceRouteSessions.activeSession)
        assertEquals(started.execution.id, firstSession.executionId)

        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.expandedLiveSequenceRouteSessions.activeSession == null
        }
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_title)).assertIsDisplayed()
        expandSequence()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.expandedLiveSequenceRouteSessions.activeSession != null
        }
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.expanded_sequence_back))
            .assertIsDisplayed()
        val retained = requireNotNull(composeTestRule.activity.expandedLiveSequenceRouteSessions.activeSession)
        assertNotSame(firstSession, retained)
        assertEquals(started.execution.id, retained.executionId)

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        assertSame(retained, composeTestRule.activity.expandedLiveSequenceRouteSessions.activeSession)
        assertEquals(started.execution.id, retained.controller.executionId)

        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.expandedLiveSequenceRouteSessions.activeSession == null
        }
        live.endSequenceEarly(started.execution.id, Instant.now())
        val secondStartedAt = Instant.now()
        val second =
            LibraryRepository.create(composeTestRule.activity).startSequenceFromTemplate(
                sequence.id,
                secondStartedAt,
                secondStartedAt,
                ZoneId.systemDefault(),
                sequence.revision,
            )
        assertNotEquals(started.execution.id, second.execution.id)
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitUntil(5_000) {
            val controller =
                LifeTracingRuntimeGraph
                    .from(composeTestRule.activity)
                    .dailyController
            val active =
                (controller.state.value.load as? DailyLoadState.Content)
                    ?.daily
                    ?.active
            val activeExecutionId =
                (active as? DailyActive.Sequence)
                    ?.runtime
                    ?.execution
                    ?.id
            activeExecutionId == second.execution.id
        }
        expandSequence()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.expandedLiveSequenceRouteSessions.activeSession
                ?.executionId == second.execution.id
        }
        assertEquals(
            second.execution.id,
            requireNotNull(
                composeTestRule.activity.expandedLiveSequenceRouteSessions.activeSession,
            ).controller.executionId,
        )

        live.endSequenceEarly(second.execution.id, Instant.now())
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.expandedLiveSequenceRouteSessions.activeSession ==
                null
        }
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_title)).assertIsDisplayed()
    }

    @Test
    fun productionSequenceEditorRetainsInvalidTextAcrossRecreationThenDiscardsOrCommitsCleanly() {
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_new_sequence))
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession != null
        }
        val first = requireNotNull(composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession)
        composeTestRule.waitUntil(5_000) {
            first.controller.state.value.load is SequenceTemplateEditorLoad.Ready
        }
        composeTestRule.runOnUiThread {
            first.controller.updateNumberInput(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN, "bad", 0, 0) {}
        }

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        assertSame(first, composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession)
        assertEquals(
            "bad",
            first.controller.state.value
                .inputText(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN, "0"),
        )
        assertTrue(
            first.controller.state.value
                .inputIsInvalid(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN),
        )
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule
            .onNodeWithText(
                composeTestRule.activity.getString(R.string.sequence_editor_discard_title),
            ).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                composeTestRule.activity.getString(R.string.sequence_editor_discard),
            ).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession == null
        }

        val name = "Sequence editor ${System.nanoTime()}"
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_new_sequence))
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession != null
        }
        val second = requireNotNull(composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession)
        composeTestRule.waitUntil(5_000) {
            second.controller.state.value.load is SequenceTemplateEditorLoad.Ready
        }
        val firstStep = DraftIdentity.New("first")
        val secondStep = DraftIdentity.New("second")
        composeTestRule.runOnUiThread {
            second.controller.updateDraft {
                it.copy(
                    name = name,
                    nodes =
                        listOf(
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    firstStep,
                                    0,
                                    StepActivityDraft.Local(
                                        ActivitySnapshotDraft("First", null, TimeTrackingMode.STOPWATCH, null),
                                    ),
                                ),
                            ),
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    secondStep,
                                    1,
                                    StepActivityDraft.Local(
                                        ActivitySnapshotDraft("Second", null, TimeTrackingMode.STOPWATCH, null),
                                    ),
                                ),
                            ),
                        ),
                )
            }
            second.controller.enterManipulation(firstStep)
            second.controller.moveManipulation(secondStep, SequenceDropDestination(position = 0))
            second.controller.moveManipulation(firstStep, SequenceDropDestination(position = 0))
            second.controller.undoManipulation()
            second.controller.selectManipulation(secondStep)
        }
        val manipulationDraft =
            second.controller.state.value
                .readyDraft()
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        assertSame(second, composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession)
        assertEquals(
            manipulationDraft,
            second.controller.state.value
                .readyDraft(),
        )
        assertEquals(
            secondStep,
            second.controller.state.value.manipulation
                ?.selected,
        )
        assertTrue(
            second.controller.state.value.manipulation
                ?.canUndo == true,
        )
        assertTrue(
            second.controller.state.value.manipulation
                ?.canRedo == true,
        )
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.sequence_editor_apply))
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) { second.controller.state.value.appliedGeneration == 1L }
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession == null
        }
        composeTestRule
            .onNode(
                hasText(composeTestRule.activity.getString(R.string.library_search_hint)) and hasSetTextAction(),
            ).performTextInput(name)
        val sequenceRow = hasText(name) and hasText(composeTestRule.activity.getString(R.string.library_sequence))
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(sequenceRow).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(sequenceRow).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession != null
        }
        val existing = requireNotNull(composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession)
        composeTestRule.waitUntil(5_000) {
            existing.controller.state.value.load is SequenceTemplateEditorLoad.Ready
        }
        composeTestRule.runOnUiThread { existing.controller.updateDraft { it.copy(shortComment = "retained") } }
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        assertSame(existing, composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession)
        assertEquals(
            "retained",
            existing.controller.state.value
                .readyDraft()
                ?.shortComment,
        )
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule
            .onNodeWithText(
                composeTestRule.activity.getString(R.string.sequence_editor_discard),
            ).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.sequenceTemplateEditorRouteSessions.activeSession == null
        }
    }

    @Test
    fun productionLauncherSessionSurvivesActivityRecreationAndIsReleasedOnlyWhenPopped() {
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_start_activity)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession != null
        }
        val first = requireNotNull(composeTestRule.activity.startActivityRouteSessions.activeSession)
        val pending = LibraryTemplateId.Activity(ActivityTemplateId("pending"))
        composeTestRule.runOnUiThread { first.interaction.select(pending) }

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        assertSame(first, composeTestRule.activity.startActivityRouteSessions.activeSession)
        assertEquals(pending, first.interaction.pendingSelectionId)
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession == null
        }
        assertNull(composeTestRule.activity.startActivityRouteSessions.activeSession)

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_start_activity)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession != null
        }
        val next: StartActivityRouteSession =
            requireNotNull(composeTestRule.activity.startActivityRouteSessions.activeSession)
        assertNotSame(first, next)
        assertNull(next.interaction.pendingSelectionId)
        assertNull(next.interaction.quickEditor)
        assertEquals(0, next.interaction.browsePath.size)

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.launcher_browse_back)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession == null
        }
    }

    @Test
    fun productionDirtyEditorSessionSurvivesActivityRecreationAndReleasesOnlyAfterRoutePop() {
        val name = "Dirty editor ${System.nanoTime()}"
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.daily_library))
            .performClick()
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_new_activity))
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession != null
        }
        val session = requireNotNull(composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession)
        val controller = session.controller
        composeTestRule
            .onNode(hasText(composeTestRule.activity.getString(R.string.activity_editor_name)) and hasSetTextAction())
            .performTextInput(name)

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        assertSame(session, composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession)
        assertSame(
            controller,
            requireNotNull(composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession).controller,
        )
        val retainedState = controller.state.value
        val retainedDraft = retainedState.readyDraft()
        assertEquals(name, retainedDraft?.name)
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        assertSame(session, composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.activity_editor_discard))
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.activityTemplateEditorRouteSessions.activeSession == null
        }
    }

    @Test
    fun productionLibraryControllerAndProjectionSurviveActivityRecreation() {
        val name = "Retained library ${System.nanoTime()}"
        TemplateAuthoringRepository.create(composeTestRule.activity).createActivityTemplate(
            ActivityTemplateDraft(name, null, TimeTrackingMode.STOPWATCH, null),
            createdAt = Instant.now(),
        )
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
        val controller =
            composeTestRule.activity.libraryControllerOwner.get {
                error("Library route must initialize the retained controller")
            }
        val projection = controller.state.value
        composeTestRule.waitUntil(5_000) {
            controller.state.value.browse is LibraryLoad.Content &&
                controller.state.value.organization is LibraryLoad.Content
        }
        composeTestRule.waitForIdle()
        val publicationStarted = CompletableDeferred<Unit>()
        val releasePublication = CompletableDeferred<Unit>()
        controller.deferNextSearchPublicationForTest {
            publicationStarted.complete(Unit)
            releasePublication.await()
        }
        composeTestRule
            .onNode(hasText(composeTestRule.activity.getString(R.string.library_search_hint)) and hasSetTextAction())
            .performTextInput(name)
        composeTestRule.waitUntil(5_000) { publicationStarted.isCompleted }

        try {
            composeTestRule.activityRule.scenario.recreate()

            val recreated =
                composeTestRule.activity.libraryControllerOwner.get {
                    error("Activity recreation must reuse the retained controller")
                }
            assertSame(controller, recreated)
            composeTestRule.runOnUiThread { releasePublication.complete(Unit) }
            composeTestRule.waitUntil(5_000) {
                (recreated.state.value.search as? LibraryLoad.Content)
                    ?.value
                    ?.any { it.name == name } == true
            }
            composeTestRule.waitForIdle()
            assertEquals(projection.browse, recreated.state.value.browse)
            assertEquals(2, composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().size)
        } finally {
            releasePublication.complete(Unit)
        }
    }

    @Test
    fun committedActivityCatalogChangeRefreshesTheRetainedLibraryAcrossTheRecreationBoundary() {
        val oldName = "Catalog before commit ${System.nanoTime()}"
        val newName = "Catalog after commit ${System.nanoTime()}"
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val created =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(oldName, null, TimeTrackingMode.STOPWATCH, null),
                createdAt = Instant.now(),
            )
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(oldName).fetchSemanticsNodes().isNotEmpty()
        }
        val controller =
            composeTestRule.activity.libraryControllerOwner.get {
                error("Library route must initialize the retained controller")
            }
        authoring.saveActivityTemplate(
            created.id,
            created.revision,
            created.toAuthoringDraft().copy(name = newName),
            Instant.now(),
        )
        composeTestRule.runOnUiThread {
            composeTestRule.activity.libraryControllerOwner.refreshIfInitialized()
        }

        composeTestRule.activityRule.scenario.recreate()

        assertSame(
            controller,
            composeTestRule.activity.libraryControllerOwner.get {
                error("Activity recreation must retain the catalog controller")
            },
        )
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(newName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(oldName).assertDoesNotExist()
    }

    @Test
    fun productionLibraryEntersAndPopsOnTheExistingDailyStack() {
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule
            .onAllNodesWithText(composeTestRule.activity.getString(R.string.library_title))[0]
            .assertIsDisplayed()

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).assertIsDisplayed()
    }

    @Test
    @Suppress("LongMethod") // One production navigation round trip is the behavior under test.
    fun libraryOriginCancelKeepsRetainedBrowseStateAndCreatesNoDurableResidueOrRefresh() {
        val fixture = returnFixture(countdown = Duration.ofDays(1), withMainValue = false)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        enterFilteredFolderSearch(fixture)

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_quick_start))
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession
                ?.controller
                ?.state
                ?.value
                ?.command is LauncherCommandState.Preflight
        }
        val session = requireNotNull(composeTestRule.activity.startActivityRouteSessions.activeSession)
        val externalName = "${fixture.query} external cancel"
        fixture.authoring.createActivityTemplate(
            ActivityTemplateDraft(externalName, null, TimeTrackingMode.STOPWATCH, null),
            createdAt = Instant.now(),
        )

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession == null
        }

        assertNull(composeTestRule.activity.startActivityRouteSessions.activeSession)
        assertEquals(LauncherCommandState.Idle, session.controller.state.value.command)
        assertRetainedFilteredFolderState(fixture)
        composeTestRule.onNodeWithText(externalName).assertDoesNotExist()
        val freshLibrary = LibraryRepository.create(composeTestRule.activity)
        assertTrue(freshLibrary.search(externalName, LibraryKindFilter.ACTIVITIES).single().name == externalName)
        assertNull(freshLibrary.getRecent(100).firstOrNull { it.id == fixture.targetId })
        assertNull(live.getActiveSession())
        assertEquals(0, durableCount("activity_snapshots", fixture.activity.id.value))
        assertEquals(0, durableCount("activity_executions", fixture.activity.id.value))
        assertTrue(
            daily().completedHistory.none {
                it is CompletedActivityHistoryRoot && it.title == fixture.activity.name
            },
        )
    }

    @Test
    @Suppress("LongMethod") // Assertions cover the complete production commit/return boundary.
    fun libraryOriginCommitRefreshesDailyAndCanonicalLibraryWithoutLosingBrowseState() {
        val fixture = returnFixture(countdown = Duration.ZERO, withMainValue = true)
        val live = LiveSessionRepository.create(composeTestRule.activity)
        clearLiveSession(live)
        composeTestRule
            .onNodeWithContentDescription(composeTestRule.activity.getString(R.string.daily_previous_day))
            .performClick()
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.daily_return_today))
            .assertIsDisplayed()
        enterFilteredFolderSearch(fixture)
        val beforeTemplate = requireNotNull(fixture.authoring.getActivityTemplate(fixture.activity.id))
        val beforeTrackable =
            fixture.library
                .getFolderContents(fixture.folderId)
                .activities
                .single { it.id == fixture.targetId }

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_quick_start))
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession
                ?.interaction
                ?.quickEditor != null
        }
        val session = requireNotNull(composeTestRule.activity.startActivityRouteSessions.activeSession)
        assertNull(session.interaction.pendingSelectionId)
        assertEquals(0, durableCount("activity_snapshots", fixture.activity.id.value))
        assertEquals(0, durableCount("activity_executions", fixture.activity.id.value))
        assertNull(fixture.library.getRecent(100).firstOrNull { it.id == fixture.targetId })

        val externalName = "${fixture.query} canonical refresh"
        fixture.authoring.createActivityTemplate(
            ActivityTemplateDraft(externalName, null, TimeTrackingMode.STOPWATCH, null),
            createdAt = Instant.now(),
        )
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.launcher_complete))
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.activity.startActivityRouteSessions.activeSession == null
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(externalName).fetchSemanticsNodes().isNotEmpty()
        }

        assertRetainedFilteredFolderState(fixture)
        composeTestRule.onNodeWithText(externalName).assertIsDisplayed()
        val freshLibrary = LibraryRepository.create(composeTestRule.activity)
        val afterTrackable = freshLibrary.search(fixture.activity.name, LibraryKindFilter.ACTIVITIES).single()
        val afterTemplate =
            requireNotNull(
                TemplateAuthoringRepository.create(composeTestRule.activity).getActivityTemplate(fixture.activity.id),
            )
        assertEquals(beforeTemplate.revision, afterTemplate.revision)
        assertEquals(beforeTemplate.statisticsSeriesId, afterTemplate.statisticsSeriesId)
        assertEquals(beforeTrackable.folderId, afterTrackable.folderId)
        assertEquals(beforeTrackable.tagIds, afterTrackable.tagIds)
        assertEquals(beforeTrackable.pinnedRank, afterTrackable.pinnedRank)
        assertTrue(requireNotNull(afterTrackable.lastUsedAt) >= fixture.createdAt)
        assertEquals(1, durableCount("activity_snapshots", fixture.activity.id.value))
        assertEquals(1, durableCount("activity_executions", fixture.activity.id.value))
        assertEquals(
            1,
            daily().completedHistory.count {
                it is CompletedActivityHistoryRoot && it.title == fixture.activity.name
            },
        )
        assertNull(live.getActiveSession())

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.library_back)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Library / ${fixture.folderName}").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.library_back)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(fixture.activity.name).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.daily_return_today))
            .assertDoesNotExist()
        composeTestRule.onNodeWithText(fixture.activity.name).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun productionEditorReturnRefreshesTheRetainedFilteredSearchFromCanonicalStorage() {
        val prefix = "S2C2-${System.nanoTime()}"
        val query = "$prefix match"
        val activityName = "$query activity"
        val sequenceName = "$query sequence"
        val renamed = "$prefix renamed"
        val newMatchingName = "$query new"
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val createdAt = Instant.now().minusSeconds(10)
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(activityName, null, TimeTrackingMode.STOPWATCH, null),
                createdAt = createdAt,
            )
        authoring.createSequenceTemplate(
            SequenceTemplateDraft(sequenceName, null),
            createdAt = createdAt.plusMillis(1),
        )

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(activityName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onNode(hasText(composeTestRule.activity.getString(R.string.library_search_hint)) and hasSetTextAction())
            .performTextInput(query)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_filter_activities))
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(activityName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(sequenceName).assertDoesNotExist()

        composeTestRule.onNodeWithText(activityName).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(hasText(activityName) and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasText(activityName) and hasSetTextAction()).performTextReplacement(renamed)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.activity_editor_done))
            .performScrollTo()
            .performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodesWithText(composeTestRule.activity.getString(R.string.library_search_empty))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(query).assertIsDisplayed()
        composeTestRule.onNodeWithText(activityName).assertDoesNotExist()
        composeTestRule.onNodeWithText(sequenceName).assertDoesNotExist()
        assertEquals(renamed, authoring.getActivityTemplate(activity.id)?.name)

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_new_activity))
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNode(hasText(composeTestRule.activity.getString(R.string.activity_editor_name)) and hasSetTextAction())
            .performTextInput(newMatchingName)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.activity_editor_done))
            .performScrollTo()
            .performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(newMatchingName).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(newMatchingName).assertIsDisplayed()
        composeTestRule.onNodeWithText(sequenceName).assertDoesNotExist()
    }

    private fun returnFixture(
        countdown: Duration,
        withMainValue: Boolean,
    ): ReturnFixture {
        val suffix = System.nanoTime().toString()
        val query = "S5C2-$suffix"
        val createdAt = Instant.now().minusSeconds(2)
        val authoring = TemplateAuthoringRepository.create(composeTestRule.activity)
        val library = LibraryRepository.create(composeTestRule.activity)
        val folderId = FolderId("folder-$suffix")
        val folderName = "S5C2 folder $suffix"
        library.createFolder(folderId, folderName, null, createdAt)
        val fields =
            if (withMainValue) {
                listOf(
                    ActivityFieldDraft(
                        DraftIdentity.New("main"),
                        0,
                        "S5C2 value $suffix",
                        CustomFieldType.NUMBER,
                        displayPrecision = 0,
                        defaultNumberScaled = 3,
                        isMainValue = true,
                    ),
                )
            } else {
                emptyList()
            }
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "$query activity",
                    null,
                    if (withMainValue) TimeTrackingMode.NO_LIVE_TRACKING else TimeTrackingMode.STOPWATCH,
                    null,
                    settings = ActivityTemplateSettings(startCountdown = countdown),
                    fields = fields,
                ),
                createdAt = createdAt.plusMillis(1),
            )
        val sequence =
            authoring.createSequenceTemplate(
                SequenceTemplateDraft("$query sequence", null),
                createdAt = createdAt.plusMillis(2),
            )
        val targetId = LibraryTemplateId.Activity(activity.id)
        library.moveActivityTemplateToFolder(activity.id, folderId, createdAt.plusMillis(3))
        library.moveSequenceTemplateToFolder(sequence.id, folderId, createdAt.plusMillis(3))
        library.createTagAndAssign(TagId("tag-$suffix"), "S5C2 tag $suffix", targetId, createdAt.plusMillis(4))
        library.pin(targetId)
        return ReturnFixture(query, createdAt, folderId, folderName, activity, targetId, authoring, library)
    }

    private fun enterFilteredFolderSearch(fixture: ReturnFixture) {
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_library)).performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodesWithText(fixture.folderName)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule
            .onNodeWithText(fixture.folderName)
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNode(hasText(composeTestRule.activity.getString(R.string.library_search_hint)) and hasSetTextAction())
            .performTextInput(fixture.query)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.library_filter_activities))
            .performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(fixture.activity.name).fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodesWithText("${fixture.query} sequence").fetchSemanticsNodes().isEmpty()
        }
        assertRetainedFilteredFolderState(fixture)
    }

    private fun assertRetainedFilteredFolderState(fixture: ReturnFixture) {
        composeTestRule.onNodeWithText(fixture.query).assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Library / ${fixture.folderName}")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(fixture.activity.name).assertIsDisplayed()
        composeTestRule.onNodeWithText("${fixture.query} sequence").assertDoesNotExist()
    }

    private fun expandSequence() {
        composeTestRule.waitForIdle()
        val expandSequence = hasTestTag("daily-expand-sequence") and hasClickAction()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodes(expandSequence, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(expandSequence, useUnmergedTree = true).performScrollTo().performClick()
    }

    private fun openDailyPlan(
        planId: PlanEntryId,
        title: String,
    ) {
        composeTestRule.runOnUiThread {
            LifeTracingRuntimeGraph
                .from(composeTestRule.activity)
                .dailyController
                .dispatch(com.alexandr5476.lifetracing.daily.DailyAction.Retry)
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onNodeWithTag("daily-plan-action-${planId.value}")
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            val session = composeTestRule.activity.planExecutionRouteSessions.activeSession
            session?.expectedIdentity?.planEntryId == planId &&
                session.controller.state.value.prepared is com.alexandr5476.lifetracing.plan.PlanExecutionLoad.Content
        }
    }

    private fun daily() =
        Instant.now().let { now ->
            DailyReadRepository.create(composeTestRule.activity).getDaily(
                DailyQuery(now.atZone(ZoneId.systemDefault()).toLocalDate(), now, 100),
            )
        }

    private fun durableCount(
        table: String,
        sourceTemplateId: String,
    ): Int {
        val database =
            android.database.sqlite.SQLiteDatabase.openDatabase(
                composeTestRule.activity.getDatabasePath("lifetracing.db").path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
        return database.use { db ->
            val query =
                if (table == "activity_snapshots") {
                    "SELECT COUNT(*) FROM activity_snapshots WHERE source_template_id = ?"
                } else {
                    "SELECT COUNT(*) FROM activity_executions e JOIN activity_snapshots s ON s.id = e.snapshot_id " +
                        "WHERE s.source_template_id = ?"
                }
            db.rawQuery(query, arrayOf(sourceTemplateId)).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }
    }

    private fun executionValues(planEntryId: String): List<PersistedExecutionValue> {
        val database =
            android.database.sqlite.SQLiteDatabase.openDatabase(
                composeTestRule.activity.getDatabasePath("lifetracing.db").path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
        return database.use { db ->
            db
                .rawQuery(
                    "SELECT values_.snapshot_field_id, values_.number_scaled, values_.category_option_id, " +
                        "values_.text_value FROM activity_execution_field_values values_ " +
                        "JOIN activity_executions executions ON executions.id = values_.activity_execution_id " +
                        "WHERE executions.plan_entry_id = ?",
                    arrayOf(planEntryId),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                PersistedExecutionValue(
                                    cursor.getString(0),
                                    cursor.getLong(1).takeUnless { cursor.isNull(1) },
                                    cursor.getString(2).takeUnless { cursor.isNull(2) },
                                    cursor.getString(3).takeUnless { cursor.isNull(3) },
                                ),
                            )
                        }
                    }
                }
        }
    }

    private fun planExecutionCount(planEntryId: String): Int {
        val database =
            android.database.sqlite.SQLiteDatabase.openDatabase(
                composeTestRule.activity.getDatabasePath("lifetracing.db").path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
        return database.use { db ->
            db
                .rawQuery(
                    "SELECT COUNT(*) FROM activity_executions WHERE plan_entry_id = ?",
                    arrayOf(planEntryId),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
        }
    }

    private fun clearLiveSession(live: LiveSessionRepository) {
        val at = Instant.now()
        when (live.getActiveSession()?.kind) {
            ActiveSessionKind.ACTIVITY -> live.completeActiveActivity(at)
            ActiveSessionKind.SEQUENCE -> live.endSequenceEarly(at)
            null -> Unit
        }
    }

    private data class ReturnFixture(
        val query: String,
        val createdAt: Instant,
        val folderId: FolderId,
        val folderName: String,
        val activity: com.alexandr5476.lifetracing.domain.ActivityTemplate,
        val targetId: LibraryTemplateId.Activity,
        val authoring: TemplateAuthoringRepository,
        val library: LibraryRepository,
    )

    private data class PersistedExecutionValue(
        val fieldId: String,
        val numberScaled: Long?,
        val categoryOptionId: String?,
        val textValue: String?,
    )
}
