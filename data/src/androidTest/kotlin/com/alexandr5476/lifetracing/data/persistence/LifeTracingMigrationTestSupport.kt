package com.alexandr5476.lifetracing.data.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale

/**
 * Room JSON is authoritative for Room-managed objects. Historical versions with LifeTracing-managed
 * CHECK constraints or partial indexes must also apply their manual schema before serving as a migration source.
 */
internal object LifeTracingMigrationTestDatabaseFactory {
    fun createVersion2(
        helper: MigrationTestHelper,
        name: String,
    ): SupportSQLiteDatabase = helper.createDatabase(name, 2).also(ActivityTemplateSchemaV2::recreate)
}

internal data class ActivityTemplateManualSchema(
    val hasTimerCheck: Boolean,
    val hasCountdownCheck: Boolean,
    val hasTimerZeroBehaviorCheck: Boolean,
    val mainValueIndexIsUnique: Boolean,
    val mainValueIndexColumns: List<String>,
    val mainValueIndexPredicate: String,
)

internal val EXPECTED_ACTIVITY_TEMPLATE_MANUAL_SCHEMA =
    ActivityTemplateManualSchema(
        hasTimerCheck = true,
        hasCountdownCheck = true,
        hasTimerZeroBehaviorCheck = true,
        mainValueIndexIsUnique = true,
        mainValueIndexColumns = listOf("activity_template_id"),
        mainValueIndexPredicate = "is_main_value = 1 and deleted_at_ms is null",
    )

internal fun SupportSQLiteDatabase.readActivityTemplateManualSchema(): ActivityTemplateManualSchema {
    val templateSql = schemaSql("table", "activity_templates")
    val settingsSql = schemaSql("table", "activity_template_settings")
    val indexSql = schemaSql("index", "idx_activity_template_one_main_field")

    return ActivityTemplateManualSchema(
        hasTimerCheck =
            templateSql.contains("check (") &&
                templateSql.contains("time_tracking_mode = 'timer'") &&
                templateSql.contains("timer_target_ms is not null") &&
                templateSql.contains("timer_target_ms > 0") &&
                templateSql.contains("time_tracking_mode in ('stopwatch', 'no_live_tracking')") &&
                templateSql.contains("timer_target_ms is null"),
        hasCountdownCheck = settingsSql.contains("check (start_countdown_ms >= 0)"),
        hasTimerZeroBehaviorCheck =
            settingsSql.contains("check (timer_zero_behavior in ('finish', 'overtime'))"),
        mainValueIndexIsUnique = indexIsUnique("idx_activity_template_one_main_field"),
        mainValueIndexColumns = indexColumns("idx_activity_template_one_main_field"),
        mainValueIndexPredicate = indexSql.substringAfter(" where ", missingDelimiterValue = "").trim(),
    )
}

private fun SupportSQLiteDatabase.schemaSql(
    type: String,
    name: String,
): String =
    query("SELECT sql FROM sqlite_master WHERE type = ? AND name = ?", arrayOf(type, name)).use { cursor ->
        check(cursor.moveToFirst()) { "Missing $type $name" }
        cursor.getString(0).normalizedSql()
    }

private fun SupportSQLiteDatabase.indexIsUnique(name: String): Boolean =
    query("PRAGMA index_list(`activity_template_fields`)").use { cursor ->
        val nameColumn = cursor.getColumnIndexOrThrow("name")
        val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameColumn) == name) return@use cursor.getInt(uniqueColumn) == 1
        }
        false
    }

private fun SupportSQLiteDatabase.indexColumns(name: String): List<String> =
    query("PRAGMA index_info(`$name`)").use { cursor ->
        val columnName = cursor.getColumnIndexOrThrow("name")
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(columnName))
        }
    }

private fun String.normalizedSql(): String =
    lowercase(Locale.ROOT)
        .replace("`", "")
        .replace(Regex("\\s+"), " ")
        .trim()
