package com.alexandr5476.lifetracing.runtime

import android.util.Log
import com.alexandr5476.lifetracing.data.persistence.LiveSessionRepository
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.MonotonicClock
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
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
    private val feedbackDispatcher: RuntimeFeedbackDispatcher,
    private val notificationPublisher: RuntimeNotificationPublisher,
    private val log: (String) -> Unit,
) {
    private val mutex = Mutex()
    val clockAnchor = RuntimeClockAnchor(wallClock, monotonicClock)

    constructor(
        repository: LiveSessionRepository,
        wallClock: WallClock,
        monotonicClock: MonotonicClock,
        scheduler: RuntimeDeadlineScheduler,
        feedbackDispatcher: RuntimeFeedbackDispatcher,
        notificationPublisher: RuntimeNotificationPublisher,
    ) : this(
        repository::getActiveRuntime,
        repository::reconcileActiveSession,
        wallClock,
        monotonicClock,
        scheduler,
        feedbackDispatcher,
        notificationPublisher,
        { Log.i(TAG, it) },
    )

    suspend fun recoverAndSchedule() = reconcileAndSchedule(emitFeedback = false)

    suspend fun onForeground() {
        clockAnchor.reset()
        reconcileAndSchedule(emitFeedback = true)
    }

    suspend fun onSystemTimeChanged() {
        clockAnchor.reset()
        mutex.withLock {
            scheduler.cancel()
            reconcileAndScheduleLocked(emitFeedback = false)
        }
    }

    suspend fun onBootCompleted() {
        clockAnchor.reset()
        reconcileAndSchedule(emitFeedback = false)
    }

    suspend fun onRuntimeStateChanged() {
        mutex.withLock { schedule(loadRuntime()) }
    }

    suspend fun onDeadlineAlarm(expected: RuntimeDeadline) {
        mutex.withLock {
            val before = loadRuntime()
            val current = before?.let(NextRuntimeDeadlineResolver::resolve)
            if (current != expected || wallClock.now() < expected.at) {
                log("stale_runtime_alarm_ignored kind=${expected.kind}")
                schedule(before)
                return@withLock
            }
            val result = reconcile(wallClock.now())
            val runtime = loadRuntime()
            val latest = result.appliedEvents.maxByOrNull { it.deadline.at }
            latest?.let(feedbackDispatcher::dispatch)
            notificationPublisher.publish(runtime, latest)
            schedule(runtime)
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
        if (emitFeedback) latest?.let(feedbackDispatcher::dispatch)
        notificationPublisher.publish(runtime, latest.takeIf { emitFeedback })
        schedule(runtime)
    }

    private fun schedule(runtime: ActiveRuntime?) {
        val deadline = runtime?.let(NextRuntimeDeadlineResolver::resolve)
        if (deadline == null) scheduler.cancel() else scheduler.schedule(deadline)
    }

    private companion object {
        const val TAG = "LifeTracingRuntime"
    }
}
