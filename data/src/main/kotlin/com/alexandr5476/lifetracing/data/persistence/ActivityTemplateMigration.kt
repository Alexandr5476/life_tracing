package com.alexandr5476.lifetracing.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ActivityTemplateSchemaV2.create(db)
        }
    }

// Historical manual DDL is immutable. Later persistence changes must add a new version-specific definition.
internal object ActivityTemplateSchemaV2 {
    fun recreate(db: SupportSQLiteDatabase) {
        listOf(
            "activity_template_tags",
            "activity_template_category_options",
            "activity_template_fields",
            "activity_template_user_state",
            "activity_template_settings",
            "activity_templates",
        ).forEach { table -> db.execSQL("DROP TABLE IF EXISTS `$table`") }
        create(db)
    }

    fun create(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
    }

    private val statements =
        listOf(
            """
            CREATE TABLE IF NOT EXISTS `activity_templates` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `short_comment` TEXT,
                `time_tracking_mode` TEXT NOT NULL, `timer_target_ms` INTEGER,
                `statistics_series_id` TEXT NOT NULL, `revision` INTEGER NOT NULL DEFAULT 1,
                `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL,
                `deleted_at_ms` INTEGER, `folder_id` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`statistics_series_id`) REFERENCES `statistics_series`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`folder_id`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK (
                    (`time_tracking_mode` = 'TIMER' AND `timer_target_ms` IS NOT NULL AND `timer_target_ms` > 0)
                    OR (`time_tracking_mode` IN ('STOPWATCH', 'NO_LIVE_TRACKING') AND `timer_target_ms` IS NULL)
                )
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_templates_deleted_name`
            ON `activity_templates` (`deleted_at_ms`, `name`)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_templates_folder_deleted`
            ON `activity_templates` (`folder_id`, `deleted_at_ms`)
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `activity_templates_series` ON `activity_templates` (`statistics_series_id`)",
            """
            CREATE TABLE IF NOT EXISTS `activity_template_settings` (
                `activity_template_id` TEXT NOT NULL,
                `show_seconds` INTEGER NOT NULL DEFAULT 1,
                `start_countdown_ms` INTEGER NOT NULL DEFAULT 0,
                `timer_zero_behavior` TEXT NOT NULL DEFAULT 'FINISH',
                `timer_end_sound` INTEGER NOT NULL DEFAULT 1,
                `timer_end_vibration` INTEGER NOT NULL DEFAULT 1,
                `keep_screen_awake` INTEGER NOT NULL DEFAULT 0,
                `confirm_manual_finish` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`activity_template_id`),
                FOREIGN KEY(`activity_template_id`) REFERENCES `activity_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                CHECK (`start_countdown_ms` >= 0),
                CHECK (`timer_zero_behavior` IN ('FINISH', 'OVERTIME'))
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `activity_template_user_state` (
                `activity_template_id` TEXT NOT NULL, `pinned_rank` INTEGER, `last_used_at_ms` INTEGER,
                PRIMARY KEY(`activity_template_id`),
                FOREIGN KEY(`activity_template_id`) REFERENCES `activity_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `activity_user_state_pinned` ON `activity_template_user_state` (`pinned_rank`)",
            """
            CREATE INDEX IF NOT EXISTS `activity_user_state_recent`
            ON `activity_template_user_state` (`last_used_at_ms` DESC)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `activity_template_fields` (
                `id` TEXT NOT NULL, `activity_template_id` TEXT NOT NULL, `position` INTEGER NOT NULL,
                `name` TEXT NOT NULL, `field_type` TEXT NOT NULL, `unit` TEXT, `display_precision` INTEGER,
                `default_number_scaled` INTEGER, `default_category_option_id` TEXT, `default_text` TEXT,
                `is_main_value` INTEGER NOT NULL DEFAULT 0, `created_at_ms` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL, `deleted_at_ms` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`activity_template_id`) REFERENCES `activity_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_template_fields_owner_active_position`
            ON `activity_template_fields` (`activity_template_id`, `deleted_at_ms`, `position`)
            """.trimIndent(),
            """
            CREATE UNIQUE INDEX IF NOT EXISTS `idx_activity_template_one_main_field`
            ON `activity_template_fields` (`activity_template_id`)
            WHERE `is_main_value` = 1 AND `deleted_at_ms` IS NULL
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `activity_template_category_options` (
                `id` TEXT NOT NULL, `activity_template_field_id` TEXT NOT NULL,
                `position` INTEGER NOT NULL, `label` TEXT NOT NULL, `is_archived` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`activity_template_field_id`) REFERENCES `activity_template_fields`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS `activity_template_category_options_field`
            ON `activity_template_category_options` (`activity_template_field_id`)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `activity_template_tags` (
                `activity_template_id` TEXT NOT NULL, `tag_id` TEXT NOT NULL,
                PRIMARY KEY(`activity_template_id`, `tag_id`),
                FOREIGN KEY(`activity_template_id`) REFERENCES `activity_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `activity_template_tags_tag` ON `activity_template_tags` (`tag_id`)",
        )
}
