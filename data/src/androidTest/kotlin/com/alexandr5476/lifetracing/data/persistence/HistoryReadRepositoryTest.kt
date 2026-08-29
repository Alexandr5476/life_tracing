package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class HistoryReadRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var repository: HistoryReadRepository

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        repository = HistoryReadRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun boundedRootsUsePersistedDateIncludeTerminalRootsAndExcludeNonRoots() {
        insertActivitySnapshot("activity-snapshot", "Frozen Activity", "Frozen note")
        insertSequenceSnapshot("sequence-snapshot", "Frozen Sequence", "Sequence note")
        insertActivity("activity-old", 100, "2026-08-20")
        insertActivity("activity-new", 300, "2026-08-20")
        insertActivity("activity-deleted", 400, "2026-08-20", deletedAtMs = 401)
        insertActivity("activity-running", 500, "2026-08-20", status = "RUNNING")
        insertSequence("sequence-completed", 250, "2026-08-20", "COMPLETED")
        insertSequence("sequence-early", 200, "2026-08-20", "ENDED_EARLY")
        insertSequence("sequence-running", 500, "2026-08-20", "RUNNING")
        insertSequenceChild("activity-child", "sequence-running", "child-occurrence", 550, "2026-08-20")
        insertActivity("other-persisted-date", 900, "2026-08-19")

        val roots = repository.getCompletedRoots(query("2026-08-20", "2026-08-20", 10))

        assertEquals(
            listOf("activity-new", "sequence-completed", "sequence-early", "activity-old"),
            roots.map {
                when (it) {
                    is CompletedActivityHistoryRoot -> it.executionId.value
                    is CompletedSequenceHistoryRoot -> it.executionId.value
                }
            },
        )
        assertEquals("Frozen Activity", (roots.first() as CompletedActivityHistoryRoot).title)
        assertEquals("Frozen Sequence", (roots[1] as CompletedSequenceHistoryRoot).title)
        assertEquals(
            listOf("activity-new", "sequence-completed"),
            repository.getCompletedRoots(query("2026-08-20", "2026-08-20", 2)).map {
                when (it) {
                    is CompletedActivityHistoryRoot -> it.executionId.value
                    is CompletedSequenceHistoryRoot -> it.executionId.value
                }
            },
        )
        assertTrue(
            repository.getCompletedRoots(query("2026-08-19", "2026-08-19", 10)).any {
                it is CompletedActivityHistoryRoot && it.executionId.value == "other-persisted-date"
            },
        )
    }

    @Test
    fun activityDetailUsesEffectiveLabelsButKeepsSnapshotConfigurationAndNoLiveMissingDuration() {
        database.statisticsSeriesDao().insert(StatisticsSeriesEntity("series", "ACTIVITY", "Source", 0, null))
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    "template",
                    "Source",
                    null,
                    "NO_LIVE_TRACKING",
                    null,
                    "series",
                    1,
                    0,
                    0,
                    null,
                    null,
                ),
                ActivityTemplateSettingsEntity("template"),
                listOf(
                    ActivityTemplateFieldEntity(
                        "number-source",
                        "template",
                        0,
                        "Creation number",
                        "NUMBER",
                        null,
                        0,
                        7,
                        null,
                        null,
                        false,
                        0,
                        0,
                        null,
                    ),
                    ActivityTemplateFieldEntity(
                        "category-source",
                        "template",
                        1,
                        "Creation category",
                        "CATEGORY",
                        null,
                        null,
                        null,
                        "option-source",
                        null,
                        false,
                        0,
                        0,
                        null,
                    ),
                    ActivityTemplateFieldEntity(
                        "text-source",
                        "template",
                        2,
                        "Creation text",
                        "TEXT",
                        null,
                        null,
                        null,
                        null,
                        "configured",
                        false,
                        0,
                        0,
                        null,
                    ),
                ),
                listOf(ActivityTemplateCategoryOptionEntity("option-source", "category-source", 0, "Creation option")),
                userState = ActivityTemplateUserStateEntity("template", null, 123),
            ),
        )
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    "detail-snapshot",
                    "Frozen Activity",
                    "Frozen note",
                    "NO_LIVE_TRACKING",
                    null,
                    "template",
                    1,
                    "series",
                    false,
                    1,
                ),
                ActivitySnapshotSettingsEntity("detail-snapshot"),
                listOf(
                    ActivitySnapshotFieldEntity(
                        "number-snapshot",
                        "detail-snapshot",
                        "number-source",
                        0,
                        "Creation number",
                        null,
                        "NUMBER",
                        null,
                        0,
                        7,
                        null,
                        null,
                        false,
                    ),
                    ActivitySnapshotFieldEntity(
                        "category-snapshot",
                        "detail-snapshot",
                        "category-source",
                        1,
                        "Creation category",
                        null,
                        "CATEGORY",
                        null,
                        null,
                        null,
                        "option-snapshot",
                        null,
                        false,
                    ),
                    ActivitySnapshotFieldEntity(
                        "text-snapshot",
                        "detail-snapshot",
                        "text-source",
                        2,
                        "Creation text",
                        null,
                        "TEXT",
                        null,
                        null,
                        null,
                        null,
                        "configured",
                        false,
                    ),
                ),
                listOf(
                    ActivitySnapshotCategoryOptionEntity(
                        "option-snapshot",
                        "category-snapshot",
                        "option-source",
                        0,
                        "Creation option",
                        null,
                    ),
                ),
            ),
        )
        insertActivity("detail", 600, "2026-08-20", snapshotId = "detail-snapshot")
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO activity_execution_field_values (activity_execution_id, snapshot_field_id, number_scaled, category_option_id, text_value) VALUES (?, ?, 0, NULL, NULL)",
            arrayOf<Any?>("detail", "number-snapshot"),
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO activity_execution_field_values (activity_execution_id, snapshot_field_id, number_scaled, category_option_id, text_value) VALUES (?, ?, NULL, ?, NULL)",
            arrayOf<Any?>("detail", "category-snapshot", "option-snapshot"),
        )
        database.activityTemplateDao().updateFieldDisplayName("number-source", "Current number", 2)
        database.activityTemplateDao().updateOptionDisplayLabel("option-source", "Current option")

        val detail = requireNotNull(repository.getActivityDetail(ActivityExecutionId("detail")))

        assertEquals("Frozen Activity", detail.root.title)
        assertNull(detail.root.activeDuration)
        assertEquals("Current number", detail.fields[0].name)
        assertEquals(ActivityHistoryActualValue.Number(0), detail.fields[0].actualValue)
        assertEquals("Current option", (detail.fields[1].actualValue as ActivityHistoryActualValue.Category).label)
        assertEquals(ActivityHistoryActualValue.Missing, detail.fields[2].actualValue)
        database.activityTemplateDao().archive("template", 3)

        val archived = requireNotNull(repository.getActivityDetail(ActivityExecutionId("detail")))
        assertEquals("Creation number", archived.fields[0].name)
        assertEquals("Creation option", (archived.fields[1].actualValue as ActivityHistoryActualValue.Category).label)
        assertEquals(123L, database.activityTemplateDao().getUserState("template")?.lastUsedAtMs)
        assertEquals(1L, database.activityTemplateDao().getById("template")?.revision)
    }

    private fun query(
        start: String,
        end: String,
        limit: Int,
    ) = CompletedHistoryQuery(HistoryDateRange(LocalDate.parse(start), LocalDate.parse(end)), limit)

    private fun insertActivitySnapshot(
        id: String,
        name: String,
        shortComment: String?,
    ) {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(id, name, shortComment, "NO_LIVE_TRACKING", null, null, null, null, false, 0),
                ActivitySnapshotSettingsEntity(id),
            ),
        )
    }

    private fun insertSequenceSnapshot(
        id: String,
        name: String,
        shortComment: String?,
    ) {
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity(id, name, shortComment, null, null, null, 0),
                SequenceSnapshotSettingsEntity(id, false, 0, 0, true, true, false, false, false, "ACTIVE"),
            ),
        )
    }

    private fun insertActivity(
        id: String,
        completedAtMs: Long,
        primaryDate: String,
        status: String = "COMPLETED",
        deletedAtMs: Long? = null,
        snapshotId: String = "activity-snapshot",
    ) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO activity_executions (id, snapshot_id, context_type, sequence_execution_id, sequence_occurrence_id, plan_entry_id, statistics_series_id, status, started_at_ms, completed_at_ms, active_duration_ms, original_zone_id, original_utc_offset_minutes, primary_local_date, completion_reason, deleted_at_ms, created_at_ms, updated_at_ms) VALUES (?, ?, 'STANDALONE', NULL, NULL, NULL, NULL, ?, NULL, ?, NULL, 'UTC', 0, ?, 'MANUAL_HISTORY_ENTRY', ?, ?, ?)",
            arrayOf<Any?>(
                id,
                snapshotId,
                status,
                completedAtMs,
                primaryDate,
                deletedAtMs,
                completedAtMs,
                completedAtMs,
            ),
        )
    }

    private fun insertSequence(
        id: String,
        endedAtMs: Long,
        primaryDate: String,
        status: String,
    ) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sequence_executions (id, snapshot_id, plan_entry_id, statistics_series_id, status, started_at_ms, ended_at_ms, active_duration_ms, pause_duration_ms, wall_duration_ms, original_zone_id, original_utc_offset_minutes, primary_local_date, current_occurrence_id, created_at_ms, updated_at_ms) VALUES (?, 'sequence-snapshot', NULL, NULL, ?, ?, ?, NULL, NULL, NULL, 'UTC', 0, ?, NULL, ?, ?)",
            arrayOf<Any?>(id, status, endedAtMs - 1, endedAtMs, primaryDate, endedAtMs, endedAtMs),
        )
    }

    private fun insertSequenceChild(
        id: String,
        sequenceId: String,
        occurrenceId: String,
        completedAtMs: Long,
        primaryDate: String,
    ) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sequence_occurrences (id, sequence_execution_id, source_sequence_snapshot_node_id, activity_snapshot_id, runtime_position, repeat_source_snapshot_node_id, repeat_iteration, status, entered_at_ms, completed_at_ms, completion_reason, is_runtime_added, is_deleted_from_history) VALUES (?, ?, NULL, 'activity-snapshot', 0, NULL, NULL, 'COMPLETED', ?, ?, NULL, 1, 0)",
            arrayOf<Any?>(occurrenceId, sequenceId, completedAtMs - 1, completedAtMs),
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO activity_executions (id, snapshot_id, context_type, sequence_execution_id, sequence_occurrence_id, plan_entry_id, statistics_series_id, status, started_at_ms, completed_at_ms, active_duration_ms, original_zone_id, original_utc_offset_minutes, primary_local_date, completion_reason, deleted_at_ms, created_at_ms, updated_at_ms) VALUES (?, 'activity-snapshot', 'SEQUENCE_CHILD', ?, ?, NULL, NULL, 'COMPLETED', NULL, ?, NULL, 'UTC', 0, ?, NULL, NULL, ?, ?)",
            arrayOf<Any?>(id, sequenceId, occurrenceId, completedAtMs, primaryDate, completedAtMs, completedAtMs),
        )
    }
}
