package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import java.math.BigInteger

internal data class StatisticsNumberRankRow(
    @ColumnInfo(name = "source_field_id") val sourceFieldId: String,
    @ColumnInfo(name = "sample_count") val sampleCount: Long,
    @ColumnInfo(name = "at_or_below_count") val atOrBelowCount: Long,
    @ColumnInfo(name = "maximum_at_or_below") val maximumAtOrBelow: Long?,
    @ColumnInfo(name = "minimum_above") val minimumAbove: Long?,
)

/** Exact parallel selection: each pass halves every unresolved signed-Long value range. */
internal class GroupedNumberMedians(
    summaries: Map<String, ExactNumberSummary>,
) {
    private val searches = summaries.mapValues { Search(it.value) }.toMutableMap()

    /** A shorter trial returns resolved Fields only; the caller finishes the bounded remainder in SQL. */
    fun select(
        passLimit: Int = Long.SIZE_BITS + 1,
        read: (Map<String, Long>) -> List<StatisticsNumberRankRow>,
    ): Map<String, StatisticsNumberSelectionRow> {
        require(passLimit in 1..Long.SIZE_BITS + 1)
        val result = mutableMapOf<String, StatisticsNumberSelectionRow>()
        // At most 64 halvings plus a final exact-value query, independently of samples and Fields.
        repeat(passLimit) {
            if (searches.isNotEmpty()) {
                val pivots = searches.mapValues { it.value.pivot() }
                val rows = read(pivots)
                require(rows.size == searches.size && rows.map { it.sourceFieldId }.toSet() == searches.keys) {
                    "Grouped Number ranks disagree with summary Fields"
                }
                rows.forEach { row ->
                    searches.getValue(row.sourceFieldId).advance(row)?.let { selection ->
                        result[row.sourceFieldId] = selection
                        searches.remove(row.sourceFieldId)
                    }
                }
            }
        }
        require(passLimit < Long.SIZE_BITS + 1 || searches.isEmpty()) { "Number median search did not converge" }
        return result
    }

    private class Search(
        summary: ExactNumberSummary,
    ) {
        private val count = summary.sampleCount
        private var lower = requireNotNull(summary.minimum)
        private var upper = requireNotNull(summary.maximum)

        // The midpoint of two Long bounds is also in Long range, so this conversion is exact.
        fun pivot(): Long =
            BigInteger
                .valueOf(lower)
                .add(BigInteger.valueOf(upper).subtract(BigInteger.valueOf(lower)).shiftRight(1))
                .toLong()

        fun advance(row: StatisticsNumberRankRow): StatisticsNumberSelectionRow? {
            require(row.sampleCount == count && row.atOrBelowCount in 0..count) { "Number rank count mismatch" }
            val pivot = pivot()
            require((row.maximumAtOrBelow == null) == (row.atOrBelowCount == 0L))
            require((row.minimumAbove == null) == (row.atOrBelowCount == count))
            row.maximumAtOrBelow?.let { require(it <= pivot) }
            row.minimumAbove?.let { require(it > pivot) }
            val lowerRank = (count - 1) / 2
            return when {
                row.atOrBelowCount == lowerRank + 1 -> {
                    val low = requireNotNull(row.maximumAtOrBelow)
                    val high = if (count % 2 == 0L) requireNotNull(row.minimumAbove) else low
                    StatisticsNumberSelectionRow(count, 2 - count % 2, low, high)
                }
                lower == upper -> {
                    require(row.atOrBelowCount > lowerRank) { "Number rank outside search bounds" }
                    require(row.maximumAtOrBelow == lower)
                    StatisticsNumberSelectionRow(count, 2 - count % 2, lower, lower)
                }
                else -> {
                    if (row.atOrBelowCount <= lowerRank) {
                        lower = requireNotNull(row.minimumAbove)
                    } else {
                        upper = requireNotNull(row.maximumAtOrBelow)
                    }
                    require(lower <= upper) { "Number rank outside search bounds" }
                    null
                }
            }
        }
    }
}

/**
 * Each pass scans selected execution values once and returns one scalar row per unresolved Field.
 * A balanced CASE tree costs O(log P) comparisons per value, avoiding a P-row pivot-table scan.
 * Hex text literals encode opaque IDs safely and keep bind use fixed even beyond 999 Fields.
 */
internal fun groupedNumberRankQuery(
    kind: StatisticsSeriesKind,
    seriesId: String,
    bounds: Pair<String?, String?>,
    pivots: Map<String, Long>,
): SupportSQLiteQuery {
    require(pivots.isNotEmpty())
    val table =
        when (kind) {
            StatisticsSeriesKind.ACTIVITY -> "activity"
            StatisticsSeriesKind.SEQUENCE -> "sequence"
            StatisticsSeriesKind.ONE_OFF_BUCKET -> error("One-off Series has no reusable Fields")
        }
    val values =
        pivots.entries.associate { (id, pivot) ->
            id.toByteArray(Charsets.UTF_8).joinToString("") { byte -> "%02x".format(byte) } to pivot
        }
    val keys = values.keys.sorted()

    fun expression(
        start: Int,
        end: Int,
    ): String {
        if (end - start == 1) return values.getValue(keys[start]).toString()
        val middle = (start + end) / 2
        return "CASE WHEN fields.source_field_id COLLATE BINARY < CAST(X'${keys[middle]}' AS TEXT) " +
            "THEN ${expression(start, middle)} ELSE ${expression(middle, end)} END"
    }
    val pivot = expression(0, keys.size)
    val eligible =
        if (kind == StatisticsSeriesKind.ACTIVITY) {
            "executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL"
        } else {
            "executions.status IN ('COMPLETED', 'ENDED_EARLY')"
        }
    val ids = keys.joinToString(",") { "CAST(X'$it' AS TEXT)" }
    val sql =
        "SELECT fields.source_field_id, COUNT(*) AS sample_count, " +
            "COUNT(CASE WHEN recorded_values.number_scaled <= ($pivot) THEN 1 END) AS at_or_below_count, " +
            "MAX(CASE WHEN recorded_values.number_scaled <= ($pivot) " +
            "THEN recorded_values.number_scaled END) " +
            "AS maximum_at_or_below, " +
            "MIN(CASE WHEN recorded_values.number_scaled > ($pivot) " +
            "THEN recorded_values.number_scaled END) " +
            "AS minimum_above " +
            "FROM ${table}_executions AS executions " +
            "CROSS JOIN ${table}_execution_field_values AS recorded_values " +
            "ON recorded_values.${table}_execution_id = executions.id " +
            "CROSS JOIN ${table}_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "WHERE executions.statistics_series_id = ? AND $eligible " +
            "AND fields.source_field_id IN ($ids) AND recorded_values.number_scaled IS NOT NULL " +
            "AND (? IS NULL OR executions.primary_local_date BETWEEN ? AND ?) GROUP BY fields.source_field_id"
    return SimpleSQLiteQuery(sql, arrayOf(seriesId, bounds.first, bounds.first, bounds.second))
}
