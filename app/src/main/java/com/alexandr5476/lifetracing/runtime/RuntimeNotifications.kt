package com.alexandr5476.lifetracing.runtime

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.domain.ActiveRuntime
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActiveSessionState
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback

interface RuntimeNotificationPublisher {
    fun publish(
        runtime: ActiveRuntime?,
        completion: RuntimeDeadlineFeedback?,
    )

    fun canPostRuntimeNotifications(): Boolean
}

class AndroidRuntimeNotificationPublisher(
    context: Context,
) : RuntimeNotificationPublisher {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.runtime_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = applicationContext.getString(R.string.runtime_notification_channel_description) },
        )
    }

    override fun publish(
        runtime: ActiveRuntime?,
        completion: RuntimeDeadlineFeedback?,
    ) {
        if (!canPostRuntimeNotifications()) return
        if (runtime == null && completion == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val title =
            if (completion != null) {
                R.string.runtime_notification_due
            } else if (runtime?.session?.kind == ActiveSessionKind.SEQUENCE) {
                R.string.runtime_notification_sequence
            } else {
                R.string.runtime_notification_activity
            }
        val state =
            when (runtime?.session?.state) {
                ActiveSessionState.PAUSED -> R.string.runtime_notification_paused
                ActiveSessionState.WAITING_NEXT -> R.string.runtime_notification_waiting
                ActiveSessionState.RUNNING -> R.string.runtime_notification_running
                null -> R.string.runtime_notification_completed
            }
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(applicationContext.getString(title))
                .setContentText(applicationContext.getString(state))
                .setOngoing(runtime != null)
                .setOnlyAlertOnce(completion == null)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun canPostRuntimeNotifications(): Boolean =
        manager.areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            )

    companion object {
        const val CHANNEL_ID = "active_runtime"
        const val NOTIFICATION_ID = 1001
    }
}
