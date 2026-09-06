package com.alexandr5476.lifetracing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.data.persistence.ActivityCommandRepository
import com.alexandr5476.lifetracing.data.persistence.DailyReadRepository
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.MonotonicClock
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import com.alexandr5476.lifetracing.launcher.LauncherCommandState
import com.alexandr5476.lifetracing.launcher.LauncherCommit
import com.alexandr5476.lifetracing.launcher.LauncherDurableCommand
import com.alexandr5476.lifetracing.launcher.LauncherLoad
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.launcher.StartActivityAction
import com.alexandr5476.lifetracing.launcher.StartActivityController
import com.alexandr5476.lifetracing.runtime.AndroidRuntimeCoordinator
import com.alexandr5476.lifetracing.runtime.InProcessRuntimeDeadlineDriver
import com.alexandr5476.lifetracing.runtime.RuntimeDeadlineScheduler
import com.alexandr5476.lifetracing.runtime.RuntimeFeedbackDispatcher
import com.alexandr5476.lifetracing.runtime.RuntimeNotificationPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProductionLauncherCoordinationTest {
    @Test
    fun concurrentProductionControllersClassifyTheDurableLoserAsLiveConflict() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val now = Instant.now()
            val live = LiveSessionRepository.create(context)
            clearLiveSession(live, now)
            val authoring = TemplateAuthoringRepository.create(context)
            val ids =
                listOf("A", "B").map { suffix ->
                    LibraryTemplateId.Activity(
                        authoring
                            .createActivityTemplate(
                                ActivityTemplateDraft(
                                    "S3 Race $suffix ${now.toEpochMilli()}",
                                    null,
                                    TimeTrackingMode.STOPWATCH,
                                    null,
                                ),
                                createdAt = now.minusSeconds(1),
                            ).id,
                    )
                }
            val library = LibraryRepository.create(context)
            val activityCommands = ActivityCommandRepository.create(context)
            val barrier = CyclicBarrier(2)
            val scopes = List(2) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
            val controllers =
                scopes.map { scope ->
                    controller(
                        scope,
                        library,
                        live,
                        FixedWallClock(now),
                        execute = { command ->
                            barrier.await(10, TimeUnit.SECONDS)
                            executeLauncherCommand(command, activityCommands, library)
                        },
                    )
                }
            try {
                controllers.forEach { controller ->
                    withTimeout(5_000) { controller.state.first { it.home !is LauncherLoad.Loading } }
                }
                controllers.zip(ids).forEach { (controller, id) ->
                    controller.dispatch(StartActivityAction.Select(id))
                }
                controllers.forEach { controller ->
                    withTimeout(5_000) { controller.state.first { it.selected is LauncherLoad.Content } }
                }
                controllers.forEach { it.dispatch(StartActivityAction.Launch()) }
                val outcomes =
                    controllers.map { controller ->
                        withTimeout(10_000) {
                            controller.state.first {
                                it.command is LauncherCommandState.Committed ||
                                    it.command is LauncherCommandState.Conflict
                            }
                        }.command
                    }

                assertEquals(1, outcomes.count { it is LauncherCommandState.Committed })
                assertEquals(1, outcomes.count { it is LauncherCommandState.Conflict })
                val commit =
                    (outcomes.single { it is LauncherCommandState.Committed } as LauncherCommandState.Committed)
                        .result as LauncherCommit.Activity
                val daily =
                    DailyReadRepository
                        .create(context, CurrentZoneIdProvider { ZoneOffset.UTC })
                        .getDaily(DailyQuery(now.atZone(ZoneOffset.UTC).toLocalDate(), now, 100))
                val active = daily.active as DailyActive.Activity
                assertEquals(commit.executionId, active.runtime.execution.id)
                val winnerId = LibraryTemplateId.Activity(requireNotNull(active.runtime.snapshot.sourceTemplateId))
                val loserId = ids.single { it != winnerId }
                val recent = library.getRecent(100).map { it.id }
                assertEquals(true, winnerId in recent)
                assertEquals(false, loserId in recent)
            } finally {
                controllers.forEach(StartActivityController::close)
                scopes.forEach(CoroutineScope::cancel)
                clearLiveSession(live, now.plusSeconds(1))
            }
        }

    @Test
    fun committedActivitySurvivesProductionCoordinatorFailureWithoutWriterRetry() =
        runBlocking {
            verifyCommittedCoordinationFailure(sequence = false)
        }

    @Test
    fun restoredLauncherRouteReturnsToDailyWithoutCreatingAnotherDurableCommand() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val now = Instant.now()
            val live = LiveSessionRepository.create(context)
            clearLiveSession(live, now)
            val authoring = TemplateAuthoringRepository.create(context)
            val noLiveId =
                authoring
                    .createActivityTemplate(
                        ActivityTemplateDraft(
                            "Recovered quick ${now.toEpochMilli()}",
                            null,
                            TimeTrackingMode.NO_LIVE_TRACKING,
                            null,
                        ),
                        createdAt = now,
                    ).id
            val liveId =
                authoring
                    .createActivityTemplate(
                        ActivityTemplateDraft(
                            "Recovered live ${now.toEpochMilli()}",
                            null,
                            TimeTrackingMode.STOPWATCH,
                            null,
                        ),
                        createdAt = now,
                    ).id
            val library = LibraryRepository.create(context)
            val commands = ActivityCommandRepository.create(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val quickController =
                controller(
                    scope,
                    library,
                    live,
                    FixedWallClock(now),
                    { command -> executeLauncherCommand(command, commands, library) },
                )
            try {
                withTimeout(5_000) { quickController.state.first { it.home !is LauncherLoad.Loading } }
                quickController.dispatch(StartActivityAction.Select(LibraryTemplateId.Activity(noLiveId)))
                withTimeout(5_000) { quickController.state.first { it.selected is LauncherLoad.Content } }
                quickController.dispatch(StartActivityAction.Launch())
                val noLive =
                    withTimeout(5_000) { quickController.state.first { it.command is LauncherCommandState.Committed } }
                        .command as LauncherCommandState.Committed
                val noLiveStack = mutableListOf<androidx.navigation3.runtime.NavKey>(DailyRoot, StartActivityRoot)
                noLiveStack.normalizeRestoredStartActivity()
                val dailyAfterNoLive =
                    DailyReadRepository
                        .create(context, CurrentZoneIdProvider { ZoneOffset.UTC })
                        .getDaily(DailyQuery(now.atZone(ZoneOffset.UTC).toLocalDate(), now, 100))
                assertEquals(listOf(DailyRoot), noLiveStack)
                assertEquals(
                    1,
                    dailyAfterNoLive.completedHistory
                        .filterIsInstance<CompletedActivityHistoryRoot>()
                        .count { it.executionId == (noLive.result as LauncherCommit.Activity).executionId },
                )

                quickController.close()
                val liveController =
                    controller(
                        scope,
                        library,
                        live,
                        FixedWallClock(now),
                        { command -> executeLauncherCommand(command, commands, library) },
                    )
                withTimeout(5_000) { liveController.state.first { it.home !is LauncherLoad.Loading } }
                liveController.dispatch(StartActivityAction.Select(LibraryTemplateId.Activity(liveId)))
                withTimeout(5_000) { liveController.state.first { it.selected is LauncherLoad.Content } }
                liveController.dispatch(StartActivityAction.Launch())
                val liveCommit =
                    withTimeout(5_000) { liveController.state.first { it.command is LauncherCommandState.Committed } }
                        .command as LauncherCommandState.Committed
                val liveStack = mutableListOf<androidx.navigation3.runtime.NavKey>(DailyRoot, StartActivityRoot)
                liveStack.normalizeRestoredStartActivity()
                val dailyAfterLive =
                    DailyReadRepository
                        .create(context, CurrentZoneIdProvider { ZoneOffset.UTC })
                        .getDaily(DailyQuery(now.atZone(ZoneOffset.UTC).toLocalDate(), now, 100))
                assertEquals(listOf(DailyRoot), liveStack)
                assertEquals(
                    (liveCommit.result as LauncherCommit.Activity).executionId,
                    (dailyAfterLive.active as DailyActive.Activity).runtime.execution.id,
                )
                liveController.close()
            } finally {
                quickController.close()
                scope.cancel()
                clearLiveSession(live, now.plusSeconds(1))
            }
        }

    @Test
    fun committedSequenceSurvivesProductionCoordinatorFailureWithoutWriterRetry() =
        runBlocking {
            verifyCommittedCoordinationFailure(sequence = true)
        }

    private suspend fun verifyCommittedCoordinationFailure(sequence: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.now()
        val live = LiveSessionRepository.create(context)
        clearLiveSession(live, now)
        val authoring = TemplateAuthoringRepository.create(context)
        val activity =
            authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "S3 ${if (sequence) "Sequence step" else "Activity"} ${now.toEpochMilli()}",
                    null,
                    TimeTrackingMode.STOPWATCH,
                    null,
                ),
                createdAt = now.minusSeconds(1),
            )
        val selectedId =
            if (sequence) {
                val template =
                    authoring.createSequenceTemplate(
                        SequenceTemplateDraft(
                            "S3 Sequence ${now.toEpochMilli()}",
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
                        createdAt = now.minusMillis(500),
                    )
                LibraryTemplateId.Sequence(template.id)
            } else {
                LibraryTemplateId.Activity(activity.id)
            }
        val library = LibraryRepository.create(context)
        val activityCommands = ActivityCommandRepository.create(context)
        val wallClock = FixedWallClock(now)
        val coordinator = failingCoordinator(live, wallClock)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var writerCalls = 0
        val controller =
            controller(
                scope,
                library,
                live,
                wallClock,
                execute = { command ->
                    writerCalls++
                    executeLauncherCommand(command, activityCommands, library)
                },
                coordinate = coordinator::onRuntimeStateChanged,
            )
        try {
            withTimeout(5_000) { controller.state.first { it.home !is LauncherLoad.Loading } }
            controller.dispatch(StartActivityAction.Select(selectedId))
            withTimeout(5_000) { controller.state.first { it.selected is LauncherLoad.Content } }
            controller.dispatch(StartActivityAction.Launch())
            val failure =
                withTimeout(5_000) {
                    controller.state.first { it.command is LauncherCommandState.CommittedCoordinationFailure }
                }.command as LauncherCommandState.CommittedCoordinationFailure
            controller.dispatch(StartActivityAction.Launch())
            controller.dispatch(StartActivityAction.RetryLaunch)
            kotlinx.coroutines.yield()

            assertEquals(1, writerCalls)
            val daily =
                DailyReadRepository
                    .create(context, CurrentZoneIdProvider { ZoneOffset.UTC })
                    .getDaily(DailyQuery(now.atZone(ZoneOffset.UTC).toLocalDate(), now, 100))
            when (val commit = failure.result) {
                is LauncherCommit.Activity -> {
                    val active = daily.active as DailyActive.Activity
                    assertEquals(commit.executionId, active.runtime.execution.id)
                    assertEquals(commit.executionId, live.getActiveSession()?.activityExecutionId)
                    assertNull(active.runtime.execution.planEntryId)
                }
                is LauncherCommit.Sequence -> {
                    val active = daily.active as DailyActive.Sequence
                    assertEquals(commit.executionId, active.runtime.execution.id)
                    assertEquals(commit.executionId, live.getActiveSession()?.sequenceExecutionId)
                    assertNull(active.runtime.execution.planEntryId)
                }
            }
            assertEquals(selectedId, library.getRecent(50).first { it.id == selectedId }.id)
        } finally {
            controller.close()
            scope.cancel()
            clearLiveSession(live, now.plusSeconds(1))
        }
    }

    private fun controller(
        scope: CoroutineScope,
        library: LibraryRepository,
        live: LiveSessionRepository,
        wallClock: WallClock,
        execute: suspend (LauncherDurableCommand) -> LauncherCommit,
        coordinate: suspend () -> Unit = {},
    ) = StartActivityController(
        scope,
        { emptyList() },
        { emptyList() },
        { emptyList() },
        { LibraryContents(emptyList(), emptyList(), emptyList()) },
        library::getLaunchTarget,
        {},
        { live.getActiveSession() != null },
        execute,
        coordinate,
        wallClock,
        { ZoneOffset.UTC },
        PreflightScheduler { _, _ -> PreflightHandle {} },
    )

    private fun failingCoordinator(
        live: LiveSessionRepository,
        wallClock: WallClock,
    ) = AndroidRuntimeCoordinator(
        live,
        wallClock,
        MonotonicClock { 0 },
        object : RuntimeDeadlineScheduler {
            override fun schedule(deadline: RuntimeDeadline) = error("scheduler failed")

            override fun cancel() = error("scheduler failed")

            override fun canScheduleExactRuntimeDeadlines(): Boolean = true
        },
        object : InProcessRuntimeDeadlineDriver {
            override fun arm(
                deadline: RuntimeDeadline,
                anchor: WallMonotonicAnchor,
                callback: suspend (RuntimeDeadline) -> Unit,
            ) = Unit

            override fun cancel() = Unit
        },
        RuntimeFeedbackDispatcher {},
        object : RuntimeNotificationPublisher {
            override fun publish(
                runtime: com.alexandr5476.lifetracing.domain.ActiveRuntime?,
                completion: com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback?,
            ) = Unit

            override fun canPostRuntimeNotifications(): Boolean = true
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

    private class FixedWallClock(
        private val value: Instant,
    ) : WallClock {
        override fun now(): Instant = value
    }
}
