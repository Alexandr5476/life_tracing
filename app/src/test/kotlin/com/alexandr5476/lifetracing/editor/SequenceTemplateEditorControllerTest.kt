package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateAuthoringState
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SequenceTemplateEditorControllerTest {
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

    private suspend fun SequenceTemplateEditorController.awaitReady() {
        withTimeout(2_000) { state.first { it.load is SequenceTemplateEditorLoad.Ready } }
    }

    private suspend fun SequenceTemplateEditorController.awaitFailure(): SequenceTemplateEditorSave.Failure =
        withTimeout(2_000) {
            state.first { it.save is SequenceTemplateEditorSave.Failure }.save as SequenceTemplateEditorSave.Failure
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
}
