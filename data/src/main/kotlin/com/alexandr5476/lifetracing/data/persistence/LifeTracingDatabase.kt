package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FolderEntity::class,
        TagEntity::class,
        StatisticsSeriesEntity::class,
        ActivityTemplateEntity::class,
        ActivityTemplateSettingsEntity::class,
        ActivityTemplateUserStateEntity::class,
        ActivityTemplateFieldEntity::class,
        ActivityTemplateCategoryOptionEntity::class,
        ActivityTemplateTagEntity::class,
        ActivitySnapshotEntity::class,
        ActivitySnapshotSettingsEntity::class,
        ActivitySnapshotFieldEntity::class,
        ActivitySnapshotCategoryOptionEntity::class,
        ActivityExecutionEntity::class,
        ActivityExecutionPauseEntity::class,
        ActivityExecutionFieldValueEntity::class,
        SequenceTemplateEntity::class,
        SequenceTemplateSettingsEntity::class,
        SequenceTemplateUserStateEntity::class,
        SequenceTemplateFieldEntity::class,
        SequenceTemplateCategoryOptionEntity::class,
        SequenceTemplateTagEntity::class,
        SequenceNodeEntity::class,
        SequenceStepOverrideEntity::class,
    ],
    version = SEQUENCE_TEMPLATE_SCHEMA_VERSION,
    exportSchema = true,
)
internal abstract class LifeTracingDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao

    abstract fun tagDao(): TagDao

    abstract fun statisticsSeriesDao(): StatisticsSeriesDao

    abstract fun activityTemplateDao(): ActivityTemplateDao

    abstract fun activitySnapshotDao(): ActivitySnapshotDao

    abstract fun activityExecutionDao(): ActivityExecutionDao

    abstract fun sequenceTemplateDao(): SequenceTemplateDao

    companion object {
        fun inMemoryBuilder(context: Context): Builder<LifeTracingDatabase> =
            Room
                .inMemoryDatabaseBuilder(context, LifeTracingDatabase::class.java)
                .addCallback(FRESH_SCHEMA_CALLBACK)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        fun builder(
            context: Context,
            name: String,
        ): Builder<LifeTracingDatabase> =
            Room
                .databaseBuilder(context, LifeTracingDatabase::class.java, name)
                .addCallback(FRESH_SCHEMA_CALLBACK)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        private val FRESH_SCHEMA_CALLBACK =
            object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    SequenceTemplateSchemaV5.drop(db)
                    ActivityExecutionSchemaV4.drop(db)
                    ActivitySnapshotSchemaV3.drop(db)
                    ActivityTemplateSchemaV2.recreate(db)
                    ActivitySnapshotSchemaV3.create(db)
                    ActivityExecutionSchemaV4.createAndSeed(db)
                    SequenceTemplateSchemaV5.create(db)
                }
            }
    }
}
