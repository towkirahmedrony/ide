package com.agentx.app.tools

/** Lifecycle of one tool invocation after it has been routed. */
enum class ToolExecutionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    PERMISSION_REQUIRED,
}

val ToolResult.status: ToolExecutionStatus
    get() = when (this) {
        is ToolResult.Success -> ToolExecutionStatus.SUCCEEDED
        is ToolResult.ApprovalRequired -> ToolExecutionStatus.PERMISSION_REQUIRED
        is ToolResult.Failure -> when (error.code) {
            ToolErrorCode.PERMISSION_DENIED,
            ToolErrorCode.APPROVAL_REQUIRED,
            -> ToolExecutionStatus.PERMISSION_REQUIRED
            ToolErrorCode.TIMEOUT -> ToolExecutionStatus.TIMED_OUT
            ToolErrorCode.CANCELLED -> ToolExecutionStatus.CANCELLED
            else -> ToolExecutionStatus.FAILED
        }
    }
