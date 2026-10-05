package com.alexandr5476.lifetracing.settings

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.ui.appearance.AppLanguage
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.AppearanceScalePolicy
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.Locale

class SettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun failed_write_keeps_canonical_selections_and_localized_retry_recovers() {
        val initial =
            AppearancePreferences(
                themeMode = ThemeMode.DARK,
                accentPaletteId = AccentPaletteId.SLATE,
                interfaceScalePercent = 115,
                textScalePercent = 125,
            )
        val values = MutableStateFlow(initial)
        var fail = true
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember {
                    SettingsController(
                        values,
                        {
                            if (fail) throw IOException("injected write failure")
                            values.value = initial.copy(themeMode = (it as SettingsChange.Theme).value)
                        },
                        { AppLanguage.SYSTEM },
                        {},
                        scope,
                    )
                }
            val state by controller.state.collectAsState()
            LifeTracingAppearance(initial) {
                SettingsScreen(state, controller::change, controller::retryMutation, controller::retryRead, {})
            }
        }
        compose
            .onNodeWithTag("settings-theme-LIGHT")
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithTag("settings-theme-DARK")
            .assertIsSelected()
        compose
            .onNodeWithTag("settings-accent-SLATE")
            .assertIsSelected()
        compose
            .onNodeWithTag("settings-interface-115")
            .assertIsSelected()
        compose
            .onNodeWithTag("settings-text-125")
            .assertIsSelected()
        compose
            .onNodeWithText(compose.activity.getString(R.string.settings_write_failure))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(compose.activity.getString(R.string.settings_saving))
            .assertDoesNotExist()
        compose.runOnIdle { fail = false }
        compose
            .onNodeWithTag("settings-retry")
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithTag("settings-theme-LIGHT")
            .assertIsSelected()
        compose
            .onNodeWithTag("settings-retry")
            .assertDoesNotExist()
        compose
            .onNodeWithTag("settings-interface-115")
            .assertIsSelected()
        compose
            .onNodeWithTag("settings-text-125")
            .assertIsSelected()
    }

    @Test
    fun english_and_russian_controls_reflow_at_maximum_app_scale_and_preserve_the_exact_option_sets() {
        val locale = mutableStateOf(Locale.ENGLISH)
        val appearance = AppearancePreferences(interfaceScalePercent = 120, textScalePercent = 130)
        compose.setContent {
            val base = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale.value) }
            val localized = base.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density = 2f, fontScale = 1.4f),
            ) {
                LifeTracingAppearance(appearance) {
                    SettingsScreen(
                        SettingsState(
                            appearance = appearance,
                            language = AppLanguage.SYSTEM,
                            failure = SettingsChange.Theme(ThemeMode.DARK),
                        ),
                        {},
                        {},
                        {},
                        {},
                    )
                }
            }
        }
        listOf(Locale.ENGLISH, Locale.forLanguageTag("ru")).forEach { language ->
            compose.runOnIdle { locale.value = language }
            val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(language) }
            val context = compose.activity.createConfigurationContext(configuration)
            compose
                .onNodeWithText(context.getString(R.string.settings_title))
                .performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(context.getString(R.string.settings_write_failure))
                .performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(context.getString(R.string.settings_accent_default))
                .performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithTag("settings-language-RUSSIAN")
                .performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithTag("settings-interface-120")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsSelected()
            compose
                .onNodeWithTag("settings-text-130")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsSelected()
            compose
                .onAllNodes(tagStartsWith("settings-interface-"))
                .assertCountEquals(AppearanceScalePolicy.interfacePercentages.size)
            compose
                .onAllNodes(tagStartsWith("settings-text-"))
                .assertCountEquals(AppearanceScalePolicy.textPercentages.size)
            compose
                .onNodeWithTag("settings-interface-125")
                .assertDoesNotExist()
            compose
                .onNodeWithTag("settings-text-135")
                .assertDoesNotExist()
        }
    }

    @Test
    fun loading_and_read_failure_use_resources_and_offer_reload() {
        val state = mutableStateOf(SettingsState())
        compose.setContent {
            LifeTracingAppearance(AppearancePreferences()) {
                SettingsScreen(
                    state.value,
                    {},
                    {},
                    { state.value = SettingsState(AppearancePreferences(), AppLanguage.SYSTEM) },
                    {},
                )
            }
        }
        compose
            .onNodeWithText(compose.activity.getString(R.string.settings_loading))
            .assertIsDisplayed()
        compose.runOnIdle { state.value = SettingsState(appearanceReadFailed = true) }
        compose
            .onNodeWithText(compose.activity.getString(R.string.settings_read_failure))
            .assertIsDisplayed()
        compose
            .onNodeWithTag("settings-reload")
            .performClick()
        compose
            .onNodeWithTag("settings-theme-SYSTEM")
            .assertIsSelected()
        compose
            .onNodeWithTag("settings-reload")
            .assertDoesNotExist()
    }

    private fun tagStartsWith(prefix: String) =
        SemanticsMatcher("Settings option tag starts with $prefix") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }
}
