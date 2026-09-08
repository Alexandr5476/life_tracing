@file:Suppress("FunctionNaming", "TooManyFunctions")

package com.alexandr5476.lifetracing.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.LibraryTrackableKind
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing

@Composable
fun LibraryRoute(
    controller: LibraryController,
    onBack: () -> Unit,
    onCreateActivity: () -> Unit = {},
    onOpenActivity: (ActivityTemplateId) -> Unit = {},
) {
    DisposableEffect(controller) { onDispose(controller::close) }
    val state by controller.state.collectAsState()
    LibraryScreen(
        state = state,
        onAction = controller::dispatch,
        onRouteBack = onBack,
        onCreateActivity = onCreateActivity,
        onOpenActivity = onOpenActivity,
    )
}

@Composable
internal fun LibraryScreen(
    state: LibraryPresentationState,
    onAction: (LibraryAction) -> Unit,
    onCreateActivity: () -> Unit = {},
    onOpenActivity: (ActivityTemplateId) -> Unit = {},
    onRouteBack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            LibraryHeader(onBack = {
                if (state.folderId == null) onRouteBack() else onAction(LibraryAction.Back)
            })
            if (state.folderId == null) {
                LifeTracingPrimaryButton(onClick = onCreateActivity, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.library_new_activity))
                }
            }
            val browse = (state.browse as? LibraryLoad.Content)?.value
            if (browse != null) Breadcrumb(browse.path)
            LifeTracingOutlinedTextField(
                value = state.query,
                onValueChange = { onAction(LibraryAction.Search(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.library_search_hint)) },
            )
            FilterRow(state.filter) { onAction(LibraryAction.SetFilter(it)) }
            if (state.query.isBlank()) {
                BrowseContent(state.browse, state.filter, onAction, onOpenActivity)
            } else {
                SearchContent(state.search ?: LibraryLoad.Loading, onAction, onOpenActivity)
            }
        }
    }
}

@Composable
private fun LibraryHeader(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onBack) { Text(stringResource(R.string.library_back)) }
    }
}

@Composable
private fun Breadcrumb(path: List<Folder>) {
    val root = stringResource(R.string.library_root)
    Text(
        text = (listOf(root) + path.map(Folder::name)).joinToString(" / "),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FilterRow(
    selected: LibraryKindFilter,
    onSelect: (LibraryKindFilter) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        LibraryKindFilter.entries.forEach { filter ->
            val label =
                stringResource(
                    when (filter) {
                        LibraryKindFilter.ALL -> R.string.library_filter_all
                        LibraryKindFilter.ACTIVITIES -> R.string.library_filter_activities
                        LibraryKindFilter.SEQUENCES -> R.string.library_filter_sequences
                    },
                )
            if (filter == selected) {
                LifeTracingPrimaryButton(onClick = { onSelect(filter) }) { Text(label) }
            } else {
                LifeTracingSecondaryButton(onClick = { onSelect(filter) }) { Text(label) }
            }
        }
    }
}

@Composable
private fun BrowseContent(
    load: LibraryLoad<LibraryBrowse>,
    filter: LibraryKindFilter,
    onAction: (LibraryAction) -> Unit,
    onOpenActivity: (ActivityTemplateId) -> Unit,
) {
    when (load) {
        LibraryLoad.Loading -> LibraryCard { Text(stringResource(R.string.library_loading)) }
        is LibraryLoad.Failure -> FailureCard(R.string.library_load_failure, onAction)
        is LibraryLoad.Content -> {
            val browse = load.value
            val pinned = browse.pinned.filtered(filter)
            val items = browse.contents.trackables().filtered(filter)
            if (pinned.isNotEmpty()) TrackableSection(R.string.library_pinned, pinned, onOpenActivity)
            if (browse.contents.folders.isNotEmpty()) FolderSection(browse.contents.folders, onAction)
            if (items.isNotEmpty()) TrackableSection(R.string.library_all, items, onOpenActivity)
            if (pinned.isEmpty() && browse.contents.folders.isEmpty() && items.isEmpty()) {
                LibraryCard { Text(stringResource(R.string.library_empty)) }
            }
        }
    }
}

@Composable
private fun SearchContent(
    load: LibraryLoad<List<LibraryTrackable>>,
    onAction: (LibraryAction) -> Unit,
    onOpenActivity: (ActivityTemplateId) -> Unit,
) {
    when (load) {
        LibraryLoad.Loading -> LibraryCard { Text(stringResource(R.string.library_search_loading)) }
        is LibraryLoad.Failure -> FailureCard(R.string.library_search_failure, onAction)
        is LibraryLoad.Content -> {
            if (load.value.isEmpty()) {
                LibraryCard { Text(stringResource(R.string.library_search_empty)) }
            } else {
                TrackableSection(R.string.library_search_results, load.value, onOpenActivity)
            }
        }
    }
}

@Composable
private fun FailureCard(
    message: Int,
    onAction: (LibraryAction) -> Unit,
) = LibraryCard(container = MaterialTheme.colorScheme.errorContainer) {
    Text(stringResource(message), color = MaterialTheme.colorScheme.onErrorContainer)
    LifeTracingPrimaryButton(onClick = { onAction(LibraryAction.Retry) }) {
        Text(stringResource(R.string.library_retry))
    }
}

@Composable
private fun FolderSection(
    folders: List<Folder>,
    onAction: (LibraryAction) -> Unit,
) = LibraryCard {
    SectionTitle(R.string.library_folders)
    folders.forEach { folder ->
        TextButton(onClick = { onAction(LibraryAction.OpenFolder(folder.id)) }) { Text(folder.name) }
    }
}

@Composable
private fun TrackableSection(
    title: Int,
    items: List<LibraryTrackable>,
    onOpenActivity: (ActivityTemplateId) -> Unit,
) = LibraryCard {
    SectionTitle(title)
    items.forEach { TrackableRow(it, onOpenActivity) }
}

@Composable
private fun TrackableRow(
    item: LibraryTrackable,
    onOpenActivity: (ActivityTemplateId) -> Unit,
) {
    val activityId = item.id as? com.alexandr5476.lifetracing.domain.LibraryTemplateId.Activity
    val shape = MaterialTheme.shapes.medium
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (activityId != null) {
                        Modifier.clip(shape).clickable { onOpenActivity(activityId.id) }
                    } else {
                        Modifier
                    },
                ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = shape,
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xSmall),
        ) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    if (item.kind == LibraryTrackableKind.ACTIVITY) {
                        R.string.library_activity
                    } else {
                        R.string.library_sequence
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
private fun SectionTitle(id: Int) = Text(stringResource(id), style = MaterialTheme.typography.titleMedium)

@Composable
private fun LibraryCard(
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

private fun LibraryContents.trackables() = activities + sequences

private fun List<LibraryTrackable>.filtered(filter: LibraryKindFilter) =
    filterNot(LibraryTrackable::isArchived).filter {
        filter == LibraryKindFilter.ALL ||
            (filter == LibraryKindFilter.ACTIVITIES && it.kind == LibraryTrackableKind.ACTIVITY) ||
            (filter == LibraryKindFilter.SEQUENCES && it.kind == LibraryTrackableKind.SEQUENCE)
    }
