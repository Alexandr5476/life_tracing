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
    fun elapsedAt(wall: Instant): Long =
        Math.addExact(
            elapsedAtAnchorMs,
            Math.subtractExact(wall.toEpochMilli(), wallAtAnchor.toEpochMilli()),
        )

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

@Suppress("LongParameterList") // Flat immutable values keep every tick independent from runtime history.
class RuntimeDisplayBaseline private constructor(
    private val anchorElapsedRealtimeMs: Long,
    private val activeElapsedAtAnchor: Duration,
    private val activeProgresses: Boolean,
    private val timerZeroElapsedRealtimeMs: Long?,
    private val timerRemainingAtAnchor: Duration?,
    private val timerOvertimeAtAnchor: Duration?,
    private val timerZeroBehavior: TimerZeroBehavior?,
    private val transitionCountdownElapsedRealtimeMs: Long?,
) {
    fun activeElapsed(elapsedRealtimeNowMs: Long): Duration =
        if (activeProgresses) {
            activeElapsedAtAnchor.plusMillis(monotonicDelta(elapsedRealtimeNowMs))
        } else {
            activeElapsedAtAnchor
        }

    fun timerRemaining(elapsedRealtimeNowMs: Long): Duration? =
        timerZeroElapsedRealtimeMs?.let { remaining(it, elapsedRealtimeNowMs) } ?: timerRemainingAtAnchor

    fun timerOvertime(elapsedRealtimeNowMs: Long): Duration? =
        timerZeroElapsedRealtimeMs
            ?.takeIf { timerZeroBehavior == TimerZeroBehavior.OVERTIME }
            ?.let { remaining(elapsedRealtimeNowMs, it) }
            ?: timerOvertimeAtAnchor?.takeIf { timerZeroBehavior == TimerZeroBehavior.OVERTIME }

    fun transitionCountdownRemaining(elapsedRealtimeNowMs: Long): Duration? =
        transitionCountdownElapsedRealtimeMs?.let { remaining(it, elapsedRealtimeNowMs) }

    private fun monotonicDelta(elapsedRealtimeNowMs: Long): Long =
        Math.subtractExact(elapsedRealtimeNowMs, anchorElapsedRealtimeMs).coerceAtLeast(0L)

    private fun remaining(
        laterMs: Long,
        earlierMs: Long,
    ): Duration = Duration.ofMillis(Math.subtractExact(laterMs, earlierMs).coerceAtLeast(0L))

    companion object {
        fun capture(
            runtime: ActiveRuntime,
            anchor: WallMonotonicAnchor,
        ): RuntimeDisplayBaseline {
            val timer = timerSource(runtime)
            val timerDeadline =
                timer?.let { TimerDeadlineCalculator.deadline(it.execution, it.target, TimerZeroBehavior.FINISH) }
            val timerElapsed = timer?.let { activityElapsed(it.execution, anchor.wallAtAnchor) }
            val transitionDeadline =
                NextRuntimeDeadlineResolver
                    .resolve(runtime)
                    ?.takeIf { it.kind == RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN }
            return RuntimeDisplayBaseline(
                anchor.elapsedAtAnchorMs,
                when (runtime) {
                    is ActiveActivityRuntime -> activityElapsed(runtime.execution, anchor.wallAtAnchor)
                    is ActiveSequenceRuntime -> sequenceElapsed(runtime.execution, anchor.wallAtAnchor)
                },
                activeProgresses(runtime),
                timerDeadline?.let(anchor::elapsedAt),
                timer?.target?.minus(requireNotNull(timerElapsed))?.coerceAtLeastZero(),
                timerElapsed?.minus(requireNotNull(timer).target)?.coerceAtLeastZero(),
                timer?.zeroBehavior,
                transitionDeadline?.at?.let(anchor::elapsedAt),
            )
        }

        private fun activeProgresses(runtime: ActiveRuntime): Boolean {
            if (runtime.session.state != ActiveSessionState.RUNNING) return false
            return when (runtime) {
                is ActiveActivityRuntime -> true
                is ActiveSequenceRuntime ->
                    runtime.execution.intervals
                        .singleOrNull { it.endedAt == null }
                        ?.kind ==
                        SequenceIntervalKind.ACTIVE_STEP
            }
        }

        private fun timerSource(runtime: ActiveRuntime): TimerSource? =
            when (runtime) {
                is ActiveActivityRuntime ->
                    if (runtime.snapshot.timeTrackingMode == TimeTrackingMode.TIMER) {
                        TimerSource(
                            runtime.execution,
                            requireNotNull(runtime.snapshot.timerTarget),
                            runtime.snapshot.settings.timerZeroBehavior,
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
                        TimerSource(
                            child,
                            requireNotNull(activity.timerTarget),
                            requireNotNull(
                                NextRuntimeDeadlineResolver.effectiveSettings(runtime, occurrence).timerZeroBehavior,
                            ),
                        )
                    } else {
                        null
                    }
                }
            }

        private data class TimerSource(
            val execution: ActivityExecution,
            val target: Duration,
            val zeroBehavior: TimerZeroBehavior,
        )

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
}

private fun Duration.coerceAtLeastZero(): Duration = if (isNegative) Duration.ZERO else this
