package com.agentx.app.model.ratelimit

import kotlin.math.floor
import kotlin.math.max

/**
 * Where a limit value came from.
 *
 * The UI renders these distinctly so a limit is never presented as authoritative
 * when it is not: provider-reported metadata, an app-configured value, or an
 * unknown limit that falls back to a safe default.
 */
enum class RateLimitSource {
    /** The provider published this limit (for example in a model catalog). */
    PROVIDER_REPORTED,

    /** The user configured this limit in AgentX. */
    APP_CONFIGURED,

    /** No authoritative metadata exists; the value is a safe placeholder. */
    UNKNOWN,
}

/** The level a [RateLimitProfile] applies to. */
enum class RateLimitScope {
    /** Applies to every request through a provider/account. */
    PROVIDER,

    /** Applies to one model within a provider. */
    MODEL,
}

/**
 * Identity of one rate-limit bucket. A model-scoped profile and a provider-scoped
 * profile are distinct buckets; both are enforced for a model-scoped request.
 */
data class RateLimitKey(
    val providerId: String,
    /** null/blank for a provider/account-level bucket. */
    val modelId: String? = null,
    /** Optional account discriminator for multi-account setups. */
    val accountId: String? = null,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
    }
}

/**
 * A configured admission limit for a provider or one of its models.
 *
 * Every numeric limit is optional on purpose: not every provider exposes all of
 * them, and a `null` limit means "unknown", never "unlimited". The manager
 * decides how to treat unknown limits (see
 * [DefaultRateLimitManager.unknownRemoteLimits]) instead of silently allowing
 * an unlimited number of requests.
 *
 * The app may always configure a value *lower* than the provider's. It may never
 * configure one higher when authoritative metadata exists — see [clamp].
 */
data class RateLimitProfile(
    val providerId: String,
    /** null/blank means the profile applies to the whole provider/account. */
    val modelId: String? = null,
    val accountId: String? = null,
    /** Requests allowed per rolling minute window. */
    val requestsPerMinute: Int? = null,
    /** Tokens (input + output) allowed per rolling minute window. */
    val tokensPerMinute: Long? = null,
    /** Requests allowed per rolling day window. */
    val requestsPerDay: Int? = null,
    /** Maximum requests in flight at once. */
    val maxConcurrentRequests: Int? = null,
    /** When false the scope is explicitly unlimited and no default is applied. */
    val enabled: Boolean = true,
    /**
     * Fraction of each limit held back as headroom, `0.0..0.5`. A provider's
     * published limit is a ceiling the app must not hit, so a small margin keeps
     * AgentX below it while still using the quota honestly.
     */
    val safetyMargin: Double = 0.0,
    val source: RateLimitSource = RateLimitSource.UNKNOWN,
    val updatedAtMillis: Long = 0L,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(safetyMargin in 0.0..MAX_SAFETY_MARGIN) {
            "safetyMargin must be between 0 and $MAX_SAFETY_MARGIN"
        }
        requirePositiveOrNull("requestsPerMinute", requestsPerMinute)
        requirePositiveOrNull("tokensPerMinute", tokensPerMinute)
        requirePositiveOrNull("requestsPerDay", requestsPerDay)
        requirePositiveOrNull("maxConcurrentRequests", maxConcurrentRequests)
    }

    val scope: RateLimitScope
        get() = if (modelId.isNullOrBlank()) RateLimitScope.PROVIDER else RateLimitScope.MODEL

    val key: RateLimitKey
        get() = RateLimitKey(
            providerId = providerId,
            modelId = modelId?.trim()?.takeIf { it.isNotBlank() },
            accountId = accountId?.trim()?.takeIf { it.isNotBlank() },
        )

    /** True when at least one numeric limit is known. */
    val hasKnownLimit: Boolean
        get() = requestsPerMinute != null ||
            tokensPerMinute != null ||
            requestsPerDay != null ||
            maxConcurrentRequests != null

    /** The limits after the configured safety margin is applied. */
    fun effectiveLimits(): RateLimitLimits = RateLimitLimits(
        requestsPerMinute = requestsPerMinute?.scaleInt(),
        tokensPerMinute = tokensPerMinute?.scaleLong(),
        requestsPerDay = requestsPerDay?.scaleInt(),
        maxConcurrentRequests = maxConcurrentRequests,
    )

    /**
     * Returns this profile with each limit reduced so it never exceeds the same
     * limit in [ceiling]. [ceiling] is the authoritative provider metadata; when
     * it is null nothing is changed. Provenance is preserved so the UI can still
     * tell an app-configured value from a provider-reported one.
     */
    fun clamp(ceiling: RateLimitProfile?): RateLimitProfile {
        if (ceiling == null) return this
        fun minInt(configured: Int?, cap: Int?): Int? = when {
            configured == null -> null
            cap == null -> configured
            else -> minOf(configured, cap)
        }
        fun minLong(configured: Long?, cap: Long?): Long? = when {
            configured == null -> null
            cap == null -> configured
            else -> minOf(configured, cap)
        }
        // Provenance is preserved so the UI can still tell an app-configured
        // value from provider-reported metadata.
        return copy(
            requestsPerMinute = minInt(requestsPerMinute, ceiling.requestsPerMinute),
            tokensPerMinute = minLong(tokensPerMinute, ceiling.tokensPerMinute),
            requestsPerDay = minInt(requestsPerDay, ceiling.requestsPerDay),
            maxConcurrentRequests = minInt(maxConcurrentRequests, ceiling.maxConcurrentRequests),
        )
    }

    /** A limit of 0 stays 0 ("none allowed"); a positive limit keeps at least 1 after the margin. */
    private fun Int.scaleInt(): Int =
        if (this <= 0) 0 else max(1, floor(this * (1.0 - safetyMargin)).toInt())

    private fun Long.scaleLong(): Long =
        if (this <= 0L) 0L else max(1L, floor(this * (1.0 - safetyMargin)).toLong())

    companion object {
        const val MAX_SAFETY_MARGIN: Double = 0.5
    }
}

/** Numeric limits already reduced by a profile's safety margin. */
data class RateLimitLimits(
    val requestsPerMinute: Int? = null,
    val tokensPerMinute: Long? = null,
    val requestsPerDay: Int? = null,
    val maxConcurrentRequests: Int? = null,
) {
    val isEmpty: Boolean
        get() = requestsPerMinute == null &&
            tokensPerMinute == null &&
            requestsPerDay == null &&
            maxConcurrentRequests == null

    companion object {
        val NONE: RateLimitLimits = RateLimitLimits()
    }
}

private fun requirePositiveOrNull(name: String, value: Int?) {
    require(value == null || value >= 0) { "$name must not be negative" }
}

private fun requirePositiveOrNull(name: String, value: Long?) {
    require(value == null || value >= 0) { "$name must not be negative" }
}
