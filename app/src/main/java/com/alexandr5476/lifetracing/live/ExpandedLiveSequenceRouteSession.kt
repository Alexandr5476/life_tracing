package com.alexandr5476.lifetracing.live

import androidx.lifecycle.ViewModel
import com.alexandr5476.lifetracing.domain.SequenceExecutionId

internal class ExpandedLiveSequenceRouteSessionOwner : ViewModel() {
    private var session: ExpandedLiveSequenceRouteSession? = null

    val activeSession: ExpandedLiveSequenceRouteSession?
        get() = session

    fun acquire(
        executionId: SequenceExecutionId,
        createController: () -> ExpandedLiveSequenceController,
    ): ExpandedLiveSequenceRouteSession {
        session?.let {
            require(it.executionId == executionId) { "Only one expanded Sequence route may be retained" }
            return it
        }
        return ExpandedLiveSequenceRouteSession(executionId, createController()).also { session = it }
    }

    fun release(expected: ExpandedLiveSequenceRouteSession) {
        if (session !== expected) return
        expected.controller.close()
        session = null
    }

    override fun onCleared() {
        session?.controller?.close()
        session = null
    }
}

internal data class ExpandedLiveSequenceRouteSession(
    val executionId: SequenceExecutionId,
    val controller: ExpandedLiveSequenceController,
)
