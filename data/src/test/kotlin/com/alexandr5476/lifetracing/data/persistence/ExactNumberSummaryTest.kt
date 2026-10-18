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
