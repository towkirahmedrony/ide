package com.agentx.app.termux

/** How many parsed lines the in-app viewer keeps. The persistent file may be larger. */
const val DEVELOPER_LOGS_UI_LIMIT: Int = 500

enum class DeveloperLogFilter(val label: String) {
    ALL("All"),
    INFO("Info"),
    WARNING("Warning"),
    ERROR("Error"),
    MODEL("Model"),
    PROOT("PRoot"),
    ROOTFS("RootFS"),
    PROCESS("Process"),
    PTY("PTY"),
    SESSION("Session"),
    TERMINAL("Terminal"),
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

    fun matches(filter: DeveloperLogFilter, query: String): Boolean {
        val filterOk = when (filter) {
            DeveloperLogFilter.ALL -> true
            DeveloperLogFilter.INFO -> level == DeveloperLogLevel.INFO
            DeveloperLogFilter.WARNING -> level == DeveloperLogLevel.WARN
            DeveloperLogFilter.ERROR -> level == DeveloperLogLevel.ERROR
            DeveloperLogFilter.MODEL -> category == DeveloperLogCategory.MODEL
            DeveloperLogFilter.PROOT -> category == DeveloperLogCategory.PROOT
            DeveloperLogFilter.ROOTFS -> category == DeveloperLogCategory.ROOTFS
            DeveloperLogFilter.PROCESS -> category == DeveloperLogCategory.PROCESS
            DeveloperLogFilter.PTY -> category == DeveloperLogCategory.PTY
            DeveloperLogFilter.SESSION -> category == DeveloperLogCategory.SESSION
            DeveloperLogFilter.TERMINAL -> category == DeveloperLogCategory.TERMINAL
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

fun visibleDeveloperLogs(
    raw: List<String>,
    filter: DeveloperLogFilter,
    query: String,
    limit: Int = DEVELOPER_LOGS_UI_LIMIT,
): List<DeveloperLogLine> {
    val matches = ArrayList<DeveloperLogLine>(minOf(raw.size, limit))
    for (line in raw) {
        val parsed = DeveloperLogLine.parse(line)
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
