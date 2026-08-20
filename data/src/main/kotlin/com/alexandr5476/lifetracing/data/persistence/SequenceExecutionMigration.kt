@file:Suppress("MaxLineLength") // Historical SQL stays explicit and reviewable.

package com.alexandr5476.lifetracing.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val SEQUENCE_EXECUTION_SCHEMA_VERSION = 7

internal val MIGRATION_6_7 =
    object : Migration(SEQUENCE_SNAPSHOT_SCHEMA_VERSION, SEQUENCE_EXECUTION_SCHEMA_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) = SequenceExecutionSchemaV7.create(db)
    }

// Historical V7 DDL owns row checks, partial indexes, and the ActivityExecution FK rebuild.
internal object SequenceExecutionSchemaV7 {
    fun drop(db: SupportSQLiteDatabase) {
        listOf(
            "activity_execution_field_values",
            "activity_execution_pauses",
            "activity_executions",
            "sequence_execution_field_values",
            "sequence_intervals",
            "sequence_occurrences",
            "sequence_executions",
        ).forEach { db.execSQL("DROP TABLE IF EXISTS `$it`") }
    }

    fun create(db: SupportSQLiteDatabase) {
        sequenceStatements.forEach(db::execSQL)
        rebuildActivityExecution(db)
    }

    private fun rebuildActivityExecution(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TEMP TABLE `v7_activity_executions` AS SELECT * FROM `activity_executions`")
        db.execSQL("CREATE TEMP TABLE `v7_activity_execution_pauses` AS SELECT * FROM `activity_execution_pauses`")
        db.execSQL(
            "CREATE TEMP TABLE `v7_activity_execution_values` AS SELECT * FROM `activity_execution_field_values`",
        )
        db.execSQL("DROP TABLE `activity_execution_field_values`")
        db.execSQL("DROP TABLE `activity_execution_pauses`")
        db.execSQL("DROP TABLE `activity_executions`")
        activityExecutionStatements.forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO `activity_executions` SELECT * FROM `v7_activity_executions`",
        )
        db.execSQL(
            "INSERT INTO `activity_execution_pauses` SELECT * FROM `v7_activity_execution_pauses`",
        )
        db.execSQL(
            "INSERT INTO `activity_execution_field_values` SELECT * FROM `v7_activity_execution_values`",
        )
        db.execSQL("DROP TABLE `v7_activity_executions`")
        db.execSQL("DROP TABLE `v7_activity_execution_pauses`")
        db.execSQL("DROP TABLE `v7_activity_execution_values`")
    }

    private val sequenceStatements =
        listOf(
            """
            CREATE TABLE IF NOT EXISTS `sequence_executions` (
                `id` TEXT NOT NULL, `snapshot_id` TEXT NOT NULL, `plan_entry_id` TEXT,
                `statistics_series_id` TEXT, `status` TEXT NOT NULL, `started_at_ms` INTEGER NOT NULL,
                `ended_at_ms` INTEGER, `active_duration_ms` INTEGER, `pause_duration_ms` INTEGER,
                `wall_duration_ms` INTEGER, `original_zone_id` TEXT NOT NULL,
                `original_utc_offset_minutes` INTEGER, `primary_local_date` TEXT NOT NULL,
                `current_occurrence_id` TEXT, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`snapshot_id`) REFERENCES `sequence_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`statistics_series_id`) REFERENCES `statistics_series`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`current_occurrence_id`) REFERENCES `sequence_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                CHECK (
                    (`status` IN ('RUNNING', 'PAUSED') AND `ended_at_ms` IS NULL
                        AND `active_duration_ms` IS NULL AND `pause_duration_ms` IS NULL
                        AND `wall_duration_ms` IS NULL)
                    OR (`status` IN ('COMPLETED', 'ENDED_EARLY') AND `ended_at_ms` IS NOT NULL
                        AND `active_duration_ms` IS NOT NULL AND `active_duration_ms` >= 0
                        AND `pause_duration_ms` IS NOT NULL AND `pause_duration_ms` >= 0
                        AND `wall_duration_ms` IS NOT NULL AND `wall_duration_ms` >= 0
                        AND `current_occurrence_id` IS NULL)
                ),
                CHECK (`ended_at_ms` IS NULL OR `ended_at_ms` >= `started_at_ms`),
                CHECK (`original_utc_offset_minutes` IS NULL
                    OR `original_utc_offset_minutes` BETWEEN -1080 AND 1080)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `sequence_executions_series_ended` ON `sequence_executions` (`statistics_series_id`, `ended_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `sequence_executions_plan_entry_id` ON `sequence_executions` (`plan_entry_id`)",
            "CREATE INDEX IF NOT EXISTS `sequence_executions_primary_local_date` ON `sequence_executions` (`primary_local_date`)",
            "CREATE INDEX IF NOT EXISTS `sequence_executions_snapshot_id` ON `sequence_executions` (`snapshot_id`)",
            "CREATE INDEX IF NOT EXISTS `sequence_executions_current_occurrence_id` ON `sequence_executions` (`current_occurrence_id`)",
            """
            CREATE TABLE IF NOT EXISTS `sequence_occurrences` (
                `id` TEXT NOT NULL, `sequence_execution_id` TEXT NOT NULL,
                `source_sequence_snapshot_node_id` TEXT, `activity_snapshot_id` TEXT NOT NULL,
                `runtime_position` INTEGER NOT NULL, `repeat_source_snapshot_node_id` TEXT,
                `repeat_iteration` INTEGER, `status` TEXT NOT NULL, `entered_at_ms` INTEGER,
                `completed_at_ms` INTEGER, `completion_reason` TEXT,
                `is_runtime_added` INTEGER NOT NULL DEFAULT 0,
                `is_deleted_from_history` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sequence_execution_id`) REFERENCES `sequence_executions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`source_sequence_snapshot_node_id`) REFERENCES `sequence_snapshot_nodes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`activity_snapshot_id`) REFERENCES `activity_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK (`runtime_position` >= 0),
                CHECK (`repeat_iteration` IS NULL OR `repeat_iteration` >= 1),
                CHECK (`is_runtime_added` IN (0, 1)),
                CHECK (`is_deleted_from_history` IN (0, 1)),
                CHECK (`completion_reason` IS NULL OR `completion_reason` IN (
                    'NATURAL_TIMER_END', 'MANUAL_FINISH', 'ADVANCED_TO_NEXT', 'JUMP', 'SEQUENCE_ENDED_EARLY'
                )),
                CHECK (
                    (`status` = 'NOT_STARTED' AND `entered_at_ms` IS NULL AND `completed_at_ms` IS NULL
                        AND `completion_reason` IS NULL)
                    OR (`status` = 'CURRENT' AND `entered_at_ms` IS NOT NULL AND `completed_at_ms` IS NULL
                        AND `completion_reason` IS NULL)
                    OR (`status` = 'SKIPPED' AND `entered_at_ms` IS NULL AND `completed_at_ms` IS NULL
                        AND `completion_reason` IS NULL)
                    OR (`status` = 'COMPLETED' AND `entered_at_ms` IS NOT NULL AND `completed_at_ms` IS NOT NULL
                        AND `completed_at_ms` >= `entered_at_ms`)
                    OR `status` = 'DELETED_EXECUTION'
                )
            )
            """.trimIndent(),
            "CREATE UNIQUE INDEX IF NOT EXISTS `sequence_occurrences_execution_position` ON `sequence_occurrences` (`sequence_execution_id`, `runtime_position`)",
            "CREATE INDEX IF NOT EXISTS `sequence_occurrences_execution_status` ON `sequence_occurrences` (`sequence_execution_id`, `status`)",
            "CREATE INDEX IF NOT EXISTS `sequence_occurrences_activity_snapshot_id` ON `sequence_occurrences` (`activity_snapshot_id`)",
            "CREATE INDEX IF NOT EXISTS `sequence_occurrences_source_node_id` ON `sequence_occurrences` (`source_sequence_snapshot_node_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_one_current_occurrence` ON `sequence_occurrences` (`sequence_execution_id`) WHERE `status` = 'CURRENT'",
            """
            CREATE TABLE IF NOT EXISTS `sequence_intervals` (
                `id` TEXT NOT NULL, `sequence_execution_id` TEXT NOT NULL, `kind` TEXT NOT NULL,
                `started_at_ms` INTEGER NOT NULL, `ended_at_ms` INTEGER, `occurrence_id` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sequence_execution_id`) REFERENCES `sequence_executions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`occurrence_id`) REFERENCES `sequence_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                CHECK (`kind` IN ('ACTIVE_STEP', 'STEP_PAUSE', 'EXPLICIT_PAUSE', 'IMPLICIT_IDLE', 'TRANSITION_COUNTDOWN')),
                CHECK (`ended_at_ms` IS NULL OR `ended_at_ms` >= `started_at_ms`)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `sequence_intervals_execution_started` ON `sequence_intervals` (`sequence_execution_id`, `started_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `sequence_intervals_execution_kind_started` ON `sequence_intervals` (`sequence_execution_id`, `kind`, `started_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `sequence_intervals_occurrence_id` ON `sequence_intervals` (`occurrence_id`)",
            """
            CREATE TABLE IF NOT EXISTS `sequence_execution_field_values` (
                `sequence_execution_id` TEXT NOT NULL, `snapshot_field_id` TEXT NOT NULL,
                `number_scaled` INTEGER, `category_option_id` TEXT, `text_value` TEXT,
                PRIMARY KEY(`sequence_execution_id`, `snapshot_field_id`),
                FOREIGN KEY(`sequence_execution_id`) REFERENCES `sequence_executions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`snapshot_field_id`) REFERENCES `sequence_snapshot_fields`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`category_option_id`) REFERENCES `sequence_snapshot_category_options`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK ((`number_scaled` IS NOT NULL) + (`category_option_id` IS NOT NULL) + (`text_value` IS NOT NULL) = 1)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `sequence_execution_values_snapshot_field_id` ON `sequence_execution_field_values` (`snapshot_field_id`)",
            "CREATE INDEX IF NOT EXISTS `sequence_execution_values_number` ON `sequence_execution_field_values` (`number_scaled`)",
            "CREATE INDEX IF NOT EXISTS `sequence_execution_values_category` ON `sequence_execution_field_values` (`category_option_id`)",
        )

    private val activityExecutionStatements =
        listOf(
            """
            CREATE TABLE `activity_executions` (
                `id` TEXT NOT NULL, `snapshot_id` TEXT NOT NULL, `context_type` TEXT NOT NULL,
                `sequence_execution_id` TEXT, `sequence_occurrence_id` TEXT, `plan_entry_id` TEXT,
                `statistics_series_id` TEXT, `status` TEXT NOT NULL, `started_at_ms` INTEGER,
                `completed_at_ms` INTEGER, `active_duration_ms` INTEGER, `original_zone_id` TEXT NOT NULL,
                `original_utc_offset_minutes` INTEGER, `primary_local_date` TEXT NOT NULL,
                `completion_reason` TEXT, `deleted_at_ms` INTEGER, `created_at_ms` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`),
                FOREIGN KEY(`snapshot_id`) REFERENCES `activity_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`statistics_series_id`) REFERENCES `statistics_series`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`sequence_execution_id`) REFERENCES `sequence_executions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`sequence_occurrence_id`) REFERENCES `sequence_occurrences`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK ((`context_type` = 'STANDALONE' AND `sequence_execution_id` IS NULL AND `sequence_occurrence_id` IS NULL)
                    OR (`context_type` = 'SEQUENCE_CHILD' AND `sequence_execution_id` IS NOT NULL AND `sequence_occurrence_id` IS NOT NULL)),
                CHECK ((`status` IN ('RUNNING', 'PAUSED') AND `started_at_ms` IS NOT NULL
                        AND `completed_at_ms` IS NULL AND `active_duration_ms` IS NULL AND `completion_reason` IS NULL)
                    OR (`status` = 'COMPLETED' AND `completed_at_ms` IS NOT NULL
                        AND ((`started_at_ms` IS NULL AND `active_duration_ms` IS NULL)
                            OR (`started_at_ms` IS NOT NULL AND `active_duration_ms` IS NOT NULL
                                AND `active_duration_ms` >= 0)))),
                CHECK (`original_utc_offset_minutes` IS NULL OR `original_utc_offset_minutes` BETWEEN -1080 AND 1080),
                CHECK (`completion_reason` IS NULL OR `completion_reason` = 'MANUAL_HISTORY_ENTRY')
            )
            """.trimIndent(),
            "CREATE INDEX `activity_executions_snapshot_id` ON `activity_executions` (`snapshot_id`)",
            "CREATE INDEX `activity_executions_series_completed` ON `activity_executions` (`statistics_series_id`, `completed_at_ms`)",
            "CREATE INDEX `activity_executions_context_completed` ON `activity_executions` (`context_type`, `completed_at_ms`)",
            "CREATE INDEX `activity_executions_sequence_execution_id` ON `activity_executions` (`sequence_execution_id`)",
            "CREATE INDEX `activity_executions_sequence_occurrence_id` ON `activity_executions` (`sequence_occurrence_id`)",
            "CREATE INDEX `activity_executions_plan_entry_id` ON `activity_executions` (`plan_entry_id`)",
            "CREATE INDEX `activity_executions_deleted_at` ON `activity_executions` (`deleted_at_ms`)",
            "CREATE INDEX `activity_executions_primary_date_deleted` ON `activity_executions` (`primary_local_date`, `deleted_at_ms`)",
            "CREATE UNIQUE INDEX `idx_one_child_execution_per_occurrence` ON `activity_executions` (`sequence_occurrence_id`) WHERE `sequence_occurrence_id` IS NOT NULL",
            """
            CREATE TABLE `activity_execution_pauses` (
                `id` TEXT NOT NULL, `activity_execution_id` TEXT NOT NULL, `started_at_ms` INTEGER NOT NULL,
                `ended_at_ms` INTEGER, PRIMARY KEY(`id`),
                FOREIGN KEY(`activity_execution_id`) REFERENCES `activity_executions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                CHECK (`ended_at_ms` IS NULL OR `ended_at_ms` >= `started_at_ms`)
            )
            """.trimIndent(),
            "CREATE INDEX `activity_execution_pauses_owner_started` ON `activity_execution_pauses` (`activity_execution_id`, `started_at_ms`)",
            """
            CREATE TABLE `activity_execution_field_values` (
                `activity_execution_id` TEXT NOT NULL, `snapshot_field_id` TEXT NOT NULL,
                `number_scaled` INTEGER, `category_option_id` TEXT, `text_value` TEXT,
                PRIMARY KEY(`activity_execution_id`, `snapshot_field_id`),
                FOREIGN KEY(`activity_execution_id`) REFERENCES `activity_executions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`snapshot_field_id`) REFERENCES `activity_snapshot_fields`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`category_option_id`) REFERENCES `activity_snapshot_category_options`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK ((`number_scaled` IS NOT NULL) + (`category_option_id` IS NOT NULL) + (`text_value` IS NOT NULL) = 1)
            )
            """.trimIndent(),
            "CREATE INDEX `activity_execution_values_snapshot_field_id` ON `activity_execution_field_values` (`snapshot_field_id`)",
            "CREATE INDEX `activity_execution_values_number` ON `activity_execution_field_values` (`number_scaled`)",
            "CREATE INDEX `activity_execution_values_category` ON `activity_execution_field_values` (`category_option_id`)",
        )
}
