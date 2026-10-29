@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "TooManyFunctions", "MatchingDeclarationName")

package com.alexandr5476.lifetracing.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets
import com.alexandr5476.lifetracing.ui.theme.spacing

internal class LibraryDeletionOrigin(
    val id: FolderId,
    val context: String,
    val handle: LibraryFocusHandle,
)

internal class LibraryLifecycleOutcome(
    val generation: Long,
    val message: String,
)

@Composable
internal fun libraryLifecycleOutcome(state: LibraryPresentationState): String? {
    val focus = LocalLibraryFocus.current
    LaunchedEffect(state.mutationFailure, focus.lifecycleOutcome) {
        if (state.mutationFailure != null) focus.lifecycleOutcome = null
    }
    return focus.lifecycleOutcome
        ?.takeIf {
            state.mutationFailure == null && state.mutationSuccessGeneration == it.generation + 1
        }?.message
}

private enum class DeletionStage { DISPOSITION, MOVE_CONTENTS, DELETE_CONTENTS }

private class LifecycleSession(
    val browseRequestId: Long? = null,
) {
    var active by mutableStateOf(true)
    var stage by mutableStateOf(DeletionStage.DISPOSITION)
}

private class LifecycleLease {
    var attached = true
    var used = false
}

/** Presentation lease only: Folder confirmation admission still belongs to the controller and writer. */
@Composable
private fun lifecycleCallback(
    owner: Any,
    enabled: Boolean = true,
    allowBusy: Boolean = false,
    owns: () -> Boolean,
    block: () -> Unit,
): () -> Unit {
    val scope = LocalLibraryOrganization.current
    val lease = remember(scope.state, owner) { LifecycleLease() }
    DisposableEffect(lease) { onDispose { lease.attached = false } }
    return {
        val current = scope.readCurrent()
        val attached = enabled && lease.attached && !lease.used && scope.visible()
        val currentContext = current === scope.state && (allowBusy || !current.isMutating)
        if (attached && currentContext && owns()) {
            lease.used = true
            block()
        }
    }
}

@Composable
internal fun TemplateArchiveAction(
    item: LibraryTrackable,
    context: String,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
) {
    val scope = LocalLibraryOrganization.current
    val focus = LocalLibraryFocus.current
    val enabled = !isMutating && scope.enabled
    val handle = rememberLibraryFocusHandle(enabled)
    var session by remember(item.id) { mutableStateOf<LifecycleSession?>(null) }
    val archive = stringResource(R.string.library_archive_target, context)
    val outcome = stringResource(R.string.library_archive_outcome, context)
    val currentSession = session
    val currentBrowse = currentSession?.browseRequestId == scope.state.browseRequestId
    if (currentSession?.active == true && currentBrowse && scope.visible()) {
        DisposableEffect(currentSession) {
            onDispose {
                currentSession.active = false
                focus.restoreTarget = handle
            }
        }
        val close = {
            currentSession.active = false
            focus.restoreTarget = handle
        }
        val cancel =
            lifecycleCallback(currentSession, allowBusy = true, owns = { currentSession.active }, block = close)
        val accept =
            lifecycleCallback(currentSession, enabled, owns = { currentSession.active }) {
                focus.lifecycleOutcome = LibraryLifecycleOutcome(scope.state.mutationSuccessGeneration, outcome)
                close()
                onAction(LibraryAction.ArchiveTemplate(item.id))
            }
        LifecycleDialog(
            R.string.library_archive_template_title,
            archive,
            stringResource(R.string.library_archive_template_message, item.name),
            currentSession,
            cancel,
            confirm = {
                LifecycleButton(
                    R.string.library_archive,
                    stringResource(R.string.library_archive_confirm, context),
                    enabled,
                    accept,
                    destructive = true,
                )
            },
        )
    }
    val openDialog = {
        val current = scope.readCurrent()
        val attached = handle.attached && enabled
        if (attached &&
            scope.visible() &&
            current === scope.state
        ) {
            session = LifecycleSession(scope.state.browseRequestId)
        }
    }
    TextButton(
        enabled = enabled,
        onClick = openDialog,
        modifier =
            libraryFocusTarget().libraryActionSemantics(openDialog).focusRequester(handle.requester).semantics {
                contentDescription =
                    archive
            },
    ) { Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun FolderDeleteAction(
    folder: Folder,
    context: String,
    isMutating: Boolean,
    onAction: (LibraryAction) -> Unit,
) {
    val scope = LocalLibraryOrganization.current
    val focus = LocalLibraryFocus.current
    val enabled = !isMutating && scope.enabled
    val handle = rememberLibraryFocusHandle(enabled)
    val request =
        libraryOrganizationCallback(enabled) {
            focus.deletionOrigin = LibraryDeletionOrigin(folder.id, context, handle)
            onAction(LibraryAction.RequestFolderDeletion(folder))
        }
    val description = stringResource(R.string.library_delete_folder_target, context)
    TextButton(
        enabled = enabled,
        onClick = request,
        modifier =
            libraryFocusTarget().libraryActionSemantics(request).focusRequester(handle.requester).semantics {
                contentDescription = description
            },
    ) { Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun FolderDeletionHost(
    deletion: LibraryFolderDeletion?,
    onAction: (LibraryAction) -> Unit,
) {
    val focus = LocalLibraryFocus.current
    val scope = LocalLibraryOrganization.current
    val visible = LocalLibraryVisible.current
    val origin = focus.deletionOrigin
    LaunchedEffect(deletion?.requestId, visible, origin?.handle?.attached) {
        val current = scope.readCurrent().folderDeletion
        val ownsOrigin = origin != null && focus.deletionOrigin === origin
        if (deletion == null && current == null && ownsOrigin) {
            focus.restoreTarget = origin?.handle
            focus.deletionOrigin = null
        } else if (deletion != null && current === deletion) {
            if (!scope.visible() || origin?.handle?.attached == false) onAction(LibraryAction.DismissFolderDeletion)
        }
    }
    if (deletion != null && visible && origin?.handle?.attached != false) {
        val context =
            origin?.takeIf { it.id == deletion.folder.id }?.context
                ?: libraryFolderContext(deletion.folder, 1)
        key(deletion.requestId, deletion.options) {
            FolderDeletionDialog(deletion, context, onAction)
        }
    }
}

@Composable
private fun FolderDeletionDialog(
    deletion: LibraryFolderDeletion,
    context: String,
    onAction: (LibraryAction) -> Unit,
) {
    val session = remember { LifecycleSession() }
    val scope = LocalLibraryOrganization.current
    DisposableEffect(session) { onDispose { session.active = false } }
    if (!session.active) return
    val stage = session.stage
    val owns = { session.active && session.stage == stage && scope.readCurrent().folderDeletion === deletion }
    val cancel =
        lifecycleCallback(stage, allowBusy = true, owns = owns) {
            session.active = false
            onAction(LibraryAction.DismissFolderDeletion)
        }
    val focus = LocalLibraryFocus.current
    val outcome = stringResource(R.string.library_folder_deleted_outcome, context)
    val commit: (LibraryAction) -> Unit = {
        focus.lifecycleOutcome = LibraryLifecycleOutcome(scope.state.mutationSuccessGeneration, outcome)
        onAction(it)
    }
    val options = deletion.options
    when (options) {
        LibraryLoad.Loading ->
            LifecycleDialog(
                R.string.library_delete_folder_title,
                stringResource(R.string.library_delete_folder_inspection, context),
                stringResource(R.string.library_delete_folder_inspecting),
                options,
                cancel,
            )
        is LibraryLoad.Failure -> {
            val retry =
                lifecycleCallback(stage, owns = owns) {
                    session.active = false
                    onAction(LibraryAction.RequestFolderDeletion(deletion.folder))
                }
            LifecycleDialog(
                R.string.library_delete_folder_title,
                stringResource(R.string.library_delete_folder_inspection, context),
                stringResource(R.string.library_delete_folder_inspection_failure),
                options,
                cancel,
                failure = stringResource(R.string.library_inspection_error, options.message),
                confirm = {
                    LifecycleButton(
                        R.string.library_retry,
                        stringResource(R.string.library_retry_inspection, context),
                        !scope.state.isMutating,
                        retry,
                    )
                },
            )
        }
        is LibraryLoad.Content -> {
            if (options.value.isEmpty) {
                val accept =
                    lifecycleCallback(stage, owns = owns) {
                        session.active = false
                        commit(LibraryAction.DeleteEmptyFolder(deletion.folder.id))
                    }
                LifecycleDialog(
                    R.string.library_delete_folder_title,
                    stringResource(R.string.library_delete_empty_confirm, context),
                    stringResource(R.string.library_delete_empty_folder_message, deletion.folder.name),
                    options,
                    cancel,
                    confirm = {
                        LifecycleButton(
                            R.string.library_delete_folder,
                            stringResource(R.string.library_delete_empty_confirm, context),
                            !scope.state.isMutating,
                            accept,
                            destructive = true,
                        )
                    },
                )
            } else {
                FolderDispositionDialog(deletion, context, options.value, session, owns, cancel, commit)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FolderDispositionDialog(
    deletion: LibraryFolderDeletion,
    context: String,
    options: LibraryFolderDeletionOptions,
    session: LifecycleSession,
    owns: () -> Boolean,
    cancel: () -> Unit,
    onAction: (LibraryAction) -> Unit,
) {
    val enabled = !LocalLibraryOrganization.current.state.isMutating
    when (session.stage) {
        DeletionStage.DISPOSITION -> {
            val move =
                lifecycleCallback(DeletionStage.DISPOSITION, owns = owns) {
                    session.stage =
                        DeletionStage.MOVE_CONTENTS
                }
            val delete =
                lifecycleCallback(DeletionStage.DISPOSITION, owns = owns) {
                    session.stage =
                        DeletionStage.DELETE_CONTENTS
                }
            LifecycleDialog(
                R.string.library_delete_non_empty_folder_title,
                stringResource(R.string.library_delete_disposition, context),
                stringResource(R.string.library_delete_non_empty_folder_message, deletion.folder.name),
                session.stage,
                cancel,
                body = {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        LifecycleButton(
                            R.string.library_move_contents,
                            stringResource(R.string.library_move_contents_target, context),
                            enabled,
                            move,
                        )
                        LifecycleButton(
                            R.string.library_delete_contents,
                            stringResource(R.string.library_delete_contents_target, context),
                            enabled,
                            delete,
                            destructive = true,
                        )
                    }
                },
            )
        }
        DeletionStage.MOVE_CONTENTS ->
            LifecycleDialog(
                R.string.library_move_contents_title,
                stringResource(R.string.library_move_contents_target, context),
                stringResource(R.string.library_move_contents_message),
                session.stage,
                cancel,
                body = {
                    val root =
                        lifecycleCallback(DeletionStage.MOVE_CONTENTS, owns = owns) {
                            session.active = false
                            onAction(LibraryAction.DeleteFolderMovingContents(deletion.folder.id, null))
                        }
                    LifecycleButton(
                        R.string.library_root,
                        stringResource(R.string.library_move_contents_root, context),
                        enabled,
                        root,
                    )
                    options.destinations.forEachIndexed { index, destination ->
                        key(destination.id) {
                            val move =
                                lifecycleCallback(destination.id, owns = owns) {
                                    session.active = false
                                    onAction(
                                        LibraryAction.DeleteFolderMovingContents(deletion.folder.id, destination.id),
                                    )
                                }
                            LifecycleButton(
                                destination.name,
                                stringResource(
                                    R.string.library_move_contents_destination,
                                    context,
                                    libraryFolderContext(
                                        destination,
                                        index + 1,
                                    ),
                                ),
                                enabled,
                                move,
                            )
                        }
                    }
                },
            )
        DeletionStage.DELETE_CONTENTS -> {
            val accept =
                lifecycleCallback(DeletionStage.DELETE_CONTENTS, owns = owns) {
                    session.active = false
                    onAction(LibraryAction.DeleteFolderAndArchiveContents(deletion.folder.id))
                }
            LifecycleDialog(
                R.string.library_delete_contents_title,
                stringResource(R.string.library_delete_contents_confirm, context),
                stringResource(R.string.library_delete_contents_message, deletion.folder.name),
                session.stage,
                cancel,
                confirm = {
                    LifecycleButton(
                        R.string.library_delete_contents,
                        stringResource(R.string.library_delete_contents_confirm, context),
                        enabled,
                        accept,
                        destructive = true,
                    )
                },
            )
        }
    }
}

@Composable
private fun LifecycleDialog(
    title: Int,
    context: String,
    message: String,
    phase: Any,
    cancel: () -> Unit,
    failure: String? = null,
    confirm: @Composable () -> Unit = {},
    body: @Composable () -> Unit = {},
) {
    val scope = LocalLibraryOrganization.current
    val cancelFocus = remember { FocusRequester() }
    val cancelDescription = stringResource(R.string.library_cancel_context, context)
    val headingDescription = stringResource(R.string.library_dialog_context, context)
    // Android Dialog owns a separate Compose view; retain this Library's resource and appearance locals.
    val libraryContext = LocalContext.current
    val libraryConfiguration = LocalConfiguration.current
    val libraryDensity = LocalDensity.current
    val withLibraryLocals: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides libraryContext,
            LocalConfiguration provides libraryConfiguration,
            LocalDensity provides libraryDensity,
        ) { LifeTracingAccessibleTargets { content() } }
    }
    AlertDialog(
        onDismissRequest = cancel,
        title = {
            withLibraryLocals {
                Text(
                    stringResource(title),
                    modifier =
                        Modifier.semantics {
                            heading()
                            contentDescription = headingDescription
                        },
                )
            }
        },
        text = {
            withLibraryLocals {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(message)
                    failure?.let {
                        Text(
                            it,
                            modifier = Modifier.semantics { if (scope.visible()) liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    body()
                }
            }
        },
        confirmButton = { withLibraryLocals { confirm() } },
        dismissButton = {
            withLibraryLocals {
                val window = LocalWindowInfo.current
                val inputMode = LocalInputModeManager.current.inputMode
                var initialFocusPending by remember(phase) { mutableStateOf(true) }
                LaunchedEffect(phase, window.isWindowFocused, inputMode, initialFocusPending) {
                    if (window.isWindowFocused && initialFocusPending) {
                        withFrameNanos { }
                        if (scope.visible()) initialFocusPending = !cancelFocus.requestFocus()
                    }
                }
                TextButton(
                    onClick = cancel,
                    modifier =
                        Modifier
                            .libraryActionSemantics(cancel)
                            .focusRequester(
                                cancelFocus,
                            ).semantics { contentDescription = cancelDescription },
                ) { Text(stringResource(R.string.library_cancel)) }
            }
        },
    )
}

@Composable
private fun LifecycleButton(
    label: Int,
    description: String,
    enabled: Boolean,
    action: () -> Unit,
    destructive: Boolean = false,
) = LifecycleButton(stringResource(label), description, enabled, action, destructive)

@Composable
private fun LifecycleButton(
    label: String,
    description: String,
    enabled: Boolean,
    action: () -> Unit,
    destructive: Boolean = false,
) {
    TextButton(
        enabled = enabled,
        onClick = action,
        colors =
            ButtonDefaults.textButtonColors(
                contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
        modifier = Modifier.libraryActionSemantics(action).semantics { contentDescription = description },
    ) {
        Text(label)
    }
}
