@file:Suppress("LongMethod", "MagicNumber")

package com.alexandr5476.lifetracing.daily

import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.MonotonicClock
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import com.alexandr5476.lifetracing.runtime.AndroidRuntimeCoordinator
import com.alexandr5476.lifetracing.runtime.InProcessRuntimeDeadlineDriver
import com.alexandr5476.lifetracing.runtime.RuntimeDeadlineScheduler
import com.alexandr5476.lifetracing.runtime.RuntimeFeedbackDispatcher
import com.alexandr5476.lifetracing.runtime.RuntimeNotificationPublisher
import com.alexandr5476.lifetracing.ui.components.LifeTracingSecondaryButton
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class DailyAccessibilityPersistenceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun semanticPauseResumeFinishPreserveCanonicalExecutionAfterPhysicalReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "daily-accessibility-${UUID.randomUUID()}.db"
        val startedAt = Instant.parse("2026-08-20T10:00:00Z")
        val now = AtomicReference(startedAt.plusSeconds(10))
        val wallClock = WallClock { now.get() }
        val bootOrigin = SystemClock.elapsedRealtime() - 10_000
        var store = RuntimeRecoveryStore(context, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var controller: DailyController? = null
        val destination = mutableStateOf(false)
        try {
            val template =
                store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Frozen accessible stopwatch", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = startedAt,
                )
            val execution = store.library.startActivityFromTemplate(template.id, startedAt, startedAt, ZoneOffset.UTC)
            val coordinator =
                AndroidRuntimeCoordinator(
                    store.live,
                    wallClock,
                    MonotonicClock { bootOrigin + Duration.between(startedAt, now.get()).toMillis() },
                    object : RuntimeDeadlineScheduler {
                        override fun schedule(deadline: RuntimeDeadline) = error("Stopwatch must not have a deadline")

                        override fun cancel() = Unit

                        override fun canScheduleExactRuntimeDeadlines() = true
                    },
                    object : InProcessRuntimeDeadlineDriver {
                        override fun arm(
                            deadline: RuntimeDeadline,
                            anchor: WallMonotonicAnchor,
                            callback: suspend (RuntimeDeadline) -> Unit,
                        ) = error("Stopwatch must not have a deadline")

                        override fun cancel() = Unit
                    },
                    RuntimeFeedbackDispatcher { error("Explicit stopwatch commands must not emit timer feedback") },
                    object : RuntimeNotificationPublisher {
                        override fun publish(
                            runtime: ActiveRuntime?,
                            completion: RuntimeDeadlineFeedback?,
                        ) = Unit

                        override fun canPostRuntimeNotifications() = true
                    },
                )
            runBlocking { coordinator.onForeground() }
            val daily =
                DailyController(
                    scope,
                    { query -> withContext(Dispatchers.IO) { store.daily.getDaily(query) } },
                    { command ->
                        withContext(Dispatchers.IO) {
                            when (command) {
                                is DailyRuntimeCommand.PauseActivity ->
                                    store.live.pauseActiveActivity(
                                        command.pauseId,
                                        command.at,
                                    )
                                is DailyRuntimeCommand.ResumeActivity -> store.live.resumeActiveActivity(command.at)
                                is DailyRuntimeCommand.FinishActivity -> store.live.completeActiveActivity(command.at)
                                else -> error("Unexpected compact Activity command")
                            }
                        }
                    },
                    { withContext(Dispatchers.IO) { coordinator.onRuntimeStateChanged() } },
                    coordinator.semanticGeneration,
                    { coordinator.displayBaseline },
                    wallClock,
                    { ZoneOffset.UTC },
                    { ActivityExecutionPauseId("accessible-pause") },
                    CoroutineLocalDateBoundaryScheduler(scope),
                    mutationGate = coordinator.mutationGate,
                )
            controller = daily
            compose.setContent {
                LifeTracingTheme {
                    if (destination.value) {
                        LifeTracingSecondaryButton(
                            onClick = { destination.value = false },
                        ) { Text("Return to Daily fixture") }
                    } else {
                        DailyRoute(daily, onStartActivity = { destination.value = true })
                    }
                }
            }
            awaitActivity(daily, ActiveSessionState.RUNNING)
            invoke(R.string.daily_pause)
            awaitActivity(daily, ActiveSessionState.PAUSED)
            assertEquals(execution.id, store.live.getActiveSession()!!.activityExecutionId)
            assertEquals(ActiveSessionState.PAUSED, store.live.getActiveSession()!!.state)
            assertEquals(
                startedAt.plusSeconds(10),
                store
                    .activity(execution.id)
                    .pauses
                    .single()
                    .startedAt,
            )
            val pausedFacts = store.durableFacts()
            invoke(R.string.daily_start_activity)
            compose.onNodeWithText("Return to Daily fixture").performSemanticsAction(SemanticsActions.OnClick)
            awaitActivity(daily, ActiveSessionState.PAUSED)
            assertEquals(pausedFacts, store.durableFacts())
            compose
                .onNode(
                    hasText(compose.activity.getString(R.string.daily_pause)) and hasClickAction(),
                ).assertDoesNotExist()
            now.set(startedAt.plusSeconds(25))
            invoke(R.string.daily_resume)
            awaitActivity(daily, ActiveSessionState.RUNNING)
            assertEquals(ActiveSessionState.RUNNING, store.live.getActiveSession()!!.state)
            assertEquals(execution.id, store.live.getActiveSession()!!.activityExecutionId)
            now.set(startedAt.plusSeconds(40))
            invoke(R.string.daily_finish)
            compose.waitUntil(10_000) {
                !daily.state.value.commandInFlight &&
                    (daily.state.value.load as? DailyLoadState.Content)?.daily?.let {
                        it.active == null && it.completedHistory.size == 1
                    } == true
            }
            for (label in listOf(R.string.daily_pause, R.string.daily_resume, R.string.daily_finish)) {
                compose.onNode(hasText(compose.activity.getString(label)) and hasClickAction()).assertDoesNotExist()
            }
            assertNull(daily.state.value.commandFailure)
            val completed = store.activity(execution.id)
            assertEquals(ActivityExecutionStatus.COMPLETED, completed.status)
            assertEquals(startedAt.plusSeconds(40), completed.completedAt)
            assertEquals(startedAt.plusSeconds(25), completed.pauses.single().endedAt)
            assertEquals(execution.snapshotId, completed.snapshotId)
            val facts = store.durableFacts()
            compose.runOnIdle {
                destination.value = true
                daily.close()
            }
            scope.cancel()
            compose.waitForIdle()
            store.close()
            store = RuntimeRecoveryStore(context, name)
            assertNull(store.live.getActiveSession())
            assertNull(store.live.getActiveRuntime())
            assertEquals(completed, store.activity(execution.id))
            val date = startedAt.atZone(ZoneOffset.UTC).toLocalDate()
            val history = store.history.getCompletedRoots(CompletedHistoryQuery(HistoryDateRange(date, date), 10))
            val root = history.single() as CompletedActivityHistoryRoot
            assertEquals(execution.id, root.executionId)
            assertEquals(Duration.ofSeconds(25), root.activeDuration)
            assertEquals(startedAt, root.startedAt)
            assertEquals(startedAt.plusSeconds(40), root.completedAt)
            assertEquals(ZoneOffset.UTC, root.originalZoneId)
            assertEquals(
                history,
                store.daily.getDaily(DailyQuery(date, now.get(), 10, ZoneOffset.UTC)).completedHistory,
            )
            assertEquals(1L to 0L, store.executionCounts())
            assertEquals(facts, store.durableFacts())
        } finally {
            compose.runOnIdle {
                destination.value = true
                controller?.close()
            }
            scope.cancel()
            store.close()
            context.deleteDatabase(name)
        }
    }

    private fun awaitActivity(
        controller: DailyController,
        expected: ActiveSessionState,
    ) {
        val action = if (expected == ActiveSessionState.PAUSED) R.string.daily_resume else R.string.daily_pause
        compose.waitUntil(10_000) {
            !controller.state.value.commandInFlight &&
                ((controller.state.value.load as? DailyLoadState.Content)?.daily?.active as? DailyActive.Activity)
                    ?.runtime
                    ?.session
                    ?.state == expected &&
                compose
                    .onAllNodes(hasText(compose.activity.getString(action)) and hasClickAction())
                    .fetchSemanticsNodes()
                    .size == 1
        }
        assertTrue(controller.state.value.commandFailure == null)
    }

    private fun invoke(resource: Int) {
        compose
            .onNode(hasText(compose.activity.getString(resource)) and hasClickAction())
            .performScrollTo()
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
    }
}
