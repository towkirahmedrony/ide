package com.agentx.app.tools

import com.agentx.app.core.ForgeResult
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspaceManager

/**
 * Resolves the workspace filesystem the current tool call may use. Tools never
 * open arbitrary paths; they go through the Workspace Runtime.
 */
fun interface WorkspaceFileSystemResolver {
    fun resolve(context: ToolExecutionContext): WorkspaceFileSystem?
}

class WorkspaceManagerFileSystemResolver(
    private val manager: WorkspaceManager,
) : WorkspaceFileSystemResolver {
    override fun resolve(context: ToolExecutionContext): WorkspaceFileSystem? {
        val current = manager.current ?: return null
        val requested = context.workspaceId
        if (requested.isNullOrBlank()) return current.fileSystem
        return if (current.workspace.id.value == requested) current.fileSystem else null
    }
}

internal fun missingWorkspace(toolName: String): Nothing = throw ToolExecutionError(
    code = ToolErrorCode.WORKSPACE_UNAVAILABLE,
    message = "No workspace is open for '$toolName'",
    toolName = toolName,
)

internal fun WorkspaceError.toToolError(toolName: String, path: String?): ToolExecutionError {
    val code = when (this.code) {
        WorkspaceErrorCode.PERMISSION_DENIED -> ToolErrorCode.PERMISSION_DENIED
        WorkspaceErrorCode.ABSOLUTE_PATH,
        WorkspaceErrorCode.PATH_TRAVERSAL,
        WorkspaceErrorCode.INVALID_PATH,
        -> ToolErrorCode.INVALID_ARGUMENTS
        else -> ToolErrorCode.EXECUTION_FAILED
    }
    return ToolExecutionError(
        code = code,
        message = userMessage,
        toolName = toolName,
        details = buildMap {
            put("workspaceCode", Json.of(this@toToolError.code.name))
            path?.let { put("path", Json.of(it)) }
        },
        cause = cause,
    )
}

internal fun <T> ForgeResult<T, WorkspaceError>.orThrow(toolName: String, path: String?): T = when (this) {
    is ForgeResult.Success -> value
    is ForgeResult.Failure -> throw error.toToolError(toolName, path ?: error.path)
}
