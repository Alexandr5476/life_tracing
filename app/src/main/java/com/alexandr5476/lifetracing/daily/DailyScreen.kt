@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.daily

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DailyRoute(controller: DailyController) {
    DisposableEffect(controller) {
        controller.onRouteEntered()
        onDispose { controller.dispatch(DailyAction.Hidden) }
    }
    val state by controller.state.collectAsState()
    DailyScreen(state = state, onAction = controller::dispatch)
}

@Composable
internal fun DailyScreen(
    state: DailyPresentationState,
    onAction: (DailyAction) -> Unit,
    displayElapsedRealtimeMs: Long? = null,
) {
    val scrollState = rememberScrollState()
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(MaterialTheme.spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            DateHeader(state.selectedDate, state.dateRelation, onAction)
            CommandFailure(state.commandFailure)
            when (val load = state.load) {
                DailyLoadState.Loading -> LoadingContent()
                is DailyLoadState.Failure -> FailureContent(onAction)
                is DailyLoadState.Empty -> DailyContent(state, load.daily, onAction, displayElapsedRealtimeMs)
                is DailyLoadState.Content -> DailyContent(state, load.daily, onAction, displayElapsedRealtimeMs)
            }
        }
    }
}

@Composable
private fun DateHeader(
    selectedDate: LocalDate,
    relation: DailyDateRelation,
    onAction: (DailyAction) -> Unit,
) {
    val previous = stringResource(R.string.daily_previous_day)
    val next = stringResource(R.string.daily_next_day)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        TextButton(
            onClick = { onAction(DailyAction.PreviousDay) },
            modifier = Modifier.semantics { contentDescription = previous },
        ) { Text("\u2039") }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.daily_title), style = MaterialTheme.typography.labelLarge)
            Text(
                text = localizedDate(selectedDate),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
            )
            if (relation != DailyDateRelation.TODAY) {
                TextButton(onClick = { onAction(DailyAction.Today) }) {
                    Text(stringResource(R.string.daily_return_today))
                }
            }
        }
        TextButton(
            onClick = { onAction(DailyAction.NextDay) },
            modifier = Modifier.semantics { contentDescription = next },
        ) { Text("\u203A") }
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
) {
    val plans = daily.dayPlans + daily.weekPlans
    val active: @Composable () -> Unit = {
        if (state.dateRelation == DailyDateRelation.TODAY) {
            daily.active?.let { ActiveSection(it, state, onAction, displayElapsedRealtimeMs) }
        }
    }
    val planned: @Composable () -> Unit = { PlannedSection(plans, state.dateRelation) }
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
) {
    SectionTitle(R.string.daily_active)
    when (active) {
        is DailyActive.Activity -> ActiveActivityCard(active, state, onAction, displayElapsedRealtimeMs)
        is DailyActive.Sequence -> ActiveSequenceCard(active, state, onAction, displayElapsedRealtimeMs)
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
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelLarge,
        )
        LiveActivityValue(
            runtime.snapshot.timeTrackingMode,
            state.runtimeDisplayBaseline,
            displayElapsedRealtimeMs,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
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
    mode: TimeTrackingMode,
    baseline: com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline?,
    displayElapsedRealtimeMs: Long?,
) {
    val elapsedNow = displayTick(baseline, displayElapsedRealtimeMs)
    val value =
        when {
            baseline == null -> stringResource(R.string.daily_timing_unavailable)
            mode == TimeTrackingMode.STOPWATCH -> durationText(baseline.activeElapsed(elapsedNow))
            else -> timerText(baseline, elapsedNow)
        }
    Text(text = value, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun ActiveSequenceCard(
    active: DailyActive.Sequence,
    state: DailyPresentationState,
    onAction: (DailyAction) -> Unit,
    displayElapsedRealtimeMs: Long?,
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
                Text(stringResource(R.string.daily_waiting), style = MaterialTheme.typography.labelLarge)
                NextStep(active)
            }
            DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN,
            DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
            -> TransitionSequenceContent(active, state.runtimeDisplayBaseline, elapsedNow)
        }
        SequenceControls(active, state.commandInFlight, onAction)
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
    Text(current.activity.name, style = MaterialTheme.typography.titleMedium)
    current.activity.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    val value =
        when {
            current.activity.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING ->
                stringResource(R.string.daily_no_live_current_step)
            baseline == null -> stringResource(R.string.daily_timing_unavailable)
            current.activity.timeTrackingMode == TimeTrackingMode.STOPWATCH ->
                baseline.currentStepStopwatchElapsed(elapsedNow)?.let(::durationText)
                    ?: stringResource(R.string.daily_timing_unavailable)
            active.runtime.currentChild == null -> stringResource(R.string.daily_timing_unavailable)
            else -> timerText(baseline, elapsedNow)
        }
    Text(value, style = MaterialTheme.typography.headlineSmall)
    NextStep(active)
}

@Composable
private fun TransitionSequenceContent(
    active: DailyActive.Sequence,
    baseline: com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline?,
    elapsedNow: Long,
) {
    Text(stringResource(R.string.daily_transition_countdown), style = MaterialTheme.typography.labelLarge)
    Text(
        baseline?.transitionCountdownRemaining(elapsedNow)?.let(::durationText)
            ?: stringResource(R.string.daily_timing_unavailable),
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
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
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
) {
    SectionTitle(R.string.daily_planned)
    if (plans.isEmpty()) {
        EmptySection(R.string.daily_no_plans)
    } else {
        plans.forEach { PlannedRow(it, relation) }
    }
}

@Composable
private fun PlannedRow(
    plan: DailyPlan,
    relation: DailyDateRelation,
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
    }
}

@Composable
private fun planContext(plan: DailyPlan): String =
    when (val target = plan.plan.target) {
        is PlanTarget.ExactDay ->
            plan.exactLocalTime?.let { stringResource(R.string.daily_exact_time, localizedTime(it)) }
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
                root.activeDuration?.let { Text(durationText(it), style = MaterialTheme.typography.labelLarge) }
                val startedAt = root.startedAt
                if (startedAt != null) {
                    Text(
                        stringResource(
                            R.string.daily_time_range,
                            localizedTime(startedAt),
                            localizedTime(root.completedAt),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        stringResource(R.string.daily_completed_at, localizedTime(root.completedAt)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            is CompletedSequenceHistoryRoot -> {
                Text(root.title, style = MaterialTheme.typography.titleMedium)
                root.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(durationText(root.activeDuration), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(
                        R.string.daily_time_range,
                        localizedTime(root.startedAt),
                        localizedTime(root.completedAt),
                    ),
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
private fun SectionTitle(id: Int) = Text(text = stringResource(id), style = MaterialTheme.typography.titleMedium)

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
        modifier = modifier.fillMaxWidth(),
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

@Composable
private fun localizedTime(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val zoneId = ZoneId.systemDefault()
    return remember(instant, locale, zoneId) {
        DateTimeFormatter
            .ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(instant.atZone(zoneId))
    }
}

private fun durationText(value: Duration): String = DateUtils.formatElapsedTime(value.seconds.coerceAtLeast(0))
