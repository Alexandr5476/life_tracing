package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "active_session",
    foreignKeys = [
        ForeignKey(
            entity = ActivityExecutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_execution_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SequenceExecutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sequence_execution_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class ActiveSessionEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int,
    @ColumnInfo(name = "session_kind") val sessionKind: String,
    @ColumnInfo(name = "activity_execution_id") val activityExecutionId: String?,
    @ColumnInfo(name = "sequence_execution_id") val sequenceExecutionId: String?,
    val state: String,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
