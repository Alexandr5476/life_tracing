package com.alexandr5476.lifetracing.runtime

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
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
    @Suppress("DEPRECATION")
    fun recordsActualApiAndRejectsMismatchedRequestedTarget() {
        val expected = InstrumentationRegistry.getArguments().getString("compatibilityExpectedApi")
        println("LifeTracing compatibility module=app actualApi=$actualApi fingerprint=${Build.FINGERPRINT}")
        assertTrue("Device is below minSdk: $actualApi", actualApi >= 26)
        if (expected != null) assertEquals("Wrong device image", expected.toInt(), actualApi)
        if (InstrumentationRegistry.getArguments().getString("compatibilityTaskSnapshotsDisabled") != null) {
            assertEquals("Task-snapshot profile is API26 only", 26, actualApi)
            val actual =
                Class
                    .forName("android.app.ActivityManager")
                    .getDeclaredField("ENABLE_TASK_SNAPSHOTS")
                    .getBoolean(null)
            println("LifeTracing compatibility module=app actualTaskSnapshots=$actual")
            assertEquals("Prepared framework task-snapshot profile was lost", false, actual)
        }
        if (InstrumentationRegistry.getArguments().getString("compatibilityScaledRaster") != null) {
            assertEquals("Scaled raster profile is API26 only", 26, actualApi)
            val metrics = DisplayMetrics()
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .getSystemService(WindowManager::class.java)
                .defaultDisplay
                .getRealMetrics(metrics)
            println(
                "LifeTracing compatibility module=app actualRaster=${metrics.widthPixels}x${metrics.heightPixels} " +
                    "densityDpi=${metrics.densityDpi}",
            )
            assertEquals("Prepared display width was lost", 720, metrics.widthPixels)
            assertEquals("Prepared display height was lost", 1280, metrics.heightPixels)
            assertEquals("Prepared display density was lost", 280, metrics.densityDpi)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "actualApi={0}")
        fun deviceApi(): List<Array<Int>> = listOf(arrayOf(Build.VERSION.SDK_INT))
    }
}
