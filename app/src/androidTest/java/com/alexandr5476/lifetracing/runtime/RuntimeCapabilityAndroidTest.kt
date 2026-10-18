package com.alexandr5476.lifetracing.runtime

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.R
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class RuntimeCapabilityAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val deadline =
        RuntimeDeadline(
            // Match durable runtime timestamps and the Intent's epoch-millisecond payload.
            Instant.ofEpochMilli(Instant.now().toEpochMilli()).plusSeconds(86_400),
            RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO,
            ActiveSessionKind.ACTIVITY,
            "platform-smoke-never-a-durable-execution",
        )

    @get:Rule
    val platformFixture: TestRule = RuntimePlatformFixtureRule(context)

    @Test
    fun realSchedulerInspectsSupportedCapabilityAndReplacesAndCancelsOneAlarm() {
        val scheduler = AndroidRuntimeDeadlineScheduler(context)
        val expected =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            } else {
                true // This path must never invoke the API-31 framework method.
            }
        assertEquals(expected, scheduler.canScheduleExactRuntimeDeadlines())
        scheduler.schedule(deadline)
        val token = RuntimeDeadlineIntentCodec.existingPendingIntent(context)
        assertNotNull(token)
        val replacement = deadline.copy(executionId = "replacement-smoke", at = deadline.at.plusSeconds(60))
        scheduler.schedule(replacement)
        assertEquals(token, RuntimeDeadlineIntentCodec.existingPendingIntent(context))
        assertEquals(replacement, deliveredDeadline(requireNotNull(token)))
        scheduler.cancel()
        assertNull(RuntimeDeadlineIntentCodec.existingPendingIntent(context))
    }

    @Test
    fun realAlarmManagerRegistersTheAuthorizedModeAndCancellationRemovesIt() {
        val scheduler = AndroidRuntimeDeadlineScheduler(context)
        // Fresh installs authorize exact alarms through API 33; API 34+ deny special access by default.
        // The matrix requires both real modes without revoking access and killing instrumentation.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        assertEquals("Unexpected fresh-install exact-alarm state", exact, scheduler.canScheduleExactRuntimeDeadlines())

        fun records() = pendingRuntimeAlarmRecords(runtimeShell("dumpsys alarm"), context.packageName)

        // Negative control: a PendingIntent alone must not be mistaken for a registered alarm.
        RuntimeDeadlineIntentCodec.pendingIntent(context, deadline)
        assertTrue(records().isEmpty())
        scheduler.schedule(deadline)
        val registered = records()
        assertEquals("Expected one registered AlarmManager alarm: $registered", 1, registered.size)
        val record = registered.single()
        assertTrue(record, Regex("(?:origWhen|when) ${deadline.at.toEpochMilli()}\\b").containsMatchIn(record))
        val window = requireNotNull(Regex("\\bwindow=(\\S+)").find(record)) { record }.groupValues[1]
        assertEquals("Actual AlarmManager batching window: $record", exact, window == "0")
        assertRuntimeAlarmAllowsIdle(record, exact)
        assertTrue(record, record.contains("repeatInterval=0"))
        println("LifeTracing AlarmManager api=${Build.VERSION.SDK_INT} exact=$exact pending=$record")
        scheduler.cancel()
        assertTrue("Cancellation must remove the system alarm", records().isEmpty())
    }

    @Test
    fun realRuntimeAndCompletionNotificationsArePostedReplacedAndCancelled() {
        val name = "notification-platform-${System.nanoTime()}"
        try {
            RuntimeRecoveryStore(context, name).use { store ->
                val template =
                    store.authoring.createActivityTemplate(
                        ActivityTemplateDraft("Notification smoke", null, TimeTrackingMode.STOPWATCH, null),
                        createdAt = deadline.at,
                    )
                store.activityCommands.startLive(
                    ActivityEntrySource.Template(template.id),
                    deadline.at,
                    deadline.at,
                    ZoneOffset.UTC,
                )
                val runtime = requireNotNull(store.live.getActiveRuntime())
                val facts = store.durableFacts()
                withRuntimeNotificationsEnabled(context) { publisher, manager ->
                    fun notification() =
                        manager.activeNotifications.singleOrNull {
                            it.id == AndroidRuntimeNotificationPublisher.NOTIFICATION_ID && it.tag == null
                        }

                    publisher.publish(runtime, null)
                    awaitRuntimeSystemState("posted runtime notification") { notification() != null }
                    val running = requireNotNull(notification())
                    assertEquals(context.packageName, running.packageName)
                    assertEquals(AndroidRuntimeNotificationPublisher.CHANNEL_ID, running.notification.channelId)
                    assertEquals(
                        context.getString(R.string.runtime_notification_activity),
                        running.notification.extras.getString(Notification.EXTRA_TITLE),
                    )
                    assertEquals(
                        context.getString(R.string.runtime_notification_running),
                        running.notification.extras.getString(Notification.EXTRA_TEXT),
                    )
                    assertTrue(running.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
                    assertTrue(running.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                    assertEquals(Notification.VISIBILITY_PRIVATE, running.notification.visibility)
                    assertEquals(Notification.CATEGORY_STOPWATCH, running.notification.category)

                    publisher.publish(null, RuntimeDeadlineFeedback(deadline, false, false))
                    awaitRuntimeSystemState("completion replacing runtime notification") {
                        notification()?.notification?.extras?.getString(Notification.EXTRA_TITLE) ==
                            context.getString(R.string.runtime_notification_due)
                    }
                    val completed = requireNotNull(notification()).notification
                    assertEquals(AndroidRuntimeNotificationPublisher.CHANNEL_ID, completed.channelId)
                    assertEquals(
                        context.getString(R.string.runtime_notification_completed),
                        completed.extras.getString(Notification.EXTRA_TEXT),
                    )
                    assertEquals(0, completed.flags and Notification.FLAG_ONGOING_EVENT)
                    assertEquals(0, completed.flags and Notification.FLAG_ONLY_ALERT_ONCE)
                    assertEquals(Notification.VISIBILITY_PRIVATE, completed.visibility)
                    assertEquals(Notification.CATEGORY_STOPWATCH, completed.category)
                    publisher.publish(null, null)
                    awaitRuntimeSystemState("cancelled system notification") { notification() == null }
                    assertEquals(facts, store.durableFacts())
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun grantedDeniedAndRevokedExactAccessChooseOneLegalSchedulingPath() {
        for (allowed in listOf(false, true)) {
            val backend = RecordingCapabilityAlarmBackend(allowed)
            val scheduler = AndroidRuntimeDeadlineScheduler(backend)
            assertEquals(allowed, scheduler.canScheduleExactRuntimeDeadlines())
            scheduler.schedule(deadline)
            assertEquals(if (allowed) listOf(deadline) else emptyList<RuntimeDeadline>(), backend.exact)
            assertEquals(if (allowed) emptyList<RuntimeDeadline>() else listOf(deadline), backend.inexact)
            scheduler.cancel()
            assertEquals(1, backend.cancellations)
        }
        val raced = RecordingCapabilityAlarmBackend(true, rejectExact = true)
        AndroidRuntimeDeadlineScheduler(raced).schedule(deadline)
        assertEquals(listOf(deadline), raced.exact)
        assertEquals(listOf(deadline), raced.inexact)
    }

    @Test
    fun realNotificationCapabilityUsesOnlyTheSupportedPermissionBoundary() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val publisher = AndroidRuntimeNotificationPublisher(context)
        val permissionAllows =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true // POST_NOTIFICATIONS is not a runtime permission before API 33.
            }
        assertEquals(manager.areNotificationsEnabled() && permissionAllows, publisher.canPostRuntimeNotifications())
        assertNotNull(manager.getNotificationChannel(AndroidRuntimeNotificationPublisher.CHANNEL_ID))
    }

    @Test
    fun deniedNotificationsNeverReachPublicationEvenForCompletion() {
        val backend = DeniedCapabilityNotificationBackend()
        val publisher = AndroidRuntimeNotificationPublisher(context, backend)
        assertFalse(publisher.canPostRuntimeNotifications())
        publisher.publish(null, RuntimeDeadlineFeedback(deadline, true, true))
        publisher.publish(null, null)
        assertTrue(backend.checks >= 3)
        assertEquals(0, backend.posts)
    }

    @Test
    fun realVibratorInvokesTheSupportedPlatformApiWithoutRequiringHardware() {
        AndroidRuntimeVibrator(context).vibrate()
    }

    @Test
    fun soundAndVibrationFlagsRemainIndependent() {
        for (sound in listOf(false, true)) {
            for (vibration in listOf(false, true)) {
                var sounds = 0
                var vibrations = 0
                AndroidRuntimeFeedbackDispatcher(
                    RuntimeSoundPlayer { sounds++ },
                    RuntimeVibrator { vibrations++ },
                ).dispatch(RuntimeDeadlineFeedback(deadline, sound, vibration))
                assertEquals(if (sound) 1 else 0, sounds)
                assertEquals(if (vibration) 1 else 0, vibrations)
            }
        }
    }

    @Test
    fun exactAccessChangedSignalIsAcceptedOnlyWhereThePlatformSupportsIt() {
        val operation =
            RuntimeBroadcastOperation.recovery(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertEquals(RuntimeBroadcastOperation.Reschedule, operation)
        } else {
            assertNull(operation)
        }
    }
}

internal class RecordingCapabilityAlarmBackend(
    private val allowed: Boolean = false,
    private val rejectExact: Boolean = false,
) : RuntimeAlarmBackend {
    val exact = mutableListOf<RuntimeDeadline>()
    val inexact = mutableListOf<RuntimeDeadline>()
    var cancellations = 0

    override fun canScheduleExactAlarms(): Boolean = allowed

    override fun scheduleExact(deadline: RuntimeDeadline) {
        exact += deadline
        if (rejectExact) throw SecurityException("exact access revoked after inspection")
    }

    override fun scheduleInexact(deadline: RuntimeDeadline) {
        inexact += deadline
    }

    override fun cancel() {
        cancellations++
    }
}

internal class DeniedCapabilityNotificationBackend : RuntimeNotificationBackend {
    var checks = 0
    var posts = 0

    override fun canPost(): Boolean {
        checks++
        return false
    }

    override fun post(notification: Notification) {
        posts++
        error("Denied notification must never be posted")
    }

    override fun cancel() = error("Denied publication must return before delivery work")
}
