@file:Suppress("LongParameterList", "TooManyFunctions", "LargeClass")

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionFieldValue
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateRevisionPolicy
import com.alexandr5476.lifetracing.domain.ActivityTemplateUserState
import com.alexandr5476.lifetracing.domain.CategoryOption
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.FolderTreeValidator
import com.alexandr5476.lifetracing.domain.LibraryContents
import com.alexandr5476.lifetracing.domain.LibraryKindFilter
import com.alexandr5476.lifetracing.domain.LibraryPinnedRanks
import com.alexandr5476.lifetracing.domain.LibraryRoot
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.LibraryTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceNode
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceRuntimeState
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFactory
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOption
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateField
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.SequenceTemplateRevisionPolicy
import com.alexandr5476.lifetracing.domain.SequenceTemplateUserState
import com.alexandr5476.lifetracing.domain.StatisticsSeries
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.Tag
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable

class LibraryRepository internal constructor(
    private val database: LifeTracingDatabase,
    private val liveSessions: LiveSessionRepository,
    private val activitySnapshotFactory: ActivitySnapshotFactory,
    private val sequenceSnapshotFactory: SequenceSnapshotFactory,
    private val nextActivityTemplateId: () -> ActivityTemplateId,
    private val nextActivityFieldId: () -> ActivityTemplateFieldId,
    private val nextActivityOptionId: () -> CategoryOptionId,
    private val nextSequenceTemplateId: () -> SequenceTemplateId,
    private val nextSequenceFieldId: () -> SequenceTemplateFieldId,
    private val nextSequenceOptionId: () -> SequenceTemplateCategoryOptionId,
    private val nextSequenceNodeId: () -> SequenceNodeId,
    private val nextStatisticsSeriesId: () -> StatisticsSeriesId,
) {
    fun getRoot(): LibraryRoot =
        transaction {
            LibraryRoot(contentsLocked(null), pinnedLocked())
        }

    fun getFolderContents(folderId: FolderId): LibraryContents =
        transaction {
            requireFolder(folderId)
            contentsLocked(folderId)
        }

    fun getFolderPath(folderId: FolderId): List<Folder> =
        transaction {
            FolderTreeValidator.path(folderId) { id -> database.folderDao().getById(id.value)?.toDomain() }
        }

    fun getAll(filter: LibraryKindFilter = LibraryKindFilter.ALL): List<LibraryTrackable> =
        transaction {
            filtered(
                filter,
                { database.libraryDao().getActiveActivities() },
                { database.libraryDao().getActiveSequences() },
            )
        }

    fun search(
        query: String,
        filter: LibraryKindFilter = LibraryKindFilter.ALL,
    ): List<LibraryTrackable> =
        transaction {
            val pattern = query.toLikePattern()
            filtered(
                filter,
                { database.libraryDao().searchActivities(pattern) },
                { database.libraryDao().searchSequences(pattern) },
            )
        }

    fun getArchived(filter: LibraryKindFilter = LibraryKindFilter.ALL): List<LibraryTrackable> =
        transaction {
            filtered(
                filter,
                { database.libraryDao().getArchivedActivities() },
                { database.libraryDao().getArchivedSequences() },
            )
        }

    fun getByTag(
        tagId: TagId,
        filter: LibraryKindFilter = LibraryKindFilter.ALL,
    ): List<LibraryTrackable> =
        transaction {
            requireNotNull(database.tagDao().getById(tagId.value)) { "Unknown Tag: ${tagId.value}" }
            filtered(
                filter,
                { database.libraryDao().getActivitiesByTag(tagId.value) },
                { database.libraryDao().getSequencesByTag(tagId.value) },
            )
        }

    fun getPinned(): List<LibraryTrackable> = transaction(::pinnedLocked)

    fun getRecent(limit: Int): List<LibraryTrackable> {
        require(limit > 0) { "Recent limit must be positive" }
        return transaction {
            rowsToTrackables(
                database.libraryDao().getRecentActivities(limit),
                database.libraryDao().getRecentSequences(limit),
            ).sortedWith(recentComparator).take(limit)
        }
    }

    fun createFolder(
        id: FolderId,
        name: String,
        parentFolderId: FolderId?,
        createdAt: Instant,
    ): Folder =
        transaction {
            FolderTreeValidator.requireCanMove(id, parentFolderId) { candidate ->
                database.folderDao().getById(candidate.value)?.toDomain()
            }
            val folder = Folder(id, name, parentFolderId, createdAt, createdAt)
            database.folderDao().insert(folder.toEntity())
            folder
        }

    fun renameFolder(
        folderId: FolderId,
        name: String,
        updatedAt: Instant,
    ): Folder =
        transaction {
            val current = requireFolder(folderId)
            require(updatedAt >= current.updatedAt) { "Folder update time is out of order" }
            check(database.folderDao().rename(folderId.value, name, updatedAt.toEpochMilli()) == 1)
            current.copy(name = name, updatedAt = updatedAt)
        }

    fun moveFolder(
        folderId: FolderId,
        destinationParentFolderId: FolderId?,
        updatedAt: Instant,
    ): Folder =
        transaction {
            val current = requireFolder(folderId)
            require(updatedAt >= current.updatedAt) { "Folder update time is out of order" }
            FolderTreeValidator.requireCanMove(folderId, destinationParentFolderId) { candidate ->
                database.folderDao().getById(candidate.value)?.toDomain()
            }
            check(
                database
                    .folderDao()
                    .move(folderId.value, destinationParentFolderId?.value, updatedAt.toEpochMilli()) == 1,
            )
            current.copy(parentFolderId = destinationParentFolderId, updatedAt = updatedAt)
        }

    fun deleteFolderMovingContents(
        folderId: FolderId,
        destinationFolderId: FolderId?,
        at: Instant,
    ) {
        transaction {
            val current = requireFolder(folderId)
            require(at >= current.updatedAt) { "Folder update time is out of order" }
            getFolderPath(folderId)
            FolderTreeValidator.requireCanMove(folderId, destinationFolderId) { candidate ->
                database.folderDao().getById(candidate.value)?.toDomain()
            }
            require(database.folderDao().getChildren(folderId.value).all { at.toEpochMilli() >= it.updatedAtMs }) {
                "Folder update time is out of order"
            }
            database.libraryDao().moveDirectActivities(folderId.value, destinationFolderId?.value)
            database.libraryDao().moveDirectSequences(folderId.value, destinationFolderId?.value)
            database.folderDao().moveChildren(folderId.value, destinationFolderId?.value, at.toEpochMilli())
            check(database.folderDao().deleteById(folderId.value) == 1)
        }
    }

    fun deleteFolderAndArchiveContents(
        folderId: FolderId,
        at: Instant,
    ) {
        transaction {
            requireFolder(folderId)
            getFolderPath(folderId)
            val subtree = database.folderDao().getSubtree(folderId.value)
            require(subtree.any { it.id == folderId.value }) { "Unknown Folder: ${folderId.value}" }
            val ids = subtree.map(FolderEntity::id)
            database.libraryDao().archiveActivitiesInFolders(ids, at.toEpochMilli())
            database.libraryDao().archiveSequencesInFolders(ids, at.toEpochMilli())
            deletionOrder(subtree).forEach { id -> check(database.folderDao().deleteById(id) == 1) }
        }
    }

    fun moveActivityTemplateToFolder(
        templateId: ActivityTemplateId,
        folderId: FolderId?,
        at: Instant,
    ) = moveTemplatesToFolder(listOf(LibraryTemplateId.Activity(templateId)), folderId, at)

    fun moveSequenceTemplateToFolder(
        templateId: SequenceTemplateId,
        folderId: FolderId?,
        at: Instant,
    ) = moveTemplatesToFolder(listOf(LibraryTemplateId.Sequence(templateId)), folderId, at)

    @Suppress("UNUSED_PARAMETER") // Folder placement updates only folder_id; Template updated_at is semantic state.
    fun moveTemplatesToFolder(
        templateIds: List<LibraryTemplateId>,
        folderId: FolderId?,
        at: Instant,
    ) {
        transaction {
            folderId?.let(::requireFolder)
            require(templateIds.distinct().size == templateIds.size) { "Bulk move cannot contain duplicates" }
            val activityIds = templateIds.filterIsInstance<LibraryTemplateId.Activity>().map { it.id.value }
            val sequenceIds = templateIds.filterIsInstance<LibraryTemplateId.Sequence>().map { it.id.value }
            require(database.libraryDao().getExistingActivityIds(activityIds).toSet() == activityIds.toSet()) {
                "Bulk move contains an unknown ActivityTemplate"
            }
            require(database.libraryDao().getExistingSequenceIds(sequenceIds).toSet() == sequenceIds.toSet()) {
                "Bulk move contains an unknown SequenceTemplate"
            }
            if (activityIds.isNotEmpty()) {
                check(database.libraryDao().moveActivities(activityIds, folderId?.value) == activityIds.size)
            }
            if (sequenceIds.isNotEmpty()) {
                check(database.libraryDao().moveSequences(sequenceIds, folderId?.value) == sequenceIds.size)
            }
        }
    }

    fun createTag(
        id: TagId,
        name: String,
        createdAt: Instant,
    ): Tag =
        transaction {
            Tag(id, name, createdAt, createdAt).also { database.tagDao().insert(it.toEntity()) }
        }

    fun renameTag(
        tagId: TagId,
        name: String,
        updatedAt: Instant,
    ): Tag =
        transaction {
            val current =
                requireNotNull(database.tagDao().getById(tagId.value)) { "Unknown Tag: ${tagId.value}" }
                    .toDomain()
            require(updatedAt >= current.updatedAt) { "Tag update time is out of order" }
            check(database.tagDao().rename(tagId.value, name, updatedAt.toEpochMilli()) == 1)
            current.copy(name = name, updatedAt = updatedAt)
        }

    fun addTag(
        templateId: LibraryTemplateId,
        tagId: TagId,
    ) {
        transaction {
            requireTemplate(templateId, activeOnly = false)
            requireNotNull(database.tagDao().getById(tagId.value)) { "Unknown Tag: ${tagId.value}" }
            when (templateId) {
                is LibraryTemplateId.Activity ->
                    database.libraryDao().addActivityTag(ActivityTemplateTagEntity(templateId.value, tagId.value))
                is LibraryTemplateId.Sequence ->
                    database.libraryDao().addSequenceTag(SequenceTemplateTagEntity(templateId.value, tagId.value))
            }
        }
    }

    fun removeTag(
        templateId: LibraryTemplateId,
        tagId: TagId,
    ) {
        transaction {
            requireTemplate(templateId, activeOnly = false)
            requireNotNull(database.tagDao().getById(tagId.value)) { "Unknown Tag: ${tagId.value}" }
            when (templateId) {
                is LibraryTemplateId.Activity -> database.libraryDao().removeActivityTag(templateId.value, tagId.value)
                is LibraryTemplateId.Sequence -> database.libraryDao().removeSequenceTag(templateId.value, tagId.value)
            }
        }
    }

    fun deleteTag(tagId: TagId) {
        transaction {
            requireNotNull(database.tagDao().getById(tagId.value)) { "Unknown Tag: ${tagId.value}" }
            check(database.tagDao().deleteById(tagId.value) == 1)
        }
    }

    fun archiveActivityTemplate(
        templateId: ActivityTemplateId,
        at: Instant,
    ) = archive(LibraryTemplateId.Activity(templateId), at)

    fun archiveSequenceTemplate(
        templateId: SequenceTemplateId,
        at: Instant,
    ) = archive(LibraryTemplateId.Sequence(templateId), at)

    fun restoreActivityTemplate(templateId: ActivityTemplateId) = restore(LibraryTemplateId.Activity(templateId))

    fun restoreSequenceTemplate(templateId: SequenceTemplateId) = restore(LibraryTemplateId.Sequence(templateId))

    fun pin(templateId: LibraryTemplateId) {
        transaction {
            requireTemplate(templateId, activeOnly = true)
            val current = pinnedLocked()
            if (current.any { it.id == templateId }) return@transaction
            val rank =
                Math.addExact(
                    current.mapNotNull(LibraryTrackable::pinnedRank).maxOrNull() ?: 0,
                    PINNED_RANK_STEP,
                )
            setPinnedRank(templateId, rank)
        }
    }

    fun unpin(templateId: LibraryTemplateId) {
        transaction {
            requireTemplate(templateId, activeOnly = true)
            setPinnedRank(templateId, null)
        }
    }

    fun reorderPinned(orderedIds: List<LibraryTemplateId>) {
        transaction {
            val currentIds = pinnedLocked().map(LibraryTrackable::id)
            require(orderedIds.toSet() == currentIds.toSet() && orderedIds.size == currentIds.size) {
                "Pinned reorder must contain the complete active pinned set"
            }
            LibraryPinnedRanks.forOrder(orderedIds).forEach(::setPinnedRank)
        }
    }

    fun startActivityFromTemplate(
        templateId: ActivityTemplateId,
        startedAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
    ): ActivityExecution =
        transaction {
            val template = requireActiveActivity(templateId)
            require(template.timeTrackingMode != TimeTrackingMode.NO_LIVE_TRACKING) {
                "NO_LIVE_TRACKING Template requires quick completion"
            }
            val snapshot = activitySnapshotFactory.fromTemplate(template, createdAt)
            database.activitySnapshotDao().insertAggregate(snapshot.toEntityAggregate())
            val execution =
                liveSessions.startStandaloneTimedActivityFromSnapshot(snapshot.id, startedAt, createdAt, zoneId)
            check(database.libraryDao().touchActivity(templateId.value, startedAt.toEpochMilli()) == 1) {
                "ActivityTemplate is missing user state"
            }
            execution
        }

    fun completeNoLiveActivityFromTemplate(
        templateId: ActivityTemplateId,
        completedAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
        actualValues: List<ActivityExecutionFieldValue> = emptyList(),
    ): ActivityExecution =
        transaction {
            val template = requireActiveActivity(templateId)
            require(template.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                "Quick completion requires NO_LIVE_TRACKING"
            }
            val snapshot = activitySnapshotFactory.fromTemplate(template, createdAt)
            database.activitySnapshotDao().insertAggregate(snapshot.toEntityAggregate())
            val execution =
                liveSessions.completeNoLiveActivityFromSnapshot(
                    snapshot.id,
                    completedAt,
                    zoneId,
                    createdAt,
                    actualValues,
                )
            check(database.libraryDao().touchActivity(templateId.value, completedAt.toEpochMilli()) == 1) {
                "ActivityTemplate is missing user state"
            }
            execution
        }

    fun startSequenceFromTemplate(
        templateId: SequenceTemplateId,
        startedAt: Instant,
        createdAt: Instant,
        zoneId: ZoneId,
    ): SequenceRuntimeState =
        transaction {
            val template = requireActiveSequence(templateId)
            val snapshot = sequenceSnapshotFactory.fromTemplate(template, activityModes(template), createdAt)
            database.sequenceSnapshotDao().insertAggregate(snapshot.toEntityAggregate())
            val state = liveSessions.startSequenceFromSnapshot(snapshot.id, startedAt, createdAt, zoneId)
            check(database.libraryDao().touchSequence(templateId.value, startedAt.toEpochMilli()) == 1) {
                "SequenceTemplate is missing user state"
            }
            state
        }

    fun duplicateActivityTemplate(
        sourceId: ActivityTemplateId,
        createdAt: Instant,
    ): ActivityTemplate =
        transaction {
            val source = requireActiveActivity(sourceId)
            val id = nextActivityTemplateId()
            val seriesId = nextStatisticsSeriesId()
            val fields = source.fields.filter { it.deletedAt == null }.map { it.duplicate(createdAt) }
            val duplicate =
                source.copy(
                    id = id,
                    statisticsSeriesId = seriesId,
                    revision = ActivityTemplateRevisionPolicy.INITIAL_REVISION,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                    deletedAt = null,
                    fields = fields,
                )
            database.statisticsSeriesDao().insert(
                StatisticsSeries(seriesId, StatisticsSeriesKind.ACTIVITY, duplicate.name, createdAt, null).toEntity(),
            )
            database.activityTemplateDao().insertAggregate(
                ActivityTemplateAggregateEntity(
                    duplicate.toEntity(),
                    duplicate.settings.toEntity(id),
                    fields.map { it.toEntity(id) },
                    fields.flatMap { field -> field.categoryOptions.map { it.toEntity(field.id) } },
                    duplicate.tagIds.map { ActivityTemplateTagEntity(id.value, it.value) },
                    ActivityTemplateUserState().toEntity(id),
                ),
            )
            duplicate
        }

    fun duplicateSequenceTemplate(
        sourceId: SequenceTemplateId,
        createdAt: Instant,
    ): SequenceTemplate =
        transaction {
            val source = requireActiveSequence(sourceId)
            val id = nextSequenceTemplateId()
            val seriesId = nextStatisticsSeriesId()
            val duplicate =
                source.copy(
                    id = id,
                    statisticsSeriesId = seriesId,
                    revision = SequenceTemplateRevisionPolicy.INITIAL_REVISION,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                    deletedAt = null,
                    userState = SequenceTemplateUserState(),
                    fields = source.fields.filter { it.deletedAt == null }.map { it.duplicate(createdAt) },
                    nodes = source.nodes.map { it.duplicate() },
                )
            database.statisticsSeriesDao().insert(
                StatisticsSeries(seriesId, StatisticsSeriesKind.SEQUENCE, duplicate.name, createdAt, null).toEntity(),
            )
            database.sequenceTemplateDao().insertAggregate(duplicate.toEntityAggregate())
            duplicate
        }

    private fun contentsLocked(folderId: FolderId?): LibraryContents {
        val folders =
            (folderId?.let { database.folderDao().getChildren(it.value) } ?: database.folderDao().getRoots())
                .map(FolderEntity::toDomain)
        val activities = database.libraryDao().getActivitiesInFolder(folderId?.value)
        val sequences = database.libraryDao().getSequencesInFolder(folderId?.value)
        val trackables = rowsToTrackables(activities, sequences)
        return LibraryContents(
            folders,
            trackables.filter { it.kind == LibraryTrackableKind.ACTIVITY },
            trackables.filter { it.kind == LibraryTrackableKind.SEQUENCE },
        )
    }

    private fun filtered(
        filter: LibraryKindFilter,
        activityQuery: () -> List<LibrarySummaryRow>,
        sequenceQuery: () -> List<LibrarySummaryRow>,
    ): List<LibraryTrackable> =
        rowsToTrackables(
            if (filter == LibraryKindFilter.SEQUENCES) emptyList() else activityQuery(),
            if (filter == LibraryKindFilter.ACTIVITIES) emptyList() else sequenceQuery(),
        ).sortedWith(catalogComparator)

    private fun pinnedLocked(): List<LibraryTrackable> =
        rowsToTrackables(
            database.libraryDao().getPinnedActivities(),
            database.libraryDao().getPinnedSequences(),
        ).sortedWith(pinnedComparator)

    private fun rowsToTrackables(
        activities: List<LibrarySummaryRow>,
        sequences: List<LibrarySummaryRow>,
    ): List<LibraryTrackable> {
        val activityTags =
            if (activities.isEmpty()) {
                emptyMap()
            } else {
                database.libraryDao().getActivityTagLinks(activities.map(LibrarySummaryRow::id)).tagMap()
            }
        val sequenceTags =
            if (sequences.isEmpty()) {
                emptyMap()
            } else {
                database.libraryDao().getSequenceTagLinks(sequences.map(LibrarySummaryRow::id)).tagMap()
            }
        return activities.map { it.toDomain(true, activityTags[it.id].orEmpty()) } +
            sequences.map { it.toDomain(false, sequenceTags[it.id].orEmpty()) }
    }

    private fun requireFolder(id: FolderId): Folder =
        requireNotNull(database.folderDao().getById(id.value)) { "Unknown Folder: ${id.value}" }.toDomain()

    private fun requireActiveActivity(id: ActivityTemplateId): ActivityTemplate =
        requireNotNull(database.activityTemplateDao().getAggregate(id.value)) {
            "Unknown ActivityTemplate: ${id.value}"
        }.toDomain()
            .also { require(it.deletedAt == null) { "Archived ActivityTemplate cannot be used" } }

    private fun requireActiveSequence(id: SequenceTemplateId): SequenceTemplate =
        requireNotNull(database.sequenceTemplateDao().getAggregate(id.value)) {
            "Unknown SequenceTemplate: ${id.value}"
        }.toDomain()
            .also { require(it.deletedAt == null) { "Archived SequenceTemplate cannot be used" } }

    private fun requireTemplate(
        id: LibraryTemplateId,
        activeOnly: Boolean,
    ) {
        val deletedAt =
            when (id) {
                is LibraryTemplateId.Activity ->
                    requireNotNull(database.activityTemplateDao().getById(id.value)) {
                        "Unknown ActivityTemplate: ${id.value}"
                    }.deletedAtMs
                is LibraryTemplateId.Sequence ->
                    requireNotNull(database.sequenceTemplateDao().getById(id.value)) {
                        "Unknown SequenceTemplate: ${id.value}"
                    }.deletedAtMs
            }
        require(!activeOnly || deletedAt == null) { "Archived Template is not eligible for this operation" }
    }

    private fun archive(
        id: LibraryTemplateId,
        at: Instant,
    ) {
        transaction {
            requireTemplate(id, activeOnly = true)
            val changed =
                when (id) {
                    is LibraryTemplateId.Activity -> database.activityTemplateDao().archive(id.value, at.toEpochMilli())
                    is LibraryTemplateId.Sequence -> database.sequenceTemplateDao().archive(id.value, at.toEpochMilli())
                }
            check(changed == 1)
        }
    }

    private fun restore(id: LibraryTemplateId) {
        transaction {
            requireTemplate(id, activeOnly = false)
            val deletedAt =
                when (id) {
                    is LibraryTemplateId.Activity -> database.activityTemplateDao().getById(id.value)?.deletedAtMs
                    is LibraryTemplateId.Sequence -> database.sequenceTemplateDao().getById(id.value)?.deletedAtMs
                }
            require(deletedAt != null) { "Template is not archived" }
            val changed =
                when (id) {
                    is LibraryTemplateId.Activity -> database.activityTemplateDao().restore(id.value)
                    is LibraryTemplateId.Sequence -> database.sequenceTemplateDao().restore(id.value)
                }
            check(changed == 1)
        }
    }

    private fun setPinnedRank(
        id: LibraryTemplateId,
        rank: Int?,
    ) {
        val changed =
            when (id) {
                is LibraryTemplateId.Activity -> database.libraryDao().setActivityPinnedRank(id.value, rank)
                is LibraryTemplateId.Sequence -> database.libraryDao().setSequencePinnedRank(id.value, rank)
            }
        check(changed == 1) { "Template is missing user state" }
    }

    private fun activityModes(template: SequenceTemplate): Map<ActivitySnapshotId, TimeTrackingMode> {
        val ids = template.nodes.flatMap(SequenceNode::activitySnapshotIds).distinct()
        return database
            .activitySnapshotDao()
            .getAggregates(ids.map(ActivitySnapshotId::value))
            .associate {
                val snapshot = it.toDomain()
                snapshot.id to snapshot.timeTrackingMode
            }.also { require(it.keys == ids.toSet()) { "Sequence is missing ActivitySnapshot metadata" } }
    }

    private fun ActivityTemplateField.duplicate(at: Instant): ActivityTemplateField {
        val optionCopies = categoryOptions.filterNot(CategoryOption::isArchived).map { it to nextActivityOptionId() }
        val optionIds = optionCopies.associate { (source, id) -> source.id to id }
        return copy(
            id = nextActivityFieldId(),
            defaultCategoryOptionId = defaultCategoryOptionId?.let(optionIds::get),
            createdAt = at,
            updatedAt = at,
            deletedAt = null,
            categoryOptions =
                optionCopies.map { (source, id) ->
                    source.copy(id = id, isArchived = false)
                },
        )
    }

    private fun SequenceTemplateField.duplicate(at: Instant): SequenceTemplateField {
        val optionCopies =
            categoryOptions
                .filterNot(SequenceTemplateCategoryOption::isArchived)
                .map { it to nextSequenceOptionId() }
        val optionIds = optionCopies.associate { (source, id) -> source.id to id }
        return copy(
            id = nextSequenceFieldId(),
            defaultCategoryOptionId = defaultCategoryOptionId?.let(optionIds::get),
            createdAt = at,
            updatedAt = at,
            deletedAt = null,
            categoryOptions = optionCopies.map { (source, id) -> source.copy(id = id, isArchived = false) },
        )
    }

    private fun SequenceNode.duplicate(): SequenceNode =
        when (this) {
            is ActivityStep -> copy(id = nextSequenceNodeId())
            is SequenceRepeatBlock ->
                copy(
                    id = nextSequenceNodeId(),
                    children = children.map { it.copy(id = nextSequenceNodeId()) },
                )
        }

    private fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable(block))

    companion object {
        fun create(context: Context): LibraryRepository {
            val database = LifeTracingDatabase.builder(context.applicationContext, DATABASE_NAME).build()
            val live =
                LiveSessionRepository(
                    database,
                    { ActivityExecutionId(uuid()) },
                    { ActivityExecutionPauseId(uuid()) },
                    {
                        com.alexandr5476.lifetracing.domain
                            .SequenceExecutionId(uuid())
                    },
                    { SequenceOccurrenceId(uuid()) },
                    { SequenceIntervalId(uuid()) },
                )
            return LibraryRepository(
                database,
                live,
                ActivitySnapshotFactory(
                    { ActivitySnapshotId(uuid()) },
                    { ActivitySnapshotFieldId(uuid()) },
                    { ActivitySnapshotCategoryOptionId(uuid()) },
                ),
                SequenceSnapshotFactory(
                    { SequenceSnapshotId(uuid()) },
                    { SequenceSnapshotFieldId(uuid()) },
                    { SequenceSnapshotCategoryOptionId(uuid()) },
                    { SequenceSnapshotNodeId(uuid()) },
                ),
                { ActivityTemplateId(uuid()) },
                { ActivityTemplateFieldId(uuid()) },
                { CategoryOptionId(uuid()) },
                { SequenceTemplateId(uuid()) },
                { SequenceTemplateFieldId(uuid()) },
                { SequenceTemplateCategoryOptionId(uuid()) },
                { SequenceNodeId(uuid()) },
                { StatisticsSeriesId(uuid()) },
            )
        }

        private fun uuid(): String = UUID.randomUUID().toString()

        private const val PINNED_RANK_STEP = 1024
        private const val DATABASE_NAME = "lifetracing.db"
    }
}

private fun LibrarySummaryRow.toDomain(
    activity: Boolean,
    tagIds: Set<TagId>,
): LibraryTrackable =
    LibraryTrackable(
        if (activity) {
            LibraryTemplateId.Activity(ActivityTemplateId(id))
        } else {
            LibraryTemplateId.Sequence(SequenceTemplateId(id))
        },
        name,
        shortComment,
        folderId?.let(::FolderId),
        tagIds,
        pinnedRank,
        lastUsedAtMs?.let(Instant::ofEpochMilli),
        deletedAtMs?.let(Instant::ofEpochMilli),
    )

private fun List<LibraryTagLinkRow>.tagMap(): Map<String, Set<TagId>> =
    groupBy(LibraryTagLinkRow::templateId).mapValues { (_, links) -> links.mapTo(linkedSetOf()) { TagId(it.tagId) } }

private fun SequenceNode.activitySnapshotIds(): List<ActivitySnapshotId> =
    when (this) {
        is ActivityStep -> listOf(activitySnapshotId)
        is SequenceRepeatBlock -> children.map(ActivityStep::activitySnapshotId)
    }

private fun String.toLikePattern(): String = "%" + replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

private fun deletionOrder(folders: List<FolderEntity>): List<String> {
    val rows = folders.associateBy(FolderEntity::id)
    val childCounts = rows.keys.associateWithTo(mutableMapOf()) { 0 }
    folders.forEach { folder ->
        val parent = folder.parentFolderId
        if (parent in rows) childCounts[parent!!] = childCounts.getValue(parent) + 1
    }
    val leaves = ArrayDeque(childCounts.filterValues { it == 0 }.keys)
    val result = mutableListOf<String>()
    while (leaves.isNotEmpty()) {
        val id = leaves.removeFirst()
        result += id
        val parent = rows.getValue(id).parentFolderId
        if (parent in rows) {
            val remaining = childCounts.getValue(parent!!) - 1
            childCounts[parent] = remaining
            if (remaining == 0) leaves.addLast(parent)
        }
    }
    require(result.size == folders.size) { "Folder subtree contains a cycle" }
    return result
}

private val catalogComparator =
    Comparator<LibraryTrackable> { left, right ->
        String.CASE_INSENSITIVE_ORDER
            .compare(left.name, right.name)
            .takeIf { it != 0 }
            ?: left.kind.compareTo(right.kind).takeIf { it != 0 }
            ?: left.id.value.compareTo(right.id.value)
    }

private val pinnedComparator =
    compareBy<LibraryTrackable> { requireNotNull(it.pinnedRank) }
        .thenBy(LibraryTrackable::kind)
        .thenComparator { left, right -> catalogComparator.compare(left, right) }

private val recentComparator =
    compareByDescending<LibraryTrackable> { requireNotNull(it.lastUsedAt) }
        .thenBy(LibraryTrackable::kind)
        .thenComparator { left, right -> catalogComparator.compare(left, right) }
