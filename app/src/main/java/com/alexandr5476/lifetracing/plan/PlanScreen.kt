@file:Suppress("FunctionNaming", "LongMethod", "TooManyFunctions")

package com.alexandr5476.lifetracing.plan

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.time.format.DateTimeFormatter

@Composable
fun PlanRoute(
    controller: PlanController,
    onBack: () -> Unit,
    onExecute: (PlanActionIdentity) -> Unit = {},
) {
    LaunchedEffect(controller) { controller.onRouteEntered() }
    val state by controller.state.collectAsState()
    PlanScreen(state, controller::dispatch, onBack, onExecute)
}

@Composable
fun PlanScreen(
    state: PlanPresentationState,
    onAction: (PlanAction) -> Unit,
    onBack: () -> Unit,
    onExecute: (PlanActionIdentity) -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.large),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.plan_back)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                onClick = { onAction(PlanAction.PreviousWeek) },
            ) { Text(stringResource(R.string.plan_previous_week)) }
            Text(state.weekStart.toString(), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onAction(PlanAction.NextWeek) }) { Text(stringResource(R.string.plan_next_week)) }
        }
        TextButton(onClick = { onAction(PlanAction.Today) }) { Text(stringResource(R.string.plan_today)) }
        when (val load = state.week) {
            PlanLoad.Loading -> Text(stringResource(R.string.plan_loading))
            is PlanLoad.Failure -> {
                Text(load.message.label())
                TextButton(onClick = { onAction(PlanAction.Refresh) }) {
                    Text(stringResource(R.string.plan_retry))
                }
            }
            is PlanLoad.Content -> WeekContent(load.value, state.isMutating, onAction, onExecute)
        }
        state.mutationFailure?.let { Text(it.label(), color = MaterialTheme.colorScheme.error) }
        if (state.recoveryFailure != null && state.week !is PlanLoad.Failure && !state.cancelledOpen) {
            Text(state.recoveryFailure.label(), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { onAction(PlanAction.Refresh) }) { Text(stringResource(R.string.plan_retry)) }
        }
        LifeTracingPrimaryButton(
            enabled = !state.isMutating,
            onClick = { onAction(PlanAction.OpenCatalog) },
        ) { Text(stringResource(R.string.plan_add)) }
        TextButton(onClick = { onAction(PlanAction.OpenCancelled) }) { Text(stringResource(R.string.plan_cancelled)) }
    }
    state.catalog?.let { CatalogDialog(it, onAction) }
    state.form?.let { ScheduleDialog(it, state.isMutating, onAction) }
    if (state.cancelledOpen) CancelledDialog(state, onAction)
}

@Composable
private fun WeekContent(
    read: com.alexandr5476.lifetracing.domain.WeekPlanRead,
    isMutating: Boolean,
    onAction: (PlanAction) -> Unit,
    onExecute: (PlanActionIdentity) -> Unit,
) {
    Text(stringResource(R.string.plan_week_days), style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        read.dayPresence.forEach { day ->
            val densityDescription = stringResource(R.string.plan_day_density, day.date.toString(), day.count)
            TextButton(
                modifier =
                    Modifier.semantics {
                        testTag = "plan-day-${day.date}"
                        contentDescription = densityDescription
                    },
                onClick = { onAction(PlanAction.SelectDate(day.date)) },
            ) { Text("${day.date.dayOfMonth}\n${day.count}") }
        }
    }
    Text(
        stringResource(R.string.plan_selected_day, read.selectedDate.toString()),
        style = MaterialTheme.typography.titleMedium,
    )
    if (read.selectedDayPlans.isEmpty()) Text(stringResource(R.string.plan_empty_day))
    read.selectedDayPlans.forEach { PlanRow(it, isMutating, onAction, onExecute) }
    Text(stringResource(R.string.plan_this_week), style = MaterialTheme.typography.titleMedium)
    if (read.weekPlans.isEmpty()) Text(stringResource(R.string.plan_empty_week))
    read.weekPlans.forEach { PlanRow(it, isMutating, onAction, onExecute) }
}

@Composable
private fun PlanRow(
    row: PlanReadRow,
    isMutating: Boolean,
    onAction: (PlanAction) -> Unit,
    onExecute: (PlanActionIdentity) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            row.shortComment?.let { Text(it) }
            row.scheduleLabel()?.let { Text(it) }
            row.activityMetadata?.timerTarget?.let {
                Text(stringResource(R.string.plan_timer_target, DateUtils.formatElapsedTime(it.seconds)))
            }
            Text(row.lifecycleLabel())
            Text(row.sourceLabel())
            if (row.plan.status == PlanEntryStatus.PLANNED && !row.engaged) {
                LifeTracingPrimaryButton(
                    enabled = !isMutating,
                    onClick = { onExecute(row.plan.actionIdentity()) },
                    modifier = Modifier.semantics { testTag = "plan-plan-action-${row.plan.id.value}" },
                ) { Text(row.executionLabel()) }
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    LifeTracingSecondaryButton(enabled = !isMutating, onClick = {
                        onAction(PlanAction.Reschedule(row))
                    }) { Text(stringResource(R.string.plan_reschedule)) }
                    TextButton(enabled = !isMutating, onClick = {
                        onAction(PlanAction.Cancel(row))
                    }) { Text(stringResource(R.string.plan_cancel)) }
                    if (row.sourceState == PlanSourceState.CHANGED) {
                        TextButton(enabled = !isMutating, onClick = {
                            onAction(PlanAction.UpdateFromTemplate(row))
                        }) { Text(stringResource(R.string.plan_update_template)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogDialog(
    catalog: PlanCatalogState,
    onAction: (PlanAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(PlanAction.DismissCatalog) },
        title = { Text(stringResource(R.string.plan_choose_template)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                LifeTracingOutlinedTextField(catalog.query, {
                    onAction(PlanAction.SearchCatalog(it))
                }, label = { Text(stringResource(R.string.plan_search)) })
                catalog.items.forEach { item ->
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        onAction(PlanAction.SelectCatalogItem(item))
                    }) { Text(item.name) }
                }
                if (catalog.loading) Text(stringResource(R.string.plan_loading))
                catalog.failure?.let {
                    Text(it.label(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onAction(PlanAction.SearchCatalog(catalog.query)) }) {
                        Text(stringResource(R.string.plan_retry))
                    }
                }
                if (catalog.hasNextPage) {
                    TextButton(onClick = {
                        onAction(PlanAction.LoadMoreCatalog)
                    }) { Text(stringResource(R.string.plan_load_more)) }
                }
            }
        },
        confirmButton = {
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(PlanAction.DismissCatalog) },
            ) { Text(stringResource(R.string.plan_close)) }
        },
    )
}

@Composable
private fun ScheduleDialog(
    form: PlanScheduleForm,
    isMutating: Boolean,
    onAction: (PlanAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(PlanAction.DismissForm) },
        title = {
            Text(
                if (form.source ==
                    null
                ) {
                    stringResource(R.string.plan_reschedule)
                } else {
                    stringResource(R.string.plan_schedule)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                form.source?.let { Text(it.name) }
                Row {
                    PlanScheduleKind.entries.forEach { kind ->
                        TextButton(onClick = { onAction(PlanAction.ChangeScheduleKind(kind)) }) { Text(kind.label()) }
                    }
                }
                LifeTracingOutlinedTextField(form.date, {
                    onAction(PlanAction.ChangeScheduleDate(it))
                }, label = { Text(stringResource(R.string.plan_date)) })
                if (form.kind ==
                    PlanScheduleKind.EXACT_DAY
                ) {
                    LifeTracingOutlinedTextField(form.time, {
                        onAction(PlanAction.ChangeScheduleTime(it))
                    }, label = { Text(stringResource(R.string.plan_time)) })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !isMutating, onClick = {
                onAction(PlanAction.SubmitForm)
            }) { Text(stringResource(R.string.plan_save)) }
        },
        dismissButton = {
            TextButton(onClick = { onAction(PlanAction.DismissForm) }) { Text(stringResource(R.string.plan_close)) }
        },
    )
}

@Composable
private fun CancelledDialog(
    state: PlanPresentationState,
    onAction: (PlanAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(PlanAction.DismissCancelled) },
        title = { Text(stringResource(R.string.plan_cancelled)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                state.recoveryFailure?.let {
                    Text(it.label())
                    TextButton(onClick = { onAction(PlanAction.Refresh) }) {
                        Text(stringResource(R.string.plan_retry))
                    }
                }
                when (val load = state.cancelled) {
                    PlanLoad.Loading -> Text(stringResource(R.string.plan_loading))
                    is PlanLoad.Failure ->
                        if (state.recoveryFailure == null) {
                            Text(load.message.label())
                            TextButton(onClick = { onAction(PlanAction.OpenCancelled) }) {
                                Text(stringResource(R.string.plan_retry))
                            }
                        }
                    is PlanLoad.Content ->
                        state.cancelledItems.forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(row.title)
                                TextButton(enabled = !state.isMutating, onClick = {
                                    onAction(PlanAction.Restore(row))
                                }) { Text(stringResource(R.string.plan_restore)) }
                            }
                        }
                    null -> Unit
                }
                if (state.cancelledHasNextPage) {
                    TextButton(enabled = !state.isMutating, onClick = {
                        onAction(PlanAction.LoadMoreCancelled)
                    }) { Text(stringResource(R.string.plan_load_more)) }
                }
            }
        },
        confirmButton = {
        },
        dismissButton = {
            TextButton(
                enabled = !state.isMutating,
                onClick = { onAction(PlanAction.DismissCancelled) },
            ) { Text(stringResource(R.string.plan_close)) }
        },
    )
}

@Composable private fun PlanScheduleKind.label() =
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
                requireNotNull(exactLocalTime).format(DateTimeFormatter.ofPattern("HH:mm")),
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

@Composable private fun PlanMessage.label() =
    stringResource(
        when (this) {
            PlanMessage.LOAD_FAILED -> R.string.plan_load_failed
            PlanMessage.INVALID_SCHEDULE -> R.string.plan_invalid_schedule
            PlanMessage.ACTION_UNAVAILABLE -> R.string.plan_action_unavailable
        },
    )

@Composable private fun PlanReadRow.lifecycleLabel() =
    when {
        plan.status == PlanEntryStatus.FULFILLED -> stringResource(R.string.plan_fulfilled)
        engaged -> stringResource(R.string.plan_engaged)
        overdue -> stringResource(R.string.plan_overdue)
        else -> stringResource(R.string.plan_planned)
    }

@Composable private fun PlanReadRow.sourceLabel() =
    when (sourceState) {
        PlanSourceState.CURRENT -> stringResource(R.string.plan_source_current)
        PlanSourceState.CHANGED -> stringResource(R.string.plan_source_changed)
        PlanSourceState.ARCHIVED -> stringResource(R.string.plan_source_archived)
        PlanSourceState.UNAVAILABLE -> stringResource(R.string.plan_source_unavailable)
    }
