package com.alexandr5476.lifetracing.plan

import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.StalePlanActionException
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
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
import java.time.LocalDate
import java.time.ZoneOffset

class PlanExecutionControllerTest {
    @Test
    fun frozenCountdownHasNoDurableWriteAndDuplicateBoundaryCommitsOnce() =
        runBlocking {
            val harness = Harness(activityAction(Duration.ofSeconds(3)))
            val controller = harness.controller(this)
            controller.awaitPrepared()

            controller.launch()
            controller.awaitPreflight()
            assertTrue(harness.commands.isEmpty())
            harness.scheduler.fireTwice()
            controller.awaitCommitted()

            assertEquals(1, harness.commands.size)
            assertEquals(harness.action.identity, harness.commands.single().identity)
            assertEquals(1, harness.coordinations)
        }

    @Test
    fun hiddenCountdownIgnoresLateBoundaryAndZeroNoLiveCommitsWithoutPreflight() =
        runBlocking {
            val timed = Harness(activityAction(Duration.ofSeconds(3)))
            val timedController = timed.controller(this)
            timedController.awaitPrepared()
            timedController.launch()
            timedController.awaitPreflight()
            timedController.setVisible(false)
            timed.scheduler.fireTwice()
            assertTrue(timed.commands.isEmpty())

            val quick = Harness(activityAction(Duration.ofSeconds(9), TimeTrackingMode.NO_LIVE_TRACKING))
            val quickController = quick.controller(this)
            quickController.awaitPrepared()
            quickController.launch()
            quickController.awaitCommitted()
            quickController.launch()
            assertEquals(1, quick.commands.size)
            assertTrue(quick.commands.single().noLive)
            assertTrue(quick.scheduler.scheduled.isEmpty())
            assertEquals(0, quick.coordinations)
        }

    @Test
    fun zeroLiveCommitsDirectlyAndFreshControllerCannotRecoverAbandonedPreflight() =
        runBlocking {
            val zero = Harness(activityAction(Duration.ZERO))
            val zeroController = zero.controller(this)
            zeroController.awaitPrepared()
            zeroController.launch()
            zeroController.awaitCommitted()
            assertTrue(zero.scheduler.scheduled.isEmpty())
            assertEquals(1, zero.coordinations)

            val abandoned = Harness(activityAction(Duration.ofSeconds(3)))
            val first = abandoned.controller(this)
            first.awaitPrepared()
            first.launch()
            first.awaitPreflight()
            first.close()
            abandoned.scheduler.fireTwice()
            assertTrue(abandoned.commands.isEmpty())

            val fresh = abandoned.controller(this)
            fresh.awaitPrepared()
            assertEquals(PlanExecutionCommandState.Idle, fresh.state.value.command)
            assertTrue(abandoned.commands.isEmpty())
        }

    @Test
    fun durableStaleAndFinalLiveConflictHaveTypedOutcomes() =
        runBlocking {
            val stale = Harness(activityAction(Duration.ZERO)).apply { failure = StalePlanActionException() }
            val staleController = stale.controller(this)
            staleController.awaitPrepared()
            staleController.launch()
            withTimeout(2_000) { staleController.state.first { it.command == PlanExecutionCommandState.Stale } }

            val conflict =
                Harness(activityAction(Duration.ofSeconds(3)))
            val conflictController = conflict.controller(this)
            conflictController.awaitPrepared()
            conflictController.launch()
            conflictController.awaitPreflight()
            conflict.failure =
                com.alexandr5476.lifetracing.domain
                    .LiveSessionConflictException()
            conflict.scheduler.fireTwice()
            withTimeout(2_000) {
                conflictController.state.first { it.command is PlanExecutionCommandState.Conflict }
            }
        }

    @Test
    fun lifecycleExitAfterCommittingDoesNotCancelTheAdmittedCommand() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness =
                Harness(activityAction(Duration.ofSeconds(3))).apply {
                    beforeExecute = {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            val controller = harness.controller(this)
            controller.awaitPrepared()
            controller.launch()
            controller.awaitPreflight()

            harness.scheduler.fireTwice()
            withTimeout(2_000) { entered.await() }
            assertTrue(controller.state.value.command is PlanExecutionCommandState.Committing)
            controller.setVisible(false)
            controller.cancelPreflight()
            controller.close()
            release.complete(Unit)
            controller.awaitCommitted()

            assertEquals(1, harness.commands.size)
            assertEquals(1, harness.coordinations)
        }

    @Test
    fun sequenceUsesFrozenFirstStepOverridePrecedence() {
        val action = sequenceAction(Duration.ofSeconds(7), Duration.ofSeconds(2))
        assertEquals(Duration.ofSeconds(2), preparePlanExecutionTarget(action).countdown)
    }

    @Test
    fun focusedPreparationRejectsAPlanChangedAfterTheClickedIdentityWasCaptured() =
        runBlocking {
            val clicked = activityAction(Duration.ZERO)
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            val harness =
                Harness(clicked).apply {
                    beforeRead = {
                        readStarted.complete(Unit)
                        releaseRead.await()
                    }
                    readAction = clicked.copy(identity = clicked.identity.copy(updatedAt = NOW.plusSeconds(1)))
                }
            val controller = harness.controller(this)

            withTimeout(2_000) { readStarted.await() }
            releaseRead.complete(Unit)
            withTimeout(2_000) { controller.state.first { it.command == PlanExecutionCommandState.Stale } }
            controller.launch()

            assertTrue(harness.commands.isEmpty())
            assertTrue(controller.state.value.prepared !is PlanExecutionLoad.Content)
        }

    @Test
    fun focusedPreparationPublishesTheExactClickedIdentityWhenItIsStillCurrent() =
        runBlocking {
            val clicked = activityAction(Duration.ZERO)
            val controller = Harness(clicked).controller(this)

            val prepared = controller.awaitPrepared().prepared as PlanExecutionLoad.Content

            assertEquals(clicked.identity, prepared.value.action.identity)
        }

    @Test
    fun focusedPreparationTreatsNewEngagementAsStaleEvenThoughStoredPlanIdentityIsUnchanged() =
        runBlocking {
            val clicked = activityAction(Duration.ZERO)
            val harness = Harness(clicked).apply { readAction = clicked.copy(engaged = true) }
            val controller = harness.controller(this)

            withTimeout(2_000) { controller.state.first { it.command == PlanExecutionCommandState.Stale } }

            assertTrue(harness.commands.isEmpty())
        }

    private class Harness(
        val action: FocusedPlanAction,
    ) {
        val scheduler = FakeScheduler()
        val commands = mutableListOf<PlanExecutionDurableCommand>()
        var failure: Exception? = null
        var coordinations = 0
        var readAction = action
        var beforeRead: suspend () -> Unit = {}
        var beforeExecute: suspend () -> Unit = {}

        fun controller(scope: CoroutineScope) =
            PlanExecutionController(
                scope,
                action.identity,
                {
                    beforeRead()
                    readAction
                },
                { false },
                { command ->
                    beforeExecute()
                    commands += command
                    failure?.let { throw it }
                    if (command.identity.kind == PlanTrackableKind.ACTIVITY) {
                        PlanExecutionCommit.Activity(ActivityExecutionId("activity"), !command.noLive)
                    } else {
                        PlanExecutionCommit.Sequence(
                            com.alexandr5476.lifetracing.domain
                                .SequenceExecutionId("sequence"),
                        )
                    }
                },
                { coordinations++ },
                WallClock { NOW },
                { ZoneOffset.UTC },
                scheduler,
            )
    }

    private class FakeScheduler : PreflightScheduler {
        data class Item(
            val callback: () -> Unit,
            var cancelled: Boolean = false,
        )

        val scheduled = mutableListOf<Item>()

        override fun schedule(
            duration: Duration,
            onBoundary: () -> Unit,
        ): PreflightHandle =
            Item(onBoundary).also(scheduled::add).let { item ->
                PreflightHandle { item.cancelled = true }
            }

        fun fireTwice() =
            scheduled.last().run {
                callback()
                callback()
            }
    }

    private suspend fun PlanExecutionController.awaitPrepared() =
        withTimeout(2_000) { state.first { it.prepared is PlanExecutionLoad.Content } }

    private suspend fun PlanExecutionController.awaitPreflight() =
        withTimeout(2_000) { state.first { it.command is PlanExecutionCommandState.Preflight } }

    private suspend fun PlanExecutionController.awaitCommitted() =
        withTimeout(2_000) { state.first { it.command is PlanExecutionCommandState.Committed } }

    private companion object {
        val NOW = Instant.parse("2026-09-15T10:00:00Z")

        fun activityAction(
            countdown: Duration,
            mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        ): FocusedPlanAction {
            val snapshot =
                ActivityConfigSnapshot(
                    ActivitySnapshotId("activity-snapshot"),
                    "Activity",
                    null,
                    mode,
                    null,
                    null,
                    null,
                    null,
                    false,
                    NOW,
                    ActivityTemplateSettings(startCountdown = countdown),
                )
            return FocusedPlanAction(
                identity(PlanTrackableKind.ACTIVITY, activity = snapshot.id),
                PlanSourceState.UNAVAILABLE,
                false,
                FocusedPlanAction.Snapshot.Activity(snapshot),
            )
        }

        fun sequenceAction(
            sequenceCountdown: Duration,
            stepCountdown: Duration,
        ): FocusedPlanAction {
            val activity = activityAction(Duration.ofSeconds(99)).snapshot as FocusedPlanAction.Snapshot.Activity
            val sequence =
                SequenceConfigSnapshot(
                    SequenceSnapshotId("sequence-snapshot"),
                    "Sequence",
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    SequenceSnapshotSettings(
                        false,
                        sequenceCountdown,
                        Duration.ZERO,
                        false,
                        false,
                        false,
                        false,
                        false,
                        com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting.ACTIVE,
                    ),
                    nodes =
                        listOf(
                            SequenceSnapshotActivityStep(
                                SequenceSnapshotNodeId("step"),
                                0,
                                activity.value.id,
                                SequenceStepOverrides(startCountdown = stepCountdown),
                            ),
                        ),
                )
            return FocusedPlanAction(
                identity(PlanTrackableKind.SEQUENCE, sequence = sequence.id),
                PlanSourceState.UNAVAILABLE,
                false,
                FocusedPlanAction.Snapshot.Sequence(sequence, mapOf(activity.value.id to activity.value)),
            )
        }

        fun identity(
            kind: PlanTrackableKind,
            activity: ActivitySnapshotId? = null,
            sequence: SequenceSnapshotId? = null,
        ) = PlanActionIdentity(
            PlanEntryId("plan"),
            kind,
            activity,
            sequence,
            PlanTarget.FloatingDay(LocalDate.parse("2026-09-15")),
            PlanEntryStatus.PLANNED,
            null,
            NOW,
        )
    }
}
