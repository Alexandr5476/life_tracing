package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ExactSignedLongSum
import com.alexandr5476.lifetracing.domain.NumberDistribution
import com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
import java.math.BigInteger

/** Shared exact arithmetic and validation for individual and grouped source-Field readers. */
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
        private const val MAX_SERIALIZED_COUNT_WIDTH = 20

        fun empty(): ExactNumberSummary = ExactNumberSummary(0, BigInteger.ZERO, null, null)

        fun readGrouped(
            rows: List<StatisticsNumberSummaryRow>,
            fieldIds: Set<String>,
        ): Map<String, ExactNumberSummary> {
            require(rows.size <= fieldIds.size) { "Too many Number Field summaries" }
            val summaries = mutableMapOf<String, ExactNumberSummary>()
            rows.forEach { row ->
                require(row.sourceFieldId in fieldIds) { "Number summary has no catalog Field" }
                require(row.sourceFieldId !in summaries) { "Duplicate Number Field summary" }
                require(row.sampleCount > 0) { "Grouped Number summary must contain recorded values" }
                // COUNT renders at most 19 digits, plus separators. Reject malformed serialized projections.
                require(
                    row.bitCounts.length <= Long.SIZE_BITS * MAX_SERIALIZED_COUNT_WIDTH,
                ) { "Oversized Number bit summary" }
                val counts = row.bitCounts.split(',')
                require(counts.size == Long.SIZE_BITS) { "Incomplete Number bit summary" }
                summaries[row.sourceFieldId] =
                    read(
                        counts.mapIndexed { index, count ->
                            StatisticsNumberBitCountRow(
                                index,
                                row.sampleCount,
                                requireNotNull(count.toLongOrNull()) { "Invalid Number bit count" },
                                row.invalidCount,
                                row.minimumScaled,
                                row.maximumScaled,
                            )
                        },
                    )
            }
            return summaries
        }

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
