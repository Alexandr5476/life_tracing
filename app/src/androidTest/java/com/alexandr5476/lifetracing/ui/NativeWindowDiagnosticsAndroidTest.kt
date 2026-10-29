package com.alexandr5476.lifetracing.ui

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class NativeWindowDiagnosticsAndroidTest {
    @Test
    fun nativeReadRetainsStderrExitStatusAndPlatformDiagnosticResults() {
        val shell = nativeDiagnosticShell()
        val failure = shell.read("sh -c 'printf visible; printf unavailable >&2; exit 7'")
        assertTrue(failure.contains("status=COMPLETE"))
        assertTrue(failure.contains("visible"))
        assertTrue(failure.contains("unavailable"))
        assertTrue(failure.contains("command_exit=7"))

        // Select the diagnostic path without causing, dismissing or relying on an actual ANR.
        val report =
            SystemUiWindowDiagnostics(shell::read).capture(
                "mCurrentFocus=Window{test Application Not Responding: com.android.systemui}",
            )
        assertTrue(report.contains("dumpsys activity lastanr\nstatus="))
        assertTrue(report.contains("dumpsys dropbox --print system_app_anr\nstatus="))
        assertTrue(report.contains("logcat -d -t 160 -v threadtime -b system -b events -b crash\nstatus="))

        // Exercise focus-delivery diagnostics without manufacturing a native focus failure.
        val focus = NativeFocusDiagnostics(shell::read).capture()
        listOf(
            "dumpsys input",
            "dumpsys window displays",
            "dumpsys window windows",
            "dumpsys power",
            "logcat -d -t 240 -v threadtime -b main -b system -b events -b crash",
        ).forEach { command ->
            assertTrue(
                focus,
                focus.contains("$command\nstatus=COMPLETE") || focus.contains("$command\nstatus=TRUNCATED"),
            )
        }
        val marker = "native-diagnostic-report-end-${System.nanoTime()}"
        val raw =
            "I TestRunner: started: capturedSnippet(example.Captured)\n" +
                "I TestRunner: finished: capturedSnippet(example.Captured)\n$report$focus\n$marker"
        val inline = nativeFailureReport(raw)
        assertTrue(inline.toByteArray().size <= 24_576)
        assertTrue(inline.contains("dumpsys input"))
        assertTrue(inline.contains("dumpsys dropbox --print system_app_anr"))
        // Unrelated boot or app records must not evict the marker from a global last-N query.
        repeat(32) { index ->
            assertTrue(Log.e("NativeDiagnosticsNoise", "after-report-$index") > 0)
        }
        val captured =
            shell.read("sh -c 'logcat -d -v tag -b main -s NativeWindowDiagnostics:E | tail -n 16'")
        val tail = ByteArrayOutputStream()
        captured.lineSequence().forEach { line ->
            val frame = line.substringAfter("NDIAG1 ", missingDelimiterValue = "")
            if (frame.isNotEmpty()) {
                val payload = frame.split(' ', limit = 3)[2].trim()
                tail.write(Base64.getDecoder().decode(payload))
            }
        }
        assertTrue(captured, tail.toString(Charsets.UTF_8.name()).contains(marker))
        assertTrue(inline.contains("TestRunner [captured]: started:"))
        assertTrue(!inline.contains("TestRunner: started:") && !inline.contains("TestRunner: finished:"))
    }
}
