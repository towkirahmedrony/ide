package com.agentx.app.tools.filesystem

import com.agentx.app.tools.Json
import com.agentx.app.tools.SecretRedactor
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

class ReadFileTool(
    private val workspaces: WorkspaceFileSystemResolver,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Read file",
        description = "Reads a UTF-8 text file from the opened workspace. Paths are workspace-relative.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = "path",
                    type = ToolParameterType.STRING,
                    description = "Workspace-relative file path.",
                    required = true,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "File path, size, and contents."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM),
        category = ToolCategory.FILESYSTEM,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val fs = workspaces.resolve(context) ?: missingWorkspace(NAME)
        val path = input.string("path").orEmpty()
        val raw = fs.readFile(path).orThrow(NAME, path)
        val fileName = path.substringAfterLast('/')
        val content = if (
            fileName.equals(".env", ignoreCase = true) ||
            fileName.endsWith(".env", ignoreCase = true) ||
            SecretRedactor.looksSecret(fileName)
        ) {
            SecretRedactor.REDACTED
        } else {
            SecretRedactor.redactText(raw)
        }
        return ToolOutput(
            content = mapOf(
                "path" to Json.of(path),
                "content" to Json.of(content),
                "bytes" to Json.of(content.length),
            ),
            displayText = content,
        )
    }

    companion object {
        const val NAME = "read_file"
    }
}
