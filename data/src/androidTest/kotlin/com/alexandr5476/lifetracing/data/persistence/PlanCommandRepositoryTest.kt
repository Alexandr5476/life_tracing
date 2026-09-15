package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CancelledPlanPageQuery
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSchedule
import com.alexandr5476.lifetracing.domain.PlanSourceState
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class PlanCommandRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var plans: PlanRepository
    private lateinit var reads: PlanReadRepository
    private var zone = ZoneId.of("UTC")
    private var planId = 0
    private var activitySnapshotId = 0
    private var sequenceSnapshotId = 0

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        LiveRuntimeTestFixtures(database).seedSeries()
        seedTemplates()
        plans = repository()
        reads = PlanReadRepository(database, CurrentZoneIdProvider { zone })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun supportedCreationFreezesSourcesAndReloadsThroughCanonicalReadsWithoutRecentMutation() {
        val activityTemplateBefore = database.activityTemplateDao().getById(ACTIVITY_TEMPLATE)!!
        val sequenceTemplateBefore = database.sequenceTemplateDao().getById(SEQUENCE_TEMPLATE)!!
        val activityRecentBefore = database.activityTemplateDao().getUserState(ACTIVITY_TEMPLATE)
        val sequenceRecentBefore = database.sequenceTemplateDao().getUserState(SEQUENCE_TEMPLATE)

        val activity =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId(ACTIVITY_TEMPLATE),
                PlanSchedule.FloatingDay(LocalDate.parse("2026-08-20")),
                instant(1),
            )
        val sequence =
            plans.createSequencePlanFromTemplate(
                SequenceTemplateId(SEQUENCE_TEMPLATE),
                PlanSchedule.Week(LocalDate.parse("2026-08-17")),
                instant(2),
            )

        val focusedActivity = reads.getFocusedAction(activity.id)
        val week =
            reads.getWeek(
                WeekPlanQuery(
                    LocalDate.parse("2026-08-17"),
                    LocalDate.parse("2026-08-20"),
                    instant(3),
                ),
            )
        val activitySnapshot = database.activitySnapshotDao().getById(activity.activitySnapshotId!!.value)!!
        val sequenceSnapshot = database.sequenceSnapshotDao().getById(sequence.sequenceSnapshotId!!.value)!!

        assertEquals(activity.actionIdentity(), focusedActivity.identity)
        assertEquals(PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")), focusedActivity.identity.target)
        assertEquals(PlanEntryStatus.PLANNED, focusedActivity.identity.status)
        assertEquals(ACTIVITY_TEMPLATE, activitySnapshot.sourceTemplateId)
        assertEquals(1L, activitySnapshot.sourceRevision)
        assertEquals("activity-series", activitySnapshot.statisticsSeriesId)
        assertEquals(listOf(sequence.id), week.weekPlans.map { it.plan.id })
        assertEquals(SEQUENCE_TEMPLATE, sequenceSnapshot.sourceTemplateId)
        assertEquals(1L, sequenceSnapshot.sourceRevision)
        assertEquals("sequence-series", sequenceSnapshot.statisticsSeriesId)
        assertEquals(activityTemplateBefore, database.activityTemplateDao().getById(ACTIVITY_TEMPLATE))
        assertEquals(sequenceTemplateBefore, database.sequenceTemplateDao().getById(SEQUENCE_TEMPLATE))
        assertEquals(activityRecentBefore, database.activityTemplateDao().getUserState(ACTIVITY_TEMPLATE))
        assertEquals(sequenceRecentBefore, database.sequenceTemplateDao().getUserState(SEQUENCE_TEMPLATE))
        assertEquals(2, count("plan_entries"))
    }

    @Test
    fun exactDayResolvesOnceInSuppliedZoneAndLaterZoneReadsDoNotMutateIt() {
        val creationZone = ZoneId.of("Asia/Kolkata")
        val local = LocalDateTime.parse("2026-08-20T15:30:00")
        val created =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId(ACTIVITY_TEMPLATE),
                PlanSchedule.ExactDay(local, creationZone),
                instant(1),
            )
        val expected = PlanTarget.ExactDay(Instant.parse("2026-08-20T10:00:00Z"), creationZone)

        assertEquals(expected, reads.getFocusedAction(created.id).identity.target)
        zone = ZoneId.of("America/Los_Angeles")
        assertEquals(expected, reads.getFocusedAction(created.id).identity.target)
        assertEquals(expected, plans.getPlan(created.id)!!.target)
    }

    @Test
    fun archivedCreationAndPostSnapshotPlanCollisionRollBackWithoutOrphans() {
        database.activityTemplateDao().archive(ACTIVITY_TEMPLATE, 1)
        val beforePlans = count("plan_entries")
        val beforeSnapshots = count("activity_snapshots")
        assertThrows(IllegalArgumentException::class.java) {
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId(ACTIVITY_TEMPLATE),
                PlanSchedule.FloatingDay(LocalDate.parse("2026-08-20")),
                instant(2),
            )
        }
        assertEquals(beforePlans, count("plan_entries"))
        assertEquals(beforeSnapshots, count("activity_snapshots"))

        database.activityTemplateDao().restore(ACTIVITY_TEMPLATE)
        val colliding = repository(nextPlanId = { PlanEntryId("collision") })
        colliding.createActivityPlanFromTemplate(
            ActivityTemplateId(ACTIVITY_TEMPLATE),
            PlanSchedule.FloatingDay(LocalDate.parse("2026-08-20")),
            instant(4),
        )
        val afterFirst = count("activity_snapshots")
        assertThrows(Exception::class.java) {
            colliding.createActivityPlanFromTemplate(
                ActivityTemplateId(ACTIVITY_TEMPLATE),
                PlanSchedule.FloatingDay(LocalDate.parse("2026-08-21")),
                instant(5),
            )
        }
        assertEquals(afterFirst, count("activity_snapshots"))
        assertEquals(1, count("plan_entries", "id = 'collision'"))
    }

    @Test
    fun rescheduleCancelAndRestoreUseCanonicalIdentityAndSupportedTargets() {
        val created =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId(ACTIVITY_TEMPLATE),
                PlanSchedule.FloatingDay(LocalDate.parse("2026-08-10")),
                instant(1),
            )
        val original = reads.getFocusedAction(created.id).identity
        assertTrue(plans.isOverdue(created, Instant.parse("2026-08-20T00:00:00Z")))

        val rescheduled =
            plans.reschedulePlanEntry(
                original,
                PlanSchedule.Week(LocalDate.parse("2026-08-24")),
                instant(2),
            )
        assertEquals(created.id, rescheduled.id)
        assertEquals(created.activitySnapshotId, rescheduled.activitySnapshotId)
        assertEquals(created.sourceRevision, rescheduled.sourceRevision)
        assertEquals(PlanTarget.Week(LocalDate.parse("2026-08-24")), rescheduled.target)
        assertEquals(created.copy(target = rescheduled.target, updatedAt = rescheduled.updatedAt), rescheduled)
        assertThrows(IllegalArgumentException::class.java) {
            plans.cancelPlan(original, instant(3))
        }

        val current = reads.getFocusedAction(created.id).identity
        val cancelled = plans.cancelPlan(current, instant(3))
        assertEquals(PlanEntryStatus.CANCELLED, cancelled.status)
        assertEquals(rescheduled.target, cancelled.target)
        assertTrue(
            reads
                .getWeek(
                    WeekPlanQuery(
                        LocalDate.parse("2026-08-24"),
                        LocalDate.parse("2026-08-24"),
                        instant(4),
                    ),
                ).weekPlans
                .isEmpty(),
        )
        assertEquals(listOf(created.id), reads.getCancelledPage(CancelledPlanPageQuery(0, 10)).items.map { it.plan.id })
        assertThrows(IllegalArgumentException::class.java) { plans.cancelPlan(current, instant(4)) }

        val cancelledIdentity = reads.getFocusedAction(created.id).identity
        val restored = plans.restoreCancelledPlan(cancelledIdentity, instant(5))
        assertEquals(PlanEntryStatus.PLANNED, restored.status)
        assertEquals(cancelled.target, restored.target)
        assertEquals(cancelled.activitySnapshotId, restored.activitySnapshotId)
        assertTrue(plans.isOverdue(restored, Instant.parse("2026-09-01T00:00:00Z")))
        assertThrows(IllegalArgumentException::class.java) {
            plans.restoreCancelledPlan(cancelledIdentity, instant(6))
        }
        assertThrows(IllegalArgumentException::class.java) {
            plans.restoreCancelledPlan(reads.getFocusedAction(created.id).identity, instant(6))
        }
        assertNull(database.activityTemplateDao().getUserState(ACTIVITY_TEMPLATE)!!.lastUsedAtMs)
    }

    @Test
    fun engagedFulfilledAndCancelledPlansRejectMutableCommandsWithoutChangingPlanFacts() {
        val engaged = createActivityPlan()
        val engagedIdentity = reads.getFocusedAction(engaged.id).identity
        val live = liveRepository()
        live.startActivityFromPlan(engaged.id, instant(2), instant(2), ZoneId.of("UTC"))

        assertThrows(IllegalArgumentException::class.java) { plans.cancelPlan(engagedIdentity, instant(3)) }
        assertThrows(IllegalArgumentException::class.java) {
            plans.reschedulePlanEntry(
                engagedIdentity,
                PlanSchedule.Week(LocalDate.parse("2026-08-24")),
                instant(3),
            )
        }
        assertEquals(engagedIdentity, reads.getFocusedAction(engaged.id).identity)

        live.completeActiveActivity(instant(4))
        val fulfilled = reads.getFocusedAction(engaged.id).identity
        assertThrows(IllegalArgumentException::class.java) { plans.cancelPlan(fulfilled, instant(5)) }
        assertThrows(IllegalArgumentException::class.java) {
            plans.reschedulePlanEntry(
                fulfilled,
                PlanSchedule.FloatingDay(LocalDate.parse("2026-08-21")),
                instant(5),
            )
        }
        assertEquals(fulfilled, reads.getFocusedAction(engaged.id).identity)

        val cancelledPlan = createActivityPlan()
        val cancelled = plans.cancelPlan(reads.getFocusedAction(cancelledPlan.id).identity, instant(5))
        val cancelledIdentity = reads.getFocusedAction(cancelled.id).identity
        assertThrows(IllegalArgumentException::class.java) { plans.cancelPlan(cancelledIdentity, instant(6)) }
        assertThrows(IllegalArgumentException::class.java) {
            plans.reschedulePlanEntry(
                cancelledIdentity,
                PlanSchedule.FloatingDay(LocalDate.parse("2026-08-21")),
                instant(6),
            )
        }
        assertEquals(cancelledIdentity, reads.getFocusedAction(cancelled.id).identity)
    }

    @Test
    fun eachExistingPlanCommandRejectsAStaleDurableFactWithoutChangingTheNewState() {
        val commands = listOf("reschedule", "cancel", "restore", "update")
        val identities =
            commands
                .associateWith { name ->
                    val plan =
                        plans.createActivityPlanFromTemplate(
                            ActivityTemplateId(ACTIVITY_TEMPLATE),
                            PlanSchedule.FloatingDay(LocalDate.parse("2026-08-20")),
                            instant(1),
                        )
                    name to reads.getFocusedAction(plan.id).identity
                }.mapValues { it.value.second }
        val ids = identities.mapValues { it.value.planEntryId.value }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE plan_entries SET planned_day = '2026-08-21' WHERE id IN " +
                "('${ids.getValue("reschedule")}', '${ids.getValue("cancel")}', '${ids.getValue("update")}')",
        )
        plans.cancelPlan(identities.getValue("restore"), instant(2))
        val cancelled = reads.getFocusedAction(identities.getValue("restore").planEntryId).identity
        database.openHelper.writableDatabase.execSQL(
            "UPDATE plan_entries SET updated_at_ms = 3000 WHERE id = '${ids.getValue("restore")}'",
        )

        assertThrows(IllegalArgumentException::class.java) {
            plans.reschedulePlanEntry(
                identities.getValue("reschedule"),
                PlanSchedule.Week(LocalDate.parse("2026-08-24")),
                instant(4),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            plans.cancelPlan(identities.getValue("cancel"), instant(4))
        }
        assertThrows(IllegalArgumentException::class.java) {
            plans.restoreCancelledPlan(cancelled, instant(4))
        }
        assertThrows(IllegalArgumentException::class.java) {
            plans.updatePlanFromTemplate(identities.getValue("update"), instant(4))
        }
        assertEquals("2026-08-21", database.planEntryDao().getById(ids.getValue("reschedule"))!!.plannedDay)
        assertEquals("PLANNED", database.planEntryDao().getById(ids.getValue("cancel"))!!.status)
        assertEquals(3_000, database.planEntryDao().getById(ids.getValue("restore"))!!.updatedAtMs)
        assertEquals(1L, database.planEntryDao().getById(ids.getValue("update"))!!.sourceRevision)
    }

    @Test
    fun productionRestoreRejectsDurableMonthWithoutNarrowingLowLevelRoundTrip() {
        val month =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId(ACTIVITY_TEMPLATE),
                PlanTarget.Month(YearMonth.parse("2026-08")),
                instant(1),
            )
        val cancelled = plans.cancelPlan(month.id, instant(2))
        assertEquals(PlanTarget.Month(YearMonth.parse("2026-08")), plans.getPlan(month.id)!!.target)

        assertThrows(IllegalArgumentException::class.java) {
            plans.restoreCancelledPlan(cancelled.actionIdentity(), instant(3))
        }
        assertEquals(cancelled, plans.getPlan(month.id))
    }

    @Test
    fun activityAndSequenceUpdateReplaceFrozenSnapshotsAndPruneOnlyUnreferencedOldOnes() {
        val activity =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId(ACTIVITY_TEMPLATE),
                PlanSchedule.FloatingDay(LocalDate.parse("2026-08-20")),
                instant(1),
            )
        val sequence =
            plans.createSequencePlanFromTemplate(
                SequenceTemplateId(SEQUENCE_TEMPLATE),
                PlanSchedule.Week(LocalDate.parse("2026-08-17")),
                instant(1),
            )
        val retainedActivitySnapshot = activity.activitySnapshotId!!.value
        database.planEntryDao().insert(
            database.planEntryDao().getById(activity.id.value)!!.copy(id = "retaining-plan"),
        )
        database.planEntryDao().insert(
            database.planEntryDao().getById(sequence.id.value)!!.copy(id = "retaining-sequence-plan"),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_templates SET name = 'Activity v2', revision = 2, updated_at_ms = 2 " +
                "WHERE id = '$ACTIVITY_TEMPLATE'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_templates SET name = 'Sequence v2', revision = 2, updated_at_ms = 2 " +
                "WHERE id = '$SEQUENCE_TEMPLATE'",
        )
        val activitySourceBeforeCommand = database.activityTemplateDao().getById(ACTIVITY_TEMPLATE)
        val sequenceSourceBeforeCommand = database.sequenceTemplateDao().getById(SEQUENCE_TEMPLATE)
        assertEquals(PlanSourceState.CHANGED, reads.getFocusedAction(activity.id).sourceState)
        assertEquals(PlanSourceState.CHANGED, reads.getFocusedAction(sequence.id).sourceState)

        val updatedActivity = plans.updatePlanFromTemplate(reads.getFocusedAction(activity.id).identity, instant(3))
        val oldSequenceSnapshot = sequence.sequenceSnapshotId!!.value
        val updatedSequence = plans.updatePlanFromTemplate(reads.getFocusedAction(sequence.id).identity, instant(3))

        assertNotEquals(activity.activitySnapshotId, updatedActivity.activitySnapshotId)
        assertNotEquals(sequence.sequenceSnapshotId, updatedSequence.sequenceSnapshotId)
        assertEquals(activity.id, updatedActivity.id)
        assertEquals(activity.target, updatedActivity.target)
        assertEquals(2L, updatedActivity.sourceRevision)
        assertEquals(2L, updatedSequence.sourceRevision)
        assertEquals(
            "Activity v2",
            database.activitySnapshotDao().getById(updatedActivity.activitySnapshotId!!.value)!!.name,
        )
        assertEquals(
            "Sequence v2",
            database.sequenceSnapshotDao().getById(updatedSequence.sequenceSnapshotId!!.value)!!.name,
        )
        assertEquals(PlanSourceState.CURRENT, reads.getFocusedAction(activity.id).sourceState)
        assertEquals(PlanSourceState.CURRENT, reads.getFocusedAction(sequence.id).sourceState)
        assertTrue(database.activitySnapshotDao().getById(retainedActivitySnapshot) != null)
        assertTrue(database.sequenceSnapshotDao().getById(oldSequenceSnapshot) != null)

        plans.updatePlanFromTemplate(reads.getFocusedAction(PlanEntryId("retaining-plan")).identity, instant(4))
        plans.updatePlanFromTemplate(
            reads.getFocusedAction(PlanEntryId("retaining-sequence-plan")).identity,
            instant(4),
        )
        assertNull(database.activitySnapshotDao().getById(retainedActivitySnapshot))
        assertNull(database.sequenceSnapshotDao().getById(oldSequenceSnapshot))
        assertNull(database.activityTemplateDao().getUserState(ACTIVITY_TEMPLATE)!!.lastUsedAtMs)
        assertNull(database.sequenceTemplateDao().getUserState(SEQUENCE_TEMPLATE)!!.lastUsedAtMs)
        assertEquals(activitySourceBeforeCommand, database.activityTemplateDao().getById(ACTIVITY_TEMPLATE))
        assertEquals(sequenceSourceBeforeCommand, database.sequenceTemplateDao().getById(SEQUENCE_TEMPLATE))
    }

    @Test
    fun engagedArchivedFulfilledAndCancelledPlansRejectUpdateWithoutReplacementWrites() {
        val engaged = createActivityPlan()
        val archived = createActivityPlan()
        val fulfilled = createActivityPlan()
        val cancelled = createActivityPlan()
        val live = liveRepository()
        live.startActivityFromPlan(engaged.id, instant(2), instant(2), ZoneId.of("UTC"))
        database.activityTemplateDao().archive(ACTIVITY_TEMPLATE, 2)
        val beforeSnapshots = count("activity_snapshots")

        assertThrows(IllegalArgumentException::class.java) {
            plans.updatePlanFromTemplate(reads.getFocusedAction(engaged.id).identity, instant(3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            plans.updatePlanFromTemplate(reads.getFocusedAction(archived.id).identity, instant(3))
        }
        database.activityTemplateDao().restore(ACTIVITY_TEMPLATE)
        live.completeActiveActivity(instant(4))
        live.startActivityFromPlan(fulfilled.id, instant(5), instant(5), ZoneId.of("UTC"))
        live.completeActiveActivity(instant(6))
        plans.cancelPlan(reads.getFocusedAction(cancelled.id).identity, instant(6))

        assertThrows(IllegalArgumentException::class.java) {
            plans.updatePlanFromTemplate(reads.getFocusedAction(fulfilled.id).identity, instant(7))
        }
        assertThrows(IllegalArgumentException::class.java) {
            plans.updatePlanFromTemplate(reads.getFocusedAction(cancelled.id).identity, instant(7))
        }
        assertEquals(beforeSnapshots, count("activity_snapshots"))
    }

    @Test
    fun unavailableSourceRejectsUpdateWithoutReplacingTheFrozenSnapshot() {
        val plan = createActivityPlan()
        val expected = reads.getFocusedAction(plan.id).identity
        val beforeSnapshots = count("activity_snapshots")
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM activity_templates WHERE id = '$ACTIVITY_TEMPLATE'",
        )

        assertEquals(PlanSourceState.UNAVAILABLE, reads.getFocusedAction(plan.id).sourceState)
        assertThrows(IllegalArgumentException::class.java) {
            plans.updatePlanFromTemplate(expected, instant(2))
        }
        assertEquals(plan.activitySnapshotId, plans.getPlan(plan.id)!!.activitySnapshotId)
        assertEquals(beforeSnapshots, count("activity_snapshots"))
    }

    @Test
    fun failedReplacementSwitchRollsBackTheInsertedSnapshotAndOriginalPlanMutation() {
        var sabotageSwitch = false
        lateinit var plan: com.alexandr5476.lifetracing.domain.PlanEntry
        val failingRepository =
            repository(
                nextActivitySnapshotId = {
                    if (sabotageSwitch) {
                        database.openHelper.writableDatabase.execSQL(
                            "UPDATE plan_entries SET updated_at_ms = 1500 WHERE id = '${plan.id.value}'",
                        )
                    }
                    ActivitySnapshotId("rollback-snapshot-${++activitySnapshotId}")
                },
            )
        plan =
            failingRepository.createActivityPlanFromTemplate(
                ActivityTemplateId(ACTIVITY_TEMPLATE),
                PlanSchedule.FloatingDay(LocalDate.parse("2026-08-20")),
                instant(1),
            )
        val expected = reads.getFocusedAction(plan.id).identity
        val beforeSnapshots = count("activity_snapshots")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_templates SET name = 'Activity v2', revision = 2, updated_at_ms = 2 " +
                "WHERE id = '$ACTIVITY_TEMPLATE'",
        )
        sabotageSwitch = true

        assertThrows(IllegalStateException::class.java) {
            failingRepository.updatePlanFromTemplate(expected, instant(2))
        }
        assertEquals(expected, reads.getFocusedAction(plan.id).identity)
        assertEquals(beforeSnapshots, count("activity_snapshots"))
        assertNull(database.activitySnapshotDao().getById("rollback-snapshot-$activitySnapshotId"))
    }

    private fun createActivityPlan() =
        plans.createActivityPlanFromTemplate(
            ActivityTemplateId(ACTIVITY_TEMPLATE),
            PlanSchedule.FloatingDay(LocalDate.parse("2026-08-20")),
            instant(1),
        )

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
            { SequenceSnapshotNodeId("sequence-node-$sequenceSnapshotId") },
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
                userState = ActivityTemplateUserStateEntity(ACTIVITY_TEMPLATE, null, null),
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
                SequenceTemplateUserStateEntity(SEQUENCE_TEMPLATE, null, null),
            ),
        )
    }

    private fun count(
        table: String,
        where: String = "1",
    ) = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table` WHERE $where").use {
        check(it.moveToFirst())
        it.getInt(0)
    }

    private fun instant(seconds: Long) = Instant.ofEpochSecond(seconds)

    companion object {
        private const val ACTIVITY_TEMPLATE = "activity-template"
        private const val SEQUENCE_TEMPLATE = "sequence-template"
    }
}
