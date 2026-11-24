package com.alexandr5476.lifetracing.runtime

import android.app.NotificationManager
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
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
    private val selectedUri: (Context, Int) -> Uri? = RingtoneManager::getActualDefaultRingtoneUri,
    private val openDefaultUri: ((Context, Uri) -> AssetFileDescriptor?)? = null,
    private val readAudioPolicy: (Context) -> NotificationAudioPolicy = ::readNotificationAudioPolicy,
    private val createPlayer: () -> MediaPlayer = ::MediaPlayer,
) : RuntimeNotificationCue {
    private val applicationContext = context.applicationContext

    override fun resolve(type: Int): Uri? =
        selectedUri(applicationContext, type)?.let { RingtoneManager.getDefaultUri(type) }

    override suspend fun play(uri: Uri) {
        // The graph's IO scope opens the cached default before handing the descriptor to the player.
        val source = openCachedSource(uri)
        if (source != null) {
            source.use { descriptor ->
                withContext(Dispatchers.Main) { playWithSource { setDataSource(descriptor) } }
            }
        } else {
            // On API 26–28, and when the cache file is absent, MediaPlayer resolves the
            // symbolic URI itself. Keep that provider I/O off main and the player on one looper.
            val worker = HandlerThread("LifeTracing notification cue").apply { start() }
            try {
                withContext(Handler(worker.looper).asCoroutineDispatcher()) {
                    playWithSource { setDataSource(applicationContext, uri) }
                }
            } finally {
                worker.quit()
            }
        }
    }

    private fun openCachedSource(uri: Uri): AssetFileDescriptor? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            null
        } else {
            try {
                if (openDefaultUri != null) {
                    openDefaultUri(applicationContext, uri)
                } else {
                    RingtoneManager.openDefaultRingtoneUri(applicationContext, uri)
                }
            } catch (_: FileNotFoundException) {
                // Some platform versions throw for a missing cache file before trying the selected cue.
                // MediaPlayer can still resolve the symbolic URI.
                null
            }
        }

    private suspend fun playWithSource(setSource: MediaPlayer.() -> Unit) {
        val player = createPlayer()
        try {
            prepareAndPlay(player, setSource)
        } finally {
            player.release()
        }
    }

    private suspend fun prepareAndPlay(
        player: MediaPlayer,
        setSource: MediaPlayer.() -> Unit,
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
        player.setSource()
        player.prepareAsync()
        if (prepared.await() && !finished.isCompleted && readAudioPolicy(applicationContext).allowsPlayback()) {
            currentCoroutineContext().ensureActive()
            player.start()
            finished.await()
        }
    }
}

internal data class NotificationAudioPolicy(
    val ringerMode: Int,
    val interruptionFilter: Int,
) {
    fun allowsPlayback(): Boolean =
        // A standalone cue has no priority-notification category. On older Android versions
        // notification audio attributes alone do not suppress this playback in priority DND.
        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
            ringerMode == AudioManager.RINGER_MODE_NORMAL
}

private fun readNotificationAudioPolicy(context: Context): NotificationAudioPolicy {
    val notifications = context.getSystemService(NotificationManager::class.java)
    val audio = context.getSystemService(AudioManager::class.java)
    return NotificationAudioPolicy(audio.ringerMode, notifications.currentInterruptionFilter)
}
