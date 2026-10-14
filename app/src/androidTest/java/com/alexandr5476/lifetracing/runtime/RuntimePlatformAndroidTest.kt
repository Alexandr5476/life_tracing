package com.alexandr5476.lifetracing.runtime

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class RuntimePlatformAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanPlatformEffects() {
        AndroidRuntimeDeadlineScheduler(context).cancel()
        context
            .getSystemService(NotificationManager::class.java)
            .cancel(AndroidRuntimeNotificationPublisher.NOTIFICATION_ID)
    }

    @Test
    fun pendingIntentIdentityIsStableWhileExtrasCarryExpectedRuntimeIdentity() {
        val first = deadline("execution-1", "occurrence-1", 60)
        val replacement = deadline("execution-2", "occurrence-2", 120)

        assertEquals(first, RuntimeDeadlineIntentCodec.decode(RuntimeDeadlineIntentCodec.intent(context, first)))
        val token = RuntimeDeadlineIntentCodec.pendingIntent(context, first)
        assertEquals(token, RuntimeDeadlineIntentCodec.pendingIntent(context, replacement))
        assertEquals(token, RuntimeDeadlineIntentCodec.existingPendingIntent(context))
        assertEquals(
            replacement,
            RuntimeDeadlineIntentCodec.decode(RuntimeDeadlineIntentCodec.intent(context, replacement)),
        )
    }

    @Test
    fun manifestDeclaresOnlyRuntimePermissionsAndReceiversNeededByTheAdapters() {
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
        val permissions = info.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.SCHEDULE_EXACT_ALARM in permissions)
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in permissions)
        assertTrue(Manifest.permission.VIBRATE in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        // Keep the same receiver assertions on both sides of the API-33 overload boundary.
        for (receiver in listOf(RuntimeDeadlineReceiver::class.java, RuntimeRecoveryReceiver::class.java)) {
            val component = ComponentName(context, receiver)
            val receiverInfo =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getReceiverInfo(component, PackageManager.ComponentInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getReceiverInfo(component, 0)
                }
            assertNotNull(receiverInfo)
            assertFalse(receiverInfo.exported)
            assertTrue(receiverInfo.enabled)
        }
        @Suppress("DEPRECATION")
        val legacyPermissions =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
        assertEquals(legacyPermissions.orEmpty().toSet(), permissions)
    }

    @Test
    fun runtimeNotificationChannelHasStablePrivacySafeBoundary() {
        AndroidRuntimeNotificationPublisher(context)

        val channel =
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(AndroidRuntimeNotificationPublisher.CHANNEL_ID)

        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(null, channel.sound)
        assertEquals(false, channel.shouldVibrate())
    }

    @Test
    fun silentDueNotificationDoesNotOwnSoundOrVibrationFeedback() {
        var sounds = 0
        var vibrations = 0
        val due = RuntimeDeadlineFeedback(deadline("execution", "occurrence", 60), false, false)
        val dispatcher =
            AndroidRuntimeFeedbackDispatcher(
                RuntimeSoundPlayer { sounds++ },
                RuntimeVibrator { vibrations++ },
            )

        dispatcher.dispatch(due)
        AndroidRuntimeNotificationPublisher(context).publish(null, due)

        assertEquals(0, sounds)
        assertEquals(0, vibrations)
    }

    private fun deadline(
        executionId: String,
        occurrenceId: String,
        seconds: Long,
    ) = RuntimeDeadline(
        Instant.ofEpochSecond(seconds),
        RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO,
        ActiveSessionKind.SEQUENCE,
        executionId,
        SequenceOccurrenceId(occurrenceId),
    )
}
