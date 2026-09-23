@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "MaxLineLength",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package com.alexandr5476.lifetracing.history

import androidx.lifecycle.ViewModel
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionCommand
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalCommand
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalMode
import com.alexandr5476.lifetracing.domain.SequenceHistoryTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.util.ConcurrentModificationException

enum class SequenceHistoryMutationIssue {
    INVALID_DATE_TIME,
    NONEXISTENT_LOCAL_TIME,
    AMBIGUOUS_LOCAL_TIME,
    INVALID_PROPOSAL,
    STALE,
    READ_FAILURE,
    TIMING_FAILURE,
    CHILD_DELETION_FAILURE,
    STRUCTURAL_FAILURE,
}

data class SequenceHistoryMutationState(
    val load: HistoryDetailLoad<SequenceHistoryDetail> = HistoryDetailLoad.Loading,
    val timingDraft: SequenceHistoryTimingDraft? = null,
    val timingProposal: SequenceHistoryTimingProposal? = null,
    val noTimingChanges: Boolean = false,
    val overlapWarning: Boolean = false,
    val childDeletionProposal: SequenceChildHistoryDeletionCommand? = null,
    val childDeletionTarget: SequenceHistoryOccurrenceDescriptor? = null,
    val structuralTarget: SequenceOccurrenceId? = null,
    val structuralTargetDescriptor: SequenceHistoryOccurrenceDescriptor? = null,
    val structuralProposal: SequenceHistoryStructuralProposal? = null,
    val isMutating: Boolean = false,
    val issue: SequenceHistoryMutationIssue? = null,
    val refreshGeneration: Long = 0,
)

sealed interface SequenceHistoryMutationAction {
    data object Retry : SequenceHistoryMutationAction

    data object BeginTiming : SequenceHistoryMutationAction

    data class EditTimestamp(
        val target: SequenceHistoryTimestampTarget,
        val text: String,
    ) : SequenceHistoryMutationAction

    data class SelectTimestampOffset(
        val target: SequenceHistoryTimestampTarget,
        val offset: ZoneOffset,
    ) : SequenceHistoryMutationAction

    data object ReviewTiming : SequenceHistoryMutationAction

    data object ProceedOverlap : SequenceHistoryMutationAction

    data object CancelOverlap : SequenceHistoryMutationAction

    data object ConfirmTiming : SequenceHistoryMutationAction

    data class RequestChildDeletion(
        val occurrenceId: SequenceOccurrenceId,
    ) : SequenceHistoryMutationAction

    data object ConfirmChildDeletion : SequenceHistoryMutationAction

    data class BeginStructuralRemoval(
        val occurrenceId: SequenceOccurrenceId,
    ) : SequenceHistoryMutationAction

    data class ChooseStructuralMode(
        val mode: SequenceHistoryStructuralRemovalMode,
    ) : SequenceHistoryMutationAction

    data class PlaceOwnerlessInterval(
        val intervalId: SequenceIntervalId,
        val placement: OwnerlessIntervalPlacement,
    ) : SequenceHistoryMutationAction

    data object ConfirmStructuralRemoval : SequenceHistoryMutationAction

    data object Cancel : SequenceHistoryMutationAction
}

class SequenceHistoryMutationController internal constructor(
    private val scope: CoroutineScope,
    val executionId: SequenceExecutionId,
    private val readDetail: suspend (SequenceExecutionId) -> SequenceHistoryDetail?,
    private val correctTiming: suspend (SequenceExecutionId, SequenceHistoryTimingCorrection, Instant) -> Unit,
    private val deleteChild: suspend (SequenceExecutionId, SequenceChildHistoryDeletionCommand, Instant) -> Unit,
    private val removeOccurrence: suspend (
        SequenceExecutionId,
        SequenceHistoryStructuralRemovalCommand,
        Instant,
    ) -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    private val mutableState = MutableStateFlow(SequenceHistoryMutationState())
    val state: StateFlow<SequenceHistoryMutationState> = mutableState
    private var operation: Job? = null
    private var closed = false

    init {
        reload()
    }

    fun dispatch(action: SequenceHistoryMutationAction) {
        if (closed || mutableState.value.isMutating) return
        when (action) {
            SequenceHistoryMutationAction.Retry -> reload()
            SequenceHistoryMutationAction.BeginTiming -> beginTiming()
            is SequenceHistoryMutationAction.EditTimestamp -> editTimestamp(action.target, action.text)
            is SequenceHistoryMutationAction.SelectTimestampOffset -> selectOffset(action.target, action.offset)
            SequenceHistoryMutationAction.ReviewTiming -> reviewTiming()
            SequenceHistoryMutationAction.ProceedOverlap ->
                mutableState.update { it.copy(overlapWarning = false, issue = null) }
            SequenceHistoryMutationAction.CancelOverlap ->
                mutableState.update {
                    if (it.structuralProposal != null) {
                        it.copy(
                            structuralTarget = null,
                            structuralTargetDescriptor = null,
                            structuralProposal = null,
                            overlapWarning = false,
                            issue = null,
                        )
                    } else {
                        it.copy(timingProposal = null, noTimingChanges = false, overlapWarning = false, issue = null)
                    }
                }
            SequenceHistoryMutationAction.ConfirmTiming -> confirmTiming()
            is SequenceHistoryMutationAction.RequestChildDeletion -> requestChildDeletion(action.occurrenceId)
            SequenceHistoryMutationAction.ConfirmChildDeletion -> confirmChildDeletion()
            is SequenceHistoryMutationAction.BeginStructuralRemoval -> beginStructural(action.occurrenceId)
            is SequenceHistoryMutationAction.ChooseStructuralMode -> chooseStructuralMode(action.mode)
            is SequenceHistoryMutationAction.PlaceOwnerlessInterval ->
                placeOwnerless(action.intervalId, action.placement)
            SequenceHistoryMutationAction.ConfirmStructuralRemoval -> confirmStructural()
            SequenceHistoryMutationAction.Cancel -> cancelTransient()
        }
    }

    fun handleBack(): Boolean {
        val state = mutableState.value
        if (state.isMutating) return true
        if (state.hasTransient()) {
            cancelTransient()
            return true
        }
        return false
    }

    fun close() {
        closed = true
        operation?.cancel()
    }

    private fun reload(issue: SequenceHistoryMutationIssue? = null) {
        if (closed || mutableState.value.isMutating) return
        operation?.cancel()
        clearTransient(HistoryDetailLoad.Loading, issue)
        operation = scope.launch { loadCanonical(issue) }
    }

    private suspend fun loadCanonical(issue: SequenceHistoryMutationIssue?) {
        val detail =
            try {
                readDetail(executionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed) {
                    mutableState.update {
                        it.copy(
                            load = HistoryDetailLoad.Failure("read"),
                            isMutating = false,
                            issue = issue ?: SequenceHistoryMutationIssue.READ_FAILURE,
                        )
                    }
                }
                return
            }
        if (!closed) {
            mutableState.update {
                it.copy(
                    load =
                        detail?.let(HistoryDetailLoad<SequenceHistoryDetail>::Content)
                            ?: HistoryDetailLoad.Unavailable,
                    isMutating = false,
                    issue = issue,
                )
            }
        }
    }

    private fun beginTiming() {
        val detail = detail() ?: return
        mutableState.update {
            it.copy(
                timingDraft = SequenceHistoryProposalBuilder.timingDraft(detail),
                timingProposal = null,
                noTimingChanges = false,
                overlapWarning = false,
                childDeletionProposal = null,
                childDeletionTarget = null,
                structuralTarget = null,
                structuralTargetDescriptor = null,
                structuralProposal = null,
                issue = null,
            )
        }
    }

    private fun editTimestamp(
        target: SequenceHistoryTimestampTarget,
        text: String,
    ) {
        if (mutableState.value.overlapWarning) return
        mutableState.update { state ->
            val draft = state.timingDraft ?: return@update state
            val value = draft.timestamps[target] ?: return@update state
            state.copy(
                timingDraft =
                    draft.copy(
                        timestamps =
                            draft.timestamps +
                                (target to value.copy(text = text, selectedOffset = null, validOffsets = emptyList())),
                    ),
                timingProposal = null,
                noTimingChanges = false,
                issue = null,
            )
        }
    }

    private fun selectOffset(
        target: SequenceHistoryTimestampTarget,
        offset: ZoneOffset,
    ) {
        mutableState.update { state ->
            val draft = state.timingDraft ?: return@update state
            val value = draft.timestamps[target] ?: return@update state
            if (offset !in value.validOffsets) return@update state
            state.copy(
                timingDraft =
                    draft.copy(
                        timestamps = draft.timestamps + (target to value.copy(selectedOffset = offset)),
                    ),
                timingProposal = null,
                noTimingChanges = false,
                issue = null,
            )
        }
    }

    private fun reviewTiming() {
        val detail = detail() ?: return
        val draft = mutableState.value.timingDraft ?: return
        when (val result = SequenceHistoryProposalBuilder.timing(detail, draft)) {
            SequenceHistoryTimingBuildResult.NoChange ->
                mutableState.update {
                    it.copy(timingProposal = null, noTimingChanges = true, overlapWarning = false, issue = null)
                }
            is SequenceHistoryTimingBuildResult.Ready ->
                mutableState.update {
                    it.copy(
                        timingProposal = result.proposal,
                        noTimingChanges = false,
                        overlapWarning = result.proposal.hasIntervalOverlap,
                        issue = null,
                    )
                }
            is SequenceHistoryTimingBuildResult.Invalid ->
                mutableState.update { state ->
                    val value = state.timingDraft?.timestamps?.get(result.target)
                    state.copy(
                        timingDraft =
                            if (value == null || result.validOffsets.isEmpty()) {
                                state.timingDraft
                            } else {
                                state.timingDraft.copy(
                                    timestamps =
                                        state.timingDraft.timestamps +
                                            (result.target to value.copy(validOffsets = result.validOffsets)),
                                )
                            },
                        timingProposal = null,
                        noTimingChanges = false,
                        overlapWarning = false,
                        issue = result.issue.toMutationIssue(),
                    )
                }
        }
    }

    private fun confirmTiming() {
        val proposal = mutableState.value.timingProposal ?: return
        if (mutableState.value.overlapWarning) return
        mutate(SequenceHistoryMutationIssue.TIMING_FAILURE) {
            correctTiming(executionId, proposal.correction, it)
        }
    }

    private fun requestChildDeletion(occurrenceId: SequenceOccurrenceId) {
        val detail = detail() ?: return
        val occurrence = detail.occurrences.singleOrNull { it.occurrenceId == occurrenceId } ?: return
        if (occurrence.status != RuntimeOccurrenceStatus.COMPLETED || occurrence.isDeletedFromHistory) return
        val childId = occurrence.childMutationFacts?.executionId ?: return
        cancelTransient()
        mutableState.update {
            it.copy(
                childDeletionProposal =
                    SequenceChildHistoryDeletionCommand(detail.updatedAt, occurrenceId, childId),
                childDeletionTarget = SequenceHistoryProposalBuilder.occurrenceDescriptor(detail, occurrenceId),
            )
        }
    }

    private fun confirmChildDeletion() {
        val proposal = mutableState.value.childDeletionProposal ?: return
        mutate(SequenceHistoryMutationIssue.CHILD_DELETION_FAILURE) { deleteChild(executionId, proposal, it) }
    }

    private fun beginStructural(occurrenceId: SequenceOccurrenceId) {
        val detail = detail() ?: return
        val occurrence = detail.occurrences.singleOrNull { it.occurrenceId == occurrenceId } ?: return
        if (
            occurrence.status !in setOf(RuntimeOccurrenceStatus.COMPLETED, RuntimeOccurrenceStatus.DELETED_EXECUTION) ||
            occurrence.childMutationFacts == null
        ) {
            return
        }
        cancelTransient()
        mutableState.update {
            it.copy(
                structuralTarget = occurrenceId,
                structuralTargetDescriptor = SequenceHistoryProposalBuilder.occurrenceDescriptor(detail, occurrenceId),
            )
        }
    }

    private fun chooseStructuralMode(mode: SequenceHistoryStructuralRemovalMode) {
        val detail = detail() ?: return
        val target = mutableState.value.structuralTarget ?: return
        val proposal = SequenceHistoryProposalBuilder.structural(detail, target, mode)
        mutableState.update {
            it.copy(
                structuralProposal = proposal,
                overlapWarning = proposal?.hasIntervalOverlap == true,
                issue = if (proposal == null) SequenceHistoryMutationIssue.INVALID_PROPOSAL else null,
            )
        }
    }

    private fun placeOwnerless(
        intervalId: SequenceIntervalId,
        placement: OwnerlessIntervalPlacement,
    ) {
        val detail = detail() ?: return
        val existing = mutableState.value.structuralProposal ?: return
        if (intervalId !in existing.ownerlessPlacements) return
        val choices = existing.ownerlessPlacements.mapNotNull { (id, value) -> value?.let { id to it } }.toMap()
        val proposal =
            SequenceHistoryProposalBuilder.structural(
                detail,
                existing.occurrenceId,
                existing.mode,
                choices + (intervalId to placement),
            ) ?: return
        mutableState.update {
            it.copy(
                structuralProposal = proposal,
                overlapWarning = proposal.hasIntervalOverlap,
                issue = null,
            )
        }
    }

    private fun confirmStructural() {
        val command = mutableState.value.structuralProposal?.command ?: return
        if (mutableState.value.overlapWarning) return
        mutate(SequenceHistoryMutationIssue.STRUCTURAL_FAILURE) { removeOccurrence(executionId, command, it) }
    }

    private fun mutate(
        failure: SequenceHistoryMutationIssue,
        command: suspend (Instant) -> Unit,
    ) {
        mutableState.update { it.copy(isMutating = true, issue = null) }
        operation =
            scope.launch {
                try {
                    command(now())
                    if (!closed) {
                        mutableState.update {
                            it.copy(
                                load = HistoryDetailLoad.Loading,
                                timingDraft = null,
                                timingProposal = null,
                                noTimingChanges = false,
                                overlapWarning = false,
                                childDeletionProposal = null,
                                childDeletionTarget = null,
                                structuralTarget = null,
                                structuralTargetDescriptor = null,
                                structuralProposal = null,
                                refreshGeneration = it.refreshGeneration + 1,
                            )
                        }
                        loadCanonical(null)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ConcurrentModificationException) {
                    if (!closed) {
                        clearTransient(HistoryDetailLoad.Loading, SequenceHistoryMutationIssue.STALE, isMutating = true)
                        loadCanonical(SequenceHistoryMutationIssue.STALE)
                    }
                } catch (_: IllegalArgumentException) {
                    if (!closed) {
                        mutableState.update {
                            it.copy(isMutating = false, issue = SequenceHistoryMutationIssue.INVALID_PROPOSAL)
                        }
                    }
                } catch (_: Exception) {
                    if (!closed) mutableState.update { it.copy(isMutating = false, issue = failure) }
                }
            }
    }

    private fun cancelTransient() {
        if (mutableState.value.isMutating) return
        mutableState.update {
            it.copy(
                timingDraft = null,
                timingProposal = null,
                noTimingChanges = false,
                overlapWarning = false,
                childDeletionProposal = null,
                childDeletionTarget = null,
                structuralTarget = null,
                structuralTargetDescriptor = null,
                structuralProposal = null,
                issue = null,
            )
        }
    }

    private fun clearTransient(
        load: HistoryDetailLoad<SequenceHistoryDetail>,
        issue: SequenceHistoryMutationIssue?,
        isMutating: Boolean = false,
    ) {
        mutableState.update {
            it.copy(
                load = load,
                timingDraft = null,
                timingProposal = null,
                noTimingChanges = false,
                overlapWarning = false,
                childDeletionProposal = null,
                childDeletionTarget = null,
                structuralTarget = null,
                structuralTargetDescriptor = null,
                structuralProposal = null,
                isMutating = isMutating,
                issue = issue,
            )
        }
    }

    private fun detail() = (mutableState.value.load as? HistoryDetailLoad.Content)?.value

    private fun SequenceHistoryMutationState.hasTransient() =
        timingDraft != null ||
            timingProposal != null ||
            noTimingChanges ||
            overlapWarning ||
            childDeletionProposal != null ||
            structuralTarget != null ||
            structuralProposal != null

    private fun SequenceHistoryProposalIssue.toMutationIssue() =
        when (this) {
            SequenceHistoryProposalIssue.INVALID_DATE_TIME -> SequenceHistoryMutationIssue.INVALID_DATE_TIME
            SequenceHistoryProposalIssue.NONEXISTENT_LOCAL_TIME -> SequenceHistoryMutationIssue.NONEXISTENT_LOCAL_TIME
            SequenceHistoryProposalIssue.AMBIGUOUS_LOCAL_TIME -> SequenceHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME
            SequenceHistoryProposalIssue.MISSING_MUTATION_FACT -> SequenceHistoryMutationIssue.INVALID_PROPOSAL
        }
}

internal class SequenceHistoryMutationRouteSessionOwner : ViewModel() {
    private var session: SequenceHistoryMutationRouteSession? = null

    val activeSession: SequenceHistoryMutationRouteSession?
        get() = session

    fun acquire(
        executionId: SequenceExecutionId,
        create: () -> SequenceHistoryMutationController,
    ): SequenceHistoryMutationRouteSession {
        session?.takeIf { it.executionId == executionId }?.let { return it }
        session?.controller?.close()
        return SequenceHistoryMutationRouteSession(executionId, create()).also { session = it }
    }

    fun release(expected: SequenceHistoryMutationRouteSession) {
        if (session !== expected) return
        expected.controller.close()
        session = null
    }

    override fun onCleared() {
        session?.controller?.close()
        session = null
    }
}

internal class SequenceHistoryMutationRouteSession(
    val executionId: SequenceExecutionId,
    val controller: SequenceHistoryMutationController,
) {
    private var deliveredRefreshGeneration = 0L

    fun deliverRefresh(
        generation: Long,
        refresh: () -> Unit,
    ) {
        if (generation <= deliveredRefreshGeneration) return
        deliveredRefreshGeneration = generation
        refresh()
    }
}
