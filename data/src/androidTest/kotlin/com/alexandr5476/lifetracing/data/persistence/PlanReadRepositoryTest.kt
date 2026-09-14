package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CancelledPlanPageQuery
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.FocusedPlanAction
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanSourceState
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.WeekPlanQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.Collections
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class PlanReadRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var reads: PlanReadRepository
    private var zone: ZoneId = ZoneOffset.UTC
    private val observedSql = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .setQueryCallback({ sql, _ -> observedSql += sql }, Executor { it.run() })
                .allowMainThreadQueries()
                .build()
        LiveRuntimeTestFixtures(database).apply {
            seedSeries()
            activity("no-live", "NO_LIVE_TRACKING")
            activity("stopwatch", "STOPWATCH")
            activity("timer", "TIMER", 55 * 60_000L)
            sequence("sequence", listOf("stopwatch"))
        }
        reads = PlanReadRepository(database, CurrentZoneIdProvider { zone })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun weekSeparatesDayRowsFromTheMondayAnchoredWeekAndKeepsCancelledOut() {
        database.planEntryDao().insert(plan("floating", activity = "no-live", day = "2026-08-20"))
        database.planEntryDao().insert(plan("fulfilled", activity = "no-live", day = "2026-08-20"))
        val live = LiveSessionRepository.create(database)
        live.completeNoLiveActivityFromPlan(
            PlanEntryId("fulfilled"),
            Instant.parse("2026-08-20T08:00:00Z"),
            ZoneOffset.UTC,
        )
        database.planEntryDao().insert(
            plan("exact", activity = "timer", scheduledAt = Instant.parse("2026-08-20T09:00:00Z")),
        )
        database.planEntryDao().insert(plan("week", sequence = "sequence", precision = "WEEK", week = "2026-08-17"))
        database.planEntryDao().insert(
            plan("fulfilled-week", sequence = "sequence", precision = "WEEK", week = "2026-08-17"),
        )
        val started =
            live.startSequenceFromPlan(
                PlanEntryId("fulfilled-week"),
                Instant.parse("2026-08-20T10:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
                ZoneOffset.UTC,
            )
        live.completeCurrentSequenceStep(started.execution.currentOccurrenceId!!, Instant.parse("2026-08-20T11:00:00Z"))
        database.planEntryDao().insert(
            plan("cancelled", activity = "no-live", day = "2026-08-20", status = "CANCELLED", cancelledAt = 2),
        )

        val read = week("2026-08-17", "2026-08-20")

        assertEquals(setOf("floating", "fulfilled", "exact"), read.selectedDayPlans.map { it.plan.id.value }.toSet())
        assertEquals(
            PlanEntryStatus.FULFILLED,
            read.selectedDayPlans
                .single { it.plan.id.value == "fulfilled" }
                .plan.status,
        )
        assertEquals(setOf("week", "fulfilled-week"), read.weekPlans.map { it.plan.id.value }.toSet())
        assertEquals(
            PlanEntryStatus.FULFILLED,
            read.weekPlans
                .single { it.plan.id.value == "fulfilled-week" }
                .plan.status,
        )
        assertTrue(read.weekPlans.all { it.effectiveLocalDate == null && it.exactLocalTime == null })
        assertTrue((read.selectedDayPlans + read.weekPlans).map { it.plan.id }.distinct().size == 5)
        assertEquals(7, read.dayPresence.size)
        assertEquals(
            (0L..6L).map { LocalDate.parse("2026-08-17").plusDays(it) },
            read.dayPresence.map { it.date },
        )
        assertEquals(3, read.dayPresence.single { it.date == LocalDate.parse("2026-08-20") }.count)
        assertEquals(
            TimeTrackingMode.NO_LIVE_TRACKING,
            read.selectedDayPlans
                .single {
                    it.plan.id.value == "floating"
                }.activityMetadata
                ?.timeTrackingMode,
        )
        assertEquals(
            Duration.ofMinutes(55),
            read.selectedDayPlans
                .single {
                    it.plan.id.value == "exact"
                }.activityMetadata
                ?.timerTarget,
        )
    }

    @Test
    fun exactPlacementUsesTheCurrentZoneWithoutMutatingTheDurableTarget() {
        database.planEntryDao().insert(plan("floating", activity = "no-live", day = "2026-08-20"))
        database.planEntryDao().insert(plan("week", sequence = "sequence", precision = "WEEK", week = "2026-08-17"))
        database.planEntryDao().insert(
            plan("exact", activity = "stopwatch", scheduledAt = Instant.parse("2026-08-20T23:30:00Z")),
        )
        val before = requireNotNull(database.planEntryDao().getById("exact"))
        val floatingBefore = requireNotNull(database.planEntryDao().getById("floating"))
        val weekBefore = requireNotNull(database.planEntryDao().getById("week"))

        assertEquals(
            setOf("exact", "floating"),
            week("2026-08-17", "2026-08-20")
                .selectedDayPlans
                .map {
                    it.plan.id.value
                }.toSet(),
        )
        zone = ZoneOffset.ofHours(2)
        assertEquals(listOf("floating"), week("2026-08-17", "2026-08-20").selectedDayPlans.map { it.plan.id.value })
        assertEquals(listOf("exact"), week("2026-08-17", "2026-08-21").selectedDayPlans.map { it.plan.id.value })
        assertEquals(before, database.planEntryDao().getById("exact"))
        assertEquals(floatingBefore, database.planEntryDao().getById("floating"))
        assertEquals(weekBefore, database.planEntryDao().getById("week"))
        assertEquals(
            PlanTarget.Week(LocalDate.parse("2026-08-17")),
            week("2026-08-17", "2026-08-20")
                .weekPlans
                .single()
                .plan.target,
        )
    }

    @Test
    fun exactDensityUsesDstAwareCurrentZoneWeekBoundaries() {
        zone = ZoneId.of("America/New_York")
        database.planEntryDao().insert(
            plan("monday", activity = "no-live", scheduledAt = Instant.parse("2026-03-02T05:00:00Z")),
        )
        database.planEntryDao().insert(
            plan("sunday", activity = "no-live", scheduledAt = Instant.parse("2026-03-09T03:59:59.999Z")),
        )
        database.planEntryDao().insert(
            plan("before", activity = "no-live", scheduledAt = Instant.parse("2026-03-02T04:59:59.999Z")),
        )
        database.planEntryDao().insert(
            plan("after", activity = "no-live", scheduledAt = Instant.parse("2026-03-09T04:00:00Z")),
        )

        val read = week("2026-03-02", "2026-03-02")

        assertEquals(listOf("monday"), read.selectedDayPlans.map { it.plan.id.value })
        assertEquals(1, read.dayPresence.single { it.date == LocalDate.parse("2026-03-02") }.count)
        assertEquals(1, read.dayPresence.single { it.date == LocalDate.parse("2026-03-08") }.count)
        assertEquals(2, read.dayPresence.sumOf { it.count })
    }

    @Test
    fun weekDensityDoesNotHydrateNonSelectedSnapshotGraphs() {
        database.planEntryDao().insert(plan("other", sequence = "sequence", day = "2026-08-21"))
        observedSql.clear()

        val read = week("2026-08-17", "2026-08-20")
        val sql = synchronized(observedSql) { observedSql.map(String::lowercase) }

        assertEquals(1, read.dayPresence.single { it.date == LocalDate.parse("2026-08-21") }.count)
        assertFalse(
            sql.any {
                "activity_snapshots" in it ||
                    "sequence_snapshots" in it ||
                    "activity_snapshot_settings" in it ||
                    "activity_snapshot_fields" in it ||
                    "activity_snapshot_category_options" in it ||
                    "sequence_snapshot_nodes" in it ||
                    "sequence_snapshot_fields" in it ||
                    "sequence_snapshot_category_options" in it ||
                    "activity_templates" in it ||
                    "sequence_templates" in it ||
                    "activity_executions" in it ||
                    "sequence_executions" in it
            },
        )
    }

    @Test
    fun selectedRowsUseOnlyCompactSnapshotMetadata() {
        database.planEntryDao().insert(plan("activity", activity = "timer", day = "2026-08-20"))
        database.planEntryDao().insert(
            plan("sequence-plan", sequence = "sequence", precision = "WEEK", week = "2026-08-17"),
        )
        observedSql.clear()

        week("2026-08-17", "2026-08-20")
        val sql = synchronized(observedSql) { observedSql.map(String::lowercase) }

        assertFalse(
            sql.any {
                "activity_snapshot_settings" in it ||
                    "activity_snapshot_fields" in it ||
                    "activity_snapshot_category_options" in it
            },
        )
        assertFalse(
            sql.any {
                "sequence_snapshot_settings" in it ||
                    "sequence_snapshot_fields" in it ||
                    "sequence_snapshot_category_options" in it ||
                    "sequence_snapshot_nodes" in it ||
                    "sequence_snapshot_step_overrides" in it
            },
        )
    }

    @Test
    fun pastDayAndWeekPlansStayOverdueOnlyInTheirOriginalContext() {
        database.planEntryDao().insert(plan("past-day", activity = "no-live", day = "2026-08-12"))
        database.planEntryDao().insert(
            plan("past-week", sequence = "sequence", precision = "WEEK", week = "2026-08-10"),
        )

        val original =
            reads.getWeek(
                WeekPlanQuery(
                    LocalDate.parse("2026-08-10"),
                    LocalDate.parse("2026-08-12"),
                    Instant.parse("2026-08-21T12:00:00Z"),
                ),
            )

        assertTrue(original.selectedDayPlans.single().overdue)
        assertTrue(original.weekPlans.single().overdue)
        assertEquals(
            PlanTarget.FloatingDay(LocalDate.parse("2026-08-12")),
            original.selectedDayPlans
                .single()
                .plan.target,
        )
        assertEquals(
            PlanTarget.Week(LocalDate.parse("2026-08-10")),
            original.weekPlans
                .single()
                .plan.target,
        )
        val later = week("2026-08-17", "2026-08-20")
        assertTrue(later.selectedDayPlans.isEmpty())
        assertTrue(later.weekPlans.isEmpty())
    }

    @Test
    fun weekRowsProjectEverySourceStateFromFrozenMetadata() {
        insertActivitySource("current-source", 1, null)
        insertActivitySource("changed-source", 2, null)
        insertActivitySource("archived-source", 1, 2)
        insertActivitySnapshot("current-snapshot", "Current frozen", "current-source", 1)
        insertActivitySnapshot("changed-snapshot", "Changed frozen", "changed-source", 1)
        insertActivitySnapshot("archived-snapshot", "Archived frozen", "archived-source", 1)
        insertActivitySnapshot("unavailable-snapshot", "Unavailable frozen", null, 1)
        listOf(
            plan("current", activity = "current-snapshot", day = "2026-08-20", source = "current-source", revision = 1),
            plan("changed", activity = "changed-snapshot", day = "2026-08-20", source = "changed-source", revision = 1),
            plan(
                "archived",
                activity = "archived-snapshot",
                day = "2026-08-20",
                source = "archived-source",
                revision = 1,
            ),
            plan("unavailable", activity = "unavailable-snapshot", day = "2026-08-20", revision = 1),
        ).forEach(database.planEntryDao()::insert)

        val byId = week("2026-08-17", "2026-08-20").selectedDayPlans.associateBy { it.plan.id.value }

        assertEquals(PlanSourceState.CURRENT, byId.getValue("current").sourceState)
        assertEquals(PlanSourceState.CHANGED, byId.getValue("changed").sourceState)
        assertEquals(PlanSourceState.ARCHIVED, byId.getValue("archived").sourceState)
        assertEquals(PlanSourceState.UNAVAILABLE, byId.getValue("unavailable").sourceState)
        assertEquals("Changed frozen", byId.getValue("changed").title)
    }

    @Test
    fun engagementRequiresTheExplicitLinkedRootAndCanonicalActiveSession() {
        database.planEntryDao().insert(plan("engaged", activity = "stopwatch", day = "2026-08-20"))
        database.planEntryDao().insert(plan("unrelated", activity = "stopwatch", day = "2026-08-20"))
        val live = LiveSessionRepository.create(database)
        live.startActivityFromPlan(
            PlanEntryId("engaged"),
            Instant.parse("2026-08-20T10:00:00Z"),
            Instant.parse("2026-08-20T10:00:00Z"),
            ZoneOffset.UTC,
        )

        val rows = week("2026-08-17", "2026-08-20").selectedDayPlans.associateBy { it.plan.id.value }

        assertTrue(rows.getValue("engaged").engaged)
        assertFalse(rows.getValue("unrelated").engaged)
    }

    @Test
    fun focusedActionHydratesOnlyItsFrozenConfigurationAndPreservesActionIdentity() {
        database.planEntryDao().insert(plan("activity", activity = "no-live", day = "2026-08-20"))
        database.planEntryDao().insert(plan("sequence", sequence = "sequence", precision = "WEEK", week = "2026-08-17"))

        val activity = reads.getFocusedAction(PlanEntryId("activity"))
        val sequence = reads.getFocusedAction(PlanEntryId("sequence"))

        assertEquals(PlanEntryId("activity"), activity.identity.planEntryId)
        assertTrue(activity.snapshot is FocusedPlanAction.Snapshot.Activity)
        assertEquals(PlanEntryId("sequence"), sequence.identity.planEntryId)
        val sequenceSnapshot = sequence.snapshot as FocusedPlanAction.Snapshot.Sequence
        assertEquals(setOf(ActivitySnapshotId("stopwatch")), sequenceSnapshot.activitySnapshots.keys)
        assertEquals(PlanTarget.Week(LocalDate.parse("2026-08-17")), sequence.identity.target)
    }

    @Test
    fun focusedActivityReturnsCompleteFrozenDefaultsFieldsAndMainValue() {
        insertRichActivitySnapshot("rich")
        database.planEntryDao().insert(plan("rich-plan", activity = "rich", day = "2026-08-20"))

        val snapshot =
            (reads.getFocusedAction(PlanEntryId("rich-plan")).snapshot as FocusedPlanAction.Snapshot.Activity).value

        assertEquals(Duration.ofSeconds(3), snapshot.settings.startCountdown)
        assertEquals(3, snapshot.fields.size)
        assertEquals(0L, snapshot.fields.single { it.id.value == "rich-number" }.defaultNumberScaled)
        assertNull(snapshot.fields.single { it.id.value == "rich-missing" }.defaultNumberScaled)
        assertTrue(snapshot.fields.single { it.id.value == "rich-number" }.isMainValue)
        val category = snapshot.fields.single { it.id.value == "rich-category" }
        assertEquals("rich-option", category.defaultCategoryOptionId?.value)
        assertEquals(listOf("Zero"), category.categoryOptions.map { it.labelAtCreation })
    }

    @Test
    fun focusedSequenceLoadsMoreThanOneBindBatchAndExactlyIncludesRepeatChildren() {
        val activityIds = (0..900).map { "wide-child-$it" }
        insertActivitySource("child-source", 1, null)
        insertRichActivitySnapshot(activityIds.first(), "child-source", 1)
        activityIds.drop(1).forEach { id -> insertActivitySnapshot(id, "Frozen $id") }
        val repeatId = "wide-repeat"
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity("wide", "Wide frozen", null, null, null, "sequence-series", 0),
                sequenceSettings("wide"),
                nodes =
                    activityIds.dropLast(1).mapIndexed { index, id ->
                        SequenceSnapshotNodeEntity("wide-step-$index", "wide", "STEP", null, index, id, null)
                    } +
                        listOf(
                            SequenceSnapshotNodeEntity(repeatId, "wide", "REPEAT", null, 900, null, 3),
                            SequenceSnapshotNodeEntity(
                                "wide-repeat-step",
                                "wide",
                                "STEP",
                                repeatId,
                                0,
                                activityIds.last(),
                                null,
                            ),
                        ),
            ),
        )
        database.planEntryDao().insert(plan("wide-plan", sequence = "wide", precision = "WEEK", week = "2026-08-17"))

        val focused =
            reads.getFocusedAction(PlanEntryId("wide-plan")).snapshot as FocusedPlanAction.Snapshot.Sequence

        assertEquals(SequenceSnapshotId("wide"), focused.value.id)
        assertEquals(activityIds.map(::ActivitySnapshotId).toSet(), focused.activitySnapshots.keys)
        assertEquals(901, focused.activitySnapshots.size)
        assertEquals(
            "Frozen wide-child-900",
            focused.activitySnapshots.getValue(ActivitySnapshotId("wide-child-900")).name,
        )
        val richChild = focused.activitySnapshots.getValue(ActivitySnapshotId("wide-child-0"))
        assertEquals("child-source", richChild.sourceTemplateId?.value)
        assertEquals("activity-series", richChild.statisticsSeriesId?.value)
        assertEquals(0L, richChild.fields.single { it.isMainValue }.defaultNumberScaled)
    }

    @Test
    fun sameRawActivityAndSequenceSnapshotIdsNeverCrossSummaryNamespaces() {
        insertActivitySource("shared-source", 1, null)
        insertSequenceSource("shared-source", 2)
        insertActivitySnapshot("collision", "Activity title", "shared-source", 1)
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity(
                    "collision",
                    "Sequence title",
                    "Sequence note",
                    "shared-source",
                    1,
                    "sequence-series",
                    0,
                ),
                sequenceSettings("collision"),
                nodes =
                    listOf(
                        SequenceSnapshotNodeEntity("collision-step", "collision", "STEP", null, 0, "stopwatch", null),
                    ),
            ),
        )
        database.planEntryDao().insert(
            plan("activity-row", activity = "collision", day = "2026-08-20", source = "shared-source", revision = 1),
        )
        database.planEntryDao().insert(
            plan(
                "sequence-row",
                sequence = "collision",
                precision = "WEEK",
                week = "2026-08-17",
                source = "shared-source",
                revision = 1,
            ),
        )
        database.planEntryDao().insert(
            plan(
                "cancelled-activity",
                activity = "collision",
                day = "2026-08-20",
                status = "CANCELLED",
                cancelledAt = 4,
                source = "shared-source",
                revision = 1,
            ),
        )
        database.planEntryDao().insert(
            plan(
                "cancelled-sequence",
                sequence = "collision",
                precision = "WEEK",
                week = "2026-08-17",
                status = "CANCELLED",
                cancelledAt = 3,
                source = "shared-source",
                revision = 1,
            ),
        )

        val week = week("2026-08-17", "2026-08-20")
        val cancelled = reads.getCancelledPage(CancelledPlanPageQuery(0, 10)).items.associateBy { it.plan.id.value }

        assertEquals("Activity title", week.selectedDayPlans.single().title)
        assertEquals("Sequence title", week.weekPlans.single().title)
        assertEquals(PlanSourceState.CURRENT, week.selectedDayPlans.single().sourceState)
        assertEquals(PlanSourceState.CHANGED, week.weekPlans.single().sourceState)
        assertEquals("Activity title", cancelled.getValue("cancelled-activity").title)
        assertEquals("Sequence title", cancelled.getValue("cancelled-sequence").title)
        assertEquals(PlanSourceState.CURRENT, cancelled.getValue("cancelled-activity").sourceState)
        assertEquals(PlanSourceState.CHANGED, cancelled.getValue("cancelled-sequence").sourceState)
        assertEquals("Sequence note", cancelled.getValue("cancelled-sequence").shortComment)
        assertNull(cancelled.getValue("cancelled-activity").shortComment)
    }

    @Test
    fun cancelledPageIsBoundedOrderedAndLeavesMonthRowsUntouched() {
        database.planEntryDao().insert(
            plan("first", activity = "no-live", day = "2026-08-20", status = "CANCELLED", cancelledAt = 4),
        )
        database.planEntryDao().insert(
            plan(
                "second",
                sequence = "sequence",
                precision = "WEEK",
                week = "2026-08-17",
                status = "CANCELLED",
                cancelledAt = 3,
            ),
        )
        database.planEntryDao().insert(
            plan(
                "month",
                activity = "no-live",
                precision = "MONTH",
                month = "2026-08",
                status = "CANCELLED",
                cancelledAt = 5,
            ),
        )

        val first = reads.getCancelledPage(CancelledPlanPageQuery(0, 1))
        val second = reads.getCancelledPage(CancelledPlanPageQuery(1, 1))

        assertEquals(listOf("first"), first.items.map { it.plan.id.value })
        assertTrue(first.hasNextPage)
        assertEquals(listOf("second"), second.items.map { it.plan.id.value })
        assertFalse(second.hasNextPage)
        assertThrows(IllegalArgumentException::class.java) { CancelledPlanPageQuery(0, 101) }
        assertEquals("MONTH", requireNotNull(database.planEntryDao().getById("month")).precision)
    }

    private fun week(
        weekStart: String,
        selectedDate: String,
    ) = reads.getWeek(
        WeekPlanQuery(
            LocalDate.parse(weekStart),
            LocalDate.parse(selectedDate),
            Instant.parse("2026-08-21T12:00:00Z"),
        ),
    )

    private fun insertActivitySource(
        id: String,
        revision: Long,
        deletedAtMs: Long?,
    ) {
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    id,
                    id,
                    null,
                    "NO_LIVE_TRACKING",
                    null,
                    "activity-series",
                    revision,
                    0,
                    deletedAtMs ?: 0,
                    deletedAtMs,
                    null,
                ),
                ActivityTemplateSettingsEntity(id),
                userState = ActivityTemplateUserStateEntity(id, null, null),
            ),
        )
    }

    private fun sequenceSettings(id: String) =
        SequenceSnapshotSettingsEntity(id, true, 5_000, 0, true, true, false, true, true, "ACTIVE")

    private fun insertSequenceSource(
        id: String,
        revision: Long,
    ) {
        database.sequenceTemplateDao().insertAggregate(
            SequenceTemplateAggregateEntity(
                SequenceTemplateEntity(id, id, null, "sequence-series", revision, 0, 0, null, null),
                SequenceTemplateSettingsEntity(id),
                SequenceTemplateUserStateEntity(id, null, null),
            ),
        )
    }

    private fun insertActivitySnapshot(
        id: String,
        name: String = id,
        source: String? = null,
        revision: Long? = null,
    ) {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    id,
                    name,
                    null,
                    "NO_LIVE_TRACKING",
                    null,
                    source,
                    revision,
                    "activity-series",
                    false,
                    0,
                ),
                ActivitySnapshotSettingsEntity(id),
            ),
        )
    }

    private fun insertRichActivitySnapshot(
        id: String,
        source: String? = null,
        revision: Long? = null,
    ) {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    id,
                    "Rich frozen",
                    "Rich note",
                    "NO_LIVE_TRACKING",
                    null,
                    source,
                    revision,
                    "activity-series",
                    false,
                    0,
                ),
                ActivitySnapshotSettingsEntity(id, startCountdownMs = 3_000),
                fields =
                    listOf(
                        ActivitySnapshotFieldEntity(
                            id = "$id-number",
                            snapshotId = id,
                            sourceFieldId = null,
                            position = 0,
                            nameAtCreation = "Number",
                            localNameOverride = null,
                            fieldType = "NUMBER",
                            unit = "kg",
                            displayPrecision = 0,
                            defaultNumberScaled = 0,
                            defaultCategoryOptionId = null,
                            defaultText = null,
                            isMainValue = true,
                        ),
                        ActivitySnapshotFieldEntity(
                            id = "$id-category",
                            snapshotId = id,
                            sourceFieldId = null,
                            position = 1,
                            nameAtCreation = "Category",
                            localNameOverride = null,
                            fieldType = "CATEGORY",
                            unit = null,
                            displayPrecision = null,
                            defaultNumberScaled = null,
                            defaultCategoryOptionId = "$id-option",
                            defaultText = null,
                        ),
                        ActivitySnapshotFieldEntity(
                            id = "$id-missing",
                            snapshotId = id,
                            sourceFieldId = null,
                            position = 2,
                            nameAtCreation = "Missing number",
                            localNameOverride = null,
                            fieldType = "NUMBER",
                            unit = null,
                            displayPrecision = null,
                            defaultNumberScaled = null,
                            defaultCategoryOptionId = null,
                            defaultText = null,
                        ),
                    ),
                options =
                    listOf(
                        ActivitySnapshotCategoryOptionEntity(
                            "$id-option",
                            "$id-category",
                            null,
                            0,
                            "Zero",
                            null,
                        ),
                    ),
            ),
        )
    }

    private fun plan(
        id: String,
        activity: String? = null,
        sequence: String? = null,
        precision: String = "DAY",
        day: String? = null,
        week: String? = null,
        month: String? = null,
        scheduledAt: Instant? = null,
        status: String = "PLANNED",
        cancelledAt: Long? = null,
        source: String? = null,
        revision: Long? = null,
    ) = PlanEntryEntity(
        id,
        if (activity != null) "ACTIVITY" else "SEQUENCE",
        source.takeIf { activity != null },
        source.takeIf { sequence != null },
        revision,
        activity,
        sequence,
        precision,
        day.takeIf { scheduledAt == null },
        week,
        month,
        scheduledAt?.toEpochMilli(),
        scheduledAt?.let { "UTC" },
        status,
        null,
        null,
        0,
        cancelledAt ?: 0,
        cancelledAt,
        null,
    )
}
