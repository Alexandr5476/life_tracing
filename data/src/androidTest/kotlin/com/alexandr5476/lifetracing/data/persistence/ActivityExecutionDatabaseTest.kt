package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatistics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityExecutionDatabaseTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var executions: ActivityExecutionDao

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        executions = database.activityExecutionDao()
        database.statisticsSeriesDao().insert(StatisticsSeriesEntity("series", "ACTIVITY", "Series", 10, null))
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                snapshot =
                    ActivitySnapshotEntity(
                        "snapshot",
                        "Snapshot",
                        null,
                        "STOPWATCH",
                        null,
                        null,
                        null,
                        "series",
                        false,
                        10,
                    ),
                settings = ActivitySnapshotSettingsEntity("snapshot"),
                fields =
                    listOf(
                        field("number", "NUMBER", 0),
                        field("category", "CATEGORY", 1),
                        field("text", "TEXT", 2),
                    ),
                options = listOf(ActivitySnapshotCategoryOptionEntity("option", "category", null, 0, "Option", null)),
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun freshSchemaSeedsReservedOneOffSeriesIdempotently() {
        val reserved = database.statisticsSeriesDao().getById(ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value)

        assertNotNull(reserved)
        assertEquals("ONE_OFF_BUCKET", reserved?.kind)
        ActivityExecutionSchemaV4.createAndSeed(database.openHelper.writableDatabase)
        assertEquals(
            1,
            count(
                "statistics_series",
                "id = '${ActivityExecutionStatistics.ONE_OFF_BUCKET_ID.value}'",
            ),
        )
    }

    @Test
    fun aggregateRoundTripsWithOrderedPausesAndTypedValues() {
        val aggregate =
            ActivityExecutionAggregateEntity(
                execution = completed().copy(activeDurationMs = 70),
                pauses =
                    listOf(
                        ActivityExecutionPauseEntity("later", "execution", 40, 50),
                        ActivityExecutionPauseEntity("earlier", "execution", 20, 30),
                    ),
                values =
                    listOf(
                        ActivityExecutionFieldValueEntity("execution", "number", 0, null, null),
                        ActivityExecutionFieldValueEntity("execution", "category", null, "option", null),
                        ActivityExecutionFieldValueEntity("execution", "text", null, null, ""),
                    ),
            )

        executions.insertAggregate(aggregate)

        val loaded = executions.getAggregate("execution")
        assertEquals(aggregate.execution, loaded?.execution)
        assertEquals(listOf("earlier", "later"), loaded?.pauses?.map { it.id })
        assertEquals(listOf("category", "number", "text"), loaded?.values?.map { it.snapshotFieldId })
    }

    @Test
    fun stateContextPauseAndTypedValueChecksRejectInvalidRows() {
        listOf(
            completed("bad-running", status = "RUNNING"),
            completed("bad-status", status = "UNKNOWN"),
            completed("bad-context", context = "STANDALONE", sequenceExecutionId = "sequence"),
            completed("bad-child", context = "SEQUENCE_CHILD", sequenceExecutionId = "sequence"),
            completed("bad-reason", reason = "INVENTED"),
        ).forEach { invalid ->
            assertThrows(SQLiteConstraintException::class.java) { insertRawExecution(invalid) }
        }

        executions.insertAggregate(ActivityExecutionAggregateEntity(completed()))
        assertThrows(SQLiteConstraintException::class.java) {
            sql("INSERT INTO activity_execution_pauses VALUES ('pause', 'execution', 50, 40)")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sql("INSERT INTO activity_execution_field_values VALUES ('execution', 'number', NULL, NULL, NULL)")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sql("INSERT INTO activity_execution_field_values VALUES ('execution', 'number', 1, NULL, 'two')")
        }
    }

    @Test
    fun foreignKeysRestrictHistoricalOwnersAndCascadeExecutionChildren() {
        assertThrows(SQLiteConstraintException::class.java) {
            insertRawExecution(completed("missing-snapshot", snapshotId = "missing"))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            insertRawExecution(completed("missing-series", seriesId = "missing"))
        }
        executions.insertAggregate(
            ActivityExecutionAggregateEntity(
                completed().copy(activeDurationMs = 80),
                pauses = listOf(ActivityExecutionPauseEntity("pause", "execution", 20, 30)),
                values = listOf(ActivityExecutionFieldValueEntity("execution", "category", null, "option", null)),
            ),
        )

        assertThrows(SQLiteConstraintException::class.java) {
            database.activitySnapshotDao().hardDelete("snapshot")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sql("DELETE FROM statistics_series WHERE id = 'series'")
        }
        assertThrows(IllegalArgumentException::class.java) {
            executions.upsertValue(ActivityExecutionFieldValueEntity("execution", "category", null, "missing", null))
        }

        assertEquals(1, executions.hardDelete("execution"))
        assertTrue(executions.getPauses("execution").isEmpty())
        assertTrue(executions.getValues("execution").isEmpty())
    }

    @Test
    fun aggregateFailureRollsBackParentAndOccurrenceIsUniqueOnlyWhenPresent() {
        assertThrows(IllegalArgumentException::class.java) {
            executions.insertAggregate(
                ActivityExecutionAggregateEntity(
                    completed("failed"),
                    values = listOf(ActivityExecutionFieldValueEntity("failed", "missing", 1, null, null)),
                ),
            )
        }
        assertNull(executions.getById("failed"))

        insertRawExecution(
            completed(
                "child-1",
                context = "SEQUENCE_CHILD",
                sequenceExecutionId = "sequence",
                sequenceOccurrenceId = "occurrence",
                seriesId = null,
            ),
        )
        assertThrows(SQLiteConstraintException::class.java) {
            insertRawExecution(
                completed(
                    "child-2",
                    context = "SEQUENCE_CHILD",
                    sequenceExecutionId = "sequence",
                    sequenceOccurrenceId = "occurrence",
                    seriesId = null,
                ),
            )
        }
        insertRawExecution(completed("standalone-1"))
        insertRawExecution(completed("standalone-2"))
    }

    @Test
    fun valueWritesValidateSnapshotFieldTypeAndCategoryOwnershipAtomically() {
        insertOwnershipSnapshot(
            id = "owner-a",
            fields =
                listOf(
                    ownershipField("number-a", "owner-a", "NUMBER", 0),
                    ownershipField("category-a", "owner-a", "CATEGORY", 1),
                    ownershipField("category-other-a", "owner-a", "CATEGORY", 2),
                ),
            options =
                listOf(
                    ActivitySnapshotCategoryOptionEntity("option-a", "category-a", null, 0, "A", null),
                    ActivitySnapshotCategoryOptionEntity(
                        "option-other-a",
                        "category-other-a",
                        null,
                        0,
                        "Other A",
                        null,
                    ),
                ),
        )
        insertOwnershipSnapshot(
            id = "owner-b",
            fields = listOf(ownershipField("number-b", "owner-b", "NUMBER", 0)),
        )
        executions.insertAggregate(
            ActivityExecutionAggregateEntity(completed("owner-execution", snapshotId = "owner-a")),
        )

        executions.upsertValue(
            ActivityExecutionFieldValueEntity("owner-execution", "category-a", null, "option-a", null),
        )
        assertEquals("option-a", executions.getValues("owner-execution").single().categoryOptionId)
        assertThrows(IllegalArgumentException::class.java) {
            executions.upsertValue(
                ActivityExecutionFieldValueEntity("owner-execution", "number-b", 1, null, null),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            executions.upsertValue(
                ActivityExecutionFieldValueEntity("owner-execution", "number-a", null, null, "wrong type"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            executions.upsertValue(
                ActivityExecutionFieldValueEntity(
                    "owner-execution",
                    "category-a",
                    null,
                    "option-other-a",
                    null,
                ),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            executions.insertAggregate(
                ActivityExecutionAggregateEntity(
                    completed("invalid-owner-aggregate", snapshotId = "owner-a"),
                    values =
                        listOf(
                            ActivityExecutionFieldValueEntity(
                                "invalid-owner-aggregate",
                                "number-b",
                                1,
                                null,
                                null,
                            ),
                        ),
                ),
            )
        }
        assertNull(executions.getById("invalid-owner-aggregate"))
    }

    @Test
    fun aggregateInsertionEnforcesReferencedSnapshotMode() {
        insertSnapshot("mode-stopwatch", "STOPWATCH")
        insertSnapshot("mode-no-live", "NO_LIVE_TRACKING")

        assertThrows(IllegalArgumentException::class.java) {
            executions.insertAggregate(
                ActivityExecutionAggregateEntity(running("running-no-live", "mode-no-live")),
            )
        }
        assertNull(executions.getById("running-no-live"))
        assertThrows(IllegalArgumentException::class.java) {
            executions.insertAggregate(
                ActivityExecutionAggregateEntity(noLiveCompleted("immediate-stopwatch", "mode-stopwatch")),
            )
        }
        assertNull(executions.getById("immediate-stopwatch"))
        assertThrows(IllegalArgumentException::class.java) {
            executions.insertAggregate(
                ActivityExecutionAggregateEntity(
                    noLiveCompleted("paused-no-live", "mode-no-live"),
                    pauses = listOf(ActivityExecutionPauseEntity("no-live-pause", "paused-no-live", 20, 30)),
                ),
            )
        }
        assertNull(executions.getById("paused-no-live"))

        executions.insertAggregate(
            ActivityExecutionAggregateEntity(running("valid-running", "mode-stopwatch")),
        )
        executions.insertAggregate(
            ActivityExecutionAggregateEntity(noLiveCompleted("valid-no-live", "mode-no-live")),
        )
        assertEquals("RUNNING", executions.getById("valid-running")?.status)
        assertEquals("COMPLETED", executions.getById("valid-no-live")?.status)
    }

    @Test
    fun malformedPauseOwnershipRollsBackWithoutTouchingExistingExecution() {
        insertSnapshot("pause-owner-snapshot", "STOPWATCH")
        executions.insertAggregate(
            ActivityExecutionAggregateEntity(running("execution-b", "pause-owner-snapshot")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            executions.insertAggregate(
                ActivityExecutionAggregateEntity(
                    completed("execution-a", snapshotId = "pause-owner-snapshot").copy(activeDurationMs = 80),
                    pauses = listOf(ActivityExecutionPauseEntity("foreign-pause", "execution-b", 20, 30)),
                ),
            )
        }
        assertNull(executions.getById("execution-a"))
        assertTrue(executions.getPauses("execution-b").isEmpty())
    }

    @Test
    fun aggregateInsertionRejectsMalformedPauseTimelines() {
        insertSnapshot("timeline-snapshot", "STOPWATCH")
        val invalidAggregates =
            listOf(
                ActivityExecutionAggregateEntity(
                    running("running-open", "timeline-snapshot"),
                    pauses = listOf(ActivityExecutionPauseEntity("running-open-pause", "running-open", 20, null)),
                ),
                ActivityExecutionAggregateEntity(
                    running("paused-empty", "timeline-snapshot").copy(status = "PAUSED", updatedAtMs = 20),
                ),
                ActivityExecutionAggregateEntity(
                    running("paused-twice", "timeline-snapshot").copy(status = "PAUSED", updatedAtMs = 30),
                    pauses =
                        listOf(
                            ActivityExecutionPauseEntity("open-1", "paused-twice", 20, null),
                            ActivityExecutionPauseEntity("open-2", "paused-twice", 30, null),
                        ),
                ),
                ActivityExecutionAggregateEntity(
                    completed("completed-open", snapshotId = "timeline-snapshot"),
                    pauses = listOf(ActivityExecutionPauseEntity("completed-open-pause", "completed-open", 20, null)),
                ),
                ActivityExecutionAggregateEntity(
                    completed("wrong-duration", snapshotId = "timeline-snapshot").copy(activeDurationMs = 89),
                ),
                completedWithPauses(
                    "before-start",
                    ActivityExecutionPauseEntity("before-start-pause", "before-start", 5, 20),
                ),
                completedWithPauses("reversed", ActivityExecutionPauseEntity("reversed-pause", "reversed", 30, 20)),
                completedWithPauses(
                    "after-completion",
                    ActivityExecutionPauseEntity("late", "after-completion", 90, 110),
                ),
                completedWithPauses(
                    "overlap",
                    ActivityExecutionPauseEntity("overlap-1", "overlap", 20, 50),
                    ActivityExecutionPauseEntity("overlap-2", "overlap", 40, 60),
                ),
            )

        invalidAggregates.forEach { aggregate ->
            assertThrows(IllegalArgumentException::class.java) { executions.insertAggregate(aggregate) }
            assertNull(executions.getById(aggregate.execution.id))
        }
    }

    @Test
    fun liveTransitionsRejectCorruptRunningNoLiveRow() {
        insertSnapshot("corrupt-no-live", "NO_LIVE_TRACKING")
        sql(
            """
            INSERT INTO activity_executions (
                id, snapshot_id, context_type, statistics_series_id, status, started_at_ms,
                original_zone_id, original_utc_offset_minutes, primary_local_date, created_at_ms, updated_at_ms
            ) VALUES (
                'corrupt-running', 'corrupt-no-live', 'STANDALONE', 'series', 'RUNNING', 10,
                'UTC', 0, '2026-08-15', 10, 10
            )
            """.trimIndent(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            executions.pause("corrupt-running", "corrupt-pause", 20)
        }
        assertThrows(IllegalArgumentException::class.java) {
            executions.complete("corrupt-running", 20)
        }
        assertEquals("RUNNING", executions.getById("corrupt-running")?.status)
        assertTrue(executions.getPauses("corrupt-running").isEmpty())
    }

    @Test
    fun pauseResumeCompleteValueUpsertAndSoftDeleteAreAtomicFocusedOperations() {
        executions.insertAggregate(ActivityExecutionAggregateEntity(running()))

        executions.pause("execution", "pause", 20)
        assertEquals("PAUSED", executions.getById("execution")?.status)
        executions.resume("execution", 40)
        assertEquals("RUNNING", executions.getById("execution")?.status)
        executions.complete("execution", 100)
        assertEquals("COMPLETED", executions.getById("execution")?.status)
        assertEquals(70L, executions.getById("execution")?.activeDurationMs)
        assertNull(executions.getById("execution")?.completionReason)

        executions.upsertValue(ActivityExecutionFieldValueEntity("execution", "number", 1, null, null))
        executions.upsertValue(ActivityExecutionFieldValueEntity("execution", "number", 0, null, null))
        assertEquals(0L, executions.getValues("execution").single().numberScaled)

        assertEquals(1, executions.softDelete("execution", 110))
        assertEquals(110L, executions.getById("execution")?.deletedAtMs)
        assertEquals(1, executions.restore("execution", 120))
        assertNull(executions.getById("execution")?.deletedAtMs)
    }

    @Test
    fun freshSchemaContainsFrozenManualChecksAndPartialIndex() {
        val schema = database.openHelper.readableDatabase.readActivityExecutionManualSchema()

        assertTrue(schema.hasContextCheck)
        assertTrue(schema.hasStatusCheck)
        assertTrue(schema.hasTypedValueCheck)
        assertTrue(schema.occurrenceIndexIsUnique)
        assertEquals("sequence_occurrence_id is not null", schema.occurrenceIndexPredicate)
        assertEquals(
            listOf("id", "activity_execution_id", "started_at_ms", "ended_at_ms"),
            schema.pauseColumns,
        )
        assertEquals(
            listOf(
                "activity_execution_id",
                "snapshot_field_id",
                "number_scaled",
                "category_option_id",
                "text_value",
            ),
            schema.valueColumns,
        )
    }

    private fun completed(
        id: String = "execution",
        status: String = "COMPLETED",
        context: String = "STANDALONE",
        sequenceExecutionId: String? = null,
        sequenceOccurrenceId: String? = null,
        snapshotId: String = "snapshot",
        seriesId: String? = "series",
        reason: String? = null,
    ) = ActivityExecutionEntity(
        id,
        snapshotId,
        context,
        sequenceExecutionId,
        sequenceOccurrenceId,
        null,
        seriesId,
        status,
        10,
        100,
        90,
        "UTC",
        0,
        "2026-08-15",
        reason,
        null,
        10,
        100,
    )

    private fun running(
        id: String = "execution",
        snapshotId: String = "snapshot",
    ) = completed(id = id, status = "RUNNING", snapshotId = snapshotId)
        .copy(completedAtMs = null, activeDurationMs = null, updatedAtMs = 10)

    private fun noLiveCompleted(
        id: String,
        snapshotId: String,
    ) = completed(id = id, snapshotId = snapshotId).copy(startedAtMs = null, activeDurationMs = null)

    private fun completedWithPauses(
        id: String,
        vararg pauses: ActivityExecutionPauseEntity,
    ): ActivityExecutionAggregateEntity {
        val pausedMillis = pauses.sumOf { requireNotNull(it.endedAtMs) - it.startedAtMs }
        return ActivityExecutionAggregateEntity(
            completed(id, snapshotId = "timeline-snapshot").copy(activeDurationMs = 90 - pausedMillis),
            pauses = pauses.toList(),
        )
    }

    private fun field(
        id: String,
        type: String,
        position: Int,
    ) = ActivitySnapshotFieldEntity(id, "snapshot", null, position, id, null, type, null, null, null, null, null, false)

    private fun insertOwnershipSnapshot(
        id: String,
        fields: List<ActivitySnapshotFieldEntity>,
        options: List<ActivitySnapshotCategoryOptionEntity> = emptyList(),
    ) {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                snapshot = ActivitySnapshotEntity(id, id, null, "STOPWATCH", null, null, null, "series", false, 10),
                settings = ActivitySnapshotSettingsEntity(id),
                fields = fields,
                options = options,
            ),
        )
    }

    private fun insertSnapshot(
        id: String,
        mode: String,
    ) {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                snapshot = ActivitySnapshotEntity(id, id, null, mode, null, null, null, "series", false, 10),
                settings = ActivitySnapshotSettingsEntity(id),
            ),
        )
    }

    private fun ownershipField(
        id: String,
        snapshotId: String,
        type: String,
        position: Int,
    ) = ActivitySnapshotFieldEntity(
        id,
        snapshotId,
        null,
        position,
        id,
        null,
        type,
        null,
        null,
        null,
        null,
        null,
        false,
    )

    private fun sql(statement: String) = database.openHelper.writableDatabase.execSQL(statement)

    private fun insertRawExecution(execution: ActivityExecutionEntity) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO activity_executions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                execution.id,
                execution.snapshotId,
                execution.contextType,
                execution.sequenceExecutionId,
                execution.sequenceOccurrenceId,
                execution.planEntryId,
                execution.statisticsSeriesId,
                execution.status,
                execution.startedAtMs,
                execution.completedAtMs,
                execution.activeDurationMs,
                execution.originalZoneId,
                execution.originalUtcOffsetMinutes,
                execution.primaryLocalDate,
                execution.completionReason,
                execution.deletedAtMs,
                execution.createdAtMs,
                execution.updatedAtMs,
            ),
        )
    }

    private fun count(
        table: String,
        where: String,
    ): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table` WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
