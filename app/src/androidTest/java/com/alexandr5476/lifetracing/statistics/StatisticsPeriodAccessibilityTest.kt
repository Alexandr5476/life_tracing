package com.alexandr5476.lifetracing.statistics

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.util.Locale

class StatisticsPeriodAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun overviewRejectsInvalidDraftsAndUsesTheAppliedPeriodAtIncreasedEnglishScaling() =
        verifyPeriods(detail = false, language = "en")

    @Test
    fun detailRejectsInvalidDraftsAndUsesTheAppliedPeriodAtIncreasedRussianScaling() =
        verifyPeriods(detail = true, language = "ru")

    private fun verifyPeriods(
        detail: Boolean,
        language: String,
    ) {
        val fixture = PeriodFixture(detail, language)
        try {
            fixture.setContent()
            keyboard.enter()
            fixture.verifyBoundedDrafts()
            fixture.verifyCustomAndAllTime()
        } finally {
            fixture.scope.cancel()
        }
    }

    private inner class PeriodFixture(
        val detail: Boolean,
        language: String,
    ) {
        private val first = StatisticsPeriod.Month(YearMonth.of(2024, 2))
        private val configuration =
            Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
                fontScale = 1.5f
            }
        private val context = compose.activity.createConfigurationContext(configuration)
        private val reads = mutableListOf<StatisticsPeriod>()
        private val prefix = if (detail) "statistics-detail" else "statistics"
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        fun setContent() {
            val overview = if (detail) null else StatisticsController(scope, first, ::read)
            val series =
                if (detail) {
                    StatisticsSeriesDetailController(
                        scope,
                        StatisticsSeriesId("series"),
                        first,
                    ) { _, period -> read(period) }
                } else {
                    null
                }
            compose.setContent {
                val systemDensity = Density(LocalDensity.current.density, configuration.fontScale)
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides configuration,
                    LocalDensity provides systemDensity,
                ) {
                    LifeTracingAppearance(AppearancePreferences(interfaceScalePercent = 120, textScalePercent = 130)) {
                        if (series != null) {
                            StatisticsSeriesDetailRoute(series) {}
                        } else {
                            StatisticsRoute(requireNotNull(overview)) {}
                        }
                    }
                }
            }
        }

        fun verifyBoundedDrafts() {
            val day = StatisticsPeriod.Day(LocalDate.of(2024, 2, 1))
            applyKind("day")
            assertApplied(day, R.string.statistics_period_day, "2024-02-01")
            val leap = StatisticsPeriod.Day(LocalDate.of(2024, 2, 29))
            rejectAndCorrect("2023-02-29", "2024-02-29", leap, R.string.statistics_invalid_date)
            assertApplied(leap, R.string.statistics_period_day, "2024-02-29")
            periodNode("$prefix-previous").performSemanticsAction(SemanticsActions.RequestFocus)
            press(Key.Enter)
            assertApplied(StatisticsPeriod.Day(LocalDate.of(2024, 2, 28)), R.string.statistics_period_day, "2024-02-28")
            compose.onNodeWithTag("$prefix-previous").assertIsFocused()
            periodNode("$prefix-next").performSemanticsAction(SemanticsActions.RequestFocus)
            press(Key.Enter)
            assertApplied(leap, R.string.statistics_period_day, "2024-02-29")

            applyKind("week")
            assertApplied(
                StatisticsPeriod.Week(LocalDate.of(2024, 2, 26)),
                R.string.statistics_period_week,
                "2024-02-26",
            )
            rejectAndCorrect(
                "2024-02",
                "2024-02-15",
                StatisticsPeriod.Week(LocalDate.of(2024, 2, 12)),
                R.string.statistics_invalid_week,
            )
            assertApplied(
                StatisticsPeriod.Week(LocalDate.of(2024, 2, 12)),
                R.string.statistics_period_week,
                "2024-02-12",
                "2024-02-18",
            )

            applyKind("month")
            rejectAndCorrect(
                "2024-13",
                "2024-04",
                StatisticsPeriod.Month(YearMonth.of(2024, 4)),
                R.string.statistics_invalid_month,
            )
            assertApplied(StatisticsPeriod.Month(YearMonth.of(2024, 4)), R.string.statistics_period_month, "2024-04-30")
            applyKind("year")
            rejectAndCorrect("202", "2028", StatisticsPeriod.Year(Year.of(2028)), R.string.statistics_invalid_year)
            assertApplied(StatisticsPeriod.Year(Year.of(2028)), R.string.statistics_period_year, "2028-12-31")
        }

        fun verifyCustomAndAllTime() {
            val applied = StatisticsPeriod.Year(Year.of(2028))
            val attempts = reads.size
            val input = periodNode("$prefix-period-input")
            input.performSemanticsAction(SemanticsActions.RequestFocus)
            val custom = periodNode("$prefix-period-custom")
            input.assertIsFocused()
            // Invoke the accessibility action while keyboard focus still belongs to the old field.
            custom.performSemanticsAction(SemanticsActions.OnClick)
            keyboard.awaitFocus(custom)
            periodNode("$prefix-editing-period")
                .assertTextContains(
                    context.getString(
                        R.string.statistics_period_editing,
                        context.getString(R.string.statistics_period_custom),
                    ),
                )
            assertApplied(applied, R.string.statistics_period_year, "2028-12-31")
            assertEquals(attempts, reads.size)
            val start = "$prefix-custom-start"
            val end = "$prefix-custom-end"
            periodNode(start).performTextReplacement("2028-03-02")
            periodNode(end).performTextReplacement("2028-03")
            enter(end)
            assertError(end, R.string.statistics_invalid_range, "2028-03")
            periodNode(end).performTextReplacement("2028-03-01")
            enter(end)
            assertError(end, R.string.statistics_invalid_range, "2028-03-01")
            assertEquals(attempts, reads.size)
            assertApplied(applied, R.string.statistics_period_year, "2028-12-31")

            scrollToOverview(StatisticsOverviewItem.HEADER)
            compose.onNodeWithTag("$prefix-refresh").performScrollTo().performClick()
            scrollToOverview(StatisticsOverviewItem.STATUS)
            compose.onNodeWithTag("$prefix-retry").performScrollTo().performClick()
            assertEquals(listOf(applied, applied), reads.drop(attempts))

            periodNode(end).performTextReplacement("2028-03-02")
            periodNode("$prefix-period-draft").assertIsDisplayed()
            enter(end)
            val customDate = LocalDate.of(2028, 3, 2)
            assertApplied(
                StatisticsPeriod.Custom(customDate, customDate),
                R.string.statistics_period_custom,
                "2028-03-02",
            )
            compose.onNodeWithTag("$prefix-previous").assertDoesNotExist()
            compose.onNodeWithTag("$prefix-next").assertDoesNotExist()

            applyKind("all_time")
            val allTime =
                context.getString(
                    R.string.statistics_period_unbounded,
                    context.getString(R.string.statistics_period_all_time),
                )
            periodNode(
                "$prefix-applied-period",
            ).assertTextContains(context.getString(R.string.statistics_period_applied, allTime))
            assertEquals(StatisticsPeriod.AllTime, reads.last())
            compose.onNodeWithTag("$prefix-previous").assertDoesNotExist()
            compose.onNodeWithTag("$prefix-next").assertDoesNotExist()
        }

        private fun read(period: StatisticsPeriod): Nothing {
            reads += period
            error("Offline")
        }

        private fun applyKind(kind: String) {
            periodNode("$prefix-period-$kind").performClick()
        }

        private fun rejectAndCorrect(
            invalid: String,
            valid: String,
            corrected: StatisticsPeriod,
            error: Int,
        ) {
            val before = reads.toList()
            val input = "$prefix-period-input"
            periodNode(input).performTextReplacement(invalid)
            enter(input)
            assertError(input, error, invalid).assertIsFocused()
            assertEquals(before, reads)
            periodNode(input).performTextReplacement(valid)
            periodNode("$prefix-period-draft").assertIsDisplayed()
            enter(input)
            assertEquals(before + corrected, reads)
        }

        private fun assertError(
            tag: String,
            error: Int,
            value: String,
        ) = periodNode(tag)
            .assertTextContains(value)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, context.getString(error)))

        private fun assertApplied(
            period: StatisticsPeriod,
            kind: Int,
            vararg dates: String,
        ) {
            val applied = periodNode("$prefix-applied-period")
            applied.assertTextContains(context.getString(kind), substring = true)
            dates.forEach { applied.assertTextContains(it, substring = true) }
            assertEquals(period, reads.last())
        }

        private fun enter(tag: String) {
            periodNode(tag).performSemanticsAction(SemanticsActions.RequestFocus)
            press(Key.Enter)
        }

        @OptIn(ExperimentalTestApi::class)
        private fun press(key: Key) {
            compose.onRoot().performKeyInput { pressKey(key) }
        }

        private fun periodNode(tag: String) =
            run {
                scrollToOverview(StatisticsOverviewItem.PERIOD)
                compose.onNodeWithTag(tag).performScrollTo()
            }

        private fun scrollToOverview(key: StatisticsOverviewItem) {
            if (detail) {
                compose.onNodeWithTag("statistics-detail-list").performScrollToKey(DetailAnchor.valueOf(key.name))
            } else {
                compose.onNodeWithTag("statistics-list").performScrollToKey(key)
            }
        }
    }
}
