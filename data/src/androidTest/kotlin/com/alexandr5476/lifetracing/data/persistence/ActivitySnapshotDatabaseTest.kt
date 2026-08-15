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
class ActivitySnapshotDatabaseTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var snapshots: ActivitySnapshotDao

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        snapshots = database.activitySnapshotDao()
        database.statisticsSeriesDao().insert(StatisticsSeriesEntity("series", "ACTIVITY", "Series", 10, null))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun sourceLinkedAndSourceLessAggregatesRoundTripWithBoundedOrderedChildren() {
        insertSourceTemplate()
        val linked =
            aggregate(
                sourceTemplateId = "template",
                sourceRevision = 4,
                statisticsSeriesId = "series",
                fields =
                    listOf(
                        field("later", 2, sourceFieldId = null),
                        field("category", 1, "CATEGORY", sourceFieldId = "source-field"),
                    ),
                options =
                    listOf(
                        option("second", 2, sourceOptionId = "source-option"),
                        option("first", 1, sourceOptionId = null),
                    ),
            )
        snapshots.insertAggregate(linked)
        val loaded = snapshots.getAggregate("snapshot")

        assertNotNull(loaded)
        assertEquals(listOf("category", "later"), loaded?.fields?.map { it.id })
        assertEquals(listOf("first", "second"), loaded?.options?.map { it.id })
        assertEquals(linked.snapshot, loaded?.snapshot)
        assertEquals(linked.settings, loaded?.settings)

        val oneOff =
            aggregate(id = "one-off", sourceTemplateId = null, sourceRevision = null, statisticsSeriesId = null)
        snapshots.insertAggregate(oneOff)
        assertEquals(oneOff, snapshots.getAggregate("one-off"))
    }

    @Test
    fun trackingAndSettingsChecksRejectInvalidRows() {
        snapshots.insertAggregate(aggregate("timer", mode = "TIMER", timerTargetMs = 1))
        snapshots.insertAggregate(aggregate("stopwatch", mode = "STOPWATCH", timerTargetMs = null))
        snapshots.insertAggregate(aggregate("no-live", mode = "NO_LIVE_TRACKING", timerTargetMs = null))

        listOf(
            snapshot("timer-null", mode = "TIMER", timerTargetMs = null),
            snapshot("timer-zero", mode = "TIMER", timerTargetMs = 0),
            snapshot("timer-negative", mode = "TIMER", timerTargetMs = -1),
            snapshot("stopwatch-target", mode = "STOPWATCH", timerTargetMs = 1),
            snapshot("no-live-target", mode = "NO_LIVE_TRACKING", timerTargetMs = 1),
        ).forEach { invalid ->
            assertThrows(SQLiteConstraintException::class.java) { snapshots.insertSnapshot(invalid) }
        }

        snapshots.insertSnapshot(snapshot("bad-settings"))
        assertThrows(SQLiteConstraintException::class.java) {
            sql("INSERT INTO activity_snapshot_settings(snapshot_id) VALUES ('bad-settings')")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            snapshots.insertSettings(ActivitySnapshotSettingsEntity("bad-settings", startCountdownMs = -1))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            snapshots.insertSettings(
                ActivitySnapshotSettingsEntity("bad-settings", timerZeroBehavior = "UNKNOWN"),
            )
        }
        snapshots.insertSettings(ActivitySnapshotSettingsEntity("bad-settings"))
        assertNotNull(snapshots.getSettings("bad-settings"))
    }

    @Test
    fun sourceAndStatisticsForeignKeysRejectMissingParentsAndRestrictSeriesDelete() {
        assertThrows(SQLiteConstraintException::class.java) {
            snapshots.insertSnapshot(snapshot(sourceTemplateId = "missing"))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            snapshots.insertSnapshot(snapshot(statisticsSeriesId = "missing"))
        }
        snapshots.insertAggregate(aggregate())
        assertThrows(SQLiteConstraintException::class.java) {
            snapshots.insertFields(listOf(field("bad-source-field", 0, sourceFieldId = "missing")))
        }
        snapshots.insertFields(listOf(field("local-category", 0, "CATEGORY")))
        assertThrows(SQLiteConstraintException::class.java) {
            snapshots.insertOptions(
                listOf(option("bad-source-option", 0, "local-category", sourceOptionId = "missing")),
            )
        }
        snapshots.insertAggregate(aggregate(id = "series-linked", statisticsSeriesId = "series"))
        assertThrows(SQLiteConstraintException::class.java) {
            sql("DELETE FROM statistics_series WHERE id = 'series'")
        }

        snapshots.insertAggregate(aggregate(id = "nullable", statisticsSeriesId = null))
        assertNull(snapshots.getById("nullable")?.statisticsSeriesId)
    }

    @Test
    fun sourceHardPurgeNullsWeakLinksButPreservesFrozenAggregateAndSeries() {
        insertSourceTemplate()
        val aggregate =
            aggregate(
                sourceTemplateId = "template",
                sourceRevision = 4,
                statisticsSeriesId = "series",
                fields =
                    listOf(
                        field(
                            "category",
                            0,
                            "CATEGORY",
                            sourceFieldId = "source-field",
                            defaultCategoryOptionId = "snapshot-option",
                        ),
                    ),
                options = listOf(option("snapshot-option", 0, sourceOptionId = "source-option")),
            )
        snapshots.insertAggregate(aggregate)

        assertEquals(1, database.activityTemplateDao().archive("template", 100))
        assertEquals("template", snapshots.getById("snapshot")?.sourceTemplateId)
        assertEquals("source-field", snapshots.getFields("snapshot").single().sourceFieldId)
        assertEquals("source-option", snapshots.getOptions("snapshot").single().sourceOptionId)

        sql("DELETE FROM activity_templates WHERE id = 'template'")

        val loaded = snapshots.getAggregate("snapshot")
        assertNotNull(loaded)
        assertNull(loaded?.snapshot?.sourceTemplateId)
        assertEquals(4L, loaded?.snapshot?.sourceRevision)
        assertEquals("series", loaded?.snapshot?.statisticsSeriesId)
        assertNull(loaded?.fields?.single()?.sourceFieldId)
        assertNull(loaded?.options?.single()?.sourceOptionId)
        assertEquals("category", loaded?.fields?.single()?.nameAtCreation)
        assertEquals("snapshot-option", loaded?.fields?.single()?.defaultCategoryOptionId)
        assertEquals("snapshot-option", loaded?.options?.single()?.labelAtCreation)
        assertEquals(aggregate.settings, loaded?.settings)
    }

    @Test
    fun fieldsOptionsAndMainValueUseOwnershipAndPartialUniqueness() {
        snapshots.insertAggregate(aggregate())
        snapshots.insertFields(listOf(field("main", 0, isMain = true), field("plain", 1)))
        assertThrows(SQLiteConstraintException::class.java) {
            snapshots.insertFields(listOf(field("second-main", 2, isMain = true)))
        }

        snapshots.insertOptions(listOf(option("owned", 0, fieldId = "plain")))
        sql("DELETE FROM activity_snapshot_fields WHERE id = 'plain'")
        assertTrue(snapshots.getOptions("snapshot").isEmpty())

        snapshots.hardDelete("snapshot")
        assertNull(snapshots.getSettings("snapshot"))
        assertTrue(snapshots.getFields("snapshot").isEmpty())
    }

    @Test
    fun identityCollisionAndFailedChildInsertRollBackAggregate() {
        snapshots.insertAggregate(aggregate("existing"))
        assertThrows(SQLiteConstraintException::class.java) {
            snapshots.insertAggregate(aggregate("existing"))
        }

        val invalid =
            aggregate(
                "failed",
                fields = listOf(field("category", 0, "CATEGORY", snapshotId = "failed")),
                options = listOf(option("bad", 0, sourceOptionId = "missing")),
            )
        assertThrows(SQLiteConstraintException::class.java) { snapshots.insertAggregate(invalid) }
        assertNull(snapshots.getById("failed"))
        assertNull(snapshots.getSettings("failed"))
        assertTrue(snapshots.getFields("failed").isEmpty())
        assertTrue(snapshots.getOptions("failed").isEmpty())

        val duplicateFields =
            aggregate(
                "duplicate-child",
                fields =
                    listOf(
                        field("same", 0, snapshotId = "duplicate-child"),
                        field("same", 1, snapshotId = "duplicate-child"),
                    ),
            )
        assertThrows(SQLiteConstraintException::class.java) { snapshots.insertAggregate(duplicateFields) }
        assertNull(snapshots.getById("duplicate-child"))
    }

    @Test
    fun freshSchemaStoresStableTextEnumsScaledIntegerAndManualInvariants() {
        snapshots.insertAggregate(
            aggregate(
                mode = "TIMER",
                timerTargetMs = 1_000,
                settings = ActivitySnapshotSettingsEntity("snapshot", false, 5, "OVERTIME"),
                fields = listOf(field("number", 0, defaultNumberScaled = 12_345)),
            ),
        )

        query(
            "SELECT time_tracking_mode, typeof(time_tracking_mode), typeof(timer_target_ms) FROM activity_snapshots",
        ) { cursor ->
            assertEquals("TIMER", cursor.getString(0))
            assertEquals("text", cursor.getString(1))
            assertEquals("integer", cursor.getString(2))
        }
        query("SELECT timer_zero_behavior, typeof(show_seconds) FROM activity_snapshot_settings") { cursor ->
            assertEquals("OVERTIME", cursor.getString(0))
            assertEquals("integer", cursor.getString(1))
        }
        query("SELECT typeof(default_number_scaled) FROM activity_snapshot_fields") { cursor ->
            assertEquals("integer", cursor.getString(0))
        }
        assertEquals(
            EXPECTED_ACTIVITY_SNAPSHOT_MANUAL_SCHEMA,
            database.openHelper.readableDatabase.readActivitySnapshotManualSchema(),
        )
        assertEquals(
            EXPECTED_ACTIVITY_TEMPLATE_MANUAL_SCHEMA,
            database.openHelper.readableDatabase.readActivityTemplateManualSchema(),
        )
    }

    private fun insertSourceTemplate() {
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                template =
                    ActivityTemplateEntity(
                        "template",
                        "Template",
                        null,
                        "STOPWATCH",
                        null,
                        "series",
                        4,
                        10,
                        20,
                        null,
                        null,
                    ),
                settings = ActivityTemplateSettingsEntity("template"),
                fields =
                    listOf(
                        ActivityTemplateFieldEntity(
                            "source-field",
                            "template",
                            0,
                            "Source field",
                            "CATEGORY",
                            null,
                            null,
                            null,
                            "source-option",
                            null,
                            false,
                            10,
                            20,
                            null,
                        ),
                    ),
                options =
                    listOf(
                        ActivityTemplateCategoryOptionEntity(
                            "source-option",
                            "source-field",
                            0,
                            "Source option",
                            false,
                        ),
                    ),
            ),
        )
    }

    private fun aggregate(
        id: String = "snapshot",
        mode: String = "STOPWATCH",
        timerTargetMs: Long? = null,
        sourceTemplateId: String? = null,
        sourceRevision: Long? = null,
        statisticsSeriesId: String? = null,
        settings: ActivitySnapshotSettingsEntity = ActivitySnapshotSettingsEntity(id),
        fields: List<ActivitySnapshotFieldEntity> = emptyList(),
        options: List<ActivitySnapshotCategoryOptionEntity> = emptyList(),
    ) = ActivitySnapshotAggregateEntity(
        snapshot(id, mode, timerTargetMs, sourceTemplateId, sourceRevision, statisticsSeriesId),
        settings,
        fields,
        options,
    )

    private fun snapshot(
        id: String = "snapshot",
        mode: String = "STOPWATCH",
        timerTargetMs: Long? = null,
        sourceTemplateId: String? = null,
        sourceRevision: Long? = null,
        statisticsSeriesId: String? = null,
    ) = ActivitySnapshotEntity(
        id,
        id,
        "comment",
        mode,
        timerTargetMs,
        sourceTemplateId,
        sourceRevision,
        statisticsSeriesId,
        false,
        10,
    )

    private fun field(
        id: String,
        position: Int,
        fieldType: String = "NUMBER",
        sourceFieldId: String? = null,
        isMain: Boolean = false,
        defaultNumberScaled: Long? = null,
        defaultCategoryOptionId: String? = null,
        snapshotId: String = "snapshot",
    ) = ActivitySnapshotFieldEntity(
        id,
        snapshotId,
        sourceFieldId,
        position,
        id,
        null,
        fieldType,
        null,
        null,
        defaultNumberScaled,
        defaultCategoryOptionId,
        null,
        isMain,
    )

    private fun option(
        id: String,
        position: Int,
        fieldId: String = "category",
        sourceOptionId: String? = null,
    ) = ActivitySnapshotCategoryOptionEntity(id, fieldId, sourceOptionId, position, id, null)

    private fun sql(statement: String) = database.openHelper.writableDatabase.execSQL(statement)

    private fun query(
        statement: String,
        assertion: (android.database.Cursor) -> Unit,
    ) {
        database.openHelper.readableDatabase.query(statement).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertion(cursor)
            assertFalse(cursor.moveToNext())
        }
    }
}
