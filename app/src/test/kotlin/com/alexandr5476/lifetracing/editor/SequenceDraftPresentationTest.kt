@file:Suppress("LongMethod")

package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceNodeDraft
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlockDraft
import com.alexandr5476.lifetracing.domain.StepActivityDraft
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SequenceDraftPresentationTest {
    @Test
    fun inspectionsNeverRescanNodesWhileMutationsVerifyCurrentOwnership() =
        runBlocking {
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.New,
                    { null },
                    { error("No picker read") },
                    { _, _, _ -> error("No write") },
                    { _, _, _, _ -> error("No write") },
                    { Instant.EPOCH },
                )
            withTimeout(2000) { controller.state.first { it.load is SequenceTemplateEditorLoad.Ready } }
            val category =
                ActivitySnapshotFieldDraft(
                    DraftIdentity.New("category"),
                    null,
                    1,
                    "Same",
                    type = CustomFieldType.CATEGORY,
                    categoryOptions =
                        (0..5).map {
                            ActivitySnapshotCategoryOptionDraft(DraftIdentity.New("option-$it"), null, it, "Same")
                        },
                )
            val number =
                ActivitySnapshotFieldDraft(DraftIdentity.New("number"), null, 0, "Same", type = CustomFieldType.NUMBER)
            val steps =
                (0 until 1200).map {
                    ActivityStepDraft(
                        DraftIdentity.Existing(SequenceNodeId("step-$it")),
                        it % 600,
                        StepActivityDraft.Existing(
                            ActivitySnapshotId("snapshot-$it"),
                            ActivitySnapshotDraft(
                                "Same",
                                null,
                                TimeTrackingMode.STOPWATCH,
                                null,
                                fields = listOf(number, category),
                            ),
                            null,
                            null,
                            null,
                            false,
                        ),
                    )
                }
            val children = CountingList(steps.drop(600))
            val nodes =
                CountingList(
                    steps.take(600).map { SequenceNodeDraft.Step(it) } +
                        SequenceNodeDraft.Repeat(
                            SequenceRepeatBlockDraft(DraftIdentity.New("repeat"), 600, 2, children),
                        ),
                )
            controller.updateDraft { it.copy(nodes = nodes) }
            val index = controller.state.value.presentation()!!
            assertEquals(1200, index.steps.size)
            val lease = SequenceTemplateEditorActions(controller, controller.state.value.generation)
            val actions = steps.map { SequenceStepActions(controller, lease, it.identity) }
            val sources =
                steps.map {
                    SequenceStepSourceActions(
                        controller,
                        lease,
                        (it.identity as DraftIdentity.Existing).id,
                    )
                }
            nodes.reads = 0
            children.reads = 0
            repeat(3) {
                actions.forEachIndexed { i, action ->
                    assertTrue(action.available())
                    assertTrue(action.exists())
                    assertSame(steps[i].configuration(), action.configuration())
                    assertTrue(action.fieldExists(number))
                    category.categoryOptions.forEach { assertTrue(action.optionExists(category, it)) }
                    SequenceStepSourceOperation.entries.forEach { sources[i].available(it) }
                }
            }
            controller.updateNumberInput(SequenceEditorInputKey.SEQUENCE_START_COUNTDOWN, "bad", 0, 0) {}
            assertSame(index, controller.state.value.presentation())
            sources.forEach { it.available(SequenceStepSourceOperation.SaveAsNew) }
            assertEquals(0, nodes.reads)
            assertEquals(0, children.reads)
            actions.last().numberDefault(number, "-1.25")
            assertTrue(nodes.reads > 0)
            assertTrue(children.reads > 0)
            assertEquals(
                -1250L,
                controller.state.value
                    .readyDraft()!!
                    .findStep(
                        steps.last().identity,
                    )!!
                    .configuration()!!
                    .fields
                    .first()
                    .defaultNumberScaled,
            )
            assertNotSame(index, controller.state.value.presentation())
            controller.enterManipulation(steps.last().identity)
            controller.moveManipulation(steps.last().identity, SequenceDropDestination(position = 0))
            val moved =
                SequenceStepActions(
                    controller,
                    SequenceTemplateEditorActions(controller, controller.state.value.generation),
                    steps.last().identity,
                )
            assertTrue(moved.exists())
            assertTrue(moved.fieldExists(number))
            moved.removeField(number)
            assertFalse(moved.fieldExists(number))
            assertTrue(controller.undoManipulation())
            assertTrue(moved.fieldExists(number))
            val replacement = number.copy(identity = DraftIdentity.New("replacement"))
            moved.field(number) { it.copy(identity = replacement.identity) }
            assertFalse(moved.fieldExists(number))
            assertTrue(moved.fieldExists(replacement))
            val option = category.categoryOptions.first()
            moved.option(category, option) { field, _ -> field.copy(categoryOptions = field.categoryOptions.drop(1)) }
            assertFalse(moved.optionExists(category, option))
            controller.discardManipulation()
            controller.updateDraft { it.copy(nodes = emptyList()) }
            assertFalse(moved.exists())
            assertFalse(moved.available())
            assertFalse(moved.optionExists(category, category.categoryOptions.first()))
            val generation = controller.state.value.generation
            controller.retry()
            withTimeout(2000) {
                controller.state.first {
                    it.generation > generation &&
                        it.load is SequenceTemplateEditorLoad.Ready
                }
            }
            assertTrue(
                controller.state.value
                    .presentation()!!
                    .steps
                    .isEmpty(),
            )
            assertFalse(moved.exists())
            controller.close()
        }

    private class CountingList<T>(
        private val values: List<T>,
    ) : AbstractList<T>() {
        var reads = 0
        override val size get() = values.size

        override fun get(index: Int): T {
            reads++
            return values[index]
        }
    }
}
