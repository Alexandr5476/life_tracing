package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.RuntimeInsertionPlacement
import com.alexandr5476.lifetracing.domain.RuntimeOccurrence
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecution
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceHistoryActualValue
import com.alexandr5476.lifetracing.domain.SequenceHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
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
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class SequenceHistoryReadRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var repository: HistoryReadRepository
    private val observedSql = Collections.synchronizedList(mutableListOf<String>())
    private val databaseName = "sequence-history-detail-test.db"
    private var writerActivity = 0
    private var writerSequence = 0
    private var writerOccurrence = 0
    private var writerInterval = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(databaseName)
        database =
            LifeTracingDatabase
                .builder(context, databaseName)
                .setQueryCallback({ sql, _ -> observedSql += sql }, Executor { it.run() })
                .allowMainThreadQueries()
                .build()
        repository = HistoryReadRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(databaseName)
    }

    @Test
    fun terminalDetailReloadsRuntimeTopologyChildrenAndBatchedOwnedRows() {
        seedTerminalRun()
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            LifeTracingDatabase
                .builder(context, databaseName)
                .setQueryCallback({ sql, _ -> observedSql += sql }, Executor { it.run() })
                .allowMainThreadQueries()
                .build()
        repository = HistoryReadRepository(database)

        observedSql.clear()
        val detail = requireNotNull(repository.getSequenceDetail(SequenceExecutionId("sequence-execution")))
        val queries = observedSql.map(String::lowercase)

        assertEquals(SequenceSnapshotId("sequence-snapshot"), detail.root.snapshotId)
        assertEquals(SequenceExecutionStatus.COMPLETED, detail.root.status)
        assertEquals(LocalDate.of(1970, 1, 1), detail.root.primaryLocalDate)
        assertEquals(Duration.ofSeconds(20), detail.root.activeDuration)
        assertEquals(Duration.ofSeconds(10), detail.root.pauseDuration)
        assertEquals(Duration.ofSeconds(30), detail.root.wallDuration)
        assertEquals(
            listOf("runtime-added", "repeat-one", "repeat-two"),
            detail.occurrences.map { it.occurrenceId.value },
        )
        assertNull(detail.occurrences[0].sourceSequenceSnapshotNodeId)
        assertTrue(detail.occurrences[0].isRuntimeAdded)
        assertEquals(
            "activity-two",
            detail.occurrences[0]
                .activity.snapshotId.value,
        )
        assertEquals("repeat-step", detail.occurrences[1].sourceSequenceSnapshotNodeId?.value)
        assertEquals("repeat-node", detail.occurrences[1].repeatSourceSnapshotNodeId?.value)
        assertEquals(1, detail.occurrences[1].repeatIteration)
        assertEquals(RuntimeOccurrenceStatus.SKIPPED, detail.occurrences[2].status)
        assertNull(detail.occurrences[2].child)
        assertEquals(ActivityExecutionId("child-runtime"), detail.occurrences[0].child?.executionId)
        assertNull(detail.occurrences[0].child?.activeDuration)
        assertEquals(2, detail.intervals.size)
        assertEquals(SequenceHistoryConfiguredValue.Number(7), detail.fields[0].configuredValue)
        assertEquals(SequenceHistoryActualValue.Number(0), detail.fields[0].actualValue)
        assertEquals(
            SequenceHistoryActualValue.Category(SequenceSnapshotCategoryOptionId("sequence-option"), "Option"),
            detail.fields[1].actualValue,
        )
        assertEquals(1, queries.count { "from activity_snapshots" in it && " in (" in it })
        assertEquals(1, queries.count { "from activity_execution_pauses" in it && " in (" in it })
        assertEquals(1, queries.count { "from activity_execution_field_values" in it && " in (" in it })
        assertFalse(queries.any { it.startsWith("insert") || it.startsWith("update") || it.startsWith("delete") })
    }

    @Test
    fun canonicalMakeNextAndGoNowTopologySurviveReloadIntoHistoryDetail() {
        seedRuntimeSnapshots()
        val live = liveRepository()
        val reordered =
            live.startSequenceFromSnapshot(
                SequenceSnapshotId("writer-navigation"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        val makeNextTarget = reordered.execution.occurrences[2]
        live.makeNext(makeNextTarget.id, Instant.ofEpochSecond(5))
        val reorderedEnded = live.endSequenceEarly(Instant.ofEpochSecond(10))

        reopen()
        val reorderedDetail = requireNotNull(repository.getSequenceDetail(reorderedEnded.execution.id))
        assertEquals(
            listOf(
                reordered.execution.occurrences[0].id,
                makeNextTarget.id,
                reordered.execution.occurrences[1].id,
            ),
            reorderedDetail.occurrences.map { it.occurrenceId },
        )

        val jumped =
            liveRepository().startSequenceFromSnapshot(
                SequenceSnapshotId("writer-navigation"),
                Instant.ofEpochSecond(20),
                Instant.ofEpochSecond(20),
                ZoneOffset.UTC,
            )
        val jumpTarget = jumped.execution.occurrences[2]
        liveRepository().goNow(jumpTarget.id, Instant.ofEpochSecond(25))
        val jumpedEnded = liveRepository().endSequenceEarly(Instant.ofEpochSecond(30))

        reopen()
        val jumpedDetail = requireNotNull(repository.getSequenceDetail(jumpedEnded.execution.id))
        assertEquals(OccurrenceCompletionReason.JUMP, jumpedDetail.occurrences[0].completionReason)
        assertEquals(RuntimeOccurrenceStatus.SKIPPED, jumpedDetail.occurrences[1].status)
        assertNull(jumpedDetail.occurrences[1].child)
        assertEquals(jumpTarget.id, jumpedDetail.occurrences[2].occurrenceId)
        assertEquals(SequenceExecutionStatus.ENDED_EARLY, jumpedDetail.root.status)
    }

    @Test
    fun canonicalRuntimeAddAndDoAgainKeepFreshHistoryIdentitiesAfterReload() {
        seedRuntimeSnapshots()
        val live = liveRepository()
        val started =
            live.startSequenceFromSnapshot(
                SequenceSnapshotId("writer-single"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        val original = started.execution.occurrences.single()
        live.completeCurrentSequenceStep(original.id, Instant.ofEpochSecond(5))
        val repeated = live.doAgain(original.id, RuntimeInsertionPlacement.START_NOW, Instant.ofEpochSecond(10))
        val replay = repeated.execution.occurrences.single { it.id != original.id }
        live.completeCurrentSequenceStep(replay.id, Instant.ofEpochSecond(15))
        val replayEnded = live.endSequenceEarly(Instant.ofEpochSecond(16))

        reopen()
        val replayDetail = requireNotNull(repository.getSequenceDetail(replayEnded.execution.id))
        val replayed = replayDetail.occurrences.single { it.occurrenceId == replay.id }
        assertEquals(original.activitySnapshotId, replayed.activitySnapshotId)
        assertTrue(
            replayed.child!!.executionId !=
                replayDetail.occurrences
                    .single { it.occurrenceId == original.id }
                    .child!!
                    .executionId,
        )

        val addLive = liveRepository()
        addLive.startSequenceFromSnapshot(
            SequenceSnapshotId("writer-navigation"),
            Instant.ofEpochSecond(20),
            Instant.ofEpochSecond(20),
            ZoneOffset.UTC,
        )
        val added =
            addLive.runtimeAdd(
                ActivityEntrySource.OneOff(
                    ActivitySnapshotDraft(
                        "Runtime detail",
                        null,
                        com.alexandr5476.lifetracing.domain.TimeTrackingMode.STOPWATCH,
                        null,
                        fields =
                            listOf(
                                ActivitySnapshotFieldDraft(
                                    com.alexandr5476.lifetracing.domain.DraftIdentity
                                        .New("zero"),
                                    null,
                                    0,
                                    "Zero",
                                    type = CustomFieldType.NUMBER,
                                    defaultNumberScaled = 0,
                                ),
                            ),
                    ),
                ),
                RuntimeInsertionPlacement.START_NOW,
                Instant.ofEpochSecond(25),
            )
        val addedOccurrence = added.execution.occurrences.single { it.isRuntimeAdded }
        addLive.completeCurrentSequenceStep(addedOccurrence.id, Instant.ofEpochSecond(30))
        val addedEnded = addLive.endSequenceEarly(Instant.ofEpochSecond(35))

        reopen()
        val addedDetail = requireNotNull(repository.getSequenceDetail(addedEnded.execution.id))
        val addedHistory = addedDetail.occurrences.single { it.occurrenceId == addedOccurrence.id }
        assertTrue(addedHistory.isRuntimeAdded)
        assertNull(addedHistory.sourceSequenceSnapshotNodeId)
        assertNull(addedHistory.repeatSourceSnapshotNodeId)
        val addedChild = requireNotNull(addedHistory.child)
        assertTrue(addedChild.executionId.value.isNotBlank())
        assertEquals(Duration.ofSeconds(5), addedChild.activeDuration)
        assertEquals(ActivityHistoryActualValue.Number(0), addedChild.fields.single().actualValue)
        assertFalse(
            database.sequenceSnapshotDao().getAggregate(addedEnded.execution.snapshotId.value)!!.nodes.any {
                it.activitySnapshotId == addedHistory.activitySnapshotId.value
            },
        )
    }

    @Test
    fun mismatchedPersistedChildSnapshotIsRejected() {
        seedTerminalRun()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE activity_executions SET snapshot_id = 'activity-one' WHERE id = 'child-runtime'",
        )

        assertThrows(IllegalArgumentException::class.java) {
            repository.getSequenceDetail(SequenceExecutionId("sequence-execution"))
        }
    }

    private fun seedTerminalRun() {
        activitySnapshot("activity-one", "Frozen one")
        activitySnapshot("activity-two", "Runtime added")
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity("sequence-snapshot", "Frozen Sequence", "Frozen note", null, null, null, 0),
                SequenceSnapshotSettingsEntity(
                    "sequence-snapshot",
                    false,
                    0,
                    0,
                    true,
                    true,
                    false,
                    false,
                    false,
                    "ACTIVE",
                ),
                fields =
                    listOf(
                        SequenceSnapshotFieldEntity(
                            "sequence-number",
                            "sequence-snapshot",
                            null,
                            0,
                            "Number",
                            null,
                            "NUMBER",
                            "km",
                            1,
                            7,
                            null,
                            null,
                        ),
                        SequenceSnapshotFieldEntity(
                            "sequence-category",
                            "sequence-snapshot",
                            null,
                            1,
                            "Category",
                            null,
                            "CATEGORY",
                            null,
                            null,
                            null,
                            "sequence-option",
                            null,
                        ),
                    ),
                options =
                    listOf(
                        SequenceSnapshotCategoryOptionEntity(
                            "sequence-option",
                            "sequence-category",
                            null,
                            0,
                            "Option",
                            null,
                        ),
                    ),
                nodes =
                    listOf(
                        SequenceSnapshotNodeEntity(
                            "repeat-node",
                            "sequence-snapshot",
                            "REPEAT",
                            null,
                            0,
                            null,
                            2,
                        ),
                        SequenceSnapshotNodeEntity(
                            "repeat-step",
                            "sequence-snapshot",
                            "STEP",
                            "repeat-node",
                            0,
                            "activity-one",
                            null,
                        ),
                    ),
            ),
        )
        val runtimeAdded = SequenceOccurrenceId("runtime-added")
        val repeatOne = SequenceOccurrenceId("repeat-one")
        val repeatTwo = SequenceOccurrenceId("repeat-two")
        val execution =
            SequenceExecution(
                SequenceExecutionId("sequence-execution"),
                SequenceSnapshotId("sequence-snapshot"),
                null,
                SequenceExecutionStatus.COMPLETED,
                Instant.EPOCH,
                Instant.ofEpochSecond(30),
                Duration.ofSeconds(20),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                ZoneOffset.UTC,
                0,
                LocalDate.of(1970, 1, 1),
                null,
                Instant.EPOCH,
                Instant.ofEpochSecond(30),
                listOf(
                    RuntimeOccurrence(
                        runtimeAdded,
                        null,
                        ActivitySnapshotId("activity-two"),
                        0,
                        null,
                        null,
                        RuntimeOccurrenceStatus.COMPLETED,
                        Instant.EPOCH,
                        Instant.ofEpochSecond(10),
                        OccurrenceCompletionReason.MANUAL_FINISH,
                        true,
                        false,
                    ),
                    RuntimeOccurrence(
                        repeatOne,
                        SequenceSnapshotNodeId("repeat-step"),
                        ActivitySnapshotId("activity-one"),
                        1,
                        SequenceSnapshotNodeId("repeat-node"),
                        1,
                        RuntimeOccurrenceStatus.COMPLETED,
                        Instant.ofEpochSecond(10),
                        Instant.ofEpochSecond(20),
                        OccurrenceCompletionReason.MANUAL_FINISH,
                        false,
                        false,
                    ),
                    RuntimeOccurrence(
                        repeatTwo,
                        SequenceSnapshotNodeId("repeat-step"),
                        ActivitySnapshotId("activity-one"),
                        2,
                        SequenceSnapshotNodeId("repeat-node"),
                        2,
                        RuntimeOccurrenceStatus.SKIPPED,
                        null,
                        null,
                        null,
                        false,
                        false,
                    ),
                ),
                listOf(
                    SequenceInterval(
                        SequenceIntervalId("runtime-interval"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        Instant.EPOCH,
                        Instant.ofEpochSecond(10),
                        runtimeAdded,
                    ),
                    SequenceInterval(
                        SequenceIntervalId("repeat-interval"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        Instant.ofEpochSecond(10),
                        Instant.ofEpochSecond(20),
                        repeatOne,
                    ),
                ),
                values =
                    listOf(
                        com.alexandr5476.lifetracing.domain.NumberSequenceExecutionValue(
                            SequenceSnapshotFieldId("sequence-number"),
                            0,
                        ),
                        com.alexandr5476.lifetracing.domain.CategorySequenceExecutionValue(
                            SequenceSnapshotFieldId("sequence-category"),
                            SequenceSnapshotCategoryOptionId("sequence-option"),
                        ),
                    ),
            )
        database.sequenceExecutionDao().insertAggregate(execution.toEntityAggregate())
        insertNoLiveChild("child-runtime", "activity-two", execution.id, runtimeAdded, Instant.ofEpochSecond(10))
        insertNoLiveChild("child-repeat", "activity-one", execution.id, repeatOne, Instant.ofEpochSecond(20))
    }

    private fun seedRuntimeSnapshots() {
        val fixtures = LiveRuntimeTestFixtures(database)
        fixtures.seedSeries()
        fixtures.activity("writer-stopwatch", "STOPWATCH")
        fixtures.sequence("writer-navigation", listOf("writer-stopwatch", "writer-stopwatch", "writer-stopwatch"))
        fixtures.sequence("writer-single", listOf("writer-stopwatch"), autoAdvance = false)
    }

    private fun liveRepository(): LiveSessionRepository =
        LiveSessionRepository(
            database,
            { ActivityExecutionId("writer-child-${++writerActivity}") },
            {
                com.alexandr5476.lifetracing.domain
                    .ActivityExecutionPauseId("writer-pause-${++writerActivity}")
            },
            { SequenceExecutionId("writer-sequence-${++writerSequence}") },
            { SequenceOccurrenceId("writer-occurrence-${++writerOccurrence}") },
            { SequenceIntervalId("writer-interval-${++writerInterval}") },
        )

    private fun reopen() {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = LifeTracingDatabase.builder(context, databaseName).allowMainThreadQueries().build()
        repository = HistoryReadRepository(database)
    }

    private fun activitySnapshot(
        id: String,
        name: String,
    ) {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(id, name, null, "NO_LIVE_TRACKING", null, null, null, null, false, 0),
                ActivitySnapshotSettingsEntity(id),
            ),
        )
    }

    private fun insertNoLiveChild(
        id: String,
        snapshotId: String,
        sequenceExecutionId: SequenceExecutionId,
        occurrenceId: SequenceOccurrenceId,
        completedAt: Instant,
    ) {
        val snapshot = requireNotNull(database.activitySnapshotDao().getAggregate(snapshotId)).toDomain()
        val child =
            ActivityExecutionFactory { ActivityExecutionId(id) }
                .completeSequenceChildNoLive(snapshot, sequenceExecutionId, occurrenceId, completedAt, ZoneOffset.UTC)
        database.activityExecutionDao().insertAggregate(child.toEntityAggregate())
    }
}
