package com.agentx.app.logging

import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.logging.LogRecord
import com.agentx.app.core.logging.LogSink
import com.agentx.app.termux.DeveloperLogCategory
import com.agentx.app.termux.DeveloperLogLevel
import com.agentx.app.termux.DeveloperLogger

/**
 * Forwards structured [com.agentx.app.core.logging.ForgeLogger] records into the
 * app's Developer Log, so the model/API diagnostics written by the platform layers
 * show up in the same screen as the terminal and runtime diagnostics.
 *
 * This is a bridge, not a second logging framework: records are still created by
 * the existing ForgeLogger, and only their destination is extended. Fields are
 * rendered as `key=value` so one record stays one readable log line.
 *
 * A record's `component` field selects the category, so model traffic is filed
 * under [DeveloperLogCategory.MODEL] and everything else under
 * [DeveloperLogCategory.SESSION].
 */
class DeveloperLogSink(
    /** Optional second destination, for example the console sink used in development. */
    private val delegate: LogSink? = null,
) : LogSink {

    override fun write(record: LogRecord) {
        delegate?.write(record)
        DeveloperLogger.log(
            level = record.level.toDeveloperLevel(),
            category = categoryFor(record),
            message = render(record),
        )
    }

    private fun render(record: LogRecord): String {
        // The `component` field is routing metadata used by [categoryFor], so it is
        // not repeated in the readable line. Everything else stays key=value.
        val fields = record.fields.filterKeys { it != COMPONENT_FIELD }
        if (fields.isEmpty()) return record.message
        val suffix = fields.entries.joinToString(" ") { (key, value) -> "$key=$value" }
        return "${record.message} $suffix"
    }

    private fun categoryFor(record: LogRecord): DeveloperLogCategory {
        val component = record.fields[COMPONENT_FIELD] as? String ?: return DeveloperLogCategory.SESSION
        return when {
            component.startsWith(MODEL_COMPONENT_PREFIX) -> DeveloperLogCategory.MODEL
            component.startsWith(GITHUB_COMPONENT_PREFIX) -> DeveloperLogCategory.GITHUB
            else -> DeveloperLogCategory.SESSION
        }
    }

    private companion object {
        const val COMPONENT_FIELD = "component"
        const val MODEL_COMPONENT_PREFIX = "model"
        const val GITHUB_COMPONENT_PREFIX = "github"
    }
}

private fun LogLevel.toDeveloperLevel(): DeveloperLogLevel = when (this) {
    LogLevel.DEBUG -> DeveloperLogLevel.DEBUG
    LogLevel.INFO -> DeveloperLogLevel.INFO
    LogLevel.WARN -> DeveloperLogLevel.WARN
    LogLevel.ERROR -> DeveloperLogLevel.ERROR
}
