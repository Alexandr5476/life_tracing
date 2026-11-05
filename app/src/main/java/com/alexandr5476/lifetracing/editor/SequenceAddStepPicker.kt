@file:Suppress("FunctionNaming", "LongMethod")

package com.alexandr5476.lifetracing.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets

/** The dialog owns native focus; the controller's single-use offer owns insertion and pagination. */
@Composable
internal fun SequenceAddStepPicker(
    offer: SequenceStepInsertionOffer,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    parentContext: String?,
    closed: () -> Unit,
) {
    val lease =
        rememberSequenceEditorActions(controller, state, normalModeOnly = true, available = {
            controller.ownsStepInsertion(offer)
        })
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val focus = remember(offer) { EditorFocus() }
    val locals: @Composable (@Composable () -> Unit) -> Unit = { content ->
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides density,
            LocalEditorFocus provides focus,
            LocalEditorTargetExists provides { controller.ownsStepInsertion(offer) },
        ) { LifeTracingAccessibleTargets { content() } }
    }
    val dismiss = {
        lease.invoke {
            controller.dismissStepInsertion(offer)
            closed()
        }
    }
    DisposableEffect(offer) { onDispose { controller.dismissStepInsertion(offer) } }
    val destination = parentContext ?: stringResource(R.string.sequence_step_top_destination)
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = focus.root,
        title = {
            locals { Text(stringResource(R.string.sequence_editor_pick_activity), Modifier.semantics { heading() }) }
        },
        text = {
            locals {
                Column {
                    Text(destination)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        item {
                            TextButton(onClick = {
                                lease.invoke {
                                    controller.chooseStepInsertion(offer, null)
                                    closed()
                                }
                            }, modifier = editorFocusTarget().fillMaxWidth()) {
                                Text(stringResource(R.string.sequence_editor_add_local_step))
                            }
                        }
                        itemsIndexed(state.availableActivities, key = {
                            _,
                            choice,
                            ->
                            choice.id.value
                        }) { ordinal, choice ->
                            val description =
                                stringResource(
                                    R.string.sequence_step_picker_choice,
                                    ordinal + 1,
                                    choice.name,
                                    choice.compactFact(),
                                    destination,
                                )
                            TextButton(
                                onClick = {
                                    lease.invoke {
                                        controller.chooseStepInsertion(offer, choice.id)
                                        closed()
                                    }
                                },
                                modifier = editorFocusTarget(description).fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(choice.name)
                                    Text(choice.compactFact(), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (state.activityPickerLoading) {
                            item {
                                Text(
                                    stringResource(R.string.sequence_step_picker_loading),
                                    Modifier.semantics {
                                        liveRegion =
                                            LiveRegionMode.Polite
                                    },
                                )
                            }
                        }
                        if (!state.activityPickerLoading &&
                            state.availableActivities.isEmpty() &&
                            !state.activityPickerFailed
                        ) {
                            item {
                                Text(stringResource(R.string.sequence_step_picker_empty))
                            }
                        }
                        if (state.activityPickerFailed) {
                            item {
                                Text(
                                    stringResource(R.string.sequence_step_picker_failure),
                                    Modifier.semantics {
                                        liveRegion =
                                            LiveRegionMode.Polite
                                    },
                                )
                                CompositionLocalProvider(
                                    LocalEditorTargetExists provides {
                                        controller.ownsStepInsertion(offer) &&
                                            controller.state.value.activityPickerFailed
                                    },
                                ) {
                                    TextButton(modifier = editorFocusTarget(), onClick = {
                                        lease.invoke {
                                            if (!controller.state.value.activityPickerFailed) return@invoke
                                            if (state.availableActivities.isEmpty()) {
                                                controller.loadActivityPicker()
                                            } else {
                                                controller.loadMoreStepInsertion(
                                                    offer,
                                                )
                                            }
                                        }
                                    }) { Text(stringResource(R.string.sequence_editor_retry)) }
                                }
                            }
                        }
                        if (state.activityPickerCanLoadMore) {
                            item {
                                val cursor = state.availableActivities.lastOrNull()?.id
                                key(cursor, state.activityPickerLoading) {
                                    CompositionLocalProvider(
                                        LocalEditorTargetExists provides {
                                            controller.ownsStepInsertion(offer) &&
                                                controller.state.value.availableActivities
                                                    .lastOrNull()
                                                    ?.id == cursor &&
                                                controller.state.value.activityPickerLoading ==
                                                state.activityPickerLoading
                                        },
                                    ) {
                                        TextButton(
                                            onClick = {
                                                if (!state.activityPickerLoading) {
                                                    lease.invoke {
                                                        controller.loadMoreStepInsertion(offer, cursor)
                                                    }
                                                }
                                            },
                                            enabled = !state.activityPickerLoading,
                                            modifier = editorFocusTarget().fillMaxWidth(),
                                        ) { Text(stringResource(R.string.sequence_editor_load_more)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { locals { PickerCancel(lease, focus, dismiss) } },
    )
}

@Composable
private fun PickerCancel(
    lease: SequenceTemplateEditorActions,
    focus: EditorFocus,
    dismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    val window = LocalWindowInfo.current
    val input = LocalInputModeManager.current.inputMode
    var initialFocusPending by remember { mutableStateOf(true) }
    LaunchedEffect(window.isWindowFocused, input, initialFocusPending, focus.removed) {
        val ownsKeyboardWindow = window.isWindowFocused && input == InputMode.Keyboard
        val needsLanding = initialFocusPending || focus.removed
        if (ownsKeyboardWindow && needsLanding) {
            withFrameNanos { }
            lease.invoke {
                if (initialFocusPending || !focus.hasFocus) initialFocusPending = !cancelFocus.requestFocus()
                focus.removed = false
            }
        }
    }
    TextButton(onClick = dismiss, modifier = editorFocusTarget().focusRequester(cancelFocus)) {
        Text(stringResource(R.string.sequence_editor_cancel))
    }
}
