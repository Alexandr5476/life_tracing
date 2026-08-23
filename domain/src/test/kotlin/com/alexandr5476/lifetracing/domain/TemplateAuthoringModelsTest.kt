package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TemplateAuthoringModelsTest {
    private val now = Instant.parse("2026-08-23T10:00:00Z")

    @Test
    fun `Activity save classification separates semantic presentation and no-op changes`() {
        val current = activityTemplate()

        assertEquals(AuthoringSaveKind.NO_OP, TemplateAuthoringPolicy.classify(current, current.copy()))
        assertEquals(
            AuthoringSaveKind.PRESENTATION_ONLY,
            TemplateAuthoringPolicy.classify(
                current,
                current.copy(fields = listOf(current.fields.single().copy(name = "Renamed"))),
            ),
        )
        assertEquals(
            AuthoringSaveKind.SEMANTIC,
            TemplateAuthoringPolicy.classify(current, current.copy(shortComment = "Changed")),
        )
        assertEquals(
            AuthoringSaveKind.SEMANTIC,
            TemplateAuthoringPolicy.classify(
                current,
                current.copy(fields = listOf(current.fields.single().copy(defaultNumberScaled = 2_000))),
            ),
        )
    }

    @Test
    fun `Sequence reorder is semantic while unchanged structure is a no-op`() {
        val first = ActivityStep(SequenceNodeId("first"), 0, ActivitySnapshotId("first-snapshot"))
        val second = ActivityStep(SequenceNodeId("second"), 1, ActivitySnapshotId("second-snapshot"))
        val current = sequenceTemplate(listOf(first, second))
        val reordered = current.copy(nodes = listOf(second.copy(position = 0), first.copy(position = 1)))

        assertEquals(AuthoringSaveKind.NO_OP, TemplateAuthoringPolicy.classify(current, current.copy()))
        assertEquals(AuthoringSaveKind.SEMANTIC, TemplateAuthoringPolicy.classify(current, reordered))
        assertEquals(first.activitySnapshotId, reordered.nodes.filterIsInstance<ActivityStep>()[1].activitySnapshotId)
    }

    @Test
    fun `snapshot comparison ignores replacement identities but preserves local override intent`() {
        val current = snapshot("old", "old-field", "old-option")
        val replacement = snapshot("new", "new-field", "new-option")

        assertTrue(TemplateAuthoringPolicy.sameConfiguration(current, replacement))
        assertFalse(
            TemplateAuthoringPolicy.sameConfiguration(
                current,
                replacement.copy(
                    fields = listOf(replacement.fields.single().copy(localNameOverride = "Route distance")),
                ),
            ),
        )
    }

    @Test
    fun `draft validation requires explicit unique identities and flat Repeat structure`() {
        val duplicate = DraftIdentity.New("same")
        val field =
            ActivityFieldDraft(
                duplicate,
                0,
                "Distance",
                CustomFieldType.NUMBER,
                unit = "km",
            )
        assertThrows(IllegalArgumentException::class.java) {
            TemplateAuthoringDraftValidator.requireValid(
                ActivityTemplateDraft(
                    "Walk",
                    null,
                    TimeTrackingMode.STOPWATCH,
                    null,
                    fields = listOf(field, field.copy(position = 1)),
                ),
            )
        }

        val step =
            ActivityStepDraft(
                DraftIdentity.New("step"),
                0,
                StepActivityDraft.Local(snapshotDraft()),
            )
        val valid =
            SequenceTemplateDraft(
                "Sequence",
                null,
                nodes =
                    listOf(
                        SequenceNodeDraft.Repeat(
                            SequenceRepeatBlockDraft(DraftIdentity.New("repeat"), 0, 2, listOf(step)),
                        ),
                    ),
            )
        TemplateAuthoringDraftValidator.requireValid(valid)
        assertThrows(IllegalArgumentException::class.java) {
            TemplateAuthoringDraftValidator.requireValid(
                valid.copy(
                    nodes =
                        listOf(
                            SequenceNodeDraft.Repeat(
                                valid.nodes.filterIsInstance<SequenceNodeDraft.Repeat>().single().value.copy(
                                    repeatCount = 0,
                                ),
                            ),
                        ),
                ),
            )
        }
    }

    @Test
    fun `propagation modes follow the whole-snapshot flag`() {
        assertTrue(TemplateAuthoringPolicy.shouldPropagate(false, LinkedStepPropagationMode.ONLY_UNMODIFIED))
        assertFalse(TemplateAuthoringPolicy.shouldPropagate(true, LinkedStepPropagationMode.ONLY_UNMODIFIED))
        assertTrue(TemplateAuthoringPolicy.shouldPropagate(true, LinkedStepPropagationMode.ALL))
    }

    private fun activityTemplate() =
        ActivityTemplate(
            ActivityTemplateId("activity"),
            "Walk",
            null,
            TimeTrackingMode.STOPWATCH,
            null,
            StatisticsSeriesId("series"),
            createdAt = now,
            updatedAt = now,
            fields =
                listOf(
                    ActivityTemplateField(
                        ActivityTemplateFieldId("distance"),
                        0,
                        "Distance",
                        CustomFieldType.NUMBER,
                        "km",
                        3,
                        1_000,
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
        )

    private fun sequenceTemplate(nodes: List<SequenceNode>) =
        SequenceTemplate(
            SequenceTemplateId("sequence"),
            "Sequence",
            null,
            StatisticsSeriesId("sequence-series"),
            createdAt = now,
            updatedAt = now,
            nodes = nodes,
        )

    private fun snapshot(
        id: String,
        fieldId: String,
        optionId: String,
    ) = ActivityConfigSnapshot(
        ActivitySnapshotId(id),
        "Walk",
        null,
        TimeTrackingMode.STOPWATCH,
        null,
        ActivityTemplateId("activity"),
        1,
        StatisticsSeriesId("series"),
        false,
        now,
        fields =
            listOf(
                ActivitySnapshotField(
                    ActivitySnapshotFieldId(fieldId),
                    ActivityTemplateFieldId("source-field"),
                    0,
                    "Difficulty",
                    type = CustomFieldType.CATEGORY,
                    defaultCategoryOptionId = ActivitySnapshotCategoryOptionId(optionId),
                    categoryOptions =
                        listOf(
                            ActivitySnapshotCategoryOption(
                                ActivitySnapshotCategoryOptionId(optionId),
                                CategoryOptionId("source-option"),
                                0,
                                "Easy",
                            ),
                        ),
                ),
            ),
    )

    private fun snapshotDraft() =
        ActivitySnapshotDraft(
            "Local",
            null,
            TimeTrackingMode.TIMER,
            Duration.ofMinutes(1),
        )
}
