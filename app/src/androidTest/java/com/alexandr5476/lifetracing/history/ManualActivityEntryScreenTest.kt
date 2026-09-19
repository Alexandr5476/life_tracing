package com.alexandr5476.lifetracing.history

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryOption
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ManualActivityEntryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ambiguityChoicesShowBothOccurrencesAndConcreteOffsets() {
        val ambiguity =
            ManualTimeAmbiguity(
                LocalDateTime.parse("2026-10-25T02:30"),
                ZoneId.of("Europe/Berlin"),
                listOf(ZoneOffset.ofHours(2), ZoneOffset.ofHours(1)),
            )
        show(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.STOPWATCH)),
                startedText = "2026-10-25 02:30",
                completedText = "2026-10-25 03:30",
                startedAmbiguity = ambiguity,
                startedIssue = ManualEntryIssue.AMBIGUOUS_LOCAL_TIME,
                command = ManualEntryCommand.Invalid(ManualEntryIssue.AMBIGUOUS_LOCAL_TIME),
            ),
        )

        compose.onNodeWithText("First occurrence (UTC+02:00)").assertIsDisplayed()
        compose.onNodeWithText("Second occurrence (UTC+01:00)").assertIsDisplayed()
        compose.onNodeWithTag("manual-history-started-offset-0").assertIsDisplayed()
        compose.onNodeWithTag("manual-history-started-offset-1").assertIsDisplayed()
    }

    @Test
    fun noLiveHasNoStartInput() {
        show(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.NO_LIVE_TRACKING)),
                completedText = "2026-09-16 10:00",
            ),
        )
        compose.onAllNodesWithText("Started (YYYY-MM-DD HH:MM)").assertCountEquals(0)
        compose.onNodeWithText("Completed (YYYY-MM-DD HH:MM)").assertIsDisplayed()
    }

    @Test
    fun timerTargetIsHumanReadable() {
        show(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.TIMER)),
                startedText = "2026-09-16 09:00",
                completedText = "2026-09-16 10:00",
            ),
        )
        compose.onNodeWithText("Configured timer target: 01:30").assertIsDisplayed()
    }

    @Test
    fun expectedErrorsAndMissingPresentationHaveDistinctEnglishAndRussianResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val english = context.localized(Locale.ENGLISH)
        val russian = context.localized(Locale.forLanguageTag("ru"))
        val ids =
            listOf(
                R.string.manual_history_invalid_datetime,
                R.string.manual_history_nonexistent_time,
                R.string.manual_history_ambiguous_time,
                R.string.manual_history_future_completion,
                R.string.manual_history_reversed_interval,
                R.string.manual_history_invalid_number,
                R.string.manual_history_invalid_category,
                R.string.manual_history_template_unavailable,
                R.string.manual_history_template_stale,
                R.string.manual_history_catalog_failure,
                R.string.manual_history_template_failure,
                R.string.manual_history_save_failure,
                R.string.manual_history_missing_value,
            )
        ids.forEach { id -> assertNotEquals(english.getString(id), russian.getString(id)) }
    }

    @Test
    fun actualFieldInteractionsPreserveZeroEmptyAndStableCategoryIdentityAcrossMissing() {
        showInteractive()

        compose.onAllNodesWithText("Missing").assertCountEquals(0)
        compose.onNodeWithTag("manual-history-field-number-actual").assertTextContains("0")
        compose.onNodeWithTag("manual-history-field-number-missing").performClick()
        compose.onAllNodesWithText("Missing").assertCountEquals(1)
        compose.onNodeWithTag("manual-history-field-number-missing").performClick()
        compose.onAllNodesWithText("Missing").assertCountEquals(0)
        compose.onNodeWithTag("manual-history-field-number-actual").assertTextContains("0")

        compose.onNodeWithTag("manual-history-field-text-missing").performClick()
        compose.onAllNodesWithText("Missing").assertCountEquals(1)
        compose.onNodeWithTag("manual-history-field-text-missing").performClick()
        compose.onAllNodesWithText("Missing").assertCountEquals(0)
        compose.onNodeWithTag("manual-history-field-text-actual").performTextInput("restored")
        compose.onNodeWithTag("manual-history-field-text-actual").assertTextContains("restored")

        compose.onNodeWithTag("manual-history-option-option-b").performScrollTo().performClick()
        compose.onNodeWithText("✓ B").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("manual-history-field-category-missing").performScrollTo().performClick()
        compose.onAllNodesWithText("Missing").assertCountEquals(1)
        compose.onNodeWithTag("manual-history-field-category-missing").performScrollTo().performClick()
        compose.onNodeWithText("✓ B").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Archived").assertDoesNotExist()
    }

    @Test
    fun gapAndStaleRecoveryRenderTypedLocalizedActions() {
        var dispatched: ManualActivityEntryAction? = null
        show(
            ManualActivityEntryState(
                catalog = ManualEntryLoad.Content(emptyList()),
                selected = ManualEntryLoad.Content(template(TimeTrackingMode.STOPWATCH)),
                startedText = "2026-03-29 02:30",
                completedText = "2026-03-29 03:30",
                startedIssue = ManualEntryIssue.NONEXISTENT_LOCAL_TIME,
                command = ManualEntryCommand.Failure(ManualEntryIssue.TEMPLATE_STALE),
            ),
        ) { dispatched = it }

        compose.onNodeWithText("This local time does not exist because the clock moves forward.").assertIsDisplayed()
        compose.onNodeWithText("Review current activity").performClick()
        compose.runOnIdle { assertEquals(ManualActivityEntryAction.ReviewStaleTemplate, dispatched) }
        compose
            .onNodeWithText("The selected activity changed. Select it again to review the current configuration.")
            .assertIsDisplayed()
    }

    private fun show(
        state: ManualActivityEntryState,
        onAction: (ManualActivityEntryAction) -> Unit = {},
    ) {
        compose.setContent {
            LifeTracingTheme {
                ManualActivityEntryScreen(state, onAction, {})
            }
        }
    }

    private fun showInteractive() {
        compose.setContent {
            LifeTracingTheme {
                var state by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(
                        ManualActivityEntryState(
                            catalog = ManualEntryLoad.Content(emptyList()),
                            selected = ManualEntryLoad.Content(fieldTemplate()),
                            completedText = "2026-09-16 10:00",
                            values =
                                mapOf(
                                    ActivityTemplateFieldId("number") to
                                        ManualEntryFieldDraft(CustomFieldType.NUMBER, numberText = "0"),
                                    ActivityTemplateFieldId("text") to
                                        ManualEntryFieldDraft(CustomFieldType.TEXT, text = ""),
                                    ActivityTemplateFieldId("category") to
                                        ManualEntryFieldDraft(
                                            CustomFieldType.CATEGORY,
                                            selectedOptionId = CategoryOptionId("option-a"),
                                        ),
                                ),
                        ),
                    )
                }
                ManualActivityEntryScreen(
                    state,
                    { action ->
                        val id =
                            when (action) {
                                is ManualActivityEntryAction.SetMissing -> action.id
                                is ManualActivityEntryAction.SetPresent -> action.id
                                is ManualActivityEntryAction.EditText -> action.id
                                is ManualActivityEntryAction.SelectCategory -> action.id
                                else -> null
                            }
                        if (id != null) {
                            val draft = state.values.getValue(id)
                            val changed =
                                when (action) {
                                    is ManualActivityEntryAction.SetMissing -> draft.copy(missing = true)
                                    is ManualActivityEntryAction.SetPresent -> draft.copy(missing = false)
                                    is ManualActivityEntryAction.EditText ->
                                        draft.copy(
                                            text = action.text,
                                            missing = false,
                                        )
                                    is ManualActivityEntryAction.SelectCategory ->
                                        draft.copy(selectedOptionId = action.optionId, missing = false)
                                    else -> draft
                                }
                            state = state.copy(values = state.values + (id to changed))
                        }
                    },
                    {},
                )
            }
        }
    }

    private fun template(mode: TimeTrackingMode): ActivityTemplate =
        ActivityTemplate(
            ActivityTemplateId("template"),
            "Template",
            null,
            mode,
            if (mode == TimeTrackingMode.TIMER) Duration.ofSeconds(90) else null,
            StatisticsSeriesId("series"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun fieldTemplate(): ActivityTemplate =
        template(TimeTrackingMode.NO_LIVE_TRACKING).copy(
            fields =
                listOf(
                    ActivityTemplateField(
                        ActivityTemplateFieldId("number"),
                        0,
                        "Number",
                        CustomFieldType.NUMBER,
                        defaultNumberScaled = 0,
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    ),
                    ActivityTemplateField(
                        ActivityTemplateFieldId("text"),
                        1,
                        "Text",
                        CustomFieldType.TEXT,
                        defaultText = "",
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    ),
                    ActivityTemplateField(
                        ActivityTemplateFieldId("category"),
                        2,
                        "Category",
                        CustomFieldType.CATEGORY,
                        defaultCategoryOptionId = CategoryOptionId("option-a"),
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                        categoryOptions =
                            listOf(
                                CategoryOption(CategoryOptionId("option-a"), 0, "A"),
                                CategoryOption(CategoryOptionId("option-b"), 1, "B"),
                                CategoryOption(CategoryOptionId("archived"), 2, "Archived", true),
                            ),
                    ),
                ),
        )

    private fun Context.localized(locale: Locale): Context =
        createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) })
}
