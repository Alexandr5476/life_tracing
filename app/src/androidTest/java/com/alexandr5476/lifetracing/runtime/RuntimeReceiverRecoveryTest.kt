package com.alexandr5476.lifetracing.runtime

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.DailyControllerOwner
import com.alexandr5476.lifetracing.LifeTracingRuntimeGraph
import com.alexandr5476.lifetracing.RuntimeGraphOwner
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.MonotonicClock
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // A single isolated broadcast/physical-file ownership harness.
class RuntimeReceiverRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseNames = mutableListOf<String>()

    @After
    fun tearDown() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun deadlineBroadcastColdAcquisitionCompletesAtLogicalZeroAndDuplicateIsHarmless() =
        runBlocking {
            for (deliverySeconds in listOf(60L, 600L)) {
                val seed = seed()
                val original = (seed.runtime as ActiveActivityRuntime).execution
                ColdProcess(context, seed.name, at(deliverySeconds)).use { process ->
                    assertEquals(0, process.creations)
                    assertNull(process.store)
                    deliver(process, seed.deadline) { attempt ->
                        assertEquals(1, process.creations)
                        assertEquals(seed.runtime, process.store!!.live.getActiveRuntime())
                        assertPending(attempt)
                        process.dispatcher.runNext()
                    }
                    val completed = process.store!!.activity(original.id)
                    assertEquals(ActivityExecutionStatus.COMPLETED, completed.status)
                    assertEquals(original.id, completed.id)
                    assertEquals(original.snapshotId, completed.snapshotId)
                    assertEquals(at(60), completed.completedAt)
                    assertEquals(Duration.ofSeconds(60), completed.activeDuration)
                    assertNull(process.store!!.live.getActiveSession())
                    assertEquals(listOf(seed.deadline), process.feedback.map { it.deadline })
                    assertEquals(process.feedback, process.notifications)
                    assertEquals(1, process.scheduler.cancellations)

                    deliver(process, seed.deadline) { attempt ->
                        assertPending(attempt)
                        process.dispatcher.runNext()
                    }
                    assertEquals(1, process.creations)
                    assertEquals(completed, process.store!!.activity(original.id))
                    assertEquals(1L to 0L, process.store!!.executionCounts())
                    assertEquals(1, process.feedback.size)
                    assertTrue(process.errors.tryReceive().isFailure)
                }
                RuntimeRecoveryStore(context, seed.name).use { reopened ->
                    assertEquals(at(60), reopened.activity(original.id).completedAt)
                    assertNull(reopened.live.getActiveSession())
                }
            }
        }

    @Test
    fun bootReceiverColdCatchUpRearmsRemainingDeadlineAndTerminalRecoveryIsSilent() =
        runBlocking {
            val seed = seed(sequence = true)
            val original = (seed.runtime as ActiveSequenceRuntime).execution
            ColdProcess(context, seed.name, at(20)).use { process ->
                deliver(process) { attempt ->
                    assertEquals(1, process.creations)
                    assertEquals(seed.runtime, process.store!!.live.getActiveRuntime())
                    assertPending(attempt)
                    process.dispatcher.runNext()
                }
                val recovered = process.store!!.sequence(original.id)
                assertEquals(original.id, recovered.id)
                assertEquals(original.snapshotId, recovered.snapshotId)
                assertEquals(original.occurrences.map { it.id }, recovered.occurrences.map { it.id })
                assertEquals(listOf(at(0), at(8), at(18)), recovered.occurrences.map { it.enteredAt })
                assertEquals(listOf(at(5), at(15), null), recovered.occurrences.map { it.completedAt })
                val remaining = process.scheduler.deadlines.single()
                assertEquals(at(29), remaining.at)
                assertEquals(remaining, process.local.deadline)
                assertEquals(WallMonotonicAnchor(at(20), 1_000), process.local.anchor)
                assertTrue(process.feedback.isEmpty())
                assertTrue(process.notifications.isEmpty())

                process.now = at(100)
                deliver(process) { attempt ->
                    assertPending(attempt)
                    process.dispatcher.runNext()
                }
                assertEquals(at(29), process.store!!.sequence(original.id).endedAt)
                assertNull(process.store!!.live.getActiveSession())
                assertEquals(1, process.creations)
                assertEquals(1, process.scheduler.cancellations)
                assertNull(process.local.deadline)
                assertTrue(process.feedback.isEmpty())
                assertTrue(process.notifications.isEmpty())
                assertTrue(process.errors.tryReceive().isFailure)
            }
            RuntimeRecoveryStore(context, seed.name).use { reopened ->
                assertEquals(at(29), reopened.sequence(original.id).endedAt)
                assertEquals(3L to 1L, reopened.executionCounts())
                assertNull(reopened.live.getActiveSession())
            }
        }

    @Test
    fun bothReceiversFinishRealPendingResultWhenColdGraphAcquisitionFails() =
        runBlocking {
            for (deadlineSignal in listOf(true, false)) {
                val seed = seed()
                ColdProcess(context, seed.name, at(100)).use { process ->
                    val failure = IllegalStateException("cold graph unavailable")
                    process.acquisitionFailure = failure
                    deliver(process, seed.deadline.takeIf { deadlineSignal }) { attempt ->
                        assertSame(failure, withTimeout(TIMEOUT_MS) { attempt.launchFailure.await() })
                        assertEquals(1, process.creations)
                        assertNull(process.store)
                    }
                    assertTrue(process.feedback.isEmpty())
                    assertTrue(process.notifications.isEmpty())
                }
                RuntimeRecoveryStore(context, seed.name).use { reopened ->
                    assertEquals(seed.runtime, reopened.live.getActiveRuntime())
                    assertEquals(1L to 0L, reopened.executionCounts())
                }
            }
        }

    @Test
    fun bothReceiversFinishAfterDispatchFailureWithoutUndoingDurableCompletion() =
        runBlocking {
            for (deadlineSignal in listOf(true, false)) {
                val seed = seed()
                val original = (seed.runtime as ActiveActivityRuntime).execution
                ColdProcess(context, seed.name, at(100)).use { process ->
                    process.scheduler.fail = true
                    deliver(process, seed.deadline.takeIf { deadlineSignal }) { attempt ->
                        assertPending(attempt)
                        assertEquals(seed.runtime, process.store!!.live.getActiveRuntime())
                        process.dispatcher.runNext()
                        val failure = withTimeout(TIMEOUT_MS) { process.errors.receive() }
                        assertTrue(failure is IllegalStateException)
                        assertEquals("alarm cancellation failed", failure.message)
                        assertFalse(attempt.launchFailure.isCompleted)
                    }
                    assertEquals(at(60), process.store!!.activity(original.id).completedAt)
                    assertNull(process.store!!.live.getActiveSession())
                    assertTrue(process.feedback.isEmpty())
                    assertTrue(process.notifications.isEmpty())
                }
                RuntimeRecoveryStore(context, seed.name).use { reopened ->
                    assertEquals(at(60), reopened.activity(original.id).completedAt)
                    assertEquals(1L to 0L, reopened.executionCounts())
                    assertNull(reopened.live.getActiveSession())
                }
            }
        }

    private fun assertPending(attempt: BroadcastAttempt) {
        // onReceive has returned and Android must wait for the queued asynchronous dispatch.
        assertFalse(attempt.finished.isCompleted)
        assertFalse(attempt.orderedCompletion.isCompleted)
        assertEquals(0, attempt.finishes.get())
    }

    private suspend fun deliver(
        process: ColdProcess,
        deadline: RuntimeDeadline? = null,
        beforeCompletion: suspend (BroadcastAttempt) -> Unit,
    ) {
        val attempt = BroadcastAttempt()
        val launcher: RuntimeBroadcastLauncher = { receivedContext, operation, finish ->
            try {
                launchRuntimeBroadcast(
                    operation,
                    {
                        finish() // Finish the actual goAsync PendingResult before notifying the test.
                        attempt.finishes.incrementAndGet()
                        attempt.finished.complete(Unit)
                    },
                    { process.graph(receivedContext) },
                )
            } catch (failure: IllegalStateException) {
                // Capture the production throw at the test delivery boundary, avoiding a process crash.
                attempt.launchFailure.complete(failure)
            } finally {
                attempt.launched.complete(Unit)
            }
        }
        val action: String
        val receiver: BroadcastReceiver
        val intent: Intent
        if (deadline != null) {
            action = RuntimeDeadlineIntentCodec.ACTION_RUNTIME_DEADLINE
            receiver = RuntimeDeadlineReceiver(launcher)
            intent = RuntimeDeadlineIntentCodec.intent(context, deadline).setComponent(null)
        } else {
            action = context.packageName + ".TEST_RUNTIME_RECOVERY." + UUID.randomUUID()
            // BOOT_COMPLETED is protected. Translate only the action at delivery, keeping Android's
            // real PendingResult on this receiver; production onReceive/goAsync/launch remain intact.
            receiver =
                object : RuntimeRecoveryReceiver(launcher) {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        super.onReceive(context, Intent(intent).setAction(Intent.ACTION_BOOT_COMPLETED))
                    }
                }
            intent = Intent(action)
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            context.sendOrderedBroadcast(
                intent.setPackage(context.packageName),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        attempt.orderedCompletion.complete(Unit)
                    }
                },
                null,
                Activity.RESULT_OK,
                null,
                null,
            )
            withTimeout(TIMEOUT_MS) { attempt.launched.await() }
            // Drain the main-thread delivery callback before checking the detached async lifetime.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {}
            beforeCompletion(attempt)
            withTimeout(TIMEOUT_MS) {
                attempt.finished.await()
                attempt.orderedCompletion.await()
            }
            assertEquals(1, attempt.finishes.get())
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private suspend fun seed(sequence: Boolean = false): Seed {
        val name = "receiver-recovery-" + UUID.randomUUID()
        databaseNames += name
        val seed =
            RuntimeRecoveryStore(context, name).use { store ->
                val templates =
                    (if (sequence) listOf(5L, 7L, 11L) else listOf(60L)).map { seconds ->
                        store.authoring.createActivityTemplate(
                            ActivityTemplateDraft("Timer", null, TimeTrackingMode.TIMER, Duration.ofSeconds(seconds)),
                            createdAt = at(0),
                        )
                    }
                if (sequence) {
                    val template =
                        store.authoring.createSequenceTemplate(
                            SequenceTemplateDraft(
                                "Sequence",
                                null,
                                settings = SequenceTemplateSettings(beforeEachStepCountdown = Duration.ofSeconds(3)),
                                nodes =
                                    templates.mapIndexed { index, activity ->
                                        SequenceNodeDraft.Step(
                                            ActivityStepDraft(
                                                DraftIdentity.New("step-$index"),
                                                index,
                                                StepActivityDraft.FromTemplate(activity.id),
                                            ),
                                        )
                                    },
                            ),
                            createdAt = at(0),
                        )
                    store.library.startSequenceFromTemplate(template.id, at(0), at(0), ZoneOffset.UTC)
                } else {
                    store.activityCommands.startLive(
                        ActivityEntrySource.Template(templates.single().id),
                        at(0),
                        at(0),
                        ZoneOffset.UTC,
                    )
                }
                val runtime = requireNotNull(store.live.getActiveRuntime())
                Seed(name, runtime, requireNotNull(NextRuntimeDeadlineResolver.resolve(runtime)))
            }
        // Own/hydrate runtime once, then close Room and discard the entire old graph/scope/anchor.
        ColdProcess(context, name, at(0)).use { old ->
            old.graph(context).coordinator.onRuntimeStateChanged()
            assertEquals(seed.runtime, old.store!!.live.getActiveRuntime())
        }
        return seed
    }

    private data class Seed(
        val name: String,
        val runtime: ActiveRuntime,
        val deadline: RuntimeDeadline,
    )

    private class BroadcastAttempt {
        val launched = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val orderedCompletion = CompletableDeferred<Unit>()
        val launchFailure = CompletableDeferred<Throwable>()
        val finishes = AtomicInteger()
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = Channel<Runnable>(Channel.UNLIMITED)

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            check(queue.trySend(block).isSuccess)
        }

        suspend fun runNext() {
            withTimeout(TIMEOUT_MS) { queue.receive() }.run()
        }
    }

    private class ColdProcess(
        private val context: Context,
        private val name: String,
        var now: Instant,
    ) : AutoCloseable {
        val dispatcher = QueuedDispatcher()
        val errors = Channel<Throwable>(Channel.UNLIMITED)
        private val scope =
            CoroutineScope(
                SupervisorJob() + dispatcher +
                    CoroutineExceptionHandler { _, error ->
                        check(errors.trySend(error).isSuccess)
                    },
            )
        val scheduler = RecordingScheduler()
        val local = RecordingLocalDriver()
        val feedback = mutableListOf<RuntimeDeadlineFeedback>()
        val notifications = mutableListOf<RuntimeDeadlineFeedback>()
        var store: RuntimeRecoveryStore? = null
            private set
        var creations = 0
            private set
        var acquisitionFailure: IllegalStateException? = null
        private var owner: RuntimeGraphOwner? =
            RuntimeGraphOwner { applicationContext ->
                assertSame(context.applicationContext, applicationContext)
                creations++
                acquisitionFailure?.let { throw it }
                val reopened = RuntimeRecoveryStore(applicationContext, name)
                store = reopened
                val coordinator =
                    AndroidRuntimeCoordinator(
                        reopened.live,
                        WallClock { now },
                        MonotonicClock { 1_000 },
                        scheduler,
                        local,
                        RuntimeFeedbackDispatcher(feedback::add),
                        object : RuntimeNotificationPublisher {
                            override fun publish(
                                runtime: ActiveRuntime?,
                                completion: RuntimeDeadlineFeedback?,
                            ) {
                                completion?.let(notifications::add)
                            }

                            override fun canPostRuntimeNotifications(): Boolean = true
                        },
                    )
                LifeTracingRuntimeGraph(
                    scope,
                    coordinator,
                    DailyControllerOwner { error("receiver must not acquire UI") },
                    { _ -> error("unused launcher") },
                    { error("unused library") },
                    { _ -> error("unused editor") },
                )
            }

        fun graph(receivedContext: Context): LifeTracingRuntimeGraph =
            LifeTracingRuntimeGraph.from(receivedContext, requireNotNull(owner))

        override fun close() {
            scope.cancel()
            owner = null
            store?.close()
            store = null
        }
    }

    private class RecordingScheduler : RuntimeDeadlineScheduler {
        val deadlines = mutableListOf<RuntimeDeadline>()
        var cancellations = 0
        var fail = false

        override fun schedule(deadline: RuntimeDeadline) {
            deadlines += deadline
        }

        override fun cancel() {
            if (fail) error("alarm cancellation failed")
            cancellations++
        }

        override fun canScheduleExactRuntimeDeadlines(): Boolean = false
    }

    private class RecordingLocalDriver : InProcessRuntimeDeadlineDriver {
        var deadline: RuntimeDeadline? = null
        var anchor: WallMonotonicAnchor? = null

        override fun arm(
            deadline: RuntimeDeadline,
            anchor: WallMonotonicAnchor,
            callback: suspend (RuntimeDeadline) -> Unit,
        ) {
            this.deadline = deadline
            this.anchor = anchor
        }

        override fun cancel() {
            deadline = null
            anchor = null
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        fun at(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
    }
}
