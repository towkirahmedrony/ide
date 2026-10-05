package com.agentx.app.model.retry

import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ratelimit.RateLimitBackoff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Retry is only safe if it is strictly bounded and its timing is bounded too, so both
 * the verdict and the schedule are pinned here.
 */
class TransientRetryPolicyTest {

    private fun error(
        code: ModelProviderErrorCode,
        status: Int? = null,
        retryAfterMillis: Long? = null,
    ) = ModelProviderError(
        code = code,
        message = "test",
        httpStatus = status,
        retryAfterMillis = retryAfterMillis,
    )

    // --- verdicts -----------------------------------------------------------

    @Test
    fun `a transient failure within the budget is retried on the same model`() {
        val policy = TransientRetryPolicy(maximumAttempts = 3)

        assertEquals(
            RetryVerdict.RETRY_SAME_MODEL,
            policy.verdict(error(ModelProviderErrorCode.TIMEOUT), attempt = 1, outputEmitted = false),
        )
        assertEquals(
            RetryVerdict.RETRY_SAME_MODEL,
            policy.verdict(error(ModelProviderErrorCode.SERVICE_UNAVAILABLE, 503), attempt = 2, outputEmitted = false),
        )
    }

    @Test
    fun `the attempt budget is a hard bound`() {
        val policy = TransientRetryPolicy(maximumAttempts = 3)
        val timeout = error(ModelProviderErrorCode.TIMEOUT)

        assertEquals(RetryVerdict.RETRY_SAME_MODEL, policy.verdict(timeout, attempt = 1, outputEmitted = false))
        assertEquals(RetryVerdict.RETRY_SAME_MODEL, policy.verdict(timeout, attempt = 2, outputEmitted = false))
        assertEquals(RetryVerdict.ATTEMPTS_EXHAUSTED, policy.verdict(timeout, attempt = 3, outputEmitted = false))
        // And it stays exhausted: no attempt number can talk it back into retrying.
        assertEquals(RetryVerdict.ATTEMPTS_EXHAUSTED, policy.verdict(timeout, attempt = 99, outputEmitted = false))
    }

    @Test
    fun `a permanent failure is never retried`() {
        val policy = TransientRetryPolicy(maximumAttempts = 4)
        listOf(
            error(ModelProviderErrorCode.AUTHENTICATION_FAILED, 401),
            error(ModelProviderErrorCode.AUTHORIZATION_FAILED, 403),
            error(ModelProviderErrorCode.PERMISSION_DENIED, 403),
            error(ModelProviderErrorCode.INVALID_REQUEST, 400),
            error(ModelProviderErrorCode.MODEL_NOT_FOUND, 404),
            error(ModelProviderErrorCode.RATE_LIMITED, 429),
            error(ModelProviderErrorCode.QUOTA_EXHAUSTED, 429),
            error(ModelProviderErrorCode.CANCELLED),
        ).forEach { failure ->
            assertEquals(
                RetryVerdict.NOT_RETRYABLE,
                policy.verdict(failure, attempt = 1, outputEmitted = false),
                "${failure.code} must not be retried",
            )
        }
    }

    @Test
    fun `an attempt that already emitted output is never restarted`() {
        val policy = TransientRetryPolicy(maximumAttempts = 4)
        assertEquals(
            RetryVerdict.OUTPUT_ALREADY_EMITTED,
            policy.verdict(error(ModelProviderErrorCode.TIMEOUT), attempt = 1, outputEmitted = true),
        )
    }

    @Test
    fun `a permanent failure is reported as permanent even after partial output`() {
        // The more specific fact wins: an operator reading this needs to know the key
        // was rejected, not merely that the stream had started.
        assertEquals(
            RetryVerdict.NOT_RETRYABLE,
            TransientRetryPolicy(maximumAttempts = 4)
                .verdict(error(ModelProviderErrorCode.AUTHENTICATION_FAILED, 401), attempt = 1, outputEmitted = true),
        )
    }

    @Test
    fun `the policy must allow at least one attempt`() {
        val failure = runCatching { TransientRetryPolicy(maximumAttempts = 0) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "maximumAttempts=0 must be rejected")
    }

    // --- backoff schedule ---------------------------------------------------

    @Test
    fun `the delay grows with each attempt`() {
        val policy = TransientRetryPolicy(
            maximumAttempts = 5,
            backoff = RateLimitBackoff(baseDelayMillis = 100L, maxDelayMillis = 10_000L, jitterRatio = 0.0, random = { 0.0 }),
        )

        val delays = (1..4).map { policy.delayMillis(it) }
        assertEquals(delays.sorted(), delays, "the wait must not shrink: $delays")
        assertTrue(delays.last() > delays.first(), "the wait must grow: $delays")
    }

    @Test
    fun `the delay is capped`() {
        val policy = TransientRetryPolicy(
            maximumAttempts = 10,
            backoff = RateLimitBackoff(baseDelayMillis = 1_000L, maxDelayMillis = 4_000L, jitterRatio = 0.0, random = { 0.0 }),
        )

        assertTrue(policy.delayMillis(9) <= 4_000L, "the wait must stay under the cap")
    }

    @Test
    fun `jitter spreads the schedule without exceeding the cap`() {
        val deterministic = TransientRetryPolicy(
            maximumAttempts = 4,
            backoff = RateLimitBackoff(baseDelayMillis = 1_000L, maxDelayMillis = 8_000L, jitterRatio = 0.0, random = { 0.0 }),
        )
        val jittered = TransientRetryPolicy(
            maximumAttempts = 4,
            backoff = RateLimitBackoff(baseDelayMillis = 1_000L, maxDelayMillis = 8_000L, jitterRatio = 0.25, random = { 1.0 }),
        )

        assertTrue(jittered.delayMillis(1) > deterministic.delayMillis(1), "jitter must vary the wait")
        assertTrue(jittered.delayMillis(1) <= 8_000L, "jitter must not exceed the cap")
    }

    @Test
    fun `a provider supplied retry-after is honoured within safe bounds`() {
        val policy = TransientRetryPolicy(
            maximumAttempts = 4,
            backoff = RateLimitBackoff(baseDelayMillis = 100L, maxDelayMillis = 5_000L, jitterRatio = 0.25),
        )

        assertEquals(2_000L, policy.delayMillis(1, retryAfterMillis = 2_000L))
        // A provider cannot make the app wait unboundedly.
        assertEquals(5_000L, policy.delayMillis(1, retryAfterMillis = 600_000L))
        // A nonsense value is ignored, not obeyed.
        assertTrue(policy.delayMillis(1, retryAfterMillis = 0L) < 5_000L)
    }
}
