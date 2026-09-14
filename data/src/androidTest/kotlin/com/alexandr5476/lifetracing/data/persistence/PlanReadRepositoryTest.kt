package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.CancelledPlanPageQuery
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class PlanReadRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var reads: PlanReadRepository
    private var zone: ZoneId = ZoneOffset.UTC

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        LiveRuntimeTestFixtures(database).apply {
            seedSeries()
            activity("no-live", "NO_LIVE_TRACKING")
            activity("stopwatch", "STOPWATCH")
            sequence("sequence", listOf("stopwatch"))
        }
        reads = PlanReadRepository(database, CurrentZoneIdProvider { zone })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun weekSeparatesDayRowsFromTheMondayAnchoredWeekAndKeepsCancelledOut() {
        database.planEntryDao().insert(plan("floating", activity = "no-live", day = "2026-08-20"))
        database.planEntryDao().insert(
            plan("exact", activity = "stopwatch", scheduledAt = Instant.parse("2026-08-20T09:00:00Z")),
        )
        database.planEntryDao().insert(plan("week", sequence = "sequence", precision = "WEEK", week = "2026-08-17"))
        database.planEntryDao().insert(
            plan("cancelled", activity = "no-live", day = "2026-08-20", status = "CANCELLED", cancelledAt = 2),
        )

        val read = week("2026-08-17", "2026-08-20")

        assertEquals(setOf("floating", "exact"), read.selectedDayPlans.map { it.plan.id.value }.toSet())
        assertEquals(listOf("week"), read.weekPlans.map { it.plan.id.value })
        assertTrue((read.selectedDayPlans + read.weekPlans).map { it.plan.id }.distinct().size == 3)
        assertEquals(7, read.dayPresence.size)
        assertEquals(2, read.dayPresence.single { it.date == LocalDate.parse("2026-08-20") }.count)
    }

    @Test
    fun exactPlacementUsesTheCurrentZoneWithoutMutatingTheDurableTarget() {
        database.planEntryDao().insert(
            plan("exact", activity = "stopwatch", scheduledAt = Instant.parse("2026-08-20T23:30:00Z")),
        )
        val before = requireNotNull(database.planEntryDao().getById("exact"))

        assertEquals(listOf("exact"), week("2026-08-17", "2026-08-20").selectedDayPlans.map { it.plan.id.value })
        zone = ZoneOffset.ofHours(2)
        assertTrue(week("2026-08-17", "2026-08-20").selectedDayPlans.isEmpty())
        assertEquals(listOf("exact"), week("2026-08-17", "2026-08-21").selectedDayPlans.map { it.plan.id.value })
        assertEquals(before, database.planEntryDao().getById("exact"))
    }

    @Test
    fun focusedActionHydratesOnlyItsFrozenConfigurationAndPreservesActionIdentity() {
        database.planEntryDao().insert(plan("activity", activity = "no-live", day = "2026-08-20"))
        database.planEntryDao().insert(plan("sequence", sequence = "sequence", precision = "WEEK", week = "2026-08-17"))

        val activity = reads.getFocusedAction(PlanEntryId("activity"))
        val sequence = reads.getFocusedAction(PlanEntryId("sequence"))

        assertEquals(PlanEntryId("activity"), activity.identity.planEntryId)
        assertTrue(activity.snapshot is com.alexandr5476.lifetracing.domain.FocusedPlanAction.Snapshot.Activity)
        assertEquals(PlanEntryId("sequence"), sequence.identity.planEntryId)
        assertTrue(sequence.snapshot is com.alexandr5476.lifetracing.domain.FocusedPlanAction.Snapshot.Sequence)
        assertEquals(PlanTarget.Week(LocalDate.parse("2026-08-17")), sequence.identity.target)
    }

    @Test
    fun cancelledPageIsBoundedOrderedAndLeavesMonthRowsUntouched() {
        database.planEntryDao().insert(
            plan("first", activity = "no-live", day = "2026-08-20", status = "CANCELLED", cancelledAt = 4),
        )
        database.planEntryDao().insert(
            plan(
                "second",
                sequence = "sequence",
                precision = "WEEK",
                week = "2026-08-17",
                status = "CANCELLED",
                cancelledAt = 3,
            ),
        )
        database.planEntryDao().insert(
            plan(
                "month",
                activity = "no-live",
                precision = "MONTH",
                month = "2026-08",
                status = "CANCELLED",
                cancelledAt = 5,
            ),
        )

        val first = reads.getCancelledPage(CancelledPlanPageQuery(0, 1))
        val second = reads.getCancelledPage(CancelledPlanPageQuery(1, 1))

        assertEquals(listOf("first"), first.items.map { it.plan.id.value })
        assertTrue(first.hasNextPage)
        assertEquals(listOf("second"), second.items.map { it.plan.id.value })
        assertFalse(second.hasNextPage)
        assertThrows(IllegalArgumentException::class.java) { CancelledPlanPageQuery(0, 101) }
        assertEquals("MONTH", requireNotNull(database.planEntryDao().getById("month")).precision)
    }

    private fun week(
        weekStart: String,
        selectedDate: String,
    ) = reads.getWeek(
        WeekPlanQuery(
            LocalDate.parse(weekStart),
            LocalDate.parse(selectedDate),
            Instant.parse("2026-08-21T12:00:00Z"),
        ),
    )

    private fun plan(
        id: String,
        activity: String? = null,
        sequence: String? = null,
        precision: String = "DAY",
        day: String? = null,
        week: String? = null,
        month: String? = null,
        scheduledAt: Instant? = null,
        status: String = "PLANNED",
        cancelledAt: Long? = null,
    ) = PlanEntryEntity(
        id,
        if (activity != null) "ACTIVITY" else "SEQUENCE",
        null,
        null,
        null,
        activity,
        sequence,
        precision,
        day.takeIf { scheduledAt == null },
        week,
        month,
        scheduledAt?.toEpochMilli(),
        scheduledAt?.let { "UTC" },
        status,
        null,
        null,
        0,
        cancelledAt ?: 0,
        cancelledAt,
        null,
    )
}
