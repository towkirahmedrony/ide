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
import com.agentx.app.tools.missingWorkspace
import com.agentx.app.tools.orThrow
import com.agentx.app.tools.toToolError
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspacePath

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
                    name = "path",
                    type = ToolParameterType.STRING,
                    description = "Workspace-relative file path.",
                    required = true,
                ),
                ToolParameter(
                    name = "content",
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
        val path = input.string("path").orEmpty()
        val content = input.string("content").orEmpty()
        when (val written = fs.writeFile(path, content)) {
            is ForgeResult.Success -> Unit
            is ForgeResult.Failure -> {
                if (written.error.code == WorkspaceErrorCode.NOT_FOUND) {
                    createParentsAndFile(fs, path)
                    fs.writeFile(path, content).orThrow(NAME, path)
                } else {
                    throw written.error.toToolError(NAME, path)
                }
            }
        }
        return ToolOutput(
            content = mapOf(
                "path" to Json.of(path),
                "bytes" to Json.of(content.length),
            ),
            displayText = "Wrote $path (${content.length} bytes)",
        )
    }

    private suspend fun createParentsAndFile(fs: WorkspaceFileSystem, path: String) {
        val normalized = WorkspacePath.normalize(path).orThrow(NAME, path)
        if (normalized.isEmpty()) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "Cannot write the workspace root as a file",
                toolName = NAME,
            )
        }
        ensureDirectory(fs, WorkspacePath.parent(normalized))
        when (val created = fs.createFile(normalized)) {
            is ForgeResult.Success -> Unit
            is ForgeResult.Failure -> if (created.error.code != WorkspaceErrorCode.ALREADY_EXISTS) {
                throw created.error.toToolError(NAME, normalized)
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
    }
}
