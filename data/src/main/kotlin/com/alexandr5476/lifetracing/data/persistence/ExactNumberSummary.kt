package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ExactSignedLongSum
import com.alexandr5476.lifetracing.domain.NumberDistribution
import com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
import java.math.BigInteger

/** Field-independent bounded summary; future grouped readers can use one instance per source identity. */
internal class ExactNumberSummary private constructor(
    val sampleCount: Long,
    private val total: BigInteger,
    private val minimum: Long?,
    private val maximum: Long?,
) {
    fun distribution(selection: StatisticsNumberSelectionRow): NumberDistribution {
        require(selection.sampleCount == sampleCount) { "Number aggregate disagrees with eligible sample count" }
        val expected =
            if (sampleCount == 0L) {
                0
            } else if (sampleCount % 2 == 0L) {
                2
            } else {
                1
            }
        require(selection.selectedCount == expected.toLong()) { "Incomplete Number median selection" }
        val endpoints =
            when (expected) {
                0 -> {
                    require(selection.lowerMedianScaled == null && selection.upperMedianScaled == null)
                    emptyList()
                }
                1 -> {
                    require(selection.lowerMedianScaled == selection.upperMedianScaled)
                    listOf(requireNotNull(selection.lowerMedianScaled))
                }
                else -> listOf(requireNotNull(selection.lowerMedianScaled), requireNotNull(selection.upperMedianScaled))
            }
        return StatisticsDistributionCalculator.numbers(sampleCount, total, minimum, maximum, endpoints)
    }

    companion object {
        fun read(rows: List<StatisticsNumberBitCountRow>): ExactNumberSummary {
            require(rows.size == Long.SIZE_BITS) { "Incomplete Number bit summary" }
            val first = rows.first()
            rows.forEachIndexed { index, row ->
                require(row.bitIndex == index) { "Missing, duplicate or unordered Number bit position" }
                require(row.sampleCount == first.sampleCount && row.minimumScaled == first.minimumScaled)
                require(row.maximumScaled == first.maximumScaled && row.invalidCount == 0L)
            }
            val total = ExactSignedLongSum.fromBitCounts(first.sampleCount, rows.map { it.setBitCount })
            return ExactNumberSummary(first.sampleCount, total, first.minimumScaled, first.maximumScaled)
        }
    }
}
