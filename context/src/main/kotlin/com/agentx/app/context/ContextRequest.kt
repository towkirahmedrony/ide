package com.agentx.app.context

import com.agentx.app.model.ModelMessage

/** Everything the engine may use to decide what context an agent task needs. */
data class ContextRequest(
    /** The current user request. Also scanned for explicitly mentioned files. */
    val task: String,
    val sessionId: String? = null,
    val workspaceId: String? = null,
    /**
     * Whether the request itself becomes an item. The agent loop already sends
     * the prompt as the user message, so it asks for supporting context only.
     */
    val includeTask: Boolean = true,
    /** Paths the user or the UI explicitly pointed at, in priority order. */
    val mentionedFiles: List<String> = emptyList(),
    /**
     * Files the user attached to this message.
     *
     * An attachment is a reference into the workspace, so it is loaded by the same
     * file loader as [mentionedFiles] — protected-path checked, redacted, truncated
     * and cached identically. Attaching a file is a stronger statement than naming
     * one, but it is not a different kind of context.
     */
    val attachments: List<AgentAttachment> = emptyList(),
    /** The file currently open in the editor. */
    val selectedFile: String? = null,
    /** Files open in editor tabs. */
    val openFiles: List<String> = emptyList(),
    /** Recently used files, most recent first. */
    val recentFiles: List<String> = emptyList(),
    /** Paths reported by SearchFilesTool, best match first. */
    val searchResults: List<String> = emptyList(),
    /** Directories the task explicitly needs a listing of. */
    val directories: List<String> = emptyList(),
    /**
     * Whether this turn is about the project.
     *
     * A conversational turn ("Hi", "thanks") must not be answered with a
     * description of the open project. When this is false the engine contributes
     * no workspace *facts* of its own: no root directory listing and no workspace
     * descriptor. Only what the caller explicitly supplied (mentioned files, the
     * open file, tool results) is used, so nothing is inspected behind the user's
     * back.
     */
    val includeWorkspace: Boolean = true,
    /** Adds a small summary item for the workspace root (one directory read). */
    val includeWorkspaceRootSummary: Boolean = true,
    /** Adds the workspace name/root/selection item. */
    val includeWorkspaceInfo: Boolean = true,
    val toolResults: List<ToolContextResult> = emptyList(),
    /** Prior conversation turns, oldest first. */
    val conversation: List<ModelMessage> = emptyList(),
    val agentState: ContextAgentState? = null,
    /**
     * Compact session memory (current task, completed work, important files).
     * Rendered as supporting context; never replaces the current user message.
     */
    val sessionSummary: String? = null,
    /** Structured execution state for the current session. */
    val taskState: ContextAgentState? = null,
    /** Overrides the engine's default budget for this request only. */
    val budget: ContextBudget? = null,
)

/** Compact description of what the running agent has done so far. */
data class ContextAgentState(
    val role: String? = null,
    val status: String? = null,
    val step: Int = 0,
    val maxSteps: Int = 0,
    val progress: String? = null,
)

/** The final, budgeted, model-ready context plus the audit trail for it. */
data class ContextResult(
    val items: List<ContextItem>,
    /** Rendered, model-ready text. Empty when nothing survived the budget. */
    val text: String,
    val excluded: List<ContextExclusion> = emptyList(),
    val truncated: List<ContextTruncation> = emptyList(),
    val usedChars: Int = items.sumOf { it.chars },
    val budget: ContextBudget = ContextBudget.DEFAULT,
    val metadata: ContextMetadata = ContextMetadata(),
) {
    val estimatedTokens: Int get() = budget.estimateTokens(usedChars)

    val limitChars: Int get() = budget.charLimit

    val isEmpty: Boolean get() = items.isEmpty()

    fun item(id: String): ContextItem? = items.firstOrNull { it.id == id }

    fun itemsOf(source: ContextSource): List<ContextItem> = items.filter { it.source == source }

    /** Ids in selection order; handy for deterministic comparisons. */
    val selectedIds: List<String> get() = items.map { it.id }
}

/**
 * Developer-facing description of one context build: what was selected, why,
 * what was truncated and what was excluded. It never contains item content, so
 * it is safe to log or show.
 */
data class ContextDebugReport(
    val summary: String,
    val lines: List<String>,
) {
    override fun toString(): String = lines.joinToString("\n")
}
