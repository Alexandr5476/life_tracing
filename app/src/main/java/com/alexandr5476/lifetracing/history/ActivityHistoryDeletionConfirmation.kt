@file:Suppress("FunctionNaming", "LongMethod")

package com.alexandr5476.lifetracing.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.ui.theme.spacing

@Composable
internal fun ActivityDeletionButton(
    detail: ActivityHistoryDetail,
    state: ActivityHistoryMutationState,
    requester: FocusRequester,
) {
    val focus = LocalHistoryFocus.current
    DisposableEffect(requester) {
        focus.recoveryTarget = requester
        onDispose { if (focus.recoveryTarget === requester) focus.recoveryTarget = null }
    }
    CorrectionButton(
        ActivityHistoryMutationAction.RequestDelete,
        stringResource(R.string.history_delete),
        "history-delete",
        state.canRequestActivityDeletion(detail.root.executionId),
        description = stringResource(R.string.history_delete_target_action, deletionTarget(detail)),
        requester = requester,
    )
}

@Composable
internal fun ActivityDeletionConfirmation(
    detail: ActivityHistoryDetail,
    state: ActivityHistoryMutationState,
) {
    val cancel = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val admission by rememberUpdatedState(LocalActivityCorrectionAdmission.current)
    val current by rememberUpdatedState(state)
    val window = LocalWindowInfo.current
    val focus = LocalHistoryFocus.current
    val failed = state.issue == ActivityHistoryMutationIssue.DELETE_FAILURE
    val recovery = if (failed) retry else cancel
    DisposableEffect(recovery) {
        focus.recoveryTarget = recovery
        onDispose { if (focus.recoveryTarget === recovery) focus.recoveryTarget = null }
    }
    LaunchedEffect(failed, window.isWindowFocused) {
        withFrameNanos { }
        if (admission.canFocus() && window.isWindowFocused && !current.isMutating) recovery.requestFocus()
    }
    Text(
        stringResource(R.string.history_delete_title),
        Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
    )
    val target = deletionTarget(detail)
    val message = stringResource(R.string.history_delete_message, target)
    val template = stringResource(R.string.history_delete_template_preserved)
    val plan = if (detail.root.planEntryId != null) stringResource(R.string.history_delete_plan_preserved) else ""
    Text(
        listOf(message, template, plan).filter(String::isNotEmpty).joinToString("\n"),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    state.issue?.let { MutationIssue(it) }
    if (state.isMutating) {
        Text(stringResource(R.string.history_deleting), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        CorrectionButton(
            ActivityHistoryMutationAction.Cancel,
            stringResource(R.string.manual_history_cancel),
            "history-delete-cancel",
            !state.isMutating,
            requester = cancel,
        )
        CorrectionButton(
            ActivityHistoryMutationAction.ConfirmDelete,
            stringResource(if (failed) R.string.history_delete_retry else R.string.history_delete),
            "history-delete-confirm",
            state.canConfirmActivityDeletion(detail.root.executionId),
            primary = true,
            description = stringResource(R.string.history_delete_confirm_action, target),
            requester = retry,
        )
    }
}

@Composable
private fun deletionTarget(detail: ActivityHistoryDetail): String =
    historyRootDescription(detail.root, stringResource(R.string.history_activity_record, detail.root.executionId.value))

/** Cancellation restores the current execution's Delete; stale recovery uses current navigation. */
@Composable
internal fun ActivityDeletionReturnFocus(
    state: ActivityHistoryMutationState,
    delete: FocusRequester,
) {
    var wasConfirming by remember { mutableStateOf(false) }
    val admission by rememberUpdatedState(LocalActivityCorrectionAdmission.current)
    val current by rememberUpdatedState(state)
    val focus = LocalHistoryFocus.current
    val window = LocalWindowInfo.current
    LaunchedEffect(state.deleteConfirmation) {
        if (state.deleteConfirmation) {
            wasConfirming = true
        } else if (wasConfirming) {
            wasConfirming = false
            withFrameNanos { }
            if (admission.canFocus() && window.isWindowFocused && !current.isMutating) {
                val detail = (current.load as? HistoryDetailLoad.Content)?.value
                if (detail != null && current.canRequestActivityDeletion(detail.root.executionId)) {
                    delete.requestFocus()
                } else {
                    focus.back.requestFocus()
                }
            }
        }
    }
}
