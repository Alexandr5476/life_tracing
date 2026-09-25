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
import com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionCommand
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsFieldDetail
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class StatisticsRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var repository: StatisticsRepository
    private val observedSql = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .setQueryCallback({ sql, _ -> observedSql.add(sql) }, Executor { it.run() })
                .build()
        repository = StatisticsRepository(database) { StatisticsSeriesId("generated-series") }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun seriesDetailBatchesMixedFieldHistoryReadsWithoutChangingPerFieldResults() {
        fun fixture(
            prefix: String,
            fieldCount: Int,
        ) {
            series(prefix, "ACTIVITY", prefix)
            val fields =
                (0 until fieldCount).map { index ->
                    ActivityTemplateFieldEntity(
                        "$prefix-field-$index",
                        prefix,
                        index,
                        "Field $index",
                        if (index % 2 == 0) "NUMBER" else "CATEGORY",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        0,
                        0,
                        null,
                    )
                }
            val options =
                fields.filterIndexed { index, _ -> index % 2 == 1 }.map {
                    ActivityTemplateCategoryOptionEntity("${it.id}-source-option", it.id, 0, "Renamed ${it.id}")
                }
            activityTemplate(prefix, prefix, prefix, fields = fields, options = options)
            val snapshotId = "$prefix-snapshot"
            database.activitySnapshotDao().insertAggregate(
                ActivitySnapshotAggregateEntity(
                    ActivitySnapshotEntity(
                        snapshotId,
                        snapshotId,
                        null,
                        "STOPWATCH",
                        null,
                        prefix,
                        1,
                        prefix,
                        false,
                        0,
                    ),
                    ActivitySnapshotSettingsEntity(snapshotId),
                    fields.mapIndexed { index, field ->
                        ActivitySnapshotFieldEntity(
                            "$snapshotId-field-$index",
                            snapshotId,
                            field.id,
                            index,
                            field.name,
                            null,
                            field.fieldType,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                        )
                    },
                    fields.filterIndexed { index, _ -> index % 2 == 1 }.flatMap { field ->
                        listOf(
                            ActivitySnapshotCategoryOptionEntity(
                                "$snapshotId-${field.id}-source",
                                "$snapshotId-field-${fields.indexOf(field)}",
                                "${field.id}-source-option",
                                0,
                                "Old source",
                                null,
                            ),
                            ActivitySnapshotCategoryOptionEntity(
                                "$snapshotId-${field.id}-fallback",
                                "$snapshotId-field-${fields.indexOf(field)}",
                                null,
                                1,
                                "Fallback ${field.id}",
                                null,
                            ),
                        )
                    },
                ),
            )
            repeat(80) { execution ->
                val values =
                    fields.mapIndexedNotNull { index, field ->
                        val snapshotField = "$snapshotId-field-$index"
                        if (index % 2 == 0 && execution % 2 == 0) {
                            numberValue(
                                "$prefix-execution-$execution",
                                snapshotField,
                                if (execution ==
                                    0
                                ) {
                                    0
                                } else {
                                    execution.toLong()
                                },
                            )
                        } else if (index % 2 == 1 && execution % 3 != 2) {
                            categoryValue(
                                "$prefix-execution-$execution",
                                snapshotField,
                                "$snapshotId-${field.id}-${if (execution % 3 == 0) "source" else "fallback"}",
                            )
                        } else {
                            null
                        }
                    }
                completedActivity("$prefix-execution-$execution", snapshotId, prefix, MINUTE, AUG_20, values = values)
            }
        }

        fixture("small", 2)
        fixture("large", 40)

        fun read(prefix: String): Pair<StatisticsSeriesDetail.Activity, Int> {
            observedSql.clear()
            val detail =
                repository.seriesDetail(
                    StatisticsSeriesId(prefix),
                    StatisticsPeriod.AllTime,
                ) as StatisticsSeriesDetail.Activity
            val historyReads =
                synchronized(observedSql) {
                    observedSql.count { it.contains("FROM activity_executions", ignoreCase = true) }
                }
            return detail to historyReads
        }
        val (small, smallReads) = read("small")
        val (large, largeReads) = read("large")
        assertEquals(2, small.fields.size)
        assertEquals(40, large.fields.size)
        assertTrue(smallReads > 0)
        assertEquals(smallReads, largeReads)
        assertTrue(largeReads <= 6)
        large.fields.forEach { detail ->
            val expected =
                when (detail) {
                    is StatisticsFieldDetail.Number ->
                        repository.numberFieldStatistics(
                            StatisticsSeriesId("large"),
                            detail.field.id,
                            StatisticsPeriod.AllTime,
                        )
                    is StatisticsFieldDetail.Category ->
                        repository.categoryFieldStatistics(
                            StatisticsSeriesId("large"),
                            detail.field.id,
                            StatisticsPeriod.AllTime,
                        )
                    is StatisticsFieldDetail.Text -> error("Unexpected Text Field")
                }
            assertEquals(
                expected,
                when (detail) {
                    is StatisticsFieldDetail.Number -> detail.statistics
                    is StatisticsFieldDetail.Category -> detail.statistics
                    is StatisticsFieldDetail.Text -> error("Unexpected Text Field")
                },
            )
        }
        val number =
            (large.fields.first { it is StatisticsFieldDetail.Number } as StatisticsFieldDetail.Number)
                .statistics
        assertEquals(40L, number.recordedCount)
        assertEquals(40L, number.missingCount)
        assertEquals(ExactValue.of(39, 1), number.values.medianScaled)
        val category =
            (
                large.fields.first {
                    it is StatisticsFieldDetail.Category
                } as StatisticsFieldDetail.Category
            ).statistics
        assertEquals(54L, category.recordedCount)
        assertEquals(26L, category.missingCount)
        assertEquals(
            setOf(
                "large-field-1-source-option",
                "large-snapshot-large-field-1-fallback",
            ),
            category.values
                .map {
                    it.id.value
                }.toSet(),
        )
    }

    @Test
    fun sequenceSeriesDetailBatchesMixedFieldHistoryReadsAndMatchesCanonicalReaders() {
        fun fixture(
            prefix: String,
            fieldCount: Int,
        ) {
            series(prefix, "SEQUENCE", prefix)
            val fields =
                (0 until fieldCount).map { index ->
                    SequenceTemplateFieldEntity(
                        "$prefix-field-$index",
                        prefix,
                        index,
                        "Field $index",
                        if (index % 2 == 0) "NUMBER" else "CATEGORY",
                        if (index % 2 == 0) "points" else null,
                        if (index % 2 == 0) 0 else null,
                        null,
                        null,
                        null,
                        false,
                        0,
                        0,
                        null,
                    )
                }
            val options =
                fields.filter { it.fieldType == "CATEGORY" }.map {
                    SequenceTemplateCategoryOptionEntity("${it.id}-source-option", it.id, 0, "Renamed ${it.id}")
                }
            sequenceTemplate(prefix, prefix, prefix, fields, options)
            activitySnapshot("$prefix-child", null)
            repeat(2) { execution ->
                val snapshot = "$prefix-execution-$execution-snapshot"
                val snapshotFields =
                    fields.mapIndexed { index, field ->
                        SequenceSnapshotFieldEntity(
                            "$snapshot-field-$index",
                            snapshot,
                            field.id,
                            index,
                            field.name,
                            null,
                            field.fieldType,
                            field.unit,
                            field.displayPrecision,
                            null,
                            null,
                            null,
                            false,
                        )
                    }
                val snapshotOptions =
                    fields
                        .mapIndexedNotNull { index, field ->
                            if (field.fieldType != "CATEGORY") {
                                null
                            } else {
                                (0..1).map { option ->
                                    SequenceSnapshotCategoryOptionEntity(
                                        "$snapshot-option-$index-$option",
                                        "$snapshot-field-$index",
                                        if (option == 0) "${field.id}-source-option" else null,
                                        option,
                                        if (option == 0) "Old source" else "Fallback ${field.id}",
                                        null,
                                    )
                                }
                            }
                        }.flatten()
                val start = if (execution == 0) START_2330 else millis("2026-08-21T23:30:00Z")
                val end = start + MINUTE
                database.sequenceSnapshotDao().insertAggregate(
                    SequenceSnapshotAggregateEntity(
                        SequenceSnapshotEntity(snapshot, "$prefix-execution-$execution", null, null, null, prefix, 0),
                        sequenceSettings(snapshot),
                        snapshotFields,
                        snapshotOptions,
                    ),
                )
                val values =
                    fields.mapIndexedNotNull { index, field ->
                        when {
                            field.fieldType == "NUMBER" && (index == 0 || execution == 0) ->
                                SequenceExecutionFieldValueEntity(
                                    "$prefix-execution-$execution",
                                    "$snapshot-field-$index",
                                    if (index == 0) execution * 20L else index.toLong(),
                                    null,
                                    null,
                                )
                            field.fieldType == "CATEGORY" && (execution == 0 || index == 1) ->
                                SequenceExecutionFieldValueEntity(
                                    "$prefix-execution-$execution",
                                    "$snapshot-field-$index",
                                    null,
                                    "$snapshot-option-$index-$execution",
                                    null,
                                )
                            else -> null
                        }
                    }
                database.sequenceExecutionDao().insertAggregate(
                    SequenceExecutionAggregateEntity(
                        SequenceExecutionEntity(
                            "$prefix-execution-$execution",
                            snapshot,
                            null,
                            prefix,
                            if (execution == 0) "COMPLETED" else "ENDED_EARLY",
                            start,
                            end,
                            MINUTE,
                            0,
                            MINUTE,
                            "UTC",
                            0,
                            if (execution == 0) AUG_20 else AUG_21,
                            null,
                            start,
                            end,
                        ),
                        occurrences =
                            listOf(
                                SequenceOccurrenceEntity(
                                    "$prefix-occurrence-$execution",
                                    "$prefix-execution-$execution",
                                    null,
                                    "$prefix-child",
                                    0,
                                    null,
                                    null,
                                    "COMPLETED",
                                    start,
                                    end,
                                    "SEQUENCE_ENDED_EARLY",
                                    true,
                                    false,
                                ),
                            ),
                        intervals =
                            listOf(
                                SequenceIntervalEntity(
                                    "$prefix-interval-$execution",
                                    "$prefix-execution-$execution",
                                    "ACTIVE_STEP",
                                    start,
                                    end,
                                    "$prefix-occurrence-$execution",
                                ),
                            ),
                        values = values,
                    ),
                )
            }
        }
        fixture("sequence-small", 2)
        fixture("sequence-large", 40)
        val period = StatisticsPeriod.Custom(LocalDate.parse(AUG_20), LocalDate.parse(AUG_21))

        fun read(id: String): Pair<StatisticsSeriesDetail.Sequence, Int> {
            observedSql.clear()
            val detail =
                repository.seriesDetail(StatisticsSeriesId(id), period) as StatisticsSeriesDetail.Sequence
            val reads =
                synchronized(observedSql) {
                    observedSql.count { it.contains("FROM sequence_executions", ignoreCase = true) }
                }
            return detail to reads
        }
        val (small, smallReads) = read("sequence-small")
        val (large, largeReads) = read("sequence-large")
        assertEquals(2, small.fields.size)
        assertEquals(40, large.fields.size)
        assertTrue(smallReads > 0)
        assertEquals(smallReads, largeReads)
        assertTrue(largeReads <= 6)
        assertEquals(2L, large.statistics.executionCount)
        large.fields.forEach { detail ->
            val expected =
                when (detail) {
                    is StatisticsFieldDetail.Number ->
                        repository.numberFieldStatistics(StatisticsSeriesId("sequence-large"), detail.field.id, period)
                    is StatisticsFieldDetail.Category ->
                        repository.categoryFieldStatistics(
                            StatisticsSeriesId("sequence-large"),
                            detail.field.id,
                            period,
                        )
                    is StatisticsFieldDetail.Text -> error("Unexpected Text Field")
                }
            assertEquals(
                expected,
                when (detail) {
                    is StatisticsFieldDetail.Number -> detail.statistics
                    is StatisticsFieldDetail.Category -> detail.statistics
                    is StatisticsFieldDetail.Text -> error("Unexpected Text Field")
                },
            )
        }
        val number =
            (large.fields.first { it is StatisticsFieldDetail.Number } as StatisticsFieldDetail.Number)
                .statistics
        assertEquals(2L, number.recordedCount)
        assertEquals(0L, number.missingCount)
        assertEquals(BigInteger.valueOf(20), number.values.totalScaled)
        assertEquals(ExactValue.of(10, 1), number.values.averageScaled)
        assertEquals(ExactValue.of(10, 1), number.values.medianScaled)
        assertEquals(0L, number.values.minimumScaled)
        assertEquals(20L, number.values.maximumScaled)
        val partlyMissingNumber =
            large.fields
                .filterIsInstance<StatisticsFieldDetail.Number>()
                .first { it.field.id.value == "sequence-large-field-2" }
                .statistics
        assertEquals(1L, partlyMissingNumber.recordedCount)
        assertEquals(1L, partlyMissingNumber.missingCount)
        val category =
            (
                large.fields.first {
                    it is StatisticsFieldDetail.Category
                } as StatisticsFieldDetail.Category
            ).statistics
        assertEquals(2L, category.recordedCount)
        assertEquals(0L, category.missingCount)
        assertEquals(
            mapOf(
                "sequence-large-field-1-source-option" to 1L,
                "sequence-large-execution-0-snapshot-option-1-1" to 0L,
                "sequence-large-execution-1-snapshot-option-1-1" to 1L,
            ),
            category.values.associate { it.id.value to it.count },
        )
        assertEquals(2L, category.values.sumOf { it.count })
        assertEquals(
            mapOf(
                "sequence-large-field-1-source-option" to ExactValue.of(1, 2),
                "sequence-large-execution-0-snapshot-option-1-1" to ExactValue.of(0, 1),
                "sequence-large-execution-1-snapshot-option-1-1" to ExactValue.of(1, 2),
            ),
            category.values.associate { it.id.value to it.recordedShare.exactValue },
        )
        val partlyMissingCategory =
            large.fields
                .filterIsInstance<StatisticsFieldDetail.Category>()
                .first { it.field.id.value == "sequence-large-field-3" }
                .statistics
        assertEquals(1L, partlyMissingCategory.recordedCount)
        assertEquals(1L, partlyMissingCategory.missingCount)
        val firstDayPeriod = StatisticsPeriod.Day(LocalDate.parse(AUG_20))
        val day =
            repository.seriesDetail(
                StatisticsSeriesId("sequence-large"),
                firstDayPeriod,
            ) as StatisticsSeriesDetail.Sequence
        assertEquals(1L, day.statistics.executionCount)
        assertTrue(
            day.fields.filterIsInstance<StatisticsFieldDetail.Number>().all { it.statistics.recordedCount == 1L },
        )
        val firstDayCategory =
            day.fields
                .filterIsInstance<StatisticsFieldDetail.Category>()
                .first { it.field.id.value == "sequence-large-field-1" }
                .statistics
        assertEquals(
            repository.categoryFieldStatistics(
                StatisticsSeriesId("sequence-large"),
                firstDayCategory.field.id,
                firstDayPeriod,
            ),
            firstDayCategory,
        )
        assertEquals(
            mapOf(
                "sequence-large-field-1-source-option" to 1L,
                "sequence-large-execution-0-snapshot-option-1-1" to 0L,
                "sequence-large-execution-1-snapshot-option-1-1" to 0L,
            ),
            firstDayCategory.values.associate { it.id.value to it.count },
        )
        val secondDay =
            repository.seriesDetail(
                StatisticsSeriesId("sequence-large"),
                StatisticsPeriod.Day(LocalDate.parse(AUG_21)),
            ) as StatisticsSeriesDetail.Sequence
        assertEquals(1L, secondDay.statistics.executionCount)
        val secondDayCategory =
            secondDay.fields
                .filterIsInstance<StatisticsFieldDetail.Category>()
                .first { it.field.id.value == "sequence-large-field-1" }
                .statistics
        assertEquals(
            mapOf(
                "sequence-large-field-1-source-option" to 0L,
                "sequence-large-execution-0-snapshot-option-1-1" to 0L,
                "sequence-large-execution-1-snapshot-option-1-1" to 1L,
            ),
            secondDayCategory.values.associate { it.id.value to it.count },
        )
    }

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
        assertEquals(4L, all.topLevelExecutionCount)
        assertEquals(2L, all.activeDayCount)
        assertEquals(Duration.ofMinutes(15), all.totalSequencePauseIdleDuration)
        val activityDetail =
            repository.seriesDetail(
                StatisticsSeriesId("activity-series"),
                StatisticsPeriod.AllTime,
            ) as StatisticsSeriesDetail.Activity
        assertEquals(
            repository.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime),
            activityDetail.statistics,
        )
        assertEquals(3L, activityDetail.statistics.executionCount)
        assertEquals(Duration.ofMinutes(210), activityDetail.statistics.durations.total)
        val sequenceDetail =
            repository.seriesDetail(
                StatisticsSeriesId("sequence-series"),
                StatisticsPeriod.AllTime,
            ) as StatisticsSeriesDetail.Sequence
        assertEquals(
            repository.sequenceSeries(StatisticsSeriesId("sequence-series"), StatisticsPeriod.AllTime),
            sequenceDetail.statistics,
        )
        assertEquals(Duration.ofMinutes(15), sequenceDetail.statistics.totalPauseIdleDuration)
        assertEquals(1L, all.oneOffActivityExecutionCount)
        assertNull(all.calendarDayCount)
        assertNull(all.averageTrackedMillisecondsPerCalendarDay)

        val aug20 = repository.global(StatisticsPeriod.Day(LocalDate.parse(AUG_20)))
        assertEquals(Duration.ofMinutes(210), aug20.totalTrackedDuration)
        assertEquals(3L, aug20.topLevelExecutionCount)
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
        assertEquals(1L, repository.global(StatisticsPeriod.Day(LocalDate.parse(AUG_21))).topLevelExecutionCount)

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
        assertEquals(3L, repository.global(StatisticsPeriod.AllTime).topLevelExecutionCount)

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
        assertEquals(1L, repository.global(StatisticsPeriod.AllTime).oneOffActivityExecutionCount)
    }

    @Test
    fun deletedSequenceChildLeavesDurableValuesButExitsActivityStatisticsOnly() {
        series("activity-series", "ACTIVITY", "Activity")
        series("sequence-series", "SEQUENCE", "Sequence")
        activityTemplateWithFields()
        activitySnapshot("child-history", "activity-series", fields = true)
        val (sequenceId, occurrenceId) =
            completedSequence(
                "sequence-history",
                "sequence-series",
                "child-history",
                10 * MINUTE,
                0,
                AUG_20,
            )
        completedActivity(
            "sequence-child",
            "child-history",
            "activity-series",
            10 * MINUTE,
            AUG_20,
            context = "SEQUENCE_CHILD",
            sequenceExecutionId = sequenceId,
            occurrenceId = occurrenceId,
            values =
                listOf(
                    numberValue("sequence-child", "child-history-number", 12),
                    categoryValue("sequence-child", "child-history-category", "child-history-tempo"),
                ),
        )
        val root = requireNotNull(database.sequenceExecutionDao().getById(sequenceId))
        val sequenceBefore = repository.sequenceSeries(StatisticsSeriesId("sequence-series"), StatisticsPeriod.AllTime)
        val globalBefore = repository.global(StatisticsPeriod.AllTime)
        assertEquals(
            1L,
            repository.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime).executionCount,
        )
        assertEquals(
            1L,
            repository
                .numberFieldStatistics(
                    StatisticsSeriesId("activity-series"),
                    StatisticsFieldId.Activity(ActivityTemplateFieldId("number-source")),
                    StatisticsPeriod.AllTime,
                ).recordedCount,
        )

        SequenceHistoryCommandRepository(database).deleteChildHistory(
            SequenceExecutionId(sequenceId),
            SequenceChildHistoryDeletionCommand(
                Instant.ofEpochMilli(root.updatedAtMs),
                SequenceOccurrenceId(occurrenceId),
                ActivityExecutionId("sequence-child"),
            ),
            Instant.ofEpochMilli(root.updatedAtMs + 1),
        )

        val activity = repository.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime)
        val number =
            repository.numberFieldStatistics(
                StatisticsSeriesId("activity-series"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId("number-source")),
                StatisticsPeriod.AllTime,
            )
        val category =
            repository.categoryFieldStatistics(
                StatisticsSeriesId("activity-series"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId("category-source")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(0L, activity.executionCount)
        assertEquals(0L, activity.durations.sampleCount)
        assertEquals(0L, number.relevantExecutionCount)
        assertEquals(0L, number.recordedCount)
        assertEquals(0L, category.relevantExecutionCount)
        assertEquals(0L, category.recordedCount)
        assertEquals(2, database.activityExecutionDao().getValues("sequence-child").size)
        assertEquals(
            sequenceBefore,
            repository.sequenceSeries(StatisticsSeriesId("sequence-series"), StatisticsPeriod.AllTime),
        )
        assertEquals(globalBefore, repository.global(StatisticsPeriod.AllTime))
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
        assertEquals(4L, detail.executionCount)
        assertEquals(2L, detail.durations.sampleCount)
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
            setOf("number-source", "category-source", "calories-source", "text-source"),
            catalog.mapTo(hashSetOf()) { it.id.value },
        )
        assertEquals("Distance", catalog.single { it.id.value == "number-source" }.displayName)
        assertEquals("kcal", catalog.single { it.id.value == "calories-source" }.unit)
        val bundled =
            repository.seriesDetail(
                StatisticsSeriesId("activity-series"),
                StatisticsPeriod.AllTime,
            ) as StatisticsSeriesDetail.Activity
        assertEquals(detail, bundled.statistics)
        assertEquals(catalog.map { it.id }, bundled.fields.map { it.field.id })
        assertEquals(
            4,
            bundled.fields
                .map { it.field.id }
                .toSet()
                .size,
        )
        assertEquals(
            catalog.single { it.id.value == "text-source" },
            (bundled.fields.single { it.field.id.value == "text-source" } as StatisticsFieldDetail.Text).field,
        )

        val number =
            repository.numberFieldStatistics(
                StatisticsSeriesId("activity-series"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId("number-source")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(4L, number.relevantExecutionCount)
        assertEquals(1L, number.recordedCount)
        assertEquals(3L, number.missingCount)
        assertEquals(0L, number.values.totalScaled.longValueExact())
        assertEquals(ExactValue.of(0, 1), number.values.averageScaled)
        assertEquals(0L, number.values.minimumScaled)
        assertEquals(
            number,
            (
                bundled.fields.single {
                    it.field.id.value == "number-source"
                } as StatisticsFieldDetail.Number
            ).statistics,
        )
        val zeroRecorded =
            (bundled.fields.single { it.field.id.value == "calories-source" } as StatisticsFieldDetail.Number)
                .statistics
        assertEquals(0L, zeroRecorded.recordedCount)
        assertEquals(4L, zeroRecorded.missingCount)
        assertNull(zeroRecorded.values.averageScaled)

        val category =
            repository.categoryFieldStatistics(
                StatisticsSeriesId("activity-series"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId("category-source")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(4L, category.relevantExecutionCount)
        assertEquals(2L, category.recordedCount)
        assertEquals(2L, category.missingCount)
        assertEquals("Tempo", category.values.single().displayLabel)
        assertEquals(2L, category.values.single().count)
        assertEquals(
            category,
            (
                bundled.fields.single {
                    it.field.id.value == "category-source"
                } as StatisticsFieldDetail.Category
            ).statistics,
        )
        assertEquals(
            "tempo-source",
            category.values
                .single()
                .id.value,
        )
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
            30L,
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
        assertEquals(1L, editedCategory.recordedCount)
        assertEquals(3L, editedCategory.missingCount)
        assertEquals(1L, editedCategory.values.single().count)

        val emptyPeriod =
            repository.seriesDetail(
                StatisticsSeriesId("activity-series"),
                StatisticsPeriod.Day(LocalDate.parse("2026-08-22")),
            ) as StatisticsSeriesDetail.Activity
        val emptyNumber =
            (emptyPeriod.fields.single { it.field.id.value == "number-source" } as StatisticsFieldDetail.Number)
                .statistics
        val emptyCategory =
            (emptyPeriod.fields.single { it.field.id.value == "category-source" } as StatisticsFieldDetail.Category)
                .statistics
        assertEquals(0L, emptyNumber.relevantExecutionCount)
        assertEquals(0L, emptyNumber.recordedCount)
        assertNull(emptyNumber.coverage.exactValue)
        assertNull(emptyNumber.values.averageScaled)
        assertEquals(0L, emptyCategory.relevantExecutionCount)
        assertEquals(0L, emptyCategory.recordedCount)
        assertNull(emptyCategory.coverage.exactValue)
        assertTrue(emptyCategory.values.all { it.recordedShare.exactValue == null })

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
        assertThrows(IllegalArgumentException::class.java) {
            repository.seriesDetail(StatisticsSeriesId(ONE_OFF), StatisticsPeriod.AllTime)
        }
        val oneOffSummary =
            repository.seriesSummaries(StatisticsPeriod.AllTime).single { it.series.id.value == ONE_OFF }
        assertEquals(1L, oneOffSummary.executionCount)
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
        assertEquals(2L, statistics.relevantExecutionCount)
        assertEquals(1L, statistics.recordedCount)
        assertEquals(1L, statistics.missingCount)
        assertEquals(7L, statistics.values.totalScaled.longValueExact())
        val detail =
            repository.seriesDetail(
                StatisticsSeriesId("sequence-series"),
                StatisticsPeriod.AllTime,
            ) as StatisticsSeriesDetail.Sequence
        assertEquals(
            repository.sequenceSeries(StatisticsSeriesId("sequence-series"), StatisticsPeriod.AllTime),
            detail.statistics,
        )
        assertEquals(statistics, (detail.fields.single() as StatisticsFieldDetail.Number).statistics)
    }

    @Test
    fun bundledCategoryKeepsSourceAndSnapshotFallbackIdentitiesDespiteEqualLabels() {
        series("activity-series", "ACTIVITY", "Workout")
        activityTemplateWithFields()
        activitySnapshot("source", "activity-series", fields = true)
        activitySnapshot("fallback", "activity-series", fields = true)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_snapshot_category_options SET source_option_id = NULL WHERE id = 'fallback-tempo'",
        )
        completedActivity(
            "source-execution",
            "source",
            "activity-series",
            MINUTE,
            AUG_20,
            values = listOf(categoryValue("source-execution", "source-category", "source-tempo")),
        )
        completedActivity(
            "fallback-execution",
            "fallback",
            "activity-series",
            MINUTE,
            AUG_20,
            values = listOf(categoryValue("fallback-execution", "fallback-category", "fallback-tempo")),
        )

        val canonical =
            repository.categoryFieldStatistics(
                StatisticsSeriesId("activity-series"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId("category-source")),
                StatisticsPeriod.AllTime,
            )
        val detail =
            repository.seriesDetail(
                StatisticsSeriesId("activity-series"),
                StatisticsPeriod.AllTime,
            ) as StatisticsSeriesDetail.Activity
        val projected =
            (detail.fields.single { it.field.id.value == "category-source" } as StatisticsFieldDetail.Category)
                .statistics
        assertEquals(canonical, projected)
        assertEquals(2, projected.values.size)
        assertEquals(setOf("tempo-source", "fallback-tempo"), projected.values.map { it.id.value }.toSet())
        assertTrue(projected.values.any { it.id is StatisticsCategoryOptionId.SnapshotFallback })
        assertEquals(listOf(1L, 1L), projected.values.map { it.count })
        assertEquals(
            listOf(ExactValue.of(1, 2), ExactValue.of(1, 2)),
            projected.values.map { it.recordedShare.exactValue },
        )
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
        val archivedDetail =
            repository.seriesDetail(
                StatisticsSeriesId("archived-series"),
                StatisticsPeriod.AllTime,
            ) as StatisticsSeriesDetail.Activity
        assertEquals(StatisticsSeriesId("archived-series"), archivedDetail.statistics.series.id)
        assertEquals(StatisticsSeriesSourceState.ARCHIVED_SOURCE, archivedDetail.statistics.series.sourceState)
        val sourcelessDetail =
            repository.seriesDetail(
                StatisticsSeriesId("sourceless-series"),
                StatisticsPeriod.AllTime,
            ) as StatisticsSeriesDetail.Sequence
        assertEquals(StatisticsSeriesSourceState.NO_CURRENT_SOURCE, sourcelessDetail.statistics.series.sourceState)
        assertEquals(1L, sourcelessDetail.statistics.executionCount)
        val summaries = repository.seriesSummaries(StatisticsPeriod.AllTime).associateBy { it.series.id.value }
        assertEquals(1L, summaries.getValue("sourceless-series").executionCount)
        assertEquals(Duration.ofMinutes(1), summaries.getValue("sourceless-series").totalDuration)
        assertEquals(1L, summaries.getValue("active-series").executionCount)

        database.activityTemplateDao().archive("active", 20)
        assertEquals(
            StatisticsSeriesSourceState.ARCHIVED_SOURCE,
            repository.seriesCatalog().single { it.id.value == "active-series" }.sourceState,
        )
        assertEquals(
            1L,
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
    fun overviewMatchesCanonicalReadersAndPreservesEveryCatalogSeries() {
        series("active-series", "ACTIVITY", "Active")
        series("archived-series", "ACTIVITY", "Archived")
        series("sourceless-series", "SEQUENCE", "Old sequence", archivedAt = 50)
        activityTemplate("active", "Active", "active-series")
        activityTemplate("archived", "Archived", "archived-series", deletedAt = 10)
        activitySnapshot("active-history", "active-series")
        completedActivity("active-execution", "active-history", "active-series", MINUTE, AUG_20)
        activitySnapshot("source-free", null)
        completedSequence("old-sequence", "sourceless-series", "source-free", MINUTE, 0, AUG_20)

        val period = StatisticsPeriod.Day(LocalDate.parse(AUG_20))
        val overview = repository.overview(period)

        assertEquals(repository.global(period), overview.global)
        assertEquals(repository.seriesSummaries(period), overview.series)
        assertEquals(
            mapOf(
                "active-series" to StatisticsSeriesSourceState.ACTIVE_SOURCE,
                "archived-series" to StatisticsSeriesSourceState.ARCHIVED_SOURCE,
                "sourceless-series" to StatisticsSeriesSourceState.NO_CURRENT_SOURCE,
                ONE_OFF to StatisticsSeriesSourceState.SYSTEM_ONE_OFF,
            ),
            overview.series.associate { it.series.id.value to it.series.sourceState },
        )
        assertEquals(0L, overview.series.single { it.series.id.value == "archived-series" }.executionCount)
        assertEquals(
            StatisticsSeriesKind.ONE_OFF_BUCKET,
            overview.series
                .single { it.series.id.value == ONE_OFF }
                .series.kind,
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
                PLAN_CREATED,
                PLAN_CREATED,
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
        assertEquals(2L, split.revision)
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
            2L,
            repository
                .activitySeries(
                    StatisticsSeriesId("old-activity-series"),
                    StatisticsPeriod.AllTime,
                ).executionCount,
        )
        assertEquals(
            1L,
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
        assertEquals(2L, database.sequenceTemplateDao().getById("sequence")?.revision)
        val sequenceAfterSplit = database.sequenceTemplateDao().getAggregate("sequence")!!
        assertEquals(sequenceBeforeSplit.settings, sequenceAfterSplit.settings)
        assertEquals(sequenceBeforeSplit.fields, sequenceAfterSplit.fields)
        assertEquals(sequenceBeforeSplit.options, sequenceAfterSplit.options)
        assertEquals(sequenceBeforeSplit.nodes, sequenceAfterSplit.nodes)
        assertEquals(sequenceBeforeSplit.userState, sequenceAfterSplit.userState)
        completedSequence("new-sequence-root", "new-sequence-series", "direct-new", 7 * MINUTE, 0, AUG_20)
        assertEquals(
            1L,
            repository
                .sequenceSeries(
                    StatisticsSeriesId("old-sequence-series"),
                    StatisticsPeriod.AllTime,
                ).executionCount,
        )
        assertEquals(
            1L,
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
        assertEquals(3L, database.activityTemplateDao().getById("activity")?.revision)
        assertEquals(millis("2026-08-20T13:00:00Z"), database.activityTemplateDao().getById("activity")?.updatedAtMs)
    }

    @Test
    fun activityCategoryOptionsStayWithinTheirSeriesLineageAfterSplit() {
        series("activity-series-a", "ACTIVITY", "Activity A")
        val field =
            ActivityTemplateFieldEntity(
                "activity-lineage-field",
                "activity-lineage-template",
                0,
                "Choice",
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
            )
        val optionX = ActivityTemplateCategoryOptionEntity("activity-option-x", field.id, 0, "X")
        activityTemplate(
            "activity-lineage-template",
            "Activity A",
            "activity-series-a",
            fields = listOf(field),
            options = listOf(optionX),
        )
        activityCategorySnapshot("activity-lineage-snapshot", "activity-series-a", field.id, optionX.id)
        completedActivity(
            "activity-lineage-execution",
            "activity-lineage-snapshot",
            "activity-series-a",
            MINUTE,
            AUG_20,
            values =
                listOf(
                    categoryValue(
                        "activity-lineage-execution",
                        "activity-lineage-snapshot-field",
                        "activity-lineage-snapshot-option",
                    ),
                ),
        )

        repository.startNewActivityStatisticsSeries(
            ActivityTemplateId("activity-lineage-template"),
            Instant.ofEpochMilli(10),
        )
        database.activityTemplateDao().insertOptions(
            listOf(ActivityTemplateCategoryOptionEntity("activity-option-y", field.id, 1, "Y")),
        )

        val oldBeforeRename =
            repository.categoryFieldStatistics(
                StatisticsSeriesId("activity-series-a"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId(field.id)),
                StatisticsPeriod.AllTime,
            )
        assertEquals(1L, oldBeforeRename.values.single().count)
        assertEquals("X", oldBeforeRename.values.single().displayLabel)
        val current =
            repository.categoryFieldStatistics(
                StatisticsSeriesId("generated-series"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId(field.id)),
                StatisticsPeriod.AllTime,
            )
        assertEquals(mapOf("X" to 0L, "Y" to 0L), current.values.associate { it.displayLabel to it.count })

        database.activityTemplateDao().updateOptions(listOf(optionX.copy(label = "X renamed")))
        val oldAfterRename =
            repository.categoryFieldStatistics(
                StatisticsSeriesId("activity-series-a"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId(field.id)),
                StatisticsPeriod.AllTime,
            )
        assertEquals(oldBeforeRename.values.single().id, oldAfterRename.values.single().id)
        assertEquals(1L, oldAfterRename.values.single().count)
        assertEquals("X renamed", oldAfterRename.values.single().displayLabel)
    }

    @Test
    fun sequenceCategoryOptionsStayWithinTheirSeriesLineageAfterSplit() {
        series("sequence-series-a", "SEQUENCE", "Sequence A")
        val field =
            SequenceTemplateFieldEntity(
                "sequence-lineage-field",
                "sequence-lineage-template",
                0,
                "Choice",
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
            )
        val optionX = SequenceTemplateCategoryOptionEntity("sequence-option-x", field.id, 0, "X")
        sequenceTemplate(
            "sequence-lineage-template",
            "Sequence A",
            "sequence-series-a",
            fields = listOf(field),
            options = listOf(optionX),
        )
        activitySnapshot("sequence-lineage-child", null)
        completedSequence(
            "sequence-lineage-execution",
            "sequence-series-a",
            "sequence-lineage-child",
            MINUTE,
            0,
            AUG_20,
            categoryFieldId = field.id,
            categoryOptionId = optionX.id,
        )

        repository.startNewSequenceStatisticsSeries(
            SequenceTemplateId("sequence-lineage-template"),
            Instant.ofEpochMilli(10),
        )
        val currentTemplate = database.sequenceTemplateDao().getAggregate("sequence-lineage-template")!!
        database.sequenceTemplateDao().updateSemanticAggregate(
            SequenceTemplateSemanticUpdate(
                expectedRevision = 2,
                template = currentTemplate.template.copy(revision = 3, updatedAtMs = 20),
                settings = currentTemplate.settings,
                fields = currentTemplate.fields,
                options =
                    currentTemplate.options +
                        SequenceTemplateCategoryOptionEntity("sequence-option-y", field.id, 1, "Y"),
                nodes = currentTemplate.nodes,
                stepOverrides = currentTemplate.stepOverrides,
            ),
        )

        val old =
            repository.categoryFieldStatistics(
                StatisticsSeriesId("sequence-series-a"),
                StatisticsFieldId.Sequence(SequenceTemplateFieldId(field.id)),
                StatisticsPeriod.AllTime,
            )
        assertEquals(1L, old.values.single().count)
        assertEquals("X", old.values.single().displayLabel)
        val current =
            repository.categoryFieldStatistics(
                StatisticsSeriesId("generated-series"),
                StatisticsFieldId.Sequence(SequenceTemplateFieldId(field.id)),
                StatisticsPeriod.AllTime,
            )
        assertEquals(mapOf("X" to 0L, "Y" to 0L), current.values.associate { it.displayLabel to it.count })
    }

    @Test
    fun seriesSplitRejectsStalePersistedMillisecondForBothKinds() {
        series("activity-series-a", "ACTIVITY", "Activity A")
        series("sequence-series-a", "SEQUENCE", "Sequence A")
        activityTemplate(
            "stale-activity",
            "Activity A",
            "activity-series-a",
            revision = 7,
            createdAtMs = 100,
            updatedAtMs = 200,
        )
        sequenceTemplate(
            "stale-sequence",
            "Sequence A",
            "sequence-series-a",
            revision = 9,
            createdAtMs = 100,
            updatedAtMs = 200,
        )
        val activityBefore = database.activityTemplateDao().getById("stale-activity")!!
        val sequenceBefore = database.sequenceTemplateDao().getById("stale-sequence")!!
        val activitySeriesBefore = database.statisticsSeriesDao().getById("activity-series-a")!!
        val sequenceSeriesBefore = database.statisticsSeriesDao().getById("sequence-series-a")!!
        val staleAt = Instant.ofEpochMilli(199).plusNanos(999_999)

        val activityFailure =
            assertThrows(IllegalArgumentException::class.java) {
                repository.startNewActivityStatisticsSeries(ActivityTemplateId("stale-activity"), staleAt)
            }
        val sequenceFailure =
            assertThrows(IllegalArgumentException::class.java) {
                repository.startNewSequenceStatisticsSeries(SequenceTemplateId("stale-sequence"), staleAt)
            }

        assertEquals("Statistics Series split time is out of order", activityFailure.message)
        assertEquals("Statistics Series split time is out of order", sequenceFailure.message)
        assertNull(database.statisticsSeriesDao().getById("generated-series"))
        assertEquals(activityBefore, database.activityTemplateDao().getById("stale-activity"))
        assertEquals(sequenceBefore, database.sequenceTemplateDao().getById("stale-sequence"))
        assertEquals(activitySeriesBefore, database.statisticsSeriesDao().getById("activity-series-a"))
        assertEquals(sequenceSeriesBefore, database.statisticsSeriesDao().getById("sequence-series-a"))
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
        assertEquals(2_000L, bounded.executionCount)
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
        revision: Long = 1,
        createdAtMs: Long = 0,
        updatedAtMs: Long = 0,
    ) = database.activityTemplateDao().insertAggregate(
        ActivityTemplateAggregateEntity(
            ActivityTemplateEntity(
                id,
                name,
                null,
                "STOPWATCH",
                null,
                seriesId,
                revision,
                createdAtMs,
                updatedAtMs,
                deletedAt,
                null,
            ),
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
                ActivityTemplateFieldEntity(
                    "text-source",
                    "activity",
                    3,
                    "Notes",
                    "TEXT",
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
        options: List<SequenceTemplateCategoryOptionEntity> = emptyList(),
        revision: Long = 1,
        createdAtMs: Long = 0,
        updatedAtMs: Long = 0,
    ) = database.sequenceTemplateDao().insertAggregate(
        SequenceTemplateAggregateEntity(
            SequenceTemplateEntity(id, name, null, seriesId, revision, createdAtMs, updatedAtMs, null, null),
            SequenceTemplateSettingsEntity(id),
            SequenceTemplateUserStateEntity(id, null, null),
            fields = fields,
            options = options,
        ),
    )

    private fun activityCategorySnapshot(
        id: String,
        seriesId: String,
        sourceFieldId: String,
        sourceOptionId: String,
    ) = database.activitySnapshotDao().insertAggregate(
        ActivitySnapshotAggregateEntity(
            ActivitySnapshotEntity(id, id, null, "STOPWATCH", null, null, null, seriesId, false, 0),
            ActivitySnapshotSettingsEntity(id),
            fields =
                listOf(
                    ActivitySnapshotFieldEntity(
                        "$id-field",
                        id,
                        sourceFieldId,
                        0,
                        "Choice",
                        null,
                        "CATEGORY",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                    ),
                ),
            options =
                listOf(
                    ActivitySnapshotCategoryOptionEntity(
                        "$id-option",
                        "$id-field",
                        sourceOptionId,
                        0,
                        "X",
                        null,
                    ),
                ),
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
        categoryFieldId: String? = null,
        categoryOptionId: String? = null,
    ): Pair<String, String> {
        require((categoryFieldId == null) == (categoryOptionId == null))
        val snapshotId = "$id-snapshot"
        val occurrenceId = "$id-occurrence"
        val started = START_2330
        val ended = started + activeMs + pauseMs
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity(snapshotId, id, null, null, null, seriesId, 0),
                sequenceSettings(snapshotId),
                fields =
                    buildList {
                        if (withNumberField) {
                            add(
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
                        }
                        categoryFieldId?.let { sourceFieldId ->
                            add(
                                SequenceSnapshotFieldEntity(
                                    "$snapshotId-category",
                                    snapshotId,
                                    sourceFieldId,
                                    0,
                                    "Choice",
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
                    },
                options =
                    categoryOptionId
                        ?.let { sourceOptionId ->
                            listOf(
                                SequenceSnapshotCategoryOptionEntity(
                                    "$snapshotId-option",
                                    "$snapshotId-category",
                                    sourceOptionId,
                                    0,
                                    "X",
                                    null,
                                ),
                            )
                        }.orEmpty(),
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
                    buildList {
                        numberValue?.let {
                            add(SequenceExecutionFieldValueEntity(id, "$snapshotId-number", it, null, null))
                        }
                        categoryOptionId?.let {
                            add(
                                SequenceExecutionFieldValueEntity(
                                    id,
                                    "$snapshotId-category",
                                    null,
                                    "$snapshotId-option",
                                    null,
                                ),
                            )
                        }
                    },
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
        val PLAN_CREATED = millis("2026-08-20T09:00:00Z")

        fun instant(value: String): Instant = Instant.parse(value)

        fun millis(value: String): Long = instant(value).toEpochMilli()
    }
}
