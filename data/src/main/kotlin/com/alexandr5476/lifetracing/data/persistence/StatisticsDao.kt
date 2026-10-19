@file:Suppress("ktlint:standard:max-line-length") // SQL stays contiguous enough to audit as SQL.

package com.alexandr5476.lifetracing.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

internal data class GlobalStatisticsRow(
    @ColumnInfo(name = "total_tracked_ms") val totalTrackedMs: Long,
    @ColumnInfo(name = "execution_count") val executionCount: Long,
    @ColumnInfo(name = "active_day_count") val activeDayCount: Long,
    @ColumnInfo(name = "sequence_pause_ms") val sequencePauseMs: Long,
    @ColumnInfo(name = "one_off_count") val oneOffCount: Long,
    @ColumnInfo(name = "one_off_tracked_ms") val oneOffTrackedMs: Long,
    @ColumnInfo(name = "first_primary_date") val firstPrimaryDate: String?,
    @ColumnInfo(name = "last_primary_date") val lastPrimaryDate: String?,
)

internal data class StatisticsSeriesCatalogRow(
    val id: String,
    val kind: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "archived_at_ms") val archivedAtMs: Long?,
    @ColumnInfo(name = "active_activity_sources") val activeActivitySources: Long,
    @ColumnInfo(name = "archived_activity_sources") val archivedActivitySources: Long,
    @ColumnInfo(name = "active_sequence_sources") val activeSequenceSources: Long,
    @ColumnInfo(name = "archived_sequence_sources") val archivedSequenceSources: Long,
)

internal data class StatisticsSeriesAggregateRow(
    @ColumnInfo(name = "series_id") val seriesId: String,
    @ColumnInfo(name = "execution_count") val executionCount: Long,
    @ColumnInfo(name = "duration_sample_count") val durationSampleCount: Long,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long,
    @ColumnInfo(name = "invalid_duration_count") val invalidDurationCount: Long,
    @ColumnInfo(name = "minimum_duration_ms") val minimumDurationMs: Long?,
    @ColumnInfo(name = "maximum_duration_ms") val maximumDurationMs: Long?,
    @ColumnInfo(name = "total_pause_ms") val totalPauseMs: Long,
    @ColumnInfo(name = "active_day_count") val activeDayCount: Long,
    @ColumnInfo(name = "first_performed_at_ms") val firstPerformedAtMs: Long?,
    @ColumnInfo(name = "last_performed_at_ms") val lastPerformedAtMs: Long?,
) {
    init {
        require(invalidDurationCount == 0L) { "Duration samples must be nonnegative SQLite integers" }
    }
}

internal data class StatisticsDurationSelectionRow(
    @ColumnInfo(name = "sample_count") val sampleCount: Long,
    @ColumnInfo(name = "selected_count") val selectedCount: Long,
    @ColumnInfo(name = "lower_median_ms") val lowerMedianMs: Long?,
    @ColumnInfo(name = "upper_median_ms") val upperMedianMs: Long?,
)

internal data class StatisticsFieldMetadataRow(
    @ColumnInfo(name = "source_field_id") val sourceFieldId: String,
    @ColumnInfo(name = "field_type") val fieldType: String,
    val unit: String?,
    @ColumnInfo(name = "display_precision") val displayPrecision: Int?,
    @ColumnInfo(name = "current_display_name") val currentDisplayName: String?,
    @ColumnInfo(name = "snapshot_field_id") val snapshotFieldId: String?,
    @ColumnInfo(name = "name_at_creation") val nameAtCreation: String?,
)

internal data class StatisticsOptionMetadataRow(
    @ColumnInfo(name = "source_option_id") val sourceOptionId: String?,
    @ColumnInfo(name = "snapshot_option_id") val snapshotOptionId: String?,
    @ColumnInfo(name = "current_label") val currentLabel: String?,
    @ColumnInfo(name = "fallback_label") val fallbackLabel: String?,
)

internal data class StatisticsCategoryCountRow(
    @ColumnInfo(name = "source_option_id") val sourceOptionId: String?,
    @ColumnInfo(name = "snapshot_option_id") val snapshotOptionId: String?,
    val count: Long,
)

internal data class StatisticsNumberBitCountRow(
    @ColumnInfo(name = "bit_index") val bitIndex: Int,
    @ColumnInfo(name = "sample_count") val sampleCount: Long,
    @ColumnInfo(name = "set_bit_count") val setBitCount: Long,
    @ColumnInfo(name = "invalid_count") val invalidCount: Long,
    @ColumnInfo(name = "minimum_scaled") val minimumScaled: Long?,
    @ColumnInfo(name = "maximum_scaled") val maximumScaled: Long?,
) {
    init {
        require(invalidCount == 0L) { "Number samples must be SQLite integers" }
    }
}

internal data class StatisticsNumberSelectionRow(
    @ColumnInfo(name = "sample_count") val sampleCount: Long,
    @ColumnInfo(name = "selected_count") val selectedCount: Long,
    @ColumnInfo(name = "lower_median_scaled") val lowerMedianScaled: Long?,
    @ColumnInfo(name = "upper_median_scaled") val upperMedianScaled: Long?,
)

/** One row per recorded source Field; bitCounts contains exactly 64 decimal COUNTs, never values. */
internal data class StatisticsNumberSummaryRow(
    @ColumnInfo(name = "source_field_id") val sourceFieldId: String,
    @ColumnInfo(name = "sample_count") val sampleCount: Long,
    @ColumnInfo(name = "invalid_count") val invalidCount: Long,
    @ColumnInfo(name = "minimum_scaled") val minimumScaled: Long?,
    @ColumnInfo(name = "maximum_scaled") val maximumScaled: Long?,
    @ColumnInfo(name = "bit_counts") val bitCounts: String,
)

internal data class StatisticsFieldOptionMetadataRow(
    @ColumnInfo(name = "source_field_id") val sourceFieldId: String,
    @ColumnInfo(name = "source_option_id") val sourceOptionId: String?,
    @ColumnInfo(name = "snapshot_option_id") val snapshotOptionId: String?,
    @ColumnInfo(name = "current_label") val currentLabel: String?,
    @ColumnInfo(name = "fallback_label") val fallbackLabel: String?,
)

internal data class StatisticsFieldCategoryCountRow(
    @ColumnInfo(name = "source_field_id") val sourceFieldId: String,
    @ColumnInfo(name = "source_option_id") val sourceOptionId: String?,
    @ColumnInfo(name = "snapshot_option_id") val snapshotOptionId: String?,
    val count: Long,
)

@Dao
// Fixed-width 64-bit SQL projections stay together with their eligibility predicates for auditability.
@Suppress("TooManyFunctions", "MaxLineLength", "LargeClass")
internal interface StatisticsDao {
    @Query(
        "SELECT fields.source_field_id, COUNT(*) AS sample_count, " +
            "COUNT(CASE WHEN typeof(recorded_values.number_scaled) != 'integer' THEN 1 END) AS invalid_count, " +
            "MIN(recorded_values.number_scaled) AS minimum_scaled, MAX(recorded_values.number_scaled) AS maximum_scaled, " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 0) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 1) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 2) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 3) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 4) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 5) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 6) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 7) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 8) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 9) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 10) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 11) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 12) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 13) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 14) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 15) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 16) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 17) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 18) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 19) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 20) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 21) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 22) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 23) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 24) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 25) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 26) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 27) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 28) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 29) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 30) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 31) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 32) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 33) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 34) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 35) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 36) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 37) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 38) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 39) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 40) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 41) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 42) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 43) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 44) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 45) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 46) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 47) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 48) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 49) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 50) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 51) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 52) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 53) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 54) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 55) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 56) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 57) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 58) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 59) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 60) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 61) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 62) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 63) & 1) = 1 THEN 1 END) AS bit_counts " +
            "FROM activity_executions AS executions " +
            "INNER JOIN activity_execution_field_values AS recorded_values ON recorded_values.activity_execution_id = executions.id " +
            "INNER JOIN activity_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "WHERE executions.statistics_series_id = :seriesId AND executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL " +
            "AND fields.source_field_id IS NOT NULL AND recorded_values.number_scaled IS NOT NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate) " +
            "GROUP BY fields.source_field_id ORDER BY fields.source_field_id",
    )
    fun activityNumberSummaries(
        seriesId: String,
        startDate: String?,
        endDate: String?,
    ): List<StatisticsNumberSummaryRow>

    // COUNT(*) uses SQLite's B-tree count, without hydrating any execution rows.
    @Query("SELECT COUNT(*) FROM activity_executions")
    fun activityHistoryExecutionCount(): Long

    // Cap metadata traversal at the selected sample count plus one.
    @Query(
        "SELECT COUNT(*) FROM (SELECT id FROM activity_snapshot_fields WHERE source_field_id = :fieldId LIMIT :limit)",
    )
    fun activityNumberFieldLineageCount(
        fieldId: String,
        limit: Long,
    ): Long

    // Only the repository's cardinality policy may select this dense-history path.
    @Query(
        "WITH eligible AS (SELECT recorded_values.number_scaled FROM activity_snapshot_fields AS fields " +
            "CROSS JOIN activity_execution_field_values AS recorded_values " +
            "CROSS JOIN activity_executions AS executions WHERE fields.source_field_id = :fieldId " +
            "AND recorded_values.snapshot_field_id = fields.id " +
            "AND executions.id = recorded_values.activity_execution_id AND executions.statistics_series_id = :seriesId " +
            "AND executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL AND recorded_values.number_scaled IS NOT NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate)), " +
            "median_values AS (SELECT number_scaled FROM eligible ORDER BY number_scaled " +
            "LIMIT CASE WHEN :expectedCount <= 0 THEN 0 WHEN :expectedCount % 2 = 0 THEN 2 ELSE 1 END " +
            "OFFSET CASE WHEN :expectedCount <= 0 THEN 0 ELSE (:expectedCount - 1) / 2 END) " +
            "SELECT (SELECT COUNT(*) FROM eligible) AS sample_count, COUNT(*) AS selected_count, " +
            "MIN(number_scaled) AS lower_median_scaled, MAX(number_scaled) AS upper_median_scaled FROM median_values",
    )
    fun activityDenseSeriesNumberSelection(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
        expectedCount: Long,
    ): StatisticsNumberSelectionRow

    @Query(
        "WITH eligible AS (SELECT recorded_values.number_scaled FROM activity_executions AS executions " +
            "CROSS JOIN activity_execution_field_values AS recorded_values ON recorded_values.activity_execution_id = executions.id " +
            "CROSS JOIN activity_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "WHERE executions.statistics_series_id = :seriesId AND fields.source_field_id = :fieldId " +
            "AND executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL " +
            "AND recorded_values.number_scaled IS NOT NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate)), " +
            "median_values AS (SELECT number_scaled FROM eligible ORDER BY number_scaled " +
            "LIMIT CASE WHEN :expectedCount <= 0 THEN 0 WHEN :expectedCount % 2 = 0 THEN 2 ELSE 1 END " +
            "OFFSET CASE WHEN :expectedCount <= 0 THEN 0 ELSE (:expectedCount - 1) / 2 END) " +
            "SELECT (SELECT COUNT(*) FROM eligible) AS sample_count, COUNT(*) AS selected_count, " +
            "MIN(number_scaled) AS lower_median_scaled, MAX(number_scaled) AS upper_median_scaled FROM median_values",
    )
    fun activitySeriesNumberSelection(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
        expectedCount: Long,
    ): StatisticsNumberSelectionRow

    @Query(
        "SELECT fields.source_field_id, COUNT(*) AS sample_count, " +
            "COUNT(CASE WHEN typeof(recorded_values.number_scaled) != 'integer' THEN 1 END) AS invalid_count, " +
            "MIN(recorded_values.number_scaled) AS minimum_scaled, MAX(recorded_values.number_scaled) AS maximum_scaled, " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 0) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 1) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 2) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 3) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 4) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 5) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 6) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 7) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 8) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 9) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 10) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 11) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 12) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 13) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 14) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 15) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 16) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 17) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 18) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 19) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 20) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 21) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 22) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 23) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 24) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 25) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 26) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 27) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 28) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 29) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 30) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 31) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 32) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 33) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 34) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 35) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 36) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 37) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 38) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 39) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 40) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 41) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 42) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 43) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 44) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 45) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 46) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 47) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 48) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 49) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 50) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 51) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 52) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 53) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 54) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 55) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 56) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 57) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 58) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 59) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 60) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 61) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 62) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 63) & 1) = 1 THEN 1 END) AS bit_counts " +
            "FROM sequence_executions AS executions " +
            "INNER JOIN sequence_execution_field_values AS recorded_values ON recorded_values.sequence_execution_id = executions.id " +
            "INNER JOIN sequence_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "WHERE executions.statistics_series_id = :seriesId AND executions.status IN ('COMPLETED', 'ENDED_EARLY') " +
            "AND fields.source_field_id IS NOT NULL AND recorded_values.number_scaled IS NOT NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate) " +
            "GROUP BY fields.source_field_id ORDER BY fields.source_field_id",
    )
    fun sequenceNumberSummaries(
        seriesId: String,
        startDate: String?,
        endDate: String?,
    ): List<StatisticsNumberSummaryRow>

    // COUNT(*) uses SQLite's B-tree count, without hydrating any execution rows.
    @Query("SELECT COUNT(*) FROM sequence_executions")
    fun sequenceHistoryExecutionCount(): Long

    // Cap metadata traversal at the selected sample count plus one.
    @Query(
        "SELECT COUNT(*) FROM (SELECT id FROM sequence_snapshot_fields WHERE source_field_id = :fieldId LIMIT :limit)",
    )
    fun sequenceNumberFieldLineageCount(
        fieldId: String,
        limit: Long,
    ): Long

    // Only the repository's cardinality policy may select this dense-history path.
    @Query(
        "WITH eligible AS (SELECT recorded_values.number_scaled FROM sequence_snapshot_fields AS fields " +
            "CROSS JOIN sequence_execution_field_values AS recorded_values " +
            "CROSS JOIN sequence_executions AS executions WHERE fields.source_field_id = :fieldId " +
            "AND recorded_values.snapshot_field_id = fields.id " +
            "AND executions.id = recorded_values.sequence_execution_id AND executions.statistics_series_id = :seriesId " +
            "AND executions.status IN ('COMPLETED', 'ENDED_EARLY') AND recorded_values.number_scaled IS NOT NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate)), " +
            "median_values AS (SELECT number_scaled FROM eligible ORDER BY number_scaled " +
            "LIMIT CASE WHEN :expectedCount <= 0 THEN 0 WHEN :expectedCount % 2 = 0 THEN 2 ELSE 1 END " +
            "OFFSET CASE WHEN :expectedCount <= 0 THEN 0 ELSE (:expectedCount - 1) / 2 END) " +
            "SELECT (SELECT COUNT(*) FROM eligible) AS sample_count, COUNT(*) AS selected_count, " +
            "MIN(number_scaled) AS lower_median_scaled, MAX(number_scaled) AS upper_median_scaled FROM median_values",
    )
    fun sequenceDenseSeriesNumberSelection(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
        expectedCount: Long,
    ): StatisticsNumberSelectionRow

    @Query(
        "WITH eligible AS (SELECT recorded_values.number_scaled FROM sequence_executions AS executions " +
            "CROSS JOIN sequence_execution_field_values AS recorded_values ON recorded_values.sequence_execution_id = executions.id " +
            "CROSS JOIN sequence_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "WHERE executions.statistics_series_id = :seriesId AND fields.source_field_id = :fieldId " +
            "AND executions.status IN ('COMPLETED', 'ENDED_EARLY') " +
            "AND recorded_values.number_scaled IS NOT NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate)), " +
            "median_values AS (SELECT number_scaled FROM eligible ORDER BY number_scaled " +
            "LIMIT CASE WHEN :expectedCount <= 0 THEN 0 WHEN :expectedCount % 2 = 0 THEN 2 ELSE 1 END " +
            "OFFSET CASE WHEN :expectedCount <= 0 THEN 0 ELSE (:expectedCount - 1) / 2 END) " +
            "SELECT (SELECT COUNT(*) FROM eligible) AS sample_count, COUNT(*) AS selected_count, " +
            "MIN(number_scaled) AS lower_median_scaled, MAX(number_scaled) AS upper_median_scaled FROM median_values",
    )
    fun sequenceSeriesNumberSelection(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
        expectedCount: Long,
    ): StatisticsNumberSelectionRow

    @Query(
        "SELECT source_fields.id AS source_field_id, options.id AS source_option_id, NULL AS snapshot_option_id, " +
            "options.label AS current_label, NULL AS fallback_label " +
            "FROM activity_template_category_options AS options INNER JOIN activity_template_fields AS source_fields " +
            "ON source_fields.id = options.activity_template_field_id INNER JOIN activity_templates AS templates " +
            "ON templates.id = source_fields.activity_template_id WHERE templates.statistics_series_id = :seriesId " +
            "UNION ALL SELECT snapshot_fields.source_field_id, snapshot_options.source_option_id, " +
            "CASE WHEN snapshot_options.source_option_id IS NULL THEN snapshot_options.id ELSE NULL END, " +
            "source_options.label, MIN(COALESCE(snapshot_options.local_label_override, snapshot_options.label_at_creation)) " +
            "FROM activity_executions AS executions INNER JOIN activity_snapshot_fields AS snapshot_fields " +
            "ON snapshot_fields.snapshot_id = executions.snapshot_id INNER JOIN activity_snapshot_category_options AS snapshot_options " +
            "ON snapshot_options.snapshot_field_id = snapshot_fields.id LEFT JOIN activity_template_category_options AS source_options " +
            "ON source_options.id = snapshot_options.source_option_id WHERE executions.statistics_series_id = :seriesId " +
            "AND executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL " +
            "AND snapshot_fields.source_field_id IS NOT NULL " +
            "GROUP BY snapshot_fields.source_field_id, snapshot_options.source_option_id, " +
            "CASE WHEN snapshot_options.source_option_id IS NULL THEN snapshot_options.id ELSE NULL END, source_options.label",
    )
    fun activityFieldOptionMetadata(seriesId: String): List<StatisticsFieldOptionMetadataRow>

    @Query(
        "SELECT source_fields.id AS source_field_id, options.id AS source_option_id, NULL AS snapshot_option_id, " +
            "options.label AS current_label, NULL AS fallback_label " +
            "FROM sequence_template_category_options AS options INNER JOIN sequence_template_fields AS source_fields " +
            "ON source_fields.id = options.sequence_template_field_id INNER JOIN sequence_templates AS templates " +
            "ON templates.id = source_fields.sequence_template_id WHERE templates.statistics_series_id = :seriesId " +
            "UNION ALL SELECT snapshot_fields.source_field_id, snapshot_options.source_option_id, " +
            "CASE WHEN snapshot_options.source_option_id IS NULL THEN snapshot_options.id ELSE NULL END, " +
            "source_options.label, MIN(COALESCE(snapshot_options.local_label_override, snapshot_options.label_at_creation)) " +
            "FROM sequence_executions AS executions INNER JOIN sequence_snapshot_fields AS snapshot_fields " +
            "ON snapshot_fields.sequence_snapshot_id = executions.snapshot_id INNER JOIN sequence_snapshot_category_options AS snapshot_options " +
            "ON snapshot_options.sequence_snapshot_field_id = snapshot_fields.id LEFT JOIN sequence_template_category_options AS source_options " +
            "ON source_options.id = snapshot_options.source_option_id WHERE executions.statistics_series_id = :seriesId " +
            "AND executions.status IN ('COMPLETED', 'ENDED_EARLY') AND snapshot_fields.source_field_id IS NOT NULL " +
            "GROUP BY snapshot_fields.source_field_id, snapshot_options.source_option_id, " +
            "CASE WHEN snapshot_options.source_option_id IS NULL THEN snapshot_options.id ELSE NULL END, source_options.label",
    )
    fun sequenceFieldOptionMetadata(seriesId: String): List<StatisticsFieldOptionMetadataRow>

    @Query(
        "SELECT fields.source_field_id, options.source_option_id, " +
            "CASE WHEN options.source_option_id IS NULL THEN options.id ELSE NULL END AS snapshot_option_id, COUNT(*) AS count " +
            "FROM activity_executions AS executions INNER JOIN activity_execution_field_values AS recorded_values " +
            "ON recorded_values.activity_execution_id = executions.id INNER JOIN activity_snapshot_fields AS fields " +
            "ON fields.id = recorded_values.snapshot_field_id INNER JOIN activity_snapshot_category_options AS options " +
            "ON options.id = recorded_values.category_option_id WHERE executions.statistics_series_id = :seriesId " +
            "AND fields.source_field_id IS NOT NULL AND executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate) " +
            "GROUP BY fields.source_field_id, options.source_option_id, " +
            "CASE WHEN options.source_option_id IS NULL THEN options.id ELSE NULL END",
    )
    fun activityFieldCategoryCounts(
        seriesId: String,
        startDate: String?,
        endDate: String?,
    ): List<StatisticsFieldCategoryCountRow>

    @Query(
        "SELECT fields.source_field_id, options.source_option_id, " +
            "CASE WHEN options.source_option_id IS NULL THEN options.id ELSE NULL END AS snapshot_option_id, COUNT(*) AS count " +
            "FROM sequence_executions AS executions INNER JOIN sequence_execution_field_values AS recorded_values " +
            "ON recorded_values.sequence_execution_id = executions.id INNER JOIN sequence_snapshot_fields AS fields " +
            "ON fields.id = recorded_values.snapshot_field_id INNER JOIN sequence_snapshot_category_options AS options " +
            "ON options.id = recorded_values.category_option_id WHERE executions.statistics_series_id = :seriesId " +
            "AND fields.source_field_id IS NOT NULL AND executions.status IN ('COMPLETED', 'ENDED_EARLY') " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate) " +
            "GROUP BY fields.source_field_id, options.source_option_id, " +
            "CASE WHEN options.source_option_id IS NULL THEN options.id ELSE NULL END",
    )
    fun sequenceFieldCategoryCounts(
        seriesId: String,
        startDate: String?,
        endDate: String?,
    ): List<StatisticsFieldCategoryCountRow>

    @Query(
        "SELECT COALESCE(SUM(COALESCE(duration_ms, 0)), 0) AS total_tracked_ms, " +
            "COUNT(*) AS execution_count, COUNT(DISTINCT primary_local_date) AS active_day_count, " +
            "COALESCE(SUM(sequence_pause_ms), 0) AS sequence_pause_ms, " +
            "COALESCE(SUM(is_one_off), 0) AS one_off_count, " +
            "COALESCE(SUM(CASE WHEN is_one_off = 1 THEN COALESCE(duration_ms, 0) ELSE 0 END), 0) AS one_off_tracked_ms, " +
            "MIN(primary_local_date) AS first_primary_date, MAX(primary_local_date) AS last_primary_date FROM (" +
            "SELECT active_duration_ms AS duration_ms, primary_local_date, 0 AS sequence_pause_ms, " +
            "CASE WHEN statistics_series_id = :oneOffSeriesId THEN 1 ELSE 0 END AS is_one_off " +
            "FROM activity_executions WHERE context_type = 'STANDALONE' AND status = 'COMPLETED' " +
            "AND deleted_at_ms IS NULL AND (:startDate IS NULL OR primary_local_date BETWEEN :startDate AND :endDate) " +
            "UNION ALL " +
            "SELECT active_duration_ms, primary_local_date, COALESCE(pause_duration_ms, 0), 0 " +
            "FROM sequence_executions WHERE status IN ('COMPLETED', 'ENDED_EARLY') " +
            "AND (:startDate IS NULL OR primary_local_date BETWEEN :startDate AND :endDate))",
    )
    fun global(
        startDate: String?,
        endDate: String?,
        oneOffSeriesId: String,
    ): GlobalStatisticsRow

    @Query(
        "SELECT series.id, series.kind, series.display_name, series.archived_at_ms, " +
            "(SELECT COUNT(*) FROM activity_templates WHERE statistics_series_id = series.id AND deleted_at_ms IS NULL) AS active_activity_sources, " +
            "(SELECT COUNT(*) FROM activity_templates WHERE statistics_series_id = series.id AND deleted_at_ms IS NOT NULL) AS archived_activity_sources, " +
            "(SELECT COUNT(*) FROM sequence_templates WHERE statistics_series_id = series.id AND deleted_at_ms IS NULL) AS active_sequence_sources, " +
            "(SELECT COUNT(*) FROM sequence_templates WHERE statistics_series_id = series.id AND deleted_at_ms IS NOT NULL) AS archived_sequence_sources " +
            "FROM statistics_series AS series ORDER BY series.display_name COLLATE NOCASE, series.kind, series.id",
    )
    fun seriesCatalog(): List<StatisticsSeriesCatalogRow>

    @Query(
        "SELECT executions.statistics_series_id AS series_id, COUNT(*) AS execution_count, " +
            "COUNT(executions.active_duration_ms) AS duration_sample_count, " +
            "COUNT(CASE WHEN executions.active_duration_ms IS NOT NULL AND " +
            "(typeof(executions.active_duration_ms) != 'integer' OR executions.active_duration_ms < 0) " +
            "THEN 1 END) AS invalid_duration_count, " +
            "MIN(executions.active_duration_ms) AS minimum_duration_ms, " +
            "MAX(executions.active_duration_ms) AS maximum_duration_ms, " +
            "COALESCE(SUM(executions.active_duration_ms), 0) AS total_duration_ms, 0 AS total_pause_ms, " +
            "COUNT(DISTINCT executions.primary_local_date) AS active_day_count, " +
            "MIN(executions.completed_at_ms) AS first_performed_at_ms, MAX(executions.completed_at_ms) AS last_performed_at_ms " +
            "FROM activity_executions AS executions INNER JOIN statistics_series AS series " +
            "ON series.id = executions.statistics_series_id AND series.kind = 'ACTIVITY' " +
            "WHERE executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL " +
            "AND (:seriesId IS NULL OR executions.statistics_series_id = :seriesId) " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate) " +
            "GROUP BY executions.statistics_series_id",
    )
    fun activitySeriesAggregates(
        seriesId: String?,
        startDate: String?,
        endDate: String?,
    ): List<StatisticsSeriesAggregateRow>

    @Query(
        "SELECT executions.statistics_series_id AS series_id, COUNT(*) AS execution_count, " +
            "COUNT(executions.active_duration_ms) AS duration_sample_count, " +
            "COUNT(CASE WHEN executions.active_duration_ms IS NOT NULL AND " +
            "(typeof(executions.active_duration_ms) != 'integer' OR executions.active_duration_ms < 0) " +
            "THEN 1 END) AS invalid_duration_count, " +
            "MIN(executions.active_duration_ms) AS minimum_duration_ms, " +
            "MAX(executions.active_duration_ms) AS maximum_duration_ms, " +
            "COALESCE(SUM(executions.active_duration_ms), 0) AS total_duration_ms, " +
            "COALESCE(SUM(executions.pause_duration_ms), 0) AS total_pause_ms, " +
            "COUNT(DISTINCT executions.primary_local_date) AS active_day_count, " +
            "MIN(executions.ended_at_ms) AS first_performed_at_ms, MAX(executions.ended_at_ms) AS last_performed_at_ms " +
            "FROM sequence_executions AS executions INNER JOIN statistics_series AS series " +
            "ON series.id = executions.statistics_series_id AND series.kind = 'SEQUENCE' " +
            "WHERE executions.status IN ('COMPLETED', 'ENDED_EARLY') " +
            "AND (:seriesId IS NULL OR executions.statistics_series_id = :seriesId) " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate) " +
            "GROUP BY executions.statistics_series_id",
    )
    fun sequenceSeriesAggregates(
        seriesId: String?,
        startDate: String?,
        endDate: String?,
    ): List<StatisticsSeriesAggregateRow>

    @Query(
        "SELECT executions.statistics_series_id AS series_id, COUNT(*) AS execution_count, " +
            "COUNT(executions.active_duration_ms) AS duration_sample_count, " +
            "COUNT(CASE WHEN executions.active_duration_ms IS NOT NULL AND " +
            "(typeof(executions.active_duration_ms) != 'integer' OR executions.active_duration_ms < 0) " +
            "THEN 1 END) AS invalid_duration_count, " +
            "MIN(executions.active_duration_ms) AS minimum_duration_ms, " +
            "MAX(executions.active_duration_ms) AS maximum_duration_ms, " +
            "COALESCE(SUM(executions.active_duration_ms), 0) AS total_duration_ms, 0 AS total_pause_ms, " +
            "COUNT(DISTINCT executions.primary_local_date) AS active_day_count, " +
            "MIN(executions.completed_at_ms) AS first_performed_at_ms, MAX(executions.completed_at_ms) AS last_performed_at_ms " +
            "FROM activity_executions AS executions WHERE executions.statistics_series_id = :seriesId " +
            "AND executions.context_type = 'STANDALONE' AND executions.status = 'COMPLETED' " +
            "AND executions.deleted_at_ms IS NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate) " +
            "GROUP BY executions.statistics_series_id",
    )
    fun oneOffAggregate(
        seriesId: String,
        startDate: String?,
        endDate: String?,
    ): StatisticsSeriesAggregateRow?

    @Query(
        "WITH eligible AS (SELECT active_duration_ms FROM activity_executions " +
            "WHERE statistics_series_id = :seriesId AND status = 'COMPLETED' AND deleted_at_ms IS NULL " +
            "AND active_duration_ms IS NOT NULL " +
            "AND (:startDate IS NULL OR primary_local_date BETWEEN :startDate AND :endDate)), " +
            "median_values AS (SELECT active_duration_ms FROM eligible ORDER BY active_duration_ms " +
            "LIMIT CASE WHEN :expectedCount <= 0 THEN 0 WHEN :expectedCount % 2 = 0 THEN 2 ELSE 1 END " +
            "OFFSET CASE WHEN :expectedCount <= 0 THEN 0 ELSE (:expectedCount - 1) / 2 END) " +
            "SELECT (SELECT COUNT(*) FROM eligible) AS sample_count, COUNT(*) AS selected_count, " +
            "MIN(active_duration_ms) AS lower_median_ms, MAX(active_duration_ms) AS upper_median_ms FROM median_values",
    )
    fun activityDurationSelection(
        seriesId: String,
        startDate: String?,
        endDate: String?,
        expectedCount: Long,
    ): StatisticsDurationSelectionRow

    @Query(
        "WITH eligible AS (SELECT active_duration_ms FROM sequence_executions " +
            "WHERE statistics_series_id = :seriesId AND status IN ('COMPLETED', 'ENDED_EARLY') " +
            "AND active_duration_ms IS NOT NULL " +
            "AND (:startDate IS NULL OR primary_local_date BETWEEN :startDate AND :endDate)), " +
            "median_values AS (SELECT active_duration_ms FROM eligible ORDER BY active_duration_ms " +
            "LIMIT CASE WHEN :expectedCount <= 0 THEN 0 WHEN :expectedCount % 2 = 0 THEN 2 ELSE 1 END " +
            "OFFSET CASE WHEN :expectedCount <= 0 THEN 0 ELSE (:expectedCount - 1) / 2 END) " +
            "SELECT (SELECT COUNT(*) FROM eligible) AS sample_count, COUNT(*) AS selected_count, " +
            "MIN(active_duration_ms) AS lower_median_ms, MAX(active_duration_ms) AS upper_median_ms FROM median_values",
    )
    fun sequenceDurationSelection(
        seriesId: String,
        startDate: String?,
        endDate: String?,
        expectedCount: Long,
    ): StatisticsDurationSelectionRow

    @Query(
        "SELECT fields.id AS source_field_id, fields.field_type, fields.unit, fields.display_precision, " +
            "fields.name AS current_display_name, NULL AS snapshot_field_id, NULL AS name_at_creation " +
            "FROM activity_template_fields AS fields INNER JOIN activity_templates AS templates " +
            "ON templates.id = fields.activity_template_id WHERE templates.statistics_series_id = :seriesId " +
            "UNION ALL " +
            "SELECT snapshot_fields.source_field_id, snapshot_fields.field_type, snapshot_fields.unit, " +
            "snapshot_fields.display_precision, source_fields.name AS current_display_name, " +
            "MIN(snapshot_fields.id) AS snapshot_field_id, MIN(snapshot_fields.name_at_creation) AS name_at_creation " +
            "FROM activity_executions AS executions INNER JOIN activity_snapshot_fields AS snapshot_fields " +
            "ON snapshot_fields.snapshot_id = executions.snapshot_id LEFT JOIN activity_template_fields AS source_fields " +
            "ON source_fields.id = snapshot_fields.source_field_id WHERE executions.statistics_series_id = :seriesId " +
            "AND executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL " +
            "AND snapshot_fields.source_field_id IS NOT NULL " +
            "GROUP BY snapshot_fields.source_field_id, snapshot_fields.field_type, snapshot_fields.unit, " +
            "snapshot_fields.display_precision, source_fields.name",
    )
    fun activityFieldMetadata(seriesId: String): List<StatisticsFieldMetadataRow>

    @Query(
        "SELECT fields.id AS source_field_id, fields.field_type, fields.unit, fields.display_precision, " +
            "fields.name AS current_display_name, NULL AS snapshot_field_id, NULL AS name_at_creation " +
            "FROM sequence_template_fields AS fields INNER JOIN sequence_templates AS templates " +
            "ON templates.id = fields.sequence_template_id WHERE templates.statistics_series_id = :seriesId " +
            "UNION ALL " +
            "SELECT snapshot_fields.source_field_id, snapshot_fields.field_type, snapshot_fields.unit, " +
            "snapshot_fields.display_precision, source_fields.name AS current_display_name, " +
            "MIN(snapshot_fields.id) AS snapshot_field_id, MIN(snapshot_fields.name_at_creation) AS name_at_creation " +
            "FROM sequence_executions AS executions INNER JOIN sequence_snapshot_fields AS snapshot_fields " +
            "ON snapshot_fields.sequence_snapshot_id = executions.snapshot_id LEFT JOIN sequence_template_fields AS source_fields " +
            "ON source_fields.id = snapshot_fields.source_field_id WHERE executions.statistics_series_id = :seriesId " +
            "AND executions.status IN ('COMPLETED', 'ENDED_EARLY') AND snapshot_fields.source_field_id IS NOT NULL " +
            "GROUP BY snapshot_fields.source_field_id, snapshot_fields.field_type, snapshot_fields.unit, " +
            "snapshot_fields.display_precision, source_fields.name",
    )
    fun sequenceFieldMetadata(seriesId: String): List<StatisticsFieldMetadataRow>

    @Query(
        "SELECT :fieldId AS source_field_id, COUNT(*) AS sample_count, " +
            "COUNT(CASE WHEN typeof(recorded_values.number_scaled) != 'integer' THEN 1 END) AS invalid_count, " +
            "MIN(recorded_values.number_scaled) AS minimum_scaled, MAX(recorded_values.number_scaled) AS maximum_scaled, " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 0) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 1) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 2) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 3) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 4) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 5) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 6) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 7) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 8) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 9) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 10) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 11) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 12) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 13) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 14) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 15) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 16) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 17) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 18) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 19) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 20) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 21) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 22) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 23) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 24) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 25) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 26) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 27) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 28) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 29) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 30) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 31) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 32) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 33) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 34) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 35) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 36) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 37) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 38) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 39) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 40) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 41) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 42) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 43) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 44) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 45) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 46) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 47) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 48) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 49) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 50) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 51) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 52) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 53) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 54) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 55) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 56) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 57) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 58) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 59) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 60) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 61) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 62) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 63) & 1) = 1 THEN 1 END) AS bit_counts " +
            "FROM activity_executions AS executions " +
            "CROSS JOIN activity_execution_field_values AS recorded_values ON recorded_values.activity_execution_id = executions.id " +
            "CROSS JOIN activity_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "WHERE executions.statistics_series_id = :seriesId AND fields.source_field_id = :fieldId " +
            "AND executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL " +
            "AND recorded_values.number_scaled IS NOT NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate)",
    )
    fun activityNumberBitCounts(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
    ): StatisticsNumberSummaryRow

    @Query(
        "WITH eligible AS (SELECT recorded_values.number_scaled FROM activity_executions AS executions CROSS JOIN " +
            "activity_execution_field_values AS recorded_values ON recorded_values.activity_execution_id = executions.id " +
            "CROSS JOIN activity_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id WHERE " +
            "executions.statistics_series_id = :seriesId AND fields.source_field_id = :fieldId AND executions.status = " +
            "'COMPLETED' AND executions.deleted_at_ms IS NULL AND recorded_values.number_scaled IS NOT NULL AND (:startDate " +
            "IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate)), median_values AS (SELECT " +
            "number_scaled FROM eligible ORDER BY number_scaled LIMIT CASE WHEN :expectedCount <= 0 THEN 0 WHEN " +
            ":expectedCount % 2 = 0 THEN 2 ELSE 1 END OFFSET CASE WHEN :expectedCount <= 0 THEN 0 ELSE (:expectedCount - 1) / " +
            "2 END) SELECT (SELECT COUNT(*) FROM eligible) AS sample_count, COUNT(*) AS selected_count, MIN(number_scaled) AS " +
            "lower_median_scaled, MAX(number_scaled) AS upper_median_scaled FROM median_values",
    )
    fun activityNumberSelection(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
        expectedCount: Long,
    ): StatisticsNumberSelectionRow

    @Query(
        "SELECT COUNT(*) FROM activity_executions AS executions WHERE executions.statistics_series_id = :seriesId AND " +
            "executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL AND (:startDate IS NULL OR " +
            "executions.primary_local_date BETWEEN :startDate AND :endDate)",
    )
    fun activityNumberExecutionCount(
        seriesId: String,
        startDate: String?,
        endDate: String?,
    ): Long

    @Query(
        "SELECT :fieldId AS source_field_id, COUNT(*) AS sample_count, " +
            "COUNT(CASE WHEN typeof(recorded_values.number_scaled) != 'integer' THEN 1 END) AS invalid_count, " +
            "MIN(recorded_values.number_scaled) AS minimum_scaled, MAX(recorded_values.number_scaled) AS maximum_scaled, " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 0) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 1) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 2) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 3) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 4) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 5) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 6) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 7) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 8) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 9) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 10) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 11) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 12) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 13) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 14) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 15) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 16) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 17) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 18) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 19) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 20) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 21) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 22) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 23) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 24) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 25) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 26) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 27) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 28) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 29) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 30) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 31) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 32) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 33) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 34) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 35) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 36) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 37) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 38) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 39) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 40) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 41) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 42) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 43) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 44) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 45) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 46) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 47) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 48) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 49) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 50) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 51) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 52) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 53) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 54) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 55) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 56) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 57) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 58) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 59) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 60) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 61) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 62) & 1) = 1 THEN 1 END) || ',' || " +
            "COUNT(CASE WHEN ((recorded_values.number_scaled >> 63) & 1) = 1 THEN 1 END) AS bit_counts " +
            "FROM sequence_executions AS executions " +
            "CROSS JOIN sequence_execution_field_values AS recorded_values ON recorded_values.sequence_execution_id = executions.id " +
            "CROSS JOIN sequence_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "WHERE executions.statistics_series_id = :seriesId AND fields.source_field_id = :fieldId " +
            "AND executions.status IN ('COMPLETED', 'ENDED_EARLY') " +
            "AND recorded_values.number_scaled IS NOT NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate)",
    )
    fun sequenceNumberBitCounts(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
    ): StatisticsNumberSummaryRow

    @Query(
        "WITH eligible AS (SELECT recorded_values.number_scaled FROM sequence_executions AS executions CROSS JOIN " +
            "sequence_execution_field_values AS recorded_values ON recorded_values.sequence_execution_id = executions.id " +
            "CROSS JOIN sequence_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id WHERE " +
            "executions.statistics_series_id = :seriesId AND fields.source_field_id = :fieldId AND executions.status IN " +
            "('COMPLETED', 'ENDED_EARLY') AND recorded_values.number_scaled IS NOT NULL AND (:startDate IS NULL OR " +
            "executions.primary_local_date BETWEEN :startDate AND :endDate)), median_values AS (SELECT number_scaled FROM " +
            "eligible ORDER BY number_scaled LIMIT CASE WHEN :expectedCount <= 0 THEN 0 WHEN :expectedCount % 2 = 0 THEN 2 " +
            "ELSE 1 END OFFSET CASE WHEN :expectedCount <= 0 THEN 0 ELSE (:expectedCount - 1) / 2 END) SELECT (SELECT " +
            "COUNT(*) FROM eligible) AS sample_count, COUNT(*) AS selected_count, MIN(number_scaled) AS lower_median_scaled, " +
            "MAX(number_scaled) AS upper_median_scaled FROM median_values",
    )
    fun sequenceNumberSelection(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
        expectedCount: Long,
    ): StatisticsNumberSelectionRow

    @Query(
        "SELECT COUNT(*) FROM sequence_executions AS executions WHERE executions.statistics_series_id = :seriesId AND " +
            "executions.status IN ('COMPLETED', 'ENDED_EARLY') AND (:startDate IS NULL OR executions.primary_local_date " +
            "BETWEEN :startDate AND :endDate)",
    )
    fun sequenceNumberExecutionCount(
        seriesId: String,
        startDate: String?,
        endDate: String?,
    ): Long

    @Query(
        "SELECT options.id AS source_option_id, NULL AS snapshot_option_id, options.label AS current_label, NULL AS fallback_label " +
            "FROM activity_template_category_options AS options INNER JOIN activity_template_fields AS source_fields " +
            "ON source_fields.id = options.activity_template_field_id INNER JOIN activity_templates AS templates " +
            "ON templates.id = source_fields.activity_template_id WHERE options.activity_template_field_id = :fieldId " +
            "AND templates.statistics_series_id = :seriesId " +
            "UNION ALL " +
            "SELECT snapshot_options.source_option_id, " +
            "CASE WHEN snapshot_options.source_option_id IS NULL THEN snapshot_options.id ELSE NULL END AS snapshot_option_id, " +
            "source_options.label AS current_label, " +
            "MIN(COALESCE(snapshot_options.local_label_override, snapshot_options.label_at_creation)) AS fallback_label " +
            "FROM activity_executions AS executions INNER JOIN activity_snapshot_fields AS snapshot_fields " +
            "ON snapshot_fields.snapshot_id = executions.snapshot_id INNER JOIN activity_snapshot_category_options AS snapshot_options " +
            "ON snapshot_options.snapshot_field_id = snapshot_fields.id LEFT JOIN activity_template_category_options AS source_options " +
            "ON source_options.id = snapshot_options.source_option_id WHERE executions.statistics_series_id = :seriesId " +
            "AND executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL " +
            "AND snapshot_fields.source_field_id = :fieldId " +
            "GROUP BY snapshot_options.source_option_id, " +
            "CASE WHEN snapshot_options.source_option_id IS NULL THEN snapshot_options.id ELSE NULL END, source_options.label",
    )
    fun activityOptionMetadata(
        seriesId: String,
        fieldId: String,
    ): List<StatisticsOptionMetadataRow>

    @Query(
        "SELECT options.id AS source_option_id, NULL AS snapshot_option_id, options.label AS current_label, NULL AS fallback_label " +
            "FROM sequence_template_category_options AS options INNER JOIN sequence_template_fields AS source_fields " +
            "ON source_fields.id = options.sequence_template_field_id INNER JOIN sequence_templates AS templates " +
            "ON templates.id = source_fields.sequence_template_id WHERE options.sequence_template_field_id = :fieldId " +
            "AND templates.statistics_series_id = :seriesId " +
            "UNION ALL " +
            "SELECT snapshot_options.source_option_id, " +
            "CASE WHEN snapshot_options.source_option_id IS NULL THEN snapshot_options.id ELSE NULL END AS snapshot_option_id, " +
            "source_options.label AS current_label, " +
            "MIN(COALESCE(snapshot_options.local_label_override, snapshot_options.label_at_creation)) AS fallback_label " +
            "FROM sequence_executions AS executions INNER JOIN sequence_snapshot_fields AS snapshot_fields " +
            "ON snapshot_fields.sequence_snapshot_id = executions.snapshot_id INNER JOIN sequence_snapshot_category_options AS snapshot_options " +
            "ON snapshot_options.sequence_snapshot_field_id = snapshot_fields.id LEFT JOIN sequence_template_category_options AS source_options " +
            "ON source_options.id = snapshot_options.source_option_id WHERE executions.statistics_series_id = :seriesId " +
            "AND executions.status IN ('COMPLETED', 'ENDED_EARLY') AND snapshot_fields.source_field_id = :fieldId " +
            "GROUP BY snapshot_options.source_option_id, " +
            "CASE WHEN snapshot_options.source_option_id IS NULL THEN snapshot_options.id ELSE NULL END, source_options.label",
    )
    fun sequenceOptionMetadata(
        seriesId: String,
        fieldId: String,
    ): List<StatisticsOptionMetadataRow>

    @Query(
        "SELECT options.source_option_id, CASE WHEN options.source_option_id IS NULL THEN options.id ELSE NULL END AS snapshot_option_id, " +
            "COUNT(*) AS count FROM activity_executions AS executions " +
            "INNER JOIN activity_execution_field_values AS recorded_values ON recorded_values.activity_execution_id = executions.id " +
            "INNER JOIN activity_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "INNER JOIN activity_snapshot_category_options AS options ON options.id = recorded_values.category_option_id " +
            "WHERE executions.statistics_series_id = :seriesId AND fields.source_field_id = :fieldId " +
            "AND executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate) " +
            "GROUP BY options.source_option_id, CASE WHEN options.source_option_id IS NULL THEN options.id ELSE NULL END",
    )
    fun activityCategoryCounts(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
    ): List<StatisticsCategoryCountRow>

    @Query(
        "SELECT options.source_option_id, CASE WHEN options.source_option_id IS NULL THEN options.id ELSE NULL END AS snapshot_option_id, " +
            "COUNT(*) AS count FROM sequence_executions AS executions " +
            "INNER JOIN sequence_execution_field_values AS recorded_values ON recorded_values.sequence_execution_id = executions.id " +
            "INNER JOIN sequence_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "INNER JOIN sequence_snapshot_category_options AS options ON options.id = recorded_values.category_option_id " +
            "WHERE executions.statistics_series_id = :seriesId AND fields.source_field_id = :fieldId " +
            "AND executions.status IN ('COMPLETED', 'ENDED_EARLY') " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate) " +
            "GROUP BY options.source_option_id, CASE WHEN options.source_option_id IS NULL THEN options.id ELSE NULL END",
    )
    fun sequenceCategoryCounts(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
    ): List<StatisticsCategoryCountRow>
}
