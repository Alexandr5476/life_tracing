@file:Suppress(
    "ComplexCondition",
    "LongParameterList",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.alexandr5476.lifetracing.plan

import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionValueOverride
import com.alexandr5476.lifetracing.domain.EffectiveSequenceStepSettingsResolver
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.LiveSessionConflictException
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotRepeatBlock
import com.alexandr5476.lifetracing.domain.StalePlanActionException
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.runtime.RuntimeMutationGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

sealed interface PlanExecutionLoad<out T> {
    data object Loading : PlanExecutionLoad<Nothing>

    data class Content<T>(
        val value: T,
    ) : PlanExecutionLoad<T>

    data class Failure(
        val message: String,
    ) : PlanExecutionLoad<Nothing>
}

data class PreparedPlanExecution(
    val action: FocusedPlanAction,
    val countdown: Duration,
    val isLive: Boolean,
)

sealed interface PlanExecutionCommandState {
    data object Idle : PlanExecutionCommandState

    data class Checking(
        val attemptId: Long,
    ) : PlanExecutionCommandState

    data class Preflight(
        val attemptId: Long,
        val duration: Duration,
        val startedAt: Instant,
        val endsAt: Instant,
    ) : PlanExecutionCommandState

    data class Committing(
        val attemptId: Long,
    ) : PlanExecutionCommandState

    data object Stale : PlanExecutionCommandState

    data class Conflict(
        val message: String,
    ) : PlanExecutionCommandState

    data class Rejected(
        val message: String,
    ) : PlanExecutionCommandState

    data class Committed(
        val result: PlanExecutionCommit,
    ) : PlanExecutionCommandState

    data class CommittedCoordinationFailure(
        val result: PlanExecutionCommit,
        val message: String,
    ) : PlanExecutionCommandState
}

data class PlanExecutionState(
    val prepared: PlanExecutionLoad<PreparedPlanExecution> = PlanExecutionLoad.Loading,
    val command: PlanExecutionCommandState = PlanExecutionCommandState.Idle,
)

sealed interface PlanExecutionCommit {
    val isLive: Boolean

    data class Activity(
        val executionId: ActivityExecutionId,
        override val isLive: Boolean,
    ) : PlanExecutionCommit

    data class Sequence(
        val executionId: SequenceExecutionId,
    ) : PlanExecutionCommit {
        override val isLive = true
    }
}

internal data class PlanExecutionDurableCommand(
    val identity: PlanActionIdentity,
    val noLive: Boolean,
    val values: List<ActivityExecutionValueOverride>,
    val at: Instant,
    val zoneId: ZoneId,
)

class PlanExecutionController internal constructor(
    private val scope: CoroutineScope,
    val expectedIdentity: PlanActionIdentity,
    private val readFocusedAction: suspend (PlanEntryId) -> FocusedPlanAction,
    private val hasLiveSession: suspend () -> Boolean,
    private val execute: suspend (PlanExecutionDurableCommand) -> PlanExecutionCommit,
    private val coordinateRuntimeStateChanged: suspend () -> Unit,
    private val wallClock: WallClock,
    private val zoneId: () -> ZoneId,
    private val preflightScheduler: PreflightScheduler,
    private val mutationGate: RuntimeMutationGate = RuntimeMutationGate(),
) {
    private val lock = Any()
    private val generation = AtomicLong()
    private val mutableState = MutableStateFlow(PlanExecutionState())
    val state: StateFlow<PlanExecutionState> = mutableState

    private var visible = true
    private var closed = false
    private var job: Job? = null
    private var preflight: PreflightHandle? = null

    init {
        prepare()
    }

    fun retryPreparation() = prepare()

    fun launch(values: List<ActivityExecutionValueOverride> = emptyList()) {
        val target = (mutableState.value.prepared as? PlanExecutionLoad.Content)?.value ?: return
        synchronized(lock) {
            if (closed || !visible || mutableState.value.command != PlanExecutionCommandState.Idle) return
            val attempt = generation.incrementAndGet()
            mutableState.update { it.copy(command = PlanExecutionCommandState.Checking(attempt)) }
            job = scope.launch { checkAndStart(attempt, target, values) }
        }
    }

    fun cancelPreflight() = synchronized(lock) { cancelPendingLocked() }

    fun setVisible(value: Boolean) {
        synchronized(lock) {
            visible = value
            if (!value) cancelPendingLocked()
        }
    }

    fun close() {
        synchronized(lock) {
            if (mutableState.value.command !is PlanExecutionCommandState.Committing) job?.cancel()
            closed = true
            cancelPendingLocked()
        }
    }

    private fun prepare() {
        var readGeneration = 0L
        synchronized(lock) {
            if (closed || mutableState.value.command is PlanExecutionCommandState.Committing) return
            readGeneration = generation.incrementAndGet()
            job?.cancel()
            mutableState.value = PlanExecutionState()
            job =
                scope.launch {
                    try {
                        val action = readFocusedAction(expectedIdentity.planEntryId)
                        if (action.identity != expectedIdentity || action.engaged) throw StalePlanActionException()
                        val target = preparePlanExecutionTarget(action)
                        synchronized(lock) {
                            if (!closed && generation.get() == readGeneration) {
                                mutableState.update { it.copy(prepared = PlanExecutionLoad.Content(target)) }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: StalePlanActionException) {
                        synchronized(lock) {
                            if (!closed && generation.get() == readGeneration) {
                                mutableState.update { it.copy(command = PlanExecutionCommandState.Stale) }
                            }
                        }
                    } catch (failure: Exception) {
                        synchronized(lock) {
                            if (!closed && generation.get() == readGeneration) {
                                mutableState.update {
                                    it.copy(prepared = PlanExecutionLoad.Failure(failure.message()))
                                }
                            }
                        }
                    }
                }
        }
    }

    private suspend fun checkAndStart(
        attempt: Long,
        target: PreparedPlanExecution,
        values: List<ActivityExecutionValueOverride>,
    ) {
        try {
            if (target.isLive && hasLiveSession()) {
                publish(attempt, PlanExecutionCommandState.Conflict("Another live session is already active"))
            } else if (target.countdown.isZero) {
                beginCommit(attempt, target, values, false)
            } else {
                startPreflight(attempt, target, values)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publish(attempt, PlanExecutionCommandState.Rejected(failure.message()))
        }
    }

    private fun startPreflight(
        attempt: Long,
        target: PreparedPlanExecution,
        values: List<ActivityExecutionValueOverride>,
    ) {
        val startedAt = wallClock.now()
        if (!publish(
                attempt,
                PlanExecutionCommandState.Preflight(
                    attempt,
                    target.countdown,
                    startedAt,
                    startedAt.plus(target.countdown),
                ),
            )
        ) {
            return
        }
        val handle = preflightScheduler.schedule(target.countdown) { beginCommit(attempt, target, values, true) }
        synchronized(lock) {
            if (generation.get() == attempt && mutableState.value.command is PlanExecutionCommandState.Preflight) {
                preflight = handle
            } else {
                handle.cancel()
            }
        }
    }

    private fun beginCommit(
        attempt: Long,
        target: PreparedPlanExecution,
        values: List<ActivityExecutionValueOverride>,
        fromPreflight: Boolean,
    ) {
        synchronized(lock) {
            val command = mutableState.value.command
            val expected =
                if (fromPreflight) {
                    command is PlanExecutionCommandState.Preflight
                } else {
                    command is PlanExecutionCommandState.Checking
                }
            if (!expected || generation.get() != attempt || closed || !visible) return
            preflight?.cancel()
            preflight = null
            mutableState.update { it.copy(command = PlanExecutionCommandState.Committing(attempt)) }
            job = scope.launch { commit(attempt, target, values) }
        }
    }

    private suspend fun commit(
        attempt: Long,
        target: PreparedPlanExecution,
        values: List<ActivityExecutionValueOverride>,
    ) {
        val admission =
            requireNotNull(
                mutationGate.admit {
                    PlanExecutionDurableCommand(
                        target.action.identity,
                        !target.isLive,
                        values,
                        wallClock.now(),
                        zoneId(),
                    )
                },
            )
        val committed =
            try {
                admission.turn.run { execute(admission.command) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StalePlanActionException) {
                publishCommit(attempt, PlanExecutionCommandState.Stale)
                return
            } catch (conflict: LiveSessionConflictException) {
                publishCommit(attempt, PlanExecutionCommandState.Conflict(conflict.message()))
                return
            } catch (failure: Exception) {
                publishCommit(attempt, PlanExecutionCommandState.Rejected(failure.message()))
                return
            }
        if (!committed.isLive) {
            publishCommit(attempt, PlanExecutionCommandState.Committed(committed))
            return
        }
        try {
            coordinateRuntimeStateChanged()
            publishCommit(attempt, PlanExecutionCommandState.Committed(committed))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publishCommit(
                attempt,
                PlanExecutionCommandState.CommittedCoordinationFailure(committed, failure.message()),
            )
        }
    }

    private fun publish(
        attempt: Long,
        command: PlanExecutionCommandState,
    ): Boolean =
        synchronized(lock) {
            if (closed || !visible || generation.get() != attempt) {
                false
            } else {
                mutableState.update { it.copy(command = command) }
                true
            }
        }

    private fun publishCommit(
        attempt: Long,
        command: PlanExecutionCommandState,
    ) {
        synchronized(lock) {
            val current = mutableState.value.command
            if (generation.get() == attempt && current is PlanExecutionCommandState.Committing) {
                mutableState.update { it.copy(command = command) }
            }
        }
    }

    private fun cancelPendingLocked() {
        when (mutableState.value.command) {
            is PlanExecutionCommandState.Checking,
            is PlanExecutionCommandState.Preflight,
            -> {
                generation.incrementAndGet()
                job?.cancel()
                job = null
                preflight?.cancel()
                preflight = null
                mutableState.update { it.copy(command = PlanExecutionCommandState.Idle) }
            }
            else -> Unit
        }
    }

    private fun Throwable.message() = message ?: javaClass.simpleName
}

internal fun preparePlanExecutionTarget(action: FocusedPlanAction): PreparedPlanExecution {
    require(
        action.identity.status == PlanEntryStatus.PLANNED &&
            !action.engaged &&
            action.identity.target !is PlanTarget.Month,
    ) { "Plan is not startable" }
    return when (val snapshot = action.snapshot) {
        is FocusedPlanAction.Snapshot.Activity -> {
            require(action.identity.kind == PlanTrackableKind.ACTIVITY)
            require(action.identity.activitySnapshotId == snapshot.value.id)
            val live = snapshot.value.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING
            PreparedPlanExecution(action, if (live) snapshot.value.settings.startCountdown else Duration.ZERO, live)
        }
        is FocusedPlanAction.Snapshot.Sequence -> {
            require(action.identity.kind == PlanTrackableKind.SEQUENCE)
            require(action.identity.sequenceSnapshotId == snapshot.value.id)
            val node = requireNotNull(snapshot.value.nodes.minByOrNull { it.position }) { "Sequence has no Steps" }
            val step =
                when (node) {
                    is SequenceSnapshotActivityStep -> node
                    is SequenceSnapshotRepeatBlock ->
                        requireNotNull(node.children.minByOrNull { it.position }) { "Repeat has no Steps" }
                }
            val activity = requireNotNull(snapshot.activitySnapshots[step.activitySnapshotId])
            val countdown =
                EffectiveSequenceStepSettingsResolver
                    .resolve(step, activity, snapshot.value.settings, true)
                    .startCountdown
            PreparedPlanExecution(action, countdown, true)
        }
    }
}
