package com.alexandr5476.lifetracing.runtime

import android.util.Log
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.MonotonicClock
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline
import com.alexandr5476.lifetracing.domain.RuntimeReconciliationResult
import com.alexandr5476.lifetracing.domain.WallClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("LongParameterList") // Production dependencies stay explicit; tests replace only boundary functions.
class AndroidRuntimeCoordinator internal constructor(
    private val loadRuntime: () -> ActiveRuntime?,
    private val reconcile: (java.time.Instant) -> RuntimeReconciliationResult,
    private val wallClock: WallClock,
    monotonicClock: MonotonicClock,
    private val scheduler: RuntimeDeadlineScheduler,
    private val localDeadlineDriver: InProcessRuntimeDeadlineDriver,
    private val feedbackDispatcher: RuntimeFeedbackDispatcher,
    private val notificationPublisher: RuntimeNotificationPublisher,
    private val log: (String) -> Unit,
) {
    private val mutex = Mutex()
    val clockAnchor = RuntimeClockAnchor(wallClock, monotonicClock)

    @Volatile
    var displayBaseline: RuntimeDisplayBaseline? = null
        private set

    constructor(
        repository: LiveSessionRepository,
        wallClock: WallClock,
        monotonicClock: MonotonicClock,
        scheduler: RuntimeDeadlineScheduler,
        localDeadlineDriver: InProcessRuntimeDeadlineDriver,
        feedbackDispatcher: RuntimeFeedbackDispatcher,
        notificationPublisher: RuntimeNotificationPublisher,
    ) : this(
        repository::getActiveRuntime,
        repository::reconcileActiveSession,
        wallClock,
        monotonicClock,
        scheduler,
        localDeadlineDriver,
        feedbackDispatcher,
        notificationPublisher,
        { Log.i(TAG, it) },
    )

    suspend fun recoverAndSchedule() = reconcileAndSchedule(emitFeedback = false)

    suspend fun onForeground() {
        mutex.withLock {
            clockAnchor.reset()
            reconcileAndScheduleLocked(emitFeedback = true)
        }
    }

    suspend fun onSystemTimeChanged() {
        mutex.withLock {
            localDeadlineDriver.cancel()
            scheduler.cancel()
            clockAnchor.reset()
            reconcileAndScheduleLocked(emitFeedback = false)
        }
    }

    suspend fun onBootCompleted() {
        mutex.withLock {
            clockAnchor.reset()
            reconcileAndScheduleLocked(emitFeedback = false)
        }
    }

    suspend fun onRuntimeStateChanged() {
        mutex.withLock {
            val runtime = loadRuntime()
            schedule(runtime)
            bestEffort("runtime_notification_failed") { notificationPublisher.publish(runtime, null) }
        }
    }

    suspend fun onDeadlineSignal(expected: RuntimeDeadline) {
        mutex.withLock {
            val before = loadRuntime()
            val current = before?.let(NextRuntimeDeadlineResolver::resolve)
            val now = wallClock.now()
            if (current != expected || now < expected.at) {
                log("stale_runtime_deadline_signal_ignored kind=${expected.kind}")
                schedule(before)
                return@withLock
            }
            val result = reconcile(now)
            val runtime = loadRuntime()
            val latest = result.appliedEvents.maxByOrNull { it.deadline.at }
            schedule(runtime)
            latest?.let { bestEffort("runtime_feedback_failed") { feedbackDispatcher.dispatch(it) } }
            bestEffort("runtime_notification_failed") { notificationPublisher.publish(runtime, latest) }
        }
    }

    private suspend fun reconcileAndSchedule(emitFeedback: Boolean) {
        mutex.withLock { reconcileAndScheduleLocked(emitFeedback) }
    }

    private fun reconcileAndScheduleLocked(emitFeedback: Boolean) {
        log("runtime_recovery_started")
        val result = reconcile(wallClock.now())
        val runtime = loadRuntime()
        val latest = result.appliedEvents.maxByOrNull { it.deadline.at }
        schedule(runtime)
        if (emitFeedback) latest?.let { bestEffort("runtime_feedback_failed") { feedbackDispatcher.dispatch(it) } }
        bestEffort("runtime_notification_failed") {
            notificationPublisher.publish(runtime, latest.takeIf { emitFeedback })
        }
    }

    private fun schedule(runtime: ActiveRuntime?) {
        displayBaseline = runtime?.let { RuntimeDisplayBaseline.capture(it, clockAnchor.snapshot()) }
        val deadline = runtime?.let(NextRuntimeDeadlineResolver::resolve)
        if (deadline == null) {
            localDeadlineDriver.cancel()
            scheduler.cancel()
        } else {
            localDeadlineDriver.arm(deadline, clockAnchor.snapshot(), ::onDeadlineSignal)
            scheduler.schedule(deadline)
        }
    }

    // Platform effects are isolated; durable/domain work stays outside this block.
    @Suppress("TooGenericExceptionCaught")
    private fun bestEffort(
        failureMessage: String,
        effect: () -> Unit,
    ) {
        try {
            effect()
        } catch (error: RuntimeException) {
            log("$failureMessage type=${error::class.java.simpleName}")
        }
    }

    private companion object {
        const val TAG = "LifeTracingRuntime"
    }
}
