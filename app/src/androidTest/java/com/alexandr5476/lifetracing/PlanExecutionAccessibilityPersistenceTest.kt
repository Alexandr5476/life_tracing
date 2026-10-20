@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package com.alexandr5476.lifetracing

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WallClock
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.launcher.PreflightHandle
import com.alexandr5476.lifetracing.launcher.PreflightScheduler
import com.alexandr5476.lifetracing.plan.PlanExecutionCommandState
import com.alexandr5476.lifetracing.plan.PlanExecutionController
import com.alexandr5476.lifetracing.plan.PlanExecutionDurableCommand
import com.alexandr5476.lifetracing.plan.PlanExecutionLoad
import com.alexandr5476.lifetracing.plan.PlanExecutionOrigin
import com.alexandr5476.lifetracing.plan.PlanExecutionRoute
import com.alexandr5476.lifetracing.plan.PlanExecutionRouteSession
import com.alexandr5476.lifetracing.plan.PlanTimeEntryIssue
import com.alexandr5476.lifetracing.ui.KeyboardInputFixture
import com.alexandr5476.lifetracing.ui.theme.LifeTracingTheme
import kotlinx.coroutines.CompletableDeferred
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PlanExecutionAccessibilityPersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val keyboard = KeyboardInputFixture(compose)

    @Test
    fun dailyOriginSemanticTimedStartPreservesExactFrozenIdentityAfterReopen() =
        liveStart(false, PlanExecutionOrigin.DAILY)

    @Test
    fun planOriginSemanticSequenceStartPreservesExactFrozenIdentityAfterReopen() =
        liveStart(true, PlanExecutionOrigin.PLAN)

    private fun liveStart(
        sequence: Boolean,
        origin: PlanExecutionOrigin,
    ) {
        Fixture().use { f ->
            val source = f.source()
            val plan =
                if (sequence) {
                    val template =
                        f.store.authoring.createSequenceTemplate(
                            SequenceTemplateDraft(
                                "Frozen Sequence",
                                null,
                                nodes =
                                    listOf(
                                        SequenceNodeDraft.Step(
                                            ActivityStepDraft(
                                                DraftIdentity.New("step"),
                                                0,
                                                StepActivityDraft.FromTemplate(source.id),
                                            ),
                                        ),
                                    ),
                            ),
                            createdAt = f.now,
                        )
                    f.store.plans.createSequencePlanFromTemplate(template.id, f.schedule(), f.now)
                } else {
                    f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.now)
                }
            f.start(plan, origin)
            invoke(R.string.plan_start_action)
            f.awaitCommit()
            assertEquals(plan.actionIdentity(), f.writes.single().identity)
            f.reopen()
            val focused = f.store.planReads.getFocusedAction(plan.id)
            assertEquals(plan.actionIdentity(), focused.identity)
            assertTrue(focused.engaged)
            assertEquals(
                PlanEntryStatus.PLANNED,
                f.store.plans
                    .getPlan(plan.id)
                    ?.status,
            )
            if (sequence) {
                val runtime = f.store.live.getActiveRuntime() as ActiveSequenceRuntime
                assertEquals(plan.id, runtime.execution.planEntryId)
                assertEquals(plan.sequenceSnapshotId, runtime.execution.snapshotId)
                assertEquals(1L to 1L, f.store.executionCounts())
                assertEquals(
                    0L,
                    f.store.statistics
                        .sequenceSeries(
                            requireNotNull(runtime.execution.statisticsSeriesId),
                            StatisticsPeriod.AllTime,
                        ).executionCount,
                )
            } else {
                val runtime = f.store.live.getActiveRuntime() as ActiveActivityRuntime
                assertEquals(plan.id, runtime.execution.planEntryId)
                assertEquals(plan.activitySnapshotId, runtime.execution.snapshotId)
                assertEquals(1L to 0L, f.store.executionCounts())
                assertEquals(
                    0L,
                    f.store.statistics
                        .activitySeries(
                            source.statisticsSeriesId,
                            StatisticsPeriod.AllTime,
                        ).executionCount,
                )
            }
            assertTrue(
                f.store.daily
                    .getDaily(DailyQuery(f.day(), f.now, 20, f.zone))
                    .active != null,
            )
            assertTrue(
                f.store.history
                    .getCompletedRoots(
                        CompletedHistoryQuery(HistoryDateRange(f.day(), f.day()), 20),
                    ).isEmpty(),
            )
        }
    }

    @Test
    fun noLiveExplicitZeroUsesOnlyEditedOverrideAndFulfillsWithoutLiveSession() = noLive("0")

    @Test
    fun noLiveMissingUsesExactFieldAndRetainsMissingDurationAfterReopen() = noLive("")

    @Test
    fun untouchedRoundedDefaultRemainsConfiguredRatherThanExplicitActual() = noLive(null)

    private fun noLive(edit: String?) {
        Fixture().use { f ->
            val source = f.source(noLive = true, fields = true)
            val plan = f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.now)
            f.start(plan, PlanExecutionOrigin.PLAN)
            val snapshot =
                (
                    f.store.planReads
                        .getFocusedAction(
                            plan.id,
                        ).snapshot as FocusedPlanAction.Snapshot.Activity
                ).value
            val field = snapshot.fields.single()
            val label = compose.activity.getString(R.string.plan_execution_main_value, "Amount kg")
            compose
                .onNode(hasText(label) and hasSetTextAction())
                .performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle()
            assertTrue(requireNotNull(f.session.quickDraft).changed.isEmpty())
            assertTrue(f.writes.isEmpty())
            if (edit != null) {
                set(label, "invalid")
                compose.onNode(button(R.string.plan_complete_action)).assertIsNotEnabled()
                set(label, "-1.25")
                assertEquals(
                    -1_250L,
                    (
                        f.session.quickDraft
                            ?.values
                            ?.get(field.id) as NumberExecutionValue
                    ).scaledValue,
                )
                if (edit.isEmpty()) {
                    invoke(compose.activity.getString(R.string.plan_execution_field_missing, label))
                } else {
                    set(label, edit)
                }
            }
            invoke(R.string.plan_complete_action)
            f.awaitCommit()
            val overrides = f.writes.single().values
            if (edit == null) {
                assertTrue(overrides.isEmpty())
            } else {
                assertEquals(field.id, overrides.single().snapshotFieldId)
                assertEquals(if (edit.isEmpty()) null else NumberExecutionValue(field.id, 0), overrides.single().value)
            }
            f.reopen()
            val saved = f.store.plans.getPlan(plan.id)!!
            val execution = f.store.activity(saved.fulfilledActivityExecutionId!!)
            assertEquals(PlanEntryStatus.FULFILLED, saved.status)
            assertEquals(plan.activitySnapshotId, execution.snapshotId)
            assertEquals(plan.id, execution.planEntryId)
            assertNull(execution.startedAt)
            assertEquals(
                if (edit == "") null else NumberExecutionValue(field.id, if (edit == null) 1_234_567 else 0),
                execution.values.singleOrNull(),
            )
            assertEquals(source.statisticsSeriesId, execution.statisticsSeriesId)
            assertNull(execution.activeDuration)
            assertNull(f.store.live.getActiveSession())
            assertEquals(1L to 0L, f.store.executionCounts())
            val detail = f.store.history.getActivityDetail(execution.id)!!
            assertEquals(execution.id, detail.root.executionId)
            assertEquals(
                1L,
                f.store.statistics
                    .activitySeries(source.statisticsSeriesId, StatisticsPeriod.AllTime)
                    .executionCount,
            )
            assertEquals(
                1,
                f.store.daily
                    .getDaily(DailyQuery(f.day(), f.now, 20, f.zone))
                    .completedHistory.size,
            )
        }
    }

    @Test
    fun explicitIndependentDstOffsetsAndCurrentOverlapConfirmationPersistExactHistoricalFacts() {
        Fixture().use { f ->
            f.zone = ZoneId.of("America/New_York")
            val source = f.source()
            val start = Instant.parse("2026-11-01T05:10:00Z")
            val end = Instant.parse("2026-11-01T06:50:00Z")
            f.store.activityCommands.addManualTimed(
                ActivityEntrySource.Template(source.id),
                start,
                end,
                f.now,
                f.zone,
            )
            val plan = f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.now.minusSeconds(86_400))
            f.start(plan, PlanExecutionOrigin.DAILY)
            invoke(R.string.plan_time_entry_action)
            set(R.string.plan_time_entry_start, "2026-11-01T01:10")
            set(R.string.plan_time_entry_optional_end, "2026-11-01T01:50")
            invoke(R.string.plan_time_entry_save)
            assertEquals(PlanTimeEntryIssue.AMBIGUOUS_START, f.session.timeDraft?.issue)
            val first = offsetLabel(true, false, "-04:00")
            val second = offsetLabel(false, true, "-05:00")
            compose
                .onNode(hasText(first) and hasClickAction())
                .assertIsNotSelected()
                .performSemanticsAction(SemanticsActions.RequestFocus)
            assertNull(f.session.timeDraft?.startedOffset)
            invoke(first)
            invoke(second)
            compose.onNode(hasText(first, substring = true) and hasClickAction()).assertIsSelected()
            compose.onNode(hasText(second, substring = true) and hasClickAction()).assertIsSelected()
            assertEquals(ZoneOffset.ofHours(-4), f.session.timeDraft?.startedOffset)
            assertEquals(ZoneOffset.ofHours(-5), f.session.timeDraft?.completedOffset)
            invoke(R.string.plan_time_entry_save)
            compose.waitUntil(10_000) { f.controller.state.value.command is PlanExecutionCommandState.Overlap }
            val old = (f.controller.state.value.command as PlanExecutionCommandState.Overlap).request
            assertEquals(start, old.startedAt)
            assertEquals(end, old.completedAt)
            assertTrue(f.writes.isEmpty())
            compose.onNode(hasText("2026-11-01T01:10-04:00[America/New_York]", substring = true)).assertExists()
            invoke(R.string.plan_execution_cancel_overlap)
            assertTrue(f.writes.isEmpty())
            invoke(R.string.plan_time_entry_save)
            compose.waitUntil(10_000) { f.controller.state.value.command is PlanExecutionCommandState.Overlap }
            set(R.string.plan_time_entry_optional_end, "2026-11-01T01:40")
            compose.runOnIdle { f.controller.launchTimeEntry(old, confirmOverlap = true) }
            assertTrue(f.writes.isEmpty())
            assertNull(f.session.timeDraft?.completedOffset)
            invoke(offsetLabel(false, true, "-05:00"))
            invoke(R.string.plan_time_entry_save)
            compose.waitUntil(10_000) { f.controller.state.value.command is PlanExecutionCommandState.Overlap }
            val current = (f.controller.state.value.command as PlanExecutionCommandState.Overlap).request
            invoke(R.string.plan_time_entry_confirm)
            f.awaitCommit()
            assertEquals(current.copy(overlapApproved = true), f.writes.single().timeEntry)
            f.reopen()
            val saved = f.store.plans.getPlan(plan.id)!!
            val execution = f.store.activity(saved.fulfilledActivityExecutionId!!)
            assertEquals(plan.id, execution.planEntryId)
            assertEquals(plan.activitySnapshotId, execution.snapshotId)
            assertEquals(start, execution.startedAt)
            assertEquals(Instant.parse("2026-11-01T06:40:00Z"), execution.completedAt)
            assertEquals(f.zone, execution.originalZoneId)
            assertEquals(-240, execution.originalUtcOffsetMinutes)
            assertEquals(Duration.ofMinutes(90), execution.activeDuration)
            assertEquals(PlanEntryStatus.FULFILLED, saved.status)
            assertNull(f.store.live.getActiveSession())
            assertEquals(2L to 0L, f.store.executionCounts())
            assertEquals(
                2L,
                f.store.statistics
                    .activitySeries(source.statisticsSeriesId, StatisticsPeriod.AllTime)
                    .executionCount,
            )
            assertEquals(
                2,
                f.store.daily
                    .getDaily(DailyQuery(f.day(), f.now, 20, f.zone))
                    .completedHistory.size,
            )
        }
    }

    @Test
    fun semanticPreflightCancelExitsBeforeAdmissionAndRejectsObsoleteBoundary() {
        Fixture().use { f ->
            val source = f.source(countdown = Duration.ofSeconds(5))
            val plan = f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.now)
            f.start(plan, PlanExecutionOrigin.DAILY)
            val before = f.store.durableFacts()
            invoke(R.string.plan_start_action)
            compose.waitUntil(10_000) { f.boundary.get() != null }
            invoke(R.string.plan_execution_cancel)
            compose.runOnIdle { f.boundary.get()!!.invoke() }
            compose.waitForIdle()
            assertEquals(1, f.backs.get())
            assertTrue(f.writes.isEmpty())
            assertEquals(before, f.store.durableFacts())
        }
    }

    @Test
    fun toolbarAndSystemBackWaitForAdmittedCommitThenDeliverSavedResultOnce() {
        Fixture().use { f ->
            val source = f.source()
            val plan = f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.now)
            f.gate = CompletableDeferred()
            f.start(plan, PlanExecutionOrigin.PLAN)
            invoke(R.string.plan_start_action)
            compose.waitUntil(10_000) { f.entered.isCompleted }
            invoke(R.string.plan_back)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(0, f.backs.get())
            assertEquals(0, f.commits.get())
            f.gate!!.complete(Unit)
            f.awaitCommit()
            compose.runOnIdle {
                f.session.exitPolicy.onCommand(
                    f.controller.state.value.command,
                ) { f.commits.incrementAndGet() }
            }
            assertEquals(1, f.commits.get())
            f.reopen()
            assertEquals(1L to 0L, f.store.executionCounts())
            assertTrue(
                f.store.planReads
                    .getFocusedAction(plan.id)
                    .engaged,
            )
        }
    }

    @Test
    fun overlapIntroducedAfterControllerCheckIsRevalidatedByWriterBeforeAnyPlanMutation() {
        Fixture().use { f ->
            val source = f.source()
            val plan = f.store.plans.createActivityPlanFromTemplate(source.id, f.schedule(), f.now.minusSeconds(86_400))
            f.gate = CompletableDeferred()
            f.start(plan, PlanExecutionOrigin.PLAN)
            invoke(R.string.plan_time_entry_action)
            set(R.string.plan_time_entry_start, "2026-11-01T09:00")
            set(R.string.plan_time_entry_optional_end, "2026-11-01T10:00")
            invoke(R.string.plan_time_entry_save)
            compose.waitUntil(10_000) { f.entered.isCompleted }
            f.store.activityCommands.addManualTimed(
                ActivityEntrySource.Template(source.id),
                Instant.parse("2026-11-01T09:30:00Z"),
                Instant.parse("2026-11-01T09:45:00Z"),
                f.now,
                f.zone,
            )
            f.gate!!.complete(Unit)
            compose.waitUntil(10_000) { f.controller.state.value.command is PlanExecutionCommandState.Overlap }
            assertEquals(
                PlanEntryStatus.PLANNED,
                f.store.plans
                    .getPlan(plan.id)
                    ?.status,
            )
            assertEquals(1L to 0L, f.store.executionCounts())
            assertNull(f.store.live.getActiveSession())
            val checked = (f.controller.state.value.command as PlanExecutionCommandState.Overlap).request
            invoke(R.string.plan_time_entry_confirm)
            compose.waitUntil(10_000) { f.commits.get() == 1 }
            assertEquals(2, f.writes.size)
            assertEquals(checked.copy(overlapApproved = true), f.writes.last().timeEntry)
            f.reopen()
            assertEquals(2L to 0L, f.store.executionCounts())
            assertEquals(
                PlanEntryStatus.FULFILLED,
                f.store.plans
                    .getPlan(plan.id)
                    ?.status,
            )
            val detail =
                f.store.history.getActivityDetail(
                    f.store.plans
                        .getPlan(plan.id)!!
                        .fulfilledActivityExecutionId!!,
                )!!
            assertEquals(plan.id, detail.root.planEntryId)
            assertEquals(plan.activitySnapshotId, detail.root.snapshotId)
            assertEquals(Duration.ofHours(1), detail.root.activeDuration)
        }
    }

    private fun offsetLabel(
        start: Boolean,
        second: Boolean,
        offset: String,
    ): String =
        compose.activity.getString(
            R.string.plan_execution_offset,
            compose.activity.getString(
                if (start) R.string.plan_time_entry_start else R.string.plan_time_entry_completion,
            ),
            compose.activity.getString(
                if (second) R.string.manual_history_second_occurrence else R.string.manual_history_first_occurrence,
                offset,
            ),
        )

    private fun button(id: Int) = hasText(compose.activity.getString(id)) and hasClickAction()

    private fun invoke(id: Int) = invoke(compose.activity.getString(id))

    private fun invoke(text: String) {
        compose
            .onNode(
                hasText(text) and hasClickAction(),
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun set(
        id: Int,
        text: String,
    ) = set(compose.activity.getString(id), text)

    private fun set(
        label: String,
        text: String,
    ) {
        compose
            .onNode(
                hasText(label) and hasSetTextAction(),
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetText) {
                it(AnnotatedString(text))
            }
    }

    private inner class Fixture : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "plan-accessibility-" + UUID.randomUUID() + ".db"
        val now = Instant.parse("2026-11-01T12:00:00Z")
        var zone: ZoneId = ZoneOffset.UTC
        var store = RuntimeRecoveryStore(context, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val boundary = AtomicReference<(() -> Unit)?>(null)
        val commits = AtomicInteger()
        val backs = AtomicInteger()
        val hidden = mutableStateOf(false)
        val writes = java.util.Collections.synchronizedList(mutableListOf<PlanExecutionDurableCommand>())
        val entered = CompletableDeferred<Unit>()
        var gate: CompletableDeferred<Unit>? = null
        private var originalTemplates: Map<String, List<List<String?>>> = emptyMap()
        lateinit var controller: PlanExecutionController
        lateinit var session: PlanExecutionRouteSession

        fun day() = now.atZone(zone).toLocalDate()

        fun schedule() = PlanSchedule.FloatingDay(day())

        fun source(
            noLive: Boolean = false,
            fields: Boolean = false,
            countdown: Duration = Duration.ZERO,
        ) = store.authoring.createActivityTemplate(
            ActivityTemplateDraft(
                "Frozen Activity",
                null,
                if (noLive) TimeTrackingMode.NO_LIVE_TRACKING else TimeTrackingMode.STOPWATCH,
                null,
                ActivityTemplateSettings(startCountdown = countdown),
                fields =
                    if (fields) {
                        listOf(
                            ActivityFieldDraft(
                                DraftIdentity.New("number"),
                                0,
                                "Amount",
                                CustomFieldType.NUMBER,
                                unit = "kg",
                                displayPrecision = 2,
                                defaultNumberScaled = 1_234_567,
                                isMainValue = true,
                            ),
                        )
                    } else {
                        emptyList()
                    },
            ),
            createdAt = now.minusSeconds(172_800),
        )

        fun start(
            plan: PlanEntry,
            origin: PlanExecutionOrigin,
        ) {
            originalTemplates =
                store.durableFacts().filterKeys { it == "activity_templates" || it == "sequence_templates" }
            controller =
                PlanExecutionController(
                    scope,
                    plan.actionIdentity(),
                    { id -> withContext(Dispatchers.IO) { store.planReads.getFocusedAction(id) } },
                    { withContext(Dispatchers.IO) { store.live.getActiveSession() != null } },
                    { command ->
                        entered.complete(Unit)
                        gate?.await()
                        withContext(Dispatchers.IO) {
                            writes += command
                            executePlanCommand(command, store.live, store.activityCommands)
                        }
                    },
                    {},
                    WallClock { now },
                    { zone },
                    PreflightScheduler { _, callback ->
                        boundary.set(callback)
                        PreflightHandle {}
                    },
                    overlapsCompletedHistory = { start, end ->
                        withContext(Dispatchers.IO) { store.activityCommands.overlapsCompletedHistory(start, end) }
                    },
                )
            session =
                PlanExecutionRouteSession(
                    plan.actionIdentity(),
                    origin,
                    controller,
                    now = { now },
                    deviceZone = { zone },
                )
            compose.setContent {
                LifeTracingTheme {
                    if (!hidden.value) {
                        PlanExecutionRoute(
                            session,
                            onBack = {
                                controller.close()
                                backs.incrementAndGet()
                                hidden.value = true
                            },
                            onReloadOrigin = {
                                controller.close()
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
            compose.waitUntil(10_000) { controller.state.value.prepared is PlanExecutionLoad.Content }
            compose.waitForIdle()
            keyboard.enter()
        }

        fun awaitCommit() {
            compose.waitUntil(10_000) { commits.get() == 1 }
            assertTrue(controller.state.value.command is PlanExecutionCommandState.Committed)
            assertEquals(1, writes.size)
        }

        fun reopen() {
            compose.runOnIdle {
                hidden.value = true
                controller.close()
            }
            scope.cancel()
            compose.waitForIdle()
            val facts = store.durableFacts()
            store.close()
            store = RuntimeRecoveryStore(context, name)
            assertEquals(facts, store.durableFacts())
            assertEquals(
                originalTemplates,
                store.durableFacts().filterKeys {
                    it == "activity_templates" ||
                        it == "sequence_templates"
                },
            )
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
