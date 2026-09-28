package com.agentx.app.tools.codeintel

import com.agentx.app.codeintel.CodeIntelligence
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

/**
 * The outline of one file, nested by containment — the same view the editor
 * shows. It is the cheapest way for the agent to see how a file is organised.
 */
class GetFileOutlineTool(
    private val workspaces: WorkspaceFileSystemResolver,
    private val codeIntelligence: CodeIntelligence,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Get file outline",
        description = "Renders the outline of a workspace file: its declarations nested by " +
            "containment, each with the line it starts on.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_PATH,
                    type = ToolParameterType.STRING,
                    description = "Workspace-relative file path.",
                    required = true,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "Detected language and the rendered outline."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM),
        category = ToolCategory.FILESYSTEM,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val fileSystem = workspaceFor(NAME, workspaces, context)
        val path = input.string(ARG_PATH).orEmpty()
        val parsed = analyzeSource(NAME, fileSystem, codeIntelligence, path).parsed
        val outline = renderOutlineText(parsed.outline)

        return ToolOutput(
            content = mapOf(
                "path" to Json.of(parsed.path),
                "language" to Json.of(parsed.language.displayName),
                "symbolCount" to Json.of(parsed.outline.symbolCount),
                "truncated" to Json.of(parsed.outline.truncated),
                "syntaxErrors" to Json.of(parsed.hasSyntaxErrors),
                "outline" to Json.of(outline),
            ),
            displayText = outline,
        )
    }

    companion object {
        const val NAME = "get_file_outline"
    }
}
