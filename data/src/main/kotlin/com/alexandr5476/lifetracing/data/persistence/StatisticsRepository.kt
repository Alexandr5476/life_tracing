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
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.GlobalStatistics
import com.alexandr5476.lifetracing.domain.NumberFieldStatistics
import com.alexandr5476.lifetracing.domain.SequenceSeriesStatistics
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
import com.alexandr5476.lifetracing.domain.StatisticsFieldDescriptor
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeries
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesPeriodSummary
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSummary
import com.alexandr5476.lifetracing.domain.dateRangeOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable

class StatisticsRepository internal constructor(
    private val database: LifeTracingDatabase,
    private val nextStatisticsSeriesId: () -> StatisticsSeriesId,
) {
    fun global(period: StatisticsPeriod): GlobalStatistics =
        transaction {
            val range = period.dateRangeOrNull()
            val row =
                database.statisticsDao().global(
                    range?.startDate?.toString(),
                    range?.endDateInclusive?.toString(),
                    ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value,
                )
            GlobalStatistics(
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
        transaction {
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
            seriesCatalogLocked().map { series ->
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
            val row = database.statisticsDao().activitySeriesAggregates(seriesId.value, start, end).singleOrNull()
            val values = database.statisticsDao().activityDurationValues(seriesId.value, start, end)
            val distribution = StatisticsDistributionCalculator.durations(values)
            require(row == null || row.durationSampleCount == distribution.sampleCount) {
                "Activity duration aggregate disagrees with its scalar samples"
            }
            ActivitySeriesStatistics(
                series,
                row?.executionCount ?: 0,
                distribution,
                row?.activeDayCount ?: 0,
                average(row?.executionCount ?: 0, row?.activeDayCount ?: 0),
                row?.firstPerformedAtMs?.let(Instant::ofEpochMilli),
                row?.lastPerformedAtMs?.let(Instant::ofEpochMilli),
            )
        }

    fun sequenceSeries(
        seriesId: StatisticsSeriesId,
        period: StatisticsPeriod,
    ): SequenceSeriesStatistics =
        transaction {
            val series = requireSeries(seriesId, StatisticsSeriesKind.SEQUENCE)
            val (start, end) = period.bounds()
            val row = database.statisticsDao().sequenceSeriesAggregates(seriesId.value, start, end).singleOrNull()
            val values = database.statisticsDao().sequenceDurationValues(seriesId.value, start, end)
            val distribution = StatisticsDistributionCalculator.durations(values)
            require(row == null || row.durationSampleCount == distribution.sampleCount) {
                "Sequence duration aggregate disagrees with its scalar samples"
            }
            val count = row?.executionCount ?: 0
            val pause = row?.totalPauseMs ?: 0
            SequenceSeriesStatistics(
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
            val relevant = executionCount(series.kind, seriesId, start, end)
            val values =
                when (fieldId) {
                    is StatisticsFieldId.Activity ->
                        database.statisticsDao().activityNumberValues(seriesId.value, fieldId.value, start, end)
                    is StatisticsFieldId.Sequence ->
                        database.statisticsDao().sequenceNumberValues(seriesId.value, fieldId.value, start, end)
                }
            val distribution = StatisticsDistributionCalculator.numbers(values)
            require(distribution.sampleCount <= relevant) { "Recorded Number values exceed relevant executions" }
            NumberFieldStatistics(
                field,
                relevant,
                distribution.sampleCount,
                relevant - distribution.sampleCount,
                CountRatio(distribution.sampleCount, relevant),
                distribution,
            )
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
            val metadata = optionMetadata(series.kind, seriesId, fieldId)
            val counts = categoryCounts(series.kind, seriesId, fieldId, start, end).associateBy(::optionKey)
            val recorded = counts.values.sumOf(StatisticsCategoryCountRow::count)
            require(recorded <= relevant) { "Recorded Category values exceed relevant executions" }
            val values =
                (metadata.keys + counts.keys)
                    .distinct()
                    .map { key ->
                        val count = counts[key]?.count ?: 0
                        CategoryValueStatistics(
                            optionIdentity(series.kind, key),
                            metadata[key] ?: key.fallbackId ?: key.sourceId.orEmpty(),
                            count,
                            CountRatio(count, recorded),
                        )
                    }.sortedWith(categoryValueComparator)
            CategoryFieldStatistics(
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
        return rows.groupBy(::optionKey).mapValues { (key, candidates) ->
            candidates.mapNotNull(StatisticsOptionMetadataRow::currentLabel).minOrNull()
                ?: candidates.mapNotNull(StatisticsOptionMetadataRow::fallbackLabel).minOrNull()
                ?: key.fallbackId
                ?: key.sourceId.orEmpty()
        }
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
