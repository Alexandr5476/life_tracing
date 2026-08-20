package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SequenceSnapshotDatabaseTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var snapshots: SequenceSnapshotDao

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        snapshots = database.sequenceSnapshotDao()
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity("activity", "Activity", null, "TIMER", 30_000, null, null, null, false, 1),
                ActivitySnapshotSettingsEntity("activity"),
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun aggregateRoundTripsFrozenOverridesAndHardDeletePrunesItsOnlyActivitySnapshot() {
        snapshots.insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity("snapshot", "Sequence", null, null, null, null, 1),
                SequenceSnapshotSettingsEntity("snapshot", true, 1_000, 2_000, true, true, false, true, true, "PAUSE"),
                nodes = listOf(SequenceSnapshotNodeEntity("step", "snapshot", "STEP", null, 0, "activity", null)),
                stepOverrides = listOf(SequenceSnapshotStepOverrideEntity("step", 0, "OVERTIME", false, false, false)),
            ),
        )

        val aggregate = requireNotNull(snapshots.getAggregate("snapshot"))
        assertEquals("PAUSE", aggregate.settings.noLiveTimeAccounting)
        assertEquals(0L, aggregate.stepOverrides.single().startCountdownMs)
        assertEquals(false, aggregate.stepOverrides.single().timerEndSound)

        snapshots.hardDeleteAndPruneOwnedActivitySnapshots("snapshot")

        assertNull(snapshots.getById("snapshot"))
        assertNull(database.activitySnapshotDao().getById("activity"))
    }

    @Test
    fun readBoundaryRejectsRawNonTimerOvertimeButAcceptsTimerOvertime() {
        insertActivitySnapshot("stopwatch", "STOPWATCH")
        snapshots.insertAggregate(snapshotAggregate("bad", "stopwatch"))
        sql(
            "INSERT INTO sequence_snapshot_step_overrides " +
                "(sequence_snapshot_node_id, timer_zero_behavior) VALUES ('bad-step', 'OVERTIME')",
        )

        assertThrows(IllegalArgumentException::class.java) { snapshots.getAggregate("bad") }

        snapshots.insertAggregate(
            snapshotAggregate("valid", "activity").copy(
                stepOverrides =
                    listOf(SequenceSnapshotStepOverrideEntity("valid-step", 0, "OVERTIME", false, null, false)),
            ),
        )
        val valid = requireNotNull(snapshots.getAggregate("valid"))
        assertEquals("OVERTIME", valid.stepOverrides.single().timerZeroBehavior)
        assertEquals(0L, valid.stepOverrides.single().startCountdownMs)
        assertEquals(false, valid.stepOverrides.single().timerEndSound)
    }

    @Test
    fun settingsAreRequiredMaterializedAndSqlConstrainedWithoutDefaults() {
        insertSnapshotRoot("settings")

        assertThrows(SQLiteConstraintException::class.java) {
            sql("INSERT INTO sequence_snapshot_settings (sequence_snapshot_id) VALUES ('settings')")
        }
        listOf(
            completeSettingsSql("settings", sequenceCountdown = -1),
            completeSettingsSql("settings", stepCountdown = -1),
            completeSettingsSql("settings", accounting = "UNKNOWN"),
        ).forEach { statement ->
            assertThrows(SQLiteConstraintException::class.java) { sql(statement) }
        }

        sql(completeSettingsSql("settings"))
        assertNotNull(snapshots.getSettings("settings"))
        val defaults = database.openHelper.readableDatabase.columnDefaults("sequence_snapshot_settings")
        assertTrue(defaults.values.all { it == null })
    }

    @Test
    fun fieldsOptionsAndPartialMainValueIndexEnforceOwnershipAndTypes() {
        val aggregate =
            snapshotAggregate("fields").copy(
                fields =
                    listOf(
                        field("number", "fields", 0, "NUMBER", isMain = true),
                        field("category", "fields", 1, "CATEGORY", defaultOption = "easy"),
                        field("text", "fields", 2, "TEXT", defaultText = "note"),
                    ),
                options =
                    listOf(
                        option("easy", "category", 0, "Same"),
                        option("hard", "category", 1, "Same"),
                    ),
            )
        snapshots.insertAggregate(aggregate)

        val loaded = requireNotNull(snapshots.getAggregate("fields"))
        assertEquals(listOf("NUMBER", "CATEGORY", "TEXT"), loaded.fields.map { it.fieldType })
        assertEquals(listOf("Same", "Same"), loaded.options.map { it.labelAtCreation })

        assertThrows(IllegalArgumentException::class.java) {
            snapshots.insertAggregate(
                snapshotAggregate("foreign-default").copy(
                    fields =
                        listOf(
                            field("first", "foreign-default", 0, "CATEGORY", defaultOption = "other-option"),
                            field("other", "foreign-default", 1, "CATEGORY"),
                        ),
                    options = listOf(option("other-option", "other", 0, "Other")),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshots.insertAggregate(
                snapshotAggregate("category-main").copy(
                    fields = listOf(field("category-main-field", "category-main", 0, "CATEGORY", isMain = true)),
                ),
            )
        }

        insertSnapshotRoot("two-main")
        sql(completeSettingsSql("two-main"))
        sql(rawFieldSql("main-one", "two-main", 0, "NUMBER", 1))
        assertThrows(SQLiteConstraintException::class.java) {
            sql(rawFieldSql("main-two", "two-main", 1, "NUMBER", 1))
        }
    }

    @Test
    fun sourceHardPurgeNullsLinksAndPreservesFrozenAggregate() {
        database.statisticsSeriesDao().insert(StatisticsSeriesEntity("sequence-series", "SEQUENCE", "Series", 1, null))
        sql(
            "INSERT INTO sequence_templates " +
                "(id, name, statistics_series_id, revision, created_at_ms, updated_at_ms, no_live_time_accounting) " +
                "VALUES ('source', 'Source', 'sequence-series', 3, 1, 1, 'ACTIVE')",
        )
        sql(
            "INSERT INTO sequence_template_fields " +
                "(id, sequence_template_id, position, name, field_type, created_at_ms, updated_at_ms) " +
                "VALUES ('source-field', 'source', 0, 'Field', 'CATEGORY', 1, 1)",
        )
        sql(
            "INSERT INTO sequence_template_category_options " +
                "(id, sequence_template_field_id, position, label) VALUES ('source-option', 'source-field', 0, 'Option')",
        )
        val linked =
            snapshotAggregate("linked").copy(
                snapshot =
                    SequenceSnapshotEntity(
                        "linked",
                        "Frozen",
                        "Comment",
                        "source",
                        3,
                        "sequence-series",
                        1,
                    ),
                fields = listOf(field("frozen-field", "linked", 0, "CATEGORY", "frozen-option", "source-field")),
                options = listOf(option("frozen-option", "frozen-field", 0, "Option", "source-option")),
                stepOverrides =
                    listOf(SequenceSnapshotStepOverrideEntity("linked-step", 0, "OVERTIME", false, false, false)),
            )
        snapshots.insertAggregate(linked)
        sql("UPDATE sequence_templates SET archived_at_ms = 2 WHERE id = 'source'")
        assertEquals("source", snapshots.getAggregate("linked")?.snapshot?.sourceTemplateId)
        assertThrows(SQLiteConstraintException::class.java) {
            sql("DELETE FROM statistics_series WHERE id = 'sequence-series'")
        }

        sql("DELETE FROM sequence_templates WHERE id = 'source'")

        val preserved = requireNotNull(snapshots.getAggregate("linked"))
        assertNull(preserved.snapshot.sourceTemplateId)
        assertEquals(3L, preserved.snapshot.sourceRevision)
        assertEquals("sequence-series", preserved.snapshot.statisticsSeriesId)
        assertEquals("Comment", preserved.snapshot.shortComment)
        assertNull(preserved.fields.single().sourceFieldId)
        assertNull(preserved.options.single().sourceOptionId)
        assertEquals("OVERTIME", preserved.stepOverrides.single().timerZeroBehavior)
    }

    @Test
    fun missingRootReferencesAreRejected() {
        database.statisticsSeriesDao().insert(StatisticsSeriesEntity("series", "SEQUENCE", "Series", 1, null))
        assertThrows(SQLiteConstraintException::class.java) {
            snapshots.insertAggregate(
                snapshotAggregate("missing-template").copy(
                    snapshot =
                        SequenceSnapshotEntity(
                            "missing-template",
                            "Sequence",
                            null,
                            "missing",
                            1,
                            "series",
                            1,
                        ),
                ),
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            snapshots.insertAggregate(
                snapshotAggregate("missing-series").copy(
                    snapshot =
                        SequenceSnapshotEntity(
                            "missing-series",
                            "Sequence",
                            null,
                            null,
                            null,
                            "missing",
                            1,
                        ),
                ),
            )
        }
    }

    @Test
    fun nodeOrderingStructureAndLosslessReadValidationAreEnforced() {
        insertActivitySnapshot("other")
        snapshots.insertAggregate(
            snapshotAggregate("nodes").copy(
                nodes =
                    listOf(
                        node("second", "nodes", "STEP", null, 1, "other", null),
                        node("repeat", "nodes", "REPEAT", null, 0, null, 2),
                        node("child-two", "nodes", "STEP", "repeat", 1, "other", null),
                        node("child-one", "nodes", "STEP", "repeat", 0, "activity", null),
                    ),
            ),
        )
        val loaded = requireNotNull(snapshots.getAggregate("nodes")).toDomain()
        assertEquals(listOf("repeat", "second"), loaded.nodes.map { it.id.value })
        assertEquals(
            listOf("child-one", "child-two"),
            (loaded.nodes.first() as com.alexandr5476.lifetracing.domain.SequenceSnapshotRepeatBlock)
                .children
                .map { it.id.value },
        )

        assertThrows(IllegalArgumentException::class.java) {
            snapshots.insertAggregate(
                snapshotAggregate("duplicate-position").copy(
                    nodes =
                        listOf(
                            node("one", "duplicate-position", "STEP", null, 0, "activity", null),
                            node("two", "duplicate-position", "STEP", null, 0, "activity", null),
                        ),
                ),
            )
        }
        listOf(0, -1).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) {
                snapshots.insertAggregate(
                    snapshotAggregate("repeat-$count").copy(
                        nodes = listOf(node("repeat-$count-node", "repeat-$count", "REPEAT", null, 0, null, count)),
                    ),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshots.insertAggregate(
                snapshotAggregate("step-parent").copy(
                    nodes =
                        listOf(
                            node("parent-step", "step-parent", "STEP", null, 0, "activity", null),
                            node("child-step", "step-parent", "STEP", "parent-step", 0, "activity", null),
                        ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshots.insertAggregate(snapshotAggregate("missing-activity", "does-not-exist"))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            database.activitySnapshotDao().hardDelete("other")
        }

        snapshots.insertAggregate(snapshotAggregate("malformed-parent"))
        assertThrows(SQLiteConstraintException::class.java) {
            sql(
                "INSERT INTO sequence_snapshot_nodes " +
                    "(id, sequence_snapshot_id, node_type, parent_repeat_node_id, position, activity_snapshot_id) " +
                    "VALUES ('missing-parent-child', 'malformed-parent', 'STEP', 'missing', 0, 'activity')",
            )
        }
        sql(
            "INSERT INTO sequence_snapshot_nodes " +
                "(id, sequence_snapshot_id, node_type, parent_repeat_node_id, position, activity_snapshot_id) " +
                "VALUES ('step-child', 'malformed-parent', 'STEP', 'malformed-parent-step', 0, 'activity')",
        )
        assertThrows(IllegalArgumentException::class.java) { snapshots.getAggregate("malformed-parent") }
    }

    @Test
    fun overridesCanonicalizeAndConstraintsCascade() {
        snapshots.insertAggregate(
            snapshotAggregate("empty-override").copy(
                stepOverrides =
                    listOf(
                        SequenceSnapshotStepOverrideEntity("empty-override-step", null, null, null, null, null),
                    ),
            ),
        )
        assertTrue(snapshots.getStepOverrides("empty-override").isEmpty())

        insertActivitySnapshot("override-stopwatch", "STOPWATCH")
        assertThrows(IllegalArgumentException::class.java) {
            snapshots.insertAggregate(
                snapshotAggregate("stopwatch-override", "override-stopwatch").copy(
                    stepOverrides =
                        listOf(
                            SequenceSnapshotStepOverrideEntity(
                                "stopwatch-override-step",
                                null,
                                "OVERTIME",
                                null,
                                null,
                                null,
                            ),
                        ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshots.insertAggregate(
                snapshotAggregate("repeat-override").copy(
                    nodes = listOf(node("repeat-owner", "repeat-override", "REPEAT", null, 0, null, 1)),
                    stepOverrides =
                        listOf(SequenceSnapshotStepOverrideEntity("repeat-owner", 0, null, null, null, null)),
                ),
            )
        }

        listOf(
            "INSERT INTO sequence_snapshot_step_overrides (sequence_snapshot_node_id, start_countdown_ms) " +
                "VALUES ('empty-override-step', -1)",
            "INSERT INTO sequence_snapshot_step_overrides (sequence_snapshot_node_id, timer_zero_behavior) " +
                "VALUES ('empty-override-step', 'UNKNOWN')",
            "INSERT INTO sequence_snapshot_step_overrides (sequence_snapshot_node_id) VALUES ('missing')",
        ).forEach { statement ->
            assertThrows(SQLiteConstraintException::class.java) { sql(statement) }
        }
        sql(
            "INSERT INTO sequence_snapshot_step_overrides " +
                "(sequence_snapshot_node_id, timer_end_sound) VALUES ('empty-override-step', 0)",
        )
        sql("DELETE FROM sequence_snapshot_nodes WHERE id = 'empty-override-step'")
        assertTrue(snapshots.getStepOverrides("empty-override").isEmpty())
    }

    @Test
    fun failedLateChildInsertRollsBackOwnedRowsAndPreservesActivitySnapshot() {
        val invalid =
            snapshotAggregate("rollback").copy(
                fields = listOf(field("rollback-field", "rollback", 0, "NUMBER", sourceField = "missing")),
            )

        assertThrows(SQLiteConstraintException::class.java) { snapshots.insertAggregate(invalid) }

        assertNull(snapshots.getById("rollback"))
        assertNull(snapshots.getSettings("rollback"))
        assertTrue(snapshots.getFields("rollback").isEmpty())
        assertTrue(snapshots.getNodes("rollback").isEmpty())
        assertTrue(snapshots.getStepOverrides("rollback").isEmpty())
        assertNotNull(database.activitySnapshotDao().getById("activity"))
    }

    private fun snapshotAggregate(
        id: String,
        activityId: String = "activity",
    ) = SequenceSnapshotAggregateEntity(
        SequenceSnapshotEntity(id, "Sequence", null, null, null, null, 1),
        SequenceSnapshotSettingsEntity(id, true, 1_000, 2_000, true, true, false, true, true, "PAUSE"),
        nodes = listOf(node("$id-step", id, "STEP", null, 0, activityId, null)),
    )

    private fun field(
        id: String,
        owner: String,
        position: Int,
        type: String,
        defaultOption: String? = null,
        sourceField: String? = null,
        defaultText: String? = null,
        isMain: Boolean = false,
    ) = SequenceSnapshotFieldEntity(
        id,
        owner,
        sourceField,
        position,
        id,
        null,
        type,
        if (type == "NUMBER") "reps" else null,
        if (type == "NUMBER") 0 else null,
        if (type == "NUMBER") 1_000 else null,
        defaultOption,
        defaultText,
        isMain,
    )

    private fun option(
        id: String,
        owner: String,
        position: Int,
        label: String,
        sourceOption: String? = null,
    ) = SequenceSnapshotCategoryOptionEntity(id, owner, sourceOption, position, label, null)

    private fun node(
        id: String,
        owner: String,
        type: String,
        parent: String?,
        position: Int,
        activityId: String?,
        repeatCount: Int?,
    ) = SequenceSnapshotNodeEntity(id, owner, type, parent, position, activityId, repeatCount)

    private fun insertActivitySnapshot(
        id: String,
        mode: String = "TIMER",
    ) {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    id,
                    id,
                    null,
                    mode,
                    if (mode == "TIMER") 30_000 else null,
                    null,
                    null,
                    null,
                    false,
                    1,
                ),
                ActivitySnapshotSettingsEntity(id),
            ),
        )
    }

    private fun insertSnapshotRoot(id: String) =
        sql("INSERT INTO sequence_snapshots (id, name, created_at_ms) VALUES ('$id', 'Sequence', 1)")

    private fun completeSettingsSql(
        id: String,
        sequenceCountdown: Long = 0,
        stepCountdown: Long = 0,
        accounting: String = "ACTIVE",
    ) = "INSERT INTO sequence_snapshot_settings VALUES " +
        "('$id', 1, $sequenceCountdown, $stepCountdown, 1, 1, 0, 1, 1, '$accounting')"

    private fun rawFieldSql(
        id: String,
        owner: String,
        position: Int,
        type: String,
        isMain: Int,
    ) = "INSERT INTO sequence_snapshot_fields " +
        "(id, sequence_snapshot_id, position, name_at_creation, field_type, is_main_value) " +
        "VALUES ('$id', '$owner', $position, '$id', '$type', $isMain)"

    private fun sql(statement: String) = database.openHelper.writableDatabase.execSQL(statement)

    private fun androidx.sqlite.db.SupportSQLiteDatabase.columnDefaults(table: String): Map<String, String?> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val default = cursor.getColumnIndexOrThrow("dflt_value")
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(name), cursor.getString(default))
            }
        }
}
