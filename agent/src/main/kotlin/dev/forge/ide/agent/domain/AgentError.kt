package dev.forge.ide.agent.domain

enum class AgentErrorCode {
    MAX_STEPS_EXCEEDED,
    TIMEOUT,
    CANCELLED,
    MODEL_FAILURE,
    TOOL_FAILURE,
    PERMISSION_DENIED,
    UNKNOWN_AGENT,
    INVALID_DELEGATION,
    NOT_CONFIGURED,
    UNKNOWN,
}

data class AgentError(
    val code: AgentErrorCode,
    val message: String,
    val role: AgentRole? = null,
    val sessionId: String? = null,
    val cause: Throwable? = null,
    val details: Map<String, String> = emptyMap(),
)
