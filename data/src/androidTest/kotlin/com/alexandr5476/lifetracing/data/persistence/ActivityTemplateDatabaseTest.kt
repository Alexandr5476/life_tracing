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
class ActivityTemplateDatabaseTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var templates: ActivityTemplateDao

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        templates = database.activityTemplateDao()
        database.statisticsSeriesDao().insert(StatisticsSeriesEntity("series", "ACTIVITY", "Series", 10, null))
        database.folderDao().insert(FolderEntity("folder", "Folder", null, 10, 10))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun completeAggregateRoundTripArchiveAndRestorePreserveIdentityAndOwnedRows() {
        val aggregate =
            ActivityTemplateAggregateEntity(
                template = template(mode = "TIMER", timerTargetMs = 1_500, folderId = "folder"),
                settings = settings(showSeconds = false, zeroBehavior = "OVERTIME"),
                fields = listOf(field("number", position = 2, isMain = true)),
                userState = ActivityTemplateUserStateEntity("template", 4, 123),
            )

        templates.insertAggregate(aggregate)

        assertEquals(aggregate.template, templates.getById("template"))
        assertEquals(aggregate.settings, templates.getSettings("template"))
        assertEquals(aggregate.fields, templates.getActiveFields("template"))
        assertEquals(aggregate.userState, templates.getUserState("template"))
        assertEquals(1, templates.archive("template", 999))
        assertEquals("series", templates.getById("template")?.statisticsSeriesId)
        assertEquals(aggregate.settings, templates.getSettings("template"))
        assertEquals(1, templates.restore("template"))
        assertNull(templates.getById("template")?.deletedAtMs)
    }

    @Test
    fun trackingCheckAcceptsOnlyFrozenModeAndTargetCombinations() {
        templates.insertAggregate(
            ActivityTemplateAggregateEntity(template("timer", "TIMER", 1), settings("timer")),
        )
        templates.insertAggregate(
            ActivityTemplateAggregateEntity(template("stopwatch", "STOPWATCH", null), settings("stopwatch")),
        )
        templates.insertAggregate(
            ActivityTemplateAggregateEntity(template("no-live", "NO_LIVE_TRACKING", null), settings("no-live")),
        )

        listOf(
            template("timer-null", "TIMER", null),
            template("timer-zero", "TIMER", 0),
            template("timer-negative", "TIMER", -1),
            template("stopwatch-target", "STOPWATCH", 1),
            template("no-live-target", "NO_LIVE_TRACKING", 1),
        ).forEach { invalid ->
            assertThrows(SQLiteConstraintException::class.java) { templates.insertTemplate(invalid) }
        }
    }

    @Test
    fun requiredRelationshipsRejectMissingRowsAndRestrictParentDeletes() {
        assertThrows(SQLiteConstraintException::class.java) {
            templates.insertTemplate(template(statisticsSeriesId = "missing"))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            templates.insertTemplate(template(folderId = "missing"))
        }
        templates.insertAggregate(ActivityTemplateAggregateEntity(template(folderId = "folder"), settings()))

        assertThrows(SQLiteConstraintException::class.java) { sql("DELETE FROM folders WHERE id = 'folder'") }
        assertThrows(SQLiteConstraintException::class.java) { sql("DELETE FROM statistics_series WHERE id = 'series'") }
    }

    @Test
    fun fieldsAreOrderedSoftArchivedAndMainValueIsEnforcedByPartialIndex() {
        templates.insertAggregate(ActivityTemplateAggregateEntity(template(), settings()))
        templates.insertFields(
            listOf(
                field("later", position = 2),
                field("first-main", position = 1, isMain = true),
            ),
        )

        assertEquals(listOf("first-main", "later"), templates.getActiveFields("template").map { it.id })
        assertThrows(SQLiteConstraintException::class.java) {
            templates.insertFields(listOf(field("second-main", position = 3, isMain = true)))
        }

        assertEquals(1, templates.archiveField("first-main", 100))
        templates.insertFields(listOf(field("second-main", position = 3, isMain = true)))
        assertEquals(listOf("later", "second-main"), templates.getActiveFields("template").map { it.id })
        assertEquals(3, templates.getAllFields("template").size)
    }

    @Test
    fun categoryOptionsKeepIdentityAllowDuplicateLabelsAndCascadeWithField() {
        templates.insertAggregate(ActivityTemplateAggregateEntity(template(), settings()))
        templates.insertFields(listOf(field("category", fieldType = "CATEGORY")))
        val first = ActivityTemplateCategoryOptionEntity("one", "category", 1, "Same", false)
        val second = ActivityTemplateCategoryOptionEntity("two", "category", 2, "Same", false)
        templates.insertOptions(listOf(first, second))

        templates.updateOptions(listOf(first.copy(label = "Renamed")))
        templates.archiveOption("two")

        assertEquals("one", templates.getCategoryOptions("category")[0].id)
        assertEquals("Renamed", templates.getCategoryOptions("category")[0].label)
        assertTrue(templates.getCategoryOptions("category")[1].isArchived)
        sql("DELETE FROM activity_template_fields WHERE id = 'category'")
        assertTrue(templates.getCategoryOptions("category").isEmpty())
    }

    @Test
    fun tagLinksAndUserStateAreMetadataAndFollowCascadeRules() {
        database.tagDao().insert(TagEntity("tag", "Tag", 1, 1))
        database.tagDao().insert(TagEntity("survivor", "Survivor", 1, 1))
        templates.insertAggregate(ActivityTemplateAggregateEntity(template(revision = 7), settings()))
        templates.insertTagLinks(listOf(ActivityTemplateTagEntity("template", "tag")))
        templates.insertTagLinks(listOf(ActivityTemplateTagEntity("template", "survivor")))
        templates.insertUserState(ActivityTemplateUserStateEntity("template", 1, 20))

        assertThrows(SQLiteConstraintException::class.java) {
            templates.insertTagLinks(listOf(ActivityTemplateTagEntity("template", "tag")))
        }
        templates.updateUserState(ActivityTemplateUserStateEntity("template", 2, 30))
        database.tagDao().deleteById("tag")

        assertNotNull(templates.getById("template"))
        assertEquals(7L, templates.getById("template")?.revision)
        assertEquals(listOf("survivor"), templates.getTagIds("template"))
        assertEquals(2, templates.getUserState("template")?.pinnedRank)

        sql("DELETE FROM activity_templates WHERE id = 'template'")
        assertTrue(templates.getTagIds("template").isEmpty())
        assertNull(templates.getUserState("template"))
        assertNotNull(database.tagDao().getById("survivor"))
    }

    @Test
    fun failedChildInsertRollsBackWholeAggregate() {
        val invalidOption = ActivityTemplateCategoryOptionEntity("option", "missing-field", 0, "Option", false)

        assertThrows(SQLiteConstraintException::class.java) {
            templates.insertAggregate(
                ActivityTemplateAggregateEntity(template(), settings(), options = listOf(invalidOption)),
            )
        }

        assertNull(templates.getById("template"))
        assertNull(templates.getSettings("template"))
    }

    @Test
    fun freshSchemaContainsChecksPartialIndexAndStableSQLiteStorage() {
        templates.insertAggregate(
            ActivityTemplateAggregateEntity(
                template(mode = "TIMER", timerTargetMs = 1_000),
                settings = settings(showSeconds = false, zeroBehavior = "OVERTIME"),
                fields = listOf(field("number", defaultNumberScaled = 12_345)),
            ),
        )

        query(
            "SELECT time_tracking_mode, typeof(time_tracking_mode), typeof(timer_target_ms) FROM activity_templates",
        ) { cursor ->
            assertEquals("TIMER", cursor.getString(0))
            assertEquals("text", cursor.getString(1))
            assertEquals("integer", cursor.getString(2))
        }
        query(
            "SELECT show_seconds, typeof(show_seconds), timer_zero_behavior FROM activity_template_settings",
        ) { cursor ->
            assertEquals(0, cursor.getInt(0))
            assertEquals("integer", cursor.getString(1))
            assertEquals("OVERTIME", cursor.getString(2))
        }
        query("SELECT typeof(default_number_scaled) FROM activity_template_fields") { cursor ->
            assertEquals("integer", cursor.getString(0))
        }
        query(
            "SELECT sql FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'idx_activity_template_one_main_field'",
        ) { cursor ->
            assertTrue(cursor.getString(0).contains("WHERE `is_main_value` = 1 AND `deleted_at_ms` IS NULL"))
        }
        query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'activity_templates'") { cursor ->
            assertTrue(cursor.getString(0).contains("CHECK"))
        }
    }

    @Test
    fun hardDeleteTemplateCascadesSettingsFieldsAndOptions() {
        templates.insertAggregate(
            ActivityTemplateAggregateEntity(
                template = template(),
                settings = settings(),
                fields = listOf(field("category", fieldType = "CATEGORY")),
                options = listOf(ActivityTemplateCategoryOptionEntity("option", "category", 0, "Option", false)),
            ),
        )

        sql("DELETE FROM activity_templates WHERE id = 'template'")

        assertNull(templates.getSettings("template"))
        assertTrue(templates.getAllFields("template").isEmpty())
        assertTrue(templates.getCategoryOptions("category").isEmpty())
    }

    private fun template(
        id: String = "template",
        mode: String = "STOPWATCH",
        timerTargetMs: Long? = null,
        statisticsSeriesId: String = "series",
        revision: Long = 1,
        folderId: String? = null,
    ) = ActivityTemplateEntity(
        id,
        id,
        "comment",
        mode,
        timerTargetMs,
        statisticsSeriesId,
        revision,
        10,
        20,
        null,
        folderId,
    )

    private fun settings(
        id: String = "template",
        showSeconds: Boolean = true,
        zeroBehavior: String = "FINISH",
    ) = ActivityTemplateSettingsEntity(id, showSeconds, 0, zeroBehavior, true, true, false, false)

    private fun field(
        id: String,
        position: Int = 0,
        fieldType: String = "NUMBER",
        isMain: Boolean = false,
        defaultNumberScaled: Long? = null,
    ) = ActivityTemplateFieldEntity(
        id,
        "template",
        position,
        id,
        fieldType,
        null,
        null,
        defaultNumberScaled,
        null,
        null,
        isMain,
        10,
        20,
        null,
    )

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
