package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ActivitySnapshotTest {
    private val now = Instant.parse("2026-08-15T10:00:00Z")

    @Test
    fun `factory copies frozen template configuration and source identities`() {
        val template = template(fields = listOf(numberField(isMainValue = true)))
        val snapshot = factory().fromTemplate(template, now)

        assertEquals("Template", snapshot.name)
        assertEquals("Comment", snapshot.shortComment)
        assertEquals(TimeTrackingMode.TIMER, snapshot.timeTrackingMode)
        assertEquals(Duration.ofSeconds(30), snapshot.timerTarget)
        assertEquals(template.id, snapshot.sourceTemplateId)
        assertEquals(template.revision, snapshot.sourceRevision)
        assertEquals(template.statisticsSeriesId, snapshot.statisticsSeriesId)
        assertEquals(template.settings, snapshot.settings)
        assertFalse(snapshot.locallyModified)
        assertEquals(now, snapshot.createdAt)
        assertNotEquals(
            template.fields
                .single()
                .id.value,
            snapshot.fields
                .single()
                .id.value,
        )
        assertEquals(template.fields.single().id, snapshot.fields.single().sourceFieldId)
        assertEquals(12_345L, snapshot.fields.single().defaultNumberScaled)
    }

    @Test
    fun `later template edits do not mutate frozen activity name or configuration`() {
        val original = template(fields = listOf(numberField()))
        val snapshot = factory().fromTemplate(original, now)
        val edited = original.copy(name = "Renamed", shortComment = "Changed", settings = ActivityTemplateSettings())

        assertEquals("Template", snapshot.name)
        assertEquals("Comment", snapshot.shortComment)
        assertNotEquals(edited.name, snapshot.name)
        assertNotEquals(edited.settings, snapshot.settings)
    }

    @Test
    fun `factory excludes archived fields and options and remaps active category default`() {
        val activeDefault = CategoryOption(CategoryOptionId("source-active"), 2, "Active")
        val archived = CategoryOption(CategoryOptionId("source-archived"), 1, "Old", isArchived = true)
        val category =
            categoryField(
                options = listOf(archived, activeDefault),
                defaultId = activeDefault.id,
            )
        val deletedField = numberField(id = "deleted").copy(deletedAt = now)

        val snapshot = factory().fromTemplate(template(fields = listOf(deletedField, category)), now)
        val copied = snapshot.fields.single()

        assertEquals(category.id, copied.sourceFieldId)
        assertEquals(listOf(activeDefault.id), copied.categoryOptions.map { it.sourceOptionId })
        assertNotEquals(
            activeDefault.id.value,
            copied.categoryOptions
                .single()
                .id.value,
        )
        assertEquals(copied.categoryOptions.single().id, copied.defaultCategoryOptionId)
        assertNull(copied.localNameOverride)
        assertNull(copied.categoryOptions.single().localLabelOverride)
    }

    @Test
    fun `template category default must be active and belong to its field`() {
        val active = CategoryOption(CategoryOptionId("active"), 0, "Active")
        val archived = CategoryOption(CategoryOptionId("archived"), 1, "Archived", isArchived = true)

        assertDoesNotThrow {
            ActivityTemplateValidator.requireValidField(categoryField(listOf(active, archived), active.id))
            ActivityTemplateValidator.requireValidField(categoryField(listOf(active, archived), null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityTemplateValidator.requireValidField(categoryField(listOf(active, archived), archived.id))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityTemplateValidator.requireValidField(
                categoryField(listOf(active), CategoryOptionId("other")),
            )
        }
    }

    @Test
    fun `display resolver follows local source fallback precedence`() {
        val field = snapshotNumberField().copy(nameAtCreation = "At creation")
        val option =
            ActivitySnapshotCategoryOption(
                ActivitySnapshotCategoryOptionId("option"),
                CategoryOptionId("source-option"),
                0,
                "At creation option",
            )

        assertEquals(
            "Local",
            ActivitySnapshotDisplayResolver.fieldName(field.copy(localNameOverride = "Local"), "Source"),
        )
        assertEquals("Source renamed", ActivitySnapshotDisplayResolver.fieldName(field, "Source renamed"))
        assertEquals("At creation", ActivitySnapshotDisplayResolver.fieldName(field, null))
        assertEquals(
            "Local option",
            ActivitySnapshotDisplayResolver.optionLabel(option.copy(localLabelOverride = "Local option"), "Source"),
        )
        assertEquals("Source option", ActivitySnapshotDisplayResolver.optionLabel(option, "Source option"))
        assertEquals("At creation option", ActivitySnapshotDisplayResolver.optionLabel(option, null))
    }

    @Test
    fun `tracking settings and source metadata invariants reject invalid snapshots`() {
        listOf(
            snapshot().copy(timeTrackingMode = TimeTrackingMode.TIMER, timerTarget = null),
            snapshot().copy(timeTrackingMode = TimeTrackingMode.TIMER, timerTarget = Duration.ZERO),
            snapshot().copy(timerTarget = Duration.ofSeconds(1)),
            snapshot().copy(settings = ActivityTemplateSettings(startCountdown = Duration.ofMillis(-1))),
            snapshot().copy(sourceRevision = null),
            snapshot().copy(sourceRevision = 0),
            snapshot().copy(statisticsSeriesId = null),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ActivityConfigSnapshotValidator.requireValid(invalid)
            }
        }
    }

    @Test
    fun `main value must be a single number field`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivityConfigSnapshotValidator.requireValidField(snapshotCategoryField().copy(isMainValue = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityConfigSnapshotValidator.requireValid(
                snapshot(fields = listOf(snapshotNumberField("one", true), snapshotNumberField("two", true))),
            )
        }
        assertDoesNotThrow {
            ActivityConfigSnapshotValidator.requireValid(
                snapshot(fields = listOf(snapshotNumberField(isMainValue = true))),
            )
            ActivityConfigSnapshotValidator.requireValid(snapshot())
        }
    }

    @Test
    fun `snapshot field types reject metadata from other types`() {
        listOf(
            snapshotNumberField().copy(defaultText = "wrong"),
            snapshotCategoryField().copy(defaultNumberScaled = 1),
            snapshotTextField().copy(defaultCategoryOptionId = ActivitySnapshotCategoryOptionId("wrong")),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ActivityConfigSnapshotValidator.requireValidField(invalid)
            }
        }
    }

    @Test
    fun `category default must belong to the same snapshot field`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivityConfigSnapshotValidator.requireValidField(
                snapshotCategoryField().copy(defaultCategoryOptionId = ActivitySnapshotCategoryOptionId("other")),
            )
        }
    }

    @Test
    fun `source-less one-off and orphaned historical snapshot remain valid`() {
        val oneOffOption =
            ActivitySnapshotCategoryOption(
                ActivitySnapshotCategoryOptionId("local-option"),
                null,
                0,
                "Local",
            )
        val oneOffField =
            snapshotCategoryField()
                .copy(
                    sourceFieldId = null,
                    categoryOptions = listOf(oneOffOption),
                    defaultCategoryOptionId = oneOffOption.id,
                )
        val oneOff =
            snapshot(fields = listOf(oneOffField)).copy(
                sourceTemplateId = null,
                sourceRevision = null,
                statisticsSeriesId = null,
            )
        val orphaned = oneOff.copy(sourceRevision = 9, statisticsSeriesId = StatisticsSeriesId("historical-series"))

        assertDoesNotThrow { ActivityConfigSnapshotValidator.requireValid(oneOff) }
        assertDoesNotThrow { ActivityConfigSnapshotValidator.requireValid(orphaned) }
    }

    private fun factory(): ActivitySnapshotFactory {
        var snapshot = 0
        var field = 0
        var option = 0
        return ActivitySnapshotFactory(
            nextSnapshotId = { ActivitySnapshotId("snapshot-${++snapshot}") },
            nextFieldId = { ActivitySnapshotFieldId("snapshot-field-${++field}") },
            nextOptionId = { ActivitySnapshotCategoryOptionId("snapshot-option-${++option}") },
        )
    }

    private fun template(fields: List<ActivityTemplateField>) =
        ActivityTemplate(
            id = ActivityTemplateId("template"),
            name = "Template",
            shortComment = "Comment",
            timeTrackingMode = TimeTrackingMode.TIMER,
            timerTarget = Duration.ofSeconds(30),
            statisticsSeriesId = StatisticsSeriesId("series"),
            revision = 4,
            createdAt = now.minusSeconds(10),
            updatedAt = now,
            settings = ActivityTemplateSettings(showSeconds = false, timerZeroBehavior = TimerZeroBehavior.OVERTIME),
            fields = fields,
        )

    private fun numberField(
        id: String = "number",
        isMainValue: Boolean = false,
    ) = ActivityTemplateField(
        id = ActivityTemplateFieldId(id),
        position = 0,
        name = "Weight",
        type = CustomFieldType.NUMBER,
        unit = "kg",
        displayPrecision = 3,
        defaultNumberScaled = 12_345,
        isMainValue = isMainValue,
        createdAt = now,
        updatedAt = now,
    )

    private fun categoryField(
        options: List<CategoryOption>,
        defaultId: CategoryOptionId?,
    ) = ActivityTemplateField(
        id = ActivityTemplateFieldId("category"),
        position = 1,
        name = "Effort",
        type = CustomFieldType.CATEGORY,
        defaultCategoryOptionId = defaultId,
        createdAt = now,
        updatedAt = now,
        categoryOptions = options,
    )

    private fun snapshot(fields: List<ActivitySnapshotField> = emptyList()) =
        ActivityConfigSnapshot(
            id = ActivitySnapshotId("snapshot"),
            name = "Snapshot",
            shortComment = null,
            timeTrackingMode = TimeTrackingMode.STOPWATCH,
            timerTarget = null,
            sourceTemplateId = ActivityTemplateId("template"),
            sourceRevision = 1,
            statisticsSeriesId = StatisticsSeriesId("series"),
            locallyModified = false,
            createdAt = now,
            fields = fields,
        )

    private fun snapshotNumberField(
        id: String = "number",
        isMainValue: Boolean = false,
    ) = ActivitySnapshotField(
        id = ActivitySnapshotFieldId(id),
        sourceFieldId = ActivityTemplateFieldId("source-$id"),
        position = 0,
        nameAtCreation = "Number",
        type = CustomFieldType.NUMBER,
        defaultNumberScaled = 12_345,
        isMainValue = isMainValue,
    )

    private fun snapshotCategoryField(): ActivitySnapshotField {
        val option =
            ActivitySnapshotCategoryOption(
                ActivitySnapshotCategoryOptionId("option"),
                CategoryOptionId("source-option"),
                0,
                "Option",
            )
        return ActivitySnapshotField(
            id = ActivitySnapshotFieldId("category"),
            sourceFieldId = ActivityTemplateFieldId("source-category"),
            position = 0,
            nameAtCreation = "Category",
            type = CustomFieldType.CATEGORY,
            defaultCategoryOptionId = option.id,
            categoryOptions = listOf(option),
        )
    }

    private fun snapshotTextField() =
        ActivitySnapshotField(
            id = ActivitySnapshotFieldId("text"),
            sourceFieldId = null,
            position = 0,
            nameAtCreation = "Text",
            type = CustomFieldType.TEXT,
            defaultText = "Default",
        )
}
