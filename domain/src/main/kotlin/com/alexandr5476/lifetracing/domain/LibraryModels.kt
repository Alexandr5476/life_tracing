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
