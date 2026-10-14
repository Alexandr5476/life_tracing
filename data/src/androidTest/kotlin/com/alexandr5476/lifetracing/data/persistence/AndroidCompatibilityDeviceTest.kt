package com.alexandr5476.lifetracing.data.persistence

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The report records the executed device API, independently of a Gradle task's name. */
@RunWith(Parameterized::class)
class AndroidCompatibilityDeviceTest(
    private val actualApi: Int,
) {
    @Test
    fun recordsActualApiAndRejectsMismatchedRequestedTarget() {
        val expected = InstrumentationRegistry.getArguments().getString("compatibilityExpectedApi")
        println("LifeTracing compatibility module=data actualApi=$actualApi fingerprint=${Build.FINGERPRINT}")
        assertTrue("Device is below minSdk: $actualApi", actualApi >= 26)
        if (expected != null) assertEquals("Wrong device image", expected.toInt(), actualApi)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "actualApi={0}")
        fun deviceApi(): List<Array<Int>> = listOf(arrayOf(Build.VERSION.SDK_INT))
    }
}
