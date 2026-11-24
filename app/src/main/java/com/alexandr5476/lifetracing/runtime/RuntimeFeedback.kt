package com.alexandr5476.lifetracing.runtime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import kotlinx.coroutines.CoroutineScope

fun interface RuntimeFeedbackDispatcher {
    fun dispatch(feedback: RuntimeDeadlineFeedback)
}

fun interface RuntimeSoundPlayer {
    fun play()
}

fun interface RuntimeVibrator {
    fun vibrate()
}

class AndroidRuntimeVibrator(
    context: Context,
) : RuntimeVibrator {
    private val vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        }

    override fun vibrate() {
        vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private companion object {
        const val VIBRATION_MS = 300L
    }
}

class AndroidRuntimeFeedbackDispatcher(
    private val soundPlayer: RuntimeSoundPlayer,
    private val vibrator: RuntimeVibrator,
) : RuntimeFeedbackDispatcher {
    override fun dispatch(feedback: RuntimeDeadlineFeedback) {
        if (feedback.soundEnabled) attemptEffect { soundPlayer.play() }
        if (feedback.vibrationEnabled) attemptEffect { vibrator.vibrate() }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Each platform effect is best effort.
    private inline fun attemptEffect(effect: () -> Unit) {
        try {
            effect()
        } catch (_: RuntimeException) {
            // Keep the other enabled effect eligible even when this one fails.
        }
    }

    internal companion object {
        fun create(
            context: Context,
            scope: CoroutineScope,
            notificationCue: RuntimeNotificationCue? = null,
        ): AndroidRuntimeFeedbackDispatcher =
            AndroidRuntimeFeedbackDispatcher(
                AndroidRuntimeSoundPlayer(scope, notificationCue ?: AndroidNotificationCue(context)),
                AndroidRuntimeVibrator(context),
            )
    }
}
