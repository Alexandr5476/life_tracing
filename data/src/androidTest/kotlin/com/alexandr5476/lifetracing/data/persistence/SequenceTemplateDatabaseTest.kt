package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SequenceTemplateDatabaseTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var sequences: SequenceTemplateDao
    private lateinit var snapshots: ActivitySnapshotDao

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        sequences = database.sequenceTemplateDao()
        snapshots = database.activitySnapshotDao()
        database.statisticsSeriesDao().insert(
            StatisticsSeriesEntity("sequence-series", "SEQUENCE", "Sequence", 1, null),
        )
        database.folderDao().insert(FolderEntity("folder", "Folder", null, 1, 1))
        database.tagDao().insert(TagEntity("tag", "Tag", 1, 1))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun completeAggregateRoundTripsInBoundedOrderAndArchivePreservesOwnedState() {
        insertSnapshot("top-snapshot")
        insertSnapshot("child-snapshot")
        val aggregate =
            aggregate(
                fields =
                    listOf(
                        field("number", 1, isMain = true),
                        field("category", 2, type = "CATEGORY", default = "option"),
                    ),
                options =
                    listOf(
                        option("option", "category", 0, "Same"),
                        option("option-two", "category", 1, "Same"),
                    ),
                tags = listOf(SequenceTemplateTagEntity("sequence", "tag")),
                nodes =
                    listOf(
                        step("top", "sequence", null, 0, "top-snapshot"),
                        repeat("repeat", "sequence", 1, 3),
                        step("child", "sequence", "repeat", 0, "child-snapshot"),
                    ),
            )

        sequences.insertAggregate(aggregate)

        val loaded = sequences.getAggregate("sequence")
        assertNotNull(loaded)
        assertEquals(aggregate.toDomain(), loaded?.toDomain())
        assertEquals(listOf("number", "category"), sequences.getActiveFields("sequence").map { it.id })
        assertEquals(listOf("option", "option-two"), sequences.getOptions("sequence").map { it.id })
        assertEquals(1, sequences.archive("sequence", 99))
        assertEquals(aggregate.settings, sequences.getSettings("sequence"))
        assertEquals(3, sequences.getNodes("sequence").size)
        assertEquals(1, sequences.restore("sequence"))
        assertNull(sequences.getById("sequence")?.deletedAtMs)
    }

    @Test
    fun aggregateInsertionRollsBackOnChildFailureAndRelationshipsAreRestricted() {
        val missingTag = aggregate(tags = listOf(SequenceTemplateTagEntity("sequence", "missing")))
        assertThrows(SQLiteConstraintException::class.java) { sequences.insertAggregate(missingTag) }
        assertNull(sequences.getById("sequence"))
        assertNull(sequences.getSettings("sequence"))

        assertThrows(SQLiteConstraintException::class.java) {
            sequences.insertAggregate(aggregate(template = template(series = "missing")))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sequences.insertAggregate(aggregate(template = template(folder = "missing")))
        }

        sequences.insertAggregate(aggregate(template = template(folder = "folder")))
        assertThrows(SQLiteConstraintException::class.java) { sql("DELETE FROM folders WHERE id = 'folder'") }
        assertThrows(SQLiteConstraintException::class.java) {
            sql("DELETE FROM statistics_series WHERE id = 'sequence-series'")
        }
    }

    @Test
    fun settingsNoLiveCodesAndNodeShapesHaveRealSQLiteChecks() {
        sequences.insertAggregate(aggregate())
        assertThrows(SQLiteConstraintException::class.java) {
            sql(
                "UPDATE sequence_template_settings SET sequence_start_countdown_ms = -1 " +
                    "WHERE sequence_template_id = 'sequence'",
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sql(
                "UPDATE sequence_template_settings SET before_each_step_countdown_ms = -1 " +
                    "WHERE sequence_template_id = 'sequence'",
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sql("UPDATE sequence_templates SET no_live_time_accounting = 'UNKNOWN' WHERE id = 'sequence'")
        }
        insertSnapshot("snapshot")
        listOf(
            "('bad-step-null', 'sequence', 'STEP', NULL, 0, NULL, NULL)",
            "('bad-step-repeat', 'sequence', 'STEP', NULL, 0, 'snapshot', 2)",
            "('bad-repeat-snapshot', 'sequence', 'REPEAT', NULL, 0, 'snapshot', 2)",
            "('bad-repeat-zero', 'sequence', 'REPEAT', NULL, 0, NULL, 0)",
            "('bad-code', 'sequence', 'OTHER', NULL, 0, NULL, NULL)",
        ).forEach { values ->
            assertThrows(SQLiteConstraintException::class.java) {
                sql(
                    "INSERT INTO sequence_nodes " +
                        "(id, sequence_template_id, node_type, parent_repeat_node_id, position, " +
                        "activity_snapshot_id, repeat_count) " +
                        "VALUES $values",
                )
            }
        }
    }

    @Test
    fun normalNodeBoundaryRejectsCrossTemplateStepParentsDuplicatesAndMissingSnapshots() {
        insertSnapshot("one")
        insertSnapshot("two")
        sequences.insertAggregate(aggregate(template = template("a"), userState = userState("a")))
        sequences.insertAggregate(
            aggregate(template = template("b"), settings = settings("b"), userState = userState("b")),
        )
        sequences.replaceNodeStructure("a", listOf(repeat("repeat-a", "a", 0, 2)), 1, 2)

        val invalidStructures =
            listOf(
                listOf(step("cross", "b", "repeat-a", 0, "one")),
                listOf(step("parent-step", "b", null, 0, "one"), step("child", "b", "parent-step", 0, "two")),
                listOf(step("duplicate-a", "b", null, 0, "one"), step("duplicate-b", "b", null, 0, "two")),
                listOf(step("missing", "b", null, 0, "missing")),
                listOf(SequenceNodeEntity("unknown", "b", "UNKNOWN", null, 0, null, null)),
            )
        invalidStructures.forEach { nodes ->
            assertThrows(IllegalArgumentException::class.java) {
                sequences.replaceNodeStructure("b", nodes, 1, 2)
            }
        }
        assertTrue(sequences.getNodes("b").isEmpty())
        assertEquals(1, sequences.getById("b")?.revision)
    }

    @Test
    fun semanticAndMetadataUpdatesEnforceRevisionFieldEvolutionAndArchivedDefaults() {
        val initial =
            aggregate(
                template = template(revision = 7),
                fields = listOf(field("number", 0, unit = "kg")),
            )
        sequences.insertAggregate(initial)
        val renamed = initial.fields.single().copy(name = "Renamed", updatedAtMs = 2)
        assertEquals(1, sequences.updateFieldDisplayName("number", "Renamed", 2))
        sequences.updateFolder("sequence", "folder")
        sequences.replaceTags("sequence", listOf(SequenceTemplateTagEntity("sequence", "tag")))
        sequences.updateUserState(SequenceTemplateUserStateEntity("sequence", 2, 20))
        assertEquals(7, sequences.getById("sequence")?.revision)
        assertEquals("Renamed", sequences.getAllFields("sequence").single().name)

        val semantic =
            SequenceTemplateSemanticUpdate(
                template = initial.template.copy(name = "Changed", revision = 8, updatedAtMs = 3, folderId = "folder"),
                settings = initial.settings.copy(autoAdvance = false),
                fields = listOf(renamed.copy(defaultNumberScaled = 12_000)),
            )
        sequences.updateSemanticAggregate(semantic)
        assertEquals(8, sequences.getById("sequence")?.revision)
        assertFalse(sequences.getSettings("sequence")!!.autoAdvance)

        assertThrows(IllegalArgumentException::class.java) {
            sequences.updateSemanticAggregate(
                semantic.copy(
                    template = semantic.template.copy(revision = 9),
                    fields = listOf(renamed.copy(unit = "minutes")),
                ),
            )
        }
        assertEquals("kg", sequences.getAllFields("sequence").single().unit)

        val archivedOption = option("option", "category", 0, "Option", archived = true)
        assertThrows(IllegalArgumentException::class.java) {
            sequences.insertAggregate(
                aggregate(
                    template = template("invalid-default"),
                    settings = settings("invalid-default"),
                    userState = userState("invalid-default"),
                    fields =
                        listOf(
                            field("category", 0, type = "CATEGORY", default = "option", owner = "invalid-default"),
                        ),
                    options = listOf(archivedOption.copy(sequenceTemplateFieldId = "category")),
                ),
            )
        }
    }

    @Test
    fun partialMainValueIndexUsesActivePredicateAndCategoryLabelsMayDuplicate() {
        sequences.insertAggregate(aggregate())
        insertFieldSql("old-main", main = 1, deletedAt = null)
        assertThrows(
            SQLiteConstraintException::class.java,
        ) { insertFieldSql("blocked-main", main = 1, deletedAt = null) }
        sql("UPDATE sequence_template_fields SET deleted_at_ms = 10 WHERE id = 'old-main'")
        insertFieldSql("new-main", main = 1, deletedAt = null)
        insertFieldSql("category", type = "CATEGORY")
        sql("INSERT INTO sequence_template_category_options VALUES ('one', 'category', 0, 'Same', 0)")
        sql("INSERT INTO sequence_template_category_options VALUES ('two', 'category', 1, 'Same', 0)")

        assertEquals(2, sequences.getOptions("sequence").size)
        val schema = database.openHelper.readableDatabase.readSequenceTemplateManualSchema()
        assertTrue(schema.mainValueIndexIsUnique)
        assertEquals(listOf("sequence_template_id"), schema.mainValueIndexColumns)
        assertEquals("is_main_value = 1 and deleted_at_ms is null", schema.mainValueIndexPredicate)
    }

    @Test
    fun replacingStepSnapshotPrunesOnlyWhenExecutionAndOtherStepOwnersAreAbsent() {
        insertSnapshot("orphan")
        sequences.insertAggregate(aggregate(nodes = listOf(step("step", "sequence", null, 0, "orphan"))))
        sequences.replaceStepSnapshot("step", snapshotAggregate("replacement", locallyModified = true), 1, 2)
        assertNull(snapshots.getById("orphan"))
        assertEquals("replacement", sequences.getNodes("sequence").single().activitySnapshotId)
        assertEquals(2, sequences.getById("sequence")?.revision)

        insertSnapshot("shared")
        sequences.replaceNodeStructure(
            "sequence",
            listOf(
                step("first", "sequence", null, 0, "shared"),
                step("second", "sequence", null, 1, "shared"),
            ),
            2,
            3,
        )
        sequences.replaceStepSnapshot("first", snapshotAggregate("first-new", locallyModified = true), 3, 4)
        assertNotNull(snapshots.getById("shared"))

        insertSnapshot("execution-owned")
        sequences.replaceNodeStructure(
            "sequence",
            listOf(step("execution-step", "sequence", null, 0, "execution-owned")),
            4,
            5,
        )
        insertRunningExecution("execution", "execution-owned")
        sequences.replaceStepSnapshot(
            "execution-step",
            snapshotAggregate("execution-new", locallyModified = true),
            5,
            6,
        )
        assertNotNull(snapshots.getById("execution-owned"))
    }

    @Test
    fun removingRepeatPrunesOrphanChildrenAndPreservesExecutionOwnedSnapshots() {
        insertSnapshot("orphan-child")
        insertSnapshot("owned-child")
        sequences.insertAggregate(
            aggregate(
                nodes =
                    listOf(
                        repeat("repeat", "sequence", 0, 3),
                        step("orphan-step", "sequence", "repeat", 0, "orphan-child"),
                        step("owned-step", "sequence", "repeat", 1, "owned-child"),
                    ),
            ),
        )
        insertRunningExecution("execution", "owned-child")

        sequences.removeNode("sequence", "repeat", 1, 2)

        assertTrue(sequences.getNodes("sequence").isEmpty())
        assertNull(snapshots.getById("orphan-child"))
        assertNotNull(snapshots.getById("owned-child"))
        assertEquals(2, sequences.getById("sequence")?.revision)
    }

    @Test
    fun currentStepSnapshotIsRestrictProtectedAndHardTemplateDeleteCascadesOnlyOwnedRows() {
        insertSnapshot("snapshot")
        sequences.insertAggregate(
            aggregate(
                fields = listOf(field("category", 0, type = "CATEGORY")),
                options = listOf(option("option", "category", 0, "Option")),
                tags = listOf(SequenceTemplateTagEntity("sequence", "tag")),
                nodes = listOf(step("step", "sequence", null, 0, "snapshot")),
            ),
        )
        assertThrows(SQLiteConstraintException::class.java) { snapshots.hardDelete("snapshot") }

        sql("DELETE FROM sequence_templates WHERE id = 'sequence'")

        assertNull(sequences.getSettings("sequence"))
        assertNull(sequences.getUserState("sequence"))
        assertTrue(sequences.getAllFields("sequence").isEmpty())
        assertTrue(sequences.getOptions("sequence").isEmpty())
        assertTrue(sequences.getTags("sequence").isEmpty())
        assertTrue(sequences.getNodes("sequence").isEmpty())
        assertNotNull(snapshots.getById("snapshot"))
    }

    @Test
    fun sourceTemplateChangesAndArchiveDoNotMutateOrDestroyExistingStepSnapshot() {
        database.statisticsSeriesDao().insert(
            StatisticsSeriesEntity("activity-series", "ACTIVITY", "Activity", 1, null),
        )
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                template =
                    ActivityTemplateEntity(
                        "activity",
                        "Original",
                        null,
                        "STOPWATCH",
                        null,
                        "activity-series",
                        4,
                        1,
                        1,
                        null,
                        null,
                    ),
                settings = ActivityTemplateSettingsEntity("activity"),
            ),
        )
        snapshots.insertAggregate(
            ActivitySnapshotAggregateEntity(
                snapshot =
                    ActivitySnapshotEntity(
                        "linked",
                        "Original",
                        null,
                        "STOPWATCH",
                        null,
                        "activity",
                        4,
                        "activity-series",
                        false,
                        1,
                    ),
                settings = ActivitySnapshotSettingsEntity("linked"),
            ),
        )
        sequences.insertAggregate(aggregate(nodes = listOf(step("step", "sequence", null, 0, "linked"))))

        sql("UPDATE activity_templates SET name = 'Renamed', revision = 5 WHERE id = 'activity'")
        database.activityTemplateDao().archive("activity", 10)

        assertEquals("Original", snapshots.getById("linked")?.name)
        assertEquals(4, snapshots.getById("linked")?.sourceRevision)
        assertEquals("linked", sequences.getNodes("sequence").single().activitySnapshotId)
        sql("DELETE FROM activity_templates WHERE id = 'activity'")
        assertNull(snapshots.getById("linked")?.sourceTemplateId)
        assertNotNull(sequences.getById("sequence"))
    }

    @Test
    fun tagCascadeDoesNotChangeTemplateRevisionAndFreshManualSchemaMatchesFrozenShape() {
        sequences.insertAggregate(
            aggregate(
                template = template(revision = 7),
                tags = listOf(SequenceTemplateTagEntity("sequence", "tag")),
            ),
        )
        database.tagDao().deleteById("tag")
        assertTrue(sequences.getTags("sequence").isEmpty())
        assertEquals(7, sequences.getById("sequence")?.revision)

        val schema = database.openHelper.readableDatabase.readSequenceTemplateManualSchema()
        assertTrue(schema.hasNoLiveAccountingCheck)
        assertTrue(schema.hasCountdownChecks)
        assertTrue(schema.hasNodeShapeCheck)
        assertEquals("CASCADE", schema.nodeForeignKeyDeletes["sequence_template_id"])
        assertEquals("CASCADE", schema.nodeForeignKeyDeletes["parent_repeat_node_id"])
        assertEquals("RESTRICT", schema.nodeForeignKeyDeletes["activity_snapshot_id"])
        assertEquals(
            listOf(
                "id",
                "name",
                "short_comment",
                "statistics_series_id",
                "revision",
                "created_at_ms",
                "updated_at_ms",
                "deleted_at_ms",
                "folder_id",
                "no_live_time_accounting",
            ),
            schema.templateColumns,
        )
        assertEquals(
            listOf(
                "sequence_template_id",
                "auto_advance",
                "sequence_start_countdown_ms",
                "before_each_step_countdown_ms",
                "transition_sound",
                "transition_vibration",
                "keep_screen_awake",
                "confirm_jump",
                "confirm_early_end",
            ),
            schema.settingsColumns,
        )
        assertEquals(
            mapOf(
                "auto_advance" to "1",
                "sequence_start_countdown_ms" to "0",
                "before_each_step_countdown_ms" to "0",
                "transition_sound" to "1",
                "transition_vibration" to "1",
                "keep_screen_awake" to "0",
                "confirm_jump" to "1",
                "confirm_early_end" to "1",
            ),
            schema.settingsColumnDefaults,
        )
        assertEquals(
            listOf(
                "id",
                "sequence_template_id",
                "position",
                "name",
                "field_type",
                "unit",
                "display_precision",
                "default_number_scaled",
                "default_category_option_id",
                "default_text",
                "is_main_value",
                "created_at_ms",
                "updated_at_ms",
                "deleted_at_ms",
            ),
            schema.fieldColumns,
        )
        assertEquals(
            listOf("id", "sequence_template_field_id", "position", "label", "is_archived"),
            schema.optionColumns,
        )
        assertEquals(
            listOf(
                "id",
                "sequence_template_id",
                "node_type",
                "parent_repeat_node_id",
                "position",
                "activity_snapshot_id",
                "repeat_count",
            ),
            schema.nodeColumns,
        )
        assertEquals(listOf("sequence_template_id"), schema.nodeOwnerIndexColumns)
        assertEquals(
            listOf("sequence_template_id", "parent_repeat_node_id", "position"),
            schema.nodeSiblingIndexColumns,
        )
        assertEquals(listOf("activity_snapshot_id"), schema.nodeSnapshotIndexColumns)
    }

    private fun aggregate(
        template: SequenceTemplateEntity = template(),
        settings: SequenceTemplateSettingsEntity = settings(template.id),
        userState: SequenceTemplateUserStateEntity = userState(template.id),
        fields: List<SequenceTemplateFieldEntity> = emptyList(),
        options: List<SequenceTemplateCategoryOptionEntity> = emptyList(),
        tags: List<SequenceTemplateTagEntity> = emptyList(),
        nodes: List<SequenceNodeEntity> = emptyList(),
    ) = SequenceTemplateAggregateEntity(template, settings, userState, fields, options, tags, nodes)

    private fun template(
        id: String = "sequence",
        series: String = "sequence-series",
        revision: Long = 1,
        folder: String? = null,
    ) = SequenceTemplateEntity(id, id, "comment", series, revision, 1, 1, null, folder, "ACTIVE")

    private fun settings(id: String = "sequence") = SequenceTemplateSettingsEntity(id)

    private fun userState(id: String = "sequence") = SequenceTemplateUserStateEntity(id, null, null)

    private fun field(
        id: String,
        position: Int,
        type: String = "NUMBER",
        unit: String? = null,
        default: String? = null,
        isMain: Boolean = false,
        owner: String = "sequence",
    ) = SequenceTemplateFieldEntity(
        id,
        owner,
        position,
        id,
        type,
        unit,
        if (type == "NUMBER") 3 else null,
        if (type == "NUMBER") 1_000 else null,
        default,
        if (type == "TEXT") "text" else null,
        isMain,
        1,
        1,
        null,
    )

    private fun option(
        id: String,
        fieldId: String,
        position: Int,
        label: String,
        archived: Boolean = false,
    ) = SequenceTemplateCategoryOptionEntity(id, fieldId, position, label, archived)

    private fun step(
        id: String,
        owner: String,
        parent: String?,
        position: Int,
        snapshot: String,
    ) = SequenceNodeEntity(id, owner, "STEP", parent, position, snapshot, null)

    private fun repeat(
        id: String,
        owner: String,
        position: Int,
        count: Int,
    ) = SequenceNodeEntity(id, owner, "REPEAT", null, position, null, count)

    private fun insertSnapshot(id: String) = snapshots.insertAggregate(snapshotAggregate(id, locallyModified = false))

    private fun snapshotAggregate(
        id: String,
        locallyModified: Boolean,
    ) = ActivitySnapshotAggregateEntity(
        snapshot = ActivitySnapshotEntity(id, id, null, "STOPWATCH", null, null, null, null, locallyModified, 1),
        settings = ActivitySnapshotSettingsEntity(id),
    )

    private fun insertRunningExecution(
        id: String,
        snapshotId: String,
    ) {
        database.activityExecutionDao().insertAggregate(
            ActivityExecutionAggregateEntity(
                ActivityExecutionEntity(
                    id,
                    snapshotId,
                    "STANDALONE",
                    null,
                    null,
                    null,
                    null,
                    "RUNNING",
                    10,
                    null,
                    null,
                    "UTC",
                    0,
                    "2026-08-15",
                    null,
                    null,
                    10,
                    10,
                ),
            ),
        )
    }

    private fun insertFieldSql(
        id: String,
        type: String = "NUMBER",
        main: Int = 0,
        deletedAt: Long? = null,
    ) {
        val deleted = deletedAt?.toString() ?: "NULL"
        sql(
            "INSERT INTO sequence_template_fields " +
                "(id, sequence_template_id, position, name, field_type, is_main_value, " +
                "created_at_ms, updated_at_ms, deleted_at_ms) " +
                "VALUES ('$id', 'sequence', 0, '$id', '$type', $main, 1, 1, $deleted)",
        )
    }

    private fun sql(statement: String) = database.openHelper.writableDatabase.execSQL(statement)
}
