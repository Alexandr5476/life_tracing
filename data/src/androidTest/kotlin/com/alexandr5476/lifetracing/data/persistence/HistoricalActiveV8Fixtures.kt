@file:Suppress("MaxLineLength", "LongMethod", "LongParameterList", "TooManyFunctions")
// Historical SQL and expectations do not use current writers, mappers or runtime engines.

package com.alexandr5476.lifetracing.data.persistence

import androidx.sqlite.db.SupportSQLiteDatabase
import com.alexandr5476.lifetracing.domain.ActiveActivityRuntime
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActiveSequenceRuntime
import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActivityConfigSnapshot
import com.alexandr5476.lifetracing.domain.ActivityExecution
import com.alexandr5476.lifetracing.domain.ActivityExecutionContext
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.ActivityExecutionPause
import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityExecutionStatus
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
import com.alexandr5476.lifetracing.domain.NoLiveTimeAccounting
import com.alexandr5476.lifetracing.domain.NumberExecutionValue
import com.alexandr5476.lifetracing.domain.NumberSequenceExecutionValue
import com.alexandr5476.lifetracing.domain.OccurrenceCompletionReason
import com.alexandr5476.lifetracing.domain.RuntimeOccurrence
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceConfigSnapshot
import com.alexandr5476.lifetracing.domain.SequenceExecution
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionStatus
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotActivityStep
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOption
import com.alexandr5476.lifetracing.domain.SequenceSnapshotCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotField
import com.alexandr5476.lifetracing.domain.SequenceSnapshotFieldId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotNodeId
import com.alexandr5476.lifetracing.domain.SequenceSnapshotRepeatBlock
import com.alexandr5476.lifetracing.domain.SequenceSnapshotSettings
import com.alexandr5476.lifetracing.domain.SequenceStepOverrides
import com.alexandr5476.lifetracing.domain.SequenceTemplateCategoryOptionId
import com.alexandr5476.lifetracing.domain.SequenceTemplateFieldId
import com.alexandr5476.lifetracing.domain.SequenceTemplateId
import com.alexandr5476.lifetracing.domain.StatisticsSeriesId
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import com.alexandr5476.lifetracing.domain.TimerZeroBehavior
import org.junit.Ignore
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Ignore("Historical fixture states, not an instrumentation test")
internal enum class HistoricalActiveV8State {
    EMPTY,
    UNOWNED,
    ACTIVITY_RUNNING,
    ACTIVITY_PAUSED,
    SEQUENCE_RUNNING,
    SEQUENCE_PAUSED,
    WAITING_NEXT,
    TRANSITION,
    PAUSED_TRANSITION,
}

@Ignore("Historical source facts, not an instrumentation test")
internal class HistoricalActiveV8Fixture(
    val state: HistoricalActiveV8State,
    private val zeroActual: Boolean = true,
    private val overtime: Boolean = false,
) {
    val origin: Instant = Instant.parse("2020-01-01T22:30:00.123Z")
    val zone: ZoneId = ZoneId.of("Asia/Tokyo")
    val date: LocalDate = LocalDate.of(2020, 1, 2)
    val sequenceId = SequenceExecutionId("sequence")
    val paused =
        state in
            setOf(
                HistoricalActiveV8State.ACTIVITY_PAUSED,
                HistoricalActiveV8State.SEQUENCE_PAUSED,
                HistoricalActiveV8State.PAUSED_TRANSITION,
            )
    val hasCurrent = state in setOf(HistoricalActiveV8State.SEQUENCE_RUNNING, HistoricalActiveV8State.SEQUENCE_PAUSED)
    val activity = frozenActivity()
    val runtimeAddedActivity =
        activity.copy(
            id = ActivitySnapshotId("runtime-snapshot"),
            name = "Frozen runtime addition",
            fields = emptyList(),
            locallyModified = false,
        )
    val sequence = frozenSequence()
    val standalone =
        expectedActivity("standalone", 0, null, if (paused) 25 else 20).copy(
            pauses =
                listOf(
                    ActivityExecutionPause(
                        ActivityExecutionPauseId("activity-pause"),
                        at(10),
                        if (paused) null else at(20),
                    ),
                ),
            values =
                if (zeroActual) {
                    listOf(
                        NumberExecutionValue(ActivitySnapshotFieldId("f-number"), 0),
                    )
                } else {
                    emptyList()
                },
        )
    val completedChild =
        expectedActivity("child-0", 0, 30, 30, 0).copy(
            values = listOf(NumberExecutionValue(ActivitySnapshotFieldId("f-number"), 0)),
        )
    val currentChild =
        if (hasCurrent) {
            expectedActivity("child-1", 40, null, if (paused) 50 else 40, 1).copy(
                pauses =
                    if (paused) {
                        listOf(
                            ActivityExecutionPause(ActivityExecutionPauseId("child-pause"), at(50), null),
                        )
                    } else {
                        emptyList()
                    },
            )
        } else {
            null
        }

    // Construct before opening the source; never derive expected state from migrated rows.
    val runtime: ActiveRuntime? =
        when (state) {
            HistoricalActiveV8State.EMPTY, HistoricalActiveV8State.UNOWNED -> null
            HistoricalActiveV8State.ACTIVITY_RUNNING, HistoricalActiveV8State.ACTIVITY_PAUSED ->
                ActiveActivityRuntime(
                    ActiveSession(
                        ActiveSessionKind.ACTIVITY,
                        if (paused) ActiveSessionState.PAUSED else ActiveSessionState.RUNNING,
                        standalone.id,
                        null,
                        standalone.updatedAt,
                    ),
                    standalone,
                    activity,
                )
            else -> {
                val execution = expectedSequence()
                ActiveSequenceRuntime(
                    ActiveSession(
                        ActiveSessionKind.SEQUENCE,
                        when {
                            paused -> ActiveSessionState.PAUSED
                            state == HistoricalActiveV8State.WAITING_NEXT -> ActiveSessionState.WAITING_NEXT
                            else -> ActiveSessionState.RUNNING
                        },
                        null,
                        sequenceId,
                        execution.updatedAt,
                    ),
                    execution,
                    sequence,
                    mapOf(activity.id to activity, runtimeAddedActivity.id to runtimeAddedActivity),
                    currentChild,
                    if (!hasCurrent &&
                        state != HistoricalActiveV8State.WAITING_NEXT
                    ) {
                        SequenceOccurrenceId("occurrence-1")
                    } else {
                        null
                    },
                )
            }
        }

    fun at(seconds: Long): Instant = origin.plusSeconds(seconds)

    fun seed(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON")
        db.seedFrozenActivityV3()
        db.execSQL(
            "UPDATE activity_snapshot_settings SET timer_zero_behavior = ? WHERE snapshot_id = 'activity-snapshot'",
            arrayOf(if (overtime) "OVERTIME" else "FINISH"),
        )
        when (state) {
            HistoricalActiveV8State.EMPTY -> Unit
            HistoricalActiveV8State.UNOWNED,
            HistoricalActiveV8State.ACTIVITY_RUNNING,
            HistoricalActiveV8State.ACTIVITY_PAUSED,
            -> seedStandalone(db)
            else -> seedSequence(db)
        }
    }

    private fun seedStandalone(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO activity_executions VALUES ('standalone', 'activity-snapshot', 'STANDALONE', NULL, NULL, NULL, 'activity-series', ?, ?, NULL, NULL, 'Asia/Tokyo', 540, '2020-01-02', NULL, NULL, ?, ?)",
            arrayOf<Any>(
                if (paused) "PAUSED" else "RUNNING",
                at(0).toEpochMilli(),
                at(0).toEpochMilli(),
                at(if (paused) 25 else 20).toEpochMilli(),
            ),
        )
        db.execSQL(
            "INSERT INTO activity_execution_pauses VALUES ('activity-pause', 'standalone', ?, ?)",
            arrayOf<Any?>(at(10).toEpochMilli(), if (paused) null else at(20).toEpochMilli()),
        )
        if (zeroActual) {
            db.execSQL(
                "INSERT INTO activity_execution_field_values VALUES ('standalone', 'f-number', 0, NULL, NULL)",
            )
        }
        if (state != HistoricalActiveV8State.UNOWNED) {
            db.execSQL(
                "INSERT INTO active_session VALUES (1, 'ACTIVITY', 'standalone', NULL, ?, ?)",
                arrayOf<Any>(if (paused) "PAUSED" else "RUNNING", at(if (paused) 25 else 20).toEpochMilli()),
            )
        }
    }

    @Suppress("CyclomaticComplexMethod") // Each authentic historical state has explicit SQL facts.
    private fun seedSequence(db: SupportSQLiteDatabase) {
        db.seedFrozenSequenceV6()
        db.execSQL(
            "UPDATE sequence_snapshot_settings SET auto_advance = ?, before_each_step_countdown_ms = 5000 WHERE sequence_snapshot_id = 'sequence-snapshot'",
            arrayOf<Any>(
                if (state ==
                    HistoricalActiveV8State.WAITING_NEXT
                ) {
                    0
                } else {
                    1
                },
            ),
        )
        db.execSQL(
            "UPDATE sequence_snapshot_step_overrides SET start_countdown_ms = 10000, timer_zero_behavior = 'FINISH' WHERE sequence_snapshot_node_id = 'f-nested'",
        )
        db.execSQL(
            "INSERT INTO activity_snapshots VALUES ('runtime-snapshot', 'Frozen runtime addition', 'Frozen comment', 'TIMER', 30000, 'activity-template', 3, 'activity-series', 0, 15)",
        )
        db.execSQL("INSERT INTO activity_snapshot_settings VALUES ('runtime-snapshot', 0, 2000, 'FINISH', 0, 1, 1, 1)")
        val updated =
            when (state) {
                HistoricalActiveV8State.SEQUENCE_RUNNING -> 40L
                HistoricalActiveV8State.SEQUENCE_PAUSED -> 50L
                HistoricalActiveV8State.PAUSED_TRANSITION -> 34L
                else -> 30L
            }
        // Root and occurrence FK cycle is filled within one source transaction.
        db.execSQL(
            "INSERT INTO sequence_executions VALUES ('sequence', 'sequence-snapshot', NULL, 'sequence-series', ?, ?, NULL, NULL, NULL, NULL, 'Asia/Tokyo', 540, '2020-01-02', NULL, ?, ?)",
            arrayOf<Any>(
                if (paused) "PAUSED" else "RUNNING",
                at(0).toEpochMilli(),
                at(0).toEpochMilli(),
                at(updated).toEpochMilli(),
            ),
        )
        db.execSQL(
            "INSERT INTO sequence_occurrences VALUES ('occurrence-0', 'sequence', 'f-top', 'activity-snapshot', 0, NULL, NULL, 'COMPLETED', ?, ?, 'NATURAL_TIMER_END', 0, 0)",
            arrayOf<Any>(at(0).toEpochMilli(), at(30).toEpochMilli()),
        )
        db.execSQL(
            "INSERT INTO sequence_occurrences VALUES ('occurrence-1', 'sequence', 'f-nested', 'activity-snapshot', 1, 'f-repeat', 1, ?, ?, NULL, NULL, 0, 0)",
            arrayOf<Any?>(
                if (hasCurrent) "CURRENT" else "NOT_STARTED",
                if (hasCurrent) at(40).toEpochMilli() else null,
            ),
        )
        db.execSQL(
            "INSERT INTO sequence_occurrences VALUES ('occurrence-2', 'sequence', 'f-nested', 'activity-snapshot', 2, 'f-repeat', 2, 'NOT_STARTED', NULL, NULL, NULL, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO sequence_occurrences VALUES ('occurrence-3', 'sequence', NULL, 'runtime-snapshot', 3, NULL, NULL, 'NOT_STARTED', NULL, NULL, NULL, 1, 0)",
        )
        db.execSQL(
            "INSERT INTO sequence_intervals VALUES ('active-0', 'sequence', 'ACTIVE_STEP', ?, ?, 'occurrence-0')",
            arrayOf<Any>(at(0).toEpochMilli(), at(30).toEpochMilli()),
        )
        db.execSQL(
            "INSERT INTO activity_executions VALUES ('child-0', 'activity-snapshot', 'SEQUENCE_CHILD', 'sequence', 'occurrence-0', NULL, 'activity-series', 'COMPLETED', ?, ?, 30000, 'Asia/Tokyo', 540, '2020-01-02', NULL, NULL, ?, ?)",
            arrayOf<Any>(at(0).toEpochMilli(), at(30).toEpochMilli(), at(0).toEpochMilli(), at(30).toEpochMilli()),
        )
        db.execSQL("INSERT INTO activity_execution_field_values VALUES ('child-0', 'f-number', 0, NULL, NULL)")
        db.execSQL("INSERT INTO sequence_execution_field_values VALUES ('sequence', 'sf-number', 0, NULL, NULL)")
        when {
            hasCurrent -> {
                db.execSQL(
                    "UPDATE sequence_executions SET current_occurrence_id = 'occurrence-1' WHERE id = 'sequence'",
                )
                db.execSQL(
                    "INSERT INTO sequence_intervals VALUES ('countdown-1', 'sequence', 'TRANSITION_COUNTDOWN', ?, ?, 'occurrence-1')",
                    arrayOf<Any>(at(30).toEpochMilli(), at(40).toEpochMilli()),
                )
                db.execSQL(
                    "INSERT INTO sequence_intervals VALUES ('active-1', 'sequence', 'ACTIVE_STEP', ?, ?, 'occurrence-1')",
                    arrayOf<Any?>(at(40).toEpochMilli(), if (paused) at(50).toEpochMilli() else null),
                )
                db.execSQL(
                    "INSERT INTO activity_executions VALUES ('child-1', 'activity-snapshot', 'SEQUENCE_CHILD', 'sequence', 'occurrence-1', NULL, 'activity-series', ?, ?, NULL, NULL, 'Asia/Tokyo', 540, '2020-01-02', NULL, NULL, ?, ?)",
                    arrayOf<Any>(
                        if (paused) "PAUSED" else "RUNNING",
                        at(40).toEpochMilli(),
                        at(40).toEpochMilli(),
                        at(updated).toEpochMilli(),
                    ),
                )
                if (paused) {
                    db.execSQL(
                        "INSERT INTO activity_execution_pauses VALUES ('child-pause', 'child-1', ?, NULL)",
                        arrayOf<Any>(at(50).toEpochMilli()),
                    )
                }
            }
            state == HistoricalActiveV8State.WAITING_NEXT ->
                db.execSQL(
                    "INSERT INTO sequence_intervals VALUES ('idle', 'sequence', 'IMPLICIT_IDLE', ?, NULL, NULL)",
                    arrayOf<Any>(at(30).toEpochMilli()),
                )
            else ->
                db.execSQL(
                    "INSERT INTO sequence_intervals VALUES ('countdown-1', 'sequence', 'TRANSITION_COUNTDOWN', ?, ?, 'occurrence-1')",
                    arrayOf<Any?>(at(30).toEpochMilli(), if (paused) at(34).toEpochMilli() else null),
                )
        }
        if (paused) {
            db.execSQL(
                "INSERT INTO sequence_intervals VALUES ('sequence-pause', 'sequence', 'EXPLICIT_PAUSE', ?, NULL, NULL)",
                arrayOf<Any>(at(updated).toEpochMilli()),
            )
        }
        db.execSQL(
            "INSERT INTO active_session VALUES (1, 'SEQUENCE', NULL, 'sequence', ?, ?)",
            arrayOf<Any>(
                when {
                    paused -> "PAUSED"
                    state == HistoricalActiveV8State.WAITING_NEXT -> "WAITING_NEXT"
                    else -> "RUNNING"
                },
                at(updated).toEpochMilli(),
            ),
        )
    }

    private fun frozenActivity() =
        ActivityConfigSnapshot(
            ActivitySnapshotId(
                "activity-snapshot",
            ),
            "Frozen activity",
            "Frozen comment",
            TimeTrackingMode.TIMER,
            Duration.ofSeconds(30),
            ActivityTemplateId(
                "activity-template",
            ),
            3,
            StatisticsSeriesId("activity-series"),
            true,
            Instant.ofEpochMilli(15),
            ActivityTemplateSettings(
                false,
                Duration.ofSeconds(2),
                if (overtime) TimerZeroBehavior.OVERTIME else TimerZeroBehavior.FINISH,
                false,
                true,
                true,
                true,
            ),
            listOf(
                ActivitySnapshotField(
                    ActivitySnapshotFieldId(
                        "f-number",
                    ),
                    ActivityTemplateFieldId(
                        "number",
                    ),
                    0,
                    "Captured amount",
                    "Local amount",
                    CustomFieldType.NUMBER,
                    "kg",
                    1,
                    1500,
                    isMainValue = true,
                ),
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("f-category"),
                    ActivityTemplateFieldId("category"),
                    1,
                    "Captured effort",
                    null,
                    CustomFieldType.CATEGORY,
                    defaultCategoryOptionId = ActivitySnapshotCategoryOptionId("f-option"),
                    categoryOptions =
                        listOf(
                            ActivitySnapshotCategoryOption(
                                ActivitySnapshotCategoryOptionId("f-option"),
                                CategoryOptionId("option"),
                                0,
                                "Captured option",
                                "Local option",
                            ),
                        ),
                ),
                ActivitySnapshotField(
                    ActivitySnapshotFieldId("f-text"),
                    ActivityTemplateFieldId("text"),
                    2,
                    "Captured note",
                    null,
                    CustomFieldType.TEXT,
                    defaultText = "Frozen default",
                ),
            ),
        )

    private fun frozenSequence() =
        SequenceConfigSnapshot(
            SequenceSnapshotId(
                "sequence-snapshot",
            ),
            "Frozen sequence",
            "Frozen sequence comment",
            SequenceTemplateId(
                "sequence-template",
            ),
            2,
            StatisticsSeriesId("sequence-series"),
            Instant.ofEpochMilli(15),
            SequenceSnapshotSettings(
                state != HistoricalActiveV8State.WAITING_NEXT,
                Duration.ZERO,
                Duration.ofSeconds(
                    5,
                ),
                false,
                true,
                true,
                true,
                true,
                NoLiveTimeAccounting.ACTIVE,
            ),
            listOf(
                SequenceSnapshotField(
                    SequenceSnapshotFieldId(
                        "sf-number",
                    ),
                    SequenceTemplateFieldId(
                        "s-number",
                    ),
                    0,
                    "Captured rounds",
                    "Local rounds",
                    CustomFieldType.NUMBER,
                    "reps",
                    0,
                    2000,
                    isMainValue = true,
                ),
                SequenceSnapshotField(
                    SequenceSnapshotFieldId("sf-category"),
                    SequenceTemplateFieldId("s-category"),
                    1,
                    "Captured quality",
                    null,
                    CustomFieldType.CATEGORY,
                    defaultCategoryOptionId = SequenceSnapshotCategoryOptionId("sf-option"),
                    categoryOptions =
                        listOf(
                            SequenceSnapshotCategoryOption(
                                SequenceSnapshotCategoryOptionId("sf-option"),
                                SequenceTemplateCategoryOptionId("s-option"),
                                0,
                                "Captured quality option",
                                "Local quality option",
                            ),
                        ),
                ),
            ),
            listOf(
                SequenceSnapshotActivityStep(SequenceSnapshotNodeId("f-top"), 0, activity.id),
                SequenceSnapshotRepeatBlock(
                    SequenceSnapshotNodeId("f-repeat"),
                    1,
                    2,
                    listOf(
                        SequenceSnapshotActivityStep(
                            SequenceSnapshotNodeId("f-nested"),
                            0,
                            activity.id,
                            SequenceStepOverrides(Duration.ofSeconds(10), TimerZeroBehavior.FINISH, false, true, true),
                        ),
                    ),
                ),
            ),
        )

    private fun expectedActivity(
        id: String,
        start: Long,
        end: Long?,
        updated: Long,
        occurrence: Int? = null,
    ) = ActivityExecution(
        ActivityExecutionId(id),
        activity.id,
        if (occurrence ==
            null
        ) {
            ActivityExecutionContext.STANDALONE
        } else {
            ActivityExecutionContext.SEQUENCE_CHILD
        },
        StatisticsSeriesId("activity-series"),
        when {
            end != null -> ActivityExecutionStatus.COMPLETED
            paused -> ActivityExecutionStatus.PAUSED
            else -> ActivityExecutionStatus.RUNNING
        },
        at(start),
        end?.let(::at),
        end?.let {
            Duration.ofSeconds(it - start)
        },
        zone,
        540,
        date,
        null,
        null,
        at(start),
        at(updated),
        sequenceExecutionId = if (occurrence == null) null else sequenceId,
        sequenceOccurrenceId = occurrence?.let { SequenceOccurrenceId("occurrence-$it") },
    )

    @Suppress("CyclomaticComplexMethod") // Expectations enumerate the durable state dimensions independently.
    private fun expectedSequence(): SequenceExecution {
        val updated =
            when (state) {
                HistoricalActiveV8State.SEQUENCE_RUNNING -> 40L
                HistoricalActiveV8State.SEQUENCE_PAUSED -> 50L
                HistoricalActiveV8State.PAUSED_TRANSITION -> 34L
                else -> 30L
            }
        return SequenceExecution(
            sequenceId,
            sequence.id,
            StatisticsSeriesId(
                "sequence-series",
            ),
            if (paused) SequenceExecutionStatus.PAUSED else SequenceExecutionStatus.RUNNING,
            at(
                0,
            ),
            null,
            null,
            null,
            null,
            zone,
            540,
            date,
            if (hasCurrent) {
                SequenceOccurrenceId(
                    "occurrence-1",
                )
            } else {
                null
            },
            at(0),
            at(updated),
            occurrences =
                (0..3).map { index ->
                    RuntimeOccurrence(
                        SequenceOccurrenceId("occurrence-$index"),
                        when (index) {
                            0 -> SequenceSnapshotNodeId("f-top")
                            3 -> null
                            else -> SequenceSnapshotNodeId("f-nested")
                        },
                        if (index ==
                            3
                        ) {
                            runtimeAddedActivity.id
                        } else {
                            activity.id
                        },
                        index,
                        if (index in
                            1..2
                        ) {
                            SequenceSnapshotNodeId("f-repeat")
                        } else {
                            null
                        },
                        if (index in 1..2) index else null,
                        when {
                            index == 0 -> RuntimeOccurrenceStatus.COMPLETED
                            index == 1 && hasCurrent -> RuntimeOccurrenceStatus.CURRENT
                            else -> RuntimeOccurrenceStatus.NOT_STARTED
                        },
                        when {
                            index == 0 -> at(0)
                            index == 1 && hasCurrent -> at(40)
                            else -> null
                        },
                        if (index ==
                            0
                        ) {
                            at(30)
                        } else {
                            null
                        },
                        if (index ==
                            0
                        ) {
                            OccurrenceCompletionReason.NATURAL_TIMER_END
                        } else {
                            null
                        },
                        index == 3,
                        false,
                    )
                },
            intervals =
                buildList {
                    add(interval("active-0", SequenceIntervalKind.ACTIVE_STEP, 0, 30, 0))
                    when {
                        hasCurrent -> {
                            add(interval("countdown-1", SequenceIntervalKind.TRANSITION_COUNTDOWN, 30, 40, 1))
                            add(interval("active-1", SequenceIntervalKind.ACTIVE_STEP, 40, if (paused) 50 else null, 1))
                        }
                        state == HistoricalActiveV8State.WAITING_NEXT ->
                            add(
                                interval("idle", SequenceIntervalKind.IMPLICIT_IDLE, 30, null),
                            )
                        else ->
                            add(
                                interval(
                                    "countdown-1",
                                    SequenceIntervalKind.TRANSITION_COUNTDOWN,
                                    30,
                                    if (paused) 34 else null,
                                    1,
                                ),
                            )
                    }
                    if (paused) add(interval("sequence-pause", SequenceIntervalKind.EXPLICIT_PAUSE, updated, null))
                },
            values = listOf(NumberSequenceExecutionValue(SequenceSnapshotFieldId("sf-number"), 0)),
        )
    }

    private fun interval(
        id: String,
        kind: SequenceIntervalKind,
        start: Long,
        end: Long?,
        occurrence: Int? = null,
    ) = SequenceInterval(
        SequenceIntervalId(id),
        kind,
        at(start),
        end?.let(::at),
        occurrence?.let {
            SequenceOccurrenceId("occurrence-$it")
        },
    )
}
