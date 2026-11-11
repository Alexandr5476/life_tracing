@file:Suppress("TooManyFunctions")

package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode

/** Resolves current physical ownership at an action boundary; never retains a draft graph. */
internal class SequenceStepActions(
    private val controller: SequenceTemplateEditorController,
    private val lease: SequenceTemplateEditorActions,
    private val identity: DraftIdentity<SequenceNodeId>,
) {
    fun available(): Boolean =
        lease.isAvailable &&
            controller.presentationEditable() &&
            controller.state.value.presentation()?.steps?.get(identity)?.let {
                it.activity !is StepActivityDraft.Duplicate
            } == true

    fun scoped(lease: SequenceTemplateEditorActions) = SequenceStepActions(controller, lease, identity)

    fun trackingMode(): TimeTrackingMode? {
        val current =
            controller.state.value
                .presentation()
                ?.steps
                ?.get(identity) ?: return null
        val pending = current.activity as? StepActivityDraft.FromTemplate
        return current.configuration()?.timeTrackingMode ?: pending?.let { source ->
            controller.state.value.availableActivities
                .firstOrNull { it.id == source.templateId }
                ?.timeTrackingMode
        }
    }

    fun exists(): Boolean =
        controller.state.value
            .presentation()
            ?.steps
            ?.containsKey(identity) == true

    fun configuration(): ActivitySnapshotDraft? =
        controller.state.value
            .presentation()
            ?.steps
            ?.get(identity)
            ?.configuration()

    fun fieldExists(captured: ActivitySnapshotFieldDraft): Boolean =
        controller.state.value
            .presentation()
            ?.fieldExists(identity, captured) == true

    fun optionExists(
        captured: ActivitySnapshotFieldDraft,
        option: ActivitySnapshotCategoryOptionDraft,
    ): Boolean =
        controller.state.value
            .presentation()
            ?.optionExists(identity, captured, option) == true

    fun invoke(action: () -> Unit) =
        lease.invoke {
            val current =
                controller.state.value
                    .readyDraft()
                    ?.findStep(identity)
            if (controller.presentationEditable() &&
                current != null &&
                current.activity !is StepActivityDraft.Duplicate
            ) {
                controller.editStepInput(action)
            }
        }

    fun step(transform: (ActivityStepDraft) -> ActivityStepDraft) =
        invoke {
            controller.updateDraft { draft ->
                draft.updateStep(identity) { current ->
                    if (current.activity is StepActivityDraft.Duplicate) current else transform(current)
                }
            }
        }

    fun activity(transform: (ActivitySnapshotDraft) -> ActivitySnapshotDraft) =
        step { current ->
            current.copy(
                activity =
                    when (val activity = current.activity) {
                        is StepActivityDraft.Local -> activity.copy(configuration = transform(activity.configuration))
                        is StepActivityDraft.Existing ->
                            activity.copy(
                                configuration = transform(activity.configuration),
                            )
                        else -> activity
                    },
            )
        }

    fun field(
        captured: ActivitySnapshotFieldDraft,
        transform: (ActivitySnapshotFieldDraft) -> ActivitySnapshotFieldDraft,
    ) = activity { configuration ->
        configuration.copy(fields = configuration.fields.map { if (it.matches(captured)) transform(it) else it })
    }

    fun option(
        captured: ActivitySnapshotFieldDraft,
        option: ActivitySnapshotCategoryOptionDraft,
        transform: (ActivitySnapshotFieldDraft, ActivitySnapshotCategoryOptionDraft) -> ActivitySnapshotFieldDraft,
    ) = field(captured) { current ->
        current.categoryOptions
            .firstOrNull {
                it.identity == option.identity && it.sourceOptionId == option.sourceOptionId
            }?.let { transform(current, it) } ?: current
    }

    fun removeField(captured: ActivitySnapshotFieldDraft) =
        activity { configuration ->
            configuration.copy(
                fields =
                    configuration.fields
                        .filterNot { it.matches(captured) }
                        .mapIndexed { position, field -> field.copy(position = position) },
            )
        }

    fun numberDefaultKey(field: ActivitySnapshotFieldDraft): String = SequenceNumberInputKey.default(field, identity)

    fun numberPrecisionKey(field: ActivitySnapshotFieldDraft): String =
        SequenceNumberInputKey.precision(field, identity)

    fun numberDefault(
        captured: ActivitySnapshotFieldDraft,
        text: String,
    ): Boolean {
        var accepted = false
        invoke { accepted = controller.setStepFieldNumber(identity, captured, text) }
        return accepted
    }

    fun numberPrecision(
        captured: ActivitySnapshotFieldDraft,
        text: String,
    ) = invoke { controller.setStepFieldNumber(identity, captured, text, precision = true) }

    fun mainValue(
        captured: ActivitySnapshotFieldDraft,
        checked: Boolean,
    ) = activity { configuration ->
        if (captured.type != CustomFieldType.NUMBER || configuration.fields.none { it.matches(captured) }) {
            configuration
        } else {
            configuration.copy(
                fields =
                    configuration.fields.map {
                        when {
                            checked -> it.copy(isMainValue = it.identity == captured.identity)
                            it.identity == captured.identity -> it.copy(isMainValue = false)
                            else -> it
                        }
                    },
            )
        }
    }
}

internal fun ActivitySnapshotFieldDraft.matches(captured: ActivitySnapshotFieldDraft): Boolean =
    identity == captured.identity &&
        type == captured.type &&
        sourceFieldId == captured.sourceFieldId &&
        unit == captured.unit

internal fun ActivityStepDraft.configuration(): ActivitySnapshotDraft? =
    when (val activity = activity) {
        is StepActivityDraft.Local -> activity.configuration
        is StepActivityDraft.Existing -> activity.configuration
        else -> null
    }

internal fun SequenceTemplateDraft.findStep(identity: DraftIdentity<SequenceNodeId>): ActivityStepDraft? =
    nodes.firstNotNullOfOrNull { node ->
        when (node) {
            is SequenceNodeDraft.Step -> node.value.takeIf { it.identity == identity }
            is SequenceNodeDraft.Repeat -> node.value.children.firstOrNull { it.identity == identity }
        }
    }

internal fun SequenceTemplateDraft.updateStep(
    identity: DraftIdentity<SequenceNodeId>,
    transform: (ActivityStepDraft) -> ActivityStepDraft,
): SequenceTemplateDraft =
    copy(
        nodes =
            nodes.map { node ->
                when (node) {
                    is SequenceNodeDraft.Step ->
                        if (node.identity ==
                            identity
                        ) {
                            SequenceNodeDraft.Step(transform(node.value))
                        } else {
                            node
                        }
                    is SequenceNodeDraft.Repeat ->
                        SequenceNodeDraft.Repeat(
                            node.value.copy(
                                children =
                                    node.value.children.map {
                                        if (it.identity == identity) transform(it) else it
                                    },
                            ),
                        )
                }
            },
    )

/** A single-use, ephemeral offer. Null parent denotes the existing top-level append command. */
internal class SequenceStepInsertionOffer(
    val parent: DraftIdentity<SequenceNodeId>?,
    val generation: Long,
    val modeGeneration: Long,
)
