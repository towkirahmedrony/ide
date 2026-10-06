package com.agentx.app.tools.filesystem

import com.agentx.app.core.ForgeResult
import com.agentx.app.tools.Json
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolInputSchema
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolOutputSpec
import com.agentx.app.tools.ToolParameter
import com.agentx.app.tools.ToolParameterType
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.WorkspaceFileSystemResolver
import com.agentx.app.tools.isProtectedWorkspacePath
import com.agentx.app.tools.missingWorkspace
import com.agentx.app.tools.orThrow
import com.agentx.app.tools.toToolError
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspacePath

/**
 * Creates or overwrites one workspace file for the agent.
 *
 * This is a mutating tool: its declared permission is ASK, so the router parks
 * the call for the user's approval and this code only ever runs once a decision
 * exists. It never accepts a filesystem root — the path is workspace-relative and
 * the workspace filesystem resolves it against the managed root, rejecting
 * absolute paths, `..`, and any canonical target outside the workspace.
 *
 * On top of those rules this tool refuses AgentX-protected internals (`.git`),
 * refuses to write the workspace root, and creates missing parent directories
 * only through the same validated filesystem.
 */
class WriteFileTool(
    private val workspaces: WorkspaceFileSystemResolver,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Write file",
        description = "Creates or overwrites a UTF-8 text file inside the opened workspace. Paths are workspace-relative.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_PATH,
                    type = ToolParameterType.STRING,
                    description = "Workspace-relative file path.",
                    required = true,
                ),
                ToolParameter(
                    name = ARG_CONTENT,
                    type = ToolParameterType.STRING,
                    description = "UTF-8 file contents to write.",
                    required = true,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "Written path and byte count."),
        // Editing a file is medium risk: the router pauses for the user's
        // approval (WAITING_FOR_PERMISSION) instead of writing automatically.
        permission = ToolPermissionDecision.ASK,
        capabilities = setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM),
        category = ToolCategory.FILESYSTEM,
        requiredPermissions = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
        metadata = mapOf("sideEffects" to "workspace-write"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val fs = workspaces.resolve(context) ?: missingWorkspace(NAME)
        val path = input.string(ARG_PATH).orEmpty()
        val content = input.string(ARG_CONTENT).orEmpty()

        val normalized = WorkspacePath.normalize(path).orThrow(NAME, path)
        requireWritablePath(path, normalized)

        when (val written = fs.writeFile(normalized, content)) {
            is ForgeResult.Success -> Unit
            is ForgeResult.Failure -> {
                if (written.error.code == WorkspaceErrorCode.NOT_FOUND) {
                    createParentsAndFile(fs, normalized)
                    fs.writeFile(normalized, content).orThrow(NAME, normalized)
                } else {
                    throw written.error.toToolError(NAME, normalized)
                }
            }
        }
        return ToolOutput(
            content = mapOf(
                "path" to normalized,
                "bytes" to Json.of(content.length),
            ),
            displayText = "Wrote $normalized (${content.length} bytes)",
        )
    }

    /** Rejects a path that is valid but must never be written by the agent. */
    private fun requireWritablePath(rawPath: String, normalized: String) {
        if (normalized.isEmpty()) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "Cannot write the workspace root as a file",
                toolName = NAME,
                details = mapOf("path" to Json.of(rawPath)),
            )
        }
        if (isProtectedWorkspacePath(normalized)) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "Writing inside .git is not allowed",
                toolName = NAME,
                details = mapOf("path" to Json.of(rawPath)),
            )
        }
    }

    private suspend fun createParentsAndFile(fs: WorkspaceFileSystem, path: String) {
        ensureDirectory(fs, WorkspacePath.parent(path))
        when (val created = fs.createFile(path)) {
            is ForgeResult.Success -> Unit
            is ForgeResult.Failure -> if (created.error.code != WorkspaceErrorCode.ALREADY_EXISTS) {
                throw created.error.toToolError(NAME, path)
            }
        }
    }

    private suspend fun ensureDirectory(fs: WorkspaceFileSystem, path: String) {
        if (path.isEmpty()) return
        if (fs.exists(path)) return
        ensureDirectory(fs, WorkspacePath.parent(path))
        when (val created = fs.createDirectory(path)) {
            is ForgeResult.Success -> Unit
            is ForgeResult.Failure -> if (created.error.code != WorkspaceErrorCode.ALREADY_EXISTS) {
                throw created.error.toToolError(NAME, path)
            }
        }
    }

    companion object {
        const val NAME = "write_file"
        const val ARG_PATH = "path"
        const val ARG_CONTENT = "content"
    }
}
