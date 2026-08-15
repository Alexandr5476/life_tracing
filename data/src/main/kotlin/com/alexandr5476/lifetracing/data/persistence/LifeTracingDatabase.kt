package com.alexandr5476.lifetracing.data.persistence

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [FolderEntity::class, TagEntity::class, StatisticsSeriesEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class LifeTracingDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao

    abstract fun tagDao(): TagDao

    abstract fun statisticsSeriesDao(): StatisticsSeriesDao
}
