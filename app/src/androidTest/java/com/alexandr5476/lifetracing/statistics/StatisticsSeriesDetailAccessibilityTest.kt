package com.alexandr5476.lifetracing.statistics

import android.content.Context
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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivitySeriesStatistics
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.CategoryFieldStatistics
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CategoryValueStatistics
import com.alexandr5476.lifetracing.domain.CountRatio
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.NumberFieldStatistics
import com.alexandr5476.lifetracing.domain.SequenceSeriesStatistics
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.StatisticsCategoryOptionId
import com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
import com.alexandr5476.lifetracing.domain.StatisticsFieldDescriptor
import com.alexandr5476.lifetracing.domain.StatisticsFieldDetail
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesKind
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSourceState
import com.alexandr5476.lifetracing.domain.StatisticsSeriesSummary
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.PrimaryWorkflowAppearance
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.assertTextFullyVisible
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.Serializable
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class StatisticsSeriesDetailAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun categoryTraversalIsLazyAndFocusSurvivesRefreshReorderAndRemoval() {
        val textFields = List(250) { index -> textField(false, "text-$index", "Repeated field") }
        val initial = largeCategory((0..299).toList())
        val targetIndex = 232
        val option = initial.statistics.values[targetIndex]
        val fieldKey = DetailFieldKey(initial.field.id)
        val offscreenField = textFields[249]
        val replacementPeriod = StatisticsPeriod.Day(LocalDate.of(2026, 8, 14))
        val fixture = Reads()
        try {
            fixture.show()
            keyboard.enter()
            keyboard.awaitFocus(node("statistics-detail-loading"))
            fixture.publish(activity(listOf(initial) + textFields))
            keyboard.awaitFocus(node("statistics-detail-content"))
            node("statistics-field-activity-text-249").assertDoesNotExist()
            node(optionTag(299)).assertDoesNotExist()
            scrollTo(DetailCategoryKey(fieldKey, initial.statistics.values[200].id))
            node(optionTag(200)).performSemanticsAction(SemanticsActions.RequestFocus)
            node(optionTag(targetIndex)).assertDoesNotExist()
            repeat(targetIndex - 200) { pressTab() }
            node(optionTag(targetIndex))
                .assertIsFocused()
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(DetailOptionIdentity, option.id))

            fixture.refresh()
            fixture.publish(activity(textFields.reversed() + largeCategory((0..299).reversed().toList())))
            keyboard.awaitFocus(node(optionTag(targetIndex)))
            fixture.refresh()
            fixture.publish(activity(textFields + largeCategory((0..299).filterNot { it == targetIndex })))
            keyboard.awaitFocus(node("statistics-field-activity-category"))

            compose.runOnIdle { fixture.controller.selectPeriod(replacementPeriod) }
            keyboard.awaitFocus(node("statistics-detail-loading"))
            fixture.publish(activity(textFields))
            keyboard.awaitFocus(compose.onNode(hasText(compose.activity.getString(R.string.statistics_field_title))))
            scrollTo(DetailFieldKey(offscreenField.field.id))
            node("statistics-field-activity-text-249")
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(DetailFieldIdentity, offscreenField.field.id))
            assertEquals(List(3) { StatisticsPeriod.AllTime } + replacementPeriod, fixture.periods)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retryContinuesThePreviouslyFocusedFieldWithoutDispatchingAnExtraRead() {
        val field = textField(false, "note", "Notes")
        val detail = activity(listOf(field))
        val fixture = Reads()
        try {
            fixture.show()
            keyboard.enter()
            fixture.publish(detail)
            val heading = "statistics-field-activity-note"
            scrollTo(DetailFieldKey(field.field.id))
            node(heading).performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
            fixture.refresh()
            compose.runOnIdle { fixture.gates.last().completeExceptionally(IllegalStateException("Unavailable")) }
            val retry = node("statistics-detail-retry")
            keyboard.awaitFocus(retry)
            retry.performSemanticsAction(SemanticsActions.OnClick)
            keyboard.awaitFocus(node("statistics-detail-loading"))
            fixture.publish(detail)
            keyboard.awaitFocus(node(heading))
            assertEquals(List(3) { StatisticsPeriod.AllTime }, fixture.periods)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun publicationWhilePausedRestoresTheFocusedMetricAfterResume() {
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        val fixture = Reads()
        val detail = activity(emptyList())
        try {
            fixture.show(owner)
            keyboard.enter()
            fixture.publish(detail)
            scrollTo(DetailSeriesMetric.ACTIVE_DAYS)
            val metric = hasText(compose.activity.getString(R.string.statistics_active_days, "2"))
            compose.onNode(metric).performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
            fixture.refresh()
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
            fixture.publish(detail)
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            keyboard.awaitFocus(compose.onNode(metric))
            assertEquals(List(2) { StatisticsPeriod.AllTime }, fixture.periods)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun englishActivityFactsAndFieldsReflowAtIncreasedScaling() = verifyScaledDetail(Locale.US, sequence = false)

    @Test
    fun russianSequenceFactsAndFieldsReflowAtIncreasedScaling() =
        verifyScaledDetail(Locale.forLanguageTag("ru"), sequence = true)

    @Test
    fun combinedEnglishMinimumActivityDetailKeepsSignedAndZeroValuesFullyVisible() =
        verifyCombinedDetail(PrimaryWorkflowAppearance.EnglishMinimum, sequence = false)

    @Test
    fun combinedRussianMaximumSequenceDetailKeepsLongMetricsAndOptionProvenanceFullyVisible() =
        verifyCombinedDetail(PrimaryWorkflowAppearance.RussianMaximum, sequence = true)

    private fun verifyCombinedDetail(
        mode: PrimaryWorkflowAppearance,
        sequence: Boolean,
    ) {
        val locale =
            mode
                .resourcesContext(compose.activity)
                .resources.configuration.locales[0]
        val scaled = ScaledDetail(locale, sequence, mode)
        scaled.show()
        scaled.assertSeries()
        scaled.assertNumber()
        scaled.assertCategoryAndText()
    }

    private fun verifyScaledDetail(
        locale: Locale,
        sequence: Boolean,
    ) {
        val scaled = ScaledDetail(locale, sequence)
        scaled.show()
        keyboard.enter()
        scaled.assertSeries()
        scaled.assertNumber()
        scaled.assertCategoryAndText()
    }

    private inner class ScaledDetail(
        private val locale: Locale,
        private val sequence: Boolean,
        private val appearance: PrimaryWorkflowAppearance? = null,
    ) {
        private val configuration =
            Configuration(compose.activity.resources.configuration).apply {
                setLocale(this@ScaledDetail.locale)
                fontScale = 1.5f
            }
        private val context: Context =
            appearance?.resourcesContext(compose.activity) ?: compose.activity.createConfigurationContext(configuration)
        private val name = "Distance and cadence repeated across a long planned training session"
        private val unit = "kilograms per recorded training set"
        private val optionLabel = "Archived option with a long historical label shared by two entries"
        private val number =
            descriptor(
                sequence,
                "number",
                CustomFieldType.NUMBER,
                name,
            ).copy(unit = unit, displayPrecision = 2)
        private val category = descriptor(sequence, "category", CustomFieldType.CATEGORY, name)
        private val text = textField(sequence, "text", name)
        private val unitless =
            descriptor(sequence, "unitless", CustomFieldType.NUMBER, "Change").copy(displayPrecision = 2)
        private val fallback = StatisticsCategoryOptionId.SnapshotFallback("retired")
        private val source =
            if (sequence) {
                StatisticsCategoryOptionId.SequenceSource(SequenceTemplateCategoryOptionId("retired"))
            } else {
                StatisticsCategoryOptionId.ActivitySource(CategoryOptionId("retired"))
            }
        private val sourceState =
            if (sequence) StatisticsSeriesSourceState.NO_CURRENT_SOURCE else StatisticsSeriesSourceState.ARCHIVED_SOURCE
        private val series =
            StatisticsSeriesSummary(
                id,
                if (sequence) StatisticsSeriesKind.SEQUENCE else StatisticsSeriesKind.ACTIVITY,
                name,
                null,
                sourceState,
            )

        fun show() {
            val limit = if (sequence) Long.MIN_VALUE else Long.MAX_VALUE
            val fields =
                listOf(
                    StatisticsFieldDetail.Number(
                        NumberFieldStatistics(
                            number,
                            4,
                            2,
                            2,
                            CountRatio(2, 4),
                            StatisticsDistributionCalculator.numbers(listOf(limit, limit)),
                        ),
                    ),
                    StatisticsFieldDetail.Category(
                        CategoryFieldStatistics(
                            category,
                            4,
                            3,
                            1,
                            CountRatio(3, 4),
                            listOf(
                                CategoryValueStatistics(source, optionLabel, 2, CountRatio(2, 3)),
                                CategoryValueStatistics(fallback, optionLabel, 1, CountRatio(1, 3)),
                            ),
                        ),
                    ),
                    StatisticsFieldDetail.Number(
                        NumberFieldStatistics(
                            unitless,
                            4,
                            4,
                            0,
                            CountRatio(4, 4),
                            StatisticsDistributionCalculator.numbers(listOf(-2_001L, -1_000L, 0L, 47L)),
                        ),
                    ),
                    text,
                )
            val detail = if (sequence) sequence(series, fields) else activity(fields, series)
            val state =
                StatisticsSeriesDetailPresentationState(
                    StatisticsPeriod.AllTime,
                    StatisticsSeriesDetailLoadState.Content(detail),
                )
            compose.setContent {
                if (appearance == null) {
                    CompositionLocalProvider(
                        LocalContext provides context,
                        LocalConfiguration provides configuration,
                        LocalDensity provides Density(LocalDensity.current.density, configuration.fontScale),
                    ) {
                        LifeTracingAppearance(
                            AppearancePreferences(interfaceScalePercent = 120, textScalePercent = 130),
                        ) {
                            StatisticsSeriesDetailScreen(state, {}, {}, {})
                        }
                    }
                } else {
                    appearance.Content { StatisticsSeriesDetailScreen(state, {}, {}, {}) }
                }
            }
        }

        fun assertSeries() {
            scrollTo(DetailAnchor.STATUS)
            val period =
                context.getString(
                    R.string.statistics_period_unbounded,
                    context.getString(R.string.statistics_period_all_time),
                )
            node("statistics-detail-content")
                .assertTextContains(context.getString(R.string.statistics_loaded))
                .assertContext(period)
            scrollTo(DetailAnchor.SERIES)
            node("statistics-detail-series-${id.value}")
                .assert(SemanticsMatcher.expectValue(DetailSeriesIdentity, id))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .assertTextContains(name)
                .assertTextContains(
                    context.getString(
                        R.string.statistics_detail_kind,
                        context.getString(
                            if (sequence) R.string.statistics_kind_sequence else R.string.statistics_kind_activity,
                        ),
                    ),
                ).assertTextContains(
                    context.getString(
                        if (sequence) R.string.statistics_source_missing else R.string.statistics_source_archived,
                    ),
                )
            assertMetric(R.string.statistics_executions, "4")
            assertMetric(R.string.statistics_active_days, "2")
            assertMetric(R.string.statistics_average_per_active_day_count, "2")
            val timestamp =
                DateTimeFormatter
                    .ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(locale)
                    .withZone(ZoneId.systemDefault())
            assertMetric(R.string.statistics_first_performed, timestamp.format(Instant.EPOCH))
            assertMetric(R.string.statistics_last_performed, timestamp.format(Instant.EPOCH.plusSeconds(86_400)))
            if (sequence) {
                assertMetric(R.string.statistics_active_duration_samples, "1")
                assertMetric(R.string.statistics_active_duration_total, "0:00:00")
                assertMetric(R.string.statistics_active_duration_average, "0:00:00")
                assertMetric(R.string.statistics_active_duration_median, "0:00:00")
                assertMetric(R.string.statistics_active_duration_minimum, "0:00:00")
                assertMetric(R.string.statistics_active_duration_maximum, "0:00:00")
                assertMetric(R.string.statistics_total_pause_idle, "0:00:00")
                assertMetric(R.string.statistics_average_pause_idle, "0:00:00")
            } else {
                assertMetric(R.string.statistics_duration_samples, "3")
                assertMetric(R.string.statistics_duration_total, "0:01:30")
                assertMetric(R.string.statistics_duration_average, "0:00:30")
                assertMetric(R.string.statistics_duration_median, "0:00:30")
                assertMetric(R.string.statistics_duration_minimum, "0:00:15")
                assertMetric(R.string.statistics_duration_maximum, "0:00:45")
            }
        }

        fun assertNumber() {
            val symbols = DecimalFormatSymbols.getInstance(locale)
            val total =
                if (sequence) {
                    "-18,446,744,073,709,551.616"
                        .replace(
                            ',',
                            symbols.groupingSeparator,
                        ).replace('.', symbols.decimalSeparator)
                } else {
                    "18,446,744,073,709,551.614"
                }
            val context = fieldContext(number, R.string.statistics_detail_number_field)
            assertMetric(R.string.statistics_number_total, "$total $unit").assertContext(context).assertMetricReflow()
            assertMetric(R.string.statistics_field_recorded, "2").assertContext(context)
            assertMetric(R.string.statistics_field_missing, "2").assertContext(context)
            assertMetric(R.string.statistics_field_coverage, ratio(2, 4, 0.5)).assertContext(context)
            assertMetric(R.string.statistics_detail_precision, "2").assertContext(context)
            val separator = symbols.decimalSeparator
            val unitlessContext =
                this.context.getString(
                    R.string.statistics_detail_field_context,
                    unitless.displayName,
                    this.context.getString(R.string.statistics_detail_number_field),
                )
            assertMetric(R.string.statistics_number_average, "-0${separator}74").assertContext(unitlessContext)
            assertMetric(R.string.statistics_number_median, "-0${separator}50").assertContext(unitlessContext)
            assertMetric(R.string.statistics_number_minimum, "-2${separator}001").assertContext(unitlessContext)
            assertMetric(R.string.statistics_number_maximum, "0${separator}047").assertContext(unitlessContext)
            if (appearance != null) assertMetricValueVisible(R.string.statistics_number_average, "-0${separator}74")
        }

        fun assertCategoryAndText() {
            val key = DetailFieldKey(category.id)
            val sourceLabel =
                if (sequence) {
                    R.string.statistics_category_sequence_source
                } else {
                    R.string.statistics_category_activity_source
                }
            assertOption(key, source, sourceLabel, 2)
            assertOption(key, fallback, R.string.statistics_category_snapshot_source, 1)
            scrollTo(DetailFieldKey(text.field.id))
            node("statistics-field-${DetailFieldKey(text.field.id).tag}")
                .assert(SemanticsMatcher.expectValue(DetailFieldIdentity, text.field.id))
                .assertTextContains(fieldContext(text.field, R.string.statistics_detail_text_field))
            textNode(context.getString(R.string.statistics_field_text))
                .assertContext(fieldContext(text.field, R.string.statistics_detail_text_field))
                .assertMetricReflow()
        }

        private fun assertOption(
            key: DetailFieldKey,
            option: StatisticsCategoryOptionId,
            provenance: Int,
            count: Int,
        ) {
            val item = DetailCategoryKey(key, option)
            scrollTo(item)
            val description =
                context.getString(
                    R.string.statistics_category_recorded_share,
                    context.getString(R.string.statistics_detail_duplicate_name, optionLabel, option.value),
                    count.toString(),
                    ratio(count, 3, count / 3.0),
                )
            node("statistics-category-${key.tag}-${item.optionTag}")
                .assert(SemanticsMatcher.expectValue(DetailFieldIdentity, category.id))
                .assert(SemanticsMatcher.expectValue(DetailOptionIdentity, option))
                .assertTextContains(description)
                .assertContext(
                    context.getString(
                        R.string.statistics_category_context,
                        context.getString(
                            R.string.statistics_detail_duplicate_name,
                            category.displayName,
                            category.id.value,
                        ),
                        context.getString(provenance),
                    ),
                ).assertMetricReflow()
        }

        private fun fieldContext(
            field: StatisticsFieldDescriptor,
            type: Int,
        ) = context.getString(
            R.string.statistics_detail_field_context,
            context.getString(R.string.statistics_detail_duplicate_name, field.displayName, field.id.value),
            context.getString(type),
        )

        private fun ratio(
            numerator: Int,
            denominator: Int,
            share: Double,
        ): String {
            val percent = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }.format(share)
            return context.getString(
                R.string.statistics_detail_ratio,
                numerator.toString(),
                denominator.toString(),
                percent,
            )
        }

        private fun assertMetric(
            label: Int,
            value: String,
        ): SemanticsNodeInteraction = textNode(context.getString(label, value)).assertIsDisplayed()

        private fun assertMetricValueVisible(
            label: Int,
            value: String,
        ) {
            assertMetric(label, value).assertTextFullyVisible()
        }

        private fun SemanticsNodeInteraction.assertMetricReflow() {
            if (appearance == null) assertReflows() else assertTextFullyVisible()
        }
    }

    private inner class Reads {
        private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val periods = mutableListOf<StatisticsPeriod>()
        val gates = mutableListOf<CompletableDeferred<StatisticsSeriesDetail>>()
        val controller =
            StatisticsSeriesDetailController(scope, id, StatisticsPeriod.AllTime) { readId, period ->
                assertEquals(id, readId)
                periods += period
                CompletableDeferred<StatisticsSeriesDetail>().also { gates += it }.await()
            }

        fun show(owner: LifecycleOwner? = null) =
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides (owner ?: LocalLifecycleOwner.current)) {
                    LifeTracingTheme { StatisticsSeriesDetailRoute(controller) {} }
                }
            }

        fun refresh() {
            compose.runOnIdle { controller.refresh() }
            keyboard.awaitFocus(node("statistics-detail-loading"))
        }

        fun publish(detail: StatisticsSeriesDetail) {
            compose.runOnIdle { assertTrue(gates.last().complete(detail)) }
        }

        fun close() {
            controller.close()
            scope.cancel()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun pressTab() = compose.onRoot().performKeyInput { pressKey(Key.Tab) }

    private fun scrollTo(key: Serializable) {
        node("statistics-detail-list").performScrollToKey(key)
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag)

    private fun textNode(text: String): SemanticsNodeInteraction {
        node("statistics-detail-list").performScrollToNode(hasText(text))
        return compose.onNode(hasText(text))
    }

    private fun SemanticsNodeInteraction.assertContext(context: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context))

    private fun SemanticsNodeInteraction.assertReflows() {
        val layouts = mutableListOf<TextLayoutResult>()
        assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse(layouts.any { it.hasVisualOverflow })
    }

    private val id = StatisticsSeriesId("exact-series-id")

    private fun activity(
        fields: List<StatisticsFieldDetail>,
        summary: StatisticsSeriesSummary =
            StatisticsSeriesSummary(
                id,
                StatisticsSeriesKind.ACTIVITY,
                "Repeated field",
                null,
                StatisticsSeriesSourceState.ACTIVE_SOURCE,
            ),
    ): StatisticsSeriesDetail.Activity {
        val category = fields.filterIsInstance<StatisticsFieldDetail.Category>().singleOrNull()
        val executions = category?.statistics?.relevantExecutionCount ?: 4L
        return StatisticsSeriesDetail.Activity(
            ActivitySeriesStatistics(
                summary,
                executions,
                StatisticsDistributionCalculator.durations(listOf(15_000L, 30_000L, 45_000L)),
                2,
                ExactValue.of(executions, 2),
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(86_400),
            ),
            fields,
        )
    }

    private fun sequence(
        summary: StatisticsSeriesSummary,
        fields: List<StatisticsFieldDetail>,
    ) = StatisticsSeriesDetail.Sequence(
        SequenceSeriesStatistics(
            summary,
            4,
            StatisticsDistributionCalculator.durations(listOf(0L)),
            Duration.ZERO,
            ExactValue.of(0, 1),
            2,
            ExactValue.of(2, 1),
            Instant.EPOCH,
            Instant.EPOCH.plusSeconds(86_400),
        ),
        fields,
    )

    private fun largeCategory(options: List<Int>): StatisticsFieldDetail.Category {
        val recorded = options.size.toLong()
        return StatisticsFieldDetail.Category(
            CategoryFieldStatistics(
                descriptor(false, "category", CustomFieldType.CATEGORY, "Repeated field"),
                recorded + 1,
                recorded,
                1,
                CountRatio(recorded, recorded + 1),
                options.map {
                    CategoryValueStatistics(
                        StatisticsCategoryOptionId.ActivitySource(CategoryOptionId("option-$it")),
                        "Repeated option",
                        1,
                        CountRatio(1, recorded),
                    )
                },
            ),
        )
    }

    private fun optionTag(index: Int) = "statistics-category-activity-category-activity-option-$index"

    private fun textField(
        sequence: Boolean,
        key: String,
        name: String,
    ) = StatisticsFieldDetail.Text(descriptor(sequence, key, CustomFieldType.TEXT, name))

    private fun descriptor(
        sequence: Boolean,
        key: String,
        type: CustomFieldType,
        name: String,
    ) = StatisticsFieldDescriptor(
        if (sequence) {
            StatisticsFieldId.Sequence(
                SequenceTemplateFieldId(key),
            )
        } else {
            StatisticsFieldId.Activity(ActivityTemplateFieldId(key))
        },
        type,
        name,
        null,
        null,
    )
}
