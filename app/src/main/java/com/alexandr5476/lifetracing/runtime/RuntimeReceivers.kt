package com.alexandr5476.lifetracing.runtime

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alexandr5476.lifetracing.LifeTracingRuntimeGraph
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import kotlinx.coroutines.launch

class RuntimeDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val operation = RuntimeBroadcastOperation.deadline(intent)
        if (operation == null) {
            android.util.Log.w(TAG, "malformed_runtime_alarm_ignored")
            return
        }
        val pending = goAsync()
        launchRuntimeBroadcast(context, operation, pending::finish)
    }
}

class RuntimeRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val operation = RuntimeBroadcastOperation.recovery(intent.action) ?: return
        val pending = goAsync()
        launchRuntimeBroadcast(context, operation, pending::finish)
    }
}

/** Both receivers use this allowlist and dispatch boundary before acquiring the cold runtime graph. */
internal sealed interface RuntimeBroadcastOperation {
    data class Deadline(
        val expected: RuntimeDeadline,
    ) : RuntimeBroadcastOperation

    data object Boot : RuntimeBroadcastOperation

    data object TimeChanged : RuntimeBroadcastOperation

    data object Reschedule : RuntimeBroadcastOperation

    suspend fun dispatch(coordinator: AndroidRuntimeCoordinator) {
        when (this) {
            is Deadline -> coordinator.onDeadlineSignal(expected)
            Boot -> coordinator.onBootCompleted()
            TimeChanged -> coordinator.onSystemTimeChanged()
            Reschedule -> coordinator.recoverAndSchedule()
        }
    }

    companion object {
        fun deadline(intent: Intent): RuntimeBroadcastOperation? =
            RuntimeDeadlineIntentCodec.decode(intent)?.let(::Deadline)

        fun recovery(action: String?): RuntimeBroadcastOperation? =
            when (action) {
                Intent.ACTION_BOOT_COMPLETED -> Boot
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> TimeChanged
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Reschedule
                else -> null
            }
    }
}

@Suppress("TooGenericExceptionCaught") // Graph initialization failure must still finish the broadcast.
private fun launchRuntimeBroadcast(
    context: Context,
    operation: RuntimeBroadcastOperation,
    finish: () -> Unit,
) {
    val graph =
        try {
            LifeTracingRuntimeGraph.from(context)
        } catch (error: RuntimeException) {
            finish()
            throw error
        }
    graph.scope.launch {
        finishBroadcast(finish) { operation.dispatch(graph.coordinator) }
    }
}

internal suspend fun finishBroadcast(
    finish: () -> Unit,
    block: suspend () -> Unit,
) {
    try {
        block()
    } finally {
        finish()
    }
}

private const val TAG = "LifeTracingRuntime"
