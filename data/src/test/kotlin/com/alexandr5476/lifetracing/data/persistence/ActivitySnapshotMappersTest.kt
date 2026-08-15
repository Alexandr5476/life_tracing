package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOption
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotField
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.ActivityTemplateFieldId
import com.alexandr5476.lifetracing.domain.ActivityTemplateId
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.CategoryOptionId
import com.alexandr5476.lifetracing.domain.CustomFieldType
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ActivitySnapshotMappersTest {
    @Test
    fun `complete aggregate round trips all identities and immutable values`() {
        val option =
            ActivitySnapshotCategoryOption(
                ActivitySnapshotCategoryOptionId("snapshot-option"),
                CategoryOptionId("source-option"),
                1,
                "At creation",
                "Local",
            )
        val snapshot =
            ActivityConfigSnapshot(
                id = ActivitySnapshotId("snapshot"),
                name = "Activity",
                shortComment = "Comment",
                timeTrackingMode = TimeTrackingMode.TIMER,
                timerTarget = Duration.ofMillis(1_500),
                sourceTemplateId = ActivityTemplateId("template"),
                sourceRevision = 7,
                statisticsSeriesId = StatisticsSeriesId("series"),
                locallyModified = true,
                createdAt = Instant.ofEpochMilli(123),
                settings =
                    ActivityTemplateSettings(
                        showSeconds = false,
                        startCountdown = Duration.ofMillis(500),
                        timerZeroBehavior = TimerZeroBehavior.OVERTIME,
                        timerEndSound = false,
                        timerEndVibration = false,
                        keepScreenAwake = true,
                        confirmManualFinish = true,
                    ),
                fields =
                    listOf(
                        ActivitySnapshotField(
                            id = ActivitySnapshotFieldId("field"),
                            sourceFieldId = ActivityTemplateFieldId("source-field"),
                            position = 2,
                            nameAtCreation = "Category",
                            localNameOverride = "Local Category",
                            type = CustomFieldType.CATEGORY,
                            defaultCategoryOptionId = option.id,
                            categoryOptions = listOf(option),
                        ),
                    ),
            )

        assertEquals(snapshot, snapshot.toEntityAggregate().toDomain())
    }

    @Test
    fun `unknown persisted enum codes fail explicitly`() {
        val aggregate = validAggregate()

        assertThrows(IllegalStateException::class.java) {
            aggregate.copy(snapshot = aggregate.snapshot.copy(timeTrackingMode = "FUTURE")).toDomain()
        }
        assertThrows(IllegalStateException::class.java) {
            aggregate.copy(settings = aggregate.settings.copy(timerZeroBehavior = "FUTURE")).toDomain()
        }
        assertThrows(IllegalStateException::class.java) {
            aggregate.copy(fields = listOf(numberField().copy(fieldType = "FUTURE"))).toDomain()
        }
    }

    private fun validAggregate() =
        ActivitySnapshotAggregateEntity(
            snapshot =
                ActivitySnapshotEntity(
                    "snapshot",
                    "Snapshot",
                    null,
                    "STOPWATCH",
                    null,
                    null,
                    null,
                    null,
                    false,
                    10,
                ),
            settings = ActivitySnapshotSettingsEntity("snapshot"),
            fields = listOf(numberField()),
        )

    private fun numberField() =
        ActivitySnapshotFieldEntity(
            "field",
            "snapshot",
            null,
            0,
            "Number",
            null,
            "NUMBER",
            null,
            null,
            12_345,
            null,
            null,
            false,
        )
}
