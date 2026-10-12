@file:Suppress("LongMethod")

package com.alexandr5476.lifetracing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.data.persistence.PlanReadRepository
import com.alexandr5476.lifetracing.data.persistence.PlanRepository
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CancelledPlanPage
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.plan.PlanAction
import com.alexandr5476.lifetracing.plan.PlanController
import com.alexandr5476.lifetracing.plan.PlanLoad
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MainActivityPlanVisibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun forwardExecutionHidesPendingCalendarWorkAndBackReloadsTheRetainedDate() {
        val activity = compose.activity
        val at = Instant.now().minusSeconds(10)
        val date = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        val source =
            TemplateAuthoringRepository.create(activity).createActivityTemplate(
                ActivityTemplateDraft("Visibility-${System.nanoTime()}", null, TimeTrackingMode.NO_LIVE_TRACKING, null),
                createdAt = at,
            )
        val plan =
            PlanRepository.create(activity).createActivityPlanFromTemplate(
                source.id,
                PlanSchedule.FloatingDay(date),
                at.plusMillis(1),
            )
        val canonical = PlanReadRepository.create(activity)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val semantic = MutableStateFlow(0L)
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val completionReadStarted = CompletableDeferred<Unit>()
        val releaseCompletionRead = CompletableDeferred<Unit>()
        val queries = mutableListOf<WeekPlanQuery>()
        var deferRead = false
        var deferCompletionRead = false
        var zone = ZoneId.systemDefault()
        var arms = 0
        var cancels = 0
        var boundary: (() -> Unit)? = null
        val scheduler =
            object : LocalDateBoundaryScheduler {
                override fun arm(
                    now: Instant,
                    zoneId: ZoneId,
                    exactBoundary: Instant?,
                    onBoundary: () -> Unit,
                ) {
                    arms++
                    boundary = onBoundary
                }

                override fun cancel() {
                    cancels++
                }
            }
        val controller =
            PlanController(
                scope,
                { query ->
                    queries += query
                    if (deferCompletionRead) {
                        completionReadStarted.complete(Unit)
                        releaseCompletionRead.await()
                    }
                    val result = withContext(Dispatchers.IO) { canonical.getWeek(query) }
                    if (deferRead) {
                        release.await()
                        finished.complete(Unit)
                    }
                    result
                },
                { _, _, _ -> emptyList() },
                { CancelledPlanPage(emptyList(), false) },
                {},
                zoneId = { zone },
                semanticGeneration = semantic,
                boundaryScheduler = scheduler,
                initiallyVisible = false,
            )
        try {
            compose.runOnUiThread { activity.planControllerOwner.get { controller } }
            compose.onNodeWithText(activity.getString(R.string.daily_plan)).performClick()
            compose.waitUntil(5_000) { controller.state.value.week is PlanLoad.Content }
            val selected = controller.state.value.selectedDate
            val captured = requireNotNull(boundary)
            compose
                .onNodeWithTag("plan-plan-action-${plan.id.value}")
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick) { click ->
                    // Admit a deferred read in the same navigation callback, before Compose commits the new route.
                    click()
                    deferRead = true
                    controller.dispatch(PlanAction.Refresh)
                }
            compose.waitUntil(5_000) { activity.planExecutionRouteSessions.activeSession != null }
            compose.waitForIdle()
            assertTrue(cancels > 0)
            val hiddenReads = queries.size
            val hiddenArms = arms
            compose.runOnUiThread {
                zone = ZoneId.of("Pacific/Auckland")
                semantic.value++
                release.complete(Unit)
                captured()
            }
            compose.waitUntil(5_000) { finished.isCompleted }
            compose.waitForIdle()
            assertEquals(hiddenReads, queries.size)
            assertEquals(hiddenArms, arms)
            assertFalse(controller.state.value.week is PlanLoad.Content)
            compose.runOnUiThread { deferRead = false }
            compose.onNodeWithText(activity.getString(R.string.plan_back)).performClick()
            compose.waitUntil(5_000) { controller.state.value.week is PlanLoad.Content }
            assertEquals(hiddenReads + 1, queries.size)
            assertEquals(selected, controller.state.value.selectedDate)
            assertEquals(zone, queries.last().zoneId)
            val read = (controller.state.value.week as PlanLoad.Content).value
            assertEquals(zone, requireNotNull(read.temporalContext).zoneId)
            val beforeCompletion = queries.size
            compose.onNodeWithTag("plan-plan-action-${plan.id.value}").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                activity.planExecutionRouteSessions.activeSession
                    ?.controller
                    ?.state
                    ?.value
                    ?.prepared is
                    com.alexandr5476.lifetracing.plan.PlanExecutionLoad.Content
            }
            compose.runOnUiThread { deferCompletionRead = true }
            compose.onNodeWithTag("plan-execution-submit").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                activity.planExecutionRouteSessions.activeSession == null &&
                    completionReadStarted.isCompleted
            }
            assertEquals(beforeCompletion + 1, queries.size)
            assertTrue(controller.state.value.week is PlanLoad.Loading)
            compose.runOnUiThread { releaseCompletionRead.complete(Unit) }
            compose.waitUntil(5_000) {
                val refreshed = (controller.state.value.week as? PlanLoad.Content)?.value
                refreshed != null && refreshed !== read
            }
            assertEquals(beforeCompletion + 1, queries.size)
            assertEquals(selected, controller.state.value.selectedDate)
            assertEquals(
                com.alexandr5476.lifetracing.domain.PlanEntryStatus.FULFILLED,
                (controller.state.value.week as PlanLoad.Content)
                    .value.selectedDayPlans
                    .single { it.plan.id == plan.id }
                    .plan.status,
            )
            compose.onNodeWithText(activity.getString(R.string.plan_back)).performClick()
            compose.waitForIdle()
            val afterBack = queries.size
            compose.runOnUiThread {
                semantic.value++
                captured()
            }
            compose.waitForIdle()
            assertEquals(afterBack, queries.size)
        } finally {
            release.complete(Unit)
            releaseCompletionRead.complete(Unit)
            compose.runOnUiThread {
                controller.close()
                scope.cancel()
            }
        }
    }
}
