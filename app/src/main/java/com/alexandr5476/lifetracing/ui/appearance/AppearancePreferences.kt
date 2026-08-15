package com.alexandr5476.lifetracing.ui.appearance

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")

data class AppearancePreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentPaletteId: AccentPaletteId = AccentPaletteId.DEFAULT,
)

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromStorage(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

fun resolveDarkTheme(
    themeMode: ThemeMode,
    systemIsDark: Boolean,
): Boolean =
    when (themeMode) {
        ThemeMode.SYSTEM -> systemIsDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

class AppearancePreferencesRepository(
    context: Context,
) {
    private val dataStore = context.applicationContext.appearanceDataStore

    val preferences: Flow<AppearancePreferences> =
        dataStore.data
            .map(::toAppearancePreferences)
            .distinctUntilChanged()

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { preferences -> preferences[THEME_MODE] = themeMode.name }
    }

    suspend fun setAccentPaletteId(accentPaletteId: AccentPaletteId) {
        dataStore.edit { preferences -> preferences[ACCENT_PALETTE_ID] = accentPaletteId.name }
    }

    private fun toAppearancePreferences(preferences: Preferences) =
        AppearancePreferences(
            themeMode = ThemeMode.fromStorage(preferences[THEME_MODE]),
            accentPaletteId = AccentPaletteId.fromStorage(preferences[ACCENT_PALETTE_ID]),
        )

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_PALETTE_ID = stringPreferencesKey("accent_palette_id")
    }
}
