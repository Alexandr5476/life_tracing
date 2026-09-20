package com.alexandr5476.lifetracing.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val HISTORY_DISCOVERY_INDEX_SCHEMA_VERSION = 10

internal val MIGRATION_9_10 =
    object : Migration(PLAN_ENTRY_SCHEMA_VERSION, HISTORY_DISCOVERY_INDEX_SCHEMA_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) = HistoryDiscoveryIndexSchemaV10.create(db)
    }

internal object HistoryDiscoveryIndexSchemaV10 {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX `activity_executions_history_latest_root` ON `activity_executions` " +
                "(`context_type`, `status`, `deleted_at_ms`, `primary_local_date`)",
        )
        db.execSQL(
            "CREATE INDEX `sequence_executions_status_primary_date` ON `sequence_executions` " +
                "(`status`, `primary_local_date`)",
        )
    }
}
