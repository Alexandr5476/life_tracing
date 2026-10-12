@file:Suppress("LongMethod", "TooManyFunctions", "LargeClass", "MaxLineLength")
// Acceptance uses authentic historical sources and the registered production entry path.

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionContext
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPause
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOption
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CategorySequenceExecutionValue
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.EffectiveSequenceStepSettings
import com.alexandr5476.lifetracing.domain.EffectiveSequenceStepSettingsResolver
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.LibraryLaunchTarget
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.NumberSequenceExecutionValue
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.RuntimeOccurrence
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceExecution
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryActualValue
import com.alexandr5476.lifetracing.domain.SequenceHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOption
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotField
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeries
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class HistoricalFrozenProductionMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = listOf("s2-frozen-v3", "s2-frozen-v6", "s2-terminal-v7")
    private val instant = Instant.parse("2020-01-01T22:30:00.123Z")
    private val date = LocalDate.of(2020, 1, 2)
    private val zone = ZoneId.of("Asia/Tokyo")
    private val overrides = SequenceStepOverrides(Duration.ZERO, TimerZeroBehavior.OVERTIME, false, true, true)
    private val activitySeriesId = StatisticsSeriesId("activity-series")
    private val sequenceSeriesId = StatisticsSeriesId("sequence-series")

    // Created before any upgrade. Expectations never originate in a migrated database.
    private val frozenActivity =
        ActivityConfigSnapshot(
            ActivitySnapshotId("activity-snapshot"),
            "Frozen activity",
            "Frozen comment",
            TimeTrackingMode.TIMER,
            Duration.ofSeconds(30),
            ActivityTemplateId("activity-template"),
            3,
            activitySeriesId,
            true,
            Instant.ofEpochMilli(15),
            ActivityTemplateSettings(false, Duration.ofSeconds(2), TimerZeroBehavior.OVERTIME, false, true, true, true),
            listOf(
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("f-number"),
                    ActivityTemplateFieldId("number"),
                    0,
                    "Captured amount",
                    "Local amount",
                    CustomFieldType.NUMBER,
                    "kg",
                    1,
                    1500,
                    isMainValue = true,
                ),
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("f-category"),
                    ActivityTemplateFieldId("category"),
                    1,
                    "Captured effort",
                    null,
                    CustomFieldType.CATEGORY,
                    defaultCategoryOptionId = ActivitySnapshotCategoryOptionId("f-option"),
                    categoryOptions =
                        listOf(
                            ActivitySnapshotCategoryOption(
                                ActivitySnapshotCategoryOptionId("f-option"),
                                CategoryOptionId("option"),
                                0,
                                "Captured option",
                                "Local option",
                            ),
                        ),
                ),
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("f-text"),
                    ActivityTemplateFieldId("text"),
                    2,
                    "Captured note",
                    null,
                    CustomFieldType.TEXT,
                    defaultText = "Frozen default",
                ),
            ),
        )
    private val frozenSequence =
        SequenceConfigSnapshot(
            SequenceSnapshotId("sequence-snapshot"),
            "Frozen sequence",
            "Frozen sequence comment",
            SequenceTemplateId("sequence-template"),
            2,
            sequenceSeriesId,
            Instant.ofEpochMilli(15),
            SequenceSnapshotSettings(
                true,
                Duration.ZERO,
                Duration.ZERO,
                false,
                true,
                true,
                true,
                true,
                NoLiveTimeAccounting.ACTIVE,
            ),
            listOf(
                SequenceSnapshotField(
                    SequenceSnapshotFieldId("sf-number"),
                    SequenceTemplateFieldId("s-number"),
                    0,
                    "Captured rounds",
                    "Local rounds",
                    CustomFieldType.NUMBER,
                    "reps",
                    0,
                    2000,
                    isMainValue = true,
                ),
                SequenceSnapshotField(
                    SequenceSnapshotFieldId("sf-category"),
                    SequenceTemplateFieldId("s-category"),
                    1,
                    "Captured quality",
                    null,
                    CustomFieldType.CATEGORY,
                    defaultCategoryOptionId = SequenceSnapshotCategoryOptionId("sf-option"),
                    categoryOptions =
                        listOf(
                            SequenceSnapshotCategoryOption(
                                SequenceSnapshotCategoryOptionId("sf-option"),
                                SequenceTemplateCategoryOptionId("s-option"),
                                0,
                                "Captured quality option",
                                "Local quality option",
                            ),
                        ),
                ),
            ),
            listOf(
                SequenceSnapshotActivityStep(SequenceSnapshotNodeId("f-top"), 0, frozenActivity.id),
                SequenceSnapshotRepeatBlock(
                    SequenceSnapshotNodeId("f-repeat"),
                    1,
                    2,
                    listOf(
                        SequenceSnapshotActivityStep(
                            SequenceSnapshotNodeId("f-nested"),
                            0,
                            frozenActivity.id,
                            overrides,
                        ),
                    ),
                ),
            ),
        )
    private val timed =
        expectedActivity("timed", instant, instant.plusSeconds(60), Duration.ofSeconds(50)).copy(
            pauses =
                listOf(
                    ActivityExecutionPause(
                        ActivityExecutionPauseId("pause"),
                        instant.plusSeconds(10),
                        instant.plusSeconds(20),
                    ),
                ),
            values =
                listOf(
                    NumberExecutionValue(ActivitySnapshotFieldId("f-number"), 0),
                    CategoryExecutionValue(
                        ActivitySnapshotFieldId("f-category"),
                        ActivitySnapshotCategoryOptionId("f-option"),
                    ),
                    TextExecutionValue(ActivitySnapshotFieldId("f-text"), "Recorded note"),
                ),
        )
    private val zero =
        expectedActivity("zero", instant.plusSeconds(120), instant.plusSeconds(120), Duration.ZERO)
            .copy(originalUtcOffsetMinutes = null)
    private val noLive =
        expectedActivity(
            "no-live",
            null,
            instant.plusSeconds(180),
            null,
        ).copy(snapshotId = ActivitySnapshotId("no-live-snapshot"))
    private val children =
        (0..2).map { index ->
            expectedActivity(
                "child-$index",
                instant.plusSeconds(index * 40L),
                instant.plusSeconds(index * 40L + 30),
                Duration.ofSeconds(30),
            ).copy(
                context = ActivityExecutionContext.SEQUENCE_CHILD,
                sequenceExecutionId = SequenceExecutionId("sequence-execution"),
                sequenceOccurrenceId = SequenceOccurrenceId("occurrence-$index"),
                values = listOf(NumberExecutionValue(ActivitySnapshotFieldId("f-number"), 2000)),
            )
        }
    private val terminalSequence =
        SequenceExecution(
            SequenceExecutionId("sequence-execution"),
            frozenSequence.id,
            sequenceSeriesId,
            SequenceExecutionStatus.COMPLETED,
            instant,
            instant.plusSeconds(110),
            Duration.ofSeconds(90),
            Duration.ofSeconds(20),
            Duration.ofSeconds(110),
            zone,
            540,
            date,
            null,
            instant,
            instant.plusSeconds(110),
            occurrences =
                (0..2).map { index ->
                    RuntimeOccurrence(
                        SequenceOccurrenceId("occurrence-$index"),
                        SequenceSnapshotNodeId(if (index == 0) "f-top" else "f-nested"),
                        frozenActivity.id,
                        index,
                        if (index == 0) null else SequenceSnapshotNodeId("f-repeat"),
                        if (index == 0) null else index,
                        RuntimeOccurrenceStatus.COMPLETED,
                        instant.plusSeconds(index * 40L),
                        instant.plusSeconds(index * 40L + 30),
                        OccurrenceCompletionReason.MANUAL_FINISH,
                        false,
                        false,
                    )
                },
            intervals =
                listOf(
                    expectedInterval("active-0", SequenceIntervalKind.ACTIVE_STEP, 0, 30, 0),
                    expectedInterval("idle-0", SequenceIntervalKind.IMPLICIT_IDLE, 30, 40),
                    expectedInterval("active-1", SequenceIntervalKind.ACTIVE_STEP, 40, 70, 1),
                    expectedInterval("pause-1", SequenceIntervalKind.EXPLICIT_PAUSE, 70, 80),
                    expectedInterval("active-2", SequenceIntervalKind.ACTIVE_STEP, 80, 110, 2),
                ),
            values =
                listOf(
                    CategorySequenceExecutionValue(
                        SequenceSnapshotFieldId("sf-category"),
                        SequenceSnapshotCategoryOptionId("sf-option"),
                    ),
                    NumberSequenceExecutionValue(SequenceSnapshotFieldId("sf-number"), 0),
                ),
        )

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @Before
    fun cleanBefore() = names.forEach(context::deleteDatabase)

    @After
    fun cleanAfter() = names.forEach(context::deleteDatabase)

    @Test
    fun authenticV3FrozenActivitySurvivesProductionUpgradeAndRepeatedReopen() = acceptance(3, names[0])

    @Test
    fun authenticV6FrozenSequenceGraphAndStandaloneFactSurviveProductionUpgradeAndRepeatedReopen() =
        acceptance(6, names[1])

    @Test
    fun authenticV7TerminalHistoryAndStatisticsSurviveProductionUpgradeAndRepeatedReopen() = acceptance(7, names[2])

    private fun acceptance(
        version: Int,
        name: String,
    ) {
        val original =
            historical(version, name).use { source ->
                assertEquals(version, source.version)
                source.execSQL("PRAGMA foreign_keys = ON")
                source.seedFrozenActivityV3()
                if (version >= 6) {
                    source.seedFrozenSequenceV6()
                    source.seedStandaloneV4(instant.toEpochMilli())
                }
                if (version == 7) source.seedTerminalV7(instant.toEpochMilli())
                assertForeignKeys(source)
                source.historicalFacts()
            }
        // The source connection is closed. Every opening below uses the same physical file.
        var shape: Map<String, CurrentTableIntegrity>? = null
        repeat(3) { opening ->
            val database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            try {
                val sqlite = database.openHelper.writableDatabase
                assertTrue(context.getDatabasePath(name).isFile)
                val current = sqlite.assertCurrentIntegrity()
                if (opening == 0) shape = current else assertEquals(shape, current)
                // Supplement canonical hydration with every pre-upgrade column, including lifecycle metadata.
                original.forEach { (table, rows) ->
                    assertEquals("Historical facts changed in $table on opening $opening", rows, sqlite.factRows(table))
                }
                assertActivityConfiguration(database)
                if (version >= 6) assertSequenceConfiguration(database)
                if (version == 3) assertTrue(sqlite.factRows("activity_executions").isEmpty())
                if (version < 7) {
                    assertTrue(sqlite.factRows("sequence_executions").isEmpty())
                    assertTrue(sqlite.factRows("sequence_occurrences").isEmpty())
                    assertTrue(sqlite.factRows("sequence_intervals").isEmpty())
                }
                assertTrue(sqlite.factRows("plan_entries").isEmpty())
                assertTrue(sqlite.factRows("active_session").isEmpty())
                assertCanonicalHistory(database, version)
                assertStatistics(database, version)
                assertForeignKeys(sqlite)
            } finally {
                database.close()
            }
        }
    }

    private fun historical(
        version: Int,
        name: String,
    ): SupportSQLiteDatabase =
        when (version) {
            3 -> LifeTracingMigrationTestDatabaseFactory.createVersion3(helper, name)
            6 -> LifeTracingMigrationTestDatabaseFactory.createVersion6(helper, name)
            7 -> LifeTracingMigrationTestDatabaseFactory.createVersion7(helper, name)
            else -> error("Unsupported fixture version")
        }

    private fun assertActivityConfiguration(database: LifeTracingDatabase) {
        val author = TemplateAuthoringRepository(database, TemplateAuthoringIds.random())
        val template = requireNotNull(author.getActivityTemplate(ActivityTemplateId("activity-template")))
        assertEquals(9L, template.revision)
        assertEquals("Current activity", template.name)
        assertEquals("Current comment", template.shortComment)
        assertEquals(Duration.ofSeconds(90), template.timerTarget)
        assertEquals(activitySeriesId, template.statisticsSeriesId)
        assertEquals(Instant.ofEpochMilli(10), template.createdAt)
        assertEquals(Instant.ofEpochMilli(20), template.updatedAt)
        assertNull(template.deletedAt)
        assertEquals(FolderId("folder"), template.folderId)
        assertEquals(setOf(TagId("tag")), template.tagIds)
        assertEquals(ActivityTemplateSettings(), template.settings)
        assertEquals(listOf("number", "category", "text"), template.fields.map { it.id.value })
        assertEquals(
            listOf(
                CustomFieldType.NUMBER,
                CustomFieldType.CATEGORY,
                CustomFieldType.TEXT,
            ),
            template.fields.map {
                it.type
            },
        )
        assertEquals(9000L, template.fields[0].defaultNumberScaled)
        assertEquals(2, template.fields[0].displayPrecision)
        assertTrue(template.fields[0].isMainValue)
        assertEquals("Current amount", template.fields[0].name)
        assertNull(template.fields[1].defaultCategoryOptionId)
        assertEquals(
            "Current option",
            template.fields[1]
                .categoryOptions
                .single()
                .label,
        )
        assertTrue(
            template.fields[1]
                .categoryOptions
                .single()
                .isArchived,
        )
        assertEquals("Current default", template.fields[2].defaultText)
        assertEquals(
            frozenActivity,
            requireNotNull(database.activitySnapshotDao().getAggregate("activity-snapshot")).toDomain(),
        )
        val library = LibraryRepository.create(database)
        val row = library.getAll().single { it.id == LibraryTemplateId.Activity(template.id) }
        assertEquals(template.name, row.name)
        assertEquals(template.shortComment, row.shortComment)
        assertEquals(Instant.ofEpochMilli(20), row.lastUsedAt)
        val target = library.getLaunchTarget(row.id) as LibraryLaunchTarget.Activity
        assertEquals(9L, target.revision)
        assertEquals(template.timeTrackingMode, target.timeTrackingMode)
        assertEquals(ActivityTemplateFieldId("number"), target.mainValue?.fieldId)
        assertEquals(9000L, target.mainValue?.defaultNumberScaled)
        assertTrue(library.getPinned().any { it.id == row.id })
        assertEquals("Historical folder", library.getFolders().single().name)
        assertEquals("Historical tag", library.getTags().single().name)
    }

    private fun assertSequenceConfiguration(database: LifeTracingDatabase) {
        val author = TemplateAuthoringRepository(database, TemplateAuthoringIds.random())
        val state = requireNotNull(author.getSequenceTemplateAuthoringState(SequenceTemplateId("sequence-template")))
        val template = state.sequence
        assertEquals(8L, template.revision)
        assertEquals("Current sequence", template.name)
        assertEquals("Current sequence comment", template.shortComment)
        assertEquals(sequenceSeriesId, template.statisticsSeriesId)
        assertEquals(Instant.ofEpochMilli(10), template.createdAt)
        assertEquals(Instant.ofEpochMilli(20), template.updatedAt)
        assertNull(template.deletedAt)
        assertEquals(FolderId("folder"), template.folderId)
        assertEquals(setOf(TagId("tag")), template.tagIds)
        assertEquals(NoLiveTimeAccounting.PAUSE, template.noLiveTimeAccounting)
        assertFalse(template.settings.autoAdvance)
        assertEquals(2, template.userState.pinnedRank)
        assertEquals(Instant.ofEpochMilli(20), template.userState.lastUsedAt)
        assertEquals(9000L, template.fields[0].defaultNumberScaled)
        assertTrue(template.fields[0].isMainValue)
        assertEquals(SequenceTemplateFieldId("s-number"), template.fields[0].id)
        assertEquals(SequenceTemplateCategoryOptionId("s-option"), template.fields[1].defaultCategoryOptionId)
        assertEquals(
            listOf(
                ActivityStep(SequenceNodeId("top"), 0, frozenActivity.id),
                SequenceRepeatBlock(
                    SequenceNodeId("repeat"),
                    1,
                    4,
                    listOf(ActivityStep(SequenceNodeId("nested"), 0, frozenActivity.id, overrides)),
                ),
            ),
            template.nodes,
        )
        assertEquals(frozenActivity, author.getStepSnapshot(template.id, SequenceNodeId("nested")))
        val snapshot = requireNotNull(database.sequenceSnapshotDao().getAggregate("sequence-snapshot")).toDomain()
        assertEquals(frozenSequence, snapshot)
        val repeat = snapshot.nodes[1] as SequenceSnapshotRepeatBlock
        val effective =
            EffectiveSequenceStepSettingsResolver.resolve(
                repeat.children.single(),
                frozenActivity,
                snapshot.settings,
                false,
            )
        assertEquals(
            EffectiveSequenceStepSettings(Duration.ZERO, TimerZeroBehavior.OVERTIME, false, true, true),
            effective,
        )
        val library = LibraryRepository.create(database)
        val row = library.getAll().single { it.id == LibraryTemplateId.Sequence(template.id) }
        assertEquals(template.name, row.name)
        assertEquals(template.shortComment, row.shortComment)
        assertEquals(template.userState.lastUsedAt, row.lastUsedAt)
        assertTrue(library.getPinned().any { it.id == row.id })
    }

    private fun assertCanonicalHistory(
        database: LifeTracingDatabase,
        version: Int,
    ) {
        val history = HistoryReadRepository(database)
        val roots = history.getCompletedRoots(CompletedHistoryQuery(HistoryDateRange(date, date), 10))
        val expectedActivities =
            if (version ==
                3
            ) {
                emptyList()
            } else if (version == 6) {
                listOf(timed)
            } else {
                listOf(timed, zero, noLive)
            }
        assertEquals(expectedActivities.size + if (version == 7) 1 else 0, roots.size)
        expectedActivities.forEach { expected ->
            val loaded = requireNotNull(database.activityExecutionDao().getAggregate(expected.id.value)).toDomain()
            // DAO values are ordered by Field identity, separately from Field schema position.
            assertEquals(expected.copy(values = expected.values.sortedBy { it.snapshotFieldId.value }), loaded)
            val detail = requireNotNull(history.getActivityDetail(expected.id))
            assertEquals(expected.snapshotId, detail.root.snapshotId)
            assertEquals(expected.startedAt, detail.root.startedAt)
            assertEquals(expected.completedAt, detail.root.completedAt)
            assertEquals(expected.activeDuration, detail.root.activeDuration)
            assertEquals(date, detail.root.primaryLocalDate)
            assertEquals(zone, detail.root.originalZoneId)
            assertEquals(expected.originalUtcOffsetMinutes, detail.root.originalUtcOffsetMinutes)
            assertNull(detail.root.planEntryId)
            assertEquals(
                detail.root,
                roots.filterIsInstance<CompletedActivityHistoryRoot>().single {
                    it.executionId ==
                        expected.id
                },
            )
        }
        if (version >= 6) {
            val detail = requireNotNull(history.getActivityDetail(timed.id))
            assertEquals("Frozen activity", detail.root.title)
            assertEquals("Frozen comment", detail.root.shortComment)
            assertEquals(frozenActivity.settings, detail.settings)
            assertEquals(listOf("Local amount", "Captured effort", "Captured note"), detail.fields.map { it.name })
            assertEquals(ActivityHistoryConfiguredValue.Number(1500), detail.fields[0].configuredValue)
            assertEquals(ActivityHistoryActualValue.Number(0), detail.fields[0].actualValue)
            assertEquals(
                ActivityHistoryActualValue.Category(ActivitySnapshotCategoryOptionId("f-option"), "Local option"),
                detail.fields[1].actualValue,
            )
            assertEquals(ActivityHistoryActualValue.Text("Recorded note"), detail.fields[2].actualValue)
        }
        if (version == 7) {
            assertEquals(
                frozenActivity.copy(
                    id = noLive.snapshotId,
                    name = "Frozen no-live",
                    timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING,
                    timerTarget = null,
                    fields = emptyList(),
                    locallyModified = false,
                ),
                requireNotNull(database.activitySnapshotDao().getAggregate(noLive.snapshotId.value)).toDomain(),
            )
            assertNull(requireNotNull(history.getActivityDetail(noLive.id)).root.activeDuration)
            val zeroDetail = requireNotNull(history.getActivityDetail(zero.id))
            assertEquals(Duration.ZERO, zeroDetail.root.activeDuration)
            assertNull(zeroDetail.root.originalUtcOffsetMinutes)
            assertEquals(ActivityHistoryActualValue.Missing, zeroDetail.fields[0].actualValue)
            assertTerminalSequence(database, history, roots.filterIsInstance<CompletedSequenceHistoryRoot>().single())
        }
        val deviceZone = CurrentZoneIdProvider { ZoneId.of("America/Los_Angeles") }
        val daily = DailyReadRepository(database, deviceZone)
        val projection = daily.getDaily(DailyQuery(date, instant.plusSeconds(300), 10))
        assertEquals(roots, projection.completedHistory)
        assertNull(projection.active)
        assertTrue(projection.dayPlans.isEmpty())
        assertTrue(projection.weekPlans.isEmpty())
        val otherDate = date.minusDays(1)
        assertTrue(daily.getDaily(DailyQuery(otherDate, instant.plusSeconds(300), 10)).completedHistory.isEmpty())
        assertEquals(
            if (version ==
                3
            ) {
                null
            } else {
                date
            },
            history.getLatestCompletedPrimaryLocalDate(instant, ZoneId.of("America/Los_Angeles")),
        )
        val plan =
            PlanReadRepository(database, deviceZone).getWeek(
                WeekPlanQuery(LocalDate.of(2019, 12, 30), date, instant),
            )
        assertTrue(plan.selectedDayPlans.isEmpty())
        assertTrue(plan.weekPlans.isEmpty())
        assertTrue(plan.dayPresence.all { it.count == 0 })
        assertNull(LiveSessionRepository.create(database).getActiveSession())
        assertNull(LiveSessionRepository.create(database).getActiveRuntime())
    }

    private fun assertTerminalSequence(
        database: LifeTracingDatabase,
        history: HistoryReadRepository,
        root: CompletedSequenceHistoryRoot,
    ) {
        assertEquals(
            terminalSequence,
            requireNotNull(database.sequenceExecutionDao().getHistoryAggregate("sequence-execution")).toDomain(),
        )
        assertEquals(
            children,
            database
                .activityExecutionDao()
                .getSequenceChildAggregates("sequence-execution")
                .map {
                    it.toDomain()
                }.sortedBy { it.id.value },
        )
        val detail = requireNotNull(history.getSequenceDetail(terminalSequence.id))
        assertEquals(root, detail.root)
        assertEquals(terminalSequence.occurrences.size, detail.occurrences.size)
        assertEquals("Frozen sequence", root.title)
        assertEquals("Frozen sequence comment", root.shortComment)
        assertEquals(SequenceExecutionStatus.COMPLETED, root.status)
        assertEquals(terminalSequence.startedAt, root.startedAt)
        assertEquals(terminalSequence.endedAt, root.completedAt)
        assertEquals(date, root.primaryLocalDate)
        assertEquals(zone, root.originalZoneId)
        assertEquals(540, root.originalUtcOffsetMinutes)
        assertEquals(terminalSequence.activeDuration, root.activeDuration)
        assertEquals(terminalSequence.pauseDuration, root.pauseDuration)
        assertEquals(terminalSequence.wallDuration, root.wallDuration)
        assertNull(root.planEntryId)
        assertEquals(frozenSequence.settings, detail.settings)
        assertEquals(terminalSequence.intervals, detail.intervals)
        assertEquals("Local rounds", detail.fields[0].name)
        assertEquals(SequenceHistoryConfiguredValue.Number(2000), detail.fields[0].configuredValue)
        assertEquals(SequenceHistoryActualValue.Number(0), detail.fields[0].actualValue)
        assertEquals(
            SequenceHistoryActualValue.Category(SequenceSnapshotCategoryOptionId("sf-option"), "Local quality option"),
            detail.fields[1].actualValue,
        )
        detail.occurrences.forEachIndexed { index, occurrence ->
            val expected = terminalSequence.occurrences[index]
            assertEquals(expected.id, occurrence.occurrenceId)
            assertEquals(expected.runtimePosition, occurrence.runtimePosition)
            assertEquals(expected.sourceSequenceSnapshotNodeId, occurrence.sourceSequenceSnapshotNodeId)
            assertEquals(expected.repeatSourceSnapshotNodeId, occurrence.repeatSourceSnapshotNodeId)
            assertEquals(expected.repeatIteration, occurrence.repeatIteration)
            assertEquals(expected.status, occurrence.status)
            assertEquals(expected.enteredAt, occurrence.enteredAt)
            assertEquals(expected.completedAt, occurrence.completedAt)
            assertEquals(expected.completionReason, occurrence.completionReason)
            assertFalse(occurrence.isRuntimeAdded)
            assertFalse(occurrence.isDeletedFromHistory)
            assertEquals("Frozen activity", occurrence.activity.title)
            val child = requireNotNull(occurrence.child)
            assertEquals(children[index].id, child.executionId)
            assertEquals(children[index].startedAt, child.startedAt)
            assertEquals(children[index].completedAt, child.completedAt)
            assertEquals(children[index].activeDuration, child.activeDuration)
            assertEquals(zone, child.originalZoneId)
            assertEquals(540, child.originalUtcOffsetMinutes)
            assertEquals(ActivityHistoryActualValue.Number(2000), child.fields[0].actualValue)
            assertEquals(listOf("Local amount", "Captured effort", "Captured note"), child.fields.map { it.name })
            assertEquals(ActivityHistoryActualValue.Missing, child.fields[1].actualValue)
        }
    }

    @Suppress("CyclomaticComplexMethod") // Three source versions have explicit frozen accounting expectations.
    private fun assertStatistics(
        database: LifeTracingDatabase,
        version: Int,
    ) {
        val stats = StatisticsRepository(database) { error("Canonical reads cannot create Statistics identities") }
        val global = stats.global(StatisticsPeriod.AllTime)
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
        assertEquals(if (version == 3) null else date, global.firstPrimaryDate)
        assertEquals(if (version == 3) null else date, global.lastPrimaryDate)
        assertEquals(
            if (version == 3) {
                0L
            } else if (version == 6) {
                1L
            } else {
                4L
            },
            global.topLevelExecutionCount,
        )
        assertEquals(
            Duration.ofSeconds(
                if (version ==
                    3
                ) {
                    0
                } else if (version == 6) {
                    50
                } else {
                    140
                },
            ),
            global.totalTrackedDuration,
        )
        assertEquals(Duration.ofSeconds(if (version == 7) 20 else 0), global.totalSequencePauseIdleDuration)
        assertEquals(if (version == 3) 0L else 1L, global.activeDayCount)
        assertEquals(0L, global.oneOffActivityExecutionCount)
        val activity = stats.activitySeries(activitySeriesId, StatisticsPeriod.AllTime)
        assertEquals(
            if (version == 3) {
                0L
            } else if (version == 6) {
                1L
            } else {
                6L
            },
            activity.executionCount,
        )
        assertEquals(
            if (version == 3) {
                0L
            } else if (version == 6) {
                1L
            } else {
                5L
            },
            activity.durations.sampleCount,
        )
        assertEquals(
            Duration.ofSeconds(
                if (version ==
                    3
                ) {
                    0
                } else if (version == 6) {
                    50
                } else {
                    140
                },
            ),
            activity.durations.total,
        )
        if (version == 7) {
            assertEquals(Duration.ZERO, activity.durations.minimum)
            assertEquals(ExactValue.of(28000, 1), activity.durations.averageMilliseconds)
            val sequence = stats.sequenceSeries(sequenceSeriesId, StatisticsPeriod.AllTime)
            assertEquals(1L, sequence.executionCount)
            assertEquals(Duration.ofSeconds(90), sequence.activeDurations.total)
            assertEquals(Duration.ofSeconds(20), sequence.totalPauseIdleDuration)
        }
        val number =
            stats.numberFieldStatistics(
                activitySeriesId,
                StatisticsFieldId.Activity(ActivityTemplateFieldId("number")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(
            if (version == 3) {
                0L
            } else if (version == 6) {
                1L
            } else {
                4L
            },
            number.recordedCount,
        )
        assertEquals(if (version == 7) 2L else 0L, number.missingCount)
        assertEquals(BigInteger.valueOf(if (version == 7) 6000 else 0), number.values.totalScaled)
        val category =
            stats.categoryFieldStatistics(
                activitySeriesId,
                StatisticsFieldId.Activity(ActivityTemplateFieldId("category")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(if (version == 3) 0L else 1L, category.recordedCount)
        assertEquals(if (version == 7) 5L else 0L, category.missingCount)
        if (version >= 6) {
            val recorded = category.values.single { it.count > 0 }
            assertEquals(StatisticsCategoryOptionId.ActivitySource(CategoryOptionId("option")), recorded.id)
            assertEquals(1L, recorded.count)
        }
        if (version == 7) {
            val sequenceCategory =
                stats.categoryFieldStatistics(
                    sequenceSeriesId,
                    StatisticsFieldId.Sequence(SequenceTemplateFieldId("s-category")),
                    StatisticsPeriod.AllTime,
                )
            assertEquals(
                StatisticsCategoryOptionId.SequenceSource(SequenceTemplateCategoryOptionId("s-option")),
                sequenceCategory.values
                    .single {
                        it.count >
                            0
                    }.id,
            )
            val sequenceNumber =
                stats.numberFieldStatistics(
                    sequenceSeriesId,
                    StatisticsFieldId.Sequence(SequenceTemplateFieldId("s-number")),
                    StatisticsPeriod.AllTime,
                )
            assertEquals(1L, sequenceNumber.recordedCount)
            assertEquals(0L, sequenceNumber.missingCount)
            assertEquals(BigInteger.ZERO, sequenceNumber.values.totalScaled)
        }
        val expectedIds =
            setOf(activitySeriesId, ActivityExecutionStatistics.ONE_OFF_BUCKET_ID) +
                if (version >= 6) setOf(sequenceSeriesId) else emptySet()
        assertEquals(expectedIds, stats.seriesCatalog().map { it.id }.toSet())
        assertEquals(StatisticsSeriesKind.ACTIVITY, activity.series.kind)
        assertEquals("Historical activity series", activity.series.displayName)
        assertNull(activity.series.archivedAt)
        if (version >= 6) {
            val sequence = stats.sequenceSeries(sequenceSeriesId, StatisticsPeriod.AllTime)
            assertEquals(StatisticsSeriesKind.SEQUENCE, sequence.series.kind)
            assertEquals("Historical sequence series", sequence.series.displayName)
        }
        assertEquals(1, stats.seriesCatalog().count { it.kind == StatisticsSeriesKind.ONE_OFF_BUCKET })
    }

    private fun expectedActivity(
        id: String,
        start: Instant?,
        end: Instant,
        duration: Duration?,
    ) = ActivityExecution(
        ActivityExecutionId(id),
        frozenActivity.id,
        ActivityExecutionContext.STANDALONE,
        activitySeriesId,
        ActivityExecutionStatus.COMPLETED,
        start,
        end,
        duration,
        zone,
        540,
        date,
        null,
        null,
        start ?: end,
        end,
    )

    private fun expectedInterval(
        id: String,
        kind: SequenceIntervalKind,
        start: Long,
        end: Long,
        occurrence: Int? = null,
    ) = SequenceInterval(
        SequenceIntervalId(id),
        kind,
        instant.plusSeconds(start),
        instant.plusSeconds(end),
        occurrence?.let { SequenceOccurrenceId("occurrence-$it") },
    )

    private fun assertForeignKeys(database: SupportSQLiteDatabase) {
        database
            .query(
                "PRAGMA foreign_key_check",
            ).use { assertFalse("Historical ownership violation", it.moveToFirst()) }
    }

    private fun SupportSQLiteDatabase.historicalFacts(): Map<String, List<List<String?>>> =
        query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }.associateWith { factRows(it) }

    private fun SupportSQLiteDatabase.factRows(table: String): List<List<String?>> =
        query(
            "SELECT * FROM `$table` " +
                (
                    if (table ==
                        "statistics_series"
                    ) {
                        "WHERE id != 'system:statistics-series:one-off-activities' "
                    } else {
                        ""
                    }
                ) +
                "ORDER BY 1, 2",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }
}
