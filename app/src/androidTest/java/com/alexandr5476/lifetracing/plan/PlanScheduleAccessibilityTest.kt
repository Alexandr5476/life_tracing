@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing.plan

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.EspressoKey
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.data.persistence.PlanSchedulingStore
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.executePlanMutation
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.nativeFailureReport
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.hamcrest.Matchers.sameInstance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PlanScheduleAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val keyboard = KeyboardInputFixture(compose)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var fixture: Fixture? = null

    @After
    fun dispose() {
        fixture?.close()
    }

    @Test
    fun nativeCreationRetainsTypedSourcesAndAllThreeTargetsSurvivePhysicalReopen() {
        val f = launch()
        val created = mutableListOf<PlanEntry>()
        for ((source, kind) in listOf(
            f.activitySource to PlanScheduleKind.FLOATING_DAY,
            f.sequenceSource to PlanScheduleKind.WEEK,
            f.activitySource to PlanScheduleKind.EXACT_DAY,
        )) {
            open(f, source)
            assertEquals(source.id, f.form.source?.id)
            assertNull(f.form.identity)
            val initial = f.form
            keyboard.awaitFocus(compose.onNodeWithTag("plan-schedule-date"))
            for (choice in PlanScheduleKind.entries) {
                native("plan-schedule-kind-$choice", Role.RadioButton)
                val node = compose.onNodeWithTag("plan-schedule-kind-$choice")
                if (choice == PlanScheduleKind.FLOATING_DAY) node.assertIsSelected() else node.assertIsNotSelected()
            }
            activate("plan-schedule-kind-$kind", accessibility = kind == PlanScheduleKind.WEEK)
            if (kind == PlanScheduleKind.FLOATING_DAY) assertFalse(f.form.scheduleEdited)
            if (kind == PlanScheduleKind.EXACT_DAY) {
                edit("date", "2026-10-26")
                edit("time", "10:00:27.123000")
                assertEquals("10:00:27.123000", f.form.time)
            }
            assertEquals(initial.formId, f.form.formId)
            assertEquals(created.size, f.commands.size)
            save(f)
            val row = f.rows().single { it.plan.id !in created.map(PlanEntry::id) }
            created += row.plan
            when (kind) {
                PlanScheduleKind.FLOATING_DAY ->
                    assertEquals(
                        PlanTarget.FloatingDay(LocalDate.parse("2026-10-24")),
                        row.plan.target,
                    )
                PlanScheduleKind.WEEK -> assertEquals(PlanTarget.Week(LocalDate.parse("2026-10-19")), row.plan.target)
                PlanScheduleKind.EXACT_DAY ->
                    assertEquals(
                        PlanTarget.ExactDay(Instant.parse("2026-10-26T09:00:27.123Z"), f.zone),
                        row.plan.target,
                    )
            }
            if (source == f.activitySource) {
                assertEquals(f.activity.id, row.plan.sourceActivityTemplateId)
            } else {
                assertEquals(f.sequence.id, row.plan.sourceSequenceTemplateId)
            }
        }
        f.reopen()
        created.forEach { plan ->
            assertEquals(plan, f.store.plans.getPlan(plan.id))
            assertEquals(
                plan.actionIdentity(),
                f.store.reads
                    .getFocusedAction(plan.id)
                    .identity,
            )
        }
        assertEquals(3, f.commands.size)
    }

    @Test
    fun nativeGapPrecisionAndOverlapChoicesPersistOnlyExplicitDistinctOccurrences() {
        val f = launch()
        val plans = mutableListOf<PlanEntry>()
        for (index in 0..1) {
            open(f, f.activitySource)
            activate("plan-schedule-kind-EXACT_DAY")
            edit("date", "2026-03-29")
            edit("time", "02:30")
            val before = f.store.durableFacts()
            compose.onNodeWithTag("plan-schedule-save").assertIsNotEnabled()
            compose
                .onNodeWithText(
                    context.getString(R.string.plan_nonexistent_time),
                ).performScrollTo()
                .assertIsDisplayed()
            assertTrue(
                compose
                    .onNodeWithTag(
                        "plan-schedule-time",
                    ).fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.Error),
            )
            edit("date", "2026-10-25")
            edit("time", "02:30:00.123000001")
            compose.onNodeWithTag("plan-schedule-save").assertIsNotEnabled()
            assertEquals("02:30:00.123000001", f.form.time)
            assertEquals(before, f.store.durableFacts())
            edit("time", "02:30:00.123")
            for (choice in 0..1) {
                native("plan-schedule-offset-$choice", Role.RadioButton)
                compose.onNodeWithTag("plan-schedule-offset-$choice").assertIsNotSelected()
            }
            compose.onNodeWithTag("plan-schedule-save").assertIsNotEnabled()
            activate("plan-schedule-offset-$index", accessibility = index == 0)
            compose.onNodeWithTag("plan-schedule-offset-$index").assertIsSelected()
            save(f)
            plans += f.rows().single { it.plan.id !in plans.map(PlanEntry::id) }.plan
        }
        assertNotEquals(plans[0].target, plans[1].target)
        f.reopen()
        for ((index, plan) in plans.withIndex()) {
            assertEquals(
                PlanTarget.ExactDay(
                    Instant.parse(
                        if (index ==
                            0
                        ) {
                            "2026-10-25T00:30:00.123Z"
                        } else {
                            "2026-10-25T01:30:00.123Z"
                        },
                    ),
                    f.zone,
                ),
                f.store.reads
                    .getFocusedAction(plan.id)
                    .identity.target,
            )
        }
        assertEquals(2, f.commands.size)
    }

    @Test
    fun nativeUntouchedReschedulePreservesExactTargetTimestampsAndSnapshotAfterZoneTravel() {
        val f = launch()
        val original = f.seed()
        val frozen =
            f.store.reads
                .getFocusedAction(original.id)
                .snapshot
        f.zone = ZoneId.of("Pacific/Auckland")
        openReschedule(f, original.id)
        val form = f.form
        assertEquals(original.actionIdentity(), form.identity)
        assertEquals(f.activity.name, form.existingTitle)
        activate("plan-schedule-kind-EXACT_DAY")
        focus("plan-schedule-date")
        keyboard.sendKey(compose.onNodeWithTag("plan-schedule-date"), KeyEvent.KEYCODE_TAB)
        keyboard.awaitFocus(compose.onNodeWithTag("plan-schedule-time"))
        assertEquals(form, f.form)
        compose.onNodeWithTag("plan-schedule-save").assertIsEnabled()
        compose.onNodeWithTag("plan-schedule-zone-review").assertDoesNotExist()
        save(f)
        assertTrue(f.commands.single() is PlanMutation.PreserveSchedule)
        f.reopen()
        assertEquals(original, f.store.plans.getPlan(original.id))
        val canonical = f.store.reads.getFocusedAction(original.id)
        assertEquals(original.actionIdentity(), canonical.identity)
        assertEquals(frozen, canonical.snapshot)
    }

    @Test
    fun nativeChangedRescheduleKeepsPlanSnapshotSourceAndStatisticsProvenanceAfterReopen() {
        val f = launch()
        val original = f.seed()
        val frozen =
            f.store.reads
                .getFocusedAction(original.id)
                .snapshot
        openReschedule(f, original.id)
        edit("time", "11:00:00.456")
        assertTrue(f.form.scheduleEdited)
        save(f, accessibility = true)
        assertTrue(f.commands.single() is PlanMutation.Reschedule)
        f.reopen()
        val changed = requireNotNull(f.store.plans.getPlan(original.id))
        assertEquals(original.copy(target = changed.target, updatedAt = f.clock), changed)
        val canonical = f.store.reads.getFocusedAction(original.id)
        assertEquals(PlanTarget.ExactDay(Instant.parse("2026-10-26T10:00:00.456Z"), f.zone), canonical.identity.target)
        assertEquals(frozen, canonical.snapshot)
    }

    @Test
    fun nativeControlsRetainLocalizedErrorsSelectionsAndKeyboardReachabilityAtScaleExtremes() {
        val f = launch()
        open(f, f.sequenceSource)
        activate("plan-schedule-kind-EXACT_DAY")
        edit("date", "2026-10-25")
        edit("time", "02:30")
        for (language in listOf("en", "ru")) {
            for (scale in listOf(Triple(90, 90, 1f), Triple(120, 130, 2f), Triple(90, 130, 2f))) {
                compose.runOnIdle {
                    f.language.value = language
                    f.scale.value = scale
                }
                val localized =
                    context.createConfigurationContext(
                        Configuration(context.resources.configuration).apply {
                            setLocale(Locale.forLanguageTag(language))
                        },
                    )
                assertScheduleTextDisplayed(
                    localized.getString(
                        R.string.plan_schedule_create_context,
                        f.sequence.name,
                        localized.getString(R.string.plan_execution_sequence),
                    ),
                    "creation language=$language scale=$scale",
                )
                for (tag in listOf(
                    "kind-FLOATING_DAY",
                    "kind-EXACT_DAY",
                    "kind-WEEK",
                    "date",
                    "time",
                    "offset-0",
                    "offset-1",
                )) {
                    val full = "plan-schedule-$tag"
                    focus(full)
                    val node = compose.onNodeWithTag(full).assertIsDisplayed().fetchSemanticsNode()
                    val minimum = 48f * context.resources.displayMetrics.density
                    assertTrue(
                        "$language $scale $full ${node.touchBoundsInRoot}",
                        node.touchBoundsInRoot.height + 1 >= minimum,
                    )
                    assertTrue(node.touchBoundsInRoot.width + 1 >= minimum)
                }
                focus("plan-schedule-date")
                keyboard.sendKey(compose.onNodeWithTag("plan-schedule-date"), KeyEvent.KEYCODE_TAB)
                keyboard.awaitFocus(compose.onNodeWithTag("plan-schedule-time"))
                shiftTab("plan-schedule-time")
                keyboard.awaitFocus(compose.onNodeWithTag("plan-schedule-date"))
                assertEquals("2026-10-25", f.form.date)
                assertEquals("02:30", f.form.time)
                compose.onNodeWithTag("plan-schedule-offset-0").assertIsNotSelected()
                compose.onNodeWithTag("plan-schedule-offset-1").assertIsNotSelected()
                activate("plan-schedule-offset-0")
                compose.onNodeWithTag("plan-schedule-offset-0").assertIsSelected()
                focus("plan-schedule-save")
                compose.onNodeWithTag("plan-schedule-save").assertIsDisplayed().assertIsEnabled()
                assertPhysicalTarget("plan-schedule-save")
                val occurrence = localized.getString(R.string.plan_first_occurrence, "02:30", "UTC+02:00")
                assertEquals(
                    listOf(occurrence),
                    compose
                        .onNodeWithTag("plan-schedule-offset-0")
                        .fetchSemanticsNode()
                        .config[SemanticsProperties.ContentDescription],
                )
                edit("time", "02:31")
                edit("time", "02:30")
                assertScheduleTextDisplayed(
                    localized.getString(R.string.plan_ambiguous_time),
                    "validation language=$language scale=$scale",
                )
                focus("plan-schedule-close")
                native("plan-schedule-close", Role.Button)
                compose.onNodeWithTag("plan-schedule-close").assertIsDisplayed()
                assertPhysicalTarget("plan-schedule-close")
                assertTrue(f.commands.isEmpty())
            }
        }
        compose.runOnIdle {
            f.zone = ZoneId.of("Pacific/Auckland")
            f.controller.dispatch(PlanAction.SubmitForm(f.form.formId, f.form.inputRevision))
        }
        compose.onNodeWithTag("plan-schedule-save").assertIsNotEnabled()
        activate("plan-schedule-zone-review", accessibility = true)
        assertEquals(f.zone, f.form.schedulingZoneId)
        assertEquals("02:30", f.form.time)
        assertNull(f.form.error)
        focus("plan-schedule-save")
        native("plan-schedule-save", Role.Button)
        assertTrue(f.commands.isEmpty())
        activate("plan-schedule-close")
        // Close restores Add on the calendar after its native window regains focus.
        keyboard.awaitFocus(compose.onNodeWithTag("plan-add"))
        val original = f.seed()
        openReschedule(f, original.id)
        for (language in listOf("en", "ru")) {
            compose.runOnIdle { f.language.value = language }
            val localized =
                context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                        setLocale(Locale.forLanguageTag(language))
                    },
                )
            assertScheduleTextDisplayed(
                localized.getString(
                    R.string.plan_schedule_reschedule_context,
                    f.activity.name,
                    localized.getString(R.string.plan_execution_activity_kind),
                ),
                "reschedule language=$language scale=${f.scale.value}",
            )
            assertEquals(original.actionIdentity(), f.form.identity)
            assertFalse(f.form.scheduleEdited)
        }
        assertTrue(f.commands.isEmpty())
    }

    @Test
    fun nativeCloseAndHiddenRouteInvalidateOldSaveAndRestoreOnlySurvivingPlanFocus() {
        val f = launch()
        open(f, f.activitySource)
        val obsolete = PlanAction.SubmitForm(f.form.formId, f.form.inputRevision)
        activate("plan-schedule-close", accessibility = true)
        keyboard.awaitFocus(compose.onNodeWithTag("plan-add"))
        open(f, f.sequenceSource)
        val replacement = f.form
        compose.runOnIdle { f.controller.dispatch(obsolete) }
        assertEquals(replacement, f.form)
        focus("plan-schedule-close")
        keyboard.sendKey(compose.onNodeWithTag("plan-schedule-close"), KeyEvent.KEYCODE_BACK)
        keyboard.awaitFocus(compose.onNodeWithTag("plan-add"))
        val plan = f.seed()
        openReschedule(f, plan.id)
        activate("plan-schedule-close")
        keyboard.awaitFocus(compose.onNodeWithTag("plan-day-${f.controller.state.value.selectedDate}"))
        openReschedule(f, plan.id)
        val stale = PlanAction.DismissForm(f.form.formId)
        compose.runOnIdle {
            f.visible.value = false
            f.controller.onRouteExited()
        }
        compose.onNodeWithTag("plan-schedule-form").assertDoesNotExist()
        compose.runOnIdle {
            f.controller.dispatch(obsolete)
            f.controller.dispatch(stale)
            f.controller.onRouteEntered()
            f.visible.value =
                true
        }
        compose.waitUntil { f.controller.state.value.week is PlanLoad.Content }
        compose.onNodeWithTag("plan-schedule-form").assertDoesNotExist()
        assertTrue(f.commands.isEmpty())
    }

    @Test
    fun nativePendingSaveDisablesEditingAndRepeatedActivationWhileCloseRetainsAcceptedWriter() {
        val f = launch()
        open(f, f.activitySource)
        val gate = CompletableDeferred<Unit>()
        f.commitGate = gate
        activate("plan-schedule-save")
        compose.waitUntil { f.controller.state.value.isMutating && f.commands.size == 1 }
        compose.onNodeWithTag("plan-schedule-save").assertIsNotEnabled()
        compose.onNodeWithTag("plan-schedule-date").assertIsNotEnabled()
        val form = f.form
        compose.runOnIdle { f.controller.dispatch(PlanAction.SubmitForm(form.formId, form.inputRevision)) }
        activate("plan-schedule-close")
        gate.complete(Unit)
        compose.waitUntil { !f.controller.state.value.isMutating }
        assertEquals(1, f.commands.size)
        val plan = f.rows().single().plan
        f.reopen()
        assertEquals(
            plan.actionIdentity(),
            f.store.reads
                .getFocusedAction(plan.id)
                .identity,
        )
    }

    private fun launch(): Fixture {
        val f = Fixture()
        fixture = f
        compose.setContent {
            val state by f.controller.state.collectAsState()
            val (size, text, font) = f.scale.value
            val config =
                Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(f.language.value))
                    fontScale = font
                }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, font),
            ) {
                LifeTracingTheme {
                    LifeTracingAppearance(
                        AppearancePreferences(interfaceScalePercent = size, textScalePercent = text),
                    ) {
                        PlanScreen(state, f.controller::dispatch, {}, isRouteVisible = f.visible.value)
                    }
                }
            }
        }
        compose.waitUntil { f.controller.state.value.week is PlanLoad.Content }
        keyboard.enter()
        return f
    }

    private fun open(
        f: Fixture,
        source: ReusablePlanCatalogItem,
    ) {
        compose.onNodeWithTag("plan-add").performScrollTo().performClick()
        compose.waitUntil {
            f.controller.state.value.catalog
                ?.loading == false
        }
        val result = compose.onNodeWithTag(catalogResultTag(source.id)).performScrollTo()
        keyboard.enter(result)
        result.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.sendKey(result, KeyEvent.KEYCODE_ENTER)
        compose.waitUntil { f.controller.state.value.form != null }
        keyboard.enter(compose.onNodeWithTag("plan-schedule-date"))
        keyboard.awaitFocus(compose.onNodeWithTag("plan-schedule-date"))
    }

    private fun openReschedule(
        f: Fixture,
        id: PlanEntryId,
    ) {
        val target =
            f.store.reads
                .getFocusedAction(id)
                .identity.target as PlanTarget.ExactDay
        val date = target.scheduledAt.atZone(f.zone).toLocalDate()
        val week = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekDelta = ChronoUnit.WEEKS.between(f.controller.state.value.weekStart, week)
        repeat(kotlin.math.abs(weekDelta).toInt()) {
            activate(if (weekDelta > 0) "plan-next-week" else "plan-previous-week")
        }
        assertEquals(week, f.controller.state.value.weekStart)
        compose.waitUntil {
            val read = (f.controller.state.value.week as? PlanLoad.Content)?.value
            read?.weekStart == week && read.dayPresence.any { it.date == date }
        }
        activate("plan-day-$date")
        compose.waitUntil {
            val read = (f.controller.state.value.week as? PlanLoad.Content)?.value
            read?.selectedDate == date && read.selectedDayPlans.any { it.plan.id == id }
        }
        activate("plan-reschedule-${id.value}")
        keyboard.enter(compose.onNodeWithTag("plan-schedule-date"))
        keyboard.awaitFocus(compose.onNodeWithTag("plan-schedule-date"))
    }

    private fun edit(
        field: String,
        value: String,
    ) {
        compose.onNodeWithTag("plan-schedule-$field").performScrollTo().performTextReplacement(value)
        assertEquals(
            value,
            compose
                .onNodeWithTag(
                    "plan-schedule-$field",
                ).fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
    }

    private fun focus(tag: String) {
        val node = compose.onNodeWithTag(tag)
        keyboard.awaitWindowFocus(node)
        if (tag != "plan-schedule-close" && tag != "plan-schedule-save") node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        keyboard.awaitFocus(node)
    }

    private fun activate(
        tag: String,
        accessibility: Boolean = false,
    ) {
        if (accessibility) {
            val node = compose.onNodeWithTag(tag)
            if (tag != "plan-schedule-close" && tag != "plan-schedule-save") node.performScrollTo()
            node.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        } else {
            focus(tag)
            keyboard.sendKey(compose.onNodeWithTag(tag), KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun save(
        f: Fixture,
        accessibility: Boolean = false,
    ) {
        val count = f.commands.size
        f.clock = f.clock.plusSeconds(1)
        activate("plan-schedule-save", accessibility)
        compose.waitUntil { f.commands.size == count + 1 && !f.controller.state.value.isMutating }
        assertNull(f.controller.state.value.mutationFailure)
        assertNull(f.controller.state.value.recoveryFailure)
    }

    private fun shiftTab(tag: String) {
        val node = compose.onNodeWithTag(tag)
        keyboard.awaitWindowFocus(node)
        val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
        onView(isRoot()).inRoot(withDecorView(sameInstance(view.rootView))).perform(
            pressKey(
                EspressoKey
                    .Builder()
                    .withKeyCode(KeyEvent.KEYCODE_TAB)
                    .withShiftPressed(true)
                    .build(),
            ),
        )
    }

    private fun assertScheduleTextDisplayed(
        expected: String,
        presentation: String,
    ) {
        // Density changes can replace the native Dialog window. Admit its keyboard owner
        // and leave field focus before inspecting passive text, so cursor/IME relocation
        // cannot undo the explicit scroll. Focusing never activates Close or Save.
        keyboard.enter(compose.onNodeWithTag("plan-schedule-date"))
        focus("plan-schedule-date")
        focus("plan-schedule-close")
        keyboard.closeKeyboard(compose.onNodeWithTag("plan-schedule-close"))
        val text = compose.onNodeWithText(expected)
        try {
            text.performScrollTo().assertIsDisplayed()
        } catch (failure: AssertionError) {
            val evidence =
                runCatching {
                    val target = text.fetchSemanticsNode()
                    val form = compose.onNodeWithTag("plan-schedule-form").fetchSemanticsNode()
                    val scroll = form.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    val view = (target.root as ViewRootForTest).view
                    val native =
                        compose.runOnIdle {
                            "windowFocused=${view.hasWindowFocus()} size=${view.width}x${view.height} " +
                                "nativeFocused=${view.rootView.findFocus()} touchMode=${view.isInTouchMode}"
                        }
                    nativeFailureReport(
                        "$presentation expected=$expected\n$native\n" +
                            "text=${target.boundsInRoot} form=${form.boundsInRoot} " +
                            "scroll=${scroll?.value?.invoke()}/${scroll?.maxValue?.invoke()}\n" +
                            "${compose.onNodeWithTag("plan-schedule-close").printToString()}\n" +
                            compose.onNodeWithTag("plan-schedule-form").printToString(),
                    )
                }.getOrElse { "Diagnostic capture failed: $it" }
            throw AssertionError("Schedule text is not displayed: $presentation. $evidence", failure)
        }
    }

    private fun assertPhysicalTarget(tag: String) {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
        val minimum = 48f * context.resources.displayMetrics.density
        assertTrue("$tag $bounds", bounds.height + 1 >= minimum && bounds.width + 1 >= minimum)
    }

    private fun native(
        tag: String,
        role: Role,
    ) {
        for (unmerged in listOf(false, true)) {
            val config = compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().config
            assertEquals(role, config.getOrNull(SemanticsProperties.Role))
            assertTrue(config.contains(SemanticsActions.OnClick))
            assertNull(config.getOrNull(SemanticsActions.CustomActions))
        }
    }

    private inner class Fixture : AutoCloseable {
        private val name = "plan-native-${UUID.randomUUID()}.db"

        @Volatile var zone: ZoneId = ZoneId.of("Europe/Berlin")

        @Volatile var clock: Instant = Instant.parse("2026-10-24T10:00:00Z")
        var store = PlanSchedulingStore(context, name, CurrentZoneIdProvider { zone })
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commands = CopyOnWriteArrayList<PlanMutation>()
        val language = mutableStateOf("en")
        val scale = mutableStateOf(Triple(100, 100, 1f))
        val visible = mutableStateOf(true)

        @Volatile var commitGate: CompletableDeferred<Unit>? = null
        val activity =
            store.authoring.createActivityTemplate(
                ActivityTemplateDraft("Same frozen name", "frozen comment", TimeTrackingMode.STOPWATCH, null),
                createdAt = clock,
            )
        val sequence =
            store.authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Same frozen name",
                    "frozen comment",
                    nodes =
                        listOf(
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    DraftIdentity.New("step"),
                                    0,
                                    StepActivityDraft.FromTemplate(activity.id),
                                ),
                            ),
                        ),
                ),
                createdAt = clock,
            )
        val activitySource = ReusablePlanCatalogItem(LibraryTemplateId.Activity(activity.id), activity.name, null, null)
        val sequenceSource = ReusablePlanCatalogItem(LibraryTemplateId.Sequence(sequence.id), sequence.name, null, null)
        val controller =
            PlanController(
                scope,
                { store.reads.getWeek(it) },
                { _, _, _ -> listOf(activitySource, sequenceSource) },
                { store.reads.getCancelledPage(it) },
                {
                    commands += it
                    commitGate?.await()
                    executePlanMutation(it, store.plans)
                },
                { clock },
                { zone },
                boundaryScheduler = LocalDateBoundaryScheduler { _, _, _, _ -> },
            )
        val form get() = requireNotNull(controller.state.value.form)

        fun seed(): PlanEntry =
            store.plans.createActivityPlanFromTemplate(
                activity.id,
                PlanSchedule.ExactDay(LocalDateTime.parse("2026-10-26T10:00:27.123"), zone),
                clock,
            )

        fun rows() =
            listOf("2026-10-24", "2026-10-25", "2026-10-26")
                .flatMap {
                    val date = LocalDate.parse(it)
                    val read =
                        store.reads.getWeek(
                            WeekPlanQuery(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), date, clock),
                        )
                    read.selectedDayPlans + read.weekPlans
                }.distinctBy { it.plan.id }

        fun reopen() {
            controller.close()
            scope.cancel()
            store.close()
            store = PlanSchedulingStore(context, name, CurrentZoneIdProvider { zone })
        }

        override fun close() {
            commitGate?.complete(Unit)
            controller.close()
            scope.cancel()
            store.close()
            context.deleteDatabase(name)
        }
    }
}
