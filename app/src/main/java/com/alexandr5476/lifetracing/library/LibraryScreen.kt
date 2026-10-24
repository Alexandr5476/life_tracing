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
import androidx.compose.runtime.rememberUpdatedState
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
    currentRouteVisible: () -> Boolean = { isRouteVisible },
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
        currentState = { controller.state.value },
        currentRouteVisible = currentRouteVisible,
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
    currentState: (() -> LibraryPresentationState)? = null,
    currentRouteVisible: (() -> Boolean)? = null,
    onRouteBack: () -> Unit,
) {
    val focus = rememberLibraryFocusRecovery(isRouteVisible)
    val feedback = rememberLibraryRetryFeedback(state)
    val latestState = rememberUpdatedState(state)
    val latestVisible = rememberUpdatedState(isRouteVisible)
    val path = (state.browse as? LibraryLoad.Content)?.value?.path.orEmpty()
    val location = (listOf(stringResource(R.string.library_root)) + path.map(Folder::name)).joinToString(" / ")
    val scope =
        LibraryOrganizationScope(
            state,
            currentState ?: { latestState.value },
            currentRouteVisible ?: { latestVisible.value },
            location,
        )
    val dispatch: (LibraryAction) -> Unit = {
        if (it == LibraryAction.Retry) feedback.request(state)
        onAction(it)
    }
    LifeTracingAccessibleTargets {
        CompositionLocalProvider(
            LocalLibraryFocus provides focus,
            LocalLibraryVisible provides isRouteVisible,
            LocalLibraryOrganization provides scope,
        ) {
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
    val mutationSucceeded = rememberLibraryMutationFeedback(state)
    val lifecycleOutcome = libraryLifecycleOutcome(state)
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
            OrganizationReadStatus(state.organization, onAction)
            if (retrySucceeded) {
                val visible = LocalLibraryVisible.current
                Text(
                    stringResource(R.string.library_retry_success),
                    modifier = Modifier.semantics { if (visible) liveRegion = LiveRegionMode.Polite },
                )
            }
            val visible = LocalLibraryVisible.current
            if (state.isMutating || mutationSucceeded) {
                Text(
                    lifecycleOutcome?.takeIf { !state.isMutating } ?: stringResource(
                        if (state.isMutating) {
                            R.string.library_organization_busy
                        } else {
                            R.string.library_organization_updated
                        },
                    ),
                    modifier = Modifier.semantics { if (visible) liveRegion = LiveRegionMode.Polite },
                )
            }
            state.mutationFailure?.let { FailureCard(R.string.library_mutation_failure, onAction) }
        }
        FolderDeletionHost(state.folderDeletion, onAction)
    }
}

@Composable
private fun OrganizationReadStatus(
    load: LibraryLoad<LibraryOrganization>,
    onAction: (LibraryAction) -> Unit,
) {
    when (load) {
        LibraryLoad.Loading -> LibraryCard { Text(stringResource(R.string.library_organization_loading)) }
        is LibraryLoad.Failure -> FailureCard(R.string.library_organization_failure, onAction)
        is LibraryLoad.Content ->
            Text(
                stringResource(R.string.library_organization_ready),
                style = MaterialTheme.typography.bodySmall,
            )
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
            context = stringResource(R.string.library_create_folder_here, LocalLibraryOrganization.current.location),
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
    val handle = rememberLibraryFocusHandle(true)
    val recovery = LocalLibraryFocus.current
    val expanded = stringResource(if (showOrdering) R.string.library_expanded else R.string.library_collapsed)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        SectionTitle(R.string.library_pinned)
        TextButton(
            onClick = {
                showOrdering = !showOrdering
                if (!showOrdering) recovery.restoreTarget = handle
            },
            modifier =
                libraryFocusTarget().focusRequester(handle.requester).semantics {
                    stateDescription = expanded
                    selected = showOrdering
                },
        ) { Text(stringResource(R.string.library_reorder_pinned)) }
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
            key(item.id) {
                val context = libraryTemplateContext(item, R.string.library_pinned, index + 1)
                Column(Modifier.fillMaxWidth()) {
                    Text(context)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        OrganizationButton(
                            stringResource(R.string.library_move_up),
                            stringResource(R.string.library_pinned_move_up, context),
                            !isMutating && index > 0,
                        ) {
                            onAction(
                                LibraryAction.ReorderPinned(allPinned.move(index, index - 1).map(LibraryTrackable::id)),
                            )
                        }
                        OrganizationButton(
                            stringResource(R.string.library_move_down),
                            stringResource(R.string.library_pinned_move_down, context),
                            !isMutating && index < allPinned.lastIndex,
                        ) {
                            onAction(
                                LibraryAction.ReorderPinned(allPinned.move(index, index + 1).map(LibraryTrackable::id)),
                            )
                        }
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
    val organizationFocus = rememberLibraryFocusHandle(true)
    val recovery = LocalLibraryFocus.current
    val expanded = stringResource(if (showOrganization) R.string.library_expanded else R.string.library_collapsed)
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
                onClick = {
                    showOrganization = !showOrganization
                    if (!showOrganization) recovery.restoreTarget = organizationFocus
                },
                modifier =
                    libraryFocusTarget().focusRequester(organizationFocus.requester).semantics {
                        contentDescription = organizeLabel
                        stateDescription = expanded
                    },
            ) {
                Text(stringResource(R.string.library_organize))
            }
        }
        if (showOrganization) {
            Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
                OrganizationActions(item, context, organization, isMutating, onAction)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OrganizationActions(
    item: LibraryTrackable,
    context: String,
    organization: LibraryLoad<LibraryOrganization>,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
) {
    val catalog = (organization as? LibraryLoad.Content)?.value ?: return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        val pinned = item.pinnedRank != null
        OrganizationButton(
            stringResource(if (pinned) R.string.library_unpin else R.string.library_pin),
            stringResource(if (pinned) R.string.library_unpin_target else R.string.library_pin_target, context),
            !isMutating,
            selected = pinned,
        ) { onAction(LibraryAction.SetPinned(item.id, !pinned)) }
        OrganizationButton(
            stringResource(R.string.library_move_to_root),
            stringResource(R.string.library_move_target_root, context),
            !isMutating,
        ) {
            onAction(LibraryAction.MoveTemplate(item.id, null))
        }
    }
    catalog.folders.forEachIndexed { index, folder ->
        key(folder.id) {
            if (folder.id != item.folderId) {
                OrganizationButton(
                    stringResource(R.string.library_move_to_folder, folder.name),
                    stringResource(
                        R.string.library_move_target_folder,
                        context,
                        libraryFolderContext(folder, index + 1),
                    ),
                    !isMutating,
                ) { onAction(LibraryAction.MoveTemplate(item.id, folder.id)) }
            }
        }
    }
    FolderNameAction(
        R.string.library_create_tag,
        R.string.library_create,
        isMutating = isMutating,
        context = stringResource(R.string.library_create_tag_target, context),
    ) {
        onAction(LibraryAction.CreateAndAssignTag(item.id, it))
    }
    catalog.tags.forEachIndexed { index, tag ->
        key(tag.id) {
            val assigned = tag.id in item.tagIds
            val tagContext = stringResource(R.string.library_tag_context, tag.name, index + 1)
            OrganizationButton(
                stringResource(if (assigned) R.string.library_remove_tag else R.string.library_add_tag, tag.name),
                stringResource(
                    if (assigned) R.string.library_unassign_target_tag else R.string.library_assign_target_tag,
                    context,
                    tagContext,
                ),
                !isMutating,
                selected = assigned,
            ) {
                onAction(
                    if (assigned) {
                        LibraryAction.UnassignTag(
                            item.id,
                            tag.id,
                        )
                    } else {
                        LibraryAction.AssignTag(item.id, tag.id)
                    },
                )
            }
        }
    }
    TemplateArchiveAction(item, context, isMutating, onAction)
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
    val context = libraryFolderContext(folder, position)
    val openLabel = stringResource(R.string.library_open_folder, folder.name, position)
    val organizeLabel = stringResource(R.string.library_organize_folder, folder.name, position)
    val handle = rememberLibraryFocusHandle(true)
    val recovery = LocalLibraryFocus.current
    val expanded = stringResource(if (showOrganization) R.string.library_expanded else R.string.library_collapsed)
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
            onClick = {
                showOrganization = !showOrganization
                if (!showOrganization) recovery.restoreTarget = handle
            },
            modifier =
                libraryFocusTarget().focusRequester(handle.requester).semantics {
                    contentDescription = organizeLabel
                    stateDescription = expanded
                },
        ) { Text(stringResource(R.string.library_organize)) }
    }
    if (!showOrganization) return
    val folders = (organization as? LibraryLoad.Content)?.value?.folders ?: return
    val forbidden =
        FolderTreeValidator.forbiddenDestinations(
            folder.id,
            folders.associate { it.id to it.parentFolderId },
        )
    FolderNameAction(
        R.string.library_rename_folder,
        R.string.library_rename,
        initialValue = folder.name,
        isMutating = isMutating,
        context = stringResource(R.string.library_rename_folder_target, context),
    ) {
        onAction(LibraryAction.RenameFolder(folder.id, it))
    }
    OrganizationButton(
        stringResource(R.string.library_move_to_root),
        stringResource(R.string.library_move_target_root, context),
        !isMutating,
    ) {
        onAction(LibraryAction.MoveFolder(folder.id, null))
    }
    folders.forEachIndexed { index, destination ->
        key(destination.id) {
            if (destination.id !in forbidden) {
                OrganizationButton(
                    stringResource(R.string.library_move_to_folder, destination.name),
                    stringResource(
                        R.string.library_move_target_folder,
                        context,
                        libraryFolderContext(
                            destination,
                            index + 1,
                        ),
                    ),
                    !isMutating,
                ) { onAction(LibraryAction.MoveFolder(folder.id, destination.id)) }
            }
        }
    }
    FolderDeleteAction(folder, context, isMutating, onAction)
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
