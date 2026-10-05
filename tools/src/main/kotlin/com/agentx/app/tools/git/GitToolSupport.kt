package com.agentx.app.tools.git

import com.agentx.app.core.ForgeResult
import com.agentx.app.git.GitError
import com.agentx.app.git.GitErrorCode
import com.agentx.app.git.GitResult
import com.agentx.app.tools.Json
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError

/**
 * The workspace a git tool was invoked for, or a structured failure.
 *
 * Git is resolved per call against the workspace the agent was told about, so a
 * tool can never read a different project than the one in context.
 */
internal fun ToolExecutionContext.requireWorkspaceId(toolName: String): String =
    workspaceId?.takeIf { it.isNotBlank() } ?: throw ToolExecutionError(
        code = ToolErrorCode.WORKSPACE_UNAVAILABLE,
        message = "No workspace is active for '$toolName'",
        toolName = toolName,
    )

internal fun <T> GitResult<T>.orThrowGit(toolName: String): T = when (this) {
    is ForgeResult.Success -> value
    is ForgeResult.Failure -> throw error.toToolError(toolName)
}

/**
 * Maps a structured [GitError] onto the tool result contract.
 *
 * The mapping is deliberately specific: an invalid commit message is an
 * argument problem, a missing project is an unavailable backend, and an
 * authentication failure is an authorization failure — none of them collapse
 * into a generic execution error.
 */
internal fun GitError.toToolError(toolName: String): ToolExecutionError {
    val code = when (this.code) {
        GitErrorCode.NO_WORKSPACE, GitErrorCode.PROJECT_UNAVAILABLE -> ToolErrorCode.WORKSPACE_UNAVAILABLE
        GitErrorCode.NOT_A_REPOSITORY, GitErrorCode.COMMAND_FAILED -> ToolErrorCode.EXECUTION_FAILED
        GitErrorCode.INVALID_MESSAGE -> ToolErrorCode.INVALID_ARGUMENTS
        GitErrorCode.AUTH_REQUIRED -> ToolErrorCode.PERMISSION_DENIED
    }
    return ToolExecutionError(
        code = code,
        message = userMessage,
        toolName = toolName,
        details = mapOf("gitCode" to Json.of(this.code.name)),
    )
}
