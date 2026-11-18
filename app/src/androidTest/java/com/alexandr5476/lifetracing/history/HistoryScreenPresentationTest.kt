package com.alexandr5476.lifetracing.history

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCategoryOption
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryDetail
import com.alexandr5476.lifetracing.domain.ActivityHistoryField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionCommand
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryChildActivity
import com.alexandr5476.lifetracing.domain.SequenceHistoryChildMutationFacts
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrence
import com.alexandr5476.lifetracing.domain.SequenceHistoryOccurrenceActivity
import com.alexandr5476.lifetracing.domain.SequenceHistoryTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.ConcurrentModificationException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class HistoryScreenPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(composeTestRule)

    private val admissionScopes = mutableListOf<CoroutineScope>()

    @After
    fun closeAdmissionScopes() {
        admissionScopes.forEach { it.cancel() }
    }

    @Test
    fun sequenceDisposedRouteCannotReviveCapturedNativeControls() {
        val f = admissionRoute()
        val button = sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo()
        val click = button.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        composeTestRule.runOnIdle { f.present.value = false }
        button.assertDoesNotExist()
        composeTestRule.runOnIdle {
            click()
            f.present.value = true
        }
        val reopened = sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().assertIsEnabled()
        composeTestRule.runOnIdle {
            click()
            assertEquals(null, f.controller.state.value.timingDraft)
        }
        reopened.performClick()
        composeTestRule.runOnIdle { assertTrue(f.controller.state.value.timingDraft != null) }
    }

    @Test
    fun sequenceNativeActionsStayRevokedAfterHiddenForegroundRoundTripBeforeFrame() {
        val f = admissionRoute()
        val button = sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo()
        val click = button.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        composeTestRule.runOnIdle {
            f.visible.value = false
            click()
            f.visible.value = true
            click()
            assertEquals(null, f.controller.state.value.timingDraft)
        }
        keyboard.enter(button)
        button.performScrollTo().assertIsEnabled().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        keyboard.awaitFocus(button)
        button.performKeyInput { pressKey(Key.Enter) }
        composeTestRule.runOnIdle { assertTrue(f.controller.state.value.timingDraft != null) }
    }

    @Test
    fun sequencePauseResumeBeforeFrameRevokesCapturedNativeAction() {
        val f = admissionRoute()
        val button = sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo()
        val click = button.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        composeTestRule.runOnIdle {
            f.lifecycle.registry.currentState = Lifecycle.State.STARTED
            f.lifecycle.registry.currentState = Lifecycle.State.RESUMED
            click()
            assertEquals(null, f.controller.state.value.timingDraft)
        }
        button.assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertTrue(f.controller.state.value.timingDraft != null) }
    }

    @Test
    fun sequencePublicationCancellationAndSessionReplacementRevokeNativeControls() {
        val f = admissionRoute()
        val button = sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo()
        val click = button.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        button.performClick()
        composeTestRule.runOnIdle {
            assertTrue(f.controller.state.value.timingDraft != null)
            f.controller.dispatch(SequenceHistoryMutationAction.Cancel)
            click()
        }
        composeTestRule.runOnIdle { assertEquals(null, f.controller.state.value.timingDraft) }
        val current =
            sequenceItem("sequence:timing", "sequence-history-timing")
                .performScrollTo()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeTestRule.runOnIdle {
            val old = f.session.value
            f.session.value = SequenceHistoryMutationRouteSession(old.executionId, f.createController())
            current()
            assertEquals(null, old.controller.state.value.timingDraft)
        }
        composeTestRule.waitUntil(2_000) { f.session.value.controller.state.value.load is HistoryDetailLoad.Content }
        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertTrue(f.session.value.controller.state.value.timingDraft != null) }
    }

    @Test
    fun sequenceNativeTimestampAcceptsRepeatedEventsAndRejectsReviewedOrDisposedEditor() {
        val f = admissionRoute()
        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-timing-list").performScrollToKey("root-ended")
        val field = composeTestRule.onNodeWithTag("sequence-history-timestamp-root-ended").performScrollTo()
        val edit = field.fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        composeTestRule.runOnIdle {
            assertTrue(edit(AnnotatedString("2026-07-01T10:30:00")))
            assertTrue(edit(AnnotatedString("2026-07-01T10:31:00")))
            assertEquals(
                "2026-07-01T10:31:00",
                f.controller.state.value.timingDraft!!
                    .timestamps[SequenceHistoryTimestampTarget.RootEndedAt]
                    ?.text,
            )
            f.controller.dispatch(SequenceHistoryMutationAction.Cancel)
            assertFalse(edit(AnnotatedString("stale")))
        }
        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-timing-list").performScrollToKey("root-ended")
        val fresh =
            field
                .performScrollTo()
                .fetchSemanticsNode()
                .config[SemanticsActions.SetText]
                .action!!
        composeTestRule.runOnIdle { f.visible.value = false }
        field.assertIsNotEnabled()
        composeTestRule.runOnIdle {
            assertFalse(fresh(AnnotatedString("hidden")))
            f.visible.value = true
        }
        field.assertIsEnabled()
    }

    @Test
    fun sequencePendingWriterDisablesNativeBackAndDuplicateConfirmation() {
        val gate = CompletableDeferred<Unit>()
        val f = admissionRoute(write = { gate.await() })
        sequenceItem("occurrence:performed", "sequence-history-delete-child-performed").performScrollTo().performClick()
        val confirm = composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").performScrollTo()
        val click = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val back =
            composeTestRule
                .onNodeWithText(text(R.string.history_back))
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        confirm.performScrollTo().performClick()
        composeTestRule.runOnIdle {
            click()
            back()
            assertTrue(f.controller.state.value.isMutating)
            assertEquals(1, f.writes)
        }
        confirm.assertIsNotEnabled()
        composeTestRule.onNodeWithTag("sequence-child-deletion-cancel").assertIsNotEnabled()
        composeTestRule.onNodeWithText(text(R.string.history_back)).assertIsNotEnabled()
        composeTestRule.runOnIdle { gate.complete(Unit) }
        composeTestRule.onNodeWithText(text(R.string.history_back)).assertIsEnabled()
    }

    @Test
    fun sequenceCommittedReadFailureOffersNativeReadOnlyRetry() {
        val f = admissionRoute()
        composeTestRule.runOnIdle {
            f.write = {
                val selected =
                    f.detail.occurrences
                        .first()
                        .occurrenceId
                f.detail =
                    f.detail.copy(
                        updatedAt = f.detail.updatedAt.plusMillis(1),
                        occurrences =
                            f.detail.occurrences.map {
                                if (it.occurrenceId ==
                                    selected
                                ) {
                                    it.copy(status = RuntimeOccurrenceStatus.DELETED_EXECUTION, child = null)
                                } else {
                                    it
                                }
                            },
                    )
                f.failReads = true
            }
        }
        sequenceItem("occurrence:performed", "sequence-history-delete-child-performed").performScrollTo().performClick()
        val click =
            composeTestRule
                .onNodeWithTag("sequence-history-confirm-delete-child")
                .performScrollTo()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeTestRule.runOnIdle { click() }
        composeTestRule
            .onNodeWithText(
                text(R.string.sequence_timing_committed_read_failure, text(R.string.sequence_timing_child_deleted)),
            ).assertExists()
        val retry = composeTestRule.onNodeWithTag("history-retry").performScrollTo().assertIsEnabled()
        val oldRetry = retry.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        retry.performClick()
        retry.assertIsEnabled()
        composeTestRule.runOnIdle {
            f.failReads = false
            oldRetry()
            assertTrue(f.controller.state.value.load is HistoryDetailLoad.Failure)
            click()
        }
        keyboard.enter(retry)
        retry.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        keyboard.awaitFocus(retry)
        retry.performKeyInput { pressKey(Key.Enter) }
        composeTestRule.runOnIdle {
            assertEquals(1, f.writes)
            assertEquals(1L, f.controller.state.value.refreshGeneration)
            assertTrue(f.controller.state.value.load is HistoryDetailLoad.Content)
            assertEquals(1, f.refreshes)
            assertEquals(SequenceHistoryCommittedMutation.CHILD_DELETION, f.controller.state.value.committedMutation)
        }
        sequenceItem("occurrence:performed", "sequence-deleted-performed").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-child-child").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sequence-history-delete-child-performed").assertDoesNotExist()
    }

    @Test
    fun sequenceReadOnlyNavigationDisablesHiddenControlsAndRevokesCapturedSection() {
        val f = admissionRoute()
        sequenceItem("sequence:navigation", "sequence-history-go-position").performTextReplacement("1")
        composeTestRule.onNodeWithTag("sequence-history-go").assertIsEnabled()
        val section = sequenceItem("sequence:navigation", "sequence-history-section-intervals")
        val click = requireNotNull(section.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val before = sequenceScrollPosition()
        composeTestRule.runOnIdle { f.visible.value = false }
        section.assertIsNotEnabled()
        composeTestRule.onNodeWithTag("sequence-history-go-position").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("sequence-history-go").assertIsNotEnabled()
        composeTestRule.runOnIdle { click() }
        assertEquals(before, sequenceScrollPosition())
        composeTestRule.runOnIdle { f.visible.value = true }
        section.assertIsEnabled()
        composeTestRule.runOnIdle { click() }
        assertEquals(before, sequenceScrollPosition())
        section.performClick()
        keyboard.awaitFocus(
            composeTestRule.onNode(
                hasText(
                    text(R.string.history_intervals),
                ) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            ),
        )
        composeTestRule.runOnIdle {
            assertEquals(1, f.reads)
            assertEquals(0, f.writes)
            assertEquals(null, f.controller.state.value.timingDraft)
        }
    }

    @Test
    fun sequenceReadOnlyNavigationRevokesPausedDisposedAndReplacedSessions() {
        val f = admissionRoute()
        val section = sequenceItem("sequence:navigation", "sequence-history-section-intervals")
        val paused = requireNotNull(section.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val before = sequenceScrollPosition()
        composeTestRule.runOnIdle {
            f.lifecycle.registry.currentState = Lifecycle.State.STARTED
            f.lifecycle.registry.currentState = Lifecycle.State.RESUMED
            paused()
        }
        assertEquals(before, sequenceScrollPosition())
        val disposed = requireNotNull(section.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        composeTestRule.runOnIdle { f.present.value = false }
        composeTestRule.runOnIdle {
            disposed()
            f.present.value = true
        }
        sequenceItem("sequence:navigation", "sequence-history-section-intervals").assertIsEnabled()
        val replaced = requireNotNull(section.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        composeTestRule.runOnIdle {
            val old = f.session.value
            f.session.value = SequenceHistoryMutationRouteSession(old.executionId, f.createController())
            replaced()
        }
        sequenceItem("sequence:navigation", "sequence-history-section-intervals").assertIsEnabled()
        val replacementPosition = sequenceScrollPosition()
        composeTestRule.runOnIdle {
            paused()
            disposed()
            replaced()
        }
        assertEquals(replacementPosition, sequenceScrollPosition())
        section.performClick()
        keyboard.awaitFocus(
            composeTestRule.onNode(
                hasText(
                    text(R.string.history_intervals),
                ) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            ),
        )
        composeTestRule.runOnIdle { assertEquals(0, f.writes) }
    }

    @Test
    fun sequenceQueuedReadOnlyJumpLosesHiddenRouteBeforeScrollAndCannotStealFocusOnReturn() {
        val f = admissionRoute(foregroundTarget = true)
        val section = sequenceItem("sequence:navigation", "sequence-history-section-intervals")
        val click = requireNotNull(section.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val before = sequenceScrollPosition()
        val foreground = composeTestRule.onNodeWithTag("foreground-focus")
        keyboard.enter(foreground)
        foreground.performSemanticsAction(SemanticsActions.RequestFocus)
        keyboard.awaitFocus(foreground)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.runOnIdle {
            click()
            f.visible.value = false
        }
        composeTestRule.mainClock.autoAdvance = true
        keyboard.awaitFocus(foreground)
        assertEquals(before, sequenceScrollPosition())
        composeTestRule.runOnIdle { f.visible.value = true }
        keyboard.enter(composeTestRule.onNodeWithText(text(R.string.history_back)))
        keyboard.awaitFocus(composeTestRule.onNodeWithText(text(R.string.history_back)))
        assertEquals(before, sequenceScrollPosition())
        composeTestRule.runOnIdle {
            assertEquals(0, f.writes)
            assertEquals(1, f.reads)
        }
    }

    @Test
    fun sequenceQueuedReadOnlyJumpCannotScrollReplacementSessionWithSameExecution() {
        val f = admissionRoute()
        val section = sequenceItem("sequence:navigation", "sequence-history-section-intervals")
        val click = requireNotNull(section.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.runOnIdle {
            click()
            val old = f.session.value
            f.session.value = SequenceHistoryMutationRouteSession(old.executionId, f.createController())
        }
        composeTestRule.mainClock.autoAdvance = true
        sequenceItem("sequence:navigation", "sequence-history-section-intervals").assertIsEnabled()
        keyboard.enter(composeTestRule.onNodeWithText(text(R.string.history_back)))
        keyboard.awaitFocus(composeTestRule.onNodeWithText(text(R.string.history_back)))
        composeTestRule.runOnIdle {
            assertEquals(0, f.writes)
            assertEquals(2, f.reads)
        }
    }

    private fun sequenceScrollPosition(): Float =
        composeTestRule
            .onNodeWithTag("sequence-history-detail-list")
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    @Test
    fun childDeletionNativeConfirmationDisambiguatesEqualFactsAndCancelRetiresConfirm() {
        val base = sequenceMutationDetail()
        val first = base.occurrences.first()
        val second =
            first.copy(
                occurrenceId = SequenceOccurrenceId("equal-second"),
                runtimePosition = 1,
                sourceSequenceSnapshotNodeId = SequenceSnapshotNodeId("second-step"),
                repeatSourceSnapshotNodeId = SequenceSnapshotNodeId("repeat-source"),
                repeatIteration = 2,
                child = first.child!!.copy(executionId = ActivityExecutionId("second-child")),
                childMutationFacts = first.childMutationFacts!!.copy(executionId = ActivityExecutionId("second-child")),
            )
        val f = admissionRoute(detail = base.copy(occurrences = listOf(first, second), intervals = emptyList()))
        sequenceItem(
            "occurrence:equal-second",
            "sequence-history-delete-child-equal-second",
        ).performScrollTo().performClick()
        val heading = composeTestRule.onNodeWithTag("sequence-child-deletion-heading")
        keyboard.enter(heading)
        heading.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        keyboard.awaitFocus(heading)
        heading.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeTestRule
            .onNodeWithText(
                text(R.string.sequence_child_deletion_parent, base.root.title, base.root.executionId.value),
            ).assertExists()
        composeTestRule
            .onNodeWithText(
                text(R.string.sequence_history_occurrence_id, second.occurrenceId.value),
            ).assertExists()
        composeTestRule
            .onNodeWithText(
                text(R.string.sequence_history_activity_snapshot_id, second.activitySnapshotId.value),
            ).assertExists()
        composeTestRule.onNodeWithText(text(R.string.sequence_history_source_id, "second-step")).assertExists()
        composeTestRule.onNodeWithText(text(R.string.sequence_history_repeat_id, "repeat-source")).assertExists()
        composeTestRule.onNodeWithText(text(R.string.history_repeat_iteration, 2)).assertExists()
        composeTestRule
            .onNodeWithTag(
                "sequence-child-deletion-child",
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) {
                it()
            }
        keyboard.awaitFocus(composeTestRule.onNodeWithTag("sequence-child-deletion-child"))
        composeTestRule
            .onNodeWithTag(
                "sequence-child-deletion-consequences",
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) {
                it()
            }
        keyboard.awaitFocus(composeTestRule.onNodeWithTag("sequence-child-deletion-consequences"))
        val confirm =
            composeTestRule
                .onNodeWithTag(
                    "sequence-history-confirm-delete-child",
                ).performScrollTo()
                .assertIsEnabled()
        confirm.assertTextEquals(text(R.string.sequence_child_deletion_confirm, second.activity.title, "second-child"))
        val retained = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        composeTestRule.onNodeWithTag("sequence-child-deletion-cancel").performScrollTo().performClick()
        composeTestRule.runOnIdle {
            retained()
            assertEquals(0, f.writes)
        }
        sequenceItem("occurrence:performed", "sequence-history-delete-child-performed").performScrollTo().performClick()
        composeTestRule.runOnIdle {
            retained()
            assertEquals(0, f.writes)
        }
        val current = composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").performScrollTo()
        current.performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeTestRule.runOnIdle {
            assertEquals(1, f.writes)
            assertEquals(
                listOf(
                    base.root.executionId to
                        com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionCommand(
                            base.updatedAt,
                            first.occurrenceId,
                            first.childMutationFacts!!.executionId,
                        ),
                ),
                f.commands,
            )
        }
    }

    @Test
    fun childDeletionEndedEarlyNoLiveRuntimeAddedDoesNotInventSourceOrDuration() {
        val base = sequenceMutationDetail()
        val first = base.occurrences.first()
        val noLive =
            first.copy(
                sourceSequenceSnapshotNodeId = null,
                repeatSourceSnapshotNodeId = null,
                repeatIteration = null,
                isRuntimeAdded = true,
                activity =
                    first.activity.copy(
                        timeTrackingMode = TimeTrackingMode.NO_LIVE_TRACKING,
                        timerTarget = null,
                    ),
                child = first.child!!.copy(startedAt = null, activeDuration = null),
                childMutationFacts = first.childMutationFacts!!.copy(startedAt = null),
            )
        val f =
            admissionRoute(
                detail =
                    base.copy(
                        root = base.root.copy(status = SequenceExecutionStatus.ENDED_EARLY),
                        occurrences = listOf(noLive),
                        intervals = emptyList(),
                    ),
            )
        sequenceItem("occurrence:performed", "sequence-history-delete-child-performed").performScrollTo().performClick()
        composeTestRule.onNodeWithText(text(R.string.history_runtime_added)).assertExists()
        composeTestRule
            .onNodeWithText(
                text(R.string.sequence_history_source_id, first.sourceSequenceSnapshotNodeId!!.value),
            ).assertDoesNotExist()
        composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").performScrollTo().assertIsEnabled()
        composeTestRule.runOnIdle {
            assertEquals(
                noLive.occurrenceId,
                f.controller.state.value.childDeletionProposal!!
                    .occurrenceId,
            )
            assertEquals(
                noLive.childMutationFacts!!.executionId,
                f.controller.state.value.childDeletionProposal!!
                    .childExecutionId,
            )
            assertEquals(null, noLive.child!!.startedAt)
            assertEquals(null, noLive.child!!.activeDuration)
        }
        composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(0, f.writes)
            assertEquals(0, f.backs)
            assertEquals(null, f.controller.state.value.childDeletionProposal)
        }
    }

    @Test
    fun childDeletionFailedWriterRequiresExplicitFreshRetryAndStaleRequiresNewReview() {
        val f = admissionRoute(write = { error("writer unavailable") })
        sequenceItem("occurrence:performed", "sequence-history-delete-child-performed").performScrollTo().performClick()
        val confirm = composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").performScrollTo()
        val old = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        confirm.performClick()
        composeTestRule
            .onNodeWithText(
                text(R.string.sequence_history_child_deletion_failure),
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule.runOnIdle {
            old()
            assertEquals(1, f.writes)
            f.write =
                { throw java.util.ConcurrentModificationException() }
        }
        confirm.performScrollTo().performClick()
        composeTestRule.runOnIdle {
            assertEquals(2, f.writes)
            assertEquals(null, f.controller.state.value.childDeletionProposal)
            assertEquals(0L, f.controller.state.value.refreshGeneration)
        }
        confirm.assertDoesNotExist()
        composeTestRule.runOnIdle {
            old()
            f.write = {}
        }
        sequenceItem("occurrence:performed", "sequence-history-delete-child-performed").performScrollTo().performClick()
        val fresh = composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").performScrollTo()
        keyboard.enter(fresh)
        fresh.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        keyboard.awaitFocus(fresh)
        fresh.performKeyInput { pressKey(Key.Enter) }
        composeTestRule.runOnIdle {
            assertEquals(3, f.writes)
            assertEquals(1L, f.controller.state.value.refreshGeneration)
        }
    }

    @Test
    fun childDeletionCapturedConfirmCannotReviveAfterOwnershipLossOrSessionReplacement() {
        val f = admissionRoute()

        fun open(): () -> Boolean {
            sequenceItem(
                "occurrence:performed",
                "sequence-history-delete-child-performed",
            ).performScrollTo().performClick()
            return composeTestRule
                .onNodeWithTag(
                    "sequence-history-confirm-delete-child",
                ).performScrollTo()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        }
        var old = open()
        composeTestRule.runOnIdle {
            f.visible.value = false
            old()
            f.visible.value = true
            old()
            assertEquals(0, f.writes)
        }
        composeTestRule.onNodeWithTag("sequence-child-deletion-cancel").performScrollTo().performClick()
        old = open()
        composeTestRule.runOnIdle {
            f.lifecycle.registry.currentState = Lifecycle.State.STARTED
            f.lifecycle.registry.currentState =
                Lifecycle.State.RESUMED
            old()
            assertEquals(0, f.writes)
        }
        composeTestRule.onNodeWithTag("sequence-child-deletion-cancel").performScrollTo().performClick()
        old = open()
        composeTestRule.runOnIdle { f.present.value = false }
        composeTestRule.runOnIdle {
            old()
            f.present.value = true
        }
        composeTestRule.runOnIdle {
            old()
            assertEquals(0, f.writes)
        }
        composeTestRule.onNodeWithTag("sequence-child-deletion-cancel").performScrollTo().performClick()
        old = open()
        composeTestRule.runOnIdle {
            f.session.value = SequenceHistoryMutationRouteSession(f.detail.root.executionId, f.createController())
            old()
            assertEquals(0, f.writes)
        }
        composeTestRule.waitUntil(2_000) { f.session.value.controller.state.value.load is HistoryDetailLoad.Content }
        composeTestRule.runOnIdle {
            old()
            assertEquals(0, f.writes)
        }
        sequenceItem("occurrence:performed", "sequence-history-delete-child-performed").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").performScrollTo().performClick()
        composeTestRule.runOnIdle { assertEquals(1, f.writes) }
    }

    @Test
    fun childDeletionCanonicalTombstoneRemovesActualChildTargetsAndRetainsOtherActions() {
        val f = admissionRoute()
        val selected = f.detail.occurrences.first()
        composeTestRule.runOnIdle {
            f.write = {
                f.detail =
                    f.detail.copy(
                        updatedAt = f.detail.updatedAt.plusMillis(1),
                        occurrences =
                            f.detail.occurrences.map {
                                if (it.occurrenceId ==
                                    selected.occurrenceId
                                ) {
                                    it.copy(status = RuntimeOccurrenceStatus.DELETED_EXECUTION, child = null)
                                } else {
                                    it
                                }
                            },
                    )
            }
        }
        val childSummary = sequenceItem("occurrence:performed", "sequence-child-child").performScrollTo()
        keyboard.enter(childSummary)
        childSummary.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        sequenceItem("occurrence:performed", "sequence-history-delete-child-performed").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").performScrollTo().performClick()
        keyboard.awaitFocus(composeTestRule.onNodeWithText(text(R.string.history_back)))
        sequenceItem("occurrence:performed", "sequence-deleted-performed").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-child-child").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sequence-history-delete-child-performed").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sequence-history-remove-occurrence-performed").assertIsEnabled()
        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().performClick()
        composeTestRule.runOnIdle {
            assertFalse(
                f.controller.state.value.timingDraft!!.timestamps.keys.any {
                    (it is SequenceHistoryTimestampTarget.ChildStartedAt && it.occurrenceId == selected.occurrenceId) ||
                        (
                            it is SequenceHistoryTimestampTarget.ChildCompletedAt &&
                                it.occurrenceId == selected.occurrenceId
                        )
                },
            )
            assertEquals(1, f.writes)
            assertEquals(1, f.refreshes)
        }
    }

    @Test
    fun childDeletionEntryPointerPressCannotCarryIntoNativeConfirmation() {
        val f = admissionRoute()
        val entry = sequenceItem("occurrence:performed", "sequence-history-delete-child-performed").performScrollTo()
        val center = entry.fetchSemanticsNode().boundsInRoot.center
        composeTestRule.onRoot().performTouchInput { down(center) }
        try {
            entry.performSemanticsAction(SemanticsActions.OnClick) { it() }
            composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").assertExists()
        } finally {
            composeTestRule.onRoot().performTouchInput { up() }
        }
        composeTestRule.runOnIdle {
            assertEquals(0, f.writes)
            assertEquals(0L, f.controller.state.value.refreshGeneration)
        }
        composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").performScrollTo().performClick()
        composeTestRule.runOnIdle { assertEquals(1, f.writes) }
    }

    @Test
    fun structuralModesAndConfirmRevokeAcrossCancelHidePauseDisposalAndReplacement() {
        val f = admissionRoute(detail = structuralMutationDetail())

        fun open(): () -> Boolean {
            sequenceItem(
                "occurrence:performed",
                "sequence-history-remove-occurrence-performed",
            ).performScrollTo().performClick()
            keyboard.awaitFocus(composeTestRule.onNodeWithTag("sequence-structural-mode-heading"))
            val mode = composeTestRule.onNodeWithTag("sequence-history-leave-gap")
            val oldMode = mode.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            composeTestRule.onNodeWithTag("sequence-structural-cancel").performClick()
            composeTestRule.runOnIdle { oldMode() }
            assertEquals(null, f.controller.state.value.structuralProposal)
            sequenceItem(
                "occurrence:performed",
                "sequence-history-remove-occurrence-performed",
            ).performScrollTo().performClick()
            composeTestRule
                .onNodeWithTag(
                    "sequence-history-leave-gap",
                ).performSemanticsAction(SemanticsActions.OnClick) {
                    it()
                }
            keyboard.awaitFocus(composeTestRule.onNodeWithTag("sequence-structural-review-heading"))
            return composeTestRule
                .onNodeWithTag(
                    "sequence-history-confirm-structural",
                ).fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        }
        var old = open()
        composeTestRule.onNodeWithTag("sequence-structural-cancel").performClick()
        composeTestRule.runOnIdle {
            old()
            assertEquals(0, f.writes)
        }
        old = open()
        composeTestRule.runOnIdle {
            f.visible.value = false
            old()
            f.visible.value = true
            old()
        }
        composeTestRule.runOnIdle {
            old()
            assertEquals(0, f.writes)
        }
        composeTestRule.onNodeWithTag("sequence-structural-cancel").performClick()
        old = open()
        composeTestRule.runOnIdle {
            f.lifecycle.registry.currentState = Lifecycle.State.STARTED
            old()
            f.lifecycle.registry.currentState =
                Lifecycle.State.RESUMED
            old()
        }
        composeTestRule.runOnIdle {
            old()
            assertEquals(0, f.writes)
        }
        composeTestRule.onNodeWithTag("sequence-structural-cancel").performClick()
        old = open()
        composeTestRule.runOnIdle { f.present.value = false }
        composeTestRule.runOnIdle {
            old()
            f.present.value = true
        }
        composeTestRule.runOnIdle {
            old()
            assertEquals(0, f.writes)
        }
        composeTestRule.onNodeWithTag("sequence-structural-cancel").performClick()
        old = open()
        composeTestRule.runOnIdle {
            f.session.value =
                SequenceHistoryMutationRouteSession(f.detail.root.executionId, f.createController())
            old()
        }
        composeTestRule.waitUntil(2_000) { f.session.value.controller.state.value.load is HistoryDetailLoad.Content }
        composeTestRule.runOnIdle {
            old()
            assertEquals(0, f.writes)
            assertEquals(0, f.refreshes)
        }
    }

    @Test
    fun structuralChoicesAnnounceExactSelectedStateAndOldChoiceCannotReachReplacement() {
        val f = admissionRoute(detail = structuralMutationDetail())
        sequenceItem(
            "occurrence:performed",
            "sequence-history-remove-occurrence-performed",
        ).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-close-gap").performClick()
        composeTestRule.onNodeWithTag("sequence-structural-placements-list").performScrollToKey("choice:ownerless")
        val fixed = composeTestRule.onNodeWithTag("sequence-history-ownerless-ownerless-fixed")
        val stale = fixed.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        keyboard.enter(fixed)
        fixed.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        keyboard.awaitFocus(fixed)
        fixed.performKeyInput { pressKey(Key.Enter) }
        composeTestRule.onNodeWithTag("sequence-history-ownerless-ownerless-fixed").assertIsSelected()
        composeTestRule.onNodeWithTag("sequence-history-confirm-structural").assertDoesNotExist()
        assertEquals(
            OwnerlessIntervalPlacement.FIXED,
            f.controller.state.value.structuralProposal!!
                .ownerlessPlacements[SequenceIntervalId("ownerless")],
        )
        assertEquals(
            null,
            f.controller.state.value.structuralProposal!!
                .ownerlessPlacements[SequenceIntervalId("ownerless-2")],
        )
        composeTestRule.onNodeWithTag("sequence-structural-cancel").performClick()
        sequenceItem(
            "occurrence:performed",
            "sequence-history-remove-occurrence-performed",
        ).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-close-gap").performClick()
        composeTestRule.runOnIdle { stale() }
        assertTrue(
            f.controller.state.value.structuralProposal!!
                .ownerlessPlacements.values
                .all { it == null },
        )
        composeTestRule.onNodeWithText(text(R.string.history_back)).performClick()
        composeTestRule.runOnIdle {
            assertEquals(0, f.writes)
            assertEquals(0, f.refreshes)
        }
    }

    @Test
    fun structuralOverlapApprovalRetiresHeldProceedAndCapturedApprovalCannotConfirm() {
        val base = structuralMutationDetail()
        val detail =
            base.copy(
                intervals =
                    base.intervals + base.intervals.last().copy(id = SequenceIntervalId("overlap")),
            )
        val f = admissionRoute(detail = detail)
        sequenceItem(
            "occurrence:performed",
            "sequence-history-remove-occurrence-performed",
        ).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-leave-gap").performClick()
        val proceed = composeTestRule.onNodeWithTag("sequence-history-overlap-proceed")
        val oldProceed = proceed.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val center = proceed.fetchSemanticsNode().boundsInRoot.center
        composeTestRule.onRoot().performTouchInput { down(center) }
        try {
            proceed.performSemanticsAction(SemanticsActions.OnClick) { it() }
            composeTestRule.onNodeWithTag("sequence-history-confirm-structural").assertIsDisplayed()
        } finally {
            composeTestRule.onRoot().performTouchInput { up() }
        }
        composeTestRule.runOnIdle {
            oldProceed()
            assertEquals(0, f.writes)
            assertEquals(0, f.refreshes)
        }
        composeTestRule
            .onNodeWithTag(
                "sequence-history-confirm-structural",
            ).performSemanticsAction(SemanticsActions.OnClick) {
                it()
            }
        composeTestRule.runOnIdle {
            assertEquals(1, f.writes)
            assertEquals(1, f.refreshes)
        }
    }

    @Test
    fun structuralCommitReadFailureRetriesOnlyReadAndRecoversRemovedOccurrenceFocus() {
        val f = admissionRoute(detail = structuralMutationDetail())
        val original = f.detail
        composeTestRule.runOnIdle {
            f.write = {
                f.detail =
                    original.copy(
                        updatedAt = original.updatedAt.plusMillis(1),
                        occurrences = original.occurrences.filter { it.occurrenceId.value != "performed" },
                        intervals = original.intervals.filter { it.occurrenceId?.value != "performed" },
                    )
                f.failReads = true
            }
        }
        val entry =
            sequenceItem(
                "occurrence:performed",
                "sequence-history-remove-occurrence-performed",
            ).performScrollTo()
        keyboard.enter(entry)
        entry.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        entry.performClick()
        composeTestRule.onNodeWithTag("sequence-history-leave-gap").performClick()
        val confirm = composeTestRule.onNodeWithTag("sequence-history-confirm-structural")
        val stale = confirm.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        confirm.performClick()
        composeTestRule.waitUntil(2_000) { f.controller.state.value.load is HistoryDetailLoad.Failure }
        assertEquals(SequenceHistoryCommittedMutation.STRUCTURAL, f.controller.state.value.committedMutation)
        composeTestRule
            .onNodeWithText(
                text(R.string.sequence_timing_committed_read_failure, text(R.string.sequence_timing_structure_changed)),
            ).assertExists()
        composeTestRule.runOnIdle {
            stale()
            f.failReads = false
        }
        composeTestRule.onNodeWithTag("history-retry").performClick()
        composeTestRule.waitUntil(2_000) { f.controller.state.value.load is HistoryDetailLoad.Content }
        keyboard.awaitFocus(composeTestRule.onNodeWithText(text(R.string.history_back)))
        composeTestRule.onNodeWithTag("sequence-history-remove-occurrence-performed").assertDoesNotExist()
        composeTestRule.runOnIdle {
            assertEquals(1, f.writes)
            assertEquals(1, f.refreshes)
        }
    }

    @Test
    fun structuralLargeSequenceUsesBoundedChoiceAndReviewCompositionWithExactOrdinalNavigation() {
        val base = structuralMutationDetail()
        val sample = base.occurrences.last()
        val occurrences =
            listOf(base.occurrences.first()) +
                List(9_999) { index ->
                    val childId = ActivityExecutionId("large-child-$index")
                    sample.copy(
                        occurrenceId = SequenceOccurrenceId("large-occurrence-$index"),
                        runtimePosition = index + 1,
                        child = sample.child!!.copy(executionId = childId),
                        childMutationFacts =
                            sample.childMutationFacts!!.copy(
                                executionId = childId,
                                pauses =
                                    sample.childMutationFacts!!.pauses.map {
                                        it.copy(
                                            id =
                                                com.alexandr5476.lifetracing.domain
                                                    .ActivityExecutionPauseId("large-pause-$index"),
                                        )
                                    },
                            ),
                    )
                }
        val choices = List(256) { index -> base.intervals.last().copy(id = SequenceIntervalId("large-choice-$index")) }
        val detail =
            base.copy(
                occurrences = occurrences,
                intervals =
                    base.intervals.filter {
                        it.occurrenceId == base.occurrences.first().occurrenceId
                    } + choices,
            )
        val f = admissionRoute(detail = detail)
        sequenceItem(
            "occurrence:performed",
            "sequence-history-remove-occurrence-performed",
        ).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-close-gap").performClick()
        val reads = f.reads

        fun navigate(ordinal: Int) {
            composeTestRule.onNodeWithTag("sequence-structural-navigate").performClick()
            composeTestRule.onNodeWithTag("sequence-structural-ordinal").performTextReplacement(ordinal.toString())
            composeTestRule.onNodeWithTag("sequence-structural-go").performClick()
        }
        for (index in listOf(0, 127, 255)) {
            navigate(index + 1)
            composeTestRule
                .onNodeWithTag(
                    "sequence-history-ownerless-large-choice-$index-fixed",
                ).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.OnClick) {
                    it()
                }
            assertEquals(
                OwnerlessIntervalPlacement.FIXED,
                f.controller.state.value.structuralProposal!!
                    .ownerlessPlacements[
                    SequenceIntervalId(
                        "large-choice-$index",
                    ),
                ],
            )
        }
        composeTestRule.runOnIdle {
            for (index in 0..255) {
                if (index !in listOf(0, 127, 255)) {
                    f.controller.dispatch(
                        SequenceHistoryMutationAction.PlaceOwnerlessInterval(
                            SequenceIntervalId("large-choice-$index"),
                            OwnerlessIntervalPlacement.TRANSLATED,
                        ),
                    )
                }
            }
        }
        val proposal = f.controller.state.value.structuralProposal!!
        assertTrue(proposal.isConfirmable)
        assertTrue(proposal.changes.size > 30_000)
        for (index in listOf(0, proposal.changes.size / 2, proposal.changes.lastIndex)) {
            navigate(index + 1)
            composeTestRule
                .onNodeWithTag(
                    "sequence-structural-change-${structuralChangeKey(proposal.changes[index])}",
                ).assertIsDisplayed()
        }
        val composed =
            composeTestRule
                .onAllNodes(
                    SemanticsMatcher("structural review fact") {
                        it.config
                            .getOrElse(
                                SemanticsProperties.TestTag,
                            ) { "" }
                            .startsWith("sequence-structural-change-")
                    },
                ).fetchSemanticsNodes()
                .size
        assertTrue(composed < 40)
        composeTestRule.runOnIdle {
            assertEquals(reads, f.reads)
            assertEquals(0, f.writes)
            assertEquals(0, f.refreshes)
        }
    }

    @Test
    fun structuralFailureRetryAndStaleRecoveryRequireNewCurrentDecision() {
        val f = admissionRoute(detail = structuralMutationDetail())
        var failure: Exception = IllegalStateException("writer failed")
        composeTestRule.runOnIdle { f.write = { throw failure } }
        sequenceItem(
            "occurrence:performed",
            "sequence-history-remove-occurrence-performed",
        ).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-leave-gap").performClick()
        val obsolete =
            composeTestRule
                .onNodeWithTag(
                    "sequence-history-confirm-structural",
                ).fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeTestRule.onNodeWithTag("sequence-history-confirm-structural").performClick()
        composeTestRule.waitUntil(2_000) {
            f.controller.state.value.issue ==
                SequenceHistoryMutationIssue.STRUCTURAL_FAILURE
        }
        composeTestRule.onNodeWithText(text(R.string.sequence_history_structural_failure)).assertIsDisplayed()
        composeTestRule.runOnIdle {
            obsolete()
            assertEquals(1, f.writes)
            failure = IllegalArgumentException("graph")
        }
        composeTestRule
            .onNodeWithTag(
                "sequence-history-confirm-structural",
            ).performSemanticsAction(SemanticsActions.OnClick) {
                it()
            }
        composeTestRule.waitUntil(2_000) {
            f.controller.state.value.issue ==
                SequenceHistoryMutationIssue.INVALID_PROPOSAL
        }
        assertEquals(0L, f.controller.state.value.refreshGeneration)
        composeTestRule.runOnIdle {
            failure = ConcurrentModificationException("stale")
            f.detail =
                f.detail.copy(updatedAt = f.detail.updatedAt.plusSeconds(1))
        }
        composeTestRule.onNodeWithTag("sequence-history-confirm-structural").performClick()
        composeTestRule.waitUntil(2_000) {
            f.controller.state.value.issue == SequenceHistoryMutationIssue.STALE &&
                f.controller.state.value.load is HistoryDetailLoad.Content
        }
        composeTestRule.runOnIdle {
            obsolete()
            assertEquals(3, f.writes)
            assertEquals(0, f.refreshes)
        }
        assertEquals(null, f.controller.state.value.structuralTarget)
        assertEquals(null, f.controller.state.value.structuralProposal)
        composeTestRule.onNodeWithTag("sequence-history-confirm-structural").assertDoesNotExist()
    }

    private fun admissionRoute(
        write: suspend () -> Unit = {},
        foregroundTarget: Boolean = false,
        detail: SequenceHistoryDetail = sequenceMutationDetail(),
    ): SequenceAdmissionRouteFixture {
        val f = SequenceAdmissionRouteFixture(detail, write)
        admissionScopes += f.scope
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides f.lifecycle) {
                LifeTracingTheme {
                    val route: @Composable () -> Unit = {
                        val current = f.session.value
                        if (f.present.value) {
                            SequenceHistoryDetailRoute(
                                current,
                                { f.backs++ },
                                { f.refreshes++ },
                                f.visible.value,
                                { f.visible.value && f.session.value === current },
                            )
                        }
                    }
                    if (foregroundTarget) {
                        Column(Modifier.fillMaxSize()) {
                            TextButton({}, Modifier.testTag("foreground-focus")) { Text("Foreground route") }
                            Box(Modifier.weight(1f)) { route() }
                        }
                    } else {
                        route()
                    }
                }
            }
        }
        composeTestRule.waitUntil(2_000) { f.controller.state.value.load is HistoryDetailLoad.Content }
        return f
    }

    private class SequenceAdmissionRouteFixture(
        var detail: SequenceHistoryDetail,
        var write: suspend () -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val visible = mutableStateOf(true)
        val present = mutableStateOf(true)
        val lifecycle = SequenceAdmissionLifecycle()
        var failReads = false
        var reads = 0
        var writes = 0
        var refreshes = 0
        var backs = 0
        val commands =
            mutableListOf<Pair<SequenceExecutionId, SequenceChildHistoryDeletionCommand>>()
        val controller = createController()
        val session = mutableStateOf(SequenceHistoryMutationRouteSession(detail.root.executionId, controller))

        fun createController() =
            SequenceHistoryMutationController(
                scope,
                detail.root.executionId,
                {
                    reads++
                    if (failReads) error("read failure") else detail
                },
                { _, _, _ -> error("unexpected timing writer") },
                { id, command, _ ->
                    commands += id to command
                    writes++
                    write()
                },
                { _, _, _ ->
                    writes++
                    write()
                },
            )
    }

    private class SequenceAdmissionLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun repeatedHourDetailShowsEachEndpointsHistoricalOffset() {
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        try {
            val zone = ZoneId.of("Europe/Berlin")
            val detail = activityDetail(zone = zone)
            val crossing =
                detail.copy(
                    root =
                        detail.root.copy(
                            startedAt = Instant.parse("2026-10-25T00:30:00Z"),
                            completedAt = Instant.parse("2026-10-25T01:30:00Z"),
                            activeDuration = Duration.ofHours(1),
                        ),
                )
            setDetail { ActivityDetail(crossing) }
            composeTestRule.onNodeWithText("UTC+02:00", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("UTC+01:00", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("Europe/Berlin", substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("Asia/Tokyo", substring = true).assertDoesNotExist()
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun localizedNoLiveRootsDisambiguateOverlapAndSequenceChildrenUseOwnZones() {
        val previousZone = TimeZone.getDefault()
        val russian = Locale.forLanguageTag("ru")
        val locale = mutableStateOf(Locale.US)
        val detailMode = mutableStateOf(false)
        val berlin = ZoneId.of("Europe/Berlin")
        val first = Instant.parse("2026-10-25T00:30:00Z")
        val second = Instant.parse("2026-10-25T01:30:00Z")
        val date = LocalDate.parse("2026-10-25")
        val noLive =
            activityDetail(TimeTrackingMode.NO_LIVE_TRACKING, duration = null, zone = berlin).root.copy(
                primaryLocalDate = date,
                startedAt = null,
                completedAt = first,
                originalUtcOffsetMinutes = 120,
            )
        val later =
            noLive.copy(
                executionId = ActivityExecutionId("later"),
                completedAt = second,
                originalUtcOffsetMinutes = 60,
            )
        val sequence = sequenceDetail(SequenceExecutionStatus.COMPLETED, berlin)
        val performed = sequence.occurrences.first()
        val child =
            requireNotNull(performed.child).copy(
                startedAt = first,
                completedAt = second,
                originalZoneId = ZoneId.of("Asia/Tokyo"),
                originalUtcOffsetMinutes = 540,
                activeDuration = Duration.ofHours(1),
            )
        val sequenceDetail =
            sequence.copy(
                root = sequence.root.copy(startedAt = first, completedAt = second),
                occurrences =
                    listOf(
                        performed.copy(enteredAt = first, completedAt = second, child = child),
                        sequence.occurrences.last(),
                    ),
                intervals = sequence.intervals.take(1).map { it.copy(startedAt = first, endedAt = second) },
            )
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            composeTestRule.setContent {
                val configuration = android.content.res.Configuration(composeTestRule.activity.resources.configuration)
                configuration.setLocale(locale.value)
                val context = composeTestRule.activity.createConfigurationContext(configuration)
                CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context) {
                    LifeTracingTheme {
                        if (detailMode.value) {
                            SequenceDetail(sequenceDetail)
                        } else {
                            HistoryScreen(
                                HistoryPresentationState(
                                    HistoryBrowseWindow(date.minusDays(1), date),
                                    HistoryRootsLoad.Content(listOf(noLive, later)),
                                ),
                                {},
                            )
                        }
                    }
                }
            }
            for (language in listOf(Locale.US, russian)) {
                composeTestRule.runOnIdle { locale.value = language }
                val clock =
                    DateTimeFormatter
                        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                        .withLocale(language)
                        .format(first.atZone(berlin))
                val configuration = android.content.res.Configuration(composeTestRule.activity.resources.configuration)
                configuration.setLocale(language)
                val resources = composeTestRule.activity.createConfigurationContext(configuration).resources
                composeTestRule
                    .onNodeWithText(
                        resources.getString(R.string.history_completion_time, "$clock (UTC+02:00, Europe/Berlin)"),
                    ).performScrollTo()
                    .assertIsDisplayed()
                composeTestRule
                    .onNodeWithText(
                        resources.getString(R.string.history_completion_time, "$clock (UTC+01:00, Europe/Berlin)"),
                    ).performScrollTo()
                    .assertIsDisplayed()
                val missing = resources.getString(R.string.history_missing_value)
                composeTestRule
                    .onAllNodesWithText(resources.getString(R.string.history_active_duration, missing))
                    .assertCountEquals(2)
                composeTestRule
                    .onAllNodesWithText(resources.getString(R.string.history_active_duration, "00:00"))
                    .assertCountEquals(0)
            }
            composeTestRule.runOnIdle { detailMode.value = true }
            composeTestRule.onNodeWithTag("sequence-history-detail-list").performScrollToKey("occurrence:performed")
            val childSummary = composeTestRule.onNodeWithTag("sequence-child-${child.executionId.value}")
            for (endpoint in listOf(first, second)) {
                childSummary.assert(
                    hasText(
                        com.alexandr5476.lifetracing.ui
                            .exactHistoricalTimestamp(endpoint, child.originalZoneId),
                        substring = true,
                    ),
                )
            }
            composeTestRule.onNodeWithTag("sequence-history-detail-list").performScrollToKey("sequence:root")
            val rootSummary = composeTestRule.onNodeWithTag("sequence-history-root")
            for (endpoint in listOf(first, second)) {
                rootSummary.assert(
                    hasText(
                        com.alexandr5476.lifetracing.ui
                            .exactHistoricalTimestamp(endpoint, berlin),
                        substring = true,
                    ),
                )
            }
            sequenceItem("occurrence:tombstone", "history-occurrence-tombstone").assertIsDisplayed()
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun activityDetailUsesHistoricalZoneAndPreservesFrozenConfigurationAndValues() {
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        try {
            val zone = ZoneId.of("America/Los_Angeles")
            val detail = activityDetail(TimeTrackingMode.TIMER, Duration.ofMinutes(5), Duration.ofMinutes(2), zone)
            setDetail { ActivityDetail(detail) }

            composeTestRule
                .onNodeWithText(
                    historicalTime(detail.root.startedAt!!, zone),
                    substring = true,
                ).assertIsDisplayed()
            composeTestRule
                .onNodeWithText(
                    historicalTime(requireNotNull(detail.root.startedAt), ZoneId.of("Asia/Tokyo")),
                    substring = true,
                ).assertDoesNotExist()
            composeTestRule.onNodeWithText(text(R.string.history_active_duration, "02:00")).assertIsDisplayed()
            composeTestRule.onNodeWithText(text(R.string.history_timer_target, "05:00")).assertIsDisplayed()
            composeTestRule
                .onNodeWithText(text(R.string.history_field_value, "Weight", "0 kg"))
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithText(text(R.string.history_configured_value, "12.5 kg"))
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithText(text(R.string.history_field_value, "Effort", "Hard"))
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithText(text(R.string.history_configured_value, "Easy"))
                .performScrollTo()
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithText(
                    text(R.string.history_field_value, "Notes", text(R.string.history_missing_value)),
                ).performScrollTo()
                .assertIsDisplayed()
            composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(0)
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun noLiveActivityShowsMissingDurationWithoutMutationControls() {
        setDetail {
            ActivityDetail(activityDetail(TimeTrackingMode.NO_LIVE_TRACKING, null, null, ZoneId.of("UTC")))
        }

        composeTestRule
            .onNodeWithText(text(R.string.history_active_duration, text(R.string.history_missing_value)))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_no_live)).assertIsDisplayed()
        composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun productionActivityDetailShowsCorrectionOnlyForNonPlanAndDeleteForBoth() {
        setMutationDetail(activityDetail())
        composeTestRule.onNodeWithTag("history-correct").assertIsDisplayed()
        composeTestRule.onNodeWithTag("history-delete").assertIsDisplayed()
        composeTestRule.onNodeWithTag("history-correct").performClick()
        composeTestRule.onNodeWithTag("history-correction-started").assertIsDisplayed()
    }

    @Test
    fun productionPlanLinkedActivityDetailShowsDeleteWithoutCorrection() {
        val standalone = activityDetail()
        setMutationDetail(
            standalone.copy(root = standalone.root.copy(planEntryId = PlanEntryId("fulfilled-plan"))),
        )
        composeTestRule.onNodeWithTag("history-correct").assertDoesNotExist()
        composeTestRule.onNodeWithTag("history-delete").assertIsDisplayed()
    }

    @Test
    fun deleteFailureStaysInConfirmationAndCanRetryOrCancel() {
        val attempts = AtomicInteger()
        val controller =
            setMutationDetail(activityDetail()) { _, _, _ ->
                attempts.incrementAndGet()
                error("delete failed")
            }
        composeTestRule.onNodeWithTag("history-delete").performClick()
        composeTestRule.onNodeWithTag("history-delete-confirm").performClick()
        composeTestRule.waitUntil(2_000) {
            controller.state.value.issue == ActivityHistoryMutationIssue.DELETE_FAILURE
        }
        composeTestRule.onNodeWithText(text(R.string.history_delete_failure)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("history-delete-confirm").assertIsDisplayed().performClick()
        composeTestRule.waitUntil(2_000) { attempts.get() == 2 }
        composeTestRule.onNodeWithText(text(R.string.manual_history_cancel)).performClick()
        composeTestRule.onNodeWithTag("history-delete-confirm").assertDoesNotExist()
        assertEquals(2, attempts.get())
    }

    @Test
    fun sequenceDetailShowsCanonicalGraphStatesProvenanceAndTombstoneInOrder() {
        val zone = ZoneId.of("America/Los_Angeles")
        val detail = sequenceDetail(SequenceExecutionStatus.ENDED_EARLY, zone)
        composeTestRule.setContent { LifeTracingTheme { SequenceDetail(detail) } }

        composeTestRule.onNodeWithText(text(R.string.history_ended_early)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_active_duration, "10:00")).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_pause_duration, "02:00")).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_wall_duration, "15:00")).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag("sequence-history-root")
            .assert(
                hasText(
                    com.alexandr5476.lifetracing.ui
                        .exactHistoricalTimestamp(detail.root.startedAt, zone),
                    substring = true,
                ),
            ).assertIsDisplayed()

        sequenceItem("occurrence:performed", "history-occurrence-performed").assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_occurrence_performed)).assertExists()
        composeTestRule.onNodeWithText(text(R.string.sequence_history_source_id, "source-step")).assertExists()
        composeTestRule.onNodeWithText(text(R.string.history_timer_target, "00:30")).assertExists()
        composeTestRule.onNodeWithText(text(R.string.history_actual_duration, "01:00")).assertExists()
        composeTestRule
            .onNodeWithContentDescription(
                text(R.string.history_actual_field, "Reps", "7 reps"),
                substring = true,
            ).assertExists()
        sequenceItem("occurrence:skipped", "history-occurrence-skipped").assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.sequence_history_source_id, "source-repeat-step")).assertExists()
        composeTestRule.onNodeWithText(text(R.string.history_repeat_iteration, 2)).assertExists()
        composeTestRule.onNodeWithText(text(R.string.history_occurrence_skipped)).assertExists()
        sequenceItem("occurrence:not-started", "history-occurrence-not-started").assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_runtime_added)).assertExists()
        composeTestRule.onNodeWithText(text(R.string.history_occurrence_not_started)).assertExists()
        sequenceItem("occurrence:tombstone", "history-occurrence-tombstone").assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_deleted_child)).assertExists()
        composeTestRule.onNodeWithText("Structurally removed").assertDoesNotExist()
        val intervalLabels =
            listOf(
                R.string.history_interval_active_step,
                R.string.history_interval_step_pause,
                R.string.history_interval_explicit_pause,
                R.string.history_interval_implicit_idle,
                R.string.history_interval_transition_countdown,
            )
        detail.intervals.zip(intervalLabels).forEach { (interval, label) ->
            sequenceItem("interval:${interval.id.value}", "history-interval-${interval.id.value}").assertIsDisplayed()
            composeTestRule.onNodeWithText(text(label)).assertExists()
        }
    }

    @Test
    fun sequencePresentEmptyTextIsDistinctFromMissingConfiguredAndActualText() {
        val base = sequenceDetail(SequenceExecutionStatus.COMPLETED, ZoneId.of("UTC"))
        val field =
            com.alexandr5476.lifetracing.domain.SequenceHistoryField(
                com.alexandr5476.lifetracing.domain
                    .SequenceSnapshotFieldId("empty"),
                "Notes",
                CustomFieldType.TEXT,
                null,
                null,
                false,
                com.alexandr5476.lifetracing.domain.SequenceHistoryConfiguredValue
                    .Text(""),
                com.alexandr5476.lifetracing.domain.SequenceHistoryActualValue
                    .Text(""),
                emptyList(),
            )
        setSequenceMutationDetail(base.copy(fields = listOf(field)))
        composeTestRule
            .onNodeWithTag("sequence-history-detail-list")
            .performScrollToKey("sequence-field:empty")
        composeTestRule
            .onNodeWithContentDescription(
                text(R.string.manual_history_present_empty),
                substring = true,
            ).assertExists()
    }

    @Test
    fun completedSequenceStatusIsPresented() {
        val completed = sequenceDetail(SequenceExecutionStatus.COMPLETED, ZoneId.of("UTC"))
        setSequenceMutationDetail(completed)
        composeTestRule.onNodeWithText(text(R.string.history_completed)).assertIsDisplayed()
    }

    @Test
    fun endedEarlySequenceUsesMutationAwareRoute() {
        setSequenceMutationDetail(
            sequenceMutationDetail().copy(
                root = sequenceMutationDetail().root.copy(status = SequenceExecutionStatus.ENDED_EARLY),
            ),
        )
        composeTestRule.onNodeWithText(text(R.string.history_ended_early)).assertIsDisplayed()
        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sequenceTimingCorrectionUsesControllerDraftAndCancelWritesNothing() {
        var timingCommands = 0
        val detail = sequenceMutationDetail()
        setSequenceMutationDetail(detail, correct = { _, _, _ -> timingCommands++ })

        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().performClick()
        composeTestRule
            .onNodeWithTag("sequence-history-timestamp-root-ended")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_active_duration, "10:00")).assertDoesNotExist()
        composeTestRule.onNodeWithText(text(R.string.manual_history_cancel)).assertIsDisplayed().performClick()
        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().assertIsDisplayed()
        assertEquals(0, timingCommands)
    }

    @Test
    fun timingFieldErrorFocusAndDstSelectionKeepExactNativeTarget() {
        val detail = sequenceMutationDetail().copy(originalZoneId = ZoneId.of("Europe/Berlin"))
        val controller = setSequenceMutationDetail(detail, correct = { _, _, _ -> error("unexpected write") })
        sequenceItem("sequence:timing", "sequence-history-timing").performClick()
        val end = composeTestRule.onNodeWithTag("sequence-history-timestamp-root-ended")
        end.performScrollTo().performTextReplacement("invalid")
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performClick()
        end.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error, text(R.string.manual_history_invalid_datetime)),
        )
        keyboard.enter(composeTestRule.onNodeWithTag("sequence-timing-error-target"))
        composeTestRule.onNodeWithTag("sequence-timing-error-target").performClick()
        keyboard.awaitFocus(end)
        val publication = controller.state.value.publication
        end.performTextReplacement("2026-10-25T02:30:27.123")
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performClick()
        val choice =
            composeTestRule.onNode(
                hasText(text(R.string.sequence_history_root_ended), substring = true) and
                    hasText(text(R.string.manual_history_second_occurrence, "UTC+01:00")),
            )
        choice.performScrollTo().performClick()
        choice.assertIsSelected()
        choice.assert(hasText(text(R.string.sequence_history_root_ended), substring = true))
        choice.assert(hasText(detail.root.executionId.value, substring = true))
        assertTrue(controller.state.value.publication > publication)
        assertEquals(
            java.time.ZoneOffset.ofHours(1),
            controller.state.value.timingDraft!!
                .timestamps
                .getValue(
                    SequenceHistoryTimestampTarget.RootEndedAt,
                ).selectedOffset,
        )
        assertEquals(0L, controller.state.value.refreshGeneration)
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performSemanticsAction(SemanticsActions.OnClick)
        keyboard.awaitFocus(composeTestRule.onNodeWithText(text(R.string.sequence_history_timing_review)))
        val oldSave =
            composeTestRule
                .onNodeWithTag(
                    "sequence-history-confirm-timing",
                ).fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeTestRule.onNodeWithText(text(R.string.history_back)).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.runOnIdle { oldSave() }
        assertEquals(null, controller.state.value.timingDraft)
        assertEquals(0L, controller.state.value.refreshGeneration)
    }

    @Test
    fun approvingOverlapRetiresPressedProceedBeforeSaveIsAvailable() {
        val base = sequenceMutationDetail()
        val detail =
            base.copy(
                intervals =
                    base.intervals +
                        base.intervals.first().copy(
                            id = SequenceIntervalId("overlapping-pause"),
                            kind = SequenceIntervalKind.EXPLICIT_PAUSE,
                            occurrenceId = null,
                        ),
            )
        var writes = 0
        val controller = setSequenceMutationDetail(detail, correct = { _, _, _ -> writes++ })
        sequenceItem("sequence:timing", "sequence-history-timing").performClick()
        val oldEnd =
            controller.state.value.timingDraft!!.timestamps.getValue(
                SequenceHistoryTimestampTarget.RootEndedAt,
            )
        composeTestRule.onNodeWithTag("sequence-history-timestamp-root-ended").performScrollTo().performTextReplacement(
            LocalDateTime.parse(oldEnd.text).plusSeconds(1).toString(),
        )
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performSemanticsAction(SemanticsActions.OnClick)
        val proceed = composeTestRule.onNodeWithTag("sequence-history-overlap-proceed")
        val center = proceed.fetchSemanticsNode().boundsInRoot.center
        composeTestRule.onRoot().performTouchInput { down(center) }
        try {
            proceed.performSemanticsAction(SemanticsActions.OnClick)
            composeTestRule.onNodeWithTag("sequence-history-confirm-timing").assertIsDisplayed()
            assertFalse(controller.state.value.overlapWarning)
        } finally {
            composeTestRule.onRoot().performTouchInput { up() }
        }
        composeTestRule.runOnIdle {
            assertEquals(0, writes)
            assertEquals(0L, controller.state.value.refreshGeneration)
            assertTrue(controller.state.value.timingProposal != null)
        }
        composeTestRule
            .onNodeWithTag(
                "sequence-history-confirm-timing",
            ).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitUntil(2_000) { writes == 1 && controller.state.value.refreshGeneration == 1L }
    }

    @Test
    fun timingEditorWithOnlyRootBoundariesDisablesEmptyOwnerSections() {
        val base = sequenceMutationDetail()
        val detail =
            base.copy(
                root = base.root.copy(activeDuration = Duration.ZERO, pauseDuration = base.root.wallDuration),
                updatedAt = base.updatedAt.plusSeconds(1),
                occurrences = emptyList(),
                intervals = emptyList(),
            )
        val controller = setSequenceMutationDetail(detail, correct = { _, _, _ -> error("unexpected writer") })
        sequenceItem("sequence:timing", "sequence-history-timing").performClick()
        assertEquals(
            setOf(SequenceHistoryTimestampTarget.RootStartedAt, SequenceHistoryTimestampTarget.RootEndedAt),
            controller.state.value.timingDraft!!
                .timestamps.keys,
        )
        composeTestRule.onNodeWithTag("sequence-timing-section").performSemanticsAction(SemanticsActions.OnClick)
        for (resource in listOf(
            R.string.sequence_timing_section_occurrences,
            R.string.sequence_timing_section_children,
            R.string.sequence_timing_section_intervals,
        )) {
            composeTestRule
                .onNodeWithText(
                    text(R.string.sequence_timing_empty_section, text(resource)),
                ).assertIsNotEnabled()
        }
        composeTestRule.onNodeWithText(text(R.string.sequence_timing_section_root)).performClick()
        composeTestRule.onNodeWithTag("sequence-timing-ordinal").performTextReplacement("2")
        composeTestRule.onNodeWithTag("sequence-timing-go").performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithTag("sequence-history-timestamp-root-ended").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-history-review-timing").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(controller.state.value.noTimingChanges)
        assertEquals(0L, controller.state.value.refreshGeneration)
    }

    @Test
    fun timingEditorPresentsSyntaxDstNoChangeAndExplicitOffsetStates() {
        val detail = sequenceMutationDetail().copy(originalZoneId = ZoneId.of("Europe/Berlin"))
        setSequenceMutationDetail(detail, correct = { _, _, _ -> error("unexpected") })
        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().performClick()
        val end = composeTestRule.onNodeWithTag("sequence-history-timestamp-root-ended")

        end.performScrollTo().performTextReplacement("not-a-date")
        composeTestRule.onNodeWithTag("sequence-history-review-timing").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText(text(R.string.manual_history_invalid_datetime)).assertIsDisplayed()

        end.performScrollTo().performTextReplacement("2026-03-29T02:30:00")
        composeTestRule.onNodeWithTag("sequence-history-review-timing").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText(text(R.string.manual_history_nonexistent_time)).assertIsDisplayed()

        end.performScrollTo().performTextReplacement("2026-10-25T02:30:00")
        composeTestRule.onNodeWithTag("sequence-history-review-timing").assertIsDisplayed().performClick()
        composeTestRule
            .onAllNodesWithText(text(R.string.manual_history_ambiguous_time))[0]
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(text(R.string.sequence_history_root_ended), substring = true) and
                    hasText(text(R.string.manual_history_second_occurrence, "UTC+01:00")),
            ).performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("sequence-history-review-timing").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText(text(R.string.sequence_history_timing_review)).assertIsDisplayed()

        composeTestRule.onNodeWithText(text(R.string.manual_history_cancel)).assertIsDisplayed().performClick()
        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-review-timing").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText(text(R.string.sequence_history_no_timing_changes)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").assertDoesNotExist()
    }

    @Test
    fun ambiguousTimingReviewKeepsSelectedOffsetVisibleAndInProposal() {
        val berlin = ZoneId.of("Europe/Berlin")
        val earlierOccurrence = Instant.parse("2026-10-25T00:30:00Z")
        var committed: SequenceHistoryTimingCorrection? = null
        val detail =
            sequenceMutationDetail().copy(
                originalZoneId = berlin,
                root = sequenceMutationDetail().root.copy(completedAt = earlierOccurrence),
            )
        val controller = setSequenceMutationDetail(detail, correct = { _, correction, _ -> committed = correction })

        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().performClick()
        composeTestRule
            .onNodeWithTag("sequence-history-timestamp-root-ended")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(text(R.string.sequence_history_root_ended), substring = true) and
                    hasText(text(R.string.manual_history_first_occurrence, "UTC+02:00")),
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(text(R.string.sequence_history_root_ended), substring = true) and
                    hasText(text(R.string.manual_history_second_occurrence, "UTC+01:00")),
            ).performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("sequence-history-review-timing").assertIsDisplayed().performClick()

        val proposal = requireNotNull(controller.state.value.timingProposal).correction
        assertEquals(earlierOccurrence, detail.root.completedAt)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), proposal.endedAt)
        composeTestRule
            .onNodeWithText(
                mutationPreviewTime(earlierOccurrence, berlin),
                substring = true,
            ).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                mutationPreviewTime(Instant.parse("2026-10-25T01:30:00Z"), berlin),
                substring = true,
            ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").assertIsDisplayed().performClick()
        composeTestRule.waitUntil(2_000) { committed != null }
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), committed?.endedAt)
    }

    @Test
    fun timingValidationGenericAndStaleFailuresRemainVisibleInReview() {
        var failure: Exception = IllegalArgumentException("validation")
        val controller =
            setSequenceMutationDetail(
                sequenceMutationDetail(),
                correct = { _, _, _ -> throw failure },
            )
        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().performClick()
        val draft =
            requireNotNull(controller.state.value.timingDraft)
                .timestamps
                .getValue(SequenceHistoryTimestampTarget.RootEndedAt)
        composeTestRule
            .onNodeWithTag("sequence-history-timestamp-root-ended")
            .performScrollTo()
            .performTextReplacement(LocalDateTime.parse(draft.text).plusSeconds(1).toString())
        composeTestRule.onNodeWithTag("sequence-history-review-timing").assertIsDisplayed().performClick()

        fun confirmFailure(
            expectedIssue: SequenceHistoryMutationIssue,
            expectedMessage: Int,
        ) {
            composeTestRule.onNodeWithTag("sequence-history-confirm-timing").assertIsDisplayed().performClick()
            composeTestRule.waitUntil(2_000) { controller.state.value.issue == expectedIssue }
            if (expectedIssue == SequenceHistoryMutationIssue.STALE) {
                composeTestRule.waitUntil(2_000) { controller.state.value.load is HistoryDetailLoad.Content }
                composeTestRule.onNodeWithTag("sequence-history-detail-list").performScrollToKey("sequence:issue")
            }
            composeTestRule.onNodeWithText(text(expectedMessage)).assertIsDisplayed()
        }

        confirmFailure(SequenceHistoryMutationIssue.INVALID_PROPOSAL, R.string.sequence_history_invalid_proposal)
        failure = IllegalStateException("persistence")
        confirmFailure(SequenceHistoryMutationIssue.TIMING_FAILURE, R.string.sequence_history_timing_failure)
        failure = ConcurrentModificationException("stale")
        confirmFailure(SequenceHistoryMutationIssue.STALE, R.string.history_changed_review)
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").assertDoesNotExist()
    }

    @Test
    fun destructiveSurfacesIdentifyOccurrenceAndCloseGapReviewHidesDeletedChildFacts() {
        val detail = structuralMutationDetail()
        var deletions = 0
        val controller =
            setSequenceMutationDetail(
                detail,
                delete = { _, _, _ -> deletions++ },
            )
        val targetText = text(R.string.history_occurrence, 1, "Performed")

        sequenceItem("occurrence:performed", "sequence-history-delete-child-performed").performScrollTo().performClick()
        composeTestRule.onNodeWithText(targetText, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_repeat_iteration, 2)).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag("sequence-child-deletion-cancel")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.onNodeWithTag("sequence-child-deletion-heading").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sequence-history-detail-list").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(null, controller.state.value.childDeletionProposal)
            assertEquals(0L, controller.state.value.refreshGeneration)
            assertEquals(0, deletions)
        }

        sequenceItem(
            "occurrence:performed",
            "sequence-history-remove-occurrence-performed",
        ).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-structural-target").assert(hasText(targetText, substring = true))
        composeTestRule.onNodeWithTag("sequence-history-close-gap").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("sequence-structural-placements-list").performScrollToKey("choice:ownerless")
        composeTestRule
            .onNodeWithText(
                text(
                    R.string.sequence_history_ownerless_interval,
                    text(R.string.history_interval_explicit_pause),
                    "ownerless",
                ),
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasTestTag("sequence-history-ownerless-ownerless-fixed") and
                    hasText(
                        text(
                            R.string.sequence_history_ownerless_fixed_choice,
                            mutationPreviewInterval(
                                START.plusSeconds(300),
                                START.plusSeconds(310),
                                detail.originalZoneId,
                            ),
                        ),
                    ),
            ).assertIsDisplayed()
        composeTestRule
            .onNode(
                hasTestTag("sequence-history-ownerless-ownerless-translated") and
                    hasText(
                        text(
                            R.string.sequence_history_ownerless_translated_choice,
                            mutationPreviewInterval(
                                START.plusSeconds(240),
                                START.plusSeconds(250),
                                detail.originalZoneId,
                            ),
                        ),
                    ),
            ).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag("sequence-history-ownerless-ownerless-fixed")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("sequence-structural-placements-list").performScrollToKey("choice:ownerless-2")
        composeTestRule
            .onNodeWithTag("sequence-history-ownerless-ownerless-2-translated")
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithText(text(R.string.sequence_history_structural_review)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-structural-target").assert(hasText(targetText, substring = true))
        composeTestRule
            .onNodeWithTag(
                "sequence-structural-review-list",
            ).performScrollToKey("child-pause:retained:pause-retained")
        composeTestRule.onNodeWithText("pause-retained", substring = true).performScrollTo().assertIsDisplayed()
        val deletedTarget = text(R.string.history_occurrence, 2, "Deleted later")
        composeTestRule
            .onNodeWithText(text(R.string.sequence_history_child_started, deletedTarget))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(text(R.string.sequence_history_child_completed, deletedTarget))
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("sequence-structural-review-list").performScrollToKey("ownerless:ownerless")
        composeTestRule
            .onNodeWithText(
                text(
                    R.string.sequence_history_ownerless_selection,
                    text(R.string.history_interval_explicit_pause),
                    "ownerless",
                    text(R.string.sequence_history_fixed),
                    mutationPreviewInterval(START.plusSeconds(300), START.plusSeconds(310), detail.originalZoneId),
                ),
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-structural-review-list").performScrollToKey("ownerless:ownerless-2")
        composeTestRule
            .onNodeWithText(
                text(
                    R.string.sequence_history_ownerless_selection,
                    text(R.string.history_interval_implicit_idle),
                    "ownerless-2",
                    text(R.string.sequence_history_translated),
                    mutationPreviewInterval(START.plusSeconds(270), START.plusSeconds(280), detail.originalZoneId),
                ),
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag("sequence-structural-review-list")
            .performScrollToKey("deleted-child:child-deleted-later")
        composeTestRule
            .onNodeWithTag("sequence-structural-notice-deleted-child:child-deleted-later")
            .assertIsDisplayed()
            .assertTextEquals(text(R.string.sequence_structural_deleted_translation, "child-deleted-later"))
        composeTestRule
            .onNodeWithTag("sequence-structural-review-list")
            .performScrollToKey("deleted-child-pause:child-deleted-later:pause-deleted-later")
        composeTestRule
            .onNodeWithTag(
                "sequence-structural-notice-deleted-child-pause:child-deleted-later:pause-deleted-later",
            ).assertIsDisplayed()
            .assertTextEquals(
                text(R.string.sequence_structural_deleted_pause, "child-deleted-later", "pause-deleted-later"),
            )
    }

    @Test
    fun leaveGapReviewShowsEveryTargetOwnedIntervalRemovalBeforeConfirmation() {
        val detail = structuralMutationDetail()
        setSequenceMutationDetail(detail)

        sequenceItem(
            "occurrence:performed",
            "sequence-history-remove-occurrence-performed",
        ).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-leave-gap").assertIsDisplayed().performClick()

        composeTestRule
            .onNodeWithTag(
                "sequence-structural-review-list",
            ).performScrollToKey("removed-interval:performed")
        composeTestRule
            .onNodeWithText(
                text(
                    R.string.sequence_history_removed_interval,
                    text(R.string.history_interval_active_step),
                    "performed",
                    mutationPreviewInterval(START.plusSeconds(10), START.plusSeconds(70), detail.originalZoneId),
                ),
            ).performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("sequence-history-confirm-structural").assertIsDisplayed()
    }

    @Test
    fun readDeletionAndStructuralFailuresHaveDistinctActiveSurfaceMessages() {
        val detail = structuralMutationDetail()
        var readFails = true
        val controller =
            SequenceHistoryMutationController(
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                detail.root.executionId,
                { if (readFails) error("read") else detail },
                { _, _, _ -> },
                { _, _, _ -> error("delete") },
                { _, _, _ -> error("structural") },
            )
        composeTestRule.setContent {
            LifeTracingTheme {
                SequenceHistoryDetailRoute(
                    SequenceHistoryMutationRouteSession(detail.root.executionId, controller),
                    {},
                    {},
                )
            }
        }
        composeTestRule.waitUntil(2_000) { controller.state.value.issue == SequenceHistoryMutationIssue.READ_FAILURE }
        composeTestRule.onNodeWithText(text(R.string.history_read_failure)).assertIsDisplayed()
        readFails = false
        controller.dispatch(SequenceHistoryMutationAction.Retry)
        composeTestRule.waitUntil(2_000) { controller.state.value.load is HistoryDetailLoad.Content }

        sequenceItem("occurrence:performed", "sequence-history-delete-child-performed").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-delete-child").performScrollTo().performClick()
        composeTestRule.waitUntil(2_000) {
            controller.state.value.issue == SequenceHistoryMutationIssue.CHILD_DELETION_FAILURE
        }
        composeTestRule.onNodeWithText(text(R.string.sequence_history_child_deletion_failure)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.manual_history_cancel)).performScrollTo().performClick()

        sequenceItem(
            "occurrence:performed",
            "sequence-history-remove-occurrence-performed",
        ).performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sequence-history-leave-gap").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-structural").assertIsDisplayed().performClick()
        composeTestRule.waitUntil(2_000) {
            controller.state.value.issue == SequenceHistoryMutationIssue.STRUCTURAL_FAILURE
        }
        composeTestRule.onNodeWithText(text(R.string.sequence_history_structural_failure)).assertIsDisplayed()
    }

    @Test
    fun inFlightConfirmationIsDisabledUntilRepositoryReturns() {
        val gate = CompletableDeferred<Unit>()
        val controller =
            setSequenceMutationDetail(
                sequenceMutationDetail(),
                correct = { _, _, _ -> gate.await() },
            )
        sequenceItem("sequence:timing", "sequence-history-timing").performScrollTo().performClick()
        val target = SequenceHistoryTimestampTarget.RootEndedAt
        val draft = requireNotNull(controller.state.value.timingDraft).timestamps.getValue(target)
        composeTestRule
            .onNodeWithTag("sequence-history-timestamp-root-ended")
            .performScrollTo()
            .performTextReplacement(LocalDateTime.parse(draft.text).plusSeconds(1).toString())
        composeTestRule.onNodeWithTag("sequence-history-review-timing").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").assertIsDisplayed().performClick()
        composeTestRule.waitUntil(2_000) { controller.state.value.isMutating }
        composeTestRule.onNodeWithTag("sequence-history-confirm-timing").assertIsNotEnabled()
        gate.complete(Unit)
    }

    @Test
    fun completedSequenceStatusAndMixedListKeepStoredDatesAndOriginalZoneClocks() {
        val suppliedDate = LocalDate.parse("2001-02-03")
        val activity = activityDetail().root.copy(primaryLocalDate = suppliedDate, title = "Activity summary")
        val sequence =
            sequenceDetail(SequenceExecutionStatus.COMPLETED, ZoneId.of("UTC"))
                .root
                .copy(primaryLocalDate = suppliedDate, title = "Sequence summary")
        composeTestRule.setContent {
            LifeTracingTheme {
                HistoryScreen(
                    HistoryPresentationState(
                        HistoryBrowseWindow(suppliedDate.minusDays(1), suppliedDate.plusDays(1)),
                        HistoryRootsLoad.Content(listOf(activity, sequence)),
                    ),
                    {},
                )
            }
        }

        composeTestRule.onNodeWithText(localDate(suppliedDate)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.history_completed)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("history-activity-${activity.executionId.value}").assertExists()
        composeTestRule.onNodeWithTag("history-sequence-${sequence.executionId.value}").assertExists()
        composeTestRule
            .onAllNodesWithText(historicalTime(activity.completedAt, activity.originalZoneId), substring = true)
            .assertCountEquals(2)
        assertTrue(activity.completedAt.toString().startsWith("2026"))
    }

    private fun sequenceItem(
        key: String,
        tag: String,
    ): androidx.compose.ui.test.SemanticsNodeInteraction {
        composeTestRule.onNodeWithTag("sequence-history-detail-list").performScrollToKey(key)
        return composeTestRule.onNodeWithTag(tag)
    }

    private fun setDetail(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            LifeTracingTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { content() }
            }
        }
    }

    private fun setMutationDetail(
        detail: ActivityHistoryDetail,
        delete: suspend (ActivityExecutionId, Instant, Instant) -> Unit = { _, _, _ -> },
    ): ActivityHistoryMutationController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller =
            ActivityHistoryMutationController(
                scope,
                detail.root.executionId,
                { detail },
                { _, _, _ -> },
                delete,
                { END.plusSeconds(1) },
            )
        val session = ActivityHistoryMutationRouteSession(detail.root.executionId, controller)
        composeTestRule.setContent {
            LifeTracingTheme {
                ActivityHistoryDetailRoute(session, {}, {}, {})
            }
        }
        composeTestRule.waitUntil(2_000) {
            controller.state.value.load is HistoryDetailLoad.Content
        }
        return controller
    }

    private fun setSequenceMutationDetail(
        detail: SequenceHistoryDetail,
        correct: suspend (
            SequenceExecutionId,
            SequenceHistoryTimingCorrection,
            Instant,
        ) -> Unit = { _, _, _ -> },
        delete: suspend (
            SequenceExecutionId,
            com.alexandr5476.lifetracing.domain.SequenceChildHistoryDeletionCommand,
            Instant,
        ) -> Unit = { _, _, _ -> },
        remove: suspend (
            SequenceExecutionId,
            com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalCommand,
            Instant,
        ) -> Unit = { _, _, _ -> },
    ): SequenceHistoryMutationController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller =
            SequenceHistoryMutationController(
                scope,
                detail.root.executionId,
                { detail },
                correct,
                delete,
                remove,
            )
        composeTestRule.setContent {
            LifeTracingTheme {
                SequenceHistoryDetailRoute(
                    SequenceHistoryMutationRouteSession(detail.root.executionId, controller),
                    onBack = {},
                    onRefresh = {},
                )
            }
        }
        composeTestRule.waitUntil(2_000) { controller.state.value.load is HistoryDetailLoad.Content }
        return controller
    }

    private fun text(
        id: Int,
        vararg arguments: Any,
    ): String = composeTestRule.activity.getString(id, *arguments)

    private fun historicalTime(
        instant: Instant,
        zoneId: ZoneId,
    ): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(composeTestRule.activity.resources.configuration.locales[0])
            .format(instant.atZone(zoneId))

    private fun mutationPreviewTime(
        instant: Instant,
        zoneId: ZoneId,
    ): String =
        mutationPreviewInstantText(
            instant,
            zoneId,
            composeTestRule.activity.resources.configuration.locales[0],
        )

    private fun mutationPreviewInterval(
        startedAt: Instant,
        endedAt: Instant,
        zoneId: ZoneId,
    ): String = "${mutationPreviewTime(startedAt, zoneId)} – ${mutationPreviewTime(endedAt, zoneId)}"

    private fun localDate(date: LocalDate): String =
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(composeTestRule.activity.resources.configuration.locales[0])
            .format(date)

    private fun activityDetail(
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        target: Duration? = null,
        duration: Duration? = Duration.ofMinutes(2),
        zone: ZoneId = ZoneId.of("UTC"),
    ): ActivityHistoryDetail {
        val easy = ActivitySnapshotCategoryOptionId("easy")
        val hard = ActivitySnapshotCategoryOptionId("hard")
        return ActivityHistoryDetail(
            CompletedActivityHistoryRoot(
                ActivityExecutionId("activity"),
                ActivitySnapshotId("activity-snapshot"),
                LocalDate.parse("2026-01-01"),
                END,
                START,
                duration,
                null,
                "Historical activity",
                "Frozen comment",
                mode,
                target,
                zone,
                null,
            ),
            END,
            zone,
            ActivityTemplateSettings(),
            listOf(
                ActivityHistoryField(
                    ActivitySnapshotFieldId("weight"),
                    "Weight",
                    CustomFieldType.NUMBER,
                    "kg",
                    1,
                    true,
                    ActivityHistoryConfiguredValue.Number(12_500),
                    ActivityHistoryActualValue.Number(0),
                    emptyList(),
                ),
                ActivityHistoryField(
                    ActivitySnapshotFieldId("effort"),
                    "Effort",
                    CustomFieldType.CATEGORY,
                    null,
                    null,
                    false,
                    ActivityHistoryConfiguredValue.Category(easy),
                    ActivityHistoryActualValue.Category(hard, "Hard"),
                    listOf(ActivityHistoryCategoryOption(easy, "Easy"), ActivityHistoryCategoryOption(hard, "Hard")),
                ),
                ActivityHistoryField(
                    ActivitySnapshotFieldId("notes"),
                    "Notes",
                    CustomFieldType.TEXT,
                    null,
                    null,
                    false,
                    ActivityHistoryConfiguredValue.Text("Default"),
                    ActivityHistoryActualValue.Missing,
                    emptyList(),
                ),
            ),
        )
    }

    private fun sequenceDetail(
        status: SequenceExecutionStatus,
        zone: ZoneId,
    ): SequenceHistoryDetail {
        val performedActivity = occurrenceActivity("Performed", TimeTrackingMode.TIMER, Duration.ofSeconds(30))
        val childField =
            ActivityHistoryField(
                ActivitySnapshotFieldId("reps"),
                "Reps",
                CustomFieldType.NUMBER,
                "reps",
                0,
                true,
                ActivityHistoryConfiguredValue.Missing,
                ActivityHistoryActualValue.Number(7_000),
                emptyList(),
            )
        val occurrences =
            listOf(
                SequenceHistoryOccurrence(
                    SequenceOccurrenceId("performed"),
                    0,
                    performedActivity.snapshotId,
                    SequenceSnapshotNodeId("source-step"),
                    null,
                    null,
                    false,
                    false,
                    RuntimeOccurrenceStatus.COMPLETED,
                    START.plusSeconds(10),
                    START.plusSeconds(70),
                    null,
                    performedActivity.copy(
                        mainValue = childField.copy(actualValue = ActivityHistoryActualValue.Missing),
                    ),
                    SequenceHistoryChildActivity(
                        ActivityExecutionId("child"),
                        ActivityExecutionStatus.COMPLETED,
                        START.plusSeconds(10),
                        START.plusSeconds(70),
                        Duration.ofMinutes(1),
                        listOf(childField),
                        zone,
                        null,
                    ),
                ),
                occurrence(
                    "skipped",
                    1,
                    RuntimeOccurrenceStatus.SKIPPED,
                    sourceNodeId = "source-repeat-step",
                    repeatParentNodeId = "repeat",
                    repeatIteration = 2,
                ),
                occurrence(
                    "not-started",
                    2,
                    RuntimeOccurrenceStatus.NOT_STARTED,
                    sourceNodeId = null,
                    isRuntimeAdded = true,
                ),
                occurrence("tombstone", 3, RuntimeOccurrenceStatus.DELETED_EXECUTION),
            )
        val intervals =
            SequenceIntervalKind.entries.mapIndexed { index, kind ->
                SequenceInterval(
                    SequenceIntervalId("interval-$index"),
                    kind,
                    START.plusSeconds(index * 10L),
                    START.plusSeconds(index * 10L + 5),
                    occurrences.getOrNull(index)?.occurrenceId,
                )
            }
        return SequenceHistoryDetail(
            CompletedSequenceHistoryRoot(
                SequenceExecutionId("sequence"),
                SequenceSnapshotId("sequence-snapshot"),
                LocalDate.parse("2026-01-01"),
                END,
                START,
                status,
                Duration.ofMinutes(10),
                Duration.ofMinutes(2),
                Duration.ofMinutes(15),
                null,
                "Historical sequence",
                "Frozen sequence comment",
                zone,
                null,
            ),
            END,
            zone,
            SequenceSnapshotSettings(
                true,
                Duration.ZERO,
                Duration.ZERO,
                true,
                true,
                false,
                true,
                true,
                NoLiveTimeAccounting.ACTIVE,
            ),
            emptyList(),
            occurrences,
            intervals,
        )
    }

    private fun sequenceMutationDetail(): SequenceHistoryDetail {
        val base = sequenceDetail(SequenceExecutionStatus.COMPLETED, ZoneId.of("America/Los_Angeles"))
        return base.copy(
            occurrences =
                base.occurrences.map { occurrence ->
                    occurrence.takeIf { it.occurrenceId.value == "performed" }?.let {
                        val child = requireNotNull(it.child)
                        it.copy(
                            childMutationFacts =
                                SequenceHistoryChildMutationFacts(
                                    child.executionId,
                                    child.startedAt,
                                    requireNotNull(child.completedAt),
                                    emptyList(),
                                ),
                        )
                    } ?: occurrence
                },
        )
    }

    private fun structuralMutationDetail(): SequenceHistoryDetail {
        val base = sequenceMutationDetail()
        val performed =
            base.occurrences
                .first()
                .copy(
                    repeatSourceSnapshotNodeId = SequenceSnapshotNodeId("repeat"),
                    repeatIteration = 2,
                )

        fun performedOccurrence(
            id: String,
            position: Int,
            title: String,
            start: Instant,
            end: Instant,
            deleted: Boolean,
        ): SequenceHistoryOccurrence {
            val activity = occurrenceActivity(title)
            val childId = ActivityExecutionId("child-$id")
            return SequenceHistoryOccurrence(
                SequenceOccurrenceId(id),
                position,
                activity.snapshotId,
                SequenceSnapshotNodeId("source-$id"),
                null,
                null,
                false,
                false,
                if (deleted) RuntimeOccurrenceStatus.DELETED_EXECUTION else RuntimeOccurrenceStatus.COMPLETED,
                start,
                end,
                null,
                activity,
                if (deleted) {
                    null
                } else {
                    SequenceHistoryChildActivity(
                        childId,
                        ActivityExecutionStatus.COMPLETED,
                        start,
                        end,
                        Duration.between(start, end),
                        emptyList(),
                        ZoneId.of("UTC"),
                        null,
                    )
                },
                SequenceHistoryChildMutationFacts(
                    childId,
                    start,
                    end,
                    listOf(
                        com.alexandr5476.lifetracing.domain.ActivityExecutionPause(
                            com.alexandr5476.lifetracing.domain
                                .ActivityExecutionPauseId("pause-$id"),
                            start.plusSeconds(10),
                            start.plusSeconds(20),
                        ),
                    ),
                ),
            )
        }
        val deleted =
            performedOccurrence(
                "deleted-later",
                1,
                "Deleted later",
                START.plusSeconds(70),
                START.plusSeconds(130),
                true,
            )
        val retained =
            performedOccurrence("retained", 2, "Retained later", START.plusSeconds(130), START.plusSeconds(190), false)
        return base.copy(
            occurrences = listOf(performed, deleted, retained),
            intervals =
                listOf(
                    SequenceInterval(
                        SequenceIntervalId("performed"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        START.plusSeconds(10),
                        START.plusSeconds(70),
                        performed.occurrenceId,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("deleted-later"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        START.plusSeconds(70),
                        START.plusSeconds(130),
                        deleted.occurrenceId,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("retained"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        START.plusSeconds(130),
                        START.plusSeconds(190),
                        retained.occurrenceId,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("ownerless"),
                        SequenceIntervalKind.EXPLICIT_PAUSE,
                        START.plusSeconds(300),
                        START.plusSeconds(310),
                        null,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("ownerless-2"),
                        SequenceIntervalKind.IMPLICIT_IDLE,
                        START.plusSeconds(330),
                        START.plusSeconds(340),
                        null,
                    ),
                ),
        )
    }

    private fun occurrence(
        id: String,
        position: Int,
        status: RuntimeOccurrenceStatus,
        sourceNodeId: String? = "source-$id",
        repeatParentNodeId: String? = null,
        repeatIteration: Int? = null,
        isRuntimeAdded: Boolean = false,
    ) = SequenceHistoryOccurrence(
        SequenceOccurrenceId(id),
        position,
        ActivitySnapshotId("snapshot-$id"),
        sourceNodeId?.let(::SequenceSnapshotNodeId),
        repeatParentNodeId?.let(::SequenceSnapshotNodeId),
        repeatIteration,
        isRuntimeAdded,
        false,
        status,
        null,
        null,
        null,
        occurrenceActivity(id),
        null,
    )

    private fun occurrenceActivity(
        title: String,
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        target: Duration? = null,
    ) = SequenceHistoryOccurrenceActivity(
        ActivitySnapshotId("snapshot-${title.lowercase()}"),
        title,
        null,
        mode,
        target,
        ActivityTemplateSettings(),
        null,
    )

    companion object {
        private val START = Instant.parse("2026-01-02T01:00:00Z")
        private val END = START.plusSeconds(900)
    }
}
