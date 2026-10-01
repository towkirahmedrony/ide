package com.agentx.app.model.ratelimit

import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelUsage
import com.agentx.app.model.runSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RateLimitManagerTest {

    private fun manager(
        clock: RateLimitClock = FakeRateLimitClock(),
        tracker: UsageTracker = DefaultUsageTracker(),
        maxWaitMillis: Long = 60_000L,
    ) = DefaultRateLimitManager(
        clock = clock,
        tracker = tracker,
        unknownRemoteLimits = null,
        maxWaitMillis = maxWaitMillis,
    )

    private fun profile(
        providerId: String = "groq",
        modelId: String? = null,
        rpm: Int? = null,
        tpm: Long? = null,
        rpd: Int? = null,
        concurrent: Int? = null,
    ) = RateLimitProfile(
        providerId = providerId,
        modelId = modelId,
        requestsPerMinute = rpm,
        tokensPerMinute = tpm,
        requestsPerDay = rpd,
        maxConcurrentRequests = concurrent,
        source = RateLimitSource.APP_CONFIGURED,
    )

    // --- rate limits -------------------------------------------------------

    @Test
    fun `requests per minute admits up to the limit then waits for the next window`() = runSuspend {
        val clock = FakeRateLimitClock()
        val manager = manager(clock)
        manager.updateProfile(profile(rpm = 2))

        manager.complete(manager.acquire(request()), null)
        manager.complete(manager.acquire(request()), null)
        assertEquals(emptyList(), clock.sleeps)

        // The third request must wait for the next minute window rather than
        // being admitted immediately.
        manager.complete(manager.acquire(request()), null)
        assertEquals(listOf(60_000L), clock.sleeps)
    }

    @Test
    fun `a zero limit denies admission with a clear rate-limit error`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(rpm = 0))

        val error = assertFailsWith<ModelProviderError> { manager.acquire(request()) }
        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertTrue(error.message!!.contains("no requests per minute"))
    }

    @Test
    fun `tokens per minute reserves and waits when exhausted`() = runSuspend {
        val clock = FakeRateLimitClock()
        val manager = manager(clock)
        manager.updateProfile(profile(tpm = 100L))

        // Keep the first reservation open: 60 tokens are held in this window.
        val first = manager.acquire(request(inputTokens = 30, outputTokens = 30))
        assertTrue(first.entries.isNotEmpty())
        assertEquals(emptyList(), clock.sleeps)

        // 60 already reserved; another 60 would exceed 100, so it waits.
        val second = manager.acquire(request(inputTokens = 30, outputTokens = 30))
        assertTrue(second.entries.isNotEmpty())
        assertEquals(listOf(60_000L), clock.sleeps)
    }

    @Test
    fun `a single request larger than the whole token budget is rejected, not looped`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(tpm = 50L))

        val error = assertFailsWith<ModelProviderError> {
            manager.acquire(request(inputTokens = 100, outputTokens = 100))
        }
        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertTrue(error.message!!.contains("single request"))
    }

    @Test
    fun `requests per day waits until the day rolls over`() = runSuspend {
        val clock = FakeRateLimitClock(now = 1_000L)
        val manager = manager(clock, maxWaitMillis = DefaultRateLimitManager.DAY_MILLIS)
        manager.updateProfile(profile(rpd = 1))

        manager.complete(manager.acquire(request()), null)
        manager.complete(manager.acquire(request()), null)

        assertEquals(1, clock.sleeps.size)
        assertEquals(DefaultRateLimitManager.DAY_MILLIS - 1_000L, clock.sleeps.single())
    }

    // --- provider and model scopes -----------------------------------------

    @Test
    fun `both provider and model limits are enforced`() = runSuspend {
        val clock = FakeRateLimitClock()
        val manager = manager(clock)
        manager.updateProfile(profile(rpm = 10))
        manager.updateProfile(profile(modelId = "llama-3.3-70b-versatile", rpm = 1))

        manager.complete(manager.acquire(request()), null)
        assertEquals(emptyList(), clock.sleeps)

        // Provider has room (10 rpm) but the model bucket is exhausted (1 rpm).
        manager.complete(manager.acquire(request()), null)
        assertEquals(listOf(60_000L), clock.sleeps)
    }

    @Test
    fun `a model-scoped profile does not throttle a different model`() = runSuspend {
        val clock = FakeRateLimitClock()
        val manager = manager(clock)
        manager.updateProfile(profile(modelId = "llama-3.3-70b-versatile", rpm = 1))

        manager.complete(manager.acquire(request(modelId = "llama-3.3-70b-versatile")), null)
        manager.complete(manager.acquire(request(modelId = "llama-3.1-8b-instant")), null)

        assertEquals(emptyList(), clock.sleeps)
    }

    @Test
    fun `an unknown remote provider falls back to the safe default rather than unlimited`() = runSuspend {
        val clock = FakeRateLimitClock()
        val manager = DefaultRateLimitManager(
            clock = clock,
            tracker = DefaultUsageTracker(),
            unknownRemoteLimits = RateLimitLimits(requestsPerMinute = 1),
        )

        manager.complete(manager.acquire(request()), null)
        assertEquals(emptyList(), clock.sleeps)
        manager.complete(manager.acquire(request()), null)
        assertEquals(listOf(60_000L), clock.sleeps)
    }

    @Test
    fun `a local request without a profile bypasses admission entirely`() = runSuspend {
        val clock = FakeRateLimitClock()
        val manager = manager(clock)
        manager.updateProfile(profile(rpm = 0))

        val permit = manager.acquire(request(rateLimited = false))
        assertTrue(permit.entries.isEmpty())
        assertTrue(clock.sleeps.isEmpty())
    }

    // --- concurrency -------------------------------------------------------

    @Test
    fun `concurrency waits for an in-flight request to finish`() = runSuspend {
        val clock = FakeRateLimitClock()
        val manager = DefaultRateLimitManager(
            clock = clock,
            tracker = DefaultUsageTracker(),
            unknownRemoteLimits = null,
            maxWaitMillis = 100L,
            concurrencyPollMillis = 50L,
        )
        manager.updateProfile(profile(concurrent = 1))

        val first = manager.acquire(request())
        val error = assertFailsWith<ModelProviderError> { manager.acquire(request()) }
        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertTrue(clock.sleeps.isNotEmpty())

        // Releasing the first request frees the slot.
        manager.complete(first, null)
        val second = manager.acquire(request())
        assertTrue(second.entries.isNotEmpty())
    }

    // --- cancellation ------------------------------------------------------

    @Test
    fun `cancellation while waiting stops the request without reserving`() = runSuspend {
        val clock = CancellingRateLimitClock(cancelAfter = 1)
        val manager = manager(clock)
        manager.updateProfile(profile(rpm = 1))

        manager.complete(manager.acquire(request()), null)

        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            manager.acquire(request())
        }
        assertEquals(1, clock.sleeps)
    }

    // --- reconciliation ----------------------------------------------------

    @Test
    fun `actual usage reconciles the reservation instead of the estimate`() = runSuspend {
        val clock = FakeRateLimitClock()
        val manager = manager(clock)
        manager.updateProfile(profile(tpm = 1_000L))

        val permit = manager.acquire(request(inputTokens = 400, outputTokens = 100))
        // Provider reports far fewer tokens than reserved.
        manager.complete(permit, ModelUsage(promptTokens = 60, completionTokens = 40, totalTokens = 100))

        // Only 100 tokens are now counted, so a fresh 800-token request fits.
        val second = manager.acquire(request(inputTokens = 700, outputTokens = 100))
        assertTrue(second.entries.isNotEmpty())
        assertEquals(emptyList(), clock.sleeps)
    }

    @Test
    fun `abandoning a request refunds the token reservation`() = runSuspend {
        val clock = FakeRateLimitClock()
        val manager = manager(clock)
        manager.updateProfile(profile(tpm = 1_000L))

        val permit = manager.acquire(request(inputTokens = 900, outputTokens = 100))
        manager.abandon(permit)

        val second = manager.acquire(request(inputTokens = 900, outputTokens = 100))
        assertTrue(second.entries.isNotEmpty())
        assertEquals(emptyList(), clock.sleeps)
    }

    @Test
    fun `closing a permit twice never double counts usage`() = runSuspend {
        val tracker = DefaultUsageTracker()
        val manager = manager(tracker = tracker)
        val permit = manager.acquire(request())

        manager.complete(permit, ModelUsage(totalTokens = 7))
        manager.complete(permit, ModelUsage(totalTokens = 7))
        manager.abandon(permit)

        val totals = tracker.totals("groq", "llama-3.3-70b-versatile")
        assertEquals(1L, totals.single().requestCount)
        assertEquals(7L, totals.single().totalTokens)
    }

    @Test
    fun `usage records distinguish estimated from actual tokens`() = runSuspend {
        val tracker = DefaultUsageTracker()
        val manager = manager(tracker = tracker)

        manager.complete(manager.acquire(request()), null)
        manager.complete(manager.acquire(request()), ModelUsage(totalTokens = 12))

        val totals = tracker.totals("groq", "llama-3.3-70b-versatile").single()
        assertEquals(2L, totals.requestCount)
        assertEquals(12L, totals.totalTokens)
    }

    @Test
    fun `rate limit events are counted separately`() = runSuspend {
        val tracker = DefaultUsageTracker()
        val manager = manager(tracker = tracker)

        manager.noteRateLimited(request())
        manager.noteRateLimited(request())

        assertEquals(2L, tracker.totals("groq", "llama-3.3-70b-versatile").single().rateLimitEvents)
    }

    // --- threading ---------------------------------------------------------

    @Test
    fun `two simultaneous requests cannot both pass a one-request quota`() = runBlocking {
        val manager = DefaultRateLimitManager(
            clock = FakeRateLimitClock(),
            tracker = DefaultUsageTracker(),
            unknownRemoteLimits = null,
            maxWaitMillis = 0L,
        )
        manager.updateProfile(profile(rpm = 1))

        val outcomes = listOf(
            async(Dispatchers.Default) { runCatching { manager.acquire(request()) }.isSuccess },
            async(Dispatchers.Default) { runCatching { manager.acquire(request()) }.isSuccess },
        ).awaitAll()

        assertEquals(1, outcomes.count { it }, "exactly one request may be admitted")
        assertEquals(1, outcomes.count { !it })
    }

    @Test
    fun `profiles clamp an app limit that exceeds provider metadata`() = runSuspend {
        val manager = manager()
        manager.updateProfile(
            RateLimitProfile(
                providerId = "groq",
                requestsPerMinute = 30,
                source = RateLimitSource.PROVIDER_REPORTED,
            ),
        )
        manager.updateProfile(profile(rpm = 500))

        assertEquals(30, manager.profile("groq")!!.requestsPerMinute)
    }
}
