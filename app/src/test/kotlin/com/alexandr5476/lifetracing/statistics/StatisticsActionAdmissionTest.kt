package com.alexandr5476.lifetracing.statistics

import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.YearMonth

class StatisticsActionAdmissionTest {
    @Test
    fun equalPublicationNeedsItsOwnAdmission() {
        val publication = StatisticsPresentationState(StatisticsPeriod.AllTime)
        var current = publication
        val old = StatisticsActionAdmission(publication, { current }, { true })
        current = publication.copy()
        assertEquals(publication, current)
        assertFalse(old.run(true) { error("Obsolete action was dispatched") })

        var selected: StatisticsPeriod? = null
        val fresh = StatisticsActionAdmission(current, { current }, { true })
        val month = StatisticsPeriod.Month(YearMonth.of(2026, 8))
        assertFalse(fresh.run(false) { error("Disabled action was dispatched") })
        assertTrue(fresh.run(true) { selected = month })
        assertEquals(month, selected)
    }

    @Test
    fun routeLossAndDetachmentArePermanentForAnIssuedAdmission() {
        val publication = StatisticsPresentationState(StatisticsPeriod.AllTime)
        var ownsRoute = true
        val old = StatisticsActionAdmission(publication, { publication }, { ownsRoute })
        ownsRoute = false
        assertFalse(old.run(true) { error("Hidden action was dispatched") })
        ownsRoute = true
        assertFalse(old.run(true) { error("Revoked action was dispatched on re-entry") })

        val fresh = StatisticsActionAdmission(publication, { publication }, { ownsRoute })
        assertTrue(fresh.run(true) {})
        fresh.detach()
        assertFalse(fresh.run(true) { error("Detached action was dispatched") })
    }
}
