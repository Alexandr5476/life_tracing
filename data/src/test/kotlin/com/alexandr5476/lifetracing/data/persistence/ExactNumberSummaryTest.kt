package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExactNumberSummaryTest {
    private fun rows(values: List<Long>): List<StatisticsNumberBitCountRow> =
        List(Long.SIZE_BITS) { bit ->
            StatisticsNumberBitCountRow(
                bit,
                values.size.toLong(),
                values.count { (it ushr bit) and 1L == 1L }.toLong(),
                0,
                values.minOrNull(),
                values.maxOrNull(),
            )
        }

    @Test
    fun boundedProjectionPreservesSignedReferenceAndEmptySummary() {
        for (values in listOf(emptyList(), listOf(0L), listOf(-5L, 0L, 8L), listOf(Long.MIN_VALUE, Long.MAX_VALUE))) {
            val sorted = values.sorted()
            val selection =
                StatisticsNumberSelectionRow(
                    values.size.toLong(),
                    if (values.isEmpty()) {
                        0
                    } else if (values.size % 2 == 0) {
                        2
                    } else {
                        1
                    },
                    sorted.getOrNull((sorted.size - 1) / 2),
                    sorted.getOrNull(sorted.size / 2),
                )
            assertEquals(
                StatisticsDistributionCalculator.numbers(values),
                ExactNumberSummary.read(rows(values)).distribution(selection),
            )
        }
    }

    @Test
    fun singleFieldProjectionIncludesEmptyAndUsesTheGroupedExactArithmetic() {
        for (values in listOf(emptyList(), listOf(-1L), listOf(-9L, 0L, 7L), listOf(Long.MIN_VALUE, Long.MAX_VALUE))) {
            val row =
                StatisticsNumberSummaryRow(
                    "field",
                    values.size.toLong(),
                    0,
                    values.minOrNull(),
                    values.maxOrNull(),
                    rows(values).joinToString(",") { it.setBitCount.toString() },
                )
            val sorted = values.sorted()
            val selection =
                StatisticsNumberSelectionRow(
                    values.size.toLong(),
                    if (values.isEmpty()) 0 else 2L - values.size % 2,
                    sorted.getOrNull((sorted.size - 1) / 2),
                    sorted.getOrNull(sorted.size / 2),
                )
            assertEquals(
                StatisticsDistributionCalculator.numbers(values),
                ExactNumberSummary.read(row).distribution(selection),
            )
            assertThrows(IllegalArgumentException::class.java) { ExactNumberSummary.read(row.copy(invalidCount = 1)) }
            assertThrows(IllegalArgumentException::class.java) { ExactNumberSummary.read(row.copy(sampleCount = -1)) }
        }
    }

    @Test
    fun medianReadPolicyProtectsSelectiveReadsAndBoundsDenseLineagesWithoutOverflow() {
        assertEquals(false, NumberMedianReadPolicy.isBroad(0, 0))
        assertEquals(false, NumberMedianReadPolicy.isBroad(2, 10_000))
        assertEquals(false, NumberMedianReadPolicy.isBroad(2, 5))
        assertEquals(true, NumberMedianReadPolicy.isBroad(3, 5))
        assertEquals(false, NumberMedianReadPolicy.useFieldFirst(3, 5, 4))
        assertEquals(true, NumberMedianReadPolicy.useFieldFirst(3, 5, 3))
        assertEquals(false, NumberMedianReadPolicy.useFieldFirst(2, 10_000, 1))
        assertEquals(false, NumberMedianReadPolicy.isBroad(Long.MAX_VALUE / 2, Long.MAX_VALUE))
        assertEquals(true, NumberMedianReadPolicy.isBroad(Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, NumberMedianReadPolicy.lineageLimit(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, NumberMedianReadPolicy.lineageLimit(Long.MAX_VALUE - 1))
        assertEquals(2L, NumberMedianReadPolicy.lineageLimit(1))
        assertThrows(IllegalArgumentException::class.java) { NumberMedianReadPolicy.isBroad(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { NumberMedianReadPolicy.lineageLimit(0) }
        assertThrows(IllegalArgumentException::class.java) { NumberMedianReadPolicy.useFieldFirst(1, 1, -1) }
    }

    @Test
    fun malformedBitRowsAreRejectedAtBoundary() {
        val valid = rows(listOf(-1L, 1L))
        val invalid =
            listOf(
                emptyList(),
                valid.dropLast(1),
                valid + valid.last(),
                valid.reversed(),
                valid.toMutableList().also { it[1] = it[0] },
                valid.toMutableList().also { it[1] = it[1].copy(sampleCount = 3) },
                valid.toMutableList().also { it[1] = it[1].copy(minimumScaled = -2) },
                valid.toMutableList().also { it[1] = it[1].copy(maximumScaled = 2) },
                valid.toMutableList().also { it[1] = it[1].copy(setBitCount = 3) },
                valid.toMutableList().also { it[1] = it[1].copy(setBitCount = -1) },
            )
        invalid.forEach { projection ->
            assertThrows(IllegalArgumentException::class.java) { ExactNumberSummary.read(projection) }
        }
        assertThrows(IllegalArgumentException::class.java) { valid.first().copy(invalidCount = 1) }
    }

    @Test
    fun groupedProjectionSharesExactArithmeticAndRejectsMalformedIdentityOrCounts() {
        val values = listOf(Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, 0L, 7L)
        val row =
            StatisticsNumberSummaryRow(
                "field",
                values.size.toLong(),
                0,
                values.minOrNull(),
                values.maxOrNull(),
                rows(values).joinToString(",") { it.setBitCount.toString() },
            )
        val summary = ExactNumberSummary.readGrouped(listOf(row), setOf("field", "empty")).getValue("field")
        assertEquals(
            StatisticsDistributionCalculator.numbers(values),
            summary.distribution(StatisticsNumberSelectionRow(5, 1, 0, 0)),
        )
        assertEquals(
            emptyMap<String, ExactNumberSummary>(),
            ExactNumberSummary.readGrouped(emptyList(), setOf("field")),
        )
        for (bad in listOf(
            row.copy(sourceFieldId = "unknown"),
            row.copy(sampleCount = 0),
            row.copy(sampleCount = -1),
            row.copy(invalidCount = 1),
            row.copy(bitCounts = "0"),
            row.copy(bitCounts = row.bitCounts + ",0"),
            row.copy(bitCounts = "broken," + row.bitCounts.substringAfter(',')),
            row.copy(bitCounts = List(64) { "-1" }.joinToString(",")),
            row.copy(bitCounts = List(64) { "6" }.joinToString(",")),
            row.copy(bitCounts = "0".repeat(1_281)),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ExactNumberSummary.readGrouped(listOf(bad), setOf("field"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExactNumberSummary.readGrouped(listOf(row, row), setOf("field", "empty"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExactNumberSummary.readGrouped(listOf(row), emptySet())
        }
    }

    @Test
    fun contradictoryMedianSelectionIsRejected() {
        val summary = ExactNumberSummary.read(rows(listOf(-1L, 1L)))
        for (selection in listOf(
            StatisticsNumberSelectionRow(0, 0, null, null),
            StatisticsNumberSelectionRow(2, 1, -1, 1),
            StatisticsNumberSelectionRow(2, 2, null, 1),
            StatisticsNumberSelectionRow(2, 2, -1, null),
            StatisticsNumberSelectionRow(2, 2, 1, -1),
        )) {
            assertThrows(IllegalArgumentException::class.java) { summary.distribution(selection) }
        }
        val empty = ExactNumberSummary.read(rows(emptyList()))
        assertThrows(
            IllegalArgumentException::class.java,
        ) { empty.distribution(StatisticsNumberSelectionRow(0, 0, 0, 0)) }
        val odd = ExactNumberSummary.read(rows(listOf(0L)))
        assertThrows(
            IllegalArgumentException::class.java,
        ) { odd.distribution(StatisticsNumberSelectionRow(1, 1, 0, 1)) }
    }
}
