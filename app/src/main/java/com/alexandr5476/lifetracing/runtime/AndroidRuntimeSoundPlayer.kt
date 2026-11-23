package com.alexandr5476.lifetracing.runtime

import android.app.NotificationManager
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal interface RuntimeNotificationCue {
    fun resolve(type: Int): Uri?

    suspend fun play(uri: Uri)
}

internal class AndroidRuntimeSoundPlayer(
    scope: CoroutineScope,
    private val cue: RuntimeNotificationCue,
) : RuntimeSoundPlayer {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            var activeCue: Job? = null
            requests.consumeEach {
                activeCue?.cancelAndJoin()
                // The handoff can hold one request while another arrives in the slot.
                // Both request the same cue, so consume the buffered request before starting.
                requests.tryReceive()
                activeCue = launch { playBestEffort() }
            }
        }
    }

    override fun play() {
        requests.trySend(Unit)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Unavailable system audio is a valid outcome.
    private suspend fun playBestEffort() {
        try {
            withTimeoutOrNull(CUE_TIMEOUT_MS) {
                cue.resolve(RingtoneManager.TYPE_NOTIFICATION)?.let { cue.play(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            // The configured system cue could not be read.
        } catch (_: RuntimeException) {
            // Platform failure leaves the next request eligible.
        }
    }

    private companion object {
        const val CUE_TIMEOUT_MS = 10_000L
    }
}

internal class AndroidNotificationCue(
    context: Context,
    private val createPlayer: () -> MediaPlayer = ::MediaPlayer,
) : RuntimeNotificationCue {
    private val applicationContext = context.applicationContext

    override fun resolve(type: Int): Uri? = RingtoneManager.getActualDefaultRingtoneUri(applicationContext, type)

    override suspend fun play(uri: Uri) {
        // Provider I/O stays on the runtime graph's IO dispatcher. MediaPlayer and its callbacks
        // share the main looper, with asynchronous preparation and a file descriptor as input.
        applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { source ->
            withContext(Dispatchers.Main) {
                val player = createPlayer()
                try {
                    prepareAndPlay(player, source)
                } finally {
                    player.release()
                }
            }
        }
    }

    private suspend fun prepareAndPlay(
        player: MediaPlayer,
        source: AssetFileDescriptor,
    ) {
        val prepared = CompletableDeferred<Boolean>()
        val finished = CompletableDeferred<Unit>()
        player.setAudioAttributes(
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        player.setOnPreparedListener { prepared.complete(true) }
        player.setOnCompletionListener { finished.complete(Unit) }
        player.setOnErrorListener { _, _, _ ->
            prepared.complete(false)
            finished.complete(Unit)
            true
        }
        player.setDataSource(source)
        player.prepareAsync()
        if (prepared.await() && !finished.isCompleted && allowsPlayback()) {
            currentCoroutineContext().ensureActive()
            player.start()
            finished.await()
        }
    }

    private fun allowsPlayback(): Boolean {
        // A standalone cue has no priority-notification category. On older Android versions
        // notification audio attributes alone do not suppress this playback in priority DND.
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        val audio = applicationContext.getSystemService(AudioManager::class.java)
        return notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }
}
