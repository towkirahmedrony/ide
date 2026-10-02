package com.agentx.app.model.ratelimit

import com.agentx.app.model.ModelUsage

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
    val estimatedInputTokens: Int = 0,
    val estimatedOutputTokens: Int = 0,
    val rateLimited: Boolean = true,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(estimatedInputTokens >= 0) { "estimatedInputTokens must not be negative" }
        require(estimatedOutputTokens >= 0) { "estimatedOutputTokens must not be negative" }
    }

    val reservedTokens: Long get() = estimatedInputTokens.toLong() + estimatedOutputTokens.toLong()
}

/**
 * Central, provider-agnostic admission control. Every remote model request
 * passes through [canRequest] / [reserve] before the provider API call, so all
 * agent roles share one quota and cannot independently exceed it.
 */
interface RateLimitManager {
    fun profiles(): List<RateLimitProfile>

    fun profile(providerId: String, modelId: String? = null, accountId: String? = null): RateLimitProfile?

    suspend fun updateProfile(profile: RateLimitProfile)

    suspend fun removeProfile(key: RateLimitKey)

    suspend fun restore()

    suspend fun canRequest(
        providerId: String,
        modelId: String,
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int,
        accountId: String? = null,
        local: Boolean = false,
    ): RateLimitDecision

    suspend fun reserve(
        providerId: String,
        modelId: String,
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int,
        accountId: String? = null,
        local: Boolean = false,
    ): RateLimitReservation

    suspend fun reconcile(
        reservation: RateLimitReservation,
        actualInputTokens: Int?,
        actualOutputTokens: Int?,
        success: Boolean,
    )

    suspend fun recordRateLimited(
        providerId: String,
        modelId: String,
        retryAfterMs: Long?,
        kind: RateLimitKind = RateLimitKind.UNKNOWN,
    )

    val usage: UsageTracker
}

suspend fun RateLimitManager.canRequest(request: RateLimitRequest): RateLimitDecision =
    canRequest(
        providerId = request.providerId,
        modelId = request.modelId,
        estimatedInputTokens = request.estimatedInputTokens,
        estimatedOutputTokens = request.estimatedOutputTokens,
        accountId = request.accountId,
        local = !request.rateLimited,
    )

suspend fun RateLimitManager.reserve(request: RateLimitRequest): RateLimitReservation =
    reserve(
        providerId = request.providerId,
        modelId = request.modelId,
        estimatedInputTokens = request.estimatedInputTokens,
        estimatedOutputTokens = request.estimatedOutputTokens,
        accountId = request.accountId,
        local = !request.rateLimited,
    )

suspend fun RateLimitManager.acquire(request: RateLimitRequest): RateLimitReservation = reserve(request)

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
 * remaining quota.
 *
 * Unknown limits stay unknown. This class never invents RPM/TPM/RPD/TPD values.
 */
class DefaultRateLimitManager(
    private val clock: RateLimitClock = SystemRateLimitClock(),
    private val tracker: UsageTracker = DefaultUsageTracker(),
    private val profileStore: RateLimitProfileStore? = null,
    private val limitSource: RateLimitLimitSource? = null,
) : RateLimitManager {

    private val lock = Any()
    private val buckets = LinkedHashMap<RateLimitKey, Bucket>()

    @Volatile
    private var profileMap: Map<RateLimitKey, RateLimitProfile> = emptyMap()

    private val profilesSource = ProfileRateLimitLimitSource { profileMap }

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

    override suspend fun canRequest(
        providerId: String,
        modelId: String,
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int,
        accountId: String?,
        local: Boolean,
    ): RateLimitDecision {
        val request = packed(providerId, modelId, estimatedInputTokens, estimatedOutputTokens, accountId, local)
        if (!request.rateLimited) return RateLimitDecision.Allowed
        val planned = plan(request)
        return synchronized(lock) {
            val now = clock.nowMillis()
            cooldownDecision(request, now)?.let { return@synchronized it }
            if (planned.isEmpty()) return@synchronized RateLimitDecision.Allowed
            when (val outcome = inspect(request, planned, now)) {
                is Inspect.Ok -> RateLimitDecision.Allowed
                is Inspect.Blocked -> outcome.decision
            }
        }
    }

    override suspend fun reserve(
        providerId: String,
        modelId: String,
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int,
        accountId: String?,
        local: Boolean,
    ): RateLimitReservation {
        val request = packed(providerId, modelId, estimatedInputTokens, estimatedOutputTokens, accountId, local)
        val now = clock.nowMillis()
        if (!request.rateLimited) {
            return RateLimitReservation.bypassed(
                providerId = providerId,
                modelId = modelId,
                accountId = accountId,
                estimatedInputTokens = estimatedInputTokens,
                estimatedOutputTokens = estimatedOutputTokens,
                nowMillis = now,
            )
        }
        val planned = plan(request)
        val outcome = synchronized(lock) {
            val at = clock.nowMillis()
            cooldownDecision(request, at)?.let { return@synchronized Admission.Denied(it) }
            if (planned.isEmpty()) {
                return@synchronized Admission.Reserved(
                    RateLimitReservation.bypassed(
                        providerId = providerId,
                        modelId = modelId,
                        accountId = accountId,
                        estimatedInputTokens = estimatedInputTokens,
                        estimatedOutputTokens = estimatedOutputTokens,
                        nowMillis = at,
                    ),
                )
            }
            tryAdmit(request, planned, at)
        }
        return when (outcome) {
            is Admission.Reserved -> outcome.reservation
            is Admission.Denied -> throw outcome.decision.toError()
        }
    }

    override suspend fun reconcile(
        reservation: RateLimitReservation,
        actualInputTokens: Int?,
        actualOutputTokens: Int?,
        success: Boolean,
    ) {
        if (!reservation.claim()) return
        val now = clock.nowMillis()
        val actualTotal = actualTotalTokens(actualInputTokens, actualOutputTokens)
        synchronized(lock) { applyReconcile(reservation, actualTotal, success) }
        if (success) {
            tracker.record(
                UsageEntry(
                    providerId = reservation.providerId,
                    modelId = reservation.modelId,
                    inputTokens = actualInputTokens,
                    outputTokens = actualOutputTokens,
                    totalTokens = actualTotal?.toInt(),
                    estimated = actualTotal == null,
                    atMillis = now,
                ),
            )
        }
    }

    override suspend fun recordRateLimited(
        providerId: String,
        modelId: String,
        retryAfterMs: Long?,
        kind: RateLimitKind,
    ) {
        val now = clock.nowMillis()
        tracker.recordRateLimit(providerId, modelId, now)
        synchronized(lock) {
            val key = RateLimitKey(providerId, modelId)
            val bucket = bucketFor(key)
            bucket.refresh(now)
            val until = blockedUntil(bucket, key, now, retryAfterMs, kind)
            if (until != null) {
                bucket.blockedUntilMillis = maxOf(bucket.blockedUntilMillis, until)
                bucket.blockedKind = kind
            }
        }
    }

    // --- internals ---------------------------------------------------------

    private data class PlannedLimit(val key: RateLimitKey, val limits: RateLimitLimits)

    private sealed interface Inspect {
        data object Ok : Inspect
        data class Blocked(val decision: RateLimitDecision.Blocked) : Inspect
    }

    private sealed interface Admission {
        data class Reserved(val reservation: RateLimitReservation) : Admission
        data class Denied(val decision: RateLimitDecision.Blocked) : Admission
    }

    private fun packed(
        providerId: String,
        modelId: String,
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int,
        accountId: String?,
        local: Boolean,
    ) = RateLimitRequest(
        providerId = providerId,
        modelId = modelId,
        accountId = accountId,
        estimatedInputTokens = estimatedInputTokens,
        estimatedOutputTokens = estimatedOutputTokens,
        rateLimited = !local,
    )

    private fun plan(request: RateLimitRequest): List<PlannedLimit> {
        val providerKey = RateLimitKey(request.providerId, null, request.accountId)
        val modelKey = RateLimitKey(request.providerId, request.modelId, request.accountId)
        val planned = mutableListOf<PlannedLimit>()
        profileMap[providerKey]?.takeIf { it.enabled }?.let {
            planned += PlannedLimit(providerKey, it.effectiveLimits())
        }
        profileMap[modelKey]?.takeIf { it.enabled }?.let {
            planned += PlannedLimit(modelKey, it.effectiveLimits())
        }
        if (planned.isEmpty()) {
            val sourced = (limitSource ?: profilesSource)
                .limitsFor(request.providerId, request.modelId, request.accountId)
            if (!sourced.isEmpty) planned += PlannedLimit(modelKey, sourced)
        }
        return planned.filter { !it.limits.isEmpty }
    }

    private fun inspect(request: RateLimitRequest, planned: List<PlannedLimit>, now: Long): Inspect {
        planned.forEach { limit ->
            val blocked = blockReason(request, limit, now) ?: return@forEach
            return Inspect.Blocked(blocked)
        }
        return Inspect.Ok
    }

    private fun tryAdmit(request: RateLimitRequest, planned: List<PlannedLimit>, now: Long): Admission {
        when (val inspected = inspect(request, planned, now)) {
            is Inspect.Blocked -> return Admission.Denied(inspected.decision)
            Inspect.Ok -> Unit
        }
        val reservedTokens = request.reservedTokens
        val entries = planned.map { limit ->
            val bucket = bucketFor(limit.key)
            bucket.refresh(now)
            bucket.minuteRequests += 1
            bucket.dayRequests += 1
            bucket.minuteTokens += reservedTokens
            bucket.dayTokens += reservedTokens
            bucket.inFlight += 1
            RateLimitReservation.Entry(limit.key, limit.limits, bucket.minuteWindowStart, bucket.dayWindowStart)
        }
        return Admission.Reserved(
            RateLimitReservation(
                providerId = request.providerId,
                modelId = request.modelId,
                accountId = request.accountId,
                estimatedInputTokens = request.estimatedInputTokens,
                estimatedOutputTokens = request.estimatedOutputTokens,
                reservedTokens = reservedTokens,
                admittedAtMillis = now,
                bypassed = false,
                entries = entries,
            ),
        )
    }

    private fun blockReason(request: RateLimitRequest, limit: PlannedLimit, now: Long): RateLimitDecision.Blocked? {
        val bucket = bucketFor(limit.key)
        bucket.refresh(now)
        val limits = limit.limits
        limits.requestsPerMinute.knownValue?.let { rpm ->
            if (rpm <= 0L) {
                return blocked(request, RateLimitKind.REQUEST, bucket.minuteWindowEnd - now, "no requests per minute are permitted")
            }
            if (bucket.minuteRequests + 1 > rpm) {
                return blocked(request, RateLimitKind.REQUEST, bucket.minuteWindowEnd - now, "requests per minute exhausted")
            }
        }
        limits.requestsPerDay.knownValue?.let { rpd ->
            if (rpd <= 0L) {
                return blocked(request, RateLimitKind.REQUEST, bucket.dayWindowEnd - now, "no requests per day are permitted")
            }
            if (bucket.dayRequests + 1 > rpd) {
                return blocked(request, RateLimitKind.REQUEST, bucket.dayWindowEnd - now, "requests per day exhausted")
            }
        }
        val reservedTokens = request.reservedTokens
        limits.tokensPerMinute.knownValue?.let { tpm ->
            if (tpm <= 0L) {
                return blocked(request, RateLimitKind.TOKENS, bucket.minuteWindowEnd - now, "no tokens per minute are permitted")
            }
            if (reservedTokens > tpm) {
                return blocked(
                    request,
                    RateLimitKind.TOKENS,
                    null,
                    "a single request needs $reservedTokens tokens but only $tpm are allowed per minute",
                )
            }
            if (bucket.minuteTokens + reservedTokens > tpm) {
                return blocked(request, RateLimitKind.TOKENS, bucket.minuteWindowEnd - now, "tokens per minute exhausted")
            }
        }
        limits.tokensPerDay.knownValue?.let { tpd ->
            if (tpd <= 0L) {
                return blocked(request, RateLimitKind.TOKENS, bucket.dayWindowEnd - now, "no tokens per day are permitted")
            }
            if (reservedTokens > tpd) {
                return blocked(
                    request,
                    RateLimitKind.TOKENS,
                    null,
                    "a single request needs $reservedTokens tokens but only $tpd are allowed per day",
                )
            }
            if (bucket.dayTokens + reservedTokens > tpd) {
                return blocked(request, RateLimitKind.TOKENS, bucket.dayWindowEnd - now, "tokens per day exhausted")
            }
        }
        limits.maxConcurrentRequests.knownValue?.let { concurrent ->
            if (concurrent <= 0L) {
                return blocked(request, RateLimitKind.REQUEST, null, "no concurrent requests are permitted")
            }
            if (bucket.inFlight + 1 > concurrent) {
                return blocked(request, RateLimitKind.REQUEST, null, "max concurrent requests exhausted")
            }
        }
        return null
    }

    private fun cooldownDecision(request: RateLimitRequest, now: Long): RateLimitDecision.Blocked? {
        val keys = listOf(
            RateLimitKey(request.providerId, request.modelId, request.accountId),
            RateLimitKey(request.providerId, null, request.accountId),
        )
        keys.forEach { key ->
            val bucket = buckets[key] ?: return@forEach
            bucket.refresh(now)
            if (bucket.blockedUntilMillis > now) {
                return blocked(
                    request,
                    kind = bucket.blockedKind,
                    retryAfterMs = bucket.blockedUntilMillis - now,
                    reason = "provider reported a rate limit; wait before retrying",
                )
            }
        }
        return null
    }

    private fun blocked(
        request: RateLimitRequest,
        kind: RateLimitKind,
        retryAfterMs: Long?,
        reason: String,
    ) = RateLimitDecision.Blocked(
        providerId = request.providerId,
        modelId = request.modelId,
        retryAfterMs = retryAfterMs?.takeIf { it > 0L },
        kind = kind,
        reason = reason,
    )

    private fun applyReconcile(reservation: RateLimitReservation, actualTotalTokens: Long?, success: Boolean) {
        reservation.entries.forEach { entry ->
            val bucket = bucketFor(entry.key)
            if (bucket.inFlight > 0) bucket.inFlight -= 1
            val sameMinute = bucket.minuteWindowStart == entry.minuteWindowStart
            val sameDay = bucket.dayWindowStart == entry.dayWindowStart
            if (!success) {
                if (sameMinute) {
                    bucket.minuteTokens = maxOf(0L, bucket.minuteTokens - reservation.reservedTokens)
                    if (bucket.minuteRequests > 0) bucket.minuteRequests -= 1
                }
                if (sameDay) {
                    bucket.dayTokens = maxOf(0L, bucket.dayTokens - reservation.reservedTokens)
                    if (bucket.dayRequests > 0) bucket.dayRequests -= 1
                }
                return@forEach
            }
            if (actualTotalTokens == null) return@forEach
            if (sameMinute) {
                bucket.minuteTokens = maxOf(0L, bucket.minuteTokens - reservation.reservedTokens)
                bucket.minuteTokens += actualTotalTokens
            }
            if (sameDay) {
                bucket.dayTokens = maxOf(0L, bucket.dayTokens - reservation.reservedTokens)
                bucket.dayTokens += actualTotalTokens
            }
        }
    }

    private fun blockedUntil(
        bucket: Bucket,
        key: RateLimitKey,
        now: Long,
        retryAfterMs: Long?,
        kind: RateLimitKind,
    ): Long? {
        if (retryAfterMs != null && retryAfterMs > 0L) return now + retryAfterMs
        val limits = limitsForKey(key)
        return when (kind) {
            RateLimitKind.TOKENS -> when {
                limits.tokensPerMinute is Quota.Known -> bucket.minuteWindowEnd
                limits.tokensPerDay is Quota.Known -> bucket.dayWindowEnd
                else -> null
            }
            RateLimitKind.REQUEST -> when {
                limits.requestsPerMinute is Quota.Known -> bucket.minuteWindowEnd
                limits.requestsPerDay is Quota.Known -> bucket.dayWindowEnd
                else -> null
            }
            RateLimitKind.UNKNOWN -> null
        }
    }

    private fun limitsForKey(key: RateLimitKey): RateLimitLimits {
        profileMap[key]?.takeIf { it.enabled }?.let { return it.effectiveLimits() }
        if (key.modelId != null) {
            profileMap[RateLimitKey(key.providerId, null, key.accountId)]
                ?.takeIf { it.enabled }
                ?.let { return it.effectiveLimits() }
        }
        val sourced = limitSource?.limitsFor(key.providerId, key.modelId.orEmpty(), key.accountId)
        if (sourced != null && !sourced.isEmpty) return sourced
        return RateLimitLimits.NONE
    }

    private fun bucketFor(key: RateLimitKey): Bucket = buckets.getOrPut(key) { Bucket() }

    private fun clampToProviderCeiling(profile: RateLimitProfile): RateLimitProfile {
        val ceiling = profileMap[RateLimitKey(profile.providerId, null, profile.accountId)] ?: return profile
        if (ceiling.source != RateLimitSource.PROVIDER_REPORTED) return profile
        return profile.clamp(ceiling)
    }

    private suspend fun persistProfiles() {
        val snapshot = profileMap.values.toList()
        runCatching { profileStore?.save(snapshot) }
    }

    private class Bucket {
        var minuteWindowStart = Long.MIN_VALUE
        var minuteRequests = 0
        var minuteTokens = 0L
        var dayWindowStart = Long.MIN_VALUE
        var dayRequests = 0
        var dayTokens = 0L
        var inFlight = 0
        var blockedUntilMillis = 0L
        var blockedKind: RateLimitKind = RateLimitKind.UNKNOWN

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
                dayTokens = 0L
            }
            if (blockedUntilMillis > 0L && blockedUntilMillis <= now) {
                blockedUntilMillis = 0L
                blockedKind = RateLimitKind.UNKNOWN
            }
        }

        val minuteWindowEnd: Long get() = minuteWindowStart + MINUTE_MILLIS

        val dayWindowEnd: Long get() = dayWindowStart + DAY_MILLIS
    }

    companion object {
        const val MINUTE_MILLIS: Long = 60_000L
        const val DAY_MILLIS: Long = 24L * 60L * 60L * 1_000L
    }
}

internal fun actualTotalTokens(actualInputTokens: Int?, actualOutputTokens: Int?): Long? = when {
    actualInputTokens == null && actualOutputTokens == null -> null
    else -> (actualInputTokens ?: 0).toLong() + (actualOutputTokens ?: 0).toLong()
}

suspend fun RateLimitManager.complete(reservation: RateLimitReservation, usage: ModelUsage?) {
    val input = usage?.promptTokens
    val output = usage?.completionTokens
    if (input != null || output != null) {
        reconcile(reservation, input, output, success = true)
        return
    }
    val total = usage?.totalTokens
    if (total != null) {
        reconcile(reservation, total, null, success = true)
        return
    }
    reconcile(reservation, null, null, success = true)
}

suspend fun RateLimitManager.abandon(reservation: RateLimitReservation) {
    reconcile(reservation, null, null, success = false)
}

suspend fun RateLimitManager.noteRateLimited(
    request: RateLimitRequest,
    retryAfterMs: Long? = null,
    kind: RateLimitKind = RateLimitKind.UNKNOWN,
) {
    recordRateLimited(request.providerId, request.modelId, retryAfterMs, kind)
}

private fun Long.mod(divisor: Long): Long = ((this % divisor) + divisor) % divisor
