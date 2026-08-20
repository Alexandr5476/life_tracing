package com.alexandr5476.lifetracing.data.persistence

import com.alexandr5476.lifetracing.domain.ActiveSession
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.ActiveSessionValidator
import com.alexandr5476.lifetracing.domain.ActivityExecutionId
import com.alexandr5476.lifetracing.domain.SequenceExecutionId
import java.time.Instant

internal fun ActiveSession.toEntity(): ActiveSessionEntity {
    ActiveSessionValidator.requireValid(this)
    return ActiveSessionEntity(
        ACTIVE_SESSION_SINGLETON_ID,
        kind.name,
        activityExecutionId?.value,
        sequenceExecutionId?.value,
        state.name,
        updatedAt.toEpochMilli(),
    )
}

internal fun ActiveSessionEntity.toDomain(): ActiveSession =
    ActiveSession(
        ActiveSessionKind.valueOf(sessionKind),
        ActiveSessionState.valueOf(state),
        activityExecutionId?.let(::ActivityExecutionId),
        sequenceExecutionId?.let(::SequenceExecutionId),
        Instant.ofEpochMilli(updatedAtMs),
    ).also(ActiveSessionValidator::requireValid)

internal const val ACTIVE_SESSION_SINGLETON_ID = 1
