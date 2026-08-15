package com.alexandr5476.lifetracing.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val ACTIVITY_SNAPSHOT_SCHEMA_VERSION = 3

internal val MIGRATION_2_3 =
    object : Migration(2, ACTIVITY_SNAPSHOT_SCHEMA_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ActivitySnapshotSchemaV3.create(db)
        }
    }

// Historical manual DDL is immutable. Later snapshot changes require a new version-specific definition.
internal object ActivitySnapshotSchemaV3 {
    fun recreate(db: SupportSQLiteDatabase) {
        drop(db)
        create(db)
    }

    fun drop(db: SupportSQLiteDatabase) {
        listOf(
            "activity_snapshot_category_options",
            "activity_snapshot_fields",
            "activity_snapshot_settings",
            "activity_snapshots",
        ).forEach { table -> db.execSQL("DROP TABLE IF EXISTS `$table`") }
    }

    fun create(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
    }

    private val statements =
        listOf(
            """
            CREATE TABLE IF NOT EXISTS `activity_snapshots` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `short_comment` TEXT,
                `time_tracking_mode` TEXT NOT NULL, `timer_target_ms` INTEGER,
                `source_template_id` TEXT, `source_revision` INTEGER,
                `statistics_series_id` TEXT, `locally_modified` INTEGER NOT NULL DEFAULT 0,
                `created_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`source_template_id`) REFERENCES `activity_templates`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`statistics_series_id`) REFERENCES `statistics_series`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK (
                    (`time_tracking_mode` = 'TIMER' AND `timer_target_ms` IS NOT NULL AND `timer_target_ms` > 0)
                    OR (`time_tracking_mode` IN ('STOPWATCH', 'NO_LIVE_TRACKING') AND `timer_target_ms` IS NULL)
                )
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_snapshots_source_template_id`
            ON `activity_snapshots` (`source_template_id`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_snapshots_statistics_series_id`
            ON `activity_snapshots` (`statistics_series_id`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `activity_snapshot_settings` (
                `snapshot_id` TEXT NOT NULL,
                `show_seconds` INTEGER NOT NULL,
                `start_countdown_ms` INTEGER NOT NULL,
                `timer_zero_behavior` TEXT NOT NULL,
                `timer_end_sound` INTEGER NOT NULL,
                `timer_end_vibration` INTEGER NOT NULL,
                `keep_screen_awake` INTEGER NOT NULL,
                `confirm_manual_finish` INTEGER NOT NULL,
                PRIMARY KEY(`snapshot_id`),
                FOREIGN KEY(`snapshot_id`) REFERENCES `activity_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                CHECK (`start_countdown_ms` >= 0),
                CHECK (`timer_zero_behavior` IN ('FINISH', 'OVERTIME'))
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `activity_snapshot_fields` (
                `id` TEXT NOT NULL, `snapshot_id` TEXT NOT NULL, `source_field_id` TEXT,
                `position` INTEGER NOT NULL, `name_at_creation` TEXT NOT NULL,
                `local_name_override` TEXT, `field_type` TEXT NOT NULL, `unit` TEXT,
                `display_precision` INTEGER, `default_number_scaled` INTEGER,
                `default_category_option_id` TEXT, `default_text` TEXT,
                `is_main_value` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`snapshot_id`) REFERENCES `activity_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`source_field_id`) REFERENCES `activity_template_fields`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_snapshot_fields_owner_position`
            ON `activity_snapshot_fields` (`snapshot_id`, `position`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_snapshot_fields_source_field_id`
            ON `activity_snapshot_fields` (`source_field_id`)
            """.trimIndent(),
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `idx_one_activity_main_snapshot_field`
            ON `activity_snapshot_fields` (`snapshot_id`)
            WHERE `is_main_value` = 1
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `activity_snapshot_category_options` (
                `id` TEXT NOT NULL, `snapshot_field_id` TEXT NOT NULL, `source_option_id` TEXT,
                `position` INTEGER NOT NULL, `label_at_creation` TEXT NOT NULL,
                `local_label_override` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`snapshot_field_id`) REFERENCES `activity_snapshot_fields`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`source_option_id`) REFERENCES `activity_template_category_options`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_snapshot_options_owner_position`
            ON `activity_snapshot_category_options` (`snapshot_field_id`, `position`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_snapshot_options_source_option_id`
            ON `activity_snapshot_category_options` (`source_option_id`)
            """.trimIndent(),
        )
}
