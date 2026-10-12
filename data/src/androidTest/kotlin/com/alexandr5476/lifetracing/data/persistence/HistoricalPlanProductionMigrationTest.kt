@file:Suppress("TooManyFunctions", "LargeClass", "LongMethod", "MaxLineLength")
// S4: authentic physical v9 sources, registered production migration, then cold canonical hydration.

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceState
import com.alexandr5476.lifetracing.domain.ActiveSequenceStateResolver
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.CancelledPlanPageQuery
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyPlanSnapshot
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanEntryValidator
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryActualValue
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFactory
import com.alexandr5476.lifetracing.domain.SequenceSnapshotRepeatBlock
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.domain.actionIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class HistoricalPlanProductionMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "s4-plan-v9-" + System.nanoTime()
    private var reference: LifeTracingDatabase? = null
    private var liveReference: LiveSessionRepository? = null
    private val database get() = requireNotNull(reference)
    private val live get() = requireNotNull(liveReference)
    private var original = emptyMap<String, List<List<String?>>>()
    private val origin = Instant.parse("2020-01-01T22:30:00.123Z")
    private val historyDate = LocalDate.of(2020, 1, 2)
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val activitySeries = StatisticsSeriesId("activity-series")
    private val sequenceSeries = StatisticsSeriesId("sequence-series")

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeTracingDatabase::class.java)

    @After
    fun cleanUp() {
        close()
        context.deleteDatabase(name)
    }

    @Test
    fun scheduledAndCancelledV9PlansKeepFrozenFactsAcrossDstPlacementAndUntouchedCommands() {
        val day = LocalDate.of(2020, 10, 25)
        val monday = LocalDate.of(2020, 10, 19)
        // Both occurrences of Berlin's repeated 02:30, with independently fixed seconds/milliseconds.
        val first = Instant.parse("2020-10-25T00:30:45.123Z")
        val second = Instant.parse("2020-10-25T01:30:45.987Z")
        val expected =
            listOf(
                historicalPlanV9("floating", PlanTarget.FloatingDay(day)),
                historicalPlanV9("exact-zone", PlanTarget.ExactDay(first, ZoneId.of("Europe/Berlin"))),
                historicalPlanV9("exact-legacy", PlanTarget.ExactDay(second, null)),
                historicalPlanV9("week", PlanTarget.Week(monday), sequence = true),
                historicalPlanV9("month", PlanTarget.Month(YearMonth.of(2020, 10))),
                historicalPlanV9("cancelled-day", PlanTarget.FloatingDay(day), status = PlanEntryStatus.CANCELLED),
                historicalPlanV9(
                    "cancelled-week",
                    PlanTarget.Week(monday),
                    sequence = true,
                    status = PlanEntryStatus.CANCELLED,
                ),
            )
        migrate {
            seedFrozenActivityV3()
            seedFrozenSequenceV6()
            execSQL("UPDATE activity_templates SET deleted_at_ms = 21 WHERE id = 'activity-template'")
            expected.forEach(::seedHistoricalPlanV9)
        }
        repeat(2) { _ ->
            assertPlans(expected)
            val library = LibraryRepository.create(database)
            assertEquals(listOf("Current activity"), library.getArchived().map { it.name })
            assertEquals(listOf("Current sequence"), library.getAll().map { it.name })
            assertEquals(
                9L,
                database
                    .activityTemplateDao()
                    .getAggregate("activity-template")!!
                    .toDomain()
                    .revision,
            )
            assertEquals(
                8L,
                database
                    .sequenceTemplateDao()
                    .getAggregate("sequence-template")!!
                    .toDomain()
                    .revision,
            )
            val berlin = ZoneId.of("Europe/Berlin")
            for ((zone, placement) in listOf(
                berlin to day,
                ZoneId.of("America/Los_Angeles") to day.minusDays(1),
                tokyo to day,
            )) {
                val provider = CurrentZoneIdProvider { zone }
                val reads = PlanReadRepository(database, provider)
                val now = Instant.parse("2020-11-01T12:00:00Z")
                val week = reads.getWeek(WeekPlanQuery(monday, placement, now))
                assertEquals(
                    listOf("exact-zone", "exact-legacy"),
                    week.selectedDayPlans.take(2).map { it.plan.id.value },
                )
                assertEquals(if (placement == day) 3 else 2, week.selectedDayPlans.size)
                assertEquals(listOf(expected[3]), week.weekPlans.map { it.plan })
                assertEquals(3, week.dayPresence.sumOf { it.count })
                week.selectedDayPlans.forEach { row ->
                    assertEquals(PlanSourceState.ARCHIVED, row.sourceState)
                    assertEquals("Frozen activity", row.title)
                    assertTrue(row.overdue)
                    assertFalse(row.engaged)
                }
                assertEquals(PlanSourceState.CHANGED, week.weekPlans.single().sourceState)
                val daily = DailyReadRepository(database, provider).getDaily(DailyQuery(placement, now, 10))
                assertEquals(week.selectedDayPlans.map { it.plan }, daily.dayPlans.map { it.plan })
                assertEquals(listOf(expected[3]), daily.weekPlans.map { it.plan })
                assertNull(daily.active)
                assertTrue(daily.completedHistory.isEmpty())
                val activity = (daily.dayPlans.first().snapshot as DailyPlanSnapshot.Activity).value
                assertFrozenActivity(activity)
                val cancelled = reads.getCancelledPage(CancelledPlanPageQuery(0, 10))
                assertEquals(setOf(expected[5], expected[6]), cancelled.items.map { it.plan }.toSet())
                assertFalse(cancelled.hasNextPage)
                assertTrue(cancelled.items.none { it.overdue || it.engaged })
                if (zone == berlin) {
                    assertEquals(
                        listOf(
                            LocalTime.parse("02:30:45.123"),
                            LocalTime.parse("02:30:45.987"),
                        ),
                        week.selectedDayPlans.take(2).map {
                            it.exactLocalTime
                        },
                    )
                    assertEquals(
                        listOf(
                            ZoneOffset.ofHours(2),
                            ZoneOffset.ofHours(1),
                        ),
                        week.selectedDayPlans.take(2).map {
                            it.exactUtcOffset
                        },
                    )
                }
                for (plan in expected.filter { it.target is PlanTarget.ExactDay }) {
                    assertEquals(plan, plans(zone).preservePlanSchedule(reads.getFocusedAction(plan.id).identity, now))
                }
            }
            val reads = PlanReadRepository(database, CurrentZoneIdProvider { tokyo })
            val sequence =
                (
                    reads
                        .getFocusedAction(
                            expected[3].id,
                        ).snapshot as FocusedPlanAction.Snapshot.Sequence
                ).value
            assertEquals(2L, sequence.sourceRevision)
            val repeat = sequence.nodes[1] as SequenceSnapshotRepeatBlock
            assertEquals(2, repeat.repeatCount)
            assertEquals("f-repeat", repeat.id.value)
            assertEquals(
                "f-nested",
                repeat.children
                    .single()
                    .id.value,
            )
            assertEquals(
                Duration.ZERO,
                repeat.children
                    .single()
                    .overrides.startCountdown,
            )
            assertThrows(IllegalArgumentException::class.java) { reads.getFocusedAction(expected[4].id) }
            assertEquals(0L, statistics().global(StatisticsPeriod.AllTime).topLevelExecutionCount)
            assertOriginalFacts()
            reopen()
        }
    }

    @Test
    fun fulfilledV9ActivityAndSequencePlansKeepReciprocalHistoryAndRootAccountingAfterReopen() {
        val dayTarget = PlanTarget.FloatingDay(historyDate)
        val weekTarget = PlanTarget.Week(LocalDate.of(2019, 12, 30))
        val timed =
            historicalPlanV9(
                "timed-plan",
                dayTarget,
                status = PlanEntryStatus.FULFILLED,
                execution = "timed",
                fulfilledAt = origin.plusSeconds(60),
            )
        val noLive =
            historicalPlanV9(
                "no-live-plan",
                weekTarget,
                snapshot = "no-live-snapshot",
                status = PlanEntryStatus.FULFILLED,
                execution = "no-live",
                fulfilledAt = origin.plusSeconds(180),
            )
        val deleted =
            historicalPlanV9(
                "deleted-plan",
                dayTarget,
                status = PlanEntryStatus.FULFILLED,
                execution = "zero",
                fulfilledAt = origin.plusSeconds(120),
            )
        val sequence =
            historicalPlanV9(
                "sequence-plan",
                weekTarget,
                sequence = true,
                status = PlanEntryStatus.FULFILLED,
                execution = "sequence-execution",
                fulfilledAt = origin.plusSeconds(110),
            )
        val unrelated = historicalPlanV9("matching-unlinked-plan", dayTarget)
        val expected = listOf(timed, noLive, deleted, sequence, unrelated)
        migrate {
            seedFrozenActivityV3()
            seedFrozenSequenceV6()
            seedStandaloneV4(origin.toEpochMilli())
            seedTerminalV7(origin.toEpochMilli())
            execSQL(
                "INSERT INTO activity_snapshot_fields VALUES ('nl-number', 'no-live-snapshot', 'number', 0, 'Captured amount', 'Local amount', 'NUMBER', 'kg', 1, 1500, NULL, NULL, 1)",
            )
            execSQL(
                "INSERT INTO activity_snapshot_fields VALUES ('nl-category', 'no-live-snapshot', 'category', 1, 'Captured effort', NULL, 'CATEGORY', NULL, NULL, NULL, 'nl-option', NULL, 0)",
            )
            execSQL(
                "INSERT INTO activity_snapshot_category_options VALUES ('nl-option', 'nl-category', 'option', 0, 'Captured option', 'Local option')",
            )
            execSQL(
                "INSERT INTO activity_execution_field_values VALUES ('no-live', 'nl-category', NULL, 'nl-option', NULL)",
            )
            expected.forEach(::seedHistoricalPlanV9)
            execSQL("UPDATE activity_executions SET plan_entry_id = 'timed-plan' WHERE id = 'timed'")
            execSQL("UPDATE activity_executions SET plan_entry_id = 'no-live-plan' WHERE id = 'no-live'")
            execSQL(
                "UPDATE activity_executions SET plan_entry_id = 'deleted-plan', deleted_at_ms = ?, updated_at_ms = ? WHERE id = 'zero'",
                arrayOf<Any>(origin.plusSeconds(200).toEpochMilli(), origin.plusSeconds(200).toEpochMilli()),
            )
            execSQL("UPDATE sequence_executions SET plan_entry_id = 'sequence-plan' WHERE id = 'sequence-execution'")
        }
        repeat(2) { _ ->
            assertPlans(expected)
            assertHistoricalFulfillment(timed, noLive, deleted, sequence)
            assertFalse(plans().isEngaged(unrelated.id))
            assertEquals(unrelated, plans().getPlan(unrelated.id))
            assertNull(live.getActiveRuntime())
            assertNull(live.getActiveSession())
            assertTrue(live.reconcileActiveSession(origin.plusSeconds(1_000)).appliedEvents.isEmpty())
            assertEquals(
                setOf(
                    "Current activity",
                    "Current sequence",
                ),
                LibraryRepository
                    .create(database)
                    .getAll()
                    .map {
                        it.name
                    }.toSet(),
            )
            assertOriginalFacts()
            reopen()
        }
    }

    @Test
    fun runningV9ActivityFulfillsOriginalPlanExactlyAtLogicalDeadline() = completeRunningActivity(40)

    @Test
    fun lateV9ActivityFulfillsOriginalPlanAtDeadlineRatherThanDelivery() = completeRunningActivity(1_000)

    @Test
    fun pausedV9ActivityStaysEngagedUntilResumeAndLogicalCompletion() {
        val fixture = recover(HistoricalActiveV8State.ACTIVITY_PAUSED)
        assertNull(NextRuntimeDeadlineResolver.resolve(live.getActiveRuntime()!!))
        assertTrue(live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.isEmpty())
        reopen()
        assertRecovered(fixture)
        live.resumeActiveActivity(fixture.at(100))
        val resumed = live.getActiveRuntime() as ActiveActivityRuntime
        assertEquals(
            fixture.standalone.pauses
                .single()
                .id,
            resumed.execution.pauses
                .single()
                .id,
        )
        assertEquals(
            fixture.at(100),
            resumed.execution.pauses
                .single()
                .endedAt,
        )
        assertEquals(fixture.at(120), NextRuntimeDeadlineResolver.resolve(resumed)?.at)
        assertBeforeDeadline(fixture, 120)
        assertEquals(
            listOf(fixture.at(120)),
            live.reconcileActiveSession(fixture.at(120)).appliedEvents.map { it.deadline.at },
        )
        assertActivityTerminal(fixture, 120)
    }

    @Test
    fun runningV9SequenceKeepsPlanPlannedUntilFinalRootCompletion() {
        val fixture = recover(HistoricalActiveV8State.SEQUENCE_RUNNING)
        assertBeforeDeadline(fixture, 70)
        assertEquals(
            listOf(fixture.at(70)),
            live.reconcileActiveSession(fixture.at(70)).appliedEvents.map { it.deadline.at },
        )
        assertEquals(PlanEntryStatus.PLANNED, plans().getPlan(PlanEntryId("active-plan"))?.status)
        assertTrue(plans().isEngaged(PlanEntryId("active-plan")))
        val transition = live.getActiveRuntime() as ActiveSequenceRuntime
        assertNull(transition.execution.currentOccurrenceId)
        assertNull(transition.currentChild)
        reopen()
        assertEquals(transition, live.getActiveRuntime())
        assertEquals(
            listOf(
                80L,
                110L,
                115L,
                145L,
            ).map(fixture::at),
            live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.map {
                it.deadline.at
            },
        )
        assertSequenceTerminal(fixture, 145)
    }

    @Test
    fun pausedV9SequenceRecoversChildPauseAndFulfillsOnlyAfterResume() {
        val fixture = recover(HistoricalActiveV8State.SEQUENCE_PAUSED)
        assertNull(NextRuntimeDeadlineResolver.resolve(live.getActiveRuntime()!!))
        assertTrue(live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.isEmpty())
        reopen()
        assertRecovered(fixture)
        live.resumeActiveSequence(fixture.at(100))
        val resumed = live.getActiveRuntime() as ActiveSequenceRuntime
        assertEquals(fixture.currentChild!!.id, resumed.currentChild?.id)
        assertEquals(
            fixture.currentChild.pauses
                .single()
                .id,
            resumed.currentChild
                ?.pauses
                ?.single()
                ?.id,
        )
        assertEquals(fixture.at(120), NextRuntimeDeadlineResolver.resolve(resumed)?.at)
        assertBeforeDeadline(fixture, 120)
        live.reconcileActiveSession(fixture.at(1_000))
        assertSequenceTerminal(fixture, 195)
    }

    @Test
    fun noCurrentV9SequenceRemainsEngagedAndEarlyEndFulfillsItsRootOnce() {
        val fixture = recover(HistoricalActiveV8State.WAITING_NEXT)
        val runtime = live.getActiveRuntime() as ActiveSequenceRuntime
        assertEquals(ActiveSequenceState.WAITING_NEXT, ActiveSequenceStateResolver.resolve(runtime))
        assertNull(runtime.currentChild)
        assertNull(runtime.execution.currentOccurrenceId)
        assertNull(NextRuntimeDeadlineResolver.resolve(runtime))
        assertTrue(live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.isEmpty())
        reopen()
        assertRecovered(fixture)
        val ended = live.endSequenceEarly(fixture.sequenceId, fixture.at(100)).execution
        assertEquals(SequenceExecutionStatus.ENDED_EARLY, ended.status)
        assertEquals(fixture.at(100), ended.endedAt)
        assertEquals(runtime.execution.occurrences.map { it.id }, ended.occurrences.map { it.id })
        assertEquals(fixture.completedChild, child("child-0"))
        assertEquals(1, database.activityExecutionDao().getSequenceChildAggregates("sequence").size)
        assertFulfilled(activePlan(fixture), fixture.at(100), sequence = true)
        assertStableTerminal(fixture.at(1_000))
        assertEquals(1L, statistics().global(StatisticsPeriod.AllTime).topLevelExecutionCount)
        assertEquals(Duration.ofSeconds(30), statistics().global(StatisticsPeriod.AllTime).totalTrackedDuration)
        assertFrozenFacts()
    }

    @Test
    fun lateActivityFulfillmentFailureRollsBackRecoveredPlanAndRootBeforeSingleRetry() =
        failAndRetry(HistoricalActiveV8State.ACTIVITY_RUNNING)

    @Test
    fun lateSequenceFulfillmentFailureRollsBackPlanChildrenIntervalsAndSlotBeforeSingleRetry() =
        failAndRetry(HistoricalActiveV8State.SEQUENCE_RUNNING)

    private fun failAndRetry(state: HistoricalActiveV8State) {
        val fixture = recover(state)
        // clear() is after root terminalization AND Plan fulfillment. Prove that ordering before aborting.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_s4_release BEFORE DELETE ON active_session BEGIN SELECT CASE WHEN (SELECT status FROM plan_entries WHERE id = 'active-plan') != 'FULFILLED' THEN RAISE(ABORT, 's4 failure too early') END; SELECT RAISE(ABORT, 's4 late fulfillment failure'); END",
        )
        val failure =
            assertThrows(SQLiteConstraintException::class.java) { live.reconcileActiveSession(fixture.at(1_000)) }
        assertTrue(failure.message.orEmpty().contains("s4 late fulfillment failure"))
        reopen()
        assertRecovered(fixture)
        // Complete row equality includes generated children, values, intervals, Plan linkage and every timestamp.
        assertOriginalFacts()
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_s4_release")
        val expectedEvents =
            if (state ==
                HistoricalActiveV8State.ACTIVITY_RUNNING
            ) {
                listOf(40L)
            } else {
                listOf(70L, 80L, 110L, 115L, 145L)
            }
        assertEquals(
            expectedEvents.map(fixture::at),
            live.reconcileActiveSession(fixture.at(1_000)).appliedEvents.map {
                it.deadline.at
            },
        )
        if (state ==
            HistoricalActiveV8State.ACTIVITY_RUNNING
        ) {
            assertActivityTerminal(fixture, 40)
        } else {
            assertSequenceTerminal(fixture, 145)
        }
    }

    private fun completeRunningActivity(delivery: Long) {
        val fixture = recover(HistoricalActiveV8State.ACTIVITY_RUNNING)
        assertEquals(fixture.at(40), NextRuntimeDeadlineResolver.resolve(live.getActiveRuntime()!!)?.at)
        assertBeforeDeadline(fixture, 40)
        assertEquals(
            listOf(fixture.at(40)),
            live.reconcileActiveSession(fixture.at(delivery)).appliedEvents.map {
                it.deadline.at
            },
        )
        assertActivityTerminal(fixture, 40)
    }

    private fun assertBeforeDeadline(
        fixture: HistoricalActiveV8Fixture,
        seconds: Long,
    ) {
        val before = facts()
        assertTrue(live.reconcileActiveSession(fixture.at(seconds).minusMillis(1)).appliedEvents.isEmpty())
        assertEquals(before, facts())
        assertEquals(PlanEntryStatus.PLANNED, plans().getPlan(PlanEntryId("active-plan"))?.status)
        assertTrue(plans().isEngaged(PlanEntryId("active-plan")))
    }

    private fun recover(state: HistoricalActiveV8State): HistoricalActiveV8Fixture {
        val fixture = HistoricalActiveV8Fixture(state)
        val expected = activePlan(fixture)
        migrate {
            // Reuse independently specified Execution SQL common to v8/v9, on a genuine v9 file.
            // No v8 database is created and no modern Plan writer is used.
            fixture.seed(this)
            seedHistoricalPlanV9(expected)
            val table = if (fixture.runtime is ActiveActivityRuntime) "activity_executions" else "sequence_executions"
            val root = if (fixture.runtime is ActiveActivityRuntime) "standalone" else "sequence"
            execSQL("UPDATE `$table` SET plan_entry_id = 'active-plan' WHERE id = ?", arrayOf<Any>(root))
        }
        assertRecovered(fixture)
        return fixture
    }

    private fun activePlan(fixture: HistoricalActiveV8Fixture) =
        historicalPlanV9(
            "active-plan",
            PlanTarget.FloatingDay(fixture.date),
            sequence = fixture.runtime is ActiveSequenceRuntime,
        )

    private fun expectedRuntime(fixture: HistoricalActiveV8Fixture): ActiveRuntime =
        when (val runtime = requireNotNull(fixture.runtime)) {
            is ActiveActivityRuntime ->
                runtime.copy(
                    execution = runtime.execution.copy(planEntryId = PlanEntryId("active-plan")),
                )
            is ActiveSequenceRuntime ->
                runtime.copy(
                    execution = runtime.execution.copy(planEntryId = PlanEntryId("active-plan")),
                )
        }

    private fun assertRecovered(fixture: HistoricalActiveV8Fixture) {
        val expected = expectedRuntime(fixture)
        val plan = activePlan(fixture)
        assertEquals(expected, live.getActiveRuntime())
        assertEquals(expected.session, live.getActiveSession())
        assertEquals(plan, plans().getPlan(plan.id))
        assertTrue(plans().isEngaged(plan.id))
        val reads = PlanReadRepository(database, CurrentZoneIdProvider { fixture.zone })
        val focused = reads.getFocusedAction(plan.id)
        assertEquals(plan.actionIdentity(), focused.identity)
        assertTrue(focused.engaged)
        assertEquals(PlanSourceState.CHANGED, focused.sourceState)
        if (expected is ActiveActivityRuntime) {
            assertEquals(fixture.activity, (focused.snapshot as FocusedPlanAction.Snapshot.Activity).value)
        } else {
            assertEquals(fixture.sequence, (focused.snapshot as FocusedPlanAction.Snapshot.Sequence).value)
        }
        val week = reads.getWeek(WeekPlanQuery(LocalDate.of(2019, 12, 30), fixture.date, fixture.at(1_000)))
        assertEquals(plan, week.selectedDayPlans.single().plan)
        assertTrue(week.selectedDayPlans.single().engaged)
        val daily = daily(fixture.date, fixture.zone, fixture.at(1_000))
        assertEquals(expected, daily.active?.runtime)
        assertEquals(plan, daily.dayPlans.single().plan)
        assertTrue(daily.dayPlans.single().engaged)
        // Mutation admission must honor durable engagement after reconstruction.
        val writer = plans()
        assertThrows(IllegalArgumentException::class.java) { writer.cancelPlan(focused.identity, fixture.at(1_000)) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { writer.preservePlanSchedule(focused.identity, fixture.at(1_000)) }
        assertThrows(IllegalArgumentException::class.java) {
            writer.updatePlanFromTemplate(focused.identity, fixture.at(1_000))
        }
        assertTrue(
            runCatching {
                if (expected is ActiveActivityRuntime) {
                    live.startActivityFromPlan(focused.identity, fixture.at(1_000), fixture.at(1_000), fixture.zone)
                } else {
                    live.startSequenceFromPlan(focused.identity, fixture.at(1_000), fixture.at(1_000), fixture.zone)
                }
            }.isFailure,
        )
        val global = statistics().global(StatisticsPeriod.AllTime)
        assertEquals(0L, global.topLevelExecutionCount)
        assertEquals(Duration.ZERO, global.totalTrackedDuration)
        assertEquals(
            if (expected is ActiveSequenceRuntime) 1L else 0L,
            statistics().activitySeries(activitySeries, StatisticsPeriod.AllTime).executionCount,
        )
        assertEquals(
            if (expected is ActiveActivityRuntime) {
                setOf(
                    "Current activity",
                )
            } else {
                setOf("Current activity", "Current sequence")
            },
            LibraryRepository
                .create(database)
                .getAll()
                .map {
                    it.name
                }.toSet(),
        )
        assertOriginalFacts()
    }

    private fun assertActivityTerminal(
        fixture: HistoricalActiveV8Fixture,
        end: Long,
    ) {
        val expected =
            (liveCompletedActivity(fixture)).copy(
                completedAt = fixture.at(end),
                updatedAt = fixture.at(end),
            )
        assertEquals(expected, child("standalone"))
        assertFulfilled(activePlan(fixture), fixture.at(end), sequence = false)
        assertStableTerminal(fixture.at(1_000))
        assertEquals(expected, child("standalone"))
        assertEquals(1, facts().getValue("activity_executions").size)
        val detail = requireNotNull(HistoryReadRepository(database).getActivityDetail(expected.id))
        assertEquals(expected.planEntryId, detail.root.planEntryId)
        assertEquals(fixture.at(end), detail.root.completedAt)
        assertEquals(fixture.date, detail.root.primaryLocalDate)
        assertEquals(fixture.zone, detail.root.originalZoneId)
        assertEquals(ActivityHistoryActualValue.Number(0), detail.fields[0].actualValue)
        assertEquals(ActivityHistoryActualValue.Missing, detail.fields[1].actualValue)
        assertEquals(listOf(detail.root), daily(fixture.date, fixture.zone, fixture.at(1_000)).completedHistory)
        assertEquals(1L, statistics().global(StatisticsPeriod.AllTime).topLevelExecutionCount)
        assertEquals(Duration.ofSeconds(30), statistics().global(StatisticsPeriod.AllTime).totalTrackedDuration)
        assertFrozenFacts()
    }

    private fun liveCompletedActivity(fixture: HistoricalActiveV8Fixture) =
        fixture.standalone.copy(
            planEntryId = PlanEntryId("active-plan"),
            status = ActivityExecutionStatus.COMPLETED,
            activeDuration = Duration.ofSeconds(30),
            pauses =
                fixture.standalone.pauses.map {
                    if (it.endedAt == null) it.copy(endedAt = fixture.at(100)) else it
                },
        )

    private fun assertSequenceTerminal(
        fixture: HistoricalActiveV8Fixture,
        end: Long,
    ) {
        val root = database.sequenceExecutionDao().getAggregate("sequence")!!.toDomain()
        val initial = (expectedRuntime(fixture) as ActiveSequenceRuntime).execution
        assertEquals(initial.id, root.id)
        assertEquals(initial.snapshotId, root.snapshotId)
        assertEquals(initial.planEntryId, root.planEntryId)
        assertEquals(initial.statisticsSeriesId, root.statisticsSeriesId)
        assertEquals(SequenceExecutionStatus.COMPLETED, root.status)
        assertEquals(fixture.at(end), root.endedAt)
        assertEquals(Duration.ofSeconds(120), root.activeDuration)
        assertEquals(Duration.ofSeconds(end - 120), root.pauseDuration)
        assertEquals(Duration.ofSeconds(end), root.wallDuration)
        assertEquals(initial.occurrences.map { it.id }, root.occurrences.map { it.id })
        assertEquals(
            initial.occurrences.map {
                it.sourceSequenceSnapshotNodeId
            },
            root.occurrences.map { it.sourceSequenceSnapshotNodeId },
        )
        assertEquals(initial.occurrences.map { it.repeatIteration }, root.occurrences.map { it.repeatIteration })
        assertTrue(root.occurrences.all { it.status == RuntimeOccurrenceStatus.COMPLETED })
        assertTrue(root.intervals.none { it.endedAt == null })
        val children = database.activityExecutionDao().getSequenceChildAggregates("sequence").map { it.toDomain() }
        assertEquals(4, children.size)
        assertEquals(fixture.completedChild, children.single { it.id == fixture.completedChild.id })
        fixture.currentChild?.let { assertTrue(children.any { child -> child.id == it.id }) }
        assertTrue(children.all { it.planEntryId == null })
        assertEquals(root.occurrences.map { it.id }.toSet(), children.map { it.sequenceOccurrenceId }.toSet())
        assertFulfilled(activePlan(fixture), fixture.at(end), sequence = true)
        assertStableTerminal(fixture.at(1_000))
        assertEquals(root, database.sequenceExecutionDao().getAggregate("sequence")!!.toDomain())
        val detail = HistoryReadRepository(database).getSequenceDetail(fixture.sequenceId)!!
        assertEquals(root.planEntryId, detail.root.planEntryId)
        assertEquals(root.endedAt, detail.root.completedAt)
        assertEquals(fixture.zone, detail.root.originalZoneId)
        assertEquals(root.intervals, detail.intervals)
        assertEquals(SequenceHistoryActualValue.Number(0), detail.fields[0].actualValue)
        assertEquals(listOf(detail.root), daily(fixture.date, fixture.zone, fixture.at(1_000)).completedHistory)
        val global = statistics().global(StatisticsPeriod.AllTime)
        assertEquals(1L, global.topLevelExecutionCount)
        assertEquals(Duration.ofSeconds(120), global.totalTrackedDuration)
        assertEquals(Duration.ofSeconds(end - 120), global.totalSequencePauseIdleDuration)
        assertEquals(4L, statistics().activitySeries(activitySeries, StatisticsPeriod.AllTime).executionCount)
        assertEquals(1L, statistics().sequenceSeries(sequenceSeries, StatisticsPeriod.AllTime).executionCount)
        assertFrozenFacts()
    }

    private fun assertFulfilled(
        originalPlan: PlanEntry,
        at: Instant,
        sequence: Boolean,
    ) {
        val expected =
            originalPlan.copy(
                status = PlanEntryStatus.FULFILLED,
                fulfilledAt = at,
                updatedAt = at,
                fulfilledActivityExecutionId = if (sequence) null else ActivityExecutionId("standalone"),
                fulfilledSequenceExecutionId = if (sequence) SequenceExecutionId("sequence") else null,
            )
        assertEquals(expected, plans().getPlan(expected.id))
        assertFalse(plans().isEngaged(expected.id))
        val focused = PlanReadRepository(database, CurrentZoneIdProvider { tokyo }).getFocusedAction(expected.id)
        assertEquals(expected.actionIdentity(), focused.identity)
        assertFalse(focused.engaged)
        val projection = daily(historyDate, tokyo, at)
        assertEquals(expected, projection.dayPlans.single().plan)
        assertFalse(projection.dayPlans.single().engaged)
    }

    private fun assertStableTerminal(now: Instant) {
        val fulfilled = requireNotNull(plans().getPlan(PlanEntryId("active-plan")))
        assertEquals(PlanEntryStatus.FULFILLED, fulfilled.status)
        val terminal = facts()
        assertNull(live.getActiveRuntime())
        assertNull(live.getActiveSession())
        assertTrue(live.reconcileActiveSession(now).appliedEvents.isEmpty())
        assertEquals(terminal, facts())
        reopen()
        assertNull(live.getActiveRuntime())
        assertNull(live.getActiveSession())
        assertTrue(live.reconcileActiveSession(now).appliedEvents.isEmpty())
        assertEquals(terminal, facts())
        assertEquals(fulfilled, plans().getPlan(fulfilled.id))
        assertFalse(plans().isEngaged(fulfilled.id))
        assertEquals(
            fulfilled.actionIdentity(),
            PlanReadRepository(
                database,
                CurrentZoneIdProvider {
                    tokyo
                },
            ).getFocusedAction(fulfilled.id).identity,
        )
        database.openHelper.readableDatabase.assertCurrentIntegrity()
    }

    private fun assertHistoricalFulfillment(
        timed: PlanEntry,
        noLive: PlanEntry,
        deleted: PlanEntry,
        sequence: PlanEntry,
    ) {
        val history = HistoryReadRepository(database)
        val roots = history.getCompletedRoots(CompletedHistoryQuery(HistoryDateRange(historyDate, historyDate), 10))
        assertEquals(3, roots.size)
        assertEquals(
            setOf(timed.id, noLive.id, sequence.id),
            roots
                .map {
                    when (it) {
                        is CompletedActivityHistoryRoot -> it.planEntryId
                        is CompletedSequenceHistoryRoot -> it.planEntryId
                    }
                }.toSet(),
        )
        roots.forEach {
            assertEquals(historyDate, it.primaryLocalDate)
            assertEquals(tokyo, it.originalZoneId)
            assertEquals(540, it.originalUtcOffsetMinutes)
        }
        val timedDetail = history.getActivityDetail(ActivityExecutionId("timed"))!!
        assertEquals(timed.fulfilledAt, timedDetail.root.completedAt)
        assertEquals(Duration.ofSeconds(50), timedDetail.root.activeDuration)
        assertEquals(ActivityHistoryConfiguredValue.Number(1500), timedDetail.fields[0].configuredValue)
        assertEquals(ActivityHistoryActualValue.Number(0), timedDetail.fields[0].actualValue)
        assertEquals(
            ActivityHistoryActualValue.Category(ActivitySnapshotCategoryOptionId("f-option"), "Local option"),
            timedDetail.fields[1].actualValue,
        )
        val noLiveDetail = history.getActivityDetail(ActivityExecutionId("no-live"))!!
        assertEquals(noLive.fulfilledAt, noLiveDetail.root.completedAt)
        assertNull(noLiveDetail.root.startedAt)
        assertNull(noLiveDetail.root.activeDuration)
        assertEquals(ActivityHistoryConfiguredValue.Number(1500), noLiveDetail.fields[0].configuredValue)
        assertEquals(ActivityHistoryActualValue.Missing, noLiveDetail.fields[0].actualValue)
        assertNull(history.getActivityDetail(ActivityExecutionId("zero")))
        assertEquals(Duration.ZERO, child("zero").activeDuration)
        assertEquals(deleted.id, child("zero").planEntryId)
        val sequenceDetail = history.getSequenceDetail(SequenceExecutionId("sequence-execution"))!!
        assertEquals(sequence.fulfilledAt, sequenceDetail.root.completedAt)
        assertEquals(sequence.id, sequenceDetail.root.planEntryId)
        assertEquals(SequenceHistoryActualValue.Number(0), sequenceDetail.fields[0].actualValue)
        assertEquals(3, sequenceDetail.occurrences.size)
        assertEquals(listOf(null, 1, 2), sequenceDetail.occurrences.map { it.repeatIteration })
        assertEquals(
            listOf("f-top", "f-nested", "f-nested"),
            sequenceDetail.occurrences.map {
                it.sourceSequenceSnapshotNodeId?.value
            },
        )
        assertEquals(
            listOf(null, "f-repeat", "f-repeat"),
            sequenceDetail.occurrences.map {
                it.repeatSourceSnapshotNodeId?.value
            },
        )
        assertEquals(
            setOf("child-0", "child-1", "child-2"),
            sequenceDetail.occurrences
                .map {
                    it.child?.executionId?.value
                }.toSet(),
        )
        assertTrue(
            database.activityExecutionDao().getSequenceChildAggregates("sequence-execution").all {
                it.toDomain().planEntryId ==
                    null
            },
        )
        val provider = CurrentZoneIdProvider { ZoneId.of("America/Los_Angeles") }
        val daily =
            DailyReadRepository(
                database,
                provider,
            ).getDaily(DailyQuery(historyDate, origin.plusSeconds(1_000), 10))
        assertEquals(roots, daily.completedHistory)
        assertEquals(
            setOf(
                timed,
                deleted,
                historicalPlanV9("matching-unlinked-plan", PlanTarget.FloatingDay(historyDate)),
            ),
            daily.dayPlans
                .map {
                    it.plan
                }.toSet(),
        )
        assertEquals(setOf(noLive, sequence), daily.weekPlans.map { it.plan }.toSet())
        assertTrue(daily.weekPlans.none { it.engaged || it.overdue })
        assertTrue(
            DailyReadRepository(
                database,
                provider,
            ).getDaily(DailyQuery(historyDate.minusDays(1), origin.plusSeconds(1_000), 10)).completedHistory.isEmpty(),
        )
        val week =
            PlanReadRepository(
                database,
                provider,
            ).getWeek(WeekPlanQuery(LocalDate.of(2019, 12, 30), historyDate, origin.plusSeconds(1_000)))
        assertEquals(daily.dayPlans.map { it.plan }, week.selectedDayPlans.map { it.plan })
        assertEquals(daily.weekPlans.map { it.plan }, week.weekPlans.map { it.plan })
        val global = statistics().global(StatisticsPeriod.AllTime)
        assertEquals(3L, global.topLevelExecutionCount)
        assertEquals(Duration.ofSeconds(140), global.totalTrackedDuration)
        assertEquals(Duration.ofSeconds(20), global.totalSequencePauseIdleDuration)
        val activity = statistics().activitySeries(activitySeries, StatisticsPeriod.AllTime)
        assertEquals(5L, activity.executionCount)
        assertEquals(4L, activity.durations.sampleCount)
        assertEquals(Duration.ofSeconds(140), activity.durations.total)
        assertEquals(1L, statistics().sequenceSeries(sequenceSeries, StatisticsPeriod.AllTime).executionCount)
        val number =
            statistics().numberFieldStatistics(
                activitySeries,
                StatisticsFieldId.Activity(ActivityTemplateFieldId("number")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(4L, number.recordedCount)
        assertEquals(1L, number.missingCount)
        assertEquals(BigInteger.valueOf(6000), number.values.totalScaled)
        val category =
            statistics().categoryFieldStatistics(
                activitySeries,
                StatisticsFieldId.Activity(ActivityTemplateFieldId("category")),
                StatisticsPeriod.AllTime,
            )
        assertEquals(2L, category.recordedCount)
        assertEquals(3L, category.missingCount)
        assertEquals(
            StatisticsCategoryOptionId.ActivitySource(CategoryOptionId("option")),
            category.values
                .single {
                    it.count >
                        0
                }.id,
        )
        assertEquals(
            setOf(
                "activity-series",
                "sequence-series",
                "system:statistics-series:one-off-activities",
            ),
            statistics()
                .seriesCatalog()
                .map {
                    it.id.value
                }.toSet(),
        )
        assertEquals(sequence.id, (roots.filterIsInstance<CompletedSequenceHistoryRoot>().single()).planEntryId)
    }

    private fun assertFrozenActivity(snapshot: com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot) {
        assertEquals(ActivitySnapshotId("activity-snapshot"), snapshot.id)
        assertEquals(3L, snapshot.sourceRevision)
        assertEquals(activitySeries, snapshot.statisticsSeriesId)
        assertEquals(Duration.ofSeconds(30), snapshot.timerTarget)
        assertEquals(listOf("f-number", "f-category", "f-text"), snapshot.fields.map { it.id.value })
        assertEquals(listOf("number", "category", "text"), snapshot.fields.map { it.sourceFieldId?.value })
        val main = snapshot.fields.single { it.isMainValue }
        assertEquals(ActivitySnapshotFieldId("f-number"), main.id)
        assertEquals(1500L, main.defaultNumberScaled)
        assertEquals("kg", main.unit)
        assertEquals(1, main.displayPrecision)
        val category = snapshot.fields[1]
        assertEquals(ActivitySnapshotCategoryOptionId("f-option"), category.defaultCategoryOptionId)
        assertEquals(CategoryOptionId("option"), category.categoryOptions.single().sourceOptionId)
        assertEquals("Frozen default", snapshot.fields[2].defaultText)
    }

    private fun assertPlans(expected: List<PlanEntry>) {
        expected.forEach { plan ->
            PlanEntryValidator.requireValid(plan)
            assertEquals(plan, plans().getPlan(plan.id))
            if (plan.target !is PlanTarget.Month) {
                assertEquals(
                    plan.actionIdentity(),
                    PlanReadRepository(
                        database,
                        CurrentZoneIdProvider {
                            tokyo
                        },
                    ).getFocusedAction(plan.id).identity,
                )
            }
        }
    }

    private fun plans(zone: ZoneId = tokyo) =
        PlanRepository(
            database,
            { error("Acceptance reads must not allocate Plans") },
            ActivitySnapshotFactory({
                error("No snapshot allocation")
            }, { error("No Field allocation") }, { error("No option allocation") }),
            SequenceSnapshotFactory({
                error("No snapshot allocation")
            }, { error("No Field allocation") }, { error("No option allocation") }, { error("No node allocation") }),
            CurrentZoneIdProvider { zone },
        )

    private fun statistics() = StatisticsRepository(database) { error("Acceptance reads must not allocate Series") }

    private fun child(id: String) = database.activityExecutionDao().getAggregate(id)!!.toDomain()

    private fun daily(
        date: LocalDate,
        zone: ZoneId,
        now: Instant,
    ) = DailyReadRepository(database, CurrentZoneIdProvider { zone }).getDaily(DailyQuery(date, now, 10))

    private fun migrate(seed: SupportSQLiteDatabase.() -> Unit) {
        LifeTracingMigrationTestDatabaseFactory.createVersion9(helper, name).use { source ->
            assertEquals(9, source.version)
            source
                .query(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name IN ('activity_executions_history_latest_root', 'sequence_executions_status_primary_date')",
                ).use {
                    assertFalse("Source must not have v10 discovery indexes", it.moveToFirst())
                }
            source.execSQL("PRAGMA foreign_keys = ON")
            source.seed()
            source
                .query(
                    "PRAGMA foreign_key_check",
                ).use { assertFalse("Invalid historical v9 fixture", it.moveToFirst()) }
            original = source.historicalPlanFacts()
        }
        assertTrue(context.getDatabasePath(name).isFile)
        reopen() // Production builder upgrades the SAME physical file.
        assertOriginalFacts()
        reopen() // Separate connection and newly acquired repositories after the upgrade.
        assertOriginalFacts()
    }

    private fun facts() = database.openHelper.readableDatabase.historicalPlanFacts()

    private fun assertOriginalFacts() = assertEquals(original, facts())

    private fun assertFrozenFacts() {
        val current = facts()
        original.filterKeys { it.contains("snapshot") || it == "statistics_series" }.forEach { (table, rows) ->
            assertEquals("Frozen facts changed in $table", rows, current.getValue(table))
        }
    }

    private fun close() {
        liveReference = null
        reference?.close()
        reference = null
    }

    private fun reopen() {
        close()
        reference = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
        database.openHelper.writableDatabase.assertCurrentIntegrity()
        liveReference = LiveSessionRepository.create(database)
    }
}
