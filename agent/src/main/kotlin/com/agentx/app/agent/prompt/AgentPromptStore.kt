package com.agentx.app.agent.prompt

import com.agentx.app.agent.domain.AgentRole

/**
 * Persistence for user-customized agent prompts.
 *
 * Only overrides are stored. The shipped defaults live in
 * [DefaultAgentPrompts], so a missing or corrupt entry always falls back to a
 * working prompt instead of disabling an agent.
 */
interface AgentPromptStore {
    suspend fun loadAll(): List<AgentPromptConfig>

    suspend fun upsert(config: AgentPromptConfig)

    suspend fun delete(role: AgentRole)
}

/** In-memory [AgentPromptStore] for tests, previews and non-Android hosts. */
class InMemoryAgentPromptStore : AgentPromptStore {

    private val prompts = linkedMapOf<AgentRole, AgentPromptConfig>()

    override suspend fun loadAll(): List<AgentPromptConfig> = prompts.values.toList()

    override suspend fun upsert(config: AgentPromptConfig) {
        prompts[config.role] = config
    }

    override suspend fun delete(role: AgentRole) {
        prompts.remove(role)
    }
}

/** Read/write facade over an [AgentPromptStore]. */
interface AgentPromptRepository {
    suspend fun stored(): List<AgentPromptConfig>

    suspend fun stored(role: AgentRole): AgentPromptConfig?

    suspend fun save(config: AgentPromptConfig)

    suspend fun delete(role: AgentRole)
}

class DefaultAgentPromptRepository(
    private val store: AgentPromptStore,
) : AgentPromptRepository {

    override suspend fun stored(): List<AgentPromptConfig> = store.loadAll()

    override suspend fun stored(role: AgentRole): AgentPromptConfig? =
        store.loadAll().firstOrNull { it.role == role }

    override suspend fun save(config: AgentPromptConfig) = store.upsert(config)

    override suspend fun delete(role: AgentRole) = store.delete(role)
}
