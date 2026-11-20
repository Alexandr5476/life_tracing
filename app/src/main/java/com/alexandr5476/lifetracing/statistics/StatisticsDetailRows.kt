package com.alexandr5476.lifetracing.statistics

import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.CategoryValueStatistics
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsFieldDetail
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import java.io.Serializable

internal enum class DetailAnchor {
    HEADER,
    STATUS,
    PERIOD,
    SERIES,
    METRICS,
    FIELDS,
    RETRY,
}

internal enum class DetailSeriesMetric(
    val activityLabel: Int,
    val sequenceLabel: Int = activityLabel,
) {
    EXECUTIONS(R.string.statistics_executions),
    DURATION_SAMPLES(R.string.statistics_duration_samples, R.string.statistics_active_duration_samples),
    DURATION_TOTAL(R.string.statistics_duration_total, R.string.statistics_active_duration_total),
    DURATION_AVERAGE(R.string.statistics_duration_average, R.string.statistics_active_duration_average),
    DURATION_MEDIAN(R.string.statistics_duration_median, R.string.statistics_active_duration_median),
    DURATION_MINIMUM(R.string.statistics_duration_minimum, R.string.statistics_active_duration_minimum),
    DURATION_MAXIMUM(R.string.statistics_duration_maximum, R.string.statistics_active_duration_maximum),
    PAUSE_TOTAL(R.string.statistics_total_pause_idle),
    PAUSE_AVERAGE(R.string.statistics_average_pause_idle),
    ACTIVE_DAYS(R.string.statistics_active_days),
    FREQUENCY(R.string.statistics_average_per_active_day_count),
    FIRST_PERFORMED(R.string.statistics_first_performed),
    LAST_PERFORMED(R.string.statistics_last_performed),
}

// Compose lazy keys must be saveable in an Android Bundle. These tags encode the variant
// and full canonical value, while composite keys keep Field and option components separate.
@ConsistentCopyVisibility
internal data class DetailFieldKey private constructor(
    private val kind: DetailFieldKind,
    private val value: String,
) : Serializable {
    constructor(id: StatisticsFieldId) : this(
        when (id) {
            is StatisticsFieldId.Activity -> DetailFieldKind.ACTIVITY
            is StatisticsFieldId.Sequence -> DetailFieldKind.SEQUENCE
        },
        id.value,
    )

    val tag get() = "${kind.tag}-$value"

    private companion object {
        const val serialVersionUID = 1L
    }
}

@ConsistentCopyVisibility
internal data class DetailCategoryKey private constructor(
    val field: DetailFieldKey,
    private val kind: DetailOptionKind,
    private val value: String,
) : Serializable {
    constructor(field: DetailFieldKey, id: StatisticsCategoryOptionId) : this(
        field,
        when (id) {
            is StatisticsCategoryOptionId.ActivitySource -> DetailOptionKind.ACTIVITY
            is StatisticsCategoryOptionId.SequenceSource -> DetailOptionKind.SEQUENCE
            is StatisticsCategoryOptionId.SnapshotFallback -> DetailOptionKind.SNAPSHOT
        },
        id.value,
    )

    val optionTag get() = "${kind.tag}-$value"

    private companion object {
        const val serialVersionUID = 1L
    }
}

private enum class DetailFieldKind(
    val tag: String,
) {
    ACTIVITY("activity"),
    SEQUENCE("sequence"),
}

private enum class DetailOptionKind(
    val tag: String,
) {
    ACTIVITY("activity"),
    SEQUENCE("sequence"),
    SNAPSHOT("snapshot"),
}

internal data class DetailFieldMetricKey(
    val field: DetailFieldKey,
    val label: Int,
)

internal sealed interface DetailFieldRow {
    val key: Serializable

    data class Field(
        override val key: DetailFieldKey,
        val detail: StatisticsFieldDetail,
        val duplicateName: Boolean,
    ) : DetailFieldRow

    data class Option(
        override val key: DetailCategoryKey,
        val owner: Field,
        val value: CategoryValueStatistics,
        val duplicateLabel: Boolean,
    ) : DetailFieldRow
}

/** An index into the current publication. Rows reference canonical facts without copying samples or formatting text. */
internal class StatisticsDetailRows(
    detail: StatisticsSeriesDetail?,
) {
    val metrics = if (detail == null) emptyList() else seriesMetrics(detail)
    val fields = detail?.fields.orEmpty().toDetailRows()
    private val seriesStart = DetailAnchor.METRICS.ordinal + 1
    val fieldsHeadingIndex = seriesStart + metrics.size
    private val metricIndices = metrics.withIndex().associate { it.value to seriesStart + it.index }
    private val fieldIndices = fields.withIndex().associate { it.value.key to fieldsHeadingIndex + 1 + it.index }

    fun index(target: Any): Int? =
        when (target) {
            DetailAnchor.RETRY -> DetailAnchor.STATUS.ordinal
            DetailAnchor.FIELDS -> fieldsHeadingIndex.takeIf { fields.isNotEmpty() }
            DetailAnchor.SERIES, DetailAnchor.METRICS ->
                (target as DetailAnchor).ordinal.takeIf {
                    metrics.isNotEmpty()
                }
            is DetailAnchor -> target.ordinal
            is DetailSeriesMetric -> metricIndices[target]
            is DetailFieldMetricKey -> fieldIndices[target.field]
            else -> fieldIndices[target]
        }

    fun survivingTarget(target: Any): Any =
        when {
            index(target) != null -> target
            target is DetailCategoryKey && index(target.field) != null -> target.field
            fields.isNotEmpty() -> DetailAnchor.FIELDS
            metrics.isNotEmpty() -> DetailAnchor.METRICS
            else -> DetailAnchor.STATUS
        }
}

private fun seriesMetrics(detail: StatisticsSeriesDetail): List<DetailSeriesMetric> =
    DetailSeriesMetric.entries.filter { metric ->
        val isPause = metric == DetailSeriesMetric.PAUSE_TOTAL || metric == DetailSeriesMetric.PAUSE_AVERAGE
        detail is StatisticsSeriesDetail.Sequence || !isPause
    }

private fun List<StatisticsFieldDetail>.toDetailRows(): List<DetailFieldRow> {
    val nameCounts = groupingBy { it.field.displayName }.eachCount()
    return buildList {
        for (field in this@toDetailRows) {
            val key = DetailFieldKey(field.field.id)
            val owner = DetailFieldRow.Field(key, field, nameCounts.getValue(field.field.displayName) > 1)
            add(owner)
            if (field is StatisticsFieldDetail.Category) {
                val values = field.statistics.values
                val labelCounts = values.groupingBy { it.displayLabel }.eachCount()
                for (value in values) {
                    add(
                        DetailFieldRow.Option(
                            DetailCategoryKey(key, value.id),
                            owner,
                            value,
                            labelCounts.getValue(value.displayLabel) > 1,
                        ),
                    )
                }
            }
        }
    }
}
