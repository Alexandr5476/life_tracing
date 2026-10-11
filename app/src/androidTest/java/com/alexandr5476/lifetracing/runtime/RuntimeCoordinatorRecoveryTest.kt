package com.alexandr5476.lifetracing.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceState
import com.alexandr5476.lifetracing.domain.ActiveSequenceStateResolver
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
import org.junit.runners.Parameterized
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

@RunWith(Parameterized::class)
@Suppress("LargeClass", "TooManyFunctions") // One file-backed ownership harness covers recovery entry points.
class RuntimeCoordinatorRecoveryTest(
    private val origin: Instant,
    private val eventZone: ZoneId,
) {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "coordinator-recovery-" + System.nanoTime()
    private val lateName = "$name-late"
    private var storeReference: RuntimeRecoveryStore? = null
    private var processReference: Process? = null
    private val store get() = requireNotNull(storeReference)
    private val process get() = requireNotNull(processReference)
    private val previousZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(eventZone))
        open()
    }

    @After
    fun tearDown() {
        try {
            discard()
            context.deleteDatabase(name)
            context.deleteDatabase(lateName)
        } finally {
            TimeZone.setDefault(previousZone)
        }
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
                // Time changes cancel the old clock mapping before terminal scheduling cancels again.
                val expectedCancellations =
                    if (expectedOperations[index] == RuntimeBroadcastOperation.TimeChanged) 2 else 1
                assertEquals(expectedCancellations, process.scheduler.cancellations)
                assertEquals(expectedCancellations, process.local.cancellations)
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

    @Test
    fun runningTimedCurrentRetainsIdentityAcrossColdForegroundAndRelocation() =
        runBlocking {
            val id = startSequence()
            assertColdForegroundPreservesState(id, 4, ActiveSequenceState.RUNNING_CURRENT)
            assertEquals(at(5), nextDeadline().at)
            val display = requireNotNull(process.coordinator.displayBaseline)
            assertEquals(Duration.ofSeconds(1), display.timerRemaining(900_000))
            assertEquals(Duration.ofMillis(500), display.timerRemaining(900_500))
        }

    @Test
    fun runningStopwatchCurrentNeverAcquiresAnAutomaticDeadline() =
        runBlocking {
            val id = startSequence(mode = TimeTrackingMode.STOPWATCH)
            assertColdForegroundPreservesState(id, 100, ActiveSequenceState.RUNNING_CURRENT)
            assertNull(NextRuntimeDeadlineResolver.resolve(requireNotNull(store.live.getActiveRuntime())))
            assertEquals(Duration.ofSeconds(100), process.coordinator.displayBaseline!!.activeElapsed(900_000))
            assertEquals(Duration.ofSeconds(101), process.coordinator.displayBaseline!!.activeElapsed(901_000))
        }

    @Test
    fun pausedCurrentRetainsChildAndPauseUntilResumeInTheNewZone() =
        runBlocking {
            val id = startSequence()
            store.live.pauseActiveSequence(id, at(1))
            assertColdForegroundPreservesState(id, 100, ActiveSequenceState.PAUSED_CURRENT)
            val paused = store.sequence(id)
            val child = store.children(id).single()
            val display = requireNotNull(process.coordinator.displayBaseline)
            assertEquals(Duration.ofSeconds(1), display.activeElapsed(900_000))
            assertEquals(display.activeElapsed(900_000), display.activeElapsed(999_000))
            store.live.resumeActiveSequence(id, at(100))
            assertEquals(at(104), nextDeadline().at)
            assertEquals(child.id, store.children(id).single().id)
            assertEquals(
                child.pauses.single().id,
                store
                    .children(id)
                    .single()
                    .pauses
                    .single()
                    .id,
            )
            assertEquals(
                paused.intervals.map { it.id },
                store
                    .sequence(id)
                    .intervals
                    .take(2)
                    .map { it.id },
            )
            discard()
            open()
            assertTrue(
                store.live
                    .reconcileActiveSession(at(104).minusMillis(1))
                    .appliedEvents
                    .isEmpty(),
            )
            assertEquals(
                at(104),
                store.live
                    .reconcileActiveSession(at(104))
                    .appliedEvents
                    .single()
                    .deadline.at,
            )
        }

    @Test
    fun waitingNextRemainsWithoutCurrentChildOrDeadlineAcrossTemporalRecovery() =
        runBlocking {
            val id = startSequence(autoAdvance = false)
            store.live.reconcileActiveSession(at(5))
            assertColdForegroundPreservesState(id, 100, ActiveSequenceState.WAITING_NEXT)
        }

    @Test
    fun runningCountdownRetainsTargetAndEntersItOnceAtTheMillisecondBoundary() =
        runBlocking {
            val id = startSequence()
            store.live.reconcileActiveSession(at(5))
            assertColdForegroundPreservesState(id, 6, ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN)
            val deadline = nextDeadline()
            val target = (store.live.getActiveRuntime() as ActiveSequenceRuntime).transitionCountdownTargetId
            process.wall.value = at(8).minusMillis(1)
            process.coordinator.onDeadlineSignal(deadline)
            assertNull(store.sequence(id).currentOccurrenceId)
            assertEquals(1, store.children(id).size)
            process.wall.value = at(8)
            process.coordinator.onDeadlineSignal(deadline)
            assertEquals(target, store.sequence(id).currentOccurrenceId)
            assertEquals(at(8), store.children(id).maxBy { requireNotNull(it.startedAt) }.startedAt)
            val children = store.children(id)
            process.wall.value = at(8).plusMillis(1)
            process.coordinator.onDeadlineSignal(deadline)
            assertEquals(children, store.children(id))
            assertEquals(1, process.effects.size)
        }

    @Test
    fun pausedCountdownRetainsConsumedIntervalAndResumeUsesOnlyTheRemainder() =
        runBlocking {
            val id = startSequence()
            store.live.reconcileActiveSession(at(5))
            store.live.pauseActiveSequence(id, at(6))
            assertColdForegroundPreservesState(id, 100, ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN)
            val paused = store.sequence(id)
            val display = requireNotNull(process.coordinator.displayBaseline)
            assertEquals(Duration.ofSeconds(2), display.transitionCountdownRemaining(900_000))
            assertEquals(Duration.ofSeconds(2), display.transitionCountdownRemaining(999_000))
            val target = (store.live.getActiveRuntime() as ActiveSequenceRuntime).transitionCountdownTargetId
            store.live.resumeActiveSequence(id, at(100))
            assertEquals(at(102), nextDeadline().at)
            assertEquals(
                paused.intervals.map { it.id },
                store
                    .sequence(id)
                    .intervals
                    .take(3)
                    .map { it.id },
            )
            discard()
            open()
            assertTrue(
                store.live
                    .reconcileActiveSession(at(102).minusMillis(1))
                    .appliedEvents
                    .isEmpty(),
            )
            assertEquals(1, store.children(id).size)
            assertEquals(
                at(102),
                store.live
                    .reconcileActiveSession(at(102))
                    .appliedEvents
                    .single()
                    .deadline.at,
            )
            assertEquals(target, store.sequence(id).currentOccurrenceId)
            assertEquals(at(102), store.children(id).maxBy { requireNotNull(it.startedAt) }.startedAt)
        }

    @Test
    fun noLiveCurrentRemainsRepresentableWithoutATimedChildOrDeadline() =
        runBlocking {
            val id = startSequence(mode = TimeTrackingMode.NO_LIVE_TRACKING)
            assertColdForegroundPreservesState(id, 100, ActiveSequenceState.RUNNING_CURRENT)
            assertTrue(store.children(id).isEmpty())
            assertNull(NextRuntimeDeadlineResolver.resolve(requireNotNull(store.live.getActiveRuntime())))
        }

    @Test
    fun terminalSequenceCannotRewindOrRegainAnActivePointer() =
        runBlocking {
            for (early in listOf(false, true)) {
                val id = startSequence()
                if (early) store.live.endSequenceEarly(id, at(1)) else store.live.reconcileActiveSession(at(100))
                assertEquals(
                    if (early) SequenceExecutionStatus.ENDED_EARLY else SequenceExecutionStatus.COMPLETED,
                    store.sequence(id).status,
                )
                assertColdForegroundPreservesState(id, 200, null)
                val terminal = store.sequence(id)
                process.wall.value = at(0)
                process.coordinator.onSystemTimeChanged()
                assertEquals(terminal, store.sequence(id))
                assertNull(store.live.getActiveSession())
            }
        }

    @Test
    fun timezoneBroadcastBeforeAndAfterActivityDeadlineIsSilentAndIdempotent() =
        runBlocking {
            val execution = startActivity()
            val before = requireNotNull(store.live.getActiveRuntime())
            val expected = nextDeadline()
            createProcess(0, 1_000).coordinator.onRuntimeStateChanged()
            val obsoleteCallback = requireNotNull(process.local.callback)
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            process.wall.value = expected.at.minusMillis(1)
            process.monotonic.value = 2_000
            val generation = process.coordinator.semanticGeneration.value
            val signal =
                requireNotNull(RuntimeBroadcastOperation.recovery(android.content.Intent.ACTION_TIMEZONE_CHANGED))
            signal.dispatch(process.coordinator)
            signal.dispatch(process.coordinator)
            assertEquals(generation + 2, process.coordinator.semanticGeneration.value)
            assertEquals(before, store.live.getActiveRuntime())
            assertEquals(expected, process.local.deadline)
            assertEquals(WallMonotonicAnchor(expected.at.minusMillis(1), 2_000), process.local.anchor)
            assertEquals(2_001L, process.local.anchor!!.elapsedAt(expected.at))
            assertEquals(2, process.local.cancellations)
            assertEquals(2, process.scheduler.cancellations)
            assertTrue(process.effects.isEmpty())
            process.wall.value = expected.at.plusMillis(1)
            process.monotonic.value = 3_000
            signal.dispatch(process.coordinator)
            val completed = store.activity(execution.id)
            assertEquals(expected.at, completed.completedAt)
            assertEquals(execution.originalZoneId, completed.originalZoneId)
            assertEquals(execution.originalUtcOffsetMinutes, completed.originalUtcOffsetMinutes)
            assertEquals(execution.primaryLocalDate, completed.primaryLocalDate)
            assertEquals(execution.statisticsSeriesId, completed.statisticsSeriesId)
            obsoleteCallback(expected)
            signal.dispatch(process.coordinator)
            process.coordinator.onForeground()
            assertEquals(completed, store.activity(execution.id))
            assertEquals(1L to 0L, store.executionCounts())
            assertTrue(process.effects.isEmpty())
            assertTrue(process.notifications.completions.isEmpty())
            discard()
            open()
            createProcess(100, 90_000)
            signal.dispatch(process.coordinator)
            assertNull(store.live.getActiveRuntime())
            assertNull(process.coordinator.displayBaseline)
            assertEquals(completed, store.activity(execution.id))
        }

    @Test
    fun coldDstChainPersistsOrderedLogicalEventsAndExactIntervalAccounting() =
        runBlocking {
            val id = startSequence()
            val before = store.sequence(id)
            val child = store.children(id).single()
            discard()
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            open()
            val result = store.live.reconcileActiveSession(at(100))
            assertEquals(listOf(5L, 8L, 15L, 18L, 29L).map(::at), result.appliedEvents.map { it.deadline.at })
            assertEquals(
                listOf(
                    RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO,
                    RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN,
                    RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO,
                    RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN,
                    RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO,
                ),
                result.appliedEvents.map { it.deadline.kind },
            )
            val terminal = store.sequence(id)
            assertEquals(listOf(0L, 5L, 8L, 15L, 18L).map(::at), terminal.intervals.map { it.startedAt })
            assertEquals(listOf(5L, 8L, 15L, 18L, 29L).map(::at), terminal.intervals.map { it.endedAt })
            assertEquals(Duration.ofSeconds(23), terminal.activeDuration)
            assertEquals(Duration.ofSeconds(6), terminal.pauseDuration)
            assertEquals(Duration.ofSeconds(29), terminal.wallDuration)
            assertEquals(before.snapshotId, terminal.snapshotId)
            assertEquals(before.statisticsSeriesId, terminal.statisticsSeriesId)
            assertEquals(before.originalZoneId, terminal.originalZoneId)
            assertEquals(before.originalUtcOffsetMinutes, terminal.originalUtcOffsetMinutes)
            assertEquals(before.primaryLocalDate, terminal.primaryLocalDate)
            before.occurrences.zip(terminal.occurrences).forEach { (original, completed) ->
                assertEquals(original.id, completed.id)
                assertEquals(original.sourceSequenceSnapshotNodeId, completed.sourceSequenceSnapshotNodeId)
                assertEquals(original.activitySnapshotId, completed.activitySnapshotId)
                assertEquals(original.runtimePosition, completed.runtimePosition)
                assertEquals(original.repeatSourceSnapshotNodeId, completed.repeatSourceSnapshotNodeId)
                assertEquals(original.repeatIteration, completed.repeatIteration)
                assertEquals(original.isRuntimeAdded, completed.isRuntimeAdded)
            }
            val children = store.children(id)
            val firstChild = children.minBy { requireNotNull(it.startedAt) }
            assertEquals(child.id, firstChild.id)
            assertEquals(child.snapshotId, firstChild.snapshotId)
            assertEquals(child.values, firstChild.values)
            assertEquals(child.statisticsSeriesId, firstChild.statisticsSeriesId)
            assertEquals(3L to 1L, store.executionCounts())
            discard()
            open()
            createProcess(200, 20_000).coordinator.onForeground()
            for (event in result.appliedEvents) process.coordinator.onDeadlineSignal(event.deadline)
            assertEquals(terminal, store.sequence(id))
            assertEquals(children, store.children(id))
            assertTrue(process.effects.isEmpty())
            assertTrue(process.notifications.completions.isEmpty())
        }

    @Test
    fun backwardCountdownClockRearmsAndForwardCatchUpNeverRewindsCommittedFacts() =
        runBlocking {
            val id = startSequence()
            store.live.reconcileActiveSession(at(5))
            createProcess(6, 1_000).coordinator.onRuntimeStateChanged()
            val deadline = nextDeadline()
            val obsolete = requireNotNull(process.local.callback)
            val before = store.live.getActiveRuntime()
            process.wall.value = at(5)
            process.monotonic.value = 20_000
            process.coordinator.onSystemTimeChanged()
            assertEquals(before, store.live.getActiveRuntime())
            assertEquals(WallMonotonicAnchor(at(5), 20_000), process.local.anchor)
            assertEquals(23_000L, process.local.anchor!!.elapsedAt(deadline.at))
            obsolete(deadline)
            assertEquals(before, store.live.getActiveRuntime())
            assertEquals(deadline, process.local.deadline)
            process.wall.value = at(20)
            process.monotonic.value = 21_000
            process.coordinator.onSystemTimeChanged()
            val committed = store.sequence(id)
            val children = store.children(id)
            assertEquals(at(18), committed.occurrences.last().enteredAt)
            assertEquals(at(29), nextDeadline().at)
            assertEquals(WallMonotonicAnchor(at(20), 21_000), process.local.anchor)
            process.wall.value = at(19)
            process.monotonic.value = 22_000
            process.coordinator.onSystemTimeChanged()
            obsolete(deadline)
            assertEquals(committed, store.sequence(id))
            assertEquals(children, store.children(id))
            assertEquals(at(29), process.local.deadline!!.at)
            assertEquals(32_000L, process.local.anchor!!.elapsedAt(at(29)))
            assertTrue(process.effects.isEmpty())
            assertTrue(process.notifications.completions.isEmpty())
            discard()
            open()
            assertEquals(committed, store.sequence(id))
        }

    private suspend fun assertColdForegroundPreservesState(
        id: SequenceExecutionId,
        recoverySeconds: Long,
        expectedState: ActiveSequenceState?,
    ) {
        val before = store.live.getActiveRuntime()
        val root = store.sequence(id)
        val children = store.children(id)
        val counts = store.executionCounts()
        val deadline = before?.let(NextRuntimeDeadlineResolver::resolve)
        createProcess(0, 50_000).coordinator.onRuntimeStateChanged()
        // Backgrounding has no command. Reopening discards the old baseline and every process owner.
        discard()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        open()
        assertEquals(before, store.live.getActiveRuntime())
        createProcess(recoverySeconds, 900_000, observeAnchor = true).coordinator.onForeground()
        repeat(2) {
            requireNotNull(RuntimeBroadcastOperation.recovery(android.content.Intent.ACTION_TIMEZONE_CHANGED))
                .dispatch(process.coordinator)
        }
        assertEquals(before, store.live.getActiveRuntime())
        assertEquals(root, store.sequence(id))
        assertEquals(children, store.children(id))
        assertEquals(counts, store.executionCounts())
        assertEquals(WallMonotonicAnchor(at(recoverySeconds), 900_000), process.anchorAtReconciliation)
        assertEquals(deadline, process.local.deadline)
        assertTrue(process.effects.isEmpty())
        assertTrue(process.notifications.completions.isEmpty())
        if (expectedState != null) {
            val runtime = store.live.getActiveRuntime() as ActiveSequenceRuntime
            assertEquals(expectedState, ActiveSequenceStateResolver.resolve(runtime))
            if (expectedState in
                setOf(
                    ActiveSequenceState.PAUSED_CURRENT,
                    ActiveSequenceState.WAITING_NEXT,
                    ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
                )
            ) {
                assertNull(deadline)
                assertTrue(process.scheduler.deadlines.isEmpty())
            }
            if (expectedState in
                setOf(
                    ActiveSequenceState.WAITING_NEXT,
                    ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN,
                    ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
                )
            ) {
                assertNull(runtime.execution.currentOccurrenceId)
                assertNull(runtime.currentChild)
            }
        } else {
            assertNull(store.live.getActiveSession())
            assertNull(process.coordinator.displayBaseline)
        }
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
            eventZone,
        )

    private fun startSequence(
        mode: TimeTrackingMode = TimeTrackingMode.TIMER,
        autoAdvance: Boolean = true,
    ): SequenceExecutionId {
        val activities =
            listOf(5L, 7L, 11L).map {
                store.authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "Step $it",
                        null,
                        mode,
                        if (mode == TimeTrackingMode.TIMER) Duration.ofSeconds(it) else null,
                    ),
                    createdAt = at(0),
                )
            }
        val template =
            store.authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Sequence",
                    null,
                    settings =
                        SequenceTemplateSettings(
                            autoAdvance = autoAdvance,
                            beforeEachStepCountdown = Duration.ofSeconds(3),
                        ),
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
            .startSequenceFromTemplate(template.id, at(0), at(0), eventZone)
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
    ): Process = Process(store, at(wallSeconds), elapsedMs, observeAnchor).also { processReference = it }

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
        wallInstant: Instant,
        elapsedMs: Long,
        observeAnchor: Boolean,
    ) {
        val wall = MutableWallClock(wallInstant)
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

    private fun at(seconds: Long): Instant = origin.plusSeconds(seconds)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "origin={0}, eventZone={1}")
        fun temporalContexts(): List<Array<Any>> =
            listOf(
                arrayOf(Instant.EPOCH, ZoneOffset.UTC),
                arrayOf(Instant.parse("2026-03-29T00:59:57Z"), ZoneId.of("Europe/Berlin")),
                arrayOf(Instant.parse("2026-10-25T00:59:57Z"), ZoneId.of("Europe/Berlin")),
            )
    }
}
