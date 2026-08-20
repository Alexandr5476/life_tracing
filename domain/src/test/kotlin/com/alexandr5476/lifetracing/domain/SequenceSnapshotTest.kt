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

        val snapshot = factory().fromTemplate(template, modes("a", "b"), now)
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
        assertEquals(ActivitySnapshotId("a"), top.activitySnapshotId)
        assertEquals(listOf(0, 1), snapshot.nodes.map { it.position })
        assertEquals(3, repeat.repeatCount)
        assertNotEquals("repeat", repeat.id.value)
        assertEquals(ActivitySnapshotId("b"), repeat.children.single().activitySnapshotId)
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
        val snapshot = factory().fromTemplate(template, modes("activity"), now)
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

    @Test
    fun `factory rejects timer override when child metadata is unavailable`() {
        val template =
            template(
                nodes =
                    listOf(
                        ActivityStep(
                            SequenceNodeId("step"),
                            0,
                            ActivitySnapshotId("activity"),
                            SequenceStepOverrides(timerZeroBehavior = TimerZeroBehavior.OVERTIME),
                        ),
                    ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            factory().fromTemplate(template, emptyMap(), now)
        }
    }

    @Test
    fun `factory accepts TIMER override and rejects it for non TIMER modes`() {
        val template =
            template(
                nodes =
                    listOf(
                        ActivityStep(
                            SequenceNodeId("step"),
                            0,
                            ActivitySnapshotId("activity"),
                            SequenceStepOverrides(timerZeroBehavior = TimerZeroBehavior.OVERTIME),
                        ),
                    ),
            )

        factory().fromTemplate(template, modes("activity"), now)
        listOf(TimeTrackingMode.STOPWATCH, TimeTrackingMode.NO_LIVE_TRACKING).forEach { mode ->
            assertThrows(IllegalArgumentException::class.java) {
                factory().fromTemplate(template, mapOf(ActivitySnapshotId("activity") to mode), now)
            }
        }
    }

    @Test
    fun `snapshot option identities must be globally unique across fields`() {
        val shared = SequenceSnapshotCategoryOptionId("same")
        val fields =
            listOf("first", "second").mapIndexed { position, id ->
                SequenceSnapshotField(
                    id = SequenceSnapshotFieldId(id),
                    sourceFieldId = null,
                    position = position,
                    nameAtCreation = id,
                    type = CustomFieldType.CATEGORY,
                    categoryOptions =
                        listOf(
                            SequenceSnapshotCategoryOption(shared, null, 0, "Option"),
                        ),
                )
            }
        val snapshot = factory().fromTemplate(template(), emptyMap(), now).copy(fields = fields)

        assertThrows(IllegalArgumentException::class.java) {
            SequenceConfigSnapshotValidator.requireValid(snapshot)
        }
    }

    @Test
    fun `factory rejects duplicate generated field node and global option identities`() {
        val fields = listOf(numberField("first").copy(position = 0), numberField("second").copy(position = 1))
        val nodes =
            listOf(
                ActivityStep(SequenceNodeId("first"), 0, ActivitySnapshotId("a")),
                ActivityStep(SequenceNodeId("second"), 1, ActivitySnapshotId("b")),
            )
        val categories =
            listOf("first", "second").mapIndexed { position, id ->
                val option = SequenceTemplateCategoryOption(SequenceTemplateCategoryOptionId("$id-option"), 0, id)
                SequenceTemplateField(
                    SequenceTemplateFieldId(id),
                    position,
                    id,
                    CustomFieldType.CATEGORY,
                    createdAt = now,
                    updatedAt = now,
                    categoryOptions = listOf(option),
                )
            }

        assertThrows(IllegalArgumentException::class.java) {
            duplicateFactory(fieldId = "same-field").fromTemplate(template(fields = fields), emptyMap(), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            duplicateFactory(nodeId = "same-node").fromTemplate(template(nodes = nodes), modes("a", "b"), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            duplicateFactory(optionId = "same-option").fromTemplate(template(fields = categories), emptyMap(), now)
        }
    }

    @Test
    fun `display resolution follows local source creation precedence`() {
        val option = SequenceSnapshotCategoryOption(SequenceSnapshotCategoryOptionId("option"), null, 0, "Created")
        val field =
            SequenceSnapshotField(
                SequenceSnapshotFieldId("field"),
                null,
                0,
                "Created",
                type = CustomFieldType.CATEGORY,
                categoryOptions = listOf(option),
            )

        assertEquals("Source", SequenceSnapshotDisplayResolver.fieldName(field, "Source"))
        assertEquals("Created", SequenceSnapshotDisplayResolver.fieldName(field, null))
        assertEquals(
            "Local",
            SequenceSnapshotDisplayResolver.fieldName(field.copy(localNameOverride = "Local"), "Source"),
        )
        assertEquals("Source", SequenceSnapshotDisplayResolver.optionLabel(option, "Source"))
        assertEquals("Created", SequenceSnapshotDisplayResolver.optionLabel(option, null))
        assertEquals(
            "Local",
            SequenceSnapshotDisplayResolver.optionLabel(option.copy(localLabelOverride = "Local"), "Source"),
        )
    }

    @Test
    fun `effective settings use frozen sequence and explicit overrides after source changes`() {
        val step =
            ActivityStep(
                SequenceNodeId("step"),
                0,
                ActivitySnapshotId("activity"),
                SequenceStepOverrides(
                    startCountdown = Duration.ZERO,
                    timerZeroBehavior = TimerZeroBehavior.OVERTIME,
                    timerEndSound = false,
                    timerEndVibration = false,
                    keepScreenAwake = false,
                ),
            )
        val source = template(nodes = listOf(step))
        val snapshot = factory().fromTemplate(source, modes("activity"), now)
        val frozenStep = snapshot.nodes.single() as SequenceSnapshotActivityStep
        val activity = timerActivitySnapshot("activity")
        val changedSource =
            source.copy(
                settings =
                    source.settings.copy(
                        sequenceStartCountdown = Duration.ofMinutes(1),
                        transitionSound = true,
                        transitionVibration = true,
                        keepScreenAwake = true,
                    ),
            )

        val effective = EffectiveSequenceStepSettingsResolver.resolve(frozenStep, activity, snapshot.settings, true)

        assertEquals(Duration.ZERO, effective.startCountdown)
        assertEquals(TimerZeroBehavior.OVERTIME, effective.timerZeroBehavior)
        assertFalse(effective.timerEndSound)
        assertFalse(effective.timerEndVibration)
        assertFalse(effective.keepScreenAwake)
        assertNotEquals(changedSource.settings, snapshot.settings)
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

    private fun duplicateFactory(
        fieldId: String? = null,
        optionId: String? = null,
        nodeId: String? = null,
    ): SequenceSnapshotFactory {
        var id = 0
        return SequenceSnapshotFactory(
            nextSnapshotId = { SequenceSnapshotId("snapshot") },
            nextFieldId = { SequenceSnapshotFieldId(fieldId ?: "field-${++id}") },
            nextOptionId = { SequenceSnapshotCategoryOptionId(optionId ?: "option-${++id}") },
            nextNodeId = { SequenceSnapshotNodeId(nodeId ?: "node-${++id}") },
        )
    }

    private fun modes(vararg ids: String) = ids.associate { ActivitySnapshotId(it) to TimeTrackingMode.TIMER }

    private fun timerActivitySnapshot(id: String) =
        ActivityConfigSnapshot(
            ActivitySnapshotId(id),
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
