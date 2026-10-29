@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MatchingDeclarationName")

package com.alexandr5476.lifetracing.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.LibraryTrackableKind
import com.alexandr5476.lifetracing.ui.components.LifeTracingOutlinedTextField

internal class LibraryOrganizationScope(
    val state: LibraryPresentationState,
    val readCurrent: () -> LibraryPresentationState,
    val visible: () -> Boolean,
    val location: String,
) {
    val enabled: Boolean get() = admits(requiresOrganization = true)

    fun admits(requiresOrganization: Boolean): Boolean =
        (!requiresOrganization || state.organization is LibraryLoad.Content) &&
            !state.isMutating &&
            if (state.query.isBlank()) state.browse is LibraryLoad.Content else state.search is LibraryLoad.Content
}

internal val LocalLibraryOrganization =
    staticCompositionLocalOf<LibraryOrganizationScope> { error("Library scope missing") }

private class OrganizationLease {
    var attached = true
    var submitted = false
}

/** One attached UI context can submit at most once; canonical controller/writer admission remains authoritative. */
@Composable
internal fun libraryOrganizationCallback(
    enabled: Boolean,
    draftKey: Any? = Unit,
    requiresOrganization: Boolean = true,
    block: () -> Unit,
): () -> Unit {
    val scope = LocalLibraryOrganization.current
    val lease = remember(scope.state, draftKey) { OrganizationLease() }
    DisposableEffect(lease) { onDispose { lease.attached = false } }
    return {
        val current = scope.readCurrent()
        val attached = enabled && lease.attached && !lease.submitted && scope.visible()
        val currentContext = scope.admits(requiresOrganization) && current == scope.state && !current.isMutating
        if (attached && currentContext) {
            lease.submitted = true
            block()
        }
    }
}

@Composable
internal fun OrganizationButton(
    label: String,
    description: String,
    enabled: Boolean,
    selected: Boolean? = null,
    requiresOrganization: Boolean = true,
    onClick: () -> Unit,
) {
    val callback = libraryOrganizationCallback(enabled, requiresOrganization = requiresOrganization, block = onClick)
    TextButton(
        enabled = enabled && LocalLibraryOrganization.current.admits(requiresOrganization),
        onClick = callback,
        modifier =
            libraryFocusTarget().libraryActionSemantics(callback).semantics {
                contentDescription = description
                selected?.let { this.selected = it }
            },
    ) { Text(label) }
}

@Composable
internal fun libraryTemplateContext(
    item: LibraryTrackable,
    section: Int,
    position: Int,
): String =
    stringResource(
        R.string.library_template_context,
        stringResource(
            if (item.kind ==
                LibraryTrackableKind.ACTIVITY
            ) {
                R.string.library_activity
            } else {
                R.string.library_sequence
            },
        ),
        item.name,
        stringResource(section),
        position,
    )

@Composable
internal fun libraryFolderContext(
    folder: Folder,
    position: Int,
): String = stringResource(R.string.library_folder_context, folder.name, position)

@Composable
internal fun FolderNameAction(
    label: Int,
    confirm: Int,
    initialValue: String = "",
    isMutating: Boolean,
    context: String,
    requiresOrganization: Boolean = true,
    onConfirm: (String) -> Unit,
) {
    val scope = LocalLibraryOrganization.current
    val enabled = !isMutating && scope.admits(requiresOrganization)
    val handle = rememberLibraryFocusHandle(enabled)
    val recovery = LocalLibraryFocus.current
    var open by remember(context) { mutableStateOf(false) }
    val close = {
        open = false
        recovery.restoreTarget = handle
    }
    if (open && scope.visible()) {
        DisposableEffect(Unit) {
            onDispose { open = false }
        }
        var value by remember { mutableStateOf(initialValue) }
        val input = remember { FocusRequester() }
        val confirmDescription = stringResource(R.string.library_confirm_context, stringResource(confirm), context)
        val cancelDescription = stringResource(R.string.library_cancel_context, context)
        val inputDescription = stringResource(R.string.library_name_context, context)
        val accept =
            libraryOrganizationCallback(value.isNotBlank() && enabled, value, requiresOrganization) {
                onConfirm(value.trim())
                close()
            }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(context, modifier = Modifier.semantics { heading() }) },
            text = {
                val window = LocalWindowInfo.current
                val inputMode = LocalInputModeManager.current.inputMode
                var initialFocusPending by remember { mutableStateOf(true) }
                LaunchedEffect(window.isWindowFocused, inputMode, initialFocusPending) {
                    if (window.isWindowFocused && initialFocusPending) {
                        withFrameNanos { }
                        if (scope.visible()) initialFocusPending = !input.requestFocus()
                    }
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LifeTracingOutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier =
                            Modifier.fillMaxWidth().focusRequester(input).semantics {
                                contentDescription = inputDescription
                            },
                        label = { Text(stringResource(R.string.library_name)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = value.isNotBlank() && enabled,
                    onClick = accept,
                    modifier =
                        Modifier.libraryActionSemantics(accept).semantics {
                            contentDescription =
                                confirmDescription
                        },
                ) {
                    Text(stringResource(confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = close, modifier = Modifier.semantics { contentDescription = cancelDescription }) {
                    Text(stringResource(R.string.library_cancel))
                }
            },
        )
    }
    val openDialog = libraryPresentationCallback(enabled = enabled, consume = false) { open = true }
    TextButton(
        enabled = enabled,
        onClick = openDialog,
        modifier =
            libraryFocusTarget().libraryActionSemantics(openDialog).focusRequester(handle.requester).semantics {
                contentDescription =
                    context
            },
    ) { Text(stringResource(label)) }
}
