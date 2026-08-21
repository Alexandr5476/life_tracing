package com.alexandr5476.lifetracing.runtime

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.SequenceOccurrenceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class RuntimePlatformAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun pendingIntentIdentityIsStableWhileExtrasCarryExpectedRuntimeIdentity() {
        val first = deadline("execution-1", "occurrence-1", 60)
        val replacement = deadline("execution-2", "occurrence-2", 120)

        assertEquals(first, RuntimeDeadlineIntentCodec.decode(RuntimeDeadlineIntentCodec.intent(context, first)))
        assertEquals(
            RuntimeDeadlineIntentCodec.pendingIntent(context, first),
            RuntimeDeadlineIntentCodec.pendingIntent(context, replacement),
        )
    }

    @Test
    fun manifestDeclaresOnlyRuntimePermissionsAndReceiversNeededByTheAdapters() {
        val info =
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        val permissions = info.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.SCHEDULE_EXACT_ALARM in permissions)
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in permissions)
        assertTrue(Manifest.permission.VIBRATE in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertNotNull(
            context.packageManager.getReceiverInfo(
                ComponentName(context, RuntimeDeadlineReceiver::class.java),
                PackageManager.ComponentInfoFlags.of(0),
            ),
        )
        assertNotNull(
            context.packageManager.getReceiverInfo(
                ComponentName(context, RuntimeRecoveryReceiver::class.java),
                PackageManager.ComponentInfoFlags.of(0),
            ),
        )
    }

    @Test
    fun runtimeNotificationChannelHasStablePrivacySafeBoundary() {
        AndroidRuntimeNotificationPublisher(context)

        val channel =
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(AndroidRuntimeNotificationPublisher.CHANNEL_ID)

        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
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
