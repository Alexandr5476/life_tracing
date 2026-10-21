@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.daily

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyActiveSequenceState
import com.alexandr5476.lifetracing.domain.DailyPlan
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.plan.PlanExecutionActionTarget
import com.alexandr5476.lifetracing.plan.planExecutionActionDescriptions
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.historicalTimestamp
import com.alexandr5476.lifetracing.ui.historicalTimestampRange
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DailyRoute(
    controller: DailyController,
    onStartActivity: () -> Unit = {},
    onLibrary: () -> Unit = {},
    onPlan: () -> Unit = {},
    onHistory: () -> Unit = {},
    onStatistics: () -> Unit = {},
    onSettings: () -> Unit = {},
    onExpandSequence: (SequenceExecutionId) -> Unit = {},
    onExecutePlan: (PlanActionIdentity) -> Unit = {},
    isRouteVisible: Boolean = true,
) {
    var entered by remember(controller) { mutableStateOf(false) }
    DisposableEffect(controller) {
        controller.onRouteEntered()
        entered = true
        onDispose { controller.dispatch(DailyAction.Hidden) }
    }
    val state by controller.state.collectAsState()
    DailyScreen(
        state = if (entered) state else state.copy(load = DailyLoadState.Loading),
        onAction = controller::dispatch,
        onStartActivity = onStartActivity,
        onLibrary = onLibrary,
        onPlan = onPlan,
        onHistory = onHistory,
        onStatistics = onStatistics,
        onSettings = onSettings,
        onExpandSequence = onExpandSequence,
        onExecutePlan = onExecutePlan,
        isRouteVisible = isRouteVisible,
    )
}

@Composable
internal fun DailyScreen(
    state: DailyPresentationState,
    onAction: (DailyAction) -> Unit,
    displayElapsedRealtimeMs: Long? = null,
    onStartActivity: () -> Unit = {},
    onLibrary: () -> Unit = {},
    onPlan: () -> Unit = {},
    onHistory: () -> Unit = {},
    onStatistics: () -> Unit = {},
    onSettings: () -> Unit = {},
    onExpandSequence: (SequenceExecutionId) -> Unit = {},
    onExecutePlan: (PlanActionIdentity) -> Unit = {},
    isRouteVisible: Boolean = true,
) {
    val scrollState = rememberScrollState()
    val canonical =
        when (val load = state.load) {
            is DailyLoadState.Content -> load.daily
            is DailyLoadState.Empty -> load.daily
            else -> null
        }
    val focusRecovery = rememberDailyFocusRecovery(state.selectedDate, isRouteVisible, canonical)
    LifeTracingAccessibleTargets {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .semantics { isTraversalGroup = true }
                        .verticalScroll(scrollState)
                        .padding(MaterialTheme.spacing.xLarge),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
            ) {
                Column(
                    focusRecovery.surviving,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
                ) {
                    DateHeader(
                        state.selectedDate,
                        state.dateRelation,
                        onAction,
                        onStartActivity,
                        onLibrary,
                        onPlan,
                        onHistory,
                        onStatistics,
                        onSettings,
                        focusRecovery.landing,
                    )
                }
                CommandFailure(state.commandFailure)
                when (val load = state.load) {
                    DailyLoadState.Loading -> LoadingContent()
                    is DailyLoadState.Failure -> Column(focusRecovery.surviving) { FailureContent(onAction) }
                    is DailyLoadState.Empty ->
                        DailyContent(
                            state,
                            load.daily,
                            onAction,
                            displayElapsedRealtimeMs,
                            onExpandSequence,
                            onExecutePlan,
                            focusRecovery.runtime,
                            focusRecovery.surviving,
                        )
                    is DailyLoadState.Content ->
                        DailyContent(
                            state,
                            load.daily,
                            onAction,
                            displayElapsedRealtimeMs,
                            onExpandSequence,
                            onExecutePlan,
                            focusRecovery.runtime,
                            focusRecovery.surviving,
                        )
                }
            }
        }
    }
}

@Composable
private fun DateHeader(
    selectedDate: LocalDate,
    relation: DailyDateRelation,
    onAction: (DailyAction) -> Unit,
    onStartActivity: () -> Unit,
    onLibrary: () -> Unit,
    onPlan: () -> Unit,
    onHistory: () -> Unit,
    onStatistics: () -> Unit,
    onSettings: () -> Unit,
    landingFocus: FocusRequester,
) {
    val previous = stringResource(R.string.daily_previous_day)
    val next = stringResource(R.string.daily_next_day)
    Row(
        modifier = Modifier.fillMaxWidth().semantics { isTraversalGroup = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        TextButton(
            onClick = { onAction(DailyAction.PreviousDay) },
            modifier =
                Modifier.focusRequester(landingFocus).semantics {
                    contentDescription = previous
                    traversalIndex = 1f
                },
        ) { Text("\u2039", modifier = Modifier.clearAndSetSemantics {}) }
        Column(
            modifier =
                Modifier.weight(1f).semantics {
                    isTraversalGroup = true
                    traversalIndex = 0f
                },
        ) {
            Text(
                text = stringResource(R.string.daily_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() },
            )
            val dateContext =
                stringResource(
                    when (relation) {
                        DailyDateRelation.TODAY -> R.string.daily_date_today
                        DailyDateRelation.PAST -> R.string.daily_date_past
                        DailyDateRelation.FUTURE -> R.string.daily_date_future
                    },
                    localizedDate(selectedDate),
                )
            Text(
                text = localizedDate(selectedDate),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { contentDescription = dateContext },
            )
        }
        TextButton(
            onClick = { onAction(DailyAction.NextDay) },
            modifier =
                Modifier.semantics {
                    contentDescription = next
                    traversalIndex = 2f
                },
        ) { Text("\u203A", modifier = Modifier.clearAndSetSemantics {}) }
    }
    if (relation != DailyDateRelation.TODAY) {
        TextButton(onClick = { onAction(DailyAction.Today) }) {
            Text(stringResource(R.string.daily_return_today))
        }
    }
    if (relation == DailyDateRelation.TODAY) {
        LifeTracingPrimaryButton(onClick = onStartActivity) {
            Text(stringResource(R.string.daily_start_activity))
        }
    }
    FlowRow(
        modifier = Modifier.testTag("daily-shell-actions"),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        LifeTracingSecondaryButton(onClick = onLibrary) {
            Text(stringResource(R.string.daily_library))
        }
        LifeTracingSecondaryButton(onClick = onPlan) {
            Text(stringResource(R.string.daily_plan))
        }
        LifeTracingSecondaryButton(onClick = onHistory) {
            Text(stringResource(R.string.daily_history))
        }
        LifeTracingSecondaryButton(onClick = onStatistics) {
            Text(stringResource(R.string.daily_statistics))
        }
        LifeTracingSecondaryButton(onClick = onSettings, modifier = Modifier.testTag("daily-settings")) {
            Text(stringResource(R.string.settings_title))
        }
    }
}

@Composable
private fun LoadingContent() {
    DailyCard {
        Text(stringResource(R.string.daily_loading), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.daily_loading_detail),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FailureContent(onAction: (DailyAction) -> Unit) {
    DailyCard(container = MaterialTheme.colorScheme.errorContainer) {
        Text(
            stringResource(R.string.daily_read_failure),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.daily_read_failure_detail),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
        LifeTracingPrimaryButton(onClick = { onAction(DailyAction.Retry) }) {
            Text(stringResource(R.string.daily_retry))
        }
    }
}

@Composable
private fun CommandFailure(failure: DailyCommandFailure?) {
    if (failure == null) return
    val message =
        when (failure) {
            is DailyCommandFailure.Rejected -> stringResource(R.string.daily_command_rejected)
            is DailyCommandFailure.Coordination -> stringResource(R.string.daily_command_coordinating)
        }
    DailyCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            message,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DailyContent(
    state: DailyPresentationState,
    daily: com.alexandr5476.lifetracing.domain.DailyRead,
    onAction: (DailyAction) -> Unit,
    displayElapsedRealtimeMs: Long?,
    onExpandSequence: (SequenceExecutionId) -> Unit,
    onExecutePlan: (PlanActionIdentity) -> Unit,
    activeFocusModifier: Modifier,
    survivingFocusModifier: Modifier,
) {
    val plans = daily.dayPlans + daily.weekPlans
    val active: @Composable () -> Unit = {
        if (state.dateRelation == DailyDateRelation.TODAY) {
            daily.active?.let {
                Column(activeFocusModifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large)) {
                    ActiveSection(it, state, onAction, displayElapsedRealtimeMs, onExpandSequence)
                }
            }
        }
    }
    val planned: @Composable () -> Unit = {
        Column(survivingFocusModifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large)) {
            PlannedSection(plans, state.dateRelation, onExecutePlan)
        }
    }
    val completed: @Composable () -> Unit = { CompletedSection(daily.completedHistory) }
    when (state.dateRelation) {
        DailyDateRelation.PAST -> {
            completed()
            planned()
        }
        DailyDateRelation.TODAY -> {
            active()
            planned()
            completed()
        }
        DailyDateRelation.FUTURE -> {
            planned()
            completed()
        }
    }
}

@Composable
private fun ActiveSection(
    active: DailyActive,
    state: DailyPresentationState,
    onAction: (DailyAction) -> Unit,
    displayElapsedRealtimeMs: Long?,
    onExpandSequence: (SequenceExecutionId) -> Unit,
) {
    SectionTitle(R.string.daily_active)
    when (active) {
        is DailyActive.Activity -> ActiveActivityCard(active, state, onAction, displayElapsedRealtimeMs)
        is DailyActive.Sequence ->
            ActiveSequenceCard(active, state, onAction, displayElapsedRealtimeMs, onExpandSequence)
    }
}

@Composable
private fun ActiveActivityCard(
    active: DailyActive.Activity,
    state: DailyPresentationState,
    onAction: (DailyAction) -> Unit,
    displayElapsedRealtimeMs: Long?,
) {
    val runtime = active.runtime
    val paused = runtime.session.state == ActiveSessionState.PAUSED
    DailyCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            runtime.snapshot.name,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
        runtime.snapshot.shortComment?.let {
            Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            stringResource(if (paused) R.string.daily_paused else R.string.daily_running),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelLarge,
        )
        LiveActivityValue(
            runtime.snapshot,
            state.runtimeDisplayBaseline,
            displayElapsedRealtimeMs,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            LifeTracingSecondaryButton(
                onClick = { onAction(if (paused) DailyAction.ResumeActivity else DailyAction.PauseActivity) },
                enabled = !state.commandInFlight,
            ) { Text(stringResource(if (paused) R.string.daily_resume else R.string.daily_pause)) }
            LifeTracingPrimaryButton(
                onClick = { onAction(DailyAction.FinishActivity) },
                enabled = !state.commandInFlight,
            ) { Text(stringResource(R.string.daily_finish)) }
        }
    }
}

@Composable
private fun LiveActivityValue(
    snapshot: com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot,
    baseline: com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline?,
    displayElapsedRealtimeMs: Long?,
) {
    val elapsedNow = displayTick(baseline, displayElapsedRealtimeMs)
    val value =
        when {
            baseline == null -> stringResource(R.string.daily_timing_unavailable)
            snapshot.timeTrackingMode == TimeTrackingMode.STOPWATCH ->
                stopwatchText(baseline.activeElapsed(elapsedNow), snapshot.settings.showSeconds)
            else -> timerText(baseline, elapsedNow)
        }
    val description = activityTimingDescription(snapshot, baseline, elapsedNow, currentStep = false)
    Text(
        text = value,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun ActiveSequenceCard(
    active: DailyActive.Sequence,
    state: DailyPresentationState,
    onAction: (DailyAction) -> Unit,
    displayElapsedRealtimeMs: Long?,
    onExpandSequence: (SequenceExecutionId) -> Unit,
) {
    val elapsedNow = displayTick(state.runtimeDisplayBaseline, displayElapsedRealtimeMs)
    val runtime = active.runtime
    DailyCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            runtime.snapshot.name,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
        runtime.snapshot.shortComment?.let {
            Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            stringResource(
                when (active.state) {
                    DailyActiveSequenceState.RUNNING_CURRENT -> R.string.daily_sequence_running_current
                    DailyActiveSequenceState.PAUSED_CURRENT -> R.string.daily_sequence_paused_current
                    DailyActiveSequenceState.WAITING_NEXT -> R.string.daily_waiting
                    DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN -> R.string.daily_sequence_running_transition
                    DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN -> R.string.daily_sequence_paused_transition
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        val totalDescription =
            stringResource(
                R.string.daily_sequence_total,
                state.runtimeDisplayBaseline?.activeElapsed(elapsedNow)?.let { spokenDuration(it) }
                    ?: stringResource(R.string.daily_timing_unavailable),
            )
        Text(
            modifier = Modifier.semantics { contentDescription = totalDescription },
            text =
                state.runtimeDisplayBaseline
                    ?.activeElapsed(elapsedNow)
                    ?.let(::durationText)
                    ?.let { stringResource(R.string.daily_sequence_total, it) }
                    ?: stringResource(R.string.daily_timing_unavailable),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
        when (active.state) {
            DailyActiveSequenceState.RUNNING_CURRENT,
            DailyActiveSequenceState.PAUSED_CURRENT,
            -> CurrentSequenceContent(active, state.runtimeDisplayBaseline, elapsedNow)
            DailyActiveSequenceState.WAITING_NEXT -> {
                NextStep(active)
            }
            DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN,
            DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
            -> TransitionSequenceContent(active, state.runtimeDisplayBaseline, elapsedNow)
        }
        SequenceControls(active, state.commandInFlight, onAction)
        LifeTracingSecondaryButton(
            onClick = { onExpandSequence(active.runtime.execution.id) },
            enabled = !state.commandInFlight,
            modifier = Modifier.testTag("daily-expand-sequence"),
        ) { Text(stringResource(R.string.daily_expand_sequence)) }
    }
}

@Composable
private fun CurrentSequenceContent(
    active: DailyActive.Sequence,
    baseline: com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline?,
    elapsedNow: Long,
) {
    val current = active.current
    if (current == null) {
        Text(stringResource(R.string.daily_timing_unavailable), style = MaterialTheme.typography.headlineSmall)
        NextStep(active)
        return
    }
    val currentDescription =
        stringResource(
            R.string.daily_current_occurrence,
            current.occurrence.runtimePosition.toLong() + 1,
            current.activity.name,
        )
    Text(
        current.activity.name,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { contentDescription = currentDescription },
    )
    current.activity.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    val value =
        when {
            current.activity.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING ->
                current.activity.mainValueText() ?: stringResource(R.string.daily_no_live_current_step)
            baseline == null -> stringResource(R.string.daily_timing_unavailable)
            current.activity.timeTrackingMode == TimeTrackingMode.STOPWATCH ->
                baseline.currentStepStopwatchElapsed(elapsedNow)?.let {
                    stopwatchText(it, current.activity.settings.showSeconds)
                }
                    ?: stringResource(R.string.daily_timing_unavailable)
            active.runtime.currentChild == null -> stringResource(R.string.daily_timing_unavailable)
            else -> timerText(baseline, elapsedNow)
        }
    val description =
        activityTimingDescription(
            current.activity,
            baseline.takeIf {
                current.activity.timeTrackingMode != TimeTrackingMode.TIMER || active.runtime.currentChild != null
            },
            elapsedNow,
            currentStep = true,
        )
    Text(
        value,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { contentDescription = description },
    )
    NextStep(active)
}

@Composable
private fun TransitionSequenceContent(
    active: DailyActive.Sequence,
    baseline: com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline?,
    elapsedNow: Long,
) {
    Text(stringResource(R.string.daily_transition_countdown), style = MaterialTheme.typography.labelLarge)
    val description =
        stringResource(
            R.string.daily_countdown_remaining,
            baseline?.transitionCountdownRemaining(elapsedNow)?.let { spokenDuration(it) }
                ?: stringResource(R.string.daily_timing_unavailable),
        )
    Text(
        baseline?.transitionCountdownRemaining(elapsedNow)?.let(::durationText)
            ?: stringResource(R.string.daily_timing_unavailable),
        modifier = Modifier.semantics { contentDescription = description },
        style = MaterialTheme.typography.headlineSmall,
    )
    NextStep(active)
}

@Composable
private fun NextStep(active: DailyActive.Sequence) {
    active.next?.let {
        Text(
            stringResource(R.string.daily_next_step, it.activity.name),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SequenceControls(
    active: DailyActive.Sequence,
    commandInFlight: Boolean,
    onAction: (DailyAction) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        when (active.state) {
            DailyActiveSequenceState.RUNNING_CURRENT -> {
                LifeTracingSecondaryButton(
                    onClick = { onAction(DailyAction.PauseSequence) },
                    enabled = !commandInFlight,
                ) { Text(stringResource(R.string.daily_pause)) }
                active.current?.let { current ->
                    LifeTracingPrimaryButton(
                        onClick = {
                            onAction(DailyAction.CompleteCurrentSequenceStep(current.occurrence.id))
                        },
                        enabled = !commandInFlight,
                    ) { Text(stringResource(R.string.daily_complete_step)) }
                }
            }
            DailyActiveSequenceState.PAUSED_CURRENT,
            DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
            ->
                LifeTracingPrimaryButton(
                    onClick = { onAction(DailyAction.ResumeSequence) },
                    enabled = !commandInFlight,
                ) { Text(stringResource(R.string.daily_resume)) }
            DailyActiveSequenceState.WAITING_NEXT ->
                LifeTracingPrimaryButton(
                    onClick = { onAction(DailyAction.StartNextSequenceStep) },
                    enabled = !commandInFlight,
                ) { Text(stringResource(R.string.daily_start_next)) }
            DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN ->
                LifeTracingSecondaryButton(
                    onClick = { onAction(DailyAction.PauseSequence) },
                    enabled = !commandInFlight,
                ) { Text(stringResource(R.string.daily_pause)) }
        }
    }
}

@Composable
private fun PlannedSection(
    plans: List<DailyPlan>,
    relation: DailyDateRelation,
    onExecute: (PlanActionIdentity) -> Unit,
) {
    SectionTitle(R.string.daily_planned)
    if (plans.isEmpty()) {
        EmptySection(R.string.daily_no_plans)
    } else {
        val executable =
            plans.filter {
                it.plan.status == PlanEntryStatus.PLANNED && !it.engaged && it.plan.target !is PlanTarget.Month
            }
        val descriptions =
            planExecutionActionDescriptions(
                executable.map {
                    PlanExecutionActionTarget(
                        it.snapshot.title,
                        it.plan.kind,
                        it.plan.target,
                        (it.snapshot as? com.alexandr5476.lifetracing.domain.DailyPlanSnapshot.Activity)
                            ?.value
                            ?.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING,
                        it.effectiveLocalDate,
                        it.exactLocalTime,
                        it.exactUtcOffset,
                    )
                },
            )
        val descriptionById = executable.mapIndexed { index, plan -> plan.plan.id to descriptions[index] }.toMap()
        plans.forEach { PlannedRow(it, relation, onExecute, descriptionById[it.plan.id]) }
    }
}

@Composable
private fun PlannedRow(
    plan: DailyPlan,
    relation: DailyDateRelation,
    onExecute: (PlanActionIdentity) -> Unit,
    executionDescription: String?,
) {
    DailyCard(
        container =
            if (relation == DailyDateRelation.TODAY && plan.plan.status == PlanEntryStatus.PLANNED) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
    ) {
        Text(plan.snapshot.title, style = MaterialTheme.typography.titleMedium)
        plan.snapshot.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            planContext(plan),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        planStatus(plan).forEach { Text(it, style = MaterialTheme.typography.labelLarge) }
        if (plan.plan.status == PlanEntryStatus.PLANNED && !plan.engaged && plan.plan.target !is PlanTarget.Month) {
            requireNotNull(executionDescription)
            LifeTracingPrimaryButton(
                onClick = { onExecute(plan.plan.actionIdentity()) },
                modifier =
                    Modifier
                        .semantics { contentDescription = executionDescription }
                        .testTag("daily-plan-action-${plan.plan.id.value}"),
            ) {
                Text(
                    stringResource(
                        if ((plan.snapshot as? com.alexandr5476.lifetracing.domain.DailyPlanSnapshot.Activity)
                                ?.value
                                ?.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING
                        ) {
                            R.string.plan_complete_action
                        } else {
                            R.string.plan_start_action
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun planContext(plan: DailyPlan): String =
    when (val target = plan.plan.target) {
        is PlanTarget.ExactDay ->
            plan.exactLocalTime?.let { time ->
                val clock =
                    plan.exactUtcOffset?.let { offset ->
                        stringResource(
                            R.string.calendar_time_with_offset,
                            localizedTime(time),
                            if (offset.totalSeconds == 0) "+00:00" else offset.id,
                        )
                    } ?: localizedTime(time)
                stringResource(R.string.daily_exact_time, clock)
            }
                ?: stringResource(R.string.daily_exact_time_unavailable)
        is PlanTarget.FloatingDay -> stringResource(R.string.daily_floating_day)
        is PlanTarget.Week -> stringResource(R.string.daily_week_of, localizedDate(target.weekStart))
        is PlanTarget.Month -> error("Month Plans are not rendered on Daily")
    }

@Composable
private fun planStatus(plan: DailyPlan): List<String> =
    buildList {
        add(
            stringResource(
                when (plan.plan.status) {
                    PlanEntryStatus.PLANNED -> R.string.daily_plan_planned
                    PlanEntryStatus.FULFILLED -> R.string.daily_plan_fulfilled
                    PlanEntryStatus.CANCELLED -> R.string.daily_plan_cancelled
                },
            ),
        )
        if (plan.engaged) add(stringResource(R.string.daily_plan_engaged))
        if (plan.overdue) add(stringResource(R.string.daily_plan_overdue))
        when (plan.sourceState) {
            PlanSourceState.CURRENT -> Unit
            PlanSourceState.CHANGED -> add(stringResource(R.string.daily_source_changed))
            PlanSourceState.ARCHIVED -> add(stringResource(R.string.daily_source_archived))
            PlanSourceState.UNAVAILABLE -> add(stringResource(R.string.daily_source_unavailable))
        }
    }

@Composable
private fun CompletedSection(completed: List<CompletedHistoryRoot>) {
    SectionTitle(R.string.daily_completed)
    if (completed.isEmpty()) {
        EmptySection(R.string.daily_no_completed)
    } else {
        completed.forEach { CompletedRow(it) }
    }
}

@Composable
private fun CompletedRow(root: CompletedHistoryRoot) {
    DailyCard {
        when (root) {
            is CompletedActivityHistoryRoot -> {
                Text(root.title, style = MaterialTheme.typography.titleMedium)
                root.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                root.activeDuration?.let { HistoricalDuration(it) }
                    ?: Text(
                        stringResource(R.string.daily_history_no_duration),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                val startedAt = root.startedAt
                if (startedAt != null) {
                    Text(
                        historicalTimestampRange(startedAt, root.completedAt, root.originalZoneId),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.daily_completed_at,
                            historicalTimestamp(root.completedAt, root.originalZoneId, includeDate = false),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            is CompletedSequenceHistoryRoot -> {
                Text(root.title, style = MaterialTheme.typography.titleMedium)
                root.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                HistoricalDuration(root.activeDuration)
                Text(
                    historicalTimestampRange(root.startedAt, root.completedAt, root.originalZoneId),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        if (root.status == SequenceExecutionStatus.ENDED_EARLY) {
                            R.string.daily_ended_early
                        } else {
                            R.string.daily_completed
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(id: Int) =
    Text(
        text = stringResource(id),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )

@Composable
private fun EmptySection(id: Int) =
    Text(
        stringResource(id),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )

@Composable
private fun DailyCard(
    modifier: Modifier = Modifier,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().semantics { isTraversalGroup = true },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            content = content,
        )
    }
}

@Composable
private fun HistoricalDuration(duration: Duration) {
    val description = stringResource(R.string.daily_history_duration, spokenDuration(duration))
    Text(
        durationText(duration),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun spokenDuration(
    duration: Duration,
    showSeconds: Boolean = true,
): String {
    val seconds = duration.seconds.coerceAtLeast(0)
    return if (showSeconds) {
        stringResource(R.string.daily_duration_seconds, seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        stringResource(R.string.daily_duration_minutes, seconds / 3600, seconds / 60 % 60)
    }
}

@Composable
private fun activityTimingDescription(
    snapshot: com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot,
    baseline: com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline?,
    elapsedNow: Long,
    currentStep: Boolean,
): String {
    val unavailable = stringResource(R.string.daily_timing_unavailable)
    val value =
        when (snapshot.timeTrackingMode) {
            TimeTrackingMode.NO_LIVE_TRACKING ->
                stringResource(
                    R.string.daily_no_live_value,
                    snapshot.mainValueText() ?: stringResource(R.string.daily_no_main_value),
                )
            TimeTrackingMode.STOPWATCH -> {
                val elapsed =
                    if (currentStep) {
                        baseline?.currentStepStopwatchElapsed(elapsedNow)
                    } else {
                        baseline?.activeElapsed(elapsedNow)
                    }
                stringResource(
                    R.string.daily_stopwatch_elapsed,
                    elapsed?.let { spokenDuration(it, snapshot.settings.showSeconds) } ?: unavailable,
                )
            }
            TimeTrackingMode.TIMER -> stringResource(R.string.daily_timer_value, spokenTimerValue(baseline, elapsedNow))
        }

    return if (currentStep) stringResource(R.string.daily_current_step, value) else value
}

@Composable
private fun spokenTimerValue(
    baseline: com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline?,
    elapsedNow: Long,
): String {
    if (baseline == null) return stringResource(R.string.daily_timing_unavailable)
    val remaining = baseline.timerRemaining(elapsedNow)
    val overtime = baseline.timerOvertime(elapsedNow)
    return when {
        remaining != null && !remaining.isZero ->
            stringResource(R.string.daily_remaining, spokenDuration(remaining))
        overtime != null && !overtime.isZero -> stringResource(R.string.daily_overtime, spokenDuration(overtime))
        remaining == null && overtime == null -> stringResource(R.string.daily_timing_unavailable)
        else -> stringResource(R.string.daily_timer_complete)
    }
}

@Composable
private fun displayTick(
    baseline: com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline?,
    displayElapsedRealtimeMs: Long?,
): Long {
    if (displayElapsedRealtimeMs != null) return displayElapsedRealtimeMs
    var now by remember(baseline) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(baseline) {
        if (baseline != null) {
            while (isActive) {
                delay(1_000)
                now = SystemClock.elapsedRealtime()
            }
        }
    }
    return now
}

@Composable
private fun timerText(
    baseline: com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline,
    elapsedNow: Long,
): String {
    val remaining = baseline.timerRemaining(elapsedNow)
    if (remaining != null && !remaining.isZero) return stringResource(R.string.daily_remaining, durationText(remaining))
    val overtime = baseline.timerOvertime(elapsedNow)
    return if (overtime != null && !overtime.isZero) {
        stringResource(R.string.daily_overtime, durationText(overtime))
    } else {
        stringResource(R.string.daily_timer_complete)
    }
}

@Composable
private fun localizedDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(date, locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)
    }
}

@Composable
private fun localizedTime(time: LocalTime): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(time, locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)
    }
}

private fun durationText(value: Duration): String = DateUtils.formatElapsedTime(value.seconds.coerceAtLeast(0))

private fun stopwatchText(
    value: Duration,
    showSeconds: Boolean,
): String =
    if (showSeconds) {
        durationText(value)
    } else {
        val minutes = value.seconds.coerceAtLeast(0) / 60
        "%d:%02d".format(minutes / 60, minutes % 60)
    }

private fun com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot.mainValueText(): String? =
    fields
        .singleOrNull { it.isMainValue }
        ?.let { field ->
            field.defaultNumberScaled?.let { value ->
                BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString() +
                    field.unit?.let { " $it" }.orEmpty()
            }
        }
