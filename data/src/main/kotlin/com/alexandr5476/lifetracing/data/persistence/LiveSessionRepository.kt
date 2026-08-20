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
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerDeadlineCalculator
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
                    persistSequenceRuntime(reconciled)
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
                persistSequenceRuntime(updated)
                updated
            }
        return requireNotNull(result) { "Stale current-occurrence command" }
    }

    fun startNextSequenceStep(at: Instant): SequenceRuntimeState =
        transaction {
            requireSequenceSession(ActiveSessionState.WAITING_NEXT)
            val loaded = loadSequenceRuntime()
            val updated = sequenceEngine.startNext(loaded.state, at, loaded.snapshot, loaded.activities)
            persistSequenceRuntime(updated)
            updated
        }

    fun pauseActiveSequence(at: Instant): SequenceRuntimeState =
        transaction {
            requireSequenceSession()
            val loaded = loadSequenceRuntime()
            val reconciled = sequenceEngine.reconcile(loaded.state, loaded.snapshot, loaded.activities, at)
            if (reconciled.execution.status == SequenceExecutionStatus.COMPLETED) {
                persistSequenceRuntime(reconciled)
                return@transaction reconciled
            }
            val paused = sequenceEngine.pause(reconciled, at, loaded.snapshot, loaded.activities)
            persistSequenceRuntime(paused)
            paused
        }

    fun resumeActiveSequence(at: Instant): SequenceRuntimeState =
        transaction {
            requireSequenceSession(ActiveSessionState.PAUSED)
            val loaded = loadSequenceRuntime()
            val resumed = sequenceEngine.resume(loaded.state, at, loaded.snapshot, loaded.activities)
            persistSequenceRuntime(resumed)
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
        persistSequenceRuntime(sequenceEngine.reconcile(loaded.state, loaded.snapshot, loaded.activities, now))
    }

    private fun persistSequenceRuntime(state: SequenceRuntimeState) {
        database.sequenceExecutionDao().upsertRuntimeAggregate(state.execution.toEntityAggregate())
        state.children.values.forEach { child ->
            if (database.activityExecutionDao().getById(child.id.value) == null) {
                database.activityExecutionDao().insertAggregate(child.toEntityAggregate())
            } else {
                database.activityExecutionDao().upsertSequenceChildAggregate(child.toEntityAggregate())
            }
        }
        if (state.execution.status == SequenceExecutionStatus.COMPLETED) {
            check(database.activeSessionDao().clear() == 1)
        } else {
            val session = sequenceSession(state.execution)
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
        val open = execution.intervals.filter { it.endedAt == null }
        require(open.size == 1) { "Active Sequence requires exactly one open classification interval" }
        val current =
            execution.currentOccurrenceId?.let { currentId ->
                execution.occurrences.single {
                    it.id ==
                        currentId
                }
            }
        when (session.state) {
            ActiveSessionState.WAITING_NEXT ->
                require(
                    execution.status == SequenceExecutionStatus.RUNNING &&
                        current == null &&
                        execution.occurrences.none { it.status == RuntimeOccurrenceStatus.CURRENT } &&
                        open.single().kind == SequenceIntervalKind.IMPLICIT_IDLE,
                ) { "WAITING_NEXT requires one open implicit-idle interval and no current Step" }
            ActiveSessionState.RUNNING -> {
                require(execution.status == SequenceExecutionStatus.RUNNING) { "Running session requires running root" }
                if (current == null) {
                    val interval = open.single()
                    require(
                        interval.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN && interval.occurrenceId != null,
                    ) {
                        "Running Sequence without current Step requires a targeted transition countdown"
                    }
                    val target = execution.occurrences.singleOrNull { it.id == interval.occurrenceId }
                    require(target?.status == RuntimeOccurrenceStatus.NOT_STARTED) {
                        "Transition countdown target must be a remaining occurrence in this execution"
                    }
                } else {
                    val expected =
                        com.alexandr5476.lifetracing.domain.stepIntervalKind(
                            activities.getValue(current.activitySnapshotId),
                            snapshot.settings.noLiveTimeAccounting,
                        )
                    require(open.single().kind == expected && open.single().occurrenceId == current.id) {
                        "Running current Step requires its one open Step-classification interval"
                    }
                    validateCurrentChild(
                        execution.id,
                        current.id,
                        activities.getValue(current.activitySnapshotId),
                        false,
                    )
                }
            }
            ActiveSessionState.PAUSED -> {
                require(
                    execution.status == SequenceExecutionStatus.PAUSED &&
                        open.single().kind == SequenceIntervalKind.EXPLICIT_PAUSE,
                ) {
                    "Paused Sequence requires one open explicit-pause interval"
                }
                current?.let {
                    validateCurrentChild(execution.id, it.id, activities.getValue(it.activitySnapshotId), true)
                }
                if (current == null) {
                    require(
                        execution.intervals.any {
                            it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN &&
                                it.endedAt != null &&
                                it.occurrenceId != null
                        },
                    ) { "Paused Sequence without current Step must preserve transition-countdown progress" }
                }
            }
        }
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
}

private fun persisted(instant: Instant): Instant = Instant.ofEpochMilli(instant.toEpochMilli())
