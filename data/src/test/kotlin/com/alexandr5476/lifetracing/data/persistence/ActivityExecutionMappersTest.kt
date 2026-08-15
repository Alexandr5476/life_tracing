package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivityCompletionReason
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionContext
import com.alexandr5476.lifetracing.domain.ActivityExecutionDurationCalculator
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPause
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
import com.alexandr5476.lifetracing.domain.ActivitySnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotFieldId
import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.CategoryExecutionValue
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TextExecutionValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ActivityExecutionMappersTest {
    @Test
    fun `completed aggregate round trips every current domain field`() {
        val start = Instant.parse("2026-08-15T10:00:00Z")
        val execution =
            ActivityExecution(
                id = ActivityExecutionId("execution"),
                snapshotId = ActivitySnapshotId("snapshot"),
                context = ActivityExecutionContext.STANDALONE,
                statisticsSeriesId = StatisticsSeriesId("series"),
                status = ActivityExecutionStatus.COMPLETED,
                startedAt = start,
                completedAt = start.plusSeconds(90),
                activeDuration = Duration.ofSeconds(60),
                originalZoneId = ZoneId.of("Europe/Moscow"),
                originalUtcOffsetMinutes = 180,
                primaryLocalDate = LocalDate.parse("2026-08-15"),
                completionReason = ActivityCompletionReason.MANUAL_HISTORY_ENTRY,
                deletedAt = null,
                createdAt = start,
                updatedAt = start.plusSeconds(90),
                pauses =
                    listOf(
                        ActivityExecutionPause(
                            ActivityExecutionPauseId("pause"),
                            start.plusSeconds(30),
                            start.plusSeconds(60),
                        ),
                    ),
                values =
                    listOf(
                        NumberExecutionValue(ActivitySnapshotFieldId("number"), 0),
                        CategoryExecutionValue(
                            ActivitySnapshotFieldId("category"),
                            ActivitySnapshotCategoryOptionId("option"),
                        ),
                        TextExecutionValue(ActivitySnapshotFieldId("text"), ""),
                    ),
            )

        assertEquals(execution, execution.toEntityAggregate().toDomain())
    }

    @Test
    fun `mapper round trip uses persisted millisecond precision`() {
        val start = Instant.parse("2026-08-15T10:00:00.000999999Z")
        val completion = Instant.parse("2026-08-15T10:00:00.002000001Z")
        val pauses =
            listOf(
                ActivityExecutionPause(
                    ActivityExecutionPauseId("pause"),
                    Instant.parse("2026-08-15T10:00:00.001000001Z"),
                    Instant.parse("2026-08-15T10:00:00.001999999Z"),
                ),
            )
        val execution =
            ActivityExecution(
                id = ActivityExecutionId("precision"),
                snapshotId = ActivitySnapshotId("snapshot"),
                context = ActivityExecutionContext.STANDALONE,
                statisticsSeriesId = StatisticsSeriesId("series"),
                status = ActivityExecutionStatus.COMPLETED,
                startedAt = start,
                completedAt = completion,
                activeDuration = ActivityExecutionDurationCalculator.calculate(start, completion, pauses),
                originalZoneId = ZoneId.of("UTC"),
                originalUtcOffsetMinutes = 0,
                primaryLocalDate = LocalDate.parse("2026-08-15"),
                completionReason = null,
                deletedAt = null,
                createdAt = start,
                updatedAt = completion,
                pauses = pauses,
                values = emptyList(),
            )

        val entity = execution.toEntityAggregate()
        val loaded = entity.toDomain()

        assertEquals(2L, entity.execution.activeDurationMs)
        assertEquals(Duration.ofMillis(2), loaded.activeDuration)
        assertEquals(Instant.ofEpochMilli(start.toEpochMilli()), loaded.startedAt)
        assertEquals(Instant.ofEpochMilli(completion.toEpochMilli()), loaded.completedAt)
        assertEquals(Instant.ofEpochMilli(start.toEpochMilli() + 1), loaded.pauses.single().startedAt)
        assertEquals(Instant.ofEpochMilli(start.toEpochMilli() + 1), loaded.pauses.single().endedAt)
    }
}
