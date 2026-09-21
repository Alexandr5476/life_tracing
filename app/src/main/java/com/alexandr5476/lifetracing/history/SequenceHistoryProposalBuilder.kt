@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MaxLineLength", "ReturnCount", "TooManyFunctions")

package com.alexandr5476.lifetracing.history

import com.alexandr5476.lifetracing.domain.ActivityExecutionPauseId
import com.alexandr5476.lifetracing.domain.ActivityHistoryTimeCorrection
import com.alexandr5476.lifetracing.domain.RuntimeOccurrenceStatus
import com.alexandr5476.lifetracing.domain.SequenceChildTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceHistoryDetail
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalCommand
import com.alexandr5476.lifetracing.domain.SequenceHistoryStructuralRemovalMode
import com.alexandr5476.lifetracing.domain.SequenceHistoryTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceInterval
import com.alexandr5476.lifetracing.domain.SequenceIntervalId
import com.alexandr5476.lifetracing.domain.SequenceIntervalKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceTimingCorrection
import com.alexandr5476.lifetracing.domain.SequenceStructuralChildTimingCorrection
import java.time.Instant
import java.time.ZoneOffset

sealed interface SequenceHistoryTimestampTarget {
    data object RootStartedAt : SequenceHistoryTimestampTarget

    data object RootEndedAt : SequenceHistoryTimestampTarget

    data class OccurrenceEnteredAt(
        val occurrenceId: SequenceOccurrenceId,
    ) : SequenceHistoryTimestampTarget

    data class OccurrenceCompletedAt(
        val occurrenceId: SequenceOccurrenceId,
    ) : SequenceHistoryTimestampTarget

    data class ChildStartedAt(
        val occurrenceId: SequenceOccurrenceId,
    ) : SequenceHistoryTimestampTarget

    data class ChildCompletedAt(
        val occurrenceId: SequenceOccurrenceId,
    ) : SequenceHistoryTimestampTarget

    data class IntervalStartedAt(
        val intervalId: SequenceIntervalId,
    ) : SequenceHistoryTimestampTarget

    data class IntervalEndedAt(
        val intervalId: SequenceIntervalId,
    ) : SequenceHistoryTimestampTarget
}

data class SequenceHistoryTimestampDraft(
    val text: String,
    val selectedOffset: ZoneOffset? = null,
    val validOffsets: List<ZoneOffset> = emptyList(),
)

data class SequenceHistoryTimingDraft(
    val expectedUpdatedAt: Instant,
    val timestamps: Map<SequenceHistoryTimestampTarget, SequenceHistoryTimestampDraft>,
)

data class SequenceHistoryTimingProposal(
    val correction: SequenceHistoryTimingCorrection,
    val hasActiveIntervalOverlap: Boolean,
    val changes: List<SequenceHistoryPreviewChange>,
)

data class SequenceHistoryOccurrenceDescriptor(
    val occurrenceId: SequenceOccurrenceId,
    val activityTitle: String,
    val runtimePosition: Int,
    val hasSourceStep: Boolean,
    val repeatIteration: Int?,
    val isRuntimeAdded: Boolean,
)

/** Display-only facts derived with the proposal; Compose never rebuilds a correction from them. */
sealed interface SequenceHistoryPreviewChange {
    data class Timestamp(
        val target: SequenceHistoryTimestampTarget,
        val before: Instant,
        val after: Instant,
    ) : SequenceHistoryPreviewChange

    data class RemovedOccurrence(
        val occurrence: SequenceHistoryOccurrenceDescriptor,
    ) : SequenceHistoryPreviewChange

    data class ChildPauseTimestamp(
        val occurrence: SequenceHistoryOccurrenceDescriptor,
        val pauseId: ActivityExecutionPauseId,
        val beforeStartedAt: Instant,
        val beforeEndedAt: Instant?,
        val afterStartedAt: Instant,
        val afterEndedAt: Instant?,
    ) : SequenceHistoryPreviewChange

    data class RemovedInterval(
        val intervalId: SequenceIntervalId,
    ) : SequenceHistoryPreviewChange

    data class OwnerlessPlacement(
        val intervalId: SequenceIntervalId,
        val placement: OwnerlessIntervalPlacement,
    ) : SequenceHistoryPreviewChange
}

sealed interface SequenceHistoryTimingBuildResult {
    data object NoChange : SequenceHistoryTimingBuildResult

    data class Ready(
        val proposal: SequenceHistoryTimingProposal,
    ) : SequenceHistoryTimingBuildResult

    data class Invalid(
        val target: SequenceHistoryTimestampTarget,
        val issue: SequenceHistoryProposalIssue,
        val validOffsets: List<ZoneOffset> = emptyList(),
    ) : SequenceHistoryTimingBuildResult
}

enum class SequenceHistoryProposalIssue {
    INVALID_DATE_TIME,
    NONEXISTENT_LOCAL_TIME,
    AMBIGUOUS_LOCAL_TIME,
    MISSING_MUTATION_FACT,
}

enum class OwnerlessIntervalPlacement {
    FIXED,
    TRANSLATED,
}

data class SequenceHistoryStructuralProposal(
    val occurrenceId: SequenceOccurrenceId,
    val target: SequenceHistoryOccurrenceDescriptor,
    val mode: SequenceHistoryStructuralRemovalMode,
    val shiftMillis: Long,
    val ownerlessPlacements: Map<SequenceIntervalId, OwnerlessIntervalPlacement?>,
    val command: SequenceHistoryStructuralRemovalCommand?,
    val changes: List<SequenceHistoryPreviewChange>,
) {
    val isConfirmable: Boolean
        get() = command != null
}

object SequenceHistoryProposalBuilder {
    fun occurrenceDescriptor(
        detail: SequenceHistoryDetail,
        occurrenceId: SequenceOccurrenceId,
    ): SequenceHistoryOccurrenceDescriptor? =
        detail.occurrences.singleOrNull { it.occurrenceId == occurrenceId }?.let {
            SequenceHistoryOccurrenceDescriptor(
                it.occurrenceId,
                it.activity.title,
                it.runtimePosition,
                it.sourceSequenceSnapshotNodeId != null,
                it.repeatIteration,
                it.isRuntimeAdded,
            )
        }

    fun timingDraft(detail: SequenceHistoryDetail): SequenceHistoryTimingDraft {
        val zone = detail.originalZoneId
        val values = linkedMapOf<SequenceHistoryTimestampTarget, SequenceHistoryTimestampDraft>()

        fun add(
            target: SequenceHistoryTimestampTarget,
            instant: Instant?,
        ) {
            instant ?: return
            val local = instant.toHistoricalLocalDateTime(zone)
            values[target] = SequenceHistoryTimestampDraft(local.text, local.selectedOffset, local.validOffsets)
        }
        add(SequenceHistoryTimestampTarget.RootStartedAt, detail.root.startedAt)
        add(SequenceHistoryTimestampTarget.RootEndedAt, detail.root.completedAt)
        detail.occurrences.forEach { occurrence ->
            if (occurrence.status != RuntimeOccurrenceStatus.DELETED_EXECUTION) {
                add(SequenceHistoryTimestampTarget.OccurrenceEnteredAt(occurrence.occurrenceId), occurrence.enteredAt)
                add(
                    SequenceHistoryTimestampTarget.OccurrenceCompletedAt(occurrence.occurrenceId),
                    occurrence.completedAt,
                )
            }
            occurrence.child?.let {
                add(SequenceHistoryTimestampTarget.ChildStartedAt(occurrence.occurrenceId), it.startedAt)
                add(SequenceHistoryTimestampTarget.ChildCompletedAt(occurrence.occurrenceId), it.completedAt)
            }
        }
        detail.intervals.forEach { interval ->
            add(SequenceHistoryTimestampTarget.IntervalStartedAt(interval.id), interval.startedAt)
            add(SequenceHistoryTimestampTarget.IntervalEndedAt(interval.id), interval.endedAt)
        }
        return SequenceHistoryTimingDraft(detail.updatedAt, values)
    }

    fun timing(
        detail: SequenceHistoryDetail,
        draft: SequenceHistoryTimingDraft,
    ): SequenceHistoryTimingBuildResult {
        val resolved = linkedMapOf<SequenceHistoryTimestampTarget, Instant>()
        draft.timestamps.forEach { (target, value) ->
            when (
                val result =
                    resolveHistoricalLocalDateTime(value.text, detail.originalZoneId, value.selectedOffset)
            ) {
                is HistoricalLocalDateTimeResolution.Resolved -> resolved[target] = result.instant
                HistoricalLocalDateTimeResolution.Invalid ->
                    return SequenceHistoryTimingBuildResult.Invalid(
                        target,
                        SequenceHistoryProposalIssue.INVALID_DATE_TIME,
                    )
                HistoricalLocalDateTimeResolution.Nonexistent ->
                    return SequenceHistoryTimingBuildResult.Invalid(
                        target,
                        SequenceHistoryProposalIssue.NONEXISTENT_LOCAL_TIME,
                    )
                is HistoricalLocalDateTimeResolution.Ambiguous ->
                    return SequenceHistoryTimingBuildResult.Invalid(
                        target,
                        SequenceHistoryProposalIssue.AMBIGUOUS_LOCAL_TIME,
                        result.offsets,
                    )
            }
        }

        fun value(
            target: SequenceHistoryTimestampTarget,
            fallback: Instant?,
        ): Instant? = resolved[target] ?: fallback
        val occurrenceCorrections =
            detail.occurrences.mapNotNull { occurrence ->
                if (occurrence.status == RuntimeOccurrenceStatus.DELETED_EXECUTION) return@mapNotNull null
                val entered =
                    value(
                        SequenceHistoryTimestampTarget.OccurrenceEnteredAt(occurrence.occurrenceId),
                        occurrence.enteredAt,
                    )
                val completed =
                    value(
                        SequenceHistoryTimestampTarget.OccurrenceCompletedAt(occurrence.occurrenceId),
                        occurrence.completedAt,
                    )
                if (entered == occurrence.enteredAt && completed == occurrence.completedAt) {
                    null
                } else {
                    SequenceOccurrenceTimingCorrection(occurrence.occurrenceId, entered, completed)
                }
            }
        val childCorrections =
            detail.occurrences.mapNotNull { occurrence ->
                val child = occurrence.child ?: return@mapNotNull null
                val started =
                    value(SequenceHistoryTimestampTarget.ChildStartedAt(occurrence.occurrenceId), child.startedAt)
                val completed =
                    value(SequenceHistoryTimestampTarget.ChildCompletedAt(occurrence.occurrenceId), child.completedAt)
                        ?: return SequenceHistoryTimingBuildResult.Invalid(
                            SequenceHistoryTimestampTarget.ChildCompletedAt(occurrence.occurrenceId),
                            SequenceHistoryProposalIssue.MISSING_MUTATION_FACT,
                        )
                if (started == child.startedAt && completed == child.completedAt) return@mapNotNull null
                SequenceChildTimingCorrection(
                    child.executionId,
                    started?.let { ActivityHistoryTimeCorrection.Timed(it, completed) }
                        ?: ActivityHistoryTimeCorrection.NoLive(completed),
                )
            }
        var intervalsChanged = false
        val intervals =
            detail.intervals.map { interval ->
                val started = value(SequenceHistoryTimestampTarget.IntervalStartedAt(interval.id), interval.startedAt)!!
                val ended = value(SequenceHistoryTimestampTarget.IntervalEndedAt(interval.id), interval.endedAt)
                interval.copy(startedAt = started, endedAt = ended).also {
                    intervalsChanged =
                        intervalsChanged ||
                        it != interval
                }
            }
        val correction =
            SequenceHistoryTimingCorrection(
                expectedUpdatedAt = draft.expectedUpdatedAt,
                startedAt =
                    value(SequenceHistoryTimestampTarget.RootStartedAt, detail.root.startedAt)
                        ?.takeIf { it != detail.root.startedAt },
                endedAt =
                    value(SequenceHistoryTimestampTarget.RootEndedAt, detail.root.completedAt)
                        ?.takeIf { it != detail.root.completedAt },
                occurrenceTimings = occurrenceCorrections,
                finalIntervals = intervals.takeIf { intervalsChanged },
                childTimings = childCorrections,
            )
        if (correction == SequenceHistoryTimingCorrection(draft.expectedUpdatedAt)) {
            return SequenceHistoryTimingBuildResult.NoChange
        }
        return SequenceHistoryTimingBuildResult.Ready(
            SequenceHistoryTimingProposal(
                correction,
                hasActiveOverlap(intervals),
                timestampChanges(detail, correction),
            ),
        )
    }

    fun structural(
        detail: SequenceHistoryDetail,
        occurrenceId: SequenceOccurrenceId,
        mode: SequenceHistoryStructuralRemovalMode,
        placements: Map<SequenceIntervalId, OwnerlessIntervalPlacement> = emptyMap(),
    ): SequenceHistoryStructuralProposal? {
        val target = detail.occurrences.singleOrNull { it.occurrenceId == occurrenceId } ?: return null
        if (target.status !in PERFORMED || target.isDeletedFromHistory) return null
        val childId = target.childMutationFacts?.executionId ?: return null
        val targetStart = target.enteredAt ?: return null
        val targetEnd = target.completedAt ?: return null
        val shift = targetEnd.toEpochMilli() - targetStart.toEpochMilli()
        val retained = detail.intervals.filter { it.occurrenceId != occurrenceId }
        if (mode == SequenceHistoryStructuralRemovalMode.LEAVE_GAP) {
            return SequenceHistoryStructuralProposal(
                occurrenceId,
                requireNotNull(occurrenceDescriptor(detail, occurrenceId)),
                mode,
                0,
                emptyMap(),
                SequenceHistoryStructuralRemovalCommand(
                    detail.updatedAt,
                    occurrenceId,
                    childId,
                    mode,
                    detail.root.completedAt,
                    retained,
                ),
                listOf(
                    SequenceHistoryPreviewChange.RemovedOccurrence(
                        requireNotNull(occurrenceDescriptor(detail, occurrenceId)),
                    ),
                ),
            )
        }
        if (shift <= 0) return null
        val finalEnd = detail.root.completedAt.minusMillis(shift)
        val later = detail.occurrences.filter { it.runtimePosition > target.runtimePosition }
        val laterIds = later.mapTo(hashSetOf()) { it.occurrenceId }
        val placementState = linkedMapOf<SequenceIntervalId, OwnerlessIntervalPlacement?>()
        val finalIntervals =
            retained.map { interval ->
                when {
                    interval.occurrenceId in laterIds -> interval.translated(shift)
                    interval.occurrenceId != null -> interval
                    else -> {
                        val translated = interval.translated(shift)
                        val fixedLegal =
                            interval.startedAt >= detail.root.startedAt && requireNotNull(interval.endedAt) <= finalEnd
                        val translatedLegal =
                            interval.startedAt >= targetStart &&
                                translated.startedAt >= detail.root.startedAt &&
                                requireNotNull(translated.endedAt) <= finalEnd
                        val placement =
                            when {
                                fixedLegal && translatedLegal -> placements[interval.id]
                                translatedLegal -> OwnerlessIntervalPlacement.TRANSLATED
                                fixedLegal -> OwnerlessIntervalPlacement.FIXED
                                else -> return null
                            }
                        if (fixedLegal && translatedLegal) placementState[interval.id] = placement
                        if (placement == OwnerlessIntervalPlacement.TRANSLATED) translated else interval
                    }
                }
            }
        val performed = later.filter { it.status in PERFORMED }
        val occurrenceTimings =
            performed.map { occurrence ->
                SequenceOccurrenceTimingCorrection(
                    occurrence.occurrenceId,
                    occurrence.enteredAt?.minusMillis(shift) ?: return null,
                    occurrence.completedAt?.minusMillis(shift) ?: return null,
                )
            }
        val childTimings =
            performed.map { occurrence ->
                val facts = occurrence.childMutationFacts ?: return null
                SequenceStructuralChildTimingCorrection(
                    facts.executionId,
                    facts.startedAt?.let {
                        ActivityHistoryTimeCorrection.Timed(it.minusMillis(shift), facts.completedAt.minusMillis(shift))
                    } ?: ActivityHistoryTimeCorrection.NoLive(facts.completedAt.minusMillis(shift)),
                    facts.pauses.map { pause ->
                        pause.copy(
                            startedAt = pause.startedAt.minusMillis(shift),
                            endedAt = pause.endedAt?.minusMillis(shift),
                        )
                    },
                )
            }
        val command =
            if (placementState.values.any { it == null }) {
                null
            } else {
                SequenceHistoryStructuralRemovalCommand(
                    detail.updatedAt,
                    occurrenceId,
                    childId,
                    mode,
                    finalEnd,
                    finalIntervals,
                    occurrenceTimings,
                    childTimings,
                )
            }
        return SequenceHistoryStructuralProposal(
            occurrenceId,
            requireNotNull(occurrenceDescriptor(detail, occurrenceId)),
            mode,
            shift,
            placementState,
            command,
            command?.let { structuralChanges(detail, it, placementState) }.orEmpty(),
        )
    }

    private fun timestampChanges(
        detail: SequenceHistoryDetail,
        correction: SequenceHistoryTimingCorrection,
    ): List<SequenceHistoryPreviewChange.Timestamp> =
        buildList {
            correction.startedAt?.let {
                add(
                    SequenceHistoryPreviewChange.Timestamp(
                        SequenceHistoryTimestampTarget.RootStartedAt,
                        requireNotNull(detail.root.startedAt),
                        it,
                    ),
                )
            }
            correction.endedAt?.let {
                add(
                    SequenceHistoryPreviewChange.Timestamp(
                        SequenceHistoryTimestampTarget.RootEndedAt,
                        detail.root.completedAt,
                        it,
                    ),
                )
            }
            correction.occurrenceTimings.forEach { timing ->
                val occurrence = detail.occurrences.single { it.occurrenceId == timing.occurrenceId }
                val enteredBefore = occurrence.enteredAt
                val enteredAfter = timing.enteredAt
                if (enteredBefore != enteredAfter && enteredBefore != null && enteredAfter != null) {
                    add(
                        SequenceHistoryPreviewChange.Timestamp(
                            SequenceHistoryTimestampTarget.OccurrenceEnteredAt(timing.occurrenceId),
                            enteredBefore,
                            enteredAfter,
                        ),
                    )
                }
                val completedBefore = occurrence.completedAt
                val completedAfter = timing.completedAt
                if (completedBefore != completedAfter && completedBefore != null && completedAfter != null) {
                    add(
                        SequenceHistoryPreviewChange.Timestamp(
                            SequenceHistoryTimestampTarget.OccurrenceCompletedAt(timing.occurrenceId),
                            completedBefore,
                            completedAfter,
                        ),
                    )
                }
            }
            correction.childTimings.forEach { timing ->
                val occurrence = detail.occurrences.single { it.child?.executionId == timing.executionId }
                occurrence.child?.startedAt?.let { before ->
                    (timing.time as? ActivityHistoryTimeCorrection.Timed)?.startedAt?.let { after ->
                        if (before !=
                            after
                        ) {
                            add(
                                SequenceHistoryPreviewChange.Timestamp(
                                    SequenceHistoryTimestampTarget.ChildStartedAt(occurrence.occurrenceId),
                                    before,
                                    after,
                                ),
                            )
                        }
                    }
                }
                occurrence.child?.completedAt?.let { before ->
                    val after = timing.time.completedAt()
                    if (before !=
                        after
                    ) {
                        add(
                            SequenceHistoryPreviewChange.Timestamp(
                                SequenceHistoryTimestampTarget.ChildCompletedAt(occurrence.occurrenceId),
                                before,
                                after,
                            ),
                        )
                    }
                }
            }
            correction.finalIntervals?.forEach { after ->
                val before = detail.intervals.single { it.id == after.id }
                if (before.startedAt !=
                    after.startedAt
                ) {
                    add(
                        SequenceHistoryPreviewChange.Timestamp(
                            SequenceHistoryTimestampTarget.IntervalStartedAt(after.id),
                            before.startedAt,
                            after.startedAt,
                        ),
                    )
                }
                val endedBefore = before.endedAt
                val endedAfter = after.endedAt
                if (endedBefore != endedAfter &&
                    endedBefore != null &&
                    endedAfter != null
                ) {
                    add(
                        SequenceHistoryPreviewChange.Timestamp(
                            SequenceHistoryTimestampTarget.IntervalEndedAt(after.id),
                            endedBefore,
                            endedAfter,
                        ),
                    )
                }
            }
        }

    private fun structuralChanges(
        detail: SequenceHistoryDetail,
        command: SequenceHistoryStructuralRemovalCommand,
        placements: Map<SequenceIntervalId, OwnerlessIntervalPlacement?>,
    ): List<SequenceHistoryPreviewChange> =
        buildList {
            add(
                SequenceHistoryPreviewChange.RemovedOccurrence(
                    requireNotNull(occurrenceDescriptor(detail, command.occurrenceId)),
                ),
            )
            detail.intervals
                .filter { it.occurrenceId == command.occurrenceId }
                .forEach { add(SequenceHistoryPreviewChange.RemovedInterval(it.id)) }
            if (detail.root.completedAt != command.finalEndedAt) {
                add(
                    SequenceHistoryPreviewChange.Timestamp(
                        SequenceHistoryTimestampTarget.RootEndedAt,
                        detail.root.completedAt,
                        command.finalEndedAt,
                    ),
                )
            }
            command.occurrenceTimings.forEach { timing ->
                val before = detail.occurrences.single { it.occurrenceId == timing.occurrenceId }
                before.enteredAt?.let {
                    add(
                        SequenceHistoryPreviewChange.Timestamp(
                            SequenceHistoryTimestampTarget.OccurrenceEnteredAt(timing.occurrenceId),
                            it,
                            requireNotNull(timing.enteredAt),
                        ),
                    )
                }
                before.completedAt?.let {
                    add(
                        SequenceHistoryPreviewChange.Timestamp(
                            SequenceHistoryTimestampTarget.OccurrenceCompletedAt(timing.occurrenceId),
                            it,
                            requireNotNull(timing.completedAt),
                        ),
                    )
                }
            }
            command.childTimings.forEach { timing ->
                val occurrence =
                    detail.occurrences.single { it.childMutationFacts?.executionId == timing.executionId }
                if (occurrence.status == RuntimeOccurrenceStatus.DELETED_EXECUTION) return@forEach
                val before = requireNotNull(occurrence.childMutationFacts)
                occurrence.child?.startedAt?.let {
                    add(
                        SequenceHistoryPreviewChange.Timestamp(
                            SequenceHistoryTimestampTarget.ChildStartedAt(occurrence.occurrenceId),
                            it,
                            requireNotNull((timing.time as? ActivityHistoryTimeCorrection.Timed)?.startedAt),
                        ),
                    )
                }
                add(
                    SequenceHistoryPreviewChange.Timestamp(
                        SequenceHistoryTimestampTarget.ChildCompletedAt(occurrence.occurrenceId),
                        requireNotNull(occurrence.child?.completedAt),
                        timing.time.completedAt(),
                    ),
                )
                val afterPauses = timing.pauses.associateBy { it.id }
                before.pauses.forEach { pause ->
                    val after = requireNotNull(afterPauses[pause.id])
                    if (pause != after) {
                        add(
                            SequenceHistoryPreviewChange.ChildPauseTimestamp(
                                requireNotNull(occurrenceDescriptor(detail, occurrence.occurrenceId)),
                                pause.id,
                                pause.startedAt,
                                pause.endedAt,
                                after.startedAt,
                                after.endedAt,
                            ),
                        )
                    }
                }
            }
            command.finalIntervals.forEach { after ->
                val before = detail.intervals.single { it.id == after.id }
                if (before.startedAt !=
                    after.startedAt
                ) {
                    add(
                        SequenceHistoryPreviewChange.Timestamp(
                            SequenceHistoryTimestampTarget.IntervalStartedAt(after.id),
                            before.startedAt,
                            after.startedAt,
                        ),
                    )
                }
                val endedBefore = before.endedAt
                val endedAfter = after.endedAt
                if (endedBefore != endedAfter &&
                    endedBefore != null &&
                    endedAfter != null
                ) {
                    add(
                        SequenceHistoryPreviewChange.Timestamp(
                            SequenceHistoryTimestampTarget.IntervalEndedAt(after.id),
                            endedBefore,
                            endedAfter,
                        ),
                    )
                }
            }
            placements.forEach { (id, placement) ->
                placement?.let { add(SequenceHistoryPreviewChange.OwnerlessPlacement(id, it)) }
            }
        }

    private fun ActivityHistoryTimeCorrection.completedAt(): Instant =
        when (this) {
            is ActivityHistoryTimeCorrection.Timed -> completedAt
            is ActivityHistoryTimeCorrection.NoLive -> completedAt
        }

    private fun hasActiveOverlap(intervals: List<SequenceInterval>): Boolean {
        var latestEnd: Instant? = null
        intervals
            .asSequence()
            .filter { it.kind == SequenceIntervalKind.ACTIVE_STEP }
            .sortedBy(SequenceInterval::startedAt)
            .forEach { interval ->
                val end = requireNotNull(interval.endedAt)
                if (latestEnd != null && interval.startedAt < latestEnd) return true
                if (latestEnd == null || end > latestEnd) latestEnd = end
            }
        return false
    }

    private fun SequenceInterval.translated(millis: Long) =
        copy(startedAt = startedAt.minusMillis(millis), endedAt = endedAt?.minusMillis(millis))

    private val PERFORMED = setOf(RuntimeOccurrenceStatus.COMPLETED, RuntimeOccurrenceStatus.DELETED_EXECUTION)
}
