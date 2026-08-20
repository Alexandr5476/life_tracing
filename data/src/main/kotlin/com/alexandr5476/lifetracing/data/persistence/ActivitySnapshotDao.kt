package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

internal data class ActivitySnapshotAggregateEntity(
    val snapshot: ActivitySnapshotEntity,
    val settings: ActivitySnapshotSettingsEntity,
    val fields: List<ActivitySnapshotFieldEntity> = emptyList(),
    val options: List<ActivitySnapshotCategoryOptionEntity> = emptyList(),
)

@Dao
@Suppress("TooManyFunctions") // Aggregate persistence stays in one focused transactional DAO.
internal abstract class ActivitySnapshotDao {
    @Query("SELECT * FROM activity_snapshots WHERE id = :id")
    abstract fun getById(id: String): ActivitySnapshotEntity?

    @Query("SELECT * FROM activity_snapshots WHERE id IN (:ids)")
    protected abstract fun getByIds(ids: List<String>): List<ActivitySnapshotEntity>

    @Query("SELECT * FROM activity_snapshot_settings WHERE snapshot_id = :snapshotId")
    abstract fun getSettings(snapshotId: String): ActivitySnapshotSettingsEntity?

    @Query("SELECT * FROM activity_snapshot_settings WHERE snapshot_id IN (:ids)")
    protected abstract fun getSettingsByIds(ids: List<String>): List<ActivitySnapshotSettingsEntity>

    @Query("SELECT * FROM activity_snapshot_fields WHERE snapshot_id = :snapshotId ORDER BY position, id")
    abstract fun getFields(snapshotId: String): List<ActivitySnapshotFieldEntity>

    @Query("SELECT * FROM activity_snapshot_fields WHERE snapshot_id IN (:ids) ORDER BY snapshot_id, position, id")
    protected abstract fun getFieldsByIds(ids: List<String>): List<ActivitySnapshotFieldEntity>

    @Query(
        "SELECT options.* FROM activity_snapshot_category_options AS options " +
            "INNER JOIN activity_snapshot_fields AS fields ON fields.id = options.snapshot_field_id " +
            "WHERE fields.snapshot_id = :snapshotId ORDER BY fields.position, options.position, options.id",
    )
    abstract fun getOptions(snapshotId: String): List<ActivitySnapshotCategoryOptionEntity>

    @Query(
        "SELECT options.* FROM activity_snapshot_category_options AS options " +
            "INNER JOIN activity_snapshot_fields AS fields ON fields.id = options.snapshot_field_id " +
            "WHERE fields.snapshot_id IN (:ids) " +
            "ORDER BY fields.snapshot_id, fields.position, options.position, options.id",
    )
    protected abstract fun getOptionsByIds(ids: List<String>): List<ActivitySnapshotCategoryOptionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertSnapshot(snapshot: ActivitySnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertSettings(settings: ActivitySnapshotSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertFields(fields: List<ActivitySnapshotFieldEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertOptions(options: List<ActivitySnapshotCategoryOptionEntity>)

    @Query("DELETE FROM activity_snapshots WHERE id = :id")
    abstract fun hardDelete(id: String): Int

    @Transaction
    open fun insertAggregate(aggregate: ActivitySnapshotAggregateEntity) {
        insertSnapshot(aggregate.snapshot)
        insertSettings(aggregate.settings)
        if (aggregate.fields.isNotEmpty()) insertFields(aggregate.fields)
        if (aggregate.options.isNotEmpty()) insertOptions(aggregate.options)
    }

    @Transaction
    open fun getAggregate(id: String): ActivitySnapshotAggregateEntity? {
        val snapshot = getById(id) ?: return null
        val settings = checkNotNull(getSettings(id)) { "Snapshot $id is missing settings" }
        return ActivitySnapshotAggregateEntity(snapshot, settings, getFields(id), getOptions(id))
    }

    @Transaction
    open fun getAggregates(ids: List<String>): List<ActivitySnapshotAggregateEntity> {
        if (ids.isEmpty()) return emptyList()
        val settings = getSettingsByIds(ids).associateBy(ActivitySnapshotSettingsEntity::snapshotId)
        val fields = getFieldsByIds(ids).groupBy(ActivitySnapshotFieldEntity::snapshotId)
        val fieldOwners = fields.flatMap { (snapshotId, rows) -> rows.map { it.id to snapshotId } }.toMap()
        val options = getOptionsByIds(ids).groupBy { fieldOwners[it.snapshotFieldId] }
        return getByIds(ids).map { snapshot ->
            ActivitySnapshotAggregateEntity(
                snapshot,
                checkNotNull(settings[snapshot.id]) { "Snapshot ${snapshot.id} is missing settings" },
                fields[snapshot.id].orEmpty(),
                options[snapshot.id].orEmpty(),
            )
        }
    }
}
