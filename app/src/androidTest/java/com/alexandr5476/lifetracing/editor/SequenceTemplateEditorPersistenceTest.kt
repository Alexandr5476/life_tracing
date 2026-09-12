package com.alexandr5476.lifetracing.editor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.data.persistence.TemplateAuthoringRepository
import com.alexandr5476.lifetracing.domain.ActivityCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivityFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

private const val ASYNC_STATE_TIMEOUT_MS = 5_000L

@RunWith(AndroidJUnit4::class)
class SequenceTemplateEditorPersistenceTest {
    @Test
    fun newApplyPromotesStepsThenDoneUpdatesTheSameSingleDurableSequence() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = TemplateAuthoringRepository.create(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val source = repository.createActivityTemplate(sourceDraft("new"), createdAt = Instant.now())
            var writes = 0
            var saves = 0
            var created: SequenceTemplate? = null
            try {
                val controller =
                    controller(
                        scope,
                        SequenceTemplateEditorTarget.New,
                        repository,
                        Instant.now().plusSeconds(1),
                        create = { draft ->
                            writes++
                            repository
                                .createSequenceTemplate(draft, createdAt = Instant.now().plusSeconds(1))
                                .also { value -> created = value }
                        },
                        save = { id, revision, draft, _ ->
                            saves++
                            repository.saveSequenceTemplate(
                                id,
                                revision,
                                draft,
                                requireNotNull(created).updatedAt.plusSeconds(1),
                            )
                        },
                    )
                controller.awaitReady()
                val settings =
                    SequenceTemplateSettings(
                        autoAdvance = false,
                        sequenceStartCountdown = Duration.ofSeconds(2),
                        beforeEachStepCountdown = Duration.ofSeconds(3),
                        transitionSound = false,
                        transitionVibration = true,
                        keepScreenAwake = true,
                        confirmJump = false,
                        confirmEarlyEnd = false,
                    )
                val overrides =
                    SequenceStepOverrides(
                        startCountdown = Duration.ZERO,
                        timerZeroBehavior = TimerZeroBehavior.OVERTIME,
                        timerEndSound = false,
                        timerEndVibration = true,
                        keepScreenAwake = false,
                    )
                controller.updateDraft {
                    SequenceTemplateDraft(
                        "Editor new ${System.nanoTime()}",
                        "Complete surface",
                        settings = settings,
                        fields =
                            listOf(
                                SequenceFieldDraft(
                                    DraftIdentity.New("volume"),
                                    0,
                                    "Volume",
                                    CustomFieldType.NUMBER,
                                    unit = "reps",
                                    displayPrecision = 0,
                                    defaultNumberScaled = 12_000,
                                    isMainValue = true,
                                ),
                            ),
                        nodes =
                            listOf(
                                step("reusable", 0, StepActivityDraft.FromTemplate(source.id), overrides),
                                step(
                                    "local",
                                    1,
                                    StepActivityDraft.Local(
                                        ActivitySnapshotDraft(
                                            "Local note",
                                            null,
                                            TimeTrackingMode.NO_LIVE_TRACKING,
                                            null,
                                        ),
                                    ),
                                ),
                                SequenceNodeDraft.Repeat(
                                    SequenceRepeatBlockDraft(
                                        DraftIdentity.New("repeat"),
                                        2,
                                        4,
                                        listOf(
                                            stepValue(
                                                "repeat-child",
                                                0,
                                                StepActivityDraft.FromTemplate(source.id),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                    )
                }
                controller.enterManipulation(DraftIdentity.New("reusable"))
                controller.applyManipulation()
                controller.awaitApplied()
                val promoted = requireNotNull(controller.state.value.readyDraft())
                assertTrue(promoted.nodes.flatMap { it.steps() }.all { it.identity is DraftIdentity.Existing })
                assertEquals(1L, requireNotNull(created).revision)

                controller.updateDraft { it.copy(name = "Final ${it.name}") }
                controller.save()
                controller.awaitCommitted()

                val reloaded =
                    TemplateAuthoringRepository
                        .create(context)
                        .getSequenceTemplateAuthoringState(requireNotNull(created).id)!!
                val draft = reloaded.toAuthoringDraft()
                assertEquals(1, writes)
                assertEquals(1, saves)
                assertEquals(requireNotNull(created).id, reloaded.sequence.id)
                assertEquals(2L, reloaded.sequence.revision)
                assertEquals(settings, draft.settings)
                assertEquals(12_000L, draft.fields.single().defaultNumberScaled)
                assertTrue(draft.fields.single().isMainValue)
                assertEquals(overrides, (draft.nodes[0] as SequenceNodeDraft.Step).value.overrides)
                assertNull(
                    ((draft.nodes[1] as SequenceNodeDraft.Step).value.activity as StepActivityDraft.Existing)
                        .configuration
                        .fields
                        .singleOrNull(),
                )
                val repeat = draft.nodes[2] as SequenceNodeDraft.Repeat
                assertEquals(4, repeat.value.repeatCount)
                assertEquals(
                    SequenceStepOverrides(),
                    repeat.value.children
                        .single()
                        .overrides,
                )
                assertEquals(
                    source.id,
                    reloaded.activitySnapshots.values
                        .first { it.name == source.name }
                        .sourceTemplateId,
                )
                assertNull(
                    reloaded.activitySnapshots.values
                        .single { it.name == "Local note" }
                        .sourceTemplateId,
                )
                controller.close()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun existingMultiEditSavesOnceWhileAStaleEditorKeepsItsDraftAndCannotMutateCanonicalState() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = TemplateAuthoringRepository.create(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val source = repository.createActivityTemplate(sourceDraft("existing"), createdAt = Instant.now())
            val created =
                repository.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "Existing ${System.nanoTime()}",
                        null,
                        nodes =
                            listOf(
                                step("step", 0, StepActivityDraft.FromTemplate(source.id)),
                                step(
                                    "local",
                                    1,
                                    StepActivityDraft.Local(
                                        ActivitySnapshotDraft("Local", null, TimeTrackingMode.STOPWATCH, null),
                                    ),
                                ),
                            ),
                    ),
                    createdAt = Instant.now().plusMillis(1),
                )
            var writes = 0
            try {
                val editor =
                    controller(
                        scope,
                        SequenceTemplateEditorTarget.Existing(created.id),
                        repository,
                        Instant.now().plusSeconds(1),
                        create = { error("Create is not used") },
                        save = { id, revision, draft, at ->
                            writes++
                            repository.saveSequenceTemplate(id, revision, draft, at)
                        },
                    )
                editor.awaitReady()
                editor.updateDraft { draft ->
                    draft.copy(
                        name = "Several edits",
                        shortComment = "one transaction",
                        settings = draft.settings.copy(autoAdvance = false, transitionSound = false),
                        nodes =
                            draft.nodes.map { node ->
                                val step = (node as SequenceNodeDraft.Step).value
                                SequenceNodeDraft.Step(
                                    step.copy(overrides = step.overrides.copy(timerEndSound = false)),
                                )
                            },
                    )
                }
                editor.save()
                editor.awaitCommitted()
                val after = requireNotNull(repository.getSequenceTemplateAuthoringState(created.id))
                assertEquals(1, writes)
                assertEquals(created.revision + 1, after.sequence.revision)
                assertEquals("Several edits", after.sequence.name)

                val stale =
                    controller(
                        scope,
                        SequenceTemplateEditorTarget.Existing(created.id),
                        repository,
                        Instant.now().plusSeconds(3),
                        create = { error("Create is not used") },
                    )
                stale.awaitReady()
                val external =
                    repository.saveSequenceTemplate(
                        created.id,
                        after.sequence.revision,
                        after.toAuthoringDraft().copy(name = "External winner"),
                        Instant.now().plusSeconds(2),
                    )
                stale.updateDraft { it.copy(name = "Stale draft") }
                val staleDraft = requireNotNull(stale.state.value.readyDraft())
                val first = staleDraft.nodes[0].identity
                val second = staleDraft.nodes[1].identity
                stale.enterManipulation(first)
                assertTrue(stale.moveManipulation(second, SequenceDropDestination(position = 0)))
                stale.applyManipulation()
                val failure = stale.awaitFailure()

                assertTrue(failure.isConflict)
                assertEquals(
                    "Stale draft",
                    stale.state.value
                        .readyDraft()
                        ?.name,
                )
                assertTrue(
                    stale.state.value.manipulation
                        ?.canUndo == true,
                )
                val canonical = requireNotNull(repository.getSequenceTemplate(created.id))
                assertEquals(external, canonical)
                editor.close()
                stale.close()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun manipulationApplyCommitsSeveralMovesAndFrozenCrossContainerDuplicateOnce() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = TemplateAuthoringRepository.create(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val base = Instant.now()
            val source = repository.createActivityTemplate(sourceDraft("manipulation"), createdAt = base)
            val overrides =
                SequenceStepOverrides(
                    startCountdown = Duration.ofSeconds(7),
                    timerZeroBehavior = TimerZeroBehavior.OVERTIME,
                    timerEndSound = false,
                )
            val created =
                repository.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "Manipulation ${System.nanoTime()}",
                        null,
                        nodes =
                            listOf(
                                step("source", 0, StepActivityDraft.FromTemplate(source.id), overrides),
                                step(
                                    "local-top",
                                    1,
                                    StepActivityDraft.Local(
                                        ActivitySnapshotDraft("Local top", null, TimeTrackingMode.STOPWATCH, null),
                                    ),
                                ),
                                SequenceNodeDraft.Repeat(
                                    SequenceRepeatBlockDraft(
                                        DraftIdentity.New("repeat"),
                                        2,
                                        2,
                                        listOf(
                                            stepValue(
                                                "local-child",
                                                0,
                                                StepActivityDraft.Local(
                                                    ActivitySnapshotDraft(
                                                        "Local child",
                                                        null,
                                                        TimeTrackingMode.STOPWATCH,
                                                        null,
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                    ),
                    createdAt = base.plusSeconds(1),
                )
            val createdState = requireNotNull(repository.getSequenceTemplateAuthoringState(created.id))
            val sourceStep = created.nodes.filterIsInstance<ActivityStep>().first()
            repository.saveStepConfiguration(
                created.id,
                sourceStep.id,
                created.revision,
                createdState.activitySnapshots.getValue(sourceStep.activitySnapshotId).toAuthoringDraft().copy(
                    name = "Frozen local source",
                ),
                base.plusSeconds(2),
            )
            repository.saveActivityTemplate(
                source.id,
                source.revision,
                source.toAuthoringDraft().copy(name = "New mutable template value"),
                base.plusSeconds(3),
            )
            val before = requireNotNull(repository.getSequenceTemplateAuthoringState(created.id))
            val frozen =
                before.activitySnapshots.getValue(
                    (before.sequence.nodes[0] as ActivityStep).activitySnapshotId,
                )
            val repeat =
                before.sequence.nodes
                    .filterIsInstance<SequenceRepeatBlock>()
                    .single()
            val child = repeat.children.single()
            var writes = 0
            try {
                val controller =
                    controller(
                        scope,
                        SequenceTemplateEditorTarget.Existing(created.id),
                        repository,
                        base.plusSeconds(4),
                        create = { error("Create is not used") },
                        save = { id, revision, draft, at ->
                            writes++
                            repository.saveSequenceTemplate(id, revision, draft, at)
                        },
                    )
                controller.awaitReady()
                val sourceIdentity = DraftIdentity.Existing(sourceStep.id)
                val repeatIdentity = DraftIdentity.Existing(repeat.id)
                controller.enterManipulation(sourceIdentity)
                assertTrue(controller.moveManipulation(sourceIdentity, SequenceDropDestination(repeatIdentity, 1)))
                assertTrue(
                    controller.moveManipulation(
                        DraftIdentity.Existing(child.id),
                        SequenceDropDestination(position = 0),
                    ),
                )
                assertTrue(controller.duplicateManipulation(sourceIdentity, SequenceDropDestination(position = 1)))
                val submitted = requireNotNull(controller.state.value.readyDraft())

                controller.applyManipulation()
                controller.awaitApplied()

                val after = requireNotNull(repository.getSequenceTemplateAuthoringState(created.id))
                assertEquals(1, writes)
                assertEquals(before.sequence.revision + 1, after.sequence.revision)
                assertEquals(after.toAuthoringDraft(), controller.state.value.readyDraft())
                assertEquals(submitted.nodes.map(SequenceNodeDraft::position), after.sequence.nodes.map { it.position })
                val movedOriginal =
                    after.sequence.nodes
                        .filterIsInstance<SequenceRepeatBlock>()
                        .single()
                        .children
                        .single()
                val duplicate = after.sequence.nodes.filterIsInstance<ActivityStep>()[1]
                val copied = after.activitySnapshots.getValue(duplicate.activitySnapshotId)
                assertEquals(sourceStep.id, movedOriginal.id)
                assertEquals(frozen.id, movedOriginal.activitySnapshotId)
                assertEquals(overrides, movedOriginal.overrides)
                assertNotEquals(sourceStep.id, duplicate.id)
                assertNotEquals(frozen.id, copied.id)
                assertEquals("Frozen local source", copied.name)
                assertNotEquals("New mutable template value", copied.name)
                assertEquals(frozen.sourceTemplateId, copied.sourceTemplateId)
                assertEquals(frozen.sourceRevision, copied.sourceRevision)
                assertEquals(frozen.statisticsSeriesId, copied.statisticsSeriesId)
                assertEquals(frozen.locallyModified, copied.locallyModified)
                assertEquals(overrides, duplicate.overrides)
                assertEquals(frozen.fields.map { it.sourceFieldId }, copied.fields.map { it.sourceFieldId })
                assertNotEquals(frozen.fields.single().id, copied.fields.single().id)
                assertEquals(frozen, after.activitySnapshots.getValue(movedOriginal.activitySnapshotId))
                controller.close()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun manipulationApplyPersistenceFailureKeepsDurableGraphAndDraftHistory() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = TemplateAuthoringRepository.create(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val base = Instant.now()
            val source = repository.createActivityTemplate(categorySourceDraft(), createdAt = base)
            val created =
                repository.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "Failure ${System.nanoTime()}",
                        null,
                        nodes = listOf(step("source", 0, StepActivityDraft.FromTemplate(source.id))),
                    ),
                    createdAt = base.plusSeconds(1),
                )
            val before = requireNotNull(repository.getSequenceTemplateAuthoringState(created.id))
            val sourceStep =
                before.sequence.nodes
                    .filterIsInstance<ActivityStep>()
                    .single()
            val sql = context.openOrCreateDatabase("lifetracing.db", Context.MODE_PRIVATE, null)
            val trackedTables =
                listOf(
                    "activity_snapshots",
                    "activity_snapshot_fields",
                    "activity_snapshot_category_options",
                    "sequence_nodes",
                )
            val beforeCounts = trackedTables.associateWith { sql.countRows(it) }
            val failureTrigger = "fail_manipulation_duplicate_node_insert"
            try {
                sql.execSQL("DROP TRIGGER IF EXISTS $failureTrigger")
                sql.execSQL(
                    "CREATE TRIGGER $failureTrigger BEFORE INSERT ON sequence_nodes " +
                        "BEGIN SELECT RAISE(ABORT, 'forced repository transaction failure'); END",
                )
                var writes = 0
                val controller =
                    controller(
                        scope,
                        SequenceTemplateEditorTarget.Existing(created.id),
                        repository,
                        base.plusSeconds(2),
                        create = { error("Create is not used") },
                        save = { id, revision, draft, at ->
                            writes++
                            repository.saveSequenceTemplate(id, revision, draft, at)
                        },
                    )
                controller.awaitReady()
                val sourceIdentity = DraftIdentity.Existing(sourceStep.id)
                controller.enterManipulation(sourceIdentity)
                assertTrue(controller.duplicateManipulation(sourceIdentity, SequenceDropDestination(position = 1)))
                val submitted = requireNotNull(controller.state.value.readyDraft())

                controller.applyManipulation()
                val failure = controller.awaitFailure()

                val after = requireNotNull(repository.getSequenceTemplateAuthoringState(created.id))
                assertTrue(failure.message.contains("forced repository transaction failure"))
                assertEquals(before.sequence.revision, after.sequence.revision)
                assertEquals(before.sequence.nodes, after.sequence.nodes)
                assertEquals(before.activitySnapshots, after.activitySnapshots)
                assertEquals(before.activitySnapshots.size, after.activitySnapshots.size)
                assertEquals(beforeCounts, trackedTables.associateWith { sql.countRows(it) })
                assertEquals(submitted, controller.state.value.readyDraft())
                assertEquals(1, writes)
                assertTrue(
                    controller.state.value.manipulation
                        ?.canUndo == true,
                )
                assertEquals(
                    sourceStep,
                    after.sequence.nodes
                        .filterIsInstance<ActivityStep>()
                        .single(),
                )
                assertTrue(controller.undoManipulation())
                assertTrue(
                    controller.state.value.manipulation
                        ?.canRedo == true,
                )
                assertTrue(controller.redoManipulation())
                assertEquals(submitted, controller.state.value.readyDraft())
                assertEquals(1, writes)
                controller.close()
            } finally {
                sql.execSQL("DROP TRIGGER IF EXISTS $failureTrigger")
                sql.close()
                scope.cancel()
            }
        }

    @Test
    fun editorUnitReplacementHelperPersistsANewLocalFieldWhilePreservingStepAndSnapshotProvenance() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = TemplateAuthoringRepository.create(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val source = repository.createActivityTemplate(sourceDraft("replacement"), createdAt = Instant.now())
            val created =
                repository.createSequenceTemplate(
                    SequenceTemplateDraft(
                        "Replacement ${System.nanoTime()}",
                        null,
                        nodes = listOf(step("step", 0, StepActivityDraft.FromTemplate(source.id))),
                    ),
                    createdAt = Instant.now().plusMillis(1),
                )
            val beforeState = requireNotNull(repository.getSequenceTemplateAuthoringState(created.id))
            val beforeStep = beforeState.sequence.nodes.single()
            val beforeSnapshot = beforeState.activitySnapshots.values.single()
            try {
                val editor =
                    controller(
                        scope,
                        SequenceTemplateEditorTarget.Existing(created.id),
                        repository,
                        Instant.now().plusSeconds(1),
                        create = { error("Create is not used") },
                    )
                editor.awaitReady()
                editor.updateDraft { draft ->
                    val node = draft.nodes.single() as SequenceNodeDraft.Step
                    val activity = node.value.activity as StepActivityDraft.Existing
                    val field = activity.configuration.fields.single()
                    draft.copy(
                        nodes =
                            listOf(
                                SequenceNodeDraft.Step(
                                    node.value.copy(
                                        activity =
                                            activity.copy(
                                                configuration =
                                                    activity.configuration.copy(
                                                        fields =
                                                            listOf(
                                                                field.withCompatibleUnitReplacement(
                                                                    "m",
                                                                    DraftIdentity.New("metres"),
                                                                ),
                                                            ),
                                                    ),
                                            ),
                                    ),
                                ),
                            ),
                    )
                }
                editor.save()
                editor.awaitCommitted()

                val afterState = requireNotNull(repository.getSequenceTemplateAuthoringState(created.id))
                val afterStep = afterState.sequence.nodes.single()
                val afterSnapshot = afterState.activitySnapshots.values.single()
                assertEquals(beforeStep.id, afterStep.id)
                assertNotEquals(beforeSnapshot.id, afterSnapshot.id)
                assertEquals(beforeSnapshot.sourceTemplateId, afterSnapshot.sourceTemplateId)
                assertEquals(beforeSnapshot.sourceRevision, afterSnapshot.sourceRevision)
                assertEquals(beforeSnapshot.statisticsSeriesId, afterSnapshot.statisticsSeriesId)
                assertTrue(afterSnapshot.locallyModified)
                assertNotEquals(beforeSnapshot.fields.single().id, afterSnapshot.fields.single().id)
                assertNull(afterSnapshot.fields.single().sourceFieldId)
                assertEquals("m", afterSnapshot.fields.single().unit)
                editor.close()
            } finally {
                scope.cancel()
            }
        }

    private fun controller(
        scope: CoroutineScope,
        target: SequenceTemplateEditorTarget,
        repository: TemplateAuthoringRepository,
        at: Instant,
        create: suspend (SequenceTemplateDraft) -> SequenceTemplate,
        save: suspend (
            com.alexandr5476.lifetracing.domain.SequenceTemplateId,
            Long,
            SequenceTemplateDraft,
            Instant,
        ) -> SequenceTemplate =
            repository::saveSequenceTemplate,
    ) = SequenceTemplateEditorController(
        scope,
        target,
        repository::getSequenceTemplateAuthoringState,
        { emptyList() },
        { draft, _, _ -> create(draft) },
        save,
        { at },
    )

    private fun step(
        key: String,
        position: Int,
        activity: StepActivityDraft,
        overrides: SequenceStepOverrides = SequenceStepOverrides(),
    ) = SequenceNodeDraft.Step(stepValue(key, position, activity, overrides))

    private fun stepValue(
        key: String,
        position: Int,
        activity: StepActivityDraft,
        overrides: SequenceStepOverrides = SequenceStepOverrides(),
    ) = com.alexandr5476.lifetracing.domain.ActivityStepDraft(
        DraftIdentity.New(key),
        position,
        activity,
        overrides,
    )

    private fun SequenceNodeDraft.steps() =
        when (this) {
            is SequenceNodeDraft.Step -> listOf(value)
            is SequenceNodeDraft.Repeat -> value.children
        }

    private fun sourceDraft(suffix: String) =
        ActivityTemplateDraft(
            "Source $suffix ${System.nanoTime()}",
            null,
            TimeTrackingMode.TIMER,
            Duration.ofSeconds(60),
            fields =
                listOf(
                    ActivityFieldDraft(
                        DraftIdentity.New("distance"),
                        0,
                        "Distance",
                        CustomFieldType.NUMBER,
                        unit = "km",
                        displayPrecision = 1,
                        defaultNumberScaled = 5_000,
                        isMainValue = true,
                    ),
                ),
        )

    private fun categorySourceDraft() =
        ActivityTemplateDraft(
            "Source failure ${System.nanoTime()}",
            null,
            TimeTrackingMode.STOPWATCH,
            null,
            fields =
                listOf(
                    ActivityFieldDraft(
                        DraftIdentity.New("effort"),
                        0,
                        "Effort",
                        CustomFieldType.CATEGORY,
                        defaultCategoryOption = DraftIdentity.New("easy"),
                        categoryOptions =
                            listOf(
                                ActivityCategoryOptionDraft(DraftIdentity.New("easy"), 0, "Easy"),
                            ),
                    ),
                ),
        )

    private fun SQLiteDatabase.countRows(table: String): Int =
        rawQuery("SELECT COUNT(*) FROM `$table`", null).use {
            check(it.moveToFirst())
            it.getInt(0)
        }

    private suspend fun SequenceTemplateEditorController.awaitReady() {
        awaitState(
            phase = "READY",
            isTerminalFailure = { it.load is SequenceTemplateEditorLoad.Failure },
        ) { it.load is SequenceTemplateEditorLoad.Ready }
    }

    private suspend fun SequenceTemplateEditorController.awaitCommitted() {
        awaitState(
            phase = "COMMITTED",
            isTerminalFailure = { it.save is SequenceTemplateEditorSave.Failure },
        ) { it.save is SequenceTemplateEditorSave.Committed }
    }

    private suspend fun SequenceTemplateEditorController.awaitApplied(expectedGeneration: Long = 1L) {
        awaitState(
            phase = "APPLIED",
            isTerminalFailure = { it.save is SequenceTemplateEditorSave.Failure },
        ) { it.appliedGeneration >= expectedGeneration }
    }

    private suspend fun SequenceTemplateEditorController.awaitFailure(): SequenceTemplateEditorSave.Failure =
        awaitState(phase = "FAILURE") { it.save is SequenceTemplateEditorSave.Failure }
            .save as SequenceTemplateEditorSave.Failure

    private suspend fun SequenceTemplateEditorController.awaitState(
        phase: String,
        isTerminalFailure: (SequenceTemplateEditorState) -> Boolean = { false },
        condition: (SequenceTemplateEditorState) -> Boolean,
    ): SequenceTemplateEditorState =
        try {
            withTimeout(ASYNC_STATE_TIMEOUT_MS) {
                state.first {
                    if (isTerminalFailure(it)) {
                        throw AssertionError("Terminal failure while awaiting $phase; ${it.diagnostic()}")
                    }
                    condition(it)
                }
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out after $ASYNC_STATE_TIMEOUT_MS ms awaiting $phase; last state: ${state.value.diagnostic()}",
                failure,
            )
        }

    private fun SequenceTemplateEditorState.diagnostic(): String =
        "load=${load.javaClass.simpleName}${load.failureMessage()}, " +
            "save=${save.javaClass.simpleName}${save.failureMessage()}, " +
            "appliedGeneration=$appliedGeneration, manipulationExists=${manipulation != null}"

    private fun SequenceTemplateEditorLoad.failureMessage(): String =
        (this as? SequenceTemplateEditorLoad.Failure)?.let { "(message=${it.message})" }.orEmpty()

    private fun SequenceTemplateEditorSave.failureMessage(): String =
        (this as? SequenceTemplateEditorSave.Failure)?.let { "(message=${it.message})" }.orEmpty()
}
