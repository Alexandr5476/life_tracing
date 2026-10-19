package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ExactSignedLongSum
import com.alexandr5476.lifetracing.domain.NumberDistribution
import com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
import java.math.BigInteger

/** Shared exact arithmetic and validation for individual and grouped source-Field readers. */
internal class ExactNumberSummary private constructor(
    val sampleCount: Long,
    private val total: BigInteger,
    val minimum: Long?,
    val maximum: Long?,
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
                summaries[row.sourceFieldId] = read(row)
            }
            return summaries
        }

        /** The single-Field aggregate also returns one row when no values are recorded. */
        fun read(row: StatisticsNumberSummaryRow): ExactNumberSummary {
            require(row.sampleCount >= 0) { "Negative Number sample count" }
            // COUNT renders at most 19 digits, plus separators; no sample serialization.
            require(row.bitCounts.length <= Long.SIZE_BITS * MAX_SERIALIZED_COUNT_WIDTH) {
                "Oversized Number bit summary"
            }
            val counts = row.bitCounts.split(',')
            require(counts.size == Long.SIZE_BITS) { "Incomplete Number bit summary" }
            return read(
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

/** Compare indexed Field/value candidates with selected execution work, never global history size. */
internal object NumberMedianReadPolicy {
    /** Cap Field/value candidates at twice the selected execution/value work plus one sentinel. */
    fun candidateLimit(executionCandidateCount: Long): Long {
        require(executionCandidateCount > 0)
        return if (executionCandidateCount >= Long.MAX_VALUE / 2) Long.MAX_VALUE else executionCandidateCount * 2 + 1
    }

    fun useFieldFirst(
        sampleCount: Long,
        executionCandidateCount: Long,
        boundedCandidateCount: Long,
    ): Boolean {
        require(sampleCount >= 0 && executionCandidateCount >= sampleCount && boundedCandidateCount >= 0)
        return sampleCount > 0 && boundedCandidateCount < candidateLimit(executionCandidateCount)
    }
}

/** Equal capped allowances: a saturated lineage cannot starve another Field, regardless of order.
 * Total visited candidates are at most 2Q + P, including one sentinel per populated Field.
 */
internal class NumberMedianProbeBudget(
    executionCandidates: Long,
    fieldCount: Int,
) {
    private var fieldsRemaining = fieldCount.also { require(it > 0) }
    private val limit = (NumberMedianReadPolicy.candidateLimit(executionCandidates) - 1) / fieldCount + 1

    val nextLimit: Long?
        get() = if (fieldsRemaining == 0) null else limit

    fun record(candidates: Long): Boolean {
        require(nextLimit != null) { "Number median probe budget exhausted" }
        require(candidates in 0..limit) { "Candidate probe exceeded its limit" }
        fieldsRemaining--
        return candidates < limit
    }
}
