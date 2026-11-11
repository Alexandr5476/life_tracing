@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList")

package com.alexandr5476.lifetracing.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.ui.components.LifeTracingAccessibleTargets

/** Computed once per draft presentation, with O(1) row/context lookup during traversal. */
@Composable
internal fun sequenceManipulationContexts(
    draft: SequenceTemplateDraft,
    choices: Map<com.alexandr5476.lifetracing.domain.ActivityTemplateId, SequenceEditorActivityChoice>,
): Map<DraftIdentity<SequenceNodeId>, String> {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(draft.nodes, choices, resources, configuration) {
        buildMap {
            fun name(step: ActivityStepDraft): String =
                when (val activity = step.activity) {
                    is StepActivityDraft.Existing -> activity.configuration.name
                    is StepActivityDraft.Local -> activity.configuration.name
                    is StepActivityDraft.Duplicate -> activity.configuration.name
                    is StepActivityDraft.FromTemplate ->
                        choices[activity.templateId]?.name
                            ?: resources.getString(R.string.sequence_editor_unavailable_activity)
                }
            draft.nodes.forEach { node ->
                when (node) {
                    is SequenceNodeDraft.Step ->
                        put(
                            node.identity,
                            resources.getString(
                                R.string.sequence_manipulation_top_step,
                                name(node.value),
                                node.position + 1,
                            ),
                        )
                    is SequenceNodeDraft.Repeat -> {
                        val parent =
                            resources.getString(
                                R.string.sequence_manipulation_repeat,
                                node.value.repeatCount,
                                node.position + 1,
                            )
                        put(node.identity, parent)
                        node.value.children.forEach { child ->
                            put(
                                child.identity,
                                resources.getString(
                                    R.string.sequence_manipulation_child,
                                    name(child),
                                    child.position + 1,
                                    parent,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SequenceNodeManipulationAction(
    identity: DraftIdentity<SequenceNodeId>,
    context: String,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    enabled: Boolean,
) {
    val actions = LocalSequenceEditorActions.current
    val active = state.manipulation != null
    val label =
        stringResource(if (active) R.string.sequence_manipulation_select else R.string.sequence_manipulation_enter)
    val description = stringResource(R.string.activity_editor_control_context, label, context)
    val identityState =
        stringResource(
            if (identity is DraftIdentity.Existing) {
                R.string.sequence_manipulation_existing
            } else {
                R.string.sequence_manipulation_new
            },
        )
    key(actions) {
        TextButton(
            onClick = {
                actions.invoke {
                    if (active) controller.selectManipulation(identity) else controller.enterManipulation(identity)
                }
            },
            enabled = enabled,
            modifier =
                editorFocusTarget(description).semantics {
                    selected = state.manipulation?.selected == identity
                    stateDescription = identityState
                },
        ) { Text(label) }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod") // Native operation, modal authority and focus have separate admission conditions.
internal fun SequenceManipulationControls(
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
    contexts: Map<DraftIdentity<SequenceNodeId>, String>,
    enabled: Boolean,
) {
    val actions = LocalSequenceEditorActions.current
    val focus = LocalEditorFocus.current
    val selected = state.manipulation?.selected ?: return
    val ready = state.load as? SequenceTemplateEditorLoad.Ready ?: return
    val valid = selected in contexts
    val visible = LocalEditorVisible.current
    val window = LocalWindowInfo.current
    val inputMode = LocalInputModeManager.current.inputMode
    var offer by remember { mutableStateOf<SequenceDestinationOffer?>(null) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    var origin by remember { mutableStateOf<EditorFocusHandle?>(null) }
    var restorePending by remember { mutableStateOf(false) }
    val close = {
        offer?.let(controller::dismissManipulationDestinations)
        offer = null
        restorePending = true
    }
    LaunchedEffect(visible, enabled) { if (!visible || !enabled) close() }
    LaunchedEffect(offer, window.isWindowFocused, inputMode, restorePending, visible, enabled) {
        val canRestore = visible && enabled && window.isWindowFocused && inputMode == InputMode.Keyboard
        if (offer == null && restorePending && canRestore) {
            withFrameNanos { }
            if (!focus.hasFocus || focus.owner === origin) {
                val restored = origin?.takeIf { it.attached }?.requester?.requestFocus() == true
                if (!restored) focus.land()
            }
            restorePending = false
        }
    }
    Column {
        SequenceEditorHeading(stringResource(R.string.sequence_manipulation_mode))
        Text(
            stringResource(
                R.string.sequence_manipulation_selected,
                contexts[selected] ?: stringResource(R.string.sequence_manipulation_selection_unavailable),
            ),
            Modifier.semantics { if (visible) liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(
                if (controller.manipulationModified) {
                    R.string.sequence_manipulation_modified
                } else {
                    R.string.sequence_manipulation_unchanged
                },
            ),
        )
        TextButton(
            onClick = {
                actions.invoke {
                    origin = focus.owner
                    offer = controller.offerManipulationDestinations(SequenceDestinationOperation.Move)
                    if (offer == null) feedback = R.string.sequence_manipulation_rejected
                }
            },
            modifier = editorFocusTarget().then(if (enabled) Modifier.focusRequester(focus.landing) else Modifier),
            enabled = enabled && valid,
        ) { Text(stringResource(R.string.sequence_manipulation_move)) }
        TextButton(
            onClick = {
                actions.invoke {
                    origin = focus.owner
                    offer = controller.offerManipulationDestinations(SequenceDestinationOperation.Duplicate)
                    if (offer == null) feedback = R.string.sequence_manipulation_rejected
                }
            },
            modifier = editorFocusTarget(),
            enabled = enabled && valid && ready.draft.canDuplicateManipulation(selected, ready.original),
        ) { Text(stringResource(R.string.sequence_manipulation_duplicate)) }
        feedback?.let { SequenceEditorStatus(it) }
    }
    offer?.takeIf { visible && enabled }?.let { current ->
        key(current) {
            SequenceDestinationDialog(
                current,
                controller,
                state,
                contexts,
                dismiss = close,
                choose = { choice ->
                    actions.invoke {
                        feedback =
                            if (controller.chooseManipulationDestination(current, choice)) {
                                R.string.sequence_manipulation_completed
                            } else {
                                R.string.sequence_manipulation_rejected
                            }
                        close()
                    }
                },
            )
        }
    }
}

@Composable
private fun SequenceDestinationDialog(
    offer: SequenceDestinationOffer,
    controller: SequenceTemplateEditorController,
    state: SequenceTemplateEditorState,
    contexts: Map<DraftIdentity<SequenceNodeId>, String>,
    dismiss: () -> Unit,
    choose: (SequenceDestinationChoice) -> Unit,
) {
    val lease = rememberSequenceEditorActions(controller, state)
    DisposableEffect(offer) {
        onDispose {
            lease.attached = false
            controller.dismissManipulationDestinations(offer)
        }
    }
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
    val first = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val operation =
        stringResource(
            if (offer.operation == SequenceDestinationOperation.Move) {
                R.string.sequence_manipulation_move
            } else {
                R.string.sequence_manipulation_duplicate
            },
        )
    val sourceContext =
        stringResource(R.string.sequence_manipulation_destination_title, operation, contexts[offer.source].orEmpty())
    AlertDialog(
        onDismissRequest = { lease.invoke(dismiss) },
        title = {
            locals {
                Text(
                    stringResource(R.string.sequence_manipulation_destinations_heading),
                    Modifier.semantics {
                        heading()
                        contentDescription = sourceContext
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        text = {
            locals {
                val window = LocalWindowInfo.current
                val inputMode = LocalInputModeManager.current.inputMode
                var pending by remember { mutableStateOf(true) }
                LaunchedEffect(window.isWindowFocused, inputMode, pending) {
                    if (pending && window.isWindowFocused && inputMode == InputMode.Keyboard) {
                        // The full source context can exceed a viewport at accessible font sizes.
                        listState.scrollToItem(1)
                        withFrameNanos { }
                        lease.invoke { pending = !first.requestFocus() }
                    }
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp).testTag("sequence-destinations"),
                    state = listState,
                ) {
                    item(key = "source-context") { SequenceEditorHeading(sourceContext) }
                    items(offer.choices, key = { sequenceDestinationKey(it.destination) }) { choice ->
                        val container =
                            choice.destination.repeat?.let { contexts[it] }.orEmpty().ifEmpty {
                                stringResource(R.string.sequence_manipulation_top_level)
                            }
                        val placement =
                            when {
                                choice.previous != null && choice.next != null ->
                                    stringResource(
                                        R.string.sequence_manipulation_between,
                                        contexts[choice.previous].orEmpty(),
                                        contexts[choice.next].orEmpty(),
                                    )
                                choice.previous != null ->
                                    stringResource(
                                        R.string.sequence_manipulation_after,
                                        contexts[choice.previous].orEmpty(),
                                    )
                                choice.next != null ->
                                    stringResource(
                                        R.string.sequence_manipulation_before,
                                        contexts[choice.next].orEmpty(),
                                    )
                                else -> stringResource(R.string.sequence_manipulation_empty)
                            }
                        val label =
                            stringResource(
                                R.string.sequence_manipulation_destination,
                                container,
                                choice.destination.position + 1,
                                placement,
                            )
                        TextButton(
                            onClick = { lease.invoke { choose(choice) } },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (choice === offer.choices.first()) {
                                            Modifier.focusRequester(first)
                                        } else {
                                            Modifier
                                        },
                                    ).testTag(sequenceDestinationKey(choice.destination))
                                    .semantics { contentDescription = label },
                        ) { Text(label) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            locals {
                TextButton(onClick = {
                    lease.invoke(dismiss)
                }) { Text(stringResource(R.string.sequence_manipulation_cancel_destination)) }
            }
        },
    )
}

internal fun sequenceDestinationKey(destination: SequenceDropDestination) =
    "sequence-destination-${destination.repeat?.editorKey() ?: "top"}-${destination.position}"
