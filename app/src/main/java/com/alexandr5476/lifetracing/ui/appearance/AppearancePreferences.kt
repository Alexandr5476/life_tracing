package com.alexandr5476.lifetracing.ui.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val interfaceScalePercent: Int = AppearanceScalePolicy.DEFAULT_PERCENT,
    val textScalePercent: Int = AppearanceScalePolicy.DEFAULT_PERCENT,
) {
    init {
        require(interfaceScalePercent in AppearanceScalePolicy.interfacePercentages)
        require(textScalePercent in AppearanceScalePolicy.textPercentages)
    }
}

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

class AppearancePreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.appearanceDataStore)

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

    suspend fun setInterfaceScalePercent(percent: Int) {
        require(percent in AppearanceScalePolicy.interfacePercentages)
        dataStore.edit { preferences -> preferences[INTERFACE_SCALE_PERCENT] = percent }
    }

    suspend fun setTextScalePercent(percent: Int) {
        require(percent in AppearanceScalePolicy.textPercentages)
        dataStore.edit { preferences -> preferences[TEXT_SCALE_PERCENT] = percent }
    }

    private fun toAppearancePreferences(preferences: Preferences): AppearancePreferences {
        val stored = preferences.asMap()
        return AppearancePreferences(
            themeMode = ThemeMode.fromStorage(preferences[THEME_MODE]),
            accentPaletteId = AccentPaletteId.fromStorage(preferences[ACCENT_PALETTE_ID]),
            interfaceScalePercent = AppearanceScalePolicy.interfaceFromStorage(stored[INTERFACE_SCALE_PERCENT] as? Int),
            textScalePercent = AppearanceScalePolicy.textFromStorage(stored[TEXT_SCALE_PERCENT] as? Int),
        )
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_PALETTE_ID = stringPreferencesKey("accent_palette_id")
        val INTERFACE_SCALE_PERCENT = intPreferencesKey("interface_scale_percent")
        val TEXT_SCALE_PERCENT = intPreferencesKey("text_scale_percent")
    }
}
