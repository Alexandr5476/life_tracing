@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "TooManyFunctions",
    "MagicNumber",
    "CyclomaticComplexMethod",
    "ComplexCondition",
)

package com.alexandr5476.lifetracing.history

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryActualValue
import com.alexandr5476.lifetracing.domain.SequenceHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryField
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrence
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged
import com.alexandr5476.lifetracing.ui.exactHistoricalTimestamp
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.ZoneId

/** One bounded vertical scroller for idle content; Back is always attached for recovery. */
@Composable
internal fun SequenceHistoryDetailSurface(
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
    onBack: () -> Unit,
    load: HistoryDetailLoad<SequenceHistoryDetail>,
    onRetry: () -> Unit,
    backEnabled: Boolean,
    retryEnabled: Boolean,
    mutationContent: @Composable (SequenceHistoryDetail) -> Unit,
) {
    val listState = rememberLazyListState()
    val focus = LocalHistoryFocus.current
    LaunchedEffect(state.hasSequenceTransient()) {
        if (state.hasSequenceTransient() &&
            state.timingDraft == null &&
            focus.lazyOwner != null
        ) {
            focus.removed = focus.owner
        }
    }
    LaunchedEffect(load is HistoryDetailLoad.Loading) {
        if (load is HistoryDetailLoad.Loading && focus.lazyOwner != null) focus.awaitingRead = true
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(MaterialTheme.spacing.large)) {
            TextButton(
                onClick = onBack,
                enabled = backEnabled,
                modifier =
                    historyFocusTarget(LocalHistoryFocus.current.back, backEnabled)
                        .then(if (backEnabled) Modifier.historyActionSemantics(onBack) else Modifier),
            ) { Text(stringResource(R.string.history_back)) }
            if (state.committedMutation != null) {
                SequenceCommittedStatus(state)
            }
            if (load is HistoryDetailLoad.Content &&
                (state.timingDraft != null || state.timingProposal != null || state.structuralTarget != null)
            ) {
                Column(Modifier.weight(1f).fillMaxWidth()) { mutationContent(load.value) }
            } else if (load is HistoryDetailLoad.Content && !state.hasSequenceTransient()) {
                SequenceDetail(
                    load.value,
                    onDeleteChild = { onAction(SequenceHistoryMutationAction.RequestChildDeletion(it)) },
                    onRemoveOccurrence = { onAction(SequenceHistoryMutationAction.BeginStructuralRemoval(it)) },
                    listState = listState,
                    issue = state.issue,
                    onTiming = { onAction(SequenceHistoryMutationAction.BeginTiming) },
                )
            } else {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                ) {
                    when (load) {
                        HistoryDetailLoad.Loading ->
                            HistoryCard {
                                SequenceReadOnlySummary(
                                    "sequence-history-loading",
                                ) { HistoryReadStatus(R.string.history_detail_loading) }
                            }
                        HistoryDetailLoad.Unavailable ->
                            HistoryCard {
                                SequenceReadOnlySummary(
                                    "sequence-history-unavailable",
                                ) { HistoryReadStatus(R.string.history_unavailable) }
                            }
                        is HistoryDetailLoad.Failure -> FailureCard(onRetry, retryEnabled)
                        is HistoryDetailLoad.Content -> mutationContent(load.value)
                    }
                }
            }
        }
    }
}

/** Keys use disjoint namespaces. Lists remain the canonical collections, never copied or sorted. */
@Composable
internal fun SequenceDetail(
    detail: SequenceHistoryDetail,
    onDeleteChild: ((SequenceOccurrenceId) -> Unit)? = null,
    onRemoveOccurrence: ((SequenceOccurrenceId) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    issue: SequenceHistoryMutationIssue? = null,
    onTiming: (() -> Unit)? = null,
) {
    val focus = LocalHistoryFocus.current
    val lease = rememberSequenceHistoryNavigation(detail, onTiming != null, issue != null)
    val scope = rememberCoroutineScope()
    val navigation = remember(lease, listState, scope) { SequenceNavigationFocus(detail, lease, listState, scope) }
    CompositionLocalProvider(LocalSequenceNavigationFocus provides navigation) {
        LaunchedEffect(SequenceDetailReference(detail), onDeleteChild != null, onRemoveOccurrence != null) {
            // Virtualization alone is not removal. Check the focused key only on canonical replacement.
            val owner = focus.lazyOwner
            val retained =
                when {
                    owner == null -> true
                    focus.sequenceOwner != null ->
                        detail.represents(
                            requireNotNull(focus.sequenceOwner),
                            onDeleteChild != null,
                            onRemoveOccurrence != null,
                        )
                    owner.startsWith("occurrence:") ->
                        detail.occurrences.any {
                            "occurrence:${it.occurrenceId.value}" ==
                                owner
                        }
                    owner.startsWith("interval:") -> detail.intervals.any { "interval:${it.id.value}" == owner }
                    owner.startsWith(
                        "sequence-field:",
                    ) -> detail.fields.any { "sequence-field:${it.id.value}" == owner }
                    else -> true
                }
            if (!retained) focus.removed = focus.owner
        }
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("sequence-history-detail-list")
                    .onPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown &&
                            event.key == Key.F6 &&
                            navigation.returnToAvailableOrdinal(
                                fromInterval = focus.lazyOwner?.startsWith("interval:") == true,
                            )
                    },
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item("sequence:root", contentType = "root") {
                CompositionLocalProvider(
                    LocalHistoryLazyIdentity provides "sequence:root",
                ) { SequenceRootSummary(detail) }
            }
            item(SEQUENCE_NAVIGATION_KEY, contentType = "navigation") {
                CompositionLocalProvider(LocalHistoryLazyIdentity provides SEQUENCE_NAVIGATION_KEY) {
                    SequenceSectionNavigation(detail, onTiming != null, issue != null)
                }
            }
            if (onTiming != null) {
                item("sequence:timing", contentType = "action") {
                    SequenceSecondaryButton(onTiming, Modifier.testTag("sequence-history-timing")) {
                        Text(stringResource(R.string.sequence_history_correct_timing))
                    }
                }
            }
            if (issue != null) item("sequence:issue", contentType = "status") { SequenceMutationIssue(issue) }
            item("sequence:fields-heading", contentType = "heading") {
                CompositionLocalProvider(LocalHistoryLazyIdentity provides "sequence:fields-heading") {
                    SequenceSectionHeading(R.string.history_sequence_fields)
                }
            }
            if (detail.fields.isEmpty()) {
                item(
                    "sequence:empty-fields",
                ) { Text(stringResource(R.string.history_no_fields)) }
            }
            items(detail.fields, key = { "sequence-field:${it.id.value}" }, contentType = { "field" }) {
                CompositionLocalProvider(
                    LocalHistoryLazyIdentity provides "sequence-field:${it.id.value}",
                ) { SequenceField(it) }
            }
            item("sequence:occurrences-heading", contentType = "heading") {
                CompositionLocalProvider(LocalHistoryLazyIdentity provides "sequence:occurrences-heading") {
                    SequenceSectionHeading(R.string.history_occurrences)
                }
            }
            items(detail.occurrences, key = { "occurrence:${it.occurrenceId.value}" }, contentType = { "occurrence" }) {
                CompositionLocalProvider(LocalHistoryLazyIdentity provides "occurrence:${it.occurrenceId.value}") {
                    SequenceOccurrence(detail.originalZoneId, it, onDeleteChild, onRemoveOccurrence)
                }
            }
            item("sequence:intervals-heading", contentType = "heading") {
                CompositionLocalProvider(LocalHistoryLazyIdentity provides "sequence:intervals-heading") {
                    SequenceSectionHeading(R.string.history_intervals)
                }
            }
            items(detail.intervals, key = { "interval:${it.id.value}" }, contentType = { "interval" }) {
                CompositionLocalProvider(LocalHistoryLazyIdentity provides "interval:${it.id.value}") {
                    SequenceHistoryInterval(detail.originalZoneId, it)
                }
            }
        }
    }
}

private class SequenceNavigationFocus(
    private val detail: SequenceHistoryDetail,
    val lease: SequenceHistoryNavigation,
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    var pending by mutableStateOf<SequenceNavigationRequest?>(null)
    val requester = FocusRequester()

    fun hasOrdinal(section: SequenceDetailOrdinal): Boolean =
        when (section) {
            SequenceDetailOrdinal.OCCURRENCE -> detail.occurrences.isNotEmpty()
            SequenceDetailOrdinal.INTERVAL -> detail.intervals.isNotEmpty()
        }

    fun returnToAvailableOrdinal(fromInterval: Boolean): Boolean =
        when {
            fromInterval && hasOrdinal(SequenceDetailOrdinal.INTERVAL) -> returnTo(SequenceDetailOrdinal.INTERVAL)
            hasOrdinal(SequenceDetailOrdinal.OCCURRENCE) -> returnTo(SequenceDetailOrdinal.OCCURRENCE)
            else -> returnTo(SequenceDetailOrdinal.INTERVAL)
        }

    fun returnTo(section: SequenceDetailOrdinal): Boolean =
        hasOrdinal(section) && go(section.fieldTag, SEQUENCE_NAVIGATION_INDEX)

    fun go(
        key: String,
        index: Int,
        origin: CoroutineScope = scope,
    ): Boolean {
        if (!origin.isActive || !lease.isCurrent()) return false
        pending = null
        lease.launch(origin) {
            withFrameNanos { }
            if (!lease.isCurrent()) return@launch
            pending = SequenceNavigationRequest(key)
            // Request the scroll synchronously while the lease is current.
            listState.requestScrollToItem(index)
        }
        return true
    }
}

private const val SEQUENCE_NAVIGATION_KEY = "sequence:navigation"
private const val SEQUENCE_NAVIGATION_INDEX = 1

private enum class SequenceDetailOrdinal(
    val tag: String,
) {
    OCCURRENCE("sequence-history-go"),
    INTERVAL("sequence-history-go-interval"),
    ;

    val fieldTag get() = "$tag-position"
}

private class SequenceNavigationRequest(
    val key: String,
)

private val LocalSequenceNavigationFocus = staticCompositionLocalOf<SequenceNavigationFocus?> { null }

@Composable
private fun sequenceNavigationTarget(identity: String? = LocalHistoryLazyIdentity.current): Modifier {
    val navigation = LocalSequenceNavigationFocus.current
    val lease = navigation?.lease
    val available = lease?.isCurrent() != false
    val request = navigation?.pending
    val pending = identity != null && request?.key == identity
    var attached by remember { mutableStateOf(false) }
    LaunchedEffect(navigation, request, pending) {
        if (pending && available) {
            withFrameNanos { }
            if (attached && lease?.isCurrent() == true && navigation.pending === request) {
                navigation.requester.requestFocus()
                navigation.pending = null
            }
        }
    }
    return historyFocusTarget(if (pending) navigation?.requester else null, available)
        .onFocusTargetAttachmentChanged { attached = it }
        .focusProperties { canFocus = lease?.canFocus() != false }
}

@Composable
private fun sequenceOrdinalReturn(section: SequenceDetailOrdinal?): Modifier {
    val navigation = LocalSequenceNavigationFocus.current
    if (section == null || navigation == null || !navigation.hasOrdinal(section) || !navigation.lease.isCurrent()) {
        return Modifier
    }
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.sequence_history_return_to_ordinals)
    return Modifier.semantics {
        customActions =
            listOf(CustomAccessibilityAction(label) { scope.isActive && navigation.returnTo(section) })
    }
}

/** Section/ordinal navigation scrolls only. No admission, reload or historical command is dispatched. */
@Composable
private fun SequenceSectionNavigation(
    detail: SequenceHistoryDetail,
    hasTiming: Boolean,
    hasIssue: Boolean,
) {
    val scope = rememberCoroutineScope()
    val navigation = requireNotNull(LocalSequenceNavigationFocus.current)
    val lease = navigation.lease
    val available = scope.isActive && lease.isCurrent()
    val focusPolicy = Modifier.focusProperties { canFocus = scope.isActive && lease.canFocus() }

    // Section offsets use only collection sizes and optional prefix items; no collection scan.
    val fieldsIndex = SEQUENCE_NAVIGATION_INDEX + 1 + (if (hasTiming) 1 else 0) + (if (hasIssue) 1 else 0)
    val occurrencesIndex = fieldsIndex + 2 + detail.fields.size.coerceAtLeast(1)
    val intervalsHeadingIndex = occurrencesIndex + detail.occurrences.size

    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            val goFields: () -> Unit = {
                navigation.go("sequence:fields-heading", fieldsIndex, scope)
            }
            val goIntervals: () -> Unit = {
                navigation.go("sequence:intervals-heading", intervalsHeadingIndex, scope)
            }
            TextButton(
                onClick = goFields,
                enabled = available,
                modifier =
                    historyFocusTarget(enabled = available)
                        .then(focusPolicy)
                        .historyActionSemantics(goFields)
                        .testTag("sequence-history-section-fields"),
            ) { Text(stringResource(R.string.history_sequence_fields)) }
            TextButton(
                onClick = goIntervals,
                enabled = available,
                modifier =
                    historyFocusTarget(enabled = available)
                        .then(focusPolicy)
                        .historyActionSemantics(goIntervals)
                        .testTag("sequence-history-section-intervals"),
            ) { Text(stringResource(R.string.history_intervals)) }
        }
        if (navigation.hasOrdinal(SequenceDetailOrdinal.OCCURRENCE)) {
            SequenceOrdinalNavigation(
                detail.occurrences.size,
                R.string.sequence_history_go_position,
                R.string.sequence_history_go,
                SequenceDetailOrdinal.OCCURRENCE,
                lease,
            ) { index ->
                val occurrenceId = detail.occurrences[index].occurrenceId
                navigation.go("occurrence:${occurrenceId.value}", occurrencesIndex + index, scope)
            }
        }
        if (navigation.hasOrdinal(SequenceDetailOrdinal.INTERVAL)) {
            SequenceOrdinalNavigation(
                detail.intervals.size,
                R.string.sequence_history_go_interval_position,
                R.string.sequence_history_go_interval,
                SequenceDetailOrdinal.INTERVAL,
                lease,
            ) { index ->
                navigation.go("interval:${detail.intervals[index].id.value}", intervalsHeadingIndex + 1 + index, scope)
            }
        }
    }
}

@Composable
private fun SequenceOrdinalNavigation(
    count: Int,
    ordinalLabel: Int,
    buttonLabel: Int,
    section: SequenceDetailOrdinal,
    lease: SequenceHistoryNavigation,
    go: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val tag = section.tag
    var ordinal by remember { mutableStateOf("") }

    fun position(): Int? = ordinal.toIntOrNull()?.takeIf { it in 1..count }?.minus(1)

    val available = scope.isActive && lease.isCurrent()
    val goEnabled = available && position() != null
    val jump: () -> Unit = {
        if (scope.isActive && lease.isCurrent()) position()?.let(go)
    }
    LifeTracingOutlinedTextField(
        value = ordinal,
        onValueChange = { if (scope.isActive && lease.isCurrent()) ordinal = it },
        enabled = available,
        label = { Text(stringResource(ordinalLabel, count)) },
        modifier =
            sequenceNavigationTarget(section.fieldTag)
                .fillMaxWidth()
                .testTag(section.fieldTag)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Tab) {
                        if (event.type == KeyEventType.KeyDown && scope.isActive && lease.isCurrent()) {
                            focusManager.moveFocus(
                                if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next,
                            )
                        }
                        true
                    } else {
                        false
                    }
                },
    )
    TextButton(
        onClick = jump,
        enabled = goEnabled,
        modifier =
            historyFocusTarget(enabled = goEnabled)
                .focusProperties { canFocus = goEnabled && scope.isActive && lease.canFocus() }
                .then(if (goEnabled) Modifier.historyActionSemantics(jump) else Modifier)
                .testTag(tag),
    ) { Text(stringResource(buttonLabel)) }
}

@Composable
private fun SequenceRootSummary(detail: SequenceHistoryDetail) {
    SequenceReadOnlySummary("sequence-history-root") {
        Text(detail.root.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        detail.root.shortComment?.let { Text(it) }
        Text(stringResource(R.string.sequence_history_execution_id, detail.root.executionId.value))
        Text(stringResource(R.string.sequence_history_snapshot_id, detail.root.snapshotId.value))
        Text(stringResource(sequenceStatusResource(detail.root.status)))
        Text(stringResource(R.string.sequence_history_zone, detail.originalZoneId.id))
        Text(
            stringResource(
                R.string.sequence_history_root_start,
                exactHistoricalTimestamp(detail.root.startedAt, detail.originalZoneId),
            ),
        )
        Text(
            stringResource(
                R.string.sequence_history_root_end,
                exactHistoricalTimestamp(detail.root.completedAt, detail.originalZoneId),
            ),
        )
        Text(stringResource(R.string.history_active_duration, sequenceDurationText(detail.root.activeDuration)))
        Text(stringResource(R.string.history_pause_duration, sequenceDurationText(detail.root.pauseDuration)))
        Text(stringResource(R.string.history_wall_duration, sequenceDurationText(detail.root.wallDuration)))
        detail.root.planEntryId?.let { Text(stringResource(R.string.sequence_history_plan_id, it.value)) }
    }
}

@Composable
private fun SequenceOccurrence(
    zoneId: ZoneId,
    occurrence: SequenceHistoryOccurrence,
    onDeleteChild: ((SequenceOccurrenceId) -> Unit)?,
    onRemoveOccurrence: ((SequenceOccurrenceId) -> Unit)?,
) {
    HistoryCard(Modifier.testTag("history-occurrence-${occurrence.occurrenceId.value}")) {
        SequenceOccurrenceFocus(occurrence, SequenceHistoryFocusElement.Summary) {
            SequenceReadOnlySummary(
                "sequence-occurrence-summary-${occurrence.occurrenceId.value}",
                SequenceDetailOrdinal.OCCURRENCE,
            ) {
                Text(
                    stringResource(
                        R.string.history_occurrence,
                        occurrence.runtimePosition + 1,
                        occurrence.activity.title,
                    ),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                occurrence.activity.shortComment?.let { Text(it) }
                Text(stringResource(R.string.sequence_history_occurrence_id, occurrence.occurrenceId.value))
                Text(stringResource(R.string.sequence_history_runtime_position, occurrence.runtimePosition))
                Text(
                    stringResource(R.string.sequence_history_activity_snapshot_id, occurrence.activitySnapshotId.value),
                )
                Text(stringResource(occurrenceStatusResource(occurrence.status)))
                Text(
                    stringResource(
                        R.string.sequence_history_source_id,
                        occurrence.sourceSequenceSnapshotNodeId?.value ?: stringResource(
                            R.string.history_missing_value,
                        ),
                    ),
                )
                occurrence.repeatSourceSnapshotNodeId?.let {
                    Text(
                        stringResource(R.string.sequence_history_repeat_id, it.value),
                    )
                }
                occurrence.repeatIteration?.let { Text(stringResource(R.string.history_repeat_iteration, it)) }
                Text(
                    stringResource(
                        if (occurrence.isRuntimeAdded) {
                            R.string.history_runtime_added
                        } else {
                            R.string.sequence_history_not_runtime_added
                        },
                    ),
                )
                Text(
                    stringResource(
                        R.string.sequence_history_completion_reason,
                        stringResource(completionReasonResource(occurrence.completionReason)),
                    ),
                )
                occurrence.enteredAt?.let {
                    Text(
                        stringResource(R.string.history_entered, exactHistoricalTimestamp(it, zoneId)),
                    )
                }
                occurrence.completedAt?.let {
                    Text(stringResource(R.string.history_occurrence_completed, exactHistoricalTimestamp(it, zoneId)))
                }
                if (occurrence.child == null || occurrence.status == RuntimeOccurrenceStatus.DELETED_EXECUTION) {
                    Text(historyTracking(occurrence.activity.timeTrackingMode, occurrence.activity.timerTarget))
                }
            }
        }
        if (occurrence.child == null || occurrence.status == RuntimeOccurrenceStatus.DELETED_EXECUTION) {
            occurrence.activity.mainValue?.let {
                SequenceOccurrenceFocus(
                    occurrence,
                    SequenceHistoryFocusElement.ConfiguredField(occurrence.activitySnapshotId, it.id),
                ) {
                    SequenceActivityField(it, configuredOnly = true)
                }
            }
        }
        if (occurrence.status == RuntimeOccurrenceStatus.DELETED_EXECUTION) {
            SequenceOccurrenceFocus(occurrence, SequenceHistoryFocusElement.Tombstone) {
                SequenceReadOnlySummary("sequence-deleted-${occurrence.occurrenceId.value}") {
                    Text(stringResource(R.string.history_deleted_child))
                    Text(stringResource(R.string.sequence_history_deleted_detail))
                    occurrence.childMutationFacts?.let {
                        Text(stringResource(R.string.sequence_history_deleted_child_id, it.executionId.value))
                    }
                }
            }
        } else {
            occurrence.child?.let { child ->
                SequenceOccurrenceFocus(occurrence, SequenceHistoryFocusElement.Child(child.executionId)) {
                    SequenceReadOnlySummary("sequence-child-${child.executionId.value}") {
                        Text(stringResource(R.string.sequence_history_child_id, child.executionId.value))
                        Text(stringResource(R.string.sequence_history_zone, child.originalZoneId.id))
                        Text(historyTracking(occurrence.activity.timeTrackingMode, occurrence.activity.timerTarget))
                        child.startedAt?.let {
                            Text(
                                stringResource(
                                    R.string.history_child_started,
                                    exactHistoricalTimestamp(it, child.originalZoneId),
                                ),
                            )
                        }
                        child.completedAt?.let {
                            Text(
                                stringResource(
                                    R.string.history_child_completed,
                                    exactHistoricalTimestamp(it, child.originalZoneId),
                                ),
                            )
                        }
                        Text(
                            stringResource(
                                R.string.history_actual_duration,
                                child.activeDuration?.let(::sequenceDurationText)
                                    ?: stringResource(R.string.history_missing_value),
                            ),
                        )
                    }
                }
                child.fields.forEach { field ->
                    SequenceOccurrenceFocus(
                        occurrence,
                        SequenceHistoryFocusElement.ChildField(child.executionId, field.id),
                    ) {
                        SequenceActivityField(field)
                    }
                }
            }
        }
        if (occurrence.childMutationFacts != null && !occurrence.isDeletedFromHistory) {
            if (onDeleteChild != null && occurrence.status == RuntimeOccurrenceStatus.COMPLETED) {
                SequenceOccurrenceFocus(
                    occurrence,
                    SequenceHistoryFocusElement.DeleteChild(requireNotNull(occurrence.childMutationFacts).executionId),
                ) {
                    SequenceSecondaryButton(
                        { onDeleteChild(occurrence.occurrenceId) },
                        Modifier.testTag("sequence-history-delete-child-${occurrence.occurrenceId.value}"),
                        enabled =
                            occurrence.canDeleteChild() &&
                                LocalSequenceHistoryAdmission.current?.enabled(
                                    SequenceHistoryMutationAction.RequestChildDeletion(occurrence.occurrenceId),
                                ) != false,
                    ) {
                        Text(stringResource(R.string.sequence_history_delete_child))
                    }
                }
            }
            if (onRemoveOccurrence != null &&
                occurrence.status in setOf(RuntimeOccurrenceStatus.COMPLETED, RuntimeOccurrenceStatus.DELETED_EXECUTION)
            ) {
                SequenceOccurrenceFocus(
                    occurrence,
                    SequenceHistoryFocusElement.RemoveOccurrence(
                        requireNotNull(occurrence.childMutationFacts).executionId,
                    ),
                ) {
                    val description =
                        stringResource(
                            R.string.sequence_structural_entry,
                            occurrence.activity.title,
                            occurrence.occurrenceId.value,
                            occurrence.childMutationFacts!!.executionId.value,
                        )
                    SequenceSecondaryButton(
                        { onRemoveOccurrence(occurrence.occurrenceId) },
                        Modifier
                            .testTag(
                                "sequence-history-remove-occurrence-${occurrence.occurrenceId.value}",
                            ).semantics {
                                contentDescription = description
                            },
                        enabled =
                            occurrence.canRemoveOccurrence() &&
                                LocalSequenceHistoryAdmission.current?.enabled(
                                    SequenceHistoryMutationAction.BeginStructuralRemoval(occurrence.occurrenceId),
                                ) != false,
                    ) {
                        Text(stringResource(R.string.sequence_history_remove_occurrence))
                    }
                }
            }
        }
    }
}

@Composable
private fun SequenceOccurrenceFocus(
    occurrence: SequenceHistoryOccurrence,
    element: SequenceHistoryFocusElement,
    content: @Composable () -> Unit,
) {
    val identity = SequenceHistoryFocusIdentity(occurrence.occurrenceId, element)
    key(identity) { CompositionLocalProvider(LocalSequenceHistoryFocusIdentity provides identity, content = content) }
}

@Composable
private fun SequenceHistoryInterval(
    zoneId: ZoneId,
    interval: SequenceInterval,
) {
    HistoryCard(Modifier.testTag("history-interval-${interval.id.value}")) {
        SequenceReadOnlySummary("sequence-interval-summary-${interval.id.value}", SequenceDetailOrdinal.INTERVAL) {
            Text(stringResource(intervalKindResource(interval.kind)))
            Text(stringResource(R.string.sequence_history_interval_id, interval.id.value))
            Text(
                interval.occurrenceId?.let { stringResource(R.string.sequence_history_owner_id, it.value) }
                    ?: stringResource(R.string.sequence_history_ownerless),
            )
            Text(
                stringResource(R.string.history_interval_started, exactHistoricalTimestamp(interval.startedAt, zoneId)),
            )
            Text(
                stringResource(
                    R.string.history_interval_ended,
                    interval.endedAt?.let { exactHistoricalTimestamp(it, zoneId) }
                        ?: stringResource(R.string.history_missing_value),
                ),
            )
        }
    }
}

@Composable
private fun SequenceField(field: SequenceHistoryField) {
    SequenceFieldValues(
        field.id.value,
        field.name,
        field.type,
        field.displayPrecision,
        field.isMainValue,
        field.categoryOptions.map { it.id.value to it.label },
        field.configuredValue,
        field.actualValue,
        field.categoryOptions.associate { it.id to it.label },
        field.unit,
    )
}

@Composable
private fun SequenceActivityField(
    field: ActivityHistoryField,
    configuredOnly: Boolean = false,
) {
    SequenceFieldValues(
        field.id.value,
        field.name,
        field.type,
        field.displayPrecision,
        field.isMainValue,
        field.categoryOptions.map { it.id.value to it.label },
        field.configuredValue,
        field.actualValue,
        field.categoryOptions.associate {
            it.id to it.label
        },
        field.unit,
        configuredOnly,
    )
}

/** Schema and selected options keep their own frozen IDs even when all labels are identical. */
@Composable
private fun SequenceFieldValues(
    id: String,
    name: String,
    type: CustomFieldType,
    precision: Int?,
    main: Boolean,
    options: List<Pair<String, String>>,
    configured: Any,
    actual: Any,
    labels: Map<*, String>,
    unit: String?,
    configuredOnly: Boolean = false,
) {
    val configuredText = historyAccessibleValue(configured, historyConfigured(configured, labels, precision, unit))
    val actualText = historyAccessibleValue(actual, historyActual(actual, precision, unit))
    val facts =
        buildList {
            add(stringResource(R.string.sequence_history_field_id, name, id))
            add(
                stringResource(
                    R.string.sequence_history_field_type,
                    stringResource(
                        when (type) {
                            CustomFieldType.NUMBER -> R.string.activity_editor_number
                            CustomFieldType.CATEGORY -> R.string.activity_editor_category
                            CustomFieldType.TEXT -> R.string.activity_editor_text
                        },
                    ),
                ),
            )
            precision?.let { add(stringResource(R.string.sequence_history_precision, it)) }
            unit?.let { add(stringResource(R.string.sequence_history_unit, it)) }
            if (main) add(stringResource(R.string.activity_editor_main_value))
            add(stringResource(R.string.history_configured_value, configuredText))
            if (!configuredOnly) add(stringResource(R.string.history_actual_field, name, actualText))
            options.forEach { (optionId, label) ->
                add(stringResource(R.string.sequence_history_option_id, label, optionId))
            }
            val configuredId =
                when (configured) {
                    is ActivityHistoryConfiguredValue.Category -> configured.optionId.value
                    is SequenceHistoryConfiguredValue.Category -> configured.optionId.value
                    else -> null
                }
            configuredId?.let { add(stringResource(R.string.sequence_history_configured_option, it)) }
            if (!configuredOnly) {
                val actualId =
                    when (actual) {
                        is ActivityHistoryActualValue.Category -> actual.optionId.value
                        is SequenceHistoryActualValue.Category -> actual.optionId.value
                        else -> null
                    }
                actualId?.let { add(stringResource(R.string.sequence_history_actual_option, it)) }
            }
        }
    Column(
        sequenceNavigationTarget(
            LocalHistoryLazyIdentity.current?.takeIf { it.startsWith("sequence-field:") },
        ).focusable().testTag("sequence-history-field-$id").clearAndSetSemantics {
            contentDescription =
                facts.joinToString(". ")
        },
    ) {
        facts.forEach { Text(it) }
    }
}

@Composable
private fun SequenceReadOnlySummary(
    tag: String,
    returnTo: SequenceDetailOrdinal? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val identity = LocalHistoryLazyIdentity.current.takeIf { returnTo != null || tag == "sequence-history-root" }
    Column(
        sequenceNavigationTarget(identity)
            .then(sequenceOrdinalReturn(returnTo))
            .semantics(mergeDescendants = true) {}
            .focusable()
            .testTag(tag),
        content = content,
    )
}

@Composable
private fun SequenceSectionHeading(resource: Int) {
    Text(
        stringResource(resource),
        sequenceNavigationTarget().semantics { heading() }.focusable(),
        style = MaterialTheme.typography.titleMedium,
    )
}

internal fun sequenceDurationText(value: Duration): String {
    val fraction =
        value.nano
            .toString()
            .padStart(9, '0')
            .trimEnd('0')
    return durationText(value) + if (value.nano == 0) "" else ".${fraction.padEnd(3, '0')}"
}

private fun completionReasonResource(reason: OccurrenceCompletionReason?): Int =
    when (reason) {
        OccurrenceCompletionReason.NATURAL_TIMER_END -> R.string.sequence_history_reason_timer
        OccurrenceCompletionReason.MANUAL_FINISH -> R.string.sequence_history_reason_manual
        OccurrenceCompletionReason.ADVANCED_TO_NEXT -> R.string.sequence_history_reason_advance
        OccurrenceCompletionReason.JUMP -> R.string.sequence_history_reason_jump
        OccurrenceCompletionReason.SEQUENCE_ENDED_EARLY -> R.string.sequence_history_reason_early
        null -> R.string.sequence_history_reason_missing
    }
