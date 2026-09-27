package com.agentx.app.tools.filesystem

import com.agentx.app.core.ForgeResult
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
import com.agentx.app.workspace.WorkspaceDirectory
import com.agentx.app.workspace.WorkspaceFile
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspacePath

class SearchFilesTool(
    private val workspaces: WorkspaceFileSystemResolver,
) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Search files",
        description = "Searches file names and UTF-8 contents inside the opened workspace.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = "query",
                    type = ToolParameterType.STRING,
                    description = "Text to search for in paths and file contents.",
                    required = true,
                ),
                ToolParameter(
                    name = "path",
                    type = ToolParameterType.STRING,
                    description = "Workspace-relative directory to search. Defaults to the workspace root.",
                    required = false,
                ),
                ToolParameter(
                    name = "maxResults",
                    type = ToolParameterType.NUMBER,
                    description = "Maximum number of matches to return. Defaults to 50.",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "Matching paths and a short snippet when the query hits file contents."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM),
        category = ToolCategory.SEARCH,
        requiredPermissions = setOf(ToolPermissionLevel.READ_ONLY),
        metadata = mapOf("sideEffects" to "none"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val fs = workspaces.resolve(context) ?: missingWorkspace(NAME)
        val query = input.string("query").orEmpty()
        val root = input.string("path") ?: WorkspacePath.ROOT
        val maxResults = input.number("maxResults")?.toInt()?.coerceIn(1, MAX_RESULTS) ?: DEFAULT_MAX_RESULTS
        val matches = mutableListOf<Match>()
        walk(fs, root, query, maxResults, matches)
        val items = matches.map { match ->
            Json.obj(
                buildMap {
                    put("path", Json.of(match.path))
                    put("kind", Json.of(match.kind))
                    match.snippet?.let { put("snippet", Json.of(it)) }
                },
            )
        }
        val display = if (matches.isEmpty()) {
            "No matches for \"$query\""
        } else {
            matches.joinToString("\n") { match ->
                buildString {
                    append(match.path)
                    match.snippet?.let { append(": ").append(it) }
                }
            }
        }
        return ToolOutput(
            content = mapOf(
                "query" to Json.of(query),
                "path" to Json.of(root),
                "matches" to Json.array(items),
                "count" to Json.of(matches.size),
            ),
            displayText = display,
        )
    }

    private suspend fun walk(
        fs: WorkspaceFileSystem,
        path: String,
        query: String,
        maxResults: Int,
        matches: MutableList<Match>,
    ) {
        if (matches.size >= maxResults) return
        val listing = when (val result = fs.list(path)) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> {
                if (path.isNotEmpty() && query.isNotEmpty() && path.contains(query, ignoreCase = true)) {
                    matches += Match(path, "path", null)
                }
                return
            }
        }
        for (node in listing) {
            if (matches.size >= maxResults) return
            val pathHit = query.isNotEmpty() && node.path.contains(query, ignoreCase = true)
            when (node) {
                is WorkspaceDirectory -> {
                    if (pathHit) matches += Match(node.path, "path", null)
                    walk(fs, node.path, query, maxResults, matches)
                }
                is WorkspaceFile -> {
                    var added = false
                    if (pathHit) {
                        matches += Match(node.path, "path", null)
                        added = true
                    }
                    if (matches.size >= maxResults) return
                    val read = fs.readFile(node.path)
                    if (read is ForgeResult.Success) {
                        val content = SecretRedactor.redactText(read.value)
                        val index = content.indexOf(query, ignoreCase = true)
                        if (index >= 0 && !added) {
                            matches += Match(node.path, "content", snippet(content, index, query.length))
                        }
                    }
                }
            }
        }
    }

    private fun snippet(content: String, index: Int, length: Int): String {
        val start = (index - 40).coerceAtLeast(0)
        val end = (index + length + 40).coerceAtMost(content.length)
        return SecretRedactor.redactText(content.substring(start, end).replace('\n', ' ').trim())
    }

    private data class Match(val path: String, val kind: String, val snippet: String?)

    companion object {
        const val NAME = "search_files"
        const val DEFAULT_MAX_RESULTS = 50
        const val MAX_RESULTS = 200
    }
}
