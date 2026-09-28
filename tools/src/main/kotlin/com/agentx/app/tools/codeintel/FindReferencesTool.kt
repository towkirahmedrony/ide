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
 * Finds every occurrence of a name in one file.
 *
 * File-scoped by design: without a repository index, cross-file references
 * cannot be answered honestly, and the result says so instead of implying it
 * searched the project.
 */
class FindReferencesTool(
    private val workspaces: WorkspaceFileSystemResolver,
    private val codeIntelligence: CodeIntelligence,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Find references",
        description = "Finds every occurrence of a name inside one workspace file, marking the " +
            "declarations. It does not search other files.",
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
                    description = "Symbol name to look for.",
                    required = true,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "Occurrences with their ranges and whether each one is a declaration."),
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

        val references = when (val result = codeIntelligence.findReferences(source.asSourceFile(), name)) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> throw result.error.toToolError(NAME, path)
        }

        val definitions = references.count { it.isDefinition }
        val body = if (references.isEmpty()) {
            "No occurrence of '$name' in $path."
        } else {
            buildString {
                append(references.size).append(" occurrences of '").append(name).append("' in ").append(path)
                append(" (").append(definitions).append(" declaration(s), ")
                append(references.size - definitions).append(" usage(s))\n")
                references.forEach { reference ->
                    append(if (reference.isDefinition) "declaration" else "usage")
                    append(" · line ").append(reference.range.start.line)
                    append('.').append(reference.range.start.column).append('\n')
                }
            }.trimEnd()
        }

        return ToolOutput(
            content = mapOf(
                "path" to Json.of(path),
                "name" to Json.of(name),
                "count" to Json.of(references.size),
                "declarations" to Json.of(definitions),
                "references" to Json.array(references.map { reference -> reference.toJsonValue() }),
            ),
            displayText = body,
        )
    }

    companion object {
        const val NAME = "find_references"
    }
}
