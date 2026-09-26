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
class HistoricalPlanFulfillmentMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = listOf("plan-v11-migrated", "plan-v11-fresh")

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun cleanBefore() = names.forEach(context::deleteDatabase)

    @After
    fun cleanAfter() = names.forEach(context::deleteDatabase)

    @Test
    fun exactV10ToV11PreservesPlanExecutionRuntimeAndHistoryFacts() {
        val original = LifeTracingMigrationTestDatabaseFactory.createVersion10(helper, names[0])
        original.seedLinkedPlans()
        val before = original.linkedFacts()
        val originalIndexes = original.indexes("plan_entries")
        val originalPlanForeignKeys = original.foreignKeys("plan_entries")
        original.close()

        helper.runMigrationsAndValidate(names[0], 11, true, MIGRATION_10_11).use { migrated ->
            assertEquals(before, migrated.linkedFacts())
            assertEquals(originalIndexes, migrated.indexes("plan_entries"))
            assertEquals(originalPlanForeignKeys, migrated.foreignKeys("plan_entries"))
            assertEquals(
                listOf("context_type", "status", "deleted_at_ms", "primary_local_date"),
                migrated.indexColumns("activity_executions_history_latest_root"),
            )
            assertEquals(
                listOf("status", "primary_local_date"),
                migrated.indexColumns("sequence_executions_status_primary_date"),
            )
            assertTrue(migrated.foreignKeys("plan_entries").containsKey("fulfilled_activity_execution_id"))
            assertEquals("plan_entries:SET NULL", migrated.foreignKeys("activity_executions")["plan_entry_id"])
            assertEquals("plan_entries:SET NULL", migrated.foreignKeys("sequence_executions")["plan_entry_id"])
            migrated.assertForeignKeysClean()
            assertV11Chronology(migrated)
        }
    }

    @Test
    fun freshV11HasTheSameNarrowChronologyRules() {
        val fresh = LifeTracingDatabase.builder(context, names[1]).allowMainThreadQueries().build()
        fresh.openHelper.writableDatabase.use { db ->
            db.seedLinkedPlans()
            assertV11Chronology(db)
            db.assertForeignKeysClean()
        }
        fresh.close()
    }

    private fun assertV11Chronology(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO plan_entries (id, trackable_kind, source_activity_template_id, source_revision, " +
                "activity_snapshot_id, precision, planned_day, status, " +
                "created_at_ms, updated_at_ms, fulfilled_at_ms) " +
                "VALUES ('historical-activity', 'ACTIVITY', 'activity-source', 1, 'activity-snapshot', " +
                "'DAY', '1970-01-01', 'FULFILLED', 1000, 1200, 900)",
        )
        db.execSQL(
            "INSERT INTO activity_executions VALUES ('historical', 'activity-snapshot', 'STANDALONE', " +
                "NULL, NULL, 'historical-activity', 'activity-series', 'COMPLETED', 800, 900, 100, " +
                "'UTC', 0, '1970-01-01', 'MANUAL_HISTORY_ENTRY', NULL, 1200, 1200)",
        )
        db.execSQL(
            "UPDATE plan_entries SET fulfilled_activity_execution_id = 'historical' WHERE id = 'historical-activity'",
        )
        assertEquals(900L, db.scalarLong("SELECT fulfilled_at_ms FROM plan_entries WHERE id = 'historical-activity'"))
        assertEquals(900L, db.scalarLong("SELECT completed_at_ms FROM activity_executions WHERE id = 'historical'"))
        db.assertRejected("UPDATE plan_entries SET fulfilled_at_ms = 900 WHERE id = 'done-sequence'")
        db.assertRejected("UPDATE plan_entries SET updated_at_ms = 999 WHERE id = 'historical-activity'")
        db.assertRejected(
            "UPDATE plan_entries SET status = 'CANCELLED', cancelled_at_ms = 999 WHERE id = 'live-activity'",
        )
        assertEquals(1200L, db.scalarLong("SELECT updated_at_ms FROM plan_entries WHERE id = 'historical-activity'"))
        assertEquals("PLANNED", db.scalarText("SELECT status FROM plan_entries WHERE id = 'live-activity'"))
    }

    private fun SupportSQLiteDatabase.seedLinkedPlans() {
        execSQL("INSERT INTO statistics_series VALUES ('activity-series', 'ACTIVITY', 'Activity', 0, NULL)")
        execSQL("INSERT INTO statistics_series VALUES ('sequence-series', 'SEQUENCE', 'Sequence', 0, NULL)")
        execSQL(
            "INSERT INTO activity_templates VALUES ('activity-source', 'Activity', NULL, 'STOPWATCH', NULL, 'activity-series', 1, 100, 100, NULL, NULL)",
        )
        execSQL(
            "INSERT INTO sequence_templates VALUES ('sequence-source', 'Sequence', NULL, 'sequence-series', 1, 100, 100, NULL, NULL, 'ACTIVE')",
        )
        execSQL(
            "INSERT INTO activity_snapshots VALUES ('activity-snapshot', 'Activity', NULL, 'STOPWATCH', NULL, 'activity-source', 1, 'activity-series', 100, 100)",
        )
        execSQL(
            "INSERT INTO sequence_snapshots VALUES ('sequence-snapshot', 'Sequence', NULL, 'sequence-source', 1, 'sequence-series', 100)",
        )
        execSQL(
            "INSERT INTO activity_snapshot_fields VALUES ('field', 'activity-snapshot', NULL, 0, 'Value', NULL, 'NUMBER', NULL, 0, NULL, NULL, NULL, 0)",
        )
        execSQL(
            "INSERT INTO plan_entries (id, trackable_kind, source_activity_template_id, source_revision, activity_snapshot_id, precision, planned_day, status, created_at_ms, updated_at_ms) VALUES ('live-activity', 'ACTIVITY', 'activity-source', 1, 'activity-snapshot', 'DAY', '1970-01-01', 'PLANNED', 1000, 1100)",
        )
        execSQL(
            "INSERT INTO plan_entries (id, trackable_kind, source_activity_template_id, source_revision, activity_snapshot_id, precision, planned_day, status, created_at_ms, updated_at_ms, fulfilled_at_ms) VALUES ('done-activity', 'ACTIVITY', 'activity-source', 1, 'activity-snapshot', 'DAY', '1970-01-01', 'FULFILLED', 1000, 1200, 1150)",
        )
        execSQL(
            "INSERT INTO plan_entries (id, trackable_kind, source_sequence_template_id, source_revision, sequence_plan_snapshot_id, precision, planned_week_start, status, created_at_ms, updated_at_ms, fulfilled_at_ms) VALUES ('done-sequence', 'SEQUENCE', 'sequence-source', 1, 'sequence-snapshot', 'WEEK', '1970-01-05', 'FULFILLED', 1000, 1200, 1150)",
        )
        execSQL(
            "INSERT INTO activity_executions VALUES ('live', 'activity-snapshot', 'STANDALONE', NULL, NULL, 'live-activity', 'activity-series', 'PAUSED', 1100, NULL, NULL, 'UTC', 0, '1970-01-01', NULL, NULL, 1100, 1120)",
        )
        execSQL("INSERT INTO activity_execution_pauses VALUES ('pause', 'live', 1110, NULL)")
        execSQL(
            "INSERT INTO activity_executions VALUES ('done', 'activity-snapshot', 'STANDALONE', NULL, NULL, 'done-activity', 'activity-series', 'COMPLETED', 1050, 1150, 100, 'UTC', 0, '1970-01-01', 'MANUAL_HISTORY_ENTRY', NULL, 1200, 1200)",
        )
        execSQL(
            "INSERT INTO sequence_executions VALUES ('sequence', 'sequence-snapshot', 'done-sequence', 'sequence-series', 'COMPLETED', 1050, 1150, 100, 0, 100, 'UTC', 0, '1970-01-01', NULL, 1050, 1200)",
        )
        execSQL("INSERT INTO activity_execution_field_values VALUES ('done', 'field', 0, NULL, NULL)")
        execSQL("UPDATE plan_entries SET fulfilled_activity_execution_id = 'done' WHERE id = 'done-activity'")
        execSQL("UPDATE plan_entries SET fulfilled_sequence_execution_id = 'sequence' WHERE id = 'done-sequence'")
        execSQL("INSERT INTO active_session VALUES (1, 'ACTIVITY', 'live', NULL, 'PAUSED', 1120)")
        assertForeignKeysClean()
    }

    private fun SupportSQLiteDatabase.linkedFacts(): Map<String, List<List<String?>>> =
        listOf(
            "plan_entries",
            "activity_executions",
            "activity_execution_pauses",
            "activity_execution_field_values",
            "sequence_executions",
            "active_session",
        ).associateWith { table ->
            val orderBy =
                when (table) {
                    "active_session" -> "singleton_id"
                    "activity_execution_field_values" -> "activity_execution_id, snapshot_field_id"
                    else -> "id"
                }
            query("SELECT * FROM `$table` ORDER BY $orderBy").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            (0 until cursor.columnCount).map { column ->
                                if (cursor.isNull(column)) null else cursor.getString(column)
                            },
                        )
                    }
                }
            }
        }

    private fun SupportSQLiteDatabase.assertRejected(sql: String) {
        assertTrue("Expected CHECK rejection: $sql", runCatching { execSQL(sql) }.isFailure)
    }

    private fun SupportSQLiteDatabase.assertForeignKeysClean() {
        query("PRAGMA foreign_key_check").use { assertTrue(!it.moveToFirst()) }
    }

    private fun SupportSQLiteDatabase.indexes(table: String): Set<String> =
        query("PRAGMA index_list(`$table`)").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }

    private fun SupportSQLiteDatabase.indexColumns(name: String): List<String> =
        query("PRAGMA index_info(`$name`)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }

    private fun SupportSQLiteDatabase.foreignKeys(table: String): Map<String, String> =
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

    private fun SupportSQLiteDatabase.scalarLong(sql: String): Long =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.scalarText(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
}
