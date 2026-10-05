package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentEventSink
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.FallbackFailureCategory
import com.agentx.app.agent.domain.ModelFallbackReason
import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.health.CandidateFailure
import com.agentx.app.model.health.CandidateHealthTracker
import com.agentx.app.model.retry.RetryVerdict
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

    /**
     * The named category of [error], whether or not it may trigger a fallback.
     *
     * Use this when the *reason* matters — for a log, an event, or a message to the
     * user. [triggerFor] answers the narrower question of whether a configured
     * candidate may be tried.
     */
    fun categoryFor(error: Throwable): FallbackFailureCategory = FallbackFailureCategory.of(error)

    fun triggerFor(error: Throwable): ModelFallbackReason? =
        (error as? ModelProviderError)?.let(::triggerFor)

    fun triggerFor(error: ModelProviderError): ModelFallbackReason? =
        categoryFor(error).toFallbackReason()
}

/**
 * The trigger a category produces, or null when it is not a fallback trigger.
 *
 * The category decides, and only [FallbackFailureCategory.fallbackEligible]
 * categories can produce a reason; an ineligible one returns null so the original
 * failure is returned to the caller unchanged. Deriving the trigger here rather than
 * listing provider codes a second time is what keeps "may this fall back?" and "what
 * actually happened?" from drifting apart.
 */
internal fun FallbackFailureCategory.toFallbackReason(): ModelFallbackReason? =
    if (!fallbackEligible) {
        null
    } else {
        when (this) {
            FallbackFailureCategory.RATE_LIMITED,
            FallbackFailureCategory.QUOTA_EXHAUSTED,
            -> ModelFallbackReason.RATE_LIMITED

            FallbackFailureCategory.TIMEOUT -> ModelFallbackReason.TIMEOUT

            FallbackFailureCategory.NETWORK_FAILURE,
            FallbackFailureCategory.CONNECTION_DEGRADED,
            -> ModelFallbackReason.NETWORK_FAILURE

            FallbackFailureCategory.TEMPORARY_PROVIDER_FAILURE,
            FallbackFailureCategory.PROVIDER_UNAVAILABLE,
            -> ModelFallbackReason.PROVIDER_UNAVAILABLE

            // Eligible categories added later must state their trigger explicitly;
            // falling through to a reason would invent a classification.
            else -> null
        }
    }

/**
 * Maps a temporary fallback reason onto the health kind recorded for a candidate.
 *
 * `else` keeps any future reason temporary, so adding a member to the reason enum
 * cannot accidentally make a failing candidate look healthy.
 */
internal fun ModelFallbackReason.toCandidateFailure(): CandidateFailure = when (this) {
    ModelFallbackReason.RATE_LIMITED -> CandidateFailure.RATE_LIMITED
    ModelFallbackReason.TIMEOUT -> CandidateFailure.TIMEOUT
    ModelFallbackReason.NETWORK_FAILURE -> CandidateFailure.CONNECTION
    ModelFallbackReason.PROVIDER_UNAVAILABLE -> CandidateFailure.PROVIDER_OUTAGE
    else -> CandidateFailure.TEMPORARY
}

/**
 * Structured metadata for one fallback decision.
 *
 * This is runtime bookkeeping, not reasoning: which candidate was asked, why the
 * previous one was passed over, what the capability/quota/health answers were, and
 * whether the switch happened before a request or after a failure. It is emitted
 * as structured log fields and is the shape a later phase can surface in the UI.
 * It never contains a credential.
 */
data class ModelFallbackDecision(
    val role: AgentRole,
    val sessionId: String,
    val originalProviderId: String,
    val originalModelId: String,
    /** Connection identity of the model being left, so same-family connections stay distinct. */
    val originalConnectionId: String? = null,
    /** Why the original candidate was passed over. */
    val ineligibleReason: String? = null,
    /** The candidate considered for this decision. */
    val consideredProviderId: String? = null,
    val consideredModelId: String? = null,
    val consideredConnectionId: String? = null,
    /** The capability/quota/health outcome for the considered candidate. */
    val eligibilityState: String? = null,
    val healthState: String? = null,
    val quotaState: String? = null,
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val selectedConnectionId: String? = null,
    /** True when a request was actually sent to the selected candidate. */
    val requestSent: Boolean = false,
    /** True when the switch happened before any request, false after a failure. */
    val beforeRequest: Boolean = true,
    val attempts: Int = 0,
    /** The reason that triggered the chain, when there was one. */
    val trigger: String? = null,
    /**
     * The named failure category behind the decision.
     *
     * Distinct from [trigger]: the category says what happened even when the answer
     * is "no fallback", which is what lets a reader tell "the key was rejected" from
     * "the provider was down" instead of seeing the same silence for both.
     */
    val failureCategory: String? = null,
) {
    /** Credential-free field map for structured logging. */
    fun fields(): Map<String, Any?> = mapOf(
        "role" to role.name,
        "sessionId" to sessionId,
        "originalProvider" to originalProviderId,
        "originalModel" to originalModelId,
        "originalConnection" to originalConnectionId,
        "ineligibleReason" to ineligibleReason,
        "consideredProvider" to consideredProviderId,
        "consideredModel" to consideredModelId,
        "consideredConnection" to consideredConnectionId,
        "eligibility" to eligibilityState,
        "health" to healthState,
        "quota" to quotaState,
        "selectedProvider" to selectedProviderId,
        "selectedModel" to selectedModelId,
        "selectedConnection" to selectedConnectionId,
        "requestSent" to requestSent,
        "beforeRequest" to beforeRequest,
        "attempts" to attempts,
        "trigger" to trigger,
        "failureCategory" to failureCategory,
    )

    /** Structural equality over the credential-free field map, for tests and logs. */
    fun describe(): String = fields().entries.joinToString(", ") { "${it.key}=${it.value}" }
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
    /**
     * Observed provider/model health. Every attempt is recorded through it, so a
     * candidate that just failed enters a cooldown and the next attempt — preemptive
     * or reactive — skips it instead of hammering it.
     */
    private val health: CandidateHealthTracker? = null,
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
        // Every attempt — primary, preemptive switch or reactive fallback — is observed
        // through one wrapper, so health reflects what actually happened rather than
        // what was planned, and a candidate that just failed cools down before the
        // next request can reach it.
        val tracked: suspend (ModelConfig) -> T = { target ->
            try {
                val value = call(target)
                health?.recordSuccess(target.providerId, target.model)
                value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ModelFallbackErrors.triggerFor(error)?.let { reason ->
                    health?.recordFailure(
                        providerId = target.providerId,
                        modelId = target.model,
                        failure = reason.toCandidateFailure(),
                        retryAfterMillis = (error as? ModelProviderError)?.retryAfterMillis,
                    )
                }
                throw error
            }
        }

        val active = policy()
        if (!active.enabledFor(role)) return tracked(primary)

        // Preemptive headroom protection, before anything is sent.
        //
        // A primary that AgentX already knows has no safe quota headroom is never
        // asked. Waiting for its 429 would mean knowingly spending the last usable
        // capacity on a request the runtime knew was over the line, and that
        // response would be the *mechanism* for discovering exhaustion rather than a
        // last-resort signal.
        when (val preflight = preflight(role, sessionId, primary, default, sink, requirements, active)) {
            Preflight.Primary -> Unit
            is Preflight.Switch -> return tracked(preflight.config)
            is Preflight.NoHeadroom -> throw preflight.error
        }

        return try {
            tracked(primary)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val reason = ModelFallbackErrors.triggerFor(error) ?: throw error
            runChain(role, sessionId, primary, default, sink, requirements, outputProduced, active, reason, error, tracked)
        }
    }

    /** What the preflight decided about the primary attempt. */
    private sealed interface Preflight {
        /** Headroom is fine (or the block is not a quota block): use the primary. */
        data object Primary : Preflight

        /** A configured candidate can take the request safely. */
        data class Switch(val config: ModelConfig) : Preflight

        /** Nothing can take it safely, so nothing is sent. */
        data class NoHeadroom(val error: Throwable) : Preflight
    }

    /**
     * Decides whether the primary may be used, using the same eligibility system
     * the rest of the runtime uses — capabilities, enabled state and quota
     * admission — so no quota rule is duplicated here.
     *
     * Only a quota block ([ModelEligibilityState.RATE_LIMITED]) triggers a switch.
     * A capability, disabled or unknown state is never papered over by a model that
     * merely happens to have quota available: the primary is attempted and reports
     * its own structured error, which is what keeps a request from being silently
     * answered by an incompatible model.
     *
     * Candidates come only from the explicitly configured chain. Attempts are
     * bounded by the policy, a provider/model pair is never revisited, and every
     * candidate must be eligible in its own right — so there is no circular
     * fallback and no candidate inside its own protected zone is chosen.
     */
    private suspend fun preflight(
        role: AgentRole,
        sessionId: String,
        primary: ModelConfig,
        default: ModelConfig,
        sink: AgentEventSink,
        requirements: ModelRequestRequirements,
        policy: ModelFallbackPolicy,
    ): Preflight {
        val eligibility = resolver.eligibilityFor(role, primary, requirements)
        if (eligibility.state != ModelEligibilityState.RATE_LIMITED) return Preflight.Primary

        val visited = LinkedHashSet<String>()
        visited += key(primary)
        var attempts = 0

        for (preference in policy.candidates(role)) {
            if (attempts >= policy.maxFallbackAttempts) break

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

            val candidateEligibility = resolver.eligibilityFor(role, candidate, requirements)
            if (!candidateEligibility.eligible) {
                logSkip(role, preference, "ineligible:${candidateEligibility.state.name}")
                continue
            }

            attempts += 1
            emitStarted(sink, sessionId, role, primary, candidate, ModelFallbackReason.RATE_LIMITED, attempts)
            // The structured record of this decision: what was asked, why it was passed
            // over, what the candidate's eligibility was, and that the switch happened
            // before any request rather than after a failure. Credential-free.
            logger.info(
                "Model fallback decision",
                ModelFallbackDecision(
                    role = role,
                    sessionId = sessionId,
                    originalProviderId = primary.providerId,
                    originalModelId = primary.model,
                    originalConnectionId = primary.connectionId,
                    ineligibleReason = eligibility.state.name,
                    consideredProviderId = candidate.providerId,
                    consideredModelId = candidate.model,
                    consideredConnectionId = candidate.connectionId,
                    eligibilityState = candidateEligibility.state.name,
                    healthState = candidateEligibility.healthState?.name,
                    quotaState = candidateEligibility.rateLimitKind?.name,
                    selectedProviderId = candidate.providerId,
                    selectedModelId = candidate.model,
                    selectedConnectionId = candidate.connectionId,
                    requestSent = true,
                    beforeRequest = true,
                    attempts = attempts,
                    trigger = ModelFallbackReason.RATE_LIMITED.name,
                    // No request was sent, so this is AgentX's own admission control
                    // refusing rather than a provider answering 429 — a distinction
                    // the user can act on differently.
                    failureCategory = FallbackFailureCategory.QUOTA_EXHAUSTED.name,
                ).fields(),
            )
            // Structured, credential-free record that the switch happened *before*
            // the primary was asked, so an observer can tell a preemptive switch
            // from a reactive recovery.
            logger.info(
                "Preemptive model switch before send",
                mapOf(
                    "sessionId" to sessionId,
                    "role" to role.name,
                    "preemptive" to true,
                    "reason" to ModelFallbackReason.RATE_LIMITED.name,
                    "fromProvider" to primary.providerId,
                    "fromModel" to primary.model,
                    "toProvider" to candidate.providerId,
                    "toModel" to candidate.model,
                    "attempts" to attempts,
                ),
            )
            return Preflight.Switch(candidate)
        }

        // Nothing has safe headroom. The primary is still not sent: the caller gets
        // its structured quota error instead of a request that was known in advance
        // to be over the usable limit, and no provider is retried in a loop.
        logger.info(
            "No configured candidate has safe quota headroom",
            mapOf(
                "sessionId" to sessionId,
                "role" to role.name,
                "preemptive" to true,
                "providerId" to primary.providerId,
                "modelId" to primary.model,
                "state" to eligibility.state.name,
                "candidates" to policy.candidates(role).size,
            ),
        )
        return Preflight.NoHeadroom(AgentModelResolutionException(error = eligibility.rejectionError()))
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
        // Kept alongside the reason so the chain's outcome is described by what
        // happened, not only by whether it was worth retrying.
        var lastCategory = ModelFallbackErrors.categoryFor(primaryError)

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
            emitStarted(sink, sessionId, role, current, candidate, lastReason, attempts, lastError)
            try {
                val result = call(candidate)
                sink.emit(
                    AgentEvent.ModelFallbackSucceeded(
                        sessionId = sessionId,
                        role = role,
                        fromProviderId = primary.providerId,
                        fromModelId = primary.model,
                        fromConnectionId = primary.connectionId,
                        toProviderId = candidate.providerId,
                        toModelId = candidate.model,
                        toConnectionId = candidate.connectionId,
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
                lastCategory = ModelFallbackErrors.categoryFor(error)
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
                    // The category the chain ended on, so "we tried the configured
                    // fallbacks and they were all down" is legible without reading
                    // each attempt's own record.
                    "failureCategory" to lastCategory.name,
                ) + retryFieldsOf(lastError),
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
        cause: Throwable? = null,
    ) {
        sink.emit(
            AgentEvent.ModelFallbackStarted(
                sessionId = sessionId,
                role = role,
                fromProviderId = from.providerId,
                fromModelId = from.model,
                fromConnectionId = from.connectionId,
                toProviderId = to.providerId,
                toModelId = to.model,
                toConnectionId = to.connectionId,
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
            ) + retryFieldsOf(cause),
        )
    }

    /**
     * What the bounded retry layer did before this fallback decision, read from the
     * failure the retry layer annotated.
     *
     * A chain that starts only after the same model was retried and gave up is a
     * different story from one that starts on the first failure, and the difference
     * is only legible if it is stated. Empty when the failure never went through a
     * retry, so the common case carries no extra fields.
     */
    private fun retryFieldsOf(cause: Throwable?): Map<String, Any?> {
        val details = (cause as? ModelProviderError)?.details ?: return emptyMap()
        val attempts = details["attempts"] ?: return emptyMap()
        return mapOf(
            "retryAttempts" to attempts,
            "retryMaxAttempts" to details["maxAttempts"],
            "retryOutcome" to details["retryOutcome"],
            "retriesExhausted" to (details["retryOutcome"] == RetryVerdict.ATTEMPTS_EXHAUSTED.name),
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

    /**
     * Identity of one candidate. The saved connection is part of the key because
     * two independent connections can share a provider family and even a model
     * id; without it a legitimate candidate would look like a duplicate of the
     * primary or of a previously tried one.
     */
    private fun key(config: ModelConfig): String =
        "${config.connectionId}::${config.providerId}::${config.model}"
}
