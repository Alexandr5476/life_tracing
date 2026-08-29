package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
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
        assertEquals(Duration.ofSeconds(10), jumpedDetail.root.activeDuration)
        assertEquals(OccurrenceCompletionReason.SEQUENCE_ENDED_EARLY, jumpedDetail.occurrences[2].completionReason)
        assertEquals(Instant.ofEpochSecond(30), jumpedDetail.occurrences[2].completedAt)
        assertTrue(jumpedDetail.occurrences[2].child != null)
    }

    @Test
    fun canonicalRuntimeAddAndDoAgainKeepFreshHistoryIdentitiesAfterReload() {
        seedRuntimeSnapshots()
        val live = liveRepository()
        val started =
            live.startSequenceFromSnapshot(
                SequenceSnapshotId("writer-two-waiting"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        val original = started.execution.occurrences.first()
        val originalChildId = requireNotNull(started.currentChild).id
        live.completeCurrentSequenceStep(original.id, Instant.ofEpochSecond(5))
        val repeated = live.doAgain(original.id, RuntimeInsertionPlacement.START_NOW, Instant.ofEpochSecond(10))
        val replay =
            repeated.execution.occurrences.single {
                it.id != original.id &&
                    it.status == RuntimeOccurrenceStatus.CURRENT
            }
        live.completeCurrentSequenceStep(replay.id, Instant.ofEpochSecond(15))
        val replayEnded = live.endSequenceEarly(Instant.ofEpochSecond(16))

        reopen()
        val replayDetail = requireNotNull(repository.getSequenceDetail(replayEnded.execution.id))
        val originalHistory = replayDetail.occurrences.single { it.occurrenceId == original.id }
        val replayed = replayDetail.occurrences.single { it.occurrenceId == replay.id }
        assertEquals(original.activitySnapshotId, replayed.activitySnapshotId)
        assertEquals(originalChildId, originalHistory.child?.executionId)
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
                                ActivitySnapshotFieldDraft(
                                    com.alexandr5476.lifetracing.domain.DraftIdentity
                                        .New("missing"),
                                    null,
                                    1,
                                    "Missing",
                                    type = CustomFieldType.TEXT,
                                ),
                                ActivitySnapshotFieldDraft(
                                    com.alexandr5476.lifetracing.domain.DraftIdentity
                                        .New("category"),
                                    null,
                                    2,
                                    "Category",
                                    type = CustomFieldType.CATEGORY,
                                    defaultCategoryOption =
                                        com.alexandr5476.lifetracing.domain.DraftIdentity
                                            .New("category-option"),
                                    categoryOptions =
                                        listOf(
                                            ActivitySnapshotCategoryOptionDraft(
                                                com.alexandr5476.lifetracing.domain.DraftIdentity.New(
                                                    "category-option",
                                                ),
                                                null,
                                                0,
                                                "Category creation",
                                            ),
                                        ),
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
        val fields = addedChild.fields.associateBy { it.name }
        assertEquals(ActivityHistoryActualValue.Number(0), fields.getValue("Zero").actualValue)
        assertEquals(ActivityHistoryActualValue.Missing, fields.getValue("Missing").actualValue)
        val category = fields.getValue("Category")
        assertEquals(
            ActivityHistoryConfiguredValue.Category(category.categoryOptions.single().id),
            category.configuredValue,
        )
        assertEquals(
            ActivityHistoryActualValue.Category(category.categoryOptions.single().id, "Category creation"),
            category.actualValue,
        )
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

    @Test
    fun canonicalNoLiveChildRemainsDurationlessAfterReload() {
        seedRuntimeSnapshots()
        val live = liveRepository()
        val started =
            live.startSequenceFromSnapshot(
                SequenceSnapshotId("writer-no-live"),
                Instant.EPOCH,
                Instant.EPOCH,
                ZoneOffset.UTC,
            )
        val completed =
            live.completeCurrentSequenceStep(
                started.execution.currentOccurrenceId!!,
                Instant.ofEpochSecond(5),
            )
        val ended = live.endSequenceEarly(Instant.ofEpochSecond(6))

        reopen()
        val detail = requireNotNull(repository.getSequenceDetail(ended.execution.id))
        assertNull(detail.occurrences[0].child?.startedAt)
        assertNull(detail.occurrences[0].child?.activeDuration)
        assertNull(detail.occurrences[1].child)
        assertEquals(RuntimeOccurrenceStatus.COMPLETED, completed.execution.occurrences[0].status)
    }

    @Test
    fun sequenceChildUsesEffectiveSourceLabelsWithoutChangingFrozenFacts() {
        seedRuntimeSnapshots()
        insertSourceLinkedRuntimeTemplate()
        val live = liveRepository()
        live.startSequenceFromSnapshot(
            SequenceSnapshotId("writer-navigation"),
            Instant.EPOCH,
            Instant.EPOCH,
            ZoneOffset.UTC,
        )
        val added =
            live.runtimeAdd(
                ActivityEntrySource.Template(ActivityTemplateId("source-linked-template")),
                RuntimeInsertionPlacement.START_NOW,
                Instant.ofEpochSecond(1),
            )
        val occurrence = added.execution.occurrences.single { it.isRuntimeAdded }
        live.completeCurrentSequenceStep(occurrence.id, Instant.ofEpochSecond(5))
        val ended = live.endSequenceEarly(Instant.ofEpochSecond(6))

        reopen()
        database.activityTemplateDao().updateFieldDisplayName("source-number", "Current number", 2)
        database.activityTemplateDao().updateOptionDisplayLabel("source-option", "Current option")
        observedSql.clear()
        val current = sourceLinkedChild(ended.execution.id, occurrence.id)
        assertEquals("Current number", current.fields[0].name)
        assertEquals("Current option", (current.fields[1].actualValue as ActivityHistoryActualValue.Category).label)
        assertEquals(1, observedSql.count { "from activity_template_fields" in it.lowercase() })
        assertEquals(1, observedSql.count { "from activity_template_category_options" in it.lowercase() })
        assertFalse(observedSql.any { it.lowercase().startsWith("insert") || it.lowercase().startsWith("update") })

        database.activityTemplateDao().archiveOption("source-option")
        assertEquals(
            "Creation option",
            (
                sourceLinkedChild(
                    ended.execution.id,
                    occurrence.id,
                ).fields[1].actualValue as ActivityHistoryActualValue.Category
            ).label,
        )
        database.activityTemplateDao().archiveField("source-number", 3)
        val fieldUnavailable = sourceLinkedChild(ended.execution.id, occurrence.id)
        assertEquals("Creation number", fieldUnavailable.fields[0].name)
        database.activityTemplateDao().archiveField("source-category", 4)
        val categoryFieldUnavailable = sourceLinkedChild(ended.execution.id, occurrence.id)
        assertEquals("Creation category", categoryFieldUnavailable.fields[1].name)
        assertEquals(
            "Creation option",
            (categoryFieldUnavailable.fields[1].actualValue as ActivityHistoryActualValue.Category).label,
        )
        database.activityTemplateDao().archive("source-linked-template", 5)
        val templateUnavailable = sourceLinkedChild(ended.execution.id, occurrence.id)
        assertEquals("Creation number", templateUnavailable.fields[0].name)
        assertEquals(
            "Creation option",
            (templateUnavailable.fields[1].actualValue as ActivityHistoryActualValue.Category).label,
        )
        assertEquals(ActivityHistoryConfiguredValue.Number(7), templateUnavailable.fields[0].configuredValue)
        assertEquals(ActivityHistoryActualValue.Number(7), templateUnavailable.fields[0].actualValue)
    }

    @Test
    fun sequenceChildLocalOverridesWinOverSourceRenameAndUnavailability() {
        insertSourceLinkedRuntimeTemplate()
        insertOverrideHistory()
        observedSql.clear()
        val available =
            sourceLinkedChild(
                SequenceExecutionId("override-sequence-execution"),
                SequenceOccurrenceId("override-occurrence"),
            )
        assertEquals("Local number", available.fields[0].name)
        assertEquals("Local option", (available.fields[1].actualValue as ActivityHistoryActualValue.Category).label)
        assertEquals(1, observedSql.count { "from activity_template_fields" in it.lowercase() })
        assertEquals(0, observedSql.count { "from activity_template_category_options" in it.lowercase() })
        assertFalse(
            observedSql.any {
                it.lowercase().startsWith("insert") ||
                    it.lowercase().startsWith("update") ||
                    it.lowercase().startsWith("delete")
            },
        )

        database.activityTemplateDao().updateFieldDisplayName("source-number", "Renamed", 2)
        database.activityTemplateDao().updateOptionDisplayLabel("source-option", "Renamed option")
        database.activityTemplateDao().archiveOption("source-option")
        database.activityTemplateDao().archive("source-linked-template", 3)
        val unavailable =
            sourceLinkedChild(
                SequenceExecutionId("override-sequence-execution"),
                SequenceOccurrenceId("override-occurrence"),
            )
        assertEquals("Local number", unavailable.fields[0].name)
        assertEquals("Local option", (unavailable.fields[1].actualValue as ActivityHistoryActualValue.Category).label)
        assertEquals(ActivityHistoryConfiguredValue.Number(7), unavailable.fields[0].configuredValue)
        assertEquals(ActivityHistoryActualValue.Number(7), unavailable.fields[0].actualValue)
        assertEquals(
            "override-option",
            (unavailable.fields[1].actualValue as ActivityHistoryActualValue.Category).optionId.value,
        )
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
        fixtures.activity("writer-no-live-activity", "NO_LIVE_TRACKING")
        fixtures.sequence("writer-navigation", listOf("writer-stopwatch", "writer-stopwatch", "writer-stopwatch"))
        fixtures.sequence("writer-single", listOf("writer-stopwatch"), autoAdvance = false)
        fixtures.sequence("writer-two-waiting", listOf("writer-stopwatch", "writer-stopwatch"), autoAdvance = false)
        fixtures.sequence(
            "writer-no-live",
            listOf("writer-no-live-activity", "writer-no-live-activity"),
            autoAdvance = false,
        )
    }

    private fun sourceLinkedChild(
        sequenceId: SequenceExecutionId,
        occurrenceId: SequenceOccurrenceId,
    ) = requireNotNull(repository.getSequenceDetail(sequenceId))
        .occurrences
        .single { it.occurrenceId == occurrenceId }
        .child!!

    private fun insertSourceLinkedRuntimeTemplate() {
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    "source-linked-template",
                    "Source",
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
                ActivityTemplateSettingsEntity("source-linked-template"),
                fields =
                    listOf(
                        ActivityTemplateFieldEntity(
                            "source-number",
                            "source-linked-template",
                            0,
                            "Creation number",
                            "NUMBER",
                            "km",
                            1,
                            7,
                            null,
                            null,
                            false,
                            0,
                            0,
                            null,
                        ),
                        ActivityTemplateFieldEntity(
                            "source-category",
                            "source-linked-template",
                            1,
                            "Creation category",
                            "CATEGORY",
                            null,
                            null,
                            null,
                            "source-option",
                            null,
                            false,
                            0,
                            0,
                            null,
                        ),
                    ),
                options =
                    listOf(
                        ActivityTemplateCategoryOptionEntity("source-option", "source-category", 0, "Creation option"),
                    ),
                userState = ActivityTemplateUserStateEntity("source-linked-template", null, null),
            ),
        )
    }

    private fun insertOverrideHistory() {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    "override-activity",
                    "Override",
                    null,
                    "NO_LIVE_TRACKING",
                    null,
                    "source-linked-template",
                    1,
                    "activity-series",
                    false,
                    0,
                ),
                ActivitySnapshotSettingsEntity("override-activity"),
                fields =
                    listOf(
                        ActivitySnapshotFieldEntity(
                            "override-number",
                            "override-activity",
                            "source-number",
                            0,
                            "Creation number",
                            "Local number",
                            "NUMBER",
                            "km",
                            1,
                            7,
                            null,
                            null,
                            false,
                        ),
                        ActivitySnapshotFieldEntity(
                            "override-category",
                            "override-activity",
                            "source-category",
                            1,
                            "Creation category",
                            null,
                            "CATEGORY",
                            null,
                            null,
                            null,
                            "override-option",
                            null,
                            false,
                        ),
                    ),
                options =
                    listOf(
                        ActivitySnapshotCategoryOptionEntity(
                            "override-option",
                            "override-category",
                            "source-option",
                            0,
                            "Creation option",
                            "Local option",
                        ),
                    ),
            ),
        )
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity(
                    "override-sequence-snapshot",
                    "Override",
                    null,
                    null,
                    null,
                    "sequence-series",
                    0,
                ),
                SequenceSnapshotSettingsEntity(
                    "override-sequence-snapshot",
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
                nodes =
                    listOf(
                        SequenceSnapshotNodeEntity(
                            "override-node",
                            "override-sequence-snapshot",
                            "STEP",
                            null,
                            0,
                            "override-activity",
                            null,
                        ),
                    ),
            ),
        )
        val occurrence = SequenceOccurrenceId("override-occurrence")
        val execution =
            SequenceExecution(
                SequenceExecutionId("override-sequence-execution"),
                SequenceSnapshotId("override-sequence-snapshot"),
                null,
                SequenceExecutionStatus.COMPLETED,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                Duration.ZERO,
                Duration.ZERO,
                ZoneOffset.UTC,
                0,
                LocalDate.of(1970, 1, 1),
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                listOf(
                    RuntimeOccurrence(
                        occurrence,
                        SequenceSnapshotNodeId("override-node"),
                        ActivitySnapshotId("override-activity"),
                        0,
                        null,
                        null,
                        RuntimeOccurrenceStatus.COMPLETED,
                        Instant.EPOCH,
                        Instant.EPOCH,
                        OccurrenceCompletionReason.MANUAL_FINISH,
                        false,
                        false,
                    ),
                ),
            )
        database.sequenceExecutionDao().insertAggregate(execution.toEntityAggregate())
        insertNoLiveChild("override-child", "override-activity", execution.id, occurrence, Instant.EPOCH)
        database.activityExecutionDao().upsertValue(
            ActivityExecutionFieldValueEntity("override-child", "override-number", 7, null, null),
        )
        database.activityExecutionDao().upsertValue(
            ActivityExecutionFieldValueEntity("override-child", "override-category", null, "override-option", null),
        )
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
        database =
            LifeTracingDatabase
                .builder(context, databaseName)
                .setQueryCallback({ sql, _ -> observedSql += sql }, Executor { it.run() })
                .allowMainThreadQueries()
                .build()
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
