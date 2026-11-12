@file:Suppress("FunctionNaming", "LongMethod", "TooManyFunctions")

package com.alexandr5476.lifetracing.plan

import android.text.format.DateUtils
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun PlanRoute(
    controller: PlanController,
    onBack: () -> Unit,
    onExecute: (PlanActionIdentity) -> Unit = {},
    isRouteVisible: Boolean = true,
    ownsRoute: () -> Boolean = { isRouteVisible },
) {
    val state by controller.state.collectAsState()
    // Read navigation ownership at event admission; visibility effects may still be pending.
    // This gate does not revoke writers that the controller has already admitted.
    val admit: (() -> Unit) -> Unit = { action -> if (ownsRoute()) action() }
    PlanScreen(
        state,
        onAction = { action -> admit { controller.dispatch(action) } },
        onBack = { admit(onBack) },
        onExecute = { row -> admit { controller.executeIfOwned(row, onExecute) } },
        isRouteVisible = isRouteVisible,
    )
}

@Composable
@Suppress("CyclomaticComplexMethod") // Canonical load, lifecycle feedback and the existing modal owners.
fun PlanScreen(
    state: PlanPresentationState,
    onAction: (PlanAction) -> Unit,
    onBack: () -> Unit,
    onExecute: (PlanReadRow) -> Unit = {},
    isRouteVisible: Boolean = true,
) {
    val focus = rememberPlanCalendarFocus(state, isRouteVisible)
    LifeTracingAccessibleTargets {
        CompositionLocalProvider(LocalPlanCalendarFocus provides focus) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .focusGroup()
                    .padding(MaterialTheme.spacing.large),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onBack, modifier = planCalendarFocusTarget().focusRequester(focus.back)) {
                        Text(stringResource(R.string.plan_back))
                    }
                }
                WeekNavigation(state, onAction)
                val canonicalToday = (state.week as? PlanLoad.Content)?.value?.temporalContext?.today
                val todayDescription =
                    canonicalToday?.let {
                        stringResource(
                            R.string.plan_calendar_navigation,
                            stringResource(R.string.plan_today),
                            fullPlanDate(it),
                        )
                    } ?: stringResource(R.string.plan_today)
                TextButton(
                    onClick = { onAction(PlanAction.Today) },
                    modifier =
                        planCalendarFocusTarget().focusRequester(focus.today).semantics {
                            contentDescription =
                                todayDescription
                        },
                ) { Text(stringResource(R.string.plan_today)) }
                when (val load = state.week) {
                    PlanLoad.Loading -> Text(stringResource(R.string.plan_loading))
                    is PlanLoad.Failure -> {
                        Text(load.message.label())
                        TextButton(
                            onClick = { onAction(PlanAction.Refresh) },
                            modifier = planCalendarFocusTarget().focusRequester(focus.retry),
                        ) { Text(stringResource(R.string.plan_retry)) }
                    }
                    is PlanLoad.Content -> WeekContent(load.value, state.isMutating, onAction, onExecute)
                }
                if (state.isMutating) Text(stringResource(R.string.plan_lifecycle_pending), Modifier.planFeedback())
                state.mutationFailure?.let {
                    Text(
                        it.label(),
                        Modifier.planFeedback(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.recoveryFailure != null && state.week !is PlanLoad.Failure && !state.cancelledOpen) {
                    Text(
                        state.recoveryFailure.label(),
                        Modifier.planFeedback(),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = {
                        onAction(PlanAction.Refresh)
                    }, modifier = planCalendarFocusTarget()) { Text(stringResource(R.string.plan_retry)) }
                }
                LifeTracingPrimaryButton(
                    enabled = !state.isMutating,
                    modifier = planCalendarFocusTarget().focusRequester(focus.add).semantics { testTag = "plan-add" },
                    onClick = { onAction(PlanAction.OpenCatalog) },
                ) { Text(stringResource(R.string.plan_add)) }
                TextButton(
                    onClick = {
                        onAction(PlanAction.OpenCancelled)
                    },
                    modifier =
                        planCalendarFocusTarget().focusRequester(focus.cancelled).semantics {
                            testTag = "plan-cancelled-entry"
                        },
                ) { Text(stringResource(R.string.plan_cancelled)) }
            }
        }
    }
    if (isRouteVisible) {
        state.catalog?.let { key(it.context.dialogId) { PlanCatalogDialog(it, onAction) } }
        state.form?.let { key(it.formId) { PlanScheduleDialog(it, state.isMutating, onAction) } }
    }
    if (isRouteVisible && state.cancelledOpen) {
        state.cancelledContext?.let { key(it.dialogId) { PlanCancelledDialog(state, onAction) } }
    }
}

@Composable
private fun fullPlanDate(date: java.time.LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(LocalConfiguration.current.locales[0]).format(date)

private const val PLAN_WEEK_LAST_DAY_OFFSET = 6L

@Composable
private fun planWeekRange(start: java.time.LocalDate): String =
    stringResource(
        R.string.plan_calendar_range,
        fullPlanDate(start),
        fullPlanDate(start.plusDays(PLAN_WEEK_LAST_DAY_OFFSET)),
    )

@Composable
private fun WeekNavigation(
    state: PlanPresentationState,
    onAction: (PlanAction) -> Unit,
) {
    val read = (state.week as? PlanLoad.Content)?.value
    val range = planWeekRange(state.weekStart)
    val today = read?.temporalContext?.today
    val isCurrent = today != null && today in state.weekStart..state.weekStart.plusDays(PLAN_WEEK_LAST_DAY_OFFSET)
    val weekDescription =
        stringResource(
            if (isCurrent) R.string.plan_calendar_current_week else R.string.plan_calendar_displayed_week,
            range,
        )
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val previous =
            stringResource(
                R.string.plan_calendar_navigation,
                stringResource(R.string.plan_previous_week),
                planWeekRange(state.weekStart.minusWeeks(1)),
            )
        TextButton(
            onClick = { onAction(PlanAction.PreviousWeek) },
            modifier =
                planCalendarFocusTarget().semantics {
                    contentDescription = previous
                    testTag = "plan-previous-week"
                },
        ) {
            Text(stringResource(R.string.plan_previous_week))
        }
        Text(
            weekDescription,
            style = MaterialTheme.typography.titleMedium,
            modifier =
                Modifier.semantics {
                    heading()
                    testTag =
                        "plan-displayed-week"
                },
        )
        val next =
            stringResource(
                R.string.plan_calendar_navigation,
                stringResource(R.string.plan_next_week),
                planWeekRange(state.weekStart.plusWeeks(1)),
            )
        TextButton(
            onClick = { onAction(PlanAction.NextWeek) },
            modifier =
                planCalendarFocusTarget().semantics {
                    contentDescription = next
                    testTag = "plan-next-week"
                },
        ) {
            Text(stringResource(R.string.plan_next_week))
        }
    }
}

@Composable
private fun WeekContent(
    read: com.alexandr5476.lifetracing.domain.WeekPlanRead,
    isMutating: Boolean,
    onAction: (PlanAction) -> Unit,
    onExecute: (PlanReadRow) -> Unit,
) {
    Text(
        stringResource(R.string.plan_week_days),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        read.dayPresence.forEach { day ->
            key(day.date) {
                val date = fullPlanDate(day.date)
                val density = stringResource(R.string.plan_day_density, date, day.count)
                val description =
                    if (day.date == read.temporalContext?.today) {
                        stringResource(R.string.plan_calendar_today, density)
                    } else {
                        density
                    }
                val focus = LocalPlanCalendarFocus.current
                val focusModifier =
                    if (day.date ==
                        read.selectedDate
                    ) {
                        Modifier.focusRequester(focus.selectedDay)
                    } else {
                        Modifier
                    }
                LifeTracingChoiceButton(
                    selected = day.date == read.selectedDate,
                    modifier =
                        planCalendarFocusTarget().then(focusModifier).semantics {
                            testTag = "plan-day-${day.date}"
                            contentDescription = description
                            selected = day.date == read.selectedDate
                        },
                    onClick = { onAction(PlanAction.SelectDate(day.date)) },
                ) { Text("${day.date.dayOfMonth}\n${day.count}") }
            }
        }
    }
    Text(
        stringResource(R.string.plan_selected_day, fullPlanDate(read.selectedDate)),
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier.semantics {
                heading()
                testTag = "plan-selected-heading"
            },
    )
    if (read.selectedDayPlans.isEmpty()) Text(stringResource(R.string.plan_empty_day))
    PlanRows(read.selectedDayPlans, isMutating, onAction, onExecute)
    Text(
        stringResource(R.string.plan_calendar_week_section, planWeekRange(read.weekStart)),
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier.semantics {
                heading()
                testTag = "plan-week-heading"
            },
    )
    if (read.weekPlans.isEmpty()) Text(stringResource(R.string.plan_empty_week))
    PlanRows(read.weekPlans, isMutating, onAction, onExecute)
}

@Composable
private fun PlanRows(
    rows: List<PlanReadRow>,
    isMutating: Boolean,
    onAction: (PlanAction) -> Unit,
    onExecute: (PlanReadRow) -> Unit,
) {
    val executable = rows.filter { it.plan.status == PlanEntryStatus.PLANNED && !it.engaged }
    val management =
        disambiguatePlanDescriptions(
            executable.map {
                stringResource(
                    R.string.plan_management_context,
                    it.title,
                    it.actionTarget().kindDescription(),
                    it.actionTarget().scheduleDescription(),
                )
            },
        )
    val managementById = executable.mapIndexed { index, row -> row.plan.id to management[index] }.toMap()
    val descriptions =
        planExecutionActionDescriptions(
            executable.map { it.actionTarget() },
        )
    val descriptionById = executable.mapIndexed { index, row -> row.plan.id to descriptions[index] }.toMap()
    rows.forEach { row ->
        key(row.plan.id) {
            PlanRow(row, isMutating, onAction, onExecute, descriptionById[row.plan.id], managementById[row.plan.id])
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun PlanRow(
    row: PlanReadRow,
    isMutating: Boolean,
    onAction: (PlanAction) -> Unit,
    onExecute: (PlanReadRow) -> Unit,
    executionDescription: String?,
    managementContext: String?,
) {
    Card(
        Modifier.fillMaxWidth().semantics {
            isTraversalGroup = true
            testTag = "plan-row-${row.plan.id.value}"
        },
    ) {
        Column(
            Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            Text(row.actionTarget().kindDescription())
            row.shortComment?.let { Text(it) }
            row.scheduleLabel()?.let { label ->
                val description = row.actionTarget().scheduleDescription()
                Text(label, Modifier.semantics { contentDescription = description })
            }
            row.activityMetadata?.timerTarget?.let {
                Text(stringResource(R.string.plan_timer_target, DateUtils.formatElapsedTime(it.seconds)))
            }
            Text(row.lifecycleLabel())
            if (row.plan.status == PlanEntryStatus.PLANNED && (row.engaged || row.overdue)) {
                Text(stringResource(R.string.plan_planned))
            }
            if (row.engaged && row.overdue) Text(stringResource(R.string.plan_overdue))
            Text(row.sourceLabel())
            if (row.plan.status == PlanEntryStatus.PLANNED && !row.engaged) {
                val executionLabel = row.executionLabel()
                requireNotNull(executionDescription)
                LifeTracingPrimaryButton(
                    enabled = !isMutating,
                    onClick = { onExecute(row) },
                    modifier =
                        planCalendarFocusTarget().semantics {
                            testTag = "plan-plan-action-${row.plan.id.value}"
                            contentDescription = executionDescription
                        },
                ) { Text(executionLabel) }
                requireNotNull(managementContext)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    val reschedule =
                        stringResource(
                            R.string.plan_management_action,
                            stringResource(R.string.plan_reschedule),
                            managementContext,
                        )
                    LifeTracingSecondaryButton(
                        enabled = !isMutating,
                        onClick = {
                            onAction(PlanAction.Reschedule(row))
                        },
                        modifier =
                            planCalendarFocusTarget().semantics {
                                testTag = "plan-reschedule-${row.plan.id.value}"
                                contentDescription = reschedule
                            },
                    ) { Text(stringResource(R.string.plan_reschedule)) }
                    val cancel =
                        stringResource(
                            R.string.plan_management_action,
                            stringResource(R.string.plan_cancel),
                            managementContext,
                        )
                    TextButton(
                        enabled = !isMutating,
                        onClick = {
                            onAction(PlanAction.Cancel(row))
                        },
                        modifier =
                            planCalendarFocusTarget().semantics {
                                testTag = "plan-cancel-${row.plan.id.value}"
                                contentDescription = cancel
                            },
                    ) { Text(stringResource(R.string.plan_cancel)) }
                    if (row.sourceState == PlanSourceState.CHANGED) {
                        val update =
                            stringResource(
                                R.string.plan_management_action,
                                stringResource(R.string.plan_update_template),
                                managementContext,
                            )
                        TextButton(
                            enabled = !isMutating,
                            onClick = {
                                onAction(PlanAction.UpdateFromTemplate(row))
                            },
                            modifier =
                                planCalendarFocusTarget().semantics {
                                    testTag = "plan-update-${row.plan.id.value}"
                                    contentDescription = update
                                },
                        ) { Text(stringResource(R.string.plan_update_template)) }
                    }
                }
            }
        }
    }
}

@Composable internal fun PlanScheduleKind.label() =
    when (this) {
        PlanScheduleKind.FLOATING_DAY -> stringResource(R.string.plan_floating_day)
        PlanScheduleKind.EXACT_DAY -> stringResource(R.string.plan_exact_day)
        PlanScheduleKind.WEEK -> stringResource(R.string.plan_week)
    }

@Composable private fun PlanReadRow.scheduleLabel(): String? =
    when (plan.target) {
        is com.alexandr5476.lifetracing.domain.PlanTarget.FloatingDay -> stringResource(R.string.plan_anytime)
        is com.alexandr5476.lifetracing.domain.PlanTarget.ExactDay ->
            stringResource(
                R.string.plan_exact_at,
                exactUtcOffset?.let { offset ->
                    stringResource(
                        R.string.calendar_time_with_offset,
                        requireNotNull(exactLocalTime).format(DateTimeFormatter.ofPattern("HH:mm")),
                        if (offset.totalSeconds == 0) "+00:00" else offset.id,
                    )
                } ?: requireNotNull(exactLocalTime).format(DateTimeFormatter.ofPattern("HH:mm")),
            )
        is com.alexandr5476.lifetracing.domain.PlanTarget.Week -> null
        is com.alexandr5476.lifetracing.domain.PlanTarget.Month -> error("Month Plans are not rendered")
    }

@Composable private fun PlanReadRow.executionLabel() =
    if (activityMetadata?.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
        stringResource(R.string.plan_complete_action)
    } else {
        stringResource(R.string.plan_start_action)
    }

@Composable internal fun PlanMessage.label() =
    stringResource(
        when (this) {
            PlanMessage.LOAD_FAILED -> R.string.plan_load_failed
            PlanMessage.INVALID_SCHEDULE -> R.string.plan_invalid_schedule
            PlanMessage.ACTION_UNAVAILABLE -> R.string.plan_action_unavailable
            PlanMessage.INVALID_DATE -> R.string.plan_invalid_date
            PlanMessage.INVALID_TIME -> R.string.plan_invalid_time
            PlanMessage.UNSUPPORTED_TIME_PRECISION -> R.string.plan_unsupported_time_precision
            PlanMessage.NONEXISTENT_TIME -> R.string.plan_nonexistent_time
            PlanMessage.AMBIGUOUS_TIME -> R.string.plan_ambiguous_time
            PlanMessage.INVALID_OFFSET -> R.string.plan_invalid_offset
            PlanMessage.ZONE_CHANGED -> R.string.plan_zone_changed
            PlanMessage.PERSISTENCE_FAILED -> R.string.plan_persistence_failed
        },
    )

@Composable private fun PlanReadRow.lifecycleLabel() =
    when {
        plan.status == PlanEntryStatus.CANCELLED -> stringResource(R.string.daily_plan_cancelled)
        plan.status == PlanEntryStatus.FULFILLED -> stringResource(R.string.plan_fulfilled)
        engaged -> stringResource(R.string.plan_engaged)
        overdue -> stringResource(R.string.plan_overdue)
        else -> stringResource(R.string.plan_planned)
    }

@Composable internal fun PlanReadRow.sourceLabel() =
    when (sourceState) {
        PlanSourceState.CURRENT -> stringResource(R.string.plan_source_current)
        PlanSourceState.CHANGED -> stringResource(R.string.plan_source_changed)
        PlanSourceState.ARCHIVED -> stringResource(R.string.plan_source_archived)
        PlanSourceState.UNAVAILABLE -> stringResource(R.string.plan_source_unavailable)
    }
