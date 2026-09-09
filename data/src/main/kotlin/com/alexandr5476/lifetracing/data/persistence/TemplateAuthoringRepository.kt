@file:Suppress(
    "CyclomaticComplexMethod",
    "LargeClass",
    "LongMethod",
    "LongParameterList",
    "MaxLineLength",
    "TooManyFunctions",
)

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOption
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldEvolution
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateRevisionPolicy
import com.alexandr5476.lifetracing.domain.ActivityTemplateUserState
import com.alexandr5476.lifetracing.domain.ActivityTemplateValidator
import com.alexandr5476.lifetracing.domain.AuthoringSaveKind
import com.alexandr5476.lifetracing.domain.CategoryOption
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.LinkedStepPropagationMode
import com.alexandr5476.lifetracing.domain.SequenceCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceNode
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateAuthoringState
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOption
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateField
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.SequenceTemplateRevisionPolicy
import com.alexandr5476.lifetracing.domain.SequenceTemplateUserState
import com.alexandr5476.lifetracing.domain.StatisticsSeries
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TemplateAuthoringDraftValidator
import com.alexandr5476.lifetracing.domain.TemplateAuthoringPolicy
import com.alexandr5476.lifetracing.domain.TemplateLibraryPlacement
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable

data class LinkedStepPropagationResult(
    val updatedSteps: Int,
    val updatedSequences: Int,
    val skippedLocallyModified: Int,
)

class TemplateAuthoringRepository internal constructor(
    private val database: LifeTracingDatabase,
    private val ids: TemplateAuthoringIds,
) {
    fun getActivityTemplate(id: ActivityTemplateId): ActivityTemplate? =
        transaction { database.activityTemplateDao().getAggregate(id.value)?.toDomain() }

    fun getSequenceTemplate(id: SequenceTemplateId): SequenceTemplate? =
        transaction { database.sequenceTemplateDao().getAggregate(id.value)?.toDomain() }

    fun getSequenceTemplateAuthoringState(id: SequenceTemplateId): SequenceTemplateAuthoringState? =
        transaction {
            val sequence = database.sequenceTemplateDao().getAggregate(id.value)?.toDomain() ?: return@transaction null
            val snapshotIds = sequence.nodes.flatMap { it.activitySnapshotIds() }.distinct()
            val snapshots =
                loadActivitySnapshots(
                    snapshotIds.map(ActivitySnapshotId::value),
                ).associateBy(ActivityConfigSnapshot::id)
            require(snapshots.size == snapshotIds.size) { "SequenceTemplate is missing ActivitySnapshot data" }
            SequenceTemplateAuthoringState(sequence, snapshots)
        }

    fun getStepSnapshot(
        sequenceTemplateId: SequenceTemplateId,
        stepId: SequenceNodeId,
    ): ActivityConfigSnapshot? =
        transaction {
            val sequence =
                database.sequenceTemplateDao().getAggregate(sequenceTemplateId.value)?.toDomain()
                    ?: return@transaction null
            val step = sequence.findStep(stepId) ?: return@transaction null
            database.activitySnapshotDao().getAggregate(step.activitySnapshotId.value)?.toDomain()
        }

    fun createActivityTemplate(
        draft: ActivityTemplateDraft,
        placement: TemplateLibraryPlacement = TemplateLibraryPlacement(),
        createdAt: Instant,
    ): ActivityTemplate = transaction { createActivityTemplateLocked(draft, placement, createdAt) }

    fun saveActivityTemplate(
        id: ActivityTemplateId,
        expectedRevision: Long,
        draft: ActivityTemplateDraft,
        savedAt: Instant,
    ): ActivityTemplate = transaction { saveActivityTemplateLocked(id, expectedRevision, draft, savedAt) }

    fun createSequenceTemplate(
        draft: SequenceTemplateDraft,
        placement: TemplateLibraryPlacement = TemplateLibraryPlacement(),
        createdAt: Instant,
    ): SequenceTemplate = transaction { createSequenceTemplateLocked(draft, placement, createdAt) }

    fun saveSequenceTemplate(
        id: SequenceTemplateId,
        expectedRevision: Long,
        draft: SequenceTemplateDraft,
        savedAt: Instant,
    ): SequenceTemplate = transaction { saveSequenceTemplateLocked(id, expectedRevision, draft, savedAt) }

    fun saveStepConfiguration(
        sequenceTemplateId: SequenceTemplateId,
        stepId: SequenceNodeId,
        expectedRevision: Long,
        draft: ActivitySnapshotDraft,
        savedAt: Instant,
    ): SequenceTemplate =
        transaction {
            val (sequence, step, current) = requireEditableStep(sequenceTemplateId, stepId, expectedRevision)
            val replacement = snapshotFromDraft(draft, current, true, savedAt)
            if (TemplateAuthoringPolicy.sameConfiguration(current, replacement)) return@transaction sequence
            requireStrictlyLater(savedAt, sequence.updatedAt, "Sequence save")
            database.sequenceTemplateDao().replaceStepSnapshot(
                step.id.value,
                replacement.toEntityAggregate(),
                expectedRevision,
                savedAt.toEpochMilli(),
            )
            requireNotNull(database.sequenceTemplateDao().getAggregate(sequence.id.value)).toDomain()
        }

    fun updateStepFromSourceTemplate(
        sequenceTemplateId: SequenceTemplateId,
        stepId: SequenceNodeId,
        expectedRevision: Long,
        savedAt: Instant,
    ): SequenceTemplate =
        transaction {
            val (sequence, step, current) = requireEditableStep(sequenceTemplateId, stepId, expectedRevision)
            val sourceId = requireNotNull(current.sourceTemplateId) { "Step source is unavailable" }
            val source = requireActiveActivity(sourceId)
            requireStrictlyLater(savedAt, sequence.updatedAt, "Sequence save")
            val replacement = activitySnapshotFactory().fromTemplate(source, savedAt)
            database.sequenceTemplateDao().replaceStepSnapshot(
                step.id.value,
                replacement.toEntityAggregate(),
                expectedRevision,
                savedAt.toEpochMilli(),
                SequenceStepSnapshotReplacementMode.FROM_SOURCE,
            )
            requireNotNull(database.sequenceTemplateDao().getAggregate(sequence.id.value)).toDomain()
        }

    fun updateSourceTemplateFromStep(
        sequenceTemplateId: SequenceTemplateId,
        stepId: SequenceNodeId,
        expectedSequenceRevision: Long,
        expectedSourceRevision: Long,
        savedAt: Instant,
    ): ActivityTemplate =
        transaction {
            val (_, _, snapshot) = requireEditableStep(sequenceTemplateId, stepId, expectedSequenceRevision)
            val sourceId = requireNotNull(snapshot.sourceTemplateId) { "Step source is unavailable" }
            val source = requireActiveActivity(sourceId)
            require(source.revision == expectedSourceRevision) { "ActivityTemplate revision changed concurrently" }
            saveActivityTemplateLocked(sourceId, expectedSourceRevision, snapshot.toTemplateDraft(source), savedAt)
        }

    fun saveStepAsNewActivityTemplate(
        sequenceTemplateId: SequenceTemplateId,
        stepId: SequenceNodeId,
        expectedRevision: Long,
        placement: TemplateLibraryPlacement = TemplateLibraryPlacement(),
        savedAt: Instant,
    ): ActivityTemplate =
        transaction {
            val (sequence, step, current) = requireEditableStep(sequenceTemplateId, stepId, expectedRevision)
            requireStrictlyLater(savedAt, sequence.updatedAt, "Sequence save")
            val source =
                current.sourceTemplateId?.let { sourceId ->
                    database.activityTemplateDao().getAggregate(sourceId.value)?.toDomain()
                }
            val created = createActivityTemplateLocked(current.toNewTemplateDraft(source), placement, savedAt)
            val replacement = activitySnapshotFactory().fromTemplate(created, savedAt)
            database.sequenceTemplateDao().replaceStepSnapshot(
                step.id.value,
                replacement.toEntityAggregate(),
                expectedRevision,
                savedAt.toEpochMilli(),
                SequenceStepSnapshotReplacementMode.NEW_SOURCE,
            )
            created
        }

    fun propagateActivityTemplateToLinkedSteps(
        id: ActivityTemplateId,
        expectedRevision: Long,
        mode: LinkedStepPropagationMode,
        savedAt: Instant,
    ): LinkedStepPropagationResult =
        transaction {
            val source = requireActiveActivity(id)
            require(source.revision == expectedRevision) { "ActivityTemplate revision changed concurrently" }
            val owners = database.sequenceTemplateDao().getLinkedStepOwners(id.value)
            val eligible = owners.filter { TemplateAuthoringPolicy.shouldPropagate(it.locallyModified, mode) }
            val currentSnapshots =
                loadActivitySnapshots(eligible.map(LinkedStepOwnerRow::activitySnapshotId)).associateBy { it.id.value }
            val replacements =
                eligible.mapNotNull { owner ->
                    val current = requireNotNull(currentSnapshots[owner.activitySnapshotId])
                    if (!owner.locallyModified && current.isSemanticallyCurrent(source)) {
                        return@mapNotNull null
                    }
                    val replacement = activitySnapshotFactory().fromTemplate(source, savedAt)
                    BulkStepSnapshotReplacement(owner, replacement.toEntityAggregate())
                }
            if (replacements.isNotEmpty()) {
                database.sequenceTemplateDao().bulkReplaceLinkedSteps(replacements, savedAt.toEpochMilli())
            }
            LinkedStepPropagationResult(
                replacements.size,
                replacements.map { it.owner.sequenceTemplateId }.distinct().size,
                owners.count { it.locallyModified && mode == LinkedStepPropagationMode.ONLY_UNMODIFIED },
            )
        }

    private fun createActivityTemplateLocked(
        draft: ActivityTemplateDraft,
        placement: TemplateLibraryPlacement,
        createdAt: Instant,
    ): ActivityTemplate {
        TemplateAuthoringDraftValidator.requireValid(draft)
        val id = ids.nextActivityTemplateId()
        val seriesId = ids.nextStatisticsSeriesId()
        val fields = resolveActivityFields(draft.fields, emptyList(), createdAt)
        val template =
            ActivityTemplate(
                id,
                draft.name,
                draft.shortComment,
                draft.timeTrackingMode,
                draft.timerTarget,
                seriesId,
                ActivityTemplateRevisionPolicy.INITIAL_REVISION,
                createdAt,
                createdAt,
                folderId = placement.folderId,
                settings = draft.settings,
                fields = fields,
                tagIds = placement.tagIds,
            ).also(ActivityTemplateValidator::requireValid)
        database.statisticsSeriesDao().insert(
            StatisticsSeries(seriesId, StatisticsSeriesKind.ACTIVITY, template.name, createdAt, null).toEntity(),
        )
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                template.toEntity(),
                template.settings.toEntity(id),
                fields.map { it.toEntity(id) },
                fields.flatMap { field -> field.categoryOptions.map { it.toEntity(field.id) } },
                template.tagIds.map { ActivityTemplateTagEntity(id.value, it.value) },
                ActivityTemplateUserState().toEntity(id),
            ),
        )
        return template
    }

    private fun saveActivityTemplateLocked(
        id: ActivityTemplateId,
        expectedRevision: Long,
        draft: ActivityTemplateDraft,
        savedAt: Instant,
    ): ActivityTemplate {
        TemplateAuthoringDraftValidator.requireValid(draft)
        val currentAggregate =
            requireNotNull(
                database.activityTemplateDao().getAggregate(id.value),
            ) { "Unknown ActivityTemplate: ${id.value}" }
        val current = currentAggregate.toDomain()
        require(current.deletedAt == null) { "Archived ActivityTemplate cannot be edited" }
        require(current.revision == expectedRevision) { "ActivityTemplate revision changed concurrently" }
        val fields = resolveActivityFields(draft.fields, current.fields, savedAt)
        val proposed =
            current
                .copy(
                    name = draft.name,
                    shortComment = draft.shortComment,
                    timeTrackingMode = draft.timeTrackingMode,
                    timerTarget = draft.timerTarget,
                    settings = draft.settings,
                    fields = fields,
                ).also(ActivityTemplateValidator::requireValid)
        return when (TemplateAuthoringPolicy.classify(current, proposed)) {
            AuthoringSaveKind.NO_OP -> current
            AuthoringSaveKind.PRESENTATION_ONLY -> {
                persistActivityPresentation(current, proposed, savedAt)
                requireNotNull(database.activityTemplateDao().getAggregate(id.value)).toDomain()
            }
            AuthoringSaveKind.SEMANTIC -> {
                requireStrictlyLater(savedAt, current.updatedAt, "Activity save")
                val committed = proposed.copy(revision = current.revision + 1, updatedAt = savedAt)
                val aggregate = committed.toSemanticUpdate(currentAggregate, expectedRevision)
                database.activityTemplateDao().updateSemanticAggregate(aggregate)
                requireNotNull(database.activityTemplateDao().getAggregate(id.value)).toDomain()
            }
        }
    }

    private fun createSequenceTemplateLocked(
        draft: SequenceTemplateDraft,
        placement: TemplateLibraryPlacement,
        createdAt: Instant,
    ): SequenceTemplate {
        TemplateAuthoringDraftValidator.requireValid(draft)
        val id = ids.nextSequenceTemplateId()
        val seriesId = ids.nextStatisticsSeriesId()
        val fields = resolveSequenceFields(draft.fields, emptyList(), createdAt)
        val resolved = resolveSequenceNodes(draft.nodes, null, emptyMap(), createdAt)
        resolved.newSnapshots.forEach { database.activitySnapshotDao().insertAggregate(it.toEntityAggregate()) }
        val template =
            SequenceTemplate(
                id,
                draft.name,
                draft.shortComment,
                seriesId,
                SequenceTemplateRevisionPolicy.INITIAL_REVISION,
                createdAt,
                createdAt,
                folderId = placement.folderId,
                noLiveTimeAccounting = draft.noLiveTimeAccounting,
                settings = draft.settings,
                userState = SequenceTemplateUserState(),
                fields = fields,
                tagIds = placement.tagIds,
                nodes = resolved.nodes,
            )
        database.statisticsSeriesDao().insert(
            StatisticsSeries(seriesId, StatisticsSeriesKind.SEQUENCE, template.name, createdAt, null).toEntity(),
        )
        database.sequenceTemplateDao().insertAggregate(template.toEntityAggregate())
        return template
    }

    private fun saveSequenceTemplateLocked(
        id: SequenceTemplateId,
        expectedRevision: Long,
        draft: SequenceTemplateDraft,
        savedAt: Instant,
    ): SequenceTemplate {
        TemplateAuthoringDraftValidator.requireValid(draft)
        val currentAggregate =
            requireNotNull(
                database.sequenceTemplateDao().getAggregate(id.value),
            ) { "Unknown SequenceTemplate: ${id.value}" }
        val current = currentAggregate.toDomain()
        require(current.deletedAt == null) { "Archived SequenceTemplate cannot be edited" }
        require(current.revision == expectedRevision) { "SequenceTemplate revision changed concurrently" }
        val snapshotIds =
            current.nodes
                .flatMap { it.activitySnapshotIds() }
                .map(ActivitySnapshotId::value)
                .distinct()
        val snapshots = loadActivitySnapshots(snapshotIds).associateBy(ActivityConfigSnapshot::id)
        require(snapshots.size == snapshotIds.size) { "SequenceTemplate is missing ActivitySnapshot data" }
        val fields = resolveSequenceFields(draft.fields, current.fields, savedAt)
        val resolved = resolveSequenceNodes(draft.nodes, current, snapshots, savedAt)
        val proposed =
            current.copy(
                name = draft.name,
                shortComment = draft.shortComment,
                noLiveTimeAccounting = draft.noLiveTimeAccounting,
                settings = draft.settings,
                fields = fields,
                nodes = resolved.nodes,
            )
        return when (TemplateAuthoringPolicy.classify(current, proposed)) {
            AuthoringSaveKind.NO_OP -> current
            AuthoringSaveKind.PRESENTATION_ONLY -> {
                persistSequencePresentation(current, proposed, savedAt)
                requireNotNull(database.sequenceTemplateDao().getAggregate(id.value)).toDomain()
            }
            AuthoringSaveKind.SEMANTIC -> {
                requireStrictlyLater(savedAt, current.updatedAt, "Sequence save")
                resolved.newSnapshots.forEach { database.activitySnapshotDao().insertAggregate(it.toEntityAggregate()) }
                val committed = proposed.copy(revision = current.revision + 1, updatedAt = savedAt)
                val aggregate = committed.toEntityAggregate()
                database.sequenceTemplateDao().updateSemanticAggregate(
                    SequenceTemplateSemanticUpdate(
                        expectedRevision,
                        aggregate.template,
                        aggregate.settings,
                        aggregate.fields,
                        aggregate.options,
                        aggregate.nodes,
                        aggregate.stepOverrides,
                        resolved.replacements,
                    ),
                )
                requireNotNull(database.sequenceTemplateDao().getAggregate(id.value)).toDomain()
            }
        }
    }

    private fun resolveSequenceNodes(
        drafts: List<SequenceNodeDraft>,
        current: SequenceTemplate?,
        currentSnapshots: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
        savedAt: Instant,
    ): ResolvedSequenceNodes {
        val existingNodes =
            current
                ?.nodes
                .orEmpty()
                .flatMap { node ->
                    when (node) {
                        is ActivityStep -> listOf(node)
                        is SequenceRepeatBlock -> listOf(node) + node.children
                    }
                }.associateBy(SequenceNode::id)
        val sources =
            drafts
                .flatMap { it.steps() }
                .mapNotNull { (it.activity as? StepActivityDraft.FromTemplate)?.templateId }
                .distinct()
                .associateWith(::requireActiveActivity)
        val newSnapshots = mutableListOf<ActivityConfigSnapshot>()
        val replacements = mutableListOf<SequenceStepSnapshotReplacement>()
        val duplicateSources = mutableSetOf<SequenceNodeId>()

        fun resolveStep(draft: ActivityStepDraft): ActivityStep {
            val id = resolveNodeIdentity(draft.identity)
            val previous = existingNodes[id]
            when (draft.identity) {
                is DraftIdentity.Existing ->
                    require(previous is ActivityStep) { "Existing Step must belong to the current SequenceTemplate" }
                is DraftIdentity.New -> require(previous == null) { "New Step identity collided with an existing node" }
            }
            val snapshotId =
                when (val activity = draft.activity) {
                    is StepActivityDraft.Existing -> {
                        val oldStep =
                            requireNotNull(previous as? ActivityStep) { "Existing snapshot requires an existing Step" }
                        require(oldStep.activitySnapshotId == activity.snapshotId) {
                            "Existing Step snapshot identity changed without an explicit source action"
                        }
                        val oldSnapshot = requireNotNull(currentSnapshots[activity.snapshotId])
                        val replacement = snapshotFromDraft(activity.configuration, oldSnapshot, true, savedAt)
                        if (TemplateAuthoringPolicy.sameConfiguration(oldSnapshot, replacement)) {
                            oldSnapshot.id
                        } else {
                            replacements +=
                                SequenceStepSnapshotReplacement(
                                    id.value,
                                    replacement.toEntityAggregate(),
                                    SequenceStepSnapshotReplacementMode.LOCAL,
                                )
                            replacement.id
                        }
                    }
                    is StepActivityDraft.FromTemplate -> {
                        require(previous == null) { "Source replacement is an explicit command" }
                        activitySnapshotFactory()
                            .fromTemplate(
                                requireNotNull(sources[activity.templateId]),
                                savedAt,
                            ).also {
                                newSnapshots += it
                            }.id
                    }
                    is StepActivityDraft.Local -> {
                        require(previous == null) { "Existing Step must preserve its committed snapshot identity" }
                        snapshotFromDraft(activity.configuration, null, true, savedAt).also { newSnapshots += it }.id
                    }
                    is StepActivityDraft.Duplicate -> {
                        require(draft.identity is DraftIdentity.New) { "Duplicate Step must receive a new identity" }
                        val source =
                            requireNotNull(existingNodes[activity.sourceStepId] as? ActivityStep) {
                                "Duplicate source must be an existing Step in the current SequenceTemplate"
                            }
                        duplicateSources += source.id
                        activitySnapshotFactory()
                            .duplicate(
                                requireNotNull(currentSnapshots[source.activitySnapshotId]) {
                                    "Duplicate source ActivitySnapshot is missing"
                                },
                                savedAt,
                            ).also { newSnapshots += it }
                            .id
                    }
                }
            return ActivityStep(id, draft.position, snapshotId, draft.overrides)
        }

        val nodes =
            drafts.map { node ->
                when (node) {
                    is SequenceNodeDraft.Step -> resolveStep(node.value)
                    is SequenceNodeDraft.Repeat -> {
                        val repeatId = resolveNodeIdentity(node.identity)
                        val previous = existingNodes[repeatId]
                        when (node.identity) {
                            is DraftIdentity.Existing ->
                                require(previous is SequenceRepeatBlock) {
                                    "Existing Repeat must belong to the current SequenceTemplate"
                                }
                            is DraftIdentity.New ->
                                require(previous == null) { "New Repeat identity collided with an existing node" }
                        }
                        SequenceRepeatBlock(
                            repeatId,
                            node.position,
                            node.value.repeatCount,
                            node.value.children.map { resolveStep(it) },
                        )
                    }
                }
            }
        require(
            duplicateSources.all { sourceId ->
                nodes.any { node ->
                    when (node) {
                        is ActivityStep -> node.id == sourceId
                        is SequenceRepeatBlock -> node.children.any { it.id == sourceId }
                    }
                }
            },
        ) { "Duplicate source Step must remain in the committed SequenceTemplate" }
        return ResolvedSequenceNodes(nodes, replacements, newSnapshots)
    }

    private fun resolveActivityFields(
        drafts: List<ActivityFieldDraft>,
        current: List<ActivityTemplateField>,
        savedAt: Instant,
    ): List<ActivityTemplateField> {
        val currentById = current.associateBy(ActivityTemplateField::id)
        val newFieldIds = drafts.newIds(ActivityFieldDraft::identity, ids.nextActivityTemplateFieldId)
        val retained = mutableSetOf<ActivityTemplateFieldId>()
        val resolved =
            drafts.map { draft ->
                val id = draft.identity.resolve(newFieldIds)
                val previous = currentById[id]
                if (draft.identity is DraftIdentity.Existing) {
                    require(previous?.deletedAt == null) { "Unknown or archived Activity Field: ${id.value}" }
                }
                retained += id
                val previousOptions = previous?.categoryOptions.orEmpty().associateBy(CategoryOption::id)
                val newOptionIds =
                    draft.categoryOptions.newIds(
                        ActivityCategoryOptionDraft::identity,
                        ids.nextCategoryOptionId,
                    )
                val retainedOptions = mutableSetOf<CategoryOptionId>()
                val options =
                    draft.categoryOptions.map { optionDraft ->
                        val optionId = optionDraft.identity.resolve(newOptionIds)
                        val old = previousOptions[optionId]
                        if (optionDraft.identity is DraftIdentity.Existing) {
                            require(
                                old != null && !old.isArchived,
                            ) { "Unknown or archived Category option: ${optionId.value}" }
                        }
                        retainedOptions += optionId
                        CategoryOption(optionId, optionDraft.position, optionDraft.label)
                    } +
                        previousOptions.values
                            .filter { it.id !in retainedOptions }
                            .map { if (it.isArchived) it else it.copy(isArchived = true) }
                val defaultId = draft.defaultCategoryOption?.resolve(newOptionIds)
                var field =
                    ActivityTemplateField(
                        id,
                        draft.position,
                        draft.name,
                        draft.type,
                        draft.unit,
                        draft.displayPrecision,
                        draft.defaultNumberScaled,
                        defaultId,
                        draft.defaultText,
                        draft.isMainValue,
                        previous?.createdAt ?: savedAt,
                        previous?.updatedAt ?: savedAt,
                        categoryOptions = options,
                    )
                if (draft.identity is DraftIdentity.Existing) {
                    ActivityTemplateFieldEvolution.requireSameIdentityCompatible(requireNotNull(previous), field)
                }
                if (previous != null && field != previous) {
                    require(savedAt >= previous.updatedAt) { "Activity Field update time is out of order" }
                    field = field.copy(updatedAt = savedAt)
                }
                field
            }
        return resolved +
            current.filter { it.id !in retained }.map { field ->
                if (field.deletedAt != null) {
                    field
                } else {
                    require(savedAt >= field.updatedAt) { "Activity Field archive time is out of order" }
                    field.copy(updatedAt = savedAt, deletedAt = savedAt)
                }
            }
    }

    private fun resolveSequenceFields(
        drafts: List<SequenceFieldDraft>,
        current: List<SequenceTemplateField>,
        savedAt: Instant,
    ): List<SequenceTemplateField> {
        val currentById = current.associateBy(SequenceTemplateField::id)
        val newFieldIds = drafts.newIds(SequenceFieldDraft::identity, ids.nextSequenceTemplateFieldId)
        val retained = mutableSetOf<SequenceTemplateFieldId>()
        val resolved =
            drafts.map { draft ->
                val id = draft.identity.resolve(newFieldIds)
                val previous = currentById[id]
                if (draft.identity is DraftIdentity.Existing) {
                    require(previous?.deletedAt == null) { "Unknown or archived Sequence Field: ${id.value}" }
                }
                retained += id
                val previousOptions =
                    previous?.categoryOptions.orEmpty().associateBy(SequenceTemplateCategoryOption::id)
                val newOptionIds =
                    draft.categoryOptions.newIds(
                        SequenceCategoryOptionDraft::identity,
                        ids.nextSequenceTemplateCategoryOptionId,
                    )
                val retainedOptions = mutableSetOf<SequenceTemplateCategoryOptionId>()
                val options =
                    draft.categoryOptions.map { optionDraft ->
                        val optionId = optionDraft.identity.resolve(newOptionIds)
                        val old = previousOptions[optionId]
                        if (optionDraft.identity is DraftIdentity.Existing) {
                            require(old != null && !old.isArchived) { "Unknown or archived Category option" }
                        }
                        retainedOptions += optionId
                        SequenceTemplateCategoryOption(optionId, optionDraft.position, optionDraft.label)
                    } +
                        previousOptions.values
                            .filter { it.id !in retainedOptions }
                            .map { if (it.isArchived) it else it.copy(isArchived = true) }
                var field =
                    SequenceTemplateField(
                        id,
                        draft.position,
                        draft.name,
                        draft.type,
                        draft.unit,
                        draft.displayPrecision,
                        draft.defaultNumberScaled,
                        draft.defaultCategoryOption?.resolve(newOptionIds),
                        draft.defaultText,
                        draft.isMainValue,
                        previous?.createdAt ?: savedAt,
                        previous?.updatedAt ?: savedAt,
                        categoryOptions = options,
                    )
                if (previous != null && field != previous) {
                    require(savedAt >= previous.updatedAt) { "Sequence Field update time is out of order" }
                    field = field.copy(updatedAt = savedAt)
                }
                field
            }
        return resolved +
            current.filter { it.id !in retained }.map { field ->
                if (field.deletedAt != null) {
                    field
                } else {
                    require(savedAt >= field.updatedAt) { "Sequence Field archive time is out of order" }
                    field.copy(updatedAt = savedAt, deletedAt = savedAt)
                }
            }
    }

    private fun snapshotFromDraft(
        draft: ActivitySnapshotDraft,
        previous: ActivityConfigSnapshot?,
        locallyModified: Boolean,
        createdAt: Instant,
    ): ActivityConfigSnapshot {
        val previousFields = previous?.fields.orEmpty().associateBy(ActivitySnapshotField::id)
        require(
            draft.fields
                .map(ActivitySnapshotFieldDraft::identity)
                .distinct()
                .size == draft.fields.size,
        ) {
            "Activity snapshot Field identities must be unique"
        }
        val fields =
            draft.fields.map { fieldDraft ->
                val previousField =
                    (fieldDraft.identity as? DraftIdentity.Existing)?.id?.let { id ->
                        requireNotNull(previousFields[id]) { "Unknown Activity snapshot Field: ${id.value}" }
                    }
                if (fieldDraft.identity is DraftIdentity.New) {
                    require(fieldDraft.sourceFieldId == null) { "New local Field cannot claim source identity" }
                }
                require(previousField == null || previousField.sourceFieldId == fieldDraft.sourceFieldId) {
                    "Snapshot Field source identity is immutable"
                }
                if (previousField?.sourceFieldId != null) {
                    require(previousField.type == fieldDraft.type && previousField.unit == fieldDraft.unit) {
                        "Source-linked snapshot Field type and unit are immutable"
                    }
                }
                val previousOptions =
                    previousField?.categoryOptions.orEmpty().associateBy(ActivitySnapshotCategoryOption::id)
                require(
                    fieldDraft.categoryOptions
                        .map(ActivitySnapshotCategoryOptionDraft::identity)
                        .distinct()
                        .size ==
                        fieldDraft.categoryOptions.size,
                ) { "Activity snapshot option identities must be unique" }
                val outputIds =
                    fieldDraft.categoryOptions.associate { it.identity to ids.nextActivitySnapshotCategoryOptionId() }
                val options =
                    fieldDraft.categoryOptions.map { optionDraft ->
                        val old =
                            (optionDraft.identity as? DraftIdentity.Existing)?.id?.let { optionId ->
                                requireNotNull(previousOptions[optionId]) {
                                    "Unknown Activity snapshot Category option: ${optionId.value}"
                                }
                            }
                        if (optionDraft.identity is DraftIdentity.New) {
                            require(
                                optionDraft.sourceOptionId == null,
                            ) { "New local option cannot claim source identity" }
                        }
                        require(old == null || old.sourceOptionId == optionDraft.sourceOptionId) {
                            "Snapshot option source identity is immutable"
                        }
                        ActivitySnapshotCategoryOption(
                            requireNotNull(outputIds[optionDraft.identity]),
                            optionDraft.sourceOptionId,
                            optionDraft.position,
                            optionDraft.labelAtCreation,
                            optionDraft.localLabelOverride,
                        )
                    }
                ActivitySnapshotField(
                    ids.nextActivitySnapshotFieldId(),
                    fieldDraft.sourceFieldId,
                    fieldDraft.position,
                    fieldDraft.nameAtCreation,
                    fieldDraft.localNameOverride,
                    fieldDraft.type,
                    fieldDraft.unit,
                    fieldDraft.displayPrecision,
                    fieldDraft.defaultNumberScaled,
                    fieldDraft.defaultCategoryOption?.let { requireNotNull(outputIds[it]) },
                    fieldDraft.defaultText,
                    fieldDraft.isMainValue,
                    options,
                )
            }
        return ActivityConfigSnapshot(
            ids.nextActivitySnapshotId(),
            draft.name,
            draft.shortComment,
            draft.timeTrackingMode,
            draft.timerTarget,
            previous?.sourceTemplateId,
            previous?.sourceRevision,
            previous?.statisticsSeriesId,
            locallyModified,
            createdAt,
            draft.settings,
            fields,
        ).also(com.alexandr5476.lifetracing.domain.ActivityConfigSnapshotValidator::requireValid)
    }

    private fun persistActivityPresentation(
        current: ActivityTemplate,
        proposed: ActivityTemplate,
        savedAt: Instant,
    ) {
        val oldFields = current.fields.associateBy(ActivityTemplateField::id)
        proposed.fields.forEach { field ->
            val old = oldFields[field.id] ?: return@forEach
            if (old.name != field.name) {
                require(savedAt >= old.updatedAt) { "Activity Field rename time is out of order" }
                check(
                    database.activityTemplateDao().updateFieldDisplayName(
                        field.id.value,
                        field.name,
                        savedAt.toEpochMilli(),
                    ) ==
                        1,
                )
            }
            val oldOptions = old.categoryOptions.associateBy(CategoryOption::id)
            field.categoryOptions.forEach { option ->
                if (oldOptions[option.id]?.label != option.label) {
                    check(database.activityTemplateDao().updateOptionDisplayLabel(option.id.value, option.label) == 1)
                }
            }
        }
    }

    private fun persistSequencePresentation(
        current: SequenceTemplate,
        proposed: SequenceTemplate,
        savedAt: Instant,
    ) {
        val oldFields = current.fields.associateBy(SequenceTemplateField::id)
        proposed.fields.forEach { field ->
            val old = oldFields[field.id] ?: return@forEach
            if (old.name != field.name) {
                require(savedAt >= old.updatedAt) { "Sequence Field rename time is out of order" }
                check(
                    database.sequenceTemplateDao().updateFieldDisplayName(
                        field.id.value,
                        field.name,
                        savedAt.toEpochMilli(),
                    ) ==
                        1,
                )
            }
            val oldOptions = old.categoryOptions.associateBy(SequenceTemplateCategoryOption::id)
            field.categoryOptions.forEach { option ->
                if (oldOptions[option.id]?.label != option.label) {
                    check(database.sequenceTemplateDao().updateOptionDisplayLabel(option.id.value, option.label) == 1)
                }
            }
        }
    }

    private fun requireEditableStep(
        sequenceTemplateId: SequenceTemplateId,
        stepId: SequenceNodeId,
        expectedRevision: Long,
    ): EditableStep {
        val sequence =
            requireNotNull(database.sequenceTemplateDao().getAggregate(sequenceTemplateId.value)) {
                "Unknown SequenceTemplate: ${sequenceTemplateId.value}"
            }.toDomain()
        require(sequence.deletedAt == null) { "Archived SequenceTemplate cannot be edited" }
        require(sequence.revision == expectedRevision) { "SequenceTemplate revision changed concurrently" }
        val step = requireNotNull(sequence.findStep(stepId)) { "Unknown Sequence Step: ${stepId.value}" }
        val snapshot =
            requireNotNull(database.activitySnapshotDao().getAggregate(step.activitySnapshotId.value)) {
                "Step ActivitySnapshot is missing"
            }.toDomain()
        return EditableStep(sequence, step, snapshot)
    }

    private fun requireActiveActivity(id: ActivityTemplateId): ActivityTemplate {
        val template =
            requireNotNull(database.activityTemplateDao().getAggregate(id.value)) {
                "Unknown ActivityTemplate: ${id.value}"
            }.toDomain()
        require(template.deletedAt == null) { "Archived ActivityTemplate is unavailable" }
        return template
    }

    private fun activitySnapshotFactory() =
        ActivitySnapshotFactory(
            ids.nextActivitySnapshotId,
            ids.nextActivitySnapshotFieldId,
            ids.nextActivitySnapshotCategoryOptionId,
        )

    private fun resolveNodeIdentity(identity: DraftIdentity<SequenceNodeId>): SequenceNodeId =
        when (identity) {
            is DraftIdentity.Existing -> identity.id
            is DraftIdentity.New -> ids.nextSequenceNodeId()
        }

    private fun requireStrictlyLater(
        next: Instant,
        previous: Instant,
        label: String,
    ) {
        require(next.toEpochMilli() > previous.toEpochMilli()) { "$label time must advance persisted milliseconds" }
    }

    private fun ActivityTemplate.toSemanticUpdate(
        current: ActivityTemplateAggregateEntity,
        expectedRevision: Long,
    ): ActivityTemplateSemanticUpdate =
        ActivityTemplateSemanticUpdate(
            toEntity(),
            settings.toEntity(id),
            fields.map { it.toEntity(id) },
            fields.flatMap { field -> field.categoryOptions.map { it.toEntity(field.id) } },
            expectedRevision,
        ).also {
            require(
                current.template.folderId == it.template.folderId,
            ) { "Activity Library placement must be preserved" }
        }

    private fun ActivityConfigSnapshot.toNewTemplateDraft(source: ActivityTemplate?): ActivityTemplateDraft =
        toTemplateDraft(source, preserveSourceIdentities = false)

    private fun ActivityConfigSnapshot.isSemanticallyCurrent(source: ActivityTemplate): Boolean =
        sourceTemplateId == source.id &&
            sourceRevision == source.revision &&
            statisticsSeriesId == source.statisticsSeriesId

    private fun ActivityConfigSnapshot.toTemplateDraft(
        source: ActivityTemplate?,
        preserveSourceIdentities: Boolean = true,
    ): ActivityTemplateDraft {
        val sourceFields =
            source?.fields.orEmpty().associateBy(
                ActivityTemplateField::id,
            )
        val fields =
            fields.mapIndexed { fieldIndex, field ->
                val sourceField = field.sourceFieldId?.let(sourceFields::get)
                val fieldIdentity: DraftIdentity<ActivityTemplateFieldId> =
                    if (preserveSourceIdentities && sourceField != null && sourceField.deletedAt == null) {
                        DraftIdentity.Existing(sourceField.id)
                    } else {
                        DraftIdentity.New("field-$fieldIndex")
                    }
                val sourceOptions =
                    sourceField?.categoryOptions.orEmpty().associateBy(
                        CategoryOption::id,
                    )
                val optionDrafts =
                    field.categoryOptions.mapIndexed { optionIndex, option ->
                        val sourceOption = option.sourceOptionId?.let(sourceOptions::get)
                        ActivityCategoryOptionDraft(
                            if (preserveSourceIdentities && sourceOption?.isArchived == false) {
                                DraftIdentity.Existing(sourceOption.id)
                            } else {
                                DraftIdentity.New("field-$fieldIndex-option-$optionIndex")
                            },
                            option.position,
                            option.localLabelOverride ?: sourceOption?.label ?: option.labelAtCreation,
                        )
                    }
                val defaultIdentity =
                    field.defaultCategoryOptionId?.let { defaultId ->
                        optionDrafts[field.categoryOptions.indexOfFirst { it.id == defaultId }].identity
                    }
                ActivityFieldDraft(
                    fieldIdentity,
                    field.position,
                    field.localNameOverride ?: sourceField?.name ?: field.nameAtCreation,
                    field.type,
                    field.unit,
                    field.displayPrecision,
                    field.defaultNumberScaled,
                    defaultIdentity,
                    field.defaultText,
                    field.isMainValue,
                    optionDrafts,
                )
            }
        return ActivityTemplateDraft(name, shortComment, timeTrackingMode, timerTarget, settings, fields)
    }

    private fun <D, T> List<D>.newIds(
        identity: (D) -> DraftIdentity<T>,
        next: () -> T,
    ): Map<String, T> =
        mapNotNull { draft -> (identity(draft) as? DraftIdentity.New)?.key }
            .associateWith { next() }

    private fun <T> DraftIdentity<T>.resolve(newIds: Map<String, T>): T =
        when (this) {
            is DraftIdentity.Existing -> id
            is DraftIdentity.New -> requireNotNull(newIds[key]) { "Unknown new draft identity: $key" }
        }

    private fun SequenceTemplate.findStep(id: SequenceNodeId): ActivityStep? =
        nodes.firstNotNullOfOrNull { node ->
            when (node) {
                is ActivityStep -> node.takeIf { it.id == id }
                is SequenceRepeatBlock -> node.children.singleOrNull { it.id == id }
            }
        }

    private fun SequenceNode.activitySnapshotIds(): List<ActivitySnapshotId> =
        when (this) {
            is ActivityStep -> listOf(activitySnapshotId)
            is SequenceRepeatBlock -> children.map(ActivityStep::activitySnapshotId)
        }

    private fun SequenceNodeDraft.steps(): List<ActivityStepDraft> =
        when (this) {
            is SequenceNodeDraft.Step -> listOf(value)
            is SequenceNodeDraft.Repeat -> value.children
        }

    private fun loadActivitySnapshots(ids: Collection<String>): List<ActivityConfigSnapshot> =
        ids
            .distinct()
            .chunked(
                SQLITE_SAFE_BIND_COUNT,
            ).flatMap(database.activitySnapshotDao()::getAggregates)
            .map { it.toDomain() }

    private fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable(block))

    companion object {
        private const val SQLITE_SAFE_BIND_COUNT = 900

        fun create(context: Context): TemplateAuthoringRepository =
            TemplateAuthoringRepository(
                LifeTracingDatabase.builder(context.applicationContext, "lifetracing.db").build(),
                TemplateAuthoringIds.random(),
            )
    }
}

internal data class TemplateAuthoringIds(
    val nextActivityTemplateId: () -> ActivityTemplateId,
    val nextSequenceTemplateId: () -> SequenceTemplateId,
    val nextStatisticsSeriesId: () -> StatisticsSeriesId,
    val nextActivityTemplateFieldId: () -> ActivityTemplateFieldId,
    val nextCategoryOptionId: () -> CategoryOptionId,
    val nextSequenceTemplateFieldId: () -> SequenceTemplateFieldId,
    val nextSequenceTemplateCategoryOptionId: () -> SequenceTemplateCategoryOptionId,
    val nextSequenceNodeId: () -> SequenceNodeId,
    val nextActivitySnapshotId: () -> ActivitySnapshotId,
    val nextActivitySnapshotFieldId: () -> ActivitySnapshotFieldId,
    val nextActivitySnapshotCategoryOptionId: () -> ActivitySnapshotCategoryOptionId,
) {
    companion object {
        fun random() =
            TemplateAuthoringIds(
                { ActivityTemplateId(uuid()) },
                { SequenceTemplateId(uuid()) },
                { StatisticsSeriesId(uuid()) },
                { ActivityTemplateFieldId(uuid()) },
                { CategoryOptionId(uuid()) },
                { SequenceTemplateFieldId(uuid()) },
                { SequenceTemplateCategoryOptionId(uuid()) },
                { SequenceNodeId(uuid()) },
                { ActivitySnapshotId(uuid()) },
                { ActivitySnapshotFieldId(uuid()) },
                { ActivitySnapshotCategoryOptionId(uuid()) },
            )

        private fun uuid() = UUID.randomUUID().toString()
    }
}

private data class ResolvedSequenceNodes(
    val nodes: List<SequenceNode>,
    val replacements: List<SequenceStepSnapshotReplacement>,
    val newSnapshots: List<ActivityConfigSnapshot>,
)

private data class EditableStep(
    val sequence: SequenceTemplate,
    val step: ActivityStep,
    val snapshot: ActivityConfigSnapshot,
)
