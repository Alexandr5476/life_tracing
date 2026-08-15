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
                execution = completed(),
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
            assertThrows(SQLiteConstraintException::class.java) { executions.insertExecution(invalid) }
        }

        executions.insertExecution(completed())
        assertThrows(SQLiteConstraintException::class.java) {
            executions.insertPause(ActivityExecutionPauseEntity("pause", "execution", 50, 40))
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
            executions.insertExecution(completed("missing-snapshot", snapshotId = "missing"))
        }
        assertThrows(SQLiteConstraintException::class.java) {
            executions.insertExecution(completed("missing-series", seriesId = "missing"))
        }
        executions.insertAggregate(
            ActivityExecutionAggregateEntity(
                completed(),
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
        assertThrows(SQLiteConstraintException::class.java) {
            executions.upsertValue(ActivityExecutionFieldValueEntity("execution", "category", null, "missing", null))
        }

        assertEquals(1, executions.hardDelete("execution"))
        assertTrue(executions.getPauses("execution").isEmpty())
        assertTrue(executions.getValues("execution").isEmpty())
    }

    @Test
    fun aggregateFailureRollsBackParentAndOccurrenceIsUniqueOnlyWhenPresent() {
        assertThrows(SQLiteConstraintException::class.java) {
            executions.insertAggregate(
                ActivityExecutionAggregateEntity(
                    completed("failed"),
                    values = listOf(ActivityExecutionFieldValueEntity("failed", "missing", 1, null, null)),
                ),
            )
        }
        assertNull(executions.getById("failed"))

        executions.insertExecution(
            completed(
                "child-1",
                context = "SEQUENCE_CHILD",
                sequenceExecutionId = "sequence",
                sequenceOccurrenceId = "occurrence",
                seriesId = null,
            ),
        )
        assertThrows(SQLiteConstraintException::class.java) {
            executions.insertExecution(
                completed(
                    "child-2",
                    context = "SEQUENCE_CHILD",
                    sequenceExecutionId = "sequence",
                    sequenceOccurrenceId = "occurrence",
                    seriesId = null,
                ),
            )
        }
        executions.insertExecution(completed("standalone-1"))
        executions.insertExecution(completed("standalone-2"))
    }

    @Test
    fun pauseResumeCompleteValueUpsertAndSoftDeleteAreAtomicFocusedOperations() {
        executions.insertExecution(running())

        executions.pause("execution", "pause", 20)
        assertEquals("PAUSED", executions.getById("execution")?.status)
        executions.resume("execution", 40)
        assertEquals("RUNNING", executions.getById("execution")?.status)
        executions.complete("execution", 100)
        assertEquals("COMPLETED", executions.getById("execution")?.status)
        assertEquals(80L, executions.getById("execution")?.activeDurationMs)
        assertNull(executions.getById("execution")?.completionReason)

        executions.upsertValue(ActivityExecutionFieldValueEntity("execution", "number", 1, null, null))
        executions.upsertValue(ActivityExecutionFieldValueEntity("execution", "number", 0, null, null))
        assertEquals(0L, executions.getValues("execution").single().numberValueScaled)

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

    private fun running() =
        completed(status = "RUNNING").copy(completedAtMs = null, activeDurationMs = null, updatedAtMs = 10)

    private fun field(
        id: String,
        type: String,
        position: Int,
    ) = ActivitySnapshotFieldEntity(id, "snapshot", null, position, id, null, type, null, null, null, null, null, false)

    private fun sql(statement: String) = database.openHelper.writableDatabase.execSQL(statement)

    private fun count(
        table: String,
        where: String,
    ): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table` WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
