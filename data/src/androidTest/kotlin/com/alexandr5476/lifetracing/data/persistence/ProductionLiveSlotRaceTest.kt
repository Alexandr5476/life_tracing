package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityEntryFieldReference
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityEntryValueOverride
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.LiveSessionConflictException
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFactory
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProductionLiveSlotRaceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private val databases = mutableListOf<LifeTracingDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "production-live-slot-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        databases.forEach(LifeTracingDatabase::close)
        context.deleteDatabase(databaseName)
    }

    @Test
    fun concurrentProductionActivityStartsHaveOneDurableWinnerAndClassifiedLoser() {
        val seed = openDatabase()
        activity(seed, "activity-a")
        activity(seed, "activity-b")
        val plans = planRepository(seed)
        val planA =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("activity-a"),
                PlanTarget.FloatingDay(LocalDate.ofEpochDay(0)),
                Instant.EPOCH,
            )
        val planB =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("activity-b"),
                PlanTarget.FloatingDay(LocalDate.ofEpochDay(0)),
                Instant.EPOCH,
            )
        val snapshotsBefore = count(seed, "activity_snapshots")
        val left = activityCommands(openDatabase())
        val right = activityCommands(openDatabase())

        val results =
            contend(
                {
                    left.startLive(
                        ActivityEntrySource.Template(ActivityTemplateId("activity-a")),
                        at(10),
                        at(10),
                        ZoneOffset.UTC,
                    )
                },
                {
                    right.startLive(
                        ActivityEntrySource.Template(ActivityTemplateId("activity-b")),
                        at(10),
                        at(10),
                        ZoneOffset.UTC,
                    )
                },
            )

        assertOneWinnerAndConflict(results)
        val observer = openDatabase()
        val session = requireNotNull(observer.activeSessionDao().get())
        val execution =
            requireNotNull(
                observer.activityExecutionDao().getAggregate(requireNotNull(session.activityExecutionId).value),
            ).toDomain()
        val winner =
            requireNotNull(
                requireNotNull(observer.activitySnapshotDao().getById(execution.snapshotId.value)).sourceTemplateId,
            )
        val loser = setOf("activity-a", "activity-b").single { it != winner }
        assertEquals(ActivityExecutionStatus.RUNNING, execution.status)
        assertEquals(at(10), execution.startedAt)
        assertEquals(1, count(observer, "active_session"))
        assertEquals(1, count(observer, "activity_executions", "context_type = 'STANDALONE'"))
        assertEquals(snapshotsBefore + 1, count(observer, "activity_snapshots"))
        assertEquals(10_000L, observer.activityTemplateDao().getUserState(winner)?.lastUsedAtMs)
        assertNull(observer.activityTemplateDao().getUserState(loser)?.lastUsedAtMs)
        listOf(planA.id, planB.id).forEach { id ->
            val plan = requireNotNull(observer.planEntryDao().getById(id.value)).toDomain()
            assertEquals(PlanEntryStatus.PLANNED, plan.status)
            assertNull(plan.fulfilledActivityExecutionId)
        }
    }

    @Test
    fun concurrentProductionActivityAndSequenceStartsHaveOneDurableWinnerAndNoLoserTree() {
        val seed = openDatabase()
        activity(seed, "activity")
        activitySnapshot(seed, "step")
        sequence(seed, "sequence", "step")
        val plans = planRepository(seed)
        val activityPlan =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("activity"),
                PlanTarget.FloatingDay(LocalDate.ofEpochDay(0)),
                Instant.EPOCH,
            )
        val sequencePlan =
            plans.createSequencePlanFromTemplate(
                SequenceTemplateId("sequence"),
                PlanTarget.FloatingDay(LocalDate.ofEpochDay(0)),
                Instant.EPOCH,
            )
        val activitySnapshotsBefore = count(seed, "activity_snapshots")
        val sequenceSnapshotsBefore = count(seed, "sequence_snapshots")
        val activity = activityCommands(openDatabase())
        val library = library(openDatabase())

        val results =
            contend(
                {
                    activity.startLive(
                        ActivityEntrySource.Template(ActivityTemplateId("activity")),
                        at(20),
                        at(20),
                        ZoneOffset.UTC,
                    )
                },
                { library.startSequenceFromTemplate(SequenceTemplateId("sequence"), at(20), at(20), ZoneOffset.UTC) },
            )

        assertOneWinnerAndConflict(results)
        val observer = openDatabase()
        val session = requireNotNull(observer.activeSessionDao().get())
        val activityWon = session.activityExecutionId != null
        val active = requireNotNull(LiveSessionRepository.create(observer).getActiveRuntime())
        assertEquals(1, count(observer, "active_session"))
        assertEquals(if (activityWon) 1 else 0, count(observer, "activity_executions", "context_type = 'STANDALONE'"))
        assertEquals(if (activityWon) 0 else 1, count(observer, "sequence_executions"))
        assertEquals(activitySnapshotsBefore + if (activityWon) 1 else 0, count(observer, "activity_snapshots"))
        assertEquals(sequenceSnapshotsBefore + if (activityWon) 0 else 1, count(observer, "sequence_snapshots"))
        assertEquals(
            if (activityWon) 20_000L else null,
            observer.activityTemplateDao().getUserState("activity")?.lastUsedAtMs,
        )
        assertEquals(
            if (activityWon) null else 20_000L,
            observer.sequenceTemplateDao().getUserState("sequence")?.lastUsedAtMs,
        )
        assertEquals(session, active.session)
        val activityPlanAfter = requireNotNull(observer.planEntryDao().getById(activityPlan.id.value)).toDomain()
        val sequencePlanAfter = requireNotNull(observer.planEntryDao().getById(sequencePlan.id.value)).toDomain()
        assertEquals(PlanEntryStatus.PLANNED, activityPlanAfter.status)
        assertEquals(PlanEntryStatus.PLANNED, sequencePlanAfter.status)
        assertNull(activityPlanAfter.fulfilledActivityExecutionId)
        assertNull(sequencePlanAfter.fulfilledSequenceExecutionId)
    }

    @Test
    fun productionWritersSurviveReloadThroughCanonicalDailyAndHistoryReaders() {
        val seed = openDatabase()
        activity(seed, "stopwatch")
        activity(seed, "timer", "TIMER", 60_000)
        activity(seed, "no-live", "NO_LIVE_TRACKING", fields = true)
        activitySnapshot(seed, "step")
        sequence(seed, "sequence", "step")
        val plans = planRepository(seed)
        val planIds =
            listOf(
                plans
                    .createActivityPlanFromTemplate(
                        ActivityTemplateId("stopwatch"),
                        PlanTarget.FloatingDay(LocalDate.ofEpochDay(0)),
                        Instant.EPOCH,
                    ).id,
                plans
                    .createActivityPlanFromTemplate(
                        ActivityTemplateId("no-live"),
                        PlanTarget.FloatingDay(LocalDate.ofEpochDay(0)),
                        Instant.EPOCH,
                    ).id,
                plans
                    .createSequencePlanFromTemplate(
                        SequenceTemplateId("sequence"),
                        PlanTarget.FloatingDay(LocalDate.ofEpochDay(0)),
                        Instant.EPOCH,
                    ).id,
            )

        val stopwatch =
            activityCommands(openDatabase()).startLive(
                ActivityEntrySource.Template(ActivityTemplateId("stopwatch")),
                at(10),
                at(10),
                ZoneOffset.UTC,
            )
        var reloaded = reloadDatabase()
        val stopwatchDaily = daily(reloaded, 10).active as DailyActive.Activity
        assertEquals(stopwatch.id, stopwatchDaily.runtime.execution.id)
        assertNull(stopwatchDaily.runtime.execution.planEntryId)
        assertEquals(
            "stopwatch",
            reloaded.activitySnapshotDao().getById(stopwatch.snapshotId.value)?.sourceTemplateId,
        )
        assertEquals(1L, reloaded.activitySnapshotDao().getById(stopwatch.snapshotId.value)?.sourceRevision)
        assertEquals(
            "stopwatch-series",
            stopwatchDaily.runtime.execution.statisticsSeriesId
                ?.value,
        )
        LiveSessionRepository.create(reloaded).completeActiveActivity(at(11))

        val timer =
            activityCommands(reloaded).startLive(
                ActivityEntrySource.Template(ActivityTemplateId("timer")),
                at(20),
                at(20),
                ZoneOffset.UTC,
            )
        reloaded = reloadDatabase()
        val timerDaily = daily(reloaded, 20).active as DailyActive.Activity
        assertEquals(timer.id, timerDaily.runtime.execution.id)
        assertEquals(at(20), timerDaily.runtime.execution.startedAt)
        assertEquals(
            60_000L,
            timerDaily.runtime.snapshot.timerTarget
                ?.toMillis(),
        )
        assertEquals("FINISH", timerDaily.runtime.snapshot.settings.timerZeroBehavior.name)
        LiveSessionRepository.create(reloaded).completeActiveActivity(at(21))

        val sequence =
            library(reloaded).startSequenceFromTemplate(
                SequenceTemplateId("sequence"),
                at(30),
                at(30),
                ZoneOffset.UTC,
            )
        reloaded = reloadDatabase()
        val sequenceDaily = daily(reloaded, 30).active as DailyActive.Sequence
        assertEquals(sequence.execution.id, sequenceDaily.runtime.execution.id)
        assertNull(sequenceDaily.runtime.execution.planEntryId)
        assertEquals(
            "sequence",
            sequenceDaily.runtime.snapshot.sourceTemplateId
                ?.value,
        )
        assertEquals(1L, sequenceDaily.runtime.snapshot.sourceRevision)
        assertEquals(1, sequenceDaily.runtime.execution.occurrences.size)
        assertNull(sequenceDaily.runtime.currentChild?.planEntryId)

        val noLiveWriter = library(openDatabase())
        val defaults =
            noLiveWriter.completeNoLiveActivityFromTemplate(
                ActivityTemplateId("no-live"),
                at(31),
                at(31),
                ZoneOffset.UTC,
            )
        val zero =
            noLiveWriter.completeNoLiveActivityFromTemplate(
                ActivityTemplateId("no-live"),
                at(32),
                at(32),
                ZoneOffset.UTC,
                listOf(
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Template(ActivityTemplateFieldId("no-live-main")),
                        ActivityEntryValue.Number(0),
                    ),
                ),
            )
        val missing =
            noLiveWriter.completeNoLiveActivityFromTemplate(
                ActivityTemplateId("no-live"),
                at(33),
                at(33),
                ZoneOffset.UTC,
                listOf(
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Template(ActivityTemplateFieldId("no-live-main")),
                        ActivityEntryValue.Missing,
                    ),
                ),
            )

        reloaded = reloadDatabase()
        val afterNoLive = daily(reloaded, 33)
        assertEquals(sequence.execution.id, (afterNoLive.active as DailyActive.Sequence).runtime.execution.id)
        assertTrue(
            afterNoLive.completedHistory
                .mapNotNull {
                    (it as? com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot)?.executionId
                }.containsAll(listOf(defaults.id, zero.id, missing.id)),
        )
        listOf(defaults, zero, missing).forEach { execution ->
            assertEquals(ActivityExecutionStatus.COMPLETED, execution.status)
            assertNull(execution.startedAt)
            assertNull(execution.activeDuration)
            assertNull(execution.completionReason)
            assertNull(execution.planEntryId)
        }
        assertEquals(1_000L, (defaults.values.single() as NumberExecutionValue).scaledValue)
        assertEquals(0L, (zero.values.single() as NumberExecutionValue).scaledValue)
        assertTrue(missing.values.isEmpty())
        val history = HistoryReadRepository(reloaded)
        assertEquals(
            1_000L,
            (
                requireNotNull(history.getActivityDetail(defaults.id)).fields.single().actualValue as
                    ActivityHistoryActualValue.Number
            ).scaledValue,
        )
        assertEquals(
            0L,
            (
                requireNotNull(history.getActivityDetail(zero.id)).fields.single().actualValue as
                    ActivityHistoryActualValue.Number
            ).scaledValue,
        )
        assertEquals(
            ActivityHistoryActualValue.Missing,
            requireNotNull(history.getActivityDetail(missing.id)).fields.single().actualValue,
        )
        val defaultsSnapshot =
            requireNotNull(reloaded.activitySnapshotDao().getAggregate(defaults.snapshotId.value)).toDomain()
        assertEquals("no-live", defaultsSnapshot.sourceTemplateId?.value)
        assertEquals(1L, defaultsSnapshot.sourceRevision)
        assertEquals("no-live-series", defaultsSnapshot.statisticsSeriesId?.value)
        assertEquals(ActivityTemplateFieldId("no-live-main"), defaultsSnapshot.fields.single().sourceFieldId)
        assertEquals(10_000L, reloaded.activityTemplateDao().getUserState("stopwatch")?.lastUsedAtMs)
        assertEquals(20_000L, reloaded.activityTemplateDao().getUserState("timer")?.lastUsedAtMs)
        assertEquals(30_000L, reloaded.sequenceTemplateDao().getUserState("sequence")?.lastUsedAtMs)
        assertEquals(33_000L, reloaded.activityTemplateDao().getUserState("no-live")?.lastUsedAtMs)
        planIds.forEach { id ->
            val plan = requireNotNull(reloaded.planEntryDao().getById(id.value)).toDomain()
            assertEquals(PlanEntryStatus.PLANNED, plan.status)
            assertNull(plan.fulfilledActivityExecutionId)
            assertNull(plan.fulfilledSequenceExecutionId)
        }
    }

    private fun contend(
        left: () -> Any,
        right: () -> Any,
    ): List<Result<Any>> {
        val barrier = CyclicBarrier(3)
        val executor = Executors.newFixedThreadPool(2)
        return try {
            val futures =
                listOf(left, right).map { contender ->
                    executor.submit<Result<Any>> {
                        barrier.await(10, TimeUnit.SECONDS)
                        runCatching(contender)
                    }
                }
            barrier.await(10, TimeUnit.SECONDS)
            futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun assertOneWinnerAndConflict(results: List<Result<Any>>) {
        assertEquals(1, results.count(Result<Any>::isSuccess))
        val failure = results.single { it.isFailure }.exceptionOrNull()
        assertTrue("Expected LiveSessionConflictException, got $failure", failure is LiveSessionConflictException)
    }

    private fun openDatabase(): LifeTracingDatabase =
        LifeTracingDatabase
            .builder(context, databaseName)
            .allowMainThreadQueries()
            .build()
            .also(databases::add)

    private fun reloadDatabase(): LifeTracingDatabase {
        closeDatabases()
        return openDatabase()
    }

    private fun closeDatabases() {
        databases.forEach(LifeTracingDatabase::close)
        databases.clear()
    }

    private fun daily(
        database: LifeTracingDatabase,
        nowSeconds: Long,
    ) = DailyReadRepository(database, CurrentZoneIdProvider { ZoneOffset.UTC }, LiveSessionRepository.create(database))
        .getDaily(DailyQuery(LocalDate.ofEpochDay(0), at(nowSeconds), 100))

    private fun activityCommands(database: LifeTracingDatabase): ActivityCommandRepository =
        ActivityCommandRepository(
            database,
            LiveSessionRepository.create(database),
            activitySnapshotFactory(),
            { ActivityExecutionId(uuid()) },
        )

    private fun library(database: LifeTracingDatabase): LibraryRepository =
        LibraryRepository(
            database,
            LiveSessionRepository.create(database),
            activitySnapshotFactory(),
            sequenceSnapshotFactory(),
            { ActivityTemplateId(uuid()) },
            { ActivityTemplateFieldId(uuid()) },
            { CategoryOptionId(uuid()) },
            { SequenceTemplateId(uuid()) },
            { SequenceTemplateFieldId(uuid()) },
            { SequenceTemplateCategoryOptionId(uuid()) },
            { SequenceNodeId(uuid()) },
            { StatisticsSeriesId(uuid()) },
        )

    private fun planRepository(database: LifeTracingDatabase): PlanRepository =
        PlanRepository(
            database,
            { PlanEntryId(uuid()) },
            activitySnapshotFactory(),
            sequenceSnapshotFactory(),
            CurrentZoneIdProvider { ZoneOffset.UTC },
        )

    private fun activitySnapshotFactory() =
        ActivitySnapshotFactory(
            { ActivitySnapshotId(uuid()) },
            { ActivitySnapshotFieldId(uuid()) },
            { ActivitySnapshotCategoryOptionId(uuid()) },
        )

    private fun sequenceSnapshotFactory() =
        SequenceSnapshotFactory(
            { SequenceSnapshotId(uuid()) },
            { SequenceSnapshotFieldId(uuid()) },
            { SequenceSnapshotCategoryOptionId(uuid()) },
            { SequenceSnapshotNodeId(uuid()) },
        )

    private fun activity(
        database: LifeTracingDatabase,
        id: String,
        mode: String = "STOPWATCH",
        timerTargetMs: Long? = null,
        fields: Boolean = false,
    ) {
        series(database, "$id-series", "ACTIVITY")
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(id, id, null, mode, timerTargetMs, "$id-series", 1, 0, 0, null, null),
                ActivityTemplateSettingsEntity(id),
                fields =
                    if (fields) {
                        listOf(
                            ActivityTemplateFieldEntity(
                                "$id-main",
                                id,
                                0,
                                "Main value",
                                "NUMBER",
                                "reps",
                                0,
                                1_000,
                                null,
                                null,
                                true,
                                0,
                                0,
                                null,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                userState = ActivityTemplateUserStateEntity(id, null, null),
            ),
        )
    }

    private fun activitySnapshot(
        database: LifeTracingDatabase,
        id: String,
    ) {
        series(database, "$id-series", "ACTIVITY")
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(id, id, null, "STOPWATCH", null, null, null, "$id-series", false, 0),
                ActivitySnapshotSettingsEntity(id),
            ),
        )
    }

    private fun sequence(
        database: LifeTracingDatabase,
        id: String,
        stepSnapshotId: String,
    ) {
        series(database, "$id-series", "SEQUENCE")
        database.sequenceTemplateDao().insertAggregate(
            SequenceTemplateAggregateEntity(
                SequenceTemplateEntity(id, id, null, "$id-series", 1, 0, 0, null, null),
                SequenceTemplateSettingsEntity(id),
                SequenceTemplateUserStateEntity(id, null, null),
                nodes = listOf(SequenceNodeEntity("$id-step", id, "STEP", null, 0, stepSnapshotId, null)),
            ),
        )
    }

    private fun series(
        database: LifeTracingDatabase,
        id: String,
        kind: String,
    ) {
        database.statisticsSeriesDao().insert(StatisticsSeriesEntity(id, kind, id, 0, null))
    }

    private fun count(
        database: LifeTracingDatabase,
        table: String,
        where: String? = null,
    ): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table${where?.let { " WHERE $it" }.orEmpty()}")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    private fun at(seconds: Long): Instant = Instant.ofEpochSecond(seconds)

    private fun uuid(): String = UUID.randomUUID().toString()
}
