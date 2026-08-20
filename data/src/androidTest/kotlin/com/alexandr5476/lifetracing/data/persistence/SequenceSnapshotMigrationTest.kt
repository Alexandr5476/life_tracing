package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SequenceSnapshotMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = listOf("snapshot-migrated", "snapshot-fresh", "snapshot-historical")

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun removeOldDatabases() = names.forEach(context::deleteDatabase)

    @After
    fun cleanUp() = names.forEach(context::deleteDatabase)

    @Test
    fun migrationFromExactVersion5PreservesRepresentativeStateAndAcceptsSnapshotAggregate() {
        LifeTracingMigrationTestDatabaseFactory.createVersion5(helper, names[0]).apply {
            insertRepresentativeVersion5State()
            close()
        }

        helper.runMigrationsAndValidate(names[0], 6, true, MIGRATION_5_6).use { migrated ->
            REPRESENTATIVE_TABLES.forEach { table ->
                assertEquals("v5 row lost from $table", EXPECTED_COUNTS.getValue(table), migrated.rowCount(table))
            }
            assertEquals("PAUSE", migrated.text("sequence_templates", "no_live_time_accounting", "id = 'sequence'"))
            assertEquals(3, migrated.number("sequence_nodes", "repeat_count", "id = 'repeat'"))
            assertEquals(
                0,
                migrated.number("sequence_step_overrides", "start_countdown_ms", "sequence_node_id = 'child'"),
            )
            assertEquals(0, migrated.number("sequence_step_overrides", "timer_end_sound", "sequence_node_id = 'child'"))
        }

        val room = LifeTracingDatabase.builder(context, names[0]).allowMainThreadQueries().build()
        try {
            room.sequenceSnapshotDao().insertAggregate(
                SequenceSnapshotAggregateEntity(
                    SequenceSnapshotEntity("frozen", "Frozen", "Comment", "sequence", 4, "sequence-series", 100),
                    SequenceSnapshotSettingsEntity("frozen", true, 0, 0, true, false, true, true, false, "PAUSE"),
                    nodes =
                        listOf(
                            SequenceSnapshotNodeEntity("frozen-repeat", "frozen", "REPEAT", null, 0, null, 2),
                            SequenceSnapshotNodeEntity(
                                "frozen-child",
                                "frozen",
                                "STEP",
                                "frozen-repeat",
                                0,
                                "snapshot",
                                null,
                            ),
                        ),
                    stepOverrides =
                        listOf(SequenceSnapshotStepOverrideEntity("frozen-child", 0, null, false, null, true)),
                ),
            )

            val loaded = requireNotNull(room.sequenceSnapshotDao().getAggregate("frozen"))
            assertEquals("sequence", loaded.snapshot.sourceTemplateId)
            assertEquals(listOf("frozen-repeat", "frozen-child"), loaded.nodes.map { it.id })
            assertEquals(0L, loaded.stepOverrides.single().startCountdownMs)
        } finally {
            room.close()
        }
    }

    @Test
    fun freshMigratedAndHistoricalVersion6HaveEquivalentSequenceSnapshotSchemas() {
        val freshDatabase = LifeTracingDatabase.builder(context, names[1]).allowMainThreadQueries().build()
        val fresh = freshDatabase.openHelper.readableDatabase.readSequenceSnapshotManualSchema()
        freshDatabase.close()

        LifeTracingMigrationTestDatabaseFactory.createVersion5(helper, names[0]).close()
        val migrated =
            helper.runMigrationsAndValidate(names[0], 6, true, MIGRATION_5_6).use {
                it.readSequenceSnapshotManualSchema()
            }
        val historical =
            LifeTracingMigrationTestDatabaseFactory.createVersion6(helper, names[2]).use {
                it.readSequenceSnapshotManualSchema()
            }

        assertEquals(fresh, migrated)
        assertEquals(fresh, historical)
        assertEquals(
            listOf(
                "id",
                "name",
                "short_comment",
                "source_template_id",
                "source_revision",
                "statistics_series_id",
                "created_at_ms",
            ),
            fresh.snapshotColumns,
        )
        assertEquals("SET NULL", fresh.snapshotForeignKeyDeletes["source_template_id"])
        assertEquals("RESTRICT", fresh.snapshotForeignKeyDeletes["statistics_series_id"])
        assertEquals(listOf("source_template_id"), fresh.snapshotSourceIndexColumns)
        assertEquals(listOf("statistics_series_id"), fresh.snapshotSeriesIndexColumns)
        assertEquals(
            listOf(
                "sequence_snapshot_id",
                "auto_advance",
                "sequence_start_countdown_ms",
                "before_each_step_countdown_ms",
                "transition_sound",
                "transition_vibration",
                "keep_screen_awake",
                "confirm_jump",
                "confirm_early_end",
                "no_live_time_accounting",
            ),
            fresh.settingsColumns,
        )
        assertEquals(
            listOf(
                "id",
                "sequence_snapshot_id",
                "source_field_id",
                "position",
                "name_at_creation",
                "local_name_override",
                "field_type",
                "unit",
                "display_precision",
                "default_number_scaled",
                "default_category_option_id",
                "default_text",
                "is_main_value",
            ),
            fresh.fieldColumns,
        )
        assertEquals("CASCADE", fresh.fieldForeignKeyDeletes["sequence_snapshot_id"])
        assertEquals("SET NULL", fresh.fieldForeignKeyDeletes["source_field_id"])
        assertEquals(listOf("sequence_snapshot_id", "position"), fresh.fieldOwnerIndexColumns)
        assertEquals(listOf("source_field_id"), fresh.fieldSourceIndexColumns)
        assertEquals(
            listOf(
                "id",
                "sequence_snapshot_field_id",
                "source_option_id",
                "position",
                "label_at_creation",
                "local_label_override",
            ),
            fresh.optionColumns,
        )
        assertEquals("CASCADE", fresh.optionForeignKeyDeletes["sequence_snapshot_field_id"])
        assertEquals("SET NULL", fresh.optionForeignKeyDeletes["source_option_id"])
        assertEquals(listOf("sequence_snapshot_field_id", "position"), fresh.optionOwnerIndexColumns)
        assertEquals(listOf("source_option_id"), fresh.optionSourceIndexColumns)
        assertTrue(fresh.mainValueIndexIsUnique)
        assertEquals(listOf("sequence_snapshot_id"), fresh.mainValueIndexColumns)
        assertEquals("is_main_value = 1", fresh.mainValueIndexPredicate)
        assertTrue(fresh.hasSettingsCountdownChecks)
        assertTrue(fresh.hasNoLiveAccountingCheck)
        assertTrue(fresh.hasNodeShapeCheck)
        assertTrue(fresh.hasOverrideCountdownCheck)
        assertTrue(fresh.hasOverrideTimerZeroBehaviorCheck)
        assertTrue(fresh.hasOverrideBooleanChecks)
        assertEquals(
            listOf(
                "id",
                "sequence_snapshot_id",
                "node_type",
                "parent_repeat_node_id",
                "position",
                "activity_snapshot_id",
                "repeat_count",
            ),
            fresh.nodeColumns,
        )
        assertEquals("CASCADE", fresh.nodeForeignKeyDeletes["sequence_snapshot_id"])
        assertEquals("CASCADE", fresh.nodeForeignKeyDeletes["parent_repeat_node_id"])
        assertEquals("RESTRICT", fresh.nodeForeignKeyDeletes["activity_snapshot_id"])
        assertEquals(listOf("sequence_snapshot_id", "parent_repeat_node_id", "position"), fresh.nodeOwnerIndexColumns)
        assertEquals(listOf("parent_repeat_node_id"), fresh.nodeParentIndexColumns)
        assertEquals(listOf("activity_snapshot_id"), fresh.nodeActivitySnapshotIndexColumns)
        assertEquals(
            listOf(
                "sequence_snapshot_node_id",
                "start_countdown_ms",
                "timer_zero_behavior",
                "timer_end_sound",
                "timer_end_vibration",
                "keep_screen_awake",
            ),
            fresh.overrideColumns,
        )
        assertEquals(listOf("sequence_snapshot_node_id"), fresh.overridePrimaryKeyColumns)
        assertEquals("CASCADE", fresh.overrideForeignKeyDeletes["sequence_snapshot_node_id"])
        assertFalse(fresh.settingsColumnDefaults.isEmpty())
        assertTrue(fresh.settingsColumnDefaults.values.all { it == null })
    }

    private fun SupportSQLiteDatabase.insertRepresentativeVersion5State() {
        execSQL("PRAGMA foreign_keys = ON")
        execSQL("INSERT INTO folders VALUES ('folder', 'Folder', NULL, 10, 20)")
        execSQL("INSERT INTO tags VALUES ('tag', 'Tag', 10, 20)")
        execSQL("INSERT INTO statistics_series VALUES ('series', 'ACTIVITY', 'Series', 10, NULL)")
        execSQL("INSERT INTO statistics_series VALUES ('sequence-series', 'SEQUENCE', 'Sequence', 10, NULL)")
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
        execSQL("INSERT INTO activity_snapshot_settings VALUES ('snapshot', 1, 0, 'FINISH', 1, 1, 0, 0)")
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
        execSQL(
            "INSERT INTO sequence_templates VALUES " +
                "('sequence', 'Sequence', 'Comment', 'sequence-series', 4, 10, 20, NULL, 'folder', 'PAUSE')",
        )
        execSQL("INSERT INTO sequence_template_settings (sequence_template_id) VALUES ('sequence')")
        execSQL("INSERT INTO sequence_template_user_state VALUES ('sequence', 1, 20)")
        execSQL(
            "INSERT INTO sequence_template_fields VALUES " +
                "('sequence-field', 'sequence', 0, 'Amount', 'NUMBER', 'reps', 0, 1000, NULL, NULL, 1, 10, 20, NULL)",
        )
        execSQL("INSERT INTO sequence_template_tags VALUES ('sequence', 'tag')")
        execSQL("INSERT INTO sequence_nodes VALUES ('top', 'sequence', 'STEP', NULL, 0, 'snapshot', NULL)")
        execSQL("INSERT INTO sequence_nodes VALUES ('repeat', 'sequence', 'REPEAT', NULL, 1, NULL, 3)")
        execSQL("INSERT INTO sequence_nodes VALUES ('child', 'sequence', 'STEP', 'repeat', 0, 'snapshot', NULL)")
        execSQL("INSERT INTO sequence_step_overrides VALUES ('child', 0, NULL, 0, NULL, 1)")
    }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.text(
        table: String,
        column: String,
        where: String,
    ): String =
        query("SELECT `$column` FROM `$table` WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.number(
        table: String,
        column: String,
        where: String,
    ): Int =
        query("SELECT `$column` FROM `$table` WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        val EXPECTED_COUNTS =
            mapOf(
                "folders" to 1,
                "tags" to 1,
                "statistics_series" to 3,
                "activity_templates" to 1,
                "activity_template_settings" to 1,
                "activity_template_user_state" to 1,
                "activity_template_fields" to 1,
                "activity_template_category_options" to 1,
                "activity_template_tags" to 1,
                "activity_snapshots" to 1,
                "activity_snapshot_settings" to 1,
                "activity_snapshot_fields" to 1,
                "activity_snapshot_category_options" to 1,
                "activity_executions" to 1,
                "activity_execution_pauses" to 1,
                "activity_execution_field_values" to 1,
                "sequence_templates" to 1,
                "sequence_template_settings" to 1,
                "sequence_template_user_state" to 1,
                "sequence_template_fields" to 1,
                "sequence_template_tags" to 1,
                "sequence_nodes" to 3,
                "sequence_step_overrides" to 1,
            )
        val REPRESENTATIVE_TABLES = EXPECTED_COUNTS.keys
    }
}
