package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class PlanEntryDatabaseTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var live: LiveSessionRepository

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        LiveRuntimeTestFixtures(database).apply {
            seedSeries()
            activity("stopwatch", "STOPWATCH")
            activity("timer", "TIMER", 60_000)
            activity("no-live", "NO_LIVE_TRACKING")
            sequence("sequence", listOf("stopwatch"))
            sequence("sequence-other", listOf("timer"))
        }
        live = liveRepository()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun validTargetsRoundTripAndInvalidRowShapesAreRejected() {
        val rows =
            listOf(
                plan("day", activity = "stopwatch", plannedDay = "2026-08-20"),
                plan("exact", activity = "stopwatch", scheduledMs = 1_000, creationZone = "UTC"),
                plan("week", activity = "stopwatch", precision = "WEEK", week = "2026-08-17"),
                plan("month", activity = "stopwatch", precision = "MONTH", month = "2026-08"),
                plan("sequence-plan", sequence = "sequence", plannedDay = "2026-08-20"),
            )
        rows.forEach(database.planEntryDao()::insert)

        assertEquals(
            PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")),
            database
                .planEntryDao()
                .getById("day")!!
                .toDomain()
                .target,
        )
        assertEquals(
            Instant.ofEpochMilli(1_000),
            (
                database
                    .planEntryDao()
                    .getById("exact")!!
                    .toDomain()
                    .target as PlanTarget.ExactDay
            ).scheduledAt,
        )
        assertEquals(5, rows.count { database.planEntryDao().getById(it.id) != null })

        listOf(
            plan("bad-kind", activity = "stopwatch").copy(trackableKind = "UNKNOWN"),
            plan("bad-target", activity = "stopwatch", plannedDay = "2026-08-20").copy(plannedMonth = "2026-08"),
            plan("bad-status", activity = "stopwatch").copy(status = "CANCELLED"),
            plan("bad-revision", activity = "stopwatch").copy(sourceActivityTemplateId = null, sourceRevision = 0),
            plan("bad-snapshots", activity = "stopwatch").copy(sequencePlanSnapshotId = "sequence"),
        ).forEach { invalid ->
            assertThrows(SQLiteConstraintException::class.java) { database.planEntryDao().insert(invalid) }
        }
    }

    @Test
    fun snapshotAndExecutionForeignKeysUseFrozenDeleteActions() {
        database.planEntryDao().insert(plan("plan", activity = "stopwatch"))
        assertThrows(SQLiteConstraintException::class.java) {
            database.openHelper.writableDatabase.execSQL("DELETE FROM activity_snapshots WHERE id = 'stopwatch'")
        }

        val started = live.startActivityFromPlan(PlanEntryId("plan"), instant(0), instant(0), ZoneId.of("UTC"))
        database.openHelper.writableDatabase.execSQL("DELETE FROM plan_entries WHERE id = 'plan'")
        assertNull(database.activityExecutionDao().getById(started.id.value)?.planEntryId)

        database.planEntryDao().insert(plan("fulfilled", activity = "no-live"))
        val completed = live.completeNoLiveActivityFromPlan(PlanEntryId("fulfilled"), instant(10), ZoneId.of("UTC"))
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM activity_executions WHERE id = '${completed.id.value}'",
        )
        val retained = database.planEntryDao().getById("fulfilled")!!.toDomain()
        assertEquals(PlanEntryStatus.FULFILLED, retained.status)
        assertNull(retained.fulfilledActivityExecutionId)
    }

    @Test
    fun activityPlanStartLocksMutationsAndCompletionFulfillsAtomically() {
        database.planEntryDao().insert(plan("plan", activity = "stopwatch"))
        val execution = live.startActivityFromPlan(PlanEntryId("plan"), instant(0), instant(0), ZoneId.of("UTC"))
        val plans = planRepository(ZoneId.of("UTC"))

        assertTrue(plans.isEngaged(PlanEntryId("plan")))
        assertThrows(IllegalArgumentException::class.java) { plans.cancelPlan(PlanEntryId("plan"), instant(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            plans.reschedulePlanEntry(
                PlanEntryId("plan"),
                PlanTarget.FloatingDay(LocalDate.parse("2026-08-21")),
                instant(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            live.startActivityFromPlan(PlanEntryId("plan"), instant(1), instant(1), ZoneId.of("UTC"))
        }

        live.completeActiveActivity(instant(30))
        val plan = plans.getPlan(PlanEntryId("plan"))!!
        assertEquals(PlanEntryStatus.FULFILLED, plan.status)
        assertEquals(execution.id, plan.fulfilledActivityExecutionId)
        assertEquals(instant(30), plan.fulfilledAt)
        assertFalse(plans.isEngaged(PlanEntryId("plan")))
    }

    @Test
    fun timerLateReconciliationUsesLogicalDeadlineAndOrdinaryExecutionDoesNotMatchPlan() {
        database.planEntryDao().insert(plan("timer-plan", activity = "timer"))
        live.startActivityFromPlan(PlanEntryId("timer-plan"), instant(0), instant(0), ZoneId.of("UTC"))
        live.reconcileActiveSession(instant(600))
        assertEquals(
            instant(60),
            database
                .planEntryDao()
                .getById("timer-plan")!!
                .toDomain()
                .fulfilledAt,
        )

        database.planEntryDao().insert(plan("ordinary-plan", activity = "stopwatch"))
        val ordinary =
            live.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("stopwatch"),
                instant(700),
                instant(700),
                ZoneId.of("UTC"),
            )
        live.completeActiveActivity(instant(710))
        assertNull(database.activityExecutionDao().getById(ordinary.id.value)?.planEntryId)
        assertEquals("PLANNED", database.planEntryDao().getById("ordinary-plan")?.status)
    }

    @Test
    fun noLiveAndSequenceFulfillmentUseOnlyRootExplicitLinkage() {
        database.planEntryDao().insert(plan("quick", activity = "no-live"))
        val quick = live.completeNoLiveActivityFromPlan(PlanEntryId("quick"), instant(5), ZoneId.of("UTC"))
        assertEquals("FULFILLED", database.planEntryDao().getById("quick")?.status)
        assertEquals("quick", database.activityExecutionDao().getById(quick.id.value)?.planEntryId)

        database.planEntryDao().insert(plan("sequence-plan", sequence = "sequence"))
        val started =
            live.startSequenceFromPlan(
                PlanEntryId("sequence-plan"),
                instant(10),
                instant(10),
                ZoneId.of("UTC"),
            )
        assertEquals("sequence-plan", database.sequenceExecutionDao().getById(started.execution.id.value)?.planEntryId)
        val child =
            database.activityExecutionDao().getAggregateByOccurrence(
                started.execution.currentOccurrenceId!!.value,
            )
        assertNull(child?.execution?.planEntryId)
        live.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, instant(20))
        assertEquals("FULFILLED", database.planEntryDao().getById("sequence-plan")?.status)
        assertEquals(
            started.execution.id.value,
            database.planEntryDao().getById("sequence-plan")?.fulfilledSequenceExecutionId,
        )
    }

    @Test
    fun calendarMergesFloatingAndExactWithDstSafeExclusiveWindows() {
        val zone = ZoneId.of("America/New_York")
        val springDay = LocalDate.parse("2026-03-08")
        val lower = springDay.atStartOfDay(zone).toInstant()
        val upper = springDay.plusDays(1).atStartOfDay(zone).toInstant()
        assertEquals(
            23,
            java.time.Duration
                .between(lower, upper)
                .toHours(),
        )
        database.planEntryDao().insert(plan("floating", activity = "stopwatch", plannedDay = springDay.toString()))
        database.planEntryDao().insert(plan("lower", activity = "stopwatch", scheduledMs = lower.toEpochMilli()))
        database.planEntryDao().insert(plan("upper", activity = "stopwatch", scheduledMs = upper.toEpochMilli()))

        val projected = planRepository(zone).getDayPlans(springDay, springDay)
        assertEquals(setOf("floating", "lower"), projected.map { it.plan.id.value }.toSet())

        val fall = LocalDate.parse("2026-11-01")
        assertEquals(
            25,
            java.time.Duration
                .between(
                    fall.atStartOfDay(zone).toInstant(),
                    fall.plusDays(1).atStartOfDay(zone).toInstant(),
                ).toHours(),
        )
    }

    @Test
    fun requiredPlanIndexesExist() {
        val names =
            database.openHelper.readableDatabase.query("PRAGMA index_list(`plan_entries`)").use { cursor ->
                buildSet {
                    val index = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(index))
                }
            }
        assertTrue(
            names.containsAll(
                setOf(
                    "plan_entries_status_planned_day",
                    "plan_entries_status_planned_week_start",
                    "plan_entries_status_planned_month",
                    "plan_entries_status_scheduled_instant",
                    "plan_entries_activity_snapshot_id",
                    "plan_entries_sequence_plan_snapshot_id",
                    "plan_entries_fulfilled_activity_execution_id",
                    "plan_entries_fulfilled_sequence_execution_id",
                ),
            ),
        )
    }

    @Test
    fun templatePlanCreationUpdateAndIdCollisionsAreTransactional() {
        seedTemplates()
        var activitySnapshot = 0
        var sequenceSnapshot = 0
        val repository =
            PlanRepository(
                database,
                { PlanEntryId("generated-plan") },
                ActivitySnapshotFactory(
                    { ActivitySnapshotId("activity-plan-snapshot-${++activitySnapshot}") },
                    { ActivitySnapshotFieldId("activity-plan-field") },
                    { ActivitySnapshotCategoryOptionId("activity-plan-option") },
                ),
                SequenceSnapshotFactory(
                    { SequenceSnapshotId("sequence-plan-snapshot-${++sequenceSnapshot}") },
                    { SequenceSnapshotFieldId("sequence-plan-field") },
                    { SequenceSnapshotCategoryOptionId("sequence-plan-option") },
                    { SequenceSnapshotNodeId("sequence-plan-node") },
                ),
                CurrentZoneIdProvider { ZoneId.of("UTC") },
            )
        val activity =
            repository.createActivityPlanFromTemplate(
                ActivityTemplateId("activity-template"),
                PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")),
                instant(1),
            )
        assertEquals("activity-plan-snapshot-1", activity.activitySnapshotId?.value)
        assertEquals(
            "activity-template",
            database.activitySnapshotDao().getById("activity-plan-snapshot-1")?.sourceTemplateId,
        )

        val currentTemplate = database.activityTemplateDao().getById("activity-template")!!
        database.activityTemplateDao().updateTemplate(
            currentTemplate.copy(name = "Updated", revision = 2, updatedAtMs = 2_000),
        )
        val updated = repository.updatePlanFromTemplate(activity.id, instant(2))
        assertEquals(2, updated.sourceRevision)
        assertEquals("activity-plan-snapshot-2", updated.activitySnapshotId?.value)
        assertNull(database.activitySnapshotDao().getById("activity-plan-snapshot-1"))

        val sequence =
            PlanRepository(
                database,
                { PlanEntryId("sequence-generated-plan") },
                ActivitySnapshotFactory(
                    { ActivitySnapshotId("unused") },
                    { ActivitySnapshotFieldId("unused") },
                    { ActivitySnapshotCategoryOptionId("unused") },
                ),
                SequenceSnapshotFactory(
                    { SequenceSnapshotId("sequence-created") },
                    { SequenceSnapshotFieldId("sequence-field") },
                    { SequenceSnapshotCategoryOptionId("sequence-option") },
                    { SequenceSnapshotNodeId("sequence-node") },
                ),
                CurrentZoneIdProvider { ZoneId.of("UTC") },
            ).createSequencePlanFromTemplate(
                SequenceTemplateId("sequence-template"),
                PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")),
                instant(3),
            )
        assertEquals("sequence-created", sequence.sequenceSnapshotId?.value)

        val beforeSnapshots = count("activity_snapshots")
        assertThrows(Exception::class.java) {
            repository.createActivityPlanFromTemplate(
                ActivityTemplateId("activity-template"),
                PlanTarget.FloatingDay(LocalDate.parse("2026-08-21")),
                instant(4),
            )
        }
        assertEquals(beforeSnapshots, count("activity_snapshots"))
        assertEquals(1, count("plan_entries", "id = 'generated-plan'"))
    }

    @Test
    fun failedFulfillmentRollsBackActivityAndSequenceRuntime() {
        database.planEntryDao().insert(plan("activity-plan", activity = "stopwatch"))
        val activity =
            live.startActivityFromPlan(
                PlanEntryId("activity-plan"),
                instant(0),
                instant(0),
                ZoneId.of("UTC"),
            )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE plan_entries SET status = 'CANCELLED', cancelled_at_ms = 1, updated_at_ms = 1 WHERE id = 'activity-plan'",
        )

        assertThrows(IllegalStateException::class.java) { live.completeActiveActivity(instant(30)) }
        assertEquals("RUNNING", database.activityExecutionDao().getById(activity.id.value)?.status)
        assertEquals(
            "ACTIVITY",
            database
                .activeSessionDao()
                .get()
                ?.kind
                ?.name,
        )

        database.openHelper.writableDatabase.execSQL(
            "UPDATE plan_entries SET status = 'PLANNED', cancelled_at_ms = NULL, updated_at_ms = 1 WHERE id = 'activity-plan'",
        )
        live.completeActiveActivity(instant(30))
        database.planEntryDao().insert(plan("sequence-plan-rollback", sequence = "sequence"))
        val sequence =
            live.startSequenceFromPlan(
                PlanEntryId("sequence-plan-rollback"),
                instant(40),
                instant(40),
                ZoneId.of("UTC"),
            )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE plan_entries SET status = 'CANCELLED', cancelled_at_ms = 41000, updated_at_ms = 41000 " +
                "WHERE id = 'sequence-plan-rollback'",
        )

        assertThrows(IllegalStateException::class.java) {
            live.completeCurrentSequenceStep(sequence.execution.currentOccurrenceId!!, instant(50))
        }
        assertEquals("RUNNING", database.sequenceExecutionDao().getById(sequence.execution.id.value)?.status)
        assertEquals(
            "SEQUENCE",
            database
                .activeSessionDao()
                .get()
                ?.kind
                ?.name,
        )
    }

    @Test
    fun semanticPlanLinkCorruptionIsRejectedOnNormalReads() {
        database.planEntryDao().insert(plan("wrong-activity", activity = "timer"))
        val activity =
            live.startStandaloneTimedActivityFromSnapshot(
                ActivitySnapshotId("stopwatch"),
                instant(0),
                instant(0),
                ZoneId.of("UTC"),
            )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_executions SET plan_entry_id = 'wrong-activity' WHERE id = '${activity.id.value}'",
        )
        assertThrows(IllegalArgumentException::class.java) {
            database.activityExecutionDao().getAggregate(activity.id.value)
        }

        database.openHelper.writableDatabase.execSQL("DELETE FROM active_session")
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM activity_executions WHERE id = '${activity.id.value}'",
        )
        database.planEntryDao().insert(plan("wrong-sequence", sequence = "sequence-other"))
        val sequence =
            live.startSequenceFromSnapshot(
                SequenceSnapshotId("sequence"),
                instant(10),
                instant(10),
                ZoneId.of("UTC"),
            )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_executions SET plan_entry_id = 'wrong-sequence' WHERE id = '${sequence.execution.id.value}'",
        )
        assertThrows(IllegalArgumentException::class.java) {
            database.sequenceExecutionDao().getAggregate(sequence.execution.id.value)
        }

        database.openHelper.writableDatabase.execSQL("DELETE FROM active_session")
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM sequence_executions WHERE id = '${sequence.execution.id.value}'",
        )
        database.planEntryDao().insert(plan("quick-a", activity = "no-live"))
        database.planEntryDao().insert(plan("quick-b", activity = "no-live"))
        live.completeNoLiveActivityFromPlan(PlanEntryId("quick-a"), instant(20), ZoneId.of("UTC"))
        val other = live.completeNoLiveActivityFromPlan(PlanEntryId("quick-b"), instant(21), ZoneId.of("UTC"))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE plan_entries SET fulfilled_activity_execution_id = '${other.id.value}' WHERE id = 'quick-a'",
        )
        assertThrows(IllegalArgumentException::class.java) {
            planRepository(ZoneId.of("UTC")).getPlan(PlanEntryId("quick-a"))
        }
    }

    @Test
    fun sourceLifecycleSoftDeleteAndSnapshotCollisionPreserveFrozenPlans() {
        seedTemplates()
        var snapshot = 0
        var planId = 0
        val plans =
            PlanRepository(
                database,
                { PlanEntryId("source-plan-${++planId}") },
                ActivitySnapshotFactory(
                    { ActivitySnapshotId("source-snapshot-${++snapshot}") },
                    { ActivitySnapshotFieldId("source-field") },
                    { ActivitySnapshotCategoryOptionId("source-option") },
                ),
                SequenceSnapshotFactory(
                    { SequenceSnapshotId("unused-sequence") },
                    { SequenceSnapshotFieldId("unused-field") },
                    { SequenceSnapshotCategoryOptionId("unused-option") },
                    { SequenceSnapshotNodeId("unused-node") },
                ),
                CurrentZoneIdProvider { ZoneId.of("UTC") },
            )
        val archivedPlan =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("activity-template"),
                PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")),
                instant(1),
            )
        val purgedPlan =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("activity-template"),
                PlanTarget.FloatingDay(LocalDate.parse("2026-08-21")),
                instant(2),
            )
        val snapshotsBefore = count("activity_snapshots")
        val collisionRepository =
            PlanRepository(
                database,
                { PlanEntryId("snapshot-collision-plan") },
                ActivitySnapshotFactory(
                    { ActivitySnapshotId("stopwatch") },
                    { ActivitySnapshotFieldId("collision-field") },
                    { ActivitySnapshotCategoryOptionId("collision-option") },
                ),
                SequenceSnapshotFactory(
                    { SequenceSnapshotId("unused") },
                    { SequenceSnapshotFieldId("unused") },
                    { SequenceSnapshotCategoryOptionId("unused") },
                    { SequenceSnapshotNodeId("unused") },
                ),
                CurrentZoneIdProvider { ZoneId.of("UTC") },
            )
        assertThrows(Exception::class.java) {
            collisionRepository.createActivityPlanFromTemplate(
                ActivityTemplateId("activity-template"),
                PlanTarget.FloatingDay(LocalDate.parse("2026-08-22")),
                instant(3),
            )
        }
        assertEquals(snapshotsBefore, count("activity_snapshots"))
        assertNull(database.planEntryDao().getById("snapshot-collision-plan"))
        database.activityTemplateDao().archive("activity-template", 3_000)
        assertThrows(IllegalArgumentException::class.java) {
            plans.updatePlanFromTemplate(archivedPlan.id, instant(4))
        }

        val execution = live.startActivityFromPlan(archivedPlan.id, instant(5), instant(5), ZoneId.of("UTC"))
        assertThrows(IllegalArgumentException::class.java) {
            plans.updatePlanFromTemplate(archivedPlan.id, instant(6))
        }
        live.completeActiveActivity(instant(7))
        assertEquals(1, database.activityExecutionDao().softDelete(execution.id.value, 8_000))
        assertEquals(PlanEntryStatus.FULFILLED, plans.getPlan(archivedPlan.id)?.status)

        database.openHelper.writableDatabase.execSQL("DELETE FROM activity_templates WHERE id = 'activity-template'")
        val retained = plans.getPlan(purgedPlan.id)!!
        assertNull(retained.sourceActivityTemplateId)
        assertEquals(1, retained.sourceRevision)
        live.startActivityFromPlan(purgedPlan.id, instant(9), instant(9), ZoneId.of("UTC"))
        live.completeActiveActivity(instant(10))
        assertEquals(PlanEntryStatus.FULFILLED, plans.getPlan(purgedPlan.id)?.status)
    }

    @Test
    fun engagementAndCompletionSurviveDatabaseReopen() {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "plan-process-death.db"
        context.deleteDatabase(name)
        database =
            LifeTracingDatabase
                .builder(context, name)
                .allowMainThreadQueries()
                .build()
        LiveRuntimeTestFixtures(database).apply {
            seedSeries()
            activity("stopwatch", "STOPWATCH")
        }
        database.planEntryDao().insert(plan("persisted-plan", activity = "stopwatch"))
        live = liveRepository()
        val execution =
            live.startActivityFromPlan(
                PlanEntryId("persisted-plan"),
                instant(0),
                instant(0),
                ZoneId.of("UTC"),
            )
        database.close()

        database =
            LifeTracingDatabase
                .builder(context, name)
                .allowMainThreadQueries()
                .build()
        live = liveRepository()
        val plans = planRepository(ZoneId.of("UTC"))
        assertEquals(PlanEntryStatus.PLANNED, plans.getPlan(PlanEntryId("persisted-plan"))?.status)
        assertTrue(plans.isEngaged(PlanEntryId("persisted-plan")))
        assertThrows(IllegalArgumentException::class.java) {
            plans.cancelPlan(PlanEntryId("persisted-plan"), instant(1))
        }
        assertEquals(
            execution.id,
            (live.getActiveRuntime() as com.alexandr5476.lifetracing.domain.ActiveActivityRuntime).execution.id,
        )
        live.completeActiveActivity(instant(2))
        assertEquals(PlanEntryStatus.FULFILLED, plans.getPlan(PlanEntryId("persisted-plan"))?.status)
        database.close()
        context.deleteDatabase(name)
        database =
            LifeTracingDatabase
                .inMemoryBuilder(context)
                .allowMainThreadQueries()
                .build()
    }

    private fun liveRepository(): LiveSessionRepository {
        var activity = 0
        var sequence = 0
        var occurrence = 0
        var interval = 0
        return LiveSessionRepository(
            database,
            { ActivityExecutionId("activity-${++activity}") },
            { ActivityExecutionPauseId("pause") },
            { SequenceExecutionId("sequence-${++sequence}") },
            { SequenceOccurrenceId("occurrence-${++occurrence}") },
            { SequenceIntervalId("interval-${++interval}") },
        )
    }

    private fun planRepository(zone: ZoneId) =
        PlanRepository(
            database,
            { PlanEntryId("generated") },
            ActivitySnapshotFactory(
                { ActivitySnapshotId("generated-activity") },
                { ActivitySnapshotFieldId("generated-field") },
                { ActivitySnapshotCategoryOptionId("generated-option") },
            ),
            SequenceSnapshotFactory(
                { SequenceSnapshotId("generated-sequence") },
                { SequenceSnapshotFieldId("generated-sequence-field") },
                { SequenceSnapshotCategoryOptionId("generated-sequence-option") },
                { SequenceSnapshotNodeId("generated-node") },
            ),
            CurrentZoneIdProvider { zone },
        )

    private fun seedTemplates() {
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    "activity-template",
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
                ActivityTemplateSettingsEntity("activity-template"),
                userState = ActivityTemplateUserStateEntity("activity-template", null, null),
            ),
        )
        database.sequenceTemplateDao().insertAggregate(
            SequenceTemplateAggregateEntity(
                SequenceTemplateEntity(
                    "sequence-template",
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
                SequenceTemplateSettingsEntity("sequence-template"),
                SequenceTemplateUserStateEntity("sequence-template", null, null),
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

    private fun plan(
        id: String,
        activity: String? = null,
        sequence: String? = null,
        precision: String = "DAY",
        plannedDay: String? = if (precision == "DAY") "2026-08-20" else null,
        week: String? = null,
        month: String? = null,
        scheduledMs: Long? = null,
        creationZone: String? = null,
    ) = PlanEntryEntity(
        id,
        if (activity != null) "ACTIVITY" else "SEQUENCE",
        null,
        null,
        null,
        activity,
        sequence,
        precision,
        if (scheduledMs == null) plannedDay else null,
        week,
        month,
        scheduledMs,
        creationZone,
        "PLANNED",
        null,
        null,
        0,
        0,
        null,
        null,
    )

    private fun instant(seconds: Long) = Instant.ofEpochSecond(seconds)
}
