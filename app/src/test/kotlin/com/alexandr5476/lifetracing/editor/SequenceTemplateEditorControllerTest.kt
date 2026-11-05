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
import com.alexandr5476.lifetracing.domain.SequenceCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
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
    fun missingManipulationSelectionCannotEnterOrReplaceCurrentSelection() =
        runBlocking {
            val canonical = authoringState()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                    { canonical },
                    { emptyList() },
                    { _, _, _ -> error("No write") },
                    { _, _, _, _ -> error("No write") },
                    { Instant.EPOCH },
                )
            controller.awaitReady()
            val missing = DraftIdentity.Existing(SequenceNodeId("missing"))
            val first = DraftIdentity.Existing(SequenceNodeId("step-1"))
            controller.enterManipulation(missing)
            assertEquals(null, controller.state.value.manipulation)
            controller.enterManipulation(first)
            val selected = controller.state.value.manipulation
            controller.selectManipulation(missing)
            controller.enterManipulation(missing)
            assertEquals(selected, controller.state.value.manipulation)
            controller.discardManipulation()
            controller.close()
        }

    @Test
    fun canonicalEditorLoadsNeverReadThePickerCatalog() =
        runBlocking {
            var pickerReads = 0
            var sequenceReads = 0
            val canonical = authoringState().withLinkedSource(ActivityTemplateId("activity"))
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(SequenceTemplateId("sequence")),
                    {
                        sequenceReads++
                        canonical
                    },
                    {
                        pickerReads++
                        error("Picker catalog must not be read for canonical hydration")
                    },
                    { _, _, _ ->
                        error("New save is not used")
                    },
                    { _, _, _, _ ->
                        canonical.sequence
                    },
                    { Instant.EPOCH },
                    loadSourceStatuses = {
                        val source = ActivityTemplateId("activity")
                        mapOf(source to ActivityTemplateSourceStatus(source, 1, false))
                    },
                    updateFromSource = { _, _, _, _ -> },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "Canonical") }
            controller.applyManipulation()
            withTimeout(2_000) {
                controller.state.first {
                    it.appliedGeneration == 1L && sequenceReads == 2
                }
            }
            controller.updateStepFromSource(SequenceNodeId("step-1"))
            withTimeout(2_000) {
                controller.state.first {
                    it.save is SequenceTemplateEditorSave.Idle && it.appliedGeneration == 2L
                }
            }

            assertEquals(0, pickerReads)
            assertEquals(3, sequenceReads)
            controller.close()
        }

    @Test
    fun applyDeliveryGenerationSurvivesExplicitLoadFailureAndSuccess() =
        runBlocking {
            val canonical = authoringState()
            var failRead = false
            var writes = 0
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                    {
                        if (failRead) error("Explicit reload failed")
                        canonical
                    },
                    { emptyList() },
                    { _, _, _ -> error("No create") },
                    { _, _, _, _ ->
                        writes++
                        canonical.sequence
                    },
                    { Instant.EPOCH },
                )
            controller.awaitReady()
            val session = SequenceTemplateEditorRouteSession(controller.target, controller)
            var deliveries = 0
            controller.updateDraft { it.copy(name = "First Apply") }
            controller.applyManipulation()
            withTimeout(2_000) { controller.state.first { it.appliedGeneration == 1L } }
            session.deliverApplied(controller.state.value.appliedGeneration) { deliveries++ }
            failRead = true
            controller.retry()
            withTimeout(2_000) { controller.state.first { it.load is SequenceTemplateEditorLoad.Failure } }
            assertEquals(1L, controller.state.value.appliedGeneration)
            session.deliverApplied(controller.state.value.appliedGeneration) { error("Already acknowledged") }
            failRead = false
            controller.retry()
            controller.awaitReady()
            assertEquals(1L, controller.state.value.appliedGeneration)
            session.deliverApplied(controller.state.value.appliedGeneration) { error("Already acknowledged") }
            controller.updateDraft { it.copy(name = "Second Apply") }
            controller.applyManipulation()
            withTimeout(2_000) { controller.state.first { it.appliedGeneration == 2L } }
            session.deliverApplied(controller.state.value.appliedGeneration) { deliveries++ }
            assertEquals(2, deliveries)
            assertEquals(2, writes)
            controller.close()
        }

    @Test
    fun pickerLoadsBoundedPagesOnlyWhenOpened() =
        runBlocking {
            var firstPageReads = 0
            var nextPageReads = 0
            val firstPage =
                (1..50).map { SequenceEditorActivityChoice(ActivityTemplateId("$it"), "Activity $it") }
            val extra = SequenceEditorActivityChoice(ActivityTemplateId("51"), "Activity 51")
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.New,
                    { null },
                    {
                        firstPageReads++
                        firstPage
                    },
                    { _, _, _ ->
                        error("New save is not used")
                    },
                    { _, _, _, _ ->
                        error("Existing save is not used")
                    },
                    { Instant.EPOCH },
                    loadMoreActivities = {
                        nextPageReads++
                        listOf(extra)
                    },
                )
            controller.awaitReady()
            assertEquals(0, firstPageReads)

            controller.loadActivityPicker()
            withTimeout(2_000) { controller.state.first { it.availableActivities.size == 50 } }
            controller.loadMoreActivityPicker()
            withTimeout(2_000) { controller.state.first { it.availableActivities.size == 51 } }

            assertEquals(1, firstPageReads)
            assertEquals(1, nextPageReads)
            assertEquals(
                extra,
                controller.state.value.availableActivities
                    .last(),
            )
            controller.close()
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
                        assertEquals(6L + writes, revision)
                        canonical = draft.toFakeAuthoring(id, revision + 1)
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
                    controller.retry()
                }
            controller.applyManipulation()
            immediateRetry.join()
            withTimeout(2_000) { controller.state.first { it.appliedGeneration == 1L } }

            assertEquals(1, writes)
            assertEquals(3, loads)
            assertEquals(canonical.toAuthoringDraft(), controller.state.value.readyDraft())
            assertEquals(null, controller.state.value.manipulation)
            controller.updateDraft { it.copy(name = "Recovered edit") }
            controller.save()
            withTimeout(2_000) { controller.state.first { it.save is SequenceTemplateEditorSave.Committed } }
            assertEquals(2, writes)
            controller.close()
        }

    @Test
    @Suppress("LongMethod") // Both obsolete outcomes and closure share explicit read gates.
    fun obsoleteSuccessAndFailureCannotReplaceNewerRetryOrPublishAfterClose() =
        runBlocking {
            for (fail in listOf(false, true)) {
                val oldRead = CompletableDeferred<Unit>()
                val oldFinished = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                val canonical = authoringState()
                var reads = 0
                val controller =
                    SequenceTemplateEditorController(
                        this,
                        SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                        {
                            if (++reads == 1) {
                                started.complete(Unit)
                                oldRead.await()
                                oldFinished.complete(Unit)
                                if (fail) error("obsolete failure")
                            }
                            canonical
                        },
                        { emptyList() },
                        { _, _, _ -> error("No write") },
                        { _, _, _, _ -> error("No write") },
                        { Instant.EPOCH },
                    )
                started.await()
                controller.retry()
                controller.awaitReady()
                controller.updateDraft { it.copy(name = "Newer draft") }
                oldRead.complete(Unit)
                oldFinished.await()
                kotlinx.coroutines.yield()
                assertEquals(
                    "Newer draft",
                    controller.state.value
                        .readyDraft()
                        ?.name,
                )
                controller.close()
            }
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val canonical = authoringState()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                    {
                        release.await()
                        finished.complete(Unit)
                        canonical
                    },
                    { emptyList() },
                    { _, _, _ -> error("No write") },
                    { _, _, _, _ -> error("No write") },
                    { Instant.EPOCH },
                )
            controller.close()
            val closed = controller.state.value
            release.complete(Unit)
            finished.await()
            kotlinx.coroutines.yield()
            assertEquals(closed, controller.state.value)
        }

    @Test
    fun dismissedDiscardAndCommittedCallbacksCannotExitEditOrWrite() =
        runBlocking {
            val canonical = authoringState()
            var writes = 0
            var exits = 0
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                    { canonical },
                    { emptyList() },
                    { _, _, _ -> error("No create") },
                    { _, _, _, _ ->
                        writes++
                        canonical.sequence
                    },
                    { Instant.EPOCH },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "Changed") }
            controller.requestBack { exits++ }
            val dismissedDialog =
                SequenceTemplateEditorActions(
                    controller,
                    controller.state.value.generation,
                    controller.state.value.discardGeneration,
                )
            controller.updateDraft { it.copy(name = "Obsolete editor callback") }
            controller.save()
            assertEquals(
                "Changed",
                controller.state.value
                    .readyDraft()
                    ?.name,
            )
            assertEquals(0, writes)
            controller.dismissDiscard()
            controller.discard { exits++ }
            assertEquals(0, exits)
            controller.requestBack { exits++ }
            dismissedDialog.invoke { controller.discard { exits++ } }
            assertTrue(controller.state.value.discardConfirmationVisible)
            assertEquals(0, exits)
            controller.dismissDiscard()
            controller.save()
            withTimeout(2_000) { controller.state.first { it.save is SequenceTemplateEditorSave.Committed } }
            val committed = controller.state.value
            controller.save()
            controller.retry()
            controller.updateDraft { it.copy(name = "Too late") }
            controller.requestBack { exits++ }
            controller.discard { exits++ }
            assertEquals(committed, controller.state.value)
            assertEquals(1, writes)
            assertEquals(0, exits)
            controller.close()
        }

    @Test
    @Suppress("LongMethod") // The retained lease follows one draft through identity and lifetime changes.
    fun presentationLeaseResolvesCurrentFieldAndOptionIdentitiesAndRejectsObsoleteOwners() =
        runBlocking {
            val canonical = authoringState()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                    { canonical },
                    { emptyList() },
                    { _, _, _ -> error("No create") },
                    { _, _, _, _ -> error("No save") },
                    { Instant.EPOCH },
                )
            controller.awaitReady()
            val first = SequenceFieldDraft(DraftIdentity.New("first"), 0, "Same", CustomFieldType.TEXT)
            val option1 = SequenceCategoryOptionDraft(DraftIdentity.New("option1"), 0, "Same")
            val option2 = SequenceCategoryOptionDraft(DraftIdentity.New("option2"), 1, "Same")
            val second =
                SequenceFieldDraft(
                    DraftIdentity.New("second"),
                    1,
                    "Same",
                    CustomFieldType.CATEGORY,
                    categoryOptions = listOf(option1, option2),
                )
            controller.updateDraft { it.copy(fields = listOf(first, second)) }
            val actions = SequenceTemplateEditorActions(controller, controller.state.value.generation)
            actions.removeField(first)
            actions.field(second) { it.copy(name = "Renamed", categoryOptions = listOf(option2.copy(position = 0))) }
            actions.option(second, option2) { field, option -> field.copy(defaultCategoryOption = option.identity) }
            assertEquals(
                option2.identity,
                controller.state.value
                    .readyDraft()!!
                    .fields
                    .single()
                    .defaultCategoryOption,
            )
            val before = controller.state.value
            actions.field(first) { it.copy(name = "Removed") }
            actions.removeField(first)
            actions.option(second, option1) { field, _ -> field.copy(defaultCategoryOption = option1.identity) }
            assertEquals(before, controller.state.value)
            actions.field(second) {
                it.copy(type = CustomFieldType.TEXT, categoryOptions = emptyList(), defaultCategoryOption = null)
            }
            val changedType = controller.state.value
            actions.field(second) { it.copy(name = "Obsolete category callback") }
            assertEquals(changedType, controller.state.value)
            actions.visible = false
            actions.update { it.copy(name = "Hidden") }
            actions.visible = true
            actions.attached = false
            actions.update { it.copy(name = "Disposed") }
            assertEquals(changedType, controller.state.value)
            actions.attached = true
            controller.retry()
            controller.awaitReady()
            actions.update { it.copy(name = "Obsolete generation") }
            assertEquals(
                canonical.sequence.name,
                controller.state.value
                    .readyDraft()
                    ?.name,
            )
            controller.close()
            actions.update { it.copy(name = "Closed") }
            assertEquals(
                canonical.sequence.name,
                controller.state.value
                    .readyDraft()
                    ?.name,
            )
        }

    @Test
    fun presentationActionsCannotCrossManipulationAdmissionOrExitButControllerEditsRemainSupported() =
        runBlocking {
            val canonical = authoringState()
            var writes = 0
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                    { canonical },
                    { emptyList() },
                    { _, _, _ -> error("No create") },
                    { _, _, _, _ ->
                        writes++
                        canonical.sequence
                    },
                    { Instant.EPOCH },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(shortComment = "Pre-entry draft") }
            val normal = SequenceTemplateEditorActions(controller, controller.state.value.generation)
            val step = DraftIdentity.Existing(SequenceNodeId("step-1"))
            controller.enterManipulation(step)
            val entered = controller.state.value
            normal.update { it.copy(name = "Obsolete form") }
            normal.invoke(controller::save)
            assertEquals(entered, controller.state.value)
            assertEquals(0, writes)

            val manipulation = SequenceTemplateEditorActions(controller, entered.generation)
            controller.updateDraft { it.copy(shortComment = "Current manipulation edit") }
            assertTrue(
                controller.state.value.manipulation
                    ?.canUndo == true,
            )
            assertTrue(controller.undoManipulation())
            assertEquals(
                "Pre-entry draft",
                controller.state.value
                    .readyDraft()
                    ?.shortComment,
            )
            controller.discardManipulation()
            val discarded = controller.state.value
            manipulation.invoke(controller::applyManipulation)
            normal.update { it.copy(name = "Must not revive") }
            assertEquals(discarded, controller.state.value)
            assertEquals(0, writes)
            controller.enterManipulation(step)
            manipulation.invoke(controller::applyManipulation)
            assertEquals(0, writes)
            val current = SequenceTemplateEditorActions(controller, controller.state.value.generation)
            current.invoke(controller::applyManipulation)
            withTimeout(2_000) { controller.state.first { it.appliedGeneration == 1L } }
            assertEquals(1, writes)
            controller.close()
        }

    @Test
    fun currentManipulationActionsUndoAndRedoAnInitiallyMissingComment() =
        runBlocking {
            val canonical = authoringState()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                    { canonical },
                    { emptyList() },
                    { _, _, _ -> error("No create") },
                    { _, _, _, _ -> error("No save") },
                    { Instant.EPOCH },
                )
            controller.awaitReady()
            val baseline = requireNotNull(controller.state.value.readyDraft())
            assertEquals(null, baseline.shortComment)
            controller.enterManipulation(DraftIdentity.Existing(SequenceNodeId("step-1")))
            val actions = SequenceTemplateEditorActions(controller, controller.state.value.generation)
            val edited = baseline.copy(shortComment = "Current controller edit")
            controller.updateDraft { edited }

            actions.invoke { controller.undoManipulation() }
            assertEquals(baseline, controller.state.value.readyDraft())
            assertTrue(
                controller.state.value.manipulation!!
                    .canRedo,
            )
            actions.invoke { controller.redoManipulation() }
            assertEquals(edited, controller.state.value.readyDraft())
            assertTrue(
                controller.state.value.manipulation!!
                    .canUndo,
            )
            controller.close()
        }

    @Test
    fun ordinarySaveFailureRetainsRawCountdownAndDraftForExplicitWriterRetry() =
        runBlocking {
            var canonical = authoringState()
            var writes = 0
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                    { canonical },
                    { emptyList() },
                    { _, _, _ -> error("No create") },
                    { id, revision, draft, _ ->
                        if (++writes == 1) error("disk full")
                        canonical = draft.toFakeAuthoring(id, revision + 1)
                        canonical.sequence
                    },
                    { Instant.EPOCH },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "Retained") }
            val key = SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN
            for (text in listOf("", "-1", "bad", "99999999999999999999999999")) {
                controller.updateNumberInput(key, text, 0, 0) { error("Invalid countdown accepted") }
                controller.save()
                assertEquals(text, controller.state.value.inputText(key, "unused"))
                assertTrue(
                    controller.state.value.isDirty(controller.state.value.load as SequenceTemplateEditorLoad.Ready),
                )
                assertEquals(0, writes)
            }
            controller.updateNumberInput(key, "0", 0, 0) { value ->
                controller.updateDraft {
                    it.copy(
                        settings = it.settings.copy(sequenceStartCountdown = Duration.ofSeconds(value)),
                    )
                }
            }
            controller.save()
            controller.awaitFailure()
            assertEquals(
                "Retained",
                controller.state.value
                    .readyDraft()
                    ?.name,
            )
            controller.updateDraft { it.copy(shortComment = "Explicit retry") }
            controller.save()
            withTimeout(2_000) { controller.state.first { it.save is SequenceTemplateEditorSave.Committed } }
            assertEquals(2, writes)
            assertEquals("Explicit retry", canonical.sequence.shortComment)
            controller.close()
        }

    @Test
    fun doneRecoveryRejectsEveryDraftMutationAndRetriesCanonicalReadWithoutWriter() =
        runBlocking {
            var canonical = authoringState()
            var reads = 0
            var writes = 0
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.Existing(canonical.sequence.id),
                    { if (++reads == 2) error("reload unavailable") else canonical },
                    { emptyList() },
                    { _, _, _ -> error("No create") },
                    { id, revision, draft, _ ->
                        writes++
                        started.complete(Unit)
                        release.await()
                        canonical = draft.toFakeAuthoring(id, revision + 1)
                        canonical.sequence
                    },
                    { Instant.EPOCH },
                )
            controller.awaitReady()
            controller.updateDraft { it.copy(name = "Saved") }
            controller.save()
            started.await()
            val saving = controller.state.value
            controller.updateDraft { it.copy(name = "Too late") }
            controller.updateNumberInput(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN, "bad", 0, 0) {}
            controller.save()
            controller.retry()
            controller.requestBack { error("Saving exited") }
            assertEquals(saving, controller.state.value)
            release.complete(Unit)
            assertTrue(controller.awaitFailure().committedAwaitingReload)
            val recovering = controller.state.value
            controller.updateDraft { it.copy(name = "Too late") }
            controller.clearNumberInput(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN)
            controller.save()
            controller.discard { error("Recovery exited") }
            assertEquals(recovering, controller.state.value)
            controller.retry()
            withTimeout(2_000) { controller.state.first { it.save is SequenceTemplateEditorSave.Committed } }
            controller.save()
            assertEquals(1, writes)
            assertEquals(3, reads)
            controller.close()
        }

    @Test
    @Suppress("LongMethod") // Both asynchronous outcomes and closure share explicit catalog read gates.
    fun obsoletePickerSuccessAndFailureCannotPublishIntoNewGenerationOrClosedRoute() =
        runBlocking {
            for (fail in listOf(false, true)) {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                var reads = 0
                val current = SequenceEditorActivityChoice(ActivityTemplateId("current"), "Current")
                val controller =
                    SequenceTemplateEditorController(
                        this,
                        SequenceTemplateEditorTarget.New,
                        { null },
                        {
                            if (++reads == 1) {
                                started.complete(Unit)
                                release.await()
                                finished.complete(Unit)
                                if (fail) error("obsolete failure")
                                listOf(SequenceEditorActivityChoice(ActivityTemplateId("old"), "Old"))
                            } else {
                                listOf(current)
                            }
                        },
                        { _, _, _ -> error("No create") },
                        { _, _, _, _ -> error("No save") },
                        { Instant.EPOCH },
                    )
                controller.awaitReady()
                controller.loadActivityPicker()
                started.await()
                controller.retry()
                controller.awaitReady()
                controller.loadActivityPicker()
                withTimeout(2_000) { controller.state.first { it.availableActivities == listOf(current) } }
                val accepted = controller.state.value
                release.complete(Unit)
                finished.await()
                kotlinx.coroutines.yield()
                assertEquals(accepted, controller.state.value)
                controller.close()
            }
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.New,
                    { null },
                    {
                        release.await()
                        finished.complete(Unit)
                        error("closed picker failure")
                    },
                    { _, _, _ -> error("No create") },
                    { _, _, _, _ -> error("No save") },
                    { Instant.EPOCH },
                )
            controller.awaitReady()
            controller.loadActivityPicker()
            controller.close()
            val closed = controller.state.value
            release.complete(Unit)
            finished.await()
            kotlinx.coroutines.yield()
            assertEquals(closed, controller.state.value)
        }

    @Test
    fun destinationAuthorityRejectsDismissedStaleForgedAndPreviousSessionChoices() =
        runBlocking {
            var canonical = authoringState()
            val controller = destinationController(this) { canonical }
            controller.awaitReady()
            val first = DraftIdentity.Existing(SequenceNodeId("step-1"))
            val repeat = DraftIdentity.Existing(SequenceNodeId("repeat"))
            controller.enterManipulation(first)

            fun offer() = requireNotNull(controller.offerManipulationDestinations(SequenceDestinationOperation.Move))

            fun baseline() = controller.state.value.readyDraft()!!

            fun reject(
                current: SequenceDestinationOffer,
                choice: SequenceDestinationChoice = current.choices.last(),
            ) {
                val before = controller.state.value
                assertFalse(controller.chooseManipulationDestination(current, choice))
                assertEquals(before, controller.state.value)
            }
            val dismissed = offer()
            controller.dismissManipulationDestinations(dismissed)
            reject(dismissed)
            val forged = offer()
            reject(forged, forged.choices.first().copy(destination = SequenceDropDestination(repeat, 99)))
            val invalidContainer = offer()
            reject(
                invalidContainer,
                invalidContainer.choices.first().copy(destination = SequenceDropDestination(first, 0)),
            )
            assertFalse(
                controller.state.value.manipulation!!
                    .canUndo,
            )
            val stale = offer()
            assertTrue(controller.moveManipulation(first, SequenceDropDestination(repeat, 0)))
            reject(stale)
            assertTrue(controller.undoManipulation())
            reject(stale)
            assertTrue(
                controller.state.value.manipulation!!
                    .canRedo,
            )
            val selected = offer()
            controller.selectManipulation(repeat)
            reject(selected)
            assertTrue(
                controller.state.value.manipulation!!
                    .canRedo,
            )
            controller.selectManipulation(first)
            val discarded = offer()
            controller.discardManipulation()
            controller.enterManipulation(first)
            reject(discarded)
            val removed = offer()
            canonical = canonical.copy(sequence = canonical.sequence.copy(nodes = emptyList()))
            controller.retry()
            controller.awaitReady()
            reject(removed)
            controller.enterManipulation(first)
            assertEquals(null, controller.state.value.manipulation)
            canonical = authoringState()
            controller.retry()
            controller.awaitReady()
            assertEquals(authoringState().toAuthoringDraft(), baseline())
            controller.close()
        }

    @Test
    @Suppress("LongMethod") // Mixed draft, raw-input and structural deltas must share one session baseline.
    fun nativeDestinationOperationPreservesDirtyRawInputHistoryAndExactDiscardBaseline() =
        runBlocking {
            val controller = destinationController(this)
            controller.awaitReady()
            val first = DraftIdentity.Existing(SequenceNodeId("step-1"))
            val repeat = DraftIdentity.Existing(SequenceNodeId("repeat"))
            controller.updateDraft { it.copy(name = "Before entry", shortComment = "Pre-entry") }
            controller.updateNumberInput("raw", "bad-before", 0, 0) { error("Invalid") }
            val baseline = controller.state.value
            controller.enterManipulation(first)
            val offered = requireNotNull(controller.offerManipulationDestinations(SequenceDestinationOperation.Move))
            controller.updateDraft { it.copy(shortComment = null) }
            controller.updateNumberInput("raw", "13", 0, 0) { }
            val choice = offered.choices.single { it.destination == SequenceDropDestination(repeat, 1) }
            assertTrue(controller.chooseManipulationDestination(offered, choice))
            assertFalse(controller.chooseManipulationDestination(offered, choice))
            val moved = controller.state.value.readyDraft()!!
            assertEquals(listOf(repeat), moved.nodes.map { it.identity })
            assertEquals(
                listOf(
                    DraftIdentity.Existing(SequenceNodeId("step-2")),
                    first,
                ),
                (moved.nodes.single() as SequenceNodeDraft.Repeat).value.children.map {
                    it.identity
                },
            )
            controller.updateNumberInput("raw", "bad-after", 0, 0) { error("Invalid") }
            assertTrue(controller.undoManipulation())
            assertEquals(
                "13",
                controller.state.value.textInputs["raw"]
                    ?.text,
            )
            assertTrue(controller.undoManipulation())
            assertEquals(
                baseline.readyDraft()!!.nodes,
                controller.state.value
                    .readyDraft()!!
                    .nodes,
            )
            assertTrue(controller.undoManipulation())
            assertEquals(
                "bad-before",
                controller.state.value.textInputs["raw"]
                    ?.text,
            )
            assertTrue(controller.undoManipulation())
            assertEquals(
                "Pre-entry",
                controller.state.value
                    .readyDraft()!!
                    .shortComment,
            )
            assertTrue(controller.redoManipulation())
            assertEquals(
                null,
                controller.state.value
                    .readyDraft()!!
                    .shortComment,
            )
            controller.discardManipulation()
            assertEquals(baseline.readyDraft(), controller.state.value.readyDraft())
            assertEquals(baseline.textInputs, controller.state.value.textInputs)
            controller.close()
        }

    @Test
    fun duplicateOfferRequiresOwnedExistingSnapshotAndRedoRestoresSameTransientSelection() =
        runBlocking {
            val controller = destinationController(this)
            controller.awaitReady()
            val first = DraftIdentity.Existing(SequenceNodeId("step-1"))
            controller.enterManipulation(first)
            val offered =
                requireNotNull(controller.offerManipulationDestinations(SequenceDestinationOperation.Duplicate))
            assertTrue(controller.chooseManipulationDestination(offered, offered.choices.last()))
            val duplicate =
                controller.state.value.manipulation!!
                    .selected
            assertTrue(duplicate is DraftIdentity.New)
            assertEquals(null, controller.offerManipulationDestinations(SequenceDestinationOperation.Duplicate))
            assertTrue(controller.undoManipulation())
            assertEquals(
                first,
                controller.state.value.manipulation!!
                    .selected,
            )
            assertTrue(controller.redoManipulation())
            assertEquals(
                duplicate,
                controller.state.value.manipulation!!
                    .selected,
            )
            controller.discardManipulation()
            controller.updateDraft { it.copy(nodes = it.nodes + SequenceNodeDraft.Step(localStep("new", 2))) }
            controller.enterManipulation(DraftIdentity.New("new"))
            assertEquals(null, controller.offerManipulationDestinations(SequenceDestinationOperation.Duplicate))
            controller.discardManipulation()
            controller.enterManipulation(DraftIdentity.Existing(SequenceNodeId("repeat")))
            assertEquals(null, controller.offerManipulationDestinations(SequenceDestinationOperation.Duplicate))
            controller.discardManipulation()
            controller.updateDraft { draft ->
                draft.copy(
                    nodes =
                        draft.nodes.map { node ->
                            if (node is SequenceNodeDraft.Step && node.value.activity is StepActivityDraft.Existing) {
                                SequenceNodeDraft.Step(
                                    node.value.copy(
                                        activity =
                                            (node.value.activity as StepActivityDraft.Existing).copy(
                                                snapshotId = ActivitySnapshotId("foreign"),
                                            ),
                                    ),
                                )
                            } else {
                                node
                            }
                        },
                )
            }
            controller.enterManipulation(first)
            val before = controller.state.value
            assertEquals(null, controller.offerManipulationDestinations(SequenceDestinationOperation.Duplicate))
            assertFalse(controller.duplicateManipulation(first, SequenceDropDestination(position = 0)))
            assertEquals(before, controller.state.value)
            controller.close()
        }

    private fun destinationController(
        scope: kotlinx.coroutines.CoroutineScope,
        read: () -> SequenceTemplateAuthoringState = ::authoringState,
    ): SequenceTemplateEditorController =
        SequenceTemplateEditorController(
            scope,
            SequenceTemplateEditorTarget.Existing(read().sequence.id),
            { read() },
            { emptyList() },
            { _, _, _ -> error("No write") },
            { _, _, _, _ -> error("No write") },
            { Instant.EPOCH },
        )

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
