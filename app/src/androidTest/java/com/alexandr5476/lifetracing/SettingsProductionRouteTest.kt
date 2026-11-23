@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.alexandr5476.lifetracing

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.alexandr5476.lifetracing.daily.DailyAction
import com.alexandr5476.lifetracing.data.persistence.LibraryRepository
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.settings.SettingsRoute
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppLanguage
import com.alexandr5476.lifetracing.ui.appearance.AppLanguageController
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferencesRepository
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.renderedTextLayout
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

@Suppress("TooManyFunctions")
class SettingsProductionRouteTest {
    val compose = createEmptyComposeRule()
    private val keyboard = KeyboardInputFixture(compose)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(keyboard).around(compose)

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
                        changeLocalesAndAwaitRecreation(scenario, AppLanguage.SYSTEM.languageTag) {
                            scenario.onActivity { AppLanguageController.apply(AppLanguage.SYSTEM) }
                        }
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
                        awaitSelected("settings-interface-100")
                        awaitSelected("settings-text-100")
                        assertRootDensity(scenario, "settings-heading", AppearancePreferences())
                        var expected = AppearancePreferences(themeMode = ThemeMode.DARK)
                        selectAppearance(repository, "settings-theme-DARK", expected)
                        awaitBackLabelColor(scenario, Color(0xFF8FCEFA))
                        expected = expected.copy(accentPaletteId = AccentPaletteId.SLATE)
                        selectAppearance(repository, "settings-accent-SLATE", expected)
                        awaitBackLabelColor(scenario, Color(0xFF9DCBEB))
                        expected = expected.copy(interfaceScalePercent = 90)
                        selectAppearance(repository, "settings-interface-90", expected)
                        expected = expected.copy(textScalePercent = 90)
                        selectAppearance(repository, "settings-text-90", expected)
                        assertRootDensity(scenario, "settings-heading", expected)
                        expected = expected.copy(interfaceScalePercent = 120)
                        selectAppearance(repository, "settings-interface-120", expected)
                        expected = expected.copy(textScalePercent = 130)
                        selectAppearance(repository, "settings-text-130", expected)
                        scenario.recreate()
                        assertSelections()
                        assertEquals(expected, AppearancePreferencesRepository(context).preferences.first())
                        val appearanceStorage = readStoredAppearance(context).asMap()
                        assertEquals(
                            setOf("theme_mode", "accent_palette_id", "interface_scale_percent", "text_scale_percent"),
                            appearanceStorage.keys.map { it.name }.toSet(),
                        )
                        listOf(AppLanguage.ENGLISH, AppLanguage.RUSSIAN, AppLanguage.SYSTEM).forEach { language ->
                            val languageTag = "settings-language-$language"
                            changeLocalesAndAwaitRecreation(scenario, language.languageTag) {
                                pressEnter(languageTag)
                            }
                            compose.waitUntil(5_000) { readLanguage(scenario) == language }
                            awaitSelected(languageTag)
                            keyboard.awaitFocus(compose.onNodeWithTag(languageTag))
                            assertPlatformLocales(scenario, language)
                            scenario.recreate()
                            awaitSelected(languageTag)
                            keyboard.awaitFocus(compose.onNodeWithTag(languageTag))
                            verifyLanguageTraversal(languageTag, language)
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
                            changeLocalesAndAwaitRecreation(scenario, unsupported) {
                                val locales = LocaleListCompat.forLanguageTags(unsupported)
                                scenario.onActivity {
                                    AppCompatDelegate.setApplicationLocales(locales)
                                }
                            }
                            scenario.recreate()
                            awaitDestination("daily-settings")
                            val seeded = AtomicReference<String>()
                            scenario.onActivity {
                                seeded.set(AppCompatDelegate.getApplicationLocales().toLanguageTags())
                            }
                            assertEquals(unsupported, seeded.get())
                            changeLocalesAndAwaitRecreation(scenario, AppLanguage.SYSTEM.languageTag) {
                                click("daily-settings")
                            }
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
                        assertRootDensity(scenario, "daily-settings", expected)
                        assertEquals(originalRuntime, live.getActiveRuntime())
                        assertEquals(selectedDate, daily.state.value.selectedDate)
                        verifyArchiveKeyboardJourney(scenario)
                        click("daily-settings")
                        assertSelections()
                        selectAppearance(repository, "settings-theme-LIGHT", expected.copy(themeMode = ThemeMode.LIGHT))
                        selectAppearance(
                            repository,
                            "settings-accent-DEFAULT",
                            expected.copy(themeMode = ThemeMode.LIGHT, accentPaletteId = AccentPaletteId.DEFAULT),
                        )
                        click("settings-back")
                        awaitDestination("daily-settings")
                        assertEquals(originalRuntime, live.getActiveRuntime())
                        assertEquals(selectedDate, daily.state.value.selectedDate)
                        verifyInjectedSystemLocales(context, scenario, repository)
                    } finally {
                        changeLocalesAndAwaitRecreation(scenario, originalLanguage.languageTag) {
                            scenario.onActivity {
                                LifeTracingRuntimeGraph.from(context).dailyController.dispatch(DailyAction.Today)
                                AppLanguageController.apply(originalLanguage)
                            }
                        }
                        compose.waitUntil(5_000) { readLanguage(scenario) == originalLanguage }
                    }
                }
            } finally {
                writeAppearance(repository, originalAppearance)
                clearLiveSession(live)
            }
        }

    private fun pressEnter(tag: String) {
        val node = compose.onNodeWithTag(tag).performScrollTo()
        keyboard.enter(node)
        node.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        node.performKeyInput { pressKey(Key.Enter) }
    }

    private fun verifyInjectedSystemLocales(
        context: Context,
        scenario: ActivityScenario<MainActivity>,
        repository: AppearancePreferencesRepository,
    ) {
        val locale = mutableStateOf(Locale.ENGLISH)
        // Supply Settings with two representative effective Android configurations on the
        // existing Activity. This leaves the device's global language untouched.
        scenario.onActivity { activity ->
            activity.setContent {
                val config = Configuration(LocalConfiguration.current).apply { setLocale(locale.value) }
                CompositionLocalProvider(
                    LocalConfiguration provides config,
                    LocalResources provides activity.createConfigurationContext(config).resources,
                ) {
                    SettingsRoute(
                        repository,
                        LifeTracingRuntimeGraph.from(context).appearanceMutations,
                        isRouteVisible = true,
                        ownsRoute = { true },
                        onBack = {},
                        onArchivedTemplates = {},
                    )
                }
            }
        }
        awaitSelected("settings-language-SYSTEM")
        compose.onNodeWithText("Settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back to Daily", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertPlatformLocales(scenario, AppLanguage.SYSTEM)
        compose.runOnIdle { locale.value = Locale.forLanguageTag("ru") }
        awaitSelected("settings-language-SYSTEM")
        compose.onNodeWithText("Настройки").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Назад к текущему дню", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertPlatformLocales(scenario, AppLanguage.SYSTEM)
    }

    private fun verifyArchiveKeyboardJourney(scenario: ActivityScenario<MainActivity>) {
        pressEnter("daily-settings")
        awaitDestination("settings-heading")
        focusedKey("settings-heading", Key.Tab)
        focusedKey("settings-back", Key.Tab)
        val openArchive = nativeClick("settings-archived-templates")
        focusedKey("settings-archived-templates", Key.Enter)
        awaitDestination("archived-back")
        keyboard.awaitFocus(compose.onNodeWithTag("archived-back"))
        val preRecreation = nativeClick("archived-back")
        scenario.recreate()
        awaitDestination("archived-back")
        focusedKey("archived-back", Key.Escape)
        awaitDestination("settings-archived-templates")
        compose.runOnIdle {
            assertFalse(openArchive())
            assertFalse(preRecreation())
        }
        focusedKey("settings-archived-templates", Key.Enter)
        awaitDestination("archived-back")
        val toolbarBack = nativeClick("archived-back")
        focusedKey("archived-back", Key.Enter)
        awaitDestination("settings-archived-templates")
        compose.runOnIdle { assertFalse(toolbarBack()) }
        focusedKey("settings-archived-templates", Key.Enter)
        awaitDestination("archived-back")
        keyboard.awaitFocus(compose.onNodeWithTag("archived-back"))
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitDestination("settings-archived-templates")
        focusedKey("settings-archived-templates", Key.Escape)
        awaitDestination("daily-settings")
        keyboard.awaitFocus(compose.onNodeWithTag("daily-settings"))
    }

    private fun focusedKey(
        tag: String,
        key: Key,
    ) {
        val node = compose.onNodeWithTag(tag)
        keyboard.awaitFocus(node)
        node.performKeyInput { pressKey(key) }
    }

    private fun nativeClick(tag: String): () -> Boolean {
        val semantics = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        return requireNotNull(semantics[SemanticsActions.OnClick].action)
    }

    private fun verifyLanguageTraversal(
        tag: String,
        language: AppLanguage,
    ) {
        val followingTag =
            when (language) {
                AppLanguage.SYSTEM -> "settings-language-ENGLISH"
                AppLanguage.ENGLISH -> "settings-language-RUSSIAN"
                AppLanguage.RUSSIAN -> "settings-interface-90"
            }
        val selected = compose.onNodeWithTag(tag)
        selected.performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag(followingTag).assertIsFocused().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        selected.assertIsFocused().assertIsSelected()
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

    private fun awaitBackLabelColor(
        scenario: ActivityScenario<MainActivity>,
        color: Color,
    ) {
        val label = AtomicReference<String>()
        scenario.onActivity { label.set(it.getString(R.string.settings_back)) }
        val back =
            compose
                .onNodeWithText(label.get(), useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
        compose.waitUntil(5_000) {
            val style = back.renderedTextLayout().layoutInput.style
            style.color == color
        }
    }

    private fun assertSelections() {
        listOf("settings-theme-DARK", "settings-accent-SLATE", "settings-interface-120", "settings-text-130")
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

    private fun changeLocalesAndAwaitRecreation(
        scenario: ActivityScenario<MainActivity>,
        expectedTags: String,
        change: () -> Unit,
    ) = awaitAppLocaleRecreation(compose, scenario, expectedTags, change)

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

    private fun assertRootDensity(
        scenario: ActivityScenario<MainActivity>,
        tag: String,
        appearance: AppearancePreferences,
    ) {
        val base = AtomicReference<Density>()
        scenario.onActivity {
            base.set(Density(it.resources.displayMetrics.density, it.resources.configuration.fontScale))
        }
        compose.waitUntil(5_000) {
            val effective =
                compose
                    .onAllNodesWithTag(tag)
                    .fetchSemanticsNodes()
                    .singleOrNull()
                    ?.layoutInfo
                    ?.density
            effective?.density == base.get().density * (appearance.interfaceScalePercent / 100f) &&
                effective?.fontScale == base.get().fontScale * (appearance.textScalePercent / 100f)
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

internal fun awaitAppLocaleRecreation(
    compose: ComposeTestRule,
    scenario: ActivityScenario<MainActivity>,
    expectedTags: String,
    change: () -> Unit,
) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    val previous = AtomicReference<MainActivity>()
    val previousTags = AtomicReference<String>()
    scenario.onActivity {
        previous.set(it)
        previousTags.set(AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }
    val resumedReplacement = CountDownLatch(1)
    val callback =
        ActivityLifecycleCallback { activity, stage ->
            if (
                activity is MainActivity &&
                activity !== previous.get() &&
                stage == Stage.RESUMED &&
                AppCompatDelegate.getApplicationLocales().toLanguageTags() == expectedTags
            ) {
                resumedReplacement.countDown()
            }
        }
    // The platform override and selected chip can change before locale-triggered recreation
    // finishes. Register before the command, and await its replacement before an explicit
    // scenario.recreate(), another locale command, or scenario.close() can race that transition.
    instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
    try {
        change()
        val changed = previousTags.get() != expectedTags
        if (changed) compose.waitUntil(10_000) { resumedReplacement.count == 0L }
        scenario.onActivity {
            assertEquals(expectedTags, AppCompatDelegate.getApplicationLocales().toLanguageTags())
            assertEquals(Stage.RESUMED, monitor.getLifecycleStageOf(it))
            if (changed) assertNotSame(previous.get(), it)
        }
    } finally {
        instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
    }
}
