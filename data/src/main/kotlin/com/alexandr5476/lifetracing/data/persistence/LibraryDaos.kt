package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(folder: FolderEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    fun update(folder: FolderEntity)

    @Query("SELECT * FROM folders WHERE id = :id")
    fun getById(id: String): FolderEntity?

    @Query("SELECT * FROM folders")
    fun getAll(): List<FolderEntity>

    @Query("DELETE FROM folders WHERE id = :id")
    fun deleteById(id: String): Int
}

@Dao
internal interface TagDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(tag: TagEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    fun update(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE id = :id")
    fun getById(id: String): TagEntity?

    @Query("SELECT * FROM tags")
    fun getAll(): List<TagEntity>

    @Query("DELETE FROM tags WHERE id = :id")
    fun deleteById(id: String): Int
}

@Dao
internal interface StatisticsSeriesDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(series: StatisticsSeriesEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    fun update(series: StatisticsSeriesEntity)

    @Query("SELECT * FROM statistics_series WHERE id = :id")
    fun getById(id: String): StatisticsSeriesEntity?

    @Query("SELECT * FROM statistics_series")
    fun getAll(): List<StatisticsSeriesEntity>
}
