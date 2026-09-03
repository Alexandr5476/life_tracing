@file:Suppress("LargeClass", "LongMethod", "LongParameterList", "MaxLineLength")

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionContext
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPause
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.RuntimeOccurrence
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceChildTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceExecution
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.ConcurrentModificationException
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class SequenceHistoryCommandRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var repository: SequenceHistoryCommandRepository
    private val observedSql = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .setQueryCallback({ sql, _ -> observedSql += sql }, Executor { it.run() })
                .allowMainThreadQueries()
                .build()
        repository = SequenceHistoryCommandRepository(database)
        seedTimedGraph()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun rootTimingCorrectionPersistsAndReloadsWithDerivedCaches() {
        val before = requireNotNull(HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID))

        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(before.updatedAt, startedAt = second(9), endedAt = second(31)),
            second(40),
        )

        val reloaded = requireNotNull(HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID))
        assertEquals(second(9), reloaded.root.startedAt)
        assertEquals(second(31), reloaded.root.completedAt)
        assertEquals(Duration.ofSeconds(20), reloaded.root.activeDuration)
        assertEquals(Duration.ofSeconds(2), reloaded.root.pauseDuration)
        assertEquals(Duration.ofSeconds(22), reloaded.root.wallDuration)
        assertEquals(before.occurrences, reloaded.occurrences)
        assertEquals(before.intervals, reloaded.intervals)
    }

    @Test
    fun nullableOffsetAndCanonicalDetailTokenSupportConsecutiveCorrections() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_executions SET original_zone_id = 'Europe/Berlin', " +
                "original_utc_offset_minutes = NULL WHERE id = ?",
            arrayOf(SEQUENCE_ID.value),
        )
        val history = HistoryReadRepository(database)
        val first = requireNotNull(history.getSequenceDetail(SEQUENCE_ID))
        assertEquals(
            Instant.ofEpochMilli(
                requireNotNull(database.sequenceExecutionDao().getById(SEQUENCE_ID.value)).updatedAtMs,
            ),
            first.updatedAt,
        )

        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(first.updatedAt, endedAt = second(31)),
            second(40),
        )
        val second = requireNotNull(history.getSequenceDetail(SEQUENCE_ID))
        assertEquals(second(40), second.updatedAt)
        assertNull(database.sequenceExecutionDao().getById(SEQUENCE_ID.value)?.originalUtcOffsetMinutes)

        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(second.updatedAt, startedAt = second(9), endedAt = second(30)),
            second(50),
        )
        val third = requireNotNull(history.getSequenceDetail(SEQUENCE_ID))
        assertEquals(second(30), third.root.completedAt)
        assertEquals(second(50), third.updatedAt)
        assertTrue(third.updatedAt > second.updatedAt)
        assertEquals(60, database.sequenceExecutionDao().getById(SEQUENCE_ID.value)?.originalUtcOffsetMinutes)
        assertThrows(ConcurrentModificationException::class.java) {
            repository.correctTiming(
                SEQUENCE_ID,
                SequenceHistoryTimingCorrection(first.updatedAt, endedAt = second(31)),
                second(60),
            )
        }
    }

    @Test
    fun localDateAndDstCorrectionMovesCanonicalHistoryAndDailyBuckets() {
        val zone = ZoneId.of("Europe/Berlin")
        val oldStart = Instant.parse("2026-10-25T02:30:00Z")
        val newStart = Instant.parse("2026-10-24T21:30:00Z")
        shiftBaseGraph(oldStart, zone)
        val oldDate = LocalDate.parse("2026-10-25")
        val newDate = LocalDate.parse("2026-10-24")
        val history = HistoryReadRepository(database)
        val daily = DailyReadRepository(database, CurrentZoneIdProvider { ZoneOffset.UTC })
        val token = detailToken()

        assertEquals(1, history.roots(oldDate).size)
        assertEquals(1, daily.getDaily(DailyQuery(oldDate, oldStart.plusSeconds(30), 10)).completedHistory.size)
        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(token, startedAt = newStart),
            oldStart.plusSeconds(30),
        )

        val root = requireNotNull(database.sequenceExecutionDao().getById(SEQUENCE_ID.value))
        assertEquals(newDate.toString(), root.primaryLocalDate)
        assertEquals(120, root.originalUtcOffsetMinutes)
        assertTrue(history.roots(oldDate).isEmpty())
        assertEquals(1, history.roots(newDate).size)
        assertTrue(daily.getDaily(DailyQuery(oldDate, oldStart.plusSeconds(30), 10)).completedHistory.isEmpty())
        assertEquals(1, daily.getDaily(DailyQuery(newDate, oldStart.plusSeconds(30), 10)).completedHistory.size)
    }

    @Test
    fun correctedRootAndChildrenMoveStatisticsDayBucketsWithoutDoubleCounting() {
        val zone = ZoneId.of("America/New_York")
        val oldStart = Instant.parse("2026-01-02T04:59:30Z")
        val newStart = Instant.parse("2026-01-02T05:00:10Z")
        val oldDate = LocalDate.parse("2026-01-01")
        val newDate = LocalDate.parse("2026-01-02")
        shiftBaseGraph(oldStart, zone)
        val statistics = StatisticsRepository(database) { StatisticsSeriesId("unused") }

        assertEquals(
            1,
            statistics
                .sequenceSeries(
                    StatisticsSeriesId("sequence-series"),
                    StatisticsPeriod.Day(oldDate),
                ).executionCount,
        )
        assertEquals(
            2,
            statistics
                .activitySeries(
                    StatisticsSeriesId("activity-series"),
                    StatisticsPeriod.Day(oldDate),
                ).executionCount,
        )
        assertEquals(Duration.ofSeconds(20), statistics.global(StatisticsPeriod.Day(oldDate)).totalTrackedDuration)
        assertEquals(
            0,
            statistics
                .sequenceSeries(
                    StatisticsSeriesId("sequence-series"),
                    StatisticsPeriod.Day(newDate),
                ).executionCount,
        )

        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(
                expectedUpdatedAt = detailToken(),
                startedAt = newStart,
                endedAt = newStart.plusSeconds(20),
                occurrenceTimings =
                    listOf(
                        SequenceOccurrenceTimingCorrection(
                            SequenceOccurrenceId("a"),
                            newStart,
                            newStart.plusSeconds(10),
                        ),
                        SequenceOccurrenceTimingCorrection(
                            SequenceOccurrenceId("b"),
                            newStart.plusSeconds(10),
                            newStart.plusSeconds(20),
                        ),
                    ),
                finalIntervals =
                    listOf(
                        interval(
                            "a",
                            newStart.epochSecond,
                            newStart.plusSeconds(10).epochSecond,
                            SequenceOccurrenceId("a"),
                        ),
                        interval(
                            "b",
                            newStart.plusSeconds(10).epochSecond,
                            newStart.plusSeconds(20).epochSecond,
                            SequenceOccurrenceId("b"),
                        ),
                    ),
                childTimings =
                    listOf(
                        SequenceChildTimingCorrection(
                            ActivityExecutionId("child-a"),
                            ActivityHistoryTimeCorrection.Timed(newStart, newStart.plusSeconds(10)),
                        ),
                        SequenceChildTimingCorrection(
                            ActivityExecutionId("child-b"),
                            ActivityHistoryTimeCorrection.Timed(newStart.plusSeconds(10), newStart.plusSeconds(20)),
                        ),
                    ),
            ),
            newStart.plusSeconds(30),
        )

        assertEquals(
            0,
            statistics
                .sequenceSeries(
                    StatisticsSeriesId("sequence-series"),
                    StatisticsPeriod.Day(oldDate),
                ).executionCount,
        )
        assertEquals(
            0,
            statistics
                .activitySeries(
                    StatisticsSeriesId("activity-series"),
                    StatisticsPeriod.Day(oldDate),
                ).executionCount,
        )
        assertEquals(Duration.ZERO, statistics.global(StatisticsPeriod.Day(oldDate)).totalTrackedDuration)
        val sequence = statistics.sequenceSeries(StatisticsSeriesId("sequence-series"), StatisticsPeriod.Day(newDate))
        val activity = statistics.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.Day(newDate))
        val global = statistics.global(StatisticsPeriod.Day(newDate))
        assertEquals(1, sequence.executionCount)
        assertEquals(Duration.ofSeconds(20), sequence.activeDurations.total)
        assertEquals(2, activity.executionCount)
        assertEquals(Duration.ofSeconds(20), activity.durations.total)
        assertEquals(Duration.ofSeconds(20), global.totalTrackedDuration)
        assertEquals(1, global.topLevelExecutionCount)
    }

    @Test
    fun coordinatedOccurrenceIntervalAndChildCorrectionRoundTripsWithoutMovingLaterStep() {
        val before = requireNotNull(HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID))
        val later = before.occurrences.single { it.occurrenceId.value == "b" }
        val intervals =
            listOf(
                interval("a-corrected", 11, 19, SequenceOccurrenceId("a")),
                interval("b", 20, 30, SequenceOccurrenceId("b")),
            )
        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(
                expectedUpdatedAt = before.updatedAt,
                occurrenceTimings =
                    listOf(SequenceOccurrenceTimingCorrection(SequenceOccurrenceId("a"), second(11), second(19))),
                finalIntervals = intervals,
                childTimings =
                    listOf(
                        SequenceChildTimingCorrection(
                            ActivityExecutionId("child-a"),
                            ActivityHistoryTimeCorrection.Timed(second(11), second(19)),
                        ),
                    ),
            ),
            second(40),
        )

        val reloaded = requireNotNull(HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID))
        val corrected = reloaded.occurrences.single { it.occurrenceId.value == "a" }
        assertEquals(second(11), corrected.enteredAt)
        assertEquals(second(19), corrected.completedAt)
        assertEquals(second(11), corrected.child?.startedAt)
        assertEquals(Duration.ofSeconds(8), corrected.child?.activeDuration)
        assertEquals(later, reloaded.occurrences.single { it.occurrenceId.value == "b" })
        assertEquals(intervals, reloaded.intervals)
        assertEquals(Duration.ofSeconds(18), reloaded.root.activeDuration)
    }

    @Test
    fun overlappingIntervalsPersistWithUnionCacheAndChildPausesRemainCoherent() {
        val overlapping =
            listOf(
                interval("a-overlap", 10, 20, SequenceOccurrenceId("a")),
                interval("b-overlap", 15, 30, SequenceOccurrenceId("b")),
            )
        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(
                detailToken(),
                occurrenceTimings =
                    listOf(SequenceOccurrenceTimingCorrection(SequenceOccurrenceId("b"), second(15), second(30))),
                finalIntervals = overlapping,
                childTimings =
                    listOf(
                        SequenceChildTimingCorrection(
                            ActivityExecutionId("child-b"),
                            ActivityHistoryTimeCorrection.Timed(second(15), second(30)),
                        ),
                    ),
            ),
            second(40),
        )
        val overlapReloaded = requireNotNull(HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID))
        assertEquals(Duration.ofSeconds(20), overlapReloaded.root.activeDuration)
        assertEquals(overlapping, overlapReloaded.intervals)

        seedPausedGraph()
        repository.correctTiming(
            PAUSED_SEQUENCE_ID,
            SequenceHistoryTimingCorrection(
                detailToken(PAUSED_SEQUENCE_ID),
                occurrenceTimings =
                    listOf(
                        SequenceOccurrenceTimingCorrection(
                            SequenceOccurrenceId("paused-a"),
                            second(10),
                            second(19),
                        ),
                    ),
                finalIntervals =
                    listOf(
                        interval("paused-a-1", 10, 12, SequenceOccurrenceId("paused-a")),
                        interval("paused-a-2", 14, 19, SequenceOccurrenceId("paused-a")),
                        interval("paused-b", 20, 30, SequenceOccurrenceId("paused-b")),
                    ),
                childTimings =
                    listOf(
                        SequenceChildTimingCorrection(
                            ActivityExecutionId("paused-child-a"),
                            ActivityHistoryTimeCorrection.Timed(second(10), second(19)),
                        ),
                    ),
            ),
            second(40),
        )
        val child = requireNotNull(database.activityExecutionDao().getAggregate("paused-child-a"))
        assertEquals(
            listOf(ActivityExecutionPauseEntity("paused-pause", "paused-child-a", 12_000, 14_000)),
            child.pauses,
        )
        assertEquals(7_000L, child.execution.activeDurationMs)
        assertEquals(
            Duration.ofSeconds(17),
            requireNotNull(HistoryReadRepository(database).getSequenceDetail(PAUSED_SEQUENCE_ID)).root.activeDuration,
        )
    }

    @Test
    fun noLiveCorrectionPersistsMissingDurationAndPauseAccounting() {
        seedNoLiveGraph()
        repository.correctTiming(
            NO_LIVE_SEQUENCE_ID,
            SequenceHistoryTimingCorrection(
                detailToken(NO_LIVE_SEQUENCE_ID),
                occurrenceTimings =
                    listOf(
                        SequenceOccurrenceTimingCorrection(
                            SequenceOccurrenceId("no-live-occurrence"),
                            second(10),
                            second(18),
                        ),
                    ),
                finalIntervals =
                    listOf(
                        SequenceInterval(
                            SequenceIntervalId("no-live-pause"),
                            SequenceIntervalKind.STEP_PAUSE,
                            second(10),
                            second(18),
                            SequenceOccurrenceId("no-live-occurrence"),
                        ),
                    ),
                childTimings =
                    listOf(
                        SequenceChildTimingCorrection(
                            ActivityExecutionId("no-live-child"),
                            ActivityHistoryTimeCorrection.NoLive(second(18)),
                        ),
                    ),
            ),
            second(40),
        )
        val detail = requireNotNull(HistoryReadRepository(database).getSequenceDetail(NO_LIVE_SEQUENCE_ID))
        val child = requireNotNull(detail.occurrences.single().child)
        assertNull(child.startedAt)
        assertNull(child.activeDuration)
        assertEquals(second(18), child.completedAt)
        assertEquals(Duration.ZERO, detail.root.activeDuration)
        assertEquals(Duration.ofSeconds(10), detail.root.pauseDuration)
    }

    @Test
    fun liveStatesAndTerminalActiveSessionOwnershipRejectWithoutWrites() {
        listOf("RUNNING", "PAUSED").forEach { status ->
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sequence_executions SET status = ?, ended_at_ms = NULL, active_duration_ms = NULL, " +
                    "pause_duration_ms = NULL, wall_duration_ms = NULL WHERE id = ?",
                arrayOf(status, SEQUENCE_ID.value),
            )
            val before = requireNotNull(database.sequenceExecutionDao().getById(SEQUENCE_ID.value))
            assertThrows(IllegalArgumentException::class.java) {
                repository.correctTiming(
                    SEQUENCE_ID,
                    SequenceHistoryTimingCorrection(second(30), endedAt = second(31)),
                    second(40),
                )
            }
            assertEquals(before, database.sequenceExecutionDao().getById(SEQUENCE_ID.value))
        }
        restoreBaseTerminalRoot()
        database.activeSessionDao().insert(
            ActiveSession(ActiveSessionKind.SEQUENCE, ActiveSessionState.RUNNING, null, SEQUENCE_ID, second(30)),
        )
        val before = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertThrows(IllegalArgumentException::class.java) {
            repository.correctTiming(
                SEQUENCE_ID,
                SequenceHistoryTimingCorrection(
                    detailToken(),
                    endedAt = second(29),
                    occurrenceTimings =
                        listOf(SequenceOccurrenceTimingCorrection(SequenceOccurrenceId("b"), second(20), second(29))),
                    finalIntervals =
                        listOf(
                            interval("a", 10, 20, SequenceOccurrenceId("a")),
                            interval("b-short", 20, 29, SequenceOccurrenceId("b")),
                        ),
                    childTimings =
                        listOf(
                            SequenceChildTimingCorrection(
                                ActivityExecutionId("child-b"),
                                ActivityHistoryTimeCorrection.Timed(second(20), second(29)),
                            ),
                        ),
                ),
                second(40),
            )
        }
        assertEquals(before, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
    }

    @Test
    fun staleAndWrongIdentityCorrectionsFailWithoutPartialWrites() {
        val initialToken = detailToken()
        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(initialToken, startedAt = second(9)),
            second(40),
        )
        val persisted = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertThrows(ConcurrentModificationException::class.java) {
            repository.correctTiming(
                SEQUENCE_ID,
                SequenceHistoryTimingCorrection(initialToken, endedAt = second(31)),
                second(50),
            )
        }
        val currentToken = detailToken()
        listOf(
            SequenceHistoryTimingCorrection(
                currentToken,
                occurrenceTimings =
                    listOf(SequenceOccurrenceTimingCorrection(SequenceOccurrenceId("foreign"), second(10), second(20))),
            ),
            SequenceHistoryTimingCorrection(
                currentToken,
                childTimings =
                    listOf(
                        SequenceChildTimingCorrection(
                            ActivityExecutionId("foreign"),
                            ActivityHistoryTimeCorrection.Timed(second(10), second(20)),
                        ),
                    ),
            ),
        ).forEach { correction ->
            assertThrows(IllegalArgumentException::class.java) {
                repository.correctTiming(SEQUENCE_ID, correction, second(50))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.correctTiming(
                SequenceExecutionId("foreign"),
                SequenceHistoryTimingCorrection(currentToken),
                second(50),
            )
        }
        assertEquals(persisted, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
    }

    @Test
    fun unlinkedAndUnchangedEndCorrectionsDoNotMutatePlans() {
        val unrelated = insertPlannedSequencePlan("unrelated-plan")
        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(detailToken(), endedAt = second(31)),
            second(40),
        )
        assertEquals(unrelated, database.planEntryDao().getById(unrelated.id))

        val linked = linkFulfilledPlan("linked-unchanged")
        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(detailToken(), startedAt = second(8)),
            second(50),
        )
        assertEquals(linked, database.planEntryDao().getById(linked.id))
    }

    @Test
    fun changedPlanEndSynchronizesOnlyFulfillmentAndAdvancesMutationMetadata() {
        val beforePlan = linkFulfilledPlan("linked-plan")
        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(
                detailToken(),
                endedAt = second(29),
                occurrenceTimings =
                    listOf(SequenceOccurrenceTimingCorrection(SequenceOccurrenceId("b"), second(20), second(29))),
                finalIntervals =
                    listOf(
                        interval("a", 10, 20, SequenceOccurrenceId("a")),
                        interval("b-short", 20, 29, SequenceOccurrenceId("b")),
                    ),
                childTimings =
                    listOf(
                        SequenceChildTimingCorrection(
                            ActivityExecutionId("child-b"),
                            ActivityHistoryTimeCorrection.Timed(second(20), second(29)),
                        ),
                    ),
            ),
            second(40),
        )
        val afterPlan = requireNotNull(database.planEntryDao().getById(beforePlan.id))
        assertEquals(beforePlan.copy(fulfilledAtMs = 29_000, updatedAtMs = 40_000), afterPlan)
        assertEquals(40_000L, database.sequenceExecutionDao().getById(SEQUENCE_ID.value)?.updatedAtMs)
        assertEquals(40_000L, database.activityExecutionDao().getById("child-b")?.updatedAtMs)
        assertEquals("FULFILLED", afterPlan.status)
        assertEquals(SEQUENCE_ID.value, afterPlan.fulfilledSequenceExecutionId)
    }

    @Test
    fun guardedPlanFailureRollsBackEveryHistoricalWrite() {
        val plan = linkFulfilledPlan("rollback-plan")
        val rootBefore = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        val childrenBefore = database.activityExecutionDao().getSequenceChildAggregates(SEQUENCE_ID.value)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_history_plan BEFORE UPDATE OF fulfilled_at_ms ON plan_entries " +
                "BEGIN SELECT RAISE(ABORT, 'forced plan failure'); END",
        )
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            repository.correctTiming(
                SEQUENCE_ID,
                SequenceHistoryTimingCorrection(detailToken(), endedAt = second(31)),
                second(40),
            )
        }
        assertEquals(rootBefore, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertEquals(childrenBefore, database.activityExecutionDao().getSequenceChildAggregates(SEQUENCE_ID.value))
        assertEquals(plan, database.planEntryDao().getById(plan.id))
    }

    @Test
    fun statisticsReloadCorrectedRootAndChildrenWithoutDoubleCountingGlobalTime() {
        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(
                detailToken(),
                occurrenceTimings =
                    listOf(SequenceOccurrenceTimingCorrection(SequenceOccurrenceId("a"), second(10), second(18))),
                finalIntervals =
                    listOf(
                        interval("a-short", 10, 18, SequenceOccurrenceId("a")),
                        interval("b", 20, 30, SequenceOccurrenceId("b")),
                    ),
                childTimings =
                    listOf(
                        SequenceChildTimingCorrection(
                            ActivityExecutionId("child-a"),
                            ActivityHistoryTimeCorrection.Timed(second(10), second(18)),
                        ),
                    ),
            ),
            second(40),
        )
        val statistics = StatisticsRepository(database) { StatisticsSeriesId("unused") }
        val sequence = statistics.sequenceSeries(StatisticsSeriesId("sequence-series"), StatisticsPeriod.AllTime)
        val activity = statistics.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime)
        val global = statistics.global(StatisticsPeriod.AllTime)
        assertEquals(Duration.ofSeconds(18), sequence.activeDurations.total)
        assertEquals(Duration.ofSeconds(18), activity.durations.total)
        assertEquals(Duration.ofSeconds(18), global.totalTrackedDuration)
        assertEquals(1, global.topLevelExecutionCount)
    }

    @Test
    fun graphLoadingUsesBatchedOwnerAndSnapshotQueries() {
        val token = detailToken()
        observedSql.clear()
        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(token, startedAt = second(9)),
            second(40),
        )
        val queries = synchronized(observedSql) { observedSql.map(String::lowercase) }
        assertEquals(1, queries.count { "from activity_executions" in it && "sequence_execution_id = ?" in it })
        assertEquals(1, queries.count { "from activity_execution_pauses" in it && " in (" in it })
        assertEquals(1, queries.count { "from activity_execution_field_values" in it && " in (" in it })
        assertEquals(1, queries.count { it.startsWith("select * from activity_snapshots where id in") })
        assertFalse(queries.any { it.startsWith("select * from activity_snapshots where id =") })
    }

    private fun seedTimedGraph() {
        val fixtures = LiveRuntimeTestFixtures(database)
        fixtures.seedSeries()
        fixtures.activity("activity-a", "STOPWATCH")
        fixtures.activity("activity-b", "STOPWATCH")
        fixtures.sequence(SNAPSHOT_ID.value, listOf("activity-a", "activity-b"))
        val occurrenceA = occurrence("a", "activity-a", 0, 10, 20)
        val occurrenceB = occurrence("b", "activity-b", 1, 20, 30)
        val intervals =
            listOf(
                interval("a", 10, 20, occurrenceA.id),
                interval("b", 20, 30, occurrenceB.id),
            )
        val durations =
            com.alexandr5476.lifetracing.domain.SequenceTimelineCalculator.calculate(
                second(10),
                second(30),
                intervals,
            )
        val execution =
            SequenceExecution(
                SEQUENCE_ID,
                SNAPSHOT_ID,
                StatisticsSeriesId("sequence-series"),
                SequenceExecutionStatus.COMPLETED,
                second(10),
                second(30),
                durations.active,
                durations.pause,
                durations.wall,
                ZoneOffset.UTC,
                0,
                second(10).atZone(ZoneOffset.UTC).toLocalDate(),
                null,
                second(10),
                second(30),
                listOf(occurrenceA, occurrenceB),
                intervals,
            )
        database.sequenceExecutionDao().insertAggregate(execution.toEntityAggregate())
        database.activityExecutionDao().insertAggregate(
            child("child-a", "activity-a", occurrenceA.id, 10, 20).toEntityAggregate(),
        )
        database.activityExecutionDao().insertAggregate(
            child("child-b", "activity-b", occurrenceB.id, 20, 30).toEntityAggregate(),
        )
    }

    private fun seedPausedGraph() {
        val snapshotId = SequenceSnapshotId("paused-snapshot")
        LiveRuntimeTestFixtures(database).sequence(snapshotId.value, listOf("activity-a", "activity-b"))
        val occurrenceA = occurrence("paused-a", "activity-a", 0, 10, 20, snapshotId)
        val occurrenceB = occurrence("paused-b", "activity-b", 1, 20, 30, snapshotId)
        val intervals =
            listOf(
                interval("paused-a-1", 10, 12, occurrenceA.id),
                interval("paused-a-2", 14, 20, occurrenceA.id),
                interval("paused-b", 20, 30, occurrenceB.id),
            )
        val durations =
            com.alexandr5476.lifetracing.domain.SequenceTimelineCalculator.calculate(
                second(10),
                second(30),
                intervals,
            )
        database.sequenceExecutionDao().insertAggregate(
            sequence(PAUSED_SEQUENCE_ID, snapshotId, listOf(occurrenceA, occurrenceB), intervals, durations),
        )
        val pause = ActivityExecutionPause(ActivityExecutionPauseId("paused-pause"), second(12), second(14))
        database.activityExecutionDao().insertAggregate(
            child("paused-child-a", "activity-a", occurrenceA.id, 10, 20, PAUSED_SEQUENCE_ID)
                .copy(pauses = listOf(pause), activeDuration = Duration.ofSeconds(8))
                .toEntityAggregate(),
        )
        database.activityExecutionDao().insertAggregate(
            child("paused-child-b", "activity-b", occurrenceB.id, 20, 30, PAUSED_SEQUENCE_ID).toEntityAggregate(),
        )
    }

    private fun seedNoLiveGraph() {
        val fixtures = LiveRuntimeTestFixtures(database)
        fixtures.activity("no-live-activity", "NO_LIVE_TRACKING")
        fixtures.sequence(
            NO_LIVE_SNAPSHOT_ID.value,
            listOf("no-live-activity"),
            noLiveAccounting = "PAUSE",
        )
        val occurrence = occurrence("no-live-occurrence", "no-live-activity", 0, 10, 20, NO_LIVE_SNAPSHOT_ID)
        val intervals =
            listOf(
                SequenceInterval(
                    SequenceIntervalId("no-live-pause"),
                    SequenceIntervalKind.STEP_PAUSE,
                    second(10),
                    second(20),
                    occurrence.id,
                ),
            )
        val durations =
            com.alexandr5476.lifetracing.domain.SequenceTimelineCalculator.calculate(
                second(10),
                second(20),
                intervals,
            )
        database.sequenceExecutionDao().insertAggregate(
            sequence(NO_LIVE_SEQUENCE_ID, NO_LIVE_SNAPSHOT_ID, listOf(occurrence), intervals, durations),
        )
        database.activityExecutionDao().insertAggregate(
            ActivityExecution(
                ActivityExecutionId("no-live-child"),
                ActivitySnapshotId("no-live-activity"),
                ActivityExecutionContext.SEQUENCE_CHILD,
                StatisticsSeriesId("activity-series"),
                ActivityExecutionStatus.COMPLETED,
                null,
                second(20),
                null,
                ZoneOffset.UTC,
                0,
                second(20).atZone(ZoneOffset.UTC).toLocalDate(),
                null,
                null,
                second(10),
                second(20),
                NO_LIVE_SEQUENCE_ID,
                occurrence.id,
                null,
            ).toEntityAggregate(),
        )
    }

    private fun sequence(
        id: SequenceExecutionId,
        snapshotId: SequenceSnapshotId,
        occurrences: List<RuntimeOccurrence>,
        intervals: List<SequenceInterval>,
        durations: com.alexandr5476.lifetracing.domain.SequenceTimelineDurations,
    ) = SequenceExecution(
        id,
        snapshotId,
        StatisticsSeriesId("sequence-series"),
        SequenceExecutionStatus.COMPLETED,
        second(10),
        second(30).takeIf { id != NO_LIVE_SEQUENCE_ID } ?: second(20),
        durations.active,
        durations.pause,
        durations.wall,
        ZoneOffset.UTC,
        0,
        second(10).atZone(ZoneOffset.UTC).toLocalDate(),
        null,
        second(10),
        second(30).takeIf { id != NO_LIVE_SEQUENCE_ID } ?: second(20),
        occurrences,
        intervals,
    ).toEntityAggregate()

    private fun shiftBaseGraph(
        start: Instant,
        zone: ZoneId,
    ) {
        val startMs = start.toEpochMilli()
        val date = start.atZone(zone).toLocalDate().toString()
        val offset = start.atZone(zone).offset.totalSeconds / 60
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_executions SET started_at_ms = ?, ended_at_ms = ?, original_zone_id = ?, " +
                "original_utc_offset_minutes = ?, primary_local_date = ?, created_at_ms = ?, updated_at_ms = ? WHERE id = ?",
            arrayOf(startMs, startMs + 20_000, zone.id, offset, date, startMs, startMs + 20_000, SEQUENCE_ID.value),
        )
        listOf("a" to 0L, "b" to 10_000L).forEach { (id, delta) ->
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sequence_occurrences SET entered_at_ms = ?, completed_at_ms = ? WHERE id = ?",
                arrayOf<Any?>(startMs + delta, startMs + delta + 10_000, id),
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sequence_intervals SET started_at_ms = ?, ended_at_ms = ? WHERE id = ?",
                arrayOf<Any?>(startMs + delta, startMs + delta + 10_000, id),
            )
        }
        listOf("child-a" to 0L, "child-b" to 10_000L).forEach { (id, delta) ->
            val childStart = Instant.ofEpochMilli(startMs + delta)
            database.openHelper.writableDatabase.execSQL(
                "UPDATE activity_executions SET started_at_ms = ?, completed_at_ms = ?, original_zone_id = ?, " +
                    "original_utc_offset_minutes = ?, primary_local_date = ?, created_at_ms = ?, updated_at_ms = ? WHERE id = ?",
                arrayOf(
                    childStart.toEpochMilli(),
                    childStart.plusSeconds(10).toEpochMilli(),
                    zone.id,
                    childStart.atZone(zone).offset.totalSeconds / 60,
                    childStart.atZone(zone).toLocalDate().toString(),
                    childStart.toEpochMilli(),
                    childStart.plusSeconds(10).toEpochMilli(),
                    id,
                ),
            )
        }
    }

    private fun restoreBaseTerminalRoot() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_executions SET status = 'COMPLETED', ended_at_ms = 30000, active_duration_ms = 20000, " +
                "pause_duration_ms = 0, wall_duration_ms = 20000 WHERE id = ?",
            arrayOf(SEQUENCE_ID.value),
        )
    }

    private fun insertPlannedSequencePlan(id: String): PlanEntryEntity {
        val plan =
            PlanEntryEntity(
                id,
                "SEQUENCE",
                null,
                null,
                null,
                null,
                SNAPSHOT_ID.value,
                "DAY",
                "1970-01-01",
                null,
                null,
                null,
                null,
                "PLANNED",
                null,
                null,
                0,
                0,
                null,
                null,
            )
        database.planEntryDao().insert(plan)
        return plan
    }

    private fun linkFulfilledPlan(id: String): PlanEntryEntity {
        insertPlannedSequencePlan(id)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_executions SET plan_entry_id = ? WHERE id = ?",
            arrayOf(id, SEQUENCE_ID.value),
        )
        val root = requireNotNull(database.sequenceExecutionDao().getById(SEQUENCE_ID.value))
        check(
            database.planEntryDao().fulfillSequence(
                id,
                root.snapshotId,
                root.id,
                requireNotNull(root.endedAtMs),
            ) == 1,
        )
        return requireNotNull(database.planEntryDao().getById(id))
    }

    private fun HistoryReadRepository.roots(date: LocalDate) =
        getCompletedRoots(CompletedHistoryQuery(HistoryDateRange(date, date), 10))

    private fun detailToken(id: SequenceExecutionId = SEQUENCE_ID) =
        requireNotNull(HistoryReadRepository(database).getSequenceDetail(id)).updatedAt

    private fun occurrence(
        id: String,
        activityId: String,
        position: Int,
        start: Long,
        end: Long,
        snapshotId: SequenceSnapshotId = SNAPSHOT_ID,
    ) = RuntimeOccurrence(
        SequenceOccurrenceId(id),
        SequenceSnapshotNodeId("${snapshotId.value}-step-$position"),
        ActivitySnapshotId(activityId),
        position,
        null,
        null,
        RuntimeOccurrenceStatus.COMPLETED,
        second(start),
        second(end),
        OccurrenceCompletionReason.MANUAL_FINISH,
        false,
        false,
    )

    private fun interval(
        id: String,
        start: Long,
        end: Long,
        occurrenceId: SequenceOccurrenceId,
    ) = SequenceInterval(
        SequenceIntervalId(id),
        SequenceIntervalKind.ACTIVE_STEP,
        second(start),
        second(end),
        occurrenceId,
    )

    private fun child(
        id: String,
        snapshotId: String,
        occurrenceId: SequenceOccurrenceId,
        start: Long,
        end: Long,
        sequenceId: SequenceExecutionId = SEQUENCE_ID,
    ) = ActivityExecution(
        ActivityExecutionId(id),
        ActivitySnapshotId(snapshotId),
        ActivityExecutionContext.SEQUENCE_CHILD,
        StatisticsSeriesId("activity-series"),
        ActivityExecutionStatus.COMPLETED,
        second(start),
        second(end),
        Duration.ofSeconds(end - start),
        ZoneOffset.UTC,
        0,
        second(start).atZone(ZoneOffset.UTC).toLocalDate(),
        null,
        null,
        second(start),
        second(end),
        sequenceId,
        occurrenceId,
        null,
    )

    private fun second(value: Long) = Instant.ofEpochSecond(value)

    private companion object {
        val SEQUENCE_ID = SequenceExecutionId("sequence")
        val SNAPSHOT_ID = SequenceSnapshotId("sequence-snapshot")
        val PAUSED_SEQUENCE_ID = SequenceExecutionId("paused-sequence")
        val NO_LIVE_SEQUENCE_ID = SequenceExecutionId("no-live-sequence")
        val NO_LIVE_SNAPSHOT_ID = SequenceSnapshotId("no-live-snapshot")
    }
}
