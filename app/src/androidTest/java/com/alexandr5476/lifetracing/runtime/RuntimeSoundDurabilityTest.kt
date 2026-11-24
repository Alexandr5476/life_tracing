package com.alexandr5476.lifetracing.runtime

import android.app.NotificationManager
import android.media.RingtoneManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceState
import com.alexandr5476.lifetracing.domain.ActiveSequenceStateResolver
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import com.alexandr5476.lifetracing.runtime.RuntimeCoordinatorRecoveryTest.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RuntimeSoundDurabilityTest {
    private val application = ApplicationProvider.getApplicationContext<RuntimeIsolationApplication>()

    @Test(timeout = 60_000)
    fun standaloneTimerUsesFrozenFlagsAtExactAndLateDeliveryAndAfterReopen() =
        runBlocking {
            assertStandaloneTimer(sound = true, deliveryDelayMs = 0)
            assertStandaloneTimer(sound = true, deliveryDelayMs = 7_000)
            assertStandaloneTimer(sound = false, deliveryDelayMs = 0)
            val audio = Job(coroutineContext[Job])
            try {
                val muted = MutedPlayback(CoroutineScope(coroutineContext + audio))
                assertStandaloneTimer(sound = true, deliveryDelayMs = 0, muted = muted)
            } finally {
                audio.cancelAndJoin()
            }
        }

    @Test
    fun pausedAndManuallyCompletedActivityRejectsSoundFromCapturedDeadline() =
        runBlocking {
            application.withDisposableDatabase { fixture ->
                RuntimeRecoveryStore(application, fixture.name).use { store ->
                    val process = Process(store, at(0))
                    process.coordinator.onForeground()
                    assertNull(store.live.getActiveSession())
                    process.assertDispatches()

                    val template = store.authoring.createActivityTemplate(timerDraft(sound = true), createdAt = at(0))
                    val execution = store.library.startActivityFromTemplate(template.id, at(0), at(0), ZoneOffset.UTC)
                    process.coordinator.onRuntimeStateChanged()
                    val deadline = requireNotNull(process.local.deadline)
                    process.assertDispatches()

                    store.live.pauseActiveActivity(ActivityExecutionPauseId("sound-test-pause"), at(1))
                    val paused = store.live.getActiveRuntime() as ActiveActivityRuntime
                    process.signal(deadline, at(20))
                    process.coordinator.onForeground()
                    assertEquals(paused, store.live.getActiveRuntime())
                    assertNull(process.local.deadline)
                    process.assertDispatches()

                    store.live.resumeActiveActivity(at(21))
                    process.coordinator.onRuntimeStateChanged()
                    val resumedDeadline = requireNotNull(process.local.deadline)
                    assertEquals(at(25), resumedDeadline.at)
                    process.assertDispatches()

                    store.live.completeActiveActivity(resumedDeadline.at)
                    process.coordinator.onRuntimeStateChanged()
                    process.signal(resumedDeadline, at(26))
                    assertNull(store.live.getActiveSession())
                    val history = requireNotNull(store.history.getActivityDetail(execution.id))
                    assertEquals(resumedDeadline.at, history.root.completedAt)
                    process.assertDispatches()
                }
            }
        }

    @Test
    fun sequenceTimerAndCountdownUseFrozenInheritanceAndExplicitOverrides() =
        runBlocking {
            for (sequenceSound in listOf(true, false)) {
                assertSequenceOverrides(sequenceSound)
            }
        }

    @Test(timeout = 60_000)
    fun planSequenceHasEquivalentReopenedFactsAcrossSuccessfulThrowingAndMissingSound() =
        runBlocking {
            application.withDisposableDatabase { seed ->
                val plan =
                    RuntimeRecoveryStore(application, seed.name).use { store ->
                        val template =
                            createSequence(
                                store,
                                sequenceSound = true,
                                overrides = listOf(null, null),
                                sequenceVibration = true,
                            )
                        val plan =
                            store.plans.createSequencePlanFromTemplate(
                                template.id,
                                PlanSchedule.FloatingDay(day),
                                at(0),
                            )
                        val action = store.planReads.getFocusedAction(plan.id)
                        store.live.startSequenceFromPlan(action.identity, at(0), at(0), ZoneOffset.UTC)
                        plan
                    }
                val start =
                    RuntimeRecoveryStore(application, seed.name).use { store ->
                        assertTrue(store.planReads.getFocusedAction(plan.id).engaged)
                        activeSequence(store)
                    }
                val results =
                    SoundOutcome.entries.associateWith { outcome ->
                        application.withDisposableDatabase { branch ->
                            // Clone only after all seed connections close, preserving starting identities.
                            application.getDatabasePath(seed.name).copyTo(application.getDatabasePath(branch.name))
                            exercisePlanBranch(branch.name, plan, start, outcome)
                        }
                    }
                for ((outcome, facts) in results) {
                    assertEquals(outcome.name, results.getValue(SoundOutcome.SUCCESS), facts)
                }
            }
        }

    private suspend fun assertStandaloneTimer(
        sound: Boolean,
        deliveryDelayMs: Long,
        muted: MutedPlayback? = null,
    ) {
        val vibration = muted != null || !sound
        application.withDisposableDatabase { fixture ->
            val original =
                RuntimeRecoveryStore(application, fixture.name).use { store ->
                    val draft = timerDraft(sound, vibration)
                    val template = store.authoring.createActivityTemplate(draft, createdAt = at(0))
                    val started = store.library.startActivityFromTemplate(template.id, at(0), at(0), ZoneOffset.UTC)
                    val frozen = store.live.getActiveRuntime() as ActiveActivityRuntime
                    assertEquals(started.snapshotId, frozen.snapshot.id)
                    assertEquals(draft.settings, frozen.snapshot.settings)
                    val changed =
                        store.authoring.saveActivityTemplate(
                            template.id,
                            template.revision,
                            draft.copy(settings = timerDraft(!sound, !vibration).settings),
                            at(1),
                        )
                    assertEquals(!sound, changed.settings.timerEndSound)
                    assertEquals(!vibration, changed.settings.timerEndVibration)
                    assertEquals(frozen, store.live.getActiveRuntime())

                    val process = Process(store, at(0), soundPlayer = muted?.player ?: RuntimeSoundPlayer {})
                    val deadline =
                        RuntimeDeadline(
                            at(5),
                            RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO,
                            ActiveSessionKind.ACTIVITY,
                            started.id.value,
                        )
                    process.coordinator.onRuntimeStateChanged()
                    assertEquals(deadline, process.local.deadline)
                    val justBefore = at(5).minusMillis(1)
                    // Foreground reaches the repository even before the due boundary.
                    process.signal(deadline, justBefore)
                    process.coordinator.onForeground()
                    assertEquals(frozen, store.live.getActiveRuntime())
                    assertEquals(deadline, process.local.deadline)
                    process.assertDispatches()

                    val expected = RuntimeDeadlineFeedback(deadline, sound, vibration)
                    val cancellations = process.scheduler.cancellations
                    process.accept(store, expected, at(5).plusMillis(deliveryDelayMs))
                    muted?.assertSuppressed()
                    assertEquals(cancellations + 1, process.scheduler.cancellations)
                    process.assertDispatches(expected)
                    process.coordinator.onDeadlineSignal(deadline)
                    process.coordinator.onForeground()
                    process.assertDispatches(expected)
                    assertNull(process.local.deadline)
                    assertNull(store.live.getActiveSession())
                    frozen
                }
            RuntimeRecoveryStore(application, fixture.name).use { reopened ->
                val detail = requireNotNull(reopened.history.getActivityDetail(original.execution.id))
                assertEquals(original.execution.id, detail.root.executionId)
                assertEquals(original.snapshot.id, detail.root.snapshotId)
                assertEquals(original.snapshot.settings, detail.settings)
                assertEquals(at(0), detail.root.startedAt)
                assertEquals(at(5), detail.root.completedAt)
                assertEquals(Duration.ofSeconds(5), detail.root.activeDuration)
                assertNull(reopened.live.getActiveSession())
                assertEquals(1L to 0L, reopened.executionCounts())
            }
        }
    }

    private suspend fun assertSequenceOverrides(sequenceSound: Boolean) {
        application.withDisposableDatabase { fixture ->
            val start =
                RuntimeRecoveryStore(application, fixture.name).use { store ->
                    val template =
                        createSequence(
                            store,
                            sequenceSound,
                            overrides = listOf(null, !sequenceSound, null),
                            localLastStep = true,
                        )
                    store.library.startSequenceFromTemplate(template.id, at(0), at(0), ZoneOffset.UTC)
                    val frozen = activeSequence(store)
                    val lastStep = frozen.snapshot.nodes.last() as SequenceSnapshotActivityStep
                    assertTrue(frozen.activitySnapshots.getValue(lastStep.activitySnapshotId).locallyModified)
                    assertNull(lastStep.overrides.timerEndSound)
                    val authoring = requireNotNull(store.authoring.getSequenceTemplateAuthoringState(template.id))
                    val draft = authoring.toAuthoringDraft()
                    val replacement =
                        draft.copy(
                            settings =
                                draft.settings.copy(
                                    transitionSound = !sequenceSound,
                                    transitionVibration = sequenceSound,
                                ),
                            nodes =
                                draft.nodes.map { node ->
                                    val step = (node as SequenceNodeDraft.Step).value
                                    SequenceNodeDraft.Step(step.copy(overrides = SequenceStepOverrides()))
                                },
                        )
                    val saved = store.authoring.saveSequenceTemplate(template.id, template.revision, replacement, at(1))
                    assertEquals(!sequenceSound, saved.settings.transitionSound)
                    assertEquals(frozen, activeSequence(store))
                    frozen
                }
            val childIds =
                RuntimeRecoveryStore(application, fixture.name).use { store ->
                    assertEquals(start, activeSequence(store))
                    val process = Process(store, at(0))
                    process.coordinator.onRuntimeStateChanged()
                    val events = sequenceEvents(start, listOf(sequenceSound, !sequenceSound, sequenceSound))
                    val children = mutableListOf(requireNotNull(start.currentChild).id)
                    for (event in events) {
                        process.accept(store, event, event.deadline.at)
                        val runtime = store.live.getActiveRuntime() as ActiveSequenceRuntime?
                        if (runtime != null) {
                            assertEquals(start.snapshot, runtime.snapshot)
                            assertEquals(start.execution.id, runtime.session.sequenceExecutionId)
                            val occurrenceId = event.deadline.expectedOccurrenceId
                            when (event.deadline.kind) {
                                RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO -> {
                                    val completed = runtime.execution.occurrences.single { it.id == occurrenceId }
                                    val state = ActiveSequenceStateResolver.resolve(runtime)
                                    assertEquals(ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN, state)
                                    assertEquals(event.deadline.at, completed.completedAt)
                                    assertNull(runtime.currentChild)
                                }
                                RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN -> {
                                    assertEquals(occurrenceId, runtime.execution.currentOccurrenceId)
                                    val child = requireNotNull(runtime.currentChild)
                                    assertEquals(occurrenceId, child.sequenceOccurrenceId)
                                    assertEquals(event.deadline.at, child.startedAt)
                                    children += child.id
                                }
                                else -> error("Unexpected Sequence deadline")
                            }
                        }
                    }
                    process.assertDispatches(*events.toTypedArray())
                    assertNull(store.live.getActiveSession())
                    children
                }
            RuntimeRecoveryStore(application, fixture.name).use { reopened ->
                val history = requireNotNull(reopened.history.getSequenceDetail(start.execution.id))
                assertCompletedSequence(start, history, childIds)
                assertEquals(3L to 1L, reopened.executionCounts())
                assertNull(reopened.live.getActiveSession())
            }
        }
    }

    private suspend fun exercisePlanBranch(
        name: String,
        plan: PlanEntry,
        start: ActiveSequenceRuntime,
        outcome: SoundOutcome,
    ): ReopenedPlanSequence =
        coroutineScope {
            val audioJob = Job(coroutineContext[Job])
            val audioScope = CoroutineScope(coroutineContext + audioJob)
            val unavailable = UnavailableCue()
            val silentPlayer = AndroidRuntimeSoundPlayer(audioScope, unavailable)
            val muted = if (outcome == SoundOutcome.MUTED) MutedPlayback(audioScope) else null
            val events = sequenceEvents(start, soundFlags = listOf(true, true), vibrationFlags = listOf(true, true))
            val (childIds, intervalIds) =
                try {
                    RuntimeRecoveryStore(application, name).use { store ->
                        RuntimeRecoveryStore(application, name).use { observer ->
                            assertEquals(start, activeSequence(observer))
                            val observations = mutableListOf<ActiveSequenceRuntime?>()
                            var successfulPlays = 0
                            val soundPlayer =
                                RuntimeSoundPlayer {
                                    observations += observer.live.getActiveRuntime() as ActiveSequenceRuntime?
                                    when (outcome) {
                                        SoundOutcome.SUCCESS -> successfulPlays++
                                        SoundOutcome.THROWING -> throw RuntimeException("injected sound failure")
                                        SoundOutcome.MISSING_CUE -> silentPlayer.play()
                                        SoundOutcome.MUTED -> requireNotNull(muted).player.play()
                                    }
                                }
                            val process = Process(store, at(0), soundPlayer = soundPlayer)
                            process.coordinator.onRuntimeStateChanged()
                            val children = mutableListOf(requireNotNull(start.currentChild).id)
                            for ((index, event) in events.withIndex()) {
                                val delaySeconds = if (index == events.lastIndex) 2L else 0L
                                val delivery = event.deadline.at.plusSeconds(delaySeconds)
                                val cancellations = process.scheduler.cancellations
                                process.accept(store, event, delivery)
                                muted?.assertSuppressed()
                                if (outcome == SoundOutcome.MISSING_CUE) {
                                    val type = withTimeout(5_000) { unavailable.resolutions.receive() }
                                    assertEquals(RingtoneManager.TYPE_NOTIFICATION, type)
                                }
                                val published = process.notifications.lastRuntime as ActiveSequenceRuntime?
                                assertEquals(published, observations[index])
                                if (published != null) {
                                    assertEquals(start.execution.id, published.session.sequenceExecutionId)
                                    assertEquals(events[index + 1].deadline, process.local.deadline)
                                    assertEquals(events[index + 1].deadline, process.scheduler.deadlines.last())
                                    val action = observer.planReads.getFocusedAction(plan.id)
                                    assertEquals(PlanEntryStatus.PLANNED, action.identity.status)
                                    assertTrue(action.engaged)
                                } else {
                                    assertEquals(cancellations + 1, process.scheduler.cancellations)
                                }
                                if (event.deadline.kind == RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN) {
                                    children += requireNotNull(published?.currentChild).id
                                }
                                process.coordinator.onDeadlineSignal(event.deadline)
                                process.assertDispatches(*events.take(index + 1).toTypedArray())
                            }
                            assertEquals(events.size, observations.size)
                            assertEquals(if (outcome == SoundOutcome.SUCCESS) events.size else 0, successfulPlays)
                            assertNull(process.local.deadline)
                            assertNull(store.live.getActiveSession())
                            val lastLiveRuntime = requireNotNull(observations[events.lastIndex - 1])
                            children to lastLiveRuntime.execution.intervals.map { it.id }
                        }
                    }
                } finally {
                    audioJob.cancelAndJoin()
                    unavailable.resolutions.close()
                }
            RuntimeRecoveryStore(application, name).use { reopened ->
                val history = requireNotNull(reopened.history.getSequenceDetail(start.execution.id))
                assertCompletedSequence(start, history, childIds)
                assertEquals(intervalIds, history.intervals.map { it.id })
                assertEquals(2L to 1L, reopened.executionCounts())
                assertNull(reopened.live.getActiveSession())
                val action = reopened.planReads.getFocusedAction(plan.id)
                assertEquals(PlanEntryStatus.FULFILLED, action.identity.status)
                assertFalse(action.engaged)
                val snapshot = action.snapshot as FocusedPlanAction.Snapshot.Sequence
                assertEquals(start.snapshot, snapshot.value)
                assertEquals(start.activitySnapshots, snapshot.activitySnapshots)
                val week = reopened.planReads.getWeek(WeekPlanQuery(day, day, at(20), ZoneOffset.UTC))
                val row = week.selectedDayPlans.single()
                val fulfilled =
                    plan.copy(
                        status = PlanEntryStatus.FULFILLED,
                        fulfilledSequenceExecutionId = start.execution.id,
                        updatedAt = at(13),
                        fulfilledAt = at(13),
                    )
                assertEquals(fulfilled, row.plan)
                ReopenedPlanSequence(history.normalizeGeneratedIds(), row.plan)
            }
        }

    private fun assertCompletedSequence(
        start: ActiveSequenceRuntime,
        history: SequenceHistoryDetail,
        childIds: List<ActivityExecutionId>,
    ) {
        val initial = start.execution
        val finalAt = at(5L + (initial.occurrences.size - 1) * 8L)
        assertEquals(initial.id, history.root.executionId)
        assertEquals(initial.snapshotId, history.root.snapshotId)
        assertEquals(initial.planEntryId, history.root.planEntryId)
        assertEquals(start.snapshot.settings, history.settings)
        assertEquals(SequenceExecutionStatus.COMPLETED, history.root.status)
        assertEquals(at(0), history.root.startedAt)
        assertEquals(finalAt, history.root.completedAt)
        assertEquals(Duration.between(at(0), finalAt), history.root.wallDuration)
        assertEquals(Duration.ofSeconds(initial.occurrences.size * 5L), history.root.activeDuration)
        assertEquals(Duration.ofSeconds((initial.occurrences.size - 1) * 3L), history.root.pauseDuration)
        assertEquals(initial.occurrences.map { it.id }, history.occurrences.map { it.occurrenceId })
        assertEquals(childIds, history.occurrences.map { it.child?.executionId })
        assertEquals(childIds, history.occurrences.map { it.childMutationFacts?.executionId })

        val expectedIntervals =
            history.occurrences.flatMapIndexed { index, occurrence ->
                val original = initial.occurrences[index]
                val enteredAt = at(index * 8L)
                val completedAt = enteredAt.plusSeconds(5)
                assertEquals(original.sourceSequenceSnapshotNodeId, occurrence.sourceSequenceSnapshotNodeId)
                assertEquals(original.activitySnapshotId, occurrence.activitySnapshotId)
                assertEquals(original.activitySnapshotId, occurrence.activity.snapshotId)
                val activity = start.activitySnapshots.getValue(original.activitySnapshotId)
                assertEquals(activity.settings, occurrence.activity.settings)
                assertEquals(RuntimeOccurrenceStatus.COMPLETED, occurrence.status)
                assertEquals(OccurrenceCompletionReason.NATURAL_TIMER_END, occurrence.completionReason)
                assertEquals(enteredAt, occurrence.enteredAt)
                assertEquals(completedAt, occurrence.completedAt)
                val child = requireNotNull(occurrence.child)
                assertEquals(ActivityExecutionStatus.COMPLETED, child.status)
                assertEquals(enteredAt, child.startedAt)
                assertEquals(completedAt, child.completedAt)
                assertEquals(Duration.ofSeconds(5), child.activeDuration)
                buildList {
                    if (index != 0) {
                        add(
                            interval(
                                SequenceIntervalKind.TRANSITION_COUNTDOWN,
                                enteredAt.minusSeconds(3),
                                enteredAt,
                                original.id,
                            ),
                        )
                    }
                    add(interval(SequenceIntervalKind.ACTIVE_STEP, enteredAt, completedAt, original.id))
                }
            }
        assertEquals(initial.intervals.single().id, history.intervals.first().id)
        assertEquals(expectedIntervals, history.intervals.map { it.copy(id = SequenceIntervalId("expected")) })
    }

    private fun SequenceHistoryDetail.normalizeGeneratedIds(): SequenceHistoryDetail =
        copy(
            occurrences =
                occurrences.mapIndexed { index, occurrence ->
                    if (index == 0) {
                        occurrence
                    } else {
                        val branchIndependentId = ActivityExecutionId("later-child-$index")
                        val mutationFacts = requireNotNull(occurrence.childMutationFacts)
                        occurrence.copy(
                            child = requireNotNull(occurrence.child).copy(executionId = branchIndependentId),
                            childMutationFacts = mutationFacts.copy(executionId = branchIndependentId),
                        )
                    }
                },
            intervals =
                intervals.mapIndexed { index, interval ->
                    if (index == 0) interval else interval.copy(id = SequenceIntervalId("later-interval-$index"))
                },
        )

    private fun createSequence(
        store: RuntimeRecoveryStore,
        sequenceSound: Boolean,
        overrides: List<Boolean?>,
        sequenceVibration: Boolean = !sequenceSound,
        localLastStep: Boolean = false,
    ) = store.authoring.createSequenceTemplate(
        SequenceTemplateDraft(
            "Feedback Sequence",
            null,
            settings =
                SequenceTemplateSettings(
                    beforeEachStepCountdown = Duration.ofSeconds(3),
                    transitionSound = sequenceSound,
                    transitionVibration = sequenceVibration,
                ),
            nodes =
                overrides.mapIndexed { index, override ->
                    val activityDraft = timerDraft(!(override ?: sequenceSound))
                    val activity =
                        if (localLastStep && index == overrides.lastIndex) {
                            StepActivityDraft.Local(
                                ActivitySnapshotDraft(
                                    activityDraft.name,
                                    activityDraft.shortComment,
                                    activityDraft.timeTrackingMode,
                                    activityDraft.timerTarget,
                                    activityDraft.settings,
                                ),
                            )
                        } else {
                            val template = store.authoring.createActivityTemplate(activityDraft, createdAt = at(0))
                            StepActivityDraft.FromTemplate(template.id)
                        }
                    SequenceNodeDraft.Step(
                        ActivityStepDraft(
                            DraftIdentity.New("step-$index"),
                            index,
                            activity,
                            SequenceStepOverrides(timerEndSound = override, timerEndVibration = override?.not()),
                        ),
                    )
                },
        ),
        createdAt = at(0),
    )

    private fun sequenceEvents(
        start: ActiveSequenceRuntime,
        soundFlags: List<Boolean>,
        vibrationFlags: List<Boolean> = soundFlags.map { !it },
    ): List<RuntimeDeadlineFeedback> =
        start.execution.occurrences.flatMapIndexed { index, occurrence ->
            val enteredAt = at(index * 8L)

            fun feedback(
                at: Instant,
                kind: RuntimeDeadlineKind,
            ) = RuntimeDeadlineFeedback(
                RuntimeDeadline(at, kind, ActiveSessionKind.SEQUENCE, start.execution.id.value, occurrence.id),
                soundFlags[index],
                vibrationFlags[index],
            )

            buildList {
                if (index != 0) add(feedback(enteredAt, RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN))
                add(feedback(enteredAt.plusSeconds(5), RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO))
            }
        }

    private suspend fun Process.accept(
        store: RuntimeRecoveryStore,
        event: RuntimeDeadlineFeedback,
        deliveredAt: Instant,
    ) {
        assertEquals(event.deadline, local.deadline)
        assertEquals(event.deadline, scheduler.deadlines.last())
        val dispatched = effects.size
        signal(event.deadline, deliveredAt)
        assertEquals(dispatched + 1, effects.size)
        assertEquals(event, effects.last())
        assertEquals(effects, notifications.completions)
        val committed = store.live.getActiveRuntime()
        assertEquals(committed, notifications.lastRuntime)
        assertEquals(committed?.let(NextRuntimeDeadlineResolver::resolve), local.deadline)
    }

    private suspend fun Process.signal(
        deadline: RuntimeDeadline,
        now: Instant,
    ) {
        wall.value = now
        monotonic.value = Duration.between(at(0), now).toMillis()
        coordinator.onDeadlineSignal(deadline)
    }

    private fun Process.assertDispatches(vararg expected: RuntimeDeadlineFeedback) {
        assertEquals(expected.toList(), effects)
        assertEquals(expected.toList(), notifications.completions)
        assertEquals(expected.count { it.soundEnabled }, soundAttempts)
        assertEquals(expected.count { it.vibrationEnabled }, vibrationAttempts)
    }

    private fun activeSequence(store: RuntimeRecoveryStore): ActiveSequenceRuntime =
        store.live.getActiveRuntime() as ActiveSequenceRuntime

    private fun interval(
        kind: SequenceIntervalKind,
        opened: Instant,
        closed: Instant,
        occurrence: SequenceOccurrenceId,
    ) = SequenceInterval(SequenceIntervalId("expected"), kind, opened, closed, occurrence)

    private fun timerDraft(
        sound: Boolean,
        vibration: Boolean = !sound,
    ) = ActivityTemplateDraft(
        "Feedback timer",
        null,
        TimeTrackingMode.TIMER,
        Duration.ofSeconds(5),
        ActivityTemplateSettings(timerEndSound = sound, timerEndVibration = vibration),
    )

    private fun at(seconds: Long): Instant = origin.plusSeconds(seconds)

    private enum class SoundOutcome { SUCCESS, THROWING, MISSING_CUE, MUTED }

    private inner class MutedPlayback(
        scope: CoroutineScope,
    ) {
        private val startsAtRelease = Channel<Int>(Channel.CONFLATED)
        private val cue =
            application.preparedNotificationCue(
                unrestrictedAudio.copy(interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE),
            ) { check(startsAtRelease.trySend(it).isSuccess) }
        val player = AndroidRuntimeSoundPlayer(scope, cue)

        suspend fun assertSuppressed() {
            assertEquals(0, withTimeout(5_000) { startsAtRelease.receive() })
        }
    }

    private class UnavailableCue : RuntimeNotificationCue {
        val resolutions = Channel<Int>(Channel.UNLIMITED)

        override fun resolve(type: Int): Uri? {
            check(resolutions.trySend(type).isSuccess)
            return null
        }

        override suspend fun play(uri: Uri): Unit = throw AssertionError("Missing system cue cannot play")
    }

    private data class ReopenedPlanSequence(
        val history: SequenceHistoryDetail,
        val plan: PlanEntry,
    )

    private companion object {
        val origin: Instant = Instant.parse("2026-01-05T12:00:00Z")
        val day: LocalDate = LocalDate.of(2026, 1, 5)
    }
}
