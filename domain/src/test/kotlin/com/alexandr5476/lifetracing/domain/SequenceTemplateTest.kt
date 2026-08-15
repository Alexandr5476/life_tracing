package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SequenceTemplateTest {
    private val now = Instant.ofEpochMilli(1_700_000_000_000)

    @Test
    fun `revision starts at one and one semantic commit increments once`() {
        assertEquals(1, template().revision)
        assertEquals(
            8,
            SequenceTemplateRevisionPolicy.after(
                7,
                SequenceTemplateEdit.NAME,
                SequenceTemplateEdit.SETTINGS,
                SequenceTemplateEdit.STRUCTURE,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SequenceTemplateValidator.requireValid(template().copy(revision = 0))
        }
    }

    @Test
    fun `library and presentation metadata do not increment revision`() {
        assertEquals(
            7,
            SequenceTemplateRevisionPolicy.after(
                7,
                SequenceTemplateEdit.FOLDER,
                SequenceTemplateEdit.TAGS,
                SequenceTemplateEdit.USER_STATE,
                SequenceTemplateEdit.FIELD_DISPLAY_NAME,
                SequenceTemplateEdit.CATEGORY_OPTION_DISPLAY_NAME,
            ),
        )
    }

    @Test
    fun `archive and restore preserve identities revision and structure`() {
        val original = template(nodes = nodes())
        val archived = SequenceTemplateLifecycle.archive(original, now)
        val restored = SequenceTemplateLifecycle.restore(archived)

        assertEquals(original.id, restored.id)
        assertEquals(original.statisticsSeriesId, restored.statisticsSeriesId)
        assertEquals(original.revision, restored.revision)
        assertEquals(original.nodes, restored.nodes)
        assertNull(restored.deletedAt)
    }

    @Test
    fun `settings reject negative countdowns`() {
        listOf(
            SequenceTemplateSettings(sequenceStartCountdown = Duration.ofMillis(-1)),
            SequenceTemplateSettings(beforeEachStepCountdown = Duration.ofMillis(-1)),
        ).forEach { settings ->
            assertThrows(IllegalArgumentException::class.java) {
                SequenceTemplateValidator.requireValid(template(settings = settings))
            }
        }
    }

    @Test
    fun `number category and text fields validate with active same-field default`() {
        listOf(numberField(), categoryField(), textField()).forEach { field ->
            assertDoesNotThrow { SequenceTemplateValidator.requireValidField(field) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceTemplateValidator.requireValidField(
                categoryField().copy(defaultCategoryOptionId = SequenceTemplateCategoryOptionId("other")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceTemplateValidator.requireValidField(
                categoryField(optionArchived = true),
            )
        }
    }

    @Test
    fun `field shapes and Main Value are enforced`() {
        listOf(
            numberField().copy(defaultText = "wrong"),
            categoryField().copy(defaultNumberScaled = 1),
            textField().copy(defaultCategoryOptionId = SequenceTemplateCategoryOptionId("option")),
            categoryField().copy(isMainValue = true),
        ).forEach { field ->
            assertThrows(IllegalArgumentException::class.java) {
                SequenceTemplateValidator.requireValidField(field)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceTemplateValidator.requireValid(
                template(fields = listOf(numberField("one", true), numberField("two", true))),
            )
        }
        assertDoesNotThrow {
            SequenceTemplateValidator.requireValid(
                template(
                    fields =
                        listOf(
                            numberField("old", true).copy(deletedAt = now),
                            numberField("new", true),
                        ),
                ),
            )
        }
    }

    @Test
    fun `same field identity preserves type and unit but permits other evolution`() {
        val original = numberField()
        listOf(
            original.copy(name = "Renamed"),
            original.copy(defaultNumberScaled = 99_000),
            original.copy(displayPrecision = 1),
            original.copy(position = 4),
            original.copy(deletedAt = now),
        ).forEach { updated ->
            assertDoesNotThrow {
                SequenceTemplateFieldEvolution.requireSameIdentityCompatible(original, updated)
            }
        }
        listOf(
            original.copy(
                type = CustomFieldType.TEXT,
                unit = null,
                displayPrecision = null,
                defaultNumberScaled = null,
            ),
            original.copy(unit = "minutes"),
        ).forEach { updated ->
            assertThrows(IllegalArgumentException::class.java) {
                SequenceTemplateFieldEvolution.requireSameIdentityCompatible(original, updated)
            }
        }
    }

    @Test
    fun `repeat counts and sibling ordering follow container scope`() {
        assertDoesNotThrow {
            SequenceTemplateValidator.requireValidNodes(
                listOf(
                    step("top", 0),
                    repeat("repeat", 1, 1, listOf(step("child-a", 0), step("child-b", 1))),
                ),
            )
        }
        assertDoesNotThrow { SequenceTemplateValidator.requireValidNodes(listOf(repeat("repeat", 0, 500))) }
        listOf(0, -1).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) {
                SequenceTemplateValidator.requireValidNodes(listOf(repeat("repeat", 0, count)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceTemplateValidator.requireValidNodes(listOf(step("a", 0), step("b", 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceTemplateValidator.requireValidNodes(
                listOf(repeat("repeat", 0, 2, listOf(step("a", 0), step("b", 0)))),
            )
        }
    }

    @Test
    fun `steps move into out of and between Repeats`() {
        val initial =
            listOf<SequenceNode>(
                step("top", 0),
                repeat("left", 1, 2),
                repeat("right", 2, 3),
            )
        val insideLeft = SequenceStructureEditor.moveStep(initial, nodeId("top"), nodeId("left"), 0)
        val insideRight = SequenceStructureEditor.moveStep(insideLeft, nodeId("top"), nodeId("right"), 0)
        val topAgain = SequenceStructureEditor.moveStep(insideRight, nodeId("top"), null, 3)

        assertEquals(listOf("top"), repeatChildren(insideLeft, "left"))
        assertEquals(listOf("top"), repeatChildren(insideRight, "right"))
        assertTrue(topAgain.last() is ActivityStep)
    }

    @Test
    fun `steps reorder within Repeat and Repeat moves only at top level`() {
        val initial =
            listOf<SequenceNode>(
                step("top", 0),
                repeat("repeat", 2, 2, listOf(step("a", 0), step("b", 2))),
            )
        val reorderedChild = SequenceStructureEditor.moveStep(initial, nodeId("b"), nodeId("repeat"), 1)
        val reorderedTop = SequenceStructureEditor.moveTopLevelNode(reorderedChild, nodeId("repeat"), 1)

        assertEquals(listOf("a", "b"), repeatChildren(reorderedChild, "repeat"))
        assertEquals(nodeId("repeat"), reorderedTop[1].id)
        assertThrows(IllegalArgumentException::class.java) {
            SequenceStructureEditor.moveStep(initial, nodeId("repeat"), nodeId("repeat"), 0)
        }
    }

    @Test
    fun `explicit reorder covers top-level Repeat units and Repeat children`() {
        val initial =
            listOf<SequenceNode>(
                step("top", 0),
                repeat("repeat", 1, 2, listOf(step("a", 0), step("b", 1))),
            )

        val topReordered =
            SequenceStructureEditor.reorderTopLevel(initial, listOf(nodeId("repeat"), nodeId("top")))
        val childrenReordered =
            SequenceStructureEditor.reorderRepeatChildren(
                topReordered,
                nodeId("repeat"),
                listOf(nodeId("b"), nodeId("a")),
            )

        assertEquals(listOf("repeat", "top"), topReordered.map { it.id.value })
        assertEquals(listOf("b", "a"), repeatChildren(childrenReordered, "repeat"))
    }

    @Test
    fun `source linked and one-off Step snapshots remain self-contained`() {
        var id = 0
        val source = activityTemplate(revision = 4)
        val snapshot =
            ActivitySnapshotFactory(
                nextSnapshotId = { ActivitySnapshotId("snapshot-${id++}") },
                nextFieldId = { ActivitySnapshotFieldId("field-${id++}") },
                nextOptionId = { ActivitySnapshotCategoryOptionId("option-${id++}") },
            ).fromTemplate(source, now)
        val oneOff =
            snapshot.copy(
                id = ActivitySnapshotId("one-off"),
                sourceTemplateId = null,
                sourceRevision = null,
                statisticsSeriesId = null,
            )

        assertEquals(source.id, snapshot.sourceTemplateId)
        assertEquals(4, snapshot.sourceRevision)
        assertEquals(source.statisticsSeriesId, snapshot.statisticsSeriesId)
        assertFalse(snapshot.locallyModified)
        assertDoesNotThrow { ActivityConfigSnapshotValidator.requireValid(oneOff) }
        assertTrue(ActivityStepSnapshotPolicy.isSourceDiverged(snapshot, 5))
        assertFalse(ActivityStepSnapshotPolicy.isSourceDiverged(snapshot, 4))
        assertEquals("Source", snapshot.name)
        assertEquals("Renamed", source.copy(name = "Renamed").name)
    }

    @Test
    fun `local Step edit replaces rather than mutates snapshot`() {
        val previous = snapshot("old", locallyModified = false)
        val replacement = snapshot("new", locallyModified = true)
        val updated = ActivityStepSnapshotPolicy.replaceLocally(step("step", 0, previous.id), previous, replacement)

        assertEquals(replacement.id, updated.activitySnapshotId)
        assertEquals(ActivitySnapshotId("old"), previous.id)
        assertThrows(IllegalArgumentException::class.java) {
            ActivityStepSnapshotPolicy.replaceLocally(updated, replacement, replacement)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityStepSnapshotPolicy.replaceLocally(
                step("step", 0, previous.id),
                previous,
                replacement.copy(locallyModified = false),
            )
        }
    }

    private fun template(
        settings: SequenceTemplateSettings = SequenceTemplateSettings(),
        fields: List<SequenceTemplateField> = emptyList(),
        nodes: List<SequenceNode> = emptyList(),
    ) = SequenceTemplate(
        id = SequenceTemplateId("sequence"),
        name = "Sequence",
        shortComment = null,
        statisticsSeriesId = StatisticsSeriesId("series"),
        createdAt = now,
        updatedAt = now,
        settings = settings,
        fields = fields,
        nodes = nodes,
    )

    private fun numberField(
        id: String = "number",
        main: Boolean = false,
    ) = SequenceTemplateField(
        id = SequenceTemplateFieldId(id),
        position = 0,
        name = "Number",
        type = CustomFieldType.NUMBER,
        unit = "kg",
        displayPrecision = 3,
        defaultNumberScaled = 12_345,
        isMainValue = main,
        createdAt = now,
        updatedAt = now,
    )

    private fun categoryField(optionArchived: Boolean = false) =
        SequenceTemplateField(
            id = SequenceTemplateFieldId("category"),
            position = 0,
            name = "Category",
            type = CustomFieldType.CATEGORY,
            defaultCategoryOptionId = SequenceTemplateCategoryOptionId("option"),
            createdAt = now,
            updatedAt = now,
            categoryOptions =
                listOf(
                    SequenceTemplateCategoryOption(
                        SequenceTemplateCategoryOptionId("option"),
                        0,
                        "Option",
                        optionArchived,
                    ),
                ),
        )

    private fun textField() =
        SequenceTemplateField(
            id = SequenceTemplateFieldId("text"),
            position = 0,
            name = "Text",
            type = CustomFieldType.TEXT,
            defaultText = "Default",
            createdAt = now,
            updatedAt = now,
        )

    private fun nodes(): List<SequenceNode> = listOf(step("top", 0), repeat("repeat", 2, 3, listOf(step("child", 0))))

    private fun step(
        id: String,
        position: Int,
        snapshotId: ActivitySnapshotId = ActivitySnapshotId("snapshot-$id"),
    ) = ActivityStep(nodeId(id), position, snapshotId)

    private fun repeat(
        id: String,
        position: Int,
        count: Int,
        children: List<ActivityStep> = emptyList(),
    ) = SequenceRepeatBlock(nodeId(id), position, count, children)

    private fun nodeId(value: String) = SequenceNodeId(value)

    private fun repeatChildren(
        nodes: List<SequenceNode>,
        repeatId: String,
    ) = nodes
        .filterIsInstance<SequenceRepeatBlock>()
        .single { it.id == nodeId(repeatId) }
        .children
        .map { it.id.value }

    private fun snapshot(
        id: String,
        locallyModified: Boolean,
    ) = ActivityConfigSnapshot(
        id = ActivitySnapshotId(id),
        name = "Snapshot",
        shortComment = null,
        timeTrackingMode = TimeTrackingMode.STOPWATCH,
        timerTarget = null,
        sourceTemplateId = ActivityTemplateId("activity"),
        sourceRevision = 1,
        statisticsSeriesId = StatisticsSeriesId("activity-series"),
        locallyModified = locallyModified,
        createdAt = now,
    )

    private fun activityTemplate(revision: Long) =
        ActivityTemplate(
            id = ActivityTemplateId("activity"),
            name = "Source",
            shortComment = null,
            timeTrackingMode = TimeTrackingMode.STOPWATCH,
            timerTarget = null,
            statisticsSeriesId = StatisticsSeriesId("activity-series"),
            revision = revision,
            createdAt = now,
            updatedAt = now,
        )
}
