package com.alexandr5476.lifetracing.history

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ManualActivityEntryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ambiguityChoicesShowBothOccurrencesAndConcreteOffsets() {
        val ambiguity =
            ManualTimeAmbiguity(
                LocalDateTime.parse("2026-10-25T02:30"),
                ZoneId.of("Europe/Berlin"),
                listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)),
            )
        show(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.STOPWATCH)),
                startedText = "2026-10-25 02:30",
                completedText = "2026-10-25 03:30",
                startedAmbiguity = ambiguity,
                startedIssue = ManualEntryIssue.AMBIGUOUS_LOCAL_TIME,
                command = ManualEntryCommand.Invalid(ManualEntryIssue.AMBIGUOUS_LOCAL_TIME),
            ),
        )

        compose.onNodeWithText("First occurrence (UTC+02:00)").assertIsDisplayed()
        compose.onNodeWithText("Second occurrence (UTC+01:00)").assertIsDisplayed()
        compose.onNodeWithTag("manual-history-started-offset-0").assertIsDisplayed()
        compose.onNodeWithTag("manual-history-started-offset-1").assertIsDisplayed()
    }

    @Test
    fun noLiveHasNoStartInput() {
        show(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.NO_LIVE_TRACKING)),
                completedText = "2026-09-16 10:00",
            ),
        )
        compose.onAllNodesWithText("Started (YYYY-MM-DD HH:MM)").assertCountEquals(0)
        compose.onNodeWithText("Completed (YYYY-MM-DD HH:MM)").assertIsDisplayed()
    }

    @Test
    fun timerTargetIsHumanReadable() {
        show(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.TIMER)),
                startedText = "2026-09-16 09:00",
                completedText = "2026-09-16 10:00",
            ),
        )
        compose.onNodeWithText("Configured timer target: 01:30").assertIsDisplayed()
    }

    @Test
    fun expectedErrorsAndMissingPresentationHaveDistinctEnglishAndRussianResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val english = context.localized(Locale.ENGLISH)
        val russian = context.localized(Locale.forLanguageTag("ru"))
        val ids =
            listOf(
                R.string.manual_history_invalid_datetime,
                R.string.manual_history_nonexistent_time,
                R.string.manual_history_ambiguous_time,
                R.string.manual_history_future_completion,
                R.string.manual_history_reversed_interval,
                R.string.manual_history_invalid_number,
                R.string.manual_history_invalid_category,
                R.string.manual_history_template_unavailable,
                R.string.manual_history_template_stale,
                R.string.manual_history_catalog_failure,
                R.string.manual_history_template_failure,
                R.string.manual_history_save_failure,
                R.string.manual_history_missing_value,
            )
        ids.forEach { id -> assertNotEquals(english.getString(id), russian.getString(id)) }
    }

    private fun show(state: ManualActivityEntryState) {
        compose.setContent {
            LifeTracingTheme {
                ManualActivityEntryScreen(state, {}, {})
            }
        }
    }

    private fun template(mode: TimeTrackingMode): ActivityTemplate =
        ActivityTemplate(
            ActivityTemplateId("template"),
            "Template",
            null,
            mode,
            if (mode == TimeTrackingMode.TIMER) Duration.ofSeconds(90) else null,
            StatisticsSeriesId("series"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun Context.localized(locale: Locale): Context =
        createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) })
}
