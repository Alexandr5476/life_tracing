package com.alexandr5476.lifetracing.settings

import com.alexandr5476.lifetracing.ui.appearance.AppLanguage
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.ThemeMode
import com.alexandr5476.lifetracing.ui.theme.AccentPaletteId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SettingsControllerTest {
    @Test
    fun a_completed_write_waits_for_canonical_observation_and_duplicate_taps_do_not_write() =
        runBlocking {
            val fixture = Fixture()
            val releaseWrite = CompletableDeferred<Unit>()
            fixture.write = { releaseWrite.await() }
            try {
                val change = SettingsChange.Theme(ThemeMode.DARK)
                fixture.controller.change(change)
                fixture.controller.change(SettingsChange.Accent(AccentPaletteId.SLATE))
                assertEquals(listOf(change), fixture.requests)
                assertEquals(fixture.initial, fixture.controller.state.value.appearance)
                releaseWrite.complete(Unit)
                fixture.controller.awaitState { it.writeCompleted }
                assertEquals(change, fixture.controller.state.value.pending)
                assertEquals(fixture.initial, fixture.controller.state.value.appearance)
                val observed = fixture.initial.copy(themeMode = ThemeMode.DARK)
                fixture.values.value = observed
                val settled = fixture.controller.awaitState { it.pending == null }
                assertEquals(observed, settled.appearance)
            } finally {
                fixture.scope.cancel()
            }
        }

    @Test
    fun failed_mutations_keep_all_canonical_fields_and_retry_only_the_failed_field() =
        runBlocking {
            val initial =
                AppearancePreferences(
                    themeMode = ThemeMode.DARK,
                    interfaceScalePercent = 115,
                    textScalePercent = 125,
                )
            val fixture = Fixture(initial)
            fixture.write = { throw IOException("write failed") }
            try {
                val change = SettingsChange.Accent(AccentPaletteId.SLATE)
                fixture.controller.change(change)
                val failed = fixture.controller.awaitState { it.failure != null }
                assertNull(failed.pending)
                assertEquals(initial, failed.appearance)
                assertEquals(change, failed.failure)
                fixture.write = { fixture.values.value = initial.copy(accentPaletteId = AccentPaletteId.SLATE) }
                fixture.controller.retryMutation()
                val settled = fixture.controller.awaitState { it.pending == null && it.failure == null }
                assertEquals(initial.copy(accentPaletteId = AccentPaletteId.SLATE), settled.appearance)
                assertEquals(listOf(change, change), fixture.requests)
            } finally {
                fixture.scope.cancel()
            }
        }

    @Test
    fun initial_loading_blocks_writes_and_read_failure_can_be_retried_without_a_write() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val releaseRead = CompletableDeferred<Unit>()
            var failRead = true
            val writes = mutableListOf<SettingsChange.Appearance>()
            val controller =
                SettingsController(
                    flow {
                        releaseRead.await()
                        if (failRead) throw IOException("read failed")
                        emit(AppearancePreferences(textScalePercent = 130))
                    },
                    { writes += it },
                    { AppLanguage.SYSTEM },
                    {},
                    scope,
                )
            try {
                assertFalse(controller.state.value.ready)
                controller.change(SettingsChange.TextScale(125))
                assertTrue(writes.isEmpty())
                releaseRead.complete(Unit)
                assertTrue(controller.awaitState { it.appearanceReadFailed }.appearanceReadFailed)
                failRead = false
                controller.retryRead()
                assertEquals(130, controller.awaitState { it.ready }.appearance?.textScalePercent)
                assertTrue(writes.isEmpty())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun locale_selection_waits_for_the_platform_and_recreation_does_not_replay_a_request() =
        runBlocking {
            val fixture = Fixture()
            val applications = mutableListOf<AppLanguage>()
            val controller =
                SettingsController(
                    fixture.values,
                    { fixture.requests += it },
                    { fixture.language },
                    { applications += it },
                    fixture.scope,
                )
            try {
                controller.change(SettingsChange.Language(AppLanguage.RUSSIAN))
                assertEquals(AppLanguage.SYSTEM, controller.state.value.language)
                assertTrue(controller.state.value.pending != null)
                fixture.language = AppLanguage.RUSSIAN
                controller.refreshLanguage()
                assertEquals(AppLanguage.RUSSIAN, controller.awaitState { it.pending == null }.language)
                val recreated =
                    SettingsController(
                        fixture.values,
                        { fixture.requests += it },
                        { fixture.language },
                        { applications += it },
                        fixture.scope,
                    )
                assertEquals(AppLanguage.RUSSIAN, recreated.awaitState { it.ready }.language)
                assertEquals(listOf(AppLanguage.RUSSIAN), applications)
                assertTrue(fixture.requests.isEmpty())
            } finally {
                fixture.scope.cancel()
            }
        }

    @Test
    fun locale_application_failure_is_retryable_and_does_not_write_appearance() =
        runBlocking {
            val fixture = Fixture()
            var fail = true
            val controller =
                SettingsController(
                    fixture.values,
                    { fixture.requests += it },
                    { fixture.language },
                    {
                        check(!fail) { "platform failure" }
                        fixture.language = it
                    },
                    fixture.scope,
                )
            try {
                controller.change(SettingsChange.Language(AppLanguage.ENGLISH))
                assertEquals(AppLanguage.SYSTEM, controller.awaitState { it.failure != null }.language)
                fail = false
                controller.retryMutation()
                assertEquals(AppLanguage.ENGLISH, controller.awaitState { it.pending == null }.language)
                assertTrue(fixture.requests.isEmpty())
                assertEquals(fixture.initial, controller.state.value.appearance)
            } finally {
                fixture.scope.cancel()
            }
        }

    private suspend fun SettingsController.awaitState(predicate: (SettingsState) -> Boolean): SettingsState =
        withTimeout(5_000) { state.first(predicate) }

    private class Fixture(
        val initial: AppearancePreferences = AppearancePreferences(),
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val values = MutableStateFlow(initial)
        val requests = mutableListOf<SettingsChange.Appearance>()
        var language = AppLanguage.SYSTEM
        var write: suspend (SettingsChange.Appearance) -> Unit = {}
        val controller =
            SettingsController(
                values,
                {
                    requests += it
                    write(it)
                },
                { language },
                { language = it },
                scope,
            )
    }
}
