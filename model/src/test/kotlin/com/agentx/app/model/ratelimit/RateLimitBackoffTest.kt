package com.agentx.app.model.ratelimit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimitBackoffTest {

    @Test
    fun `retries are bounded`() {
        val backoff = RateLimitBackoff(maxAttempts = 3, random = { 0.0 })

        assertTrue(backoff.canRetry(0))
        assertTrue(backoff.canRetry(2))
        assertFalse(backoff.canRetry(3))
    }

    @Test
    fun `delay grows exponentially and is capped`() {
        val backoff = RateLimitBackoff(
            baseDelayMillis = 500L,
            maxDelayMillis = 5_000L,
            jitterRatio = 0.0,
            random = { 0.0 },
        )

        assertEquals(500L, backoff.delayMillis(1))
        assertEquals(1_000L, backoff.delayMillis(2))
        assertEquals(2_000L, backoff.delayMillis(3))
        assertEquals(4_000L, backoff.delayMillis(4))
        assertEquals(5_000L, backoff.delayMillis(5))
    }

    @Test
    fun `jitter only adds delay within the configured ratio`() {
        val backoff = RateLimitBackoff(
            baseDelayMillis = 1_000L,
            maxDelayMillis = 10_000L,
            jitterRatio = 0.5,
            random = { 1.0 },
        )

        assertEquals(1_500L, backoff.delayMillis(1))
    }

    @Test
    fun `retry-after is respected`() {
        val backoff = RateLimitBackoff(
            baseDelayMillis = 500L,
            maxDelayMillis = 30_000L,
            random = { 0.0 },
        )

        assertEquals(7_000L, backoff.delayMillis(1, retryAfterMillis = 7_000L))
    }

    @Test
    fun `retry-after never makes the app sleep past the cap`() {
        val backoff = RateLimitBackoff(maxDelayMillis = 10_000L)

        assertEquals(10_000L, backoff.delayMillis(1, retryAfterMillis = 5_000_000L))
    }

    @Test
    fun `a non-positive retry-after falls back to backoff`() {
        val backoff = RateLimitBackoff(baseDelayMillis = 500L, jitterRatio = 0.0, random = { 0.0 })

        assertEquals(500L, backoff.delayMillis(1, retryAfterMillis = 0L))
    }
}
