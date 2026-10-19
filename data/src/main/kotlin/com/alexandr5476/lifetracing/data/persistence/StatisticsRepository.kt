@file:Suppress("TooManyFunctions", "LargeClass")

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import com.alexandr5476.lifetracing.domain.ActivitySeriesStatistics
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryFieldStatistics
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CategoryValueStatistics
import com.alexandr5476.lifetracing.domain.CountRatio
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DurationDistribution
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.GlobalStatistics
import com.alexandr5476.lifetracing.domain.NumberDistribution
import com.alexandr5476.lifetracing.domain.NumberFieldStatistics
import com.alexandr5476.lifetracing.domain.SequenceSeriesStatistics
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
import com.alexandr5476.lifetracing.domain.StatisticsFieldDescriptor
import com.alexandr5476.lifetracing.domain.StatisticsFieldDetail
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsOverview
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeries
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesPeriodSummary
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSummary
import com.alexandr5476.lifetracing.domain.dateRangeOrNull
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable

// The selection independently counts all eligible samples but returns only scalar order statistics.
internal fun StatisticsDurationSelectionRow.readDurationDistribution(
    aggregate: StatisticsSeriesAggregateRow?,
): DurationDistribution {
    val expectedCount = aggregate?.durationSampleCount ?: 0
    require(sampleCount == expectedCount) { "Duration aggregate disagrees with eligible sample count" }
    val expectedSelection =
        when {
            expectedCount == 0L -> 0
            expectedCount % 2 == 0L -> 2
            else -> 1
        }
    require(selectedCount == expectedSelection.toLong()) { "Duration median selection is incomplete" }
    val endpoints =
        when (expectedSelection) {
            0 -> {
                require(lowerMedianMs == null && upperMedianMs == null) { "Unexpected empty median endpoints" }
                emptyList()
            }
            1 -> {
                require(lowerMedianMs == upperMedianMs) { "Odd median endpoints disagree" }
                listOf(requireNotNull(lowerMedianMs) { "Missing median endpoint" })
            }
            else -> listOf(requireNotNull(lowerMedianMs), requireNotNull(upperMedianMs))
        }
    return StatisticsDistributionCalculator.durations(
        expectedCount,
        BigInteger.valueOf(aggregate?.totalDurationMs ?: 0),
        aggregate?.minimumDurationMs,
        aggregate?.maximumDurationMs,
        endpoints,
    )
}

class StatisticsRepository internal constructor(
    private val database: LifeTracingDatabase,
    private val nextStatisticsSeriesId: () -> StatisticsSeriesId,
) {
    fun global(period: StatisticsPeriod): GlobalStatistics = transaction { globalLocked(period) }

    fun overview(period: StatisticsPeriod): StatisticsOverview =
        transaction { StatisticsOverview(globalLocked(period), seriesSummariesLocked(period)) }

    private fun globalLocked(period: StatisticsPeriod): GlobalStatistics {
        val range = period.dateRangeOrNull()
        val row =
            database.statisticsDao().global(
                range?.startDate?.toString(),
                range?.endDateInclusive?.toString(),
                ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value,
            )
        return GlobalStatistics(
            totalTrackedDuration = Duration.ofMillis(row.totalTrackedMs),
            topLevelExecutionCount = row.executionCount,
            activeDayCount = row.activeDayCount,
            averageTrackedMillisecondsPerActiveDay = average(row.totalTrackedMs, row.activeDayCount),
            averageTopLevelExecutionsPerActiveDay = average(row.executionCount, row.activeDayCount),
            totalSequencePauseIdleDuration = Duration.ofMillis(row.sequencePauseMs),
            oneOffActivityExecutionCount = row.oneOffCount,
            oneOffActivityTrackedDuration = Duration.ofMillis(row.oneOffTrackedMs),
            firstPrimaryDate = row.firstPrimaryDate?.let(LocalDate::parse),
            lastPrimaryDate = row.lastPrimaryDate?.let(LocalDate::parse),
            calendarDayCount = range?.calendarDayCount,
            averageTrackedMillisecondsPerCalendarDay =
                range?.let { average(row.totalTrackedMs, it.calendarDayCount) },
        )
    }

    fun seriesCatalog(): List<StatisticsSeriesSummary> = transaction(::seriesCatalogLocked)

    fun seriesSummaries(period: StatisticsPeriod): List<StatisticsSeriesPeriodSummary> =
        transaction { seriesSummariesLocked(period) }

    private fun seriesSummariesLocked(period: StatisticsPeriod): List<StatisticsSeriesPeriodSummary> {
        val range = period.dateRangeOrNull()
        val start = range?.startDate?.toString()
        val end = range?.endDateInclusive?.toString()
        val rows =
            (
                database.statisticsDao().activitySeriesAggregates(null, start, end) +
                    database.statisticsDao().sequenceSeriesAggregates(null, start, end) +
                    listOfNotNull(
                        database.statisticsDao().oneOffAggregate(
                            ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value,
                            start,
                            end,
                        ),
                    )
            ).associateBy(StatisticsSeriesAggregateRow::seriesId)
        return seriesCatalogLocked().map { series ->
            val row = rows[series.id.value]
            val samples = row?.durationSampleCount ?: 0
            val total = row?.totalDurationMs ?: 0
            StatisticsSeriesPeriodSummary(
                series,
                row?.executionCount ?: 0,
                samples,
                Duration.ofMillis(total),
                average(total, samples),
                row?.activeDayCount ?: 0,
            )
        }
    }

    fun activitySeries(
        seriesId: StatisticsSeriesId,
        period: StatisticsPeriod,
    ): ActivitySeriesStatistics =
        transaction {
            val series = requireSeries(seriesId, StatisticsSeriesKind.ACTIVITY)
            val (start, end) = period.bounds()
            activitySeriesLocked(series, start, end)
        }

    fun sequenceSeries(
        seriesId: StatisticsSeriesId,
        period: StatisticsPeriod,
    ): SequenceSeriesStatistics =
        transaction {
            val series = requireSeries(seriesId, StatisticsSeriesKind.SEQUENCE)
            val (start, end) = period.bounds()
            sequenceSeriesLocked(series, start, end)
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun seriesDetail(
        seriesId: StatisticsSeriesId,
        period: StatisticsPeriod,
    ): StatisticsSeriesDetail =
        transaction {
            val series = requireSeries(seriesId)
            require(series.kind != StatisticsSeriesKind.ONE_OFF_BUCKET) { "One-off Series has no reusable detail" }
            val (start, end) = period.bounds()
            val statistics =
                when (series.kind) {
                    StatisticsSeriesKind.ACTIVITY -> activitySeriesLocked(series, start, end)
                    StatisticsSeriesKind.SEQUENCE -> sequenceSeriesLocked(series, start, end)
                    StatisticsSeriesKind.ONE_OFF_BUCKET -> error("One-off Series has no reusable detail")
                }
            val relevant =
                when (statistics) {
                    is ActivitySeriesStatistics -> statistics.executionCount
                    is SequenceSeriesStatistics -> statistics.executionCount
                    else -> error("Unsupported Series statistics")
                }
            val catalog = fieldCatalogLocked(series, seriesId)
            val dao = database.statisticsDao()
            val numberFields = catalog.filter { it.type == CustomFieldType.NUMBER }
            val numberSummaries =
                if (numberFields.isEmpty()) {
                    emptyMap()
                } else {
                    val rows =
                        when (series.kind) {
                            StatisticsSeriesKind.ACTIVITY -> dao.activityNumberSummaries(seriesId.value, start, end)
                            StatisticsSeriesKind.SEQUENCE -> dao.sequenceNumberSummaries(seriesId.value, start, end)
                            StatisticsSeriesKind.ONE_OFF_BUCKET -> error("One-off Series has no reusable detail")
                        }
                    ExactNumberSummary.readGrouped(rows, numberFields.map { it.id.value }.toSet())
                }
            val executionCandidates =
                if (numberSummaries.isEmpty()) 0L else numberExecutionCandidates(seriesId, series.kind, start to end)
            val numberDistributions =
                groupedNumberDistributions(
                    seriesId,
                    series.kind,
                    numberFields,
                    numberSummaries,
                    start to end,
                    executionCandidates,
                )
            val (optionMetadata, categoryCounts) =
                if (catalog.any { it.type == CustomFieldType.CATEGORY }) {
                    when (series.kind) {
                        StatisticsSeriesKind.ACTIVITY ->
                            dao.activityFieldOptionMetadata(seriesId.value) to
                                dao.activityFieldCategoryCounts(seriesId.value, start, end)
                        StatisticsSeriesKind.SEQUENCE ->
                            dao.sequenceFieldOptionMetadata(seriesId.value) to
                                dao.sequenceFieldCategoryCounts(seriesId.value, start, end)
                        StatisticsSeriesKind.ONE_OFF_BUCKET ->
                            emptyList<StatisticsFieldOptionMetadataRow>() to
                                emptyList()
                    }
                } else {
                    emptyList<StatisticsFieldOptionMetadataRow>() to emptyList()
                }
            val metadataByField = optionMetadata.groupBy(StatisticsFieldOptionMetadataRow::sourceFieldId)
            val countsByField = categoryCounts.groupBy(StatisticsFieldCategoryCountRow::sourceFieldId)
            val fields =
                catalog.map { field ->
                    when (field.type) {
                        CustomFieldType.NUMBER ->
                            StatisticsFieldDetail.Number(
                                numberStatistics(
                                    field,
                                    relevant,
                                    numberDistributions[field.id.value]
                                        ?: ExactNumberSummary.empty().distribution(
                                            StatisticsNumberSelectionRow(0, 0, null, null),
                                        ),
                                ),
                            )
                        CustomFieldType.CATEGORY ->
                            StatisticsFieldDetail.Category(
                                categoryStatistics(
                                    series.kind,
                                    field,
                                    relevant,
                                    optionMetadataRows(
                                        metadataByField[field.id.value].orEmpty().map {
                                            StatisticsOptionMetadataRow(
                                                it.sourceOptionId,
                                                it.snapshotOptionId,
                                                it.currentLabel,
                                                it.fallbackLabel,
                                            )
                                        },
                                    ),
                                    countsByField[field.id.value].orEmpty().map {
                                        StatisticsCategoryCountRow(it.sourceOptionId, it.snapshotOptionId, it.count)
                                    },
                                ),
                            )
                        CustomFieldType.TEXT -> StatisticsFieldDetail.Text(field)
                    }
                }
            when (statistics) {
                is ActivitySeriesStatistics -> StatisticsSeriesDetail.Activity(statistics, fields)
                is SequenceSeriesStatistics -> StatisticsSeriesDetail.Sequence(statistics, fields)
                else -> error("Unsupported Series statistics")
            }
        }

    private fun activitySeriesLocked(
        series: StatisticsSeriesSummary,
        start: String?,
        end: String?,
    ): ActivitySeriesStatistics {
        val row = database.statisticsDao().activitySeriesAggregates(series.id.value, start, end).singleOrNull()
        val distribution =
            database
                .statisticsDao()
                .activityDurationSelection(series.id.value, start, end, row?.durationSampleCount ?: 0)
                .readDurationDistribution(row)
        return ActivitySeriesStatistics(
            series,
            row?.executionCount ?: 0,
            distribution,
            row?.activeDayCount ?: 0,
            average(row?.executionCount ?: 0, row?.activeDayCount ?: 0),
            row?.firstPerformedAtMs?.let(Instant::ofEpochMilli),
            row?.lastPerformedAtMs?.let(Instant::ofEpochMilli),
        )
    }

    private fun sequenceSeriesLocked(
        series: StatisticsSeriesSummary,
        start: String?,
        end: String?,
    ): SequenceSeriesStatistics {
        val row = database.statisticsDao().sequenceSeriesAggregates(series.id.value, start, end).singleOrNull()
        val distribution =
            database
                .statisticsDao()
                .sequenceDurationSelection(series.id.value, start, end, row?.durationSampleCount ?: 0)
                .readDurationDistribution(row)
        val count = row?.executionCount ?: 0
        val pause = row?.totalPauseMs ?: 0
        return SequenceSeriesStatistics(
            series,
            count,
            distribution,
            Duration.ofMillis(pause),
            average(pause, count),
            row?.activeDayCount ?: 0,
            average(count, row?.activeDayCount ?: 0),
            row?.firstPerformedAtMs?.let(Instant::ofEpochMilli),
            row?.lastPerformedAtMs?.let(Instant::ofEpochMilli),
        )
    }

    fun fieldCatalog(seriesId: StatisticsSeriesId): List<StatisticsFieldDescriptor> =
        transaction { fieldCatalogLocked(requireSeries(seriesId), seriesId) }

    fun numberFieldStatistics(
        seriesId: StatisticsSeriesId,
        fieldId: StatisticsFieldId,
        period: StatisticsPeriod,
    ): NumberFieldStatistics =
        transaction {
            val series = requireSeries(seriesId)
            val field = requireField(series, seriesId, fieldId, CustomFieldType.NUMBER)
            val (start, end) = period.bounds()
            val relevant =
                when (series.kind) {
                    StatisticsSeriesKind.ACTIVITY ->
                        database.statisticsDao().activityNumberExecutionCount(seriesId.value, start, end)
                    StatisticsSeriesKind.SEQUENCE ->
                        database.statisticsDao().sequenceNumberExecutionCount(seriesId.value, start, end)
                    StatisticsSeriesKind.ONE_OFF_BUCKET -> error("One-off reusable Field Statistics are not supported")
                }
            numberFieldStatisticsLocked(series, field, relevant, start, end)
        }

    fun categoryFieldStatistics(
        seriesId: StatisticsSeriesId,
        fieldId: StatisticsFieldId,
        period: StatisticsPeriod,
    ): CategoryFieldStatistics =
        transaction {
            val series = requireSeries(seriesId)
            val field = requireField(series, seriesId, fieldId, CustomFieldType.CATEGORY)
            val (start, end) = period.bounds()
            val relevant = executionCount(series.kind, seriesId, start, end)
            categoryFieldStatisticsLocked(series, field, relevant, start, end)
        }

    private fun numberFieldStatisticsLocked(
        series: StatisticsSeriesSummary,
        field: StatisticsFieldDescriptor,
        relevant: Long,
        start: String?,
        end: String?,
    ): NumberFieldStatistics {
        val dao = database.statisticsDao()
        val bits =
            when (field.id) {
                is StatisticsFieldId.Activity ->
                    dao.activityNumberBitCounts(
                        series.id.value,
                        field.id.value,
                        start,
                        end,
                    )
                is StatisticsFieldId.Sequence ->
                    dao.sequenceNumberBitCounts(
                        series.id.value,
                        field.id.value,
                        start,
                        end,
                    )
            }
        val summary = ExactNumberSummary.read(bits)
        val candidates =
            if (summary.sampleCount == 0L) 0L else numberExecutionCandidates(series.id, series.kind, start to end)
        val distribution = numberDistribution(series.id, field, summary, start to end, candidates)
        return numberStatistics(field, relevant, distribution)
    }

    private fun numberExecutionCandidates(
        seriesId: StatisticsSeriesId,
        kind: StatisticsSeriesKind,
        bounds: Pair<String?, String?>,
    ): Long {
        val (start, end) = bounds
        val dao = database.statisticsDao()
        return when (kind) {
            StatisticsSeriesKind.ACTIVITY -> dao.activityNumberExecutionCandidateCount(seriesId.value, start, end)
            StatisticsSeriesKind.SEQUENCE -> dao.sequenceNumberExecutionCandidateCount(seriesId.value, start, end)
            StatisticsSeriesKind.ONE_OFF_BUCKET -> error("One-off Series has no reusable Fields")
        }
    }

    /** Share the probe budget and execution traversal across all populated result Fields. */
    @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private fun groupedNumberDistributions(
        seriesId: StatisticsSeriesId,
        kind: StatisticsSeriesKind,
        fields: List<StatisticsFieldDescriptor>,
        summaries: Map<String, ExactNumberSummary>,
        bounds: Pair<String?, String?>,
        executionCandidates: Long,
    ): Map<String, NumberDistribution> {
        if (summaries.isEmpty()) return emptyMap()
        val populated = fields.filter { it.id.value in summaries }
        if (populated.size == 1) {
            val field = populated.single()
            return mapOf(
                field.id.value to
                    numberDistribution(
                        seriesId,
                        field,
                        summaries.getValue(field.id.value),
                        bounds,
                        executionCandidates,
                    ),
            )
        }
        val dao = database.statisticsDao()
        val budget = NumberMedianProbeBudget(executionCandidates, populated.size)
        val result = mutableMapOf<String, NumberDistribution>()
        for (field in populated) {
            val limit = budget.nextLimit
            if (limit != null) {
                val candidates =
                    when (kind) {
                        StatisticsSeriesKind.ACTIVITY -> dao.activityNumberFieldCandidateCount(field.id.value, limit)
                        StatisticsSeriesKind.SEQUENCE -> dao.sequenceNumberFieldCandidateCount(field.id.value, limit)
                        StatisticsSeriesKind.ONE_OFF_BUCKET -> error("One-off Series has no reusable Fields")
                    }
                if (budget.record(candidates)) {
                    result[field.id.value] =
                        numberDistribution(
                            seriesId,
                            field,
                            summaries.getValue(field.id.value),
                            bounds,
                            executionCandidates,
                            true,
                        )
                }
            }
        }
        val remaining = summaries.filterKeys { it !in result }
        if (remaining.size > DIRECT_SCALAR_FIELD_LIMIT) {
            // Preserve fast convergence for duplicate-heavy distributions. Wide small sets instead
            // finish with at most sixteen scalar sorts after three shared passes. Host VM evidence
            // covers 3/4/8/16 and the adjacent 17/24/32/40 counts; larger sets keep shared ranks.
            val smallFallbackPassLimit =
                if (remaining.size <= SMALL_FALLBACK_FIELD_LIMIT) SMALL_FALLBACK_TRIAL_PASSES else Long.SIZE_BITS + 1
            val selections =
                GroupedNumberMedians(remaining).select(smallFallbackPassLimit) { pivots ->
                    dao.groupedNumberRanks(groupedNumberRankQuery(kind, seriesId.value, bounds, pivots))
                }
            selections.forEach { (fieldId, selection) ->
                result[fieldId] = remaining.getValue(fieldId).distribution(selection)
            }
        }
        // Up to three Fields skip the trial entirely. The scalar cap stays constant as P grows.
        populated.filter { it.id.value !in result }.forEach { field ->
            result[field.id.value] =
                numberDistribution(
                    seriesId,
                    field,
                    remaining.getValue(field.id.value),
                    bounds,
                    executionCandidates,
                    false,
                )
        }
        return result
    }

    @Suppress("LongParameterList")
    private fun numberDistribution(
        seriesId: StatisticsSeriesId,
        field: StatisticsFieldDescriptor,
        summary: ExactNumberSummary,
        bounds: Pair<String?, String?>,
        executionCandidateCount: Long,
        fieldFirstOverride: Boolean? = null,
    ): NumberDistribution {
        val (start, end) = bounds
        val dao = database.statisticsDao()
        val selection =
            if (summary.sampleCount == 0L) {
                StatisticsNumberSelectionRow(0, 0, null, null)
            } else {
                val fieldFirst =
                    fieldFirstOverride
                        ?: useFieldFirstNumberSelection(field.id, summary.sampleCount, executionCandidateCount)
                when (field.id) {
                    is StatisticsFieldId.Activity -> {
                        val select =
                            if (fieldFirst) {
                                dao::activityDenseSeriesNumberSelection
                            } else {
                                dao::activitySeriesNumberSelection
                            }
                        select(seriesId.value, field.id.value, start, end, summary.sampleCount)
                    }
                    is StatisticsFieldId.Sequence -> {
                        val select =
                            if (fieldFirst) {
                                dao::sequenceDenseSeriesNumberSelection
                            } else {
                                dao::sequenceSeriesNumberSelection
                            }
                        select(seriesId.value, field.id.value, start, end, summary.sampleCount)
                    }
                }
            }
        return summary.distribution(selection)
    }

    private fun useFieldFirstNumberSelection(
        fieldId: StatisticsFieldId,
        sampleCount: Long,
        executionCandidateCount: Long,
    ): Boolean {
        val dao = database.statisticsDao()
        val limit = NumberMedianReadPolicy.candidateLimit(executionCandidateCount)
        val candidates =
            when (fieldId) {
                is StatisticsFieldId.Activity -> dao.activityNumberFieldCandidateCount(fieldId.value, limit)
                is StatisticsFieldId.Sequence -> dao.sequenceNumberFieldCandidateCount(fieldId.value, limit)
            }
        return NumberMedianReadPolicy.useFieldFirst(sampleCount, executionCandidateCount, candidates)
    }

    private fun numberStatistics(
        field: StatisticsFieldDescriptor,
        relevant: Long,
        distribution: NumberDistribution,
    ): NumberFieldStatistics {
        require(distribution.sampleCount <= relevant) { "Recorded Number values exceed relevant executions" }
        return NumberFieldStatistics(
            field,
            relevant,
            distribution.sampleCount,
            relevant - distribution.sampleCount,
            CountRatio(distribution.sampleCount, relevant),
            distribution,
        )
    }

    private fun categoryFieldStatisticsLocked(
        series: StatisticsSeriesSummary,
        field: StatisticsFieldDescriptor,
        relevant: Long,
        start: String?,
        end: String?,
    ): CategoryFieldStatistics =
        categoryStatistics(
            series.kind,
            field,
            relevant,
            optionMetadata(series.kind, series.id, field.id),
            categoryCounts(series.kind, series.id, field.id, start, end),
        )

    private fun categoryStatistics(
        kind: StatisticsSeriesKind,
        field: StatisticsFieldDescriptor,
        relevant: Long,
        metadata: Map<OptionKey, String>,
        countRows: List<StatisticsCategoryCountRow>,
    ): CategoryFieldStatistics {
        val counts = countRows.associateBy(::optionKey)
        val recorded = counts.values.sumOf(StatisticsCategoryCountRow::count)
        require(recorded <= relevant) { "Recorded Category values exceed relevant executions" }
        val values =
            (metadata.keys + counts.keys)
                .distinct()
                .map { key ->
                    val count = counts[key]?.count ?: 0
                    CategoryValueStatistics(
                        optionIdentity(kind, key),
                        metadata[key] ?: key.fallbackId ?: key.sourceId.orEmpty(),
                        count,
                        CountRatio(count, recorded),
                    )
                }.sortedWith(categoryValueComparator)
        return CategoryFieldStatistics(
            field,
            relevant,
            recorded,
            relevant - recorded,
            CountRatio(recorded, relevant),
            values,
        )
    }

    fun startNewActivityStatisticsSeries(
        templateId: ActivityTemplateId,
        at: Instant,
    ): StatisticsSeries =
        transaction {
            val current =
                requireNotNull(database.activityTemplateDao().getAggregate(templateId.value)) {
                    "Unknown ActivityTemplate: ${templateId.value}"
                }.also { it.toDomain() }
            require(current.template.deletedAtMs == null) { "Archived ActivityTemplate cannot start a new Series" }
            requireSeriesKind(current.template.statisticsSeriesId, StatisticsSeriesKind.ACTIVITY)
            val atMs = at.toEpochMilli()
            require(atMs >= current.template.updatedAtMs) { "Statistics Series split time is out of order" }
            val series =
                StatisticsSeries(
                    nextStatisticsSeriesId(),
                    StatisticsSeriesKind.ACTIVITY,
                    current.template.name,
                    Instant.ofEpochMilli(atMs),
                    null,
                )
            database.statisticsSeriesDao().insert(series.toEntity())
            check(
                database.activityTemplateDao().startNewStatisticsSeries(
                    templateId.value,
                    current.template.statisticsSeriesId,
                    current.template.revision,
                    series.id.value,
                    atMs,
                ) == 1,
            ) { "ActivityTemplate changed concurrently" }
            series
        }

    fun startNewSequenceStatisticsSeries(
        templateId: SequenceTemplateId,
        at: Instant,
    ): StatisticsSeries =
        transaction {
            val current =
                requireNotNull(database.sequenceTemplateDao().getAggregate(templateId.value)) {
                    "Unknown SequenceTemplate: ${templateId.value}"
                }.also { it.toDomain() }
            require(current.template.deletedAtMs == null) { "Archived SequenceTemplate cannot start a new Series" }
            requireSeriesKind(current.template.statisticsSeriesId, StatisticsSeriesKind.SEQUENCE)
            val atMs = at.toEpochMilli()
            require(atMs >= current.template.updatedAtMs) { "Statistics Series split time is out of order" }
            val series =
                StatisticsSeries(
                    nextStatisticsSeriesId(),
                    StatisticsSeriesKind.SEQUENCE,
                    current.template.name,
                    Instant.ofEpochMilli(atMs),
                    null,
                )
            database.statisticsSeriesDao().insert(series.toEntity())
            check(
                database.sequenceTemplateDao().startNewStatisticsSeries(
                    templateId.value,
                    current.template.statisticsSeriesId,
                    current.template.revision,
                    series.id.value,
                    atMs,
                ) == 1,
            ) { "SequenceTemplate changed concurrently" }
            series
        }

    private fun seriesCatalogLocked(): List<StatisticsSeriesSummary> =
        database.statisticsDao().seriesCatalog().map { row ->
            val kind = parseKind(row.kind)
            val activitySources = row.activeActivitySources + row.archivedActivitySources
            val sequenceSources = row.activeSequenceSources + row.archivedSequenceSources
            when (kind) {
                StatisticsSeriesKind.ACTIVITY ->
                    require(sequenceSources == 0L) { "Activity Series has a Sequence source" }
                StatisticsSeriesKind.SEQUENCE ->
                    require(activitySources == 0L) { "Sequence Series has an Activity source" }
                StatisticsSeriesKind.ONE_OFF_BUCKET ->
                    require(activitySources == 0L && sequenceSources == 0L) {
                        "One-off Series cannot have a Template source"
                    }
            }
            val sourceState =
                when (kind) {
                    StatisticsSeriesKind.ONE_OFF_BUCKET -> StatisticsSeriesSourceState.SYSTEM_ONE_OFF
                    StatisticsSeriesKind.ACTIVITY ->
                        sourceState(row.activeActivitySources, row.archivedActivitySources)
                    StatisticsSeriesKind.SEQUENCE ->
                        sourceState(row.activeSequenceSources, row.archivedSequenceSources)
                }
            StatisticsSeriesSummary(
                StatisticsSeriesId(row.id),
                kind,
                row.displayName,
                row.archivedAtMs?.let(Instant::ofEpochMilli),
                sourceState,
            )
        }

    private fun requireSeries(
        id: StatisticsSeriesId,
        expectedKind: StatisticsSeriesKind? = null,
    ): StatisticsSeriesSummary {
        val series = seriesCatalogLocked().singleOrNull { it.id == id }
        requireNotNull(series) { "Unknown Statistics Series: ${id.value}" }
        expectedKind?.let { require(series.kind == it) { "Statistics Series kind mismatch" } }
        return series
    }

    private fun requireSeriesKind(
        id: String,
        expectedKind: StatisticsSeriesKind,
    ) {
        val row = requireNotNull(database.statisticsSeriesDao().getById(id)) { "Template Statistics Series is missing" }
        require(parseKind(row.kind) == expectedKind) { "Template Statistics Series kind mismatch" }
    }

    private fun fieldCatalogLocked(
        series: StatisticsSeriesSummary,
        seriesId: StatisticsSeriesId,
    ): List<StatisticsFieldDescriptor> {
        val rows =
            when (series.kind) {
                StatisticsSeriesKind.ACTIVITY -> database.statisticsDao().activityFieldMetadata(seriesId.value)
                StatisticsSeriesKind.SEQUENCE -> database.statisticsDao().sequenceFieldMetadata(seriesId.value)
                StatisticsSeriesKind.ONE_OFF_BUCKET -> emptyList()
            }
        return rows
            .groupBy(StatisticsFieldMetadataRow::sourceFieldId)
            .map { (sourceId, candidates) ->
                val types = candidates.map(StatisticsFieldMetadataRow::fieldType).distinct()
                val units = candidates.map(StatisticsFieldMetadataRow::unit).distinct()
                require(types.size == 1 && units.size == 1) {
                    "Statistics Field $sourceId has incompatible historical type or unit"
                }
                val current = candidates.filter { it.snapshotFieldId == null }.sortedBy { it.currentDisplayName }
                val displayName =
                    candidates.mapNotNull(StatisticsFieldMetadataRow::currentDisplayName).minOrNull()
                        ?: candidates
                            .sortedBy { it.snapshotFieldId }
                            .firstNotNullOfOrNull(StatisticsFieldMetadataRow::nameAtCreation)
                        ?: error("Statistics Field $sourceId has no display metadata")
                val metadata = current.firstOrNull() ?: candidates.sortedBy { it.snapshotFieldId }.first()
                StatisticsFieldDescriptor(
                    when (series.kind) {
                        StatisticsSeriesKind.ACTIVITY -> StatisticsFieldId.Activity(ActivityTemplateFieldId(sourceId))
                        StatisticsSeriesKind.SEQUENCE -> StatisticsFieldId.Sequence(SequenceTemplateFieldId(sourceId))
                        StatisticsSeriesKind.ONE_OFF_BUCKET ->
                            error(
                                "One-off fields are not reusable Statistics fields",
                            )
                    },
                    parseFieldType(types.single()),
                    displayName,
                    units.single(),
                    metadata.displayPrecision,
                )
            }.sortedWith(fieldComparator)
    }

    private fun requireField(
        series: StatisticsSeriesSummary,
        seriesId: StatisticsSeriesId,
        fieldId: StatisticsFieldId,
        type: CustomFieldType,
    ): StatisticsFieldDescriptor {
        require(
            (series.kind == StatisticsSeriesKind.ACTIVITY && fieldId is StatisticsFieldId.Activity) ||
                (series.kind == StatisticsSeriesKind.SEQUENCE && fieldId is StatisticsFieldId.Sequence),
        ) { "Statistics Field kind does not match its Series" }
        return requireNotNull(fieldCatalogLocked(series, seriesId).singleOrNull { it.id == fieldId }) {
            "Unknown Statistics Field: ${fieldId.value}"
        }.also { require(it.type == type) { "Statistics Field type mismatch" } }
    }

    private fun executionCount(
        kind: StatisticsSeriesKind,
        seriesId: StatisticsSeriesId,
        start: String?,
        end: String?,
    ): Long =
        when (kind) {
            StatisticsSeriesKind.ACTIVITY ->
                database
                    .statisticsDao()
                    .activitySeriesAggregates(seriesId.value, start, end)
                    .singleOrNull()
                    ?.executionCount ?: 0
            StatisticsSeriesKind.SEQUENCE ->
                database
                    .statisticsDao()
                    .sequenceSeriesAggregates(seriesId.value, start, end)
                    .singleOrNull()
                    ?.executionCount ?: 0
            StatisticsSeriesKind.ONE_OFF_BUCKET -> error("One-off reusable Field Statistics are not supported")
        }

    private fun optionMetadata(
        kind: StatisticsSeriesKind,
        seriesId: StatisticsSeriesId,
        fieldId: StatisticsFieldId,
    ): Map<OptionKey, String> {
        val rows =
            when (kind) {
                StatisticsSeriesKind.ACTIVITY ->
                    database.statisticsDao().activityOptionMetadata(seriesId.value, fieldId.value)
                StatisticsSeriesKind.SEQUENCE ->
                    database.statisticsDao().sequenceOptionMetadata(seriesId.value, fieldId.value)
                StatisticsSeriesKind.ONE_OFF_BUCKET -> error("One-off reusable Field Statistics are not supported")
            }
        return optionMetadataRows(rows)
    }

    private fun optionMetadataRows(rows: List<StatisticsOptionMetadataRow>): Map<OptionKey, String> =
        rows.groupBy(::optionKey).mapValues { (key, candidates) ->
            candidates.mapNotNull(StatisticsOptionMetadataRow::currentLabel).minOrNull()
                ?: candidates.mapNotNull(StatisticsOptionMetadataRow::fallbackLabel).minOrNull()
                ?: key.fallbackId
                ?: key.sourceId.orEmpty()
        }

    private fun categoryCounts(
        kind: StatisticsSeriesKind,
        seriesId: StatisticsSeriesId,
        fieldId: StatisticsFieldId,
        start: String?,
        end: String?,
    ): List<StatisticsCategoryCountRow> =
        when (kind) {
            StatisticsSeriesKind.ACTIVITY ->
                database.statisticsDao().activityCategoryCounts(seriesId.value, fieldId.value, start, end)
            StatisticsSeriesKind.SEQUENCE ->
                database.statisticsDao().sequenceCategoryCounts(seriesId.value, fieldId.value, start, end)
            StatisticsSeriesKind.ONE_OFF_BUCKET -> error("One-off reusable Field Statistics are not supported")
        }

    private fun optionIdentity(
        kind: StatisticsSeriesKind,
        key: OptionKey,
    ): StatisticsCategoryOptionId =
        key.sourceId?.let { source ->
            when (kind) {
                StatisticsSeriesKind.ACTIVITY -> StatisticsCategoryOptionId.ActivitySource(CategoryOptionId(source))
                StatisticsSeriesKind.SEQUENCE ->
                    StatisticsCategoryOptionId.SequenceSource(SequenceTemplateCategoryOptionId(source))
                StatisticsSeriesKind.ONE_OFF_BUCKET -> error("One-off reusable Field Statistics are not supported")
            }
        } ?: StatisticsCategoryOptionId.SnapshotFallback(requireNotNull(key.fallbackId))

    private fun StatisticsPeriod.bounds(): Pair<String?, String?> =
        dateRangeOrNull().let { it?.startDate?.toString() to it?.endDateInclusive?.toString() }

    private fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable(block))

    companion object {
        private const val DIRECT_SCALAR_FIELD_LIMIT = 3
        private const val SMALL_FALLBACK_FIELD_LIMIT = 16
        private const val SMALL_FALLBACK_TRIAL_PASSES = 3

        fun create(context: Context): StatisticsRepository =
            StatisticsRepository(
                LifeTracingDatabase.builder(context.applicationContext, "lifetracing.db").build(),
                { StatisticsSeriesId(UUID.randomUUID().toString()) },
            )
    }
}

private data class OptionKey(
    val sourceId: String?,
    val fallbackId: String?,
) {
    init {
        require((sourceId != null) xor (fallbackId != null)) { "Category option identity must have one stable key" }
    }
}

private fun optionKey(row: StatisticsOptionMetadataRow): OptionKey =
    row.sourceOptionId?.let { OptionKey(it, null) }
        ?: OptionKey(null, requireNotNull(row.snapshotOptionId))

private fun optionKey(row: StatisticsCategoryCountRow): OptionKey =
    row.sourceOptionId?.let { OptionKey(it, null) }
        ?: OptionKey(null, requireNotNull(row.snapshotOptionId))

private fun average(
    numerator: Long,
    denominator: Long,
): ExactValue? = if (denominator == 0L) null else ExactValue.of(numerator, denominator)

private fun parseKind(value: String): StatisticsSeriesKind =
    runCatching { StatisticsSeriesKind.valueOf(value) }
        .getOrElse { throw IllegalArgumentException("Unknown Statistics Series kind: $value", it) }

private fun parseFieldType(value: String): CustomFieldType =
    runCatching { CustomFieldType.valueOf(value) }
        .getOrElse { throw IllegalArgumentException("Unknown Statistics Field type: $value", it) }

private fun sourceState(
    activeSources: Long,
    archivedSources: Long,
): StatisticsSeriesSourceState =
    when {
        activeSources > 0 -> StatisticsSeriesSourceState.ACTIVE_SOURCE
        archivedSources > 0 -> StatisticsSeriesSourceState.ARCHIVED_SOURCE
        else -> StatisticsSeriesSourceState.NO_CURRENT_SOURCE
    }

private val categoryValueComparator =
    Comparator<CategoryValueStatistics> { left, right ->
        String.CASE_INSENSITIVE_ORDER.compare(left.displayLabel, right.displayLabel).takeIf { it != 0 }
            ?: left.id.value.compareTo(right.id.value)
    }

private val fieldComparator =
    Comparator<StatisticsFieldDescriptor> { left, right ->
        String.CASE_INSENSITIVE_ORDER.compare(left.displayName, right.displayName).takeIf { it != 0 }
            ?: left.id.value.compareTo(right.id.value)
    }
