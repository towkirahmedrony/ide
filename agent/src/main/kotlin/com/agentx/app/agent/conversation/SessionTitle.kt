package com.agentx.app.agent.conversation

/**
 * Lightweight, deterministic titles derived from the first meaningful user
 * message. No model call is required.
 */
object SessionTitle {

    const val DEFAULT: String = "New session"
    const val MAX_LENGTH: Int = 48

    private val filler = Regex(
        """^(?:please\s+)?(?:can you|could you|would you|i want to|i need to|i'd like to|im trying to|i am trying to)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val questionLead = Regex(
        """^(?:why can't|why doesnt|why doesn't|why is|why are|how does|how do|how can|how to|what is|what's|what are|where is|where are)\s+(?:the\s+)?""",
        RegexOption.IGNORE_CASE,
    )
    private val problem = Regex(
        """\b(?:can't|cannot|doesn't|does not|won't|fail(?:s|ed|ing)?|error|bug|crash(?:es|ed|ing)?|broken|not working)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun derive(userMessage: String): String {
        val firstLine = userMessage.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (firstLine.isBlank()) return DEFAULT

        var text = firstLine.replace(Regex("\\s+"), " ").trim()
        text = text.trimEnd('.', '?', '!', ':')
        text = filler.replace(text, "")
        val hadQuestionLead = questionLead.containsMatchIn(text)
        text = questionLead.replace(text, "")
        text = text.trim().trimEnd('.', '?', '!')
        if (text.isBlank()) return truncate(firstLine)

        val needsFix = hadQuestionLead || problem.containsMatchIn(firstLine)
        if (needsFix && !text.startsWith("fix ", ignoreCase = true)) {
            text = "Fix $text"
        }
        return truncate(titleCase(text))
    }

    fun isPlaceholder(title: String): Boolean =
        title.isBlank() || title.equals(DEFAULT, ignoreCase = true)

    private fun titleCase(text: String): String {
        val small = setOf("a", "an", "the", "and", "or", "of", "to", "for", "in", "on", "at")
        return text.split(' ').filter { it.isNotEmpty() }.mapIndexed { index, word ->
            val lower = word.lowercase()
            if (index > 0 && lower in small) lower
            else lower.replaceFirstChar { it.titlecase() }
        }.joinToString(" ")
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_LENGTH) return text
        val cut = text.take(MAX_LENGTH)
        val space = cut.lastIndexOf(' ')
        val kept = if (space > MAX_LENGTH / 2) cut.substring(0, space) else cut
        return kept.trimEnd(',', ';', '-', ' ')
    }
}
