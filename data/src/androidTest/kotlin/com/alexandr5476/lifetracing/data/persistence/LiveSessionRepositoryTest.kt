package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceTimelineCalculator
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class LiveSessionRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var repository: LiveSessionRepository

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        seed(database)
        repository = repository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun standaloneStartPauseResumeCompleteIsOneCoordinatedState() {
        val execution =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("stopwatch"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        assertEquals(
            "RUNNING",
            database
                .activeSessionDao()
                .get()
                ?.state
                ?.name,
        )

        repository.pauseActiveActivity(ActivityExecutionPauseId("manual-pause"), instant(10))
        assertEquals(ActivityExecutionStatus.PAUSED, repositoryExecution(execution.id.value).status)
        assertEquals(
            "PAUSED",
            database
                .activeSessionDao()
                .get()
                ?.state
                ?.name,
        )

        repository.resumeActiveActivity(instant(20))
        repository.completeActiveActivity(instant(30))

        val completed = repositoryExecution(execution.id.value)
        assertEquals(ActivityExecutionStatus.COMPLETED, completed.status)
        assertEquals(20_000L, completed.activeDuration?.toMillis())
        assertNull(database.activeSessionDao().get())
    }

    @Test
    fun timerReconcilesAtExactDeadlineAndSecondStartCreatesNothing() {
        val execution =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("timer"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        assertThrows(IllegalArgumentException::class.java) {
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("stopwatch"),
                instant(1),
                instant(1),
                ZoneOffset.UTC,
            )
        }
        assertNull(database.activityExecutionDao().getById("activity-2"))

        val firstDelivery = repository.reconcileActiveSession(instant(100))
        val duplicateDelivery = repository.reconcileActiveSession(instant(100))

        assertEquals(instant(60), repositoryExecution(execution.id.value).completedAt)
        assertEquals(
            listOf(RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO),
            firstDelivery.appliedEvents.map { it.deadline.kind },
        )
        assertEquals(
            instant(60),
            firstDelivery.appliedEvents
                .single()
                .deadline.at,
        )
        assertEquals(emptyList<Any>(), duplicateDelivery.appliedEvents)
        assertNull(repository.getActiveSession())
    }

    @Test
    fun standaloneTimerPauseOvertimeAndStopwatchPoliciesAreDeterministic() {
        val pausedTimer =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("timer"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.reconcileActiveSession(instant(10))
        assertEquals(ActivityExecutionStatus.RUNNING, repositoryExecution(pausedTimer.id.value).status)
        repository.pauseActiveActivity(ActivityExecutionPauseId("timer-pause"), instant(20))
        repository.reconcileActiveSession(instant(200))
        assertEquals(ActivityExecutionStatus.PAUSED, repositoryExecution(pausedTimer.id.value).status)
        repository.resumeActiveActivity(instant(220))
        repository.reconcileActiveSession(instant(259))
        assertEquals(ActivityExecutionStatus.RUNNING, repositoryExecution(pausedTimer.id.value).status)
        repository.reconcileActiveSession(instant(260))
        assertEquals(instant(260), repositoryExecution(pausedTimer.id.value).completedAt)

        val overtime =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("timer-overtime"),
                instant(300),
                instant(300),
                ZoneOffset.UTC,
            )
        repository.reconcileActiveSession(instant(1_000))
        assertEquals(ActivityExecutionStatus.RUNNING, repositoryExecution(overtime.id.value).status)
        repository.completeActiveActivity(instant(1_000))

        val stopwatch =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("stopwatch"),
                instant(1_100),
                instant(1_100),
                ZoneOffset.UTC,
            )
        repository.reconcileActiveSession(instant(10_000))
        assertEquals(ActivityExecutionStatus.RUNNING, repositoryExecution(stopwatch.id.value).status)
    }

    @Test
    fun pausingExpiredTimerCommitsNaturalCompletionInstead() {
        val timer =
            repository.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("timer"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )

        repository.pauseActiveActivity(ActivityExecutionPauseId("too-late"), instant(100))

        assertEquals(instant(60), repositoryExecution(timer.id.value).completedAt)
        assertNull(repository.getActiveSession())
    }

    @Test
    fun sequenceStartTransitionPauseResumeAndNaturalFinalCompletionStayAtomic() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val first = started.execution.currentOccurrenceId!!
        assertEquals(
            RuntimeOccurrenceStatus.CURRENT,
            started.execution.occurrences
                .first()
                .status,
        )
        assertNotNull(started.currentChild)
        assertEquals(1, started.execution.intervals.size)

        val transition = repository.completeCurrentSequenceStep(first, instant(5))
        assertEquals(
            SequenceIntervalKind.TRANSITION_COUNTDOWN,
            transition.execution.intervals
                .single {
                    it.endedAt ==
                        null
                }.kind,
        )
        repository.pauseActiveSequence(instant(10))
        assertEquals("PAUSED", repository.getActiveSession()?.state?.name)
        repository.resumeActiveSequence(instant(20))
        repository.reconcileActiveSession(instant(25))

        val running = repositorySequence(started.execution.id.value)
        assertEquals(instant(25), running.occurrences[1].enteredAt)
        val finished = repository.completeCurrentSequenceStep(running.currentOccurrenceId!!, instant(30))
        assertEquals(SequenceExecutionStatus.COMPLETED, finished.execution.status)
        assertNull(repository.getActiveSession())
        assertEquals(10_000L, finished.execution.activeDuration?.toMillis())
        assertEquals(20_000L, finished.execution.pauseDuration?.toMillis())
    }

    @Test
    fun sequenceStartModesAndEmptySequenceUseOnlyDurableRuntimeStates() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-empty"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        }
        assertNull(repository.getActiveSession())

        val timer =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-one-timer"),
                instant(10),
                instant(10),
                ZoneOffset.UTC,
            )
        assertEquals(
            RuntimeOccurrenceStatus.CURRENT,
            timer.execution.occurrences
                .single()
                .status,
        )
        assertEquals(
            SequenceIntervalKind.ACTIVE_STEP,
            timer.execution.intervals
                .single()
                .kind,
        )
        assertNotNull(timer.currentChild)
        repository.completeCurrentSequenceStep(timer.execution.currentOccurrenceId!!, instant(20))

        val noLiveActive =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-no-live-active"),
                instant(30),
                instant(30),
                ZoneOffset.UTC,
            )
        assertEquals(
            SequenceIntervalKind.ACTIVE_STEP,
            noLiveActive.execution.intervals
                .single()
                .kind,
        )
        assertNull(noLiveActive.currentChild)
        repository.completeCurrentSequenceStep(noLiveActive.execution.currentOccurrenceId!!, instant(40))

        val noLivePause =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-no-live-pause"),
                instant(50),
                instant(50),
                ZoneOffset.UTC,
            )
        assertEquals(
            SequenceIntervalKind.STEP_PAUSE,
            noLivePause.execution.intervals
                .single()
                .kind,
        )
        assertNull(noLivePause.currentChild)
        assertEquals(1, noLivePause.execution.intervals.size)
    }

    @Test
    fun staleCompletionCommitsDueNaturalTransitionWithoutTouchingNextStep() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-timer-direct"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val original = started.execution.currentOccurrenceId!!

        assertThrows(IllegalArgumentException::class.java) {
            repository.completeCurrentSequenceStep(original, instant(100))
        }

        val reconciled = repositorySequence(started.execution.id.value)
        assertEquals(instant(60), reconciled.occurrences[0].completedAt)
        assertEquals(instant(60), reconciled.occurrences[1].enteredAt)
        assertEquals(RuntimeOccurrenceStatus.CURRENT, reconciled.occurrences[1].status)
        val active = repository.getActiveRuntime() as ActiveSequenceRuntime
        assertEquals(reconciled, active.execution)
        assertEquals(reconciled.occurrences[1].id, active.execution.currentOccurrenceId)
        assertNull(NextRuntimeDeadlineResolver.resolve(active))
    }

    @Test
    fun goNowPersistsJumpSkippedRowsFreshChildAndPostJumpDeadlineAcrossRepositoryReload() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-navigation"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val current = started.execution.occurrences[0]
        val skipped = started.execution.occurrences[1]
        val target = started.execution.occurrences[2]
        val oldChildId = requireNotNull(started.currentChild).id

        val jumped = repository.goNow(target.id, instant(10))

        assertEquals(OccurrenceCompletionReason.JUMP, jumped.execution.occurrences[0].completionReason)
        assertEquals(10_000L, repositoryExecution(oldChildId.value).activeDuration?.toMillis())
        assertEquals(RuntimeOccurrenceStatus.SKIPPED, jumped.execution.occurrences[1].status)
        assertNull(database.activityExecutionDao().getAggregateByOccurrence(skipped.id.value))
        assertEquals(target.id, jumped.execution.currentOccurrenceId)
        assertEquals(instant(10), jumped.execution.occurrences[2].enteredAt)
        assertEquals(instant(70), requireNotNull(NextRuntimeDeadlineResolver.resolve(activeSequence())).at)

        repository = repository(database, 100)
        val reloaded = activeSequence()
        assertEquals(jumped.execution, reloaded.execution)
        assertEquals(target.id, reloaded.currentChild?.sequenceOccurrenceId)
        assertTrue(reloaded.currentChild?.id != oldChildId)
        assertEquals(current.id, repositoryExecution(oldChildId.value).sequenceOccurrenceId)
    }

    @Test
    fun goNowFromNoLivePersistsOnlyLegitimateCompletedAndTargetChildren() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-no-live-navigation"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val noLive = started.execution.occurrences[0]
        val target = started.execution.occurrences[1]
        assertNull(started.currentChild)

        val jumped = repository.goNow(target.id, instant(10))
        val completedNoLive =
            requireNotNull(database.activityExecutionDao().getAggregateByOccurrence(noLive.id.value)).toDomain()

        assertEquals(ActivityExecutionStatus.COMPLETED, completedNoLive.status)
        assertNull(completedNoLive.startedAt)
        assertNull(completedNoLive.activeDuration)
        assertEquals(OccurrenceCompletionReason.JUMP, jumped.execution.occurrences[0].completionReason)
        assertEquals(target.id, jumped.execution.currentOccurrenceId)
        assertNotNull(database.activityExecutionDao().getAggregateByOccurrence(target.id.value))
    }

    @Test
    fun staleGoNowCommitsEarlierAutomaticTimerTransitionWithoutRewritingIt() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-timer-navigation"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val automaticallyStarted = started.execution.occurrences[1]

        assertThrows(IllegalArgumentException::class.java) {
            repository.goNow(automaticallyStarted.id, instant(70))
        }

        val persisted = repositorySequence(started.execution.id.value)
        assertEquals(instant(60), persisted.occurrences[0].completedAt)
        assertEquals(OccurrenceCompletionReason.NATURAL_TIMER_END, persisted.occurrences[0].completionReason)
        assertEquals(instant(60), persisted.occurrences[1].enteredAt)
        assertEquals(RuntimeOccurrenceStatus.CURRENT, persisted.occurrences[1].status)
        assertEquals(automaticallyStarted.id, persisted.currentOccurrenceId)
        assertNotNull(database.activityExecutionDao().getAggregateByOccurrence(automaticallyStarted.id.value))
    }

    @Test
    fun makeNextPersistsOnlyFutureOrderThroughRepositoryReload() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-four"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val target = started.execution.occurrences[3]
        val childBefore =
            requireNotNull(
                database.activityExecutionDao().getAggregateByOccurrence(started.execution.currentOccurrenceId!!.value),
            )
        val intervalsBefore = database.sequenceExecutionDao().getIntervals(started.execution.id.value)

        val reordered = repository.makeNext(target.id, instant(5))

        assertEquals(
            listOf(
                started.execution.occurrences[0].id,
                target.id,
                started.execution.occurrences[1].id,
                started.execution.occurrences[2].id,
            ),
            reordered.execution.occurrences
                .sortedBy { it.runtimePosition }
                .map { it.id },
        )
        assertEquals(started.execution.currentOccurrenceId, reordered.execution.currentOccurrenceId)
        assertEquals(
            childBefore,
            database.activityExecutionDao().getAggregateByOccurrence(
                started.execution.currentOccurrenceId!!.value,
            ),
        )
        assertEquals(intervalsBefore, database.sequenceExecutionDao().getIntervals(started.execution.id.value))
        repository = repository(database, 100)
        assertEquals(reordered.execution, activeSequence().execution)
    }

    @Test
    fun doAgainUsesFreshIdentitiesSnapshotDefaultsAndCreatesNoDeferredChild() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-defaults"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val original = started.execution.occurrences[0]
        val originalChildId = requireNotNull(started.currentChild).id.value
        database.activityExecutionDao().upsertValue(
            ActivityExecutionFieldValueEntity(originalChildId, "defaults-number", 99, null, null),
        )
        repository.completeCurrentSequenceStep(original.id, instant(10))
        val originalChildBeforeRepeat = requireNotNull(database.activityExecutionDao().getAggregate(originalChildId))
        val originalOccurrenceBeforeRepeat =
            repositorySequence(started.execution.id.value).occurrences.single { it.id == original.id }

        val immediate = repository.doAgain(original.id, RuntimeInsertionPlacement.START_NOW, instant(20))
        val repeated =
            immediate.execution.occurrences.single {
                it.id != original.id &&
                    it.activitySnapshotId == original.activitySnapshotId
            }
        val repeatedChild =
            requireNotNull(database.activityExecutionDao().getAggregateByOccurrence(repeated.id.value))

        assertTrue(repeated.id != original.id)
        assertEquals(original.activitySnapshotId, repeated.activitySnapshotId)
        assertFalse(repeated.isRuntimeAdded)
        assertEquals(original.sourceSequenceSnapshotNodeId, repeated.sourceSequenceSnapshotNodeId)
        assertTrue(repeatedChild.execution.id != originalChildId)
        assertEquals(originalChildBeforeRepeat, database.activityExecutionDao().getAggregate(originalChildId))
        assertEquals(originalOccurrenceBeforeRepeat, immediate.execution.occurrences.single { it.id == original.id })
        assertEquals(5L, repeatedChild.values.single().numberScaled)
        assertEquals(instant(80), requireNotNull(NextRuntimeDeadlineResolver.resolve(activeSequence())).at)

        repository = repository(database, 100)
        val restoredRepeated = activeSequence().execution.occurrences.single { it.id == repeated.id }
        assertEquals(repeated, restoredRepeated)

        val intervalsBeforeDeferred = database.sequenceExecutionDao().getIntervals(started.execution.id.value)
        val deferred = repository.doAgain(original.id, RuntimeInsertionPlacement.AFTER_CURRENT, instant(25))
        val deferredOccurrence =
            deferred.execution.occurrences.single { occurrence ->
                occurrence.id != original.id &&
                    occurrence.id != repeated.id &&
                    occurrence.activitySnapshotId == original.activitySnapshotId
            }
        assertNull(database.activityExecutionDao().getAggregateByOccurrence(deferredOccurrence.id.value))
        assertEquals(intervalsBeforeDeferred, database.sequenceExecutionDao().getIntervals(started.execution.id.value))
        assertEquals(originalChildBeforeRepeat, database.activityExecutionDao().getAggregate(originalChildId))
    }

    @Test
    fun runtimeAddAuthorsTemplateAndOneOffSnapshotsWithDurableNonImmediatePlacements() {
        runtimeTemplate("runtime-template", TimeTrackingMode.TIMER, 60_000, revision = 7)
        val sequenceSnapshotBefore = database.sequenceSnapshotDao().getAggregate("sequence-navigation")
        val templateBefore = database.activityTemplateDao().getById("runtime-template")
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-navigation"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )

        val withTemplate =
            repository.runtimeAdd(
                ActivityEntrySource.Template(ActivityTemplateId("runtime-template")),
                RuntimeInsertionPlacement.TO_END,
                instant(5),
            )
        val templateOccurrence = withTemplate.execution.occurrences.single { it.isRuntimeAdded }
        val templateSnapshot =
            requireNotNull(database.activitySnapshotDao().getAggregate(templateOccurrence.activitySnapshotId.value))
                .toDomain()
        assertEquals(started.execution.occurrences.size, templateOccurrence.runtimePosition)
        assertEquals(ActivityTemplateId("runtime-template"), templateSnapshot.sourceTemplateId)
        assertEquals(7L, templateSnapshot.sourceRevision)
        assertEquals("activity-series", templateSnapshot.statisticsSeriesId?.value)
        assertEquals(
            setOf("runtime-template-number", "runtime-template-category"),
            templateSnapshot.fields.map { it.sourceFieldId?.value }.toSet(),
        )
        assertEquals(
            "runtime-template-option-a",
            templateSnapshot.fields
                .single { it.sourceFieldId?.value == "runtime-template-category" }
                .categoryOptions
                .single()
                .sourceOptionId
                ?.value,
        )
        assertNull(database.activityExecutionDao().getAggregateByOccurrence(templateOccurrence.id.value))
        assertEquals(5_000L, database.activityTemplateDao().getUserState("runtime-template")?.lastUsedAtMs)

        val withOneOff =
            repository.runtimeAdd(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.STOPWATCH)),
                RuntimeInsertionPlacement.AFTER_CURRENT,
                instant(6),
            )
        val oneOffOccurrence =
            withOneOff.execution.occurrences.single {
                it.isRuntimeAdded && it.id != templateOccurrence.id
            }
        val oneOffSnapshot =
            requireNotNull(database.activitySnapshotDao().getAggregate(oneOffOccurrence.activitySnapshotId.value))
                .toDomain()
        assertEquals(1, oneOffOccurrence.runtimePosition)
        assertEquals(
            started.execution.occurrences
                .drop(1)
                .map { it.id } + templateOccurrence.id,
            withOneOff.execution.occurrences
                .sortedBy { it.runtimePosition }
                .drop(2)
                .map { it.id },
        )
        assertNull(oneOffSnapshot.sourceTemplateId)
        assertNull(oneOffSnapshot.sourceRevision)
        assertNull(oneOffSnapshot.statisticsSeriesId)
        assertTrue(oneOffSnapshot.fields.all { it.sourceFieldId == null })
        assertNull(database.activityExecutionDao().getAggregateByOccurrence(oneOffOccurrence.id.value))
        assertEquals(5_000L, database.activityTemplateDao().getUserState("runtime-template")?.lastUsedAtMs)
        database.libraryDao().touchActivity("runtime-template", 20_000)
        repository.runtimeAdd(
            ActivityEntrySource.Template(ActivityTemplateId("runtime-template")),
            RuntimeInsertionPlacement.TO_END,
            instant(7),
        )
        assertEquals(20_000L, database.activityTemplateDao().getUserState("runtime-template")?.lastUsedAtMs)
        assertEquals(templateBefore, database.activityTemplateDao().getById("runtime-template"))
        assertEquals(sequenceSnapshotBefore, database.sequenceSnapshotDao().getAggregate("sequence-navigation"))
    }

    @Test
    fun runtimeAddStartNowUsesSnapshotDefaultsAndNoLiveCreatesNoCurrentChild() {
        val waiting =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-waiting"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(waiting.execution.currentOccurrenceId!!, instant(10))

        val timed =
            repository.runtimeAdd(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.TIMER)),
                RuntimeInsertionPlacement.START_NOW,
                instant(20),
            )
        val timedOccurrence = timed.execution.occurrences.single { it.isRuntimeAdded }
        val timedChild =
            requireNotNull(database.activityExecutionDao().getAggregateByOccurrence(timedOccurrence.id.value))
        assertEquals(timedOccurrence.id, timed.execution.currentOccurrenceId)
        assertEquals(5L, timedChild.values.single().numberScaled)
        assertEquals(instant(80), requireNotNull(NextRuntimeDeadlineResolver.resolve(activeSequence())).at)
        assertEquals("RUNNING", repository.getActiveSession()?.state?.name)
        repository.endSequenceEarly(instant(30))

        val noLiveSequence =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-waiting"),
                instant(40),
                instant(40),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(noLiveSequence.execution.currentOccurrenceId!!, instant(50))
        val noLive =
            repository.runtimeAdd(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.NO_LIVE_TRACKING)),
                RuntimeInsertionPlacement.START_NOW,
                instant(60),
            )
        val noLiveOccurrence = noLive.execution.occurrences.single { it.isRuntimeAdded }
        assertEquals(noLiveOccurrence.id, noLive.execution.currentOccurrenceId)
        assertNull(database.activityExecutionDao().getAggregateByOccurrence(noLiveOccurrence.id.value))
        assertEquals(
            SequenceIntervalKind.ACTIVE_STEP,
            noLive.execution.intervals
                .single { it.endedAt == null }
                .kind,
        )
    }

    @Test
    fun startNowInterruptsPersistedTransitionCountdownAndInvalidatesItsOldDeadline() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(1))
        val target = started.execution.occurrences[1]

        val inserted =
            repository.runtimeAdd(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.STOPWATCH)),
                RuntimeInsertionPlacement.START_NOW,
                instant(4),
            )
        val added = inserted.execution.occurrences.single { it.isRuntimeAdded }

        assertEquals(
            instant(4),
            inserted.execution.intervals
                .single {
                    it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN && it.occurrenceId == target.id
                }.endedAt,
        )
        assertEquals(
            RuntimeOccurrenceStatus.NOT_STARTED,
            inserted.execution.occurrences
                .single { it.id == target.id }
                .status,
        )
        assertNull(database.activityExecutionDao().getAggregateByOccurrence(target.id.value))
        assertEquals(added.id, inserted.execution.currentOccurrenceId)

        repository = repository(database, 100)
        assertEquals(inserted.execution, activeSequence().execution)
        repository.completeCurrentSequenceStep(added.id, instant(6))
        assertEquals(instant(13), requireNotNull(NextRuntimeDeadlineResolver.resolve(activeSequence())).at)

        repository.reconcileActiveSession(instant(11))
        val staleDeadline = repositorySequence(started.execution.id.value)
        assertNull(staleDeadline.currentOccurrenceId)
        assertEquals(
            RuntimeOccurrenceStatus.NOT_STARTED,
            staleDeadline.occurrences.single { it.id == target.id }.status,
        )
        assertNull(database.activityExecutionDao().getAggregateByOccurrence(target.id.value))
    }

    @Test
    fun startNowAtCountdownBoundaryCommitsOnlyOriginalTargetTransition() {
        runtimeTemplate("runtime-template", TimeTrackingMode.TIMER, 60_000)
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(1))
        val target = started.execution.occurrences[1]

        assertThrows(IllegalArgumentException::class.java) {
            repository.runtimeAdd(
                ActivityEntrySource.Template(ActivityTemplateId("runtime-template")),
                RuntimeInsertionPlacement.START_NOW,
                instant(11),
            )
        }

        val persisted = repositorySequence(started.execution.id.value)
        assertEquals(target.id, persisted.currentOccurrenceId)
        assertEquals(instant(11), persisted.occurrences.single { it.id == target.id }.enteredAt)
        assertEquals(started.execution.occurrences.map { it.id }, persisted.occurrences.map { it.id })
        assertNull(database.activitySnapshotDao().getAggregate("runtime-snapshot-1"))
        assertNull(database.activityTemplateDao().getUserState("runtime-template")?.lastUsedAtMs)
    }

    @Test
    fun doAgainPreservesFrozenTimerStepOverrideAcrossRoomReload() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-step-overtime"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val original = started.execution.occurrences.first()
        repository.completeCurrentSequenceStep(original.id, instant(10))

        val replayed = repository.doAgain(original.id, RuntimeInsertionPlacement.START_NOW, instant(20))
        val replay =
            replayed.execution.occurrences.single {
                it.id != original.id &&
                    it.status == RuntimeOccurrenceStatus.CURRENT
            }

        assertTrue(replay.id != original.id)
        assertEquals(original.activitySnapshotId, replay.activitySnapshotId)
        assertEquals(original.sourceSequenceSnapshotNodeId, replay.sourceSequenceSnapshotNodeId)
        assertFalse(replay.isRuntimeAdded)
        assertNull(NextRuntimeDeadlineResolver.resolve(activeSequence()))

        repository = repository(database, 100)
        val restored = activeSequence()
        assertEquals(replay, restored.execution.occurrences.single { it.id == replay.id })
        assertNull(NextRuntimeDeadlineResolver.resolve(restored))
        repository.reconcileActiveSession(instant(1_000))
        assertEquals(replay.id, repositorySequence(started.execution.id.value).currentOccurrenceId)
    }

    @Test
    fun doAgainPreservesRepeatProvenanceAcrossRoomReload() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-repeat-overtime"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val original = started.execution.occurrences.first()
        repository.completeCurrentSequenceStep(original.id, instant(10))

        val replayed = repository.doAgain(original.id, RuntimeInsertionPlacement.START_NOW, instant(20))
        val replay =
            replayed.execution.occurrences.single {
                it.id != original.id &&
                    it.status == RuntimeOccurrenceStatus.CURRENT
            }

        assertEquals(original.sourceSequenceSnapshotNodeId, replay.sourceSequenceSnapshotNodeId)
        assertEquals(original.repeatSourceSnapshotNodeId, replay.repeatSourceSnapshotNodeId)
        assertEquals(original.repeatIteration, replay.repeatIteration)
        assertFalse(replay.isRuntimeAdded)
        assertNull(NextRuntimeDeadlineResolver.resolve(activeSequence()))

        repository = repository(database, 100)
        assertEquals(replay, activeSequence().execution.occurrences.single { it.id == replay.id })
        repository.reconcileActiveSession(instant(1_000))
        assertEquals(replay.id, repositorySequence(started.execution.id.value).currentOccurrenceId)
    }

    @Test
    fun doAgainOfRuntimeAddedOccurrenceRemainsSourceLessAcrossRoomReload() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-waiting"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(10))
        val addedState =
            repository.runtimeAdd(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.STOPWATCH)),
                RuntimeInsertionPlacement.START_NOW,
                instant(20),
            )
        val added = addedState.execution.occurrences.single { it.isRuntimeAdded }
        repository.completeCurrentSequenceStep(added.id, instant(30))

        val replayed = repository.doAgain(added.id, RuntimeInsertionPlacement.START_NOW, instant(40))
        val replay = replayed.execution.occurrences.single { it.isRuntimeAdded && it.id != added.id }

        assertNull(replay.sourceSequenceSnapshotNodeId)
        assertNull(replay.repeatSourceSnapshotNodeId)
        assertNull(replay.repeatIteration)
        repository = repository(database, 100)
        assertEquals(replay, activeSequence().execution.occurrences.single { it.id == replay.id })
    }

    @Test
    fun earlyEndedRuntimeTemplateChildFeedsActivitySeriesWithoutGlobalDoubleCounting() {
        runtimeTemplate("runtime-template", TimeTrackingMode.TIMER, 60_000)
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-waiting"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(10))
        val added =
            repository.runtimeAdd(
                ActivityEntrySource.Template(ActivityTemplateId("runtime-template")),
                RuntimeInsertionPlacement.START_NOW,
                instant(20),
            )
        val runtimeOccurrence = added.execution.occurrences.single { it.isRuntimeAdded }
        val runtimeSnapshot =
            requireNotNull(
                database.activitySnapshotDao().getAggregate(runtimeOccurrence.activitySnapshotId.value),
            ).toDomain()

        val ended = repository.endSequenceEarly(instant(50))
        val statistics = StatisticsRepository(database) { StatisticsSeriesId("unused") }
        val global = statistics.global(StatisticsPeriod.AllTime)
        val sequence =
            statistics.sequenceSeries(StatisticsSeriesId("sequence-series"), StatisticsPeriod.AllTime)
        val activity =
            statistics.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime)
        val number =
            statistics.numberFieldStatistics(
                StatisticsSeriesId("activity-series"),
                StatisticsFieldId.Activity(ActivityTemplateFieldId("runtime-template-number")),
                StatisticsPeriod.AllTime,
            )

        assertEquals(SequenceExecutionStatus.ENDED_EARLY, ended.execution.status)
        assertEquals(StatisticsSeriesId("activity-series"), runtimeSnapshot.statisticsSeriesId)
        assertEquals(Duration.ofSeconds(40), ended.execution.activeDuration)
        assertEquals(Duration.ofSeconds(10), ended.execution.pauseDuration)
        assertEquals(Duration.ofSeconds(40), global.totalTrackedDuration)
        assertEquals(1L, global.topLevelExecutionCount)
        assertEquals(Duration.ofSeconds(40), sequence.activeDurations.total)
        assertEquals(Duration.ofSeconds(10), sequence.totalPauseIdleDuration)
        assertEquals(2L, activity.executionCount)
        assertEquals(Duration.ofSeconds(40), activity.durations.total)
        assertEquals(2L, number.relevantExecutionCount)
        assertEquals(1L, number.recordedCount)
    }

    @Test
    fun runtimeAddedNoLivePauseUsesParentTimelineWithoutSyntheticChildDuration() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-waiting-pause"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(10))
        val added =
            repository.runtimeAdd(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.NO_LIVE_TRACKING)),
                RuntimeInsertionPlacement.START_NOW,
                instant(20),
            )
        val occurrence = added.execution.occurrences.single { it.isRuntimeAdded }

        assertNull(database.activityExecutionDao().getAggregateByOccurrence(occurrence.id.value))
        assertEquals(
            SequenceIntervalKind.STEP_PAUSE,
            added.execution.intervals
                .single { it.endedAt == null }
                .kind,
        )

        val ended = repository.endSequenceEarly(instant(50))
        val child =
            requireNotNull(
                database.activityExecutionDao().getAggregateByOccurrence(occurrence.id.value),
            ).toDomain()

        assertEquals(Duration.ofSeconds(10), ended.execution.activeDuration)
        assertEquals(Duration.ofSeconds(40), ended.execution.pauseDuration)
        assertEquals(Duration.ofSeconds(50), ended.execution.wallDuration)
        assertNull(child.activeDuration)
    }

    @Test
    fun deferredRuntimeAddAndDoAgainAdvanceInCommittedOrder() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-timer-navigation"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val original = started.execution.occurrences.first()
        val originalDeadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(activeSequence()))
        val added =
            repository.runtimeAdd(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.STOPWATCH)),
                RuntimeInsertionPlacement.AFTER_CURRENT,
                instant(5),
            )
        val addedOccurrence = added.execution.occurrences.single { it.isRuntimeAdded }

        assertEquals(originalDeadline, NextRuntimeDeadlineResolver.resolve(activeSequence()))
        repository.reconcileActiveSession(instant(60))
        assertEquals(addedOccurrence.id, activeSequence().execution.currentOccurrenceId)

        val repeated =
            repository.doAgain(original.id, RuntimeInsertionPlacement.AFTER_CURRENT, instant(61))
        val repeatedOccurrence =
            repeated.execution.occurrences.single {
                it.id != original.id &&
                    it.id != addedOccurrence.id &&
                    it.sourceSequenceSnapshotNodeId == original.sourceSequenceSnapshotNodeId
            }
        repository.completeCurrentSequenceStep(addedOccurrence.id, instant(70))

        assertEquals(repeatedOccurrence.id, activeSequence().execution.currentOccurrenceId)
    }

    @Test
    fun invalidRuntimeAddAfterAutomaticTransitionCommitsOnlyReconciliation() {
        runtimeTemplate("runtime-template", TimeTrackingMode.TIMER, 60_000)
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-waiting"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )

        assertThrows(IllegalArgumentException::class.java) {
            repository.runtimeAdd(
                ActivityEntrySource.Template(ActivityTemplateId("runtime-template")),
                RuntimeInsertionPlacement.AFTER_CURRENT,
                instant(70),
            )
        }

        val persisted = repositorySequence(started.execution.id.value)
        assertEquals(instant(60), persisted.occurrences.first().completedAt)
        assertEquals(OccurrenceCompletionReason.NATURAL_TIMER_END, persisted.occurrences.first().completionReason)
        assertNull(persisted.currentOccurrenceId)
        assertEquals(started.execution.occurrences.map { it.id }, persisted.occurrences.map { it.id })
        assertNull(database.activitySnapshotDao().getAggregate("runtime-snapshot-1"))
        assertNull(database.activityTemplateDao().getUserState("runtime-template")?.lastUsedAtMs)
        database.activityTemplateDao().archive("runtime-template", 71_000)
        assertThrows(IllegalArgumentException::class.java) {
            repository.runtimeAdd(
                ActivityEntrySource.Template(ActivityTemplateId("runtime-template")),
                RuntimeInsertionPlacement.TO_END,
                instant(71),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.runtimeAdd(
                ActivityEntrySource.Plan(
                    com.alexandr5476.lifetracing.domain
                        .PlanEntryId("plan"),
                ),
                RuntimeInsertionPlacement.TO_END,
                instant(72),
            )
        }
        assertNull(database.activitySnapshotDao().getAggregate("runtime-snapshot-2"))
    }

    @Test
    fun lateRuntimeAddFailureRollsBackSnapshotTopologyChildSessionAndRecent() {
        runtimeTemplate("runtime-template", TimeTrackingMode.TIMER, 60_000)
        database.libraryDao().touchActivity("runtime-template", 15_000)
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-waiting"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(10))
        val before = repositorySequence(started.execution.id.value)
        val sessionBefore = database.activeSessionDao().get()
        val snapshotCount = count("activity_snapshots")
        val childCount = count("activity_executions")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_runtime_add_recent BEFORE UPDATE ON activity_template_user_state " +
                "WHEN OLD.activity_template_id = 'runtime-template' " +
                "BEGIN SELECT RAISE(ABORT, 'induced Runtime Add failure'); END",
        )

        assertThrows(RuntimeException::class.java) {
            repository.runtimeAdd(
                ActivityEntrySource.Template(ActivityTemplateId("runtime-template")),
                RuntimeInsertionPlacement.START_NOW,
                instant(20),
            )
        }

        assertEquals(before, repositorySequence(started.execution.id.value))
        assertEquals(sessionBefore, database.activeSessionDao().get())
        assertEquals(snapshotCount, count("activity_snapshots"))
        assertEquals(childCount, count("activity_executions"))
        assertNull(database.activitySnapshotDao().getAggregate("runtime-snapshot-1"))
        assertEquals(15_000L, database.activityTemplateDao().getUserState("runtime-template")?.lastUsedAtMs)
    }

    @Test
    fun authoredRuntimeAddSnapshotsAndCurrentChildRecoverAndReconcileAfterFileReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "runtime-add-authoring-reopen-${System.nanoTime()}"
        database.close()
        context.deleteDatabase(name)
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            seed(database)
            runtimeTemplate("runtime-template", TimeTrackingMode.TIMER, 60_000, revision = 9)
            repository = repository(database)
            val started =
                repository.startSequenceFromSnapshot(
                    SequenceSnapshotId("sequence-waiting"),
                    instant(0),
                    instant(0),
                    ZoneOffset.UTC,
                )
            repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(10))
            val deferred =
                repository
                    .runtimeAdd(
                        ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.STOPWATCH)),
                        RuntimeInsertionPlacement.TO_END,
                        instant(15),
                    ).execution.occurrences
                    .single { it.isRuntimeAdded }
            val immediateState =
                repository.runtimeAdd(
                    ActivityEntrySource.Template(ActivityTemplateId("runtime-template")),
                    RuntimeInsertionPlacement.START_NOW,
                    instant(20),
                )
            val immediate = immediateState.execution.occurrences.single { it.isRuntimeAdded && it.id != deferred.id }
            val childId = requireNotNull(immediateState.currentChild).id
            database.close()

            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = repository(database, 100)
            val recovered = activeSequence()
            assertEquals(immediate.id, recovered.execution.currentOccurrenceId)
            assertEquals(childId, recovered.currentChild?.id)
            assertEquals(9L, recovered.activitySnapshots.getValue(immediate.activitySnapshotId).sourceRevision)
            assertEquals(
                ActivityTemplateId("runtime-template"),
                recovered.activitySnapshots.getValue(immediate.activitySnapshotId).sourceTemplateId,
            )
            assertNull(recovered.activitySnapshots.getValue(deferred.activitySnapshotId).sourceTemplateId)
            assertEquals(instant(80), requireNotNull(NextRuntimeDeadlineResolver.resolve(recovered)).at)

            repository.reconcileActiveSession(instant(80))
            val reconciled = repositorySequence(started.execution.id.value)
            assertEquals(instant(80), reconciled.occurrences.single { it.id == immediate.id }.completedAt)
            assertEquals("WAITING_NEXT", repository.getActiveSession()?.state?.name)
        } finally {
            database.close()
            context.deleteDatabase(name)
            database = inMemoryDatabase()
        }
    }

    @Test
    fun earlyEndPersistsTimedNoLiveAndWaitingTerminalShapesWithoutSyntheticChildren() {
        val timed =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-navigation"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val timedCurrent = timed.execution.currentOccurrenceId!!
        val timedEnded = repository.endSequenceEarly(instant(10))
        assertEarlyEnded(timedEnded, timedCurrent, 10_000)
        assertEquals(10_000L, repositoryExecution(timed.currentChild!!.id.value).activeDuration?.toMillis())
        timed.execution.occurrences.drop(1).forEach {
            assertNull(database.activityExecutionDao().getAggregateByOccurrence(it.id.value))
        }

        val noLive =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-no-live-navigation"),
                instant(20),
                instant(20),
                ZoneOffset.UTC,
            )
        val noLiveCurrent = noLive.execution.currentOccurrenceId!!
        val noLiveEnded = repository.endSequenceEarly(instant(30))
        assertEarlyEnded(noLiveEnded, noLiveCurrent, 10_000)
        assertNull(
            requireNotNull(database.activityExecutionDao().getAggregateByOccurrence(noLiveCurrent.value))
                .execution.activeDurationMs,
        )

        val waiting =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-waiting"),
                instant(40),
                instant(40),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(waiting.execution.currentOccurrenceId!!, instant(50))
        val untouched = waiting.execution.occurrences[1]
        val waitingEnded = repository.endSequenceEarly(instant(60))
        assertEquals(SequenceExecutionStatus.ENDED_EARLY, waitingEnded.execution.status)
        assertNull(waitingEnded.execution.currentOccurrenceId)
        assertTrue(waitingEnded.execution.intervals.none { it.endedAt == null })
        assertNull(database.activityExecutionDao().getAggregateByOccurrence(untouched.id.value))
        assertNull(repository.getActiveSession())
    }

    @Test
    fun pausedTimedCurrentEarlyEndRetainsAndFinalizesTheSameDurableChild() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val current = started.execution.occurrences.single { it.id == started.execution.currentOccurrenceId }
        val childId = requireNotNull(started.currentChild).id

        repository.pauseActiveSequence(instant(10))

        val paused = repositorySequence(started.execution.id.value)
        val pausedChild = repositoryExecution(childId.value)
        val childPause = pausedChild.pauses.single()
        assertEquals(SequenceExecutionStatus.PAUSED, paused.status)
        assertEquals(
            started.execution.id,
            database.activeSessionDao().get()?.sequenceExecutionId,
        )
        assertEquals(ActiveSessionState.PAUSED, database.activeSessionDao().get()?.state)
        assertEquals(current.id, paused.currentOccurrenceId)
        assertEquals(RuntimeOccurrenceStatus.CURRENT, paused.occurrences.single { it.id == current.id }.status)
        assertEquals(current.activitySnapshotId, paused.occurrences.single { it.id == current.id }.activitySnapshotId)
        assertEquals(
            current.sourceSequenceSnapshotNodeId,
            paused.occurrences.single { it.id == current.id }.sourceSequenceSnapshotNodeId,
        )
        assertEquals(
            current.repeatSourceSnapshotNodeId,
            paused.occurrences.single { it.id == current.id }.repeatSourceSnapshotNodeId,
        )
        assertEquals(current.repeatIteration, paused.occurrences.single { it.id == current.id }.repeatIteration)
        assertEquals(current.runtimePosition, paused.occurrences.single { it.id == current.id }.runtimePosition)
        assertEquals(ActivityExecutionStatus.PAUSED, pausedChild.status)
        assertEquals(started.execution.id, pausedChild.sequenceExecutionId)
        assertEquals(current.id, pausedChild.sequenceOccurrenceId)
        assertNull(childPause.endedAt)
        assertEquals(instant(10), childPause.startedAt)
        assertEquals(
            SequenceIntervalKind.ACTIVE_STEP,
            paused.intervals.single { it.kind == SequenceIntervalKind.ACTIVE_STEP }.kind,
        )
        assertEquals(instant(10), paused.intervals.single { it.kind == SequenceIntervalKind.ACTIVE_STEP }.endedAt)
        assertEquals(current.id, paused.intervals.single { it.kind == SequenceIntervalKind.ACTIVE_STEP }.occurrenceId)
        val openPause = paused.intervals.single { it.endedAt == null }
        assertEquals(SequenceIntervalKind.EXPLICIT_PAUSE, openPause.kind)
        assertNull(openPause.occurrenceId)
        val later = started.execution.occurrences.filter { it.id != current.id }
        later.forEach { assertNull(database.activityExecutionDao().getAggregateByOccurrence(it.id.value)) }

        repository.endSequenceEarly(instant(30))

        repository = repository(database, 100)
        val durable = requireNotNull(database.sequenceExecutionDao().getAggregate(started.execution.id.value))
        val durableCurrent = durable.occurrences.single { it.id == current.id.value }
        val durableChild = requireNotNull(database.activityExecutionDao().getAggregate(childId.value))
        val durablePause = durableChild.pauses.single()
        val ended = durable.toDomain()
        val endedCurrent = ended.occurrences.single { it.id == current.id }
        val endedChild = repositoryExecution(childId.value)
        val endedPause = endedChild.pauses.single()
        val durations =
            SequenceTimelineCalculator.calculate(
                ended.startedAt,
                requireNotNull(ended.endedAt),
                ended.intervals,
            )
        assertEquals("ENDED_EARLY", durable.execution.status)
        assertEquals(30_000L, durable.execution.endedAtMs)
        assertNull(durable.execution.currentOccurrenceId)
        assertEquals("COMPLETED", durableCurrent.status)
        assertEquals("SEQUENCE_ENDED_EARLY", durableCurrent.completionReason)
        assertEquals(30_000L, durableCurrent.completedAtMs)
        assertTrue(durable.intervals.none { it.endedAtMs == null })
        assertEquals("COMPLETED", durableChild.execution.status)
        assertEquals(30_000L, durableChild.execution.completedAtMs)
        assertEquals(childPause.id.value, durablePause.id)
        assertEquals(10_000L, durablePause.startedAtMs)
        assertEquals(30_000L, durablePause.endedAtMs)
        assertEquals(SequenceExecutionStatus.ENDED_EARLY, ended.status)
        assertEquals(instant(30), ended.endedAt)
        assertNull(ended.currentOccurrenceId)
        assertEquals(RuntimeOccurrenceStatus.COMPLETED, endedCurrent.status)
        assertEquals(OccurrenceCompletionReason.SEQUENCE_ENDED_EARLY, endedCurrent.completionReason)
        assertEquals(instant(30), endedCurrent.completedAt)
        assertEquals(current.activitySnapshotId, endedCurrent.activitySnapshotId)
        assertEquals(current.sourceSequenceSnapshotNodeId, endedCurrent.sourceSequenceSnapshotNodeId)
        assertEquals(current.repeatSourceSnapshotNodeId, endedCurrent.repeatSourceSnapshotNodeId)
        assertEquals(current.repeatIteration, endedCurrent.repeatIteration)
        assertEquals(current.runtimePosition, endedCurrent.runtimePosition)
        assertEquals(ActivityExecutionStatus.COMPLETED, endedChild.status)
        assertEquals(childId, endedChild.id)
        assertEquals(started.execution.id, endedChild.sequenceExecutionId)
        assertEquals(current.id, endedChild.sequenceOccurrenceId)
        assertEquals(instant(30), endedChild.completedAt)
        assertEquals(Duration.ofSeconds(10), endedChild.activeDuration)
        assertEquals(pausedChild.values, endedChild.values)
        assertEquals(childPause.id, endedPause.id)
        assertEquals(instant(10), endedPause.startedAt)
        assertEquals(instant(30), endedPause.endedAt)
        assertTrue(ended.intervals.none { it.endedAt == null })
        assertEquals(durations.active, ended.activeDuration)
        assertEquals(durations.pause, ended.pauseDuration)
        assertEquals(durations.wall, ended.wallDuration)
        assertEquals(Duration.ofSeconds(10), ended.activeDuration)
        assertEquals(Duration.ofSeconds(20), ended.pauseDuration)
        assertEquals(Duration.ofSeconds(30), ended.wallDuration)
        assertNull(database.activeSessionDao().get())
        later.forEach { assertNull(database.activityExecutionDao().getAggregateByOccurrence(it.id.value)) }

        val detail = requireNotNull(HistoryReadRepository(database).getSequenceDetail(started.execution.id))
        val historyCurrent = detail.occurrences.single { it.occurrenceId == current.id }
        assertEquals(SequenceExecutionStatus.ENDED_EARLY, detail.root.status)
        assertEquals(instant(30), detail.root.completedAt)
        assertEquals(current.activitySnapshotId, historyCurrent.activitySnapshotId)
        assertEquals(childId, historyCurrent.child?.executionId)
        assertEquals(instant(30), historyCurrent.child?.completedAt)
        assertEquals(Duration.ofSeconds(10), historyCurrent.child?.activeDuration)
    }

    @Test
    fun pausedTransitionCountdownEarlyEndKeepsTargetUnperformedWithoutChild() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val first = started.execution.currentOccurrenceId!!
        val target = started.execution.occurrences.single { it.id != first }
        repository.completeCurrentSequenceStep(first, instant(5))
        repository.pauseActiveSequence(instant(10))

        val paused = repositorySequence(started.execution.id.value)
        val countdown = paused.intervals.single { it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN }
        val pausedTarget = paused.occurrences.single { it.id == target.id }
        assertEquals(SequenceExecutionStatus.PAUSED, paused.status)
        assertEquals(
            started.execution.id,
            database.activeSessionDao().get()?.sequenceExecutionId,
        )
        assertEquals(ActiveSessionState.PAUSED, database.activeSessionDao().get()?.state)
        assertNull(paused.currentOccurrenceId)
        assertTrue(paused.occurrences.none { it.status == RuntimeOccurrenceStatus.CURRENT })
        assertEquals(target.id, countdown.occurrenceId)
        assertEquals(instant(5), countdown.startedAt)
        assertEquals(instant(10), countdown.endedAt)
        val openPause = paused.intervals.single { it.endedAt == null }
        assertEquals(SequenceIntervalKind.EXPLICIT_PAUSE, openPause.kind)
        assertNull(openPause.occurrenceId)
        assertEquals(RuntimeOccurrenceStatus.NOT_STARTED, pausedTarget.status)
        assertNull(pausedTarget.enteredAt)
        assertNull(pausedTarget.completedAt)
        assertNull(database.activityExecutionDao().getAggregateByOccurrence(target.id.value))

        repository.endSequenceEarly(instant(20))

        repository = repository(database, 100)
        val durable = requireNotNull(database.sequenceExecutionDao().getAggregate(started.execution.id.value))
        val durableTarget = durable.occurrences.single { it.id == target.id.value }
        val ended = durable.toDomain()
        val endedTarget = ended.occurrences.single { it.id == target.id }
        val durations =
            SequenceTimelineCalculator.calculate(
                ended.startedAt,
                requireNotNull(ended.endedAt),
                ended.intervals,
            )
        assertEquals("ENDED_EARLY", durable.execution.status)
        assertEquals(20_000L, durable.execution.endedAtMs)
        assertNull(durable.execution.currentOccurrenceId)
        assertEquals("NOT_STARTED", durableTarget.status)
        assertNull(durableTarget.enteredAtMs)
        assertNull(durableTarget.completedAtMs)
        assertNull(durableTarget.completionReason)
        assertTrue(durable.intervals.none { it.endedAtMs == null })
        assertEquals(SequenceExecutionStatus.ENDED_EARLY, ended.status)
        assertEquals(instant(20), ended.endedAt)
        assertNull(ended.currentOccurrenceId)
        assertTrue(ended.intervals.none { it.endedAt == null })
        assertEquals(target.id, endedTarget.id)
        assertEquals(target.activitySnapshotId, endedTarget.activitySnapshotId)
        assertEquals(target.sourceSequenceSnapshotNodeId, endedTarget.sourceSequenceSnapshotNodeId)
        assertEquals(target.repeatSourceSnapshotNodeId, endedTarget.repeatSourceSnapshotNodeId)
        assertEquals(target.repeatIteration, endedTarget.repeatIteration)
        assertEquals(target.runtimePosition, endedTarget.runtimePosition)
        assertEquals(RuntimeOccurrenceStatus.NOT_STARTED, endedTarget.status)
        assertNull(endedTarget.enteredAt)
        assertNull(endedTarget.completedAt)
        assertNull(endedTarget.completionReason)
        assertNull(database.activityExecutionDao().getAggregateByOccurrence(target.id.value))
        assertEquals(durations.active, ended.activeDuration)
        assertEquals(durations.pause, ended.pauseDuration)
        assertEquals(durations.wall, ended.wallDuration)
        assertEquals(Duration.ofSeconds(5), ended.activeDuration)
        assertEquals(Duration.ofSeconds(15), ended.pauseDuration)
        assertEquals(Duration.ofSeconds(20), ended.wallDuration)
        assertNull(database.activeSessionDao().get())

        val detail = requireNotNull(HistoryReadRepository(database).getSequenceDetail(started.execution.id))
        val historyTarget = detail.occurrences.single { it.occurrenceId == target.id }
        assertEquals(SequenceExecutionStatus.ENDED_EARLY, detail.root.status)
        assertEquals(instant(20), detail.root.completedAt)
        assertEquals(RuntimeOccurrenceStatus.NOT_STARTED, historyTarget.status)
        assertNull(historyTarget.enteredAt)
        assertNull(historyTarget.completedAt)
        assertNull(historyTarget.child)
    }

    @Test
    fun persistedFileReopenCatchesUpTimerCountdownToHistoricalStopwatchStart() {
        withReopenedSequence("sequence-timer", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertEquals(instant(60), restored.occurrences[0].completedAt)
            assertEquals(instant(90), restored.occurrences[1].enteredAt)
            assertEquals(instant(90), repositoryExecution("activity-101").startedAt)
        }
    }

    @Test
    fun persistedFileReopenCatchesUpTimerDirectlyToStopwatch() {
        withReopenedSequence("sequence-timer-direct", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertEquals(instant(60), restored.occurrences[0].completedAt)
            assertEquals(instant(60), restored.occurrences[1].enteredAt)
        }
    }

    @Test
    fun persistedRuntimeAddedTimerTopologyRecoversBatchedMetadataAndReconcilesAfterReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "runtime-added-reopen-${System.nanoTime()}"
        val snapshotQueries = CopyOnWriteArrayList<Pair<String, Int>>()
        database.close()
        context.deleteDatabase(name)
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            seed(database)
            LiveRuntimeTestFixtures(database).sequence(
                "sequence-runtime-recovery",
                listOf("stopwatch", "stopwatch"),
                autoAdvance = false,
                countdownMs = 30_000,
            )
            repository = repository(database)
            val started =
                repository.startSequenceFromSnapshot(
                    SequenceSnapshotId("sequence-runtime-recovery"),
                    instant(0),
                    instant(0),
                    ZoneOffset.UTC,
                )
            repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(10))
            val before = requireNotNull(database.sequenceExecutionDao().getAggregate(started.execution.id.value))
            val runtimeTimer =
                SequenceOccurrenceEntity(
                    "runtime-timer-occurrence",
                    started.execution.id.value,
                    null,
                    "timer",
                    1,
                    null,
                    null,
                    "NOT_STARTED",
                    null,
                    null,
                    null,
                    isRuntimeAdded = true,
                )
            val runtimeNoLive =
                runtimeTimer.copy(
                    id = "runtime-no-live-occurrence",
                    activitySnapshotId = "no-live",
                    runtimePosition = 2,
                )
            val after =
                before.copy(
                    execution = before.execution.copy(updatedAtMs = 20_000),
                    occurrences =
                        listOf(
                            before.occurrences[0],
                            runtimeTimer,
                            runtimeNoLive,
                            before.occurrences[1].copy(runtimePosition = 3),
                        ),
                    intervals =
                        before.intervals.map {
                            if (it.endedAtMs == null) it.copy(endedAtMs = 20_000) else it
                        } +
                            SequenceIntervalEntity(
                                "runtime-countdown",
                                started.execution.id.value,
                                "TRANSITION_COUNTDOWN",
                                20_000,
                                null,
                                runtimeTimer.id,
                            ),
                )
            database.runInTransaction {
                database.sequenceExecutionDao().persistRuntimeDelta(before, after)
                check(database.activeSessionDao().updateState("RUNNING", 20_000) == 1)
            }
            database.close()

            database =
                LifeTracingDatabase
                    .builder(context, name)
                    .allowMainThreadQueries()
                    .setQueryCallback(
                        { sql, arguments -> snapshotQueries += sql to arguments.size },
                        java.util.concurrent.Executor(Runnable::run),
                    ).build()
            repository = repository(database, 100)
            val recovered = repository.getActiveRuntime() as ActiveSequenceRuntime

            assertEquals(
                listOf(
                    started.execution.occurrences[0]
                        .id.value,
                    runtimeTimer.id,
                    runtimeNoLive.id,
                    started.execution.occurrences[1]
                        .id.value,
                ),
                recovered.execution.occurrences
                    .sortedBy { it.runtimePosition }
                    .map { it.id.value },
            )
            assertEquals(
                setOf("stopwatch", "timer", "no-live"),
                recovered.activitySnapshots.keys
                    .map { it.value }
                    .toSet(),
            )
            assertEquals(instant(50), requireNotNull(NextRuntimeDeadlineResolver.resolve(recovered)).at)
            assertTrue(recovered.execution.occurrences[1].isRuntimeAdded)
            assertNull(recovered.execution.occurrences[1].sourceSequenceSnapshotNodeId)
            val batchedSnapshotQueries =
                snapshotQueries.filter { (sql, _) ->
                    sql.lowercase().startsWith("select * from activity_snapshots where id in")
                }
            assertTrue(batchedSnapshotQueries.size in 1..3)
            assertTrue(batchedSnapshotQueries.all { (_, argumentCount) -> argumentCount == 3 })

            repository.reconcileActiveSession(instant(50))
            val startedRuntime = repository.getActiveRuntime() as ActiveSequenceRuntime
            assertEquals(runtimeTimer.id, startedRuntime.execution.currentOccurrenceId?.value)
            assertNotNull(startedRuntime.currentChild)
            database.close()

            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = repository(database, 200)
            val restoredCurrent = repository.getActiveRuntime() as ActiveSequenceRuntime
            assertEquals(runtimeTimer.id, restoredCurrent.execution.currentOccurrenceId?.value)
            assertNotNull(restoredCurrent.currentChild)

            repository.reconcileActiveSession(instant(110))
            val completedRuntime = repositorySequence(started.execution.id.value)
            assertEquals(
                instant(110),
                completedRuntime.occurrences.single { it.id.value == runtimeTimer.id }.completedAt,
            )
            assertEquals("WAITING_NEXT", repository.getActiveSession()?.state?.name)
        } finally {
            database.close()
            context.deleteDatabase(name)
            database = inMemoryDatabase()
        }
    }

    @Test
    fun persistedFileReopenCatchesUpTwoTimersAndCompletesSequence() {
        withReopenedSequence("sequence-timers", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertEquals(instant(60), restored.occurrences[0].completedAt)
            assertEquals(instant(90), restored.occurrences[1].enteredAt)
            assertEquals(instant(150), restored.occurrences[1].completedAt)
            assertEquals(instant(150), restored.endedAt)
            assertNull(repository.getActiveSession())
        }
    }

    @Test
    fun longCatchUpReturnsAllTimerAndCountdownFeedbackInSemanticOrder() {
        repository.startSequenceFromSnapshot(
            SequenceSnapshotId("sequence-many-timers"),
            instant(0),
            instant(0),
            ZoneOffset.UTC,
        )

        val events = repository.reconcileActiveSession(instant(10_000)).appliedEvents
        val expected =
            buildList {
                repeat(12) { index ->
                    if (index > 0) {
                        add(RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN to instant(61L * index))
                    }
                    add(RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO to instant(60L + 61L * index))
                }
            }

        assertEquals(expected, events.map { it.deadline.kind to it.deadline.at })
        assertNull(repository.getActiveSession())
    }

    @Test
    fun persistedFileReopenStopsCatchUpAtNoLiveStep() {
        withReopenedSequence("sequence-timer-no-live", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertEquals(instant(60), restored.occurrences[1].enteredAt)
            assertEquals(RuntimeOccurrenceStatus.CURRENT, restored.occurrences[1].status)
            assertNull(database.activityExecutionDao().getAggregateByOccurrence(restored.occurrences[1].id.value))
        }
    }

    @Test
    fun persistedFileReopenStopsAtWaitingNextWhenAutoAdvanceIsOff() {
        withReopenedSequence("sequence-waiting", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertNull(restored.currentOccurrenceId)
            assertEquals("WAITING_NEXT", repository.getActiveSession()?.state?.name)
            assertEquals(instant(60), restored.intervals.single { it.endedAt == null }.startedAt)
        }
    }

    @Test
    fun persistedFileReopenLeavesOvertimeTimerCurrent() {
        withReopenedSequence("sequence-overtime", 600) { executionId ->
            val restored = repositorySequence(executionId.value)
            assertEquals(RuntimeOccurrenceStatus.CURRENT, restored.occurrences[0].status)
            assertNull(restored.occurrences[0].completedAt)
            assertEquals(restored.occurrences[0].id, restored.currentOccurrenceId)
        }
    }

    @Test
    fun intervalIdCollisionRollsBackWithoutReparentingHistory() {
        val intervalIds = ArrayDeque(listOf("collision-interval", "b-initial", "collision-interval"))
        repository = repository(database, intervalIds = { SequenceIntervalId(intervalIds.removeFirst()) })
        val historical =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-one-timer"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(historical.execution.currentOccurrenceId!!, instant(10))
        val historicalInterval = database.sequenceExecutionDao().getIntervals(historical.execution.id.value).single()
        val active =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(20),
                instant(20),
                ZoneOffset.UTC,
            )
        val before = repositorySequence(active.execution.id.value)
        val sessionBefore = database.activeSessionDao().get()

        assertThrows(RuntimeException::class.java) {
            repository.completeCurrentSequenceStep(active.execution.currentOccurrenceId!!, instant(25))
        }

        assertEquals(
            historicalInterval,
            database.sequenceExecutionDao().getIntervals(historical.execution.id.value).single(),
        )
        assertEquals(before, repositorySequence(active.execution.id.value))
        assertEquals(sessionBefore, database.activeSessionDao().get())
    }

    @Test
    fun childExecutionIdCollisionRollsBackWithoutConvertingStandaloneHistory() {
        LiveRuntimeTestFixtures(database).standaloneExecution(id = "collision-child")
        val childIds = ArrayDeque(listOf("initial-child", "collision-child"))
        repository = repository(database, childIds = { ActivityExecutionId(childIds.removeFirst()) })
        val unrelated = database.activityExecutionDao().getAggregate("collision-child")
        val active =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-timer-direct"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val before = repositorySequence(active.execution.id.value)
        val sessionBefore = database.activeSessionDao().get()

        assertThrows(RuntimeException::class.java) {
            repository.completeCurrentSequenceStep(active.execution.currentOccurrenceId!!, instant(10))
        }

        assertEquals(unrelated, database.activityExecutionDao().getAggregate("collision-child"))
        assertEquals(before, repositorySequence(active.execution.id.value))
        assertEquals(sessionBefore, database.activeSessionDao().get())
    }

    @Test
    fun childPauseIdCollisionRollsBackWithoutReparentingForeignPause() {
        LiveRuntimeTestFixtures(database).standaloneExecution(
            id = "foreign-paused",
            status = "PAUSED",
            pauseId = "collision-pause",
        )
        repository = repository(database, pauseIds = { ActivityExecutionPauseId("collision-pause") })
        val foreign = database.activityExecutionDao().getAggregate("foreign-paused")
        val active =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val before = repositorySequence(active.execution.id.value)
        val childBefore =
            database.activityExecutionDao().getAggregateByOccurrence(
                active.execution.currentOccurrenceId!!.value,
            )
        val sessionBefore = database.activeSessionDao().get()

        assertThrows(RuntimeException::class.java) { repository.pauseActiveSequence(instant(10)) }

        assertEquals(foreign, database.activityExecutionDao().getAggregate("foreign-paused"))
        assertEquals(before, repositorySequence(active.execution.id.value))
        assertEquals(
            childBefore,
            database.activityExecutionDao().getAggregateByOccurrence(active.execution.currentOccurrenceId!!.value),
        )
        assertEquals(sessionBefore, database.activeSessionDao().get())
    }

    @Test
    fun pauseAfterDueTimerCommitsWaitingNextThenReportsRejection() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-waiting"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )

        assertThrows(IllegalArgumentException::class.java) { repository.pauseActiveSequence(instant(70)) }

        val persisted = repositorySequence(started.execution.id.value)
        assertEquals(instant(60), persisted.occurrences[0].completedAt)
        assertEquals(RuntimeOccurrenceStatus.COMPLETED, persisted.occurrences[0].status)
        assertEquals(SequenceExecutionStatus.RUNNING, persisted.status)
        assertNull(persisted.currentOccurrenceId)
        assertEquals(SequenceIntervalKind.IMPLICIT_IDLE, persisted.intervals.single { it.endedAt == null }.kind)
        assertEquals(instant(60), persisted.intervals.single { it.endedAt == null }.startedAt)
        assertEquals("WAITING_NEXT", repository.getActiveSession()?.state?.name)
        assertEquals(0, persisted.intervals.count { it.kind == SequenceIntervalKind.EXPLICIT_PAUSE })
        assertEquals(0, database.activityExecutionDao().getPauses("activity-1").count { it.endedAtMs == null })

        repository.reconcileActiveSession(instant(70))
        assertEquals(persisted, repositorySequence(started.execution.id.value))
    }

    @Test
    fun oneStepDeltaUpdatesOnlyTheRowsThatChanged() {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE mutation_audit (table_name TEXT NOT NULL)")
        sql.execSQL(
            "CREATE TRIGGER audit_occurrence_update AFTER UPDATE ON sequence_occurrences " +
                "BEGIN INSERT INTO mutation_audit VALUES ('occurrence'); END",
        )
        sql.execSQL(
            "CREATE TRIGGER audit_interval_update AFTER UPDATE ON sequence_intervals " +
                "BEGIN INSERT INTO mutation_audit VALUES ('interval'); END",
        )
        sql.execSQL(
            "CREATE TRIGGER audit_sequence_update AFTER UPDATE ON sequence_executions " +
                "BEGIN INSERT INTO mutation_audit VALUES ('sequence'); END",
        )
        sql.execSQL(
            "CREATE TRIGGER audit_child_update AFTER UPDATE ON activity_executions " +
                "BEGIN INSERT INTO mutation_audit VALUES ('child'); END",
        )
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        sql.execSQL("DELETE FROM mutation_audit")

        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(5))

        assertEquals(1, auditCount("occurrence"))
        assertEquals(1, auditCount("interval"))
        assertEquals(1, auditCount("sequence"))
        assertEquals(1, auditCount("child"))
        assertNotNull(repository.getActiveSession())
    }

    private fun inMemoryDatabase() =
        LifeTracingDatabase
            .inMemoryBuilder(ApplicationProvider.getApplicationContext())
            .allowMainThreadQueries()
            .build()

    private fun seed(database: LifeTracingDatabase) {
        val fixtures = LiveRuntimeTestFixtures(database)
        fixtures.seedSeries()
        fixtures.activity("stopwatch", "STOPWATCH")
        fixtures.activity("timer", "TIMER", 60_000)
        fixtures.activity("timer-overtime", "TIMER", 60_000, "OVERTIME")
        fixtures.activity("no-live", "NO_LIVE_TRACKING")
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    "defaults",
                    "defaults",
                    null,
                    "TIMER",
                    60_000,
                    null,
                    null,
                    "activity-series",
                    false,
                    0,
                ),
                ActivitySnapshotSettingsEntity("defaults"),
                fields =
                    listOf(
                        ActivitySnapshotFieldEntity(
                            "defaults-number",
                            "defaults",
                            null,
                            0,
                            "Number",
                            null,
                            "NUMBER",
                            null,
                            0,
                            5,
                            null,
                            null,
                            false,
                        ),
                    ),
            ),
        )
        fixtures.sequence("sequence", listOf("stopwatch", "stopwatch"), countdownMs = 10_000)
        fixtures.sequence("sequence-navigation", listOf("stopwatch", "stopwatch", "timer"))
        fixtures.sequence("sequence-no-live-navigation", listOf("no-live", "stopwatch"))
        fixtures.sequence("sequence-timer-navigation", listOf("timer", "stopwatch", "stopwatch"))
        fixtures.sequence("sequence-four", List(4) { "stopwatch" })
        fixtures.sequence("sequence-defaults", listOf("defaults", "stopwatch"), autoAdvance = false)
        fixtures.sequence("sequence-timer", listOf("timer", "stopwatch"), countdownMs = 30_000)
        fixtures.sequence("sequence-timer-direct", listOf("timer", "stopwatch"))
        fixtures.sequence("sequence-timers", listOf("timer", "timer"), countdownMs = 30_000)
        fixtures.sequence("sequence-timer-no-live", listOf("timer", "no-live"))
        fixtures.sequence("sequence-waiting", listOf("timer", "stopwatch"), autoAdvance = false)
        fixtures.sequence(
            "sequence-waiting-pause",
            listOf("timer", "stopwatch"),
            autoAdvance = false,
            noLiveAccounting = "PAUSE",
        )
        fixtures.sequence("sequence-overtime", listOf("timer-overtime", "stopwatch"))
        fixtures.sequence(
            "sequence-step-overtime",
            listOf("timer", "stopwatch"),
            autoAdvance = false,
            timerZeroOverrides = mapOf(0 to "OVERTIME"),
        )
        fixtures.repeatSequence("sequence-repeat-overtime", "timer", timerZeroBehavior = "OVERTIME")
        fixtures.sequence("sequence-many-timers", List(12) { "timer" }, countdownMs = 1_000)
        fixtures.sequence("sequence-empty", emptyList())
        fixtures.sequence("sequence-one-timer", listOf("timer"))
        fixtures.sequence("sequence-no-live-active", listOf("no-live"))
        fixtures.sequence("sequence-no-live-pause", listOf("no-live"), noLiveAccounting = "PAUSE")
    }

    private fun withReopenedSequence(
        snapshotId: String,
        reconcileAtSeconds: Long,
        verify: (SequenceExecutionId) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "live-runtime-reopen-${System.nanoTime()}"
        database.close()
        context.deleteDatabase(name)
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            seed(database)
            repository = repository(database)
            val started =
                repository.startSequenceFromSnapshot(
                    SequenceSnapshotId(snapshotId),
                    instant(0),
                    instant(0),
                    ZoneOffset.UTC,
                )
            database.close()

            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = repository(database, 100)
            repository.reconcileActiveSession(instant(reconcileAtSeconds))
            verify(started.execution.id)
        } finally {
            database.close()
            context.deleteDatabase(name)
            database = inMemoryDatabase()
        }
    }

    private fun repository(
        database: LifeTracingDatabase,
        offset: Int = 0,
        childIds: (() -> ActivityExecutionId)? = null,
        pauseIds: (() -> ActivityExecutionPauseId)? = null,
        intervalIds: (() -> SequenceIntervalId)? = null,
    ): LiveSessionRepository {
        var activity = offset
        var pause = offset
        var sequence = offset
        var occurrence = offset
        var interval = offset
        var snapshot = offset
        var field = offset
        var option = offset
        return LiveSessionRepository(
            database,
            childIds ?: { ActivityExecutionId("activity-${++activity}") },
            pauseIds ?: { ActivityExecutionPauseId("pause-${++pause}") },
            { SequenceExecutionId("sequence-${++sequence}") },
            { SequenceOccurrenceId("occurrence-${++occurrence}") },
            intervalIds ?: { SequenceIntervalId("interval-${++interval}") },
            ActivitySnapshotFactory(
                { ActivitySnapshotId("runtime-snapshot-${++snapshot}") },
                { ActivitySnapshotFieldId("runtime-field-${++field}") },
                { ActivitySnapshotCategoryOptionId("runtime-option-${++option}") },
            ),
        )
    }

    private fun runtimeTemplate(
        id: String,
        mode: TimeTrackingMode,
        targetMs: Long? = null,
        revision: Long = 1,
    ) {
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    id,
                    id,
                    "Template note",
                    mode.name,
                    targetMs,
                    "activity-series",
                    revision,
                    0,
                    0,
                    null,
                    null,
                ),
                ActivityTemplateSettingsEntity(id),
                fields =
                    listOf(
                        ActivityTemplateFieldEntity(
                            "$id-number",
                            id,
                            0,
                            "Number",
                            "NUMBER",
                            null,
                            0,
                            5,
                            null,
                            null,
                            true,
                            0,
                            0,
                            null,
                        ),
                        ActivityTemplateFieldEntity(
                            "$id-category",
                            id,
                            1,
                            "Category",
                            "CATEGORY",
                            null,
                            null,
                            null,
                            "$id-option-a",
                            null,
                            false,
                            0,
                            0,
                            null,
                        ),
                    ),
                options =
                    listOf(
                        ActivityTemplateCategoryOptionEntity(
                            "$id-option-a",
                            "$id-category",
                            0,
                            "A",
                        ),
                    ),
                userState = ActivityTemplateUserStateEntity(id, null, null),
            ),
        )
    }

    private fun oneOff(mode: TimeTrackingMode) =
        ActivitySnapshotDraft(
            "Runtime one-off",
            "One-off note",
            mode,
            if (mode == TimeTrackingMode.TIMER) Duration.ofSeconds(60) else null,
            fields =
                listOf(
                    ActivitySnapshotFieldDraft(
                        DraftIdentity.New("number"),
                        null,
                        0,
                        "Number",
                        type = CustomFieldType.NUMBER,
                        defaultNumberScaled = 5,
                    ),
                ),
        )

    private fun repositoryExecution(id: String) =
        requireNotNull(database.activityExecutionDao().getAggregate(id)).toDomain()

    private fun repositorySequence(id: String) =
        requireNotNull(database.sequenceExecutionDao().getAggregate(id)).toDomain()

    private fun activeSequence() = repository.getActiveRuntime() as ActiveSequenceRuntime

    private fun assertEarlyEnded(
        state: com.alexandr5476.lifetracing.domain.SequenceRuntimeState,
        occurrenceId: SequenceOccurrenceId,
        expectedDurationMs: Long,
    ) {
        assertEquals(SequenceExecutionStatus.ENDED_EARLY, state.execution.status)
        assertEquals(
            OccurrenceCompletionReason.SEQUENCE_ENDED_EARLY,
            state.execution.occurrences
                .single { it.id == occurrenceId }
                .completionReason,
        )
        assertEquals(
            ActivityExecutionStatus.COMPLETED,
            requireNotNull(database.activityExecutionDao().getAggregateByOccurrence(occurrenceId.value))
                .toDomain()
                .status,
        )
        assertNull(state.execution.currentOccurrenceId)
        assertEquals(expectedDurationMs, state.execution.activeDuration?.toMillis())
        assertEquals(0L, state.execution.pauseDuration?.toMillis())
        assertEquals(expectedDurationMs, state.execution.wallDuration?.toMillis())
        assertTrue(state.execution.intervals.none { it.endedAt == null })
        assertNull(repository.getActiveSession())
    }

    private fun auditCount(table: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM mutation_audit WHERE table_name = ?", arrayOf(table))
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }

    private fun count(table: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table")
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }

    private fun instant(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}
