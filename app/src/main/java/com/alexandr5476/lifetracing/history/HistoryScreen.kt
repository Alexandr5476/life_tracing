@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "TooManyFunctions",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
)

package com.alexandr5476.lifetracing.history

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryActualValue
import com.alexandr5476.lifetracing.domain.SequenceHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryField
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrence
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HistoryRoute(
    controller: HistoryController,
    onBack: () -> Unit,
    onAddCompletedActivity: () -> Unit,
    onOpenActivity: (CompletedActivityHistoryRoot) -> Unit,
    onOpenSequence: (CompletedSequenceHistoryRoot) -> Unit,
) {
    LaunchedEffect(controller) { controller.onRouteEntered() }
    val state by controller.state.collectAsState()
    HistoryScreen(state, controller::dispatch, onBack, onAddCompletedActivity, onOpenActivity, onOpenSequence)
}

@Composable
internal fun ActivityHistoryDetailRoute(
    session: ActivityHistoryMutationRouteSession,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDeleted: () -> Unit,
) {
    val controller = session.controller
    val state by controller.state.collectAsState()
    BackHandler(enabled = state.draft != null || state.deleteConfirmation || state.isMutating) {
        controller.handleBack()
    }
    LaunchedEffect(state.refreshGeneration, state.deleted) {
        session.deliverRefresh(state.refreshGeneration, onRefresh)
        if (state.deleted) session.deliverDelete(onDeleted)
    }
    ActivityHistoryMutationSurface(
        state,
        controller::dispatch,
        onBack = { if (!controller.handleBack()) onBack() },
    )
}

@Composable
fun SequenceHistoryDetailRoute(
    controller: HistoryDetailController<SequenceHistoryDetail>,
    onBack: () -> Unit,
) {
    DisposableEffect(controller) { onDispose(controller::close) }
    val load by controller.state.collectAsState()
    HistoryDetailSurface(onBack, load, controller::reload) { SequenceDetail(it) }
}

@Composable
internal fun HistoryScreen(
    state: HistoryPresentationState,
    onAction: (HistoryAction) -> Unit,
    onBack: () -> Unit = {},
    onAddCompletedActivity: () -> Unit = {},
    onOpenActivity: (CompletedActivityHistoryRoot) -> Unit = {},
    onOpenSequence: (CompletedSequenceHistoryRoot) -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text(stringResource(R.string.history_back)) }
            }
            LifeTracingPrimaryButton(
                onClick = onAddCompletedActivity,
                modifier = Modifier.testTag("history-add-completed-activity"),
            ) { Text(stringResource(R.string.manual_history_title)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LifeTracingSecondaryButton(
                    onClick = { onAction(HistoryAction.Older) },
                    modifier = Modifier.testTag("history-older"),
                ) { Text(stringResource(R.string.history_older)) }
                Text(
                    historyDateRange(state.window.startDate, state.window.endDate),
                    style = MaterialTheme.typography.labelLarge,
                )
                LifeTracingSecondaryButton(
                    onClick = { onAction(HistoryAction.Newer) },
                    enabled = state.canNavigateNewer,
                    modifier = Modifier.testTag("history-newer"),
                ) { Text(stringResource(R.string.history_newer)) }
            }
            when (val load = state.load) {
                HistoryRootsLoad.Loading -> HistoryCard { Text(stringResource(R.string.history_loading)) }
                HistoryRootsLoad.Empty -> HistoryCard { Text(stringResource(R.string.history_empty)) }
                is HistoryRootsLoad.Failure -> FailureCard(onRetry = { onAction(HistoryAction.Retry) })
                is HistoryRootsLoad.Content ->
                    load.roots
                        .groupBy(CompletedHistoryRoot::primaryLocalDate)
                        .toSortedMap(compareByDescending { it })
                        .forEach { (date, roots) ->
                            Text(historyDate(date), style = MaterialTheme.typography.titleMedium)
                            roots.forEach { root ->
                                HistoryRootRow(root, onOpenActivity, onOpenSequence)
                            }
                        }
            }
        }
    }
}

@Composable
private fun HistoryRootRow(
    root: CompletedHistoryRoot,
    onOpenActivity: (CompletedActivityHistoryRoot) -> Unit,
    onOpenSequence: (CompletedSequenceHistoryRoot) -> Unit,
) {
    HistoryCard(
        modifier =
            Modifier
                .testTag(
                    when (root) {
                        is CompletedActivityHistoryRoot -> "history-activity-${root.executionId.value}"
                        is CompletedSequenceHistoryRoot -> "history-sequence-${root.executionId.value}"
                    },
                ),
        onClick = {
            when (root) {
                is CompletedActivityHistoryRoot -> onOpenActivity(root)
                is CompletedSequenceHistoryRoot -> onOpenSequence(root)
            }
        },
    ) {
        when (root) {
            is CompletedActivityHistoryRoot -> {
                Text(root.title, style = MaterialTheme.typography.titleMedium)
                root.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    stringResource(R.string.history_active_duration, durationOrMissing(root.activeDuration)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    historyTracking(root.timeTrackingMode, root.timerTarget),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            is CompletedSequenceHistoryRoot -> {
                Text(root.title, style = MaterialTheme.typography.titleMedium)
                root.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    stringResource(R.string.history_active_duration, durationText(root.activeDuration)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.history_pause_duration, durationText(root.pauseDuration)))
                Text(stringResource(R.string.history_wall_duration, durationText(root.wallDuration)))
                Text(
                    stringResource(sequenceStatusResource(root.status)),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun <T> HistoryDetailSurface(
    onBack: () -> Unit,
    load: HistoryDetailLoad<T>,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.history_back)) }
            when (load) {
                HistoryDetailLoad.Loading -> HistoryCard { Text(stringResource(R.string.history_detail_loading)) }
                HistoryDetailLoad.Unavailable -> HistoryCard { Text(stringResource(R.string.history_unavailable)) }
                is HistoryDetailLoad.Failure -> FailureCard(onRetry)
                is HistoryDetailLoad.Content -> content(load.value)
            }
        }
    }
}

@Composable
internal fun ActivityDetail(detail: ActivityHistoryDetail) {
    Text(detail.root.title, style = MaterialTheme.typography.headlineSmall)
    detail.root.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    Text(
        historyRootTimingInZone(
            detail.root.startedAt,
            detail.root.completedAt,
            detail.originalZoneId,
        ),
    )
    Text(stringResource(R.string.history_active_duration, durationOrMissing(detail.root.activeDuration)))
    Text(
        historyTracking(detail.root.timeTrackingMode, detail.root.timerTarget),
        style = MaterialTheme.typography.labelLarge,
    )
    Text(stringResource(R.string.history_fields), style = MaterialTheme.typography.titleMedium)
    if (detail.fields.isEmpty()) Text(stringResource(R.string.history_no_fields))
    detail.fields.forEach { HistoryField(it) }
}

@Composable
private fun ActivityHistoryMutationSurface(
    state: ActivityHistoryMutationState,
    onAction: (ActivityHistoryMutationAction) -> Unit,
    onBack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            TextButton(onClick = onBack, enabled = !state.isMutating) { Text(stringResource(R.string.history_back)) }
            when (val load = state.load) {
                HistoryDetailLoad.Loading -> HistoryCard { Text(stringResource(R.string.history_detail_loading)) }
                HistoryDetailLoad.Unavailable -> HistoryCard { Text(stringResource(R.string.history_unavailable)) }
                is HistoryDetailLoad.Failure -> FailureCard { onAction(ActivityHistoryMutationAction.Retry) }
                is HistoryDetailLoad.Content -> {
                    when {
                        state.draft != null -> CorrectionEditor(load.value, state, onAction)
                        state.deleteConfirmation ->
                            DeleteConfirmation(load.value, state.isMutating, state.issue, onAction)
                        else -> {
                            state.issue?.let { MutationIssue(it) }
                            ActivityDetail(load.value)
                            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                                if (load.value.root.planEntryId == null) {
                                    LifeTracingSecondaryButton(
                                        onClick = { onAction(ActivityHistoryMutationAction.BeginCorrection) },
                                        modifier = Modifier.testTag("history-correct"),
                                    ) { Text(stringResource(R.string.history_correct)) }
                                }
                                LifeTracingSecondaryButton(
                                    onClick = { onAction(ActivityHistoryMutationAction.RequestDelete) },
                                    modifier = Modifier.testTag("history-delete"),
                                ) { Text(stringResource(R.string.history_delete)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CorrectionEditor(
    detail: ActivityHistoryDetail,
    state: ActivityHistoryMutationState,
    onAction: (ActivityHistoryMutationAction) -> Unit,
) {
    val draft = requireNotNull(state.draft)
    Text(detail.root.title, style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.history_event_zone, draft.eventZoneId.id))
    draft.startedText?.let { started ->
        LifeTracingOutlinedTextField(
            started,
            { onAction(ActivityHistoryMutationAction.EditStarted(it)) },
            Modifier.fillMaxWidth().testTag("history-correction-started"),
            label = { Text(stringResource(R.string.history_correction_started)) },
            enabled = !state.isMutating,
        )
        OffsetChoices(draft.startedOffsets, draft.startedOffset) {
            onAction(ActivityHistoryMutationAction.SelectStartedOffset(it))
        }
    }
    LifeTracingOutlinedTextField(
        draft.completedText,
        { onAction(ActivityHistoryMutationAction.EditCompleted(it)) },
        Modifier.fillMaxWidth().testTag("history-correction-completed"),
        label = { Text(stringResource(R.string.history_correction_completed)) },
        enabled = !state.isMutating,
    )
    OffsetChoices(draft.completedOffsets, draft.completedOffset) {
        onAction(ActivityHistoryMutationAction.SelectCompletedOffset(it))
    }
    LifeTracingOutlinedTextField(
        draft.shortComment,
        { onAction(ActivityHistoryMutationAction.EditComment(it)) },
        Modifier.fillMaxWidth().testTag("history-correction-comment"),
        label = { Text(stringResource(R.string.history_short_comment)) },
        enabled = !state.isMutating,
    )
    detail.fields.forEach { field ->
        CorrectionField(field, draft.values.getValue(field.id), state.isMutating, onAction)
    }
    state.issue?.let { MutationIssue(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        LifeTracingSecondaryButton(
            onClick = { onAction(ActivityHistoryMutationAction.Cancel) },
            enabled = !state.isMutating,
        ) { Text(stringResource(R.string.manual_history_cancel)) }
        LifeTracingPrimaryButton(
            onClick = { onAction(ActivityHistoryMutationAction.Save) },
            enabled = !state.isMutating,
            modifier = Modifier.testTag("history-correction-save"),
        ) {
            Text(stringResource(if (state.isMutating) R.string.history_saving else R.string.history_save_correction))
        }
    }
}

@Composable
private fun CorrectionField(
    field: ActivityHistoryField,
    draft: ActivityHistoryFieldDraft,
    isMutating: Boolean,
    onAction: (ActivityHistoryMutationAction) -> Unit,
) {
    HistoryCard {
        Text(field.name, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(
                R.string.history_configured_value,
                historyConfigured(
                    field.configuredValue,
                    field.categoryOptions.associate { it.id to it.label },
                    field.displayPrecision,
                    field.unit,
                ),
            ),
        )
        if (draft.missing) Text(stringResource(R.string.history_missing_value))
        when (field.type) {
            CustomFieldType.NUMBER ->
                LifeTracingOutlinedTextField(
                    draft.numberText,
                    { onAction(ActivityHistoryMutationAction.EditNumber(field.id, it)) },
                    Modifier.fillMaxWidth().testTag("history-correction-field-${field.id.value}"),
                    label = { Text(stringResource(R.string.manual_history_actual)) },
                    enabled = !isMutating,
                )
            CustomFieldType.TEXT ->
                LifeTracingOutlinedTextField(
                    draft.text,
                    { onAction(ActivityHistoryMutationAction.EditText(field.id, it)) },
                    Modifier.fillMaxWidth().testTag("history-correction-field-${field.id.value}"),
                    label = { Text(stringResource(R.string.manual_history_actual)) },
                    enabled = !isMutating,
                )
            CustomFieldType.CATEGORY ->
                field.categoryOptions.forEach { option ->
                    TextButton(
                        onClick = { onAction(ActivityHistoryMutationAction.SelectCategory(field.id, option.id)) },
                        enabled = !isMutating,
                        modifier = Modifier.testTag("history-correction-option-${option.id.value}"),
                    ) {
                        Text(
                            if (!draft.missing &&
                                draft.selectedOptionId == option.id
                            ) {
                                "✓ ${option.label}"
                            } else {
                                option.label
                            },
                        )
                    }
                }
        }
        LifeTracingSecondaryButton(
            onClick = { onAction(ActivityHistoryMutationAction.SetMissing(field.id, !draft.missing)) },
            enabled = !isMutating,
        ) {
            Text(
                stringResource(
                    if (draft.missing) R.string.manual_history_restore_value else R.string.manual_history_set_missing,
                ),
            )
        }
    }
}

@Composable
private fun OffsetChoices(
    offsets: List<ZoneOffset>,
    selected: ZoneOffset?,
    onSelect: (ZoneOffset) -> Unit,
) {
    if (offsets.size != 2) return
    Text(stringResource(R.string.manual_history_ambiguous_time), color = MaterialTheme.colorScheme.error)
    offsets.forEachIndexed { index, offset ->
        TextButton(onClick = { onSelect(offset) }) {
            Text(
                (if (selected == offset) "✓ " else "") +
                    stringResource(
                        if (index ==
                            0
                        ) {
                            R.string.manual_history_first_occurrence
                        } else {
                            R.string.manual_history_second_occurrence
                        },
                        "UTC${if (offset == ZoneOffset.UTC) "+00:00" else offset.id}",
                    ),
            )
        }
    }
}

@Composable
private fun DeleteConfirmation(
    detail: ActivityHistoryDetail,
    isMutating: Boolean,
    issue: ActivityHistoryMutationIssue?,
    onAction: (ActivityHistoryMutationAction) -> Unit,
) {
    Text(stringResource(R.string.history_delete_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.history_delete_message, detail.root.title))
    issue?.let { MutationIssue(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        LifeTracingSecondaryButton(
            onClick = { onAction(ActivityHistoryMutationAction.Cancel) },
            enabled = !isMutating,
        ) { Text(stringResource(R.string.manual_history_cancel)) }
        LifeTracingPrimaryButton(
            onClick = { onAction(ActivityHistoryMutationAction.ConfirmDelete) },
            enabled = !isMutating,
            modifier = Modifier.testTag("history-delete-confirm"),
        ) { Text(stringResource(if (isMutating) R.string.history_deleting else R.string.history_delete)) }
    }
}

@Composable
private fun MutationIssue(issue: ActivityHistoryMutationIssue) {
    val resource =
        when (issue) {
            ActivityHistoryMutationIssue.INVALID_DATE_TIME -> R.string.manual_history_invalid_datetime
            ActivityHistoryMutationIssue.NONEXISTENT_LOCAL_TIME -> R.string.manual_history_nonexistent_time
            ActivityHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME -> R.string.manual_history_ambiguous_time
            ActivityHistoryMutationIssue.FUTURE_COMPLETION -> R.string.manual_history_future_completion
            ActivityHistoryMutationIssue.REVERSED_INTERVAL -> R.string.manual_history_reversed_interval
            ActivityHistoryMutationIssue.INVALID_NUMBER -> R.string.manual_history_invalid_number
            ActivityHistoryMutationIssue.INVALID_CATEGORY -> R.string.manual_history_invalid_category
            ActivityHistoryMutationIssue.STALE -> R.string.history_changed_review
            ActivityHistoryMutationIssue.SAVE_FAILURE -> R.string.history_correction_failure
            ActivityHistoryMutationIssue.DELETE_FAILURE -> R.string.history_delete_failure
        }
    Text(stringResource(resource), color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun SequenceDetail(detail: SequenceHistoryDetail) {
    Text(detail.root.title, style = MaterialTheme.typography.headlineSmall)
    detail.root.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    Text(
        historyRootTimingInZone(
            detail.root.startedAt,
            detail.root.completedAt,
            detail.originalZoneId,
        ),
    )
    Text(
        stringResource(sequenceStatusResource(detail.root.status)),
        style = MaterialTheme.typography.labelLarge,
    )
    Text(stringResource(R.string.history_active_duration, durationText(detail.root.activeDuration)))
    Text(stringResource(R.string.history_pause_duration, durationText(detail.root.pauseDuration)))
    Text(stringResource(R.string.history_wall_duration, durationText(detail.root.wallDuration)))
    if (detail.fields.isNotEmpty()) {
        Text(stringResource(R.string.history_sequence_fields), style = MaterialTheme.typography.titleMedium)
        detail.fields.forEach { HistoryField(it) }
    }
    Text(stringResource(R.string.history_occurrences), style = MaterialTheme.typography.titleMedium)
    detail.occurrences.forEach { occurrence -> Occurrence(detail.originalZoneId, occurrence) }
    Text(stringResource(R.string.history_intervals), style = MaterialTheme.typography.titleMedium)
    detail.intervals.forEach { interval -> HistoryInterval(detail.originalZoneId, interval) }
}

@Composable
private fun Occurrence(
    zoneId: ZoneId,
    occurrence: SequenceHistoryOccurrence,
) {
    HistoryCard(modifier = Modifier.testTag("history-occurrence-${occurrence.occurrenceId.value}")) {
        Text(
            stringResource(R.string.history_occurrence, occurrence.runtimePosition + 1, occurrence.activity.title),
            style = MaterialTheme.typography.titleMedium,
        )
        occurrence.activity.shortComment?.let { Text(it) }
        Text(stringResource(occurrenceStatusResource(occurrence.status)), style = MaterialTheme.typography.labelLarge)
        if (occurrence.sourceSequenceSnapshotNodeId != null) Text(stringResource(R.string.history_source_step))
        occurrence.repeatIteration?.let { Text(stringResource(R.string.history_repeat_iteration, it)) }
        if (occurrence.isRuntimeAdded) Text(stringResource(R.string.history_runtime_added))
        occurrence.enteredAt?.let { Text(stringResource(R.string.history_entered, historyInstant(it, zoneId))) }
        occurrence.completedAt?.let {
            Text(
                stringResource(R.string.history_occurrence_completed, historyInstant(it, zoneId)),
            )
        }
        Text(historyTracking(occurrence.activity.timeTrackingMode, occurrence.activity.timerTarget))
        occurrence.activity.mainValue?.let { HistoryField(it) }
        if (occurrence.status == RuntimeOccurrenceStatus.DELETED_EXECUTION && occurrence.child == null) {
            Text(stringResource(R.string.history_deleted_child), style = MaterialTheme.typography.labelLarge)
        } else {
            occurrence.child?.let { child ->
                child.startedAt?.let {
                    Text(stringResource(R.string.history_child_started, historyInstant(it, zoneId)))
                }
                child.completedAt?.let {
                    Text(stringResource(R.string.history_child_completed, historyInstant(it, zoneId)))
                }
                Text(stringResource(R.string.history_actual_duration, durationOrMissing(child.activeDuration)))
                child.fields.forEach { HistoryField(it) }
            }
        }
    }
}

@Composable
private fun HistoryInterval(
    zoneId: ZoneId,
    interval: SequenceInterval,
) {
    HistoryCard(modifier = Modifier.testTag("history-interval-${interval.id.value}")) {
        Text(stringResource(intervalKindResource(interval.kind)), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.history_interval_started, historyInstant(interval.startedAt, zoneId)))
        Text(
            stringResource(
                R.string.history_interval_ended,
                interval.endedAt?.let { historyInstant(it, zoneId) } ?: stringResource(R.string.history_missing_value),
            ),
        )
    }
}

@Composable
private fun HistoryField(field: ActivityHistoryField) =
    HistoryFieldContent(
        field.name,
        field.unit,
        field.displayPrecision,
        field.categoryOptions.associate {
            it.id to
                it.label
        },
        field.configuredValue,
        field.actualValue,
    )

@Composable
private fun HistoryField(field: SequenceHistoryField) =
    HistoryFieldContent(
        field.name,
        field.unit,
        field.displayPrecision,
        field.categoryOptions.associate {
            it.id to
                it.label
        },
        field.configuredValue,
        field.actualValue,
    )

@Composable
private fun HistoryFieldContent(
    name: String,
    unit: String?,
    precision: Int?,
    labels: Map<*, String>,
    configured: Any,
    actual: Any,
) {
    val configuredText = historyConfigured(configured, labels, precision, unit)
    val actualText = historyActual(actual, precision, unit)
    Text(stringResource(R.string.history_field_value, name, actualText), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.history_configured_value, configuredText), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun historyConfigured(
    value: Any,
    labels: Map<*, String>,
    precision: Int?,
    unit: String?,
): String =
    when (value) {
        ActivityHistoryConfiguredValue.Missing,
        SequenceHistoryConfiguredValue.Missing,
        -> stringResource(R.string.history_missing_value)
        is ActivityHistoryConfiguredValue.Number -> numberText(value.scaledValue, precision, unit)
        is SequenceHistoryConfiguredValue.Number -> numberText(value.scaledValue, precision, unit)
        is ActivityHistoryConfiguredValue.Category ->
            labels[value.optionId] ?: stringResource(R.string.history_missing_value)
        is SequenceHistoryConfiguredValue.Category ->
            labels[value.optionId] ?: stringResource(R.string.history_missing_value)
        is ActivityHistoryConfiguredValue.Text -> value.value
        is SequenceHistoryConfiguredValue.Text -> value.value
        else -> stringResource(R.string.history_missing_value)
    }

@Composable
private fun historyActual(
    value: Any,
    precision: Int?,
    unit: String?,
): String =
    when (value) {
        ActivityHistoryActualValue.Missing,
        SequenceHistoryActualValue.Missing,
        -> stringResource(R.string.history_missing_value)
        is ActivityHistoryActualValue.Number -> numberText(value.scaledValue, precision, unit)
        is SequenceHistoryActualValue.Number -> numberText(value.scaledValue, precision, unit)
        is ActivityHistoryActualValue.Category -> value.label
        is SequenceHistoryActualValue.Category -> value.label
        is ActivityHistoryActualValue.Text -> value.value
        is SequenceHistoryActualValue.Text -> value.value
        else -> stringResource(R.string.history_missing_value)
    }

@Composable
private fun FailureCard(onRetry: () -> Unit) {
    HistoryCard(container = MaterialTheme.colorScheme.errorContainer) {
        Text(stringResource(R.string.history_read_failure), color = MaterialTheme.colorScheme.onErrorContainer)
        LifeTracingPrimaryButton(onClick = onRetry) { Text(stringResource(R.string.history_retry)) }
    }
}

@Composable
private fun HistoryCard(
    modifier: Modifier = Modifier,
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val cardModifier = modifier.fillMaxWidth()
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val colors = CardDefaults.cardColors(containerColor = container)
    if (onClick == null) {
        Card(cardModifier, border = border, colors = colors, elevation = CardDefaults.cardElevation(0.dp)) {
            HistoryCardContent(content)
        }
    } else {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            border = border,
            colors = colors,
            elevation = CardDefaults.cardElevation(0.dp),
        ) { HistoryCardContent(content) }
    }
}

@Composable
private fun HistoryCardContent(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(MaterialTheme.spacing.large),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        content = content,
    )
}

@Composable
private fun historyDateRange(
    start: LocalDate,
    end: LocalDate,
): String = "${historyDate(start)} – ${historyDate(end)}"

@Composable
private fun historyDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
}

@Composable
private fun historyRootTimingInZone(
    start: Instant?,
    end: Instant,
    zoneId: ZoneId,
): String {
    val endText = historyInstant(end, zoneId)
    return start?.let { "${historyInstant(it, zoneId)} – $endText" } ?: endText
}

@Composable
private fun historyInstant(
    instant: Instant,
    zoneId: ZoneId,
): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter
        .ofLocalizedDateTime(
            FormatStyle.MEDIUM,
            FormatStyle.SHORT,
        ).withLocale(locale)
        .format(instant.atZone(zoneId))
}

@Composable
private fun historyTracking(
    mode: TimeTrackingMode,
    target: Duration?,
): String =
    when (mode) {
        TimeTrackingMode.NO_LIVE_TRACKING -> stringResource(R.string.history_no_live)
        TimeTrackingMode.TIMER -> stringResource(R.string.history_timer_target, durationOrMissing(target))
        TimeTrackingMode.STOPWATCH -> stringResource(R.string.history_stopwatch)
    }

@Composable
private fun durationOrMissing(value: Duration?): String =
    value?.let(::durationText) ?: stringResource(R.string.history_missing_value)

private fun sequenceStatusResource(status: SequenceExecutionStatus): Int =
    when (status) {
        SequenceExecutionStatus.COMPLETED -> R.string.history_completed
        SequenceExecutionStatus.ENDED_EARLY -> R.string.history_ended_early
        SequenceExecutionStatus.RUNNING,
        SequenceExecutionStatus.PAUSED,
        -> error("Only terminal Sequence status is representable in History")
    }

private fun occurrenceStatusResource(status: RuntimeOccurrenceStatus): Int =
    when (status) {
        RuntimeOccurrenceStatus.NOT_STARTED -> R.string.history_occurrence_not_started
        RuntimeOccurrenceStatus.CURRENT -> R.string.history_occurrence_current
        RuntimeOccurrenceStatus.COMPLETED -> R.string.history_occurrence_performed
        RuntimeOccurrenceStatus.SKIPPED -> R.string.history_occurrence_skipped
        RuntimeOccurrenceStatus.DELETED_EXECUTION -> R.string.history_occurrence_deleted_execution
    }

private fun intervalKindResource(kind: SequenceIntervalKind): Int =
    when (kind) {
        SequenceIntervalKind.ACTIVE_STEP -> R.string.history_interval_active_step
        SequenceIntervalKind.STEP_PAUSE -> R.string.history_interval_step_pause
        SequenceIntervalKind.EXPLICIT_PAUSE -> R.string.history_interval_explicit_pause
        SequenceIntervalKind.IMPLICIT_IDLE -> R.string.history_interval_implicit_idle
        SequenceIntervalKind.TRANSITION_COUNTDOWN -> R.string.history_interval_transition_countdown
    }

private fun durationText(value: Duration): String = DateUtils.formatElapsedTime(value.seconds.coerceAtLeast(0))

private fun numberText(
    value: Long,
    precision: Int?,
    unit: String?,
): String =
    BigDecimal
        .valueOf(value, 3)
        .setScale(precision ?: 3)
        .stripTrailingZeros()
        .toPlainString() +
        unit?.let { " $it" }.orEmpty()
