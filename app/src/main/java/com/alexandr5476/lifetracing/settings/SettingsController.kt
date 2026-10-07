package com.alexandr5476.lifetracing.settings

import com.alexandr5476.lifetracing.ui.appearance.AppLanguage
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferencesRepository
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface SettingsChange {
    sealed interface Appearance : SettingsChange

    data class Theme(
        val value: ThemeMode,
    ) : Appearance

    data class Accent(
        val value: AccentPaletteId,
    ) : Appearance

    data class InterfaceScale(
        val value: Int,
    ) : Appearance

    data class TextScale(
        val value: Int,
    ) : Appearance

    data class Language(
        val value: AppLanguage,
    ) : SettingsChange
}

internal fun interface SettingsPreferenceWriter {
    suspend fun write(change: SettingsChange.Appearance)
}

internal class RepositorySettingsWriter(
    private val repository: AppearancePreferencesRepository,
) : SettingsPreferenceWriter {
    override suspend fun write(change: SettingsChange.Appearance) {
        when (change) {
            is SettingsChange.Theme -> repository.setThemeMode(change.value)
            is SettingsChange.Accent -> repository.setAccentPaletteId(change.value)
            is SettingsChange.InterfaceScale -> repository.setInterfaceScalePercent(change.value)
            is SettingsChange.TextScale -> repository.setTextScalePercent(change.value)
        }
    }
}

/** Process-owned ordering; controller recreation never replays an accepted mutation. */
internal class SettingsAppearanceMutations(
    private val scope: CoroutineScope,
) {
    private var tail: Job? = null

    fun launchIfAccepted(
        accept: () -> Boolean,
        mutation: suspend () -> Unit,
    ) {
        val job =
            synchronized(this) {
                if (!accept()) return
                val previous = tail
                scope
                    .launch(start = CoroutineStart.LAZY) {
                        previous?.join()
                        mutation()
                    }.also { tail = it }
            }
        job.invokeOnCompletion {
            synchronized(this) {
                if (tail === job) tail = null
            }
        }
        // join starts a lazy predecessor even if the dispatcher starts a later job first.
        job.start()
    }
}

internal data class SettingsState(
    val appearance: AppearancePreferences? = null,
    val language: AppLanguage? = null,
    val appearanceReadFailed: Boolean = false,
    val languageReadFailed: Boolean = false,
    val pending: SettingsChange? = null,
    val writeCompleted: Boolean = false,
    val failure: SettingsChange? = null,
) {
    val ready: Boolean
        get() = appearance != null && language != null && !appearanceReadFailed && !languageReadFailed
}

/** Transient requests never supply selected values; only repository emissions and platform reads do. */
internal class SettingsController(
    private val preferences: Flow<AppearancePreferences>,
    private val writer: SettingsPreferenceWriter,
    private val readLanguage: () -> AppLanguage,
    private val applyLanguage: (AppLanguage) -> Unit,
    private val scope: CoroutineScope,
    private val appearanceMutations: SettingsAppearanceMutations = SettingsAppearanceMutations(scope),
) {
    private val mutableState = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = mutableState
    private var observation: Job? = null

    init {
        retryRead()
    }

    fun retryRead() {
        observation?.cancel()
        refreshLanguage()
        observation =
            scope.launch {
                try {
                    preferences.collect { appearance ->
                        mutableState.update {
                            settle(it.copy(appearance = appearance, appearanceReadFailed = false))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(appearanceReadFailed = true) }
                }
            }
    }

    fun refreshLanguage() {
        try {
            val language = readLanguage()
            mutableState.update { settle(it.copy(language = language, languageReadFailed = false)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(languageReadFailed = true) }
        }
    }

    fun change(change: SettingsChange) {
        if (change is SettingsChange.Appearance) {
            appearanceMutations.launchIfAccepted({ accept(change) }) { executeChange(change) }
        } else if (accept(change)) {
            scope.launch { executeChange(change) }
        }
    }

    private fun accept(change: SettingsChange): Boolean {
        val current = mutableState.value
        if (!current.ready || current.pending != null) return false
        val requested = current.copy(pending = change, writeCompleted = false, failure = null)
        return mutableState.compareAndSet(current, requested)
    }

    private suspend fun executeChange(change: SettingsChange) {
        try {
            when (change) {
                is SettingsChange.Appearance -> writer.write(change)
                is SettingsChange.Language -> {
                    applyLanguage(change.value)
                    refreshLanguage()
                }
            }
            mutableState.update { settle(it.copy(writeCompleted = true)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(pending = null, writeCompleted = false, failure = change) }
        }
    }

    fun retryMutation() {
        mutableState.value.failure?.let(::change)
    }

    private fun settle(state: SettingsState): SettingsState =
        if (state.writeCompleted && state.pending?.matches(state) == true) {
            state.copy(pending = null, writeCompleted = false)
        } else {
            state
        }
}

private fun SettingsChange.matches(state: SettingsState): Boolean =
    when (this) {
        is SettingsChange.Theme -> state.appearance?.themeMode == value
        is SettingsChange.Accent -> state.appearance?.accentPaletteId == value
        is SettingsChange.InterfaceScale -> state.appearance?.interfaceScalePercent == value
        is SettingsChange.TextScale -> state.appearance?.textScalePercent == value
        is SettingsChange.Language -> state.language == value
    }
