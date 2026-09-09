@file:Suppress("LongMethod", "LongParameterList")

package com.alexandr5476.lifetracing.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDisplayResolver
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
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class TemplateAuthoringRepositoryTest {
    private lateinit var database: LifeTracingDatabase
    private lateinit var repository: TemplateAuthoringRepository
    private lateinit var baseIds: TemplateAuthoringIds
    private val observedSql = mutableListOf<String>()

    @Before
    fun setUp() {
        database =
            LifeTracingDatabase
                .inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
                .allowMainThreadQueries()
                .setQueryCallback({ sql, _ -> observedSql += sql }, Executor { it.run() })
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
                presentation.toAuthoringDraft().copy(name = "Hiking"),
                at(4),
            )
        assertEquals(2L, semantic.revision)
        assertEquals(at(4), semantic.updatedAt)
        assertEquals(created.statisticsSeriesId, semantic.statisticsSeriesId)
        assertEquals("Hiking", database.statisticsSeriesDao().getById(created.statisticsSeriesId.value)?.displayName)
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
        val unitReplacementId = replaced.fields.single { it.deletedAt == null }.id
        assertNotEquals(oldFieldId, unitReplacementId)
        assertNotNull(replaced.fields.single { it.id == oldFieldId }.deletedAt)

        val typeReplaced =
            repository.saveActivityTemplate(
                created.id,
                3,
                replaced.toAuthoringDraft().let { current ->
                    current.copy(
                        fields =
                            listOf(
                                current.fields.single().copy(
                                    identity = DraftIdentity.New("type-replacement"),
                                    type = CustomFieldType.TEXT,
                                    unit = null,
                                    displayPrecision = null,
                                    defaultNumberScaled = null,
                                    defaultText = "note",
                                    isMainValue = false,
                                ),
                            ),
                    )
                },
                at(6),
            )
        assertEquals(4L, typeReplaced.revision)
        assertNotEquals(unitReplacementId, typeReplaced.fields.single { it.deletedAt == null }.id)
        assertNotNull(typeReplaced.fields.single { it.id == unitReplacementId }.deletedAt)
    }

    @Test
    fun retainedActivityFieldRejectsTypeAndUnitMutationWithoutPartialPersistence() {
        val number = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val numberDraft = number.toAuthoringDraft()

        assertThrows(IllegalArgumentException::class.java) {
            repository.saveActivityTemplate(
                number.id,
                number.revision,
                numberDraft.copy(fields = listOf(numberDraft.fields.single().copy(unit = "m"))),
                at(2),
            )
        }
        assertEquals(number, repository.getActivityTemplate(number.id))

        val unitReplacement =
            repository.saveActivityTemplate(
                number.id,
                number.revision,
                numberDraft.copy(
                    fields =
                        listOf(
                            numberDraft.fields.single().copy(
                                identity = DraftIdentity.New("unit-replacement"),
                                unit = "m",
                            ),
                        ),
                ),
                at(2),
            )
        assertEquals(2L, unitReplacement.revision)
        assertNotEquals(number.fields.single().id, unitReplacement.fields.single { it.deletedAt == null }.id)
        assertNotNull(unitReplacement.fields.single { it.id == number.fields.single().id }.deletedAt)

        val category = repository.createActivityTemplate(categoryActivityDraft(), createdAt = at(10))
        val categoryDraft = category.toAuthoringDraft()
        val retainedTypeMutation =
            categoryDraft.copy(
                fields =
                    listOf(
                        categoryDraft.fields.single().copy(
                            type = CustomFieldType.TEXT,
                            defaultCategoryOption = null,
                            defaultText = "note",
                            categoryOptions = emptyList(),
                        ),
                    ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            repository.saveActivityTemplate(category.id, category.revision, retainedTypeMutation, at(11))
        }
        val afterRejectedType = requireNotNull(repository.getActivityTemplate(category.id))
        assertEquals(category.revision, afterRejectedType.revision)
        assertEquals(category.fields, afterRejectedType.fields)

        val typeReplacement =
            repository.saveActivityTemplate(
                category.id,
                category.revision,
                retainedTypeMutation.copy(
                    fields =
                        listOf(
                            retainedTypeMutation.fields.single().copy(
                                identity = DraftIdentity.New("type-replacement"),
                            ),
                        ),
                ),
                at(11),
            )
        assertEquals(2L, typeReplacement.revision)
        assertEquals(CustomFieldType.TEXT, typeReplacement.fields.single { it.deletedAt == null }.type)
        assertNotEquals(category.fields.single().id, typeReplacement.fields.single { it.deletedAt == null }.id)
        assertNotNull(typeReplacement.fields.single { it.id == category.fields.single().id }.deletedAt)
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
        val renamedOptionId =
            renamed.fields
                .single()
                .categoryOptions
                .single()
                .id
        val added =
            repository.saveActivityTemplate(
                renamed.id,
                1,
                renamed.toAuthoringDraft().let { current ->
                    current.copy(
                        fields =
                            listOf(
                                current.fields.single().copy(
                                    categoryOptions =
                                        current.fields.single().categoryOptions +
                                            com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft(
                                                DraftIdentity.New("medium"),
                                                1,
                                                "Medium",
                                            ),
                                ),
                            ),
                    )
                },
                at(3),
            )
        val activeAddedOptions =
            added.fields
                .single()
                .categoryOptions
                .filterNot { it.isArchived }
        assertEquals(renamedOptionId, activeAddedOptions.single { it.label == "Hard" }.id)
        assertNotEquals(renamedOptionId, activeAddedOptions.single { it.label == "Medium" }.id)

        val removedSelected =
            repository.saveActivityTemplate(
                added.id,
                2,
                added.toAuthoringDraft().let { current ->
                    current.copy(
                        fields =
                            listOf(
                                current.fields.single().copy(
                                    defaultCategoryOption = null,
                                    categoryOptions =
                                        current.fields
                                            .single()
                                            .categoryOptions
                                            .filter { it.label == "Medium" },
                                ),
                            ),
                    )
                },
                at(4),
            )
        assertNull(removedSelected.fields.single().defaultCategoryOptionId)
        assertTrue(
            removedSelected.fields
                .single()
                .categoryOptions
                .single { it.id == renamedOptionId }
                .isArchived,
        )
        assertEquals(
            listOf("Medium"),
            removedSelected.fields
                .single()
                .categoryOptions
                .filterNot {
                    it.isArchived
                }.map { it.label },
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
        val authoringState = repository.getSequenceTemplateAuthoringState(sequence.id)!!
        val snapshots = authoringState.activitySnapshots
        assertEquals(sequence, authoringState.sequence)
        assertEquals(sequence.toAuthoringDraft(snapshots), authoringState.toAuthoringDraft())
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
    fun canonicalAuthoringReadHydratesSnapshotsByBindChunksRatherThanSteps() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequence =
            repository.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Large",
                    null,
                    nodes = List(901) { index -> sourceStep("step-$index", index, source.id) },
                ),
                createdAt = at(2),
            )

        observedSql.clear()
        val state = repository.getSequenceTemplateAuthoringState(sequence.id)!!

        assertEquals(901, state.activitySnapshots.size)
        assertEquals(901, state.toAuthoringDraft().nodes.size)
        assertEquals(
            2,
            observedSql.count { it.startsWith("SELECT * FROM activity_snapshots WHERE id IN") },
        )
    }

    @Test
    fun duplicateAndMoveCopiesFrozenSnapshotWithFreshPhysicalIdentitiesAndOverrides() {
        val source = repository.createActivityTemplate(categoryActivityDraft(), createdAt = at(1))
        val sequence =
            repository.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Duplicate",
                    null,
                    nodes =
                        listOf(
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    DraftIdentity.New("source"),
                                    0,
                                    StepActivityDraft.FromTemplate(source.id),
                                    SequenceStepOverrides(startCountdown = Duration.ofSeconds(3)),
                                ),
                            ),
                        ),
                ),
                createdAt = at(2),
            )
        val sourceStep = sequence.nodes.flatMap { it.stepIds() }.single()
        val frozen = repository.getStepSnapshot(sequence.id, sourceStep)!!
        repository.saveActivityTemplate(
            source.id,
            1,
            source.toAuthoringDraft().copy(name = "New template value"),
            at(3),
        )
        val draft = repository.getSequenceTemplateAuthoringState(sequence.id)!!.toAuthoringDraft()
        val duplicate =
            ActivityStepDraft(
                DraftIdentity.New("duplicate"),
                0,
                StepActivityDraft.Duplicate(sourceStep),
                SequenceStepOverrides(startCountdown = Duration.ofSeconds(3)),
            )
        val saved =
            repository.saveSequenceTemplate(
                sequence.id,
                1,
                draft.copy(
                    nodes =
                        listOf(
                            SequenceNodeDraft.Repeat(
                                SequenceRepeatBlockDraft(DraftIdentity.New("repeat"), 0, 2, listOf(duplicate)),
                            ),
                            SequenceNodeDraft.Step(
                                (draft.nodes.single() as SequenceNodeDraft.Step).value.copy(position = 1),
                            ),
                        ),
                ),
                at(4),
            )

        val copiedStep =
            (saved.nodes.first() as com.alexandr5476.lifetracing.domain.SequenceRepeatBlock)
                .children
                .single()
        val copied = repository.getStepSnapshot(saved.id, copiedStep.id)!!
        assertEquals(2L, saved.revision)
        assertNotEquals(sourceStep, copiedStep.id)
        assertNotEquals(frozen.id, copied.id)
        assertEquals(frozen.name, copied.name)
        assertEquals(frozen.sourceTemplateId, copied.sourceTemplateId)
        assertEquals(frozen.sourceRevision, copied.sourceRevision)
        assertEquals(frozen.statisticsSeriesId, copied.statisticsSeriesId)
        assertEquals(frozen.locallyModified, copied.locallyModified)
        assertEquals(frozen.settings, copied.settings)
        assertEquals(frozen.fields.map { it.sourceFieldId }, copied.fields.map { it.sourceFieldId })
        assertEquals(
            frozen.fields.flatMap { it.categoryOptions }.map { it.sourceOptionId },
            copied.fields.flatMap { it.categoryOptions }.map { it.sourceOptionId },
        )
        assertNotEquals(frozen.fields.single().id, copied.fields.single().id)
        assertNotEquals(
            frozen.fields
                .single()
                .categoryOptions
                .single()
                .id,
            copied.fields
                .single()
                .categoryOptions
                .single()
                .id,
        )
        assertEquals(SequenceStepOverrides(startCountdown = Duration.ofSeconds(3)), copiedStep.overrides)
    }

    @Test
    fun movingExistingStepIntoAndOutOfRepeatPreservesItsFrozenIdentityAndOverrides() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequence = repository.createSequenceTemplate(sequenceDraft(source.id), createdAt = at(2))
        val sourceStep = sequence.nodes.filterIsInstance<com.alexandr5476.lifetracing.domain.ActivityStep>().single()
        val sourceSnapshot = repository.getStepSnapshot(sequence.id, sourceStep.id)!!
        val initial = repository.getSequenceTemplateAuthoringState(sequence.id)!!.toAuthoringDraft()
        val top = (initial.nodes.filterIsInstance<SequenceNodeDraft.Step>().single()).value
        val repeat = (initial.nodes.filterIsInstance<SequenceNodeDraft.Repeat>().single()).value
        val intoRepeat =
            initial.copy(
                nodes =
                    listOf(
                        SequenceNodeDraft.Repeat(
                            repeat.copy(
                                position = 0,
                                children = listOf(top.copy(position = 0), repeat.children.single().copy(position = 1)),
                            ),
                        ),
                    ),
            )
        val movedInto = repository.saveSequenceTemplate(sequence.id, 1, intoRepeat, at(3))
        val movedSource =
            (movedInto.nodes.single() as com.alexandr5476.lifetracing.domain.SequenceRepeatBlock)
                .children
                .single { it.id == sourceStep.id }
        assertEquals(sourceSnapshot.id, movedSource.activitySnapshotId)
        assertEquals(sourceStep.overrides, movedSource.overrides)

        val inside = repository.getSequenceTemplateAuthoringState(sequence.id)!!.toAuthoringDraft()
        val insideRepeat = (inside.nodes.single() as SequenceNodeDraft.Repeat).value
        val sourceInside = insideRepeat.children.single { (it.identity as DraftIdentity.Existing).id == sourceStep.id }
        val localInside = insideRepeat.children.single { it !== sourceInside }
        val movedOut =
            repository.saveSequenceTemplate(
                sequence.id,
                2,
                inside.copy(
                    nodes =
                        listOf(
                            SequenceNodeDraft.Step(sourceInside.copy(position = 0)),
                            SequenceNodeDraft.Repeat(
                                insideRepeat.copy(position = 1, children = listOf(localInside.copy(position = 0))),
                            ),
                        ),
                ),
                at(4),
            )
        val movedOutSource =
            movedOut.nodes
                .filterIsInstance<com.alexandr5476.lifetracing.domain.ActivityStep>()
                .single()
        assertEquals(3L, movedOut.revision)
        assertEquals(sourceStep.id, movedOutSource.id)
        assertEquals(sourceSnapshot.id, movedOutSource.activitySnapshotId)
        assertEquals(sourceStep.overrides, movedOutSource.overrides)
        assertEquals(sourceSnapshot, repository.getStepSnapshot(sequence.id, sourceStep.id))
    }

    @Test
    fun duplicatePropagationAndLaterEditsKeepBothPhysicalOwnersIndependent() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequence = repository.createSequenceTemplate(singleStepSequence(source.id, "duplicate"), createdAt = at(2))
        val originalStep = sequence.nodes.flatMap { it.stepIds() }.single()
        val draft = repository.getSequenceTemplateAuthoringState(sequence.id)!!.toAuthoringDraft()
        val duplicated =
            repository.saveSequenceTemplate(
                sequence.id,
                1,
                draft.copy(
                    nodes =
                        draft.nodes +
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    DraftIdentity.New("duplicate"),
                                    1,
                                    StepActivityDraft.Duplicate(originalStep),
                                ),
                            ),
                ),
                at(3),
            )
        val duplicateStep = duplicated.nodes.flatMap { it.stepIds() }.single { it != originalStep }
        repository.saveActivityTemplate(source.id, 1, source.toAuthoringDraft().copy(name = "Propagated"), at(4))
        val propagation =
            repository.propagateActivityTemplateToLinkedSteps(
                source.id,
                2,
                LinkedStepPropagationMode.ONLY_UNMODIFIED,
                at(5),
            )
        assertEquals(2, propagation.updatedSteps)
        val original = repository.getStepSnapshot(sequence.id, originalStep)!!
        val duplicate = repository.getStepSnapshot(sequence.id, duplicateStep)!!
        assertEquals("Propagated", original.name)
        assertEquals("Propagated", duplicate.name)
        assertNotEquals(original.id, duplicate.id)

        repository.saveStepConfiguration(
            sequence.id,
            duplicateStep,
            3,
            duplicate.toAuthoringDraft().copy(name = "Duplicate only"),
            at(6),
        )
        assertEquals("Propagated", repository.getStepSnapshot(sequence.id, originalStep)?.name)
        assertEquals("Duplicate only", repository.getStepSnapshot(sequence.id, duplicateStep)?.name)
    }

    @Test
    fun locallyModifiedDuplicateIsSkippedByOnlyUnmodifiedPropagation() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequence =
            repository.createSequenceTemplate(
                singleStepSequence(source.id, "local duplicate"),
                createdAt = at(2),
            )
        val originalStep = sequence.nodes.flatMap { it.stepIds() }.single()
        val local = repository.getStepSnapshot(sequence.id, originalStep)!!
        repository.saveStepConfiguration(
            sequence.id,
            originalStep,
            1,
            local.toAuthoringDraft().copy(name = "Local source"),
            at(3),
        )
        val draft = repository.getSequenceTemplateAuthoringState(sequence.id)!!.toAuthoringDraft()
        val duplicated =
            repository.saveSequenceTemplate(
                sequence.id,
                2,
                draft.copy(
                    nodes =
                        draft.nodes +
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    DraftIdentity.New("duplicate"),
                                    1,
                                    StepActivityDraft.Duplicate(originalStep),
                                ),
                            ),
                ),
                at(4),
            )
        val duplicateStep = duplicated.nodes.flatMap { it.stepIds() }.single { it != originalStep }
        val duplicate = repository.getStepSnapshot(sequence.id, duplicateStep)!!
        assertTrue(duplicate.locallyModified)
        assertEquals("Local source", duplicate.name)
        assertEquals(local.sourceTemplateId, duplicate.sourceTemplateId)
        assertEquals(local.statisticsSeriesId, duplicate.statisticsSeriesId)

        repository.saveActivityTemplate(source.id, 1, source.toAuthoringDraft().copy(name = "Template update"), at(5))
        val result =
            repository.propagateActivityTemplateToLinkedSteps(
                source.id,
                2,
                LinkedStepPropagationMode.ONLY_UNMODIFIED,
                at(6),
            )
        assertEquals(0, result.updatedSteps)
        assertEquals(2, result.skippedLocallyModified)
    }

    @Test
    fun duplicateStaleRevisionAndSnapshotCollisionLeaveNoOrphan() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequence = repository.createSequenceTemplate(singleStepSequence(source.id, "collision"), createdAt = at(2))
        val originalStep = sequence.nodes.flatMap { it.stepIds() }.single()
        val originalSnapshot = repository.getStepSnapshot(sequence.id, originalStep)!!
        val validDuplicateDraft =
            repository.getSequenceTemplateAuthoringState(sequence.id)!!.toAuthoringDraft().copy(
                nodes =
                    repository.getSequenceTemplateAuthoringState(sequence.id)!!.toAuthoringDraft().nodes +
                        SequenceNodeDraft.Step(
                            ActivityStepDraft(
                                DraftIdentity.New("duplicate"),
                                1,
                                StepActivityDraft.Duplicate(originalStep),
                            ),
                        ),
            )
        val beforeNodes = database.sequenceTemplateDao().getNodes(sequence.id.value)
        val beforeSnapshots = count("activity_snapshots")

        assertThrows(IllegalArgumentException::class.java) {
            repository.saveSequenceTemplate(sequence.id, 0, validDuplicateDraft, at(3))
        }
        val colliding =
            TemplateAuthoringRepository(
                database,
                baseIds.copy(nextActivitySnapshotId = { originalSnapshot.id }),
            )
        assertThrows(SQLiteConstraintException::class.java) {
            colliding.saveSequenceTemplate(sequence.id, 1, validDuplicateDraft, at(3))
        }
        assertEquals(1L, repository.getSequenceTemplate(sequence.id)?.revision)
        assertEquals(beforeNodes, database.sequenceTemplateDao().getNodes(sequence.id.value))
        assertEquals(beforeSnapshots, count("activity_snapshots"))
        assertEquals(originalSnapshot, repository.getStepSnapshot(sequence.id, originalStep))
    }

    @Test
    fun sourceLinkedLocalFieldRejectsIdentityCorruptionAndAllowsExplicitReplacement() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequence = repository.createSequenceTemplate(singleStepSequence(source.id, "field"), createdAt = at(2))
        val step = sequence.nodes.flatMap { it.stepIds() }.single()
        val original = repository.getStepSnapshot(sequence.id, step)!!
        val field = original.toAuthoringDraft().fields.single()
        val snapshotCount = count("activity_snapshots")

        assertThrows(IllegalArgumentException::class.java) {
            repository.saveStepConfiguration(
                sequence.id,
                step,
                1,
                original.toAuthoringDraft().copy(fields = listOf(field.copy(unit = "m"))),
                at(3),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveStepConfiguration(
                sequence.id,
                step,
                1,
                original.toAuthoringDraft().copy(fields = listOf(field.copy(type = CustomFieldType.TEXT, unit = null))),
                at(3),
            )
        }
        assertEquals(snapshotCount, count("activity_snapshots"))
        assertEquals(1L, repository.getSequenceTemplate(sequence.id)?.revision)
        assertEquals(original, repository.getStepSnapshot(sequence.id, step))

        val saved =
            repository.saveStepConfiguration(
                sequence.id,
                step,
                1,
                original.toAuthoringDraft().copy(
                    fields =
                        listOf(
                            field.copy(
                                identity = DraftIdentity.New("distance-metres"),
                                sourceFieldId = null,
                                unit = "m",
                            ),
                        ),
                ),
                at(3),
            )
        val replacement = repository.getStepSnapshot(sequence.id, step)!!
        assertEquals(2L, saved.revision)
        assertNotEquals(original.id, replacement.id)
        assertNull(replacement.fields.single().sourceFieldId)
        assertEquals("m", replacement.fields.single().unit)
        assertEquals(source.statisticsSeriesId, replacement.statisticsSeriesId)
    }

    @Test
    fun saveAsNewUsesEffectiveSourceLabelsAndLocalOverridePrecedence() {
        val typoDraft =
            categoryActivityDraft().let { draft ->
                draft.copy(
                    fields =
                        listOf(
                            draft.fields.single().copy(
                                name = "Distnace",
                                categoryOptions =
                                    listOf(
                                        draft.fields
                                            .single()
                                            .categoryOptions
                                            .single()
                                            .copy(label = "Tempoo"),
                                    ),
                            ),
                        ),
                )
            }
        val source = repository.createActivityTemplate(typoDraft, createdAt = at(1))
        val first = repository.createSequenceTemplate(singleStepSequence(source.id, "effective"), createdAt = at(2))
        val firstStep = first.nodes.flatMap { it.stepIds() }.single()
        val physical = repository.getStepSnapshot(first.id, firstStep)!!
        val sourceDraft = source.toAuthoringDraft()
        val corrected =
            repository.saveActivityTemplate(
                source.id,
                1,
                sourceDraft.copy(
                    fields =
                        listOf(
                            sourceDraft.fields.single().copy(
                                name = "Distance",
                                categoryOptions =
                                    listOf(
                                        sourceDraft.fields
                                            .single()
                                            .categoryOptions
                                            .single()
                                            .copy(label = "Tempo"),
                                    ),
                            ),
                        ),
                ),
                at(3),
            )
        assertEquals(1L, corrected.revision)
        assertEquals("Distnace", physical.fields.single().nameAtCreation)
        assertEquals(
            "Tempoo",
            physical.fields
                .single()
                .categoryOptions
                .single()
                .labelAtCreation,
        )

        val copied = repository.saveStepAsNewActivityTemplate(first.id, firstStep, 1, savedAt = at(4))
        assertEquals("Distance", copied.fields.single().name)
        assertEquals(
            "Tempo",
            copied.fields
                .single()
                .categoryOptions
                .single()
                .label,
        )
        assertNotEquals(corrected.fields.single().id, copied.fields.single().id)
        assertNotEquals(
            corrected.fields
                .single()
                .categoryOptions
                .single()
                .id,
            copied.fields
                .single()
                .categoryOptions
                .single()
                .id,
        )

        val second = repository.createSequenceTemplate(singleStepSequence(source.id, "override"), createdAt = at(4))
        val secondStep = second.nodes.flatMap { it.stepIds() }.single()
        val secondSnapshot = repository.getStepSnapshot(second.id, secondStep)!!
        repository.saveStepConfiguration(
            second.id,
            secondStep,
            1,
            secondSnapshot.toAuthoringDraft().copy(
                fields =
                    listOf(
                        secondSnapshot
                            .toAuthoringDraft()
                            .fields
                            .single()
                            .copy(localNameOverride = "Route distance"),
                    ),
            ),
            at(5),
        )
        database.activityTemplateDao().archive(source.id.value, at(6).toEpochMilli())
        val overridden = repository.saveStepAsNewActivityTemplate(second.id, secondStep, 2, savedAt = at(7))
        assertEquals("Route distance", overridden.fields.single().name)
        assertEquals(
            "Tempo",
            overridden.fields
                .single()
                .categoryOptions
                .single()
                .label,
        )
    }

    @Test
    fun sequenceNodeExistingIdentityRequiresPersistedOwnerAndKind() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequenceCount = count("sequence_templates")
        val seriesCount = count("statistics_series")
        val snapshotCount = count("activity_snapshots")
        assertThrows(IllegalArgumentException::class.java) {
            repository.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Fake Step",
                    null,
                    nodes =
                        listOf(
                            SequenceNodeDraft.Step(
                                ActivityStepDraft(
                                    DraftIdentity.Existing(SequenceNodeId("fake-step")),
                                    0,
                                    StepActivityDraft.FromTemplate(source.id),
                                ),
                            ),
                        ),
                ),
                createdAt = at(2),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Fake Repeat",
                    null,
                    nodes =
                        listOf(
                            SequenceNodeDraft.Repeat(
                                SequenceRepeatBlockDraft(
                                    DraftIdentity.Existing(SequenceNodeId("fake-repeat")),
                                    0,
                                    2,
                                    listOf(
                                        ActivityStepDraft(
                                            DraftIdentity.New("child"),
                                            0,
                                            StepActivityDraft.FromTemplate(source.id),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                ),
                createdAt = at(2),
            )
        }
        assertEquals(sequenceCount, count("sequence_templates"))
        assertEquals(seriesCount, count("statistics_series"))
        assertEquals(snapshotCount, count("activity_snapshots"))

        val created = repository.createSequenceTemplate(singleStepSequence(source.id, "valid"), createdAt = at(2))
        val beforeNodes = database.sequenceTemplateDao().getNodes(created.id.value)
        val beforeSnapshots = count("activity_snapshots")
        val currentDraft = created.toAuthoringDraft(snapshots(created.id))
        val unknownStep =
            SequenceNodeDraft.Step(
                ActivityStepDraft(
                    DraftIdentity.Existing(SequenceNodeId("unknown-step")),
                    1,
                    StepActivityDraft.FromTemplate(source.id),
                ),
            )
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveSequenceTemplate(
                created.id,
                1,
                currentDraft.copy(nodes = currentDraft.nodes + unknownStep),
                at(3),
            )
        }
        val unknownRepeat =
            SequenceNodeDraft.Repeat(
                SequenceRepeatBlockDraft(
                    DraftIdentity.Existing(SequenceNodeId("unknown-repeat")),
                    1,
                    2,
                    listOf(
                        ActivityStepDraft(
                            DraftIdentity.New("new-child"),
                            0,
                            StepActivityDraft.FromTemplate(source.id),
                        ),
                    ),
                ),
            )
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveSequenceTemplate(
                created.id,
                1,
                currentDraft.copy(nodes = currentDraft.nodes + unknownRepeat),
                at(3),
            )
        }
        assertEquals(1L, repository.getSequenceTemplate(created.id)?.revision)
        assertEquals(beforeNodes, database.sequenceTemplateDao().getNodes(created.id.value))
        assertEquals(beforeSnapshots, count("activity_snapshots"))
        assertTrue(created.nodes.flatMap { it.stepIds() }.none { it.value == "step-valid" })
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
    fun presentationRenamesDoNotPropagateCurrentSnapshotsButAllStillReplacesModified() {
        val source = repository.createActivityTemplate(categoryActivityDraft(), createdAt = at(1))
        val sequence = repository.createSequenceTemplate(singleStepSequence(source.id, "labels"), createdAt = at(2))
        val step = sequence.nodes.flatMap { it.stepIds() }.single()
        val original = repository.getStepSnapshot(sequence.id, step)!!
        val draft = source.toAuthoringDraft()
        val renamed =
            repository.saveActivityTemplate(
                source.id,
                1,
                draft.copy(
                    fields =
                        listOf(
                            draft.fields.single().copy(
                                name = "Effort",
                                categoryOptions =
                                    listOf(
                                        draft.fields
                                            .single()
                                            .categoryOptions
                                            .single()
                                            .copy(label = "Comfortable"),
                                    ),
                            ),
                        ),
                ),
                at(3),
            )
        assertEquals(1L, renamed.revision)
        assertEquals(
            "Effort",
            ActivitySnapshotDisplayResolver.fieldName(original.fields.single(), renamed.fields.single().name),
        )
        assertEquals(
            "Comfortable",
            ActivitySnapshotDisplayResolver.optionLabel(
                original.fields
                    .single()
                    .categoryOptions
                    .single(),
                renamed.fields
                    .single()
                    .categoryOptions
                    .single()
                    .label,
            ),
        )

        val only =
            repository.propagateActivityTemplateToLinkedSteps(
                source.id,
                1,
                LinkedStepPropagationMode.ONLY_UNMODIFIED,
                at(4),
            )
        val allCurrent =
            repository.propagateActivityTemplateToLinkedSteps(
                source.id,
                1,
                LinkedStepPropagationMode.ALL,
                at(5),
            )
        assertEquals(0, only.updatedSteps)
        assertEquals(0, only.updatedSequences)
        assertEquals(0, allCurrent.updatedSteps)
        assertEquals(original.id, repository.getStepSnapshot(sequence.id, step)?.id)
        assertEquals(1L, repository.getSequenceTemplate(sequence.id)?.revision)

        repository.saveStepConfiguration(
            sequence.id,
            step,
            1,
            original.toAuthoringDraft().copy(name = "Local"),
            at(6),
        )
        val local = repository.getStepSnapshot(sequence.id, step)!!
        val replaced =
            repository.propagateActivityTemplateToLinkedSteps(
                source.id,
                1,
                LinkedStepPropagationMode.ALL,
                at(7),
            )
        assertEquals(1, replaced.updatedSteps)
        assertEquals(1, replaced.updatedSequences)
        assertNotEquals(local.id, repository.getStepSnapshot(sequence.id, step)?.id)
        assertFalse(repository.getStepSnapshot(sequence.id, step)!!.locallyModified)
        assertEquals(3L, repository.getSequenceTemplate(sequence.id)?.revision)
    }

    @Test
    fun simpleSequenceSaveDoesNotRewriteNodesButReorderDoes() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequence =
            repository.createSequenceTemplate(
                SequenceTemplateDraft(
                    "Many Steps",
                    null,
                    nodes = List(10) { sourceStep("node-$it", it, source.id) },
                ),
                createdAt = at(2),
            )
        val originalSnapshots = snapshots(sequence.id).keys
        installNodeWriteCounters()

        val renamed =
            repository.saveSequenceTemplate(
                sequence.id,
                1,
                sequence.toAuthoringDraft(snapshots(sequence.id)).let { draft ->
                    draft.copy(
                        name = "Renamed",
                        settings = draft.settings.copy(autoAdvance = false),
                    )
                },
                at(3),
            )
        assertEquals(2L, renamed.revision)
        assertEquals(0, nodeWrites("insert"))
        assertEquals(0, nodeWrites("delete"))

        val reorderedDraft =
            renamed.toAuthoringDraft(snapshots(sequence.id)).let { draft ->
                draft.copy(
                    nodes =
                        draft.nodes.reversed().mapIndexed { position, node ->
                            val stepNode = node as SequenceNodeDraft.Step
                            SequenceNodeDraft.Step(stepNode.value.copy(position = position))
                        },
                )
            }
        val reordered = repository.saveSequenceTemplate(sequence.id, 2, reorderedDraft, at(4))
        assertEquals(3L, reordered.revision)
        assertTrue(nodeWrites("insert") > 0)
        assertTrue(nodeWrites("delete") > 0)
        assertEquals(originalSnapshots, snapshots(sequence.id).keys)
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
        assertEquals(1, all.updatedSteps)
        assertEquals(1, all.updatedSequences)
        assertFalse(repository.getStepSnapshot(sequenceA.id, stepA)!!.locallyModified)
        assertNotNull(database.activitySnapshotDao().getById(localA.id.value))
        assertEquals(
            source.statisticsSeriesId.value,
            database.activitySnapshotDao().getById(localA.id.value)?.statisticsSeriesId,
        )
        assertEquals(localA.id.value, database.activityExecutionDao().getById("execution")?.snapshotId)
        assertEquals(3L, repository.getSequenceTemplate(sequenceA.id)?.revision)
        assertEquals(2L, repository.getSequenceTemplate(sequenceB.id)?.revision)
    }

    @Test
    fun bulkPruningRetainsSequenceSnapshotAndOccurrenceReferences() {
        val source = repository.createActivityTemplate(activityDraft(), createdAt = at(1))
        val sequenceA = repository.createSequenceTemplate(singleStepSequence(source.id, "frozen"), createdAt = at(2))
        val sequenceB =
            repository.createSequenceTemplate(
                singleStepSequence(source.id, "occurrence"),
                createdAt = at(2),
            )
        val oldA = repository.getStepSnapshot(sequenceA.id, sequenceA.nodes.flatMap { it.stepIds() }.single())!!
        val oldB = repository.getStepSnapshot(sequenceB.id, sequenceB.nodes.flatMap { it.stepIds() }.single())!!
        insertSequenceSnapshotReference("frozen-sequence", oldA.id.value)
        insertSequenceOccurrenceReference("occurrence-sequence", oldB.id.value)
        repository.saveActivityTemplate(
            source.id,
            1,
            source.toAuthoringDraft().copy(shortComment = "Changed"),
            at(3),
        )

        val result =
            repository.propagateActivityTemplateToLinkedSteps(
                source.id,
                2,
                LinkedStepPropagationMode.ALL,
                at(4),
            )
        assertEquals(2, result.updatedSteps)
        assertNotNull(database.activitySnapshotDao().getById(oldA.id.value))
        assertNotNull(database.activitySnapshotDao().getById(oldB.id.value))
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

    private fun insertSequenceSnapshotReference(
        id: String,
        activitySnapshotId: String,
    ) {
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity(id, id, null, null, null, null, 1_000),
                SequenceSnapshotSettingsEntity(id, true, 0, 0, true, true, false, true, true, "ACTIVE"),
                nodes =
                    listOf(
                        SequenceSnapshotNodeEntity("$id-step", id, "STEP", null, 0, activitySnapshotId, null),
                    ),
            ),
        )
    }

    private fun insertSequenceOccurrenceReference(
        id: String,
        activitySnapshotId: String,
    ) {
        val snapshotId = "$id-snapshot"
        database.sequenceSnapshotDao().insertAggregate(
            SequenceSnapshotAggregateEntity(
                SequenceSnapshotEntity(snapshotId, id, null, null, null, null, 1_000),
                SequenceSnapshotSettingsEntity(snapshotId, true, 0, 0, true, true, false, true, true, "ACTIVE"),
            ),
        )
        database.sequenceExecutionDao().insertAggregate(
            SequenceExecutionAggregateEntity(
                SequenceExecutionEntity(
                    id,
                    snapshotId,
                    null,
                    null,
                    "ENDED_EARLY",
                    1_000,
                    2_000,
                    1_000,
                    0,
                    1_000,
                    "UTC",
                    0,
                    "1970-01-01",
                    null,
                    1_000,
                    2_000,
                ),
                occurrences =
                    listOf(
                        SequenceOccurrenceEntity(
                            "$id-occurrence",
                            id,
                            null,
                            activitySnapshotId,
                            0,
                            null,
                            null,
                            "COMPLETED",
                            1_000,
                            2_000,
                            "SEQUENCE_ENDED_EARLY",
                            true,
                            false,
                        ),
                    ),
                intervals =
                    listOf(
                        SequenceIntervalEntity("$id-active", id, "ACTIVE_STEP", 1_000, 2_000, "$id-occurrence"),
                    ),
            ),
        )
    }

    private fun installNodeWriteCounters() {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE node_write_counter(kind TEXT PRIMARY KEY, writes INTEGER NOT NULL)")
        sql.execSQL("INSERT INTO node_write_counter VALUES ('insert', 0), ('delete', 0)")
        sql.execSQL(
            "CREATE TRIGGER count_node_insert AFTER INSERT ON sequence_nodes " +
                "BEGIN UPDATE node_write_counter SET writes = writes + 1 WHERE kind = 'insert'; END",
        )
        sql.execSQL(
            "CREATE TRIGGER count_node_delete AFTER DELETE ON sequence_nodes " +
                "BEGIN UPDATE node_write_counter SET writes = writes + 1 WHERE kind = 'delete'; END",
        )
    }

    private fun nodeWrites(kind: String): Int =
        database.openHelper.readableDatabase
            .query(
                "SELECT writes FROM node_write_counter WHERE kind = ?",
                arrayOf(kind),
            ).use {
                check(it.moveToFirst())
                it.getInt(0)
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
