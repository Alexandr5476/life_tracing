@file:Suppress("MagicNumber")

package com.alexandr5476.lifetracing.ui.appearance

/** Shared presentation policy for persistence and future Settings controls. */
object AppearanceScalePolicy {
    const val DEFAULT_PERCENT = 100

    val interfacePercentages: List<Int> = listOf(90, 95, 100, 105, 110, 115, 120)
    val textPercentages: List<Int> = listOf(90, 95, 100, 105, 110, 115, 120, 125, 130)

    fun interfaceFromStorage(percent: Int?): Int = percent?.takeIf { it in interfacePercentages } ?: DEFAULT_PERCENT

    fun textFromStorage(percent: Int?): Int = percent?.takeIf { it in textPercentages } ?: DEFAULT_PERCENT
}
