@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package com.alexandr5476.lifetracing

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.launcher.LauncherCommandState
import com.alexandr5476.lifetracing.launcher.LauncherDurableCommand
import com.alexandr5476.lifetracing.launcher.LauncherLoad
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.launcher.StartActivityController
import com.alexandr5476.lifetracing.launcher.StartActivityRoute
import com.alexandr5476.lifetracing.launcher.StartActivityRouteSession
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.onLazyNode
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class StartActivityAccessibilityPersistenceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val keyboardInput = KeyboardInputFixture(compose)

    @Test
    fun semanticActivitySelectionPreservesCanonicalRuntimeAndSnapshotAfterPhysicalReopen() = launchAndReopen(false)

    @Test
    fun semanticSequenceSelectionUsesExactWriterAndSurvivesPhysicalReopen() = launchAndReopen(true)

    private fun launchAndReopen(sequence: Boolean) {
        Fixture().use { fixture ->
            val activity =
                fixture.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Accessible Activity", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = fixture.now,
                )
            val id =
                if (sequence) {
                    LibraryTemplateId.Sequence(
                        fixture.store.authoring
                            .createSequenceTemplate(
                                SequenceTemplateDraft(
                                    "Accessible Sequence",
                                    null,
                                    nodes =
                                        listOf(
                                            SequenceNodeDraft.Step(
                                                ActivityStepDraft(
                                                    DraftIdentity.New("step"),
                                                    0,
                                                    StepActivityDraft.FromTemplate(activity.id),
                                                ),
                                            ),
                                        ),
                                ),
                                createdAt = fixture.now,
                            ).id,
                    )
                } else {
                    LibraryTemplateId.Activity(activity.id)
                }
            fixture.start()
            val title = if (sequence) "Accessible Sequence" else "Accessible Activity"
            if (sequence) {
                invoke(R.string.launcher_browse_open)
                compose.waitUntil(10_000) { fixture.controller.state.value.browse is LauncherLoad.Content }
            } else {
                compose
                    .onLazyNode(
                        hasText(compose.activity.getString(R.string.launcher_search_hint)) and hasSetTextAction(),
                    ).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(title)) }
                compose.waitUntil(10_000) { fixture.controller.state.value.search is LauncherLoad.Content }
            }
            compose
                .onLazyNode(
                    launcherButton(title),
                ).performSemanticsAction(SemanticsActions.RequestFocus)
                .assertIsFocused()
            assertTrue(fixture.writes.isEmpty())
            invoke(title)
            fixture.awaitCommit()
            val command = fixture.writes.single()
            if (sequence) {
                assertEquals(id.value, (command as LauncherDurableCommand.StartSequence).templateId.value)
            } else {
                assertEquals(id.value, (command as LauncherDurableCommand.StartActivity).templateId.value)
            }
            val session = requireNotNull(fixture.store.live.getActiveSession())
            val runtime = requireNotNull(fixture.store.live.getActiveRuntime())
            val daily = fixture.daily()
            val facts = fixture.store.durableFacts()
            fixture.reopen()
            assertEquals(session, fixture.store.live.getActiveSession())
            assertEquals(runtime, fixture.store.live.getActiveRuntime())
            assertEquals(daily, fixture.daily())
            assertEquals(facts, fixture.store.durableFacts())
            if (runtime is ActiveActivityRuntime) {
                assertEquals(activity.id, runtime.snapshot.sourceTemplateId)
                assertEquals(activity.revision, runtime.snapshot.sourceRevision)
                assertEquals(activity.statisticsSeriesId, runtime.snapshot.statisticsSeriesId)
                assertEquals(runtime.snapshot.id, runtime.execution.snapshotId)
                assertEquals(1L to 0L, fixture.store.executionCounts())
            } else {
                val running = runtime as ActiveSequenceRuntime
                assertEquals(id.value, running.snapshot.sourceTemplateId?.value)
                assertEquals(running.snapshot.id, running.execution.snapshotId)
                assertEquals(1L to 1L, fixture.store.executionCounts())
            }
            assertEquals(1, fixture.commits.get())
        }
    }

    @Test
    fun semanticQuickZeroPreservesFieldAndStatisticsIdentityWithoutTouchingLiveSession() = quickAndReopen(false)

    @Test
    fun semanticQuickMissingIsNeverNumericZeroAfterPhysicalReopen() = quickAndReopen(true)

    @Test
    fun semanticUntouchedQuickDefaultPreservesUnroundedStoredValue() = quickAndReopen(missing = false, unchanged = true)

    private fun quickAndReopen(
        missing: Boolean,
        unchanged: Boolean = false,
    ) {
        Fixture().use { fixture ->
            val live =
                fixture.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Unrelated live", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = fixture.now,
                )
            fixture.store.library.startActivityFromTemplate(live.id, fixture.now, fixture.now, ZoneOffset.UTC)
            val active = fixture.store.live.getActiveRuntime()
            val template =
                fixture.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "Quick number",
                        null,
                        TimeTrackingMode.NO_LIVE_TRACKING,
                        null,
                        fields =
                            listOf(
                                ActivityFieldDraft(
                                    DraftIdentity.New("value"),
                                    0,
                                    "Quantity",
                                    CustomFieldType.NUMBER,
                                    displayPrecision = 2,
                                    defaultNumberScaled = 1234,
                                    isMainValue = true,
                                ),
                            ),
                    ),
                    createdAt = fixture.now,
                )
            fixture.store.library.pin(LibraryTemplateId.Activity(template.id))
            val templates = fixture.store.durableFacts().getValue("activity_templates")
            fixture.start()
            invoke("Quick number")
            compose.waitUntil(10_000) { fixture.session.interaction.quickEditor != null }
            assertEquals(
                template.fields.single().id,
                fixture.session.interaction.quickEditor!!
                    .fieldId,
            )
            assertEquals(
                LibraryTemplateId.Activity(template.id),
                fixture.session.interaction.quickEditor!!
                    .targetId,
            )
            val input = compose.onNode(hasText("Quantity") and hasSetTextAction()).performScrollTo()
            input.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
            assertTrue(fixture.writes.isEmpty())
            if (!unchanged) {
                input.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(if (missing) "" else "0")) }
            }
            invoke(R.string.launcher_complete)
            fixture.awaitCommit()
            val written = fixture.writes.single() as LauncherDurableCommand.CompleteNoLive
            assertEquals(template.id, written.templateId)
            if (unchanged) {
                assertNull(
                    written.override,
                )
            } else {
                assertEquals(template.fields.single().id, written.override!!.fieldId)
            }
            val root = fixture.history().single() as CompletedActivityHistoryRoot
            val saved = fixture.store.activity(root.executionId)
            assertEquals(template.statisticsSeriesId, saved.statisticsSeriesId)
            assertNull(saved.startedAt)
            assertNull(saved.activeDuration)
            if (missing) {
                assertTrue(
                    saved.values.isEmpty(),
                )
            } else {
                assertEquals(if (unchanged) 1234L else 0L, (saved.values.single() as NumberExecutionValue).scaledValue)
            }
            val snapshot = requireNotNull(fixture.store.activityCommands.getHistory(saved.id)).snapshot
            assertEquals(template.fields.single().id, snapshot.fields.single().sourceFieldId)
            assertEquals(template.id, snapshot.sourceTemplateId)
            assertEquals(active, fixture.store.live.getActiveRuntime())
            val facts = fixture.store.durableFacts()
            fixture.reopen()
            assertEquals(saved, fixture.store.activity(saved.id))
            assertEquals(
                snapshot,
                fixture.store.activityCommands
                    .getHistory(saved.id)!!
                    .snapshot,
            )
            assertEquals(active, fixture.store.live.getActiveRuntime())
            assertEquals(listOf(root), fixture.history())
            assertEquals(2L to 0L, fixture.store.executionCounts())
            assertEquals(templates, fixture.store.durableFacts().getValue("activity_templates"))
            assertEquals(facts, fixture.store.durableFacts())
        }
    }

    @Test
    fun semanticOneOffNoLiveCompletionCreatesOnlyOneHistoricalSystemBucketRoot() {
        Fixture().use { fixture ->
            fixture.start()
            invoke(R.string.launcher_new_one_off)
            val name =
                compose.onNode(
                    hasText(compose.activity.getString(R.string.launcher_one_off_name)) and hasSetTextAction(),
                )
            name.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("One off accessible")) }
            invoke(R.string.launcher_one_off_timer)
            compose
                .onNode(
                    hasText(compose.activity.getString(R.string.launcher_one_off_timer_minutes)) and hasSetTextAction(),
                ).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("0")) }
            assertTrue(fixture.writes.isEmpty())
            compose
                .onNode(hasText(compose.activity.getString(R.string.launcher_one_off_start)) and hasClickAction())
                .assertIsNotEnabled()
            invoke(R.string.launcher_one_off_no_live)
            invoke(R.string.launcher_complete)
            fixture.awaitCommit()
            assertTrue(fixture.writes.single() is LauncherDurableCommand.OneOff)
            val root = fixture.history().single() as CompletedActivityHistoryRoot
            val saved = fixture.store.activity(root.executionId)
            assertEquals(
                com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics.ONE_OFF_BUCKET_ID,
                saved.statisticsSeriesId,
            )
            val snapshot =
                fixture.store.activityCommands
                    .getHistory(saved.id)!!
                    .snapshot
            assertNull(snapshot.sourceTemplateId)
            assertNull(fixture.store.live.getActiveSession())
            fixture.reopen()
            assertEquals(saved, fixture.store.activity(saved.id))
            assertEquals(1L to 0L, fixture.store.executionCounts())
            assertTrue(
                fixture.store
                    .durableFacts()
                    .getValue("activity_templates")
                    .isEmpty(),
            )
            assertEquals(listOf(root), fixture.history())
        }
    }

    @Test
    fun semanticPinnedMoveUsesCompleteOrderAndLeavesAllTemplateAndSnapshotFactsUnchanged() {
        Fixture().use { fixture ->
            val templates =
                listOf("First", "Middle", "Last").map { name ->
                    fixture.store.authoring.createActivityTemplate(
                        ActivityTemplateDraft(name, null, TimeTrackingMode.STOPWATCH, null),
                        createdAt = fixture.now,
                    )
                }
            val ids = templates.map { LibraryTemplateId.Activity(it.id) }
            ids.forEach(fixture.store.library::pin)
            fixture.store.library.startActivityFromTemplate(
                templates[1].id,
                fixture.now,
                fixture.now,
                ZoneOffset.UTC,
            )
            fixture.store.live.completeActiveActivity(fixture.now)
            val before = fixture.store.durableFacts()
            fixture.start()
            compose
                .onLazyNode(
                    androidx.compose.ui.test
                        .hasContentDescription("Move Middle up"),
                ).performSemanticsAction(SemanticsActions.OnClick)
            val expected = listOf(ids[1], ids[0], ids[2])
            compose.waitUntil(10_000) {
                !fixture.controller.state.value.organizationInFlight &&
                    (fixture.controller.state.value.home as? LauncherLoad.Content)?.value?.pinned?.map { it.id } ==
                    expected
            }
            assertEquals(listOf(expected), fixture.reorders)
            assertEquals(
                expected,
                fixture.store.library
                    .getPinned()
                    .map { it.id },
            )
            templates.forEach { assertEquals(it, fixture.store.authoring.getActivityTemplate(it.id)) }
            val after = fixture.store.durableFacts()
            before.filterKeys { !it.endsWith("_user_state") }.forEach { (table, facts) ->
                assertEquals("Reordering must not change $table", facts, after.getValue(table))
            }
            before.filterKeys { it.endsWith("_user_state") }.forEach { (table, rows) ->
                assertEquals(
                    "Reordering preserves owner and Recent time",
                    rows.associate { it[0] to it[2] },
                    after.getValue(table).associate { it[0] to it[2] },
                )
            }
            assertTrue(fixture.writes.isEmpty())
            fixture.reopen()
            assertEquals(
                expected,
                fixture.store.library
                    .getPinned()
                    .map { it.id },
            )
        }
    }

    @Test
    fun semanticHistoricalOptionsValidateTimesAndPreserveExplicitOffsetsAndOverlapProposal() {
        Fixture().use { fixture ->
            fixture.zone = java.time.ZoneId.of("Europe/Berlin")
            val template =
                fixture.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "Historical",
                        null,
                        TimeTrackingMode.STOPWATCH,
                        null,
                        fields =
                            listOf(
                                ActivityFieldDraft(
                                    DraftIdentity.New("number"),
                                    0,
                                    "Amount",
                                    CustomFieldType.NUMBER,
                                    displayPrecision = 2,
                                ),
                            ),
                    ),
                    createdAt = fixture.now,
                )
            fixture.store.library.pin(LibraryTemplateId.Activity(template.id))
            fixture.start()
            compose
                .onNodeWithContentDescription(
                    compose.activity.getString(R.string.launcher_options_for, "Historical"),
                ).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(10_000) { fixture.controller.state.value.options is LauncherLoad.Content }

            fun draft() = (fixture.controller.state.value.options as LauncherLoad.Content).value

            fun set(
                id: Int,
                value: String,
            ) {
                compose
                    .onLazyNode(hasText(compose.activity.getString(id)) and hasSetTextAction())
                    .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(value)) }
            }
            for ((start, end, issue) in listOf(
                Triple(
                    "2026-03-29 02:30",
                    "",
                    com.alexandr5476.lifetracing.launcher.StartOptionsIssue.NONEXISTENT_START,
                ),
                Triple("2026-11-02 12:00", "", com.alexandr5476.lifetracing.launcher.StartOptionsIssue.FUTURE_START),
                Triple(
                    "2026-08-20 12:00",
                    "2026-08-20 11:00",
                    com.alexandr5476.lifetracing.launcher.StartOptionsIssue.REVERSED_INTERVAL,
                ),
            )) {
                set(R.string.manual_history_started, start)
                set(R.string.launcher_optional_end, end)
                invoke(R.string.manual_history_save)
                compose.waitUntil(10_000) { draft().issue == issue }
                assertTrue(fixture.writes.isEmpty())
                assertEquals(0L to 0L, fixture.store.executionCounts())
            }
            set(R.string.manual_history_started, "2026-10-25 02:10")
            set(R.string.launcher_optional_end, "2026-10-25 02:30")
            invoke(R.string.manual_history_save)
            compose.waitUntil(10_000) { draft().startedOffsets.size == 2 }
            assertNull(draft().startedOffset)

            fun offset(timestamp: Int) {
                val occurrence = compose.activity.getString(R.string.manual_history_second_occurrence, "+01:00")
                val label =
                    compose.activity.getString(
                        R.string.launcher_offset_choice,
                        compose.activity.getString(timestamp),
                        occurrence,
                    )
                compose
                    .onLazyNode(
                        androidx.compose.ui.test
                            .hasContentDescription(label),
                    ).performSemanticsAction(SemanticsActions.RequestFocus)
                    .assertIsFocused()
                assertTrue(fixture.writes.isEmpty())
                compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick)
            }
            offset(R.string.manual_history_started)
            assertEquals(ZoneOffset.ofHours(1), draft().startedOffset)
            invoke(R.string.manual_history_save)
            compose.waitUntil(10_000) { draft().completedOffsets.size == 2 }
            assertNull(draft().completedOffset)
            offset(R.string.manual_history_completed)
            assertEquals(ZoneOffset.ofHours(1), draft().completedOffset)
            val label = compose.activity.getString(R.string.launcher_field_actual, "Amount")

            fun number(value: String) {
                compose
                    .onLazyNode(hasText(label) and hasSetTextAction())
                    .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(value)) }
            }
            number("0")
            fixture.store.activityCommands.addManualTimed(
                com.alexandr5476.lifetracing.domain.ActivityEntrySource
                    .Template(template.id),
                Instant.parse("2026-10-25T01:00:00Z"),
                Instant.parse("2026-10-25T02:00:00Z"),
                fixture.now,
                fixture.zone,
            )
            invoke(R.string.manual_history_save)
            compose.waitUntil(10_000) { draft().overlap != null }
            val firstProposal = requireNotNull(draft().overlap)
            invoke(R.string.launcher_cancel_overlap)
            compose.waitUntil(10_000) { draft().overlap == null }
            assertTrue(fixture.writes.isEmpty())
            assertEquals(1L to 0L, fixture.store.executionCounts())
            invoke(R.string.manual_history_save)
            compose.waitUntil(10_000) { draft().overlap != null }
            number("1")
            assertNull(draft().overlap)
            number("0")
            assertTrue(draft().draftVersion > firstProposal.draftVersion)
            invoke(R.string.manual_history_save)
            compose.waitUntil(10_000) { draft().overlap != null }
            val approved = requireNotNull(draft().overlap)
            invoke(R.string.manual_history_proceed)
            fixture.awaitCommit()
            val command = fixture.writes.single() as LauncherDurableCommand.StartOptionsTimed
            assertEquals(approved, command.proposal)
            assertTrue(command.overlapApproved)
            assertEquals(template.revision, command.proposal.expectedRevision)
            assertEquals(Instant.parse("2026-10-25T01:10:00Z"), command.proposal.startedAt)
            assertEquals(Instant.parse("2026-10-25T01:30:00Z"), command.proposal.completedAt)
            assertEquals(fixture.zone, command.proposal.zoneId)
            val committed = fixture.controller.state.value.command as LauncherCommandState.Committed
            val result = committed.result as com.alexandr5476.lifetracing.launcher.LauncherCommit.Activity
            val history = fixture.store.activityCommands.getHistory(result.executionId)!!
            assertEquals(
                template.fields.single().id,
                history.snapshot.fields
                    .single()
                    .sourceFieldId,
            )
            assertEquals(0L, (history.execution.values.single() as NumberExecutionValue).scaledValue)
            fixture.reopen()
            assertEquals(history, fixture.store.activityCommands.getHistory(result.executionId))
            assertNull(fixture.store.live.getActiveSession())
            assertEquals(2L to 0L, fixture.store.executionCounts())
        }
    }

    @Test
    fun semanticPreflightCancellationAndBothBackActionsRespectPendingDurableCommit() {
        Fixture().use { fixture ->
            val template =
                fixture.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "Countdown",
                        null,
                        TimeTrackingMode.STOPWATCH,
                        null,
                        settings =
                            com.alexandr5476.lifetracing.domain.ActivityTemplateSettings(
                                startCountdown = java.time.Duration.ofSeconds(5),
                            ),
                    ),
                    createdAt = fixture.now,
                )
            val folder =
                com.alexandr5476.lifetracing.domain
                    .FolderId("commit-folder")
            fixture.store.library.createFolder(folder, "Commit folder", null, fixture.now)
            fixture.store.library.moveActivityTemplateToFolder(template.id, folder, fixture.now)
            fixture.start()
            invoke(R.string.launcher_browse_open)
            compose.waitUntil(10_000) { fixture.controller.state.value.browse is LauncherLoad.Content }
            invoke("Commit folder")
            compose.waitUntil(10_000) {
                fixture.controller.state.value.browseFolderId == folder &&
                    fixture.controller.state.value.browse is LauncherLoad.Content
            }
            invoke("Countdown")
            compose.waitUntil(10_000) {
                fixture.controller.state.value.command is LauncherCommandState.Preflight &&
                    fixture.preflightBoundary.get() != null
            }
            val obsoleteBoundary = fixture.preflightBoundary.get()!!
            val facts = fixture.store.durableFacts()
            invoke(R.string.launcher_cancel)
            compose.waitUntil(10_000) { fixture.controller.state.value.command == LauncherCommandState.Idle }
            compose.runOnUiThread { obsoleteBoundary() }
            assertTrue(fixture.writes.isEmpty())
            assertEquals(facts, fixture.store.durableFacts())
            assertEquals(0L to 0L, fixture.store.executionCounts())
            fixture.commitGate = kotlinx.coroutines.CompletableDeferred()
            invoke("Countdown")
            compose.waitUntil(10_000) {
                fixture.controller.state.value.command is LauncherCommandState.Preflight &&
                    fixture.preflightBoundary.get()?.let { it !== obsoleteBoundary } == true
            }
            compose.runOnUiThread { fixture.preflightBoundary.get()!!() }
            compose.waitUntil(10_000) { fixture.commitEntered.isCompleted }
            assertTrue(fixture.controller.state.value.command is LauncherCommandState.Committing)
            invoke(R.string.launcher_browse_back)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(false, fixture.hidden.value)
            assertEquals(0, fixture.backs.get())
            assertEquals(
                listOf(folder),
                fixture.session.interaction.browsePath
                    .map { it.id },
            )
            assertEquals(folder, fixture.controller.state.value.browseFolderId)
            assertEquals(0L to 0L, fixture.store.executionCounts())
            compose.runOnUiThread { fixture.commitGate!!.complete(Unit) }
            fixture.awaitCommit()
            assertEquals(1L to 0L, fixture.store.executionCounts())
            assertEquals(1, fixture.commits.get())
        }
    }

    @Test
    fun nestedToolbarBackCancelsPreflightAndObsoleteCallbackCannotWriteAfterReentry() =
        nestedBackCancellation(system = false, checking = false)

    @Test
    fun nestedSystemBackCancelsPreflightAndObsoleteCallbackCannotWriteAfterReentry() =
        nestedBackCancellation(system = true, checking = false)

    @Test
    fun nestedToolbarBackCancelsCheckingBeforeSchedulerRegistration() =
        nestedBackCancellation(system = false, checking = true)

    @Test
    fun nestedSystemBackCancelsCheckingBeforeSchedulerRegistration() =
        nestedBackCancellation(system = true, checking = true)

    private fun nestedBackCancellation(
        system: Boolean,
        checking: Boolean,
    ) {
        Fixture().use { fixture ->
            val parent =
                com.alexandr5476.lifetracing.domain
                    .FolderId("parent")
            val child =
                com.alexandr5476.lifetracing.domain
                    .FolderId("child")
            fixture.store.library.createFolder(parent, "Parent folder", null, fixture.now)
            fixture.store.library.createFolder(child, "Child folder", parent, fixture.now)
            val template =
                fixture.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft(
                        "Nested countdown",
                        null,
                        TimeTrackingMode.TIMER,
                        java.time.Duration.ofMinutes(2),
                        settings =
                            com.alexandr5476.lifetracing.domain.ActivityTemplateSettings(
                                startCountdown = java.time.Duration.ofSeconds(30),
                            ),
                    ),
                    createdAt = fixture.now,
                )
            fixture.store.library.moveActivityTemplateToFolder(template.id, child, fixture.now)
            val facts = fixture.store.durableFacts()
            val sessionBefore = fixture.store.live.getActiveSession()
            if (checking) fixture.checkingGate = kotlinx.coroutines.CompletableDeferred()
            fixture.start()

            fun back() {
                // Controller state may publish before Compose installs the corresponding Back handler.
                if (system) {
                    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                } else {
                    invoke(R.string.launcher_browse_back)
                }
                compose.waitForIdle()
            }

            fun enterChild() {
                invoke(R.string.launcher_browse_open)
                compose.waitUntil(10_000) { fixture.controller.state.value.browse is LauncherLoad.Content }
                invoke("Parent folder")
                compose.waitUntil(10_000) {
                    fixture.controller.state.value.browseFolderId == parent &&
                        fixture.controller.state.value.browse is LauncherLoad.Content
                }
                invoke("Child folder")
                compose.waitUntil(10_000) {
                    fixture.controller.state.value.browseFolderId == child &&
                        fixture.controller.state.value.browse is LauncherLoad.Content
                }
            }
            enterChild()
            back()
            compose.waitUntil(10_000) {
                fixture.controller.state.value.browseFolderId == parent &&
                    fixture.controller.state.value.browse is LauncherLoad.Content
            }
            assertEquals(
                listOf(parent),
                fixture.session.interaction.browsePath
                    .map { it.id },
            )
            assertEquals(0, fixture.backs.get())
            invoke("Child folder")
            compose.waitUntil(10_000) {
                fixture.controller.state.value.browseFolderId == child &&
                    fixture.controller.state.value.browse is LauncherLoad.Content
            }
            invoke("Nested countdown")
            compose.waitUntil(10_000) {
                if (checking) {
                    fixture.controller.state.value.command is LauncherCommandState.Checking &&
                        fixture.checkingEntered.isCompleted
                } else {
                    fixture.controller.state.value.command is LauncherCommandState.Preflight &&
                        fixture.preflightBoundary.get() != null
                }
            }
            val obsolete = fixture.preflightBoundary.get()
            val path = fixture.session.interaction.browsePath
            val selected = fixture.controller.state.value.selected
            back()
            assertEquals(1, fixture.backs.get())
            assertEquals(true, fixture.hidden.value)
            assertEquals(LauncherCommandState.Idle, fixture.controller.state.value.command)
            assertEquals(path, fixture.session.interaction.browsePath)
            assertEquals(child, fixture.controller.state.value.browseFolderId)
            assertEquals(selected, fixture.controller.state.value.selected)
            assertNull(fixture.session.interaction.pendingSelectionId)
            compose.runOnUiThread {
                fixture.checkingGate?.complete(Unit)
                obsolete?.invoke()
            }
            if (checking) assertNull(fixture.preflightBoundary.get())
            assertEquals(if (checking) 0 else 1, fixture.preflightCancellations.get())
            assertEquals(0, fixture.commits.get())
            assertTrue(fixture.writes.isEmpty())
            assertEquals(sessionBefore, fixture.store.live.getActiveSession())
            assertEquals(0L to 0L, fixture.store.executionCounts())
            assertEquals(facts, fixture.store.durableFacts())
            fixture.reopen()
            assertEquals(sessionBefore, fixture.store.live.getActiveSession())
            assertEquals(0L to 0L, fixture.store.executionCounts())
            assertEquals(facts, fixture.store.durableFacts())
            fixture.checkingGate = null
            fixture.preflightBoundary.set(null)
            fixture.start(reentry = true)
            assertTrue(
                fixture.session.interaction.browsePath
                    .isEmpty(),
            )
            assertNull(fixture.session.interaction.pendingSelectionId)
            assertEquals(LauncherLoad.Idle, fixture.controller.state.value.selected)
            assertTrue(fixture.writes.isEmpty())
            enterChild()
            invoke("Nested countdown")
            compose.waitUntil(10_000) {
                fixture.controller.state.value.command is LauncherCommandState.Preflight &&
                    fixture.preflightBoundary.get() != null
            }
            compose.runOnUiThread {
                obsolete?.invoke()
                assertTrue(fixture.controller.state.value.command is LauncherCommandState.Preflight)
                fixture.preflightBoundary.get()!!.invoke()
            }
            fixture.awaitCommit()
            val runtime = fixture.store.live.getActiveRuntime() as ActiveActivityRuntime
            assertEquals(template.id, runtime.snapshot.sourceTemplateId)
            assertEquals(template.revision, runtime.snapshot.sourceRevision)
            assertEquals(template.statisticsSeriesId, runtime.snapshot.statisticsSeriesId)
            assertEquals(runtime.snapshot.id, runtime.execution.snapshotId)
            assertEquals(
                template.id,
                (fixture.writes.single() as LauncherDurableCommand.StartActivity).templateId,
            )
            fixture.reopen()
            assertEquals(runtime, fixture.store.live.getActiveRuntime())
            assertEquals(1L to 0L, fixture.store.executionCounts())
            assertEquals(1, fixture.commits.get())
        }
    }

    @Test
    fun semanticConflictRetryAndCommittedCoordinationFailureNeverDuplicateLaunch() {
        Fixture().use { fixture ->
            val active =
                fixture.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Existing", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = fixture.now,
                )
            fixture.store.library.startActivityFromTemplate(active.id, fixture.now, fixture.now, fixture.zone)
            val candidate =
                fixture.store.authoring.createActivityTemplate(
                    ActivityTemplateDraft("Candidate", null, TimeTrackingMode.STOPWATCH, null),
                    createdAt = fixture.now,
                )
            fixture.store.library.pin(LibraryTemplateId.Activity(candidate.id))
            fixture.coordinationFailure = true
            fixture.start()
            invoke("Candidate")
            compose.waitUntil(10_000) { fixture.controller.state.value.command is LauncherCommandState.Conflict }
            invoke(R.string.launcher_retry)
            compose.waitUntil(10_000) { fixture.controller.state.value.command is LauncherCommandState.Conflict }
            assertTrue(fixture.writes.isEmpty())
            fixture.store.live.completeActiveActivity(fixture.now)
            invoke(R.string.launcher_retry)
            compose.waitUntil(10_000) { fixture.commits.get() == 1 }
            assertTrue(fixture.controller.state.value.command is LauncherCommandState.CommittedCoordinationFailure)
            assertEquals(candidate.id, (fixture.writes.single() as LauncherDurableCommand.StartActivity).templateId)
            assertEquals(
                candidate.id,
                (fixture.store.live.getActiveRuntime() as ActiveActivityRuntime).snapshot.sourceTemplateId,
            )
            fixture.reopen()
            assertEquals(2L to 0L, fixture.store.executionCounts())
            assertEquals(1, fixture.commits.get())
        }
    }

    private fun invoke(id: Int) = invoke(compose.activity.getString(id))

    private fun invoke(label: String) {
        compose.onLazyNode(launcherButton(label)).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun launcherButton(label: String) =
        hasText(label) and hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private inner class Fixture : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "launcher-accessibility-${UUID.randomUUID()}.db"
        val now = Instant.parse("2026-11-01T12:00:00Z")
        var zone: java.time.ZoneId = ZoneOffset.UTC
        var coordinationFailure = false
        var commitGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val commitEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val preflightBoundary =
            java.util.concurrent.atomic
                .AtomicReference<(() -> Unit)?>(null)
        var store = RuntimeRecoveryStore(context, name)
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var checkingGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val checkingEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val preflightCancellations = AtomicInteger()
        val backs = AtomicInteger()
        val writes = java.util.Collections.synchronizedList(mutableListOf<LauncherDurableCommand>())
        val reorders = java.util.Collections.synchronizedList(mutableListOf<List<LibraryTemplateId>>())
        val commits = AtomicInteger()
        val hidden = mutableStateOf(false)
        lateinit var controller: StartActivityController
        lateinit var session: StartActivityRouteSession

        fun start(reentry: Boolean = false) {
            if (reentry) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            controller =
                StartActivityController(
                    scope,
                    { limit -> withContext(Dispatchers.IO) { store.library.getRecent(limit) } },
                    { withContext(Dispatchers.IO) { store.library.getPinned() } },
                    { query -> withContext(Dispatchers.IO) { store.library.search(query) } },
                    { folder ->
                        withContext(Dispatchers.IO) {
                            if (folder ==
                                null
                            ) {
                                store.library.getRoot().contents
                            } else {
                                store.library.getFolderContents(folder)
                            }
                        }
                    },
                    { id -> withContext(Dispatchers.IO) { store.library.getLaunchTarget(id) } },
                    { ids ->
                        withContext(Dispatchers.IO) {
                            reorders += ids
                            store.library.reorderPinned(ids)
                        }
                    },
                    { withContext(Dispatchers.IO) { store.live.getActiveSession() != null } },
                    { command ->
                        commitEntered.complete(Unit)
                        commitGate?.await()
                        withContext(Dispatchers.IO) {
                            writes += command
                            executeLauncherCommand(command, store.activityCommands, store.library)
                        }
                    },
                    { if (coordinationFailure) error("Controlled post-commit coordination failure") },
                    WallClock { now },
                    { zone },
                    PreflightScheduler { _, boundary ->
                        preflightBoundary.set(boundary)
                        PreflightHandle { preflightCancellations.incrementAndGet() }
                    },
                    initialLiveConflict = { target ->
                        checkingEntered.complete(Unit)
                        checkingGate?.await()
                        withContext(Dispatchers.IO) { store.library.hasLiveLaunchConflict(target.id, target.revision) }
                    },
                    readActivityTemplate = { id ->
                        withContext(Dispatchers.IO) { store.authoring.getActivityTemplate(id) }
                    },
                    overlapsCompletedHistory = {
                        start,
                        end,
                        ->
                        withContext(Dispatchers.IO) { store.activityCommands.overlapsCompletedHistory(start, end) }
                    },
                )
            session =
                StartActivityRouteSession(
                    controller,
                    com.alexandr5476.lifetracing.launcher
                        .LauncherRouteExitPolicy(),
                )
            if (reentry) {
                compose.runOnIdle { hidden.value = false }
            } else {
                compose.setContent {
                    LifeTracingTheme {
                        if (hidden.value) {
                            Text("Daily received committed launch")
                        } else {
                            StartActivityRoute(
                                session,
                                onBack = {
                                    backs.incrementAndGet()
                                    hidden.value = true
                                },
                                onCommitted = {
                                    commits.incrementAndGet()
                                    hidden.value = true
                                },
                            )
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { controller.state.value.home is LauncherLoad.Content }
            keyboardInput.enter()
        }

        fun awaitCommit() {
            compose.waitUntil(10_000) { commits.get() == 1 }
            assertTrue(controller.state.value.command is LauncherCommandState.Committed)
            assertEquals(1, writes.size)
        }

        fun daily() =
            store.daily.getDaily(DailyQuery(now.atZone(ZoneOffset.UTC).toLocalDate(), now, 10, ZoneOffset.UTC))

        fun history() =
            store.history.getCompletedRoots(
                CompletedHistoryQuery(
                    HistoryDateRange(
                        now.atZone(ZoneOffset.UTC).toLocalDate(),
                        now.atZone(ZoneOffset.UTC).toLocalDate(),
                    ),
                    10,
                ),
            )

        fun reopen() {
            compose.runOnIdle {
                hidden.value = true
                controller.close()
            }
            scope.cancel()
            compose.waitForIdle()
            store.close()
            store = RuntimeRecoveryStore(context, name)
        }

        override fun close() {
            if (::controller.isInitialized) {
                compose.runOnIdle {
                    hidden.value = true
                    controller.close()
                }
            }
            scope.cancel()
            store.close()
            context.deleteDatabase(name)
        }
    }
}
