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
class ActivityTemplateMigrationTest {
    private val databaseName = "activity-template-migration"
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun removeOldDatabase() {
        context.deleteDatabase(databaseName)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFrom1To2PreservesRowsValidatesSchemaAndAcceptsNewAggregate() {
        helper.createDatabase(databaseName, 1).apply {
            execSQL("INSERT INTO folders VALUES ('folder', 'Folder', NULL, 10, 20)")
            execSQL("INSERT INTO tags VALUES ('tag', 'Tag', 10, 20)")
            execSQL("INSERT INTO statistics_series VALUES ('series', 'ACTIVITY', 'Series', 10, NULL)")
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 2, true, MIGRATION_1_2).use { migrated ->
            migrated.query("SELECT name FROM folders WHERE id = 'folder'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Folder", cursor.getString(0))
            }
            migrated.query("SELECT name FROM tags WHERE id = 'tag'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Tag", cursor.getString(0))
            }
            migrated.query("SELECT display_name FROM statistics_series WHERE id = 'series'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Series", cursor.getString(0))
            }
            migrated
                .query(
                    "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'idx_activity_template_one_main_field'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0).contains("WHERE `is_main_value` = 1 AND `deleted_at_ms` IS NULL"))
                }

            migrated.execSQL(
                """
                INSERT INTO activity_templates (
                    id, name, short_comment, time_tracking_mode, timer_target_ms,
                    statistics_series_id, revision, created_at_ms, updated_at_ms, deleted_at_ms, folder_id
                ) VALUES ('template', 'Template', NULL, 'TIMER', 1000, 'series', 1, 10, 20, NULL, 'folder')
                """.trimIndent(),
            )
            migrated.execSQL("INSERT INTO activity_template_settings (activity_template_id) VALUES ('template')")
            migrated
                .query(
                    "SELECT time_tracking_mode, show_seconds FROM activity_templates JOIN activity_template_settings " +
                        "ON activity_templates.id = activity_template_settings.activity_template_id",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("TIMER", cursor.getString(0))
                    assertEquals(1, cursor.getInt(1))
                }
        }
    }
}
