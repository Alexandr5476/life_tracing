package com.alexandr5476.lifetracing.launcher

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

internal fun LauncherCommandState.isCommittedResult(): Boolean =
    this is LauncherCommandState.Committed || this is LauncherCommandState.CommittedCoordinationFailure
