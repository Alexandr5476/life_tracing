package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.StepActivityDraft

enum class SequenceStepSourceOperation {
    UpdateFromSource,
    UpdateSource,
    SaveAsNew,
}

data class SequenceStepSourceAttempt(
    val stepId: SequenceNodeId,
    val operation: SequenceStepSourceOperation,
)

/** A scalar admission token, never a retained copy of the authoring graph. */
internal class SequenceStepSourceActions(
    private val controller: SequenceTemplateEditorController,
    private val lease: SequenceTemplateEditorActions,
    private val stepId: SequenceNodeId,
) {
    private val epoch = controller.state.value.sourceActionEpoch
    private val sourceId = controller.state.value.stepSourceIds[stepId]
    private val sourceStatus = sourceId?.let(controller.state.value.sourceStatuses::get)
    private val snapshotId = currentStep()?.snapshotId
    private val initiallyEnabled = controller.state.value.sourceActionsAllowed() && snapshotId != null

    private fun currentStep(): StepActivityDraft.Existing? =
        controller.state.value
            .presentation()
            ?.steps
            ?.get(DraftIdentity.Existing(stepId))
            ?.activity
            as? StepActivityDraft.Existing

    fun available(operation: SequenceStepSourceOperation): Boolean {
        val state = controller.state.value
        val step = currentStep() ?: return false
        return initiallyEnabled &&
            lease.isAvailable &&
            controller.presentationEditable() &&
            state.sourceActionsAllowed() &&
            state.sourceActionEpoch == epoch &&
            step.snapshotId == snapshotId &&
            step.sourceTemplateId == sourceId &&
            state.stepSourceIds.containsKey(stepId) &&
            state.stepSourceIds[stepId] == sourceId &&
            sourceId?.let(state.sourceStatuses::get) == sourceStatus &&
            (operation == SequenceStepSourceOperation.SaveAsNew || sourceStatus is SequenceEditorStepSource.Active)
    }

    fun invoke(operation: SequenceStepSourceOperation) =
        lease.invoke {
            if (available(operation)) {
                when (operation) {
                    SequenceStepSourceOperation.UpdateFromSource -> controller.updateStepFromSource(stepId)
                    SequenceStepSourceOperation.UpdateSource -> controller.updateSourceTemplate(stepId)
                    SequenceStepSourceOperation.SaveAsNew -> controller.saveStepAsNewTemplate(stepId)
                }
            }
        }
}
