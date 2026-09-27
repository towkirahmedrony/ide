package com.agentx.app.tools.filesystem

import com.agentx.app.tools.Json
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolExecutionContext
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
import com.agentx.app.workspace.WorkspaceDirectory
import com.agentx.app.workspace.WorkspaceFile
import com.agentx.app.workspace.WorkspacePath

class ListDirectoryTool(
    private val workspaces: WorkspaceFileSystemResolver,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "List directory",
        description = "Lists files and folders inside the opened workspace. Paths are workspace-relative.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = "path",
                    type = ToolParameterType.STRING,
                    description = "Workspace-relative directory to list. Defaults to the workspace root.",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "Directory entries with kind and path."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM),
        category = ToolCategory.FILESYSTEM,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val fs = workspaces.resolve(context) ?: missingWorkspace(NAME)
        val path = input.string("path") ?: WorkspacePath.ROOT
        val entries = fs.list(path).orThrow(NAME, path)
        val items = entries.map { node ->
            Json.obj(
                "path" to Json.of(node.path),
                "name" to Json.of(node.name),
                "kind" to Json.of(
                    when (node) {
                        is WorkspaceDirectory -> "directory"
                        is WorkspaceFile -> "file"
                    },
                ),
            )
        }
        val display = if (entries.isEmpty()) {
            "(empty)"
        } else {
            entries.joinToString("\n") { node ->
                val kind = if (node is WorkspaceDirectory) "dir" else "file"
                "$kind ${node.path}"
            }
        }
        return ToolOutput(
            content = mapOf(
                "path" to Json.of(path),
                "entries" to Json.array(items),
                "count" to Json.of(entries.size),
            ),
            displayText = display,
        )
    }

    companion object {
        const val NAME = "list_directory"
    }
}
