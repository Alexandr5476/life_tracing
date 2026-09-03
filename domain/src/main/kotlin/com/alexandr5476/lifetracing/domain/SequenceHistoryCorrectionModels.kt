package com.alexandr5476.lifetracing.domain

import java.time.Instant

data class SequenceHistoryTimingCorrection(
    val expectedUpdatedAt: Instant,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val occurrenceTimings: List<SequenceOccurrenceTimingCorrection> = emptyList(),
    val finalIntervals: List<SequenceInterval>? = null,
    val childTimings: List<SequenceChildTimingCorrection> = emptyList(),
)

data class SequenceOccurrenceTimingCorrection(
    val occurrenceId: SequenceOccurrenceId,
    val enteredAt: Instant?,
    val completedAt: Instant?,
)

data class SequenceChildTimingCorrection(
    val executionId: ActivityExecutionId,
    val time: ActivityHistoryTimeCorrection,
)

data class SequenceHistoryChildExecution(
    val execution: ActivityExecution,
    val snapshot: ActivityConfigSnapshot,
)

data class SequenceHistoryTimingCorrectionResult(
    val execution: SequenceExecution,
    val children: List<ActivityExecution>,
)

data class SequenceChildHistoryDeletionCommand(
    val expectedUpdatedAt: Instant,
    val occurrenceId: SequenceOccurrenceId,
    val childExecutionId: ActivityExecutionId,
)

data class SequenceChildHistoryDeletionResult(
    val execution: SequenceExecution,
    val child: ActivityExecution,
)

enum class SequenceHistoryStructuralRemovalMode {
    LEAVE_GAP,
    CLOSE_GAP,
}

data class SequenceHistoryStructuralRemovalCommand(
    val expectedUpdatedAt: Instant,
    val occurrenceId: SequenceOccurrenceId,
    val childExecutionId: ActivityExecutionId,
    val mode: SequenceHistoryStructuralRemovalMode,
    val finalEndedAt: Instant,
    val finalIntervals: List<SequenceInterval>,
    val occurrenceTimings: List<SequenceOccurrenceTimingCorrection> = emptyList(),
    val childTimings: List<SequenceStructuralChildTimingCorrection> = emptyList(),
)

data class SequenceStructuralChildTimingCorrection(
    val executionId: ActivityExecutionId,
    val time: ActivityHistoryTimeCorrection,
    val pauses: List<ActivityExecutionPause>,
)

data class SequenceHistoryStructuralRemovalResult(
    val execution: SequenceExecution,
    val children: List<ActivityExecution>,
)
