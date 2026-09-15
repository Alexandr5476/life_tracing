@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.plan

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOption
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.launcher.formatLauncherCountdown
import com.alexandr5476.lifetracing.ui.components.LifeTracingLinearProgressIndicator
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
internal fun PlanExecutionRoute(
    session: PlanExecutionRouteSession,
    onBack: () -> Unit,
    onReloadOrigin: () -> Unit,
    onCommitted: (PlanExecutionCommit) -> Unit,
) {
    val state by session.controller.state.collectAsState()
    val exit = {
        if (state.command == PlanExecutionCommandState.Stale) {
            onReloadOrigin()
        } else {
            session.exitPolicy.requestExit(session.controller, onBack, onCommitted)
        }
    }
    BackHandler(enabled = true, onBack = exit)
    LaunchedEffect(state.command) { session.exitPolicy.onCommand(state.command, onCommitted) }
    val prepared = (state.prepared as? PlanExecutionLoad.Content)?.value
    LaunchedEffect(prepared) {
        val activity = (prepared?.action?.snapshot as? FocusedPlanAction.Snapshot.Activity)?.value
        if (prepared?.isLive == false && activity != null) session.prepareQuickDraft(activity)
    }
    PlanExecutionScreen(state, session, exit, onReloadOrigin)
}

@Composable
internal fun PlanExecutionScreen(
    state: PlanExecutionState,
    session: PlanExecutionRouteSession,
    onBack: () -> Unit,
    onReloadOrigin: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.plan_execution_title), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text(stringResource(R.string.plan_back)) }
            }
            when (val prepared = state.prepared) {
                PlanExecutionLoad.Loading -> Text(stringResource(R.string.plan_execution_loading))
                is PlanExecutionLoad.Failure ->
                    FailureCard(R.string.plan_execution_load_failed) {
                        session.controller.retryPreparation()
                    }
                is PlanExecutionLoad.Content -> PreparedContent(prepared.value, state.command, session)
            }
            CommandContent(state.command, session.controller::retryPreparation, onBack, onReloadOrigin)
        }
    }
}

@Composable
private fun PreparedContent(
    prepared: PreparedPlanExecution,
    command: PlanExecutionCommandState,
    session: PlanExecutionRouteSession,
) {
    val idle = command == PlanExecutionCommandState.Idle
    when (val snapshot = prepared.action.snapshot) {
        is FocusedPlanAction.Snapshot.Activity -> {
            Text(snapshot.value.name, style = MaterialTheme.typography.titleLarge)
            snapshot.value.shortComment?.let { Text(it) }
            if (!prepared.isLive) {
                NoLiveEditor(snapshot.value, session, idle)
            } else {
                LifeTracingPrimaryButton(
                    enabled = idle,
                    onClick = session.controller::launch,
                    modifier = Modifier.testTag("plan-execution-submit"),
                ) {
                    Text(stringResource(R.string.plan_start_action))
                }
            }
        }
        is FocusedPlanAction.Snapshot.Sequence -> {
            Text(snapshot.value.name, style = MaterialTheme.typography.titleLarge)
            snapshot.value.shortComment?.let { Text(it) }
            LifeTracingPrimaryButton(
                enabled = idle,
                onClick = session.controller::launch,
                modifier = Modifier.testTag("plan-execution-submit"),
            ) {
                Text(stringResource(R.string.plan_start_action))
            }
        }
    }
}

@Composable
private fun NoLiveEditor(
    snapshot: ActivityConfigSnapshot,
    session: PlanExecutionRouteSession,
    enabled: Boolean,
) {
    val draft = session.quickDraft ?: return
    snapshot.fields
        .sortedWith(compareByDescending<ActivitySnapshotField> { it.isMainValue }.thenBy { it.position })
        .forEach { field ->
            when (field.type) {
                CustomFieldType.NUMBER ->
                    LifeTracingOutlinedTextField(
                        value = draft.numberTexts[field.id].orEmpty(),
                        onValueChange = { session.editNumber(field, it) },
                        modifier = Modifier.fillMaxWidth().testTag("plan-value-${field.id.value}"),
                        label = { Text(field.label()) },
                        isError = field.id in draft.invalid,
                        enabled = enabled,
                    )
                CustomFieldType.TEXT ->
                    LifeTracingOutlinedTextField(
                        value = (draft.values[field.id] as? TextExecutionValue)?.value.orEmpty(),
                        onValueChange = { session.editText(field, it) },
                        modifier = Modifier.fillMaxWidth().testTag("plan-value-${field.id.value}"),
                        label = { Text(field.label()) },
                        enabled = enabled,
                    )
                CustomFieldType.CATEGORY -> CategoryEditor(field, draft.values[field.id], enabled, session)
            }
            TextButton(
                onClick = { session.markMissing(field) },
                enabled = enabled,
                modifier = Modifier.testTag("plan-missing-${field.id.value}"),
            ) { Text(stringResource(R.string.plan_execution_set_missing)) }
        }
    LifeTracingPrimaryButton(
        enabled = enabled && draft.invalid.isEmpty(),
        onClick = { session.controller.launch(draft.overrides(snapshot.fields)) },
        modifier = Modifier.testTag("plan-execution-submit"),
    ) { Text(stringResource(R.string.plan_complete_action)) }
}

@Composable
private fun CategoryEditor(
    field: ActivitySnapshotField,
    value: ActivityExecutionFieldValue?,
    enabled: Boolean,
    session: PlanExecutionRouteSession,
) {
    var open by remember(field.id) { mutableStateOf(false) }
    val selected = (value as? CategoryExecutionValue)?.optionId
    Box {
        LifeTracingSecondaryButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.testTag("plan-value-${field.id.value}"),
        ) {
            Text(
                field.categoryOptions.singleOrNull { it.id == selected }?.label()
                    ?: stringResource(R.string.plan_execution_missing),
            )
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            field.categoryOptions.sortedBy { it.position }.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        open = false
                        session.editCategory(field, option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun CommandContent(
    command: PlanExecutionCommandState,
    retry: () -> Unit,
    cancel: () -> Unit,
    reload: () -> Unit,
) {
    when (command) {
        PlanExecutionCommandState.Idle -> Unit
        is PlanExecutionCommandState.Checking -> MessageCard(R.string.plan_execution_checking)
        is PlanExecutionCommandState.Preflight -> PreflightCard(command, cancel)
        is PlanExecutionCommandState.Committing -> MessageCard(R.string.plan_execution_committing)
        PlanExecutionCommandState.Stale ->
            FailureCard(R.string.plan_execution_stale, R.string.plan_execution_reload, reload)
        is PlanExecutionCommandState.Conflict -> FailureCard(R.string.plan_execution_conflict, action = retry)
        is PlanExecutionCommandState.Rejected -> FailureCard(R.string.plan_execution_rejected, action = retry)
        is PlanExecutionCommandState.Committed -> MessageCard(R.string.plan_execution_committed)
        is PlanExecutionCommandState.CommittedCoordinationFailure ->
            MessageCard(R.string.plan_execution_committed_coordination)
    }
}

@Composable
private fun PreflightCard(
    state: PlanExecutionCommandState.Preflight,
    cancel: () -> Unit,
) {
    var elapsed by remember(state.attemptId) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val elapsedStart = remember(state.attemptId) { elapsed }
    val wallStart = remember(state.attemptId) { Instant.now() }
    LaunchedEffect(state.attemptId) {
        while (true) {
            delay(250)
            elapsed = SystemClock.elapsedRealtime()
        }
    }
    val remaining =
        Duration
            .between(wallStart.plusMillis(elapsed - elapsedStart), state.endsAt)
            .coerceAtLeast(Duration.ZERO)
    val progress = 1f - remaining.toMillis().toFloat() / state.duration.toMillis().coerceAtLeast(1L)
    MessageCard(R.string.plan_execution_countdown, formatLauncherCountdown(remaining)) {
        LifeTracingLinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) })
        LifeTracingSecondaryButton(onClick = cancel) { Text(stringResource(R.string.plan_execution_cancel)) }
    }
}

@Composable
private fun FailureCard(
    message: Int,
    actionLabel: Int = R.string.plan_execution_retry,
    action: () -> Unit,
) = MessageCard(message) {
    LifeTracingSecondaryButton(onClick = action) { Text(stringResource(actionLabel)) }
}

@Composable
private fun MessageCard(
    message: Int,
    vararg formatArgs: Any,
    content: @Composable () -> Unit = {},
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(stringResource(message, *formatArgs))
            content()
        }
    }
}

private fun ActivitySnapshotField.label(): String =
    listOfNotNull(localNameOverride ?: nameAtCreation, unit).joinToString(" ")

private fun ActivitySnapshotCategoryOption.label(): String = localLabelOverride ?: labelAtCreation
