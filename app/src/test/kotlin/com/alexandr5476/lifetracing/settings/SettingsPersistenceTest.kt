package com.alexandr5476.lifetracing.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import com.alexandr5476.lifetracing.ui.appearance.AppLanguage
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferencesRepository
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

internal class SettingsPersistenceTest {
    @TempDir
    lateinit var directory: File

    @ParameterizedTest
    @MethodSource("appearanceChanges")
    @Suppress("LongMethod")
    fun newer_intent_survives_an_older_suspended_write_across_controller_recreation(
        older: SettingsChange.Appearance,
        newer: SettingsChange.Appearance,
        expected: AppearancePreferences,
    ) = runBlocking {
        val requests = mutableListOf<SettingsChange.Appearance>()
        withStore { store, processScope ->
            val appearanceMutations = SettingsAppearanceMutations(processScope)
            val repository = AppearancePreferencesRepository(store)
            val screen = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val recreatedScreen = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val releaseOlder = CompletableDeferred<Unit>()
            val releaseNewer = CompletableDeferred<Unit>()
            val olderFinished = CompletableDeferred<Unit>()
            val newerFinished = CompletableDeferred<Unit>()
            val writer =
                SettingsPreferenceWriter { change ->
                    requests += change
                    if (change == older) releaseOlder.await() else releaseNewer.await()
                    RepositorySettingsWriter(repository).write(change)
                    if (change == older) olderFinished.complete(Unit) else newerFinished.complete(Unit)
                }

            fun controller(scope: CoroutineScope) =
                SettingsController(
                    repository.preferences,
                    writer,
                    { AppLanguage.SYSTEM },
                    {},
                    scope,
                    appearanceMutations = appearanceMutations,
                )
            try {
                val original = controller(screen)
                withTimeout(5_000) { original.state.first { it.ready } }
                original.change(older)
                // Unconfined starts the accepted request inline and suspends at the explicit gate.
                assertEquals(listOf(older), requests)
                screen.cancel()
                val recreated = controller(recreatedScreen)
                withTimeout(5_000) { recreated.state.first { it.ready } }
                assertEquals(null, recreated.state.value.pending)
                assertEquals(listOf(older), requests)
                recreated.change(newer)
                assertEquals(newer, recreated.state.value.pending)
                releaseNewer.complete(Unit)
                // If the newer write bypasses the older one, force it to commit first, then let
                // the stale older write commit last. A serialized writer instead keeps it queued.
                if (newer in requests) withTimeout(5_000) { newerFinished.await() }
                releaseOlder.complete(Unit)
                withTimeout(5_000) {
                    olderFinished.await()
                    newerFinished.await()
                    recreated.state.first { it.pending == null }
                }
                assertEquals(listOf(older, newer), requests)
                assertEquals(expected, AppearancePreferencesRepository(store).preferences.first())
            } finally {
                releaseOlder.complete(Unit)
                releaseNewer.complete(Unit)
                screen.cancel()
                recreatedScreen.cancel()
            }
        }
        withStore { store, processScope ->
            val repository = AppearancePreferencesRepository(store)
            val fresh =
                SettingsController(
                    repository.preferences,
                    { requests += it },
                    { AppLanguage.SYSTEM },
                    {},
                    processScope,
                )
            withTimeout(5_000) { fresh.state.first { it.ready } }
            assertEquals(expected, repository.preferences.first())
            assertEquals(listOf(older, newer), requests)
        }
    }

    @Test
    @Suppress("LongMethod")
    fun accepted_write_survives_immediate_screen_disposal_and_recreation_without_replay() =
        runBlocking {
            val initial = AppearancePreferences(ThemeMode.LIGHT, AccentPaletteId.SLATE, 115, 125)
            val expected = initial.copy(themeMode = ThemeMode.DARK)
            val requests = mutableListOf<SettingsChange.Appearance>()
            withStore { store, processScope ->
                val appearanceMutations = SettingsAppearanceMutations(processScope)
                val repository = AppearancePreferencesRepository(store)
                repository.setThemeMode(initial.themeMode)
                repository.setAccentPaletteId(initial.accentPaletteId)
                repository.setInterfaceScalePercent(initial.interfaceScalePercent)
                repository.setTextScalePercent(initial.textScalePercent)
                val screen = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val recreatedScreen = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                val writer =
                    SettingsPreferenceWriter { change ->
                        requests += change
                        entered.complete(Unit)
                        try {
                            release.await()
                            RepositorySettingsWriter(repository).write(change)
                        } finally {
                            finished.complete(Unit)
                        }
                    }
                try {
                    val controller =
                        SettingsController(
                            repository.preferences,
                            writer,
                            { AppLanguage.SYSTEM },
                            {},
                            screen,
                            appearanceMutations = appearanceMutations,
                        )
                    withTimeout(5_000) { controller.state.first { it.ready } }
                    controller.change(SettingsChange.Theme(ThemeMode.DARK))
                    withTimeout(5_000) { entered.await() }
                    assertEquals(initial, controller.state.value.appearance)
                    screen.cancel()
                    val recreated =
                        SettingsController(
                            repository.preferences,
                            writer,
                            { AppLanguage.SYSTEM },
                            {},
                            recreatedScreen,
                            appearanceMutations = appearanceMutations,
                        )
                    withTimeout(5_000) { recreated.state.first { it.ready } }
                    assertEquals(initial, recreated.state.value.appearance)
                    release.complete(Unit)
                    withTimeout(5_000) { finished.await() }
                    assertEquals(expected, AppearancePreferencesRepository(store).preferences.first())
                    assertEquals(listOf(SettingsChange.Theme(ThemeMode.DARK)), requests)
                } finally {
                    release.complete(Unit)
                    screen.cancel()
                    recreatedScreen.cancel()
                }
            }
            withStore { store, _ ->
                assertEquals(expected, AppearancePreferencesRepository(store).preferences.first())
            }
        }

    @Test
    fun focused_settings_writes_are_observed_preserve_other_fields_and_reload_without_a_locale_key() =
        runBlocking {
            val expected =
                AppearancePreferences(
                    themeMode = ThemeMode.DARK,
                    accentPaletteId = AccentPaletteId.SLATE,
                    interfaceScalePercent = 115,
                    textScalePercent = 125,
                )
            withStore { store, scope ->
                val repository = AppearancePreferencesRepository(store)
                var language = AppLanguage.SYSTEM
                val controller =
                    SettingsController(
                        repository.preferences,
                        RepositorySettingsWriter(repository),
                        { language },
                        { language = it },
                        scope,
                    )
                withTimeout(5_000) { controller.state.first { it.ready } }
                val cases =
                    listOf(
                        SettingsChange.Theme(ThemeMode.DARK) to AppearancePreferences(themeMode = ThemeMode.DARK),
                        SettingsChange.Accent(AccentPaletteId.SLATE) to
                            AppearancePreferences(themeMode = ThemeMode.DARK, accentPaletteId = AccentPaletteId.SLATE),
                        SettingsChange.InterfaceScale(115) to expected.copy(textScalePercent = 100),
                        SettingsChange.TextScale(125) to expected,
                    )
                cases.forEach { (change, canonical) ->
                    controller.change(change)
                    val observed =
                        withTimeout(5_000) {
                            controller.state.first { it.pending == null && it.appearance == canonical }
                        }
                    assertEquals(canonical, observed.appearance)
                    assertEquals(canonical, repository.preferences.first())
                }
                val appearanceStorage = store.data.first().asMap()
                controller.change(SettingsChange.Language(AppLanguage.RUSSIAN))
                withTimeout(5_000) {
                    controller.state.first { it.pending == null && it.language == AppLanguage.RUSSIAN }
                }
                assertEquals(appearanceStorage, store.data.first().asMap())
                assertEquals(
                    setOf("theme_mode", "accent_palette_id", "interface_scale_percent", "text_scale_percent"),
                    appearanceStorage.keys.map { it.name }.toSet(),
                )
            }
            withStore { store, _ ->
                assertEquals(expected, AppearancePreferencesRepository(store).preferences.first())
            }
        }

    private suspend fun withStore(block: suspend (DataStore<Preferences>, CoroutineScope) -> Unit) {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Unconfined + job)
        val store =
            PreferenceDataStoreFactory.create(
                scope = scope,
                storage =
                    OkioStorage(
                        fileSystem = FileSystem.SYSTEM,
                        serializer = PreferencesSerializer,
                        producePath = { File(directory, "settings.preferences_pb").toOkioPath() },
                    ),
            )
        try {
            block(store, scope)
        } finally {
            job.cancelAndJoin()
        }
    }

    companion object {
        @JvmStatic
        fun appearanceChanges(): List<Arguments> =
            listOf(
                Arguments.of(
                    SettingsChange.Theme(ThemeMode.DARK),
                    SettingsChange.Theme(ThemeMode.LIGHT),
                    AppearancePreferences(themeMode = ThemeMode.LIGHT),
                ),
                Arguments.of(
                    SettingsChange.Accent(AccentPaletteId.SLATE),
                    SettingsChange.Accent(AccentPaletteId.DEFAULT),
                    AppearancePreferences(),
                ),
                Arguments.of(
                    SettingsChange.InterfaceScale(115),
                    SettingsChange.InterfaceScale(105),
                    AppearancePreferences(interfaceScalePercent = 105),
                ),
                Arguments.of(
                    SettingsChange.TextScale(125),
                    SettingsChange.TextScale(110),
                    AppearancePreferences(textScalePercent = 110),
                ),
            )
    }
}
