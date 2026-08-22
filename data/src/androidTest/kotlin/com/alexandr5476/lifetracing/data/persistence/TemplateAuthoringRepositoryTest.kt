@file:Suppress("LongMethod", "LongParameterList")

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.Folder
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.LinkedStepPropagationMode
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.Tag
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TemplateLibraryPlacement
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TemplateAuthoringRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var repository: TemplateAuthoringRepository
    private lateinit var baseIds: TemplateAuthoringIds

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .build()
        baseIds = deterministicIds("test")
        repository = TemplateAuthoringRepository(database, baseIds)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun activityCreateNoOpPresentationSemanticAndReplacementFollowOneBoundary() {
        database.folderDao().insert(Folder(FolderId("folder"), "Folder", null, at(0), at(0)).toEntity())
        database.tagDao().insert(Tag(TagId("tag"), "Tag", at(0), at(0)).toEntity())
        val created =
            repository.createActivityTemplate(
                activityDraft(),
                TemplateLibraryPlacement(FolderId("folder"), setOf(TagId("tag"))),
                at(1),
            )

        assertEquals(1L, created.revision)
        assertEquals("ACTIVITY", database.statisticsSeriesDao().getById(created.statisticsSeriesId.value)?.kind)
        assertEquals(FolderId("folder"), created.folderId)
        assertEquals(setOf(TagId("tag")), created.tagIds)
        assertNull(database.activityTemplateDao().getUserState(created.id.value)?.pinnedRank)
        assertNull(database.activityTemplateDao().getUserState(created.id.value)?.lastUsedAtMs)
        assertEquals(0, count("activity_snapshots"))

        val unchanged = repository.saveActivityTemplate(created.id, 1, created.toAuthoringDraft(), at(2))
        assertEquals(created.updatedAt, unchanged.updatedAt)
        assertEquals(1L, unchanged.revision)

        val renamedDraft =
            unchanged.toAuthoringDraft().let { draft ->
                draft.copy(fields = listOf(draft.fields.single().copy(name = "Route distance")))
            }
        val presentation = repository.saveActivityTemplate(created.id, 1, renamedDraft, at(3))
        assertEquals(1L, presentation.revision)
        assertEquals(created.updatedAt, presentation.updatedAt)
        assertEquals("Route distance", presentation.fields.single().name)
        assertEquals(at(3), presentation.fields.single().updatedAt)

        val semantic =
            repository.saveActivityTemplate(
                created.id,
                1,
                presentation.toAuthoringDraft().copy(name = "Walking"),
                at(4),
            )
        assertEquals(2L, semantic.revision)
        assertEquals(at(4), semantic.updatedAt)
        assertEquals(created.statisticsSeriesId, semantic.statisticsSeriesId)
        assertEquals("Walking", database.statisticsSeriesDao().getById(created.statisticsSeriesId.value)?.displayName)
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveActivityTemplate(created.id, 1, semantic.toAuthoringDraft(), at(5))
        }

        val oldFieldId = semantic.fields.single().id
        val replacementDraft =
            semantic.toAuthoringDraft().copy(
                fields =
                    listOf(
                        semantic.toAuthoringDraft().fields.single().copy(
                            identity = DraftIdentity.New("replacement"),
                            unit = "m",
                        ),
                    ),
            )
        val replaced = repository.saveActivityTemplate(created.id, 2, replacementDraft, at(5))
        assertEquals(3L, replaced.revision)
        assertNotEquals(oldFieldId, replaced.fields.single { it.deletedAt == null }.id)
        assertNotNull(replaced.fields.single { it.id == oldFieldId }.deletedAt)
    }

    @Test
    fun optionLabelRenameIsPresentationOnlyAndCreateCollisionRollsBack() {
        val created = repository.createActivityTemplate(categoryActivityDraft(), createdAt = at(1))
        val draft = created.toAuthoringDraft()
        val renamedOption =
            draft.fields
                .single()
                .categoryOptions
                .single()
                .copy(label = "Hard")
        val renamed =
            repository.saveActivityTemplate(
                created.id,
                1,
                draft.copy(
                    fields = listOf(draft.fields.single().copy(categoryOptions = listOf(renamedOption))),
                ),
                at(2),
            )
        assertEquals(1L, renamed.revision)
        assertEquals(created.updatedAt, renamed.updatedAt)
        assertEquals(
            "Hard",
            renamed.fields
                .single()
                .categoryOptions
                .single()
                .label,
        )

        val colliding =
            TemplateAuthoringRepository(
                database,
                baseIds.copy(nextStatisticsSeriesId = { created.statisticsSeriesId }),
            )
        val templateCount = count("activity_templates")
        assertThrows(SQLiteConstraintException::class.java) {
            colliding.createActivityTemplate(activityDraft(), createdAt = at(3))
        }
        assertEquals(templateCount, count("activity_templates"))
    }

    @Test
    fun sequenceCreateReorderLocalSourceRoundTripsAndSaveAsNewPreserveHistory() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequence = repository.createSequenceTemplate(sequenceDraft(source.id), createdAt = at(2))
        val snapshots = snapshots(sequence.id)
        val originalIds = snapshots.keys
        val sourceStep = sequence.nodes.flatMap { it.stepIds() }.first()
        val sourceSnapshot = repository.getStepSnapshot(sequence.id, sourceStep)!!
        assertEquals(source.id, sourceSnapshot.sourceTemplateId)
        assertEquals(source.statisticsSeriesId, sourceSnapshot.statisticsSeriesId)
        assertFalse(sourceSnapshot.locallyModified)
        val localStep = sequence.nodes.flatMap { it.stepIds() }.last()
        assertNull(repository.getStepSnapshot(sequence.id, localStep)?.sourceTemplateId)
        assertNull(repository.getStepSnapshot(sequence.id, localStep)?.statisticsSeriesId)

        val reorderedDraft =
            sequence.toAuthoringDraft(snapshots).let { draft ->
                draft.copy(
                    nodes =
                        draft.nodes.reversed().mapIndexed { position, node ->
                            when (node) {
                                is SequenceNodeDraft.Step ->
                                    SequenceNodeDraft.Step(node.value.copy(position = position))
                                is SequenceNodeDraft.Repeat ->
                                    SequenceNodeDraft.Repeat(node.value.copy(position = position))
                            }
                        },
                )
            }
        val reordered = repository.saveSequenceTemplate(sequence.id, 1, reorderedDraft, at(3))
        assertEquals(2L, reordered.revision)
        assertEquals(originalIds, snapshots(reordered.id).keys)
        val noOp =
            repository.saveSequenceTemplate(
                reordered.id,
                2,
                reordered.toAuthoringDraft(snapshots(reordered.id)),
                at(4),
            )
        assertEquals(reordered.updatedAt, noOp.updatedAt)
        assertEquals(2L, noOp.revision)

        val oldSnapshotId = repository.getStepSnapshot(sequence.id, sourceStep)!!.id
        val locallyEdited =
            repository.saveStepConfiguration(
                sequence.id,
                sourceStep,
                2,
                repository
                    .getStepSnapshot(sequence.id, sourceStep)!!
                    .toAuthoringDraft()
                    .copy(
                        name = "Route walk",
                        fields =
                            sourceSnapshot.toAuthoringDraft().fields.map {
                                it.copy(localNameOverride = "Route distance")
                            },
                    ),
                at(4),
            )
        val localSnapshot = repository.getStepSnapshot(sequence.id, sourceStep)!!
        assertEquals(3L, locallyEdited.revision)
        assertTrue(localSnapshot.locallyModified)
        assertEquals(source.id, localSnapshot.sourceTemplateId)
        assertEquals(source.statisticsSeriesId, localSnapshot.statisticsSeriesId)
        assertEquals("Route distance", localSnapshot.fields.single().localNameOverride)
        assertNull(database.activitySnapshotDao().getById(oldSnapshotId.value))

        val updatedSource =
            repository.updateSourceTemplateFromStep(sequence.id, sourceStep, 3, 1, at(5))
        assertEquals("Route walk", updatedSource.name)
        assertEquals("Route distance", updatedSource.fields.single().name)
        assertEquals(2L, updatedSource.revision)
        assertEquals(localSnapshot.id, repository.getStepSnapshot(sequence.id, sourceStep)?.id)
        assertEquals(3L, repository.getSequenceTemplate(sequence.id)?.revision)

        val refreshed = repository.updateStepFromSourceTemplate(sequence.id, sourceStep, 3, at(6))
        val refreshedSnapshot = repository.getStepSnapshot(sequence.id, sourceStep)!!
        assertEquals(4L, refreshed.revision)
        assertEquals(2L, refreshedSnapshot.sourceRevision)
        assertFalse(refreshedSnapshot.locallyModified)
        assertEquals("Route walk", refreshedSnapshot.name)
        assertNull(refreshedSnapshot.fields.single().localNameOverride)
        assertEquals("Route distance", refreshedSnapshot.fields.single().nameAtCreation)

        val savedAsNew = repository.saveStepAsNewActivityTemplate(sequence.id, sourceStep, 4, savedAt = at(7))
        val relinked = repository.getStepSnapshot(sequence.id, sourceStep)!!
        assertNotEquals(source.id, savedAsNew.id)
        assertNotEquals(source.statisticsSeriesId, savedAsNew.statisticsSeriesId)
        assertEquals(savedAsNew.id, relinked.sourceTemplateId)
        assertEquals(1L, relinked.sourceRevision)
        assertFalse(relinked.locallyModified)
        assertEquals("Route walk", repository.getActivityTemplate(source.id)?.name)
    }

    @Test
    fun archivedSourceBlocksBothUpdateDirectionsButLocalEditAndSaveAsNewRemainAvailable() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequence = repository.createSequenceTemplate(singleStepSequence(source.id, "orphan"), createdAt = at(2))
        val step = sequence.nodes.flatMap { it.stepIds() }.single()
        database.activityTemplateDao().archive(source.id.value, at(3).toEpochMilli())

        repository.saveStepConfiguration(
            sequence.id,
            step,
            1,
            repository.getStepSnapshot(sequence.id, step)!!.toAuthoringDraft().copy(name = "Orphan local"),
            at(4),
        )
        assertThrows(IllegalArgumentException::class.java) {
            repository.updateStepFromSourceTemplate(sequence.id, step, 2, at(5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.updateSourceTemplateFromStep(sequence.id, step, 2, 1, at(5))
        }
        val replacement = repository.saveStepAsNewActivityTemplate(sequence.id, step, 2, savedAt = at(5))
        assertEquals(replacement.id, repository.getStepSnapshot(sequence.id, step)?.sourceTemplateId)
        assertEquals("Orphan local", replacement.name)
    }

    @Test
    fun propagationTargetsOnlyMutableStepsAndPruningRetainsPlanAndExecutionReferences() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequenceA = repository.createSequenceTemplate(singleStepSequence(source.id, "A"), createdAt = at(2))
        val sequenceB = repository.createSequenceTemplate(singleStepSequence(source.id, "B"), createdAt = at(2))
        val stepA = sequenceA.nodes.flatMap { it.stepIds() }.single()
        val stepB = sequenceB.nodes.flatMap { it.stepIds() }.single()
        val oldA = repository.getStepSnapshot(sequenceA.id, stepA)!!
        val oldB = repository.getStepSnapshot(sequenceB.id, stepB)!!
        repository.saveStepConfiguration(
            sequenceA.id,
            stepA,
            1,
            oldA.toAuthoringDraft().copy(name = "Local A"),
            at(3),
        )
        val localA = repository.getStepSnapshot(sequenceA.id, stepA)!!
        insertPlan("plan", oldB.id.value)
        insertExecution("execution", localA)
        repository.saveActivityTemplate(
            source.id,
            1,
            source.toAuthoringDraft().copy(name = "Walking updated"),
            at(4),
        )
        StatisticsRepository(database) { StatisticsSeriesId("split-series") }
            .startNewActivityStatisticsSeries(source.id, at(5))

        val onlyUnmodified =
            repository.propagateActivityTemplateToLinkedSteps(
                source.id,
                3,
                LinkedStepPropagationMode.ONLY_UNMODIFIED,
                at(6),
            )
        assertEquals(1, onlyUnmodified.updatedSteps)
        assertEquals(1, onlyUnmodified.updatedSequences)
        assertEquals(1, onlyUnmodified.skippedLocallyModified)
        assertEquals(localA.id, repository.getStepSnapshot(sequenceA.id, stepA)?.id)
        assertNotEquals(oldB.id, repository.getStepSnapshot(sequenceB.id, stepB)?.id)
        assertEquals(
            StatisticsSeriesId("split-series"),
            repository.getStepSnapshot(sequenceB.id, stepB)?.statisticsSeriesId,
        )
        assertNotNull(database.activitySnapshotDao().getById(oldB.id.value))
        assertEquals(
            source.statisticsSeriesId.value,
            database.activitySnapshotDao().getById(oldB.id.value)?.statisticsSeriesId,
        )
        assertEquals(oldB.id.value, database.planEntryDao().getById("plan")?.activitySnapshotId)

        val all =
            repository.propagateActivityTemplateToLinkedSteps(
                source.id,
                3,
                LinkedStepPropagationMode.ALL,
                at(7),
            )
        assertEquals(2, all.updatedSteps)
        assertEquals(2, all.updatedSequences)
        assertFalse(repository.getStepSnapshot(sequenceA.id, stepA)!!.locallyModified)
        assertNotNull(database.activitySnapshotDao().getById(localA.id.value))
        assertEquals(
            source.statisticsSeriesId.value,
            database.activitySnapshotDao().getById(localA.id.value)?.statisticsSeriesId,
        )
        assertEquals(localA.id.value, database.activityExecutionDao().getById("execution")?.snapshotId)
        assertEquals(3L, repository.getSequenceTemplate(sequenceA.id)?.revision)
        assertEquals(3L, repository.getSequenceTemplate(sequenceB.id)?.revision)
    }

    @Test
    fun replacementRetainsSnapshotUntilEveryMutableStepReferenceMoves() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequence =
            repository.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Shared snapshot",
                    null,
                    nodes = listOf(sourceStep("one", 0, source.id), sourceStep("two", 1, source.id)),
                ),
                createdAt = at(2),
            )
        val steps = sequence.nodes.flatMap { it.stepIds() }
        val shared = repository.getStepSnapshot(sequence.id, steps.first())!!
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sequence_nodes SET activity_snapshot_id = ? WHERE id = ?",
            arrayOf(shared.id.value, steps.last().value),
        )

        repository.saveStepConfiguration(
            sequence.id,
            steps.first(),
            1,
            shared.toAuthoringDraft().copy(name = "First local"),
            at(3),
        )
        assertNotNull(database.activitySnapshotDao().getById(shared.id.value))
        assertEquals(shared.id, repository.getStepSnapshot(sequence.id, steps.last())?.id)

        repository.saveStepConfiguration(
            sequence.id,
            steps.last(),
            2,
            shared.toAuthoringDraft().copy(name = "Second local"),
            at(4),
        )
        assertNull(database.activitySnapshotDao().getById(shared.id.value))
    }

    @Test
    fun bulkCollisionRollsBackSnapshotsNodeRepointsAndSequenceRevisions() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequence =
            repository.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Collision",
                    null,
                    nodes =
                        listOf(
                            sourceStep("one", 0, source.id),
                            sourceStep("two", 1, source.id),
                        ),
                ),
                createdAt = at(2),
            )
        val before = snapshots(sequence.id).keys
        repository.saveActivityTemplate(
            source.id,
            1,
            source.toAuthoringDraft().copy(name = "Collision updated"),
            at(3),
        )
        var calls = 0
        val colliding =
            TemplateAuthoringRepository(
                database,
                baseIds.copy(
                    nextActivitySnapshotId = {
                        calls++
                        ActivitySnapshotId("bulk-collision")
                    },
                ),
            )

        assertThrows(SQLiteConstraintException::class.java) {
            colliding.propagateActivityTemplateToLinkedSteps(
                source.id,
                2,
                LinkedStepPropagationMode.ALL,
                at(4),
            )
        }
        assertEquals(2, calls)
        assertEquals(before, snapshots(sequence.id).keys)
        assertEquals(1L, repository.getSequenceTemplate(sequence.id)?.revision)
        assertNull(database.activitySnapshotDao().getById("bulk-collision"))
    }

    @Test
    fun largePropagationUpdatesOneThousandStepsAndEachSequenceRevisionOnce() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        repeat(20) { sequenceIndex ->
            repository.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Sequence $sequenceIndex",
                    null,
                    nodes =
                        List(50) { stepIndex ->
                            sourceStep("$sequenceIndex-$stepIndex", stepIndex, source.id)
                        },
                ),
                createdAt = at(2),
            )
        }
        repository.saveActivityTemplate(
            source.id,
            1,
            source.toAuthoringDraft().copy(shortComment = "Updated"),
            at(3),
        )

        val result =
            repository.propagateActivityTemplateToLinkedSteps(
                source.id,
                2,
                LinkedStepPropagationMode.ALL,
                at(4),
            )
        assertEquals(1_000, result.updatedSteps)
        assertEquals(20, result.updatedSequences)
        assertEquals(
            20,
            database
                .sequenceTemplateDao()
                .getLinkedStepOwners(source.id.value)
                .map {
                    it.sequenceTemplateId
                }.distinct()
                .size,
        )
        assertTrue(
            database.sequenceTemplateDao().getLinkedStepOwners(source.id.value).all { it.sequenceRevision == 2L },
        )
    }

    private fun activityDraft() =
        ActivityTemplateDraft(
            "Walking",
            "Outside",
            TimeTrackingMode.STOPWATCH,
            null,
            fields =
                listOf(
                    ActivityFieldDraft(
                        DraftIdentity.New("distance"),
                        0,
                        "Distance",
                        CustomFieldType.NUMBER,
                        "km",
                        3,
                        1_000,
                        isMainValue = true,
                    ),
                ),
        )

    private fun categoryActivityDraft() =
        ActivityTemplateDraft(
            "Rating",
            null,
            TimeTrackingMode.NO_LIVE_TRACKING,
            null,
            fields =
                listOf(
                    ActivityFieldDraft(
                        DraftIdentity.New("difficulty"),
                        0,
                        "Difficulty",
                        CustomFieldType.CATEGORY,
                        defaultCategoryOption = DraftIdentity.New("easy"),
                        categoryOptions =
                            listOf(
                                com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft(
                                    DraftIdentity.New("easy"),
                                    0,
                                    "Easy",
                                ),
                            ),
                    ),
                ),
        )

    private fun sequenceDraft(sourceId: ActivityTemplateId) =
        SequenceTemplateDraft(
            "Morning",
            null,
            nodes =
                listOf(
                    sourceStep("source-step", 0, sourceId),
                    SequenceNodeDraft.Repeat(
                        SequenceRepeatBlockDraft(
                            DraftIdentity.New("repeat"),
                            1,
                            2,
                            listOf(
                                ActivityStepDraft(
                                    DraftIdentity.New("local-step"),
                                    0,
                                    StepActivityDraft.Local(
                                        ActivitySnapshotDraft(
                                            "Local",
                                            null,
                                            TimeTrackingMode.NO_LIVE_TRACKING,
                                            null,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
        )

    private fun singleStepSequence(
        sourceId: ActivityTemplateId,
        suffix: String,
    ) = SequenceTemplateDraft("Sequence $suffix", null, nodes = listOf(sourceStep("step-$suffix", 0, sourceId)))

    private fun sourceStep(
        key: String,
        position: Int,
        sourceId: ActivityTemplateId,
    ) = SequenceNodeDraft.Step(
        ActivityStepDraft(DraftIdentity.New(key), position, StepActivityDraft.FromTemplate(sourceId)),
    )

    private fun snapshots(sequenceId: SequenceTemplateId): Map<ActivitySnapshotId, ActivityConfigSnapshot> {
        val sequence = repository.getSequenceTemplate(sequenceId)!!
        return sequence.nodes.flatMap { it.stepIds() }.associate { stepId ->
            val snapshot = repository.getStepSnapshot(sequenceId, stepId)!!
            snapshot.id to snapshot
        }
    }

    private fun com.alexandr5476.lifetracing.domain.SequenceNode.stepIds(): List<SequenceNodeId> =
        when (this) {
            is com.alexandr5476.lifetracing.domain.ActivityStep -> listOf(id)
            is com.alexandr5476.lifetracing.domain.SequenceRepeatBlock -> children.map { it.id }
        }

    private fun insertPlan(
        id: String,
        snapshotId: String,
    ) {
        database.planEntryDao().insert(
            PlanEntryEntity(
                id,
                "ACTIVITY",
                null,
                null,
                null,
                snapshotId,
                null,
                "DAY",
                "2026-08-23",
                null,
                null,
                null,
                null,
                "PLANNED",
                null,
                null,
                0,
                0,
                null,
                null,
            ),
        )
    }

    private fun insertExecution(
        id: String,
        snapshot: ActivityConfigSnapshot,
    ) {
        database.activityExecutionDao().insertAggregate(
            ActivityExecutionAggregateEntity(
                ActivityExecutionEntity(
                    id,
                    snapshot.id.value,
                    "STANDALONE",
                    null,
                    null,
                    null,
                    snapshot.statisticsSeriesId?.value,
                    "COMPLETED",
                    1_000,
                    2_000,
                    1_000,
                    "UTC",
                    0,
                    "1970-01-01",
                    null,
                    null,
                    1_000,
                    2_000,
                ),
            ),
        )
    }

    private fun count(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            check(it.moveToFirst())
            it.getInt(0)
        }

    private fun deterministicIds(prefix: String): TemplateAuthoringIds {
        var next = 0

        fun id(kind: String) = "$prefix-$kind-${next++}"
        return TemplateAuthoringIds(
            { ActivityTemplateId(id("activity")) },
            { SequenceTemplateId(id("sequence")) },
            { StatisticsSeriesId(id("series")) },
            { ActivityTemplateFieldId(id("activity-field")) },
            { CategoryOptionId(id("activity-option")) },
            { SequenceTemplateFieldId(id("sequence-field")) },
            {
                com.alexandr5476.lifetracing.domain
                    .SequenceTemplateCategoryOptionId(id("sequence-option"))
            },
            { SequenceNodeId(id("node")) },
            { ActivitySnapshotId(id("snapshot")) },
            { ActivitySnapshotFieldId(id("snapshot-field")) },
            { ActivitySnapshotCategoryOptionId(id("snapshot-option")) },
        )
    }

    private fun at(second: Long): Instant = Instant.ofEpochSecond(second)
}
