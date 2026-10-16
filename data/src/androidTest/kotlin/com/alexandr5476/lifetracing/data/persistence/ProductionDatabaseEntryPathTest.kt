@file:Suppress("LongMethod", "TooManyFunctions", "MaxLineLength")
// Physical lifecycle and failure boundaries are explicit acceptance fixtures.

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeries
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.Tag
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ProductionDatabaseEntryPathTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = listOf("s1-production-fresh", "s1-production-v1", "s1-unsupported", "s1-late-migration-failure")
    private val createdAt = Instant.ofEpochMilli(1_700_000_000_123)
    private val updatedAt = Instant.ofEpochMilli(1_700_000_010_456)
    private val parent = Folder(FolderId("s1-parent-17"), "Historical parent", null, createdAt, updatedAt)
    private val child =
        Folder(
            FolderId("s1-child-29"),
            "Historical child",
            parent.id,
            createdAt.plusMillis(321),
            updatedAt.plusMillis(654),
        )
    private val tag = Tag(TagId("s1-tag-43"), "Historical focus", createdAt.plusMillis(777), updatedAt.plusMillis(888))
    private val series =
        StatisticsSeries(
            StatisticsSeriesId("s1-series-61"),
            StatisticsSeriesKind.ACTIVITY,
            "Historical independent series",
            createdAt.plusMillis(999),
            updatedAt.plusMillis(111),
        )

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun cleanBefore() = names.forEach(context::deleteDatabase)

    @After
    fun cleanAfter() = names.forEach(context::deleteDatabase)

    @Test
    fun freshAndAuthenticV1ProductionPathsShareIntegrityAndDurableCanonicalState() {
        assertFalse(context.getDatabasePath(names[0]).exists())
        createHistoricalV1(names[1])
        val freshShape = initializeAndReopen(names[0], historical = false)
        val upgradedShape = initializeAndReopen(names[1], historical = true)
        assertEquals(freshShape, upgradedShape)
    }

    @Test
    fun unsupportedVersionRefusesRepeatedOpeningWithoutReplacingCommittedFacts() {
        createHistoricalV1(names[2])
        independent(names[2], writable = true).use { it.version = 11 }
        val original = sourceState(names[2])
        repeat(2) {
            withDatabase(names[2]) { database ->
                val failure = assertThrows(IllegalStateException::class.java) { database.openHelper.writableDatabase }
                assertTrue(failure.message.orEmpty().contains("11 to 10"))
            }
            assertEquals(original, sourceState(names[2]))
            assertSourceFacts(names[2], expectedVersion = 11)
        }
    }

    @Test
    fun lateFullChainMigrationFailureRollsBackAllEarlierVersionsAndRemainsNonDestructiveOnRetry() {
        createHistoricalV1(names[3])
        // Deliberately damaged source, separate from the valid historical acceptance fixture.
        // Migrations 1..9 can run; v10 then hits this globally conflicting index name.
        independent(names[3], writable = true).use {
            it.execSQL("CREATE INDEX activity_executions_history_latest_root ON tags(name)")
        }
        val original = sourceState(names[3])
        repeat(2) {
            withDatabase(names[3]) { database ->
                val failure = assertThrows(SQLiteException::class.java) { database.openHelper.writableDatabase }
                assertTrue(failure.message.orEmpty().contains("activity_executions_history_latest_root"))
                assertTrue(failure.message.orEmpty().contains("already exists"))
            }
            assertEquals(
                "Migration DDL, identity hash and data must roll back together",
                original,
                sourceState(names[3]),
            )
            assertSourceFacts(names[3], expectedVersion = 1)
        }
    }

    private fun createHistoricalV1(name: String) {
        helper.createDatabase(name, 1).use { source ->
            assertEquals(1, source.version)
            assertEquals(setOf("folders", "tags", "statistics_series"), source.readCurrentIntegrity().keys)
            listOf(parent, child).forEach { folder ->
                source.execSQL(
                    "INSERT INTO folders VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        folder.id.value,
                        folder.name,
                        folder.parentFolderId?.value,
                        folder.createdAt.toEpochMilli(),
                        folder.updatedAt.toEpochMilli(),
                    ),
                )
            }
            source.execSQL(
                "INSERT INTO tags VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(tag.id.value, tag.name, tag.createdAt.toEpochMilli(), tag.updatedAt.toEpochMilli()),
            )
            source.execSQL(
                "INSERT INTO statistics_series VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    series.id.value,
                    series.kind.name,
                    series.displayName,
                    series.createdAt.toEpochMilli(),
                    series.archivedAt?.toEpochMilli(),
                ),
            )
        }
        assertSourceFacts(name, expectedVersion = 1)
    }

    private fun initializeAndReopen(
        name: String,
        historical: Boolean,
    ): Map<String, CurrentTableIntegrity> {
        val shape =
            withDatabase(name) { database ->
                val sqlite = database.openHelper.writableDatabase
                assertTrue(context.getDatabasePath(name).isFile)
                val initialized = sqlite.assertCurrentIntegrity()
                assertCanonicalInfrastructure(database, historical, expectedSeriesCount = if (historical) 2 else 1)
                initialized
            }
        // Acquire new repositories only after the original physical connection has closed.
        val (authored, committedFacts) =
            withDatabase(name) { reopened ->
                assertEquals(shape, reopened.openHelper.readableDatabase.assertCurrentIntegrity())
                assertCanonicalInfrastructure(reopened, historical, expectedSeriesCount = if (historical) 2 else 1)
                val template =
                    author(reopened).createActivityTemplate(
                        ActivityTemplateDraft(
                            "Persisted timer",
                            "Created after initialization",
                            TimeTrackingMode.TIMER,
                            Duration.ofSeconds(73),
                            settings =
                                ActivityTemplateSettings(
                                    startCountdown = Duration.ofSeconds(3),
                                    keepScreenAwake = true,
                                ),
                            fields =
                                listOf(
                                    ActivityFieldDraft(
                                        DraftIdentity.New("distance"),
                                        0,
                                        "Distance",
                                        CustomFieldType.NUMBER,
                                        "km",
                                        3,
                                        12_345,
                                        isMainValue = true,
                                    ),
                                ),
                        ),
                        createdAt = updatedAt.plusSeconds(10),
                    )
                assertEquals(ActivityTemplateId("s1-authored-activity"), template.id)
                assertEquals(StatisticsSeriesId("s1-authored-series"), template.statisticsSeriesId)
                assertConstraintEnforcement(reopened.openHelper.writableDatabase, template)
                template to reopened.openHelper.readableDatabase.fixtureFacts()
            }
        repeat(2) {
            withDatabase(name) { reopened ->
                assertEquals(shape, reopened.openHelper.readableDatabase.assertCurrentIntegrity())
                assertCanonicalInfrastructure(reopened, historical, expectedSeriesCount = if (historical) 3 else 2)
                assertEquals(authored, author(reopened).getActivityTemplate(authored.id))
                assertEquals(committedFacts, reopened.openHelper.readableDatabase.fixtureFacts())
                val library = LibraryRepository.create(reopened)
                val row = library.getAll().single()
                assertEquals(LibraryTemplateId.Activity(authored.id), row.id)
                assertEquals(authored.name, row.name)
                assertEquals(authored.shortComment, row.shortComment)
                val target = library.getLaunchTarget(row.id) as LibraryLaunchTarget.Activity
                assertEquals(authored.timeTrackingMode, target.timeTrackingMode)
                assertEquals(authored.settings.startCountdown, target.startCountdown)
                assertEquals(authored.revision, target.revision)
                assertEquals(authored.fields.single().id, target.mainValue?.fieldId)
                assertEquals(12_345L, target.mainValue?.defaultNumberScaled)
                val catalogItem = library.getReusableActivityCatalog(limit = 5).single()
                assertEquals(authored.id, catalogItem.id)
                assertEquals(authored.timerTarget, catalogItem.timerTarget)
                assertEquals("km", catalogItem.mainValueUnit)
                assertEquals(3, catalogItem.mainValueDisplayPrecision)
                assertNull(row.lastUsedAt)
            }
        }
        return shape
    }

    private fun author(database: LifeTracingDatabase): TemplateAuthoringRepository =
        TemplateAuthoringRepository(
            database,
            TemplateAuthoringIds.random().copy(
                nextActivityTemplateId = { ActivityTemplateId("s1-authored-activity") },
                nextStatisticsSeriesId = { StatisticsSeriesId("s1-authored-series") },
                nextActivityTemplateFieldId = { ActivityTemplateFieldId("s1-authored-field") },
            ),
        )

    private fun assertCanonicalInfrastructure(
        database: LifeTracingDatabase,
        historical: Boolean,
        expectedSeriesCount: Int,
    ) {
        val library = LibraryRepository.create(database)
        val statistics = StatisticsRepository(database) { error("Read must not create a Series") }
        assertEquals(if (historical) setOf(parent, child) else emptySet<Folder>(), library.getFolders().toSet())
        assertEquals(if (historical) listOf(tag) else emptyList<Tag>(), library.getTags())
        if (historical) {
            assertEquals(listOf(parent, child), library.getFolderPath(child.id))
            val read = statistics.activitySeries(series.id, StatisticsPeriod.AllTime).series
            assertEquals(series.id, read.id)
            assertEquals(series.kind, read.kind)
            assertEquals(series.displayName, read.displayName)
            assertEquals(series.archivedAt, read.archivedAt)
            // Summary intentionally omits creation time; supplement the canonical read for that frozen fact.
            assertEquals(series, database.statisticsSeriesDao().getById(series.id.value)?.toDomain())
        }
        val catalog = statistics.seriesCatalog()
        assertEquals(expectedSeriesCount, catalog.size)
        assertEquals(1, catalog.count { it.id == ActivityExecutionStatistics.ONE_OFF_BUCKET_ID })
        assertEquals(1, catalog.count { it.kind == StatisticsSeriesKind.ONE_OFF_BUCKET })
        assertEquals(
            StatisticsSeries(
                ActivityExecutionStatistics.ONE_OFF_BUCKET_ID,
                StatisticsSeriesKind.ONE_OFF_BUCKET,
                "One-off activities",
                Instant.EPOCH,
                null,
            ),
            database.statisticsSeriesDao().getById(ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value)?.toDomain(),
        )
        val zone = CurrentZoneIdProvider { ZoneOffset.UTC }
        val date = LocalDate.of(2026, 10, 12)
        val daily = DailyReadRepository(database, zone).getDaily(DailyQuery(date, updatedAt, 5))
        assertTrue(daily.dayPlans.isEmpty())
        assertTrue(daily.weekPlans.isEmpty())
        assertTrue(daily.completedHistory.isEmpty())
        assertNull(daily.active)
        val history = HistoryReadRepository(database)
        assertTrue(history.getCompletedRoots(CompletedHistoryQuery(HistoryDateRange(date, date), 5)).isEmpty())
        assertNull(history.getLatestCompletedPrimaryLocalDate(updatedAt, ZoneOffset.UTC))
        val plan = PlanReadRepository(database, zone).getWeek(WeekPlanQuery(date, date, updatedAt))
        assertTrue(plan.selectedDayPlans.isEmpty())
        assertTrue(plan.weekPlans.isEmpty())
        assertTrue(plan.dayPresence.all { it.count == 0 })
        assertNull(LiveSessionRepository.create(database).getActiveSession())
        assertNull(LiveSessionRepository.create(database).getActiveRuntime())
    }

    private fun assertConstraintEnforcement(
        sqlite: SupportSQLiteDatabase,
        template: ActivityTemplate,
    ) {
        val original = sqlite.fixtureFacts()
        val id = template.id.value
        listOf(
            "UPDATE activity_templates SET folder_id = 'missing-folder' WHERE id = '$id'",
            "DELETE FROM statistics_series WHERE id = '${template.statisticsSeriesId.value}'",
            "UPDATE activity_templates SET timer_target_ms = 0 WHERE id = '$id'",
            "UPDATE activity_template_settings SET start_countdown_ms = -1 WHERE activity_template_id = '$id'",
            "INSERT INTO activity_template_fields (id, activity_template_id, position, name, field_type, is_main_value, created_at_ms, updated_at_ms) VALUES ('rejected-main', '$id', 1, 'Second main', 'NUMBER', 1, 100, 100)",
        ).forEach { statement ->
            assertThrows(SQLiteConstraintException::class.java) { sqlite.execSQL(statement) }
            assertEquals("Failed statement changed durable fixture: $statement", original, sqlite.fixtureFacts())
        }
        // Positive boundary evidence: archived Main Values and non-main active Fields are outside the predicate.
        sqlite.beginTransaction()
        try {
            sqlite.execSQL(
                "INSERT INTO activity_template_fields (id, activity_template_id, position, name, field_type, is_main_value, created_at_ms, updated_at_ms, deleted_at_ms) VALUES ('archived-main', '$id', 1, 'Archived main', 'NUMBER', 1, 100, 100, 101)",
            )
            sqlite.execSQL(
                "INSERT INTO activity_template_fields (id, activity_template_id, position, name, field_type, is_main_value, created_at_ms, updated_at_ms) VALUES ('non-main', '$id', 2, 'Non-main', 'NUMBER', 0, 100, 100)",
            )
            sqlite.query("SELECT COUNT(*) FROM activity_template_fields").use {
                it.moveToFirst()
                assertEquals(3, it.getInt(0))
            }
        } finally {
            sqlite.endTransaction() // Roll back probe rows; retain only the production-authored aggregate.
        }
        assertEquals(original, sqlite.fixtureFacts())
        sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private fun SupportSQLiteDatabase.fixtureFacts(): Map<String, List<List<String?>>> =
        readCurrentIntegrity().keys.associateWith { table ->
            query("SELECT * FROM `$table` ORDER BY 1").use { it.rows() }
        }

    private fun open(name: String): LifeTracingDatabase =
        LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()

    private inline fun <T> withDatabase(
        name: String,
        block: (LifeTracingDatabase) -> T,
    ): T {
        val database = open(name)
        try {
            return block(database)
        } finally {
            database.close()
        }
    }

    private fun independent(
        name: String,
        writable: Boolean = false,
    ): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).absolutePath,
            null,
            if (writable) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY,
        )

    private fun sourceState(name: String): Pair<Int, Map<String, List<List<String?>>>> =
        independent(name).use { sqlite ->
            sqlite.version to
                listOf(
                    "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name",
                    "SELECT * FROM folders ORDER BY id",
                    "SELECT * FROM tags ORDER BY id",
                    "SELECT * FROM statistics_series ORDER BY id",
                    "SELECT * FROM room_master_table ORDER BY id",
                ).associateWith { sql -> sqlite.rawQuery(sql, null).use { it.rows() } }
        }

    private fun assertSourceFacts(
        name: String,
        expectedVersion: Int,
    ) {
        independent(name).use { sqlite ->
            assertEquals(expectedVersion, sqlite.version)
            assertEquals(
                listOf(
                    listOf(
                        parent.id.value,
                        parent.name,
                        null,
                        parent.createdAt.toEpochMilli().toString(),
                        parent.updatedAt.toEpochMilli().toString(),
                    ),
                    listOf(
                        child.id.value,
                        child.name,
                        parent.id.value,
                        child.createdAt.toEpochMilli().toString(),
                        child.updatedAt.toEpochMilli().toString(),
                    ),
                ).toSet(),
                sqlite
                    .rawQuery("SELECT * FROM folders", null)
                    .use {
                        it.rows()
                    }.toSet(),
            )
            assertEquals(
                listOf(
                    listOf(
                        tag.id.value,
                        tag.name,
                        tag.createdAt.toEpochMilli().toString(),
                        tag.updatedAt.toEpochMilli().toString(),
                    ),
                ),
                sqlite.rawQuery("SELECT * FROM tags", null).use {
                    it.rows()
                },
            )
            assertEquals(
                listOf(
                    listOf(
                        series.id.value,
                        "ACTIVITY",
                        series.displayName,
                        series.createdAt.toEpochMilli().toString(),
                        series.archivedAt?.toEpochMilli().toString(),
                    ),
                ),
                sqlite.rawQuery("SELECT * FROM statistics_series", null).use {
                    it.rows()
                },
            )
            val tables =
                sqlite
                    .rawQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('room_master_table', 'android_metadata') AND name NOT LIKE 'sqlite_%'",
                        null,
                    ).use {
                        it.rows()
                    }
            assertEquals(setOf(listOf("folders"), listOf("tags"), listOf("statistics_series")), tables.toSet())
        }
    }

    private fun Cursor.rows(): List<List<String?>> =
        buildList { while (moveToNext()) add(List(columnCount) { if (isNull(it)) null else getString(it) }) }
}
