package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
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
    fun medianReadPolicyBoundsFieldAndValueFanoutWithoutGlobalMajorityOrOverflow() {
        // Dense minority and singleton sparse Fields use their exclusive lineage.
        assertEquals(true, NumberMedianReadPolicy.useFieldFirst(800, 800, 800))
        assertEquals(true, NumberMedianReadPolicy.useFieldFirst(1, 800, 1))
        assertEquals(true, NumberMedianReadPolicy.useFieldFirst(1, 800, 1_600))
        assertEquals(false, NumberMedianReadPolicy.useFieldFirst(1, 800, 1_601))
        // Value candidates, rather than global executions or sample density, set the budget.
        assertEquals(true, NumberMedianReadPolicy.useFieldFirst(1, 19_200, 20_000))
        // Tiny new Series and narrow periods stop probing a large shared lineage.
        assertEquals(false, NumberMedianReadPolicy.useFieldFirst(3, 72, 145))
        assertEquals(false, NumberMedianReadPolicy.useFieldFirst(3, 3, 7))
        assertEquals(false, NumberMedianReadPolicy.useFieldFirst(1, 1, 3))
        assertEquals(false, NumberMedianReadPolicy.useFieldFirst(0, 0, 0))
        assertEquals(3L, NumberMedianReadPolicy.candidateLimit(1))
        assertEquals(Long.MAX_VALUE, NumberMedianReadPolicy.candidateLimit(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, NumberMedianReadPolicy.candidateLimit(Long.MAX_VALUE / 2))
        assertEquals(Long.MAX_VALUE - 2, NumberMedianReadPolicy.candidateLimit(Long.MAX_VALUE / 2 - 1))
        assertEquals(false, NumberMedianReadPolicy.useFieldFirst(1, Long.MAX_VALUE, Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { NumberMedianReadPolicy.candidateLimit(0) }
        assertThrows(IllegalArgumentException::class.java) { NumberMedianReadPolicy.candidateLimit(-1) }
        assertThrows(IllegalArgumentException::class.java) { NumberMedianReadPolicy.useFieldFirst(2, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { NumberMedianReadPolicy.useFieldFirst(1, 1, -1) }
    }

    @Test
    fun moderateSharedLineagesRemainIndexedAcrossTheFairAllowanceBoundary() {
        for (fields in listOf(16, 17, 24)) {
            for (oldExecutions in listOf(999, 1_000, 1_001, 1_002)) {
                val budget = NumberMedianProbeBudget(1_000L * fields, fields, workMultiplier = 15)
                repeat(fields) {
                    assertEquals(true, budget.record(1_000L + oldExecutions))
                }
                assertEquals(null, budget.nextLimit)
            }
        }
    }

    @Test
    fun indexedSortAllowanceIsFairBoundedAndSaturatesWithoutOverflow() {
        for (fields in listOf(16, 17, 24)) {
            val executions = 1_000L * fields
            val cap = 15 * executions / fields + 1
            for (expensiveFirst in listOf(true, false)) {
                val budget = NumberMedianProbeBudget(executions, fields, workMultiplier = 15)
                val counts = List(fields - 1) { 2_001L }.toMutableList()
                if (expensiveFirst) counts.add(0, cap) else counts.add(cap)
                counts.forEach { count -> assertEquals(count < cap, budget.record(count)) }
                assertEquals(true, counts.sum() <= 15 * executions + fields)
            }
        }
        assertEquals(
            Long.MAX_VALUE,
            NumberMedianProbeBudget(Long.MAX_VALUE, 2, workMultiplier = 15).nextLimit,
        )
        assertThrows(IllegalArgumentException::class.java) { NumberMedianProbeBudget(1, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { NumberMedianProbeBudget(1, 1, 17) }
    }

    @Test
    fun groupedProbeBudgetIncludesFailedProbesAndBoundsAllFieldsTogether() {
        val budget = NumberMedianProbeBudget(2_400, 24)
        assertEquals(201L, budget.nextLimit)
        assertEquals(false, budget.record(201))
        repeat(23) {
            assertEquals(201L, budget.nextLimit)
            assertEquals(true, budget.record(100))
        }
        assertEquals(null, budget.nextLimit)
        assertThrows(IllegalArgumentException::class.java) { budget.record(0) }
        val last = NumberMedianProbeBudget(2_400, 24)
        repeat(23) { assertEquals(true, last.record(100)) }
        assertEquals(false, last.record(201))
        assertEquals(null, last.nextLimit)
        val huge = NumberMedianProbeBudget(Long.MAX_VALUE, 1)
        assertEquals(Long.MAX_VALUE, huge.nextLimit)
        assertEquals(false, huge.record(Long.MAX_VALUE))
        val exact = NumberMedianProbeBudget(1, 1)
        assertEquals(true, exact.record(2))
        assertEquals(null, exact.nextLimit)
        assertThrows(IllegalArgumentException::class.java) { NumberMedianProbeBudget(1, 0) }
        val many = NumberMedianProbeBudget(1, 100)
        repeat(100) {
            assertEquals(1L, many.nextLimit)
            assertEquals(false, many.record(1))
        }
    }

    @Test
    fun histogramsBoundSignedWorstCaseAndRefineBytesWithExactAdjacentRanks() {
        val signed = signedRankSamples()
        val random = kotlin.random.Random(37)
        val samples =
            (
                listOf(
                    signed,
                    signed.dropLast(1),
                    signed + 1L,
                    listOf(Long.MIN_VALUE),
                    listOf(Long.MAX_VALUE),
                    listOf(-2L, -1L),
                    listOf(0L),
                    listOf(Long.MIN_VALUE, Long.MAX_VALUE),
                    List(10_000) { 7L },
                    List(100_000) { random.nextLong() },
                    byteWorstSamples(),
                ) +
                    List(100) { List(200 + it % 2) { random.nextLong() } } +
                    List(100) { List(200 + it % 2) { Long.MAX_VALUE - random.nextLong(1L shl 55) } } +
                    List(100) { List(200 + it % 2) { Long.MIN_VALUE + random.nextLong(1L shl 55) } }
            ).mapIndexed { index, values -> "$index" to values }
                .toMap()
        val summaries = samples.mapValues { ExactNumberSummary.read(rows(it.value)) }
        var passes = 0
        var bytePasses = 0
        val selections =
            GroupedNumberHistograms(summaries).select { ranges, shift ->
                passes++
                if (shift != null) bytePasses++
                val buckets = histogramRows(samples, ranges, shift)
                assertEquals(true, buckets.size <= 256 * ranges.size)
                buckets
            }
        assertEquals(9, passes)
        assertEquals(true, bytePasses > 0)
        samples.forEach { (field, values) ->
            assertEquals(
                StatisticsDistributionCalculator.numbers(values),
                summaries.getValue(field).distribution(selections.getValue(field)),
            )
        }
    }

    @Test
    fun magnitudeHistogramResolvesSignedRankAdversaryInOneTraversal() {
        val signed = signedRankSamples()
        val signedSummary = ExactNumberSummary.read(rows(signed))
        var rankPasses = 0
        GroupedNumberMedians(mapOf("signed" to signedSummary)).select { pivots ->
            rankPasses++
            listOf(rankRow("signed", signed, pivots.getValue("signed")))
        }
        assertEquals(64, rankPasses)
        var signedPasses = 0
        GroupedNumberHistograms(mapOf("signed" to signedSummary)).select { ranges, shift ->
            signedPasses++
            histogramRows(mapOf("signed" to signed), ranges, shift)
        }
        assertEquals(1, signedPasses)
        assertEquals(
            emptyMap<String, StatisticsNumberSelectionRow>(),
            GroupedNumberHistograms(emptyMap()).select { _, _ ->
                error("Empty summary must not read")
            },
        )
    }

    private fun byteWorstSamples(): List<Long> {
        val base = 1L shl 62
        return List(99) { base } + listOf(base + 1) +
            (1..61).map { base + (1L shl it) } + listOf(Long.MAX_VALUE) + List(38) { base + 2 }
    }

    private fun signedRankSamples(): List<Long> =
        List(200) { index ->
            when {
                index < 99 -> Long.MIN_VALUE
                index == 99 -> 0L
                index <= 162 -> 1L shl (index - 100)
                index == 163 -> Long.MAX_VALUE
                else -> 1L
            }
        }

    private fun histogramRows(
        samples: Map<String, List<Long>>,
        ranges: Map<String, LongRange>,
        shift: Int?,
    ): List<StatisticsNumberBucketRow> =
        ranges.flatMap { (field, range) ->
            samples
                .getValue(field)
                .filter { it in range }
                .groupBy { value ->
                    if (shift == null) {
                        if (value < 0) {
                            63 - (64 - java.lang.Long.numberOfLeadingZeros(value.inv()))
                        } else {
                            64 + (64 - java.lang.Long.numberOfLeadingZeros(value))
                        }
                    } else {
                        ((value ushr shift) and 255).toInt()
                    }
                }.toSortedMap()
                .map { (bucket, values) ->
                    StatisticsNumberBucketRow(field, bucket, values.size.toLong(), values.min(), values.max())
                }
        }

    @Test
    fun histogramsRejectIncoherentCountsFieldsAndUnorderedRanges() {
        val samples = mapOf("field" to listOf(-1L, 1L))
        val summary = mapOf("field" to ExactNumberSummary.read(rows(samples.getValue("field"))))
        val valid = histogramRows(samples, mapOf("field" to -1L..1L), null)
        for (invalid in listOf(
            emptyList(),
            valid + valid.first(),
            valid.reversed(),
            valid.map { it.copy(sourceFieldId = "foreign") },
            valid.map { it.copy(sampleCount = 0) },
            valid.map { it.copy(sampleCount = 2) },
            valid.map { it.copy(bucketId = 256) },
            valid.map { it.copy(minimumScaled = Long.MIN_VALUE) },
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                GroupedNumberHistograms(summary).select { _, _ -> invalid }
            }
        }
    }

    @Test
    fun histogramQueriesUseFixedBindsSafeIdentitiesAndIntegerApi26Sql() {
        for (kind in listOf(
            StatisticsSeriesKind.ACTIVITY,
            StatisticsSeriesKind.SEQUENCE,
        )) {
            for (shift in listOf(null, 56, 0)) {
                val query =
                    groupedNumberHistogramQuery(
                        kind,
                        "series",
                        null to null,
                        mapOf("opaque'??" to Long.MIN_VALUE..Long.MAX_VALUE),
                        shift,
                    )
                assertEquals(4, query.argCount)
                assertEquals(false, query.sql.contains("opaque'??"))
                assertEquals(false, query.sql.contains("OVER ("))
                assertEquals(true, query.sql.contains("GROUP BY fields.source_field_id, bucket_id"))
            }
        }
    }

    @Test
    fun groupedTrialReturnsOnlyResolvedFieldsForBoundedScalarCompletion() {
        val broadSamples =
            (0..15).associate { field ->
                "$field" to List(1_000) { java.util.Random(37L + it * 1_000_003L + field).nextLong() }
            }
        val samples = broadSamples + ("resolved" to List(1_000) { 7L })
        val summaries = samples.mapValues { ExactNumberSummary.read(rows(it.value)) }
        var queries = 0
        val selected =
            GroupedNumberMedians(summaries).select(3) { pivots ->
                queries++
                pivots.map { (field, pivot) -> rankRow(field, samples.getValue(field), pivot) }
            }
        assertEquals(3, queries)
        assertEquals(true, "resolved" in selected)
        assertEquals(true, selected.size < samples.size)
        selected.forEach { (field, selection) ->
            assertEquals(
                StatisticsDistributionCalculator.numbers(samples.getValue(field)),
                summaries.getValue(field).distribution(selection),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupedNumberMedians(summaries).select(0) { emptyList() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupedNumberMedians(summaries).select(66) { emptyList() }
        }
    }

    @Test
    fun groupedRanksPreserveExactMediansForSignedBoundariesDuplicatesAndLargeSamples() {
        val random = kotlin.random.Random(37)
        val fixtures =
            listOf(
                listOf(0L),
                listOf(Long.MIN_VALUE, Long.MAX_VALUE),
                listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE),
                List(100_000) { 7L },
                List(101) { if (it == 0) Long.MIN_VALUE else Long.MAX_VALUE },
                listOf(Long.MIN_VALUE),
                listOf(Long.MAX_VALUE),
                listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1),
                listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE),
                listOf(-2L, -1L),
            ) + List(200) { List(1 + random.nextInt(300)) { random.nextLong() } }
        val samples = fixtures.mapIndexed { index, values -> "$index" to values }.toMap()
        val summaries = samples.mapValues { ExactNumberSummary.read(rows(it.value)) }
        var queries = 0
        val selections =
            GroupedNumberMedians(summaries).select { pivots ->
                queries++
                if (queries == 1) {
                    val expectedPivots =
                        listOf(
                            0L,
                            -1L,
                            -1L,
                            7L,
                            -1L,
                            Long.MIN_VALUE,
                            Long.MAX_VALUE,
                            Long.MIN_VALUE,
                            Long.MAX_VALUE - 1,
                            -2L,
                        )
                    expectedPivots.forEachIndexed { index, expected ->
                        assertEquals(expected, pivots.getValue("$index"))
                    }
                }
                pivots.map { (field, pivot) -> rankRow(field, samples.getValue(field), pivot) }
            }
        assertEquals(true, queries in 1..65)
        samples.forEach { (field, values) ->
            assertEquals(
                StatisticsDistributionCalculator.numbers(values),
                summaries.getValue(field).distribution(selections.getValue(field)),
            )
        }
        assertEquals(
            emptyMap<String, StatisticsNumberSelectionRow>(),
            GroupedNumberMedians(emptyMap()).select {
                error("No reads")
            },
        )
    }

    @Test
    fun groupedRanksRejectMissingDuplicateUnknownAndIncompatiblePersistedCounts() {
        val summary = ExactNumberSummary.read(rows(listOf(-1L, 1L)))
        val row = rankRow("field", listOf(-1L, 1L), 0)
        for (invalid in listOf(
            emptyList(),
            listOf(row, row),
            listOf(row.copy(sourceFieldId = "unknown")),
            listOf(row.copy(sampleCount = 1)),
            listOf(row.copy(atOrBelowCount = -1)),
            listOf(row.copy(atOrBelowCount = 3)),
            listOf(row.copy(maximumAtOrBelow = null)),
            listOf(row.copy(minimumAbove = null)),
            listOf(row.copy(maximumAtOrBelow = 1)),
            listOf(row.copy(minimumAbove = -1)),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                GroupedNumberMedians(mapOf("field" to summary)).select { invalid }
            }
        }
    }

    @Test
    fun groupedRankQueryBoundsBindsAndUsesBalancedSafeOpaqueIdentityLookup() {
        val pivots = (0..1_200).associate { "quote'/$it/\uD83D\uDE00" to (it.toLong() - 600) }
        val query =
            groupedNumberRankQuery(
                com.alexandr5476.lifetracing.domain.StatisticsSeriesKind.ACTIVITY,
                "series",
                null to null,
                pivots,
            )
        assertEquals(4, query.argCount)
        assertEquals(false, query.sql.contains("quote'"))
        assertEquals(false, query.sql.contains("OVER ("))
        assertEquals(3 * (pivots.size - 1), Regex("COLLATE BINARY").findAll(query.sql).count())
    }

    private fun rankRow(
        field: String,
        values: List<Long>,
        pivot: Long,
    ): StatisticsNumberRankRow =
        StatisticsNumberRankRow(
            field,
            values.size.toLong(),
            values.count { it <= pivot }.toLong(),
            values.filter { it <= pivot }.maxOrNull(),
            values.filter { it > pivot }.minOrNull(),
        )

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
