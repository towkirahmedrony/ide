package com.agentx.app.agent.model

import com.agentx.app.model.health.CandidateHealthScope
import com.agentx.app.model.health.CandidateHealthState
import com.agentx.app.model.health.CandidateHealthTracker

import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityProfile
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.capability.capabilityProfile
import com.agentx.app.model.capability.isLocalRuntime
import com.agentx.app.model.ratelimit.RateLimitDecision
import com.agentx.app.model.ratelimit.RateLimitKind
import com.agentx.app.model.ratelimit.RateLimitManager
import com.agentx.app.model.ratelimit.quotaScope

/**
 * Typed eligibility of one role + model + rate-limit snapshot.
 *
 * Only [AVAILABLE] may be executed. Every other state is a structured rejection:
 * this phase never substitutes a different model for an ineligible one.
 */
enum class ModelEligibilityState {
    /** Capabilities are satisfied and no known quota blocks a request. */
    AVAILABLE,

    /** The provider's rate-limit state currently forbids a request. */
    RATE_LIMITED,

    /**
     * The candidate recently failed and is inside its cooldown, or its provider
     * has a problem that reaches beyond one model (rejected credentials, an
     * invalid configuration). Distinct from [RATE_LIMITED]: this is observed
     * runtime health, not a quota declaration.
     */
    PROVIDER_UNHEALTHY,

    /** A required capability is authoritatively unsupported by this model. */
    CAPABILITY_UNSUPPORTED,

    /** The model definition exists but is disabled. */
    DISABLED,

    /** Capability metadata is absent, so support cannot be confirmed. */
    UNKNOWN,
}

/**
 * What a caller additionally needs from a model beyond [AgentRoleRequirements].
 *
 * The role's own requirements are always applied; [additionalCapabilities] only
 * *adds* to them (for example a role request that needs vision), so a caller can
 * never weaken the role contract.
 */
data class ModelRequestRequirements(
    val additionalCapabilities: Set<ModelCapability> = emptySet(),
    val estimatedInputTokens: Int = 0,
    val estimatedOutputTokens: Int = 0,
) {
    /** The union of the role's requirements and this request's extra ones. */
    fun requiredFor(role: AgentRole): Set<ModelCapability> =
        AgentRoleRequirements.required(role) + additionalCapabilities

    companion object {
        val DEFAULT: ModelRequestRequirements = ModelRequestRequirements()
    }
}

/**
 * The outcome of evaluating one model against a role, its capabilities and the
 * current rate-limit state. [reason] is human-readable; [missingCapabilities],
 * [retryAfterMs] and [rateLimitKind] carry the structured detail.
 */
data class ModelEligibility(
    val role: AgentRole,
    val providerId: String,
    val modelId: String,
    val state: ModelEligibilityState,
    val profile: ModelCapabilityProfile,
    val missingCapabilities: List<ModelCapability> = emptyList(),
    val retryAfterMs: Long? = null,
    val rateLimitKind: RateLimitKind? = null,
    /** Observed health of the candidate when it was rejected for that reason. */
    val healthState: CandidateHealthState? = null,
    val healthScope: CandidateHealthScope? = null,
    val reason: String? = null,
) {
    val eligible: Boolean get() = state == ModelEligibilityState.AVAILABLE

    val local: Boolean get() = profile.local

    /**
     * A structured [AgentError] for an ineligible model. Only valid when
     * [eligible] is false; a successful eligibility has no error to report.
     */
    fun rejectionError(): AgentError {
        require(!eligible) { "an eligible model has no rejection error" }
        val first = missingCapabilities.firstOrNull()
        val details = LinkedHashMap<String, String>()
        details["role"] = role.name
        details["provider"] = providerId
        details["model"] = modelId
        details["reason"] = state.name
        // Fine-grained, machine-readable cause so a caller can distinguish an
        // unknown capability from a definite rejection, an observed-connection
        // problem and a rate limit without parsing the human message. [code]
        // stays MODEL_NOT_ELIGIBLE for every capability/health rejection.
        details["cause"] = when (state) {
            ModelEligibilityState.UNKNOWN -> "MODEL_CAPABILITY_UNKNOWN"
            ModelEligibilityState.PROVIDER_UNHEALTHY -> "CONNECTION_DEGRADED"
            ModelEligibilityState.RATE_LIMITED -> "MODEL_RATE_LIMITED"
            ModelEligibilityState.DISABLED,
            ModelEligibilityState.CAPABILITY_UNSUPPORTED,
            -> "MODEL_NOT_ELIGIBLE"
            ModelEligibilityState.AVAILABLE -> "AVAILABLE"
        }
        first?.let { details["capability"] = it.id }
        // The runtime values behind the verdict, so a rejection can be traced
        // without guessing. Nothing here is a secret: it is the resolved support
        // state and where that resolution came from.
        first?.let { details["support"] = profile.support(it).name }
        details["provenance"] = profile.provenance.name
        details["known"] = profile.known.toString()
        retryAfterMs?.let { details["retryAfterMs"] = it.toString() }
        rateLimitKind?.let { details["rateLimitKind"] = it.name }
        healthState?.let { details["healthState"] = it.name }
        healthScope?.let { details["healthScope"] = it.name }
        details["local"] = profile.local.toString()
        val message = buildString {
            append("MODEL_NOT_ELIGIBLE role=${role.name} provider=$providerId model=$modelId")
            append(" reason=${state.name}")
            first?.let {
                append(" capability=${it.id}")
                append(" support=${profile.support(it).name}")
            }
            append(" provenance=${profile.provenance.name}")
            retryAfterMs?.let { append(" retryAfterMs=$it") }
        }
        return AgentError(
            code = AgentErrorCode.MODEL_NOT_ELIGIBLE,
            message = message,
            role = role,
            details = details,
        )
    }

    companion object {
        fun available(
            role: AgentRole,
            config: ModelConfig,
            profile: ModelCapabilityProfile,
        ): ModelEligibility = ModelEligibility(
            role = role,
            providerId = config.providerId,
            modelId = config.model,
            state = ModelEligibilityState.AVAILABLE,
            profile = profile,
        )
    }
}

/**
 * The single place that decides whether a model may perform a role.
 *
 * It combines the role's required capabilities ([AgentRoleRequirements]) with
 * the authoritative [ModelCapabilityRegistry] and the current
 * [RateLimitManager] admission state. No individual agent performs these checks,
 * so a model that cannot call tools, cannot stream, is disabled, or is currently
 * rate limited is rejected consistently for every role.
 *
 * Admission is only *evaluated* here ([RateLimitManager.canRequest]); quota is
 * never reserved. The actual reservation stays at the gateway execution
 * boundary, exactly where it already lives.
 */
class ModelEligibilityChecker(
    private val capabilityRegistry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry.DEFAULT,
    private val rateLimitManager: RateLimitManager? = null,
    /**
     * Observed runtime health. Optional: a host that records none behaves exactly
     * as before, because health only ever *subtracts* eligibility.
     */
    private val healthTracker: CandidateHealthTracker? = null,
) {

    /**
     * The capability profile in effect for [config].
     *
     * Resolved through the one shared precedence rule, so a capability declared
     * for this configuration is seen here without weakening what an undeclared
     * model resolves to: nothing stated and nothing registered stays
     * [CapabilitySupport.UNKNOWN], which is not support.
     */
    fun profile(config: ModelConfig): ModelCapabilityProfile =
        config.capabilityProfile(capabilityRegistry)

    /** True when [config] runs locally, so it shares no remote rate-limit bucket. */
    fun isLocal(config: ModelConfig): Boolean = config.isLocalRuntime(capabilityRegistry)

    /**
     * Evaluates [config] for [role]. Order matters: a disabled model is disabled
     * regardless of quota, and an unsupported capability is a hard rejection even
     * when quota is available.
     */
    suspend fun check(
        role: AgentRole,
        config: ModelConfig,
        requirements: ModelRequestRequirements = ModelRequestRequirements.DEFAULT,
    ): ModelEligibility {
        val profile = profile(config)

        if (!profile.enabled) {
            return ModelEligibility(
                role = role,
                providerId = config.providerId,
                modelId = config.model,
                state = ModelEligibilityState.DISABLED,
                profile = profile,
                reason = "model is disabled",
            )
        }

        val required = requirements.requiredFor(role)
        val missing = required.filterNot { profile.supports(it) }
        if (missing.isNotEmpty()) {
            // A capability the registry explicitly marks UNSUPPORTED is a definite
            // rejection. A capability the registry does not know about (or a model
            // with no authoritative metadata) is UNKNOWN: support cannot be
            // confirmed, so a tool-enabled role must not assume it.
            val unsupported = missing.filter { profile.support(it) == CapabilitySupport.UNSUPPORTED }
            val state = if (unsupported.isNotEmpty()) {
                ModelEligibilityState.CAPABILITY_UNSUPPORTED
            } else {
                ModelEligibilityState.UNKNOWN
            }
            return ModelEligibility(
                role = role,
                providerId = config.providerId,
                modelId = config.model,
                state = state,
                profile = profile,
                missingCapabilities = missing,
                reason = if (state == ModelEligibilityState.CAPABILITY_UNSUPPORTED) {
                    "required capability is unsupported"
                } else {
                    "required capability support is unknown"
                },
            )
        }

        // Observed health. A candidate that just failed is given its cooldown instead
        // of being hammered again, and a provider-wide problem (rejected credentials,
        // an invalid configuration) is respected for every model of that provider.
        // Checked before quota because a cooled-down candidate must not be asked at
        // all, and after capability because an impossible model is a hard rejection
        // however healthy its provider looks.
        healthTracker?.let { tracker ->
            if (!tracker.isUsable(config.providerId, config.model)) {
                val health = tracker.state(config.providerId, config.model)
                return ModelEligibility(
                    role = role,
                    providerId = config.providerId,
                    modelId = config.model,
                    state = ModelEligibilityState.PROVIDER_UNHEALTHY,
                    profile = profile,
                    retryAfterMs = tracker.cooldownRemainingMillis(config.providerId, config.model),
                    healthState = health.state,
                    healthScope = health.scope,
                    reason = "candidate health is ${health.state.name} (${health.reason ?: "no detail"})",
                )
            }
        }

        // No manager registered means rate limiting is not in play for this host
        // (tests, headless previews); capabilities decide health.
        val manager = rateLimitManager ?: return ModelEligibility.available(role, config, profile)

        val decision = manager.canRequest(
            providerId = config.providerId,
            modelId = config.model,
            estimatedInputTokens = requirements.estimatedInputTokens,
            estimatedOutputTokens = requirements.estimatedOutputTokens,
            // The same scope the gateway will reserve against ([ModelConfig.quotaScope]).
            // Asking a different one used to make a connection-scoped quota invisible
            // here, so a candidate with no headroom was reported as available and only
            // discovered otherwise when the request was already being sent.
            accountId = config.quotaScope(),
            local = isLocal(config),
        )
        return when (decision) {
            is RateLimitDecision.Blocked -> ModelEligibility(
                role = role,
                providerId = config.providerId,
                modelId = config.model,
                state = ModelEligibilityState.RATE_LIMITED,
                profile = profile,
                retryAfterMs = decision.retryAfterMs,
                rateLimitKind = decision.kind,
                reason = decision.reason,
            )
            RateLimitDecision.Allowed -> ModelEligibility.available(role, config, profile)
        }
    }
}

/**
 * A resolved model plus the eligibility that decided it.
 *
 * [explicit] records that the configuration came from the role's own mapping
 * (the user's Settings choice or the built-in default) rather than the active
 * model fallback, so an explicit assignment is reported rather than replaced.
 */
data class ModelResolutionResult(
    val role: AgentRole,
    val config: ModelConfig,
    val eligibility: ModelEligibility,
    val explicit: Boolean,
) {
    val eligible: Boolean get() = eligibility.eligible

    val rejectionReason: ModelEligibilityState? get() = eligibility.state.takeUnless { eligible }

    /** The selected config when eligible, otherwise null. Never a substitute model. */
    fun configOrNull(): ModelConfig? = if (eligible) config else null

    /** The structured rejection when ineligible, otherwise null. */
    fun errorOrNull(): AgentError? = if (eligible) null else eligibility.rejectionError()

    /** The selected config when eligible, otherwise throws with the reason. */
    fun eligibleConfigOrThrow(): ModelConfig {
        if (eligible) return config
        throw AgentModelResolutionException(eligibility.rejectionError())
    }
}
