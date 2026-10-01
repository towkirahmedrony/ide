package com.agentx.app.context

import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.architecture.LayerStatus
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelToolCall
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspacePath

/**
 * What the engine knows about the open workspace at one point in time.
 * [recentFiles] is most-recent-first and never contains absolute locations.
 */
data class WorkspaceSnapshot(
    val workspaceId: String? = null,
    val name: String? = null,
    val rootPath: String = WorkspacePath.ROOT,
    val selectedFile: String? = null,
    val openFiles: List<String> = emptyList(),
    val recentFiles: List<String> = emptyList(),
)

/**
 * Port to the Workspace Runtime. This is the only way the Context Engine
 * reaches workspace information: it never walks a repository on its own and
 * never opens a path the runtime did not hand it.
 */
interface WorkspaceContextProvider {
    /** Current workspace snapshot, or null when no workspace is open. */
    suspend fun snapshot(): WorkspaceSnapshot?

    /** Filesystem of the open workspace, or null when none is open. */
    suspend fun fileSystem(): WorkspaceFileSystem?

    /**
     * Records that [path] was used as context, so it can rank as recently used
     * in a later build. Implementations may ignore this.
     */
    fun markAccessed(path: String) = Unit
}

/** Provider used before (or without) a workspace being open. */
object EmptyWorkspaceContextProvider : WorkspaceContextProvider {
    override suspend fun snapshot(): WorkspaceSnapshot? = null

    override suspend fun fileSystem(): WorkspaceFileSystem? = null
}

/** Contributes additional context from one source. */
interface ContextProvider {
    val id: String

    val source: ContextSource

    suspend fun collect(request: ContextRequest): List<ContextItem>
}

/**
 * Central context authority between the workspace and the agent.
 *
 * It selects, prepares, limits and manages the information an agent task
 * really needs, and returns the final model-ready form. It is provider
 * agnostic: nothing here knows about a model vendor or a context window.
 *
 * Construction of context is `suspend` and runs its storage work on an IO
 * dispatcher, so it never blocks the caller's main thread.
 */
interface ContextEngine {

    /** Builds the context for one agent task: select, rank, bound, render. */
    suspend fun buildContext(request: ContextRequest): ContextResult

    /** Adds (or replaces by id) items in a session's live context, ranked. */
    fun addItems(sessionId: String, items: List<ContextItem>): List<ContextItem>

    /** Removes items by id; returns what is left, ranked. */
    fun removeItems(sessionId: String, ids: Collection<String>): List<ContextItem>

    /** Current items of a session, ranked. Empty when the session is unknown. */
    fun items(sessionId: String): List<ContextItem>

    /** Drops a session's live context. */
    fun clearSession(sessionId: String)

    /** Orders items by priority band, then relevance. Stable and deterministic. */
    fun rank(items: List<ContextItem>): List<ContextItem>

    /** Caps [items] to [budget], reporting truncations and exclusions. */
    fun enforceBudget(items: List<ContextItem>, budget: ContextBudget = ContextBudget.DEFAULT): ContextSelection

    /** Renders items into the final, model-ready text. */
    fun render(items: List<ContextItem>): String

    /** Conversation and tool-result context for one agent run. */
    fun newRunContext(sessionId: String, budget: ContextBudget = ContextBudget.DEFAULT): RunContext

    /** Developer view of one build: selection, reasons, truncations, size. */
    fun debug(result: ContextResult): ContextDebugReport
}

/**
 * The context of a single agent run: the growing conversation plus every tool
 * result, budgeted. It is deliberately the same shape the agent loop used
 * before the Context Engine existed, so the loop only had to swap the
 * implementation.
 */
interface RunContext {
    val sessionId: String

    /** True when at least one message or tool result was shortened or dropped. */
    val truncatedCount: Int

    /** Seeds the conversation with the system instruction and the user task. */
    fun start(systemPrompt: String, userPrompt: String)

    /** Restores a conversation snapshot (for example when resuming a run). */
    fun restore(messages: List<ModelMessage>)

    /**
     * Replaces the system instruction of the current conversation, keeping every
     * other message exactly as it is.
     *
     * Used when a run is resumed: the conversation and tool results must be
     * preserved, while the instruction that is prepended to the next model
     * request is resolved again so a Settings change takes effect. The
     * conversation ends up with exactly one system message, always first.
     */
    fun updateSystemPrompt(text: String)

    fun addAssistant(content: String, toolCalls: List<ModelToolCall> = emptyList())

    /**
     * Adds a tool result to the run context. The result is truncated to the
     * tool-result budget, recorded for inspection, and returned in bounded form
     * through [messages].
     */
    fun addToolResult(
        callId: String,
        toolName: String,
        content: String,
        path: String? = null,
        status: ToolContextStatus = ToolContextStatus.SUCCESS,
    )

    /** The bounded, model-ready conversation. Safe to call repeatedly. */
    fun messages(): List<ModelMessage>

    /** Context items recorded for this run (tool results and conversation). */
    fun items(): List<ContextItem>
}

/** Creates the [RunContext] for one agent run. */
fun interface RunContextFactory {
    fun create(sessionId: String, budget: ContextBudget): RunContext

    companion object {
        /** A factory backed by [engine]. */
        fun of(engine: ContextEngine): RunContextFactory =
            RunContextFactory { sessionId, budget -> engine.newRunContext(sessionId, budget) }

        /**
         * Standalone factory for callers that have no engine wired (tests, or an
         * agent assembled without the context module).
         */
        fun default(): RunContextFactory = of(DefaultContextEngine())
    }
}

val CONTEXT_LAYER = LayerDescriptor(
    id = "context",
    title = "Context Engine",
    summary = "Selects, ranks, limits and prepares the workspace and conversation context an agent task needs.",
    status = LayerStatus.ACTIVE,
)
