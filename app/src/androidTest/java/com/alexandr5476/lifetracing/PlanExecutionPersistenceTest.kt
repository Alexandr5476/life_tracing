package com.alexandr5476.lifetracing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.PlanReadRepository
import com.alexandr5476.lifetracing.data.persistence.PlanRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
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

    private fun controller(
        scope: CoroutineScope,
        planId: com.alexandr5476.lifetracing.domain.PlanEntryId,
        reads: PlanReadRepository,
        live: LiveSessionRepository,
        at: Instant,
        scheduler: PreflightScheduler,
    ) = PlanExecutionController(
        scope,
        planId,
        reads::getFocusedAction,
        { live.getActiveSession() != null },
        { executePlanCommand(it, live) },
        {},
        WallClock { at },
        { ZoneOffset.UTC },
        scheduler,
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

        override fun schedule(
            duration: Duration,
            onBoundary: () -> Unit,
        ): PreflightHandle {
            callbacks += onBoundary
            return PreflightHandle {}
        }

        fun fireTwice() {
            callbacks.single().invoke()
            callbacks.single().invoke()
        }
    }

    private suspend fun PlanExecutionController.awaitPrepared() =
        withTimeout(5_000) { state.first { it.prepared is PlanExecutionLoad.Content } }

    private suspend fun PlanExecutionController.awaitPreflight() =
        withTimeout(5_000) { state.first { it.command is PlanExecutionCommandState.Preflight } }

    private suspend fun PlanExecutionController.awaitCommitted() =
        withTimeout(5_000) { state.first { it.command is PlanExecutionCommandState.Committed } }
}
