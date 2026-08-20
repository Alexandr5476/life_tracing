@file:Suppress(
    "LongParameterList",
    "LoopWithTooManyJumpStatements",
    "LongMethod",
    "ReturnCount",
    "TooManyFunctions",
    "CyclomaticComplexMethod",
) // The explicit state machine keeps every durable transition visible in one bounded engine.

package com.alexandr5476.lifetracing.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class SequenceRuntimeState(
    val execution: SequenceExecution,
    val children: Map<SequenceOccurrenceId, ActivityExecution> = emptyMap(),
) {
    val currentChild: ActivityExecution?
        get() = execution.currentOccurrenceId?.let(children::get)
}

object TimerDeadlineCalculator {
    fun deadline(
        execution: ActivityExecution,
        target: Duration,
        zeroBehavior: TimerZeroBehavior,
    ): Instant? {
        if (execution.status != ActivityExecutionStatus.RUNNING || zeroBehavior == TimerZeroBehavior.OVERTIME) {
            return null
        }
        val startedAt = requireNotNull(execution.startedAt) { "Running Timer requires a start" }
        var deadlineMs = Math.addExact(startedAt.toEpochMilli(), target.toMillis())
        execution.pauses.forEach { pause ->
            val endedAt = requireNotNull(pause.endedAt) { "Running Timer cannot contain an open pause" }
            deadlineMs =
                Math.addExact(deadlineMs, Math.subtractExact(endedAt.toEpochMilli(), pause.startedAt.toEpochMilli()))
        }
        return Instant.ofEpochMilli(deadlineMs)
    }
}

class SequenceRuntimeEngine(
    private val executionFactory: SequenceExecutionFactory,
    private val activityExecutionFactory: ActivityExecutionFactory,
    private val nextPauseId: () -> ActivityExecutionPauseId,
    private val nextIntervalId: () -> SequenceIntervalId,
) {
    fun start(
        snapshot: SequenceConfigSnapshot,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
        startedAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
    ): SequenceRuntimeState {
        val execution = executionFactory.start(snapshot, startedAt, createdAt, zoneId)
        require(execution.occurrences.isNotEmpty()) { "An empty Sequence cannot start" }
        return startOccurrence(
            SequenceRuntimeState(execution),
            firstRemaining(execution),
            persisted(startedAt),
            snapshot,
            activitySnapshots,
        )
    }

    fun reconcile(
        initial: SequenceRuntimeState,
        snapshot: SequenceConfigSnapshot,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
        now: Instant,
    ): SequenceRuntimeState = ReconciliationWorkingSet(initial, snapshot, activitySnapshots).reconcile(persisted(now))

    fun completeCurrent(
        initial: SequenceRuntimeState,
        expectedOccurrenceId: SequenceOccurrenceId,
        at: Instant,
        snapshot: SequenceConfigSnapshot,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ): SequenceRuntimeState {
        val reconciled = reconcile(initial, snapshot, activitySnapshots, at)
        require(reconciled.execution.status == SequenceExecutionStatus.RUNNING) { "Sequence is no longer active" }
        val current = requireNotNull(current(reconciled.execution)) { "Sequence is not running a Step" }
        require(current.id == expectedOccurrenceId) { "Stale current-occurrence command" }
        return completeCurrent(
            reconciled,
            expectedOccurrenceId,
            persisted(at),
            OccurrenceCompletionReason.MANUAL_FINISH,
            snapshot,
            activitySnapshots,
        )
    }

    fun startNext(
        initial: SequenceRuntimeState,
        at: Instant,
        snapshot: SequenceConfigSnapshot,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ): SequenceRuntimeState {
        val execution = initial.execution
        require(execution.status == SequenceExecutionStatus.RUNNING && current(execution) == null) {
            "Start next requires a running Sequence without a current Step"
        }
        require(openInterval(execution)?.kind == SequenceIntervalKind.IMPLICIT_IDLE) {
            "Start next requires WAITING_NEXT"
        }
        val next = firstRemaining(execution)
        val transitionAt = persisted(at)
        return beginTransition(closeOpen(initial, transitionAt), next, transitionAt, snapshot, activitySnapshots)
    }

    fun pause(
        initial: SequenceRuntimeState,
        at: Instant,
        snapshot: SequenceConfigSnapshot,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ): SequenceRuntimeState {
        val state = reconcile(initial, snapshot, activitySnapshots, at)
        require(state.execution.status == SequenceExecutionStatus.RUNNING) { "Sequence is no longer running" }
        require(openInterval(state.execution)?.kind != SequenceIntervalKind.IMPLICIT_IDLE) {
            "WAITING_NEXT is already an idle state"
        }
        val pauseAt = persisted(at)
        val current = current(state.execution)
        val currentChild = state.currentChild
        val child =
            if (current != null && currentChild != null) {
                ActivityExecutionTransitions.pause(currentChild, nextPauseId(), pauseAt)
            } else {
                currentChild
            }
        val closed = closeOpen(state.withChild(current?.id, child), pauseAt)
        return closed.copy(
            execution =
                closed.execution.copy(
                    status = SequenceExecutionStatus.PAUSED,
                    updatedAt = pauseAt,
                    intervals =
                        closed.execution.intervals +
                            SequenceInterval(
                                nextIntervalId(),
                                SequenceIntervalKind.EXPLICIT_PAUSE,
                                pauseAt,
                                null,
                                null,
                            ),
                ),
        )
    }

    fun resume(
        initial: SequenceRuntimeState,
        at: Instant,
        snapshot: SequenceConfigSnapshot,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ): SequenceRuntimeState {
        val resumeAt = persisted(at)
        require(initial.execution.status == SequenceExecutionStatus.PAUSED) { "Only a paused Sequence can resume" }
        require(openInterval(initial.execution)?.kind == SequenceIntervalKind.EXPLICIT_PAUSE) {
            "Paused Sequence requires one open explicit pause"
        }
        var state = closeOpen(initial, resumeAt)
        val current = current(state.execution)
        if (current != null) {
            val activity = activitySnapshots.requireSnapshot(current.activitySnapshotId)
            val child = state.currentChild?.let { ActivityExecutionTransitions.resume(it, resumeAt) }
            val kind = stepIntervalKind(activity, snapshot.settings.noLiveTimeAccounting)
            return state.copy(
                execution =
                    state.execution.copy(
                        status = SequenceExecutionStatus.RUNNING,
                        updatedAt = resumeAt,
                        intervals =
                            state.execution.intervals +
                                SequenceInterval(nextIntervalId(), kind, resumeAt, null, current.id),
                    ),
                children = state.children + listOfNotNull(child?.let { current.id to it }),
            )
        }
        val target =
            requireNotNull(nextRemainingOccurrence(state.execution)) {
                "Paused transition countdown lost its target occurrence"
            }
        val targetId = target.id
        val pause = requireNotNull(openInterval(initial.execution))
        val targetSegments =
            initial.execution.intervals.filter {
                it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN &&
                    it.occurrenceId == targetId &&
                    it.endedAt != null
            }
        val latest =
            requireNotNull(targetSegments.maxByOrNull { requireNotNull(it.endedAt) }) {
                "Paused transition countdown has no progress for its target"
            }
        require(latest.endedAt == pause.startedAt) { "Paused transition countdown target is not temporally adjacent" }
        state =
            state.copy(execution = state.execution.copy(status = SequenceExecutionStatus.RUNNING, updatedAt = resumeAt))
        val activity = activitySnapshots.requireSnapshot(target.activitySnapshotId)
        val required = effectiveSettings(target, snapshot, activity).startCountdown.toMillis()
        require(closedCountdownMillis(state.execution, targetId) < required) {
            "Paused transition countdown is already exhausted"
        }
        return state.copy(
            execution =
                state.execution.copy(
                    intervals =
                        state.execution.intervals +
                            SequenceInterval(
                                nextIntervalId(),
                                SequenceIntervalKind.TRANSITION_COUNTDOWN,
                                resumeAt,
                                null,
                                targetId,
                            ),
                ),
        )
    }

    private fun completeCurrent(
        state: SequenceRuntimeState,
        occurrenceId: SequenceOccurrenceId,
        at: Instant,
        reason: OccurrenceCompletionReason,
        snapshot: SequenceConfigSnapshot,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ): SequenceRuntimeState {
        val current = occurrence(state.execution, occurrenceId)
        val activity = activitySnapshots.requireSnapshot(current.activitySnapshotId)
        val child =
            when (activity.timeTrackingMode) {
                TimeTrackingMode.NO_LIVE_TRACKING ->
                    activityExecutionFactory.completeSequenceChildNoLive(
                        activity,
                        state.execution.id,
                        occurrenceId,
                        at,
                        state.execution.originalZoneId,
                    )
                TimeTrackingMode.STOPWATCH,
                TimeTrackingMode.TIMER,
                -> ActivityExecutionTransitions.complete(requireNotNull(state.currentChild), at)
            }
        val closed = closeOpen(state.withChild(occurrenceId, child), at)
        val completed =
            closed.copy(
                execution =
                    closed.execution.copy(
                        currentOccurrenceId = null,
                        updatedAt = at,
                        occurrences =
                            closed.execution.occurrences.map {
                                if (it.id == occurrenceId) {
                                    it.copy(
                                        status = RuntimeOccurrenceStatus.COMPLETED,
                                        completedAt = at,
                                        completionReason = reason,
                                    )
                                } else {
                                    it
                                }
                            },
                    ),
            )
        val next =
            completed.execution.occurrences.filter { it.status == RuntimeOccurrenceStatus.NOT_STARTED }.minByOrNull {
                it.runtimePosition
            }
        if (next == null) return finish(completed, at)
        if (!snapshot.settings.autoAdvance) {
            return completed.copy(
                execution =
                    completed.execution.copy(
                        intervals =
                            completed.execution.intervals +
                                SequenceInterval(nextIntervalId(), SequenceIntervalKind.IMPLICIT_IDLE, at, null, null),
                    ),
            )
        }
        return beginTransition(completed, next, at, snapshot, activitySnapshots)
    }

    private fun beginTransition(
        state: SequenceRuntimeState,
        next: RuntimeOccurrence,
        at: Instant,
        snapshot: SequenceConfigSnapshot,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ): SequenceRuntimeState {
        val activity = activitySnapshots.requireSnapshot(next.activitySnapshotId)
        val countdown = effectiveSettings(next, snapshot, activity).startCountdown
        return if (countdown.isZero) {
            startOccurrence(state, next, at, snapshot, activitySnapshots)
        } else {
            state.copy(
                execution =
                    state.execution.copy(
                        updatedAt = at,
                        intervals =
                            state.execution.intervals +
                                SequenceInterval(
                                    nextIntervalId(),
                                    SequenceIntervalKind.TRANSITION_COUNTDOWN,
                                    at,
                                    null,
                                    next.id,
                                ),
                    ),
            )
        }
    }

    private fun startOccurrence(
        state: SequenceRuntimeState,
        occurrence: RuntimeOccurrence,
        at: Instant,
        snapshot: SequenceConfigSnapshot,
        activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ): SequenceRuntimeState {
        require(occurrence.status == RuntimeOccurrenceStatus.NOT_STARTED) { "Only a remaining Step can start" }
        val activity = activitySnapshots.requireSnapshot(occurrence.activitySnapshotId)
        val child =
            if (activity.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                null
            } else {
                activityExecutionFactory.startSequenceChildTimed(
                    activity,
                    state.execution.id,
                    occurrence.id,
                    at,
                    at,
                    state.execution.originalZoneId,
                )
            }
        val current = occurrence.copy(status = RuntimeOccurrenceStatus.CURRENT, enteredAt = at)
        return state.copy(
            execution =
                state.execution.copy(
                    currentOccurrenceId = occurrence.id,
                    updatedAt = at,
                    occurrences = state.execution.occurrences.map { if (it.id == occurrence.id) current else it },
                    intervals =
                        state.execution.intervals +
                            SequenceInterval(
                                nextIntervalId(),
                                stepIntervalKind(activity, snapshot.settings.noLiveTimeAccounting),
                                at,
                                null,
                                occurrence.id,
                            ),
                ),
            children = if (child == null) state.children else state.children + (occurrence.id to child),
        )
    }

    private fun finish(
        state: SequenceRuntimeState,
        at: Instant,
    ): SequenceRuntimeState {
        val durations = SequenceTimelineCalculator.calculate(state.execution.startedAt, at, state.execution.intervals)
        return state.copy(
            execution =
                state.execution.copy(
                    status = SequenceExecutionStatus.COMPLETED,
                    endedAt = at,
                    activeDuration = durations.active,
                    pauseDuration = durations.pause,
                    wallDuration = durations.wall,
                    currentOccurrenceId = null,
                    updatedAt = at,
                ),
        )
    }

    private fun effectiveSettings(
        occurrence: RuntimeOccurrence,
        snapshot: SequenceConfigSnapshot,
        activity: ActivityConfigSnapshot,
    ): EffectiveSequenceStepSettings {
        val stepId = requireNotNull(occurrence.sourceSequenceSnapshotNodeId) { "Runtime-added Steps are out of scope" }
        val step =
            snapshot.nodes
                .flatMap {
                    when (it) {
                        is SequenceSnapshotActivityStep -> listOf(it)
                        is SequenceSnapshotRepeatBlock -> it.children
                    }
                }.single { it.id == stepId }
        return EffectiveSequenceStepSettingsResolver.resolve(step, activity, snapshot.settings, false)
    }

    private fun closeOpen(
        state: SequenceRuntimeState,
        at: Instant,
    ): SequenceRuntimeState {
        val open = openInterval(state.execution) ?: return state
        require(at >= open.startedAt) { "Transition timestamp cannot precede the open interval" }
        return state.copy(
            execution =
                state.execution.copy(
                    intervals = state.execution.intervals.map { if (it.id == open.id) it.copy(endedAt = at) else it },
                ),
        )
    }

    private fun closedCountdownMillis(
        execution: SequenceExecution,
        target: SequenceOccurrenceId,
    ): Long =
        execution.intervals
            .asSequence()
            .filter {
                it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN && it.occurrenceId == target && it.endedAt != null
            }.fold(0L) { total, interval ->
                Math.addExact(
                    total,
                    Math.subtractExact(
                        requireNotNull(interval.endedAt).toEpochMilli(),
                        interval.startedAt.toEpochMilli(),
                    ),
                )
            }

    private inner class ReconciliationWorkingSet(
        initial: SequenceRuntimeState,
        private val snapshot: SequenceConfigSnapshot,
        private val activities: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ) {
        private val original = initial.execution
        private val occurrences = original.occurrences.toMutableList()
        private val occurrenceIndex = occurrences.indices.associateBy { occurrences[it].id }
        private val runtimeOrder = occurrences.indices.sortedBy { occurrences[it].runtimePosition }
        private val steps =
            snapshot.nodes
                .flatMap {
                    when (it) {
                        is SequenceSnapshotActivityStep -> listOf(it)
                        is SequenceSnapshotRepeatBlock -> it.children
                    }
                }.associateBy(SequenceSnapshotActivityStep::id)
        private val intervals = original.intervals.toMutableList()
        private val children = initial.children.toMutableMap()
        private val consumedCountdownMs = mutableMapOf<SequenceOccurrenceId, Long>()
        private var openIntervalIndex = intervals.indices.singleOrNull { intervals[it].endedAt == null }
        private var currentIndex = original.currentOccurrenceId?.let(occurrenceIndex::getValue)
        private var remainingCursor = 0
        private var status = original.status
        private var endedAt = original.endedAt
        private var activeDuration = original.activeDuration
        private var pauseDuration = original.pauseDuration
        private var wallDuration = original.wallDuration
        private var updatedAt = original.updatedAt

        init {
            intervals.forEach { interval ->
                if (interval.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN && interval.endedAt != null) {
                    val target = requireNotNull(interval.occurrenceId)
                    consumedCountdownMs[target] =
                        Math.addExact(
                            consumedCountdownMs[target] ?: 0L,
                            Duration.between(interval.startedAt, interval.endedAt).toMillis(),
                        )
                }
            }
        }

        fun reconcile(now: Instant): SequenceRuntimeState {
            var events = 0
            val limit = Math.addExact(Math.multiplyExact(occurrences.size, EVENTS_PER_OCCURRENCE_BOUND), 1)
            while (status == SequenceExecutionStatus.RUNNING) {
                val current = currentIndex
                if (current != null) {
                    val occurrence = occurrences[current]
                    val activity = activities.requireSnapshot(occurrence.activitySnapshotId)
                    val child = children[occurrence.id]
                    if (activity.timeTrackingMode != TimeTrackingMode.TIMER || child == null) break
                    val deadline =
                        TimerDeadlineCalculator.deadline(
                            child,
                            requireNotNull(activity.timerTarget),
                            settings(current).timerZeroBehavior!!,
                        ) ?: break
                    if (deadline > now) break
                    completeCurrent(current, deadline)
                } else {
                    val openIndex = openIntervalIndex ?: break
                    val open = intervals[openIndex]
                    if (open.kind != SequenceIntervalKind.TRANSITION_COUNTDOWN) break
                    val targetId = requireNotNull(open.occurrenceId)
                    val target = occurrenceIndex.getValue(targetId)
                    require(target == nextRemainingIndex()) { "Transition countdown must target the runtime frontier" }
                    val remaining =
                        Math.subtractExact(
                            settings(target).startCountdown.toMillis(),
                            consumedCountdownMs[targetId] ?: 0L,
                        )
                    require(remaining > 0) { "Transition countdown is already exhausted" }
                    val deadline = Instant.ofEpochMilli(Math.addExact(open.startedAt.toEpochMilli(), remaining))
                    if (deadline > now) break
                    closeOpen(deadline)
                    startOccurrence(target, deadline)
                }
                check(++events <= limit) { "Reconciliation exceeded the active Sequence event bound" }
            }
            return freeze()
        }

        private fun completeCurrent(
            index: Int,
            at: Instant,
        ) {
            val occurrence = occurrences[index]
            val activity = activities.requireSnapshot(occurrence.activitySnapshotId)
            children[occurrence.id] =
                when (activity.timeTrackingMode) {
                    TimeTrackingMode.NO_LIVE_TRACKING ->
                        activityExecutionFactory.completeSequenceChildNoLive(
                            activity,
                            original.id,
                            occurrence.id,
                            at,
                            original.originalZoneId,
                        )
                    TimeTrackingMode.STOPWATCH,
                    TimeTrackingMode.TIMER,
                    -> ActivityExecutionTransitions.complete(requireNotNull(children[occurrence.id]), at)
                }
            closeOpen(at)
            occurrences[index] =
                occurrence.copy(
                    status = RuntimeOccurrenceStatus.COMPLETED,
                    completedAt = at,
                    completionReason = OccurrenceCompletionReason.NATURAL_TIMER_END,
                )
            currentIndex = null
            updatedAt = at
            val next = nextRemainingIndex()
            if (next == null) {
                finish(at)
            } else if (!snapshot.settings.autoAdvance) {
                addInterval(SequenceIntervalKind.IMPLICIT_IDLE, at, null)
            } else {
                beginTransition(next, at)
            }
        }

        private fun beginTransition(
            index: Int,
            at: Instant,
        ) {
            if (settings(index).startCountdown.isZero) {
                startOccurrence(index, at)
            } else {
                updatedAt = at
                addInterval(SequenceIntervalKind.TRANSITION_COUNTDOWN, at, occurrences[index].id)
            }
        }

        private fun startOccurrence(
            index: Int,
            at: Instant,
        ) {
            require(index == nextRemainingIndex()) { "Normal progression cannot skip the runtime frontier" }
            val occurrence = occurrences[index]
            val activity = activities.requireSnapshot(occurrence.activitySnapshotId)
            if (activity.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
                children[occurrence.id] =
                    activityExecutionFactory.startSequenceChildTimed(
                        activity,
                        original.id,
                        occurrence.id,
                        at,
                        at,
                        original.originalZoneId,
                    )
            }
            occurrences[index] = occurrence.copy(status = RuntimeOccurrenceStatus.CURRENT, enteredAt = at)
            currentIndex = index
            updatedAt = at
            addInterval(stepIntervalKind(activity, snapshot.settings.noLiveTimeAccounting), at, occurrence.id)
        }

        private fun closeOpen(at: Instant) {
            val index = requireNotNull(openIntervalIndex) { "Active Sequence lost its open interval" }
            val open = intervals[index]
            require(at >= open.startedAt) { "Transition timestamp cannot precede the open interval" }
            intervals[index] = open.copy(endedAt = at)
            if (open.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN) {
                val target = requireNotNull(open.occurrenceId)
                consumedCountdownMs[target] =
                    Math.addExact(
                        consumedCountdownMs[target] ?: 0L,
                        Duration.between(open.startedAt, at).toMillis(),
                    )
            }
            openIntervalIndex = null
        }

        private fun addInterval(
            kind: SequenceIntervalKind,
            at: Instant,
            occurrenceId: SequenceOccurrenceId?,
        ) {
            check(openIntervalIndex == null)
            intervals += SequenceInterval(nextIntervalId(), kind, at, null, occurrenceId)
            openIntervalIndex = intervals.lastIndex
        }

        private fun nextRemainingIndex(): Int? {
            while (remainingCursor < runtimeOrder.size &&
                occurrences[runtimeOrder[remainingCursor]].status != RuntimeOccurrenceStatus.NOT_STARTED
            ) {
                remainingCursor++
            }
            return runtimeOrder.getOrNull(remainingCursor)
        }

        private fun settings(index: Int): EffectiveSequenceStepSettings {
            val occurrence = occurrences[index]
            val stepId =
                requireNotNull(occurrence.sourceSequenceSnapshotNodeId) {
                    "Runtime-added Steps are out of scope"
                }
            return EffectiveSequenceStepSettingsResolver.resolve(
                steps.getValue(stepId),
                activities.requireSnapshot(occurrence.activitySnapshotId),
                snapshot.settings,
                false,
            )
        }

        private fun finish(at: Instant) {
            val durations = SequenceTimelineCalculator.calculate(original.startedAt, at, intervals)
            status = SequenceExecutionStatus.COMPLETED
            endedAt = at
            activeDuration = durations.active
            pauseDuration = durations.pause
            wallDuration = durations.wall
            updatedAt = at
        }

        private fun freeze(): SequenceRuntimeState =
            SequenceRuntimeState(
                original.copy(
                    status = status,
                    endedAt = endedAt,
                    activeDuration = activeDuration,
                    pauseDuration = pauseDuration,
                    wallDuration = wallDuration,
                    currentOccurrenceId = currentIndex?.let { occurrences[it].id },
                    updatedAt = updatedAt,
                    occurrences = occurrences,
                    intervals = intervals,
                ),
                children,
            )
    }
}

fun stepIntervalKind(
    activity: ActivityConfigSnapshot,
    noLiveAccounting: NoLiveTimeAccounting,
): SequenceIntervalKind =
    if (activity.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING ||
        noLiveAccounting == NoLiveTimeAccounting.ACTIVE
    ) {
        SequenceIntervalKind.ACTIVE_STEP
    } else {
        SequenceIntervalKind.STEP_PAUSE
    }

private fun openInterval(execution: SequenceExecution): SequenceInterval? =
    execution.intervals.singleOrNull { it.endedAt == null }

private fun current(execution: SequenceExecution): RuntimeOccurrence? =
    execution.currentOccurrenceId?.let { occurrence(execution, it) }

private fun occurrence(
    execution: SequenceExecution,
    id: SequenceOccurrenceId,
): RuntimeOccurrence = execution.occurrences.single { it.id == id }

private fun firstRemaining(execution: SequenceExecution): RuntimeOccurrence =
    requireNotNull(nextRemainingOccurrence(execution)) {
        "Sequence has no remaining occurrence"
    }

fun nextRemainingOccurrence(execution: SequenceExecution): RuntimeOccurrence? =
    execution.occurrences
        .asSequence()
        .filter { it.status == RuntimeOccurrenceStatus.NOT_STARTED }
        .minByOrNull(RuntimeOccurrence::runtimePosition)

private fun Map<ActivitySnapshotId, ActivityConfigSnapshot>.requireSnapshot(
    id: ActivitySnapshotId,
): ActivityConfigSnapshot = requireNotNull(this[id]) { "Missing Activity snapshot metadata: ${id.value}" }

private fun persisted(instant: Instant): Instant = Instant.ofEpochMilli(instant.toEpochMilli())

private const val EVENTS_PER_OCCURRENCE_BOUND = 3

private fun SequenceRuntimeState.withChild(
    occurrenceId: SequenceOccurrenceId?,
    child: ActivityExecution?,
): SequenceRuntimeState =
    if (occurrenceId == null || child == null) this else copy(children = children + (occurrenceId to child))
