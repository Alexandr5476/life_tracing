@file:Suppress("FunctionNaming", "LongParameterList")

package com.alexandr5476.lifetracing.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ArchivedTemplatesRoute(
    onBack: () -> Unit,
    onRestored: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { LibraryRepository.create(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val controller =
        remember(repository, scope, onRestored) {
            ArchivedTemplatesController(
                { withContext(Dispatchers.IO) { repository.getArchived() } },
                { id -> withContext(Dispatchers.IO) { restoreArchivedTemplate(repository, id) } },
                onRestored,
                scope,
            )
        }
    val state by controller.state.collectAsState()
    ArchivedTemplatesScreen(state, controller::restore, controller::reload, controller::retryRestore, onBack)
}

@Composable
internal fun ArchivedTemplatesScreen(
    state: ArchivedTemplatesState,
    onRestore: (LibraryTemplateId) -> Unit,
    onReload: () -> Unit,
    onRetryRestore: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("archived-page"),
            contentPadding = PaddingValues(MaterialTheme.spacing.xLarge),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large)) {
                    Text(
                        stringResource(R.string.archived_templates_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    LifeTracingSecondaryButton(onClick = onBack, modifier = Modifier.testTag("archived-back")) {
                        Text(stringResource(R.string.archived_templates_back))
                    }
                    ArchivedFeedback(state, onReload, onRetryRestore)
                }
            }
            val rows = state.items
            if (rows != null) {
                if (rows.isEmpty() && !state.loading && !state.readFailed) {
                    item { Text(stringResource(R.string.archived_templates_empty)) }
                }
                items(rows, key = { it.id.archivedRowKey() }) { row ->
                    ArchivedRow(row, state.pending == row.id, state.canRestore, onRestore)
                }
            }
        }
    }
}

@Composable
private fun ArchivedFeedback(
    state: ArchivedTemplatesState,
    onReload: () -> Unit,
    onRetryRestore: () -> Unit,
) {
    if (state.loading) {
        Text(stringResource(R.string.archived_templates_loading), modifier = Modifier.testTag("archived-loading"))
    }
    if (state.readFailed) {
        Text(stringResource(R.string.archived_templates_read_failure))
        LifeTracingSecondaryButton(onClick = onReload, modifier = Modifier.testTag("archived-reload")) {
            Text(stringResource(R.string.archived_templates_reload))
        }
    }
    if (state.pending != null) {
        Text(
            stringResource(R.string.archived_templates_restoring),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.failedRestore != null) {
        Text(
            stringResource(R.string.archived_templates_restore_failure),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
        )
        LifeTracingSecondaryButton(
            onClick = onRetryRestore,
            enabled = state.canRestore,
            modifier = Modifier.testTag("archived-retry"),
        ) {
            Text(stringResource(R.string.archived_templates_retry))
        }
    }
}

@Composable
private fun ArchivedRow(
    row: LibraryTrackable,
    restoring: Boolean,
    enabled: Boolean,
    onRestore: (LibraryTemplateId) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("archived-row-${row.id.archivedRowKey()}"),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(
                stringResource(
                    when (row.id) {
                        is LibraryTemplateId.Activity -> R.string.library_activity
                        is LibraryTemplateId.Sequence -> R.string.library_sequence
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            row.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            LifeTracingSecondaryButton(
                onClick = { onRestore(row.id) },
                enabled = enabled,
                modifier = Modifier.testTag("archived-restore-${row.id.archivedRowKey()}"),
            ) {
                Text(
                    stringResource(
                        if (restoring) {
                            R.string.archived_templates_restoring_row
                        } else {
                            R.string.archived_templates_restore
                        },
                    ),
                )
            }
        }
    }
}
