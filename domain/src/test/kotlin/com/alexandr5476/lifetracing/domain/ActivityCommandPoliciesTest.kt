package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ActivityCommandPoliciesTest {
    private var snapshotId = 0
    private var fieldId = 0
    private var optionId = 0
    private var executionId = 0
    private val snapshots =
        ActivitySnapshotFactory(
            { ActivitySnapshotId("snapshot-${++snapshotId}") },
            { ActivitySnapshotFieldId("field-${++fieldId}") },
            { ActivitySnapshotCategoryOptionId("option-${++optionId}") },
        )
    private val executions = ActivityExecutionFactory { ActivityExecutionId("execution-${++executionId}") }

    @Test
    fun `one-off snapshot is source-less and overrides preserve default missing and zero semantics`() {
        val built = snapshots.fromOneOff(oneOffDraft(), instant(20))
        val snapshot = built.snapshot

        assertNull(snapshot.sourceTemplateId)
        assertNull(snapshot.sourceRevision)
        assertNull(snapshot.statisticsSeriesId)
        assertTrue(snapshot.fields.all { it.sourceFieldId == null })
        assertTrue(snapshot.fields.flatMap(ActivitySnapshotField::categoryOptions).all { it.sourceOptionId == null })

        val generated = executions.startTimed(snapshot, instant(10), instant(20), ZoneOffset.UTC)
        val numberId = built.fieldIdsByKey.getValue("number")
        val categoryId = built.fieldIdsByKey.getValue("category")
        val textId = built.fieldIdsByKey.getValue("text")
        val corrected =
            ActivityExecutionValuePolicy.apply(
                generated,
                snapshot,
                listOf(
                    ActivityExecutionValueOverride(numberId, NumberExecutionValue(numberId, 0)),
                    ActivityExecutionValueOverride(
                        categoryId,
                        CategoryExecutionValue(categoryId, built.optionIdsByKey.getValue("hard")),
                    ),
                    ActivityExecutionValueOverride(textId, null),
                ),
            )

        assertEquals(ActivityExecutionStatistics.ONE_OFF_BUCKET_ID, corrected.statisticsSeriesId)
        assertEquals(
            setOf(
                NumberExecutionValue(numberId, 0),
                CategoryExecutionValue(categoryId, built.optionIdsByKey.getValue("hard")),
            ),
            corrected.values.toSet(),
        )
    }

    @Test
    fun `value overrides reject unknown targets and mismatched carried value identities`() {
        val built = snapshots.fromOneOff(oneOffDraft(), instant(20))
        val snapshot = built.snapshot
        val execution = executions.startTimed(snapshot, instant(10), instant(20), ZoneOffset.UTC)
        val numberId = built.fieldIdsByKey.getValue("number")
        val textId = built.fieldIdsByKey.getValue("text")

        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionValuePolicy.apply(
                execution,
                snapshot,
                listOf(ActivityExecutionValueOverride(ActivitySnapshotFieldId("missing"), null)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityExecutionValuePolicy.apply(
                execution,
                snapshot,
                listOf(ActivityExecutionValueOverride(numberId, TextExecutionValue(textId, "wrong target"))),
            )
        }
    }

    @Test
    fun `one-off draft rejects persistence and source identities`() {
        val invalid =
            oneOffDraft().copy(
                fields =
                    oneOffDraft().fields.mapIndexed { index, field ->
                        if (index == 0) {
                            field.copy(identity = DraftIdentity.Existing(ActivitySnapshotFieldId("persisted")))
                        } else {
                            field
                        }
                    },
            )

        assertThrows(IllegalArgumentException::class.java) { snapshots.fromOneOff(invalid, instant(0)) }
    }

    @Test
    fun `manual Timer uses entered interval including zero duration and historical DST offset`() {
        val timer = timedSnapshot(TimeTrackingMode.TIMER, Duration.ofMinutes(10))
        val zone = ZoneId.of("Europe/Berlin")
        val start = Instant.parse("2026-08-20T08:00:00Z")
        val end = start.plus(Duration.ofMinutes(14))

        val execution = executions.createManualTimed(timer, start, end, end.plusSeconds(1), zone)
        val zero = executions.createManualTimed(timer, end, end, end, zone)

        assertEquals(Duration.ofMinutes(14), execution.activeDuration)
        assertEquals(ActivityCompletionReason.MANUAL_HISTORY_ENTRY, execution.completionReason)
        assertEquals(120, execution.originalUtcOffsetMinutes)
        assertEquals(Duration.ZERO, zero.activeDuration)
        assertThrows(IllegalArgumentException::class.java) {
            executions.createManualTimed(timer, start, end.plusSeconds(2), end.plusSeconds(1), zone)
        }
    }

    @Test
    fun `cross-midnight and No-live use their respective primary instants`() {
        val zone = ZoneId.of("Europe/Moscow")
        val start = Instant.parse("2026-08-20T20:50:00Z")
        val end = Instant.parse("2026-08-20T21:30:00Z")
        val timed = executions.createManualTimed(timedSnapshot(), start, end, end, zone)
        val noLive = executions.createManualNoLiveHistory(noLiveSnapshot(), end, end, zone)

        assertEquals(Duration.ofMinutes(40), timed.activeDuration)
        assertEquals("2026-08-20", timed.primaryLocalDate.toString())
        assertEquals("2026-08-21", noLive.primaryLocalDate.toString())
        assertNull(noLive.startedAt)
        assertNull(noLive.activeDuration)
    }

    @Test
    fun `history correction preserves pauses recalculates time and detects no-op`() {
        val snapshot = timedSnapshot()
        val initial = executions.createManualTimed(snapshot, instant(10), instant(40), instant(50), ZoneOffset.UTC)
        val pause = ActivityExecutionPause(ActivityExecutionPauseId("pause"), instant(20), instant(25))
        val withPause =
            initial.copy(
                activeDuration = ActivityExecutionDurationCalculator.calculate(instant(10), instant(40), listOf(pause)),
                pauses = listOf(pause),
            )
        val correction =
            ActivityHistoryCorrection(
                withPause.updatedAt,
                ActivityHistoryTimeCorrection.Timed(instant(5), instant(45)),
                ZoneOffset.UTC,
                withPause.values,
                snapshot.shortComment,
            )

        assertFalse(ActivityHistoryCorrectionPolicy.isNoOp(withPause, snapshot, correction))
        val corrected = ActivityHistoryCorrectionPolicy.correct(withPause, snapshot, correction, instant(60))
        assertEquals(Duration.ofSeconds(35), corrected.activeDuration)
        assertEquals(listOf(pause), corrected.pauses)

        val noOp =
            correction.copy(
                time = ActivityHistoryTimeCorrection.Timed(instant(10), instant(40)),
            )
        assertTrue(ActivityHistoryCorrectionPolicy.isNoOp(withPause, snapshot, noOp))
        assertThrows(IllegalArgumentException::class.java) {
            ActivityHistoryCorrectionPolicy.correct(
                withPause,
                snapshot,
                correction.copy(time = ActivityHistoryTimeCorrection.Timed(instant(21), instant(45))),
                instant(60),
            )
        }
    }

    @Test
    fun `No-live correction keeps missing duration and rejects timed shape`() {
        val snapshot = noLiveSnapshot()
        val initial = executions.createManualNoLiveHistory(snapshot, instant(10), instant(20), ZoneOffset.UTC)
        val correction =
            ActivityHistoryCorrection(
                initial.updatedAt,
                ActivityHistoryTimeCorrection.NoLive(instant(15)),
                ZoneId.of("Europe/Berlin"),
                emptyList(),
                "changed",
            )

        val corrected = ActivityHistoryCorrectionPolicy.correct(initial, snapshot, correction, instant(30))
        assertNull(corrected.startedAt)
        assertNull(corrected.activeDuration)
        assertEquals(60, corrected.originalUtcOffsetMinutes)
        assertThrows(IllegalArgumentException::class.java) {
            ActivityHistoryCorrectionPolicy.correct(
                initial,
                snapshot,
                correction.copy(time = ActivityHistoryTimeCorrection.Timed(instant(1), instant(2))),
                instant(30),
            )
        }
    }

    @Test
    fun `Short Comment replacement clones schema identities and maps values explicitly`() {
        val source = snapshots.fromOneOff(oneOffDraft(), instant(0)).snapshot
        val replacement = snapshots.replaceShortComment(source, "new", instant(1))

        assertEquals("new", replacement.snapshot.shortComment)
        assertEquals(source.sourceTemplateId, replacement.snapshot.sourceTemplateId)
        assertEquals(
            source.fields.map(ActivitySnapshotField::sourceFieldId),
            replacement.snapshot.fields.map(ActivitySnapshotField::sourceFieldId),
        )
        assertEquals(source.fields.size, replacement.fieldIds.size)
        assertEquals(
            source.fields.sumOf { it.categoryOptions.size },
            replacement.optionIds.size,
        )
    }

    @Test
    fun `comment-only replacement matches semantically different Fields with tied positions in any order`() {
        val previous = tiedPositionSnapshot()
        val replacement =
            snapshots
                .replaceShortComment(previous, "new", instant(1))
                .snapshot
                .let { it.copy(fields = it.fields.reversed()) }

        assertTrue(ActivityHistoricalSnapshotPolicy.isCommentOnlyReplacement(previous, replacement))
        assertFalse(
            ActivityHistoricalSnapshotPolicy.isCommentOnlyReplacement(
                previous,
                replacement.copy(
                    fields =
                        replacement.fields.map { field ->
                            if (field.sourceFieldId == ActivityTemplateFieldId("number")) {
                                field.copy(unit = "changed")
                            } else {
                                field
                            }
                        },
                ),
            ),
        )
    }

    @Test
    fun `comment-only replacement matches tied Category options by semantics and preserves default`() {
        val previous = tiedPositionSnapshot()
        val replacement =
            snapshots
                .replaceShortComment(previous, "new", instant(1))
                .snapshot
                .let { snapshot ->
                    snapshot.copy(
                        fields =
                            snapshot.fields.map { field ->
                                if (field.type == CustomFieldType.CATEGORY) {
                                    field.copy(categoryOptions = field.categoryOptions.reversed())
                                } else {
                                    field
                                }
                            },
                    )
                }

        assertTrue(ActivityHistoricalSnapshotPolicy.isCommentOnlyReplacement(previous, replacement))
        assertFalse(
            ActivityHistoricalSnapshotPolicy.isCommentOnlyReplacement(
                previous,
                replacement.copy(
                    fields =
                        replacement.fields.map { field ->
                            if (field.type == CustomFieldType.CATEGORY) {
                                field.copy(
                                    categoryOptions =
                                        field.categoryOptions.map { option ->
                                            if (option.sourceOptionId == CategoryOptionId("option-b")) {
                                                option.copy(labelAtCreation = "changed")
                                            } else {
                                                option
                                            }
                                        },
                                )
                            } else {
                                field
                            }
                        },
                ),
            ),
        )
    }

    private fun oneOffDraft() =
        ActivitySnapshotDraft(
            name = "One-off",
            shortComment = "old",
            timeTrackingMode = TimeTrackingMode.STOPWATCH,
            timerTarget = null,
            fields =
                listOf(
                    ActivitySnapshotFieldDraft(
                        DraftIdentity.New("number"),
                        null,
                        0,
                        "Number",
                        type = CustomFieldType.NUMBER,
                        defaultNumberScaled = 5,
                    ),
                    ActivitySnapshotFieldDraft(
                        DraftIdentity.New("category"),
                        null,
                        1,
                        "Category",
                        type = CustomFieldType.CATEGORY,
                        defaultCategoryOption = DraftIdentity.New("easy"),
                        categoryOptions =
                            listOf(
                                ActivitySnapshotCategoryOptionDraft(DraftIdentity.New("easy"), null, 0, "Easy"),
                                ActivitySnapshotCategoryOptionDraft(DraftIdentity.New("hard"), null, 1, "Hard"),
                            ),
                    ),
                    ActivitySnapshotFieldDraft(
                        DraftIdentity.New("text"),
                        null,
                        2,
                        "Text",
                        type = CustomFieldType.TEXT,
                        defaultText = "default",
                    ),
                ),
        )

    private fun tiedPositionSnapshot() =
        ActivityConfigSnapshot(
            id = ActivitySnapshotId("previous"),
            name = "Tied positions",
            shortComment = "old",
            timeTrackingMode = TimeTrackingMode.STOPWATCH,
            timerTarget = null,
            sourceTemplateId = ActivityTemplateId("template"),
            sourceRevision = 1,
            statisticsSeriesId = StatisticsSeriesId("series"),
            locallyModified = false,
            createdAt = instant(0),
            fields =
                listOf(
                    ActivitySnapshotField(
                        id = ActivitySnapshotFieldId("old-number"),
                        sourceFieldId = ActivityTemplateFieldId("number"),
                        position = 0,
                        nameAtCreation = "Number",
                        type = CustomFieldType.NUMBER,
                        unit = "reps",
                        displayPrecision = 1,
                        defaultNumberScaled = 5,
                        isMainValue = true,
                    ),
                    ActivitySnapshotField(
                        id = ActivitySnapshotFieldId("old-category"),
                        sourceFieldId = ActivityTemplateFieldId("category"),
                        position = 0,
                        nameAtCreation = "Category",
                        type = CustomFieldType.CATEGORY,
                        defaultCategoryOptionId = ActivitySnapshotCategoryOptionId("old-option-a"),
                        categoryOptions =
                            listOf(
                                ActivitySnapshotCategoryOption(
                                    ActivitySnapshotCategoryOptionId("old-option-a"),
                                    CategoryOptionId("option-a"),
                                    0,
                                    "A",
                                ),
                                ActivitySnapshotCategoryOption(
                                    ActivitySnapshotCategoryOptionId("old-option-b"),
                                    CategoryOptionId("option-b"),
                                    0,
                                    "B",
                                ),
                            ),
                    ),
                ),
        )

    private fun timedSnapshot(
        mode: TimeTrackingMode = TimeTrackingMode.STOPWATCH,
        target: Duration? = null,
    ) = ActivityConfigSnapshot(
        ActivitySnapshotId("timed-${mode.name}"),
        "Timed",
        null,
        mode,
        target,
        null,
        null,
        null,
        false,
        instant(0),
    )

    private fun noLiveSnapshot() =
        ActivityConfigSnapshot(
            ActivitySnapshotId("no-live"),
            "No live",
            null,
            TimeTrackingMode.NO_LIVE_TRACKING,
            null,
            null,
            null,
            null,
            false,
            instant(0),
        )

    private fun instant(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}
