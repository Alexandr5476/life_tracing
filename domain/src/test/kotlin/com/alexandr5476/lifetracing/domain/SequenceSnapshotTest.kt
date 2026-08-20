package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SequenceSnapshotTest {
    private val now = Instant.parse("2026-08-20T12:00:00Z")

    @Test
    @Suppress("LongMethod") // One fixture demonstrates the complete frozen mapping.
    fun `factory freezes active fields structure and explicit step overrides`() {
        val active = SequenceTemplateCategoryOption(SequenceTemplateCategoryOptionId("source-option"), 0, "Active")
        val archived = SequenceTemplateCategoryOption(SequenceTemplateCategoryOptionId("old-option"), 1, "Old", true)
        val category =
            SequenceTemplateField(
                SequenceTemplateFieldId("field"),
                0,
                "Effort",
                CustomFieldType.CATEGORY,
                defaultCategoryOptionId = active.id,
                createdAt = now,
                updatedAt = now,
                categoryOptions = listOf(active, archived),
            )
        val template =
            template(
                fields = listOf(category, numberField("deleted").copy(deletedAt = now)),
                nodes =
                    listOf(
                        ActivityStep(
                            SequenceNodeId("top"),
                            0,
                            ActivitySnapshotId("a"),
                            SequenceStepOverrides(Duration.ZERO, timerEndSound = false),
                        ),
                        SequenceRepeatBlock(
                            SequenceNodeId("repeat"),
                            1,
                            3,
                            listOf(
                                ActivityStep(
                                    SequenceNodeId("child"),
                                    0,
                                    ActivitySnapshotId("b"),
                                    SequenceStepOverrides(keepScreenAwake = false),
                                ),
                            ),
                        ),
                    ),
            )

        val snapshot = factory().fromTemplate(template, now)
        val copied = snapshot.fields.single()
        val top = snapshot.nodes[0] as SequenceSnapshotActivityStep
        val repeat = snapshot.nodes[1] as SequenceSnapshotRepeatBlock

        assertEquals(template.id, snapshot.sourceTemplateId)
        assertEquals(template.revision, snapshot.sourceRevision)
        assertEquals(template.statisticsSeriesId, snapshot.statisticsSeriesId)
        assertEquals(NoLiveTimeAccounting.PAUSE, snapshot.settings.noLiveTimeAccounting)
        assertNotEquals(category.id.value, copied.id.value)
        assertEquals(category.id, copied.sourceFieldId)
        assertEquals(listOf(active.id), copied.categoryOptions.map { it.sourceOptionId })
        assertEquals(copied.categoryOptions.single().id, copied.defaultCategoryOptionId)
        assertNotEquals("top", top.id.value)
        assertEquals(Duration.ZERO, top.overrides.startCountdown)
        assertEquals(false, top.overrides.timerEndSound)
        assertEquals(3, repeat.repeatCount)
        assertNotEquals("repeat", repeat.id.value)
        assertEquals(
            false,
            repeat.children
                .single()
                .overrides.keepScreenAwake,
        )
    }

    @Test
    fun `snapshot remains frozen and validates structural boundaries`() {
        val template = template(nodes = listOf(ActivityStep(SequenceNodeId("step"), 0, ActivitySnapshotId("activity"))))
        val snapshot = factory().fromTemplate(template, now)
        val step = snapshot.nodes.single() as SequenceSnapshotActivityStep
        val activity =
            ActivityConfigSnapshot(
                ActivitySnapshotId("activity"),
                "Activity",
                null,
                TimeTrackingMode.TIMER,
                Duration.ofSeconds(30),
                null,
                null,
                null,
                false,
                now,
            )
        val effective = EffectiveSequenceStepSettingsResolver.resolve(step, activity, snapshot.settings, true)

        assertEquals(snapshot.settings.sequenceStartCountdown, effective.startCountdown)
        assertEquals("Sequence", snapshot.name)
        assertFalse(snapshot.nodes.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            SequenceConfigSnapshotValidator.requireValid(snapshot.copy(sourceRevision = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceConfigSnapshotValidator.requireValid(
                snapshot.copy(nodes = listOf(SequenceSnapshotRepeatBlock(step.id, 0, 0, emptyList()))),
            )
        }
        assertNull(snapshot.fields.singleOrNull())
    }

    private fun factory(): SequenceSnapshotFactory {
        var id = 0
        return SequenceSnapshotFactory(
            nextSnapshotId = { SequenceSnapshotId("snapshot-${++id}") },
            nextFieldId = { SequenceSnapshotFieldId("field-${++id}") },
            nextOptionId = { SequenceSnapshotCategoryOptionId("option-${++id}") },
            nextNodeId = { SequenceSnapshotNodeId("node-${++id}") },
        )
    }

    private fun template(
        fields: List<SequenceTemplateField> = emptyList(),
        nodes: List<SequenceNode> = emptyList(),
    ) = SequenceTemplate(
        SequenceTemplateId("sequence"),
        "Sequence",
        "Comment",
        StatisticsSeriesId("series"),
        4,
        now,
        now,
        noLiveTimeAccounting = NoLiveTimeAccounting.PAUSE,
        settings = SequenceTemplateSettings(sequenceStartCountdown = Duration.ofSeconds(5)),
        fields = fields,
        nodes = nodes,
    )

    private fun numberField(id: String) =
        SequenceTemplateField(
            SequenceTemplateFieldId(id),
            1,
            id,
            CustomFieldType.NUMBER,
            createdAt = now,
            updatedAt = now,
        )
}
