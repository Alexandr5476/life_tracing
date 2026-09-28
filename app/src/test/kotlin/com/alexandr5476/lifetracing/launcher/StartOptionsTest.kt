package com.alexandr5476.lifetracing.launcher

import com.alexandr5476.lifetracing.domain.ActivityEntryValue
import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.CategoryOption
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@Suppress("MaxLineLength")
class StartOptionsTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun nonexistentAmbiguousFutureAndReversedTimesDoNotProduceCommands() {
        val template = template()
        val gap = template.initialStartOptions(now, berlin).copy(startedText = "2026-03-29T02:30", startedOffset = null)
        assertEquals(
            StartOptionsIssue.NONEXISTENT_START,
            (gap.validate(now, berlin) as StartOptionsValidation.Invalid).issue,
        )

        val overlap = gap.copy(startedText = "2026-10-25T02:30")
        val ambiguous = overlap.validate(now, berlin) as StartOptionsValidation.Invalid
        assertEquals(StartOptionsIssue.AMBIGUOUS_START, ambiguous.issue)
        assertEquals(2, ambiguous.offsets.size)
        val resolved = overlap.copy(startedOffset = ambiguous.offsets.last()).validate(now, berlin)
        assertTrue(resolved is StartOptionsValidation.Valid)

        val future = gap.copy(startedText = "2026-11-02T12:00")
        assertEquals(
            StartOptionsIssue.FUTURE_START,
            (future.validate(now, berlin) as StartOptionsValidation.Invalid).issue,
        )
        val reversed = gap.copy(startedText = "2026-10-25T04:00", completedText = "2026-10-25T03:00")
        assertEquals(
            StartOptionsIssue.REVERSED_INTERVAL,
            (reversed.validate(now, berlin) as StartOptionsValidation.Invalid).issue,
        )
    }

    @Test
    fun openingDuringRepeatedHourStillRequiresExplicitOffsetChoice() {
        val firstOccurrence = Instant.parse("2026-10-25T00:30:00Z")
        val draft = template().initialStartOptions(firstOccurrence, berlin)
        assertEquals(null, draft.startedOffset)
        val unresolved = draft.validate(firstOccurrence.plusSeconds(60), berlin) as StartOptionsValidation.Invalid
        assertEquals(StartOptionsIssue.AMBIGUOUS_START, unresolved.issue)
    }

    @Test
    fun offsetFromDifferentZoneCannotChangeAnUnambiguousLocalTime() {
        val draft =
            template().initialStartOptions(now, ZoneOffset.UTC).copy(
                startedText = "2026-08-20T09:00",
                startedOffset = ZoneOffset.ofHours(5),
            )
        val result = draft.validate(now, ZoneOffset.UTC) as StartOptionsValidation.Valid
        assertEquals(Instant.parse("2026-08-20T09:00:00Z"), result.proposal.startedAt)
    }

    @Test
    fun crossMidnightAndFieldValuesRetainExactProvenanceAndMissing() {
        val template = template(fields = true)
        val draft =
            template.initialStartOptions(now, ZoneOffset.UTC).copy(
                startedText = "2026-08-20T23:50",
                completedText = "2026-08-21T00:30",
            )
        val result = draft.validate(now, ZoneOffset.UTC) as StartOptionsValidation.Valid
        assertEquals(Instant.parse("2026-08-20T23:50:00Z"), result.proposal.startedAt)
        assertEquals(Instant.parse("2026-08-21T00:30:00Z"), result.proposal.completedAt)
        assertEquals(1, result.proposal.expectedRevision)
        assertEquals(
            listOf(
                ActivityEntryValue.Number(0),
                ActivityEntryValue.Category(
                    com.alexandr5476.lifetracing.domain.ActivityEntryOptionReference
                        .Template(CategoryOptionId("option")),
                ),
                ActivityEntryValue.Missing,
            ),
            result.proposal.values.map { it.value },
        )
        val edited =
            draft.copy(
                values =
                    draft.values +
                        mapOf(
                            ActivityTemplateFieldId("number") to
                                draft.values.getValue(ActivityTemplateFieldId("number")).copy(numberText = "12"),
                            ActivityTemplateFieldId("category") to
                                draft.values
                                    .getValue(ActivityTemplateFieldId("category"))
                                    .copy(selectedOptionId = CategoryOptionId("other")),
                            ActivityTemplateFieldId("text") to
                                draft.values
                                    .getValue(ActivityTemplateFieldId("text"))
                                    .copy(text = "actual", missing = false),
                        ),
            )
        val actual =
            (
                edited.validate(
                    now,
                    ZoneOffset.UTC,
                ) as StartOptionsValidation.Valid
            ).proposal.values.map { it.value }
        assertEquals(ActivityEntryValue.Number(12_000), actual[0])
        assertEquals(
            ActivityEntryValue.Category(
                com.alexandr5476.lifetracing.domain.ActivityEntryOptionReference
                    .Template(CategoryOptionId("other")),
            ),
            actual[1],
        )
        assertEquals(ActivityEntryValue.Text("actual"), actual[2])
    }

    private fun template(fields: Boolean = false) =
        ActivityTemplate(
            ActivityTemplateId("activity"),
            "activity",
            null,
            TimeTrackingMode.STOPWATCH,
            null,
            StatisticsSeriesId("series"),
            1,
            now,
            now,
            fields =
                if (!fields) {
                    emptyList()
                } else {
                    listOf(
                        ActivityTemplateField(
                            ActivityTemplateFieldId("number"),
                            0,
                            "Number",
                            CustomFieldType.NUMBER,
                            displayPrecision = 0,
                            defaultNumberScaled = 0,
                            createdAt = now,
                            updatedAt = now,
                        ),
                        ActivityTemplateField(
                            ActivityTemplateFieldId("category"),
                            1,
                            "Category",
                            CustomFieldType.CATEGORY,
                            defaultCategoryOptionId = CategoryOptionId("option"),
                            createdAt = now,
                            updatedAt = now,
                            categoryOptions =
                                listOf(
                                    CategoryOption(CategoryOptionId("option"), 0, "One"),
                                    CategoryOption(CategoryOptionId("other"), 1, "Two"),
                                ),
                        ),
                        ActivityTemplateField(
                            ActivityTemplateFieldId("text"),
                            2,
                            "Text",
                            CustomFieldType.TEXT,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                },
        )
}
