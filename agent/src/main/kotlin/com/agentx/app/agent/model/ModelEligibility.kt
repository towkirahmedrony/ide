package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityProfile
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.capability.isLocalRuntime
import com.agentx.app.model.capability.toCapabilityProfile
import com.agentx.app.model.ratelimit.RateLimitDecision
import com.agentx.app.model.ratelimit.RateLimitKind
import com.agentx.app.model.ratelimit.RateLimitManager

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
        first?.let { details["capability"] = it.id }
        retryAfterMs?.let { details["retryAfterMs"] = it.toString() }
        rateLimitKind?.let { details["rateLimitKind"] = it.name }
        details["local"] = profile.local.toString()
        val message = buildString {
            append("MODEL_NOT_ELIGIBLE role=${role.name} provider=$providerId model=$modelId")
            append(" reason=${state.name}")
            first?.let { append(" capability=${it.id}") }
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
) {

    /** The capability profile in effect for [config], honoring an explicit override. */
    fun profile(config: ModelConfig): ModelCapabilityProfile {
        config.capabilities?.let { override ->
            return override.toCapabilityProfile(config.providerId, config.model)
        }
        return capabilityRegistry.profile(config.providerId, config.model)
    }

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

        // No manager registered means rate limiting is not in play for this host
        // (tests, headless previews); capabilities decide eligibility.
        val manager = rateLimitManager ?: return ModelEligibility.available(role, config, profile)

        val decision = manager.canRequest(
            providerId = config.providerId,
            modelId = config.model,
            estimatedInputTokens = requirements.estimatedInputTokens,
            estimatedOutputTokens = requirements.estimatedOutputTokens,
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
