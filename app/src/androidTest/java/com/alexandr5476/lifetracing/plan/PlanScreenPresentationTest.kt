package com.alexandr5476.lifetracing.plan

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.PlanActivityRowMetadata
import com.alexandr5476.lifetracing.domain.PlanDayPresence
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class PlanScreenPresentationTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val monday = LocalDate.parse("2026-09-14")
    private val selected = monday.plusDays(1)

    @Test
    fun canonicalWeekShowsSevenDaysSeparateContextsAndIndependentScheduleExecutionSemantics() {
        val floatingNoLive = row("floating-no-live", PlanTarget.FloatingDay(selected), noLive = true)
        val exactNoLive =
            row(
                "exact-no-live",
                PlanTarget.ExactDay(Instant.parse("2026-09-15T12:30:00Z"), java.time.ZoneOffset.UTC),
                noLive = true,
            )
        val timed = row("timed", PlanTarget.FloatingDay(selected), timerTarget = Duration.ofSeconds(90))
        val sequence = row("sequence", PlanTarget.Week(monday), sequence = true)
        val weekNoLive = row("week-no-live", PlanTarget.Week(monday), noLive = true)
        val densities = listOf(0, 3, 2, 1, 4, 1, 0)
        setScreen(listOf(floatingNoLive, exactNoLive, timed), listOf(sequence, weekNoLive), densities)

        val dayTags =
            compose
                .onAllNodes(
                    SemanticsMatcher("Plan day") {
                        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("plan-day-") == true
                    },
                ).fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.TestTag] }
        assertEquals((0L..6L).map { "plan-day-${monday.plusDays(it)}" }, dayTags)
        densities.forEachIndexed { offset, density ->
            val date = monday.plusDays(offset.toLong())
            compose
                .onNodeWithContentDescription(context.getString(R.string.plan_day_density, date.toString(), density))
                .assertExists()
        }
        compose.onNodeWithText(context.getString(R.string.plan_selected_day, selected.toString())).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_this_week)).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.plan_anytime)).assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.plan_exact_at, "12:30")).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.plan_week_placement)).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.plan_timer_target, "01:30")).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.plan_complete_action)).assertCountEquals(3)
        compose.onAllNodesWithText(context.getString(R.string.plan_start_action)).assertCountEquals(2)
        compose
            .onNodeWithContentDescription(context.getString(R.string.plan_day_density, selected.toString(), 3))
            .assertExists()
        compose.onNodeWithText("Month").assertDoesNotExist()
    }

    @Test
    fun lifecycleAndSourceStatesOnlyOfferMutableChangedUpdate() {
        val current = row("current", PlanTarget.FloatingDay(selected), source = PlanSourceState.CURRENT)
        val changed = row("changed", PlanTarget.FloatingDay(selected), source = PlanSourceState.CHANGED)
        val archived = row("archived", PlanTarget.FloatingDay(selected), source = PlanSourceState.ARCHIVED)
        val unavailable = row("unavailable", PlanTarget.FloatingDay(selected), source = PlanSourceState.UNAVAILABLE)
        val engaged = row("engaged", PlanTarget.FloatingDay(selected), engaged = true)
        val fulfilled = row("fulfilled", PlanTarget.FloatingDay(selected), fulfilled = true)
        val overdue = row("overdue", PlanTarget.FloatingDay(selected), overdue = true)
        setScreen(listOf(current, changed, archived, unavailable, engaged, fulfilled, overdue), emptyList())

        compose.onAllNodesWithText(context.getString(R.string.plan_source_current)).assertCountEquals(4)
        compose.onNodeWithText(context.getString(R.string.plan_source_changed)).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_source_archived)).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_source_unavailable)).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_engaged)).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_fulfilled)).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_overdue)).assertExists()
        compose
            .onAllNodesWithText(context.getString(R.string.plan_update_template))
            .assertCountEquals(1)[0]
            .assertIsEnabled()
        compose.onAllNodesWithText(context.getString(R.string.plan_start_action)).assertCountEquals(5)
    }

    @Test
    fun enabledExecutionButtonReturnsTheExactRenderedRowIdentity() {
        val row = row("clicked", PlanTarget.FloatingDay(selected), noLive = true, overdue = true)
        var clicked: com.alexandr5476.lifetracing.domain.PlanActionIdentity? = null
        val read = WeekPlanRead(monday, selected, listOf(row), emptyList(), emptyList())
        compose.setContent {
            LifeTracingTheme {
                PlanScreen(
                    PlanPresentationState(monday, selected, PlanLoad.Content(read)),
                    {},
                    {},
                    { clicked = it },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.plan_complete_action)).assertIsEnabled().performClick()

        assertEquals(row.plan.actionIdentity(), clicked)
    }

    @Test
    fun failuresUseResourceBackedCopyAndRecoveryDisablesMutationEntry() {
        val arbitrary = "repository secret detail"
        val state =
            PlanPresentationState(
                monday,
                selected,
                week = PlanLoad.Failure(PlanMessage.LOAD_FAILED),
                isMutating = true,
                mutationFailure = PlanMessage.ACTION_UNAVAILABLE,
            )
        compose.setContent { LifeTracingTheme { PlanScreen(state, {}, {}) } }

        compose.onNodeWithText(context.getString(R.string.plan_load_failed)).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_action_unavailable)).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_retry)).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_add)).assertIsNotEnabled()
        compose.onNodeWithText(arbitrary).assertDoesNotExist()
    }

    @Test
    fun hiddenCanonicalRecoveryFailureHasVisibleLocalizedRetry() {
        val read =
            WeekPlanRead(
                monday,
                selected,
                emptyList(),
                emptyList(),
                (0L..6L).map { PlanDayPresence(monday.plusDays(it), 0) },
            )
        val state =
            PlanPresentationState(
                monday,
                selected,
                week = PlanLoad.Content(read),
                isMutating = true,
                recoveryFailure = PlanMessage.LOAD_FAILED,
            )
        compose.setContent { LifeTracingTheme { PlanScreen(state, {}, {}) } }

        compose.onNodeWithText(context.getString(R.string.plan_load_failed)).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_retry)).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_add)).assertIsNotEnabled()
    }

    @Test
    fun weekRecoveryFailureRemainsRetryableWhileCancelledDialogIsOpen() {
        val state =
            PlanPresentationState(
                monday,
                selected,
                week = PlanLoad.Failure(PlanMessage.LOAD_FAILED),
                cancelledOpen = true,
                cancelled = PlanLoad.Content(CancelledPlanPage(emptyList(), false)),
                isMutating = true,
                recoveryFailure = PlanMessage.LOAD_FAILED,
            )
        compose.setContent { LifeTracingTheme { PlanScreen(state, {}, {}) } }

        compose
            .onAllNodesWithText(
                context.getString(R.string.plan_load_failed),
            ).assertCountEquals(2)[1]
            .assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.plan_retry)).assertCountEquals(2)[1].assertIsDisplayed()
    }

    private fun setScreen(
        day: List<PlanReadRow>,
        week: List<PlanReadRow>,
        densities: List<Int> = List(7) { if (it == 1) day.size else 0 },
    ) {
        val read =
            WeekPlanRead(
                monday,
                selected,
                day,
                week,
                (0L..6L).map { PlanDayPresence(monday.plusDays(it), densities[it.toInt()]) },
            )
        compose.setContent {
            LifeTracingTheme {
                PlanScreen(PlanPresentationState(monday, selected, PlanLoad.Content(read)), {}, {})
            }
        }
    }

    @Suppress("LongParameterList")
    private fun row(
        id: String,
        target: PlanTarget,
        noLive: Boolean = false,
        sequence: Boolean = false,
        source: PlanSourceState = PlanSourceState.CURRENT,
        engaged: Boolean = false,
        fulfilled: Boolean = false,
        overdue: Boolean = false,
        timerTarget: Duration? = null,
    ): PlanReadRow {
        val at = Instant.parse("2026-09-15T10:00:00Z")
        val plan =
            PlanEntry(
                PlanEntryId(id),
                if (sequence) PlanTrackableKind.SEQUENCE else PlanTrackableKind.ACTIVITY,
                ActivityTemplateId("activity-$id").takeUnless { sequence },
                SequenceTemplateId("sequence-$id").takeIf { sequence },
                1,
                ActivitySnapshotId("activity-snapshot-$id").takeUnless { sequence },
                SequenceSnapshotId("sequence-snapshot-$id").takeIf { sequence },
                target,
                if (fulfilled) PlanEntryStatus.FULFILLED else PlanEntryStatus.PLANNED,
                ActivityExecutionId("execution-$id").takeIf { fulfilled && !sequence },
                null,
                at,
                at,
                null,
                at.takeIf { fulfilled },
            )
        return PlanReadRow(
            plan,
            selected.takeUnless { target is PlanTarget.Week },
            (target as? PlanTarget.ExactDay)?.let { LocalTime.of(12, 30) },
            id,
            null,
            source,
            engaged,
            overdue,
            PlanActivityRowMetadata(
                when {
                    noLive -> TimeTrackingMode.NO_LIVE_TRACKING
                    timerTarget != null -> TimeTrackingMode.TIMER
                    else -> TimeTrackingMode.STOPWATCH
                },
                timerTarget,
            ).takeUnless { sequence },
        )
    }
}
