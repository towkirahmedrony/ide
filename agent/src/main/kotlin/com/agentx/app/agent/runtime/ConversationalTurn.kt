package com.agentx.app.agent.runtime

/**
 * Decides whether a user turn is about the project.
 *
 * The agent must not inspect the workspace to answer "Hi". Doing so is work the
 * user did not ask for, and the project description that comes back is not an
 * answer to their message. Inspection is therefore allowed only for a turn that
 * is actually about the project, its files or its code.
 *
 * The classification errs in one direction on purpose: only *recognisable* small
 * talk skips project context. Anything else — including a short, ambiguous
 * question — is treated as a real task, so the conservative failure mode is
 * "the agent may look at the project", never "the agent cannot inspect the file
 * the user asked about".
 *
 * This is a heuristic gate for automatic context injection, not a security
 * boundary: it decides what the agent volunteers, never what it is permitted to
 * do. Tool permission and the Tool Router remain the only authority on that.
 */
object ConversationalTurn {

    /** A message longer than this is a task, whatever it starts with. */
    private const val MAX_SMALL_TALK_WORDS = 6

    /** Greetings, acknowledgements and pleasantries. Normally lowercase. */
    private val SMALL_TALK: Set<String> = setOf(
        "hi", "hii", "hiii", "hiiii", "hey", "heya", "hello", "helo", "hullo", "yo", "hiya", "sup",
        "good morning", "good afternoon", "good evening", "good night",
        "how are you", "how are ya", "how are you doing", "how is it going", "hows it going",
        "how are things", "hows things", "how is everything", "whats up", "what is up",
        "thanks", "thank you", "thanks a lot", "thank you so much", "many thanks", "ty", "thx",
        "ok", "okay", "k", "kk", "cool", "nice", "great", "awesome", "perfect", "got it",
        "sounds good", "sure", "yes", "no", "yep", "yeah", "nope", "alright", "all right",
        "bye", "goodbye", "see you", "see ya", "good luck", "welcome", "no problem",
        "you are welcome", "my pleasure", "nice to meet you",
        "who are you", "what are you", "what can you do", "what do you do",
    )

    /** Words that may follow a greeting without turning it into a request. */
    private val FILLER: Set<String> = setOf(
        "there", "agent", "assistant", "ai", "bot", "you", "u", "man", "mate", "dude", "buddy",
        "friend", "my", "sir", "maam", "madam", "again", "team", "all", "everyone", "guys",
        "folks", "person", "please", "so", "much", "very", "a", "lot", "and", "how", "are",
        "is", "it", "going", "things", "up", "doing", "youre", "the", "best",
    )

    /**
     * Whether the workspace may contribute automatic context to this turn.
     *
     * `false` only for recognisable small talk.
     */
    fun requiresWorkspace(prompt: String): Boolean {
        val normalized = normalize(prompt)
        if (normalized.isEmpty()) return false
        return !isSmallTalk(normalized)
    }

    private fun isSmallTalk(normalized: String): Boolean {
        val words = normalized.split(' ')
        if (words.size > MAX_SMALL_TALK_WORDS) return false
        if (normalized in SMALL_TALK) return true
        // "hi there", "hello agent", "hello how are you": a known phrase followed
        // only by filler, or by another known phrase, is still small talk.
        for (phrase in SMALL_TALK) {
            if (!normalized.startsWith("$phrase ")) continue
            val remainder = normalized.removePrefix("$phrase ")
            if (remainder.split(' ').all { it in FILLER }) return true
            if (isSmallTalk(remainder)) return true
        }
        return false
    }

    /**
     * Lowercases, drops punctuation and collapses whitespace, so "Hi!!!" and
     * "hi" are the same message. Letters, digits, `_`, `-` and `'` survive; a
     * path such as `src/Auth.kt` becomes tokens, which never match small talk.
     */
    private fun normalize(text: String): String = text
        .lowercase()
        .replace(Regex("[^a-z0-9_'\\-]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
