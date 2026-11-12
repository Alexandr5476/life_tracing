@file:Suppress("LongMethod")

package com.alexandr5476.lifetracing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.data.persistence.PlanReadRepository
import com.alexandr5476.lifetracing.data.persistence.PlanRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.plan.PlanAction
import com.alexandr5476.lifetracing.plan.PlanController
import com.alexandr5476.lifetracing.plan.PlanExecutionController
import com.alexandr5476.lifetracing.plan.PlanExecutionOrigin
import com.alexandr5476.lifetracing.plan.PlanLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class PlanExecuteNavigationAuthorityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun staleDayWeekAndReplacedRowsCannotNavigateButCurrentDuplicateCan() {
        Fixture().use { f ->
            for (id in listOf(f.day, f.week)) {
                val original = f.capture(id)
                for (action in listOf(PlanAction.NextWeek, PlanAction.PreviousWeek)) {
                    compose.runOnUiThread {
                        f.controller.dispatch(action)
                        original()
                        f.assertNoExecution()
                    }
                    f.awaitContent()
                    compose.runOnUiThread {
                        original()
                        f.assertNoExecution()
                    }
                }
                val beforeDayChange = f.capture(id)
                compose.runOnUiThread {
                    val state = f.controller.state.value
                    val other = state.weekStart.plusDays((state.selectedDate.dayOfWeek.value % 7).toLong())
                    f.controller.dispatch(PlanAction.SelectDate(other))
                    beforeDayChange()
                    f.assertNoExecution()
                }
                f.awaitContent()
                compose.runOnUiThread {
                    beforeDayChange()
                    f.assertNoExecution()
                    f.controller.dispatch(PlanAction.Today)
                }
                f.awaitContent()
                val replaced = f.capture(id)
                val prior = (f.controller.state.value.week as PlanLoad.Content).value
                compose.runOnUiThread { f.controller.dispatch(PlanAction.Refresh) }
                f.awaitContent()
                val current = (f.controller.state.value.week as PlanLoad.Content).value
                assertTrue(prior !== current)
                compose.runOnUiThread {
                    replaced()
                    f.assertNoExecution()
                }
            }
            f.assertUnchanged()
            // Identical frozen titles and targets still dispatch the exact second Plan identity.
            val execute = f.capture(f.duplicate)
            compose.runOnUiThread { execute() }
            compose.waitUntil(5_000) { compose.activity.planExecutionRouteSessions.activeSession != null }
            assertEquals(
                f.duplicate,
                compose.activity.planExecutionRouteSessions.activeSession!!
                    .expectedIdentity.planEntryId,
            )
            f.assertUnchanged()
        }
    }

    @Test
    fun modalAndOutgoingRouteCallbacksCannotAcquireSessionOrChangeDestination() {
        Fixture().use { f ->
            for (open in listOf(PlanAction.OpenCatalog, PlanAction.OpenCancelled)) {
                val execute = f.capture(f.day)
                compose.runOnUiThread {
                    f.controller.dispatch(open)
                    execute()
                    f.assertNoExecution()
                }
                compose.waitForIdle()
                compose.runOnUiThread {
                    execute()
                    f.assertNoExecution()
                    val state = f.controller.state.value
                    state.catalog?.let { f.controller.dispatch(PlanAction.DismissCatalog(it.context)) }
                    state.cancelledContext?.let { f.controller.dispatch(PlanAction.DismissCancelled(it)) }
                }
            }
            val execute = f.capture(f.day)
            compose.runOnUiThread {
                val row =
                    (f.controller.state.value.week as PlanLoad.Content).value.selectedDayPlans.single {
                        it.plan.id ==
                            f.day
                    }
                f.controller.dispatch(PlanAction.Reschedule(row))
                execute()
                f.assertNoExecution()
            }
            val form = requireNotNull(f.controller.state.value.form)
            compose.runOnUiThread { f.controller.dispatch(PlanAction.DismissForm(form.formId)) }
            val outgoing = f.capture(f.day)
            val back =
                compose
                    .onNodeWithText(compose.activity.getString(R.string.plan_back))
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            // Both callbacks run before the visibility DisposableEffect can hide the controller.
            compose.runOnUiThread {
                back()
                outgoing()
                f.assertNoExecution()
            }
            compose.waitForIdle()
            compose.onNodeWithText(compose.activity.getString(R.string.daily_plan)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.daily_history)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.runOnUiThread {
                outgoing()
                f.assertNoExecution()
            }
            compose.waitForIdle()
            compose.onNodeWithText(compose.activity.getString(R.string.history_title)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.history_back)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(compose.activity.getString(R.string.daily_plan)).assertIsDisplayed()
            f.assertUnchanged()
        }
    }

    @Test
    fun rejectedModalExecuteCannotNavigateOrInvokeARetainedExecutionWriter() {
        Fixture().use { f ->
            val obsolete = f.capture(f.day)
            val retained = compose.runOnIdle { f.retainExecutionProbe() }
            compose.runOnUiThread {
                f.controller.dispatch(PlanAction.OpenCatalog)
                obsolete()
                assertSame(retained, compose.activity.planExecutionRouteSessions.activeSession)
                assertEquals(0, f.executionWrites)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("plan-catalog-search").assertIsDisplayed()
            assertSame(retained, compose.activity.planExecutionRouteSessions.activeSession)
            assertEquals(0, f.executionWrites)
            f.assertUnchanged()
        }
    }

    private inner class Fixture : AutoCloseable {
        private val now = Instant.ofEpochMilli(System.currentTimeMillis())
        private val at = now.minusSeconds(10)
        private val date = now.atZone(ZoneId.systemDefault()).toLocalDate()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val reads = PlanReadRepository.create(compose.activity)
        private val plans = PlanRepository.create(compose.activity)
        private val source =
            TemplateAuthoringRepository.create(compose.activity).createActivityTemplate(
                ActivityTemplateDraft(
                    "Execute authority ${System.nanoTime()}",
                    null,
                    TimeTrackingMode.NO_LIVE_TRACKING,
                    null,
                ),
                createdAt = at,
            )
        private val originals =
            listOf(
                plans.createActivityPlanFromTemplate(source.id, PlanSchedule.FloatingDay(date), at.plusMillis(1)),
                plans.createActivityPlanFromTemplate(source.id, PlanSchedule.FloatingDay(date), at.plusMillis(2)),
                plans.createActivityPlanFromTemplate(
                    source.id,
                    PlanSchedule.Week(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))),
                    at.plusMillis(3),
                ),
            )
        val day = originals[0].id
        val duplicate = originals[1].id
        val week = originals[2].id
        private var managementWrites = 0
        var executionWrites = 0
            private set
        val controller =
            PlanController(
                scope,
                { query -> withContext(Dispatchers.IO) { reads.getWeek(query) } },
                { _, _, _ -> emptyList() },
                { CancelledPlanPage(emptyList(), false) },
                { managementWrites++ },
                now = { now },
                boundaryScheduler = LocalDateBoundaryScheduler { _, _, _, _ -> },
                initiallyVisible = false,
            )

        init {
            compose.runOnUiThread { compose.activity.planControllerOwner.get { controller } }
            compose.onNodeWithText(compose.activity.getString(R.string.daily_plan)).performClick()
            awaitContent()
        }

        fun capture(id: PlanEntryId): () -> Boolean =
            compose
                .onNodeWithTag("plan-plan-action-${id.value}")
                .performScrollTo()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!

        fun awaitContent() {
            compose.waitUntil(5_000) { controller.state.value.week is PlanLoad.Content }
            compose.waitForIdle()
        }

        fun retainExecutionProbe(): com.alexandr5476.lifetracing.plan.PlanExecutionRouteSession {
            val identity = originals.first().actionIdentity()
            val graph = LifeTracingRuntimeGraph.from(compose.activity)
            return requireNotNull(
                compose.activity.planExecutionRouteSessions.acquire(identity, PlanExecutionOrigin.PLAN) {
                    PlanExecutionController(
                        scope,
                        identity,
                        { id -> withContext(Dispatchers.IO) { reads.getFocusedAction(id) } },
                        { false },
                        {
                            executionWrites++
                            error("Rejected navigation must not reach the execution writer")
                        },
                        {},
                        WallClock(Instant::now),
                        { ZoneId.systemDefault() },
                        object : PreflightScheduler {
                            override fun schedule(
                                duration: Duration,
                                onBoundary: () -> Unit,
                            ): PreflightHandle = error("No-live probe has no preflight")
                        },
                        graph.coordinator.mutationGate,
                    )
                },
            )
        }

        fun assertNoExecution() {
            assertNull(compose.activity.planExecutionRouteSessions.activeSession)
            assertEquals(0, executionWrites)
            assertEquals(0, managementWrites)
        }

        fun assertUnchanged() {
            // Navigation alone must leave canonical linkage/status/tokens unchanged.
            originals.forEach { assertEquals(it.actionIdentity(), reads.getFocusedAction(it.id).identity) }
            assertEquals(0, executionWrites)
            assertEquals(0, managementWrites)
        }

        override fun close() {
            compose.runOnUiThread {
                compose.activity.planExecutionRouteSessions.activeSession?.let {
                    compose.activity.planExecutionRouteSessions.release(it)
                }
                controller.close()
                scope.cancel()
            }
        }
    }
}
