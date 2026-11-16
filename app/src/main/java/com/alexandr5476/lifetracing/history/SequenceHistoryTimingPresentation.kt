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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrence
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.onFocusTargetAttachmentChanged
import com.alexandr5476.lifetracing.ui.exactHistoricalTimestamp
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import java.time.ZoneOffset

/** Index references the canonical collections; no per-target occurrence search or repository access. */
private class TimingContext(
    val detail: SequenceHistoryDetail,
) {
    val occurrences = detail.occurrences.associateBy { it.occurrenceId }
    val intervals = detail.intervals.associateBy { it.id }

    fun occurrence(target: SequenceHistoryTimestampTarget): SequenceHistoryOccurrence? =
        when (target) {
            is SequenceHistoryTimestampTarget.OccurrenceEnteredAt -> occurrences[target.occurrenceId]
            is SequenceHistoryTimestampTarget.OccurrenceCompletedAt -> occurrences[target.occurrenceId]
            is SequenceHistoryTimestampTarget.ChildStartedAt -> occurrences[target.occurrenceId]
            is SequenceHistoryTimestampTarget.ChildCompletedAt -> occurrences[target.occurrenceId]
            else -> interval(target)?.occurrenceId?.let(occurrences::get)
        }

    fun interval(target: SequenceHistoryTimestampTarget): SequenceInterval? =
        when (target) {
            is SequenceHistoryTimestampTarget.IntervalStartedAt -> intervals[target.intervalId]
            is SequenceHistoryTimestampTarget.IntervalEndedAt -> intervals[target.intervalId]
            else -> null
        }
}

@Composable
private fun timingLabel(
    context: TimingContext,
    target: SequenceHistoryTimestampTarget,
): String {
    val kind =
        when (target) {
            SequenceHistoryTimestampTarget.RootStartedAt -> R.string.sequence_history_root_started
            SequenceHistoryTimestampTarget.RootEndedAt -> R.string.sequence_history_root_ended
            is SequenceHistoryTimestampTarget.OccurrenceEnteredAt -> R.string.sequence_timing_entered
            is SequenceHistoryTimestampTarget.OccurrenceCompletedAt -> R.string.sequence_timing_completed
            is SequenceHistoryTimestampTarget.ChildStartedAt -> R.string.sequence_timing_child_started
            is SequenceHistoryTimestampTarget.ChildCompletedAt -> R.string.sequence_timing_child_completed
            is SequenceHistoryTimestampTarget.IntervalStartedAt -> R.string.sequence_timing_interval_started
            is SequenceHistoryTimestampTarget.IntervalEndedAt -> R.string.sequence_timing_interval_ended
        }
    val parts =
        mutableListOf(
            stringResource(kind),
            context.detail.root.title,
            stringResource(R.string.sequence_history_execution_id, context.detail.root.executionId.value),
        )
    context.occurrence(target)?.let { occurrence ->
        parts += stringResource(R.string.history_occurrence, occurrence.runtimePosition + 1, occurrence.activity.title)
        parts += stringResource(R.string.sequence_timing_occurrence_id, occurrence.occurrenceId.value)
        if (occurrence.sourceSequenceSnapshotNodeId != null) parts += stringResource(R.string.history_source_step)
        occurrence.repeatIteration?.let { parts += stringResource(R.string.history_repeat_iteration, it) }
        if (occurrence.isRuntimeAdded) parts += stringResource(R.string.history_runtime_added)
        if (target is SequenceHistoryTimestampTarget.ChildStartedAt ||
            target is SequenceHistoryTimestampTarget.ChildCompletedAt
        ) {
            occurrence.child?.let { parts += stringResource(R.string.sequence_timing_child_id, it.executionId.value) }
        }
    }
    context.interval(target)?.let {
        parts += stringResource(intervalKindResource(it.kind))
        parts += stringResource(R.string.sequence_timing_interval_id, it.id.value)
        if (it.occurrenceId == null) parts += stringResource(R.string.sequence_history_ownerless)
    }
    return parts.joinToString(". ")
}

/** One request binds a typed target (null is the review warning), index and originating lease. */
internal class TimingJump(
    val target: SequenceHistoryTimestampTarget?,
    val index: Int,
    val lease: SequenceHistoryNavigation,
    val scope: CoroutineScope,
) {
    var scrolled by mutableStateOf(false)
}

/** Read-only navigation with no suspending scroll continuation past the authority check. */
internal class TimingNavigation(
    private val lease: SequenceHistoryNavigation,
    private val indexOf: (SequenceHistoryTimestampTarget?) -> Int?,
) {
    private var request by mutableStateOf<TimingJump?>(null)
    private var attached = true
    val pending: TimingJump? get() = request?.takeIf(::isPending)
    val requester = FocusRequester()

    fun cancelPending() {
        request = null
    }

    fun dispose() {
        attached = false
        cancelPending()
    }

    fun isPending(jump: TimingJump): Boolean {
        val current =
            attached &&
                request === jump &&
                jump.lease === lease &&
                jump.scope.isActive &&
                lease.isCurrent() &&
                indexOf(jump.target) == jump.index
        if (!current && request === jump) cancelPending()
        return current
    }

    fun go(
        target: SequenceHistoryTimestampTarget?,
        scope: CoroutineScope,
        requestScroll: (Int) -> Unit,
    ) {
        if (!attached || !scope.isActive || !lease.isCurrent()) return
        val index = indexOf(target) ?: return
        val jump = TimingJump(target, index, lease, scope)
        request = jump
        lease.launch(scope) {
            try {
                withFrameNanos { }
                if (isPending(jump)) {
                    requestScroll(index)
                    if (isPending(jump)) jump.scrolled = true
                }
            } finally {
                if (!scope.isActive || !lease.isCurrent()) {
                    if (request === jump) cancelPending()
                }
            }
        }
    }

    fun focus(
        jump: TimingJump,
        attached: Boolean,
        available: Boolean,
        requestFocus: () -> Boolean,
    ): Boolean {
        val ready = isPending(jump) && jump.scrolled
        if (!ready || !attached || !available) return false
        val focused = requestFocus()
        if (focused && request === jump) cancelPending()
        return focused
    }
}

private class TimingOwnerReference(
    private val owner: Any,
) {
    override fun equals(other: Any?): Boolean = other is TimingOwnerReference && owner === other.owner

    override fun hashCode(): Int = System.identityHashCode(owner)
}

@Composable
private fun rememberTimingNavigation(
    lease: SequenceHistoryNavigation,
    destinations: SequenceTimingDestinations,
    owner: Any,
    prefix: Int = 0,
): TimingNavigation {
    val reference = TimingOwnerReference(owner)
    val currentOwner by rememberUpdatedState(reference)
    val currentDestinations by rememberUpdatedState(destinations)
    val navigation =
        remember(lease, destinations, reference, prefix) {
            TimingNavigation(lease) { target ->
                when {
                    currentOwner != reference || currentDestinations !== destinations -> null
                    target == null -> 0.takeIf { prefix == 1 }
                    else -> destinations.positions[target]?.plus(prefix)
                }
            }
        }
    DisposableEffect(navigation) { onDispose { navigation.dispose() } }
    return navigation
}

@Composable
private fun timingFocus(
    navigation: TimingNavigation,
    target: SequenceHistoryTimestampTarget?,
    enabled: Boolean,
    lease: SequenceHistoryNavigation,
): Modifier {
    val focus = LocalHistoryFocus.current
    val window = LocalWindowInfo.current
    val mode = LocalInputModeManager.current.inputMode
    val request = navigation.pending?.takeIf { it.target == target && it.scrolled }
    var attached by remember { mutableStateOf(false) }
    LaunchedEffect(navigation, request, enabled, attached, window.isWindowFocused, mode) {
        val available = enabled && attached && window.isWindowFocused
        if (request != null && available) {
            withFrameNanos { }
            navigation.focus(request, attached, enabled && window.isWindowFocused) {
                navigation.requester.requestFocus().also { focused ->
                    if (focused) {
                        focus.removed = null
                        focus.awaitingRead = false
                        focus.landingPending = false
                    }
                }
            }
        }
    }
    return historyFocusTarget(if (request != null) navigation.requester else null, enabled)
        .onFocusTargetAttachmentChanged { attached = it }
        .focusProperties { canFocus = lease.canFocus() }
}

/** Hardware pane navigation moves native focus only; Enter still uses S1's guarded action. */
@Composable
private fun timingKeyboard(
    lease: SequenceHistoryNavigation,
    navigation: TimingNavigation,
    navigationFocus: FocusRequester,
    primaryFocus: FocusRequester,
): Modifier {
    val focus = LocalHistoryFocus.current
    return Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.F6 && lease.isCurrent()) {
            navigation.cancelPending()
            focus.removed = null
            focus.awaitingRead = false
            focus.landingPending = false
            if (event.isShiftPressed) primaryFocus.requestFocus() else navigationFocus.requestFocus()
        } else {
            false
        }
    }
}

@Composable
private fun timingSectionLabel(section: SequenceTimingSection): String =
    stringResource(
        when (section) {
            SequenceTimingSection.TIMESTAMPS -> R.string.sequence_timing_section_timestamps
            SequenceTimingSection.ROOT -> R.string.sequence_timing_section_root
            SequenceTimingSection.OCCURRENCES -> R.string.sequence_timing_section_occurrences
            SequenceTimingSection.CHILDREN -> R.string.sequence_timing_section_children
            SequenceTimingSection.INTERVALS -> R.string.sequence_timing_section_intervals
        },
    )

@Composable
private fun TimingNavigator(
    destinations: SequenceTimingDestinations,
    list: LazyListState,
    navigation: TimingNavigation,
    lease: SequenceHistoryNavigation,
    keyboard: Modifier,
    navigationFocus: FocusRequester,
) {
    var ordinal by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(SequenceTimingSection.TIMESTAMPS) }
    var expanded by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val enabled = lease.isCurrent()
    val submittedOrdinal = ordinal
    val submittedSection = section
    val count = destinations.count(section)
    LaunchedEffect(lease) {
        expanded = false
        unavailable = false
    }
    Column(keyboard) {
        SequenceSecondaryButton(
            { if (lease.isCurrent()) expanded = true },
            modifier =
                Modifier.testTag("sequence-timing-section").focusRequester(navigationFocus).focusProperties {
                    canFocus =
                        lease.canFocus()
                },
            enabled = enabled,
        ) { Text(stringResource(R.string.sequence_timing_navigate_by, timingSectionLabel(section))) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SequenceTimingSection.entries.forEach { option ->
                val sectionCount = destinations.count(option)
                val label = timingSectionLabel(option)
                DropdownMenuItem(
                    text = {
                        Text(
                            if (sectionCount ==
                                0
                            ) {
                                stringResource(R.string.sequence_timing_empty_section, label)
                            } else {
                                label
                            },
                        )
                    },
                    modifier =
                        Modifier.semantics { selected = section == option }.focusProperties {
                            canFocus =
                                lease.canFocus()
                        },
                    enabled = enabled && sectionCount > 0,
                    onClick = {
                        if (lease.isCurrent() && destinations.count(option) > 0) {
                            section = option
                            ordinal = ""
                            unavailable = false
                            expanded = false
                        }
                    },
                )
            }
            Text(
                stringResource(R.string.sequence_timing_keyboard_help),
                Modifier
                    .padding(
                        MaterialTheme.spacing.small,
                    ).focusProperties { canFocus = lease.canFocus() }
                    .focusable(enabled),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            LifeTracingOutlinedTextField(
                ordinal,
                {
                    if (lease.isCurrent()) {
                        ordinal = it
                        unavailable = false
                    }
                },
                Modifier
                    .weight(1f)
                    .testTag("sequence-timing-ordinal")
                    .then(historyFocusTarget(enabled = enabled))
                    .focusProperties { canFocus = lease.canFocus() },
                label = {
                    Text(
                        stringResource(R.string.sequence_timing_owner_ordinal, timingSectionLabel(section), count),
                    )
                },
                enabled = enabled,
            )
            SequenceSecondaryButton(
                {
                    if (ordinal == submittedOrdinal && section == submittedSection && lease.isCurrent()) {
                        val index = submittedOrdinal.toIntOrNull()?.let { destinations.index(submittedSection, it) }
                        unavailable = index == null
                        if (index != null) {
                            navigation.go(destinations.target(index), scope) { list.requestScrollToItem(it) }
                        }
                    }
                },
                enabled = enabled && ordinal.toIntOrNull()?.let { it in 1..count } == true,
                modifier = Modifier.testTag("sequence-timing-go").focusProperties { canFocus = lease.canFocus() },
            ) { Text(stringResource(R.string.sequence_timing_go)) }
        }
        if (unavailable) {
            Text(
                stringResource(R.string.sequence_timing_no_owner_target),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
internal fun ColumnScope.SequenceTimingEditor(
    detail: SequenceHistoryDetail,
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
) {
    val draft = requireNotNull(state.timingDraft)
    val context = remember(SequenceDetailReference(detail)) { TimingContext(detail) }
    // Target membership/order is fixed for an editor lifetime. Keystrokes never rebuild this list.
    val targets = remember(SequenceDetailReference(detail)) { draft.timestamps.keys.toList() }
    val destinations =
        remember(SequenceDetailReference(detail), targets) { SequenceTimingDestinations(detail, targets) }
    val positions = destinations.positions
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lease = rememberSequenceHistoryNavigation(detail, hasTiming = true, hasIssue = state.issue != null)
    val navigation = rememberTimingNavigation(lease, destinations, draft)
    val enabled = lease.isCurrent()
    val navigationFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    val keyboard = timingKeyboard(lease, navigation, navigationFocus, primaryFocus)
    val focus = LocalHistoryFocus.current
    LaunchedEffect(Unit) {
        focus.removed = null
        focus.awaitingRead = false
        focus.landingPending = false
        if (targets.isNotEmpty()) navigation.go(targets.first(), scope) { list.requestScrollToItem(it) }
    }
    LaunchedEffect(state.invalidTimestampTarget, state.publication) {
        state.invalidTimestampTarget?.takeIf { it in positions }?.let { target ->
            navigation.go(target, scope) { list.requestScrollToItem(it) }
        }
    }
    TimingNavigator(destinations, list, navigation, lease, keyboard, navigationFocus)
    LazyColumn(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .testTag("sequence-history-timing-list")
            .then(keyboard),
        state = list,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        itemsIndexed(targets, key = {
            _,
            target,
            ->
            timestampTargetKey(target)
        }, contentType = { _, _ -> "timestamp" }) { index, target ->
            val value = draft.timestamps.getValue(target)
            val admission = requireNotNull(LocalSequenceHistoryAdmission.current)
            val input = remember(admission, target) { admission.input(target) }
            val label = timingLabel(context, target)
            val issue = state.issue?.takeIf { state.invalidTimestampTarget == target }
            val errorText =
                issue?.let {
                    stringResource(
                        if (it == SequenceHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME &&
                            value.selectedOffset != null &&
                            value.selectedOffset !in value.validOffsets
                        ) {
                            R.string.sequence_timing_invalid_offset
                        } else {
                            sequenceTimingIssueResource(it)
                        },
                    )
                }
            val inputZone = stringResource(R.string.sequence_timing_input_zone, detail.originalZoneId.id)
            val recordedZone =
                context
                    .occurrence(target)
                    ?.child
                    ?.takeIf {
                        (
                            target is SequenceHistoryTimestampTarget.ChildStartedAt ||
                                target is SequenceHistoryTimestampTarget.ChildCompletedAt
                        ) &&
                            it.originalZoneId != detail.originalZoneId
                    }?.let { stringResource(R.string.sequence_timing_recorded_zone, it.originalZoneId.id) }
            val inputDescription = listOfNotNull(label, inputZone, recordedZone).joinToString(". ")
            Column {
                Text(stringResource(R.string.sequence_timing_position, index + 1, targets.size))
                Text(inputZone)
                recordedZone?.let { Text(it) }
                LifeTracingOutlinedTextField(
                    value.text,
                    { input(it) },
                    Modifier
                        .fillMaxWidth()
                        .testTag("sequence-history-timestamp-${timestampTargetKey(target)}")
                        .then(timingFocus(navigation, target, enabled, lease))
                        .semantics {
                            contentDescription = inputDescription
                            setText { enabled && input(it.text) }
                            if (errorText != null) error(errorText)
                        },
                    label = { Text(label) },
                    enabled = enabled,
                    isError = issue != null,
                    supportingText =
                        errorText?.let {
                            {
                                Text(
                                    it,
                                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                        },
                )
                if (value.validOffsets.size == 2) {
                    Column(Modifier.selectableGroup()) {
                        value.validOffsets.forEachIndexed { offsetIndex, offset ->
                            val action =
                                admission.action(
                                    SequenceHistoryMutationAction.SelectTimestampOffset(target, offset),
                                )
                            val choice =
                                stringResource(
                                    if (offsetIndex ==
                                        0
                                    ) {
                                        R.string.manual_history_first_occurrence
                                    } else {
                                        R.string.manual_history_second_occurrence
                                    },
                                    "UTC${if (offset == ZoneOffset.UTC) "+00:00" else offset.id}",
                                )
                            FlowRow(
                                Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .focusProperties { canFocus = lease.canFocus() }
                                    .selectable(
                                        selected = value.selectedOffset == offset,
                                        enabled = enabled,
                                        role = Role.RadioButton,
                                        onClick = action,
                                    ).padding(MaterialTheme.spacing.small),
                            ) {
                                RadioButton(value.selectedOffset == offset, onClick = null, enabled = enabled)
                                Text(label)
                                Text(choice)
                            }
                        }
                    }
                }
            }
        }
    }
    val noChangeFocus = remember { FocusRequester() }
    LaunchedEffect(state.noTimingChanges) {
        if (state.noTimingChanges) {
            withFrameNanos { }
            if (lease.isCurrent()) noChangeFocus.requestFocus()
        }
    }
    if (state.noTimingChanges) {
        Text(
            stringResource(R.string.sequence_history_no_timing_changes),
            Modifier
                .then(historyFocusTarget(noChangeFocus, enabled))
                .focusProperties { canFocus = lease.canFocus() }
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                }.focusable(enabled),
        )
    }
    if (state.issue != null && state.invalidTimestampTarget == null) SequenceMutationIssue(state.issue)
    state.invalidTimestampTarget?.let { target ->
        SequenceSecondaryButton(
            { navigation.go(target, scope) { list.requestScrollToItem(it) } },
            enabled = enabled,
            modifier = Modifier.testTag("sequence-timing-error-target").focusProperties { canFocus = lease.canFocus() },
        ) { Text(stringResource(R.string.sequence_timing_go_error)) }
    }
    FlowRow(modifier = keyboard, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        SequenceSecondaryButton(
            {
                onAction(SequenceHistoryMutationAction.Cancel)
            },
            modifier =
                Modifier.focusProperties {
                    canFocus = lease.canFocus()
                },
            enabled = enabled,
        ) { Text(stringResource(R.string.manual_history_cancel)) }
        SequencePrimaryButton(
            { onAction(SequenceHistoryMutationAction.ReviewTiming) },
            enabled = enabled,
            modifier =
                Modifier.testTag("sequence-history-review-timing").focusRequester(primaryFocus).focusProperties {
                    canFocus =
                        lease.canFocus()
                },
        ) { Text(stringResource(R.string.sequence_history_review_timing)) }
    }
}

@Composable
internal fun ColumnScope.SequenceTimingReview(
    detail: SequenceHistoryDetail,
    state: SequenceHistoryMutationState,
    onAction: (SequenceHistoryMutationAction) -> Unit,
) {
    val proposal = requireNotNull(state.timingProposal)
    val destinations =
        remember(TimingOwnerReference(proposal), SequenceDetailReference(detail)) {
            SequenceTimingDestinations(
                detail,
                proposal.changes.map { (it as SequenceHistoryPreviewChange.Timestamp).target },
            )
        }
    val prefix = if (state.overlapWarning) 1 else 0
    val context = remember(SequenceDetailReference(detail)) { TimingContext(detail) }
    val list = rememberLazyListState()
    val lease = rememberSequenceHistoryNavigation(detail, hasTiming = true, hasIssue = state.issue != null)
    val navigation = rememberTimingNavigation(lease, destinations, proposal, prefix)
    val enabled = lease.isCurrent()
    val navigationFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    val keyboard = timingKeyboard(lease, navigation, navigationFocus, primaryFocus)
    val heading = remember { FocusRequester() }
    val focus = LocalHistoryFocus.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.overlapWarning) {
        withFrameNanos { }
        if (lease.isCurrent()) {
            focus.removed = null
            focus.awaitingRead = false
            focus.landingPending = false
            if (state.overlapWarning) {
                navigation.go(null, scope) { list.requestScrollToItem(it) }
            } else {
                heading.requestFocus()
            }
        }
    }
    Text(
        stringResource(R.string.sequence_history_timing_review),
        Modifier
            .then(historyFocusTarget(heading, enabled))
            .focusProperties { canFocus = lease.canFocus() }
            .semantics {
                heading()
            }.focusable(enabled),
        style = MaterialTheme.typography.titleMedium,
    )
    TimingNavigator(destinations, list, navigation, lease, keyboard, navigationFocus)
    LazyColumn(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .testTag("sequence-history-timing-review-list")
            .then(keyboard),
        state = list,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        if (state.overlapWarning) {
            item("overlap-warning", contentType = "warning") {
                Text(
                    stringResource(R.string.sequence_history_overlap_warning),
                    Modifier
                        .then(timingFocus(navigation, null, enabled, lease))
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .focusable(enabled),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        itemsIndexed(proposal.changes, key = {
            _,
            change,
            ->
            timestampTargetKey((change as SequenceHistoryPreviewChange.Timestamp).target)
        }) { _, fact ->
            val change = fact as SequenceHistoryPreviewChange.Timestamp
            val child = context.occurrence(change.target)?.child
            val zone =
                if (change.target is SequenceHistoryTimestampTarget.ChildStartedAt ||
                    change.target is SequenceHistoryTimestampTarget.ChildCompletedAt
                ) {
                    requireNotNull(child).originalZoneId
                } else {
                    detail.originalZoneId
                }
            Text(
                stringResource(
                    R.string.sequence_timing_before_after,
                    timingLabel(context, change.target),
                    exactHistoricalTimestamp(change.before, zone),
                    exactHistoricalTimestamp(change.after, zone),
                ),
                Modifier
                    .testTag("sequence-history-change-${timestampTargetKey(change.target)}")
                    .then(timingFocus(navigation, change.target, enabled, lease))
                    .focusable(enabled),
            )
        }
    }
    state.issue?.let { SequenceMutationIssue(it) }
    if (state.isMutating) {
        Text(
            stringResource(R.string.sequence_timing_saving),
            Modifier.semantics {
                liveRegion =
                    LiveRegionMode.Polite
            },
        )
    }
    // Retire any pressed native control before approval exposes a separate Save action.
    key(state.overlapWarning) {
        FlowRow(modifier = keyboard, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            SequenceSecondaryButton(
                {
                    onAction(
                        if (state.overlapWarning) {
                            SequenceHistoryMutationAction.CancelOverlap
                        } else {
                            SequenceHistoryMutationAction.Cancel
                        },
                    )
                },
                enabled = enabled,
                modifier =
                    Modifier.focusProperties { canFocus = lease.canFocus() }.testTag(
                        if (state.overlapWarning) {
                            "sequence-history-overlap-cancel"
                        } else {
                            "sequence-history-cancel-review"
                        },
                    ),
            ) {
                Text(stringResource(R.string.manual_history_cancel))
            }
            SequencePrimaryButton(
                {
                    onAction(
                        if (state.overlapWarning) {
                            SequenceHistoryMutationAction.ProceedOverlap
                        } else {
                            SequenceHistoryMutationAction.ConfirmTiming
                        },
                    )
                },
                enabled = enabled,
                modifier =
                    Modifier.focusRequester(primaryFocus).focusProperties { canFocus = lease.canFocus() }.testTag(
                        if (state.overlapWarning) {
                            "sequence-history-overlap-proceed"
                        } else {
                            "sequence-history-confirm-timing"
                        },
                    ),
            ) {
                Text(
                    stringResource(
                        if (state.overlapWarning) {
                            R.string.history_overlap_proceed
                        } else {
                            R.string.history_save_correction
                        },
                    ),
                )
            }
        }
    }
}

internal fun sequenceTimingIssueResource(issue: SequenceHistoryMutationIssue): Int =
    when (issue) {
        SequenceHistoryMutationIssue.INVALID_DATE_TIME -> R.string.manual_history_invalid_datetime
        SequenceHistoryMutationIssue.NONEXISTENT_LOCAL_TIME -> R.string.manual_history_nonexistent_time
        SequenceHistoryMutationIssue.AMBIGUOUS_LOCAL_TIME -> R.string.manual_history_ambiguous_time
        else -> R.string.sequence_history_invalid_proposal
    }

@Composable
internal fun SequenceCommittedStatus(state: SequenceHistoryMutationState) {
    val family =
        stringResource(
            when (state.committedMutation) {
                SequenceHistoryCommittedMutation.TIMING -> R.string.sequence_timing_correction
                SequenceHistoryCommittedMutation.CHILD_DELETION -> R.string.sequence_timing_child_deleted
                else -> R.string.sequence_timing_structure_changed
            },
        )
    Text(
        stringResource(
            when (state.load) {
                HistoryDetailLoad.Loading -> R.string.sequence_timing_committed_loading
                is HistoryDetailLoad.Content -> R.string.sequence_timing_committed_loaded
                else -> R.string.sequence_timing_committed_read_failure
            },
            family,
        ),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
