package com.agentx.app.tools.codeintel

import com.agentx.app.codeintel.CodeIntelligence
import com.agentx.app.codeintel.SymbolKind
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

/**
 * Structural view of one file: which declarations exist and where.
 *
 * Read-only and cheap compared to `read_file`: the agent gets the shape of a
 * file without pulling its whole text into the conversation.
 */
class GetFileSymbolsTool(
    private val workspaces: WorkspaceFileSystemResolver,
    private val codeIntelligence: CodeIntelligence,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Get file symbols",
        description = "Lists the declarations of a workspace file (classes, interfaces, functions, " +
            "methods, properties, …) with their line and column ranges. The file's text is not returned.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_PATH,
                    type = ToolParameterType.STRING,
                    description = "Workspace-relative file path.",
                    required = true,
                ),
                ToolParameter(
                    name = ARG_KIND,
                    type = ToolParameterType.STRING,
                    description = "Optional filter by symbol kind.",
                    enumValues = SymbolKind.entries.map { it.displayName },
                ),
            ),
        ),
        output = ToolOutputSpec(
            description = "Detected language, symbol list, and whether parsing found syntax errors " +
                "or hit an extraction limit.",
        ),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM),
        category = ToolCategory.FILESYSTEM,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val fileSystem = workspaceFor(NAME, workspaces, context)
        val path = input.string(ARG_PATH).orEmpty()
        val rawKind = input.string(ARG_KIND)?.takeIf { it.isNotBlank() }
        val kind = rawKind?.let { value ->
            parseSymbolKind(value) ?: throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "Unknown symbol kind '$value'",
                toolName = NAME,
                details = mapOf("allowed" to Json.array(symbolKindNames())),
            )
        }

        val parsed = analyzeSource(NAME, fileSystem, codeIntelligence, path).parsed
        val symbols = if (kind == null) parsed.symbols else parsed.symbols.filter { it.kind == kind }
        return ToolOutput(
            content = mapOf(
                "path" to Json.of(parsed.path),
                "language" to Json.of(parsed.language.displayName),
                "count" to Json.of(symbols.size),
                "syntaxErrors" to Json.of(parsed.hasSyntaxErrors),
                "outlineTruncated" to Json.of(parsed.outline.truncated),
                "symbols" to Json.array(symbols.map { symbol -> symbol.toJsonValue() }),
            ),
            displayText = renderSymbols(parsed, symbols, filtered = kind != null),
        )
    }

    companion object {
        const val NAME = "get_file_symbols"
    }
}
