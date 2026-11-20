package com.alexandr5476.lifetracing.statistics

import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.dateRangeOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

class StatisticsPeriodPresentationTest {
    @Test
    fun `next week must have a representable inclusive end`() {
        val lastMonday = LocalDate.MAX.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastCompleteWeek = StatisticsPeriod.Week(lastMonday.minusWeeks(1))
        assertNull(navigate(lastCompleteWeek, 1))
        assertEquals(lastCompleteWeek, navigate(StatisticsPeriod.Week(lastMonday.minusWeeks(2)), 1))
        assertNull(StatisticsPeriodDraft(LocalDate.MAX.toString()).period(StatisticsPeriodKind.WEEK))
    }

    @Test
    fun `bounded navigation rejects overflow and leaves unbounded and custom periods alone`() {
        val minimumDate = LocalDate.MIN
        val maximumDate = LocalDate.MAX
        listOf(
            StatisticsPeriod.Day(minimumDate),
            StatisticsPeriod.Week(minimumDate),
            StatisticsPeriod.Month(YearMonth.from(minimumDate)),
            StatisticsPeriod.Year(Year.from(minimumDate)),
        ).forEach { assertNull(navigate(it, -1)) }
        listOf(
            StatisticsPeriod.Day(maximumDate),
            StatisticsPeriod.Month(YearMonth.from(maximumDate)),
            StatisticsPeriod.Year(Year.from(maximumDate)),
        ).forEach { assertNull(navigate(it, 1)) }
        listOf(StatisticsPeriod.Custom(minimumDate, maximumDate), StatisticsPeriod.AllTime).forEach {
            assertNull(navigate(it, -1))
            assertNull(navigate(it, 1))
        }
    }

    @Test
    fun `bounded drafts round trip signed years at the supported calendar limits`() {
        for (date in listOf(LocalDate.MIN, LocalDate.MAX, LocalDate.of(7, 2, 1))) {
            for (period in listOf(
                StatisticsPeriod.Month(YearMonth.from(date)),
                StatisticsPeriod.Year(Year.from(date)),
            )) {
                val kind = StatisticsPeriodKind.from(period)
                val draft = StatisticsPeriodDraft.from(kind, period, date)
                assertEquals(period, draft.period(kind))
            }
        }
    }

    @Test
    fun `invalid drafts are rejected and valid leap and custom dates are inclusive`() {
        val invalid =
            mapOf(
                StatisticsPeriodKind.DAY to StatisticsPeriodDraft("2023-02-29"),
                StatisticsPeriodKind.WEEK to StatisticsPeriodDraft("2024-02"),
                StatisticsPeriodKind.MONTH to StatisticsPeriodDraft("2024-13"),
                StatisticsPeriodKind.YEAR to StatisticsPeriodDraft("202"),
                StatisticsPeriodKind.CUSTOM to StatisticsPeriodDraft("2024-02-29", "2024-02-28"),
            )
        invalid.forEach { (kind, draft) -> assertNull(draft.period(kind)) }
        val leapDate = LocalDate.of(2024, 2, 29)
        assertEquals(
            StatisticsPeriod.Day(leapDate),
            StatisticsPeriodDraft("2024-02-29").period(StatisticsPeriodKind.DAY),
        )
        val week = StatisticsPeriodDraft("2024-02-29").period(StatisticsPeriodKind.WEEK)
        assertEquals(StatisticsPeriod.Week(LocalDate.of(2024, 2, 26)), week)
        assertEquals(LocalDate.of(2024, 3, 3), week?.dateRangeOrNull()?.endDateInclusive)
        val custom = StatisticsPeriodDraft("2024-02-29", "2024-02-29").period(StatisticsPeriodKind.CUSTOM)
        assertEquals(StatisticsPeriod.Custom(leapDate, leapDate), custom)
        assertEquals(1L, custom?.dateRangeOrNull()?.calendarDayCount)
    }
}
