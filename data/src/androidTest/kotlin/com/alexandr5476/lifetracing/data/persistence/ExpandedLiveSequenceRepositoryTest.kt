package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActiveSequenceState
import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CurrentZoneIdProvider
import com.alexandr5476.lifetracing.domain.DailyActive
import com.alexandr5476.lifetracing.domain.DailyQuery
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequence
import com.alexandr5476.lifetracing.domain.ExpandedLiveSequenceRead
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.NumberSequenceExecutionValue
import com.alexandr5476.lifetracing.domain.RuntimeDisplayBaseline
import com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.WallMonotonicAnchor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class ExpandedLiveSequenceRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var live: LiveSessionRepository
    private val observedSql = CopyOnWriteArrayList<String>()
    private var reloadOffset = 100

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> observedSql += sql }, Executor { it.run() })
                .build()
        seed()
        live = repository()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun exactExpandedReadHydratesFrozenRepresentativeGraphInBulk() {
        val started =
            live.startSequenceFromSnapshot(SequenceSnapshotId("expanded"), at(0), at(0), ZoneOffset.UTC)
        val firstId = requireNotNull(started.execution.currentOccurrenceId)
        val firstChildId = requireNotNull(started.currentChild).id.value
        database.activityExecutionDao().upsertValue(
            ActivityExecutionFieldValueEntity(firstChildId, "a-default", 0, null, null),
        )
        live.completeCurrentSequenceStep(firstId, at(1))
        live.startNextSequenceStep(at(2))
        live.reconcileActiveSession(at(9))
        val added =
            live
                .runtimeAdd(
                    ActivityEntrySource.Template(ActivityTemplateId("runtime-source")),
                    RuntimeInsertionPlacement.TO_END,
                    at(10),
                ).execution.occurrences
                .single { it.isRuntimeAdded }
        val mutableSource = requireNotNull(database.activityTemplateDao().getById("runtime-source"))
        database.activityTemplateDao().updateTemplate(
            mutableSource.copy(name = "Changed mutable template", revision = 2, updatedAtMs = 11_000),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_templates SET name = 'Changed source Activity', revision = 2 WHERE id = 'source-a'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_templates SET name = 'Changed source Sequence', revision = 2 " +
                "WHERE id = 'sequence-source'",
        )
        observedSql.clear()

        val expanded = active(live.getExpandedSequence(started.execution.id))

        assertEquals("Frozen expanded", expanded.runtime.snapshot.name)
        assertEquals(
            "Frozen a",
            expanded.occurrences
                .first()
                .activity.name,
        )
        assertEquals(ActiveSequenceState.RUNNING_CURRENT, expanded.state)
        assertEquals(listOf(0, 1, 2, 3), expanded.occurrences.map { it.occurrence.runtimePosition })
        assertEquals(
            setOf("a", "b", added.activitySnapshotId.value),
            expanded.runtime.activitySnapshots.keys
                .map { it.value }
                .toSet(),
        )
        assertEquals(
            "runtime-source",
            expanded.occurrences
                .last()
                .activity.name,
        )
        assertTrue(expanded.occurrences[1].occurrence.repeatIteration == 1)
        assertTrue(expanded.occurrences[2].occurrence.repeatIteration == 2)
        assertEquals(
            "expanded-repeat",
            expanded.occurrences[1]
                .occurrence.repeatSourceSnapshotNodeId
                ?.value,
        )
        assertTrue(
            expanded.occurrences
                .last()
                .occurrence.isRuntimeAdded,
        )
        assertNull(
            expanded.occurrences
                .last()
                .occurrence.sourceSequenceSnapshotNodeId,
        )
        assertNotNull(expanded.occurrences[0].childExecution)
        assertNotNull(expanded.occurrences[1].childExecution)
        assertNull(expanded.occurrences[2].childExecution)
        assertNull(expanded.occurrences[3].childExecution)
        assertEquals(
            0L,
            (
                expanded.occurrences[0]
                    .childExecution
                    ?.values
                    ?.single() as NumberExecutionValue
            ).scaledValue,
        )
        assertEquals(
            5L,
            expanded.occurrences[0]
                .activity.fields[0]
                .defaultNumberScaled,
        )
        assertNull(
            expanded.occurrences[0]
                .activity.fields[1]
                .defaultNumberScaled,
        )
        assertEquals(
            0L,
            (
                expanded.runtime.execution.values
                    .single() as NumberSequenceExecutionValue
            ).scaledValue,
        )
        assertEquals(Duration.ofSeconds(2), expanded.occurrences[0].effectiveSettings.startCountdown)
        assertEquals(Duration.ofSeconds(7), expanded.occurrences[1].effectiveSettings.startCountdown)
        assertEquals(Duration.ofSeconds(7), expanded.occurrences[2].effectiveSettings.startCountdown)
        assertEquals(Duration.ofSeconds(3), expanded.occurrences[3].effectiveSettings.startCountdown)
        assertSame(expanded.occurrences[1].childExecution, expanded.runtime.currentChild)
        assertNull(expanded.runtime.transitionCountdownTargetId)
        assertTrue(
            RuntimeDisplayBaseline.capture(expanded.runtime, WallMonotonicAnchor(at(10), 100), 100).matches(
                expanded.runtime,
            ),
        )

        val sql = observedSql.map(String::lowercase)
        assertEquals(
            1,
            sql.count { "from activity_executions" in it && "context_type = 'sequence_child'" in it },
        )
        assertEquals(1, sql.count { "from activity_execution_pauses where activity_execution_id in" in it })
        assertEquals(1, sql.count { "from activity_execution_field_values where activity_execution_id in" in it })
        assertFalse(sql.any { "from activity_templates" in it || "from sequence_templates" in it })
    }

    @Test
    fun expandedAndDailyShareAllFiveReloadedClassifications() {
        val first = live.startSequenceFromSnapshot(SequenceSnapshotId("expanded"), at(0), at(0), ZoneOffset.UTC)
        assertShared(first.execution.id, ActiveSequenceState.RUNNING_CURRENT, current = true, countdown = false)
        live.pauseActiveSequence(at(1))
        assertShared(first.execution.id, ActiveSequenceState.PAUSED_CURRENT, current = true, countdown = false)
        live.resumeActiveSequence(at(2))
        live.completeCurrentSequenceStep(requireNotNull(first.execution.currentOccurrenceId), at(3))
        assertShared(first.execution.id, ActiveSequenceState.WAITING_NEXT, current = false, countdown = false)
        live.endSequenceEarly(at(4))

        val second = live.startSequenceFromSnapshot(SequenceSnapshotId("countdown"), at(5), at(5), ZoneOffset.UTC)
        live.completeCurrentSequenceStep(requireNotNull(second.execution.currentOccurrenceId), at(6))
        assertShared(
            second.execution.id,
            ActiveSequenceState.RUNNING_TRANSITION_COUNTDOWN,
            current = false,
            countdown = true,
        )
        live.pauseActiveSequence(at(7))
        assertShared(
            second.execution.id,
            ActiveSequenceState.PAUSED_TRANSITION_COUNTDOWN,
            current = false,
            countdown = true,
        )
    }

    @Test
    fun exactRouteNeverRebindsAcrossInactiveAndDifferentLiveSlots() {
        val oldId = SequenceExecutionId("old-route")
        assertEquals(ExpandedLiveSequenceRead.StaleOrInactive, live.getExpandedSequence(oldId))

        live.startStandaloneTimedActivityFromSnapshot(
            ActivitySnapshotId("a"),
            at(0),
            at(0),
            ZoneOffset.UTC,
        )
        assertEquals(ExpandedLiveSequenceRead.StaleOrInactive, live.getExpandedSequence(oldId))
        live.completeActiveActivity(at(1))

        val first = live.startSequenceFromSnapshot(SequenceSnapshotId("expanded"), at(2), at(2), ZoneOffset.UTC)
        assertEquals(ExpandedLiveSequenceRead.StaleOrInactive, live.getExpandedSequence(oldId))
        live.endSequenceEarly(at(3))
        assertEquals(ExpandedLiveSequenceRead.StaleOrInactive, live.getExpandedSequence(first.execution.id))

        val later = live.startSequenceFromSnapshot(SequenceSnapshotId("countdown"), at(4), at(4), ZoneOffset.UTC)
        assertEquals(ExpandedLiveSequenceRead.StaleOrInactive, live.getExpandedSequence(first.execution.id))
        assertEquals(later.execution.id, active(live.getExpandedSequence(later.execution.id)).runtime.execution.id)
    }

    @Test
    fun persistedChildSnapshotMismatchIsRejectedAtTheExpandedBoundary() {
        val started =
            live.startSequenceFromSnapshot(SequenceSnapshotId("expanded"), at(0), at(0), ZoneOffset.UTC)
        val childId = requireNotNull(started.currentChild).id.value
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_executions SET snapshot_id = 'b' WHERE id = ?",
            arrayOf(childId),
        )

        assertThrows(IllegalArgumentException::class.java) {
            live.getExpandedSequence(started.execution.id)
        }
    }

    @Test
    fun persistedActiveStateContradictionIsRejectedByTheSharedClassifier() {
        val started =
            live.startSequenceFromSnapshot(SequenceSnapshotId("expanded"), at(0), at(0), ZoneOffset.UTC)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE active_session SET state = 'WAITING_NEXT' WHERE singleton_id = 1",
        )

        assertThrows(IllegalArgumentException::class.java) {
            live.getExpandedSequence(started.execution.id)
        }
    }

    @Test
    fun derivedSnapshotAndChildIdsUseSafeBindChunksAboveTheSqliteBoundary() {
        val executionId = seedLargeActiveGraph(901)
        observedSql.clear()

        val expanded = active(live.getExpandedSequence(executionId))

        assertEquals(901, expanded.occurrences.size)
        assertEquals(901, expanded.runtime.activitySnapshots.size)
        assertEquals(901, expanded.occurrences.count { it.childExecution != null })
        val sql = observedSql.map(String::lowercase)
        assertEquals(2, sql.count { it.startsWith("select * from activity_snapshots where id in") })
        assertEquals(
            1,
            sql.count { "from activity_executions" in it && "context_type = 'sequence_child'" in it },
        )
        assertEquals(2, sql.count { "from activity_execution_pauses where activity_execution_id in" in it })
        assertEquals(2, sql.count { "from activity_execution_field_values where activity_execution_id in" in it })
    }

    private fun assertShared(
        id: SequenceExecutionId,
        expected: ActiveSequenceState,
        current: Boolean,
        countdown: Boolean,
    ) {
        live = repository(reloadOffset)
        reloadOffset += 100
        val expanded = active(live.getExpandedSequence(id))
        val daily =
            DailyReadRepository(database, CurrentZoneIdProvider { ZoneOffset.UTC }, live)
                .getDaily(DailyQuery(LocalDate.ofEpochDay(0), at(8), 10))
                .active as DailyActive.Sequence
        assertEquals(expected, expanded.state)
        assertEquals(expected, daily.state)
        assertEquals(current, expanded.runtime.execution.currentOccurrenceId != null)
        assertEquals(countdown, expanded.runtime.transitionCountdownTargetId != null)
    }

    private fun active(result: ExpandedLiveSequenceRead): ExpandedLiveSequence =
        (result as ExpandedLiveSequenceRead.Active).value

    private fun seed() {
        val fixtures = LiveRuntimeTestFixtures(database)
        fixtures.seedSeries()
        activityTemplate("source-a")
        activityTemplate("source-b")
        runtimeTemplate()
        activity("a", defaultNumber = 5, sourceTemplateId = "source-a")
        activity("b", sourceTemplateId = "source-b")
        sequenceTemplate()
        sequence("expanded", autoAdvance = false, beforeEachMs = 3_000)
        sequence("countdown", autoAdvance = true, beforeEachMs = 10_000)
    }

    private fun activity(
        id: String,
        defaultNumber: Long? = null,
        sourceTemplateId: String? = null,
    ) {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    id,
                    "Frozen $id",
                    null,
                    "STOPWATCH",
                    null,
                    sourceTemplateId,
                    sourceTemplateId?.let { 1 },
                    "activity-series",
                    false,
                    0,
                ),
                ActivitySnapshotSettingsEntity(id),
                fields =
                    listOf(
                        ActivitySnapshotFieldEntity(
                            "$id-default",
                            id,
                            null,
                            0,
                            "Default",
                            null,
                            "NUMBER",
                            null,
                            0,
                            defaultNumber,
                            null,
                            null,
                            false,
                        ),
                        ActivitySnapshotFieldEntity(
                            "$id-missing",
                            id,
                            null,
                            1,
                            "Missing",
                            null,
                            "NUMBER",
                            null,
                            0,
                            null,
                            null,
                            null,
                            false,
                        ),
                    ),
            ),
        )
    }

    private fun sequence(
        id: String,
        autoAdvance: Boolean,
        beforeEachMs: Long,
    ) {
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity(
                    id,
                    "Frozen $id",
                    "Frozen note",
                    if (id == "expanded") "sequence-source" else null,
                    if (id == "expanded") 1 else null,
                    "sequence-series",
                    0,
                ),
                SequenceSnapshotSettingsEntity(
                    id,
                    autoAdvance,
                    5_000,
                    beforeEachMs,
                    true,
                    true,
                    false,
                    true,
                    true,
                    "ACTIVE",
                ),
                fields =
                    listOf(
                        SequenceSnapshotFieldEntity(
                            "$id-value",
                            id,
                            null,
                            0,
                            "Effort",
                            null,
                            "NUMBER",
                            null,
                            0,
                            0,
                            null,
                            null,
                            false,
                        ),
                    ),
                nodes =
                    listOf(
                        SequenceSnapshotNodeEntity("$id-step-a", id, "STEP", null, 0, "a", null),
                        SequenceSnapshotNodeEntity("$id-repeat", id, "REPEAT", null, 1, null, 2),
                        SequenceSnapshotNodeEntity("$id-step-b", id, "STEP", "$id-repeat", 0, "b", null),
                    ),
                stepOverrides =
                    listOf(
                        SequenceSnapshotStepOverrideEntity("$id-step-a", 2_000, null, null, null, null),
                        SequenceSnapshotStepOverrideEntity("$id-step-b", 7_000, null, null, null, null),
                    ),
            ),
        )
    }

    private fun runtimeTemplate() {
        activityTemplate("runtime-source")
    }

    private fun activityTemplate(id: String) {
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    id,
                    id,
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
                ActivityTemplateSettingsEntity(id),
                userState = ActivityTemplateUserStateEntity(id, null, null),
            ),
        )
    }

    private fun sequenceTemplate() {
        database.sequenceTemplateDao().insertAggregate(
            SequenceTemplateAggregateEntity(
                SequenceTemplateEntity(
                    "sequence-source",
                    "Mutable Sequence",
                    null,
                    "sequence-series",
                    1,
                    0,
                    0,
                    null,
                    null,
                ),
                SequenceTemplateSettingsEntity("sequence-source", autoAdvance = false),
                SequenceTemplateUserStateEntity("sequence-source", null, null),
                nodes =
                    listOf(
                        SequenceNodeEntity("mutable-a", "sequence-source", "STEP", null, 0, "a", null),
                        SequenceNodeEntity("mutable-repeat", "sequence-source", "REPEAT", null, 1, null, 2),
                        SequenceNodeEntity(
                            "mutable-b",
                            "sequence-source",
                            "STEP",
                            "mutable-repeat",
                            0,
                            "b",
                            null,
                        ),
                    ),
            ),
        )
    }

    @Suppress("LongMethod") // The large fixture makes each derived bind fan-out independently observable.
    private fun seedLargeActiveGraph(count: Int): SequenceExecutionId {
        val sql = database.openHelper.writableDatabase
        database.runInTransaction {
            val insertSnapshot =
                sql.compileStatement(
                    "INSERT INTO activity_snapshots " +
                        "(id, name, short_comment, time_tracking_mode, timer_target_ms, source_template_id, " +
                        "source_revision, statistics_series_id, locally_modified, created_at_ms) " +
                        "VALUES (?, ?, NULL, 'STOPWATCH', NULL, NULL, NULL, 'activity-series', 0, 0)",
                )
            val insertSettings =
                sql.compileStatement(
                    "INSERT INTO activity_snapshot_settings " +
                        "(snapshot_id, show_seconds, start_countdown_ms, timer_zero_behavior, timer_end_sound, " +
                        "timer_end_vibration, keep_screen_awake, confirm_manual_finish) " +
                        "VALUES (?, 1, 0, 'FINISH', 1, 1, 0, 0)",
                )
            repeat(count) { index ->
                val id = "large-activity-$index"
                insertSnapshot.clearBindings()
                insertSnapshot.bindString(1, id)
                insertSnapshot.bindString(2, id)
                insertSnapshot.executeInsert()
                insertSettings.clearBindings()
                insertSettings.bindString(1, id)
                insertSettings.executeInsert()
            }
        }
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity("large-sequence", "Large", null, null, null, "sequence-series", 0),
                SequenceSnapshotSettingsEntity(
                    "large-sequence",
                    false,
                    0,
                    0,
                    true,
                    true,
                    false,
                    true,
                    true,
                    "ACTIVE",
                ),
                nodes =
                    List(count) { index ->
                        SequenceSnapshotNodeEntity(
                            "large-step-$index",
                            "large-sequence",
                            "STEP",
                            null,
                            index,
                            "large-activity-$index",
                            null,
                        )
                    },
            ),
        )
        val executionId = SequenceExecutionId("large-execution")
        val currentIndex = count - 1
        database.sequenceExecutionDao().insertAggregate(
            SequenceExecutionAggregateEntity(
                SequenceExecutionEntity(
                    executionId.value,
                    "large-sequence",
                    null,
                    "sequence-series",
                    "RUNNING",
                    0,
                    null,
                    null,
                    null,
                    null,
                    "UTC",
                    0,
                    "1970-01-01",
                    "large-occurrence-$currentIndex",
                    0,
                    0,
                ),
                occurrences =
                    List(count) { index ->
                        SequenceOccurrenceEntity(
                            "large-occurrence-$index",
                            executionId.value,
                            "large-step-$index",
                            "large-activity-$index",
                            index,
                            null,
                            null,
                            if (index == currentIndex) "CURRENT" else "COMPLETED",
                            0,
                            if (index == currentIndex) null else 0,
                            if (index == currentIndex) null else "MANUAL_FINISH",
                        )
                    },
                intervals =
                    listOf(
                        SequenceIntervalEntity(
                            "large-interval",
                            executionId.value,
                            "ACTIVE_STEP",
                            0,
                            null,
                            "large-occurrence-$currentIndex",
                        ),
                    ),
            ),
        )
        database.runInTransaction {
            val insertChild =
                sql.compileStatement(
                    "INSERT INTO activity_executions " +
                        "(id, snapshot_id, context_type, sequence_execution_id, sequence_occurrence_id, " +
                        "plan_entry_id, statistics_series_id, status, started_at_ms, completed_at_ms, " +
                        "active_duration_ms, original_zone_id, original_utc_offset_minutes, primary_local_date, " +
                        "completion_reason, deleted_at_ms, created_at_ms, updated_at_ms) " +
                        "VALUES (?, ?, 'SEQUENCE_CHILD', 'large-execution', ?, NULL, 'activity-series', ?, 0, ?, ?, " +
                        "'UTC', 0, '1970-01-01', NULL, NULL, 0, 0)",
                )
            repeat(count) { index ->
                insertChild.clearBindings()
                insertChild.bindString(1, "large-child-$index")
                insertChild.bindString(2, "large-activity-$index")
                insertChild.bindString(3, "large-occurrence-$index")
                if (index == currentIndex) {
                    insertChild.bindString(4, "RUNNING")
                    insertChild.bindNull(5)
                    insertChild.bindNull(6)
                } else {
                    insertChild.bindString(4, "COMPLETED")
                    insertChild.bindLong(5, 0)
                    insertChild.bindLong(6, 0)
                }
                insertChild.executeInsert()
            }
        }
        database.activeSessionDao().insert(
            ActiveSession(
                ActiveSessionKind.SEQUENCE,
                ActiveSessionState.RUNNING,
                null,
                executionId,
                at(0),
            ),
        )
        return executionId
    }

    private fun repository(offset: Int = 0): LiveSessionRepository {
        var child = offset
        var pause = offset
        var sequence = offset
        var occurrence = offset
        var interval = offset
        var snapshot = offset
        var field = offset
        var option = offset
        return LiveSessionRepository(
            database,
            { ActivityExecutionId("child-${++child}") },
            { ActivityExecutionPauseId("pause-${++pause}") },
            { SequenceExecutionId("sequence-${++sequence}") },
            { SequenceOccurrenceId("occurrence-${++occurrence}") },
            { SequenceIntervalId("interval-${++interval}") },
            ActivitySnapshotFactory(
                { ActivitySnapshotId("runtime-snapshot-${++snapshot}") },
                { ActivitySnapshotFieldId("runtime-field-${++field}") },
                { ActivitySnapshotCategoryOptionId("runtime-option-${++option}") },
            ),
        )
    }

    private fun at(seconds: Long): Instant = Instant.EPOCH.plusSeconds(seconds)
}
