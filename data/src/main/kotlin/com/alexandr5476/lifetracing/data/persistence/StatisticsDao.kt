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
    @ColumnInfo(name = "total_pause_ms") val totalPauseMs: Long,
    @ColumnInfo(name = "active_day_count") val activeDayCount: Long,
    @ColumnInfo(name = "first_performed_at_ms") val firstPerformedAtMs: Long?,
    @ColumnInfo(name = "last_performed_at_ms") val lastPerformedAtMs: Long?,
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

@Dao
@Suppress("TooManyFunctions", "MaxLineLength")
internal interface StatisticsDao {
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
        "SELECT active_duration_ms FROM activity_executions WHERE statistics_series_id = :seriesId " +
            "AND status = 'COMPLETED' AND deleted_at_ms IS NULL AND active_duration_ms IS NOT NULL " +
            "AND (:startDate IS NULL OR primary_local_date BETWEEN :startDate AND :endDate) ORDER BY active_duration_ms",
    )
    fun activityDurationValues(
        seriesId: String,
        startDate: String?,
        endDate: String?,
    ): List<Long>

    @Query(
        "SELECT active_duration_ms FROM sequence_executions WHERE statistics_series_id = :seriesId " +
            "AND status IN ('COMPLETED', 'ENDED_EARLY') AND active_duration_ms IS NOT NULL " +
            "AND (:startDate IS NULL OR primary_local_date BETWEEN :startDate AND :endDate) ORDER BY active_duration_ms",
    )
    fun sequenceDurationValues(
        seriesId: String,
        startDate: String?,
        endDate: String?,
    ): List<Long>

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
        "SELECT recorded_values.number_scaled FROM activity_executions AS executions " +
            "INNER JOIN activity_execution_field_values AS recorded_values ON recorded_values.activity_execution_id = executions.id " +
            "INNER JOIN activity_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "WHERE executions.statistics_series_id = :seriesId AND fields.source_field_id = :fieldId " +
            "AND executions.status = 'COMPLETED' AND executions.deleted_at_ms IS NULL " +
            "AND recorded_values.number_scaled IS NOT NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate) " +
            "ORDER BY recorded_values.number_scaled",
    )
    fun activityNumberValues(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
    ): List<Long>

    @Query(
        "SELECT recorded_values.number_scaled FROM sequence_executions AS executions " +
            "INNER JOIN sequence_execution_field_values AS recorded_values ON recorded_values.sequence_execution_id = executions.id " +
            "INNER JOIN sequence_snapshot_fields AS fields ON fields.id = recorded_values.snapshot_field_id " +
            "WHERE executions.statistics_series_id = :seriesId AND fields.source_field_id = :fieldId " +
            "AND executions.status IN ('COMPLETED', 'ENDED_EARLY') AND recorded_values.number_scaled IS NOT NULL " +
            "AND (:startDate IS NULL OR executions.primary_local_date BETWEEN :startDate AND :endDate) " +
            "ORDER BY recorded_values.number_scaled",
    )
    fun sequenceNumberValues(
        seriesId: String,
        fieldId: String,
        startDate: String?,
        endDate: String?,
    ): List<Long>

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
