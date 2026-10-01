package com.agentx.app.termux

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A record of what the terminal actually did, for the cases where the app cannot report it itself.
 *
 * Logcat is not enough for this chain. The failures here happen at or below the native boundary —
 * `System.loadLibrary`, `JNI.createSubprocess`, `fork`, an APK whose libraries were never unpacked
 * — and some of them end the process outright, taking the log buffer with them. So every line is
 * also appended to a file in app storage: after a crash, after a restart, or on a device where
 * logcat is not reachable, the last run is still readable.
 *
 * Two deliberate choices:
 *
 * - A [Throwable] is recorded by class *name* and full stack trace, never by `message` alone. The
 *   difference between `UnsatisfiedLinkError: dlopen failed: library "libtermux.so" not found`
 *   and `IllegalStateException: ...` is the whole diagnosis, and `message` alone has repeatedly
 *   been `null` for exactly these failures.
 * - Nothing here is filtered or reworded. The point is to have the real error on screen instead of
 *   a sentence that describes a symptom.
 */
object TerminalDiagnostics {

    /** How many lines the UI keeps. Enough for a start-up sequence, bounded for memory. */
    private const val MAX_LINES = 400

    /** Rotate rather than grow without bound: this file is written on every session start. */
    private const val MAX_FILE_BYTES = 256 * 1024L

    private const val TAG = "TerminalDiagnostics"

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    /** Target file, set once by the runtime that owns the app's private storage. */
    private var sink: File? = null

    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Points the recorder at a file it may append to. Called once with app-private storage; until
     * then the recorder is memory-only, which is still useful within one process.
     */
    fun attach(file: File?) {
        synchronized(lock) {
            sink = file
            file?.parentFile?.mkdirs()
        }
    }

    /** Records one lifecycle fact. */
    fun record(tag: String, message: String) {
        append("$tag: $message")
    }

    /**
     * Records a failure, including everything needed to identify it later.
     *
     * The class name is deliberately separate from the message: for linkage failures such as
     * `UnsatisfiedLinkError` the name is often the only informative part, and for
     * `NoClassDefFoundError` the message names the *missing* class rather than the failure.
     */
    fun recordFailure(tag: String, message: String, error: Throwable) {
        append("$tag: $message")
        append("$tag: FAILED with ${error.javaClass.name}: ${error.message ?: "(no message)"}")
        // Defensive on purpose. getStackTraceString is a stubbed no-op in JVM tests (where it
        // returns null and would otherwise NPE here) and is the one call in this class that is not
        // plain Kotlin. A recorder that can throw while recording a failure is worse than useless:
        // it replaces the real error with its own.
        val trace = runCatching { Log.getStackTraceString(error) }.getOrNull().orEmpty()
        trace.lineSequence()
            .filter { it.isNotBlank() }
            .take(40)
            .forEach { append("$tag:   $it") }
    }

    /** The recorded lines, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    /** The last [count] lines, for the diagnostic panel. */
    fun tail(count: Int): List<String> = synchronized(lock) {
        lines.toList().takeLast(count)
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            runCatching { sink?.writeText("") }
        }
    }

    /** A compact multi-line report for the terminal screen's failure panel. */
    fun report(maxLines: Int = 12): String = tail(maxLines).joinToString("\n")

    private fun append(line: String) {
        val stamped = "${timestamp.format(Date())} $line"
        synchronized(lock) {
            lines.addLast(stamped)
            while (lines.size > MAX_LINES) lines.removeFirst()
            runCatching { writeToSink(stamped) }
        }
    }

    private fun writeToSink(line: String) {
        val file = sink ?: return
        file.parentFile?.mkdirs()
        // Rotate by keeping the tail: a diagnostic file that fills the disk is a new bug.
        if (file.length() > MAX_FILE_BYTES) {
            val kept = runCatching { file.readLines().takeLast(MAX_LINES) }.getOrDefault(emptyList())
            file.writeText(kept.joinToString("\n", postfix = "\n"))
        }
        file.appendText("$line\n")
    }
}
