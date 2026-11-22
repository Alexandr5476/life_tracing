@file:Suppress("FunctionNaming", "LongParameterList")

package com.alexandr5476.lifetracing.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.LibraryTrackableKind
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ArchivedTemplatesRoute(
    restores: ArchivedRestoreCoordinator,
    isRouteVisible: Boolean,
    ownsRoute: () -> Boolean,
    onBack: () -> Unit,
    onRestored: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { LibraryRepository.create(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val notifyRestored by rememberUpdatedState(onRestored)
    val controller =
        remember(repository, scope, restores) {
            ArchivedTemplatesController(
                { withContext(Dispatchers.IO) { repository.getArchived() } },
                { id -> withContext(Dispatchers.IO) { restoreArchivedTemplate(repository, id) } },
                { notifyRestored() },
                scope,
                restores,
            )
        }
    val state by controller.state.collectAsState()
    val restoreStatus by restores.status.collectAsState()
    ArchivedTemplatesScreen(
        state,
        controller::restore,
        controller::reload,
        controller::retryRestore,
        onBack,
        readCurrent = { controller.state.value },
        isRouteVisible = isRouteVisible,
        ownsRoute = ownsRoute,
        restoreStatus = restoreStatus,
        readRestoreStatus = { restores.status.value },
    )
}

@Composable
internal fun ArchivedTemplatesScreen(
    state: ArchivedTemplatesState,
    onRestore: (LibraryTemplateId) -> Unit,
    onReload: () -> Unit,
    onRetryRestore: () -> Unit,
    onBack: () -> Unit,
    readCurrent: () -> ArchivedTemplatesState,
    isRouteVisible: Boolean = true,
    ownsRoute: () -> Boolean = { true },
    restoreStatus: ArchivedRestoreStatus = ArchivedRestoreStatus(),
    readRestoreStatus: () -> ArchivedRestoreStatus = { restoreStatus },
) {
    val presentation = rememberSettingsPresentation(isRouteVisible, ownsRoute)
    val admission = presentation.rememberAdmission(state, readCurrent)
    val archiveCompletion = state.archiveCompletion
    val restoreEnabled = state.canRestore && archiveCompletion == restoreStatus.completion
    val ownsRestore: (LibraryTemplateId) -> Boolean = { id ->
        admission.isCurrent() && readRestoreStatus().allowsRestore(id, archiveCompletion)
    }
    val rows = state.items.orEmpty()
    val duplicateNames = remember(rows) { rows.groupingBy { it.archiveNameKey }.eachCount() }
    val failed = rows.findArchiveTarget(state.failedRestore, duplicateNames)
    val pending = rows.findArchiveTarget(state.pending ?: restoreStatus.pending.singleOrNull(), duplicateNames)
    val list = rememberLazyListState()
    val focus = rememberArchiveFocus(state, restoreStatus, presentation, admission, list)
    val pageSemantics =
        if (isRouteVisible) {
            Modifier.semantics { isTraversalGroup = true }
        } else {
            Modifier.clearAndSetSemantics { hideFromAccessibility() }
        }
    LifeTracingAccessibleTargets {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().then(pageSemantics).settingsKeyboardBack(presentation, onBack)) {
                ArchivedHeader(focus, presentation, onBack)
                ArchivedList(
                    Modifier.weight(1f),
                    list,
                    rows,
                    duplicateNames,
                    state.pending,
                    restoreStatus,
                    restoreEnabled,
                    focus,
                    ownsRestore,
                    onRestore,
                )
                ArchivedFeedback(
                    state,
                    restoreStatus,
                    pending?.label,
                    failed,
                    isRouteVisible,
                    focus,
                    admission,
                    ownsRestore,
                    restoreEnabled && failed?.id !in restoreStatus.pending,
                    onReload,
                    onRetryRestore,
                )
            }
        }
    }
}

@Composable
private fun ArchivedList(
    modifier: Modifier,
    list: LazyListState,
    rows: List<LibraryTrackable>,
    duplicateNames: Map<Pair<LibraryTrackableKind, String>, Int>,
    pending: LibraryTemplateId?,
    restoreStatus: ArchivedRestoreStatus,
    restoreEnabled: Boolean,
    focus: ArchiveFocus,
    ownsRestore: (LibraryTemplateId) -> Boolean,
    onRestore: (LibraryTemplateId) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("archived-page"),
        state = list,
        contentPadding =
            PaddingValues(
                horizontal = MaterialTheme.spacing.xLarge,
                vertical = MaterialTheme.spacing.small,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
    ) {
        items(rows, key = { it.id.archivedRowKey() }) { row ->
            val restoring = pending == row.id || row.id in restoreStatus.pending
            ArchivedRow(
                row,
                includeIdentity = duplicateNames.getValue(row.archiveNameKey) > 1,
                restoring = restoring,
                enabled = restoreEnabled && !restoring,
                focus = focus,
                ownsRestore = ownsRestore,
                onRestore = onRestore,
            )
        }
    }
}

@Composable
private fun ArchivedHeader(
    focus: ArchiveFocus,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
) {
    Column(
        modifier =
            Modifier.padding(
                start = MaterialTheme.spacing.xLarge,
                end = MaterialTheme.spacing.xLarge,
                top = MaterialTheme.spacing.xLarge,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        val interactions = remember { MutableInteractionSource() }
        Text(
            stringResource(R.string.archived_templates_title),
            modifier =
                focus
                    .target(ARCHIVE_HEADING)
                    .testTag(ARCHIVE_HEADING)
                    .semantics { heading() }
                    .indication(interactions, ripple())
                    .focusable(interactionSource = interactions),
            style = MaterialTheme.typography.headlineSmall,
        )
        SettingsActionButton(
            presentation::isCurrent,
            onBack,
            focus.target(ARCHIVE_BACK).testTag(ARCHIVE_BACK),
        ) {
            Text(stringResource(R.string.archived_templates_back))
        }
    }
}

@Composable
private fun ArchivedFeedback(
    state: ArchivedTemplatesState,
    restores: ArchivedRestoreStatus,
    pendingLabel: String?,
    failed: ArchiveTarget?,
    visible: Boolean,
    focus: ArchiveFocus,
    admission: SettingsActionAdmission,
    ownsRestore: (LibraryTemplateId) -> Boolean,
    retryEnabled: Boolean,
    onReload: () -> Unit,
    onRetryRestore: () -> Unit,
) {
    val message = state.feedback(restores, pendingLabel, failed?.label)
    val status = if (visible) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
    val canonicalFailure = failed != null && state.canRestore && state.archiveCompletion == restores.completion
    val error = state.readFailed || canonicalFailure
    // Status and recovery stay available even when the list is scrolled far from the header.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.xLarge, vertical = MaterialTheme.spacing.medium),
    ) {
        Text(
            message,
            modifier = Modifier.testTag(if (state.loading) "archived-loading" else "archived-status").then(status),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.readFailed) {
            val enabled = !state.loading && state.pending == null
            SettingsActionButton(
                admission::isCurrent,
                onReload,
                focus.target(ARCHIVE_RELOAD, enabled).testTag(ARCHIVE_RELOAD),
                enabled = enabled,
            ) {
                Text(stringResource(R.string.archived_templates_reload))
            }
        } else if (failed != null && retryEnabled) {
            val ownsRetry = { ownsRestore(failed.id) }
            val description = stringResource(R.string.archived_templates_retry_target, failed.label)
            SettingsActionButton(
                ownsRetry,
                onRetryRestore,
                focus
                    .target(ARCHIVE_RETRY, preferredKey = failed.id.archivedRowKey())
                    .testTag(ARCHIVE_RETRY)
                    .semantics { contentDescription = description },
            ) {
                Text(stringResource(R.string.archived_templates_retry))
            }
        }
    }
}

@Composable
private fun ArchivedTemplatesState.feedback(
    restores: ArchivedRestoreStatus,
    pendingLabel: String?,
    failedLabel: String?,
): String =
    when {
        readFailed -> stringResource(R.string.archived_templates_read_failure)
        reconcilingRestore || archiveCompletion != restores.completion ->
            stringResource(R.string.archived_templates_reconciling)
        loading && readGeneration == 0L -> stringResource(R.string.archived_templates_loading)
        loading -> stringResource(R.string.archived_templates_reloading)
        pendingLabel != null -> stringResource(R.string.archived_templates_restoring_target, pendingLabel)
        pending != null || restores.pending.isNotEmpty() -> stringResource(R.string.archived_templates_restoring)
        failedLabel != null -> stringResource(R.string.archived_templates_restore_failure, failedLabel)
        else -> canonicalFeedback()
    }

@Composable
private fun ArchivedTemplatesState.canonicalFeedback(): String {
    val size = items.orEmpty().size
    val count =
        if (size == 0) {
            stringResource(R.string.archived_templates_empty)
        } else {
            pluralStringResource(R.plurals.archived_templates_count, size, size)
        }
    return if (restoreReconciled) stringResource(R.string.archived_templates_restore_confirmed, count) else count
}

private data class ArchiveTarget(
    val id: LibraryTemplateId,
    val label: String,
)

private val LibraryTrackable.archiveNameKey: Pair<LibraryTrackableKind, String>
    get() = kind to name.lowercase()

@Composable
private fun List<LibraryTrackable>.findArchiveTarget(
    id: LibraryTemplateId?,
    duplicateNames: Map<Pair<LibraryTrackableKind, String>, Int>,
): ArchiveTarget? =
    id?.let { firstOrNull { row -> row.id == id } }?.let { row ->
        ArchiveTarget(row.id, row.archiveTarget(duplicateNames.getValue(row.archiveNameKey) > 1))
    }

@Composable
private fun ArchivedRow(
    row: LibraryTrackable,
    includeIdentity: Boolean,
    restoring: Boolean,
    enabled: Boolean,
    focus: ArchiveFocus,
    ownsRestore: (LibraryTemplateId) -> Boolean,
    onRestore: (LibraryTemplateId) -> Unit,
) {
    val id = row.id
    val rowKey = id.archivedRowKey()
    val target = row.archiveTarget(includeIdentity)
    val details =
        row.shortComment?.let { stringResource(R.string.archived_templates_row_with_comment, target, it) }
            ?: stringResource(R.string.archived_templates_row, target)
    val action = stringResource(R.string.archived_templates_restore_target, target)
    val pending = stringResource(R.string.archived_templates_restoring_row)
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("archived-row-$rowKey"),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag("archived-details-$rowKey")
                    .clearAndSetSemantics { contentDescription = details },
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text(
                    stringResource(id.archiveKindResource),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                row.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            SettingsActionButton(
                { ownsRestore(id) },
                onClick = { onRestore(id) },
                enabled = enabled,
                modifier =
                    focus
                        .target(rowKey, enabled)
                        .testTag("archived-restore-$rowKey")
                        .semantics {
                            contentDescription = action
                            if (restoring) stateDescription = pending
                        },
            ) {
                Text(if (restoring) pending else stringResource(R.string.archived_templates_restore))
            }
        }
    }
}

@Composable
private fun LibraryTrackable.archiveTarget(includeIdentity: Boolean): String {
    val kind = stringResource(id.archiveKindResource)
    return if (includeIdentity) {
        stringResource(R.string.archived_templates_target_with_id, kind, name, id.value)
    } else {
        stringResource(R.string.archived_templates_target, kind, name)
    }
}

private val LibraryTemplateId.archiveKindResource: Int
    get() =
        when (this) {
            is LibraryTemplateId.Activity -> R.string.library_activity
            is LibraryTemplateId.Sequence -> R.string.library_sequence
        }
