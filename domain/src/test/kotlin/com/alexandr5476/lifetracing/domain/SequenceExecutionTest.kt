package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class SequenceExecutionTest {
    @Test
    fun materializerExpandsTopLevelAndRepeatInRuntimeOrderWithFreshOneBasedMetadata() {
        val original =
            snapshot(
                nodes =
                    listOf(
                        step("a", 0, "activity-a"),
                        repeat("repeat", 1, 3, step("b", 0, "activity-b"), step("c", 1, "activity-c")),
                        step("d", 2, "activity-d"),
                    ),
            )
        var id = 0

        val occurrences = RuntimeOccurrenceMaterializer { SequenceOccurrenceId("occ-${++id}") }.materialize(original)

        assertEquals(
            listOf("a", "b", "c", "b", "c", "b", "c", "d"),
            occurrences.map { it.sourceSequenceSnapshotNodeId?.value },
        )
        assertEquals((0..7).toList(), occurrences.map(RuntimeOccurrence::runtimePosition))
        assertEquals(listOf(null, 1, 1, 2, 2, 3, 3, null), occurrences.map(RuntimeOccurrence::repeatIteration))
        assertEquals(
            listOf(null, "repeat", "repeat", "repeat", "repeat", "repeat", "repeat", null),
            occurrences.map {
                it.repeatSourceSnapshotNodeId?.value
            },
        )
        assertEquals((1..8).map { "occ-$it" }, occurrences.map { it.id.value })
        assertEquals(
            listOf(
                "activity-a",
                "activity-b",
                "activity-c",
                "activity-b",
                "activity-c",
                "activity-b",
                "activity-c",
                "activity-d",
            ),
            occurrences.map {
                it.activitySnapshotId.value
            },
        )
        assertEquals(List(8) { RuntimeOccurrenceStatus.NOT_STARTED }, occurrences.map(RuntimeOccurrence::status))
        assertEquals(original, original.copy())
        assertNotSame(original.nodes, occurrences)
    }

    @Test
    fun materializerCoversOneStepMultipleStepsRepeatOneAndRejectsIdentityCollision() {
        val materializer = RuntimeOccurrenceMaterializer { SequenceOccurrenceId("same") }
        assertEquals(1, materializer.materialize(snapshot(nodes = listOf(step("one", 0)))).size)
        assertEquals(
            listOf("first", "second"),
            RuntimeOccurrenceMaterializer(sequenceIds())
                .materialize(
                    snapshot(nodes = listOf(step("second", 1), step("first", 0))),
                ).map { it.sourceSequenceSnapshotNodeId?.value },
        )
        assertEquals(
            listOf(1),
            RuntimeOccurrenceMaterializer(sequenceIds())
                .materialize(
                    snapshot(nodes = listOf(repeat("r", 0, 1, step("child", 0)))),
                ).map(RuntimeOccurrence::repeatIteration),
        )
        assertThrows(IllegalArgumentException::class.java) {
            materializer.materialize(snapshot(nodes = listOf(step("one", 0), step("two", 1))))
        }
    }

    @Test
    fun materializerGuardsRuntimePositionArithmeticBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeOccurrenceMaterializer(sequenceIds()).materialize(
                snapshot(
                    nodes =
                        listOf(
                            repeat(
                                "huge",
                                0,
                                Int.MAX_VALUE,
                                step("one", 0),
                                step("two", 1),
                            ),
                        ),
                ),
            )
        }
    }

    @Test
    fun timelineUsesActiveUnionAndPauseComplementAcrossOverlapAdjacencyAndUnsortedInput() {
        val start = instant(0)
        val durations =
            SequenceTimelineCalculator.calculate(
                start,
                instant(40),
                listOf(
                    interval("idle", SequenceIntervalKind.IMPLICIT_IDLE, 25, 30),
                    interval("second", SequenceIntervalKind.ACTIVE_STEP, 10, 20),
                    interval("overlap", SequenceIntervalKind.ACTIVE_STEP, 5, 15),
                    interval("adjacent", SequenceIntervalKind.ACTIVE_STEP, 20, 25),
                    interval("zero", SequenceIntervalKind.ACTIVE_STEP, 30, 30),
                ),
            )

        assertEquals(Duration.ofMillis(20), durations.active)
        assertEquals(Duration.ofMillis(20), durations.pause)
        assertEquals(Duration.ofMillis(40), durations.wall)
    }

    @Test
    fun timelineSupportsAllPauseNoPauseAndRejectsInvalidBoundsOrOpenIntervals() {
        assertEquals(
            SequenceTimelineDurations(Duration.ZERO, Duration.ofMillis(10), Duration.ofMillis(10)),
            SequenceTimelineCalculator.calculate(instant(0), instant(10), emptyList()),
        )
        assertEquals(
            SequenceTimelineDurations(Duration.ofMillis(10), Duration.ZERO, Duration.ofMillis(10)),
            SequenceTimelineCalculator.calculate(
                instant(0),
                instant(10),
                listOf(interval("active", SequenceIntervalKind.ACTIVE_STEP, 0, 10)),
            ),
        )
        listOf(
            { SequenceTimelineCalculator.calculate(instant(10), instant(0), emptyList()) },
            {
                SequenceTimelineCalculator.calculate(
                    instant(0),
                    instant(10),
                    listOf(interval("outside", SequenceIntervalKind.ACTIVE_STEP, 9, 11)),
                )
            },
            {
                SequenceTimelineCalculator.calculate(
                    instant(0),
                    instant(10),
                    listOf(interval("reversed", SequenceIntervalKind.ACTIVE_STEP, 8, 7)),
                )
            },
            {
                SequenceTimelineCalculator.calculate(
                    instant(0),
                    instant(10),
                    listOf(interval("open", SequenceIntervalKind.ACTIVE_STEP, 1, null)),
                )
            },
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                invalid()
                Unit
            }
        }
    }

    @Test
    fun timelineHandlesNestedDisjointRangesAndEveryNonActiveClassification() {
        val durations =
            SequenceTimelineCalculator.calculate(
                instant(0),
                instant(100),
                listOf(
                    interval("active-first", SequenceIntervalKind.ACTIVE_STEP, 0, 10),
                    interval("active-nested", SequenceIntervalKind.ACTIVE_STEP, 2, 5),
                    interval("explicit", SequenceIntervalKind.EXPLICIT_PAUSE, 10, 12),
                    interval("idle", SequenceIntervalKind.IMPLICIT_IDLE, 12, 14),
                    interval("transition", SequenceIntervalKind.TRANSITION_COUNTDOWN, 14, 16),
                    interval("step-pause", SequenceIntervalKind.STEP_PAUSE, 16, 20, "occ"),
                    interval("active-second", SequenceIntervalKind.ACTIVE_STEP, 20, 30),
                ),
            )

        assertEquals(Duration.ofMillis(20), durations.active)
        assertEquals(Duration.ofMillis(80), durations.pause)
        assertEquals(durations.wall, durations.active + durations.pause)
    }

    @Test
    fun timelineSupportsZeroWallAndRejectsOverflowingMillisecondArithmetic() {
        assertEquals(
            SequenceTimelineDurations(Duration.ZERO, Duration.ZERO, Duration.ZERO),
            SequenceTimelineCalculator.calculate(instant(5), instant(5), emptyList()),
        )
        assertThrows(ArithmeticException::class.java) {
            SequenceTimelineCalculator.calculate(
                Instant.ofEpochMilli(Long.MIN_VALUE),
                Instant.ofEpochMilli(Long.MAX_VALUE),
                emptyList(),
            )
        }
    }

    @Test
    fun factoryCopiesStatisticsTimezoneAndMaterializesAllTypedDefaults() {
        val option = SequenceSnapshotCategoryOption(SequenceSnapshotCategoryOptionId("option"), null, 0, "Option")
        val fields =
            listOf(
                field("number", CustomFieldType.NUMBER, number = 12_000),
                field("category", CustomFieldType.CATEGORY, option = option),
                field("text", CustomFieldType.TEXT, text = "note"),
                field("missing", CustomFieldType.TEXT),
            )
        val snapshot = snapshot(fields = fields, nodes = listOf(step("step", 0)))
        val start = Instant.parse("2025-10-26T00:30:00.123456Z")

        val execution =
            SequenceExecutionFactory(
                { SequenceExecutionId("execution") },
                RuntimeOccurrenceMaterializer(sequenceIds()),
            ).start(snapshot, start, start.plusSeconds(1), ZoneId.of("Europe/Berlin"))

        assertEquals(snapshot.statisticsSeriesId, execution.statisticsSeriesId)
        assertEquals(Instant.parse("2025-10-26T00:30:00.123Z"), execution.startedAt)
        assertEquals("2025-10-26", execution.primaryLocalDate.toString())
        assertEquals(120, execution.originalUtcOffsetMinutes)
        assertEquals(3, execution.values.size)
        assertEquals(12_000, (execution.values[0] as NumberSequenceExecutionValue).scaledValue)
        assertEquals(
            SequenceSnapshotCategoryOptionId("option"),
            (execution.values[1] as CategorySequenceExecutionValue).optionId,
        )
        assertEquals("note", (execution.values[2] as TextSequenceExecutionValue).value)
    }

    @Test
    fun rootValidatorAcceptsEveryStatusShapeAndChecksCompletedCaches() {
        val snapshot = snapshot(nodes = listOf(step("step", 0)))
        val live = running(snapshot)
        assertDoesNotThrow { SequenceExecutionValidator.requireValid(live, snapshot) }
        assertDoesNotThrow {
            SequenceExecutionValidator.requireValid(live.copy(status = SequenceExecutionStatus.PAUSED), snapshot)
        }
        val active = interval("active", SequenceIntervalKind.ACTIVE_STEP, 0, 60, "occ")
        val completed =
            live.copy(
                status = SequenceExecutionStatus.COMPLETED,
                endedAt = instant(100),
                activeDuration = Duration.ofMillis(60),
                pauseDuration = Duration.ofMillis(40),
                wallDuration = Duration.ofMillis(100),
                intervals = listOf(active),
            )
        assertDoesNotThrow { SequenceExecutionValidator.requireValid(completed, snapshot) }
        assertDoesNotThrow {
            SequenceExecutionValidator.requireValid(
                completed.copy(status = SequenceExecutionStatus.ENDED_EARLY),
                snapshot,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceExecutionValidator.requireValid(completed.copy(activeDuration = Duration.ofMillis(59)), snapshot)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceExecutionValidator.requireValid(live.copy(endedAt = instant(100)), snapshot)
        }
    }

    @Test
    fun executionValidatorRejectsStructurallyInvalidSnapshot() {
        val snapshot = snapshot(nodes = listOf(step("first", 0), step("second", 0)))

        assertThrows(IllegalArgumentException::class.java) {
            SequenceExecutionValidator.requireValid(running(snapshot), snapshot)
        }
    }

    @Test
    fun validatorRequiresCurrentPointerAndOccurrenceStateConsistency() {
        val snapshot = snapshot(nodes = listOf(step("step", 0)))
        val current = occurrence("occ", "step", RuntimeOccurrenceStatus.CURRENT, entered = 1)
        assertDoesNotThrow {
            SequenceExecutionValidator.requireValid(
                running(snapshot).copy(occurrences = listOf(current), currentOccurrenceId = current.id),
                snapshot,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceExecutionValidator.requireValid(running(snapshot).copy(occurrences = listOf(current)), snapshot)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceExecutionValidator.requireValid(
                running(snapshot).copy(
                    occurrences =
                        listOf(
                            current,
                            current.copy(id = SequenceOccurrenceId("other"), runtimePosition = 1),
                        ),
                    currentOccurrenceId = current.id,
                ),
                snapshot,
            )
        }
    }

    @Test
    fun validatorEnforcesSourceRepeatRuntimeAddedAndCategoryOwnership() {
        val option = SequenceSnapshotCategoryOption(SequenceSnapshotCategoryOptionId("option"), null, 0, "Option")
        val snapshot =
            snapshot(
                fields = listOf(field("category", CustomFieldType.CATEGORY, option = option)),
                nodes = listOf(repeat("repeat", 0, 2, step("child", 0, "activity-child"))),
            )
        val valid =
            occurrence("occ", "child").copy(
                activitySnapshotId = ActivitySnapshotId("activity-child"),
                repeatSourceSnapshotNodeId = SequenceSnapshotNodeId("repeat"),
                repeatIteration = 1,
            )
        assertDoesNotThrow {
            SequenceExecutionValidator.requireValid(running(snapshot).copy(occurrences = listOf(valid)), snapshot)
        }
        listOf(
            valid.copy(repeatIteration = 0),
            valid.copy(repeatIteration = 3),
            valid.copy(activitySnapshotId = ActivitySnapshotId("wrong")),
            valid.copy(isRuntimeAdded = true),
        ).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) {
                SequenceExecutionValidator.requireValid(running(snapshot).copy(occurrences = listOf(bad)), snapshot)
            }
        }
        val runtimeAdded =
            valid.copy(
                sourceSequenceSnapshotNodeId = null,
                repeatSourceSnapshotNodeId = null,
                repeatIteration = null,
                isRuntimeAdded = true,
            )
        assertDoesNotThrow {
            SequenceExecutionValidator.requireValid(
                running(snapshot).copy(occurrences = listOf(runtimeAdded)),
                snapshot,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceExecutionValidator.requireValid(
                running(snapshot).copy(
                    values =
                        listOf(
                            CategorySequenceExecutionValue(
                                SequenceSnapshotFieldId("category"),
                                SequenceSnapshotCategoryOptionId("wrong"),
                            ),
                        ),
                ),
                snapshot,
            )
        }
    }

    @Test
    fun skippedOccurrenceCannotPersistCompletionReasonButCompletedCan() {
        val snapshot = snapshot(nodes = listOf(step("step", 0)))
        val skipped = occurrence("occ", "step", RuntimeOccurrenceStatus.SKIPPED)
        assertDoesNotThrow {
            SequenceExecutionValidator.requireValid(running(snapshot).copy(occurrences = listOf(skipped)), snapshot)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SequenceExecutionValidator.requireValid(
                running(snapshot).copy(
                    occurrences = listOf(skipped.copy(completionReason = OccurrenceCompletionReason.JUMP)),
                ),
                snapshot,
            )
        }
        val completed =
            skipped.copy(
                status = RuntimeOccurrenceStatus.COMPLETED,
                enteredAt = instant(1),
                completedAt = instant(2),
                completionReason = OccurrenceCompletionReason.MANUAL_FINISH,
            )
        assertDoesNotThrow {
            SequenceExecutionValidator.requireValid(running(snapshot).copy(occurrences = listOf(completed)), snapshot)
        }
    }

    private fun running(snapshot: SequenceConfigSnapshot) =
        SequenceExecution(
            SequenceExecutionId("execution"),
            snapshot.id,
            snapshot.statisticsSeriesId,
            SequenceExecutionStatus.RUNNING,
            instant(0),
            null,
            null,
            null,
            null,
            ZoneId.of("UTC"),
            0,
            instant(0).atZone(ZoneId.of("UTC")).toLocalDate(),
            null,
            instant(0),
            instant(0),
            occurrences = listOf(occurrence("occ", "step")),
        )

    private fun snapshot(
        fields: List<SequenceSnapshotField> = emptyList(),
        nodes: List<SequenceSnapshotNode> = emptyList(),
    ) = SequenceConfigSnapshot(
        SequenceSnapshotId("snapshot"),
        "Sequence",
        null,
        null,
        null,
        StatisticsSeriesId("series"),
        instant(0),
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
        fields,
        nodes,
    )

    private fun step(
        id: String,
        position: Int,
        activityId: String = "activity",
    ) = SequenceSnapshotActivityStep(SequenceSnapshotNodeId(id), position, ActivitySnapshotId(activityId))

    private fun repeat(
        id: String,
        position: Int,
        count: Int,
        vararg children: SequenceSnapshotActivityStep,
    ) = SequenceSnapshotRepeatBlock(SequenceSnapshotNodeId(id), position, count, children.toList())

    private fun field(
        id: String,
        type: CustomFieldType,
        number: Long? = null,
        option: SequenceSnapshotCategoryOption? = null,
        text: String? = null,
    ) = SequenceSnapshotField(
        SequenceSnapshotFieldId(id),
        null,
        id.hashCode(),
        id,
        type = type,
        defaultNumberScaled = number,
        defaultCategoryOptionId = option?.id,
        defaultText = text,
        categoryOptions = listOfNotNull(option),
    )

    private fun occurrence(
        id: String,
        source: String,
        status: RuntimeOccurrenceStatus = RuntimeOccurrenceStatus.NOT_STARTED,
        entered: Long? = null,
    ) = RuntimeOccurrence(
        SequenceOccurrenceId(id),
        SequenceSnapshotNodeId(source),
        ActivitySnapshotId("activity"),
        0,
        null,
        null,
        status,
        entered?.let(::instant),
        null,
        null,
        isRuntimeAdded = false,
        isDeletedFromHistory = false,
    )

    private fun interval(
        id: String,
        kind: SequenceIntervalKind,
        start: Long,
        end: Long?,
        occurrenceId: String? = null,
    ) = SequenceInterval(
        SequenceIntervalId(id),
        kind,
        instant(start),
        end?.let(::instant),
        occurrenceId?.let(::SequenceOccurrenceId),
    )

    private fun instant(milliseconds: Long): Instant = Instant.ofEpochMilli(milliseconds)

    private fun sequenceIds(): () -> SequenceOccurrenceId {
        var id = 0
        return { SequenceOccurrenceId("occ-${++id}") }
    }
}
