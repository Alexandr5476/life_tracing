package com.alexandr5476.lifetracing.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val SEQUENCE_TEMPLATE_SCHEMA_VERSION = 5

internal val MIGRATION_4_5 =
    object : Migration(ACTIVITY_EXECUTION_SCHEMA_VERSION, SEQUENCE_TEMPLATE_SCHEMA_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SequenceTemplateSchemaV5.create(db)
        }
    }

// Historical V5 manual DDL owns CHECK constraints and the partial Main Value index.
internal object SequenceTemplateSchemaV5 {
    fun drop(db: SupportSQLiteDatabase) {
        listOf(
            "sequence_step_overrides",
            "sequence_nodes",
            "sequence_template_tags",
            "sequence_template_category_options",
            "sequence_template_fields",
            "sequence_template_user_state",
            "sequence_template_settings",
            "sequence_templates",
        ).forEach { table -> db.execSQL("DROP TABLE IF EXISTS `$table`") }
    }

    fun create(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
    }

    private val statements =
        listOf(
            """
            CREATE TABLE IF NOT EXISTS `sequence_templates` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `short_comment` TEXT,
                `statistics_series_id` TEXT NOT NULL, `revision` INTEGER NOT NULL DEFAULT 1,
                `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL,
                `deleted_at_ms` INTEGER, `folder_id` TEXT,
                `no_live_time_accounting` TEXT NOT NULL DEFAULT 'ACTIVE',
                PRIMARY KEY(`id`),
                FOREIGN KEY(`statistics_series_id`) REFERENCES `statistics_series`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`folder_id`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK (`no_live_time_accounting` IN ('ACTIVE', 'PAUSE'))
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_templates_deleted_name`
            ON `sequence_templates` (`deleted_at_ms`, `name`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_templates_folder_deleted`
            ON `sequence_templates` (`folder_id`, `deleted_at_ms`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_templates_series`
            ON `sequence_templates` (`statistics_series_id`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sequence_template_settings` (
                `sequence_template_id` TEXT NOT NULL,
                `auto_advance` INTEGER NOT NULL DEFAULT 1,
                `sequence_start_countdown_ms` INTEGER NOT NULL DEFAULT 0,
                `before_each_step_countdown_ms` INTEGER NOT NULL DEFAULT 0,
                `transition_sound` INTEGER NOT NULL DEFAULT 1,
                `transition_vibration` INTEGER NOT NULL DEFAULT 1,
                `keep_screen_awake` INTEGER NOT NULL DEFAULT 0,
                `confirm_jump` INTEGER NOT NULL DEFAULT 1,
                `confirm_early_end` INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(`sequence_template_id`),
                FOREIGN KEY(`sequence_template_id`) REFERENCES `sequence_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                CHECK (`sequence_start_countdown_ms` >= 0),
                CHECK (`before_each_step_countdown_ms` >= 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sequence_template_user_state` (
                `sequence_template_id` TEXT NOT NULL, `pinned_rank` INTEGER, `last_used_at_ms` INTEGER,
                PRIMARY KEY(`sequence_template_id`),
                FOREIGN KEY(`sequence_template_id`) REFERENCES `sequence_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_user_state_pinned`
            ON `sequence_template_user_state` (`pinned_rank`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_user_state_recent`
            ON `sequence_template_user_state` (`last_used_at_ms` DESC)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sequence_template_fields` (
                `id` TEXT NOT NULL, `sequence_template_id` TEXT NOT NULL, `position` INTEGER NOT NULL,
                `name` TEXT NOT NULL, `field_type` TEXT NOT NULL, `unit` TEXT, `display_precision` INTEGER,
                `default_number_scaled` INTEGER, `default_category_option_id` TEXT, `default_text` TEXT,
                `is_main_value` INTEGER NOT NULL DEFAULT 0, `created_at_ms` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL, `deleted_at_ms` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sequence_template_id`) REFERENCES `sequence_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_template_fields_owner_active_position`
            ON `sequence_template_fields` (`sequence_template_id`, `deleted_at_ms`, `position`)
            """.trimIndent(),
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `idx_one_sequence_template_main_field`
            ON `sequence_template_fields` (`sequence_template_id`)
            WHERE `is_main_value` = 1 AND `deleted_at_ms` IS NULL
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sequence_template_category_options` (
                `id` TEXT NOT NULL, `sequence_template_field_id` TEXT NOT NULL,
                `position` INTEGER NOT NULL, `label` TEXT NOT NULL, `is_archived` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sequence_template_field_id`) REFERENCES `sequence_template_fields`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_template_category_options_field`
            ON `sequence_template_category_options` (`sequence_template_field_id`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sequence_template_tags` (
                `sequence_template_id` TEXT NOT NULL, `tag_id` TEXT NOT NULL,
                PRIMARY KEY(`sequence_template_id`, `tag_id`),
                FOREIGN KEY(`sequence_template_id`) REFERENCES `sequence_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_template_tags_tag`
            ON `sequence_template_tags` (`tag_id`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sequence_nodes` (
                `id` TEXT NOT NULL, `sequence_template_id` TEXT NOT NULL, `node_type` TEXT NOT NULL,
                `parent_repeat_node_id` TEXT, `position` INTEGER NOT NULL,
                `activity_snapshot_id` TEXT, `repeat_count` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`sequence_template_id`) REFERENCES `sequence_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`parent_repeat_node_id`) REFERENCES `sequence_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`activity_snapshot_id`) REFERENCES `activity_snapshots`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK (
                    (`node_type` = 'STEP' AND `activity_snapshot_id` IS NOT NULL AND `repeat_count` IS NULL)
                    OR (`node_type` = 'REPEAT' AND `activity_snapshot_id` IS NULL AND `repeat_count` IS NOT NULL
                        AND `repeat_count` > 0 AND `parent_repeat_node_id` IS NULL)
                )
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_nodes_sequence`
            ON `sequence_nodes` (`sequence_template_id`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_nodes_parent_position`
            ON `sequence_nodes` (`sequence_template_id`, `parent_repeat_node_id`, `position`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_nodes_parent_repeat_node_id`
            ON `sequence_nodes` (`parent_repeat_node_id`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `sequence_nodes_activity_snapshot_id`
            ON `sequence_nodes` (`activity_snapshot_id`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sequence_step_overrides` (
                `sequence_node_id` TEXT NOT NULL,
                `start_countdown_ms` INTEGER,
                `timer_zero_behavior` TEXT,
                `timer_end_sound` INTEGER,
                `timer_end_vibration` INTEGER,
                `keep_screen_awake` INTEGER,
                PRIMARY KEY(`sequence_node_id`),
                FOREIGN KEY(`sequence_node_id`) REFERENCES `sequence_nodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                CHECK (`start_countdown_ms` IS NULL OR `start_countdown_ms` >= 0),
                CHECK (`timer_zero_behavior` IS NULL OR `timer_zero_behavior` IN ('FINISH', 'OVERTIME')),
                CHECK (`timer_end_sound` IS NULL OR `timer_end_sound` IN (0, 1)),
                CHECK (`timer_end_vibration` IS NULL OR `timer_end_vibration` IN (0, 1)),
                CHECK (`keep_screen_awake` IS NULL OR `keep_screen_awake` IN (0, 1))
            )
            """.trimIndent(),
        )
}
