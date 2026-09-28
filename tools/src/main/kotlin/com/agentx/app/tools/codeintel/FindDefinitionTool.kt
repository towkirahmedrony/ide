package com.agentx.app.tools.codeintel

import com.agentx.app.codeintel.CodeIntelligence
import com.agentx.app.core.ForgeResult
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
 * Finds the declarations of a name in one file.
 *
 * Scope is intentionally the analysed file: this build has no repository index,
 * and a tool that pretended to search dependencies would be lying about its
 * result.
 */
class FindDefinitionTool(
    private val workspaces: WorkspaceFileSystemResolver,
    private val codeIntelligence: CodeIntelligence,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Find definition",
        description = "Finds the declarations of a name inside one workspace file. " +
            "It does not search other files or dependencies.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_PATH,
                    type = ToolParameterType.STRING,
                    description = "Workspace-relative file path to search in.",
                    required = true,
                ),
                ToolParameter(
                    name = ARG_NAME,
                    type = ToolParameterType.STRING,
                    description = "Symbol name: simple (getUser) or qualified (UserRepository.getUser).",
                    required = true,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "Every matching declaration with its range."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM),
        category = ToolCategory.SEARCH,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none", "scope" to "single-file"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val fileSystem = workspaceFor(NAME, workspaces, context)
        val path = input.string(ARG_PATH).orEmpty()
        val name = input.requireName(NAME)
        val source = analyzeSource(NAME, fileSystem, codeIntelligence, path)

        val definitions = when (val result = codeIntelligence.findDefinitions(source.asSourceFile(), name)) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> throw result.error.toToolError(NAME, path)
        }

        val body = if (definitions.isEmpty()) {
            "No declaration of '$name' in $path."
        } else {
            definitions.joinToString("\n") { symbol ->
                "${symbol.kind.displayName} ${symbol.qualifiedName} · line ${symbol.range.start.line}"
            }
        }

        return ToolOutput(
            content = mapOf(
                "path" to Json.of(path),
                "name" to Json.of(name),
                "count" to Json.of(definitions.size),
                "definitions" to Json.array(definitions.map { symbol -> symbol.toJsonValue() }),
            ),
            displayText = body,
        )
    }

    companion object {
        const val NAME = "find_definition"
    }
}
