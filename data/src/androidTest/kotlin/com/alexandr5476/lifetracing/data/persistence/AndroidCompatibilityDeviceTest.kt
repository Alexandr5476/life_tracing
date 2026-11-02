package com.alexandr5476.lifetracing.data.persistence

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.alexandr5476.lifetracing.domain.ExactValue
import com.alexandr5476.lifetracing.domain.StatisticsDistributionCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.math.BigInteger
import java.time.Duration

/** The report records the executed device API, independently of a Gradle task's name. */
@RunWith(Parameterized::class)
class AndroidCompatibilityDeviceTest(
    private val actualApi: Int,
) {
    @Test
    @Suppress("DEPRECATION")
    fun recordsActualApiAndRejectsMismatchedRequestedTarget() {
        val expected = InstrumentationRegistry.getArguments().getString("compatibilityExpectedApi")
        println("LifeTracing compatibility module=data actualApi=$actualApi fingerprint=${Build.FINGERPRINT}")
        assertTrue("Device is below minSdk: $actualApi", actualApi >= 26)
        if (expected != null) assertEquals("Wrong device image", expected.toInt(), actualApi)
        if (InstrumentationRegistry.getArguments().getString("compatibilityTaskSnapshotsDisabled") != null) {
            assertEquals("Task-snapshot profile is API26 only", 26, actualApi)
            val actual =
                Class
                    .forName("android.app.ActivityManager")
                    .getDeclaredField("ENABLE_TASK_SNAPSHOTS")
                    .getBoolean(null)
            println("LifeTracing compatibility module=data actualTaskSnapshots=$actual")
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
                "LifeTracing compatibility module=data actualRaster=${metrics.widthPixels}x${metrics.heightPixels} " +
                    "densityDpi=${metrics.densityDpi}",
            )
            assertEquals("Prepared display width was lost", 720, metrics.widthPixels)
            assertEquals("Prepared display height was lost", 1280, metrics.heightPixels)
            assertEquals("Prepared display density was lost", 280, metrics.densityDpi)
        }
    }

    @Test
    fun evenSampleMediansRemainExactOnTheActualAndroidRuntime() {
        val durations = StatisticsDistributionCalculator.durations(listOf(0L, 1L))
        assertEquals(ExactValue.of(1, 2), durations.medianMilliseconds)
        val numbers = StatisticsDistributionCalculator.numbers(listOf(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(ExactValue.of(-1, 2), numbers.medianScaled)
    }

    @Test
    fun distributionTotalsRemainExactAtTheLongBoundaryOnTheActualAndroidRuntime() {
        assertEquals(Duration.ZERO, StatisticsDistributionCalculator.durations(listOf(0L, 0L)).total)
        assertEquals(
            Duration.ofMillis(Long.MAX_VALUE),
            StatisticsDistributionCalculator.durations(listOf(Long.MAX_VALUE - 1, 1L)).total,
        )
        assertThrows(ArithmeticException::class.java) {
            StatisticsDistributionCalculator.durations(listOf(Long.MAX_VALUE, 1L))
        }
        assertEquals(
            BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.valueOf(2),
            StatisticsDistributionCalculator.numbers(listOf(Long.MAX_VALUE, Long.MAX_VALUE)).totalScaled,
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "actualApi={0}")
        fun deviceApi(): List<Array<Int>> = listOf(arrayOf(Build.VERSION.SDK_INT))
    }
}
