package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityExecutionMigrationTest {
    private val names = listOf("execution-migrated", "execution-fresh", "execution-historical")
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun removeOldDatabases() = names.forEach(context::deleteDatabase)

    @After
    fun cleanUp() = names.forEach(context::deleteDatabase)

    @Test
    fun migrationFrom3To4PreservesEveryV3TableSeedsBucketAndAcceptsExecutionAggregate() {
        LifeTracingMigrationTestDatabaseFactory.createVersion3(helper, names[0]).apply {
            insertRepresentativeV3Rows()
            close()
        }

        helper.runMigrationsAndValidate(names[0], 4, true, MIGRATION_3_4).use { migrated ->
            V3_TABLES.forEach { table -> assertEquals("v3 row lost from $table", 1, migrated.rowCount(table)) }
            assertEquals(
                1,
                migrated.rowCount(
                    "statistics_series",
                    "id = '${ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value}'",
                ),
            )
            assertEquals(1, migrated.rowCount("statistics_series", "id = 'series'"))
            migrated.insertExecutionAggregate()
            migrated
                .query(
                    """
                    SELECT executions.status, pauses.ended_at_ms, field_values.category_option_id
                    FROM activity_executions AS executions
                    JOIN activity_execution_pauses AS pauses ON pauses.activity_execution_id = executions.id
                    JOIN activity_execution_field_values AS field_values
                        ON field_values.activity_execution_id = executions.id
                    WHERE executions.id = 'execution'
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("COMPLETED", cursor.getString(0))
                    assertEquals(50L, cursor.getLong(1))
                    assertEquals("snapshot-option", cursor.getString(2))
                }
        }
    }

    @Test
    fun freshMigratedAndHistoricalVersion4HaveEquivalentManualSchemasAndSeed() {
        val freshDatabase = LifeTracingDatabase.builder(context, names[1]).allowMainThreadQueries().build()
        val freshSchema = freshDatabase.openHelper.readableDatabase.readActivityExecutionManualSchema()
        val freshSnapshotSchema = freshDatabase.openHelper.readableDatabase.readActivitySnapshotManualSchema()
        val freshTemplateSchema = freshDatabase.openHelper.readableDatabase.readActivityTemplateManualSchema()
        assertEquals(
            1,
            freshDatabase.openHelper.readableDatabase.rowCount(
                "statistics_series",
                "id = '${ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value}'",
            ),
        )
        freshDatabase.close()

        LifeTracingMigrationTestDatabaseFactory.createVersion3(helper, names[0]).close()
        val migratedSchemas =
            helper.runMigrationsAndValidate(names[0], 4, true, MIGRATION_3_4).use { migrated ->
                Triple(
                    migrated.readActivityExecutionManualSchema(),
                    migrated.readActivitySnapshotManualSchema(),
                    migrated.readActivityTemplateManualSchema(),
                )
            }
        val historicalSchemas =
            LifeTracingMigrationTestDatabaseFactory.createVersion4(helper, names[2]).use { historical ->
                assertEquals(
                    1,
                    historical.rowCount(
                        "statistics_series",
                        "id = '${ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value}'",
                    ),
                )
                Triple(
                    historical.readActivityExecutionManualSchema(),
                    historical.readActivitySnapshotManualSchema(),
                    historical.readActivityTemplateManualSchema(),
                )
            }

        assertEquals(freshSchema, migratedSchemas.first)
        assertEquals(freshSchema, historicalSchemas.first)
        assertEquals(freshSnapshotSchema, migratedSchemas.second)
        assertEquals(freshSnapshotSchema, historicalSchemas.second)
        assertEquals(freshTemplateSchema, migratedSchemas.third)
        assertEquals(freshTemplateSchema, historicalSchemas.third)
    }

    private fun SupportSQLiteDatabase.insertRepresentativeV3Rows() {
        execSQL("INSERT INTO folders VALUES ('folder', 'Folder', NULL, 10, 20)")
        execSQL("INSERT INTO tags VALUES ('tag', 'Tag', 10, 20)")
        execSQL("INSERT INTO statistics_series VALUES ('series', 'ACTIVITY', 'Series', 10, NULL)")
        execSQL(
            """
            INSERT INTO activity_templates VALUES (
                'template', 'Template', NULL, 'STOPWATCH', NULL, 'series', 1, 10, 20, NULL, 'folder'
            )
            """.trimIndent(),
        )
        execSQL("INSERT INTO activity_template_settings (activity_template_id) VALUES ('template')")
        execSQL("INSERT INTO activity_template_user_state VALUES ('template', 1, 20)")
        execSQL(
            """
            INSERT INTO activity_template_fields VALUES (
                'field', 'template', 0, 'Category', 'CATEGORY', NULL, NULL, NULL,
                'option', NULL, 0, 10, 20, NULL
            )
            """.trimIndent(),
        )
        execSQL("INSERT INTO activity_template_category_options VALUES ('option', 'field', 0, 'Option', 0)")
        execSQL("INSERT INTO activity_template_tags VALUES ('template', 'tag')")
        execSQL(
            """
            INSERT INTO activity_snapshots VALUES (
                'snapshot', 'Snapshot', NULL, 'STOPWATCH', NULL, 'template', 1, 'series', 0, 30
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO activity_snapshot_settings (
                snapshot_id, show_seconds, start_countdown_ms, timer_zero_behavior,
                timer_end_sound, timer_end_vibration, keep_screen_awake, confirm_manual_finish
            ) VALUES ('snapshot', 1, 0, 'FINISH', 1, 1, 0, 0)
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO activity_snapshot_fields VALUES (
                'snapshot-field', 'snapshot', 'field', 0, 'Category', NULL, 'CATEGORY',
                NULL, NULL, NULL, 'snapshot-option', NULL, 0
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO activity_snapshot_category_options VALUES (
                'snapshot-option', 'snapshot-field', 'option', 0, 'Option', NULL
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertExecutionAggregate() {
        execSQL(
            """
            INSERT INTO activity_executions VALUES (
                'execution', 'snapshot', 'STANDALONE', NULL, NULL, NULL, 'series', 'COMPLETED',
                10, 100, 50, 'UTC', 0, '2026-08-15', NULL, NULL, 10, 100
            )
            """.trimIndent(),
        )
        execSQL("INSERT INTO activity_execution_pauses VALUES ('pause', 'execution', 20, 50)")
        execSQL(
            """
            INSERT INTO activity_execution_field_values VALUES (
                'execution', 'snapshot-field', NULL, 'snapshot-option', NULL
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.rowCount(
        table: String,
        where: String = "1",
    ): Int =
        query("SELECT COUNT(*) FROM `$table` WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        val V3_TABLES =
            listOf(
                "folders",
                "tags",
                "activity_templates",
                "activity_template_settings",
                "activity_template_user_state",
                "activity_template_fields",
                "activity_template_category_options",
                "activity_template_tags",
                "activity_snapshots",
                "activity_snapshot_settings",
                "activity_snapshot_fields",
                "activity_snapshot_category_options",
            )
    }
}
