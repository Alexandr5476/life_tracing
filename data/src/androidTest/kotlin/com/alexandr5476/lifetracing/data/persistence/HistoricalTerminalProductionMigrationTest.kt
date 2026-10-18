@file:Suppress("LongMethod", "TooManyFunctions", "MaxLineLength")
// Fixtures use v7/v8 DDL and historical SQL, never current-version entity writers.

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryActualValue
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HistoricalTerminalProductionMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()
    private val start = Instant.parse("2020-01-01T22:30:00.123Z")
    private val date = LocalDate.of(2020, 1, 2)
    private val zone = ZoneId.of("Asia/Tokyo")
    private val executionId = SequenceExecutionId("terminal")
    private val activitySeries = StatisticsSeriesId("activity-series")
    private val sequenceSeries = StatisticsSeriesId("sequence-series")

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @After
    fun cleanFiles() = names.forEach(context::deleteDatabase)

    @Test
    fun authenticV7CompletedSkippedAndDeletedHistorySurvivesUpgrade() = acceptance(7, false)

    @Test
    fun authenticV7EndedEarlySkippedAndDeletedHistorySurvivesUpgrade() = acceptance(7, true)

    @Test
    fun authenticV8CompletedSkippedAndDeletedHistorySurvivesUpgrade() = acceptance(8, false)

    @Test
    fun authenticV8EndedEarlySkippedAndDeletedHistorySurvivesUpgrade() = acceptance(8, true)

    private fun acceptance(
        version: Int,
        early: Boolean,
    ) {
        val name = "terminal-v$version-" + UUID.randomUUID()
        names += name
        val original =
            historical(version, name).use { source ->
                assertEquals(version, source.version)
                source.execSQL("PRAGMA foreign_keys = ON")
                source.beginTransaction()
                try {
                    source.seedFrozenActivityV3()
                    source.seedFrozenSequenceV6()
                    seedTerminal(source, early)
                    source.setTransactionSuccessful()
                } finally {
                    source.endTransaction()
                }
                source.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                assertTrue("Plan did not exist before v9", "plan_entries" !in source.readCurrentIntegrity())
                source.readCurrentIntegrity().keys.associateWith { source.factRows(it) }
            }
        // The source connection is closed before production registered migrations run.
        repeat(2) {
            val database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            try {
                val sqlite = database.openHelper.writableDatabase
                assertTrue(context.getDatabasePath(name).isFile)
                sqlite.assertCurrentIntegrity()
                assertFacts(sqlite, original)
                assertCanonical(database, early)
                // Canonical readers must be write-free, including deletion/tombstone hydration.
                assertFacts(sqlite, original)
                assertTrue(sqlite.factRows("active_session").isEmpty())
                assertTrue(sqlite.factRows("plan_entries").isEmpty())
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
            7 -> LifeTracingMigrationTestDatabaseFactory.createVersion7(helper, name)
            8 -> LifeTracingMigrationTestDatabaseFactory.createVersion8(helper, name)
            else -> error("Unsupported historical fixture")
        }

    private fun seedTerminal(
        source: SupportSQLiteDatabase,
        early: Boolean,
    ) {
        source.execSQL(
            "INSERT INTO sequence_executions VALUES ('terminal', 'sequence-snapshot', NULL, 'sequence-series', ?, ?, ?, 90000, 30000, 120000, 'Asia/Tokyo', 540, '2020-01-02', NULL, ?, ?)",
            arrayOf<Any>(if (early) "ENDED_EARLY" else "COMPLETED", at(0), at(120), at(0), at(140)),
        )
        for (index in 0..4) {
            val skipped = index == 1
            val runtimeAdded = index >= 3
            val deleted = index == 2 || index == 3
            val entered = (if (index == 0) 0 else (index - 1) * 30).toLong()
            source.execSQL(
                "INSERT INTO sequence_occurrences VALUES (?, 'terminal', ?, 'activity-snapshot', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "occurrence-$index",
                    if (runtimeAdded) {
                        null
                    } else if (index == 0) {
                        "f-top"
                    } else {
                        "f-nested"
                    },
                    index,
                    if (index in 1..2) "f-repeat" else null,
                    if (index in 1..2) index else null,
                    if (skipped) {
                        "SKIPPED"
                    } else if (deleted) {
                        "DELETED_EXECUTION"
                    } else {
                        "COMPLETED"
                    },
                    if (skipped) null else at(entered),
                    if (skipped) null else at(entered + 30),
                    if (skipped) {
                        null
                    } else if (early && index == 4) {
                        "SEQUENCE_ENDED_EARLY"
                    } else {
                        "MANUAL_FINISH"
                    },
                    if (runtimeAdded) 1 else 0,
                    if (index == 3) 1 else 0,
                ),
            )
            if (skipped) continue // An untouched skipped Step has no child or values.
            source.execSQL(
                "INSERT INTO activity_executions VALUES (?, 'activity-snapshot', 'SEQUENCE_CHILD', 'terminal', ?, NULL, 'activity-series', 'COMPLETED', ?, ?, 30000, 'Asia/Tokyo', 540, '2020-01-02', NULL, ?, ?, ?)",
                arrayOf<Any?>(
                    "child-$index",
                    "occurrence-$index",
                    at(entered),
                    at(entered + 30),
                    if (deleted) at(130 + index.toLong()) else null,
                    at(entered),
                    if (deleted) at(130 + index.toLong()) else at(entered + 30),
                ),
            )
            source.execSQL(
                "INSERT INTO activity_execution_field_values VALUES (?, 'f-number', ?, NULL, NULL)",
                arrayOf<Any>("child-$index", index * 1000),
            )
            source.execSQL(
                "INSERT INTO activity_execution_field_values VALUES (?, 'f-category', NULL, 'f-option', NULL)",
                arrayOf<Any>("child-$index"),
            )
            source.execSQL(
                "INSERT INTO activity_execution_field_values VALUES (?, 'f-text', NULL, NULL, ?)",
                arrayOf<Any>("child-$index", "Recorded child $index"),
            )
            // Leave-gap structural removal drops owned intervals, retaining the historical child.
            // The untracked gap remains pause/idle time in root accounting.
            if (index == 3) continue
            source.execSQL(
                "INSERT INTO sequence_intervals VALUES (?, 'terminal', ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "interval-$index",
                    "ACTIVE_STEP",
                    at(entered),
                    at(entered + 30),
                    "occurrence-$index",
                ),
            )
        }
        source.execSQL("INSERT INTO sequence_execution_field_values VALUES ('terminal', 'sf-number', 0, NULL, NULL)")
        source.execSQL(
            "INSERT INTO sequence_execution_field_values VALUES ('terminal', 'sf-category', NULL, 'sf-option', NULL)",
        )
    }

    private fun assertCanonical(
        database: LifeTracingDatabase,
        early: Boolean,
    ) {
        val history = HistoryReadRepository(database)
        val root =
            history.getCompletedRoots(CompletedHistoryQuery(HistoryDateRange(date, date), 10)).single() as
                CompletedSequenceHistoryRoot
        assertEquals(executionId, root.executionId)
        assertEquals(if (early) SequenceExecutionStatus.ENDED_EARLY else SequenceExecutionStatus.COMPLETED, root.status)
        assertEquals("Frozen sequence", root.title)
        assertEquals(start, root.startedAt)
        assertEquals(start.plusSeconds(120), root.completedAt)
        assertEquals(zone, root.originalZoneId)
        assertEquals(540, root.originalUtcOffsetMinutes)
        assertEquals(date, root.primaryLocalDate)
        assertEquals(Duration.ofSeconds(90), root.activeDuration)
        assertEquals(Duration.ofSeconds(30), root.pauseDuration)
        assertEquals(Duration.ofSeconds(120), root.wallDuration)
        val detail = requireNotNull(history.getSequenceDetail(executionId))
        assertEquals(root, detail.root)
        assertEquals(start.plusSeconds(140), detail.updatedAt)
        assertEquals(SequenceHistoryActualValue.Number(0), detail.fields[0].actualValue)
        assertEquals(listOf(0, 1, 2, 4), detail.occurrences.map { it.runtimePosition })
        assertEquals(
            listOf(
                RuntimeOccurrenceStatus.COMPLETED,
                RuntimeOccurrenceStatus.SKIPPED,
                RuntimeOccurrenceStatus.DELETED_EXECUTION,
                RuntimeOccurrenceStatus.COMPLETED,
            ),
            detail.occurrences.map { it.status },
        )
        detail.occurrences.forEach { occurrence ->
            val index = occurrence.runtimePosition
            assertEquals(SequenceOccurrenceId("occurrence-$index"), occurrence.occurrenceId)
            assertEquals(ActivitySnapshotId("activity-snapshot"), occurrence.activitySnapshotId)
            assertEquals(index == 4, occurrence.isRuntimeAdded)
            assertFalse(occurrence.isDeletedFromHistory)
            assertEquals(
                if (index == 4) null else SequenceSnapshotNodeId(if (index == 0) "f-top" else "f-nested"),
                occurrence.sourceSequenceSnapshotNodeId,
            )
            assertEquals(
                if (index in
                    1..2
                ) {
                    SequenceSnapshotNodeId("f-repeat")
                } else {
                    null
                },
                occurrence.repeatSourceSnapshotNodeId,
            )
            assertEquals(if (index in 1..2) index else null, occurrence.repeatIteration)
            assertEquals("Frozen activity", occurrence.activity.title)
            val entered = (if (index == 0) 0 else (index - 1) * 30).toLong()
            assertEquals(if (index == 1) null else start.plusSeconds(entered), occurrence.enteredAt)
            assertEquals(if (index == 1) null else start.plusSeconds(entered + 30), occurrence.completedAt)
            assertEquals(
                if (index == 1) {
                    null
                } else if (early && index == 4) {
                    OccurrenceCompletionReason.SEQUENCE_ENDED_EARLY
                } else {
                    OccurrenceCompletionReason.MANUAL_FINISH
                },
                occurrence.completionReason,
            )
            if (index == 1 || index == 2) {
                assertNull(occurrence.child)
                assertEquals(
                    if (index == 2) ActivityExecutionId("child-2") else null,
                    occurrence.childMutationFacts?.executionId,
                )
            } else {
                val child = requireNotNull(occurrence.child)
                assertEquals(ActivityExecutionId("child-$index"), child.executionId)
                assertEquals(occurrence.enteredAt, child.startedAt)
                assertEquals(occurrence.completedAt, child.completedAt)
                assertEquals(Duration.ofSeconds(30), child.activeDuration)
                assertEquals(zone, child.originalZoneId)
                assertEquals(540, child.originalUtcOffsetMinutes)
                assertEquals(ActivityHistoryActualValue.Number(index * 1000L), child.fields[0].actualValue)
            }
        }
        val children = database.activityExecutionDao().getSequenceChildAggregates("terminal").map { it.toDomain() }
        assertEquals(setOf("child-0", "child-2", "child-3", "child-4"), children.map { it.id.value }.toSet())
        children.forEach { child ->
            val index =
                child.id.value
                    .removePrefix("child-")
                    .toInt()
            assertEquals(executionId, child.sequenceExecutionId)
            assertEquals(SequenceOccurrenceId("occurrence-$index"), child.sequenceOccurrenceId)
            assertEquals(ActivitySnapshotId("activity-snapshot"), child.snapshotId)
            assertEquals(activitySeries, child.statisticsSeriesId)
            assertEquals(date, child.primaryLocalDate)
            assertEquals(zone, child.originalZoneId)
            assertEquals(540, child.originalUtcOffsetMinutes)
            assertEquals(if (index in 2..3) start.plusSeconds(130 + index.toLong()) else null, child.deletedAt)
            assertEquals(child.deletedAt ?: child.completedAt, child.updatedAt)
        }
        assertEquals(
            listOf("interval-0", "interval-2", "interval-4"),
            detail.intervals.map { it.id.value },
        )
        val daily =
            DailyReadRepository(database, CurrentZoneIdProvider { zone })
                .getDaily(DailyQuery(date, start.plusSeconds(200), 10))
        assertEquals(listOf(root), daily.completedHistory)
        assertNull(daily.active)
        assertTrue(daily.dayPlans.isEmpty())
        assertTrue(daily.weekPlans.isEmpty())
        assertNull(LiveSessionRepository.create(database).getActiveRuntime())

        val statistics = StatisticsRepository(database) { error("Reads cannot allocate statistical identity") }
        val global = statistics.global(StatisticsPeriod.AllTime)
        assertEquals(1L, global.topLevelExecutionCount)
        assertEquals(Duration.ofSeconds(90), global.totalTrackedDuration)
        assertEquals(Duration.ofSeconds(30), global.totalSequencePauseIdleDuration)
        val activity = statistics.activitySeries(activitySeries, StatisticsPeriod.AllTime)
        assertEquals(2L, activity.executionCount) // Only child-0 and child-4, never skipped/deleted children.
        assertEquals(2L, activity.durations.sampleCount)
        assertEquals(Duration.ofSeconds(60), activity.durations.total)
        val number =
            statistics.numberFieldStatistics(
                activitySeries,
                StatisticsFieldId.Activity(ActivityTemplateFieldId("number")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(2L, number.recordedCount)
        assertEquals(0L, number.missingCount)
        assertEquals(BigInteger.valueOf(4000), number.values.totalScaled)
        val sequence = statistics.sequenceSeries(sequenceSeries, StatisticsPeriod.AllTime)
        assertEquals(1L, sequence.executionCount)
        assertEquals(Duration.ofSeconds(90), sequence.activeDurations.total)
        assertEquals(Duration.ofSeconds(30), sequence.totalPauseIdleDuration)
    }

    private fun assertFacts(
        sqlite: SupportSQLiteDatabase,
        original: Map<String, List<List<String?>>>,
    ) {
        original.forEach { (table, rows) ->
            assertEquals("Historical status/deletion/provenance corruption in $table", rows, sqlite.factRows(table))
        }
    }

    private fun SupportSQLiteDatabase.factRows(table: String): List<List<String?>> =
        query("SELECT * FROM `$table` ORDER BY 1, 2").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private fun at(seconds: Long) = start.plusSeconds(seconds).toEpochMilli()
}
