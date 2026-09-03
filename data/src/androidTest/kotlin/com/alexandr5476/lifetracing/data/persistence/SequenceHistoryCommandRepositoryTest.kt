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
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.RuntimeOccurrence
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionCommand
import com.alexandr5476.lifetracing.domain.SequenceChildTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceExecution
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalCommand
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalMode
import com.alexandr5476.lifetracing.domain.SequenceHistoryTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceStructuralChildTimingCorrection
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
    fun leaveGapPersistsHiddenOccurrenceAndCanonicalHistoryOmitsIt() {
        val plan = linkFulfilledPlan("structural-leave-plan")
        val beforeRoot = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        val beforeChild = requireNotNull(database.activityExecutionDao().getAggregate("child-a"))
        val result =
            repository.removeOccurrenceHistory(
                SEQUENCE_ID,
                SequenceHistoryStructuralRemovalCommand(
                    detailToken(),
                    SequenceOccurrenceId("a"),
                    ActivityExecutionId("child-a"),
                    SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                    second(30),
                    listOf(interval("b", 20, 30, SequenceOccurrenceId("b"))),
                ),
                second(40).plusNanos(999_999),
            )

        val durable = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        val hidden = durable.occurrences.single { it.id == "a" }
        assertEquals("DELETED_EXECUTION", hidden.status)
        assertTrue(hidden.isDeletedFromHistory)
        assertEquals(
            beforeRoot.occurrences
                .single {
                    it.id == "a"
                }.copy(status = hidden.status, isDeletedFromHistory = true),
            hidden,
        )
        assertEquals(listOf("b"), durable.intervals.map { it.id })
        assertEquals(beforeRoot.execution.startedAtMs, durable.execution.startedAtMs)
        assertEquals(beforeRoot.execution.endedAtMs, durable.execution.endedAtMs)
        assertEquals(20_000L, durable.execution.wallDurationMs)
        assertEquals(10_000L, durable.execution.activeDurationMs)
        assertEquals(10_000L, durable.execution.pauseDurationMs)
        assertEquals(40_000L, durable.execution.updatedAtMs)
        val deletedChild = requireNotNull(database.activityExecutionDao().getAggregate("child-a"))
        assertEquals(beforeChild.execution.copy(deletedAtMs = 40_000, updatedAtMs = 40_000), deletedChild.execution)
        assertEquals(beforeChild.pauses, deletedChild.pauses)
        assertEquals(beforeChild.values, deletedChild.values)
        assertEquals(plan, database.planEntryDao().getById(plan.id))

        val detail = requireNotNull(HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID))
        assertEquals(listOf("b"), detail.occurrences.map { it.occurrenceId.value })
        assertEquals(second(40), detail.updatedAt)
        assertEquals(result.execution, durable.toDomain())
        val statistics = StatisticsRepository(database) { StatisticsSeriesId("unused") }
        assertEquals(
            Duration.ofSeconds(10),
            statistics
                .sequenceSeries(
                    StatisticsSeriesId("sequence-series"),
                    StatisticsPeriod.AllTime,
                ).activeDurations.total,
        )
        assertEquals(Duration.ofSeconds(10), statistics.global(StatisticsPeriod.AllTime).totalTrackedDuration)
        assertEquals(
            1L,
            statistics.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime).executionCount,
        )

        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(detail.updatedAt, startedAt = second(9)),
            second(50),
        )
        val corrected = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertTrue(corrected.occurrences.single { it.id == "a" }.isDeletedFromHistory)
        assertEquals(40_000L, database.activityExecutionDao().getById("child-a")?.deletedAtMs)
        assertEquals(
            listOf(
                "b",
            ),
            requireNotNull(HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID)).occurrences.map {
                it.occurrenceId.value
            },
        )
    }

    @Test
    fun closeGapPersistsTranslatedSuffixAndSynchronizesFulfilledPlan() {
        val plan = linkFulfilledPlan("structural-close-plan")
        val result =
            repository.removeOccurrenceHistory(
                SEQUENCE_ID,
                SequenceHistoryStructuralRemovalCommand(
                    detailToken(),
                    SequenceOccurrenceId("a"),
                    ActivityExecutionId("child-a"),
                    SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                    second(20),
                    listOf(interval("b", 10, 20, SequenceOccurrenceId("b"))),
                    occurrenceTimings =
                        listOf(SequenceOccurrenceTimingCorrection(SequenceOccurrenceId("b"), second(10), second(20))),
                    childTimings =
                        listOf(
                            SequenceStructuralChildTimingCorrection(
                                ActivityExecutionId("child-b"),
                                ActivityHistoryTimeCorrection.Timed(second(10), second(20)),
                                emptyList(),
                            ),
                        ),
                ),
                second(40),
            )

        val durable = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertEquals(20_000L, durable.execution.endedAtMs)
        assertEquals(10_000L, durable.execution.activeDurationMs)
        assertEquals(0L, durable.execution.pauseDurationMs)
        assertEquals(10_000L, durable.execution.wallDurationMs)
        assertEquals(10_000L, durable.occurrences.single { it.id == "b" }.enteredAtMs)
        assertEquals(20_000L, durable.occurrences.single { it.id == "b" }.completedAtMs)
        assertEquals(
            SequenceIntervalEntity("b", SEQUENCE_ID.value, "ACTIVE_STEP", 10_000, 20_000, "b"),
            durable.intervals.single(),
        )
        val child = requireNotNull(database.activityExecutionDao().getAggregate("child-b"))
        assertEquals(10_000L, child.execution.startedAtMs)
        assertEquals(20_000L, child.execution.completedAtMs)
        assertEquals(10_000L, child.execution.activeDurationMs)
        assertEquals(40_000L, child.execution.updatedAtMs)
        assertEquals(plan.copy(fulfilledAtMs = 20_000, updatedAtMs = 40_000), database.planEntryDao().getById(plan.id))
        assertEquals(result.execution, durable.toDomain())
        assertEquals(
            listOf(
                "b",
            ),
            requireNotNull(HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID)).occurrences.map {
                it.occurrenceId.value
            },
        )
    }

    @Test
    fun leaveGapOnVisibleTombstonePreservesOriginalChildDeletionMetadata() {
        repository.deleteChildHistory(
            SEQUENCE_ID,
            SequenceChildHistoryDeletionCommand(
                detailToken(),
                SequenceOccurrenceId("a"),
                ActivityExecutionId("child-a"),
            ),
            second(40),
        )
        repository.removeOccurrenceHistory(
            SEQUENCE_ID,
            SequenceHistoryStructuralRemovalCommand(
                detailToken(),
                SequenceOccurrenceId("a"),
                ActivityExecutionId("child-a"),
                SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                second(30),
                listOf(interval("b", 20, 30, SequenceOccurrenceId("b"))),
            ),
            second(50),
        )

        val child = requireNotNull(database.activityExecutionDao().getById("child-a"))
        assertEquals(40_000L, child.deletedAtMs)
        assertEquals(40_000L, child.updatedAtMs)
        val root = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertTrue(root.occurrences.single { it.id == "a" }.isDeletedFromHistory)
        assertEquals(50_000L, root.execution.updatedAtMs)
    }

    @Test
    fun closeGapMovesExistingPauseRowsInPlaceAndRollsBackAtPauseFailure() {
        seedPausedLaterChildGraph()
        val beforeRoot = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(PAUSED_SEQUENCE_ID.value))
        val beforeChildren = database.activityExecutionDao().getSequenceChildAggregates(PAUSED_SEQUENCE_ID.value)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_structural_pause BEFORE UPDATE ON activity_execution_pauses " +
                "WHEN OLD.id = 'paused-b-pause' BEGIN SELECT RAISE(ABORT, 'forced pause failure'); END",
        )
        val command = closePausedLaterChildCommand()
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            repository.removeOccurrenceHistory(PAUSED_SEQUENCE_ID, command, second(40))
        }
        assertEquals(beforeRoot, database.sequenceExecutionDao().getHistoryAggregate(PAUSED_SEQUENCE_ID.value))
        assertEquals(
            beforeChildren,
            database.activityExecutionDao().getSequenceChildAggregates(PAUSED_SEQUENCE_ID.value),
        )

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_structural_pause")
        repository.removeOccurrenceHistory(PAUSED_SEQUENCE_ID, command, second(40))
        val child = requireNotNull(database.activityExecutionDao().getAggregate("paused-child-b"))
        assertEquals(10_000L, child.execution.startedAtMs)
        assertEquals(20_000L, child.execution.completedAtMs)
        assertEquals(8_000L, child.execution.activeDurationMs)
        assertEquals(
            ActivityExecutionPauseEntity("paused-b-pause", "paused-child-b", 12_000, 14_000),
            child.pauses.single(),
        )
        val root = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(PAUSED_SEQUENCE_ID.value))
        assertEquals(8_000L, root.execution.activeDurationMs)
        assertEquals(2_000L, root.execution.pauseDurationMs)
        assertEquals(10_000L, root.execution.wallDurationMs)
    }

    @Test
    fun canonicalHistoryFiltersOnlyAfterValidatingHiddenRowsAndKeepsVisibleTombstones() {
        repository.deleteChildHistory(
            SEQUENCE_ID,
            SequenceChildHistoryDeletionCommand(
                detailToken(),
                SequenceOccurrenceId("b"),
                ActivityExecutionId("child-b"),
            ),
            second(40),
        )
        repository.removeOccurrenceHistory(
            SEQUENCE_ID,
            SequenceHistoryStructuralRemovalCommand(
                detailToken(),
                SequenceOccurrenceId("a"),
                ActivityExecutionId("child-a"),
                SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                second(30),
                listOf(interval("b", 20, 30, SequenceOccurrenceId("b"))),
            ),
            second(50),
        )
        val detail = requireNotNull(HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID))
        assertEquals(1, detail.occurrences.size)
        assertEquals(RuntimeOccurrenceStatus.DELETED_EXECUTION, detail.occurrences.single().status)
        assertNull(detail.occurrences.single().child)

        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_occurrences SET activity_snapshot_id = 'activity-b' WHERE id = 'a'",
        )
        assertThrows(IllegalArgumentException::class.java) {
            HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID)
        }
    }

    @Test
    fun structuralRemovalRejectsStaleTokenAndActiveSessionWithoutWrites() {
        val valid =
            SequenceHistoryStructuralRemovalCommand(
                detailToken(),
                SequenceOccurrenceId("a"),
                ActivityExecutionId("child-a"),
                SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                second(30),
                listOf(interval("b", 20, 30, SequenceOccurrenceId("b"))),
            )
        val before = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertThrows(ConcurrentModificationException::class.java) {
            repository.removeOccurrenceHistory(
                SEQUENCE_ID,
                valid.copy(expectedUpdatedAt = second(29)),
                second(40),
            )
        }
        assertEquals(before, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))

        database.activeSessionDao().insert(
            ActiveSession(ActiveSessionKind.SEQUENCE, ActiveSessionState.RUNNING, null, SEQUENCE_ID, second(30)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            repository.removeOccurrenceHistory(SEQUENCE_ID, valid, second(40))
        }
        assertEquals(before, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertNull(database.activityExecutionDao().getById("child-a")?.deletedAtMs)
    }

    @Test
    fun closeGapMovesChildStatisticsDateWhileRootDailyDateStaysFixed() {
        val start = Instant.parse("2024-01-01T23:59:55Z")
        shiftBaseGraph(start, ZoneOffset.UTC)
        val oldChildDate = LocalDate.parse("2024-01-02")
        val rootDate = LocalDate.parse("2024-01-01")
        val statistics = StatisticsRepository(database) { StatisticsSeriesId("unused") }
        assertEquals(
            1L,
            statistics
                .activitySeries(
                    StatisticsSeriesId("activity-series"),
                    StatisticsPeriod.Day(oldChildDate),
                ).executionCount,
        )

        repository.removeOccurrenceHistory(
            SEQUENCE_ID,
            SequenceHistoryStructuralRemovalCommand(
                detailToken(),
                SequenceOccurrenceId("a"),
                ActivityExecutionId("child-a"),
                SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                start.plusSeconds(10),
                listOf(
                    SequenceInterval(
                        SequenceIntervalId("b"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        start,
                        start.plusSeconds(10),
                        SequenceOccurrenceId("b"),
                    ),
                ),
                occurrenceTimings =
                    listOf(
                        SequenceOccurrenceTimingCorrection(
                            SequenceOccurrenceId("b"),
                            start,
                            start.plusSeconds(10),
                        ),
                    ),
                childTimings =
                    listOf(
                        SequenceStructuralChildTimingCorrection(
                            ActivityExecutionId("child-b"),
                            ActivityHistoryTimeCorrection.Timed(start, start.plusSeconds(10)),
                            emptyList(),
                        ),
                    ),
            ),
            start.plusSeconds(30),
        )

        assertEquals(
            0L,
            statistics
                .activitySeries(
                    StatisticsSeriesId("activity-series"),
                    StatisticsPeriod.Day(oldChildDate),
                ).executionCount,
        )
        assertEquals(
            1L,
            statistics
                .activitySeries(
                    StatisticsSeriesId("activity-series"),
                    StatisticsPeriod.Day(rootDate),
                ).executionCount,
        )
        assertEquals(rootDate.toString(), database.sequenceExecutionDao().getById(SEQUENCE_ID.value)?.primaryLocalDate)
        assertTrue(
            HistoryReadRepository(database).roots(rootDate).any {
                it is CompletedSequenceHistoryRoot && it.executionId == SEQUENCE_ID
            },
        )
        assertFalse(
            HistoryReadRepository(database).roots(oldChildDate).any {
                it is CompletedSequenceHistoryRoot && it.executionId == SEQUENCE_ID
            },
        )
    }

    @Test
    fun structuralPlanFailureRollsBackRootOccurrenceChildAndIntervals() {
        val plan = linkFulfilledPlan("structural-rollback-plan")
        val beforeRoot = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        val beforeChildren = database.activityExecutionDao().getSequenceChildAggregates(SEQUENCE_ID.value)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_structural_plan BEFORE UPDATE OF fulfilled_at_ms ON plan_entries " +
                "BEGIN SELECT RAISE(ABORT, 'forced plan failure'); END",
        )
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            repository.removeOccurrenceHistory(
                SEQUENCE_ID,
                SequenceHistoryStructuralRemovalCommand(
                    detailToken(),
                    SequenceOccurrenceId("a"),
                    ActivityExecutionId("child-a"),
                    SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                    second(20),
                    listOf(interval("b", 10, 20, SequenceOccurrenceId("b"))),
                    occurrenceTimings =
                        listOf(
                            SequenceOccurrenceTimingCorrection(SequenceOccurrenceId("b"), second(10), second(20)),
                        ),
                    childTimings =
                        listOf(
                            SequenceStructuralChildTimingCorrection(
                                ActivityExecutionId("child-b"),
                                ActivityHistoryTimeCorrection.Timed(second(10), second(20)),
                                emptyList(),
                            ),
                        ),
                ),
                second(40),
            )
        }
        assertEquals(beforeRoot, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertEquals(beforeChildren, database.activityExecutionDao().getSequenceChildAggregates(SEQUENCE_ID.value))
        assertEquals(plan, database.planEntryDao().getById(plan.id))
    }

    @Test
    fun structuralGraphLoadingUsesBatchedOwnerAndSnapshotQueries() {
        val token = detailToken()
        observedSql.clear()
        repository.removeOccurrenceHistory(
            SEQUENCE_ID,
            SequenceHistoryStructuralRemovalCommand(
                token,
                SequenceOccurrenceId("a"),
                ActivityExecutionId("child-a"),
                SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
                second(30),
                listOf(interval("b", 20, 30, SequenceOccurrenceId("b"))),
            ),
            second(40),
        )
        val queries = synchronized(observedSql) { observedSql.map(String::lowercase) }
        assertEquals(1, queries.count { "from activity_executions" in it && "sequence_execution_id = ?" in it })
        assertEquals(1, queries.count { "from activity_execution_pauses" in it && " in (" in it })
        assertEquals(1, queries.count { "from activity_execution_field_values" in it && " in (" in it })
        assertEquals(1, queries.count { it.startsWith("select * from activity_snapshots where id in") })
        assertFalse(queries.any { it.startsWith("select * from activity_snapshots where id =") })
    }

    @Test
    fun closeGapPersistsCountdownForLaterNotStartedOwnerWithoutFabricatingChildFacts() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_snapshot_settings SET before_each_step_countdown_ms = 5000 " +
                "WHERE sequence_snapshot_id = ?",
            arrayOf(SNAPSHOT_ID.value),
        )
        database.openHelper.writableDatabase.execSQL("DELETE FROM activity_executions WHERE id = 'child-b'")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_occurrences SET status = 'NOT_STARTED', entered_at_ms = NULL, " +
                "completed_at_ms = NULL, completion_reason = NULL WHERE id = 'b'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_intervals SET kind = 'TRANSITION_COUNTDOWN', ended_at_ms = 25000 WHERE id = 'b'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_executions SET status = 'ENDED_EARLY', ended_at_ms = 25000, active_duration_ms = 10000, " +
                "pause_duration_ms = 5000, wall_duration_ms = 15000 WHERE id = ?",
            arrayOf(SEQUENCE_ID.value),
        )

        repository.removeOccurrenceHistory(
            SEQUENCE_ID,
            SequenceHistoryStructuralRemovalCommand(
                detailToken(),
                SequenceOccurrenceId("a"),
                ActivityExecutionId("child-a"),
                SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                second(15),
                listOf(
                    SequenceInterval(
                        SequenceIntervalId("b"),
                        SequenceIntervalKind.TRANSITION_COUNTDOWN,
                        second(10),
                        second(15),
                        SequenceOccurrenceId("b"),
                    ),
                ),
            ),
            second(40),
        )

        val durable = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        val notStarted = durable.occurrences.single { it.id == "b" }
        assertEquals("NOT_STARTED", notStarted.status)
        assertNull(notStarted.enteredAtMs)
        assertNull(notStarted.completedAtMs)
        assertNull(database.activityExecutionDao().getAggregateByOccurrence("b"))
        assertEquals(
            SequenceIntervalEntity("b", SEQUENCE_ID.value, "TRANSITION_COUNTDOWN", 10_000, 15_000, "b"),
            durable.intervals.single(),
        )
        val detail = requireNotNull(HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID))
        assertEquals(RuntimeOccurrenceStatus.NOT_STARTED, detail.occurrences.single().status)
        assertNull(detail.occurrences.single().child)
    }

    @Test
    fun closeGapPersistsJumpCountdownForLaterSkippedOwnerWithoutFabricatingChildFacts() {
        seedSkippedCountdownGraph()
        repository.removeOccurrenceHistory(
            SKIPPED_COUNTDOWN_SEQUENCE_ID,
            SequenceHistoryStructuralRemovalCommand(
                detailToken(SKIPPED_COUNTDOWN_SEQUENCE_ID),
                SequenceOccurrenceId("jump-a"),
                ActivityExecutionId("jump-child-a"),
                SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                second(20),
                listOf(
                    SequenceInterval(
                        SequenceIntervalId("jump-countdown-b"),
                        SequenceIntervalKind.TRANSITION_COUNTDOWN,
                        second(10),
                        second(12),
                        SequenceOccurrenceId("jump-b"),
                    ),
                    interval("jump-c", 12, 20, SequenceOccurrenceId("jump-c")),
                ),
                occurrenceTimings =
                    listOf(
                        SequenceOccurrenceTimingCorrection(
                            SequenceOccurrenceId("jump-c"),
                            second(12),
                            second(20),
                        ),
                    ),
                childTimings =
                    listOf(
                        SequenceStructuralChildTimingCorrection(
                            ActivityExecutionId("jump-child-c"),
                            ActivityHistoryTimeCorrection.Timed(second(12), second(20)),
                            emptyList(),
                        ),
                    ),
            ),
            second(40),
        )

        val durable =
            requireNotNull(
                database.sequenceExecutionDao().getHistoryAggregate(SKIPPED_COUNTDOWN_SEQUENCE_ID.value),
            )
        val skipped = durable.occurrences.single { it.id == "jump-b" }
        assertEquals("SKIPPED", skipped.status)
        assertNull(skipped.enteredAtMs)
        assertNull(skipped.completedAtMs)
        assertNull(database.activityExecutionDao().getAggregateByOccurrence("jump-b"))
        assertEquals(
            SequenceIntervalEntity(
                "jump-countdown-b",
                SKIPPED_COUNTDOWN_SEQUENCE_ID.value,
                "TRANSITION_COUNTDOWN",
                10_000,
                12_000,
                "jump-b",
            ),
            durable.intervals.single { it.id == "jump-countdown-b" },
        )
        val detail =
            requireNotNull(HistoryReadRepository(database).getSequenceDetail(SKIPPED_COUNTDOWN_SEQUENCE_ID))
        assertEquals(
            RuntimeOccurrenceStatus.SKIPPED,
            detail.occurrences.single { it.occurrenceId.value == "jump-b" }.status,
        )
        assertNull(detail.occurrences.single { it.occurrenceId.value == "jump-b" }.child)
    }

    @Test
    fun deleteChildHistoryPersistsCanonicalTombstoneWithoutChangingSequenceOrPlanFacts() {
        val plan = linkFulfilledPlan("deletion-plan")
        val history = HistoryReadRepository(database)
        val beforeDetail = requireNotNull(history.getSequenceDetail(SEQUENCE_ID))
        val beforeRoot = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        val beforeChild = requireNotNull(database.activityExecutionDao().getAggregate("child-a"))
        val beforeStatistics = StatisticsRepository(database) { StatisticsSeriesId("unused") }
        assertEquals(
            2L,
            beforeStatistics
                .activitySeries(
                    StatisticsSeriesId("activity-series"),
                    StatisticsPeriod.AllTime,
                ).executionCount,
        )

        val result =
            repository.deleteChildHistory(
                SEQUENCE_ID,
                SequenceChildHistoryDeletionCommand(
                    beforeDetail.updatedAt.plusNanos(999_999),
                    SequenceOccurrenceId("a"),
                    ActivityExecutionId("child-a"),
                ),
                second(40).plusNanos(999_999),
            )

        val afterRoot = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        val afterChild = requireNotNull(database.activityExecutionDao().getAggregate("child-a"))
        assertEquals(beforeRoot.execution.copy(updatedAtMs = 40_000), afterRoot.execution)
        assertEquals(
            beforeRoot.occurrences.map {
                if (it.id == "a") it.copy(status = "DELETED_EXECUTION") else it
            },
            afterRoot.occurrences,
        )
        assertEquals(beforeRoot.intervals, afterRoot.intervals)
        assertEquals(beforeRoot.values, afterRoot.values)
        assertEquals(
            beforeChild.execution.copy(deletedAtMs = 40_000, updatedAtMs = 40_000),
            afterChild.execution,
        )
        assertEquals(beforeChild.pauses, afterChild.pauses)
        assertEquals(beforeChild.values, afterChild.values)
        assertEquals(plan, database.planEntryDao().getById(plan.id))
        assertEquals(second(40), result.execution.updatedAt)
        assertEquals(second(40), result.child.deletedAt)

        val detail = requireNotNull(history.getSequenceDetail(SEQUENCE_ID))
        val tombstone = detail.occurrences.single { it.occurrenceId.value == "a" }
        assertEquals(RuntimeOccurrenceStatus.DELETED_EXECUTION, tombstone.status)
        assertNull(tombstone.child)
        assertEquals(beforeDetail.occurrences.first().activity, tombstone.activity)
        assertTrue(detail.occurrences.single { it.occurrenceId.value == "b" }.child != null)
        assertEquals(beforeDetail.intervals, detail.intervals)
        assertEquals(beforeDetail.root, detail.root)

        val activity = beforeStatistics.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime)
        val sequence = beforeStatistics.sequenceSeries(StatisticsSeriesId("sequence-series"), StatisticsPeriod.AllTime)
        val global = beforeStatistics.global(StatisticsPeriod.AllTime)
        assertEquals(1L, activity.executionCount)
        assertEquals(Duration.ofSeconds(10), activity.durations.total)
        assertEquals(1L, sequence.executionCount)
        assertEquals(Duration.ofSeconds(20), sequence.activeDurations.total)
        assertEquals(Duration.ofSeconds(20), global.totalTrackedDuration)
        assertEquals(1L, global.topLevelExecutionCount)

        assertThrows(IllegalArgumentException::class.java) {
            repository.deleteChildHistory(
                SEQUENCE_ID,
                SequenceChildHistoryDeletionCommand(
                    detail.updatedAt,
                    tombstone.occurrenceId,
                    ActivityExecutionId("child-a"),
                ),
                second(50),
            )
        }
    }

    @Test
    fun deletionPreservesChildPausesAndValuesAndSequenceTimeline() {
        seedPausedGraph()
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO activity_snapshot_fields " +
                "(id, snapshot_id, source_field_id, position, name_at_creation, local_name_override, " +
                "field_type, unit, " +
                "display_precision, default_number_scaled, default_category_option_id, default_text, is_main_value) " +
                "VALUES ('paused-value-field', 'activity-a', NULL, 0, 'Value', NULL, 'NUMBER', NULL, " +
                "0, NULL, NULL, NULL, 0)",
        )
        database.activityExecutionDao().upsertValue(
            ActivityExecutionFieldValueEntity("paused-child-a", "paused-value-field", 7, null, null),
        )
        val beforeRoot = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(PAUSED_SEQUENCE_ID.value))
        val beforeChild = requireNotNull(database.activityExecutionDao().getAggregate("paused-child-a"))

        repository.deleteChildHistory(
            PAUSED_SEQUENCE_ID,
            SequenceChildHistoryDeletionCommand(
                Instant.ofEpochMilli(beforeRoot.execution.updatedAtMs),
                SequenceOccurrenceId("paused-a"),
                ActivityExecutionId("paused-child-a"),
            ),
            second(40),
        )

        val afterRoot = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(PAUSED_SEQUENCE_ID.value))
        val afterChild = requireNotNull(database.activityExecutionDao().getAggregate("paused-child-a"))
        assertEquals(beforeRoot.intervals, afterRoot.intervals)
        assertEquals(beforeRoot.execution.activeDurationMs, afterRoot.execution.activeDurationMs)
        assertEquals(beforeRoot.execution.pauseDurationMs, afterRoot.execution.pauseDurationMs)
        assertEquals(beforeRoot.execution.wallDurationMs, afterRoot.execution.wallDurationMs)
        assertEquals(beforeChild.pauses, afterChild.pauses)
        assertEquals(beforeChild.values, afterChild.values)
        assertEquals(40_000L, afterChild.execution.deletedAtMs)
    }

    @Test
    fun endedEarlyNoLiveChildDeletionKeepsDurationMissing() {
        seedNoLiveGraph()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_executions SET status = 'ENDED_EARLY' WHERE id = ?",
            arrayOf(NO_LIVE_SEQUENCE_ID.value),
        )
        val before = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(NO_LIVE_SEQUENCE_ID.value))

        repository.deleteChildHistory(
            NO_LIVE_SEQUENCE_ID,
            SequenceChildHistoryDeletionCommand(
                Instant.ofEpochMilli(before.execution.updatedAtMs),
                SequenceOccurrenceId("no-live-occurrence"),
                ActivityExecutionId("no-live-child"),
            ),
            second(40),
        )

        val child = requireNotNull(database.activityExecutionDao().getById("no-live-child"))
        val detail = requireNotNull(HistoryReadRepository(database).getSequenceDetail(NO_LIVE_SEQUENCE_ID))
        assertNull(child.startedAtMs)
        assertNull(child.activeDurationMs)
        assertEquals(20_000L, child.completedAtMs)
        assertEquals(40_000L, child.deletedAtMs)
        assertEquals(RuntimeOccurrenceStatus.DELETED_EXECUTION, detail.occurrences.single().status)
        assertNull(detail.occurrences.single().child)
    }

    @Test
    fun invalidChildDeletionTargetsAndStatesFailWithoutWrites() {
        val beforeRoot = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        val beforeChildren = database.activityExecutionDao().getSequenceChildAggregates(SEQUENCE_ID.value)
        val valid =
            SequenceChildHistoryDeletionCommand(second(30), SequenceOccurrenceId("a"), ActivityExecutionId("child-a"))
        assertThrows(ConcurrentModificationException::class.java) {
            repository.deleteChildHistory(SEQUENCE_ID, valid.copy(expectedUpdatedAt = second(29)), second(40))
        }
        listOf(
            valid.copy(occurrenceId = SequenceOccurrenceId("missing")),
            valid.copy(childExecutionId = ActivityExecutionId("missing")),
            valid.copy(occurrenceId = SequenceOccurrenceId("b")),
        ).forEach { command ->
            assertThrows(IllegalArgumentException::class.java) {
                repository.deleteChildHistory(SEQUENCE_ID, command, second(40))
            }
        }

        seedPausedGraph()
        assertThrows(IllegalArgumentException::class.java) {
            repository.deleteChildHistory(
                SEQUENCE_ID,
                valid.copy(childExecutionId = ActivityExecutionId("paused-child-a")),
                second(40),
            )
        }
        assertEquals(beforeRoot, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertEquals(beforeChildren, database.activityExecutionDao().getSequenceChildAggregates(SEQUENCE_ID.value))
    }

    @Test
    fun persistedTargetIntegrityMismatchesFailWithoutFurtherWrites() {
        val command =
            SequenceChildHistoryDeletionCommand(second(30), SequenceOccurrenceId("a"), ActivityExecutionId("child-a"))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_executions SET snapshot_id = 'activity-b' WHERE id = 'child-a'",
        )
        val mismatchedSnapshot = database.activityExecutionDao().getSequenceChildAggregates(SEQUENCE_ID.value)
        assertThrows(IllegalArgumentException::class.java) {
            repository.deleteChildHistory(SEQUENCE_ID, command, second(40))
        }
        assertEquals(mismatchedSnapshot, database.activityExecutionDao().getSequenceChildAggregates(SEQUENCE_ID.value))

        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_executions SET snapshot_id = 'activity-a' WHERE id = 'child-a'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_occurrences SET status = 'SKIPPED', entered_at_ms = NULL, completed_at_ms = NULL, " +
                "completion_reason = NULL WHERE id = 'a'",
        )
        val nonCompleted = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertThrows(IllegalArgumentException::class.java) {
            repository.deleteChildHistory(SEQUENCE_ID, command, second(40))
        }
        assertEquals(nonCompleted, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertNull(database.activityExecutionDao().getById("child-a")?.deletedAtMs)
    }

    @Test
    fun liveAndActiveSessionOwnedRootsRejectChildDeletionWithoutWrites() {
        val command =
            SequenceChildHistoryDeletionCommand(second(30), SequenceOccurrenceId("a"), ActivityExecutionId("child-a"))
        listOf("RUNNING", "PAUSED").forEach { status ->
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sequence_executions SET status = ?, ended_at_ms = NULL, active_duration_ms = NULL, " +
                    "pause_duration_ms = NULL, wall_duration_ms = NULL WHERE id = ?",
                arrayOf(status, SEQUENCE_ID.value),
            )
            val before = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
            assertThrows(IllegalArgumentException::class.java) {
                repository.deleteChildHistory(SEQUENCE_ID, command, second(40))
            }
            assertEquals(before, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        }
        restoreBaseTerminalRoot()
        database.activeSessionDao().insert(
            ActiveSession(ActiveSessionKind.SEQUENCE, ActiveSessionState.RUNNING, null, SEQUENCE_ID, second(30)),
        )
        val before = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertThrows(IllegalArgumentException::class.java) {
            repository.deleteChildHistory(SEQUENCE_ID, command, second(40))
        }
        assertEquals(before, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
    }

    @Test
    fun childDeletionRollsBackRootAndOccurrenceWhenChildWriteFails() {
        val rootBefore = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        val childBefore = requireNotNull(database.activityExecutionDao().getAggregate("child-a"))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_child_history_delete BEFORE UPDATE OF deleted_at_ms ON activity_executions " +
                "BEGIN SELECT RAISE(ABORT, 'forced child deletion failure'); END",
        )

        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            repository.deleteChildHistory(
                SEQUENCE_ID,
                SequenceChildHistoryDeletionCommand(
                    second(30),
                    SequenceOccurrenceId("a"),
                    ActivityExecutionId("child-a"),
                ),
                second(40),
            )
        }

        assertEquals(rootBefore, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertEquals(childBefore, database.activityExecutionDao().getAggregate("child-a"))
    }

    @Test
    fun incoherentFulfilledPlanRejectsChildDeletionWithoutWrites() {
        val plan = linkFulfilledPlan("incoherent-deletion-plan")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE plan_entries SET fulfilled_at_ms = 29000 WHERE id = ?",
            arrayOf(plan.id),
        )
        val rootBefore = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        val childBefore = requireNotNull(database.activityExecutionDao().getAggregate("child-a"))

        assertThrows(IllegalArgumentException::class.java) {
            repository.deleteChildHistory(
                SEQUENCE_ID,
                SequenceChildHistoryDeletionCommand(
                    second(30),
                    SequenceOccurrenceId("a"),
                    ActivityExecutionId("child-a"),
                ),
                second(40),
            )
        }

        assertEquals(rootBefore, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertEquals(childBefore, database.activityExecutionDao().getAggregate("child-a"))
    }

    @Test
    fun rootTimingCorrectionAfterDeletionRetainsTombstoneAndChildTimingCorrectionRejectsIt() {
        repository.deleteChildHistory(
            SEQUENCE_ID,
            SequenceChildHistoryDeletionCommand(second(30), SequenceOccurrenceId("a"), ActivityExecutionId("child-a")),
            second(40),
        )
        repository.correctTiming(
            SEQUENCE_ID,
            SequenceHistoryTimingCorrection(second(40), startedAt = second(9)),
            second(50),
        )
        val afterRootCorrection = requireNotNull(HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID))
        val deletedChild = requireNotNull(database.activityExecutionDao().getById("child-a"))
        assertEquals(second(50), afterRootCorrection.updatedAt)
        assertEquals(RuntimeOccurrenceStatus.DELETED_EXECUTION, afterRootCorrection.occurrences.first().status)
        assertNull(afterRootCorrection.occurrences.first().child)
        assertEquals(40_000L, deletedChild.deletedAtMs)
        assertEquals(40_000L, deletedChild.updatedAtMs)

        val before = requireNotNull(database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
        assertThrows(IllegalArgumentException::class.java) {
            repository.correctTiming(
                SEQUENCE_ID,
                SequenceHistoryTimingCorrection(
                    afterRootCorrection.updatedAt,
                    childTimings =
                        listOf(
                            SequenceChildTimingCorrection(
                                ActivityExecutionId("child-a"),
                                ActivityHistoryTimeCorrection.Timed(second(10), second(20)),
                            ),
                        ),
                ),
                second(60),
            )
        }
        assertEquals(before, database.sequenceExecutionDao().getHistoryAggregate(SEQUENCE_ID.value))
    }

    @Test
    fun readerRejectsMismatchedCompletedAndTombstoneChildStates() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_executions SET deleted_at_ms = 40000, updated_at_ms = 40000 WHERE id = 'child-a'",
        )
        assertThrows(IllegalArgumentException::class.java) {
            HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID)
        }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_executions SET deleted_at_ms = NULL, updated_at_ms = 20000 WHERE id = 'child-a'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_occurrences SET status = 'DELETED_EXECUTION' WHERE id = 'a'",
        )
        assertThrows(IllegalArgumentException::class.java) {
            HistoryReadRepository(database).getSequenceDetail(SEQUENCE_ID)
        }
    }

    @Test
    fun deletionGraphLoadingRemainsBatched() {
        observedSql.clear()
        repository.deleteChildHistory(
            SEQUENCE_ID,
            SequenceChildHistoryDeletionCommand(second(30), SequenceOccurrenceId("a"), ActivityExecutionId("child-a")),
            second(40),
        )
        val queries = synchronized(observedSql) { observedSql.map(String::lowercase) }
        assertEquals(1, queries.count { "from activity_executions" in it && "sequence_execution_id = ?" in it })
        assertEquals(1, queries.count { "from activity_execution_pauses" in it && " in (" in it })
        assertEquals(1, queries.count { "from activity_execution_field_values" in it && " in (" in it })
        assertEquals(1, queries.count { it.startsWith("select * from activity_snapshots where id in") })
        assertFalse(queries.any { it.startsWith("select * from activity_snapshots where id =") })
    }

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

    private fun seedPausedLaterChildGraph() {
        seedPausedGraph()
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM sequence_intervals WHERE id = 'paused-b'",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sequence_intervals " +
                "(id, sequence_execution_id, kind, started_at_ms, ended_at_ms, occurrence_id) VALUES " +
                "('paused-b-1', 'paused-sequence', 'ACTIVE_STEP', 20000, 22000, 'paused-b'), " +
                "('paused-b-2', 'paused-sequence', 'ACTIVE_STEP', 24000, 30000, 'paused-b')",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO activity_execution_pauses " +
                "(id, activity_execution_id, started_at_ms, ended_at_ms) " +
                "VALUES ('paused-b-pause', 'paused-child-b', 22000, 24000)",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_executions SET active_duration_ms = 8000 WHERE id = 'paused-child-b'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_executions SET active_duration_ms = 16000, pause_duration_ms = 4000 " +
                "WHERE id = 'paused-sequence'",
        )
    }

    private fun closePausedLaterChildCommand() =
        SequenceHistoryStructuralRemovalCommand(
            detailToken(PAUSED_SEQUENCE_ID),
            SequenceOccurrenceId("paused-a"),
            ActivityExecutionId("paused-child-a"),
            SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
            second(20),
            listOf(
                interval("paused-b-1", 10, 12, SequenceOccurrenceId("paused-b")),
                interval("paused-b-2", 14, 20, SequenceOccurrenceId("paused-b")),
            ),
            occurrenceTimings =
                listOf(
                    SequenceOccurrenceTimingCorrection(SequenceOccurrenceId("paused-b"), second(10), second(20)),
                ),
            childTimings =
                listOf(
                    SequenceStructuralChildTimingCorrection(
                        ActivityExecutionId("paused-child-b"),
                        ActivityHistoryTimeCorrection.Timed(second(10), second(20)),
                        listOf(
                            ActivityExecutionPause(ActivityExecutionPauseId("paused-b-pause"), second(12), second(14)),
                        ),
                    ),
                ),
        )

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

    private fun seedSkippedCountdownGraph() {
        val fixtures = LiveRuntimeTestFixtures(database)
        fixtures.activity("activity-c", "STOPWATCH")
        fixtures.sequence(
            SKIPPED_COUNTDOWN_SNAPSHOT_ID.value,
            listOf("activity-a", "activity-b", "activity-c"),
            countdownMs = 5_000,
        )
        val occurrenceA = occurrence("jump-a", "activity-a", 0, 10, 20, SKIPPED_COUNTDOWN_SNAPSHOT_ID)
        val occurrenceB =
            occurrence("jump-b", "activity-b", 1, 20, 22, SKIPPED_COUNTDOWN_SNAPSHOT_ID).copy(
                status = RuntimeOccurrenceStatus.SKIPPED,
                enteredAt = null,
                completedAt = null,
                completionReason = null,
            )
        val occurrenceC = occurrence("jump-c", "activity-c", 2, 22, 30, SKIPPED_COUNTDOWN_SNAPSHOT_ID)
        val intervals =
            listOf(
                interval("jump-a", 10, 20, occurrenceA.id),
                SequenceInterval(
                    SequenceIntervalId("jump-countdown-b"),
                    SequenceIntervalKind.TRANSITION_COUNTDOWN,
                    second(20),
                    second(22),
                    occurrenceB.id,
                ),
                interval("jump-c", 22, 30, occurrenceC.id),
            )
        val durations =
            com.alexandr5476.lifetracing.domain.SequenceTimelineCalculator.calculate(
                second(10),
                second(30),
                intervals,
            )
        database.sequenceExecutionDao().insertAggregate(
            SequenceExecution(
                SKIPPED_COUNTDOWN_SEQUENCE_ID,
                SKIPPED_COUNTDOWN_SNAPSHOT_ID,
                StatisticsSeriesId("sequence-series"),
                SequenceExecutionStatus.COMPLETED,
                second(10),
                second(30),
                durations.active,
                durations.pause,
                durations.wall,
                ZoneOffset.UTC,
                0,
                LocalDate.ofEpochDay(0),
                null,
                second(10),
                second(30),
                listOf(occurrenceA, occurrenceB, occurrenceC),
                intervals,
            ).toEntityAggregate(),
        )
        database.activityExecutionDao().insertAggregate(
            child(
                "jump-child-a",
                "activity-a",
                occurrenceA.id,
                10,
                20,
                SKIPPED_COUNTDOWN_SEQUENCE_ID,
            ).toEntityAggregate(),
        )
        database.activityExecutionDao().insertAggregate(
            child(
                "jump-child-c",
                "activity-c",
                occurrenceC.id,
                22,
                30,
                SKIPPED_COUNTDOWN_SEQUENCE_ID,
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
        val SKIPPED_COUNTDOWN_SEQUENCE_ID = SequenceExecutionId("skipped-countdown-sequence")
        val SKIPPED_COUNTDOWN_SNAPSHOT_ID = SequenceSnapshotId("skipped-countdown-snapshot")
    }
}
