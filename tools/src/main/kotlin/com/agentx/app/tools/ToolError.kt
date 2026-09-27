package com.agentx.app.tools

/**
 * Structured tool failure as data. [ToolExecutionError] is the throwable form
 * used inside tool implementations; the router always exposes this envelope
 * through [ToolResult.Failure].
 */
data class ToolError(
    val code: ToolErrorCode,
    val message: String,
    val toolId: ToolId? = null,
    val details: JsonObject = emptyMap(),
    val cause: Throwable? = null,
) {
    fun toExecutionError(): ToolExecutionError = ToolExecutionError(
        code = code,
        message = message,
        toolName = toolId?.value,
        details = details,
        cause = cause,
    )
}

fun ToolExecutionError.toToolError(): ToolError = ToolError(
    code = code,
    message = message ?: "Tool failed",
    toolId = toolName?.let { runCatching { ToolId(it) }.getOrNull() },
    details = details,
    cause = cause,
)
