@file:Suppress("FunctionNaming", "LongMethod")

package com.alexandr5476.lifetracing.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton

@Composable
internal fun StepSourceActions(
    stepId: SequenceNodeId,
    state: SequenceTemplateEditorState,
    controller: SequenceTemplateEditorController,
) {
    val lease = LocalSequenceEditorActions.current
    val context = LocalSequenceStepContext.current
    val sourceId = state.stepSourceIds[stepId]
    val source = sourceId?.let(state.sourceStatuses::get)
    val step = state.readyDraft()?.findStep(DraftIdentity.Existing(stepId))?.activity as? StepActivityDraft.Existing
    val actions =
        remember(
            controller,
            lease,
            state.sourceActionEpoch,
            state.sourceActionsAllowed(),
            sourceId,
            source,
            step?.snapshotId,
        ) {
            SequenceStepSourceActions(controller, lease, stepId)
        }
    val sourceContext = sourceContext(sourceId == null, source)
    val frozenRevision =
        step
            ?.sourceRevision
            ?.let {
                stringResource(R.string.sequence_source_frozen_revision, it)
            }.orEmpty()
    val status = stringResource(R.string.sequence_source_context, context, sourceContext, frozenRevision)
    val visible = LocalEditorVisible.current
    val focus = LocalEditorFocus.current
    LaunchedEffect(actions) { focus.observeLogicalTargets() }
    Column {
        Text(status)
        if (!state.sourceActionsAllowed()) Text(stringResource(sourceActionDisabledReason(state)))
        state.sourceAttempt?.takeIf { it.stepId == stepId }?.let { attempt ->
            val feedback = sourceFeedback(state.save)
            Text(
                stringResource(
                    R.string.sequence_source_context,
                    stringResource(sourceOperationLabel(attempt.operation)),
                    context,
                    stringResource(feedback),
                ),
                Modifier.semantics { if (visible) liveRegion = LiveRegionMode.Polite },
            )
        }
        // Preserve the established unavailable-source surface: only Save as New is offered.
        SequenceStepSourceOperation.entries
            .filter {
                it == SequenceStepSourceOperation.SaveAsNew || source is SequenceEditorStepSource.Active
            }.forEach { operation ->
                key(actions, operation) {
                    val enabled = actions.available(operation)
                    val availability =
                        stringResource(
                            if (enabled) R.string.sequence_source_available else sourceActionDisabledReason(state),
                        )
                    CompositionLocalProvider(LocalEditorTargetExists provides { actions.available(operation) }) {
                        LifeTracingSecondaryButton(
                            onClick = { if (enabled) actions.invoke(operation) },
                            modifier =
                                editorFocusTarget(
                                    stringResource(
                                        R.string.sequence_source_context,
                                        stringResource(sourceOperationLabel(operation)),
                                        status,
                                        availability,
                                    ),
                                ).fillMaxWidth().semantics { stateDescription = availability },
                            enabled = enabled,
                        ) { Text(stringResource(sourceOperationLabel(operation))) }
                    }
                }
            }
    }
}

@Composable
private fun sourceContext(
    unlinked: Boolean,
    source: SequenceEditorStepSource?,
): String =
    when {
        unlinked -> stringResource(R.string.sequence_source_unlinked)
        source is SequenceEditorStepSource.Active -> stringResource(R.string.sequence_source_active, source.revision)
        source is SequenceEditorStepSource.Archived ->
            stringResource(
                R.string.sequence_source_archived,
                source.revision,
            )
        source is SequenceEditorStepSource.Loading -> stringResource(R.string.sequence_source_loading)
        else -> stringResource(R.string.sequence_source_missing)
    }

private fun sourceFeedback(save: SequenceTemplateEditorSave): Int =
    when (save) {
        SequenceTemplateEditorSave.Saving -> R.string.sequence_editor_saving
        is SequenceTemplateEditorSave.Failure -> sourceFailureMessage(save)
        else -> R.string.sequence_source_completed
    }

/** Existing repository rejection messages identify the failing optimistic owner; never display raw details/IDs. */
internal fun sourceFailureMessage(failure: SequenceTemplateEditorSave.Failure): Int =
    when {
        failure.committedAwaitingReload -> R.string.sequence_editor_reload_failure
        failure.message.contains("ActivityTemplate revision changed concurrently", ignoreCase = true) ->
            R.string.sequence_source_conflict
        failure.message.contains("Archived ActivityTemplate", ignoreCase = true) ->
            R.string.sequence_source_archived_failure
        failure.message.startsWith("Unknown ActivityTemplate:", ignoreCase = true) ||
            failure.message == "Step source is unavailable" -> R.string.sequence_source_missing_failure
        failure.isConflict -> R.string.sequence_editor_conflict
        else -> R.string.sequence_source_failure
    }

private fun sourceOperationLabel(operation: SequenceStepSourceOperation): Int =
    when (operation) {
        SequenceStepSourceOperation.UpdateFromSource -> R.string.sequence_editor_update_from_source
        SequenceStepSourceOperation.UpdateSource -> R.string.sequence_editor_update_source
        SequenceStepSourceOperation.SaveAsNew -> R.string.sequence_editor_save_as_new_template
    }

private fun sourceActionDisabledReason(state: SequenceTemplateEditorState): Int =
    when {
        (state.save as? SequenceTemplateEditorSave.Failure)?.committedAwaitingReload == true ->
            R.string.sequence_editor_reload_failure
        state.save is SequenceTemplateEditorSave.Saving -> R.string.sequence_editor_saving
        state.save is SequenceTemplateEditorSave.Committed -> R.string.activity_editor_committed
        state.discardConfirmationVisible -> R.string.sequence_source_discard_pending
        state.manipulation != null -> R.string.sequence_source_manipulation_pending
        state.readyDraft()?.let(state::hasInvalidInput) == true -> R.string.sequence_source_invalid_input
        else -> R.string.sequence_editor_source_actions_require_apply
    }
