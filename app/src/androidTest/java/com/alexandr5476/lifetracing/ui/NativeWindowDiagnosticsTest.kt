package com.alexandr5476.lifetracing.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeWindowDiagnosticsTest {
    private val obstruction = "mCurrentFocus=Window{123 u0 Application Not Responding: com.android.systemui}"

    @Test
    fun capturesOnlySystemUiObstructionsOnceAndKeepsEveryReadOnlyCommand() {
        val commands = mutableListOf<String>()
        val diagnostics =
            SystemUiWindowDiagnostics { command ->
                commands += command
                "trace for $command"
            }
        assertEquals("", diagnostics.capture("mCurrentFocus=Window{456 LifeTracing}"))
        assertEquals("", diagnostics.capture("Application Not Responding: another.package"))
        assertEquals("", diagnostics.capture("Application Not Responding: com.android.systemui.other"))
        assertTrue(commands.isEmpty())
        val report = diagnostics.capture(obstruction)
        assertEquals(
            listOf(
                "dumpsys activity lastanr",
                "dumpsys dropbox --print system_app_anr",
                "logcat -d -t 160 -v threadtime -b system -b events -b crash",
            ),
            commands,
        )
        commands.forEach { assertTrue(report.contains("trace for $it")) }
        assertTrue(diagnostics.capture(obstruction).contains("already attempted"))
        assertEquals(3, commands.size)

        // A focused app window in dumpsys does not establish input-dispatch or View focus.
        commands.clear()
        val focus =
            NativeFocusDiagnostics { command ->
                commands += command
                "focus trace for $command"
            }
        val focusReport = focus.capture()
        assertEquals(
            listOf(
                "dumpsys input",
                "dumpsys window displays",
                "dumpsys window windows",
                "dumpsys power",
                "logcat -d -t 240 -v threadtime -b main -b system -b events -b crash",
            ),
            commands,
        )
        commands.forEach { assertTrue(focusReport.contains("focus trace for $it")) }
        assertTrue(focus.capture().contains("already attempted"))
        assertEquals(5, commands.size)
    }

    @Test
    fun captureFailureKeepsRemainingEvidenceAndCannotTriggerAnotherAttempt() {
        var calls = 0
        val diagnostics =
            SystemUiWindowDiagnostics { command ->
                calls++
                if (calls == 1) throw IOException("unavailable lastanr")
                "retained $command"
            }
        val report = diagnostics.capture(obstruction)
        assertTrue(report.contains("unavailable lastanr"))
        assertTrue(report.contains("retained dumpsys dropbox --print system_app_anr"))
        assertTrue(report.contains("retained logcat"))
        diagnostics.capture(obstruction)
        assertEquals(3, calls)

        calls = 0
        val focus =
            NativeFocusDiagnostics { command ->
                calls++
                if (calls == 1) throw IOException("unavailable input state")
                "retained $command"
            }
        val focusReport = focus.capture()
        assertTrue(focusReport.contains("unavailable input state"))
        assertTrue(focusReport.contains("retained dumpsys window displays"))
        assertTrue(focusReport.contains("retained dumpsys window windows"))
        assertTrue(focusReport.contains("retained dumpsys power"))
        assertTrue(focusReport.contains("retained logcat"))
        focus.capture()
        assertEquals(5, calls)
    }

    @Test
    fun oversizedDiagnosticsRetainOriginalCauseAndLateFocusEvidenceInTheExportedFailure() {
        val commands =
            listOf(
                "dumpsys activity lastanr",
                "dumpsys dropbox --print system_app_anr",
                "logcat -d -t 160 -v threadtime -b system -b events -b crash",
                "dumpsys input",
                "dumpsys window displays",
                "dumpsys window windows",
                "dumpsys power",
                "logcat -d -t 240 -v threadtime -b main -b system -b events -b crash",
            )
        val raw =
            "interactive=true, windowFocused=false\nmCurrentFocus=SystemUI ANR\n" +
                "I TestRunner: started: currentCase(example.Test)\n" +
                "I TestRunner: finished: previousCase(example.Test)\n" +
                commands.joinToString("\n") { command ->
                    "$command\nstatus=COMPLETE\n" + "old unrelated trace\n".repeat(3_000) +
                        "Input Dispatcher State:\n  DispatchEnabled: true\n  DispatchFrozen: false\n" +
                        "  FocusedApplications:\n    displayId=0, name='LifeTracing'\n" +
                        "  FocusedWindows:\n    displayId=0, name='SystemUI ANR'\ncommand_exit=0\n"
                }
        val logged = mutableListOf<String>()
        val inline = NativeWindowFailureReport(logged::add).retain(raw)
        val cause = IllegalStateException("original RootViewWithoutFocusException")
        val failure = AssertionError("Native admission failed. $inline", cause)
        val exported = failure.stackTraceToString().take(65_536)
        assertTrue(
            "Inline evidence must leave room for the original exception stack",
            inline.toByteArray().size <= 24_576,
        )
        commands.forEach { assertTrue("Missing query header: $it", exported.contains(it)) }
        assertTrue(exported.contains("DispatchEnabled: true"))
        assertTrue(exported.contains("DispatchFrozen: false"))
        assertTrue(exported.contains("displayId=0, name='LifeTracing'"))
        assertTrue(exported.contains("displayId=0, name='SystemUI ANR'"))
        assertTrue(
            exported.contains("Caused by: java.lang.IllegalStateException: original RootViewWithoutFocusException"),
        )
        assertEquals(cause, failure.cause)
        assertFalse(inline.contains("TestRunner: started:"))
        assertFalse(inline.contains("TestRunner: finished:"))
        assertTrue(inline.contains("TestRunner [captured]: started:"))
        assertTrue("Complete captured reads must remain in logcat", raw == decodeFrames(logged))
    }

    @Test
    fun unicodeDiagnosticsAreLoggedWithoutLossAndLoggingFailureCannotReplaceTheAssertion() {
        val raw =
            "dumpsys input\nstatus=COMPLETE\n" + "\u0416\uD83D\uDE42".repeat(24_000) +
                "\ncommand_exit=0\nlast evidence"
        val chunks = mutableListOf<String>()
        val inline = NativeWindowFailureReport(chunks::add).retain(raw)
        assertTrue("Unicode diagnostic chunks must reassemble without loss", raw == decodeFrames(chunks))
        assertTrue(chunks.all { it.toByteArray().size <= 2_048 })
        assertTrue(chunks.none { it.contains('\uFFFD') || it.contains("TestRunner:") || it.contains('\n') })
        val empty = mutableListOf<String>()
        NativeWindowFailureReport(empty::add).retain("")
        assertEquals("", decodeFrames(empty))
        assertTrue(inline.toByteArray().size <= 24_576)
        var calls = 0
        val tail = mutableListOf<String>()
        val withFailure =
            NativeWindowFailureReport { chunk ->
                calls++
                if (calls == 1) throw IOException("log sink unavailable")
                tail += chunk
            }.retain(raw)
        assertTrue(calls > 1)
        assertTrue(withFailure.contains("log sink unavailable"))
        assertTrue(withFailure.contains("dumpsys input"))
        assertTrue(withFailure.contains("command_exit=0"))
        assertTrue(decodeFrames(tail, complete = false).endsWith("last evidence"))
    }

    private fun decodeFrames(
        frames: List<String>,
        complete: Boolean = true,
    ): String {
        val output = ByteArrayOutputStream()
        var capture: String? = null
        var lastIndex = if (complete) 0 else 1
        var total = 0
        frames.forEach { frame ->
            val parts = frame.split(' ', limit = 4)
            assertEquals("Diagnostic records must be framed", 4, parts.size)
            assertEquals("NDIAG1", parts[0])
            assertTrue("Lifecycle records must remain encoded", ':' !in frame && '\n' !in frame)
            if (capture == null) capture = parts[1]
            assertEquals(capture, parts[1])
            val position = parts[2].split('/')
            assertEquals(lastIndex + 1, position[0].toInt())
            if (total == 0) total = position[1].toInt()
            assertEquals(total, position[1].toInt())
            lastIndex = position[0].toInt()
            output.write(Base64.getDecoder().decode(parts[3]))
        }
        assertTrue("Diagnostic frame sequence must reach its declared end", total > 0 && lastIndex == total)
        return output.toString(Charsets.UTF_8.name())
    }

    @Test
    fun boundedReadReportsTruncationAndErrorsAndClosesItsStream() {
        val closed = AtomicInteger()
        val shell =
            BoundedDiagnosticShell(
                open = {
                    object : ByteArrayInputStream("0123456789".toByteArray()) {
                        override fun close() {
                            closed.incrementAndGet()
                            super.close()
                        }
                    }
                },
                maximumBytes = 8,
            )
        val report = shell.read("read-only command")
        assertTrue(report.contains("TRUNCATED"))
        assertTrue(report.contains("01234567"))
        assertFalse(report.contains("012345678"))
        assertEquals(1, closed.get())
        val failure = BoundedDiagnosticShell(open = { throw IOException("pipe unavailable") }).read("failed command")
        assertTrue(failure.contains("ERROR"))
        assertTrue(failure.contains("pipe unavailable"))
    }

    @Test
    fun deadlineReturnsWithoutWaitingForOpenAndClosesALateStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val shell =
            BoundedDiagnosticShell(
                open = {
                    entered.countDown()
                    // Deliberately model a native open that does not respond to interruption.
                    var released = false
                    while (!released) {
                        try {
                            release.await()
                            released = true
                        } catch (_: InterruptedException) {
                            // The late descriptor must still be closed when native open returns.
                        }
                    }
                    object : ByteArrayInputStream(byteArrayOf()) {
                        override fun close() {
                            closed.countDown()
                            super.close()
                        }
                    }
                },
                timeoutMillis = 50,
                start = { task ->
                    Thread(task, "diagnostic-deadline-test").apply { isDaemon = true }.start()
                    assertTrue(
                        "The native open must start before its deadline is tested",
                        entered.await(5, TimeUnit.SECONDS),
                    )
                },
            )
        try {
            assertTrue(shell.read("blocked command").contains("TIMEOUT"))
        } finally {
            release.countDown()
        }
        assertTrue("The descriptor returned after timeout must be closed", closed.await(5, TimeUnit.SECONDS))
    }
}
