package com.alexandr5476.lifetracing.runtime

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun runtimeShell(command: String): String =
    ParcelFileDescriptor
        .AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .bufferedReader()
        .use { it.readText() }

/** Poll asynchronous system state with a deadline instead of assuming notification delivery latency. */
internal fun awaitRuntimeSystemState(
    description: String,
    condition: () -> Boolean,
) {
    val until = SystemClock.elapsedRealtime() + 10_000
    val pause = CountDownLatch(1)
    while (!condition()) {
        check(SystemClock.elapsedRealtime() < until) { "Timed out waiting for $description" }
        pause.await(25, TimeUnit.MILLISECONDS)
    }
}

/** Scope real framework permission delegation and the pre-33 application-op to this assertion. */
internal fun withRuntimeNotificationsEnabled(
    context: Context,
    assertion: (AndroidRuntimeNotificationPublisher, NotificationManager) -> Unit,
) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val manager = context.getSystemService(NotificationManager::class.java)
    val previous = manager.activeNotifications.filter { it.id == AndroidRuntimeNotificationPublisher.NOTIFICATION_ID }
    val previouslyEnabled = manager.areNotificationsEnabled()
    val previousPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            null
        }
    val originalOp =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationAppOp(context)
        } else {
            null
        }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Persisted revocation kills instrumentation. Delegate the real framework permission instead.
            automation.adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS)
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
            )
        } else {
            runtimeShell("appops set ${context.packageName} POST_NOTIFICATION allow")
        }
        val publisher = AndroidRuntimeNotificationPublisher(context)
        assertTrue("NotificationManager must allow publication", publisher.canPostRuntimeNotifications())
        manager.cancel(AndroidRuntimeNotificationPublisher.NOTIFICATION_ID)
        awaitRuntimeSystemState("clean notification slot") {
            manager.activeNotifications.none { it.id == AndroidRuntimeNotificationPublisher.NOTIFICATION_ID }
        }
        assertion(publisher, manager)
    } finally {
        try {
            manager.cancel(AndroidRuntimeNotificationPublisher.NOTIFICATION_ID)
            awaitRuntimeSystemState("notification cleanup") {
                manager.activeNotifications.none { it.id == AndroidRuntimeNotificationPublisher.NOTIFICATION_ID }
            }
            previous.forEach { manager.notify(it.tag, it.id, it.notification) }
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                automation.dropShellPermissionIdentity()
                assertEquals(previousPermission, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                runtimeShell("appops set ${context.packageName} POST_NOTIFICATION $originalOp")
                assertEquals(originalOp, notificationAppOp(context))
            }
            assertEquals(previouslyEnabled, manager.areNotificationsEnabled())
        }
    }
}

private fun notificationAppOp(context: Context): String {
    val output = runtimeShell("appops get ${context.packageName} POST_NOTIFICATION")
    val mode = Regex("POST_NOTIFICATION: (allow|ignore|deny|default|foreground)").find(output)?.groupValues?.get(1)
    check(mode != null || output.contains("No operations")) { "Unrecognized notification app-op: $output" }
    return mode ?: "default"
}

/** Only pending RTC_WAKEUP entries, excluding delivered-alarm statistics and removal history. */
internal fun pendingRuntimeAlarmRecords(
    dump: String,
    packageName: String,
): List<String> {
    check(dump.contains("Current Alarm Manager state:")) { "AlarmManager diagnostics unavailable: $dump" }
    val pending =
        dump
            .substringBefore("Pending user blocked background alarms:")
            .substringBefore("Idle mode state:")
            .substringBefore("Past-due non-wakeup alarms:")
    val header = Regex("(?m)^\\s*RTC_WAKEUP #\\d+: Alarm\\{[^\\n]* ${Regex.escape(packageName)}\\}[^\\n]*$")
    return header
        .findAll(pending)
        .map { match -> pending.substring(match.range.first).substringBefore("\n\n") }
        .filter { it.contains("tag=*walarm*:${RuntimeDeadlineIntentCodec.ACTION_RUNTIME_DEADLINE}") }
        .toList()
}
