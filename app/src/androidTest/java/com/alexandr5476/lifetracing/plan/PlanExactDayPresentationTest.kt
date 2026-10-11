package com.alexandr5476.lifetracing.plan

import android.content.res.Configuration
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.WeekPlanRead
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PlanExactDayPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

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
            compose.onNodeWithText(context.getString(R.string.plan_time)).performTextReplacement("02:31")
            compose
                .onNodeWithContentDescription(
                    context.getString(R.string.plan_first_occurrence, "02:31", "UTC+02:00"),
                ).assertIsNotSelected()
            compose
                .onNodeWithContentDescription(
                    context.getString(R.string.plan_second_occurrence, "02:31", "UTC+01:00"),
                ).assertIsNotSelected()
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
                    ),
                )
            }
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_invalid_offset),
                ).performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.plan_save)).assertIsNotEnabled()
            compose.onNodeWithText(context.getString(R.string.plan_date)).performTextReplacement("2026-03-29")
            compose.onNodeWithText(context.getString(R.string.plan_nonexistent_time)).assertIsDisplayed()
            compose.onNodeWithTag("plan-schedule-form").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.plan_save)).assertIsNotEnabled()
            compose.onNodeWithText(context.getString(R.string.plan_date)).performTextReplacement("not-date")
            compose.onNodeWithText(context.getString(R.string.plan_invalid_date)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.plan_date)).performTextReplacement("2026-03-29")
            compose.onNodeWithText(context.getString(R.string.plan_time)).performTextReplacement("bad-time")
            compose.onNodeWithText(context.getString(R.string.plan_invalid_time)).assertIsDisplayed()
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
                controller.dispatch(PlanAction.SubmitForm)
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
        assertions: (PlanController, List<PlanMutation>) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val commands = java.util.concurrent.CopyOnWriteArrayList<PlanMutation>()
        val controller =
            PlanController(
                scope,
                { WeekPlanRead(it.weekStart, it.selectedDate, emptyList(), emptyList(), emptyList()) },
                { _, _, _ -> emptyList() },
                { CancelledPlanPage(emptyList(), false) },
                { commands += it },
                { Instant.parse("2026-10-24T10:00:00Z") },
                zoneId,
                boundaryScheduler = LocalDateBoundaryScheduler { _, _, _, _ -> },
            )
        try {
            compose.runOnIdle {
                controller.dispatch(
                    PlanAction.SelectCatalogItem(
                        ReusablePlanCatalogItem(
                            LibraryTemplateId.Activity(ActivityTemplateId("source")),
                            "Activity",
                            null,
                            null,
                        ),
                    ),
                )
                controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.EXACT_DAY))
                controller.dispatch(PlanAction.ChangeScheduleDate("2026-10-25"))
                controller.dispatch(PlanAction.ChangeScheduleTime("02:30"))
            }
            compose.setContent {
                val state by controller.state.collectAsState()
                LifeTracingTheme { PlanScreen(state, controller::dispatch, {}) }
            }
            assertions(controller, commands)
        } finally {
            compose.runOnIdle { controller.close() }
            scope.cancel()
        }
    }
}
