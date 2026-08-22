package com.alexandr5476.lifetracing.domain

import java.time.Instant

@JvmInline
value class FolderId(
    val value: String,
)

@JvmInline
value class TagId(
    val value: String,
)

@JvmInline
value class StatisticsSeriesId(
    val value: String,
)

data class Folder(
    val id: FolderId,
    val name: String,
    val parentFolderId: FolderId?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Tag(
    val id: TagId,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class StatisticsSeries(
    val id: StatisticsSeriesId,
    val kind: StatisticsSeriesKind,
    val displayName: String,
    val createdAt: Instant,
    val archivedAt: Instant?,
)

enum class StatisticsSeriesKind {
    ACTIVITY,
    SEQUENCE,
    ONE_OFF_BUCKET,
}

sealed interface LibraryTemplateId {
    val value: String

    data class Activity(
        val id: ActivityTemplateId,
    ) : LibraryTemplateId {
        override val value: String = id.value
    }

    data class Sequence(
        val id: SequenceTemplateId,
    ) : LibraryTemplateId {
        override val value: String = id.value
    }
}

enum class LibraryTrackableKind {
    ACTIVITY,
    SEQUENCE,
}

enum class LibraryKindFilter {
    ALL,
    ACTIVITIES,
    SEQUENCES,
}

data class LibraryTrackable(
    val id: LibraryTemplateId,
    val name: String,
    val shortComment: String?,
    val folderId: FolderId?,
    val tagIds: Set<TagId>,
    val pinnedRank: Int?,
    val lastUsedAt: Instant?,
    val archivedAt: Instant?,
) {
    val kind: LibraryTrackableKind =
        when (id) {
            is LibraryTemplateId.Activity -> LibraryTrackableKind.ACTIVITY
            is LibraryTemplateId.Sequence -> LibraryTrackableKind.SEQUENCE
        }

    val isArchived: Boolean = archivedAt != null
}

data class LibraryContents(
    val folders: List<Folder>,
    val activities: List<LibraryTrackable>,
    val sequences: List<LibraryTrackable>,
)

data class LibraryRoot(
    val contents: LibraryContents,
    val pinned: List<LibraryTrackable>,
)

object LibraryPinnedRanks {
    private const val STEP = 1024

    fun forOrder(ids: List<LibraryTemplateId>): Map<LibraryTemplateId, Int> {
        require(ids.distinct().size == ids.size) { "Pinned order cannot contain duplicate Template identities" }
        return ids.mapIndexed { index, id -> id to Math.multiplyExact(index + 1, STEP) }.toMap()
    }
}
