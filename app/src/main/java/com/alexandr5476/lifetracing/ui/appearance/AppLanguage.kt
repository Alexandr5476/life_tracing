package com.alexandr5476.lifetracing.ui.appearance

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguage(
    val languageTag: String,
) {
    SYSTEM(""),
    ENGLISH("en"),
    RUSSIAN("ru"),
    ;

    companion object {
        fun fromLanguageTag(value: String?): AppLanguage = entries.firstOrNull { it.languageTag == value } ?: SYSTEM
    }
}

/**
 * AppCompat persists per-app locales itself, so language is intentionally not duplicated in DataStore.
 */
object AppLanguageController {
    fun current(): AppLanguage {
        val locales = AppCompatDelegate.getApplicationLocales()
        val language = AppLanguage.fromLanguageTag(locales.get(0)?.language)
        // Unsupported leading locales must relinquish the platform override as well as select SYSTEM.
        if (language == AppLanguage.SYSTEM && !locales.isEmpty) {
            apply(AppLanguage.SYSTEM)
        }
        return language
    }

    fun apply(language: AppLanguage) {
        val locales =
            if (language == AppLanguage.SYSTEM) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.languageTag)
            }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
