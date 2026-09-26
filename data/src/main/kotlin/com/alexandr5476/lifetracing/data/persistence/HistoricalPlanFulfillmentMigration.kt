package com.alexandr5476.lifetracing.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val HISTORICAL_PLAN_FULFILLMENT_SCHEMA_VERSION = 11

internal val MIGRATION_10_11 =
    object : Migration(HISTORY_DISCOVERY_INDEX_SCHEMA_VERSION, HISTORICAL_PLAN_FULFILLMENT_SCHEMA_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PlanEntrySchemaV11.rebuild(db)
        }
    }

/** v11 changes only the Activity fulfillment chronology; v9 DDL remains a versioned fact. */
internal object PlanEntrySchemaV11 {
    private val tableSql =
        PlanEntrySchemaV9.planStatements
            .first()
            .replace(
                "CHECK (`fulfilled_at_ms` IS NULL OR `fulfilled_at_ms` >= `created_at_ms`)",
                "CHECK (`fulfilled_at_ms` IS NULL OR `trackable_kind` = 'ACTIVITY' OR " +
                    "`fulfilled_at_ms` >= `created_at_ms`)",
            ).also { check(it != PlanEntrySchemaV9.planStatements.first()) }

    fun rebuild(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TEMP TABLE v11_activity_plan_links (id TEXT PRIMARY KEY, plan_entry_id TEXT NOT NULL)")
        db.execSQL("CREATE TEMP TABLE v11_sequence_plan_links (id TEXT PRIMARY KEY, plan_entry_id TEXT NOT NULL)")
        db.execSQL(
            "INSERT INTO v11_activity_plan_links " +
                "SELECT id, plan_entry_id FROM activity_executions WHERE plan_entry_id IS NOT NULL",
        )
        db.execSQL(
            "INSERT INTO v11_sequence_plan_links " +
                "SELECT id, plan_entry_id FROM sequence_executions WHERE plan_entry_id IS NOT NULL",
        )
        db.execSQL(tableSql.replace("`plan_entries`", "`plan_entries_v11`"))
        db.execSQL("INSERT INTO `plan_entries_v11` SELECT * FROM `plan_entries`")
        db.execSQL("DROP TABLE `plan_entries`")
        db.execSQL("ALTER TABLE `plan_entries_v11` RENAME TO `plan_entries`")
        PlanEntrySchemaV9.planStatements.drop(1).forEach(db::execSQL)
        db.execSQL(
            "UPDATE activity_executions SET plan_entry_id = " +
                "(SELECT plan_entry_id FROM v11_activity_plan_links " +
                "WHERE v11_activity_plan_links.id = activity_executions.id) " +
                "WHERE id IN (SELECT id FROM v11_activity_plan_links)",
        )
        db.execSQL(
            "UPDATE sequence_executions SET plan_entry_id = " +
                "(SELECT plan_entry_id FROM v11_sequence_plan_links " +
                "WHERE v11_sequence_plan_links.id = sequence_executions.id) " +
                "WHERE id IN (SELECT id FROM v11_sequence_plan_links)",
        )
        db.execSQL("DROP TABLE v11_activity_plan_links")
        db.execSQL("DROP TABLE v11_sequence_plan_links")
    }
}
