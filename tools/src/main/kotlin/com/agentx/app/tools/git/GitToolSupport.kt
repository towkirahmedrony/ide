package com.agentx.app.tools.git

import com.agentx.app.core.ForgeResult
import com.agentx.app.git.GitError
import com.agentx.app.git.GitErrorCode
import com.agentx.app.git.GitPushError
import com.agentx.app.git.GitPushFailure
import com.agentx.app.git.GitResult
import com.agentx.app.tools.Json
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.isProtectedWorkspacePath
import com.agentx.app.tools.toToolError
import com.agentx.app.workspace.WorkspacePath

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
 * Validates a repository-relative path before it reaches Git.
 *
 * The workspace's own path rule (the one the file tools use) refuses absolute paths
 * and `..` traversal; on top of it, `.git` internals and git pathspec "magic" (a
 * leading `:`) are refused, so a path the model supplied can never stage, diff or
 * otherwise reach repository metadata.
 */
internal fun validateRepositoryPath(raw: String, toolName: String): String {
    val normalized = when (val result = WorkspacePath.normalize(raw)) {
        is ForgeResult.Success -> result.value
        is ForgeResult.Failure -> throw result.error.toToolError(toolName, raw)
    }
    if (normalized.isEmpty() || isProtectedWorkspacePath(normalized) || normalized.startsWith(':')) {
        throw ToolExecutionError(
            code = ToolErrorCode.INVALID_ARGUMENTS,
            message = "Refusing the protected path '$raw'",
            toolName = toolName,
            details = mapOf("path" to Json.of(raw)),
        )
    }
    return normalized
}

/**
 * Maps a structured push failure onto the tool result contract.
 *
 * The specific category is preserved in `details["pushCode"]` so an authentication
 * failure, a branch mismatch and a non-fast-forward rejection stay distinguishable
 * even though they share a coarse [ToolErrorCode].
 */
internal fun GitPushError.toToolError(toolName: String): ToolExecutionError {
    val code = when (failure) {
        GitPushFailure.AUTHENTICATION,
        GitPushFailure.AUTHORIZATION,
        GitPushFailure.NO_CONNECTION,
        GitPushFailure.CREDENTIAL_UNAVAILABLE,
        -> ToolErrorCode.PERMISSION_DENIED

        GitPushFailure.BRANCH_MISMATCH,
        GitPushFailure.FORCE_PUSH_FORBIDDEN,
        GitPushFailure.REMOTE_NOT_GITHUB,
        GitPushFailure.NOT_A_REPOSITORY,
        -> ToolErrorCode.INVALID_ARGUMENTS

        GitPushFailure.WORKSPACE_UNAVAILABLE -> ToolErrorCode.WORKSPACE_UNAVAILABLE

        GitPushFailure.NETWORK,
        GitPushFailure.REPOSITORY_NOT_FOUND,
        GitPushFailure.REMOTE_REJECTED,
        GitPushFailure.NON_FAST_FORWARD,
        GitPushFailure.RATE_LIMITED,
        GitPushFailure.CANCELLED,
        GitPushFailure.UNKNOWN,
        -> ToolErrorCode.EXECUTION_FAILED
    }
    return ToolExecutionError(
        code = code,
        message = userMessage,
        toolName = toolName,
        details = mapOf("pushCode" to Json.of(failure.name)),
    )
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
