package dev.forge.ide.tools

import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.architecture.LayerStatus

/** JSON-like payload used for tool arguments and results. */
typealias JsonObject = Map<String, Any?>

/** Describes a callable tool, including its JSON-schema input. */
data class ToolDefinition(
    val id: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject = emptyMap(),
    val requiresApproval: Boolean = false,
)

sealed interface ToolResult {
    data class Success(val output: JsonObject) : ToolResult

    data class Failure(val message: String) : ToolResult
}

/** A capability the agent can invoke. Implemented in a later task. */
interface Tool {
    val definition: ToolDefinition

    suspend fun execute(input: JsonObject): ToolResult
}

/** Registry of the tools available to the agent. */
interface ToolRegistry {
    fun register(tool: Tool)

    fun get(id: String): Tool?

    fun definitions(): List<ToolDefinition>
}

val TOOLS_LAYER = LayerDescriptor(
    id = "tools",
    title = "Tool System",
    summary = "Exposes filesystem, git, web, MCP, and other capabilities to agents as tools.",
    status = LayerStatus.CONTRACT_ONLY,
)
