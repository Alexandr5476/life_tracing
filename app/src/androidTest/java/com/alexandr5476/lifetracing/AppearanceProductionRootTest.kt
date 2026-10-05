package com.alexandr5476.lifetracing

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferencesRepository
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AppearanceProductionRootTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun persisted_scales_reach_main_activity_daily_subtree_and_survive_recreation() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = AppearancePreferencesRepository(context)
            val original = repository.preferences.first()
            val expected =
                AppearancePreferences(
                    themeMode = ThemeMode.LIGHT,
                    accentPaletteId = AccentPaletteId.SLATE,
                    interfaceScalePercent = 110,
                    textScalePercent = 125,
                )
            try {
                writeAppearance(repository, expected)
                assertEquals(expected, AppearancePreferencesRepository(context).preferences.first())
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val title = context.getString(R.string.daily_title)
                    val parent = systemDensity(scenario)
                    awaitRootDensity(title, parent.density * 1.1f, parent.fontScale * 1.25f)
                    scenario.recreate()
                    val recreatedParent = systemDensity(scenario)
                    awaitRootDensity(title, recreatedParent.density * 1.1f, recreatedParent.fontScale * 1.25f)
                    repository.setInterfaceScalePercent(100)
                    repository.setTextScalePercent(100)
                    awaitRootDensity(title, recreatedParent.density, recreatedParent.fontScale)
                }
            } finally {
                writeAppearance(repository, original)
            }
        }

    private fun systemDensity(scenario: ActivityScenario<MainActivity>): Density {
        val result = AtomicReference<Density>()
        scenario.onActivity { activity ->
            result.set(
                Density(
                    density = activity.resources.displayMetrics.density,
                    fontScale = activity.resources.configuration.fontScale,
                ),
            )
        }
        return result.get()
    }

    private fun awaitRootDensity(
        title: String,
        density: Float,
        fontScale: Float,
    ) {
        compose.waitUntil(timeoutMillis = 5_000) {
            val effective =
                compose
                    .onAllNodesWithText(title)
                    .fetchSemanticsNodes()
                    .singleOrNull()
                    ?.layoutInfo
                    ?.density
            effective?.density == density && effective?.fontScale == fontScale
        }
    }

    private suspend fun writeAppearance(
        repository: AppearancePreferencesRepository,
        appearance: AppearancePreferences,
    ) {
        repository.setThemeMode(appearance.themeMode)
        repository.setAccentPaletteId(appearance.accentPaletteId)
        repository.setInterfaceScalePercent(appearance.interfaceScalePercent)
        repository.setTextScalePercent(appearance.textScalePercent)
    }
}
