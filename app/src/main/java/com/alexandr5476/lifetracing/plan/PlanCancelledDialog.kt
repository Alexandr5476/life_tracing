@file:Suppress("FunctionNaming", "LongMethod")

package com.alexandr5476.lifetracing.plan

import android.text.format.DateUtils
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged
import com.alexandr5476.lifetracing.ui.theme.spacing

/** Native keyboard ownership only; focus never carries command authority. */
private class CancelledFocus {
    val close = FocusRequester()
    var owner: Any? = null
    var removed by mutableStateOf(false)
}

@Composable
private fun cancelledFocusTarget(
    focus: CancelledFocus,
    enabled: Boolean = true,
): Modifier {
    val token = remember { Any() }
    LaunchedEffect(enabled) {
        if (!enabled && focus.owner === token) focus.removed = true
    }
    return Modifier
        .onFocusTargetAttachmentChanged {
            if (!it && focus.owner === token) focus.removed = true
        }.onFocusChanged {
            if (it.isFocused && !focus.removed) focus.owner = token
        }
}

@Composable
internal fun PlanCancelledDialog(
    state: PlanPresentationState,
    onAction: (PlanAction) -> Unit,
) {
    val authority = requireNotNull(state.cancelledContext)
    val focus = remember { CancelledFocus() }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val present: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides density,
        ) { LifeTracingAccessibleTargets(content) }
    }
    AlertDialog(
        onDismissRequest = { onAction(PlanAction.DismissCancelled(authority)) },
        title = { present { Text(stringResource(R.string.plan_cancelled), Modifier.semantics { heading() }) } },
        text = {
            present {
                val window = LocalWindowInfo.current
                val mode = LocalInputModeManager.current.inputMode
                var landing by remember { mutableStateOf(true) }
                val needsFocus = landing || focus.removed || state.isMutating
                LaunchedEffect(window.isWindowFocused, mode, landing, focus.removed, state.isMutating) {
                    if (window.isWindowFocused && needsFocus) {
                        withFrameNanos { }
                        focus.removed = false
                        focus.owner = null
                        landing = !focus.close.requestFocus()
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .focusGroup()
                        .semantics { testTag = "plan-cancelled-list" },
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    Text(stringResource(R.string.plan_cancelled_context))
                    Text(
                        cancelledStatus(state),
                        Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            testTag = "plan-cancelled-status"
                        },
                    )
                    state.mutationFailure?.let { Text(it.label(), Modifier.planFeedback()) }
                    state.recoveryFailure?.let { Text(it.label(), Modifier.planFeedback()) }
                    val descriptions = cancelledDescriptions(state.cancelledItems)
                    state.cancelledItems.forEachIndexed { index, row ->
                        key(row.plan.id) {
                            CancelledRow(row, descriptions[index], state.isMutating, authority, focus, onAction)
                        }
                    }
                    if (state.cancelled is PlanLoad.Failure ||
                        state.cancelledRetrying ||
                        state.recoveryFailure != null
                    ) {
                        TextButton(
                            enabled = state.cancelled !is PlanLoad.Loading,
                            modifier =
                                cancelledFocusTarget(focus, state.cancelled !is PlanLoad.Loading)
                                    .semantics { testTag = "plan-cancelled-retry" },
                            onClick = { onAction(PlanAction.RetryCancelled(authority, state.cancelledOffset)) },
                        ) { Text(stringResource(R.string.plan_retry)) }
                    }
                    if (state.cancelledHasNextPage && state.cancelled !is PlanLoad.Failure) {
                        TextButton(
                            enabled = !state.isMutating && state.cancelled !is PlanLoad.Loading,
                            modifier =
                                cancelledFocusTarget(
                                    focus,
                                    !state.isMutating && state.cancelled !is PlanLoad.Loading,
                                ).semantics { testTag = "plan-cancelled-more" },
                            onClick = { onAction(PlanAction.LoadMoreCancelled(authority, state.cancelledOffset)) },
                        ) { Text(stringResource(R.string.plan_load_more)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            present {
                TextButton(
                    modifier =
                        cancelledFocusTarget(focus)
                            .focusRequester(focus.close)
                            .semantics { testTag = "plan-cancelled-close" },
                    onClick = { onAction(PlanAction.DismissCancelled(authority)) },
                ) { Text(stringResource(R.string.plan_close)) }
            }
        },
    )
}

@Composable
@Suppress("LongParameterList")
private fun CancelledRow(
    row: PlanReadRow,
    description: String,
    mutating: Boolean,
    authority: PlanCancelledContext,
    focus: CancelledFocus,
    onAction: (PlanAction) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().semantics {
            isTraversalGroup = true
            testTag =
                "plan-cancelled-row-${row.plan.id.value}"
        },
    ) {
        Column(
            Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            Text(row.actionTarget().kindDescription())
            Text(row.actionTarget().scheduleDescription())
            Text(stringResource(R.string.daily_plan_cancelled))
            Text(row.sourceLabel())
            row.shortComment?.let { Text(it) }
            row.activityMetadata?.let { metadata ->
                Text(
                    stringResource(
                        if (metadata.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                            R.string.plan_execution_no_live
                        } else {
                            R.string.plan_execution_timed
                        },
                    ),
                )
                metadata.timerTarget?.let {
                    Text(stringResource(R.string.plan_timer_target, DateUtils.formatElapsedTime(it.seconds)))
                }
            }
            if (row.plan.status == PlanEntryStatus.CANCELLED) {
                TextButton(
                    enabled = !mutating,
                    modifier =
                        cancelledFocusTarget(focus).semantics {
                            testTag = "plan-restore-${row.plan.id.value}"
                            contentDescription = description
                        },
                    onClick = { onAction(PlanAction.Restore(row, authority)) },
                ) { Text(stringResource(R.string.plan_restore)) }
            }
        }
    }
}

/** Linear passes over only the loaded lightweight summaries. */
@Composable
private fun cancelledDescriptions(rows: List<PlanReadRow>): List<String> =
    disambiguatePlanDescriptions(
        rows.map { row ->
            stringResource(
                R.string.plan_cancelled_action,
                stringResource(R.string.plan_restore),
                row.title,
                row.actionTarget().kindDescription(),
                row.actionTarget().scheduleDescription(),
                stringResource(R.string.daily_plan_cancelled),
                row.sourceLabel(),
                row.shortComment.orEmpty(),
            )
        },
    )

@Composable
private fun cancelledStatus(state: PlanPresentationState): String =
    stringResource(
        when {
            state.isMutating -> R.string.plan_lifecycle_pending
            state.cancelled is PlanLoad.Loading && state.cancelledAppending -> R.string.plan_cancelled_appending
            state.cancelled == null || state.cancelled is PlanLoad.Loading -> R.string.plan_cancelled_loading
            state.cancelled is PlanLoad.Failure && state.cancelledAppending -> R.string.plan_cancelled_append_failed
            state.cancelled is PlanLoad.Failure -> R.string.plan_cancelled_failed
            state.cancelledItems.isEmpty() -> R.string.plan_cancelled_empty
            else -> R.string.plan_cancelled_results
        },
        state.cancelledItems.size,
    )
