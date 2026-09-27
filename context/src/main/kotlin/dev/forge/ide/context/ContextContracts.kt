package dev.forge.ide.context

import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.architecture.LayerStatus

enum class ContextItemKind {
    FILE,
    SELECTION,
    SYMBOL,
    MEMORY,
    INSTRUCTION,
    DIAGNOSTIC,
    CUSTOM,
}

/** A single piece of context that may be fed to a model. */
data class ContextItem(
    val id: String,
    val kind: ContextItemKind,
    val content: String,
    val source: String? = null,
    val score: Double? = null,
)

data class ContextQuery(
    val intent: String,
    val workspaceId: String? = null,
    val limit: Int? = null,
)

data class ContextBundle(val items: List<ContextItem>)

/** Contributes context from one source (files, symbols, memory, ...). */
interface ContextProvider {
    val id: String

    suspend fun collect(query: ContextQuery): List<ContextItem>
}

/** Assembles ranked context for a request. Implemented in a later task. */
interface ContextEngine {
    suspend fun assemble(query: ContextQuery): ContextBundle
}

val CONTEXT_LAYER = LayerDescriptor(
    id = "context",
    title = "Context Engine",
    summary = "Gathers, ranks, and assembles repository and conversation context.",
    status = LayerStatus.CONTRACT_ONLY,
)
