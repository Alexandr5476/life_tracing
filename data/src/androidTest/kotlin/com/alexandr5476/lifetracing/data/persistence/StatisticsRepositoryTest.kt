@file:Suppress("LongMethod", "LongParameterList")

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class StatisticsRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var repository: StatisticsRepository

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        repository = StatisticsRepository(database) { StatisticsSeriesId("generated-series") }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun globalAccountingUsesOnlyTopLevelTerminalRowsAndPrimaryDate() {
        series("activity-series", "ACTIVITY", "Activity")
        series("sequence-series", "SEQUENCE", "Sequence")
        activitySnapshot("standalone", "activity-series")
        activitySnapshot("child", "activity-series")
        activitySnapshot("one-off", null, mode = "NO_LIVE_TRACKING")
        completedActivity("standalone-execution", "standalone", "activity-series", 30 * MINUTE, AUG_20)
        val sequence = completedSequence("sequence", "sequence-series", "child", 60 * MINUTE, 15 * MINUTE, AUG_20)
        completedActivity(
            "child-execution",
            "child",
            "activity-series",
            60 * MINUTE,
            AUG_20,
            context = "SEQUENCE_CHILD",
            sequenceExecutionId = sequence.first,
            occurrenceId = sequence.second,
        )
        completedActivity(
            "one-off-execution",
            "one-off",
            ONE_OFF,
            null,
            AUG_21,
            completedAtMs = millis("2026-08-21T12:00:00Z"),
        )
        completedActivity("cross-midnight", "standalone", "activity-series", 120 * MINUTE, AUG_20, START_2330)
        completedActivity("running", "standalone", "activity-series", null, AUG_20, status = "RUNNING")

        val all = repository.global(StatisticsPeriod.AllTime)
        assertEquals(Duration.ofMinutes(210), all.totalTrackedDuration)
        assertEquals(4, all.topLevelExecutionCount)
        assertEquals(2, all.activeDayCount)
        assertEquals(Duration.ofMinutes(15), all.totalSequencePauseIdleDuration)
        assertEquals(1, all.oneOffActivityExecutionCount)
        assertNull(all.calendarDayCount)
        assertNull(all.averageTrackedMillisecondsPerCalendarDay)

        val aug20 = repository.global(StatisticsPeriod.Day(LocalDate.parse(AUG_20)))
        assertEquals(Duration.ofMinutes(210), aug20.totalTrackedDuration)
        assertEquals(3, aug20.topLevelExecutionCount)
        assertEquals(ExactValue.of(210 * MINUTE, 1), aug20.averageTrackedMillisecondsPerCalendarDay)
        assertEquals(
            Duration.ofMinutes(210),
            repository
                .activitySeries(
                    StatisticsSeriesId("activity-series"),
                    StatisticsPeriod.Day(LocalDate.parse(AUG_20)),
                ).durations.total,
        )
        assertEquals(
            Duration.ofMinutes(60),
            repository
                .sequenceSeries(
                    StatisticsSeriesId("sequence-series"),
                    StatisticsPeriod.AllTime,
                ).activeDurations.total,
        )
        listOf(
            StatisticsPeriod.Week(LocalDate.parse("2026-08-17")),
            StatisticsPeriod.Month(YearMonth.of(2026, 8)),
            StatisticsPeriod.Year(Year.of(2026)),
            StatisticsPeriod.Custom(LocalDate.parse(AUG_20), LocalDate.parse(AUG_21)),
        ).forEach { period ->
            assertEquals(Duration.ofMinutes(210), repository.global(period).totalTrackedDuration)
        }
        assertEquals(1, repository.global(StatisticsPeriod.Day(LocalDate.parse(AUG_21))).topLevelExecutionCount)

        val correctedStart = millis("2026-08-21T13:00:00Z")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_executions SET started_at_ms = ?, completed_at_ms = ?, active_duration_ms = ?, " +
                "primary_local_date = ?, updated_at_ms = ? WHERE id = 'cross-midnight'",
            arrayOf<Any>(
                correctedStart,
                correctedStart + 60 * MINUTE,
                60 * MINUTE,
                AUG_21,
                correctedStart + 60 * MINUTE,
            ),
        )
        assertEquals(
            Duration.ofMinutes(90),
            repository.global(StatisticsPeriod.Day(LocalDate.parse(AUG_20))).totalTrackedDuration,
        )
        assertEquals(
            Duration.ofMinutes(60),
            repository.global(StatisticsPeriod.Day(LocalDate.parse(AUG_21))).totalTrackedDuration,
        )
        assertEquals(Duration.ofMinutes(150), repository.global(StatisticsPeriod.AllTime).totalTrackedDuration)

        database.activityExecutionDao().softDelete("standalone-execution", millis("2026-08-22T00:00:00Z"))
        assertEquals(Duration.ofMinutes(120), repository.global(StatisticsPeriod.AllTime).totalTrackedDuration)
        assertEquals(3, repository.global(StatisticsPeriod.AllTime).topLevelExecutionCount)

        activitySnapshot("one-off-child", null)
        val oneOffChildParent =
            completedSequence("sequence-one-off", "sequence-series", "one-off-child", 10 * MINUTE, 0, AUG_20)
        completedActivity(
            "one-off-child-execution",
            "one-off-child",
            null,
            10 * MINUTE,
            AUG_20,
            context = "SEQUENCE_CHILD",
            sequenceExecutionId = oneOffChildParent.first,
            occurrenceId = oneOffChildParent.second,
        )
        assertEquals(Duration.ofMinutes(130), repository.global(StatisticsPeriod.AllTime).totalTrackedDuration)
        assertEquals(1, repository.global(StatisticsPeriod.AllTime).oneOffActivityExecutionCount)
    }

    @Test
    fun activityFieldsUseStableIdentityMissingSemanticsAndSqlCategoryGrouping() {
        series("activity-series", "ACTIVITY", "Workout")
        activityTemplateWithFields()
        activitySnapshot("timed-a", "activity-series", fields = true, localNumberName = "Distance to store")
        activitySnapshot("timed-b", "activity-series")
        activitySnapshot("no-live-fields", "activity-series", mode = "NO_LIVE_TRACKING", fields = true)
        activitySnapshot("after-removal", "activity-series", mode = "NO_LIVE_TRACKING")
        completedActivity(
            "a",
            "timed-a",
            "activity-series",
            10 * MINUTE,
            AUG_20,
            values =
                listOf(
                    numberValue("a", "timed-a-number", 0),
                    categoryValue("a", "timed-a-category", "timed-a-tempo"),
                ),
        )
        completedActivity("b", "timed-b", "activity-series", 20 * MINUTE, AUG_20)
        completedActivity(
            "c",
            "no-live-fields",
            "activity-series",
            null,
            AUG_21,
            completedAtMs = millis("2026-08-21T10:00:00Z"),
            values =
                listOf(
                    categoryValue("c", "no-live-fields-category", "no-live-fields-tempo"),
                ),
        )
        completedActivity(
            "d",
            "after-removal",
            "activity-series",
            null,
            AUG_21,
            completedAtMs = millis("2026-08-21T11:00:00Z"),
        )

        val detail = repository.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime)
        assertEquals(4, detail.executionCount)
        assertEquals(2, detail.durations.sampleCount)
        assertEquals(Duration.ofMinutes(30), detail.durations.total)
        assertEquals(ExactValue.of(15 * MINUTE, 1), detail.durations.averageMilliseconds)

        database.activityTemplateDao().updateFields(
            listOf(
                database
                    .activityTemplateDao()
                    .getAllFields("activity")
                    .first {
                        it.id == "number-source"
                    }.copy(name = "Distance", deletedAtMs = 9),
            ),
        )
        database.activityTemplateDao().updateOptions(
            listOf(
                database
                    .activityTemplateDao()
                    .getCategoryOptions(
                        "category-source",
                    ).single()
                    .copy(label = "Tempo", isArchived = true),
            ),
        )
        val catalog = repository.fieldCatalog(StatisticsSeriesId("activity-series"))
        assertEquals(
            setOf("number-source", "category-source", "calories-source"),
            catalog.mapTo(hashSetOf()) { it.id.value },
        )
        assertEquals("Distance", catalog.single { it.id.value == "number-source" }.displayName)
        assertEquals("kcal", catalog.single { it.id.value == "calories-source" }.unit)

        val number =
            repository.numberFieldStatistics(
                StatisticsSeriesId("activity-series"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId("number-source")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(4, number.relevantExecutionCount)
        assertEquals(1, number.recordedCount)
        assertEquals(3, number.missingCount)
        assertEquals(0, number.values.totalScaled.longValueExact())
        assertEquals(ExactValue.of(0, 1), number.values.averageScaled)
        assertEquals(0, number.values.minimumScaled)

        val category =
            repository.categoryFieldStatistics(
                StatisticsSeriesId("activity-series"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId("category-source")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(4, category.relevantExecutionCount)
        assertEquals(2, category.recordedCount)
        assertEquals(2, category.missingCount)
        assertEquals("Tempo", category.values.single().displayLabel)
        assertEquals(2, category.values.single().count)
        assertEquals(
            ExactValue.of(1, 1),
            category.values
                .single()
                .recordedShare.exactValue,
        )

        database.activityExecutionDao().upsertValue(numberValue("a", "timed-a-number", 30))
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM activity_execution_field_values " +
                "WHERE activity_execution_id = 'c' AND snapshot_field_id = 'no-live-fields-category'",
        )
        assertEquals(
            30,
            repository
                .numberFieldStatistics(
                    StatisticsSeriesId("activity-series"),
                    StatisticsFieldId.Activity(ActivityTemplateFieldId("number-source")),
                    StatisticsPeriod.AllTime,
                ).values.totalScaled
                .longValueExact(),
        )
        val editedCategory =
            repository.categoryFieldStatistics(
                StatisticsSeriesId("activity-series"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId("category-source")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(1, editedCategory.recordedCount)
        assertEquals(3, editedCategory.missingCount)
        assertEquals(1, editedCategory.values.single().count)

        activitySnapshot("local-one-off", null, fields = true, sourceLinked = false)
        completedActivity(
            "local-one-off-execution",
            "local-one-off",
            ONE_OFF,
            5 * MINUTE,
            AUG_20,
            values = listOf(numberValue("local-one-off-execution", "local-one-off-number", 99)),
        )
        assertTrue(repository.fieldCatalog(StatisticsSeriesId(ONE_OFF)).isEmpty())
        val oneOffSummary =
            repository.seriesSummaries(StatisticsPeriod.AllTime).single { it.series.id.value == ONE_OFF }
        assertEquals(1, oneOffSummary.executionCount)
        assertEquals(Duration.ofMinutes(5), oneOffSummary.totalDuration)
    }

    @Test
    fun sequenceFieldsUseStableIdentityAndMissingDenominator() {
        series("sequence-series", "SEQUENCE", "Routine")
        sequenceTemplate(
            "sequence",
            "Routine",
            "sequence-series",
            fields =
                listOf(
                    SequenceTemplateFieldEntity(
                        "sequence-number-source",
                        "sequence",
                        0,
                        "Effort",
                        "NUMBER",
                        "points",
                        0,
                        null,
                        null,
                        null,
                        true,
                        0,
                        0,
                        null,
                    ),
                ),
        )
        activitySnapshot("sequence-child", null)
        completedSequence(
            "with-field",
            "sequence-series",
            "sequence-child",
            MINUTE,
            0,
            AUG_20,
            withNumberField = true,
            numberValue = 7,
        )
        completedSequence("before-field", "sequence-series", "sequence-child", MINUTE, 0, AUG_20)

        val field = repository.fieldCatalog(StatisticsSeriesId("sequence-series")).single()
        assertEquals(StatisticsFieldId.Sequence(SequenceTemplateFieldId("sequence-number-source")), field.id)
        val statistics =
            repository.numberFieldStatistics(
                StatisticsSeriesId("sequence-series"),
                field.id,
                StatisticsPeriod.AllTime,
            )
        assertEquals(2, statistics.relevantExecutionCount)
        assertEquals(1, statistics.recordedCount)
        assertEquals(1, statistics.missingCount)
        assertEquals(7, statistics.values.totalScaled.longValueExact())
    }

    @Test
    fun catalogRetainsArchivedSourcelessAndZeroExecutionSeries() {
        series("active-series", "ACTIVITY", "Active")
        series("archived-series", "ACTIVITY", "Archived")
        series("sourceless-series", "SEQUENCE", "Old sequence", archivedAt = 50)
        activityTemplate("active", "Active", "active-series")
        activityTemplate("archived", "Archived", "archived-series", deletedAt = 10)
        activitySnapshot("active-history", "active-series")
        completedActivity("active-history-execution", "active-history", "active-series", MINUTE, AUG_20)
        activitySnapshot("sourceless-child", null)
        completedSequence("historical", "sourceless-series", "sourceless-child", MINUTE, 0, AUG_20)

        val states = repository.seriesCatalog().associate { it.id.value to it.sourceState }
        assertEquals(StatisticsSeriesSourceState.ACTIVE_SOURCE, states["active-series"])
        assertEquals(StatisticsSeriesSourceState.ARCHIVED_SOURCE, states["archived-series"])
        assertEquals(StatisticsSeriesSourceState.NO_CURRENT_SOURCE, states["sourceless-series"])
        assertEquals(StatisticsSeriesSourceState.SYSTEM_ONE_OFF, states[ONE_OFF])
        val summaries = repository.seriesSummaries(StatisticsPeriod.AllTime).associateBy { it.series.id.value }
        assertEquals(1, summaries.getValue("sourceless-series").executionCount)
        assertEquals(Duration.ofMinutes(1), summaries.getValue("sourceless-series").totalDuration)
        assertEquals(1, summaries.getValue("active-series").executionCount)

        database.activityTemplateDao().archive("active", 20)
        assertEquals(
            StatisticsSeriesSourceState.ARCHIVED_SOURCE,
            repository.seriesCatalog().single { it.id.value == "active-series" }.sourceState,
        )
        assertEquals(
            1,
            repository.activitySeries(StatisticsSeriesId("active-series"), StatisticsPeriod.AllTime).executionCount,
        )
        assertNull(database.statisticsSeriesDao().getById("active-series")?.archivedAtMs)
        database.activityTemplateDao().restore("active")
        assertEquals(
            StatisticsSeriesSourceState.ACTIVE_SOURCE,
            repository.seriesCatalog().single { it.id.value == "active-series" }.sourceState,
        )

        database.openHelper.writableDatabase.execSQL(
            "UPDATE statistics_series SET kind = 'UNKNOWN' WHERE id = 'sourceless-series'",
        )
        assertThrows(IllegalArgumentException::class.java) { repository.seriesCatalog() }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE statistics_series SET kind = 'SEQUENCE' WHERE id = 'sourceless-series'",
        )
    }

    @Test
    fun seriesSplitIsAtomicPreservesOldPlanSnapshotAndRenameMirrorsLabel() {
        series("old-activity-series", "ACTIVITY", "Run")
        series("old-sequence-series", "SEQUENCE", "Workout")
        activityTemplate("activity", "Run", "old-activity-series")
        sequenceTemplate("sequence", "Workout", "old-sequence-series")
        activitySnapshot("plan-snapshot", "old-activity-series", sourceTemplate = "activity", sourceRevision = 1)
        database.planEntryDao().insert(
            PlanEntryEntity(
                "plan",
                "ACTIVITY",
                "activity",
                null,
                1,
                "plan-snapshot",
                null,
                "DAY",
                AUG_20,
                null,
                null,
                null,
                null,
                "PLANNED",
                null,
                null,
                START_2330,
                START_2330,
                null,
                null,
            ),
        )
        val activityBeforeSplit = database.activityTemplateDao().getAggregate("activity")!!
        val sequenceBeforeSplit = database.sequenceTemplateDao().getAggregate("sequence")!!

        val newActivity =
            repository.startNewActivityStatisticsSeries(
                ActivityTemplateId("activity"),
                instant("2026-08-20T10:00:00Z"),
            )
        assertEquals("generated-series", newActivity.id.value)
        val split = database.activityTemplateDao().getById("activity")!!
        assertEquals("generated-series", split.statisticsSeriesId)
        assertEquals(2, split.revision)
        val activityAfterSplit = database.activityTemplateDao().getAggregate("activity")!!
        assertEquals(activityBeforeSplit.settings, activityAfterSplit.settings)
        assertEquals(activityBeforeSplit.fields, activityAfterSplit.fields)
        assertEquals(activityBeforeSplit.options, activityAfterSplit.options)
        assertEquals(activityBeforeSplit.userState, activityAfterSplit.userState)
        assertEquals("old-activity-series", database.activitySnapshotDao().getById("plan-snapshot")?.statisticsSeriesId)
        assertNull(database.statisticsSeriesDao().getById("old-activity-series")?.archivedAtMs)

        val live = liveRepository()
        val planned =
            live.startActivityFromPlan(
                PlanEntryId("plan"),
                instant("2026-08-20T11:00:00Z"),
                instant("2026-08-20T11:00:00Z"),
                ZoneOffset.UTC,
            )
        live.completeActiveActivity(instant("2026-08-20T11:10:00Z"))
        assertEquals(StatisticsSeriesId("old-activity-series"), planned.statisticsSeriesId)

        activitySnapshot("direct-new", "generated-series", sourceTemplate = "activity", sourceRevision = 2)
        val direct =
            live.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("direct-new"),
                instant("2026-08-20T12:00:00Z"),
                instant("2026-08-20T12:00:00Z"),
                ZoneOffset.UTC,
            )
        live.completeActiveActivity(instant("2026-08-20T12:20:00Z"))
        assertEquals(StatisticsSeriesId("generated-series"), direct.statisticsSeriesId)

        val oldSequence =
            completedSequence(
                "old-sequence-root",
                "old-sequence-series",
                "plan-snapshot",
                5 * MINUTE,
                0,
                AUG_20,
            )
        completedActivity(
            "old-step-execution",
            "plan-snapshot",
            "old-activity-series",
            5 * MINUTE,
            AUG_20,
            context = "SEQUENCE_CHILD",
            sequenceExecutionId = oldSequence.first,
            occurrenceId = oldSequence.second,
        )
        assertEquals(
            2,
            repository
                .activitySeries(
                    StatisticsSeriesId("old-activity-series"),
                    StatisticsPeriod.AllTime,
                ).executionCount,
        )
        assertEquals(
            1,
            repository.activitySeries(StatisticsSeriesId("generated-series"), StatisticsPeriod.AllTime).executionCount,
        )

        val activityAggregate = database.activityTemplateDao().getAggregate("activity")!!
        database.activityTemplateDao().updateSemanticAggregate(
            ActivityTemplateSemanticUpdate(
                activityAggregate.template.copy(
                    name = "Running",
                    revision = 3,
                    updatedAtMs = millis("2026-08-20T13:00:00Z"),
                ),
                activityAggregate.settings,
                activityAggregate.fields,
                activityAggregate.options,
            ),
        )
        assertEquals("Running", database.statisticsSeriesDao().getById("generated-series")?.displayName)
        assertEquals("Run", database.activitySnapshotDao().getById("plan-snapshot")?.name)

        repository = StatisticsRepository(database) { StatisticsSeriesId("new-sequence-series") }
        repository.startNewSequenceStatisticsSeries(SequenceTemplateId("sequence"), instant("2026-08-20T14:00:00Z"))
        assertEquals("new-sequence-series", database.sequenceTemplateDao().getById("sequence")?.statisticsSeriesId)
        assertEquals(2, database.sequenceTemplateDao().getById("sequence")?.revision)
        val sequenceAfterSplit = database.sequenceTemplateDao().getAggregate("sequence")!!
        assertEquals(sequenceBeforeSplit.settings, sequenceAfterSplit.settings)
        assertEquals(sequenceBeforeSplit.fields, sequenceAfterSplit.fields)
        assertEquals(sequenceBeforeSplit.options, sequenceAfterSplit.options)
        assertEquals(sequenceBeforeSplit.nodes, sequenceAfterSplit.nodes)
        assertEquals(sequenceBeforeSplit.userState, sequenceAfterSplit.userState)
        completedSequence("new-sequence-root", "new-sequence-series", "direct-new", 7 * MINUTE, 0, AUG_20)
        assertEquals(
            1,
            repository
                .sequenceSeries(
                    StatisticsSeriesId("old-sequence-series"),
                    StatisticsPeriod.AllTime,
                ).executionCount,
        )
        assertEquals(
            1,
            repository
                .sequenceSeries(
                    StatisticsSeriesId("new-sequence-series"),
                    StatisticsPeriod.AllTime,
                ).executionCount,
        )

        val sequenceAggregate = database.sequenceTemplateDao().getAggregate("sequence")!!
        database.sequenceTemplateDao().updateSemanticAggregate(
            SequenceTemplateSemanticUpdate(
                expectedRevision = 2,
                template =
                    sequenceAggregate.template.copy(
                        name = "Training",
                        revision = 3,
                        updatedAtMs = millis("2026-08-20T14:30:00Z"),
                    ),
                settings = sequenceAggregate.settings,
                fields = sequenceAggregate.fields,
                options = sequenceAggregate.options,
                nodes = sequenceAggregate.nodes,
                stepOverrides = sequenceAggregate.stepOverrides,
            ),
        )
        assertEquals("Training", database.statisticsSeriesDao().getById("new-sequence-series")?.displayName)

        repository = StatisticsRepository(database) { StatisticsSeriesId("old-activity-series") }
        assertThrows(SQLiteConstraintException::class.java) {
            repository.startNewActivityStatisticsSeries(ActivityTemplateId("activity"), instant("2026-08-20T15:00:00Z"))
        }
        assertEquals("generated-series", database.activityTemplateDao().getById("activity")?.statisticsSeriesId)
        assertEquals(3, database.activityTemplateDao().getById("activity")?.revision)
        assertEquals(millis("2026-08-20T13:00:00Z"), database.activityTemplateDao().getById("activity")?.updatedAtMs)
    }

    @Test
    fun boundedLargeHistoryUsesFreshAggregateQueries() {
        series("large-series", "ACTIVITY", "Large")
        activitySnapshot("large-snapshot", "large-series")
        val statement =
            database.openHelper.writableDatabase.compileStatement(
                "INSERT INTO activity_executions " +
                    "(id,snapshot_id,context_type,statistics_series_id,status,started_at_ms,completed_at_ms," +
                    "active_duration_ms,original_zone_id,primary_local_date,created_at_ms,updated_at_ms) " +
                    "VALUES (?, 'large-snapshot', 'STANDALONE', " +
                    "'large-series', 'COMPLETED', ?, ?, 1000, 'UTC', ?, ?, ?)",
            )
        database.runInTransaction {
            repeat(3_000) { index ->
                val inRange = index < 2_000
                val start =
                    if (inRange) {
                        millis("2026-08-20T00:00:00Z") + index * 2_000L
                    } else {
                        millis("2026-07-01T00:00:00Z") + index * 2_000L
                    }
                statement.clearBindings()
                statement.bindString(1, "large-$index")
                statement.bindLong(2, start)
                statement.bindLong(3, start + 1_000)
                statement.bindString(4, if (inRange) AUG_20 else "2026-07-01")
                statement.bindLong(5, start)
                statement.bindLong(6, start + 1_000)
                statement.executeInsert()
            }
        }

        val bounded =
            repository.activitySeries(
                StatisticsSeriesId("large-series"),
                StatisticsPeriod.Day(LocalDate.parse(AUG_20)),
            )
        assertEquals(2_000, bounded.executionCount)
        assertEquals(Duration.ofSeconds(2_000), bounded.durations.total)
        assertTrue(database.sequenceExecutionDao().getIntervals("missing").isEmpty())
    }

    private fun series(
        id: String,
        kind: String,
        name: String,
        archivedAt: Long? = null,
    ) = database.statisticsSeriesDao().insert(StatisticsSeriesEntity(id, kind, name, 0, archivedAt))

    private fun activityTemplate(
        id: String,
        name: String,
        seriesId: String,
        deletedAt: Long? = null,
        fields: List<ActivityTemplateFieldEntity> = emptyList(),
        options: List<ActivityTemplateCategoryOptionEntity> = emptyList(),
    ) = database.activityTemplateDao().insertAggregate(
        ActivityTemplateAggregateEntity(
            ActivityTemplateEntity(id, name, null, "STOPWATCH", null, seriesId, 1, 0, 0, deletedAt, null),
            ActivityTemplateSettingsEntity(id),
            fields,
            options,
            userState = ActivityTemplateUserStateEntity(id, null, null),
        ),
    )

    private fun activityTemplateWithFields() {
        val fields =
            listOf(
                ActivityTemplateFieldEntity(
                    "number-source",
                    "activity",
                    0,
                    "Distnace",
                    "NUMBER",
                    "km",
                    3,
                    null,
                    null,
                    null,
                    false,
                    0,
                    0,
                    null,
                ),
                ActivityTemplateFieldEntity(
                    "calories-source",
                    "activity",
                    1,
                    "Calories",
                    "NUMBER",
                    "kcal",
                    0,
                    null,
                    null,
                    null,
                    false,
                    0,
                    0,
                    null,
                ),
                ActivityTemplateFieldEntity(
                    "category-source",
                    "activity",
                    2,
                    "Type",
                    "CATEGORY",
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    0,
                    0,
                    null,
                ),
            )
        activityTemplate(
            "activity",
            "Workout",
            "activity-series",
            fields = fields,
            options = listOf(ActivityTemplateCategoryOptionEntity("tempo-source", "category-source", 0, "Tempoo")),
        )
    }

    private fun sequenceTemplate(
        id: String,
        name: String,
        seriesId: String,
        fields: List<SequenceTemplateFieldEntity> = emptyList(),
    ) = database.sequenceTemplateDao().insertAggregate(
        SequenceTemplateAggregateEntity(
            SequenceTemplateEntity(id, name, null, seriesId, 1, 0, 0, null, null),
            SequenceTemplateSettingsEntity(id),
            SequenceTemplateUserStateEntity(id, null, null),
            fields = fields,
        ),
    )

    private fun activitySnapshot(
        id: String,
        seriesId: String?,
        mode: String = "STOPWATCH",
        fields: Boolean = false,
        localNumberName: String? = null,
        sourceTemplate: String? = null,
        sourceRevision: Long? = null,
        sourceLinked: Boolean = true,
    ) {
        val snapshotFields =
            if (!fields) {
                emptyList()
            } else {
                listOf(
                    ActivitySnapshotFieldEntity(
                        "$id-number",
                        id,
                        if (sourceLinked) "number-source" else null,
                        0,
                        "Distnace",
                        localNumberName,
                        "NUMBER",
                        "km",
                        3,
                        null,
                        null,
                        null,
                        false,
                    ),
                    ActivitySnapshotFieldEntity(
                        "$id-category",
                        id,
                        if (sourceLinked) "category-source" else null,
                        1,
                        "Type",
                        null,
                        "CATEGORY",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                    ),
                )
            }
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    id,
                    if (id ==
                        "plan-snapshot"
                    ) {
                        "Run"
                    } else {
                        id
                    },
                    null,
                    mode,
                    null,
                    sourceTemplate,
                    sourceRevision,
                    seriesId,
                    false,
                    0,
                ),
                ActivitySnapshotSettingsEntity(id),
                snapshotFields,
                if (fields) {
                    listOf(
                        ActivitySnapshotCategoryOptionEntity(
                            "$id-tempo",
                            "$id-category",
                            if (sourceLinked) "tempo-source" else null,
                            0,
                            "Tempoo",
                            null,
                        ),
                    )
                } else {
                    emptyList()
                },
            ),
        )
    }

    private fun completedActivity(
        id: String,
        snapshotId: String,
        seriesId: String?,
        durationMs: Long?,
        primaryDate: String,
        startedAtMs: Long = START_2330,
        completedAtMs: Long = startedAtMs + (durationMs ?: 0),
        status: String = "COMPLETED",
        context: String = "STANDALONE",
        sequenceExecutionId: String? = null,
        occurrenceId: String? = null,
        values: List<ActivityExecutionFieldValueEntity> = emptyList(),
    ) {
        val noLive = durationMs == null && status == "COMPLETED"
        database.activityExecutionDao().insertAggregate(
            ActivityExecutionAggregateEntity(
                ActivityExecutionEntity(
                    id,
                    snapshotId,
                    context,
                    sequenceExecutionId,
                    occurrenceId,
                    null,
                    seriesId,
                    status,
                    if (noLive) null else startedAtMs,
                    if (status == "COMPLETED") completedAtMs else null,
                    if (status == "COMPLETED") durationMs else null,
                    "UTC",
                    0,
                    primaryDate,
                    null,
                    null,
                    startedAtMs,
                    if (status == "COMPLETED") completedAtMs else startedAtMs,
                ),
                values = values,
            ),
        )
    }

    private fun completedSequence(
        id: String,
        seriesId: String,
        childSnapshotId: String,
        activeMs: Long,
        pauseMs: Long,
        primaryDate: String,
        withNumberField: Boolean = false,
        numberValue: Long? = null,
    ): Pair<String, String> {
        val snapshotId = "$id-snapshot"
        val occurrenceId = "$id-occurrence"
        val started = START_2330
        val ended = started + activeMs + pauseMs
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity(snapshotId, id, null, null, null, seriesId, 0),
                sequenceSettings(snapshotId),
                fields =
                    if (withNumberField) {
                        listOf(
                            SequenceSnapshotFieldEntity(
                                "$snapshotId-number",
                                snapshotId,
                                "sequence-number-source",
                                0,
                                "Effort",
                                null,
                                "NUMBER",
                                "points",
                                0,
                                null,
                                null,
                                null,
                                true,
                            ),
                        )
                    } else {
                        emptyList()
                    },
            ),
        )
        database.sequenceExecutionDao().insertAggregate(
            SequenceExecutionAggregateEntity(
                SequenceExecutionEntity(
                    id,
                    snapshotId,
                    null,
                    seriesId,
                    "ENDED_EARLY",
                    started,
                    ended,
                    activeMs,
                    pauseMs,
                    activeMs + pauseMs,
                    "UTC",
                    0,
                    primaryDate,
                    null,
                    started,
                    ended,
                ),
                occurrences =
                    listOf(
                        SequenceOccurrenceEntity(
                            occurrenceId,
                            id,
                            null,
                            childSnapshotId,
                            0,
                            null,
                            null,
                            "COMPLETED",
                            started,
                            started + activeMs,
                            "SEQUENCE_ENDED_EARLY",
                            true,
                            false,
                        ),
                    ),
                intervals =
                    listOf(
                        SequenceIntervalEntity(
                            "$id-active",
                            id,
                            "ACTIVE_STEP",
                            started,
                            started + activeMs,
                            occurrenceId,
                        ),
                        SequenceIntervalEntity("$id-pause", id, "IMPLICIT_IDLE", started + activeMs, ended, null),
                    ),
                values =
                    numberValue
                        ?.let {
                            listOf(SequenceExecutionFieldValueEntity(id, "$snapshotId-number", it, null, null))
                        }.orEmpty(),
            ),
        )
        return id to occurrenceId
    }

    private fun sequenceSettings(id: String) =
        SequenceSnapshotSettingsEntity(id, true, 0, 0, true, true, false, true, true, "ACTIVE")

    private fun numberValue(
        executionId: String,
        fieldId: String,
        value: Long,
    ) = ActivityExecutionFieldValueEntity(executionId, fieldId, value, null, null)

    private fun categoryValue(
        executionId: String,
        fieldId: String,
        optionId: String,
    ) = ActivityExecutionFieldValueEntity(executionId, fieldId, null, optionId, null)

    private fun liveRepository(): LiveSessionRepository {
        var activity = 0
        return LiveSessionRepository(
            database,
            { ActivityExecutionId("live-activity-${++activity}") },
            { ActivityExecutionPauseId("live-pause") },
            { SequenceExecutionId("live-sequence") },
            { SequenceOccurrenceId("live-occurrence") },
            { SequenceIntervalId("live-interval") },
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val AUG_20 = "2026-08-20"
        const val AUG_21 = "2026-08-21"
        const val ONE_OFF = "system:statistics-series:one-off-activities"
        val START_2330 = millis("2026-08-20T23:30:00Z")

        fun instant(value: String): Instant = Instant.parse(value)

        fun millis(value: String): Long = instant(value).toEpochMilli()
    }
}
