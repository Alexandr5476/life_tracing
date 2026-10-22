@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "TooManyFunctions")

package com.alexandr5476.lifetracing.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderTreeValidator
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.LibraryTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.components.LifeTracingChoiceButton
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField
import com.alexandr5476.lifetracing.ui.components.LifeTracingPrimaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing

@Composable
fun LibraryRoute(
    controller: LibraryController,
    onBack: () -> Unit,
    onCreateActivity: () -> Unit = {},
    onCreateSequence: () -> Unit = {},
    onOpenActivity: (ActivityTemplateId) -> Unit = {},
    onOpenSequence: (SequenceTemplateId) -> Unit = {},
    onQuickStart: (LibraryTemplateId) -> Unit = {},
    isRouteVisible: Boolean = true,
) {
    val state by controller.state.collectAsState()
    LibraryScreen(
        state = state,
        onAction = controller::dispatch,
        onRouteBack = onBack,
        onCreateActivity = onCreateActivity,
        onCreateSequence = onCreateSequence,
        onOpenActivity = onOpenActivity,
        onOpenSequence = onOpenSequence,
        onQuickStart = onQuickStart,
        isRouteVisible = isRouteVisible,
    )
}

@Composable
internal fun LibraryScreen(
    state: LibraryPresentationState,
    onAction: (LibraryAction) -> Unit,
    onCreateActivity: () -> Unit = {},
    onCreateSequence: () -> Unit = {},
    onOpenActivity: (ActivityTemplateId) -> Unit = {},
    onOpenSequence: (SequenceTemplateId) -> Unit = {},
    onQuickStart: (LibraryTemplateId) -> Unit = {},
    isRouteVisible: Boolean = true,
    onRouteBack: () -> Unit,
) {
    val focus = rememberLibraryFocusRecovery(isRouteVisible)
    val feedback = rememberLibraryRetryFeedback(state)
    val dispatch: (LibraryAction) -> Unit = {
        if (it == LibraryAction.Retry) feedback.request(state)
        onAction(it)
    }
    LifeTracingAccessibleTargets {
        CompositionLocalProvider(LocalLibraryFocus provides focus, LocalLibraryVisible provides isRouteVisible) {
            LibraryContent(
                state,
                dispatch,
                onCreateActivity,
                onCreateSequence,
                onOpenActivity,
                onOpenSequence,
                onQuickStart,
                feedback.succeeded,
                onRouteBack,
            )
        }
    }
}

@Composable
private fun LibraryContent(
    state: LibraryPresentationState,
    onAction: (LibraryAction) -> Unit,
    onCreateActivity: () -> Unit,
    onCreateSequence: () -> Unit,
    onOpenActivity: (ActivityTemplateId) -> Unit,
    onOpenSequence: (SequenceTemplateId) -> Unit,
    onQuickStart: (LibraryTemplateId) -> Unit,
    retrySucceeded: Boolean,
    onRouteBack: () -> Unit,
) {
    val focus = LocalLibraryFocus.current
    val searchDescription =
        stringResource(
            if (state.query.isBlank()) R.string.library_search_inactive else R.string.library_search_active,
        )
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(focus.root)
                    .semantics { isTraversalGroup = true }
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            LibraryHeader(onBack = {
                if (state.folderId == null) onRouteBack() else onAction(LibraryAction.Back)
            })
            if (state.folderId == null) {
                LifeTracingPrimaryButton(onClick = onCreateActivity, modifier = libraryFocusTarget().fillMaxWidth()) {
                    Text(stringResource(R.string.library_new_activity))
                }
                LifeTracingPrimaryButton(onClick = onCreateSequence, modifier = libraryFocusTarget().fillMaxWidth()) {
                    Text(stringResource(R.string.library_new_sequence))
                }
            }
            val browse = (state.browse as? LibraryLoad.Content)?.value
            Breadcrumb(browse?.path, state.folderId != null)
            LifeTracingOutlinedTextField(
                value = state.query,
                onValueChange = { onAction(LibraryAction.Search(it)) },
                modifier = libraryFocusTarget().fillMaxWidth().semantics { stateDescription = searchDescription },
                label = { Text(stringResource(R.string.library_search_hint)) },
            )
            FilterRow(state.filter) { onAction(LibraryAction.SetFilter(it)) }
            if (state.query.isBlank()) {
                BrowseContent(
                    state.browse,
                    state.filter,
                    state.organization,
                    state.isMutating,
                    onAction,
                    onOpenActivity,
                    onOpenSequence,
                    onQuickStart,
                )
            } else {
                SearchContent(
                    state.search ?: LibraryLoad.Loading,
                    state.organization,
                    state.isMutating,
                    onAction,
                    onOpenActivity,
                    onOpenSequence,
                    onQuickStart,
                )
            }
            when (state.organization) {
                LibraryLoad.Loading -> LibraryCard { Text(stringResource(R.string.library_organization_loading)) }
                is LibraryLoad.Failure -> FailureCard(R.string.library_organization_failure, onAction)
                is LibraryLoad.Content ->
                    Text(
                        stringResource(R.string.library_organization_ready),
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            if (retrySucceeded) {
                val visible = LocalLibraryVisible.current
                Text(
                    stringResource(R.string.library_retry_success),
                    modifier = Modifier.semantics { if (visible) liveRegion = LiveRegionMode.Polite },
                )
            }
            state.mutationFailure?.let { FailureCard(R.string.library_mutation_failure, onAction) }
        }
        state.folderDeletion?.let { FolderDeletionDialog(it, state.isMutating, onAction) }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LibraryHeader(onBack: () -> Unit) {
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            stringResource(R.string.library_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        TextButton(
            onClick = onBack,
            modifier = libraryFocusTarget().focusRequester(LocalLibraryFocus.current.landing),
        ) {
            Text(stringResource(R.string.library_back))
        }
    }
}

@Composable
private fun Breadcrumb(
    path: List<Folder>?,
    nested: Boolean,
) {
    val root = stringResource(R.string.library_root)
    val location =
        if (path == null && nested) {
            stringResource(R.string.library_nested_location)
        } else {
            (listOf(root) + path.orEmpty().map(Folder::name)).joinToString(" / ")
        }
    val description = stringResource(R.string.library_location, location)
    Text(
        text = location,
        modifier = Modifier.semantics { contentDescription = description },
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
            LifeTracingChoiceButton(
                selected = filter == selected,
                modifier = libraryFocusTarget().semantics { this.selected = filter == selected },
                onClick = { onSelect(filter) },
            ) { Text(label) }
        }
    }
}

@Composable
private fun BrowseContent(
    load: LibraryLoad<LibraryBrowse>,
    filter: LibraryKindFilter,
    organization: LibraryLoad<LibraryOrganization>,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
    onOpenActivity: (ActivityTemplateId) -> Unit,
    onOpenSequence: (SequenceTemplateId) -> Unit,
    onQuickStart: (LibraryTemplateId) -> Unit,
) {
    when (load) {
        LibraryLoad.Loading -> LibraryCard { Text(stringResource(R.string.library_loading)) }
        is LibraryLoad.Failure -> FailureCard(R.string.library_load_failure, onAction)
        is LibraryLoad.Content -> {
            val browse = load.value
            val pinned = browse.pinned.filtered(filter)
            val items = browse.contents.trackables().filtered(filter)
            if (browse.pinned.isNotEmpty()) {
                PinnedSection(
                    browse.pinned,
                    filter,
                    organization,
                    isMutating,
                    onAction,
                    onOpenActivity,
                    onOpenSequence,
                    onQuickStart,
                )
            }
            FolderSection(browse.contents.folders, organization, isMutating, onAction)
            if (items.isNotEmpty()) {
                TrackableSection(
                    R.string.library_all,
                    items,
                    organization,
                    isMutating,
                    onAction,
                    onOpenActivity,
                    onOpenSequence,
                    onQuickStart,
                )
            }
            if (pinned.isEmpty() && browse.contents.folders.isEmpty() && items.isEmpty()) {
                LibraryCard { Text(stringResource(R.string.library_empty)) }
            }
        }
    }
}

@Composable
private fun SearchContent(
    load: LibraryLoad<List<LibraryTrackable>>,
    organization: LibraryLoad<LibraryOrganization>,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
    onOpenActivity: (ActivityTemplateId) -> Unit,
    onOpenSequence: (SequenceTemplateId) -> Unit,
    onQuickStart: (LibraryTemplateId) -> Unit,
) {
    when (load) {
        LibraryLoad.Loading -> LibraryCard { Text(stringResource(R.string.library_search_loading)) }
        is LibraryLoad.Failure -> FailureCard(R.string.library_search_failure, onAction)
        is LibraryLoad.Content -> {
            if (load.value.isEmpty()) {
                LibraryCard { Text(stringResource(R.string.library_search_empty)) }
            } else {
                TrackableSection(
                    R.string.library_search_results,
                    load.value,
                    organization,
                    isMutating,
                    onAction,
                    onOpenActivity,
                    onOpenSequence,
                    onQuickStart,
                )
            }
        }
    }
}

@Composable
private fun FailureCard(
    message: Int,
    onAction: (LibraryAction) -> Unit,
) = LibraryCard(container = MaterialTheme.colorScheme.errorContainer) {
    val visible = LocalLibraryVisible.current
    Text(
        stringResource(message),
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.semantics { if (visible) liveRegion = LiveRegionMode.Polite },
    )
    val retryLabel = stringResource(R.string.library_retry_for, stringResource(message))
    LifeTracingPrimaryButton(
        onClick = { onAction(LibraryAction.Retry) },
        modifier = libraryFocusTarget().semantics { contentDescription = retryLabel },
    ) {
        Text(stringResource(R.string.library_retry))
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FolderSection(
    folders: List<Folder>,
    organization: LibraryLoad<LibraryOrganization>,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
) = LibraryCard {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        SectionTitle(R.string.library_folders)
        FolderNameAction(
            label = R.string.library_create_folder,
            confirm = R.string.library_create,
            isMutating = isMutating,
        ) { onAction(LibraryAction.CreateFolder(it)) }
    }
    folders.forEachIndexed { index, folder ->
        key(folder.id) { FolderRow(folder, index + 1, organization, isMutating, onAction) }
    }
}

@Composable
private fun TrackableSection(
    title: Int,
    items: List<LibraryTrackable>,
    organization: LibraryLoad<LibraryOrganization>,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
    onOpenActivity: (ActivityTemplateId) -> Unit,
    onOpenSequence: (SequenceTemplateId) -> Unit,
    onQuickStart: (LibraryTemplateId) -> Unit,
) = LibraryCard {
    SectionTitle(title)
    items.forEachIndexed { index, item ->
        key(item.id) {
            TrackableRow(
                item,
                title,
                index + 1,
                organization,
                isMutating,
                onAction,
                onOpenActivity,
                onOpenSequence,
                onQuickStart,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PinnedSection(
    allPinned: List<LibraryTrackable>,
    filter: LibraryKindFilter,
    organization: LibraryLoad<LibraryOrganization>,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
    onOpenActivity: (ActivityTemplateId) -> Unit,
    onOpenSequence: (SequenceTemplateId) -> Unit,
    onQuickStart: (LibraryTemplateId) -> Unit,
) = LibraryCard {
    var showOrdering by remember { mutableStateOf(false) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        SectionTitle(R.string.library_pinned)
        TextButton(onClick = { showOrdering = !showOrdering }) {
            Text(stringResource(R.string.library_reorder_pinned))
        }
    }
    allPinned.filtered(filter).forEachIndexed { index, item ->
        key(item.id) {
            TrackableRow(
                item,
                R.string.library_pinned,
                index + 1,
                organization,
                isMutating,
                onAction,
                onOpenActivity,
                onOpenSequence,
                onQuickStart,
            )
        }
    }
    if (showOrdering) {
        allPinned.forEachIndexed { index, item ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    if (index > 0) {
                        TextButton(
                            enabled = !isMutating,
                            onClick = {
                                onAction(
                                    LibraryAction.ReorderPinned(
                                        allPinned.move(index, index - 1).map(LibraryTrackable::id),
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.library_move_up)) }
                    }
                    if (index < allPinned.lastIndex) {
                        TextButton(
                            enabled = !isMutating,
                            onClick = {
                                onAction(
                                    LibraryAction.ReorderPinned(
                                        allPinned.move(index, index + 1).map(LibraryTrackable::id),
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.library_move_down)) }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TrackableRow(
    item: LibraryTrackable,
    section: Int,
    position: Int,
    organization: LibraryLoad<LibraryOrganization>,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
    onOpenActivity: (ActivityTemplateId) -> Unit,
    onOpenSequence: (SequenceTemplateId) -> Unit,
    onQuickStart: (LibraryTemplateId) -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val kind =
        stringResource(
            if (item.kind == LibraryTrackableKind.ACTIVITY) {
                R.string.library_activity
            } else {
                R.string.library_sequence
            },
        )
    // Section/position disambiguate discovery labels only; callbacks capture the typed ID.
    val context = stringResource(R.string.library_template_context, kind, item.name, stringResource(section), position)
    val openLabel = stringResource(R.string.library_open_template, context)
    val openDescription =
        item.shortComment?.let {
            stringResource(R.string.library_template_details, openLabel, it)
        } ?: openLabel
    val quickLabel = stringResource(R.string.library_quick_start_template, context)
    val organizeLabel = stringResource(R.string.library_organize_template, context)
    var showOrganization by remember(item.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().semantics { isTraversalGroup = true },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = shape,
    ) {
        Column(
            modifier =
                libraryFocusTarget()
                    .fillMaxWidth()
                    .heightIn(min = LocalMinimumInteractiveComponentSize.current)
                    .clip(shape)
                    .clickable(role = Role.Button, onClickLabel = openLabel) {
                        when (val id = item.id) {
                            is LibraryTemplateId.Activity -> onOpenActivity(id.id)
                            is LibraryTemplateId.Sequence -> onOpenSequence(id.id)
                        }
                    }.semantics { contentDescription = openDescription }
                    .padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xSmall),
        ) {
            Text(item.name, style = MaterialTheme.typography.titleSmall)
            Text(kind, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            item.shortComment?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            LifeTracingPrimaryButton(
                onClick = { onQuickStart(item.id) },
                modifier = libraryFocusTarget().semantics { contentDescription = quickLabel },
            ) {
                Text(stringResource(R.string.library_quick_start))
            }
            TextButton(
                onClick = { showOrganization = !showOrganization },
                modifier = libraryFocusTarget().semantics { contentDescription = organizeLabel },
            ) {
                Text(stringResource(R.string.library_organize))
            }
        }
        if (showOrganization) {
            Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
                OrganizationActions(item, organization, isMutating, onAction)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OrganizationActions(
    item: LibraryTrackable,
    organization: LibraryLoad<LibraryOrganization>,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
) {
    val catalog = (organization as? LibraryLoad.Content)?.value ?: return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        TextButton(
            enabled = !isMutating,
            onClick = { onAction(LibraryAction.SetPinned(item.id, item.pinnedRank == null)) },
        ) { Text(stringResource(if (item.pinnedRank == null) R.string.library_pin else R.string.library_unpin)) }
        TextButton(enabled = !isMutating, onClick = { onAction(LibraryAction.MoveTemplate(item.id, null)) }) {
            Text(stringResource(R.string.library_move_to_root))
        }
    }
    catalog.folders.filter { it.id != item.folderId }.forEach { folder ->
        TextButton(enabled = !isMutating, onClick = { onAction(LibraryAction.MoveTemplate(item.id, folder.id)) }) {
            Text(stringResource(R.string.library_move_to_folder, folder.name))
        }
    }
    FolderNameAction(
        label = R.string.library_create_tag,
        confirm = R.string.library_create,
        isMutating = isMutating,
    ) { onAction(LibraryAction.CreateAndAssignTag(item.id, it)) }
    catalog.tags.forEach { tag ->
        val assigned = tag.id in item.tagIds
        TextButton(
            enabled = !isMutating,
            onClick = {
                onAction(
                    if (assigned) {
                        LibraryAction.UnassignTag(item.id, tag.id)
                    } else {
                        LibraryAction.AssignTag(item.id, tag.id)
                    },
                )
            },
        ) {
            Text(stringResource(if (assigned) R.string.library_remove_tag else R.string.library_add_tag, tag.name))
        }
    }
    TemplateArchiveAction(item, isMutating, onAction)
}

@Composable
private fun TemplateArchiveAction(
    item: LibraryTrackable,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
) {
    var open by remember(item.id) { mutableStateOf(false) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.library_archive_template_title)) },
            text = { Text(stringResource(R.string.library_archive_template_message, item.name)) },
            confirmButton = {
                TextButton(
                    enabled = !isMutating,
                    onClick = {
                        onAction(LibraryAction.ArchiveTemplate(item.id))
                        open = false
                    },
                ) {
                    Text(
                        stringResource(R.string.library_archive),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.library_cancel)) }
            },
        )
    }
    TextButton(enabled = !isMutating, onClick = { open = true }) {
        Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FolderRow(
    folder: Folder,
    position: Int,
    organization: LibraryLoad<LibraryOrganization>,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
) {
    var showOrganization by remember(folder.id) { mutableStateOf(false) }
    val openLabel = stringResource(R.string.library_open_folder, folder.name, position)
    val organizeLabel = stringResource(R.string.library_organize_folder, folder.name, position)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        TextButton(
            onClick = { onAction(LibraryAction.OpenFolder(folder.id)) },
            modifier = libraryFocusTarget().semantics { contentDescription = openLabel },
        ) { Text(folder.name) }
        TextButton(
            onClick = { showOrganization = !showOrganization },
            modifier = libraryFocusTarget().semantics { contentDescription = organizeLabel },
        ) {
            Text(stringResource(R.string.library_organize))
        }
    }
    if (!showOrganization) return
    val folders = (organization as? LibraryLoad.Content)?.value?.folders ?: return
    val forbidden =
        FolderTreeValidator.forbiddenDestinations(
            folder.id,
            folders.associate { it.id to it.parentFolderId },
        )
    FolderNameAction(
        label = R.string.library_rename_folder,
        confirm = R.string.library_rename,
        initialValue = folder.name,
        isMutating = isMutating,
    ) { onAction(LibraryAction.RenameFolder(folder.id, it)) }
    TextButton(enabled = !isMutating, onClick = { onAction(LibraryAction.MoveFolder(folder.id, null)) }) {
        Text(stringResource(R.string.library_move_to_root))
    }
    folders
        .filterNot { it.id in forbidden }
        .forEach { destination ->
            TextButton(
                enabled = !isMutating,
                onClick = { onAction(LibraryAction.MoveFolder(folder.id, destination.id)) },
            ) {
                Text(stringResource(R.string.library_move_to_folder, destination.name))
            }
        }
    TextButton(
        enabled = !isMutating,
        onClick = { onAction(LibraryAction.RequestFolderDeletion(folder)) },
    ) {
        Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error)
    }
}

private enum class FolderDeleteStage { DISPOSITION, MOVE_CONTENTS, DELETE_CONTENTS }

@Composable
private fun FolderDeletionDialog(
    deletion: LibraryFolderDeletion,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
) {
    val options = deletion.options
    var stage by remember(deletion.folder.id, options) { mutableStateOf(FolderDeleteStage.DISPOSITION) }
    val dismiss = { onAction(LibraryAction.DismissFolderDeletion) }
    when (options) {
        LibraryLoad.Loading ->
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(R.string.library_delete_folder_title)) },
                text = { Text(stringResource(R.string.library_delete_folder_inspecting)) },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.library_cancel)) }
                },
            )
        is LibraryLoad.Failure ->
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(R.string.library_delete_folder_title)) },
                text = { Text(stringResource(R.string.library_delete_folder_inspection_failure)) },
                confirmButton = {
                    TextButton(onClick = { onAction(LibraryAction.RequestFolderDeletion(deletion.folder)) }) {
                        Text(stringResource(R.string.library_retry))
                    }
                },
                dismissButton = {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.library_cancel)) }
                },
            )
        is LibraryLoad.Content ->
            if (options.value.isEmpty) {
                EmptyFolderDeletionDialog(deletion.folder, isMutating, dismiss, onAction)
            } else {
                NonEmptyFolderDeletionDialog(deletion.folder, options.value, stage, { stage = it }, dismiss, onAction)
            }
    }
}

@Composable
private fun EmptyFolderDeletionDialog(
    folder: Folder,
    isMutating: Boolean,
    onDismiss: () -> Unit,
    onAction: (LibraryAction) -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.library_delete_folder_title)) },
    text = { Text(stringResource(R.string.library_delete_empty_folder_message, folder.name)) },
    confirmButton = {
        TextButton(enabled = !isMutating, onClick = { onAction(LibraryAction.DeleteEmptyFolder(folder.id)) }) {
            Text(stringResource(R.string.library_delete_folder), color = MaterialTheme.colorScheme.error)
        }
    },
    dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
    },
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod") // The three explicit destructive dialog stages remain adjacent and visible.
private fun NonEmptyFolderDeletionDialog(
    folder: Folder,
    options: LibraryFolderDeletionOptions,
    stage: FolderDeleteStage,
    onStage: (FolderDeleteStage) -> Unit,
    onDismiss: () -> Unit,
    onAction: (LibraryAction) -> Unit,
) {
    when (stage) {
        FolderDeleteStage.DISPOSITION ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.library_delete_non_empty_folder_title)) },
                text = { Text(stringResource(R.string.library_delete_non_empty_folder_message, folder.name)) },
                confirmButton = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                        TextButton(onClick = { onStage(FolderDeleteStage.MOVE_CONTENTS) }) {
                            Text(stringResource(R.string.library_move_contents))
                        }
                        TextButton(onClick = { onStage(FolderDeleteStage.DELETE_CONTENTS) }) {
                            Text(
                                stringResource(R.string.library_delete_contents),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
                },
            )
        FolderDeleteStage.MOVE_CONTENTS ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.library_move_contents_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.library_move_contents_message))
                        TextButton(
                            onClick = {
                                onAction(LibraryAction.DeleteFolderMovingContents(folder.id, null))
                            },
                        ) { Text(stringResource(R.string.library_root)) }
                        options.destinations.forEach { destination ->
                            TextButton(
                                onClick = {
                                    onAction(
                                        LibraryAction.DeleteFolderMovingContents(folder.id, destination.id),
                                    )
                                },
                            ) { Text(destination.name) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
                },
            )
        FolderDeleteStage.DELETE_CONTENTS ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.library_delete_contents_title)) },
                text = { Text(stringResource(R.string.library_delete_contents_message, folder.name)) },
                confirmButton = {
                    TextButton(
                        onClick = { onAction(LibraryAction.DeleteFolderAndArchiveContents(folder.id)) },
                    ) {
                        Text(
                            stringResource(R.string.library_delete_contents),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
                },
            )
    }
}

@Composable
private fun FolderNameAction(
    label: Int,
    confirm: Int,
    initialValue: String = "",
    isMutating: Boolean,
    onConfirm: (String) -> Unit,
) {
    var open by remember(label, initialValue) { mutableStateOf(false) }
    if (open) {
        var value by remember { mutableStateOf(initialValue) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(label)) },
            text = {
                LifeTracingOutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.library_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = value.isNotBlank() && !isMutating,
                    onClick = {
                        onConfirm(value.trim())
                        open = false
                    },
                ) { Text(stringResource(confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.library_cancel)) }
            },
        )
    }
    TextButton(enabled = !isMutating, onClick = { open = true }) { Text(stringResource(label)) }
}

@Composable
private fun SectionTitle(id: Int) =
    Text(
        stringResource(id),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )

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

private fun <T> List<T>.move(
    from: Int,
    to: Int,
): List<T> = toMutableList().also { it.add(to, it.removeAt(from)) }
