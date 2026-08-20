@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "ReturnCount",
    "TooManyFunctions",
) // One coordinator owns the single atomic live-runtime boundary.

package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionContext
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityExecutionValidator
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.EffectiveSequenceStepSettingsResolver
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceExecutionFactory
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionValidator
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceRuntimeEngine
import com.alexandr5476.lifetracing.domain.SequenceRuntimeState
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotRepeatBlock
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerDeadlineCalculator
import com.alexandr5476.lifetracing.domain.nextRemainingOccurrence
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Callable

internal class LiveSessionRepository(
    private val database: LifeTracingDatabase,
    nextActivityExecutionId: () -> ActivityExecutionId,
    nextActivityPauseId: () -> ActivityExecutionPauseId,
    nextSequenceExecutionId: () -> SequenceExecutionId,
    nextOccurrenceId: () -> SequenceOccurrenceId,
    nextIntervalId: () -> SequenceIntervalId,
) {
    private val activityFactory = ActivityExecutionFactory(nextActivityExecutionId)
    private val sequenceEngine =
        SequenceRuntimeEngine(
            SequenceExecutionFactory(
                nextSequenceExecutionId,
                com.alexandr5476.lifetracing.domain
                    .RuntimeOccurrenceMaterializer(nextOccurrenceId),
            ),
            activityFactory,
            nextActivityPauseId,
            nextIntervalId,
        )

    fun getActiveSession(): ActiveSession? = transaction(::getActiveSessionLocked)

    fun startStandaloneTimedActivityFromSnapshot(
        snapshotId: ActivitySnapshotId,
        startedAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
    ): ActivityExecution =
        transaction {
            require(getActiveSessionLocked() == null) { "Another live session is already active" }
            val snapshot = loadActivitySnapshot(snapshotId)
            val execution = activityFactory.startTimed(snapshot, startedAt, createdAt, zoneId)
            database.activityExecutionDao().insertAggregate(execution.toEntityAggregate())
            database.activeSessionDao().insert(
                ActiveSession(
                    ActiveSessionKind.ACTIVITY,
                    ActiveSessionState.RUNNING,
                    execution.id,
                    null,
                    execution.updatedAt,
                ),
            )
            execution
        }

    fun pauseActiveActivity(
        pauseId: ActivityExecutionPauseId,
        at: Instant,
    ) {
        transaction {
            reconcileActivityLocked(at)
            if (getActiveSessionLocked() == null) return@transaction
            val session = requireActivitySession(ActiveSessionState.RUNNING)
            database.activityExecutionDao().pause(
                requireNotNull(session.activityExecutionId).value,
                pauseId.value,
                at.toEpochMilli(),
            )
            updateSession(ActiveSessionState.PAUSED, at)
        }
    }

    fun resumeActiveActivity(at: Instant) {
        transaction {
            val session = requireActivitySession(ActiveSessionState.PAUSED)
            database.activityExecutionDao().resume(requireNotNull(session.activityExecutionId).value, at.toEpochMilli())
            updateSession(ActiveSessionState.RUNNING, at)
        }
    }

    fun completeActiveActivity(at: Instant) {
        transaction {
            reconcileActivityLocked(at)
            val session = getActiveSessionLocked() ?: return@transaction
            require(session.kind == ActiveSessionKind.ACTIVITY) { "Active session is not an Activity" }
            database.activityExecutionDao().complete(
                requireNotNull(session.activityExecutionId).value,
                at.toEpochMilli(),
            )
            check(database.activeSessionDao().clear() == 1)
        }
    }

    fun startSequenceFromSnapshot(
        snapshotId: SequenceSnapshotId,
        startedAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
    ): SequenceRuntimeState =
        transaction {
            require(getActiveSessionLocked() == null) { "Another live session is already active" }
            val snapshot = loadSequenceSnapshot(snapshotId)
            val activities = loadActivitySnapshots(snapshot)
            val state = sequenceEngine.start(snapshot, activities, startedAt, createdAt, zoneId)
            database.sequenceExecutionDao().insertAggregate(state.execution.toEntityAggregate())
            state.children.values.forEach { database.activityExecutionDao().insertAggregate(it.toEntityAggregate()) }
            database.activeSessionDao().insert(sequenceSession(state.execution))
            state
        }

    fun reconcileActiveSession(now: Instant): ActiveSession? =
        transaction {
            when (getActiveSessionLocked()?.kind) {
                ActiveSessionKind.ACTIVITY -> reconcileActivityLocked(now)
                ActiveSessionKind.SEQUENCE -> reconcileSequenceLocked(now)
                null -> Unit
            }
            getActiveSessionLocked()
        }

    fun completeCurrentSequenceStep(
        expectedOccurrenceId: SequenceOccurrenceId,
        at: Instant,
    ): SequenceRuntimeState {
        val result =
            transaction {
                requireSequenceSession()
                val loaded = loadSequenceRuntime()
                val reconciled = sequenceEngine.reconcile(loaded.state, loaded.snapshot, loaded.activities, at)
                if (reconciled.execution.currentOccurrenceId != expectedOccurrenceId) {
                    persistSequenceRuntime(loaded, reconciled)
                    return@transaction null
                }
                val updated =
                    sequenceEngine.completeCurrent(
                        reconciled,
                        expectedOccurrenceId,
                        at,
                        loaded.snapshot,
                        loaded.activities,
                    )
                persistSequenceRuntime(loaded, updated)
                updated
            }
        return requireNotNull(result) { "Stale current-occurrence command" }
    }

    fun startNextSequenceStep(at: Instant): SequenceRuntimeState =
        transaction {
            requireSequenceSession(ActiveSessionState.WAITING_NEXT)
            val loaded = loadSequenceRuntime()
            val updated = sequenceEngine.startNext(loaded.state, at, loaded.snapshot, loaded.activities)
            persistSequenceRuntime(loaded, updated)
            updated
        }

    fun pauseActiveSequence(at: Instant): SequenceRuntimeState {
        val result =
            transaction {
                requireSequenceSession()
                val loaded = loadSequenceRuntime()
                val reconciled = sequenceEngine.reconcile(loaded.state, loaded.snapshot, loaded.activities, at)
                if (reconciled.execution.status == SequenceExecutionStatus.COMPLETED) {
                    persistSequenceRuntime(loaded, reconciled)
                    return@transaction PauseSequenceResult(reconciled, true)
                }
                if (reconciled.execution.currentOccurrenceId == null &&
                    reconciled.execution.intervals
                        .singleOrNull { it.endedAt == null }
                        ?.kind ==
                    SequenceIntervalKind.IMPLICIT_IDLE
                ) {
                    persistSequenceRuntime(loaded, reconciled)
                    return@transaction PauseSequenceResult(reconciled, false)
                }
                val paused = sequenceEngine.pause(reconciled, at, loaded.snapshot, loaded.activities)
                persistSequenceRuntime(loaded, paused)
                PauseSequenceResult(paused, true)
            }
        require(result.applied) { "WAITING_NEXT is already an idle state" }
        return result.state
    }

    fun resumeActiveSequence(at: Instant): SequenceRuntimeState =
        transaction {
            requireSequenceSession(ActiveSessionState.PAUSED)
            val loaded = loadSequenceRuntime()
            val resumed = sequenceEngine.resume(loaded.state, at, loaded.snapshot, loaded.activities)
            persistSequenceRuntime(loaded, resumed)
            resumed
        }

    private fun reconcileActivityLocked(now: Instant) {
        val session = getActiveSessionLocked() ?: return
        if (session.kind != ActiveSessionKind.ACTIVITY || session.state == ActiveSessionState.PAUSED) return
        val id = requireNotNull(session.activityExecutionId).value
        val execution = requireNotNull(database.activityExecutionDao().getAggregate(id)).toDomain()
        val snapshot = loadActivitySnapshot(execution.snapshotId)
        if (snapshot.timeTrackingMode != TimeTrackingMode.TIMER) return
        val deadline =
            TimerDeadlineCalculator.deadline(
                execution,
                requireNotNull(snapshot.timerTarget),
                snapshot.settings.timerZeroBehavior,
            ) ?: return
        if (deadline <= persisted(now)) {
            database.activityExecutionDao().complete(id, deadline.toEpochMilli())
            check(database.activeSessionDao().clear() == 1)
        }
    }

    private fun reconcileSequenceLocked(now: Instant) {
        val loaded = loadSequenceRuntime()
        persistSequenceRuntime(loaded, sequenceEngine.reconcile(loaded.state, loaded.snapshot, loaded.activities, now))
    }

    private fun persistSequenceRuntime(
        loaded: LoadedSequenceRuntime,
        after: SequenceRuntimeState,
    ) {
        val before = loaded.state
        val snapshot = loaded.snapshot
        val activities = loaded.activities
        require(after.children.keys.containsAll(before.children.keys)) {
            "Runtime transitions cannot remove known child executions"
        }
        SequenceExecutionValidator.requireValid(after.execution, snapshot)
        if (after.execution.status == SequenceExecutionStatus.RUNNING ||
            after.execution.status == SequenceExecutionStatus.PAUSED
        ) {
            validateSequenceRuntimeShape(sequenceSession(after.execution).state, after.execution, snapshot, activities)
        }
        after.children.forEach { (occurrenceId, child) ->
            val occurrence = after.execution.occurrences.single { it.id == occurrenceId }
            ActivityExecutionValidator.requireValid(child, activities.getValue(occurrence.activitySnapshotId))
            require(
                child.context == ActivityExecutionContext.SEQUENCE_CHILD &&
                    child.sequenceExecutionId == after.execution.id &&
                    child.sequenceOccurrenceId == occurrenceId,
            ) { "Sequence child ownership disagrees with the intended runtime state" }
        }
        val current = after.execution.currentOccurrenceId?.let(after.children::get)
        val currentOccurrence =
            after.execution.currentOccurrenceId?.let { id -> after.execution.occurrences.single { it.id == id } }
        if (currentOccurrence != null) {
            if (activities.getValue(currentOccurrence.activitySnapshotId).timeTrackingMode ==
                TimeTrackingMode.NO_LIVE_TRACKING
            ) {
                require(current == null) { "Current No-live Step cannot have a child execution" }
            } else {
                requireNotNull(current) { "Current timed Step requires its child execution" }
                require(
                    current.status ==
                        if (after.execution.status == SequenceExecutionStatus.PAUSED) {
                            ActivityExecutionStatus.PAUSED
                        } else {
                            ActivityExecutionStatus.RUNNING
                        },
                ) { "Current child state disagrees with the intended Sequence state" }
            }
        }
        database.sequenceExecutionDao().persistRuntimeDelta(
            before.execution.toEntityAggregate(),
            after.execution.toEntityAggregate(),
        )
        after.children.forEach { (occurrenceId, child) ->
            val previous = before.children[occurrenceId]
            if (previous == null) {
                database.activityExecutionDao().insertAggregate(child.toEntityAggregate())
            } else {
                database.activityExecutionDao().persistSequenceChildDelta(
                    previous.toEntityAggregate(),
                    child.toEntityAggregate(),
                )
            }
        }
        if (after.execution.status == SequenceExecutionStatus.COMPLETED) {
            check(database.activeSessionDao().clear() == 1)
        } else if (before.execution != after.execution) {
            val session = sequenceSession(after.execution)
            check(database.activeSessionDao().updateState(session.state.name, session.updatedAt.toEpochMilli()) == 1)
        }
    }

    private fun loadSequenceRuntime(): LoadedSequenceRuntime {
        val session = requireSequenceSession()
        val id = requireNotNull(session.sequenceExecutionId).value
        val execution = requireNotNull(database.sequenceExecutionDao().getAggregate(id)).toDomain()
        val snapshot = loadSequenceSnapshot(execution.snapshotId)
        val activities = loadActivitySnapshots(snapshot)
        SequenceExecutionValidator.requireValid(execution, snapshot)
        val child =
            execution.currentOccurrenceId?.let { occurrenceId ->
                database.activityExecutionDao().getAggregateByOccurrence(occurrenceId.value)?.toDomain()
            }
        return LoadedSequenceRuntime(
            SequenceRuntimeState(
                execution,
                child?.let { mapOf(requireNotNull(execution.currentOccurrenceId) to it) }.orEmpty(),
            ),
            snapshot,
            activities,
        )
    }

    private fun getActiveSessionLocked(): ActiveSession? {
        val session = database.activeSessionDao().get() ?: return null
        when (session.kind) {
            ActiveSessionKind.ACTIVITY -> validateActivitySession(session)
            ActiveSessionKind.SEQUENCE -> validateSequenceSession(session)
        }
        return session
    }

    private fun validateActivitySession(session: ActiveSession) {
        val execution =
            requireNotNull(
                database.activityExecutionDao().getAggregate(requireNotNull(session.activityExecutionId).value),
            ) {
                "Active ActivityExecution is missing"
            }.toDomain()
        val snapshot = loadActivitySnapshot(execution.snapshotId)
        ActivityExecutionValidator.requireValid(execution, snapshot)
        require(execution.context == ActivityExecutionContext.STANDALONE && execution.deletedAt == null) {
            "Active Activity must point to a non-deleted standalone execution"
        }
        require(
            (session.state == ActiveSessionState.RUNNING && execution.status == ActivityExecutionStatus.RUNNING) ||
                (session.state == ActiveSessionState.PAUSED && execution.status == ActivityExecutionStatus.PAUSED),
        ) { "Active Activity state disagrees with its execution" }
    }

    private fun validateSequenceSession(session: ActiveSession) {
        val id = requireNotNull(session.sequenceExecutionId).value
        val execution =
            requireNotNull(database.sequenceExecutionDao().getAggregate(id)) { "Active SequenceExecution is missing" }
                .toDomain()
        val snapshot = loadSequenceSnapshot(execution.snapshotId)
        val activities = loadActivitySnapshots(snapshot)
        SequenceExecutionValidator.requireValid(execution, snapshot)
        validateSequenceRuntimeShape(session.state, execution, snapshot, activities)
        val current =
            execution.currentOccurrenceId?.let { currentId ->
                execution.occurrences.single {
                    it.id ==
                        currentId
                }
            }
        if (current != null) {
            validateCurrentChild(
                execution.id,
                current.id,
                activities.getValue(current.activitySnapshotId),
                session.state == ActiveSessionState.PAUSED,
            )
        }
    }

    private fun validateSequenceRuntimeShape(
        state: ActiveSessionState,
        execution: com.alexandr5476.lifetracing.domain.SequenceExecution,
        snapshot: SequenceConfigSnapshot,
        activities: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ) {
        val open = execution.intervals.filter { it.endedAt == null }
        require(open.size == 1) { "Active Sequence requires exactly one open classification interval" }
        val interval = open.single()
        require(
            execution.intervals.filter { it.endedAt != null }.all { requireNotNull(it.endedAt) <= interval.startedAt },
        ) {
            "The open runtime interval must follow every closed segment"
        }
        val current = execution.currentOccurrenceId?.let { id -> execution.occurrences.single { it.id == id } }
        val frontier = nextRemainingOccurrence(execution)
        if (current != null) {
            require(frontier == null || frontier.runtimePosition > current.runtimePosition) {
                "Current Step cannot skip an earlier remaining occurrence"
            }
        }
        when (state) {
            ActiveSessionState.WAITING_NEXT ->
                require(
                    execution.status == SequenceExecutionStatus.RUNNING &&
                        current == null &&
                        execution.occurrences.none { it.status == RuntimeOccurrenceStatus.CURRENT } &&
                        interval.kind == SequenceIntervalKind.IMPLICIT_IDLE &&
                        interval.occurrenceId == null &&
                        frontier != null,
                ) { "WAITING_NEXT requires a global implicit idle and a remaining frontier" }
            ActiveSessionState.RUNNING -> {
                require(execution.status == SequenceExecutionStatus.RUNNING) { "Running session requires running root" }
                if (current == null) {
                    require(
                        interval.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN &&
                            interval.occurrenceId == frontier?.id,
                    ) { "Transition countdown must target the next remaining occurrence" }
                    require(
                        countdownConsumedMs(execution, requireNotNull(frontier).id) <
                            requiredCountdownMs(frontier, snapshot, activities),
                    ) {
                        "Transition countdown requires positive remaining time"
                    }
                } else {
                    val expected =
                        com.alexandr5476.lifetracing.domain.stepIntervalKind(
                            activities.getValue(current.activitySnapshotId),
                            snapshot.settings.noLiveTimeAccounting,
                        )
                    require(interval.kind == expected && interval.occurrenceId == current.id) {
                        "Running current Step requires its one open Step-classification interval"
                    }
                }
            }
            ActiveSessionState.PAUSED -> {
                require(
                    execution.status == SequenceExecutionStatus.PAUSED &&
                        interval.kind == SequenceIntervalKind.EXPLICIT_PAUSE &&
                        interval.occurrenceId == null,
                ) { "Paused Sequence requires one global explicit-pause interval" }
                if (current == null) {
                    val target = requireNotNull(frontier) { "Paused countdown requires a remaining frontier" }
                    val segments =
                        execution.intervals.filter {
                            it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN &&
                                it.occurrenceId == target.id &&
                                it.endedAt != null
                        }
                    val latest =
                        requireNotNull(segments.maxByOrNull { requireNotNull(it.endedAt) }) {
                            "Paused countdown requires progress for its frontier target"
                        }
                    require(latest.endedAt == interval.startedAt) {
                        "Paused countdown progress must end exactly when the explicit pause starts"
                    }
                    require(
                        countdownConsumedMs(execution, target.id) < requiredCountdownMs(target, snapshot, activities),
                    ) {
                        "Paused countdown must retain positive remaining time"
                    }
                }
            }
        }
    }

    private fun requiredCountdownMs(
        occurrence: com.alexandr5476.lifetracing.domain.RuntimeOccurrence,
        snapshot: SequenceConfigSnapshot,
        activities: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    ): Long {
        val source = requireNotNull(occurrence.sourceSequenceSnapshotNodeId)
        val step =
            snapshot.nodes
                .flatMap {
                    when (it) {
                        is SequenceSnapshotActivityStep -> listOf(it)
                        is SequenceSnapshotRepeatBlock -> it.children
                    }
                }.single { it.id == source }
        return EffectiveSequenceStepSettingsResolver
            .resolve(step, activities.getValue(occurrence.activitySnapshotId), snapshot.settings, false)
            .startCountdown
            .toMillis()
    }

    private fun countdownConsumedMs(
        execution: com.alexandr5476.lifetracing.domain.SequenceExecution,
        target: SequenceOccurrenceId,
    ): Long =
        execution.intervals
            .asSequence()
            .filter {
                it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN && it.occurrenceId == target && it.endedAt != null
            }.sumOf {
                java.time.Duration
                    .between(it.startedAt, requireNotNull(it.endedAt))
                    .toMillis()
            }

    private fun validateCurrentChild(
        sequenceId: SequenceExecutionId,
        occurrenceId: SequenceOccurrenceId,
        snapshot: ActivityConfigSnapshot,
        paused: Boolean,
    ) {
        val child = database.activityExecutionDao().getAggregateByOccurrence(occurrenceId.value)?.toDomain()
        if (snapshot.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
            require(child == null) { "Current No-live Step cannot have a running child execution" }
            return
        }
        requireNotNull(child) { "Current timed Step is missing its child execution" }
        ActivityExecutionValidator.requireValid(child, snapshot)
        require(child.sequenceExecutionId == sequenceId && child.sequenceOccurrenceId == occurrenceId) {
            "Current child ownership disagrees with its Sequence"
        }
        require(child.status == if (paused) ActivityExecutionStatus.PAUSED else ActivityExecutionStatus.RUNNING) {
            "Current child state disagrees with its Sequence session"
        }
    }

    private fun sequenceSession(execution: com.alexandr5476.lifetracing.domain.SequenceExecution): ActiveSession {
        val state =
            when (execution.status) {
                SequenceExecutionStatus.PAUSED -> ActiveSessionState.PAUSED
                SequenceExecutionStatus.RUNNING ->
                    if (execution.currentOccurrenceId == null &&
                        execution.intervals.singleOrNull { it.endedAt == null }?.kind ==
                        SequenceIntervalKind.IMPLICIT_IDLE
                    ) {
                        ActiveSessionState.WAITING_NEXT
                    } else {
                        ActiveSessionState.RUNNING
                    }
                else -> error("Terminal Sequence cannot own an active session")
            }
        return ActiveSession(ActiveSessionKind.SEQUENCE, state, null, execution.id, execution.updatedAt)
    }

    private fun requireActivitySession(state: ActiveSessionState): ActiveSession =
        requireNotNull(getActiveSessionLocked()).also {
            require(it.kind == ActiveSessionKind.ACTIVITY && it.state == state) { "Activity session state mismatch" }
        }

    private fun requireSequenceSession(expectedState: ActiveSessionState? = null): ActiveSession =
        requireNotNull(getActiveSessionLocked()).also {
            require(it.kind == ActiveSessionKind.SEQUENCE && (expectedState == null || it.state == expectedState)) {
                "Sequence session state mismatch"
            }
        }

    private fun updateSession(
        state: ActiveSessionState,
        at: Instant,
    ) {
        check(database.activeSessionDao().updateState(state.name, persisted(at).toEpochMilli()) == 1)
    }

    private fun loadActivitySnapshot(id: ActivitySnapshotId): ActivityConfigSnapshot =
        requireNotNull(
            database.activitySnapshotDao().getAggregate(id.value),
        ) { "Unknown Activity snapshot: ${id.value}" }
            .toDomain()

    private fun loadSequenceSnapshot(id: SequenceSnapshotId): SequenceConfigSnapshot =
        requireNotNull(
            database.sequenceSnapshotDao().getAggregate(id.value),
        ) { "Unknown Sequence snapshot: ${id.value}" }
            .toDomain()

    private fun loadActivitySnapshots(
        snapshot: SequenceConfigSnapshot,
    ): Map<ActivitySnapshotId, ActivityConfigSnapshot> {
        val ids =
            snapshot.nodes
                .flatMap {
                    when (it) {
                        is com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep ->
                            listOf(
                                it.activitySnapshotId,
                            )
                        is com.alexandr5476.lifetracing.domain.SequenceSnapshotRepeatBlock ->
                            it.children.map(
                                com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep::activitySnapshotId,
                            )
                    }
                }.distinct()
        return database
            .activitySnapshotDao()
            .getAggregates(ids.map(ActivitySnapshotId::value))
            .associate {
                val domain = it.toDomain()
                domain.id to domain
            }.also { loaded ->
                require(loaded.keys == ids.toSet()) { "Sequence is missing Activity snapshot metadata" }
            }
    }

    private fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable(block))

    private data class LoadedSequenceRuntime(
        val state: SequenceRuntimeState,
        val snapshot: SequenceConfigSnapshot,
        val activities: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
    )

    private data class PauseSequenceResult(
        val state: SequenceRuntimeState,
        val applied: Boolean,
    )
}

private fun persisted(instant: Instant): Instant = Instant.ofEpochMilli(instant.toEpochMilli())
