package com.agentx.app.context

/**
 * Configurable context limits.
 *
 * Nothing here assumes one fixed model context window: the budget is supplied
 * per request and may be derived from whatever model the Model Gateway is
 * pointed at. Character limits are the source of truth; [maxTotalTokens] is an
 * optional second ceiling converted with [charsPerToken].
 */
data class ContextBudget(
    /** Hard ceiling for the assembled context, in characters. */
    val maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS,
    /** Optional ceiling converted to characters with [charsPerToken]. */
    val maxTotalTokens: Int? = null,
    /** Rough characters per token used for the token estimate only. */
    val charsPerToken: Int = DEFAULT_CHARS_PER_TOKEN,
    /** Maximum number of items in the final context. */
    val maxItems: Int = DEFAULT_MAX_ITEMS,
    /** Maximum number of file items. */
    val maxFileCount: Int = DEFAULT_MAX_FILE_COUNT,
    /** Maximum characters kept from one file before truncation. */
    val maxFileChars: Int = DEFAULT_MAX_FILE_CHARS,
    /** Maximum number of directory listings. */
    val maxDirectoryCount: Int = DEFAULT_MAX_DIRECTORY_COUNT,
    /** Maximum entries kept from one directory listing. */
    val maxDirectoryEntries: Int = DEFAULT_MAX_DIRECTORY_ENTRIES,
    /** Maximum number of tool-result items. */
    val maxToolResultCount: Int = DEFAULT_MAX_TOOL_RESULT_COUNT,
    /** Maximum characters kept from one tool result. */
    val maxToolResultChars: Int = DEFAULT_MAX_TOOL_RESULT_CHARS,
    /** Maximum number of conversation messages kept. */
    val maxConversationMessages: Int = DEFAULT_MAX_CONVERSATION_MESSAGES,
    /** Maximum characters kept from the whole conversation history. */
    val maxConversationChars: Int = DEFAULT_MAX_CONVERSATION_CHARS,
    /** Maximum number of skill-context items. */
    val maxSkillItems: Int = DEFAULT_MAX_SKILL_ITEMS,
    /** Maximum characters kept from one skill's instructions. */
    val maxSkillChars: Int = DEFAULT_MAX_SKILL_CHARS,
    /**
     * Maximum characters the whole skill block may occupy.
     *
     * The skill block is appended to the system instruction, so it is bounded
     * separately from the general context ceiling: a long list of skills can
     * never grow the system prompt past what the model was configured for. The
     * effective limit is the smaller of this and [charLimit].
     */
    val maxSkillTotalChars: Int = DEFAULT_MAX_SKILL_TOTAL_CHARS,
    /**
     * Maximum characters kept from the project's `DESIGN.md`.
     *
     * The design direction is appended to the system instruction like the skill
     * block, so it gets its own ceiling rather than borrowing the (much larger)
     * file ceiling. A direction file is meant to be a short statement of intent;
     * one that exceeds this is truncated, not admitted whole.
     */
    val maxDesignChars: Int = DEFAULT_MAX_DESIGN_CHARS,
    /**
     * Maximum characters kept from a platform profile block.
     *
     * Like the design direction, a platform profile goes into the system
     * instruction, so it is bounded by its own small ceiling rather than by a file
     * ceiling. A profile is a page of conventions, not a manual: one that exceeds
     * this is truncated, and one that cannot fit the request at all is omitted.
     */
    val maxPlatformChars: Int = DEFAULT_MAX_PLATFORM_CHARS,
) {

    /** Effective character ceiling, honoring both the char and token limits. */
    val charLimit: Int
        get() = maxTotalTokens?.let { minOf(maxTotalChars, it * charsPerToken) } ?: maxTotalChars

    /** Characters allowed by a single item of [source]. */
    fun itemCharLimit(source: ContextSource): Int = when (source) {
        ContextSource.FILE -> maxFileChars
        ContextSource.TOOL_RESULT -> maxToolResultChars
        ContextSource.CONVERSATION -> maxConversationChars
        ContextSource.SKILL -> maxSkillChars
        ContextSource.DESIGN -> maxDesignChars
        ContextSource.PLATFORM -> maxPlatformChars
        ContextSource.DIRECTORY -> maxDirectoryEntries * AVERAGE_DIRECTORY_ENTRY_CHARS
        else -> Int.MAX_VALUE
    }

    /** Rough token estimate for diagnostics; never used to drop content. */
    fun estimateTokens(chars: Int): Int =
        if (chars <= 0) 0 else (chars + charsPerToken - 1) / charsPerToken

    /** Returns every configuration problem found; empty means valid. */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (maxTotalChars <= 0) problems += "maxTotalChars must be positive"
        if (charsPerToken <= 0) problems += "charsPerToken must be positive"
        maxTotalTokens?.let { if (it <= 0) problems += "maxTotalTokens must be positive" }
        if (maxItems <= 0) problems += "maxItems must be positive"
        if (maxFileCount < 0) problems += "maxFileCount must not be negative"
        if (maxFileChars <= 0) problems += "maxFileChars must be positive"
        if (maxDirectoryCount < 0) problems += "maxDirectoryCount must not be negative"
        if (maxDirectoryEntries <= 0) problems += "maxDirectoryEntries must be positive"
        if (maxToolResultCount < 0) problems += "maxToolResultCount must not be negative"
        if (maxToolResultChars <= 0) problems += "maxToolResultChars must be positive"
        if (maxConversationMessages < 0) problems += "maxConversationMessages must not be negative"
        if (maxConversationChars <= 0) problems += "maxConversationChars must be positive"
        if (maxSkillItems < 0) problems += "maxSkillItems must not be negative"
        if (maxSkillChars <= 0) problems += "maxSkillChars must be positive"
        if (maxSkillTotalChars <= 0) problems += "maxSkillTotalChars must be positive"
        if (maxDesignChars <= 0) problems += "maxDesignChars must be positive"
        if (maxPlatformChars <= 0) problems += "maxPlatformChars must be positive"
        return problems
    }

    companion object {
        const val DEFAULT_MAX_TOTAL_CHARS = 48_000
        const val DEFAULT_CHARS_PER_TOKEN = 4
        const val DEFAULT_MAX_ITEMS = 40
        const val DEFAULT_MAX_FILE_COUNT = 12
        const val DEFAULT_MAX_FILE_CHARS = 12_000
        const val DEFAULT_MAX_DIRECTORY_COUNT = 4
        const val DEFAULT_MAX_DIRECTORY_ENTRIES = 200
        const val DEFAULT_MAX_TOOL_RESULT_COUNT = 8
        const val DEFAULT_MAX_TOOL_RESULT_CHARS = 4_000
        const val DEFAULT_MAX_CONVERSATION_MESSAGES = 16
        const val DEFAULT_MAX_CONVERSATION_CHARS = 24_000
        const val DEFAULT_MAX_SKILL_ITEMS = 6
        const val DEFAULT_MAX_SKILL_CHARS = 2_500

        /**
         * Whole-block skill ceiling. A quarter of the default context, so skills
         * stay a supporting instruction set rather than the bulk of the prompt.
         */
        const val DEFAULT_MAX_SKILL_TOTAL_CHARS = 12_000

        /**
         * Whole-file ceiling for the project design direction.
         *
         * Small on purpose. `DESIGN.md` is a statement of intent that is injected
         * into the system instruction of every UI turn, so it competes with the
         * work itself for the model's attention: 2,000 characters is enough for
         * identity, audience, palette, type, density, shape and interaction
         * principles, and short enough that it stays read.
         */
        const val DEFAULT_MAX_DESIGN_CHARS = 2_000

        /**
         * Whole-block ceiling for a platform profile.
         *
         * Sized to the shipped profiles (the largest is about 1,700 characters
         * including its heading) with headroom for one more paragraph. It is
         * injected into the system instruction of every UI turn, so it must stay a
         * page rather than grow into a manual.
         */
        const val DEFAULT_MAX_PLATFORM_CHARS = 2_000

        /** Rough size of one rendered directory entry. */
        private const val AVERAGE_DIRECTORY_ENTRY_CHARS = 64

        val DEFAULT = ContextBudget()
    }
}

/** Content that may have been shortened, with the original size preserved. */
data class TruncatedContent(
    val text: String,
    val truncated: Boolean,
    val originalChars: Int,
) {
    companion object {
        fun whole(content: String): TruncatedContent =
            TruncatedContent(content, truncated = false, originalChars = content.length)
    }
}

/**
 * Line-aware, deterministic truncation. Content that fits is returned
 * unchanged; content that does not is cut at the last line boundary that keeps
 * at least half of the allowed window, and always says how much was dropped.
 */
object ContextTruncator {

    fun truncate(content: String, maxChars: Int): TruncatedContent {
        if (maxChars <= 0) {
            return TruncatedContent(
                text = if (content.isEmpty()) "" else marker(0, content.length),
                truncated = content.isNotEmpty(),
                originalChars = content.length,
            )
        }
        if (content.length <= maxChars) return TruncatedContent.whole(content)

        val head = content.take(maxChars)
        val lineBreak = head.lastIndexOf('\n')
        val kept = if (lineBreak > maxChars / 2) head.substring(0, lineBreak) else head
        return TruncatedContent(
            text = kept + marker(kept.length, content.length),
            truncated = true,
            originalChars = content.length,
        )
    }

    /** Splits content into [chunkChars]-sized pieces, in order. */
    fun chunk(content: String, chunkChars: Int): List<String> {
        require(chunkChars > 0) { "chunkChars must be positive" }
        if (content.isEmpty()) return emptyList()
        return content.chunked(chunkChars)
    }

    private fun marker(kept: Int, total: Int): String =
        "\n…[truncated: showed $kept of $total characters]"
}

/** Specific reason why a candidate never made it into the context. */
enum class ContextExclusionReason {
    PROTECTED_PATH,
    NOT_FOUND,
    UNREADABLE,
    EMPTY,
    OVER_FILE_LIMIT,
    OVER_DIRECTORY_LIMIT,
    OVER_TOOL_RESULT_LIMIT,
    OVER_SKILL_LIMIT,
    OVER_CONVERSATION_LIMIT,
    OVER_ITEM_LIMIT,
    OVER_CHAR_BUDGET,
}

/** A candidate that was deliberately left out, with the reason. */
data class ContextExclusion(
    val id: String,
    val source: ContextSource,
    val path: String? = null,
    val reason: ContextExclusionReason,
    val detail: String? = null,
)

/** An item that was shortened to fit, with both sizes recorded. */
data class ContextTruncation(
    val id: String,
    val source: ContextSource,
    val path: String? = null,
    val originalChars: Int,
    val keptChars: Int,
    val reason: String,
)

/** Result of enforcing a budget: what survived, what was shortened, what was dropped. */
data class ContextSelection(
    val items: List<ContextItem>,
    val truncated: List<ContextTruncation> = emptyList(),
    val excluded: List<ContextExclusion> = emptyList(),
    val usedChars: Int = items.sumOf { it.chars },
    val budget: ContextBudget = ContextBudget.DEFAULT,
) {
    val estimatedTokens: Int get() = budget.estimateTokens(usedChars)

    val limitChars: Int get() = budget.charLimit

    val isEmpty: Boolean get() = items.isEmpty()
}
