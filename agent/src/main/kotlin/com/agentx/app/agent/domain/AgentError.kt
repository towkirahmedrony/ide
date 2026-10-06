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

    /** A verification stage ran and failed. */
    VERIFICATION_FAILED,

    /** No verification could be run (no CI, no connection). */
    VERIFICATION_UNAVAILABLE,

    /** A GitHub Actions run completed with a failing conclusion. */
    CI_FAILURE,

    /** A GitHub API call failed for a reason that is not authentication or transport. */
    GITHUB_API_FAILURE,

    /** A credential was rejected or is missing. */
    AUTHENTICATION_FAILURE,

    /** The remote could not be reached. */
    NETWORK_FAILURE,

    /** A workspace or path security rule refused the operation. */
    WORKSPACE_SECURITY_FAILURE,

    /** A likely secret was detected in the change set. */
    SECRET_DETECTED,

    /** The workflow stopped without a result (retry budget exhausted, verification blocked). */
    BLOCKED,

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
