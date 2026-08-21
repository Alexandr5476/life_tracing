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

    fun createVersion6(
        helper: MigrationTestHelper,
        name: String,
    ): SupportSQLiteDatabase =
        helper.createDatabase(name, 6).also { database ->
            SequenceSnapshotSchemaV6.drop(database)
            SequenceTemplateSchemaV5.drop(database)
            ActivityExecutionSchemaV4.drop(database)
            ActivitySnapshotSchemaV3.drop(database)
            ActivityTemplateSchemaV2.recreate(database)
            ActivitySnapshotSchemaV3.create(database)
            ActivityExecutionSchemaV4.createAndSeed(database)
            SequenceTemplateSchemaV5.create(database)
            SequenceSnapshotSchemaV6.create(database)
        }

    fun createVersion7(
        helper: MigrationTestHelper,
        name: String,
    ): SupportSQLiteDatabase =
        helper.createDatabase(name, 7).also { database ->
            SequenceExecutionSchemaV7.drop(database)
            SequenceSnapshotSchemaV6.drop(database)
            SequenceTemplateSchemaV5.drop(database)
            ActivitySnapshotSchemaV3.drop(database)
            ActivityTemplateSchemaV2.recreate(database)
            ActivitySnapshotSchemaV3.create(database)
            ActivityExecutionSchemaV4.createAndSeed(database)
            SequenceTemplateSchemaV5.create(database)
            SequenceSnapshotSchemaV6.create(database)
            SequenceExecutionSchemaV7.create(database)
        }

    fun createVersion8(
        helper: MigrationTestHelper,
        name: String,
    ): SupportSQLiteDatabase =
        helper.createDatabase(name, 8).also { database ->
            ActiveSessionSchemaV8.drop(database)
            SequenceExecutionSchemaV7.drop(database)
            SequenceSnapshotSchemaV6.drop(database)
            SequenceTemplateSchemaV5.drop(database)
            ActivitySnapshotSchemaV3.drop(database)
            ActivityTemplateSchemaV2.recreate(database)
            ActivitySnapshotSchemaV3.create(database)
            ActivityExecutionSchemaV4.createAndSeed(database)
            SequenceTemplateSchemaV5.create(database)
            SequenceSnapshotSchemaV6.create(database)
            SequenceExecutionSchemaV7.create(database)
            ActiveSessionSchemaV8.create(database)
        }

    fun createVersion9(
        helper: MigrationTestHelper,
        name: String,
    ): SupportSQLiteDatabase =
        helper.createDatabase(name, 9).also { database ->
            PlanEntrySchemaV9.dropPlan(database)
            ActiveSessionSchemaV8.drop(database)
            SequenceExecutionSchemaV7.drop(database)
            SequenceSnapshotSchemaV6.drop(database)
            SequenceTemplateSchemaV5.drop(database)
            ActivitySnapshotSchemaV3.drop(database)
            ActivityTemplateSchemaV2.recreate(database)
            ActivitySnapshotSchemaV3.create(database)
            ActivityExecutionSchemaV4.createAndSeed(database)
            SequenceTemplateSchemaV5.create(database)
            SequenceSnapshotSchemaV6.create(database)
            SequenceExecutionSchemaV7.create(database)
            ActiveSessionSchemaV8.create(database)
            PlanEntrySchemaV9.migrate(database)
        }
}

internal data class SequenceExecutionManualSchema(
    val rootColumns: List<String>,
    val rootForeignKeys: Map<String, String>,
    val hasRootStatusCacheCheck: Boolean,
    val hasRootTemporalCheck: Boolean,
    val rootIndexes: Map<String, List<String>>,
    val occurrenceColumns: List<String>,
    val occurrenceForeignKeys: Map<String, String>,
    val hasOccurrenceStatusCheck: Boolean,
    val hasOccurrenceReasonCheck: Boolean,
    val hasOccurrenceBooleanChecks: Boolean,
    val currentIndexUnique: Boolean,
    val currentIndexPredicate: String,
    val occurrencePositionIndexUnique: Boolean,
    val occurrenceIndexes: Map<String, List<String>>,
    val intervalColumns: List<String>,
    val intervalForeignKeys: Map<String, String>,
    val hasIntervalKindCheck: Boolean,
    val hasIntervalTemporalCheck: Boolean,
    val intervalIndexes: Map<String, List<String>>,
    val valueColumns: List<String>,
    val valueForeignKeys: Map<String, String>,
    val hasTypedValueCheck: Boolean,
    val valueIndexes: Map<String, List<String>>,
    val activityExecutionForeignKeys: Map<String, String>,
    val childIndexUnique: Boolean,
    val childIndexPredicate: String,
)

internal fun SupportSQLiteDatabase.readSequenceExecutionManualSchema(): SequenceExecutionManualSchema {
    val rootSql = schemaSql("table", "sequence_executions")
    val occurrenceSql = schemaSql("table", "sequence_occurrences")
    val intervalSql = schemaSql("table", "sequence_intervals")
    val valueSql = schemaSql("table", "sequence_execution_field_values")
    return SequenceExecutionManualSchema(
        rootColumns = tableColumns("sequence_executions"),
        rootForeignKeys = foreignKeyTargetsAndDeletes("sequence_executions"),
        hasRootStatusCacheCheck =
            rootSql.contains("status in ('running', 'paused')") &&
                rootSql.contains("status in ('completed', 'ended_early')") &&
                rootSql.contains("current_occurrence_id is null"),
        hasRootTemporalCheck = rootSql.contains("ended_at_ms is null or ended_at_ms >= started_at_ms"),
        rootIndexes =
            listOf(
                "sequence_executions_series_ended",
                "sequence_executions_plan_entry_id",
                "sequence_executions_primary_local_date",
                "sequence_executions_snapshot_id",
                "sequence_executions_current_occurrence_id",
            ).associateWith(::indexColumns),
        occurrenceColumns = tableColumns("sequence_occurrences"),
        occurrenceForeignKeys = foreignKeyTargetsAndDeletes("sequence_occurrences"),
        hasOccurrenceStatusCheck =
            listOf("not_started", "current", "completed", "skipped", "deleted_execution").all { code ->
                occurrenceSql.contains("status = '$code'")
            },
        hasOccurrenceReasonCheck =
            listOf("natural_timer_end", "manual_finish", "advanced_to_next", "jump", "sequence_ended_early")
                .all(occurrenceSql::contains),
        hasOccurrenceBooleanChecks =
            occurrenceSql.contains("is_runtime_added in (0, 1)") &&
                occurrenceSql.contains("is_deleted_from_history in (0, 1)"),
        currentIndexUnique = indexIsUnique("sequence_occurrences", "idx_one_current_occurrence"),
        currentIndexPredicate = schemaSql("index", "idx_one_current_occurrence").substringAfter(" where ", "").trim(),
        occurrencePositionIndexUnique =
            indexIsUnique("sequence_occurrences", "sequence_occurrences_execution_position"),
        occurrenceIndexes =
            listOf(
                "sequence_occurrences_execution_position",
                "sequence_occurrences_execution_status",
                "sequence_occurrences_activity_snapshot_id",
                "sequence_occurrences_source_node_id",
            ).associateWith(::indexColumns),
        intervalColumns = tableColumns("sequence_intervals"),
        intervalForeignKeys = foreignKeyTargetsAndDeletes("sequence_intervals"),
        hasIntervalKindCheck =
            listOf("active_step", "step_pause", "explicit_pause", "implicit_idle", "transition_countdown")
                .all(intervalSql::contains),
        hasIntervalTemporalCheck = intervalSql.contains("ended_at_ms is null or ended_at_ms >= started_at_ms"),
        intervalIndexes =
            listOf(
                "sequence_intervals_execution_started",
                "sequence_intervals_execution_kind_started",
                "sequence_intervals_occurrence_id",
            ).associateWith(::indexColumns),
        valueColumns = tableColumns("sequence_execution_field_values"),
        valueForeignKeys = foreignKeyTargetsAndDeletes("sequence_execution_field_values"),
        hasTypedValueCheck =
            valueSql.contains("number_scaled is not null") &&
                valueSql.contains("category_option_id is not null") &&
                valueSql.contains("text_value is not null"),
        valueIndexes =
            listOf(
                "sequence_execution_values_snapshot_field_id",
                "sequence_execution_values_number",
                "sequence_execution_values_category",
            ).associateWith(::indexColumns),
        activityExecutionForeignKeys = foreignKeyTargetsAndDeletes("activity_executions"),
        childIndexUnique = indexIsUnique("activity_executions", "idx_one_child_execution_per_occurrence"),
        childIndexPredicate =
            schemaSql("index", "idx_one_child_execution_per_occurrence").substringAfter(" where ", "").trim(),
    )
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

internal data class SequenceSnapshotManualSchema(
    val snapshotColumns: List<String>,
    val snapshotForeignKeyDeletes: Map<String, String>,
    val snapshotSourceIndexColumns: List<String>,
    val snapshotSeriesIndexColumns: List<String>,
    val settingsColumns: List<String>,
    val settingsColumnDefaults: Map<String, String?>,
    val hasSettingsCountdownChecks: Boolean,
    val hasNoLiveAccountingCheck: Boolean,
    val fieldColumns: List<String>,
    val fieldForeignKeyDeletes: Map<String, String>,
    val fieldOwnerIndexColumns: List<String>,
    val fieldSourceIndexColumns: List<String>,
    val optionColumns: List<String>,
    val optionForeignKeyDeletes: Map<String, String>,
    val optionOwnerIndexColumns: List<String>,
    val optionSourceIndexColumns: List<String>,
    val mainValueIndexIsUnique: Boolean,
    val mainValueIndexColumns: List<String>,
    val mainValueIndexPredicate: String,
    val nodeColumns: List<String>,
    val nodeForeignKeyDeletes: Map<String, String>,
    val hasNodeShapeCheck: Boolean,
    val nodeOwnerIndexColumns: List<String>,
    val nodeParentIndexColumns: List<String>,
    val nodeActivitySnapshotIndexColumns: List<String>,
    val overrideColumns: List<String>,
    val overridePrimaryKeyColumns: List<String>,
    val overrideForeignKeyDeletes: Map<String, String>,
    val hasOverrideCountdownCheck: Boolean,
    val hasOverrideTimerZeroBehaviorCheck: Boolean,
    val hasOverrideBooleanChecks: Boolean,
)

internal fun SupportSQLiteDatabase.readSequenceSnapshotManualSchema(): SequenceSnapshotManualSchema {
    val settingsSql = schemaSql("table", "sequence_snapshot_settings")
    val mainIndexSql = schemaSql("index", "idx_one_sequence_main_snapshot_field")
    val nodeSql = schemaSql("table", "sequence_snapshot_nodes")
    val overrideSql = schemaSql("table", "sequence_snapshot_step_overrides")
    return SequenceSnapshotManualSchema(
        snapshotColumns = tableColumns("sequence_snapshots"),
        snapshotForeignKeyDeletes = foreignKeyDeletes("sequence_snapshots"),
        snapshotSourceIndexColumns = indexColumns("sequence_snapshots_source_template_id"),
        snapshotSeriesIndexColumns = indexColumns("sequence_snapshots_statistics_series_id"),
        settingsColumns = tableColumns("sequence_snapshot_settings"),
        settingsColumnDefaults = tableColumnDefaults("sequence_snapshot_settings"),
        hasSettingsCountdownChecks =
            settingsSql.contains("check (sequence_start_countdown_ms >= 0)") &&
                settingsSql.contains("check (before_each_step_countdown_ms >= 0)"),
        hasNoLiveAccountingCheck =
            settingsSql.contains("no_live_time_accounting in ('active', 'pause')"),
        fieldColumns = tableColumns("sequence_snapshot_fields"),
        fieldForeignKeyDeletes = foreignKeyDeletes("sequence_snapshot_fields"),
        fieldOwnerIndexColumns = indexColumns("sequence_snapshot_fields_owner_position"),
        fieldSourceIndexColumns = indexColumns("sequence_snapshot_fields_source_field_id"),
        optionColumns = tableColumns("sequence_snapshot_category_options"),
        optionForeignKeyDeletes = foreignKeyDeletes("sequence_snapshot_category_options"),
        optionOwnerIndexColumns = indexColumns("sequence_snapshot_options_owner_position"),
        optionSourceIndexColumns = indexColumns("sequence_snapshot_options_source_option_id"),
        mainValueIndexIsUnique =
            indexIsUnique("sequence_snapshot_fields", "idx_one_sequence_main_snapshot_field"),
        mainValueIndexColumns = indexColumns("idx_one_sequence_main_snapshot_field"),
        mainValueIndexPredicate = mainIndexSql.substringAfter(" where ", "").trim(),
        nodeColumns = tableColumns("sequence_snapshot_nodes"),
        nodeForeignKeyDeletes = foreignKeyDeletes("sequence_snapshot_nodes"),
        hasNodeShapeCheck =
            nodeSql.contains("node_type = 'step'") &&
                nodeSql.contains("activity_snapshot_id is not null") &&
                nodeSql.contains("node_type = 'repeat'") &&
                nodeSql.contains("repeat_count > 0") &&
                nodeSql.contains("parent_repeat_node_id is null"),
        nodeOwnerIndexColumns = indexColumns("sequence_snapshot_nodes_parent_position"),
        nodeParentIndexColumns = indexColumns("sequence_snapshot_nodes_parent_repeat_node_id"),
        nodeActivitySnapshotIndexColumns = indexColumns("sequence_snapshot_nodes_activity_snapshot_id"),
        overrideColumns = tableColumns("sequence_snapshot_step_overrides"),
        overridePrimaryKeyColumns = tablePrimaryKeyColumns("sequence_snapshot_step_overrides"),
        overrideForeignKeyDeletes = foreignKeyDeletes("sequence_snapshot_step_overrides"),
        hasOverrideCountdownCheck =
            overrideSql.contains("start_countdown_ms is null or start_countdown_ms >= 0"),
        hasOverrideTimerZeroBehaviorCheck =
            overrideSql.contains("timer_zero_behavior is null or timer_zero_behavior in ('finish', 'overtime')"),
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

private fun SupportSQLiteDatabase.foreignKeyTargetsAndDeletes(table: String): Map<String, String> =
    query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
        val fromColumn = cursor.getColumnIndexOrThrow("from")
        val targetTable = cursor.getColumnIndexOrThrow("table")
        val onDeleteColumn = cursor.getColumnIndexOrThrow("on_delete")
        buildMap {
            while (cursor.moveToNext()) {
                put(
                    cursor.getString(fromColumn),
                    "${cursor.getString(targetTable)}:${cursor.getString(onDeleteColumn)}",
                )
            }
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
