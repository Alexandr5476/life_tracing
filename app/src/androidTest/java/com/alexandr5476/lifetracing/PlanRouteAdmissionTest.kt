@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.alexandr5476.lifetracing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.plan.PlanController
import com.alexandr5476.lifetracing.plan.PlanLoad
import com.alexandr5476.lifetracing.plan.PlanMutation
import com.alexandr5476.lifetracing.plan.PlanScheduleKind
import com.alexandr5476.lifetracing.plan.catalogResultTag
import com.alexandr5476.lifetracing.runtime.RuntimeIsolationApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Native production callbacks run after Back, before Compose can publish hidden visibility. */
class PlanRouteAdmissionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun sameTurnBackRejectsCancelWithAValidCanonicalIdentity() =
        journey { f ->
            f.rejectAfterBack(f.capture("plan-cancel-${f.plan.id.value}"))
            f.reopenUnchanged()
        }

    @Test
    fun sameTurnBackRejectsChangedSourceUpdateWithAValidCanonicalIdentity() =
        journey { f ->
            assertEquals(PlanSourceState.CHANGED, f.row().sourceState)
            f.rejectAfterBack(f.capture("plan-update-${f.plan.id.value}"))
            f.reopenUnchanged()
        }

    @Test
    fun sameTurnBackRejectsRestoreFromTheStillOwnedCancelledDialog() =
        journey { f ->
            val back = f.back()
            f.click("plan-cancelled-entry")
            f.await { f.controller.state.value.cancelled is PlanLoad.Content }
            f.rejectAfterBack(f.capture("plan-restore-${f.cancelled.id.value}"), back)
            f.reopenUnchanged()
        }

    @Test
    fun sameTurnBackRejectsEditedRescheduleSubmissionFromTheStillOwnedForm() =
        journey { f ->
            val back = f.back()
            f.click("plan-reschedule-${f.plan.id.value}")
            compose.onNodeWithTag("plan-schedule-date").performTextReplacement(f.otherDate.toString())
            assertFalse(requireNotNull(f.controller.state.value.form).preservesExistingSchedule)
            f.rejectAfterBack(f.capture("plan-schedule-save"), back)
            f.reopenUnchanged()
        }

    @Test
    fun sameTurnBackRejectsNewPlanSubmissionFromTheStillOwnedForm() =
        journey { f ->
            val back = f.back()
            f.openCreation()
            f.rejectAfterBack(f.capture("plan-schedule-save"), back)
            f.reopenUnchanged()
        }

    @Test
    fun sameTurnBackRejectsCalendarAndCatalogCallbacksAndRetainsReentryDefaults() =
        journey { f ->
            // Establish a non-Today context so stale Today would observably change it.
            f.click("plan-next-week")
            f.awaitContent()
            val state = f.controller.state.value
            val other = state.weekStart.plusDays(if (state.selectedDate == state.weekStart) 1 else 0)
            val callbacks =
                listOf(
                    f.capture("plan-previous-week"),
                    f.capture("plan-next-week"),
                    f.captureText(R.string.plan_today),
                    f.capture("plan-day-$other"),
                    f.capture("plan-add"),
                    f.capture("plan-cancelled-entry"),
                )
            val back = f.back()
            val reads = f.reads
            compose.runOnUiThread {
                back()
                callbacks.forEach {
                    it()
                    assertEquals(state.weekStart, f.controller.state.value.weekStart)
                    assertEquals(state.selectedDate, f.controller.state.value.selectedDate)
                    assertNull(f.controller.state.value.catalog)
                    assertFalse(f.controller.state.value.cancelledOpen)
                    assertEquals(reads, f.reads)
                    f.assertNoCommands()
                }
            }
            f.assertDaily()
            assertEquals(reads, f.reads)
            f.enterPlan()
            assertEquals(reads + 1, f.reads)
            assertEquals(state.weekStart, f.controller.state.value.weekStart)
            assertEquals(state.selectedDate, f.controller.state.value.selectedDate)
            f.openCreation()
            val form = requireNotNull(f.controller.state.value.form)
            assertEquals(PlanScheduleKind.FLOATING_DAY, form.kind)
            assertEquals(state.selectedDate.toString(), form.date)
            f.click("plan-schedule-close")
            f.reopenUnchanged()
        }

    @Test
    fun focusedExecutionTransitionRejectsRetainedCalendarAndManagementActionsAndReturns() =
        journey { f ->
            val state = f.controller.state.value
            val stale =
                listOf(
                    f.capture("plan-next-week"),
                    f.capture("plan-add"),
                    f.capture("plan-cancel-${f.plan.id.value}"),
                    f.capture("plan-update-${f.plan.id.value}"),
                )
            val execute = f.capture("plan-plan-action-${f.plan.id.value}")
            val reads = f.reads
            compose.runOnUiThread {
                execute()
                val session = requireNotNull(f.host().planExecutionRouteSessions.activeSession)
                stale.forEach { it() }
                execute()
                assertEquals(session, f.host().planExecutionRouteSessions.activeSession)
                assertEquals(state.weekStart, f.controller.state.value.weekStart)
                assertEquals(state.selectedDate, f.controller.state.value.selectedDate)
                assertNull(f.controller.state.value.catalog)
                assertEquals(reads, f.reads)
                assertEquals(0, f.writes.size)
            }
            compose.waitForIdle()
            compose.onNodeWithText(f.host().getString(R.string.plan_back)).performClick()
            f.awaitContent()
            assertEquals(state.selectedDate, f.controller.state.value.selectedDate)
            f.assertNoCommands()
            f.reopenUnchanged()
        }

    @Test
    fun currentRouteCancelRestoreUpdateAndSubmitPersistThroughPhysicalReopen() =
        journey { f ->
            f.click("plan-update-${f.plan.id.value}")
            f.awaitMutation(1)
            val updated = requireNotNull(f.store.plans.getPlan(f.plan.id))
            assertEquals(f.source.revision + 1, updated.sourceRevision)
            assertTrue(updated.activitySnapshotId != f.plan.activitySnapshotId)
            f.click("plan-reschedule-${f.plan.id.value}")
            compose.onNodeWithTag("plan-schedule-date").performTextReplacement(f.otherDate.toString())
            f.click("plan-schedule-save")
            f.awaitMutation(2)
            val rescheduled = requireNotNull(f.store.plans.getPlan(f.plan.id))
            assertEquals(PlanTarget.FloatingDay(f.otherDate), rescheduled.target)
            assertEquals(updated.activitySnapshotId, rescheduled.activitySnapshotId)
            f.click("plan-day-${f.otherDate}")
            f.awaitContent()
            f.click("plan-cancel-${f.plan.id.value}")
            f.awaitMutation(3)
            f.click("plan-cancelled-entry")
            f.await { f.controller.state.value.cancelled is PlanLoad.Content }
            f.click("plan-restore-${f.plan.id.value}")
            f.awaitMutation(4)
            f.click("plan-cancelled-close")
            f.openCreation()
            f.click("plan-schedule-save")
            f.awaitMutation(5)
            val expected = f.store.durableFacts()
            f.reopen(expected)
            val restored = requireNotNull(f.store.plans.getPlan(f.plan.id))
            assertEquals(PlanEntryStatus.PLANNED, restored.status)
            assertEquals(rescheduled.target, restored.target)
            assertEquals(updated.activitySnapshotId, restored.activitySnapshotId)
            assertEquals(updated.sourceRevision, restored.sourceRevision)
            assertEquals(
                restored.actionIdentity(),
                f.store.planReads
                    .getFocusedAction(restored.id)
                    .identity,
            )
            assertEquals(
                2,
                f.store.planReads
                    .getWeek(f.query(f.otherDate))
                    .selectedDayPlans.size,
            )
        }

    @Test
    fun writerAdmittedBeforeBackCompletesOnceWithoutReplayOnReentry() =
        journey { f ->
            val release = CompletableDeferred<Unit>()
            f.writerPermit = release
            val cancel = f.capture("plan-cancel-${f.plan.id.value}")
            val back = f.back()
            compose.runOnUiThread {
                cancel()
                assertEquals(1, f.writes.size)
                back()
                cancel()
                assertEquals(1, f.writes.size)
            }
            f.assertDaily()
            compose.runOnUiThread { release.complete(Unit) }
            f.await { f.writerFinished.isCompleted }
            f.enterPlan()
            f.awaitMutation(1)
            assertEquals(1, f.writes.size)
            val expected = f.store.durableFacts()
            f.reopen(expected)
            assertEquals(
                PlanEntryStatus.CANCELLED,
                f.store.plans
                    .getPlan(f.plan.id)
                    ?.status,
            )
            assertEquals(
                f.plan.activitySnapshotId,
                f.store.plans
                    .getPlan(f.plan.id)
                    ?.activitySnapshotId,
            )
        }

    private fun journey(body: (Fixture) -> Unit) =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<RuntimeIsolationApplication>()
            app.withDisposableRuntime { environment ->
                Fixture(app) { runBlocking { environment.close() } }.use(body)
            }
        }

    private inner class Fixture(
        private val app: RuntimeIsolationApplication,
        private val closeGraph: () -> Unit,
    ) : AutoCloseable {
        private val now = Instant.ofEpochMilli(Instant.now().toEpochMilli())
        val date = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val otherDate =
            date
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusDays(if (date.dayOfWeek == DayOfWeek.MONDAY) 1 else 0)
        var store = RuntimeRecoveryStore(app, "lifetracing.db")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val source =
            store.authoring.createActivityTemplate(
                ActivityTemplateDraft("Route authority", null, TimeTrackingMode.NO_LIVE_TRACKING, null),
                createdAt = now.minusSeconds(10),
            )
        val plan =
            store.plans.createActivityPlanFromTemplate(
                source.id,
                PlanSchedule.FloatingDay(date),
                now.minusSeconds(9),
            )
        private val duplicate =
            store.plans.createActivityPlanFromTemplate(source.id, PlanSchedule.FloatingDay(date), now.minusSeconds(8))
        val cancelled =
            store.plans
                .createActivityPlanFromTemplate(source.id, PlanSchedule.FloatingDay(date), now.minusSeconds(7))
                .let { store.plans.cancelPlan(it.actionIdentity(), now.minusSeconds(6)) }
        val writes = mutableListOf<PlanMutation>()
        val writerFinished = CompletableDeferred<Unit>()
        var reads = 0
        var writerPermit = CompletableDeferred<Unit>().apply { complete(Unit) }
        private var clock = now
        val controller =
            PlanController(
                scope,
                { query ->
                    reads++
                    withContext(Dispatchers.IO) { store.planReads.getWeek(query) }
                },
                { query, limit, after ->
                    reads++
                    withContext(Dispatchers.IO) { store.library.getReusablePlanCatalog(query, limit, after) }
                },
                { query ->
                    reads++
                    withContext(Dispatchers.IO) { store.planReads.getCancelledPage(query) }
                },
                { command ->
                    writes += command
                    writerPermit.await()
                    withContext(Dispatchers.IO) { executePlanMutation(command, store.plans) }
                    writerFinished.complete(Unit)
                },
                now = { clock.also { clock = it.plusMillis(1) } },
                boundaryScheduler = LocalDateBoundaryScheduler { _, _, _, _ -> },
                initiallyVisible = false,
            )
        private var scenario: ActivityScenario<MainActivity>? = null
        private var activity: MainActivity? = null
        private val baseline: Map<String, List<List<String?>>>

        init {
            store.authoring.saveActivityTemplate(
                source.id,
                source.revision,
                source.toAuthoringDraft().copy(shortComment = "Changed source"),
                now.minusSeconds(5),
            )
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity { activity = it }
            compose.runOnUiThread { host().planControllerOwner.get { controller } }
            enterPlan()
            baseline = store.durableFacts()
        }

        fun host() = requireNotNull(activity)

        fun query(selected: LocalDate) =
            com.alexandr5476.lifetracing.domain.WeekPlanQuery(
                selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                selected,
                now,
            )

        fun row() =
            (controller.state.value.week as PlanLoad.Content).value.selectedDayPlans.single {
                it.plan.id ==
                    plan.id
            }

        fun await(condition: () -> Boolean) = compose.waitUntil(10_000, condition)

        fun awaitContent() {
            await { controller.state.value.week is PlanLoad.Content }
            compose.waitForIdle()
        }

        fun awaitMutation(count: Int) {
            await { !controller.state.value.isMutating && writes.size == count }
            assertNull(controller.state.value.mutationFailure)
            compose.waitForIdle()
        }

        fun enterPlan() {
            compose.onNodeWithText(host().getString(R.string.daily_plan)).performScrollTo().performClick()
            awaitContent()
        }

        fun click(tag: String) {
            val action = capture(tag)
            compose.runOnUiThread { assertTrue(action()) }
        }

        fun capture(tag: String): () -> Boolean {
            val node = compose.onNodeWithTag(tag)
            // Native dialog footer buttons have no scrollable ancestor.
            if (tag !in setOf("plan-schedule-save", "plan-schedule-close", "plan-cancelled-close")) {
                node.performScrollTo()
            }
            return node
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        }

        fun captureText(id: Int): () -> Boolean =
            compose
                .onNodeWithText(host().getString(id))
                .performScrollTo()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!

        fun back() = captureText(R.string.plan_back)

        fun openCreation() {
            click("plan-add")
            await {
                controller.state.value.catalog
                    ?.loading == false
            }
            val item = requireNotNull(controller.state.value.catalog).items.single()
            click(catalogResultTag(item.id))
        }

        fun rejectAfterBack(
            action: () -> Boolean,
            back: () -> Boolean = this.back(),
        ) {
            compose.runOnUiThread {
                back()
                action()
                assertNoCommands()
            }
            assertDaily()
        }

        fun assertDaily() {
            compose.waitForIdle()
            compose.onNodeWithText(host().getString(R.string.daily_plan)).assertIsDisplayed()
        }

        fun assertNoCommands() {
            assertEquals(0, writes.size)
            assertNull(host().planExecutionRouteSessions.activeSession)
        }

        fun reopenUnchanged() {
            assertNoCommands()
            reopen(baseline)
            listOf(plan, duplicate, cancelled).forEach {
                assertEquals(it, store.plans.getPlan(it.id))
                assertEquals(it.actionIdentity(), store.planReads.getFocusedAction(it.id).identity)
            }
            assertEquals(PlanSourceState.CHANGED, store.planReads.getFocusedAction(plan.id).sourceState)
        }

        fun reopen(expected: Map<String, List<List<String?>>>) {
            assertEquals(expected, store.durableFacts())
            scenario?.close()
            scenario = null
            compose.runOnUiThread { controller.close() }
            scope.cancel()
            closeGraph()
            store.close()
            store = RuntimeRecoveryStore(app, "lifetracing.db")
            assertEquals(expected, store.durableFacts())
            assertEquals(0L to 0L, store.executionCounts())
            assertNull(store.live.getActiveSession())
        }

        override fun close() {
            writerPermit.complete(Unit)
            scenario?.close()
            compose.runOnUiThread { controller.close() }
            scope.cancel()
            store.close()
        }
    }
}
