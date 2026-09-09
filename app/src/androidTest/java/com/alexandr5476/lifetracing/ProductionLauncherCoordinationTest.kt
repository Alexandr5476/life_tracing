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
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.MonotonicClock
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.launcher.LauncherCommandState
import com.alexandr5476.lifetracing.launcher.LauncherCommit
import com.alexandr5476.lifetracing.launcher.LauncherDurableCommand
import com.alexandr5476.lifetracing.launcher.LauncherLoad
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.launcher.StartActivityAction
import com.alexandr5476.lifetracing.launcher.StartActivityController
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSession
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSessionOwner
import com.alexandr5476.lifetracing.library.LibraryAction
import com.alexandr5476.lifetracing.library.LibraryController
import com.alexandr5476.lifetracing.library.LibraryLoad
import com.alexandr5476.lifetracing.library.LibraryMutation
import com.alexandr5476.lifetracing.library.LibraryOrganization
import com.alexandr5476.lifetracing.runtime.AndroidRuntimeCoordinator
import com.alexandr5476.lifetracing.runtime.InProcessRuntimeDeadlineDriver
import com.alexandr5476.lifetracing.runtime.RuntimeDeadlineScheduler
import com.alexandr5476.lifetracing.runtime.RuntimeFeedbackDispatcher
import com.alexandr5476.lifetracing.runtime.RuntimeNotificationPublisher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ProductionLauncherCoordinationTest {
    @Test
    @Suppress("LongMethod") // The three existing launcher variants share one real Library-entry boundary.
    fun libraryPrimedRoutesUseTheExistingProductionLaunchWriters() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val now = Instant.now()
            val suffix = now.toEpochMilli()
            val live = LiveSessionRepository.create(context)
            clearLiveSession(live, now)
            val authoring = TemplateAuthoringRepository.create(context)
            val timed =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft("S5 timed $suffix", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = now.minusSeconds(3),
                )
            val noLive =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft("S5 no-live $suffix", null, TimeTrackingMode.NO_LIVE_TRACKING, null),
                    createdAt = now.minusSeconds(2),
                )
            val sequence =
                authoring.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "S5 sequence $suffix",
                        null,
                        nodes =
                            listOf(
                                SequenceNodeDraft.Step(
                                    ActivityStepDraft(
                                        DraftIdentity.New("step"),
                                        0,
                                        StepActivityDraft.FromTemplate(timed.id),
                                    ),
                                ),
                            ),
                    ),
                    createdAt = now.minusSeconds(1),
                )
            val library = LibraryRepository.create(context)
            val commands = ActivityCommandRepository.create(context)
            val dailyReader = DailyReadRepository.create(context, CurrentZoneIdProvider { ZoneOffset.UTC })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sessions = StartActivityRouteSessionOwner()
            val durableCommands = mutableListOf<LauncherDurableCommand>()
            val timedBefore = requireNotNull(authoring.getActivityTemplate(timed.id))
            val noLiveBefore = requireNotNull(authoring.getActivityTemplate(noLive.id))
            val sequenceBefore = requireNotNull(authoring.getSequenceTemplate(sequence.id))
            try {
                val timedSession =
                    sessions.acquire {
                        controller(
                            scope,
                            library,
                            live,
                            FixedWallClock(now),
                            execute = { command ->
                                durableCommands += command
                                executeLauncherCommand(command, commands, library)
                            },
                        )
                    }
                val timedCommit =
                    resolvePrimedLibraryRoute(
                        timedSession,
                        LibraryTemplateId.Activity(timed.id),
                    ) as LauncherCommit.Activity
                assertTrue(timedCommit.isLive)
                assertNull(timedSession.interaction.resolveSelection(timedSession.controller.loadedTarget()))
                assertEquals(timedCommit.executionId, live.getActiveSession()?.activityExecutionId)
                val timedActive =
                    dailyReader
                        .getDaily(DailyQuery(now.atZone(ZoneOffset.UTC).toLocalDate(), now, 100))
                        .active as DailyActive.Activity
                assertEquals(timed.id, timedActive.runtime.snapshot.sourceTemplateId)
                assertEquals(timedBefore.revision, timedActive.runtime.snapshot.sourceRevision)
                assertEquals(timedBefore.statisticsSeriesId, timedActive.runtime.snapshot.statisticsSeriesId)
                sessions.release(timedSession)

                val noLiveSession =
                    sessions.acquire {
                        controller(
                            scope,
                            library,
                            live,
                            FixedWallClock(now),
                            execute = { command ->
                                durableCommands += command
                                executeLauncherCommand(command, commands, library)
                            },
                        )
                    }
                val noLiveCommit =
                    resolvePrimedLibraryRoute(
                        noLiveSession,
                        LibraryTemplateId.Activity(noLive.id),
                    ) as LauncherCommit.Activity
                assertEquals(false, noLiveCommit.isLive)
                assertEquals(timedCommit.executionId, live.getActiveSession()?.activityExecutionId)
                val noLiveHistory =
                    dailyReader
                        .getDaily(DailyQuery(now.atZone(ZoneOffset.UTC).toLocalDate(), now, 100))
                        .completedHistory
                        .filterIsInstance<CompletedActivityHistoryRoot>()
                        .single { it.executionId == noLiveCommit.executionId }
                assertNull(noLiveHistory.startedAt)
                assertNull(noLiveHistory.activeDuration)
                assertNull(noLiveHistory.planEntryId)
                sessions.release(noLiveSession)

                clearLiveSession(live, now.plusSeconds(1))
                val sequenceSession =
                    sessions.acquire {
                        controller(
                            scope,
                            library,
                            live,
                            FixedWallClock(now.plusSeconds(2)),
                            execute = { command ->
                                durableCommands += command
                                executeLauncherCommand(command, commands, library)
                            },
                        )
                    }
                val sequenceCommit =
                    resolvePrimedLibraryRoute(
                        sequenceSession,
                        LibraryTemplateId.Sequence(sequence.id),
                    ) as LauncherCommit.Sequence
                assertEquals(sequenceCommit.executionId, live.getActiveSession()?.sequenceExecutionId)
                sessions.release(sequenceSession)

                assertEquals(
                    listOf(
                        LauncherDurableCommand.StartActivity::class,
                        LauncherDurableCommand.CompleteNoLive::class,
                        LauncherDurableCommand.StartSequence::class,
                    ),
                    durableCommands.map { it::class },
                )
                assertEquals(
                    setOf(
                        LibraryTemplateId.Activity(timed.id),
                        LibraryTemplateId.Activity(noLive.id),
                        LibraryTemplateId.Sequence(sequence.id),
                    ),
                    library.getRecent(100).map(LibraryTrackable::id).toSet().intersect(
                        setOf(
                            LibraryTemplateId.Activity(timed.id),
                            LibraryTemplateId.Activity(noLive.id),
                            LibraryTemplateId.Sequence(sequence.id),
                        ),
                    ),
                )
                val activeSequence =
                    dailyReader
                        .getDaily(DailyQuery(now.atZone(ZoneOffset.UTC).toLocalDate(), now.plusSeconds(2), 100))
                        .active as DailyActive.Sequence
                assertEquals(sequenceCommit.executionId, activeSequence.runtime.execution.id)
                assertEquals(sequence.id, activeSequence.runtime.snapshot.sourceTemplateId)
                assertEquals(sequenceBefore.revision, activeSequence.runtime.snapshot.sourceRevision)
                assertEquals(sequenceBefore.statisticsSeriesId, activeSequence.runtime.snapshot.statisticsSeriesId)
                assertEquals(timedBefore.revision, authoring.getActivityTemplate(timed.id)?.revision)
                assertEquals(
                    timedBefore.statisticsSeriesId,
                    authoring.getActivityTemplate(timed.id)?.statisticsSeriesId,
                )
                assertEquals(noLiveBefore.revision, authoring.getActivityTemplate(noLive.id)?.revision)
                assertEquals(
                    noLiveBefore.statisticsSeriesId,
                    authoring.getActivityTemplate(noLive.id)?.statisticsSeriesId,
                )
                assertEquals(sequenceBefore.revision, authoring.getSequenceTemplate(sequence.id)?.revision)
                assertEquals(
                    sequenceBefore.statisticsSeriesId,
                    authoring.getSequenceTemplate(sequence.id)?.statisticsSeriesId,
                )
            } finally {
                sessions.activeSession?.let(sessions::release)
                clearLiveSession(live, now.plusSeconds(3))
                scope.cancel()
            }
        }

    @Test
    fun loadedTimedNoLiveAndSequenceTargetsArchivedBeforeCommitLeaveNoRuntimeOrRecentResidue() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val now = Instant.now()
            val suffix = now.toEpochMilli()
            val live = LiveSessionRepository.create(context)
            clearLiveSession(live, now)
            val authoring = TemplateAuthoringRepository.create(context)
            val timed =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft("S4 timed $suffix", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = now.minusSeconds(4),
                )
            val noLive =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft("S4 no-live $suffix", null, TimeTrackingMode.NO_LIVE_TRACKING, null),
                    createdAt = now.minusSeconds(3),
                )
            val sequence =
                authoring.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "S4 sequence $suffix",
                        null,
                        nodes =
                            listOf(
                                SequenceNodeDraft.Step(
                                    ActivityStepDraft(
                                        DraftIdentity.New("step"),
                                        0,
                                        StepActivityDraft.FromTemplate(timed.id),
                                    ),
                                ),
                            ),
                    ),
                    createdAt = now.minusSeconds(2),
                )
            val targets =
                listOf(
                    LibraryTemplateId.Activity(timed.id),
                    LibraryTemplateId.Activity(noLive.id),
                    LibraryTemplateId.Sequence(sequence.id),
                )
            val library = LibraryRepository.create(context)
            val commands = ActivityCommandRepository.create(context)

            targets.forEachIndexed { index, id ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                var writers = 0
                val controller =
                    controller(
                        scope,
                        library,
                        live,
                        FixedWallClock(now),
                        execute = { command ->
                            writers++
                            executeLauncherCommand(command, commands, library)
                        },
                        initialLiveConflict = { false },
                    )
                try {
                    withTimeout(5_000) { controller.state.first { it.home !is LauncherLoad.Loading } }
                    controller.dispatch(StartActivityAction.Select(id))
                    withTimeout(5_000) { controller.state.first { it.selected is LauncherLoad.Content } }
                    executeLibraryMutation(LibraryMutation.ArchiveTemplate(id, now.plusMillis(index.toLong())), library)

                    controller.dispatch(StartActivityAction.Launch())
                    withTimeout(5_000) { controller.state.first { it.command is LauncherCommandState.Rejected } }

                    assertEquals(1, writers)
                    assertNull(live.getActiveSession())
                    assertTrue(library.getRecent(100).none { it.id == id })
                } finally {
                    controller.close()
                    scope.cancel()
                    clearLiveSession(live, now.plusSeconds(1))
                }
            }
        }

    @Test
    fun realSemanticSaveRehydratesTheLauncherBeforeTheOldNoLiveBoundaryCanPersist() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val now = Instant.now()
            val live = LiveSessionRepository.create(context)
            clearLiveSession(live, now)
            val authoring = TemplateAuthoringRepository.create(context)
            val initial =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "F2 activity ${now.toEpochMilli()}",
                        null,
                        TimeTrackingMode.NO_LIVE_TRACKING,
                        null,
                    ),
                    createdAt = now.minusSeconds(2),
                )
            val library = LibraryRepository.create(context)
            val commands = ActivityCommandRepository.create(context)
            val initialCheckEntered = CompletableDeferred<Unit>()
            val releaseInitialCheck = CompletableDeferred<Unit>()
            val boundary = CompletableDeferred<() -> Unit>()
            var writers = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller =
                controller(
                    scope,
                    library,
                    live,
                    FixedWallClock(now),
                    execute = { command ->
                        writers++
                        executeLauncherCommand(command, commands, library)
                    },
                    scheduler =
                        PreflightScheduler { _, callback ->
                            boundary.complete(callback)
                            PreflightHandle {}
                        },
                    initialLiveConflict = { target ->
                        initialCheckEntered.complete(Unit)
                        releaseInitialCheck.await()
                        library.hasLiveLaunchConflict(target.id, target.revision)
                    },
                )
            try {
                withTimeout(5_000) { controller.state.first { it.home !is LauncherLoad.Loading } }
                controller.dispatch(StartActivityAction.Select(LibraryTemplateId.Activity(initial.id)))
                withTimeout(5_000) { controller.state.first { it.selected is LauncherLoad.Content } }
                controller.dispatch(StartActivityAction.Launch())
                initialCheckEntered.await()
                val changed =
                    authoring.saveActivityTemplate(
                        initial.id,
                        initial.revision,
                        initial.toAuthoringDraft().copy(
                            settings = ActivityTemplateSettings(startCountdown = Duration.ofSeconds(1)),
                        ),
                        now.minusSeconds(1),
                    )
                releaseInitialCheck.complete(Unit)

                withTimeout(5_000) {
                    controller.state.first {
                        it.command == LauncherCommandState.Idle &&
                            (it.selected as? LauncherLoad.Content)?.value?.revision == changed.revision
                    }
                }
                assertEquals(
                    Duration.ofSeconds(1),
                    (controller.state.value.selected as LauncherLoad.Content).value.startCountdown,
                )
                assertEquals(0, writers)
                assertNull(live.getActiveSession())
                assertEquals(false, library.getRecent(100).any { it.id == LibraryTemplateId.Activity(initial.id) })

                controller.dispatch(StartActivityAction.Launch())
                withTimeout(5_000) { controller.state.first { it.command is LauncherCommandState.Preflight } }
                withTimeout(5_000) { boundary.await()() }
                withTimeout(5_000) { controller.state.first { it.command is LauncherCommandState.Committed } }
                assertEquals(1, writers)
                assertEquals(changed.revision, library.getLaunchTarget(LibraryTemplateId.Activity(initial.id)).revision)
            } finally {
                releaseInitialCheck.complete(Unit)
                controller.close()
                scope.cancel()
            }
        }

    @Test
    fun selectObservedBeforeTheBoundaryCannotCreateASecondNoLiveFactOrReplaceTheCommittedTarget() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val now = Instant.now()
            val live = LiveSessionRepository.create(context)
            clearLiveSession(live, now)
            val authoring = TemplateAuthoringRepository.create(context)
            val first =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "F1 first ${now.toEpochMilli()}",
                        null,
                        TimeTrackingMode.NO_LIVE_TRACKING,
                        null,
                        ActivityTemplateSettings(startCountdown = Duration.ofSeconds(1)),
                    ),
                    createdAt = now.minusSeconds(1),
                )
            val second =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "F1 second ${now.toEpochMilli()}",
                        null,
                        TimeTrackingMode.NO_LIVE_TRACKING,
                        null,
                    ),
                    createdAt = now.minusMillis(500),
                )
            val library = LibraryRepository.create(context)
            val commands = ActivityCommandRepository.create(context)
            val dailyReader = DailyReadRepository.create(context, CurrentZoneIdProvider { ZoneOffset.UTC })
            val baselineHistory =
                dailyReader
                    .getDaily(DailyQuery(now.atZone(ZoneOffset.UTC).toLocalDate(), now, 100))
                    .completedHistory
                    .filterIsInstance<CompletedActivityHistoryRoot>()
                    .map { it.executionId }
                    .toSet()
            val selectObserved = CountDownLatch(1)
            val releaseSelect = CountDownLatch(1)
            val releaseWriter = kotlinx.coroutines.CompletableDeferred<Unit>()
            val boundary = CompletableDeferred<() -> Unit>()
            var writers = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller =
                controller(
                    scope,
                    library,
                    live,
                    FixedWallClock(now),
                    execute = { command ->
                        writers++
                        val committed = executeLauncherCommand(command, commands, library)
                        releaseWriter.await()
                        committed
                    },
                    scheduler =
                        PreflightScheduler { _, callback ->
                            boundary.complete(callback)
                            PreflightHandle {}
                        },
                    onSelectObserved = { command ->
                        if (command is LauncherCommandState.Preflight) {
                            selectObserved.countDown()
                            check(releaseSelect.await(5, TimeUnit.SECONDS))
                        }
                    },
                )
            try {
                withTimeout(5_000) { controller.state.first { it.home !is LauncherLoad.Loading } }
                controller.dispatch(StartActivityAction.Select(LibraryTemplateId.Activity(first.id)))
                withTimeout(5_000) { controller.state.first { it.selected is LauncherLoad.Content } }
                controller.dispatch(StartActivityAction.Launch())
                withTimeout(5_000) { controller.state.first { it.command is LauncherCommandState.Preflight } }

                scope.launch {
                    controller.dispatch(StartActivityAction.Select(LibraryTemplateId.Activity(second.id)))
                }
                assertTrue(selectObserved.await(5, TimeUnit.SECONDS))
                withTimeout(5_000) { boundary.await()() }
                withTimeout(5_000) { controller.state.first { it.command is LauncherCommandState.Committing } }
                releaseSelect.countDown()
                withTimeout(5_000) { while (writers == 0) kotlinx.coroutines.yield() }
                val selected = (controller.state.value.selected as LauncherLoad.Content).value
                assertEquals(first.id, (selected.id as LibraryTemplateId.Activity).id)
                assertEquals(1, writers)

                releaseWriter.complete(Unit)
                val committed =
                    withTimeout(5_000) { controller.state.first { it.command is LauncherCommandState.Committed } }
                        .command as LauncherCommandState.Committed
                val execution = committed.result as LauncherCommit.Activity
                val reloadedLibrary = LibraryRepository.create(context)
                val daily = dailyReader.getDaily(DailyQuery(now.atZone(ZoneOffset.UTC).toLocalDate(), now, 100))
                assertEquals(
                    listOf(execution.executionId),
                    daily.completedHistory
                        .filterIsInstance<CompletedActivityHistoryRoot>()
                        .map { it.executionId }
                        .filterNot(baselineHistory::contains),
                )
                assertEquals(
                    LibraryTemplateId.Activity(first.id),
                    reloadedLibrary.getRecent(100).first { it.id == LibraryTemplateId.Activity(first.id) }.id,
                )
                assertEquals(
                    false,
                    reloadedLibrary.getRecent(100).any { it.id == LibraryTemplateId.Activity(second.id) },
                )
                assertNull(live.getActiveSession())
            } finally {
                releaseSelect.countDown()
                releaseWriter.complete(Unit)
                controller.close()
                scope.cancel()
            }
        }

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

    @Test
    fun libraryReorderIsTheFreshStartActivityPinnedOrderWithoutChangingTemplateIdentity() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val now = Instant.now()
            val authoring = TemplateAuthoringRepository.create(context)
            val activityA =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "S3 pinned A ${now.toEpochMilli()}",
                        null,
                        TimeTrackingMode.STOPWATCH,
                        null,
                    ),
                    createdAt = now.minusSeconds(3),
                )
            val activityB =
                authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "S3 pinned B ${now.toEpochMilli()}",
                        null,
                        TimeTrackingMode.STOPWATCH,
                        null,
                    ),
                    createdAt = now.minusSeconds(2),
                )
            val sequence =
                authoring.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "S3 pinned sequence ${now.toEpochMilli()}",
                        null,
                        nodes =
                            listOf(
                                SequenceNodeDraft.Step(
                                    ActivityStepDraft(
                                        DraftIdentity.New("step"),
                                        0,
                                        StepActivityDraft.FromTemplate(activityA.id),
                                    ),
                                ),
                            ),
                    ),
                    createdAt = now.minusSeconds(1),
                )
            val library = LibraryRepository.create(context)
            val baseline = library.getPinned().map(LibraryTrackable::id)
            val activityAId = LibraryTemplateId.Activity(activityA.id)
            val activityBId = LibraryTemplateId.Activity(activityB.id)
            val sequenceId = LibraryTemplateId.Sequence(sequence.id)
            listOf(activityAId, activityBId, sequenceId).forEach(library::pin)
            val desired = listOf(sequenceId, activityBId, activityAId) + baseline
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val failNextRootRead = AtomicBoolean()
            val libraryController =
                LibraryController(
                    scope,
                    {
                        if (failNextRootRead.getAndSet(false)) error("post-commit browse failed")
                        library.getRoot()
                    },
                    library::getFolderContents,
                    library::getFolderPath,
                    library::search,
                    { LibraryOrganization(library.getFolders(), library.getTags()) },
                    { mutation ->
                        require(mutation is LibraryMutation.ReorderPinned)
                        library.reorderPinned(mutation.ids)
                        failNextRootRead.set(true)
                    },
                )
            try {
                withTimeout(5_000) { libraryController.state.first { it.browse is LibraryLoad.Content } }
                libraryController.dispatch(LibraryAction.ReorderPinned(desired))
                withTimeout(5_000) {
                    libraryController.state.first { !it.isMutating && it.browse is LibraryLoad.Failure }
                }
                assertNull(libraryController.state.value.mutationFailure)

                val freshLibrary = LibraryRepository.create(context)
                assertEquals(desired, freshLibrary.getPinned().map(LibraryTrackable::id))
                libraryController.dispatch(LibraryAction.Retry)
                withTimeout(5_000) {
                    libraryController.state.first { state ->
                        (state.browse as? LibraryLoad.Content)?.value?.pinned?.map(LibraryTrackable::id) == desired
                    }
                }
                val freshStart =
                    controller(
                        scope,
                        freshLibrary,
                        LiveSessionRepository.create(context),
                        FixedWallClock(now),
                        execute = { error("launcher writer is not used") },
                        readPinned = freshLibrary::getPinned,
                    )
                try {
                    val home =
                        withTimeout(5_000) { freshStart.state.first { it.home is LauncherLoad.Content } }
                            .let { (it.home as LauncherLoad.Content).value }
                    assertEquals(desired, home.pinned.map(LibraryTrackable::id))
                } finally {
                    freshStart.close()
                }

                val reloadedActivityA = requireNotNull(authoring.getActivityTemplate(activityA.id))
                val reloadedActivityB = requireNotNull(authoring.getActivityTemplate(activityB.id))
                val reloadedSequence = requireNotNull(authoring.getSequenceTemplate(sequence.id))
                assertEquals(activityA.revision, reloadedActivityA.revision)
                assertEquals(activityA.statisticsSeriesId, reloadedActivityA.statisticsSeriesId)
                assertEquals(activityB.revision, reloadedActivityB.revision)
                assertEquals(activityB.statisticsSeriesId, reloadedActivityB.statisticsSeriesId)
                assertEquals(sequence.revision, reloadedSequence.revision)
                assertEquals(sequence.statisticsSeriesId, reloadedSequence.statisticsSeriesId)
            } finally {
                libraryController.close()
                scope.cancel()
            }
        }

    private suspend fun resolvePrimedLibraryRoute(
        session: StartActivityRouteSession,
        id: LibraryTemplateId,
    ): LauncherCommit {
        withTimeout(5_000) { session.controller.state.first { it.home !is LauncherLoad.Loading } }
        session.primeInitialSelection(id)
        val target =
            withTimeout(5_000) {
                session.controller.state.first { it.selected is LauncherLoad.Content }
            }.let { (it.selected as LauncherLoad.Content).value }
        session.interaction.resolveSelection(target)?.let(session.controller::dispatch)
            ?: error("Expected Library priming to resolve an immediate launcher action")
        return withTimeout(5_000) {
            session.controller.state.first { it.command is LauncherCommandState.Committed }
        }.let { (it.command as LauncherCommandState.Committed).result }
    }

    private fun StartActivityController.loadedTarget() = (state.value.selected as LauncherLoad.Content).value

    private fun controller(
        scope: CoroutineScope,
        library: LibraryRepository,
        live: LiveSessionRepository,
        wallClock: WallClock,
        execute: suspend (LauncherDurableCommand) -> LauncherCommit,
        readPinned: suspend () -> List<LibraryTrackable> = { emptyList() },
        coordinate: suspend () -> Unit = {},
        scheduler: PreflightScheduler = PreflightScheduler { _, _ -> PreflightHandle {} },
        onSelectObserved: (LauncherCommandState) -> Unit = {},
        initialLiveConflict: (suspend (LibraryLaunchTarget) -> Boolean)? = null,
    ) = StartActivityController(
        scope,
        { emptyList() },
        readPinned,
        { emptyList() },
        { LibraryContents(emptyList(), emptyList(), emptyList()) },
        library::getLaunchTarget,
        {},
        { live.getActiveSession() != null },
        execute,
        coordinate,
        wallClock,
        { ZoneOffset.UTC },
        scheduler,
        initialLiveConflict =
            initialLiveConflict ?: { target -> library.hasLiveLaunchConflict(target.id, target.revision) },
        onSelectObserved = onSelectObserved,
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
