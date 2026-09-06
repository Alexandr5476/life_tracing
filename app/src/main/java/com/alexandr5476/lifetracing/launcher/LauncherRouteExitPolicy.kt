package com.alexandr5476.lifetracing.launcher

import androidx.lifecycle.ViewModel

/** Keeps route disposal outside the durable transaction's unresolved boundary. */
internal class LauncherRouteExitPolicy {
    private var committedResultHandled = false

    fun requestExit(
        command: LauncherCommandState,
        onBack: () -> Unit,
        onCommitted: () -> Unit,
    ) {
        when {
            command.isCommittedResult() -> deliverCommittedResult(onCommitted)
            command != LauncherCommandState.Committing -> onBack()
        }
    }

    fun onCommand(
        command: LauncherCommandState,
        onCommitted: () -> Unit,
    ) {
        if (command.isCommittedResult()) deliverCommittedResult(onCommitted)
    }

    private fun deliverCommittedResult(onCommitted: () -> Unit) {
        if (committedResultHandled) return
        committedResultHandled = true
        onCommitted()
    }
}

internal class StartActivityRouteSessionOwner : ViewModel() {
    private var session: StartActivityRouteSession? = null

    val activeSession: StartActivityRouteSession?
        get() = session

    fun acquire(createController: () -> StartActivityController): StartActivityRouteSession =
        session ?: StartActivityRouteSession(createController(), LauncherRouteExitPolicy()).also { session = it }

    fun release(expected: StartActivityRouteSession) {
        if (session !== expected) return
        expected.controller.close()
        session = null
    }

    override fun onCleared() {
        session?.controller?.close()
        session = null
    }
}

internal class StartActivityRouteSession(
    val controller: StartActivityController,
    val exitPolicy: LauncherRouteExitPolicy,
)

internal fun LauncherCommandState.isCommittedResult(): Boolean =
    this is LauncherCommandState.Committed || this is LauncherCommandState.CommittedCoordinationFailure
