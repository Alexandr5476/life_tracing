package com.alexandr5476.lifetracing.runtime

import android.app.AlarmManager
import android.content.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RuntimeBroadcastAdmissionTest {
    @Test
    fun exactAccessSignalFailsClosedBeforeApi31AndReschedulesOnSupportedApis() {
        val action = AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        for (api in listOf(26, 30)) assertNull(RuntimeBroadcastOperation.recovery(action, api))
        for (api in listOf(31, 32, 33, 35, 36)) {
            assertEquals(RuntimeBroadcastOperation.Reschedule, RuntimeBroadcastOperation.recovery(action, api))
        }
    }

    @Test
    fun ordinaryRecoveryAllowlistAndUnsupportedInputsAreIdenticalAcrossApiBoundaries() {
        for (api in listOf(26, 31, 33, 35, 36)) {
            assertEquals(
                RuntimeBroadcastOperation.Boot,
                RuntimeBroadcastOperation.recovery(Intent.ACTION_BOOT_COMPLETED, api),
            )
            for (action in listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
                assertEquals(RuntimeBroadcastOperation.TimeChanged, RuntimeBroadcastOperation.recovery(action, api))
            }
            for (action in listOf(null, "unsupported", Intent.ACTION_LOCKED_BOOT_COMPLETED)) {
                assertNull(RuntimeBroadcastOperation.recovery(action, api))
            }
        }
    }
}
