package com.agentx.app.termux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How many parsed lines the in-app viewer keeps. The persistent file may be larger. */
const val DEVELOPER_LOGS_UI_LIMIT: Int = 500

enum class DeveloperLogFilter(val label: String) {
    ALL("All"),
    INFO("Info"),
    WARNING("Warning"),
    ERROR("Error"),
    AGENT("Agent"),
    MODEL("Model"),
    GITHUB("GitHub"),
    APP("App"),
    PROOT("PRoot"),
    ROOTFS("RootFS"),
    PROCESS("Process"),
    PTY("PTY"),
    SESSION("Session"),
    TERMINAL("Terminal"),
    STORAGE("Storage"),
}

/**
 * True for the categories written by the terminal/runtime path — the PTY sessions,
 * the PRoot/rootfs bootstrap, the process launch and the environment.
 *
 * These are the events that used to flood the Developer Log before the Terminal page
 * was ever opened. They are still recorded and persisted; the viewer hides them while
 * the Terminal page is not the active page. [DeveloperLogCategory.ERROR] is
 * deliberately not terminal-specific so a genuine failure is never hidden, and
 * [DeveloperLogCategory.STORAGE] is a user-requested audit, not terminal chatter.
 */
val DeveloperLogCategory.isTerminalSource: Boolean
    get() = when (this) {
        DeveloperLogCategory.TERMINAL,
        DeveloperLogCategory.SESSION,
        DeveloperLogCategory.ROOTFS,
        DeveloperLogCategory.PROOT,
        DeveloperLogCategory.PROCESS,
        DeveloperLogCategory.PTY,
        DeveloperLogCategory.ENV,
        DeveloperLogCategory.INPUT,
        DeveloperLogCategory.OUTPUT,
        DeveloperLogCategory.RESTART,
        -> true
        else -> false
    }

/**
 * Whether the Terminal page is the page the user is currently on.
 *
 * Driven by the actual navigation lifecycle (the Terminal composable enters and
 * leaves composition), never by screen text or timers. It is the single source of
 * truth the Developer Log view filters terminal diagnostics with, so terminal logs
 * show while the Terminal page is open and stay out of the active view otherwise —
 * without ever deleting the stored history.
 */
object DeveloperLogVisibility {

    private val _terminalPageActive = MutableStateFlow(false)

    /** True while the Terminal page is composed (i.e. the active page). */
    val terminalPageActive: StateFlow<Boolean> = _terminalPageActive.asStateFlow()

    fun enterTerminalPage() {
        _terminalPageActive.value = true
    }

    fun exitTerminalPage() {
        _terminalPageActive.value = false
    }

    /** Restores the process-wide default; used by tests so state cannot leak. */
    fun reset() {
        _terminalPageActive.value = false
    }
}

/**
 * One displayed log row. Parsed from a [DeveloperLogger] line without changing storage.
 *
 * Display form: `HH:mm:ss LEVEL  [CATEGORY] message`
 */
data class DeveloperLogLine(
    val raw: String,
    val time: String,
    val level: DeveloperLogLevel?,
    val category: DeveloperLogCategory?,
    val message: String,
) {

    val display: String
        get() {
            val levelLabel = (level?.name ?: "").padEnd(5)
            val categoryLabel = category?.name ?: "LOG"
            return if (level == null) {
                raw
            } else {
                "$time $levelLabel [$categoryLabel] $message"
            }
        }

    /**
     * The complete, human-readable entry a tap on this row copies: the stored line,
     * which already carries the full timestamp, severity, source category, the
     * message, any correlation identifier and — for an exception — its flattened
     * stack trace. Copying one row copies exactly this entry, never the log list.
     */
    val copyText: String
        get() = raw.trim().ifEmpty { display }

    fun matches(filter: DeveloperLogFilter, query: String): Boolean {
        val filterOk = when (filter) {
            DeveloperLogFilter.ALL -> true
            DeveloperLogFilter.INFO -> level == DeveloperLogLevel.INFO
            DeveloperLogFilter.WARNING -> level == DeveloperLogLevel.WARN
            DeveloperLogFilter.ERROR -> level == DeveloperLogLevel.ERROR
            DeveloperLogFilter.AGENT -> category == DeveloperLogCategory.AGENT
            DeveloperLogFilter.MODEL -> category == DeveloperLogCategory.MODEL
            DeveloperLogFilter.GITHUB -> category == DeveloperLogCategory.GITHUB
            DeveloperLogFilter.APP -> category == DeveloperLogCategory.APP
            DeveloperLogFilter.PROOT -> category == DeveloperLogCategory.PROOT
            DeveloperLogFilter.ROOTFS -> category == DeveloperLogCategory.ROOTFS
            DeveloperLogFilter.PROCESS -> category == DeveloperLogCategory.PROCESS
            DeveloperLogFilter.PTY -> category == DeveloperLogCategory.PTY
            DeveloperLogFilter.SESSION -> category == DeveloperLogCategory.SESSION
            DeveloperLogFilter.TERMINAL -> category == DeveloperLogCategory.TERMINAL
            DeveloperLogFilter.STORAGE -> category == DeveloperLogCategory.STORAGE
        }
        if (!filterOk) return false
        if (query.isBlank()) return true
        return raw.contains(query, ignoreCase = true)
    }

    companion object {
        private val PATTERN = Regex(
            """^(?:\d{4}-\d{2}-\d{2} )?(\d{2}:\d{2}:\d{2})(?:\.\d+)?\s+(\w+)\s+\[(\w+)\]\s+(.*)$""",
        )

        fun parse(raw: String): DeveloperLogLine {
            val match = PATTERN.matchEntire(raw)
            if (match == null) {
                return DeveloperLogLine(
                    raw = raw,
                    time = "",
                    level = null,
                    category = null,
                    message = raw,
                )
            }
            val level = runCatching { DeveloperLogLevel.valueOf(match.groupValues[2]) }.getOrNull()
            val category = runCatching { DeveloperLogCategory.valueOf(match.groupValues[3]) }.getOrNull()
            return DeveloperLogLine(
                raw = raw,
                time = match.groupValues[1],
                level = level,
                category = category,
                message = match.groupValues[4],
            )
        }
    }
}

/**
 * Parses and filters the stored lines for display.
 *
 * [showTerminalLogs] hides terminal/runtime diagnostics while the Terminal page is not
 * active. It only affects what is *shown*: [raw] is never modified, so navigating away
 * from the Terminal page does not delete the history and returning to it shows the same
 * lines again.
 */
fun visibleDeveloperLogs(
    raw: List<String>,
    filter: DeveloperLogFilter,
    query: String,
    limit: Int = DEVELOPER_LOGS_UI_LIMIT,
    showTerminalLogs: Boolean = true,
): List<DeveloperLogLine> {
    val matches = ArrayList<DeveloperLogLine>(minOf(raw.size, limit))
    for (line in raw) {
        val parsed = DeveloperLogLine.parse(line)
        if (!showTerminalLogs && parsed.category?.isTerminalSource == true) continue
        if (parsed.matches(filter, query)) matches += parsed
    }
    return if (matches.size <= limit) matches else matches.takeLast(limit)
}

fun lastLogValue(lines: List<String>, category: DeveloperLogCategory, prefix: String): String? {
    val marker = "] $prefix"
    for (index in lines.lastIndex downTo 0) {
        val line = lines[index]
        if (!line.contains("[$category]")) continue
        val at = line.lastIndexOf(marker)
        if (at < 0) continue
        val value = line.substring(at + marker.length).trim()
        if (value.isNotEmpty()) return value
    }
    return null
}
