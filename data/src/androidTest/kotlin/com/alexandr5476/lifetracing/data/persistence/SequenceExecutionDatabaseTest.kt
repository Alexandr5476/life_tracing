package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SequenceExecutionDatabaseTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var executions: SequenceExecutionDao

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        executions = database.sequenceExecutionDao()
        database.statisticsSeriesDao().insert(
            StatisticsSeriesEntity("activity-series", "ACTIVITY", "Activity", 0, null),
        )
        database.statisticsSeriesDao().insert(
            StatisticsSeriesEntity("sequence-series", "SEQUENCE", "Sequence", 0, null),
        )
        insertActivitySnapshot("activity", "STOPWATCH", "activity-series", withFields = true)
        insertActivitySnapshot("one-off", "STOPWATCH", null)
        insertActivitySnapshot("no-live", "NO_LIVE_TRACKING", null)
        insertSequenceSnapshot("sequence-snapshot")
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun completeAggregateRoundTripsOrderedRowsTypedValuesAndOverlapUnionCaches() {
        val aggregate =
            completedAggregate().copy(
                intervals =
                    listOf(
                        interval("idle", "IMPLICIT_IDLE", 60, 100),
                        interval("active-late", "ACTIVE_STEP", 10, 60, "top"),
                        interval("active-early", "ACTIVE_STEP", 0, 20, "top"),
                        interval("step-pause", "STEP_PAUSE", 30, 40, "top"),
                        interval("explicit", "EXPLICIT_PAUSE", 60, 70),
                        interval("transition", "TRANSITION_COUNTDOWN", 70, 80),
                    ),
                values =
                    listOf(
                        SequenceExecutionFieldValueEntity("execution", "seq-number", 0, null, null),
                        SequenceExecutionFieldValueEntity("execution", "seq-category", null, "seq-option", null),
                        SequenceExecutionFieldValueEntity("execution", "seq-text", null, null, ""),
                    ),
            )

        executions.insertAggregate(aggregate)

        val loaded = requireNotNull(executions.getAggregate("execution"))
        assertEquals(listOf("top", "repeat-occurrence"), loaded.occurrences.map { it.id })
        assertEquals(
            listOf(
                "active-early",
                "active-late",
                "step-pause",
                "explicit",
                "idle",
                "transition",
            ),
            loaded.intervals.map {
                it.id
            },
        )
        assertEquals(listOf("seq-category", "seq-number", "seq-text"), loaded.values.map { it.snapshotFieldId })
        assertEquals(60L, loaded.execution.activeDurationMs)
        assertEquals(40L, loaded.execution.pauseDurationMs)
        assertEquals(100L, loaded.execution.wallDurationMs)
        assertEquals("ADVANCED_TO_NEXT", loaded.occurrences.first().completionReason)
    }

    @Test
    fun rootForeignKeysChecksCurrentPointerAndTransactionRollbackAreEnforced() {
        assertThrows(SQLiteConstraintException::class.java) {
            insertRawRoot(root("missing-snapshot").copy(snapshotId = "missing"))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            insertRawRoot(root("missing-series").copy(statisticsSeriesId = "missing"))
        }
        listOf(
            root().copy(status = "UNKNOWN"),
            root().copy(activeDurationMs = null),
            runningRoot().copy(activeDurationMs = 0),
            root().copy(endedAtMs = -1),
            root().copy(currentOccurrenceId = "top"),
        ).forEachIndexed { index, invalid ->
            assertThrows(RuntimeException::class.java) {
                insertRawRoot(invalid.copy(id = "invalid-$index"))
            }
        }

        val live =
            SequenceExecutionAggregateEntity(
                runningRoot().copy(currentOccurrenceId = "current"),
                occurrences = listOf(occurrence("current", 0, "CURRENT", enteredAt = 0)),
                intervals = listOf(interval("open", "ACTIVE_STEP", 0, null, "current")),
            )
        executions.insertAggregate(live)
        assertEquals("current", executions.getById("execution")?.currentOccurrenceId)

        assertThrows(SQLiteConstraintException::class.java) {
            executions.insertAggregate(
                completedAggregate("rollback").copy(
                    intervals =
                        listOf(
                            interval("open", "ACTIVE_STEP", 0, 60, "rollback-top")
                                .copy(sequenceExecutionId = "rollback"),
                        ),
                ),
            )
        }
        assertNull(executions.getById("rollback"))

        assertThrows(SQLiteConstraintException::class.java) {
            sql("DELETE FROM sequence_snapshots WHERE id = 'sequence-snapshot'")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sql("DELETE FROM statistics_series WHERE id = 'sequence-series'")
        }
    }

    @Test
    fun occurrencePartialIndexRowChecksOrderingAndForeignKeysAreReal() {
        executions.insertAggregate(SequenceExecutionAggregateEntity(runningRoot()))
        insertRawOccurrence("not-started-1", 0, "NOT_STARTED")
        insertRawOccurrence("not-started-2", 1, "NOT_STARTED")
        insertRawOccurrence("completed-1", 2, "COMPLETED", 1, 2, "MANUAL_FINISH")
        insertRawOccurrence("completed-2", 3, "COMPLETED", 1, 2, null)
        insertRawOccurrence("current", 4, "CURRENT", 1)

        assertThrows(SQLiteConstraintException::class.java) {
            insertRawOccurrence("second-current", 5, "CURRENT", 1)
        }
        listOf(
            { insertRawOccurrence("negative", -1, "NOT_STARTED") },
            { insertRawOccurrence("unknown", 6, "UNKNOWN") },
            { insertRawOccurrence("skipped-reason", 7, "SKIPPED", reason = "JUMP") },
            { insertRawOccurrence("zero-repeat", 8, "NOT_STARTED", repeatIteration = 0) },
            { insertRawOccurrence("unknown-reason", 9, "COMPLETED", 1, 2, "UNKNOWN") },
            { insertRawOccurrence("missing-execution", 10, "NOT_STARTED", executionId = "missing") },
            { insertRawOccurrence("missing-activity", 11, "NOT_STARTED", activityId = "missing") },
            { insertRawOccurrence("duplicate-position", 0, "NOT_STARTED") },
        ).forEach { invalid -> assertThrows(SQLiteConstraintException::class.java) { invalid() } }

        val schema = database.openHelper.readableDatabase.readSequenceExecutionManualSchema()
        assertTrue(schema.currentIndexUnique)
        assertEquals("status = 'current'", schema.currentIndexPredicate)
        assertTrue(schema.occurrencePositionIndexUnique)
    }

    @Test
    fun normalBoundaryRejectsWrongSourceRepeatIntervalAndValueOwnership() {
        insertSequenceSnapshot("other-sequence", stepId = "other-step")
        val invalidOccurrences =
            listOf(
                occurrence("bad-type", 0).copy(sourceSequenceSnapshotNodeId = "repeat"),
                occurrence("bad-owner", 0).copy(sourceSequenceSnapshotNodeId = "other-step"),
                occurrence("bad-activity", 0).copy(activitySnapshotId = "one-off"),
                occurrence("bad-repeat", 0).copy(repeatSourceSnapshotNodeId = "repeat", repeatIteration = 0),
                occurrence("bad-runtime", 0).copy(isRuntimeAdded = true),
            )
        invalidOccurrences.forEachIndexed { index, occurrence ->
            assertThrows(IllegalArgumentException::class.java) {
                executions.insertAggregate(
                    SequenceExecutionAggregateEntity(
                        runningRoot("bad-occurrence-$index"),
                        occurrences = listOf(occurrence.copy(sequenceExecutionId = "bad-occurrence-$index")),
                    ),
                )
            }
        }
        executions.insertAggregate(
            SequenceExecutionAggregateEntity(
                runningRoot("owner"),
                occurrences = listOf(occurrence("owned", 0).copy(sequenceExecutionId = "owner")),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            executions.insertAggregate(
                SequenceExecutionAggregateEntity(
                    runningRoot("foreign-interval"),
                    intervals =
                        listOf(
                            interval(
                                "foreign",
                                "ACTIVE_STEP",
                                0,
                                null,
                                "owned",
                            ).copy(sequenceExecutionId = "foreign-interval"),
                        ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            executions.insertAggregate(
                completedAggregate("open-completed").copy(
                    intervals =
                        listOf(
                            interval("open-completed-interval", "ACTIVE_STEP", 0, null, "open-completed-top")
                                .copy(sequenceExecutionId = "open-completed"),
                        ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            executions.insertAggregate(
                SequenceExecutionAggregateEntity(
                    runningRoot("missing-step-link"),
                    intervals =
                        listOf(
                            interval("missing-step-link-interval", "ACTIVE_STEP", 0, null)
                                .copy(sequenceExecutionId = "missing-step-link"),
                        ),
                ),
            )
        }
        listOf(
            SequenceExecutionFieldValueEntity("bad-value", "activity-number", 1, null, null),
            SequenceExecutionFieldValueEntity("bad-value", "seq-number", null, null, "wrong"),
            SequenceExecutionFieldValueEntity("bad-value", "seq-category", null, "activity-option", null),
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                executions.insertAggregate(
                    SequenceExecutionAggregateEntity(runningRoot("bad-value"), values = listOf(value)),
                )
            }
        }
    }

    @Test
    fun intervalSqlChecksSetNullAndDefensiveReadAreEnforced() {
        executions.insertAggregate(
            SequenceExecutionAggregateEntity(
                runningRoot().copy(currentOccurrenceId = "current"),
                occurrences = listOf(occurrence("current", 0, "CURRENT", 0)),
                intervals = listOf(interval("open", "ACTIVE_STEP", 0, null, "current")),
            ),
        )
        listOf(
            "INSERT INTO sequence_intervals VALUES ('unknown', 'execution', 'UNKNOWN', 0, 1, NULL)",
            "INSERT INTO sequence_intervals VALUES ('reversed', 'execution', 'EXPLICIT_PAUSE', 2, 1, NULL)",
            "INSERT INTO sequence_intervals VALUES ('missing-root', 'missing', 'EXPLICIT_PAUSE', 0, 1, NULL)",
        ).forEach { statement ->
            assertThrows(SQLiteConstraintException::class.java) { sql(statement) }
        }
        sql("DELETE FROM sequence_occurrences WHERE id = 'current'")
        assertNull(executions.getIntervals("execution").single().occurrenceId)
        assertNull(executions.getById("execution")?.currentOccurrenceId)
        assertNotNull(executions.getAggregate("execution"))

        insertRawOccurrence("wrong-current", 1, "NOT_STARTED")
        sql("UPDATE sequence_executions SET current_occurrence_id = 'wrong-current' WHERE id = 'execution'")
        assertThrows(IllegalArgumentException::class.java) { executions.getAggregate("execution") }
    }

    @Test
    fun activitySequenceChildrenRequireSameParentSnapshotAndUniqueOccurrence() {
        executions.insertAggregate(
            SequenceExecutionAggregateEntity(
                runningRoot(),
                occurrences =
                    listOf(
                        occurrence("child-occurrence", 0, "COMPLETED", 0, 10, "MANUAL_FINISH"),
                        occurrence("one-off-occurrence", 1, "COMPLETED", 0, 10, "MANUAL_FINISH").copy(
                            sourceSequenceSnapshotNodeId = null,
                            activitySnapshotId = "one-off",
                            isRuntimeAdded = true,
                        ),
                        occurrence("no-live-occurrence", 2, "COMPLETED", 0, 10, "MANUAL_FINISH").copy(
                            sourceSequenceSnapshotNodeId = null,
                            activitySnapshotId = "no-live",
                            isRuntimeAdded = true,
                        ),
                    ),
            ),
        )
        val timed = childActivity("child", "execution", "child-occurrence", "activity", "activity-series")
        database.activityExecutionDao().insertAggregate(ActivityExecutionAggregateEntity(timed))
        assertEquals("SEQUENCE_CHILD", database.activityExecutionDao().getById("child")?.contextType)
        assertNull(database.activityExecutionDao().getById("child")?.completionReason)

        val oneOff = childActivity("one-off-child", "execution", "one-off-occurrence", "one-off", null)
        database.activityExecutionDao().insertAggregate(ActivityExecutionAggregateEntity(oneOff))
        assertNull(database.activityExecutionDao().getById("one-off-child")?.statisticsSeriesId)

        executions.insertAggregate(SequenceExecutionAggregateEntity(runningRoot("other")))
        listOf(
            childActivity("wrong-parent", "other", "child-occurrence", "activity", "activity-series"),
            childActivity("wrong-occurrence", "execution", "one-off-occurrence", "activity", "activity-series"),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                database.activityExecutionDao().insertAggregate(ActivityExecutionAggregateEntity(invalid))
            }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            database.activityExecutionDao().insertAggregate(
                ActivityExecutionAggregateEntity(
                    childActivity("missing-occurrence", "execution", "missing", "activity", "activity-series"),
                ),
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            database.activityExecutionDao().insertAggregate(ActivityExecutionAggregateEntity(timed.copy(id = "second")))
        }
        database.activityExecutionDao().insertAggregate(
            ActivityExecutionAggregateEntity(
                childActivity("no-live-child", "execution", "no-live-occurrence", "no-live", null)
                    .copy(startedAtMs = null, activeDurationMs = null),
            ),
        )
        assertNull(database.activityExecutionDao().getById("no-live-child")?.startedAtMs)

        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_executions SET sequence_execution_id = 'other' WHERE id = 'child'",
        )
        assertThrows(IllegalArgumentException::class.java) {
            database.activityExecutionDao().getAggregate("child")
        }
    }

    @Test
    fun occurrenceOwnershipProtectsActivitySnapshotsAndExecutionProtectsSequenceSnapshot() {
        insertSequenceSnapshot("deletable", stepId = "deletable-step", activityId = "one-off")
        executions.insertAggregate(
            SequenceExecutionAggregateEntity(
                runningRoot(),
                occurrences =
                    listOf(
                        occurrence("runtime-added", 0).copy(
                            sourceSequenceSnapshotNodeId = null,
                            activitySnapshotId = "one-off",
                            isRuntimeAdded = true,
                        ),
                    ),
            ),
        )

        database.sequenceSnapshotDao().hardDeleteAndPruneOwnedActivitySnapshots("deletable")
        assertNotNull(database.activitySnapshotDao().getById("one-off"))
        assertTrue(database.sequenceSnapshotDao().hasSequenceExecutionReference("sequence-snapshot"))
        assertThrows(IllegalArgumentException::class.java) {
            database.sequenceSnapshotDao().hardDeleteAndPruneOwnedActivitySnapshots("sequence-snapshot")
        }
        assertNotNull(executions.getById("execution"))

        assertThrows(SQLiteConstraintException::class.java) { database.activitySnapshotDao().hardDelete("one-off") }
        assertEquals(1, executions.hardDelete("execution"))
        assertFalse(database.sequenceSnapshotDao().hasSequenceExecutionReference("sequence-snapshot"))
        assertEquals(1, database.activitySnapshotDao().hardDelete("one-off"))
    }

    private fun completedAggregate(id: String = "execution"): SequenceExecutionAggregateEntity {
        val topId = if (id == "execution") "top" else "$id-top"
        val repeatOccurrenceId = if (id == "execution") "repeat-occurrence" else "$id-repeat-occurrence"
        return SequenceExecutionAggregateEntity(
            root(id),
            occurrences =
                listOf(
                    occurrence(topId, 0, "COMPLETED", 0, 60, "ADVANCED_TO_NEXT").copy(sequenceExecutionId = id),
                    occurrence(repeatOccurrenceId, 1).copy(
                        sequenceExecutionId = id,
                        sourceSequenceSnapshotNodeId = "repeat-child",
                        repeatSourceSnapshotNodeId = "repeat",
                        repeatIteration = 1,
                    ),
                ),
            intervals = listOf(interval("$id-active", "ACTIVE_STEP", 0, 60, topId).copy(sequenceExecutionId = id)),
        )
    }

    private fun root(id: String = "execution") =
        SequenceExecutionEntity(
            id,
            "sequence-snapshot",
            null,
            "sequence-series",
            "COMPLETED",
            0,
            100,
            60,
            40,
            100,
            "UTC",
            0,
            "1970-01-01",
            null,
            0,
            100,
        )

    private fun runningRoot(id: String = "execution") =
        root(
            id,
        ).copy(
            status = "RUNNING",
            endedAtMs = null,
            activeDurationMs = null,
            pauseDurationMs = null,
            wallDurationMs = null,
            updatedAtMs = 0,
        )

    private fun occurrence(
        id: String,
        position: Int,
        status: String = "NOT_STARTED",
        enteredAt: Long? = null,
        completedAt: Long? = null,
        reason: String? = null,
    ) = SequenceOccurrenceEntity(
        id,
        "execution",
        "top-step",
        "activity",
        position,
        null,
        null,
        status,
        enteredAt,
        completedAt,
        reason,
    )

    private fun interval(
        id: String,
        kind: String,
        start: Long,
        end: Long?,
        occurrenceId: String? = null,
    ) = SequenceIntervalEntity(id, "execution", kind, start, end, occurrenceId)

    private fun childActivity(
        id: String,
        sequenceId: String,
        occurrenceId: String,
        snapshotId: String,
        seriesId: String?,
    ) = ActivityExecutionEntity(
        id,
        snapshotId,
        "SEQUENCE_CHILD",
        sequenceId,
        occurrenceId,
        null,
        seriesId,
        "COMPLETED",
        0,
        10,
        10,
        "UTC",
        0,
        "1970-01-01",
        null,
        null,
        0,
        10,
    )

    private fun insertActivitySnapshot(
        id: String,
        mode: String,
        seriesId: String?,
        withFields: Boolean = false,
    ) {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(id, id, null, mode, null, null, null, seriesId, false, 0),
                ActivitySnapshotSettingsEntity(id),
                fields =
                    if (withFields) {
                        listOf(
                            ActivitySnapshotFieldEntity(
                                "activity-number",
                                id,
                                null,
                                0,
                                "Number",
                                null,
                                "NUMBER",
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                            ),
                            ActivitySnapshotFieldEntity(
                                "activity-category",
                                id,
                                null,
                                1,
                                "Category",
                                null,
                                "CATEGORY",
                                null,
                                null,
                                null,
                                "activity-option",
                                null,
                                false,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                options =
                    if (withFields) {
                        listOf(
                            ActivitySnapshotCategoryOptionEntity(
                                "activity-option",
                                "activity-category",
                                null,
                                0,
                                "Option",
                                null,
                            ),
                        )
                    } else {
                        emptyList()
                    },
            ),
        )
    }

    private fun insertSequenceSnapshot(
        id: String,
        stepId: String = "top-step",
        activityId: String = "activity",
    ) {
        val isPrimary = id == "sequence-snapshot"
        val repeatId = if (isPrimary) "repeat" else "$id-repeat"
        val repeatChildId = if (isPrimary) "repeat-child" else "$id-repeat-child"
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity(id, id, null, null, null, "sequence-series", 0),
                SequenceSnapshotSettingsEntity(id, true, 0, 0, true, true, false, true, true, "ACTIVE"),
                fields =
                    if (isPrimary) {
                        listOf(
                            SequenceSnapshotFieldEntity(
                                "seq-number",
                                id,
                                null,
                                0,
                                "Number",
                                null,
                                "NUMBER",
                                null,
                                null,
                                0,
                                null,
                                null,
                                false,
                            ),
                            SequenceSnapshotFieldEntity(
                                "seq-category",
                                id,
                                null,
                                1,
                                "Category",
                                null,
                                "CATEGORY",
                                null,
                                null,
                                null,
                                "seq-option",
                                null,
                                false,
                            ),
                            SequenceSnapshotFieldEntity(
                                "seq-text",
                                id,
                                null,
                                2,
                                "Text",
                                null,
                                "TEXT",
                                null,
                                null,
                                null,
                                null,
                                "",
                                false,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                options =
                    if (isPrimary) {
                        listOf(
                            SequenceSnapshotCategoryOptionEntity("seq-option", "seq-category", null, 0, "Option", null),
                        )
                    } else {
                        emptyList()
                    },
                nodes =
                    listOf(
                        SequenceSnapshotNodeEntity(stepId, id, "STEP", null, 0, activityId, null),
                        SequenceSnapshotNodeEntity(repeatId, id, "REPEAT", null, 1, null, 2),
                        SequenceSnapshotNodeEntity(repeatChildId, id, "STEP", repeatId, 0, activityId, null),
                    ),
            ),
        )
    }

    private fun insertRawRoot(root: SequenceExecutionEntity) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sequence_executions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                root.id,
                root.snapshotId,
                root.planEntryId,
                root.statisticsSeriesId,
                root.status,
                root.startedAtMs,
                root.endedAtMs,
                root.activeDurationMs,
                root.pauseDurationMs,
                root.wallDurationMs,
                root.originalZoneId,
                root.originalUtcOffsetMinutes,
                root.primaryLocalDate,
                root.currentOccurrenceId,
                root.createdAtMs,
                root.updatedAtMs,
            ),
        )
    }

    private fun insertRawOccurrence(
        id: String,
        position: Int,
        status: String,
        enteredAt: Long? = null,
        completedAt: Long? = null,
        reason: String? = null,
        repeatIteration: Int? = null,
        executionId: String = "execution",
        activityId: String = "activity",
    ) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sequence_occurrences (id, sequence_execution_id, activity_snapshot_id, runtime_position, repeat_iteration, status, entered_at_ms, completed_at_ms, completion_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                id,
                executionId,
                activityId,
                position,
                repeatIteration,
                status,
                enteredAt,
                completedAt,
                reason,
            ),
        )
    }

    private fun sql(statement: String) = database.openHelper.writableDatabase.execSQL(statement)
}
