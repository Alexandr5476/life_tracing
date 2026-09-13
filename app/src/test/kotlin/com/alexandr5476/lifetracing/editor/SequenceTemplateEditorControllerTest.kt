package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSourceStatus
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateAuthoringState
import com.alexandr5476.lifetracing.domain.SequenceTemplateDraft
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.toAuthoringDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.CoroutineContext

@Suppress("LargeClass") // Editor boundary scenarios share one focused controller harness.
class SequenceTemplateEditorControllerTest {
    private val inlineDispatcher =
        object : CoroutineDispatcher() {
            override fun dispatch(
                context: CoroutineContext,
                block: Runnable,
            ) = block.run()
        }

    @Test
    fun newDraftChangesAndDiscardNeverWrite() =
        runBlocking {
            var writes = 0
            var exits = 0
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.New,
                    { null },
                    { emptyList() },
                    { _, _, _ ->
                        writes++
                        error("Discard must not write")
                    },
                    { _, _, _, _ -> error("Existing save is not used") },
                    { Instant.EPOCH },
                )
            withTimeout(2_000) { controller.state.first { it.load is SequenceTemplateEditorLoad.Ready } }

            controller.updateDraft { it.copy(name = "Workout") }
            controller.requestBack { exits++ }
            assertTrue(controller.state.value.discardConfirmationVisible)
            controller.discard { exits++ }

            assertEquals(0, writes)
            assertEquals(1, exits)
            controller.close()
        }

    @Test
    fun existingEditorUsesOneCanonicalGraphReadAndKeepsItsDraftOnConflict() =
        runBlocking {
            var reads = 0
            val state = authoringState()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(state.sequence.id),
                    {
                        reads++
                        state
                    },
                    { emptyList() },
                    { _, _, _ -> error("New save is not used") },
                    { _, _, _, _ -> error("SequenceTemplate revision changed concurrently") },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitReady()

            val loaded = requireNotNull(controller.state.value.readyDraft())
            assertEquals(2, loaded.nodes.size)
            controller.updateDraft { it.copy(name = "My draft") }
            controller.save()
            val failure = controller.awaitFailure()

            assertEquals(1, reads)
            assertTrue(failure.isConflict)
            assertEquals(
                "My draft",
                controller.state.value
                    .readyDraft()
                    ?.name,
            )
            controller.close()
        }

    @Test
    @Suppress("LongMethod") // Covers all three repository action adapters in one canonical-reload fixture.
    fun sourceActionsRefreshSharedStatusAndRebaseOnEveryCanonicalReload() =
        runBlocking {
            val sourceId = ActivityTemplateId("activity")
            val newSourceId = ActivityTemplateId("new-activity")
            val stepId = SequenceNodeId("step-1")
            val initial = authoringState().withLinkedSource(sourceId)
            var canonical = initial
            var sourceReads = 0
            val sourceRevisions = mutableMapOf(sourceId to 4L)
            var sourceRefreshes = 0
            var sourceTemplateUpdates = 0
            var savedAsNew = 0
            var sequenceReads = 0
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(initial.sequence.id),
                    {
                        sequenceReads++
                        canonical
                    },
                    { emptyList() },
                    { _, _, _ -> error("New save is not used") },
                    { _, _, _, _ -> error("Normal save is not used") },
                    { Instant.EPOCH.plusSeconds(1) },
                    loadSourceStatuses = { ids ->
                        sourceReads++
                        ids
                            .mapNotNull { id ->
                                sourceRevisions[id]?.let { id to ActivityTemplateSourceStatus(id, it, false) }
                            }.toMap()
                    },
                    updateFromSource = { id, target, revision, _ ->
                        assertEquals(initial.sequence.id, id)
                        assertEquals(stepId, target)
                        assertEquals(7, revision)
                        sourceRefreshes++
                        canonical = canonical.copy(sequence = canonical.sequence.copy(revision = 8))
                    },
                    updateSource = { _, target, sequenceRevision, sourceRevision, _ ->
                        assertEquals(stepId, target)
                        assertEquals(8, sequenceRevision)
                        assertEquals(4, sourceRevision)
                        sourceTemplateUpdates++
                        sourceRevisions[sourceId] = 5
                    },
                    saveAsNewSource = { _, target, revision, _ ->
                        assertEquals(stepId, target)
                        assertEquals(8, revision)
                        savedAsNew++
                        sourceRevisions[newSourceId] = 1
                        canonical =
                            canonical
                                .withLinkedSource(newSourceId)
                                .copy(sequence = canonical.sequence.copy(revision = 9))
                    },
                )
            controller.awaitReady()

            assertEquals(1, sourceReads)
            assertEquals(4, controller.state.value.activeRevision(sourceId))
            controller.updateStepFromSource(stepId)
            withTimeout(2_000) { controller.state.first { it.appliedGeneration == 1L } }

            assertEquals(2, sourceReads)
            assertEquals(1, sourceRefreshes)
            assertEquals(2, sequenceReads)
            val ready = controller.state.value.load as SequenceTemplateEditorLoad.Ready
            assertEquals(8, ready.expectedRevision)
            assertEquals(ready.original, ready.draft)

            controller.updateDraft { it.copy(name = "Unsaved") }
            controller.updateStepFromSource(stepId)
            assertEquals(1, sourceRefreshes)
            controller.updateDraft { it.copy(name = "Workout") }
            controller.updateNumberInput(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN, "invalid", 0, 0) {}
            controller.updateSourceTemplate(stepId)
            assertEquals(0, sourceTemplateUpdates)
            controller.clearNumberInput(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN)
            controller.enterManipulation(DraftIdentity.Existing(stepId))
            controller.saveStepAsNewTemplate(stepId)
            assertEquals(0, savedAsNew)
            controller.discardManipulation()
            controller.updateSourceTemplate(stepId)
            withTimeout(2_000) { controller.state.first { it.appliedGeneration == 2L } }
            assertEquals(5, controller.state.value.activeRevision(sourceId))
            controller.saveStepAsNewTemplate(stepId)
            withTimeout(2_000) { controller.state.first { it.appliedGeneration == 3L } }

            assertEquals(1, sourceTemplateUpdates)
            assertEquals(1, savedAsNew)
            assertEquals(newSourceId, controller.state.value.stepSourceIds[stepId])
            assertEquals(1, controller.state.value.activeRevision(newSourceId))
            assertEquals(4, sourceReads)
            var exits = 0
            controller.updateDraft { it.copy(name = "Later unsaved change") }
            controller.requestBack { exits++ }
            assertTrue(controller.state.value.discardConfirmationVisible)
            controller.discard { exits++ }
            assertEquals(1, exits)
            assertEquals("Workout", canonical.sequence.name)
            controller.close()
        }

    @Test
    @Suppress("LongMethod") // Three writer shapes share one deterministic recovery contract.
    fun committedSourceActionsRetryOnlyCanonicalRecovery() =
        runBlocking {
            SourceActionKind.entries.forEach { kind ->
                val sourceId = ActivityTemplateId("source-$kind")
                val replacementId = ActivityTemplateId("replacement-$kind")
                val stepId = SequenceNodeId("step-1")
                var canonical = authoringState().withLinkedSource(sourceId)
                val revisions = mutableMapOf(sourceId to 4L)
                var loads = 0
                var writes = 0
                val controller =
                    SequenceTemplateEditorController(
                        this,
                        SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                        {
                            loads++
                            if (loads == 2) error("temporary canonical reload failure")
                            canonical
                        },
                        { emptyList() },
                        { _, _, _ -> error("Create is not used") },
                        { _, _, _, _ -> error("Save is not used") },
                        { Instant.EPOCH.plusSeconds(1) },
                        loadSourceStatuses = { ids ->
                            ids
                                .mapNotNull { id ->
                                    revisions[id]?.let { id to ActivityTemplateSourceStatus(id, it, false) }
                                }.toMap()
                        },
                        updateFromSource = { _, _, _, _ ->
                            if (kind != SourceActionKind.UPDATE_FROM) error("Wrong writer")
                            writes++
                            canonical = canonical.copy(sequence = canonical.sequence.copy(revision = 8))
                        },
                        updateSource = { _, _, _, _, _ ->
                            if (kind != SourceActionKind.UPDATE_SOURCE) error("Wrong writer")
                            writes++
                            revisions[sourceId] = 5
                        },
                        saveAsNewSource = { _, _, _, _ ->
                            if (kind != SourceActionKind.SAVE_NEW) error("Wrong writer")
                            writes++
                            revisions[replacementId] = 1
                            canonical =
                                canonical
                                    .withLinkedSource(replacementId)
                                    .copy(sequence = canonical.sequence.copy(revision = 8))
                        },
                    )
                controller.awaitReady()

                when (kind) {
                    SourceActionKind.UPDATE_FROM -> controller.updateStepFromSource(stepId)
                    SourceActionKind.UPDATE_SOURCE -> controller.updateSourceTemplate(stepId)
                    SourceActionKind.SAVE_NEW -> controller.saveStepAsNewTemplate(stepId)
                }
                val failure = controller.awaitFailure()
                assertTrue(failure.committedAwaitingReload)
                val oldDraft = controller.state.value.readyDraft()
                controller.updateDraft { it.copy(name = "must stay blocked") }
                assertEquals(oldDraft, controller.state.value.readyDraft())

                controller.retry()
                withTimeout(2_000) { controller.state.first { it.appliedGeneration == 1L } }

                assertEquals(1, writes)
                assertEquals(3, loads)
                assertEquals(canonical.toAuthoringDraft(), controller.state.value.readyDraft())
                if (kind == SourceActionKind.UPDATE_SOURCE) {
                    assertEquals(5, controller.state.value.activeRevision(sourceId))
                }
                if (kind == SourceActionKind.SAVE_NEW) {
                    assertEquals(replacementId, controller.state.value.stepSourceIds[stepId])
                }
                controller.close()
            }
        }

    @Test
    fun archivedExistingSequenceFailsInsteadOfCreatingANewDraft() =
        runBlocking {
            val state = authoringState().copy(sequence = authoringState().sequence.copy(deletedAt = Instant.EPOCH))
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(state.sequence.id),
                    { state },
                    { emptyList() },
                    { _, _, _ -> error("Create must not run") },
                    { _, _, _, _ -> error("Save must not run") },
                    { Instant.EPOCH },
                )

            withTimeout(2_000) { controller.state.first { it.load is SequenceTemplateEditorLoad.Failure } }

            assertInstanceOf(SequenceTemplateEditorLoad.Failure::class.java, controller.state.value.load)
            assertFalse(controller.state.value.load is SequenceTemplateEditorLoad.Ready)
            controller.close()
        }

    @Test
    fun invalidRepeatTextIsDirtyAndBlocksTheRepositoryWriteWithoutRestoringTheOldValue() =
        runBlocking {
            var writes = 0
            var exits = 0
            val state = authoringState()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(state.sequence.id),
                    { state },
                    { emptyList() },
                    { _, _, _ -> error("New save is not used") },
                    { _, _, _, _ ->
                        writes++
                        state.sequence
                    },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitReady()
            val repeat =
                controller.state.value
                    .readyDraft()!!
                    .nodes
                    .filterIsInstance<SequenceNodeDraft.Repeat>()
                    .single()
            val key = SequenceEditorInputKey.repeatCount(repeat.identity)

            controller.updateNumberInput(key, "0", repeat.value.repeatCount.toLong(), 1, Int.MAX_VALUE.toLong()) {
                error("Invalid text must not update the draft")
            }
            controller.requestBack { exits++ }
            controller.save()

            assertEquals("0", controller.state.value.inputText(key, "2"))
            assertTrue(controller.state.value.inputIsInvalid(key))
            assertTrue(controller.state.value.discardConfirmationVisible)
            assertEquals(0, exits)
            assertEquals(0, writes)
            assertEquals(2, repeat.value.repeatCount)
            controller.close()
        }

    @Test
    fun changingSourceLinkedSnapshotFieldUnitCreatesALocalReplacementIdentity() {
        val sourceId = ActivityTemplateFieldId("source")
        val field =
            ActivitySnapshotFieldDraft(
                DraftIdentity.Existing(ActivitySnapshotFieldId("snapshot-field")),
                sourceId,
                0,
                "Distance",
                "Route distance",
                CustomFieldType.NUMBER,
                unit = "km",
            )

        val replacement = field.withCompatibleUnitReplacement("m", DraftIdentity.New("replacement"))

        assertEquals(DraftIdentity.New("replacement"), replacement.identity)
        assertEquals(null, replacement.sourceFieldId)
        assertEquals("Route distance", replacement.nameAtCreation)
        assertEquals(null, replacement.localNameOverride)
        assertEquals("m", replacement.unit)
    }

    @Test
    fun changingCommittedLocalSnapshotFieldTypeOrUnitCreatesAReplacementIdentity() {
        val field =
            ActivitySnapshotFieldDraft(
                DraftIdentity.Existing(ActivitySnapshotFieldId("local-field")),
                null,
                0,
                "Distance",
                type = CustomFieldType.NUMBER,
                unit = "km",
            )

        assertEquals(
            DraftIdentity.New("unit-replacement"),
            field.withCompatibleUnitReplacement("m", DraftIdentity.New("unit-replacement")).identity,
        )
        assertEquals(
            DraftIdentity.New("type-replacement"),
            field.withCompatibleTypeReplacement(CustomFieldType.TEXT, DraftIdentity.New("type-replacement")).identity,
        )
    }

    @Test
    fun manipulationDiscardRestoresSessionEntryDraftAndNeverWrites() =
        runBlocking {
            var writes = 0
            val state = authoringState()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(state.sequence.id),
                    { state },
                    { emptyList() },
                    { _, _, _ -> error("Create must not run") },
                    { _, _, _, _ ->
                        writes++
                        state.sequence
                    },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "ordinary unsaved") }
            val baseline = requireNotNull(controller.state.value.readyDraft())
            val step =
                baseline.nodes
                    .filterIsInstance<SequenceNodeDraft.Step>()
                    .single()
                    .identity
            val repeat =
                baseline.nodes
                    .filterIsInstance<SequenceNodeDraft.Repeat>()
                    .single()
                    .identity

            controller.enterManipulation(step)
            assertTrue(controller.moveManipulation(step, SequenceDropDestination(repeat, 1)))
            assertTrue(
                controller.state.value.manipulation
                    ?.canUndo == true,
            )
            controller.discardManipulation()

            assertEquals(baseline, controller.state.value.readyDraft())
            assertEquals(null, controller.state.value.manipulation)
            assertEquals(0, writes)
            controller.close()
        }

    @Test
    fun manipulationUndoRedoRestoresNonStructuralDraftAndTextInputTogether() =
        runBlocking {
            val state = authoringState()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(state.sequence.id),
                    { state },
                    { emptyList() },
                    { _, _, _ -> error("Create must not run") },
                    { _, _, _, _ -> state.sequence },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitReady()
            val entry = requireNotNull(controller.state.value.readyDraft())
            val step =
                entry.nodes
                    .filterIsInstance<SequenceNodeDraft.Step>()
                    .single()
                    .identity
            val key = SequenceEditorInputKey.stepCountdown(step)

            controller.enterManipulation(step)
            controller.updateNumberInput(key, "9", 0, 0) { seconds ->
                controller.updateDraft { it.withStepCountdown(step, seconds) }
            }
            val edited = requireNotNull(controller.state.value.readyDraft())
            val editedInputs = controller.state.value.textInputs
            assertTrue(
                controller.state.value.manipulation
                    ?.canUndo == true,
            )

            assertTrue(controller.undoManipulation())
            assertEquals(entry, controller.state.value.readyDraft())
            assertTrue(
                controller.state.value.textInputs
                    .isEmpty(),
            )
            assertTrue(controller.redoManipulation())
            assertEquals(edited, controller.state.value.readyDraft())
            assertEquals(editedInputs, controller.state.value.textInputs)
            controller.close()
        }

    @Test
    fun manipulationDiscardRestoresPreExistingInvalidInputExactly() =
        runBlocking {
            var writes = 0
            var exits = 0
            val state = authoringState()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(state.sequence.id),
                    { state },
                    { emptyList() },
                    { _, _, _ -> error("Create must not run") },
                    { _, _, _, _ ->
                        writes++
                        state.sequence
                    },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitReady()
            val step =
                requireNotNull(controller.state.value.readyDraft())
                    .nodes
                    .filterIsInstance<SequenceNodeDraft.Step>()
                    .single()
                    .identity
            val key = SequenceEditorInputKey.stepCountdown(step)
            controller.updateNumberInput(key, "invalid", 0, 0) { error("Invalid input must not update the draft") }
            val entryDraft = requireNotNull(controller.state.value.readyDraft())
            val entryInputs = controller.state.value.textInputs

            controller.enterManipulation(step)
            controller.updateNumberInput(key, "9", 0, 0) { seconds ->
                controller.updateDraft { it.withStepCountdown(step, seconds) }
            }
            controller.discardManipulation()

            assertEquals(entryDraft, controller.state.value.readyDraft())
            assertEquals(entryInputs, controller.state.value.textInputs)
            assertTrue(controller.state.value.inputIsInvalid(key))
            controller.requestBack { exits++ }
            assertTrue(controller.state.value.discardConfirmationVisible)
            assertEquals(0, exits)
            assertEquals(0, writes)
            controller.close()
        }

    @Test
    fun manipulationDiscardRemovesValidAndInvalidInputsCreatedAfterCleanEntry() =
        runBlocking {
            var writes = 0
            var exits = 0
            val state = authoringState()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(state.sequence.id),
                    { state },
                    { emptyList() },
                    { _, _, _ -> error("Create must not run") },
                    { _, _, _, _ ->
                        writes++
                        state.sequence
                    },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitReady()
            val baseline = requireNotNull(controller.state.value.readyDraft())
            val step =
                baseline.nodes
                    .filterIsInstance<SequenceNodeDraft.Step>()
                    .single()
                    .identity
            val key = SequenceEditorInputKey.stepCountdown(step)

            controller.enterManipulation(step)
            controller.updateNumberInput(key, "9", 0, 0) { seconds ->
                controller.updateDraft { it.withStepCountdown(step, seconds) }
            }
            controller.updateNumberInput(key, "invalid", 0, 0) { error("Invalid input must not update the draft") }
            assertTrue(controller.state.value.inputIsInvalid(key))
            controller.discardManipulation()

            assertEquals(baseline, controller.state.value.readyDraft())
            assertTrue(
                controller.state.value.textInputs
                    .isEmpty(),
            )
            assertFalse(controller.state.value.inputIsInvalid(key))
            controller.requestBack { exits++ }
            assertEquals(1, exits)
            assertFalse(controller.state.value.discardConfirmationVisible)
            controller.save()
            assertTrue(controller.state.value.save is SequenceTemplateEditorSave.Committed)
            assertEquals(0, writes)
            controller.close()
        }

    @Test
    fun staleApplyRetainsManipulationDraftAndHistory() =
        runBlocking {
            val canonical = authoringState()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                    { canonical },
                    { emptyList() },
                    { _, _, _ -> error("Create must not run") },
                    { _, _, _, _ -> error("SequenceTemplate revision changed concurrently") },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitReady()
            val initial = requireNotNull(controller.state.value.readyDraft())
            val step =
                initial.nodes
                    .filterIsInstance<SequenceNodeDraft.Step>()
                    .single()
                    .identity
            val repeat =
                initial.nodes
                    .filterIsInstance<SequenceNodeDraft.Repeat>()
                    .single()
                    .identity
            controller.enterManipulation(step)
            controller.moveManipulation(step, SequenceDropDestination(repeat, 1))
            val submitted = controller.state.value.readyDraft()

            controller.applyManipulation()
            controller.awaitFailure()

            assertEquals(submitted, controller.state.value.readyDraft())
            assertTrue(
                controller.state.value.manipulation
                    ?.canUndo == true,
            )
            assertEquals(7, canonical.sequence.revision)
            controller.close()
        }

    @Test
    fun observableNewApplyCompletionImmediatelyAcceptsEditAndDoneSave() =
        runBlocking {
            var creates = 0
            var saves = 0
            var savedName: String? = null
            var canonical: SequenceTemplateAuthoringState? = null
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.New,
                    { canonical },
                    { emptyList() },
                    { draft, _, _ ->
                        creates++
                        canonical = draft.toFakeAuthoring(SequenceTemplateId("created"), 1)
                        requireNotNull(canonical).sequence
                    },
                    { id, revision, draft, _ ->
                        saves++
                        savedName = draft.name
                        assertEquals(SequenceTemplateId("created"), id)
                        assertEquals(1, revision)
                        canonical = draft.toFakeAuthoring(id, 2)
                        requireNotNull(canonical).sequence
                    },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitReady()
            controller.updateDraft {
                it.copy(
                    name = "New sequence",
                    nodes =
                        listOf(
                            SequenceNodeDraft.Step(localStep("one", 0)),
                            SequenceNodeDraft.Step(localStep("two", 1)),
                        ),
                )
            }
            controller.enterManipulation(DraftIdentity.New("one"))
            controller.moveManipulation(DraftIdentity.New("two"), SequenceDropDestination(position = 0))
            val immediateSave =
                launch(inlineDispatcher, start = CoroutineStart.UNDISPATCHED) {
                    controller.state.first { it.appliedGeneration == 1L }
                    controller.updateDraft { it.copy(name = "After apply") }
                    controller.save()
                }
            controller.applyManipulation()
            immediateSave.join()

            assertEquals(1, creates)
            val firstSnapshot = (canonical?.sequence?.nodes?.first() as ActivityStep).activitySnapshotId
            assertEquals("two", firstSnapshot.value)
            assertEquals(1L, controller.state.value.appliedGeneration)
            assertEquals(1, saves)
            assertEquals("After apply", savedName)
            assertTrue(controller.state.value.save is SequenceTemplateEditorSave.Committed)
            controller.close()
        }

    @Test
    fun duplicateSaveWhileSavingDoesNotStartAnotherWrite() =
        runBlocking {
            val canonical = authoringState()
            val writerStarted = CompletableDeferred<Unit>()
            val releaseWriter = CompletableDeferred<Unit>()
            var writes = 0
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                    { canonical },
                    { emptyList() },
                    { _, _, _ -> error("Create is not used") },
                    { _, _, _, _ ->
                        writes++
                        writerStarted.complete(Unit)
                        releaseWriter.await()
                        canonical.sequence
                    },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "Changed") }

            controller.save()
            writerStarted.await()
            controller.save()
            assertEquals(1, writes)
            releaseWriter.complete(Unit)
            withTimeout(2_000) { controller.state.first { it.save is SequenceTemplateEditorSave.Committed } }

            assertEquals(1, writes)
            controller.close()
        }

    @Test
    fun applyRetryAfterCommittedReloadFailureDoesNotRepeatWriter() =
        runBlocking {
            val initial = authoringState()
            var canonical = initial
            var loads = 0
            var writes = 0
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(initial.sequence.id),
                    {
                        loads++
                        if (loads == 2) error("temporary reload failure")
                        canonical
                    },
                    { emptyList() },
                    { _, _, _ -> error("Create is not used") },
                    { id, revision, draft, _ ->
                        writes++
                        assertEquals(7, revision)
                        canonical = draft.toFakeAuthoring(id, 8)
                        canonical.sequence
                    },
                    { Instant.EPOCH.plusSeconds(1) },
                )
            controller.awaitReady()
            val draft = requireNotNull(controller.state.value.readyDraft())
            val step =
                draft.nodes
                    .filterIsInstance<SequenceNodeDraft.Step>()
                    .single()
                    .identity
            val repeat =
                draft.nodes
                    .filterIsInstance<SequenceNodeDraft.Repeat>()
                    .single()
                    .identity
            controller.enterManipulation(step)
            controller.moveManipulation(step, SequenceDropDestination(repeat, 1))

            val immediateRetry =
                launch(inlineDispatcher, start = CoroutineStart.UNDISPATCHED) {
                    controller.state.first { it.save is SequenceTemplateEditorSave.Failure }
                    controller.applyManipulation()
                }
            controller.applyManipulation()
            immediateRetry.join()
            withTimeout(2_000) { controller.state.first { it.appliedGeneration == 1L } }

            assertEquals(1, writes)
            assertEquals(3, loads)
            assertEquals(canonical.toAuthoringDraft(), controller.state.value.readyDraft())
            assertEquals(null, controller.state.value.manipulation)
            controller.close()
        }

    private suspend fun SequenceTemplateEditorController.awaitReady() {
        withTimeout(2_000) { state.first { it.load is SequenceTemplateEditorLoad.Ready } }
    }

    private suspend fun SequenceTemplateEditorController.awaitFailure(): SequenceTemplateEditorSave.Failure =
        withTimeout(2_000) {
            state.first { it.save is SequenceTemplateEditorSave.Failure }.save as SequenceTemplateEditorSave.Failure
        }

    private fun SequenceTemplateEditorState.activeRevision(id: ActivityTemplateId): Long? =
        (sourceStatuses[id] as? SequenceEditorStepSource.Active)?.revision

    private enum class SourceActionKind {
        UPDATE_FROM,
        UPDATE_SOURCE,
        SAVE_NEW,
    }

    private fun authoringState(): SequenceTemplateAuthoringState {
        val firstSnapshot = ActivitySnapshotId("first")
        val secondSnapshot = ActivitySnapshotId("second")
        val firstStep = ActivityStep(SequenceNodeId("step-1"), 0, firstSnapshot)
        val repeatStep = ActivityStep(SequenceNodeId("step-2"), 0, secondSnapshot)
        val sequence =
            SequenceTemplate(
                SequenceTemplateId("sequence"),
                "Workout",
                null,
                StatisticsSeriesId("series"),
                revision = 7,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                nodes = listOf(firstStep, SequenceRepeatBlock(SequenceNodeId("repeat"), 1, 2, listOf(repeatStep))),
            )
        return SequenceTemplateAuthoringState(
            sequence,
            mapOf(
                firstSnapshot to snapshot(firstSnapshot, "Warmup"),
                secondSnapshot to snapshot(secondSnapshot, "Rest"),
            ),
        )
    }

    private fun SequenceTemplateAuthoringState.withLinkedSource(sourceId: ActivityTemplateId) =
        copy(
            activitySnapshots =
                activitySnapshots.mapValues { (_, snapshot) ->
                    if (snapshot.id == ActivitySnapshotId("first")) {
                        snapshot.copy(sourceTemplateId = sourceId, sourceRevision = 3)
                    } else {
                        snapshot
                    }
                },
        )

    private fun snapshot(
        id: ActivitySnapshotId,
        name: String,
    ) = ActivityConfigSnapshot(
        id = id,
        name = name,
        shortComment = null,
        timeTrackingMode = com.alexandr5476.lifetracing.domain.TimeTrackingMode.STOPWATCH,
        timerTarget = null,
        sourceTemplateId = null,
        sourceRevision = null,
        statisticsSeriesId = null,
        locallyModified = false,
        createdAt = Instant.EPOCH,
    )

    private fun localStep(
        key: String,
        position: Int,
    ) = ActivityStepDraft(
        DraftIdentity.New(key),
        position,
        StepActivityDraft.Local(
            com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft(
                key,
                null,
                com.alexandr5476.lifetracing.domain.TimeTrackingMode.STOPWATCH,
                null,
            ),
        ),
    )

    private fun SequenceTemplateDraft.withStepCountdown(
        identity: DraftIdentity<SequenceNodeId>,
        seconds: Long,
    ): SequenceTemplateDraft =
        copy(
            nodes =
                nodes.map { node ->
                    if (node is SequenceNodeDraft.Step && node.identity == identity) {
                        SequenceNodeDraft.Step(
                            node.value.copy(
                                overrides = node.value.overrides.copy(startCountdown = Duration.ofSeconds(seconds)),
                            ),
                        )
                    } else {
                        node
                    }
                },
        )

    private fun SequenceTemplateDraft.toFakeAuthoring(
        id: SequenceTemplateId,
        revision: Long,
    ): SequenceTemplateAuthoringState {
        val snapshots = mutableMapOf<ActivitySnapshotId, ActivityConfigSnapshot>()

        fun step(value: ActivityStepDraft): ActivityStep {
            val key =
                when (val identity = value.identity) {
                    is DraftIdentity.Existing -> identity.id.value
                    is DraftIdentity.New -> identity.key
                }
            val snapshotId = ActivitySnapshotId(key)
            val configuration =
                when (val activity = value.activity) {
                    is StepActivityDraft.Existing -> activity.configuration
                    is StepActivityDraft.Local -> activity.configuration
                    else -> error("Fake authoring does not duplicate")
                }
            snapshots[snapshotId] = snapshot(snapshotId, configuration.name)
            return ActivityStep(SequenceNodeId(key), value.position, snapshotId, value.overrides)
        }
        val committedNodes =
            nodes.map { node ->
                when (node) {
                    is SequenceNodeDraft.Step -> step(node.value)
                    is SequenceNodeDraft.Repeat ->
                        SequenceRepeatBlock(
                            (node.identity as DraftIdentity.Existing).id,
                            node.position,
                            node.value.repeatCount,
                            node.value.children.map(::step),
                        )
                }
            }
        return SequenceTemplateAuthoringState(
            SequenceTemplate(
                id,
                name,
                shortComment,
                StatisticsSeriesId("series-$revision"),
                revision,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(revision),
                nodes = committedNodes,
            ),
            snapshots,
        )
    }
}
