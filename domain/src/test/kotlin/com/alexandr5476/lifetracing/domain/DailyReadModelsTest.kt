package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate

class DailyReadModelsTest {
    @Test
    fun dailyQueryRequiresAnExplicitPositiveHistoryBound() {
        assertThrows<IllegalArgumentException> {
            DailyQuery(LocalDate.of(2026, 8, 20), Instant.EPOCH, 0)
        }
    }
}
