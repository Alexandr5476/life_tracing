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
    RuntimePlatformFixture.requireNotificationVacant(context)
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
        assertion(publisher, manager)
    } finally {
        try {
            manager.cancel(AndroidRuntimeNotificationPublisher.NOTIFICATION_ID)
            awaitRuntimeSystemState("notification cleanup") {
                manager.activeNotifications.none {
                    it.id == AndroidRuntimeNotificationPublisher.NOTIFICATION_ID && it.tag == null
                }
            }
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

/** AOSP AlarmManager flags printed by Alarm.dump(), independent of the batching window. */
internal fun assertRuntimeAlarmAllowsIdle(
    record: String,
    exact: Boolean,
) {
    val encoded = requireNotNull(Regex("\\bflags=0x([0-9a-fA-F]+)\\b").find(record)) { record }.groupValues[1]
    val flags = encoded.toInt(16)
    // FLAG_ALLOW_WHILE_IDLE = 1<<2. Since S, the service converts inexact AWI to
    // FLAG_ALLOW_WHILE_IDLE_COMPAT = 1<<5. Reject unrestricted exemptions (1<<3)
    // that could otherwise hide a regression to set()/setExact().
    val expected = if (!exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0x20 else 0x04
    assertEquals("Actual idle allowance api=${Build.VERSION.SDK_INT}: $record", expected, flags and 0x2c)
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
