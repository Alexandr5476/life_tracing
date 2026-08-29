package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionFactory
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityHistoryActualValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryConfiguredValue
import com.alexandr5476.lifetracing.domain.ActivityHistoryCorrection
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFactory
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CompletedActivityHistoryRoot
import com.alexandr5476.lifetracing.domain.CompletedHistoryQuery
import com.alexandr5476.lifetracing.domain.CompletedSequenceHistoryRoot
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.HistoryDateRange
import com.alexandr5476.lifetracing.domain.PlanEntryId
import com.alexandr5476.lifetracing.domain.PlanEntryStatus
import com.alexandr5476.lifetracing.domain.PlanTarget
import com.alexandr5476.lifetracing.domain.SequenceExecution
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFactory
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class HistoryReadRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var repository: HistoryReadRepository
    private val observedSql = Collections.synchronizedList(mutableListOf<String>())
    private var fileDatabaseName: String? = null

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .setQueryCallback({ sql, _ -> observedSql += sql }, Executor { it.run() })
                .allowMainThreadQueries()
                .build()
        repository = HistoryReadRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        fileDatabaseName?.let(ApplicationProvider.getApplicationContext<Context>()::deleteDatabase)
    }

    @Test
    fun boundedRootsUsePersistedDateIncludeTerminalRootsAndExcludeNonRoots() {
        insertActivitySnapshot("activity-snapshot", "Frozen Activity", "Frozen note")
        insertTimedActivitySnapshot("timed-snapshot")
        insertSequenceSnapshot("sequence-snapshot", "Frozen Sequence", "Sequence note")
        insertActivity("activity-old", at(100))
        insertActivity("activity-new", at(300))
        val deleted = insertActivity("activity-deleted", at(400))
        database.activityExecutionDao().softDeleteCompletedStandalone(
            deleted.id.value,
            deleted.updatedAt.toEpochMilli(),
            deleted.updatedAt.plusMillis(1).toEpochMilli(),
        )
        insertRunningActivity("activity-running", at(500))
        insertSequence("sequence-completed", at(250), SequenceExecutionStatus.COMPLETED)
        insertSequence("sequence-early", at(200), SequenceExecutionStatus.ENDED_EARLY)
        insertSequence("sequence-running", at(500), SequenceExecutionStatus.RUNNING)
        insertSequenceChild("activity-child", "sequence-running", "child-occurrence", at(550))
        insertActivity("other-persisted-date", at(6 * 60 * 60), ZoneOffset.ofHours(-7))
        val snapshotsBefore = count("activity_snapshots") to count("sequence_snapshots")
        val executionBefore = database.activityExecutionDao().getById("activity-new")
        val activeSessionBefore = database.activeSessionDao().get()

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
        assertEquals(snapshotsBefore, count("activity_snapshots") to count("sequence_snapshots"))
        assertEquals(executionBefore, database.activityExecutionDao().getById("activity-new"))
        assertEquals(activeSessionBefore, database.activeSessionDao().get())
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
                        "Local text",
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
                    ActivitySnapshotCategoryOptionEntity(
                        "local-option",
                        "category-snapshot",
                        null,
                        1,
                        "Creation local option",
                        "Local option",
                    ),
                ),
            ),
        )
        val detailExecution = insertActivity("detail", at(600), snapshotId = "detail-snapshot")
        database.activityExecutionDao().upsertValue(
            ActivityExecutionFieldValueEntity(detailExecution.id.value, "number-snapshot", 0, null, null),
        )
        database.activityExecutionDao().upsertValue(
            ActivityExecutionFieldValueEntity(
                detailExecution.id.value,
                "category-snapshot",
                null,
                "option-snapshot",
                null,
            ),
        )
        database.activityTemplateDao().updateFieldDisplayName("number-source", "Current number", 2)
        database.activityTemplateDao().updateOptionDisplayLabel("option-source", "Current option")
        val snapshotCountBefore = count("activity_snapshots")
        val executionBefore = database.activityExecutionDao().getById("detail")
        observedSql.clear()

        val detail = requireNotNull(repository.getActivityDetail(ActivityExecutionId("detail")))

        assertEquals("Frozen Activity", detail.root.title)
        assertNull(detail.root.activeDuration)
        assertEquals("Current number", detail.fields[0].name)
        assertEquals(ActivityHistoryActualValue.Number(0), detail.fields[0].actualValue)
        assertEquals("Current option", (detail.fields[1].actualValue as ActivityHistoryActualValue.Category).label)
        assertEquals(ActivityHistoryActualValue.Text("configured"), detail.fields[2].actualValue)
        assertEquals("Local text", detail.fields[2].name)
        val localOption = detail.fields[1].categoryOptions.single { it.id.value == "local-option" }
        assertEquals("Local option", localOption.label)
        assertFalse(
            observedSql.map(String::lowercase).any {
                it.startsWith("insert") || it.startsWith("update") || it.startsWith("delete")
            },
        )
        database.activityTemplateDao().archive("template", 3)

        val archived = requireNotNull(repository.getActivityDetail(ActivityExecutionId("detail")))
        assertEquals("Creation number", archived.fields[0].name)
        assertEquals("Creation option", (archived.fields[1].actualValue as ActivityHistoryActualValue.Category).label)
        assertEquals("Local text", archived.fields[2].name)
        assertEquals(
            "Local option",
            archived.fields[1]
                .categoryOptions
                .single { it.id.value == "local-option" }
                .label,
        )
        assertEquals(CustomFieldType.NUMBER, archived.fields[0].type)
        assertEquals(ActivityHistoryConfiguredValue.Number(7), archived.fields[0].configuredValue)
        assertEquals(ActivityHistoryActualValue.Number(0), archived.fields[0].actualValue)
        assertEquals(
            "option-snapshot",
            (archived.fields[1].actualValue as ActivityHistoryActualValue.Category).optionId.value,
        )
        assertEquals(123L, database.activityTemplateDao().getUserState("template")?.lastUsedAtMs)
        assertEquals(1L, database.activityTemplateDao().getById("template")?.revision)
        assertEquals(snapshotCountBefore, count("activity_snapshots"))
        assertEquals(executionBefore, database.activityExecutionDao().getById("detail"))
    }

    @Test
    fun unavailableSourceOptionFallsBackWithoutChangingFrozenActivityFacts() {
        val execution = insertSourceDisplayFixture()
        database.activityTemplateDao().updateOptionDisplayLabel("display-option-source", "Current option")

        val current = requireNotNull(repository.getActivityDetail(execution.id))
        assertEquals("Current option", categoryActual(current).label)

        database.activityTemplateDao().archiveOption("display-option-source")
        val unavailable = requireNotNull(repository.getActivityDetail(execution.id))
        assertEquals("Creation option", categoryActual(unavailable).label)
        assertFrozenDisplayFixtureFacts(unavailable)
        assertEquals("Local field", unavailable.fields[2].name)
        assertEquals(
            "Local option",
            unavailable.fields[1]
                .categoryOptions
                .single {
                    it.id.value ==
                        "display-local-option"
                }.label,
        )
    }

    @Test
    fun unavailableSourceFieldsFallBackWithTheirCategoryOptions() {
        val execution = insertSourceDisplayFixture()
        database.activityTemplateDao().updateFieldDisplayName("display-number-source", "Current number", 2)
        database.activityTemplateDao().updateOptionDisplayLabel("display-option-source", "Current option")

        database.activityTemplateDao().archiveField("display-number-source", 3)
        val numberUnavailable = requireNotNull(repository.getActivityDetail(execution.id))
        assertEquals("Creation number", numberUnavailable.fields[0].name)
        assertEquals("Current option", categoryActual(numberUnavailable).label)

        database.activityTemplateDao().archiveField("display-category-source", 4)
        val categoryUnavailable = requireNotNull(repository.getActivityDetail(execution.id))
        assertEquals("Creation category", categoryUnavailable.fields[1].name)
        assertEquals("Creation option", categoryActual(categoryUnavailable).label)
        assertFrozenDisplayFixtureFacts(categoryUnavailable)
        assertEquals("Local field", categoryUnavailable.fields[2].name)
        assertEquals(
            "Local option",
            categoryUnavailable.fields[1]
                .categoryOptions
                .single { it.id.value == "display-local-option" }
                .label,
        )
    }

    @Test
    fun equalTimestampsUseTheSqlAndMergedIdentityOrder() {
        insertActivitySnapshot("activity-snapshot", "Frozen Activity", null)
        insertSequenceSnapshot("sequence-snapshot", "Frozen Sequence", null)
        val sameInstant = at(100)
        listOf("activity-c", "activity-a", "activity-b").forEach { insertActivity(it, sameInstant) }
        listOf("sequence-c", "sequence-a", "sequence-b").forEach {
            insertSequence(it, sameInstant, SequenceExecutionStatus.COMPLETED)
        }

        assertEquals(
            listOf("activity-a", "activity-b"),
            rootIds(repository.getCompletedRoots(query("2026-08-20", "2026-08-20", 2))),
        )
        assertEquals(
            listOf("activity-a", "activity-b", "activity-c", "sequence-a"),
            rootIds(repository.getCompletedRoots(query("2026-08-20", "2026-08-20", 4))),
        )
    }

    @Test
    fun rootListUsesBoundedBatchedQueriesWithoutDetailHydrationOrWrites() {
        insertActivitySnapshot("activity-snapshot", "Frozen Activity", null)
        insertSequenceSnapshot("sequence-snapshot", "Frozen Sequence", null)
        repeat(3) { insertActivity("activity-$it", at((100 + it).toLong())) }
        repeat(3) { insertSequence("sequence-$it", at((200 + it).toLong()), SequenceExecutionStatus.COMPLETED) }

        observedSql.clear()
        repository.getCompletedRoots(query("2026-08-20", "2026-08-20", 4))
        val queries = observedSql.map(String::lowercase)

        assertEquals(1, queries.count { "from activity_executions" in it && "limit" in it })
        assertEquals(1, queries.count { "from sequence_executions" in it && "limit" in it })
        assertEquals(1, queries.count { "from activity_snapshots" in it && " in (" in it })
        assertEquals(1, queries.count { "from sequence_snapshots" in it && " in (" in it })
        assertFalse(
            queries.any {
                "sequence_occurrences" in it ||
                    "sequence_intervals" in it ||
                    "activity_template_fields" in it
            },
        )
        assertFalse(queries.any { it.startsWith("insert") || it.startsWith("update") || it.startsWith("delete") })
    }

    @Test
    fun detailUsesOneBatchedSourceDisplayLookupPerMetadataKindAndDoesNotWrite() {
        val execution = insertSourceDisplayFixture()
        observedSql.clear()
        repository.getActivityDetail(execution.id)
        val queries = observedSql.map(String::lowercase)

        assertEquals(1, queries.count { "from activity_template_fields" in it })
        assertEquals(1, queries.count { "from activity_template_category_options" in it })
        assertFalse(queries.any { it.startsWith("insert") || it.startsWith("update") || it.startsWith("delete") })
    }

    @Test
    fun canonicalCorrectionReloadsWithStableExecutionAndReplacementSnapshot() {
        reopenFileDatabase("history-correction-reload.db")
        insertNoLiveTemplate("source")
        val commands = commands("correction")
        val original =
            commands.addManualNoLive(
                ActivityEntrySource.Template(ActivityTemplateId("source")),
                at(100),
                at(101),
                ZoneOffset.UTC,
            )
        val corrected =
            commands.correctHistory(
                original.id,
                ActivityHistoryCorrection(
                    original.updatedAt,
                    ActivityHistoryTimeCorrection.NoLive(requireNotNull(original.completedAt)),
                    ZoneOffset.UTC,
                    original.values,
                    "Corrected comment",
                ),
                at(102),
            )
        val replacementSnapshot = corrected.snapshot.id

        reopenFileDatabase("history-correction-reload.db", deleteFirst = false)
        val root =
            repository.getCompletedRoots(query("2026-08-20", "2026-08-20", 10)).single()
                as CompletedActivityHistoryRoot

        assertEquals(original.id, root.executionId)
        assertEquals(replacementSnapshot, root.snapshotId)
        assertEquals("Corrected comment", root.shortComment)
        val detail = requireNotNull(repository.getActivityDetail(original.id))
        assertEquals(replacementSnapshot, detail.root.snapshotId)
        assertEquals("Corrected comment", detail.root.shortComment)
        assertEquals("source", detail.root.title)
        assertEquals(TimeTrackingMode.NO_LIVE_TRACKING, detail.root.timeTrackingMode)
        assertNull(detail.root.activeDuration)
    }

    @Test
    fun canonicalPlanFulfillmentSurvivesHistoryDeleteAndReload() {
        reopenFileDatabase("history-plan-delete-reload.db")
        insertNoLiveTemplate("source")
        val plans = plans("plan")
        val plan =
            plans.createActivityPlanFromTemplate(
                ActivityTemplateId("source"),
                PlanTarget.FloatingDay(LocalDate.parse("2026-08-20")),
                at(10),
            )
        val commands = commands("delete")
        val execution =
            commands.addManualNoLive(
                ActivityEntrySource.Plan(plan.id),
                at(100),
                at(101),
                ZoneOffset.UTC,
            )
        val fulfilled = requireNotNull(plans.getPlan(plan.id))
        assertEquals(PlanEntryStatus.FULFILLED, fulfilled.status)
        assertEquals(execution.id, fulfilled.fulfilledActivityExecutionId)

        commands.softDeleteHistory(execution.id, execution.updatedAt, at(102))
        reopenFileDatabase("history-plan-delete-reload.db", deleteFirst = false)
        val reloadedPlan = requireNotNull(plans("reloaded").getPlan(plan.id))
        val executionBeforeRead = database.activityExecutionDao().getById(execution.id.value)
        val snapshotsBeforeRead = count("activity_snapshots")
        val activeSessionBeforeRead = database.activeSessionDao().get()
        val roots = repository.getCompletedRoots(query("2026-08-20", "2026-08-20", 10))

        assertFalse(
            roots
                .filterIsInstance<CompletedActivityHistoryRoot>()
                .any { it.executionId == execution.id },
        )
        assertEquals(fulfilled, reloadedPlan)
        assertEquals(PlanEntryStatus.FULFILLED, reloadedPlan.status)
        assertEquals(execution.id, reloadedPlan.fulfilledActivityExecutionId)
        assertEquals(executionBeforeRead, database.activityExecutionDao().getById(execution.id.value))
        assertEquals(snapshotsBeforeRead, count("activity_snapshots"))
        assertEquals(activeSessionBeforeRead, database.activeSessionDao().get())
    }

    private fun rootIds(roots: List<com.alexandr5476.lifetracing.domain.CompletedHistoryRoot>) =
        roots.map {
            when (it) {
                is CompletedActivityHistoryRoot -> it.executionId.value
                is CompletedSequenceHistoryRoot -> it.executionId.value
            }
        }

    private fun reopenFileDatabase(
        name: String,
        deleteFirst: Boolean = true,
    ) {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (deleteFirst) context.deleteDatabase(name)
        database = LifeTracingDatabase.builder(context, name).allowMainThreadQueries().build()
        repository = HistoryReadRepository(database)
        fileDatabaseName = name
    }

    private fun insertNoLiveTemplate(id: String) {
        database.statisticsSeriesDao().insert(StatisticsSeriesEntity("$id-series", "ACTIVITY", id, 0, null))
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(id, id, null, "NO_LIVE_TRACKING", null, "$id-series", 1, 0, 0, null, null),
                ActivityTemplateSettingsEntity(id),
                userState = ActivityTemplateUserStateEntity(id, null, 0),
            ),
        )
    }

    private fun commands(prefix: String): ActivityCommandRepository {
        var next = 0

        fun id(kind: String) = "$prefix-$kind-${next++}"
        return ActivityCommandRepository(
            database,
            LiveSessionRepository(
                database,
                { ActivityExecutionId(id("live")) },
                {
                    com.alexandr5476.lifetracing.domain
                        .ActivityExecutionPauseId(id("pause"))
                },
                { SequenceExecutionId(id("sequence")) },
                { SequenceOccurrenceId(id("occurrence")) },
                { SequenceIntervalId(id("interval")) },
            ),
            ActivitySnapshotFactory(
                { ActivitySnapshotId(id("snapshot")) },
                { ActivitySnapshotFieldId(id("field")) },
                { ActivitySnapshotCategoryOptionId(id("option")) },
            ),
            { ActivityExecutionId(id("execution")) },
        )
    }

    private fun plans(prefix: String): PlanRepository {
        var next = 0

        fun id(kind: String) = "$prefix-$kind-${next++}"
        return PlanRepository(
            database,
            { PlanEntryId(id("entry")) },
            ActivitySnapshotFactory(
                { ActivitySnapshotId(id("activity-snapshot")) },
                { ActivitySnapshotFieldId(id("activity-field")) },
                { ActivitySnapshotCategoryOptionId(id("activity-option")) },
            ),
            SequenceSnapshotFactory(
                { SequenceSnapshotId(id("sequence-snapshot")) },
                { SequenceSnapshotFieldId(id("sequence-field")) },
                { SequenceSnapshotCategoryOptionId(id("sequence-option")) },
                { SequenceSnapshotNodeId(id("sequence-node")) },
            ),
            com.alexandr5476.lifetracing.domain
                .CurrentZoneIdProvider { ZoneOffset.UTC },
        )
    }

    private fun insertSourceDisplayFixture(): ActivityExecution {
        database.statisticsSeriesDao().insert(StatisticsSeriesEntity("display-series", "ACTIVITY", "Display", 0, null))
        database.activityTemplateDao().insertAggregate(
            ActivityTemplateAggregateEntity(
                ActivityTemplateEntity(
                    "display-template",
                    "Display",
                    null,
                    "NO_LIVE_TRACKING",
                    null,
                    "display-series",
                    1,
                    0,
                    0,
                    null,
                    null,
                ),
                ActivityTemplateSettingsEntity("display-template"),
                listOf(
                    ActivityTemplateFieldEntity(
                        "display-number-source",
                        "display-template",
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
                        "display-category-source",
                        "display-template",
                        1,
                        "Creation category",
                        "CATEGORY",
                        null,
                        null,
                        null,
                        "display-option-source",
                        null,
                        false,
                        0,
                        0,
                        null,
                    ),
                    ActivityTemplateFieldEntity(
                        "display-text-source",
                        "display-template",
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
                listOf(
                    ActivityTemplateCategoryOptionEntity(
                        "display-option-source",
                        "display-category-source",
                        0,
                        "Creation option",
                    ),
                ),
            ),
        )
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(
                    "display-snapshot",
                    "Frozen Activity",
                    "Frozen note",
                    "NO_LIVE_TRACKING",
                    null,
                    "display-template",
                    1,
                    "display-series",
                    false,
                    1,
                ),
                ActivitySnapshotSettingsEntity("display-snapshot"),
                listOf(
                    ActivitySnapshotFieldEntity(
                        "display-number-snapshot",
                        "display-snapshot",
                        "display-number-source",
                        0,
                        "Creation number",
                        null,
                        "NUMBER",
                        "km",
                        1,
                        7,
                        null,
                        null,
                        false,
                    ),
                    ActivitySnapshotFieldEntity(
                        "display-category-snapshot",
                        "display-snapshot",
                        "display-category-source",
                        1,
                        "Creation category",
                        null,
                        "CATEGORY",
                        null,
                        null,
                        null,
                        "display-option-snapshot",
                        null,
                        false,
                    ),
                    ActivitySnapshotFieldEntity(
                        "display-local-field-snapshot",
                        "display-snapshot",
                        "display-text-source",
                        2,
                        "Creation text",
                        "Local field",
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
                        "display-option-snapshot",
                        "display-category-snapshot",
                        "display-option-source",
                        0,
                        "Creation option",
                        null,
                    ),
                    ActivitySnapshotCategoryOptionEntity(
                        "display-local-option",
                        "display-category-snapshot",
                        null,
                        1,
                        "Creation local option",
                        "Local option",
                    ),
                ),
            ),
        )
        val execution = insertActivity("display-execution", at(700), snapshotId = "display-snapshot")
        database.activityExecutionDao().upsertValue(
            ActivityExecutionFieldValueEntity(execution.id.value, "display-number-snapshot", 0, null, null),
        )
        database.activityExecutionDao().upsertValue(
            ActivityExecutionFieldValueEntity(
                execution.id.value,
                "display-category-snapshot",
                null,
                "display-option-snapshot",
                null,
            ),
        )
        return execution
    }

    private fun categoryActual(
        detail: com.alexandr5476.lifetracing.domain.ActivityHistoryDetail,
    ): ActivityHistoryActualValue.Category = detail.fields[1].actualValue as ActivityHistoryActualValue.Category

    private fun assertFrozenDisplayFixtureFacts(detail: com.alexandr5476.lifetracing.domain.ActivityHistoryDetail) {
        assertEquals(CustomFieldType.NUMBER, detail.fields[0].type)
        assertEquals("km", detail.fields[0].unit)
        assertEquals(1, detail.fields[0].displayPrecision)
        assertEquals(ActivityHistoryConfiguredValue.Number(7), detail.fields[0].configuredValue)
        assertEquals(ActivityHistoryActualValue.Number(0), detail.fields[0].actualValue)
        assertEquals("display-option-snapshot", categoryActual(detail).optionId.value)
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

    private fun insertTimedActivitySnapshot(id: String) {
        database.activitySnapshotDao().insertAggregate(
            ActivitySnapshotAggregateEntity(
                ActivitySnapshotEntity(id, id, null, "STOPWATCH", null, null, null, null, false, 0),
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
        completedAt: Instant,
        zoneId: ZoneOffset = ZoneOffset.UTC,
        snapshotId: String = "activity-snapshot",
    ): ActivityExecution {
        val snapshot = requireNotNull(database.activitySnapshotDao().getAggregate(snapshotId)).toDomain()
        val execution =
            ActivityExecutionFactory { ActivityExecutionId(id) }
                .createManualNoLiveHistory(snapshot, completedAt, completedAt.plusMillis(1), zoneId)
        database.activityExecutionDao().insertAggregate(execution.toEntityAggregate())
        return execution
    }

    private fun insertRunningActivity(
        id: String,
        startedAt: Instant,
    ) {
        val snapshot = requireNotNull(database.activitySnapshotDao().getAggregate("timed-snapshot")).toDomain()
        val execution =
            ActivityExecutionFactory { ActivityExecutionId(id) }
                .startTimed(snapshot, startedAt, startedAt, ZoneOffset.UTC)
        database.activityExecutionDao().insertAggregate(execution.toEntityAggregate())
    }

    private fun insertSequence(
        id: String,
        at: Instant,
        status: SequenceExecutionStatus,
    ) {
        val startedAt = at.minusMillis(1)
        val terminal = status == SequenceExecutionStatus.COMPLETED || status == SequenceExecutionStatus.ENDED_EARLY
        val execution =
            SequenceExecution(
                SequenceExecutionId(id),
                SequenceSnapshotId("sequence-snapshot"),
                null,
                status,
                startedAt,
                if (terminal) at else null,
                if (terminal) Duration.ZERO else null,
                if (terminal) Duration.ofMillis(1) else null,
                if (terminal) Duration.ofMillis(1) else null,
                ZoneOffset.UTC,
                0,
                startedAt.atZone(ZoneOffset.UTC).toLocalDate(),
                null,
                startedAt,
                at,
            )
        database.sequenceExecutionDao().insertAggregate(execution.toEntityAggregate())
    }

    private fun insertSequenceChild(
        id: String,
        sequenceId: String,
        occurrenceId: String,
        completedAt: Instant,
    ) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sequence_occurrences (id, sequence_execution_id, source_sequence_snapshot_node_id, activity_snapshot_id, runtime_position, repeat_source_snapshot_node_id, repeat_iteration, status, entered_at_ms, completed_at_ms, completion_reason, is_runtime_added, is_deleted_from_history) VALUES (?, ?, NULL, 'activity-snapshot', 0, NULL, NULL, 'COMPLETED', ?, ?, NULL, 1, 0)",
            arrayOf<Any?>(
                occurrenceId,
                sequenceId,
                completedAt.minusMillis(1).toEpochMilli(),
                completedAt.toEpochMilli(),
            ),
        )
        val snapshot = requireNotNull(database.activitySnapshotDao().getAggregate("activity-snapshot")).toDomain()
        val execution =
            ActivityExecutionFactory { ActivityExecutionId(id) }
                .completeSequenceChildNoLive(
                    snapshot,
                    SequenceExecutionId(sequenceId),
                    com.alexandr5476.lifetracing.domain
                        .SequenceOccurrenceId(occurrenceId),
                    completedAt,
                    ZoneOffset.UTC,
                )
        database.activityExecutionDao().insertAggregate(execution.toEntityAggregate())
    }

    private fun at(offsetMillis: Long): Instant = Instant.parse("2026-08-20T00:00:00Z").plusMillis(offsetMillis)

    private fun count(table: String): Long =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
}
