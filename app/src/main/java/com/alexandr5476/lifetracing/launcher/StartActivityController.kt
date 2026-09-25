@file:Suppress(
    "ComplexCondition",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "LargeClass",
    "CyclomaticComplexMethod",
    "MaxLineLength",
    "SwallowedException",
    "ThrowsCount",
)

package com.alexandr5476.lifetracing.launcher

import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.ExpiredFinishTimerStartException
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
import com.alexandr5476.lifetracing.history.ManualEntryFieldDraft
import com.alexandr5476.lifetracing.runtime.RuntimeMutationGate
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
import java.time.ZoneOffset
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

    data class Checking(
        val attemptId: Long,
    ) : LauncherCommandState

    data class Preflight(
        val attemptId: Long,
        val targetId: LibraryTemplateId,
        val duration: Duration,
        val startedAt: Instant,
        val endsAt: Instant,
    ) : LauncherCommandState

    data class Committing(
        val attemptId: Long,
    ) : LauncherCommandState

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

internal enum class LauncherReadChannel {
    HOME,
    SEARCH,
    BROWSE,
    SELECTED,
}

data class StartActivityState(
    val home: LauncherLoad<LauncherHome> = LauncherLoad.Loading,
    val searchQuery: String = "",
    val search: LauncherLoad<List<LibraryTrackable>> = LauncherLoad.Idle,
    val browseFolderId: FolderId? = null,
    val browse: LauncherLoad<LibraryContents> = LauncherLoad.Idle,
    val selected: LauncherLoad<LibraryLaunchTarget> = LauncherLoad.Idle,
    val command: LauncherCommandState = LauncherCommandState.Idle,
    val options: LauncherLoad<StartOptionsDraft> = LauncherLoad.Idle,
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

    data class OpenOptions(
        val id: ActivityTemplateId,
    ) : StartActivityAction

    data object CloseOptions : StartActivityAction

    data class EditOptionStart(
        val text: String,
    ) : StartActivityAction

    data class EditOptionEnd(
        val text: String,
    ) : StartActivityAction

    data class ChooseOptionStartOffset(
        val offset: ZoneOffset,
    ) : StartActivityAction

    data class ChooseOptionEndOffset(
        val offset: ZoneOffset,
    ) : StartActivityAction

    data class EditOptionNumber(
        val fieldId: ActivityTemplateFieldId,
        val text: String,
    ) : StartActivityAction

    data class EditOptionText(
        val fieldId: ActivityTemplateFieldId,
        val text: String,
    ) : StartActivityAction

    data class ChooseOptionCategory(
        val fieldId: ActivityTemplateFieldId,
        val optionId: CategoryOptionId,
    ) : StartActivityAction

    data class SetOptionMissing(
        val fieldId: ActivityTemplateFieldId,
        val missing: Boolean,
    ) : StartActivityAction

    data object SaveOptions : StartActivityAction

    data object ConfirmOptionsOverlap : StartActivityAction

    data object CancelOptionsOverlap : StartActivityAction

    data object ReviewOptionsTemplate : StartActivityAction

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

    data class StartOptionsLive(
        val proposal: StartOptionsProposal,
    ) : LauncherDurableCommand {
        override val at: Instant = proposal.commandAt
        override val zoneId: ZoneId = proposal.zoneId
    }

    data class StartOptionsTimed(
        val proposal: StartOptionsProposal,
    ) : LauncherDurableCommand {
        override val at: Instant = proposal.commandAt
        override val zoneId: ZoneId = proposal.zoneId
    }

    data class StartOptionsNoLive(
        val proposal: StartOptionsProposal,
    ) : LauncherDurableCommand {
        override val at: Instant = proposal.commandAt
        override val zoneId: ZoneId = proposal.zoneId
    }

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
    private val initialLiveConflict: (suspend (LibraryLaunchTarget) -> Boolean)? = null,
    private val onSelectObserved: (LauncherCommandState) -> Unit = {},
    private val onReadPublicationChecked: (LauncherReadChannel) -> Unit = {},
    private val onReadPublicationArbitrated: (LauncherReadChannel) -> Unit = {},
    private val onPinnedOrderCommitted: () -> Unit = {},
    private val mutationGate: RuntimeMutationGate = RuntimeMutationGate(),
    private val readActivityTemplate: suspend (ActivityTemplateId) -> ActivityTemplate? = { null },
    private val overlapsCompletedHistory: suspend (Instant, Instant) -> Boolean = { _, _ -> false },
) {
    private val homeGeneration = AtomicLong()
    private val searchGeneration = AtomicLong()
    private val browseGeneration = AtomicLong()
    private val targetGeneration = AtomicLong()
    private val attemptGeneration = AtomicLong()
    private val optionsGeneration = AtomicLong()
    private val lifecycleLock = Any()
    private val readPublicationLock = Any()
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
            is StartActivityAction.OpenOptions -> openOptions(action.id)
            StartActivityAction.CloseOptions -> closeOptions()
            is StartActivityAction.EditOptionStart ->
                editOptions {
                    it.copy(startedText = action.text, startedOffset = null, startedOffsets = emptyList())
                }
            is StartActivityAction.EditOptionEnd ->
                editOptions {
                    it.copy(completedText = action.text, completedOffset = null, completedOffsets = emptyList())
                }
            is StartActivityAction.ChooseOptionStartOffset -> editOptions { it.copy(startedOffset = action.offset) }
            is StartActivityAction.ChooseOptionEndOffset -> editOptions { it.copy(completedOffset = action.offset) }
            is StartActivityAction.EditOptionNumber ->
                editOptionValue(action.fieldId) {
                    it.copy(numberText = action.text, missing = false)
                }
            is StartActivityAction.EditOptionText ->
                editOptionValue(action.fieldId) {
                    it.copy(text = action.text, missing = false)
                }
            is StartActivityAction.ChooseOptionCategory ->
                editOptionValue(action.fieldId) {
                    it.copy(selectedOptionId = action.optionId, missing = false)
                }
            is StartActivityAction.SetOptionMissing ->
                editOptionValue(
                    action.fieldId,
                ) { it.copy(missing = action.missing) }
            StartActivityAction.SaveOptions -> submitOptions(false)
            StartActivityAction.ConfirmOptionsOverlap -> submitOptions(true)
            StartActivityAction.CancelOptionsOverlap -> editOptions { it }
            StartActivityAction.ReviewOptionsTemplate -> reviewOptionsTemplate()
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
        synchronized(readPublicationLock) {
            closed = true
            homeGeneration.incrementAndGet()
            searchGeneration.incrementAndGet()
            browseGeneration.incrementAndGet()
            targetGeneration.incrementAndGet()
            optionsGeneration.incrementAndGet()
        }
        visible = false
        abandonPendingLaunch()
    }

    /** Shares the durable-boundary lock so Back cannot escape a pending launch. */
    internal fun arbitrateRouteExit(): LauncherRouteExitDecision =
        synchronized(lifecycleLock) {
            when (mutableState.value.command) {
                is LauncherCommandState.Committed,
                is LauncherCommandState.CommittedCoordinationFailure,
                -> LauncherRouteExitDecision.DELIVER_COMMIT

                is LauncherCommandState.Committing -> LauncherRouteExitDecision.WAIT_FOR_COMMIT
                is LauncherCommandState.Checking,
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
        val generation =
            synchronized(readPublicationLock) {
                if (closed) return
                homeGeneration.incrementAndGet().also {
                    mutableState.update { state -> state.copy(home = LauncherLoad.Loading) }
                }
            }
        scope.launch {
            try {
                val home = LauncherHome(readRecent(recentLimit), readPinned())
                publishRead(generation, homeGeneration, LauncherReadChannel.HOME) { state ->
                    state.copy(home = LauncherLoad.Content(home))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publishRead(generation, homeGeneration, LauncherReadChannel.HOME) { state ->
                    state.copy(home = LauncherLoad.Failure(failure.message()))
                }
            }
        }
    }

    private fun search(query: String) {
        val generation =
            synchronized(readPublicationLock) {
                searchGeneration.incrementAndGet().also {
                    mutableState.update { state ->
                        state.copy(
                            searchQuery = query,
                            search = if (query.isBlank()) LauncherLoad.Idle else LauncherLoad.Loading,
                        )
                    }
                }
            }
        if (query.isBlank() || closed) return
        scope.launch {
            try {
                val results = searchLibrary(query)
                publishRead(generation, searchGeneration, LauncherReadChannel.SEARCH) { state ->
                    state.copy(search = LauncherLoad.Content(results))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publishRead(generation, searchGeneration, LauncherReadChannel.SEARCH) { state ->
                    state.copy(search = LauncherLoad.Failure(failure.message()))
                }
            }
        }
    }

    private fun browse(folderId: FolderId?) {
        val generation =
            synchronized(readPublicationLock) {
                browseGeneration.incrementAndGet().also {
                    mutableState.update { state ->
                        state.copy(browseFolderId = folderId, browse = LauncherLoad.Loading)
                    }
                }
            }
        if (closed) return
        scope.launch {
            try {
                val contents = browseLibrary(folderId)
                publishRead(generation, browseGeneration, LauncherReadChannel.BROWSE) { state ->
                    state.copy(browse = LauncherLoad.Content(contents))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publishRead(generation, browseGeneration, LauncherReadChannel.BROWSE) { state ->
                    state.copy(browse = LauncherLoad.Failure(failure.message()))
                }
            }
        }
    }

    private fun select(id: LibraryTemplateId) {
        // A pre-lock observation is deliberately non-authoritative; the locked re-check decides.
        onSelectObserved(mutableState.value.command)
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
                publishRead(generation, targetGeneration, LauncherReadChannel.SELECTED) { state ->
                    state.copy(selected = LauncherLoad.Content(target))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publishRead(generation, targetGeneration, LauncherReadChannel.SELECTED) { state ->
                    state.copy(selected = LauncherLoad.Failure(failure.message()))
                }
            }
        }
    }

    private fun selectLocked(id: LibraryTemplateId): Long? {
        when (mutableState.value.command) {
            is LauncherCommandState.Committing,
            is LauncherCommandState.Committed,
            is LauncherCommandState.CommittedCoordinationFailure,
            -> return null

            else -> cancelPendingLaunchLocked()
        }
        return synchronized(readPublicationLock) {
            selectedTargetId = id
            targetGeneration.incrementAndGet().also {
                mutableState.update { state ->
                    state.copy(selected = LauncherLoad.Loading, command = LauncherCommandState.Idle)
                }
            }
        }
    }

    private fun publishRead(
        generation: Long,
        currentGeneration: AtomicLong,
        channel: LauncherReadChannel,
        transform: (StartActivityState) -> StartActivityState,
    ) {
        if (closed || currentGeneration.get() != generation) return
        // This observation is deliberately non-authoritative; ownership and publication share the lock below.
        onReadPublicationChecked(channel)
        synchronized(readPublicationLock) {
            if (!closed && currentGeneration.get() == generation) {
                mutableState.update(transform)
            }
        }
        onReadPublicationArbitrated(channel)
    }

    private fun reorder(ids: List<LibraryTemplateId>) {
        if (closed || mutableState.value.organizationInFlight) return
        mutableState.update { it.copy(organizationInFlight = true, organizationFailure = null) }
        scope.launch {
            try {
                reorderPinned(ids)
                onPinnedOrderCommitted()
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

    private fun openOptions(
        id: ActivityTemplateId,
        retained: StartOptionsDraft? = null,
    ) {
        val generation =
            synchronized(lifecycleLock) {
                if (closed ||
                    !visible ||
                    mutableState.value.command != LauncherCommandState.Idle ||
                    mutableState.value.options != LauncherLoad.Idle
                ) {
                    return
                }
                optionsGeneration.incrementAndGet().also { next ->
                    mutableState.update { it.copy(options = LauncherLoad.Loading) }
                }
            }
        scope.launch {
            try {
                val template = readActivityTemplate(id)
                synchronized(lifecycleLock) {
                    if (closed || optionsGeneration.get() != generation) return@launch
                    mutableState.update { state ->
                        state.copy(
                            options =
                                if (template == null || template.deletedAt != null) {
                                    LauncherLoad.Failure("Activity is unavailable")
                                } else {
                                    val current = template.initialStartOptions(wallClock.now(), zoneId())
                                    LauncherLoad.Content(
                                        if (retained == null) {
                                            current
                                        } else {
                                            current.copy(
                                                startedText = retained.startedText,
                                                completedText = retained.completedText,
                                                startedOffset = retained.startedOffset,
                                                completedOffset = retained.completedOffset,
                                            )
                                        },
                                    )
                                },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                synchronized(lifecycleLock) {
                    if (!closed && optionsGeneration.get() == generation) {
                        mutableState.update { it.copy(options = LauncherLoad.Failure(failure.message())) }
                    }
                }
            }
        }
    }

    private fun closeOptions() {
        synchronized(lifecycleLock) {
            if (mutableState.value.command is LauncherCommandState.Committing ||
                mutableState.value.command is LauncherCommandState.Committed ||
                mutableState.value.command is LauncherCommandState.CommittedCoordinationFailure
            ) {
                return
            }
            optionsGeneration.incrementAndGet()
            cancelPendingLaunchLocked()
            mutableState.update { it.copy(options = LauncherLoad.Idle, command = LauncherCommandState.Idle) }
        }
    }

    private fun editOptions(transform: (StartOptionsDraft) -> StartOptionsDraft) {
        synchronized(lifecycleLock) {
            if (mutableState.value.command != LauncherCommandState.Idle) return
            mutableState.update { state ->
                val draft = (state.options as? LauncherLoad.Content)?.value ?: return@update state
                state.copy(
                    options =
                        LauncherLoad.Content(
                            transform(draft).copy(
                                issue = null,
                                overlap = null,
                                draftVersion = draft.draftVersion + 1,
                            ),
                        ),
                )
            }
        }
    }

    private fun editOptionValue(
        id: ActivityTemplateFieldId,
        transform: (ManualEntryFieldDraft) -> ManualEntryFieldDraft,
    ) = editOptions { draft ->
        draft.copy(
            values =
                draft.values[id]?.let { draft.values + (id to transform(it)) } ?: draft.values,
        )
    }

    private fun reviewOptionsTemplate() {
        val id =
            synchronized(lifecycleLock) {
                val draft = (mutableState.value.options as? LauncherLoad.Content)?.value ?: return
                if (mutableState.value.command != LauncherCommandState.Idle || !draft.stale) return
                mutableState.update { it.copy(options = LauncherLoad.Idle) }
                draft.template.id
            }
        openOptions(id)
    }

    private fun submitOptions(approvedOverlap: Boolean) {
        val draft = (mutableState.value.options as? LauncherLoad.Content)?.value ?: return
        val approved = draft.overlap.takeIf { approvedOverlap }
        synchronized(lifecycleLock) {
            if (closed || !visible || mutableState.value.command != LauncherCommandState.Idle || draft.stale) return
            val attempt = attemptGeneration.incrementAndGet()
            mutableState.update { it.copy(command = LauncherCommandState.Checking(attempt)) }
            pendingLaunchJob = scope.launch { prepareOptions(attempt, draft, approved) }
        }
    }

    private suspend fun prepareOptions(
        attempt: Long,
        draft: StartOptionsDraft,
        approved: StartOptionsProposal?,
    ) {
        try {
            val zone = zoneId()
            val first =
                when (val validation = draft.validate(wallClock.now(), zone)) {
                    is StartOptionsValidation.Valid -> validation.proposal
                    is StartOptionsValidation.Invalid -> {
                        optionIssue(attempt, validation)
                        return
                    }
                }
            if (first.isLive && hasLiveSession()) {
                optionIssue(attempt, StartOptionsValidation.Invalid(StartOptionsIssue.LIVE_CONFLICT))
                return
            }
            val interval = first.interval
            if (interval != null &&
                (approved == null || approved.interval != interval || approved.draftVersion != draft.draftVersion) &&
                overlapsCompletedHistory(interval.first, interval.second)
            ) {
                synchronized(lifecycleLock) {
                    if (optionAttemptCurrent(attempt, draft)) {
                        mutableState.update { state ->
                            state.copy(
                                options = LauncherLoad.Content(draft.copy(overlap = first, issue = null)),
                                command = LauncherCommandState.Idle,
                            )
                        }
                    }
                }
                return
            }
            if (zoneId() != zone) {
                optionIssue(attempt, StartOptionsValidation.Invalid(StartOptionsIssue.ZONE_CHANGED))
                return
            }
            synchronized(lifecycleLock) {
                if (!optionAttemptCurrent(attempt, draft)) return
                mutableState.update { it.copy(command = LauncherCommandState.Committing(attempt)) }
            }
            commitOptions(attempt, draft, zone)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            optionIssue(attempt, StartOptionsValidation.Invalid(StartOptionsIssue.SAVE_FAILURE))
        }
    }

    private fun optionAttemptCurrent(
        attempt: Long,
        draft: StartOptionsDraft,
    ): Boolean {
        val current = (mutableState.value.options as? LauncherLoad.Content)?.value
        return attemptGeneration.get() == attempt &&
            current?.draftVersion == draft.draftVersion &&
            current.template.id == draft.template.id &&
            mutableState.value.command is LauncherCommandState.Checking
    }

    private fun optionIssue(
        attempt: Long,
        invalid: StartOptionsValidation.Invalid,
    ) {
        synchronized(lifecycleLock) {
            if (attemptGeneration.get() != attempt) return
            val command = mutableState.value.command
            if (command !is LauncherCommandState.Checking && command !is LauncherCommandState.Committing) return
            mutableState.update { state ->
                val draft = (state.options as? LauncherLoad.Content)?.value ?: return@update state
                state.copy(
                    options =
                        LauncherLoad.Content(
                            draft.copy(
                                issue = invalid.issue,
                                stale = draft.stale || invalid.issue == StartOptionsIssue.STALE_TEMPLATE,
                                startedOffsets =
                                    if (invalid.issue ==
                                        StartOptionsIssue.AMBIGUOUS_START
                                    ) {
                                        invalid.offsets
                                    } else {
                                        draft.startedOffsets
                                    },
                                completedOffsets =
                                    if (invalid.issue ==
                                        StartOptionsIssue.AMBIGUOUS_END
                                    ) {
                                        invalid.offsets
                                    } else {
                                        draft.completedOffsets
                                    },
                            ),
                        ),
                    command = LauncherCommandState.Idle,
                )
            }
        }
    }

    private suspend fun commitOptions(
        attempt: Long,
        draft: StartOptionsDraft,
        zone: ZoneId,
    ) {
        val committed =
            try {
                val admission =
                    mutationGate.admit {
                        if (zoneId() != zone) {
                            throw StartOptionsValidationException(
                                StartOptionsValidation.Invalid(StartOptionsIssue.ZONE_CHANGED),
                            )
                        }
                        when (val validated = draft.validate(wallClock.now(), zone)) {
                            is StartOptionsValidation.Valid -> {
                                val proposal = validated.proposal
                                when {
                                    proposal.isLive -> LauncherDurableCommand.StartOptionsLive(proposal)
                                    proposal.startedAt == null -> LauncherDurableCommand.StartOptionsNoLive(proposal)
                                    else -> LauncherDurableCommand.StartOptionsTimed(proposal)
                                }
                            }
                            is StartOptionsValidation.Invalid -> throw StartOptionsValidationException(validated)
                        }
                    }
                requireNotNull(admission).turn.run { execute(admission.command) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: StartOptionsValidationException) {
                optionIssue(attempt, invalid.validation)
                return
            } catch (_: ExpiredFinishTimerStartException) {
                optionIssue(attempt, StartOptionsValidation.Invalid(StartOptionsIssue.EXPIRED_FINISH))
                return
            } catch (_: LiveSessionConflictException) {
                optionIssue(attempt, StartOptionsValidation.Invalid(StartOptionsIssue.LIVE_CONFLICT))
                return
            } catch (_: StaleLauncherTargetException) {
                optionIssue(attempt, StartOptionsValidation.Invalid(StartOptionsIssue.STALE_TEMPLATE))
                return
            } catch (_: Exception) {
                optionIssue(attempt, StartOptionsValidation.Invalid(StartOptionsIssue.SAVE_FAILURE))
                return
            }
        if (!committed.isLive) {
            publishCommitResult(attempt, LauncherCommandState.Committed(committed))
            return
        }
        try {
            coordinateRuntimeStateChanged()
            publishCommitResult(attempt, LauncherCommandState.Committed(committed))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publishCommitResult(
                attempt,
                LauncherCommandState.CommittedCoordinationFailure(committed, failure.message()),
            )
        }
    }

    private class StartOptionsValidationException(
        val validation: StartOptionsValidation.Invalid,
    ) : IllegalArgumentException()

    private fun launch(override: QuickMainValueOverride?) {
        val target = (mutableState.value.selected as? LauncherLoad.Content)?.value ?: return
        synchronized(lifecycleLock) {
            if (closed ||
                !visible ||
                mutableState.value.command != LauncherCommandState.Idle ||
                mutableState.value.options != LauncherLoad.Idle
            ) {
                return
            }
            lastOverride = override
            validateOverride(target, override)?.let { rejection ->
                mutableState.update { it.copy(command = LauncherCommandState.Rejected(rejection)) }
                return
            }
            val attemptId = attemptGeneration.incrementAndGet()
            mutableState.update { it.copy(command = LauncherCommandState.Checking(attemptId)) }
            pendingLaunchJob = scope.launch { prepareLaunch(attemptId, target, override) }
        }
    }

    private suspend fun prepareLaunch(
        attemptId: Long,
        target: LibraryLaunchTarget,
        override: QuickMainValueOverride?,
    ) {
        try {
            val hasConflict = initialLiveConflict?.invoke(target) ?: (target.isLive && hasLiveSession())
            if (hasConflict) {
                publishAttempt(
                    attemptId,
                    { it is LauncherCommandState.Checking && it.attemptId == attemptId },
                    LauncherCommandState.Conflict("Another live session is already active"),
                )
                return
            }
            if (target.startCountdown.isZero) {
                beginDurable(attemptId, target, override, fromPreflight = false)
            } else {
                startPreflight(attemptId, target, override)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: StaleLauncherTargetException) {
            rehydrateTarget(attemptId, target.id, checkingAttempt(attemptId))
        } catch (failure: Exception) {
            publishAttempt(
                attemptId,
                checkingAttempt(attemptId),
                LauncherCommandState.Rejected(failure.message()),
            )
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
        if (!publishAttempt(attemptId, checkingAttempt(attemptId), preflight)) return
        val handle =
            preflightScheduler.schedule(target.startCountdown) {
                beginDurable(attemptId, target, override, fromPreflight = true)
            }
        synchronized(lifecycleLock) {
            val current = mutableState.value.command
            if (attemptGeneration.get() == attemptId &&
                current is LauncherCommandState.Preflight &&
                current.attemptId == attemptId
            ) {
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
                    current is LauncherCommandState.Checking && current.attemptId == attemptId
                }
            if (!expected || attemptGeneration.get() != attemptId || closed || !visible) return
            preflightHandle?.cancel()
            preflightHandle = null
            mutableState.update { it.copy(command = LauncherCommandState.Committing(attemptId)) }
            pendingLaunchJob = scope.launch { commit(attemptId, target, override) }
        }
    }

    private suspend fun commit(
        attemptId: Long,
        target: LibraryLaunchTarget,
        override: QuickMainValueOverride?,
    ) {
        val admission =
            mutationGate.admit {
                val now = wallClock.now()
                when (target) {
                    is LibraryLaunchTarget.Activity ->
                        if (target.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                            LauncherDurableCommand.CompleteNoLive(
                                target.id.id,
                                override,
                                target.revision,
                                now,
                                zoneId(),
                            )
                        } else {
                            LauncherDurableCommand.StartActivity(target.id.id, target.revision, now, zoneId())
                        }
                    is LibraryLaunchTarget.Sequence ->
                        LauncherDurableCommand.StartSequence(target.id.id, target.revision, now, zoneId())
                }
            }
        val committed =
            try {
                requireNotNull(admission).turn.run { execute(admission.command) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (conflict: LiveSessionConflictException) {
                publishCommitResult(attemptId, LauncherCommandState.Conflict(conflict.message()))
                return
            } catch (ignored: StaleLauncherTargetException) {
                rehydrateTarget(attemptId, target.id, committingAttempt(attemptId))
                return
            } catch (failure: Exception) {
                publishCommitResult(attemptId, LauncherCommandState.Rejected(failure.message()))
                return
            }
        if (!committed.isLive) {
            publishCommitResult(attemptId, LauncherCommandState.Committed(committed))
            return
        }
        try {
            coordinateRuntimeStateChanged()
            publishCommitResult(attemptId, LauncherCommandState.Committed(committed))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publishCommitResult(
                attemptId,
                LauncherCommandState.CommittedCoordinationFailure(committed, failure.message()),
            )
        }
    }

    private fun publishCommitResult(
        attemptId: Long,
        command: LauncherCommandState,
    ) {
        synchronized(lifecycleLock) {
            if (attemptGeneration.get() == attemptId && committingAttempt(attemptId)(mutableState.value.command)) {
                mutableState.update { it.copy(command = command) }
            }
        }
    }

    private fun rehydrateTarget(
        attemptId: Long,
        id: LibraryTemplateId,
        expected: (LauncherCommandState) -> Boolean,
    ) {
        val generation =
            synchronized(lifecycleLock) {
                if (attemptGeneration.get() != attemptId || !expected(mutableState.value.command)) {
                    return
                }
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
        if (command !is LauncherCommandState.Checking && command !is LauncherCommandState.Preflight) return
        attemptGeneration.incrementAndGet()
        pendingLaunchJob?.cancel()
        pendingLaunchJob = null
        preflightHandle?.cancel()
        preflightHandle = null
        mutableState.update { it.copy(command = LauncherCommandState.Idle) }
    }

    private fun publishAttempt(
        attemptId: Long,
        expected: (LauncherCommandState) -> Boolean,
        command: LauncherCommandState,
    ): Boolean =
        synchronized(lifecycleLock) {
            if (attemptGeneration.get() != attemptId || !expected(mutableState.value.command) || closed || !visible) {
                false
            } else {
                mutableState.update { it.copy(command = command) }
                true
            }
        }

    private fun checkingAttempt(attemptId: Long): (LauncherCommandState) -> Boolean =
        { state ->
            state is LauncherCommandState.Checking && state.attemptId == attemptId
        }

    private fun committingAttempt(attemptId: Long): (LauncherCommandState) -> Boolean =
        { state ->
            state is LauncherCommandState.Committing && state.attemptId == attemptId
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
