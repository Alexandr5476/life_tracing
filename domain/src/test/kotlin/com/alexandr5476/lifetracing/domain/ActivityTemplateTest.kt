package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ActivityTemplateTest {
    private val now = Instant.ofEpochMilli(1_700_000_000_000)

    @Test
    fun `valid time tracking configurations are accepted`() {
        assertDoesNotThrow { ActivityTemplateValidator.requireValidTracking(TimeTrackingMode.STOPWATCH, null) }
        assertDoesNotThrow { ActivityTemplateValidator.requireValidTracking(TimeTrackingMode.NO_LIVE_TRACKING, null) }
        assertDoesNotThrow {
            ActivityTemplateValidator.requireValidTracking(TimeTrackingMode.TIMER, Duration.ofSeconds(1))
        }
    }

    @Test
    fun `timer requires a positive target`() {
        listOf(null, Duration.ZERO, Duration.ofMillis(-1)).forEach { target ->
            assertThrows(IllegalArgumentException::class.java) {
                ActivityTemplateValidator.requireValidTracking(TimeTrackingMode.TIMER, target)
            }
        }
    }

    @Test
    fun `non timer modes reject targets`() {
        listOf(TimeTrackingMode.STOPWATCH, TimeTrackingMode.NO_LIVE_TRACKING).forEach { mode ->
            assertThrows(IllegalArgumentException::class.java) {
                ActivityTemplateValidator.requireValidTracking(mode, Duration.ofSeconds(1))
            }
        }
    }

    @Test
    fun `semantic edits increment revision once per committed edit`() {
        assertEquals(ActivityTemplateRevisionPolicy.INITIAL_REVISION, template().revision)
        listOf(
            ActivityTemplateEdit.NAME,
            ActivityTemplateEdit.SHORT_COMMENT,
            ActivityTemplateEdit.TRACKING_CONFIGURATION,
            ActivityTemplateEdit.SETTINGS,
            ActivityTemplateEdit.FIELD_SCHEMA,
        ).forEach { edit ->
            assertEquals(2, ActivityTemplateRevisionPolicy.after(1, edit))
        }
    }

    @Test
    fun `library and presentation metadata do not increment revision`() {
        assertEquals(
            4,
            ActivityTemplateRevisionPolicy.after(
                4,
                ActivityTemplateEdit.FOLDER,
                ActivityTemplateEdit.TAGS,
                ActivityTemplateEdit.USER_STATE,
                ActivityTemplateEdit.FIELD_DISPLAY_NAME,
            ),
        )
    }

    @Test
    fun `number category and text fields validate`() {
        assertDoesNotThrow { ActivityTemplateValidator.requireValidField(numberField()) }
        assertDoesNotThrow { ActivityTemplateValidator.requireValidField(categoryField()) }
        assertDoesNotThrow { ActivityTemplateValidator.requireValidField(textField()) }
    }

    @Test
    fun `field types reject columns belonging to other types`() {
        val invalidFields =
            listOf(
                numberField().copy(defaultText = "wrong"),
                categoryField().copy(defaultNumberScaled = 1),
                textField().copy(defaultCategoryOptionId = CategoryOptionId("option")),
            )

        invalidFields.forEach { field ->
            assertThrows(IllegalArgumentException::class.java) {
                ActivityTemplateValidator.requireValidField(field)
            }
        }
    }

    @Test
    fun `category default must belong to the field`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivityTemplateValidator.requireValidField(
                categoryField().copy(defaultCategoryOptionId = CategoryOptionId("other")),
            )
        }
    }

    @Test
    fun `Main Value must be Number and unique among active fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivityTemplateValidator.requireValidField(categoryField().copy(isMainValue = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityTemplateValidator.requireValid(
                template(fields = listOf(numberField("one", true), numberField("two", true))),
            )
        }
    }

    @Test
    fun `archived Main Value allows a new active Main Value`() {
        val fields =
            listOf(
                numberField("old", true).copy(deletedAt = now),
                numberField("new", true),
            )

        assertDoesNotThrow { ActivityTemplateValidator.requireValid(template(fields = fields)) }
    }

    @Test
    fun `field and option presentation changes preserve identity`() {
        val field = numberField()
        val option = CategoryOption(CategoryOptionId("option"), 0, "Original")

        assertEquals(field.id, field.copy(name = "Renamed", deletedAt = now).id)
        assertEquals(option.id, option.copy(label = "Renamed", isArchived = true).id)
    }

    @Test
    fun `archive and restore preserve template and statistics identities`() {
        val original = template()
        val archived = ActivityTemplateLifecycle.archive(original, now)
        val restored = ActivityTemplateLifecycle.restore(archived)

        assertEquals(original.id, archived.id)
        assertEquals(original.statisticsSeriesId, archived.statisticsSeriesId)
        assertEquals(original.revision, archived.revision)
        assertNull(restored.deletedAt)
        assertEquals(original.id, restored.id)
    }

    @Test
    fun `negative countdown is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivityTemplateValidator.requireValid(
                template(settings = ActivityTemplateSettings(startCountdown = Duration.ofMillis(-1))),
            )
        }
    }

    private fun template(
        settings: ActivityTemplateSettings = ActivityTemplateSettings(),
        fields: List<ActivityTemplateField> = emptyList(),
    ) = ActivityTemplate(
        id = ActivityTemplateId("template"),
        name = "Template",
        shortComment = null,
        timeTrackingMode = TimeTrackingMode.STOPWATCH,
        timerTarget = null,
        statisticsSeriesId = StatisticsSeriesId("series"),
        createdAt = now,
        updatedAt = now,
        settings = settings,
        fields = fields,
    )

    private fun numberField(
        id: String = "number",
        isMainValue: Boolean = false,
    ) = ActivityTemplateField(
        id = ActivityTemplateFieldId(id),
        position = 0,
        name = "Number",
        type = CustomFieldType.NUMBER,
        unit = "kg",
        displayPrecision = 3,
        defaultNumberScaled = 12_345,
        isMainValue = isMainValue,
        createdAt = now,
        updatedAt = now,
    )

    private fun categoryField() =
        ActivityTemplateField(
            id = ActivityTemplateFieldId("category"),
            position = 0,
            name = "Category",
            type = CustomFieldType.CATEGORY,
            defaultCategoryOptionId = CategoryOptionId("option"),
            createdAt = now,
            updatedAt = now,
            categoryOptions = listOf(CategoryOption(CategoryOptionId("option"), 0, "Option")),
        )

    private fun textField() =
        ActivityTemplateField(
            id = ActivityTemplateFieldId("text"),
            position = 0,
            name = "Text",
            type = CustomFieldType.TEXT,
            defaultText = "Default",
            createdAt = now,
            updatedAt = now,
        )
}
