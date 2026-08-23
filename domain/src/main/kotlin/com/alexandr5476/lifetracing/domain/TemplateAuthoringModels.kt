package com.alexandr5476.lifetracing.domain

import java.time.Duration

sealed interface DraftIdentity<out T> {
    data class Existing<T>(
        val id: T,
    ) : DraftIdentity<T>

    data class New(
        val key: String,
    ) : DraftIdentity<Nothing> {
        init {
            require(key.isNotBlank()) { "New draft identity key must not be blank" }
        }
    }
}

data class TemplateLibraryPlacement(
    val folderId: FolderId? = null,
    val tagIds: Set<TagId> = emptySet(),
)

data class ActivityCategoryOptionDraft(
    val identity: DraftIdentity<CategoryOptionId>,
    val position: Int,
    val label: String,
)

data class ActivityFieldDraft(
    val identity: DraftIdentity<ActivityTemplateFieldId>,
    val position: Int,
    val name: String,
    val type: CustomFieldType,
    val unit: String? = null,
    val displayPrecision: Int? = null,
    val defaultNumberScaled: Long? = null,
    val defaultCategoryOption: DraftIdentity<CategoryOptionId>? = null,
    val defaultText: String? = null,
    val isMainValue: Boolean = false,
    val categoryOptions: List<ActivityCategoryOptionDraft> = emptyList(),
)

data class ActivityTemplateDraft(
    val name: String,
    val shortComment: String?,
    val timeTrackingMode: TimeTrackingMode,
    val timerTarget: Duration?,
    val settings: ActivityTemplateSettings = ActivityTemplateSettings(),
    val fields: List<ActivityFieldDraft> = emptyList(),
)

data class SequenceCategoryOptionDraft(
    val identity: DraftIdentity<SequenceTemplateCategoryOptionId>,
    val position: Int,
    val label: String,
)

data class SequenceFieldDraft(
    val identity: DraftIdentity<SequenceTemplateFieldId>,
    val position: Int,
    val name: String,
    val type: CustomFieldType,
    val unit: String? = null,
    val displayPrecision: Int? = null,
    val defaultNumberScaled: Long? = null,
    val defaultCategoryOption: DraftIdentity<SequenceTemplateCategoryOptionId>? = null,
    val defaultText: String? = null,
    val isMainValue: Boolean = false,
    val categoryOptions: List<SequenceCategoryOptionDraft> = emptyList(),
)

data class ActivitySnapshotCategoryOptionDraft(
    val identity: DraftIdentity<ActivitySnapshotCategoryOptionId>,
    val sourceOptionId: CategoryOptionId?,
    val position: Int,
    val labelAtCreation: String,
    val localLabelOverride: String? = null,
)

data class ActivitySnapshotFieldDraft(
    val identity: DraftIdentity<ActivitySnapshotFieldId>,
    val sourceFieldId: ActivityTemplateFieldId?,
    val position: Int,
    val nameAtCreation: String,
    val localNameOverride: String? = null,
    val type: CustomFieldType,
    val unit: String? = null,
    val displayPrecision: Int? = null,
    val defaultNumberScaled: Long? = null,
    val defaultCategoryOption: DraftIdentity<ActivitySnapshotCategoryOptionId>? = null,
    val defaultText: String? = null,
    val isMainValue: Boolean = false,
    val categoryOptions: List<ActivitySnapshotCategoryOptionDraft> = emptyList(),
)

data class ActivitySnapshotDraft(
    val name: String,
    val shortComment: String?,
    val timeTrackingMode: TimeTrackingMode,
    val timerTarget: Duration?,
    val settings: ActivityTemplateSettings = ActivityTemplateSettings(),
    val fields: List<ActivitySnapshotFieldDraft> = emptyList(),
)

sealed interface StepActivityDraft {
    data class Existing(
        val snapshotId: ActivitySnapshotId,
        val configuration: ActivitySnapshotDraft,
    ) : StepActivityDraft

    data class FromTemplate(
        val templateId: ActivityTemplateId,
    ) : StepActivityDraft

    data class Local(
        val configuration: ActivitySnapshotDraft,
    ) : StepActivityDraft
}

data class ActivityStepDraft(
    val identity: DraftIdentity<SequenceNodeId>,
    val position: Int,
    val activity: StepActivityDraft,
    val overrides: SequenceStepOverrides = SequenceStepOverrides(),
)

data class SequenceRepeatBlockDraft(
    val identity: DraftIdentity<SequenceNodeId>,
    val position: Int,
    val repeatCount: Int,
    val children: List<ActivityStepDraft>,
)

sealed interface SequenceNodeDraft {
    val identity: DraftIdentity<SequenceNodeId>
    val position: Int

    data class Step(
        val value: ActivityStepDraft,
    ) : SequenceNodeDraft {
        override val identity = value.identity
        override val position = value.position
    }

    data class Repeat(
        val value: SequenceRepeatBlockDraft,
    ) : SequenceNodeDraft {
        override val identity = value.identity
        override val position = value.position
    }
}

data class SequenceTemplateDraft(
    val name: String,
    val shortComment: String?,
    val noLiveTimeAccounting: NoLiveTimeAccounting = NoLiveTimeAccounting.ACTIVE,
    val settings: SequenceTemplateSettings = SequenceTemplateSettings(),
    val fields: List<SequenceFieldDraft> = emptyList(),
    val nodes: List<SequenceNodeDraft> = emptyList(),
)

enum class AuthoringSaveKind {
    NO_OP,
    PRESENTATION_ONLY,
    SEMANTIC,
}

enum class LinkedStepPropagationMode {
    ONLY_UNMODIFIED,
    ALL,
}

@Suppress("TooManyFunctions") // Explicit semantic and presentation projections keep revision policy auditable.
object TemplateAuthoringPolicy {
    fun classify(
        current: ActivityTemplate,
        proposed: ActivityTemplate,
    ): AuthoringSaveKind =
        when {
            activitySemanticState(current) != activitySemanticState(proposed) -> AuthoringSaveKind.SEMANTIC
            activityPresentationState(current) != activityPresentationState(proposed) ->
                AuthoringSaveKind.PRESENTATION_ONLY
            else -> AuthoringSaveKind.NO_OP
        }

    fun classify(
        current: SequenceTemplate,
        proposed: SequenceTemplate,
    ): AuthoringSaveKind =
        when {
            sequenceSemanticState(current) != sequenceSemanticState(proposed) -> AuthoringSaveKind.SEMANTIC
            sequencePresentationState(current) != sequencePresentationState(proposed) ->
                AuthoringSaveKind.PRESENTATION_ONLY
            else -> AuthoringSaveKind.NO_OP
        }

    fun shouldPropagate(
        locallyModified: Boolean,
        mode: LinkedStepPropagationMode,
    ): Boolean = mode == LinkedStepPropagationMode.ALL || !locallyModified

    fun sameConfiguration(
        current: ActivityConfigSnapshot,
        proposed: ActivityConfigSnapshot,
    ): Boolean = activitySnapshotState(current) == activitySnapshotState(proposed)

    private fun activitySemanticState(template: ActivityTemplate) =
        listOf(
            template.name,
            template.shortComment,
            template.timeTrackingMode,
            template.timerTarget,
            template.settings,
            template.fields.map(::activityFieldSemanticState),
        )

    private fun activityPresentationState(template: ActivityTemplate) =
        template.fields.map { field -> field.id to (field.name to field.categoryOptions.map { it.id to it.label }) }

    private fun activityFieldSemanticState(field: ActivityTemplateField) =
        listOf(
            field.id,
            field.position,
            field.type,
            field.unit,
            field.displayPrecision,
            field.defaultNumberScaled,
            field.defaultCategoryOptionId,
            field.defaultText,
            field.isMainValue,
            field.deletedAt != null,
            field.categoryOptions.map { listOf(it.id, it.position, it.isArchived) },
        )

    private fun sequenceSemanticState(template: SequenceTemplate) =
        listOf(
            template.name,
            template.shortComment,
            template.noLiveTimeAccounting,
            template.settings,
            template.fields.map(::sequenceFieldSemanticState),
            template.nodes,
        )

    private fun sequencePresentationState(template: SequenceTemplate) =
        template.fields.map { field -> field.id to (field.name to field.categoryOptions.map { it.id to it.label }) }

    private fun sequenceFieldSemanticState(field: SequenceTemplateField) =
        listOf(
            field.id,
            field.position,
            field.type,
            field.unit,
            field.displayPrecision,
            field.defaultNumberScaled,
            field.defaultCategoryOptionId,
            field.defaultText,
            field.isMainValue,
            field.deletedAt != null,
            field.categoryOptions.map { listOf(it.id, it.position, it.isArchived) },
        )

    private fun activitySnapshotState(snapshot: ActivityConfigSnapshot) =
        listOf(
            snapshot.name,
            snapshot.shortComment,
            snapshot.timeTrackingMode,
            snapshot.timerTarget,
            snapshot.sourceTemplateId,
            snapshot.sourceRevision,
            snapshot.statisticsSeriesId,
            snapshot.settings,
            snapshot.fields.map { field ->
                listOf(
                    field.sourceFieldId,
                    field.position,
                    field.nameAtCreation,
                    field.localNameOverride,
                    field.type,
                    field.unit,
                    field.displayPrecision,
                    field.defaultNumberScaled,
                    field.defaultText,
                    field.isMainValue,
                    field.categoryOptions.map { option ->
                        listOf(
                            option.sourceOptionId,
                            option.position,
                            option.labelAtCreation,
                            option.localLabelOverride,
                            option.id == field.defaultCategoryOptionId,
                        )
                    },
                )
            },
        )
}

object TemplateAuthoringDraftValidator {
    fun requireValid(draft: ActivityTemplateDraft) {
        ActivityTemplateValidator.requireValidTracking(draft.timeTrackingMode, draft.timerTarget)
        require(!draft.settings.startCountdown.isNegative) { "Start countdown must not be negative" }
        requireUniqueIdentities(draft.fields.map(ActivityFieldDraft::identity), "Activity Field")
        require(draft.fields.count(ActivityFieldDraft::isMainValue) <= 1) {
            "ActivityTemplate may have at most one Main Value"
        }
        draft.fields.forEach { field ->
            requireUniqueIdentities(field.categoryOptions.map(ActivityCategoryOptionDraft::identity), "Category option")
            requireDefaultBelongs(field.defaultCategoryOption, field.categoryOptions.map { it.identity })
        }
    }

    fun requireValid(draft: SequenceTemplateDraft) {
        require(!draft.settings.sequenceStartCountdown.isNegative) { "Sequence start countdown must not be negative" }
        require(!draft.settings.beforeEachStepCountdown.isNegative) { "Before-step countdown must not be negative" }
        requireUniqueIdentities(draft.fields.map(SequenceFieldDraft::identity), "Sequence Field")
        val nodeIdentities =
            draft.nodes.flatMap { node ->
                when (node) {
                    is SequenceNodeDraft.Step -> listOf(node.identity)
                    is SequenceNodeDraft.Repeat -> listOf(node.identity) + node.value.children.map { it.identity }
                }
            }
        requireUniqueIdentities(nodeIdentities, "Sequence node")
        requireOrderedPositions(draft.nodes.map(SequenceNodeDraft::position), "top-level")
        draft.nodes.forEach { node ->
            if (node is SequenceNodeDraft.Repeat) {
                require(node.value.repeatCount > 0) { "Repeat count must be positive" }
                requireOrderedPositions(node.value.children.map(ActivityStepDraft::position), "Repeat")
            }
        }
        draft.fields.forEach { field ->
            requireUniqueIdentities(field.categoryOptions.map(SequenceCategoryOptionDraft::identity), "Category option")
            requireDefaultBelongs(field.defaultCategoryOption, field.categoryOptions.map { it.identity })
        }
    }

    private fun requireUniqueIdentities(
        identities: List<DraftIdentity<*>>,
        label: String,
    ) {
        require(identities.distinct().size == identities.size) { "$label identities must be unique" }
    }

    private fun requireDefaultBelongs(
        default: DraftIdentity<*>?,
        options: List<DraftIdentity<*>>,
    ) {
        require(default == null || default in options) { "Category default must belong to the same Field" }
    }

    private fun requireOrderedPositions(
        positions: List<Int>,
        label: String,
    ) {
        require(positions == positions.sorted() && positions.distinct().size == positions.size) {
            "$label positions must be ordered and unique"
        }
    }
}

fun ActivityTemplate.toAuthoringDraft() =
    ActivityTemplateDraft(
        name,
        shortComment,
        timeTrackingMode,
        timerTarget,
        settings,
        fields.filter { it.deletedAt == null }.map { field ->
            ActivityFieldDraft(
                DraftIdentity.Existing(field.id),
                field.position,
                field.name,
                field.type,
                field.unit,
                field.displayPrecision,
                field.defaultNumberScaled,
                field.defaultCategoryOptionId?.let { DraftIdentity.Existing(it) },
                field.defaultText,
                field.isMainValue,
                field.categoryOptions.filterNot { it.isArchived }.map { option ->
                    ActivityCategoryOptionDraft(
                        DraftIdentity.Existing(option.id),
                        option.position,
                        option.label,
                    )
                },
            )
        },
    )

fun ActivityConfigSnapshot.toAuthoringDraft() =
    ActivitySnapshotDraft(
        name,
        shortComment,
        timeTrackingMode,
        timerTarget,
        settings,
        fields.map { field ->
            ActivitySnapshotFieldDraft(
                DraftIdentity.Existing(field.id),
                field.sourceFieldId,
                field.position,
                field.nameAtCreation,
                field.localNameOverride,
                field.type,
                field.unit,
                field.displayPrecision,
                field.defaultNumberScaled,
                field.defaultCategoryOptionId?.let { DraftIdentity.Existing(it) },
                field.defaultText,
                field.isMainValue,
                field.categoryOptions.map { option ->
                    ActivitySnapshotCategoryOptionDraft(
                        DraftIdentity.Existing(option.id),
                        option.sourceOptionId,
                        option.position,
                        option.labelAtCreation,
                        option.localLabelOverride,
                    )
                },
            )
        },
    )

fun SequenceTemplate.toAuthoringDraft(activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>) =
    SequenceTemplateDraft(
        name,
        shortComment,
        noLiveTimeAccounting,
        settings,
        fields.filter { it.deletedAt == null }.map { field ->
            SequenceFieldDraft(
                DraftIdentity.Existing(field.id),
                field.position,
                field.name,
                field.type,
                field.unit,
                field.displayPrecision,
                field.defaultNumberScaled,
                field.defaultCategoryOptionId?.let { DraftIdentity.Existing(it) },
                field.defaultText,
                field.isMainValue,
                field.categoryOptions.filterNot { it.isArchived }.map { option ->
                    SequenceCategoryOptionDraft(
                        DraftIdentity.Existing(option.id),
                        option.position,
                        option.label,
                    )
                },
            )
        },
        nodes.map { node ->
            when (node) {
                is ActivityStep -> SequenceNodeDraft.Step(node.toAuthoringDraft(activitySnapshots))
                is SequenceRepeatBlock ->
                    SequenceNodeDraft.Repeat(
                        SequenceRepeatBlockDraft(
                            DraftIdentity.Existing(node.id),
                            node.position,
                            node.repeatCount,
                            node.children.map { it.toAuthoringDraft(activitySnapshots) },
                        ),
                    )
            }
        },
    )

private fun ActivityStep.toAuthoringDraft(activitySnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>) =
    ActivityStepDraft(
        DraftIdentity.Existing(id),
        position,
        StepActivityDraft.Existing(
            activitySnapshotId,
            requireNotNull(activitySnapshots[activitySnapshotId]) {
                "ActivitySnapshot is missing for Step ${id.value}"
            }.toAuthoringDraft(),
        ),
        overrides,
    )
