package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.LocalDateTimeResolutionException
import com.alexandr5476.lifetracing.domain.PlanEntry
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFactory
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import com.alexandr5476.lifetracing.domain.actionIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
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
class PlanExactDayCommandTest(
    private val sequence: Boolean,
) {
    private lateinit var database: LifeTracingDatabase
    private lateinit var plans: PlanRepository
    private lateinit var reads: PlanReadRepository
    private lateinit var databaseName: String
    private var zone = ZoneId.of("Europe/Berlin")
    private var planId = 0
    private var activitySnapshotId = 0
    private var sequenceSnapshotId = 0
    private var sequenceNodeId = 0

    @Before
    fun setUp() {
        databaseName = "plan-exact-day-${UUID.randomUUID()}.db"
        openDatabase()
        LiveRuntimeTestFixtures(database).seedSeries()
        seedTemplates()
    }

    @After
    fun tearDown() {
        database.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(databaseName)
    }

    @Test
    fun gapCreationHasNoDurableEffects() = assertRejectedCreation(schedule("2026-03-29T02:30"))

    @Test
    fun unresolvedOverlapCreationHasNoDurableEffects() = assertRejectedCreation(schedule("2026-10-25T02:30"))

    @Test
    fun invalidOverlapOffsetCreationHasNoDurableEffects() =
        assertRejectedCreation(schedule("2026-10-25T02:30", ZoneOffset.ofHours(3)))

    @Test
    fun obsoleteOffsetCreationHasNoDurableEffects() {
        val previewed = schedule("2026-10-25T02:30", ZoneOffset.ofHours(2))
        assertEquals(
            PlanTarget.ExactDay(Instant.parse("2026-10-25T00:30:00Z"), BERLIN),
            previewed.toTarget(),
        )
        assertRejectedCreation(previewed.copy(localDateTime = LocalDateTime.parse("2026-10-25T03:30")))
    }

    @Test
    fun earlierOverlapCreationRoundTrips() =
        assertCreation(schedule("2026-10-25T02:30", ZoneOffset.ofHours(2)), "2026-10-25T00:30:00Z")

    @Test
    fun laterOverlapCreationRoundTrips() =
        assertCreation(schedule("2026-10-25T02:30", ZoneOffset.ofHours(1)), "2026-10-25T01:30:00Z")

    @Test
    fun ordinaryCreationRoundTrips() = assertCreation(schedule("2026-10-25T03:30"), "2026-10-25T02:30:00Z")

    @Test
    fun preservationAndEquivalentInputNeverWriteOrLosePrecision() = assertExactNoOp(legacyZone = false)

    @Test
    fun legacyNullCreationZoneIsRetainedByBothNoOpCommands() = assertExactNoOp(legacyZone = true)

    @Test
    fun ordinaryChangedRescheduleRoundTrips() =
        assertChanged(schedule("2026-10-25T03:30"), "2026-10-25T02:30:00Z", BERLIN)

    @Test
    fun changedRescheduleSelectsEarlierOverlap() =
        assertChanged(schedule("2026-10-25T02:30", ZoneOffset.ofHours(2)), "2026-10-25T00:30:00Z", BERLIN)

    @Test
    fun changedRescheduleSelectsLaterOverlap() =
        assertChanged(schedule("2026-10-25T02:30", ZoneOffset.ofHours(1)), "2026-10-25T01:30:00Z", BERLIN)

    @Test
    fun changedRescheduleCanSwitchOverlapOccurrences() {
        val original = create(schedule("2026-10-25T02:30", ZoneOffset.ofHours(2)))
        assertChangedFrom(
            original,
            schedule("2026-10-25T02:30", ZoneOffset.ofHours(1)),
            "2026-10-25T01:30:00Z",
            BERLIN,
        )
    }

    @Test
    fun changedApplicableZoneIsPersistedOnlyWhenInstantChanges() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertChanged(
            PlanSchedule.ExactDay(LocalDateTime.parse("2026-10-25T03:30"), tokyo),
            "2026-10-24T18:30:00Z",
            tokyo,
        )
    }

    @Test
    fun floatingAndWeekCompatibilityIncludesLiteralNoOps() {
        val original = create(PlanSchedule.FloatingDay(LocalDate.parse("2026-10-25")))
        assertLiteralNoOp(original, PlanSchedule.FloatingDay(LocalDate.parse("2026-10-25")))
        val week = PlanSchedule.Week(LocalDate.parse("2026-10-26"))
        val changed = plans.reschedulePlanEntry(original.actionIdentity(), week, instant(2))
        assertEquals(original.copy(target = week.toTarget(), updatedAt = instant(2)), changed)
        assertLiteralNoOp(changed, week)
        val next = PlanSchedule.FloatingDay(LocalDate.parse("2026-10-27"))
        val floating = plans.reschedulePlanEntry(changed.actionIdentity(), next, instant(3))
        reopenDatabase()
        assertEquals(original.copy(target = next.toTarget(), updatedAt = instant(3)), plans.getPlan(original.id))
        assertEquals(floating.actionIdentity(), reads.getFocusedAction(original.id).identity)
    }

    @Test
    fun rejectedRescheduleLeavesAllFactsUnchanged() {
        val original = create(secondOccurrence())
        val before = durableFacts()
        val focused = reads.getFocusedAction(original.id)
        listOf(
            schedule("2026-03-29T02:30"),
            schedule("2026-10-25T02:30"),
            schedule("2026-10-25T02:30", ZoneOffset.ofHours(3)),
            schedule("2026-10-25T03:30", ZoneOffset.ofHours(2)),
        ).forEach { invalid ->
            assertThrows(LocalDateTimeResolutionException::class.java) {
                plans.reschedulePlanEntry(focused.identity, invalid, instant(2))
            }
            assertEquals(before, durableFacts())
        }
        reopenDatabase()
        assertEquals(before, durableFacts())
        assertEquals(original, plans.getPlan(original.id))
        assertEquals(focused, reads.getFocusedAction(original.id))
    }

    @Test
    fun changedRescheduleDaoFailureRetainsTheOriginalDurableFacts() {
        val original = create(secondOccurrence())
        val focused = reads.getFocusedAction(original.id)
        val before = durableFacts()
        forbidPlanUpdates()
        assertThrows(Exception::class.java) {
            plans.reschedulePlanEntry(focused.identity, schedule("2026-10-25T03:30"), instant(2))
        }
        reopenDatabase()
        assertEquals(original, plans.getPlan(original.id))
        assertEquals(focused, reads.getFocusedAction(original.id))
        assertEquals(before, durableFacts())
    }

    @Test
    fun noOpsRejectRunningEngagement() = assertGuard("RUNNING")

    @Test
    fun noOpsRejectPausedEngagement() = assertGuard("PAUSED")

    @Test
    fun noOpsRejectFulfilledState() = assertGuard("FULFILLED")

    @Test
    fun noOpsRejectCancelledState() = assertGuard("CANCELLED")

    @Test
    fun noOpsRejectStaleActionIdentity() = assertGuard("STALE")

    @Test
    fun noOpsRejectOutOfOrderCommandTime() = assertGuard("ORDER")

    @Test
    fun preservationAndExplicitRescheduleRejectRetainedMonth() = assertGuard("MONTH")

    private fun assertRejectedCreation(proposal: PlanSchedule) {
        val existing = create(schedule("2026-10-25T03:30"))
        val focused = reads.getFocusedAction(existing.id)
        val before = durableFacts()
        assertThrows(LocalDateTimeResolutionException::class.java) { create(proposal) }
        assertEquals(before, durableFacts())
        reopenDatabase()
        assertEquals(before, durableFacts())
        assertEquals(existing, plans.getPlan(existing.id))
        assertEquals(focused, reads.getFocusedAction(existing.id))
    }

    private fun assertCreation(
        proposal: PlanSchedule.ExactDay,
        expectedInstant: String,
    ) {
        val activitySource = database.activityTemplateDao().getAggregate(ACTIVITY_TEMPLATE)
        val sequenceSource = database.sequenceTemplateDao().getAggregate(SEQUENCE_TEMPLATE)
        val created = create(proposal)
        val expected = PlanTarget.ExactDay(Instant.parse(expectedInstant), proposal.currentZoneId)
        assertEquals(expected, created.target)
        val focused = reads.getFocusedAction(created.id)
        val before = durableFacts()
        reopenDatabase()
        assertEquals(created, plans.getPlan(created.id))
        assertEquals(expected, reads.getFocusedAction(created.id).identity.target)
        assertEquals(focused, reads.getFocusedAction(created.id))
        assertEquals(before, durableFacts())
        assertEquals(activitySource, database.activityTemplateDao().getAggregate(ACTIVITY_TEMPLATE))
        assertEquals(sequenceSource, database.sequenceTemplateDao().getAggregate(SEQUENCE_TEMPLATE))
    }

    private fun assertExactNoOp(legacyZone: Boolean) {
        val created = create(secondOccurrence())
        if (legacyZone) {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE plan_entries SET creation_zone_id = NULL WHERE id = '${created.id.value}'",
            )
        }
        val original = requireNotNull(plans.getPlan(created.id))
        val focused = reads.getFocusedAction(created.id)
        val row = database.planEntryDao().getById(created.id.value)
        val before = durableFacts()
        forbidPlanUpdates()
        assertEquals(original, plans.preservePlanSchedule(focused.identity, instant(2)))
        zone = ZoneId.of("Pacific/Auckland")
        assertEquals(original, plans.preservePlanSchedule(focused.identity, instant(3)))
        val equivalent = PlanSchedule.ExactDay(LocalDateTime.parse("2026-10-25T14:30:27.123"), zone)
        assertEquals(original, plans.reschedulePlanEntry(focused.identity, equivalent, instant(4)))
        assertEquals(before, durableFacts())
        reopenDatabase()
        assertEquals(original, plans.getPlan(created.id))
        assertEquals(row, database.planEntryDao().getById(created.id.value))
        assertEquals(focused, reads.getFocusedAction(created.id))
        assertEquals(Instant.parse("2026-10-25T01:30:27.123Z"), (original.target as PlanTarget.ExactDay).scheduledAt)
        assertEquals(before, durableFacts())
        assertEquals(original, plans.preservePlanSchedule(focused.identity, instant(5)))
        assertEquals(original, plans.reschedulePlanEntry(focused.identity, equivalent, instant(6)))
        assertEquals(before, durableFacts())
    }

    private fun assertLiteralNoOp(
        original: PlanEntry,
        proposal: PlanSchedule,
    ) {
        val before = durableFacts()
        forbidPlanUpdates()
        assertEquals(original, plans.reschedulePlanEntry(original.actionIdentity(), proposal, instant(4)))
        assertEquals(original, plans.preservePlanSchedule(original.actionIdentity(), instant(4)))
        assertEquals(before, durableFacts())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER forbid_plan_updates")
    }

    private fun assertChanged(
        proposal: PlanSchedule.ExactDay,
        expectedInstant: String,
        expectedZone: ZoneId,
    ) = assertChangedFrom(create(schedule("2026-10-24T12:00")), proposal, expectedInstant, expectedZone)

    private fun assertChangedFrom(
        original: PlanEntry,
        proposal: PlanSchedule.ExactDay,
        expectedInstant: String,
        expectedZone: ZoneId,
    ) {
        val before = durableFacts().minus("plan_entries")
        val focused = reads.getFocusedAction(original.id)
        val expected =
            original.copy(
                target = PlanTarget.ExactDay(Instant.parse(expectedInstant), expectedZone),
                updatedAt = instant(2),
            )
        val changed = plans.reschedulePlanEntry(focused.identity, proposal, instant(2))
        assertEquals(expected, changed)
        reopenDatabase()
        assertEquals(expected, plans.getPlan(original.id))
        assertEquals(expected.actionIdentity(), reads.getFocusedAction(original.id).identity)
        assertEquals(focused.snapshot, reads.getFocusedAction(original.id).snapshot)
        assertEquals(before, durableFacts().minus("plan_entries"))
        val day = Instant.parse(expectedInstant).atZone(zone).toLocalDate()
        val weekStart =
            day.with(
                java.time.temporal.TemporalAdjusters
                    .previousOrSame(java.time.DayOfWeek.MONDAY),
            )
        val rows =
            reads
                .getWeek(WeekPlanQuery(weekStart, day, instant(3)))
                .selectedDayPlans
        assertEquals(expected, rows.single { it.plan.id == original.id }.plan)
    }

    private fun assertGuard(state: String) {
        var original = create(secondOccurrence())
        var expected = original.actionIdentity()
        var at = instant(5)
        val live = liveRepository()
        when (state) {
            "RUNNING", "PAUSED", "FULFILLED" -> {
                if (sequence) {
                    live.startSequenceFromPlan(original.id, instant(2), instant(2), zone)
                    if (state == "PAUSED") live.pauseActiveSequence(instant(3))
                    if (state == "FULFILLED") live.endSequenceEarly(instant(3))
                } else {
                    live.startActivityFromPlan(original.id, instant(2), instant(2), zone)
                    if (state == "PAUSED") live.pauseActiveActivity(ActivityExecutionPauseId("guard-pause"), instant(3))
                    if (state == "FULFILLED") live.completeActiveActivity(instant(3))
                }
            }
            "CANCELLED" -> plans.cancelPlan(expected, instant(3))
            "STALE" -> expected = expected.copy(sourceRevision = 99)
            "ORDER" -> at = instant(0)
            "MONTH" ->
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE plan_entries SET precision = 'MONTH', scheduled_instant_ms = NULL, " +
                        "creation_zone_id = NULL, planned_month = '2026-10' WHERE id = '${original.id.value}'",
                )
            else -> error("Unknown guard fixture")
        }
        original = requireNotNull(plans.getPlan(original.id))
        if (state != "STALE") expected = original.actionIdentity()
        val before = durableFacts()
        assertThrows(IllegalArgumentException::class.java) { plans.preservePlanSchedule(expected, at) }
        assertThrows(IllegalArgumentException::class.java) {
            plans.reschedulePlanEntry(expected, secondOccurrence(), at)
        }
        assertEquals(before, durableFacts())
        reopenDatabase()
        assertEquals(original, plans.getPlan(original.id))
        assertEquals(before, durableFacts())
        if (state != "MONTH") assertEquals(original.actionIdentity(), reads.getFocusedAction(original.id).identity)
    }

    private fun create(schedule: PlanSchedule): PlanEntry =
        if (sequence) {
            plans.createSequencePlanFromTemplate(SequenceTemplateId(SEQUENCE_TEMPLATE), schedule, instant(1))
        } else {
            plans.createActivityPlanFromTemplate(ActivityTemplateId(ACTIVITY_TEMPLATE), schedule, instant(1))
        }

    private fun schedule(
        text: String,
        offset: ZoneOffset? = null,
    ) = PlanSchedule.ExactDay(LocalDateTime.parse(text), BERLIN, offset)

    private fun secondOccurrence() = schedule("2026-10-25T02:30:27.123", ZoneOffset.ofHours(1))

    private fun forbidPlanUpdates() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER forbid_plan_updates BEFORE UPDATE ON plan_entries " +
                "BEGIN SELECT RAISE(ABORT, 'No-op must not execute a Plan UPDATE'); END",
        )
    }

    /** Complete durable fixture facts, including every snapshot-owned metadata and Execution/linkage row. */
    private fun durableFacts(): Map<String, List<List<String?>>> {
        val tables =
            database.openHelper.readableDatabase
                .query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata' ORDER BY name",
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
        return tables.associateWith { table ->
            database.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                    }
                }
            }
        }
    }

    private fun openDatabase() {
        database =
            LifeTracingDatabase
                .builder(ApplicationProvider.getApplicationContext<Context>(), databaseName)
                .allowMainThreadQueries()
                .build()
        plans = repository()
        reads = PlanReadRepository(database, CurrentZoneIdProvider { zone })
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun liveRepository(): LiveSessionRepository {
        var activity = 0
        var sequence = 0
        var occurrence = 0
        var interval = 0
        return LiveSessionRepository(
            database,
            { ActivityExecutionId("activity-${++activity}") },
            { ActivityExecutionPauseId("pause-$activity") },
            { SequenceExecutionId("sequence-${++sequence}") },
            { SequenceOccurrenceId("occurrence-${++occurrence}") },
            { SequenceIntervalId("interval-${++interval}") },
        )
    }

    private fun repository(
        nextPlanId: () -> PlanEntryId = { PlanEntryId("plan-${++planId}") },
        nextActivitySnapshotId: () -> ActivitySnapshotId = {
            ActivitySnapshotId(
                "activity-plan-${++activitySnapshotId}",
            )
        },
        nextSequenceSnapshotId: () -> SequenceSnapshotId = {
            SequenceSnapshotId(
                "sequence-plan-${++sequenceSnapshotId}",
            )
        },
    ) = PlanRepository(
        database,
        nextPlanId,
        ActivitySnapshotFactory(
            nextActivitySnapshotId,
            { ActivitySnapshotFieldId("activity-field-$activitySnapshotId") },
            { ActivitySnapshotCategoryOptionId("activity-option-$activitySnapshotId") },
        ),
        SequenceSnapshotFactory(
            nextSequenceSnapshotId,
            { SequenceSnapshotFieldId("sequence-field-$sequenceSnapshotId") },
            { SequenceSnapshotCategoryOptionId("sequence-option-$sequenceSnapshotId") },
            { SequenceSnapshotNodeId("sequence-node-${++sequenceNodeId}") },
        ),
        CurrentZoneIdProvider { zone },
    )

    private fun seedTemplates() {
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    ACTIVITY_TEMPLATE,
                    "Activity",
                    null,
                    "STOPWATCH",
                    null,
                    "activity-series",
                    1,
                    0,
                    0,
                    null,
                    null,
                ),
                ActivityTemplateSettingsEntity(ACTIVITY_TEMPLATE),
                fields =
                    listOf(
                        ActivityTemplateFieldEntity(
                            id = "activity-category",
                            activityTemplateId = ACTIVITY_TEMPLATE,
                            position = 0,
                            name = "Category",
                            fieldType = "CATEGORY",
                            unit = null,
                            displayPrecision = null,
                            defaultNumberScaled = null,
                            defaultCategoryOptionId = null,
                            defaultText = null,
                            createdAtMs = 0,
                            updatedAtMs = 0,
                            deletedAtMs = null,
                        ),
                    ),
                options =
                    listOf(ActivityTemplateCategoryOptionEntity("activity-option", "activity-category", 0, "Option")),
                userState = ActivityTemplateUserStateEntity(ACTIVITY_TEMPLATE, null, 100),
            ),
        )
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    "sequence-step-snapshot",
                    "Sequence step",
                    null,
                    "STOPWATCH",
                    null,
                    ACTIVITY_TEMPLATE,
                    1,
                    "activity-series",
                    false,
                    0,
                ),
                ActivitySnapshotSettingsEntity("sequence-step-snapshot"),
            ),
        )
        database.sequenceTemplateDao().insertAggregate(
            SequenceTemplateAggregateEntity(
                SequenceTemplateEntity(
                    SEQUENCE_TEMPLATE,
                    "Sequence",
                    null,
                    "sequence-series",
                    1,
                    0,
                    0,
                    null,
                    null,
                    "ACTIVE",
                ),
                SequenceTemplateSettingsEntity(SEQUENCE_TEMPLATE),
                SequenceTemplateUserStateEntity(SEQUENCE_TEMPLATE, null, 100),
                fields =
                    listOf(
                        SequenceTemplateFieldEntity(
                            id = "sequence-category",
                            sequenceTemplateId = SEQUENCE_TEMPLATE,
                            position = 0,
                            name = "Category",
                            fieldType = "CATEGORY",
                            unit = null,
                            displayPrecision = null,
                            defaultNumberScaled = null,
                            defaultCategoryOptionId = null,
                            defaultText = null,
                            createdAtMs = 0,
                            updatedAtMs = 0,
                            deletedAtMs = null,
                        ),
                    ),
                options =
                    listOf(SequenceTemplateCategoryOptionEntity("sequence-option", "sequence-category", 0, "Option")),
                nodes =
                    listOf(
                        SequenceNodeEntity(
                            "sequence-step",
                            SEQUENCE_TEMPLATE,
                            "STEP",
                            null,
                            0,
                            "sequence-step-snapshot",
                            null,
                        ),
                    ),
            ),
        )
    }

    private fun instant(seconds: Long) = Instant.ofEpochSecond(seconds)

    companion object {
        private const val ACTIVITY_TEMPLATE = "activity-template"
        private const val SEQUENCE_TEMPLATE = "sequence-template"
        private val BERLIN = ZoneId.of("Europe/Berlin")

        @JvmStatic
        @Parameterized.Parameters(name = "sequence={0}")
        fun kinds(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))
    }
}
