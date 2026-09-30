package com.agentx.app.agent.conversation

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentSession
import com.agentx.app.agent.domain.AgentStatus

/** Role of a persisted conversation message. Distinct from the model-wire [com.agentx.app.model.ModelRole]. */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
    SUB_AGENT,
    ERROR,
}

/** Lifecycle of one persisted message. Tokens are never stored as separate rows. */
enum class MessageStatus {
    STARTED,
    STREAMING,
    COMPLETED,
    ERROR,
}

/** Structured payload of one conversation message. */
data class MessageContent(
    val text: String = "",
    val toolName: String? = null,
    val toolArguments: String? = null,
    val toolResult: String? = null,
    val toolCallId: String? = null,
    val subAgentRole: String? = null,
    val subAgentSessionId: String? = null,
    val errorCode: String? = null,
)

/** Non-secret provenance kept next to a message. */
data class MessageMetadata(
    val status: MessageStatus = MessageStatus.COMPLETED,
    val timestampMillis: Long = 0L,
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val toolSuccess: Boolean? = null,
    val truncated: Boolean = false,
    val redacted: Boolean = false,
    val attributes: Map<String, String> = emptyMap(),
)

data class ConversationMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: MessageContent,
    val metadata: MessageMetadata = MessageMetadata(),
)

/**
 * Compact, reusable memory for a long session. Updated from structured
 * metadata (tool records, files, errors) rather than an extra model call.
 */
data class SessionSummary(
    val currentTask: String? = null,
    val completed: List<String> = emptyList(),
    val discoveredFiles: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val unresolved: List<String> = emptyList(),
    val requirements: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val toolHighlights: List<String> = emptyList(),
    val updatedAtMillis: Long = 0L,
) {
    fun isEmpty(): Boolean =
        currentTask.isNullOrBlank() &&
            completed.isEmpty() &&
            discoveredFiles.isEmpty() &&
            decisions.isEmpty() &&
            unresolved.isEmpty() &&
            requirements.isEmpty() &&
            constraints.isEmpty() &&
            toolHighlights.isEmpty()

    fun render(): String {
        if (isEmpty()) return ""
        return buildString {
            currentTask?.takeIf { it.isNotBlank() }?.let {
                append("Current task:\n").append(it.trim()).append('\n')
            }
            fun section(title: String, items: List<String>) {
                val kept = items.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (kept.isEmpty()) return
                append('\n').append(title).append(":\n")
                kept.forEach { append("- ").append(it).append('\n') }
            }
            section("Completed", completed)
            section("Important files", discoveredFiles)
            section("Decisions", decisions)
            section("Unresolved", unresolved)
            section("User requirements", requirements)
            section("Constraints", constraints)
            section("Tool results", toolHighlights)
        }.trim()
    }
}

/** Structured execution state that complements the natural-language transcript. */
data class SessionTaskState(
    val activeTask: String? = null,
    val currentPlan: String? = null,
    val currentStep: String? = null,
    val completedSteps: List<String> = emptyList(),
    val pendingSteps: List<String> = emptyList(),
    val relevantFiles: List<String> = emptyList(),
    val lastToolResult: String? = null,
    val lastError: String? = null,
    val workspaceId: String? = null,
) {
    fun isEmpty(): Boolean =
        activeTask.isNullOrBlank() &&
            currentPlan.isNullOrBlank() &&
            currentStep.isNullOrBlank() &&
            completedSteps.isEmpty() &&
            pendingSteps.isEmpty() &&
            relevantFiles.isEmpty() &&
            lastToolResult.isNullOrBlank() &&
            lastError.isNullOrBlank()
}

/**
 * One Agent Session's conversation. [session] reuses the domain [AgentSession];
 * messages never leak across sessions.
 */
data class AgentConversation(
    val session: AgentSession,
    val messages: List<ConversationMessage> = emptyList(),
    val summary: SessionSummary = SessionSummary(),
    val taskState: SessionTaskState = SessionTaskState(),
) {
    val id: String get() = session.id
    val workspaceId: String? get() = session.workspaceId
    val role: AgentRole get() = session.role
    val status: AgentStatus get() = session.status
}
