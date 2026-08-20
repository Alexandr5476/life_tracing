package com.alexandr5476.lifetracing.domain

import java.time.Instant

enum class ActiveSessionKind {
    ACTIVITY,
    SEQUENCE,
}

enum class ActiveSessionState {
    RUNNING,
    PAUSED,
    WAITING_NEXT,
}

data class ActiveSession(
    val kind: ActiveSessionKind,
    val state: ActiveSessionState,
    val activityExecutionId: ActivityExecutionId?,
    val sequenceExecutionId: SequenceExecutionId?,
    val updatedAt: Instant,
)

object ActiveSessionValidator {
    fun requireValid(session: ActiveSession) {
        when (session.kind) {
            ActiveSessionKind.ACTIVITY ->
                require(
                    session.activityExecutionId != null &&
                        session.sequenceExecutionId == null &&
                        session.state != ActiveSessionState.WAITING_NEXT,
                ) { "Activity session requires only Activity ownership and cannot wait for a next Step" }
            ActiveSessionKind.SEQUENCE ->
                require(session.activityExecutionId == null && session.sequenceExecutionId != null) {
                    "Sequence session requires only Sequence ownership"
                }
        }
    }
}
