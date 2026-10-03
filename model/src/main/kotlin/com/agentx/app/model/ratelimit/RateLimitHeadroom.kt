package com.agentx.app.model.ratelimit

/**
 * One dimension's contribution to a headroom decision.
 *
 * [used] and [remaining] are informational: local accounting is an estimate, and
 * [remaining] is left null whenever the limit is not known, because a number that
 * was never observed must never be presented as if it were exact. The
 * authoritative answer for whether a request may run is
 * [RateLimitHeadroom.decision], which is produced by the same manager logic that
 * the gateway enforces.
 */
data class QuotaDimensionState(
    val dimension: QuotaDimension,
    val limit: Long? = null,
    val used: Long? = null,
    val remaining: Long? = null,
    /** The fraction of the limit held back as safety headroom, when configured. */
    val safetyMargin: Double? = null,
    val knowledge: QuotaKnowledge = QuotaKnowledge.UNKNOWN,
    val source: RateLimitSource? = null,
    /** Time until this dimension refills, when a provider reported one. */
    val resetAfterMillis: Long? = null,
) {
    /** True only when the provider gave us the ceiling and what is left of it. */
    val exactRemainingKnown: Boolean get() = limit != null && remaining != null
}

/**
 * The structured quota picture for one prospective request.
 *
 * This is the read-only view that candidate selection and observability need:
 * it answers "may this provider/model take this request, and on what grounds?"
 * without reserving anything and without a second decision path — [decision]
 * comes from the same [RateLimitManager.canRequest] the gateway enforces, so a
 * preflight answer can never disagree with the enforcement.
 *
 * A local endpoint does not consume remote quota, so its headroom is reported as
 * local and its decision is always allowed by the remote rules.
 */
data class RateLimitHeadroom(
    val providerId: String,
    val modelId: String,
    val accountId: String? = null,
    val decision: RateLimitDecision,
    val local: Boolean = false,
    /** The capacity this request is expected to consume. */
    val estimatedRequestTokens: Long = 0L,
    val dimensions: List<QuotaDimensionState> = emptyList(),
    /** What the provider most recently reported, when a signal was ingested. */
    val observed: ProviderQuotaSignal? = null,
) {
    val allowed: Boolean get() = decision is RateLimitDecision.Allowed

    val blocked: Boolean get() = decision is RateLimitDecision.Blocked

    /** The dimension a block was attributed to, when the manager could attribute it. */
    val limitingKind: RateLimitKind? get() = (decision as? RateLimitDecision.Blocked)?.kind

    /** How long the caller should wait before retrying, when the provider or a cooldown said so. */
    val retryAfterMillis: Long? get() = (decision as? RateLimitDecision.Blocked)?.retryAfterMs

    val reason: String? get() = (decision as? RateLimitDecision.Blocked)?.reason

    /** The provider's own view of one dimension, if it reported one. */
    fun providerFact(dimension: QuotaDimension): ObservedQuotaFact? = observed?.factFor(dimension)
}
