package com.agentx.app.agent.prompt

import com.agentx.app.agent.domain.AgentRole

/**
 * The single entry point for resolving and editing agent system prompts.
 *
 * Deterministic resolution order (no merging):
 * 1. a user-customized prompt that is enabled and non-blank;
 * 2. otherwise the built-in default for the role.
 *
 * The result is never blank: a broken or empty configuration cannot silently
 * disable an agent.
 */
class PromptManager(
    private val repository: AgentPromptRepository = DefaultAgentPromptRepository(InMemoryAgentPromptStore()),
    private val defaults: (AgentRole) -> String = DefaultAgentPrompts::forRole,
    private val resolver: PromptTemplate = PromptTemplate,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Effective prompt for [role], with template variables substituted. */
    suspend fun resolve(
        role: AgentRole,
        variables: PromptVariables = PromptVariables.EMPTY,
    ): ResolvedPrompt {
        val stored = custom(role)
        val template = stored?.prompt ?: defaults(role)
        val source = if (stored != null) AgentPromptSource.CUSTOM else AgentPromptSource.DEFAULT
        val resolution = resolver.resolve(template, variables)
        // A custom prompt that resolves to nothing (e.g. only unknown variables)
        // must never disable the agent: fall back to the shipped default.
        val text = resolution.text.ifBlank { defaults(role) }
        return ResolvedPrompt(
            role = role,
            text = text,
            source = if (text == resolution.text) source else AgentPromptSource.DEFAULT,
            unknownVariables = resolution.unknownVariables,
            rejectedVariables = resolution.rejectedVariables,
        )
    }

    /**
     * The configuration shown in Settings. A stored override is returned even
     * when disabled, so the user can see and re-enable it; resolution still
     * falls back to the default while it is disabled or blank.
     */
    suspend fun config(role: AgentRole): AgentPromptConfig =
        repository.stored(role)?.copy(isCustom = true) ?: defaultConfig(role)

    /** Effective configuration for every role, in enum order. */
    suspend fun configs(): List<AgentPromptConfig> = AgentRole.entries.map { config(it) }

    /** The shipped default text for [role]. */
    fun defaultText(role: AgentRole): String = defaults(role)

    /** Whether [role] has a stored user override (enabled or not). */
    suspend fun isCustom(role: AgentRole): Boolean = repository.stored(role) != null

    /** Saves [prompt] as the user's override for [role]. */
    suspend fun save(
        role: AgentRole,
        prompt: String,
        enabled: Boolean = true,
        description: String? = null,
        version: String? = null,
    ): AgentPromptConfig {
        val config = AgentPromptConfig(
            role = role,
            prompt = prompt,
            enabled = enabled,
            description = description ?: DefaultAgentPrompts.describe(role),
            version = version,
            updatedAtMillis = clock(),
            isCustom = true,
        )
        repository.save(config)
        return config
    }

    /** Removes the override, restoring the built-in default. */
    suspend fun reset(role: AgentRole) {
        repository.delete(role)
    }

    /** Removes every override. */
    suspend fun resetAll() {
        AgentRole.entries.forEach { repository.delete(it) }
    }

    private suspend fun custom(role: AgentRole): AgentPromptConfig? {
        val stored = repository.stored(role) ?: return null
        if (!stored.enabled || stored.prompt.isBlank()) return null
        return stored
    }

    private fun defaultConfig(role: AgentRole): AgentPromptConfig = AgentPromptConfig(
        role = role,
        prompt = defaults(role),
        enabled = true,
        description = DefaultAgentPrompts.describe(role),
        updatedAtMillis = 0L,
        isCustom = false,
    )
}
