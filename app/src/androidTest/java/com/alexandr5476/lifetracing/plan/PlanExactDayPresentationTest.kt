package com.alexandr5476.lifetracing.plan

import android.content.res.Configuration
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanDayPresence
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.PrimaryWorkflowAppearance
import com.alexandr5476.lifetracing.ui.assertTextFullyVisible
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PlanExactDayPresentationTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun calendarToExactDayKeepsGapAndBothOverlapChoicesReadableAcrossAppearances() {
        val appearance = mutableStateOf(PrimaryWorkflowAppearance.EnglishMinimum)
        val english = appearance.value.resourcesContext(context)
        val zone = ZoneId.of("America/New_York")
        withForm(zoneId = { zone }, appearance = appearance) { controller, commands ->
            val date = LocalDate.parse("2027-03-14")
            compose.onNodeWithTag("plan-day-$date").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("plan-displayed-week").assertTextFullyVisible()
            compose.onNodeWithText(english.getString(R.string.plan_empty_day)).assertTextFullyVisible()
            compose.onNodeWithTag("plan-add").performScrollTo().performClick()
            compose.waitUntil {
                controller.state.value.catalog
                    ?.loading == false
            }
            compose.onNodeWithTag("plan-catalog-activity-source").performClick()
            compose.onNodeWithTag("plan-schedule-kind-EXACT_DAY").performScrollTo().performClick()
            val dateInput = compose.onNodeWithTag("plan-schedule-date")
            val timeInput = compose.onNodeWithTag("plan-schedule-time")
            val save = compose.onNodeWithTag("plan-schedule-save")
            dateInput.performScrollTo().performTextReplacement(date.toString())
            timeInput.performScrollTo().performTextReplacement("02:30")
            compose.onNodeWithText(english.getString(R.string.plan_nonexistent_time)).assertTextFullyVisible()
            save.assertIsDisplayed().assertIsNotEnabled()
            compose.runOnIdle { appearance.value = PrimaryWorkflowAppearance.RussianMaximum }
            val russian = appearance.value.resourcesContext(context)
            timeInput.performScrollTo()
            keyboard.enter(timeInput)
            timeInput.performClick()
            val dialogView = (timeInput.fetchSemanticsNode().root as ViewRootForTest).view
            compose.waitUntil(5_000) {
                compose.runOnIdle {
                    ViewCompat.getRootWindowInsets(dialogView)?.isVisible(WindowInsetsCompat.Type.ime()) ==
                        true
                }
            }
            compose.onNodeWithText(russian.getString(R.string.plan_nonexistent_time)).assertTextFullyVisible()
            save.assertIsDisplayed().assertIsNotEnabled()
            keyboard.closeKeyboard(timeInput)

            dateInput.performScrollTo().performTextReplacement("2027-11-07")
            timeInput.performScrollTo().performTextReplacement("01:30")
            val unselected = controller.state.value.form
            for (mode in PrimaryWorkflowAppearance.entries) {
                compose.runOnIdle { appearance.value = mode }
                val resources = mode.resourcesContext(context)
                listOf(R.string.plan_first_occurrence to "-04:00", R.string.plan_second_occurrence to "-05:00")
                    .forEachIndexed { index, (label, offset) ->
                        compose.onNodeWithTag("plan-schedule-offset-$index").performScrollTo().assertIsNotSelected()
                        compose
                            .onNodeWithText(resources.getString(label, "01:30", "UTC$offset"), useUnmergedTree = true)
                            .assertTextFullyVisible()
                    }
                save.assertIsNotEnabled()
            }
            compose.runOnIdle {
                assertEquals(unselected, controller.state.value.form)
                assertTrue(commands.isEmpty())
            }
            compose
                .onNodeWithTag("plan-schedule-offset-0")
                .performScrollTo()
                .performClick()
                .assertIsSelected()
            compose
                .onNodeWithTag("plan-schedule-offset-1")
                .performScrollTo()
                .performClick()
                .assertIsSelected()
            save.assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil { commands.size == 1 }
            val command = commands.single() as PlanMutation.CreateActivity
            assertEquals(ActivityTemplateId("source"), command.id)
            assertEquals(PlanTarget.ExactDay(Instant.parse("2027-11-07T06:30:00Z"), zone), command.schedule.toTarget())
        }
    }

    @Test
    fun overlapControlsAreAccessibleUnselectedAndResetAfterAnEdit() {
        withForm { controller, commands ->
            val first = context.getString(R.string.plan_first_occurrence, "02:30", "UTC+02:00")
            val second = context.getString(R.string.plan_second_occurrence, "02:30", "UTC+01:00")
            compose
                .onNodeWithContentDescription(first)
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsNotSelected()
            compose
                .onNodeWithContentDescription(second)
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsNotSelected()
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_scheduling_zone, "Europe/Berlin"),
                ).performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.plan_save)).assertIsNotEnabled()
            compose
                .onNodeWithContentDescription(first)
                .performScrollTo()
                .performClick()
                .assertIsSelected()
            compose.runOnIdle {
                assertEquals(
                    ZoneOffset.ofHours(2),
                    controller.state.value.form
                        ?.selectedOffset,
                )
            }
            compose
                .onNodeWithContentDescription(second)
                .performScrollTo()
                .performClick()
                .assertIsSelected()
            compose.runOnIdle {
                assertEquals(
                    ZoneOffset.ofHours(1),
                    controller.state.value.form
                        ?.selectedOffset,
                )
            }
            compose
                .onNodeWithText(context.getString(R.string.plan_time))
                .performScrollTo()
                .performTextReplacement("02:31")
            compose
                .onNodeWithContentDescription(
                    context.getString(R.string.plan_first_occurrence, "02:31", "UTC+02:00"),
                ).assertIsNotSelected()
            compose
                .onNodeWithContentDescription(
                    context.getString(R.string.plan_second_occurrence, "02:31", "UTC+01:00"),
                ).assertIsNotSelected()
                .performScrollTo()
                .performClick()
            compose.runOnIdle {
                assertEquals(
                    ZoneOffset.ofHours(1),
                    controller.state.value.form
                        ?.selectedOffset,
                )
            }
            compose.onNodeWithText(context.getString(R.string.plan_save)).performClick()
            compose.waitUntil { commands.size == 1 }
        }
    }

    @Test
    fun selectingAnOverlapOccurrenceRetainsKeyboardFocus() {
        withForm { _, commands ->
            val first = context.getString(R.string.plan_first_occurrence, "02:30", "UTC+02:00")
            val choice = compose.onNodeWithContentDescription(first).performScrollTo()
            keyboard.enter(choice)
            choice.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            choice
                .assertIsFocused()
                .performClick()
                .assertIsSelected()
                .assertIsFocused()
            compose.runOnIdle { assertTrue(commands.isEmpty()) }
        }
    }

    @Test
    fun unsupportedFractionsShowARecoverableLocalizedError() {
        withForm { _, commands ->
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_date),
                ).performScrollTo()
                .performTextReplacement("2026-10-26")
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_time),
                ).performScrollTo()
                .performTextReplacement("10:00:00.123456789")
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_unsupported_time_precision),
                ).performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithTag("plan-schedule-form").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.plan_save)).assertIsNotEnabled()
            compose.runOnIdle { assertTrue(commands.isEmpty()) }
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_time),
                ).performScrollTo()
                .performTextReplacement("10:00:00.123")
            compose.onNodeWithText(context.getString(R.string.plan_save)).performClick()
            compose.waitUntil { commands.size == 1 }
        }
    }

    @Test
    fun localizedGapAndMalformedInputFeedbackRemainInsideTheEditableDialog() {
        withForm { controller, commands ->
            compose.runOnIdle {
                val form = requireNotNull(controller.state.value.form)
                controller.dispatch(
                    PlanAction.SelectScheduleOffset(
                        ZoneOffset.ofHours(3),
                        form.date,
                        form.time,
                        form.schedulingZoneId,
                        form.inputRevision,
                        form.formId,
                    ),
                )
            }
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_invalid_offset),
                ).performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.plan_save)).assertIsNotEnabled()
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_date),
                ).performScrollTo()
                .performTextReplacement("2026-03-29")
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_nonexistent_time),
                ).performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithTag("plan-schedule-form").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.plan_save)).assertIsNotEnabled()
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_date),
                ).performScrollTo()
                .performTextReplacement("not-date")
            compose.onNodeWithText(context.getString(R.string.plan_invalid_date)).performScrollTo().assertIsDisplayed()
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_date),
                ).performScrollTo()
                .performTextReplacement("2026-03-29")
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_time),
                ).performScrollTo()
                .performTextReplacement("bad-time")
            compose.onNodeWithText(context.getString(R.string.plan_invalid_time)).performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertTrue(commands.isEmpty()) }
        }
    }

    @Test
    fun russianOccurrenceLabelsAndTemporalFeedbackUseLocalizedResources() {
        val configuration =
            Configuration(
                context.resources.configuration,
            ).apply { setLocale(Locale.forLanguageTag("ru")) }
        val russian = context.createConfigurationContext(configuration)
        val english =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    setLocale(Locale.ENGLISH)
                },
            )
        assertEquals(
            "02:30 · UTC+02:00 · первый раз",
            russian.getString(R.string.plan_first_occurrence, "02:30", "UTC+02:00"),
        )
        assertEquals(
            "02:30 · UTC+01:00 · второй раз",
            russian.getString(R.string.plan_second_occurrence, "02:30", "UTC+01:00"),
        )
        for (id in listOf(
            R.string.plan_unsupported_time_precision,
            R.string.plan_nonexistent_time,
            R.string.plan_ambiguous_time,
            R.string.plan_invalid_offset,
            R.string.plan_zone_changed,
        )) {
            assertTrue(russian.getString(id) != english.getString(id))
        }
    }

    @Test
    fun changedZoneShowsAnExplicitReviewPathAndThenUsesTheDisplayedCurrentZone() {
        var zone = ZoneId.of("Europe/Berlin")
        withForm(zoneId = { zone }) { controller, commands ->
            compose.runOnIdle {
                zone = ZoneId.of("Pacific/Auckland")
                controller.dispatch(PlanAction.SubmitForm(controller.formId, controller.formRevision))
            }
            compose.onNodeWithText(context.getString(R.string.plan_zone_changed)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.plan_save)).assertIsNotEnabled()
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_review_current_zone),
                ).performScrollTo()
                .performClick()
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_scheduling_zone, "Pacific/Auckland"),
                ).performScrollTo()
                .assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(
                    zone,
                    controller.state.value.form
                        ?.schedulingZoneId,
                )
                assertTrue(commands.isEmpty())
            }
        }
    }

    private fun withForm(
        zoneId: () -> ZoneId = { ZoneId.of("Europe/Berlin") },
        appearance: State<PrimaryWorkflowAppearance>? = null,
        assertions: (PlanController, List<PlanMutation>) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val commands = java.util.concurrent.CopyOnWriteArrayList<PlanMutation>()
        val controller =
            PlanController(
                scope,
                {
                    WeekPlanRead(
                        it.weekStart,
                        it.selectedDate,
                        emptyList(),
                        emptyList(),
                        List(7) { day -> PlanDayPresence(it.weekStart.plusDays(day.toLong()), 0) },
                    )
                },
                { _, _, _ ->
                    listOf(
                        ReusablePlanCatalogItem(
                            LibraryTemplateId.Activity(ActivityTemplateId("source")),
                            if (appearance == null) "Activity" else "A longer activity name for the appointment",
                            null,
                            null,
                        ),
                    )
                },
                { CancelledPlanPage(emptyList(), false) },
                { commands += it },
                { Instant.parse(if (appearance == null) "2026-10-24T10:00:00Z" else "2027-03-14T16:00:00Z") },
                zoneId,
                boundaryScheduler = LocalDateBoundaryScheduler { _, _, _, _ -> },
            )
        try {
            if (appearance == null) {
                compose.runOnIdle { controller.dispatch(PlanAction.OpenCatalog) }
                compose.waitUntil {
                    controller.state.value.catalog
                        ?.loading == false
                }
                compose.runOnIdle {
                    val catalog = requireNotNull(controller.state.value.catalog)
                    controller.dispatch(PlanAction.SelectCatalogItem(catalog.items.single(), catalog.context))
                    controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.EXACT_DAY, controller.formId))
                    controller.dispatch(PlanAction.ChangeScheduleDate("2026-10-25", controller.formId))
                    controller.dispatch(PlanAction.ChangeScheduleTime("02:30", controller.formId))
                }
            }
            compose.setContent {
                val state by controller.state.collectAsState()
                if (appearance == null) {
                    LifeTracingTheme { PlanScreen(state, controller::dispatch, {}) }
                } else {
                    appearance.value.Content { PlanRoute(controller, onBack = {}) }
                }
            }
            if (appearance != null) compose.waitUntil { controller.state.value.week is PlanLoad.Content }
            assertions(controller, commands)
        } finally {
            compose.runOnIdle { controller.close() }
            scope.cancel()
        }
    }
}
