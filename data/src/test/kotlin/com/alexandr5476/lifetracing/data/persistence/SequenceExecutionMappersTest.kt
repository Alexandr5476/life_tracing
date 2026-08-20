package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActivitySnapshotId
import com.alexandr5476.lifetracing.domain.NumberSequenceExecutionValue
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.RuntimeOccurrence
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceExecution
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SequenceExecutionMappersTest {
    @Test
    fun `complete aggregate round trips every persisted value`() {
        val occurrence =
            RuntimeOccurrence(
                SequenceOccurrenceId("occurrence"),
                SequenceSnapshotNodeId("step"),
                ActivitySnapshotId("activity"),
                0,
                null,
                null,
                RuntimeOccurrenceStatus.COMPLETED,
                Instant.ofEpochMilli(1),
                Instant.ofEpochMilli(9),
                OccurrenceCompletionReason.ADVANCED_TO_NEXT,
                false,
                false,
            )
        val execution =
            SequenceExecution(
                SequenceExecutionId("execution"),
                SequenceSnapshotId("snapshot"),
                StatisticsSeriesId("series"),
                SequenceExecutionStatus.COMPLETED,
                Instant.ofEpochMilli(0),
                Instant.ofEpochMilli(10),
                Duration.ofMillis(8),
                Duration.ofMillis(2),
                Duration.ofMillis(10),
                ZoneId.of("UTC"),
                0,
                LocalDate.parse("1970-01-01"),
                null,
                Instant.ofEpochMilli(0),
                Instant.ofEpochMilli(10),
                listOf(occurrence),
                listOf(
                    SequenceInterval(
                        SequenceIntervalId("interval"),
                        SequenceIntervalKind.ACTIVE_STEP,
                        Instant.ofEpochMilli(1),
                        Instant.ofEpochMilli(9),
                        occurrence.id,
                    ),
                ),
                listOf(NumberSequenceExecutionValue(SequenceSnapshotFieldId("field"), 0)),
            )

        assertEquals(execution, execution.toEntityAggregate().toDomain())
    }

    @Test
    fun `unknown persisted codes fail explicitly`() {
        val aggregate = runningAggregate()
        listOf(
            aggregate.copy(execution = aggregate.execution.copy(status = "UNKNOWN")),
            aggregate.copy(occurrences = listOf(aggregate.occurrences.single().copy(status = "UNKNOWN"))),
            aggregate.copy(
                occurrences =
                    listOf(
                        aggregate.occurrences.single().copy(
                            status = "COMPLETED",
                            enteredAtMs = 0,
                            completedAtMs = 1,
                            completionReason = "UNKNOWN",
                        ),
                    ),
            ),
            aggregate.copy(
                intervals = listOf(SequenceIntervalEntity("interval", "execution", "UNKNOWN", 0, null, null)),
            ),
        ).forEach { invalid ->
            assertThrows(IllegalStateException::class.java) { invalid.toDomain() }
        }
    }

    private fun runningAggregate() =
        SequenceExecutionAggregateEntity(
            SequenceExecutionEntity(
                "execution",
                "snapshot",
                null,
                "series",
                "RUNNING",
                0,
                null,
                null,
                null,
                null,
                "UTC",
                0,
                "1970-01-01",
                null,
                0,
                0,
            ),
            occurrences =
                listOf(
                    SequenceOccurrenceEntity(
                        "occurrence",
                        "execution",
                        "step",
                        "activity",
                        0,
                        null,
                        null,
                        "NOT_STARTED",
                        null,
                        null,
                        null,
                    ),
                ),
        )
}
