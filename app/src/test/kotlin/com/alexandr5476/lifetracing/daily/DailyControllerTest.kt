@file:Suppress("ComplexCondition", "LargeClass", "LongMethod") // Scenarios share one application-boundary harness.

package com.alexandr5476.lifetracing.daily

import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyActiveSequenceState
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DailyRead
import com.alexandr5476.lifetracing.domain.DailySequenceOccurrence
import com.alexandr5476.lifetracing.domain.EffectiveSequenceStepSettings
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline
import com.alexandr5476.lifetracing.domain.RuntimeOccurrence
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceExecution
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class DailyControllerTest {
    @Test
    fun initialStateUsesCurrentZoneTodayAndOneCanonicalRead() =
        runBlocking {
            val harness = Harness(Instant.parse("2026-03-07T22:30:00Z"), ZoneId.of("Europe/Moscow"))
            val controller = harness.controller(this)

            controller.awaitLoaded()

            assertEquals(LocalDate.parse("2026-03-08"), controller.state.value.selectedDate)
            assertEquals(DailyDateRelation.TODAY, controller.state.value.dateRelation)
            assertEquals(
                listOf(DailyQuery(LocalDate.parse("2026-03-08"), harness.wall.value, 100)),
                harness.queries,
            )
            controller.close()
        }

    @Test
    fun routeEntryReusesInitialReadButRefreshesOnceAfterBeingHidden() =
        runBlocking {
            val harness = Harness()
            val controller = harness.controller(this)
            harness.awaitReadCount(1)

            controller.onRouteEntered()
            controller.onRouteEntered()
            assertEquals(1, harness.queries.size)

            controller.dispatch(DailyAction.Hidden)
            assertEquals(1, harness.boundary.cancels)
            controller.onRouteEntered()
            harness.awaitReadCount(2)
            controller.onRouteEntered()

            assertEquals(2, harness.queries.size)
            controller.close()
        }

    @Test
    fun dateActionsReadOnlyRequestedDates() =
        runBlocking {
            val harness = Harness()
            val controller = harness.controller(this)
            harness.awaitReadCount(1)

            controller.dispatch(DailyAction.PreviousDay)
            harness.awaitReadCount(2)
            controller.dispatch(DailyAction.NextDay)
            harness.awaitReadCount(3)
            controller.dispatch(DailyAction.Today)
            harness.awaitReadCount(4)

            assertEquals(
                listOf("2026-08-20", "2026-08-19", "2026-08-20", "2026-08-20"),
                harness.queries.map { it.selectedDate.toString() },
            )
            assertTrue(harness.commands.isEmpty())
            controller.close()
        }

    @Test
    fun obsoleteSlowReadCannotReplaceNewerDateGeneration() =
        runBlocking {
            val slow = CompletableDeferred<DailyRead>()
            val harness = Harness()
            harness.reader = { query ->
                harness.queries += query
                when (query.selectedDate) {
                    LocalDate.parse("2026-08-19") -> slow.await()
                    else -> daily(activityRuntime("new"))
                }
            }
            val controller = harness.controller(this)
            controller.awaitLoaded()

            controller.dispatch(DailyAction.PreviousDay)
            harness.awaitReadCount(2)
            controller.dispatch(DailyAction.NextDay)
            controller.awaitLoadedExecution("new")
            slow.complete(daily(activityRuntime("old")))

            assertEquals(
                "new",
                controller
                    .loadedActivity()
                    .execution.id.value,
            )
            assertEquals(LocalDate.parse("2026-08-20"), controller.state.value.selectedDate)
            controller.close()
        }

    @Test
    fun readFailureClearsCurrentContentAndRetryReadsAgain() =
        runBlocking {
            var fail = false
            val harness = Harness()
            harness.reader = { query ->
                harness.queries += query
                if (fail) error("read failed") else daily(activityRuntime("first"))
            }
            val controller = harness.controller(this)
            controller.awaitLoaded()
            fail = true

            controller.dispatch(DailyAction.Retry)
            controller.awaitFailure()

            assertNull(controller.state.value.runtimeDisplayBaseline)
            assertInstanceOf(DailyLoadState.Failure::class.java, controller.state.value.load)
            fail = false
            controller.dispatch(DailyAction.Retry)
            harness.awaitReadCount(3)
            controller.awaitLoaded()
            assertEquals(3, harness.queries.size)
            controller.close()
        }

    @Test
    fun visibleAndCoordinatorInvalidationEachCauseBoundedRead() =
        runBlocking {
            val harness = Harness()
            val controller = harness.controller(this)
            harness.awaitReadCount(1)

            controller.dispatch(DailyAction.Visible)
            harness.awaitReadCount(2)
            harness.semantic.value++
            harness.awaitReadCount(3)

            assertEquals(3, harness.queries.size)
            controller.close()
        }

    @Test
    fun localMidnightReclassifiesWithoutChangingSelectionAndRearms() =
        runBlocking {
            val zone = ZoneId.of("America/New_York")
            val start = Instant.parse("2026-03-08T05:00:00Z")
            val harness = Harness(start, zone)
            val controller = harness.controller(this)
            harness.awaitReadCount(1)
            assertEquals(Instant.parse("2026-03-09T04:00:00Z"), nextLocalDateBoundary(start, zone))
            assertEquals(Duration.ofHours(23), Duration.between(start, nextLocalDateBoundary(start, zone)))

            harness.wall.value = Instant.parse("2026-03-09T04:00:00Z")
            harness.boundary.fire()
            harness.awaitReadCount(2)

            assertEquals(LocalDate.parse("2026-03-08"), controller.state.value.selectedDate)
            assertEquals(DailyDateRelation.PAST, controller.state.value.dateRelation)
            assertEquals(2, harness.boundary.arms.size)
            controller.close()
        }

    @Test
    fun displayTickUsesMatchedBaselineWithoutReading() =
        runBlocking {
            val runtime = activityRuntime("active")
            val baseline = RuntimeDisplayBaseline.capture(runtime, WallMonotonicAnchor(harnessInstant, 1_000), 1_000)
            val harness = Harness()
            harness.daily = daily(runtime)
            harness.baseline = baseline
            val controller = harness.controller(this)
            controller.awaitLoaded()
            val reads = harness.queries.size

            assertEquals(
                Duration.ofSeconds(5),
                requireNotNull(controller.state.value.runtimeDisplayBaseline).activeElapsed(6_000),
            )
            assertEquals(reads, harness.queries.size)
            controller.close()
        }

    @Test
    fun matchingBaselineIsExposedAndMismatchedExecutionIsRejected() =
        runBlocking {
            val first = activityRuntime("first")
            val second = activityRuntime("second")
            val harness = Harness()
            harness.daily = daily(first)
            harness.baseline = RuntimeDisplayBaseline.capture(first, WallMonotonicAnchor(harnessInstant, 0), 0)
            val controller = harness.controller(this)
            controller.awaitLoaded()
            assertTrue(
                controller.state.value.runtimeDisplayBaseline
                    ?.matches(first) == true,
            )

            harness.daily = daily(second)
            controller.dispatch(DailyAction.Retry)
            controller.awaitLoadedExecution("second")
            assertNull(controller.state.value.runtimeDisplayBaseline)
            controller.close()
        }

    @Test
    fun mismatchedSequenceOccurrenceAndCountdownBaselinesAreRejected() =
        runBlocking {
            val first = sequenceActive(DailyActiveSequenceState.RUNNING_CURRENT, SequenceOccurrenceId("first"))
            val second = sequenceActive(DailyActiveSequenceState.RUNNING_CURRENT, SequenceOccurrenceId("second"))
            val countdownFirst =
                sequenceActive(
                    DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN,
                    SequenceOccurrenceId("countdown-first"),
                )
            val countdownSecond =
                sequenceActive(
                    DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN,
                    SequenceOccurrenceId("countdown-second"),
                )
            val harness = Harness()
            harness.daily = daily(second)
            harness.baseline = RuntimeDisplayBaseline.capture(first.runtime, WallMonotonicAnchor(harnessInstant, 0), 0)
            val controller = harness.controller(this)
            controller.awaitLoaded()
            assertNull(controller.state.value.runtimeDisplayBaseline)

            harness.daily = daily(countdownSecond)
            harness.baseline =
                RuntimeDisplayBaseline.capture(countdownFirst.runtime, WallMonotonicAnchor(harnessInstant, 0), 0)
            controller.dispatch(DailyAction.Retry)
            harness.awaitReadCount(2)
            withTimeout(2_000) { controller.state.first { it.load is DailyLoadState.Content } }
            assertNull(controller.state.value.runtimeDisplayBaseline)
            controller.close()
        }

    @Test
    fun activityCommandsUseWallTimeGeneratedPauseIdAndCommitCoordinateReadOrder() =
        runBlocking {
            val cases =
                listOf(
                    ActiveSessionState.RUNNING to DailyAction.PauseActivity,
                    ActiveSessionState.PAUSED to DailyAction.ResumeActivity,
                    ActiveSessionState.RUNNING to DailyAction.FinishActivity,
                    ActiveSessionState.PAUSED to DailyAction.FinishActivity,
                )
            cases.forEachIndexed { index, (sessionState, action) ->
                val harness = Harness()
                harness.daily = daily(activityRuntime("activity-$index", sessionState))
                val controller = harness.controller(this)
                controller.awaitLoaded()
                harness.events.clear()

                controller.dispatch(action)
                harness.awaitCommandFinished(controller)

                assertEquals(listOf("repository", "coordinate", "read"), harness.events.take(3))
                assertEquals(harness.wall.value, harness.commands.single().at)
                if (action == DailyAction.PauseActivity) {
                    assertEquals(
                        ActivityExecutionPauseId("pause-id"),
                        (harness.commands.single() as DailyRuntimeCommand.PauseActivity).pauseId,
                    )
                }
                controller.close()
            }
        }

    @Test
    fun sequenceCommandsCoverEveryBasicCanonicalStateAndExactOccurrence() =
        runBlocking {
            val occurrenceId = SequenceOccurrenceId("current")
            val cases =
                listOf(
                    DailyActiveSequenceState.RUNNING_CURRENT to DailyAction.PauseSequence,
                    DailyActiveSequenceState.RUNNING_CURRENT to DailyAction.CompleteCurrentSequenceStep(occurrenceId),
                    DailyActiveSequenceState.PAUSED_CURRENT to DailyAction.ResumeSequence,
                    DailyActiveSequenceState.WAITING_NEXT to DailyAction.StartNextSequenceStep,
                    DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN to DailyAction.PauseSequence,
                    DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN to DailyAction.ResumeSequence,
                )
            cases.forEach { (state, action) ->
                val harness = Harness()
                harness.daily = daily(sequenceActive(state, occurrenceId))
                val controller = harness.controller(this)
                controller.awaitLoaded()

                controller.dispatch(action)
                harness.awaitCommandFinished(controller)

                assertEquals(harness.wall.value, harness.commands.single().at)
                if (action is DailyAction.CompleteCurrentSequenceStep) {
                    assertEquals(
                        occurrenceId,
                        (harness.commands.single() as DailyRuntimeCommand.CompleteCurrentSequenceStep).occurrenceId,
                    )
                }
                controller.close()
            }
        }

    @Test
    fun staleOccurrenceRejectionNeverRetargetsReplacementOccurrence() =
        runBlocking {
            val old = SequenceOccurrenceId("old")
            val replacement = SequenceOccurrenceId("replacement")
            val harness = Harness()
            var reads = 0
            harness.reader = { query ->
                harness.queries += query
                reads++
                daily(sequenceActive(DailyActiveSequenceState.RUNNING_CURRENT, if (reads == 1) old else replacement))
            }
            harness.repositoryFailure = IllegalArgumentException("stale occurrence")
            val controller = harness.controller(this)
            controller.awaitLoaded()

            controller.dispatch(DailyAction.CompleteCurrentSequenceStep(old))
            harness.awaitCommandFinished(controller)
            harness.awaitReadCount(2)

            assertEquals(
                old,
                (harness.commands.single() as DailyRuntimeCommand.CompleteCurrentSequenceStep).occurrenceId,
            )
            assertEquals(
                replacement,
                controller
                    .loadedSequence()
                    .current
                    ?.occurrence
                    ?.id,
            )
            assertInstanceOf(DailyCommandFailure.Rejected::class.java, controller.state.value.commandFailure)
            assertEquals(0, harness.coordinationCalls)
            controller.close()
        }

    @Test
    fun repositoryAndPostCommitFailuresRemainDistinctAndRecoverable() =
        runBlocking {
            val rejected =
                Harness().apply {
                    daily = daily(activityRuntime("rejected"))
                    repositoryFailure = IllegalStateException("rejected")
                }
            val rejectedController = rejected.controller(this)
            rejectedController.awaitLoaded()
            rejectedController.dispatch(DailyAction.FinishActivity)
            rejected.awaitCommandFinished(rejectedController)
            assertInstanceOf(DailyCommandFailure.Rejected::class.java, rejectedController.state.value.commandFailure)
            assertEquals(0, rejected.coordinationCalls)
            assertEquals(2, rejected.queries.size)
            rejectedController.close()

            val coordination =
                Harness().apply {
                    daily = daily(activityRuntime("committed"))
                    coordinationFailure = IllegalStateException("scheduler failed")
                }
            val coordinationController = coordination.controller(this)
            coordinationController.awaitLoaded()
            coordinationController.dispatch(DailyAction.FinishActivity)
            coordination.awaitCommandFinished(coordinationController)
            assertInstanceOf(
                DailyCommandFailure.Coordination::class.java,
                coordinationController.state.value.commandFailure,
            )
            assertEquals(1, coordination.commands.size)
            assertEquals(1, coordination.coordinationCalls)
            assertEquals(2, coordination.queries.size)
            coordinationController.close()
        }

    @Test
    fun runtimeApiContainsOnlyBasicActions() {
        val names =
            DailyAction::class.java.declaredClasses
                .map { it.simpleName }
                .toSet()

        assertFalse(names.any { it in setOf("GoNow", "MakeNext", "RuntimeAdd", "DoAgain", "EarlyEnd", "StartPlanned") })
    }

    private class Harness(
        initialInstant: Instant = harnessInstant,
        val zone: ZoneId = ZoneOffset.UTC,
    ) {
        val wall = MutableWallClock(initialInstant)
        val semantic = MutableStateFlow(0L)
        val boundary = FakeBoundary()
        val queries = mutableListOf<DailyQuery>()
        val commands = mutableListOf<DailyRuntimeCommand>()
        val events = mutableListOf<String>()
        var coordinationCalls = 0
        var daily: DailyRead = emptyDaily
        var baseline: RuntimeDisplayBaseline? = null
        var repositoryFailure: Exception? = null
        var coordinationFailure: Exception? = null
        var reader: suspend (DailyQuery) -> DailyRead = { query ->
            queries += query
            events += "read"
            daily
        }

        fun controller(scope: CoroutineScope) =
            DailyController(
                scope,
                { reader(it) },
                { command ->
                    events += "repository"
                    commands += command
                    repositoryFailure?.let { throw it }
                },
                {
                    events += "coordinate"
                    coordinationCalls++
                    coordinationFailure?.let { throw it }
                },
                semantic,
                { baseline },
                wall,
                { zone },
                { ActivityExecutionPauseId("pause-id") },
                boundary,
            )

        suspend fun awaitReadCount(count: Int) {
            withTimeout(2_000) {
                while (queries.size < count) kotlinx.coroutines.yield()
            }
        }

        suspend fun awaitCommandFinished(controller: DailyController) {
            withTimeout(2_000) {
                while (
                    commands.isEmpty() ||
                    controller.state.value.commandInFlight ||
                    controller.state.value.load is DailyLoadState.Loading ||
                    queries.size < 2
                ) {
                    kotlinx.coroutines.yield()
                }
            }
        }
    }

    private suspend fun DailyController.awaitLoaded() =
        withTimeout(2_000) { state.first { it.load is DailyLoadState.Empty || it.load is DailyLoadState.Content } }

    private suspend fun DailyController.awaitFailure() =
        withTimeout(2_000) { state.first { it.load is DailyLoadState.Failure } }

    private suspend fun DailyController.awaitLoadedExecution(id: String) =
        withTimeout(2_000) {
            state.first {
                (it.load as? DailyLoadState.Content)
                    ?.daily
                    ?.active
                    ?.runtime
                    .let { runtime -> (runtime as? ActiveActivityRuntime)?.execution?.id?.value } == id
            }
        }

    private fun DailyController.loadedActivity(): ActiveActivityRuntime =
        ((state.value.load as DailyLoadState.Content).daily.active as DailyActive.Activity).runtime

    private fun DailyController.loadedSequence(): DailyActive.Sequence =
        (state.value.load as DailyLoadState.Content).daily.active as DailyActive.Sequence

    private class MutableWallClock(
        var value: Instant,
    ) : WallClock {
        override fun now(): Instant = value
    }

    private class FakeBoundary : LocalDateBoundaryScheduler {
        data class Arm(
            val now: Instant,
            val zone: ZoneId,
            val callback: () -> Unit,
        )

        val arms = mutableListOf<Arm>()
        var cancels = 0

        override fun arm(
            now: Instant,
            zoneId: ZoneId,
            onBoundary: () -> Unit,
        ) {
            arms += Arm(now, zoneId, onBoundary)
        }

        override fun cancel() {
            cancels++
        }

        fun fire() = arms.last().callback()
    }

    private companion object {
        val harnessInstant: Instant = Instant.parse("2026-08-20T10:00:00Z")
        val emptyDaily = DailyRead(emptyList(), emptyList(), emptyList(), null)

        fun daily(runtime: ActiveActivityRuntime) =
            DailyRead(emptyList(), emptyList(), emptyList(), DailyActive.Activity(runtime))

        fun daily(active: DailyActive.Sequence) = DailyRead(emptyList(), emptyList(), emptyList(), active)

        fun activityRuntime(
            id: String,
            state: ActiveSessionState = ActiveSessionState.RUNNING,
        ): ActiveActivityRuntime {
            val snapshot = activitySnapshot("snapshot-$id")
            val execution =
                ActivityExecutionFactory {
                    ActivityExecutionId(id)
                }.startTimed(snapshot, harnessInstant, harnessInstant, ZoneOffset.UTC)
            return ActiveActivityRuntime(
                ActiveSession(ActiveSessionKind.ACTIVITY, state, execution.id, null, execution.updatedAt),
                execution.copy(
                    status =
                        if (state ==
                            ActiveSessionState.PAUSED
                        ) {
                            com.alexandr5476.lifetracing.domain.ActivityExecutionStatus.PAUSED
                        } else {
                            execution.status
                        },
                ),
                snapshot,
            )
        }

        fun sequenceActive(
            state: DailyActiveSequenceState,
            occurrenceId: SequenceOccurrenceId,
        ): DailyActive.Sequence {
            val activity = activitySnapshot("step")
            val hasCurrent =
                state == DailyActiveSequenceState.RUNNING_CURRENT || state == DailyActiveSequenceState.PAUSED_CURRENT
            val current =
                occurrence(
                    if (hasCurrent) occurrenceId else SequenceOccurrenceId("previous"),
                    RuntimeOccurrenceStatus.CURRENT,
                    harnessInstant,
                )
            val next =
                occurrence(
                    if (hasCurrent) SequenceOccurrenceId("next") else occurrenceId,
                    RuntimeOccurrenceStatus.NOT_STARTED,
                    null,
                    1,
                )
            val occurrences = listOf(current, next)
            val execution =
                SequenceExecution(
                    SequenceExecutionId("sequence-execution"),
                    SequenceSnapshotId("sequence"),
                    null,
                    if (state == DailyActiveSequenceState.PAUSED_CURRENT ||
                        state == DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
                    ) {
                        SequenceExecutionStatus.PAUSED
                    } else {
                        SequenceExecutionStatus.RUNNING
                    },
                    harnessInstant,
                    null,
                    null,
                    null,
                    null,
                    ZoneOffset.UTC,
                    0,
                    LocalDate.parse("2026-08-20"),
                    current.id.takeIf { hasCurrent },
                    harnessInstant,
                    harnessInstant,
                    occurrences,
                    emptyList(),
                )
            val snapshot = sequenceSnapshot(activity)
            val sessionState =
                when (state) {
                    DailyActiveSequenceState.PAUSED_CURRENT,
                    DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
                    -> ActiveSessionState.PAUSED
                    DailyActiveSequenceState.WAITING_NEXT -> ActiveSessionState.WAITING_NEXT
                    else -> ActiveSessionState.RUNNING
                }
            val runtime =
                ActiveSequenceRuntime(
                    ActiveSession(ActiveSessionKind.SEQUENCE, sessionState, null, execution.id, execution.updatedAt),
                    execution,
                    snapshot,
                    mapOf(activity.id to activity),
                    null,
                    if (!hasCurrent && state != DailyActiveSequenceState.WAITING_NEXT) next.id else null,
                )
            val occurrenceView =
                DailySequenceOccurrence(
                    current,
                    activity,
                    EffectiveSequenceStepSettings(Duration.ZERO, TimerZeroBehavior.FINISH, true, true, false),
                )
            return DailyActive.Sequence(
                runtime,
                state,
                occurrenceView.takeIf {
                    hasCurrent
                },
                DailySequenceOccurrence(next, activity, occurrenceView.effectiveSettings),
            )
        }

        fun activitySnapshot(id: String) =
            ActivityConfigSnapshot(
                ActivitySnapshotId(id),
                id,
                null,
                TimeTrackingMode.STOPWATCH,
                null,
                null,
                null,
                null,
                false,
                harnessInstant,
                ActivityTemplateSettings(),
            )

        fun sequenceSnapshot(activity: ActivityConfigSnapshot) =
            SequenceConfigSnapshot(
                SequenceSnapshotId("sequence"),
                "Sequence",
                null,
                null,
                null,
                null,
                harnessInstant,
                SequenceSnapshotSettings(
                    true,
                    Duration.ZERO,
                    Duration.ZERO,
                    true,
                    true,
                    false,
                    true,
                    true,
                    NoLiveTimeAccounting.ACTIVE,
                ),
                nodes = listOf(SequenceSnapshotActivityStep(SequenceSnapshotNodeId("step"), 0, activity.id)),
            )

        fun occurrence(
            id: SequenceOccurrenceId,
            status: RuntimeOccurrenceStatus,
            enteredAt: Instant?,
            position: Int = 0,
        ) = RuntimeOccurrence(
            id,
            SequenceSnapshotNodeId("step"),
            ActivitySnapshotId("step"),
            position,
            null,
            null,
            status,
            enteredAt,
            null,
            null,
            false,
            false,
        )
    }
}
