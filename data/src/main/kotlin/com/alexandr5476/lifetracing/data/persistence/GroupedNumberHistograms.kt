package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind

internal data class StatisticsNumberBucketRow(
    @ColumnInfo(name = "source_field_id") val sourceFieldId: String,
    @ColumnInfo(name = "bucket_id") val bucketId: Int,
    @ColumnInfo(name = "sample_count") val sampleCount: Long,
    @ColumnInfo(name = "minimum_scaled") val minimumScaled: Long,
    @ColumnInfo(name = "maximum_scaled") val maximumScaled: Long,
)

/**
 * Exact completion after a short rank trial. A magnitude histogram separates zero and powers
 * of two without bisecting empty signed-Long ranges. Remaining adjacent ranks share at most
 * one bucket per Field; byte passes remove eight remaining value bits at a time.
 * At most 9 traversals and 256 rows per Field, independently of samples and their distribution.
 */
internal class GroupedNumberHistograms(
    summaries: Map<String, ExactNumberSummary>,
) {
    private val searches = summaries.mapValues { Search(it.value) }.toMutableMap()

    fun select(
        read: (Map<String, LongRange>, Int?) -> List<StatisticsNumberBucketRow>,
    ): Map<String, StatisticsNumberSelectionRow> {
        val result = mutableMapOf<String, StatisticsNumberSelectionRow>()
        repeat(MAX_PASSES) { pass ->
            if (searches.isNotEmpty()) {
                val ranges = searches.mapValues { it.value.range }
                val shift = if (pass == 0) null else ranges.values.maxOf { byteShift(it) }
                val rows = read(ranges, shift)
                require(rows.size <= searches.size.toLong() * MAX_BUCKETS) { "Oversized Number histogram" }
                val grouped = rows.groupBy { it.sourceFieldId }
                require(grouped.keys == searches.keys) { "Number histogram disagrees with summary Fields" }
                grouped.forEach { (field, buckets) ->
                    searches.getValue(field).advance(buckets)?.let { selection ->
                        result[field] = selection
                        searches.remove(field)
                    }
                }
            }
        }
        require(searches.isEmpty()) { "Number histogram did not converge" }
        return result
    }

    private class Search(
        summary: ExactNumberSummary,
    ) {
        private val sampleCount = summary.sampleCount
        private var rangeCount = sampleCount
        var range = requireNotNull(summary.minimum)..requireNotNull(summary.maximum)
            private set
        private var ranks = listOf((sampleCount - 1) / 2, sampleCount / 2)
        private val endpoints = arrayOfNulls<Long>(2)

        fun advance(buckets: List<StatisticsNumberBucketRow>): StatisticsNumberSelectionRow? {
            require(buckets.size in 1..MAX_BUCKETS)
            var preceding = 0L
            var previous: StatisticsNumberBucketRow? = null
            var nextRange: LongRange? = null
            var nextCount = 0L
            val nextRanks = ranks.toMutableList()
            buckets.forEach { bucket ->
                require(bucket.bucketId in 0 until MAX_BUCKETS && bucket.sampleCount > 0)
                require(bucket.minimumScaled in range && bucket.maximumScaled in bucket.minimumScaled..range.last)
                previous?.let {
                    require(it.bucketId < bucket.bucketId && it.maximumScaled < bucket.minimumScaled)
                }
                val end = Math.addExact(preceding, bucket.sampleCount)
                ranks.forEachIndexed { index, rank ->
                    if (endpoints[index] == null && rank in preceding until end) {
                        val localRank = rank - preceding
                        endpoints[index] = endpoint(bucket, localRank)
                        if (endpoints[index] == null) {
                            val pending = bucket.minimumScaled..bucket.maximumScaled
                            require(nextRange == null || nextRange == pending) { "Nonadjacent Number median ranks" }
                            nextRange = pending
                            nextCount = bucket.sampleCount
                            nextRanks[index] = localRank
                        }
                    }
                }
                preceding = end
                previous = bucket
            }
            require(preceding == rangeCount) { "Number histogram count mismatch" }
            if (endpoints.all { it != null }) {
                return StatisticsNumberSelectionRow(sampleCount, 2 - sampleCount % 2, endpoints[0], endpoints[1])
            }
            range = requireNotNull(nextRange)
            rangeCount = nextCount
            ranks = nextRanks
            return null
        }

        private fun endpoint(
            bucket: StatisticsNumberBucketRow,
            rank: Long,
        ): Long? =
            when {
                rank == 0L || bucket.minimumScaled == bucket.maximumScaled -> bucket.minimumScaled
                rank == bucket.sampleCount - 1 -> bucket.maximumScaled
                else -> null
            }
    }

    companion object {
        const val MAX_PASSES = 9
        const val MAX_BUCKETS = 256

        private fun byteShift(range: LongRange): Int =
            ((Long.SIZE_BITS - 1 - java.lang.Long.numberOfLeadingZeros(range.first xor range.last)) / Byte.SIZE_BITS) *
                Byte.SIZE_BITS
    }
}

/** Execution-first, API-26-compatible GROUP BY; no sample cursor or window functions. */
internal fun groupedNumberHistogramQuery(
    kind: StatisticsSeriesKind,
    seriesId: String,
    bounds: Pair<String?, String?>,
    ranges: Map<String, LongRange>,
    shift: Int?,
): SupportSQLiteQuery {
    require(ranges.isNotEmpty())
    require(shift == null || shift in 0..(Long.SIZE_BITS - Byte.SIZE_BITS) && shift % Byte.SIZE_BITS == 0)
    val table =
        when (kind) {
            StatisticsSeriesKind.ACTIVITY -> "activity"
            StatisticsSeriesKind.SEQUENCE -> "sequence"
            StatisticsSeriesKind.ONE_OFF_BUCKET -> error("One-off Series has no reusable Fields")
        }
    val encoded = ranges.mapKeys { (id, _) -> id.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) } }
    val keys = encoded.keys.sorted()

    fun rangeExpression(
        start: Int,
        end: Int,
        lower: Boolean,
    ): String {
        if (end - start == 1) {
            val range = encoded.getValue(keys[start])
            return (if (lower) range.first else range.last).toString()
        }
        val middle = (start + end) / 2
        return "CASE WHEN fields.source_field_id COLLATE BINARY < CAST(X'${keys[middle]}' AS TEXT) " +
            "THEN ${rangeExpression(start, middle, lower)} ELSE ${rangeExpression(middle, end, lower)} END"
    }

    // Ordered magnitude bins: negative powers, zero, positive powers. All arithmetic is signed integer.
    val thresholds =
        listOf(Long.MIN_VALUE) + ((Long.SIZE_BITS - 2) downTo 0).map { -(1L shl it) } +
            listOf(0L) + (0..(Long.SIZE_BITS - 2)).map { 1L shl it }

    fun magnitudeExpression(
        start: Int,
        end: Int,
    ): String {
        if (end - start == 1) return start.toString()
        val middle = (start + end) / 2
        return "CASE WHEN recorded_values.number_scaled < ${thresholds[middle]} " +
            "THEN ${magnitudeExpression(start, middle)} ELSE ${magnitudeExpression(middle, end)} END"
    }
    val bucket =
        if (shift == null) {
            magnitudeExpression(0, thresholds.size)
        } else {
            "((recorded_values.number_scaled >> $shift) & 255)"
        }
    val rangePredicate =
        if (shift == null) {
            ""
        } else {
            "AND recorded_values.number_scaled BETWEEN (${rangeExpression(0, keys.size, true)}) " +
                "AND (${rangeExpression(0, keys.size, false)}) "
        }
    val eligible =
        if (kind == StatisticsSeriesKind.ACTIVITY) {
            "executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL"
        } else {
            "executions.status IN ('COMPLETED', 'ENDED_EARLY')"
        }
    val ids = keys.joinToString(",") { "CAST(X'$it' AS TEXT)" }
    val sql =
        "SELECT fields.source_field_id, ($bucket) AS bucket_id, COUNT(*) AS sample_count, " +
            "MIN(recorded_values.number_scaled) AS minimum_scaled, " +
            "MAX(recorded_values.number_scaled) AS maximum_scaled " +
            "FROM ${table}_executions AS executions " +
            "CROSS JOIN ${table}_execution_field_values AS recorded_values " +
            "ON recorded_values.${table}_execution_id = executions.id " +
            "CROSS JOIN ${table}_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "WHERE executions.statistics_series_id = ? AND $eligible " +
            "AND fields.source_field_id IN ($ids) AND recorded_values.number_scaled IS NOT NULL " +
            "AND (? IS NULL OR executions.primary_local_date BETWEEN ? AND ?) " +
            rangePredicate +
            "GROUP BY fields.source_field_id, bucket_id ORDER BY fields.source_field_id, bucket_id"
    return SimpleSQLiteQuery(sql, arrayOf(seriesId, bounds.first, bounds.first, bounds.second))
}
