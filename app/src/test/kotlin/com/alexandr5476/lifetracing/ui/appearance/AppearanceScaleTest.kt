package com.alexandr5476.lifetracing.ui.appearance

import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppearanceScaleTest {
    @Test
    fun legal_scale_sets_are_exact_and_every_value_is_accepted() {
        val interfaceValues = listOf(90, 95, 100, 105, 110, 115, 120)
        val textValues = listOf(90, 95, 100, 105, 110, 115, 120, 125, 130)
        assertEquals(interfaceValues, AppearanceScalePolicy.interfacePercentages)
        assertEquals(textValues, AppearanceScalePolicy.textPercentages)
        interfaceValues.forEach { percent ->
            assertEquals(percent, AppearancePreferences(interfaceScalePercent = percent).interfaceScalePercent)
            assertEquals(percent, AppearanceScalePolicy.interfaceFromStorage(percent))
        }
        textValues.forEach { percent ->
            assertEquals(percent, AppearancePreferences(textScalePercent = percent).textScalePercent)
            assertEquals(percent, AppearanceScalePolicy.textFromStorage(percent))
        }
    }

    @Test
    fun invalid_scales_are_rejected_in_memory_and_only_storage_readers_fall_back() {
        listOf(Int.MIN_VALUE, 0, 85, 91, 99, 121, 125, Int.MAX_VALUE).forEach { percent ->
            assertThrows(IllegalArgumentException::class.java) {
                AppearancePreferences(interfaceScalePercent = percent)
            }
            assertEquals(100, AppearanceScalePolicy.interfaceFromStorage(percent))
        }
        listOf(Int.MIN_VALUE, 0, 85, 91, 99, 131, 135, Int.MAX_VALUE).forEach { percent ->
            assertThrows(IllegalArgumentException::class.java) {
                AppearancePreferences(textScalePercent = percent)
            }
            assertEquals(100, AppearanceScalePolicy.textFromStorage(percent))
        }
        assertEquals(100, AppearanceScalePolicy.interfaceFromStorage(null))
        assertEquals(100, AppearanceScalePolicy.textFromStorage(null))
        assertEquals(100, AppearancePreferences().interfaceScalePercent)
        assertEquals(100, AppearancePreferences().textScalePercent)
    }

    @Test
    fun density_composes_with_both_system_components_and_repeated_application_is_stable() {
        val parent = Density(density = 2.75f, fontScale = 1.4f)
        val appearance = AppearancePreferences(interfaceScalePercent = 115, textScalePercent = 130)
        repeat(5) {
            val effective = appearance.scaledDensity(parent)
            assertEquals(2.75f * 1.15f, effective.density)
            assertEquals(1.4f * 1.3f, effective.fontScale)
        }
        val changedParent = Density(density = 3.25f, fontScale = 1.65f)
        val effective = appearance.scaledDensity(changedParent)
        assertEquals(3.25f * 1.15f, effective.density)
        assertEquals(1.65f * 1.3f, effective.fontScale)
    }

    @Test
    fun default_scale_preserves_system_values_exactly() {
        val parent = Density(density = 2.713f, fontScale = 1.437f)
        val effective = AppearancePreferences().scaledDensity(parent)
        assertEquals(parent.density, effective.density)
        assertEquals(parent.fontScale, effective.fontScale)
    }
}
