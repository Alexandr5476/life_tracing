package com.alexandr5476.lifetracing

import android.app.LocaleManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.StatisticsSeriesDetail
import com.alexandr5476.lifetracing.history.HistoryController
import com.alexandr5476.lifetracing.runtime.ManifestRuntimeEnvironment
import com.alexandr5476.lifetracing.runtime.RuntimeDeadlineIntentCodec
import com.alexandr5476.lifetracing.runtime.RuntimeIsolationApplication
import com.alexandr5476.lifetracing.settings.SettingsAppearanceMutations
import com.alexandr5476.lifetracing.settings.archivedRowKey
import com.alexandr5476.lifetracing.statistics.StatisticsController
import com.alexandr5476.lifetracing.statistics.StatisticsLoadState
import com.alexandr5476.lifetracing.statistics.StatisticsSeriesDetailLoadState
import com.alexandr5476.lifetracing.ui.appearance.AppLanguage
import com.alexandr5476.lifetracing.ui.appearance.AppLanguageController
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferencesRepository
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.historicalTimestampParts
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class MainActivityConfigurationRecoveryTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun preference_recovery_preserves_canonical_facts_after_physical_reopen() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<RuntimeIsolationApplication>()
            val preferences = AppearancePreferencesRepository(app)
            val originalAppearance = preferences.preferences.first()
            app.withDisposableRuntime { runtime ->
                val seed: ConfigurationRecoveryFacts
                val baseline: Map<String, Any?>
                val durable: Map<String, List<List<String?>>>
                RuntimeRecoveryStore(app, runtime.name).use { store ->
                    seed = ConfigurationRecoveryFacts.seed(store)
                    seed.assertSeed(store)
                    baseline = seed.read(store)
                    durable = store.durableFacts()
                }
                try {
                    preferences.setThemeMode(ThemeMode.LIGHT)
                    preferences.setAccentPaletteId(AccentPaletteId.DEFAULT)
                    preferences.setInterfaceScalePercent(100)
                    preferences.setTextScalePercent(100)
                    verifyRouteRecovery(app, runtime, seed)
                    assertEquals(changedAppearance, AppearancePreferencesRepository(app).preferences.first())
                    RuntimeRecoveryStore(app, runtime.name).use { reopened ->
                        val current = seed.read(reopened)
                        baseline.forEach { (reader, before) -> assertEquals(reader, before, current.getValue(reader)) }
                        assertEquals(durable, reopened.durableFacts())
                    }
                    assertEquals(1, runtime.acquisitions)
                    assertNull(RuntimeDeadlineIntentCodec.existingPendingIntent(app))
                } finally {
                    preferences.setThemeMode(originalAppearance.themeMode)
                    preferences.setAccentPaletteId(originalAppearance.accentPaletteId)
                    preferences.setInterfaceScalePercent(originalAppearance.interfaceScalePercent)
                    preferences.setTextScalePercent(originalAppearance.textScalePercent)
                }
            }
        }

    private fun verifyRouteRecovery(
        app: RuntimeIsolationApplication,
        runtime: ManifestRuntimeEnvironment,
        seed: ConfigurationRecoveryFacts,
    ) {
        var originalLanguage: AppLanguage? = null
        val data = RuntimeRecoveryStore(app, runtime.name)
        data.use { store ->
            val journey = Journey(seed, store)
            try {
                val previous =
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        scenario.onActivity { originalLanguage = AppLanguageController.current() }
                        awaitAppLocaleRecreation(compose, scenario, "en") {
                            scenario.onActivity { AppLanguageController.apply(AppLanguage.ENGLISH) }
                        }
                        journey.changePreferences(scenario)
                    }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val persistedTags =
                        app
                            .getSystemService(
                                LocaleManager::class.java,
                            ).applicationLocales
                            .toLanguageTags()
                    assertEquals("ru", persistedTags)
                }
                ActivityScenario.launch(MainActivity::class.java).use { journey.verifyFreshLaunch(it, previous) }
            } finally {
                originalLanguage?.let { language ->
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        awaitAppLocaleRecreation(compose, scenario, language.languageTag) {
                            scenario.onActivity { AppLanguageController.apply(language) }
                        }
                    }
                }
            }
        }
    }

    private data class PreviousPresentation(
        val openArchive: () -> Boolean,
        val formattedNumber: String,
        val formattedTime: String,
    )

    private inner class Journey(
        private val seed: ConfigurationRecoveryFacts,
        private val store: RuntimeRecoveryStore,
    ) {
        private val restoreTag = "archived-restore-${seed.archived.archivedRowKey()}"

        fun changePreferences(scenario: ActivityScenario<MainActivity>): PreviousPresentation {
            val englishNumber = inspectStatistics(scenario)
            val englishTime = inspectHistory(scenario)
            openSettings()
            awaitSelected("settings-language-ENGLISH")
            assertSettingsLabels("Settings", "Back to Daily")
            click("settings-archived-templates")
            awaitArchive()
            val previousRestore = callback(restoreTag)
            back(scenario)
            commitThemeAcrossRecreation(scenario)
            select("settings-accent-SLATE")
            select("settings-interface-120")
            select("settings-text-130")
            val previousSettings = callback("settings-archived-templates")
            awaitAppLocaleRecreation(compose, scenario, "ru") { click("settings-language-RUSSIAN") }
            awaitSelected("settings-language-RUSSIAN")
            assertSettingsLabels("Настройки", "Назад к текущему дню")
            reject(previousSettings)
            click("settings-archived-templates")
            awaitArchive()
            val replacedRestore = callback(restoreTag)
            scenario.recreate()
            awaitArchive()
            node(restoreTag).assertIsDisplayed()
            reject(previousRestore, replacedRestore)
            // Leave Archive as the old Activity's last destination. A fresh launch owns Daily.
            return PreviousPresentation(previousSettings, englishNumber, englishTime)
        }

        private fun awaitArchive() {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(restoreTag).fetchSemanticsNodes().size == 1 }
        }

        private fun commitThemeAcrossRecreation(scenario: ActivityScenario<MainActivity>) {
            val processQueue = AtomicReference<SettingsAppearanceMutations>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // Hold the head of the existing process queue. Settings still submits through
            // its production controller and writes through its actual DataStore repository.
            scenario.onActivity {
                val queue = LifeTracingRuntimeGraph.from(it).appearanceMutations
                processQueue.set(queue)
                queue.launchIfAccepted({ true }) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                compose.waitUntil(10_000) { entered.isCompleted }
                awaitSelected("settings-theme-LIGHT")
                val staleDark = callback("settings-theme-DARK")
                click("settings-theme-DARK")
                compose.waitUntil(10_000) {
                    val light = selectionConfig("settings-theme-LIGHT")
                    light?.getOrNull(SemanticsProperties.Selected) == true &&
                        light.getOrNull(SemanticsProperties.Disabled) != null
                }
                scenario.recreate()
                awaitSelected("settings-theme-LIGHT")
                reject(staleDark)
                release.complete(Unit)
                awaitSelected("settings-theme-DARK")
            } finally {
                release.complete(Unit)
                val drained = CompletableDeferred<Unit>()
                processQueue.get().launchIfAccepted({ true }) { drained.complete(Unit) }
                compose.waitUntil(10_000) { drained.isCompleted }
            }
        }

        fun verifyFreshLaunch(
            scenario: ActivityScenario<MainActivity>,
            previous: PreviousPresentation,
        ) {
            openSettings()
            scenario.onActivity {
                assertEquals("ru", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                assertEquals(AppLanguage.RUSSIAN, AppLanguageController.current())
            }
            awaitSelected("settings-language-RUSSIAN")
            assertSettingsLabels("Настройки", "Назад к текущему дню")
            listOf("settings-theme-DARK", "settings-accent-SLATE", "settings-interface-120", "settings-text-130")
                .forEach(::awaitSelected)
            reject(previous.openArchive)
            back(scenario)
            assertNotEquals(previous.formattedNumber, inspectStatistics(scenario))
            assertNotEquals(previous.formattedTime, inspectHistory(scenario))
        }

        private fun inspectStatistics(scenario: ActivityScenario<MainActivity>): String {
            val seriesId = seed.activity.statisticsSeriesId
            compose.onNodeWithText(label(scenario, R.string.daily_statistics)).performScrollTo().performClick()
            overviewNode("statistics-period-custom").performClick()
            listOf("statistics-custom-start", "statistics-custom-end").forEach { tag ->
                overviewNode(tag).performSemanticsAction(SemanticsActions.SetText) {
                    assertTrue(it(AnnotatedString(ConfigurationRecoveryFacts.date.toString())))
                }
            }
            overviewNode("statistics-custom-apply").performClick()
            val seriesTag = "statistics-series-${seriesId.value}"
            val expectedOverview = store.statistics.overview(ConfigurationRecoveryFacts.period)
            val controller = AtomicReference<StatisticsController>()
            scenario.onActivity { activity ->
                controller.set(
                    activity.statisticsControllerOwner.get { error("Statistics overview must own the visible entry") },
                )
            }
            compose.waitUntil(10_000) {
                val state = controller.get().state.value
                state.selectedPeriod == ConfigurationRecoveryFacts.period &&
                    (state.load as? StatisticsLoadState.Content)?.overview == expectedOverview
            }
            overviewNode(seriesTag).performClick()
            val locale = locale(scenario)
            val number =
                NumberFormat
                    .getNumberInstance(locale)
                    .apply { maximumFractionDigits = 3 }
                    .format(BigDecimal("18446744073709551.614"))
            val numberLine = label(scenario, R.string.statistics_number_total, "$number km")
            val expected = store.statistics.seriesDetail(seriesId, ConfigurationRecoveryFacts.period)
            awaitSeries(scenario, expected)
            val staleRefresh =
                requireNotNull(
                    compose
                        .onNodeWithTag(
                            "statistics-detail-list",
                        ).performScrollToNode(hasTestTag("statistics-detail-refresh"))
                        .let { compose.onNodeWithTag("statistics-detail-refresh") }
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                        .action,
                )
            scenario.recreate()
            awaitSeries(scenario, expected)
            val numberMatcher = hasText(numberLine)
            compose.onNodeWithTag("statistics-detail-list").performScrollToNode(numberMatcher)
            compose.onNode(numberMatcher).performScrollTo().assertIsDisplayed()
            reject(staleRefresh)
            back(scenario)
            compose.onNodeWithTag("statistics-list").assertIsDisplayed()
            back(scenario)
            return number
        }

        private fun awaitSeries(
            scenario: ActivityScenario<MainActivity>,
            expected: StatisticsSeriesDetail,
        ) {
            compose.waitUntil(10_000) {
                var observed = false
                scenario.onActivity {
                    val session = it.statisticsSeriesDetailRouteSessions.activeSession
                    val state = session?.controller?.state?.value
                    observed =
                        session?.expectedSeriesId == seed.activity.statisticsSeriesId &&
                        state?.selectedPeriod == ConfigurationRecoveryFacts.period &&
                        (state.load as? StatisticsSeriesDetailLoadState.Content)?.detail == expected
                }
                observed
            }
        }

        private fun inspectHistory(scenario: ActivityScenario<MainActivity>): String {
            val root = requireNotNull(store.history.getActivityDetail(seed.overlapId)).root
            // Fix the browse clock at the seeded historical date. Point/details and list
            // still read this fixture through the canonical repository.
            scenario.onActivity { activity ->
                val graph = LifeTracingRuntimeGraph.from(activity)
                activity.historyControllerOwner.get {
                    HistoryController(
                        graph.scope,
                        readRoots = { query -> withContext(Dispatchers.IO) { store.history.getCompletedRoots(query) } },
                        now = { root.completedAt },
                        zoneId = { root.originalZoneId },
                        readLatestDate = { time ->
                            withContext(Dispatchers.IO) {
                                store.history.getLatestCompletedPrimaryLocalDate(time.now, time.zoneId)
                            }
                        },
                        semanticGeneration = graph.coordinator.semanticGeneration,
                    )
                }
            }
            compose.onNodeWithText(label(scenario, R.string.daily_history)).performScrollTo().performClick()
            val start =
                historicalTimestampParts(requireNotNull(root.startedAt), root.originalZoneId, locale(scenario), true)
            val end = historicalTimestampParts(root.completedAt, root.originalZoneId, locale(scenario), true)
            assertEquals(start.localText, end.localText)
            assertEquals("-04:00", start.utcOffset)
            assertEquals("-05:00", end.utcOffset)
            assertEquals(ConfigurationRecoveryFacts.zone.id, start.zoneId)
            assertEquals(start.zoneId, end.zoneId)
            val startText =
                label(scenario, R.string.history_timestamp_context, start.localText, start.utcOffset, start.zoneId)
            val endText = label(scenario, R.string.history_timestamp_context, end.localText, end.utcOffset, end.zoneId)
            val range = label(scenario, R.string.history_timed_range, startText, endText)
            val row = "history-activity-${seed.overlapId.value}"
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(range, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val comment = hasText(requireNotNull(seed.activity.shortComment)) and hasAnyAncestor(hasTestTag(row))
            compose.onNode(comment, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            back(scenario)
            return range
        }
    }

    private fun overviewNode(tag: String) =
        compose
            .onNodeWithTag("statistics-list")
            .performScrollToNode(hasTestTag(tag))
            .let { compose.onNodeWithTag(tag).performScrollTo() }

    private fun openSettings() {
        click("daily-settings")
        node("settings-heading").assertIsDisplayed()
    }

    private fun assertSettingsLabels(
        heading: String,
        back: String,
    ) {
        compose.onNodeWithText(heading, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(back, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    private fun select(tag: String) {
        click(tag)
        awaitSelected(tag)
    }

    private fun awaitSelected(tag: String) {
        compose.waitUntil(10_000) {
            val config = selectionConfig(tag)
            config?.getOrNull(SemanticsProperties.Selected) == true &&
                config.getOrNull(SemanticsProperties.Disabled) == null
        }
    }

    private fun selectionConfig(tag: String) =
        compose
            .onAllNodesWithTag(tag)
            .fetchSemanticsNodes()
            .singleOrNull()
            ?.config

    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    private fun click(tag: String) = node(tag).performClick()

    private fun callback(tag: String): () -> Boolean =
        requireNotNull(node(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)

    private fun reject(vararg callbacks: () -> Boolean) {
        compose.runOnIdle { callbacks.forEach { assertFalse(it()) } }
    }

    private fun back(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    private fun locale(scenario: ActivityScenario<MainActivity>): Locale {
        var locale: Locale? = null
        scenario.onActivity { locale = it.resources.configuration.locales[0] }
        return requireNotNull(locale)
    }

    private fun label(
        scenario: ActivityScenario<MainActivity>,
        resource: Int,
        vararg args: String,
    ): String {
        var label: String? = null
        scenario.onActivity { label = it.getString(resource, *args) }
        return requireNotNull(label)
    }

    private companion object {
        val changedAppearance = AppearancePreferences(ThemeMode.DARK, AccentPaletteId.SLATE, 120, 130)
    }
}
