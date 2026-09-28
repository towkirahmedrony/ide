package com.agentx.app.context

/**
 * Deterministic, embedding-free relevance.
 *
 * Items are ordered by priority band first, then by relevance. The sort is
 * stable, so items that tie keep the order in which they were collected —
 * which makes a context build reproducible for the same inputs.
 */
object ContextRanker {

    private val ORDER =
        compareByDescending<ContextItem> { it.priority.rank }
            .thenByDescending { it.relevance }

    fun rank(items: List<ContextItem>): List<ContextItem> = items.sortedWith(ORDER)
}
