package com.alexandr5476.lifetracing.runtime

import com.alexandr5476.lifetracing.domain.ActiveSessionKind
import com.alexandr5476.lifetracing.domain.RuntimeDeadline
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineFeedback
import com.alexandr5476.lifetracing.domain.RuntimeDeadlineKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class AndroidRuntimeFeedbackDispatcherTest {
    @Test
    fun dispatchesEachEnabledEffectExactlyOnceForEveryDeadlineKind() {
        for (kind in RuntimeDeadlineKind.entries) {
            for (soundEnabled in listOf(false, true)) {
                for (vibrationEnabled in listOf(false, true)) {
                    val effects = RecordingEffects()
                    effects.dispatcher.dispatch(feedback(kind, soundEnabled, vibrationEnabled))

                    assertEquals(if (soundEnabled) 1 else 0, effects.soundAttempts)
                    assertEquals(if (vibrationEnabled) 1 else 0, effects.vibrationAttempts)
                }
            }
        }
    }

    @Test
    fun soundFailureLeavesVibrationAndLaterSoundAvailable() {
        val effects = RecordingEffects()
        val event = feedback(RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO, sound = true, vibration = true)
        effects.soundFailure = SecurityException("cue inaccessible")

        effects.dispatcher.dispatch(event)
        assertEquals(1, effects.soundAttempts)
        assertEquals(1, effects.vibrationAttempts)

        effects.soundFailure = null
        effects.dispatcher.dispatch(event)
        assertEquals(2, effects.soundAttempts)
        assertEquals(2, effects.vibrationAttempts)
    }

    @Test
    fun vibrationFailureDoesNotRepeatSound() {
        val effects = RecordingEffects()
        effects.vibrationFailure = IllegalStateException("vibrator unavailable")

        effects.dispatcher.dispatch(feedback(RuntimeDeadlineKind.SEQUENCE_TIMER_ZERO, sound = true, vibration = true))

        assertEquals(1, effects.soundAttempts)
        assertEquals(1, effects.vibrationAttempts)
    }

    @Test
    fun bothFailuresStayInsideTheDispatcher() {
        val effects = RecordingEffects()
        effects.soundFailure = SecurityException("cue inaccessible")
        effects.vibrationFailure = IllegalStateException("vibrator unavailable")

        effects.dispatcher.dispatch(feedback(RuntimeDeadlineKind.SEQUENCE_TRANSITION_COUNTDOWN, true, true))

        assertEquals(1, effects.soundAttempts)
        assertEquals(1, effects.vibrationAttempts)
    }

    private fun feedback(
        kind: RuntimeDeadlineKind,
        sound: Boolean,
        vibration: Boolean,
    ): RuntimeDeadlineFeedback {
        val sessionKind =
            if (kind == RuntimeDeadlineKind.ACTIVITY_TIMER_ZERO) {
                ActiveSessionKind.ACTIVITY
            } else {
                ActiveSessionKind.SEQUENCE
            }
        return RuntimeDeadlineFeedback(RuntimeDeadline(Instant.EPOCH, kind, sessionKind, "timer"), sound, vibration)
    }

    private class RecordingEffects {
        var soundAttempts = 0
        var vibrationAttempts = 0
        var soundFailure: RuntimeException? = null
        var vibrationFailure: RuntimeException? = null

        val dispatcher =
            AndroidRuntimeFeedbackDispatcher(
                RuntimeSoundPlayer {
                    soundAttempts++
                    soundFailure?.let { throw it }
                },
                RuntimeVibrator {
                    vibrationAttempts++
                    vibrationFailure?.let { throw it }
                },
            )
    }
}
