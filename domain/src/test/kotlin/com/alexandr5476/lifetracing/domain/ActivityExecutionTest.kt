package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class ActivityExecutionTest {
    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val zone = ZoneId.of("Europe/Moscow")
    private var nextId = 0
    private val factory = ActivityExecutionFactory { ActivityExecutionId("execution-${++nextId}") }

    @Test
    fun `timed start uses event timezone and materializes even zero and empty defaults`() {
        val execution = factory.startTimed(snapshot(), now.minusSeconds(60), now, zone)

        assertEquals(ActivityExecutionStatus.RUNNING, execution.status)
        assertEquals("2026-08-15", execution.primaryLocalDate.toString())
        assertEquals(180, execution.originalUtcOffsetMinutes)
        assertEquals(
            listOf(
                NumberExecutionValue(ActivitySnapshotFieldId("number"), 0),
                CategoryExecutionValue(ActivitySnapshotFieldId("category"), ActivitySnapshotCategoryOptionId("option")),
                TextExecutionValue(ActivitySnapshotFieldId("text"), ""),
            ),
            execution.values,
        )
    }

    @Test
    fun `source-less standalone execution routes to reserved one-off series`() {
        val execution = factory.startTimed(snapshot(seriesId = null), now, now, zone)

        assertEquals(ActivityExecutionStatistics.ONE_OFF_BUCKET_ID, execution.statisticsSeriesId)
    }

    @Test
    fun `Sequence children retain parent links and one-off child has no Statistics Series`() {
        val sequenceId = SequenceExecutionId("sequence")
        val occurrenceId = SequenceOccurrenceId("occurrence")
        val reusable =
            factory.startSequenceChildTimed(snapshot(), sequenceId, occurrenceId, now, now, zone)
        val oneOffNoLive =
            factory.completeSequenceChildNoLive(
                snapshot(mode = TimeTrackingMode.NO_LIVE_TRACKING, seriesId = null),
                sequenceId,
                occurrenceId,
                now,
                zone,
            )

        assertEquals(ActivityExecutionContext.SEQUENCE_CHILD, reusable.context)
        assertEquals(sequenceId, reusable.sequenceExecutionId)
        assertEquals(occurrenceId, reusable.sequenceOccurrenceId)
        assertEquals(StatisticsSeriesId("series"), reusable.statisticsSeriesId)
        assertNull(reusable.completionReason)
        assertEquals(ActivityExecutionStatus.COMPLETED, oneOffNoLive.status)
        assertNull(oneOffNoLive.startedAt)
        assertNull(oneOffNoLive.activeDuration)
        assertNull(oneOffNoLive.statisticsSeriesId)
        assertNull(oneOffNoLive.completionReason)
    }

    @Test
    fun `primary date and offset follow the original zone at the event instant`() {
        val event = Instant.parse("2026-01-01T23:30:00Z")

        val losAngeles = factory.startTimed(snapshot(), event, event, ZoneId.of("America/Los_Angeles"))
        val tokyo = factory.startTimed(snapshot(), event, event, ZoneId.of("Asia/Tokyo"))

        assertEquals("2026-01-01", losAngeles.primaryLocalDate.toString())
        assertEquals(-480, losAngeles.originalUtcOffsetMinutes)
        assertEquals("2026-01-02", tokyo.primaryLocalDate.toString())
        assertEquals(540, tokyo.originalUtcOffsetMinutes)
    }

    @Test
    fun `manual creation rejects future and reversed history`() {
        assertThrows(IllegalArgumentException::class.java) {
            factory.createManualTimed(snapshot(), now, now.minusSeconds(1), now, zone)
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.createManualNoLiveHistory(
                snapshot(mode = TimeTrackingMode.NO_LIVE_TRACKING),
                now.plusSeconds(1),
                now,
                zone,
            )
        }
    }

    @Test
    fun `manual timed execution is completed without replaying timer target`() {
        val execution =
            factory.createManualTimed(
                snapshot(mode = TimeTrackingMode.TIMER),
                now.minusSeconds(90),
                now.minusSeconds(30),
                now,
                zone,
            )

        assertEquals(Duration.ofSeconds(60), execution.activeDuration)
        assertEquals(ActivityCompletionReason.MANUAL_HISTORY_ENTRY, execution.completionReason)
        assertEquals(ActivityExecutionStatus.COMPLETED, execution.status)
    }

    @Test
    fun `quick and historical no-live completion retain distinct reason and timestamps`() {
        val noLive = snapshot(mode = TimeTrackingMode.NO_LIVE_TRACKING)
        val quick = factory.completeNoLiveNow(noLive, now, zone)
        val historical =
            factory.createManualNoLiveHistory(
                noLive,
                now.minusSeconds(30),
                now,
                zone,
            )

        assertNull(quick.startedAt)
        assertNull(quick.activeDuration)
        assertNull(quick.completionReason)
        assertEquals(now, quick.completedAt)
        assertEquals(now, quick.createdAt)
        assertEquals(ActivityCompletionReason.MANUAL_HISTORY_ENTRY, historical.completionReason)
        assertEquals(now.minusSeconds(30), historical.completedAt)
        assertEquals(now, historical.createdAt)
    }

    @Test
    fun `snapshot mode and execution state combinations are enforced`() {
        val stopwatch = snapshot(TimeTrackingMode.STOPWATCH)
        val timer = snapshot(TimeTrackingMode.TIMER)
        val noLive = snapshot(TimeTrackingMode.NO_LIVE_TRACKING)
        val runningStopwatch = factory.startTimed(stopwatch, now, now, zone)
        val runningTimer = factory.startTimed(timer, now, now, zone)
        val completedTimed =
            factory.createManualTimed(stopwatch, now.minusSeconds(2), now.minusSeconds(1), now, zone)
        val completedNoLive = factory.completeNoLiveNow(noLive, now, zone)
        val paused =
            ActivityExecutionTransitions.pause(
                runningStopwatch,
                ActivityExecutionPauseId("mode-pause"),
                now,
            )

        assertDoesNotThrow { ActivityExecutionValidator.requireValid(runningStopwatch, stopwatch) }
        assertDoesNotThrow { ActivityExecutionValidator.requireValid(runningTimer, timer) }
        assertDoesNotThrow { ActivityExecutionValidator.requireValid(completedTimed, stopwatch) }
        assertDoesNotThrow { ActivityExecutionValidator.requireValid(completedNoLive, noLive) }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionValidator.requireValid(runningStopwatch, noLive)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionValidator.requireValid(paused, noLive)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionValidator.requireValid(completedNoLive, stopwatch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionValidator.requireValid(completedNoLive, timer)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionValidator.requireValid(completedTimed, noLive)
        }
    }

    @Test
    fun `timeline calculation and creation use epoch millisecond precision`() {
        val start = Instant.parse("2026-08-15T12:00:00.000999999Z")
        val pauseStart = Instant.parse("2026-08-15T12:00:00.001000001Z")
        val pauseEnd = Instant.parse("2026-08-15T12:00:00.001999999Z")
        val completion = Instant.parse("2026-08-15T12:00:00.002000001Z")
        val duration =
            ActivityExecutionDurationCalculator.calculate(
                start,
                completion,
                listOf(ActivityExecutionPause(ActivityExecutionPauseId("precision"), pauseStart, pauseEnd)),
            )

        val manual = factory.createManualTimed(snapshot(), start, completion, completion, zone)

        assertEquals(Duration.ofMillis(2), duration)
        assertEquals(Instant.ofEpochMilli(start.toEpochMilli()), manual.startedAt)
        assertEquals(Instant.ofEpochMilli(completion.toEpochMilli()), manual.completedAt)
        assertEquals(Duration.ofMillis(2), manual.activeDuration)
    }

    @Test
    fun `live stopwatch and timer completion leaves reason null and subtracts pauses`() {
        val started = factory.startTimed(snapshot(), now.minusSeconds(100), now.minusSeconds(100), zone)
        val pausedOnce =
            ActivityExecutionTransitions.pause(
                started,
                ActivityExecutionPauseId("pause-1"),
                now.minusSeconds(80),
            )
        val resumedOnce = ActivityExecutionTransitions.resume(pausedOnce, now.minusSeconds(60))
        val pausedTwice =
            ActivityExecutionTransitions.pause(
                resumedOnce,
                ActivityExecutionPauseId("pause-2"),
                now.minusSeconds(30),
            )

        val completed = ActivityExecutionTransitions.complete(pausedTwice, now)
        val completedTimer =
            ActivityExecutionTransitions.complete(
                factory.startTimed(
                    snapshot(TimeTrackingMode.TIMER),
                    now.minusSeconds(10),
                    now.minusSeconds(10),
                    zone,
                ),
                now,
            )

        assertEquals(Duration.ofSeconds(50), completed.activeDuration)
        assertEquals(ActivityExecutionStatus.COMPLETED, completed.status)
        assertNull(completed.completionReason)
        assertNull(completedTimer.completionReason)
        assertEquals(now, completed.pauses.last().endedAt)
    }

    @Test
    fun `duration rejects open overlapping outside and reversed pauses`() {
        val start = now.minusSeconds(100)
        val pause = { id: String, from: Long, to: Long? ->
            ActivityExecutionPause(
                ActivityExecutionPauseId(id),
                now.minusSeconds(from),
                to?.let(now::minusSeconds),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionDurationCalculator.calculate(start, now, listOf(pause("open", 50, null)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionDurationCalculator.calculate(
                start,
                now,
                listOf(pause("a", 80, 40), pause("b", 60, 20)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionDurationCalculator.calculate(start, now, listOf(pause("outside", 110, 90)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionDurationCalculator.calculate(start, now, listOf(pause("reversed", 20, 30)))
        }
    }

    @Test
    fun `illegal transitions fail`() {
        val running = factory.startTimed(snapshot(), now, now, zone)

        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionTransitions.resume(running, now)
        }
        val paused = ActivityExecutionTransitions.pause(running, ActivityExecutionPauseId("pause"), now)
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionTransitions.pause(paused, ActivityExecutionPauseId("other"), now)
        }
    }

    @Test
    fun `value validator rejects foreign field mismatched type and foreign category option`() {
        val snapshot = snapshot()
        val execution = factory.startTimed(snapshot, now, now, zone)

        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionValidator.requireValid(
                execution.copy(values = listOf(TextExecutionValue(ActivitySnapshotFieldId("foreign"), "x"))),
                snapshot,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionValidator.requireValid(
                execution.copy(values = listOf(TextExecutionValue(ActivitySnapshotFieldId("number"), "x"))),
                snapshot,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionValidator.requireValid(
                execution.copy(
                    values =
                        listOf(
                            CategoryExecutionValue(
                                ActivitySnapshotFieldId("category"),
                                ActivitySnapshotCategoryOptionId("foreign"),
                            ),
                        ),
                ),
                snapshot,
            )
        }
    }

    private fun snapshot(
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        seriesId: StatisticsSeriesId? = StatisticsSeriesId("series"),
    ) = ActivityConfigSnapshot(
        id = ActivitySnapshotId("snapshot"),
        name = "Run",
        shortComment = null,
        timeTrackingMode = mode,
        timerTarget = if (mode == TimeTrackingMode.TIMER) Duration.ofMinutes(5) else null,
        sourceTemplateId = null,
        sourceRevision = null,
        statisticsSeriesId = seriesId,
        locallyModified = false,
        createdAt = now,
        fields =
            listOf(
                ActivitySnapshotField(
                    id = ActivitySnapshotFieldId("number"),
                    sourceFieldId = null,
                    position = 0,
                    nameAtCreation = "Number",
                    type = CustomFieldType.NUMBER,
                    defaultNumberScaled = 0,
                ),
                ActivitySnapshotField(
                    id = ActivitySnapshotFieldId("category"),
                    sourceFieldId = null,
                    position = 1,
                    nameAtCreation = "Category",
                    type = CustomFieldType.CATEGORY,
                    defaultCategoryOptionId = ActivitySnapshotCategoryOptionId("option"),
                    categoryOptions =
                        listOf(
                            ActivitySnapshotCategoryOption(
                                id = ActivitySnapshotCategoryOptionId("option"),
                                sourceOptionId = null,
                                position = 0,
                                labelAtCreation = "Option",
                            ),
                        ),
                ),
                ActivitySnapshotField(
                    id = ActivitySnapshotFieldId("text"),
                    sourceFieldId = null,
                    position = 2,
                    nameAtCreation = "Text",
                    type = CustomFieldType.TEXT,
                    defaultText = "",
                ),
            ),
    )
}
