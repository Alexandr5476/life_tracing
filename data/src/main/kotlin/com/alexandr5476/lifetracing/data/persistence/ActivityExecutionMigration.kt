package com.alexandr5476.lifetracing.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics

internal const val ACTIVITY_EXECUTION_SCHEMA_VERSION = 4

internal val MIGRATION_3_4 =
    object : Migration(ACTIVITY_SNAPSHOT_SCHEMA_VERSION, ACTIVITY_EXECUTION_SCHEMA_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ActivityExecutionSchemaV4.createAndSeed(db)
        }
    }

// Historical manual DDL is immutable. Sequence/occurrence/plan columns deliberately have no
// foreign keys until those owner tables are introduced by their own schema version.
internal object ActivityExecutionSchemaV4 {
    fun drop(db: SupportSQLiteDatabase) {
        listOf(
            "activity_execution_field_values",
            "activity_execution_pauses",
            "activity_executions",
        ).forEach { table -> db.execSQL("DROP TABLE IF EXISTS `$table`") }
    }

    fun createAndSeed(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
        db.execSQL(
            "INSERT OR IGNORE INTO statistics_series " +
                "(id, kind, display_name, created_at_ms, archived_at_ms) VALUES (?, 'ONE_OFF_BUCKET', ?, 0, NULL)",
            arrayOf(ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value, "One-off activities"),
        )
    }

    private val statements =
        listOf(
            """
            CREATE TABLE IF NOT EXISTS `activity_executions` (
                `id` TEXT NOT NULL, `snapshot_id` TEXT NOT NULL, `context_type` TEXT NOT NULL,
                `sequence_execution_id` TEXT, `sequence_occurrence_id` TEXT, `plan_entry_id` TEXT,
                `statistics_series_id` TEXT, `status` TEXT NOT NULL, `started_at_ms` INTEGER,
                `completed_at_ms` INTEGER, `active_duration_ms` INTEGER, `original_zone_id` TEXT NOT NULL,
                `original_utc_offset_minutes` INTEGER, `primary_local_date` TEXT NOT NULL,
                `completion_reason` TEXT, `deleted_at_ms` INTEGER, `created_at_ms` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`snapshot_id`) REFERENCES `activity_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`statistics_series_id`) REFERENCES `statistics_series`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK (
                    (`context_type` = 'STANDALONE' AND `sequence_execution_id` IS NULL AND `sequence_occurrence_id` IS NULL)
                    OR (`context_type` = 'SEQUENCE_CHILD' AND `sequence_execution_id` IS NOT NULL AND `sequence_occurrence_id` IS NOT NULL)
                ),
                CHECK (
                    (`status` IN ('RUNNING', 'PAUSED') AND `started_at_ms` IS NOT NULL
                        AND `completed_at_ms` IS NULL AND `active_duration_ms` IS NULL AND `completion_reason` IS NULL)
                    OR (`status` = 'COMPLETED' AND `completed_at_ms` IS NOT NULL
                        AND ((`started_at_ms` IS NULL AND `active_duration_ms` IS NULL)
                            OR (`started_at_ms` IS NOT NULL AND `active_duration_ms` IS NOT NULL
                                AND `active_duration_ms` >= 0)))
                ),
                CHECK (`original_utc_offset_minutes` IS NULL
                    OR `original_utc_offset_minutes` BETWEEN -1080 AND 1080),
                CHECK (`completion_reason` IS NULL OR `completion_reason` = 'MANUAL_HISTORY_ENTRY')
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `activity_executions_snapshot_id` ON `activity_executions` (`snapshot_id`)",
            """
            CREATE INDEX IF NOT EXISTS `activity_executions_series_completed`
            ON `activity_executions` (`statistics_series_id`, `completed_at_ms`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_executions_context_completed`
            ON `activity_executions` (`context_type`, `completed_at_ms`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_executions_sequence_execution_id`
            ON `activity_executions` (`sequence_execution_id`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_executions_sequence_occurrence_id`
            ON `activity_executions` (`sequence_occurrence_id`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_executions_plan_entry_id`
            ON `activity_executions` (`plan_entry_id`)
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `activity_executions_deleted_at` ON `activity_executions` (`deleted_at_ms`)",
            """
            CREATE INDEX IF NOT EXISTS `activity_executions_primary_date_deleted`
            ON `activity_executions` (`primary_local_date`, `deleted_at_ms`)
            """.trimIndent(),
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `idx_one_child_execution_per_occurrence`
            ON `activity_executions` (`sequence_occurrence_id`)
            WHERE `sequence_occurrence_id` IS NOT NULL
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `activity_execution_pauses` (
                `id` TEXT NOT NULL, `execution_id` TEXT NOT NULL, `started_at_ms` INTEGER NOT NULL,
                `ended_at_ms` INTEGER, PRIMARY KEY(`id`),
                FOREIGN KEY(`execution_id`) REFERENCES `activity_executions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                CHECK (`ended_at_ms` IS NULL OR `ended_at_ms` >= `started_at_ms`)
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_execution_pauses_owner_started`
            ON `activity_execution_pauses` (`execution_id`, `started_at_ms`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `activity_execution_field_values` (
                `execution_id` TEXT NOT NULL, `snapshot_field_id` TEXT NOT NULL,
                `number_value_scaled` INTEGER, `category_option_id` TEXT, `text_value` TEXT,
                PRIMARY KEY(`execution_id`, `snapshot_field_id`),
                FOREIGN KEY(`execution_id`) REFERENCES `activity_executions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`snapshot_field_id`) REFERENCES `activity_snapshot_fields`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`category_option_id`) REFERENCES `activity_snapshot_category_options`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK (
                    (`number_value_scaled` IS NOT NULL) + (`category_option_id` IS NOT NULL) + (`text_value` IS NOT NULL) = 1
                )
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_execution_values_snapshot_field_id`
            ON `activity_execution_field_values` (`snapshot_field_id`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_execution_values_number`
            ON `activity_execution_field_values` (`number_value_scaled`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_execution_values_category`
            ON `activity_execution_field_values` (`category_option_id`)
            """.trimIndent(),
        )
}
