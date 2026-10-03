package com.agentx.app.agent.delegation

/**
 * How much work a task is likely to need. Deliberately coarse — three bands are
 * enough to decide whether the Main Agent should handle a turn itself or reach
 * for a specialist, and nothing downstream needs a finer scale.
 */
enum class TaskComplexity {
    /** A single, self-contained action the Main Agent can finish directly. */
    SIMPLE,

    /** A focused task that benefits from one specialist. */
    MODERATE,

    /** Multi-step or cross-cutting work that may need several specialists in sequence. */
    COMPLEX,
}

/**
 * Classifies a task into a [TaskComplexity] band from the task text alone.
 *
 * This is **deterministic and model-free on purpose**: the delegation policy must
 * be able to decide "can the Main Agent just do this?" without spending a model
 * call, and two identical tasks must always classify the same way so the
 * behaviour is testable and reproducible. The signals are intentionally simple
 * and language-light (word count, step and conjunction markers, number of distinct
 * file references, a small verb vocabulary); they are heuristics, not a parser.
 */
object TaskComplexityClassifier {

    /** Below this many words a task is a candidate for SIMPLE. */
    private const val SHORT_WORDS = 12

    /** At or above this many words a task leans COMPLEX. */
    private const val LONG_WORDS = 45

    /** Verbs that, on their own, describe a quick read-only or single-edit action. */
    private val SIMPLE_VERBS = setOf(
        "read", "show", "print", "list", "open", "view", "find", "locate",
        "explain", "describe", "summarize", "summarise", "what", "where", "which",
        "rename", "format", "lint",
    )

    /** Verbs that describe substantial, usually multi-file engineering work. */
    private val COMPLEX_VERBS = setOf(
        "refactor", "migrate", "redesign", "rearchitect", "architect",
        "implement", "integrate", "orchestrate", "overhaul", "rewrite",
        "parallelize", "optimize", "optimise", "audit", "harden",
    )

    /** Phrases that signal more than one step. */
    private val STEP_MARKERS = listOf(
        "then", "after that", "afterwards", "next", "followed by", "and also",
        "as well as", "finally", "step 1", "step 2", "first", "second",
    )

    /** Phrases that signal breadth across the codebase. */
    private val BREADTH_MARKERS = listOf(
        "across", "every", "all ", "entire", "whole", "throughout",
        "end-to-end", "end to end", "codebase", "project-wide", "everywhere",
    )

    fun classify(task: String, objective: String? = null): TaskComplexity {
        val text = buildString {
            append(task.trim())
            if (!objective.isNullOrBlank()) {
                append(' ')
                append(objective.trim())
            }
        }
        if (text.isBlank()) return TaskComplexity.SIMPLE

        val lower = text.lowercase()
        val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wordCount = words.size

        var score = 0

        // Length is the strongest single signal.
        when {
            wordCount >= LONG_WORDS -> score += 3
            wordCount <= SHORT_WORDS -> score -= 2
        }

        // Explicit multi-step structure: list markers, numbered steps, several sentences.
        val sentenceCount = text.split(Regex("[.!?\\n]+")).count { it.isNotBlank() }
        if (sentenceCount >= 3) score += 2 else if (sentenceCount >= 2) score += 1
        if (STEP_MARKERS.any { lower.contains(it) }) score += 2
        if (lower.contains(Regex("(^|\\s)(and|;|,)\\s"))) score += 1

        // Breadth across the codebase.
        if (BREADTH_MARKERS.any { lower.contains(it) }) score += 2

        // Distinct file references (paths or dotted file names).
        val fileRefs = Regex("[\\w./-]+\\.[A-Za-z0-9]{1,6}")
            .findAll(text)
            .map { it.value }
            .filter { it.contains('.') && !it.startsWith('.') }
            .toSet()
        when {
            fileRefs.size >= 3 -> score += 2
            fileRefs.size == 2 -> score += 1
        }

        // Verb vocabulary nudges in both directions.
        val firstWord = words.firstOrNull()?.trim(',', ':', ';', '.', '?', '!')
        if (COMPLEX_VERBS.any { lower.contains(it) }) score += 2
        val looksSimpleVerb = firstWord in SIMPLE_VERBS
        if (looksSimpleVerb) score -= 2

        return when {
            score >= 4 -> TaskComplexity.COMPLEX
            score <= -1 -> TaskComplexity.SIMPLE
            else -> TaskComplexity.MODERATE
        }
    }
}
