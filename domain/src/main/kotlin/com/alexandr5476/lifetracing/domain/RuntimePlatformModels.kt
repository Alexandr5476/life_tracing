@file:Suppress("ReturnCount") // Deadline/state helpers use guard clauses to keep invalid states explicit.

package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant

fun interface WallClock {
    fun now(): Instant
}

fun interface MonotonicClock {
    fun elapsedRealtimeMillis(): Long
}

data class WallMonotonicAnchor(
    val wallAtAnchor: Instant,
    val elapsedAtAnchorMs: Long,
) {
    fun wallAt(elapsedNowMs: Long): Instant =
        Instant.ofEpochMilli(
            Math.addExact(
                wallAtAnchor.toEpochMilli(),
                Math.subtractExact(elapsedNowMs, elapsedAtAnchorMs),
            ),
        )
}

sealed interface ActiveRuntime {
    val session: ActiveSession
}

data class ActiveActivityRuntime(
    override val session: ActiveSession,
    val execution: ActivityExecution,
    val snapshot: ActivityConfigSnapshot,
) : ActiveRuntime

data class ActiveSequenceRuntime(
    override val session: ActiveSession,
    val execution: SequenceExecution,
    val snapshot: SequenceConfigSnapshot,
    val activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    val currentChild: ActivityExecution?,
) : ActiveRuntime

enum class RuntimeDeadlineKind {
    ACTIVITY_TIMER_ZERO,
    SEQUENCE_TIMER_ZERO,
    SEQUENCE_TRANSITION_COUNTDOWN,
}

data class RuntimeDeadline(
    val at: Instant,
    val kind: RuntimeDeadlineKind,
    val sessionKind: ActiveSessionKind,
    val executionId: String,
    val expectedOccurrenceId: SequenceOccurrenceId? = null,
)

data class RuntimeDeadlineFeedback(
    val deadline: RuntimeDeadline,
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
)

data class RuntimeReconciliationResult(
    val finalSession: ActiveSession?,
    val appliedEvents: List<RuntimeDeadlineFeedback>,
)

object NextRuntimeDeadlineResolver {
    fun resolve(runtime: ActiveRuntime): RuntimeDeadline? {
        if (runtime.session.state != ActiveSessionState.RUNNING) return null
        return when (runtime) {
            is ActiveActivityRuntime -> activityDeadline(runtime)
            is ActiveSequenceRuntime -> sequenceDeadline(runtime)
        }
    }

    private fun activityDeadline(runtime: ActiveActivityRuntime): RuntimeDeadline? {
        if (runtime.snapshot.timeTrackingMode != TimeTrackingMode.TIMER) return null
        val deadline =
            TimerDeadlineCalculator.deadline(
                runtime.execution,
                requireNotNull(runtime.snapshot.timerTarget),
                runtime.snapshot.settings.timerZeroBehavior,
            ) ?: return null
        return RuntimeDeadline(
            deadline,
            RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO,
            ActiveSessionKind.ACTIVITY,
            runtime.execution.id.value,
        )
    }

    private fun sequenceDeadline(runtime: ActiveSequenceRuntime): RuntimeDeadline? {
        val currentId = runtime.execution.currentOccurrenceId
        if (currentId != null) {
            val occurrence = runtime.execution.occurrences.single { it.id == currentId }
            val activity = runtime.activitySnapshots.getValue(occurrence.activitySnapshotId)
            val child = runtime.currentChild ?: return null
            if (activity.timeTrackingMode != TimeTrackingMode.TIMER) return null
            val settings = effectiveSettings(runtime, occurrence)
            val deadline =
                TimerDeadlineCalculator.deadline(
                    child,
                    requireNotNull(activity.timerTarget),
                    requireNotNull(settings.timerZeroBehavior),
                ) ?: return null
            return RuntimeDeadline(
                deadline,
                RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO,
                ActiveSessionKind.SEQUENCE,
                runtime.execution.id.value,
                currentId,
            )
        }

        val open = runtime.execution.intervals.singleOrNull { it.endedAt == null } ?: return null
        if (open.kind != SequenceIntervalKind.TRANSITION_COUNTDOWN) return null
        val targetId = requireNotNull(open.occurrenceId)
        val target = runtime.execution.occurrences.single { it.id == targetId }
        val requiredMs = effectiveSettings(runtime, target).startCountdown.toMillis()
        val consumedMs =
            runtime.execution.intervals
                .asSequence()
                .filter {
                    it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN &&
                        it.occurrenceId == targetId &&
                        it.endedAt != null
                }.fold(0L) { total, interval ->
                    Math.addExact(
                        total,
                        Duration.between(interval.startedAt, requireNotNull(interval.endedAt)).toMillis(),
                    )
                }
        val remainingMs = Math.subtractExact(requiredMs, consumedMs)
        require(remainingMs > 0) { "Transition countdown must retain positive remaining time" }
        return RuntimeDeadline(
            Instant.ofEpochMilli(Math.addExact(open.startedAt.toEpochMilli(), remainingMs)),
            RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN,
            ActiveSessionKind.SEQUENCE,
            runtime.execution.id.value,
            targetId,
        )
    }

    fun effectiveSettings(
        runtime: ActiveSequenceRuntime,
        occurrence: RuntimeOccurrence,
    ): EffectiveSequenceStepSettings {
        val sourceId =
            requireNotNull(occurrence.sourceSequenceSnapshotNodeId) {
                "Runtime-added Steps are out of scope"
            }
        val step =
            runtime.snapshot.nodes
                .flatMap { node ->
                    when (node) {
                        is SequenceSnapshotActivityStep -> listOf(node)
                        is SequenceSnapshotRepeatBlock -> node.children
                    }
                }.single { it.id == sourceId }
        return EffectiveSequenceStepSettingsResolver.resolve(
            step,
            runtime.activitySnapshots.getValue(occurrence.activitySnapshotId),
            runtime.snapshot.settings,
            false,
        )
    }
}

object RuntimeDisplayDurations {
    fun activeElapsed(
        runtime: ActiveRuntime,
        observedWall: Instant,
    ): Duration =
        when (runtime) {
            is ActiveActivityRuntime -> activityElapsed(runtime.execution, observedWall)
            is ActiveSequenceRuntime -> sequenceElapsed(runtime.execution, observedWall)
        }

    fun timerRemaining(
        runtime: ActiveRuntime,
        observedWall: Instant,
    ): Duration? {
        val deadline = naturalTimerDeadline(runtime) ?: return null
        return Duration.between(observedWall, deadline).coerceAtLeast(Duration.ZERO)
    }

    fun timerOvertime(
        runtime: ActiveRuntime,
        observedWall: Instant,
    ): Duration? {
        val zeroBehavior = timerZeroBehavior(runtime) ?: return null
        if (zeroBehavior != TimerZeroBehavior.OVERTIME) return null
        val deadline = naturalTimerDeadline(runtime) ?: return null
        return Duration.between(deadline, observedWall).coerceAtLeast(Duration.ZERO)
    }

    fun transitionCountdownRemaining(
        runtime: ActiveSequenceRuntime,
        observedWall: Instant,
    ): Duration? {
        val deadline = NextRuntimeDeadlineResolver.resolve(runtime)
        if (deadline?.kind != RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN) return null
        return Duration.between(observedWall, deadline.at).coerceAtLeast(Duration.ZERO)
    }

    private fun naturalTimerDeadline(runtime: ActiveRuntime): Instant? =
        when (runtime) {
            is ActiveActivityRuntime ->
                if (runtime.snapshot.timeTrackingMode == TimeTrackingMode.TIMER) {
                    TimerDeadlineCalculator.deadline(
                        runtime.execution,
                        requireNotNull(runtime.snapshot.timerTarget),
                        TimerZeroBehavior.FINISH,
                    )
                } else {
                    null
                }
            is ActiveSequenceRuntime -> {
                val currentId = runtime.execution.currentOccurrenceId ?: return null
                val occurrence = runtime.execution.occurrences.single { it.id == currentId }
                val activity = runtime.activitySnapshots.getValue(occurrence.activitySnapshotId)
                val child = runtime.currentChild
                if (activity.timeTrackingMode == TimeTrackingMode.TIMER && child != null) {
                    TimerDeadlineCalculator.deadline(
                        child,
                        requireNotNull(activity.timerTarget),
                        TimerZeroBehavior.FINISH,
                    )
                } else {
                    null
                }
            }
        }

    private fun timerZeroBehavior(runtime: ActiveRuntime): TimerZeroBehavior? =
        when (runtime) {
            is ActiveActivityRuntime ->
                runtime.snapshot.settings.timerZeroBehavior.takeIf {
                    runtime.snapshot.timeTrackingMode == TimeTrackingMode.TIMER
                }
            is ActiveSequenceRuntime -> {
                val currentId = runtime.execution.currentOccurrenceId ?: return null
                val occurrence = runtime.execution.occurrences.single { it.id == currentId }
                NextRuntimeDeadlineResolver.effectiveSettings(runtime, occurrence).timerZeroBehavior
            }
        }

    private fun activityElapsed(
        execution: ActivityExecution,
        observedWall: Instant,
    ): Duration {
        val startedAt = requireNotNull(execution.startedAt)
        val effectiveEnd = maxOf(observedWall, execution.updatedAt, startedAt)
        val pauses =
            execution.pauses.map { pause ->
                if (pause.endedAt ==
                    null
                ) {
                    pause.copy(endedAt = effectiveEnd)
                } else {
                    pause
                }
            }
        return ActivityExecutionDurationCalculator.calculate(startedAt, effectiveEnd, pauses)
    }

    private fun sequenceElapsed(
        execution: SequenceExecution,
        observedWall: Instant,
    ): Duration {
        val effectiveEnd = maxOf(observedWall, execution.updatedAt, execution.startedAt)
        val intervals =
            execution.intervals.map { interval ->
                if (interval.endedAt ==
                    null
                ) {
                    interval.copy(endedAt = effectiveEnd)
                } else {
                    interval
                }
            }
        return SequenceTimelineCalculator.calculate(execution.startedAt, effectiveEnd, intervals).active
    }
}

private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (this < minimum) minimum else this
