package com.alexandr5476.lifetracing.launcher

import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityLaunchMainValue
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.LiveSessionConflictException
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class StartActivityControllerTest {
    @Test
    fun homeUsesBoundedRecentOrderedPinnedAndRetryWithoutHydratingTargets() =
        runBlocking {
            val harness = Harness()
            harness.homeFailure = IllegalStateException("read failed")
            val controller = harness.controller(this)
            controller.awaitHomeFailure()

            assertEquals(listOf(12), harness.recentLimits)
            assertEquals(0, harness.targetReads.size)
            harness.homeFailure = null
            controller.dispatch(StartActivityAction.Retry)
            controller.awaitHome()

            val home = (controller.state.value.home as LauncherLoad.Content).value
            assertEquals(listOf("recent"), home.recent.map { it.name })
            assertEquals(listOf("pinned-2", "pinned-1"), home.pinned.map { it.name })
            assertEquals(listOf(12, 12), harness.recentLimits)
            assertEquals(1, harness.pinnedReads)

            controller.dispatch(StartActivityAction.Search("  "))
            assertTrue(controller.state.value.search is LauncherLoad.Idle)
            assertTrue(harness.searchQueries.isEmpty())
            controller.close()
        }

    @Test
    fun staleSearchBrowseAndSelectionReadsCannotReplaceTheLatestRequest() =
        runBlocking {
            val oldSearch = CompletableDeferred<List<LibraryTrackable>>()
            val newSearch = CompletableDeferred<List<LibraryTrackable>>()
            val oldBrowse = CompletableDeferred<LibraryContents>()
            val newBrowse = CompletableDeferred<LibraryContents>()
            val oldTarget = CompletableDeferred<LibraryLaunchTarget>()
            val newTarget = CompletableDeferred<LibraryLaunchTarget>()
            val harness = Harness()
            harness.searcher = { query ->
                harness.searchQueries += query
                if (query == "old") oldSearch.await() else newSearch.await()
            }
            harness.browser = { folder ->
                harness.browseRequests += folder
                if (folder?.value == "old") oldBrowse.await() else newBrowse.await()
            }
            harness.targetReader = { id ->
                harness.targetReads += id
                if (id.value == "old") oldTarget.await() else newTarget.await()
            }
            val controller = harness.controller(this)
            controller.awaitHome()

            controller.dispatch(StartActivityAction.Search("old"))
            controller.dispatch(StartActivityAction.Search("new"))
            newSearch.complete(listOf(trackable("new")))
            controller.awaitSearch("new")
            oldSearch.complete(listOf(trackable("old")))

            controller.dispatch(StartActivityAction.Browse(FolderId("old")))
            controller.dispatch(StartActivityAction.Browse(null))
            newBrowse.complete(contents("new"))
            controller.awaitBrowse("new")
            oldBrowse.complete(contents("old"))

            controller.dispatch(StartActivityAction.Select(activityId("old")))
            controller.dispatch(StartActivityAction.Select(activityId("new")))
            newTarget.complete(activityTarget("new"))
            controller.awaitTarget("new")
            oldTarget.complete(activityTarget("old"))
            kotlinx.coroutines.yield()

            assertEquals("new", controller.loadedTarget().name)
            assertEquals(
                "new",
                controller
                    .loadedBrowse()
                    .folders
                    .single()
                    .name,
            )
            assertEquals("new", controller.loadedSearch().single().name)
            controller.close()
        }

    @Test
    fun zeroCountdownCommitsImmediatelyOnceUsingOneBoundaryInstant() =
        runBlocking {
            val harness = Harness()
            harness.target = activityTarget("activity")
            val controller = harness.controller(this)
            controller.awaitHome()
            controller.selectAndAwait(activityId("activity"))

            controller.dispatch(StartActivityAction.Launch())
            controller.dispatch(StartActivityAction.Launch())
            controller.awaitCommitted()

            assertEquals(1, harness.commands.size)
            val command = harness.commands.single() as LauncherDurableCommand.StartActivity
            assertEquals(harness.wall.value, command.at)
            assertEquals(1, harness.coordinationCalls)
            controller.close()
        }

    @Test
    fun positivePreflightIsNonDurableAndCancelHideOrDuplicateBoundaryCannotStartTwice() =
        runBlocking {
            val harness = Harness()
            harness.target = activityTarget("activity", Duration.ofSeconds(3))
            val controller = harness.controller(this)
            controller.awaitHome()
            controller.selectAndAwait(activityId("activity"))

            controller.dispatch(StartActivityAction.Launch())
            controller.awaitPreflight()
            assertTrue(harness.commands.isEmpty())
            controller.dispatch(StartActivityAction.CancelPreflight)
            harness.scheduler.fireAllTwice()
            assertTrue(harness.commands.isEmpty())

            controller.dispatch(StartActivityAction.Launch())
            controller.awaitPreflight()
            harness.scheduler.fireLatestTwice()
            controller.awaitCommitted()
            assertEquals(1, harness.commands.size)

            val hiddenHarness = Harness().apply { target = activityTarget("hidden", Duration.ofSeconds(3)) }
            val hidden = hiddenHarness.controller(this)
            hidden.awaitHome()
            hidden.selectAndAwait(activityId("hidden"))
            hidden.dispatch(StartActivityAction.Launch())
            hidden.awaitPreflight()
            hidden.dispatch(StartActivityAction.Hidden)
            hiddenHarness.scheduler.fireAllTwice()
            assertTrue(hiddenHarness.commands.isEmpty())
            hidden.close()

            val closedHarness = Harness().apply { target = activityTarget("closed", Duration.ofSeconds(3)) }
            val closed = closedHarness.controller(this)
            closed.awaitHome()
            closed.selectAndAwait(activityId("closed"))
            closed.dispatch(StartActivityAction.Launch())
            closed.awaitPreflight()
            closed.close()
            closedHarness.scheduler.fireAllTwice()
            assertTrue(closedHarness.commands.isEmpty())
            controller.close()
        }

    @Test
    fun initialAndBoundaryLiveConflictsAreDistinctWhileNoLiveIgnoresTheSlot() =
        runBlocking {
            listOf(activityTarget("activity"), sequenceTarget("sequence")).forEach { target ->
                val initial =
                    Harness().apply {
                        this.target = target
                        live = true
                    }
                val initialController = initial.controller(this)
                initialController.awaitHome()
                initialController.selectAndAwait(target.id)
                initialController.dispatch(StartActivityAction.Launch())
                initialController.awaitConflict()
                assertTrue(initial.commands.isEmpty())
                initialController.close()
            }

            val race = Harness().apply { target = sequenceTarget("race", Duration.ofSeconds(2)) }
            race.writerFailure = LiveSessionConflictException()
            val raceController = race.controller(this)
            raceController.awaitHome()
            raceController.selectAndAwait(sequenceId("race"))
            raceController.dispatch(StartActivityAction.Launch())
            raceController.awaitPreflight()
            race.scheduler.fireLatestTwice()
            raceController.awaitConflict()
            assertEquals(1, race.commands.size)
            assertEquals(0, race.coordinationCalls)

            val noLive =
                Harness().apply {
                    target = noLiveTarget("quick")
                    live = true
                }
            val noLiveController = noLive.controller(this)
            noLiveController.awaitHome()
            noLiveController.selectAndAwait(activityId("quick"))
            val override = QuickMainValueOverride(ActivityTemplateFieldId("main"), QuickMainValue.Number(0))
            noLiveController.dispatch(StartActivityAction.Launch(override))
            noLiveController.awaitCommitted()
            val command = noLive.commands.single() as LauncherDurableCommand.CompleteNoLive
            assertEquals(override, command.override)
            assertEquals(0, noLive.liveChecks)
            assertEquals(0, noLive.coordinationCalls)

            raceController.close()
            noLiveController.close()
        }

    @Test
    fun committedCoordinationFailureCannotRetryTheWriter() =
        runBlocking {
            val harness =
                Harness().apply {
                    target = activityTarget("activity")
                    coordinationFailure = IllegalStateException("scheduler failed")
                }
            val controller = harness.controller(this)
            controller.awaitHome()
            controller.selectAndAwait(activityId("activity"))
            controller.dispatch(StartActivityAction.Launch())
            controller.awaitCoordinationFailure()

            controller.dispatch(StartActivityAction.Launch())
            controller.dispatch(StartActivityAction.RetryLaunch)
            kotlinx.coroutines.yield()

            assertEquals(1, harness.commands.size)
            val state = controller.state.value.command as LauncherCommandState.CommittedCoordinationFailure
            assertEquals("activity-execution", (state.result as LauncherCommit.Activity).executionId.value)
            controller.close()
        }

    @Test
    fun reorderPassesTheCompleteIdentityOrderAndRefreshesPinned() =
        runBlocking {
            val harness = Harness()
            val controller = harness.controller(this)
            controller.awaitHome()
            val order = listOf(sequenceId("sequence"), activityId("activity"))

            controller.dispatch(StartActivityAction.ReorderPinned(order))
            withTimeout(2_000) {
                controller.state.first {
                    !it.organizationInFlight && harness.reorders.isNotEmpty() && harness.pinnedReads == 2
                }
            }

            assertEquals(order, harness.reorders.single())
            assertEquals(2, harness.pinnedReads)
            controller.close()
        }

    private class Harness {
        val wall = MutableWallClock(NOW)
        val scheduler = FakePreflightScheduler()
        val recentLimits = mutableListOf<Int>()
        val searchQueries = mutableListOf<String>()
        val browseRequests = mutableListOf<FolderId?>()
        val targetReads = mutableListOf<LibraryTemplateId>()
        val reorders = mutableListOf<List<LibraryTemplateId>>()
        val commands = mutableListOf<LauncherDurableCommand>()
        var pinnedReads = 0
        var coordinationCalls = 0
        var liveChecks = 0
        var homeFailure: Exception? = null
        var writerFailure: Exception? = null
        var coordinationFailure: Exception? = null
        var live = false
        var target: LibraryLaunchTarget = activityTarget("activity")
        var searcher: suspend (String) -> List<LibraryTrackable> = { query ->
            searchQueries += query
            listOf(trackable(query))
        }
        var browser: suspend (FolderId?) -> LibraryContents = { folder ->
            browseRequests += folder
            contents(folder?.value ?: "root")
        }
        var targetReader: suspend (LibraryTemplateId) -> LibraryLaunchTarget = { id ->
            targetReads += id
            target
        }

        fun controller(scope: CoroutineScope) =
            StartActivityController(
                scope,
                { limit ->
                    recentLimits += limit
                    homeFailure?.let { throw it }
                    listOf(trackable("recent"))
                },
                {
                    pinnedReads++
                    listOf(trackable("pinned-2"), trackable("pinned-1"))
                },
                searcher,
                browser,
                targetReader,
                { reorders += it },
                {
                    liveChecks++
                    live
                },
                { command ->
                    commands += command
                    writerFailure?.let { throw it }
                    when (command) {
                        is LauncherDurableCommand.StartActivity ->
                            LauncherCommit.Activity(ActivityExecutionId("activity-execution"), true)
                        is LauncherDurableCommand.CompleteNoLive ->
                            LauncherCommit.Activity(ActivityExecutionId("no-live-execution"), false)
                        is LauncherDurableCommand.StartSequence ->
                            LauncherCommit.Sequence(SequenceExecutionId("sequence-execution"))
                    }
                },
                {
                    coordinationCalls++
                    coordinationFailure?.let { throw it }
                },
                wall,
                { ZoneOffset.UTC },
                scheduler,
            )
    }

    private class FakePreflightScheduler : PreflightScheduler {
        data class Scheduled(
            val duration: Duration,
            val callback: () -> Unit,
            var cancelled: Boolean = false,
        )

        val scheduled = mutableListOf<Scheduled>()

        override fun schedule(
            duration: Duration,
            onBoundary: () -> Unit,
        ): PreflightHandle {
            val item = Scheduled(duration, onBoundary)
            scheduled += item
            return PreflightHandle { item.cancelled = true }
        }

        fun fireLatestTwice() {
            scheduled.last().let { item ->
                item.callback()
                item.callback()
            }
        }

        fun fireAllTwice() =
            scheduled.forEach { item ->
                if (!item.cancelled) item.callback()
                if (!item.cancelled) item.callback()
            }
    }

    private class MutableWallClock(
        var value: Instant,
    ) : WallClock {
        override fun now(): Instant = value
    }

    private suspend fun StartActivityController.awaitHome() =
        withTimeout(2_000) { state.first { it.home is LauncherLoad.Content } }

    private suspend fun StartActivityController.awaitHomeFailure() =
        withTimeout(2_000) { state.first { it.home is LauncherLoad.Failure } }

    private suspend fun StartActivityController.awaitSearch(name: String) =
        withTimeout(2_000) { state.first { (it.search as? LauncherLoad.Content)?.value?.single()?.name == name } }

    private suspend fun StartActivityController.awaitBrowse(name: String) =
        withTimeout(
            2_000,
        ) {
            state.first {
                (it.browse as? LauncherLoad.Content)
                    ?.value
                    ?.folders
                    ?.single()
                    ?.name == name
            }
        }

    private suspend fun StartActivityController.awaitTarget(name: String) =
        withTimeout(2_000) { state.first { (it.selected as? LauncherLoad.Content)?.value?.name == name } }

    private suspend fun StartActivityController.selectAndAwait(id: LibraryTemplateId) {
        dispatch(StartActivityAction.Select(id))
        awaitTarget(id.value)
    }

    private suspend fun StartActivityController.awaitPreflight() =
        withTimeout(2_000) { state.first { it.command is LauncherCommandState.Preflight } }

    private suspend fun StartActivityController.awaitCommitted() =
        withTimeout(2_000) { state.first { it.command is LauncherCommandState.Committed } }

    private suspend fun StartActivityController.awaitConflict() =
        withTimeout(2_000) { state.first { it.command is LauncherCommandState.Conflict } }

    private suspend fun StartActivityController.awaitCoordinationFailure() =
        withTimeout(2_000) { state.first { it.command is LauncherCommandState.CommittedCoordinationFailure } }

    private fun StartActivityController.loadedTarget() = (state.value.selected as LauncherLoad.Content).value

    private fun StartActivityController.loadedSearch() = (state.value.search as LauncherLoad.Content).value

    private fun StartActivityController.loadedBrowse() = (state.value.browse as LauncherLoad.Content).value

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-20T10:00:00Z")

        fun activityId(id: String) = LibraryTemplateId.Activity(ActivityTemplateId(id))

        fun sequenceId(id: String) = LibraryTemplateId.Sequence(SequenceTemplateId(id))

        fun activityTarget(
            id: String,
            countdown: Duration = Duration.ZERO,
        ) = LibraryLaunchTarget.Activity(activityId(id), id, countdown, TimeTrackingMode.STOPWATCH, null)

        fun noLiveTarget(id: String) =
            LibraryLaunchTarget.Activity(
                activityId(id),
                id,
                Duration.ZERO,
                TimeTrackingMode.NO_LIVE_TRACKING,
                ActivityLaunchMainValue(ActivityTemplateFieldId("main"), "Value", "reps", 0, 5),
            )

        fun sequenceTarget(
            id: String,
            countdown: Duration = Duration.ZERO,
        ) = LibraryLaunchTarget.Sequence(sequenceId(id), id, countdown)

        fun trackable(name: String) = LibraryTrackable(activityId(name), name, null, null, emptySet(), null, null, null)

        fun contents(name: String) =
            LibraryContents(
                listOf(Folder(FolderId(name), name, null, NOW, NOW)),
                emptyList(),
                emptyList(),
            )
    }
}
