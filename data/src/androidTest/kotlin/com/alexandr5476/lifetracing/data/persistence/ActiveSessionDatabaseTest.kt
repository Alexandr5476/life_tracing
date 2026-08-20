package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ActiveSessionDatabaseTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var fixtures: LiveRuntimeTestFixtures

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        fixtures = LiveRuntimeTestFixtures(database)
        fixtures.seedSeries()
        fixtures.activity("stopwatch", "STOPWATCH")
        fixtures.sequence("sequence", listOf("stopwatch"))
        fixtures.standaloneExecution()
        fixtures.sequenceExecution()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun activitySingletonRoundTripsAndSecondRowIsImpossible() {
        database.activeSessionDao().insert(activitySession())
        assertEquals(activitySession(), database.activeSessionDao().get())

        assertThrows(SQLiteConstraintException::class.java) {
            database.activeSessionDao().insert(activitySession())
        }
        assertThrows(SQLiteConstraintException::class.java) {
            sql("INSERT INTO active_session VALUES (2, 'SEQUENCE', NULL, 'sequence-execution', 'RUNNING', 0)")
        }
    }

    @Test
    fun validSequenceAndRestrictForeignKeysAreEnforced() {
        database.activeSessionDao().insert(
            ActiveSession(
                ActiveSessionKind.SEQUENCE,
                ActiveSessionState.RUNNING,
                null,
                SequenceExecutionId("sequence-execution"),
                Instant.EPOCH,
            ),
        )
        assertEquals(
            "SEQUENCE",
            database
                .activeSessionDao()
                .get()
                ?.kind
                ?.name,
        )
        assertThrows(SQLiteConstraintException::class.java) {
            sql("DELETE FROM sequence_executions WHERE id = 'sequence-execution'")
        }

        database.activeSessionDao().clear()
        database.activeSessionDao().insert(activitySession())
        assertThrows(SQLiteConstraintException::class.java) {
            sql("DELETE FROM activity_executions WHERE id = 'activity-execution'")
        }
    }

    @Test
    fun missingTargetsUnknownCodesAndInvalidOwnershipShapesAreRejected() {
        listOf(
            "INSERT INTO active_session VALUES (1, 'ACTIVITY', 'missing', NULL, 'RUNNING', 0)",
            "INSERT INTO active_session VALUES (1, 'SEQUENCE', NULL, 'missing', 'RUNNING', 0)",
            "INSERT INTO active_session VALUES (1, 'UNKNOWN', 'activity-execution', NULL, 'RUNNING', 0)",
            "INSERT INTO active_session VALUES (1, 'ACTIVITY', 'activity-execution', NULL, 'UNKNOWN', 0)",
            "INSERT INTO active_session VALUES (1, 'ACTIVITY', 'activity-execution', NULL, 'WAITING_NEXT', 0)",
            "INSERT INTO active_session VALUES (1, 'ACTIVITY', 'activity-execution', 'sequence-execution', 'RUNNING', 0)",
            "INSERT INTO active_session VALUES (1, 'ACTIVITY', NULL, NULL, 'RUNNING', 0)",
        ).forEach { statement ->
            assertThrows(SQLiteConstraintException::class.java) { sql(statement) }
        }
    }

    private fun activitySession() =
        ActiveSession(
            ActiveSessionKind.ACTIVITY,
            ActiveSessionState.RUNNING,
            ActivityExecutionId("activity-execution"),
            null,
            Instant.EPOCH,
        )

    private fun sql(statement: String) = database.openHelper.writableDatabase.execSQL(statement)
}
