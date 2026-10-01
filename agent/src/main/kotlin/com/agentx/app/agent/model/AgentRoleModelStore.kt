package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentRole

/**
 * One role's saved model assignment: which provider connection the role uses and,
 * when the user picked one, which model within it.
 *
 * It holds identifiers only. A credential, an endpoint and every other secret
 * stay with the provider connection the id refers to, so a role never duplicates
 * a key and removing a connection never leaks one. [connectionId] records the
 * saved connection (model preset) the user chose from, when any, so a removed
 * connection can be reported instead of silently replaced.
 */
data class RoleModelConfig(
    val role: AgentRole,
    val providerId: String,
    /** Model within [providerId]; null means the provider/definition default. */
    val model: String? = null,
    /** Saved connection (model preset) this role was assigned from, when any. */
    val connectionId: String? = null,
    val updatedAtMillis: Long = 0L,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
    }

    /** The resolver-facing preference: provider and model, never an endpoint. */
    fun toPreference(): RoleModelPreference = RoleModelPreference(providerId, model)
}

/**
 * Persistence for per-role model assignments.
 *
 * Only overrides are stored: the built-in mapping lives in
 * [AgentModelPreferences.DEFAULT], so an installation with no saved role
 * configuration keeps working exactly as before and a cleared entry falls back
 * to the default rather than leaving a role unconfigured.
 */
interface AgentRoleModelStore {
    suspend fun loadAll(): List<RoleModelConfig>

    suspend fun upsert(config: RoleModelConfig)

    suspend fun delete(role: AgentRole)
}

/** In-memory [AgentRoleModelStore] for tests, previews and non-Android hosts. */
class InMemoryAgentRoleModelStore(initial: List<RoleModelConfig> = emptyList()) : AgentRoleModelStore {

    private val configs = LinkedHashMap<AgentRole, RoleModelConfig>()

    init {
        initial.forEach { configs[it.role] = it }
    }

    override suspend fun loadAll(): List<RoleModelConfig> = configs.values.toList()

    override suspend fun upsert(config: RoleModelConfig) {
        configs[config.role] = config
    }

    override suspend fun delete(role: AgentRole) {
        configs.remove(role)
    }
}

/** Read/write facade over an [AgentRoleModelStore]. */
interface AgentRoleModelRepository {
    suspend fun stored(): List<RoleModelConfig>

    suspend fun stored(role: AgentRole): RoleModelConfig?

    suspend fun save(config: RoleModelConfig)

    suspend fun delete(role: AgentRole)
}

class DefaultAgentRoleModelRepository(
    private val store: AgentRoleModelStore,
) : AgentRoleModelRepository {

    override suspend fun stored(): List<RoleModelConfig> = store.loadAll()

    override suspend fun stored(role: AgentRole): RoleModelConfig? =
        store.loadAll().firstOrNull { it.role == role }

    override suspend fun save(config: RoleModelConfig) = store.upsert(config)

    override suspend fun delete(role: AgentRole) = store.delete(role)
}
