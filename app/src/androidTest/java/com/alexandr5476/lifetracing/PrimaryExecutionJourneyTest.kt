@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceState
import com.alexandr5476.lifetracing.domain.ActiveSequenceStateResolver
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.cursor
import com.alexandr5476.lifetracing.launcher.LauncherCommandState
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.launcher.StartActivityController
import com.alexandr5476.lifetracing.plan.PlanExecutionCommandState
import com.alexandr5476.lifetracing.plan.PlanExecutionController
import com.alexandr5476.lifetracing.plan.PlanExecutionOrigin
import com.alexandr5476.lifetracing.runtime.RuntimeIsolationApplication
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Real NavDisplay, route owners, controllers, mutation gate and physical production repositories. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class PrimaryExecutionJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun dailyLauncherActivityPauseResumeFinishHistoryAndBackPreserveFrozenIdentityAfterReopen() =
        journey { f ->
            val template = f.activityTemplate()
            f.store.library.pin(LibraryTemplateId.Activity(template.id))
            f.launch()
            f.selectPinned(template.name)
            f.awaitDailyRuntime()
            val original = f.store.live.getActiveRuntime() as ActiveActivityRuntime
            assertEquals(template.id, original.snapshot.sourceTemplateId)
            assertEquals(template.revision, original.snapshot.sourceRevision)
            f.dailyLanding()

            f.invoke(R.string.daily_pause)
            f.await {
                f.store.live
                    .getActiveSession()
                    ?.state == ActiveSessionState.PAUSED
            }
            assertEquals(
                original.execution.id,
                f.store.live
                    .getActiveSession()!!
                    .activityExecutionId,
            )
            f.invoke(R.string.daily_history)
            f.invoke(R.string.history_back)
            f.dailyLanding()
            assertEquals(original.snapshot.id, (f.store.live.getActiveRuntime() as ActiveActivityRuntime).snapshot.id)
            f.invoke(R.string.daily_resume)
            f.await {
                f.store.live
                    .getActiveSession()
                    ?.state == ActiveSessionState.RUNNING
            }
            f.invoke(R.string.daily_finish)
            f.awaitDailyTerminal()
            f.noRuntimeActions()
            f.dailyLanding()
            f.openHistoryFact(template.name)
            f.await { compose.onAllNodes(hasText(template.name)).fetchSemanticsNodes().isNotEmpty() }
            f.systemBack()
            f.systemBack()
            f.dailyLanding()
            f.noRuntimeActions()
            val saved = f.store.activity(original.execution.id)
            assertEquals(original.snapshot.id, saved.snapshotId)
            assertEquals(1, saved.pauses.size)
            f.reopen {
                assertNull(live.getActiveRuntime())
                assertEquals(saved, activity(saved.id))
                assertEquals(saved.id, history.getActivityDetail(saved.id)!!.root.executionId)
                assertEquals(listOf(saved.id.value), f.roots(this).map { it.cursor().executionId })
                assertEquals(
                    1L,
                    statistics.activitySeries(template.statisticsSeriesId, StatisticsPeriod.AllTime).executionCount,
                )
                assertEquals(1L to 0L, executionCounts())
            }
        }

    @Test
    fun launcherSequencePausedWaitingBackReentryAndNormalCompletionKeepOccurrencesAfterReopen() =
        journey { f ->
            val template = f.sequenceTemplate()
            f.store.library.pin(LibraryTemplateId.Sequence(template.id))
            f.launch()
            f.selectPinned(template.name)
            f.awaitDailyRuntime()
            val original = f.store.live.getActiveRuntime() as ActiveSequenceRuntime
            f.expand()
            f.invoke(R.string.expanded_sequence_pause)
            f.await {
                (f.store.live.getActiveRuntime() as? ActiveSequenceRuntime)?.let(
                    ActiveSequenceStateResolver::resolve,
                ) ==
                    ActiveSequenceState.PAUSED_CURRENT
            }
            f.systemBack()
            f.dailyLanding()
            assertEquals(
                original.execution.id,
                f.store.live
                    .getActiveSession()!!
                    .sequenceExecutionId,
            )
            f.expand()
            f.invoke(R.string.expanded_sequence_resume)
            f.invoke(R.string.expanded_sequence_complete)
            f.await {
                (f.store.live.getActiveRuntime() as? ActiveSequenceRuntime)?.let(
                    ActiveSequenceStateResolver::resolve,
                ) ==
                    ActiveSequenceState.WAITING_NEXT
            }
            val waiting = f.store.live.getActiveRuntime() as ActiveSequenceRuntime
            assertNull(waiting.execution.currentOccurrenceId)
            f.systemBack()
            f.dailyLanding()
            f.expand()
            f.invoke(R.string.expanded_sequence_start_next)
            f.await {
                (f.store.live.getActiveRuntime() as? ActiveSequenceRuntime)?.let(
                    ActiveSequenceStateResolver::resolve,
                ) ==
                    ActiveSequenceState.RUNNING_CURRENT
            }
            val second = f.store.live.getActiveRuntime() as ActiveSequenceRuntime
            assertEquals(original.execution.id, second.execution.id)
            assertEquals(original.execution.snapshotId, second.execution.snapshotId)
            assertEquals(original.execution.occurrences.map { it.id }, second.execution.occurrences.map { it.id })
            f.invoke(R.string.expanded_sequence_complete)
            f.awaitDailyTerminal()
            val completed = f.store.sequence(original.execution.id)
            assertEquals(SequenceExecutionStatus.COMPLETED, completed.status)
            val children = f.store.children(completed.id)
            assertEquals(2, children.size)
            for (child in children) {
                val occurrence = completed.occurrences.single { it.id == child.sequenceOccurrenceId }
                assertEquals(occurrence.activitySnapshotId, child.snapshotId)
                assertTrue(occurrence.sourceSequenceSnapshotNodeId != null)
            }
            f.openHistoryFact(template.name)
            f.systemBack()
            f.systemBack()
            f.noRuntimeActions()
            f.reopen {
                assertEquals(completed, sequence(completed.id))
                assertNull(live.getActiveSession())
                assertEquals(completed.id, history.getSequenceDetail(completed.id)!!.root.executionId)
                assertEquals(2L to 1L, executionCounts())
                assertEquals(
                    1L,
                    statistics.sequenceSeries(template.statisticsSeriesId, StatisticsPeriod.AllTime).executionCount,
                )
                assertEquals(children, children(completed.id))
            }
        }

    @Test
    fun dailyPlanLiveActivityUsesNativePlanActionAndReturnsWithoutSecondStart() =
        journey { f ->
            val source = f.activityTemplate()
            val plan = f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.seedTime)
            val expectedIdentity =
                f.store.planReads
                    .getFocusedAction(plan.id)
                    .identity
            f.launch()
            f.openPlan(source.name, noLive = false, origin = PlanExecutionOrigin.DAILY)
            assertEquals(
                expectedIdentity,
                f
                    .host()
                    .planExecutionRouteSessions.activeSession!!
                    .expectedIdentity,
            )
            f.invoke(R.string.plan_start_action)
            f.awaitDailyRuntime()
            val active = f.store.live.getActiveRuntime() as ActiveActivityRuntime
            assertEquals(plan.id, active.execution.planEntryId)
            assertEquals(plan.activitySnapshotId, active.snapshot.id)
            assertEquals(
                PlanEntryStatus.PLANNED,
                f.store.plans
                    .getPlan(plan.id)!!
                    .status,
            )
            assertTrue(
                f.store.planReads
                    .getFocusedAction(plan.id)
                    .engaged,
            )
            f.planAction(source.name, false).assertDoesNotExist()
            val engaged = f.store.durableFacts()
            f.recreate()
            f.dailyLanding()
            assertEquals(engaged, f.store.durableFacts())
            f.invoke(R.string.daily_finish)
            f.awaitDailyTerminal()
            f.reopen {
                val savedPlan = plans.getPlan(plan.id)!!
                assertEquals(PlanEntryStatus.FULFILLED, savedPlan.status)
                assertEquals(active.execution.id, savedPlan.fulfilledActivityExecutionId)
                assertEquals(plan.activitySnapshotId, activity(active.execution.id).snapshotId)
                assertEquals(active.execution.id, history.getActivityDetail(active.execution.id)!!.root.executionId)
                assertNull(live.getActiveSession())
                assertEquals(1L to 0L, executionCounts())
            }
        }

    @Test
    fun planOriginSequenceEndEarlyFulfillsTheSameFrozenPlanAndExecutionAfterReopen() =
        journey { f ->
            val source = f.sequenceTemplate()
            val plan = f.store.plans.createSequencePlanFromTemplate(source.id, f.schedule(), f.seedTime)
            val expectedIdentity =
                f.store.planReads
                    .getFocusedAction(plan.id)
                    .identity
            val runtimeSource =
                f.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Journey runtime addition", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = f.seedTime,
                )
            f.launch()
            f.openPlan(source.name, noLive = false, origin = PlanExecutionOrigin.PLAN)
            assertEquals(
                expectedIdentity,
                f
                    .host()
                    .planExecutionRouteSessions.activeSession!!
                    .expectedIdentity,
            )
            f.invoke(R.string.plan_start_action)
            f.awaitDailyRuntime()
            val active = f.store.live.getActiveRuntime() as ActiveSequenceRuntime
            assertEquals(plan.id, active.execution.planEntryId)
            assertEquals(plan.sequenceSnapshotId, active.execution.snapshotId)
            assertTrue(
                f.store.planReads
                    .getFocusedAction(plan.id)
                    .engaged,
            )
            f.expand()
            f.invoke(R.string.expanded_sequence_complete)
            f.await {
                (f.store.live.getActiveRuntime() as? ActiveSequenceRuntime)?.let(
                    ActiveSequenceStateResolver::resolve,
                ) ==
                    ActiveSequenceState.WAITING_NEXT
            }
            f.openSequenceActions()
            f.invoke(R.string.expanded_sequence_runtime_add)
            f.invoke(R.string.expanded_sequence_start_now)
            f.await {
                f
                    .host()
                    .expandedLiveSequenceRouteSessions.activeSession
                    ?.controller
                    ?.state
                    ?.value
                    ?.catalog !=
                    null
            }
            compose
                .onNode(hasContentDescription(runtimeSource.name, substring = true) and hasClickAction())
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            f.await {
                (f.store.live.getActiveRuntime() as? ActiveSequenceRuntime)?.execution?.occurrences?.any {
                    it.isRuntimeAdded
                } ==
                    true
            }
            val afterAdd = f.store.live.getActiveRuntime() as ActiveSequenceRuntime
            val added = afterAdd.execution.occurrences.single { it.isRuntimeAdded }
            val addedSnapshot = afterAdd.activitySnapshots.getValue(added.activitySnapshotId)
            assertEquals(runtimeSource.id, addedSnapshot.sourceTemplateId)
            assertEquals(runtimeSource.revision, addedSnapshot.sourceRevision)
            assertEquals(added.id, afterAdd.currentChild!!.sequenceOccurrenceId)
            assertEquals(added.activitySnapshotId, afterAdd.currentChild!!.snapshotId)
            assertEquals(active.execution.snapshotId, afterAdd.execution.snapshotId)
            assertNull(added.sourceSequenceSnapshotNodeId)
            assertNull(added.repeatSourceSnapshotNodeId)
            assertNull(added.repeatIteration)
            f.openSequenceActions()
            f.invoke(R.string.expanded_sequence_end_early)
            f.invoke(R.string.expanded_sequence_cancel)
            assertEquals(
                active.execution.id,
                f.store.live
                    .getActiveSession()!!
                    .sequenceExecutionId,
            )
            f.openSequenceActions()
            f.invoke(R.string.expanded_sequence_end_early)
            f.invoke(R.string.expanded_sequence_confirm)
            f.awaitDailyTerminal()
            val saved = f.store.sequence(active.execution.id)
            assertEquals(SequenceExecutionStatus.ENDED_EARLY, saved.status)
            f.invoke(R.string.daily_plan)
            f.planAction(source.name, false).assertDoesNotExist()
            f.invoke(R.string.plan_back)
            f.openHistoryFact(source.name)
            f.systemBack()
            f.systemBack()
            f.reopen {
                val savedPlan = plans.getPlan(plan.id)!!
                assertEquals(PlanEntryStatus.FULFILLED, savedPlan.status)
                assertEquals(saved.id, savedPlan.fulfilledSequenceExecutionId)
                assertEquals(saved, sequence(saved.id))
                assertEquals(2, children(saved.id).size)
                val child = children(saved.id).single { it.sequenceOccurrenceId == added.id }
                assertEquals(addedSnapshot.id, child.snapshotId)
                assertEquals(afterAdd.currentChild!!.id, child.id)
                val reopenedOccurrence = sequence(saved.id).occurrences.single { it.id == added.id }
                assertEquals(added.activitySnapshotId, reopenedOccurrence.activitySnapshotId)
                assertEquals(added.sourceSequenceSnapshotNodeId, reopenedOccurrence.sourceSequenceSnapshotNodeId)
                assertEquals(added.repeatSourceSnapshotNodeId, reopenedOccurrence.repeatSourceSnapshotNodeId)
                assertEquals(added.repeatIteration, reopenedOccurrence.repeatIteration)
                assertEquals(saved.id, history.getSequenceDetail(saved.id)!!.root.executionId)
                assertNull(live.getActiveSession())
                assertEquals(2L to 1L, executionCounts())
            }
        }

    @Test
    fun dailyNoLivePlanZeroReturnsToDailyAndRetainsFrozenFieldAfterReopen() = noLivePlan(PlanExecutionOrigin.DAILY)

    @Test
    fun planNoLivePlanZeroReturnsToPlanAndRetainsFrozenFieldAfterReopen() = noLivePlan(PlanExecutionOrigin.PLAN)

    private fun noLivePlan(origin: PlanExecutionOrigin) =
        journey { f ->
            val source =
                f.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "Journey no-live",
                        null,
                        TimeTrackingMode.NO_LIVE_TRACKING,
                        null,
                        fields =
                            listOf(
                                ActivityFieldDraft(
                                    DraftIdentity.New("amount"),
                                    0,
                                    "Journey amount",
                                    CustomFieldType.NUMBER,
                                    displayPrecision = 0,
                                    defaultNumberScaled = 7_000,
                                    isMainValue = true,
                                ),
                            ),
                    ),
                    createdAt = f.seedTime,
                )
            val plan = f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.seedTime)
            val expectedIdentity =
                f.store.planReads
                    .getFocusedAction(plan.id)
                    .identity
            val frozen =
                (
                    f.store.planReads
                        .getFocusedAction(plan.id)
                        .snapshot as
                        com.alexandr5476.lifetracing.domain.FocusedPlanAction.Snapshot.Activity
                ).value
            f.launch()
            f.openPlan(source.name, true, origin)
            assertEquals(
                expectedIdentity,
                f
                    .host()
                    .planExecutionRouteSessions.activeSession!!
                    .expectedIdentity,
            )
            val field = frozen.fields.single()
            val session = requireNotNull(f.host().planExecutionRouteSessions.activeSession)
            // Fixed storage scale is 1,000 even when display precision is zero.
            assertEquals(7_000L, field.defaultNumberScaled)
            assertEquals("7", session.quickDraft!!.numberTexts[field.id])
            assertTrue(session.quickDraft!!.changed.isEmpty())
            val label = f.text(R.string.plan_execution_main_value, "Journey amount")
            val input = compose.onNode(hasText(label) and hasSetTextAction())
            input.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
            input.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("0")) }
            compose.waitForIdle()
            assertEquals("0", session.quickDraft!!.numberTexts[field.id])
            assertEquals(setOf(field.id), session.quickDraft!!.changed)
            assertEquals(NumberExecutionValue(field.id, 0), session.quickDraft!!.values[field.id])
            f.invoke(R.string.plan_complete_action)
            f.await { f.host().planExecutionRouteSessions.activeSession == null }
            val savedPlan = f.store.plans.getPlan(plan.id)!!
            val completed = f.store.activity(savedPlan.fulfilledActivityExecutionId!!)
            assertNull(f.store.live.getActiveSession())
            assertEquals(listOf(NumberExecutionValue(frozen.fields.single().id, 0)), completed.values)
            assertNull(completed.activeDuration)
            f.planAction(source.name, true).assertDoesNotExist()
            if (origin == PlanExecutionOrigin.PLAN) {
                compose.onNodeWithText(f.text(R.string.plan_title)).assertExists()
                f.invoke(R.string.plan_back)
            }
            f.dailyLanding()
            f.openHistoryFact(source.name)
            f.systemBack()
            f.systemBack()
            f.reopen {
                assertEquals(completed, activity(completed.id))
                assertEquals(plan.id, completed.planEntryId)
                assertEquals(plan.activitySnapshotId, completed.snapshotId)
                assertEquals(PlanEntryStatus.FULFILLED, plans.getPlan(plan.id)!!.status)
                assertNull(history.getActivityDetail(completed.id)!!.root.activeDuration)
                assertEquals(
                    1L,
                    statistics.activitySeries(source.statisticsSeriesId, StatisticsPeriod.AllTime).executionCount,
                )
                assertEquals(1L to 0L, executionCounts())
                assertNull(live.getActiveSession())
            }
        }

    @Test
    fun idleLauncherToolbarAndSystemBackReturnKeyboardFocusWithoutWriting() =
        journey { f ->
            f.launch()
            val before = f.store.durableFacts()
            repeat(2) { attempt ->
                f.invoke(R.string.daily_start_activity)
                f.action(f.text(R.string.launcher_browse_back)).assertIsFocused()
                if (attempt == 0) {
                    f.action(f.text(R.string.launcher_browse_back)).performKeyInput {
                        pressKey(androidx.compose.ui.input.key.Key.Enter)
                    }
                } else {
                    f.systemBack()
                }
                f.dailyLanding()
                assertNull(f.host().startActivityRouteSessions.activeSession)
                assertEquals(before, f.store.durableFacts())
            }
        }

    @Test
    fun launcherPreflightCancellationAndRetainedRecreationNeverReplaySelection() =
        journey { f ->
            val source = f.activityTemplate(countdown = Duration.ofHours(1))
            f.store.library.pin(LibraryTemplateId.Activity(source.id))
            f.launch()
            val before = f.store.durableFacts()
            f.selectPinned(source.name)
            f.await {
                f
                    .host()
                    .startActivityRouteSessions.activeSession
                    ?.controller
                    ?.state
                    ?.value
                    ?.command is
                    LauncherCommandState.Preflight
            }
            val retained = f.host().startActivityRouteSessions.activeSession!!
            f.recreate()
            assertSame(retained, f.host().startActivityRouteSessions.activeSession)
            assertEquals(before, f.store.durableFacts())
            f.systemBack()
            f.dailyLanding()
            assertNull(f.host().startActivityRouteSessions.activeSession)
            assertEquals(before, f.store.durableFacts())
            f.invoke(R.string.daily_start_activity)
            f.action(f.text(R.string.launcher_browse_back)).assertIsFocused()
            assertEquals(
                LauncherCommandState.Idle,
                f
                    .host()
                    .startActivityRouteSessions.activeSession!!
                    .controller.state.value.command,
            )
            f.systemBack()
            f.reopen {
                assertEquals(before, durableFacts())
                assertEquals(0L to 0L, executionCounts())
            }
        }

    @Test
    fun focusedTimeEntryBackAndStaleOriginReloadNeverWriteOrRestoreObsoleteActions() =
        journey { f ->
            val source = f.activityTemplate()
            val plan = f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.seedTime)
            val expectedIdentity =
                f.store.planReads
                    .getFocusedAction(plan.id)
                    .identity
            f.launch()
            val before = f.store.durableFacts()
            f.openPlan(source.name, false, PlanExecutionOrigin.PLAN)
            f.invoke(R.string.plan_time_entry_action)
            f.invoke(R.string.plan_time_entry_back)
            f.action(f.text(R.string.plan_back)).assertIsFocused()
            assertNull(
                f
                    .host()
                    .planExecutionRouteSessions.activeSession!!
                    .timeDraft,
            )
            f.systemBack()
            assertEquals(before, f.store.durableFacts())
            f.openPlan(source.name, false, PlanExecutionOrigin.PLAN, enterOrigin = false)
            f.store.plans.cancelPlan(expectedIdentity, Instant.now())
            val cancelled = f.store.durableFacts()
            val session = f.host().planExecutionRouteSessions.activeSession!!
            f.invoke(R.string.plan_start_action)
            f.await {
                session.controller.state.value.command ==
                    com.alexandr5476.lifetracing.plan.PlanExecutionCommandState.Stale
            }
            f.systemBack()
            f.await { f.host().planExecutionRouteSessions.activeSession == null }
            f.planAction(source.name, false).assertDoesNotExist()
            f.invoke(R.string.plan_back)
            f.dailyLanding()
            assertEquals(cancelled, f.store.durableFacts())
            f.reopen {
                assertEquals(cancelled, durableFacts())
                assertEquals(0L to 0L, executionCounts())
            }
        }

    @Test
    fun planReadRetryAndBackDuringCommitDeliverSavedCoordinationFailureOnceToDaily() =
        journey { f ->
            val source = f.activityTemplate()
            val plan = f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.seedTime)
            val expectedIdentity =
                f.store.planReads
                    .getFocusedAction(plan.id)
                    .identity
            f.launch()
            val graph = LifeTracingRuntimeGraph.from(f.host())
            val reads = AtomicInteger()
            val writes = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller =
                PlanExecutionController(
                    graph.scope,
                    expectedIdentity,
                    { id ->
                        if (reads.incrementAndGet() ==
                            1
                        ) {
                            error("fixture read failure")
                        } else {
                            f.store.planReads.getFocusedAction(id)
                        }
                    },
                    { f.store.live.getActiveSession() != null },
                    { command ->
                        entered.complete(Unit)
                        release.await()
                        writes.incrementAndGet()
                        executePlanCommand(command, f.store.live, f.store.activityCommands)
                    },
                    { error("fixture coordination failure after durable commit") },
                    WallClock(Instant::now),
                    { ZoneId.systemDefault() },
                    noPreflight,
                    graph.coordinator.mutationGate,
                )
            val retained =
                f.host().planExecutionRouteSessions.acquire(expectedIdentity, PlanExecutionOrigin.DAILY) {
                    controller
                }!!
            try {
                f.openPlan(source.name, false, PlanExecutionOrigin.DAILY, waitForContent = false)
                f.await {
                    controller.state.value.prepared is com.alexandr5476.lifetracing.plan.PlanExecutionLoad.Failure
                }
                compose.onNodeWithText(f.text(R.string.plan_execution_load_failed)).assertExists()
                f.invoke(R.string.plan_execution_retry)
                f.invoke(R.string.plan_start_action)
                f.await { entered.isCompleted }
                f.systemBack()
                assertSame(retained, f.host().planExecutionRouteSessions.activeSession)
                f.recreate()
                assertSame(retained, f.host().planExecutionRouteSessions.activeSession)
                f.invoke(R.string.plan_back)
                assertEquals(0, writes.get())
                release.complete(Unit)
                f.awaitDailyRuntime()
                f.dailyLanding()
                assertTrue(controller.state.value.command is PlanExecutionCommandState.CommittedCoordinationFailure)
                compose.onNodeWithText(f.text(R.string.daily_command_coordinating)).assertExists()
                f.recreate()
                compose.onNodeWithText(f.text(R.string.daily_command_coordinating)).assertExists()
                assertEquals(1, writes.get())
                assertTrue(
                    f.store.planReads
                        .getFocusedAction(plan.id)
                        .engaged,
                )
                f.invoke(R.string.daily_finish)
                f.awaitDailyTerminal()
                f.reopen {
                    assertEquals(1, writes.get())
                    assertEquals(PlanEntryStatus.FULFILLED, plans.getPlan(plan.id)!!.status)
                    assertEquals(1L to 0L, executionCounts())
                }
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun launcherBackDuringCommitDeliversSavedCoordinationFailureOnceToDaily() =
        journey { f ->
            val source = f.activityTemplate()
            f.store.library.pin(LibraryTemplateId.Activity(source.id))
            f.launch()
            val graph = LifeTracingRuntimeGraph.from(f.host())
            val writes = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller =
                StartActivityController(
                    graph.scope,
                    f.store.library::getRecent,
                    f.store.library::getPinned,
                    { error("Search unused by pinned fixture") },
                    { error("Browse unused by pinned fixture") },
                    f.store.library::getLaunchTarget,
                    f.store.library::reorderPinned,
                    { f.store.live.getActiveSession() != null },
                    { command ->
                        entered.complete(Unit)
                        release.await()
                        writes.incrementAndGet()
                        executeLauncherCommand(command, f.store.activityCommands, f.store.library)
                    },
                    { error("fixture coordination failure after durable commit") },
                    WallClock(Instant::now),
                    { ZoneId.systemDefault() },
                    noPreflight,
                    mutationGate = graph.coordinator.mutationGate,
                )
            val retained = f.host().startActivityRouteSessions.acquire { controller }
            try {
                f.selectPinned(source.name)
                f.await { entered.isCompleted }
                f.systemBack()
                assertSame(retained, f.host().startActivityRouteSessions.activeSession)
                f.recreate()
                f.invoke(R.string.launcher_browse_back)
                assertSame(retained, f.host().startActivityRouteSessions.activeSession)
                assertEquals(0, writes.get())
                release.complete(Unit)
                f.awaitDailyRuntime()
                assertTrue(controller.state.value.command is LauncherCommandState.CommittedCoordinationFailure)
                compose.onNodeWithText(f.text(R.string.daily_command_coordinating)).assertExists()
                f.invoke(R.string.daily_history)
                f.systemBack()
                f.dailyLanding()
                compose.onNodeWithText(f.text(R.string.daily_command_coordinating)).assertExists()
                assertEquals(1, writes.get())
                f.invoke(R.string.daily_finish)
                f.awaitDailyTerminal()
                f.reopen {
                    assertEquals(1, writes.get())
                    assertEquals(1L to 0L, executionCounts())
                }
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun russianLargeFontProductionActivityJourneyKeepsActionsReachable() = scaledActivityJourney("ru", 2f, 120, 130)

    @Test
    fun englishReducedScaleProductionActivityJourneyKeepsNativeTargets() = scaledActivityJourney("en", 1f, 90, 90)

    @Test
    fun duplicateDailyPlanSelectionRetainsExactIdentityAndFrozenLinkageAfterReopen() =
        duplicatePlanSelection(PlanExecutionOrigin.DAILY)

    @Test
    fun duplicatePlanWeekSelectionRetainsExactIdentityAndFrozenLinkageAfterReopen() =
        duplicatePlanSelection(PlanExecutionOrigin.PLAN)

    private fun duplicatePlanSelection(origin: PlanExecutionOrigin) =
        journey { f ->
            val source = f.activityTemplate()
            val first = f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.seedTime)
            val chosen = f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.seedTime)
            val weekPlan =
                f.store.plans.createActivityPlanFromTemplate(
                    source.id,
                    PlanSchedule.Week(f.schedule().date.with(java.time.DayOfWeek.MONDAY)),
                    f.seedTime,
                )
            val expected =
                f.store.planReads
                    .getFocusedAction(chosen.id)
                    .identity
            f.launch()
            if (origin == PlanExecutionOrigin.PLAN) f.invoke(R.string.daily_plan)
            val prefix = f.text(R.string.plan_execution_action, source.name, f.text(R.string.plan_start_action))
            val matcher = hasContentDescription(prefix, substring = true) and hasClickAction()
            f.await { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 3 }
            val descriptions =
                compose.onAllNodes(matcher).fetchSemanticsNodes().map {
                    it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
                }
            assertEquals(3, descriptions.toSet().size)
            // Resolve the fixture's expected identity by its test-only tag, then select
            // through the distinct spoken target. Production never resolves by a label.
            val tag = if (origin == PlanExecutionOrigin.DAILY) "daily" else "plan"
            val name =
                compose
                    .onNode(
                        androidx.compose.ui.test
                            .hasTestTag("$tag-plan-action-${chosen.id.value}"),
                    ).fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
                    .single()
            val before = f.store.durableFacts()
            compose
                .onNodeWithContentDescription(
                    name,
                ).performScrollTo()
                .performSemanticsAction(SemanticsActions.RequestFocus)
            assertEquals(before, f.store.durableFacts())
            compose.onNodeWithContentDescription(name).performSemanticsAction(SemanticsActions.OnClick)
            f.await {
                f
                    .host()
                    .planExecutionRouteSessions.activeSession
                    ?.expectedIdentity == expected
            }
            assertEquals(
                expected,
                f
                    .host()
                    .planExecutionRouteSessions.activeSession!!
                    .expectedIdentity,
            )
            assertTrue(first.id != expected.planEntryId)
            f.await {
                f
                    .host()
                    .planExecutionRouteSessions.activeSession
                    ?.controller
                    ?.state
                    ?.value
                    ?.prepared is com.alexandr5476.lifetracing.plan.PlanExecutionLoad.Content
            }
            f.invoke(R.string.plan_start_action)
            f.awaitDailyRuntime()
            val active = f.store.live.getActiveRuntime() as ActiveActivityRuntime
            assertEquals(chosen.id, active.execution.planEntryId)
            assertEquals(chosen.activitySnapshotId, active.snapshot.id)
            assertEquals(first, f.store.plans.getPlan(first.id))
            assertEquals(weekPlan, f.store.plans.getPlan(weekPlan.id))
            assertTrue(
                !f.store.planReads
                    .getFocusedAction(first.id)
                    .engaged,
            )
            f.invoke(R.string.daily_finish)
            f.awaitDailyTerminal()
            f.reopen {
                assertEquals(first, plans.getPlan(first.id))
                assertEquals(weekPlan, plans.getPlan(weekPlan.id))
                assertTrue(!planReads.getFocusedAction(first.id).engaged)
                assertTrue(!planReads.getFocusedAction(weekPlan.id).engaged)
                val saved = plans.getPlan(chosen.id)!!
                assertEquals(PlanEntryStatus.FULFILLED, saved.status)
                assertEquals(active.execution.id, saved.fulfilledActivityExecutionId)
                assertEquals(chosen.id, activity(active.execution.id).planEntryId)
                assertEquals(chosen.activitySnapshotId, activity(active.execution.id).snapshotId)
                assertEquals(1L to 0L, executionCounts())
            }
        }

    private fun scaledActivityJourney(
        language: String,
        font: Float,
        interfaceScale: Int,
        textScale: Int,
    ) = journey { f ->
        val template = f.activityTemplate()
        f.store.library.pin(LibraryTemplateId.Activity(template.id))
        f.launch()
        f.applyAppearance(language, font, interfaceScale, textScale)
        f.selectPinned(template.name)
        f.awaitDailyRuntime()
        f.dailyLanding()
        for (id in listOf(R.string.daily_pause, R.string.daily_resume, R.string.daily_finish)) {
            val node = f.action(f.text(id))
            f.await { compose.onAllNodes(hasText(f.text(id)) and hasClickAction()).fetchSemanticsNodes().size == 1 }
            node.performScrollTo()
            val bounds = node.fetchSemanticsNode().touchBoundsInRoot
            val minimum =
                48 *
                    f
                        .host()
                        .resources.displayMetrics.density
            assertTrue(bounds.width >= minimum - 1 && bounds.height >= minimum - 1)
            f.invoke(id)
        }
        f.awaitDailyTerminal()
        f.openHistoryFact(template.name)
        f.systemBack()
        f.systemBack()
        f.dailyLanding()
        f.reopen {
            assertEquals(1L to 0L, executionCounts())
            assertNull(live.getActiveSession())
        }
    }

    private val noPreflight =
        object : PreflightScheduler {
            override fun schedule(
                duration: Duration,
                onBoundary: () -> Unit,
            ): PreflightHandle = error("Fixture has no pre-start countdown")
        }

    private fun journey(body: (Fixture) -> Unit) =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<RuntimeIsolationApplication>()
            app.withDisposableRuntime { environment ->
                val fixture = Fixture(app) { runBlocking { environment.close() } }
                try {
                    body(fixture)
                } finally {
                    fixture.close()
                }
            }
        }

    private inner class Fixture(
        private val app: RuntimeIsolationApplication,
        private val closeGraph: () -> Unit,
    ) : AutoCloseable {
        val seedTime: Instant = Instant.ofEpochMilli(Instant.now().minusSeconds(10).toEpochMilli())
        private val zone = ZoneId.systemDefault()
        private val day = Instant.now().atZone(zone).toLocalDate()
        var store = RuntimeRecoveryStore(app, "lifetracing.db")
        private var scenario: ActivityScenario<MainActivity>? = null
        private var activity: MainActivity? = null

        fun host() = requireNotNull(activity)

        private var resourceContext: android.content.Context? = null

        fun text(
            id: Int,
            vararg args: Any,
        ) = (resourceContext ?: host()).getString(id, *args)

        fun applyAppearance(
            language: String,
            font: Float,
            interfaceScale: Int,
            textScale: Int,
        ) {
            val configuration =
                android.content.res.Configuration(host().resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                    fontScale = font
                }
            val localized = host().createConfigurationContext(configuration)
            resourceContext = localized
            scenario!!.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(
                        LocalContext provides localized,
                        LocalConfiguration provides configuration,
                        LocalDensity provides Density(activity.resources.displayMetrics.density, font),
                    ) {
                        LifeTracingApp(
                            appearance =
                                AppearancePreferences(
                                    interfaceScalePercent = interfaceScale,
                                    textScalePercent = textScale,
                                ),
                            startActivityRouteSessions = activity.startActivityRouteSessions,
                            activityTemplateEditorRouteSessions = activity.activityTemplateEditorRouteSessions,
                            sequenceTemplateEditorRouteSessions = activity.sequenceTemplateEditorRouteSessions,
                            libraryControllerOwner = activity.libraryControllerOwner,
                            expandedLiveSequenceRouteSessions = activity.expandedLiveSequenceRouteSessions,
                            planControllerOwner = activity.planControllerOwner,
                            planExecutionRouteSessions = activity.planExecutionRouteSessions,
                            historyControllerOwner = activity.historyControllerOwner,
                            statisticsControllerOwner = activity.statisticsControllerOwner,
                            statisticsSeriesDetailRouteSessions = activity.statisticsSeriesDetailRouteSessions,
                            manualActivityEntryRouteSessions = activity.manualActivityEntryRouteSessions,
                            activityHistoryMutationRouteSessions = activity.activityHistoryMutationRouteSessions,
                            sequenceHistoryMutationRouteSessions = activity.sequenceHistoryMutationRouteSessions,
                        )
                    }
                }
            }
            compose.waitForIdle()
        }

        fun schedule() = PlanSchedule.FloatingDay(day)

        fun await(condition: () -> Boolean) = compose.waitUntil(10_000, condition)

        fun action(label: String) = compose.onNode(hasText(label) and hasClickAction())

        fun invoke(id: Int) {
            await {
                compose
                    .onAllNodes(
                        hasText(text(id)) and hasClickAction() and
                            androidx.compose.ui.test
                                .isEnabled(),
                    ).fetchSemanticsNodes()
                    .size == 1
            }
            reachable(action(text(id))).performSemanticsAction(SemanticsActions.OnClick)
            // Let NavDisplay install the destination and its Back handler before the next input.
            compose.waitForIdle()
        }

        // Fixed Material dialog actions have no scroll ancestor; their bodies do.
        private fun reachable(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
            val parent = node.fetchSemanticsNode().parent
            if (generateSequence(parent) { it.parent }.any { it.config.contains(SemanticsActions.ScrollBy) }) {
                node.performScrollTo()
            }
            return node.assertIsDisplayed()
        }

        fun openSequenceActions() {
            val description = text(R.string.expanded_sequence_global_actions)
            await {
                compose
                    .onAllNodes(
                        hasContentDescription(description) and
                            androidx.compose.ui.test
                                .isEnabled(),
                    ).fetchSemanticsNodes()
                    .size == 1 &&
                    host()
                        .expandedLiveSequenceRouteSessions.activeSession
                        ?.controller
                        ?.state
                        ?.value
                        ?.commandInFlight ==
                    false
            }
            reachable(
                compose.onNodeWithContentDescription(description),
            ).performSemanticsAction(SemanticsActions.OnClick)
        }

        fun dailyLanding() {
            await { compose.onAllNodesWithText(text(R.string.daily_title)).fetchSemanticsNodes().size == 1 }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(text(R.string.daily_previous_day)).assertIsFocused()
        }

        fun launch() {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity { activity = it }
            keyboard.enter()
            compose
                .onNodeWithContentDescription(text(R.string.daily_previous_day))
                .performSemanticsAction(SemanticsActions.RequestFocus)
        }

        fun recreate() {
            scenario!!.recreate()
            scenario!!.onActivity { activity = it }
            compose.waitForIdle()
        }

        fun systemBack() {
            compose.waitForIdle()
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        }

        fun selectPinned(name: String) {
            invoke(R.string.daily_start_activity)
            action(text(R.string.launcher_browse_back)).assertIsFocused()
            await { compose.onAllNodes(hasText(name) and hasClickAction()).fetchSemanticsNodes().size == 1 }
            action(name).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        }

        fun awaitDailyRuntime() {
            await {
                host().startActivityRouteSessions.activeSession == null &&
                    host().planExecutionRouteSessions.activeSession == null &&
                    store.live.getActiveSession() != null
            }
            await {
                val state =
                    LifeTracingRuntimeGraph
                        .from(app)
                        .dailyController.state.value
                (state.load as? com.alexandr5476.lifetracing.daily.DailyLoadState.Content)?.daily?.active != null &&
                    !state.commandInFlight
            }
            compose.waitForIdle()
        }

        fun awaitDailyTerminal() {
            await {
                store.live.getActiveSession() == null &&
                    host().expandedLiveSequenceRouteSessions.activeSession == null
            }
            await {
                val state =
                    LifeTracingRuntimeGraph
                        .from(app)
                        .dailyController.state.value
                val daily =
                    when (val load = state.load) {
                        is com.alexandr5476.lifetracing.daily.DailyLoadState.Content -> load.daily
                        is com.alexandr5476.lifetracing.daily.DailyLoadState.Empty -> load.daily
                        else -> null
                    }
                daily != null && daily.active == null && !state.commandInFlight
            }
            compose.waitForIdle()
            noRuntimeActions()
            dailyLanding()
        }

        fun noRuntimeActions() {
            for (id in listOf(
                R.string.daily_pause,
                R.string.daily_resume,
                R.string.daily_finish,
                R.string.daily_expand_sequence,
            )) {
                action(text(id)).assertDoesNotExist()
            }
        }

        fun expand() {
            invoke(R.string.daily_expand_sequence)
            await { host().expandedLiveSequenceRouteSessions.activeSession != null }
            compose.waitForIdle()
            action(text(R.string.expanded_sequence_back)).assertIsFocused()
        }

        fun planAction(
            name: String,
            noLive: Boolean,
        ) = compose.onNodeWithContentDescription(
            text(
                R.string.plan_execution_action,
                name,
                text(if (noLive) R.string.plan_complete_action else R.string.plan_start_action),
            ),
            substring = true,
        )

        fun openPlan(
            name: String,
            noLive: Boolean,
            origin: PlanExecutionOrigin,
            enterOrigin: Boolean = true,
            waitForContent: Boolean = true,
        ) {
            if (origin == PlanExecutionOrigin.PLAN && enterOrigin) invoke(R.string.daily_plan)
            val description =
                text(
                    R.string.plan_execution_action,
                    name,
                    text(if (noLive) R.string.plan_complete_action else R.string.plan_start_action),
                )
            await {
                compose
                    .onAllNodes(
                        hasContentDescription(description, substring = true),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            planAction(name, noLive).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            await { compose.onAllNodesWithText(text(R.string.plan_execution_title)).fetchSemanticsNodes().size == 1 }
            if (waitForContent) {
                await {
                    host()
                        .planExecutionRouteSessions.activeSession
                        ?.controller
                        ?.state
                        ?.value
                        ?.prepared is
                        com.alexandr5476.lifetracing.plan.PlanExecutionLoad.Content
                }
            }
            compose.waitForIdle()
            assertEquals(origin, host().planExecutionRouteSessions.activeSession!!.origin)
            action(text(R.string.plan_back)).assertIsFocused()
        }

        fun activityTemplate(countdown: Duration = Duration.ZERO) =
            store.authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Journey stopwatch",
                    null,
                    TimeTrackingMode.STOPWATCH,
                    null,
                    settings = ActivityTemplateSettings(startCountdown = countdown),
                ),
                createdAt = seedTime,
            )

        fun sequenceTemplate(): com.alexandr5476.lifetracing.domain.SequenceTemplate {
            val activity = activityTemplate()
            return store.authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Journey sequence",
                    null,
                    settings = SequenceTemplateSettings(autoAdvance = false),
                    nodes =
                        (0..1).map { index ->
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    DraftIdentity.New("step-$index"),
                                    index,
                                    StepActivityDraft.FromTemplate(activity.id),
                                ),
                            )
                        },
                ),
                createdAt = seedTime,
            )
        }

        fun openHistoryFact(name: String) {
            invoke(R.string.daily_history)
            await { compose.onAllNodes(hasText(name) and hasClickAction()).fetchSemanticsNodes().size == 1 }
            action(name).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            await {
                host()
                    .activityHistoryMutationRouteSessions.activeSession
                    ?.controller
                    ?.state
                    ?.value
                    ?.load is
                    com.alexandr5476.lifetracing.history.HistoryDetailLoad.Content ||
                    host()
                        .sequenceHistoryMutationRouteSessions.activeSession
                        ?.controller
                        ?.state
                        ?.value
                        ?.load is
                        com.alexandr5476.lifetracing.history.HistoryDetailLoad.Content
            }
            compose.waitForIdle()
        }

        fun roots(reads: RuntimeRecoveryStore) =
            reads.history.getCompletedRoots(
                CompletedHistoryQuery(HistoryDateRange(day, day), 20),
            )

        fun reopen(checks: RuntimeRecoveryStore.() -> Unit) {
            val facts = store.durableFacts()
            val roots = roots(store)
            roots.forEach { root ->
                assertEquals(zone, root.originalZoneId)
                assertEquals(day, root.primaryLocalDate)
            }
            val statistics = store.statistics.global(StatisticsPeriod.AllTime)
            val daily = store.daily.getDaily(DailyQuery(day, Instant.now(), 20, zone))
            scenario?.close()
            scenario = null
            closeGraph()
            store.close()
            store = RuntimeRecoveryStore(app, "lifetracing.db")
            assertEquals(facts, store.durableFacts())
            assertEquals(roots, roots(store))
            assertEquals(statistics, store.statistics.global(StatisticsPeriod.AllTime))
            assertEquals(roots.size.toLong(), statistics.topLevelExecutionCount)
            assertEquals(
                daily.completedHistory,
                store.daily.getDaily(DailyQuery(day, Instant.now(), 20, zone)).completedHistory,
            )
            store.checks()
        }

        override fun close() {
            scenario?.close()
            store.close()
        }
    }
}
