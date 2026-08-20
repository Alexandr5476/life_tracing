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
class SequenceExecutionMigrationTest {
    private val names = listOf("sequence-v7-migrated", "sequence-v7-fresh", "sequence-v7-historical")
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun removeOldDatabases() = names.forEach(context::deleteDatabase)

    @After
    fun cleanUp() = names.forEach(context::deleteDatabase)

    @Test
    fun migrationFromExactV6PreservesEveryRepresentativeRowAndAcceptsV7Aggregate() {
        LifeTracingMigrationTestDatabaseFactory.createVersion6(helper, names[0]).apply {
            insertRepresentativeV6Rows()
            close()
        }

        helper.runMigrationsAndValidate(names[0], 7, true, MIGRATION_6_7).use { migrated ->
            REPRESENTATIVE_ROWS.forEach { (table, where) ->
                assertEquals("v6 row lost from $table", 1, migrated.rowCount(table, where))
            }
            assertEquals(
                20L,
                migrated.longValue("SELECT started_at_ms FROM activity_execution_pauses WHERE id = 'pause'"),
            )
            assertEquals(
                "activity-option",
                migrated.stringValue(
                    "SELECT category_option_id FROM activity_execution_field_values " +
                        "WHERE activity_execution_id = 'activity-execution'",
                ),
            )
            migrated.insertV7Aggregate()
            migrated
                .query(
                    "SELECT roots.active_duration_ms, occurrences.repeat_iteration, intervals.kind, " +
                        "values.number_scaled " +
                        "FROM sequence_executions AS roots " +
                        "JOIN sequence_occurrences AS occurrences ON occurrences.sequence_execution_id = roots.id " +
                        "JOIN sequence_intervals AS intervals ON intervals.sequence_execution_id = roots.id " +
                        "JOIN sequence_execution_field_values AS values ON values.sequence_execution_id = roots.id " +
                        "WHERE roots.id = 'sequence-execution'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(60L, cursor.getLong(0))
                    assertEquals(1, cursor.getInt(1))
                    assertEquals("ACTIVE_STEP", cursor.getString(2))
                    assertEquals(0L, cursor.getLong(3))
                }
        }
    }

    @Test
    fun freshMigratedAndHistoricalV7HaveEquivalentSemanticSchemasAndRealActivityForeignKeys() {
        val fresh = LifeTracingDatabase.builder(context, names[1]).allowMainThreadQueries().build()
        val freshSchema = fresh.openHelper.readableDatabase.readSequenceExecutionManualSchema()
        fresh.close()

        LifeTracingMigrationTestDatabaseFactory.createVersion6(helper, names[0]).close()
        val migrated =
            helper.runMigrationsAndValidate(names[0], 7, true, MIGRATION_6_7).use {
                it.readSequenceExecutionManualSchema()
            }
        val historical =
            LifeTracingMigrationTestDatabaseFactory.createVersion7(helper, names[2]).use {
                it.readSequenceExecutionManualSchema()
            }

        assertEquals(freshSchema, migrated)
        assertEquals(freshSchema, historical)
        assertEquals("activity_snapshots:RESTRICT", freshSchema.activityExecutionForeignKeys["snapshot_id"])
        assertEquals("statistics_series:RESTRICT", freshSchema.activityExecutionForeignKeys["statistics_series_id"])
        assertEquals("sequence_executions:RESTRICT", freshSchema.activityExecutionForeignKeys["sequence_execution_id"])
        assertEquals(
            "sequence_occurrences:RESTRICT",
            freshSchema.activityExecutionForeignKeys["sequence_occurrence_id"],
        )
        assertTrue(freshSchema.childIndexUnique)
        assertEquals("sequence_occurrence_id is not null", freshSchema.childIndexPredicate)
        assertTrue(freshSchema.hasRootStatusCacheCheck)
        assertTrue(freshSchema.hasOccurrenceStatusCheck)
        assertTrue(freshSchema.hasOccurrenceReasonCheck)
        assertTrue(freshSchema.hasIntervalKindCheck)
        assertTrue(freshSchema.hasTypedValueCheck)
    }

    private fun SupportSQLiteDatabase.insertRepresentativeV6Rows() {
        execSQL("INSERT INTO folders VALUES ('folder', 'Folder', NULL, 0, 0)")
        execSQL("INSERT INTO tags VALUES ('tag', 'Tag', 0, 0)")
        execSQL("INSERT INTO statistics_series VALUES ('activity-series', 'ACTIVITY', 'Activity', 0, NULL)")
        execSQL("INSERT INTO statistics_series VALUES ('sequence-series', 'SEQUENCE', 'Sequence', 0, NULL)")
        execSQL(
            "INSERT INTO activity_templates VALUES ('activity-template', 'Activity', NULL, 'STOPWATCH', NULL, 'activity-series', 1, 0, 0, NULL, 'folder')",
        )
        execSQL("INSERT INTO activity_template_settings (activity_template_id) VALUES ('activity-template')")
        execSQL("INSERT INTO activity_template_user_state VALUES ('activity-template', 1, 0)")
        execSQL(
            "INSERT INTO activity_template_fields VALUES ('activity-field', 'activity-template', 0, 'Category', 'CATEGORY', NULL, NULL, NULL, 'activity-option', NULL, 0, 0, 0, NULL)",
        )
        execSQL(
            "INSERT INTO activity_template_category_options VALUES ('activity-option-source', 'activity-field', 0, 'Option', 0)",
        )
        execSQL("INSERT INTO activity_template_tags VALUES ('activity-template', 'tag')")
        execSQL(
            "INSERT INTO activity_snapshots VALUES ('activity-snapshot', 'Activity', NULL, 'STOPWATCH', NULL, 'activity-template', 1, 'activity-series', 0, 0)",
        )
        execSQL("INSERT INTO activity_snapshot_settings (snapshot_id) VALUES ('activity-snapshot')")
        execSQL(
            "INSERT INTO activity_snapshot_fields VALUES ('activity-snapshot-field', 'activity-snapshot', 'activity-field', 0, 'Category', NULL, 'CATEGORY', NULL, NULL, NULL, 'activity-option', NULL, 0)",
        )
        execSQL(
            "INSERT INTO activity_snapshot_category_options VALUES ('activity-option', 'activity-snapshot-field', 'activity-option-source', 0, 'Option', NULL)",
        )
        execSQL(
            "INSERT INTO activity_executions VALUES ('activity-execution', 'activity-snapshot', 'STANDALONE', NULL, NULL, NULL, 'activity-series', 'COMPLETED', 0, 100, 70, 'UTC', 0, '1970-01-01', NULL, NULL, 0, 100)",
        )
        execSQL("INSERT INTO activity_execution_pauses VALUES ('pause', 'activity-execution', 20, 50)")
        execSQL(
            "INSERT INTO activity_execution_field_values VALUES ('activity-execution', 'activity-snapshot-field', NULL, 'activity-option', NULL)",
        )
        execSQL(
            "INSERT INTO sequence_templates VALUES ('sequence-template', 'Sequence', NULL, 'sequence-series', 1, 0, 0, NULL, 'folder', 'ACTIVE')",
        )
        execSQL("INSERT INTO sequence_template_settings (sequence_template_id) VALUES ('sequence-template')")
        execSQL("INSERT INTO sequence_template_user_state VALUES ('sequence-template', 1, 0)")
        execSQL(
            "INSERT INTO sequence_template_fields VALUES ('sequence-field', 'sequence-template', 0, 'Number', 'NUMBER', NULL, 0, 0, NULL, NULL, 0, 0, 0, NULL)",
        )
        execSQL(
            "INSERT INTO sequence_template_category_options VALUES ('sequence-option', 'sequence-field', 0, 'Option', 0)",
        )
        execSQL("INSERT INTO sequence_template_tags VALUES ('sequence-template', 'tag')")
        execSQL(
            "INSERT INTO sequence_nodes VALUES ('sequence-step', 'sequence-template', 'STEP', NULL, 0, 'activity-snapshot', NULL)",
        )
        execSQL(
            "INSERT INTO sequence_step_overrides (sequence_node_id, start_countdown_ms) VALUES ('sequence-step', 0)",
        )
        execSQL(
            "INSERT INTO sequence_snapshots VALUES ('sequence-snapshot', 'Sequence', NULL, 'sequence-template', 1, 'sequence-series', 0)",
        )
        execSQL("INSERT INTO sequence_snapshot_settings VALUES ('sequence-snapshot', 1, 0, 0, 1, 1, 0, 1, 1, 'ACTIVE')")
        execSQL(
            "INSERT INTO sequence_snapshot_fields VALUES ('sequence-snapshot-field', 'sequence-snapshot', 'sequence-field', 0, 'Number', NULL, 'NUMBER', NULL, 0, 0, NULL, NULL, 0)",
        )
        execSQL(
            "INSERT INTO sequence_snapshot_category_options VALUES ('sequence-snapshot-option', 'sequence-snapshot-field', 'sequence-option', 0, 'Option', NULL)",
        )
        execSQL(
            "INSERT INTO sequence_snapshot_nodes VALUES ('sequence-snapshot-step', 'sequence-snapshot', 'STEP', NULL, 0, 'activity-snapshot', NULL)",
        )
        execSQL(
            "INSERT INTO sequence_snapshot_step_overrides (sequence_snapshot_node_id, start_countdown_ms) VALUES ('sequence-snapshot-step', 0)",
        )
    }

    private fun SupportSQLiteDatabase.insertV7Aggregate() {
        execSQL(
            "INSERT INTO sequence_snapshot_nodes VALUES ('sequence-snapshot-repeat', 'sequence-snapshot', 'REPEAT', NULL, 1, NULL, 2)",
        )
        execSQL(
            "INSERT INTO sequence_snapshot_nodes VALUES ('sequence-snapshot-repeat-child', 'sequence-snapshot', 'STEP', 'sequence-snapshot-repeat', 0, 'activity-snapshot', NULL)",
        )
        execSQL(
            "INSERT INTO sequence_executions VALUES ('sequence-execution', 'sequence-snapshot', NULL, 'sequence-series', 'COMPLETED', 0, 100, 60, 40, 100, 'UTC', 0, '1970-01-01', NULL, 0, 100)",
        )
        execSQL(
            "INSERT INTO sequence_occurrences VALUES ('occurrence', 'sequence-execution', 'sequence-snapshot-repeat-child', 'activity-snapshot', 0, 'sequence-snapshot-repeat', 1, 'COMPLETED', 0, 60, 'MANUAL_FINISH', 0, 0)",
        )
        execSQL(
            "INSERT INTO sequence_intervals VALUES ('interval', 'sequence-execution', 'ACTIVE_STEP', 0, 60, 'occurrence')",
        )
        execSQL(
            "INSERT INTO sequence_execution_field_values VALUES ('sequence-execution', 'sequence-snapshot-field', 0, NULL, NULL)",
        )
    }

    private fun SupportSQLiteDatabase.rowCount(
        table: String,
        where: String,
    ): Int =
        query("SELECT COUNT(*) FROM `$table` WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.longValue(sql: String): Long =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.stringValue(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private companion object {
        val REPRESENTATIVE_ROWS =
            mapOf(
                "folders" to "id = 'folder'",
                "tags" to "id = 'tag'",
                "statistics_series" to "id = 'activity-series'",
                "activity_templates" to "id = 'activity-template'",
                "activity_template_settings" to "activity_template_id = 'activity-template'",
                "activity_template_user_state" to "activity_template_id = 'activity-template'",
                "activity_template_fields" to "id = 'activity-field'",
                "activity_template_category_options" to "id = 'activity-option-source'",
                "activity_template_tags" to "activity_template_id = 'activity-template'",
                "activity_snapshots" to "id = 'activity-snapshot'",
                "activity_snapshot_settings" to "snapshot_id = 'activity-snapshot'",
                "activity_snapshot_fields" to "id = 'activity-snapshot-field'",
                "activity_snapshot_category_options" to "id = 'activity-option'",
                "activity_executions" to "id = 'activity-execution'",
                "activity_execution_pauses" to "id = 'pause'",
                "activity_execution_field_values" to "activity_execution_id = 'activity-execution'",
                "sequence_templates" to "id = 'sequence-template'",
                "sequence_template_settings" to "sequence_template_id = 'sequence-template'",
                "sequence_template_user_state" to "sequence_template_id = 'sequence-template'",
                "sequence_template_fields" to "id = 'sequence-field'",
                "sequence_template_category_options" to "id = 'sequence-option'",
                "sequence_template_tags" to "sequence_template_id = 'sequence-template'",
                "sequence_nodes" to "id = 'sequence-step'",
                "sequence_step_overrides" to "sequence_node_id = 'sequence-step'",
                "sequence_snapshots" to "id = 'sequence-snapshot'",
                "sequence_snapshot_settings" to "sequence_snapshot_id = 'sequence-snapshot'",
                "sequence_snapshot_fields" to "id = 'sequence-snapshot-field'",
                "sequence_snapshot_category_options" to "id = 'sequence-snapshot-option'",
                "sequence_snapshot_nodes" to "id = 'sequence-snapshot-step'",
                "sequence_snapshot_step_overrides" to "sequence_snapshot_node_id = 'sequence-snapshot-step'",
            )
    }
}
