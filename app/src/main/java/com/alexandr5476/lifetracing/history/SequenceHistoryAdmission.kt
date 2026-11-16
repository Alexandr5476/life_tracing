@file:Suppress(
    "FunctionNaming",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LongParameterList",
    "MatchingDeclarationName",
    "TooManyFunctions",
    "LongMethod",
)

package com.alexandr5476.lifetracing.history

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton

/** A publication owns buttons; a native timestamp input advances only its own cursor. */
internal class SequenceHistoryAdmission(
    private val executionId: SequenceExecutionId,
    private val publication: SequenceHistoryMutationState,
    private val current: () -> SequenceHistoryMutationState,
    private val ownsRoute: () -> Boolean,
    private val dispatch: (SequenceHistoryMutationAction) -> Unit,
    private val onRevoked: () -> Unit = {},
) {
    var attached = true
    private var revoked = false
    private val canonicalValid =
        (publication.load as? HistoryDetailLoad.Content)
            ?.value
            ?.hasValidSequenceIdentity(executionId) == true

    fun revoke() {
        if (!revoked) {
            revoked = true
            onRevoked()
        }
    }

    private fun ownsActionRoute(): Boolean {
        if (!attached || revoked) return false
        if (!ownsRoute()) revoke()
        return !revoked
    }

    fun isCurrent(): Boolean =
        attached && !revoked && ownsRoute() && current() === publication && !publication.isMutating

    fun enabled(action: SequenceHistoryMutationAction): Boolean =
        isCurrent() &&
            (action == SequenceHistoryMutationAction.Retry || canonicalValid) &&
            sequenceHistoryActionAllowed(executionId, publication, action)

    fun action(action: SequenceHistoryMutationAction): () -> Unit =
        {
            if (accepts(publication, action)) dispatch(action)
        }

    fun back(block: () -> Unit): () -> Unit =
        {
            if (ownsActionRoute() && isCurrent()) block()
        }

    fun input(target: SequenceHistoryTimestampTarget): (String) -> Boolean {
        var expected = publication
        return { text ->
            val action = SequenceHistoryMutationAction.EditTimestamp(target, text)
            if (accepts(expected, action)) {
                dispatch(action)
                expected = current()
                true
            } else {
                false
            }
        }
    }

    private fun accepts(
        expected: SequenceHistoryMutationState,
        action: SequenceHistoryMutationAction,
    ): Boolean =
        ownsActionRoute() &&
            current() === expected &&
            (action == SequenceHistoryMutationAction.Retry || canonicalValid) &&
            sequenceHistoryActionAllowed(executionId, expected, action)
}

internal fun SequenceHistoryMutationState.hasSequenceTransient(): Boolean =
    timingDraft != null ||
        timingProposal != null ||
        noTimingChanges ||
        overlapWarning ||
        childDeletionProposal != null ||
        structuralTarget != null ||
        structuralProposal != null

internal fun SequenceHistoryDetail.isHistoricalSequence(id: SequenceExecutionId): Boolean =
    root.executionId == id &&
        root.status in setOf(SequenceExecutionStatus.COMPLETED, SequenceExecutionStatus.ENDED_EARLY) &&
        root.completedAt >= root.startedAt

/** Phase policy uses the existing canonical aggregate and proposals; it never reads History. */
internal fun sequenceHistoryActionAllowed(
    executionId: SequenceExecutionId,
    state: SequenceHistoryMutationState,
    action: SequenceHistoryMutationAction,
): Boolean {
    if (state.isMutating) return false
    if (action == SequenceHistoryMutationAction.Retry) {
        return state.load is HistoryDetailLoad.Failure || state.load is HistoryDetailLoad.Unavailable
    }
    val detail = (state.load as? HistoryDetailLoad.Content)?.value ?: return false
    if (!detail.isHistoricalSequence(executionId)) return false
    if (action == SequenceHistoryMutationAction.Cancel) return state.hasSequenceTransient()
    val phaseCount =
        (if (state.timingDraft != null) 1 else 0) +
            (if (state.childDeletionProposal != null) 1 else 0) +
            (if (state.structuralTarget != null) 1 else 0)
    if (phaseCount > 1) return false
    val idle = !state.hasSequenceTransient()
    val draft = state.timingDraft
    val timingReview =
        state.timingProposal?.let {
            draft?.expectedUpdatedAt == detail.updatedAt &&
                it.correction.expectedUpdatedAt == detail.updatedAt &&
                it.changes.isNotEmpty() &&
                !state.noTimingChanges
        } == true
    val structural = state.structuralProposal
    val structuralReview =
        structural?.let {
            it.occurrenceId == state.structuralTarget &&
                it.isConfirmable &&
                it.ownerlessPlacements.values.none { placement -> placement == null } &&
                it.command?.expectedUpdatedAt == detail.updatedAt &&
                it.command.occurrenceId == it.occurrenceId &&
                detail.eligibleOccurrence(it.occurrenceId, structural = true)?.childMutationFacts?.executionId ==
                it.command.childExecutionId
        } == true
    return when (action) {
        SequenceHistoryMutationAction.Retry -> false
        SequenceHistoryMutationAction.BeginTiming -> idle
        is SequenceHistoryMutationAction.RequestChildDeletion ->
            idle && detail.eligibleOccurrence(action.occurrenceId, structural = false) != null
        is SequenceHistoryMutationAction.BeginStructuralRemoval ->
            idle && detail.eligibleOccurrence(action.occurrenceId, structural = true) != null
        SequenceHistoryMutationAction.Cancel -> state.hasSequenceTransient()
        SequenceHistoryMutationAction.ProceedOverlap,
        SequenceHistoryMutationAction.CancelOverlap,
        -> state.overlapWarning && (timingReview || structuralReview)
        is SequenceHistoryMutationAction.EditTimestamp ->
            draft?.expectedUpdatedAt == detail.updatedAt &&
                action.target in draft.timestamps &&
                state.timingProposal == null &&
                !state.overlapWarning
        is SequenceHistoryMutationAction.SelectTimestampOffset ->
            draft?.expectedUpdatedAt == detail.updatedAt &&
                action.offset in draft.timestamps[action.target]?.validOffsets.orEmpty() &&
                state.timingProposal == null &&
                !state.overlapWarning
        SequenceHistoryMutationAction.ReviewTiming ->
            draft?.expectedUpdatedAt == detail.updatedAt && state.timingProposal == null && !state.overlapWarning
        SequenceHistoryMutationAction.ConfirmTiming -> timingReview && !state.overlapWarning
        SequenceHistoryMutationAction.ConfirmChildDeletion ->
            state.childDeletionProposal?.let {
                it.expectedUpdatedAt == detail.updatedAt &&
                    state.childDeletionTarget?.occurrenceId == it.occurrenceId &&
                    detail.eligibleOccurrence(it.occurrenceId, structural = false)?.childMutationFacts?.executionId ==
                    it.childExecutionId
            } == true
        is SequenceHistoryMutationAction.ChooseStructuralMode ->
            state.structuralTarget?.let { detail.eligibleOccurrence(it, structural = true) } != null &&
                structural == null &&
                !state.overlapWarning
        is SequenceHistoryMutationAction.PlaceOwnerlessInterval ->
            structural != null &&
                !structural.isConfirmable &&
                structural.occurrenceId == state.structuralTarget &&
                action.intervalId in structural.ownerlessPlacements &&
                structural.ownerlessPlacements[action.intervalId] == null
        SequenceHistoryMutationAction.ConfirmStructuralRemoval -> structuralReview && !state.overlapWarning
    }
}

private fun SequenceHistoryDetail.eligibleOccurrence(
    id: SequenceOccurrenceId,
    structural: Boolean,
) = occurrences.singleOrNull { it.occurrenceId == id }?.takeIf {
    !it.isDeletedFromHistory &&
        it.childMutationFacts != null &&
        (it.child == null || it.child?.executionId == it.childMutationFacts?.executionId) &&
        (
            it.status == RuntimeOccurrenceStatus.COMPLETED ||
                structural &&
                it.status == RuntimeOccurrenceStatus.DELETED_EXECUTION
        )
}

/** Validate only the selected aggregate; temporary identity sets never become presentation state. */
internal fun SequenceHistoryDetail.hasValidSequenceIdentity(id: SequenceExecutionId): Boolean {
    if (!isHistoricalSequence(id)) return false
    val occurrenceIds = HashSet<SequenceOccurrenceId>()
    val childIds = HashSet<ActivityExecutionId>()
    val intervalIds = HashSet<SequenceIntervalId>()
    return occurrences.all { occurrence ->
        val child = occurrence.child?.executionId
        val facts = occurrence.childMutationFacts?.executionId
        occurrenceIds.add(occurrence.occurrenceId) &&
            (child == null || facts == null || child == facts) &&
            ((facts ?: child)?.let(childIds::add) != false)
    } &&
        intervals.all {
            intervalIds.add(it.id) &&
                it.endedAt != null &&
                (it.occurrenceId == null || it.occurrenceId in occurrenceIds)
        }
}

internal val LocalSequenceHistoryAdmission = staticCompositionLocalOf<SequenceHistoryAdmission?> { null }

@Composable
internal fun rememberSequenceHistoryAdmission(
    executionId: SequenceExecutionId,
    state: SequenceHistoryMutationState,
    current: () -> SequenceHistoryMutationState,
    ownsRoute: () -> Boolean,
    visible: Boolean,
    dispatch: (SequenceHistoryMutationAction) -> Unit,
): SequenceHistoryAdmission {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val read by rememberUpdatedState(current)
    val owner by rememberUpdatedState(ownsRoute)
    val send by rememberUpdatedState(dispatch)
    var generation by remember { mutableStateOf(0L) }
    val admission =
        remember(SequencePublication(state), visible, lifecycle, generation) {
            SequenceHistoryAdmission(executionId, state, { read() }, {
                visible && owner() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }, { send(it) }) { generation++ }
        }
    DisposableEffect(admission) { onDispose { admission.attached = false } }
    DisposableEffect(lifecycle, admission) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) admission.revoke()
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(admission, ownsRoute) {
        // Navigation snapshot changes revoke authority before the next Compose frame.
        val observer = SnapshotStateObserver { it() }
        observer.start()
        observer.observeReads(admission, { it.revoke() }) { ownsRoute() }
        onDispose {
            observer.stop()
            observer.clear()
        }
    }
    return admission
}

private class SequencePublication(
    private val state: SequenceHistoryMutationState,
) {
    override fun equals(other: Any?): Boolean = other is SequencePublication && state === other.state

    override fun hashCode(): Int = System.identityHashCode(state)
}

@Composable
internal fun SequenceSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val available = enabled && (LocalSequenceHistoryAdmission.current?.isCurrent() != false)
    LifeTracingSecondaryButton(
        onClick = onClick,
        modifier = modifier.then(if (available) Modifier.historyActionSemantics(onClick) else Modifier),
        enabled = available,
        content = content,
    )
}

@Composable
internal fun SequencePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val available = enabled && (LocalSequenceHistoryAdmission.current?.isCurrent() != false)
    LifeTracingPrimaryButton(
        onClick = onClick,
        modifier = modifier.then(if (available) Modifier.historyActionSemantics(onClick) else Modifier),
        enabled = available,
        content = content,
    )
}
