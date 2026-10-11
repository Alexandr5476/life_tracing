@file:Suppress("LongMethod", "MagicNumber")

package com.alexandr5476.lifetracing.plan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.daily.LocalDateBoundaryScheduler
import com.alexandr5476.lifetracing.data.persistence.PlanSchedulingStore
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.LibraryTemplateId
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.ReusablePlanCatalogItem
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.domain.actionIdentity
import com.alexandr5476.lifetracing.executePlanMutation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@RunWith(Parameterized::class)
class PlanExactDayPersistenceTest(
    private val sequence: Boolean,
) {
    @Test
    fun rejectedCreationHasNoDispatcherOrDurableEffectsAndCanBeCorrected() =
        runBlocking {
            for ((date, offset, error) in listOf(
                Triple("2026-03-29", null, PlanMessage.NONEXISTENT_TIME),
                Triple("2026-10-25", null, PlanMessage.AMBIGUOUS_TIME),
                Triple("2026-10-25", ZoneOffset.ofHours(3), PlanMessage.INVALID_OFFSET),
            )) {
                Fixture(sequence).use { fixture ->
                    fixture.ready()
                    val before = fixture.store.durableFacts()
                    fixture.newExact(date)
                    if (offset != null) fixture.choose(offset)
                    fixture.submit()
                    assertEquals(error, fixture.form.error)
                    assertTrue(fixture.commands.isEmpty())
                    assertEquals(before, fixture.store.durableFacts())
                    fixture.controller.dispatch(PlanAction.ChangeScheduleDate("2026-10-26"))
                    fixture.controller.dispatch(PlanAction.ChangeScheduleTime("10:00"))
                    fixture.submit()
                    fixture.reopen()
                    fixture.assertExact(fixture.onlyPlan("2026-10-26"), "2026-10-26T09:00:00Z")
                }
            }
        }

    @Test
    fun bothSelectedOccurrencesPersistThroughTheProductionDispatcherAndFreshCanonicalReads() =
        runBlocking {
            for ((offset, instant) in listOf(2 to "2026-10-25T00:30:00Z", 1 to "2026-10-25T01:30:00Z")) {
                Fixture(sequence).use { fixture ->
                    fixture.ready()
                    fixture.newExact()
                    fixture.choose(ZoneOffset.ofHours(offset))
                    fixture.submit()
                    assertEquals(sequence, fixture.commands.single() is PlanMutation.CreateSequence)
                    fixture.reopen()
                    val plan = fixture.onlyPlan()
                    fixture.assertExact(plan, instant)
                    assertEquals(
                        plan.actionIdentity(),
                        fixture.store.reads
                            .getFocusedAction(plan.id)
                            .identity,
                    )
                }
            }
        }

    @Test
    fun untouchedHighPrecisionTargetSurvivesPreservationZoneTravelDismissalAndReopening() =
        runBlocking {
            Fixture(sequence).use { fixture ->
                fixture.ready()
                val original = fixture.seed("2026-10-25T02:30:27.123", 1)
                val focus = fixture.store.reads.getFocusedAction(original.id)
                val before = fixture.store.durableFacts()
                fixture.store.forbidPlanUpdates()
                fixture.open(original)
                fixture.controller.dispatch(PlanAction.DismissForm)
                assertTrue(fixture.commands.isEmpty())
                fixture.open(original)
                assertTrue(fixture.form.offsetChoices.isEmpty())
                fixture.clock = fixture.clock.plusSeconds(1)
                fixture.submit()
                assertEquals(
                    original.actionIdentity(),
                    (fixture.commands.last() as PlanMutation.PreserveSchedule).identity,
                )
                fixture.open(original)
                fixture.zone = ZoneId.of("Pacific/Auckland")
                fixture.signal.value++
                fixture.clock = fixture.clock.plusSeconds(1)
                fixture.submit()
                assertTrue(fixture.commands.last() is PlanMutation.PreserveSchedule)
                assertEquals(before, fixture.store.durableFacts())
                fixture.reopen()
                assertEquals(original, fixture.store.plans.getPlan(original.id))
                assertEquals(focus, fixture.store.reads.getFocusedAction(original.id))
                assertEquals(before, fixture.store.durableFacts())
                assertEquals(original, fixture.row(original.id).plan)
            }
        }

    @Test
    fun explicitEditsPersistBothOccurrencesAndKeepFrozenIdentityAndSourceProvenance() =
        runBlocking {
            val changes =
                listOf(
                    Triple("2026-10-26", "10:00", null),
                    Triple("2026-10-25", "02:30", ZoneOffset.ofHours(2)),
                    Triple("2026-10-25", "02:30", ZoneOffset.ofHours(1)),
                )
            for ((date, time, offset) in changes) {
                Fixture(sequence).use { fixture ->
                    fixture.ready()
                    val original =
                        if (offset ==
                            ZoneOffset.ofHours(1)
                        ) {
                            fixture.seed("2026-10-25T02:30", 2)
                        } else {
                            fixture.seed("2026-10-24T12:00", null)
                        }
                    val snapshot =
                        fixture.store.reads
                            .getFocusedAction(original.id)
                            .snapshot
                    fixture.open(original)
                    fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:31"))
                    fixture.controller.dispatch(PlanAction.ChangeScheduleDate(date))
                    fixture.controller.dispatch(PlanAction.ChangeScheduleTime(time))
                    if (offset != null) fixture.choose(offset)
                    fixture.clock = fixture.clock.plusSeconds(1)
                    fixture.submit()
                    val command = fixture.commands.single() as PlanMutation.Reschedule
                    assertEquals(
                        PlanSchedule.ExactDay(LocalDateTime.parse(date + "T" + time), fixture.zone, offset),
                        command.schedule,
                    )
                    fixture.reopen()
                    val changed = requireNotNull(fixture.store.plans.getPlan(original.id))
                    assertEquals(
                        original.copy(target = command.schedule.toTarget(), updatedAt = fixture.clock),
                        changed,
                    )
                    assertEquals(
                        snapshot,
                        fixture.store.reads
                            .getFocusedAction(original.id)
                            .snapshot,
                    )
                    assertEquals(changed, fixture.row(changed.id).plan)
                    assertNotEquals(original.updatedAt, changed.updatedAt)
                }
            }
        }

    @Test
    fun editedRestoredTextLetsRepositoryKeepEquivalentInstantAsATransactionalNoOp() =
        runBlocking {
            Fixture(sequence).use { fixture ->
                fixture.ready()
                val original = fixture.seed("2026-10-25T02:30", 1)
                val before = fixture.store.durableFacts()
                fixture.store.forbidPlanUpdates()
                fixture.open(original)
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:31"))
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:30"))
                fixture.choose(ZoneOffset.ofHours(1))
                fixture.clock = fixture.clock.plusSeconds(1)
                fixture.submit()
                assertTrue(fixture.commands.single() is PlanMutation.Reschedule)
                fixture.reopen()
                assertEquals(original, fixture.store.plans.getPlan(original.id))
                assertEquals(before, fixture.store.durableFacts())
                assertEquals(
                    original.actionIdentity(),
                    fixture.store.reads
                        .getFocusedAction(original.id)
                        .identity,
                )
            }
        }

    @Test
    fun changedDraftZoneRequiresExplicitReviewBeforePersistingTheNewZone() =
        runBlocking {
            Fixture(sequence).use { fixture ->
                fixture.ready()
                val original = fixture.seed("2026-10-25T02:30", 1)
                fixture.open(original)
                fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:31"))
                fixture.choose(ZoneOffset.ofHours(1))
                val before = fixture.store.durableFacts()
                fixture.zone = ZoneId.of("Pacific/Auckland")
                fixture.submit()
                assertEquals(PlanMessage.ZONE_CHANGED, fixture.form.error)
                assertTrue(fixture.commands.isEmpty())
                assertEquals(before, fixture.store.durableFacts())
                fixture.controller.dispatch(PlanAction.RevalidateScheduleZone)
                fixture.clock = fixture.clock.plusSeconds(1)
                fixture.submit()
                fixture.reopen()
                val changed = requireNotNull(fixture.store.plans.getPlan(original.id))
                fixture.assertExact(changed, "2026-10-24T13:31:00Z")
                assertEquals(original.copy(target = changed.target, updatedAt = fixture.clock), changed)
            }
        }

    @Test
    fun preservationCannotBypassLifecycleEngagementOrStaleActionRecovery() =
        runBlocking {
            for (guard in listOf("RUNNING", "PAUSED", "FULFILLED", "CANCELLED", "STALE")) {
                Fixture(sequence).use { fixture ->
                    fixture.ready()
                    val original = fixture.seed("2026-10-25T02:30", 1)
                    fixture.open(original)
                    fixture.clock = fixture.clock.plusSeconds(1)
                    when (guard) {
                        "RUNNING", "PAUSED", "FULFILLED" -> {
                            if (sequence) {
                                fixture.store.live.startSequenceFromPlan(
                                    original.actionIdentity(),
                                    fixture.clock,
                                    fixture.clock,
                                    fixture.zone,
                                )
                                fixture.clock = fixture.clock.plusSeconds(1)
                                if (guard == "PAUSED") fixture.store.live.pauseActiveSequence(fixture.clock)
                                if (guard == "FULFILLED") fixture.store.live.endSequenceEarly(fixture.clock)
                            } else {
                                fixture.store.live.startActivityFromPlan(
                                    original.actionIdentity(),
                                    fixture.clock,
                                    fixture.clock,
                                    fixture.zone,
                                )
                                fixture.clock = fixture.clock.plusSeconds(1)
                                if (guard ==
                                    "PAUSED"
                                ) {
                                    fixture.store.live.pauseActiveActivity(
                                        ActivityExecutionPauseId("pause"),
                                        fixture.clock,
                                    )
                                }
                                if (guard == "FULFILLED") fixture.store.live.completeActiveActivity(fixture.clock)
                            }
                        }
                        "CANCELLED" -> fixture.store.plans.cancelPlan(original.actionIdentity(), fixture.clock)
                        "STALE" ->
                            fixture.store.plans.reschedulePlanEntry(
                                original.actionIdentity(),
                                PlanSchedule.FloatingDay(LocalDate.parse("2026-10-25")),
                                fixture.clock,
                            )
                    }
                    val before = fixture.store.durableFacts()
                    fixture.clock = fixture.clock.plusSeconds(1)
                    fixture.submit(PlanMessage.ACTION_UNAVAILABLE)
                    assertTrue(fixture.commands.single() is PlanMutation.PreserveSchedule)
                    assertEquals(PlanMessage.ACTION_UNAVAILABLE, fixture.controller.state.value.mutationFailure)
                    assertNull(fixture.controller.state.value.form)
                    assertEquals(before, fixture.store.durableFacts())
                    fixture.reopen()
                    assertEquals(before, fixture.store.durableFacts())
                }
            }
        }

    @Test
    fun rejectedReschedulingKeepsTheFormAndAllExistingFactsThenAllowsCorrection() =
        runBlocking {
            for ((date, offset, error) in listOf(
                Triple("2026-03-29", null, PlanMessage.NONEXISTENT_TIME),
                Triple("2026-10-25", null, PlanMessage.AMBIGUOUS_TIME),
                Triple("2026-10-25", ZoneOffset.ofHours(3), PlanMessage.INVALID_OFFSET),
            )) {
                Fixture(sequence).use { fixture ->
                    fixture.ready()
                    val original = fixture.seed("2026-10-24T12:00", null)
                    val frozen =
                        fixture.store.reads
                            .getFocusedAction(original.id)
                            .snapshot
                    val before = fixture.store.durableFacts()
                    fixture.open(original)
                    fixture.controller.dispatch(PlanAction.ChangeScheduleDate(date))
                    fixture.controller.dispatch(PlanAction.ChangeScheduleTime("02:30"))
                    if (offset != null) fixture.choose(offset)
                    fixture.submit()
                    assertEquals(error, fixture.form.error)
                    assertTrue(fixture.commands.isEmpty())
                    assertEquals(before, fixture.store.durableFacts())
                    fixture.controller.dispatch(PlanAction.ChangeScheduleDate("2026-10-26"))
                    fixture.controller.dispatch(PlanAction.ChangeScheduleTime("10:00"))
                    fixture.clock = fixture.clock.plusSeconds(1)
                    fixture.submit()
                    fixture.reopen()
                    val changed = requireNotNull(fixture.store.plans.getPlan(original.id))
                    fixture.assertExact(changed, "2026-10-26T09:00:00Z")
                    assertEquals(original.copy(target = changed.target, updatedAt = fixture.clock), changed)
                    assertEquals(
                        frozen,
                        fixture.store.reads
                            .getFocusedAction(original.id)
                            .snapshot,
                    )
                }
            }
        }

    private class Fixture(
        private val sequence: Boolean,
    ) : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "plan-form-${UUID.randomUUID()}.db"

        @Volatile var zone: ZoneId = ZoneId.of("Europe/Berlin")

        @Volatile var clock: Instant = Instant.parse("2026-10-24T10:00:00Z")
        var store = PlanSchedulingStore(context, name, CurrentZoneIdProvider { zone })
        val signal = MutableStateFlow(0L)
        val commands = mutableListOf<PlanMutation>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val activity =
            store.authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Activity",
                    "frozen",
                    TimeTrackingMode.STOPWATCH,
                    null,
                    fields =
                        listOf(
                            ActivityFieldDraft(
                                DraftIdentity.New("text"),
                                0,
                                "Notes",
                                CustomFieldType.TEXT,
                                defaultText = "fact",
                            ),
                        ),
                ),
                createdAt = clock,
            )
        private val sequenceTemplate =
            store.authoring.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Sequence",
                    "frozen",
                    fields =
                        listOf(
                            SequenceFieldDraft(
                                DraftIdentity.New("text"),
                                0,
                                "Notes",
                                CustomFieldType.TEXT,
                                defaultText = "fact",
                            ),
                        ),
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
                createdAt = clock,
            )
        private val source =
            if (sequence) {
                LibraryTemplateId.Sequence(
                    sequenceTemplate.id,
                )
            } else {
                LibraryTemplateId.Activity(activity.id)
            }
        val controller =
            PlanController(
                scope,
                { store.reads.getWeek(it) },
                { _, _, _ -> emptyList() },
                { store.reads.getCancelledPage(it) },
                {
                    commands += it
                    executePlanMutation(it, store.plans)
                },
                { clock },
                { zone },
                signal,
                LocalDateBoundaryScheduler { _, _, _, _ -> },
            )
        val form get() = requireNotNull(controller.state.value.form)

        suspend fun ready() = withTimeout(5_000) { controller.state.first { it.week is PlanLoad.Content } }

        fun newExact(date: String = "2026-10-25") {
            controller.dispatch(PlanAction.SelectCatalogItem(ReusablePlanCatalogItem(source, "Template", null, null)))
            controller.dispatch(PlanAction.ChangeScheduleKind(PlanScheduleKind.EXACT_DAY))
            controller.dispatch(PlanAction.ChangeScheduleDate(date))
            controller.dispatch(PlanAction.ChangeScheduleTime("02:30"))
        }

        fun choose(offset: ZoneOffset) =
            controller.dispatch(
                PlanAction.SelectScheduleOffset(
                    offset,
                    form.date,
                    form.time,
                    form.schedulingZoneId,
                    form.inputRevision,
                ),
            )

        suspend fun submit(expectedFailure: PlanMessage? = null) {
            controller.dispatch(PlanAction.SubmitForm)
            withTimeout(5_000) { controller.state.first { !it.isMutating } }
            assertEquals(expectedFailure, controller.state.value.mutationFailure)
        }

        fun seed(
            local: String,
            offset: Int?,
        ): PlanEntry {
            val schedule = PlanSchedule.ExactDay(LocalDateTime.parse(local), zone, offset?.let(ZoneOffset::ofHours))
            return if (sequence) {
                store.plans.createSequencePlanFromTemplate(sequenceTemplate.id, schedule, clock)
            } else {
                store.plans.createActivityPlanFromTemplate(activity.id, schedule, clock)
            }
        }

        fun row(id: PlanEntryId): com.alexandr5476.lifetracing.domain.PlanReadRow {
            val target = requireNotNull(store.plans.getPlan(id)).target
            val date =
                (target as? PlanTarget.ExactDay)?.scheduledAt?.atZone(zone)?.toLocalDate()
                    ?: LocalDate.parse("2026-10-25")
            val monday =
                date.with(
                    java.time.temporal.TemporalAdjusters
                        .previousOrSame(java.time.DayOfWeek.MONDAY),
                )
            return store.reads
                .getWeek(WeekPlanQuery(monday, date, clock))
                .selectedDayPlans
                .single { it.plan.id == id }
        }

        fun open(plan: PlanEntry) = controller.dispatch(PlanAction.Reschedule(row(plan.id)))

        fun onlyPlan(day: String = "2026-10-25"): PlanEntry {
            val date = LocalDate.parse(day)
            val monday =
                date.with(
                    java.time.temporal.TemporalAdjusters
                        .previousOrSame(java.time.DayOfWeek.MONDAY),
                )
            return store.reads
                .getWeek(WeekPlanQuery(monday, date, clock))
                .selectedDayPlans
                .single()
                .plan
        }

        fun assertExact(
            plan: PlanEntry,
            instant: String,
        ) {
            assertEquals(PlanTarget.ExactDay(Instant.parse(instant), zone), plan.target)
            assertEquals(plan.actionIdentity(), store.reads.getFocusedAction(plan.id).identity)
        }

        fun reopen() {
            controller.close()
            scope.cancel()
            store.close()
            store = PlanSchedulingStore(context, name, CurrentZoneIdProvider { zone })
        }

        override fun close() {
            controller.close()
            scope.cancel()
            store.close()
            context.deleteDatabase(name)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "sequence={0}")
        fun parameters() = listOf(arrayOf(false), arrayOf(true))
    }
}
