package com.alexandr5476.lifetracing.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HistoryNumberFormatTest {
    @Test
    fun finerStoredFactsUseDisplayPrecisionWithoutRequiringExactConversion() {
        assertEquals("12.6 kg", numberText(12555, 1, "kg"))
        assertEquals("-12.6 kg", numberText(-12555, 1, "kg"))
        assertEquals("13", numberText(12555, 0, null))
        assertEquals("12.555", numberText(12555, null, null))
        assertEquals("0 kg", numberText(0, 1, "kg"))
    }
}
