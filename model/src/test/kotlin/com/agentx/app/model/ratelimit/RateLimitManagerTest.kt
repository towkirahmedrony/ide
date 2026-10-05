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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitManagerTest {

    private fun manager(clock: RateLimitClock = FakeRateLimitClock()) = DefaultRateLimitManager(
        clock = clock,
        tracker = DefaultUsageTracker(),
    )

    private fun profile(
        providerId: String = "groq",
        modelId: String? = null,
        rpm: Int? = null,
        tpm: Long? = null,
        rpd: Int? = null,
        tpd: Long? = null,
        concurrent: Int? = null,
    ) = RateLimitProfile(
        providerId = providerId,
        modelId = modelId,
        requestsPerMinute = rpm,
        tokensPerMinute = tpm,
        requestsPerDay = rpd,
        tokensPerDay = tpd,
        maxConcurrentRequests = concurrent,
        source = RateLimitSource.APP_CONFIGURED,
    )

    @Test
    fun `known request-per-minute limit admits then blocks`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(rpm = 2))

        manager.complete(manager.reserve(request()), null)
        manager.complete(manager.reserve(request()), null)

        val decision = manager.canRequest(request())
        val blocked = assertIs<RateLimitDecision.Blocked>(decision)
        assertEquals(RateLimitKind.REQUEST, blocked.kind)
        assertEquals(60_000L, blocked.retryAfterMs)

        val error = assertFailsWith<ModelProviderError> { manager.reserve(request()) }
        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `a zero request-per-minute limit denies admission`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(rpm = 0))

        val error = assertFailsWith<ModelProviderError> { manager.reserve(request()) }
        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertTrue(error.message!!.contains("no requests per minute"))
    }

    @Test
    fun `known token-per-minute limit reserves and blocks when exhausted`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(tpm = 100L))

        val first = manager.reserve(request(inputTokens = 30, outputTokens = 30))
        assertTrue(first.entries.isNotEmpty())

        val decision = manager.canRequest(request(inputTokens = 30, outputTokens = 30))
        val blocked = assertIs<RateLimitDecision.Blocked>(decision)
        assertEquals(RateLimitKind.TOKENS, blocked.kind)
        assertEquals(60_000L, blocked.retryAfterMs)
    }

    @Test
    fun `a single request larger than the token budget is rejected`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(tpm = 50L))

        val error = assertFailsWith<ModelProviderError> {
            manager.reserve(request(inputTokens = 100, outputTokens = 100))
        }
        assertEquals(ModelProviderErrorCode.RATE_LIMITED, error.code)
        assertTrue(error.message!!.contains("single request"))
        assertEquals("TOKENS", error.details["scope"])
    }

    @Test
    fun `requests per day blocks until the day window ends`() = runSuspend {
        val clock = FakeRateLimitClock(now = 1_000L)
        val manager = manager(clock)
        manager.updateProfile(profile(rpd = 1))

        manager.complete(manager.reserve(request()), null)

        val blocked = assertIs<RateLimitDecision.Blocked>(manager.canRequest(request()))
        assertEquals(RateLimitKind.REQUEST, blocked.kind)
        assertEquals(DefaultRateLimitManager.DAY_MILLIS - 1_000L, blocked.retryAfterMs)
    }

    @Test
    fun `known tokens per day limit blocks`() = runSuspend {
        val clock = FakeRateLimitClock(now = 1_000L)
        val manager = manager(clock)
        manager.updateProfile(profile(tpd = 50L))

        manager.complete(manager.reserve(request(inputTokens = 40, outputTokens = 10)), null)

        val blocked = assertIs<RateLimitDecision.Blocked>(
            manager.canRequest(request(inputTokens = 1, outputTokens = 0)),
        )
        assertEquals(RateLimitKind.TOKENS, blocked.kind)
        assertEquals(DefaultRateLimitManager.DAY_MILLIS - 1_000L, blocked.retryAfterMs)
    }

    @Test
    fun `unknown limits are not replaced with guessed numbers`() = runSuspend {
        val manager = manager()

        assertIs<RateLimitDecision.Allowed>(manager.canRequest(request()))
        val reservation = manager.reserve(request())
        assertTrue(reservation.bypassed)
        assertTrue(reservation.entries.isEmpty())
    }

    @Test
    fun `provider and model buckets are isolated`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(modelId = "llama-3.3-70b-versatile", rpm = 1))

        manager.complete(manager.reserve(request(modelId = "llama-3.3-70b-versatile")), null)

        assertIs<RateLimitDecision.Blocked>(
            manager.canRequest(request(modelId = "llama-3.3-70b-versatile")),
        )
        assertIs<RateLimitDecision.Allowed>(
            manager.canRequest(request(modelId = "llama-3.1-8b-instant")),
        )
        manager.complete(manager.reserve(request(modelId = "llama-3.1-8b-instant")), null)
    }

    @Test
    fun `both provider and model limits are enforced`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(rpm = 10))
        manager.updateProfile(profile(modelId = "llama-3.3-70b-versatile", rpm = 1))

        manager.complete(manager.reserve(request()), null)

        val blocked = assertIs<RateLimitDecision.Blocked>(manager.canRequest(request()))
        assertEquals(RateLimitKind.REQUEST, blocked.kind)
    }

    @Test
    fun `a local request without a profile bypasses admission`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(rpm = 0))

        val reservation = manager.reserve(request(rateLimited = false))
        assertTrue(reservation.bypassed)
        assertTrue(reservation.entries.isEmpty())
        assertIs<RateLimitDecision.Allowed>(manager.canRequest(request(rateLimited = false)))
    }

    @Test
    fun `successful reconciliation uses actual tokens instead of the estimate`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(tpm = 1_000L))

        val permit = manager.reserve(request(inputTokens = 400, outputTokens = 100))
        manager.reconcile(permit, actualInputTokens = 60, actualOutputTokens = 40, success = true)

        val second = manager.reserve(request(inputTokens = 700, outputTokens = 100))
        assertTrue(second.entries.isNotEmpty())
        assertIs<RateLimitDecision.Allowed>(manager.canRequest(request(inputTokens = 100, outputTokens = 0)))
    }

    @Test
    fun `failed request reconciliation refunds the reservation`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(tpm = 1_000L, rpm = 1))

        val permit = manager.reserve(request(inputTokens = 900, outputTokens = 100))
        manager.reconcile(permit, actualInputTokens = null, actualOutputTokens = null, success = false)

        val second = manager.reserve(request(inputTokens = 900, outputTokens = 100))
        assertTrue(second.entries.isNotEmpty())
    }

    @Test
    fun `unavailable actual usage keeps the estimated reservation`() = runSuspend {
        val manager = manager()
        manager.updateProfile(profile(tpm = 1_000L))

        val permit = manager.reserve(request(inputTokens = 400, outputTokens = 100))
        manager.reconcile(permit, actualInputTokens = null, actualOutputTokens = null, success = true)

        val blocked = assertIs<RateLimitDecision.Blocked>(
            manager.canRequest(request(inputTokens = 400, outputTokens = 200)),
        )
        assertEquals(RateLimitKind.TOKENS, blocked.kind)
    }

    @Test
    fun `closing a reservation twice never double counts usage`() = runSuspend {
        val tracker = DefaultUsageTracker()
        val manager = DefaultRateLimitManager(tracker = tracker)
        val permit = manager.reserve(request())

        manager.complete(permit, ModelUsage(totalTokens = 7))
        manager.complete(permit, ModelUsage(totalTokens = 7))
        manager.abandon(permit)

        val totals = tracker.totals("groq", "llama-3.3-70b-versatile")
        assertEquals(1L, totals.single().requestCount)
        assertEquals(7L, totals.single().totalTokens)
    }

    @Test
    fun `http 429 records temporary unavailability`() = runSuspend {
        val clock = FakeRateLimitClock(now = 5_000L)
        val manager = manager(clock)
        // accountId = null: the request carries no connection discriminator, so the
        // cooldown belongs to the provider/model scope itself.
        manager.recordRateLimited("groq", "llama-3.3-70b-versatile", retryAfterMs = 2_000L, accountId = null)

        val blocked = assertIs<RateLimitDecision.Blocked>(manager.canRequest(request()))
        assertEquals(2_000L, blocked.retryAfterMs)
        assertTrue(blocked.reason.contains("provider reported a rate limit"))
    }

    @Test
    fun `token-based rate-limit response blocks until the known window ends`() = runSuspend {
        val clock = FakeRateLimitClock(now = 10_000L)
        val manager = manager(clock)
        manager.updateProfile(profile(tpm = 1_000L))
        manager.recordRateLimited(
            providerId = "groq",
            modelId = "llama-3.3-70b-versatile",
            retryAfterMs = null,
            kind = RateLimitKind.TOKENS,
            accountId = null,
        )

        val blocked = assertIs<RateLimitDecision.Blocked>(manager.canRequest(request()))
        assertEquals(RateLimitKind.TOKENS, blocked.kind)
        assertEquals(50_000L, blocked.retryAfterMs)
    }

    @Test
    fun `missing retry-after does not invent a wait when limits are unknown`() = runSuspend {
        val manager = manager()
        manager.recordRateLimited("groq", "llama-3.3-70b-versatile", retryAfterMs = null, accountId = null)

        assertIs<RateLimitDecision.Allowed>(manager.canRequest(request()))
        assertNull(manager.canRequest(request()).let { (it as? RateLimitDecision.Blocked)?.retryAfterMs })
    }

    @Test
    fun `two simultaneous requests cannot both pass a one-request quota`() = runBlocking {
        val manager = DefaultRateLimitManager(
            clock = FakeRateLimitClock(),
            tracker = DefaultUsageTracker(),
        )
        manager.updateProfile(profile(rpm = 1))

        val outcomes = listOf(
            async(Dispatchers.Default) { runCatching { manager.reserve(request()) }.isSuccess },
            async(Dispatchers.Default) { runCatching { manager.reserve(request()) }.isSuccess },
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
