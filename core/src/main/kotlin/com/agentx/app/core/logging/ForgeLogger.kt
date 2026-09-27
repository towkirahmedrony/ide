package com.agentx.app.core.logging

/** Severity levels ordered from most to least verbose. */
enum class LogLevel(val weight: Int) {
    DEBUG(10),
    INFO(20),
    WARN(30),
    ERROR(40),
}

/** A single structured log entry. */
data class LogRecord(
    val level: LogLevel,
    val message: String,
    val timestampMillis: Long,
    val fields: Map<String, Any?> = emptyMap(),
)

/** Destination for log records. Implementations can be swapped in tests. */
fun interface LogSink {
    fun write(record: LogRecord)
}

/**
 * Structured logger used throughout the platform. Kept free of Android types so
 * it can be reused by platform-independent domain modules.
 */
interface ForgeLogger {
    val level: LogLevel

    fun debug(message: String, fields: Map<String, Any?> = emptyMap())

    fun info(message: String, fields: Map<String, Any?> = emptyMap())

    fun warn(message: String, fields: Map<String, Any?> = emptyMap())

    fun error(message: String, throwable: Throwable? = null, fields: Map<String, Any?> = emptyMap())

    /** Returns a logger that attaches [fields] to every entry it writes. */
    fun child(fields: Map<String, Any?>): ForgeLogger
}

/** Default sink that writes a single line per record to standard output. */
object ConsoleLogSink : LogSink {
    override fun write(record: LogRecord) {
        val suffix = if (record.fields.isEmpty()) {
            ""
        } else {
            " " + record.fields.entries.joinToString(", ") { (key, value) -> "$key=$value" }
        }
        println("[${record.level}] ${record.message}$suffix")
    }
}

private class DefaultForgeLogger(
    override val level: LogLevel,
    private val sink: LogSink,
    private val baseFields: Map<String, Any?>,
) : ForgeLogger {

    private fun emit(target: LogLevel, message: String, fields: Map<String, Any?>) {
        if (target.weight < level.weight) return
        sink.write(
            LogRecord(
                level = target,
                message = message,
                timestampMillis = System.currentTimeMillis(),
                fields = baseFields + fields,
            ),
        )
    }

    override fun debug(message: String, fields: Map<String, Any?>) = emit(LogLevel.DEBUG, message, fields)

    override fun info(message: String, fields: Map<String, Any?>) = emit(LogLevel.INFO, message, fields)

    override fun warn(message: String, fields: Map<String, Any?>) = emit(LogLevel.WARN, message, fields)

    override fun error(message: String, throwable: Throwable?, fields: Map<String, Any?>) {
        val merged = if (throwable == null) fields else fields + ("error" to throwable.message)
        emit(LogLevel.ERROR, message, merged)
    }

    override fun child(fields: Map<String, Any?>): ForgeLogger =
        DefaultForgeLogger(level, sink, baseFields + fields)
}

/** Entry point for building loggers. */
object ForgeLoggers {
    fun create(
        level: LogLevel = LogLevel.INFO,
        sink: LogSink = ConsoleLogSink,
        baseFields: Map<String, Any?> = emptyMap(),
    ): ForgeLogger = DefaultForgeLogger(level, sink, baseFields)
}
