package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.preset.ModelProviderIds

/**
 * Logical provider identifiers the role → model mapping refers to.
 *
 * These are not registered providers: a role preference naming one of them
 * resolves only when a matching connection is supplied to [AgentModelResolver].
 * Nothing here creates a provider, holds a credential, or performs a network
 * request, so a role may name a provider that is not connected yet.
 */
object AgentModelProviders {
    const val GEMINI = ModelProviderIds.GEMINI
    const val GROQ = ModelProviderIds.GROQ
    const val OPENAI_COMPATIBLE = ModelProviderIds.OPENAI_COMPATIBLE

    /**
     * The local model runtime (for example Qwen 2.5 Coder). It shares the
     * [OPENAI_COMPATIBLE] identity: one provider family, distinguished per
     * connection by its endpoint and model. Kept as an alias so the Phase 1
     * role mapping and its tests keep compiling unchanged.
     */
    const val OPENAI_COMPATIBLE_LOCAL = ModelProviderIds.OPENAI_COMPATIBLE
}

/**
 * Model identifiers the target role mapping asks for.
 *
 * `GEMINI` is the Flash model the provider catalog offers as its preferred
 * model, so the default mapping names a model that actually exists in the
 * configured catalog rather than an arbitrary one.
 */
object AgentModelIds {
    const val GEMINI = "gemini-2.0-flash"
    const val GROQ = "llama-3.3-70b-versatile"
    const val QWEN_CODER = "qwen2.5-coder-14b"
}

/**
 * The provider (and, optionally, the model) one role should use when it is
 * available. Identifiers only: never an endpoint and never a credential.
 */
data class RoleModelPreference(
    val providerId: String,
    /** Optional model within [providerId]; a definition's own preference takes precedence. */
    val model: String? = null,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
    }
}

/**
 * The declarative role → model mapping.
 *
 * [DEFAULT] encodes the target architecture. It is a *preference*: a role whose
 * provider is not connected simply falls back to the active configuration, so
 * the mapping can describe Gemini/Groq/Qwen before any of them exist.
 */
data class AgentModelPreferences(
    val byRole: Map<AgentRole, RoleModelPreference> = emptyMap(),
) {
    operator fun get(role: AgentRole): RoleModelPreference? = byRole[role]

    fun with(role: AgentRole, preference: RoleModelPreference): AgentModelPreferences =
        copy(byRole = byRole + (role to preference))

    companion object {
        /** No role-specific configuration: every role uses the active model. */
        val EMPTY: AgentModelPreferences = AgentModelPreferences()

        /**
         * The desired mapping:
         *
         * ```
         * MAIN       → Gemini
         * EXPLORER   → Groq
         * RESEARCHER → Gemini
         * CODER      → Qwen 2.5 Coder 14B (openai-compatible-local)
         * DEBUGGER   → Qwen 2.5 Coder 14B (openai-compatible-local)
         * REVIEWER   → Groq
         * TESTER     → Groq
         * ```
         */
        val DEFAULT: AgentModelPreferences = AgentModelPreferences(
            mapOf(
                AgentRole.MAIN to RoleModelPreference(AgentModelProviders.GEMINI, AgentModelIds.GEMINI),
                AgentRole.EXPLORER to RoleModelPreference(AgentModelProviders.GROQ, AgentModelIds.GROQ),
                AgentRole.RESEARCHER to RoleModelPreference(AgentModelProviders.GEMINI, AgentModelIds.GEMINI),
                AgentRole.CODER to RoleModelPreference(
                    AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
                    AgentModelIds.QWEN_CODER,
                ),
                AgentRole.DEBUGGER to RoleModelPreference(
                    AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
                    AgentModelIds.QWEN_CODER,
                ),
                AgentRole.REVIEWER to RoleModelPreference(AgentModelProviders.GROQ, AgentModelIds.GROQ),
                AgentRole.TESTER to RoleModelPreference(AgentModelProviders.GROQ, AgentModelIds.GROQ),
            ),
        )
    }
}

/**
 * Resolves the [ModelConfig] one [AgentRole] should run with.
 *
 * Pure and deterministic: it performs no network request, creates no provider,
 * executes no tool, and never touches [com.agentx.app.model.ModelGateway]. It
 * only selects among configurations the caller already has, which keeps the
 * resolution layer independent of how connections are established.
 *
 * Resolution order:
 * 1. No role preference → the [default] configuration.
 * 2. The preferred provider is [default]'s provider → [default] with the role's
 *    model when one is known.
 * 3. The preferred provider has a supplied [connections] entry → that
 *    configuration, with the role's model when one is known.
 * 4. Otherwise → the [default] configuration, so an unconnected provider never
 *    breaks a run that works today.
 */
class AgentModelResolver(
    private val preferences: AgentModelPreferences = AgentModelPreferences.EMPTY,
    /**
     * The provider configurations the caller already has, keyed by
     * [RoleModelPreference.providerId]. Empty means "only the active model".
     */
    private val connections: () -> Map<String, ModelConfig> = { emptyMap() },
    /**
     * Optional live source consulted on every [resolve]. Settings writes through
     * [com.agentx.app.agent.model.AgentRoleModelRegistry] and the resolver picks
     * the new mapping up without being rebuilt. When null the fixed
     * [preferences] value is used.
     */
    private val livePreferences: (() -> AgentModelPreferences)? = null,
) {

    /** The mapping in effect for this resolution: the live source when present. */
    private fun currentPreferences(): AgentModelPreferences = livePreferences?.invoke() ?: preferences

    /** Resolves [role]'s configuration from the role mapping alone. */
    fun resolve(role: AgentRole, default: ModelConfig): ModelConfig =
        resolve(role, preferredModel = null, default = default)

    /** Resolves a definition's role, honoring [AgentDefinition.modelPreference]. */
    fun resolve(definition: AgentDefinition, default: ModelConfig): ModelConfig =
        resolve(definition.role, definition.modelPreference, default)

    /**
     * @param preferredModel the caller's model preference (for example an
     *   [AgentDefinition.modelPreference]); when blank, the role's configured
     *   model is used.
     */
    fun resolve(role: AgentRole, preferredModel: String?, default: ModelConfig): ModelConfig {
        val preference = currentPreferences()[role] ?: return default
        val providerId = preference.providerId
        // The role's own mapping (the user's Settings choice, or the built-in
        // default) wins over the agent definition's static model; the definition
        // is only consulted when the role names a provider but no model. That is
        // what makes a model picked in Settings take effect at run time.
        val model = preference.model?.takeIf { it.isNotBlank() }
            ?: preferredModel?.takeIf { it.isNotBlank() }

        if (providerId == default.providerId) return withModel(default, model)
        val connection = connections()[providerId] ?: return default
        return withModel(connection, model)
    }

    /** The configured preference for [role], when any. */
    fun preference(role: AgentRole): RoleModelPreference? = currentPreferences()[role]

    private fun withModel(config: ModelConfig, model: String?): ModelConfig =
        if (model == null || model == config.model) config else config.copy(model = model)
}
