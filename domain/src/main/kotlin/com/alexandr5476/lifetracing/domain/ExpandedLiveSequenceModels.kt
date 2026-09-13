@file:Suppress("LongMethod") // One pass validates and projects the complete bounded occurrence graph.

package com.alexandr5476.lifetracing.domain

sealed interface ExpandedLiveSequenceRead {
    data class Active(
        val value: ExpandedLiveSequence,
    ) : ExpandedLiveSequenceRead

    data object StaleOrInactive : ExpandedLiveSequenceRead
}

data class ExpandedLiveSequence(
    val runtime: ActiveSequenceRuntime,
    val state: ActiveSequenceState,
    val occurrences: List<ExpandedLiveSequenceOccurrence>,
)

data class ExpandedLiveSequenceOccurrence(
    val occurrence: RuntimeOccurrence,
    val activity: ActivityConfigSnapshot,
    val childExecution: ActivityExecution?,
    val effectiveSettings: EffectiveSequenceStepSettings,
)

object ExpandedLiveSequenceProjector {
    fun project(
        runtime: ActiveSequenceRuntime,
        children: List<ActivityExecution>,
    ): ExpandedLiveSequence {
        SequenceExecutionValidator.requireValid(runtime.execution, runtime.snapshot)
        val state = ActiveSequenceStateResolver.resolve(runtime)
        val occurrences = runtime.execution.occurrences.sortedBy(RuntimeOccurrence::runtimePosition)
        RuntimeOccurrenceCardinalityPolicy.requireSupported(occurrences.size.toLong())
        val occurrencesById = occurrences.associateBy(RuntimeOccurrence::id)
        require(occurrencesById.size == occurrences.size) { "Expanded Sequence occurrence identities must be unique" }
        val childrenByOccurrence = HashMap<SequenceOccurrenceId, ActivityExecution>(children.size)
        children.forEach { child ->
            require(child.context == ActivityExecutionContext.SEQUENCE_CHILD) {
                "Expanded Sequence child query returned a non-child execution"
            }
            require(child.sequenceExecutionId == runtime.execution.id) {
                "Expanded Sequence child belongs to another root execution"
            }
            val occurrenceId =
                requireNotNull(child.sequenceOccurrenceId) {
                    "Expanded Sequence child is missing its occurrence linkage"
                }
            val occurrence =
                requireNotNull(occurrencesById[occurrenceId]) {
                    "Expanded Sequence child references another root occurrence"
                }
            require(childrenByOccurrence.put(occurrenceId, child) == null) {
                "At most one child execution may belong to an occurrence"
            }
            require(child.snapshotId == occurrence.activitySnapshotId) {
                "Expanded Sequence child snapshot must match its occurrence"
            }
            ActivityExecutionValidator.requireValid(child, runtime.activitySnapshots.getValue(child.snapshotId))
        }

        val firstOccurrenceId = occurrences.firstOrNull()?.id
        val steps =
            runtime.snapshot.nodes
                .asSequence()
                .flatMap { node ->
                    when (node) {
                        is SequenceSnapshotActivityStep -> sequenceOf(node)
                        is SequenceSnapshotRepeatBlock -> node.children.asSequence()
                    }
                }.associateBy(SequenceSnapshotActivityStep::id)
        val requiredActivitySnapshots =
            steps.values.mapTo(hashSetOf(), SequenceSnapshotActivityStep::activitySnapshotId).apply {
                addAll(occurrences.map(RuntimeOccurrence::activitySnapshotId))
            }
        require(runtime.activitySnapshots.keys.containsAll(requiredActivitySnapshots)) {
            "Expanded Sequence graph is missing frozen Activity snapshot metadata"
        }
        val expectedCurrentChild =
            runtime.execution.currentOccurrenceId?.let(childrenByOccurrence::get)
        require(runtime.currentChild == expectedCurrentChild) {
            "Expanded Sequence current child disagrees with the canonical runtime"
        }
        val projected =
            occurrences.map { occurrence ->
                val activity =
                    requireNotNull(runtime.activitySnapshots[occurrence.activitySnapshotId]) {
                        "Expanded Sequence occurrence references a missing Activity snapshot"
                    }
                val child = childrenByOccurrence[occurrence.id]
                requireValidChildState(occurrence, activity, child, runtime)
                val isFirstStep = occurrence.id == firstOccurrenceId && !occurrence.isRuntimeAdded
                ExpandedLiveSequenceOccurrence(
                    occurrence,
                    activity,
                    child,
                    occurrence.sourceSequenceSnapshotNodeId?.let { sourceId ->
                        EffectiveSequenceStepSettingsResolver.resolve(
                            requireNotNull(steps[sourceId]) {
                                "Expanded Sequence occurrence references a missing frozen Step"
                            },
                            activity,
                            runtime.snapshot.settings,
                            isFirstStep,
                        )
                    } ?: EffectiveSequenceStepSettingsResolver.resolve(
                        activity,
                        runtime.snapshot.settings,
                        isFirstStep = false,
                    ),
                )
            }
        return ExpandedLiveSequence(runtime, state, projected)
    }

    private fun requireValidChildState(
        occurrence: RuntimeOccurrence,
        activity: ActivityConfigSnapshot,
        child: ActivityExecution?,
        runtime: ActiveSequenceRuntime,
    ) {
        when (occurrence.status) {
            RuntimeOccurrenceStatus.NOT_STARTED,
            RuntimeOccurrenceStatus.SKIPPED,
            -> require(child == null) { "Unperformed occurrence cannot have a child execution" }
            RuntimeOccurrenceStatus.COMPLETED ->
                require(child?.status == ActivityExecutionStatus.COMPLETED && child.deletedAt == null) {
                    "Completed occurrence requires one retained completed child execution"
                }
            RuntimeOccurrenceStatus.DELETED_EXECUTION ->
                require(child?.deletedAt != null) { "Deleted occurrence requires its deleted child execution" }
            RuntimeOccurrenceStatus.CURRENT -> {
                if (activity.timeTrackingMode == TimeTrackingMode.NO_LIVE_TRACKING) {
                    require(child == null) { "Current No-live occurrence cannot have a child execution" }
                } else {
                    val expected =
                        if (runtime.session.state == ActiveSessionState.PAUSED) {
                            ActivityExecutionStatus.PAUSED
                        } else {
                            ActivityExecutionStatus.RUNNING
                        }
                    require(child?.status == expected && child.deletedAt == null) {
                        "Current timed occurrence requires a matching current child execution"
                    }
                }
                require(runtime.currentChild == child) {
                    "Expanded Sequence current child disagrees with the canonical runtime"
                }
            }
        }
    }
}
