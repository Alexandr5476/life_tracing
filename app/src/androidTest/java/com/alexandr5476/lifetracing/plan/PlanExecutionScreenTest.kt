package com.alexandr5476.lifetracing.plan

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.PlanActionIdentity
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.PlanTrackableKind
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class PlanExecutionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun activityOffersSecondaryTimeEntry() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val activity = activity()
            val action = focusedActivity(activity)
            val controller = controller(scope, action)
            val session = PlanExecutionRouteSession(action.identity, PlanExecutionOrigin.DAILY, controller)
            compose.setContent {
                LifeTracingTheme {
                    PlanExecutionScreen(
                        PlanExecutionState(
                            PlanExecutionLoad.Content(PreparedPlanExecution(action, Duration.ZERO, true)),
                        ),
                        session,
                        {},
                        {},
                    )
                }
            }
            compose.onNodeWithTag("plan-execution-time-entry").assertExists().performClick()
            compose.onNodeWithTag("plan-time-start").assertExists()
            compose.onNodeWithTag("plan-time-end").assertExists()
            compose.onNodeWithTag("plan-time-save").assertExists()
            controller.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sequenceDoesNotOfferSecondaryTimeEntry() {
        val sequenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val action = focusedSequence(activity())
            val controller = controller(sequenceScope, action)
            val session = PlanExecutionRouteSession(action.identity, PlanExecutionOrigin.PLAN, controller)
            compose.setContent {
                LifeTracingTheme {
                    PlanExecutionScreen(
                        PlanExecutionState(
                            PlanExecutionLoad.Content(PreparedPlanExecution(action, Duration.ZERO, true)),
                        ),
                        session,
                        {},
                        {},
                    )
                }
            }
            compose.onNodeWithTag("plan-execution-time-entry").assertDoesNotExist()
            controller.close()
        } finally {
            sequenceScope.cancel()
        }
    }

    private fun controller(
        scope: CoroutineScope,
        action: FocusedPlanAction,
    ) = PlanExecutionController(
        scope,
        action.identity,
        { action },
        { false },
        { error("No command is submitted in this presentation test") },
        {},
        WallClock { now },
        { ZoneOffset.UTC },
        object : PreflightScheduler {
            override fun schedule(
                duration: Duration,
                onBoundary: () -> Unit,
            ) = PreflightHandle {}
        },
    )

    private fun activity() =
        ActivityConfigSnapshot(
            ActivitySnapshotId("activity-snapshot"),
            "Activity",
            null,
            TimeTrackingMode.STOPWATCH,
            null,
            null,
            null,
            null,
            false,
            now,
        )

    private fun focusedActivity(snapshot: ActivityConfigSnapshot) =
        FocusedPlanAction(
            identity(PlanTrackableKind.ACTIVITY, snapshot.id, null),
            PlanSourceState.UNAVAILABLE,
            false,
            FocusedPlanAction.Snapshot.Activity(snapshot),
        )

    private fun focusedSequence(activity: ActivityConfigSnapshot): FocusedPlanAction {
        val snapshot =
            SequenceConfigSnapshot(
                SequenceSnapshotId("sequence-snapshot"),
                "Sequence",
                null,
                null,
                null,
                null,
                now,
                SequenceSnapshotSettings(
                    autoAdvance = false,
                    sequenceStartCountdown = Duration.ZERO,
                    beforeEachStepCountdown = Duration.ZERO,
                    transitionSound = false,
                    transitionVibration = false,
                    keepScreenAwake = false,
                    confirmJump = false,
                    confirmEarlyEnd = false,
                    noLiveTimeAccounting = NoLiveTimeAccounting.ACTIVE,
                ),
                nodes =
                    listOf(
                        SequenceSnapshotActivityStep(
                            SequenceSnapshotNodeId("step"),
                            0,
                            activity.id,
                            SequenceStepOverrides(),
                        ),
                    ),
            )
        return FocusedPlanAction(
            identity(PlanTrackableKind.SEQUENCE, null, snapshot.id),
            PlanSourceState.UNAVAILABLE,
            false,
            FocusedPlanAction.Snapshot.Sequence(snapshot, mapOf(activity.id to activity)),
        )
    }

    private fun identity(
        kind: PlanTrackableKind,
        activity: ActivitySnapshotId?,
        sequence: SequenceSnapshotId?,
    ) = PlanActionIdentity(
        PlanEntryId("plan"),
        kind,
        activity,
        sequence,
        PlanTarget.FloatingDay(LocalDate.parse("2026-09-15")),
        PlanEntryStatus.PLANNED,
        null,
        now,
    )

    private companion object {
        val now: Instant = Instant.parse("2026-09-15T10:00:00Z")
    }
}
