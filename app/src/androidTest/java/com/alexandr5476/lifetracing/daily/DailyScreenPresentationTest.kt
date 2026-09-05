@file:Suppress(
    "LargeClass",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
)

package com.alexandr5476.lifetracing.daily

import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActiveSessionValidator
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionTransitions
import com.alexandr5476.lifetracing.domain.ActivityExecutionValidator
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyActiveSequenceState
import com.alexandr5476.lifetracing.domain.DailyPlan
import com.alexandr5476.lifetracing.domain.DailyPlanSnapshot
import com.alexandr5476.lifetracing.domain.DailyRead
import com.alexandr5476.lifetracing.domain.DailySequenceOccurrence
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceMaterializer
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceExecutionFactory
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionValidator
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceRuntimeEngine
import com.alexandr5476.lifetracing.domain.SequenceRuntimeState
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class DailyScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun header_empty_idle_failure_and_temporal_order_are_explicit() {
        val actions = mutableListOf<DailyAction>()
        val selected = LocalDate.parse("2026-08-19")
        val harness = screen(presentation(selected, DailyDateRelation.PAST, DailyLoadState.Loading), actions)
        composeTestRule.onNodeWithText(localizedDate(selected)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_loading)).assertIsDisplayed()
        composeTestRule.onNodeWithText("\u2039").assertIsDisplayed()
        composeTestRule.onNodeWithText("\u203A").assertIsDisplayed()

        harness.state.value =
            presentation(
                LocalDate.parse("2026-08-20"),
                DailyDateRelation.TODAY,
                DailyLoadState.Empty(emptyDaily),
            )
        composeTestRule.onNodeWithText(string(R.string.daily_no_plans)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_no_completed)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_active)).assertDoesNotExist()

        val planned = plan("Today idle plan", target = PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")))
        val completed = completedActivity("Today completed fact", Duration.ofMinutes(5))
        val active = activity("Today active", TimeTrackingMode.STOPWATCH)
        harness.state.value =
            DailyPresentationState(
                LocalDate.parse("2026-08-20"),
                DailyDateRelation.TODAY,
                DailyLoadState.Content(
                    DailyRead(listOf(planned), emptyList(), listOf(completed), DailyActive.Activity(active)),
                ),
                runtimeDisplayBaseline = baseline(active, 1),
            )
        assertSectionAbove(R.string.daily_active, R.string.daily_planned)
        assertSectionAbove(R.string.daily_planned, R.string.daily_completed)

        harness.state.value =
            state(DailyDateRelation.TODAY, DailyRead(listOf(planned), emptyList(), listOf(completed), null))
        assertSectionAbove(R.string.daily_planned, R.string.daily_completed)
        composeTestRule.onNodeWithText(string(R.string.daily_active)).assertDoesNotExist()

        val failedDate = LocalDate.parse("2026-08-21")
        harness.state.value =
            presentation(failedDate, DailyDateRelation.FUTURE, DailyLoadState.Failure("offline"))
        composeTestRule.onNodeWithText(localizedDate(failedDate)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_retry)).performClick()
        assertEquals(listOf(DailyAction.Retry), actions)

        harness.state.value =
            state(DailyDateRelation.PAST, DailyRead(listOf(planned), emptyList(), listOf(completed), null))
        assertSectionAbove(R.string.daily_completed, R.string.daily_planned)
        harness.state.value =
            state(DailyDateRelation.FUTURE, DailyRead(listOf(planned), emptyList(), listOf(completed), null))
        assertSectionAbove(R.string.daily_planned, R.string.daily_completed)
        harness.state.value =
            state(DailyDateRelation.FUTURE, DailyRead(listOf(planned), emptyList(), emptyList(), null))
        composeTestRule.onNodeWithText("Today completed fact").assertDoesNotExist()

        actions.clear()
        composeTestRule.onNodeWithContentDescription(string(R.string.daily_previous_day)).performClick()
        composeTestRule.onNodeWithContentDescription(string(R.string.daily_next_day)).performClick()
        composeTestRule.onNodeWithText(string(R.string.daily_return_today)).performClick()
        assertEquals(listOf(DailyAction.PreviousDay, DailyAction.NextDay, DailyAction.Today), actions)
    }

    @Test
    fun plans_keep_original_context_lifecycle_source_and_absent_comment_semantics() {
        val overdueDay = plan("Past day plan", overdue = true)
        val overdueWeek =
            plan(
                "Past week plan",
                target = PlanTarget.Week(LocalDate.parse("2026-08-17")),
                overdue = true,
            )
        val exact =
            plan(
                "Exact plan",
                target = PlanTarget.ExactDay(at, ZoneOffset.UTC),
                exactTime = LocalTime.of(10, 30),
                sourceState = PlanSourceState.CHANGED,
            )
        val harness =
            screen(
                state(
                    DailyDateRelation.PAST,
                    DailyRead(listOf(overdueDay, exact), listOf(overdueWeek), emptyList(), null),
                    selectedDate = LocalDate.parse("2026-08-19"),
                ),
            )

        composeTestRule.onNodeWithText("Past day plan").fetchSemanticsNode()
        composeTestRule.onNodeWithText(string(R.string.daily_floating_day)).fetchSemanticsNode()
        composeTestRule.onAllNodesWithText(string(R.string.daily_plan_planned)).assertCountEquals(4)
        composeTestRule.onAllNodesWithText(string(R.string.daily_plan_overdue)).assertCountEquals(2)
        composeTestRule
            .onNodeWithText(string(R.string.daily_week_of, localizedDate(LocalDate.parse("2026-08-17"))))
            .fetchSemanticsNode()
        composeTestRule
            .onNodeWithText(string(R.string.daily_exact_time, localizedTime(LocalTime.of(10, 30))))
            .fetchSemanticsNode()
        composeTestRule.onNodeWithText(string(R.string.daily_source_changed)).fetchSemanticsNode()
        composeTestRule.onNodeWithText("No comment").assertDoesNotExist()
        composeTestRule.onNodeWithText("Start").assertDoesNotExist()

        harness.state.value =
            state(
                DailyDateRelation.TODAY,
                DailyRead(listOf(plan("Only today's canonical plan")), emptyList(), emptyList(), null),
            )
        composeTestRule.onNodeWithText("Past day plan").assertDoesNotExist()
        composeTestRule.onNodeWithText("Past week plan").assertDoesNotExist()

        harness.state.value =
            state(
                DailyDateRelation.TODAY,
                DailyRead(listOf(plan("Engaged plan", engaged = true)), emptyList(), emptyList(), null),
            )
        composeTestRule.onNodeWithText(string(R.string.daily_plan_engaged)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.daily_plan_planned)).assertCountEquals(2)
        composeTestRule.onNodeWithText(string(R.string.daily_plan_fulfilled)).assertDoesNotExist()

        harness.state.value =
            state(
                DailyDateRelation.PAST,
                DailyRead(
                    listOf(plan("Fulfilled plan", status = PlanEntryStatus.FULFILLED)),
                    emptyList(),
                    emptyList(),
                    null,
                ),
            )
        composeTestRule.onNodeWithText(string(R.string.daily_plan_fulfilled)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_plan_engaged)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.daily_plan_overdue)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Start").assertDoesNotExist()
    }

    @Test
    fun completed_history_distinguishes_no_live_timed_and_terminal_sequence_roots() {
        val noLive = completedActivity("No-live fact", null, TimeTrackingMode.NO_LIVE_TRACKING)
        val timed = completedActivity("Timed fact", Duration.ofMinutes(5))
        val completedSequence = completedSequence("Completed sequence root", SequenceExecutionStatus.COMPLETED)
        val earlySequence = completedSequence("Early sequence root", SequenceExecutionStatus.ENDED_EARLY)
        screen(
            state(
                DailyDateRelation.PAST,
                DailyRead(emptyList(), emptyList(), listOf(noLive, timed, completedSequence, earlySequence), null),
            ),
        )

        composeTestRule.onNodeWithText("No-live fact").fetchSemanticsNode()
        composeTestRule.onNodeWithText("0:00").assertDoesNotExist()
        composeTestRule.onNodeWithText(durationText(300)).fetchSemanticsNode()
        composeTestRule.onNodeWithText(historyRange(timed)).fetchSemanticsNode()
        composeTestRule.onNodeWithText("Completed sequence root").fetchSemanticsNode()
        composeTestRule.onNodeWithText(durationText(240)).fetchSemanticsNode()
        composeTestRule
            .onAllNodesWithText(
                string(
                    R.string.daily_time_range,
                    localizedTime(completedSequence.startedAt),
                    localizedTime(completedSequence.completedAt),
                ),
            )[0]
            .fetchSemanticsNode()
        composeTestRule.onAllNodesWithText(string(R.string.daily_completed)).assertCountEquals(2)
        composeTestRule.onNodeWithText("Sequence child row").assertDoesNotExist()
        composeTestRule.onNodeWithText("Early sequence root").fetchSemanticsNode()
        composeTestRule.onNodeWithText(durationText(180)).fetchSemanticsNode()
        composeTestRule.onNodeWithText(string(R.string.daily_ended_early)).fetchSemanticsNode()
    }

    @Test
    fun daily_formatting_uses_effective_configuration_locale_and_recomposes() {
        val originalDefault = Locale.getDefault()
        val russian = Locale.forLanguageTag("ru")
        val selectedDate = LocalDate.parse("2026-08-20")
        val weekStart = LocalDate.parse("2026-08-17")
        val exactTime = LocalTime.of(10, 30)
        val noLive = completedActivity("Localized completion", null, TimeTrackingMode.NO_LIVE_TRACKING)
        val timed = completedActivity("Localized range", Duration.ofMinutes(10))
        val effectiveLocale = mutableStateOf(russian)
        try {
            Locale.setDefault(Locale.US)
            composeTestRule.setContent {
                val locale by effectiveLocale
                val configuration =
                    android.content.res.Configuration(composeTestRule.activity.resources.configuration).apply {
                        setLocale(locale)
                    }
                val context = composeTestRule.activity.createConfigurationContext(configuration)
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalContext provides context,
                ) {
                    LifeTracingTheme {
                        DailyScreen(
                            state(
                                DailyDateRelation.PAST,
                                DailyRead(
                                    listOf(
                                        plan(
                                            "Localized exact",
                                            target = PlanTarget.ExactDay(at, ZoneOffset.UTC),
                                            exactTime = exactTime,
                                        ),
                                    ),
                                    listOf(plan("Localized week", target = PlanTarget.Week(weekStart))),
                                    listOf(noLive, timed),
                                    null,
                                ),
                                selectedDate,
                            ),
                            onAction = {},
                        )
                    }
                }
            }

            assertDailyFormatting(russian, selectedDate, weekStart, exactTime, noLive, timed)

            composeTestRule.runOnIdle { effectiveLocale.value = Locale.US }
            assertDailyFormatting(Locale.US, selectedDate, weekStart, exactTime, noLive, timed)
            composeTestRule.onNodeWithText(localizedDate(selectedDate, russian)).assertDoesNotExist()

            Locale.setDefault(russian)
            composeTestRule.runOnIdle { effectiveLocale.value = Locale.UK }
            composeTestRule.onNodeWithText(localizedDate(selectedDate, Locale.UK)).assertIsDisplayed()
        } finally {
            Locale.setDefault(originalDefault)
        }
    }

    @Test
    fun activity_stopwatch_progresses_pauses_canonically_and_disables_in_flight_actions() {
        val actions = mutableListOf<DailyAction>()
        val running = activity("Running stopwatch", TimeTrackingMode.STOPWATCH)
        val runningBaseline = baseline(running, 2)
        val harness = screen(daily(DailyActive.Activity(running), runningBaseline), actions, 2_000)
        composeTestRule.onNodeWithText(durationText(2)).assertIsDisplayed()

        composeTestRule.runOnIdle { harness.tick.longValue = 5_000 }
        composeTestRule.onNodeWithText(durationText(5)).assertIsDisplayed()
        assertEquals(emptyList<DailyAction>(), actions)
        composeTestRule.onNodeWithText(string(R.string.daily_pause)).performClick()

        val paused = activity("Paused stopwatch", TimeTrackingMode.STOPWATCH, pausedAtSeconds = 3)
        val pausedBaseline = baseline(paused, 10)
        harness.state.value = daily(DailyActive.Activity(paused), pausedBaseline)
        composeTestRule.runOnIdle { harness.tick.longValue = 10_000 }
        composeTestRule.onNodeWithText(durationText(3)).assertIsDisplayed()
        composeTestRule.runOnIdle { harness.tick.longValue = 100_000 }
        composeTestRule.onNodeWithText(durationText(3)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_resume)).performClick()
        composeTestRule.onNodeWithText(string(R.string.daily_finish)).performClick()
        assertEquals(
            listOf(DailyAction.PauseActivity, DailyAction.ResumeActivity, DailyAction.FinishActivity),
            actions,
        )

        harness.state.value = daily(DailyActive.Activity(paused), pausedBaseline).copy(commandInFlight = true)
        composeTestRule.onNodeWithText(string(R.string.daily_resume)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.daily_finish)).assertIsNotEnabled()
    }

    @Test
    fun activity_timer_uses_remaining_then_overtime_and_missing_baseline_is_unavailable() {
        val timer = activity("Overtime timer", TimeTrackingMode.TIMER, TimerZeroBehavior.OVERTIME)
        val timerBaseline = baseline(timer, 2)
        val harness = screen(daily(DailyActive.Activity(timer), timerBaseline), tick = 2_000)
        composeTestRule.onNodeWithText(string(R.string.daily_remaining, durationText(3))).assertIsDisplayed()

        composeTestRule.runOnIdle { harness.tick.longValue = 7_000 }
        composeTestRule.onNodeWithText(string(R.string.daily_overtime, durationText(2))).assertIsDisplayed()

        harness.state.value = daily(DailyActive.Activity(timer), null)
        composeTestRule.onNodeWithText(string(R.string.daily_timing_unavailable)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_remaining, durationText(3))).assertDoesNotExist()
    }

    @Test
    fun sequence_current_stopwatch_and_total_progress_independently_then_freeze_when_paused() {
        val actions = mutableListOf<DailyAction>()
        val runningFixture = stopwatchAfterPriorStep(paused = false)
        val running = runningFixture.active()
        val runningBaseline = baseline(running.runtime, 12)
        val harness = screen(daily(running, runningBaseline), actions, 12_000)
        composeTestRule.onNodeWithText(string(R.string.daily_sequence_total, durationText(12))).assertIsDisplayed()
        composeTestRule.onNodeWithText(durationText(2)).assertIsDisplayed()

        composeTestRule.runOnIdle { harness.tick.longValue = 15_000 }
        composeTestRule.onNodeWithText(string(R.string.daily_sequence_total, durationText(15))).assertIsDisplayed()
        composeTestRule.onNodeWithText(durationText(5)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_complete_step)).performClick()
        assertEquals(
            running.current?.occurrence?.id,
            (actions.single() as DailyAction.CompleteCurrentSequenceStep).occurrenceId,
        )
        harness.state.value = daily(running, runningBaseline).copy(commandInFlight = true)
        composeTestRule.onNodeWithText(string(R.string.daily_pause)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.daily_complete_step)).assertIsNotEnabled()

        val pausedFixture = stopwatchAfterPriorStep(paused = true)
        val paused = pausedFixture.active()
        val pausedBaseline = baseline(paused.runtime, 20)
        harness.state.value = daily(paused, pausedBaseline)
        composeTestRule.runOnIdle { harness.tick.longValue = 20_000 }
        composeTestRule.onNodeWithText(string(R.string.daily_sequence_total, durationText(12))).assertIsDisplayed()
        composeTestRule.onNodeWithText(durationText(2)).assertIsDisplayed()
        composeTestRule.runOnIdle { harness.tick.longValue = 200_000 }
        composeTestRule.onNodeWithText(string(R.string.daily_sequence_total, durationText(12))).assertIsDisplayed()
        composeTestRule.onNodeWithText(durationText(2)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_resume)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_complete_step)).assertDoesNotExist()
    }

    @Test
    fun sequence_timer_uses_child_value_and_no_live_current_has_no_synthetic_timer() {
        val timerFixture =
            singleStepSequence(snapshot("Timer child", TimeTrackingMode.TIMER, TimerZeroBehavior.OVERTIME))
        val timer = timerFixture.active()
        val timerBaseline = baseline(timer.runtime, 2)
        val harness = screen(daily(timer, timerBaseline), tick = 2_000)
        composeTestRule.onNodeWithText(string(R.string.daily_remaining, durationText(3))).assertIsDisplayed()
        composeTestRule.runOnIdle { harness.tick.longValue = 7_000 }
        composeTestRule.onNodeWithText(string(R.string.daily_overtime, durationText(2))).assertIsDisplayed()

        val noLiveFixture = singleStepSequence(snapshot("No-live current", TimeTrackingMode.NO_LIVE_TRACKING))
        val noLive = noLiveFixture.active()
        harness.state.value = daily(noLive, baseline(noLive.runtime, 3))
        composeTestRule.runOnIdle { harness.tick.longValue = 3_000 }
        composeTestRule.onNodeWithText(string(R.string.daily_no_live_current_step)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_remaining, durationText(2))).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.daily_overtime, durationText(2))).assertDoesNotExist()
    }

    @Test
    fun waiting_and_transition_states_never_fabricate_a_current_child_or_countdown() {
        val waiting = waitingSequence().active()
        val harness = screen(daily(waiting, baseline(waiting.runtime, 4)), tick = 4_000)
        composeTestRule.onNodeWithText(string(R.string.daily_waiting)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_start_next)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting first").assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.daily_transition_countdown)).assertDoesNotExist()

        val runningCountdown = countdownSequence(paused = false).active()
        val runningBaseline = baseline(runningCountdown.runtime, 7)
        harness.state.value = daily(runningCountdown, runningBaseline)
        composeTestRule.runOnIdle { harness.tick.longValue = 7_000 }
        composeTestRule.onNodeWithText(string(R.string.daily_transition_countdown)).assertIsDisplayed()
        composeTestRule.onNodeWithText(durationText(3)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Countdown first").assertDoesNotExist()
        composeTestRule.runOnIdle { harness.tick.longValue = 8_000 }
        composeTestRule.onNodeWithText(durationText(2)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_pause)).assertIsDisplayed()

        val pausedCountdown = countdownSequence(paused = true).active()
        val pausedBaseline = baseline(pausedCountdown.runtime, 10)
        harness.state.value = daily(pausedCountdown, pausedBaseline)
        composeTestRule.runOnIdle { harness.tick.longValue = 10_000 }
        composeTestRule.onNodeWithText(durationText(3)).assertIsDisplayed()
        composeTestRule.runOnIdle { harness.tick.longValue = 100_000 }
        composeTestRule.onNodeWithText(durationText(3)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Countdown first").assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.daily_resume)).assertIsDisplayed()

        listOf("Go now", "Make next", "Runtime Add", "Do again", "Early End", "Expand").forEach {
            composeTestRule.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    private fun screen(
        state: DailyPresentationState,
        actions: MutableList<DailyAction> = mutableListOf(),
        tick: Long? = null,
    ): ScreenHarness {
        val stateHolder = mutableStateOf(state)
        val tickHolder = mutableLongStateOf(tick ?: 0)
        composeTestRule.setContent {
            LifeTracingTheme {
                DailyScreen(stateHolder.value, actions::add, tickHolder.value.takeIf { tick != null })
            }
        }
        return ScreenHarness(stateHolder, tickHolder)
    }

    private data class ScreenHarness(
        val state: androidx.compose.runtime.MutableState<DailyPresentationState>,
        val tick: androidx.compose.runtime.MutableLongState,
    )

    private fun presentation(
        selectedDate: LocalDate,
        relation: DailyDateRelation,
        load: DailyLoadState,
    ) = DailyPresentationState(selectedDate, relation, load)

    private fun state(
        relation: DailyDateRelation,
        daily: DailyRead,
        selectedDate: LocalDate = LocalDate.parse("2026-08-20"),
    ) = DailyPresentationState(selectedDate, relation, DailyLoadState.Content(daily))

    private fun daily(
        active: DailyActive,
        baseline: RuntimeDisplayBaseline?,
    ) = DailyPresentationState(
        LocalDate.parse("2026-08-20"),
        DailyDateRelation.TODAY,
        DailyLoadState.Content(DailyRead(emptyList(), emptyList(), emptyList(), active)),
        runtimeDisplayBaseline = baseline,
    )

    private fun activity(
        name: String,
        mode: TimeTrackingMode,
        zeroBehavior: TimerZeroBehavior = TimerZeroBehavior.FINISH,
        pausedAtSeconds: Long? = null,
    ): ActiveActivityRuntime {
        val snapshot = snapshot(name, mode, zeroBehavior)
        var execution =
            ActivityExecutionFactory {
                ActivityExecutionId(
                    name,
                )
            }.startTimed(snapshot, at, at, ZoneOffset.UTC)
        if (pausedAtSeconds != null) {
            execution =
                ActivityExecutionTransitions.pause(
                    execution,
                    ActivityExecutionPauseId("pause-$name"),
                    instant(pausedAtSeconds),
                )
        }
        ActivityExecutionValidator.requireValid(execution, snapshot)
        val session =
            ActiveSession(
                ActiveSessionKind.ACTIVITY,
                if (pausedAtSeconds == null) ActiveSessionState.RUNNING else ActiveSessionState.PAUSED,
                execution.id,
                null,
                execution.updatedAt,
            )
        ActiveSessionValidator.requireValid(session)
        return ActiveActivityRuntime(session, execution, snapshot)
    }

    private fun snapshot(
        name: String,
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        zeroBehavior: TimerZeroBehavior = TimerZeroBehavior.FINISH,
        timerSeconds: Long = 5,
    ) = ActivityConfigSnapshot(
        ActivitySnapshotId(name),
        name,
        null,
        mode,
        if (mode == TimeTrackingMode.TIMER) Duration.ofSeconds(timerSeconds) else null,
        null,
        null,
        null,
        false,
        at,
        ActivityTemplateSettings(timerZeroBehavior = zeroBehavior),
    )

    private fun plan(
        title: String,
        target: PlanTarget = PlanTarget.FloatingDay(LocalDate.parse("2026-08-19")),
        status: PlanEntryStatus = PlanEntryStatus.PLANNED,
        exactTime: LocalTime? = null,
        sourceState: PlanSourceState = PlanSourceState.CURRENT,
        engaged: Boolean = false,
        overdue: Boolean = false,
    ): DailyPlan {
        val snapshot = snapshot(title)
        val plan =
            PlanEntry(
                PlanEntryId(title),
                PlanTrackableKind.ACTIVITY,
                null,
                null,
                null,
                snapshot.id,
                null,
                target,
                status,
                null,
                null,
                at,
                at,
                null,
                if (status == PlanEntryStatus.FULFILLED) at else null,
            )
        return DailyPlan(
            plan,
            LocalDate.parse("2026-08-19"),
            exactTime,
            DailyPlanSnapshot.Activity(snapshot),
            sourceState,
            engaged,
            overdue,
        )
    }

    private fun completedActivity(
        title: String,
        duration: Duration?,
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
    ): CompletedActivityHistoryRoot =
        CompletedActivityHistoryRoot(
            ActivityExecutionId(title),
            ActivitySnapshotId(title),
            LocalDate.parse("2026-08-20"),
            instant(600),
            at.takeIf { mode != TimeTrackingMode.NO_LIVE_TRACKING },
            duration,
            null,
            title,
            null,
            mode,
            null,
        )

    private fun completedSequence(
        title: String,
        status: SequenceExecutionStatus,
    ): CompletedSequenceHistoryRoot {
        val startOffset = if (status == SequenceExecutionStatus.COMPLETED) 100L else 200L
        val active =
            if (status == SequenceExecutionStatus.COMPLETED) {
                Duration.ofMinutes(4)
            } else {
                Duration.ofMinutes(3)
            }
        return CompletedSequenceHistoryRoot(
            SequenceExecutionId(title),
            SequenceSnapshotId(title),
            LocalDate.parse("2026-08-20"),
            instant(startOffset + 600),
            instant(startOffset),
            status,
            active,
            Duration.ofMinutes(10).minus(active),
            Duration.ofMinutes(10),
            null,
            title,
            null,
        )
    }

    private fun stopwatchAfterPriorStep(paused: Boolean): SequenceFixture {
        val first = snapshot("Prior timer", TimeTrackingMode.TIMER, timerSeconds = 10)
        val second = snapshot("Current stopwatch")
        var fixture = sequenceFixture(listOf(first, second), countdownSeconds = 0)
        fixture =
            fixture.copy(
                state = fixture.engine.reconcile(fixture.state, fixture.snapshot, fixture.activities, instant(10)),
            )
        if (paused) {
            fixture =
                fixture.copy(
                    state = fixture.engine.pause(fixture.state, instant(12), fixture.snapshot, fixture.activities),
                )
        }
        return fixture
    }

    private fun singleStepSequence(activity: ActivityConfigSnapshot): SequenceFixture =
        sequenceFixture(listOf(activity), countdownSeconds = 0)

    private fun waitingSequence(): SequenceFixture {
        val first = snapshot("Waiting first")
        val next = snapshot("Waiting next")
        var fixture = sequenceFixture(listOf(first, next), countdownSeconds = 0, autoAdvance = false)
        fixture =
            fixture.copy(
                state =
                    fixture.engine.completeCurrent(
                        fixture.state,
                        requireNotNull(fixture.state.execution.currentOccurrenceId),
                        instant(1),
                        fixture.snapshot,
                        fixture.activities,
                    ),
            )
        return fixture
    }

    private fun countdownSequence(paused: Boolean): SequenceFixture {
        val first = snapshot("Countdown first", TimeTrackingMode.TIMER)
        val next = snapshot("Countdown next")
        var fixture = sequenceFixture(listOf(first, next), countdownSeconds = 5)
        fixture =
            fixture.copy(
                state = fixture.engine.reconcile(fixture.state, fixture.snapshot, fixture.activities, instant(5)),
            )
        if (paused) {
            fixture =
                fixture.copy(
                    state = fixture.engine.pause(fixture.state, instant(7), fixture.snapshot, fixture.activities),
                )
        }
        return fixture
    }

    private fun sequenceFixture(
        activities: List<ActivityConfigSnapshot>,
        countdownSeconds: Long,
        autoAdvance: Boolean = true,
    ): SequenceFixture {
        var occurrenceId = 0
        var childId = 0
        var pauseId = 0
        var intervalId = 0
        val engine =
            SequenceRuntimeEngine(
                SequenceExecutionFactory(
                    { SequenceExecutionId("sequence-execution") },
                    RuntimeOccurrenceMaterializer { SequenceOccurrenceId("occurrence-${++occurrenceId}") },
                ),
                ActivityExecutionFactory { ActivityExecutionId("child-${++childId}") },
                { ActivityExecutionPauseId("child-pause-${++pauseId}") },
                { SequenceIntervalId("interval-${++intervalId}") },
                { SequenceOccurrenceId("occurrence-${++occurrenceId}") },
            )
        val snapshot =
            SequenceConfigSnapshot(
                SequenceSnapshotId("sequence"),
                "Sequence",
                null,
                null,
                null,
                null,
                at,
                SequenceSnapshotSettings(
                    autoAdvance,
                    Duration.ZERO,
                    Duration.ofSeconds(countdownSeconds),
                    true,
                    true,
                    false,
                    true,
                    true,
                    NoLiveTimeAccounting.ACTIVE,
                ),
                nodes =
                    activities.mapIndexed { index, activity ->
                        SequenceSnapshotActivityStep(
                            SequenceSnapshotNodeId("step-$index"),
                            index,
                            activity.id,
                        )
                    },
            )
        val activityMap = activities.associateBy(ActivityConfigSnapshot::id)
        return SequenceFixture(
            engine,
            snapshot,
            activityMap,
            engine.start(snapshot, activityMap, at, at, ZoneOffset.UTC),
        )
    }

    private data class SequenceFixture(
        val engine: SequenceRuntimeEngine,
        val snapshot: SequenceConfigSnapshot,
        val activities: Map<ActivitySnapshotId, ActivityConfigSnapshot>,
        val state: SequenceRuntimeState,
    ) {
        fun active(): DailyActive.Sequence {
            SequenceExecutionValidator.requireValid(state.execution, snapshot)
            state.children.forEach { (occurrenceId, child) ->
                val occurrence = state.execution.occurrences.single { it.id == occurrenceId }
                ActivityExecutionValidator.requireValid(child, activities.getValue(occurrence.activitySnapshotId))
            }
            val current = state.execution.currentOccurrenceId
            val open = state.execution.intervals.single { it.endedAt == null }
            val presentationState =
                when {
                    state.execution.status == SequenceExecutionStatus.PAUSED && current != null ->
                        DailyActiveSequenceState.PAUSED_CURRENT
                    state.execution.status == SequenceExecutionStatus.PAUSED ->
                        DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
                    current != null -> DailyActiveSequenceState.RUNNING_CURRENT
                    open.kind == SequenceIntervalKind.IMPLICIT_IDLE -> DailyActiveSequenceState.WAITING_NEXT
                    else -> DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN
                }
            val sessionState =
                when (presentationState) {
                    DailyActiveSequenceState.PAUSED_CURRENT,
                    DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
                    -> ActiveSessionState.PAUSED
                    DailyActiveSequenceState.WAITING_NEXT -> ActiveSessionState.WAITING_NEXT
                    else -> ActiveSessionState.RUNNING
                }
            val session =
                ActiveSession(
                    ActiveSessionKind.SEQUENCE,
                    sessionState,
                    null,
                    state.execution.id,
                    state.execution.updatedAt,
                )
            ActiveSessionValidator.requireValid(session)
            val next =
                state.execution.occurrences
                    .filter { it.status == RuntimeOccurrenceStatus.NOT_STARTED }
                    .minByOrNull { it.runtimePosition }
            val runtime =
                ActiveSequenceRuntime(
                    session,
                    state.execution,
                    snapshot,
                    activities,
                    state.currentChild,
                    next?.id.takeIf {
                        presentationState == DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN ||
                            presentationState == DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
                    },
                )

            fun view(occurrenceId: SequenceOccurrenceId?): DailySequenceOccurrence? =
                occurrenceId?.let { id ->
                    val occurrence = state.execution.occurrences.single { it.id == id }
                    DailySequenceOccurrence(
                        occurrence,
                        activities.getValue(occurrence.activitySnapshotId),
                        NextRuntimeDeadlineResolver.effectiveSettings(runtime, occurrence),
                    )
                }
            return DailyActive.Sequence(runtime, presentationState, view(current), view(next?.id))
        }
    }

    private fun baseline(
        runtime: com.alexandr5476.lifetracing.domain.ActiveRuntime,
        observedAtSeconds: Long,
    ): RuntimeDisplayBaseline =
        RuntimeDisplayBaseline
            .capture(
                runtime,
                WallMonotonicAnchor(instant(observedAtSeconds), observedAtSeconds * 1_000),
                observedAtSeconds * 1_000,
            ).also { require(it.matches(runtime)) }

    private fun assertSectionAbove(
        first: Int,
        second: Int,
    ) {
        val firstTop =
            composeTestRule
                .onAllNodesWithText(string(first))[0]
                .fetchSemanticsNode()
                .boundsInRoot.top
        val secondTop =
            composeTestRule
                .onAllNodesWithText(string(second))[0]
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("Expected ${string(first)} above ${string(second)}", firstTop < secondTop)
    }

    private fun historyRange(root: CompletedActivityHistoryRoot): String =
        string(
            R.string.daily_time_range,
            localizedTime(requireNotNull(root.startedAt)),
            localizedTime(root.completedAt),
        )

    private fun assertDailyFormatting(
        locale: Locale,
        selectedDate: LocalDate,
        weekStart: LocalDate,
        exactTime: LocalTime,
        noLive: CompletedActivityHistoryRoot,
        timed: CompletedActivityHistoryRoot,
    ) {
        composeTestRule.onNodeWithText(localizedDate(selectedDate, locale)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(localizedString(locale, R.string.daily_week_of, localizedDate(weekStart, locale)))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(localizedString(locale, R.string.daily_exact_time, localizedTime(exactTime, locale)))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                localizedString(
                    locale,
                    R.string.daily_completed_at,
                    localizedTime(noLive.completedAt, locale),
                ),
            ).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                localizedString(
                    locale,
                    R.string.daily_time_range,
                    localizedTime(requireNotNull(timed.startedAt), locale),
                    localizedTime(timed.completedAt, locale),
                ),
            ).assertIsDisplayed()
    }

    private fun localizedDate(
        date: LocalDate,
        locale: Locale = effectiveLocale(),
    ): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)

    private fun localizedTime(
        time: LocalTime,
        locale: Locale = effectiveLocale(),
    ): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)

    private fun localizedTime(
        instant: Instant,
        locale: Locale = effectiveLocale(),
    ): String =
        DateTimeFormatter
            .ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(instant.atZone(ZoneId.systemDefault()))

    private fun effectiveLocale(): Locale = composeTestRule.activity.resources.configuration.locales[0]

    private fun durationText(seconds: Long): String = DateUtils.formatElapsedTime(seconds)

    private fun instant(seconds: Long): Instant = at.plusSeconds(seconds)

    private fun string(
        id: Int,
        vararg arguments: Any,
    ): String = composeTestRule.activity.getString(id, *arguments)

    private fun localizedString(
        locale: Locale,
        id: Int,
        vararg arguments: Any,
    ): String {
        val configuration = android.content.res.Configuration(composeTestRule.activity.resources.configuration)
        configuration.setLocale(locale)
        return composeTestRule.activity.createConfigurationContext(configuration).getString(id, *arguments)
    }

    private companion object {
        val at: Instant = Instant.parse("2026-08-20T10:00:00Z")
        val emptyDaily = DailyRead(emptyList(), emptyList(), emptyList(), null)
    }
}
