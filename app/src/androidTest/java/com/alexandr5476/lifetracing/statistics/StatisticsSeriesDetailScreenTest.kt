@file:Suppress("MaxLineLength")

package com.alexandr5476.lifetracing.statistics

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        compose.onNodeWithTag("statistics-detail-loading").assertIsDisplayed()
        compose.onNodeWithTag("statistics-detail-refresh").performClick()
        compose.runOnIdle { state.value = state.value.copy(load = StatisticsSeriesDetailLoadState.Failure) }
        compose.onNodeWithTag("statistics-detail-retry").performClick()
        compose.onNodeWithTag("statistics-detail-period-all_time").performClick()
        assertEquals(2, refreshes)
        assertEquals(StatisticsPeriod.AllTime, selected)
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_back)).performClick()
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
        compose.onNodeWithTag("statistics-detail-period-day").performClick()
        compose.onNodeWithTag("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Day(LocalDate.parse("2026-08-01")), selected.last())
        compose.onNodeWithTag("statistics-detail-period-week").performClick()
        compose.onNodeWithTag("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Week(LocalDate.parse("2026-07-27")), selected.last())
        compose.onNodeWithTag("statistics-detail-period-month").performClick()
        compose.onNodeWithTag("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Month(YearMonth.of(2026, 8)), selected.last())
        compose.onNodeWithTag("statistics-detail-period-year").performClick()
        compose.onNodeWithTag("statistics-period-apply").performClick()
        assertEquals(StatisticsPeriod.Year(java.time.Year.of(2026)), selected.last())
        compose.onNodeWithTag("statistics-detail-period-custom").performClick()
        compose.onNodeWithTag("statistics-detail-custom-start").performTextInput("2026-08-03")
        compose.onNodeWithTag("statistics-detail-custom-end").performTextInput("2026-08-10")
        compose.onNodeWithTag("statistics-detail-custom-apply").performClick()
        assertEquals(
            StatisticsPeriod.Custom(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-10")),
            selected.last(),
        )
        compose.onNodeWithTag("statistics-detail-period-all_time").performClick()
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
            .onNodeWithText(
                compose.activity.getString(
                    R.string.statistics_duration_total,
                    compose.activity.getString(R.string.statistics_no_duration_samples),
                ),
            ).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_source_archived)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_executions, "1")).assertIsDisplayed()
        compose
            .onNodeWithText(
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
        compose
            .onAllNodesWithText(
                compose.activity.getString(R.string.statistics_duration_total, "0:00:00"),
            ).fetchSemanticsNodes()
            .also {
                assertEquals(1, it.size)
            }
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_source_missing)).assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.statistics_first_performed, formatted(Instant.EPOCH)),
            ).assertIsDisplayed()
        compose
            .onNodeWithText(
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
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_executions, "0")).assertIsDisplayed()
        compose
            .onNodeWithText(
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
        compose.onNodeWithTag("statistics-field-activity-number-id").assertExists()
        compose.onNodeWithTag("statistics-field-activity-empty-number").assertExists()
        compose.onNodeWithTag("statistics-field-activity-category-id").assertExists()
        compose.onNodeWithTag("statistics-field-activity-text-id").assertExists()
        compose.onNodeWithTag("statistics-category-activity-category-id-activity-option-a").assertExists()
        compose.onNodeWithTag("statistics-category-activity-category-id-activity-option-b").assertExists()
        compose
            .onNodeWithTag("statistics-field-activity-text-id")
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(compose.activity.getString(R.string.statistics_field_text))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(compose.activity.getString(R.string.statistics_number_total, "0 kg"))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.statistics_number_average, "0 kg"),
            ).performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.statistics_number_median, "0 kg"),
            ).performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.statistics_number_minimum, "0 kg"),
            ).performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.statistics_number_maximum, "0 kg"),
            ).performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(compose.activity.getString(R.string.statistics_field_recorded, "1"))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(compose.activity.getString(R.string.statistics_field_missing, "1"))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(
                    R.string.statistics_field_coverage,
                    NumberFormat.getPercentInstance(Locale.getDefault()).format(0.5),
                ),
            ).performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(compose.activity.getString(R.string.statistics_field_recorded, "0"))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(compose.activity.getString(R.string.statistics_field_missing, "2"))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(
                    R.string.statistics_field_coverage,
                    NumberFormat.getPercentInstance(Locale.getDefault()).format(0.0),
                ),
            ).performScrollTo()
            .assertIsDisplayed()
        val unavailable = compose.activity.getString(R.string.statistics_unavailable)
        listOf(
            R.string.statistics_number_total,
            R.string.statistics_number_average,
            R.string.statistics_number_median,
            R.string.statistics_number_minimum,
            R.string.statistics_number_maximum,
        ).forEach { resource ->
            compose
                .onNodeWithText(compose.activity.getString(resource, unavailable))
                .performScrollTo()
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
            .onNodeWithTag("statistics-field-activity-text-id")
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(compose.activity.getString(R.string.statistics_field_text))
            .performScrollTo()
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
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_source_missing)).assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.statistics_total_pause_idle, "0:00:30"),
            ).assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.statistics_average_pause_idle, "0:00:15"),
            ).assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(R.string.statistics_duration_total, "0:01:30"),
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
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_source_archived)).assertIsDisplayed()

        compose.runOnIdle {
            detailState.value =
                state(
                    StatisticsSeriesDetail.Sequence(
                        sequence.statistics.copy(
                            executionCount = 0,
                            activeDurations = DurationDistribution(0, Duration.ZERO, null, null, null, null),
                            firstPerformedAt = null,
                            lastPerformedAt = null,
                        ),
                        emptyList(),
                    ),
                )
        }
        compose.onNodeWithText(compose.activity.getString(R.string.statistics_executions, "0")).assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(
                    R.string.statistics_duration_total,
                    compose.activity.getString(R.string.statistics_no_duration_samples),
                ),
            ).assertIsDisplayed()
        compose
            .onNodeWithText(
                compose.activity.getString(
                    R.string.statistics_first_performed,
                    compose.activity.getString(R.string.statistics_unavailable),
                ),
            ).assertIsDisplayed()
    }

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
