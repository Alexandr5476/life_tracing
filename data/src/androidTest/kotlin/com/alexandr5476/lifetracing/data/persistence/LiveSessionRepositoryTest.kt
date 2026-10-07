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
import com.alexandr5476.lifetracing.domain.ActivityExecutionValueOverride
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequenceRead
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline
import com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceCardinalityPolicy
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceTimelineCalculator
import com.alexandr5476.lifetracing.domain.StaleSequenceRouteException
import com.alexandr5476.lifetracing.domain.StaleSequenceTargetException
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TransitionCountdownProgressResolver
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
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
import java.time.LocalDate
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
    fun exactExpandedRouteIdentityNeverMutatesAReplacementSequence() {
        val first =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-navigation"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        assertThrows(StaleSequenceRouteException::class.java) {
            repository.pauseActiveSequence(SequenceExecutionId("another"), instant(1))
        }
        assertEquals(first.execution, activeSequence().execution)

        repository.endSequenceEarly(first.execution.id, instant(2))
        val second =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-navigation"),
                instant(3),
                instant(3),
                ZoneOffset.UTC,
            )
        assertThrows(StaleSequenceRouteException::class.java) {
            repository.endSequenceEarly(first.execution.id, instant(4))
        }

        assertEquals(second.execution.id, activeSequence().execution.id)
        assertEquals(SequenceExecutionStatus.RUNNING, activeSequence().execution.status)
    }

    @Test
    fun exactCurrentValueCommandsPreserveZeroMissingAndDeadlineStaleness() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-defaults"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val occurrenceId = requireNotNull(started.execution.currentOccurrenceId)
        val fieldId = ActivitySnapshotFieldId("defaults-number")
        repository.updateCurrentSequenceStepValues(
            started.execution.id,
            occurrenceId,
            listOf(ActivityExecutionValueOverride(fieldId, null)),
            instant(10),
        )
        assertTrue(requireNotNull(activeSequence().currentChild).values.isEmpty())
        repository.updateCurrentSequenceStepValues(
            started.execution.id,
            occurrenceId,
            listOf(ActivityExecutionValueOverride(fieldId, NumberExecutionValue(fieldId, 0))),
            instant(20),
        )
        assertEquals(
            0L,
            (requireNotNull(activeSequence().currentChild).values.single() as NumberExecutionValue).scaledValue,
        )

        assertThrows(StaleSequenceTargetException::class.java) {
            repository.updateCurrentSequenceStepValues(
                started.execution.id,
                occurrenceId,
                emptyList(),
                instant(60),
            )
        }
        assertEquals(RuntimeOccurrenceStatus.NOT_STARTED, activeSequence().execution.occurrences[1].status)
        assertNull(activeSequence().currentChild)
        assertEquals(
            0L,
            (repositoryExecution(requireNotNull(started.currentChild).id.value).values.single() as NumberExecutionValue)
                .scaledValue,
        )
    }

    @Test
    fun noLiveCompletionAppliesTransientOverridesAtomicallyWithChildCreation() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-defaults"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        repository.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(10))
        val added =
            repository.runtimeAdd(
                started.execution.id,
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.NO_LIVE_TRACKING)),
                RuntimeInsertionPlacement.START_NOW,
                instant(20),
            )
        val occurrenceId = requireNotNull(added.execution.currentOccurrenceId)
        val expanded = repository.getExpandedSequence(started.execution.id) as ExpandedLiveSequenceRead.Active
        val fieldId =
            expanded.value.occurrences
                .single { it.occurrence.id == occurrenceId }
                .activity.fields
                .single()
                .id

        repository.completeCurrentSequenceStep(
            started.execution.id,
            occurrenceId,
            listOf(ActivityExecutionValueOverride(fieldId, NumberExecutionValue(fieldId, 0))),
            instant(30),
        )

        val child =
            requireNotNull(
                database.activityExecutionDao().getAggregateByOccurrence(occurrenceId.value),
            ).toDomain()
        assertEquals(0L, (child.values.single() as NumberExecutionValue).scaledValue)
        assertEquals(ActivityExecutionStatus.COMPLETED, child.status)
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
    @Suppress("LongMethod") // The file-reopen fixture and frozen-source assertions form one recovery scenario.
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
            val frozenRuntime = activeSequence()
            database.openHelper.writableDatabase.execSQL(
                "UPDATE activity_templates SET name = 'Changed source', timer_target_ms = 120000, revision = 10 " +
                    "WHERE id = 'runtime-template'",
            )
            database.close()

            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = repository(database, 100)
            val recovered = activeSequence()
            assertEquals(frozenRuntime, recovered)
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
            assertEquals(OccurrenceCompletionReason.NATURAL_TIMER_END, restored.occurrences[0].completionReason)
            assertEquals(
                instant(90),
                restored.intervals.single { it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN }.endedAt,
            )
            assertEquals(2, count("activity_executions"))
        }
    }

    @Test
    fun runningStopwatchReopenPreservesCurrentChildAndUsesFreshDisplayAnchor() {
        withFileRuntime { name ->
            LiveRuntimeTestFixtures(database).repeatSequence("sequence-stopwatch-recovery", "stopwatch")
            startRecoverySequence("sequence-stopwatch-recovery")
            val before = activeSequence()
            reopenRuntime(name)

            val restored = activeSequence()
            assertEquals(before, restored)
            assertNull(NextRuntimeDeadlineResolver.resolve(restored))
            assertTrue(repository.reconcileActiveSession(instant(600)).appliedEvents.isEmpty())
            assertEquals(before, activeSequence())
            val display =
                RuntimeDisplayBaseline.capture(restored, WallMonotonicAnchor(instant(600), 7_000), 7_000)
            assertEquals(Duration.ofSeconds(600), display.currentStepStopwatchElapsed(7_000))
            assertEquals(Duration.ofSeconds(605), display.currentStepStopwatchElapsed(12_000))
            assertEquals(1, count("activity_executions"))
        }
    }

    @Test
    fun pausedTimerReopenPreservesPausesValuesAndResumesOnlyRemainingActiveTime() {
        withFileRuntime { name ->
            val started = startRecoverySequence("sequence-defaults")
            val field = ActivitySnapshotFieldId("defaults-number")
            repository.updateCurrentSequenceStepValues(
                started.execution.id,
                started.execution.currentOccurrenceId!!,
                listOf(ActivityExecutionValueOverride(field, NumberExecutionValue(field, 0))),
                instant(5),
            )
            repository.pauseActiveSequence(instant(10))
            val before = activeSequence()
            val childBefore = requireNotNull(before.currentChild)
            val childPause = childBefore.pauses.single()
            val sequencePause = before.execution.intervals.single { it.endedAt == null }
            reopenRuntime(name)

            assertEquals(before, activeSequence())
            assertTrue(repository.reconcileActiveSession(instant(600)).appliedEvents.isEmpty())
            assertEquals(before, activeSequence())
            assertNull(NextRuntimeDeadlineResolver.resolve(activeSequence()))
            val display =
                RuntimeDisplayBaseline.capture(activeSequence(), WallMonotonicAnchor(instant(600), 0), 0)
            assertEquals(Duration.ofSeconds(10), display.activeElapsed(100_000))
            assertEquals(Duration.ofSeconds(50), display.timerRemaining(100_000))

            repository.resumeActiveSequence(instant(600))
            val resumed = activeSequence()
            assertEquals(childBefore.id, resumed.currentChild?.id)
            assertEquals(childPause.copy(endedAt = instant(600)), resumed.currentChild?.pauses?.single())
            assertEquals(
                sequencePause.copy(endedAt = instant(600)),
                resumed.execution.intervals.single { it.id == sequencePause.id },
            )
            assertEquals(instant(650), NextRuntimeDeadlineResolver.resolve(resumed)?.at)
            assertTrue(repository.reconcileActiveSession(instant(649)).appliedEvents.isEmpty())
            repository.reconcileActiveSession(instant(650))
            val child = repositoryExecution(childBefore.id.value)
            assertEquals(Duration.ofSeconds(60), child.activeDuration)
            assertEquals(childBefore.values, child.values)
            assertEquals(instant(650), child.completedAt)
            assertEquals(ActiveSessionState.WAITING_NEXT, repository.getActiveSession()?.state)
            reopenRuntime(name, 200)
            assertEquals(child, repositoryExecution(child.id.value))
            assertEquals(before.execution.id, activeSequence().execution.id)
            assertEquals(1, count("activity_executions"))
        }
    }

    @Test
    fun persistedRunningCountdownReopensBeforeDeadlineWithOriginalIntervalAndTarget() {
        withFileRuntime { name ->
            startRecoverySequence("sequence-timer")
            repository.reconcileActiveSession(instant(60))
            val before = activeSequence()
            val countdown = before.execution.intervals.single { it.endedAt == null }
            reopenRuntime(name)

            assertEquals(before, activeSequence())
            assertTrue(repository.reconcileActiveSession(instant(80)).appliedEvents.isEmpty())
            assertEquals(before, activeSequence())
            val progress = requireNotNull(TransitionCountdownProgressResolver.running(activeSequence()))
            assertEquals(countdown.occurrenceId, progress.targetOccurrenceId)
            assertEquals(
                Duration.ZERO,
                TransitionCountdownProgressResolver.closedDuration(before.execution, progress.targetOccurrenceId),
            )
            assertEquals(Duration.ofSeconds(30), progress.remaining)
            assertEquals(Duration.ofSeconds(20), Duration.between(countdown.startedAt, instant(80)))
            assertEquals(instant(90), progress.deadlineAt)
            val display =
                RuntimeDisplayBaseline.capture(activeSequence(), WallMonotonicAnchor(instant(80), 0), 0)
            assertEquals(Duration.ofSeconds(10), display.transitionCountdownRemaining(0))
            assertEquals(instant(90), NextRuntimeDeadlineResolver.resolve(activeSequence())?.at)
            assertNull(activeSequence().currentChild)

            repository.reconcileActiveSession(instant(90))
            val entered = activeSequence()
            assertEquals(countdown.occurrenceId, entered.execution.currentOccurrenceId)
            assertEquals(instant(90), entered.currentChild?.startedAt)
            assertEquals(
                countdown.copy(endedAt = instant(90)),
                entered.execution.intervals.single { it.id == countdown.id },
            )
        }
    }

    @Test
    fun pausedCountdownReopenRetainsConsumedDurationAndStartsTargetAtResumedDeadlineOnce() {
        withFileRuntime { name ->
            startRecoverySequence("sequence-timer")
            repository.reconcileActiveSession(instant(60))
            repository.pauseActiveSequence(instant(70))
            val before = activeSequence()
            val target = requireNotNull(before.transitionCountdownTargetId)
            reopenRuntime(name)

            assertEquals(before, activeSequence())
            assertTrue(repository.reconcileActiveSession(instant(600)).appliedEvents.isEmpty())
            assertEquals(before, activeSequence())
            val progress = requireNotNull(TransitionCountdownProgressResolver.paused(activeSequence()))
            assertEquals(target, progress.targetOccurrenceId)
            assertEquals(
                Duration.ofSeconds(10),
                TransitionCountdownProgressResolver.closedDuration(before.execution, target),
            )
            assertEquals(Duration.ofSeconds(20), progress.remaining)
            val display =
                RuntimeDisplayBaseline.capture(activeSequence(), WallMonotonicAnchor(instant(600), 0), 0)
            assertEquals(Duration.ofSeconds(20), display.transitionCountdownRemaining(100_000))
            assertNull(progress.deadlineAt)
            assertNull(NextRuntimeDeadlineResolver.resolve(activeSequence()))
            assertEquals(1, count("activity_executions"))

            repository.resumeActiveSequence(instant(600))
            assertEquals(instant(620), NextRuntimeDeadlineResolver.resolve(activeSequence())?.at)
            assertTrue(repository.reconcileActiveSession(instant(619)).appliedEvents.isEmpty())
            repository.reconcileActiveSession(instant(1_000))
            val entered = activeSequence()
            assertEquals(target, entered.execution.currentOccurrenceId)
            assertEquals(
                instant(620),
                entered.execution
                    .occurrences
                    .single { it.id == target }
                    .enteredAt,
            )
            assertEquals(instant(620), entered.currentChild?.startedAt)
            assertEquals(
                Duration.ofSeconds(30),
                TransitionCountdownProgressResolver.closedDuration(entered.execution, target),
            )
            assertEquals(
                before.execution.intervals.map { it.id },
                entered.execution
                    .intervals
                    .take(before.execution.intervals.size)
                    .map { it.id },
            )
            reopenRuntime(name, 200)
            assertEquals(entered, activeSequence())
            assertTrue(repository.reconcileActiveSession(instant(2_000)).appliedEvents.isEmpty())
            assertEquals(entered, activeSequence())
            assertEquals(2, count("activity_executions"))
        }
    }

    @Test
    fun planLinkedTerminalCatchUpReopensIntoCanonicalReadersWithoutDoubleCounting() {
        verifyTerminalPlanRecovery(forceFailure = false)
    }

    @Test
    fun lateTerminalPlanFailureRollsBackDurablyAndRetryCommitsExactlyOnce() {
        verifyTerminalPlanRecovery(forceFailure = true)
    }

    @Test
    @Suppress("LongMethod") // Keep the bind-boundary fixture and its complete query accounting together.
    fun activeLoaderChunksDistinctDerivedSnapshotsAndNeverScansUnfinishedHistory() {
        withFileRuntime { name ->
            val fixtures = LiveRuntimeTestFixtures(database)
            fixtures.standaloneExecution(id = "unpointed-activity")
            fixtures.sequenceExecution(id = "unpointed-sequence")
            val started = startRecoverySequence("sequence")
            val before = requireNotNull(database.sequenceExecutionDao().getAggregate(started.execution.id.value))
            val derivedIds = List(1_001) { "derived-$it" }
            database.runInTransaction {
                derivedIds.forEach { fixtures.activity(it, "STOPWATCH") }
                val source = before.occurrences.last()
                val added =
                    derivedIds.mapIndexed { index, id ->
                        source.copy(
                            id = "derived-occurrence-$index",
                            sourceSequenceSnapshotNodeId = null,
                            activitySnapshotId = id,
                            runtimePosition = index + before.occurrences.size,
                            isRuntimeAdded = true,
                        )
                    }
                database.sequenceExecutionDao().persistRuntimeDelta(
                    before,
                    before.copy(occurrences = before.occurrences + added),
                )
            }
            val persisted = requireNotNull(database.sequenceExecutionDao().getAggregate(started.execution.id.value))
            val queries = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
            reopenRuntime(name, queries = queries)
            // Opening and Room invalidation bookkeeping are outside aggregate query accounting.
            database.openHelper.writableDatabase
            queries.clear()
            val recovered = activeSequence()
            val reads =
                queries.filter {
                    it.first.lowercase().startsWith("select") &&
                        "room_table_modification_log" !in it.first.lowercase()
                }
            val expectedIds = (derivedIds + "stopwatch").toSet()
            assertEquals(
                expectedIds,
                recovered.activitySnapshots.keys
                    .map { it.value }
                    .toSet(),
            )
            assertEquals(persisted.toDomain(), recovered.execution)
            assertEquals(started.currentChild?.id, recovered.currentChild?.id)
            val modes =
                reads.filter {
                    it.first.lowercase().startsWith("select id, time_tracking_mode from activity_snapshots")
                }
            val hydration = reads.filter { it.first.lowercase().startsWith("select * from activity_snapshots") }
            listOf(modes, hydration).forEach { batch ->
                assertTrue(batch.size in 2..12)
                assertTrue(batch.all { it.second.size in 1..900 && " in (" in it.first.lowercase() })
                assertEquals(expectedIds, batch.flatMap { it.second }.toSet())
                assertTrue(batch.any { it.second.size == 900 })
            }
            listOf("activity_snapshot_settings", "activity_snapshot_fields", "activity_snapshot_category_options")
                .forEach { table ->
                    val batch = reads.filter { "from $table " in it.first.lowercase() }
                    assertTrue(batch.size in 2..12)
                    assertTrue(batch.all { " in (" in it.first.lowercase() && it.second.size in 1..900 })
                }
            listOf("sequence_executions", "sequence_intervals")
                .forEach { table ->
                    val owned = reads.filter { "from $table " in it.first.lowercase() }
                    assertTrue(owned.isNotEmpty())
                    assertTrue(owned.all { it.second == listOf(started.execution.id.value) })
                }
            val occurrenceReads = reads.filter { "from sequence_occurrences " in it.first.lowercase() }
            assertTrue(occurrenceReads.size in 1..8)
            occurrenceReads.forEach { (sql, arguments) ->
                val expected =
                    if (sql.lowercase().startsWith("select *")) {
                        started.execution.id.value
                    } else {
                        started.execution.currentOccurrenceId!!.value
                    }
                assertEquals(listOf(expected), arguments)
            }
            val childReads = reads.filter { "from activity_executions " in it.first.lowercase() }
            assertTrue(childReads.size in 1..4)
            assertTrue(childReads.all { it.second == listOf(started.execution.currentOccurrenceId!!.value) })
            // Fixed validation passes plus two chunks per snapshot-owned table, independent of occurrence count.
            assertTrue(reads.size < 140)
            assertTrue(reads.none { "from activity_templates" in it.first.lowercase() })
            assertTrue(reads.none { "from sequence_templates" in it.first.lowercase() })
            assertTrue(repository.reconcileActiveSession(instant(600)).appliedEvents.isEmpty())
            database.activeSessionDao().clear()
            reopenRuntime(name, 200)
            assertNull(repository.getActiveRuntime())
            assertTrue(repository.reconcileActiveSession(instant(1_000)).appliedEvents.isEmpty())
            assertNotNull(database.sequenceExecutionDao().getById("unpointed-sequence"))
            assertNotNull(database.activityExecutionDao().getById("unpointed-activity"))
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
    @Suppress("LongMethod") // One limit-sized file-reopen scenario verifies the complete recovered aggregate.
    fun fileReopenCatchesUpLimitSizedRepeatWithoutChangingIdentityOrLogicalTime() {
        withFileRuntime { name ->
            val count = RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES
            val childCount = count / 2
            val snapshotId = "limit-repeat"
            val repeatId = "$snapshotId-repeat"
            val fixtures = LiveRuntimeTestFixtures(database)
            fixtures.activity("millisecond-timer", "TIMER", targetMs = 1)
            database.activitySnapshotDao().insertFields(
                listOf(
                    ActivitySnapshotFieldEntity(
                        id = "limit-number",
                        snapshotId = "millisecond-timer",
                        sourceFieldId = null,
                        position = 0,
                        nameAtCreation = "Number",
                        localNameOverride = null,
                        fieldType = "NUMBER",
                        unit = null,
                        displayPrecision = 0,
                        defaultNumberScaled = 7,
                        defaultCategoryOptionId = null,
                        defaultText = null,
                    ),
                    ActivitySnapshotFieldEntity(
                        id = "limit-category",
                        snapshotId = "millisecond-timer",
                        sourceFieldId = null,
                        position = 1,
                        nameAtCreation = "Category",
                        localNameOverride = null,
                        fieldType = "CATEGORY",
                        unit = null,
                        displayPrecision = null,
                        defaultNumberScaled = null,
                        defaultCategoryOptionId = "limit-option",
                        defaultText = null,
                    ),
                ),
            )
            database.activitySnapshotDao().insertOptions(
                listOf(ActivitySnapshotCategoryOptionEntity("limit-option", "limit-category", null, 0, "Option", null)),
            )
            database.sequenceSnapshotDao().insertAggregate(
                SequenceSnapshotAggregateEntity(
                    SequenceSnapshotEntity(snapshotId, snapshotId, null, null, null, "sequence-series", 0),
                    SequenceSnapshotSettingsEntity(snapshotId, true, 0, 1, true, true, false, true, true, "ACTIVE"),
                    nodes =
                        listOf(SequenceSnapshotNodeEntity(repeatId, snapshotId, "REPEAT", null, 0, null, 2)) +
                            List(childCount) { index ->
                                SequenceSnapshotNodeEntity(
                                    "$snapshotId-step-$index",
                                    snapshotId,
                                    "STEP",
                                    repeatId,
                                    index,
                                    "millisecond-timer",
                                    null,
                                )
                            },
                ),
            )
            val started = startRecoverySequence(snapshotId)
            val originalChildId = requireNotNull(started.currentChild).id
            val originalOccurrences = started.execution.occurrences
            val queries = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
            reopenRuntime(name, 100_000, queries)
            val recovered = activeSequence()
            assertEquals(started.execution, recovered.execution)
            assertEquals(originalChildId, recovered.currentChild?.id)
            assertEquals(Instant.ofEpochMilli(1), NextRuntimeDeadlineResolver.resolve(recovered)?.at)

            val recoveryAt = Instant.ofEpochMilli(3L * count)
            queries.clear()
            val result = repository.reconcileActiveSession(recoveryAt)
            assertBatchedRecoveryChildValidation(
                queries.toList(),
                count,
                requireNotNull(started.execution.currentOccurrenceId).value,
            )
            val terminal = repositorySequence(started.execution.id.value)
            val endedAt = Instant.ofEpochMilli(2L * count - 1)
            assertEquals(SequenceExecutionStatus.COMPLETED, terminal.status)
            assertEquals(endedAt, terminal.endedAt)
            assertEquals(Duration.ofMillis(count.toLong()), terminal.activeDuration)
            assertEquals(Duration.ofMillis(count - 1L), terminal.pauseDuration)
            assertEquals(Duration.ofMillis(2L * count - 1), terminal.wallDuration)
            assertNull(result.finalSession)
            assertNull(repository.getActiveRuntime())
            assertEquals(2 * count - 1, result.appliedEvents.size)
            val latestFeedback = result.appliedEvents.last().deadline
            assertEquals(endedAt, latestFeedback.at)
            assertEquals(RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO, latestFeedback.kind)
            val children =
                database
                    .activityExecutionDao()
                    .getSequenceChildAggregates(terminal.id.value)
                    .also { aggregates ->
                        aggregates.forEach { aggregate ->
                            assertEquals(
                                setOf(
                                    ActivityExecutionFieldValueEntity(
                                        aggregate.execution.id,
                                        "limit-number",
                                        7,
                                        null,
                                        null,
                                    ),
                                    ActivityExecutionFieldValueEntity(
                                        aggregate.execution.id,
                                        "limit-category",
                                        null,
                                        "limit-option",
                                        null,
                                    ),
                                ),
                                aggregate.values.toSet(),
                            )
                        }
                    }.associate { requireNotNull(it.execution.sequenceOccurrenceId) to it.execution }
            val countdowns =
                terminal.intervals
                    .filter { it.kind == SequenceIntervalKind.TRANSITION_COUNTDOWN }
                    .associateBy { it.occurrenceId }
            assertEquals(count, children.size)
            assertEquals(count - 1, countdowns.size)
            assertEquals(2 * count - 1, terminal.intervals.size)
            terminal.occurrences.forEachIndexed { index, occurrence ->
                val enteredAt = Instant.ofEpochMilli(2L * index)
                val completedAt = enteredAt.plusMillis(1)
                val original = originalOccurrences[index]
                assertEquals(
                    original.copy(
                        status = RuntimeOccurrenceStatus.COMPLETED,
                        enteredAt = enteredAt,
                        completedAt = completedAt,
                        completionReason = OccurrenceCompletionReason.NATURAL_TIMER_END,
                    ),
                    occurrence,
                )
                assertEquals(repeatId, occurrence.repeatSourceSnapshotNodeId?.value)
                assertEquals(index / childCount + 1, occurrence.repeatIteration)
                val child = children.getValue(occurrence.id.value)
                assertEquals(terminal.id.value, child.sequenceExecutionId)
                assertEquals(occurrence.activitySnapshotId.value, child.snapshotId)
                assertEquals(enteredAt.toEpochMilli(), child.startedAtMs)
                assertEquals(completedAt.toEpochMilli(), child.completedAtMs)
                assertEquals(1L, child.activeDurationMs)
                assertEquals("COMPLETED", child.status)
                if (index == 0) {
                    assertEquals(originalChildId.value, child.id)
                } else {
                    val countdown = countdowns.getValue(occurrence.id)
                    assertEquals(enteredAt.minusMillis(1), countdown.startedAt)
                    assertEquals(enteredAt, countdown.endedAt)
                    val feedback = result.appliedEvents[2 * index - 1].deadline
                    assertEquals(RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN, feedback.kind)
                    assertEquals(enteredAt, feedback.at)
                    assertEquals(occurrence.id, feedback.expectedOccurrenceId)
                }
                val timerFeedback = result.appliedEvents[2 * index].deadline
                assertEquals(completedAt, timerFeedback.at)
                assertEquals(occurrence.id, timerFeedback.expectedOccurrenceId)
            }
            val occurrenceReads =
                queries.filter { (sql, _) ->
                    sql.lowercase().startsWith("select * from sequence_occurrences")
                }
            assertTrue(occurrenceReads.isNotEmpty())
            assertTrue(
                occurrenceReads.all { (sql, arguments) ->
                    sql.lowercase().contains("where sequence_execution_id =") && arguments == listOf(terminal.id.value)
                },
            )
            val snapshotReads =
                queries.filter { (sql, _) ->
                    sql.lowercase().startsWith("select * from activity_snapshots where id in")
                }
            assertTrue(snapshotReads.size in 1..12)
            assertTrue(snapshotReads.all { (_, arguments) -> arguments.size <= 3 })
            val identities = children.mapValues { it.value.id }
            reopenRuntime(name, 200_000)
            assertEquals(terminal, repositorySequence(terminal.id.value))
            assertNull(repository.getActiveSession())
            assertTrue(repository.reconcileActiveSession(recoveryAt).appliedEvents.isEmpty())
            assertEquals(
                identities,
                database
                    .activityExecutionDao()
                    .getSequenceChildAggregates(terminal.id.value)
                    .associate { requireNotNull(it.execution.sequenceOccurrenceId) to it.execution.id },
            )
            val statistics = StatisticsRepository(database) { StatisticsSeriesId("unused") }
            val global = statistics.global(StatisticsPeriod.AllTime)
            assertEquals(1L, global.topLevelExecutionCount)
            assertEquals(Duration.ofMillis(count.toLong()), global.totalTrackedDuration)
        }
    }

    @Test
    fun longCatchUpReturnsAllTimerAndCountdownFeedbackInSemanticOrder() {
        withFileRuntime { name ->
            val started = startRecoverySequence("sequence-many-timers")
            val before = activeSequence()
            reopenRuntime(name)
            assertEquals(before, activeSequence())
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
            val terminal = repositorySequence(started.execution.id.value)
            assertEquals(SequenceExecutionStatus.COMPLETED, terminal.status)
            assertEquals(instant(731), terminal.endedAt)
            assertEquals(before.execution.occurrences.map { it.id }, terminal.occurrences.map { it.id })
            val children =
                terminal.occurrences.map {
                    requireNotNull(database.activityExecutionDao().getAggregateByOccurrence(it.id.value))
                }
            children.forEachIndexed { index, child ->
                assertEquals(61_000L * index, child.execution.startedAtMs)
                assertEquals(60_000L + 61_000L * index, child.execution.completedAtMs)
            }
            reopenRuntime(name, 200)
            assertNull(repository.getActiveSession())
            assertTrue(repository.reconcileActiveSession(instant(20_000)).appliedEvents.isEmpty())
            assertEquals(terminal, repositorySequence(terminal.id.value))
            assertEquals(
                children,
                terminal.occurrences.map { database.activityExecutionDao().getAggregateByOccurrence(it.id.value) },
            )
            assertEquals(12, count("activity_executions"))
        }
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
    fun preDeadlinePausePersistsAcrossReloadBeforeDelayedWorkerReconciliation() {
        val started =
            repository.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence-waiting"),
                instant(0),
                instant(0),
                ZoneOffset.UTC,
            )
        val deadline = requireNotNull(NextRuntimeDeadlineResolver.resolve(activeSequence())).at
        val admittedAt = deadline.minusSeconds(1)

        repository.pauseActiveSequence(started.execution.id, admittedAt)
        repository = repository(database, 100)
        val reloaded = activeSequence()

        assertEquals(ActiveSessionState.PAUSED, reloaded.session.state)
        assertEquals(started.execution.currentOccurrenceId, reloaded.execution.currentOccurrenceId)
        assertNull(reloaded.execution.occurrences[0].completedAt)
        assertEquals(
            admittedAt,
            reloaded.execution.intervals
                .single { it.endedAt == null }
                .startedAt,
        )

        repository.reconcileActiveSession(deadline.plusSeconds(1))
        assertEquals(reloaded.execution, repositorySequence(started.execution.id.value))
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

    @Test
    fun runtimeAddCapsPersistedActiveSequenceAndLegacyOverLimitRuntimeRemainsRecoverable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "runtime-occurrence-limit-${System.nanoTime()}"
        database.close()
        context.deleteDatabase(name)
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            seed(database)
            repository = repository(database)
            runtimeTemplate("runtime-limit-template", TimeTrackingMode.STOPWATCH)
            val started =
                repository.startSequenceFromSnapshot(
                    SequenceSnapshotId("sequence-one-timer"),
                    instant(0),
                    instant(0),
                    ZoneOffset.UTC,
                )
            addLegacyOccurrences(
                started.execution.id.value,
                RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES - 1,
            )

            val allowed =
                repository.runtimeAdd(
                    ActivityEntrySource.Template(ActivityTemplateId("runtime-limit-template")),
                    RuntimeInsertionPlacement.TO_END,
                    instant(1),
                )
            val occurrencesAtLimit = database.sequenceExecutionDao().getOccurrences(started.execution.id.value)
            val snapshotsAtLimit = count("activity_snapshots")
            val recentAtLimit = database.activityTemplateDao().getUserState("runtime-limit-template")?.lastUsedAtMs

            assertEquals(
                RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES,
                allowed.execution.occurrences.size,
            )
            assertEquals(RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES, occurrencesAtLimit.size)
            assertThrows(IllegalArgumentException::class.java) {
                repository.runtimeAdd(
                    ActivityEntrySource.Template(ActivityTemplateId("runtime-limit-template")),
                    RuntimeInsertionPlacement.TO_END,
                    instant(2),
                )
            }
            assertEquals(snapshotsAtLimit, count("activity_snapshots"))
            assertEquals(occurrencesAtLimit, database.sequenceExecutionDao().getOccurrences(started.execution.id.value))
            assertEquals(
                recentAtLimit,
                database.activityTemplateDao().getUserState("runtime-limit-template")?.lastUsedAtMs,
            )

            database.close()
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = repository(database, 100)
            val recoveredAtLimit = activeSequence()
            assertEquals(started.execution.id, recoveredAtLimit.execution.id)
            assertEquals(
                RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES,
                recoveredAtLimit.execution.occurrences.size,
            )

            addLegacyOccurrences(
                started.execution.id.value,
                RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES + 1,
            )
            database.close()
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            repository = repository(database, 200)
            assertEquals(
                RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES + 1,
                activeSequence().execution.occurrences.size,
            )
            repository.reconcileActiveSession(instant(3))
            repository.pauseActiveSequence(instant(4))
            repository.resumeActiveSequence(instant(5))
            assertEquals(
                RuntimeOccurrenceCardinalityPolicy.MAX_SUPPORTED_RUNTIME_OCCURRENCES + 1,
                activeSequence().execution.occurrences.size,
            )
            assertThrows(IllegalArgumentException::class.java) {
                repository.runtimeAdd(
                    ActivityEntrySource.Template(ActivityTemplateId("runtime-limit-template")),
                    RuntimeInsertionPlacement.TO_END,
                    instant(6),
                )
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
            database = inMemoryDatabase()
            repository = repository(database)
        }
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
            val recovered = repositorySequence(started.execution.id.value)
            val children =
                recovered.occurrences.map { database.activityExecutionDao().getAggregateByOccurrence(it.id.value) }
            assertTrue(repository.reconcileActiveSession(instant(reconcileAtSeconds + 1)).appliedEvents.isEmpty())
            reopenRuntime(name, 200)
            assertTrue(repository.reconcileActiveSession(instant(reconcileAtSeconds + 2)).appliedEvents.isEmpty())
            assertEquals(recovered, repositorySequence(started.execution.id.value))
            assertEquals(
                children,
                recovered.occurrences.map { database.activityExecutionDao().getAggregateByOccurrence(it.id.value) },
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
            database = inMemoryDatabase()
        }
    }

    private fun withFileRuntime(verify: (String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sequence-recovery-${System.nanoTime()}"
        database.close()
        context.deleteDatabase(name)
        try {
            database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
            seed(database)
            repository = repository(database)
            verify(name)
        } finally {
            database.close()
            context.deleteDatabase(name)
            database = inMemoryDatabase()
        }
    }

    private fun reopenRuntime(
        name: String,
        offset: Int = 100,
        queries: CopyOnWriteArrayList<Pair<String, List<Any?>>>? = null,
    ) {
        database.close()
        val builder =
            LifeTracingDatabase
                .builder(ApplicationProvider.getApplicationContext(), name)
                .allowMainThreadQueries()
        if (queries != null) {
            builder.setQueryCallback(
                { sql, arguments -> queries += sql to arguments.toList() },
                java.util.concurrent.Executor(Runnable::run),
            )
        }
        database = builder.build()
        repository = repository(database, offset)
    }

    private fun assertBatchedRecoveryChildValidation(
        queries: List<Pair<String, List<Any?>>>,
        occurrenceCount: Int,
        currentOccurrenceId: String,
    ) {
        val batchSize = 900 // Below SQLite's portable 999-bind limit.
        val currentChildReads =
            queries.filter { (sql, _) ->
                sql.lowercase().startsWith("select * from activity_executions where sequence_occurrence_id =")
            }
        // Active projection hydrates three times; reconciliation hydrates the same child twice more.
        assertEquals("Original current child hydrations", 5, currentChildReads.size)
        assertTrue(currentChildReads.all { (_, arguments) -> arguments == listOf(currentOccurrenceId) })
        val metadata =
            listOf(
                "select id, sequence_execution_id, activity_snapshot_id from sequence_occurrences" to
                    (occurrenceCount + batchSize - 1) / batchSize,
                "select id, time_tracking_mode, statistics_series_id from activity_snapshots" to 1,
                "select id, snapshot_id, field_type from activity_snapshot_fields" to 1,
                "select id, snapshot_field_id from activity_snapshot_category_options" to 1,
            )
        metadata.forEach { (prefix, expectedBatches) ->
            val reads = queries.filter { (sql, _) -> sql.lowercase().startsWith(prefix) }
            val batches = reads.filter { (sql, _) -> sql.lowercase().contains("where id in") }
            assertEquals("Batched validation reads for $prefix", expectedBatches, batches.size)
            assertTrue(batches.all { (_, arguments) -> arguments.size in 1..batchSize })
            val points = reads.filterNot { (sql, _) -> sql.lowercase().contains("where id in") }
            // Only the fixed number of original-current-child hydrations may use point metadata reads.
            assertTrue("Point validation reads for $prefix: ${points.size}", points.size <= currentChildReads.size)
            if (prefix.endsWith("from sequence_occurrences")) {
                assertTrue(points.all { (_, arguments) -> arguments == listOf(currentOccurrenceId) })
            }
        }
        val scopedOptionReads =
            queries.count { (sql, _) ->
                sql.lowercase().startsWith("select options.id, options.snapshot_field_id")
            }
        assertTrue(
            "Per-child Category validation reads: $scopedOptionReads",
            scopedOptionReads <= currentChildReads.size,
        )
    }

    private fun startRecoverySequence(snapshot: String) =
        repository.startSequenceFromSnapshot(
            SequenceSnapshotId(snapshot),
            instant(0),
            instant(0),
            ZoneOffset.UTC,
        )

    @Suppress("LongMethod") // One explicit fixture covers persistence, rollback, retry, and canonical reads.
    private fun verifyTerminalPlanRecovery(forceFailure: Boolean) {
        withFileRuntime { name ->
            val planId = PlanEntryId("recovery-plan")
            database.planEntryDao().insert(
                PlanEntryEntity(
                    id = planId.value,
                    trackableKind = "SEQUENCE",
                    sourceActivityTemplateId = null,
                    sourceSequenceTemplateId = null,
                    sourceRevision = null,
                    activitySnapshotId = null,
                    sequencePlanSnapshotId = "sequence-timers",
                    precision = "DAY",
                    plannedDay = "1970-01-01",
                    plannedWeekStart = null,
                    plannedMonth = null,
                    scheduledInstantMs = null,
                    creationZoneId = null,
                    status = "PLANNED",
                    fulfilledActivityExecutionId = null,
                    fulfilledSequenceExecutionId = null,
                    createdAtMs = 0,
                    updatedAtMs = 0,
                    cancelledAtMs = null,
                    fulfilledAtMs = null,
                ),
            )
            val started = repository.startSequenceFromPlan(planId, instant(0), instant(0), ZoneOffset.UTC)
            val rootBefore = database.sequenceExecutionDao().getAggregate(started.execution.id.value)
            val initialOccurrenceId = requireNotNull(started.execution.currentOccurrenceId).value
            val childBefore = database.activityExecutionDao().getAggregateByOccurrence(initialOccurrenceId)
            val sessionBefore = database.activeSessionDao().get()
            val planBefore = database.planEntryDao().getById(planId.value)
            val frozen = activeSequence()
            if (forceFailure) {
                database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_recovery_fulfillment BEFORE UPDATE ON plan_entries " +
                        "WHEN OLD.id = 'recovery-plan' AND NEW.status = 'FULFILLED' " +
                        "BEGIN SELECT RAISE(ABORT, 'induced terminal fulfillment failure'); END",
                )
            }
            reopenRuntime(name)
            assertEquals(frozen, activeSequence())
            if (forceFailure) {
                val failure =
                    assertThrows(RuntimeException::class.java) { repository.reconcileActiveSession(instant(600)) }
                assertTrue(
                    generateSequence<Throwable>(failure) { it.cause }.any {
                        it.message?.contains("induced terminal fulfillment failure") == true
                    },
                )
                reopenRuntime(name, 200)
                assertEquals(rootBefore, database.sequenceExecutionDao().getAggregate(started.execution.id.value))
                assertEquals(
                    childBefore,
                    database.activityExecutionDao().getAggregateByOccurrence(initialOccurrenceId),
                )
                assertEquals(sessionBefore, database.activeSessionDao().get())
                assertEquals(planBefore, database.planEntryDao().getById(planId.value))
                assertEquals(1, count("activity_executions"))
                assertEquals(1, count("sequence_intervals"))
                database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_recovery_fulfillment")
            }
            val events = repository.reconcileActiveSession(instant(600)).appliedEvents
            assertEquals(
                listOf(instant(60), instant(90), instant(150)),
                events.map { it.deadline.at },
            )
            val terminal = repositorySequence(started.execution.id.value)
            val children =
                terminal.occurrences.map {
                    requireNotNull(database.activityExecutionDao().getAggregateByOccurrence(it.id.value))
                }
            reopenRuntime(name, 300)
            assertTrue(repository.reconcileActiveSession(instant(1_000)).appliedEvents.isEmpty())
            assertNull(repository.getActiveRuntime())
            assertEquals(terminal, repositorySequence(terminal.id.value))
            assertEquals(
                children,
                terminal.occurrences.map { database.activityExecutionDao().getAggregateByOccurrence(it.id.value) },
            )
            assertEquals(2, count("activity_executions"))
            assertEquals(3, count("sequence_intervals"))
            assertEquals(started.currentChild?.id?.value, children[0].execution.id)
            assertEquals(90_000L, children[1].execution.startedAtMs)
            assertEquals(150_000L, children[1].execution.completedAtMs)
            assertEquals(SequenceExecutionStatus.COMPLETED, terminal.status)
            assertEquals(instant(150), terminal.endedAt)
            assertNull(terminal.currentOccurrenceId)
            assertTrue(terminal.intervals.all { it.endedAt != null })
            assertEquals(
                frozen.snapshot,
                database.sequenceSnapshotDao().getAggregate(terminal.snapshotId.value)?.toDomain(),
            )
            assertEquals(frozen.execution.statisticsSeriesId, terminal.statisticsSeriesId)
            terminal.occurrences.forEachIndexed { index, occurrence ->
                assertEquals(
                    frozen.execution.occurrences[index].copy(
                        status = RuntimeOccurrenceStatus.COMPLETED,
                        enteredAt = instant(if (index == 0) 0 else 90),
                        completedAt = instant(if (index == 0) 60 else 150),
                        completionReason = OccurrenceCompletionReason.NATURAL_TIMER_END,
                    ),
                    occurrence,
                )
            }
            assertTerminalCanonicalReads(terminal.id, planId)
        }
    }

    private fun assertTerminalCanonicalReads(
        executionId: SequenceExecutionId,
        planId: PlanEntryId,
    ) {
        val zone = CurrentZoneIdProvider { ZoneOffset.UTC }
        val day = LocalDate.of(1970, 1, 1)
        val plans = PlanReadRepository(database, zone).getWeek(WeekPlanQuery(day.minusDays(3), day, instant(600)))
        val row = plans.selectedDayPlans.single()
        assertEquals(planId, row.plan.id)
        assertEquals(PlanEntryStatus.FULFILLED, row.plan.status)
        assertEquals(executionId, row.plan.fulfilledSequenceExecutionId)
        assertEquals(instant(150), row.plan.fulfilledAt)
        assertEquals(SequenceSnapshotId("sequence-timers"), row.plan.sequenceSnapshotId)
        assertFalse(row.engaged)
        assertFalse(row.overdue)

        val history = HistoryReadRepository(database)
        val roots = history.getCompletedRoots(CompletedHistoryQuery(HistoryDateRange(day, day), 10))
        val root = roots.single() as CompletedSequenceHistoryRoot
        assertEquals(executionId, root.executionId)
        assertEquals(planId, root.planEntryId)
        assertEquals(day, root.primaryLocalDate)
        assertEquals(instant(150), root.completedAt)
        val detail = requireNotNull(history.getSequenceDetail(executionId))
        assertEquals(root, detail.root)
        assertEquals(listOf(instant(0), instant(90)), detail.occurrences.map { it.enteredAt })
        assertEquals(listOf(instant(60), instant(150)), detail.occurrences.map { it.completedAt })
        assertTrue(detail.occurrences.all { it.child?.activeDuration == Duration.ofSeconds(60) })

        val daily = DailyReadRepository(database, zone).getDaily(DailyQuery(day, instant(600), 10))
        assertNull(daily.active)
        assertEquals(roots, daily.completedHistory)
        assertEquals(row.plan, daily.dayPlans.single().plan)
        assertFalse(daily.dayPlans.single().engaged)

        val statistics = StatisticsRepository(database) { StatisticsSeriesId("unused") }
        val global = statistics.global(StatisticsPeriod.AllTime)
        assertEquals(1L, global.topLevelExecutionCount)
        assertEquals(Duration.ofSeconds(120), global.totalTrackedDuration)
        val sequence = statistics.sequenceSeries(StatisticsSeriesId("sequence-series"), StatisticsPeriod.AllTime)
        assertEquals(Duration.ofSeconds(120), sequence.activeDurations.total)
        assertEquals(Duration.ofSeconds(30), sequence.totalPauseIdleDuration)
        val activity = statistics.activitySeries(StatisticsSeriesId("activity-series"), StatisticsPeriod.AllTime)
        assertEquals(2L, activity.executionCount)
        assertEquals(Duration.ofSeconds(120), activity.durations.total)
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

    private fun addLegacyOccurrences(
        executionId: String,
        targetCount: Int,
    ) {
        val existing = database.sequenceExecutionDao().getOccurrences(executionId)
        val source = existing.single { it.status == "CURRENT" }
        val sql = database.openHelper.writableDatabase
        val insertOccurrenceSql =
            """
            INSERT INTO sequence_occurrences (
                id, sequence_execution_id, source_sequence_snapshot_node_id, activity_snapshot_id,
                runtime_position, repeat_source_snapshot_node_id, repeat_iteration, status,
                entered_at_ms, completed_at_ms, completion_reason, is_runtime_added, is_deleted_from_history
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'NOT_STARTED', NULL, NULL, NULL, 0, 0)
            """.trimIndent()
        database.runInTransaction {
            (existing.size until targetCount).forEach { position ->
                sql.execSQL(
                    insertOccurrenceSql,
                    arrayOf<Any?>(
                        "legacy-$executionId-$position",
                        executionId,
                        source.sourceSequenceSnapshotNodeId,
                        source.activitySnapshotId,
                        position,
                        source.repeatSourceSnapshotNodeId,
                        source.repeatIteration,
                    ),
                )
            }
        }
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
