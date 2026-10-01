package com.agentx.app.termux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DeveloperLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

enum class DeveloperLogCategory {
    TERMINAL,
    SESSION,
    ROOTFS,
    PROOT,
    PROCESS,
    PTY,
    ENV,
    INPUT,
    OUTPUT,
    RESTART,
    ERROR,
}

/**
 * Persistent diagnostic logger for the terminal/runtime startup path.
 *
 * Independent of Compose. Until [attach] points at app-private storage the recorder is
 * memory-only, which is still usable within one process and in JVM tests.
 */
object DeveloperLogger {

    const val RELATIVE_PATH: String = "diagnostics/terminal.log"

    const val DEFAULT_MAX_FILE_BYTES: Long = 5L * 1024 * 1024

    private const val MAX_MEMORY_LINES = 2000

    private val LOGGED_ENV = setOf(
        "PATH",
        "HOME",
        "SHELL",
        "TERM",
        "TMPDIR",
        "PROOT_LOADER",
        "PROOT_LOADER32",
        "PROOT_L2S_DIR",
    )

    private val lock = Any()
    private val memory = ArrayDeque<String>()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val linesFlow = MutableStateFlow<List<String>>(emptyList())

    private var sink: File? = null
    private var maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES

    /** In-memory tail, oldest first. Bounded to [MAX_MEMORY_LINES]. */
    val lines: StateFlow<List<String>> = linesFlow.asStateFlow()

    fun attach(file: File?, maxBytes: Long = DEFAULT_MAX_FILE_BYTES) {
        synchronized(lock) {
            sink = file
            maxFileBytes = maxBytes.coerceAtLeast(1024L)
            file?.parentFile?.mkdirs()
            seedFromSinkLocked()
            publishLocked()
        }
    }

    fun log(
        level: DeveloperLogLevel,
        category: DeveloperLogCategory,
        message: String,
        error: Throwable? = null,
    ) {
        append(level, category, message)
        if (error != null) writeThrowable(level, category, error)
    }

    fun debug(category: DeveloperLogCategory, message: String) =
        log(DeveloperLogLevel.DEBUG, category, message)

    fun info(category: DeveloperLogCategory, message: String) =
        log(DeveloperLogLevel.INFO, category, message)

    fun warn(category: DeveloperLogCategory, message: String) =
        log(DeveloperLogLevel.WARN, category, message)

    fun error(category: DeveloperLogCategory, message: String, error: Throwable? = null) =
        log(DeveloperLogLevel.ERROR, category, message, error)

    fun clear() {
        synchronized(lock) {
            memory.clear()
            runCatching { sink?.writeText("") }
            publishLocked()
        }
    }

    fun readAll(): String = synchronized(lock) {
        val file = sink
        if (file != null && file.isFile) {
            runCatching { file.readText() }.getOrElse { memory.joinToString("\n") }
        } else {
            memory.joinToString("\n")
        }
    }

    fun logEnvironment(entries: Array<String>) {
        val seen = HashSet<String>()
        for (entry in entries) {
            val separator = entry.indexOf('=')
            if (separator <= 0) continue
            val name = entry.substring(0, separator)
            if (name !in LOGGED_ENV) continue
            seen += name
            val value = entry.substring(separator + 1)
            info(DeveloperLogCategory.ENV, "$name=${redact(name, value)}")
        }
        for (name in LOGGED_ENV) {
            if (name !in seen) info(DeveloperLogCategory.ENV, "$name=(unset)")
        }
    }

    fun logEnvironment(entries: Map<String, String>) {
        logEnvironment(entries.map { (name, value) -> "$name=$value" }.toTypedArray())
    }

    fun flagValues(arguments: List<String>, flag: String): List<String> {
        val values = ArrayList<String>()
        var index = 0
        while (index < arguments.size) {
            if (arguments[index] == flag && index + 1 < arguments.size) {
                values += arguments[index + 1]
                index += 2
            } else {
                index += 1
            }
        }
        return values
    }

    /**
     * Appends a structured runtime snapshot already assembled by the caller.
     *
     * Each snapshot line is recorded through the normal logger so it stays in the
     * persistent file and on the live [lines] flow.
     */
    fun captureSnapshot(body: String) {
        info(DeveloperLogCategory.TERMINAL, "Runtime snapshot captured")
        body.lineSequence().forEach { line ->
            info(DeveloperLogCategory.TERMINAL, line)
        }
    }

    fun logProcessLaunch(executable: String, arguments: List<String>, workingDirectory: String) {
        info(DeveloperLogCategory.PROCESS, "executable = $executable")
        info(DeveloperLogCategory.PROCESS, "arguments = ${arguments.joinToString(" ")}")
        info(DeveloperLogCategory.PROCESS, "working directory = $workingDirectory")
        val rootfs = flagValues(arguments, "-r").firstOrNull()
        info(DeveloperLogCategory.PROCESS, "rootfs = ${rootfs ?: "(none)"}")
        val binds = flagValues(arguments, "-b")
        info(
            DeveloperLogCategory.PROCESS,
            "bind mounts = ${binds.ifEmpty { listOf("(none)") }.joinToString()}",
        )
    }

    private fun redact(name: String, value: String): String =
        if (TermuxEnvironment.looksSecret(name)) "<redacted>" else value

    private fun writeThrowable(level: DeveloperLogLevel, category: DeveloperLogCategory, error: Throwable) {
        append(level, category, "exception class=${error.javaClass.name}")
        append(level, category, "message=${error.message ?: "(no message)"}")
        append(level, category, "stack:")
        val trace = runCatching { error.stackTraceToString() }.getOrNull().orEmpty()
        trace.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { append(level, category, "  $it") }
        val cause = error.cause
        if (cause != null && cause !== error) {
            append(
                level,
                category,
                "caused by ${cause.javaClass.name}: ${cause.message ?: "(no message)"}",
            )
        }
    }

    private fun append(level: DeveloperLogLevel, category: DeveloperLogCategory, message: String) {
        synchronized(lock) {
            val stamped = "${timestamp.format(Date())} ${level.name} [$category] $message"
            memory.addLast(stamped)
            while (memory.size > MAX_MEMORY_LINES) memory.removeFirst()
            runCatching { writeToSink(stamped) }
            publishLocked()
        }
    }

    private fun seedFromSinkLocked() {
        val file = sink ?: return
        if (!file.isFile) return
        val loaded = runCatching {
            file.readLines().filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
        if (loaded.isEmpty()) return
        memory.clear()
        loaded.takeLast(MAX_MEMORY_LINES).forEach { memory.addLast(it) }
    }

    private fun publishLocked() {
        linesFlow.value = memory.toList()
    }

    private fun writeToSink(line: String) {
        val file = sink ?: return
        file.parentFile?.mkdirs()
        if (file.exists() && file.length() > maxFileBytes) {
            rotate(file)
        }
        file.appendText("$line\n")
        if (file.length() > maxFileBytes) {
            rotate(file)
        }
    }

    private fun rotate(file: File) {
        val keepBytes = (maxFileBytes / 2).coerceAtLeast(512L)
        val length = file.length()
        if (length <= keepBytes) return
        val kept = RandomAccessFile(file, "r").use { raf ->
            var position = length - keepBytes
            raf.seek(position)
            if (position > 0) {
                while (raf.filePointer < length) {
                    val byte = raf.read()
                    if (byte < 0 || byte == '\n'.code) break
                }
            }
            val remaining = (length - raf.filePointer).toInt().coerceAtLeast(0)
            val bytes = ByteArray(remaining)
            raf.readFully(bytes)
            bytes
        }
        file.writeBytes(kept)
    }
}
