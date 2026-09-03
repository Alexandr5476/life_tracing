@file:Suppress("LargeClass", "LongParameterList", "LongMethod")

package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class SequenceHistoryStructuralRemovalPolicyTest {
    @Test
    fun `leave gap hides the retained occurrence and deletes only its child and intervals`() {
        val graph = graph()
        val target = graph.occurrence("b")
        val targetChild = graph.child("b")

        val result = remove(graph, leaveGap(graph, "b"), minute(50).plusNanos(999_999))
        val removed = result.execution.occurrences.single { it.id == target.id }
        val deletedChild = result.children.single { it.id == targetChild.id }

        assertEquals(
            target.copy(status = RuntimeOccurrenceStatus.DELETED_EXECUTION, isDeletedFromHistory = true),
            removed,
        )
        assertEquals(targetChild.copy(deletedAt = minute(50), updatedAt = minute(50)), deletedChild)
        assertEquals(
            graph.execution.occurrences.filterNot { it.id == target.id },
            result.execution.occurrences.filterNot {
                it.id ==
                    target.id
            },
        )
        assertEquals(graph.execution.intervals.filter { it.occurrenceId != target.id }, result.execution.intervals)
        assertEquals(graph.execution.startedAt, result.execution.startedAt)
        assertEquals(graph.execution.endedAt, result.execution.endedAt)
        assertEquals(Duration.ofMinutes(20), result.execution.activeDuration)
        assertEquals(Duration.ofMinutes(10), result.execution.pauseDuration)
        assertEquals(Duration.ofMinutes(30), result.execution.wallDuration)
        assertEquals(graph.execution.values, result.execution.values)
        assertEquals(graph.execution.planEntryId, result.execution.planEntryId)
        assertEquals(graph.execution.snapshotId, result.execution.snapshotId)
        assertEquals(graph.execution.statisticsSeriesId, result.execution.statisticsSeriesId)
    }

    @Test
    fun `structural removal accepts ended early and rejects live roots`() {
        val graph = graph()
        assertEquals(
            SequenceExecutionStatus.ENDED_EARLY,
            remove(
                graph.copy(execution = graph.execution.copy(status = SequenceExecutionStatus.ENDED_EARLY)),
                leaveGap(graph, "b"),
            ).execution.status,
        )
        listOf(SequenceExecutionStatus.RUNNING, SequenceExecutionStatus.PAUSED).forEach { status ->
            val live =
                graph.copy(
                    execution =
                        graph.execution.copy(
                            status = status,
                            endedAt = null,
                            activeDuration = null,
                            pauseDuration = null,
                            wallDuration = null,
                        ),
                )
            assertThrows(IllegalArgumentException::class.java) { remove(live, leaveGap(graph, "b")) }
        }
    }

    @Test
    fun `leave gap on an S3 tombstone preserves its original child deletion mutation`() {
        val graph = graph()
        val tombstone =
            SequenceChildHistoryDeletionPolicy.delete(
                graph.execution,
                graph.snapshot,
                graph.children,
                SequenceChildHistoryDeletionCommand(
                    graph.execution.updatedAt,
                    graph.occurrence("b").id,
                    graph.child("b").id,
                ),
                minute(45),
            )
        val tombstoneGraph =
            graph.copy(
                execution = tombstone.execution,
                children =
                    graph.children.map {
                        if (it.execution.id ==
                            tombstone.child.id
                        ) {
                            it.copy(execution = tombstone.child)
                        } else {
                            it
                        }
                    },
            )

        val result = remove(tombstoneGraph, leaveGap(tombstoneGraph, "b"), minute(50))

        assertEquals(tombstone.child, result.children.single { it.id == tombstone.child.id })
        assertEquals(minute(50), result.execution.updatedAt)
        assertTrue(
            result.execution.occurrences
                .single { it.id.value == "b" }
                .isDeletedFromHistory,
        )
    }

    @Test
    fun `leave gap removes no-live parent accounting without inventing child duration`() {
        listOf(SequenceIntervalKind.ACTIVE_STEP, SequenceIntervalKind.STEP_PAUSE).forEach { kind ->
            val graph = noLiveGraph("b", kind)
            val result = remove(graph, leaveGap(graph, "b"))
            val child = result.children.single { it.id.value == "child-b" }

            assertNull(child.startedAt)
            assertNull(child.activeDuration)
            assertTrue(child.pauses.isEmpty())
            assertEquals(
                if (kind ==
                    SequenceIntervalKind.ACTIVE_STEP
                ) {
                    Duration.ofMinutes(20)
                } else {
                    Duration.ofMinutes(20)
                },
                result.execution.activeDuration,
            )
            assertEquals(Duration.ofMinutes(10), result.execution.pauseDuration)
        }
    }

    @Test
    fun `close gap translates the complete later suffix and shortens root caches`() {
        val graph = graph()
        val result = remove(graph, closeGap(graph, "b"))
        val oldC = graph.occurrence("c")
        val newC = result.execution.occurrences.single { it.id == oldC.id }

        assertEquals(graph.occurrence("a"), result.execution.occurrences.single { it.id.value == "a" })
        assertEquals(oldC.copy(enteredAt = minute(20), completedAt = minute(30)), newC)
        assertEquals(minute(30), result.execution.endedAt)
        assertEquals(Duration.ofMinutes(20), result.execution.activeDuration)
        assertEquals(Duration.ZERO, result.execution.pauseDuration)
        assertEquals(Duration.ofMinutes(20), result.execution.wallDuration)
        assertEquals(
            graph.child("c").copy(
                startedAt = minute(20),
                completedAt = minute(30),
                updatedAt = minute(50),
                primaryLocalDate = minute(20).atZone(ZoneId.of("UTC")).toLocalDate(),
            ),
            result.children.single { it.id.value == "child-c" },
        )
    }

    @Test
    fun `close gap can remove the last performed occurrence without moving earlier facts`() {
        val graph = graph()
        val command =
            SequenceHistoryStructuralRemovalCommand(
                graph.execution.updatedAt,
                graph.occurrence("c").id,
                graph.child("c").id,
                SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
                minute(30),
                graph.execution.intervals.filter { it.occurrenceId?.value != "c" },
            )

        val result = remove(graph, command)

        assertEquals(graph.occurrence("a"), result.execution.occurrences.single { it.id.value == "a" })
        assertEquals(graph.occurrence("b"), result.execution.occurrences.single { it.id.value == "b" })
        assertEquals(minute(30), result.execution.endedAt)
        assertEquals(Duration.ofMinutes(20), result.execution.wallDuration)
    }

    @Test
    fun `close gap shifts child pauses only through explicit preserved pause identities`() {
        val graph = pausedLaterGraph()
        val command = closeGap(graph, "b", pausedChild = true)
        val result = remove(graph, command)
        val oldChild = graph.child("c")
        val child = result.children.single { it.id == oldChild.id }

        assertEquals(oldChild.pauses.single().id, child.pauses.single().id)
        assertEquals(minute(22), child.pauses.single().startedAt)
        assertEquals(minute(24), child.pauses.single().endedAt)
        assertEquals(oldChild.activeDuration, child.activeDuration)

        assertThrows(IllegalArgumentException::class.java) {
            remove(
                graph,
                command.copy(
                    childTimings = command.childTimings.map { it.copy(pauses = graph.child("c").pauses) },
                ),
            )
        }
    }

    @Test
    fun `close gap shifts no-live children without duration and derives timezone attribution`() {
        val graph = noLiveGraph("c", SequenceIntervalKind.ACTIVE_STEP)
        val command = closeGap(graph, "b", laterNoLive = true)
        val result = remove(graph, command)
        val child = result.children.single { it.id.value == "child-c" }

        assertNull(child.startedAt)
        assertNull(child.activeDuration)
        assertTrue(child.pauses.isEmpty())
        assertEquals(minute(30), child.completedAt)
        assertEquals(minute(30).atZone(child.originalZoneId).toLocalDate(), child.primaryLocalDate)
    }

    @Test
    fun `close gap recomputes a shifted child offset across DST`() {
        val start = Instant.parse("2026-10-24T23:30:00Z")
        val graph = graph(start, Duration.ofHours(1), ZoneId.of("Europe/Berlin"))
        val result = remove(graph, closeGap(graph, "b"), graph.execution.updatedAt.plusSeconds(1))
        val child = result.children.single { it.id.value == "child-c" }

        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), child.startedAt)
        assertEquals(120, child.originalUtcOffsetMinutes)
        assertEquals(child.startedAt!!.atZone(child.originalZoneId).toLocalDate(), child.primaryLocalDate)

        val dateGraph =
            graph(
                Instant.parse("2026-01-01T08:30:00Z"),
                Duration.ofHours(1),
                ZoneId.of("Pacific/Kiritimati"),
            )
        val dateChild =
            remove(dateGraph, closeGap(dateGraph, "b"), dateGraph.execution.updatedAt.plusSeconds(1))
                .children
                .single { it.id.value == "child-c" }
        assertEquals("2026-01-01", dateChild.primaryLocalDate.toString())
    }

    @Test
    fun `explicit coherent overlap is accepted but incomplete suffix movement is rejected`() {
        val graph = graph().withC(start = minute(28), end = minute(40))
        val command =
            closeGap(graph, "b").copy(
                finalEndedAt = minute(35),
                occurrenceTimings =
                    listOf(
                        SequenceOccurrenceTimingCorrection(graph.occurrence("c").id, minute(23), minute(35)),
                    ),
                finalIntervals =
                    listOf(
                        graph.execution.intervals.single { it.id.value == "a" },
                        interval("c", minute(23), minute(35), "c"),
                    ),
                childTimings =
                    listOf(
                        SequenceStructuralChildTimingCorrection(
                            graph.child("c").id,
                            ActivityHistoryTimeCorrection.Timed(minute(23), minute(35)),
                            emptyList(),
                        ),
                    ),
            )

        val result = remove(graph, command)
        assertEquals(Duration.ofMinutes(25), result.execution.wallDuration)
        assertThrows(IllegalArgumentException::class.java) {
            remove(graph, command.copy(occurrenceTimings = emptyList(), childTimings = emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            remove(graph, command.copy(childTimings = emptyList()))
        }
    }

    @Test
    fun `structural commands reject stale foreign invalid and non-advancing targets`() {
        val graph = graph()
        val valid = leaveGap(graph, "b")
        val invalidCommands =
            listOf(
                valid.copy(expectedUpdatedAt = minute(39)),
                valid.copy(occurrenceId = SequenceOccurrenceId("missing")),
                valid.copy(childExecutionId = ActivityExecutionId("missing")),
                valid.copy(occurrenceId = graph.occurrence("a").id),
            )
        invalidCommands.forEach { command ->
            assertThrows(IllegalArgumentException::class.java) { remove(graph, command) }
        }
        assertThrows(IllegalArgumentException::class.java) { remove(graph, valid, graph.execution.updatedAt) }

        val hidden = remove(graph, valid)
        val hiddenGraph = graph.copy(execution = hidden.execution, children = wrap(hidden.children, graph))
        assertThrows(
            IllegalArgumentException::class.java,
        ) { remove(hiddenGraph, leaveGap(hiddenGraph, "b"), minute(60)) }

        val skipped =
            graph.copy(
                execution =
                    graph.execution.copy(
                        occurrences =
                            graph.execution.occurrences.map {
                                if (it.id.value ==
                                    "b"
                                ) {
                                    it.copy(
                                        status = RuntimeOccurrenceStatus.SKIPPED,
                                        enteredAt = null,
                                        completedAt = null,
                                        completionReason = null,
                                    )
                                } else {
                                    it
                                }
                            },
                        intervals = graph.execution.intervals.filter { it.occurrenceId?.value != "b" },
                        activeDuration = Duration.ofMinutes(20),
                        pauseDuration = Duration.ofMinutes(10),
                    ),
                children = graph.children.filter { it.execution.sequenceOccurrenceId?.value != "b" },
            )
        val skippedCommand =
            leaveGap(graph, "b").copy(
                expectedUpdatedAt = skipped.execution.updatedAt,
                finalIntervals = skipped.execution.intervals,
            )
        assertThrows(IllegalArgumentException::class.java) { remove(skipped, skippedCommand) }
    }

    @Test
    fun `leave gap cannot move unrelated facts and close gap preserves interval shape`() {
        val graph = graph()
        val leave = leaveGap(graph, "b")
        assertThrows(IllegalArgumentException::class.java) {
            remove(
                graph,
                leave.copy(
                    occurrenceTimings =
                        listOf(
                            SequenceOccurrenceTimingCorrection(graph.occurrence("c").id, minute(29), minute(39)),
                        ),
                ),
            )
        }
        val close = closeGap(graph, "b")
        listOf(
            close.copy(
                finalIntervals =
                    close.finalIntervals.map {
                        if (it.id.value ==
                            "c"
                        ) {
                            it.copy(kind = SequenceIntervalKind.IMPLICIT_IDLE)
                        } else {
                            it
                        }
                    },
            ),
            close.copy(
                finalIntervals =
                    close.finalIntervals.map {
                        if (it.id.value ==
                            "c"
                        ) {
                            it.copy(id = SequenceIntervalId("new"))
                        } else {
                            it
                        }
                    },
            ),
            close.copy(
                finalIntervals =
                    close.finalIntervals.map {
                        if (it.id.value ==
                            "c"
                        ) {
                            it.copy(endedAt = minute(29))
                        } else {
                            it
                        }
                    },
            ),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { remove(graph, invalid) }
        }

        val ownerless =
            SequenceInterval(
                SequenceIntervalId("earlier-idle"),
                SequenceIntervalKind.IMPLICIT_IDLE,
                minute(11),
                minute(12),
                null,
            )
        val graphWithOwnerless =
            graph.copy(
                execution =
                    graph.execution.copy(
                        intervals =
                            graph.execution.intervals + ownerless,
                    ),
            )
        val explicit = closeGap(graphWithOwnerless, "b")
        remove(graphWithOwnerless, explicit)
        assertThrows(IllegalArgumentException::class.java) {
            remove(
                graphWithOwnerless,
                explicit.copy(
                    finalIntervals =
                        explicit.finalIntervals.map {
                            if (it.id == ownerless.id) {
                                it.copy(startedAt = minute(1), endedAt = minute(2))
                            } else {
                                it
                            }
                        },
                ),
            )
        }
    }

    @Test
    fun `structural removal validates exact child owner and snapshot linkage`() {
        val graph = graph()
        val child = graph.children.single { it.execution.id.value == "child-b" }
        listOf(
            child.copy(execution = child.execution.copy(sequenceExecutionId = SequenceExecutionId("other"))),
            child.copy(execution = child.execution.copy(sequenceOccurrenceId = SequenceOccurrenceId("a"))),
            child.copy(execution = child.execution.copy(snapshotId = ActivitySnapshotId("other"))),
        ).forEach { invalidChild ->
            val invalid =
                graph.copy(
                    children =
                        graph.children.map {
                            if (it.execution.id ==
                                invalidChild.execution.id
                            ) {
                                invalidChild
                            } else {
                                it
                            }
                        },
                )
            assertThrows(IllegalArgumentException::class.java) { remove(invalid, leaveGap(graph, "b")) }
        }
    }

    @Test
    fun `subsequent root correction accepts hidden facts but cannot edit the hidden occurrence`() {
        val graph = graph()
        val removed = remove(graph, closeGap(graph, "b"))
        val removedGraph = graph.copy(execution = removed.execution, children = wrap(removed.children, graph))

        val corrected =
            SequenceHistoryTimingCorrectionPolicy.correct(
                removed.execution,
                graph.snapshot,
                removedGraph.children,
                SequenceHistoryTimingCorrection(removed.execution.updatedAt, endedAt = minute(31)),
                minute(60),
            )
        assertTrue(
            corrected.execution.occurrences
                .single { it.id.value == "b" }
                .isDeletedFromHistory,
        )
        assertThrows(IllegalArgumentException::class.java) {
            SequenceHistoryTimingCorrectionPolicy.correct(
                removed.execution,
                graph.snapshot,
                removedGraph.children,
                SequenceHistoryTimingCorrection(
                    removed.execution.updatedAt,
                    occurrenceTimings =
                        listOf(
                            SequenceOccurrenceTimingCorrection(graph.occurrence("b").id, minute(20), minute(30)),
                        ),
                ),
                minute(60),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceHistoryTimingCorrectionPolicy.correct(
                removed.execution,
                graph.snapshot,
                removedGraph.children,
                SequenceHistoryTimingCorrection(
                    removed.execution.updatedAt,
                    childTimings =
                        listOf(
                            SequenceChildTimingCorrection(
                                graph.child("b").id,
                                ActivityHistoryTimeCorrection.Timed(minute(20), minute(30)),
                            ),
                        ),
                ),
                minute(60),
            )
        }
        val targetChild = removedGraph.children.single { it.execution.sequenceOccurrenceId?.value == "b" }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceHistoricalTimingGraphValidator.requireValid(
                removed.execution,
                graph.snapshot,
                removedGraph.children.map {
                    if (it.execution.id == targetChild.execution.id) {
                        it.copy(execution = it.execution.copy(deletedAt = null))
                    } else {
                        it
                    }
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceHistoricalTimingGraphValidator.requireValid(
                removed.execution.copy(intervals = removed.execution.intervals + graph.execution.intervals[1]),
                graph.snapshot,
                removedGraph.children,
            )
        }
    }

    private fun remove(
        graph: Graph,
        command: SequenceHistoryStructuralRemovalCommand,
        at: Instant = minute(50),
    ) = SequenceHistoryStructuralRemovalPolicy.remove(graph.execution, graph.snapshot, graph.children, command, at)

    private fun leaveGap(
        graph: Graph,
        target: String,
    ) = SequenceHistoryStructuralRemovalCommand(
        graph.execution.updatedAt.plusNanos(999_999),
        graph.occurrence(target).id,
        graph.child(target).id,
        SequenceHistoryStructuralRemovalMode.LEAVE_GAP,
        requireNotNull(graph.execution.endedAt),
        graph.execution.intervals.filter { it.occurrenceId?.value != target },
    )

    private fun closeGap(
        graph: Graph,
        target: String,
        pausedChild: Boolean = false,
        laterNoLive: Boolean = false,
    ): SequenceHistoryStructuralRemovalCommand {
        require(target == "b")
        val shift = Duration.between(graph.occurrence("b").enteredAt, graph.occurrence("b").completedAt)
        val c = graph.occurrence("c")
        val child = graph.child("c")
        val newStart = requireNotNull(c.enteredAt).minus(shift)
        val newEnd = requireNotNull(c.completedAt).minus(shift)
        val childTime =
            if (laterNoLive) {
                ActivityHistoryTimeCorrection.NoLive(
                    newEnd,
                )
            } else {
                ActivityHistoryTimeCorrection.Timed(newStart, newEnd)
            }
        return SequenceHistoryStructuralRemovalCommand(
            graph.execution.updatedAt,
            graph.occurrence(target).id,
            graph.child(target).id,
            SequenceHistoryStructuralRemovalMode.CLOSE_GAP,
            requireNotNull(graph.execution.endedAt).minus(shift),
            graph.execution.intervals.filter { it.occurrenceId?.value != target }.map { interval ->
                if (interval.occurrenceId?.value == "c") {
                    interval.copy(startedAt = interval.startedAt.minus(shift), endedAt = interval.endedAt?.minus(shift))
                } else {
                    interval
                }
            },
            listOf(SequenceOccurrenceTimingCorrection(c.id, newStart, newEnd)),
            listOf(
                SequenceStructuralChildTimingCorrection(
                    child.id,
                    childTime,
                    if (pausedChild) {
                        child.pauses.map {
                            it.copy(startedAt = it.startedAt.minus(shift), endedAt = it.endedAt?.minus(shift))
                        }
                    } else {
                        emptyList()
                    },
                ),
            ),
        )
    }

    private fun graph(
        start: Instant = minute(10),
        step: Duration = Duration.ofMinutes(10),
        zone: ZoneId = ZoneId.of("UTC"),
    ): Graph {
        val snapshots = listOf("a", "b", "c").associateWith { activitySnapshot(it) }
        val sequenceSnapshot = sequenceSnapshot(snapshots.values.toList())
        val occurrences =
            listOf("a", "b", "c").mapIndexed { index, id ->
                occurrence(
                    id,
                    snapshots.getValue(id).id,
                    index,
                    start.plus(step.multipliedBy(index.toLong())),
                    start.plus(
                        step.multipliedBy(
                            (
                                index +
                                    1
                            ).toLong(),
                        ),
                    ),
                )
            }
        val intervals =
            occurrences.map {
                interval(it.id.value, requireNotNull(it.enteredAt), requireNotNull(it.completedAt), it.id.value)
            }
        val durations = SequenceTimelineCalculator.calculate(start, start.plus(step.multipliedBy(3)), intervals)
        val execution =
            SequenceExecution(
                SequenceExecutionId("sequence"),
                sequenceSnapshot.id,
                sequenceSnapshot.statisticsSeriesId,
                SequenceExecutionStatus.COMPLETED,
                start,
                start.plus(step.multipliedBy(3)),
                durations.active,
                durations.pause,
                durations.wall,
                zone,
                start.atZone(zone).offset.totalSeconds / 60,
                start.atZone(zone).toLocalDate(),
                null,
                start,
                start.plus(step.multipliedBy(3)),
                occurrences,
                intervals,
                listOf(TextSequenceExecutionValue(SequenceSnapshotFieldId("field"), "kept")),
                PlanEntryId("plan"),
            )
        val children =
            occurrences.map { occurrence ->
                val snapshot = snapshots.getValue(occurrence.id.value)
                SequenceHistoryChildExecution(
                    child(
                        "child-${occurrence.id.value}",
                        snapshot,
                        occurrence.id.value,
                        requireNotNull(occurrence.enteredAt),
                        requireNotNull(occurrence.completedAt),
                        zone,
                    ),
                    snapshot,
                )
            }
        return Graph(execution, sequenceSnapshot, children)
    }

    private fun pausedLaterGraph(): Graph {
        val graph = graph()
        val pause = ActivityExecutionPause(ActivityExecutionPauseId("pause-c"), minute(32), minute(34))
        val child = graph.child("c").copy(pauses = listOf(pause), activeDuration = Duration.ofMinutes(8))
        val intervals =
            graph.execution.intervals.filter { it.occurrenceId?.value != "c" } +
                interval("c-1", minute(30), minute(32), "c") +
                interval("c-2", minute(34), minute(40), "c")
        val durations = SequenceTimelineCalculator.calculate(minute(10), minute(40), intervals)
        return graph.copy(
            execution =
                graph.execution.copy(
                    intervals = intervals,
                    activeDuration = durations.active,
                    pauseDuration = durations.pause,
                ),
            children = graph.children.map { if (it.execution.id == child.id) it.copy(execution = child) else it },
        )
    }

    private fun noLiveGraph(
        target: String,
        kind: SequenceIntervalKind,
    ): Graph {
        val graph = graph()
        val snapshot = activitySnapshot(target, TimeTrackingMode.NO_LIVE_TRACKING)
        val child =
            graph.child(target).copy(
                snapshotId = snapshot.id,
                startedAt = null,
                activeDuration = null,
                originalUtcOffsetMinutes =
                    requireNotNull(
                        graph.child(target).completedAt,
                    ).atZone(graph.child(target).originalZoneId).offset.totalSeconds /
                        60,
                primaryLocalDate =
                    requireNotNull(
                        graph.child(target).completedAt,
                    ).atZone(graph.child(target).originalZoneId).toLocalDate(),
            )
        val snapshots =
            graph.snapshot.nodes.filterIsInstance<SequenceSnapshotActivityStep>().map { node ->
                if (node.id.value ==
                    "step-$target"
                ) {
                    snapshot
                } else {
                    graph.children.single { it.snapshot.id == node.activitySnapshotId }.snapshot
                }
            }
        val sequenceSnapshot =
            sequenceSnapshot(
                snapshots,
                if (kind ==
                    SequenceIntervalKind.STEP_PAUSE
                ) {
                    NoLiveTimeAccounting.PAUSE
                } else {
                    NoLiveTimeAccounting.ACTIVE
                },
            )
        val intervals =
            graph.execution.intervals.map {
                if (it.occurrenceId?.value ==
                    target
                ) {
                    it.copy(kind = kind)
                } else {
                    it
                }
            }
        val durations =
            SequenceTimelineCalculator.calculate(
                graph.execution.startedAt,
                requireNotNull(graph.execution.endedAt),
                intervals,
            )
        return graph.copy(
            snapshot = sequenceSnapshot,
            execution =
                graph.execution.copy(
                    snapshotId = sequenceSnapshot.id,
                    intervals = intervals,
                    activeDuration = durations.active,
                    pauseDuration = durations.pause,
                ),
            children =
                graph.children.map {
                    if (it.execution.id ==
                        child.id
                    ) {
                        SequenceHistoryChildExecution(child, snapshot)
                    } else {
                        it
                    }
                },
        )
    }

    private fun Graph.withC(
        start: Instant,
        end: Instant,
    ): Graph {
        val occurrence = occurrence("c").copy(enteredAt = start, completedAt = end)
        val child =
            child(
                "c",
            ).copy(startedAt = start, completedAt = end, activeDuration = Duration.between(start, end), updatedAt = end)
        val intervals =
            execution.intervals.map {
                if (it.occurrenceId?.value ==
                    "c"
                ) {
                    it.copy(startedAt = start, endedAt = end)
                } else {
                    it
                }
            }
        val durations =
            SequenceTimelineCalculator.calculate(
                execution.startedAt,
                requireNotNull(execution.endedAt),
                intervals,
            )
        return copy(
            execution =
                execution.copy(
                    occurrences =
                        execution.occurrences.map {
                            if (it.id.value ==
                                "c"
                            ) {
                                occurrence
                            } else {
                                it
                            }
                        },
                    intervals = intervals,
                    activeDuration = durations.active,
                    pauseDuration = durations.pause,
                ),
            children = children.map { if (it.execution.id.value == "child-c") it.copy(execution = child) else it },
        )
    }

    private fun activitySnapshot(
        id: String,
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
    ) = ActivityConfigSnapshot(
        ActivitySnapshotId("activity-$id"),
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
        activities: List<ActivityConfigSnapshot>,
        accounting: NoLiveTimeAccounting = NoLiveTimeAccounting.ACTIVE,
    ) = SequenceConfigSnapshot(
        SequenceSnapshotId("snapshot"),
        "Sequence",
        null,
        null,
        null,
        StatisticsSeriesId("series"),
        Instant.EPOCH,
        SequenceSnapshotSettings(true, Duration.ZERO, Duration.ZERO, true, true, false, true, true, accounting),
        listOf(
            SequenceSnapshotField(
                id = SequenceSnapshotFieldId("field"),
                sourceFieldId = null,
                position = 0,
                nameAtCreation = "Field",
                type = CustomFieldType.TEXT,
            ),
        ),
        activities.mapIndexed {
            index,
            activity,
            ->
            SequenceSnapshotActivityStep(
                SequenceSnapshotNodeId("step-${('a'.code + index).toChar()}"),
                index,
                activity.id,
            )
        },
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
        Duration.between(start, end),
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

    private fun interval(
        id: String,
        start: Instant,
        end: Instant,
        occurrenceId: String,
    ) = SequenceInterval(
        SequenceIntervalId(id),
        SequenceIntervalKind.ACTIVE_STEP,
        start,
        end,
        SequenceOccurrenceId(occurrenceId),
    )

    private fun wrap(
        executions: List<ActivityExecution>,
        graph: Graph,
    ) = executions.map { execution ->
        SequenceHistoryChildExecution(
            execution,
            graph.children
                .single {
                    it.execution.id ==
                        execution.id
                }.snapshot,
        )
    }

    private fun Graph.occurrence(id: String) = execution.occurrences.single { it.id.value == id }

    private fun Graph.child(id: String) = children.single { it.execution.sequenceOccurrenceId?.value == id }.execution

    private fun minute(value: Long): Instant = Instant.ofEpochSecond(value * 60)

    private data class Graph(
        val execution: SequenceExecution,
        val snapshot: SequenceConfigSnapshot,
        val children: List<SequenceHistoryChildExecution>,
    )
}
