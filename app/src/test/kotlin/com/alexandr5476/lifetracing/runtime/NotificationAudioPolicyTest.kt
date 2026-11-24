package com.alexandr5476.lifetracing.runtime

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationAudioPolicyTest {
    private val normal =
        NotificationAudioPolicy(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)

    @Test
    fun normalRingerAndUnrestrictedInterruptionsPermitSound() {
        assertTrue(normal.allowsPlayback())
    }

    @Test
    fun silentAndVibrateModesSuppressSoundWithUnrestrictedInterruptions() {
        for (mode in listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)) {
            assertFalse(normal.copy(ringerMode = mode).allowsPlayback(), "Ringer mode $mode")
        }
    }

    @Test
    fun restrictedAndUnknownInterruptionsSuppressSoundWithNormalRinger() {
        val filters =
            listOf(
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                NotificationManager.INTERRUPTION_FILTER_NONE,
                NotificationManager.INTERRUPTION_FILTER_ALARMS,
                NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
            )
        for (filter in filters) {
            assertFalse(normal.copy(interruptionFilter = filter).allowsPlayback(), "Interruption filter $filter")
        }
    }
}
