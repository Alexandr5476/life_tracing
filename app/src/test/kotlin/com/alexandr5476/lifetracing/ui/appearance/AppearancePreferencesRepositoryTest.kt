package com.alexandr5476.lifetracing.ui.appearance

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppearancePreferencesRepositoryTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun persisted_scales_and_existing_appearance_reload_from_a_fresh_store() =
        runBlocking {
            val expected =
                AppearancePreferences(
                    themeMode = ThemeMode.DARK,
                    accentPaletteId = AccentPaletteId.SLATE,
                    interfaceScalePercent = 115,
                    textScalePercent = 125,
                )
            withStore { store ->
                val repository = AppearancePreferencesRepository(store)
                repository.setThemeMode(expected.themeMode)
                repository.setAccentPaletteId(expected.accentPaletteId)
                repository.setInterfaceScalePercent(expected.interfaceScalePercent)
                repository.setTextScalePercent(expected.textScalePercent)
                assertEquals(expected, repository.preferences.first())
            }
            // The previous DataStore's scope has finished: this read opens the same persisted file anew.
            withStore { store ->
                assertEquals(expected, AppearancePreferencesRepository(store).preferences.first())
            }
        }

    @Test
    fun missing_and_unsupported_stored_values_use_safe_defaults_independently() =
        runBlocking {
            withStore { store ->
                val repository = AppearancePreferencesRepository(store)
                assertEquals(AppearancePreferences(), repository.preferences.first())
                listOf(85 to 135, 91 to 99, 125 to 131).forEach { (interfacePercent, textPercent) ->
                    store.edit {
                        it[intPreferencesKey("interface_scale_percent")] = interfacePercent
                        it[intPreferencesKey("text_scale_percent")] = textPercent
                        it[stringPreferencesKey("theme_mode")] = "future-theme"
                        it[stringPreferencesKey("accent_palette_id")] = "future-accent"
                    }
                    assertEquals(AppearancePreferences(), repository.preferences.first())
                }
                store.edit {
                    it[intPreferencesKey("interface_scale_percent")] = 110
                    it[intPreferencesKey("text_scale_percent")] = 99
                }
                assertEquals(
                    AppearancePreferences(interfaceScalePercent = 110),
                    repository.preferences.first(),
                )
                store.edit {
                    it[intPreferencesKey("interface_scale_percent")] = 99
                    it[intPreferencesKey("text_scale_percent")] = 130
                }
                assertEquals(AppearancePreferences(textScalePercent = 130), repository.preferences.first())
            }
        }

    @Test
    fun unsupported_stored_scale_types_fall_back_without_changing_valid_theme_or_accent() =
        runBlocking {
            withStore { store ->
                val repository = AppearancePreferencesRepository(store)
                repository.setThemeMode(ThemeMode.LIGHT)
                repository.setAccentPaletteId(AccentPaletteId.SLATE)
                store.edit {
                    it[stringPreferencesKey("interface_scale_percent")] = "110"
                    it[doublePreferencesKey("text_scale_percent")] = 125.0
                }
                assertEquals(
                    AppearancePreferences(themeMode = ThemeMode.LIGHT, accentPaletteId = AccentPaletteId.SLATE),
                    repository.preferences.first(),
                )
            }
        }

    @Test
    fun wrong_type_theme_falls_back_independently_and_can_be_overwritten_and_reloaded() =
        malformedStringPreferenceCanRecover("theme_mode")

    @Test
    fun wrong_type_accent_falls_back_independently_and_can_be_overwritten_and_reloaded() =
        malformedStringPreferenceCanRecover("accent_palette_id")

    private fun malformedStringPreferenceCanRecover(key: String) =
        runBlocking {
            val legal = AppearancePreferences(ThemeMode.DARK, AccentPaletteId.SLATE, 115, 125)
            withStore { store ->
                val repository = AppearancePreferencesRepository(store)
                repository.setThemeMode(legal.themeMode)
                repository.setAccentPaletteId(legal.accentPaletteId)
                repository.setInterfaceScalePercent(legal.interfaceScalePercent)
                repository.setTextScalePercent(legal.textScalePercent)
                store.edit { it[intPreferencesKey(key)] = 42 }
            }
            withStore { store ->
                val repository = AppearancePreferencesRepository(store)
                val fallback =
                    if (key == "theme_mode") {
                        legal.copy(themeMode = ThemeMode.SYSTEM)
                    } else {
                        legal.copy(accentPaletteId = AccentPaletteId.DEFAULT)
                    }
                assertEquals(fallback, repository.preferences.first())
                if (key == "theme_mode") {
                    repository.setThemeMode(legal.themeMode)
                } else {
                    repository.setAccentPaletteId(legal.accentPaletteId)
                }
                assertEquals(legal, repository.preferences.first())
            }
            withStore { store ->
                assertEquals(legal, AppearancePreferencesRepository(store).preferences.first())
            }
        }

    @Test
    fun writers_accept_every_legal_value_and_reject_invalid_values_without_changing_preferences() =
        runBlocking {
            withStore { store ->
                val repository = AppearancePreferencesRepository(store)
                listOf(90, 95, 100, 105, 110, 115, 120).forEach { percent ->
                    repository.setInterfaceScalePercent(percent)
                    assertEquals(percent, repository.preferences.first().interfaceScalePercent)
                }
                listOf(90, 95, 100, 105, 110, 115, 120, 125, 130).forEach { percent ->
                    repository.setTextScalePercent(percent)
                    assertEquals(percent, repository.preferences.first().textScalePercent)
                }
                val before = repository.preferences.first()
                listOf(85, 91, 125, Int.MAX_VALUE).forEach { percent ->
                    val failure = runCatching { repository.setInterfaceScalePercent(percent) }.exceptionOrNull()
                    assertTrue(failure is IllegalArgumentException)
                    assertEquals(before, repository.preferences.first())
                }
                listOf(85, 91, 135, Int.MAX_VALUE).forEach { percent ->
                    val failure = runCatching { repository.setTextScalePercent(percent) }.exceptionOrNull()
                    assertTrue(failure is IllegalArgumentException)
                    assertEquals(before, repository.preferences.first())
                }
            }
        }

    private suspend fun withStore(block: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + job),
                // Android's stub SDK selects File.renameTo, which cannot replace an existing file on Windows.
                // Use DataStore's host-compatible storage with the same real Preferences protobuf serializer.
                storage =
                    OkioStorage(
                        fileSystem = FileSystem.SYSTEM,
                        serializer = PreferencesSerializer,
                        producePath = { File(directory, "appearance.preferences_pb").toOkioPath() },
                    ),
            )
        try {
            block(store)
        } finally {
            job.cancelAndJoin()
        }
    }
}
