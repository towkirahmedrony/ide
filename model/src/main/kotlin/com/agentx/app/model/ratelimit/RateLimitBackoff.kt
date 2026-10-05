package com.agentx.app.model.ratelimit

import kotlin.math.min

/**
 * The project's single bounded backoff schedule: increasing delay, a hard ceiling,
 * and jitter so callers that failed together do not retry in lockstep.
 *
 * It owns the *timing* of a retry and nothing else — never whether a retry is
 * allowed, which category a failure belongs to, or which model is asked next. Those
 * are decided by [com.agentx.app.model.retry.TransientRetryPolicy] (transient
 * failures) and by the configured fallback policy (a different model).
 *
 * There is exactly one schedule per retry site and providers never retry by
 * themselves, so retries cannot multiply into an uncontrolled loop. Every attempt a
 * caller starts goes back through [RateLimitManager] admission control.
 */
class RateLimitBackoff(
    /** Total attempts including the first. `4` means the first call plus three retries. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    private val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    /** Fraction of the exponential delay added as jitter, `0.0..1.0`. */
    private val jitterRatio: Double = DEFAULT_JITTER_RATIO,
    private val random: () -> Double = { Math.random() },
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(baseDelayMillis > 0) { "baseDelayMillis must be positive" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must be >= baseDelayMillis" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be between 0 and 1" }
    }

    /** Whether another attempt is allowed after [attempts] attempts have been made. */
    fun canRetry(attempts: Int): Boolean = attempts < maxAttempts

    /**
     * Delay before the next attempt. [attempt] is 1-based (the delay before the
     * first retry is `attempt = 1`).
     *
     * A provider-supplied [retryAfterMillis] is honored, capped at
     * [maxDelayMillis] so a provider cannot make the app sleep unboundedly.
     * Otherwise the delay grows exponentially with additive jitter.
     */
    fun delayMillis(attempt: Int, retryAfterMillis: Long? = null): Long {
        if (retryAfterMillis != null && retryAfterMillis > 0L) {
            return min(retryAfterMillis, maxDelayMillis)
        }
        val exponent = (attempt - 1).coerceIn(0, MAX_SHIFT)
        val exponential = min(maxDelayMillis, baseDelayMillis shl exponent)
        val jitter = (exponential.toDouble() * jitterRatio * random()).toLong().coerceAtLeast(0L)
        return min(maxDelayMillis, exponential + jitter)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 4
        const val DEFAULT_BASE_DELAY_MILLIS: Long = 500L
        const val DEFAULT_MAX_DELAY_MILLIS: Long = 30_000L
        const val DEFAULT_JITTER_RATIO: Double = 0.25
        private const val MAX_SHIFT = 20
    }
}
