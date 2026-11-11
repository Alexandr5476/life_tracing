package com.alexandr5476.lifetracing.plan

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
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
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PlanScreenPresentationTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val keyboard =
        com.alexandr5476.lifetracing.ui
            .KeyboardInputFixture(compose)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val monday = LocalDate.parse("2026-09-14")
    private val selected = monday.plusDays(1)

    @Test
    fun repeatedHourLabelsUseCanonicalOccurrenceOffsets() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val date = LocalDate.parse("2026-10-25")
        val entries =
            listOf("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z").mapIndexed { index, text ->
                val instant = Instant.parse(text)
                val occurrence = instant.atZone(zone)
                row("overlap-$index", PlanTarget.ExactDay(instant, java.time.ZoneOffset.UTC)).copy(
                    effectiveLocalDate = occurrence.toLocalDate(),
                    exactLocalTime = occurrence.toLocalTime(),
                    exactUtcOffset = occurrence.offset,
                )
            }
        val start = date.with(java.time.DayOfWeek.MONDAY)
        val read =
            WeekPlanRead(
                start,
                date,
                entries,
                emptyList(),
                (0L..6L).map {
                    PlanDayPresence(
                        start.plusDays(it),
                        if (it ==
                            6L
                        ) {
                            2
                        } else {
                            0
                        },
                    )
                },
            )
        compose.setContent {
            LifeTracingTheme { PlanScreen(PlanPresentationState(start, date, PlanLoad.Content(read)), {}, {}) }
        }
        for (offset in listOf("+02:00", "+01:00")) {
            val clock = context.getString(R.string.calendar_time_with_offset, "02:30", offset)
            compose.onNodeWithText(context.getString(R.string.plan_exact_at, clock)).assertExists()
        }
    }

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
                .onNodeWithContentDescription(context.getString(R.string.plan_day_density, fullDate(date), density))
                .assertExists()
        }
        compose.onNodeWithText(context.getString(R.string.plan_selected_day, fullDate(selected))).assertExists()
        compose
            .onNodeWithText(
                context.getString(
                    R.string.plan_calendar_week_section,
                    context.getString(R.string.plan_calendar_range, fullDate(monday), fullDate(monday.plusDays(6))),
                ),
            ).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.plan_anytime)).assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.plan_exact_at, "12:30")).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.plan_week_placement)).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.plan_timer_target, "01:30")).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.plan_complete_action)).assertCountEquals(3)
        compose.onAllNodesWithText(context.getString(R.string.plan_start_action)).assertCountEquals(2)
        compose
            .onNodeWithContentDescription(context.getString(R.string.plan_day_density, fullDate(selected), 3))
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
        var clicked: PlanActionIdentity? = null
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

        compose
            .onNodeWithContentDescription(
                context.getString(
                    R.string.plan_execution_action,
                    row.title,
                    context.getString(R.string.plan_complete_action),
                ),
                substring = true,
            ).assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)

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

    @Test
    fun duplicatePlanActionsRemainDistinctAcrossLanguagesWeeksAndNativeActivation() {
        val entries =
            listOf(
                row("first", PlanTarget.FloatingDay(selected)),
                row("same-schedule", PlanTarget.FloatingDay(selected), source = PlanSourceState.CHANGED),
                row("archived", PlanTarget.FloatingDay(selected), source = PlanSourceState.ARCHIVED),
                row("unavailable", PlanTarget.FloatingDay(selected), source = PlanSourceState.UNAVAILABLE),
                row("exact", PlanTarget.ExactDay(Instant.parse("2026-09-15T12:30:00Z"), null)),
                row("sequence", PlanTarget.FloatingDay(selected), sequence = true),
                row("complete-one", PlanTarget.FloatingDay(selected), noLive = true),
                row("complete-two", PlanTarget.FloatingDay(selected), noLive = true),
            ).map {
                it.copy(
                    title = "Shared frozen",
                    plan =
                        it.plan.copy(
                            sourceActivityTemplateId =
                                ActivityTemplateId("same-source").takeIf { _ ->
                                    it.plan.kind ==
                                        PlanTrackableKind.ACTIVITY
                                },
                        ),
                )
            }
        val week = row("week", PlanTarget.Week(monday)).copy(title = "Shared frozen")
        val blocked =
            listOf(
                row("engaged", PlanTarget.FloatingDay(selected), engaged = true),
                row("fulfilled", PlanTarget.FloatingDay(selected), fulfilled = true),
                row("cancelled", PlanTarget.FloatingDay(selected)).let { row ->
                    row.copy(plan = row.plan.copy(status = PlanEntryStatus.CANCELLED, cancelledAt = row.plan.createdAt))
                },
            )
        val read =
            mutableStateOf(
                WeekPlanRead(monday, selected, entries + blocked, listOf(week), emptyList()),
            )
        val language = mutableStateOf("en")
        val clicked = mutableListOf<PlanActionIdentity>()
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    setLocale(Locale.forLanguageTag(language.value))
                }
            val localized = context.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
            ) {
                LifeTracingTheme {
                    PlanScreen(
                        PlanPresentationState(
                            read.value.weekStart,
                            read.value.selectedDate,
                            PlanLoad.Content(read.value),
                        ),
                        {
                        },
                        {},
                        clicked::add,
                    )
                }
            }
        }
        keyboard.enter()
        for (locale in listOf("en", "ru")) {
            compose.runOnIdle { language.value = locale }
            val eligible = entries + week
            val names =
                eligible.map {
                    compose
                        .onNode(
                            hasTestTag("plan-plan-action-${it.plan.id.value}"),
                        ).fetchSemanticsNode()
                        .config[SemanticsProperties.ContentDescription]
                        .single()
                }
            assertEquals(names.size, names.toSet().size)
            val configuration =
                Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(locale))
                }
            val localized = context.createConfigurationContext(configuration)
            val dateFormat =
                DateTimeFormatter
                    .ofLocalizedDate(
                        FormatStyle.FULL,
                    ).withLocale(Locale.forLanguageTag(locale))
            val base =
                localized.getString(
                    R.string.plan_execution_action_context,
                    localized.getString(
                        R.string.plan_execution_action,
                        "Shared frozen",
                        localized.getString(R.string.plan_start_action),
                    ),
                    localized.getString(R.string.plan_execution_activity_kind),
                    localized.getString(R.string.plan_execution_day, dateFormat.format(selected)),
                )
            for (index in 0..3) {
                assertEquals(
                    localized.getString(R.string.plan_execution_action_ordinal, base, index + 1, 4),
                    names[index],
                )
            }
            assertTrue(
                names.last().contains(localized.getString(R.string.plan_execution_week, dateFormat.format(monday))),
            )
            assertTrue(!names[4].contains("UTC"))
            names.forEachIndexed { index, name ->
                assertTrue(name.contains("Shared frozen"))
                for (unmerged in listOf(false, true)) {
                    compose
                        .onAllNodes(
                            hasContentDescription(name) and
                                hasClickAction(),
                            useUnmergedTree = unmerged,
                        ).assertCountEquals(1)
                }
                val kind =
                    if (index == 5) {
                        R.string.plan_execution_sequence
                    } else {
                        R.string.plan_execution_activity_kind
                    }
                val operation =
                    if (index == 6 ||
                        index == 7
                    ) {
                        R.string.plan_complete_action
                    } else {
                        R.string.plan_start_action
                    }
                assertTrue(name.contains(localized.getString(kind)))
                assertTrue(name.contains(localized.getString(operation)))
                val node = compose.onNodeWithContentDescription(name)
                node.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
                assertEquals(emptyList<PlanActionIdentity>(), clicked)
                node.performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(listOf(eligible[index].plan.actionIdentity()), clicked)
                clicked.clear()
            }
            blocked.forEach {
                compose
                    .onNode(
                        hasTestTag("plan-plan-action-${it.plan.id.value}"),
                    ).assertDoesNotExist()
            }
        }
        val oldNames =
            compose
                .onAllNodes(
                    hasContentDescription("Shared frozen", substring = true),
                ).fetchSemanticsNodes()
                .map {
                    it.config[SemanticsProperties.ContentDescription].single()
                }
        compose.runOnIdle {
            val next = selected.plusWeeks(1)
            read.value =
                WeekPlanRead(
                    monday.plusWeeks(1),
                    next,
                    listOf(
                        entries.first().copy(plan = entries.first().plan.copy(target = PlanTarget.FloatingDay(next))),
                    ),
                    emptyList(),
                    emptyList(),
                )
        }
        oldNames.forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
        val refreshed =
            compose
                .onNode(
                    hasTestTag("plan-plan-action-first"),
                ).fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .single()
        val dateFormat =
            DateTimeFormatter
                .ofLocalizedDate(
                    FormatStyle.FULL,
                ).withLocale(Locale.forLanguageTag("ru"))
        assertTrue(refreshed.contains(dateFormat.format(selected.plusWeeks(1))))
    }

    @Test
    fun calendarNativeSelectionUsesCanonicalDatesAndNeverInventsDensity() {
        val read =
            mutableStateOf(
                WeekPlanRead(
                    monday,
                    selected,
                    emptyList(),
                    emptyList(),
                    (0L..6L).map { PlanDayPresence(monday.plusDays(it), it.toInt()) },
                ),
            )
        val load = mutableStateOf<PlanLoad<WeekPlanRead>>(PlanLoad.Content(read.value))
        val actions = mutableListOf<PlanAction>()
        compose.setContent {
            LifeTracingTheme {
                PlanScreen(
                    PlanPresentationState(read.value.weekStart, read.value.selectedDate, load.value),
                    actions::add,
                    {},
                )
            }
        }
        (0L..6L).forEach { offset ->
            val date = monday.plusDays(offset)
            val day = compose.onNodeWithTag("plan-day-$date")
            day.assertExists()
            if (date == selected) day.assertIsSelected() else day.assertIsNotSelected()
            day.performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(PlanAction.SelectDate(date), actions.last())
        }
        compose.runOnIdle {
            read.value = read.value.copy(selectedDate = monday)
            load.value =
                PlanLoad.Content(read.value)
        }
        compose.onNodeWithTag("plan-day-$monday").assertIsSelected()
        compose.onNodeWithTag("plan-day-$selected").assertIsNotSelected()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(1)
        for (tag in listOf("plan-selected-heading", "plan-week-heading")) {
            assertTrue(
                compose
                    .onNodeWithTag(tag)
                    .fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.Heading),
            )
        }
        for (unavailable in listOf(PlanLoad.Loading, PlanLoad.Failure(PlanMessage.LOAD_FAILED))) {
            compose.runOnIdle { load.value = unavailable }
            compose
                .onAllNodes(
                    SemanticsMatcher("Calendar day") {
                        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("plan-day-") == true
                    },
                ).assertCountEquals(0)
            compose.onNodeWithText(context.getString(R.string.plan_empty_day)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.plan_empty_week)).assertDoesNotExist()
        }
    }

    @Test
    fun managementNamesAreUniqueAndNativeActivationKeepsExactRowsAcrossReordering() {
        val entries =
            listOf("first", "second", "third").map {
                row(
                    it,
                    PlanTarget.FloatingDay(selected),
                    source = PlanSourceState.CHANGED,
                ).copy(title = "Shared frozen")
            } +
                row(
                    "sequence-management",
                    PlanTarget.FloatingDay(selected),
                    sequence = true,
                    source = PlanSourceState.CHANGED,
                ).copy(title = "Shared frozen")
        val rows = mutableStateOf(entries)
        val language = mutableStateOf("en")
        val actions = mutableListOf<PlanAction>()
        compose.setContent {
            val configuration =
                Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language.value))
                }
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
            ) {
                LifeTracingTheme {
                    val read = WeekPlanRead(monday, selected, rows.value, emptyList(), emptyList())
                    PlanScreen(PlanPresentationState(monday, selected, PlanLoad.Content(read)), actions::add, {})
                }
            }
        }
        keyboard.enter()
        keyboard.awaitFocus(compose.onNodeWithText(context.getString(R.string.plan_back)))
        val nativeIds =
            entries.associate {
                it.plan.id to
                    compose.onNodeWithTag("plan-cancel-${it.plan.id.value}").fetchSemanticsNode().id
            }
        for (languageTag in listOf("en", "ru")) {
            for (order in listOf(entries, entries.reversed())) {
                compose.runOnIdle { language.value = languageTag }
                val locale = Locale.forLanguageTag(languageTag)
                val localized =
                    context.createConfigurationContext(
                        Configuration(context.resources.configuration).apply {
                            setLocale(locale)
                        },
                    )
                compose.runOnIdle { rows.value = order }
                val names = mutableListOf<String>()
                order.forEach { entry ->
                    for (operation in listOf("reschedule", "cancel", "update")) {
                        val node = compose.onNodeWithTag("plan-$operation-${entry.plan.id.value}")
                        val name = node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
                        names.add(name)
                        assertTrue(name.contains("Shared frozen"))
                        val peers = order.filter { it.plan.kind == entry.plan.kind }
                        val kind =
                            if (entry.plan.kind ==
                                PlanTrackableKind.ACTIVITY
                            ) {
                                R.string.plan_execution_activity_kind
                            } else {
                                R.string.plan_execution_sequence
                            }
                        val base =
                            localized.getString(
                                R.string.plan_management_context,
                                entry.title,
                                localized.getString(kind),
                                localized.getString(R.string.plan_execution_day, fullDate(selected, locale)),
                            )
                        val target =
                            if (peers.size ==
                                1
                            ) {
                                base
                            } else {
                                localized.getString(
                                    R.string.plan_execution_action_ordinal,
                                    base,
                                    peers.indexOf(entry) + 1,
                                    peers.size,
                                )
                            }
                        val operationId =
                            when (operation) {
                                "reschedule" -> R.string.plan_reschedule
                                "cancel" -> R.string.plan_cancel
                                else -> R.string.plan_update_template
                            }
                        assertEquals(
                            localized.getString(
                                R.string.plan_management_action,
                                localized.getString(operationId),
                                target,
                            ),
                            name,
                        )
                        if (operation == "cancel") assertEquals(nativeIds[entry.plan.id], node.fetchSemanticsNode().id)
                        for (unmerged in listOf(false, true)) {
                            compose
                                .onAllNodes(
                                    hasContentDescription(name) and hasClickAction(),
                                    useUnmergedTree = unmerged,
                                ).assertCountEquals(1)
                        }
                        node.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
                        assertTrue(actions.isEmpty())
                        keyboard.sendKey(node, android.view.KeyEvent.KEYCODE_ENTER)
                        val expected =
                            when (operation) {
                                "reschedule" -> PlanAction.Reschedule(entry)
                                "cancel" -> PlanAction.Cancel(entry)
                                else -> PlanAction.UpdateFromTemplate(entry)
                            }
                        assertEquals(listOf(expected), actions)
                        val dispatchedRow =
                            when (val action = actions.single()) {
                                is PlanAction.Reschedule -> action.row
                                is PlanAction.Cancel -> action.row
                                is PlanAction.UpdateFromTemplate -> action.row
                                else -> error("Unexpected command")
                            }
                        org.junit.Assert.assertSame(entry, dispatchedRow)
                        assertEquals(entry.plan.actionIdentity(), dispatchedRow.plan.actionIdentity())
                        actions.clear()
                    }
                }
                assertEquals(names.size, names.toSet().size)
            }
        }
    }

    @Test
    fun keyboardDayReloadFailureRetryAndRemovedRowRecoverWithoutCommands() {
        val entry = row("focus", PlanTarget.FloatingDay(monday)).copy(effectiveLocalDate = monday)
        val read =
            WeekPlanRead(
                monday,
                selected,
                emptyList(),
                emptyList(),
                (0L..6L).map { PlanDayPresence(monday.plusDays(it), if (it == 0L) 1 else 0) },
            )
        val load = mutableStateOf<PlanLoad<WeekPlanRead>>(PlanLoad.Content(read))
        val selection = mutableStateOf(selected)
        val chosen = read.copy(selectedDate = monday, selectedDayPlans = listOf(entry))
        val actions = mutableListOf<PlanAction>()
        compose.setContent {
            LifeTracingTheme {
                PlanScreen(PlanPresentationState(monday, selection.value, load.value), actions::add, {})
            }
        }
        keyboard.enter()
        keyboard.awaitFocus(compose.onNodeWithText(context.getString(R.string.plan_back)))
        val day = compose.onNodeWithTag("plan-day-$monday")
        day.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.sendKey(day, android.view.KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf(PlanAction.SelectDate(monday)), actions)
        actions.clear()
        compose.runOnIdle {
            selection.value = monday
            load.value = PlanLoad.Loading
        }
        keyboard.awaitFocus(compose.onNodeWithText(context.getString(R.string.plan_today)))
        compose.runOnIdle { load.value = PlanLoad.Content(chosen) }
        keyboard.awaitFocus(day)
        val cancel = compose.onNodeWithTag("plan-cancel-focus")
        cancel.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {
            load.value =
                PlanLoad.Content(chosen.copy(selectedDayPlans = emptyList()))
        }
        keyboard.awaitFocus(day)
        compose.runOnIdle { load.value = PlanLoad.Failure(PlanMessage.LOAD_FAILED) }
        val retry = compose.onNodeWithText(context.getString(R.string.plan_retry))
        keyboard.awaitFocus(retry)
        assertTrue(actions.isEmpty())
        keyboard.sendKey(retry, android.view.KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf(PlanAction.Refresh), actions)
        actions.clear()
        compose.runOnIdle { load.value = PlanLoad.Loading }
        compose.runOnIdle { load.value = PlanLoad.Content(chosen) }
        keyboard.awaitFocus(day)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun localizedCalendarDistinguishesTodaySelectedAndNavigatedWeekWithoutUiClock() {
        val language = mutableStateOf("en")
        val read =
            mutableStateOf(
                WeekPlanRead(
                    monday,
                    selected,
                    emptyList(),
                    emptyList(),
                    (0L..6L).map { PlanDayPresence(monday.plusDays(it), it.toInt()) },
                    com.alexandr5476.lifetracing.domain.CalendarTemporalContext(
                        Instant.parse("2026-09-14T10:00:00Z"),
                        java.time.ZoneOffset.UTC,
                    ),
                ),
            )
        val actions = mutableListOf<PlanAction>()
        compose.setContent {
            val configuration =
                Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language.value))
                }
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
            ) {
                LifeTracingTheme {
                    PlanScreen(
                        PlanPresentationState(
                            read.value.weekStart,
                            read.value.selectedDate,
                            PlanLoad.Content(read.value),
                        ),
                        actions::add,
                        {},
                    )
                }
            }
        }
        for (tag in listOf("en", "ru")) {
            compose.runOnIdle { language.value = tag }
            val locale = Locale.forLanguageTag(tag)
            val localized =
                context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                        setLocale(locale)
                    },
                )

            fun range(start: LocalDate) =
                localized.getString(
                    R.string.plan_calendar_range,
                    fullDate(start, locale),
                    fullDate(start.plusDays(6), locale),
                )
            compose
                .onNodeWithText(
                    localized.getString(R.string.plan_calendar_current_week, range(monday)),
                ).assertExists()
            (0L..6L).forEach { offset ->
                val date = monday.plusDays(offset)
                val density = localized.getString(R.string.plan_day_density, fullDate(date, locale), offset.toInt())
                val name = if (offset == 0L) localized.getString(R.string.plan_calendar_today, density) else density
                val node = compose.onNodeWithContentDescription(name)
                if (offset == 1L) node.assertIsSelected() else node.assertIsNotSelected()
                for (unmerged in listOf(false, true)) {
                    compose
                        .onAllNodes(
                            hasContentDescription(name) and hasClickAction(),
                            useUnmergedTree = unmerged,
                        ).assertCountEquals(1)
                }
                assertEquals(
                    androidx.compose.ui.semantics.Role.Button,
                    node.fetchSemanticsNode().config[SemanticsProperties.Role],
                )
            }
            for ((id, action, destination) in listOf(
                Triple("plan-previous-week", PlanAction.PreviousWeek, monday.minusWeeks(1)),
                Triple("plan-next-week", PlanAction.NextWeek, monday.plusWeeks(1)),
            )) {
                val node = compose.onNodeWithTag(id)
                assertTrue(
                    node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single().contains(
                        range(destination),
                    ),
                )
                node.performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(action, actions.last())
            }
            compose
                .onNodeWithText(
                    localized.getString(R.string.plan_today),
                ).performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(PlanAction.Today, actions.last())
        }
        compose.runOnIdle {
            read.value =
                read.value.copy(
                    weekStart = monday.plusWeeks(1),
                    selectedDate = selected.plusWeeks(1),
                    dayPresence = (0L..6L).map { PlanDayPresence(monday.plusWeeks(1).plusDays(it), 0) },
                )
        }
        val ru = Locale.forLanguageTag("ru")
        val localized =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    setLocale(ru)
                },
            )
        val range =
            localized.getString(
                R.string.plan_calendar_range,
                fullDate(monday.plusWeeks(1), ru),
                fullDate(monday.plusWeeks(1).plusDays(6), ru),
            )
        compose.onNodeWithText(localized.getString(R.string.plan_calendar_displayed_week, range)).assertExists()
        compose.runOnIdle {
            read.value =
                read.value.copy(
                    temporalContext = null,
                    weekStart = monday,
                    selectedDate = selected,
                    dayPresence = (0L..6L).map { PlanDayPresence(monday.plusDays(it), 0) },
                )
        }
        val originalRange =
            localized.getString(
                R.string.plan_calendar_range,
                fullDate(monday, ru),
                fullDate(monday.plusDays(6), ru),
            )
        compose.onNodeWithText(localized.getString(R.string.plan_calendar_displayed_week, originalRange)).assertExists()
        compose.onNodeWithTag("plan-day-$monday").assertIsNotSelected()
        compose.onNodeWithTag("plan-day-$selected").assertIsSelected()
        compose
            .onNodeWithContentDescription(
                localized.getString(R.string.plan_day_density, fullDate(monday, ru), 0),
            ).assertExists()
        compose.onNodeWithText(localized.getString(R.string.plan_empty_day)).assertExists()
        compose.onNodeWithText(localized.getString(R.string.plan_empty_week)).assertExists()
    }

    @Test
    fun exactSemanticsFollowCanonicalOffsetsZoneAndStrictOverduePublications() {
        val date = LocalDate.parse("2026-10-25")
        val start = date.with(java.time.DayOfWeek.MONDAY)
        val instants = listOf(Instant.parse("2026-10-25T00:30:00Z"), Instant.parse("2026-10-25T01:30:00Z"))

        fun projection(
            zone: java.time.ZoneId,
            now: Instant,
        ): WeekPlanRead {
            val rows =
                instants.mapIndexed { index, instant ->
                    val local = instant.atZone(zone)
                    row(
                        "canonical-$index",
                        PlanTarget.ExactDay(instant, java.time.ZoneId.of("Asia/Tokyo")),
                        source = PlanSourceState.CHANGED,
                    ).copy(
                        effectiveLocalDate = local.toLocalDate(),
                        exactLocalTime = local.toLocalTime(),
                        exactUtcOffset = local.offset,
                        overdue = now > instant,
                    )
                }
            return WeekPlanRead(
                start,
                date,
                rows,
                emptyList(),
                (0L..6L).map {
                    PlanDayPresence(
                        start.plusDays(it),
                        if (it ==
                            6L
                        ) {
                            2
                        } else {
                            0
                        },
                    )
                },
                com.alexandr5476.lifetracing.domain
                    .CalendarTemporalContext(now, zone),
            )
        }
        val read = mutableStateOf(projection(java.time.ZoneId.of("Europe/Berlin"), instants.first().minusMillis(1)))
        compose.setContent {
            LifeTracingTheme { PlanScreen(PlanPresentationState(start, date, PlanLoad.Content(read.value)), {}, {}) }
        }
        for (now in listOf(instants.first().minusMillis(1), instants.first(), instants.first().plusMillis(1))) {
            compose.runOnIdle { read.value = projection(java.time.ZoneId.of("Europe/Berlin"), now) }
            compose.onAllNodesWithText(context.getString(R.string.plan_overdue)).assertCountEquals(
                if (now >
                    instants.first()
                ) {
                    1
                } else {
                    0
                },
            )
            listOf("+02:00", "+01:00").forEachIndexed { index, offset ->
                val clock = context.getString(R.string.calendar_time_with_offset, "02:30", offset)
                compose.onNodeWithText(context.getString(R.string.plan_exact_at, clock)).assertExists()
                val name =
                    compose
                        .onNodeWithTag(
                            "plan-reschedule-canonical-$index",
                        ).fetchSemanticsNode()
                        .config[SemanticsProperties.ContentDescription]
                        .single()
                assertTrue(name.contains(offset))
                assertTrue(!name.contains("+09:00"))
            }
        }
        compose.runOnIdle { read.value = projection(java.time.ZoneOffset.UTC, instants.first()) }
        instants.forEachIndexed { index, _ ->
            val name =
                compose
                    .onNodeWithTag(
                        "plan-reschedule-canonical-$index",
                    ).fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription]
                    .single()
            assertTrue(name.contains("+00:00"))
            assertTrue(!name.contains("+02:00"))
            assertTrue(!name.contains("+01:00"))
        }
    }

    @Test
    fun forbiddenManagementActionsRemainAbsentAndLifecycleStaysSeparateFromEngagement() {
        val base = row("cancelled", PlanTarget.FloatingDay(selected), source = PlanSourceState.CHANGED)
        val cancelled =
            base.copy(
                plan = base.plan.copy(status = PlanEntryStatus.CANCELLED, cancelledAt = base.plan.updatedAt),
            )
        val blocked =
            listOf(
                row("live", PlanTarget.FloatingDay(selected), source = PlanSourceState.CHANGED, engaged = true),
                row("done", PlanTarget.FloatingDay(selected), source = PlanSourceState.CHANGED, fulfilled = true),
                cancelled,
            )
        val ineligible =
            listOf(PlanSourceState.CURRENT, PlanSourceState.ARCHIVED, PlanSourceState.UNAVAILABLE).map {
                row(it.name, PlanTarget.FloatingDay(selected), source = it)
            }
        setScreen(blocked + ineligible, emptyList())
        for (entry in blocked) {
            for (action in listOf("reschedule", "cancel", "update", "plan-action")) {
                compose.onNodeWithTag("plan-$action-${entry.plan.id.value}").assertDoesNotExist()
            }
        }
        ineligible.forEach { compose.onNodeWithTag("plan-update-${it.plan.id.value}").assertDoesNotExist() }
        compose.onAllNodesWithText(context.getString(R.string.plan_planned)).assertCountEquals(4)
        compose.onNodeWithText(context.getString(R.string.plan_engaged)).assertExists()
        compose.onNodeWithText(context.getString(R.string.plan_fulfilled)).assertExists()
        compose.onNodeWithText(context.getString(R.string.daily_plan_cancelled)).assertExists()
        val custom =
            SemanticsMatcher(
                "Executable custom action",
            ) { !it.config.getOrNull(SemanticsActions.CustomActions).isNullOrEmpty() }
        compose.onAllNodes(custom, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun englishRussianScaleExtremesKeepAllDatesAndManagementControlsKeyboardReachable() {
        val language = mutableStateOf("en")
        val scale = mutableStateOf(0)
        val configurations = listOf(Triple(90, 90, 1f), Triple(120, 130, 2f), Triple(90, 130, 2f))
        val entry = row("scaled", PlanTarget.FloatingDay(selected), source = PlanSourceState.CHANGED)
        val read =
            WeekPlanRead(
                monday,
                selected,
                listOf(entry),
                emptyList(),
                (0L..6L).map { PlanDayPresence(monday.plusDays(it), 0) },
            )
        val actions = mutableListOf<PlanAction>()
        compose.setContent {
            val (interfaceScale, textScale, systemFont) = configurations[scale.value]
            val configuration =
                Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language.value))
                    fontScale =
                        systemFont
                }
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit
                        .Density(density.density, systemFont),
            ) {
                com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance(
                    com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences(
                        interfaceScalePercent = interfaceScale,
                        textScalePercent = textScale,
                    ),
                ) {
                    PlanScreen(PlanPresentationState(monday, selected, PlanLoad.Content(read)), actions::add, {})
                }
            }
        }
        keyboard.enter()
        keyboard.awaitFocus(compose.onNodeWithText(context.getString(R.string.plan_back)))
        for (languageTag in listOf("en", "ru")) {
            for (index in configurations.indices) {
                compose.runOnIdle {
                    language.value = languageTag
                    scale.value = index
                }
                val nodes =
                    (0L..6L).map { "plan-day-${monday.plusDays(it)}" } +
                        listOf(
                            "plan-plan-action-scaled",
                            "plan-reschedule-scaled",
                            "plan-cancel-scaled",
                            "plan-update-scaled",
                        )
                var previous: androidx.compose.ui.test.SemanticsNodeInteraction? = null
                for (id in nodes) {
                    val node = compose.onNodeWithTag(id).performScrollTo().assertIsDisplayed()
                    val bounds = node.fetchSemanticsNode().touchBoundsInRoot
                    val minimum = 48 * context.resources.displayMetrics.density
                    assertTrue("$id minimum native target", bounds.width >= minimum - 1 && bounds.height >= minimum - 1)
                    if (previous ==
                        null
                    ) {
                        node.performSemanticsAction(SemanticsActions.RequestFocus)
                    } else {
                        keyboard.sendKey(previous, android.view.KeyEvent.KEYCODE_TAB)
                    }
                    keyboard.awaitFocus(node)
                    previous = node
                }
                val localized =
                    context.createConfigurationContext(
                        Configuration(context.resources.configuration).apply {
                            setLocale(Locale.forLanguageTag(languageTag))
                        },
                    )
                for ((id, operation) in listOf(
                    "reschedule" to R.string.plan_reschedule,
                    "cancel" to R.string.plan_cancel,
                    "update" to R.string.plan_update_template,
                )) {
                    val name =
                        compose
                            .onNodeWithTag(
                                "plan-$id-scaled",
                            ).fetchSemanticsNode()
                            .config[SemanticsProperties.ContentDescription]
                            .single()
                    assertTrue(name.contains(localized.getString(operation)))
                    assertTrue(name.contains(fullDate(selected, Locale.forLanguageTag(languageTag))))
                }
                assertTrue(actions.isEmpty())
            }
        }
    }

    @Test
    fun nativeWeekNavigationRetainsItsControlAcrossLoadingAndCanonicalPublication() {
        val read =
            mutableStateOf(
                WeekPlanRead(
                    monday,
                    selected,
                    emptyList(),
                    emptyList(),
                    (0L..6L).map {
                        PlanDayPresence(monday.plusDays(it), 0)
                    },
                ),
            )
        val loading = mutableStateOf(false)
        val actions = mutableListOf<PlanAction>()
        compose.setContent {
            LifeTracingTheme {
                PlanScreen(
                    PlanPresentationState(
                        read.value.weekStart,
                        read.value.selectedDate,
                        if (loading.value) PlanLoad.Loading else PlanLoad.Content(read.value),
                    ),
                    actions::add,
                    {},
                )
            }
        }
        keyboard.enter()
        val back = compose.onNodeWithText(context.getString(R.string.plan_back))
        keyboard.awaitFocus(back)
        keyboard.sendKey(back, android.view.KeyEvent.KEYCODE_TAB)
        val previous = compose.onNodeWithTag("plan-previous-week")
        keyboard.awaitFocus(previous)
        keyboard.sendKey(previous, android.view.KeyEvent.KEYCODE_TAB)
        val next = compose.onNodeWithTag("plan-next-week")
        keyboard.awaitFocus(next)
        assertTrue(actions.isEmpty())
        keyboard.sendKey(next, android.view.KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf(PlanAction.NextWeek), actions)
        compose.runOnIdle { loading.value = true }
        keyboard.awaitFocus(next)
        compose.runOnIdle {
            read.value =
                read.value.copy(
                    weekStart = monday.plusWeeks(1),
                    selectedDate = selected.plusWeeks(1),
                    dayPresence = (0L..6L).map { PlanDayPresence(monday.plusWeeks(1).plusDays(it), 0) },
                )
            loading.value = false
        }
        keyboard.awaitFocus(next)
        assertEquals(listOf(PlanAction.NextWeek), actions)
        keyboard.sendKey(next, android.view.KeyEvent.KEYCODE_TAB)
        val today = compose.onNodeWithText(context.getString(R.string.plan_today))
        keyboard.awaitFocus(today)
        keyboard.sendKey(today, android.view.KeyEvent.KEYCODE_TAB)
        keyboard.awaitFocus(compose.onNodeWithTag("plan-day-${monday.plusWeeks(1)}"))
    }

    @Test
    fun passivePublicationsReorderingAndHiddenRouteNeverStealKeyboardOwnership() {
        val first = row("stable-first", PlanTarget.FloatingDay(selected))
        val second = row("stable-second", PlanTarget.FloatingDay(selected))
        val read =
            mutableStateOf(
                WeekPlanRead(
                    monday,
                    selected,
                    listOf(first, second),
                    emptyList(),
                    (0L..6L).map { PlanDayPresence(monday.plusDays(it), 0) },
                ),
            )
        val visible = mutableStateOf(true)
        val actions = mutableListOf<PlanAction>()
        compose.setContent {
            LifeTracingTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.TextButton(
                        onClick = {},
                        modifier =
                            androidx.compose.ui.Modifier.semantics {
                                testTag =
                                    "other-route"
                            },
                    ) {
                        androidx.compose.material3.Text("Other route")
                    }
                    PlanScreen(PlanPresentationState(monday, selected, PlanLoad.Content(read.value)), actions::add, {
                    }, isRouteVisible = visible.value)
                }
            }
        }
        keyboard.enter()
        keyboard.awaitFocus(compose.onNodeWithText(context.getString(R.string.plan_back)))
        val cancel = compose.onNodeWithTag("plan-cancel-stable-first")
        cancel.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        val nativeId = cancel.fetchSemanticsNode().id
        compose.runOnIdle {
            read.value =
                read.value.copy(
                    selectedDayPlans = listOf(second, first.copy(overdue = true)),
                )
        }
        cancel.assertIsFocused()
        assertEquals(nativeId, cancel.fetchSemanticsNode().id)
        assertTrue(actions.isEmpty())
        keyboard.sendKey(cancel, android.view.KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf(PlanAction.Cancel(first.copy(overdue = true))), actions)
        actions.clear()
        val other = compose.onNodeWithTag("other-route")
        compose.runOnIdle { visible.value = false }
        other.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle { read.value = read.value.copy(selectedDayPlans = emptyList()) }
        keyboard.awaitFocus(other)
        assertTrue(actions.isEmpty())
        compose.runOnIdle { visible.value = true }
        keyboard.awaitFocus(compose.onNodeWithText(context.getString(R.string.plan_back)))
        assertTrue(actions.isEmpty())
    }

    private fun fullDate(
        date: LocalDate,
        locale: Locale = context.resources.configuration.locales[0],
    ): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)

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
