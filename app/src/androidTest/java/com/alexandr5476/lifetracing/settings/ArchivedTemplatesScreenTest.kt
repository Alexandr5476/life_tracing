package com.alexandr5476.lifetracing.settings

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Locale

class ArchivedTemplatesScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Suppress("LongMethod")
    fun reload_retry_and_typed_restore_admit_only_current_canonical_actions() {
        val activity = LibraryTemplateId.Activity(ActivityTemplateId("same-id"))
        val sequence = LibraryTemplateId.Sequence(SequenceTemplateId("same-id"))
        val initial = ArchivedTemplatesState(listOf(row(activity), row(sequence)), loading = false)
        val state = mutableStateOf(initial)
        val visible = mutableStateOf(true)
        val mounted = mutableStateOf(true)
        val ownsRoute = { visible.value }
        val restores = mutableListOf<LibraryTemplateId>()
        var reloads = 0
        var backs = 0
        compose.setContent {
            if (mounted.value) {
                LifeTracingAppearance(AppearancePreferences()) {
                    ArchivedTemplatesScreen(
                        state.value,
                        {
                            restores += it
                            state.value = state.value.copy(pending = it)
                        },
                        {
                            reloads++
                            state.value = state.value.copy(loading = true, readFailed = false)
                        },
                        {
                            val id = requireNotNull(state.value.failedRestore)
                            restores += id
                            state.value = state.value.copy(pending = id, failedRestore = null)
                        },
                        { backs++ },
                        { state.value },
                        visible.value,
                        ownsRoute,
                    )
                }
            }
        }
        val activityTag = "archived-restore-${activity.archivedRowKey()}"
        val sequenceTag = "archived-restore-${sequence.archivedRowKey()}"
        val obsoletePublication = nativeClick(activityTag)
        compose.runOnIdle {
            state.value = initial.copy(items = listOf(row(activity), row(sequence)), readGeneration = 1)
            assertFalse(obsoletePublication())
        }
        val activityRestore = nativeClick(activityTag)
        compose.runOnIdle {
            assertTrue(activityRestore())
            assertFalse(activityRestore())
            assertEquals(listOf(activity, sequence), state.value.items?.map { it.id })
            state.value = state.value.copy(pending = null, readFailed = true)
            assertFalse(activityRestore())
        }
        val reload = nativeClick("archived-reload")
        compose.runOnIdle {
            assertTrue(reload())
            assertFalse(reload())
            assertFalse(activityRestore())
            state.value =
                ArchivedTemplatesState(
                    listOf(row(sequence)),
                    loading = false,
                    failedRestore = sequence,
                    readGeneration = 2,
                )
        }
        val obsoleteRetry = nativeClick("archived-retry")
        compose.runOnIdle {
            state.value = state.value.copy(failedRestore = null)
            assertFalse(obsoleteRetry())
            assertFalse(reload())
            assertFalse(activityRestore())
            state.value = state.value.copy(failedRestore = sequence, readGeneration = 3)
        }
        val retry = nativeClick("archived-retry")
        compose.runOnIdle {
            assertTrue(retry())
            assertFalse(retry())
            state.value = state.value.copy(pending = null, readGeneration = 4)
        }
        val back = nativeClick("archived-back")
        val sequenceRestore = nativeClick(sequenceTag)
        compose.runOnIdle {
            visible.value = false
            assertFalse(back())
            assertFalse(sequenceRestore())
        }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = false }
        compose.onNodeWithTag("archived-page").assertDoesNotExist()
        compose.runOnIdle {
            mounted.value = true
            visible.value = true
        }
        val currentBack = nativeClick("archived-back")
        compose.runOnIdle {
            assertFalse(back())
            assertFalse(sequenceRestore())
            assertTrue(currentBack())
            assertEquals(listOf(activity, sequence), restores)
            assertEquals(1, reloads)
            assertEquals(1, backs)
        }
        scrollTo(sequenceTag)
        val restoreButton = compose.onNodeWithTag(sequenceTag)
        restoreButton.performTouchInput { down(center) }
        compose.runOnIdle { visible.value = false }
        restoreButton.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(activity, sequence), restores)
            visible.value = true
        }
        restoreButton.performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(activity, sequence, sequence), restores) }
    }

    @Test
    fun english_and_russian_states_reflow_and_restore_is_the_only_row_action() {
        val locale = mutableStateOf(Locale.ENGLISH)
        val state = mutableStateOf(ArchivedTemplatesState())
        val activity = LibraryTemplateId.Activity(ActivityTemplateId("same-id"))
        val sequence = LibraryTemplateId.Sequence(SequenceTemplateId("same-id"))
        val rows = listOf(row(activity), row(sequence))
        val requests = mutableListOf<LibraryTemplateId>()
        var retries = 0
        val appearance = AppearancePreferences(interfaceScalePercent = 120, textScalePercent = 130)
        compose.setContent {
            val base = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale.value) }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density = 2f, fontScale = 1.4f),
            ) {
                LifeTracingAppearance(appearance) {
                    ArchivedTemplatesScreen(
                        state.value,
                        {
                            requests += it
                            state.value = state.value.copy(pending = it)
                        },
                        { state.value = ArchivedTemplatesState(rows, loading = false) },
                        {
                            retries++
                            state.value = ArchivedTemplatesState(listOf(rows.last()), loading = false)
                        },
                        {},
                        readCurrent = { state.value },
                    )
                }
            }
        }
        listOf(Locale.ENGLISH, Locale.forLanguageTag("ru")).forEach { language ->
            compose.runOnIdle {
                locale.value = language
                state.value = ArchivedTemplatesState()
            }
            val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(language) }
            val context = compose.activity.createConfigurationContext(configuration)
            showText(context.getString(R.string.archived_templates_loading))
            compose.runOnIdle { state.value = ArchivedTemplatesState(emptyList(), loading = false) }
            showText(context.getString(R.string.archived_templates_empty))
            compose.runOnIdle { state.value = ArchivedTemplatesState(loading = false, readFailed = true) }
            showText(context.getString(R.string.archived_templates_read_failure))
            scrollTo("archived-reload")
            compose.onNodeWithTag("archived-reload").performClick()
            showText(context.getString(R.string.library_activity))
            showText(context.getString(R.string.library_sequence))
            val restoreTag = "archived-restore-${activity.archivedRowKey()}"
            scrollTo(restoreTag)
            compose.onNodeWithTag(restoreTag).performClick().assertIsNotEnabled()
            assertEquals(activity, requests.last())
            compose.onNodeWithTag("archived-row-${activity.archivedRowKey()}").assertExists()
            showText(context.getString(R.string.archived_templates_restoring))
            compose.runOnIdle {
                state.value = state.value.copy(pending = null, failedRestore = activity)
            }
            showText(context.getString(R.string.archived_templates_restore_failure))
            scrollTo("archived-retry")
            compose.onNodeWithTag("archived-retry").performClick()
            scrollTo("archived-row-${sequence.archivedRowKey()}")
            compose.onNodeWithTag("archived-row-${sequence.archivedRowKey()}").assertIsDisplayed()
            compose.onNodeWithTag("archived-row-${activity.archivedRowKey()}").assertDoesNotExist()
            compose.onNodeWithTag("archived-purge").assertDoesNotExist()
            compose.onNodeWithText("Permanent purge").assertDoesNotExist()
            compose.onNodeWithText("Удалить навсегда").assertDoesNotExist()
        }
        assertEquals(2, retries)
        assertEquals(listOf(activity, activity), requests)
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("archived-page").performScrollToNode(hasTestTag(tag))
    }

    private fun nativeClick(tag: String): () -> Boolean {
        scrollTo(tag)
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return requireNotNull(node.config[SemanticsActions.OnClick].action)
    }

    private fun showText(text: String) {
        compose.onNodeWithTag("archived-page").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun row(id: LibraryTemplateId) =
        LibraryTrackable(
            id,
            "Long archived template / Длинное название архивного шаблона",
            "Preserved comment / Сохранённый комментарий",
            null,
            emptySet(),
            null,
            null,
            Instant.EPOCH,
        )
}
