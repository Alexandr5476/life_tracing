package com.alexandr5476.lifetracing

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.alexandr5476.lifetracing.daily.DailyAction
import com.alexandr5476.lifetracing.daily.DailyDateRelation
import com.alexandr5476.lifetracing.daily.DailyLoadState
import com.alexandr5476.lifetracing.daily.DailyPresentationState
import com.alexandr5476.lifetracing.daily.DailyScreen
import com.alexandr5476.lifetracing.domain.DailyRead
import com.alexandr5476.lifetracing.ui.theme.LifeTracingMotion
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class LifeTracingAppTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun production_shell_displays_daily_root() {
        composeTestRule.setContent { LifeTracingApp() }

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.daily_title))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.design_preview_heading))
            .assertDoesNotExist()
    }

    @Test
    fun shell_starts_with_only_daily_root_and_uses_finite_lifetracing_motion() {
        assertEquals(listOf(DailyRoot), dailyInitialBackStack)
        assertEquals(1, dailyInitialBackStack.size)
        assertEquals(LifeTracingMotion.standardDurationMillis, dailyNavigationTransitionDurationMillis)
        assertTrue(dailyNavigationTransitionDurationMillis > 0)
    }

    @Test
    fun past_daily_keeps_header_and_dispatches_date_navigation() {
        val actions = mutableListOf<DailyAction>()
        composeTestRule.setContent {
            LifeTracingTheme {
                DailyScreen(
                    state =
                        DailyPresentationState(
                            selectedDate = LocalDate.parse("2026-08-19"),
                            dateRelation = DailyDateRelation.PAST,
                            load = DailyLoadState.Empty(DailyRead(emptyList(), emptyList(), emptyList(), null)),
                        ),
                    onAction = actions::add,
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(composeTestRule.activity.getString(R.string.daily_previous_day))
            .performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.daily_return_today)).performClick()

        assertEquals(listOf(DailyAction.PreviousDay, DailyAction.Today), actions)
    }

    @Test
    fun daily_resources_are_utf8_and_month_presentation_remains_absent() {
        val context = composeTestRule.activity
        val dailyIds =
            R.string::class.java.fields
                .filter { it.name.startsWith("daily_") }
                .map { it.getInt(null) }
        val badSequences = listOf("Ð", "Ñ", "â", "вЂ")

        listOf(Locale.ENGLISH, Locale.forLanguageTag("ru")).forEach { locale ->
            val configuration = android.content.res.Configuration(context.resources.configuration)
            configuration.setLocale(locale)
            val localized = context.createConfigurationContext(configuration)
            dailyIds.forEach { id ->
                val value = localized.getString(id)
                assertFalse("Mojibake in $locale resource: $value", badSequences.any(value::contains))
            }
        }

        val russian =
            android.content.res
                .Configuration(context.resources.configuration)
                .also {
                    it.setLocale(Locale.forLanguageTag("ru"))
                }.let(context::createConfigurationContext)
        assertEquals("Предыдущий день", russian.getString(R.string.daily_previous_day))
        assertEquals("%1\$s–%2\$s", context.resources.getString(R.string.daily_time_range))
        assertEquals(0, context.resources.getIdentifier("daily_month_plan", "string", context.packageName))
    }
}
