package com.alexandr5476.lifetracing.ui

import java.io.InputStream
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Read the first obstructing SystemUI report, without dismissing it or retrying a testcase. */
internal class SystemUiWindowDiagnostics(
    private val read: (String) -> String,
) {
    private val attempted = AtomicBoolean()
    private val obstruction = Regex("Application Not Responding: com\\.android\\.systemui(?:[}\\s]|$)")

    fun capture(windows: String): String {
        if (!obstruction.containsMatchIn(windows)) return ""
        if (!attempted.compareAndSet(false, true)) {
            return "\nSystemUI diagnostic capture already attempted in this process."
        }
        return "\nRead-only SystemUI evidence (one attempt per instrumentation process):\n" +
            listOf(
                "dumpsys activity lastanr",
                "dumpsys dropbox --print system_app_anr",
                "logcat -d -t 160 -v threadtime -b system -b events -b crash",
            ).joinToString("\n") { command ->
                "$command\n" + runCatching { read(command) }.getOrElse { "capture error: $it" }
            }
    }
}

/** Capture the first native admission failure even when WindowManager names the app as focused. */
internal class NativeFocusDiagnostics(
    private val read: (String) -> String,
) {
    private val attempted = AtomicBoolean()

    fun capture(): String {
        if (!attempted.compareAndSet(false, true)) {
            return "\nNative focus diagnostic capture already attempted in this process."
        }
        return "\nRead-only native focus evidence (one attempt per instrumentation process):\n" +
            listOf(
                "dumpsys input",
                "dumpsys window displays",
                "dumpsys window windows",
                "dumpsys power",
                "logcat -d -t 240 -v threadtime -b main -b system -b events -b crash",
            ).joinToString("\n") { command ->
                "$command\n" + runCatching { read(command) }.getOrElse { "capture error: $it" }
            }
    }
}

/** Keep inline assertions below the runner limit and retain complete bounded reads in logcat. */
internal class NativeWindowFailureReport(
    private val log: (String) -> Unit,
) {
    fun retain(diagnostics: String): String {
        var logFailure: String? = null
        val bytes = diagnostics.toByteArray(Charsets.UTF_8)
        val capture = nextCapture.incrementAndGet()
        val total = maxOf(1, (bytes.size + 1_023) / 1_024)
        repeat(total) { index ->
            val start = index * 1_024
            val payload =
                Base64.getEncoder().encodeToString(bytes.copyOfRange(start, minOf(start + 1_024, bytes.size)))
            // The SDK log collector searches message bodies for TestRunner lifecycle markers.
            runCatching { log("NDIAG1 $capture ${index + 1}/$total $payload") }.onFailure {
                if (logFailure == null) logFailure = "${it.javaClass.simpleName}: ${it.message}"
            }
        }
        val reference =
            "Full bounded diagnostics: logcat tag NativeWindowDiagnostics, NDIAG1 Base64 frames. " +
                "Inline evidence is selected context.\n"
        val logging = logFailure?.let { "Logcat write failed: ${clipUtf8(it, 512)}\n" }.orEmpty()
        val sections = diagnostics.split(Regex("(?m)(?=^(?:dumpsys |logcat ))")).filter(String::isNotEmpty)
        val inline = reference + logging + sections.joinToString("\n", transform = ::compactSection)
        return clipUtf8(inline.replace("TestRunner:", "TestRunner [captured]:"), 24_576)
    }

    private fun compactSection(section: String): String {
        val lines = section.lines()
        val status =
            lines.drop(1).filter {
                val value = it.trim()
                value.startsWith("status=") || value.startsWith("command_exit=") || value.startsWith("capture error:")
            }
        val selected = linkedSetOf<Int>()
        selected += (1 until minOf(6, lines.size))
        var following = 0
        lines.forEachIndexed { index, line ->
            val value = line.trim()
            if (important.any(value::startsWith)) selected += index
            if (following > 0) {
                selected += index
                following--
            }
            if (value in listOf("FocusedApplications:", "FocusedWindows:", "FocusRequests:")) following = 2
        }
        val context = selected.filter { it > 0 && lines[it] !in status }.joinToString("\n") { lines[it] }
        return clipUtf8((listOf(lines.first()) + status).joinToString("\n"), 768) + "\n" + clipUtf8(context, 1_280)
    }

    private fun clipUtf8(
        value: String,
        maximumBytes: Int,
    ): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maximumBytes) return value
        val suffix = "\n[inline excerpt truncated; see full logcat]"
        val budget = maximumBytes - suffix.toByteArray(Charsets.UTF_8).size
        var end = 0
        var size = 0
        while (end < value.length) {
            val point = value.codePointAt(end)
            val bytes =
                when {
                    point <= 0x7f -> 1
                    point <= 0x7ff -> 2
                    point <= 0xffff -> 3
                    else -> 4
                }
            if (size + bytes > budget) break
            size += bytes
            end += Character.charCount(point)
        }
        return value.substring(0, end) + suffix
    }

    private companion object {
        val nextCapture = AtomicLong()
        val important =
            listOf(
                "mCurrentFocus=",
                "mFocusedApp=",
                "mFocusedWindow=",
                "mTopFocusedDisplayId=",
                "Input Dispatcher State:",
                "DispatchEnabled:",
                "DispatchFrozen:",
                "FocusedDisplayId:",
                "FocusedApplications:",
                "FocusedWindows:",
                "FocusRequests:",
                "mWakefulness=",
                "mInteractive=",
                "mIsInteractive=",
                "Process:",
                "Timestamp:",
                "Subject:",
            )
    }
}

/** Bound diagnostic I/O independently of a stalled Android service, including late pipe creation. */
internal class BoundedDiagnosticShell(
    private val open: (String) -> InputStream,
    private val timeoutMillis: Long = 5_000,
    private val maximumBytes: Int = 65_536,
    private val start: (Runnable) -> Unit = { task ->
        Thread(task, "keyboard-failure-diagnostics").apply { isDaemon = true }.start()
    },
) {
    init {
        require(timeoutMillis > 0)
        require(maximumBytes in 1 until Int.MAX_VALUE)
    }

    fun read(command: String): String {
        val cancelled = AtomicBoolean()
        val opened = AtomicReference<InputStream?>()
        val task =
            FutureTask {
                val stream = open(command)
                opened.set(stream)
                try {
                    if (cancelled.get()) return@FutureTask "status=CANCELLED"
                    val bytes = ByteArray(maximumBytes + 1)
                    var count = 0
                    while (count < bytes.size) {
                        val size = stream.read(bytes, count, bytes.size - count)
                        if (size < 0) break
                        count += size
                    }
                    val status = if (count > maximumBytes) "TRUNCATED at $maximumBytes bytes" else "COMPLETE"
                    "status=$status\n" + String(bytes, 0, minOf(count, maximumBytes), Charsets.UTF_8)
                } finally {
                    if (opened.compareAndSet(stream, null)) stream.close()
                }
            }
        return try {
            start(task)
            task.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            "status=TIMEOUT after $timeoutMillis ms"
        } catch (failure: ExecutionException) {
            "status=ERROR ${failure.cause ?: failure}"
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            "status=INTERRUPTED"
        } finally {
            cancelled.set(true)
            runCatching { opened.getAndSet(null)?.close() }
            task.cancel(true)
        }
    }
}
