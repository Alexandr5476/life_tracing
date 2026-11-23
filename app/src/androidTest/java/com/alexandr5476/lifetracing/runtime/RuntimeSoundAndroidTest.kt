package com.alexandr5476.lifetracing.runtime

import android.Manifest
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexandr5476.lifetracing.LifeTracingRuntimeGraph
import com.alexandr5476.lifetracing.data.persistence.RuntimeRecoveryStore
import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.ActivityEntrySource
import com.alexandr5476.lifetracing.domain.ActivityTemplateDraft
import com.alexandr5476.lifetracing.domain.ActivityTemplateSettings
import com.alexandr5476.lifetracing.domain.NextRuntimeDeadlineResolver
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import com.alexandr5476.lifetracing.domain.TimeTrackingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RuntimeSoundAndroidTest {
    private val application = ApplicationProvider.getApplicationContext<RuntimeIsolationApplication>()

    @Test
    fun missingCueAndPlatformFailuresLeaveLaterRequestsEligible() =
        runTest {
            val requestedTypes = mutableListOf<Int>()
            val attemptedUris = mutableListOf<Uri>()
            val firstCue = Uri.parse("content://runtime-sound/first")
            val secondCue = Uri.parse("content://runtime-sound/second")
            val platform =
                object : RuntimeNotificationCue {
                    override fun resolve(type: Int): Uri? {
                        requestedTypes += type
                        return when (requestedTypes.size) {
                            1 -> null
                            2 -> throw SecurityException("settings unavailable")
                            3, 4 -> firstCue
                            else -> secondCue
                        }
                    }

                    override suspend fun play(uri: Uri) {
                        attemptedUris += uri
                        when (attemptedUris.size) {
                            1 -> throw IOException("cue inaccessible")
                            2 -> throw IllegalStateException("playback failed")
                        }
                    }
                }
            val player = AndroidRuntimeSoundPlayer(backgroundScope, platform)
            runCurrent()
            assertTrue(requestedTypes.isEmpty())

            repeat(5) {
                player.play()
                runCurrent()
            }

            assertEquals(List(5) { RingtoneManager.TYPE_NOTIFICATION }, requestedTypes)
            assertEquals(listOf(firstCue, firstCue, secondCue), attemptedUris)
        }

    @Test
    fun replacementAndTimeoutCloseThePreviousCueBeforeAnotherStarts() =
        runTest {
            val events = mutableListOf<String>()
            var attempts = 0
            val firstCleanupStarted = CompletableDeferred<Unit>()
            val releaseFirstCue = CompletableDeferred<Unit>()
            val platform =
                object : RuntimeNotificationCue {
                    override fun resolve(type: Int): Uri = Uri.parse("content://runtime-sound/default")

                    override suspend fun play(uri: Uri) {
                        val attempt = ++attempts
                        events += "start $attempt"
                        try {
                            if (attempt <= 2) awaitCancellation()
                        } finally {
                            if (attempt == 1) {
                                firstCleanupStarted.complete(Unit)
                                withContext(NonCancellable) { releaseFirstCue.await() }
                            }
                            events += "finish $attempt"
                        }
                    }
                }
            val player = AndroidRuntimeSoundPlayer(backgroundScope, platform)
            player.play()
            runCurrent()
            try {
                repeat(20) { player.play() }
                runCurrent()
                assertTrue(firstCleanupStarted.isCompleted)
                assertEquals(listOf("start 1"), events)
                repeat(20) { player.play() }
            } finally {
                releaseFirstCue.complete(Unit)
            }
            runCurrent()
            assertEquals(listOf("start 1", "finish 1", "start 2"), events)

            advanceTimeBy(10_001)
            runCurrent()
            player.play()
            runCurrent()

            assertEquals(listOf("start 1", "finish 1", "start 2", "finish 2", "start 3", "finish 3"), events)
        }

    @Test
    fun productionFeedbackFactoryDoesNotResolveAudioDuringConstruction() =
        runTest {
            val audioContext = UnavailableAudioContext(application)
            val deadline = activityDeadline()

            val dispatcher = AndroidRuntimeFeedbackDispatcher.create(audioContext, backgroundScope)
            runCurrent()
            assertEquals(0, audioContext.resolutionAttempts)

            dispatcher.dispatch(RuntimeDeadlineFeedback(deadline, soundEnabled = false, vibrationEnabled = false))
            runCurrent()
            assertEquals(0, audioContext.resolutionAttempts)

            dispatcher.dispatch(RuntimeDeadlineFeedback(deadline, soundEnabled = true, vibrationEnabled = false))
            runCurrent()
            assertEquals(1, audioContext.resolutionAttempts)
        }

    @Test(timeout = 60_000)
    fun productionGraphAttemptsSoundWithAudioUnavailableAndNotificationPermissionDenied() =
        runBlocking {
            val cue = UnavailableNotificationCue()
            application.withDisposableRuntime(notificationCue = cue) { environment ->
                val audioContext = DeniedNotificationPermissionContext(application)
                RuntimeRecoveryStore(application, environment.name).use { store ->
                    val deadline = seedOverdueSoundEnabledTimer(store)
                    val graph = LifeTracingRuntimeGraph.from(audioContext, environment.owner)
                    assertSame(graph, LifeTracingRuntimeGraph.from(audioContext, environment.owner))
                    assertEquals(0, cue.resolutionAttempts.get())

                    graph.coordinator.onDeadlineSignal(deadline)
                    withTimeout(10_000) { cue.resolutionAttempted.await() }

                    assertEquals(1, cue.resolutionAttempts.get())
                    assertNull(store.live.getActiveSession())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val manager = application.getSystemService(NotificationManager::class.java)
                        assertFalse(
                            manager.activeNotifications.any {
                                it.id == AndroidRuntimeNotificationPublisher.NOTIFICATION_ID && it.tag == null
                            },
                        )
                    }
                }
            }
        }

    @Test(timeout = 45_000)
    fun configuredSystemCueReachesTheRealMediaPlayerAndReleasesItAfterFailuresAndCompletion() =
        runBlocking {
            withContext(Dispatchers.IO) {
                val probe = MediaPlayerProbe()
                val failedPlayback = MediaPlayerProbe()
                val initializationFailed = CompletableDeferred<Unit>()
                var creations = 0
                val platform =
                    AndroidNotificationCue(application) {
                        when (++creations) {
                            1 -> {
                                initializationFailed.complete(Unit)
                                error("platform initialization failed")
                            }
                            2 -> RecordingMediaPlayer(failedPlayback, failStart = true)
                            else -> RecordingMediaPlayer(probe)
                        }
                    }
                val uri = platform.resolve(RingtoneManager.TYPE_NOTIFICATION)
                val notifications = application.getSystemService(NotificationManager::class.java)
                val audio = application.getSystemService(AudioManager::class.java)
                assumeTrue("The device must have a selected default notification cue", uri != null)
                assumeTrue(
                    "The device must allow interruptions for this playback probe",
                    notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
                        audio.ringerMode == AudioManager.RINGER_MODE_NORMAL,
                )

                val consumer = Job(coroutineContext[Job])
                try {
                    val player = AndroidRuntimeSoundPlayer(this + consumer, platform)
                    player.play()
                    withTimeout(10_000) { initializationFailed.await() }
                    player.play()
                    withTimeout(12_000) { failedPlayback.released.await() }
                    player.play()
                    withTimeout(12_000) { probe.released.await() }
                } finally {
                    consumer.cancelAndJoin()
                }

                assertEquals(AudioAttributes.USAGE_NOTIFICATION, probe.attributes?.usage)
                assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, probe.attributes?.contentType)
                assertEquals(1, failedPlayback.startAttempts)
                assertEquals(0, failedPlayback.starts)
                assertEquals(1, failedPlayback.releases)
                assertEquals(1, probe.startAttempts)
                assertEquals(1, probe.starts)
                assertEquals(1, probe.completions)
                assertEquals(1, probe.releases)
            }
        }

    private fun activityDeadline() =
        RuntimeDeadline(Instant.EPOCH, RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO, ActiveSessionKind.ACTIVITY, "timer")

    private fun seedOverdueSoundEnabledTimer(store: RuntimeRecoveryStore): RuntimeDeadline {
        val startedAt = Instant.now().minusSeconds(120)
        val template =
            store.authoring.createActivityTemplate(
                ActivityTemplateDraft(
                    "Sound-enabled timer",
                    null,
                    TimeTrackingMode.TIMER,
                    Duration.ofSeconds(60),
                    ActivityTemplateSettings(timerEndSound = true, timerEndVibration = false),
                ),
                createdAt = startedAt,
            )
        store.activityCommands.startLive(
            ActivityEntrySource.Template(template.id),
            startedAt,
            startedAt,
            ZoneOffset.UTC,
        )
        return requireNotNull(NextRuntimeDeadlineResolver.resolve(requireNotNull(store.live.getActiveRuntime())))
    }

    private class UnavailableNotificationCue : RuntimeNotificationCue {
        val resolutionAttempts = AtomicInteger()
        val resolutionAttempted = CompletableDeferred<Unit>()

        override fun resolve(type: Int): Uri? {
            assertEquals(RingtoneManager.TYPE_NOTIFICATION, type)
            resolutionAttempts.incrementAndGet()
            resolutionAttempted.complete(Unit)
            throw SecurityException("System cue resolution unavailable")
        }

        override suspend fun play(uri: Uri): Unit = throw AssertionError("Unavailable cue has no playback")
    }

    // Only the standalone feedback factory uses this Context. A runtime graph also needs real
    // resolver access for PendingIntent and database operations.
    private class UnavailableAudioContext(
        base: Context,
    ) : ContextWrapper(base) {
        var resolutionAttempts = 0
            private set

        override fun getApplicationContext(): Context = this

        override fun getContentResolver(): ContentResolver {
            resolutionAttempts++
            throw SecurityException("System cue resolution unavailable")
        }
    }

    private class DeniedNotificationPermissionContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun checkPermission(
            permission: String,
            pid: Int,
            uid: Int,
        ): Int =
            if (permission == Manifest.permission.POST_NOTIFICATIONS) {
                PackageManager.PERMISSION_DENIED
            } else {
                super.checkPermission(permission, pid, uid)
            }
    }

    private class MediaPlayerProbe {
        val released = CompletableDeferred<Unit>()
        var attributes: AudioAttributes? = null
        var startAttempts = 0
        var starts = 0
        var completions = 0
        var releases = 0
    }

    private class RecordingMediaPlayer(
        private val probe: MediaPlayerProbe,
        private val failStart: Boolean = false,
    ) : MediaPlayer() {
        override fun setAudioAttributes(attributes: AudioAttributes) {
            super.setAudioAttributes(attributes)
            probe.attributes = attributes
        }

        override fun start() {
            probe.startAttempts++
            check(!failStart) { "platform start failed" }
            super.start()
            probe.starts++
        }

        override fun setOnCompletionListener(listener: OnCompletionListener) {
            super.setOnCompletionListener { player ->
                probe.completions++
                listener.onCompletion(player)
            }
        }

        override fun release() {
            super.release()
            probe.releases++
            probe.released.complete(Unit)
        }
    }
}
