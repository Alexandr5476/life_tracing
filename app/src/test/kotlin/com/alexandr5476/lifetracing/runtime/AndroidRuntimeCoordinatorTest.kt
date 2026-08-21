package com.alexandr5476.lifetracing.runtime

import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
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
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceMaterializer
import com.alexandr5476.lifetracing.domain.RuntimeReconciliationResult
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceExecutionFactory
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
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
import com.alexandr5476.lifetracing.domain.WallClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AndroidRuntimeCoordinatorTest {
    @Test
    fun coroutineDriverUsesMonotonicDeadlineAndReplacesPreviousOneShot() =
        runBlocking {
            val monotonic = MutableMonotonicClock(6_000)
            val anchor =
                com.alexandr5476.lifetracing.domain
                    .WallMonotonicAnchor(instant(100), 1_000)
            val due = requireNotNull(NextRuntimeDeadlineResolver.resolve(runningTimer())).copy(at = instant(105))
            val future = due.copy(at = instant(106))
            val fired = mutableListOf<RuntimeDeadline>()
            val driver = CoroutineInProcessRuntimeDeadlineDriver(this, monotonic)

            driver.arm(future, anchor, fired::add)
            driver.arm(due, anchor, fired::add)
            yield()

            assertEquals(listOf(due), fired)
        }

    @Test
    fun localDriverCompletesSuccessiveTenSecondSequenceTimersWithoutAlarmDelivery() =
        runBlocking {
            val harness =
                SequenceHarness(
                    listOf(activity("a", TimeTrackingMode.TIMER, 10), activity("b", TimeTrackingMode.TIMER, 10)),
                )
            val wall = MutableWallClock(instant(0))
            val monotonic = MutableMonotonicClock(0)
            val local = FakeLocalDriver()
            val scheduler = FakeScheduler(exactAllowed = false)
            val coordinator = harness.coordinator(wall, monotonic, local, scheduler)

            coordinator.onRuntimeStateChanged()
            wall.value = instant(10)
            monotonic.value = 10_000
            local.fire()
            wall.value = instant(20)
            monotonic.value = 20_000
            local.fire()

            val occurrences = harness.state.execution.occurrences
            assertEquals(instant(0), occurrences[0].enteredAt)
            assertEquals(instant(10), occurrences[0].completedAt)
            assertEquals(OccurrenceCompletionReason.NATURAL_TIMER_END, occurrences[0].completionReason)
            assertEquals(instant(10), occurrences[1].enteredAt)
            assertEquals(instant(20), occurrences[1].completedAt)
            assertEquals(OccurrenceCompletionReason.NATURAL_TIMER_END, occurrences[1].completionReason)
            assertEquals(SequenceExecutionStatus.COMPLETED, harness.state.execution.status)
            assertEquals(instant(20), harness.state.execution.endedAt)
            assertEquals(false, scheduler.canScheduleExactRuntimeDeadlines())
        }

    @Test
    fun localDriverCompletesShortTransitionCountdownWithoutPollingOrAlarmDelivery() =
        runBlocking {
            val harness =
                SequenceHarness(
                    listOf(activity("a", TimeTrackingMode.TIMER, 5), activity("b", TimeTrackingMode.STOPWATCH)),
                    countdownSeconds = 3,
                )
            val wall = MutableWallClock(instant(0))
            val monotonic = MutableMonotonicClock(0)
            val local = FakeLocalDriver()
            val coordinator = harness.coordinator(wall, monotonic, local, FakeScheduler(exactAllowed = false))

            coordinator.onRuntimeStateChanged()
            wall.value = instant(5)
            monotonic.value = 5_000
            local.fire()
            wall.value = instant(8)
            monotonic.value = 8_000
            local.fire()

            assertEquals(
                instant(5),
                harness.state.execution.occurrences[0]
                    .completedAt,
            )
            assertEquals(
                instant(8),
                harness.state.execution.occurrences[1]
                    .enteredAt,
            )
            assertEquals(2, local.fired.size)
        }

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

            coordinator.onDeadlineSignal(deadline)
            coordinator.onDeadlineSignal(deadline)

            assertEquals(1, reconciliations)
            assertEquals(listOf(feedback), effects)
            assertEquals(2, scheduler.cancellations)
        }

    @Test
    fun localAndAlarmRaceUsesOneStaleCheckedTransitionAndEffect() =
        runBlocking {
            var current: ActiveRuntime? = runningTimer()
            val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(requireNotNull(current)))
            val feedback = RuntimeDeadlineFeedback(deadline, true, true)
            val local = FakeLocalDriver()
            val effects = mutableListOf<RuntimeDeadlineFeedback>()
            var reconciliations = 0
            val coordinator =
                coordinator(
                    load = { current },
                    reconcile = {
                        reconciliations++
                        current = null
                        RuntimeReconciliationResult(null, listOf(feedback))
                    },
                    local = local,
                    feedback = effects::add,
                    wallSeconds = 60,
                )

            coordinator.onRuntimeStateChanged()
            local.fire()
            coordinator.onDeadlineSignal(deadline)

            assertEquals(1, reconciliations)
            assertEquals(listOf(feedback), effects)
        }

    @Test
    fun alarmThenCancelledLocalCallbackIsHarmless() =
        runBlocking {
            var current: ActiveRuntime? = runningTimer()
            val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(requireNotNull(current)))
            val feedback = RuntimeDeadlineFeedback(deadline, true, true)
            val local = FakeLocalDriver()
            val effects = mutableListOf<RuntimeDeadlineFeedback>()
            var reconciliations = 0
            val coordinator =
                coordinator(
                    load = { current },
                    reconcile = {
                        reconciliations++
                        current = null
                        RuntimeReconciliationResult(null, listOf(feedback))
                    },
                    local = local,
                    feedback = effects::add,
                    wallSeconds = 60,
                )

            coordinator.onRuntimeStateChanged()
            val oldCallback = local.armed.single().callback
            coordinator.onDeadlineSignal(deadline)
            oldCallback(deadline)

            assertEquals(1, reconciliations)
            assertEquals(listOf(feedback), effects)
        }

    @Test
    fun schedulingPrecedesAndSurvivesIndependentEffectFailures() =
        runBlocking {
            val first = runningTimer()
            val second = runningTimer("next", 60)
            val firstDeadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(first))
            val secondDeadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(second))
            val feedback = RuntimeDeadlineFeedback(firstDeadline, true, true)
            var current: ActiveRuntime? = first
            val scheduler = FakeScheduler()
            val local = FakeLocalDriver()
            val notifications = FakeNotifications(throwOnPublish = true)
            val coordinator =
                coordinator(
                    load = { current },
                    reconcile = {
                        current = second
                        RuntimeReconciliationResult(second.session, listOf(feedback))
                    },
                    scheduler = scheduler,
                    local = local,
                    feedback = { error("feedback failed") },
                    notifications = notifications,
                    wallSeconds = 60,
                )

            coordinator.onDeadlineSignal(firstDeadline)

            assertEquals(secondDeadline, scheduler.scheduled.last())
            assertEquals(secondDeadline, local.armed.last().deadline)
            assertEquals(1, notifications.attempts)
        }

    @Test
    fun stateChangesSynchronizeBothSchedulersAndNotificationWithoutFeedback() =
        runBlocking {
            var current: ActiveRuntime? = runningTimer()
            val scheduler = FakeScheduler()
            val local = FakeLocalDriver()
            val notifications = FakeNotifications()
            var feedbackCount = 0
            val coordinator =
                coordinator(
                    load = { current },
                    reconcile = { RuntimeReconciliationResult(current?.session, emptyList()) },
                    scheduler = scheduler,
                    local = local,
                    feedback = { feedbackCount++ },
                    notifications = notifications,
                )

            coordinator.onRuntimeStateChanged()
            current =
                (current as ActiveActivityRuntime).copy(
                    session = requireNotNull(current).session.copy(state = ActiveSessionState.PAUSED),
                )
            coordinator.onRuntimeStateChanged()
            val waiting =
                SequenceHarness(
                    listOf(
                        activity("wait-a", TimeTrackingMode.TIMER, 10),
                        activity("wait-b", TimeTrackingMode.STOPWATCH),
                    ),
                    autoAdvance = false,
                )
            waiting.reconcile(instant(10))
            current = waiting.runtime()
            coordinator.onRuntimeStateChanged()
            current = null
            coordinator.onRuntimeStateChanged()

            assertEquals(
                listOf(
                    ActiveSessionState.RUNNING,
                    ActiveSessionState.PAUSED,
                    ActiveSessionState.WAITING_NEXT,
                    null,
                ),
                notifications.states,
            )
            assertEquals(1, scheduler.scheduled.size)
            assertEquals(3, scheduler.cancellations)
            assertEquals(1, local.armed.size)
            assertEquals(3, local.cancellations)
            assertEquals(0, feedbackCount)
        }

    @Test
    fun timeChangeCancelsOldLocalDeadlineAndRebuildsFromNewAnchor() =
        runBlocking {
            val wall = MutableWallClock(instant(100))
            val monotonic = MutableMonotonicClock(1_000)
            var current: ActiveRuntime? = runningTimer("before", 100)
            val local = FakeLocalDriver()
            val scheduler = FakeScheduler()
            val coordinator =
                coordinator(
                    load = { current },
                    reconcile = {
                        current = runningTimer("after", 1_000)
                        RuntimeReconciliationResult(current?.session, emptyList())
                    },
                    scheduler = scheduler,
                    local = local,
                    wall = wall,
                    monotonic = monotonic,
                )
            coordinator.onRuntimeStateChanged()
            val old = local.armed.single()
            wall.value = instant(1_000)
            monotonic.value = 6_000

            assertEquals(61_000, old.anchor.elapsedAt(old.deadline.at))
            coordinator.onSystemTimeChanged()

            val replacement = local.armed.last()
            assertEquals(66_000, replacement.anchor.elapsedAt(replacement.deadline.at))
            assertEquals(1, local.cancellations)
            assertEquals(1, scheduler.cancellations)
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

            coordinator.onDeadlineSignal(deadline)
            current = running.copy(session = running.session.copy(state = ActiveSessionState.PAUSED))
            coordinator.onDeadlineSignal(deadline)

            assertEquals(0, reconciliations)
        }

    @Test
    fun cancelledLocalCallbacksAfterManualCompletionOrPauseAreStale() =
        runBlocking {
            val running = runningTimer()
            val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(running))
            var current: ActiveRuntime? = running
            var reconciliations = 0
            val local = FakeLocalDriver()
            val coordinator =
                coordinator(
                    load = { current },
                    reconcile = {
                        reconciliations++
                        RuntimeReconciliationResult(current?.session, emptyList())
                    },
                    local = local,
                    wallSeconds = 60,
                )
            coordinator.onRuntimeStateChanged()
            val oldCallback = local.armed.single().callback

            current = null
            coordinator.onRuntimeStateChanged()
            oldCallback(deadline)
            coordinator.onDeadlineSignal(deadline)
            current = running.copy(session = running.session.copy(state = ActiveSessionState.PAUSED))
            coordinator.onRuntimeStateChanged()
            oldCallback(deadline)
            coordinator.onDeadlineSignal(deadline)

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
    fun backwardWallRaceReanchorsBeforeRearmingAlreadyDueLocalDeadline() =
        runBlocking {
            var current: ActiveRuntime? = runningTimer(timerTargetSeconds = 10)
            val wall = MutableWallClock(Instant.EPOCH)
            val monotonic = MutableMonotonicClock(0)
            val local = FakeLocalDriver()
            val scheduler = FakeScheduler()
            val effects = mutableListOf<RuntimeDeadlineFeedback>()
            val notifications = FakeNotifications()
            var reconciliations = 0
            val coordinator =
                coordinator(
                    load = { current },
                    reconcile = {
                        reconciliations++
                        RuntimeReconciliationResult(current?.session, emptyList())
                    },
                    scheduler = scheduler,
                    local = local,
                    feedback = effects::add,
                    notifications = notifications,
                    wall = wall,
                    monotonic = monotonic,
                )
            coordinator.onRuntimeStateChanged()
            wall.value = instant(-100)
            monotonic.value = 10_000

            local.fire()

            assertEquals(0, reconciliations)
            assertEquals(emptyList<RuntimeDeadlineFeedback>(), effects)
            assertEquals(emptyList<RuntimeDeadlineFeedback>(), notifications.completions)
            assertEquals(2, local.armed.size)
            assertEquals(2, scheduler.scheduled.size)
            assertEquals(local.armed.first().deadline, local.armed.last().deadline)
            assertEquals(
                true,
                local.armed
                    .last()
                    .anchor
                    .elapsedAt(
                        local.armed
                            .last()
                            .deadline.at,
                    ) > monotonic.value,
            )

            coordinator.onSystemTimeChanged()

            assertEquals(1, reconciliations)
            assertEquals(1, local.cancellations)
            assertEquals(1, scheduler.cancellations)
            assertEquals(3, local.armed.size)
            assertEquals(3, scheduler.scheduled.size)
        }

    @Test
    fun staleOldSignalReanchorsAuthoritativeNewDeadlineWhenOldMappingSaysItIsDue() =
        runBlocking {
            val first = runningTimer("first", timerTargetSeconds = 10)
            val firstDeadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(first))
            val second = runningTimer("second", startedAtSeconds = 10, timerTargetSeconds = 10)
            val secondDeadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(second))
            var current: ActiveRuntime? = first
            val wall = MutableWallClock(Instant.EPOCH)
            val monotonic = MutableMonotonicClock(0)
            val local = FakeLocalDriver()
            val scheduler = FakeScheduler()
            var reconciliations = 0
            val coordinator =
                coordinator(
                    load = { current },
                    reconcile = {
                        reconciliations++
                        RuntimeReconciliationResult(current?.session, emptyList())
                    },
                    scheduler = scheduler,
                    local = local,
                    wall = wall,
                    monotonic = monotonic,
                )
            coordinator.onRuntimeStateChanged()
            current = second
            wall.value = instant(-100)
            monotonic.value = 20_000

            coordinator.onDeadlineSignal(firstDeadline)

            assertEquals(0, reconciliations)
            assertEquals(2, local.armed.size)
            assertEquals(secondDeadline, local.armed.last().deadline)
            assertEquals(
                true,
                local.armed
                    .last()
                    .anchor
                    .elapsedAt(secondDeadline.at) > monotonic.value,
            )
            assertEquals(listOf(firstDeadline, secondDeadline), scheduler.scheduled)
        }

    @Test
    fun normallyEarlySignalRearmsWithoutUnnecessaryReanchorOrTransition() =
        runBlocking {
            val current = runningTimer(timerTargetSeconds = 10)
            val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(current))
            val wall = MutableWallClock(Instant.EPOCH)
            val monotonic = MutableMonotonicClock(0)
            val local = FakeLocalDriver()
            var reconciliations = 0
            val coordinator =
                coordinator(
                    load = { current },
                    reconcile = {
                        reconciliations++
                        RuntimeReconciliationResult(current.session, emptyList())
                    },
                    local = local,
                    wall = wall,
                    monotonic = monotonic,
                )
            coordinator.onRuntimeStateChanged()
            wall.value = instant(5)
            monotonic.value = 5_000

            coordinator.onDeadlineSignal(deadline)

            assertEquals(0, reconciliations)
            assertEquals(2, local.armed.size)
            assertEquals(
                com.alexandr5476.lifetracing.domain
                    .WallMonotonicAnchor(Instant.EPOCH, 0),
                local.armed.last().anchor,
            )
            assertEquals(
                10_000,
                local.armed
                    .last()
                    .anchor
                    .elapsedAt(deadline.at),
            )
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
    fun exactSecurityExceptionFallsBackToLegalInexactOneShot() {
        val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(runningTimer()))
        val backend = FakeAlarmBackend(true, throwOnExact = true)

        AndroidRuntimeDeadlineScheduler(backend).schedule(deadline)

        assertEquals(listOf(deadline), backend.inexact)
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
        local: FakeLocalDriver = FakeLocalDriver(),
        feedback: (RuntimeDeadlineFeedback) -> Unit = {},
        notifications: FakeNotifications = FakeNotifications(),
        wallSeconds: Long = 200,
        wall: MutableWallClock = MutableWallClock(instant(wallSeconds)),
        monotonic: MutableMonotonicClock = MutableMonotonicClock(0),
    ) = AndroidRuntimeCoordinator(
        load,
        reconcile,
        wall,
        monotonic,
        scheduler,
        local,
        RuntimeFeedbackDispatcher(feedback),
        notifications,
        {},
    )

    private fun runningTimer(
        id: String = "execution",
        startedAtSeconds: Long = 0,
        timerTargetSeconds: Long = 60,
    ): ActiveActivityRuntime {
        val snapshot =
            ActivityConfigSnapshot(
                ActivitySnapshotId("timer"),
                "Timer",
                null,
                TimeTrackingMode.TIMER,
                Duration.ofSeconds(timerTargetSeconds),
                null,
                null,
                null,
                false,
                Instant.EPOCH,
                ActivityTemplateSettings(),
            )
        val execution =
            ActivityExecutionFactory { ActivityExecutionId(id) }
                .startTimed(snapshot, instant(startedAtSeconds), instant(startedAtSeconds), ZoneOffset.UTC)
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

    private class FakeScheduler(
        private val exactAllowed: Boolean = true,
    ) : RuntimeDeadlineScheduler {
        val scheduled = mutableListOf<RuntimeDeadline>()
        var cancellations = 0

        override fun schedule(deadline: RuntimeDeadline) {
            scheduled += deadline
        }

        override fun cancel() {
            cancellations++
        }

        override fun canScheduleExactRuntimeDeadlines(): Boolean = exactAllowed
    }

    private class FakeLocalDriver : InProcessRuntimeDeadlineDriver {
        data class Armed(
            val deadline: RuntimeDeadline,
            val anchor: com.alexandr5476.lifetracing.domain.WallMonotonicAnchor,
            val callback: suspend (RuntimeDeadline) -> Unit,
        )

        val armed = mutableListOf<Armed>()
        val fired = mutableListOf<RuntimeDeadline>()
        var cancellations = 0

        override fun arm(
            deadline: RuntimeDeadline,
            anchor: com.alexandr5476.lifetracing.domain.WallMonotonicAnchor,
            callback: suspend (RuntimeDeadline) -> Unit,
        ) {
            armed += Armed(deadline, anchor, callback)
        }

        override fun cancel() {
            cancellations++
        }

        suspend fun fire() {
            val armedDeadline = armed.last()
            fired += armedDeadline.deadline
            armedDeadline.callback(armedDeadline.deadline)
        }
    }

    private class FakeAlarmBackend(
        private val exactAllowed: Boolean,
        private val throwOnExact: Boolean = false,
    ) : RuntimeAlarmBackend {
        val exact = mutableListOf<RuntimeDeadline>()
        val inexact = mutableListOf<RuntimeDeadline>()

        override fun canScheduleExactAlarms(): Boolean = exactAllowed

        override fun scheduleExact(deadline: RuntimeDeadline) {
            if (throwOnExact) throw SecurityException("revoked")
            exact += deadline
        }

        override fun scheduleInexact(deadline: RuntimeDeadline) {
            inexact += deadline
        }

        override fun cancel() = Unit
    }

    private class FakeNotifications(
        private val throwOnPublish: Boolean = false,
    ) : RuntimeNotificationPublisher {
        val states = mutableListOf<ActiveSessionState?>()
        val completions = mutableListOf<RuntimeDeadlineFeedback>()
        var attempts = 0

        override fun publish(
            runtime: ActiveRuntime?,
            completion: RuntimeDeadlineFeedback?,
        ) {
            attempts++
            states += runtime?.session?.state
            completion?.let(completions::add)
            if (throwOnPublish) error("notification failed")
        }

        override fun canPostRuntimeNotifications(): Boolean = true
    }

    private class SequenceHarness(
        activities: List<ActivityConfigSnapshot>,
        countdownSeconds: Long = 0,
        autoAdvance: Boolean = true,
    ) {
        private val snapshots = activities.associateBy { it.id }
        private val snapshot =
            SequenceConfigSnapshot(
                SequenceSnapshotId("sequence"),
                "Sequence",
                null,
                null,
                null,
                null,
                Instant.EPOCH,
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
                        SequenceSnapshotActivityStep(SequenceSnapshotNodeId("step-$index"), index, activity.id)
                    },
            )
        private var occurrence = 0
        private var child = 0
        private var interval = 0
        private val engine =
            SequenceRuntimeEngine(
                SequenceExecutionFactory(
                    { SequenceExecutionId("sequence-execution") },
                    RuntimeOccurrenceMaterializer { SequenceOccurrenceId("occurrence-${++occurrence}") },
                ),
                ActivityExecutionFactory { ActivityExecutionId("child-${++child}") },
                {
                    com.alexandr5476.lifetracing.domain
                        .ActivityExecutionPauseId("pause")
                },
                { SequenceIntervalId("interval-${++interval}") },
            )
        var state: SequenceRuntimeState =
            engine.start(
                snapshot,
                snapshots,
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )

        fun coordinator(
            wall: MutableWallClock,
            monotonic: MutableMonotonicClock,
            local: FakeLocalDriver,
            scheduler: FakeScheduler,
        ): AndroidRuntimeCoordinator =
            AndroidRuntimeCoordinator(
                { runtime() },
                { now ->
                    reconcile(now)
                    RuntimeReconciliationResult(runtime()?.session, emptyList())
                },
                wall,
                monotonic,
                scheduler,
                local,
                RuntimeFeedbackDispatcher {},
                FakeNotifications(),
                {},
            )

        fun reconcile(now: Instant) {
            state = engine.reconcile(state, snapshot, snapshots, now)
        }

        fun runtime(): ActiveSequenceRuntime? {
            if (state.execution.status == SequenceExecutionStatus.COMPLETED) return null
            val sessionState =
                if (state.execution.currentOccurrenceId == null &&
                    state.execution.intervals
                        .singleOrNull { it.endedAt == null }
                        ?.kind ==
                    SequenceIntervalKind.IMPLICIT_IDLE
                ) {
                    ActiveSessionState.WAITING_NEXT
                } else {
                    ActiveSessionState.RUNNING
                }
            return ActiveSequenceRuntime(
                ActiveSession(
                    ActiveSessionKind.SEQUENCE,
                    sessionState,
                    null,
                    state.execution.id,
                    state.execution.updatedAt,
                ),
                state.execution,
                snapshot,
                snapshots,
                state.currentChild,
            )
        }
    }

    private fun activity(
        id: String,
        mode: TimeTrackingMode,
        timerSeconds: Long? = null,
    ) = ActivityConfigSnapshot(
        ActivitySnapshotId(id),
        id,
        null,
        mode,
        timerSeconds?.let(Duration::ofSeconds),
        null,
        null,
        null,
        false,
        instant(0),
        ActivityTemplateSettings(),
    )

    private fun instant(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}
