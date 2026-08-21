package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanEntryMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = listOf("plan-v9-activity", "plan-v9-sequence", "plan-v9-fresh", "plan-v9-history")

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun cleanBefore() = names.forEach(context::deleteDatabase)

    @After
    fun cleanAfter() = names.forEach(context::deleteDatabase)

    @Test
    fun activeStandaloneActivitySurvivesExactV8ToV9() {
        LifeTracingMigrationTestDatabaseFactory.createVersion8(helper, names[0]).apply {
            seedSnapshots()
            execSQL(
                "INSERT INTO activity_executions VALUES ('activity', 'activity-snapshot', 'STANDALONE', NULL, NULL, NULL, 'activity-series', 'PAUSED', 100, NULL, NULL, 'UTC', 0, '1970-01-01', NULL, NULL, 100, 120)",
            )
            execSQL("INSERT INTO activity_execution_pauses VALUES ('pause', 'activity', 110, NULL)")
            execSQL("INSERT INTO active_session VALUES (1, 'ACTIVITY', 'activity', NULL, 'PAUSED', 120)")
            close()
        }

        helper.runMigrationsAndValidate(names[0], 9, true, MIGRATION_8_9).use { migrated ->
            assertEquals(100L, migrated.long("SELECT started_at_ms FROM activity_executions WHERE id = 'activity'"))
            assertEquals(110L, migrated.long("SELECT started_at_ms FROM activity_execution_pauses WHERE id = 'pause'"))
            assertEquals("activity", migrated.text("SELECT activity_execution_id FROM active_session"))
            assertEquals("PAUSED", migrated.text("SELECT state FROM active_session"))
            assertEquals("plan_entries:SET NULL", migrated.foreignKey("activity_executions", "plan_entry_id"))
            assertForeignKeysClean(migrated)
        }
    }

    @Test
    fun activeSequenceAndCurrentChildSurviveExactV8ToV9() {
        LifeTracingMigrationTestDatabaseFactory.createVersion8(helper, names[1]).apply {
            seedSnapshots()
            execSQL(
                "INSERT INTO sequence_executions VALUES ('sequence', 'sequence-snapshot', NULL, 'sequence-series', 'RUNNING', 100, NULL, NULL, NULL, NULL, 'UTC', 0, '1970-01-01', NULL, 100, 130)",
            )
            execSQL(
                "INSERT INTO sequence_occurrences VALUES ('occurrence', 'sequence', 'sequence-step', 'activity-snapshot', 0, NULL, NULL, 'CURRENT', 100, NULL, NULL, 0, 0)",
            )
            execSQL("UPDATE sequence_executions SET current_occurrence_id = 'occurrence' WHERE id = 'sequence'")
            execSQL(
                "INSERT INTO sequence_intervals VALUES ('interval', 'sequence', 'ACTIVE_STEP', 100, NULL, 'occurrence')",
            )
            execSQL(
                "INSERT INTO activity_executions VALUES ('child', 'activity-snapshot', 'SEQUENCE_CHILD', 'sequence', 'occurrence', NULL, 'activity-series', 'PAUSED', 100, NULL, NULL, 'UTC', 0, '1970-01-01', NULL, NULL, 100, 120)",
            )
            execSQL("INSERT INTO activity_execution_pauses VALUES ('child-pause', 'child', 120, NULL)")
            execSQL("INSERT INTO active_session VALUES (1, 'SEQUENCE', NULL, 'sequence', 'RUNNING', 130)")
            close()
        }

        helper.runMigrationsAndValidate(names[1], 9, true, MIGRATION_8_9).use { migrated ->
            assertEquals(
                "occurrence",
                migrated.text("SELECT current_occurrence_id FROM sequence_executions WHERE id = 'sequence'"),
            )
            assertEquals(
                "sequence",
                migrated.text("SELECT sequence_execution_id FROM activity_executions WHERE id = 'child'"),
            )
            assertEquals(
                120L,
                migrated.long("SELECT started_at_ms FROM activity_execution_pauses WHERE id = 'child-pause'"),
            )
            assertEquals("sequence", migrated.text("SELECT sequence_execution_id FROM active_session"))
            assertEquals("plan_entries:SET NULL", migrated.foreignKey("sequence_executions", "plan_entry_id"))
            assertForeignKeysClean(migrated)
        }
    }

    @Test
    fun freshMigratedAndHistoricalV9SharePlanAndExecutionForeignKeys() {
        val fresh = LifeTracingDatabase.builder(context, names[2]).allowMainThreadQueries().build()
        val freshShape = fresh.openHelper.readableDatabase.shape()
        fresh.close()

        LifeTracingMigrationTestDatabaseFactory.createVersion8(helper, names[0]).close()
        val migrated = helper.runMigrationsAndValidate(names[0], 9, true, MIGRATION_8_9).use { it.shape() }
        val historical = LifeTracingMigrationTestDatabaseFactory.createVersion9(helper, names[3]).use { it.shape() }

        assertEquals(freshShape, migrated)
        assertEquals(freshShape, historical)
        assertEquals(20, freshShape.planColumns.size)
        assertEquals("activity_snapshots:RESTRICT", freshShape.planForeignKeys["activity_snapshot_id"])
        assertEquals("sequence_snapshots:RESTRICT", freshShape.planForeignKeys["sequence_plan_snapshot_id"])
        assertEquals("plan_entries:SET NULL", freshShape.activityForeignKeys["plan_entry_id"])
        assertEquals("plan_entries:SET NULL", freshShape.sequenceForeignKeys["plan_entry_id"])
    }

    private fun SupportSQLiteDatabase.seedSnapshots() {
        execSQL("INSERT INTO statistics_series VALUES ('activity-series', 'ACTIVITY', 'Activity', 0, NULL)")
        execSQL("INSERT INTO statistics_series VALUES ('sequence-series', 'SEQUENCE', 'Sequence', 0, NULL)")
        execSQL(
            "INSERT INTO activity_snapshots VALUES ('activity-snapshot', 'Activity', NULL, 'STOPWATCH', NULL, NULL, NULL, 'activity-series', 0, 0)",
        )
        execSQL("INSERT INTO activity_snapshot_settings VALUES ('activity-snapshot', 1, 0, 'FINISH', 1, 1, 0, 0)")
        execSQL(
            "INSERT INTO sequence_snapshots VALUES ('sequence-snapshot', 'Sequence', NULL, NULL, NULL, 'sequence-series', 0)",
        )
        execSQL("INSERT INTO sequence_snapshot_settings VALUES ('sequence-snapshot', 1, 0, 0, 1, 1, 0, 1, 1, 'ACTIVE')")
        execSQL(
            "INSERT INTO sequence_snapshot_nodes VALUES ('sequence-step', 'sequence-snapshot', 'STEP', NULL, 0, 'activity-snapshot', NULL)",
        )
    }

    private fun assertForeignKeysClean(database: SupportSQLiteDatabase) {
        database.query("PRAGMA foreign_key_check").use { assertTrue(!it.moveToFirst()) }
    }

    private fun SupportSQLiteDatabase.shape() =
        SchemaShape(
            columns("plan_entries"),
            foreignKeys("plan_entries"),
            foreignKeys("activity_executions"),
            foreignKeys("sequence_executions"),
            indexes("plan_entries"),
        )

    private fun SupportSQLiteDatabase.columns(table: String) =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                val index = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(index))
            }
        }

    private fun SupportSQLiteDatabase.indexes(table: String) =
        query("PRAGMA index_list(`$table`)").use { cursor ->
            buildSet {
                val index = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(index))
            }
        }

    private fun SupportSQLiteDatabase.foreignKeys(table: String) =
        query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
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

    private fun SupportSQLiteDatabase.foreignKey(
        table: String,
        column: String,
    ) = foreignKeys(table).getValue(column)

    private fun SupportSQLiteDatabase.text(sql: String) =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.long(sql: String) =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private data class SchemaShape(
        val planColumns: List<String>,
        val planForeignKeys: Map<String, String>,
        val activityForeignKeys: Map<String, String>,
        val sequenceForeignKeys: Map<String, String>,
        val planIndexes: Set<String>,
    )
}
