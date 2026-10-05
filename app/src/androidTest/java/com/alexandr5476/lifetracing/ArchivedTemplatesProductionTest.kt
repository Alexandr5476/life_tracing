@file:Suppress("LongMethod", "TooManyFunctions")

package com.alexandr5476.lifetracing

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.data.persistence.HistoryReadRepository
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.PlanReadRepository
import com.alexandr5476.lifetracing.data.persistence.PlanRepository
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
import com.alexandr5476.lifetracing.settings.archivedRowKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

class ArchivedTemplatesProductionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun production_restore_preserves_both_identities_and_frozen_facts_and_refreshes_retained_library() =
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
