@file:Suppress("MaxLineLength") // Frozen SQL is kept close to the documented table shape.

package com.alexandr5476.lifetracing.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val SEQUENCE_SNAPSHOT_SCHEMA_VERSION = 6

internal val MIGRATION_5_6 =
    object : Migration(SEQUENCE_TEMPLATE_SCHEMA_VERSION, SEQUENCE_SNAPSHOT_SCHEMA_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) = SequenceSnapshotSchemaV6.create(db)
    }

// Historical V6 manual DDL owns checks and the partial Main Value index.
internal object SequenceSnapshotSchemaV6 {
    fun drop(db: SupportSQLiteDatabase) {
        listOf(
            "sequence_snapshot_step_overrides",
            "sequence_snapshot_nodes",
            "sequence_snapshot_category_options",
            "sequence_snapshot_fields",
            "sequence_snapshot_settings",
            "sequence_snapshots",
        ).forEach { db.execSQL("DROP TABLE IF EXISTS `$it`") }
    }

    fun create(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)

    private val statements =
        listOf(
            """
            CREATE TABLE IF NOT EXISTS `sequence_snapshots` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `short_comment` TEXT,
                `source_template_id` TEXT, `source_revision` INTEGER, `statistics_series_id` TEXT,
                `created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`),
                FOREIGN KEY(`source_template_id`) REFERENCES `sequence_templates`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(`statistics_series_id`) REFERENCES `statistics_series`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `sequence_snapshots_source_template_id` ON `sequence_snapshots` (`source_template_id`)",
            "CREATE INDEX IF NOT EXISTS `sequence_snapshots_statistics_series_id` ON `sequence_snapshots` (`statistics_series_id`)",
            """
            CREATE TABLE IF NOT EXISTS `sequence_snapshot_settings` (
                `sequence_snapshot_id` TEXT NOT NULL, `auto_advance` INTEGER NOT NULL,
                `sequence_start_countdown_ms` INTEGER NOT NULL, `before_each_step_countdown_ms` INTEGER NOT NULL,
                `transition_sound` INTEGER NOT NULL, `transition_vibration` INTEGER NOT NULL,
                `keep_screen_awake` INTEGER NOT NULL, `confirm_jump` INTEGER NOT NULL,
                `confirm_early_end` INTEGER NOT NULL, `no_live_time_accounting` TEXT NOT NULL,
                PRIMARY KEY(`sequence_snapshot_id`),
                FOREIGN KEY(`sequence_snapshot_id`) REFERENCES `sequence_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                CHECK (`sequence_start_countdown_ms` >= 0),
                CHECK (`before_each_step_countdown_ms` >= 0),
                CHECK (`no_live_time_accounting` IN ('ACTIVE', 'PAUSE'))
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sequence_snapshot_fields` (
                `id` TEXT NOT NULL, `sequence_snapshot_id` TEXT NOT NULL, `source_field_id` TEXT,
                `position` INTEGER NOT NULL, `name_at_creation` TEXT NOT NULL, `local_name_override` TEXT,
                `field_type` TEXT NOT NULL, `unit` TEXT, `display_precision` INTEGER,
                `default_number_scaled` INTEGER, `default_category_option_id` TEXT, `default_text` TEXT,
                `is_main_value` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`),
                FOREIGN KEY(`sequence_snapshot_id`) REFERENCES `sequence_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`source_field_id`) REFERENCES `sequence_template_fields`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `sequence_snapshot_fields_owner_position` ON `sequence_snapshot_fields` (`sequence_snapshot_id`, `position`)",
            "CREATE INDEX IF NOT EXISTS `sequence_snapshot_fields_source_field_id` ON `sequence_snapshot_fields` (`source_field_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_one_sequence_main_snapshot_field` ON `sequence_snapshot_fields` (`sequence_snapshot_id`) WHERE `is_main_value` = 1",
            """
            CREATE TABLE IF NOT EXISTS `sequence_snapshot_category_options` (
                `id` TEXT NOT NULL, `sequence_snapshot_field_id` TEXT NOT NULL, `source_option_id` TEXT,
                `position` INTEGER NOT NULL, `label_at_creation` TEXT NOT NULL, `local_label_override` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sequence_snapshot_field_id`) REFERENCES `sequence_snapshot_fields`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`source_option_id`) REFERENCES `sequence_template_category_options`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `sequence_snapshot_options_owner_position` ON `sequence_snapshot_category_options` (`sequence_snapshot_field_id`, `position`)",
            "CREATE INDEX IF NOT EXISTS `sequence_snapshot_options_source_option_id` ON `sequence_snapshot_category_options` (`source_option_id`)",
            """
            CREATE TABLE IF NOT EXISTS `sequence_snapshot_nodes` (
                `id` TEXT NOT NULL, `sequence_snapshot_id` TEXT NOT NULL, `node_type` TEXT NOT NULL,
                `parent_repeat_node_id` TEXT, `position` INTEGER NOT NULL, `activity_snapshot_id` TEXT,
                `repeat_count` INTEGER, PRIMARY KEY(`id`),
                FOREIGN KEY(`sequence_snapshot_id`) REFERENCES `sequence_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`parent_repeat_node_id`) REFERENCES `sequence_snapshot_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`activity_snapshot_id`) REFERENCES `activity_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK ((`node_type` = 'STEP' AND `activity_snapshot_id` IS NOT NULL AND `repeat_count` IS NULL)
                    OR (`node_type` = 'REPEAT' AND `activity_snapshot_id` IS NULL AND `repeat_count` IS NOT NULL
                        AND `repeat_count` > 0 AND `parent_repeat_node_id` IS NULL))
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `sequence_snapshot_nodes_parent_position` ON `sequence_snapshot_nodes` (`sequence_snapshot_id`, `parent_repeat_node_id`, `position`)",
            "CREATE INDEX IF NOT EXISTS `sequence_snapshot_nodes_parent_repeat_node_id` ON `sequence_snapshot_nodes` (`parent_repeat_node_id`)",
            "CREATE INDEX IF NOT EXISTS `sequence_snapshot_nodes_activity_snapshot_id` ON `sequence_snapshot_nodes` (`activity_snapshot_id`)",
            """
            CREATE TABLE IF NOT EXISTS `sequence_snapshot_step_overrides` (
                `sequence_snapshot_node_id` TEXT NOT NULL, `start_countdown_ms` INTEGER,
                `timer_zero_behavior` TEXT, `timer_end_sound` INTEGER, `timer_end_vibration` INTEGER,
                `keep_screen_awake` INTEGER, PRIMARY KEY(`sequence_snapshot_node_id`),
                FOREIGN KEY(`sequence_snapshot_node_id`) REFERENCES `sequence_snapshot_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                CHECK (`start_countdown_ms` IS NULL OR `start_countdown_ms` >= 0),
                CHECK (`timer_zero_behavior` IS NULL OR `timer_zero_behavior` IN ('FINISH', 'OVERTIME')),
                CHECK (`timer_end_sound` IS NULL OR `timer_end_sound` IN (0, 1)),
                CHECK (`timer_end_vibration` IS NULL OR `timer_end_vibration` IN (0, 1)),
                CHECK (`keep_screen_awake` IS NULL OR `keep_screen_awake` IN (0, 1))
            )
            """.trimIndent(),
        )
}
