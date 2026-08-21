package com.alexandr5476.lifetracing.runtime

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alexandr5476.lifetracing.LifeTracingRuntimeGraph
import kotlinx.coroutines.launch

class RuntimeDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val deadline = RuntimeDeadlineIntentCodec.decode(intent)
        if (deadline == null) {
            android.util.Log.w(TAG, "malformed_runtime_alarm_ignored")
            return
        }
        val pending = goAsync()
        val graph = LifeTracingRuntimeGraph.from(context)
        graph.scope.launch {
            finishBroadcast(pending::finish) { graph.coordinator.onDeadlineSignal(deadline) }
        }
    }
}

class RuntimeRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val pending = goAsync()
        val graph = LifeTracingRuntimeGraph.from(context)
        graph.scope.launch {
            finishBroadcast(pending::finish) {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED -> graph.coordinator.onBootCompleted()
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    -> graph.coordinator.onSystemTimeChanged()
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                        graph.coordinator.recoverAndSchedule()
                }
            }
        }
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
