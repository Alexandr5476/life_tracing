package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class HistoryReadModelsTest {
    @Test
    fun historyQueryRequiresOrderedDatesAndPositiveLimit() {
        assertThrows<IllegalArgumentException> {
            HistoryDateRange(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 20))
        }
        assertThrows<IllegalArgumentException> {
            CompletedHistoryQuery(
                HistoryDateRange(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 20)),
                0,
            )
        }
    }
}
