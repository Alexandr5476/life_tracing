@file:Suppress("LongMethod", "LongParameterList")

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityHistoryCorrection
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionCommand
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalCommand
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalMode
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
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class StatisticsRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var repository: StatisticsRepository
    private val observedSql = Collections.synchronizedList(mutableListOf<String>())
    private val observedNumberReads = Collections.synchronizedList(mutableListOf<Pair<String, List<Any?>>>())

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .setQueryCallback(::observeStatisticsQuery, Executor { it.run() })
                .build()
        repository = StatisticsRepository(database) { StatisticsSeriesId("generated-series") }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun seriesDetailRejectsRawNumberSampleMaterialization() {
        numberHistorySources()
        repeat(128) { index ->
            completedActivity(
                "bounded-detail-$index",
                "number-history",
                "activity-series",
                null,
                AUG_20,
                values = listOf(numberValue("bounded-detail-$index", "number-history-number", index.toLong())),
            )
        }
        observedSql.clear()
        val detail = repository.seriesDetail(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime)
        val queries = synchronized(observedSql) { observedSql.toList() }
        assertTrue(
            "Series Detail must return scalar summaries, never historical Number samples",
            queries.none { it.contains("SELECT fields.source_field_id, recorded_values.number_scaled") },
        )
        val number =
            detail.fields
                .filterIsInstance<StatisticsFieldDetail.Number>()
                .single { it.field.id.value == "number-source" }
                .statistics
        assertEquals(128L, number.recordedCount)
        assertEquals(BigInteger.valueOf(8_128), number.values.totalScaled)
        assertEquals(ExactValue.of(127, 2), number.values.medianScaled)
        assertEquals(
            repository.numberFieldStatistics(
                StatisticsSeriesId("activity-series"),
                number.field.id,
                StatisticsPeriod.AllTime,
            ),
            number,
        )
    }

    @Test
    fun seriesDetailBoundsNumberRowsAndQueriesAcrossGrowingHistory() {
        numberHistorySources()
        val values = mutableListOf<Long>()
        val queryCounts = mutableMapOf<String, Int>()
        var previous = 0
        for (size in listOf(1, 32, 512)) {
            database.runInTransaction {
                for (index in previous until size) {
                    val value =
                        when (index % 5) {
                            0 -> Long.MIN_VALUE
                            1 -> Long.MAX_VALUE
                            2 -> 0L
                            else -> index.toLong()
                        }
                    values.add(value)
                    completedActivity(
                        "detail-growth-$index",
                        "number-history",
                        "activity-series",
                        null,
                        AUG_20,
                        values = listOf(numberValue("detail-growth-$index", "number-history-number", value)),
                    )
                    completedSequence(
                        "detail-growth-root-$index",
                        "sequence-series",
                        "number-history",
                        1,
                        0,
                        AUG_20,
                        withNumberField = true,
                        numberValue = value,
                    )
                }
            }
            previous = size
            for ((series, field) in numberHistoryIdentities()) {
                observedSql.clear()
                val detail = repository.seriesDetail(series, StatisticsPeriod.AllTime)
                val queries =
                    synchronized(observedSql) {
                        observedSql.filter {
                            (it.trimStart().startsWith("SELECT") || it.trimStart().startsWith("WITH")) &&
                                !it.contains("room_table_modification_log")
                        }
                    }
                val originalCount = queryCounts.getOrPut(series.value) { queries.size }
                assertEquals(originalCount, queries.size)
                assertEquals(1, queries.count { it.contains(" AS bit_counts ") })
                assertEquals(1, queries.count { it.contains("CROSS JOIN") && it.contains("median_values") })
                assertEquals(1, queries.count { it.startsWith("SELECT COUNT(*) FROM (") && it.contains("LIMIT") })
                assertTrue(queries.none { it.contains("SELECT fields.source_field_id, recorded_values.number_scaled") })
                val number =
                    detail.fields
                        .filterIsInstance<StatisticsFieldDetail.Number>()
                        .single {
                            it.field.id ==
                                field
                        }.statistics
                assertEquals(
                    com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
                        .numbers(values),
                    number.values,
                )
                assertEquals(repository.numberFieldStatistics(series, field, StatisticsPeriod.AllTime), number)
                val dao = database.statisticsDao()
                val summaries =
                    when (field) {
                        is StatisticsFieldId.Activity -> dao.activityNumberSummaries(series.value, null, null)
                        is StatisticsFieldId.Sequence -> dao.sequenceNumberSummaries(series.value, null, null)
                    }
                assertEquals(1, summaries.size)
                assertEquals(
                    64,
                    summaries
                        .single()
                        .bitCounts
                        .split(',')
                        .size,
                )
                val selection =
                    when (field) {
                        is StatisticsFieldId.Activity ->
                            dao.activitySeriesNumberSelection(
                                series.value,
                                field.value,
                                null,
                                null,
                                size.toLong(),
                            )
                        is StatisticsFieldId.Sequence ->
                            dao.sequenceSeriesNumberSelection(
                                series.value,
                                field.value,
                                null,
                                null,
                                size.toLong(),
                            )
                    }
                assertEquals(size.toLong(), selection.sampleCount)
                assertEquals(if (size % 2 == 0) 2L else 1L, selection.selectedCount)
            }
        }
    }

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
                        if (index == 2 || index == 4) "Same label" else "Field $index",
                        when {
                            index == 36 -> "TEXT"
                            index % 2 == 0 -> "NUMBER"
                            else -> "CATEGORY"
                        },
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
                        if (field.fieldType == "NUMBER" && index != 38 && execution % 2 == 0) {
                            numberValue(
                                "$prefix-execution-$execution",
                                snapshotField,
                                when (index) {
                                    4 -> Long.MAX_VALUE
                                    6 -> Long.MIN_VALUE
                                    8 -> if (execution % 4 == 0) Long.MIN_VALUE else Long.MAX_VALUE
                                    else -> execution.toLong()
                                },
                            )
                        } else if (index % 2 == 1 && index != 37 && execution % 3 != 2) {
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
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_templates SET deleted_at_ms = 100 WHERE id = 'large'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_template_fields SET deleted_at_ms = 100 WHERE id = 'large-field-2'",
        )

        fun read(prefix: String): Pair<StatisticsSeriesDetail.Activity, Int> {
            observedSql.clear()
            val detail =
                repository.seriesDetail(
                    StatisticsSeriesId(prefix),
                    StatisticsPeriod.AllTime,
                ) as StatisticsSeriesDetail.Activity
            val historyReads =
                synchronized(observedSql) {
                    observedSql.count { it.contains("activity_executions AS executions", ignoreCase = true) }
                }
            return detail to historyReads
        }
        val (small, smallReads) = read("small")
        val (large, largeReads) = read("large")
        assertEquals(2, small.fields.size)
        assertEquals(40, large.fields.size)
        assertTrue(smallReads > 0)
        val smallNumbers =
            small.fields.filterIsInstance<StatisticsFieldDetail.Number>().count {
                it.statistics.recordedCount >
                    0
            }
        val largeNumbers =
            large.fields.filterIsInstance<StatisticsFieldDetail.Number>().count {
                it.statistics.recordedCount >
                    0
            }
        assertEquals(smallReads + largeNumbers - smallNumbers, largeReads)
        assertTrue(largeReads <= 6 + largeNumbers)
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
                    is StatisticsFieldDetail.Text -> detail.field
                }
            assertEquals(
                expected,
                when (detail) {
                    is StatisticsFieldDetail.Number -> detail.statistics
                    is StatisticsFieldDetail.Category -> detail.statistics
                    is StatisticsFieldDetail.Text -> detail.field
                },
            )
        }
        val numberFields =
            large.fields.filterIsInstance<StatisticsFieldDetail.Number>().associateBy {
                it.field.id.value
            }
        assertEquals(
            BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(40)),
            numberFields
                .getValue("large-field-4")
                .statistics.values.totalScaled,
        )
        assertEquals(
            BigInteger.valueOf(Long.MIN_VALUE).multiply(BigInteger.valueOf(40)),
            numberFields
                .getValue("large-field-6")
                .statistics.values.totalScaled,
        )
        assertEquals(
            ExactValue.of(-1, 2),
            numberFields
                .getValue("large-field-8")
                .statistics.values.medianScaled,
        )
        assertEquals(0L, numberFields.getValue("large-field-38").statistics.recordedCount)
        assertEquals(large.statistics.executionCount, numberFields.getValue("large-field-38").statistics.missingCount)
        assertEquals(
            BigInteger.ZERO,
            numberFields
                .getValue("large-field-38")
                .statistics.values.totalScaled,
        )
        assertNull(
            numberFields
                .getValue("large-field-38")
                .statistics.values.medianScaled,
        )
        assertEquals("Same label", numberFields.getValue("large-field-2").field.displayName)
        assertEquals("Same label", numberFields.getValue("large-field-4").field.displayName)
        assertEquals(
            listOf("large-field-36"),
            large.fields.filterIsInstance<StatisticsFieldDetail.Text>().map {
                it.field.id.value
            },
        )
        val unrecordedCategory =
            large.fields
                .filterIsInstance<StatisticsFieldDetail.Category>()
                .single { it.field.id.value == "large-field-37" }
                .statistics
        assertEquals(0L, unrecordedCategory.recordedCount)
        assertTrue(unrecordedCategory.values.isNotEmpty())
        assertTrue(unrecordedCategory.values.all { it.count == 0L })
        assertEquals(repository.fieldCatalog(StatisticsSeriesId("large")), large.fields.map { it.field })
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
                        if (index == 2 || index == 4) "Same label" else "Field $index",
                        when {
                            index == 36 -> "TEXT"
                            index % 2 == 0 -> "NUMBER"
                            else -> "CATEGORY"
                        },
                        if (index % 2 == 0 && index != 36) "points" else null,
                        if (index % 2 == 0 && index != 36) 0 else null,
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
                            field.fieldType == "NUMBER" &&
                                index != 38 &&
                                (index in listOf(0, 4, 6, 8) || execution == 0) ->
                                SequenceExecutionFieldValueEntity(
                                    "$prefix-execution-$execution",
                                    "$snapshot-field-$index",
                                    when (index) {
                                        0 -> execution * 20L
                                        4 -> Long.MAX_VALUE
                                        6 -> Long.MIN_VALUE
                                        8 -> if (execution == 0) Long.MIN_VALUE else Long.MAX_VALUE
                                        else -> index.toLong()
                                    },
                                    null,
                                    null,
                                )
                            field.fieldType == "CATEGORY" && index != 37 && (execution == 0 || index == 1) ->
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
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_templates SET deleted_at_ms = 100 WHERE id = 'sequence-large'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_template_fields SET deleted_at_ms = 100 WHERE id = 'sequence-large-field-2'",
        )
        val period = StatisticsPeriod.Custom(LocalDate.parse(AUG_20), LocalDate.parse(AUG_21))

        fun read(id: String): Pair<StatisticsSeriesDetail.Sequence, Int> {
            observedSql.clear()
            val detail =
                repository.seriesDetail(StatisticsSeriesId(id), period) as StatisticsSeriesDetail.Sequence
            val reads =
                synchronized(observedSql) {
                    observedSql.count { it.contains("sequence_executions AS executions", ignoreCase = true) }
                }
            return detail to reads
        }
        val (small, smallReads) = read("sequence-small")
        val (large, largeReads) = read("sequence-large")
        assertEquals(2, small.fields.size)
        assertEquals(40, large.fields.size)
        assertTrue(smallReads > 0)
        val smallNumbers =
            small.fields.filterIsInstance<StatisticsFieldDetail.Number>().count {
                it.statistics.recordedCount >
                    0
            }
        val largeNumbers =
            large.fields.filterIsInstance<StatisticsFieldDetail.Number>().count {
                it.statistics.recordedCount >
                    0
            }
        assertEquals(smallReads + largeNumbers - smallNumbers, largeReads)
        assertTrue(largeReads <= 6 + largeNumbers)
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
                    is StatisticsFieldDetail.Text -> detail.field
                }
            assertEquals(
                expected,
                when (detail) {
                    is StatisticsFieldDetail.Number -> detail.statistics
                    is StatisticsFieldDetail.Category -> detail.statistics
                    is StatisticsFieldDetail.Text -> detail.field
                },
            )
        }
        val numberFields =
            large.fields.filterIsInstance<StatisticsFieldDetail.Number>().associateBy {
                it.field.id.value
            }
        assertEquals(
            BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2)),
            numberFields
                .getValue("sequence-large-field-4")
                .statistics.values.totalScaled,
        )
        assertEquals(
            BigInteger.valueOf(Long.MIN_VALUE).multiply(BigInteger.valueOf(2)),
            numberFields
                .getValue("sequence-large-field-6")
                .statistics.values.totalScaled,
        )
        assertEquals(
            ExactValue.of(-1, 2),
            numberFields
                .getValue("sequence-large-field-8")
                .statistics.values.medianScaled,
        )
        assertEquals(0L, numberFields.getValue("sequence-large-field-38").statistics.recordedCount)
        assertEquals(
            large.statistics.executionCount,
            numberFields.getValue("sequence-large-field-38").statistics.missingCount,
        )
        assertEquals(
            BigInteger.ZERO,
            numberFields
                .getValue("sequence-large-field-38")
                .statistics.values.totalScaled,
        )
        assertNull(
            numberFields
                .getValue("sequence-large-field-38")
                .statistics.values.medianScaled,
        )
        assertEquals("Same label", numberFields.getValue("sequence-large-field-2").field.displayName)
        assertEquals("Same label", numberFields.getValue("sequence-large-field-4").field.displayName)
        assertEquals(
            listOf(
                "sequence-large-field-36",
            ),
            large.fields.filterIsInstance<StatisticsFieldDetail.Text>().map {
                it.field.id.value
            },
        )
        val unrecordedCategory =
            large.fields
                .filterIsInstance<StatisticsFieldDetail.Category>()
                .single { it.field.id.value == "sequence-large-field-37" }
                .statistics
        assertEquals(0L, unrecordedCategory.recordedCount)
        assertTrue(unrecordedCategory.values.isNotEmpty())
        assertTrue(unrecordedCategory.values.all { it.count == 0L })
        assertEquals(repository.fieldCatalog(StatisticsSeriesId("sequence-large")), large.fields.map { it.field })
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
            day.fields.filterIsInstance<StatisticsFieldDetail.Number>().all {
                it.statistics.recordedCount ==
                    if (it.field.id.value
                            .endsWith("-38")
                    ) {
                        0L
                    } else {
                        1L
                    }
            },
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
        assertEquals(BigInteger.ZERO, number.values.totalScaled)
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
            BigInteger.valueOf(30),
            repository
                .numberFieldStatistics(
                    StatisticsSeriesId("activity-series"),
                    StatisticsFieldId.Activity(ActivityTemplateFieldId("number-source")),
                    StatisticsPeriod.AllTime,
                ).values.totalScaled,
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
        assertEquals(BigInteger.valueOf(7), statistics.values.totalScaled)
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

        statement.close()
        series("small-series", "ACTIVITY", "Small")
        activitySnapshot("small-snapshot", "small-series")
        completedActivity("small-row", "small-snapshot", "small-series", 1_000, AUG_20)

        fun selectCount(): Int =
            synchronized(observedSql) {
                observedSql.count {
                    val statement = it.trimStart()
                    val isRead =
                        statement.startsWith("SELECT", ignoreCase = true) ||
                            statement.startsWith("WITH", ignoreCase = true)
                    isRead && !statement.contains("room_table_modification_log", ignoreCase = true)
                }
            }
        observedSql.clear()
        repository.activitySeries(StatisticsSeriesId("small-series"), StatisticsPeriod.AllTime)
        val smallActivityQueries = selectCount()
        series("large-sequence-series", "SEQUENCE", "Large Sequence")
        completedSequence("large-root", "large-sequence-series", "large-snapshot", 1_000, 0, AUG_20)
        observedSql.clear()
        repository.sequenceSeries(StatisticsSeriesId("large-sequence-series"), StatisticsPeriod.AllTime)
        val smallSequenceQueries = selectCount()
        val sequenceStatement =
            database.openHelper.writableDatabase.compileStatement(
                "INSERT INTO sequence_executions " +
                    "(id,snapshot_id,statistics_series_id,status,started_at_ms,ended_at_ms,active_duration_ms," +
                    "pause_duration_ms,wall_duration_ms,original_zone_id,primary_local_date," +
                    "created_at_ms,updated_at_ms) " +
                    "VALUES (?, 'large-root-snapshot', 'large-sequence-series', 'COMPLETED', ?, ?, " +
                    "1000, 0, 1000, 'UTC', ?, ?, ?)",
            )
        sequenceStatement.use { insert ->
            database.runInTransaction {
                repeat(2_999) { index ->
                    val date = if (index < 1_999) AUG_20 else "2026-07-01"
                    val start = millis("${date}T00:00:00Z") + index * 2_000L
                    insert.bindString(1, "large-sequence-$index")
                    insert.bindLong(2, start)
                    insert.bindLong(3, start + 1_000)
                    insert.bindString(4, date)
                    insert.bindLong(5, start)
                    insert.bindLong(6, start + 1_000)
                    insert.executeInsert()
                }
            }
        }
        val periods =
            listOf(
                StatisticsPeriod.Day(LocalDate.parse(AUG_20)) to 2_000L,
                StatisticsPeriod.AllTime to 3_000L,
            )
        for ((period, expected) in periods) {
            observedSql.clear()
            val activity = repository.activitySeries(StatisticsSeriesId("large-series"), period)
            assertEquals(smallActivityQueries, selectCount())
            assertEquals(3, selectCount())
            observedSql.clear()
            val sequence = repository.sequenceSeries(StatisticsSeriesId("large-sequence-series"), period)
            assertEquals(smallSequenceQueries, selectCount())
            assertEquals(3, selectCount())
            assertEquals(expected, activity.executionCount)
            assertEquals(expected, sequence.executionCount)
            assertEquals(activity.durations, sequence.activeDurations)
            assertEquals(expected, activity.durations.sampleCount)
            assertEquals(Duration.ofMillis(expected * 1_000), activity.durations.total)
            assertEquals(ExactValue.of(1_000, 1), activity.durations.medianMilliseconds)
            assertEquals(ExactValue.of(1_000, 1), activity.durations.averageMilliseconds)
            assertEquals(Duration.ofSeconds(1), activity.durations.minimum)
            assertEquals(Duration.ofSeconds(1), activity.durations.maximum)
            val dao = database.statisticsDao()
            val start = if (period is StatisticsPeriod.Day) AUG_20 else null
            val selections =
                listOf(
                    dao.activityDurationSelection("large-series", start, start, expected),
                    dao.sequenceDurationSelection("large-sequence-series", start, start, expected),
                )
            selections.forEach {
                assertEquals(expected, it.sampleCount)
                assertEquals(2L, it.selectedCount)
                assertEquals(1_000L, it.lowerMedianMs)
                assertEquals(1_000L, it.upperMedianMs)
            }
        }
    }

    @Test
    fun orderedDurationReadersPreserveEligibilityPeriodsAndDetail() {
        series("duration-activity", "ACTIVITY", "Activity")
        series("duration-other", "ACTIVITY", "Other")
        series("duration-sequence", "SEQUENCE", "Sequence")
        series("duration-other-sequence", "SEQUENCE", "Other Sequence")
        activitySnapshot("duration-snapshot", "duration-activity")
        activitySnapshot("duration-no-live", "duration-activity", mode = "NO_LIVE_TRACKING")
        activitySnapshot("duration-other-snapshot", "duration-other")
        val activityId = StatisticsSeriesId("duration-activity")
        val sequenceId = StatisticsSeriesId("duration-sequence")
        val day = StatisticsPeriod.Day(LocalDate.parse(AUG_20))
        assertEquals(0L, repository.activitySeries(activityId, day).durations.sampleCount)
        assertEquals(0L, repository.sequenceSeries(sequenceId, day).activeDurations.sampleCount)
        completedActivity("duration-missing", "duration-no-live", activityId.value, null, AUG_20)
        val noLive = repository.activitySeries(activityId, day)
        assertEquals(1L, noLive.executionCount)
        assertEquals(0L, noLive.durations.sampleCount)
        assertEquals(Duration.ZERO, noLive.durations.total)
        assertNull(noLive.durations.minimum)
        assertNull(noLive.durations.maximum)
        assertNull(noLive.durations.medianMilliseconds)
        listOf(0L, 2L, 9L).forEachIndexed { index, duration ->
            completedActivity("duration-$index", "duration-snapshot", activityId.value, duration, AUG_20)
        }
        val child = completedSequence("duration-root", sequenceId.value, "duration-snapshot", 3, 0, AUG_20)
        completedActivity(
            "duration-child",
            "duration-snapshot",
            activityId.value,
            3,
            AUG_20,
            context = "SEQUENCE_CHILD",
            sequenceExecutionId = child.first,
            occurrenceId = child.second,
        )
        completedActivity(
            "duration-outside",
            "duration-snapshot",
            activityId.value,
            20,
            AUG_21,
            startedAtMs = millis("2026-08-21T23:30:00Z"),
        )
        completedActivity("duration-deleted", "duration-snapshot", activityId.value, 100, AUG_20)
        database.activityExecutionDao().softDelete("duration-deleted", START_2330 + 1_000)
        completedActivity("duration-other-row", "duration-other-snapshot", "duration-other", 101, AUG_20)
        for (status in listOf("RUNNING", "PAUSED")) {
            completedActivity("duration-$status", "duration-snapshot", activityId.value, null, AUG_20, status = status)
        }
        completedSequence("duration-second-root", sequenceId.value, "duration-snapshot", 2, 0, AUG_20)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_executions SET status = 'COMPLETED' WHERE id = 'duration-second-root'",
        )
        completedSequence(
            "duration-third-root",
            sequenceId.value,
            "duration-snapshot",
            9,
            0,
            AUG_21,
            startedAtMs = millis("2026-08-21T23:30:00Z"),
        )
        completedSequence("duration-other-root", "duration-other-sequence", "duration-snapshot", 101, 0, AUG_20)
        for (status in listOf("RUNNING", "PAUSED")) {
            completedSequence("duration-live-$status", sequenceId.value, "duration-snapshot", 100, 0, AUG_20)
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sequence_executions SET status = ?, ended_at_ms = NULL, active_duration_ms = NULL, " +
                    "pause_duration_ms = NULL, wall_duration_ms = NULL WHERE id = ?",
                arrayOf(status, "duration-live-$status"),
            )
        }
        val activity = repository.activitySeries(activityId, day)
        assertEquals(5L, activity.executionCount)
        assertEquals(4L, activity.durations.sampleCount)
        assertEquals(Duration.ofMillis(14), activity.durations.total)
        assertEquals(ExactValue.of(7, 2), activity.durations.averageMilliseconds)
        assertEquals(ExactValue.of(5, 2), activity.durations.medianMilliseconds)
        assertEquals(Duration.ZERO, activity.durations.minimum)
        assertEquals(Duration.ofMillis(9), activity.durations.maximum)
        val allActivity = repository.activitySeries(activityId, StatisticsPeriod.AllTime)
        assertEquals(6L, allActivity.executionCount)
        assertEquals(5L, allActivity.durations.sampleCount)
        assertEquals(Duration.ofMillis(34), allActivity.durations.total)
        assertEquals(ExactValue.of(3, 1), allActivity.durations.medianMilliseconds)
        val sequence = repository.sequenceSeries(sequenceId, day)
        assertEquals(2L, sequence.executionCount)
        assertEquals(2L, sequence.activeDurations.sampleCount)
        assertEquals(Duration.ofMillis(5), sequence.activeDurations.total)
        assertEquals(ExactValue.of(5, 2), sequence.activeDurations.medianMilliseconds)
        val allSequence = repository.sequenceSeries(sequenceId, StatisticsPeriod.AllTime)
        assertEquals(3L, allSequence.executionCount)
        assertEquals(Duration.ofMillis(14), allSequence.activeDurations.total)
        assertEquals(ExactValue.of(3, 1), allSequence.activeDurations.medianMilliseconds)
        assertEquals(activity, (repository.seriesDetail(activityId, day) as StatisticsSeriesDetail.Activity).statistics)
        assertEquals(sequence, (repository.seriesDetail(sequenceId, day) as StatisticsSeriesDetail.Sequence).statistics)
        activitySnapshot("duration-one-off", null)
        activitySnapshot("duration-one-off-no-live", null, mode = "NO_LIVE_TRACKING")
        completedActivity("duration-one-off-row", "duration-one-off", ONE_OFF, 7, AUG_20)
        completedActivity("duration-one-off-missing", "duration-one-off-no-live", ONE_OFF, null, AUG_20)
        val overview = repository.overview(day)
        assertEquals(repository.seriesSummaries(day), overview.series)
        assertEquals(repository.global(day), overview.global)
        val oneOff = overview.series.single { it.series.id.value == ONE_OFF }
        assertEquals(2L, oneOff.executionCount)
        assertEquals(1L, oneOff.durationSampleCount)
        assertEquals(Duration.ofMillis(7), oneOff.totalDuration)
        val aggregate = requireNotNull(database.statisticsDao().oneOffAggregate(ONE_OFF, AUG_20, AUG_20))
        assertEquals(7L, aggregate.minimumDurationMs)
        assertEquals(7L, aggregate.maximumDurationMs)
    }

    @Test
    fun durationSelectionRejectsCountMismatchAndInvalidCachedValues() {
        series("selection-series", "ACTIVITY", "Selection")
        series("selection-sequence", "SEQUENCE", "Sequence")
        activitySnapshot("selection-snapshot", "selection-series")
        completedActivity("selection-row", "selection-snapshot", "selection-series", 3, AUG_20)
        completedSequence("selection-root", "selection-sequence", "selection-snapshot", 3, 0, AUG_20)
        val dao = database.statisticsDao()
        val aggregate = dao.activitySeriesAggregates("selection-series", null, null).single()
        val selected = dao.activityDurationSelection("selection-series", null, null, 1)
        assertEquals(1L, selected.sampleCount)
        assertEquals(1L, selected.selectedCount)
        assertEquals(
            repository.activitySeries(StatisticsSeriesId("selection-series"), StatisticsPeriod.AllTime).durations,
            selected.readDurationDistribution(aggregate),
        )
        assertThrows(IllegalArgumentException::class.java) { selected.readDurationDistribution(null) }
        assertThrows(IllegalArgumentException::class.java) {
            selected.copy(sampleCount = 0).readDurationDistribution(aggregate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            selected.copy(selectedCount = 0).readDurationDistribution(aggregate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            selected.copy(lowerMedianMs = null).readDurationDistribution(aggregate)
        }
        val wrongRank = dao.activityDurationSelection("selection-series", null, null, 0)
        assertEquals(1L, wrongRank.sampleCount)
        assertEquals(0L, wrongRank.selectedCount)
        assertThrows(IllegalArgumentException::class.java) { wrongRank.readDurationDistribution(null) }
        val sql = database.openHelper.writableDatabase
        sql.execSQL("PRAGMA ignore_check_constraints = ON")
        try {
            sql.execSQL("UPDATE activity_executions SET active_duration_ms = -1 WHERE id = 'selection-row'")
            sql.execSQL("UPDATE sequence_executions SET active_duration_ms = -1 WHERE id = 'selection-root'")
        } finally {
            sql.execSQL("PRAGMA ignore_check_constraints = OFF")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.activitySeries(StatisticsSeriesId("selection-series"), StatisticsPeriod.AllTime)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.sequenceSeries(StatisticsSeriesId("selection-sequence"), StatisticsPeriod.AllTime)
        }
        assertEquals(-1L, database.activityExecutionDao().getById("selection-row")?.activeDurationMs)
        assertEquals(-1L, database.sequenceExecutionDao().getById("selection-root")?.activeDurationMs)
        sql.execSQL("PRAGMA ignore_check_constraints = ON")
        try {
            sql.execSQL("UPDATE activity_executions SET active_duration_ms = 0.5 WHERE id = 'selection-row'")
            sql.execSQL("UPDATE sequence_executions SET active_duration_ms = 0.5 WHERE id = 'selection-root'")
        } finally {
            sql.execSQL("PRAGMA ignore_check_constraints = OFF")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.activitySeries(StatisticsSeriesId("selection-series"), StatisticsPeriod.AllTime)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.sequenceSeries(StatisticsSeriesId("selection-sequence"), StatisticsPeriod.AllTime)
        }
    }

    @Test
    fun durationReadersSurvivePhysicalReopenAndHistoricalDeletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "statistics-duration-${UUID.randomUUID()}"
        database.close()
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = StatisticsRepository(database) { StatisticsSeriesId("unused") }
            series("reopen-activity", "ACTIVITY", "Activity")
            series("reopen-sequence", "SEQUENCE", "Sequence")
            activitySnapshot("reopen-snapshot", "reopen-activity")
            completedActivity("reopen-row", "reopen-snapshot", "reopen-activity", 3, AUG_20)
            completedSequence("reopen-root", "reopen-sequence", "reopen-snapshot", 7, 0, AUG_20)
            val activityId = StatisticsSeriesId("reopen-activity")
            val sequenceId = StatisticsSeriesId("reopen-sequence")
            val activity = repository.activitySeries(activityId, StatisticsPeriod.AllTime)
            val sequence = repository.sequenceSeries(sequenceId, StatisticsPeriod.AllTime)
            database.close()
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = StatisticsRepository(database) { StatisticsSeriesId("unused") }
            assertEquals(activity, repository.activitySeries(activityId, StatisticsPeriod.AllTime))
            assertEquals(sequence, repository.sequenceSeries(sequenceId, StatisticsPeriod.AllTime))
            database.activityExecutionDao().softDelete("reopen-row", START_2330 + 100)
            val deleted = repository.activitySeries(activityId, StatisticsPeriod.AllTime)
            assertEquals(0L, deleted.executionCount)
            assertEquals(0L, deleted.durations.sampleCount)
            assertEquals(Duration.ZERO, deleted.durations.total)
            assertNull(deleted.durations.medianMilliseconds)
            assertEquals(sequence, repository.sequenceSeries(sequenceId, StatisticsPeriod.AllTime))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun boundedNumberReadersPreserveSignedEligibilityAndPeriods() {
        numberHistorySources()
        val values = listOf(Long.MIN_VALUE, Long.MAX_VALUE, -9L, 0L, 11L)
        values.forEachIndexed { index, value ->
            completedActivity(
                "number-$index",
                "number-history",
                "activity-series",
                null,
                AUG_20,
                values = listOf(numberValue("number-$index", "number-history-number", value)),
            )
            completedSequence(
                "number-root-$index",
                "sequence-series",
                "number-history",
                1,
                0,
                AUG_20,
                withNumberField = true,
                numberValue = value,
            )
        }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_executions SET status = 'COMPLETED' WHERE id = 'number-root-0'",
        )
        completedActivity("number-missing", "number-history", "activity-series", null, AUG_20)
        completedSequence("number-root-missing", "sequence-series", "number-history", 1, 0, AUG_20)
        for ((date, value) in listOf(AUG_21 to Long.MAX_VALUE, "2026-08-24" to Long.MIN_VALUE)) {
            val performedAtMs = millis("${date}T10:00:00Z")
            repeat(2) { index ->
                val id = "number-extreme-$date-$index"
                completedActivity(
                    id,
                    "number-history",
                    "activity-series",
                    null,
                    date,
                    startedAtMs = performedAtMs,
                    values = listOf(numberValue(id, "number-history-number", value)),
                )
                completedSequence(
                    "$id-root",
                    "sequence-series",
                    "number-history",
                    1,
                    0,
                    date,
                    withNumberField = true,
                    numberValue = value,
                    startedAtMs = performedAtMs,
                )
            }
        }
        series("other-activity", "ACTIVITY", "Other")
        series("other-sequence", "SEQUENCE", "Other")
        activitySnapshot("other-number-history", "other-activity", mode = "NO_LIVE_TRACKING", fields = true)
        completedActivity(
            "other-number",
            "other-number-history",
            "other-activity",
            null,
            AUG_20,
            values = listOf(numberValue("other-number", "other-number-history-number", 999)),
        )
        completedSequence(
            "other-number-root",
            "other-sequence",
            "number-history",
            1,
            0,
            AUG_20,
            withNumberField = true,
            numberValue = 999,
        )
        // A second stable Number identity with the same display label must stay separate.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_template_fields SET name = 'Distnace' WHERE id = 'calories-source'",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO activity_snapshot_fields " +
                "(id,snapshot_id,source_field_id,position,name_at_creation,field_type,unit,display_precision) " +
                "VALUES ('other-number-field','number-history','calories-source',2,'Distnace','NUMBER','kcal',0)",
        )
        database.activityExecutionDao().upsertValue(numberValue("number-0", "other-number-field", 999))
        activitySnapshot("number-live-history", "activity-series", fields = true)
        for (status in listOf("RUNNING", "PAUSED")) {
            completedActivity(
                "number-$status",
                "number-live-history",
                "activity-series",
                null,
                AUG_20,
                status = status,
                values = listOf(numberValue("number-$status", "number-live-history-number", 100)),
            )
            completedSequence(
                "number-root-$status",
                "sequence-series",
                "number-history",
                1,
                0,
                AUG_20,
                withNumberField = true,
                numberValue = 100,
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sequence_executions SET status = ?, ended_at_ms = NULL, active_duration_ms = NULL, " +
                    "pause_duration_ms = NULL, wall_duration_ms = NULL WHERE id = ?",
                arrayOf(status, "number-root-$status"),
            )
        }
        completedActivity(
            "number-deleted",
            "number-history",
            "activity-series",
            null,
            AUG_20,
            values = listOf(numberValue("number-deleted", "number-history-number", 100)),
        )
        database.activityExecutionDao().softDelete("number-deleted", START_2330 + 100)
        // Both eligible child values and tombstone exclusion are also pinned by the existing
        // deletedSequenceChildLeavesDurableValuesButExitsActivityStatisticsOnly case.
        // Archiving sources and Fields preserves the frozen historical contribution.
        for (kind in listOf("activity", "sequence")) {
            database.openHelper.writableDatabase.execSQL("UPDATE ${kind}_templates SET deleted_at_ms = 1")
            database.openHelper.writableDatabase.execSQL("UPDATE ${kind}_template_fields SET deleted_at_ms = 1")
        }
        val expectations =
            listOf(
                StatisticsPeriod.Day(LocalDate.parse(AUG_20)) to values,
                StatisticsPeriod.Day(LocalDate.parse(AUG_21)) to listOf(Long.MAX_VALUE, Long.MAX_VALUE),
                StatisticsPeriod.Day(LocalDate.parse("2026-08-24")) to listOf(Long.MIN_VALUE, Long.MIN_VALUE),
                StatisticsPeriod.AllTime to
                    (values + listOf(Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE)),
            )
        for ((period, expected) in expectations) {
            val missing = if (period == StatisticsPeriod.AllTime || period == expectations.first().first) 1L else 0L
            val reference =
                com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
                    .numbers(expected)
            for ((series, field) in numberHistoryIdentities()) {
                val actual = repository.numberFieldStatistics(series, field, period)
                assertEquals(reference, actual.values)
                assertEquals(expected.size.toLong() + missing, actual.relevantExecutionCount)
                assertEquals(missing, actual.missingCount)
                assertEquals(
                    actual,
                    (
                        repository.seriesDetail(series, period).fields.single {
                            it.field.id == field
                        } as StatisticsFieldDetail.Number
                    ).statistics,
                )
            }
        }
        for ((series, field) in numberHistoryIdentities()) {
            val empty =
                repository.numberFieldStatistics(
                    series,
                    field,
                    StatisticsPeriod.Day(LocalDate.parse("2026-08-22")),
                )
            assertEquals(0L, empty.recordedCount)
            assertEquals(0L, empty.missingCount)
            assertNull(empty.coverage.exactValue)
            val emptyDetail = repository.seriesDetail(series, StatisticsPeriod.Day(LocalDate.parse("2026-08-22")))
            assertEquals(
                empty,
                emptyDetail.fields
                    .filterIsInstance<StatisticsFieldDetail.Number>()
                    .single { it.field.id == field }
                    .statistics,
            )
        }
    }

    @Test
    fun numberReadersBoundResultsAndQueriesAcrossGrowingHistory() {
        numberHistorySources()
        completedSequence("bulk-root", "sequence-series", "number-history", 1, 0, AUG_20, withNumberField = true)
        val reference = mutableListOf<Long>()
        var previous = 0
        for (size in listOf(1, 100, 4_000)) {
            database.runInTransaction {
                for (index in previous until size) {
                    val value =
                        when (index % 7) {
                            0 -> Long.MIN_VALUE
                            1 -> Long.MAX_VALUE
                            2 -> 0L
                            3 -> -7L
                            else -> index * 1_000_000_007L
                        }
                    database.openHelper.writableDatabase.execSQL(
                        "INSERT INTO activity_executions " +
                            "(id,snapshot_id,context_type,statistics_series_id,status,completed_at_ms," +
                            "original_zone_id,primary_local_date,created_at_ms,updated_at_ms) " +
                            "VALUES (?, 'number-history', 'STANDALONE', 'activity-series', 'COMPLETED', " +
                            "0, 'UTC', ?, 0, 0)",
                        arrayOf("bulk-$index", AUG_20),
                    )
                    database.openHelper.writableDatabase.execSQL(
                        "INSERT INTO sequence_executions " +
                            "(id,snapshot_id,statistics_series_id,status,started_at_ms,ended_at_ms," +
                            "active_duration_ms,pause_duration_ms,wall_duration_ms,original_zone_id," +
                            "primary_local_date,created_at_ms,updated_at_ms) " +
                            "VALUES (?, 'bulk-root-snapshot', 'sequence-series', 'COMPLETED', " +
                            "0, 1, 1, 0, 1, 'UTC', ?, 0, 1)",
                        arrayOf("bulk-sequence-$index", AUG_20),
                    )
                    // Sparse rows coexist with dense, repeated, distinct and extreme signed values.
                    if (index % 5 != 4) {
                        reference.add(value)
                        database.activityExecutionDao().upsertValue(
                            numberValue("bulk-$index", "number-history-number", value),
                        )
                        database.openHelper.writableDatabase.execSQL(
                            "INSERT INTO sequence_execution_field_values" +
                                "(sequence_execution_id,snapshot_field_id,number_scaled) " +
                                "VALUES (?, 'bulk-root-snapshot-number', ?)",
                            arrayOf<Any>("bulk-sequence-$index", value),
                        )
                    }
                }
            }
            previous = size
            for ((series, field) in numberHistoryIdentities()) {
                observedSql.clear()
                val actual = repository.numberFieldStatistics(series, field, StatisticsPeriod.AllTime)
                val reads =
                    synchronized(observedSql) {
                        observedSql.filter {
                            val statement = it.trimStart()
                            val isRead = statement.startsWith("SELECT") || statement.startsWith("WITH")
                            isRead && !statement.contains("room_table_modification_log", ignoreCase = true)
                        }
                    }
                assertEquals(7, reads.size)
                assertTrue(reads.none { it.contains("active_duration_ms") })
                assertEquals(
                    com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
                        .numbers(reference),
                    actual.values,
                )
                val relevant = size.toLong() + if (field is StatisticsFieldId.Sequence) 1 else 0
                assertEquals(relevant, actual.relevantExecutionCount)
                assertEquals(relevant - reference.size, actual.missingCount)
                val dao = database.statisticsDao()
                val bits =
                    if (field is StatisticsFieldId.Activity) {
                        dao.activityNumberBitCounts(series.value, field.value, null, null)
                    } else {
                        dao.sequenceNumberBitCounts(series.value, field.value, null, null)
                    }
                assertEquals(Long.SIZE_BITS, bits.bitCounts.split(',').size)
                assertNumberReadPlans()
                val selection =
                    if (field is StatisticsFieldId.Activity) {
                        dao.activityNumberSelection(series.value, field.value, null, null, actual.recordedCount)
                    } else {
                        dao.sequenceNumberSelection(series.value, field.value, null, null, actual.recordedCount)
                    }
                assertEquals(actual.recordedCount, selection.sampleCount)
                assertEquals(if (actual.recordedCount % 2 == 0L) 2L else 1L, selection.selectedCount)
                assertEquals(actual.values, ExactNumberSummary.read(bits).distribution(selection))
            }
        }
    }

    @Test
    fun numberReadersSurvivePhysicalReopenAndHistoricalDeletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "statistics-number-${UUID.randomUUID()}"
        database.close()
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = StatisticsRepository(database) { StatisticsSeriesId("unused") }
            numberHistorySources()
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO sequence_template_fields " +
                    "(id,sequence_template_id,position,name,field_type,created_at_ms,updated_at_ms) " +
                    "VALUES ('physical-category','sequence',1,'Choice','CATEGORY',0,0)",
            )
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO sequence_template_category_options (id,sequence_template_field_id,position,label) " +
                    "VALUES ('physical-option','physical-category',0,'Choice')",
            )
            completedActivity(
                "physical-number",
                "number-history",
                "activity-series",
                null,
                AUG_20,
                values =
                    listOf(
                        numberValue("physical-number", "number-history-number", Long.MIN_VALUE),
                        categoryValue("physical-number", "number-history-category", "number-history-tempo"),
                    ),
            )
            completedSequence(
                "physical-root",
                "sequence-series",
                "number-history",
                1,
                0,
                AUG_20,
                withNumberField = true,
                numberValue = Long.MAX_VALUE,
                categoryFieldId = "physical-category",
                categoryOptionId = "physical-option",
            )
            val identities = numberHistoryIdentities()
            val before =
                identities.map { (series, field) ->
                    repository.numberFieldStatistics(series, field, StatisticsPeriod.AllTime)
                }
            val detailBefore =
                identities.map { (series, _) ->
                    repository.seriesDetail(series, StatisticsPeriod.AllTime)
                }
            database.close()
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = StatisticsRepository(database) { StatisticsSeriesId("unused") }
            assertEquals(
                before,
                identities.map { (series, field) ->
                    repository.numberFieldStatistics(series, field, StatisticsPeriod.AllTime)
                },
            )
            assertEquals(
                detailBefore,
                identities.map { (series, _) -> repository.seriesDetail(series, StatisticsPeriod.AllTime) },
            )
            val categoryBefore =
                detailBefore
                    .first()
                    .fields
                    .filterIsInstance<StatisticsFieldDetail.Category>()
                    .single()
                    .statistics
            assertEquals(1L, categoryBefore.recordedCount)
            assertEquals(
                "tempo-source",
                categoryBefore.values
                    .single()
                    .id.value,
            )
            database.activityExecutionDao().softDeleteCompletedStandalone(
                "physical-number",
                START_2330,
                START_2330 + 100,
            )
            val (series, field) = identities.first()
            val after = repository.numberFieldStatistics(series, field, StatisticsPeriod.AllTime)
            val detailAfter = repository.seriesDetail(series, StatisticsPeriod.AllTime)
            assertEquals(
                after,
                detailAfter.fields
                    .filterIsInstance<StatisticsFieldDetail.Number>()
                    .single {
                        it.field.id ==
                            field
                    }.statistics,
            )
            val categoryAfter =
                detailAfter.fields
                    .filterIsInstance<StatisticsFieldDetail.Category>()
                    .single()
                    .statistics
            assertEquals(categoryBefore.field, categoryAfter.field)
            assertEquals(0L, categoryAfter.recordedCount)
            assertEquals(categoryBefore.values.map { it.id }, categoryAfter.values.map { it.id })
            assertEquals(
                detailBefore.last(),
                repository.seriesDetail(identities.last().first, StatisticsPeriod.AllTime),
            )
            assertEquals(before.first().field.id, after.field.id)
            assertEquals(0L, after.recordedCount)
            assertEquals(BigInteger.ZERO, after.values.totalScaled)
            assertEquals(
                before.last(),
                repository.numberFieldStatistics(
                    identities.last().first,
                    identities.last().second,
                    StatisticsPeriod.AllTime,
                ),
            )
            assertEquals(
                "activity-series",
                database.activityExecutionDao().getById("physical-number")!!.statisticsSeriesId,
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun numberQueriesRestrictTinySeriesAndNarrowPeriodsBeforeFieldLineageAfterReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "statistics-selectivity-${UUID.randomUUID()}"
        database.close()
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = StatisticsRepository(database) { StatisticsSeriesId("unused") }
            numberHistorySources()
            completedSequence(
                "selectivity-root",
                "sequence-series",
                "number-history",
                1,
                0,
                AUG_20,
                withNumberField = true,
            )
            database.runInTransaction {
                // Reuse frozen snapshots: even a single Field can have a very large value lineage.
                repeat(8_000) { index ->
                    val date = if (index == 7_999) AUG_21 else AUG_20
                    for (kind in listOf("activity", "sequence")) {
                        val snapshot = if (kind == "activity") "number-history" else "selectivity-root-snapshot"
                        val field = "$snapshot-number"
                        val columns =
                            if (kind == "activity") {
                                "context_type,completed_at_ms"
                            } else {
                                "started_at_ms,ended_at_ms,active_duration_ms,pause_duration_ms,wall_duration_ms"
                            }
                        val facts = if (kind == "activity") "'STANDALONE',1" else "0,1,1,0,1"
                        database.openHelper.writableDatabase.execSQL(
                            "INSERT INTO ${kind}_executions " +
                                "(id,snapshot_id,statistics_series_id,status,original_zone_id,primary_local_date," +
                                "created_at_ms,updated_at_ms,$columns) " +
                                "VALUES (?,?,?,'COMPLETED','UTC',?,0,1,$facts)",
                            arrayOf("$kind-old-$index", snapshot, "$kind-series", date),
                        )
                        database.openHelper.writableDatabase.execSQL(
                            "INSERT INTO ${kind}_execution_field_values " +
                                "(${kind}_execution_id,snapshot_field_id,number_scaled) " +
                                "VALUES (?,?,?)",
                            arrayOf<Any>("$kind-old-$index", field, if (index == 7_999) -7L else index.toLong()),
                        )
                    }
                }
            }
            val tinyValues = listOf(Long.MIN_VALUE, Long.MAX_VALUE, 9L)
            for (kind in listOf("activity", "sequence")) {
                series("$kind-tiny", kind.uppercase(), "New Series")
                tinyValues.forEachIndexed { index, value ->
                    val id = "$kind-tiny-$index"
                    if (kind == "activity") {
                        activitySnapshot("$id-snapshot", "activity-tiny", mode = "NO_LIVE_TRACKING", fields = true)
                        completedActivity(
                            id,
                            "$id-snapshot",
                            "activity-tiny",
                            null,
                            AUG_20,
                            values = listOf(numberValue(id, "$id-snapshot-number", value)),
                        )
                    } else {
                        completedSequence(id, "sequence-tiny", "number-history", 1, 0, AUG_20, true, value)
                    }
                }
            }
            seedMinorityNumberHistory()
            assertEquals(2L, database.statisticsDao().activityNumberFieldCandidateCount("number-source", 2))
            assertEquals(2L, database.statisticsDao().sequenceNumberFieldCandidateCount("sequence-number-source", 2))
            repeat(2) {
                database.close()
                database =
                    LifeTracingDatabase
                        .builder(context, name)
                        .allowMainThreadQueries()
                        .setQueryCallback(::observeStatisticsQuery, Executor { it.run() })
                        .build()
                repository = StatisticsRepository(database) { StatisticsSeriesId("unused") }
                for ((oldSeries, field) in numberHistoryIdentities()) {
                    val kind = if (field is StatisticsFieldId.Activity) "activity" else "sequence"
                    for ((seriesId, period, values) in listOf(
                        Triple(StatisticsSeriesId("$kind-tiny"), StatisticsPeriod.AllTime, tinyValues),
                        Triple(oldSeries, StatisticsPeriod.Day(LocalDate.parse(AUG_21)), listOf(-7L)),
                        Triple(oldSeries, StatisticsPeriod.AllTime, (0L..7_998L).toList() + -7L),
                    )) {
                        observedNumberReads.clear()
                        val actual = repository.numberFieldStatistics(seriesId, field, period)
                        val bundled =
                            repository
                                .seriesDetail(seriesId, period)
                                .fields
                                .filterIsInstance<StatisticsFieldDetail.Number>()
                                .single { it.field.id == field }
                                .statistics
                        assertEquals(
                            com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
                                .numbers(values),
                            actual.values,
                        )
                        val missing =
                            if (field is StatisticsFieldId.Sequence &&
                                seriesId == oldSeries &&
                                period == StatisticsPeriod.AllTime
                            ) {
                                1L
                            } else {
                                0L
                            }
                        assertEquals(values.size.toLong() + missing, actual.relevantExecutionCount)
                        assertEquals(missing, actual.missingCount)
                        assertEquals(actual, bundled)
                        val dense =
                            synchronized(observedNumberReads) {
                                observedNumberReads.any { (sql, _) ->
                                    sql.startsWith(
                                        "WITH eligible AS (SELECT recorded_values.number_scaled FROM ${kind}_snapshot_fields",
                                    )
                                }
                            }
                        assertEquals(seriesId == oldSeries && period == StatisticsPeriod.AllTime, dense)
                        assertNumberReadPlans()
                    }
                }
                assertMinorityNumberHistory()
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun sharedNumberLineagesUseOneGroupedTraversalAfterPhysicalReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "statistics-shared-fields-${UUID.randomUUID()}"
        database.close()
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            numberHistorySources()
            for (fields in listOf(4, 8, 16, 24)) seedSharedNumberLineages(fields)
            database.close()
            database =
                LifeTracingDatabase
                    .builder(context, name)
                    .allowMainThreadQueries()
                    .setQueryCallback(::observeStatisticsQuery, Executor { it.run() })
                    .build()
            repository = StatisticsRepository(database) { StatisticsSeriesId("unused") }
            for (fields in listOf(4, 8, 16, 24)) {
                for (kind in listOf("activity", "sequence")) {
                    observedSql.clear()
                    observedNumberReads.clear()
                    val numbers =
                        repository
                            .seriesDetail(
                                StatisticsSeriesId("$kind-shared-$fields-new"),
                                StatisticsPeriod.AllTime,
                            ).fields
                            .filterIsInstance<StatisticsFieldDetail.Number>()
                    assertEquals(fields, numbers.size)
                    numbers.forEach { number ->
                        val field =
                            number.field.id.value
                                .substringAfterLast('-')
                                .toInt()
                        assertEquals("$kind-shared-$fields-source-$field", number.field.id.value)
                        val values = List(if (field % 2 == 0) 100 else 99) { sharedNumberValue(it, field) }
                        val statistics = number.statistics
                        assertEquals(100L, statistics.relevantExecutionCount)
                        assertEquals(values.size.toLong(), statistics.recordedCount)
                        assertEquals(100L - values.size, statistics.missingCount)
                        assertEquals(values.size.toLong(), statistics.coverage.numerator)
                        assertEquals(100L, statistics.coverage.denominator)
                        assertEquals(
                            com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
                                .numbers(values),
                            statistics.values,
                        )
                    }
                    val queries = synchronized(observedSql) { observedSql.toList() }
                    assertEquals(
                        "No per-Field execution/value traversal is allowed for the shared fallback",
                        0,
                        queries.count {
                            it.startsWith(
                                "WITH eligible AS (SELECT recorded_values.number_scaled FROM ${kind}_executions",
                            )
                        },
                    )
                    val ranks =
                        synchronized(observedNumberReads) {
                            observedNumberReads.filter { it.first.contains(" AS at_or_below_count") }
                        }
                    assertEquals("Shared pass count must not grow with populated Field count", 3, ranks.size)
                    assertEquals(
                        fields,
                        queries.count { it.startsWith("SELECT COUNT(*) FROM (") && it.contains("LIMIT") },
                    )
                    ranks.forEach { (sql, args) ->
                        assertEquals("Bind count is independent of Field count", 4, args.size)
                        val plan =
                            database.openHelper.readableDatabase
                                .query(
                                    "EXPLAIN QUERY PLAN $sql",
                                    args.toTypedArray(),
                                ).use {
                                    buildList { while (it.moveToNext()) add(it.getString(3)) }
                                }
                        val searches = plan.filter { it.contains("SEARCH ", ignoreCase = true) }
                        assertTrue("Selected executions must lead: $plan", searches.first().contains("executions"))
                        assertEquals("One execution/value/Field join per shared pass: $plan", 3, searches.size)
                        assertTrue(plan.none { it.contains("CORRELATED", ignoreCase = true) })
                    }
                }
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun smallNumberFallbackUsesScalarMediansAfterPhysicalReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cases =
            listOf(
                1 to false,
                2 to false,
                2 to true,
                3 to false,
                4 to false,
                8 to false,
                16 to false,
                17 to false,
                24 to false,
            )
        for ((fields, exclusiveFirst) in cases) {
            val name = "statistics-small-fallback-${UUID.randomUUID()}"
            database.close()
            try {
                database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
                numberHistorySources()
                seedSharedNumberLineages(fields, 1_000, 7_000, exclusiveFirst, ::broadNumberValue)
                database.close()
                database =
                    LifeTracingDatabase
                        .builder(context, name)
                        .allowMainThreadQueries()
                        .setQueryCallback(::observeStatisticsQuery, Executor { it.run() })
                        .build()
                repository = StatisticsRepository(database) { error("Read must not allocate identity") }
                for (kind in listOf("activity", "sequence")) {
                    val seriesId = StatisticsSeriesId("$kind-shared-$fields-new")
                    observedSql.clear()
                    observedNumberReads.clear()
                    val numbers =
                        repository
                            .seriesDetail(seriesId, StatisticsPeriod.AllTime)
                            .fields
                            .filterIsInstance<StatisticsFieldDetail.Number>()
                    val reads = synchronized(observedNumberReads) { observedNumberReads.toList() }
                    assertEquals(fields, numbers.size)
                    numbers.forEach { number ->
                        val field =
                            number.field.id.value
                                .substringAfterLast('-')
                                .toInt()
                        val values = List(1_000 - field % 2) { broadNumberValue(it, field) }.sorted()
                        val count = values.size.toLong()
                        val total = values.fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value) }
                        val statistics = number.statistics
                        val expectedId = "$kind-shared-$fields-source-$field"
                        assertEquals(
                            if (kind == "activity") {
                                StatisticsFieldId.Activity(ActivityTemplateFieldId(expectedId))
                            } else {
                                StatisticsFieldId.Sequence(SequenceTemplateFieldId(expectedId))
                            },
                            number.field.id,
                        )
                        assertEquals(1_000L, statistics.relevantExecutionCount)
                        assertEquals(count, statistics.recordedCount)
                        assertEquals(1_000L - count, statistics.missingCount)
                        assertEquals(count, statistics.coverage.numerator)
                        assertEquals(1_000L, statistics.coverage.denominator)
                        assertTrue(
                            "Fixture must exceed signed-Long total range",
                            total.abs() > BigInteger.valueOf(Long.MAX_VALUE),
                        )
                        assertEquals(count, statistics.values.sampleCount)
                        assertEquals(total, statistics.values.totalScaled)
                        assertEquals(ExactValue.of(total, BigInteger.valueOf(count)), statistics.values.averageScaled)
                        assertEquals(
                            ExactValue.of(
                                BigInteger.valueOf(values[(values.size - 1) / 2]) +
                                    BigInteger.valueOf(values[values.size / 2]),
                                BigInteger.valueOf(2),
                            ),
                            statistics.values.medianScaled,
                        )
                        assertEquals(values.first(), statistics.values.minimumScaled)
                        assertEquals(values.last(), statistics.values.maximumScaled)
                        assertEquals(
                            repository.numberFieldStatistics(seriesId, number.field.id, StatisticsPeriod.AllTime),
                            statistics,
                        )
                    }
                    val ranks = reads.count { it.first.contains(" AS at_or_below_count") }
                    when {
                        fields <= 3 -> assertEquals(0, ranks)
                        fields <= 16 ->
                            assertEquals("Small wide-range fallback must stop after three shared passes", 3, ranks)
                        else -> assertTrue("Large unresolved sets retain shared selection", ranks >= 8)
                    }
                    val medians = reads.filter { it.first.contains(" AS lower_median_scaled") }
                    assertEquals(if (fields <= 16) fields else 0, medians.size)
                    val executionFirstCount = if (exclusiveFirst) 1 else fields
                    assertEquals(
                        if (fields > 16) 0 else executionFirstCount,
                        medians.count {
                            it.first.startsWith(
                                "WITH eligible AS (SELECT recorded_values.number_scaled FROM ${kind}_executions",
                            )
                        },
                    )
                    val probes =
                        reads.filter {
                            it.first.startsWith("SELECT COUNT(*) FROM (") && it.first.contains("LIMIT")
                        }
                    assertEquals(fields, probes.size)
                    var probeVisits = 0L
                    probes.forEachIndexed { index, (sql, args) ->
                        database.openHelper.readableDatabase.query(sql, args.toTypedArray()).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            val limit = (args.last() as Number).toLong()
                            probeVisits += cursor.getLong(0)
                            if (exclusiveFirst && index == 0) {
                                assertTrue(cursor.getLong(0) < limit)
                            } else {
                                assertEquals(
                                    "Shared old lineage must exhaust the bounded probe",
                                    limit,
                                    cursor.getLong(0),
                                )
                            }
                        }
                    }
                    val candidates =
                        if (kind == "activity") {
                            database.statisticsDao().activityNumberExecutionCandidateCount(seriesId.value, null, null)
                        } else {
                            database.statisticsDao().sequenceNumberExecutionCandidateCount(seriesId.value, null, null)
                        }
                    assertTrue(
                        "Total probe work includes every saturated sentinel",
                        probeVisits <= 2 * candidates + fields,
                    )
                    val day = repository.seriesDetail(seriesId, StatisticsPeriod.Day(LocalDate.parse(AUG_20)))
                    assertEquals(numbers, day.fields.filterIsInstance<StatisticsFieldDetail.Number>())
                    // Inspect only the canonical detail statements, not the parity reads above.
                    observedNumberReads.clear()
                    observedNumberReads.addAll(reads)
                    assertNumberReadPlans()
                }
            } finally {
                database.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun numberProbeFairnessSurvivesDisplayRenameAndPhysicalReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "statistics-fair-probes-${UUID.randomUUID()}"
        database.close()
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            numberHistorySources()
            seedSharedNumberLineages(24, 1_000, 50_000, number = ::broadNumberValue, exclusiveOthers = true)
            val historicalFingerprint = numberHistoryFingerprint()
            database.close()
            database =
                LifeTracingDatabase
                    .builder(context, name)
                    .allowMainThreadQueries()
                    .setQueryCallback(::observeStatisticsQuery, Executor { it.run() })
                    .build()
            repository = StatisticsRepository(database) { error("Read must not allocate identity") }
            for (kind in listOf("activity", "sequence")) {
                val seriesId = StatisticsSeriesId("$kind-shared-24-new")
                val before =
                    mutableMapOf<StatisticsFieldId, com.alexandr5476.lifetracing.domain.NumberDistribution>()
                for (expensiveName in listOf("A expensive", "Z expensive")) {
                    // Only current presentation metadata changes; frozen snapshots and values stay untouched.
                    database.openHelper.writableDatabase
                        .execSQL(
                            "UPDATE ${kind}_template_fields SET name = CASE WHEN id = ? THEN ? ELSE 'M cheap' END",
                            arrayOf("$kind-shared-24-source-0", expensiveName),
                        )
                    observedNumberReads.clear()
                    val numbers =
                        repository
                            .seriesDetail(seriesId, StatisticsPeriod.AllTime)
                            .fields
                            .filterIsInstance<StatisticsFieldDetail.Number>()
                    val reads = synchronized(observedNumberReads) { observedNumberReads.toList() }
                    assertEquals(24, numbers.size)
                    assertEquals(
                        if (expensiveName.startsWith("A")) 0 else 23,
                        numbers.indexOfFirst {
                            it.field.id.value
                                .endsWith("-0")
                        },
                    )
                    assertEquals(24, reads.count { it.first.startsWith("SELECT COUNT(*) FROM (") })
                    assertEquals(0, reads.count { it.first.contains(" AS at_or_below_count") })
                    val medians = reads.filter { it.first.contains(" AS lower_median_scaled") }
                    assertEquals(24, medians.size)
                    assertEquals(
                        "Only the expensive Field may use execution-first scalar selection",
                        1,
                        medians.count {
                            it.first.startsWith(
                                "WITH eligible AS (SELECT recorded_values.number_scaled FROM ${kind}_executions",
                            )
                        },
                    )
                    numbers.forEach { number ->
                        val field =
                            number.field.id.value
                                .substringAfterLast('-')
                                .toInt()
                        val expectedId = "$kind-shared-24-source-$field"
                        assertEquals(
                            if (kind == "activity") {
                                StatisticsFieldId.Activity(ActivityTemplateFieldId(expectedId))
                            } else {
                                StatisticsFieldId.Sequence(SequenceTemplateFieldId(expectedId))
                            },
                            number.field.id,
                        )
                        val values = List(1_000 - field % 2) { broadNumberValue(it, field) }
                        val statistics = number.statistics
                        assertEquals(1_000L, statistics.relevantExecutionCount)
                        assertEquals(values.size.toLong(), statistics.recordedCount)
                        assertEquals((field % 2).toLong(), statistics.missingCount)
                        assertEquals(values.size.toLong(), statistics.coverage.numerator)
                        assertEquals(1_000L, statistics.coverage.denominator)
                        assertEquals(
                            com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
                                .numbers(values),
                            statistics.values,
                        )
                        assertEquals(
                            statistics,
                            repository.numberFieldStatistics(seriesId, number.field.id, StatisticsPeriod.AllTime),
                        )
                        before
                            .putIfAbsent(number.field.id, statistics.values)
                            ?.let { assertEquals(it, statistics.values) }
                    }
                    val day = repository.seriesDetail(seriesId, StatisticsPeriod.Day(LocalDate.parse(AUG_20)))
                    assertEquals(numbers, day.fields.filterIsInstance<StatisticsFieldDetail.Number>())
                    assertEquals(
                        "Presentation rename and reads preserve every historical row",
                        historicalFingerprint,
                        numberHistoryFingerprint(),
                    )
                    observedNumberReads.clear()
                    observedNumberReads.addAll(reads)
                    assertNumberReadPlans()
                }
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun numberHistoryFingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val tables =
            listOf("activity", "sequence").flatMap { kind ->
                listOf("executions", "execution_field_values", "snapshots", "snapshot_fields").map { "${kind}_$it" }
            }
        for (table in tables) {
            digest.update(table.toByteArray())
            database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) {
                    repeat(cursor.columnCount) { column ->
                        digest.update((cursor.getString(column) ?: "<NULL>").toByteArray())
                        digest.update(0.toByte())
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun broadNumberValue(
        index: Int,
        field: Int,
    ): Long =
        when (index) {
            0 -> Long.MIN_VALUE
            1 -> Long.MAX_VALUE
            else -> java.util.Random(37L + index * 1_000_003L + field).nextLong()
        }

    private fun sharedNumberValue(
        index: Int,
        field: Int,
    ): Long =
        when {
            field % 2 == 0 ->
                when (index % 4) {
                    0 -> Long.MIN_VALUE
                    1, 3 -> Long.MAX_VALUE
                    else -> 0L
                }
            else -> sharedOddNumberValue(index, field)
        }

    private fun sharedOddNumberValue(
        index: Int,
        field: Int,
    ): Long =
        when (index % 5) {
            0 -> Long.MIN_VALUE
            1, 2 -> Long.MAX_VALUE
            3 -> 0L
            else -> -7L - field
        }

    private fun seedSharedNumberLineages(
        fields: Int,
        selectedExecutions: Int = 100,
        oldExecutions: Int = 6_000,
        exclusiveFirst: Boolean = false,
        number: (Int, Int) -> Long = ::sharedNumberValue,
        exclusiveOthers: Boolean = false,
    ) {
        database.runInTransaction {
            for (kind in listOf("activity", "sequence")) {
                val prefix = "$kind-shared-$fields"
                for (era in listOf("old", "new")) {
                    val seriesId = "$prefix-$era"
                    val snapshot = "$seriesId-snapshot"
                    series(seriesId, kind.uppercase(), "Shared lineage")
                    if (kind == "activity") {
                        activitySnapshot(snapshot, seriesId, mode = "NO_LIVE_TRACKING")
                    } else {
                        database.sequenceSnapshotDao().insertAggregate(
                            SequenceSnapshotAggregateEntity(
                                SequenceSnapshotEntity(snapshot, "Shared", null, null, null, seriesId, 0),
                                sequenceSettings(snapshot),
                            ),
                        )
                    }
                    repeat(fields) { field ->
                        val source = "$prefix-source-$field"
                        if (era == "old") {
                            database.openHelper.writableDatabase.execSQL(
                                "INSERT INTO ${kind}_template_fields " +
                                    "(id,${kind}_template_id,position,name,field_type,created_at_ms,updated_at_ms) " +
                                    "VALUES (?,?,?,'Same label','NUMBER',0,0)",
                                arrayOf<Any>(
                                    source,
                                    if (kind ==
                                        "activity"
                                    ) {
                                        "activity"
                                    } else {
                                        "sequence"
                                    },
                                    fields * 100 + field,
                                ),
                            )
                        }
                        val owner = if (kind == "activity") "snapshot_id" else "sequence_snapshot_id"
                        database.openHelper.writableDatabase.execSQL(
                            "INSERT INTO ${kind}_snapshot_fields " +
                                "(id,$owner,source_field_id,position,name_at_creation,field_type) " +
                                "VALUES (?,?,?,?,'Same label','NUMBER')",
                            arrayOf<Any>("$snapshot-$field", snapshot, source, field),
                        )
                    }
                    repeat(if (era == "old") oldExecutions else selectedExecutions) { index ->
                        val id = "$seriesId-$index"
                        val columns =
                            if (kind == "activity") {
                                "context_type,completed_at_ms"
                            } else {
                                "started_at_ms,ended_at_ms,active_duration_ms,pause_duration_ms,wall_duration_ms"
                            }
                        val facts = if (kind == "activity") "'STANDALONE',1" else "0,1,1,0,1"
                        database.openHelper.writableDatabase.execSQL(
                            "INSERT INTO ${kind}_executions " +
                                "(id,snapshot_id,statistics_series_id,status,original_zone_id,primary_local_date," +
                                "created_at_ms,updated_at_ms,$columns) " +
                                "VALUES (?,?,?,'COMPLETED','UTC',?,0,1,$facts)",
                            arrayOf(id, snapshot, seriesId, AUG_20),
                        )
                        repeat(fields) { field ->
                            val missing = era == "new" && field % 2 == 1 && index == selectedExecutions - 1
                            val exclusive = (exclusiveFirst && field == 0) || (exclusiveOthers && field > 0)
                            if (!missing && !(era == "old" && exclusive)) {
                                database.openHelper.writableDatabase.execSQL(
                                    "INSERT INTO ${kind}_execution_field_values " +
                                        "(${kind}_execution_id,snapshot_field_id,number_scaled) VALUES (?,?,?)",
                                    arrayOf<Any>(id, "$snapshot-$field", number(index, field)),
                                )
                            }
                        }
                    }
                }
                database.openHelper.writableDatabase.execSQL("UPDATE ${kind}_templates SET deleted_at_ms = 2")
            }
        }
    }

    @Test
    fun numberReadersRejectNonIntegerStorage() {
        numberHistorySources()
        completedActivity(
            "malformed-number",
            "number-history",
            "activity-series",
            null,
            AUG_20,
            values = listOf(numberValue("malformed-number", "number-history-number", 1)),
        )
        completedSequence(
            "malformed-root",
            "sequence-series",
            "number-history",
            1,
            0,
            AUG_20,
            withNumberField = true,
            numberValue = 1,
        )
        for (literal in listOf("0.5", "'corrupt'")) {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE activity_execution_field_values SET number_scaled = $literal",
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sequence_execution_field_values SET number_scaled = $literal",
            )
            for ((series, field) in numberHistoryIdentities()) {
                assertThrows(IllegalArgumentException::class.java) {
                    repository.numberFieldStatistics(series, field, StatisticsPeriod.AllTime)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    repository.seriesDetail(series, StatisticsPeriod.AllTime)
                }
            }
        }
    }

    @Test
    fun mixedStatisticsSurviveReopenCorrectionAndDeletionWithIndependentExpectations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "statistics-s4-${UUID.randomUUID()}"
        database.close()
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = StatisticsRepository(database) { error("A Statistics read must not allocate identity") }
            mixedStatisticsHistory()
            assertMixedStatistics()
            val before = statisticsProjections(repository)
            val facts = statisticsFacts(database)
            database.close()
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = StatisticsRepository(database) { error("A Statistics read must not allocate identity") }
            assertEquals(facts, statisticsFacts(database))
            assertEquals(before, statisticsProjections(repository))
            assertMixedStatistics()

            val unaffected = repository.seriesDetail(StatisticsSeriesId("sequence-series"), StatisticsPeriod.AllTime)
            val other = repository.activitySeries(StatisticsSeriesId("s4-other-series"), StatisticsPeriod.AllTime)
            mutateStatisticsHistory(database, "correct")
            assertEquals(Duration.ofMinutes(142), repository.global(StatisticsPeriod.AllTime).totalTrackedDuration)
            assertEquals(
                Duration.ofMinutes(75),
                repository.global(StatisticsPeriod.Day(LocalDate.parse(AUG_20))).totalTrackedDuration,
            )
            assertEquals(
                Duration.ofMinutes(60),
                repository.global(StatisticsPeriod.Day(LocalDate.parse(AUG_21))).totalTrackedDuration,
            )
            val corrected = repository.seriesDetail(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime)
            val number =
                corrected.fields
                    .filterIsInstance<StatisticsFieldDetail.Number>()
                    .single {
                        it.field.id.value ==
                            "number-source"
                    }.statistics
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.valueOf(8), number.values.totalScaled)
            assertEquals(ExactValue.of(8, 1), number.values.medianScaled)
            assertEquals(4L, number.relevantExecutionCount)
            assertEquals(3L, number.recordedCount)
            assertEquals(1L, number.missingCount)
            assertEquals(
                1L,
                repository
                    .numberFieldStatistics(
                        StatisticsSeriesId("activity-series"),
                        number.field.id,
                        StatisticsPeriod.Day(LocalDate.parse(AUG_20)),
                    ).recordedCount,
            )
            assertEquals(
                2L,
                repository
                    .numberFieldStatistics(
                        StatisticsSeriesId("activity-series"),
                        number.field.id,
                        StatisticsPeriod.Day(LocalDate.parse(AUG_21)),
                    ).recordedCount,
            )

            mutateStatisticsHistory(database, "delete")
            mutateStatisticsHistory(database, "child")
            val after =
                repository.seriesDetail(
                    StatisticsSeriesId("activity-series"),
                    StatisticsPeriod.AllTime,
                ) as StatisticsSeriesDetail.Activity
            assertEquals(2L, after.statistics.executionCount)
            assertEquals(Duration.ofMinutes(45), after.statistics.durations.total)
            assertEquals(ExactValue.of(45 * MINUTE, 2), after.statistics.durations.medianMilliseconds)
            val remaining =
                after.fields
                    .filterIsInstance<StatisticsFieldDetail.Number>()
                    .single {
                        it.field.id.value ==
                            "number-source"
                    }.statistics
            assertEquals(1L, remaining.recordedCount)
            assertEquals(1L, remaining.missingCount)
            assertEquals(BigInteger.valueOf(8), remaining.values.totalScaled)
            assertEquals(ExactValue.of(8, 1), remaining.values.medianScaled)
            val remainingDense =
                after.fields
                    .filterIsInstance<StatisticsFieldDetail.Number>()
                    .single {
                        it.field.id.value ==
                            "s4-dense"
                    }.statistics
            assertEquals(2L, remainingDense.recordedCount)
            assertEquals(BigInteger.valueOf(5), remainingDense.values.totalScaled)
            assertEquals(Duration.ofMinutes(142), repository.global(StatisticsPeriod.AllTime).totalTrackedDuration)
            assertEquals(
                Duration.ofMinutes(5),
                repository
                    .activitySeries(
                        StatisticsSeriesId("activity-series"),
                        StatisticsPeriod.Day(LocalDate.parse(AUG_20)),
                    ).durations.total,
            )
            assertEquals(
                Duration.ofMinutes(40),
                repository
                    .activitySeries(
                        StatisticsSeriesId("activity-series"),
                        StatisticsPeriod.Day(LocalDate.parse(AUG_21)),
                    ).durations.total,
            )
            val category =
                after.fields
                    .filterIsInstance<StatisticsFieldDetail.Category>()
                    .single()
                    .statistics
            assertEquals(1L, category.recordedCount)
            assertEquals(1L, category.missingCount)
            assertEquals(
                unaffected,
                repository.seriesDetail(StatisticsSeriesId("sequence-series"), StatisticsPeriod.AllTime),
            )
            assertEquals(
                other,
                repository.activitySeries(StatisticsSeriesId("s4-other-series"), StatisticsPeriod.AllTime),
            )
            assertEquals("FULFILLED", database.planEntryDao().getById("s4-plan")!!.status)
            assertEquals("activity-series", database.activityExecutionDao().getById("s4-timed")!!.statisticsSeriesId)
            assertEquals(
                "number-source",
                database
                    .activitySnapshotDao()
                    .getAggregate("s4-timed-snapshot")!!
                    .fields
                    .first()
                    .sourceFieldId,
            )
            assertEquals(
                "DELETED_EXECUTION",
                database
                    .sequenceExecutionDao()
                    .getOccurrences("s4-removed")
                    .single()
                    .status,
            )
            assertTrue(
                database
                    .sequenceExecutionDao()
                    .getOccurrences("s4-removed")
                    .single()
                    .isDeletedFromHistory,
            )
            val afterMutations = statisticsProjections(repository)
            database.close()
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = StatisticsRepository(database) { error("A Statistics read must not allocate identity") }
            assertEquals(afterMutations, statisticsProjections(repository))
        } finally {
            database.close()
            assertTrue(context.deleteDatabase(name))
        }
    }

    @Test
    fun concurrentStandaloneCorrectionKeepsEveryStatisticsProjectionInOneTransaction() =
        statisticsMutationInterleavings("correct")

    @Test
    fun concurrentStandaloneDeletionKeepsEveryStatisticsProjectionInOneTransaction() =
        statisticsMutationInterleavings("delete")

    @Test
    fun concurrentChildDeletionKeepsEveryStatisticsProjectionInOneTransaction() =
        statisticsMutationInterleavings("child")

    @Test
    fun statisticsReadFailureAndCancellationReleaseTransactionWithoutDurableWrites() {
        val failure = AtomicReference<RuntimeException?>()
        database.close()
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .openHelperFactory(
                    StatisticsReadTestOpenHelperFactory(beforeQuery = { _, sql ->
                        if (sql.contains("median_values")) failure.getAndSet(null)?.let { throw it }
                    }),
                ).build()
        repository = StatisticsRepository(database) { error("Read allocated an identity") }
        mixedStatisticsHistory()
        val before = statisticsFacts(database)
        val expected = statisticsProjections(repository)
        for (exception in listOf(
            IllegalStateException("injected read failure"),
            CancellationException("injected cancellation"),
        )) {
            failure.set(exception)
            val thrown =
                assertThrows(exception.javaClass) {
                    repository.seriesDetail(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime)
                }
            assertEquals(exception, thrown)
            assertTrue(!database.inTransaction())
            assertEquals(before, statisticsFacts(database))
            assertEquals(expected, statisticsProjections(repository))
        }
    }

    private fun statisticsMutationInterleavings(mutation: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activityId = StatisticsSeriesId("activity-series")
        val sequenceId = StatisticsSeriesId("sequence-series")
        val period =
            if (mutation ==
                "delete"
            ) {
                StatisticsPeriod.AllTime
            } else {
                StatisticsPeriod.Day(LocalDate.parse(AUG_20))
            }
        // Pause only after at least one history SELECT has established the canonical transaction.
        val readers =
            listOf<Pair<String, (StatisticsRepository) -> Any>>(
                "overview" to { it.overview(period) },
                "summaries" to { it.seriesSummaries(period) },
                "activity" to { it.activitySeries(activityId, period) },
                "sequence" to { it.sequenceSeries(sequenceId, period) },
                "detail" to { it.seriesDetail(activityId, period) },
                "sequence-detail" to { it.seriesDetail(sequenceId, period) },
                "number" to
                    {
                        it.numberFieldStatistics(
                            activityId,
                            StatisticsFieldId.Activity(ActivityTemplateFieldId("number-source")),
                            period,
                        )
                    },
                "category" to
                    {
                        it.categoryFieldStatistics(
                            activityId,
                            StatisticsFieldId.Activity(ActivityTemplateFieldId("category-source")),
                            period,
                        )
                    },
            )
        for ((label, read) in readers) {
            val name = "statistics-race-$mutation-$label-${UUID.randomUUID()}"
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writerAttempt = CountDownLatch(1)
            val remainingReads = AtomicInteger(Int.MAX_VALUE)
            val readingThread = AtomicReference<Thread?>()
            val writingThread = AtomicReference<Thread?>()
            val workers = Executors.newFixedThreadPool(2)
            var writer: LifeTracingDatabase? = null
            database.close()
            try {
                database =
                    LifeTracingDatabase
                        .builder(context, name)
                        .allowMainThreadQueries()
                        .openHelperFactory(
                            StatisticsReadTestOpenHelperFactory(beforeQuery = { actual, sql ->
                                if (Thread.currentThread() === readingThread.get() &&
                                    (sql.startsWith("SELECT", true) || sql.startsWith("WITH", true)) &&
                                    remainingReads.decrementAndGet() == 0
                                ) {
                                    check(actual.inTransaction()) { "Canonical $label read lost its transaction" }
                                    entered.countDown()
                                    check(release.await(30, TimeUnit.SECONDS)) { "Reader gate timed out" }
                                }
                            }),
                        ).build()
                repository = StatisticsRepository(database) { error("Read allocated identity") }
                mixedStatisticsHistory()
                writer =
                    LifeTracingDatabase
                        .builder(context, name)
                        .allowMainThreadQueries()
                        .openHelperFactory(
                            StatisticsReadTestOpenHelperFactory(beforeTransaction = {
                                if (Thread.currentThread() === writingThread.get()) writerAttempt.countDown()
                            }),
                        ).build()
                val writerConnection = writer
                writerConnection.openHelper.writableDatabase
                val expectedOld = read(repository)
                // Detail: catalog/aggregate precede duration selection. Other compound readers
                // also have at least two SELECTs. The second SELECT gates the read deterministically.
                remainingReads.set(2)
                val reading =
                    workers.submit(
                        Callable {
                            readingThread.set(Thread.currentThread())
                            read(repository)
                        },
                    )
                assertTrue("$mutation/$label did not reach reader gate", entered.await(30, TimeUnit.SECONDS))
                val writing =
                    workers.submit {
                        writingThread.set(Thread.currentThread())
                        mutateStatisticsHistory(writerConnection, mutation)
                    }
                assertTrue(
                    "Writer did not attempt a separate Room transaction",
                    writerAttempt.await(30, TimeUnit.SECONDS),
                )
                release.countDown()
                assertEquals("Mixed $mutation/$label result", expectedOld, reading.get(30, TimeUnit.SECONDS))
                writing.get(30, TimeUnit.SECONDS)
                val writerStatistics = StatisticsRepository(writerConnection) { error("Read allocated identity") }
                assertEquals(read(writerStatistics), read(repository))
                val number =
                    repository.numberFieldStatistics(
                        activityId,
                        StatisticsFieldId.Activity(ActivityTemplateFieldId("number-source")),
                        period,
                    )
                when (mutation) {
                    "correct" -> {
                        assertEquals(2L, number.relevantExecutionCount)
                        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), number.values.totalScaled)
                    }
                    "delete" -> {
                        assertEquals(3L, number.relevantExecutionCount)
                        assertEquals(2L, number.recordedCount)
                        assertEquals(BigInteger.valueOf(-1), number.values.totalScaled)
                        assertEquals(ExactValue.of(-1, 2), number.values.medianScaled)
                    }
                    "child" -> {
                        assertEquals(2L, number.relevantExecutionCount)
                        assertEquals(BigInteger.valueOf(Long.MIN_VALUE), number.values.totalScaled)
                    }
                }
            } finally {
                release.countDown()
                workers.shutdownNow()
                try {
                    assertTrue("Statistics workers leaked", workers.awaitTermination(30, TimeUnit.SECONDS))
                } finally {
                    writer?.close()
                    database.close()
                    assertTrue(context.deleteDatabase(name))
                }
            }
        }
    }

    private fun statisticsProjections(reader: StatisticsRepository): List<Any> =
        listOf(
            StatisticsPeriod.AllTime,
            StatisticsPeriod.Day(LocalDate.parse(AUG_20)),
            StatisticsPeriod.Day(LocalDate.parse(AUG_21)),
        ).flatMap { period ->
            val activity = StatisticsSeriesId("activity-series")
            val sequence = StatisticsSeriesId("sequence-series")
            listOf(
                reader.global(period),
                reader.overview(period),
                reader.seriesCatalog(),
                reader.seriesSummaries(period),
                reader.activitySeries(activity, period),
                reader.sequenceSeries(sequence, period),
                reader.seriesDetail(activity, period),
                reader.seriesDetail(sequence, period),
                reader.fieldCatalog(activity),
                reader.numberFieldStatistics(
                    activity,
                    StatisticsFieldId.Activity(ActivityTemplateFieldId("number-source")),
                    period,
                ),
                reader.categoryFieldStatistics(
                    activity,
                    StatisticsFieldId.Activity(ActivityTemplateFieldId("category-source")),
                    period,
                ),
            )
        }

    private fun assertMixedStatistics() {
        val tombstone = database.sequenceExecutionDao().getOccurrences("s4-tombstone").single()
        assertEquals("DELETED_EXECUTION", tombstone.status)
        assertTrue(!tombstone.isDeletedFromHistory)
        assertEquals(1, database.activityExecutionDao().getValues("s4-tombstone-child").size)
        assertNull(database.activityExecutionDao().getById("s4-sourceless-child")!!.statisticsSeriesId)
        assertNull(database.activitySnapshotDao().getById("s4-sourceless")!!.statisticsSeriesId)
        val all = StatisticsPeriod.AllTime
        val activityId = StatisticsSeriesId("activity-series")
        val sequenceId = StatisticsSeriesId("sequence-series")
        val global = repository.global(all)
        // Independent ledger: standalone 30 + Plan 5 + other 7; roots 60 + 20 + tombstone 10 + removed 0.
        assertEquals(Duration.ofMinutes(132), global.totalTrackedDuration)
        assertEquals(8L, global.topLevelExecutionCount)
        assertEquals(3L, global.activeDayCount)
        assertEquals(Duration.ofMinutes(25), global.totalSequencePauseIdleDuration)
        assertEquals(0L, global.oneOffActivityExecutionCount)
        val activity = repository.activitySeries(activityId, all)
        assertEquals(4L, activity.executionCount)
        assertEquals(3L, activity.durations.sampleCount)
        assertEquals(Duration.ofMinutes(95), activity.durations.total)
        assertEquals(ExactValue.of(95 * MINUTE, 3), activity.durations.averageMilliseconds)
        assertEquals(ExactValue.of(30 * MINUTE, 1), activity.durations.medianMilliseconds)
        assertEquals(Duration.ofMinutes(5), activity.durations.minimum)
        assertEquals(Duration.ofMinutes(60), activity.durations.maximum)
        assertEquals(StatisticsSeriesSourceState.ARCHIVED_SOURCE, activity.series.sourceState)
        val sequence = repository.sequenceSeries(sequenceId, all)
        assertEquals(4L, sequence.executionCount)
        assertEquals(Duration.ofMinutes(90), sequence.activeDurations.total)
        assertEquals(ExactValue.of(15 * MINUTE, 1), sequence.activeDurations.medianMilliseconds)
        assertEquals(Duration.ZERO, sequence.activeDurations.minimum)
        val sequenceDetail = repository.seriesDetail(sequenceId, all) as StatisticsSeriesDetail.Sequence
        assertEquals(sequence, sequenceDetail.statistics)
        val sequenceNumber =
            sequenceDetail.fields
                .filterIsInstance<StatisticsFieldDetail.Number>()
                .single()
                .statistics
        assertEquals(4L, sequenceNumber.relevantExecutionCount)
        assertEquals(2L, sequenceNumber.recordedCount)
        assertEquals(2L, sequenceNumber.missingCount)
        assertEquals(BigInteger.valueOf(-2), sequenceNumber.values.totalScaled)
        assertEquals(ExactValue.of(-1, 1), sequenceNumber.values.averageScaled)
        assertEquals(ExactValue.of(-1, 1), sequenceNumber.values.medianScaled)
        val sequenceCategory =
            sequenceDetail.fields
                .filterIsInstance<StatisticsFieldDetail.Category>()
                .single()
                .statistics
        assertEquals(2L, sequenceCategory.recordedCount)
        assertEquals(2L, sequenceCategory.missingCount)
        assertEquals(
            "s4-sequence-option",
            sequenceCategory.values
                .single()
                .id.value,
        )
        val overview = repository.overview(all)
        assertEquals(global, overview.global)
        assertEquals(repository.seriesSummaries(all), overview.series)
        assertEquals(4L, overview.series.single { it.series.id == activityId }.executionCount)
        assertEquals(Duration.ofMinutes(95), overview.series.single { it.series.id == activityId }.totalDuration)
        val detail = repository.seriesDetail(activityId, all) as StatisticsSeriesDetail.Activity
        assertEquals(activity, detail.statistics)
        assertEquals(repository.fieldCatalog(activityId), detail.fields.map { it.field })
        assertEquals(1, detail.fields.filterIsInstance<StatisticsFieldDetail.Text>().size)
        val number =
            detail.fields
                .filterIsInstance<StatisticsFieldDetail.Number>()
                .single {
                    it.field.id.value ==
                        "number-source"
                }.statistics
        assertEquals(4L, number.relevantExecutionCount)
        assertEquals(3L, number.recordedCount)
        assertEquals(1L, number.missingCount)
        assertEquals(BigInteger.valueOf(-1), number.values.totalScaled)
        assertEquals(ExactValue.of(-1, 3), number.values.averageScaled)
        assertEquals(ExactValue.of(0, 1), number.values.medianScaled)
        assertEquals(Long.MIN_VALUE, number.values.minimumScaled)
        assertEquals(Long.MAX_VALUE, number.values.maximumScaled)
        val absent =
            detail.fields
                .filterIsInstance<StatisticsFieldDetail.Number>()
                .single {
                    it.field.id.value ==
                        "calories-source"
                }.statistics
        assertEquals(0L, absent.recordedCount)
        assertEquals(4L, absent.missingCount)
        assertNull(absent.values.medianScaled)
        val dense =
            detail.fields
                .filterIsInstance<StatisticsFieldDetail.Number>()
                .single {
                    it.field.id.value ==
                        "s4-dense"
                }.statistics
        assertEquals(4L, dense.recordedCount)
        assertEquals(0L, dense.missingCount)
        assertEquals(BigInteger.TEN, dense.values.totalScaled)
        assertEquals(ExactValue.of(5, 2), dense.values.medianScaled)
        val category =
            detail.fields
                .filterIsInstance<StatisticsFieldDetail.Category>()
                .single()
                .statistics
        assertEquals(3L, category.recordedCount)
        assertEquals(1L, category.missingCount)
        assertEquals(
            mapOf("tempo-source" to 2L, "s4-no-live-tempo" to 1L),
            category.values.associate {
                it.id.value to
                    it.count
            },
        )
        assertTrue(
            category.values
                .single {
                    it.id.value == "s4-no-live-tempo"
                }.id is StatisticsCategoryOptionId.SnapshotFallback,
        )
        for (id in listOf(activityId, sequenceId)) {
            for (field in repository.seriesDetail(id, all).fields) {
                when (field) {
                    is StatisticsFieldDetail.Number ->
                        assertEquals(
                            repository.numberFieldStatistics(id, field.field.id, all),
                            field.statistics,
                        )
                    is StatisticsFieldDetail.Category ->
                        assertEquals(
                            repository.categoryFieldStatistics(id, field.field.id, all),
                            field.statistics,
                        )
                    is StatisticsFieldDetail.Text -> Unit
                }
            }
        }
        assertEquals(
            Duration.ofMinutes(105),
            repository.global(StatisticsPeriod.Day(LocalDate.parse(AUG_20))).totalTrackedDuration,
        )
        assertEquals(
            Duration.ofMinutes(20),
            repository.global(StatisticsPeriod.Day(LocalDate.parse(AUG_21))).totalTrackedDuration,
        )
        for (period in listOf(
            StatisticsPeriod.Week(LocalDate.parse("2026-08-17")),
            StatisticsPeriod.Month(YearMonth.of(2026, 8)),
            StatisticsPeriod.Custom(LocalDate.parse(AUG_20), LocalDate.parse(AUG_21)),
        )) {
            assertEquals(Duration.ofMinutes(125), repository.global(period).totalTrackedDuration)
        }
        assertEquals(
            global,
            repository
                .global(
                    StatisticsPeriod.Year(Year.of(2026)),
                ).copy(calendarDayCount = null, averageTrackedMillisecondsPerCalendarDay = null),
        )
        val empty =
            repository.seriesDetail(
                activityId,
                StatisticsPeriod.Day(LocalDate.parse("2025-01-01")),
            ) as StatisticsSeriesDetail.Activity
        assertEquals(0L, empty.statistics.executionCount)
        assertEquals(detail.fields.map { it.field }, empty.fields.map { it.field })
        assertEquals(
            BigInteger.ZERO,
            empty.fields
                .filterIsInstance<StatisticsFieldDetail.Number>()
                .first()
                .statistics.values.totalScaled,
        )
        val originalZone = java.util.TimeZone.getDefault()
        val expected = statisticsProjections(repository)
        try {
            for (zone in listOf("Pacific/Kiritimati", "America/New_York")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                assertEquals(expected, statisticsProjections(repository))
            }
        } finally {
            java.util.TimeZone.setDefault(originalZone)
        }
    }

    private fun mixedStatisticsHistory() {
        numberHistorySources()
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO activity_template_fields " +
                "(id,activity_template_id,position,name,field_type,created_at_ms,updated_at_ms) " +
                "VALUES ('s4-dense','activity',4,'Dense','NUMBER',0,0)",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sequence_template_fields " +
                "(id,sequence_template_id,position,name,field_type,created_at_ms,updated_at_ms) " +
                "VALUES ('s4-sequence-category','sequence',1,'Choice','CATEGORY',0,0), " +
                "('s4-sequence-text','sequence',2,'Text','TEXT',0,0)",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sequence_template_category_options (id,sequence_template_field_id,position,label) " +
                "VALUES ('s4-sequence-option','s4-sequence-category',0,'Choice')",
        )
        activitySnapshot("s4-timed-snapshot", "activity-series", fields = true)
        denseStatisticsSnapshotField("s4-timed-snapshot", 1)
        activitySnapshot("s4-no-live", "activity-series", mode = "NO_LIVE_TRACKING", fields = true)
        denseStatisticsSnapshotField("s4-no-live", 2)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_snapshot_category_options SET source_option_id = NULL WHERE id = 's4-no-live-tempo'",
        )
        completedActivity(
            "s4-timed",
            "s4-timed-snapshot",
            "activity-series",
            30 * MINUTE,
            AUG_20,
            values =
                listOf(
                    numberValue("s4-timed", "s4-timed-snapshot-number", Long.MIN_VALUE),
                    numberValue("s4-timed", "s4-timed-snapshot-dense", 1),
                    categoryValue("s4-timed", "s4-timed-snapshot-category", "s4-timed-snapshot-tempo"),
                ),
        )
        completedActivity(
            "s4-no-live",
            "s4-no-live",
            "activity-series",
            null,
            AUG_21,
            completedAtMs = millis("2026-08-21T12:00:00Z"),
            values =
                listOf(
                    numberValue("s4-no-live", "s4-no-live-number", 0),
                    numberValue("s4-no-live", "s4-no-live-dense", 2),
                    categoryValue("s4-no-live", "s4-no-live-category", "s4-no-live-tempo"),
                ),
        )
        activitySnapshot("s4-child-snapshot", "activity-series", fields = true)
        denseStatisticsSnapshotField("s4-child-snapshot", 3)
        completedSequence(
            "s4-root",
            "sequence-series",
            "s4-child-snapshot",
            60 * MINUTE,
            15 * MINUTE,
            AUG_20,
            withNumberField = true,
            numberValue = 0,
            categoryFieldId = "s4-sequence-category",
            categoryOptionId = "s4-sequence-option",
        )
        completedActivity(
            "s4-child",
            "s4-child-snapshot",
            "activity-series",
            60 * MINUTE,
            AUG_20,
            context = "SEQUENCE_CHILD",
            sequenceExecutionId = "s4-root",
            occurrenceId = "s4-root-occurrence",
            values =
                listOf(
                    numberValue("s4-child", "s4-child-snapshot-number", Long.MAX_VALUE),
                    numberValue("s4-child", "s4-child-snapshot-dense", 3),
                    categoryValue("s4-child", "s4-child-snapshot-category", "s4-child-snapshot-tempo"),
                ),
        )
        activitySnapshot("s4-sourceless", null)
        val nextDay = millis("2026-08-21T12:00:00Z")
        completedSequence(
            "s4-sourceless-root",
            "sequence-series",
            "s4-sourceless",
            20 * MINUTE,
            0,
            AUG_21,
            withNumberField = true,
            numberValue = -2,
            categoryFieldId = "s4-sequence-category",
            categoryOptionId = "s4-sequence-option",
            startedAtMs = nextDay,
        )
        completedActivity(
            "s4-sourceless-child",
            "s4-sourceless",
            null,
            20 * MINUTE,
            AUG_21,
            startedAtMs = nextDay,
            context = "SEQUENCE_CHILD",
            sequenceExecutionId = "s4-sourceless-root",
            occurrenceId = "s4-sourceless-root-occurrence",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_executions SET status = 'COMPLETED' WHERE id = 's4-sourceless-root'",
        )
        completedSequence("s4-tombstone", "sequence-series", "s4-child-snapshot", 10 * MINUTE, 0, AUG_20)
        completedActivity(
            "s4-tombstone-child",
            "s4-child-snapshot",
            "activity-series",
            10 * MINUTE,
            AUG_20,
            context = "SEQUENCE_CHILD",
            sequenceExecutionId = "s4-tombstone",
            occurrenceId = "s4-tombstone-occurrence",
            values = listOf(numberValue("s4-tombstone-child", "s4-child-snapshot-number", 888)),
        )
        deleteStatisticsChild(database, "s4-tombstone", "s4-tombstone-child")
        completedSequence("s4-removed", "sequence-series", "s4-child-snapshot", 10 * MINUTE, 0, AUG_20)
        completedActivity(
            "s4-removed-child",
            "s4-child-snapshot",
            "activity-series",
            10 * MINUTE,
            AUG_20,
            context = "SEQUENCE_CHILD",
            sequenceExecutionId = "s4-removed",
            occurrenceId = "s4-removed-occurrence",
            values = listOf(numberValue("s4-removed-child", "s4-child-snapshot-number", 999)),
        )
        deleteStatisticsChild(database, "s4-removed", "s4-removed-child")
        val removed = database.sequenceExecutionDao().getHistoryAggregate("s4-removed")!!
        SequenceHistoryCommandRepository(database).removeOccurrenceHistory(
            SequenceExecutionId("s4-removed"),
            SequenceHistoryStructuralRemovalCommand(
                Instant.ofEpochMilli(removed.execution.updatedAtMs),
                SequenceOccurrenceId("s4-removed-occurrence"),
                ActivityExecutionId("s4-removed-child"),
                SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                Instant.ofEpochMilli(removed.execution.endedAtMs!!),
                removed.toDomain().intervals.filter {
                    it.occurrenceId !=
                        SequenceOccurrenceId(
                            "s4-removed-occurrence",
                        )
                },
            ),
            Instant.ofEpochMilli(removed.execution.updatedAtMs + 1),
        )
        completedActivity(
            "s4-deleted",
            "s4-timed-snapshot",
            "activity-series",
            90 * MINUTE,
            AUG_20,
            values = listOf(numberValue("s4-deleted", "s4-timed-snapshot-number", 77)),
        )
        val deleted = database.activityExecutionDao().getById("s4-deleted")!!
        statisticsCommands(database).softDeleteHistory(
            ActivityExecutionId("s4-deleted"),
            Instant.ofEpochMilli(deleted.updatedAtMs),
            Instant.ofEpochMilli(
                deleted.updatedAtMs + 1,
            ),
        )
        for (status in listOf(
            "RUNNING",
            "PAUSED",
        )) {
            completedActivity("s4-$status", "s4-timed-snapshot", "activity-series", null, AUG_20, status = status)
        }
        series("s4-other-series", "ACTIVITY", "Other")
        activitySnapshot("s4-other-snapshot", "s4-other-series")
        completedActivity(
            "s4-other",
            "s4-other-snapshot",
            "s4-other-series",
            7 * MINUTE,
            "2026-11-01",
            startedAtMs = millis("2026-11-01T06:30:00Z"),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_executions SET original_zone_id = 'America/New_York', " +
                "original_utc_offset_minutes = -300 WHERE id = 's4-other'",
        )
        activitySnapshot("plan-snapshot", "activity-series", sourceTemplate = "activity", sourceRevision = 1)
        denseStatisticsSnapshotField("plan-snapshot", 4)
        database.planEntryDao().insert(
            PlanEntryEntity(
                "s4-plan",
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
        val live = liveRepository()
        live.startActivityFromPlan(
            PlanEntryId("s4-plan"),
            instant("2026-08-20T11:00:00Z"),
            instant("2026-08-20T11:00:00Z"),
            ZoneOffset.UTC,
        )
        live.completeActiveActivity(instant("2026-08-20T11:05:00Z"))
        database.activityTemplateDao().archive("activity", millis("2026-08-22T00:00:00Z"))
    }

    private fun denseStatisticsSnapshotField(
        snapshotId: String,
        defaultValue: Long,
    ) {
        // Fixture schema is completed before any execution owns this snapshot.
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO activity_snapshot_fields " +
                "(id,snapshot_id,source_field_id,position,name_at_creation,field_type,default_number_scaled) " +
                "VALUES (?,?,'s4-dense',4,'Dense','NUMBER',?)",
            arrayOf<Any>("$snapshotId-dense", snapshotId, defaultValue),
        )
    }

    private fun statisticsCommands(db: LifeTracingDatabase): ActivityCommandRepository =
        ActivityCommandRepository(
            db,
            LiveSessionRepository(db, {
                error("No runtime creation")
            }, {
                error("No pause creation")
            }, {
                error(
                    "No Sequence creation",
                )
            }, { error("No occurrence creation") }, { error("No interval creation") }),
            ActivitySnapshotFactory({
                error("No snapshot replacement")
            }, { error("No Field replacement") }, { error("No option replacement") }),
            { error("No execution creation") },
        )

    private fun mutateStatisticsHistory(
        db: LifeTracingDatabase,
        mutation: String,
    ) {
        val commands = statisticsCommands(db)
        when (mutation) {
            "correct" -> {
                val current = commands.getHistory(ActivityExecutionId("s4-timed"))!!
                val start = instant("2026-08-21T10:00:00Z")
                commands.correctHistory(
                    current.execution.id,
                    ActivityHistoryCorrection(
                        current.execution.updatedAt,
                        ActivityHistoryTimeCorrection.Timed(start, start.plusSeconds(40 * 60)),
                        ZoneOffset.UTC,
                        current.execution.values.map {
                            if (it is NumberExecutionValue && it.snapshotFieldId.value == "s4-timed-snapshot-number") {
                                it.copy(
                                    scaledValue = 8,
                                )
                            } else {
                                it
                            }
                        },
                        current.snapshot.shortComment,
                    ),
                    instant("2026-08-23T00:00:00Z"),
                )
            }
            "delete" -> {
                val row = db.activityExecutionDao().getById("s4-no-live")!!
                commands.softDeleteHistory(
                    ActivityExecutionId(row.id),
                    Instant.ofEpochMilli(row.updatedAtMs),
                    instant("2026-08-23T00:00:00Z"),
                )
            }
            "child" -> deleteStatisticsChild(db, "s4-root", "s4-child")
            else -> error("Unknown mutation")
        }
    }

    private fun deleteStatisticsChild(
        db: LifeTracingDatabase,
        rootId: String,
        childId: String,
    ) {
        val root = db.sequenceExecutionDao().getById(rootId)!!
        SequenceHistoryCommandRepository(db).deleteChildHistory(
            SequenceExecutionId(rootId),
            SequenceChildHistoryDeletionCommand(
                Instant.ofEpochMilli(root.updatedAtMs),
                SequenceOccurrenceId("$rootId-occurrence"),
                ActivityExecutionId(childId),
            ),
            Instant.ofEpochMilli(root.updatedAtMs + 1),
        )
    }

    private fun statisticsFacts(db: LifeTracingDatabase): Map<String, List<List<String?>>> {
        val tables =
            db.openHelper.writableDatabase
                .query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
        return tables.associateWith { table ->
            db.openHelper.writableDatabase.query("SELECT * FROM `$table`").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            (0 until cursor.columnCount).map {
                                if (cursor.isNull(it)) null else cursor.getString(it)
                            },
                        )
                    }
                }.sortedBy { it.toString() }
            }
        }
    }

    private fun observeStatisticsQuery(
        sql: String,
        args: List<Any?>,
    ) {
        observedSql.add(sql)
        if (sql.contains(" AS bit_counts ") ||
            sql.contains(" AS lower_median_scaled") ||
            sql.contains(" AS at_or_below_count") ||
            (sql.startsWith("SELECT COUNT(*) FROM (") && sql.contains("LEFT JOIN"))
        ) {
            observedNumberReads.add(sql to args.toList())
        }
    }

    private fun assertNumberReadPlans() {
        val queries = synchronized(observedNumberReads) { observedNumberReads.toList() }
        assertTrue("Number queries must be observed", queries.isNotEmpty())
        queries.forEach { (sql, args) ->
            val plan =
                database.openHelper.readableDatabase
                    .query("EXPLAIN QUERY PLAN $sql", args.toTypedArray())
                    .use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(cursor.getString(3))
                        }
                    }
            if (sql.contains(" AS bit_counts ")) {
                assertTrue(
                    "Bit counts must have no correlated history scans: $plan",
                    plan.none { it.contains("CORRELATED") },
                )
                assertEquals(64, Regex(Regex.escape("COUNT(CASE WHEN ((")).findAll(sql).count())
            }
            val searches = plan.filter { it.contains("SEARCH ") }
            if (sql.startsWith("SELECT COUNT(*) FROM (") && sql.contains("LEFT JOIN")) {
                assertTrue("Candidate probe must stop early", sql.contains("LIMIT ?"))
                assertTrue(
                    "Candidate probe must use the source Field index: $plan",
                    searches.first().contains("fields"),
                )
                assertTrue(plan.any { it.contains("execution_values_snapshot_field_id") })
                database.openHelper.readableDatabase.query(sql, args.toTypedArray()).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getLong(0) <= (args.last() as Number).toLong())
                }
                return@forEach
            }
            val fieldFirst =
                sql.startsWith("WITH eligible AS (SELECT recorded_values.number_scaled FROM ") &&
                    sql.substringBefore("CROSS JOIN").contains("snapshot_fields AS fields")
            if (fieldFirst) {
                assertTrue("Dense median must use the source Field index: $plan", searches.first().contains("fields"))
                assertTrue(plan.any { it.contains("execution_values_snapshot_field_id") })
            } else {
                assertTrue("Eligibility must precede Field lineage: $plan", searches.first().contains("executions"))
                assertTrue(
                    "Field metadata must use its value's primary key: $plan",
                    searches.none { it.contains("source_field_id=?") },
                )
            }
        }
        observedNumberReads.clear()
    }

    private fun seedMinorityNumberHistory() {
        database.runInTransaction {
            for (kind in listOf("activity", "sequence")) {
                val seriesId = "$kind-minority"
                val snapshot = "$kind-minority-snapshot"
                series(seriesId, kind.uppercase(), "Minority")
                if (kind == "activity") {
                    activitySnapshot(snapshot, seriesId, mode = "NO_LIVE_TRACKING")
                } else {
                    database.sequenceSnapshotDao().insertAggregate(
                        SequenceSnapshotAggregateEntity(
                            SequenceSnapshotEntity(snapshot, "Minority", null, null, null, seriesId, 0),
                            sequenceSettings(snapshot),
                        ),
                    )
                }
                repeat(24) { field ->
                    val source = "$kind-minority-$field"
                    database.openHelper.writableDatabase.execSQL(
                        "INSERT INTO ${kind}_template_fields " +
                            "(id,${kind}_template_id,position,name,field_type,created_at_ms,updated_at_ms) " +
                            "VALUES (?,?,?,'Same label','NUMBER',0,0)",
                        arrayOf<Any>(source, if (kind == "activity") "activity" else "sequence", 100 + field),
                    )
                    val owner = if (kind == "activity") "snapshot_id" else "sequence_snapshot_id"
                    database.openHelper.writableDatabase.execSQL(
                        "INSERT INTO ${kind}_snapshot_fields " +
                            "(id,$owner,source_field_id,position,name_at_creation,field_type) " +
                            "VALUES (?,?,?,?,'Same label','NUMBER')",
                        arrayOf<Any>("$snapshot-$field", snapshot, source, field),
                    )
                }
                repeat(800) { index ->
                    val id = "$kind-minority-execution-$index"
                    val columns =
                        if (kind == "activity") {
                            "context_type,completed_at_ms"
                        } else {
                            "started_at_ms,ended_at_ms,active_duration_ms,pause_duration_ms,wall_duration_ms"
                        }
                    val facts = if (kind == "activity") "'STANDALONE',1" else "0,1,1,0,1"
                    database.openHelper.writableDatabase.execSQL(
                        "INSERT INTO ${kind}_executions " +
                            "(id,snapshot_id,statistics_series_id,status,original_zone_id,primary_local_date," +
                            "created_at_ms,updated_at_ms,$columns) " +
                            "VALUES (?,?,?,'COMPLETED','UTC',?,0,1,$facts)",
                        arrayOf(id, snapshot, seriesId, AUG_20),
                    )
                    repeat(24) { field ->
                        if (field != 23 || index == 0) {
                            val value = if (index % 2 == 0) Long.MIN_VALUE else Long.MAX_VALUE
                            database.openHelper.writableDatabase.execSQL(
                                "INSERT INTO ${kind}_execution_field_values " +
                                    "(${kind}_execution_id,snapshot_field_id,number_scaled) VALUES (?,?,?)",
                                arrayOf<Any>(id, "$snapshot-$field", value),
                            )
                        }
                    }
                }
                database.openHelper.writableDatabase.execSQL("UPDATE ${kind}_templates SET deleted_at_ms = 2")
            }
        }
    }

    private fun assertMinorityNumberHistory() {
        for (kind in listOf("activity", "sequence")) {
            val seriesId = StatisticsSeriesId("$kind-minority")
            observedNumberReads.clear()
            observedSql.clear()
            val detail = repository.seriesDetail(seriesId, StatisticsPeriod.AllTime)
            val numbers =
                detail.fields
                    .filterIsInstance<StatisticsFieldDetail.Number>()
            assertEquals(24, numbers.size)
            // Room can record background invalidation queries while these assertions inspect the log.
            val detailQueries = synchronized(observedSql) { observedSql.toList() }
            assertEquals(
                1,
                detailQueries.count {
                    it.startsWith("SELECT COUNT(*) FROM ${kind}_executions AS executions LEFT JOIN")
                },
            )
            assertEquals(24, detailQueries.count { it.startsWith("SELECT COUNT(*) FROM (") && it.contains("LIMIT") })
            numbers.forEach { number ->
                val fieldId = number.field.id.value
                val sparse = fieldId.endsWith("-23")
                val values =
                    if (sparse) {
                        listOf(Long.MIN_VALUE)
                    } else {
                        List(800) { if (it % 2 == 0) Long.MIN_VALUE else Long.MAX_VALUE }
                    }
                val expected =
                    com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
                        .numbers(values)
                assertEquals(expected, number.statistics.values)
                assertEquals(800L, number.statistics.relevantExecutionCount)
                assertEquals(if (sparse) 799L else 0L, number.statistics.missingCount)
                assertEquals(
                    number.statistics,
                    repository.numberFieldStatistics(seriesId, number.field.id, StatisticsPeriod.AllTime),
                )
            }
            val numberQueries = synchronized(observedNumberReads) { observedNumberReads.toList() }
            val medians = numberQueries.filter { it.first.contains(" AS lower_median_scaled") }
            assertEquals(48, medians.size)
            assertTrue(
                "Every dense/sparse median must avoid repeated all-Field execution scans",
                medians.all { it.first.substringBefore("CROSS JOIN").contains("snapshot_fields AS fields") },
            )
            assertNumberReadPlans()
        }
    }

    private fun numberHistorySources() {
        series("activity-series", "ACTIVITY", "Activity")
        series("sequence-series", "SEQUENCE", "Sequence")
        activityTemplateWithFields()
        sequenceTemplate(
            "sequence",
            "Sequence",
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
        activitySnapshot("number-history", "activity-series", mode = "NO_LIVE_TRACKING", fields = true)
    }

    private fun numberHistoryIdentities(): List<Pair<StatisticsSeriesId, StatisticsFieldId>> =
        listOf(
            StatisticsSeriesId("activity-series") to
                StatisticsFieldId.Activity(ActivityTemplateFieldId("number-source")),
            StatisticsSeriesId("sequence-series") to
                StatisticsFieldId.Sequence(SequenceTemplateFieldId("sequence-number-source")),
        )

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
                pauses =
                    if (status == "PAUSED") {
                        listOf(ActivityExecutionPauseEntity("$id-pause", id, startedAtMs, null))
                    } else {
                        emptyList()
                    },
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
        startedAtMs: Long = START_2330,
    ): Pair<String, String> {
        require((categoryFieldId == null) == (categoryOptionId == null))
        val snapshotId = "$id-snapshot"
        val occurrenceId = "$id-occurrence"
        val started = startedAtMs
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
