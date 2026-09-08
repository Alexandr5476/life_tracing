package com.alexandr5476.lifetracing.editor

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Locale

class ActivityTemplateEditorScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cleanToolbarBackExitsAndFocusedEditorHasNoPrimaryNavigation() {
        var exits = 0
        val controller = controller()
        composeTestRule.setContent {
            LifeTracingTheme { ActivityTemplateEditorRoute(controller) { exits++ } }
        }
        awaitReady(controller)

        composeTestRule.onNodeWithText(text(R.string.activity_editor_back)).performClick()

        assertEquals(1, exits)
        composeTestRule.onNodeWithText(text(R.string.daily_start_activity)).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.daily_library)).assertDoesNotExist()
        controller.close()
    }

    @Test
    fun dirtyToolbarAndNativeBackShareDiscardConfirmationAndCancelKeepsDraft() {
        var exits = 0
        val controller = controller()
        composeTestRule.setContent {
            LifeTracingTheme { ActivityTemplateEditorRoute(controller) { exits++ } }
        }
        awaitReady(controller)
        composeTestRule.runOnIdle { controller.updateDraft { it.copy(name = "Draft") } }

        composeTestRule.onNodeWithText(text(R.string.activity_editor_back)).performClick()
        composeTestRule.onNodeWithText(text(R.string.activity_editor_discard_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.activity_editor_continue_editing)).performClick()
        composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.onNodeWithText(text(R.string.activity_editor_discard_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.activity_editor_continue_editing)).performClick()

        assertEquals(0, exits)
        assertEquals(
            "Draft",
            controller.state.value
                .readyDraft()
                ?.name,
        )
        controller.close()
    }

    @Test
    fun selectedCategoryDefaultCanBeClearedWithoutRemovingItsOption() {
        var saved: ActivityTemplateDraft? = null
        val draft =
            ActivityTemplateDraft(
                "Rating",
                null,
                TimeTrackingMode.NO_LIVE_TRACKING,
                null,
                fields =
                    listOf(
                        ActivityFieldDraft(
                            DraftIdentity.New("difficulty"),
                            0,
                            "Difficulty",
                            CustomFieldType.CATEGORY,
                            defaultCategoryOption = DraftIdentity.New("easy"),
                            categoryOptions =
                                listOf(ActivityCategoryOptionDraft(DraftIdentity.New("easy"), 0, "Easy")),
                        ),
                    ),
            )
        val controller = controller(draft) { saved = it }
        composeTestRule.setContent { LifeTracingTheme { ActivityTemplateEditorRoute(controller) {} } }
        awaitReady(controller)

        composeTestRule.onNodeWithText(text(R.string.activity_editor_clear_default)).performScrollTo().performClick()
        composeTestRule.runOnIdle {
            val field =
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
            assertNull(field.defaultCategoryOption)
            assertEquals(listOf("Easy"), field.categoryOptions.map(ActivityCategoryOptionDraft::label))
        }
        composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().performClick()
        composeTestRule.waitUntil { saved != null }
        assertNull(saved!!.fields.single().defaultCategoryOption)
        controller.close()
    }

    @Test
    fun ioWriterCompletionReturnsToComposeOnceOnTheMainThread() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller =
            ActivityTemplateEditorController(
                scope,
                ActivityTemplateEditorTarget.New,
                { null },
                { draft, _, _ -> template(draft) },
                { _, _, draft, _ -> template(draft) },
                Instant::now,
            )
        var callbacks = 0
        var callbackLooper: Looper? = null
        composeTestRule.setContent {
            LifeTracingTheme {
                ActivityTemplateEditorRoute(
                    controller,
                    onBack = {},
                    onCommitted = {
                        callbacks++
                        callbackLooper = Looper.myLooper()
                    },
                )
            }
        }
        awaitReady(controller)

        composeTestRule.onNodeWithText(text(R.string.activity_editor_done)).performScrollTo().performClick()
        composeTestRule.waitUntil { callbacks == 1 }
        composeTestRule.waitForIdle()

        assertEquals(1, callbacks)
        assertEquals(Looper.getMainLooper(), callbackLooper)
        controller.close()
        scope.cancel()
    }

    @Test
    fun englishCompactChoicesStayInsideBounds() = compactChoicesStayInsideBounds(Locale.ENGLISH)

    @Test
    fun russianCompactChoicesStayInsideBounds() = compactChoicesStayInsideBounds(Locale.forLanguageTag("ru"))

    private fun compactChoicesStayInsideBounds(locale: Locale) {
        val configuration =
            android.content.res.Configuration(composeTestRule.activity.resources.configuration).apply {
                setLocale(locale)
            }
        val context = composeTestRule.activity.createConfigurationContext(configuration)
        val controller = controller()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context) {
                LifeTracingTheme {
                    Box(Modifier.width(320.dp)) { ActivityTemplateEditorRoute(controller) {} }
                }
            }
        }
        awaitReady(controller)

        val labels =
            listOf(
                R.string.activity_editor_stopwatch,
                R.string.activity_editor_timer,
                R.string.activity_editor_none,
                R.string.activity_editor_number,
                R.string.activity_editor_category,
                R.string.activity_editor_text,
            ).map(context::getString)
        val compactWidthPixels = 320 * context.resources.displayMetrics.density
        labels.forEach { label ->
            val node = composeTestRule.onNodeWithText(label)
            node.performScrollTo().assertIsDisplayed().assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= 0f)
            assertTrue(bounds.right <= compactWidthPixels)
        }
        controller.close()
    }

    private fun controller(
        initial: ActivityTemplateDraft = ActivityTemplateDraft("", null, TimeTrackingMode.STOPWATCH, null),
        save: (ActivityTemplateDraft) -> Unit = {},
    ) = ActivityTemplateEditorController(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ActivityTemplateEditorTarget.New,
        { null },
        { draft, _, _ ->
            save(draft)
            template(draft)
        },
        { _, _, draft, _ ->
            save(draft)
            template(draft)
        },
        Instant::now,
    ).also { controller ->
        if (initial.name.isNotEmpty() || initial.fields.isNotEmpty()) {
            composeTestRule.waitUntil { controller.state.value.load is ActivityTemplateEditorLoad.Ready }
            composeTestRule.runOnIdle { controller.updateDraft { initial } }
        }
    }

    private fun awaitReady(controller: ActivityTemplateEditorController) {
        composeTestRule.waitUntil { controller.state.value.load is ActivityTemplateEditorLoad.Ready }
    }

    private fun template(draft: ActivityTemplateDraft) =
        ActivityTemplate(
            ActivityTemplateId("template"),
            draft.name,
            draft.shortComment,
            draft.timeTrackingMode,
            draft.timerTarget,
            StatisticsSeriesId("series"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            settings = draft.settings,
        )

    private fun text(id: Int): String = composeTestRule.activity.getString(id)
}
