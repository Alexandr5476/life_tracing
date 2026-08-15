package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityStep
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.SequenceNodeId
import com.alexandr5476.lifetracing.domain.SequenceRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceTemplate
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOption
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateField
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.SequenceTemplateSettings
import com.alexandr5476.lifetracing.domain.SequenceTemplateUserState
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TagId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SequenceTemplateMappersTest {
    private val now = Instant.ofEpochMilli(1_700_000_000_000)

    @Test
    fun `full aggregate round trips stable storage codes typed settings fields and nested nodes`() {
        val option = SequenceTemplateCategoryOption(SequenceTemplateCategoryOptionId("option"), 0, "Option")
        val template =
            SequenceTemplate(
                id = SequenceTemplateId("sequence"),
                name = "Sequence",
                shortComment = "Comment",
                statisticsSeriesId = StatisticsSeriesId("series"),
                revision = 4,
                createdAt = now,
                updatedAt = now,
                noLiveTimeAccounting = NoLiveTimeAccounting.PAUSE,
                settings =
                    SequenceTemplateSettings(
                        autoAdvance = false,
                        sequenceStartCountdown = Duration.ofSeconds(2),
                        beforeEachStepCountdown = Duration.ofSeconds(1),
                    ),
                userState = SequenceTemplateUserState(3, now),
                fields =
                    listOf(
                        SequenceTemplateField(
                            id = SequenceTemplateFieldId("category"),
                            position = 0,
                            name = "Category",
                            type = CustomFieldType.CATEGORY,
                            defaultCategoryOptionId = option.id,
                            createdAt = now,
                            updatedAt = now,
                            categoryOptions = listOf(option),
                        ),
                    ),
                tagIds = setOf(TagId("tag")),
                nodes =
                    listOf(
                        ActivityStep(SequenceNodeId("top"), 0, ActivitySnapshotId("top-snapshot")),
                        SequenceRepeatBlock(
                            SequenceNodeId("repeat"),
                            1,
                            3,
                            listOf(ActivityStep(SequenceNodeId("child"), 0, ActivitySnapshotId("child-snapshot"))),
                        ),
                    ),
            )

        val aggregate = template.toEntityAggregate()

        assertEquals("PAUSE", aggregate.template.noLiveTimeAccounting)
        assertEquals(2_000, aggregate.settings.sequenceStartCountdownMs)
        assertEquals(listOf("top", "repeat", "child"), aggregate.nodes.map { it.id })
        assertEquals(template, aggregate.toDomain())
    }

    @Test
    fun `unknown persisted codes fail explicitly`() {
        val base =
            SequenceTemplateAggregateEntity(
                template = SequenceTemplateEntity("sequence", "Sequence", null, "series", 1, 1, 1, null, null),
                settings = SequenceTemplateSettingsEntity("sequence"),
                userState = SequenceTemplateUserStateEntity("sequence", null, null),
            )
        assertThrows(IllegalStateException::class.java) {
            base.copy(template = base.template.copy(noLiveTimeAccounting = "UNKNOWN")).toDomain()
        }
        assertThrows(IllegalStateException::class.java) {
            base
                .copy(
                    fields =
                        listOf(
                            SequenceTemplateFieldEntity(
                                "field",
                                "sequence",
                                0,
                                "Field",
                                "UNKNOWN",
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                1,
                                1,
                                null,
                            ),
                        ),
                ).toDomain()
        }
        assertThrows(IllegalStateException::class.java) {
            base.copy(nodes = listOf(SequenceNodeEntity("node", "sequence", "UNKNOWN", null, 0, null, null))).toDomain()
        }
    }
}
