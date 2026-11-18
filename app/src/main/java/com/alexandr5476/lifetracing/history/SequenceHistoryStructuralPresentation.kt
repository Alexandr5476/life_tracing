@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "TooManyFunctions",
    "MaxLineLength",
    "CyclomaticComplexMethod",
)

package com.alexandr5476.lifetracing.history

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrence
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalMode
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceTimelineCalculator
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.exactHistoricalTimestamp
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.time.Instant
import java.time.ZoneId

/** Reference identity prevents a replacement review from inheriting native presses or deferred navigation. */
private class StructuralReference(
    private val value: Any?,
) {
    override fun equals(other: Any?): Boolean = other is StructuralReference && value === other.value

    override fun hashCode(): Int = System.identityHashCode(value)
}

private const val STRUCTURAL_SUMMARY_COUNT = 4

/** Identity-only notices never publish a deleted child's hidden actual timestamps or values. */
private data class DeletedStructuralNotice(
    val childId: ActivityExecutionId,
    val pauseId: ActivityExecutionPauseId? = null,
) {
    val key: String get() =
        pauseId?.let { "deleted-child-pause:${childId.value}:${it.value}" }
            ?: "deleted-child:${childId.value}"
}

private class StructuralContext(
    val detail: SequenceHistoryDetail,
) {
    val occurrences = detail.occurrences.associateBy { it.occurrenceId }
    val intervals = detail.intervals.associateBy { it.id }
    val deletedChildIds =
        detail.occurrences
            .filter { it.status == RuntimeOccurrenceStatus.DELETED_EXECUTION }
            .mapNotNull { it.childMutationFacts?.executionId }
            .toHashSet()
}

internal fun structuralChangeKey(change: SequenceHistoryPreviewChange): String =
    when (change) {
        is SequenceHistoryPreviewChange.Timestamp -> "timestamp:${timestampTargetKey(change.target)}"
        is SequenceHistoryPreviewChange.RemovedOccurrence ->
            "removed-occurrence:${change.occurrence.occurrenceId.value}"
        is SequenceHistoryPreviewChange.RemovedInterval -> "removed-interval:${change.interval.intervalId.value}"
        is SequenceHistoryPreviewChange.ChildPauseTimestamp ->
            "child-pause:${change.occurrence.occurrenceId.value}:" + change.pauseId.value
        is SequenceHistoryPreviewChange.OwnerlessPlacement -> "ownerless:${change.intervalId.value}"
    }

@Composable
internal fun ColumnScope.SequenceStructuralSurface(
    detail: SequenceHistoryDetail,
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
) {
    val proposal = state.structuralProposal
    val stage =
        when {
            proposal == null -> "mode"
            !proposal.isConfirmable -> "placements"
            else -> "review"
        }
    // Choice publications share their immutable choice index; final review owns the exact immutable command.
    val owner = if (stage == "placements") proposal?.ownerlessChoices else proposal
    val context = remember(SequenceDetailReference(detail)) { StructuralContext(detail) }
    val target = context.occurrences[state.structuralTarget]
    if (target == null) {
        SequenceMutationIssue(SequenceHistoryMutationIssue.INVALID_PROPOSAL)
        return
    }
    key(stage, state.structuralTarget, SequenceDetailReference(detail), StructuralReference(owner)) {
        val lease = rememberSequenceHistoryNavigation(detail, hasTiming = false, hasIssue = state.issue != null)
        val enabled = lease.isCurrent()
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val navigationFocus = remember { FocusRequester() }
        val primaryFocus = remember { FocusRequester() }
        val keyboard =
            Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.F6 && lease.isCurrent()) {
                    (if (event.isShiftPressed) primaryFocus else navigationFocus).requestFocus()
                    true
                } else {
                    false
                }
            }
        val navigation = remember(lease) { StructuralListNavigation(lease) }
        DisposableEffect(navigation) { onDispose { navigation.dispose() } }
        val title =
            when (stage) {
                "mode" -> R.string.sequence_history_structural_title
                "placements" -> R.string.sequence_history_ownerless_title
                else -> R.string.sequence_history_structural_review
            }
        Text(
            stringResource(title),
            deletionHeadingFocus(lease)
                .semantics {
                    heading()
                }.focusable(enabled)
                .testTag("sequence-structural-$stage-heading"),
            style = MaterialTheme.typography.titleMedium,
        )
        val deletedTranslations =
            remember(StructuralReference(proposal)) {
                buildList {
                    proposal?.command?.childTimings?.forEach { correction ->
                        if (correction.executionId in context.deletedChildIds) {
                            add(DeletedStructuralNotice(correction.executionId))
                            correction.pauses.forEach { add(DeletedStructuralNotice(correction.executionId, it.id)) }
                        }
                    }
                }
            }
        val count =
            when (stage) {
                "placements" -> requireNotNull(proposal).ownerlessChoices.size
                "review" -> requireNotNull(proposal).changes.size + deletedTranslations.size
                else -> 0
            }
        StructuralNavigator(count, lease, navigationFocus, keyboard) { index ->
            navigation.go(index, scope) { list.requestScrollToItem(it) }
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(keyboard)
                .testTag("sequence-structural-$stage-list"),
            state = list,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item("summary:target", contentType = "summary") {
                Column(
                    structuralFactFocus(navigation, 0, lease)
                        .semantics(mergeDescendants = true) {
                        }.focusable(enabled)
                        .testTag("sequence-structural-target"),
                ) {
                    Text(
                        stringResource(
                            R.string.sequence_child_deletion_parent,
                            detail.root.title,
                            detail.root.executionId.value,
                        ),
                    )
                    Text(stringResource(R.string.history_occurrence, target.runtimePosition + 1, target.activity.title))
                    Text(stringResource(R.string.sequence_history_occurrence_id, target.occurrenceId.value))
                    Text(
                        stringResource(R.string.sequence_history_activity_snapshot_id, target.activitySnapshotId.value),
                    )
                    if (target.enteredAt != null && target.completedAt != null) {
                        Text(
                            stringResource(
                                R.string.sequence_structural_target_span,
                                structuralInterval(target.enteredAt!!, target.completedAt, detail.originalZoneId),
                                sequenceDurationText(java.time.Duration.between(target.enteredAt, target.completedAt)),
                            ),
                        )
                    }
                    target.childMutationFacts?.let {
                        Text(stringResource(R.string.sequence_child_deletion_child, it.executionId.value))
                    }
                    target.sourceSequenceSnapshotNodeId?.let {
                        Text(stringResource(R.string.sequence_history_source_id, it.value))
                    }
                    target.repeatSourceSnapshotNodeId?.let {
                        Text(stringResource(R.string.sequence_history_repeat_id, it.value))
                    }
                    target.repeatIteration?.let { Text(stringResource(R.string.history_repeat_iteration, it)) }
                    if (target.isRuntimeAdded) Text(stringResource(R.string.history_runtime_added))
                }
            }
            item("summary:consequences", contentType = "summary") {
                Text(
                    stringResource(R.string.sequence_structural_consequences),
                    structuralFactFocus(
                        navigation,
                        1,
                        lease,
                    ).focusable(enabled).testTag("sequence-structural-consequences"),
                )
            }
            item("summary:mode", contentType = "summary") {
                Column(
                    structuralFactFocus(navigation, 2, lease)
                        .semantics(mergeDescendants = true) {
                        }.focusable(enabled),
                ) {
                    Text(stringResource(R.string.sequence_structural_leave_explanation))
                    Text(stringResource(R.string.sequence_structural_close_explanation))
                    proposal?.let {
                        Text(
                            stringResource(
                                if (it.mode == SequenceHistoryStructuralRemovalMode.LEAVE_GAP) {
                                    R.string.sequence_history_leave_gap
                                } else {
                                    R.string.sequence_history_close_gap
                                },
                            ),
                        )
                    }
                }
            }
            item("summary:result", contentType = "summary") {
                Column(
                    structuralFactFocus(navigation, STRUCTURAL_SUMMARY_COUNT - 1, lease)
                        .semantics(mergeDescendants = true) {
                        }.focusable(enabled),
                ) {
                    when (stage) {
                        "mode" -> Text(stringResource(R.string.sequence_history_structural_mode_prompt))
                        "placements" -> {
                            Text(stringResource(R.string.sequence_history_ownerless_message))
                            val remaining =
                                (proposal?.ownerlessPlacements as? SequenceHistoryPlacementMap)?.unresolvedCount
                                    ?: proposal?.ownerlessPlacements?.values?.count { it == null } ?: 0
                            Text(
                                stringResource(R.string.sequence_structural_incomplete, remaining),
                                Modifier.semantics {
                                    liveRegion =
                                        LiveRegionMode.Polite
                                },
                            )
                        }
                        else -> {
                            StructuralResult(detail, requireNotNull(proposal))
                            if (state.overlapWarning) {
                                Text(
                                    stringResource(R.string.sequence_history_overlap_warning),
                                    Modifier
                                        .semantics {
                                            liveRegion = LiveRegionMode.Polite
                                        }.testTag("sequence-structural-overlap-warning"),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
            if (stage == "placements") {
                itemsIndexed(requireNotNull(proposal).ownerlessChoices, key = { _, choice ->
                    "choice:${choice.intervalId.value}"
                }, contentType = { _, _ -> "choice" }) { index, choice ->
                    Column(structuralFactFocus(navigation, index + STRUCTURAL_SUMMARY_COUNT, lease).selectableGroup()) {
                        Text(
                            stringResource(
                                R.string.sequence_history_ownerless_interval,
                                stringResource(intervalKindResource(choice.kind)),
                                choice.intervalId.value,
                            ),
                        )
                        Text(stringResource(R.string.sequence_history_ownerless))
                        OwnerlessIntervalPlacement.entries.forEach { placement ->
                            val fixed = placement == OwnerlessIntervalPlacement.FIXED
                            val placementKey = if (fixed) "fixed" else "translated"
                            val label =
                                stringResource(
                                    if (fixed) {
                                        R.string.sequence_history_ownerless_fixed_choice
                                    } else {
                                        R.string.sequence_history_ownerless_translated_choice
                                    },
                                    structuralInterval(
                                        if (fixed) choice.fixedStartedAt else choice.translatedStartedAt,
                                        if (fixed) choice.fixedEndedAt else choice.translatedEndedAt,
                                        detail.originalZoneId,
                                    ),
                                )
                            val selected = proposal.ownerlessPlacements[choice.intervalId] == placement
                            val action =
                                SequenceHistoryMutationAction.PlaceOwnerlessInterval(
                                    choice.intervalId,
                                    placement,
                                )
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .focusProperties {
                                        canFocus =
                                            lease.canFocus()
                                    }.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = {
                                        onAction(action)
                                    })
                                    .testTag(
                                        "sequence-history-ownerless-${choice.intervalId.value}-$placementKey",
                                    ).padding(MaterialTheme.spacing.small),
                            ) {
                                RadioButton(selected, onClick = null, enabled = enabled)
                                Text(label)
                            }
                        }
                    }
                }
            }
            if (stage == "review") {
                itemsIndexed(requireNotNull(proposal).changes, key = { _, change ->
                    structuralChangeKey(change)
                }, contentType = { _, change -> change.javaClass.name }) { index, change ->
                    val forced =
                        change is SequenceHistoryPreviewChange.OwnerlessPlacement &&
                            change.intervalId !in proposal.ownerlessPlacements
                    Text(
                        structuralChangeText(context, change) +
                            if (forced) " " + stringResource(R.string.sequence_structural_forced) else "",
                        structuralFactFocus(
                            navigation,
                            index + STRUCTURAL_SUMMARY_COUNT,
                            lease,
                        ).focusable(enabled).testTag("sequence-structural-change-${structuralChangeKey(change)}"),
                    )
                }
                // Deleted child mutation facts remain validation inputs, never resurrected visible actual results.
                itemsIndexed(deletedTranslations, key = { _, notice -> notice.key }) { index, notice ->
                    val text =
                        if (notice.pauseId == null) {
                            stringResource(R.string.sequence_structural_deleted_translation, notice.childId.value)
                        } else {
                            stringResource(
                                R.string.sequence_structural_deleted_pause,
                                notice.childId.value,
                                notice.pauseId.value,
                            )
                        }
                    Text(
                        text,
                        structuralFactFocus(navigation, STRUCTURAL_SUMMARY_COUNT + proposal.changes.size + index, lease)
                            .focusable(enabled)
                            .testTag("sequence-structural-notice-${notice.key}"),
                    )
                }
            }
        }
        state.issue?.let { SequenceMutationIssue(it) }
        if (state.isMutating) {
            Text(
                stringResource(R.string.history_saving),
                Modifier.semantics {
                    liveRegion =
                        LiveRegionMode.Polite
                },
            )
        }
        // Separate native nodes retire held presses at every destructive stage transition.
        key(stage, state.overlapWarning) {
            FlowRow(
                Modifier.then(keyboard),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                SequenceSecondaryButton(
                    { onAction(SequenceHistoryMutationAction.Cancel) },
                    enabled = enabled,
                    modifier =
                        Modifier
                            .then(
                                if (stage ==
                                    "placements"
                                ) {
                                    Modifier.focusRequester(primaryFocus)
                                } else {
                                    Modifier
                                },
                            ).testTag(
                                if (state.overlapWarning) {
                                    "sequence-history-overlap-cancel"
                                } else {
                                    "sequence-structural-cancel"
                                },
                            ),
                ) {
                    Text(stringResource(R.string.manual_history_cancel))
                }
                if (stage == "mode") {
                    SequenceHistoryStructuralRemovalMode.entries.forEach { mode ->
                        key(mode) {
                            SequenceSecondaryButton(
                                {
                                    onAction(SequenceHistoryMutationAction.ChooseStructuralMode(mode))
                                },
                                enabled = enabled,
                                modifier =
                                    Modifier.focusRequester(primaryFocus).testTag(
                                        if (mode ==
                                            SequenceHistoryStructuralRemovalMode.LEAVE_GAP
                                        ) {
                                            "sequence-history-leave-gap"
                                        } else {
                                            "sequence-history-close-gap"
                                        },
                                    ),
                            ) {
                                Text(
                                    stringResource(
                                        if (mode == SequenceHistoryStructuralRemovalMode.LEAVE_GAP) {
                                            R.string.sequence_history_leave_gap
                                        } else {
                                            R.string.sequence_history_close_gap
                                        },
                                    ),
                                )
                            }
                        }
                    }
                } else if (stage == "review") {
                    SequencePrimaryButton(
                        {
                            onAction(
                                if (state.overlapWarning) {
                                    SequenceHistoryMutationAction.ProceedOverlap
                                } else {
                                    SequenceHistoryMutationAction.ConfirmStructuralRemoval
                                },
                            )
                        },
                        enabled = enabled,
                        modifier =
                            Modifier.focusRequester(primaryFocus).testTag(
                                if (state.overlapWarning) {
                                    "sequence-history-overlap-proceed"
                                } else {
                                    "sequence-history-confirm-structural"
                                },
                            ),
                    ) {
                        Text(
                            stringResource(
                                if (state.overlapWarning) {
                                    R.string.history_overlap_proceed
                                } else {
                                    R.string.sequence_history_confirm_structural
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StructuralNavigator(
    count: Int,
    lease: SequenceHistoryNavigation,
    requester: FocusRequester,
    keyboard: Modifier,
    go: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var ordinal by remember { mutableStateOf("") }
    Column(keyboard) {
        SequenceSecondaryButton(
            { if (lease.isCurrent()) expanded = true },
            enabled = lease.isCurrent(),
            modifier = Modifier.focusRequester(requester).testTag("sequence-structural-navigate"),
        ) {
            Text(stringResource(R.string.sequence_structural_navigate))
        }
        DropdownMenu(expanded && lease.isCurrent(), onDismissRequest = { expanded = false }) {
            Text(
                stringResource(R.string.sequence_structural_navigation_help),
                Modifier.padding(MaterialTheme.spacing.small),
            )
            listOf(
                R.string.sequence_structural_summary_target,
                R.string.sequence_structural_summary_consequences,
                R.string.sequence_structural_summary_mode,
                R.string.sequence_structural_summary_result,
            ).forEachIndexed {
                index,
                resource,
                ->
                SequenceSecondaryButton({
                    if (lease.isCurrent()) {
                        expanded = false
                        go(index)
                    }
                }, enabled = lease.isCurrent()) {
                    Text(stringResource(resource))
                }
            }
            LifeTracingOutlinedTextField(
                ordinal,
                { if (lease.isCurrent()) ordinal = it },
                Modifier.testTag("sequence-structural-ordinal"),
                label = {
                    Text(stringResource(R.string.sequence_structural_ordinal, count))
                },
                enabled =
                    lease.isCurrent() && count > 0,
            )
            SequenceSecondaryButton(
                {
                    if (lease.isCurrent()) {
                        ordinal.toIntOrNull()?.takeIf { it in 1..count }?.let {
                            expanded = false
                            go(
                                it + STRUCTURAL_SUMMARY_COUNT - 1,
                            )
                        }
                    }
                },
                enabled = lease.isCurrent() && ordinal.toIntOrNull()?.let { it in 1..count } == true,
                modifier = Modifier.testTag("sequence-structural-go"),
            ) {
                Text(stringResource(R.string.sequence_timing_go))
            }
        }
    }
}

@Composable
private fun StructuralResult(
    detail: SequenceHistoryDetail,
    proposal: SequenceHistoryStructuralProposal,
) {
    val command = requireNotNull(proposal.command)
    val durations =
        remember(StructuralReference(proposal)) {
            runCatching {
                SequenceTimelineCalculator.calculate(
                    detail.root.startedAt,
                    command.finalEndedAt,
                    command.finalIntervals,
                )
            }.getOrNull()
        }
    Text(
        stringResource(
            R.string.sequence_history_final_root_end,
            exactHistoricalTimestamp(command.finalEndedAt, detail.originalZoneId),
        ),
    )
    Text(
        stringResource(
            R.string.sequence_structural_duration,
            sequenceDurationText(detail.root.activeDuration),
            (
                durations?.active?.let(::sequenceDurationText)
                    ?: stringResource(R.string.history_missing_value)
            ),
            sequenceDurationText(detail.root.pauseDuration),
            (
                durations?.pause?.let(::sequenceDurationText)
                    ?: stringResource(R.string.history_missing_value)
            ),
            sequenceDurationText(detail.root.wallDuration),
            (
                durations?.wall?.let(::sequenceDurationText)
                    ?: stringResource(R.string.history_missing_value)
            ),
        ),
    )
}

@Composable
private fun structuralInterval(
    start: Instant,
    end: Instant?,
    zone: ZoneId,
): String =
    exactHistoricalTimestamp(start, zone) + " \u2013 " +
        (end?.let { exactHistoricalTimestamp(it, zone) } ?: stringResource(R.string.history_missing_value))

@Composable
private fun structuralIntervalEndpointLabel(
    context: StructuralContext,
    intervalId: SequenceIntervalId,
    endpointLabel: Int,
): String {
    val interval = context.intervals.getValue(intervalId)
    val owner = interval.occurrenceId?.let(context.occurrences::getValue)
    return buildList {
        add(stringResource(endpointLabel, interval.id.value))
        add(stringResource(intervalKindResource(interval.kind)))
        if (owner == null) {
            add(stringResource(R.string.sequence_history_ownerless))
        } else {
            add(stringResource(R.string.sequence_history_owner_id, owner.occurrenceId.value))
            val status = stringResource(occurrenceStatusResource(owner.status))
            add(stringResource(R.string.sequence_structural_interval_owner_status, status))
        }
    }.joinToString(". ")
}

@Composable
private fun structuralChangeText(
    context: StructuralContext,
    change: SequenceHistoryPreviewChange,
): String {
    val zone = context.detail.originalZoneId

    fun occurrence(id: com.alexandr5476.lifetracing.domain.SequenceOccurrenceId) = context.occurrences.getValue(id)

    @Composable fun occurrenceText(value: SequenceHistoryOccurrence): String =
        buildList {
            add(stringResource(R.string.history_occurrence, value.runtimePosition + 1, value.activity.title))
            add(stringResource(R.string.sequence_history_occurrence_id, value.occurrenceId.value))
            add(stringResource(R.string.sequence_history_activity_snapshot_id, value.activitySnapshotId.value))
            value.sourceSequenceSnapshotNodeId?.let {
                add(
                    stringResource(R.string.sequence_history_source_id, it.value),
                )
            }
            value.repeatSourceSnapshotNodeId?.let { add(stringResource(R.string.sequence_history_repeat_id, it.value)) }
            value.repeatIteration?.let { add(stringResource(R.string.history_repeat_iteration, it)) }
            if (value.isRuntimeAdded) add(stringResource(R.string.history_runtime_added))
        }.joinToString(". ")
    return when (change) {
        is SequenceHistoryPreviewChange.Timestamp -> {
            val owner =
                when (val target = change.target) {
                    is SequenceHistoryTimestampTarget.OccurrenceEnteredAt -> occurrence(target.occurrenceId)
                    is SequenceHistoryTimestampTarget.OccurrenceCompletedAt -> occurrence(target.occurrenceId)
                    is SequenceHistoryTimestampTarget.ChildStartedAt -> occurrence(target.occurrenceId)
                    is SequenceHistoryTimestampTarget.ChildCompletedAt -> occurrence(target.occurrenceId)
                    else -> null
                }
            val child =
                change.target is SequenceHistoryTimestampTarget.ChildStartedAt ||
                    change.target is SequenceHistoryTimestampTarget.ChildCompletedAt
            val label =
                when (val target = change.target) {
                    SequenceHistoryTimestampTarget.RootStartedAt ->
                        stringResource(
                            R.string.sequence_history_root_started,
                        )
                    SequenceHistoryTimestampTarget.RootEndedAt -> stringResource(R.string.sequence_history_root_ended)
                    is SequenceHistoryTimestampTarget.OccurrenceEnteredAt ->
                        stringResource(
                            R.string.sequence_history_occurrence_entered,
                            occurrenceText(requireNotNull(owner)),
                        )
                    is SequenceHistoryTimestampTarget.OccurrenceCompletedAt ->
                        stringResource(
                            R.string.sequence_history_occurrence_completed,
                            occurrenceText(requireNotNull(owner)),
                        )
                    is SequenceHistoryTimestampTarget.ChildStartedAt ->
                        stringResource(
                            R.string.sequence_history_child_started,
                            occurrenceText(requireNotNull(owner)),
                        )
                    is SequenceHistoryTimestampTarget.ChildCompletedAt ->
                        stringResource(
                            R.string.sequence_history_child_completed,
                            occurrenceText(requireNotNull(owner)),
                        )
                    is SequenceHistoryTimestampTarget.IntervalStartedAt ->
                        structuralIntervalEndpointLabel(
                            context,
                            target.intervalId,
                            R.string.sequence_history_interval_started,
                        )
                    is SequenceHistoryTimestampTarget.IntervalEndedAt ->
                        structuralIntervalEndpointLabel(
                            context,
                            target.intervalId,
                            R.string.sequence_history_interval_ended,
                        )
                } +
                    if (child) {
                        ". " +
                            stringResource(
                                R.string.sequence_timing_child_id,
                                requireNotNull(owner?.child).executionId.value,
                            )
                    } else {
                        ""
                    }
            val endpointZone = if (child) requireNotNull(owner?.child).originalZoneId else zone
            stringResource(
                R.string.sequence_timing_before_after,
                label,
                exactHistoricalTimestamp(change.before, endpointZone),
                exactHistoricalTimestamp(change.after, endpointZone),
            )
        }
        is SequenceHistoryPreviewChange.RemovedOccurrence ->
            stringResource(
                R.string.sequence_history_removed_occurrence,
                occurrenceText(occurrence(change.occurrence.occurrenceId)),
            )
        is SequenceHistoryPreviewChange.RemovedInterval ->
            stringResource(
                R.string.sequence_history_removed_interval,
                stringResource(intervalKindResource(change.interval.kind)),
                change.interval.intervalId.value,
                structuralInterval(change.interval.startedAt, change.interval.endedAt, zone),
            )
        is SequenceHistoryPreviewChange.OwnerlessPlacement ->
            stringResource(
                R.string.sequence_history_ownerless_selection,
                stringResource(intervalKindResource(change.kind)),
                change.intervalId.value,
                stringResource(
                    if (change.placement ==
                        OwnerlessIntervalPlacement.FIXED
                    ) {
                        R.string.sequence_history_fixed
                    } else {
                        R.string.sequence_history_translated
                    },
                ),
                structuralInterval(change.resultingStartedAt, change.resultingEndedAt, zone),
            )
        is SequenceHistoryPreviewChange.ChildPauseTimestamp -> {
            val owner = occurrence(change.occurrence.occurrenceId)
            val child = requireNotNull(owner.child)
            stringResource(
                R.string.sequence_timing_before_after,
                stringResource(R.string.sequence_history_child_pause, occurrenceText(owner), change.pauseId.value) +
                    ". " +
                    stringResource(R.string.sequence_timing_child_id, child.executionId.value),
                structuralInterval(change.beforeStartedAt, change.beforeEndedAt, child.originalZoneId),
                structuralInterval(change.afterStartedAt, change.afterEndedAt, child.originalZoneId),
            )
        }
    }
}
