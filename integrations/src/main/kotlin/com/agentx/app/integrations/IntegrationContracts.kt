package com.agentx.app.integrations

import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.architecture.LayerStatus

enum class IntegrationKind {
    MCP,
    WEB,
    BROWSER,
    CUSTOM,
}

data class IntegrationDescriptor(
    val id: String,
    val name: String,
    val kind: IntegrationKind,
)

interface Integration {
    val descriptor: IntegrationDescriptor
}

// --- MCP -------------------------------------------------------------------

/** Connection settings for an MCP server. No server is configured by default. */
data class McpServerConfig(
    val id: String,
    val transport: String,
    val command: String? = null,
    val url: String? = null,
)

data class McpToolDescriptor(
    val name: String,
    val description: String,
)

/** Client port for a single MCP server. Implemented in a later task. */
interface McpClient {
    val serverId: String

    suspend fun listTools(): List<McpToolDescriptor>

    suspend fun callTool(name: String, arguments: Map<String, Any?>): String
}

// --- Web / browser ---------------------------------------------------------

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String? = null,
)

/** Web search/fetch capability exposed to agents. */
interface WebTool {
    suspend fun search(query: String, limit: Int = 10): List<WebSearchResult>
}

/** A controllable browser session for richer web interactions. */
interface BrowserSession {
    val id: String

    suspend fun navigate(url: String)

    suspend fun snapshot(): String
}

/** Tracks the integrations available to the platform. */
interface IntegrationRegistry {
    fun register(integration: Integration)

    fun descriptors(): List<IntegrationDescriptor>
}

val INTEGRATIONS_LAYER = LayerDescriptor(
    id = "integrations",
    title = "Integrations",
    summary = "Manages external service connections and credentials for the tool system.",
    status = LayerStatus.ACTIVE,
)
