@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CalendarTemporalContext
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFactory
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CalendarTemporalReadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "calendar-temporal-${UUID.randomUUID()}.db"
    private lateinit var database: LifeTracingDatabase
    private var zone = ZoneId.of("Europe/Berlin")
    private val provider = CurrentZoneIdProvider { zone }

    @Before
    fun setUp() {
        database = open()
        LiveRuntimeTestFixtures(database).apply {
            seedSeries()
            activity("snapshot", "NO_LIVE_TRACKING")
        }
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    "source",
                    "Source",
                    null,
                    "NO_LIVE_TRACKING",
                    null,
                    "activity-series",
                    1,
                    0,
                    0,
                    null,
                    null,
                ),
                ActivityTemplateSettingsEntity("source"),
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_snapshots SET source_template_id = 'source', source_revision = 1 WHERE id = 'snapshot'",
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun allCalendarReadersUseHalfOpenDstDaysAndAdjacentWeeksWithoutLoss() {
        for ((literal, hours) in listOf("2026-03-29" to 23L, "2026-10-25" to 25L)) {
            val date = LocalDate.parse(literal)
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            assertEquals(hours, Duration.between(start, end).toHours())
            val instants = linkedMapOf("$literal-start" to start, "$literal-inside" to start.plusSeconds(3_600))
            if (hours == 25L) {
                instants["$literal-first"] = Instant.parse("2026-10-25T00:30:00Z")
                instants["$literal-second"] = Instant.parse("2026-10-25T01:30:00Z")
            }
            instants.forEach { (id, instant) -> database.planEntryDao().insert(plan(id, instant)) }
            database.planEntryDao().insert(plan("$literal-before", start.minusMillis(1)))
            database.planEntryDao().insert(plan("$literal-end", end))
            val durable = durableRows()
            val plans = plans()
            val reads = PlanReadRepository(database, provider)
            val daily = DailyReadRepository(database, provider)
            val dayIds = plans.getDayPlans(date, date).map { it.plan.id.value }.toSet()
            assertEquals(instants.keys, dayIds)
            val nextIds = plans.getDayPlans(date.plusDays(1), date.plusDays(1)).map { it.plan.id.value }.toSet()
            assertEquals(setOf("$literal-end"), nextIds)
            assertTrue(dayIds.intersect(nextIds).isEmpty())
            assertEquals(
                setOf("$literal-before"),
                plans
                    .getDayPlans(date.minusDays(1), date.minusDays(1))
                    .map {
                        it.plan.id.value
                    }.toSet(),
            )
            val week = reads.getWeek(WeekPlanQuery(date.with(DayOfWeek.MONDAY), date, start))
            val day = daily.getDaily(DailyQuery(date, start, 20))
            assertEquals(dayIds, week.selectedDayPlans.map { it.plan.id.value }.toSet())
            assertEquals(dayIds, day.dayPlans.map { it.plan.id.value }.toSet())
            assertEquals(instants.size, week.dayPresence.single { it.date == date }.count)
            val nextWeek = reads.getWeek(WeekPlanQuery(date.plusDays(1), date.plusDays(1), start))
            assertEquals(nextIds, nextWeek.selectedDayPlans.map { it.plan.id.value }.toSet())
            assertEquals(
                nextIds,
                daily
                    .getDaily(DailyQuery(date.plusDays(1), start, 20))
                    .dayPlans
                    .map { it.plan.id.value }
                    .toSet(),
            )
            assertEquals(durable, durableRows())
        }
    }

    @Test
    fun relocationAndReloadMoveExactDensityButPreserveLiteralAndEveryDurableFact() {
        val instant = Instant.parse("2026-10-26T00:30:00Z")
        val monday = LocalDate.parse("2026-10-26")
        val sunday = monday.minusDays(1)
        database.planEntryDao().insert(plan("exact", instant))
        database.planEntryDao().insert(plan("floating", day = sunday.toString()))
        database.planEntryDao().insert(plan("week", precision = "WEEK", week = monday.minusWeeks(1).toString()))
        database.planEntryDao().insert(plan("month", precision = "MONTH", month = "2026-10"))
        val durable = durableRows()
        val target = plans().getPlan(PlanEntryId("exact"))
        for (reload in listOf(false, true)) {
            if (reload) {
                database.close()
                database = open()
            }
            for ((effectiveZone, date, time, offset) in listOf(
                Relocation(ZoneId.of("Europe/Berlin"), monday, LocalTime.of(1, 30), ZoneOffset.ofHours(1)),
                Relocation(ZoneId.of("America/New_York"), sunday, LocalTime.of(20, 30), ZoneOffset.ofHours(-4)),
            )) {
                zone = effectiveZone
                val now = instant.plusMillis(1)
                val reads = PlanReadRepository(database, provider)
                val daily = DailyReadRepository(database, provider)
                val week = reads.getWeek(WeekPlanQuery(date.with(DayOfWeek.MONDAY), date, now))
                val row = week.selectedDayPlans.single { it.plan.id.value == "exact" }
                assertEquals(date, row.effectiveLocalDate)
                assertEquals(time, row.exactLocalTime)
                assertEquals(offset, row.exactUtcOffset)
                assertEquals(target, row.plan)
                assertEquals(PlanSourceState.CURRENT, row.sourceState)
                assertTrue(row.overdue)
                assertEquals(CalendarTemporalContext(now, zone), week.temporalContext)
                val dailyRow = daily.getDaily(DailyQuery(date, now, 20)).dayPlans.single { it.plan.id.value == "exact" }
                assertEquals(row.effectiveLocalDate, dailyRow.effectiveLocalDate)
                assertEquals(row.exactLocalTime, dailyRow.exactLocalTime)
                assertEquals(row.exactUtcOffset, dailyRow.exactUtcOffset)
                val other = if (date == monday) sunday else monday
                assertFalse(daily.getDaily(DailyQuery(other, now, 20)).dayPlans.any { it.plan.id.value == "exact" })
                val literal = daily.getDaily(DailyQuery(sunday, now, 20))
                assertEquals(
                    PlanTarget.FloatingDay(sunday),
                    literal.dayPlans
                        .single { it.plan.id.value == "floating" }
                        .plan.target,
                )
                assertEquals(date == monday, literal.dayPlans.single { it.plan.id.value == "floating" }.overdue)
                assertEquals(
                    PlanTarget.Week(monday.minusWeeks(1)),
                    literal.weekPlans
                        .single()
                        .plan.target,
                )
                assertEquals(date == monday, literal.weekPlans.single().overdue)
                val oldWeek = reads.getWeek(WeekPlanQuery(sunday.with(DayOfWeek.MONDAY), sunday, now))
                assertEquals(if (date == sunday) 2 else 1, oldWeek.dayPresence.single { it.date == sunday }.count)
                val newWeek = reads.getWeek(WeekPlanQuery(monday, monday, now))
                assertEquals(if (date == monday) 1 else 0, newWeek.dayPresence.single { it.date == monday }.count)
                assertEquals(
                    PlanTarget.Month(YearMonth.of(2026, 10)),
                    plans().getMonthPlans(YearMonth.of(2026, 10)).single().target,
                )
                assertEquals(durable, durableRows())
            }
        }
    }

    @Test
    fun overlapOccurrencesHaveActualOffsetsAbsoluteOrderAndStrictOverdueAfterReload() {
        val first = Instant.parse("2026-10-25T00:30:00Z")
        val second = Instant.parse("2026-10-25T01:30:00Z")
        val date = LocalDate.parse("2026-10-25")
        // Reverse insertion/identity order to make the absolute-Instant tie-break observable.
        database.planEntryDao().insert(plan("a-second", second))
        database.planEntryDao().insert(plan("z-first", first))
        val durable = durableRows()
        for (reload in listOf(false, true)) {
            if (reload) {
                database.close()
                database = open()
            }
            repeat(2) {
                val rows =
                    PlanReadRepository(
                        database,
                        provider,
                    ).getWeek(WeekPlanQuery(date.with(DayOfWeek.MONDAY), date, first))
                val daily = DailyReadRepository(database, provider).getDaily(DailyQuery(date, first, 20))
                assertEquals(listOf("z-first", "a-second"), rows.selectedDayPlans.map { it.plan.id.value })
                assertEquals(rows.selectedDayPlans.map { it.plan.id }, daily.dayPlans.map { it.plan.id })
                assertEquals(
                    listOf(first, second),
                    rows.selectedDayPlans.map { (it.plan.target as PlanTarget.ExactDay).scheduledAt },
                )
                assertEquals(
                    listOf(LocalTime.of(2, 30), LocalTime.of(2, 30)),
                    rows.selectedDayPlans.map { it.exactLocalTime },
                )
                assertEquals(
                    listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)),
                    rows.selectedDayPlans.map { it.exactUtcOffset },
                )
                assertEquals(rows.selectedDayPlans.map { it.exactUtcOffset }, daily.dayPlans.map { it.exactUtcOffset })
                assertEquals(listOf("z-first", "a-second"), plans().getDayPlans(date, date).map { it.plan.id.value })
                assertEquals(2, rows.dayPresence.single { it.date == date }.count)
            }
            for (now in listOf(first.minusMillis(1), first, first.plusMillis(1))) {
                val weekRow =
                    PlanReadRepository(
                        database,
                        provider,
                    ).getWeek(WeekPlanQuery(date.with(DayOfWeek.MONDAY), date, now)).selectedDayPlans.first()
                val dayRow =
                    DailyReadRepository(
                        database,
                        provider,
                    ).getDaily(DailyQuery(date, now, 20)).dayPlans.first()
                assertEquals(now > first, weekRow.overdue)
                assertEquals(weekRow.overdue, dayRow.overdue)
            }
            assertEquals(durable, durableRows())
        }
    }

    @Test
    fun dailyActiveEligibilityUsesTheSameCapturedTodayAsItsPublishedContext() {
        LiveRuntimeTestFixtures(database).activity("active-snapshot", "STOPWATCH")
        val now = Instant.parse("2026-10-25T23:30:00Z")
        LiveSessionRepository.create(database).startStandaloneTimedActivityFromSnapshot(
            ActivitySnapshotId("active-snapshot"),
            now,
            now,
            ZoneOffset.UTC,
        )
        val durable = durableRows()
        val daily = DailyReadRepository(database, provider)
        for (effectiveZone in listOf(ZoneId.of("Europe/Berlin"), ZoneId.of("UTC"))) {
            val today = now.atZone(effectiveZone).toLocalDate()
            val read = daily.getDaily(DailyQuery(today, now, 20, effectiveZone))
            assertEquals(CalendarTemporalContext(now, effectiveZone), read.temporalContext)
            assertTrue(read.active != null)
            assertEquals(null, daily.getDaily(DailyQuery(today.minusDays(1), now, 20, effectiveZone)).active)
            assertEquals(null, daily.getDaily(DailyQuery(today.plusDays(1), now, 20, effectiveZone)).active)
            assertEquals(durable, durableRows())
        }
    }

    @Test
    fun eachReaderCapturesProviderOnceAndExplicitContextSurvivesProviderRelocation() {
        val date = LocalDate.parse("2026-10-25")
        val instant = Instant.parse("2026-10-25T00:30:00Z")
        database.planEntryDao().insert(plan("exact", instant))
        var calls = 0
        val changing =
            CurrentZoneIdProvider {
                calls++
                zone.also { zone = ZoneId.of("Asia/Tokyo") }
            }
        val berlin = ZoneId.of("Europe/Berlin")
        val week =
            PlanReadRepository(
                database,
                changing,
            ).getWeek(WeekPlanQuery(date.with(DayOfWeek.MONDAY), date, instant))
        assertEquals(1, calls)
        assertEquals(CalendarTemporalContext(instant, berlin), week.temporalContext)
        assertEquals(LocalTime.of(2, 30), week.selectedDayPlans.single().exactLocalTime)
        assertEquals(ZoneOffset.ofHours(2), week.selectedDayPlans.single().exactUtcOffset)
        zone = berlin
        calls = 0
        val daily = DailyReadRepository(database, changing).getDaily(DailyQuery(date, instant, 20))
        assertEquals(1, calls)
        assertEquals(CalendarTemporalContext(instant, berlin), daily.temporalContext)
        assertEquals(week.selectedDayPlans.single().exactUtcOffset, daily.dayPlans.single().exactUtcOffset)
        val pinned = DailyReadRepository(database, changing).getDaily(DailyQuery(date, instant, 20, berlin))
        assertEquals(1, calls)
        assertEquals(daily, pinned)
    }

    private data class Relocation(
        val zone: ZoneId,
        val date: LocalDate,
        val time: LocalTime,
        val offset: ZoneOffset,
    )

    private fun open() = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()

    private fun plans() =
        PlanRepository(
            database,
            { error("read must not create a Plan") },
            ActivitySnapshotFactory({
                ActivitySnapshotId("unused")
            }, { ActivitySnapshotFieldId("unused") }, { ActivitySnapshotCategoryOptionId("unused") }),
            SequenceSnapshotFactory({
                SequenceSnapshotId("unused")
            }, {
                SequenceSnapshotFieldId("unused")
            }, { SequenceSnapshotCategoryOptionId("unused") }, { SequenceSnapshotNodeId("unused") }),
            provider,
        )

    private fun plan(
        id: String,
        instant: Instant? = null,
        precision: String = "DAY",
        day: String? = null,
        week: String? = null,
        month: String? = null,
    ) = PlanEntryEntity(
        id,
        "ACTIVITY",
        "source",
        null,
        1,
        "snapshot",
        null,
        precision,
        day,
        week,
        month,
        instant
            ?.toEpochMilli(),
        instant?.let {
            "UTC"
        },
        "PLANNED",
        null,
        null,
        0,
        0,
        null,
        null,
    )

    /** Full test-only row comparison proves projections and reopen never write any durable fact. */
    private fun durableRows(): Map<String, List<List<String?>>> {
        val sqlite = database.openHelper.readableDatabase
        val tables =
            sqlite
                .query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
        return tables.associateWith { table ->
            sqlite.query("SELECT * FROM `$table`").use { cursor ->
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
}
