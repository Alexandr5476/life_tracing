package com.alexandr5476.lifetracing.ui

import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val SURFACE_FLINGER_DUMP = "dumpsys SurfaceFlinger"

/** Read the first obstructing SystemUI report, without dismissing it or retrying a testcase. */
internal class SystemUiWindowDiagnostics(
    private val read: (String) -> String,
) {
    private val attempted = AtomicBoolean()
    private val obstruction = Regex("Application Not Responding: com\\.android\\.systemui(?:[}\\s]|$)")

    fun capture(windows: String): String {
        if (!obstruction.containsMatchIn(windows)) return ""
        if (!attempted.compareAndSet(false, true)) {
            return "\nSystemUI diagnostic capture already attempted for this fixture."
        }
        return "\nRead-only SystemUI evidence (one attempt per fixture):\n" +
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
            return "\nNative focus diagnostic capture already attempted for this fixture."
        }
        return "\nRead-only native focus evidence (one attempt per fixture):\n" +
            listOf(
                "dumpsys input",
                "dumpsys window displays",
                "dumpsys window windows",
                SURFACE_FLINGER_DUMP,
                "dumpsys activity service com.android.systemui/.SystemUIService WMShell",
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
    private val complete: () -> String = { "" },
    private val retainArtifact: (ByteArray) -> String = { "" },
) {
    fun retain(diagnostics: String): String {
        var logFailure: String? = null
        val bytes = diagnostics.toByteArray(Charsets.UTF_8)
        val artifact = runCatching { retainArtifact(bytes) }.getOrElse { "Diagnostic artifact failed: $it" }
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
        val receipt =
            runCatching { complete() }.getOrElse {
                if (logFailure == null) logFailure = "${it.javaClass.simpleName}: ${it.message}"
                ""
            }
        val reference =
            listOf(
                artifact,
                receipt,
                "Full bounded diagnostics: logcat tag NativeWindowDiagnostics, NDIAG1 Base64 frames. " +
                    "Inline evidence is selected context.",
            ).filter(String::isNotEmpty).joinToString("\n") + "\n"
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
        // A restored log buffer may contain frames from a previous instrumentation process.
        val nextCapture = AtomicLong(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
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
                "Window Infos:",
                "max send vsync id:",
                "max send delay (ns):",
                "unsent messages:",
                "mWakefulness=",
                "mInteractive=",
                "mIsInteractive=",
                "Process:",
                "Timestamp:",
                "Subject:",
            )
    }
}

/** Keep only one small page in flight; a positive write is not a retention acknowledgment. */
internal class AcknowledgedDiagnosticLog(
    private val write: (String) -> Int,
    private val read: () -> String,
    private val now: () -> Long = System::nanoTime,
    private val timeoutNanos: Long = TimeUnit.SECONDS.toNanos(5),
) {
    private val page = mutableListOf<String>()
    private var stopped: Exception? = null
    private var writes = 0
    private var rejected = 0
    private var lastRejection = 0
    private var acknowledged = 0

    fun append(frame: String) {
        stopped?.let { throw IOException("Diagnostic transport stopped", it) }
        page += frame
        // 32 maximum-size records fit inside the bounded 64 KiB acknowledgment read.
        if (page.size == 32) flush()
    }

    fun complete(): String {
        flush()
        return "Logcat transport: acknowledged=$acknowledged, write_calls=$writes, " +
            "rejected_writes=$rejected, last_rejection=$lastRejection"
    }

    private fun flush() {
        stopped?.let { throw IOException("Diagnostic transport stopped", it) }
        if (page.isEmpty()) return
        try {
            val remaining = page.toMutableSet()
            val pendingWrites = page.toMutableSet()
            val deadline = now() + timeoutNanos
            while (remaining.isNotEmpty()) {
                for (frame in pendingWrites.toList()) {
                    writes++
                    val result = write(frame)
                    if (result > 0) {
                        pendingWrites.remove(frame)
                    } else {
                        rejected++
                        lastRejection = result
                        break
                    }
                }
                val retained =
                    read()
                        .lineSequence()
                        .mapNotNull { line ->
                            val start = line.indexOf("NDIAG1 ")
                            if (start < 0) null else line.substring(start).trimEnd()
                        }.toSet()
                val confirmed = remaining.filterTo(mutableSetOf()) { it.trimEnd() in retained }
                acknowledged += confirmed.size
                remaining.removeAll(confirmed)
                pendingWrites.removeAll(confirmed)
                if (remaining.isNotEmpty() && now() >= deadline) {
                    throw IOException(
                        "Unacknowledged diagnostic frames=${remaining.size}; " +
                            "rejected_writes=$rejected, last_rejection=$lastRejection",
                    )
                }
            }
            page.clear()
        } catch (failure: Exception) {
            stopped = failure
            throw failure
        }
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
        val task =
            FutureTask {
                open(command).use { stream ->
                    val bytes = ByteArray(maximumBytes + 1)
                    var count = 0
                    while (count < bytes.size) {
                        val size = stream.read(bytes, count, bytes.size - count)
                        if (size < 0) break
                        count += size
                    }
                    if (count <= maximumBytes) {
                        "status=COMPLETE\n" + String(bytes, 0, count, Charsets.UTF_8)
                    } else {
                        truncatedReport(stream, bytes, preserveTail = command == SURFACE_FLINGER_DUMP)
                    }
                }
            }
        return try {
            start(task)
            task.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // A caller deadline must not close a live native pipe either. The daemon reader
            // keeps its bounded buffer and owns closure when the producer eventually ends.
            "status=TIMEOUT after $timeoutMillis ms"
        } catch (failure: ExecutionException) {
            "status=ERROR ${failure.cause ?: failure}"
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            "status=INTERRUPTED"
        }
    }

    private fun truncatedReport(
        stream: InputStream,
        prefix: ByteArray,
        preserveTail: Boolean,
    ): String {
        // SurfaceFlinger puts window update delivery counters after the layer dump.
        val tailSize = if (preserveTail) minOf(512, maximumBytes / 2) else 0
        val tail = prefix.copyOfRange(prefix.size - tailSize, prefix.size)
        // Android 8's UiAutomation pump treats a closed reader as fatal EPIPE.
        val drain = ByteArray(8_192)
        while (true) {
            val size = stream.read(drain)
            if (size < 0) break
            if (tail.isNotEmpty() && size > 0) {
                val copied = minOf(tail.size, size)
                tail.copyInto(tail, 0, copied, tail.size)
                drain.copyInto(tail, tail.size - copied, size - copied, size)
            }
        }
        return buildString {
            appendLine("status=TRUNCATED at $maximumBytes bytes")
            append(String(prefix, 0, maximumBytes - tail.size, Charsets.UTF_8))
            if (tail.isNotEmpty()) {
                append("\n[... middle of SurfaceFlinger dump omitted ...]\n")
                append(tail.toString(Charsets.UTF_8))
            }
        }
    }
}
