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
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class RuntimeCapabilityAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val deadline =
        RuntimeDeadline(
            Instant.now().plusSeconds(86_400),
            RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO,
            ActiveSessionKind.ACTIVITY,
            "platform-smoke-never-a-durable-execution",
        )

    @After
    fun cleanPlatformEffects() {
        AndroidRuntimeDeadlineScheduler(context).cancel()
        context
            .getSystemService(NotificationManager::class.java)
            .cancel(AndroidRuntimeNotificationPublisher.NOTIFICATION_ID)
    }

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
        assertEquals(
            replacement,
            RuntimeDeadlineIntentCodec.decode(RuntimeDeadlineIntentCodec.intent(context, replacement)),
        )
        scheduler.cancel()
        assertNull(RuntimeDeadlineIntentCodec.existingPendingIntent(context))
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
    fun vibratorInitializesOnBothApiPathsWithoutRequiringHardwareAndFlagsRemainIndependent() {
        assertNotNull(AndroidRuntimeVibrator(context))
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
