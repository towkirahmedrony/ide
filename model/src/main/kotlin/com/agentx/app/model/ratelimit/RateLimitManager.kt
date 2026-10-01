package com.agentx.app.model.ratelimit

import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelUsage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A request asking for admission. Estimates are reserved before the provider is
 * called and reconciled with real usage afterwards.
 *
 * [rateLimited] is false for local/self-hosted runtimes (Qwen via llama.cpp,
 * Ollama, vLLM): they share no remote quota and stay unlimited by default.
 */
data class RateLimitRequest(
    val providerId: String,
    val modelId: String,
    val accountId: String? = null,
    val estimatedInputTokens: Long = 0L,
    val estimatedOutputTokens: Long = 0L,
    val rateLimited: Boolean = true,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
    }

    /** Tokens reserved up front (input estimate plus expected output budget). */
    val reservedTokens: Long get() = estimatedInputTokens + estimatedOutputTokens
}

/**
 * A reservation returned by [RateLimitManager.acquire]. Exactly one of
 * [RateLimitManager.complete] or [RateLimitManager.abandon] must be called; the
 * permit closes itself so a reservation can never be double-released or double
 * counted.
 */
class RateLimitPermit internal constructor(
    val request: RateLimitRequest,
    internal val entries: List<Entry>,
    val reservedTokens: Long,
    val admittedAtMillis: Long,
) {
    internal data class Entry(
        val key: RateLimitKey,
        val limits: RateLimitLimits,
        /** Minute window the token reservation belongs to. */
        val minuteWindowStart: Long,
    )

    private val claimed = AtomicBoolean(false)

    /** True once this permit has been completed or abandoned. */
    val closed: Boolean get() = claimed.get()

    /** @return true for the first caller only. */
    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    companion object {
        internal fun bypassed(request: RateLimitRequest, nowMillis: Long): RateLimitPermit =
            RateLimitPermit(request, emptyList(), 0L, nowMillis)
    }
}

/**
 * Central, provider-agnostic admission control. Every remote model request passes
 * through [acquire] before the provider API call, so all agent roles (Main,
 * Explorer, Researcher, Coder, Debugger, Reviewer, Tester) share one quota and
 * cannot independently exceed it.
 */
interface RateLimitManager {
    /** Every configured profile, in insertion order. */
    fun profiles(): List<RateLimitProfile>

    fun profile(providerId: String, modelId: String? = null, accountId: String? = null): RateLimitProfile?

    /**
     * Stores [profile]. When authoritative provider metadata exists for the same
     * provider, the stored value is clamped so an app-configured limit can never
     * exceed it.
     */
    suspend fun updateProfile(profile: RateLimitProfile)

    suspend fun removeProfile(key: RateLimitKey)

    /** Restores persisted profiles; safe to call more than once. */
    suspend fun restore()

    /**
     * Waits until [request] may be sent, then reserves its estimated cost.
     * Cancelling the caller stops the wait immediately.
     *
     * @throws ModelProviderError with [ModelProviderErrorCode.RATE_LIMITED] when
     *   the request cannot be admitted (a zero limit, a single request larger
     *   than the whole TPM budget, or no capacity within the configured wait).
     */
    suspend fun acquire(request: RateLimitRequest): RateLimitPermit

    /** Reconciles [permit] with the usage the provider actually reported. */
    suspend fun complete(permit: RateLimitPermit, usage: ModelUsage?)

    /** Releases [permit] after a failure, refunding the token reservation. */
    suspend fun abandon(permit: RateLimitPermit)

    /** Records a provider rate-limit (429) event against the usage tracker. */
    suspend fun noteRateLimited(request: RateLimitRequest)

    /** The usage tracker backing this manager, for the Settings UI. */
    val usage: UsageTracker
}

/** Persistence port for rate-limit profiles. Never stores a credential. */
interface RateLimitProfileStore {
    suspend fun load(): List<RateLimitProfile>

    suspend fun save(profiles: List<RateLimitProfile>)
}

/** Store used by previews, tests and the platform default. */
class InMemoryRateLimitProfileStore(initial: List<RateLimitProfile> = emptyList()) : RateLimitProfileStore {
    private val values = LinkedHashMap<RateLimitKey, RateLimitProfile>()

    init {
        initial.forEach { values[it.key] = it }
    }

    override suspend fun load(): List<RateLimitProfile> = values.values.toList()

    override suspend fun save(profiles: List<RateLimitProfile>) {
        values.clear()
        profiles.forEach { values[it.key] = it }
    }
}

/**
 * Default [RateLimitManager].
 *
 * Fixed (aligned) minute and day windows are used rather than a sliding window:
 * they are deterministic, cheap, and easy to reconcile. All mutation happens
 * under one lock, so two simultaneous requests can never both pass the same
 * remaining Quota.
 */
class DefaultRateLimitManager(
    private val clock: RateLimitClock = SystemRateLimitClock(),
    private val tracker: UsageTracker = DefaultUsageTracker(),
    private val profileStore: RateLimitProfileStore? = null,
    /**
     * Applied when a remote provider has no configured profile. `null` disables
     * the safe default, which is only appropriate for a fully self-hosted setup.
     */
    private val unknownRemoteLimits: RateLimitLimits? = SAFE_REMOTE_DEFAULTS,
    /** Longest a request waits for capacity before failing clearly. */
    private val maxWaitMillis: Long = DEFAULT_MAX_WAIT_MILLIS,
    /** Re-check interval used when waiting on the concurrency limit. */
    private val concurrencyPollMillis: Long = DEFAULT_CONCURRENCY_POLL_MILLIS,
) : RateLimitManager {

    private val lock = Any()
    private val buckets = LinkedHashMap<RateLimitKey, Bucket>()

    @Volatile
    private var profileMap: Map<RateLimitKey, RateLimitProfile> = emptyMap()

    override val usage: UsageTracker get() = tracker

    override fun profiles(): List<RateLimitProfile> = profileMap.values.toList()

    override fun profile(providerId: String, modelId: String?, accountId: String?): RateLimitProfile? =
        profileMap[RateLimitKey(providerId, modelId?.takeIf { it.isNotBlank() }, accountId?.takeIf { it.isNotBlank() })]

    override suspend fun updateProfile(profile: RateLimitProfile) {
        val stored = clampToProviderCeiling(profile)
        synchronized(lock) {
            profileMap = profileMap + (stored.key to stored)
        }
        persistProfiles()
    }

    override suspend fun removeProfile(key: RateLimitKey) {
        synchronized(lock) {
            profileMap = profileMap - key
            buckets.remove(key)
        }
        persistProfiles()
    }

    override suspend fun restore() {
        val stored = profileStore?.load() ?: return
        synchronized(lock) {
            if (profileMap.isNotEmpty()) return
            profileMap = stored.associateBy { it.key }
        }
    }

    override suspend fun acquire(request: RateLimitRequest): RateLimitPermit {
        val now = clock.nowMillis()
        if (!request.rateLimited) return RateLimitPermit.bypassed(request, now)

        val planned = plan(request)
        if (planned.isEmpty()) return RateLimitPermit.bypassed(request, now)

        val deadline = now + maxWaitMillis
        while (true) {
            val outcome = synchronized(lock) { tryAdmit(request, planned, clock.nowMillis()) }
            when (outcome) {
                is Admission.Reserved -> return outcome.permit
                is Admission.Impossible -> throw admissionError(request, outcome.reason)
                is Admission.Wait -> {
                    val waitMillis = maxOf(1L, outcome.millis)
                    if (clock.nowMillis() + waitMillis > deadline) {
                        throw admissionError(
                            request,
                            "capacity did not free up within ${maxWaitMillis}ms",
                        )
                    }
                    clock.sleep(waitMillis)
                }
            }
        }
    }

    override suspend fun complete(permit: RateLimitPermit, usage: ModelUsage?) {
        if (!permit.claim()) return
        val now = clock.nowMillis()
        synchronized(lock) { reconcile(permit, usage?.totalTokens?.toLong()) }
        tracker.record(
            UsageEntry.of(
                providerId = permit.request.providerId,
                modelId = permit.request.modelId,
                usage = usage,
                atMillis = now,
            ),
        )
    }

    override suspend fun abandon(permit: RateLimitPermit) {
        if (!permit.claim()) return
        synchronized(lock) { reconcile(permit, actualTotalTokens = null) }
    }

    override suspend fun noteRateLimited(request: RateLimitRequest) {
        tracker.recordRateLimit(request.providerId, request.modelId, clock.nowMillis())
    }

    // --- internals ---------------------------------------------------------

    private data class PlannedLimit(val key: RateLimitKey, val limits: RateLimitLimits)

    private sealed interface Admission {
        data class Reserved(val permit: RateLimitPermit) : Admission

        data class Wait(val millis: Long) : Admission

        data class Impossible(val reason: String) : Admission
    }

    private fun plan(request: RateLimitRequest): List<PlannedLimit> {
        val providerKey = RateLimitKey(request.providerId, null, request.accountId)
        val modelKey = RateLimitKey(request.providerId, request.modelId, request.accountId)
        val planned = mutableListOf<PlannedLimit>()
        var explicitlyUnlimited = false

        profileMap[providerKey]?.let { profile ->
            if (profile.enabled) planned += PlannedLimit(providerKey, profile.effectiveLimits())
            else explicitlyUnlimited = true
        }
        profileMap[modelKey]?.let { profile ->
            if (profile.enabled) planned += PlannedLimit(modelKey, profile.effectiveLimits())
            else explicitlyUnlimited = true
        }
        if (planned.isEmpty() && !explicitlyUnlimited) {
            unknownRemoteLimits?.takeUnless { it.isEmpty }?.let { planned += PlannedLimit(providerKey, it) }
        }
        return planned
    }

    private fun tryAdmit(
        request: RateLimitRequest,
        planned: List<PlannedLimit>,
        now: Long,
    ): Admission {
        val reservedTokens = request.reservedTokens
        var wait = 0L

        planned.forEach { limit ->
            val bucket = bucketFor(limit.key)
            bucket.refresh(now)
            val limits = limit.limits

            limits.requestsPerMinute?.let { rpm ->
                if (rpm <= 0) return Admission.Impossible("no requests per minute are permitted")
                if (bucket.minuteRequests + 1 > rpm) wait = maxOf(wait, bucket.minuteWindowEnd - now)
            }
            limits.requestsPerDay?.let { rpd ->
                if (rpd <= 0) return Admission.Impossible("no requests per day are permitted")
                if (bucket.dayRequests + 1 > rpd) wait = maxOf(wait, bucket.dayWindowEnd - now)
            }
            limits.tokensPerMinute?.let { tpm ->
                if (tpm <= 0) return Admission.Impossible("no tokens per minute are permitted")
                if (reservedTokens > tpm) {
                    return Admission.Impossible(
                        "a single request needs $reservedTokens tokens but only $tpm are allowed per minute",
                    )
                }
                if (bucket.minuteTokens + reservedTokens > tpm) wait = maxOf(wait, bucket.minuteWindowEnd - now)
            }
            limits.maxConcurrentRequests?.let { concurrent ->
                if (concurrent <= 0) return Admission.Impossible("no concurrent requests are permitted")
                if (bucket.inFlight + 1 > concurrent) wait = maxOf(wait, concurrencyPollMillis)
            }
        }

        if (wait > 0L) return Admission.Wait(wait)

        val entries = planned.map { limit ->
            val bucket = bucketFor(limit.key)
            bucket.minuteRequests += 1
            bucket.dayRequests += 1
            bucket.minuteTokens += reservedTokens
            bucket.inFlight += 1
            RateLimitPermit.Entry(limit.key, limit.limits, bucket.minuteWindowStart)
        }
        // request.reservedTokens stays the permit's reserved amount.
        return Admission.Reserved(
            RateLimitPermit(
                request = request,
                entries = entries,
                reservedTokens = reservedTokens,
                admittedAtMillis = now,
            ),
        )
    }

    private fun reconcile(permit: RateLimitPermit, actualTotalTokens: Long?) {
        permit.entries.forEach { entry ->
            val bucket = bucketFor(entry.key)
            if (bucket.inFlight > 0) bucket.inFlight -= 1
            // Only reconcile tokens while the reservation's window is current;
            // a rolled window has already reset its counters.
            if (bucket.minuteWindowStart == entry.minuteWindowStart) {
                bucket.minuteTokens = maxOf(0L, bucket.minuteTokens - permit.reservedTokens)
                if (actualTotalTokens != null && actualTotalTokens > 0L) {
                    bucket.minuteTokens += actualTotalTokens
                }
            }
        }
    }

    private fun bucketFor(key: RateLimitKey): Bucket = buckets.getOrPut(key) { Bucket() }

    private fun clampToProviderCeiling(profile: RateLimitProfile): RateLimitProfile {
        val ceiling = profileMap[RateLimitKey(profile.providerId, null, profile.accountId)] ?: return profile
        if (ceiling.source != RateLimitSource.PROVIDER_REPORTED) return profile
        return profile.clamp(ceiling)
    }

    private suspend fun persistProfiles() {
        val snapshot = profileMap.values.toList()
        // Persistence is best-effort and must never block admission.
        runCatching { profileStore?.save(snapshot) }
    }

    private fun admissionError(request: RateLimitRequest, reason: String): ModelProviderError =
        ModelProviderError(
            code = ModelProviderErrorCode.RATE_LIMITED,
            message = "Rate limit for ${request.providerId}/${request.modelId}: $reason",
            providerId = request.providerId,
            retryable = false,
        )

    /** Live counters for one [RateLimitKey]. Mutated only under the manager lock. */
    private class Bucket {
        var minuteWindowStart = Long.MIN_VALUE
        var minuteRequests = 0
        var minuteTokens = 0L
        var dayWindowStart = Long.MIN_VALUE
        var dayRequests = 0
        var inFlight = 0

        fun refresh(now: Long) {
            val minuteStart = now - now.mod(MINUTE_MILLIS)
            if (minuteStart != minuteWindowStart) {
                minuteWindowStart = minuteStart
                minuteRequests = 0
                minuteTokens = 0L
            }
            val dayStart = now - now.mod(DAY_MILLIS)
            if (dayStart != dayWindowStart) {
                dayWindowStart = dayStart
                dayRequests = 0
            }
        }

        val minuteWindowEnd: Long get() = minuteWindowStart + MINUTE_MILLIS

        val dayWindowEnd: Long get() = dayWindowStart + DAY_MILLIS
    }

    companion object {
        const val MINUTE_MILLIS: Long = 60_000L
        const val DAY_MILLIS: Long = 24L * 60L * 60L * 1_000L

        /** Conservative placeholder used when a remote provider's limits are unknown. */
        val SAFE_REMOTE_DEFAULTS: RateLimitLimits = RateLimitLimits(
            requestsPerMinute = 60,
            tokensPerMinute = 60_000L,
            requestsPerDay = 1_000,
            maxConcurrentRequests = 4,
        )

        const val DEFAULT_MAX_WAIT_MILLIS: Long = 60_000L
        const val DEFAULT_CONCURRENCY_POLL_MILLIS: Long = 50L
    }
}

private fun Long.mod(divisor: Long): Long = ((this % divisor) + divisor) % divisor
