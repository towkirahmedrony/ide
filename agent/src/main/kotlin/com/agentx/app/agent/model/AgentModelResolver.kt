package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityErrors
import com.agentx.app.model.capability.ModelCapabilityProfile
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.capability.toCapabilityProfile
import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.ratelimit.RateLimitManager

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
    const val CEREBRAS = ModelProviderIds.CEREBRAS
    const val MISTRAL = ModelProviderIds.MISTRAL
    const val OPENROUTER = ModelProviderIds.OPENROUTER
    const val CLOUDFLARE = ModelProviderIds.CLOUDFLARE
    const val NVIDIA_NIM = ModelProviderIds.NVIDIA_NIM

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
    const val GEMINI = "gemini-3.5-flash"
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
    /**
     * Authoritative capability lookup used by [validate] and [resolveChecked].
     * [resolve] itself stays a pure config selection and does not consult this.
     */
    private val capabilityRegistry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry.DEFAULT,
    /**
     * Admission control consulted by [resolveForRole], never by the pure
     * [resolve]. A null manager means rate limiting is not configured for this
     * host, so capabilities alone decide eligibility, exactly as before this
     * phase. Quota is only evaluated here, never reserved.
     */
    private val rateLimitManager: RateLimitManager? = null,
) {

    /**
     * The single shared capability + rate-limit check. Every role resolves
     * through it, so no individual agent reimplements eligibility.
     */
    private val eligibilityChecker: ModelEligibilityChecker =
        ModelEligibilityChecker(capabilityRegistry, rateLimitManager)

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
    fun resolve(role: AgentRole, preferredModel: String?, default: ModelConfig): ModelConfig =
        select(role, preferredModel, default).config

    /**
     * Internal selection shared by [resolve] and [resolveForRole]. It records
     * whether the role's own mapping produced the config, so an explicit role
     * assignment can be reported as such.
     */
    private fun select(role: AgentRole, preferredModel: String?, default: ModelConfig): Selection {
        val preference = currentPreferences()[role] ?: return Selection(default, fromRoleMapping = false)
        val providerId = preference.providerId
        // The role's own mapping (the user's Settings choice, or the built-in
        // default) wins over the agent definition's static model; the definition
        // is only consulted when the role names a provider but no model. That is
        // what makes a model picked in Settings take effect at run time.
        val model = preference.model?.takeIf { it.isNotBlank() }
            ?: preferredModel?.takeIf { it.isNotBlank() }

        if (providerId == default.providerId) return Selection(withModel(default, model), fromRoleMapping = true)
        val connection = connections()[providerId] ?: return Selection(default, fromRoleMapping = false)
        return Selection(withModel(connection, model), fromRoleMapping = true)
    }

    private data class Selection(val config: ModelConfig, val fromRoleMapping: Boolean)

    /** The configured preference for [role], when any. */
    fun preference(role: AgentRole): RoleModelPreference? = currentPreferences()[role]

    /**
     * Resolves an explicit [preference] into a [ModelConfig] using the same
     * rules [select] applies to a role's configured preference: the default
     * model when the provider matches, otherwise the supplied connection, with
     * the preference's model overriding when one is named.
     *
     * Returns null when the preference's provider is not connected, so a caller
     * (the fallback layer) can skip a candidate instead of inventing an
     * endpoint. It performs no network request and touches no credential beyond
     * what the supplied connection already holds.
     */
    fun configFor(preference: RoleModelPreference, default: ModelConfig): ModelConfig? {
        val model = preference.model?.takeIf { it.isNotBlank() }
        if (preference.providerId == default.providerId) return withModel(default, model)
        val connection = connections()[preference.providerId] ?: return null
        return withModel(connection, model)
    }

    /**
     * Evaluates an explicit [config] for [role] with the shared capability and
     * rate-limit check. Exposed so the fallback layer judges candidates exactly
     * as [resolveForRole] judges the role's configured model, without
     * reimplementing capability or quota logic.
     */
    suspend fun eligibilityFor(
        role: AgentRole,
        config: ModelConfig,
        requirements: ModelRequestRequirements = ModelRequestRequirements.DEFAULT,
    ): ModelEligibility = eligibilityChecker.check(role, config, requirements)

    /**
     * Same selection as [resolve], then checks that the chosen model meets
     * [role]'s required capabilities. Never switches provider or model.
     */
    fun resolveChecked(role: AgentRole, default: ModelConfig): ModelConfig {
        val config = resolve(role, default)
        validate(role, config).getOrThrow()
        return config
    }

    fun resolveChecked(definition: AgentDefinition, default: ModelConfig): ModelConfig {
        val config = resolve(definition, default)
        validate(definition.role, config).getOrThrow()
        return config
    }

    /**
     * Resolves [role]'s configuration and evaluates whether it may run right now.
     *
     * Unlike [resolve], this consults the authoritative capability registry and,
     * when one is configured, the [RateLimitManager]. It never reserves quota
     * (admission only) and never substitutes another model: the returned
     * [ModelResolutionResult] carries the selected config, the eligibility state
     * and a structured reason when it cannot run (see [ModelEligibilityState]).
     */
    suspend fun resolveForRole(
        role: AgentRole,
        default: ModelConfig,
        requirements: ModelRequestRequirements = ModelRequestRequirements.DEFAULT,
    ): ModelResolutionResult =
        resolveForRole(role = role, preferredModel = null, default = default, requirements = requirements)

    suspend fun resolveForRole(
        role: AgentRole,
        preferredModel: String?,
        default: ModelConfig,
        requirements: ModelRequestRequirements = ModelRequestRequirements.DEFAULT,
    ): ModelResolutionResult {
        val selection = select(role, preferredModel, default)
        val eligibility = eligibilityChecker.check(role, selection.config, requirements)
        return ModelResolutionResult(
            role = role,
            config = selection.config,
            eligibility = eligibility,
            explicit = selection.fromRoleMapping,
        )
    }

    suspend fun resolveForRole(
        definition: AgentDefinition,
        default: ModelConfig,
        requirements: ModelRequestRequirements = ModelRequestRequirements.DEFAULT,
    ): ModelResolutionResult =
        resolveForRole(definition.role, definition.modelPreference, default, requirements)

    /**
     * Whether [config] can satisfy [role]. A missing capability is a structured
     * [AgentError] with [AgentErrorCode.MODEL_CAPABILITY_UNSUPPORTED]; this
     * phase never falls back to another model.
     */
    fun validate(role: AgentRole, config: ModelConfig): Result<Unit> {
        val required = AgentRoleRequirements.required(role)
        val profile = profileFor(config)
        val missing = required.filterNot { capability -> profile.supports(capability) }
        if (missing.isEmpty()) return Result.success(Unit)
        val first = missing.first()
        return Result.failure(
            AgentModelResolutionException(
                error = capabilityError(role, config, first, profile),
            ),
        )
    }

    fun canSatisfy(role: AgentRole, config: ModelConfig): Boolean = validate(role, config).isSuccess

    private fun profileFor(config: ModelConfig): ModelCapabilityProfile {
        config.capabilities?.let { override ->
            return override.toCapabilityProfile(config.providerId, config.model)
        }
        return capabilityRegistry.profile(config.providerId, config.model)
    }

    private fun capabilityError(
        role: AgentRole,
        config: ModelConfig,
        capability: ModelCapability,
        profile: ModelCapabilityProfile,
    ): AgentError = AgentError(
        code = AgentErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
        message = "${ModelCapabilityErrors.CODE} provider=${config.providerId} " +
            "model=${config.model} capability=${capability.id}",
        role = role,
        details = mapOf(
            "provider" to config.providerId,
            "model" to config.model,
            "capability" to capability.id,
            "known" to profile.known.toString(),
            "support" to profile.support(capability).name,
            "local" to profile.local.toString(),
        ),
    )

    private fun withModel(config: ModelConfig, model: String?): ModelConfig =
        if (model == null || model == config.model) config else config.copy(model = model)
}

/** Thrown by [AgentModelResolver.resolveChecked] when a role's model cannot satisfy the role. */
class AgentModelResolutionException(
    val error: AgentError,
) : RuntimeException(error.message)
