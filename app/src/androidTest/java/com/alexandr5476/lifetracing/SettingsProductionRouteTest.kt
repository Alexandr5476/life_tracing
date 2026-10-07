package com.alexandr5476.lifetracing

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.daily.DailyAction
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.appearance.AppLanguage
import com.alexandr5476.lifetracing.ui.appearance.AppLanguageController
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferencesRepository
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

@Suppress("TooManyFunctions")
class SettingsProductionRouteTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    @Suppress("LongMethod")
    fun settings_controls_recreation_and_back_preserve_daily_and_live_runtime() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = AppearancePreferencesRepository(context)
            val originalAppearance = repository.preferences.first()
            val live = LiveSessionRepository.create(context)
            clearLiveSession(live)
            val now = Instant.now()
            val template =
                TemplateAuthoringRepository.create(context).createActivityTemplate(
                    ActivityTemplateDraft(
                        "Settings fixture ${now.toEpochMilli()}",
                        null,
                        TimeTrackingMode.STOPWATCH,
                        null,
                    ),
                    createdAt = now,
                )
            LibraryRepository.create(context).startActivityFromTemplate(template.id, now, now, ZoneOffset.UTC)
            val originalRuntime = requireNotNull(live.getActiveRuntime())
            try {
                writeAppearance(repository, AppearancePreferences())
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val originalLanguage = readLanguage(scenario)
                    try {
                        scenario.onActivity { AppLanguageController.apply(AppLanguage.SYSTEM) }
                        compose.waitUntil(5_000) { readLanguage(scenario) == AppLanguage.SYSTEM }
                        awaitDestination("daily-settings")
                        val daily = LifeTracingRuntimeGraph.from(context).dailyController
                        scenario.onActivity {
                            daily.dispatch(DailyAction.Today)
                            daily.dispatch(DailyAction.PreviousDay)
                        }
                        val selectedDate = daily.state.value.selectedDate
                        click("daily-settings")
                        awaitSelected("settings-theme-SYSTEM")
                        var expected = AppearancePreferences(themeMode = ThemeMode.DARK)
                        selectAppearance(repository, "settings-theme-DARK", expected)
                        expected = expected.copy(accentPaletteId = AccentPaletteId.SLATE)
                        selectAppearance(repository, "settings-accent-SLATE", expected)
                        expected = expected.copy(interfaceScalePercent = 115)
                        selectAppearance(repository, "settings-interface-115", expected)
                        expected = expected.copy(textScalePercent = 125)
                        selectAppearance(repository, "settings-text-125", expected)
                        scenario.recreate()
                        assertSelections()
                        assertEquals(expected, AppearancePreferencesRepository(context).preferences.first())
                        val appearanceStorage = readStoredAppearance(context).asMap()
                        assertEquals(
                            setOf("theme_mode", "accent_palette_id", "interface_scale_percent", "text_scale_percent"),
                            appearanceStorage.keys.map { it.name }.toSet(),
                        )
                        listOf(AppLanguage.ENGLISH, AppLanguage.RUSSIAN, AppLanguage.SYSTEM).forEach { language ->
                            click("settings-language-$language")
                            compose.waitUntil(5_000) { readLanguage(scenario) == language }
                            awaitSelected("settings-language-$language")
                            assertPlatformLocales(scenario, language)
                            scenario.recreate()
                            awaitSelected("settings-language-$language")
                            assertSelections()
                            val title = AtomicReference<String>()
                            scenario.onActivity { title.set(it.getString(R.string.settings_title)) }
                            compose
                                .onNodeWithText(title.get())
                                .performScrollTo()
                                .assertIsDisplayed()
                            assertEquals(expected, repository.preferences.first())
                            assertEquals(appearanceStorage, readStoredAppearance(context).asMap())
                            assertEquals(originalRuntime, live.getActiveRuntime())
                            assertSame(daily, LifeTracingRuntimeGraph.from(context).dailyController)
                            assertEquals(selectedDate, daily.state.value.selectedDate)
                        }
                        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                        awaitDestination("daily-settings")
                        listOf("fr", "fr,en").forEach { unsupported ->
                            scenario.onActivity {
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(unsupported))
                            }
                            scenario.recreate()
                            awaitDestination("daily-settings")
                            val seeded = AtomicReference<String>()
                            scenario.onActivity {
                                seeded.set(AppCompatDelegate.getApplicationLocales().toLanguageTags())
                            }
                            assertEquals(unsupported, seeded.get())
                            click("daily-settings")
                            awaitSelected("settings-language-SYSTEM")
                            assertPlatformLocales(scenario, AppLanguage.SYSTEM)
                            scenario.recreate()
                            awaitSelected("settings-language-SYSTEM")
                            assertPlatformLocales(scenario, AppLanguage.SYSTEM)
                            assertEquals(expected, repository.preferences.first())
                            assertEquals(appearanceStorage, readStoredAppearance(context).asMap())
                            assertEquals(originalRuntime, live.getActiveRuntime())
                            assertSame(daily, LifeTracingRuntimeGraph.from(context).dailyController)
                            assertEquals(selectedDate, daily.state.value.selectedDate)
                            click("settings-back")
                            awaitDestination("daily-settings")
                        }
                        assertDailyDensity(scenario)
                        assertEquals(originalRuntime, live.getActiveRuntime())
                        assertEquals(selectedDate, daily.state.value.selectedDate)
                        click("daily-settings")
                        awaitDestination("settings-back")
                        click("settings-back")
                        awaitDestination("daily-settings")
                        assertEquals(originalRuntime, live.getActiveRuntime())
                        assertEquals(selectedDate, daily.state.value.selectedDate)
                    } finally {
                        scenario.onActivity {
                            LifeTracingRuntimeGraph.from(context).dailyController.dispatch(DailyAction.Today)
                            AppLanguageController.apply(originalLanguage)
                        }
                        compose.waitUntil(5_000) { readLanguage(scenario) == originalLanguage }
                    }
                }
            } finally {
                writeAppearance(repository, originalAppearance)
                clearLiveSession(live)
            }
        }

    private suspend fun selectAppearance(
        repository: AppearancePreferencesRepository,
        tag: String,
        expected: AppearancePreferences,
    ) {
        click(tag)
        // Drive the Compose dispatcher before waiting for the command's persisted result.
        awaitSelected(tag)
        assertEquals(expected, withTimeout(5_000) { repository.preferences.first() })
    }

    private fun assertSelections() {
        listOf("settings-theme-DARK", "settings-accent-SLATE", "settings-interface-115", "settings-text-125")
            .forEach { tag ->
                awaitSelected(tag)
                compose
                    .onNodeWithTag(tag)
                    .assertIsSelected()
            }
    }

    private fun click(tag: String) {
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .performClick()
    }

    private fun awaitDestination(tag: String) {
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithTag(tag)
                .fetchSemanticsNodes()
                .size == 1
        }
    }

    private fun awaitSelected(tag: String) {
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithTag(tag)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.let {
                    it.config.getOrNull(SemanticsProperties.Selected) == true &&
                        it.config.getOrNull(SemanticsProperties.Disabled) == null
                } == true
        }
    }

    private fun readLanguage(scenario: ActivityScenario<MainActivity>): AppLanguage {
        val value = AtomicReference<AppLanguage>()
        scenario.onActivity { value.set(AppLanguageController.current()) }
        return value.get()
    }

    private fun assertPlatformLocales(
        scenario: ActivityScenario<MainActivity>,
        language: AppLanguage,
    ) {
        val tags = AtomicReference<String>()
        scenario.onActivity { tags.set(AppCompatDelegate.getApplicationLocales().toLanguageTags()) }
        assertEquals(language.languageTag, tags.get())
    }

    private fun assertDailyDensity(scenario: ActivityScenario<MainActivity>) {
        val base = AtomicReference<Density>()
        scenario.onActivity {
            base.set(Density(it.resources.displayMetrics.density, it.resources.configuration.fontScale))
        }
        compose.waitUntil(5_000) {
            val effective =
                compose
                    .onAllNodesWithTag("daily-settings")
                    .fetchSemanticsNodes()
                    .singleOrNull()
                    ?.layoutInfo
                    ?.density
            effective?.density == base.get().density * 1.15f &&
                effective?.fontScale == base.get().fontScale * 1.25f
        }
    }

    private suspend fun readStoredAppearance(context: Context): Preferences =
        FileSystem.SYSTEM
            .source(context.preferencesDataStoreFile("appearance").toOkioPath())
            .buffer()
            .use { PreferencesSerializer.readFrom(it) }

    private suspend fun writeAppearance(
        repository: AppearancePreferencesRepository,
        appearance: AppearancePreferences,
    ) {
        repository.setThemeMode(appearance.themeMode)
        repository.setAccentPaletteId(appearance.accentPaletteId)
        repository.setInterfaceScalePercent(appearance.interfaceScalePercent)
        repository.setTextScalePercent(appearance.textScalePercent)
    }

    private fun clearLiveSession(live: LiveSessionRepository) {
        val at = Instant.now()
        when (live.getActiveSession()?.kind) {
            ActiveSessionKind.ACTIVITY -> live.completeActiveActivity(at)
            ActiveSessionKind.SEQUENCE -> live.endSequenceEarly(at)
            null -> Unit
        }
    }
}
