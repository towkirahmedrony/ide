package com.agentx.app.model.ratelimit

import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode

/** Which quota dimension blocked a request. */
enum class RateLimitKind {
    REQUEST,
    TOKENS,
    UNKNOWN,
}

/**
 * Result of [RateLimitManager.canRequest]. Unknown quotas never become a
 * guessed block; only a [Quota.Known] limit or a recorded 429 can block.
 */
sealed class RateLimitDecision {
    data object Allowed : RateLimitDecision()

    data class Blocked(
        val providerId: String,
        val modelId: String,
        val retryAfterMs: Long? = null,
        val kind: RateLimitKind = RateLimitKind.UNKNOWN,
        val reason: String,
    ) : RateLimitDecision() {
        fun toError(): ModelProviderError = ModelProviderError(
            code = ModelProviderErrorCode.RATE_LIMITED,
            message = "Rate limit for $providerId/$modelId: $reason",
            providerId = providerId,
            httpStatus = 429,
            retryable = false,
            retryAfterMillis = retryAfterMs,
            details = mapOf(
                "modelId" to modelId,
                "scope" to kind.name,
                "retryAfterMs" to retryAfterMs,
            ),
        )
    }
}

/**
 * A reservation returned by [RateLimitManager.reserve]. Exactly one
 * [RateLimitManager.reconcile] call must close it.
 */
class RateLimitReservation internal constructor(
    val providerId: String,
    val modelId: String,
    val accountId: String? = null,
    val estimatedInputTokens: Int,
    val estimatedOutputTokens: Int,
    val reservedTokens: Long,
    val admittedAtMillis: Long,
    val bypassed: Boolean,
    internal val entries: List<Entry>,
) {
    internal data class Entry(
        val key: RateLimitKey,
        val limits: RateLimitLimits,
        val minuteWindowStart: Long,
        val dayWindowStart: Long,
    )

    private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)

    val closed: Boolean get() = claimed.get()

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    companion object {
        internal fun bypassed(
            providerId: String,
            modelId: String,
            accountId: String?,
            estimatedInputTokens: Int,
            estimatedOutputTokens: Int,
            nowMillis: Long,
        ): RateLimitReservation = RateLimitReservation(
            providerId = providerId,
            modelId = modelId,
            accountId = accountId,
            estimatedInputTokens = estimatedInputTokens,
            estimatedOutputTokens = estimatedOutputTokens,
            reservedTokens = 0L,
            admittedAtMillis = nowMillis,
            bypassed = true,
            entries = emptyList(),
        )
    }
}

/** Maps a provider 429 onto [RateLimitKind] from response metadata when possible. */
fun rateLimitKindOf(message: String?, providerErrorType: String?): RateLimitKind {
    val haystack = listOfNotNull(message, providerErrorType).joinToString(" ").lowercase()
    if (haystack.isBlank()) return RateLimitKind.UNKNOWN
    val tokenHit = TOKEN_HINTS.any { haystack.contains(it) }
    val requestHit = REQUEST_HINTS.any { haystack.contains(it) }
    return when {
        tokenHit && !requestHit -> RateLimitKind.TOKENS
        requestHit && !tokenHit -> RateLimitKind.REQUEST
        else -> RateLimitKind.UNKNOWN
    }
}

private val TOKEN_HINTS = listOf("token", "tpm", "tokens per minute", "tokens per day")
private val REQUEST_HINTS = listOf("request", "rpm", "requests per minute", "requests per day")
