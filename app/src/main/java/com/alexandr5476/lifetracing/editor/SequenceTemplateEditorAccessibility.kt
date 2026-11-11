@file:Suppress("FunctionNaming", "LongMethod")

package com.alexandr5476.lifetracing.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets

internal val LocalSequenceEditorActions =
    staticCompositionLocalOf<SequenceTemplateEditorActions> { error("Sequence actions missing") }

@Composable
internal fun SequenceEditorHeading(title: String) =
    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)

/** A retained native action keeps its published requested value instead of retoggling mutable node state. */
@Composable
internal fun SequenceMainValueCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) = Checkbox(
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier =
        modifier.semantics {
            if (enabled) {
                onClick {
                    onCheckedChange(!checked)
                    true
                }
            }
        },
    enabled = enabled,
)

@Composable
internal fun SequenceEditorStatus(message: Int) {
    val visible = LocalEditorVisible.current
    Text(stringResource(message), Modifier.semantics { if (visible) liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun SequenceEditorBack(
    controller: SequenceTemplateEditorController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val actions = LocalSequenceEditorActions.current
    TextButton(
        onClick = { actions.invoke { controller.requestBack(onBack) } },
        modifier = editorFocusTarget().then(modifier),
        enabled = enabled,
    ) { Text(stringResource(R.string.sequence_editor_back)) }
}

@Composable
internal fun SequenceEditorDiscard(
    controller: SequenceTemplateEditorController,
    onBack: () -> Unit,
    state: SequenceTemplateEditorState,
) {
    val focus = remember { FocusRequester() }
    val lease = rememberSequenceEditorActions(controller, state, dialog = true)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val locals: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides density,
        ) {
            LifeTracingAccessibleTargets { content() }
        }
    }
    val dialogTitle =
        stringResource(
            R.string.activity_editor_control_context,
            stringResource(R.string.sequence_editor_discard_title),
            stringResource(R.string.sequence_editor_title),
        )
    AlertDialog(
        onDismissRequest = { lease.invoke(controller::dismissDiscard) },
        title = {
            locals {
                Text(
                    stringResource(R.string.sequence_editor_discard_title),
                    Modifier.semantics {
                        heading()
                        contentDescription =
                            dialogTitle
                    },
                )
            }
        },
        text = {
            locals {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                ) { Text(stringResource(R.string.sequence_editor_discard_message)) }
            }
        },
        confirmButton = {
            locals {
                TextButton(
                    onClick = { lease.invoke { controller.discard(onBack) } },
                ) { Text(stringResource(R.string.sequence_editor_discard)) }
            }
        },
        dismissButton = {
            locals {
                val window = LocalWindowInfo.current
                val inputMode = LocalInputModeManager.current.inputMode
                var initialFocusPending by remember { mutableStateOf(true) }
                LaunchedEffect(window.isWindowFocused, inputMode, initialFocusPending, lease.visible) {
                    if (window.isWindowFocused && initialFocusPending) {
                        withFrameNanos { }
                        lease.invoke { initialFocusPending = !focus.requestFocus() }
                    }
                }
                TextButton(
                    onClick = {
                        lease.invoke(controller::dismissDiscard)
                    },
                    modifier =
                        Modifier.focusRequester(
                            focus,
                        ),
                ) { Text(stringResource(R.string.sequence_editor_continue)) }
            }
        },
    )
}

@Composable
@Suppress("LongParameterList") // Controller generation, mode and logical disclosure jointly own a presentation lease.
internal fun rememberSequenceEditorActions(
    controller: SequenceTemplateEditorController,
    state: SequenceTemplateEditorState,
    dialog: Boolean = false,
    normalModeOnly: Boolean = false,
    disclosure: State<Boolean>? = null,
    available: () -> Boolean = { true },
): SequenceTemplateEditorActions {
    val currentAvailable = androidx.compose.runtime.rememberUpdatedState(available)
    val discardGeneration = state.discardGeneration.takeIf { dialog }
    val visible = LocalEditorVisible.current
    val actions =
        remember(
            controller,
            state.generation,
            discardGeneration,
            controller.presentationModeGeneration,
            disclosure?.value,
            visible,
        ) {
            SequenceTemplateEditorActions(controller, state.generation, discardGeneration, normalModeOnly) {
                disclosure?.value != false && currentAvailable.value()
            }
        }
    actions.visible = visible
    DisposableEffect(actions) { onDispose { actions.attached = false } }
    return actions
}
