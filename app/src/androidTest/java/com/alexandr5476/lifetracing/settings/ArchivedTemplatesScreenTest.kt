package com.alexandr5476.lifetracing.settings

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.LibraryTrackable
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Locale

class ArchivedTemplatesScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
