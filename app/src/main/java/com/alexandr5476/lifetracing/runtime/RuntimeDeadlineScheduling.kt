package com.alexandr5476.lifetracing.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import java.time.Instant

interface RuntimeDeadlineScheduler {
    fun schedule(deadline: RuntimeDeadline)

    fun cancel()

    fun canScheduleExactRuntimeDeadlines(): Boolean
}

class AndroidRuntimeDeadlineScheduler internal constructor(
    private val backend: RuntimeAlarmBackend,
) : RuntimeDeadlineScheduler {
    constructor(context: Context) : this(AndroidRuntimeAlarmBackend(context))

    override fun schedule(deadline: RuntimeDeadline) {
        if (backend.canScheduleExactAlarms()) backend.scheduleExact(deadline) else backend.scheduleInexact(deadline)
    }

    override fun cancel() = backend.cancel()

    override fun canScheduleExactRuntimeDeadlines(): Boolean = backend.canScheduleExactAlarms()
}

internal interface RuntimeAlarmBackend {
    fun canScheduleExactAlarms(): Boolean

    fun scheduleExact(deadline: RuntimeDeadline)

    fun scheduleInexact(deadline: RuntimeDeadline)

    fun cancel()
}

private class AndroidRuntimeAlarmBackend(
    context: Context,
) : RuntimeAlarmBackend {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)

    override fun scheduleExact(deadline: RuntimeDeadline) {
        val pendingIntent = RuntimeDeadlineIntentCodec.pendingIntent(applicationContext, deadline)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline.at.toEpochMilli(), pendingIntent)
        Log.i(TAG, "runtime_alarm_scheduled mode=exact kind=${deadline.kind}")
    }

    override fun scheduleInexact(deadline: RuntimeDeadline) {
        val pendingIntent = RuntimeDeadlineIntentCodec.pendingIntent(applicationContext, deadline)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline.at.toEpochMilli(), pendingIntent)
        Log.i(TAG, "runtime_alarm_scheduled mode=inexact kind=${deadline.kind}")
    }

    override fun cancel() {
        RuntimeDeadlineIntentCodec.existingPendingIntent(applicationContext)?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.i(TAG, "runtime_alarm_cancelled")
        }
    }

    override fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private companion object {
        const val TAG = "LifeTracingRuntime"
    }
}

object RuntimeDeadlineIntentCodec {
    const val ACTION_RUNTIME_DEADLINE = "com.alexandr5476.lifetracing.action.RUNTIME_DEADLINE"
    private const val EXTRA_KIND = "deadline_kind"
    private const val EXTRA_SESSION_KIND = "session_kind"
    private const val EXTRA_EXECUTION_ID = "execution_id"
    private const val EXTRA_OCCURRENCE_ID = "occurrence_id"
    private const val EXTRA_DEADLINE_MS = "deadline_ms"
    private const val REQUEST_CODE = 0

    fun pendingIntent(
        context: Context,
        deadline: RuntimeDeadline,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent(context, deadline),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun existingPendingIntent(context: Context): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RuntimeDeadlineReceiver::class.java).setAction(ACTION_RUNTIME_DEADLINE),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    fun intent(
        context: Context,
        deadline: RuntimeDeadline,
    ): Intent =
        Intent(context, RuntimeDeadlineReceiver::class.java)
            .setAction(ACTION_RUNTIME_DEADLINE)
            .putExtra(EXTRA_KIND, deadline.kind.name)
            .putExtra(EXTRA_SESSION_KIND, deadline.sessionKind.name)
            .putExtra(EXTRA_EXECUTION_ID, deadline.executionId)
            .putExtra(EXTRA_OCCURRENCE_ID, deadline.expectedOccurrenceId?.value)
            .putExtra(EXTRA_DEADLINE_MS, deadline.at.toEpochMilli())

    @Suppress("ReturnCount") // Intent extras are an external trust boundary; each malformed field fails closed.
    fun decode(intent: Intent?): RuntimeDeadline? {
        if (intent?.action != ACTION_RUNTIME_DEADLINE) return null
        val executionId = intent.getStringExtra(EXTRA_EXECUTION_ID)?.takeIf(String::isNotBlank) ?: return null
        val deadlineMs = intent.getLongExtra(EXTRA_DEADLINE_MS, Long.MIN_VALUE)
        if (deadlineMs == Long.MIN_VALUE) return null
        val kind =
            intent.getStringExtra(EXTRA_KIND)?.let { runCatching { RuntimeDeadlineKind.valueOf(it) }.getOrNull() }
                ?: return null
        val sessionKind =
            intent.getStringExtra(EXTRA_SESSION_KIND)?.let { runCatching { ActiveSessionKind.valueOf(it) }.getOrNull() }
                ?: return null
        val occurrenceId = intent.getStringExtra(EXTRA_OCCURRENCE_ID)?.let(::SequenceOccurrenceId)
        if ((kind == RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO) !=
            (sessionKind == ActiveSessionKind.ACTIVITY)
        ) {
            return null
        }
        if (kind != RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO && occurrenceId == null) return null
        return RuntimeDeadline(Instant.ofEpochMilli(deadlineMs), kind, sessionKind, executionId, occurrenceId)
    }
}
