@file:Suppress(
    "ComplexCondition",
    "CyclomaticComplexMethod",
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalMode
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.historicalTimestamp
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.temporal.ChronoField

@Composable
fun HistoryRoute(
    controller: HistoryController,
    onBack: () -> Unit,
    onAddCompletedActivity: () -> Unit,
    onOpenActivity: (CompletedActivityHistoryRoot) -> Unit,
    onOpenSequence: (CompletedSequenceHistoryRoot) -> Unit,
    isRouteVisible: Boolean = true,
    ownsRoute: () -> Boolean = { isRouteVisible },
) {
    LaunchedEffect(controller, isRouteVisible) {
        if (isRouteVisible && ownsRoute()) controller.onRouteEntered()
    }
    val state by controller.state.collectAsState()
    HistoryScreen(
        state,
        controller::dispatch,
        onBack,
        onAddCompletedActivity,
        onOpenActivity,
        onOpenSequence,
        isRouteVisible,
        readCurrent = { controller.state.value },
        ownsRoute = ownsRoute,
    )
}

@Composable
internal fun ActivityHistoryDetailRoute(
    session: ActivityHistoryMutationRouteSession,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDeleted: () -> Unit,
    isRouteVisible: Boolean = true,
    ownsRoute: () -> Boolean = { isRouteVisible },
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
    val admission = rememberHistoryAdmission(state, { controller.state.value }, ownsRoute, isRouteVisible)
    val focus =
        rememberHistoryFocus(
            isRouteVisible,
            state.load is HistoryDetailLoad.Loading,
            state.load is HistoryDetailLoad.Failure,
            ownsRoute,
        )
    LifeTracingAccessibleTargets {
        CompositionLocalProvider(LocalHistoryAdmission provides admission, LocalHistoryFocus provides focus) {
            ActivityHistoryMutationSurface(
                state,
                controller::dispatch,
                onBack = admission.callback { if (!controller.handleBack()) onBack() },
            )
        }
    }
}

@Composable
internal fun SequenceHistoryDetailRoute(
    session: SequenceHistoryMutationRouteSession,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    isRouteVisible: Boolean = true,
    ownsRoute: () -> Boolean = { isRouteVisible },
) {
    val controller = session.controller
    val state by controller.state.collectAsState()
    BackHandler(
        enabled =
            state.isMutating ||
                state.timingDraft != null ||
                state.childDeletionProposal != null ||
                state.structuralTarget != null,
    ) {
        controller.handleBack()
    }
    LaunchedEffect(state.refreshGeneration) {
        session.deliverRefresh(state.refreshGeneration, onRefresh)
    }
    val admission = rememberHistoryAdmission(state, { controller.state.value }, ownsRoute, isRouteVisible)
    val focus =
        rememberHistoryFocus(
            isRouteVisible,
            state.load is HistoryDetailLoad.Loading,
            state.load is HistoryDetailLoad.Failure,
            ownsRoute,
        )
    LifeTracingAccessibleTargets {
        CompositionLocalProvider(LocalHistoryAdmission provides admission, LocalHistoryFocus provides focus) {
            HistoryDetailSurface(
                onBack = admission.callback { if (!controller.handleBack()) onBack() },
                load = state.load,
                onRetry = admission.callback { controller.dispatch(SequenceHistoryMutationAction.Retry) },
            ) { SequenceHistoryMutationSurface(it, state, controller::dispatch) }
        }
    }
}

@Composable
internal fun HistoryScreen(
    state: HistoryPresentationState,
    onAction: (HistoryAction) -> Unit,
    onBack: () -> Unit = {},
    onAddCompletedActivity: () -> Unit = {},
    onOpenActivity: (CompletedActivityHistoryRoot) -> Unit = {},
    onOpenSequence: (CompletedSequenceHistoryRoot) -> Unit = {},
    isRouteVisible: Boolean = true,
    readCurrent: () -> HistoryPresentationState = { state },
    ownsRoute: () -> Boolean = { isRouteVisible },
) {
    val admission = rememberHistoryAdmission(state, readCurrent, ownsRoute, isRouteVisible)
    val focus =
        rememberHistoryFocus(
            isRouteVisible,
            state.load is HistoryRootsLoad.Loading,
            state.load is HistoryRootsLoad.Failure || state.discoveryFailure != null,
            ownsRoute,
        )
    LifeTracingAccessibleTargets {
        CompositionLocalProvider(LocalHistoryAdmission provides admission, LocalHistoryFocus provides focus) {
            HistoryScreenContent(state, onAction, onBack, onAddCompletedActivity, onOpenActivity, onOpenSequence)
        }
    }
}

@Composable
private fun HistoryScreenContent(
    state: HistoryPresentationState,
    onAction: (HistoryAction) -> Unit,
    onBack: () -> Unit,
    onAddCompletedActivity: () -> Unit,
    onOpenActivity: (CompletedActivityHistoryRoot) -> Unit,
    onOpenSequence: (CompletedSequenceHistoryRoot) -> Unit,
) {
    val admission = LocalHistoryAdmission.current
    val focus = LocalHistoryFocus.current
    val back = admission.callback(block = onBack)
    val add = admission.callback(block = onAddCompletedActivity)
    val older = admission.callback { onAction(HistoryAction.Older) }
    val newer = admission.callback(state.canNavigateNewer) { onAction(HistoryAction.Newer) }
    val retry =
        admission.callback(state.load is HistoryRootsLoad.Failure || state.discoveryFailure != null) {
            onAction(HistoryAction.Retry)
        }
    val more = admission.callback(state.canLoadMore) { onAction(HistoryAction.LoadMore) }
    val olderWindow = state.window.older()
    val newerWindow = state.window.newer(state.newestDate)
    val olderLabel =
        stringResource(
            R.string.history_older_period,
            historyDateRange(olderWindow.startDate, olderWindow.endDate),
        )
    val newerLabel =
        newerWindow?.let {
            stringResource(R.string.history_newer_period, historyDateRange(it.startDate, it.endDate))
        } ?: stringResource(R.string.history_newer)
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                Text(
                    stringResource(R.string.history_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(
                    onClick = back,
                    modifier = historyFocusTarget(focus.back).historyActionSemantics(back).testTag("history-back"),
                ) {
                    Text(stringResource(R.string.history_back))
                }
            }
            LifeTracingPrimaryButton(
                onClick = add,
                modifier = historyFocusTarget().historyActionSemantics(add).testTag("history-add-completed-activity"),
            ) { Text(stringResource(R.string.manual_history_title)) }
            Text(
                historyDateRange(state.window.startDate, state.window.endDate),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                LifeTracingSecondaryButton(
                    onClick = older,
                    modifier = historyFocusTarget().historyActionSemantics(older, olderLabel).testTag("history-older"),
                ) { Text(olderLabel) }
                LifeTracingSecondaryButton(
                    onClick = newer,
                    enabled = state.canNavigateNewer,
                    modifier =
                        historyFocusTarget(enabled = state.canNavigateNewer)
                            .historyActionSemantics(newer, newerLabel)
                            .testTag("history-newer"),
                ) { Text(newerLabel) }
            }
            when (val load = state.load) {
                HistoryRootsLoad.Loading -> HistoryCard { HistoryReadStatus(R.string.history_loading) }
                HistoryRootsLoad.Empty -> {
                    state.discoveryFailure?.let { FailureCard(onRetry = retry) }
                        ?: HistoryCard { HistoryReadStatus(R.string.history_empty) }
                }
                is HistoryRootsLoad.Failure -> FailureCard(onRetry = retry)
                is HistoryRootsLoad.Content ->
                    {
                        state.discoveryFailure?.let { FailureCard(onRetry = retry) }
                        Text(
                            stringResource(R.string.history_results, load.roots.size),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        // Canonical page order already groups dates newest first. No UI sorting or history reads.
                        load.roots.groupBy(CompletedHistoryRoot::primaryLocalDate).forEach { (date, roots) ->
                            key(date) {
                                Text(
                                    historyDate(date),
                                    modifier = Modifier.semantics { heading() },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            roots.forEach { root ->
                                key(root.identity()) { HistoryRootRow(root, onOpenActivity, onOpenSequence) }
                            }
                        }
                        if (state.canLoadMore) {
                            LifeTracingSecondaryButton(
                                onClick = more,
                                modifier =
                                    historyFocusTarget()
                                        .historyActionSemantics(
                                            more,
                                        ).testTag("history-load-more"),
                            ) {
                                Text(stringResource(R.string.manual_history_load_more))
                            }
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
    val identity = root.identity()
    val record =
        stringResource(
            if (root is CompletedActivityHistoryRoot) {
                R.string.history_activity_record
            } else {
                R.string.history_sequence_record
            },
            identity.executionId,
        )
    val open =
        LocalHistoryAdmission.current.callback {
            when (root) {
                is CompletedActivityHistoryRoot -> onOpenActivity(root)
                is CompletedSequenceHistoryRoot -> onOpenSequence(root)
            }
        }
    val description = historyRootDescription(root, record)
    val openLabel = stringResource(R.string.history_open_record, record)
    HistoryCard(
        modifier =
            historyFocusTarget()
                .semantics { contentDescription = description }
                .historyActionSemantics(open, openLabel)
                .testTag(
                    when (root) {
                        is CompletedActivityHistoryRoot -> "history-activity-${root.executionId.value}"
                        is CompletedSequenceHistoryRoot -> "history-sequence-${root.executionId.value}"
                    },
                ),
        onClick = open,
    ) {
        when (root) {
            is CompletedActivityHistoryRoot -> {
                Text(root.title, style = MaterialTheme.typography.titleMedium)
                root.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(historyRootTimingInZone(root.startedAt, root.completedAt, root.originalZoneId))
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
                Text(historyRootTimingInZone(root.startedAt, root.completedAt, root.originalZoneId))
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
private fun historyRootDescription(
    root: CompletedHistoryRoot,
    record: String,
): String {
    val title =
        when (root) {
            is CompletedActivityHistoryRoot -> root.title
            is CompletedSequenceHistoryRoot -> root.title
        }
    val timing =
        when (root) {
            is CompletedActivityHistoryRoot ->
                historyRootTimingInZone(
                    root.startedAt,
                    root.completedAt,
                    root.originalZoneId,
                )
            is CompletedSequenceHistoryRoot ->
                historyRootTimingInZone(
                    root.startedAt,
                    root.completedAt,
                    root.originalZoneId,
                )
        }
    val facts =
        when (root) {
            is CompletedActivityHistoryRoot ->
                listOf(
                    stringResource(R.string.history_completed),
                    stringResource(R.string.history_active_duration, durationOrMissing(root.activeDuration)),
                    historyTracking(root.timeTrackingMode, root.timerTarget),
                    root.shortComment.orEmpty(),
                )
            is CompletedSequenceHistoryRoot ->
                listOf(
                    stringResource(sequenceStatusResource(root.status)),
                    stringResource(R.string.history_active_duration, durationText(root.activeDuration)),
                    stringResource(R.string.history_pause_duration, durationText(root.pauseDuration)),
                    stringResource(R.string.history_wall_duration, durationText(root.wallDuration)),
                    root.shortComment.orEmpty(),
                )
        }
    return (listOf(record, title, timing) + facts).filter(String::isNotBlank).joinToString(". ")
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
            TextButton(
                onClick = onBack,
                modifier = historyFocusTarget(LocalHistoryFocus.current.back).historyActionSemantics(onBack),
            ) { Text(stringResource(R.string.history_back)) }
            when (load) {
                HistoryDetailLoad.Loading -> HistoryCard { HistoryReadStatus(R.string.history_detail_loading) }
                HistoryDetailLoad.Unavailable -> HistoryCard { HistoryReadStatus(R.string.history_unavailable) }
                is HistoryDetailLoad.Failure -> FailureCard(onRetry)
                is HistoryDetailLoad.Content -> content(load.value)
            }
        }
    }
}

@Composable
internal fun ActivityDetail(detail: ActivityHistoryDetail) {
    Text(detail.root.title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
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
    Text(
        stringResource(R.string.history_fields),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
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
            TextButton(
                onClick = onBack,
                enabled = !state.isMutating,
                modifier = historyFocusTarget(LocalHistoryFocus.current.back).historyActionSemantics(onBack),
            ) { Text(stringResource(R.string.history_back)) }
            when (val load = state.load) {
                HistoryDetailLoad.Loading -> HistoryCard { HistoryReadStatus(R.string.history_detail_loading) }
                HistoryDetailLoad.Unavailable -> HistoryCard { HistoryReadStatus(R.string.history_unavailable) }
                is HistoryDetailLoad.Failure -> {
                    val retry = LocalHistoryAdmission.current.callback { onAction(ActivityHistoryMutationAction.Retry) }
                    FailureCard(retry)
                }
                is HistoryDetailLoad.Content -> {
                    when {
                        state.overlapWarning ->
                            OverlapWarning(state.isMutating, onAction)
                        state.draft != null -> CorrectionEditor(load.value, state, onAction)
                        state.deleteConfirmation ->
                            DeleteConfirmation(load.value, state.isMutating, state.issue, onAction)
                        else -> {
                            state.issue?.let { MutationIssue(it) }
                            HistoryReadStatus(R.string.history_detail_ready)
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
private fun OverlapWarning(
    isMutating: Boolean,
    onAction: (ActivityHistoryMutationAction) -> Unit,
) {
    Text(stringResource(R.string.history_overlap_warning), color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        LifeTracingSecondaryButton(
            onClick = { onAction(ActivityHistoryMutationAction.CancelOverlap) },
            enabled = !isMutating,
            modifier = Modifier.testTag("history-overlap-cancel"),
        ) { Text(stringResource(R.string.manual_history_cancel)) }
        LifeTracingPrimaryButton(
            onClick = { onAction(ActivityHistoryMutationAction.ProceedOverlap) },
            enabled = !isMutating,
            modifier = Modifier.testTag("history-overlap-proceed"),
        ) { Text(stringResource(R.string.history_overlap_proceed)) }
    }
}

@Composable
private fun CorrectionEditor(
    detail: ActivityHistoryDetail,
    state: ActivityHistoryMutationState,
    onAction: (ActivityHistoryMutationAction) -> Unit,
) {
    val draft = requireNotNull(state.draft)
    Text(detail.root.title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
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
    enabled: Boolean = true,
    onSelect: (ZoneOffset) -> Unit,
) {
    if (offsets.size != 2) return
    Text(stringResource(R.string.manual_history_ambiguous_time), color = MaterialTheme.colorScheme.error)
    offsets.forEachIndexed { index, offset ->
        TextButton(onClick = { onSelect(offset) }, enabled = enabled) {
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
private fun SequenceHistoryMutationSurface(
    detail: SequenceHistoryDetail,
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
) {
    val occurrenceDescriptors =
        remember(detail.occurrences) { SequenceHistoryProposalBuilder.occurrenceDescriptors(detail) }
    when {
        state.childDeletionProposal != null -> SequenceChildDeletionConfirmation(detail, state, onAction)
        state.structuralTarget != null && state.structuralProposal == null ->
            StructuralModeChooser(state, onAction)
        state.structuralProposal != null && !state.structuralProposal.isConfirmable ->
            OwnerlessPlacementChooser(detail, state, onAction)
        state.structuralProposal != null -> StructuralReview(detail, occurrenceDescriptors, state, onAction)
        state.timingProposal != null -> TimingReview(detail, occurrenceDescriptors, state, onAction)
        state.timingDraft != null -> TimingEditor(detail, occurrenceDescriptors, state, onAction)
        else -> {
            state.issue?.let { SequenceMutationIssue(it) }
            SequenceDetail(
                detail,
                onDeleteChild = { onAction(SequenceHistoryMutationAction.RequestChildDeletion(it)) },
                onRemoveOccurrence = { onAction(SequenceHistoryMutationAction.BeginStructuralRemoval(it)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                LifeTracingSecondaryButton(
                    onClick = { onAction(SequenceHistoryMutationAction.BeginTiming) },
                    enabled = !state.isMutating,
                    modifier = Modifier.testTag("sequence-history-timing"),
                ) { Text(stringResource(R.string.sequence_history_correct_timing)) }
            }
        }
    }
}

@Composable
private fun TimingEditor(
    detail: SequenceHistoryDetail,
    occurrenceDescriptors: Map<SequenceOccurrenceId, SequenceHistoryOccurrenceDescriptor>,
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
) {
    val draft = requireNotNull(state.timingDraft)
    Text(detail.root.title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.history_event_zone, detail.originalZoneId.id))
    draft.timestamps.forEach { (target, value) ->
        LifeTracingOutlinedTextField(
            value.text,
            { onAction(SequenceHistoryMutationAction.EditTimestamp(target, it)) },
            Modifier.fillMaxWidth().testTag("sequence-history-timestamp-${timestampTargetKey(target)}"),
            label = { Text(timestampTargetLabel(occurrenceDescriptors, target)) },
            enabled = !state.isMutating,
        )
        OffsetChoices(value.validOffsets, value.selectedOffset, enabled = !state.isMutating) {
            onAction(SequenceHistoryMutationAction.SelectTimestampOffset(target, it))
        }
    }
    if (state.noTimingChanges) {
        Text(stringResource(R.string.sequence_history_no_timing_changes))
    }
    state.issue?.let { SequenceMutationIssue(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        LifeTracingSecondaryButton(
            onClick = { onAction(SequenceHistoryMutationAction.Cancel) },
            enabled = !state.isMutating,
        ) { Text(stringResource(R.string.manual_history_cancel)) }
        LifeTracingPrimaryButton(
            onClick = { onAction(SequenceHistoryMutationAction.ReviewTiming) },
            enabled = !state.isMutating,
            modifier = Modifier.testTag("sequence-history-review-timing"),
        ) { Text(stringResource(R.string.sequence_history_review_timing)) }
    }
}

@Composable
private fun TimingReview(
    detail: SequenceHistoryDetail,
    occurrenceDescriptors: Map<SequenceOccurrenceId, SequenceHistoryOccurrenceDescriptor>,
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
) {
    val proposal = requireNotNull(state.timingProposal)
    Text(stringResource(R.string.sequence_history_timing_review), style = MaterialTheme.typography.headlineSmall)
    ProposalChanges(detail, occurrenceDescriptors, proposal.changes)
    state.issue?.let { SequenceMutationIssue(it) }
    if (state.overlapWarning) {
        Text(stringResource(R.string.history_overlap_warning), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            LifeTracingSecondaryButton(
                onClick = { onAction(SequenceHistoryMutationAction.CancelOverlap) },
                enabled = !state.isMutating,
                modifier = Modifier.testTag("sequence-history-overlap-cancel"),
            ) { Text(stringResource(R.string.manual_history_cancel)) }
            LifeTracingPrimaryButton(
                onClick = { onAction(SequenceHistoryMutationAction.ProceedOverlap) },
                enabled = !state.isMutating,
                modifier = Modifier.testTag("sequence-history-overlap-proceed"),
            ) { Text(stringResource(R.string.history_overlap_proceed)) }
        }
    } else {
        ReviewActions(
            isMutating = state.isMutating,
            confirmLabel = R.string.history_save_correction,
            confirmTag = "sequence-history-confirm-timing",
            onCancel = { onAction(SequenceHistoryMutationAction.Cancel) },
            onConfirm = { onAction(SequenceHistoryMutationAction.ConfirmTiming) },
        )
    }
}

@Composable
private fun SequenceChildDeletionConfirmation(
    detail: SequenceHistoryDetail,
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
) {
    Text(stringResource(R.string.sequence_history_delete_child_title), style = MaterialTheme.typography.headlineSmall)
    OccurrenceTarget(requireNotNull(state.childDeletionTarget))
    Text(stringResource(R.string.sequence_history_delete_child_message, detail.root.title))
    state.issue?.let { SequenceMutationIssue(it) }
    ReviewActions(
        isMutating = state.isMutating,
        confirmLabel = if (state.isMutating) R.string.history_deleting else R.string.history_delete,
        confirmTag = "sequence-history-confirm-delete-child",
        onCancel = { onAction(SequenceHistoryMutationAction.Cancel) },
        onConfirm = { onAction(SequenceHistoryMutationAction.ConfirmChildDeletion) },
    )
}

@Composable
private fun StructuralModeChooser(
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
) {
    Text(stringResource(R.string.sequence_history_structural_title), style = MaterialTheme.typography.headlineSmall)
    OccurrenceTarget(requireNotNull(state.structuralTargetDescriptor))
    Text(stringResource(R.string.sequence_history_structural_mode_prompt))
    state.issue?.let { SequenceMutationIssue(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        LifeTracingSecondaryButton(
            onClick = { onAction(SequenceHistoryMutationAction.Cancel) },
            enabled = !state.isMutating,
        ) { Text(stringResource(R.string.manual_history_cancel)) }
        LifeTracingSecondaryButton(
            onClick = {
                onAction(
                    SequenceHistoryMutationAction.ChooseStructuralMode(SequenceHistoryStructuralRemovalMode.LEAVE_GAP),
                )
            },
            enabled = !state.isMutating,
            modifier = Modifier.testTag("sequence-history-leave-gap"),
        ) { Text(stringResource(R.string.sequence_history_leave_gap)) }
        LifeTracingPrimaryButton(
            onClick = {
                onAction(
                    SequenceHistoryMutationAction.ChooseStructuralMode(SequenceHistoryStructuralRemovalMode.CLOSE_GAP),
                )
            },
            enabled = !state.isMutating,
            modifier = Modifier.testTag("sequence-history-close-gap"),
        ) { Text(stringResource(R.string.sequence_history_close_gap)) }
    }
}

@Composable
private fun OwnerlessPlacementChooser(
    detail: SequenceHistoryDetail,
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
) {
    val proposal = requireNotNull(state.structuralProposal)
    Text(stringResource(R.string.sequence_history_ownerless_title), style = MaterialTheme.typography.headlineSmall)
    OccurrenceTarget(proposal.target)
    Text(stringResource(R.string.sequence_history_ownerless_message))
    state.issue?.let { SequenceMutationIssue(it) }
    proposal.ownerlessChoices.filter { it.selectedPlacement == null }.forEach { choice ->
        HistoryCard {
            Text(
                stringResource(
                    R.string.sequence_history_ownerless_interval,
                    stringResource(intervalKindResource(choice.kind)),
                    choice.intervalId.value,
                ),
            )
            LifeTracingSecondaryButton(
                onClick = {
                    onAction(
                        SequenceHistoryMutationAction.PlaceOwnerlessInterval(
                            choice.intervalId,
                            OwnerlessIntervalPlacement.FIXED,
                        ),
                    )
                },
                enabled = !state.isMutating,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("sequence-history-ownerless-${choice.intervalId.value}-fixed"),
            ) {
                Text(
                    stringResource(
                        R.string.sequence_history_ownerless_fixed_choice,
                        mutationPreviewInterval(choice.fixedStartedAt, choice.fixedEndedAt, detail.originalZoneId),
                    ),
                )
            }
            LifeTracingPrimaryButton(
                onClick = {
                    onAction(
                        SequenceHistoryMutationAction.PlaceOwnerlessInterval(
                            choice.intervalId,
                            OwnerlessIntervalPlacement.TRANSLATED,
                        ),
                    )
                },
                enabled = !state.isMutating,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("sequence-history-ownerless-${choice.intervalId.value}-translated"),
            ) {
                Text(
                    stringResource(
                        R.string.sequence_history_ownerless_translated_choice,
                        mutationPreviewInterval(
                            choice.translatedStartedAt,
                            choice.translatedEndedAt,
                            detail.originalZoneId,
                        ),
                    ),
                )
            }
        }
    }
    LifeTracingSecondaryButton(
        onClick = { onAction(SequenceHistoryMutationAction.Cancel) },
        enabled = !state.isMutating,
    ) { Text(stringResource(R.string.manual_history_cancel)) }
}

@Composable
private fun StructuralReview(
    detail: SequenceHistoryDetail,
    occurrenceDescriptors: Map<SequenceOccurrenceId, SequenceHistoryOccurrenceDescriptor>,
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
) {
    val proposal = requireNotNull(state.structuralProposal)
    Text(stringResource(R.string.sequence_history_structural_review), style = MaterialTheme.typography.headlineSmall)
    OccurrenceTarget(proposal.target)
    Text(
        stringResource(
            if (proposal.mode == SequenceHistoryStructuralRemovalMode.LEAVE_GAP) {
                R.string.sequence_history_leave_gap
            } else {
                R.string.sequence_history_close_gap
            },
        ),
    )
    Text(
        stringResource(
            R.string.sequence_history_final_root_end,
            mutationPreviewInstant(requireNotNull(proposal.command).finalEndedAt, detail.originalZoneId),
        ),
    )
    ProposalChanges(detail, occurrenceDescriptors, proposal.changes)
    state.issue?.let { SequenceMutationIssue(it) }
    if (state.overlapWarning) {
        Text(stringResource(R.string.history_overlap_warning), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            LifeTracingSecondaryButton(
                onClick = { onAction(SequenceHistoryMutationAction.CancelOverlap) },
                enabled = !state.isMutating,
                modifier = Modifier.testTag("sequence-history-overlap-cancel"),
            ) { Text(stringResource(R.string.manual_history_cancel)) }
            LifeTracingPrimaryButton(
                onClick = { onAction(SequenceHistoryMutationAction.ProceedOverlap) },
                enabled = !state.isMutating,
                modifier = Modifier.testTag("sequence-history-overlap-proceed"),
            ) { Text(stringResource(R.string.history_overlap_proceed)) }
        }
    } else {
        ReviewActions(
            isMutating = state.isMutating,
            confirmLabel = R.string.sequence_history_confirm_structural,
            confirmTag = "sequence-history-confirm-structural",
            onCancel = { onAction(SequenceHistoryMutationAction.Cancel) },
            onConfirm = { onAction(SequenceHistoryMutationAction.ConfirmStructuralRemoval) },
        )
    }
}

@Composable
private fun ReviewActions(
    isMutating: Boolean,
    confirmLabel: Int,
    confirmTag: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (isMutating) Text(stringResource(R.string.history_saving))
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        LifeTracingSecondaryButton(onClick = onCancel, enabled = !isMutating) {
            Text(stringResource(R.string.manual_history_cancel))
        }
        LifeTracingPrimaryButton(
            onClick = onConfirm,
            enabled = !isMutating,
            modifier = Modifier.testTag(confirmTag),
        ) { Text(stringResource(confirmLabel)) }
    }
}

@Composable
private fun ProposalChanges(
    detail: SequenceHistoryDetail,
    occurrenceDescriptors: Map<SequenceOccurrenceId, SequenceHistoryOccurrenceDescriptor>,
    changes: List<SequenceHistoryPreviewChange>,
) {
    Text(stringResource(R.string.sequence_history_changes), style = MaterialTheme.typography.titleMedium)
    changes.forEach { change ->
        when (change) {
            is SequenceHistoryPreviewChange.Timestamp ->
                Text(
                    stringResource(
                        R.string.sequence_history_change,
                        timestampTargetLabel(occurrenceDescriptors, change.target),
                        mutationPreviewInstant(change.before, detail.originalZoneId),
                        mutationPreviewInstant(change.after, detail.originalZoneId),
                    ),
                )
            is SequenceHistoryPreviewChange.RemovedOccurrence ->
                Text(
                    stringResource(
                        R.string.sequence_history_removed_occurrence,
                        occurrenceTargetText(change.occurrence),
                    ),
                )
            is SequenceHistoryPreviewChange.ChildPauseTimestamp ->
                Text(
                    stringResource(
                        R.string.sequence_history_change,
                        stringResource(
                            R.string.sequence_history_child_pause,
                            occurrenceTargetText(change.occurrence),
                            change.pauseId.value,
                        ),
                        mutationPreviewInterval(change.beforeStartedAt, change.beforeEndedAt, detail.originalZoneId),
                        mutationPreviewInterval(change.afterStartedAt, change.afterEndedAt, detail.originalZoneId),
                    ),
                )
            is SequenceHistoryPreviewChange.RemovedInterval ->
                Text(
                    stringResource(
                        R.string.sequence_history_removed_interval,
                        stringResource(intervalKindResource(change.interval.kind)),
                        change.interval.intervalId.value,
                        mutationPreviewInterval(
                            change.interval.startedAt,
                            change.interval.endedAt,
                            detail.originalZoneId,
                        ),
                    ),
                )
            is SequenceHistoryPreviewChange.OwnerlessPlacement ->
                Text(
                    stringResource(
                        R.string.sequence_history_ownerless_selection,
                        stringResource(intervalKindResource(change.kind)),
                        change.intervalId.value,
                        stringResource(
                            if (change.placement == OwnerlessIntervalPlacement.FIXED) {
                                R.string.sequence_history_fixed
                            } else {
                                R.string.sequence_history_translated
                            },
                        ),
                        mutationPreviewInterval(
                            change.resultingStartedAt,
                            change.resultingEndedAt,
                            detail.originalZoneId,
                        ),
                    ),
                )
        }
    }
}

@Composable
private fun timestampTargetLabel(
    occurrenceDescriptors: Map<SequenceOccurrenceId, SequenceHistoryOccurrenceDescriptor>,
    target: SequenceHistoryTimestampTarget,
): String =
    when (target) {
        SequenceHistoryTimestampTarget.RootStartedAt -> stringResource(R.string.sequence_history_root_started)
        SequenceHistoryTimestampTarget.RootEndedAt -> stringResource(R.string.sequence_history_root_ended)
        is SequenceHistoryTimestampTarget.OccurrenceEnteredAt ->
            stringResource(
                R.string.sequence_history_occurrence_entered,
                occurrenceTargetText(occurrenceDescriptors.getValue(target.occurrenceId)),
            )
        is SequenceHistoryTimestampTarget.OccurrenceCompletedAt ->
            stringResource(
                R.string.sequence_history_occurrence_completed,
                occurrenceTargetText(occurrenceDescriptors.getValue(target.occurrenceId)),
            )
        is SequenceHistoryTimestampTarget.ChildStartedAt ->
            stringResource(
                R.string.sequence_history_child_started,
                occurrenceTargetText(occurrenceDescriptors.getValue(target.occurrenceId)),
            )
        is SequenceHistoryTimestampTarget.ChildCompletedAt ->
            stringResource(
                R.string.sequence_history_child_completed,
                occurrenceTargetText(occurrenceDescriptors.getValue(target.occurrenceId)),
            )
        is SequenceHistoryTimestampTarget.IntervalStartedAt ->
            stringResource(
                R.string.sequence_history_interval_started,
                target.intervalId.value,
            )
        is SequenceHistoryTimestampTarget.IntervalEndedAt ->
            stringResource(
                R.string.sequence_history_interval_ended,
                target.intervalId.value,
            )
    }

@Composable
private fun OccurrenceTarget(target: SequenceHistoryOccurrenceDescriptor) {
    Text(occurrenceTargetText(target), style = MaterialTheme.typography.titleMedium)
    if (target.hasSourceStep) Text(stringResource(R.string.history_source_step))
    target.repeatIteration?.let { Text(stringResource(R.string.history_repeat_iteration, it)) }
    if (target.isRuntimeAdded) Text(stringResource(R.string.history_runtime_added))
}

@Composable
private fun occurrenceTargetText(target: SequenceHistoryOccurrenceDescriptor): String =
    stringResource(R.string.history_occurrence, target.runtimePosition + 1, target.activityTitle)

@Composable
private fun mutationPreviewInterval(
    startedAt: Instant,
    endedAt: Instant?,
    zoneId: ZoneId,
): String =
    "${mutationPreviewInstant(startedAt, zoneId)} – " +
        (endedAt?.let { mutationPreviewInstant(it, zoneId) } ?: stringResource(R.string.history_missing_value))

private fun timestampTargetKey(target: SequenceHistoryTimestampTarget): String =
    when (target) {
        SequenceHistoryTimestampTarget.RootStartedAt -> "root-started"
        SequenceHistoryTimestampTarget.RootEndedAt -> "root-ended"
        is SequenceHistoryTimestampTarget.OccurrenceEnteredAt -> "occurrence-${target.occurrenceId.value}-entered"
        is SequenceHistoryTimestampTarget.OccurrenceCompletedAt -> "occurrence-${target.occurrenceId.value}-completed"
        is SequenceHistoryTimestampTarget.ChildStartedAt -> "child-${target.occurrenceId.value}-started"
        is SequenceHistoryTimestampTarget.ChildCompletedAt -> "child-${target.occurrenceId.value}-completed"
        is SequenceHistoryTimestampTarget.IntervalStartedAt -> "interval-${target.intervalId.value}-started"
        is SequenceHistoryTimestampTarget.IntervalEndedAt -> "interval-${target.intervalId.value}-ended"
    }

@Composable
private fun SequenceMutationIssue(issue: SequenceHistoryMutationIssue) {
    val resource =
        when (issue) {
            SequenceHistoryMutationIssue.INVALID_DATE_TIME -> R.string.manual_history_invalid_datetime
            SequenceHistoryMutationIssue.NONEXISTENT_LOCAL_TIME -> R.string.manual_history_nonexistent_time
            SequenceHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME -> R.string.manual_history_ambiguous_time
            SequenceHistoryMutationIssue.INVALID_PROPOSAL -> R.string.sequence_history_invalid_proposal
            SequenceHistoryMutationIssue.STALE -> R.string.history_changed_review
            SequenceHistoryMutationIssue.READ_FAILURE -> R.string.history_read_failure
            SequenceHistoryMutationIssue.TIMING_FAILURE -> R.string.sequence_history_timing_failure
            SequenceHistoryMutationIssue.CHILD_DELETION_FAILURE -> R.string.sequence_history_child_deletion_failure
            SequenceHistoryMutationIssue.STRUCTURAL_FAILURE -> R.string.sequence_history_structural_failure
        }
    Text(stringResource(resource), color = MaterialTheme.colorScheme.error)
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
internal fun SequenceDetail(
    detail: SequenceHistoryDetail,
    onDeleteChild: ((com.alexandr5476.lifetracing.domain.SequenceOccurrenceId) -> Unit)? = null,
    onRemoveOccurrence: ((com.alexandr5476.lifetracing.domain.SequenceOccurrenceId) -> Unit)? = null,
) {
    Text(detail.root.title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
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
    detail.occurrences.forEach { occurrence ->
        Occurrence(detail.originalZoneId, occurrence, onDeleteChild, onRemoveOccurrence)
    }
    Text(stringResource(R.string.history_intervals), style = MaterialTheme.typography.titleMedium)
    detail.intervals.forEach { interval -> HistoryInterval(detail.originalZoneId, interval) }
}

@Composable
private fun Occurrence(
    zoneId: ZoneId,
    occurrence: SequenceHistoryOccurrence,
    onDeleteChild: ((com.alexandr5476.lifetracing.domain.SequenceOccurrenceId) -> Unit)?,
    onRemoveOccurrence: ((com.alexandr5476.lifetracing.domain.SequenceOccurrenceId) -> Unit)?,
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
                    Text(stringResource(R.string.history_child_started, historyInstant(it, child.originalZoneId)))
                }
                child.completedAt?.let {
                    Text(stringResource(R.string.history_child_completed, historyInstant(it, child.originalZoneId)))
                }
                Text(stringResource(R.string.history_actual_duration, durationOrMissing(child.activeDuration)))
                child.fields.forEach { HistoryField(it) }
            }
        }
        if (
            onDeleteChild != null &&
            occurrence.status == RuntimeOccurrenceStatus.COMPLETED &&
            !occurrence.isDeletedFromHistory &&
            occurrence.childMutationFacts != null
        ) {
            LifeTracingSecondaryButton(
                onClick = { onDeleteChild(occurrence.occurrenceId) },
                modifier = Modifier.testTag("sequence-history-delete-child-${occurrence.occurrenceId.value}"),
            ) { Text(stringResource(R.string.sequence_history_delete_child)) }
        }
        if (
            onRemoveOccurrence != null &&
            occurrence.status in setOf(RuntimeOccurrenceStatus.COMPLETED, RuntimeOccurrenceStatus.DELETED_EXECUTION) &&
            !occurrence.isDeletedFromHistory &&
            occurrence.childMutationFacts != null
        ) {
            LifeTracingSecondaryButton(
                onClick = { onRemoveOccurrence(occurrence.occurrenceId) },
                modifier = Modifier.testTag("sequence-history-remove-occurrence-${occurrence.occurrenceId.value}"),
            ) { Text(stringResource(R.string.sequence_history_remove_occurrence)) }
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
    val actualDescription = stringResource(R.string.history_actual_field, name, actualText)
    val configuredDescription = stringResource(R.string.history_configured_value, configuredText)
    Column(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$actualDescription. $configuredDescription"
        },
    ) {
        Text(
            stringResource(R.string.history_field_value, name, actualText),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.history_configured_value, configuredText),
            style = MaterialTheme.typography.bodySmall,
        )
    }
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
private fun HistoryReadStatus(resource: Int) {
    Text(stringResource(resource), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun FailureCard(onRetry: () -> Unit) {
    HistoryCard(container = MaterialTheme.colorScheme.errorContainer) {
        Text(
            stringResource(R.string.history_read_failure),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        LifeTracingPrimaryButton(
            onClick = onRetry,
            modifier =
                historyFocusTarget(LocalHistoryFocus.current.retry)
                    .historyActionSemantics(onRetry)
                    .testTag("history-retry"),
        ) { Text(stringResource(R.string.history_retry)) }
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
    return start?.let { stringResource(R.string.history_timed_range, historyInstant(it, zoneId), endText) }
        ?: stringResource(R.string.history_completion_time, endText)
}

@Composable
private fun historyInstant(
    instant: Instant,
    zoneId: ZoneId,
): String = historicalTimestamp(instant, zoneId, includeDate = true)

@Composable
private fun mutationPreviewInstant(
    instant: Instant,
    zoneId: ZoneId,
): String =
    mutationPreviewInstantText(
        instant,
        zoneId,
        LocalConfiguration.current.locales[0],
    )

internal fun mutationPreviewInstantText(
    instant: Instant,
    zoneId: ZoneId,
    locale: java.util.Locale,
): String {
    val zoned = instant.atZone(zoneId)
    val formatted =
        DateTimeFormatterBuilder()
            .appendLocalized(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
            .appendFraction(ChronoField.MILLI_OF_SECOND, 3, 3, true)
            .toFormatter(locale)
            .format(zoned)
    return if (zoneId.rules.getValidOffsets(zoned.toLocalDateTime()).size > 1) {
        "$formatted ${zoned.offset.id}"
    } else {
        formatted
    }
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
