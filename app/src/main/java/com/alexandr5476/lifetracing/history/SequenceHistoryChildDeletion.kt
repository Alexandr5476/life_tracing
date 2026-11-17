@file:Suppress("FunctionNaming", "LongMethod", "MaxLineLength")

package com.alexandr5476.lifetracing.history

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionCommand
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrence
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged
import com.alexandr5476.lifetracing.ui.theme.spacing

/** Selected canonical facts only; mutation facts never become displayed historical results. */
internal fun SequenceHistoryDetail.childDeletionOccurrence(
    proposal: SequenceChildHistoryDeletionCommand,
): SequenceHistoryOccurrence? =
    occurrences.singleOrNull { it.occurrenceId == proposal.occurrenceId }?.takeIf {
        isHistoricalSequence(root.executionId) &&
            updatedAt == proposal.expectedUpdatedAt &&
            it.canDeleteChild() &&
            it.childMutationFacts?.executionId == proposal.childExecutionId
    }

/** Equal-looking replacement proposals still own different native confirmation nodes. */
private class ChildDeletionProposalReference(
    private val proposal: SequenceChildHistoryDeletionCommand,
) {
    override fun equals(other: Any?): Boolean = other is ChildDeletionProposalReference && proposal === other.proposal

    override fun hashCode(): Int = System.identityHashCode(proposal)
}

@Composable
internal fun SequenceChildDeletionConfirmation(
    detail: SequenceHistoryDetail,
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
) {
    val proposal = requireNotNull(state.childDeletionProposal)
    val target = remember(SequenceDetailReference(detail), proposal) { detail.childDeletionOccurrence(proposal) }
    // A malformed publication cannot manufacture identity from the old display descriptor.
    if (target == null) {
        SequenceMutationIssue(SequenceHistoryMutationIssue.INVALID_PROPOSAL)
        return
    }
    key("child-deletion-confirmation", SequenceDetailReference(detail), ChildDeletionProposalReference(proposal)) {
        val lease = rememberSequenceHistoryNavigation(detail, hasTiming = false, hasIssue = state.issue != null)
        val enabled = lease.isCurrent()
        Text(
            stringResource(R.string.sequence_history_delete_child_title),
            modifier =
                deletionHeadingFocus(
                    lease,
                ).semantics { heading() }.testTag("sequence-child-deletion-heading").focusable(),
            style = MaterialTheme.typography.headlineSmall,
        )
        DeletionSummary("sequence-child-deletion-occurrence", lease) {
            Text(
                stringResource(
                    R.string.sequence_child_deletion_parent,
                    detail.root.title,
                    detail.root.executionId.value,
                ),
            )
            Text(stringResource(R.string.history_occurrence, target.runtimePosition + 1, target.activity.title))
            Text(stringResource(R.string.sequence_history_occurrence_id, target.occurrenceId.value))
            Text(stringResource(R.string.sequence_history_activity_snapshot_id, target.activitySnapshotId.value))
            target.sourceSequenceSnapshotNodeId?.let {
                Text(
                    stringResource(R.string.sequence_history_source_id, it.value),
                )
            }
            target.repeatSourceSnapshotNodeId?.let {
                Text(
                    stringResource(R.string.sequence_history_repeat_id, it.value),
                )
            }
            target.repeatIteration?.let { Text(stringResource(R.string.history_repeat_iteration, it)) }
            if (target.isRuntimeAdded) Text(stringResource(R.string.history_runtime_added))
        }
        DeletionSummary("sequence-child-deletion-child", lease) {
            Text(stringResource(R.string.sequence_child_deletion_child, proposal.childExecutionId.value))
        }
        DeletionSummary("sequence-child-deletion-consequences", lease) {
            Text(stringResource(R.string.sequence_history_delete_child_message, detail.root.title))
        }
        state.issue?.let { SequenceMutationIssue(it) }
        if (state.isMutating) {
            Text(stringResource(R.string.history_deleting), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        // Separate native nodes from the entry action and from every other review stage.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            SequenceSecondaryButton(
                onClick = { onAction(SequenceHistoryMutationAction.Cancel) },
                enabled = enabled && !state.isMutating,
                modifier = Modifier.testTag("sequence-child-deletion-cancel"),
            ) { Text(stringResource(R.string.manual_history_cancel)) }
            SequencePrimaryButton(
                onClick = { onAction(SequenceHistoryMutationAction.ConfirmChildDeletion) },
                enabled =
                    enabled &&
                        !state.isMutating &&
                        LocalSequenceHistoryAdmission.current?.enabled(
                            SequenceHistoryMutationAction.ConfirmChildDeletion,
                        ) !=
                        false,
                modifier = Modifier.testTag("sequence-history-confirm-delete-child"),
            ) {
                Text(
                    stringResource(
                        R.string.sequence_child_deletion_confirm,
                        target.activity.title,
                        proposal.childExecutionId.value,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DeletionSummary(
    tag: String,
    lease: SequenceHistoryNavigation,
    content: @Composable () -> Unit,
) {
    Column(
        historyFocusTarget(enabled = lease.isCurrent())
            .focusProperties { canFocus = lease.canFocus() }
            .semantics(mergeDescendants = true) { }
            .testTag(tag)
            .focusable(),
    ) { content() }
}

/** Heading landing borrows read-only S1 ownership and rechecks after the frame. */
@Composable
private fun deletionHeadingFocus(lease: SequenceHistoryNavigation): Modifier {
    val requester = remember { FocusRequester() }
    var attached by remember { mutableStateOf(false) }
    var landed by remember { mutableStateOf(false) }
    val window = LocalWindowInfo.current
    val mode = LocalInputModeManager.current.inputMode
    val focus = LocalHistoryFocus.current
    LaunchedEffect(lease, attached, window.isWindowFocused, mode) {
        val available = attached && window.isWindowFocused
        if (!landed && available && lease.isCurrent()) {
            withFrameNanos { }
            if (attached && window.isWindowFocused && lease.isCurrent()) {
                landed = requester.requestFocus()
                if (landed) {
                    focus.removed = null
                    focus.awaitingRead = false
                    focus.landingPending = false
                }
            }
        }
    }
    return historyFocusTarget(requester, lease.isCurrent())
        .onFocusTargetAttachmentChanged { attached = it }
        .focusProperties { canFocus = lease.canFocus() }
}
