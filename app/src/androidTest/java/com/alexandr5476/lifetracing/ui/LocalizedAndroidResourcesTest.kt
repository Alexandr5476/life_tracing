package com.alexandr5476.lifetracing.ui

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizedAndroidResourcesTest {
    @Test
    fun dailyActionsAndSettingsRetryUseTheExplicitLocale() {
        val english = resources("en-US")
        val russian = resources("ru-RU")

        assertEquals("Complete step", english.getString(R.string.daily_complete_step))
        assertEquals("Завершить шаг", russian.getString(R.string.daily_complete_step))
        assertEquals("Current step 2: Tempo 42", english.getString(R.string.daily_current_occurrence, 2, "Tempo 42"))
        assertEquals("Текущий шаг 2: Tempo 42", russian.getString(R.string.daily_current_occurrence, 2, "Tempo 42"))
        assertEquals("Retry change", english.getString(R.string.settings_retry))
        assertEquals("Повторить изменение", russian.getString(R.string.settings_retry))
        assertSettingsRetry(english, "Could not change Interface size: 120%.", "Retry Interface size: 120%")
        assertSettingsRetry(
            russian,
            "Не удалось изменить настройку «Размер интерфейса: 120%».",
            "Повторить изменение настройки «Размер интерфейса: 120%»",
        )
    }

    @Test
    fun archivedTemplateCountsSelectEnglishAndRussianGrammaticalForms() {
        val counts = listOf(1, 2, 5, 21)
        assertEquals(
            listOf("1 archived template", "2 archived templates", "5 archived templates", "21 archived templates"),
            archivedCounts(resources("en-US"), counts),
        )
        assertEquals(
            listOf("В архиве 1 шаблон", "В архиве 2 шаблона", "В архиве 5 шаблонов", "В архиве 21 шаблон"),
            archivedCounts(resources("ru-RU"), counts),
        )
    }

    @Test
    fun historicalTimestampContextKeepsTheOriginalZoneAndBothOverlapOffsets() {
        assertEquals(
            listOf("1:30 AM (UTC-04:00, America/New_York)", "1:30 AM (UTC-05:00, America/New_York)"),
            overlapTimestamps(resources("en-US")),
        )
        assertEquals(
            listOf("01:30 (UTC-04:00, America/New_York)", "01:30 (UTC-05:00, America/New_York)"),
            overlapTimestamps(resources("ru-RU")),
        )
    }

    private fun assertSettingsRetry(
        resources: Resources,
        failure: String,
        retry: String,
    ) {
        val scale = resources.getString(R.string.settings_percent, 120)
        assertEquals("120%", scale)
        val choice =
            resources.getString(
                R.string.settings_option_description,
                resources.getString(R.string.settings_interface_scale),
                scale,
            )
        assertEquals(failure, resources.getString(R.string.settings_write_failure, choice))
        assertEquals(retry, resources.getString(R.string.settings_retry_description, choice))
    }

    private fun archivedCounts(
        resources: Resources,
        counts: List<Int>,
    ): List<String> = counts.map { resources.getQuantityString(R.plurals.archived_templates_count, it, it) }

    private fun overlapTimestamps(resources: Resources): List<String> {
        val zone = ZoneId.of("America/New_York")
        val locale = resources.configuration.locales[0]
        return listOf("2024-11-03T05:30:00Z", "2024-11-03T06:30:00Z").map { timestamp ->
            val parts = historicalTimestampParts(Instant.parse(timestamp), zone, locale, includeDate = false)
            resources
                .getString(R.string.history_timestamp_context, parts.localText, parts.utcOffset, parts.zoneId)
                .replace(Regex("""\p{Zs}"""), " ")
        }
    }

    private fun resources(languageTag: String): Resources {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val config =
            Configuration(target.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(languageTag))
            }
        return target.createConfigurationContext(config).resources
    }
}
