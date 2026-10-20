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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyActiveSequenceState
import com.alexandr5476.lifetracing.domain.DailyPlan
import com.alexandr5476.lifetracing.domain.DailyPlanSnapshot
import com.alexandr5476.lifetracing.domain.DailyRead
import com.alexandr5476.lifetracing.domain.DailySequenceOccurrence
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
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
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
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
import java.util.TimeZone

class DailyScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(composeTestRule)

    @Test
    fun repeated_hour_labels_use_canonical_occurrence_offsets() {
        val zone = ZoneId.of("Europe/Berlin")
        val date = LocalDate.parse("2026-10-25")
        val entries =
            listOf("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z").mapIndexed { index, text ->
                val instant = Instant.parse(text)
                val occurrence = instant.atZone(zone)
                plan(
                    "Overlap $index",
                    target = PlanTarget.ExactDay(instant, ZoneOffset.UTC),
                    exactTime = occurrence.toLocalTime(),
                ).copy(
                    effectiveLocalDate = occurrence.toLocalDate(),
                    exactUtcOffset = occurrence.offset,
                )
            }
        screen(state(DailyDateRelation.TODAY, DailyRead(entries, emptyList(), emptyList(), null), selectedDate = date))
        val clock =
            LocalTime
                .of(
                    2,
                    30,
                ).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(effectiveLocale()))
        for (offset in listOf("+02:00", "+01:00")) {
            val attributed = string(R.string.calendar_time_with_offset, clock, offset)
            composeTestRule.onNodeWithText(string(R.string.daily_exact_time, attributed)).assertExists()
        }
    }

    @Test
    fun header_empty_idle_failure_and_temporal_order_are_explicit() {
        val actions = mutableListOf<DailyAction>()
        val selected = LocalDate.parse("2026-08-19")
        val harness = screen(presentation(selected, DailyDateRelation.PAST, DailyLoadState.Loading), actions)
        composeTestRule.onNodeWithText(localizedDate(selected)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_loading)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.daily_previous_day)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.daily_next_day)).assertIsDisplayed()

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
    fun dailyShellExposesStatisticsActionInAdaptiveActionLayout() {
        var opened = false
        composeTestRule.setContent {
            LifeTracingTheme {
                DailyScreen(
                    presentation(LocalDate.parse("2026-08-20"), DailyDateRelation.TODAY, DailyLoadState.Loading),
                    {},
                    onStatistics = { opened = true },
                )
            }
        }
        composeTestRule.onNodeWithTag("daily-shell-actions").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_statistics)).assertIsDisplayed().performClick()
        assertTrue(opened)
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
        composeTestRule.onAllNodesWithText(string(R.string.plan_start_action)).assertCountEquals(3)

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
    fun dayExactAndWeekRowsExposeFrozenModeActionsAndExactClickedIdentity() {
        val day = plan("No-live day", overdue = true, mode = TimeTrackingMode.NO_LIVE_TRACKING)
        val exact =
            plan(
                "Timed exact",
                target = PlanTarget.ExactDay(at, ZoneOffset.UTC),
                exactTime = LocalTime.of(10, 30),
                sourceState = PlanSourceState.ARCHIVED,
            )
        val week = sequencePlan("Sequence week", LocalDate.parse("2026-08-17"))
        val engaged = plan("Engaged", engaged = true)
        val fulfilled = plan("Frozen fulfilled Plan", status = PlanEntryStatus.FULFILLED)
        val clicked = mutableListOf<PlanActionIdentity>()
        screen(
            state(
                DailyDateRelation.TODAY,
                DailyRead(
                    listOf(day, exact, engaged, fulfilled),
                    listOf(week),
                    emptyList(),
                    DailyActive.Activity(activity("Unrelated live", TimeTrackingMode.STOPWATCH)),
                ),
            ),
            planExecutions = clicked,
        )

        composeTestRule
            .onNodeWithContentDescription(
                string(R.string.plan_execution_action, day.snapshot.title, string(R.string.plan_complete_action)),
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule
            .onNodeWithContentDescription(
                string(R.string.plan_execution_action, week.snapshot.title, string(R.string.plan_start_action)),
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(day.plan.actionIdentity(), week.plan.actionIdentity()), clicked)
    }

    @Test
    fun completedOverlapClocksStayOriginalAndLocalizedAfterDeviceZoneChange() {
        val previousZone = TimeZone.getDefault()
        val locale = mutableStateOf(Locale.US)
        val generation = mutableStateOf(0)
        val zone = ZoneId.of("Europe/Berlin")
        val first = Instant.parse("2026-10-25T00:30:00Z")
        val second = Instant.parse("2026-10-25T01:30:00Z")
        val date = LocalDate.parse("2026-10-25")
        val timed =
            completedActivity("Crossing timed", Duration.ofHours(1)).copy(
                primaryLocalDate = date,
                startedAt = first,
                completedAt = second,
                originalZoneId = zone,
                originalUtcOffsetMinutes = 120,
            )
        val noLive =
            completedActivity("Second completion", null, TimeTrackingMode.NO_LIVE_TRACKING).copy(
                primaryLocalDate = date,
                completedAt = second,
                originalZoneId = zone,
                originalUtcOffsetMinutes = 60,
            )
        val sequence =
            completedSequence("Crossing sequence", SequenceExecutionStatus.ENDED_EARLY).copy(
                primaryLocalDate = date,
                startedAt = first,
                completedAt = second,
                originalZoneId = zone,
                originalUtcOffsetMinutes = 120,
                activeDuration = Duration.ofHours(1),
                pauseDuration = Duration.ZERO,
                wallDuration = Duration.ofHours(1),
            )
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            composeTestRule.setContent {
                val configuration = android.content.res.Configuration(composeTestRule.activity.resources.configuration)
                configuration.setLocale(locale.value)
                val context = composeTestRule.activity.createConfigurationContext(configuration)
                CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context) {
                    LifeTracingTheme {
                        DailyScreen(
                            state(
                                DailyDateRelation.PAST,
                                DailyRead(
                                    emptyList(),
                                    emptyList(),
                                    listOf(timed.copy(title = "Crossing timed ${generation.value}"), noLive, sequence),
                                    null,
                                ),
                                date,
                            ),
                            onAction = {},
                        )
                    }
                }
            }
            for (language in listOf(Locale.US, Locale.forLanguageTag("ru"))) {
                composeTestRule.runOnIdle { locale.value = language }
                val clock =
                    DateTimeFormatter
                        .ofLocalizedTime(FormatStyle.SHORT)
                        .withLocale(language)
                        .format(LocalTime.of(2, 30))
                val earlier = "$clock (UTC+02:00, Europe/Berlin)"
                val later = "$clock (UTC+01:00, Europe/Berlin)"
                val range = localizedString(language, R.string.daily_time_range, earlier, later)
                composeTestRule.onAllNodesWithText(range).assertCountEquals(2)
                composeTestRule.onAllNodesWithText(range)[0].performScrollTo().assertIsDisplayed()
                composeTestRule
                    .onNodeWithText(localizedString(language, R.string.daily_completed_at, later))
                    .performScrollTo()
                    .assertIsDisplayed()
                composeTestRule
                    .onNodeWithText(localizedString(language, R.string.daily_ended_early))
                    .performScrollTo()
                    .assertIsDisplayed()
                composeTestRule.runOnIdle {
                    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
                    generation.value++
                }
                composeTestRule.onAllNodesWithText(range).assertCountEquals(2)
                composeTestRule.onNodeWithText("0:00").assertDoesNotExist()
            }
        } finally {
            TimeZone.setDefault(previousZone)
        }
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
            composeTestRule
                .onNodeWithText(localizedDate(selectedDate, Locale.UK))
                .performScrollTo()
                .assertIsDisplayed()
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
    fun stopwatch_honors_frozen_show_seconds_while_timer_keeps_second_precision() {
        val standalone = activity("Minute stopwatch", TimeTrackingMode.STOPWATCH, showSeconds = false)
        val harness = screen(daily(DailyActive.Activity(standalone), baseline(standalone, 65)), tick = 65_000)
        composeTestRule.onNodeWithText("0:01").assertIsDisplayed()

        val sequence = singleStepSequence(snapshot("Sequence minute stopwatch", showSeconds = false)).active()
        harness.state.value = daily(sequence, baseline(sequence.runtime, 65))
        composeTestRule.runOnIdle { harness.tick.longValue = 65_000 }
        composeTestRule.onNodeWithText("0:01").assertIsDisplayed()

        val timer = activity("Precise timer", TimeTrackingMode.TIMER, showSeconds = false)
        harness.state.value = daily(DailyActive.Activity(timer), baseline(timer, 2))
        composeTestRule.runOnIdle { harness.tick.longValue = 2_000 }
        composeTestRule.onNodeWithText(string(R.string.daily_remaining, durationText(3))).assertIsDisplayed()
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
        val expanded = mutableListOf<SequenceExecutionId>()
        val harness = screen(daily(running, runningBaseline), actions, 12_000, expanded)
        composeTestRule.onNodeWithText(string(R.string.daily_sequence_total, durationText(12))).assertIsDisplayed()
        composeTestRule.onNodeWithText(durationText(2)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("daily-expand-sequence").performClick()
        assertEquals(listOf(running.runtime.execution.id), expanded)

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
    fun sequence_timer_and_no_live_current_use_their_frozen_values() {
        val timerFixture =
            singleStepSequence(snapshot("Timer child", TimeTrackingMode.TIMER, TimerZeroBehavior.OVERTIME))
        val timer = timerFixture.active()
        val timerBaseline = baseline(timer.runtime, 2)
        val harness = screen(daily(timer, timerBaseline), tick = 2_000)
        composeTestRule.onNodeWithText(string(R.string.daily_remaining, durationText(3))).assertIsDisplayed()
        composeTestRule.runOnIdle { harness.tick.longValue = 7_000 }
        composeTestRule.onNodeWithText(string(R.string.daily_overtime, durationText(2))).assertIsDisplayed()

        val noLiveWithMainValue =
            singleStepSequence(
                snapshot("No-live with value", TimeTrackingMode.NO_LIVE_TRACKING).copy(
                    fields =
                        listOf(
                            ActivitySnapshotField(
                                ActivitySnapshotFieldId("pages"),
                                null,
                                0,
                                "Pages",
                                type = CustomFieldType.NUMBER,
                                unit = "pages",
                                displayPrecision = 0,
                                defaultNumberScaled = 0,
                                isMainValue = true,
                            ),
                        ),
                ),
            ).active()
        harness.state.value = daily(noLiveWithMainValue, baseline(noLiveWithMainValue.runtime, 3))
        composeTestRule.runOnIdle { harness.tick.longValue = 3_000 }
        composeTestRule.onNodeWithText("0 pages").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.daily_no_live_current_step)).assertDoesNotExist()

        val noLiveWithoutMainValue =
            singleStepSequence(
                snapshot("No-live current", TimeTrackingMode.NO_LIVE_TRACKING),
            ).active()
        harness.state.value = daily(noLiveWithoutMainValue, baseline(noLiveWithoutMainValue.runtime, 3))
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

        listOf("Go now", "Make next", "Runtime Add", "Do again", "Early End").forEach {
            composeTestRule.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
        composeTestRule.onNodeWithText(string(R.string.daily_expand_sequence)).assertIsDisplayed()
    }

    @Test
    fun accessibilityHeadingsDatesAndFeedbackRecoverWithoutStaleActions() {
        val actions = mutableListOf<DailyAction>()
        val running = activity("Accessible frozen Activity", TimeTrackingMode.STOPWATCH)
        val harness = screen(daily(DailyActive.Activity(running), baseline(running, 2)), actions, 2_000)
        listOf(R.string.daily_title, R.string.daily_active, R.string.daily_planned, R.string.daily_completed).forEach {
            composeTestRule
                .onNode(hasText(string(it)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .assertExists()
        }
        composeTestRule
            .onNodeWithContentDescription(
                string(R.string.daily_date_today, localizedDate(harness.state.value.selectedDate)),
            ).assertExists()
        assertSectionAbove(R.string.daily_active, R.string.daily_planned)
        assertSectionAbove(R.string.daily_planned, R.string.daily_completed)
        composeTestRule.onNodeWithContentDescription(string(R.string.daily_previous_day)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f),
        )
        composeTestRule.onNodeWithContentDescription(string(R.string.daily_next_day)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 2f),
        )
        assertSingleLabeledAction(string(R.string.daily_pause))
        for (unmerged in listOf(false, true)) {
            composeTestRule
                .onAllNodes(
                    hasClickAction() and hasContentDescription(string(R.string.daily_previous_day)),
                    unmerged,
                ).assertCountEquals(1)
        }
        harness.state.value = harness.state.value.copy(load = DailyLoadState.Loading)
        composeTestRule.onNodeWithText(string(R.string.daily_loading)).assertExists()
        runtimeAction(R.string.daily_pause).assertDoesNotExist()
        runtimeAction(R.string.daily_finish).assertDoesNotExist()
        harness.state.value = harness.state.value.copy(load = DailyLoadState.Failure("read failure"))
        composeTestRule.onNodeWithText(string(R.string.daily_read_failure)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        runtimeAction(R.string.daily_retry).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(DailyAction.Retry), actions)
        harness.state.value = harness.state.value.copy(load = DailyLoadState.Empty(emptyDaily))
        composeTestRule.onNodeWithText(string(R.string.daily_loading)).assertDoesNotExist()
        runtimeAction(R.string.daily_retry).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.daily_no_plans)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.daily_no_completed)).assertExists()
        harness.state.value = daily(DailyActive.Activity(running), baseline(running, 2))
        runtimeAction(R.string.daily_pause).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(DailyAction.Retry, DailyAction.PauseActivity), actions)
        for ((failure, resource) in listOf(
            DailyCommandFailure.Rejected("stale") to R.string.daily_command_rejected,
            DailyCommandFailure.Coordination("committed") to R.string.daily_command_coordinating,
        )) {
            harness.state.value = harness.state.value.copy(commandFailure = failure)
            composeTestRule.onNodeWithText(string(resource)).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            )
        }
        composeTestRule
            .onNodeWithContentDescription(string(R.string.daily_previous_day))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule
            .onNodeWithContentDescription(string(R.string.daily_next_day))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(
            listOf(DailyAction.Retry, DailyAction.PauseActivity, DailyAction.PreviousDay, DailyAction.NextDay),
            actions,
        )
        for ((relation, resource) in listOf(
            DailyDateRelation.PAST to R.string.daily_date_past,
            DailyDateRelation.FUTURE to R.string.daily_date_future,
        )) {
            harness.state.value = harness.state.value.copy(dateRelation = relation, commandFailure = null)
            composeTestRule
                .onNodeWithContentDescription(
                    string(resource, localizedDate(harness.state.value.selectedDate)),
                ).assertExists()
        }
    }

    @Test
    fun accessibilityActivityTimingAndActionsFollowCanonicalState() {
        val actions = mutableListOf<DailyAction>()
        val running = activity("Frozen stopwatch", TimeTrackingMode.STOPWATCH, showSeconds = false)
        val harness = screen(daily(DailyActive.Activity(running), baseline(running, 65)), actions, 65_000)
        composeTestRule
            .onNodeWithContentDescription(
                string(R.string.daily_stopwatch_elapsed, string(R.string.daily_duration_minutes, 0, 1)),
            ).assertExists()
        composeTestRule.onNodeWithText(string(R.string.daily_running)).assertExists()
        runtimeAction(R.string.daily_resume).assertDoesNotExist()
        runtimeAction(R.string.daily_pause).performSemanticsAction(SemanticsActions.OnClick)
        val paused = activity("Frozen stopwatch", TimeTrackingMode.STOPWATCH, pausedAtSeconds = 65, showSeconds = false)
        harness.state.value = daily(DailyActive.Activity(paused), baseline(paused, 70))
        composeTestRule.runOnIdle { harness.tick.longValue = 700_000 }
        composeTestRule.onNodeWithText(string(R.string.daily_paused)).assertExists()
        composeTestRule
            .onNodeWithContentDescription(
                string(R.string.daily_stopwatch_elapsed, string(R.string.daily_duration_minutes, 0, 1)),
            ).assertExists()
        runtimeAction(R.string.daily_pause).assertDoesNotExist()
        runtimeAction(R.string.daily_resume).performSemanticsAction(SemanticsActions.OnClick)
        runtimeAction(R.string.daily_finish).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(DailyAction.PauseActivity, DailyAction.ResumeActivity, DailyAction.FinishActivity), actions)
        harness.state.value = harness.state.value.copy(commandInFlight = true)
        runtimeAction(R.string.daily_resume).assertIsNotEnabled()
        runtimeAction(R.string.daily_finish).assertIsNotEnabled()
        harness.state.value = harness.state.value.copy(runtimeDisplayBaseline = null)
        composeTestRule
            .onNodeWithContentDescription(
                string(R.string.daily_stopwatch_elapsed, string(R.string.daily_timing_unavailable)),
            ).assertExists()
        harness.state.value = state(DailyDateRelation.TODAY, emptyDaily)
        runtimeAction(R.string.daily_resume).assertDoesNotExist()
        runtimeAction(R.string.daily_finish).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.daily_active)).assertDoesNotExist()
    }

    @Test
    fun accessibilityTimerBoundariesRemainPassiveAndUnannounced() {
        val actions = mutableListOf<DailyAction>()
        val timer = activity("Frozen Timer", TimeTrackingMode.TIMER, TimerZeroBehavior.OVERTIME)
        val harness = screen(daily(DailyActive.Activity(timer), baseline(timer, 2)), actions, 2_000)
        for ((tick, expected) in listOf(
            2_000L to string(R.string.daily_remaining, spokenSeconds(3)),
            5_000L to string(R.string.daily_timer_complete),
            7_000L to string(R.string.daily_overtime, spokenSeconds(2)),
        )) {
            composeTestRule.runOnIdle { harness.tick.longValue = tick }
            val node = composeTestRule.onNodeWithContentDescription(string(R.string.daily_timer_value, expected))
            node.assertExists()
            assertNoAnnouncingAncestor(node.fetchSemanticsNode())
            assertEquals(emptyList<DailyAction>(), actions)
            assertEquals(
                timer,
                (harness.state.value.load as DailyLoadState.Content).daily.active.let {
                    (it as DailyActive.Activity).runtime
                },
            )
        }
        harness.state.value = daily(DailyActive.Activity(timer), null)
        composeTestRule
            .onNodeWithContentDescription(
                string(R.string.daily_timer_value, string(R.string.daily_timing_unavailable)),
            ).assertExists()
        val paused = activity("Frozen Timer", TimeTrackingMode.TIMER, TimerZeroBehavior.OVERTIME, pausedAtSeconds = 2)
        harness.state.value = daily(DailyActive.Activity(paused), baseline(paused, 8))
        composeTestRule.runOnIdle { harness.tick.longValue = 100_000 }
        composeTestRule
            .onNodeWithContentDescription(
                string(R.string.daily_timer_value, string(R.string.daily_remaining, spokenSeconds(3))),
            ).assertExists()
    }

    @Test
    fun accessibilitySequenceStatesExposeOnlyCanonicalActions() {
        val actions = mutableListOf<DailyAction>()
        val expanded = mutableListOf<SequenceExecutionId>()
        val variants =
            listOf(
                Triple(
                    stopwatchAfterPriorStep(false).active(),
                    R.string.daily_sequence_running_current,
                    listOf(R.string.daily_pause, R.string.daily_complete_step),
                ),
                Triple(
                    stopwatchAfterPriorStep(true).active(),
                    R.string.daily_sequence_paused_current,
                    listOf(R.string.daily_resume),
                ),
                Triple(waitingSequence().active(), R.string.daily_waiting, listOf(R.string.daily_start_next)),
                Triple(
                    countdownSequence(false).active(),
                    R.string.daily_sequence_running_transition,
                    listOf(R.string.daily_pause),
                ),
                Triple(
                    countdownSequence(true).active(),
                    R.string.daily_sequence_paused_transition,
                    listOf(R.string.daily_resume),
                ),
            )
        val harness = screen(daily(variants.first().first, null), actions, 20_000, expanded)
        val allControls =
            listOf(R.string.daily_pause, R.string.daily_resume, R.string.daily_complete_step, R.string.daily_start_next)
        for ((active, stateLabel, legal) in variants) {
            harness.state.value = daily(active, baseline(active.runtime, 20))
            composeTestRule.onNodeWithText(active.runtime.snapshot.name).assertExists()
            composeTestRule.onNodeWithText(string(stateLabel)).assertExists()
            active.current?.let {
                composeTestRule
                    .onNodeWithContentDescription(
                        string(R.string.daily_current_occurrence, it.occurrence.runtimePosition + 1, it.activity.name),
                    ).assertExists()
            }
            for (control in allControls) {
                if (control in legal) {
                    runtimeAction(control).performSemanticsAction(SemanticsActions.OnClick)
                } else {
                    runtimeAction(control).assertDoesNotExist()
                }
            }
            val expected =
                when (active.state) {
                    DailyActiveSequenceState.RUNNING_CURRENT ->
                        listOf(
                            DailyAction.PauseSequence,
                            DailyAction.CompleteCurrentSequenceStep(active.current!!.occurrence.id),
                        )
                    DailyActiveSequenceState.PAUSED_CURRENT, DailyActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN ->
                        listOf(DailyAction.ResumeSequence)
                    DailyActiveSequenceState.WAITING_NEXT -> listOf(DailyAction.StartNextSequenceStep)
                    DailyActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN -> listOf(DailyAction.PauseSequence)
                }
            assertEquals(expected, actions.toList())
            actions.clear()
            runtimeAction(R.string.daily_expand_sequence).performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(active.runtime.execution.id, expanded.last())
            harness.state.value = harness.state.value.copy(commandInFlight = true)
            legal.forEach { runtimeAction(it).assertIsNotEnabled() }
            runtimeAction(R.string.daily_expand_sequence).assertIsNotEnabled()
            if (active.state == DailyActiveSequenceState.WAITING_NEXT) {
                composeTestRule
                    .onAllNodes(
                        hasContentDescription(string(R.string.daily_current_step, ""), substring = true),
                    ).assertCountEquals(0)
            }
        }
    }

    @Test
    fun accessibilitySequenceMissingAndNoLiveDataStayHonest() {
        val noLive = singleStepSequence(snapshot("No-live step", TimeTrackingMode.NO_LIVE_TRACKING)).active()
        val harness = screen(daily(noLive, baseline(noLive.runtime, 3)), tick = 3_000)
        composeTestRule
            .onNodeWithContentDescription(
                string(
                    R.string.daily_current_step,
                    string(R.string.daily_no_live_value, string(R.string.daily_no_main_value)),
                ),
            ).assertExists()
        val field =
            ActivitySnapshotField(
                ActivitySnapshotFieldId("accessible-pages"),
                null,
                0,
                "Pages",
                type = CustomFieldType.NUMBER,
                unit = "pages",
                displayPrecision = 0,
                defaultNumberScaled = 0,
                isMainValue = true,
            )
        val valued =
            singleStepSequence(
                snapshot("Valued step", TimeTrackingMode.NO_LIVE_TRACKING).copy(fields = listOf(field)),
            ).active()
        harness.state.value = daily(valued, baseline(valued.runtime, 3))
        composeTestRule
            .onNodeWithContentDescription(
                string(
                    R.string.daily_current_step,
                    string(R.string.daily_no_live_value, "0 pages"),
                ),
            ).assertExists()
        val running = stopwatchAfterPriorStep(false).active()
        harness.state.value = daily(running.copy(current = null), null)
        runtimeAction(R.string.daily_complete_step).assertDoesNotExist()
        composeTestRule.onAllNodesWithText(string(R.string.daily_timing_unavailable)).assertCountEquals(2)
        val timer = singleStepSequence(snapshot("Missing child", TimeTrackingMode.TIMER)).active()
        harness.state.value =
            daily(timer.copy(runtime = timer.runtime.copy(currentChild = null)), baseline(timer.runtime, 2))
        composeTestRule
            .onNodeWithContentDescription(
                string(
                    R.string.daily_current_step,
                    string(R.string.daily_timer_value, string(R.string.daily_timing_unavailable)),
                ),
            ).assertExists()
        val countdown = countdownSequence(true).active()
        harness.state.value = daily(countdown, null)
        composeTestRule
            .onNodeWithContentDescription(
                string(
                    R.string.daily_countdown_remaining,
                    string(R.string.daily_timing_unavailable),
                ),
            ).assertExists()
    }

    @Test
    fun accessibilityPlansAndHistoryPreserveContextAndIdentity() {
        val execute = mutableListOf<PlanActionIdentity>()
        val exact =
            plan(
                "Frozen exact",
                target = PlanTarget.ExactDay(at, ZoneOffset.ofHours(-5)),
                exactTime = LocalTime.of(5, 0),
            ).copy(effectiveLocalDate = LocalDate.parse("2026-08-20"), exactUtcOffset = ZoneOffset.ofHours(-5))
        val engaged = plan("Engaged", engaged = true)
        val fulfilled = plan("Frozen fulfilled Plan", status = PlanEntryStatus.FULFILLED)
        val cancelled = plan("Frozen cancelled Plan", status = PlanEntryStatus.CANCELLED)
        val plans =
            listOf(
                exact,
                engaged,
                fulfilled,
                cancelled,
                plan("Changed", sourceState = PlanSourceState.CHANGED, overdue = true),
                plan("Archived", sourceState = PlanSourceState.ARCHIVED),
                plan("Unavailable", sourceState = PlanSourceState.UNAVAILABLE),
            )
        val history =
            listOf(
                completedActivity("No-live history", null, TimeTrackingMode.NO_LIVE_TRACKING),
                completedActivity("Timed history", Duration.ofSeconds(65)),
                completedSequence("Ended history", SequenceExecutionStatus.ENDED_EARLY),
            )
        screen(state(DailyDateRelation.TODAY, DailyRead(plans, emptyList(), history, null)), planExecutions = execute)
        for (p in plans) {
            val action =
                composeTestRule.onNode(
                    hasClickAction() and hasAnyAncestor(hasAnyChild(hasText(p.snapshot.title))),
                )
            // Each card retains independent native controls and readable lifecycle text.
            if (p.engaged || p.plan.status != PlanEntryStatus.PLANNED) {
                action.assertDoesNotExist()
            } else {
                action.performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(p.plan.actionIdentity(), execute.last())
            }
        }
        listOf(
            R.string.daily_plan_engaged,
            R.string.daily_plan_fulfilled,
            R.string.daily_plan_cancelled,
            R.string.daily_plan_overdue,
            R.string.daily_source_changed,
            R.string.daily_source_archived,
            R.string.daily_source_unavailable,
            R.string.daily_ended_early,
            R.string.daily_history_no_duration,
        ).forEach {
            composeTestRule.onNodeWithText(string(it)).assertExists()
        }
        composeTestRule
            .onNodeWithText(
                string(
                    R.string.daily_exact_time,
                    string(R.string.calendar_time_with_offset, localizedTime(LocalTime.of(5, 0)), "-05:00"),
                ),
            ).assertExists()
        composeTestRule
            .onNodeWithContentDescription(
                string(R.string.daily_history_duration, spokenSeconds(65)),
            ).assertExists()
        composeTestRule.onNodeWithText(historyRange(history[1] as CompletedActivityHistoryRoot)).assertExists()
    }

    @Test
    fun accessibilityEnglishAndRussianReflowAtCombinedScales() {
        val locale = mutableStateOf(Locale.US)
        val systemFont = mutableFloatStateOf(2f)
        val appearance = mutableStateOf(AppearancePreferences(interfaceScalePercent = 120, textScalePercent = 130))
        val running = activity("Scaled Activity", TimeTrackingMode.STOPWATCH)
        val state = mutableStateOf(daily(DailyActive.Activity(running), baseline(running, 2)))
        val actions = mutableListOf<DailyAction>()
        composeTestRule.setContent {
            val configuration =
                android.content.res.Configuration(composeTestRule.activity.resources.configuration).apply {
                    setLocale(locale.value)
                    fontScale = systemFont.floatValue
                }
            val context = composeTestRule.activity.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalContext provides context,
                LocalDensity provides
                    androidx.compose.ui.unit
                        .Density(density.density, configuration.fontScale),
            ) {
                LifeTracingAppearance(appearance.value) {
                    DailyScreen(state.value, actions::add, displayElapsedRealtimeMs = 2_000)
                }
            }
        }
        val sequence = stopwatchAfterPriorStep(false).active()
        val pausedSequence = stopwatchAfterPriorStep(true).active()
        val paused = activity("Scaled Activity", TimeTrackingMode.STOPWATCH, pausedAtSeconds = 2)
        for (language in listOf(Locale.US, Locale.forLanguageTag("ru"))) {
            composeTestRule.runOnIdle {
                locale.value = language
                state.value = daily(DailyActive.Activity(running), baseline(running, 2))
            }
            composeTestRule
                .onNodeWithContentDescription(localizedString(language, R.string.daily_previous_day))
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithContentDescription(localizedString(language, R.string.daily_next_day))
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithContentDescription(
                    localizedString(
                        language,
                        R.string.daily_date_today,
                        localizedDate(state.value.selectedDate, language),
                    ),
                ).assertExists()
            composeTestRule
                .onNodeWithContentDescription(
                    localizedString(
                        language,
                        R.string.daily_stopwatch_elapsed,
                        localizedString(language, R.string.daily_duration_seconds, 0, 0, 2),
                    ),
                ).assertExists()
            activateScaledControls(language, listOf(R.string.daily_pause, R.string.daily_finish))
            state.value = daily(DailyActive.Activity(paused), baseline(paused, 2))
            composeTestRule
                .onNode(hasText(localizedString(language, R.string.daily_pause)) and hasClickAction())
                .assertDoesNotExist()
            activateScaledControls(language, listOf(R.string.daily_resume, R.string.daily_finish))
            state.value = daily(sequence, baseline(sequence.runtime, 12))
            activateScaledControls(language, listOf(R.string.daily_pause, R.string.daily_complete_step))
            composeTestRule
                .onNode(
                    hasText(localizedString(language, R.string.daily_expand_sequence)) and hasClickAction(),
                ).performScrollTo()
                .assertIsDisplayed()
            state.value = daily(pausedSequence, baseline(pausedSequence.runtime, 20))
            activateScaledControls(language, listOf(R.string.daily_resume))
        }
        val perLocale =
            listOf(
                DailyAction.PauseActivity,
                DailyAction.FinishActivity,
                DailyAction.ResumeActivity,
                DailyAction.FinishActivity,
                DailyAction.PauseSequence,
                DailyAction.CompleteCurrentSequenceStep(sequence.current!!.occurrence.id),
                DailyAction.ResumeSequence,
            )
        composeTestRule.runOnIdle {
            locale.value = Locale.US
            systemFont.floatValue = 1f
            appearance.value = AppearancePreferences(interfaceScalePercent = 90, textScalePercent = 90)
            state.value = daily(DailyActive.Activity(running), baseline(running, 2))
        }
        activateScaledControls(Locale.US, listOf(R.string.daily_pause, R.string.daily_finish))
        assertEquals(perLocale + perLocale + listOf(DailyAction.PauseActivity, DailyAction.FinishActivity), actions)
    }

    @Test
    fun accessibilityKeyboardFocusRecoversOnTerminalRemovalWithoutFollowingTicksOrRefreshes() {
        val actions = mutableListOf<DailyAction>()
        val running = activity("Focus timer", TimeTrackingMode.TIMER)
        val content = daily(DailyActive.Activity(running), baseline(running, 2))
        val harness = screen(content, actions, 2_000)
        keyboard.enter()
        val finish = runtimeAction(R.string.daily_finish)
        finish.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        for (tick in listOf(4_999L, 5_000L, 5_001L)) {
            composeTestRule.runOnIdle { harness.tick.longValue = tick }
            finish.assertIsFocused()
            assertTrue(actions.isEmpty())
        }
        val history = composeTestRule.onNode(hasText(string(R.string.daily_history)) and hasClickAction())
        history.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        harness.state.value = content.copy(load = DailyLoadState.Loading)
        history.assertIsFocused()
        harness.state.value = content
        history.assertIsFocused()
        harness.state.value =
            content.copy(
                selectedDate = content.selectedDate.plusDays(1),
                load = DailyLoadState.Empty(DailyRead(emptyList(), emptyList(), emptyList(), null)),
            )
        history.assertIsFocused()
        harness.state.value = content
        history.assertIsFocused()
        finish.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        harness.state.value =
            content.copy(load = DailyLoadState.Empty(DailyRead(emptyList(), emptyList(), emptyList(), null)))
        composeTestRule.onNodeWithContentDescription(string(R.string.daily_previous_day)).assertIsFocused()
        assertTrue(actions.isEmpty())
        runtimeAction(R.string.daily_finish).assertDoesNotExist()
        harness.visible.value = false
        history.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        harness.state.value = content
        history.assertIsFocused()
        harness.state.value =
            content.copy(load = DailyLoadState.Empty(DailyRead(emptyList(), emptyList(), emptyList(), null)))
        history.assertIsFocused()
        harness.visible.value = true
        composeTestRule.onNodeWithContentDescription(string(R.string.daily_previous_day)).assertIsFocused()
        assertTrue(actions.isEmpty())
    }

    private fun activateScaledControls(
        locale: Locale,
        controls: List<Int>,
    ) {
        for (control in controls) {
            val label = localizedString(locale, control)
            val node = composeTestRule.onNode(hasText(label) and hasClickAction())
            node.performScrollTo().assertIsDisplayed()
            val touch = node.fetchSemanticsNode().touchBoundsInRoot
            val minimumPx = 48 * composeTestRule.activity.resources.displayMetrics.density
            assertTrue("Touch height must be at least 48 Android dp", touch.height >= minimumPx - 1)
            assertTrue("Touch width must be at least 48 Android dp", touch.width >= minimumPx - 1)
            node.performSemanticsAction(SemanticsActions.OnClick)
            assertSingleLabeledAction(label)
        }
        if (controls.size == 2) {
            val first =
                composeTestRule
                    .onNode(hasText(localizedString(locale, controls[0])) and hasClickAction())
                    .getUnclippedBoundsInRoot()
            val second =
                composeTestRule
                    .onNode(hasText(localizedString(locale, controls[1])) and hasClickAction())
                    .getUnclippedBoundsInRoot()
            assertTrue("Runtime targets must not overlap", first.bottom <= second.top || first.right <= second.left)
        }
    }

    private fun assertSingleLabeledAction(label: String) {
        // Material merges the Text label into its button only in the merged tree.
        val action = hasClickAction() and (hasText(label) or hasAnyDescendant(hasText(label)))
        for (unmerged in listOf(false, true)) {
            composeTestRule.onAllNodes(action, useUnmergedTree = unmerged).assertCountEquals(1)
        }
        composeTestRule.onAllNodes(hasText(label), useUnmergedTree = true).assertCountEquals(1)
    }

    private fun runtimeAction(resource: Int) = composeTestRule.onNode(hasText(string(resource)) and hasClickAction())

    private fun spokenSeconds(seconds: Int) =
        string(
            R.string.daily_duration_seconds,
            seconds / 3600,
            seconds / 60 % 60,
            seconds % 60,
        )

    private fun assertNoAnnouncingAncestor(node: androidx.compose.ui.semantics.SemanticsNode) {
        var current: androidx.compose.ui.semantics.SemanticsNode? = node
        while (current != null) {
            assertTrue(
                "Timer or ancestor must not announce ticks",
                !current.config.contains(SemanticsProperties.LiveRegion),
            )
            current = current.parent
        }
    }

    private fun screen(
        state: DailyPresentationState,
        actions: MutableList<DailyAction> = mutableListOf(),
        tick: Long? = null,
        expanded: MutableList<SequenceExecutionId> = mutableListOf(),
        planExecutions: MutableList<PlanActionIdentity> = mutableListOf(),
        onStatistics: () -> Unit = {},
    ): ScreenHarness {
        val stateHolder = mutableStateOf(state)
        val tickHolder = mutableLongStateOf(tick ?: 0)
        val visibleHolder = mutableStateOf(true)
        composeTestRule.setContent {
            LifeTracingTheme {
                DailyScreen(
                    stateHolder.value,
                    actions::add,
                    tickHolder.value.takeIf { tick != null },
                    onExpandSequence = expanded::add,
                    onExecutePlan = planExecutions::add,
                    onStatistics = onStatistics,
                    isRouteVisible = visibleHolder.value,
                )
            }
        }
        return ScreenHarness(stateHolder, tickHolder, visibleHolder)
    }

    private data class ScreenHarness(
        val state: androidx.compose.runtime.MutableState<DailyPresentationState>,
        val tick: androidx.compose.runtime.MutableLongState,
        val visible: androidx.compose.runtime.MutableState<Boolean>,
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
        showSeconds: Boolean = true,
    ): ActiveActivityRuntime {
        val snapshot = snapshot(name, mode, zeroBehavior, showSeconds = showSeconds)
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
        showSeconds: Boolean = true,
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
        ActivityTemplateSettings(showSeconds = showSeconds, timerZeroBehavior = zeroBehavior),
    )

    private fun plan(
        title: String,
        target: PlanTarget = PlanTarget.FloatingDay(LocalDate.parse("2026-08-19")),
        status: PlanEntryStatus = PlanEntryStatus.PLANNED,
        exactTime: LocalTime? = null,
        sourceState: PlanSourceState = PlanSourceState.CURRENT,
        engaged: Boolean = false,
        overdue: Boolean = false,
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
    ): DailyPlan {
        val snapshot = snapshot(title, mode)
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

    private fun sequencePlan(
        title: String,
        weekStart: LocalDate,
    ): DailyPlan {
        val snapshotId = SequenceSnapshotId(title)
        val plan =
            PlanEntry(
                PlanEntryId(title),
                PlanTrackableKind.SEQUENCE,
                null,
                null,
                null,
                null,
                snapshotId,
                PlanTarget.Week(weekStart),
                PlanEntryStatus.PLANNED,
                null,
                null,
                at,
                at,
                null,
                null,
            )
        return DailyPlan(
            plan,
            LocalDate.parse("2026-08-19"),
            null,
            DailyPlanSnapshot.Sequence(
                com.alexandr5476.lifetracing.domain.DailySequencePlanMetadata(
                    snapshotId,
                    title,
                    null,
                    null,
                    null,
                ),
            ),
            PlanSourceState.UNAVAILABLE,
            false,
            false,
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
            java.time.ZoneOffset.UTC,
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
            java.time.ZoneOffset.UTC,
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
                .getUnclippedBoundsInRoot()
                .top
        val secondTop =
            composeTestRule
                .onAllNodesWithText(string(second))[0]
                .getUnclippedBoundsInRoot()
                .top
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
        composeTestRule
            .onNodeWithText(localizedDate(selectedDate, locale))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(localizedString(locale, R.string.daily_week_of, localizedDate(weekStart, locale)))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(localizedString(locale, R.string.daily_exact_time, localizedTime(exactTime, locale)))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                localizedString(
                    locale,
                    R.string.daily_completed_at,
                    localizedTime(noLive.completedAt, locale),
                ),
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                localizedString(
                    locale,
                    R.string.daily_time_range,
                    localizedTime(requireNotNull(timed.startedAt), locale),
                    localizedTime(timed.completedAt, locale),
                ),
            ).performScrollTo()
            .assertIsDisplayed()
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
            .format(instant.atZone(ZoneOffset.UTC)) + " (UTC+00:00, Z)"

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
