package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.room.testing.MigrationTestHelper
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
class ActivitySnapshotMigrationTest {
    private val databaseNames =
        listOf(
            "activity-snapshot-migration",
            "activity-snapshot-historical-v3",
            "activity-snapshot-fresh-v3",
            "activity-snapshot-parity-migrated",
        )
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun removeOldDatabases() = databaseNames.forEach(context::deleteDatabase)

    @After
    fun cleanUp() = databaseNames.forEach(context::deleteDatabase)

    @Test
    fun migrationFrom2To3PreservesEveryV2TableAndAcceptsCompleteSnapshot() {
        LifeTracingMigrationTestDatabaseFactory.createVersion2(helper, databaseNames[0]).apply {
            execSQL("INSERT INTO folders VALUES ('folder', 'Folder', NULL, 10, 20)")
            execSQL("INSERT INTO tags VALUES ('tag', 'Tag', 10, 20)")
            execSQL("INSERT INTO statistics_series VALUES ('series', 'ACTIVITY', 'Series', 10, NULL)")
            execSQL(
                """
                INSERT INTO activity_templates VALUES (
                    'template', 'Template', 'Comment', 'STOPWATCH', NULL, 'series', 4,
                    10, 20, NULL, 'folder'
                )
                """.trimIndent(),
            )
            execSQL("INSERT INTO activity_template_settings (activity_template_id) VALUES ('template')")
            execSQL("INSERT INTO activity_template_user_state VALUES ('template', 2, 30)")
            execSQL(
                """
                INSERT INTO activity_template_fields VALUES (
                    'field', 'template', 0, 'Category', 'CATEGORY', NULL, NULL,
                    NULL, 'option', NULL, 0, 10, 20, NULL
                )
                """.trimIndent(),
            )
            execSQL("INSERT INTO activity_template_category_options VALUES ('option', 'field', 0, 'Option', 0)")
            execSQL("INSERT INTO activity_template_tags VALUES ('template', 'tag')")
            close()
        }

        helper.runMigrationsAndValidate(databaseNames[0], 3, true, MIGRATION_2_3).use { migrated ->
            listOf(
                "folders",
                "tags",
                "statistics_series",
                "activity_templates",
                "activity_template_settings",
                "activity_template_user_state",
                "activity_template_fields",
                "activity_template_category_options",
                "activity_template_tags",
            ).forEach { table -> assertEquals("v2 row lost from $table", 1, migrated.rowCount(table)) }
            assertEquals(EXPECTED_ACTIVITY_TEMPLATE_MANUAL_SCHEMA, migrated.readActivityTemplateManualSchema())
            assertEquals(EXPECTED_ACTIVITY_SNAPSHOT_MANUAL_SCHEMA, migrated.readActivitySnapshotManualSchema())

            migrated.execSQL(
                """
                INSERT INTO activity_snapshots VALUES (
                    'snapshot', 'Snapshot', 'Frozen', 'TIMER', 1000, 'template', 4, 'series', 0, 40
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                "INSERT INTO activity_snapshot_settings (snapshot_id, timer_zero_behavior) " +
                    "VALUES ('snapshot', 'OVERTIME')",
            )
            migrated.execSQL(
                """
                INSERT INTO activity_snapshot_fields VALUES (
                    'snapshot-field', 'snapshot', 'field', 0, 'Category', NULL, 'CATEGORY',
                    NULL, NULL, NULL, 'snapshot-option', NULL, 0
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO activity_snapshot_category_options VALUES (
                    'snapshot-option', 'snapshot-field', 'option', 0, 'Option', NULL
                )
                """.trimIndent(),
            )
            migrated
                .query(
                    """
                    SELECT snapshots.name, settings.timer_zero_behavior, fields.name_at_creation,
                           options.label_at_creation
                    FROM activity_snapshots AS snapshots
                    JOIN activity_snapshot_settings AS settings ON settings.snapshot_id = snapshots.id
                    JOIN activity_snapshot_fields AS fields ON fields.snapshot_id = snapshots.id
                    JOIN activity_snapshot_category_options AS options ON options.snapshot_field_id = fields.id
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Snapshot", cursor.getString(0))
                    assertEquals("OVERTIME", cursor.getString(1))
                    assertEquals("Category", cursor.getString(2))
                    assertEquals("Option", cursor.getString(3))
                }
        }
    }

    @Test
    fun historicalVersion3MaterializesBothFrozenManualSchemas() {
        LifeTracingMigrationTestDatabaseFactory.createVersion3(helper, databaseNames[1]).use { historical ->
            assertEquals(EXPECTED_ACTIVITY_TEMPLATE_MANUAL_SCHEMA, historical.readActivityTemplateManualSchema())
            assertEquals(EXPECTED_ACTIVITY_SNAPSHOT_MANUAL_SCHEMA, historical.readActivitySnapshotManualSchema())
        }
    }

    @Test
    fun freshMigratedAndHistoricalV3HaveEquivalentManagedSnapshotSchema() {
        val freshDatabase =
            LifeTracingDatabase
                .builder(context, databaseNames[2])
                .allowMainThreadQueries()
                .build()
        val freshSnapshotSchema = freshDatabase.openHelper.readableDatabase.readActivitySnapshotManualSchema()
        val freshTemplateSchema = freshDatabase.openHelper.readableDatabase.readActivityTemplateManualSchema()
        freshDatabase.close()

        LifeTracingMigrationTestDatabaseFactory.createVersion2(helper, databaseNames[3]).close()
        val migratedSchemas =
            helper.runMigrationsAndValidate(databaseNames[3], 3, true, MIGRATION_2_3).use { migrated ->
                migrated.readActivitySnapshotManualSchema() to migrated.readActivityTemplateManualSchema()
            }
        val historicalSchemas =
            LifeTracingMigrationTestDatabaseFactory.createVersion3(helper, databaseNames[1]).use { historical ->
                historical.readActivitySnapshotManualSchema() to historical.readActivityTemplateManualSchema()
            }

        assertEquals(freshSnapshotSchema, migratedSchemas.first)
        assertEquals(freshSnapshotSchema, historicalSchemas.first)
        assertEquals(freshTemplateSchema, migratedSchemas.second)
        assertEquals(freshTemplateSchema, historicalSchemas.second)
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
