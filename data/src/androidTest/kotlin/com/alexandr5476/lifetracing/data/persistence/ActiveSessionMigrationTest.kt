package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveSessionMigrationTest {
    private val names = listOf("active-v8-migrated", "active-v8-fresh", "active-v8-historical")
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun removeOldDatabases() = names.forEach(context::deleteDatabase)

    @After
    fun cleanUp() = names.forEach(context::deleteDatabase)

    @Test
    fun migrationFromExactV7PreservesRepresentativeRowsAndAddsConstrainedSingleton() {
        LifeTracingMigrationTestDatabaseFactory.createVersion7(helper, names[0]).apply {
            insertRepresentativeV7Rows()
            close()
        }

        helper.runMigrationsAndValidate(names[0], 8, true, MIGRATION_7_8).use { migrated ->
            migrated.execSQL("PRAGMA foreign_keys = ON")
            REPRESENTATIVE_ROWS.forEach { (table, where) ->
                assertEquals("v7 row lost from $table", 1, migrated.count(table, where))
            }
            migrated.execSQL(
                "INSERT INTO active_session VALUES (1, 'ACTIVITY', 'live-activity', NULL, 'RUNNING', 100)",
            )
            assertEquals("ACTIVITY", migrated.text("SELECT session_kind FROM active_session WHERE singleton_id = 1"))
            migrated.execSQL("DELETE FROM active_session")
            migrated.execSQL(
                "INSERT INTO active_session VALUES (1, 'SEQUENCE', NULL, 'live-sequence', 'WAITING_NEXT', 100)",
            )
            assertEquals("WAITING_NEXT", migrated.text("SELECT state FROM active_session WHERE singleton_id = 1"))
            assertThrows(SQLiteConstraintException::class.java) {
                migrated.execSQL(
                    "INSERT INTO active_session VALUES (2, 'SEQUENCE', NULL, 'live-sequence', 'RUNNING', 100)",
                )
            }
        }
    }

    @Test
    fun freshMigratedAndHistoricalV8HaveEquivalentActiveSessionSemantics() {
        val fresh = LifeTracingDatabase.builder(context, names[1]).allowMainThreadQueries().build()
        val freshSchema = fresh.openHelper.readableDatabase.activeSessionSchema()
        fresh.close()

        LifeTracingMigrationTestDatabaseFactory.createVersion7(helper, names[0]).close()
        val migrated =
            helper.runMigrationsAndValidate(names[0], 8, true, MIGRATION_7_8).use {
                it.activeSessionSchema()
            }
        val historical =
            LifeTracingMigrationTestDatabaseFactory.createVersion8(helper, names[2]).use {
                it.activeSessionSchema()
            }

        assertEquals(freshSchema, migrated)
        assertEquals(freshSchema, historical)
        assertEquals(
            listOf(
                ActiveColumn("singleton_id", "INTEGER", true, null, 1),
                ActiveColumn("session_kind", "TEXT", true, null, 0),
                ActiveColumn("activity_execution_id", "TEXT", false, null, 0),
                ActiveColumn("sequence_execution_id", "TEXT", false, null, 0),
                ActiveColumn("state", "TEXT", true, null, 0),
                ActiveColumn("updated_at_ms", "INTEGER", true, null, 0),
            ),
            freshSchema.columns,
        )
        assertEquals("activity_executions:RESTRICT", freshSchema.foreignKeys["activity_execution_id"])
        assertEquals("sequence_executions:RESTRICT", freshSchema.foreignKeys["sequence_execution_id"])
        assertTrue(freshSchema.singletonCheck)
        assertTrue(freshSchema.kindCheck)
        assertTrue(freshSchema.stateCheck)
        assertTrue(freshSchema.ownershipCheck)
        assertFalse(freshSchema.hasDefaults)
    }

    private fun SupportSQLiteDatabase.insertRepresentativeV7Rows() {
        execSQL("PRAGMA foreign_keys = ON")
        execSQL("INSERT INTO statistics_series VALUES ('activity-series', 'ACTIVITY', 'Activity', 0, NULL)")
        execSQL("INSERT INTO statistics_series VALUES ('sequence-series', 'SEQUENCE', 'Sequence', 0, NULL)")
        execSQL(
            "INSERT INTO activity_templates VALUES ('activity-template', 'Activity', NULL, 'STOPWATCH', NULL, 'activity-series', 1, 0, 0, NULL, NULL)",
        )
        execSQL("INSERT INTO activity_template_settings (activity_template_id) VALUES ('activity-template')")
        execSQL(
            "INSERT INTO activity_snapshots VALUES ('activity-snapshot', 'Activity', NULL, 'STOPWATCH', NULL, 'activity-template', 1, 'activity-series', 0, 0)",
        )
        execSQL("INSERT INTO activity_snapshot_settings VALUES ('activity-snapshot', 1, 0, 'FINISH', 1, 1, 0, 0)")
        execSQL(
            "INSERT INTO activity_snapshot_fields VALUES ('activity-field', 'activity-snapshot', NULL, 0, 'Value', NULL, 'NUMBER', NULL, 0, 1000, NULL, NULL, 1)",
        )
        execSQL(
            "INSERT INTO activity_executions VALUES ('standalone', 'activity-snapshot', 'STANDALONE', NULL, NULL, NULL, 'activity-series', 'COMPLETED', 0, 60, 60, 'UTC', 0, '1970-01-01', NULL, NULL, 0, 60)",
        )
        execSQL("INSERT INTO activity_execution_field_values VALUES ('standalone', 'activity-field', 2000, NULL, NULL)")
        execSQL(
            "INSERT INTO activity_executions VALUES ('live-activity', 'activity-snapshot', 'STANDALONE', NULL, NULL, NULL, 'activity-series', 'RUNNING', 100, NULL, NULL, 'UTC', 0, '1970-01-01', NULL, NULL, 100, 100)",
        )
        execSQL(
            "INSERT INTO sequence_templates VALUES ('sequence-template', 'Sequence', NULL, 'sequence-series', 1, 0, 0, NULL, NULL, 'ACTIVE')",
        )
        execSQL("INSERT INTO sequence_template_settings (sequence_template_id) VALUES ('sequence-template')")
        execSQL(
            "INSERT INTO sequence_nodes VALUES ('sequence-node', 'sequence-template', 'STEP', NULL, 0, 'activity-snapshot', NULL)",
        )
        execSQL(
            "INSERT INTO sequence_snapshots VALUES ('sequence-snapshot', 'Sequence', NULL, 'sequence-template', 1, 'sequence-series', 0)",
        )
        execSQL("INSERT INTO sequence_snapshot_settings VALUES ('sequence-snapshot', 1, 0, 0, 1, 1, 0, 1, 1, 'ACTIVE')")
        execSQL(
            "INSERT INTO sequence_snapshot_fields VALUES ('sequence-field', 'sequence-snapshot', NULL, 0, 'Value', NULL, 'NUMBER', NULL, 0, 1000, NULL, NULL, 1)",
        )
        execSQL(
            "INSERT INTO sequence_snapshot_nodes VALUES ('sequence-step', 'sequence-snapshot', 'STEP', NULL, 0, 'activity-snapshot', NULL)",
        )
        execSQL(
            "INSERT INTO sequence_executions VALUES ('sequence-execution', 'sequence-snapshot', NULL, 'sequence-series', 'COMPLETED', 0, 60, 60, 0, 60, 'UTC', 0, '1970-01-01', NULL, 0, 60)",
        )
        execSQL(
            "INSERT INTO sequence_occurrences VALUES ('occurrence', 'sequence-execution', 'sequence-step', 'activity-snapshot', 0, NULL, NULL, 'COMPLETED', 0, 60, 'MANUAL_FINISH', 0, 0)",
        )
        execSQL(
            "INSERT INTO sequence_intervals VALUES ('interval', 'sequence-execution', 'ACTIVE_STEP', 0, 60, 'occurrence')",
        )
        execSQL(
            "INSERT INTO sequence_execution_field_values VALUES ('sequence-execution', 'sequence-field', 3000, NULL, NULL)",
        )
        execSQL(
            "INSERT INTO activity_executions VALUES ('child', 'activity-snapshot', 'SEQUENCE_CHILD', 'sequence-execution', 'occurrence', NULL, 'activity-series', 'COMPLETED', 0, 60, 60, 'UTC', 0, '1970-01-01', NULL, NULL, 0, 60)",
        )
        execSQL(
            "INSERT INTO sequence_executions VALUES ('live-sequence', 'sequence-snapshot', NULL, 'sequence-series', 'RUNNING', 100, NULL, NULL, NULL, NULL, 'UTC', 0, '1970-01-01', NULL, 100, 100)",
        )
    }

    private fun SupportSQLiteDatabase.activeSessionSchema(): ActiveSchema {
        val columns =
            query("PRAGMA table_info(`active_session`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            ActiveColumn(
                                cursor.getString(cursor.getColumnIndexOrThrow("name")),
                                cursor.getString(cursor.getColumnIndexOrThrow("type")),
                                cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                                cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                                cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                            ),
                        )
                    }
                }
            }
        val foreignKeys =
            query("PRAGMA foreign_key_list(`active_session`)").use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        put(
                            cursor.getString(cursor.getColumnIndexOrThrow("from")),
                            cursor.getString(cursor.getColumnIndexOrThrow("table")) + ":" +
                                cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
                        )
                    }
                }
            }
        val sql = text("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'active_session'").normalizeSql()
        return ActiveSchema(
            columns,
            foreignKeys,
            "check (`singleton_id` = 1)" in sql,
            "`session_kind` in ('activity', 'sequence')" in sql,
            "`state` in ('running', 'paused', 'waiting_next')" in sql,
            "`session_kind` = 'activity'" in sql && "`session_kind` = 'sequence'" in sql,
            columns.any { it.defaultValue != null },
        )
    }

    private fun SupportSQLiteDatabase.count(
        table: String,
        where: String,
    ): Int =
        query("SELECT COUNT(*) FROM `$table` WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.text(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun String.normalizeSql(): String = lowercase().replace(Regex("\\s+"), " ")

    private data class ActiveColumn(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int,
    )

    private data class ActiveSchema(
        val columns: List<ActiveColumn>,
        val foreignKeys: Map<String, String>,
        val singletonCheck: Boolean,
        val kindCheck: Boolean,
        val stateCheck: Boolean,
        val ownershipCheck: Boolean,
        val hasDefaults: Boolean,
    )

    private companion object {
        val REPRESENTATIVE_ROWS =
            listOf(
                "activity_templates" to "id = 'activity-template'",
                "activity_snapshots" to "id = 'activity-snapshot'",
                "activity_executions" to "id = 'standalone'",
                "activity_execution_field_values" to "activity_execution_id = 'standalone'",
                "sequence_templates" to "id = 'sequence-template'",
                "sequence_snapshots" to "id = 'sequence-snapshot'",
                "sequence_executions" to "id = 'sequence-execution'",
                "sequence_occurrences" to "id = 'occurrence'",
                "sequence_intervals" to "id = 'interval'",
                "sequence_execution_field_values" to "sequence_execution_id = 'sequence-execution'",
                "activity_executions" to "id = 'child'",
            )
    }
}
