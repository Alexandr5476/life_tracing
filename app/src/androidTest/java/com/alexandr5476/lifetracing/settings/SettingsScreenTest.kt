@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.alexandr5476.lifetracing.settings

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppLanguage
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.IOException
import java.util.Locale

@Suppress("TooManyFunctions")
class SettingsScreenTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val keyboard = KeyboardInputFixture(compose)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(keyboard).around(compose)

    @Test
    @Suppress("LongMethod")
    fun current_settings_actions_work_and_retained_native_actions_expire_on_hide_and_disposal() {
        val initial = SettingsState(AppearancePreferences(), AppLanguage.SYSTEM)
        val failed = initial.copy(failure = SettingsChange.Theme(ThemeMode.DARK))
        val state = mutableStateOf(failed)
        val visible = mutableStateOf(true)
        val appearance = mutableStateOf(AppearancePreferences())
        val mounted = mutableStateOf(true)
        val ownsRoute = { visible.value }
        val changes = mutableListOf<SettingsChange>()
        val commands = mutableListOf<String>()
        compose.setContent {
            if (mounted.value) {
                LifeTracingAppearance(appearance.value) {
                    SettingsScreen(
                        state.value,
                        { changes += it },
                        { commands += "retry" },
                        { commands += "reload" },
                        { commands += "back" },
                        { commands += "archive" },
                        visible.value,
                        ownsRoute,
                        readCurrent = { state.value },
                    )
                }
            }
        }
        val optionTags =
            listOf(
                "settings-theme-LIGHT",
                "settings-accent-SLATE",
                "settings-language-ENGLISH",
                "settings-interface-105",
                "settings-text-110",
            )
        val commandTags = listOf("settings-retry", "settings-back", "settings-archived-templates")
        val retained = (optionTags + commandTags).map(::nativeClick)
        compose.runOnIdle {
            retained.forEach { assertTrue(it()) }
            assertEquals(
                listOf(
                    SettingsChange.Theme(ThemeMode.LIGHT),
                    SettingsChange.Accent(AccentPaletteId.SLATE),
                    SettingsChange.Language(AppLanguage.ENGLISH),
                    SettingsChange.InterfaceScale(105),
                    SettingsChange.TextScale(110),
                ),
                changes,
            )
            assertEquals(listOf("retry", "back", "archive"), commands)
            changes.clear()
            commands.clear()
            visible.value = false
            appearance.value = AppearancePreferences(themeMode = ThemeMode.DARK, textScalePercent = 130)
            retained.forEach { assertFalse(it()) }
        }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        val current = nativeClick("settings-theme-LIGHT")
        compose.runOnIdle {
            retained.forEach { assertFalse(it()) }
            assertTrue(current())
            assertEquals(listOf(SettingsChange.Theme(ThemeMode.LIGHT)), changes)
            assertTrue(commands.isEmpty())
            state.value = failed.copy(appearanceReadFailed = true)
        }
        compose.onNodeWithTag(SETTINGS_RETRY).assertIsNotEnabled()
        val blockedRetry = nativeClick(SETTINGS_RETRY)
        val reload = nativeClick("settings-reload")
        compose.runOnIdle {
            assertFalse(blockedRetry())
            assertTrue(reload())
            mounted.value = false
        }
        compose.onNodeWithTag("settings-page").assertDoesNotExist()
        compose.runOnIdle { mounted.value = true }
        val replacementReload = nativeClick("settings-reload")
        compose.runOnIdle {
            assertFalse(reload())
            assertFalse(current())
            assertTrue(replacementReload())
            assertEquals(listOf("reload", "reload"), commands)
        }
    }

    @Test
    fun accepted_write_completes_while_hidden_and_selection_waits_for_the_repository() {
        val initial = AppearancePreferences()
        val values = MutableStateFlow(initial)
        val visible = mutableStateOf(true)
        val ownsRoute = { visible.value }
        val releaseWrite = CompletableDeferred<Unit>()
        val writeReturned = CompletableDeferred<Unit>()
        val writes = mutableListOf<SettingsChange.Appearance>()
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val mutations = SettingsAppearanceMutations(processScope)
        try {
            compose.setContent {
                val scope = rememberCoroutineScope()
                val controller =
                    remember(scope) {
                        SettingsController(
                            values,
                            {
                                writes += it
                                releaseWrite.await()
                                writeReturned.complete(Unit)
                            },
                            { AppLanguage.SYSTEM },
                            {},
                            scope,
                            mutations,
                        )
                    }
                val state by controller.state.collectAsState()
                LifeTracingAppearance(initial) {
                    SettingsScreen(
                        state,
                        controller::change,
                        controller::retryMutation,
                        controller::retryRead,
                        {},
                        isRouteVisible = visible.value,
                        ownsRoute = ownsRoute,
                        readCurrent = { controller.state.value },
                    )
                }
            }
            keyboard.enter()
            val lightChip = compose.onNodeWithTag("settings-theme-LIGHT")
            val light = nativeClick("settings-theme-LIGHT")
            val dark = nativeClick("settings-theme-DARK")
            lightChip.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
            compose.runOnIdle {
                assertTrue(light())
                assertFalse(dark())
                visible.value = false
                releaseWrite.complete(Unit)
            }
            compose.waitUntil(5_000) { writeReturned.isCompleted }
            compose
                .onAllNodes(hasTestTag("settings-theme-LIGHT") and isFocused(), useUnmergedTree = true)
                .assertCountEquals(0)
            compose.runOnIdle {
                assertFalse(light())
                visible.value = true
            }
            compose.onNodeWithTag("settings-theme-SYSTEM").assertIsSelected()
            assertMutationStatus(R.string.settings_awaiting_observation, themeDescription(R.string.settings_light))
            keyboard.awaitFocus(compose.onNodeWithTag(SETTINGS_HEADING))
            compose.runOnIdle { values.value = initial.copy(themeMode = ThemeMode.LIGHT) }
            lightChip.assertIsSelected()
            keyboard.awaitFocus(lightChip)
            compose.onNodeWithTag("settings-mutation-status").assertDoesNotExist()
            compose.runOnIdle {
                assertFalse(dark())
                assertEquals(listOf(SettingsChange.Theme(ThemeMode.LIGHT)), writes)
            }
        } finally {
            processScope.cancel()
        }
    }

    @Test
    @Suppress("LongMethod")
    fun keyboard_appearance_changes_keep_canonical_selection_until_publication_and_restore_the_same_option() {
        val initial = AppearancePreferences()
        val values = MutableStateFlow(initial)
        val writes = mutableListOf<SettingsChange.Appearance>()
        var release = CompletableDeferred<Unit>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember {
                    SettingsController(
                        values,
                        {
                            writes += it
                            release.await()
                        },
                        { AppLanguage.SYSTEM },
                        {},
                        scope,
                    )
                }
            val state by controller.state.collectAsState()
            LifeTracingAppearance(state.appearance ?: initial) {
                SettingsScreen(
                    state,
                    controller::change,
                    controller::retryMutation,
                    controller::retryRead,
                    {},
                    readCurrent = { controller.state.value },
                )
            }
        }
        keyboard.enter()
        val mutations =
            listOf(
                AppearanceChoice("settings-theme", "SYSTEM", "DARK", initial.copy(themeMode = ThemeMode.DARK)),
                AppearanceChoice(
                    "settings-accent",
                    "DEFAULT",
                    "SLATE",
                    initial.copy(themeMode = ThemeMode.DARK, accentPaletteId = AccentPaletteId.SLATE),
                ),
                AppearanceChoice(
                    "settings-interface",
                    "100",
                    "120",
                    AppearancePreferences(ThemeMode.DARK, AccentPaletteId.SLATE, 120),
                ),
                AppearanceChoice(
                    "settings-text",
                    "100",
                    "130",
                    AppearancePreferences(ThemeMode.DARK, AccentPaletteId.SLATE, 120, 130),
                ),
            )
        mutations.forEachIndexed { index, mutation ->
            compose.runOnIdle { release = CompletableDeferred() }
            val (prefix, old, requested, canonical) = mutation
            val previouslySelected = compose.onNodeWithTag("$prefix-$old").assertIsSelected()
            val originalAction = nativeClick("$prefix-$old")
            val choice = compose.onNodeWithTag("$prefix-$requested").performScrollTo().assertIsNotSelected()
            val description = choice.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
            choice.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
            choice.performKeyInput { pressKey(Key.Enter) }
            assertMutationStatus(R.string.settings_saving, description)
            keyboard.awaitFocus(compose.onNodeWithTag(SETTINGS_HEADING))
            previouslySelected.assertIsSelected().assertIsNotEnabled()
            choice.assertIsNotSelected().assertIsNotEnabled()
            val disabledAction = nativeClick("$prefix-$requested")
            compose.runOnIdle {
                assertFalse(originalAction())
                assertFalse(disabledAction())
                assertEquals(index + 1, writes.size)
                release.complete(Unit)
            }
            assertMutationStatus(R.string.settings_awaiting_observation, description)
            previouslySelected.assertIsSelected().assertIsNotEnabled()
            choice.assertIsNotSelected().assertIsNotEnabled()
            compose.runOnIdle {
                assertFalse(disabledAction())
                values.value = canonical
            }
            choice.assertIsSelected().assertIsEnabled()
            previouslySelected.assertIsNotSelected().assertIsEnabled()
            compose.onNodeWithTag("settings-mutation-status").assertDoesNotExist()
            keyboard.awaitFocus(choice)
            choice.assertIsDisplayed()
            val group = optionGroups().single { it.prefix == prefix }
            val expected = group.options.single { it.id == requested }.change
            compose.runOnIdle {
                assertEquals(index + 1, writes.size)
                assertEquals(expected, writes.last())
            }
        }
    }

    private data class AppearanceChoice(
        val prefix: String,
        val old: String,
        val requested: String,
        val canonical: AppearancePreferences,
    )

    @Test
    fun keyboard_navigation_from_the_fallback_supersedes_option_recovery() {
        val initial = AppearancePreferences()
        val state = mutableStateOf(SettingsState(initial, AppLanguage.SYSTEM))
        val commands = mutableListOf<SettingsChange>()
        compose.setContent {
            LifeTracingAppearance(initial) {
                SettingsScreen(
                    state.value,
                    { change ->
                        commands += change
                        state.value = state.value.copy(pending = change)
                    },
                    { error("unexpected mutation retry") },
                    { error("unexpected read retry") },
                    { error("unexpected back navigation") },
                    { error("unexpected archive navigation") },
                    readCurrent = { state.value },
                )
            }
        }
        keyboard.enter()
        val dark = compose.onNodeWithTag("settings-theme-DARK").performScrollTo()
        dark.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        dark.performKeyInput { pressKey(Key.Enter) }
        keyboard.awaitFocus(compose.onNodeWithTag(SETTINGS_HEADING))
        keyTab()
        compose.onNodeWithTag(SETTINGS_BACK).assertIsFocused()
        compose.runOnIdle {
            state.value = SettingsState(initial.copy(themeMode = ThemeMode.DARK), AppLanguage.SYSTEM)
        }
        dark.assertIsSelected().assertIsEnabled()
        compose.onNodeWithTag(SETTINGS_BACK).assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(SettingsChange.Theme(ThemeMode.DARK)), commands) }
    }

    @Test
    @Suppress("LongMethod")
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
        val finishRetry = CompletableDeferred<Unit>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember {
                    SettingsController(
                        values,
                        {
                            if (fail) throw IOException("injected write failure")
                            finishRetry.await()
                            values.value = initial.copy(themeMode = (it as SettingsChange.Theme).value)
                        },
                        { AppLanguage.SYSTEM },
                        {},
                        scope,
                    )
                }
            val state by controller.state.collectAsState()
            LifeTracingAppearance(initial) {
                SettingsScreen(
                    state,
                    controller::change,
                    controller::retryMutation,
                    controller::retryRead,
                    {},
                    readCurrent = { controller.state.value },
                )
            }
        }
        keyboard.enter()
        val light = compose.onNodeWithTag("settings-theme-LIGHT").performScrollTo()
        light.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        light.performKeyInput { pressKey(Key.Enter) }
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
        assertMutationStatus(R.string.settings_write_failure, themeDescription(R.string.settings_light))
        val retry = compose.onNodeWithTag(SETTINGS_RETRY)
        keyboard.awaitFocus(retry)
        val failedRetry = nativeClick("settings-retry")
        compose.runOnIdle { assertTrue(failedRetry()) }
        val replacementRetry = nativeClick("settings-retry")
        compose.runOnIdle {
            fail = false
            assertFalse(failedRetry())
        }
        retry.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertMutationStatus(R.string.settings_retrying, themeDescription(R.string.settings_light))
        compose.onNodeWithTag("settings-theme-DARK").assertIsSelected().assertIsNotEnabled()
        keyboard.awaitFocus(compose.onNodeWithTag(SETTINGS_HEADING))
        compose.runOnIdle {
            assertFalse(replacementRetry())
            finishRetry.complete(Unit)
        }
        compose
            .onNodeWithTag("settings-theme-LIGHT")
            .assertIsSelected()
        keyboard.awaitFocus(light)
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
    fun language_failure_retries_the_same_request_and_waits_for_the_platform_observation() {
        val initial = AppearancePreferences()
        var controller: SettingsController? = null
        var observed = AppLanguage.SYSTEM
        var fail = true
        val applications = mutableListOf<AppLanguage>()
        val writes = mutableListOf<SettingsChange.Appearance>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner =
                remember {
                    SettingsController(
                        MutableStateFlow(initial),
                        { writes += it },
                        { observed },
                        {
                            applications += it
                            if (fail) throw IOException("injected language failure")
                        },
                        scope,
                    ).also { controller = it }
                }
            val state by owner.state.collectAsState()
            LifeTracingAppearance(initial) {
                SettingsScreen(
                    state,
                    owner::change,
                    owner::retryMutation,
                    owner::retryRead,
                    {},
                    readCurrent = { owner.state.value },
                )
            }
        }
        val russian = compose.onNodeWithTag("settings-language-RUSSIAN")
        val description = choiceDescription(R.string.settings_language, R.string.settings_russian)
        russian.performScrollTo().performClick()
        assertMutationStatus(R.string.settings_write_failure, description)
        val retry = nativeClick(SETTINGS_RETRY)
        compose.runOnIdle {
            fail = false
            assertTrue(retry())
        }
        assertMutationStatus(R.string.settings_awaiting_observation, description)
        compose.onNodeWithTag("settings-language-SYSTEM").assertIsSelected().assertIsNotEnabled()
        russian.assertIsNotSelected().assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(retry())
            observed = AppLanguage.RUSSIAN
            requireNotNull(controller).refreshLanguage()
            assertEquals(listOf(AppLanguage.RUSSIAN, AppLanguage.RUSSIAN), applications)
            assertTrue(writes.isEmpty())
        }
        russian.assertIsSelected().assertIsEnabled()
        compose.onNodeWithTag("settings-mutation-status").assertDoesNotExist()
    }

    @Test
    @Suppress("LongMethod")
    fun english_and_russian_controls_reflow_at_maximum_app_scale_and_preserve_the_exact_option_sets() {
        val locale = mutableStateOf(Locale.ENGLISH)
        val changes = mutableListOf<SettingsChange>()
        val appearance = AppearancePreferences(ThemeMode.DARK, AccentPaletteId.SLATE, 120, 130)
        val state =
            SettingsState(
                appearance = appearance,
                language = AppLanguage.SYSTEM,
                failure = SettingsChange.Theme(ThemeMode.LIGHT),
            )
        compose.setContent {
            val base = LocalContext.current
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    setLocale(locale.value)
                    densityDpi = (densityDpi * 1.2f).toInt()
                    fontScale = 1.4f
                }
            val localized = base.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(localized.resources.displayMetrics.density, configuration.fontScale),
            ) {
                LifeTracingAppearance(appearance) {
                    SettingsScreen(
                        state,
                        { changes += it },
                        {},
                        {},
                        {},
                        readCurrent = { state },
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
            val theme = context.getString(R.string.settings_theme)
            val light = context.getString(R.string.settings_light)
            val description = context.getString(R.string.settings_option_description, theme, light)
            assertMutationStatus(R.string.settings_write_failure, description, context)
            val retryDescription = context.getString(R.string.settings_retry_description, description)
            compose.onNodeWithTag(SETTINGS_RETRY).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(retryDescription)),
            )
            val options = optionGroups()
            options.forEach { assertGroup(it, context) }
            compose
                .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assertCountEquals(24)
            val actions =
                options.flatMap { group ->
                    group.options.map { "${group.prefix}-${it.id}" to it.change }
                }
            val traversal = listOf(SETTINGS_BACK, SETTINGS_ARCHIVE, SETTINGS_RETRY) + actions.map { it.first }
            assertKeyboardTraversal(traversal)
            compose.runOnIdle { assertTrue(changes.isEmpty()) }
            actions.forEach { (tag, change) ->
                val action = nativeClick(tag)
                compose.runOnIdle {
                    assertTrue(action())
                    assertEquals(change, changes.last())
                }
            }
            compose.runOnIdle {
                assertEquals(24, changes.size)
                changes.clear()
            }
        }
    }

    @Test
    @Suppress("LongMethod")
    fun loading_and_read_failure_use_resources_and_offer_reload() {
        val finishInitialRead = CompletableDeferred<Unit>()
        val finishRetry = CompletableDeferred<Unit>()
        val writes = mutableListOf<SettingsChange>()
        var appearanceReads = 0
        var language = AppLanguage.SYSTEM
        var failLanguageRead = false
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember {
                    SettingsController(
                        flow {
                            if (++appearanceReads == 1) {
                                finishInitialRead.await()
                                throw IOException("appearance unreadable")
                            }
                            finishRetry.await()
                            emit(AppearancePreferences())
                        },
                        { writes += it },
                        {
                            if (failLanguageRead) throw IOException("language unreadable")
                            language
                        },
                        { writes += SettingsChange.Language(it) },
                        scope,
                    )
                }
            val state by controller.state.collectAsState()
            LifeTracingAppearance(AppearancePreferences()) {
                SettingsScreen(
                    state,
                    controller::change,
                    controller::retryMutation,
                    controller::retryRead,
                    {},
                    readCurrent = { controller.state.value },
                )
            }
        }
        assertReadStatus(R.string.settings_loading)
        compose.runOnIdle { finishInitialRead.complete(Unit) }
        assertReadStatus(R.string.settings_appearance_read_failure)
        val appearanceReload = nativeClick(SETTINGS_RELOAD)
        compose.runOnIdle {
            failLanguageRead = true
            assertTrue(appearanceReload())
        }
        assertReadStatus(R.string.settings_language_read_failure)
        assertKeyboardTraversal(listOf(SETTINGS_BACK, SETTINGS_ARCHIVE, SETTINGS_RELOAD))
        // Reverse traversal leaves focus on Back. Reach Reload again using hardware keys.
        keyTab()
        keyTab()
        val languageReload = nativeClick(SETTINGS_RELOAD)
        compose.runOnIdle {
            assertFalse(appearanceReload())
            failLanguageRead = false
            language = AppLanguage.RUSSIAN
        }
        compose.onNodeWithTag(SETTINGS_RELOAD).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertReadStatus(R.string.settings_loading)
        keyboard.awaitFocus(compose.onNodeWithTag(SETTINGS_HEADING))
        compose.runOnIdle {
            assertFalse(languageReload())
            finishRetry.complete(Unit)
        }
        compose.onNodeWithTag("settings-language-RUSSIAN").assertIsSelected()
        compose.onNodeWithTag("settings-read-status").assertDoesNotExist()
        compose.onNodeWithTag(SETTINGS_RELOAD).assertDoesNotExist()
        keyboard.awaitFocus(compose.onNodeWithTag(SETTINGS_HEADING))
        compose.runOnIdle {
            assertTrue(writes.isEmpty())
            assertEquals(3, appearanceReads)
        }
    }

    private fun assertReadStatus(resource: Int) {
        compose
            .onNodeWithTag("settings-read-status")
            .assertTextEquals(compose.activity.getString(resource))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun assertMutationStatus(
        resource: Int,
        choice: String,
        context: Context = compose.activity,
    ) {
        compose
            .onNodeWithTag("settings-mutation-status")
            .assertTextEquals(context.getString(resource, choice))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun themeDescription(option: Int): String = choiceDescription(R.string.settings_theme, option)

    private fun choiceDescription(
        title: Int,
        option: Int,
    ): String =
        compose.activity.run {
            getString(R.string.settings_option_description, getString(title), getString(option))
        }

    private fun assertKeyboardTraversal(tags: List<String>) {
        keyboard.enter()
        val start = compose.onNodeWithTag(tags.first()).performScrollTo()
        start.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        tags.drop(1).forEach { tag ->
            keyTab()
            compose.onNodeWithTag(tag).assertIsFocused()
        }
        compose.onNodeWithTag(tags.last()).assertIsDisplayed()
        tags.asReversed().drop(1).forEach { tag ->
            keyTab(reverse = true)
            compose.onNodeWithTag(tag).assertIsFocused()
        }
    }

    private fun keyTab(reverse: Boolean = false) {
        compose.onRoot().performKeyInput {
            if (reverse) keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            if (reverse) keyUp(Key.ShiftLeft)
        }
    }

    private fun assertGroup(
        group: OptionGroup,
        context: Context,
    ) {
        val groupNode = hasTestTag("${group.prefix}-group")
        val descendant = hasAnyAncestor(groupNode)
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val title = context.getString(group.title)
        compose
            .onNode(groupNode)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNode(descendant and hasText(title)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onAllNodes(descendant and radio).assertCountEquals(group.options.size)
        val focusable = SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)
        compose.onAllNodes(descendant and focusable).assertCountEquals(group.options.size)
        group.options.forEach { option ->
            val label =
                if (option.label == R.string.settings_percent) {
                    context.getString(option.label, option.id.toInt())
                } else {
                    context.getString(option.label)
                }
            val description = context.getString(R.string.settings_option_description, title, label)
            val node =
                compose
                    .onNode(descendant and hasTestTag("${group.prefix}-${option.id}"))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description)))
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
                    .assertHasClickAction()
                    .assertIsEnabled()
            if (option.id == group.selected) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }

    private data class Option(
        val id: String,
        val label: Int,
        val change: SettingsChange,
    )

    private data class OptionGroup(
        val prefix: String,
        val title: Int,
        val selected: String,
        val options: List<Option>,
    )

    private fun optionGroups(): List<OptionGroup> =
        listOf(
            OptionGroup(
                "settings-theme",
                R.string.settings_theme,
                "DARK",
                listOf(
                    Option("SYSTEM", R.string.settings_system, SettingsChange.Theme(ThemeMode.SYSTEM)),
                    Option("LIGHT", R.string.settings_light, SettingsChange.Theme(ThemeMode.LIGHT)),
                    Option("DARK", R.string.settings_dark, SettingsChange.Theme(ThemeMode.DARK)),
                ),
            ),
            OptionGroup(
                "settings-accent",
                R.string.settings_accent,
                "SLATE",
                listOf(
                    Option("DEFAULT", R.string.settings_accent_default, SettingsChange.Accent(AccentPaletteId.DEFAULT)),
                    Option("SLATE", R.string.settings_accent_slate, SettingsChange.Accent(AccentPaletteId.SLATE)),
                ),
            ),
            OptionGroup(
                "settings-language",
                R.string.settings_language,
                "SYSTEM",
                listOf(
                    Option("SYSTEM", R.string.settings_system, SettingsChange.Language(AppLanguage.SYSTEM)),
                    Option("ENGLISH", R.string.settings_english, SettingsChange.Language(AppLanguage.ENGLISH)),
                    Option("RUSSIAN", R.string.settings_russian, SettingsChange.Language(AppLanguage.RUSSIAN)),
                ),
            ),
            OptionGroup(
                "settings-interface",
                R.string.settings_interface_scale,
                "120",
                (90..120 step 5).map { percent ->
                    Option(percent.toString(), R.string.settings_percent, SettingsChange.InterfaceScale(percent))
                },
            ),
            OptionGroup(
                "settings-text",
                R.string.settings_text_scale,
                "130",
                (90..130 step 5).map { percent ->
                    Option(percent.toString(), R.string.settings_percent, SettingsChange.TextScale(percent))
                },
            ),
        )

    private fun nativeClick(tag: String): () -> Boolean {
        val node = compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode()
        return requireNotNull(node.config[SemanticsActions.OnClick].action)
    }
}
