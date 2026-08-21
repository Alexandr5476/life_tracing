package com.alexandr5476.lifetracing.runtime

import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.MonotonicClock
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.RuntimeReconciliationResult
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AndroidRuntimeCoordinatorTest {
    @Test
    fun duplicateAndStaleAlarmDeliveriesCannotRepeatACompletionOrFeedback() =
        runBlocking {
            var current: ActiveRuntime? = runningTimer()
            val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(requireNotNull(current)))
            val feedback = RuntimeDeadlineFeedback(deadline, true, true)
            var reconciliations = 0
            val effects = mutableListOf<RuntimeDeadlineFeedback>()
            val scheduler = FakeScheduler()
            val coordinator =
                coordinator(
                    load = { current },
                    reconcile = {
                        reconciliations++
                        current = null
                        RuntimeReconciliationResult(null, listOf(feedback))
                    },
                    scheduler = scheduler,
                    feedback = effects::add,
                    wallSeconds = 180,
                )

            coordinator.onDeadlineAlarm(deadline)
            coordinator.onDeadlineAlarm(deadline)

            assertEquals(1, reconciliations)
            assertEquals(listOf(feedback), effects)
            assertEquals(2, scheduler.cancellations)
        }

    @Test
    fun oldAlarmAfterManualCancellationOrPauseIsAdvisoryOnly() =
        runBlocking {
            val running = runningTimer()
            val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(running))
            var reconciliations = 0
            var current: ActiveRuntime? = null
            val coordinator =
                coordinator({ current }, {
                    reconciliations++
                    RuntimeReconciliationResult(null, emptyList())
                })

            coordinator.onDeadlineAlarm(deadline)
            current = running.copy(session = running.session.copy(state = ActiveSessionState.PAUSED))
            coordinator.onDeadlineAlarm(deadline)

            assertEquals(0, reconciliations)
        }

    @Test
    fun foregroundRecoveryEmitsOnlyLatestCatchUpFeedback() =
        runBlocking {
            var current: ActiveRuntime? = runningTimer()
            val base = requireNotNull(NextRuntimeDeadlineResolver.resolve(requireNotNull(current)))
            val earlier = RuntimeDeadlineFeedback(base.copy(at = instant(60)), true, false)
            val latest = RuntimeDeadlineFeedback(base.copy(at = instant(120)), false, true)
            val effects = mutableListOf<RuntimeDeadlineFeedback>()
            val coordinator =
                coordinator(
                    load = { current },
                    reconcile = {
                        current = null
                        RuntimeReconciliationResult(null, listOf(earlier, latest))
                    },
                    feedback = effects::add,
                )

            coordinator.onForeground()

            assertEquals(listOf(latest), effects)
        }

    @Test
    fun timeChangeReanchorsBeforeWallReconciliation() =
        runBlocking {
            val wall = MutableWallClock(instant(100))
            val monotonic = MutableMonotonicClock(1_000)
            var reconciledAt: Instant? = null
            val coordinator =
                coordinator(
                    load = { null },
                    reconcile = {
                        reconciledAt = it
                        RuntimeReconciliationResult(null, emptyList())
                    },
                    wall = wall,
                    monotonic = monotonic,
                )
            monotonic.value = 6_000
            wall.value = instant(1_000)

            assertEquals(instant(105), coordinator.clockAnchor.estimatedWallNow())
            coordinator.onSystemTimeChanged()

            assertEquals(instant(1_000), coordinator.clockAnchor.estimatedWallNow())
            assertEquals(instant(1_000), reconciledAt)
        }

    @Test
    fun exactCapabilitySelectsOneExactOrInexactPath() {
        val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(runningTimer()))
        val exactBackend = FakeAlarmBackend(true)
        val fallbackBackend = FakeAlarmBackend(false)

        AndroidRuntimeDeadlineScheduler(exactBackend).schedule(deadline)
        AndroidRuntimeDeadlineScheduler(fallbackBackend).schedule(deadline)

        assertEquals(listOf(deadline), exactBackend.exact)
        assertEquals(emptyList<RuntimeDeadline>(), exactBackend.inexact)
        assertEquals(listOf(deadline), fallbackBackend.inexact)
        assertEquals(false, AndroidRuntimeDeadlineScheduler(fallbackBackend).canScheduleExactRuntimeDeadlines())
    }

    @Test
    fun asyncReceiverHelperAlwaysFinishesOnFailure() {
        var finished = false

        assertThrows(IllegalStateException::class.java) {
            runBlocking { finishBroadcast({ finished = true }) { error("boom") } }
        }

        assertEquals(true, finished)
    }

    @Suppress("LongParameterList") // Defaults keep each coordinator scenario focused on its one changing boundary.
    private fun coordinator(
        load: () -> ActiveRuntime?,
        reconcile: (Instant) -> RuntimeReconciliationResult,
        scheduler: FakeScheduler = FakeScheduler(),
        feedback: (RuntimeDeadlineFeedback) -> Unit = {},
        wallSeconds: Long = 200,
        wall: MutableWallClock = MutableWallClock(instant(wallSeconds)),
        monotonic: MutableMonotonicClock = MutableMonotonicClock(0),
    ) = AndroidRuntimeCoordinator(
        load,
        reconcile,
        wall,
        monotonic,
        scheduler,
        RuntimeFeedbackDispatcher(feedback),
        FakeNotifications(),
        {},
    )

    private fun runningTimer(): ActiveActivityRuntime {
        val snapshot =
            ActivityConfigSnapshot(
                ActivitySnapshotId("timer"),
                "Timer",
                null,
                TimeTrackingMode.TIMER,
                Duration.ofSeconds(60),
                null,
                null,
                null,
                false,
                instant(0),
                ActivityTemplateSettings(),
            )
        val execution =
            ActivityExecutionFactory { ActivityExecutionId("execution") }
                .startTimed(snapshot, instant(0), instant(0), ZoneOffset.UTC)
        return ActiveActivityRuntime(
            ActiveSession(
                ActiveSessionKind.ACTIVITY,
                ActiveSessionState.RUNNING,
                execution.id,
                null,
                execution.updatedAt,
            ),
            execution,
            snapshot,
        )
    }

    private class MutableWallClock(
        var value: Instant,
    ) : WallClock {
        override fun now(): Instant = value
    }

    private class MutableMonotonicClock(
        var value: Long,
    ) : MonotonicClock {
        override fun elapsedRealtimeMillis(): Long = value
    }

    private class FakeScheduler : RuntimeDeadlineScheduler {
        val scheduled = mutableListOf<RuntimeDeadline>()
        var cancellations = 0

        override fun schedule(deadline: RuntimeDeadline) {
            scheduled += deadline
        }

        override fun cancel() {
            cancellations++
        }

        override fun canScheduleExactRuntimeDeadlines(): Boolean = true
    }

    private class FakeAlarmBackend(
        private val exactAllowed: Boolean,
    ) : RuntimeAlarmBackend {
        val exact = mutableListOf<RuntimeDeadline>()
        val inexact = mutableListOf<RuntimeDeadline>()

        override fun canScheduleExactAlarms(): Boolean = exactAllowed

        override fun scheduleExact(deadline: RuntimeDeadline) {
            exact += deadline
        }

        override fun scheduleInexact(deadline: RuntimeDeadline) {
            inexact += deadline
        }

        override fun cancel() = Unit
    }

    private class FakeNotifications : RuntimeNotificationPublisher {
        override fun publish(
            runtime: ActiveRuntime?,
            completion: RuntimeDeadlineFeedback?,
        ) = Unit

        override fun canPostRuntimeNotifications(): Boolean = true
    }

    private fun instant(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}
