package com.agentx.app.ui.ide.state

import com.agentx.app.model.ContentToolCallParser
import com.agentx.app.model.ModelResponse
import com.agentx.app.ui.ide.data.AgentFailureKind
import com.agentx.app.ui.ide.data.PersistedAgentMessage
import com.agentx.app.ui.ide.data.PersistedMessageKind
import com.agentx.app.ui.ide.model.ActivityItemStatus
import com.agentx.app.ui.ide.model.AgentActivityKind
import com.agentx.app.ui.ide.model.AgentActivityUiModel
import com.agentx.app.ui.ide.model.AgentTurnOutcome
import com.agentx.app.ui.ide.model.ChatMessageKind
import com.agentx.app.ui.ide.model.ChatMessageUiModel
import com.agentx.app.ui.ide.model.InlineSpan
import com.agentx.app.ui.ide.model.MessageBlock
import com.agentx.app.ui.ide.model.MessageState
import com.agentx.app.ui.ide.model.ToolActivityUiModel
import com.agentx.app.ui.ide.model.ToolRunStatus
import com.agentx.app.ui.ide.model.UiError
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The Agent chat presentation layer. It converts the Agent Core's structured
 * output (streamed text, tool events, persisted conversation entries) into the
 * UI models the chat renders, and it renders markdown as structured blocks
 * rather than exposing raw JSON.
 *
 * Everything here is pure Kotlin so it can be unit tested on the JVM without a
 * device, and so no parsing happens inside a Composable.
 */
object AgentChatPresentation {

    // ───────────────────────────── Markdown ─────────────────────────────

    /**
     * Drops internal tool-call JSON so it is never rendered as the assistant's
     * answer. Leftover prose, if any, is kept.
     */
    fun visibleAssistantText(text: String): String {
        if (text.isBlank()) return ""
        return ContentToolCallParser.normalize(
            ModelResponse(model = "ui", providerId = "ui", content = text),
        ).content
    }

    /** True when [text] is an internal tool-call payload rather than user-facing prose. */
    fun isInternalToolCallText(text: String): Boolean {
        if (text.isBlank()) return false
        return visibleAssistantText(text).isBlank() &&
            (ContentToolCallParser.parse(text).isNotEmpty() || ContentToolCallParser.isLikelyToolCallText(text))
    }

    /** Parses markdown into renderable blocks, tolerating a streaming, partial response. */
    fun parseBlocks(text: String): List<MessageBlock> {
        val visible = visibleAssistantText(text)
        if (visible.isEmpty()) return emptyList()
        val normalized = visible.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val blocks = mutableListOf<MessageBlock>()
        val paragraph = mutableListOf<String>()
        var i = 0

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                val joined = paragraph.joinToString("\n").trim()
                if (joined.isNotEmpty()) {
                    blocks += MessageBlock.Paragraph(parseInline(joined))
                }
                paragraph.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (isFence(trimmed)) {
                flushParagraph()
                val language = fenceLanguage(trimmed)
                val code = StringBuilder()
                i++
                while (i < lines.size && !isFence(lines[i].trim())) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                // A closing fence is consumed when present; an unterminated fence
                // while streaming simply keeps the rest of the text as code.
                if (i < lines.size) i++
                blocks += MessageBlock.Code(language, code.toString())
                continue
            }

            if (trimmed.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            val heading = headingMatch(trimmed)
            if (heading != null) {
                flushParagraph()
                blocks += MessageBlock.Heading(heading.first, parseInline(heading.second))
                i++
                continue
            }

            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quote = mutableListOf<String>()
                while (i < lines.size) {
                    val q = lines[i].trim()
                    if (!q.startsWith(">")) break
                    quote += q.removePrefix(">").trimStart()
                    i++
                }
                blocks += MessageBlock.Quote(parseInline(quote.joinToString("\n").trim()))
                continue
            }

            if (bulletMatch(line) != null) {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val item = bulletMatch(lines[i]) ?: break
                    items += item
                    i++
                }
                blocks += MessageBlock.BulletList(items.map(::parseInline))
                continue
            }

            if (numberedMatch(line) != null) {
                flushParagraph()
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val item = numberedMatch(lines[i]) ?: break
                    items += item
                    i++
                }
                blocks += MessageBlock.NumberedList(items.map(::parseInline))
                continue
            }

            paragraph += line
            i++
        }
        flushParagraph()
        return blocks
    }

    /** Parses inline markdown into styled runs. Only safe link schemes are kept. */
    fun parseInline(text: String): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        if (text.isEmpty()) return spans
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                spans += InlineSpan.Text(buffer.toString())
                buffer.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        spans += InlineSpan.Code(text.substring(i + 1, end))
                        i = end + 1
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        flush()
                        spans += InlineSpan.Bold(text.substring(i + 2, end))
                        i = end + 2
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                c == '*' || c == '_' -> {
                    val end = text.indexOf(c, i + 1)
                    if (end > i + 1) {
                        flush()
                        spans += InlineSpan.Italic(text.substring(i + 1, end))
                        i = end + 1
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                c == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                        val end = text.indexOf(')', close + 2)
                        if (end > close) {
                            val label = text.substring(i + 1, close)
                            val url = text.substring(close + 2, end).trim()
                            if (isSafeUrl(url)) {
                                flush()
                                spans += InlineSpan.Link(label, url)
                                i = end + 1
                            } else {
                                buffer.append(c)
                                i++
                            }
                        } else {
                            buffer.append(c)
                            i++
                        }
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        flush()
        return spans
    }

    /** Flattens inline spans back to plain text (used for copy and previews). */
    fun inlinePlainText(spans: List<InlineSpan>): String = buildString {
        spans.forEach { span ->
            append(
                when (span) {
                    is InlineSpan.Text -> span.text
                    is InlineSpan.Code -> span.text
                    is InlineSpan.Bold -> span.text
                    is InlineSpan.Italic -> span.text
                    is InlineSpan.Link -> span.text
                },
            )
        }
    }

    /** Flattens blocks back to plain text. */
    fun plainText(blocks: List<MessageBlock>): String = buildString {
        blocks.forEach { block ->
            when (block) {
                is MessageBlock.Paragraph -> append(inlinePlainText(block.spans)).append('\n')
                is MessageBlock.Heading -> append(inlinePlainText(block.spans)).append('\n')
                is MessageBlock.Quote -> append(inlinePlainText(block.spans)).append('\n')
                is MessageBlock.BulletList -> block.items.forEach {
                    append("- ").append(inlinePlainText(it)).append('\n')
                }
                is MessageBlock.NumberedList -> block.items.forEachIndexed { index, item ->
                    append(index + 1).append(". ").append(inlinePlainText(item)).append('\n')
                }
                is MessageBlock.Code -> append(block.code).append('\n')
            }
        }
    }.trim()

    private fun headingMatch(line: String): Pair<Int, String>? {
        var level = 0
        while (level < line.length && level < 6 && line[level] == '#') level++
        if (level == 0) return null
        if (level >= line.length || line[level] != ' ') return null
        return level to line.substring(level + 1).trim()
    }

    private val bulletRegex = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
    private val numberedRegex = Regex("^\\s{0,3}\\d{1,3}[.)]\\s+(.*)$")

    private fun bulletMatch(line: String): String? =
        bulletRegex.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun numberedMatch(line: String): String? =
        numberedRegex.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun isFence(trimmedLine: String): Boolean =
        trimmedLine.startsWith("```") || trimmedLine.startsWith("~~~")

    private fun fenceLanguage(trimmedLine: String): String? =
        trimmedLine.drop(3).trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }

    private fun isSafeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("mailto:") ||
            lower.startsWith("tel:")
    }

    // ───────────────────────────── Code highlighting ─────────────────────────────

    /**
     * A small, language-agnostic syntax highlighter. It recognises comments,
     * strings, numbers and keywords for the common languages the agent emits; an
     * unknown language falls back to a generic keyword set rather than failing.
     */
    fun highlightCode(code: String, language: String?): List<CodeToken> {
        val keywords = keywordsFor(language)
        val hashComments = hashIsComment(language)
        val tokens = mutableListOf<CodeToken>()
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                tokens += CodeToken(buffer.toString(), CodeTokenKind.PLAIN)
                buffer.setLength(0)
            }
        }

        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '/' && i + 1 < code.length && code[i + 1] == '*' -> {
                    flush()
                    val end = code.indexOf("*/", i + 2)
                    val stop = if (end < 0) code.length else end + 2
                    tokens += CodeToken(code.substring(i, stop), CodeTokenKind.COMMENT)
                    i = stop
                }

                c == '/' && i + 1 < code.length && code[i + 1] == '/' -> {
                    flush()
                    val end = code.indexOf('\n', i)
                    val stop = if (end < 0) code.length else end
                    tokens += CodeToken(code.substring(i, stop), CodeTokenKind.COMMENT)
                    i = stop
                }

                c == '#' && hashComments -> {
                    flush()
                    val end = code.indexOf('\n', i)
                    val stop = if (end < 0) code.length else end
                    tokens += CodeToken(code.substring(i, stop), CodeTokenKind.COMMENT)
                    i = stop
                }

                c == '"' || c == '\'' || c == '`' -> {
                    flush()
                    var j = i + 1
                    while (j < code.length && code[j] != c) {
                        if (code[j] == '\\' && j + 1 < code.length) j += 2 else j++
                    }
                    val stop = if (j < code.length) j + 1 else code.length
                    tokens += CodeToken(code.substring(i, stop), CodeTokenKind.STRING)
                    i = stop
                }

                c.isDigit() -> {
                    flush()
                    var j = i
                    while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    tokens += CodeToken(code.substring(i, j), CodeTokenKind.NUMBER)
                    i = j
                }

                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i
                    while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    val word = code.substring(i, j)
                    when {
                        word in keywords -> {
                            flush()
                            tokens += CodeToken(word, CodeTokenKind.KEYWORD)
                        }
                        word in TYPE_WORDS -> {
                            flush()
                            tokens += CodeToken(word, CodeTokenKind.TYPE)
                        }
                        else -> buffer.append(word)
                    }
                    i = j
                }

                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        flush()
        return tokens
    }

    private fun hashIsComment(language: String?): Boolean = when (language?.lowercase()?.trim()) {
        "python", "py", "sh", "shell", "bash", "zsh", "yaml", "yml", "ruby", "rb", "r",
        "toml", "make", "makefile", "dockerfile", "perl", "pl", "conf", "ini",
        -> true

        else -> false
    }

    private fun keywordsFor(language: String?): Set<String> = when (language?.lowercase()?.trim()) {
        "kotlin", "kt", "kts" -> KOTLIN
        "java" -> JAVA
        "python", "py" -> PYTHON
        "javascript", "js", "jsx", "typescript", "ts", "tsx", "mjs", "cjs" -> JS_TS
        "shell", "sh", "bash", "zsh" -> SHELL
        "yaml", "yml", "toml", "ini", "conf" -> CONFIG
        "sql" -> SQL
        "c", "h", "cpp", "cc", "cxx", "hpp", "hxx", "c++", "cs", "go", "rust", "rs", "swift" -> C_FAMILY
        else -> GENERIC
    }

    private val TYPE_WORDS = setOf(
        "true", "false", "null", "True", "False", "None", "nil", "undefined", "this", "self", "super",
    )

    private val KOTLIN = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when",
        "for", "while", "do", "return", "break", "continue", "try", "catch", "finally", "throw", "is",
        "in", "as", "typealias", "constructor", "init", "companion", "suspend", "override", "open",
        "private", "public", "protected", "internal", "sealed", "data", "enum", "annotation", "lateinit",
        "const", "abstract", "inline", "reified", "where", "by", "out", "crossinline", "noinline",
    )
    private val JAVA = setOf(
        "package", "import", "class", "interface", "enum", "extends", "implements", "public", "private",
        "protected", "static", "final", "abstract", "synchronized", "volatile", "transient", "native",
        "void", "boolean", "byte", "char", "short", "int", "long", "float", "double", "if", "else",
        "switch", "case", "default", "for", "while", "do", "break", "continue", "return", "try", "catch",
        "finally", "throw", "throws", "new", "this", "super", "instanceof", "package", "record",
    )
    private val PYTHON = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
        "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
        "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield", "match",
        "case",
    )
    private val JS_TS = setOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch",
        "case", "default", "break", "continue", "new", "class", "extends", "super", "this", "typeof",
        "instanceof", "in", "of", "try", "catch", "finally", "throw", "async", "await", "yield",
        "import", "from", "export", "default", "as", "interface", "type", "enum", "implements",
        "public", "private", "protected", "readonly", "static", "declare", "namespace", "abstract",
    )
    private val SHELL = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
        "return", "export", "local", "readonly", "in", "echo", "cd", "set", "unset", "shift", "source",
        "exit",
    )
    private val CONFIG = setOf("true", "false", "null", "yes", "no", "on", "off")
    private val SQL = setOf(
        "select", "from", "where", "insert", "into", "update", "delete", "create", "table", "alter",
        "drop", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having",
        "limit", "offset", "and", "or", "not", "null", "values", "set", "as", "distinct", "union",
    )
    private val C_FAMILY = setOf(
        "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "goto",
        "struct", "union", "enum", "typedef", "static", "const", "extern", "inline", "volatile",
        "register", "signed", "unsigned", "void", "int", "char", "long", "short", "float", "double",
        "class", "public", "private", "protected", "template", "namespace", "using", "try", "catch",
        "throw", "new", "delete", "this", "fn", "let", "mut", "impl", "trait", "match", "pub", "use",
        "func", "defer", "chan", "range", "package", "import", "guard", "var",
    )
    private val GENERIC = KOTLIN + JAVA + PYTHON + JS_TS + SHELL + SQL + C_FAMILY

    // ───────────────────────────── Tool / activity mapping ─────────────────────────────

    /** Turns a raw tool id such as `search_files` or `readFile` into `SEARCH_FILES`. */
    fun toolDisplayName(toolName: String): String {
        if (toolName.isBlank()) return "TOOL"
        val snake = buildString {
            toolName.forEachIndexed { index, c ->
                when {
                    c == '-' || c == ' ' || c == '.' -> append('_')
                    c.isUpperCase() && index > 0 && toolName[index - 1].isLowerCase() -> append('_').append(c)
                    else -> append(c)
                }
            }
        }
        return snake.replace(Regex("_+"), "_").trim('_').uppercase()
    }

    /** Human sentence for a tool execution, used in the activity list. */
    fun activityLabel(toolName: String): String = when (toolName.lowercase()) {
        "read_file", "read_range" -> "Read a file"
        "search_files", "search_text" -> "Searched files"
        "list_files", "list_directory" -> "Listed files"
        "find_symbol", "find_definition", "find_references" -> "Found a symbol"
        "get_file_symbols", "get_file_outline" -> "Inspected file structure"
        "run_command" -> "Terminal command"
        "write_file", "create_file" -> "Wrote a file"
        "apply_patch" -> "Applied a patch"
        "git_status", "git_diff", "git_log" -> "Checked git state"
        else -> "Used ${toolName.replace('_', ' ').trim()}"
    }

    /** Classifies a tool into an activity kind for its activity row. */
    fun activityKind(toolName: String): AgentActivityKind = when (toolName.lowercase()) {
        "read_file", "read_range" -> AgentActivityKind.FILE_READ
        "search_files", "search_text", "find_symbol", "find_definition", "find_references",
        "get_file_symbols", "get_file_outline",
        -> AgentActivityKind.SEARCH

        "run_command" -> AgentActivityKind.TERMINAL
        "write_file", "create_file", "apply_patch", "edit_file" -> AgentActivityKind.FILE_WRITE
        else -> AgentActivityKind.TOOL
    }

    /**
     * A short, safe subject line for one tool call, e.g. `"AuthRepository.kt"`
     * for READ_FILE. Extracted from real tool arguments, redacted, never raw JSON.
     */
    fun toolSubject(toolName: String, detail: String?): String? {
        val clean = detail?.trim().orEmpty()
        if (clean.isEmpty() || clean == "(no arguments)") return null
        return when (toolName.lowercase()) {
            "read_file", "read_range", "write_file", "create_file" ->
                firstValue(clean, listOf("path", "file"))

            "search_files", "search_text" ->
                firstValue(clean, listOf("query", "pattern", "text"))?.let { "\"$it\"" }

            "list_files", "list_directory" ->
                firstValue(clean, listOf("path", "dir", "directory"))

            // The row renders as `$ npm run build`, so the subject is the command.
            "run_command" ->
                firstValue(clean, listOf("command", "cmd"))

            else -> null
        }
    }

    /** The terminal command text, when the detail carries one. */
    fun terminalCommand(detail: String?): String? {
        val clean = detail?.trim().orEmpty()
        if (clean.isEmpty() || clean == "(no arguments)") return null
        return firstValue(clean, listOf("command", "cmd"))
            ?: clean.takeIf { it.length <= 120 && !it.contains('{') }?.lineSequence()?.firstOrNull()
    }

    private fun firstValue(detail: String, keys: List<String>): String? {
        for (key in keys) {
            // Matches `key=value`, `key: value` and `key="value"` from the safe
            // argument previews; full JSON is never handed to the UI.
            val regex = Regex("(?i)\\b${Regex.escape(key)}\\s*[=:]\\s*(\"?)([^,\"\\n]{1,120})")
            regex.find(detail)?.let { match ->
                val value = match.groupValues[2].trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    /** Splits tool/terminal output into a bounded list of display lines. */
    fun outputLines(result: String?, limit: Int = 80): List<String> =
        result.orEmpty()
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .take(limit)
            .toList()

    /**
     * The headline status text for the collapsible activity block: `Working · 7s`
     * while running, then `Completed · 12.4s`, `Failed · 2.1s` or `Stopped`.
     */
    fun activityHeadline(
        outcome: AgentTurnOutcome,
        elapsedMillis: Long,
        activeStepCount: Int,
        activeStepLabel: String,
        failedLabel: String?,
    ): String {
        val time = formatDuration(elapsedMillis)
        return when (outcome) {
            AgentTurnOutcome.RUNNING -> "$activeStepLabel · ${formatElapsedSeconds(elapsedMillis)}"
            AgentTurnOutcome.SUCCESS -> "Completed · $time · $activeStepCount steps"
            AgentTurnOutcome.FAILED -> "Failed · $time${failedLabel?.let { " · $it" }.orEmpty()}"
            AgentTurnOutcome.STOPPED -> "Stopped · $time"
        }
    }

    /** Picks the currently active step's label for the headline. */
    fun activeStepLabel(activities: List<AgentActivityUiModel>): String {
        val active = activities.lastOrNull { it.status == ActivityItemStatus.ACTIVE }
        if (active != null) return active.label
        val last = activities.lastOrNull() ?: return "Working"
        return when (last.status) {
            ActivityItemStatus.FAILED -> last.label
            else -> last.label
        }
    }

    /** Redacts and truncates a tool argument preview so no secret reaches the UI. */
    fun sanitizeToolDetail(detail: String?, limit: Int = 160): String? {
        val trimmed = detail?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "(no arguments)") return null
        val redacted = SECRET_PATTERN.replace(trimmed) { match -> "${match.groupValues[1]}=[REDACTED]" }
        return if (redacted.length > limit) redacted.take(limit).trimEnd() + "…" else redacted
    }

    /** Single-line, truncated tool result preview. */
    fun summarizeToolResult(result: String?, limit: Int = 200): String? {
        val firstLine = result?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: return null
        return if (firstLine.length > limit) firstLine.take(limit).trimEnd() + "…" else firstLine
    }

    fun toolStatusFrom(success: Boolean?): ToolRunStatus = when (success) {
        true -> ToolRunStatus.COMPLETED
        false -> ToolRunStatus.FAILED
        null -> ToolRunStatus.COMPLETED
    }

    fun activityStatusFrom(status: ToolRunStatus): ActivityItemStatus = when (status) {
        ToolRunStatus.RUNNING -> ActivityItemStatus.ACTIVE
        ToolRunStatus.COMPLETED -> ActivityItemStatus.DONE
        else -> ActivityItemStatus.FAILED
    }

    // ───────────────────────────── Errors ─────────────────────────────

    fun errorFrom(kind: AgentFailureKind, message: String): UiError = UiError(
        title = when (kind) {
            AgentFailureKind.CONNECTION -> "Connection failed"
            AgentFailureKind.TIMEOUT -> "Request timed out"
            AgentFailureKind.INVALID_RESPONSE -> "Invalid response"
            AgentFailureKind.NOT_CONFIGURED -> "No model connected"
            AgentFailureKind.CANCELLED -> "Generation stopped"
            AgentFailureKind.NONE, AgentFailureKind.UNKNOWN -> "Request failed"
        },
        message = message.ifBlank { "The agent could not complete this turn." },
        code = kind.name,
        retryable = kind != AgentFailureKind.NOT_CONFIGURED && kind != AgentFailureKind.CANCELLED,
    )

    // ───────────────────────────── Formatting ─────────────────────────────

    /** `12.4s` under a minute, `1m 03s` above it. */
    fun formatDuration(millis: Long): String {
        val safe = millis.coerceAtLeast(0L)
        return if (safe < 60_000L) {
            String.format(Locale.US, "%.1fs", safe / 1000.0)
        } else {
            val minutes = (safe / 60_000L).toInt()
            val seconds = ((safe % 60_000L) / 1000L).toInt()
            String.format(Locale.US, "%dm %02ds", minutes, seconds)
        }
    }

    /** `12s` — the live ticking form shown while generating. */
    fun formatElapsedSeconds(millis: Long): String = "${millis.coerceAtLeast(0L) / 1000L}s"

    fun formatClockTime(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        if (millis <= 0L) return ""
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        format.timeZone = zone
        return format.format(Date(millis))
    }

    /** `just now`, `5m ago`, `2h ago`, `3d ago`. */
    fun relativeTime(updatedAtMillis: Long, nowMillis: Long): String {
        if (updatedAtMillis <= 0L) return ""
        val delta = (nowMillis - updatedAtMillis).coerceAtLeast(0L)
        return when {
            delta < 60_000L -> "just now"
            delta < 3_600_000L -> "${delta / 60_000L}m ago"
            delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
            delta < 2_592_000_000L -> "${delta / 86_400_000L}d ago"
            else -> "${delta / 2_592_000_000L}mo ago"
        }
    }

    // ───────────────────────────── Persistence mapping ─────────────────────────────

    /** Maps a persisted transcript into the chat's UI models, grouping tool runs under their reply. */
    fun persistedTranscriptToUi(messages: List<PersistedAgentMessage>): List<ChatMessageUiModel> {
        val result = mutableListOf<ChatMessageUiModel>()
        var pendingActivities = mutableListOf<AgentActivityUiModel>()
        for (message in messages) {
            val ui = persistedMessageToUi(message)
            when (ui.kind) {
                ChatMessageKind.TOOL -> {
                    // Restored tool entries already carry a full structured row.
                    pendingActivities += ui.activities
                    result += ui
                }

                ChatMessageKind.ASSISTANT -> {
                    val restored = ui.activities + pendingActivities.toList()
                    result += ui.copy(activities = restored)
                    pendingActivities = mutableListOf()
                }

                else -> result += ui
            }
        }
        return result
    }

    /** Maps one persisted entry. Markdown is parsed here, never inside a Composable. */
    fun persistedMessageToUi(message: PersistedAgentMessage): ChatMessageUiModel = when (message.kind) {
        PersistedMessageKind.USER -> ChatMessageUiModel(
            id = message.id,
            kind = ChatMessageKind.USER,
            rawText = message.text,
            blocks = userBlocks(message.text),
            timestampMillis = message.timestampMillis,
            state = MessageState.COMPLETE,
        )

        PersistedMessageKind.ASSISTANT -> ChatMessageUiModel(
            id = message.id,
            kind = ChatMessageKind.ASSISTANT,
            rawText = visibleAssistantText(message.text),
            blocks = parseBlocks(message.text),
            timestampMillis = message.timestampMillis,
            state = MessageState.COMPLETE,
            modelId = message.modelId,
        )

        PersistedMessageKind.ERROR -> ChatMessageUiModel(
            id = message.id,
            kind = ChatMessageKind.ERROR,
            rawText = message.text,
            timestampMillis = message.timestampMillis,
            state = MessageState.FAILED,
            error = UiError(
                title = "Request failed",
                message = message.text.ifBlank { "The agent could not complete this turn." },
                code = message.errorCode,
                retryable = true,
            ),
            modelId = message.modelId,
        )

        PersistedMessageKind.TOOL -> ChatMessageUiModel(
            id = message.id,
            kind = ChatMessageKind.TOOL,
            rawText = message.text,
            timestampMillis = message.timestampMillis,
            state = if (message.toolSuccess == false) MessageState.FAILED else MessageState.COMPLETE,
            tool = toolFromPersisted(message),
            activities = listOf(activityFromPersistedTool(message)),
        )

        PersistedMessageKind.SUB_AGENT -> ChatMessageUiModel(
            id = message.id,
            kind = ChatMessageKind.SYSTEM,
            rawText = message.text.ifBlank { "Sub-agent completed" },
            blocks = parseBlocks(message.text),
            timestampMillis = message.timestampMillis,
        )

        PersistedMessageKind.SYSTEM -> ChatMessageUiModel(
            id = message.id,
            kind = ChatMessageKind.SYSTEM,
            rawText = message.text,
            blocks = parseBlocks(message.text),
            timestampMillis = message.timestampMillis,
        )
    }

    private fun toolFromPersisted(message: PersistedAgentMessage): ToolActivityUiModel {
        val name = message.toolName?.takeIf { it.isNotBlank() } ?: "tool"
        return ToolActivityUiModel(
            id = message.id,
            toolName = name,
            displayName = toolDisplayName(name),
            detail = sanitizeToolDetail(message.toolArguments ?: message.text.lineSequence().firstOrNull()),
            status = toolStatusFrom(message.toolSuccess),
            startedAtMillis = message.timestampMillis,
            summary = summarizeToolResult(message.toolResult),
        )
    }

    /** Restored turns get the same structured activity row live turns show. */
    fun activityFromPersistedTool(message: PersistedAgentMessage): AgentActivityUiModel {
        val name = message.toolName?.takeIf { it.isNotBlank() } ?: "tool"
        val detail = sanitizeToolDetail(message.toolArguments)
        val subject = toolSubject(name, detail)
        return AgentActivityUiModel(
            id = message.id,
            label = subject ?: activityLabel(name),
            status = if (message.toolSuccess == false) ActivityItemStatus.FAILED else ActivityItemStatus.DONE,
            kind = activityKind(name),
            toolName = name,
            timestampMillis = message.timestampMillis,
            detail = subject,
            outputLines = outputLines(message.toolResult),
            role = message.subAgentRole,
        )
    }

    /** A user message is plain text; it is never treated as markdown headings or fences. */
    fun userBlocks(text: String): List<MessageBlock> {
        if (text.isBlank()) return emptyList()
        return listOf(MessageBlock.Paragraph(parseInline(text.trim())))
    }

    private val SECRET_PATTERN = Regex(
        "(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|passwd|credential|authorization)\\s*[=:]\\s*\\S+",
    )

    /** Secrets never survive into an activity row's output preview. */
    fun redactOutput(text: String?): String? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        val redacted = SECRET_PATTERN.replace(clean) { match -> "${match.groupValues[1]}=[REDACTED]" }
        return redacted.takeIf { it.isNotEmpty() }
    }
}

enum class CodeTokenKind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, TYPE }

data class CodeToken(val text: String, val kind: CodeTokenKind)
