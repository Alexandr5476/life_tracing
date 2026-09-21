package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDiscoveryIndexMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = listOf("history-index-v10-migration", "history-index-v10-fresh")

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun cleanBefore() = names.forEach(context::deleteDatabase)

    @After
    fun cleanAfter() = names.forEach(context::deleteDatabase)

    @Test
    fun v9ToV10RetainsRootsAndAddsLatestDiscoveryIndexes() {
        LifeTracingMigrationTestDatabaseFactory.createVersion9(helper, names[0]).apply {
            seedRoots()
            close()
        }

        helper.runMigrationsAndValidate(names[0], 10, true, MIGRATION_9_10).use { migrated ->
            assertEquals(
                "2026-09-17",
                migrated.text("SELECT primary_local_date FROM activity_executions WHERE id = 'activity'"),
            )
            assertEquals(
                "2026-09-18",
                migrated.text("SELECT primary_local_date FROM sequence_executions WHERE id = 'sequence'"),
            )
            assertEquals(
                listOf("context_type", "status", "deleted_at_ms", "primary_local_date"),
                migrated.indexColumns("activity_executions_history_latest_root"),
            )
            assertEquals(
                listOf("status", "primary_local_date"),
                migrated.indexColumns("sequence_executions_status_primary_date"),
            )
        }
    }

    @Test
    fun freshAndMigratedV10ShareLatestDiscoveryIndexes() {
        val fresh = LifeTracingDatabase.builder(context, names[1]).allowMainThreadQueries().build()
        val freshShape = fresh.openHelper.readableDatabase.latestDiscoveryIndexShape()
        fresh.close()

        LifeTracingMigrationTestDatabaseFactory.createVersion9(helper, names[0]).close()
        val migrated =
            helper
                .runMigrationsAndValidate(names[0], 10, true, MIGRATION_9_10)
                .use { it.latestDiscoveryIndexShape() }
        assertEquals(freshShape, migrated)
    }

    private fun SupportSQLiteDatabase.seedRoots() {
        execSQL("INSERT INTO statistics_series VALUES ('activity-series', 'ACTIVITY', 'Activity', 0, NULL)")
        execSQL("INSERT INTO statistics_series VALUES ('sequence-series', 'SEQUENCE', 'Sequence', 0, NULL)")
        execSQL(
            "INSERT INTO activity_snapshots VALUES " +
                "('activity-snapshot', 'Activity', NULL, 'STOPWATCH', NULL, NULL, NULL, 'activity-series', 0, 0)",
        )
        execSQL(
            "INSERT INTO sequence_snapshots VALUES " +
                "('sequence-snapshot', 'Sequence', NULL, NULL, NULL, 'sequence-series', 0)",
        )
        execSQL(
            "INSERT INTO activity_executions VALUES " +
                "('activity', 'activity-snapshot', 'STANDALONE', NULL, NULL, NULL, 'activity-series', " +
                "'COMPLETED', 100, 200, 100, 'UTC', 0, '2026-09-17', NULL, NULL, 100, 200)",
        )
        execSQL(
            "INSERT INTO sequence_executions VALUES " +
                "('sequence', 'sequence-snapshot', NULL, 'sequence-series', 'COMPLETED', 100, 200, " +
                "100, 0, 100, 'UTC', 0, '2026-09-18', NULL, 100, 200)",
        )
    }

    private fun SupportSQLiteDatabase.latestDiscoveryIndexShape() =
        listOf(
            indexColumns("activity_executions_history_latest_root"),
            indexColumns("sequence_executions_status_primary_date"),
        )

    private fun SupportSQLiteDatabase.indexColumns(name: String): List<String> =
        query("PRAGMA index_info(`$name`)").use { cursor ->
            val columnName = cursor.getColumnIndexOrThrow("name")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(columnName))
            }
        }

    private fun SupportSQLiteDatabase.text(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
}
