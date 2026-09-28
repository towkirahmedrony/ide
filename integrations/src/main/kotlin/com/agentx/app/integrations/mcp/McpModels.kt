package com.agentx.app.integrations.mcp

import com.agentx.app.integrations.McpServerConfig
import com.agentx.app.integrations.connection.Connection
import com.agentx.app.integrations.connection.ConnectionAuthMethod
import com.agentx.app.integrations.connection.ConnectionCapabilities
import com.agentx.app.integrations.connection.ConnectionCapability
import com.agentx.app.integrations.connection.ConnectionConfig
import com.agentx.app.integrations.connection.ConnectionType

/**
 * How an MCP server is reached. This is metadata only: no transport is opened
 * and no MCP command is executed in this task.
 */
enum class McpTransportKind(val displayName: String, val wireName: String) {
    STDIO("Standard I/O", "stdio"),
    SSE("Server-sent events", "sse"),
    HTTP("HTTP", "http"),
    ;

    companion object {
        fun fromWire(raw: String?): McpTransportKind =
            entries.firstOrNull { it.wireName.equals(raw?.trim(), ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: STDIO
    }
}

/**
 * Transport port for a future MCP client. Implementations must not execute
 * arbitrary commands; they only describe how a server would be reached.
 */
interface McpTransport {
    val kind: McpTransportKind

    val description: String
}

data class StdioMcpTransport(
    val command: String,
    val arguments: List<String> = emptyList(),
) : McpTransport {
    override val kind: McpTransportKind = McpTransportKind.STDIO
    override val description: String = "stdio"
}

data class SseMcpTransport(
    val url: String,
) : McpTransport {
    override val kind: McpTransportKind = McpTransportKind.SSE
    override val description: String = "sse"
}

data class HttpMcpTransport(
    val url: String,
) : McpTransport {
    override val kind: McpTransportKind = McpTransportKind.HTTP
    override val description: String = "http"
}

/** Capability metadata advertised by an MCP server. Definitions only. */
data class McpCapabilityMetadata(
    val tools: Boolean = true,
    val resources: Boolean = true,
    val prompts: Boolean = true,
) {
    fun toConnectionCapabilities(): Set<ConnectionCapability> = buildSet {
        if (tools) add(ConnectionCapabilities.TOOLS)
        if (resources) add(ConnectionCapabilities.RESOURCES)
        if (prompts) add(ConnectionCapabilities.PROMPTS)
    }

    companion object {
        val ALL = McpCapabilityMetadata()

        fun from(capabilities: Set<ConnectionCapability>): McpCapabilityMetadata = McpCapabilityMetadata(
            tools = ConnectionCapabilities.TOOLS in capabilities,
            resources = ConnectionCapabilities.RESOURCES in capabilities,
            prompts = ConnectionCapabilities.PROMPTS in capabilities,
        )
    }
}

/**
 * Typed view of an MCP connection. Built from a generic [Connection] or the
 * existing [McpServerConfig] contract. No client is started here.
 */
data class McpConnection(
    val serverId: String,
    val config: McpServerConfig,
    val transport: McpTransport,
    val capabilities: McpCapabilityMetadata = McpCapabilityMetadata.ALL,
) {
    companion object {
        fun from(config: McpServerConfig): McpConnection {
            val kind = McpTransportKind.fromWire(config.transport)
            val transport = when (kind) {
                McpTransportKind.STDIO -> StdioMcpTransport(command = config.command.orEmpty())
                McpTransportKind.SSE -> SseMcpTransport(url = config.url.orEmpty())
                McpTransportKind.HTTP -> HttpMcpTransport(url = config.url.orEmpty())
            }
            return McpConnection(serverId = config.id, config = config, transport = transport)
        }

        fun from(connection: Connection): McpConnection {
            val transportKind = McpTransportKind.fromWire(
                connection.config.metadata[META_TRANSPORT] ?: McpTransportKind.STDIO.wireName,
            )
            val command = connection.config.metadata[META_COMMAND]
            val url = connection.config.endpoint
            val config = McpServerConfig(
                id = connection.id.value,
                transport = transportKind.wireName,
                command = command,
                url = url,
            )
            return from(config).copy(capabilities = McpCapabilityMetadata.from(connection.capabilities))
        }
    }
}

const val META_TRANSPORT: String = "mcp.transport"
const val META_COMMAND: String = "mcp.command"

/** Builds the non-secret [ConnectionConfig] for an MCP server. */
fun mcpConnectionConfig(
    transport: McpTransportKind,
    endpoint: String? = null,
    command: String? = null,
    authMethod: ConnectionAuthMethod = ConnectionAuthMethod.NONE,
): ConnectionConfig = ConnectionConfig(
    endpoint = endpoint?.takeIf { it.isNotBlank() },
    authMethod = authMethod,
    metadata = buildMap {
        put(META_TRANSPORT, transport.wireName)
        command?.takeIf { it.isNotBlank() }?.let { put(META_COMMAND, it) }
    },
)

fun Connection.asMcpConnection(): McpConnection? =
    if (type == ConnectionType.MCP_SERVER) McpConnection.from(this) else null
