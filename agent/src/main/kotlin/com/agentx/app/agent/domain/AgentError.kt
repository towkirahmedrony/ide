package com.agentx.app.agent.domain

enum class AgentErrorCode {
    MAX_STEPS_EXCEEDED,
    TIMEOUT,
    CANCELLED,
    MODEL_FAILURE,
    MALFORMED_RESPONSE,
    TOOL_FAILURE,
    TOOL_UNKNOWN,
    TOOL_ARGUMENTS_INVALID,
    PERMISSION_DENIED,
    SUB_AGENT_FAILURE,
    CONTEXT_TOO_LARGE,
    UNKNOWN_AGENT,
    INVALID_DELEGATION,
    NOT_CONFIGURED,
    MODEL_CAPABILITY_UNSUPPORTED,

    /** The resolved model cannot run the role right now (capability/disabled/rate limit). */
    MODEL_NOT_ELIGIBLE,

    /**
     * An explicit role assignment names a connection/provider that is not
     * currently connected. Resolution fails instead of substituting another
     * connection or the active model; see [AgentError.details] for the role,
     * connection and whether an intentional fallback is configured.
     */
    MODEL_NOT_CONNECTED,
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
