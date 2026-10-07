package com.agentx.app.ui.ide.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.policy.AgentToolPolicy
import com.agentx.app.tools.DefaultToolPreferences
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolCategory
import com.agentx.app.tools.ToolConnectionType
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.ToolPreferences
import com.agentx.app.tools.ToolRegistry
import com.agentx.app.tools.effectiveAvailability
import kotlinx.coroutines.launch

/** The three states a tool can be in, as shown in Settings → Tools. */
enum class ToolStatus(val label: String) {
    ENABLED("Enabled"),
    DISABLED("Disabled"),
    UNAVAILABLE("Unavailable"),
}

/** Quick status filters above the list. */
enum class ToolFilter(val label: String) {
    ALL("All"),
    ENABLED("Enabled"),
    DISABLED("Disabled"),
    UNAVAILABLE("Unavailable"),
}

/**
 * Presentation view of one tool, derived entirely from its [ToolDefinition] and
 * the live enablement. It never invents a tool: a card exists only because the
 * registry holds the tool.
 */
data class ToolEntry(
    val id: String,
    val title: String,
    val description: String,
    val category: ToolCategory,
    val categoryLabel: String,
    val status: ToolStatus,
    val available: Boolean,
    val enabled: Boolean,
    val permissionLabel: String,
    val accessLabel: String,
    val capabilities: List<String>,
    val connectionLabel: String?,
    val unavailableReason: String?,
    val roles: List<String>,
) {
    /** Only an available tool can be turned on or off; an unavailable one has no backend. */
    val editable: Boolean get() = available
}

/**
 * Settings → Tools. Reads the live [ToolRegistry] and writes the same
 * [DefaultToolPreferences] the Tool Router and the agent tool bridge read, so a
 * switch here changes what the agent is offered and may run.
 *
 * Both collaborators are nullable so a preview renders the screen without a
 * wired Tool System; in that case the screen reports that the tool system is
 * unavailable rather than showing a fabricated list.
 */
class ToolsViewModel(
    private val registry: ToolRegistry?,
    private val preferences: DefaultToolPreferences?,
) : ViewModel() {

    var tools by mutableStateOf<List<ToolEntry>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var query by mutableStateOf("")
        private set

    var statusFilter by mutableStateOf(ToolFilter.ALL)
        private set

    var categoryFilter by mutableStateOf<ToolCategory?>(null)
        private set

    /** True when the Tool System was wired; false in a preview or a headless test. */
    val available: Boolean get() = registry != null

    init {
        refresh()
    }

    fun refresh() {
        loading = true
        val definitions = registry?.definitions().orEmpty()
        tools = definitions
            .map { definition -> definition.toEntry(preferences) }
            .sortedWith(compareBy<ToolEntry>({ it.category.ordinal }, { it.title.lowercase() }))
        loading = false
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val prefs = preferences ?: return
        viewModelScope.launch {
            prefs.setEnabled(id, enabled)
            refresh()
        }
    }

    fun reset() {
        val prefs = preferences ?: return
        viewModelScope.launch {
            prefs.reset()
            refresh()
            message = "All tools are enabled again."
        }
    }

    fun updateQuery(value: String) {
        query = value
    }

    fun updateStatusFilter(value: ToolFilter) {
        statusFilter = value
    }

    fun updateCategoryFilter(value: ToolCategory?) {
        categoryFilter = value
    }

    fun dismissMessage() {
        message = null
    }

    /** Categories present in the current catalog, in a stable order. */
    val categories: List<ToolCategory>
        get() = tools.map { it.category }.distinct().sortedBy { it.ordinal }

    val enabledCount: Int get() = tools.count { it.status == ToolStatus.ENABLED }
    val disabledCount: Int get() = tools.count { it.status == ToolStatus.DISABLED }
    val unavailableCount: Int get() = tools.count { it.status == ToolStatus.UNAVAILABLE }

    /** The list after the search box, status filter and category filter are applied. */
    val filtered: List<ToolEntry>
        get() {
            val needle = query.trim().lowercase()
            val status = statusFilter
            val category = categoryFilter
            return tools.filter { entry ->
                val matchesQuery = needle.isEmpty() ||
                    entry.title.lowercase().contains(needle) ||
                    entry.id.lowercase().contains(needle) ||
                    entry.description.lowercase().contains(needle)
                val matchesStatus = when (status) {
                    ToolFilter.ALL -> true
                    ToolFilter.ENABLED -> entry.status == ToolStatus.ENABLED
                    ToolFilter.DISABLED -> entry.status == ToolStatus.DISABLED
                    ToolFilter.UNAVAILABLE -> entry.status == ToolStatus.UNAVAILABLE
                }
                matchesQuery && matchesStatus && (category == null || entry.category == category)
            }
        }

    /** [filtered] grouped by category, in category order. */
    val grouped: List<Pair<ToolCategory, List<ToolEntry>>>
        get() = filtered
            .groupBy { it.category }
            .toList()
            .sortedBy { it.first.ordinal }
}

/** Human-readable name of a category. */
internal fun ToolCategory.label(): String = when (this) {
    ToolCategory.FILESYSTEM -> "Filesystem"
    ToolCategory.SEARCH -> "Search"
    ToolCategory.COMMAND -> "Command"
    ToolCategory.GIT -> "Git"
    ToolCategory.WEB -> "Web"
    ToolCategory.BROWSER -> "Browser"
    ToolCategory.MCP -> "MCP"
    ToolCategory.OTHER -> "Other"
}

/** Human-readable name of a required connection type. */
internal fun ToolConnectionType.label(): String = when (this) {
    ToolConnectionType.GITHUB -> "GitHub"
    ToolConnectionType.SUPABASE -> "Supabase"
    ToolConnectionType.MCP_SERVER -> "MCP server"
    ToolConnectionType.CUSTOM_API -> "custom API"
}

/** How a call is gated, in plain language. */
internal fun ToolPermissionDecision.label(): String = when (this) {
    ToolPermissionDecision.ALLOW -> "Runs automatically"
    ToolPermissionDecision.ASK -> "Asks for approval"
    ToolPermissionDecision.DENY -> "Blocked by policy"
}

/**
 * A concise security summary: what the tool can reach or change. Derived from the
 * declared capabilities and required permission levels, never asserted by the UI.
 */
internal fun ToolDefinition.accessLabel(): String {
    val labels = buildList {
        if (ToolCapability.SHELL in capabilities || ToolPermissionLevel.COMMAND_EXECUTION in requiredPermissions) {
            add("Runs shell commands")
        }
        if (ToolCapability.MUTATING in capabilities || ToolPermissionLevel.WORKSPACE_WRITE in requiredPermissions) {
            add("Changes files")
        }
        if (ToolCapability.GIT in capabilities || ToolPermissionLevel.GIT_WRITE in requiredPermissions) {
            add("Writes to Git")
        }
        if (ToolCapability.NETWORK in capabilities || ToolPermissionLevel.NETWORK in requiredPermissions) {
            add("Uses the network")
        }
        if (ToolCapability.CREDENTIALS in capabilities) {
            add("Needs a connection")
        }
    }
    return if (labels.isEmpty()) "Read-only" else labels.joinToString(" · ")
}

/**
 * Why a declared tool cannot run, or null when it can. Kept honest: an
 * unimplemented tool says so, and a connection-backed tool names the connection
 * it needs rather than claiming to work.
 */
internal fun ToolDefinition.unavailableReason(): String? {
    if (effectiveAvailability.isAvailable) return null
    val requirement = connectionRequirement
    return when {
        requirement != null -> "Not connected — needs a ${requirement.type.label()} connection"
        category == ToolCategory.BROWSER -> "Browser automation is not implemented in this build"
        category == ToolCategory.MCP -> "MCP servers are not connected in this build"
        else -> "Not implemented in this build"
    }
}

/** The role display names whose policy holds [toolId]. Empty for a provider tool. */
private fun rolesFor(toolId: String): List<String> = AgentRole.entries
    .filter { role -> toolId in AgentToolPolicy.toolIdsFor(role) }
    .map { role -> AgentCatalog.definition(role).name }

/** Maps a registry definition onto the card model, using the live enablement. */
internal fun ToolDefinition.toEntry(preferences: ToolPreferences?): ToolEntry {
    val availability = effectiveAvailability.isAvailable
    val enabled = preferences?.isEnabled(name) ?: true
    return ToolEntry(
        id = name,
        title = title,
        description = description,
        category = category,
        categoryLabel = category.label(),
        available = availability,
        enabled = availability && enabled,
        status = when {
            !availability -> ToolStatus.UNAVAILABLE
            enabled -> ToolStatus.ENABLED
            else -> ToolStatus.DISABLED
        },
        permissionLabel = permission.label(),
        accessLabel = accessLabel(),
        capabilities = capabilities.map { it.name.lowercase().replace('_', ' ') }.sorted(),
        connectionLabel = connectionRequirement?.type?.label(),
        unavailableReason = unavailableReason(),
        roles = rolesFor(name),
    )
}
