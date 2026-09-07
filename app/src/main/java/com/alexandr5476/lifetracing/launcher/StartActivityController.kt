@file:Suppress("ComplexCondition", "ReturnCount", "TooGenericExceptionCaught")

package com.alexandr5476.lifetracing.launcher

import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.LiveSessionConflictException
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.StaleLauncherTargetException
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

sealed interface LauncherLoad<out T> {
    data object Idle : LauncherLoad<Nothing>

    data object Loading : LauncherLoad<Nothing>

    data class Content<T>(
        val value: T,
    ) : LauncherLoad<T>

    data class Failure(
        val message: String,
    ) : LauncherLoad<Nothing>
}

data class LauncherHome(
    val recent: List<LibraryTrackable>,
    val pinned: List<LibraryTrackable>,
)

sealed interface QuickMainValue {
    data object Missing : QuickMainValue

    data class Number(
        val scaledValue: Long,
    ) : QuickMainValue
}

data class QuickMainValueOverride(
    val fieldId: ActivityTemplateFieldId,
    val value: QuickMainValue,
)

sealed interface LauncherCommit {
    val isLive: Boolean

    data class Activity(
        val executionId: ActivityExecutionId,
        override val isLive: Boolean,
    ) : LauncherCommit

    data class Sequence(
        val executionId: SequenceExecutionId,
    ) : LauncherCommit {
        override val isLive: Boolean = true
    }
}

sealed interface LauncherCommandState {
    data object Idle : LauncherCommandState

    data object Checking : LauncherCommandState

    data class Preflight(
        val attemptId: Long,
        val targetId: LibraryTemplateId,
        val duration: Duration,
        val startedAt: Instant,
        val endsAt: Instant,
    ) : LauncherCommandState

    data object Committing : LauncherCommandState

    data class Conflict(
        val message: String,
    ) : LauncherCommandState

    data class Rejected(
        val message: String,
    ) : LauncherCommandState

    data class Committed(
        val result: LauncherCommit,
    ) : LauncherCommandState

    data class CommittedCoordinationFailure(
        val result: LauncherCommit,
        val message: String,
    ) : LauncherCommandState
}

internal enum class LauncherRouteExitDecision {
    BACK,
    WAIT_FOR_COMMIT,
    DELIVER_COMMIT,
}

data class StartActivityState(
    val home: LauncherLoad<LauncherHome> = LauncherLoad.Loading,
    val searchQuery: String = "",
    val search: LauncherLoad<List<LibraryTrackable>> = LauncherLoad.Idle,
    val browseFolderId: FolderId? = null,
    val browse: LauncherLoad<LibraryContents> = LauncherLoad.Idle,
    val selected: LauncherLoad<LibraryLaunchTarget> = LauncherLoad.Idle,
    val command: LauncherCommandState = LauncherCommandState.Idle,
    val organizationInFlight: Boolean = false,
    val organizationFailure: String? = null,
)

sealed interface StartActivityAction {
    data object Retry : StartActivityAction

    data class Search(
        val query: String,
    ) : StartActivityAction

    data class Browse(
        val folderId: FolderId?,
    ) : StartActivityAction

    data class Select(
        val id: LibraryTemplateId,
    ) : StartActivityAction

    data class ReorderPinned(
        val completeOrderedIds: List<LibraryTemplateId>,
    ) : StartActivityAction

    data class Launch(
        val mainValueOverride: QuickMainValueOverride? = null,
    ) : StartActivityAction

    data object RetryLaunch : StartActivityAction

    data object CancelPreflight : StartActivityAction

    data object Visible : StartActivityAction

    data object Hidden : StartActivityAction
}

internal sealed interface LauncherDurableCommand {
    val at: Instant
    val zoneId: ZoneId

    data class StartActivity(
        val templateId: com.alexandr5476.lifetracing.domain.ActivityTemplateId,
        val expectedRevision: Long,
        override val at: Instant,
        override val zoneId: ZoneId,
    ) : LauncherDurableCommand

    data class CompleteNoLive(
        val templateId: com.alexandr5476.lifetracing.domain.ActivityTemplateId,
        val override: QuickMainValueOverride?,
        val expectedRevision: Long,
        override val at: Instant,
        override val zoneId: ZoneId,
    ) : LauncherDurableCommand

    data class StartSequence(
        val templateId: com.alexandr5476.lifetracing.domain.SequenceTemplateId,
        val expectedRevision: Long,
        override val at: Instant,
        override val zoneId: ZoneId,
    ) : LauncherDurableCommand
}

internal fun interface PreflightHandle {
    fun cancel()
}

internal fun interface PreflightScheduler {
    fun schedule(
        duration: Duration,
        onBoundary: () -> Unit,
    ): PreflightHandle
}

internal class CoroutinePreflightScheduler(
    private val scope: CoroutineScope,
) : PreflightScheduler {
    override fun schedule(
        duration: Duration,
        onBoundary: () -> Unit,
    ): PreflightHandle {
        val job =
            scope.launch {
                delay(duration.toMillis())
                onBoundary()
            }
        return PreflightHandle(job::cancel)
    }
}

@Suppress("LongParameterList", "TooManyFunctions")
class StartActivityController internal constructor(
    private val scope: CoroutineScope,
    private val readRecent: suspend (Int) -> List<LibraryTrackable>,
    private val readPinned: suspend () -> List<LibraryTrackable>,
    private val searchLibrary: suspend (String) -> List<LibraryTrackable>,
    private val browseLibrary: suspend (FolderId?) -> LibraryContents,
    private val readTarget: suspend (LibraryTemplateId) -> LibraryLaunchTarget,
    private val reorderPinned: suspend (List<LibraryTemplateId>) -> Unit,
    private val hasLiveSession: suspend () -> Boolean,
    private val execute: suspend (LauncherDurableCommand) -> LauncherCommit,
    private val coordinateRuntimeStateChanged: suspend () -> Unit,
    private val wallClock: WallClock,
    private val zoneId: () -> ZoneId,
    private val preflightScheduler: PreflightScheduler,
    private val recentLimit: Int = DEFAULT_RECENT_LIMIT,
) {
    private val homeGeneration = AtomicLong()
    private val searchGeneration = AtomicLong()
    private val browseGeneration = AtomicLong()
    private val targetGeneration = AtomicLong()
    private val attemptGeneration = AtomicLong()
    private val lifecycleLock = Any()
    private val mutableState = MutableStateFlow(StartActivityState())
    val state: StateFlow<StartActivityState> = mutableState

    @Volatile
    private var visible = true

    @Volatile
    private var closed = false

    private var pendingLaunchJob: Job? = null
    private var preflightHandle: PreflightHandle? = null
    private var lastOverride: QuickMainValueOverride? = null

    init {
        require(recentLimit > 0) { "Launcher Recent limit must be positive" }
        refreshHome()
    }

    fun dispatch(action: StartActivityAction) {
        when (action) {
            StartActivityAction.Retry -> retryReads()
            is StartActivityAction.Search -> search(action.query)
            is StartActivityAction.Browse -> browse(action.folderId)
            is StartActivityAction.Select -> select(action.id)
            is StartActivityAction.ReorderPinned -> reorder(action.completeOrderedIds)
            is StartActivityAction.Launch -> launch(action.mainValueOverride)
            StartActivityAction.RetryLaunch -> retryLaunch()
            StartActivityAction.CancelPreflight -> abandonPendingLaunch()
            StartActivityAction.Visible -> onVisible()
            StartActivityAction.Hidden -> onHidden()
        }
    }

    fun onVisible() {
        if (closed) return
        visible = true
        refreshHome()
    }

    fun onHidden() {
        visible = false
        abandonPendingLaunch()
    }

    fun close() {
        closed = true
        visible = false
        homeGeneration.incrementAndGet()
        searchGeneration.incrementAndGet()
        browseGeneration.incrementAndGet()
        targetGeneration.incrementAndGet()
        abandonPendingLaunch()
    }

    /** Shares the durable-boundary lock so Back cannot escape a pending launch. */
    internal fun arbitrateRouteExit(): LauncherRouteExitDecision =
        synchronized(lifecycleLock) {
            when (mutableState.value.command) {
                is LauncherCommandState.Committed,
                is LauncherCommandState.CommittedCoordinationFailure,
                -> LauncherRouteExitDecision.DELIVER_COMMIT

                LauncherCommandState.Committing -> LauncherRouteExitDecision.WAIT_FOR_COMMIT
                LauncherCommandState.Checking,
                is LauncherCommandState.Preflight,
                -> {
                    cancelPendingLaunchLocked()
                    LauncherRouteExitDecision.BACK
                }

                else -> LauncherRouteExitDecision.BACK
            }
        }

    private fun retryReads() {
        if (mutableState.value.home is LauncherLoad.Failure) refreshHome()
        val search = mutableState.value.search
        if (search is LauncherLoad.Failure && mutableState.value.searchQuery.isNotBlank()) {
            search(mutableState.value.searchQuery)
        }
        if (mutableState.value.browse is LauncherLoad.Failure) browse(mutableState.value.browseFolderId)
        val selected = mutableState.value.selected
        if (selected is LauncherLoad.Failure) {
            val id = selectedTargetId ?: return
            select(id)
        }
    }

    private var selectedTargetId: LibraryTemplateId? = null

    private fun refreshHome() {
        if (closed) return
        val generation = homeGeneration.incrementAndGet()
        mutableState.update { it.copy(home = LauncherLoad.Loading) }
        scope.launch {
            try {
                val home = LauncherHome(readRecent(recentLimit), readPinned())
                if (homeGeneration.get() == generation && !closed) {
                    mutableState.update { it.copy(home = LauncherLoad.Content(home)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (homeGeneration.get() == generation && !closed) {
                    mutableState.update { it.copy(home = LauncherLoad.Failure(failure.message())) }
                }
            }
        }
    }

    private fun search(query: String) {
        val generation = searchGeneration.incrementAndGet()
        mutableState.update {
            it.copy(
                searchQuery = query,
                search = if (query.isBlank()) LauncherLoad.Idle else LauncherLoad.Loading,
            )
        }
        if (query.isBlank() || closed) return
        scope.launch {
            try {
                val results = searchLibrary(query)
                if (searchGeneration.get() == generation && !closed) {
                    mutableState.update { it.copy(search = LauncherLoad.Content(results)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (searchGeneration.get() == generation && !closed) {
                    mutableState.update { it.copy(search = LauncherLoad.Failure(failure.message())) }
                }
            }
        }
    }

    private fun browse(folderId: FolderId?) {
        val generation = browseGeneration.incrementAndGet()
        mutableState.update { it.copy(browseFolderId = folderId, browse = LauncherLoad.Loading) }
        if (closed) return
        scope.launch {
            try {
                val contents = browseLibrary(folderId)
                if (browseGeneration.get() == generation && !closed) {
                    mutableState.update { it.copy(browse = LauncherLoad.Content(contents)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (browseGeneration.get() == generation && !closed) {
                    mutableState.update { it.copy(browse = LauncherLoad.Failure(failure.message())) }
                }
            }
        }
    }

    private fun select(id: LibraryTemplateId) {
        val generation = synchronized(lifecycleLock) { selectLocked(id) } ?: return
        readSelected(id, generation)
    }

    private fun readSelected(
        id: LibraryTemplateId,
        generation: Long,
    ) {
        if (closed) return
        scope.launch {
            try {
                val target = readTarget(id)
                if (targetGeneration.get() == generation && selectedTargetId == id && !closed) {
                    mutableState.update { it.copy(selected = LauncherLoad.Content(target)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (targetGeneration.get() == generation && selectedTargetId == id && !closed) {
                    mutableState.update { it.copy(selected = LauncherLoad.Failure(failure.message())) }
                }
            }
        }
    }

    private fun selectLocked(id: LibraryTemplateId): Long? {
        when (mutableState.value.command) {
            LauncherCommandState.Committing,
            is LauncherCommandState.Committed,
            is LauncherCommandState.CommittedCoordinationFailure,
            -> return null

            else -> cancelPendingLaunchLocked()
        }
        selectedTargetId = id
        val generation = targetGeneration.incrementAndGet()
        mutableState.update { it.copy(selected = LauncherLoad.Loading, command = LauncherCommandState.Idle) }
        return generation
    }

    private fun reorder(ids: List<LibraryTemplateId>) {
        if (closed || mutableState.value.organizationInFlight) return
        mutableState.update { it.copy(organizationInFlight = true, organizationFailure = null) }
        scope.launch {
            try {
                reorderPinned(ids)
                mutableState.update { it.copy(organizationInFlight = false) }
                refreshHome()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(organizationInFlight = false, organizationFailure = failure.message())
                }
            }
        }
    }

    private fun launch(override: QuickMainValueOverride?) {
        val target = (mutableState.value.selected as? LauncherLoad.Content)?.value ?: return
        synchronized(lifecycleLock) {
            if (closed || !visible || mutableState.value.command != LauncherCommandState.Idle) return
            lastOverride = override
            validateOverride(target, override)?.let { rejection ->
                mutableState.update { it.copy(command = LauncherCommandState.Rejected(rejection)) }
                return
            }
            val attemptId = attemptGeneration.incrementAndGet()
            mutableState.update { it.copy(command = LauncherCommandState.Checking) }
            pendingLaunchJob = scope.launch { prepareLaunch(attemptId, target, override) }
        }
    }

    private suspend fun prepareLaunch(
        attemptId: Long,
        target: LibraryLaunchTarget,
        override: QuickMainValueOverride?,
    ) {
        try {
            if (target.isLive && hasLiveSession()) {
                publishAttempt(attemptId, LauncherCommandState.Conflict("Another live session is already active"))
                return
            }
            if (target.startCountdown.isZero) {
                beginDurable(attemptId, target, override, fromPreflight = false)
            } else {
                startPreflight(attemptId, target, override)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publishAttempt(attemptId, LauncherCommandState.Rejected(failure.message()))
        }
    }

    private fun startPreflight(
        attemptId: Long,
        target: LibraryLaunchTarget,
        override: QuickMainValueOverride?,
    ) {
        val startedAt = wallClock.now()
        val preflight =
            LauncherCommandState.Preflight(
                attemptId,
                target.id,
                target.startCountdown,
                startedAt,
                startedAt.plus(target.startCountdown),
            )
        if (!publishAttempt(attemptId, preflight)) return
        val handle =
            preflightScheduler.schedule(target.startCountdown) {
                beginDurable(attemptId, target, override, fromPreflight = true)
            }
        synchronized(lifecycleLock) {
            val current = mutableState.value.command
            if (current is LauncherCommandState.Preflight && current.attemptId == attemptId) {
                preflightHandle = handle
            } else {
                handle.cancel()
            }
        }
    }

    private fun beginDurable(
        attemptId: Long,
        target: LibraryLaunchTarget,
        override: QuickMainValueOverride?,
        fromPreflight: Boolean,
    ) {
        synchronized(lifecycleLock) {
            val current = mutableState.value.command
            val expected =
                if (fromPreflight) {
                    current is LauncherCommandState.Preflight && current.attemptId == attemptId
                } else {
                    current == LauncherCommandState.Checking
                }
            if (!expected || attemptGeneration.get() != attemptId || closed || !visible) return
            preflightHandle?.cancel()
            preflightHandle = null
            mutableState.update { it.copy(command = LauncherCommandState.Committing) }
            pendingLaunchJob = scope.launch { commit(target, override) }
        }
    }

    private suspend fun commit(
        target: LibraryLaunchTarget,
        override: QuickMainValueOverride?,
    ) {
        val now = wallClock.now()
        val command =
            when (target) {
                is LibraryLaunchTarget.Activity ->
                    if (target.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                        LauncherDurableCommand.CompleteNoLive(target.id.id, override, target.revision, now, zoneId())
                    } else {
                        LauncherDurableCommand.StartActivity(target.id.id, target.revision, now, zoneId())
                    }
                is LibraryLaunchTarget.Sequence ->
                    LauncherDurableCommand.StartSequence(target.id.id, target.revision, now, zoneId())
            }
        val committed =
            try {
                execute(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (conflict: LiveSessionConflictException) {
                publishCommitResult(LauncherCommandState.Conflict(conflict.message()))
                return
            } catch (ignored: StaleLauncherTargetException) {
                rehydrateTarget(target.id)
                return
            } catch (failure: Exception) {
                publishCommitResult(LauncherCommandState.Rejected(failure.message()))
                return
            }
        if (!committed.isLive) {
            publishCommitResult(LauncherCommandState.Committed(committed))
            return
        }
        try {
            coordinateRuntimeStateChanged()
            publishCommitResult(LauncherCommandState.Committed(committed))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publishCommitResult(LauncherCommandState.CommittedCoordinationFailure(committed, failure.message()))
        }
    }

    private fun publishCommitResult(command: LauncherCommandState) {
        synchronized(lifecycleLock) {
            if (mutableState.value.command == LauncherCommandState.Committing) {
                mutableState.update { it.copy(command = command) }
            }
        }
    }

    private fun rehydrateTarget(id: LibraryTemplateId) {
        val generation =
            synchronized(lifecycleLock) {
                if (mutableState.value.command != LauncherCommandState.Committing) return
                selectedTargetId = id
                val next = targetGeneration.incrementAndGet()
                mutableState.update { it.copy(selected = LauncherLoad.Loading, command = LauncherCommandState.Idle) }
                next
            }
        readSelected(id, generation)
    }

    private fun retryLaunch() {
        if (mutableState.value.command !is LauncherCommandState.Conflict &&
            mutableState.value.command !is LauncherCommandState.Rejected
        ) {
            return
        }
        mutableState.update { it.copy(command = LauncherCommandState.Idle) }
        launch(lastOverride)
    }

    private fun abandonPendingLaunch() {
        synchronized(lifecycleLock) {
            cancelPendingLaunchLocked()
        }
    }

    private fun cancelPendingLaunchLocked() {
        val command = mutableState.value.command
        if (command != LauncherCommandState.Checking && command !is LauncherCommandState.Preflight) return
        attemptGeneration.incrementAndGet()
        pendingLaunchJob?.cancel()
        pendingLaunchJob = null
        preflightHandle?.cancel()
        preflightHandle = null
        mutableState.update { it.copy(command = LauncherCommandState.Idle) }
    }

    private fun publishAttempt(
        attemptId: Long,
        command: LauncherCommandState,
    ): Boolean =
        synchronized(lifecycleLock) {
            if (attemptGeneration.get() != attemptId || closed || !visible) {
                false
            } else {
                mutableState.update { it.copy(command = command) }
                true
            }
        }

    private fun validateOverride(
        target: LibraryLaunchTarget,
        override: QuickMainValueOverride?,
    ): String? {
        if (override == null) return null
        val activity = target as? LibraryLaunchTarget.Activity ?: return "Only an Activity accepts Main Value"
        if (activity.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
            return "Quick Main Value is only accepted for No-live completion"
        }
        if (activity.mainValue?.fieldId != override.fieldId) return "Main Value Field is stale or mismatched"
        return null
    }

    private val LibraryLaunchTarget.isLive: Boolean
        get() = this is LibraryLaunchTarget.Sequence || timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING

    private val LibraryLaunchTarget.timeTrackingMode: TimeTrackingMode
        get() = (this as LibraryLaunchTarget.Activity).timeTrackingMode

    private fun Throwable.message(): String = message ?: javaClass.simpleName

    private companion object {
        const val DEFAULT_RECENT_LIMIT = 12
    }
}

internal fun QuickMainValueOverride.toEntryValue(): ActivityEntryValue =
    when (val value = value) {
        QuickMainValue.Missing -> ActivityEntryValue.Missing
        is QuickMainValue.Number -> ActivityEntryValue.Number(value.scaledValue)
    }
