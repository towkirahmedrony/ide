package com.agentx.app.foundation

import com.agentx.app.integrations.connection.ConnectionManager
import com.agentx.app.integrations.connection.ConnectionType
import com.agentx.app.integrations.connection.InstalledTool
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolConnectionCapability
import com.agentx.app.tools.ToolConnectionType
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolRegistry
import com.agentx.app.tools.extensions.ProviderToolEntry
import com.agentx.app.tools.extensions.ProviderToolInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps the Tool Registry in step with the connections the user actually made.
 *
 * The Connection Manager decides what a connection may do; this class mirrors that
 * decision into the Tool System, so provider tools appear when a service is
 * connected and disappear when it is disconnected, expires or loses a capability.
 * It never widens permissions: the router still checks the connection and the
 * tool's declared permission on every call.
 */
class IntegrationToolSynchronizer(
    private val manager: ConnectionManager,
    registry: ToolRegistry,
    private val scope: CoroutineScope,
) {

    private val installer = ProviderToolInstaller(registry)

    private var job: Job? = null

    /** Starts following the manager and synchronises the registry once immediately. */
    fun start() {
        sync(manager.tools())
        job = scope.launch {
            manager.state.collect { sync(manager.tools()) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Installed provider tool names after the last sync. */
    fun installedToolNames(): Set<String> = installer.installedNames()

    private fun sync(tools: List<InstalledTool>) {
        installer.sync(tools.mapNotNull { it.toEntry() })
    }
}

/**
 * Maps a provider tool onto the Tool System's own vocabulary.
 *
 * A tool whose provider is not one the Tool System knows about is skipped: the
 * registry only installs tools it can describe.
 */
private fun InstalledTool.toEntry(): ProviderToolEntry? {
    val connectionType = connectionType ?: return null
    val capability = connectionCapability ?: return null
    return ProviderToolEntry(
        toolName = toolName,
        title = title,
        description = description,
        connectionType = connectionType,
        connectionCapability = capability,
        category = category,
        mutating = mutating,
        implemented = implemented,
        enabled = enabled,
        permission = if (mutating) ToolPermissionDecision.ASK else ToolPermissionDecision.ALLOW,
    )
}

private val InstalledTool.connectionType: ToolConnectionType?
    get() = when (provider) {
        ConnectionType.GITHUB -> ToolConnectionType.GITHUB
        ConnectionType.SUPABASE -> ToolConnectionType.SUPABASE
        ConnectionType.MCP_SERVER -> ToolConnectionType.MCP_SERVER
        ConnectionType.CUSTOM_API -> null
    }

/**
 * The connection capabilities the Tool System knows about, keyed by the id the
 * Connection Manager uses. A provider capability that is not in this list is not
 * something a tool may request, so the tool is not installed.
 */
private val KNOWN_CAPABILITIES: Map<String, ToolConnectionCapability> = listOf(
    ToolConnectionCapability.REPOSITORY_READ,
    ToolConnectionCapability.REPOSITORY_WRITE,
    ToolConnectionCapability.PULL_REQUEST,
    ToolConnectionCapability.ISSUES,
    ToolConnectionCapability.DATABASE_READ,
    ToolConnectionCapability.DATABASE_WRITE,
    ToolConnectionCapability.STORAGE,
    ToolConnectionCapability.PROJECT_METADATA,
    ToolConnectionCapability.TOOLS,
    ToolConnectionCapability.RESOURCES,
    ToolConnectionCapability.PROMPTS,
).associateBy { it.id.lowercase() }

private val InstalledTool.connectionCapability: ToolConnectionCapability?
    get() = KNOWN_CAPABILITIES[requiredCapability.id.lowercase()]

private val InstalledTool.category: ToolCategory
    get() = when (provider) {
        ConnectionType.GITHUB -> ToolCategory.GIT
        ConnectionType.MCP_SERVER -> ToolCategory.MCP
        ConnectionType.SUPABASE -> ToolCategory.OTHER
        ConnectionType.CUSTOM_API -> ToolCategory.OTHER
    }
