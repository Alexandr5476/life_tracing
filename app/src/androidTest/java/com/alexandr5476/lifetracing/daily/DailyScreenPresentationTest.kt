@file:Suppress("LongMethod", "LongParameterList", "MagicNumber")

package com.alexandr5476.lifetracing.daily

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyActiveSequenceState
import com.alexandr5476.lifetracing.domain.DailyPlan
import com.alexandr5476.lifetracing.domain.DailyPlanSnapshot
import com.alexandr5476.lifetracing.domain.DailyRead
import com.alexandr5476.lifetracing.domain.DailySequenceOccurrence
import com.alexandr5476.lifetracing.domain.EffectiveSequenceStepSettings
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline
import com.alexandr5476.lifetracing.domain.RuntimeOccurrence
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class DailyScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loading_failure_and_temporal_sections_keep_header_and_dispatch_expected_actions() {
        val selected = LocalDate.parse("2026-08-20")
        val actions = mutableListOf<DailyAction>()
        val harness = screen(DailyPresentationState(selected, DailyDateRelation.TODAY, DailyLoadState.Loading), actions)
        composeTestRule.onNodeWithText(string(R.string.daily_loading)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_title)).assertIsDisplayed()

        harness.state.value =
            DailyPresentationState(selected, DailyDateRelation.TODAY, DailyLoadState.Failure("offline"))
        composeTestRule.onNodeWithText(string(R.string.daily_retry)).performClick()
        assertEquals(listOf(DailyAction.Retry), actions)

        val planned = plan("today-plan")
        val completed = completedActivity("done", Duration.ofMinutes(5))
        harness.state.value =
            state(DailyDateRelation.PAST, DailyRead(listOf(planned), emptyList(), listOf(completed), null))
        composeTestRule.onNodeWithText(string(R.string.daily_completed)).assertIsDisplayed()
        harness.state.value =
            state(DailyDateRelation.FUTURE, DailyRead(listOf(planned), emptyList(), listOf(completed), null))
        composeTestRule.onAllNodesWithText(string(R.string.daily_planned))[0].assertIsDisplayed()
    }

    @Test
    fun plans_and_completed_roots_preserve_canonical_metadata_without_actions() {
        val overdue = plan("past frozen", overdue = true)
        val week = plan("week frozen", target = PlanTarget.Week(LocalDate.parse("2026-08-17")))
        val engaged = plan("engaged frozen", engaged = true)
        val fulfilled = plan("fulfilled frozen", status = PlanEntryStatus.FULFILLED)
        val exact =
            plan("exact frozen", target = PlanTarget.ExactDay(at, ZoneOffset.UTC), exactTime = LocalTime.of(10, 30))
        val noLive = completedActivity("no live", null)
        val early = completedSequence("sequence root", SequenceExecutionStatus.ENDED_EARLY)
        screen(
            state(
                DailyDateRelation.PAST,
                DailyRead(listOf(overdue, week, engaged, fulfilled, exact), emptyList(), listOf(noLive, early), null),
            ),
        )

        listOf(
            "past frozen",
            "week frozen",
            "engaged frozen",
            "fulfilled frozen",
            "exact frozen",
            "no live",
            "sequence root",
        ).forEach { composeTestRule.onAllNodesWithText(it)[0].fetchSemanticsNode() }
        listOf(
            string(R.string.daily_plan_planned),
            string(R.string.daily_plan_overdue),
            string(R.string.daily_plan_engaged),
            string(R.string.daily_plan_fulfilled),
            string(R.string.daily_ended_early),
        ).forEach { composeTestRule.onAllNodesWithText(it)[0].fetchSemanticsNode() }
        val weekPrefix = string(R.string.daily_week_of).substringBefore("%")
        composeTestRule.onNodeWithText(weekPrefix, substring = true).fetchSemanticsNode()
        composeTestRule.onNodeWithText("0:00").assertDoesNotExist()
        composeTestRule.onNodeWithText("Start", substring = true).assertDoesNotExist()
    }

    @Test
    fun activity_baselines_tick_without_actions_and_buttons_honor_in_flight_state() {
        val runtime = activity("stopwatch", TimeTrackingMode.STOPWATCH, ActiveSessionState.RUNNING)
        val baseline = RuntimeDisplayBaseline.capture(runtime, WallMonotonicAnchor(at, 0), 0)
        val actions = mutableListOf<DailyAction>()
        val harness = screen(daily(DailyActive.Activity(runtime), baseline), actions, 1_000)
        composeTestRule
            .onNodeWithText(
                android.text.format.DateUtils
                    .formatElapsedTime(1),
            ).assertIsDisplayed()
        composeTestRule.runOnIdle { harness.tick.longValue = 2_000 }
        composeTestRule
            .onNodeWithText(
                android.text.format.DateUtils
                    .formatElapsedTime(2),
            ).assertIsDisplayed()
        assertEquals(emptyList<DailyAction>(), actions)
        composeTestRule.onNodeWithText(string(R.string.daily_pause)).performClick()
        assertEquals(listOf(DailyAction.PauseActivity), actions)

        harness.state.value =
            daily(
                DailyActive.Activity(activity("paused", TimeTrackingMode.STOPWATCH, ActiveSessionState.PAUSED)),
                baseline,
            ).copy(commandInFlight = true)
        composeTestRule.onNodeWithText(string(R.string.daily_resume)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.daily_finish)).assertIsNotEnabled()
        harness.state.value = daily(DailyActive.Activity(runtime), null)
        composeTestRule.onNodeWithText(string(R.string.daily_timing_unavailable)).assertIsDisplayed()
    }

    @Test
    fun five_sequence_states_render_only_their_valid_basic_controls() {
        val actions = mutableListOf<DailyAction>()
        val cases =
            listOf(
                DailyActiveSequenceState.RUNNING_CURRENT to string(R.string.daily_complete_step),
                DailyActiveSequenceState.PAUSED_CURRENT to string(R.string.daily_resume),
                DailyActiveSequenceState.WAITING_NEXT to string(R.string.daily_start_next),
                DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN to string(R.string.daily_pause),
                DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN to string(R.string.daily_resume),
            )
        val first = sequence(cases.first().first)
        val harness =
            screen(
                daily(first, RuntimeDisplayBaseline.capture(first.runtime, WallMonotonicAnchor(at, 0), 0)),
                actions,
                1_000,
            )
        cases.forEach { (sequenceState, actionText) ->
            val active = sequence(sequenceState)
            val baseline =
                if (sequenceState == DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN ||
                    sequenceState == DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
                ) {
                    null
                } else {
                    RuntimeDisplayBaseline.capture(active.runtime, WallMonotonicAnchor(at, 0), 0)
                }
            harness.state.value = daily(active, baseline)
            composeTestRule.onNodeWithText("Sequence").assertIsDisplayed()
            composeTestRule.onNodeWithText(actionText).assertIsDisplayed()
            if (sequenceState == DailyActiveSequenceState.WAITING_NEXT ||
                sequenceState == DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN ||
                sequenceState == DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
            ) {
                composeTestRule.onNodeWithText("Current step").assertDoesNotExist()
            }
        }
        val running = sequence(DailyActiveSequenceState.RUNNING_CURRENT)
        harness.state.value =
            daily(running, RuntimeDisplayBaseline.capture(running.runtime, WallMonotonicAnchor(at, 0), 0))
        composeTestRule.onNodeWithText(string(R.string.daily_complete_step)).performClick()
        assertEquals(
            SequenceOccurrenceId("current"),
            (actions.last() as DailyAction.CompleteCurrentSequenceStep).occurrenceId,
        )
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

    private fun state(
        relation: DailyDateRelation,
        daily: DailyRead,
        commandInFlight: Boolean = false,
    ) = DailyPresentationState(
        LocalDate.parse("2026-08-20"),
        relation,
        DailyLoadState.Content(daily),
        commandInFlight = commandInFlight,
    )

    private fun daily(
        active: DailyActive,
        baseline: RuntimeDisplayBaseline?,
    ) = DailyRead(emptyList(), emptyList(), emptyList(), active).let {
        DailyPresentationState(
            LocalDate.parse("2026-08-20"),
            DailyDateRelation.TODAY,
            DailyLoadState.Content(it),
            runtimeDisplayBaseline = baseline,
        )
    }

    private fun activity(
        name: String,
        mode: TimeTrackingMode,
        sessionState: ActiveSessionState,
    ): ActiveActivityRuntime {
        val snapshot = snapshot(name, mode)
        val execution =
            ActivityExecutionFactory {
                ActivityExecutionId(
                    name,
                )
            }.startTimed(snapshot, at, at, ZoneOffset.UTC)
        return ActiveActivityRuntime(
            ActiveSession(ActiveSessionKind.ACTIVITY, sessionState, execution.id, null, execution.updatedAt),
            execution,
            snapshot,
        )
    }

    private fun snapshot(
        name: String,
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
    ) = ActivityConfigSnapshot(
        ActivitySnapshotId(name),
        name,
        null,
        mode,
        if (mode == TimeTrackingMode.TIMER) Duration.ofSeconds(5) else null,
        null,
        null,
        null,
        false,
        at,
        ActivityTemplateSettings(),
    )

    private fun plan(
        title: String,
        target: PlanTarget = PlanTarget.FloatingDay(LocalDate.parse("2026-08-19")),
        status: PlanEntryStatus = PlanEntryStatus.PLANNED,
        exactTime: LocalTime? = null,
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
            PlanSourceState.CURRENT,
            engaged,
            overdue,
        )
    }

    private fun completedActivity(
        title: String,
        duration: Duration?,
    ): CompletedHistoryRoot =
        CompletedActivityHistoryRoot(
            ActivityExecutionId(title),
            ActivitySnapshotId(title),
            LocalDate.parse("2026-08-20"),
            at.plusSeconds(600),
            at,
            duration,
            null,
            title,
            null,
            TimeTrackingMode.STOPWATCH,
            null,
        )

    private fun completedSequence(
        title: String,
        status: SequenceExecutionStatus,
    ): CompletedHistoryRoot =
        CompletedSequenceHistoryRoot(
            com.alexandr5476.lifetracing.domain
                .SequenceExecutionId(title),
            SequenceSnapshotId(title),
            LocalDate.parse("2026-08-20"),
            at.plusSeconds(600),
            at,
            status,
            Duration.ofMinutes(5),
            Duration.ZERO,
            Duration.ofMinutes(10),
            null,
            title,
            null,
        )

    private fun sequence(state: DailyActiveSequenceState): DailyActive.Sequence {
        val currentId = SequenceOccurrenceId("current")
        val step = snapshot("Current step")
        val nextActivity = snapshot("Next step")
        val current =
            DailySequenceOccurrence(
                RuntimeOccurrence(
                    currentId,
                    null,
                    step.id,
                    0,
                    null,
                    null,
                    RuntimeOccurrenceStatus.CURRENT,
                    at,
                    null,
                    null,
                    false,
                    false,
                ),
                step,
                EffectiveSequenceStepSettings(
                    Duration.ofSeconds(5),
                    com.alexandr5476.lifetracing.domain.TimerZeroBehavior.FINISH,
                    true,
                    true,
                    false,
                ),
            )
        val next =
            DailySequenceOccurrence(
                RuntimeOccurrence(
                    SequenceOccurrenceId("next"),
                    null,
                    nextActivity.id,
                    1,
                    null,
                    null,
                    RuntimeOccurrenceStatus.NOT_STARTED,
                    null,
                    null,
                    null,
                    false,
                    false,
                ),
                nextActivity,
                current.effectiveSettings,
            )
        val hasCurrent =
            state == DailyActiveSequenceState.RUNNING_CURRENT || state == DailyActiveSequenceState.PAUSED_CURRENT
        val execution =
            com.alexandr5476.lifetracing.domain.SequenceExecution(
                com.alexandr5476.lifetracing.domain
                    .SequenceExecutionId("sequence"),
                SequenceSnapshotId("sequence"),
                null,
                if (state == DailyActiveSequenceState.PAUSED_CURRENT ||
                    state == DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
                ) {
                    SequenceExecutionStatus.PAUSED
                } else {
                    SequenceExecutionStatus.RUNNING
                },
                at,
                null,
                null,
                null,
                null,
                ZoneOffset.UTC,
                0,
                LocalDate.parse("2026-08-20"),
                currentId.takeIf { hasCurrent },
                at,
                at,
                listOf(current.occurrence, next.occurrence),
            )
        val runtime =
            com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime(
                ActiveSession(
                    ActiveSessionKind.SEQUENCE,
                    when (state) {
                        DailyActiveSequenceState.PAUSED_CURRENT,
                        DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
                        -> ActiveSessionState.PAUSED
                        DailyActiveSequenceState.WAITING_NEXT -> ActiveSessionState.WAITING_NEXT
                        else -> ActiveSessionState.RUNNING
                    },
                    null,
                    execution.id,
                    at,
                ),
                execution,
                com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot(
                    SequenceSnapshotId("sequence"),
                    "Sequence",
                    null,
                    null,
                    null,
                    null,
                    at,
                    com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings(
                        true,
                        Duration.ZERO,
                        Duration.ZERO,
                        true,
                        true,
                        false,
                        true,
                        true,
                        com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting.ACTIVE,
                    ),
                ),
                mapOf(step.id to step, nextActivity.id to nextActivity),
                null,
                next.occurrence.id.takeIf { !hasCurrent && state != DailyActiveSequenceState.WAITING_NEXT },
            )
        return DailyActive.Sequence(runtime, state, current.takeIf { hasCurrent }, next)
    }

    private fun string(id: Int): String = composeTestRule.activity.getString(id)

    private companion object {
        val at: Instant = Instant.parse("2026-08-20T10:00:00Z")
    }
}
