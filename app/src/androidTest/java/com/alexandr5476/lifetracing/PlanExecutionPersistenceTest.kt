package com.alexandr5476.lifetracing

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.PlanReadRepository
import com.alexandr5476.lifetracing.data.persistence.PlanRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.plan.PlanExecutionCommandState
import com.alexandr5476.lifetracing.plan.PlanExecutionController
import com.alexandr5476.lifetracing.plan.PlanExecutionLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class PlanExecutionPersistenceTest {
    @Test
    fun realCountdownCommitsOnceZeroBypassesPreflightAndAbandonedPreflightIsNotRecoverable() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val live = LiveSessionRepository.create(context)
            clearLiveSession(live, Instant.now())
            val authoring = TemplateAuthoringRepository.create(context)
            val plans = PlanRepository.create(context) { ZoneOffset.UTC }
            val reads = PlanReadRepository.create(context) { ZoneOffset.UTC }
            val library = LibraryRepository.create(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val at = Instant.ofEpochMilli(Instant.now().toEpochMilli())
            try {
                val countdownTemplate =
                    authoring.createActivityTemplate(
                        ActivityTemplateDraft(
                            "Plan countdown ${at.toEpochMilli()}",
                            null,
                            TimeTrackingMode.STOPWATCH,
                            null,
                            ActivityTemplateSettings(startCountdown = Duration.ofSeconds(3)),
                        ),
                        createdAt = at,
                    )
                val countdownPlan =
                    plans.createActivityPlanFromTemplate(
                        countdownTemplate.id,
                        PlanSchedule.FloatingDay(LocalDate.of(2026, 9, 15)),
                        createdAt = at,
                    )
                val scheduler = CapturingScheduler()
                val controller = controller(scope, countdownPlan.id, reads, live, at, scheduler)
                controller.awaitPrepared()
                controller.launch()
                controller.awaitPreflight()

                assertNull(live.getActiveSession())
                assertFalse(reads.getFocusedAction(countdownPlan.id).engaged)
                assertTrue(library.getRecent(100).none { it.id == LibraryTemplateId.Activity(countdownTemplate.id) })

                scheduler.fireTwice()
                controller.awaitCommitted()
                val runtime = live.getActiveRuntime() as ActiveActivityRuntime
                assertEquals(countdownPlan.id, runtime.execution.planEntryId)
                assertEquals(countdownPlan.activitySnapshotId, runtime.snapshot.id)
                assertEquals(PlanEntryStatus.PLANNED, reads.getFocusedAction(countdownPlan.id).identity.status)
                assertTrue(reads.getFocusedAction(countdownPlan.id).engaged)
                assertEquals(
                    at,
                    library
                        .getRecent(100)
                        .single { it.id == LibraryTemplateId.Activity(countdownTemplate.id) }
                        .lastUsedAt,
                )
                live.completeActiveActivity(at.plusSeconds(1))
                controller.close()

                val zeroTemplate =
                    authoring.createActivityTemplate(
                        ActivityTemplateDraft("Plan zero ${at.toEpochMilli()}", null, TimeTrackingMode.STOPWATCH, null),
                        createdAt = at,
                    )
                val zeroPlan =
                    plans.createActivityPlanFromTemplate(
                        zeroTemplate.id,
                        PlanSchedule.FloatingDay(LocalDate.of(2026, 9, 15)),
                        at,
                    )
                val zeroScheduler = CapturingScheduler()
                val zeroController = controller(scope, zeroPlan.id, reads, live, at, zeroScheduler)
                zeroController.awaitPrepared()
                zeroController.launch()
                zeroController.awaitCommitted()
                assertTrue(zeroScheduler.callbacks.isEmpty())
                assertEquals(zeroPlan.id, (live.getActiveRuntime() as ActiveActivityRuntime).execution.planEntryId)
                live.completeActiveActivity(at.plusSeconds(2))
                zeroController.close()

                val abandonedPlan =
                    plans.createActivityPlanFromTemplate(
                        countdownTemplate.id,
                        PlanSchedule.FloatingDay(LocalDate.of(2026, 9, 16)),
                        at.plusSeconds(3),
                    )
                val abandonedScheduler = CapturingScheduler()
                val abandoned = controller(scope, abandonedPlan.id, reads, live, at, abandonedScheduler)
                abandoned.awaitPrepared()
                abandoned.launch()
                abandoned.awaitPreflight()
                abandonedScheduler.awaitScheduled()
                abandoned.close()
                abandonedScheduler.fireTwice()

                val fresh = controller(scope, abandonedPlan.id, reads, live, at, CapturingScheduler())
                fresh.awaitPrepared()
                assertNull(live.getActiveRuntime())
                assertFalse(reads.getFocusedAction(abandonedPlan.id).engaged)
                assertEquals(PlanExecutionCommandState.Idle, fresh.state.value.command)
                fresh.close()
            } finally {
                clearLiveSession(live, at.plusSeconds(10))
                scope.cancel()
            }
        }

    @Test
    fun activitySourceDivergenceDuringRealPreflightKeepsAndExecutesTheFrozenPlan() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val live = LiveSessionRepository.create(context)
            val authoring = TemplateAuthoringRepository.create(context)
            val plans = PlanRepository.create(context) { ZoneOffset.UTC }
            val reads = PlanReadRepository.create(context) { ZoneOffset.UTC }
            val library = LibraryRepository.create(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val base = Instant.ofEpochMilli(Instant.now().toEpochMilli())
            try {
                SourceMutation.entries.forEachIndexed { index, mutation ->
                    val at = base.plusSeconds(index * 10L)
                    clearLiveSession(live, at)
                    val template =
                        authoring.createActivityTemplate(
                            ActivityTemplateDraft(
                                "S3C2 source $mutation ${base.toEpochMilli()}",
                                null,
                                TimeTrackingMode.STOPWATCH,
                                null,
                                ActivityTemplateSettings(startCountdown = Duration.ofSeconds(3)),
                            ),
                            createdAt = at,
                        )
                    val plan =
                        plans.createActivityPlanFromTemplate(
                            template.id,
                            PlanSchedule.FloatingDay(LocalDate.of(2026, 9, 15).plusDays(index.toLong())),
                            at.plusMillis(100),
                        )
                    val original = reads.getFocusedAction(plan.id)
                    val scheduler = CapturingScheduler()
                    val controller = controller(scope, plan.id, reads, live, at.plusSeconds(1), scheduler)
                    controller.awaitPrepared()
                    controller.launch()
                    controller.awaitPreflight()

                    when (mutation) {
                        SourceMutation.CHANGE ->
                            authoring.saveActivityTemplate(
                                template.id,
                                template.revision,
                                template
                                    .toAuthoringDraft()
                                    .copy(settings = ActivityTemplateSettings(startCountdown = Duration.ofSeconds(9))),
                                at.plusSeconds(2),
                            )
                        SourceMutation.ARCHIVE -> library.archiveActivityTemplate(template.id, at.plusSeconds(2))
                        SourceMutation.DELETE -> deleteActivityTemplate(context, template.id)
                    }

                    scheduler.fireTwice()
                    controller.awaitCommitted()

                    val runtime = live.getActiveRuntime() as ActiveActivityRuntime
                    val retained = reads.getFocusedAction(plan.id)
                    val originalSnapshot = (original.snapshot as FocusedPlanAction.Snapshot.Activity).value
                    val retainedSnapshot = (retained.snapshot as FocusedPlanAction.Snapshot.Activity).value
                    assertEquals(original.identity, retained.identity)
                    assertEquals(plan.activitySnapshotId, runtime.snapshot.id)
                    assertEquals(
                        originalSnapshot.copy(sourceTemplateId = retainedSnapshot.sourceTemplateId),
                        retainedSnapshot,
                    )
                    assertEquals(retainedSnapshot, runtime.snapshot)
                    assertEquals(mutation.sourceState, retained.sourceState)
                    assertEquals(1L, countPlanActivityExecutions(context, plan.id.value))
                    if (mutation == SourceMutation.DELETE) {
                        assertNull(authoring.getActivityTemplate(template.id))
                        assertEquals(0L, countActivityTemplates(context, template.id.value))
                    }
                    live.completeActiveActivity(at.plusSeconds(3))
                    controller.close()
                }
            } finally {
                clearLiveSession(live, base.plusSeconds(60))
                scope.cancel()
            }
        }

    @Test
    fun identityChangingPlanMutationsDuringRealPreflightRejectTheOriginalActionAsStale() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val live = LiveSessionRepository.create(context)
            val authoring = TemplateAuthoringRepository.create(context)
            val plans = PlanRepository.create(context) { ZoneOffset.UTC }
            val reads = PlanReadRepository.create(context) { ZoneOffset.UTC }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val base = Instant.ofEpochMilli(Instant.now().toEpochMilli())
            try {
                PlanMutation.entries.forEachIndexed { index, mutation ->
                    val at = base.plusSeconds(index * 10L)
                    clearLiveSession(live, at)
                    val template =
                        authoring.createActivityTemplate(
                            ActivityTemplateDraft(
                                "S3C2 stale $mutation ${base.toEpochMilli()}",
                                null,
                                TimeTrackingMode.STOPWATCH,
                                null,
                                ActivityTemplateSettings(startCountdown = Duration.ofSeconds(3)),
                            ),
                            createdAt = at,
                        )
                    val plan =
                        plans.createActivityPlanFromTemplate(
                            template.id,
                            PlanSchedule.FloatingDay(LocalDate.of(2026, 10, 1).plusDays(index.toLong())),
                            at.plusMillis(100),
                        )
                    val original = reads.getFocusedAction(plan.id)
                    val scheduler = CapturingScheduler()
                    val controller = controller(scope, plan.id, reads, live, at.plusSeconds(1), scheduler)
                    controller.awaitPrepared()
                    controller.launch()
                    controller.awaitPreflight()

                    val changed =
                        when (mutation) {
                            PlanMutation.RESCHEDULE ->
                                plans.reschedulePlanEntry(
                                    original.identity,
                                    PlanSchedule.FloatingDay(LocalDate.of(2026, 11, 1).plusDays(index.toLong())),
                                    at.plusSeconds(2),
                                )
                            PlanMutation.UPDATE_FROM_TEMPLATE -> {
                                authoring.saveActivityTemplate(
                                    template.id,
                                    template.revision,
                                    template
                                        .toAuthoringDraft()
                                        .copy(
                                            settings =
                                                ActivityTemplateSettings(
                                                    startCountdown = Duration.ofSeconds(9),
                                                ),
                                        ),
                                    at.plusSeconds(2),
                                )
                                plans.updatePlanFromTemplate(original.identity, at.plusSeconds(3))
                            }
                            PlanMutation.CANCEL -> plans.cancelPlan(original.identity, at.plusSeconds(2))
                        }
                    assertNotEquals(original.identity, changed.actionIdentity())
                    assertEquals(plan.id, changed.id)
                    assertTrue(changed.updatedAt > plan.updatedAt)
                    when (mutation) {
                        PlanMutation.RESCHEDULE -> {
                            assertNotEquals(plan.target, changed.target)
                            assertEquals(plan.activitySnapshotId, changed.activitySnapshotId)
                        }
                        PlanMutation.UPDATE_FROM_TEMPLATE -> {
                            assertNotEquals(plan.activitySnapshotId, changed.activitySnapshotId)
                            assertNotEquals(plan.sourceRevision, changed.sourceRevision)
                        }
                        PlanMutation.CANCEL -> {
                            assertEquals(PlanEntryStatus.CANCELLED, changed.status)
                            assertEquals(plan.activitySnapshotId, changed.activitySnapshotId)
                        }
                    }

                    scheduler.fireTwice()
                    controller.awaitStale()

                    assertNull(live.getActiveRuntime())
                    assertEquals(0L, countPlanActivityExecutions(context, plan.id.value))
                    assertEquals(0L, countActivityRecentUses(context, template.id.value))
                    assertEquals(changed, plans.getPlan(plan.id))
                    val durable = reads.getFocusedAction(plan.id)
                    assertEquals(changed.actionIdentity(), durable.identity)
                    if (mutation == PlanMutation.UPDATE_FROM_TEMPLATE) {
                        assertEquals(
                            Duration.ofSeconds(9),
                            (durable.snapshot as FocusedPlanAction.Snapshot.Activity).value.settings.startCountdown,
                        )
                    }
                    controller.close()
                }
            } finally {
                clearLiveSession(live, base.plusSeconds(60))
                scope.cancel()
            }
        }

    @Test
    fun finalLiveSlotOwnershipRejectsTimedActivityAndSequenceAfterRealPreflight() =
        runBlocking {
            verifyLateLiveConflict(sequence = false)
            verifyLateLiveConflict(sequence = true)
        }

    @Test
    fun realSequenceControllerSkipsLeadingEmptyRepeatAndUsesFrozenFirstStepPrecedence() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val live = LiveSessionRepository.create(context)
            val authoring = TemplateAuthoringRepository.create(context)
            val plans = PlanRepository.create(context) { ZoneOffset.UTC }
            val reads = PlanReadRepository.create(context) { ZoneOffset.UTC }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val at = Instant.ofEpochMilli(Instant.now().toEpochMilli())
            try {
                clearLiveSession(live, at)
                val activity =
                    authoring.createActivityTemplate(
                        ActivityTemplateDraft(
                            "S3C2 frozen Sequence step ${at.toEpochMilli()}",
                            null,
                            TimeTrackingMode.STOPWATCH,
                            null,
                            ActivityTemplateSettings(startCountdown = Duration.ofSeconds(99)),
                        ),
                        createdAt = at,
                    )
                val sequence =
                    authoring.createSequenceTemplate(
                        sequenceDraft(
                            "S3C2 frozen Sequence ${at.toEpochMilli()}",
                            activity.id,
                            sequenceCountdown = Duration.ofSeconds(7),
                            beforeEachCountdown = Duration.ofSeconds(3),
                            stepOverride = Duration.ofSeconds(2),
                            leadingEmptyRepeat = true,
                        ),
                        createdAt = at.plusMillis(100),
                    )
                val plan =
                    plans.createSequencePlanFromTemplate(
                        sequence.id,
                        PlanSchedule.FloatingDay(LocalDate.of(2026, 12, 1)),
                        at.plusMillis(200),
                    )
                val original = reads.getFocusedAction(plan.id)
                val scheduler = CapturingScheduler()
                val controller = controller(scope, plan.id, reads, live, at.plusSeconds(1), scheduler)
                controller.awaitPrepared()
                controller.launch()
                val preflight = controller.awaitPreflight()
                assertEquals(Duration.ofSeconds(2), preflight.duration)
                assertNull(live.getActiveRuntime())
                assertEquals(0L, countPlanSequenceExecutions(context, plan.id.value))

                val state = requireNotNull(authoring.getSequenceTemplateAuthoringState(sequence.id))
                val currentDraft = state.toAuthoringDraft()
                val currentStep = currentDraft.nodes.last() as SequenceNodeDraft.Step
                authoring.saveSequenceTemplate(
                    sequence.id,
                    sequence.revision,
                    currentDraft.copy(
                        settings =
                            currentDraft.settings.copy(
                                sequenceStartCountdown = Duration.ofSeconds(17),
                                beforeEachStepCountdown = Duration.ofSeconds(13),
                            ),
                        nodes =
                            listOf(
                                SequenceNodeDraft.Step(
                                    currentStep.value.copy(
                                        overrides = SequenceStepOverrides(startCountdown = Duration.ofSeconds(11)),
                                    ),
                                ),
                            ),
                    ),
                    at.plusSeconds(2),
                )

                scheduler.fireTwice()
                controller.awaitCommitted()

                val runtime = live.getActiveRuntime() as ActiveSequenceRuntime
                val emptyRepeat = runtime.snapshot.nodes.first() as SequenceSnapshotRepeatBlock
                val frozenStep = runtime.snapshot.nodes.last() as SequenceSnapshotActivityStep
                val firstOccurrence = runtime.execution.occurrences.single()
                assertEquals(plan.id, runtime.execution.planEntryId)
                assertEquals(plan.sequenceSnapshotId, runtime.snapshot.id)
                assertTrue(emptyRepeat.children.isEmpty())
                assertEquals(Duration.ofSeconds(7), runtime.snapshot.settings.sequenceStartCountdown)
                assertEquals(Duration.ofSeconds(3), runtime.snapshot.settings.beforeEachStepCountdown)
                assertEquals(Duration.ofSeconds(2), frozenStep.overrides.startCountdown)
                assertEquals(
                    frozenStep.id,
                    firstOccurrence.sourceSequenceSnapshotNodeId,
                )
                assertNull(requireNotNull(runtime.currentChild).planEntryId)
                assertEquals(1L, countPlanSequenceExecutions(context, plan.id.value))
                assertEquals(0L, countPlanActivityExecutions(context, plan.id.value))
                assertEquals(1L, countUnlinkedSequenceChildren(context, runtime.execution.id.value))
                assertEquals(original.identity, reads.getFocusedAction(plan.id).identity)
                assertTrue(reads.getFocusedAction(plan.id).engaged)
                assertEquals(PlanEntryStatus.PLANNED, plans.getPlan(plan.id)?.status)
                assertEquals(
                    at.plusSeconds(1),
                    authoring.getSequenceTemplate(sequence.id)?.userState?.lastUsedAt,
                )
                live.endSequenceEarly(at.plusSeconds(3))
                controller.close()
            } finally {
                clearLiveSession(live, at.plusSeconds(60))
                scope.cancel()
            }
        }

    private fun controller(
        scope: CoroutineScope,
        planId: com.alexandr5476.lifetracing.domain.PlanEntryId,
        reads: PlanReadRepository,
        live: LiveSessionRepository,
        at: Instant,
        scheduler: PreflightScheduler,
    ) = PlanExecutionController(
        scope,
        reads.getFocusedAction(planId).identity,
        reads::getFocusedAction,
        { live.getActiveSession() != null },
        { executePlanCommand(it, live) },
        {},
        WallClock { at },
        { ZoneOffset.UTC },
        scheduler,
    )

    private suspend fun verifyLateLiveConflict(sequence: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val live = LiveSessionRepository.create(context)
        val authoring = TemplateAuthoringRepository.create(context)
        val plans = PlanRepository.create(context) { ZoneOffset.UTC }
        val reads = PlanReadRepository.create(context) { ZoneOffset.UTC }
        val library = LibraryRepository.create(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val at = Instant.ofEpochMilli(Instant.now().toEpochMilli())
        try {
            clearLiveSession(live, at)
            val sourceActivity =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "S3C2 conflict source $sequence ${at.toEpochMilli()}",
                        null,
                        TimeTrackingMode.STOPWATCH,
                        null,
                        ActivityTemplateSettings(startCountdown = Duration.ofSeconds(3)),
                    ),
                    createdAt = at,
                )
            val (plan, sourceId) =
                if (sequence) {
                    val sourceSequence =
                        authoring.createSequenceTemplate(
                            sequenceDraft(
                                "S3C2 conflict Sequence ${at.toEpochMilli()}",
                                sourceActivity.id,
                                sequenceCountdown = Duration.ofSeconds(3),
                            ),
                            createdAt = at.plusMillis(100),
                        )
                    plans.createSequencePlanFromTemplate(
                        sourceSequence.id,
                        PlanSchedule.FloatingDay(LocalDate.of(2027, 1, 2)),
                        at.plusMillis(200),
                    ) to LibraryTemplateId.Sequence(sourceSequence.id)
                } else {
                    plans.createActivityPlanFromTemplate(
                        sourceActivity.id,
                        PlanSchedule.FloatingDay(LocalDate.of(2027, 1, 1)),
                        at.plusMillis(100),
                    ) to LibraryTemplateId.Activity(sourceActivity.id)
                }
            val original = reads.getFocusedAction(plan.id)
            val scheduler = CapturingScheduler()
            val controller = controller(scope, plan.id, reads, live, at.plusSeconds(1), scheduler)
            controller.awaitPrepared()
            controller.launch()
            controller.awaitPreflight()

            val unrelated =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "S3C2 unrelated live $sequence ${at.toEpochMilli()}",
                        null,
                        TimeTrackingMode.STOPWATCH,
                        null,
                    ),
                    createdAt = at.plusSeconds(2),
                )
            library.startActivityFromTemplate(unrelated.id, at.plusSeconds(3), at.plusSeconds(3), ZoneOffset.UTC)
            val authoritativeRuntime = requireNotNull(live.getActiveRuntime())

            scheduler.fireTwice()
            controller.awaitConflict()

            assertEquals(authoritativeRuntime, live.getActiveRuntime())
            assertEquals(original.identity, reads.getFocusedAction(plan.id).identity)
            assertFalse(reads.getFocusedAction(plan.id).engaged)
            assertEquals(0L, countPlanActivityExecutions(context, plan.id.value))
            assertEquals(0L, countPlanSequenceExecutions(context, plan.id.value))
            when (sourceId) {
                is LibraryTemplateId.Activity ->
                    assertEquals(0L, countActivityRecentUses(context, sourceId.value))
                is LibraryTemplateId.Sequence ->
                    assertEquals(0L, countSequenceRecentUses(context, sourceId.value))
            }
            controller.close()
        } finally {
            clearLiveSession(live, at.plusSeconds(60))
            scope.cancel()
        }
    }

    private fun sequenceDraft(
        name: String,
        activityId: ActivityTemplateId,
        sequenceCountdown: Duration,
        beforeEachCountdown: Duration = Duration.ZERO,
        stepOverride: Duration? = null,
        leadingEmptyRepeat: Boolean = false,
    ) = SequenceTemplateDraft(
        name,
        null,
        settings =
            SequenceTemplateSettings(
                sequenceStartCountdown = sequenceCountdown,
                beforeEachStepCountdown = beforeEachCountdown,
            ),
        nodes =
            buildList {
                if (leadingEmptyRepeat) {
                    add(
                        SequenceNodeDraft.Repeat(
                            SequenceRepeatBlockDraft(DraftIdentity.New("empty-repeat"), 0, 1, emptyList()),
                        ),
                    )
                }
                add(
                    SequenceNodeDraft.Step(
                        ActivityStepDraft(
                            DraftIdentity.New("step"),
                            if (leadingEmptyRepeat) 1 else 0,
                            StepActivityDraft.FromTemplate(activityId),
                            SequenceStepOverrides(startCountdown = stepOverride),
                        ),
                    ),
                )
            },
    )

    private fun clearLiveSession(
        live: LiveSessionRepository,
        at: Instant,
    ) {
        when (live.getActiveSession()?.kind) {
            ActiveSessionKind.ACTIVITY -> live.completeActiveActivity(at)
            ActiveSessionKind.SEQUENCE -> live.endSequenceEarly(at)
            null -> Unit
        }
    }

    private class CapturingScheduler : PreflightScheduler {
        val callbacks = mutableListOf<() -> Unit>()
        private val scheduled = kotlinx.coroutines.CompletableDeferred<Unit>()

        override fun schedule(
            duration: Duration,
            onBoundary: () -> Unit,
        ): PreflightHandle {
            callbacks += onBoundary
            scheduled.complete(Unit)
            return PreflightHandle {}
        }

        suspend fun awaitScheduled() = withTimeout(5_000) { scheduled.await() }

        suspend fun fireTwice() {
            awaitScheduled()
            val callback = callbacks.single()
            callback.invoke()
            callback.invoke()
        }
    }

    private suspend fun PlanExecutionController.awaitPrepared() =
        withTimeout(5_000) { state.first { it.prepared is PlanExecutionLoad.Content } }

    private suspend fun PlanExecutionController.awaitPreflight() =
        withTimeout(5_000) {
            state.first { it.command is PlanExecutionCommandState.Preflight }.command as
                PlanExecutionCommandState.Preflight
        }

    private suspend fun PlanExecutionController.awaitCommitted() =
        withTimeout(5_000) { state.first { it.command is PlanExecutionCommandState.Committed } }

    private suspend fun PlanExecutionController.awaitStale() =
        withTimeout(5_000) { state.first { it.command == PlanExecutionCommandState.Stale } }

    private suspend fun PlanExecutionController.awaitConflict() =
        withTimeout(5_000) { state.first { it.command is PlanExecutionCommandState.Conflict } }

    private fun deleteActivityTemplate(
        context: Context,
        id: ActivityTemplateId,
    ) {
        openDatabase(context).use { database ->
            database.setForeignKeyConstraintsEnabled(true)
            assertEquals(1, database.delete("activity_templates", "id = ?", arrayOf(id.value)))
        }
    }

    private fun countPlanActivityExecutions(
        context: Context,
        planId: String,
    ) = count(context, "SELECT COUNT(*) FROM activity_executions WHERE plan_entry_id = ?", planId)

    private fun countPlanSequenceExecutions(
        context: Context,
        planId: String,
    ) = count(context, "SELECT COUNT(*) FROM sequence_executions WHERE plan_entry_id = ?", planId)

    private fun countUnlinkedSequenceChildren(
        context: Context,
        sequenceExecutionId: String,
    ) = count(
        context,
        "SELECT COUNT(*) FROM activity_executions WHERE sequence_execution_id = ? AND plan_entry_id IS NULL",
        sequenceExecutionId,
    )

    private fun countActivityTemplates(
        context: Context,
        templateId: String,
    ) = count(context, "SELECT COUNT(*) FROM activity_templates WHERE id = ?", templateId)

    private fun countActivityRecentUses(
        context: Context,
        templateId: String,
    ) = count(
        context,
        "SELECT COUNT(*) FROM activity_template_user_state WHERE activity_template_id = ? AND last_used_at_ms IS NOT NULL",
        templateId,
    )

    private fun countSequenceRecentUses(
        context: Context,
        templateId: String,
    ) = count(
        context,
        "SELECT COUNT(*) FROM sequence_template_user_state WHERE sequence_template_id = ? AND last_used_at_ms IS NOT NULL",
        templateId,
    )

    private fun count(
        context: Context,
        sql: String,
        argument: String,
    ): Long =
        openDatabase(context).use { database ->
            DatabaseUtils.longForQuery(database, sql, arrayOf(argument))
        }

    private fun openDatabase(context: Context): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("lifetracing.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )

    private enum class SourceMutation(
        val sourceState: PlanSourceState,
    ) {
        CHANGE(PlanSourceState.CHANGED),
        ARCHIVE(PlanSourceState.ARCHIVED),
        DELETE(PlanSourceState.UNAVAILABLE),
    }

    private enum class PlanMutation {
        RESCHEDULE,
        UPDATE_FROM_TEMPLATE,
        CANCEL,
    }
}
