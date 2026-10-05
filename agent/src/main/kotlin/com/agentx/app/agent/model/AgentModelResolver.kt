package com.agentx.app.agent.model

import com.agentx.app.model.health.CandidateHealthTracker

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
import com.agentx.app.model.capability.capabilityProfile
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
    /**
     * Optional saved connection (preset) identity this role was assigned from.
     *
     * When present it wins over [providerId], because several independent
     * connections can share one provider family (two custom OpenAI-compatible
     * endpoints, or a custom endpoint and an Ollama server). A preference that
     * names a connection is treated as an explicit assignment: if that exact
     * connection is not connected, resolution fails rather than degrading to the
     * provider-family rules, so a saved assignment is never silently answered by
     * a different connection of the same family.
     * Null means "resolve by provider family", which is what the built-in
     * defaults use and what a role configured before connections had identities
     * keeps doing.
     */
    val connectionId: String? = null,
    /**
     * Whether this preference is an explicit role assignment (the user's saved
     * Settings choice) rather than a policy default.
     *
     * An explicit assignment is authoritative: when the provider/connection it
     * names cannot be addressed, the resolver returns a structured failure and
     * never substitutes the active model. A policy default (the built-in mapping)
     * keeps the documented compatibility behaviour. A preference that names a
     * [connectionId] is authoritative regardless of this flag.
     */
    val explicit: Boolean = false,
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
 * 2. The preferred connection is named by [RoleModelPreference.connectionId] →
 *    that exact connection when connected. When it is not connected resolution
 *    fails with [AgentErrorCode.MODEL_NOT_CONNECTED]; a named assignment is never
 *    answered by another connection.
 * 3. The preferred provider is [default]'s provider → [default] with the role's
 *    model when one is known.
 * 4. A supplied [connections] entry of the preferred provider family → that
 *    configuration, with the role's model when one is known.
 * 5. The preferred provider/connection is not connected:
 *    - an explicit assignment ([RoleModelPreference.explicit], or a named
 *      connection) → a structured failure, never a substitute model;
 *    - a policy default → the [default] configuration, so an unconnected built-in
 *      preference never breaks a run that works today.
 *
 * The distinction in step 5 is what makes an explicit role → model assignment
 * authoritative while keeping the built-in mapping's compatibility behaviour.
 */
class AgentModelResolver(
    private val preferences: AgentModelPreferences = AgentModelPreferences.EMPTY,
    /**
     * The configurations the caller already has, keyed by connection identity
     * ([ModelConfig.connectionId]). A preference that names a connection is
     * resolved by that key; one that names only a provider family is resolved
     * against the configurations' [ModelConfig.providerId]. Empty means "only the
     * active model".
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
    /**
     * Observed provider/model health, consulted by [resolveForRole] alongside
     * capabilities and quota so a candidate inside its cooldown is never chosen.
     * A null tracker means health is not recorded for this host.
     */
    private val healthTracker: CandidateHealthTracker? = null,
    /**
     * Whether an intentional fallback chain is configured for [AgentRole].
     *
     * Purely informational for the structured resolution failure: it lets the
     * error report that a substitution is possible only through the explicitly
     * configured fallback policy, never through hidden resolution. The resolver
     * itself never falls back; it never consults or triggers the fallback layer.
     */
    private val intentionalFallback: (AgentRole) -> Boolean = { false },
) {

    /**
     * The single shared capability + rate-limit check. Every role resolves
     * through it, so no individual agent reimplements eligibility.
     */
    private val eligibilityChecker: ModelEligibilityChecker =
        ModelEligibilityChecker(capabilityRegistry, rateLimitManager, healthTracker)

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
     *
     * @throws AgentModelResolutionException when an explicit role assignment's
     *   connection/provider is not connected. The configured model is never
     *   silently replaced.
     */
    fun resolve(role: AgentRole, preferredModel: String?, default: ModelConfig): ModelConfig {
        val selection = select(role, preferredModel, default)
        selection.error?.let { throw AgentModelResolutionException(it) }
        return checkNotNull(selection.config) { "selection without config and without error" }
    }

    /**
     * Internal selection shared by [resolve] and [resolveForRole]. It records
     * whether the role's own mapping produced the config, so an explicit role
     * assignment can be reported as such, and carries a structured error when an
     * explicit assignment cannot be addressed.
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
        // An explicit assignment — the user's saved Settings choice, or any
        // preference that names a specific saved connection — is authoritative. It
        // is never silently answered by another connection or the active model.
        val authoritative = preference.explicit || !preference.connectionId.isNullOrBlank()

        // A specific saved connection is addressed by its own identity, so a role
        // assigned to one custom endpoint never resolves to another endpoint of the
        // same provider family. A named connection that is not connected right now
        // (disconnected or deleted, or a map keyed differently) is a hard failure:
        // the saved identity is preserved and reported instead of being replaced.
        val namedId = preference.connectionId?.takeIf { it.isNotBlank() }
        if (namedId != null) {
            val named = connections()[namedId]
                ?: return Selection(
                    config = null,
                    fromRoleMapping = true,
                    error = connectionFailure(role, preference, model, namedId),
                )
            return Selection(withModel(named, model), fromRoleMapping = true)
        }

        if (providerId == default.providerId) return Selection(withModel(default, model), fromRoleMapping = true)

        val connection = connectionForProvider(providerId)
        if (connection != null) return Selection(withModel(connection, model), fromRoleMapping = true)

        // The provider family is not connected. A policy default may still fall
        // back to the active model (documented compatibility); an explicit
        // assignment may not.
        if (authoritative) {
            return Selection(
                config = null,
                fromRoleMapping = true,
                error = connectionFailure(role, preference, model, providerId),
            )
        }
        return Selection(default, fromRoleMapping = false)
    }

    /**
     * The connection a preference with no explicit identity resolves to: any
     * connected configuration of that provider family. Scans the values because
     * the connection set is keyed by connection identity, not provider family.
     */
    private fun connectionForProvider(providerId: String): ModelConfig? =
        connections().values.firstOrNull { it.providerId == providerId }

    /**
     * The outcome of [select]: a resolved configuration, or a structured failure
     * when an explicit assignment cannot be addressed ([config] is null then).
     */
    private data class Selection(
        val config: ModelConfig?,
        val fromRoleMapping: Boolean,
        val error: AgentError? = null,
    )

    /**
     * The structured failure for an explicit assignment whose provider or saved
     * connection is not currently connected.
     *
     * The role, the requested provider/connection, the requested model and
     * whether an intentional fallback is configured are carried on
     * [AgentError.details], so the UI/runtime can explain the failure instead of
     * silently executing the role on a different model. It never contains a
     * credential or an endpoint.
     */
    private fun connectionFailure(
        role: AgentRole,
        preference: RoleModelPreference,
        model: String?,
        missingConnectionId: String,
    ): AgentError {
        val connection = preference.connectionId?.takeIf { it.isNotBlank() } ?: missingConnectionId
        val requestedModel = model ?: preference.model
        val fallbackConfigured = intentionalFallback(role)
        return AgentError(
            code = AgentErrorCode.MODEL_NOT_CONNECTED,
            message = "MODEL_NOT_CONNECTED role=${role.name} provider=${preference.providerId} " +
                "connection=$connection model=${requestedModel ?: "(provider default)"} " +
                "reason=CONNECTION_NOT_CONNECTED fallbackAvailable=$fallbackConfigured",
            role = role,
            details = buildMap {
                put("role", role.name)
                put("provider", preference.providerId)
                put("connection", connection)
                requestedModel?.let { put("model", it) }
                put("reason", "CONNECTION_NOT_CONNECTED")
                put("cause", "MODEL_NOT_CONNECTED")
                put("explicit", "true")
                put("fallbackAvailable", fallbackConfigured.toString())
            },
        )
    }

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
     * endpoint. A named connection that is not connected yields null rather than
     * degrading to a different connection of the same family, so a candidate's
     * identity is preserved. It performs no network request and touches no
     * credential beyond what the supplied connection already holds.
     */
    fun configFor(preference: RoleModelPreference, default: ModelConfig): ModelConfig? {
        val model = preference.model?.takeIf { it.isNotBlank() }
        // A named connection is addressed by its own identity: when it is not
        // connected the candidate is skipped, never swapped for a sibling
        // connection of the same provider family.
        val namedId = preference.connectionId?.takeIf { it.isNotBlank() }
        if (namedId != null) {
            val named = connections()[namedId] ?: return null
            return withModel(named, model)
        }
        if (preference.providerId == default.providerId) return withModel(default, model)
        val connection = connectionForProvider(preference.providerId) ?: return null
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

    /**
     * @throws AgentModelResolutionException when an explicit role assignment's
     *   connection/provider is not connected. The runtime receives the same
     *   structured failure [resolve] produces; it never receives a substitute
     *   model.
     */
    suspend fun resolveForRole(
        role: AgentRole,
        preferredModel: String?,
        default: ModelConfig,
        requirements: ModelRequestRequirements = ModelRequestRequirements.DEFAULT,
    ): ModelResolutionResult {
        val selection = select(role, preferredModel, default)
        selection.error?.let { throw AgentModelResolutionException(it) }
        val config = checkNotNull(selection.config) { "selection without config and without error" }
        val eligibility = eligibilityChecker.check(role, config, requirements)
        return ModelResolutionResult(
            role = role,
            config = config,
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

    /**
     * The capability profile in effect for [config], through the one shared
     * precedence rule — so a capability that travels on the configuration (a
     * saved per-model declaration) is honored here exactly as the eligibility
     * checker and the gateway honor it.
     */
    private fun profileFor(config: ModelConfig): ModelCapabilityProfile =
        config.capabilityProfile(capabilityRegistry)

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
            // Where the resolved value came from, so a rejection can be traced to a
            // built-in definition, a saved declaration or the absence of both.
            "provenance" to profile.provenance.name,
            "local" to profile.local.toString(),
        ),
    )

    /**
     * [config] pointed at [model].
     *
     * A change of model drops the configuration's capability declaration: a
     * statement made about one model must never be inherited by another of the
     * same provider, or a role that overrides the model would borrow a capability
     * that was never stated for it.
     */
    private fun withModel(config: ModelConfig, model: String?): ModelConfig =
        if (model == null || model == config.model) {
            config
        } else {
            config.copy(model = model, declaredCapabilities = null)
        }
}

/** Thrown by [AgentModelResolver.resolveChecked] when a role's model cannot satisfy the role. */
class AgentModelResolutionException(
    val error: AgentError,
) : RuntimeException(error.message)
