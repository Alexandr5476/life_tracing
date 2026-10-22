@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package com.alexandr5476.lifetracing.live

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceState
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequenceRead
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.executeExpandedSequenceCommand
import com.alexandr5476.lifetracing.ui.onLazyNode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ExpandedLiveSequenceAccessibilityPersistenceTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val keyboardInput =
        com.alexandr5476.lifetracing.ui
            .KeyboardInputFixture(compose)

    @Test
    fun semanticPauseResumeCompleteAndStartNextRetainPlanAndPhysicalRuntimeFacts() {
        Fixture().use { fixture ->
            val before = fixture.active()
            fixture.mount()
            fixture.invoke(R.string.expanded_sequence_pause)
            fixture.awaitWrites(1)
            assertEquals(ActiveSessionState.PAUSED, fixture.active().session.state)
            fixture.invoke(R.string.expanded_sequence_resume)
            fixture.awaitWrites(2)
            fixture.invoke(R.string.expanded_sequence_complete)
            fixture.awaitWrites(3)
            assertEquals(
                ActiveSequenceState.WAITING_NEXT,
                fixture.controller.state.value.sequence!!
                    .state,
            )
            assertNull(fixture.active().execution.currentOccurrenceId)
            fixture.invoke(R.string.expanded_sequence_start_next)
            fixture.awaitWrites(4)
            val after = fixture.active()
            assertEquals(before.execution.id, after.execution.id)
            assertEquals(before.execution.occurrences[1].id, after.execution.currentOccurrenceId)
            assertEquals(RuntimeOccurrenceStatus.COMPLETED, after.execution.occurrences[0].status)
            assertEquals(before.snapshot, after.snapshot)
            assertEquals(before.activitySnapshots, after.activitySnapshots)
            assertEquals(
                before.execution.occurrences.map { it.sourceSequenceSnapshotNodeId },
                after.execution.occurrences.map { it.sourceSequenceSnapshotNodeId },
            )
            assertEquals(
                PlanEntryStatus.PLANNED,
                fixture.store.plans
                    .getPlan(fixture.plan.id)!!
                    .status,
            )
            assertTrue(
                fixture.store.planReads
                    .getFocusedAction(fixture.plan.id)
                    .engaged,
            )
            assertEquals(fixture.plan.id, after.execution.planEntryId)
            val children = fixture.store.children(after.execution.id)
            val facts = fixture.store.durableFacts()
            fixture.reopen()
            assertEquals(after, fixture.active())
            assertEquals(children, fixture.store.children(after.execution.id))
            assertEquals(facts, fixture.store.durableFacts())
            assertEquals(fixture.plan.id, fixture.active().execution.planEntryId)
            assertEquals(
                PlanEntryStatus.PLANNED,
                fixture.store.plans
                    .getPlan(fixture.plan.id)!!
                    .status,
            )
        }
    }

    @Test
    fun canonicalCurrentValuesRemainEditableThroughSaveCompleteAndPhysicalReopen() {
        assertCanonicalValuesAfterReopen(complete = true)
    }

    @Test
    fun canonicalSavedCurrentValuesKeepSnapshotAndMissingAfterPhysicalReopen() {
        assertCanonicalValuesAfterReopen(complete = false)
    }

    private fun assertCanonicalValuesAfterReopen(complete: Boolean) {
        Fixture(withValues = true).use { fixture ->
            val before = fixture.active()
            val child = before.currentChild!!
            val snapshot = before.activitySnapshots.getValue(child.snapshotId)
            val quantity = snapshot.fields.first()
            val zero = snapshot.fields[1]
            val optional = snapshot.fields.last()
            fixture.mount()
            val draft = fixture.controller.state.value.currentValueDraft!!
            assertEquals(child.values.associateBy { it.snapshotFieldId }, draft.values.filterValues { it != null })
            assertEquals(
                com.alexandr5476.lifetracing.domain
                    .NumberExecutionValue(quantity.id, 0),
                draft.values[quantity.id],
            )
            assertNull(draft.values[optional.id])
            compose
                .onLazyNode(
                    hasContentDescription(
                        fixture.string(
                            R.string.expanded_sequence_field_summary,
                            fixture.string(R.string.expanded_sequence_draft_values),
                            fixture.string(R.string.expanded_sequence_main_value_field, "Quantity kg"),
                            fixture.string(R.string.activity_editor_number),
                            "0",
                        ),
                    ),
                ).assertExists()
            compose
                .onLazyNode(
                    androidx.compose.ui.test
                        .hasTestTag("expanded-sequence-current-value-${quantity.id.value}"),
                ).performTextReplacement("2")
            assertEquals(0, fixture.writes.get())
            assertEquals(child, fixture.active().currentChild)
            fixture.invoke(R.string.expanded_sequence_save_values)
            fixture.awaitWrites(1)
            val saved = fixture.active().currentChild!!
            val command = fixture.commands.single() as ExpandedSequenceCommand.SaveValues
            assertEquals(before.execution.currentOccurrenceId, command.occurrenceId)
            assertEquals(snapshot.fields.map { it.id }, command.values.map { it.snapshotFieldId })
            assertEquals(
                com.alexandr5476.lifetracing.domain
                    .NumberExecutionValue(quantity.id, 2_000),
                command.values.first().value,
            )
            assertNull(command.values.last().value)
            val retained = fixture.controller.state.value.currentValueDraft!!
            assertEquals(
                com.alexandr5476.lifetracing.domain
                    .NumberExecutionValue(quantity.id, 2_000),
                retained.values[quantity.id],
            )
            assertNull(retained.values[optional.id])
            compose.onLazyNode(hasText(fixture.string(R.string.expanded_sequence_draft_values))).assertExists()
            if (complete) {
                fixture.invoke(R.string.expanded_sequence_complete)
                fixture.awaitWrites(2)
            }
            val facts = fixture.store.durableFacts()
            fixture.reopen()
            val projection =
                fixture.store.live.getExpandedSequence(
                    before.execution.id,
                ) as ExpandedLiveSequenceRead.Active
            val row = projection.value.occurrences.single { it.occurrence.id == before.execution.currentOccurrenceId }
            assertEquals(
                if (complete) RuntimeOccurrenceStatus.COMPLETED else RuntimeOccurrenceStatus.CURRENT,
                row.occurrence.status,
            )
            assertEquals(snapshot, row.activity)
            assertEquals(saved.id, row.childExecution!!.id)
            assertEquals(saved.snapshotId, row.childExecution!!.snapshotId)
            assertEquals(saved.values, row.childExecution!!.values)
            assertEquals(
                com.alexandr5476.lifetracing.domain
                    .NumberExecutionValue(zero.id, 0),
                row.childExecution!!.values.single { it.snapshotFieldId == zero.id },
            )
            assertTrue(row.childExecution!!.values.none { it.snapshotFieldId == optional.id })
            assertEquals(facts, fixture.store.durableFacts())
        }
    }

    @Test
    fun semanticMakeNextUsesClickedRepeatedOccurrenceAndPreservesProvenanceAfterReopen() {
        Fixture().use { fixture ->
            val before = fixture.active()
            val selected = before.execution.occurrences[2]
            fixture.mount()
            val description =
                fixture.string(
                    R.string.expanded_sequence_row_actions,
                    fixture.string(R.string.expanded_sequence_occurrence_identity, "Repeated activity", 3),
                )
            compose
                .onLazyNode(
                    hasContentDescription(description),
                ).performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertEquals(0, fixture.writes.get())
            fixture.invoke(R.string.expanded_sequence_make_next)
            fixture.awaitWrites(1)
            val after = fixture.active()
            assertEquals(
                listOf(before.execution.occurrences[0].id, selected.id, before.execution.occurrences[1].id),
                after.execution.occurrences.map { it.id },
            )
            assertEquals(before.snapshot, after.snapshot)
            assertEquals(before.activitySnapshots, after.activitySnapshots)
            assertEquals(selected.copy(runtimePosition = 1), after.execution.occurrences[1])
            val facts = fixture.store.durableFacts()
            fixture.reopen()
            assertEquals(after, fixture.active())
            assertEquals(facts, fixture.store.durableFacts())
        }
    }

    @Test
    fun semanticTemplateRuntimeAddStartNowKeepsFrozenSnapshotSourceAndChildAfterReopen() {
        Fixture().use { fixture ->
            val reusable =
                fixture.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Runtime reusable", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = fixture.now,
                )
            val duplicate =
                fixture.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Runtime reusable", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = fixture.now,
                )
            val before = fixture.active()
            fixture.mount()
            fixture.invoke(R.string.expanded_sequence_complete)
            fixture.awaitWrites(1)
            compose
                .onNodeWithTag(
                    "expanded-sequence-global-actions",
                ).performSemanticsAction(SemanticsActions.OnClick) { it() }
            fixture.invoke(R.string.expanded_sequence_runtime_add)
            compose.waitUntil(10_000) { fixture.controller.state.value.catalog != null }
            fixture.invoke(R.string.expanded_sequence_start_now)
            val catalog = fixture.controller.state.value.catalog!!
            val position = catalog.indexOfFirst { it.id == reusable.id } + 1
            assertTrue(catalog.any { it.id == duplicate.id && it.name == reusable.name })
            assertEquals(1, fixture.writes.get())
            compose
                .onNodeWithContentDescription(
                    fixture.string(
                        R.string.expanded_sequence_catalog_identity,
                        "Runtime reusable",
                        fixture.string(R.string.expanded_sequence_stopwatch),
                        position,
                    ),
                ).performSemanticsAction(SemanticsActions.OnClick) { it() }
            fixture.awaitWrites(2)
            val after = fixture.active()
            val added = after.execution.occurrences.single { it.isRuntimeAdded }
            assertEquals(added.id, after.execution.currentOccurrenceId)
            assertEquals(1, added.runtimePosition)
            assertNull(added.sourceSequenceSnapshotNodeId)
            assertNull(added.repeatSourceSnapshotNodeId)
            assertNull(added.repeatIteration)
            assertNotEquals(before.execution.occurrences[0].id, added.id)
            val snapshot = after.activitySnapshots.getValue(added.activitySnapshotId)
            assertEquals(reusable.id, snapshot.sourceTemplateId)
            assertEquals("Runtime reusable", snapshot.name)
            val child = after.currentChild!!
            assertEquals(added.id, child.sequenceOccurrenceId)
            assertEquals(snapshot.id, child.snapshotId)
            assertEquals(before.snapshot, after.snapshot)
            val facts = fixture.store.durableFacts()
            fixture.reopen()
            assertEquals(after, fixture.active())
            assertEquals(facts, fixture.store.durableFacts())
            val projection =
                fixture.store.live.getExpandedSequence(
                    after.execution.id,
                ) as ExpandedLiveSequenceRead.Active
            assertEquals(
                snapshot,
                projection.value.occurrences
                    .single { it.occurrence.id == added.id }
                    .activity,
            )
            assertEquals(
                child,
                projection.value.occurrences
                    .single { it.occurrence.id == added.id }
                    .childExecution,
            )
        }
    }

    @Test
    fun semanticEarlyEndCancelThenConfirmFulfillsOnlyExplicitPlanAfterReopen() {
        Fixture().use { fixture ->
            val before = fixture.active()
            fixture.mount()

            fun openEnd() {
                compose
                    .onNodeWithTag("expanded-sequence-global-actions")
                    .performSemanticsAction(SemanticsActions.OnClick) { it() }
                fixture.invoke(R.string.expanded_sequence_end_early)
            }
            openEnd()
            assertEquals(0, fixture.writes.get())
            fixture.invoke(R.string.expanded_sequence_cancel)
            assertEquals(0, fixture.writes.get())
            openEnd()
            fixture.invoke(R.string.expanded_sequence_confirm)
            fixture.awaitWrites(1)
            compose.waitUntil(10_000) { fixture.staleExits.get() > 0 }
            val terminal = fixture.store.sequence(before.execution.id)
            assertEquals(SequenceExecutionStatus.ENDED_EARLY, terminal.status)
            assertEquals(fixture.plan.id, terminal.planEntryId)
            assertNull(fixture.store.live.getActiveSession())
            val plan = fixture.store.plans.getPlan(fixture.plan.id)!!
            assertEquals(PlanEntryStatus.FULFILLED, plan.status)
            assertEquals(terminal.id, plan.fulfilledSequenceExecutionId)
            assertEquals(terminal.endedAt, plan.fulfilledAt)
            val facts = fixture.store.durableFacts()
            fixture.reopen()
            assertEquals(terminal, fixture.store.sequence(terminal.id))
            assertEquals(plan, fixture.store.plans.getPlan(plan.id))
            assertEquals(facts, fixture.store.durableFacts())
            assertEquals(1, fixture.writes.get())
        }
    }

    @Test
    fun timerBeforeAtAndAfterDeadlineIsLabeledWithoutReconciliationAndPauseFreezesIt() {
        Fixture(mode = TimeTrackingMode.TIMER).use { fixture ->
            val sequence =
                (
                    fixture.store.live.getExpandedSequence(
                        fixture.started.execution.id,
                    ) as ExpandedLiveSequenceRead.Active
                ).value
            val baseline =
                com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline.capture(
                    sequence.runtime,
                    com.alexandr5476.lifetracing.domain
                        .WallMonotonicAnchor(fixture.now, 0),
                    0,
                )
            val state =
                mutableStateOf(
                    ExpandedLiveSequenceState(sequence = sequence, loading = false, displayBaseline = baseline),
                )
            val tick = androidx.compose.runtime.mutableLongStateOf(59_000)
            val facts = fixture.store.durableFacts()
            compose.setContent {
                LifeTracingTheme { ExpandedLiveSequenceScreen(state.value, fixture.controller, {}, tick.longValue) }
            }
            compose.onLazyNode(hasText(fixture.string(R.string.expanded_sequence_timer_remaining, "00:01")))
            compose
                .onNodeWithContentDescription(
                    fixture.string(
                        R.string.expanded_sequence_timer_remaining,
                        fixture.string(R.string.daily_duration_seconds, 0L, 0L, 1L),
                    ),
                ).assertExists()
            compose.runOnIdle { tick.longValue = 60_000 }
            compose.onLazyNode(hasText(fixture.string(R.string.expanded_sequence_timer_remaining, "00:00")))
            compose.runOnIdle { tick.longValue = 61_000 }
            compose.onLazyNode(hasText(fixture.string(R.string.expanded_sequence_overtime, "00:01")))
            assertEquals(facts, fixture.store.durableFacts())
            fixture.store.live.pauseActiveSequence(fixture.started.execution.id, fixture.now.plusSeconds(1))
            val paused =
                (
                    fixture.store.live.getExpandedSequence(
                        fixture.started.execution.id,
                    ) as ExpandedLiveSequenceRead.Active
                ).value
            val frozen =
                com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline.capture(
                    paused.runtime,
                    com.alexandr5476.lifetracing.domain
                        .WallMonotonicAnchor(fixture.now.plusSeconds(1), 0),
                    0,
                )
            compose.runOnIdle { state.value = state.value.copy(sequence = paused, displayBaseline = frozen) }
            compose.onLazyNode(hasText(fixture.string(R.string.expanded_sequence_timer_remaining, "00:59")))
            compose.runOnIdle { tick.longValue = 120_000 }
            compose.onLazyNode(hasText(fixture.string(R.string.expanded_sequence_timer_remaining, "00:59")))
        }
    }

    @Test
    fun runningAndPausedCountdownUseBaselineWithoutFabricatingCurrentOrCompletingAtZero() {
        Fixture(
            settings =
                SequenceTemplateSettings(
                    autoAdvance = true,
                    beforeEachStepCountdown = java.time.Duration.ofSeconds(5),
                ),
        ).use { fixture ->
            fixture.store.live.completeCurrentSequenceStep(
                fixture.active().execution.currentOccurrenceId!!,
                fixture.now.plusSeconds(1),
            )
            val sequence =
                (
                    fixture.store.live.getExpandedSequence(
                        fixture.started.execution.id,
                    ) as ExpandedLiveSequenceRead.Active
                ).value
            assertEquals(ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN, sequence.state)
            val baseline =
                com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline.capture(
                    sequence.runtime,
                    com.alexandr5476.lifetracing.domain
                        .WallMonotonicAnchor(fixture.now.plusSeconds(1), 0),
                    0,
                )
            val state =
                mutableStateOf(
                    ExpandedLiveSequenceState(sequence = sequence, loading = false, displayBaseline = baseline),
                )
            val tick = androidx.compose.runtime.mutableLongStateOf(0)
            val facts = fixture.store.durableFacts()
            compose.setContent {
                LifeTracingTheme { ExpandedLiveSequenceScreen(state.value, fixture.controller, {}, tick.longValue) }
            }
            compose
                .onNodeWithText(
                    fixture.string(R.string.expanded_sequence_transition_remaining, "00:05"),
                ).assertExists()
            compose.onNodeWithText(fixture.string(R.string.expanded_sequence_complete)).assertDoesNotExist()
            compose.runOnIdle { tick.longValue = 5_000 }
            compose
                .onNodeWithText(
                    fixture.string(R.string.expanded_sequence_transition_remaining, "00:00"),
                ).assertExists()
            assertEquals(facts, fixture.store.durableFacts())
            fixture.store.live.pauseActiveSequence(fixture.started.execution.id, fixture.now.plusSeconds(2))
            val paused =
                (
                    fixture.store.live.getExpandedSequence(
                        fixture.started.execution.id,
                    ) as ExpandedLiveSequenceRead.Active
                ).value
            val frozen =
                com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline.capture(
                    paused.runtime,
                    com.alexandr5476.lifetracing.domain
                        .WallMonotonicAnchor(fixture.now.plusSeconds(2), 0),
                    0,
                )
            compose.runOnIdle { state.value = state.value.copy(sequence = paused, displayBaseline = frozen) }
            compose
                .onNodeWithText(
                    fixture.string(R.string.expanded_sequence_transition_remaining, "00:04"),
                ).assertExists()
            compose.runOnIdle { tick.longValue = 120_000 }
            compose
                .onNodeWithText(
                    fixture.string(R.string.expanded_sequence_transition_remaining, "00:04"),
                ).assertExists()
            compose.onNodeWithText(fixture.string(R.string.expanded_sequence_complete)).assertDoesNotExist()
        }
    }

    @Test
    fun runningRefreshFailureRejectsCallbacksAndPreservesFactsAfterReopen() =
        assertRefreshFailureAfterReopen(ActiveSequenceState.RUNNING_CURRENT)

    @Test
    fun pausedRefreshFailureRejectsCallbacksAndPreservesFactsAfterReopen() =
        assertRefreshFailureAfterReopen(ActiveSequenceState.PAUSED_CURRENT)

    @Test
    fun waitingRefreshFailureRejectsCallbacksAndPreservesFactsAfterReopen() =
        assertRefreshFailureAfterReopen(ActiveSequenceState.WAITING_NEXT)

    @Test
    fun countdownRefreshFailureRejectsCallbacksAndPreservesFactsAfterReopen() =
        assertRefreshFailureAfterReopen(ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN)

    @Test
    fun pausedCountdownRefreshFailureRejectsCallbacksAndPreservesFactsAfterReopen() =
        assertRefreshFailureAfterReopen(ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN)

    private fun assertRefreshFailureAfterReopen(initialState: ActiveSequenceState) {
        val countdown =
            initialState == ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN ||
                initialState == ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
        Fixture(
            settings =
                SequenceTemplateSettings(
                    autoAdvance = countdown,
                    beforeEachStepCountdown =
                        if (countdown) java.time.Duration.ofSeconds(30) else java.time.Duration.ZERO,
                ),
            initialState = initialState,
        ).use { fixture ->
            fixture.mount()
            assertEquals(
                initialState,
                fixture.controller.state.value.sequence!!
                    .state,
            )
            val before = fixture.active()
            val facts = fixture.store.durableFacts()
            val target =
                before.execution.occurrences
                    .last()
                    .id
            fixture.controller.requestEndEarly()
            compose.onNodeWithText(fixture.string(R.string.expanded_sequence_confirm)).assertExists()
            val confirmationClick =
                compose
                    .onNodeWithText(fixture.string(R.string.expanded_sequence_confirm))
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action!!
            val pending = kotlinx.coroutines.CompletableDeferred<ExpandedLiveSequenceRead>()
            fixture.readGate.set(pending)
            compose.runOnIdle { fixture.controller.refresh() }

            fun attemptStaleCallbacks() {
                compose.runOnIdle {
                    confirmationClick()
                    fixture.controller.confirmPending()
                    fixture.controller.pause()
                    fixture.controller.resume()
                    fixture.controller.completeCurrent()
                    fixture.controller.startNext()
                    fixture.controller.requestGoNow(target)
                    fixture.controller.makeNext(target)
                    fixture.controller.doAgain(
                        before.execution.occurrences
                            .first()
                            .id,
                        RuntimeInsertionPlacement.TO_END,
                    )
                    fixture.controller.runtimeAddTemplate(fixture.activity.id, RuntimeInsertionPlacement.TO_END)
                    fixture.controller.requestEndEarly()
                    assertEquals(0, fixture.writes.get())
                    assertTrue(fixture.commands.isEmpty())
                    assertTrue(!fixture.controller.state.value.commandInFlight)
                    assertNull(fixture.controller.state.value.confirmation)
                }
            }
            attemptStaleCallbacks()
            compose.onNodeWithText(fixture.string(R.string.expanded_sequence_confirm)).assertDoesNotExist()
            compose.runOnIdle { pending.completeExceptionally(IllegalStateException("refresh failed")) }
            compose.waitUntil(10_000) { fixture.controller.state.value.readFailure != null }
            attemptStaleCallbacks()
            for (unmerged in listOf(false, true)) {
                assertEquals(
                    2,
                    compose
                        .onAllNodes(
                            hasClickAction() and isEnabled(),
                            useUnmergedTree = unmerged,
                        ).fetchSemanticsNodes()
                        .size,
                )
            }
            assertEquals(before, fixture.active())
            assertEquals(facts, fixture.store.durableFacts())

            // A real external runtime command changes which action the retry may expose.
            val changeAt = fixture.now.plusSeconds(4)
            when (initialState) {
                ActiveSequenceState.RUNNING_CURRENT,
                ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN,
                -> fixture.store.live.pauseActiveSequence(before.execution.id, changeAt)
                ActiveSequenceState.PAUSED_CURRENT,
                ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
                -> fixture.store.live.resumeActiveSequence(before.execution.id, changeAt)
                ActiveSequenceState.WAITING_NEXT ->
                    fixture.store.live.startNextSequenceStep(before.execution.id, changeAt)
            }
            val canonical =
                fixture.store.live.getExpandedSequence(before.execution.id) as ExpandedLiveSequenceRead.Active
            fixture.readGate.set(null)
            fixture.invoke(R.string.expanded_sequence_retry)
            compose.waitUntil(10_000) {
                !fixture.controller.state.value.loading && fixture.controller.state.value.sequence == canonical.value
            }
            compose.onNodeWithText(fixture.string(R.string.expanded_sequence_confirm)).assertDoesNotExist()
            fixture.controller.confirmPending()
            assertTrue(fixture.commands.isEmpty())
            val paused =
                canonical.value.state == ActiveSequenceState.PAUSED_CURRENT ||
                    canonical.value.state == ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN
            val authorized = if (paused) R.string.expanded_sequence_resume else R.string.expanded_sequence_pause
            val forbidden = if (paused) R.string.expanded_sequence_pause else R.string.expanded_sequence_resume
            compose.onNodeWithText(fixture.string(forbidden)).assertDoesNotExist()
            fixture.clock.set(changeAt)
            fixture.invoke(authorized)
            fixture.awaitWrites(1)
            assertEquals(
                if (paused) ExpandedSequenceCommand.Resume::class else ExpandedSequenceCommand.Pause::class,
                fixture.commands.single()::class,
            )
            val after = fixture.active()
            assertEquals(before.execution.id, after.execution.id)
            assertEquals(before.execution.occurrences.map { it.id }, after.execution.occurrences.map { it.id })
            assertEquals(before.snapshot, after.snapshot)
            assertEquals(before.activitySnapshots, after.activitySnapshots)
            assertEquals(fixture.plan.id, after.execution.planEntryId)
            val finalFacts = fixture.store.durableFacts()
            fixture.reopen()
            assertEquals(after, fixture.active())
            assertEquals(finalFacts, fixture.store.durableFacts())
            val reopened = fixture.store.live.getExpandedSequence(after.execution.id) as ExpandedLiveSequenceRead.Active
            assertEquals(after, reopened.value.runtime)
            assertEquals(
                PlanEntryStatus.PLANNED,
                fixture.store.plans
                    .getPlan(fixture.plan.id)!!
                    .status,
            )
            assertTrue(
                fixture.store.planReads
                    .getFocusedAction(fixture.plan.id)
                    .engaged,
            )
            assertEquals(1, fixture.writes.get())
        }
    }

    @Test
    fun foregroundRefreshRetainsRuntimeAddDraftAndAddsOnceAfterPhysicalReopen() {
        Fixture().use { fixture ->
            fixture.mount()
            keyboardInput.enter()
            val unrelated =
                fixture.store.activityCommands.addManualTimed(
                    com.alexandr5476.lifetracing.domain.ActivityEntrySource.OneOff(
                        com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft(
                            "Unrelated history",
                            null,
                            TimeTrackingMode.STOPWATCH,
                            null,
                        ),
                    ),
                    fixture.now.minusSeconds(200),
                    fixture.now.minusSeconds(100),
                    fixture.now,
                    ZoneOffset.UTC,
                )
            val before = fixture.active()
            val originalFacts = fixture.store.durableFacts()
            compose
                .onNodeWithContentDescription(fixture.string(R.string.expanded_sequence_global_actions))
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            fixture.invoke(R.string.expanded_sequence_runtime_add)
            compose
                .onNodeWithText(fixture.string(R.string.expanded_sequence_name))
                .performTextReplacement("Foreground draft")
            fixture.invoke(R.string.expanded_sequence_timer)
            fixture.invoke(R.string.expanded_sequence_after_current)
            val seconds = compose.onNodeWithText(fixture.string(R.string.expanded_sequence_timer_seconds))
            seconds.performScrollTo().performTextReplacement("137")
            seconds.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            seconds.assertIsFocused()
            val pending = fixture.holdForegroundRefresh()
            compose.onNodeWithText(fixture.string(R.string.expanded_sequence_add)).assertDoesNotExist()
            compose.onNodeWithText(fixture.string(R.string.expanded_sequence_loading)).assertExists()
            assertEquals(originalFacts, fixture.store.durableFacts())
            fixture.releaseRefresh(pending)
            compose.onNodeWithText("Foreground draft").assertExists()
            compose.onNodeWithText(fixture.string(R.string.expanded_sequence_timer)).assertIsSelected()
            compose.onNodeWithText(fixture.string(R.string.expanded_sequence_after_current)).assertIsSelected()
            seconds.assertTextContains("137").assertIsFocused()
            assertEquals(0, fixture.writes.get())
            assertTrue(fixture.commands.isEmpty())
            assertEquals(originalFacts, fixture.store.durableFacts())
            fixture.invoke(R.string.expanded_sequence_add)
            fixture.awaitWrites(1)
            val after = fixture.active()
            val added = after.execution.occurrences.single { it.isRuntimeAdded }
            val snapshot = after.activitySnapshots.getValue(added.activitySnapshotId)
            assertEquals("Foreground draft", snapshot.name)
            assertEquals(TimeTrackingMode.TIMER, snapshot.timeTrackingMode)
            assertEquals(java.time.Duration.ofSeconds(137), snapshot.timerTarget)
            assertNull(added.sourceSequenceSnapshotNodeId)
            assertNull(added.repeatSourceSnapshotNodeId)
            assertNull(added.repeatIteration)
            assertNull(snapshot.sourceTemplateId)
            assertEquals(
                before.execution.occurrences.map {
                    it.id
                },
                after.execution.occurrences
                    .filterNot { it.isRuntimeAdded }
                    .map { it.id },
            )
            before.activitySnapshots.forEach { (id, frozen) ->
                assertEquals(frozen, after.activitySnapshots.getValue(id))
            }
            assertEquals(before.execution.currentOccurrenceId, after.execution.currentOccurrenceId)
            assertEquals(1, added.runtimePosition)
            assertEquals(before.execution.id, after.execution.id)
            assertEquals(before.snapshot, after.snapshot)
            assertEquals(before.currentChild, after.currentChild)
            assertEquals(fixture.plan.id, after.execution.planEntryId)
            val command = fixture.commands.single() as ExpandedSequenceCommand.RuntimeAdd
            assertEquals(before.execution.id, command.executionId)
            assertEquals(RuntimeInsertionPlacement.AFTER_CURRENT, command.placement)
            val facts = fixture.store.durableFacts()
            fixture.reopen()
            assertEquals(after, fixture.active())
            assertEquals(facts, fixture.store.durableFacts())
            assertEquals(unrelated, fixture.store.activity(unrelated.id))
            assertEquals(
                PlanEntryStatus.PLANNED,
                fixture.store.plans
                    .getPlan(fixture.plan.id)!!
                    .status,
            )
        }
    }

    @Test
    fun foregroundRefreshRetainsNumberFocusAndDeepScrollWithoutWritesAfterReopen() =
        assertEditorForegroundRefresh(text = false)

    @Test
    fun foregroundRefreshRetainsTextFocusAndDeepScrollWithoutWritesAfterReopen() =
        assertEditorForegroundRefresh(text = true)

    private fun assertEditorForegroundRefresh(text: Boolean) {
        Fixture(withValues = true, withText = text, stepCount = 80, currentPosition = 40).use { fixture ->
            fixture.mount()
            keyboardInput.enter()
            val before = fixture.active()
            val facts = fixture.store.durableFacts()
            val label =
                if (text) {
                    "Notes"
                } else {
                    fixture.string(
                        R.string.expanded_sequence_main_value_field,
                        "Quantity kg",
                    )
                }
            val matcher = hasText(label) and hasSetTextAction()
            val editor = compose.onLazyNode(matcher)
            editor.performTextReplacement(if (text) "Uncommitted notes" else "73")
            editor.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            editor.assertIsFocused()
            compose.waitForIdle()
            val top = editor.fetchSemanticsNode().boundsInRoot.top
            val scroll =
                compose
                    .onNode(hasScrollToIndexAction())
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                    .value()
            val draft = fixture.controller.state.value.currentValueDraft
            val pending = fixture.holdForegroundRefresh()
            compose.onNode(matcher).assertDoesNotExist()
            fixture.releaseRefresh(pending)
            // Do not scroll to the editor here: that would conceal a reset to the header.
            compose
                .onNode(matcher)
                .assertIsDisplayed()
                .assertIsFocused()
                .assertTextContains(if (text) "Uncommitted notes" else "73")
            assertEquals(
                top,
                compose
                    .onNode(matcher)
                    .fetchSemanticsNode()
                    .boundsInRoot.top,
                1f,
            )
            assertEquals(
                scroll,
                compose
                    .onNode(hasScrollToIndexAction())
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                    .value(),
                1f,
            )
            assertEquals(draft, fixture.controller.state.value.currentValueDraft)
            assertTrue(fixture.commands.isEmpty())
            assertEquals(0, fixture.writes.get())
            assertEquals(facts, fixture.store.durableFacts())
            fixture.reopen()
            assertEquals(before, fixture.active())
            assertEquals(facts, fixture.store.durableFacts())
        }
    }

    private inner class Fixture(
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        withValues: Boolean = false,
        withText: Boolean = false,
        stepCount: Int = 3,
        currentPosition: Int = 0,
        settings: SequenceTemplateSettings = SequenceTemplateSettings(autoAdvance = false),
        initialState: ActiveSequenceState = ActiveSequenceState.RUNNING_CURRENT,
    ) : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "expanded-accessibility-${UUID.randomUUID()}.db"
        val now = Instant.parse("2026-10-20T12:00:00Z")
        var store = RuntimeRecoveryStore(context, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val generation = MutableStateFlow(0L)
        val clock = AtomicReference(now)
        val writes = AtomicInteger()
        val commands = java.util.concurrent.CopyOnWriteArrayList<ExpandedSequenceCommand>()
        val staleExits = AtomicInteger()
        val visible = mutableStateOf(true)
        val activity =
            store.authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Repeated activity",
                    null,
                    mode,
                    if (mode == TimeTrackingMode.TIMER) java.time.Duration.ofSeconds(60) else null,
                    fields =
                        if (withValues) {
                            listOf(
                                com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                                    DraftIdentity.New("quantity"),
                                    0,
                                    "Quantity",
                                    com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                                    unit = "kg",
                                    displayPrecision = 0,
                                    defaultNumberScaled = 0,
                                    isMainValue = true,
                                ),
                                com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                                    DraftIdentity.New("zero"),
                                    1,
                                    "Zero",
                                    com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                                    displayPrecision = 0,
                                    defaultNumberScaled = 0,
                                ),
                                com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                                    DraftIdentity.New("optional"),
                                    2,
                                    "Optional",
                                    com.alexandr5476.lifetracing.domain.CustomFieldType.NUMBER,
                                    displayPrecision = 0,
                                ),
                            ) +
                                if (withText) {
                                    listOf(
                                        com.alexandr5476.lifetracing.domain.ActivityFieldDraft(
                                            DraftIdentity.New("notes"),
                                            3,
                                            "Notes",
                                            com.alexandr5476.lifetracing.domain.CustomFieldType.TEXT,
                                        ),
                                    )
                                } else {
                                    emptyList()
                                }
                        } else {
                            emptyList()
                        },
                    settings =
                        com.alexandr5476.lifetracing.domain.ActivityTemplateSettings(
                            timerZeroBehavior = com.alexandr5476.lifetracing.domain.TimerZeroBehavior.OVERTIME,
                        ),
                ),
                createdAt = now,
            )
        val template =
            store.authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Accessible plan sequence",
                    null,
                    settings = settings,
                    nodes =
                        (0 until stepCount).map { position ->
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    DraftIdentity.New("step-$position"),
                                    position,
                                    StepActivityDraft.FromTemplate(activity.id),
                                ),
                            )
                        },
                ),
                createdAt = now,
            )
        val plan =
            store.plans.createSequencePlanFromTemplate(
                template.id,
                PlanSchedule.FloatingDay(now.atZone(ZoneOffset.UTC).toLocalDate()),
                now,
            )
        val started =
            store.live.startSequenceFromPlan(plan.actionIdentity(), now, now, ZoneOffset.UTC).also { runtime ->
                if (currentPosition != 0) {
                    store.live.goNow(
                        runtime.execution.id,
                        runtime.execution.occurrences[currentPosition].id,
                        now.plusSeconds(1),
                    )
                }
                when (initialState) {
                    ActiveSequenceState.RUNNING_CURRENT -> Unit
                    ActiveSequenceState.PAUSED_CURRENT ->
                        store.live.pauseActiveSequence(runtime.execution.id, now.plusSeconds(1))
                    ActiveSequenceState.WAITING_NEXT,
                    ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN,
                    ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
                    -> {
                        store.live.completeCurrentSequenceStep(
                            runtime.execution.currentOccurrenceId!!,
                            now.plusSeconds(1),
                        )
                        if (initialState == ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN) {
                            store.live.pauseActiveSequence(runtime.execution.id, now.plusSeconds(2))
                        }
                    }
                }
            }
        val readGate = AtomicReference<kotlinx.coroutines.CompletableDeferred<ExpandedLiveSequenceRead>?>(null)
        val controller =
            ExpandedLiveSequenceController(
                scope,
                started.execution.id,
                { id -> readGate.get()?.await() ?: store.live.getExpandedSequence(id) },
                { command ->
                    commands += command
                    executeExpandedSequenceCommand(command, store.live)
                    writes.incrementAndGet()
                },
                { generation.value++ },
                generation,
                { null },
                { after -> store.library.getReusableActivityCatalog(50, after) },
                { clock.get() },
            )

        fun holdForegroundRefresh(): kotlinx.coroutines.CompletableDeferred<ExpandedLiveSequenceRead> {
            val pending = kotlinx.coroutines.CompletableDeferred<ExpandedLiveSequenceRead>()
            readGate.set(pending)
            compose.runOnIdle { generation.value++ }
            compose.waitUntil(10_000) { controller.state.value.loading }
            compose.waitForIdle()
            return pending
        }

        fun releaseRefresh(pending: kotlinx.coroutines.CompletableDeferred<ExpandedLiveSequenceRead>) {
            val canonical = store.live.getExpandedSequence(started.execution.id)
            readGate.set(null)
            compose.runOnIdle { pending.complete(canonical) }
            compose.waitUntil(10_000) { !controller.state.value.loading }
            compose.waitForIdle()
        }

        fun active(): ActiveSequenceRuntime = store.live.getActiveRuntime() as ActiveSequenceRuntime

        fun mount() {
            compose.setContent {
                LifeTracingTheme {
                    if (visible.value) {
                        ExpandedLiveSequenceRoute(
                            controller,
                            onBack = { visible.value = false },
                            onStale = {
                                staleExits.incrementAndGet()
                                visible.value = false
                            },
                        )
                    }
                }
            }
            compose.waitUntil(10_000) { !controller.state.value.loading }
        }

        fun string(
            id: Int,
            vararg args: Any,
        ): String = context.getString(id, *args)

        fun invoke(id: Int) {
            clock.updateAndGet { it.plusSeconds(1) }
            val matcher = hasText(string(id))
            val node =
                if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
                    compose.onLazyNode(matcher)
                } else {
                    compose.onNode(matcher)
                }
            node.performSemanticsAction(SemanticsActions.OnClick) { it() }
        }

        fun awaitWrites(expected: Int) {
            compose.waitUntil(10_000) {
                writes.get() == expected && !controller.state.value.commandInFlight
            }
            // Structural commands need not advance updatedAt. Wait for publication of
            // the complete canonical projection after the explicitly observed commit.
            val canonical = store.live.getExpandedSequence(started.execution.id)
            compose.waitUntil(10_000) {
                val state = controller.state.value
                !state.loading &&
                    state.commandFailure == null &&
                    when (canonical) {
                        is ExpandedLiveSequenceRead.Active -> state.sequence == canonical.value
                        ExpandedLiveSequenceRead.StaleOrInactive -> state.stale
                    }
            }
            compose.waitForIdle()
        }

        fun reopen() {
            compose.runOnIdle {
                visible.value = false
                controller.close()
            }
            scope.cancel()
            compose.waitForIdle()
            store.close()
            store = RuntimeRecoveryStore(context, name)
        }

        override fun close() {
            compose.runOnIdle {
                visible.value = false
                controller.close()
            }
            scope.cancel()
            store.close()
            context.deleteDatabase(name)
        }
    }
}
