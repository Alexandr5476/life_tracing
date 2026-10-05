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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsPersistenceTest {
    @TempDir
    lateinit var directory: File

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
}
