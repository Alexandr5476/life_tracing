@file:Suppress("FunctionNaming", "LongParameterList", "TooManyFunctions")

package com.alexandr5476.lifetracing.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.CountRatio
import com.alexandr5476.lifetracing.domain.NumberFieldStatistics
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsFieldDetail
import com.alexandr5476.lifetracing.ui.theme.spacing
import java.math.BigInteger
import java.util.Locale

@Composable
internal fun FieldContent(
    row: DetailFieldRow.Field,
    focus: StatisticsOverviewFocus,
    admission: StatisticsActionAdmission,
) {
    val name = fieldContext(row)
    val field = row.detail
    val type = stringResource(fieldTypeLabel(field))
    val context = stringResource(R.string.statistics_detail_field_context, name, type)
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(
                context,
                detailReadTarget(focus, row.key, admission)
                    .testTag("statistics-field-${row.key.tag}")
                    .semantics {
                        heading()
                        this[DetailFieldIdentity] = field.field.id
                    },
                style = MaterialTheme.typography.titleSmall,
            )
            FieldMetrics(row.key, context, field, focus, admission)
        }
    }
}

@Composable
private fun FieldMetrics(
    fieldKey: DetailFieldKey,
    context: String,
    field: StatisticsFieldDetail,
    focus: StatisticsOverviewFocus,
    admission: StatisticsActionAdmission,
) {
    val locale = LocalConfiguration.current.locales[0]
    val metrics =
        when (field) {
            is StatisticsFieldDetail.Number -> numberMetrics(field.statistics, locale)
            is StatisticsFieldDetail.Category -> {
                val s = field.statistics
                sampleMetrics(s.recordedCount, s.missingCount, s.relevantExecutionCount, s.coverage, locale) +
                    (R.string.statistics_category_values to null)
            }
            is StatisticsFieldDetail.Text -> listOf(R.string.statistics_field_text to null)
        }
    for ((label, value) in metrics) {
        val metricId =
            if (label == R.string.statistics_detail_default_precision) R.string.statistics_detail_precision else label
        val target = DetailFieldMetricKey(fieldKey, metricId)
        key(target) {
            val modifier = detailReadTarget(focus, target, admission, context)
            Text(
                if (value == null) stringResource(label) else stringResource(label, value),
                if (label == R.string.statistics_category_values) modifier.semantics { heading() } else modifier,
            )
        }
    }
}

@Composable
private fun numberMetrics(
    statistics: NumberFieldStatistics,
    locale: Locale,
): List<Pair<Int, String>> {
    val s = statistics.values
    val unit = statistics.field.unit
    val precision = statistics.field.displayPrecision
    val unavailable = stringResource(R.string.statistics_unavailable)
    return sampleMetrics(
        statistics.recordedCount,
        statistics.missingCount,
        statistics.relevantExecutionCount,
        statistics.coverage,
        locale,
    ) + numberMetadata(unit, precision) +
        listOf(
            R.string.statistics_number_total to
                numberWithUnit(
                    s.totalScaled
                        .takeIf {
                            s.sampleCount > 0
                        }?.let { statisticsScaledInteger(it, locale) },
                    unit,
                    unavailable,
                ),
            R.string.statistics_number_average to
                numberWithUnit(
                    s.averageScaled?.let { statisticsScaledExact(it, precision, locale) },
                    unit,
                    unavailable,
                ),
            R.string.statistics_number_median to
                numberWithUnit(s.medianScaled?.let { statisticsScaledExact(it, precision, locale) }, unit, unavailable),
            R.string.statistics_number_minimum to
                numberWithUnit(
                    s.minimumScaled?.let { statisticsScaledInteger(BigInteger.valueOf(it), locale) },
                    unit,
                    unavailable,
                ),
            R.string.statistics_number_maximum to
                numberWithUnit(
                    s.maximumScaled?.let { statisticsScaledInteger(BigInteger.valueOf(it), locale) },
                    unit,
                    unavailable,
                ),
        )
}

private fun numberMetadata(
    unit: String?,
    precision: Int?,
): List<Pair<Int, String>> =
    buildList {
        if (!unit.isNullOrBlank()) add(R.string.statistics_detail_unit to unit)
        val label =
            if (precision ==
                null
            ) {
                R.string.statistics_detail_default_precision
            } else {
                R.string.statistics_detail_precision
            }
        add(label to (precision ?: DETAIL_NUMBER_STORAGE_SCALE).toString())
    }

@Composable
private fun numberWithUnit(
    value: String?,
    unit: String?,
    unavailable: String,
): String =
    when {
        value == null -> unavailable
        unit.isNullOrBlank() -> value
        else -> stringResource(R.string.statistics_detail_number_unit, value, unit)
    }

@Composable
private fun sampleMetrics(
    recorded: Long,
    missing: Long,
    relevant: Long,
    coverage: CountRatio,
    locale: Locale,
): List<Pair<Int, String>> =
    listOf(
        R.string.statistics_detail_relevant to statisticsCount(relevant, locale),
        R.string.statistics_field_recorded to statisticsCount(recorded, locale),
        R.string.statistics_field_missing to statisticsCount(missing, locale),
        R.string.statistics_field_coverage to ratioText(coverage, locale),
    )

@Composable
internal fun CategoryContent(
    row: DetailFieldRow.Option,
    focus: StatisticsOverviewFocus,
    admission: StatisticsActionAdmission,
) {
    val option = row.value
    val locale = LocalConfiguration.current.locales[0]
    val provenance =
        when (option.id) {
            is StatisticsCategoryOptionId.ActivitySource -> R.string.statistics_category_activity_source
            is StatisticsCategoryOptionId.SequenceSource -> R.string.statistics_category_sequence_source
            is StatisticsCategoryOptionId.SnapshotFallback -> R.string.statistics_category_snapshot_source
        }
    val label =
        if (row.duplicateLabel) {
            stringResource(R.string.statistics_detail_duplicate_name, option.displayLabel, option.id.value)
        } else {
            option.displayLabel
        }
    val context =
        stringResource(R.string.statistics_category_context, fieldContext(row.owner), stringResource(provenance))
    Text(
        stringResource(
            R.string.statistics_category_recorded_share,
            label,
            statisticsCount(option.count, locale),
            ratioText(option.recordedShare, locale),
        ),
        detailReadTarget(focus, row.key, admission, context)
            .testTag("statistics-category-${row.key.field.tag}-${row.key.optionTag}")
            .semantics {
                this[DetailFieldIdentity] = row.owner.detail.field.id
                this[DetailOptionIdentity] = option.id
            },
    )
}

@Composable
private fun ratioText(
    value: CountRatio,
    locale: Locale,
): String {
    val percent = statisticsPercent(value, locale) ?: stringResource(R.string.statistics_unavailable)
    return stringResource(
        R.string.statistics_detail_ratio,
        statisticsCount(value.numerator, locale),
        statisticsCount(value.denominator, locale),
        percent,
    )
}

@Composable
private fun fieldContext(row: DetailFieldRow.Field): String {
    val field = row.detail.field
    return if (row.duplicateName) {
        stringResource(R.string.statistics_detail_duplicate_name, field.displayName, field.id.value)
    } else {
        field.displayName
    }
}

private fun fieldTypeLabel(field: StatisticsFieldDetail): Int =
    when (field) {
        is StatisticsFieldDetail.Number -> R.string.statistics_detail_number_field
        is StatisticsFieldDetail.Category -> R.string.statistics_detail_category_field
        is StatisticsFieldDetail.Text -> R.string.statistics_detail_text_field
    }
