@file:Suppress("LongMethod", "TooManyFunctions")

package com.alexandr5476.lifetracing

import android.content.Context
import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigationevent.NavigationEventTransitionState
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.data.persistence.HistoryReadRepository
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.PlanReadRepository
import com.alexandr5476.lifetracing.data.persistence.PlanRepository
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.data.persistence.StatisticsRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.library.LibraryController
import com.alexandr5476.lifetracing.library.LibraryControllerOwner
import com.alexandr5476.lifetracing.library.LibraryLoad
import com.alexandr5476.lifetracing.runtime.RuntimeIsolationApplication
import com.alexandr5476.lifetracing.settings.ArchivedRestoreCoordinator
import com.alexandr5476.lifetracing.settings.ArchivedTemplatesController
import com.alexandr5476.lifetracing.settings.ArchivedTemplatesScreen
import com.alexandr5476.lifetracing.settings.archivedRowKey
import com.alexandr5476.lifetracing.settings.restoreArchivedTemplate
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalTestApi::class)
class ArchivedTemplatesProductionTest {
    // The default unconfined test dispatcher can resume archive IO and apply/layout on a worker.
    // Queue composition continuations for the test clock to drain on the UI thread instead.
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    @Test
    fun route_back_actions_expire_across_transitions_reentry_pause_and_recreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("daily-settings").performScrollTo().performClick()
            val settingsBack = nativeClick("settings-back")
            val openArchive = nativeClick("settings-archived-templates")
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { assertTrue(openArchive()) }
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithTag("settings-page").assertExists()
                compose.onNodeWithTag("archived-page").assertExists()
                compose.runOnIdle {
                    assertFalse(openArchive())
                    assertFalse(settingsBack())
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            awaitLoaded()
            val archiveBack = nativeClick("archived-back")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("settings-page").assertIsDisplayed()
            compose.onNodeWithTag("archived-page").assertDoesNotExist()
            compose.runOnIdle {
                assertFalse(settingsBack())
                assertFalse(openArchive())
            }
            compose.onNodeWithTag("settings-archived-templates").performClick()
            awaitLoaded()
            val pausedBack = nativeClick("archived-back")
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.onActivity { assertFalse(pausedBack()) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle {
                assertFalse(pausedBack())
                assertFalse(archiveBack())
            }
            val preRecreationBack = nativeClick("archived-back")
            scenario.recreate()
            awaitLoaded()
            compose.runOnIdle { assertFalse(preRecreationBack()) }
            compose.onNodeWithTag("archived-back").performClick()
            compose.onNodeWithTag("settings-page").assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("daily-settings").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("settings-page").assertDoesNotExist()
            compose.onNodeWithTag("daily-settings").performClick()
            compose.runOnIdle { assertFalse(settingsBack()) }
            compose.onNodeWithTag("settings-back").performClick()
            compose.onNodeWithTag("daily-settings").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("settings-page").assertDoesNotExist()
        }
    }

    @Test
    fun predictive_back_started_on_settings_cannot_pop_the_archive_opened_during_the_gesture() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("daily-settings").performScrollTo().performClick()
            val openArchive = nativeClick("settings-archived-templates")
            scenario.onActivity { activity ->
                val start = BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)
                activity.onBackPressedDispatcher.dispatchOnBackStarted(start)
                assertTrue(openArchive())
            }
            // Apply the new stack to NavDisplay. The gesture can still be showing its Back preview.
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val transition = activity.navigationEventDispatcher.transitionState.value
                assertTrue(transition is NavigationEventTransitionState.InProgress)
                assertEquals(
                    NavigationEventTransitionState.TRANSITIONING_BACK,
                    (transition as NavigationEventTransitionState.InProgress).direction,
                )
                activity.onBackPressedDispatcher.onBackPressed()
            }
            awaitLoaded()
            compose.onNodeWithTag("archived-page").assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("settings-page").assertIsDisplayed()
            compose.onNodeWithTag("archived-page").assertDoesNotExist()
        }
    }

    @Test
    fun archive_read_resumes_on_ui_thread_after_worker_completion() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resumedOnMain = AtomicReference<Boolean>()
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        val scope = rememberCoroutineScope()
                        remember<ArchivedTemplatesController>(scope) {
                            ArchivedTemplatesController(
                                readArchived = {
                                    withContext(Dispatchers.IO) {
                                        entered.complete(Unit)
                                        release.await()
                                    }
                                    resumedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                                    emptyList()
                                },
                                restoreTemplate = {},
                                onRestored = {},
                                scope = scope,
                                restores = ArchivedRestoreCoordinator(),
                            )
                        }
                    }
                }
                compose.waitUntil(5_000) { entered.isCompleted }
                // Resume from a real worker after the composition coroutine has suspended.
                runBlocking(Dispatchers.IO) { release.complete(Unit) }
                compose.waitUntil(5_000) { resumedOnMain.get() != null }
                assertTrue("Archive reads must return to the UI dispatcher", resumedOnMain.get())
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun accepted_restore_finishes_repository_refresh_and_reconciliation_after_screen_disposal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository.create(context)
        val at = Instant.ofEpochMilli(System.currentTimeMillis())
        val template =
            TemplateAuthoringRepository.create(context).createActivityTemplate(
                ActivityTemplateDraft("Disposed Restore $at", null, TimeTrackingMode.NO_LIVE_TRACKING, null),
                createdAt = at,
            )
        val id = LibraryTemplateId.Activity(template.id)
        repository.archiveActivityTemplate(template.id, at.plusSeconds(1))
        val release = CompletableDeferred<Unit>()
        val mounted = mutableStateOf(true)
        val requests = AtomicInteger()
        val reads = AtomicInteger()
        val activeController = AtomicReference<ArchivedTemplatesController>()
        val retainedLibrary = AtomicReference<LibraryController>()
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                val graph = LifeTracingRuntimeGraph.from(context)
                scenario.onActivity { activity ->
                    val owner = ViewModelProvider(activity)[LibraryControllerOwner::class.java]
                    retainedLibrary.set(owner.get(graph::createLibraryController))
                    activity.setContent {
                        if (mounted.value) {
                            val scope = rememberCoroutineScope()
                            val controller =
                                remember(scope) {
                                    ArchivedTemplatesController(
                                        readArchived = {
                                            reads.incrementAndGet()
                                            withContext(Dispatchers.IO) { repository.getArchived() }
                                        },
                                        restoreTemplate = { target ->
                                            requests.incrementAndGet()
                                            release.await()
                                            withContext(Dispatchers.IO) { restoreArchivedTemplate(repository, target) }
                                        },
                                        onRestored = owner::refreshIfInitialized,
                                        scope = scope,
                                        restores = graph.archivedRestores,
                                    )
                                }
                            SideEffect { activeController.set(controller) }
                            val state by controller.state.collectAsState()
                            val restoreStatus by graph.archivedRestores.status.collectAsState()
                            LifeTracingAppearance(AppearancePreferences()) {
                                ArchivedTemplatesScreen(
                                    state,
                                    controller::restore,
                                    controller::reload,
                                    controller::retryRestore,
                                    {},
                                    readCurrent = { controller.state.value },
                                    restoreStatus = restoreStatus,
                                    readRestoreStatus = { graph.archivedRestores.status.value },
                                )
                            }
                        }
                    }
                }
                val library = retainedLibrary.get()
                val loaded =
                    runBlocking {
                        withTimeout(5_000) { library.state.first { it.browse is LibraryLoad.Content } }
                    }
                val initialContents = (loaded.browse as LibraryLoad.Content).value.contents
                assertFalse(initialContents.activities.any { it.id == id })
                awaitLoaded()
                scrollToRow(id)
                val original = activeController.get()
                val restore = nativeClick("archived-restore-${id.archivedRowKey()}")
                compose.runOnIdle {
                    assertTrue(restore())
                    assertEquals(id, original.state.value.pending)
                    assertFalse(restore())
                    mounted.value = false
                }
                compose.onNodeWithTag("archived-page").assertDoesNotExist()
                assertTrue(repository.getArchived().any { it.id == id })
                compose.runOnIdle { mounted.value = true }
                awaitLoaded()
                assertEquals(2, reads.get())
                val replacement = activeController.get()
                assertNotSame(original, replacement)
                val blocked = attemptPendingRestore(id)
                compose.runOnIdle { assertFalse(restore()) }
                assertEquals(1, requests.get())

                release.complete(Unit)
                compose.waitUntil(5_000) {
                    val state = original.state.value
                    val next = replacement.state.value
                    state.canRestore &&
                        state.items?.none { it.id == id } == true &&
                        next.canRestore &&
                        next.items?.none { it.id == id } == true
                }
                assertEquals(4, reads.get())
                assertEquals(template, TemplateAuthoringRepository.create(context).getActivityTemplate(template.id))
                runBlocking { awaitLibraryContains(library, id) }
                compose.runOnIdle { mounted.value = false }
                compose.onNodeWithTag("archived-page").assertDoesNotExist()
                compose.runOnIdle { mounted.value = true }
                awaitLoaded()
                assertEquals(5, reads.get())
                val reopened = activeController.get().state.value
                assertTrue(reopened.items?.none { it.id == id } == true)
                compose.runOnIdle {
                    assertFalse(restore())
                    assertFalse(blocked())
                }
                assertEquals(1, requests.get())
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun production_archive_keeps_restore_reserved_across_activity_recreation_and_rereads_on_completion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository.create(context)
        val at = Instant.ofEpochMilli(System.currentTimeMillis())
        val template =
            TemplateAuthoringRepository.create(context).createActivityTemplate(
                ActivityTemplateDraft("Recreated Archive $at", null, TimeTrackingMode.NO_LIVE_TRACKING, null),
                createdAt = at,
            )
        val id = LibraryTemplateId.Activity(template.id)
        repository.archiveActivityTemplate(template.id, at.plusSeconds(1))
        val releaseWrite = CompletableDeferred<Unit>()
        val reconciling = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requests = AtomicInteger()
        val reads = AtomicInteger()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val owner = AtomicReference<LibraryControllerOwner>()
                scenario.onActivity { owner.set(it.libraryControllerOwner) }
                val original =
                    ArchivedTemplatesController(
                        readArchived = {
                            if (reads.incrementAndGet() == 2) {
                                reconciling.complete(Unit)
                                releaseRead.await()
                            }
                            withContext(Dispatchers.IO) { repository.getArchived() }
                        },
                        restoreTemplate = { target ->
                            requests.incrementAndGet()
                            releaseWrite.await()
                            withContext(Dispatchers.IO) { restoreArchivedTemplate(repository, target) }
                        },
                        onRestored = owner.get()::refreshIfInitialized,
                        scope = scope,
                        restores = LifeTracingRuntimeGraph.from(context).archivedRestores,
                    )
                compose.waitUntil(5_000) { original.state.value.canRestore }
                scenario.onActivity {
                    original.restore(id)
                    assertEquals(id, original.state.value.pending)
                    scope.cancel()
                }
                openArchives()
                val beforeRecreation = attemptPendingRestore(id)
                scenario.recreate()
                awaitLoaded()
                attemptPendingRestore(id)
                compose.runOnIdle { assertFalse(beforeRecreation()) }

                releaseWrite.complete(Unit)
                compose.waitUntil(5_000) { reconciling.isCompleted }
                assertTrue(repository.getArchived().none { it.id == id })
                attemptPendingRestore(id)
                releaseRead.complete(Unit)
                val rowTag = "archived-row-${id.archivedRowKey()}"
                compose.waitUntil(5_000) { compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isEmpty() }
                awaitLoaded()
                assertEquals(listOf(1, 2), listOf(requests.get(), reads.get()))
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("settings-page").assertIsDisplayed()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("daily-settings").performScrollTo().assertIsDisplayed()
            }
        } finally {
            releaseWrite.complete(Unit)
            releaseRead.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun production_restore_preserves_both_identities_and_frozen_facts_and_refreshes_retained_library(): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val library = LibraryRepository.create(context)
            val authoring = TemplateAuthoringRepository.create(context)
            val live = LiveSessionRepository.create(context)
            clearLive(live)
            val at = Instant.ofEpochMilli(System.currentTimeMillis()).minusSeconds(600)
            val activity =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft("S3 Activity $at", "Preserved comment", TimeTrackingMode.STOPWATCH, null),
                    createdAt = at,
                )
            val sequence =
                authoring.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "S3 Sequence $at",
                        "Preserved sequence",
                        nodes =
                            listOf(
                                SequenceNodeDraft.Step(
                                    ActivityStepDraft(
                                        DraftIdentity.New("s3-step"),
                                        0,
                                        StepActivityDraft.FromTemplate(activity.id),
                                    ),
                                ),
                            ),
                    ),
                    createdAt = at.plusSeconds(1),
                )
            val activityId = LibraryTemplateId.Activity(activity.id)
            val sequenceId = LibraryTemplateId.Sequence(sequence.id)
            val ids = setOf(activityId, sequenceId)
            val plans = PlanRepository.create(context)
            val schedule = PlanSchedule.FloatingDay(LocalDate.now())
            val activityPlan = plans.createActivityPlanFromTemplate(activity.id, schedule, at.plusSeconds(2))
            val sequencePlan = plans.createSequencePlanFromTemplate(sequence.id, schedule, at.plusSeconds(3))
            val activityStartedAt = at.plusSeconds(10)
            val execution =
                library.startActivityFromTemplate(
                    activity.id,
                    startedAt = activityStartedAt,
                    createdAt = activityStartedAt,
                    zoneId = ZoneOffset.UTC,
                )
            live.completeActiveActivity(at.plusSeconds(12))
            val sequenceStartedAt = at.plusSeconds(20)
            val sequenceRuntime =
                library.startSequenceFromTemplate(
                    sequence.id,
                    startedAt = sequenceStartedAt,
                    createdAt = sequenceStartedAt,
                    zoneId = ZoneOffset.UTC,
                )
            live.endSequenceEarly(at.plusSeconds(22))
            val history = HistoryReadRepository.create(context)
            val activityFact = requireNotNull(history.getActivityDetail(execution.id))
            val sequenceFact = requireNotNull(history.getSequenceDetail(sequenceRuntime.execution.id))
            val sequenceAuthoring = requireNotNull(authoring.getSequenceTemplateAuthoringState(sequence.id))
            val stepSnapshots = sequenceAuthoring.activitySnapshots
            val planReads = PlanReadRepository.create(context)
            val activityPlanAction = planReads.getFocusedAction(activityPlan.id)
            val sequencePlanAction = planReads.getFocusedAction(sequencePlan.id)
            assertEquals(PlanSourceState.CURRENT, activityPlanAction.sourceState)
            assertEquals(PlanSourceState.CURRENT, sequencePlanAction.sourceState)
            val activityPlanSnapshot = activityPlanAction.snapshot
            val sequencePlanSnapshot = sequencePlanAction.snapshot

            library.archiveActivityTemplate(activity.id, at.plusSeconds(30))
            library.archiveSequenceTemplate(sequence.id, at.plusSeconds(30))
            val archivedPlanReads = PlanReadRepository.create(context)
            val archivedActivityPlanAction = archivedPlanReads.getFocusedAction(activityPlan.id)
            val archivedSequencePlanAction = archivedPlanReads.getFocusedAction(sequencePlan.id)
            assertEquals(PlanSourceState.ARCHIVED, archivedActivityPlanAction.sourceState)
            assertEquals(PlanSourceState.ARCHIVED, archivedSequencePlanAction.sourceState)
            assertEquals(activityPlanAction.identity, archivedActivityPlanAction.identity)
            assertEquals(sequencePlanAction.identity, archivedSequencePlanAction.identity)
            assertEquals(activityPlanSnapshot, archivedActivityPlanAction.snapshot)
            assertEquals(sequencePlanSnapshot, archivedSequencePlanAction.snapshot)
            val freshAuthoring = TemplateAuthoringRepository.create(context)
            val archivedActivity = requireNotNull(freshAuthoring.getActivityTemplate(activity.id))
            val archivedSequence = requireNotNull(freshAuthoring.getSequenceTemplate(sequence.id))
            val archivedRows = LibraryRepository.create(context).getArchived().filter { it.id in ids }
            assertEquals(ids, archivedRows.map { it.id }.toSet())
            assertTrue(LibraryRepository.create(context).getAll().none { it.id in ids })
            ids.forEach { id ->
                assertThrows(IllegalArgumentException::class.java) {
                    LibraryRepository.create(context).getLaunchTarget(id)
                }
            }
            val archivedSource = freshAuthoring.getActivityTemplateSourceStatuses(listOf(activity.id))
            assertTrue(archivedSource.getValue(activity.id).isArchived)
            assertSource(context, activity.statisticsSeriesId, StatisticsSeriesSourceState.ARCHIVED_SOURCE)
            assertSource(context, sequence.statisticsSeriesId, StatisticsSeriesSourceState.ARCHIVED_SOURCE)
            val seriesBefore = seriesIds(context)

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose
                    .onNodeWithText(resource(scenario, R.string.daily_library))
                    .performScrollTo()
                    .performClick()
                compose.onNodeWithText(resource(scenario, R.string.library_new_activity)).assertIsDisplayed()
                val owner = AtomicReference<LibraryControllerOwner>()
                val retained = AtomicReference<LibraryController>()
                scenario.onActivity {
                    owner.set(it.libraryControllerOwner)
                    retained.set(it.libraryControllerOwner.get { error("Production Library must be initialized") })
                }
                withTimeout(5_000) { retained.get().state.first { it.browse is LibraryLoad.Content } }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                openArchives()
                ids.forEach(::scrollToRow)
                scenario.recreate()
                awaitLoaded()
                ids.forEach(::scrollToRow)
                val afterRecreation = LibraryRepository.create(context).getArchived().filter { it.id in ids }
                assertEquals(ids, afterRecreation.map { it.id }.toSet())

                restoreRow(activityId)
                awaitLibraryContains(retained.get(), activityId)
                assertEquals(
                    archivedActivity.copy(deletedAt = null),
                    TemplateAuthoringRepository.create(context).getActivityTemplate(activity.id),
                )
                assertSource(context, activity.statisticsSeriesId, StatisticsSeriesSourceState.ACTIVE_SOURCE)
                restoreRow(sequenceId)
                awaitLibraryContains(retained.get(), sequenceId)
                assertEquals(
                    archivedSequence.copy(deletedAt = null),
                    TemplateAuthoringRepository.create(context).getSequenceTemplate(sequence.id),
                )
                assertSource(context, sequence.statisticsSeriesId, StatisticsSeriesSourceState.ACTIVE_SOURCE)
                val reloaded = LibraryRepository.create(context)
                assertTrue(reloaded.getArchived().none { it.id in ids })
                archivedRows.forEach { row ->
                    assertEquals(row.copy(archivedAt = null), reloaded.getAll().single { it.id == row.id })
                    assertEquals(row.id, reloaded.getLaunchTarget(row.id).id)
                }
                val afterAuthoring = TemplateAuthoringRepository.create(context)
                val activeSource = afterAuthoring.getActivityTemplateSourceStatuses(listOf(activity.id))
                assertFalse(activeSource.getValue(activity.id).isArchived)
                val afterSequence = requireNotNull(afterAuthoring.getSequenceTemplateAuthoringState(sequence.id))
                assertEquals(stepSnapshots, afterSequence.activitySnapshots)
                val afterHistory = HistoryReadRepository.create(context)
                assertEquals(activityFact, afterHistory.getActivityDetail(execution.id))
                assertEquals(sequenceFact, afterHistory.getSequenceDetail(sequenceRuntime.execution.id))
                val afterPlans = PlanRepository.create(context)
                assertEquals(activityPlan, afterPlans.getPlan(activityPlan.id))
                assertEquals(sequencePlan, afterPlans.getPlan(sequencePlan.id))
                val afterPlanReads = PlanReadRepository.create(context)
                val restoredActivityPlanAction = afterPlanReads.getFocusedAction(activityPlan.id)
                val restoredSequencePlanAction = afterPlanReads.getFocusedAction(sequencePlan.id)
                assertEquals(PlanSourceState.CURRENT, restoredActivityPlanAction.sourceState)
                assertEquals(PlanSourceState.CURRENT, restoredSequencePlanAction.sourceState)
                assertEquals(activityPlanAction.identity, restoredActivityPlanAction.identity)
                assertEquals(sequencePlanAction.identity, restoredSequencePlanAction.identity)
                assertEquals(activityPlanSnapshot, restoredActivityPlanAction.snapshot)
                assertEquals(sequencePlanSnapshot, restoredSequencePlanAction.snapshot)
                assertEquals(seriesBefore, seriesIds(context))
                assertNull(LiveSessionRepository.create(context).getActiveSession())
                scenario.onActivity {
                    assertSame(owner.get(), it.libraryControllerOwner)
                    val afterController = it.libraryControllerOwner.get { error("Must retain Library controller") }
                    assertSame(retained.get(), afterController)
                    it.onBackPressedDispatcher.onBackPressed()
                }
                compose.onNodeWithTag("settings-archived-templates").performScrollTo().assertIsDisplayed()
            }
        }

    @Test
    fun production_restore_routes_equal_raw_ids_by_kind_and_preserves_both_templates(): Unit =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<RuntimeIsolationApplication>()
            app.withDisposableRuntime {
                val rawId = UUID.randomUUID().toString()
                val at = Instant.ofEpochMilli(System.currentTimeMillis()).minusSeconds(60)
                val name = "Shared archive name"
                val archivedAt = at.plusSeconds(5)
                RuntimeRecoveryStore(app, "lifetracing.db").use { seed ->
                    val authoring = seed.authoringWithSharedTemplateId(rawId)
                    val activityDraft = ActivityTemplateDraft(name, null, TimeTrackingMode.NO_LIVE_TRACKING, null)
                    val initial = authoring.createActivityTemplate(activityDraft, createdAt = at)
                    val activity =
                        authoring.saveActivityTemplate(
                            initial.id,
                            initial.revision,
                            activityDraft.copy(shortComment = "Revised activity"),
                            at.plusSeconds(1),
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
                                                DraftIdentity.New("shared-id-step"),
                                                0,
                                                StepActivityDraft.FromTemplate(activity.id),
                                            ),
                                        ),
                                    ),
                            ),
                            createdAt = at.plusSeconds(2),
                        )
                    val activityId = LibraryTemplateId.Activity(activity.id)
                    val sequenceId = LibraryTemplateId.Sequence(sequence.id)
                    val ids = setOf(activityId, sequenceId)
                    assertEquals(listOf(rawId, rawId), listOf(activityId.value, sequenceId.value))
                    assertEquals(2, ids.size)
                    assertNotEquals(activity.statisticsSeriesId, sequence.statisticsSeriesId)
                    val schedule = PlanSchedule.FloatingDay(LocalDate.now().plusDays(1))
                    val plans =
                        listOf(
                            seed.plans.createActivityPlanFromTemplate(activity.id, schedule, at.plusSeconds(3)),
                            seed.plans.createSequencePlanFromTemplate(sequence.id, schedule, at.plusSeconds(3)),
                        )
                    val planActions = plans.map { seed.planReads.getFocusedAction(it.id) }
                    val completedAt = at.plusSeconds(4)
                    val execution =
                        seed.library.completeNoLiveActivityFromTemplate(
                            activity.id,
                            completedAt,
                            completedAt,
                            ZoneOffset.UTC,
                        )
                    val history = requireNotNull(seed.history.getActivityDetail(execution.id))
                    val frozenSequence = requireNotNull(authoring.getSequenceTemplateAuthoringState(sequence.id))

                    seed.library.archiveActivityTemplate(activity.id, archivedAt)
                    seed.library.archiveSequenceTemplate(sequence.id, archivedAt)
                    val archive = LibraryRepository.create(app).getArchived()
                    assertEquals(ids, archive.map { it.id }.toSet())
                    val archivedActivity = archive.single { it.id == activityId }
                    val archivedSequence = archive.single { it.id == sequenceId }

                    ActivityScenario.launch(MainActivity::class.java).use {
                        openArchives()
                        scrollToRow(activityId)
                        compose.onNodeWithTag("archived-restore-${activityId.archivedRowKey()}").assertIsEnabled()
                        restoreRow(sequenceId)

                        val afterFirst = LibraryRepository.create(app)
                        assertEquals(listOf(archivedActivity), afterFirst.getArchived())
                        assertEquals(listOf(archivedSequence.copy(archivedAt = null)), afterFirst.getAll())
                        scrollToRow(activityId)
                        compose.onNodeWithTag("archived-restore-${activityId.archivedRowKey()}").assertIsEnabled()
                        restoreRow(activityId)
                        awaitLoaded()
                    }

                    RuntimeRecoveryStore(app, "lifetracing.db").use { reopened ->
                        assertTrue(reopened.library.getArchived().isEmpty())
                        val restoredRows = archive.map { it.copy(archivedAt = null) }.toSet()
                        assertEquals(restoredRows, reopened.library.getAll().toSet())
                        assertEquals(activity, reopened.authoring.getActivityTemplate(activity.id))
                        assertEquals(sequence, reopened.authoring.getSequenceTemplate(sequence.id))
                        assertEquals(frozenSequence, reopened.authoring.getSequenceTemplateAuthoringState(sequence.id))
                        plans.zip(planActions).forEach { (plan, original) ->
                            assertEquals(plan, reopened.plans.getPlan(plan.id))
                            val current = reopened.planReads.getFocusedAction(plan.id)
                            assertEquals(PlanSourceState.CURRENT, current.sourceState)
                            assertEquals(original.identity, current.identity)
                            assertEquals(original.snapshot, current.snapshot)
                        }
                        assertEquals(history, reopened.history.getActivityDetail(execution.id))
                        assertSource(app, activity.statisticsSeriesId, StatisticsSeriesSourceState.ACTIVE_SOURCE)
                        assertSource(app, sequence.statisticsSeriesId, StatisticsSeriesSourceState.ACTIVE_SOURCE)
                    }
                }
            }
        }

    @Test
    fun stale_production_row_converges_after_another_actor_restores_without_a_duplicate_template_or_series() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val at = Instant.ofEpochMilli(System.currentTimeMillis())
        val template =
            TemplateAuthoringRepository.create(context).createActivityTemplate(
                ActivityTemplateDraft("S3 Stale $at", null, TimeTrackingMode.NO_LIVE_TRACKING, null),
                createdAt = at,
            )
        val id = LibraryTemplateId.Activity(template.id)
        LibraryRepository.create(context).archiveActivityTemplate(template.id, at.plusSeconds(1))
        val seriesBefore = seriesIds(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            openArchives()
            scrollToRow(id)
            LibraryRepository.create(context).restoreActivityTemplate(template.id)
            compose.onNodeWithTag("archived-row-${id.archivedRowKey()}").assertExists()
            restoreRow(id)
            assertEquals(template, TemplateAuthoringRepository.create(context).getActivityTemplate(template.id))
            assertEquals(1, LibraryRepository.create(context).getAll().count { it.id == id })
            assertTrue(LibraryRepository.create(context).getArchived().none { it.id == id })
            assertEquals(seriesBefore, seriesIds(context))
            assertSource(context, template.statisticsSeriesId, StatisticsSeriesSourceState.ACTIVE_SOURCE)
        }
    }

    private fun openArchives() {
        compose.onNodeWithTag("daily-settings").performScrollTo().performClick()
        compose.onNodeWithTag("settings-archived-templates").performScrollTo().performClick()
        awaitLoaded()
    }

    private fun nativeClick(tag: String): () -> Boolean {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return requireNotNull(node.config[SemanticsActions.OnClick].action)
    }

    private fun awaitLoaded() {
        compose.waitUntil(5_000) {
            val pages = compose.onAllNodesWithTag("archived-page").fetchSemanticsNodes()
            val loading = compose.onAllNodesWithTag("archived-loading").fetchSemanticsNodes()
            pages.size == 1 && loading.isEmpty()
        }
    }

    private fun scrollToRow(id: LibraryTemplateId) {
        compose.onNodeWithTag("archived-page").performScrollToNode(hasTestTag("archived-row-${id.archivedRowKey()}"))
    }

    private fun attemptPendingRestore(id: LibraryTemplateId): () -> Boolean {
        val tag = "archived-restore-${id.archivedRowKey()}"
        compose.onNodeWithTag("archived-page").performScrollToNode(hasTestTag(tag))
        val control = compose.onNodeWithTag(tag).assertIsNotEnabled()
        assertTrue(control.fetchSemanticsNode().config.contains(SemanticsProperties.StateDescription))
        control.performTouchInput { click() }
        val action = nativeClick(tag)
        compose.runOnIdle { assertFalse(action()) }
        return action
    }

    private fun restoreRow(id: LibraryTemplateId) {
        scrollToRow(id)
        compose.onNodeWithTag("archived-restore-${id.archivedRowKey()}").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("archived-row-${id.archivedRowKey()}").fetchSemanticsNodes().isEmpty()
        }
    }

    private suspend fun awaitLibraryContains(
        controller: LibraryController,
        id: LibraryTemplateId,
    ) {
        withTimeout(5_000) {
            controller.state.first {
                val browse = (it.browse as? LibraryLoad.Content)?.value
                browse != null && (browse.contents.activities + browse.contents.sequences).any { row -> row.id == id }
            }
        }
    }

    private fun seriesIds(context: Context): Set<StatisticsSeriesId> {
        val catalog = StatisticsRepository.create(context).seriesCatalog()
        return catalog.map { it.id }.toSet()
    }

    private fun assertSource(
        context: Context,
        id: StatisticsSeriesId,
        expected: StatisticsSeriesSourceState,
    ) {
        val matching = StatisticsRepository.create(context).seriesCatalog().filter { it.id == id }
        assertEquals(1, matching.size)
        assertEquals(expected, matching.single().sourceState)
    }

    private fun resource(
        scenario: ActivityScenario<MainActivity>,
        id: Int,
    ): String {
        val value = AtomicReference<String>()
        scenario.onActivity { value.set(it.getString(id)) }
        return value.get()
    }

    private fun clearLive(live: LiveSessionRepository) {
        when (live.getActiveSession()?.kind) {
            ActiveSessionKind.ACTIVITY -> live.completeActiveActivity(Instant.now())
            ActiveSessionKind.SEQUENCE -> live.endSequenceEarly(Instant.now())
            null -> Unit
        }
    }
}
