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

    fun createVersion3(
        helper: MigrationTestHelper,
        name: String,
    ): SupportSQLiteDatabase =
        helper.createDatabase(name, 3).also { database ->
            ActivitySnapshotSchemaV3.drop(database)
            ActivityTemplateSchemaV2.recreate(database)
            ActivitySnapshotSchemaV3.create(database)
        }

    fun createVersion4(
        helper: MigrationTestHelper,
        name: String,
    ): SupportSQLiteDatabase =
        helper.createDatabase(name, 4).also { database ->
            ActivityExecutionSchemaV4.drop(database)
            ActivitySnapshotSchemaV3.drop(database)
            ActivityTemplateSchemaV2.recreate(database)
            ActivitySnapshotSchemaV3.create(database)
            ActivityExecutionSchemaV4.createAndSeed(database)
        }

    fun createVersion5(
        helper: MigrationTestHelper,
        name: String,
    ): SupportSQLiteDatabase =
        helper.createDatabase(name, 5).also { database ->
            SequenceTemplateSchemaV5.drop(database)
            ActivityExecutionSchemaV4.drop(database)
            ActivitySnapshotSchemaV3.drop(database)
            ActivityTemplateSchemaV2.recreate(database)
            ActivitySnapshotSchemaV3.create(database)
            ActivityExecutionSchemaV4.createAndSeed(database)
            SequenceTemplateSchemaV5.create(database)
        }
}

internal data class SequenceTemplateManualSchema(
    val hasNoLiveAccountingCheck: Boolean,
    val hasCountdownChecks: Boolean,
    val hasNodeShapeCheck: Boolean,
    val nodeForeignKeyDeletes: Map<String, String>,
    val mainValueIndexIsUnique: Boolean,
    val mainValueIndexColumns: List<String>,
    val mainValueIndexPredicate: String,
    val templateColumns: List<String>,
    val settingsColumns: List<String>,
    val settingsColumnDefaults: Map<String, String?>,
    val fieldColumns: List<String>,
    val optionColumns: List<String>,
    val nodeColumns: List<String>,
    val nodeOwnerIndexColumns: List<String>,
    val nodeSiblingIndexColumns: List<String>,
    val nodeSnapshotIndexColumns: List<String>,
    val overrideColumns: List<String>,
    val overridePrimaryKeyColumns: List<String>,
    val overrideForeignKeyDeletes: Map<String, String>,
    val hasOverrideCountdownCheck: Boolean,
    val hasOverrideTimerZeroBehaviorCheck: Boolean,
    val hasOverrideBooleanChecks: Boolean,
)

internal fun SupportSQLiteDatabase.readSequenceTemplateManualSchema(): SequenceTemplateManualSchema {
    val templateSql = schemaSql("table", "sequence_templates")
    val settingsSql = schemaSql("table", "sequence_template_settings")
    val nodeSql = schemaSql("table", "sequence_nodes")
    val mainIndexSql = schemaSql("index", "idx_one_sequence_template_main_field")
    val overrideSql = schemaSql("table", "sequence_step_overrides")
    return SequenceTemplateManualSchema(
        hasNoLiveAccountingCheck = templateSql.contains("no_live_time_accounting in ('active', 'pause')"),
        hasCountdownChecks =
            settingsSql.contains("check (sequence_start_countdown_ms >= 0)") &&
                settingsSql.contains("check (before_each_step_countdown_ms >= 0)"),
        hasNodeShapeCheck =
            nodeSql.contains("node_type = 'step'") &&
                nodeSql.contains("activity_snapshot_id is not null") &&
                nodeSql.contains("node_type = 'repeat'") &&
                nodeSql.contains("repeat_count > 0") &&
                nodeSql.contains("parent_repeat_node_id is null"),
        nodeForeignKeyDeletes = foreignKeyDeletes("sequence_nodes"),
        mainValueIndexIsUnique = indexIsUnique("sequence_template_fields", "idx_one_sequence_template_main_field"),
        mainValueIndexColumns = indexColumns("idx_one_sequence_template_main_field"),
        mainValueIndexPredicate = mainIndexSql.substringAfter(" where ", "").trim(),
        templateColumns = tableColumns("sequence_templates"),
        settingsColumns = tableColumns("sequence_template_settings"),
        settingsColumnDefaults =
            tableColumnDefaults(
                "sequence_template_settings",
            ).filterKeys(SEQUENCE_SETTING_COLUMNS::contains),
        fieldColumns = tableColumns("sequence_template_fields"),
        optionColumns = tableColumns("sequence_template_category_options"),
        nodeColumns = tableColumns("sequence_nodes"),
        nodeOwnerIndexColumns = indexColumns("sequence_nodes_sequence"),
        nodeSiblingIndexColumns = indexColumns("sequence_nodes_parent_position"),
        nodeSnapshotIndexColumns = indexColumns("sequence_nodes_activity_snapshot_id"),
        overrideColumns = tableColumns("sequence_step_overrides"),
        overridePrimaryKeyColumns = tablePrimaryKeyColumns("sequence_step_overrides"),
        overrideForeignKeyDeletes = foreignKeyDeletes("sequence_step_overrides"),
        hasOverrideCountdownCheck =
            overrideSql.contains("start_countdown_ms is null or start_countdown_ms >= 0"),
        hasOverrideTimerZeroBehaviorCheck =
            overrideSql.contains(
                "timer_zero_behavior is null or timer_zero_behavior in ('finish', 'overtime')",
            ),
        hasOverrideBooleanChecks =
            listOf("timer_end_sound", "timer_end_vibration", "keep_screen_awake").all { column ->
                overrideSql.contains("$column is null or $column in (0, 1)")
            },
    )
}

internal data class ActivityExecutionManualSchema(
    val executionColumns: List<String>,
    val pauseColumns: List<String>,
    val valueColumns: List<String>,
    val hasContextCheck: Boolean,
    val hasStatusCheck: Boolean,
    val hasTypedValueCheck: Boolean,
    val occurrenceIndexIsUnique: Boolean,
    val occurrenceIndexPredicate: String,
)

internal fun SupportSQLiteDatabase.readActivityExecutionManualSchema(): ActivityExecutionManualSchema {
    val executionSql = schemaSql("table", "activity_executions")
    val valueSql = schemaSql("table", "activity_execution_field_values")
    val occurrenceIndexSql = schemaSql("index", "idx_one_child_execution_per_occurrence")
    return ActivityExecutionManualSchema(
        executionColumns = tableColumns("activity_executions"),
        pauseColumns = tableColumns("activity_execution_pauses"),
        valueColumns = tableColumns("activity_execution_field_values"),
        hasContextCheck =
            executionSql.contains("context_type = 'standalone'") &&
                executionSql.contains("context_type = 'sequence_child'"),
        hasStatusCheck =
            executionSql.contains("status in ('running', 'paused')") && executionSql.contains("status = 'completed'"),
        hasTypedValueCheck =
            valueSql.contains("number_scaled is not null") &&
                valueSql.contains("category_option_id is not null") &&
                valueSql.contains("text_value is not null"),
        occurrenceIndexIsUnique =
            indexIsUnique("activity_executions", "idx_one_child_execution_per_occurrence"),
        occurrenceIndexPredicate = occurrenceIndexSql.substringAfter(" where ", "").trim(),
    )
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

internal data class ActivitySnapshotManualSchema(
    val hasTimerCheck: Boolean,
    val hasCountdownCheck: Boolean,
    val hasTimerZeroBehaviorCheck: Boolean,
    val mainValueIndexIsUnique: Boolean,
    val mainValueIndexColumns: List<String>,
    val mainValueIndexPredicate: String,
    val snapshotColumns: List<String>,
    val settingsColumns: List<String>,
    val settingsColumnDefaults: Map<String, String?>,
    val fieldColumns: List<String>,
    val optionColumns: List<String>,
)

internal val EXPECTED_ACTIVITY_SNAPSHOT_MANUAL_SCHEMA =
    ActivitySnapshotManualSchema(
        hasTimerCheck = true,
        hasCountdownCheck = true,
        hasTimerZeroBehaviorCheck = true,
        mainValueIndexIsUnique = true,
        mainValueIndexColumns = listOf("snapshot_id"),
        mainValueIndexPredicate = "is_main_value = 1",
        snapshotColumns =
            listOf(
                "id",
                "name",
                "short_comment",
                "time_tracking_mode",
                "timer_target_ms",
                "source_template_id",
                "source_revision",
                "statistics_series_id",
                "locally_modified",
                "created_at_ms",
            ),
        settingsColumns =
            listOf(
                "snapshot_id",
                "show_seconds",
                "start_countdown_ms",
                "timer_zero_behavior",
                "timer_end_sound",
                "timer_end_vibration",
                "keep_screen_awake",
                "confirm_manual_finish",
            ),
        settingsColumnDefaults =
            listOf(
                "show_seconds",
                "start_countdown_ms",
                "timer_zero_behavior",
                "timer_end_sound",
                "timer_end_vibration",
                "keep_screen_awake",
                "confirm_manual_finish",
            ).associateWith { null },
        fieldColumns =
            listOf(
                "id",
                "snapshot_id",
                "source_field_id",
                "position",
                "name_at_creation",
                "local_name_override",
                "field_type",
                "unit",
                "display_precision",
                "default_number_scaled",
                "default_category_option_id",
                "default_text",
                "is_main_value",
            ),
        optionColumns =
            listOf(
                "id",
                "snapshot_field_id",
                "source_option_id",
                "position",
                "label_at_creation",
                "local_label_override",
            ),
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
        mainValueIndexIsUnique = indexIsUnique("activity_template_fields", "idx_activity_template_one_main_field"),
        mainValueIndexColumns = indexColumns("idx_activity_template_one_main_field"),
        mainValueIndexPredicate = indexSql.substringAfter(" where ", missingDelimiterValue = "").trim(),
    )
}

internal fun SupportSQLiteDatabase.readActivitySnapshotManualSchema(): ActivitySnapshotManualSchema {
    val snapshotSql = schemaSql("table", "activity_snapshots")
    val settingsSql = schemaSql("table", "activity_snapshot_settings")
    val indexSql = schemaSql("index", "idx_one_activity_main_snapshot_field")
    return ActivitySnapshotManualSchema(
        hasTimerCheck =
            snapshotSql.contains("check (") &&
                snapshotSql.contains("time_tracking_mode = 'timer'") &&
                snapshotSql.contains("timer_target_ms is not null") &&
                snapshotSql.contains("timer_target_ms > 0") &&
                snapshotSql.contains("time_tracking_mode in ('stopwatch', 'no_live_tracking')") &&
                snapshotSql.contains("timer_target_ms is null"),
        hasCountdownCheck = settingsSql.contains("check (start_countdown_ms >= 0)"),
        hasTimerZeroBehaviorCheck =
            settingsSql.contains("check (timer_zero_behavior in ('finish', 'overtime'))"),
        mainValueIndexIsUnique =
            indexIsUnique("activity_snapshot_fields", "idx_one_activity_main_snapshot_field"),
        mainValueIndexColumns = indexColumns("idx_one_activity_main_snapshot_field"),
        mainValueIndexPredicate = indexSql.substringAfter(" where ", missingDelimiterValue = "").trim(),
        snapshotColumns = tableColumns("activity_snapshots"),
        settingsColumns = tableColumns("activity_snapshot_settings"),
        settingsColumnDefaults =
            tableColumnDefaults("activity_snapshot_settings").filterKeys(SNAPSHOT_SETTING_COLUMNS::contains),
        fieldColumns = tableColumns("activity_snapshot_fields"),
        optionColumns = tableColumns("activity_snapshot_category_options"),
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

private fun SupportSQLiteDatabase.indexIsUnique(
    table: String,
    name: String,
): Boolean =
    query("PRAGMA index_list(`$table`)").use { cursor ->
        val nameColumn = cursor.getColumnIndexOrThrow("name")
        val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameColumn) == name) return@use cursor.getInt(uniqueColumn) == 1
        }
        false
    }

private fun SupportSQLiteDatabase.tableColumns(table: String): List<String> =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val columnName = cursor.getColumnIndexOrThrow("name")
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(columnName))
        }
    }

private fun SupportSQLiteDatabase.tableColumnDefaults(table: String): Map<String, String?> =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val columnName = cursor.getColumnIndexOrThrow("name")
        val defaultValue = cursor.getColumnIndexOrThrow("dflt_value")
        buildMap {
            while (cursor.moveToNext()) {
                put(cursor.getString(columnName), cursor.getString(defaultValue))
            }
        }
    }

private fun SupportSQLiteDatabase.tablePrimaryKeyColumns(table: String): List<String> =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val columnName = cursor.getColumnIndexOrThrow("name")
        val primaryKeyPosition = cursor.getColumnIndexOrThrow("pk")
        buildList {
            val columns = mutableListOf<Pair<Int, String>>()
            while (cursor.moveToNext()) {
                val position = cursor.getInt(primaryKeyPosition)
                if (position > 0) columns += position to cursor.getString(columnName)
            }
            addAll(columns.sortedBy(Pair<Int, String>::first).map(Pair<Int, String>::second))
        }
    }

private fun SupportSQLiteDatabase.indexColumns(name: String): List<String> =
    query("PRAGMA index_info(`$name`)").use { cursor ->
        val columnName = cursor.getColumnIndexOrThrow("name")
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(columnName))
        }
    }

private fun SupportSQLiteDatabase.foreignKeyDeletes(table: String): Map<String, String> =
    query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
        val fromColumn = cursor.getColumnIndexOrThrow("from")
        val onDeleteColumn = cursor.getColumnIndexOrThrow("on_delete")
        buildMap {
            while (cursor.moveToNext()) put(cursor.getString(fromColumn), cursor.getString(onDeleteColumn))
        }
    }

private fun String.normalizedSql(): String =
    lowercase(Locale.ROOT)
        .replace("`", "")
        .replace(Regex("\\s+"), " ")
        .trim()

private val SNAPSHOT_SETTING_COLUMNS =
    setOf(
        "show_seconds",
        "start_countdown_ms",
        "timer_zero_behavior",
        "timer_end_sound",
        "timer_end_vibration",
        "keep_screen_awake",
        "confirm_manual_finish",
    )

private val SEQUENCE_SETTING_COLUMNS =
    setOf(
        "auto_advance",
        "sequence_start_countdown_ms",
        "before_each_step_countdown_ms",
        "transition_sound",
        "transition_vibration",
        "keep_screen_awake",
        "confirm_jump",
        "confirm_early_end",
    )
