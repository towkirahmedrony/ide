package com.agentx.app.tools.extensions

import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolConnectionCapability
import com.agentx.app.tools.ToolConnectionRequirement
import com.agentx.app.tools.ToolConnectionType
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.ToolRegistry

/**
 * One tool a provider contributes, described without depending on the
 * integrations module. The app maps a provider's catalog onto these entries and
 * asks [ProviderToolInstaller] to sync them.
 */
data class ProviderToolEntry(
    val toolName: String,
    val title: String,
    val description: String,
    val connectionType: ToolConnectionType,
    val connectionCapability: ToolConnectionCapability,
    val category: ToolCategory,
    val mutating: Boolean,
    /** False when the tool is declared but its implementation is not written yet. */
    val implemented: Boolean,
    /** True when the connection currently provides everything the tool needs. */
    val enabled: Boolean,
    /** Permission the tool asks for; mutating tools ask before they run. */
    val permission: ToolPermissionDecision = if (mutating) {
        ToolPermissionDecision.ASK
    } else {
        ToolPermissionDecision.ALLOW
    },
    val requiredPermissions: Set<ToolPermissionLevel> = defaultPermissions(mutating),
) {
    companion object {
        fun defaultPermissions(mutating: Boolean): Set<ToolPermissionLevel> =
            if (mutating) {
                setOf(ToolPermissionLevel.NETWORK, ToolPermissionLevel.GIT_WRITE)
            } else {
                setOf(ToolPermissionLevel.NETWORK)
            }
    }
}

/**
 * A provider tool.
 *
 * It always carries the connection requirement, so the router asks the Connection
 * Manager for authorization before the tool runs, and a tool whose implementation
 * is not written yet refuses with a clear message instead of pretending to work.
 * The credential never reaches this class: a future implementation asks the
 * connection layer for one internally.
 */
class ProviderTool(
    private val entry: ProviderToolEntry,
) : Tool {

    override val definition = ToolDefinition(
        name = entry.toolName,
        title = entry.title,
        description = entry.description,
        permission = entry.permission,
        capabilities = buildSet {
            add(ToolCapability.NETWORK)
            add(ToolCapability.CREDENTIALS)
            if (entry.mutating) {
                add(ToolCapability.MUTATING)
                add(ToolCapability.GIT)
            } else {
                add(ToolCapability.READ_ONLY)
            }
        },
        category = entry.category,
        requiredPermissions = entry.requiredPermissions,
        metadata = buildMap {
            put("provider", entry.connectionType.name.lowercase())
            put("implemented", entry.implemented.toString())
            put("mutating", entry.mutating.toString())
            put("capability", entry.connectionCapability.id.lowercase())
        },
        connectionRequirement = ToolConnectionRequirement(
            type = entry.connectionType,
            capability = entry.connectionCapability,
        ),
    )

    override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
        throw ToolExecutionError(
            code = ToolErrorCode.PERMISSION_DENIED,
            message = if (entry.implemented) {
                "'${definition.name}' is installed but its service call is not implemented yet"
            } else {
                "'${definition.name}' is declared but not enabled in this build"
            },
            toolName = definition.name,
        )
    }
}

/**
 * Keeps the Tool System in step with the connections that actually exist.
 *
 * Tools are installed while their provider is connected with the required
 * capability, and removed on disconnect, so the agent's tool list never claims a
 * capability the user has not authorized. Installation is idempotent, and it only
 * touches the names it owns.
 */
class ProviderToolInstaller(
    private val registry: ToolRegistry,
) {

    private var installed: Set<String> = emptySet()

    /** Currently installed provider tool names. */
    fun installedNames(): Set<String> = installed

    /**
     * Registers every [entries] tool that is enabled and unregisters the ones that
     * stopped being available. Returns the names installed after the sync.
     */
    @Synchronized
    fun sync(entries: List<ProviderToolEntry>): Set<String> {
        val shouldBeInstalled = entries.filter { it.enabled && it.implemented }.map { it.toolName }.toSet()

        // Remove tools that are no longer available, e.g. after a disconnect.
        (installed - shouldBeInstalled).forEach { name -> registry.unregister(name) }

        entries.filter { it.enabled && it.implemented && it.toolName !in installed }
            .forEach { entry -> registry.register(ProviderTool(entry)) }

        installed = shouldBeInstalled
        return installed
    }

    /** Removes every provider tool; used when connections are reset. */
    @Synchronized
    fun clear() {
        installed.forEach { name -> registry.unregister(name) }
        installed = emptySet()
    }
}
