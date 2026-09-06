@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityLaunchMainValue
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.LibraryTrackableKind
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
        exitPolicy.requestExit({ controller.state.value.command }, onBack, onCommitted)
    }
    BackHandler(enabled = true) {
        exitRoute()
    }
    LaunchedEffect(state.command) {
        exitPolicy.onCommand(state.command, onCommitted)
    }
    StartActivityScreen(state, controller::dispatch, session.interaction, exitRoute)
}

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
    val select: (LibraryTemplateId) -> Unit = { id ->
        interaction.select(id)
        onAction(StartActivityAction.Select(id))
    }
    LaunchedEffect(selected, interaction.pendingSelectionId) {
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
                    if (interaction.browsePath.isEmpty()) {
                        onRouteBack()
                    } else {
                        onAction(StartActivityAction.Browse(interaction.browseBack()))
                    }
                })
            }
            item { CommandPresentation(state.command, (selected as? LauncherLoad.Content)?.value?.name, onAction) }
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
                        )
                    }
                    item {
                        PinnedSection(
                            canonical = home.value.pinned,
                            organizationInFlight = state.organizationInFlight,
                            organizationFailure = state.organizationFailure,
                            enabled = state.canSelect(quickEditor != null),
                            onSelect = select,
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
                    onRetry = { onAction(StartActivityAction.Retry) },
                )
            }
        }
    }
}

private fun StartActivityState.canSelect(quickEditorOpen: Boolean): Boolean =
    !organizationInFlight &&
        !quickEditorOpen &&
        selected !is LauncherLoad.Loading &&
        command == LauncherCommandState.Idle

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
        LauncherCommandState.Checking -> LauncherCard { Text(stringResource(R.string.launcher_checking)) }
        LauncherCommandState.Committing -> LauncherCard { Text(stringResource(R.string.launcher_committing)) }
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
) {
    LauncherCard {
        SectionTitle(title)
        if (items.isEmpty()) Text(stringResource(empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { item ->
            key(item.key()) { TrackableRow(item, enabled, onSelect = { onSelect(item.id) }) }
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
                    key(item.key()) { TrackableRow(item, enabled, onSelect = { onSelect(item.id) }) }
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
                    key(item.key()) { TrackableRow(item, enabled, onSelect = { onSelect(item.id) }) }
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
    onMove: (Int) -> Unit,
    onDrag: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val moveUp = stringResource(R.string.launcher_move_up, item.name)
    val moveDown = stringResource(R.string.launcher_move_down, item.name)
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        TrackableRow(item, enabled, onSelect, Modifier.weight(1f))
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
