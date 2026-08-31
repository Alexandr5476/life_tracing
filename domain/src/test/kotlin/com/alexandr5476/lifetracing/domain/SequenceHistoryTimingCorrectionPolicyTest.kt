package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class SequenceHistoryTimingCorrectionPolicyTest {
    @Test
    fun `accepts both terminal states and rejects live states`() {
        assertEquals(
            SequenceExecutionStatus.COMPLETED,
            correct(graph()).execution.status,
        )
        assertEquals(
            SequenceExecutionStatus.ENDED_EARLY,
            correct(graph(status = SequenceExecutionStatus.ENDED_EARLY)).execution.status,
        )
        listOf(SequenceExecutionStatus.RUNNING, SequenceExecutionStatus.PAUSED).forEach { status ->
            assertThrows(IllegalArgumentException::class.java) { correct(graph(status = status)) }
        }
    }

    @Test
    fun `rejects stale correction and root only edits retain facts but cannot exclude them`() {
        val graph = graph()
        assertThrows(IllegalArgumentException::class.java) {
            correct(graph, SequenceHistoryTimingCorrection(minute(19), endedAt = minute(21)))
        }

        val result =
            correct(
                graph,
                SequenceHistoryTimingCorrection(
                    minute(20).plusNanos(999_999),
                    endedAt = minute(21),
                ),
                minute(22),
            )
        assertEquals(graph.execution.occurrences, result.execution.occurrences)
        assertEquals(graph.execution.intervals, result.execution.intervals)
        assertEquals(graph.children.map(SequenceHistoryChildExecution::execution), result.children)
        assertThrows(IllegalArgumentException::class.java) {
            correct(graph, SequenceHistoryTimingCorrection(minute(20), startedAt = minute(11)))
        }
    }

    @Test
    fun `root start uses original historical zone and persistence precision`() {
        val zone = ZoneId.of("Europe/Berlin")
        val start = Instant.parse("2026-10-25T01:30:00Z")
        val graph = graph(start, start.plus(Duration.ofMinutes(10)), zone)
        val correctedStart = Instant.parse("2026-10-25T00:30:00.999999Z")

        val corrected =
            correct(
                graph,
                SequenceHistoryTimingCorrection(graph.execution.updatedAt, startedAt = correctedStart),
                graph.execution.updatedAt.plusSeconds(1),
            ).execution

        assertEquals(Instant.parse("2026-10-25T00:30:00.999Z"), corrected.startedAt)
        assertEquals(120, corrected.originalUtcOffsetMinutes)
        assertEquals("2026-10-25", corrected.primaryLocalDate.toString())
    }

    @Test
    fun `explicit final intervals recalculate caches with union and reject invalid graph facts`() {
        val graph = graph()
        val overlapping =
            listOf(
                interval("a", SequenceIntervalKind.ACTIVE_STEP, minute(10), minute(15), "a"),
                interval("b", SequenceIntervalKind.ACTIVE_STEP, minute(14), minute(20), "b"),
            )
        val result =
            correct(
                graph,
                SequenceHistoryTimingCorrection(graph.execution.updatedAt, finalIntervals = overlapping),
            ).execution
        assertEquals(Duration.ofMinutes(10), result.activeDuration)
        assertEquals(Duration.ZERO, result.pauseDuration)
        assertEquals(Duration.ofMinutes(10), result.wallDuration)

        listOf(
            listOf(interval("open", SequenceIntervalKind.ACTIVE_STEP, minute(10), null, "a")),
            listOf(interval("outside", SequenceIntervalKind.ACTIVE_STEP, minute(9), minute(10), "a")),
            listOf(interval("foreign", SequenceIntervalKind.ACTIVE_STEP, minute(10), minute(11), "foreign")),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                correct(graph, SequenceHistoryTimingCorrection(graph.execution.updatedAt, finalIntervals = invalid))
            }
        }
    }

    @Test
    fun `shortening one occurrence child and interval leaves later step fixed with an idle gap`() {
        val graph = graph(end = minute(30), middleAt = minute(20))
        val intervals =
            listOf(
                interval("a", SequenceIntervalKind.ACTIVE_STEP, minute(10), minute(18), "a"),
                interval("b", SequenceIntervalKind.ACTIVE_STEP, minute(20), minute(30), "b"),
            )
        val result =
            correct(
                graph,
                SequenceHistoryTimingCorrection(
                    expectedUpdatedAt = graph.execution.updatedAt,
                    occurrenceTimings =
                        listOf(
                            SequenceOccurrenceTimingCorrection(SequenceOccurrenceId("a"), minute(10), minute(18)),
                        ),
                    finalIntervals = intervals,
                    childTimings =
                        listOf(
                            SequenceChildTimingCorrection(
                                ActivityExecutionId("child-a"),
                                ActivityHistoryTimeCorrection.Timed(minute(10), minute(18)),
                            ),
                        ),
                ),
            )

        assertEquals(
            minute(20),
            result.execution.occurrences
                .single { it.id.value == "b" }
                .enteredAt,
        )
        assertEquals(minute(20), result.children.single { it.id.value == "child-b" }.startedAt)
        val originalA = graph.execution.occurrences.single { it.id.value == "a" }
        val correctedA = result.execution.occurrences.single { it.id.value == "a" }
        assertEquals(originalA.copy(enteredAt = minute(10), completedAt = minute(18)), correctedA)
        assertEquals(Duration.ofMinutes(18), result.execution.activeDuration)
        assertEquals(Duration.ofMinutes(2), result.execution.pauseDuration)
    }

    @Test
    fun `child correction enforces parent snapshot state and time shape`() {
        val graph = graph()
        val child = graph.children.single { it.execution.id.value == "child-a" }
        listOf(
            child.copy(execution = child.execution.copy(sequenceExecutionId = SequenceExecutionId("other"))),
            child.copy(execution = child.execution.copy(sequenceOccurrenceId = SequenceOccurrenceId("other"))),
            child.copy(execution = child.execution.copy(snapshotId = ActivitySnapshotId("other"))),
            child.copy(
                execution =
                    child.execution.copy(
                        status = ActivityExecutionStatus.RUNNING,
                        completedAt = null,
                        activeDuration = null,
                    ),
            ),
            child.copy(execution = child.execution.copy(deletedAt = minute(19))),
        ).forEach { invalidChild ->
            assertThrows(IllegalArgumentException::class.java) {
                correct(graph.copy(children = listOf(invalidChild)), childCorrection(graph))
            }
        }
        val noLive = noLiveGraph()
        assertThrows(IllegalArgumentException::class.java) {
            correct(
                noLive,
                childCorrection(noLive, ActivityHistoryTimeCorrection.Timed(minute(10), minute(11))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            correct(graph, childCorrection(graph, ActivityHistoryTimeCorrection.NoLive(minute(11))))
        }
        val correctedNoLive =
            correct(noLive, childCorrection(noLive, ActivityHistoryTimeCorrection.NoLive(minute(14))))
                .children
                .single()
        assertNull(correctedNoLive.startedAt)
        assertNull(correctedNoLive.activeDuration)
        assertEquals(emptyList<ActivityExecutionPause>(), correctedNoLive.pauses)
    }

    @Test
    fun `corrected identities provenance values and mutation time remain stable`() {
        val graph = graph()
        val result =
            correct(
                graph,
                SequenceHistoryTimingCorrection(
                    graph.execution.updatedAt,
                    endedAt = minute(19),
                    occurrenceTimings =
                        listOf(SequenceOccurrenceTimingCorrection(SequenceOccurrenceId("b"), minute(15), minute(19))),
                    finalIntervals =
                        listOf(
                            interval("a", SequenceIntervalKind.ACTIVE_STEP, minute(10), minute(15), "a"),
                            interval("b", SequenceIntervalKind.ACTIVE_STEP, minute(15), minute(19), "b"),
                        ),
                    childTimings =
                        listOf(
                            SequenceChildTimingCorrection(
                                ActivityExecutionId("child-a"),
                                ActivityHistoryTimeCorrection.Timed(minute(10), minute(19)),
                            ),
                            SequenceChildTimingCorrection(
                                ActivityExecutionId("child-b"),
                                ActivityHistoryTimeCorrection.Timed(minute(15), minute(19)),
                            ),
                        ),
                ),
                minute(31),
            )

        assertEquals(graph.execution.id, result.execution.id)
        assertEquals(graph.execution.snapshotId, result.execution.snapshotId)
        assertEquals(graph.execution.statisticsSeriesId, result.execution.statisticsSeriesId)
        assertEquals(graph.execution.planEntryId, result.execution.planEntryId)
        assertEquals(minute(19), result.execution.endedAt)
        assertEquals(minute(31), result.execution.updatedAt)
        assertEquals(
            graph.execution.occurrences.map(RuntimeOccurrence::id),
            result.execution.occurrences.map(RuntimeOccurrence::id),
        )
        val correctedChild = result.children.single { it.id.value == "child-a" }
        val originalChild = graph.children.single { it.execution.id == correctedChild.id }.execution
        assertEquals(
            originalChild.copy(
                startedAt = correctedChild.startedAt,
                completedAt = correctedChild.completedAt,
                activeDuration = correctedChild.activeDuration,
                originalUtcOffsetMinutes = correctedChild.originalUtcOffsetMinutes,
                primaryLocalDate = correctedChild.primaryLocalDate,
                updatedAt = correctedChild.updatedAt,
            ),
            correctedChild,
        )
        assertEquals(minute(31), correctedChild.updatedAt)
    }

    private fun correct(
        graph: Graph,
        correction: SequenceHistoryTimingCorrection = SequenceHistoryTimingCorrection(graph.execution.updatedAt),
        correctedAt: Instant = graph.execution.updatedAt.plusSeconds(1),
    ) = SequenceHistoryTimingCorrectionPolicy.correct(
        graph.execution,
        graph.snapshot,
        graph.children,
        correction,
        correctedAt,
    )

    private fun childCorrection(
        graph: Graph,
        time: ActivityHistoryTimeCorrection = ActivityHistoryTimeCorrection.Timed(minute(10), minute(19)),
    ) = SequenceHistoryTimingCorrection(
        graph.execution.updatedAt,
        childTimings = listOf(SequenceChildTimingCorrection(ActivityExecutionId("child-a"), time)),
    )

    private fun graph(
        start: Instant = minute(10),
        end: Instant = minute(20),
        zone: ZoneId = ZoneId.of("UTC"),
        status: SequenceExecutionStatus = SequenceExecutionStatus.COMPLETED,
        middleAt: Instant = start.plus(Duration.ofMinutes(5)),
    ): Graph {
        val a = activitySnapshot("activity-a")
        val b = activitySnapshot("activity-b")
        val snapshot = sequenceSnapshot(a.id, b.id)
        val aEnd = middleAt
        val childA = child("child-a", a, "a", start, aEnd, zone)
        val childB = child("child-b", b, "b", aEnd, end, zone)
        val occurrences =
            listOf(
                occurrence("a", a.id, 0, start, aEnd),
                occurrence("b", b.id, 1, aEnd, end),
            )
        val intervals =
            listOf(
                interval("a", SequenceIntervalKind.ACTIVE_STEP, start, aEnd, "a"),
                interval("b", SequenceIntervalKind.ACTIVE_STEP, aEnd, end, "b"),
            )
        val durations = SequenceTimelineCalculator.calculate(start, end, intervals)
        return Graph(
            SequenceExecution(
                id = SequenceExecutionId("sequence"),
                snapshotId = snapshot.id,
                statisticsSeriesId = snapshot.statisticsSeriesId,
                status = status,
                startedAt = start,
                endedAt = end,
                activeDuration = durations.active,
                pauseDuration = durations.pause,
                wallDuration = durations.wall,
                originalZoneId = zone,
                originalUtcOffsetMinutes = start.atZone(zone).offset.totalSeconds / 60,
                primaryLocalDate = start.atZone(zone).toLocalDate(),
                currentOccurrenceId = null,
                createdAt = start,
                updatedAt = end,
                occurrences = occurrences,
                intervals = intervals,
                planEntryId = PlanEntryId("plan"),
            ),
            snapshot,
            listOf(SequenceHistoryChildExecution(childA, a), SequenceHistoryChildExecution(childB, b)),
        )
    }

    private fun noLiveGraph(): Graph {
        val snapshot = activitySnapshot("activity-a", TimeTrackingMode.NO_LIVE_TRACKING)
        val graph = graph()
        val sequenceSnapshot = sequenceSnapshot(snapshot.id, ActivitySnapshotId("activity-b"))
        val noLive =
            graph.children.first().execution.copy(
                snapshotId = snapshot.id,
                startedAt = null,
                completedAt = minute(15),
                activeDuration = null,
                originalUtcOffsetMinutes = 0,
                primaryLocalDate = minute(15).atZone(ZoneId.of("UTC")).toLocalDate(),
                pauses = emptyList(),
            )
        return graph.copy(
            execution =
                graph.execution.copy(
                    occurrences =
                        graph.execution.occurrences.map {
                            if (it.id.value ==
                                "a"
                            ) {
                                it.copy(activitySnapshotId = snapshot.id)
                            } else {
                                it
                            }
                        },
                ),
            snapshot = sequenceSnapshot,
            children = listOf(SequenceHistoryChildExecution(noLive, snapshot)),
        )
    }

    private fun activitySnapshot(
        id: String,
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
    ) = ActivityConfigSnapshot(
        ActivitySnapshotId(id),
        id,
        null,
        mode,
        null,
        null,
        null,
        null,
        false,
        Instant.EPOCH,
    )

    private fun sequenceSnapshot(
        a: ActivitySnapshotId,
        b: ActivitySnapshotId,
    ) = SequenceConfigSnapshot(
        SequenceSnapshotId("snapshot"),
        "Sequence",
        null,
        null,
        null,
        StatisticsSeriesId("series"),
        Instant.EPOCH,
        SequenceSnapshotSettings(
            true,
            Duration.ZERO,
            Duration.ZERO,
            true,
            true,
            false,
            true,
            true,
            NoLiveTimeAccounting.ACTIVE,
        ),
        emptyList(),
        listOf(
            SequenceSnapshotActivityStep(SequenceSnapshotNodeId("step-a"), 0, a),
            SequenceSnapshotActivityStep(SequenceSnapshotNodeId("step-b"), 1, b),
        ),
    )

    private fun child(
        id: String,
        snapshot: ActivityConfigSnapshot,
        occurrenceId: String,
        start: Instant,
        end: Instant,
        zone: ZoneId,
    ) = ActivityExecution(
        ActivityExecutionId(id),
        snapshot.id,
        ActivityExecutionContext.SEQUENCE_CHILD,
        snapshot.statisticsSeriesId,
        ActivityExecutionStatus.COMPLETED,
        start,
        end,
        ActivityExecutionDurationCalculator.calculate(start, end, emptyList()),
        zone,
        start.atZone(zone).offset.totalSeconds / 60,
        start.atZone(zone).toLocalDate(),
        null,
        null,
        Instant.EPOCH,
        end,
        SequenceExecutionId("sequence"),
        SequenceOccurrenceId(occurrenceId),
        null,
    )

    private fun occurrence(
        id: String,
        snapshotId: ActivitySnapshotId,
        position: Int,
        start: Instant,
        end: Instant,
    ) = RuntimeOccurrence(
        SequenceOccurrenceId(id),
        SequenceSnapshotNodeId("step-$id"),
        snapshotId,
        position,
        null,
        null,
        RuntimeOccurrenceStatus.COMPLETED,
        start,
        end,
        OccurrenceCompletionReason.MANUAL_FINISH,
        false,
        false,
    )

    private fun interval(
        id: String,
        kind: SequenceIntervalKind,
        start: Instant,
        end: Instant?,
        occurrenceId: String,
    ) = SequenceInterval(SequenceIntervalId(id), kind, start, end, SequenceOccurrenceId(occurrenceId))

    private fun minute(value: Long): Instant = Instant.ofEpochSecond(value * 60)

    private data class Graph(
        val execution: SequenceExecution,
        val snapshot: SequenceConfigSnapshot,
        val children: List<SequenceHistoryChildExecution>,
    )
}
