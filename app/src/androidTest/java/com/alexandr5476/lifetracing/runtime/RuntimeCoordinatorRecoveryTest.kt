package com.alexandr5476.lifetracing.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.MonotonicClock
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Suppress("LargeClass", "TooManyFunctions") // One file-backed ownership harness covers recovery entry points.
class RuntimeCoordinatorRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "coordinator-recovery-" + System.nanoTime()
    private val lateName = "$name-late"
    private var storeReference: RuntimeRecoveryStore? = null
    private var processReference: Process? = null
    private val store get() = requireNotNull(storeReference)
    private val process get() = requireNotNull(processReference)

    @Before
    fun setUp() {
        open()
    }

    @After
    fun tearDown() {
        discard()
        context.deleteDatabase(name)
        context.deleteDatabase(lateName)
    }

    @Test
    fun freshForegroundRearmsPauseAdjustedIdentityAndBuildsANewDisplayBaseline() =
        runBlocking {
            startActivity()
            store.live.pauseActiveActivity(ActivityExecutionPauseId("pause"), at(10))
            store.live.resumeActiveActivity(at(20))
            val before = requireNotNull(store.live.getActiveRuntime())
            val expected = requireNotNull(NextRuntimeDeadlineResolver.resolve(before))
            createProcess(20, 20_000).coordinator.onRuntimeStateChanged()
            assertEquals(70_000L, process.local.anchor!!.elapsedAt(expected.at))

            // Physically close Room and drop every old repository/coordinator/driver/anchor owner.
            discard()
            open()
            createProcess(40, 900_000)
            assertNull(process.coordinator.displayBaseline)
            process.coordinator.onForeground()

            assertEquals(before, store.live.getActiveRuntime())
            assertEquals(expected, process.scheduler.deadlines.single())
            assertEquals(expected, process.local.deadline)
            assertEquals(WallMonotonicAnchor(at(40), 900_000), process.local.anchor)
            assertEquals(930_000L, process.local.anchor!!.elapsedAt(expected.at))
            val display = requireNotNull(process.coordinator.displayBaseline)
            assertTrue(display.matches(requireNotNull(store.live.getActiveRuntime())))
            assertEquals(Duration.ofSeconds(30), display.activeElapsed(900_000))
            assertEquals(Duration.ofSeconds(30), display.timerRemaining(900_000))
            assertEquals(Duration.ofSeconds(29), display.timerRemaining(901_000))
            assertTrue(process.effects.isEmpty())
        }

    @Test
    fun overdueActivityForegroundAndLateInexactSignalKeepTheSameLogicalCompletion() =
        runBlocking {
            val execution = startActivity()
            val deadline = nextDeadline()
            createProcess(0, 1_000).coordinator.onRuntimeStateChanged()
            discard()
            // All connections are closed before cloning the same durable starting facts.
            context.getDatabasePath(name).copyTo(context.getDatabasePath(lateName))
            var foregroundFact: com.alexandr5476.lifetracing.domain.ActivityExecution? = null
            for (alarm in listOf(false, true)) {
                val branch = if (alarm) lateName else name
                open(branch)
                assertEquals(execution, store.activity(execution.id))
                createProcess(500, 2_000)
                assertFalse(process.scheduler.canScheduleExactRuntimeDeadlines())

                if (alarm) process.coordinator.onDeadlineSignal(deadline) else process.coordinator.onForeground()

                val completed = store.activity(execution.id)
                if (alarm) assertEquals(foregroundFact, completed) else foregroundFact = completed
                assertEquals(execution.id, completed.id)
                assertEquals(execution.snapshotId, completed.snapshotId)
                assertEquals(ActivityExecutionStatus.COMPLETED, completed.status)
                assertEquals(at(60), completed.completedAt)
                assertEquals(Duration.ofSeconds(60), completed.activeDuration)
                assertNull(store.live.getActiveRuntime())
                assertEquals(listOf(deadline), process.effects.map { it.deadline })
                assertEquals(process.effects, process.notifications.completions)
                assertNull(process.coordinator.displayBaseline)
                assertEquals(1, process.scheduler.cancellations)
                assertEquals(1, process.local.cancellations)

                process.coordinator.onDeadlineSignal(deadline)
                process.coordinator.onForeground()
                assertEquals(completed, store.activity(execution.id))
                assertEquals(1, process.effects.size)
                assertEquals(1, process.notifications.completions.size)
                discard()
                open(branch)
                assertEquals(completed, store.activity(execution.id))
                discard()
            }
        }

    @Test
    fun multiBoundarySequenceForegroundAndLateSignalEmitOnlyTheLatestCompletion() =
        runBlocking {
            val id = startSequence()
            val before = store.sequence(id)
            val originalChild = store.children(id).single()
            val first = nextDeadline()
            createProcess(0, 0).coordinator.onRuntimeStateChanged()
            discard()
            context.getDatabasePath(name).copyTo(context.getDatabasePath(lateName))
            var foregroundEvents: List<RuntimeDeadlineFeedback>? = null
            for (alarm in listOf(false, true)) {
                val branch = if (alarm) lateName else name
                open(branch)
                assertEquals(before, store.sequence(id))
                createProcess(100, 700)
                if (alarm) process.coordinator.onDeadlineSignal(first) else process.coordinator.onForeground()

                val terminal = store.sequence(id)
                assertEquals(before.id, terminal.id)
                assertEquals(originalChild.id, store.children(id).minBy { requireNotNull(it.startedAt) }.id)
                assertEquals(before.snapshotId, terminal.snapshotId)
                assertEquals(before.occurrences.map { it.id }, terminal.occurrences.map { it.id })
                assertEquals(listOf(at(0), at(8), at(18)), terminal.occurrences.map { it.enteredAt })
                assertEquals(listOf(at(5), at(15), at(29)), terminal.occurrences.map { it.completedAt })
                assertEquals(at(29), terminal.endedAt)
                assertEquals(SequenceExecutionStatus.COMPLETED, terminal.status)
                assertEquals(
                    listOf(at(5), at(15), at(29)),
                    store
                        .children(id)
                        .sortedBy {
                            it.startedAt
                        }.map { it.completedAt },
                )
                val latest = process.effects.single().deadline
                assertEquals(at(29), latest.at)
                assertEquals(RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO, latest.kind)
                assertEquals(terminal.occurrences.last().id, latest.expectedOccurrenceId)
                assertEquals(process.effects, process.notifications.completions)
                if (alarm) {
                    assertEquals(foregroundEvents, process.effects)
                } else {
                    foregroundEvents =
                        process.effects.toList()
                }
                assertNull(store.live.getActiveRuntime())
                assertEquals(1, process.local.cancellations)
                assertEquals(1, process.scheduler.cancellations)

                val children = store.children(id)
                process.coordinator.onDeadlineSignal(first)
                process.coordinator.onDeadlineSignal(latest)
                assertEquals(terminal, store.sequence(id))
                assertEquals(children, store.children(id))
                assertEquals(1, process.effects.size)
                assertEquals(1, process.notifications.completions.size)
                discard()
                open(branch)
                assertEquals(terminal, store.sequence(id))
                discard()
            }
        }

    @Test
    fun bootCatchUpIsSilentAndRearmsOnlyTheRemainingSemanticDeadline() =
        runBlocking {
            val id = startSequence()
            createProcess(0, 90_000).coordinator.onRuntimeStateChanged()
            discard()
            open()
            createProcess(20, 1_000)
            process.coordinator.onBootCompleted()

            assertEquals(listOf(at(0), at(8), at(18)), store.sequence(id).occurrences.map { it.enteredAt })
            assertEquals(
                at(29),
                process.scheduler.deadlines
                    .single()
                    .at,
            )
            assertEquals(WallMonotonicAnchor(at(20), 1_000), process.local.anchor)
            assertEquals(10_000L, process.local.anchor!!.elapsedAt(at(29)))
            assertTrue(process.effects.isEmpty())
            assertTrue(process.notifications.completions.isEmpty())

            process.wall.value = at(100)
            process.coordinator.onBootCompleted()
            assertEquals(at(29), store.sequence(id).endedAt)
            assertEquals(1, process.scheduler.cancellations)
            assertEquals(1, process.local.cancellations)
            assertTrue(process.effects.isEmpty())
            assertTrue(process.notifications.completions.isEmpty())
        }

    @Test
    fun timeAndTimezoneRecoveryResetTheAnchorBeforeRepositoryReconciliationAndStaySilent() =
        runBlocking {
            for (action in listOf(
                android.content.Intent.ACTION_TIME_CHANGED,
                android.content.Intent.ACTION_TIMEZONE_CHANGED,
            )) {
                val id = startSequence()
                createProcess(0, 50_000, observeAnchor = true).coordinator.onRuntimeStateChanged()
                process.wall.value = at(20)
                process.monotonic.value = 51_000
                assertEquals(at(1), process.coordinator.clockAnchor.estimatedWallNow())

                requireNotNull(RuntimeBroadcastOperation.recovery(action)).dispatch(process.coordinator)

                assertEquals(WallMonotonicAnchor(at(20), 51_000), process.anchorAtReconciliation)
                assertEquals(process.anchorAtReconciliation, process.local.anchor)
                assertEquals(
                    at(29),
                    process.scheduler.deadlines
                        .last()
                        .at,
                )
                assertEquals(60_000L, process.local.anchor!!.elapsedAt(at(29)))
                assertEquals(1, process.scheduler.cancellations)
                assertEquals(1, process.local.cancellations)
                assertEquals(
                    at(18),
                    store
                        .sequence(id)
                        .occurrences
                        .last()
                        .enteredAt,
                )
                assertTrue(process.effects.isEmpty())
                assertTrue(process.notifications.completions.isEmpty())

                process.wall.value = at(100)
                process.monotonic.value = 52_000
                requireNotNull(RuntimeBroadcastOperation.recovery(action)).dispatch(process.coordinator)
                assertEquals(WallMonotonicAnchor(at(100), 52_000), process.anchorAtReconciliation)
                assertEquals(at(29), store.sequence(id).endedAt)
                assertEquals(3, process.scheduler.cancellations)
                assertEquals(3, process.local.cancellations)
                assertTrue(process.effects.isEmpty())
                assertTrue(process.notifications.completions.isEmpty())
            }
        }

    @Test
    fun committedCatchUpSurvivesLocalAndAlarmArmOrCancellationFailureAfterReopen() =
        runBlocking {
            for (localFailure in listOf(false, true)) {
                for (terminal in listOf(false, true)) {
                    val id = startSequence()
                    discard()
                    open()
                    createProcess(if (terminal) 100 else 20, 1_000)
                    if (localFailure) process.local.fail = true else process.scheduler.fail = true

                    assertThrows(IllegalStateException::class.java) {
                        runBlocking { process.coordinator.onForeground() }
                    }
                    val committed = store.sequence(id)
                    val children = store.children(id)
                    assertEquals(at(18), committed.occurrences.last().enteredAt)
                    if (terminal) assertEquals(at(29), committed.endedAt) else assertNull(committed.endedAt)
                    discard()
                    open()
                    assertEquals(committed, store.sequence(id))
                    assertEquals(children, store.children(id))
                    createProcess(100, 5_000)
                    process.coordinator.recoverAndSchedule()
                    assertEquals(at(29), store.sequence(id).endedAt)
                    assertTrue(process.effects.isEmpty())
                }
            }
        }

    @Test
    fun runtimeAddStartNowMakesCapturedLocalAndAlarmIdentitiesHarmless() =
        runBlocking {
            val id = startSequence()
            createProcess(0, 0).coordinator.onRuntimeStateChanged()
            val firstTimer = requireNotNull(process.local.deadline)
            process.wall.value = at(5)
            process.monotonic.value = 5_000
            process.coordinator.onDeadlineSignal(firstTimer)
            val old = requireNotNull(process.local.deadline)
            assertEquals(RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN, old.kind)
            assertEquals(at(8), old.at)
            val callback = requireNotNull(process.local.callback)
            val priorEffects = process.effects.toList()
            val priorCompletions = process.notifications.completions.toList()
            assertEquals(1, priorEffects.size)
            val inserted =
                store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("inserted", null, TimeTrackingMode.TIMER, Duration.ofSeconds(50)),
                    createdAt = at(6),
                )
            store.live.runtimeAdd(
                id,
                ActivityEntrySource.Template(inserted.id),
                RuntimeInsertionPlacement.START_NOW,
                at(6),
            )
            process.wall.value = at(6)
            process.monotonic.value = 6_000
            process.coordinator.onRuntimeStateChanged()
            val before = requireNotNull(store.live.getActiveRuntime()) as ActiveSequenceRuntime
            val children = store.children(id)
            val counts = store.executionCounts()
            val replacement = requireNotNull(process.local.deadline)
            assertEquals(at(56), replacement.at)
            assertTrue(replacement.expectedOccurrenceId != old.expectedOccurrenceId)

            process.wall.value = at(10)
            process.monotonic.value = 10_000
            callback(old)
            process.coordinator.onDeadlineSignal(old)
            process.coordinator.onDeadlineSignal(old)

            assertEquals(before, store.live.getActiveRuntime())
            assertEquals(children, store.children(id))
            assertEquals(counts, store.executionCounts())
            assertEquals(replacement, process.local.deadline)
            assertEquals(priorEffects, process.effects)
            assertEquals(priorCompletions, process.notifications.completions)
            discard()
            open()
            assertEquals(before, store.live.getActiveRuntime())
        }

    @Test
    fun supportedReceiverActionsReconcileSilentlyAndDeadlineRoutingFinishesAfterFailure() =
        runBlocking {
            val actions =
                listOf(
                    android.content.Intent.ACTION_BOOT_COMPLETED,
                    android.content.Intent.ACTION_TIME_CHANGED,
                    android.content.Intent.ACTION_TIMEZONE_CHANGED,
                    android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                )
            val expectedOperations =
                listOf(
                    RuntimeBroadcastOperation.Boot,
                    RuntimeBroadcastOperation.TimeChanged,
                    RuntimeBroadcastOperation.TimeChanged,
                    RuntimeBroadcastOperation.Reschedule,
                )
            for ((index, action) in actions.withIndex()) {
                assertEquals(expectedOperations[index], RuntimeBroadcastOperation.recovery(action))
                val execution = startActivity()
                discard()
                open()
                createProcess(100, 5_000)
                var finished = 0
                finishBroadcast({ finished++ }) {
                    requireNotNull(RuntimeBroadcastOperation.recovery(action)).dispatch(process.coordinator)
                }
                assertEquals(1, finished)
                assertEquals(at(60), store.activity(execution.id).completedAt)
                assertNull(store.live.getActiveSession())
                assertEquals(1L, process.coordinator.semanticGeneration.value)
                assertEquals(1, process.scheduler.cancellations)
                assertTrue(process.effects.isEmpty())
                assertTrue(process.notifications.completions.isEmpty())
            }

            val execution = startActivity()
            val expected = nextDeadline()
            val operation =
                requireNotNull(
                    RuntimeBroadcastOperation.deadline(RuntimeDeadlineIntentCodec.intent(context, expected)),
                )
            assertEquals(RuntimeBroadcastOperation.Deadline(expected), operation)
            discard()
            open()
            createProcess(100, 500)
            var finished = 0
            finishBroadcast({ finished++ }) { operation.dispatch(process.coordinator) }
            assertEquals(1, finished)
            assertEquals(at(60), store.activity(execution.id).completedAt)
            assertEquals(listOf(expected), process.effects.map { it.deadline })

            val failingExecution = startActivity()
            val failingOperation =
                requireNotNull(
                    RuntimeBroadcastOperation.deadline(
                        RuntimeDeadlineIntentCodec.intent(
                            context,
                            nextDeadline(),
                        ),
                    ),
                )
            createProcess(100, 500)
            process.scheduler.fail = true
            assertThrows(IllegalStateException::class.java) {
                runBlocking { finishBroadcast({ finished++ }) { failingOperation.dispatch(process.coordinator) } }
            }
            assertEquals(2, finished)
            discard()
            open()
            assertEquals(at(60), store.activity(failingExecution.id).completedAt)
        }

    @Test
    fun malformedDeadlineAndUnsupportedRecoveryInputsFailClosed() {
        assertNull(RuntimeBroadcastOperation.recovery(null))
        assertNull(RuntimeBroadcastOperation.recovery("unsupported"))
        assertNull(RuntimeBroadcastOperation.recovery(android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED))
        val execution = startActivity()
        val expected = nextDeadline()
        val malformed =
            listOf(
                android.content.Intent(),
                RuntimeDeadlineIntentCodec.intent(context, expected).setAction("unsupported"),
                RuntimeDeadlineIntentCodec.intent(context, expected).apply { removeExtra("execution_id") },
                RuntimeDeadlineIntentCodec.intent(context, expected).putExtra("execution_id", " "),
                RuntimeDeadlineIntentCodec.intent(context, expected).apply { removeExtra("deadline_ms") },
                RuntimeDeadlineIntentCodec.intent(context, expected).putExtra("deadline_kind", "unsupported"),
                RuntimeDeadlineIntentCodec.intent(context, expected).putExtra("session_kind", "SEQUENCE"),
                RuntimeDeadlineIntentCodec
                    .intent(context, expected)
                    .putExtra("deadline_kind", "SEQUENCE_TIMER_ZERO")
                    .putExtra("session_kind", "SEQUENCE"),
            )
        malformed.forEach { assertNull(RuntimeBroadcastOperation.deadline(it)) }
        assertEquals(execution, store.activity(execution.id))
        assertEquals(1L to 0L, store.executionCounts())
    }

    private fun nextDeadline(): RuntimeDeadline =
        requireNotNull(NextRuntimeDeadlineResolver.resolve(requireNotNull(store.live.getActiveRuntime())))

    private fun startActivity() =
        store.activityCommands.startLive(
            ActivityEntrySource.Template(
                store.authoring
                    .createActivityTemplate(
                        ActivityTemplateDraft("Timer", null, TimeTrackingMode.TIMER, Duration.ofSeconds(60)),
                        createdAt = at(0),
                    ).id,
            ),
            at(0),
            at(0),
            ZoneOffset.UTC,
        )

    private fun startSequence(): SequenceExecutionId {
        val activities =
            listOf(5L, 7L, 11L).map {
                store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Timer $it", null, TimeTrackingMode.TIMER, Duration.ofSeconds(it)),
                    createdAt = at(0),
                )
            }
        val template =
            store.authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Sequence",
                    null,
                    settings = SequenceTemplateSettings(beforeEachStepCountdown = Duration.ofSeconds(3)),
                    nodes =
                        activities.mapIndexed { index, activity ->
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
        return store.library
            .startSequenceFromTemplate(template.id, at(0), at(0), ZoneOffset.UTC)
            .execution.id
    }

    private fun open(databaseName: String = name) {
        check(storeReference == null)
        storeReference = RuntimeRecoveryStore(context, databaseName)
    }

    private fun discard() {
        processReference = null
        storeReference?.close()
        storeReference = null
    }

    private fun createProcess(
        wallSeconds: Long,
        elapsedMs: Long,
        observeAnchor: Boolean = false,
    ): Process = Process(store, wallSeconds, elapsedMs, observeAnchor).also { processReference = it }

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

    private class Process(
        store: RuntimeRecoveryStore,
        wallSeconds: Long,
        elapsedMs: Long,
        observeAnchor: Boolean,
    ) {
        val wall = MutableWallClock(at(wallSeconds))
        val monotonic = MutableMonotonicClock(elapsedMs)
        val scheduler = RecordingScheduler()
        val local = RecordingLocalDriver()
        val effects = mutableListOf<RuntimeDeadlineFeedback>()
        val notifications = RecordingNotifications()
        var anchorAtReconciliation: WallMonotonicAnchor? = null
        val coordinator: AndroidRuntimeCoordinator

        init {
            coordinator =
                if (observeAnchor) {
                    AndroidRuntimeCoordinator(
                        store.live::getActiveRuntime,
                        { now ->
                            anchorAtReconciliation = currentAnchor()
                            store.live.reconcileActiveSession(now)
                        },
                        wall,
                        monotonic,
                        scheduler,
                        local,
                        RuntimeFeedbackDispatcher(effects::add),
                        notifications,
                        {},
                    )
                } else {
                    AndroidRuntimeCoordinator(
                        store.live,
                        wall,
                        monotonic,
                        scheduler,
                        local,
                        RuntimeFeedbackDispatcher(effects::add),
                        notifications,
                    )
                }
        }

        private fun currentAnchor() = coordinator.clockAnchor.snapshot()
    }

    private class RecordingScheduler : RuntimeDeadlineScheduler {
        val deadlines = mutableListOf<RuntimeDeadline>()
        var cancellations = 0
        var fail = false

        override fun schedule(deadline: RuntimeDeadline) {
            if (fail) error("alarm arm failed")
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
        var callback: (suspend (RuntimeDeadline) -> Unit)? = null
        var cancellations = 0
        var fail = false

        override fun arm(
            deadline: RuntimeDeadline,
            anchor: WallMonotonicAnchor,
            callback: suspend (RuntimeDeadline) -> Unit,
        ) {
            if (fail) error("local arm failed")
            this.deadline = deadline
            this.anchor = anchor
            this.callback = callback
        }

        override fun cancel() {
            if (fail) error("local cancellation failed")
            cancellations++
            deadline = null
            anchor = null
            callback = null
        }
    }

    private class RecordingNotifications : RuntimeNotificationPublisher {
        val completions = mutableListOf<RuntimeDeadlineFeedback>()

        override fun publish(
            runtime: ActiveRuntime?,
            completion: RuntimeDeadlineFeedback?,
        ) {
            completion?.let(completions::add)
        }

        override fun canPostRuntimeNotifications(): Boolean = true
    }

    private companion object {
        fun at(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
    }
}
