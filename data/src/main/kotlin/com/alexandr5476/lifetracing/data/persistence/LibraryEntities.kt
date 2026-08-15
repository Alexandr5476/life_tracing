package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_folder_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["parent_folder_id"], name = "folders_parent")],
)
internal data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "parent_folder_id") val parentFolderId: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(tableName = "tags")
internal data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(tableName = "statistics_series")
internal data class StatisticsSeriesEntity(
    @PrimaryKey val id: String,
    val kind: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "archived_at_ms") val archivedAtMs: Long?,
)
