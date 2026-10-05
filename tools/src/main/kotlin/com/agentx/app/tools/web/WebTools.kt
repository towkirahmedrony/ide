package com.agentx.app.tools.web

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

/**
 * Fetches one URL and returns its text body.
 *
 * Read-only network access: it declares READ_ONLY + NETWORK and requires the
 * NETWORK permission level, so only a role whose ceiling includes network (the
 * Researcher) can run it. The transport is a port, never hardcoded to a model
 * provider.
 */
class WebFetchTool(private val client: HttpGetClient) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Web fetch",
        description = "Fetches a single http(s) URL and returns its status, content type and text body.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_URL,
                    type = ToolParameterType.STRING,
                    description = "Absolute http(s) URL to fetch.",
                    required = true,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "HTTP status, content type, resolved URL and the body text."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.NETWORK, ToolCapability.READ_ONLY),
        category = ToolCategory.WEB,
        requiredPermissions = setOf(ToolPermissionLevel.NETWORK),
        metadata = mapOf("sideEffects" to "network-read"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val raw = input.string(ARG_URL)?.trim().orEmpty()
        val uri = runCatching { java.net.URI(raw) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "'$ARG_URL' must be an absolute http(s) URL",
                toolName = NAME,
                details = mapOf(ARG_URL to Json.of(raw)),
            )
        }
        val response = try {
            client.get(raw, emptyMap())
        } catch (unavailable: WebUnavailableException) {
            throw ToolExecutionError(
                code = ToolErrorCode.TOOL_UNAVAILABLE,
                message = unavailable.message ?: "Web fetch is unavailable",
                toolName = NAME,
            )
        } catch (error: Throwable) {
            throw ToolExecutionError(
                code = ToolErrorCode.EXECUTION_FAILED,
                message = error.message ?: "Failed to fetch '$raw'",
                toolName = NAME,
                cause = error,
            )
        }
        return ToolOutput(
            content = mapOf(
                "url" to Json.of(response.finalUrl),
                "statusCode" to Json.of(response.statusCode),
                "contentType" to (response.contentType?.let { Json.of(it) } ?: com.agentx.app.tools.JsonValue.Null),
                "bytes" to Json.of(response.body.length),
                "body" to Json.of(response.body),
            ),
            displayText = response.body.ifBlank { "(empty response · HTTP ${response.statusCode})" },
        )
    }

    companion object {
        const val NAME = "web_fetch"
        const val ARG_URL = "url"
    }
}

/**
 * Searches the web and returns structured hits.
 *
 * The provider is injected, so the search capability comes from a real tool
 * provider (which [DuckDuckGoWebSearchProvider] implements) and never from a
 * model. The tool only shapes the query and the results.
 */
class WebSearchTool(private val provider: WebSearchProvider) : Tool {

    override val definition = ToolDefinition(
        name = NAME,
        title = "Web search",
        description = "Search the web and return result titles, URLs and snippets.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                ToolParameter(
                    name = ARG_QUERY,
                    type = ToolParameterType.STRING,
                    description = "Search query.",
                    required = true,
                ),
                ToolParameter(
                    name = ARG_LIMIT,
                    type = ToolParameterType.NUMBER,
                    description = "Maximum number of results (default ${DEFAULT_LIMIT}).",
                    required = false,
                ),
            ),
        ),
        output = ToolOutputSpec(description = "A list of results with title, url and snippet."),
        permission = ToolPermissionDecision.ALLOW,
        capabilities = setOf(ToolCapability.NETWORK, ToolCapability.READ_ONLY),
        category = ToolCategory.WEB,
        requiredPermissions = setOf(ToolPermissionLevel.NETWORK),
        metadata = mapOf("sideEffects" to "network-read"),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        val query = input.string(ARG_QUERY)?.trim().orEmpty()
        if (query.isEmpty()) {
            throw ToolExecutionError(
                code = ToolErrorCode.INVALID_ARGUMENTS,
                message = "'$ARG_QUERY' must not be blank",
                toolName = NAME,
            )
        }
        val limit = input.number(ARG_LIMIT)?.toInt()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT
        val results = try {
            provider.search(query, limit)
        } catch (unavailable: WebUnavailableException) {
            throw ToolExecutionError(
                code = ToolErrorCode.TOOL_UNAVAILABLE,
                message = unavailable.message ?: "Web search is unavailable",
                toolName = NAME,
            )
        } catch (error: Throwable) {
            throw ToolExecutionError(
                code = ToolErrorCode.EXECUTION_FAILED,
                message = error.message ?: "Search for '$query' failed",
                toolName = NAME,
                cause = error,
            )
        }
        return ToolOutput(
            content = mapOf(
                "query" to Json.of(query),
                "count" to Json.of(results.size),
                "results" to Json.array(
                    results.map { item ->
                        Json.obj(
                            "title" to Json.of(item.title),
                            "url" to Json.of(item.url),
                            "snippet" to Json.of(item.snippet),
                        )
                    },
                ),
            ),
            displayText = if (results.isEmpty()) {
                "No results for \"$query\""
            } else {
                results.joinToString("\n") { item -> "${item.title}\n${item.url}\n${item.snippet}" }
            },
        )
    }

    companion object {
        const val NAME = "web_search"
        const val ARG_QUERY = "query"
        const val ARG_LIMIT = "limit"
        const val DEFAULT_LIMIT = 8
        const val MAX_LIMIT = 25
    }
}
