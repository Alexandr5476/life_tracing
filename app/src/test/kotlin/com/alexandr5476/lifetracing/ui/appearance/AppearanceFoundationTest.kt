package com.alexandr5476.lifetracing.ui.appearance

import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import com.alexandr5476.lifetracing.ui.theme.toAccentPalette
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppearanceFoundationTest {
    @Test
    fun system_theme_follows_system_and_explicit_modes_override_it() {
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemIsDark = false))
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemIsDark = true))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemIsDark = true))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemIsDark = false))
    }

    @Test
    fun persisted_appearance_values_round_trip_and_unknown_values_fall_back() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorage(ThemeMode.DARK.name))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("future-theme"))
        assertEquals(AccentPaletteId.SLATE, AccentPaletteId.fromStorage(AccentPaletteId.SLATE.name))
        assertEquals(AccentPaletteId.DEFAULT, AccentPaletteId.fromStorage("future-accent"))
    }

    @Test
    fun accent_presets_supply_distinct_semantic_primary_colors() {
        assertNotEquals(
            AccentPaletteId.DEFAULT
                .toAccentPalette()
                .light
                .primary,
            AccentPaletteId.SLATE
                .toAccentPalette()
                .light
                .primary,
        )
    }

    @Test
    fun app_language_maps_supported_tags_and_defaults_to_system() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag("en"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromLanguageTag("ru"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag("future-language"))
    }
}
