package com.agentx.app.tools

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
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
        val current = manager.current
        val requested = context.workspaceId
        if (current == null) {
            logger.info(
                "Workspace resolver found no open workspace",
                mapOf("requestedWorkspaceId" to requested, "sessionId" to context.sessionId),
            )
            return null
        }
        if (requested.isNullOrBlank()) return current.fileSystem
        if (current.workspace.id.value == requested) return current.fileSystem
        logger.info(
            "Workspace resolver rejected a mismatched workspace id",
            mapOf(
                "requestedWorkspaceId" to requested,
                "currentWorkspaceId" to current.workspace.id.value,
                "sessionId" to context.sessionId,
            ),
        )
        return null
    }

    private companion object {
        val logger = ForgeLoggers.create(LogLevel.INFO, baseFields = mapOf("layer" to "tools"))
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
