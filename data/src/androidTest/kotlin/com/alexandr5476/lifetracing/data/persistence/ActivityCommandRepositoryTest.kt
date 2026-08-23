package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityEntryFieldReference
import com.alexandr5476.lifetracing.domain.ActivityEntryOptionReference
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityEntryValueOverride
import com.alexandr5476.lifetracing.domain.ActivityExecutionDurationCalculator
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPause
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivityExecutionTransitions
import com.alexandr5476.lifetracing.domain.ActivityHistoryCorrection
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.ExpiredFinishTimerDecisionRequiredException
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
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
import com.alexandr5476.lifetracing.domain.StatisticsFieldId
import com.alexandr5476.lifetracing.domain.StatisticsPeriod
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ConcurrentModificationException

@RunWith(AndroidJUnit4::class)
class ActivityCommandRepositoryTest {
    private lateinit var database: LifeTracingDatabase

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun backdatedTemplateStartUsesCanonicalLiveSlotRecentAndRollsBackSecondSnapshot() {
        template("stopwatch", TimeTrackingMode.STOPWATCH)
        val repository = repository("backdated")
        val execution =
            repository.startLive(
                ActivityEntrySource.Template(ActivityTemplateId("stopwatch")),
                instant(0),
                instant(1_200),
                ZoneId.of("Europe/Moscow"),
            )

        assertEquals(ActivityExecutionStatus.RUNNING, execution.status)
        assertEquals(instant(0), execution.startedAt)
        assertEquals(instant(1_200), execution.createdAt)
        assertEquals("1970-01-01", execution.primaryLocalDate.toString())
        assertEquals(execution.id, database.activeSessionDao().get()?.activityExecutionId)
        assertEquals(1_200_000L, database.activityTemplateDao().getUserState("stopwatch")?.lastUsedAtMs)

        val snapshotCount = count("activity_snapshots")
        assertThrows(IllegalArgumentException::class.java) {
            repository.startLive(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.STOPWATCH)),
                instant(1),
                instant(2),
                ZoneOffset.UTC,
            )
        }
        assertEquals(snapshotCount, count("activity_snapshots"))
        assertEquals(1, count("activity_executions"))
    }

    @Test
    fun recentUsesCommandTimeAndCannotRewindForDirectOrPlanTemplateUse() {
        template("manual-recent", TimeTrackingMode.STOPWATCH)
        template("monotonic-recent", TimeTrackingMode.STOPWATCH)
        template("plan-recent", TimeTrackingMode.STOPWATCH)
        template("plan-rewind", TimeTrackingMode.STOPWATCH)
        database.libraryDao().touchActivity("manual-recent", 2_000_000)
        database.libraryDao().touchActivity("monotonic-recent", 5_000_000)
        database.libraryDao().touchActivity("plan-recent", 2_000_000)
        database.libraryDao().touchActivity("plan-rewind", 5_000_000)
        val repository = repository("recent")
        val plans = planRepository()

        repository.addManualTimed(
            ActivityEntrySource.Template(ActivityTemplateId("manual-recent")),
            instant(100),
            instant(200),
            instant(3_000),
            ZoneOffset.UTC,
        )
        assertEquals(3_000_000L, database.activityTemplateDao().getUserState("manual-recent")?.lastUsedAtMs)

        repository.addManualTimed(
            ActivityEntrySource.Template(ActivityTemplateId("monotonic-recent")),
            instant(100),
            instant(200),
            instant(4_000),
            ZoneOffset.UTC,
        )
        assertEquals(5_000_000L, database.activityTemplateDao().getUserState("monotonic-recent")?.lastUsedAtMs)

        val plan =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("plan-recent"),
                PlanTarget.FloatingDay(LocalDate.of(2026, 8, 20)),
                instant(50),
            )
        repository.addManualTimed(
            ActivityEntrySource.Plan(plan.id),
            instant(100),
            instant(200),
            instant(3_000),
            ZoneOffset.UTC,
        )
        assertEquals(3_000_000L, database.activityTemplateDao().getUserState("plan-recent")?.lastUsedAtMs)

        val rewindPlan =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("plan-rewind"),
                PlanTarget.FloatingDay(LocalDate.of(2026, 8, 21)),
                instant(50),
            )
        repository.addManualTimed(
            ActivityEntrySource.Plan(rewindPlan.id),
            instant(100),
            instant(200),
            instant(4_000),
            ZoneOffset.UTC,
        )
        assertEquals(5_000_000L, database.activityTemplateDao().getUserState("plan-rewind")?.lastUsedAtMs)
    }

    @Test
    fun expiredFinishIsSurfacedWithoutResidueWhileOvertimeRemainsLive() {
        template("finish", TimeTrackingMode.TIMER, 600_000, "FINISH")
        template("overtime", TimeTrackingMode.TIMER, 600_000, "OVERTIME")
        val repository = repository("timer")

        assertThrows(ExpiredFinishTimerDecisionRequiredException::class.java) {
            repository.startLive(
                ActivityEntrySource.Template(ActivityTemplateId("finish")),
                instant(0),
                instant(900),
                ZoneOffset.UTC,
            )
        }
        assertEquals(0, count("activity_snapshots"))
        assertEquals(0, count("activity_executions"))
        assertNull(database.activeSessionDao().get())
        assertNull(database.activityTemplateDao().getUserState("finish")?.lastUsedAtMs)

        val overtime =
            repository.startLive(
                ActivityEntrySource.Template(ActivityTemplateId("overtime")),
                instant(0),
                instant(900),
                ZoneOffset.UTC,
            )
        assertEquals(ActivityExecutionStatus.RUNNING, overtime.status)
        assertEquals(ActivityExecutionStatus.RUNNING, repository.getHistory(overtime.id)?.execution?.status)
    }

    @Test
    fun manualHistoryPreservesDefaultsMissingZeroOverlapNoLiveAndStatistics() {
        template("timer", TimeTrackingMode.TIMER, 600_000, fields = true)
        template("live", TimeTrackingMode.STOPWATCH)
        template("no-live", TimeTrackingMode.NO_LIVE_TRACKING, fields = true)
        val repository = repository("manual")
        val timer =
            repository.addManualTimed(
                ActivityEntrySource.Template(ActivityTemplateId("timer")),
                instant(0),
                instant(840),
                instant(900),
                ZoneId.of("Europe/Berlin"),
                listOf(
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Template(
                            com.alexandr5476.lifetracing.domain
                                .ActivityTemplateFieldId("timer-number"),
                        ),
                        ActivityEntryValue.Number(0),
                    ),
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Template(
                            com.alexandr5476.lifetracing.domain
                                .ActivityTemplateFieldId("timer-text"),
                        ),
                        ActivityEntryValue.Missing,
                    ),
                ),
            )

        assertEquals(Duration.ofMinutes(14), timer.activeDuration)
        assertNull(database.activeSessionDao().get())
        assertTrue(timer.values.any { it is NumberExecutionValue && it.scaledValue == 0L })
        assertFalse(timer.values.any { it is TextExecutionValue })
        assertTrue(repository.overlapsCompletedHistory(instant(840), instant(850)))
        assertFalse(repository.overlapsCompletedHistory(instant(841), instant(850)))
        assertEquals(
            Duration.ofMinutes(14),
            StatisticsRepository(database) { StatisticsSeriesId("unused") }
                .activitySeries(StatisticsSeriesId("timer-series"), StatisticsPeriod.AllTime)
                .durations.total,
        )

        val active =
            repository.startLive(
                ActivityEntrySource.Template(ActivityTemplateId("live")),
                instant(1_000),
                instant(1_000),
                ZoneOffset.UTC,
            )
        val noLive =
            repository.addManualNoLive(
                ActivityEntrySource.Template(ActivityTemplateId("no-live")),
                instant(500),
                instant(1_000),
                ZoneId.of("America/New_York"),
            )
        assertNull(noLive.startedAt)
        assertNull(noLive.activeDuration)
        assertEquals(active.id, database.activeSessionDao().get()?.activityExecutionId)
        assertEquals(1_000_000L, database.activityTemplateDao().getUserState("no-live")?.lastUsedAtMs)
    }

    @Test
    fun crossMidnightOverlapAndNoLivePlanUseHistoricalEventFacts() {
        template("cross", TimeTrackingMode.STOPWATCH)
        template("cross-no-live", TimeTrackingMode.NO_LIVE_TRACKING, fields = true)
        val repository = repository("cross")
        val zone = ZoneId.of("Europe/Moscow")
        val start = Instant.parse("2026-08-20T20:50:00Z")
        val end = Instant.parse("2026-08-20T21:30:00Z")
        val timed =
            repository.addManualTimed(
                ActivityEntrySource.Template(ActivityTemplateId("cross")),
                start,
                end,
                Instant.parse("2026-08-22T00:00:00Z"),
                zone,
            )
        assertEquals(Duration.ofMinutes(40), timed.activeDuration)
        assertEquals(LocalDate.of(2026, 8, 20), timed.primaryLocalDate)
        assertTrue(repository.overlapsCompletedHistory(start.plusSeconds(60), end.minusSeconds(60)))
        assertFalse(repository.overlapsCompletedHistory(end.plusSeconds(1), end.plusSeconds(2)))

        val plans = planRepository()
        val noLivePlan =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("cross-no-live"),
                PlanTarget.FloatingDay(LocalDate.of(2026, 8, 21)),
                Instant.parse("2026-08-19T00:00:00Z"),
            )
        val noLive =
            repository.addManualNoLive(
                ActivityEntrySource.Plan(noLivePlan.id),
                Instant.parse("2026-08-21T12:00:00Z"),
                Instant.parse("2026-08-22T00:00:00Z"),
                zone,
            )
        assertNull(noLive.startedAt)
        assertNull(noLive.activeDuration)
        assertEquals(LocalDate.of(2026, 8, 21), noLive.primaryLocalDate)
        assertEquals(PlanEntryStatus.FULFILLED, plans.getPlan(noLivePlan.id)?.status)
        assertEquals(
            Instant.parse("2026-08-22T00:00:00Z").toEpochMilli(),
            database.activityTemplateDao().getUserState("cross-no-live")?.lastUsedAtMs,
        )

        repository.softDeleteHistory(timed.id, timed.updatedAt, Instant.parse("2026-08-23T00:00:00Z"))
        assertFalse(repository.overlapsCompletedHistory(start, end))

        val live =
            repository.startLive(
                ActivityEntrySource.Template(ActivityTemplateId("cross")),
                Instant.parse("2026-08-23T01:00:00Z"),
                Instant.parse("2026-08-23T01:01:00Z"),
                zone,
            )
        assertThrows(IllegalArgumentException::class.java) {
            repository.softDeleteHistory(live.id, live.updatedAt, Instant.parse("2026-08-23T02:00:00Z"))
        }

        LiveRuntimeTestFixtures(database).apply {
            seedSeries()
            activity("stopwatch", "STOPWATCH")
            sequence("sequence", listOf("stopwatch"))
            sequenceExecution()
        }
        val childSnapshot = requireNotNull(database.activitySnapshotDao().getAggregate("stopwatch")).toDomain()
        val child =
            ActivityExecutionTransitions.complete(
                com.alexandr5476.lifetracing.domain
                    .ActivityExecutionFactory {
                        ActivityExecutionId("completed-sequence-child")
                    }.startSequenceChildTimed(
                        childSnapshot,
                        SequenceExecutionId("sequence-execution"),
                        SequenceOccurrenceId("sequence-execution-occurrence"),
                        start,
                        start,
                        zone,
                    ),
                end,
            )
        database.activityExecutionDao().insertAggregate(child.toEntityAggregate())
        assertFalse(repository.overlapsCompletedHistory(start, end))
        assertThrows(IllegalArgumentException::class.java) {
            repository.softDeleteHistory(child.id, child.updatedAt, Instant.parse("2026-08-23T03:00:00Z"))
        }
    }

    @Test
    fun oneOffLiveTimedAndNoLiveRemainSourceLessAndUseSystemBucket() {
        val repository = repository("one-off")
        val manual =
            repository.addManualTimed(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.STOPWATCH)),
                instant(0),
                instant(60),
                instant(120),
                ZoneOffset.UTC,
                listOf(
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.OneOff("number"),
                        ActivityEntryValue.Number(0),
                    ),
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.OneOff("category"),
                        ActivityEntryValue.Category(ActivityEntryOptionReference.OneOff("option-b")),
                    ),
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.OneOff("text"),
                        ActivityEntryValue.Missing,
                    ),
                ),
            )
        val manualItem = requireNotNull(repository.getHistory(manual.id))
        assertEquals(ActivityExecutionStatistics.ONE_OFF_BUCKET_ID, manual.statisticsSeriesId)
        assertNull(manualItem.snapshot.sourceTemplateId)
        assertNull(manualItem.snapshot.sourceRevision)
        assertNull(manualItem.snapshot.statisticsSeriesId)
        assertEquals("One-off note", manualItem.snapshot.shortComment)
        assertTrue(manualItem.snapshot.fields.all { it.sourceFieldId == null })
        assertTrue(
            manualItem.snapshot.fields
                .flatMap { it.categoryOptions }
                .all { it.sourceOptionId == null },
        )
        assertTrue(manual.values.any { it is NumberExecutionValue && it.scaledValue == 0L })
        assertTrue(manual.values.any { it is CategoryExecutionValue })
        assertFalse(manual.values.any { it is TextExecutionValue })

        val noLive =
            repository.addManualNoLive(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.NO_LIVE_TRACKING)),
                instant(180),
                instant(240),
                ZoneOffset.UTC,
            )
        assertNull(noLive.startedAt)
        assertNull(noLive.activeDuration)
        assertEquals(ActivityExecutionStatistics.ONE_OFF_BUCKET_ID, noLive.statisticsSeriesId)

        val live =
            repository.startLive(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.STOPWATCH)),
                instant(300),
                instant(360),
                ZoneOffset.UTC,
            )
        assertEquals(ActivityExecutionStatus.RUNNING, live.status)
        assertEquals(live.id, database.activeSessionDao().get()?.activityExecutionId)
        assertEquals(0, count("activity_templates"))
        assertEquals(0, count("activity_template_user_state"))
        assertEquals(0, count("activity_template_tags"))
        assertEquals(0, count("folders"))
        assertEquals(0, count("tags"))
    }

    @Test
    fun planUsesFrozenSnapshotNoAutoMatchAndHistoryCorrectionDeleteStayExplicit() {
        template("planned", TimeTrackingMode.STOPWATCH, fields = true, shortComment = "old")
        val plans = planRepository()
        val explicit =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("planned"),
                PlanTarget.FloatingDay(LocalDate.of(2026, 8, 20)),
                instant(0),
            )
        val unmatched =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("planned"),
                PlanTarget.FloatingDay(LocalDate.of(2026, 8, 21)),
                instant(1),
            )
        val repository = repository("plan")
        repository.addManualTimed(
            ActivityEntrySource.Template(ActivityTemplateId("planned")),
            instant(10),
            instant(20),
            instant(30),
            ZoneOffset.UTC,
        )
        assertEquals(PlanEntryStatus.PLANNED, plans.getPlan(unmatched.id)?.status)

        val template = requireNotNull(database.activityTemplateDao().getById("planned"))
        database.activityTemplateDao().updateTemplate(
            template.copy(name = "changed", revision = 2, updatedAtMs = 2_000),
        )
        database.activityTemplateDao().archive("planned", 2_001)
        val planSnapshot =
            requireNotNull(
                database.activitySnapshotDao().getAggregate(requireNotNull(explicit.activitySnapshotId).value),
            ).toDomain()
        val execution =
            repository.addManualTimed(
                ActivityEntrySource.Plan(explicit.id),
                instant(100),
                instant(200),
                instant(300),
                ZoneOffset.UTC,
                listOf(
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Snapshot(planSnapshot.fields.first().id),
                        ActivityEntryValue.Number(0),
                    ),
                ),
            )
        assertEquals(planSnapshot.id, execution.snapshotId)
        assertEquals("planned", planSnapshot.name)
        assertEquals(PlanEntryStatus.FULFILLED, plans.getPlan(explicit.id)?.status)

        val noOp =
            ActivityHistoryCorrection(
                execution.updatedAt,
                ActivityHistoryTimeCorrection.Timed(
                    requireNotNull(execution.startedAt),
                    requireNotNull(execution.completedAt),
                ),
                execution.originalZoneId,
                execution.values,
                planSnapshot.shortComment,
            )
        val valuesBeforeNoOp = database.activityExecutionDao().getValues(execution.id.value)
        val snapshotsBeforeNoOp = count("activity_snapshots")
        val unchanged = repository.correctHistory(execution.id, noOp, instant(400))
        assertEquals(execution.updatedAt, unchanged.execution.updatedAt)
        assertEquals(planSnapshot.id, unchanged.snapshot.id)
        assertEquals(valuesBeforeNoOp, database.activityExecutionDao().getValues(execution.id.value))
        assertEquals(snapshotsBeforeNoOp, count("activity_snapshots"))

        val correction =
            noOp.copy(
                time = ActivityHistoryTimeCorrection.Timed(instant(90), instant(210)),
                shortComment = "corrected",
            )
        val corrected = repository.correctHistory(execution.id, correction, instant(400))
        assertNotEquals(planSnapshot.id, corrected.snapshot.id)
        assertEquals("corrected", corrected.snapshot.shortComment)
        assertNotNull(database.activitySnapshotDao().getById(planSnapshot.id.value))
        assertEquals(planSnapshot.id, plans.getPlan(explicit.id)?.activitySnapshotId)
        assertEquals(execution.values.size, corrected.execution.values.size)
        assertTrue(
            corrected.execution.values.none { value ->
                execution.values.any {
                    it.snapshotFieldId ==
                        value.snapshotFieldId
                }
            },
        )
        assertEquals(
            planSnapshot.fields.map { it.sourceFieldId },
            corrected.snapshot.fields.map { it.sourceFieldId },
        )
        assertEquals(
            planSnapshot.fields.flatMap { it.categoryOptions }.map { it.sourceOptionId },
            corrected.snapshot.fields
                .flatMap { it.categoryOptions }
                .map { it.sourceOptionId },
        )
        assertEquals(planSnapshot.settings, corrected.snapshot.settings)
        assertEquals(planSnapshot.locallyModified, corrected.snapshot.locallyModified)

        assertThrows(ConcurrentModificationException::class.java) {
            repository.correctHistory(execution.id, correction, instant(500))
        }
        assertThrows(ConcurrentModificationException::class.java) {
            repository.softDeleteHistory(execution.id, execution.updatedAt, instant(500))
        }
        assertEquals(corrected, repository.getHistory(execution.id))
        val deleted = repository.softDeleteHistory(execution.id, corrected.execution.updatedAt, instant(500))
        assertEquals(instant(500), deleted.deletedAt)
        assertEquals(PlanEntryStatus.FULFILLED, plans.getPlan(explicit.id)?.status)
        assertEquals(
            1,
            StatisticsRepository(database) { StatisticsSeriesId("unused") }
                .activitySeries(StatisticsSeriesId("planned-series"), StatisticsPeriod.AllTime)
                .executionCount,
        )
        assertFalse(repository.overlapsCompletedHistory(instant(90), instant(210), execution.id))
    }

    @Test
    fun fulfilledPlanCorrectionRejectsArbitrarySnapshotButAcceptsCommentOnlyReplacement() {
        template("plan-boundary", TimeTrackingMode.STOPWATCH)
        val plans = planRepository()
        val plan =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("plan-boundary"),
                PlanTarget.FloatingDay(LocalDate.of(2026, 8, 20)),
                instant(0),
            )
        val repository = repository("plan-boundary")
        val execution =
            repository.addManualTimed(
                ActivityEntrySource.Plan(plan.id),
                instant(10),
                instant(20),
                instant(30),
                ZoneOffset.UTC,
            )
        val frozen =
            requireNotNull(database.activitySnapshotDao().getAggregate(requireNotNull(plan.activitySnapshotId).value))
                .toDomain()
        val arbitrary =
            frozen.copy(
                id = ActivitySnapshotId("plan-boundary-arbitrary"),
                name = "materially different",
                createdAt = instant(40),
            )
        database.activitySnapshotDao().insertAggregate(arbitrary.toEntityAggregate())
        val executionBefore = requireNotNull(database.activityExecutionDao().getAggregate(execution.id.value))
        val planBefore = requireNotNull(database.planEntryDao().getById(plan.id.value))

        assertThrows(IllegalArgumentException::class.java) {
            database.activityExecutionDao().correctCompletedStandalone(
                execution.updatedAt.toEpochMilli(),
                frozen.id.value,
                executionBefore.copy(
                    execution =
                        executionBefore.execution.copy(
                            snapshotId = arbitrary.id.value,
                            updatedAtMs = instant(40).toEpochMilli(),
                        ),
                ),
            )
        }
        assertEquals(executionBefore, database.activityExecutionDao().getAggregate(execution.id.value))
        assertEquals(planBefore, database.planEntryDao().getById(plan.id.value))

        val corrected =
            repository.correctHistory(
                execution.id,
                ActivityHistoryCorrection(
                    execution.updatedAt,
                    ActivityHistoryTimeCorrection.Timed(instant(10), instant(20)),
                    ZoneOffset.UTC,
                    execution.values,
                    "corrected comment",
                ),
                instant(50),
            )
        assertNotEquals(frozen.id, corrected.snapshot.id)
        assertEquals("corrected comment", corrected.snapshot.shortComment)
        assertEquals(frozen.id, plans.getPlan(plan.id)?.activitySnapshotId)
        assertEquals(PlanEntryStatus.FULFILLED, plans.getPlan(plan.id)?.status)
    }

    @Test
    fun valueTimeAndNoLiveCorrectionsReplaceOnlyRequestedHistoricalFacts() {
        template("correction", TimeTrackingMode.STOPWATCH, fields = true, shortComment = "original")
        template("correction-no-live", TimeTrackingMode.NO_LIVE_TRACKING, fields = true)
        val repository = repository("correction")
        val original =
            repository.addManualTimed(
                ActivityEntrySource.Template(ActivityTemplateId("correction")),
                Instant.parse("2026-08-20T10:00:00Z"),
                Instant.parse("2026-08-20T10:14:00Z"),
                Instant.parse("2026-08-20T12:00:00Z"),
                ZoneOffset.UTC,
            )
        val item = requireNotNull(repository.getHistory(original.id))
        val number = item.snapshot.fields.single { it.sourceFieldId == ActivityTemplateFieldId("correction-number") }
        val category =
            item.snapshot.fields.single {
                it.sourceFieldId ==
                    ActivityTemplateFieldId(
                        "correction-category",
                    )
            }
        val optionB = category.categoryOptions.single { it.sourceOptionId == CategoryOptionId("correction-option-b") }
        val correctedValues =
            listOf(
                NumberExecutionValue(number.id, 0),
                CategoryExecutionValue(category.id, optionB.id),
            )
        val corrected =
            repository.correctHistory(
                original.id,
                ActivityHistoryCorrection(
                    original.updatedAt,
                    ActivityHistoryTimeCorrection.Timed(
                        Instant.parse("2026-08-21T03:50:00Z"),
                        Instant.parse("2026-08-21T04:30:00Z"),
                    ),
                    ZoneId.of("America/New_York"),
                    correctedValues,
                    "original",
                ),
                Instant.parse("2026-08-22T00:00:00Z"),
            )
        assertEquals(item.snapshot.id, corrected.snapshot.id)
        assertEquals(Duration.ofMinutes(40), corrected.execution.activeDuration)
        assertEquals(LocalDate.of(2026, 8, 20), corrected.execution.primaryLocalDate)
        assertEquals(-240, corrected.execution.originalUtcOffsetMinutes)
        assertEquals(correctedValues.sortedBy { it.snapshotFieldId.value }, corrected.execution.values)
        assertEquals(2, database.activityExecutionDao().getValues(original.id.value).size)
        assertFalse(corrected.execution.values.any { it is TextExecutionValue })
        assertEquals(
            0L,
            StatisticsRepository(database) { StatisticsSeriesId("unused") }
                .numberFieldStatistics(
                    StatisticsSeriesId("correction-series"),
                    StatisticsFieldId.Activity(ActivityTemplateFieldId("correction-number")),
                    StatisticsPeriod.AllTime,
                ).values.totalScaled
                .longValueExact(),
        )
        assertEquals(
            "B",
            StatisticsRepository(database) { StatisticsSeriesId("unused") }
                .categoryFieldStatistics(
                    StatisticsSeriesId("correction-series"),
                    StatisticsFieldId.Activity(ActivityTemplateFieldId("correction-category")),
                    StatisticsPeriod.AllTime,
                ).values
                .single { it.count == 1L }
                .displayLabel,
        )

        val beforeRejected = requireNotNull(repository.getHistory(original.id))
        assertThrows(IllegalArgumentException::class.java) {
            repository.correctHistory(
                original.id,
                ActivityHistoryCorrection(
                    corrected.execution.updatedAt,
                    ActivityHistoryTimeCorrection.Timed(
                        Instant.parse("2026-08-22T00:00:00Z"),
                        Instant.parse("2026-08-24T00:00:00Z"),
                    ),
                    ZoneOffset.UTC,
                    corrected.execution.values,
                    "original",
                ),
                Instant.parse("2026-08-23T00:00:00Z"),
            )
        }
        assertEquals(beforeRejected, repository.getHistory(original.id))

        val noLive =
            repository.addManualNoLive(
                ActivityEntrySource.Template(ActivityTemplateId("correction-no-live")),
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-20T01:00:00Z"),
                ZoneOffset.UTC,
                listOf(
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Template(ActivityTemplateFieldId("correction-no-live-number")),
                        ActivityEntryValue.Number(0),
                    ),
                    ActivityEntryValueOverride(
                        ActivityEntryFieldReference.Template(ActivityTemplateFieldId("correction-no-live-text")),
                        ActivityEntryValue.Missing,
                    ),
                ),
            )
        val noLiveCorrected =
            repository.correctHistory(
                noLive.id,
                ActivityHistoryCorrection(
                    noLive.updatedAt,
                    ActivityHistoryTimeCorrection.NoLive(Instant.parse("2026-08-21T12:00:00Z")),
                    ZoneId.of("Europe/Moscow"),
                    noLive.values,
                    null,
                ),
                Instant.parse("2026-08-22T00:00:00Z"),
            )
        assertNull(noLiveCorrected.execution.startedAt)
        assertNull(noLiveCorrected.execution.activeDuration)
        assertEquals(LocalDate.of(2026, 8, 21), noLiveCorrected.execution.primaryLocalDate)
        assertTrue(noLiveCorrected.execution.values.any { it is NumberExecutionValue && it.scaledValue == 0L })
        assertFalse(noLiveCorrected.execution.values.any { it is TextExecutionValue })
    }

    @Test
    fun pauseCorrectionPrunesUnsharedSnapshotAndLargeHistoryDoesNotChangeWorkingSet() {
        template("history", TimeTrackingMode.STOPWATCH)
        val repository = repository("history")
        val snapshot =
            ActivitySnapshotFactory(
                { ActivitySnapshotId("history-snapshot") },
                { ActivitySnapshotFieldId("unused-field") },
                { ActivitySnapshotCategoryOptionId("unused-option") },
            ).fromTemplate(
                requireNotNull(database.activityTemplateDao().getAggregate("history")).toDomain(),
                instant(0),
            )
        database.activitySnapshotDao().insertAggregate(snapshot.toEntityAggregate())
        val pause = ActivityExecutionPause(ActivityExecutionPauseId("pause"), instant(20), instant(25))
        val base =
            com.alexandr5476.lifetracing.domain
                .ActivityExecutionFactory { ActivityExecutionId("paused-history") }
                .createManualTimed(snapshot, instant(10), instant(40), instant(50), ZoneOffset.UTC)
        val paused =
            base.copy(
                pauses = listOf(pause),
                activeDuration = ActivityExecutionDurationCalculator.calculate(instant(10), instant(40), listOf(pause)),
            )
        database.activityExecutionDao().insertAggregate(paused.toEntityAggregate())
        val bulkSnapshot =
            ActivitySnapshotFactory(
                { ActivitySnapshotId("bulk-snapshot") },
                { ActivitySnapshotFieldId("bulk-unused-field") },
                { ActivitySnapshotCategoryOptionId("bulk-unused-option") },
            ).fromTemplate(
                requireNotNull(database.activityTemplateDao().getAggregate("history")).toDomain(),
                instant(0),
            )
        database.activitySnapshotDao().insertAggregate(bulkSnapshot.toEntityAggregate())
        insertLargeHistory(bulkSnapshot.id.value, "history-series", 2_000)

        val corrected =
            repository.correctHistory(
                paused.id,
                ActivityHistoryCorrection(
                    paused.updatedAt,
                    ActivityHistoryTimeCorrection.Timed(instant(5), instant(45)),
                    ZoneOffset.UTC,
                    emptyList(),
                    "changed",
                ),
                instant(60),
            )
        assertEquals(Duration.ofSeconds(35), corrected.execution.activeDuration)
        assertNull(database.activitySnapshotDao().getById("history-snapshot"))
        assertEquals(2_001, count("activity_executions"))

        val before = repository.getHistory(paused.id)
        assertThrows(IllegalArgumentException::class.java) {
            repository.correctHistory(
                paused.id,
                ActivityHistoryCorrection(
                    corrected.execution.updatedAt,
                    ActivityHistoryTimeCorrection.Timed(instant(21), instant(45)),
                    ZoneOffset.UTC,
                    emptyList(),
                    "changed",
                ),
                instant(70),
            )
        }
        assertEquals(before, repository.getHistory(paused.id))
    }

    @Test
    fun idCollisionsRollBackOneOffExecutionAndCommentReplacement() {
        LiveRuntimeTestFixtures(database).apply {
            seedSeries()
            activity("collision", "STOPWATCH")
        }
        val snapshotCollision = repository("snapshot-collision", nextSnapshotId = { ActivitySnapshotId("collision") })
        val snapshotRows = count("activity_snapshots")
        assertThrows(Exception::class.java) {
            snapshotCollision.addManualTimed(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.STOPWATCH)),
                instant(0),
                instant(1),
                instant(2),
                ZoneOffset.UTC,
            )
        }
        assertEquals(snapshotRows, count("activity_snapshots"))

        template("collision-template", TimeTrackingMode.STOPWATCH)
        val executionCollision =
            repository("execution-collision", nextExecutionId = { ActivityExecutionId("same-execution") })
        executionCollision.addManualTimed(
            ActivityEntrySource.Template(ActivityTemplateId("collision-template")),
            instant(10),
            instant(20),
            instant(30),
            ZoneOffset.UTC,
        )
        val snapshotsBefore = count("activity_snapshots")
        assertThrows(Exception::class.java) {
            executionCollision.addManualTimed(
                ActivityEntrySource.OneOff(oneOff(TimeTrackingMode.STOPWATCH)),
                instant(40),
                instant(50),
                instant(60),
                ZoneOffset.UTC,
            )
        }
        assertEquals(snapshotsBefore, count("activity_snapshots"))

        val existing = requireNotNull(executionCollision.getHistory(ActivityExecutionId("same-execution")))
        val replacementCollision = repository("replacement", nextSnapshotId = { existing.snapshot.id })
        assertThrows(Exception::class.java) {
            replacementCollision.correctHistory(
                existing.execution.id,
                ActivityHistoryCorrection(
                    existing.execution.updatedAt,
                    ActivityHistoryTimeCorrection.Timed(
                        requireNotNull(existing.execution.startedAt),
                        requireNotNull(existing.execution.completedAt),
                    ),
                    ZoneOffset.UTC,
                    existing.execution.values,
                    "new comment",
                ),
                instant(70),
            )
        }
        assertEquals(existing, executionCollision.getHistory(existing.execution.id))
        assertEquals(snapshotsBefore, count("activity_snapshots"))
    }

    private fun repository(
        prefix: String,
        nextSnapshotId: (() -> ActivitySnapshotId)? = null,
        nextExecutionId: (() -> ActivityExecutionId)? = null,
    ): ActivityCommandRepository {
        var id = 0

        fun next(kind: String) = "$prefix-$kind-${id++}"
        val live =
            LiveSessionRepository(
                database,
                { ActivityExecutionId(next("live-execution")) },
                { ActivityExecutionPauseId(next("pause")) },
                { SequenceExecutionId(next("sequence")) },
                { SequenceOccurrenceId(next("occurrence")) },
                { SequenceIntervalId(next("interval")) },
            )
        return ActivityCommandRepository(
            database,
            live,
            ActivitySnapshotFactory(
                nextSnapshotId ?: { ActivitySnapshotId(next("snapshot")) },
                { ActivitySnapshotFieldId(next("field")) },
                { ActivitySnapshotCategoryOptionId(next("option")) },
            ),
            nextExecutionId ?: { ActivityExecutionId(next("manual-execution")) },
        )
    }

    private fun planRepository(): PlanRepository {
        var id = 0

        fun next(kind: String) = "plan-$kind-${id++}"
        return PlanRepository(
            database,
            { PlanEntryId(next("entry")) },
            ActivitySnapshotFactory(
                { ActivitySnapshotId(next("activity-snapshot")) },
                { ActivitySnapshotFieldId(next("activity-field")) },
                { ActivitySnapshotCategoryOptionId(next("activity-option")) },
            ),
            SequenceSnapshotFactory(
                { SequenceSnapshotId(next("sequence-snapshot")) },
                { SequenceSnapshotFieldId(next("sequence-field")) },
                { SequenceSnapshotCategoryOptionId(next("sequence-option")) },
                { SequenceSnapshotNodeId(next("sequence-node")) },
            ),
            CurrentZoneIdProvider { ZoneOffset.UTC },
        )
    }

    private fun template(
        id: String,
        mode: TimeTrackingMode,
        targetMs: Long? = null,
        zeroBehavior: String = "FINISH",
        fields: Boolean = false,
        shortComment: String? = null,
    ) {
        val seriesId = "$id-series"
        database.statisticsSeriesDao().insert(StatisticsSeriesEntity(seriesId, "ACTIVITY", id, 0, null))
        val fieldRows =
            if (fields) {
                listOf(
                    ActivityTemplateFieldEntity(
                        "$id-number",
                        id,
                        0,
                        "Number",
                        "NUMBER",
                        null,
                        0,
                        5,
                        null,
                        null,
                        true,
                        0,
                        0,
                        null,
                    ),
                    ActivityTemplateFieldEntity(
                        "$id-text",
                        id,
                        1,
                        "Text",
                        "TEXT",
                        null,
                        null,
                        null,
                        null,
                        "default",
                        false,
                        0,
                        0,
                        null,
                    ),
                    ActivityTemplateFieldEntity(
                        "$id-category",
                        id,
                        2,
                        "Category",
                        "CATEGORY",
                        null,
                        null,
                        null,
                        "$id-option-a",
                        null,
                        false,
                        0,
                        0,
                        null,
                    ),
                )
            } else {
                emptyList()
            }
        val optionRows =
            if (fields) {
                listOf(
                    ActivityTemplateCategoryOptionEntity("$id-option-a", "$id-category", 0, "A"),
                    ActivityTemplateCategoryOptionEntity("$id-option-b", "$id-category", 1, "B"),
                )
            } else {
                emptyList()
            }
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(id, id, shortComment, mode.name, targetMs, seriesId, 1, 0, 0, null, null),
                ActivityTemplateSettingsEntity(id, timerZeroBehavior = zeroBehavior),
                fieldRows,
                optionRows,
                userState = ActivityTemplateUserStateEntity(id, null, null),
            ),
        )
    }

    private fun oneOff(mode: TimeTrackingMode) =
        ActivitySnapshotDraft(
            "One-off",
            "One-off note",
            mode,
            if (mode == TimeTrackingMode.TIMER) Duration.ofMinutes(10) else null,
            fields =
                listOf(
                    ActivitySnapshotFieldDraft(
                        DraftIdentity.New("number"),
                        null,
                        0,
                        "Number",
                        type = CustomFieldType.NUMBER,
                        defaultNumberScaled = 5,
                    ),
                    ActivitySnapshotFieldDraft(
                        DraftIdentity.New("category"),
                        null,
                        1,
                        "Category",
                        type = CustomFieldType.CATEGORY,
                        defaultCategoryOption = DraftIdentity.New("option-a"),
                        categoryOptions =
                            listOf(
                                ActivitySnapshotCategoryOptionDraft(
                                    DraftIdentity.New("option-a"),
                                    null,
                                    0,
                                    "A",
                                ),
                                ActivitySnapshotCategoryOptionDraft(
                                    DraftIdentity.New("option-b"),
                                    null,
                                    1,
                                    "B",
                                ),
                            ),
                    ),
                    ActivitySnapshotFieldDraft(
                        DraftIdentity.New("text"),
                        null,
                        2,
                        "Text",
                        type = CustomFieldType.TEXT,
                        defaultText = "default",
                    ),
                ),
        )

    private fun insertLargeHistory(
        snapshotId: String,
        seriesId: String,
        size: Int,
    ) {
        database.runInTransaction {
            repeat(size) { index ->
                val start = 100_000L + index * 2L
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO activity_executions " +
                        "(id, snapshot_id, context_type, sequence_execution_id, " +
                        "sequence_occurrence_id, plan_entry_id, " +
                        "statistics_series_id, status, started_at_ms, completed_at_ms, active_duration_ms, " +
                        "original_zone_id, original_utc_offset_minutes, primary_local_date, completion_reason, " +
                        "deleted_at_ms, created_at_ms, updated_at_ms) " +
                        "VALUES (?, ?, 'STANDALONE', NULL, NULL, NULL, ?, " +
                        "'COMPLETED', ?, ?, 1, 'UTC', 0, '1970-01-01', 'MANUAL_HISTORY_ENTRY', NULL, ?, ?)",
                    arrayOf<Any?>("bulk-$index", snapshotId, seriesId, start, start + 1, start + 1, start + 1),
                )
            }
        }
    }

    private fun count(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            check(it.moveToFirst())
            it.getInt(0)
        }

    private fun instant(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}
