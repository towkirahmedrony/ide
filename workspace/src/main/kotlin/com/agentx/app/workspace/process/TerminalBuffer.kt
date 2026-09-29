package com.agentx.app.workspace.process

/**
 * Bounded ring of terminal lines. Large streams such as `find /` or gradle
 * output must not grow memory without limit.
 */
class TerminalBuffer(
    val maxLines: Int = DEFAULT_MAX_LINES,
    val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    private val lock = Any()
    private val lines = ArrayDeque<TerminalBufferLine>()
    private var charCount: Int = 0
    private var dropped: Int = 0

    val size: Int
        get() = synchronized(lock) { lines.size }

    val droppedCount: Int
        get() = synchronized(lock) { dropped }

    fun snapshot(): List<TerminalBufferLine> = synchronized(lock) { lines.toList() }

    fun append(line: TerminalBufferLine) {
        synchronized(lock) {
            lines.addLast(line)
            charCount += line.text.length
            trimLocked()
        }
    }

    fun appendAll(newLines: List<TerminalBufferLine>) {
        newLines.forEach(::append)
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            charCount = 0
            dropped = 0
        }
    }

    private fun trimLocked() {
        while (lines.size > maxLines || charCount > maxChars) {
            val removed = lines.removeFirst()
            charCount -= removed.text.length
            dropped += 1
        }
        if (charCount < 0) charCount = 0
    }

    companion object {
        const val DEFAULT_MAX_LINES: Int = 2_000
        const val DEFAULT_MAX_CHARS: Int = 512_000
    }
}

data class TerminalBufferLine(
    val id: String,
    val text: String,
    val kind: ProcessStreamKind,
)
