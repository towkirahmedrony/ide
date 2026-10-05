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

    /**
     * The structured, read-only quota picture for a prospective request.
     *
     * This exists so candidate selection and observability can ask "may this
     * provider/model take this request, and why not?" without reserving anything
     * and without a second decision path: the answer comes from the same
     * [canRequest] the gateway enforces, so a preflight decision and the
     * enforcement can never disagree.
     *
     * The default builds a decision-only view, which is enough for a manager that
     * has no per-dimension accounting to expose.
     */
    suspend fun headroom(
        providerId: String,
        modelId: String,
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int,
        accountId: String? = null,
        local: Boolean = false,
    ): RateLimitHeadroom = RateLimitHeadroom(
        providerId = providerId,
        modelId = modelId,
        accountId = accountId,
        local = local,
        estimatedRequestTokens = estimatedInputTokens.toLong() + estimatedOutputTokens.toLong(),
        decision = canRequest(providerId, modelId, estimatedInputTokens, estimatedOutputTokens, accountId, local),
    )

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

    /**
     * Records a provider 429 so the same scope is not asked again before the
     * provider's own wait has passed.
     *
     * [accountId] must be the same discriminator the blocked request was admitted
     * under, so a cooldown lands on the connection that was actually refused and
     * never on an unrelated connection of the same provider family.
     */
    suspend fun recordRateLimited(
        providerId: String,
        modelId: String,
        retryAfterMs: Long?,
        kind: RateLimitKind = RateLimitKind.UNKNOWN,
        accountId: String? = null,
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

    /**
     * The persisted profiles right now, when the backing store can produce them
     * without suspending.
     *
     * Admission control is consulted on the request path, which cannot wait for a
     * suspending load before the very first request after a restart. A store backed
     * by something already in memory (a preferences file, a snapshot) returns them
     * here so a configured quota is in force from the first call; a store that
     * cannot returns none, and [RateLimitManager.restore] remains the async path.
     */
    fun snapshot(): List<RateLimitProfile> = emptyList()
}

/** Store used by previews, tests and the platform default. */
class InMemoryRateLimitProfileStore(initial: List<RateLimitProfile> = emptyList()) : RateLimitProfileStore {
    private val values = LinkedHashMap<RateLimitKey, RateLimitProfile>()

    init {
        initial.forEach { values[it.key] = it }
    }

    override suspend fun load(): List<RateLimitProfile> = values.values.toList()

    override fun snapshot(): List<RateLimitProfile> = values.values.toList()

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

    init {
        // Quotas configured in a previous run are in force from the first request,
        // not only after a suspending restore completes: profileMap is what
        // admission control reads, so it must not start empty when the store
        // already knows the limits. A store that can only load asynchronously
        // returns nothing here and restore() remains the path that fills it in.
        profileStore?.snapshot()?.takeIf { it.isNotEmpty() }?.let { stored ->
            profileMap = stored.associateBy { it.key }
        }
    }

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

    override suspend fun headroom(
        providerId: String,
        modelId: String,
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int,
        accountId: String?,
        local: Boolean,
    ): RateLimitHeadroom {
        val request = packed(providerId, modelId, estimatedInputTokens, estimatedOutputTokens, accountId, local)
        val decision = canRequest(
            providerId = providerId,
            modelId = modelId,
            estimatedInputTokens = estimatedInputTokens,
            estimatedOutputTokens = estimatedOutputTokens,
            accountId = accountId,
            local = local,
        )
        // A local endpoint does not consume remote quota, so there is no remote
        // headroom to report and no dimension to be near a limit on.
        if (!request.rateLimited) {
            return RateLimitHeadroom(
                providerId = providerId,
                modelId = modelId,
                accountId = accountId,
                decision = decision,
                local = true,
                estimatedRequestTokens = request.reservedTokens,
            )
        }

        val totals = usage.totals(providerId, modelId).firstOrNull()
        val dimensions = plan(request).flatMap { planned ->
            listOf(
                dimensionState(planned.key, QuotaDimension.REQUESTS_PER_MINUTE, planned.limits.requestsPerMinute, totals?.requestCount),
                dimensionState(planned.key, QuotaDimension.REQUESTS_PER_HOUR, planned.limits.requestsPerHour, totals?.requestCount),
                dimensionState(planned.key, QuotaDimension.REQUESTS_PER_DAY, planned.limits.requestsPerDay, totals?.requestCount),
                dimensionState(planned.key, QuotaDimension.TOKENS_PER_MINUTE, planned.limits.tokensPerMinute, totals?.totalTokens),
                dimensionState(planned.key, QuotaDimension.INPUT_TOKENS_PER_MINUTE, planned.limits.inputTokensPerMinute, totals?.inputTokens),
                dimensionState(planned.key, QuotaDimension.OUTPUT_TOKENS_PER_MINUTE, planned.limits.outputTokensPerMinute, totals?.outputTokens),
                dimensionState(planned.key, QuotaDimension.TOKENS_PER_DAY, planned.limits.tokensPerDay, totals?.totalTokens),
                dimensionState(planned.key, QuotaDimension.CONCURRENT_REQUESTS, planned.limits.maxConcurrentRequests, null),
            ).filter { it.limit != null }
        }

        return RateLimitHeadroom(
            providerId = providerId,
            modelId = modelId,
            accountId = accountId,
            decision = decision,
            local = false,
            estimatedRequestTokens = request.reservedTokens,
            dimensions = dimensions,
        )
    }

    /**
     * One dimension's known state.
     *
     * `remaining` is only reported when the ceiling itself is known: a number that
     * was never observed must not be shown as if the provider had confirmed it, and
     * an unknown limit stays UNKNOWN rather than becoming a guessed allowance.
     */
    private fun dimensionState(
        key: RateLimitKey,
        dimension: QuotaDimension,
        quota: Quota,
        used: Long?,
    ): QuotaDimensionState {
        val limit = quota.knownValue
        val profile = profileMap[key]
        return QuotaDimensionState(
            dimension = dimension,
            limit = limit,
            used = used,
            remaining = limit?.let { ceiling -> used?.let { consumed -> (ceiling - consumed).coerceAtLeast(0L) } },
            safetyMargin = profile?.safetyMargin,
            knowledge = when {
                limit == null -> QuotaKnowledge.UNKNOWN
                used == null -> QuotaKnowledge.KNOWN
                else -> QuotaKnowledge.ESTIMATED
            },
            source = profile?.source,
        )
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
        synchronized(lock) { applyReconcile(reservation, actualInputTokens, actualOutputTokens, actualTotal, success) }
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
        accountId: String?,
    ) {
        val now = clock.nowMillis()
        tracker.recordRateLimit(providerId, modelId, now)
        synchronized(lock) {
            // The cooldown belongs to the scope that was refused. Recording it on
            // the provider family alone would let one refused connection stop an
            // unrelated connection of the same family, which shares no quota.
            val key = RateLimitKey(providerId, modelId, accountId?.takeIf { it.isNotBlank() })
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
        val reservedInput = request.estimatedInputTokens.toLong()
        val reservedOutput = request.estimatedOutputTokens.toLong()
        val entries = planned.map { limit ->
            val bucket = bucketFor(limit.key)
            bucket.refresh(now)
            bucket.minuteRequests += 1
            bucket.hourRequests += 1
            bucket.dayRequests += 1
            bucket.minuteTokens += reservedTokens
            bucket.dayTokens += reservedTokens
            bucket.minuteInputTokens += reservedInput
            bucket.minuteOutputTokens += reservedOutput
            bucket.inFlight += 1
            RateLimitReservation.Entry(
                key = limit.key,
                limits = limit.limits,
                minuteWindowStart = bucket.minuteWindowStart,
                hourWindowStart = bucket.hourWindowStart,
                dayWindowStart = bucket.dayWindowStart,
            )
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
        limits.requestsPerHour.knownValue?.let { rph ->
            if (rph <= 0L) {
                return blocked(request, RateLimitKind.REQUEST, bucket.hourWindowEnd - now, "no requests per hour are permitted")
            }
            if (bucket.hourRequests + 1 > rph) {
                return blocked(request, RateLimitKind.REQUEST, bucket.hourWindowEnd - now, "requests per hour exhausted")
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
        // A provider that publishes the prompt and completion sides separately is
        // enforced on each side, never on their sum: one side being over its own
        // ceiling must not be excused by the other side being under.
        val reservedInput = request.estimatedInputTokens.toLong()
        val reservedOutput = request.estimatedOutputTokens.toLong()
        limits.inputTokensPerMinute.knownValue?.let { ipm ->
            if (ipm <= 0L) {
                return blocked(request, RateLimitKind.TOKENS, bucket.minuteWindowEnd - now, "no input tokens per minute are permitted")
            }
            if (reservedInput > ipm) {
                return blocked(
                    request,
                    RateLimitKind.TOKENS,
                    null,
                    "a single request needs $reservedInput input tokens but only $ipm are allowed per minute",
                )
            }
            if (bucket.minuteInputTokens + reservedInput > ipm) {
                return blocked(request, RateLimitKind.TOKENS, bucket.minuteWindowEnd - now, "input tokens per minute exhausted")
            }
        }
        limits.outputTokensPerMinute.knownValue?.let { opm ->
            if (opm <= 0L) {
                return blocked(request, RateLimitKind.TOKENS, bucket.minuteWindowEnd - now, "no output tokens per minute are permitted")
            }
            if (reservedOutput > opm) {
                return blocked(
                    request,
                    RateLimitKind.TOKENS,
                    null,
                    "a single request needs $reservedOutput output tokens but only $opm are allowed per minute",
                )
            }
            if (bucket.minuteOutputTokens + reservedOutput > opm) {
                return blocked(request, RateLimitKind.TOKENS, bucket.minuteWindowEnd - now, "output tokens per minute exhausted")
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

    private fun applyReconcile(
        reservation: RateLimitReservation,
        actualInputTokens: Int?,
        actualOutputTokens: Int?,
        actualTotalTokens: Long?,
        success: Boolean,
    ) {
        val reservedInput = reservation.estimatedInputTokens.toLong()
        val reservedOutput = reservation.estimatedOutputTokens.toLong()
        reservation.entries.forEach { entry ->
            val bucket = bucketFor(entry.key)
            // In-flight is always released, whatever the outcome: a request that
            // came back, failed or was cancelled is no longer occupying a slot, and
            // holding it would permanently consume a concurrency allowance.
            if (bucket.inFlight > 0) bucket.inFlight -= 1
            val sameMinute = bucket.minuteWindowStart == entry.minuteWindowStart
            val sameHour = bucket.hourWindowStart == entry.hourWindowStart
            val sameDay = bucket.dayWindowStart == entry.dayWindowStart
            if (!success) {
                // Nothing was consumed: every reservation taken for this attempt is
                // given back, in the window it was actually taken in.
                if (sameMinute) {
                    bucket.minuteTokens = maxOf(0L, bucket.minuteTokens - reservation.reservedTokens)
                    bucket.minuteInputTokens = maxOf(0L, bucket.minuteInputTokens - reservedInput)
                    bucket.minuteOutputTokens = maxOf(0L, bucket.minuteOutputTokens - reservedOutput)
                    if (bucket.minuteRequests > 0) bucket.minuteRequests -= 1
                }
                if (sameHour) {
                    if (bucket.hourRequests > 0) bucket.hourRequests -= 1
                }
                if (sameDay) {
                    bucket.dayTokens = maxOf(0L, bucket.dayTokens - reservation.reservedTokens)
                    if (bucket.dayRequests > 0) bucket.dayRequests -= 1
                }
                return@forEach
            }
            // The request succeeded: the estimate has already been counted, so the
            // reservation is replaced by what the provider actually reported — but
            // only where the provider reported it. A response without usage keeps
            // the conservative estimate rather than being credited as free.
            if (actualTotalTokens != null && sameMinute) {
                bucket.minuteTokens = maxOf(0L, bucket.minuteTokens - reservation.reservedTokens)
                bucket.minuteTokens += actualTotalTokens
            }
            if (actualTotalTokens != null && sameDay) {
                bucket.dayTokens = maxOf(0L, bucket.dayTokens - reservation.reservedTokens)
                bucket.dayTokens += actualTotalTokens
            }
            if (sameMinute && (actualInputTokens != null || actualOutputTokens != null)) {
                if (actualInputTokens != null) {
                    bucket.minuteInputTokens = maxOf(0L, bucket.minuteInputTokens - reservedInput)
                    bucket.minuteInputTokens += actualInputTokens.toLong()
                }
                if (actualOutputTokens != null) {
                    bucket.minuteOutputTokens = maxOf(0L, bucket.minuteOutputTokens - reservedOutput)
                    bucket.minuteOutputTokens += actualOutputTokens.toLong()
                }
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
                // Shortest configured window first: waiting out a minute is always
                // enough to clear a minute bucket, and a longer window is only used
                // when it is the only one the provider stated.
                limits.requestsPerMinute is Quota.Known -> bucket.minuteWindowEnd
                limits.requestsPerHour is Quota.Known -> bucket.hourWindowEnd
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
        var minuteInputTokens = 0L
        var minuteOutputTokens = 0L
        var hourWindowStart = Long.MIN_VALUE
        var hourRequests = 0
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
                minuteInputTokens = 0L
                minuteOutputTokens = 0L
            }
            val hourStart = now - now.mod(HOUR_MILLIS)
            if (hourStart != hourWindowStart) {
                hourWindowStart = hourStart
                hourRequests = 0
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

        val hourWindowEnd: Long get() = hourWindowStart + HOUR_MILLIS

        val dayWindowEnd: Long get() = dayWindowStart + DAY_MILLIS
    }

    companion object {
        const val MINUTE_MILLIS: Long = 60_000L
        const val HOUR_MILLIS: Long = 60L * 60L * 1_000L
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
    // The request's own scope travels with the record, so the cooldown lands where
    // the request was admitted rather than on every connection of the family.
    recordRateLimited(request.providerId, request.modelId, retryAfterMs, kind, request.accountId)
}

private fun Long.mod(divisor: Long): Long = ((this % divisor) + divisor) % divisor
