@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions", "LargeClass")

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.data.persistence.PlanSchedulingStore
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.CancelledPlanPageQuery
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanReadRow
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WeekPlanRead
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.executePlanMutation
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.appearance.AppearancePreferences
import com.alexandr5476.lifetracing.ui.appearance.LifeTracingAppearance
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PlanLifecycleAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val keyboard = KeyboardInputFixture(compose)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var fixture: Harness? = null
    private var store: PlanSchedulingStore? = null
    private var databaseName: String? = null
    private val zone = ZoneId.of("Europe/Berlin")
    private val at = Instant.parse("2026-10-24T12:00:00Z")

    @After
    fun dispose() {
        fixture?.close()
        store?.close()
        databaseName?.let { context.deleteDatabase(it) }
    }

    @Test
    fun nativeEntryLocalizedDuplicateRestoreNamesHaveSingleClickAndExactIdentityWithoutFocusCommands() {
        val f = launch()
        open()
        for (language in listOf("en", "ru")) {
            compose.runOnIdle { f.language.value = language }
            val localized = localized(language)
            compose
                .onNode(
                    hasText(localized.getString(R.string.plan_cancelled)) and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
                ).assertIsDisplayed()
            val descriptions = mutableListOf<String>()
            f.rows.forEach { row ->
                val tag = "plan-restore-${row.plan.id.value}"
                focus(tag)
                native(tag)
                descriptions +=
                    compose
                        .onNodeWithTag(tag)
                        .fetchSemanticsNode()
                        .config
                        .getOrNull(SemanticsProperties.ContentDescription)!!
                        .single()
            }
            assertTrue(descriptions.all { it.contains("2026-10-24T08:00:27.123Z") })
            assertTrue(descriptions.none { it.contains("Pacific/Auckland") })
            assertEquals(3, descriptions.distinct().size)
            assertTrue(descriptions.all { it.contains(localized.getString(R.string.daily_plan_cancelled)) })
            assertTrue(descriptions.any { it.contains(localized.getString(R.string.plan_execution_sequence)) })
            assertTrue(descriptions.any { it.contains(localized.getString(R.string.plan_execution_activity_kind)) })
            assertTrue(f.commands.isEmpty())
        }
        val row = f.rows[1]
        activate("plan-restore-${row.plan.id.value}")
        compose.waitUntil { f.commands.size == 1 && !f.controller.state.value.isMutating }
        assertEquals(row.plan.actionIdentity(), (f.commands.single() as PlanMutation.Restore).identity)
        assertTrue(
            f.actions
                .filterIsInstance<PlanAction.Restore>()
                .single()
                .row === row,
        )
        keyboard.awaitFocus(compose.onNodeWithTag("plan-cancelled-close"))
    }

    @Test
    fun nativePaginationKeepsFocusedRowsDuringAppendFailureAndRetriesOnlyBoundedFailedPage() {
        val f = Harness()
        f.rows = (0..34).map { summary("page-$it") }
        val gate = CompletableDeferred<Unit>()
        var fail = true
        f.reader = { query ->
            if (query.offset == 30) {
                gate.await()
                if (fail) error("append")
            }
            CancelledPlanPage(f.rows.drop(query.offset).take(query.limit), query.offset == 0)
        }
        launch(f)
        open()
        focus("plan-restore-page-5")
        compose.runOnIdle { f.controller.dispatch(PlanAction.LoadMoreCancelled(f.authority, 30)) }
        compose.waitUntil { f.requests.size == 2 }
        assertEquals(30, f.controller.state.value.cancelledItems.size)
        keyboard.awaitFocus(compose.onNodeWithTag("plan-restore-page-5"))
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil { f.controller.state.value.cancelled is PlanLoad.Failure }
        assertEquals(30, f.controller.state.value.cancelledItems.size)
        focus("plan-restore-page-5")
        compose.runOnIdle { fail = false }
        activate("plan-cancelled-retry")
        compose.waitUntil { f.controller.state.value.cancelledItems.size == 35 }
        assertEquals(listOf(0, 30, 30), f.requests.map { it.offset })
        assertTrue(f.requests.all { it.limit == 30 })
        assertEquals(
            35,
            f.controller.state.value.cancelledItems
                .map { it.plan.id }
                .distinct()
                .size,
        )
        focus("plan-restore-page-34")
        keyboard.sendKey(compose.onNodeWithTag("plan-restore-page-34"), KeyEvent.KEYCODE_TAB)
        keyboard.awaitFocus(compose.onNodeWithTag("plan-cancelled-close"))
        assertTrue(f.commands.isEmpty())
    }

    @Test
    fun initialLoadingFailureRetryEmptyAndNativeDismissalReturnToSurvivingEntry() {
        val f = Harness()
        val gate = CompletableDeferred<Unit>()
        var fail = true
        f.reader = {
            gate.await()
            if (fail) error("initial")
            CancelledPlanPage(emptyList(), false)
        }
        launch(f)
        open()
        assertTrue(f.controller.state.value.cancelled is PlanLoad.Loading)
        compose.onNodeWithTag("plan-restore-one").assertDoesNotExist()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil { f.controller.state.value.cancelled is PlanLoad.Failure }
        compose.onNodeWithText(context.getString(R.string.plan_cancelled_empty)).assertDoesNotExist()
        native("plan-cancelled-retry")
        compose.runOnIdle { fail = false }
        activate("plan-cancelled-retry", semantics = true)
        compose.waitUntil { f.controller.state.value.cancelled is PlanLoad.Content }
        compose.onNodeWithText(context.getString(R.string.plan_cancelled_empty)).performScrollTo().assertIsDisplayed()
        focus("plan-cancelled-close")
        keyboard.sendKey(compose.onNodeWithTag("plan-cancelled-close"), KeyEvent.KEYCODE_BACK)
        keyboard.awaitFocus(compose.onNodeWithTag("plan-cancelled-entry"))
        assertFalse(f.controller.state.value.cancelledOpen)
        assertTrue(f.commands.isEmpty())
    }

    @Test
    fun pendingReadDismissalReopenRouteExitAndObsoleteCallbacksNeverOwnNewDialog() {
        val f = Harness()
        val gate = CompletableDeferred<Unit>()
        f.reader = {
            gate.await()
            CancelledPlanPage(f.rows, false)
        }
        launch(f)
        open()
        val old = f.authority
        activate("plan-cancelled-close")
        keyboard.awaitFocus(compose.onNodeWithTag("plan-cancelled-entry"))
        open()
        val fresh = f.authority
        compose.runOnIdle {
            f.controller.dispatch(PlanAction.DismissCancelled(old))
            f.controller.dispatch(PlanAction.Restore(f.rows.first(), old))
            gate.complete(Unit)
        }
        compose.waitUntil { f.controller.state.value.cancelled is PlanLoad.Content }
        assertEquals(fresh.dialogId, f.authority.dialogId)
        compose.runOnIdle {
            f.visible.value = false
            f.controller.onRouteExited()
        }
        compose.onNodeWithTag("plan-cancelled-close").assertDoesNotExist()
        compose.runOnIdle {
            f.controller.dispatch(PlanAction.OpenCancelled)
            f.controller.dispatch(PlanAction.Restore(f.rows.first(), fresh))
            f.controller.onRouteEntered()
            f.visible.value = true
        }
        assertFalse(f.controller.state.value.cancelledOpen)
        assertTrue(f.commands.isEmpty())
    }

    @Test
    fun englishRussianCombinedScalingKeepsFullPageRestoreMoreRetryCloseNativeAndPhysicallyReachable() {
        val f = Harness()
        f.rows = (0..34).map { summary("scale-$it", "A long duplicate frozen title with original scheduling context") }
        f.reader = { query ->
            if (query.offset > 0) error("append failure for native Retry")
            CancelledPlanPage(f.rows.take(30), true)
        }
        launch(f)
        open()
        for (language in listOf("en", "ru")) {
            for (scale in listOf(Triple(90, 90, 1f), Triple(120, 130, 2f), Triple(90, 130, 2f))) {
                compose.runOnIdle {
                    f.language.value = language
                    f.scale.value = scale
                }
                val tags = (0..29).map { "plan-restore-scale-$it" } + "plan-cancelled-more"
                for ((index, tag) in tags.withIndex()) {
                    val node = compose.onNodeWithTag(tag)
                    node.performScrollTo().assertIsDisplayed().assertIsEnabled()
                    native(tag)
                    val bounds = node.fetchSemanticsNode().touchBoundsInRoot
                    val minimum = 48f * context.resources.displayMetrics.density
                    assertTrue(
                        "$language $scale $tag $bounds",
                        bounds.width + 1 >= minimum && bounds.height + 1 >= minimum,
                    )
                    if (index ==
                        0
                    ) {
                        focus(tag)
                    } else {
                        keyboard.sendKey(compose.onNodeWithTag(tags[index - 1]), KeyEvent.KEYCODE_TAB)
                    }
                    keyboard.awaitFocus(node)
                }
                activate("plan-cancelled-more")
                compose.waitUntil { f.controller.state.value.cancelled is PlanLoad.Failure }
                for (tag in listOf("plan-cancelled-retry", "plan-cancelled-close")) {
                    focus(tag)
                    native(tag)
                    val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
                    val minimum = 48f * context.resources.displayMetrics.density
                    assertTrue(
                        "$language $scale $tag $bounds",
                        bounds.width + 1 >= minimum && bounds.height + 1 >= minimum,
                    )
                }
                activate("plan-cancelled-close")
                keyboard.awaitFocus(compose.onNodeWithTag("plan-cancelled-entry"))
                open()
            }
        }
        assertTrue(f.commands.isEmpty())
    }

    @Test
    fun physicalActivitySequenceCancelAndRestoreRetainExactIdentitySnapshotSourceAndPastInstant() {
        createStore()
        val activity =
            store!!.authoring.createActivityTemplate(
                ActivityTemplateDraft("Duplicate frozen title", "frozen comment", TimeTrackingMode.STOPWATCH, null),
                createdAt = at,
            )
        val sequence =
            store!!.authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Duplicate frozen title",
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
                createdAt = at,
            )
        val schedule = PlanSchedule.ExactDay(LocalDateTime.parse("2026-10-24T10:00:27.123"), zone)
        val originals =
            listOf(
                store!!.plans.createActivityPlanFromTemplate(activity.id, schedule, at),
                store!!.plans.createActivityPlanFromTemplate(activity.id, schedule, at),
                store!!.plans.createSequencePlanFromTemplate(sequence.id, schedule, at),
            )
        val f = realHarness()
        launch(f)
        awaitIdle(f)
        val snapshots = originals.associate { it.id to store!!.reads.getFocusedAction(it.id).snapshot }
        originals.forEach { original ->
            activate("plan-cancel-${original.id.value}", semantics = true)
            awaitIdle(f)
            assertTrue(f.controller.state.value.week is PlanLoad.Content)
            assertFalse(
                (f.controller.state.value.week as PlanLoad.Content).value.selectedDayPlans.any {
                    it.plan.id ==
                        original.id
                },
            )
        }
        reopen(f)
        val cancelled = store!!.reads.getCancelledPage(CancelledPlanPageQuery(0, 30)).items
        assertEquals(originals.map { it.id }.toSet(), cancelled.map { it.plan.id }.toSet())
        originals.forEach { original ->
            val row = cancelled.single { it.plan.id == original.id }
            assertEquals(
                original.copy(
                    status = PlanEntryStatus.CANCELLED,
                    updatedAt = row.plan.updatedAt,
                    cancelledAt = row.plan.cancelledAt,
                ),
                row.plan,
            )
            assertTrue(row.plan.cancelledAt != null)
            assertFalse(row.overdue)
            assertEquals(snapshots.getValue(original.id), store!!.reads.getFocusedAction(original.id).snapshot)
        }
        open()
        originals.forEach { original ->
            activate("plan-restore-${original.id.value}", semantics = true)
            awaitIdle(f)
            assertFalse(
                f.controller.state.value.cancelledItems
                    .any { it.plan.id == original.id },
            )
            keyboard.awaitFocus(compose.onNodeWithTag("plan-cancelled-close"))
        }
        reopen(f)
        val read =
            store!!.reads.getWeek(
                com.alexandr5476.lifetracing.domain.WeekPlanQuery(
                    LocalDate.parse("2026-10-19"),
                    LocalDate.parse("2026-10-24"),
                    f.clock,
                    zone,
                ),
            )
        originals.forEach { original ->
            val row = read.selectedDayPlans.single { it.plan.id == original.id }
            assertEquals(original.copy(updatedAt = row.plan.updatedAt), row.plan)
            assertNull(row.plan.cancelledAt)
            assertTrue(row.overdue)
            assertEquals(snapshots.getValue(original.id), store!!.reads.getFocusedAction(original.id).snapshot)
        }
        assertTrue(
            store!!
                .reads
                .getCancelledPage(CancelledPlanPageQuery(0, 30))
                .items
                .isEmpty(),
        )
        assertEquals(
            originals.map {
                it.id
            },
            f.commands.filterIsInstance<PlanMutation.Cancel>().map { it.identity.planEntryId },
        )
        assertEquals(
            originals.map {
                it.id
            },
            f.commands.filterIsInstance<PlanMutation.Restore>().map { it.identity.planEntryId },
        )
    }

    @Test
    fun physicalExplicitActivitySequenceUpdatesPreserveTargetsSeriesChildrenAndOtherFrozenHistory() {
        createStore()
        val activity =
            store!!.authoring.createActivityTemplate(
                ActivityTemplateDraft("Old title", "old comment", TimeTrackingMode.STOPWATCH, null),
                createdAt = at,
            )
        val sequence =
            store!!.authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Old title",
                    "old comment",
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
                createdAt = at,
            )
        val originals =
            listOf(
                store!!.plans.createActivityPlanFromTemplate(
                    activity.id,
                    PlanSchedule.FloatingDay(LocalDate.parse("2026-10-24")),
                    at,
                ),
                store!!.plans.createSequencePlanFromTemplate(
                    sequence.id,
                    PlanSchedule.Week(LocalDate.parse("2026-10-19")),
                    at,
                ),
            )
        // Independent history stays frozen across source authoring and explicit Plan updates.
        val historyPlan =
            store!!.plans.createActivityPlanFromTemplate(
                activity.id,
                PlanSchedule.FloatingDay(LocalDate.parse("2026-10-24")),
                at,
            )
        store!!.live.startActivityFromPlan(store!!.reads.getFocusedAction(historyPlan.id).identity, at, at, zone)
        store!!.live.completeActiveActivity(at.plusSeconds(1))
        val historical = store!!.reads.getFocusedAction(historyPlan.id)
        val historicalFacts =
            store!!.durableFacts().filterKeys {
                it.contains("execution") ||
                    it.contains("occurrence") ||
                    it.contains("interval") ||
                    it == "statistics_series"
            }
        val frozen = originals.associate { it.id to store!!.reads.getFocusedAction(it.id).snapshot }
        store!!.authoring.saveActivityTemplate(
            activity.id,
            activity.revision,
            activity.toAuthoringDraft().copy(name = "New activity", shortComment = "new comment"),
            at.plusSeconds(2),
        )
        store!!.authoring.saveSequenceTemplate(
            sequence.id,
            sequence.revision,
            sequence
                .toAuthoringDraft(
                    requireNotNull(store!!.authoring.getSequenceTemplateAuthoringState(sequence.id)).activitySnapshots,
                ).copy(name = "New sequence", shortComment = "new comment"),
            at.plusSeconds(2),
        )
        // Authoring renames mutable Series labels; all IDs and historical facts stay frozen.
        val renamedSeries =
            mapOf(
                activity.statisticsSeriesId.value to "New activity",
                sequence.statisticsSeriesId.value to "New sequence",
            )
        val expectedHistoricalFacts =
            historicalFacts + (
                "statistics_series" to
                    historicalFacts.getValue("statistics_series").map { series ->
                        series.mapIndexed { index, value ->
                            if (index == 2) renamedSeries[series.first()] ?: value else value
                        }
                    }
            )
        assertEquals(expectedHistoricalFacts, store!!.durableFacts().filterKeys { it in historicalFacts })
        originals.forEach { assertEquals(frozen.getValue(it.id), store!!.reads.getFocusedAction(it.id).snapshot) }
        val f = realHarness()
        launch(f)
        awaitIdle(f)
        originals.forEach {
            assertEquals(PlanSourceState.CHANGED, store!!.reads.getFocusedAction(it.id).sourceState)
            activate("plan-update-${it.id.value}", semantics = true)
            awaitIdle(f)
        }
        reopen(f)
        originals.forEach { original ->
            val current = store!!.reads.getFocusedAction(original.id)
            assertEquals(original.target, current.identity.target)
            assertEquals(2L, current.identity.sourceRevision)
            assertEquals(PlanSourceState.CURRENT, current.sourceState)
            when (val snapshot = current.snapshot) {
                is FocusedPlanAction.Snapshot.Activity -> {
                    val before = (frozen.getValue(original.id) as FocusedPlanAction.Snapshot.Activity).value
                    assertNotEquals(before.id, snapshot.value.id)
                    assertEquals(before.statisticsSeriesId, snapshot.value.statisticsSeriesId)
                    assertEquals("New activity", snapshot.value.name)
                    assertEquals("new comment", snapshot.value.shortComment)
                }
                is FocusedPlanAction.Snapshot.Sequence -> {
                    val before = frozen.getValue(original.id) as FocusedPlanAction.Snapshot.Sequence
                    assertNotEquals(before.value.id, snapshot.value.id)
                    assertEquals(before.value.statisticsSeriesId, snapshot.value.statisticsSeriesId)
                    assertEquals(before.activitySnapshots, snapshot.activitySnapshots)
                    assertEquals("New sequence", snapshot.value.name)
                    assertEquals("new comment", snapshot.value.shortComment)
                }
            }
        }
        val retained = store!!.reads.getFocusedAction(historyPlan.id)
        assertEquals(historical.identity, retained.identity)
        assertEquals(historical.snapshot, retained.snapshot)
        assertEquals(expectedHistoricalFacts, store!!.durableFacts().filterKeys { it in historicalFacts })
        assertEquals(2, f.commands.size)
    }

    @Test
    fun recoveryRetryReadsBothSurfacesWithoutReplayingRestoreAndCloseRemainsNativeEnabled() {
        val f = Harness()
        var failWeek = false
        val cancelledRetry = CompletableDeferred<Unit>()
        f.reader = { query ->
            if (f.requests.size == 3) cancelledRetry.await()
            CancelledPlanPage(f.rows.drop(query.offset).take(query.limit), false)
        }
        f.weekReader = {
            if (failWeek) error("week recovery")
            WeekPlanRead(it.weekStart, it.selectedDate, emptyList(), emptyList(), emptyList())
        }
        launch(f)
        open()
        val old = f.authority
        compose.runOnIdle { failWeek = true }
        activate("plan-restore-one", semantics = true)
        compose.waitUntil { f.controller.state.value.recoveryFailure != null }
        assertTrue(f.controller.state.value.isMutating)
        compose.onNodeWithTag("plan-cancelled-close").assertIsEnabled()
        compose
            .onNodeWithTag("plan-restore-two")
            .performScrollTo()
            .assertIsNotEnabled()
        compose.runOnIdle {
            f.controller.dispatch(PlanAction.Restore(f.rows.first(), old))
            failWeek = false
        }
        activate("plan-cancelled-retry")
        compose.waitUntil { f.requests.size == 3 }
        assertTrue(f.controller.state.value.isMutating)
        assertEquals(1, f.commands.size)
        compose.runOnIdle { cancelledRetry.complete(Unit) }
        compose.waitUntil { !f.controller.state.value.isMutating }
        assertEquals(1, f.commands.size)
        assertEquals(3, f.requests.size)
        compose.onNodeWithTag("plan-restore-one").assertDoesNotExist()
        keyboard.awaitFocus(compose.onNodeWithTag("plan-cancelled-close"))
    }

    @Test
    fun forbiddenLifecycleAndSourceRowsExposeNoForbiddenNativeOrCustomActions() {
        val f = Harness()
        val activeTarget = PlanTarget.FloatingDay(LocalDate.parse("2026-10-24"))
        val rows =
            PlanSourceState.entries.map { source ->
                summary("source-$source").let {
                    it.copy(
                        plan =
                            it.plan.copy(
                                status = PlanEntryStatus.PLANNED,
                                cancelledAt = null,
                                target = activeTarget,
                            ),
                        sourceState = source,
                    )
                }
            } +
                listOf(
                    summary("engaged").let {
                        it.copy(
                            plan =
                                it.plan.copy(
                                    status = PlanEntryStatus.PLANNED,
                                    cancelledAt = null,
                                    target = activeTarget,
                                ),
                            sourceState = PlanSourceState.CHANGED,
                            engaged = true,
                        )
                    },
                    summary("fulfilled").let {
                        it.copy(
                            plan =
                                it.plan.copy(
                                    status = PlanEntryStatus.FULFILLED,
                                    cancelledAt = null,
                                    fulfilledAt = at,
                                    target = activeTarget,
                                ),
                            sourceState = PlanSourceState.CHANGED,
                        )
                    },
                )
        f.weekReader = { WeekPlanRead(it.weekStart, it.selectedDate, rows, emptyList(), emptyList()) }
        launch(f)
        compose.waitUntil { f.controller.state.value.week is PlanLoad.Content }
        rows.forEach { row ->
            val eligible = row.plan.status == PlanEntryStatus.PLANNED && !row.engaged
            if (!eligible) {
                compose.onNodeWithTag("plan-cancel-${row.plan.id.value}").assertDoesNotExist()
            }
            if (!eligible || row.sourceState != PlanSourceState.CHANGED) {
                compose.onNodeWithTag("plan-update-${row.plan.id.value}").assertDoesNotExist()
                compose.runOnIdle { f.controller.dispatch(PlanAction.UpdateFromTemplate(row)) }
            }
        }
        assertTrue(f.commands.isEmpty())
    }

    @Test
    fun rejectedPhysicalCancelLeavesAllDurableFactsUnchangedAfterReopenAndNeverRetriesWriter() {
        createStore()
        val activity =
            store!!.authoring.createActivityTemplate(
                ActivityTemplateDraft("Rejected frozen title", "frozen comment", TimeTrackingMode.STOPWATCH, null),
                createdAt = at,
            )
        val original =
            store!!.plans.createActivityPlanFromTemplate(
                activity.id,
                PlanSchedule.FloatingDay(LocalDate.parse("2026-10-24")),
                at,
            )
        store!!.forbidPlanUpdates()
        val facts = store!!.durableFacts()
        val f = realHarness()
        launch(f)
        awaitIdle(f)
        activate("plan-cancel-${original.id.value}", semantics = true)
        awaitIdle(f)
        assertEquals(PlanMessage.PERSISTENCE_FAILED, f.controller.state.value.mutationFailure)
        assertEquals(1, f.commands.size)
        reopen(f)
        assertEquals(facts, store!!.durableFacts())
        val row = (f.controller.state.value.week as PlanLoad.Content).value.selectedDayPlans.single()
        assertEquals(original, row.plan)
        assertTrue(
            store!!
                .reads
                .getCancelledPage(CancelledPlanPageQuery(0, 30))
                .items
                .isEmpty(),
        )
        assertEquals(1, f.commands.size)
    }

    private fun createStore() {
        val name = "plan-lifecycle-${UUID.randomUUID()}.db"
        databaseName = name
        store = PlanSchedulingStore(context, name, CurrentZoneIdProvider { zone })
    }

    private fun realHarness(): Harness =
        Harness(Dispatchers.Default).apply {
            clock = at.plusSeconds(10)
            weekReader = { store!!.reads.getWeek(it) }
            reader = { store!!.reads.getCancelledPage(it) }
            commit = { executePlanMutation(it, store!!.plans) }
        }

    private fun reopen(f: Harness) {
        compose.runOnIdle {
            f.visible.value = false
            f.controller.onRouteExited()
        }
        store!!.close()
        store = PlanSchedulingStore(context, requireNotNull(databaseName), CurrentZoneIdProvider { zone })
        compose.runOnIdle {
            f.controller.onRouteEntered()
            f.visible.value = true
        }
        awaitIdle(f)
        keyboard.enter(compose.onNodeWithText(context.getString(R.string.plan_back)))
        keyboard.awaitFocus(compose.onNodeWithText(context.getString(R.string.plan_back)))
    }

    private fun awaitIdle(f: Harness) {
        compose.waitUntil(5_000) {
            !f.controller.state.value.isMutating &&
                f.controller.state.value.week is PlanLoad.Content
        }
    }

    private fun localized(language: String) =
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) },
        )

    private fun native(tag: String) {
        for (unmerged in listOf(false, true)) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = unmerged).assertCountEquals(1)
            val config = compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().config
            assertEquals(Role.Button, config.getOrNull(SemanticsProperties.Role))
            assertTrue(config.contains(SemanticsActions.OnClick))
            assertNull(config.getOrNull(SemanticsActions.CustomActions))
        }
    }

    private fun focus(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag != "plan-cancelled-close") node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(node)
    }

    private fun activate(
        tag: String,
        semantics: Boolean = false,
    ) {
        focus(tag)
        val node = compose.onNodeWithTag(tag)
        if (semantics) {
            node.performSemanticsAction(SemanticsActions.OnClick) { it() }
        } else {
            keyboard.sendKey(node, KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun open() {
        activate("plan-cancelled-entry")
        keyboard.enter(compose.onNodeWithTag("plan-cancelled-close"))
        keyboard.awaitFocus(compose.onNodeWithTag("plan-cancelled-close"))
    }

    private fun launch(f: Harness = Harness()): Harness {
        fixture = f
        f.start()
        compose.setContent {
            val state by f.controller.state.collectAsState()
            val (interfaceScale, textScale, systemFont) = f.scale.value
            val configuration =
                Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(f.language.value))
                    fontScale = systemFont
                }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, systemFont),
            ) {
                LifeTracingTheme {
                    LifeTracingAppearance(
                        AppearancePreferences(interfaceScalePercent = interfaceScale, textScalePercent = textScale),
                    ) {
                        PlanScreen(state, {
                            f.actions += it
                            f.controller.dispatch(it)
                        }, {}, isRouteVisible = f.visible.value)
                    }
                }
            }
        }
        keyboard.enter()
        keyboard.awaitFocus(compose.onNodeWithText(context.getString(R.string.plan_back)))
        return f
    }

    private inner class Harness(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main.immediate,
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var clock = at.plusSeconds(10)
        var rows = listOf(summary("one"), summary("two"), summary("sequence", sequence = true))
        val language = mutableStateOf("en")
        val scale = mutableStateOf(Triple(100, 100, 1f))
        val visible = mutableStateOf(true)
        val actions = CopyOnWriteArrayList<PlanAction>()
        val commands = CopyOnWriteArrayList<PlanMutation>()
        val requests = CopyOnWriteArrayList<CancelledPlanPageQuery>()
        var reader: suspend (CancelledPlanPageQuery) -> CancelledPlanPage = {
            CancelledPlanPage(rows.drop(it.offset).take(it.limit), it.offset + it.limit < rows.size)
        }
        var weekReader: suspend (com.alexandr5476.lifetracing.domain.WeekPlanQuery) -> WeekPlanRead = {
            WeekPlanRead(it.weekStart, it.selectedDate, emptyList(), emptyList(), emptyList())
        }
        var commit: suspend (PlanMutation) -> Unit = { mutation ->
            if (mutation is PlanMutation.Restore) rows = rows.filterNot { it.plan.id == mutation.identity.planEntryId }
        }
        lateinit var controller: PlanController
        val authority get() = requireNotNull(controller.state.value.cancelledContext)

        fun start() {
            controller =
                PlanController(
                    scope,
                    { weekReader(it) },
                    { _, _, _ -> emptyList() },
                    {
                        requests += it
                        reader(it)
                    },
                    {
                        commands += it
                        clock = clock.plusSeconds(1)
                        commit(it)
                    },
                    { clock },
                    { zone },
                    boundaryScheduler = LocalDateBoundaryScheduler { _, _, _, _ -> },
                )
        }

        override fun close() {
            controller.close()
            scope.cancel()
        }
    }

    private fun summary(
        id: String,
        title: String = "Same frozen title",
        sequence: Boolean = false,
    ): PlanReadRow =
        PlanReadRow(
            PlanEntry(
                PlanEntryId(id),
                if (sequence) PlanTrackableKind.SEQUENCE else PlanTrackableKind.ACTIVITY,
                null,
                null,
                null,
                if (sequence) null else ActivitySnapshotId("snapshot-$id"),
                if (sequence) {
                    com.alexandr5476.lifetracing.domain
                        .SequenceSnapshotId("snapshot-$id")
                } else {
                    null
                },
                PlanTarget.ExactDay(Instant.parse("2026-10-24T08:00:27.123Z"), ZoneId.of("Pacific/Auckland")),
                PlanEntryStatus.CANCELLED,
                null,
                null,
                at,
                at,
                at,
                null,
            ),
            null,
            null,
            title,
            "frozen comment",
            PlanSourceState.UNAVAILABLE,
            false,
            false,
            null,
        )
}
