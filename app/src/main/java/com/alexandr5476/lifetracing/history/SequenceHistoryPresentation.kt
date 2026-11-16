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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.alexandr5476.lifetracing.ui.exactHistoricalTimestamp
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.launch
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
        if (state.hasSequenceTransient() && focus.lazyOwner != null) focus.removed = focus.owner
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
            if (load is HistoryDetailLoad.Content && !state.hasSequenceTransient()) {
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
    val navigation = remember { SequenceNavigationFocus() }
    CompositionLocalProvider(LocalSequenceNavigationFocus provides navigation) {
        LaunchedEffect(SequenceDetailReference(detail)) {
            navigation.pending = null
            // Virtualization alone is not removal. Check the focused key only on canonical replacement.
            val owner = focus.lazyOwner
            val retained =
                when {
                    owner == null -> true
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
            modifier = Modifier.fillMaxSize().testTag("sequence-history-detail-list"),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item("sequence:root", contentType = "root") {
                CompositionLocalProvider(
                    LocalHistoryLazyIdentity provides "sequence:root",
                ) { SequenceRootSummary(detail) }
            }
            item("sequence:navigation", contentType = "navigation") {
                CompositionLocalProvider(LocalHistoryLazyIdentity provides "sequence:navigation") {
                    SequenceSectionNavigation(detail, listState, onTiming != null, issue != null)
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

private class SequenceNavigationFocus {
    var pending by mutableStateOf<String?>(null)
    val requester = FocusRequester()
}

private val LocalSequenceNavigationFocus = staticCompositionLocalOf { SequenceNavigationFocus() }

@Composable
private fun sequenceSummaryFocus(allowJump: Boolean = true): Modifier {
    val navigation = LocalSequenceNavigationFocus.current
    val identity = LocalHistoryLazyIdentity.current
    val pending = allowJump && identity != null && navigation.pending == identity
    LaunchedEffect(pending) {
        if (pending) {
            withFrameNanos { }
            if (navigation.pending == identity) {
                navigation.requester.requestFocus()
                navigation.pending = null
            }
        }
    }
    return historyFocusTarget(if (pending) navigation.requester else null)
}

/** Section/ordinal navigation scrolls only. No admission, reload or historical command is dispatched. */
@Composable
private fun SequenceSectionNavigation(
    detail: SequenceHistoryDetail,
    state: LazyListState,
    hasTiming: Boolean,
    hasIssue: Boolean,
) {
    val scope = rememberCoroutineScope()
    var ordinal by remember { mutableStateOf("") }
    val navigation = LocalSequenceNavigationFocus.current

    // Section offsets use only collection sizes and optional prefix items; no collection scan.
    fun go(key: String) {
        val index = if (key == "occurrence") ordinal.toIntOrNull()?.minus(1) else null
        if (key == "occurrence" && (index == null || index !in detail.occurrences.indices)) return
        scope.launch {
            val fieldsIndex = 2 + (if (hasTiming) 1 else 0) + (if (hasIssue) 1 else 0)
            val occurrencesIndex = fieldsIndex + 2 + (if (detail.fields.isEmpty()) 1 else detail.fields.size)
            navigation.pending =
                when (key) {
                    "fields" -> "sequence:fields-heading"
                    "intervals" -> "sequence:intervals-heading"
                    else -> "occurrence:${detail.occurrences[requireNotNull(index)].occurrenceId.value}"
                }
            state.scrollToItem(
                when (key) {
                    "fields" -> fieldsIndex
                    "intervals" -> occurrencesIndex + detail.occurrences.size
                    else -> occurrencesIndex + requireNotNull(index)
                },
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        TextButton(onClick = {
            go("fields")
        }, modifier = historyFocusTarget()) { Text(stringResource(R.string.history_sequence_fields)) }
        TextButton(onClick = {
            go("intervals")
        }, modifier = historyFocusTarget()) { Text(stringResource(R.string.history_intervals)) }
        LifeTracingOutlinedTextField(
            value = ordinal,
            onValueChange = { ordinal = it },
            label = { Text(stringResource(R.string.sequence_history_go_position, detail.occurrences.size)) },
            modifier = historyFocusTarget().fillMaxWidth().testTag("sequence-history-go-position"),
        )
        TextButton(
            onClick = { go("occurrence") },
            enabled = ordinal.toIntOrNull()?.let { it in 1..detail.occurrences.size } == true,
            modifier = historyFocusTarget().testTag("sequence-history-go"),
        ) { Text(stringResource(R.string.sequence_history_go)) }
    }
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
        SequenceReadOnlySummary("sequence-occurrence-summary-${occurrence.occurrenceId.value}") {
            Text(
                stringResource(R.string.history_occurrence, occurrence.runtimePosition + 1, occurrence.activity.title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            occurrence.activity.shortComment?.let { Text(it) }
            Text(stringResource(R.string.sequence_history_occurrence_id, occurrence.occurrenceId.value))
            Text(stringResource(R.string.sequence_history_runtime_position, occurrence.runtimePosition))
            Text(stringResource(R.string.sequence_history_activity_snapshot_id, occurrence.activitySnapshotId.value))
            Text(stringResource(occurrenceStatusResource(occurrence.status)))
            Text(
                stringResource(
                    R.string.sequence_history_source_id,
                    occurrence.sourceSequenceSnapshotNodeId?.value ?: stringResource(R.string.history_missing_value),
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
        if (occurrence.child == null || occurrence.status == RuntimeOccurrenceStatus.DELETED_EXECUTION) {
            occurrence.activity.mainValue?.let { SequenceActivityField(it, configuredOnly = true) }
        }
        if (occurrence.status == RuntimeOccurrenceStatus.DELETED_EXECUTION) {
            SequenceReadOnlySummary("sequence-deleted-${occurrence.occurrenceId.value}") {
                Text(stringResource(R.string.history_deleted_child))
                Text(stringResource(R.string.sequence_history_deleted_detail))
                occurrence.childMutationFacts?.let {
                    Text(stringResource(R.string.sequence_history_deleted_child_id, it.executionId.value))
                }
            }
        } else {
            occurrence.child?.let { child ->
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
                child.fields.forEach { field ->
                    androidx.compose.runtime.key(field.id) { SequenceActivityField(field) }
                }
            }
        }
        if (occurrence.childMutationFacts != null && !occurrence.isDeletedFromHistory) {
            if (onDeleteChild != null && occurrence.status == RuntimeOccurrenceStatus.COMPLETED) {
                SequenceSecondaryButton(
                    { onDeleteChild(occurrence.occurrenceId) },
                    Modifier.testTag("sequence-history-delete-child-${occurrence.occurrenceId.value}"),
                ) {
                    Text(stringResource(R.string.sequence_history_delete_child))
                }
            }
            if (onRemoveOccurrence != null &&
                occurrence.status in setOf(RuntimeOccurrenceStatus.COMPLETED, RuntimeOccurrenceStatus.DELETED_EXECUTION)
            ) {
                SequenceSecondaryButton(
                    { onRemoveOccurrence(occurrence.occurrenceId) },
                    Modifier.testTag("sequence-history-remove-occurrence-${occurrence.occurrenceId.value}"),
                ) {
                    Text(stringResource(R.string.sequence_history_remove_occurrence))
                }
            }
        }
    }
}

@Composable
private fun SequenceHistoryInterval(
    zoneId: ZoneId,
    interval: SequenceInterval,
) {
    HistoryCard(Modifier.testTag("history-interval-${interval.id.value}")) {
        SequenceReadOnlySummary("sequence-interval-summary-${interval.id.value}") {
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
        sequenceSummaryFocus(
            LocalHistoryLazyIdentity.current?.startsWith("sequence-field:") == true,
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
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        sequenceSummaryFocus(
            tag == "sequence-history-root" ||
                tag.startsWith("sequence-occurrence-summary") ||
                tag.startsWith("sequence-interval-summary"),
        ).semantics(mergeDescendants = true) {}.focusable().testTag(tag),
        content = content,
    )
}

@Composable
private fun SequenceSectionHeading(resource: Int) {
    Text(
        stringResource(resource),
        sequenceSummaryFocus().semantics { heading() }.focusable(),
        style = MaterialTheme.typography.titleMedium,
    )
}

private fun sequenceDurationText(value: Duration): String {
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
