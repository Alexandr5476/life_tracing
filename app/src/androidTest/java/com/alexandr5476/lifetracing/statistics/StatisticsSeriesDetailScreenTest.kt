@file:Suppress("MaxLineLength")

package com.alexandr5476.lifetracing.statistics

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivitySeriesStatistics
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.CategoryFieldStatistics
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CategoryValueStatistics
import com.alexandr5476.lifetracing.domain.CountRatio
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DurationDistribution
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.NumberDistribution
import com.alexandr5476.lifetracing.domain.NumberFieldStatistics
import com.alexandr5476.lifetracing.domain.SequenceSeriesStatistics
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsFieldDescriptor
import com.alexandr5476.lifetracing.domain.StatisticsFieldDetail
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSummary
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.math.BigInteger
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class StatisticsSeriesDetailScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loading_failure_retry_and_refresh_useTheExpectedControllerCallbacks() {
        var refreshes = 0
        var back = 0
        var selected: StatisticsPeriod? = null
        val state =
            mutableStateOf(StatisticsSeriesDetailPresentationState(StatisticsPeriod.Month(YearMonth.of(2026, 8))))
        compose.setContent {
            LifeTracingTheme {
                StatisticsSeriesDetailScreen(
                    state.value,
                    { selected = it },
                    { refreshes++ },
                    { back++ },
                )
            }
        }
        compose.scrollToTag("statistics-detail-loading").assertIsDisplayed()
        compose.scrollToTag("statistics-detail-refresh").performClick()
        compose.runOnIdle { state.value = state.value.copy(load = StatisticsSeriesDetailLoadState.Failure) }
        compose.scrollToTag("statistics-detail-retry").performClick()
        compose.scrollToTag("statistics-detail-period-all_time").performClick()
        assertEquals(2, refreshes)
        assertEquals(StatisticsPeriod.AllTime, selected)
        compose.scrollToText(compose.activity.getString(R.string.statistics_back)).performClick()
        assertEquals(1, back)
    }

    @Test
    fun detailPeriodSelectionSupportsMondayWeeksInclusiveCustomAndAllTime() {
        val selected = mutableListOf<StatisticsPeriod>()
        compose.setContent {
            LifeTracingTheme {
                StatisticsSeriesDetailScreen(
                    StatisticsSeriesDetailPresentationState(StatisticsPeriod.Month(YearMonth.of(2026, 8))),
                    { selected += it },
                    {},
                    {},
                )
            }
        }
        compose.scrollToTag("statistics-detail-period-day").performClick()
        compose.scrollToTag("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Day(LocalDate.parse("2026-08-01")), selected.last())
        compose.scrollToTag("statistics-detail-period-week").performClick()
        compose.scrollToTag("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Week(LocalDate.parse("2026-07-27")), selected.last())
        compose.scrollToTag("statistics-detail-period-month").performClick()
        compose.scrollToTag("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Month(YearMonth.of(2026, 8)), selected.last())
        compose.scrollToTag("statistics-detail-period-year").performClick()
        compose.scrollToTag("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Year(java.time.Year.of(2026)), selected.last())
        compose.scrollToTag("statistics-detail-period-custom").performClick()
        compose.scrollToTag("statistics-detail-custom-start").performTextInput("2026-08-03")
        compose.scrollToTag("statistics-detail-custom-end").performTextInput("2026-08-10")
        compose.scrollToTag("statistics-detail-custom-apply").performClick()
        assertEquals(
            StatisticsPeriod.Custom(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-10")),
            selected.last(),
        )
        compose.scrollToTag("statistics-detail-period-all_time").performClick()
        assertEquals(StatisticsPeriod.AllTime, selected.last())
    }

    @Test
    fun noDurationSamplesAndSampledZeroRemainDistinctAndLifecycleMetadataIsVisible() {
        val detailState =
            mutableStateOf(
                state(
                    activity(
                        DurationDistribution(0, Duration.ZERO, null, null, null, null),
                        StatisticsSeriesSourceState.ARCHIVED_SOURCE,
                        executionCount = 1,
                    ),
                ),
            )
        compose.setContent {
            LifeTracingTheme {
                StatisticsSeriesDetailScreen(
                    detailState.value,
                    {},
                    {},
                    {},
                )
            }
        }
        compose
            .scrollToText(
                compose.activity.getString(
                    R.string.statistics_duration_total,
                    compose.activity.getString(R.string.statistics_no_duration_samples),
                ),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_source_archived),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_executions, "1"),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_first_performed, formatted(Instant.EPOCH)),
            ).assertIsDisplayed()

        compose.runOnIdle {
            detailState.value =
                state(
                    activity(
                        DurationDistribution(
                            1,
                            Duration.ZERO,
                            ExactValue.of(0, 1),
                            ExactValue.of(0, 1),
                            Duration.ZERO,
                            Duration.ZERO,
                        ),
                        StatisticsSeriesSourceState.NO_CURRENT_SOURCE,
                    ),
                )
        }
        val sampledZero = compose.activity.getString(R.string.statistics_duration_total, "0:00:00")
        compose.scrollToText(sampledZero).assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText(sampledZero).fetchSemanticsNodes().size)
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_source_missing),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_first_performed, formatted(Instant.EPOCH)),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_last_performed, formatted(Instant.EPOCH)),
            ).assertIsDisplayed()

        compose.runOnIdle {
            detailState.value =
                state(
                    activity(
                        DurationDistribution(0, Duration.ZERO, null, null, null, null),
                        StatisticsSeriesSourceState.NO_CURRENT_SOURCE,
                        executionCount = 0,
                    ),
                )
        }
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_executions, "0"),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(
                    R.string.statistics_first_performed,
                    compose.activity.getString(R.string.statistics_unavailable),
                ),
            ).assertIsDisplayed()
    }

    @Test
    fun numberCategoryAndTextFieldsUseStableIdentityAndCanonicalSamples() {
        val number =
            StatisticsFieldDescriptor(
                StatisticsFieldId.Activity(ActivityTemplateFieldId("number-id")),
                CustomFieldType.NUMBER,
                "Weight",
                "kg",
                2,
            )
        val category =
            StatisticsFieldDescriptor(
                StatisticsFieldId.Activity(ActivityTemplateFieldId("category-id")),
                CustomFieldType.CATEGORY,
                "Mood",
                null,
                null,
            )
        val text =
            StatisticsFieldDescriptor(
                StatisticsFieldId.Activity(ActivityTemplateFieldId("text-id")),
                CustomFieldType.TEXT,
                "Note",
                null,
                null,
            )
        val fields =
            listOf(
                StatisticsFieldDetail.Number(
                    NumberFieldStatistics(
                        number,
                        2,
                        1,
                        1,
                        CountRatio(1, 2),
                        NumberDistribution(1, BigInteger.ZERO, ExactValue.of(0, 1), ExactValue.of(0, 1), 0, 0),
                    ),
                ),
                StatisticsFieldDetail.Number(
                    NumberFieldStatistics(
                        number.copy(
                            id = StatisticsFieldId.Activity(ActivityTemplateFieldId("empty-number")),
                            displayName = "Empty number",
                        ),
                        2,
                        0,
                        2,
                        CountRatio(0, 2),
                        NumberDistribution(0, BigInteger.ZERO, null, null, null, null),
                    ),
                ),
                StatisticsFieldDetail.Category(
                    CategoryFieldStatistics(
                        category,
                        2,
                        2,
                        0,
                        CountRatio(2, 2),
                        listOf(
                            CategoryValueStatistics(
                                StatisticsCategoryOptionId.ActivitySource(CategoryOptionId("option-a")),
                                "Same label",
                                1,
                                CountRatio(1, 2),
                            ),
                            CategoryValueStatistics(
                                StatisticsCategoryOptionId.ActivitySource(CategoryOptionId("option-b")),
                                "Same label",
                                1,
                                CountRatio(1, 2),
                            ),
                        ),
                    ),
                ),
                StatisticsFieldDetail.Text(text),
            )
        val detailState =
            mutableStateOf(
                state(
                    activity(
                        DurationDistribution(0, Duration.ZERO, null, null, null, null),
                        StatisticsSeriesSourceState.ACTIVE_SOURCE,
                        fields,
                        executionCount = 2,
                    ),
                ),
            )
        compose.setContent {
            LifeTracingTheme {
                StatisticsSeriesDetailScreen(detailState.value, {}, {}, {})
            }
        }
        compose.scrollToTag("statistics-field-activity-number-id").assertExists()
        compose.scrollToTag("statistics-field-activity-empty-number").assertExists()
        compose.scrollToTag("statistics-field-activity-category-id").assertExists()
        compose.scrollToTag("statistics-field-activity-text-id").assertExists()
        compose.scrollToTag("statistics-category-activity-category-id-activity-option-a").assertExists()
        compose.scrollToTag("statistics-category-activity-category-id-activity-option-b").assertExists()
        compose
            .scrollToTag("statistics-field-activity-text-id")
            .assertIsDisplayed()
        compose
            .scrollToText(compose.activity.getString(R.string.statistics_field_text))
            .assertIsDisplayed()
        compose
            .scrollToText(compose.activity.getString(R.string.statistics_number_total, "0 kg"))
            .assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_number_average, "0.00 kg"),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_number_median, "0.00 kg"),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_number_minimum, "0 kg"),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_number_maximum, "0 kg"),
            ).assertIsDisplayed()
        compose
            .scrollToText(compose.activity.getString(R.string.statistics_field_recorded, "1"))
            .assertIsDisplayed()
        compose
            .scrollToText(compose.activity.getString(R.string.statistics_field_missing, "1"))
            .assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(
                    R.string.statistics_field_coverage,
                    ratio("1", "2", 0.5),
                ),
            ).assertIsDisplayed()
        compose
            .scrollToText(compose.activity.getString(R.string.statistics_field_recorded, "0"))
            .assertIsDisplayed()
        compose
            .scrollToText(compose.activity.getString(R.string.statistics_field_missing, "2"))
            .assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(
                    R.string.statistics_field_coverage,
                    ratio("0", "2", 0.0),
                ),
            ).assertIsDisplayed()
        val unavailable = compose.activity.getString(R.string.statistics_unavailable)
        listOf(
            R.string.statistics_number_total,
            R.string.statistics_number_average,
            R.string.statistics_number_median,
            R.string.statistics_number_minimum,
            R.string.statistics_number_maximum,
        ).forEach { resource ->
            compose
                .scrollToText(compose.activity.getString(resource, unavailable))
                .assertIsDisplayed()
        }

        compose.runOnIdle {
            detailState.value =
                state(
                    activity(
                        DurationDistribution(0, Duration.ZERO, null, null, null, null),
                        StatisticsSeriesSourceState.ACTIVE_SOURCE,
                        listOf(StatisticsFieldDetail.Text(text)),
                    ),
                )
        }
        compose
            .scrollToTag("statistics-field-activity-text-id")
            .assertIsDisplayed()
        compose
            .scrollToText(compose.activity.getString(R.string.statistics_field_text))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_field_recorded, "1")).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_number_total, "0")).assertDoesNotExist()
    }

    @Test
    fun sequenceMetricsAndNoCurrentSourceAreRenderedIncludingZeroExecutionContent() {
        val sequence =
            StatisticsSeriesDetail.Sequence(
                SequenceSeriesStatistics(
                    summary(StatisticsSeriesSourceState.NO_CURRENT_SOURCE).copy(kind = StatisticsSeriesKind.SEQUENCE),
                    2,
                    DurationDistribution(
                        1,
                        Duration.ofSeconds(90),
                        ExactValue.of(90_000, 1),
                        ExactValue.of(90_000, 1),
                        Duration.ofSeconds(90),
                        Duration.ofSeconds(90),
                    ),
                    Duration.ofSeconds(30),
                    ExactValue.of(15_000, 1),
                    1,
                    ExactValue.of(2, 1),
                    null,
                    null,
                ),
                emptyList(),
            )
        val detailState = mutableStateOf(state(sequence))
        compose.setContent {
            LifeTracingTheme { StatisticsSeriesDetailScreen(detailState.value, {}, {}, {}) }
        }
        compose
            .scrollToText(compose.activity.getString(R.string.statistics_source_missing))
            .assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_total_pause_idle, "0:00:30"),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_average_pause_idle, "0:00:15"),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_active_duration_total, "0:01:30"),
            ).assertIsDisplayed()

        compose.runOnIdle {
            detailState.value =
                state(
                    StatisticsSeriesDetail.Sequence(
                        sequence.statistics.copy(
                            series =
                                sequence.statistics.series.copy(
                                    sourceState = StatisticsSeriesSourceState.ARCHIVED_SOURCE,
                                ),
                        ),
                        emptyList(),
                    ),
                )
        }
        compose
            .scrollToText(compose.activity.getString(R.string.statistics_source_archived))
            .assertIsDisplayed()

        compose.runOnIdle {
            detailState.value =
                state(
                    StatisticsSeriesDetail.Sequence(
                        sequence.statistics.copy(
                            executionCount = 0,
                            activeDurations = DurationDistribution(0, Duration.ZERO, null, null, null, null),
                            totalPauseIdleDuration = Duration.ZERO,
                            averagePauseIdleMilliseconds = null,
                            activeDayCount = 0,
                            averageExecutionsPerActiveDay = null,
                            firstPerformedAt = null,
                            lastPerformedAt = null,
                        ),
                        emptyList(),
                    ),
                )
        }
        compose
            .scrollToText(compose.activity.getString(R.string.statistics_executions, "0"))
            .assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(
                    R.string.statistics_active_duration_total,
                    compose.activity.getString(R.string.statistics_no_duration_samples),
                ),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(
                    R.string.statistics_first_performed,
                    compose.activity.getString(R.string.statistics_unavailable),
                ),
            ).assertIsDisplayed()
    }

    @Test
    fun zeroPeriodCoveragePreservesHistoricalOptionsAndUnavailableNumberTotals() {
        val field =
            StatisticsFieldDescriptor(
                StatisticsFieldId.Activity(ActivityTemplateFieldId("category")),
                CustomFieldType.CATEGORY,
                "Historical choices",
                null,
                null,
            )
        val value =
            CategoryValueStatistics(
                StatisticsCategoryOptionId.ActivitySource(CategoryOptionId("historical")),
                "Historical option",
                0,
                CountRatio(0, 0),
            )
        val statistics = CategoryFieldStatistics(field, 0, 0, 0, CountRatio(0, 0), listOf(value))
        val number =
            field.copy(
                id = StatisticsFieldId.Activity(ActivityTemplateFieldId("number")),
                type = CustomFieldType.NUMBER,
                displayName = "Historical measurement",
            )
        val numberStatistics =
            NumberFieldStatistics(
                number,
                0,
                0,
                0,
                CountRatio(0, 0),
                NumberDistribution(0, BigInteger.ZERO, null, null, null, null),
            )
        val detail =
            activity(
                DurationDistribution(0, Duration.ZERO, null, null, null, null),
                StatisticsSeriesSourceState.NO_CURRENT_SOURCE,
                listOf(StatisticsFieldDetail.Category(statistics), StatisticsFieldDetail.Number(numberStatistics)),
            )
        compose.setContent {
            LifeTracingTheme { StatisticsSeriesDetailScreen(state(detail), {}, {}, {}) }
        }
        val unavailable = compose.activity.getString(R.string.statistics_unavailable)
        val ratio = compose.activity.getString(R.string.statistics_detail_ratio, "0", "0", unavailable)
        val coverage = compose.activity.getString(R.string.statistics_field_coverage, ratio)
        val categoryContext = fieldContext(field.displayName, R.string.statistics_detail_category_field)
        val numberContext = fieldContext(number.displayName, R.string.statistics_detail_number_field)
        listOf(categoryContext, numberContext).forEach { context ->
            val metric =
                hasText(coverage) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context)
            compose.onNodeWithTag("statistics-detail-list").performScrollToNode(metric)
            compose.onNode(metric).assertIsDisplayed()
        }
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_number_total, unavailable),
            ).assertIsDisplayed()
        compose
            .scrollToText(
                compose.activity.getString(R.string.statistics_category_recorded_share, value.displayLabel, "0", ratio),
            ).assertIsDisplayed()
    }

    private fun fieldContext(
        name: String,
        type: Int,
    ) = compose.activity.getString(R.string.statistics_detail_field_context, name, compose.activity.getString(type))

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.scrollToTag(tag: String) =
        onNodeWithTag("statistics-detail-list").performScrollToNode(hasTestTag(tag)).let { onNodeWithTag(tag) }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.scrollToText(text: String) =
        onNodeWithTag("statistics-detail-list").performScrollToNode(hasText(text)).let { onNodeWithText(text) }

    private fun ratio(
        numerator: String,
        denominator: String,
        share: Double,
    ) = compose.activity.getString(
        R.string.statistics_detail_ratio,
        numerator,
        denominator,
        NumberFormat.getPercentInstance(Locale.getDefault()).format(share),
    )

    private fun state(detail: StatisticsSeriesDetail.Activity) =
        StatisticsSeriesDetailPresentationState(
            StatisticsPeriod.AllTime,
            StatisticsSeriesDetailLoadState.Content(detail),
        )

    private fun activity(
        durations: DurationDistribution,
        source: StatisticsSeriesSourceState,
        fields: List<StatisticsFieldDetail> = emptyList(),
        executionCount: Long = if (durations.sampleCount == 0L) 0 else 1,
    ) = StatisticsSeriesDetail.Activity(
        ActivitySeriesStatistics(
            summary(source),
            executionCount,
            durations,
            if (executionCount ==
                0L
            ) {
                0
            } else {
                1
            },
            null,
            if (executionCount ==
                0L
            ) {
                null
            } else {
                Instant.EPOCH
            },
            if (executionCount == 0L) null else Instant.EPOCH,
        ),
        fields,
    )

    private fun state(detail: StatisticsSeriesDetail.Sequence) =
        StatisticsSeriesDetailPresentationState(
            StatisticsPeriod.AllTime,
            StatisticsSeriesDetailLoadState.Content(detail),
        )

    private fun summary(source: StatisticsSeriesSourceState) =
        StatisticsSeriesSummary(StatisticsSeriesId("series"), StatisticsSeriesKind.ACTIVITY, "Activity", null, source)

    private fun formatted(value: Instant) =
        DateTimeFormatter
            .ofLocalizedDateTime(
                FormatStyle.MEDIUM,
            ).withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(value)
}
