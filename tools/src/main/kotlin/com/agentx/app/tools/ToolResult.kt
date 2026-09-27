package com.agentx.app.tools

/** Stable, coarse-grained categories for tool failures. */
enum class ToolErrorCode {
    UNKNOWN_TOOL,
    DUPLICATE_TOOL,
    INVALID_DEFINITION,
    INVALID_ARGUMENTS,
    PERMISSION_DENIED,
    APPROVAL_REQUIRED,
    EXECUTION_FAILED,
    TIMEOUT,
    CANCELLED,
    WORKSPACE_UNAVAILABLE,
}

/**
 * Structured description of why a tool call did not succeed. It carries a
 * stable [code] so callers (and eventually the agent core) can branch on the
 * failure category instead of parsing messages.
 */
class ToolExecutionError(
    val code: ToolErrorCode,
    message: String,
    val toolName: String? = null,
    val details: JsonObject = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    override fun toString(): String =
        "ToolExecutionError(code=$code, tool=$toolName, message=$message)"
}

/**
 * Normalized envelope returned by the router for every tool invocation,
 * regardless of which tool or permission policy was involved.
 */
sealed interface ToolResult {
    val toolName: String

    /** The tool ran and produced structured [output]. */
    data class Success(
        override val toolName: String,
        val output: ToolOutput,
        val durationMillis: Long = 0,
    ) : ToolResult

    /** The call could not run; [error] explains why in a structured form. */
    data class Failure(
        override val toolName: String,
        val error: ToolExecutionError,
    ) : ToolResult

    /**
     * Policy returned ASK: execution is paused until the caller supplies an
     * approved [ToolApproval] on the next invocation. No UI exists yet; this is
     * only the domain contract a future approval prompt will use.
     */
    data class ApprovalRequired(
        override val toolName: String,
        val request: ToolApprovalRequest,
    ) : ToolResult
}
