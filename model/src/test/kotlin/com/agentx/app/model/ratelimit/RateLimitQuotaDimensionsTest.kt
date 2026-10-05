package com.agentx.app.model.ratelimit

import com.agentx.app.model.runSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quota dimensions a provider can state, and what happens to a reservation when
 * the request behind it finishes, fails, or is cancelled.
 *
 * The point of these tests is that a configured limit is enforced *before* dispatch
 * and that a reservation can never leak: either it is reconciled with what the
 * provider reported, or it is given back in full.
 */
class RateLimitQuotaDimensionsTest {

    private fun manager(clock: RateLimitClock = FakeRateLimitClock()) = DefaultRateLimitManager(
        clock = clock,
        tracker = DefaultUsageTracker(),
    )

    private suspend fun RateLimitManager.configure(profile: RateLimitProfile) = updateProfile(profile)

    private fun profile(
        rpm: Int? = null,
        rph: Int? = null,
        rpd: Int? = null,
        tpm: Long? = null,
        inputTpm: Long? = null,
        outputTpm: Long? = null,
        concurrent: Int? = null,
    ) = RateLimitProfile(
        providerId = "provider",
        modelId = "model",
        requestsPerMinute = rpm,
        requestsPerHour = rph,
        requestsPerDay = rpd,
        tokensPerMinute = tpm,
        inputTokensPerMinute = inputTpm,
        outputTokensPerMinute = outputTpm,
        maxConcurrentRequests = concurrent,
        source = RateLimitSource.APP_CONFIGURED,
    )

    private suspend fun RateLimitManager.reserve(
        input: Int = 10,
        output: Int = 10,
    ): RateLimitReservation = reserve("provider", "model", input, output)

    private suspend fun RateLimitManager.decide(input: Int = 10, output: Int = 10): RateLimitDecision =
        canRequest("provider", "model", input, output)

    // --- hourly requests ---------------------------------------------------

    @Test
    fun `an hourly request limit is enforced`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(rph = 3))

        manager.reserve()
        manager.reserve()
        manager.reserve()

        val blocked = assertIs<RateLimitDecision.Blocked>(manager.decide())
        assertEquals(RateLimitKind.REQUEST, blocked.kind)
        assertTrue(blocked.reason.contains("requests per hour"), blocked.reason)
        assertEquals(DefaultRateLimitManager.HOUR_MILLIS, blocked.retryAfterMs)
    }

    @Test
    fun `an hourly reservation is given back when the request fails`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(rph = 1))
        val permit = manager.reserve()
        assertIs<RateLimitDecision.Blocked>(manager.decide(), "the only hourly slot is taken")

        manager.reconcile(permit, actualInputTokens = null, actualOutputTokens = null, success = false)

        assertIs<RateLimitDecision.Allowed>(manager.decide(), "a failed request consumed nothing")
    }

    @Test
    fun `an hourly limit does not leak into the next hour`() = runSuspend {
        val clock = FakeRateLimitClock(now = 0L)
        val manager = manager(clock)
        manager.configure(profile(rph = 1))
        manager.reserve()
        assertIs<RateLimitDecision.Blocked>(manager.decide())

        clock.sleep(DefaultRateLimitManager.HOUR_MILLIS)

        assertIs<RateLimitDecision.Allowed>(manager.decide(), "the hour window has rolled over")
    }

    // --- split token ceilings ----------------------------------------------

    @Test
    fun `an input token ceiling is enforced independently of the output ceiling`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(inputTpm = 100L, outputTpm = 1_000L))

        // Well within the input side, far beyond the output side: the output ceiling
        // must block even though the input side has room to spare.
        val blocked = assertIs<RateLimitDecision.Blocked>(manager.decide(input = 10, output = 2_000))
        assertTrue(blocked.reason.contains("output tokens per minute"), blocked.reason)
        assertNull(blocked.retryAfterMs, "one request can never fit, so waiting does not help")
    }

    @Test
    fun `an output token ceiling is enforced independently of the input ceiling`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(inputTpm = 1_000L, outputTpm = 100L))

        val blocked = assertIs<RateLimitDecision.Blocked>(manager.decide(input = 2_000, output = 10))
        assertTrue(blocked.reason.contains("input tokens per minute"), blocked.reason)
    }

    @Test
    fun `a split ceiling is consumed cumulatively and not only per request`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(inputTpm = 100L, outputTpm = 100L))

        manager.reserve(input = 60, output = 60)

        val blocked = assertIs<RateLimitDecision.Blocked>(manager.decide(input = 60, output = 60))
        assertEquals(DefaultRateLimitManager.MINUTE_MILLIS, blocked.retryAfterMs)
    }

    @Test
    fun `actual input usage below the estimate frees the input budget again`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(inputTpm = 100L))
        val permit = manager.reserve(input = 60, output = 0)
        assertIs<RateLimitDecision.Blocked>(manager.decide(input = 60, output = 0))

        manager.reconcile(permit, actualInputTokens = 10, actualOutputTokens = 0, success = true)

        assertIs<RateLimitDecision.Allowed>(manager.decide(input = 60, output = 0))
    }

    @Test
    fun `actual input usage above the estimate consumes the extra`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(inputTpm = 100L))
        val permit = manager.reserve(input = 10, output = 0)
        assertIs<RateLimitDecision.Allowed>(manager.decide(input = 10, output = 0))

        manager.reconcile(permit, actualInputTokens = 95, actualOutputTokens = 0, success = true)

        assertTrue(
            assertIs<RateLimitDecision.Blocked>(manager.decide(input = 10, output = 0))
                .reason.contains("input tokens per minute"),
            "the request really used more than it reserved",
        )
    }

    @Test
    fun `a response without usage keeps the conservative estimate`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(inputTpm = 100L))
        val permit = manager.reserve(input = 60, output = 0)

        manager.reconcile(permit, actualInputTokens = null, actualOutputTokens = null, success = true)

        // Nothing was reported, so the estimate stands: crediting the request as free
        // would let an unreported provider be used past its ceiling.
        assertIs<RateLimitDecision.Blocked>(manager.decide(input = 60, output = 0))
    }

    @Test
    fun `an output estimate is reconciled separately from the input estimate`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(outputTpm = 100L))
        val permit = manager.reserve(input = 0, output = 50)

        // Actual output below the estimate frees room; the input side is untouched.
        manager.reconcile(permit, actualInputTokens = 0, actualOutputTokens = 5, success = true)

        assertIs<RateLimitDecision.Allowed>(manager.decide(input = 0, output = 50))
    }

    // --- unknown dimensions -------------------------------------------------

    @Test
    fun `an unconfigured dimension never blocks`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        // Only the hourly request count is stated; everything else is unknown.
        manager.configure(profile(rph = 10))

        assertIs<RateLimitDecision.Allowed>(
            manager.decide(input = 10_000_000, output = 10_000_000),
            "an unknown token ceiling must not be treated as zero",
        )
    }

    @Test
    fun `a zero ceiling is still a ceiling`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(rph = 0))

        val blocked = assertIs<RateLimitDecision.Blocked>(manager.decide())
        assertTrue(blocked.reason.contains("no requests per hour"), blocked.reason)
    }

    // --- concurrency --------------------------------------------------------

    @Test
    fun `concurrent requests cannot collectively oversubscribe the limit`() = runBlocking {
        val manager = DefaultRateLimitManager(clock = FakeRateLimitClock(), tracker = DefaultUsageTracker())
        manager.configure(profile(rpm = 5))

        val outcomes = (1..25).map {
            async(Dispatchers.Default) {
                runCatching { manager.reserve(input = 1, output = 1) }.isSuccess
            }
        }.awaitAll()

        assertEquals(5, outcomes.count { it }, "exactly the configured ceiling is admitted, no more")
        assertEquals(20, outcomes.count { !it })
    }

    @Test
    fun `a concurrency ceiling caps requests in flight`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(concurrent = 2))

        val first = manager.reserve()
        val second = manager.reserve()

        val blocked = assertIs<RateLimitDecision.Blocked>(manager.decide())
        assertEquals(RateLimitKind.REQUEST, blocked.kind)
        assertTrue(blocked.reason.contains("concurrent"), blocked.reason)

        manager.reconcile(first, null, null, success = true)
        assertIs<RateLimitDecision.Allowed>(manager.decide(), "one slot was released")

        manager.reconcile(second, null, null, success = false)
        assertIs<RateLimitDecision.Allowed>(manager.decide())
    }

    @Test
    fun `abandoning a reservation releases every dimension it took`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(rpm = 1, rph = 1, tpm = 100L, concurrent = 1))
        val permit = manager.reserve(input = 50, output = 50)
        assertIs<RateLimitDecision.Blocked>(
            manager.canRequest("provider", "model", 50, 50),
            "the reservation occupies the minute, hour and concurrency slots",
        )

        manager.reconcile(permit, actualInputTokens = null, actualOutputTokens = null, success = false)

        assertIs<RateLimitDecision.Allowed>(manager.canRequest("provider", "model", 50, 50))
    }

    @Test
    fun `only one reconciliation of a reservation is ever applied`() = runSuspend {
        val manager = manager(FakeRateLimitClock(now = 0L))
        manager.configure(profile(tpm = 100L))
        val permit = manager.reserve(input = 100, output = 0)

        manager.reconcile(permit, actualInputTokens = 10, actualOutputTokens = 0, success = true)
        // Claiming the same reservation again must change nothing: if the second call
        // were applied, the reserved 100 would be refunded a second time and the
        // bucket would under-count what the provider actually served.
        manager.reconcile(permit, actualInputTokens = 10, actualOutputTokens = 0, success = true)

        assertIs<RateLimitDecision.Allowed>(manager.decide(input = 90, output = 0))
        assertIs<RateLimitDecision.Blocked>(manager.decide(input = 91, output = 0))
    }
}
