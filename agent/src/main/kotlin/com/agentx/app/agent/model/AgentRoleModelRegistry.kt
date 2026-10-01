package com.agentx.app.agent.model

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentRole

/**
 * The single authoritative role → model configuration shared by Settings and
 * [AgentModelResolver].
 *
 * It overlays the user's persisted per-role choices on the built-in defaults and
 * caches the effective mapping, so [preferences] is safe to read on every model
 * request. Only provider and model identifiers are stored here — never a
 * credential, endpoint or connection secret; those stay with the provider
 * connection the role points at.
 *
 * The runtime path is:
 * `Settings → this registry → AgentModelResolver → ModelConfig → ModelGateway`.
 * There is no second, hardcoded role mapping anywhere else.
 */
class AgentRoleModelRegistry(
    private val repository: AgentRoleModelRepository,
    private val defaults: AgentModelPreferences = AgentModelPreferences.DEFAULT,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    @Volatile
    private var overrides: Map<AgentRole, RoleModelConfig> = emptyMap()

    /**
     * Loads saved per-role overrides. Safe to call at startup and again when the
     * app returns to the foreground; it is idempotent.
     */
    suspend fun load() {
        overrides = repository.stored().associateBy { it.role }
    }

    /** The role → model mapping consumed by [AgentModelResolver]. */
    fun preferences(): AgentModelPreferences {
        val byRole = defaults.byRole.toMutableMap()
        overrides.forEach { (role, config) ->
            byRole[role] = config.toPreference()
        }
        return AgentModelPreferences(byRole)
    }

    /** The built-in preference for [role], before any user override. */
    fun default(role: AgentRole): RoleModelPreference? = defaults[role]

    /** The user's saved override for [role], or null when it follows the default. */
    fun override(role: AgentRole): RoleModelConfig? = overrides[role]

    /** Every saved override, keyed by role. */
    fun overrides(): Map<AgentRole, RoleModelConfig> = overrides

    /**
     * The effective assignment of [role]: the user's override when present, the
     * built-in preference otherwise, with the catalog's model as a last resort.
     */
    fun selection(role: AgentRole): RoleModelSelection {
        val override = overrides[role]
        val preference = override?.toPreference() ?: defaults[role]
        return RoleModelSelection(
            role = role,
            providerId = preference?.providerId,
            model = preference?.model?.takeIf { it.isNotBlank() }
                ?: AgentCatalog.definition(role).modelPreference,
            connectionId = override?.connectionId,
            explicit = override != null,
        )
    }

    /** Every role's effective assignment, in enum order. */
    fun selections(): List<RoleModelSelection> = AgentRole.entries.map(::selection)

    /**
     * Saves [role]'s provider/model choice and updates the live mapping
     * immediately, so the next run uses it without a restart.
     */
    suspend fun save(
        role: AgentRole,
        providerId: String,
        model: String? = null,
        connectionId: String? = null,
    ): RoleModelConfig {
        val config = RoleModelConfig(
            role = role,
            providerId = providerId.trim(),
            model = model?.trim()?.takeIf { it.isNotBlank() },
            connectionId = connectionId?.trim()?.takeIf { it.isNotBlank() },
            updatedAtMillis = clock(),
        )
        repository.save(config)
        overrides = overrides + (role to config)
        return config
    }

    /** Removes [role]'s override, restoring the built-in default. */
    suspend fun reset(role: AgentRole) {
        repository.delete(role)
        overrides = overrides - role
    }
}

/** The effective model assignment of one role, before availability is judged. */
data class RoleModelSelection(
    val role: AgentRole,
    val providerId: String?,
    val model: String?,
    val connectionId: String?,
    /** True when the user explicitly saved this assignment rather than the default. */
    val explicit: Boolean,
)
