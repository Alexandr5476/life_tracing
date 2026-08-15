package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.StatisticsSeries
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.Tag
import com.alexandr5476.lifetracing.domain.TagId
import java.time.Instant

internal fun FolderEntity.toDomain() =
    Folder(
        id = FolderId(id),
        name = name,
        parentFolderId = parentFolderId?.let(::FolderId),
        createdAt = Instant.ofEpochMilli(createdAtMs),
        updatedAt = Instant.ofEpochMilli(updatedAtMs),
    )

internal fun Folder.toEntity() =
    FolderEntity(
        id = id.value,
        name = name,
        parentFolderId = parentFolderId?.value,
        createdAtMs = createdAt.toEpochMilli(),
        updatedAtMs = updatedAt.toEpochMilli(),
    )

internal fun TagEntity.toDomain() =
    Tag(
        id = TagId(id),
        name = name,
        createdAt = Instant.ofEpochMilli(createdAtMs),
        updatedAt = Instant.ofEpochMilli(updatedAtMs),
    )

internal fun Tag.toEntity() =
    TagEntity(
        id = id.value,
        name = name,
        createdAtMs = createdAt.toEpochMilli(),
        updatedAtMs = updatedAt.toEpochMilli(),
    )

internal fun StatisticsSeriesEntity.toDomain() =
    StatisticsSeries(
        id = StatisticsSeriesId(id),
        kind = kind.toStatisticsSeriesKind(),
        displayName = displayName,
        createdAt = Instant.ofEpochMilli(createdAtMs),
        archivedAt = archivedAtMs?.let(Instant::ofEpochMilli),
    )

internal fun StatisticsSeries.toEntity() =
    StatisticsSeriesEntity(
        id = id.value,
        kind = kind.toStorageCode(),
        displayName = displayName,
        createdAtMs = createdAt.toEpochMilli(),
        archivedAtMs = archivedAt?.toEpochMilli(),
    )

private fun StatisticsSeriesKind.toStorageCode() =
    when (this) {
        StatisticsSeriesKind.ACTIVITY -> "ACTIVITY"
        StatisticsSeriesKind.SEQUENCE -> "SEQUENCE"
        StatisticsSeriesKind.ONE_OFF_BUCKET -> "ONE_OFF_BUCKET"
    }

private fun String.toStatisticsSeriesKind() =
    when (this) {
        "ACTIVITY" -> StatisticsSeriesKind.ACTIVITY
        "SEQUENCE" -> StatisticsSeriesKind.SEQUENCE
        "ONE_OFF_BUCKET" -> StatisticsSeriesKind.ONE_OFF_BUCKET
        else -> error("Unknown statistics series kind code: $this")
    }
