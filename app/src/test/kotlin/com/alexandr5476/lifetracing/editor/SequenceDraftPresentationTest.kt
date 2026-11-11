@file:Suppress("LongMethod")

package com.alexandr5476.lifetracing.editor

import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldDraft
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStepDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.DraftIdentity
import com.alexandr5476.lifetracing.domain.SequenceCategoryOptionDraft
import com.alexandr5476.lifetracing.domain.SequenceFieldDraft
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

    @Test
    fun expandedStepInspectionsAreLinearInFieldsAndOptionsAndRespectProvenance() =
        runBlocking {
            val controller =
                SequenceTemplateEditorController(
                    this,
                    SequenceTemplateEditorTarget.New,
                    { null },
                    { error("No picker") },
                    { _, _, _ -> error("No write") },
                    { _, _, _, _ -> error("No write") },
                    { Instant.EPOCH },
                )
            withTimeout(2000) { controller.state.first { it.load is SequenceTemplateEditorLoad.Ready } }
            val options =
                (0 until 80).map { field ->
                    (0 until 80).map { option ->
                        ActivitySnapshotCategoryOptionDraft(
                            DraftIdentity.New("$field-$option"),
                            CategoryOptionId("source-$field-$option"),
                            option,
                            "Same",
                        )
                    }
                }
            val countedOptions = options.map { CountingList(it) }
            val fields =
                (0 until 80).map {
                    ActivitySnapshotFieldDraft(
                        DraftIdentity.New("field-$it"),
                        ActivityTemplateFieldId("source-$it"),
                        it,
                        "Same",
                        type = CustomFieldType.CATEGORY,
                        categoryOptions = countedOptions[it],
                    )
                }
            val countedFields = CountingList(fields)
            val sequenceOptions =
                (0 until 80).map {
                    SequenceCategoryOptionDraft(
                        DraftIdentity.New("sequence-$it"),
                        it,
                        "Same",
                    )
                }
            val countedSequenceOptions = CountingList(sequenceOptions)
            val sequenceField =
                SequenceFieldDraft(
                    DraftIdentity.New("sequence-field"),
                    0,
                    "Same",
                    CustomFieldType.CATEGORY,
                    categoryOptions = countedSequenceOptions,
                )
            val step =
                ActivityStepDraft(
                    DraftIdentity.New("step"),
                    0,
                    StepActivityDraft.Local(
                        ActivitySnapshotDraft("Same", null, TimeTrackingMode.STOPWATCH, null, fields = countedFields),
                    ),
                )
            controller.updateDraft {
                it.copy(
                    nodes = listOf(SequenceNodeDraft.Step(step)),
                    fields = listOf(sequenceField),
                )
            }
            assertEquals(
                1,
                controller.state.value
                    .presentation()!!
                    .steps.size,
            )
            assertTrue(countedFields.reads <= fields.size * 10)
            assertTrue(countedOptions.sumOf { it.reads } <= 80 * 80 * 10)
            val actions =
                SequenceStepActions(
                    controller,
                    SequenceTemplateEditorActions(controller, controller.state.value.generation),
                    step.identity,
                )
            val sequenceActions = SequenceTemplateEditorActions(controller, controller.state.value.generation)
            countedFields.reads = 0
            countedOptions.forEach { it.reads = 0 }
            countedSequenceOptions.reads = 0
            repeat(3) {
                sequenceOptions.forEach { assertTrue(sequenceActions.optionExists(sequenceField, it)) }
                fields.forEachIndexed { index, field ->
                    assertTrue(actions.fieldExists(field))
                    assertFalse(actions.fieldExists(field.copy(type = CustomFieldType.NUMBER)))
                    assertFalse(actions.fieldExists(field.copy(unit = "stale unit")))
                    options[index].forEach { option ->
                        assertTrue(actions.optionExists(field, option))
                        assertFalse(
                            actions.optionExists(
                                field,
                                option.copy(
                                    sourceOptionId = CategoryOptionId("foreign"),
                                ),
                            ),
                        )
                    }
                    assertFalse(
                        actions.fieldExists(
                            field.copy(
                                sourceFieldId = ActivityTemplateFieldId("foreign"),
                            ),
                        ),
                    )
                }
            }
            assertEquals(0, countedFields.reads)
            assertEquals(0, countedOptions.sumOf { it.reads })
            assertEquals(0, countedSequenceOptions.reads)
            sequenceActions.removeField(sequenceField)
            assertFalse(sequenceActions.optionExists(sequenceField, sequenceOptions.first()))
            val removed = fields.first()
            val replaced = fields[1]
            actions.removeField(removed)
            actions.field(replaced) { it.copy(sourceFieldId = ActivityTemplateFieldId("new")) }
            assertFalse(actions.fieldExists(removed))
            assertFalse(actions.optionExists(removed, options.first().first()))
            assertFalse(actions.fieldExists(replaced))
            assertFalse(actions.optionExists(replaced, options[1].first()))
            val before = controller.state.value.readyDraft()
            actions.field(removed) { it.copy(unit = "stale") }
            actions.option(replaced, options[1].first()) { field, _ -> field.copy(unit = "stale") }
            assertEquals(before, controller.state.value.readyDraft())
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
