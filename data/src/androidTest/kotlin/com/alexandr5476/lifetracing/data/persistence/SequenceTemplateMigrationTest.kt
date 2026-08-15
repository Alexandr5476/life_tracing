package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SequenceTemplateMigrationTest {
    private val names = listOf("sequence-migrated", "sequence-fresh", "sequence-historical")
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun removeOldDatabases() = names.forEach(context::deleteDatabase)

    @After
    fun cleanUp() = names.forEach(context::deleteDatabase)

    @Test
    fun migrationFrom4To5PreservesV4AndAcceptsCompleteSequenceAggregate() {
        LifeTracingMigrationTestDatabaseFactory.createVersion4(helper, names[0]).apply {
            insertRepresentativeV4Rows()
            close()
        }

        helper.runMigrationsAndValidate(names[0], 5, true, MIGRATION_4_5).use { migrated ->
            V4_TABLES.forEach { table -> assertEquals("v4 row lost from $table", 1, migrated.rowCount(table)) }
            assertEquals(1, migrated.rowCount("statistics_series", "id = 'series'"))
            assertEquals(
                1,
                migrated.rowCount(
                    "statistics_series",
                    "id = '${ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value}'",
                ),
            )
            migrated.insertCompleteSequenceAggregate()
            assertEquals(1, migrated.rowCount("sequence_templates"))
            assertEquals(1, migrated.rowCount("sequence_template_settings"))
            assertEquals(1, migrated.rowCount("sequence_template_user_state"))
            assertEquals(2, migrated.rowCount("sequence_template_fields"))
            assertEquals(2, migrated.rowCount("sequence_template_category_options"))
            assertEquals(1, migrated.rowCount("sequence_template_tags"))
            assertEquals(4, migrated.rowCount("sequence_nodes"))
            assertThrows(SQLiteConstraintException::class.java) {
                migrated.execSQL(
                    "UPDATE sequence_template_settings SET sequence_start_countdown_ms = -1 " +
                        "WHERE sequence_template_id = 'sequence'",
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                migrated.execSQL(
                    "INSERT INTO sequence_template_fields " +
                        "(id, sequence_template_id, position, name, field_type, is_main_value, " +
                        "created_at_ms, updated_at_ms) " +
                        "VALUES ('second-main', 'sequence', 2, 'Second', 'NUMBER', 1, 1, 1)",
                )
            }
        }
    }

    @Test
    fun freshMigratedAndHistoricalVersion5HaveEquivalentManualSchemas() {
        val freshDatabase = LifeTracingDatabase.builder(context, names[1]).allowMainThreadQueries().build()
        val fresh = freshDatabase.schemaBundle()
        freshDatabase.close()

        LifeTracingMigrationTestDatabaseFactory.createVersion4(helper, names[0]).close()
        val migrated =
            helper.runMigrationsAndValidate(names[0], 5, true, MIGRATION_4_5).use { it.schemaBundle() }
        val historical =
            LifeTracingMigrationTestDatabaseFactory.createVersion5(helper, names[2]).use { it.schemaBundle() }

        assertEquals(fresh, migrated)
        assertEquals(fresh, historical)
        assertTrue(fresh.sequence.hasNoLiveAccountingCheck)
        assertTrue(fresh.sequence.hasCountdownChecks)
        assertTrue(fresh.sequence.hasNodeShapeCheck)
    }

    private fun LifeTracingDatabase.schemaBundle() = openHelper.readableDatabase.schemaBundle()

    private fun SupportSQLiteDatabase.schemaBundle() =
        SchemaBundle(
            readActivityTemplateManualSchema(),
            readActivitySnapshotManualSchema(),
            readActivityExecutionManualSchema(),
            readSequenceTemplateManualSchema(),
        )

    private fun SupportSQLiteDatabase.insertRepresentativeV4Rows() {
        execSQL("INSERT INTO folders VALUES ('folder', 'Folder', NULL, 10, 20)")
        execSQL("INSERT INTO tags VALUES ('tag', 'Tag', 10, 20)")
        execSQL("INSERT INTO statistics_series VALUES ('series', 'ACTIVITY', 'Series', 10, NULL)")
        execSQL(
            "INSERT INTO activity_templates VALUES " +
                "('template', 'Template', NULL, 'STOPWATCH', NULL, 'series', 1, 10, 20, NULL, 'folder')",
        )
        execSQL("INSERT INTO activity_template_settings (activity_template_id) VALUES ('template')")
        execSQL("INSERT INTO activity_template_user_state VALUES ('template', 1, 20)")
        execSQL(
            "INSERT INTO activity_template_fields VALUES " +
                "('field', 'template', 0, 'Category', 'CATEGORY', NULL, NULL, NULL, 'option', NULL, 0, 10, 20, NULL)",
        )
        execSQL("INSERT INTO activity_template_category_options VALUES ('option', 'field', 0, 'Option', 0)")
        execSQL("INSERT INTO activity_template_tags VALUES ('template', 'tag')")
        execSQL(
            "INSERT INTO activity_snapshots VALUES " +
                "('snapshot', 'Snapshot', NULL, 'STOPWATCH', NULL, 'template', 1, 'series', 0, 30)",
        )
        execSQL(
            "INSERT INTO activity_snapshot_settings VALUES " +
                "('snapshot', 1, 0, 'FINISH', 1, 1, 0, 0)",
        )
        execSQL(
            "INSERT INTO activity_snapshot_fields VALUES " +
                "('snapshot-field', 'snapshot', 'field', 0, 'Category', NULL, 'CATEGORY', " +
                "NULL, NULL, NULL, 'snapshot-option', NULL, 0)",
        )
        execSQL(
            "INSERT INTO activity_snapshot_category_options VALUES " +
                "('snapshot-option', 'snapshot-field', 'option', 0, 'Option', NULL)",
        )
        execSQL(
            "INSERT INTO activity_executions VALUES " +
                "('execution', 'snapshot', 'STANDALONE', NULL, NULL, NULL, 'series', 'COMPLETED', " +
                "10, 100, 80, 'UTC', 0, '2026-08-15', NULL, NULL, 10, 100)",
        )
        execSQL("INSERT INTO activity_execution_pauses VALUES ('pause', 'execution', 20, 30)")
        execSQL(
            "INSERT INTO activity_execution_field_values VALUES " +
                "('execution', 'snapshot-field', NULL, 'snapshot-option', NULL)",
        )
    }

    private fun SupportSQLiteDatabase.insertCompleteSequenceAggregate() {
        execSQL("INSERT INTO statistics_series VALUES ('sequence-series', 'SEQUENCE', 'Sequence', 10, NULL)")
        execSQL(
            "INSERT INTO sequence_templates VALUES " +
                "('sequence', 'Sequence', 'Comment', 'sequence-series', 1, 10, 20, NULL, 'folder', 'ACTIVE')",
        )
        execSQL("INSERT INTO sequence_template_settings (sequence_template_id) VALUES ('sequence')")
        execSQL("INSERT INTO sequence_template_user_state VALUES ('sequence', 1, 20)")
        execSQL(
            "INSERT INTO sequence_template_fields VALUES " +
                "('main', 'sequence', 0, 'Amount', 'NUMBER', 'reps', 0, 15000, NULL, NULL, 1, 10, 20, NULL)",
        )
        execSQL(
            "INSERT INTO sequence_template_fields VALUES " +
                "('category', 'sequence', 1, 'Effort', 'CATEGORY', NULL, NULL, NULL, 'easy', NULL, 0, 10, 20, NULL)",
        )
        execSQL("INSERT INTO sequence_template_category_options VALUES ('easy', 'category', 0, 'Same', 0)")
        execSQL("INSERT INTO sequence_template_category_options VALUES ('hard', 'category', 1, 'Same', 0)")
        execSQL("INSERT INTO sequence_template_tags VALUES ('sequence', 'tag')")
        execSQL("INSERT INTO sequence_nodes VALUES ('top', 'sequence', 'STEP', NULL, 0, 'snapshot', NULL)")
        execSQL("INSERT INTO sequence_nodes VALUES ('repeat', 'sequence', 'REPEAT', NULL, 1, NULL, 3)")
        execSQL("INSERT INTO sequence_nodes VALUES ('child-one', 'sequence', 'STEP', 'repeat', 0, 'snapshot', NULL)")
        execSQL("INSERT INTO sequence_nodes VALUES ('child-two', 'sequence', 'STEP', 'repeat', 1, 'snapshot', NULL)")
    }

    private fun SupportSQLiteDatabase.rowCount(
        table: String,
        where: String = "1",
    ): Int =
        query("SELECT COUNT(*) FROM `$table` WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private data class SchemaBundle(
        val activityTemplate: ActivityTemplateManualSchema,
        val activitySnapshot: ActivitySnapshotManualSchema,
        val activityExecution: ActivityExecutionManualSchema,
        val sequence: SequenceTemplateManualSchema,
    )

    private companion object {
        val V4_TABLES =
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
                "activity_executions",
                "activity_execution_pauses",
                "activity_execution_field_values",
            )
    }
}
