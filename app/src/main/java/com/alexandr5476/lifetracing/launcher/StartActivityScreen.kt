@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "ComplexCondition",
)

package com.alexandr5476.lifetracing.launcher

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityLaunchMainValue
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.LibraryTrackableKind
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.components.LifeTracingLinearProgressIndicator
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
internal fun StartActivityRoute(
    session: StartActivityRouteSession,
    onBack: () -> Unit,
    onCommitted: () -> Unit,
) {
    val controller = session.controller
    val state by controller.state.collectAsState()
    val exitPolicy = session.exitPolicy
    val exitRoute = {
        exitPolicy.requestExit(controller::arbitrateRouteExit, onBack, onCommitted)
    }
    BackHandler(enabled = true) {
        if (state.oneOff != null &&
            state.command !is LauncherCommandState.Committing &&
            state.command !is LauncherCommandState.Committed &&
            state.command !is LauncherCommandState.CommittedCoordinationFailure
        ) {
            controller.dispatch(StartActivityAction.CloseOneOff)
        } else if (state.options != LauncherLoad.Idle &&
            state.command !is LauncherCommandState.Committing &&
            state.command !is LauncherCommandState.Committed &&
            state.command !is LauncherCommandState.CommittedCoordinationFailure
        ) {
            controller.dispatch(StartActivityAction.CloseOptions)
        } else {
            exitRoute()
        }
    }
    LaunchedEffect(state.command) {
        exitPolicy.onCommand(state.command, onCommitted)
    }
    StartActivityScreen(state, controller::dispatch, session.interaction, exitRoute)
}

@Suppress("CyclomaticComplexMethod") // Launcher sections remain in their product order.
@Composable
internal fun StartActivityScreen(
    state: StartActivityState,
    onAction: (StartActivityAction) -> Unit,
    interaction: StartActivityRouteInteraction,
    onRouteBack: () -> Unit = {},
) {
    val selected = state.selected
    val quickEditor = interaction.quickEditor
    val quickTarget =
        ((selected as? LauncherLoad.Content)?.value as? LibraryLaunchTarget.Activity)
            ?.takeIf { it.id == quickEditor?.targetId && it.mainValue?.fieldId == quickEditor.fieldId }
    val options: (LibraryTemplateId) -> Unit = { id ->
        if (id is LibraryTemplateId.Activity) onAction(StartActivityAction.OpenOptions(id.id))
    }
    val select: (LibraryTemplateId) -> Unit = { id ->
        interaction.select(id)
        onAction(StartActivityAction.Select(id))
    }
    LaunchedEffect(selected, interaction.pendingSelectionId) {
        if (state.oneOff != null) return@LaunchedEffect
        val target = (selected as? LauncherLoad.Content)?.value ?: return@LaunchedEffect
        interaction.resolveSelection(target)?.let(onAction)
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(MaterialTheme.spacing.xLarge),
        ) {
            item {
                LauncherHeader(onBack = {
                    when {
                        state.command is LauncherCommandState.Committing ||
                            state.command is LauncherCommandState.Committed ||
                            state.command is LauncherCommandState.CommittedCoordinationFailure -> onRouteBack()
                        state.oneOff != null -> onAction(StartActivityAction.CloseOneOff)
                        state.options != LauncherLoad.Idle -> onAction(StartActivityAction.CloseOptions)
                        interaction.browsePath.isNotEmpty() ->
                            onAction(StartActivityAction.Browse(interaction.browseBack()))
                        else -> onRouteBack()
                    }
                })
            }
            item { CommandPresentation(state.command, (selected as? LauncherLoad.Content)?.value?.name, onAction) }
            if (state.options != LauncherLoad.Idle) item { StartOptionsPanel(state.options, state.command, onAction) }
            state.oneOff?.let { draft -> item { OneOffPanel(draft, state.command, onAction) } }
            item { OrganizationFailure(state.organizationFailure) }
            item { TargetLoading(selected, onAction) }
            quickTarget?.let { target ->
                item {
                    QuickMainValueCard(
                        target = target,
                        editor = requireNotNull(quickEditor),
                        onValueChange = interaction::editQuickValue,
                        onCancel = interaction::cancelQuickEditor,
                        onComplete = { interaction.completeQuickEditor(target)?.let(onAction) },
                    )
                }
            }
            when (val home = state.home) {
                LauncherLoad.Loading -> item { LauncherLoading() }
                is LauncherLoad.Failure -> item { LauncherFailure(R.string.launcher_home_failure, onAction) }
                is LauncherLoad.Content -> {
                    item {
                        TrackableSection(
                            title = R.string.launcher_recent,
                            empty = R.string.launcher_recent_empty,
                            items = home.value.recent,
                            enabled = state.canSelect(quickEditor != null),
                            onSelect = select,
                            onOptions = options,
                        )
                    }
                    item {
                        PinnedSection(
                            canonical = home.value.pinned,
                            organizationInFlight = state.organizationInFlight,
                            organizationFailure = state.organizationFailure,
                            enabled = state.canSelect(quickEditor != null),
                            onSelect = select,
                            onOptions = options,
                            onReorder = { onAction(StartActivityAction.ReorderPinned(it)) },
                        )
                    }
                }
                LauncherLoad.Idle -> Unit
            }
            item {
                SearchSection(
                    query = state.searchQuery,
                    state = state.search,
                    enabled = state.canSelect(quickEditor != null),
                    onSearch = { onAction(StartActivityAction.Search(it)) },
                    onSelect = select,
                    onOptions = options,
                    onRetry = { onAction(StartActivityAction.Retry) },
                )
            }
            item {
                BrowseSection(
                    state = state.browse,
                    breadcrumbs = interaction.browsePath,
                    enabled = state.canSelect(quickEditor != null),
                    onBrowse = { folder ->
                        if (folder == null) interaction.openRoot()
                        onAction(StartActivityAction.Browse(folder))
                    },
                    onFolder = { folder ->
                        interaction.openFolder(folder)
                        onAction(StartActivityAction.Browse(folder.id))
                    },
                    onSelect = select,
                    onOptions = options,
                    onRetry = { onAction(StartActivityAction.Retry) },
                )
            }
            if (state.oneOff == null) {
                item {
                    LauncherCard {
                        LifeTracingSecondaryButton(
                            onClick = { onAction(StartActivityAction.OpenOneOff) },
                            enabled = state.canSelect(quickEditor != null),
                            modifier = Modifier.testTag("launcher-new-one-off"),
                        ) { Text(stringResource(R.string.launcher_new_one_off)) }
                    }
                }
            }
        }
    }
}

private fun StartActivityState.canSelect(quickEditorOpen: Boolean): Boolean =
    !organizationInFlight &&
        !quickEditorOpen &&
        options == LauncherLoad.Idle &&
        oneOff == null &&
        selected !is LauncherLoad.Loading &&
        command == LauncherCommandState.Idle

@Composable
private fun OneOffPanel(
    draft: OneOffDraft,
    command: LauncherCommandState,
    onAction: (StartActivityAction) -> Unit,
) {
    val enabled =
        command == LauncherCommandState.Idle ||
            command is LauncherCommandState.Conflict ||
            command is LauncherCommandState.Rejected
    val valid = runCatching { draft.configuration() }.isSuccess
    LauncherCard {
        Text(stringResource(R.string.launcher_new_one_off), style = MaterialTheme.typography.titleLarge)
        LifeTracingOutlinedTextField(
            value = draft.name,
            onValueChange = { onAction(StartActivityAction.EditOneOffName(it)) },
            modifier = Modifier.fillMaxWidth().testTag("launcher-one-off-name"),
            enabled = enabled,
            label = { Text(stringResource(R.string.launcher_one_off_name)) },
        )
        LifeTracingOutlinedTextField(
            value = draft.shortComment,
            onValueChange = { onAction(StartActivityAction.EditOneOffComment(it)) },
            modifier = Modifier.fillMaxWidth().testTag("launcher-one-off-comment"),
            enabled = enabled,
            label = { Text(stringResource(R.string.launcher_one_off_comment)) },
        )
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            listOf(
                TimeTrackingMode.STOPWATCH to R.string.launcher_one_off_stopwatch,
                TimeTrackingMode.TIMER to R.string.launcher_one_off_timer,
                TimeTrackingMode.NO_LIVE_TRACKING to R.string.launcher_one_off_no_live,
            ).forEach { (mode, label) ->
                LifeTracingSecondaryButton(
                    onClick = { onAction(StartActivityAction.EditOneOffMode(mode)) },
                    enabled = enabled,
                ) {
                    Text(stringResource(label) + if (draft.mode == mode) " \u2713" else "")
                }
            }
        }
        if (draft.mode == TimeTrackingMode.TIMER) {
            LifeTracingOutlinedTextField(
                value = draft.timerMinutes,
                onValueChange = { onAction(StartActivityAction.EditOneOffTimerMinutes(it)) },
                modifier = Modifier.fillMaxWidth().testTag("launcher-one-off-timer"),
                enabled = enabled,
                label = { Text(stringResource(R.string.launcher_one_off_timer_minutes)) },
            )
        }
        if (!valid) Text(stringResource(R.string.launcher_one_off_invalid), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            LifeTracingSecondaryButton(
                onClick = { onAction(StartActivityAction.CloseOneOff) },
                enabled = enabled,
            ) { Text(stringResource(R.string.launcher_cancel)) }
            LifeTracingPrimaryButton(
                onClick = {
                    onAction(
                        if (command == LauncherCommandState.Idle) {
                            StartActivityAction.ExecuteOneOff
                        } else {
                            StartActivityAction.RetryLaunch
                        },
                    )
                },
                enabled = enabled && valid,
                modifier = Modifier.testTag("launcher-one-off-execute"),
            ) {
                Text(
                    stringResource(
                        if (draft.mode == TimeTrackingMode.NO_LIVE_TRACKING) {
                            R.string.launcher_complete
                        } else {
                            R.string.launcher_one_off_start
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun LauncherHeader(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.launcher_title), style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onBack) { Text(stringResource(R.string.launcher_browse_back)) }
    }
}

@Composable
private fun LauncherLoading() = LauncherCard { Text(stringResource(R.string.launcher_loading)) }

@Composable
private fun LauncherFailure(
    message: Int,
    onAction: (StartActivityAction) -> Unit,
) = LauncherCard(container = MaterialTheme.colorScheme.errorContainer) {
    Text(stringResource(message), style = MaterialTheme.typography.titleMedium)
    LifeTracingPrimaryButton(onClick = { onAction(StartActivityAction.Retry) }) {
        Text(stringResource(R.string.launcher_retry))
    }
}

@Composable
private fun OrganizationFailure(failure: String?) {
    if (failure != null) {
        LauncherCard(container = MaterialTheme.colorScheme.errorContainer) {
            Text(stringResource(R.string.launcher_pinned_failure))
        }
    }
}

@Composable
private fun TargetLoading(
    selected: LauncherLoad<LibraryLaunchTarget>,
    onAction: (StartActivityAction) -> Unit,
) {
    when (selected) {
        LauncherLoad.Loading -> LauncherCard { Text(stringResource(R.string.launcher_target_loading)) }
        is LauncherLoad.Failure ->
            LauncherCard(container = MaterialTheme.colorScheme.errorContainer) {
                Text(stringResource(R.string.launcher_target_failure))
                LifeTracingSecondaryButton(onClick = { onAction(StartActivityAction.Retry) }) {
                    Text(stringResource(R.string.launcher_retry))
                }
            }
        else -> Unit
    }
}

@Composable
private fun CommandPresentation(
    command: LauncherCommandState,
    selectedTargetName: String?,
    onAction: (StartActivityAction) -> Unit,
) {
    when (command) {
        LauncherCommandState.Idle -> Unit
        is LauncherCommandState.Checking -> LauncherCard { Text(stringResource(R.string.launcher_checking)) }
        is LauncherCommandState.Committing -> LauncherCard { Text(stringResource(R.string.launcher_committing)) }
        is LauncherCommandState.Preflight -> PreflightCard(command, selectedTargetName, onAction)
        is LauncherCommandState.Conflict ->
            LauncherCard(container = MaterialTheme.colorScheme.errorContainer) {
                Text(stringResource(R.string.launcher_conflict), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.launcher_conflict_detail))
                LifeTracingSecondaryButton(onClick = { onAction(StartActivityAction.RetryLaunch) }) {
                    Text(stringResource(R.string.launcher_retry))
                }
            }
        is LauncherCommandState.Rejected ->
            LauncherCard(container = MaterialTheme.colorScheme.errorContainer) {
                Text(stringResource(R.string.launcher_rejected), style = MaterialTheme.typography.titleMedium)
                LifeTracingSecondaryButton(onClick = { onAction(StartActivityAction.RetryLaunch) }) {
                    Text(stringResource(R.string.launcher_retry))
                }
            }
        is LauncherCommandState.Committed -> LauncherCard { Text(stringResource(R.string.launcher_committed)) }
        is LauncherCommandState.CommittedCoordinationFailure ->
            LauncherCard {
                Text(stringResource(R.string.launcher_committed_coordination))
            }
    }
}

@Composable
private fun PreflightCard(
    preflight: LauncherCommandState.Preflight,
    targetName: String?,
    onAction: (StartActivityAction) -> Unit,
) {
    var elapsedNow by remember(preflight.attemptId) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val elapsedAtStart = remember(preflight.attemptId) { elapsedNow }
    val wallAtStart = remember(preflight.attemptId) { Instant.now() }
    LaunchedEffect(preflight.attemptId) {
        while (true) {
            delay(250)
            elapsedNow = SystemClock.elapsedRealtime()
        }
    }
    val remaining = preflightCountdownRemaining(preflight, wallAtStart.plusMillis(elapsedNow - elapsedAtStart))
    val progress = 1f - remaining.toMillis().toFloat() / preflight.duration.toMillis().coerceAtLeast(1L)
    LauncherCard(container = MaterialTheme.colorScheme.primaryContainer) {
        targetName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(stringResource(R.string.launcher_countdown, formatLauncherCountdown(remaining)))
        LifeTracingLinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) })
        LifeTracingSecondaryButton(onClick = { onAction(StartActivityAction.CancelPreflight) }) {
            Text(stringResource(R.string.launcher_cancel))
        }
    }
}

internal fun preflightCountdownRemaining(
    preflight: LauncherCommandState.Preflight,
    now: Instant,
): Duration = Duration.between(now, preflight.endsAt).coerceAtLeast(Duration.ZERO)

internal fun formatLauncherCountdown(value: Duration): String {
    val seconds = value.seconds.coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun TrackableSection(
    title: Int,
    empty: Int,
    items: List<LibraryTrackable>,
    enabled: Boolean,
    onSelect: (LibraryTemplateId) -> Unit,
    onOptions: (LibraryTemplateId) -> Unit,
) {
    LauncherCard {
        SectionTitle(title)
        if (items.isEmpty()) Text(stringResource(empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { item ->
            key(
                item.key(),
            ) { TrackableRow(item, enabled, onSelect = { onSelect(item.id) }, onOptions = { onOptions(item.id) }) }
        }
    }
}

@Composable
private fun SearchSection(
    query: String,
    state: LauncherLoad<List<LibraryTrackable>>,
    enabled: Boolean,
    onSearch: (String) -> Unit,
    onSelect: (LibraryTemplateId) -> Unit,
    onOptions: (LibraryTemplateId) -> Unit,
    onRetry: () -> Unit,
) {
    LauncherCard {
        SectionTitle(R.string.launcher_search)
        LifeTracingOutlinedTextField(
            value = query,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.launcher_search_hint)) },
            enabled = enabled,
        )
        when (state) {
            LauncherLoad.Idle -> Unit
            LauncherLoad.Loading -> Text(stringResource(R.string.launcher_search_loading))
            is LauncherLoad.Failure -> LauncherFailure(R.string.launcher_search_failure) { onRetry() }
            is LauncherLoad.Content -> {
                if (state.value.isEmpty()) Text(stringResource(R.string.launcher_search_empty))
                state.value.forEach { item ->
                    key(
                        item.key(),
                    ) {
                        TrackableRow(
                            item,
                            enabled,
                            onSelect = { onSelect(item.id) },
                            onOptions = { onOptions(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseSection(
    state: LauncherLoad<com.alexandr5476.lifetracing.domain.LibraryContents>,
    breadcrumbs: List<LauncherBreadcrumb>,
    enabled: Boolean,
    onBrowse: (com.alexandr5476.lifetracing.domain.FolderId?) -> Unit,
    onFolder: (Folder) -> Unit,
    onSelect: (LibraryTemplateId) -> Unit,
    onOptions: (LibraryTemplateId) -> Unit,
    onRetry: () -> Unit,
) {
    LauncherCard {
        SectionTitle(R.string.launcher_browse)
        if (breadcrumbs.isEmpty()) {
            LifeTracingSecondaryButton(
                onClick = { onBrowse(null) },
                enabled = enabled,
            ) { Text(stringResource(R.string.launcher_browse_open)) }
        } else {
            Text(breadcrumbs.joinToString(" / ") { it.name }, style = MaterialTheme.typography.labelLarge)
        }
        when (state) {
            LauncherLoad.Idle -> Unit
            LauncherLoad.Loading -> Text(stringResource(R.string.launcher_browse_loading))
            is LauncherLoad.Failure -> LauncherFailure(R.string.launcher_browse_failure) { onRetry() }
            is LauncherLoad.Content -> {
                val contents = state.value
                if (contents.folders.isEmpty() && contents.activities.isEmpty() && contents.sequences.isEmpty()) {
                    Text(stringResource(R.string.launcher_browse_empty))
                }
                contents.folders.forEach { folder ->
                    TextButton(onClick = { onFolder(folder) }, enabled = enabled) { Text(folder.name) }
                }
                (contents.activities + contents.sequences).forEach { item ->
                    key(
                        item.key(),
                    ) {
                        TrackableRow(
                            item,
                            enabled,
                            onSelect = { onSelect(item.id) },
                            onOptions = { onOptions(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PinnedSection(
    canonical: List<LibraryTrackable>,
    organizationInFlight: Boolean,
    organizationFailure: String?,
    enabled: Boolean,
    onSelect: (LibraryTemplateId) -> Unit,
    onOptions: (LibraryTemplateId) -> Unit,
    onReorder: (List<LibraryTemplateId>) -> Unit,
) {
    var order by remember { mutableStateOf(canonical) }
    var draggedId by remember { mutableStateOf<LibraryTemplateId?>(null) }
    var dragStartOrder by remember { mutableStateOf<List<LibraryTrackable>?>(null) }
    LaunchedEffect(canonical) { order = canonical }
    LaunchedEffect(organizationFailure) { if (organizationFailure != null) order = canonical }
    LauncherCard {
        SectionTitle(R.string.launcher_pinned)
        if (order.isEmpty()) {
            Text(
                stringResource(R.string.launcher_pinned_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        order.forEachIndexed { index, item ->
            key(item.key()) {
                PinnedRow(
                    item = item,
                    enabled = enabled && !organizationInFlight,
                    canMoveUp = index > 0,
                    canMoveDown = index < order.lastIndex,
                    onSelect = { onSelect(item.id) },
                    onOptions = { onOptions(item.id) },
                    onMove = { delta ->
                        val next = (index + delta).coerceIn(0, order.lastIndex)
                        if (next != index) {
                            order = order.move(index, next)
                            onReorder(order.map(LibraryTrackable::id))
                        }
                    },
                    onDrag = { delta ->
                        if (organizationInFlight) return@PinnedRow
                        val current = order.indexOfFirst { it.id == draggedId }
                        if (current >= 0) {
                            val next = (current + if (delta > 0f) 1 else -1).coerceIn(0, order.lastIndex)
                            if (next != current) order = order.move(current, next)
                        }
                    },
                    onDragStart = {
                        draggedId = item.id
                        dragStartOrder = order
                    },
                    onDragEnd = {
                        if (draggedId != null && order != dragStartOrder) {
                            onReorder(order.map(LibraryTrackable::id))
                        }
                        draggedId = null
                        dragStartOrder = null
                    },
                    onDragCancel = {
                        order = dragStartOrder ?: canonical
                        draggedId = null
                        dragStartOrder = null
                    },
                )
            }
        }
    }
}

private fun List<LibraryTrackable>.move(
    from: Int,
    to: Int,
): List<LibraryTrackable> = toMutableList().also { items -> items.add(to, items.removeAt(from)) }

private fun LibraryTrackable.key(): String = "${kind.name}:${id.value}"

@Composable
private fun PinnedRow(
    item: LibraryTrackable,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onOptions: () -> Unit,
    onMove: (Int) -> Unit,
    onDrag: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val moveUp = stringResource(R.string.launcher_move_up, item.name)
    val moveDown = stringResource(R.string.launcher_move_down, item.name)
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        TrackableRow(item, enabled, onSelect, onOptions, Modifier.weight(1f))
        TextButton(
            onClick = { onMove(-1) },
            modifier = Modifier.semantics { contentDescription = moveUp },
            enabled = enabled && canMoveUp,
        ) { Text("\u2191") }
        TextButton(
            onClick = { onMove(1) },
            modifier = Modifier.semantics { contentDescription = moveDown },
            enabled = enabled && canMoveDown,
        ) { Text("\u2193") }
        val handle = stringResource(R.string.launcher_drag_handle)
        Text(
            "\u2195",
            modifier =
                Modifier.semantics { contentDescription = handle }.pointerInput(item.id, enabled) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { if (enabled) onDragStart() },
                        onDrag = { change, amount ->
                            if (enabled) {
                                change.consume()
                                onDrag(amount.y)
                            }
                        },
                        onDragEnd = { if (enabled) onDragEnd() },
                        onDragCancel = onDragCancel,
                    )
                },
        )
    }
}

@Composable
private fun TrackableRow(
    item: LibraryTrackable,
    enabled: Boolean,
    onSelect: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(enabled = enabled, onClick = onSelect),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    if (item.kind ==
                        LibraryTrackableKind.ACTIVITY
                    ) {
                        R.string.launcher_activity
                    } else {
                        R.string.launcher_sequence
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            item.shortComment?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (item.kind == LibraryTrackableKind.ACTIVITY) {
                TextButton(
                    onClick = onOptions,
                    enabled = enabled,
                    modifier = Modifier.testTag("launcher-options-${item.id.value}"),
                ) {
                    Text(stringResource(R.string.launcher_start_options))
                }
            }
        }
    }
}

@Composable
private fun QuickMainValueCard(
    target: LibraryLaunchTarget.Activity,
    editor: QuickMainValueEditorState,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onComplete: () -> Unit,
) {
    val mainValue = requireNotNull(target.mainValue)
    val parsed = parseLauncherNumber(editor.text, mainValue.displayPrecision)
    val valid = !editor.changed || editor.text.isBlank() || parsed != null
    LauncherCard(container = MaterialTheme.colorScheme.secondaryContainer) {
        Text(target.name, style = MaterialTheme.typography.titleMedium)
        LifeTracingOutlinedTextField(
            value = editor.text,
            onValueChange = onValueChange,
            modifier = Modifier.widthIn(max = 360.dp),
            label = { Text(mainValue.label()) },
        )
        if (!valid) Text(stringResource(R.string.launcher_invalid_number), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            LifeTracingSecondaryButton(onClick = onCancel) { Text(stringResource(R.string.launcher_cancel)) }
            LifeTracingPrimaryButton(
                enabled = valid,
                onClick = onComplete,
            ) { Text(stringResource(R.string.launcher_complete)) }
        }
    }
}

private fun ActivityLaunchMainValue.label(): String = listOfNotNull(name, unit).joinToString(" ")

internal fun quickMainValueOverride(
    mainValue: ActivityLaunchMainValue,
    text: String,
    changed: Boolean,
): QuickMainValueOverride? {
    if (!changed) return null
    val value =
        if (text.isBlank()) {
            QuickMainValue.Missing
        } else {
            QuickMainValue.Number(
                requireNotNull(parseLauncherNumber(text, mainValue.displayPrecision)),
            )
        }
    return QuickMainValueOverride(mainValue.fieldId, value)
}

@Composable
private fun SectionTitle(id: Int) = Text(stringResource(id), style = MaterialTheme.typography.titleMedium)

@Composable
private fun LauncherCard(
    container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) = Card(
    modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun StartOptionsPanel(
    options: LauncherLoad<StartOptionsDraft>,
    command: LauncherCommandState,
    onAction: (StartActivityAction) -> Unit,
) {
    LauncherCard {
        Text(stringResource(R.string.launcher_start_options), style = MaterialTheme.typography.titleLarge)
        when (options) {
            LauncherLoad.Idle -> Unit
            LauncherLoad.Loading -> Text(stringResource(R.string.launcher_target_loading))
            is LauncherLoad.Failure -> {
                Text(
                    stringResource(R.string.manual_history_template_unavailable),
                    color = MaterialTheme.colorScheme.error,
                )
                LifeTracingSecondaryButton(onClick = { onAction(StartActivityAction.CloseOptions) }) {
                    Text(stringResource(R.string.launcher_cancel))
                }
            }
            is LauncherLoad.Content -> StartOptionsForm(options.value, command, onAction)
        }
    }
}

@Composable
private fun StartOptionsForm(
    draft: StartOptionsDraft,
    command: LauncherCommandState,
    onAction: (StartActivityAction) -> Unit,
) {
    val enabled = command == LauncherCommandState.Idle
    Text(draft.template.name, style = MaterialTheme.typography.titleMedium)
    if (draft.template.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
        LifeTracingOutlinedTextField(
            value = draft.startedText,
            onValueChange = { onAction(StartActivityAction.EditOptionStart(it)) },
            modifier = Modifier.fillMaxWidth().testTag("launcher-options-start"),
            enabled = enabled,
            label = { Text(stringResource(R.string.manual_history_started)) },
            supportingText = { Text(stringResource(R.string.manual_history_datetime_hint)) },
        )
        StartOptionsOffsets(draft.startedOffsets, draft.startedOffset, enabled) {
            onAction(StartActivityAction.ChooseOptionStartOffset(it))
        }
    }
    LifeTracingOutlinedTextField(
        value = draft.completedText,
        onValueChange = { onAction(StartActivityAction.EditOptionEnd(it)) },
        modifier = Modifier.fillMaxWidth().testTag("launcher-options-end"),
        enabled = enabled,
        label = {
            Text(
                stringResource(
                    if (draft.template.timeTrackingMode ==
                        TimeTrackingMode.NO_LIVE_TRACKING
                    ) {
                        R.string.manual_history_completed
                    } else {
                        R.string.launcher_optional_end
                    },
                ),
            )
        },
        supportingText = { Text(stringResource(R.string.manual_history_datetime_hint)) },
    )
    StartOptionsOffsets(draft.completedOffsets, draft.completedOffset, enabled) {
        onAction(StartActivityAction.ChooseOptionEndOffset(it))
    }
    draft.template.fields.filter { it.deletedAt == null }.forEach { field ->
        StartOptionsField(field, draft, enabled, onAction)
    }
    draft.issue?.let { issue ->
        Text(stringResource(issue.resource()), color = MaterialTheme.colorScheme.error)
    }
    if (draft.stale) {
        LifeTracingSecondaryButton(
            onClick = { onAction(StartActivityAction.ReviewOptionsTemplate) },
            enabled = enabled,
        ) {
            Text(stringResource(R.string.manual_history_review_current))
        }
    }
    if (draft.overlap != null) {
        Text(stringResource(R.string.manual_history_overlap), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            LifeTracingSecondaryButton(
                onClick = { onAction(StartActivityAction.CancelOptionsOverlap) },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.manual_history_cancel))
            }
            LifeTracingPrimaryButton(
                onClick = { onAction(StartActivityAction.ConfirmOptionsOverlap) },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.manual_history_proceed))
            }
        }
    } else if (!draft.stale) {
        LifeTracingPrimaryButton(
            onClick = { onAction(StartActivityAction.SaveOptions) },
            enabled = enabled,
            modifier = Modifier.testTag("launcher-options-save"),
        ) { Text(stringResource(R.string.manual_history_save)) }
    }
    LifeTracingSecondaryButton(onClick = { onAction(StartActivityAction.CloseOptions) }, enabled = enabled) {
        Text(stringResource(R.string.launcher_cancel))
    }
}

@Composable
private fun StartOptionsOffsets(
    offsets: List<java.time.ZoneOffset>,
    selected: java.time.ZoneOffset?,
    enabled: Boolean,
    choose: (java.time.ZoneOffset) -> Unit,
) {
    offsets.forEachIndexed { index, offset ->
        LifeTracingSecondaryButton(onClick = { choose(offset) }, enabled = enabled) {
            val label =
                if (index ==
                    0
                ) {
                    R.string.manual_history_first_occurrence
                } else {
                    R.string.manual_history_second_occurrence
                }
            Text(stringResource(label, offset.id) + if (selected == offset) " ?" else "")
        }
    }
}

@Composable
private fun StartOptionsField(
    field: ActivityTemplateField,
    state: StartOptionsDraft,
    enabled: Boolean,
    onAction: (StartActivityAction) -> Unit,
) {
    val draft = state.values.getValue(field.id)
    Text(field.name, style = MaterialTheme.typography.titleSmall)
    when (field.type) {
        CustomFieldType.NUMBER ->
            LifeTracingOutlinedTextField(
                value = draft.numberText,
                onValueChange = { onAction(StartActivityAction.EditOptionNumber(field.id, it)) },
                modifier = Modifier.fillMaxWidth().testTag("launcher-options-field-${field.id.value}"),
                enabled = enabled,
                label = { Text(stringResource(R.string.manual_history_actual)) },
            )
        CustomFieldType.TEXT ->
            LifeTracingOutlinedTextField(
                value = draft.text,
                onValueChange = { onAction(StartActivityAction.EditOptionText(field.id, it)) },
                modifier = Modifier.fillMaxWidth().testTag("launcher-options-field-${field.id.value}"),
                enabled = enabled,
                label = { Text(stringResource(R.string.manual_history_actual)) },
            )
        CustomFieldType.CATEGORY ->
            field.categoryOptions.filterNot { it.isArchived }.forEach { option ->
                TextButton(
                    onClick = { onAction(StartActivityAction.ChooseOptionCategory(field.id, option.id)) },
                    enabled = enabled,
                ) {
                    Text(option.label + if (!draft.missing && draft.selectedOptionId == option.id) " ?" else "")
                }
            }
    }
    LifeTracingSecondaryButton(
        onClick = { onAction(StartActivityAction.SetOptionMissing(field.id, !draft.missing)) },
        enabled = enabled,
    ) {
        Text(
            stringResource(
                if (draft.missing) R.string.manual_history_restore_value else R.string.manual_history_set_missing,
            ),
        )
    }
}

@Suppress("CyclomaticComplexMethod")
private fun StartOptionsIssue.resource(): Int =
    when (this) {
        StartOptionsIssue.INVALID_START, StartOptionsIssue.INVALID_END -> R.string.manual_history_invalid_datetime
        StartOptionsIssue.NONEXISTENT_START,
        StartOptionsIssue.NONEXISTENT_END,
        -> R.string.manual_history_nonexistent_time
        StartOptionsIssue.AMBIGUOUS_START, StartOptionsIssue.AMBIGUOUS_END -> R.string.manual_history_ambiguous_time
        StartOptionsIssue.FUTURE_START -> R.string.launcher_options_future_start
        StartOptionsIssue.FUTURE_END -> R.string.manual_history_future_completion
        StartOptionsIssue.REVERSED_INTERVAL -> R.string.manual_history_reversed_interval
        StartOptionsIssue.INVALID_NUMBER -> R.string.manual_history_invalid_number
        StartOptionsIssue.INVALID_CATEGORY -> R.string.manual_history_invalid_category
        StartOptionsIssue.EXPIRED_FINISH -> R.string.launcher_options_expired_finish
        StartOptionsIssue.LIVE_CONFLICT -> R.string.launcher_conflict_detail
        StartOptionsIssue.STALE_TEMPLATE -> R.string.manual_history_template_stale
        StartOptionsIssue.ZONE_CHANGED -> R.string.launcher_options_zone_changed
        StartOptionsIssue.READ_FAILURE -> R.string.manual_history_template_failure
        StartOptionsIssue.SAVE_FAILURE -> R.string.manual_history_save_failure
    }
