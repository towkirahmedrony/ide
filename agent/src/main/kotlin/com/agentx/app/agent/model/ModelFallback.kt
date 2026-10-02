package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentEventSink
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.ModelFallbackReason
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import kotlin.coroutines.cancellation.CancellationException

/**
 * The single, explicit fallback configuration. It is the *only* place that
 * decides whether automatic fallback runs and which models a role may fall back
 * to; no provider preference is hardcoded anywhere.
 *
 * [automaticFallback] is **off by default** and is deliberately not derived from
 * any other setting, so an app that never configures a chain behaves exactly as
 * it did before this phase. A chain must be declared per role in
 * [fallbacksByRole]; configured providers are never added automatically.
 */
data class ModelFallbackPolicy(
    /** Whether a temporary primary failure may try a configured candidate. */
    val automaticFallback: Boolean = DEFAULT_AUTOMATIC_FALLBACK,
    /** Maximum fallback attempts for one model request; the primary is not counted. */
    val maxFallbackAttempts: Int = DEFAULT_MAX_FALLBACK_ATTEMPTS,
    /** Ordered candidate chain per role. An absent/empty chain means "no fallback". */
    val fallbacksByRole: Map<AgentRole, List<RoleModelPreference>> = emptyMap(),
) {
    init {
        require(maxFallbackAttempts >= 0) { "maxFallbackAttempts must not be negative" }
    }

    fun candidates(role: AgentRole): List<RoleModelPreference> = fallbacksByRole[role].orEmpty()

    /** True only when fallback is enabled and [role] actually has a chain. */
    fun enabledFor(role: AgentRole): Boolean = automaticFallback && candidates(role).isNotEmpty()

    /** Returns a policy with [role]'s chain set to [chain]. */
    fun withFallbacks(role: AgentRole, chain: List<RoleModelPreference>): ModelFallbackPolicy =
        copy(fallbacksByRole = fallbacksByRole + (role to chain))

    companion object {
        /** Fallback is opt-in; this default must not change silently. */
        const val DEFAULT_AUTOMATIC_FALLBACK: Boolean = false

        /** A small, safe bound on how many candidates one request may try. */
        const val DEFAULT_MAX_FALLBACK_ATTEMPTS: Int = 2

        /** Automatic fallback disabled and no configured chains. */
        val DISABLED: ModelFallbackPolicy = ModelFallbackPolicy()
    }
}

/**
 * Classifies a typed provider failure into a temporary [ModelFallbackReason], or
 * null when the failure is permanent and must be returned unchanged.
 *
 * Only the existing [ModelProviderErrorCode] categories are interpreted; an
 * arbitrary/unknown exception is never a fallback trigger. A [PROVIDER_ERROR]
 * with a 4xx status is a permanent request/configuration problem (for example an
 * invalid model), so only a 5xx or status-less provider failure is temporary.
 */
object ModelFallbackErrors {

    fun triggerFor(error: Throwable): ModelFallbackReason? =
        (error as? ModelProviderError)?.let(::triggerFor)

    fun triggerFor(error: ModelProviderError): ModelFallbackReason? = when (error.code) {
        ModelProviderErrorCode.RATE_LIMITED -> ModelFallbackReason.RATE_LIMITED
        ModelProviderErrorCode.TIMEOUT -> ModelFallbackReason.TIMEOUT
        ModelProviderErrorCode.NETWORK_ERROR,
        ModelProviderErrorCode.CONNECTION_FAILED,
        -> ModelFallbackReason.NETWORK_FAILURE
        ModelProviderErrorCode.PROVIDER_ERROR ->
            if (error.httpStatus == null || error.httpStatus >= 500) {
                ModelFallbackReason.PROVIDER_UNAVAILABLE
            } else {
                null
            }
        // INVALID_CONFIG, INVALID_REQUEST, AUTHENTICATION_FAILED, UNSUPPORTED,
        // INVALID_RESPONSE, PROVIDER_NOT_FOUND, DUPLICATE_PROVIDER, CANCELLED,
        // UNKNOWN: all permanent / not a temporary execution failure.
        else -> null
    }
}

/**
 * Controlled, capability- and quota-aware model fallback.
 *
 * It wraps one logical model request ([call]) and, only when the active
 * [ModelFallbackPolicy] enables it and the primary failure is temporary, tries
 * the role's explicitly configured candidates in order.
 *
 * Every candidate is judged by the same [AgentModelResolver] eligibility system
 * the primary model uses — capabilities, enabled state and rate-limit admission —
 * so no capability or quota logic is duplicated here. A candidate that cannot
 * call tools is never selected, and the same conversation, tools and role flow
 * through unchanged: only the [ModelConfig] differs between attempts.
 *
 * Attempts are bounded by [ModelFallbackPolicy.maxFallbackAttempts], a
 * provider/model pair is never tried twice, and streaming safety is delegated to
 * the caller via [outputProduced]: once a response has produced meaningful model
 * output, the original failure is returned instead of starting a second model.
 */
class ModelFallback(
    private val policy: () -> ModelFallbackPolicy = { ModelFallbackPolicy.DISABLED },
    private val resolver: AgentModelResolver = AgentModelResolver(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logger: ForgeLogger = ForgeLoggers.create(
        LogLevel.INFO,
        baseFields = mapOf("layer" to "agent", "component" to "modelFallback"),
    ),
) {

    /**
     * Runs [call] against [primary]. When it fails with a temporary error and a
     * candidate succeeds, the candidate's value is returned; otherwise the
     * original (or last) failure is thrown and no model was silently changed.
     *
     * @param outputProduced reports whether the current attempt has already
     *   emitted meaningful model output; when it starts returning true, fallback
     *   stops so a partially streamed response is never duplicated.
     */
    suspend fun <T> execute(
        role: AgentRole,
        sessionId: String,
        primary: ModelConfig,
        default: ModelConfig = primary,
        sink: AgentEventSink,
        requirements: ModelRequestRequirements = ModelRequestRequirements.DEFAULT,
        outputProduced: () -> Boolean = { false },
        call: suspend (ModelConfig) -> T,
    ): T {
        val active = policy()
        if (!active.enabledFor(role)) return call(primary)
        return try {
            call(primary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val reason = ModelFallbackErrors.triggerFor(error) ?: throw error
            runChain(role, sessionId, primary, default, sink, requirements, outputProduced, active, reason, error, call)
        }
    }

    private suspend fun <T> runChain(
        role: AgentRole,
        sessionId: String,
        primary: ModelConfig,
        default: ModelConfig,
        sink: AgentEventSink,
        requirements: ModelRequestRequirements,
        outputProduced: () -> Boolean,
        policy: ModelFallbackPolicy,
        primaryReason: ModelFallbackReason,
        primaryError: Throwable,
        call: suspend (ModelConfig) -> T,
    ): T {
        val visited = LinkedHashSet<String>()
        visited += key(primary)
        var attempts = 0
        var current = primary
        var lastError = primaryError
        var lastReason = primaryReason

        for (preference in policy.candidates(role)) {
            if (attempts >= policy.maxFallbackAttempts) break
            // Streaming safety: a response that already produced output cannot
            // be continued by a different model without duplicating it.
            if (outputProduced()) break

            val candidate = resolver.configFor(preference, default)
            if (candidate == null) {
                logSkip(role, preference, "provider-not-connected")
                continue
            }
            val candidateKey = key(candidate)
            if (candidateKey in visited) {
                logSkip(role, preference, "duplicate-candidate")
                continue
            }
            visited += candidateKey

            val eligibility = resolver.eligibilityFor(role, candidate, requirements)
            if (!eligibility.eligible) {
                logSkip(role, preference, "ineligible:${eligibility.state.name}")
                continue
            }

            attempts += 1
            emitStarted(sink, sessionId, role, current, candidate, lastReason, attempts)
            try {
                val result = call(candidate)
                sink.emit(
                    AgentEvent.ModelFallbackSucceeded(
                        sessionId = sessionId,
                        role = role,
                        fromProviderId = primary.providerId,
                        fromModelId = primary.model,
                        toProviderId = candidate.providerId,
                        toModelId = candidate.model,
                        attempts = attempts,
                        timestampMillis = clock(),
                    ),
                )
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                current = candidate
                ModelFallbackErrors.triggerFor(error)?.let { lastReason = it }
                // A candidate that failed after producing output cannot be
                // replaced safely either.
                if (outputProduced()) break
            }
        }

        if (attempts > 0 && !outputProduced()) {
            sink.emit(
                AgentEvent.ModelFallbackExhausted(
                    sessionId = sessionId,
                    role = role,
                    providerId = current.providerId,
                    modelId = current.model,
                    attempts = attempts,
                    reason = lastReason,
                    timestampMillis = clock(),
                ),
            )
            logger.info(
                "Model fallback exhausted",
                mapOf(
                    "sessionId" to sessionId,
                    "role" to role.name,
                    "attempts" to attempts,
                    "lastProvider" to current.providerId,
                    "lastModel" to current.model,
                    "reason" to lastReason.name,
                ),
            )
        }
        throw lastError
    }

    private fun emitStarted(
        sink: AgentEventSink,
        sessionId: String,
        role: AgentRole,
        from: ModelConfig,
        to: ModelConfig,
        reason: ModelFallbackReason,
        attempt: Int,
    ) {
        sink.emit(
            AgentEvent.ModelFallbackStarted(
                sessionId = sessionId,
                role = role,
                fromProviderId = from.providerId,
                fromModelId = from.model,
                toProviderId = to.providerId,
                toModelId = to.model,
                reason = reason,
                attempt = attempt,
                timestampMillis = clock(),
            ),
        )
        logger.info(
            "Model fallback started",
            mapOf(
                "sessionId" to sessionId,
                "role" to role.name,
                "fromProvider" to from.providerId,
                "fromModel" to from.model,
                "toProvider" to to.providerId,
                "toModel" to to.model,
                "reason" to reason.name,
                "attempt" to attempt,
            ),
        )
    }

    private fun logSkip(role: AgentRole, preference: RoleModelPreference, why: String) {
        logger.info(
            "Model fallback candidate skipped",
            mapOf(
                "role" to role.name,
                "provider" to preference.providerId,
                "model" to (preference.model ?: ""),
                "reason" to why,
            ),
        )
    }

    private fun key(config: ModelConfig): String = "${config.providerId}::${config.model}"
}
