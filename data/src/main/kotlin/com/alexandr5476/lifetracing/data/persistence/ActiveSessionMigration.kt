@file:Suppress("MaxLineLength") // The frozen singleton DDL is kept literal for migration review.

package com.alexandr5476.lifetracing.data.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val ACTIVE_SESSION_SCHEMA_VERSION = 8

internal val MIGRATION_7_8 =
    object : Migration(SEQUENCE_EXECUTION_SCHEMA_VERSION, ACTIVE_SESSION_SCHEMA_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) = ActiveSessionSchemaV8.create(db)
    }

internal object ActiveSessionSchemaV8 {
    fun drop(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `active_session`")
    }

    fun create(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `active_session` (
                `singleton_id` INTEGER NOT NULL,
                `session_kind` TEXT NOT NULL,
                `activity_execution_id` TEXT,
                `sequence_execution_id` TEXT,
                `state` TEXT NOT NULL,
                `updated_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`singleton_id`),
                FOREIGN KEY(`activity_execution_id`) REFERENCES `activity_executions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`sequence_execution_id`) REFERENCES `sequence_executions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                CHECK (`singleton_id` = 1),
                CHECK (`session_kind` IN ('ACTIVITY', 'SEQUENCE')),
                CHECK (`state` IN ('RUNNING', 'PAUSED', 'WAITING_NEXT')),
                CHECK (
                    (`session_kind` = 'ACTIVITY' AND `activity_execution_id` IS NOT NULL
                        AND `sequence_execution_id` IS NULL AND `state` IN ('RUNNING', 'PAUSED'))
                    OR (`session_kind` = 'SEQUENCE' AND `activity_execution_id` IS NULL
                        AND `sequence_execution_id` IS NOT NULL
                        AND `state` IN ('RUNNING', 'PAUSED', 'WAITING_NEXT'))
                )
            )
            """.trimIndent(),
        )
    }
}
