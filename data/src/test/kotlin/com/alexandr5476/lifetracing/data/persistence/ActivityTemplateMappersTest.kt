package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivityTemplate
import com.alexandr5476.lifetracing.domain.ActivityTemplateField
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.ActivityTemplateUserState
import com.alexandr5476.lifetracing.domain.CategoryOption
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.FolderId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TagId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ActivityTemplateMappersTest {
    private val now = Instant.ofEpochMilli(1_700_000_000_123)

    @Test
    fun `aggregate constituent round trips preserve typed values and stable codes`() {
        val settings =
            ActivityTemplateSettings(
                showSeconds = false,
                startCountdown = Duration.ofSeconds(3),
                timerZeroBehavior = TimerZeroBehavior.OVERTIME,
                timerEndSound = false,
                keepScreenAwake = true,
                confirmManualFinish = true,
            )
        val option = CategoryOption(CategoryOptionId("option"), 2, "Hard", true)
        val field =
            ActivityTemplateField(
                id = ActivityTemplateFieldId("field"),
                position = 1,
                name = "Effort",
                type = CustomFieldType.CATEGORY,
                defaultCategoryOptionId = option.id,
                createdAt = now,
                updatedAt = now.plusSeconds(1),
                categoryOptions = listOf(option),
            )
        val template =
            ActivityTemplate(
                id = ActivityTemplateId("template"),
                name = "Focus",
                shortComment = "Deep work",
                timeTrackingMode = TimeTrackingMode.TIMER,
                timerTarget = Duration.ofMinutes(25),
                statisticsSeriesId = StatisticsSeriesId("series"),
                revision = 4,
                createdAt = now,
                updatedAt = now.plusSeconds(2),
                folderId = FolderId("folder"),
                settings = settings,
                fields = listOf(field),
                tagIds = setOf(TagId("tag")),
            )

        val mappedSettings = settings.toEntity(template.id).toDomain()
        val mappedOption = option.toEntity(field.id).toDomain()
        val mappedField = field.toEntity(template.id).toDomain(listOf(mappedOption))
        val mapped = template.toEntity().toDomain(mappedSettings, listOf(mappedField), template.tagIds)

        assertEquals("TIMER", template.toEntity().timeTrackingMode)
        assertEquals("OVERTIME", settings.toEntity(template.id).timerZeroBehavior)
        assertEquals("CATEGORY", field.toEntity(template.id).fieldType)
        assertEquals(template, mapped)
    }

    @Test
    fun `user state round trip preserves nullable instant`() {
        val state = ActivityTemplateUserState(3, now)

        assertEquals(state, state.toEntity(ActivityTemplateId("template")).toDomain())
    }

    @Test
    fun `unknown storage codes fail explicitly`() {
        val template = templateEntity(timeTrackingMode = "UNKNOWN")
        val settings = ActivityTemplateSettingsEntity("template", timerZeroBehavior = "UNKNOWN")
        val field = fieldEntity(fieldType = "UNKNOWN")

        assertThrows(IllegalStateException::class.java) {
            template.toDomain(ActivityTemplateSettings(), emptyList(), emptySet())
        }
        assertThrows(IllegalStateException::class.java, settings::toDomain)
        assertThrows(IllegalStateException::class.java) { field.toDomain(emptyList()) }
    }

    private fun templateEntity(timeTrackingMode: String) =
        ActivityTemplateEntity(
            "template",
            "Template",
            null,
            timeTrackingMode,
            null,
            "series",
            1,
            now.toEpochMilli(),
            now.toEpochMilli(),
            null,
            null,
        )

    private fun fieldEntity(fieldType: String) =
        ActivityTemplateFieldEntity(
            "field",
            "template",
            0,
            "Field",
            fieldType,
            null,
            null,
            null,
            null,
            null,
            false,
            now.toEpochMilli(),
            now.toEpochMilli(),
            null,
        )
}
