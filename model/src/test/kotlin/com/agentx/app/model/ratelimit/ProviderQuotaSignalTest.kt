package com.agentx.app.model.ratelimit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Provider-neutral quota signals and the structured headroom view.
 *
 * The parser is deliberately not a list of provider header names: it reads the
 * shape providers use, so a provider that publishes quota is understood without
 * being special-cased and one that publishes nothing stays UNKNOWN rather than
 * becoming a guessed allowance.
 */
class ProviderQuotaSignalTest {

    private fun headers(vararg pairs: Pair<String, String>): Map<String, List<String>> =
        pairs.associate { (name, value) -> name to listOf(value) }

    // --- header parsing ----------------------------------------------------

    @Test
    fun `several quota dimensions are read from one response`() {
        val signal = ProviderQuotaHeaders.parse(
            headers(
                "x-ratelimit-limit-requests" to "30",
                "x-ratelimit-remaining-requests" to "12",
                "x-ratelimit-limit-tokens" to "6000",
                "x-ratelimit-remaining-tokens" to "4500",
                "x-ratelimit-reset-tokens" to "2.5s",
                "retry-after" to "30",
            ),
            nowMillis = 1_000L,
        )

        val requests = signal.factFor(QuotaDimension.REQUESTS_PER_MINUTE)
        assertEquals(30L, requests?.limit)
        assertEquals(12L, requests?.remaining)
        assertEquals(QuotaKnowledge.KNOWN, requests?.knowledge)

        val tokens = signal.factFor(QuotaDimension.TOKENS_PER_MINUTE)
        assertEquals(6000L, tokens?.limit)
        assertEquals(4500L, tokens?.remaining)
        assertEquals(2500L, tokens?.resetAfterMillis)

        // A `Retry-After` is the provider telling us how long to wait, and it is
        // honoured rather than replaced by a local backoff guess.
        assertEquals(30_000L, signal.retryAfterMillis)
        assertTrue(signal.hasSignal)
    }

    @Test
    fun `a windowed dimension is not confused with the base window`() {
        val signal = ProviderQuotaHeaders.parse(
            headers(
                "x-ratelimit-remaining-requests-day" to "900",
                "x-ratelimit-limit-requests-day" to "1000",
                "x-ratelimit-remaining-tokens-minute" to "800",
            ),
        )

        assertEquals(900L, signal.factFor(QuotaDimension.REQUESTS_PER_DAY)?.remaining)
        assertEquals(1000L, signal.factFor(QuotaDimension.REQUESTS_PER_DAY)?.limit)
        assertEquals(800L, signal.factFor(QuotaDimension.TOKENS_PER_MINUTE)?.remaining)
        // The per-day bucket must not be reported as a per-minute one.
        assertNull(signal.factFor(QuotaDimension.REQUESTS_PER_MINUTE))
    }

    @Test
    fun `quota that was not reported stays unknown`() {
        val signal = ProviderQuotaHeaders.parse(
            headers("content-type" to "application/json", "x-request-id" to "abc"),
        )

        assertTrue(signal.facts.isEmpty())
        assertFalse(signal.hasSignal)
        assertNull(signal.factFor(QuotaDimension.TOKENS_PER_MINUTE)?.limit)
        assertEquals(ProviderQuotaSignal.EMPTY.facts, signal.facts)
    }

    @Test
    fun `an unreadable value yields no fact rather than an invented one`() {
        val signal = ProviderQuotaHeaders.parse(
            headers(
                "x-ratelimit-remaining-tokens" to "not-a-number",
                "x-ratelimit-reset-tokens" to "eventually",
            ),
        )

        // The dimension is simply absent: nothing is guessed from a value we could
        // not read.
        assertTrue(signal.facts.none { it.hasNumbers })
        assertNull(signal.factFor(QuotaDimension.TOKENS_PER_MINUTE)?.remaining)
        assertNull(signal.factFor(QuotaDimension.TOKENS_PER_MINUTE)?.resetAfterMillis)
    }

    @Test
    fun `reset values are read in the forms providers use`() {
        assertEquals(90_000L, resetOf("1m30s"))
        assertEquals(250L, resetOf("250ms"))
        assertEquals(120_000L, resetOf("120"))
        assertEquals(3_600_000L, resetOf("1h"))
        assertNull(resetOf("soon"))
    }

    private fun resetOf(raw: String): Long? =
        ProviderQuotaHeaders.parse(headers("x-ratelimit-reset-tokens" to raw)).factFor(QuotaDimension.TOKENS_PER_MINUTE)?.resetAfterMillis

    @Test
    fun `an unmodelled window is reported as unrecognised instead of guessed`() {
        val signal = ProviderQuotaHeaders.parse(headers("x-ratelimit-remaining-requests-week" to "5"))

        val fact = signal.facts.single()
        assertEquals(QuotaDimension.UNRECOGNISED, fact.dimension)
        assertEquals(5L, fact.remaining)
        assertNull(signal.factFor(QuotaDimension.REQUESTS_PER_MINUTE))
    }

    // --- headroom ----------------------------------------------------------

    private fun manager(vararg profiles: RateLimitProfile): DefaultRateLimitManager {
        val manager = DefaultRateLimitManager()
        runBlocking { profiles.forEach { manager.updateProfile(it) } }
        return manager
    }

    private fun profile(
        providerId: String = "groq",
        modelId: String? = null,
        rpm: Int? = null,
        tpm: Long? = null,
        margin: Double = 0.0,
    ) = RateLimitProfile(
        providerId = providerId,
        modelId = modelId,
        requestsPerMinute = rpm,
        tokensPerMinute = tpm,
        safetyMargin = margin,
        source = RateLimitSource.APP_CONFIGURED,
    )

    @Test
    fun `headroom evaluates every configured dimension and reports it`() = runBlocking {
        val manager = manager(profile(rpm = 10, tpm = 100_000))

        val headroom = manager.headroom(
            providerId = "groq",
            modelId = "groq-y",
            estimatedInputTokens = 100,
            estimatedOutputTokens = 100,
        )

        assertTrue(headroom.allowed)
        assertEquals(200L, headroom.estimatedRequestTokens)
        assertEquals(
            setOf(QuotaDimension.REQUESTS_PER_MINUTE, QuotaDimension.TOKENS_PER_MINUTE),
            headroom.dimensions.map { it.dimension }.toSet(),
        )
        val tokens = headroom.dimensions.first { it.dimension == QuotaDimension.TOKENS_PER_MINUTE }
        assertEquals(100_000L, tokens.limit)
        assertTrue(tokens.knowledge == QuotaKnowledge.KNOWN || tokens.knowledge == QuotaKnowledge.ESTIMATED)
        assertEquals(RateLimitSource.APP_CONFIGURED, tokens.source)
    }

    @Test
    fun `a dimension with no known limit is never presented as a number`() = runBlocking {
        // Only a request limit is configured: the token allowance is unknown, so it
        // must not appear as an invented remaining figure.
        val manager = manager(profile(rpm = 10))

        val headroom = manager.headroom("groq", "groq-y", 100, 100)
        val tokens = headroom.dimensions.firstOrNull { it.dimension == QuotaDimension.TOKENS_PER_MINUTE }

        assertNull(tokens)
        assertTrue(headroom.allowed)
    }

    @Test
    fun `reservations are part of the preflight decision`() = runBlocking {
        val manager = manager(profile(rpm = 2))

        assertTrue(manager.headroom("groq", "groq-y", 10, 10).allowed)
        manager.reserve("groq", "groq-y", 10, 10)
        // One of the two is now held by the reservation.
        assertTrue(manager.headroom("groq", "groq-y", 10, 10).allowed)
        manager.reserve("groq", "groq-y", 10, 10)

        // Both are held, so a third request must be refused before it is sent.
        val blocked = manager.headroom("groq", "groq-y", 10, 10)
        assertTrue(blocked.blocked)
        assertEquals(RateLimitKind.REQUEST, blocked.limitingKind)
    }

    @Test
    fun `the safety margin keeps the last usable capacity for the reserve`() = runBlocking {
        // A hard limit of 10 with half of it protected: the request must be refused
        // well before the provider's own ceiling is reached.
        val manager = manager(profile(rpm = 10, margin = 0.5))

        var admitted = 0
        repeat(10) { index ->
            val headroom = manager.headroom("groq", "groq-y", 10, 10)
            if (!headroom.allowed) return@repeat
            manager.reserve("groq", "groq-y", 10, 10)
            admitted = index + 1
        }

        assertTrue(admitted < 10, "the protected margin must stop the request before the hard limit")
        assertTrue(manager.headroom("groq", "groq-y", 10, 10).blocked)
    }

    @Test
    fun `a local endpoint has no remote quota to spend`() = runBlocking {
        // The same provider is blocked remotely, but a local runtime does not consume
        // remote quota, so it must not be refused and must not report remote capacity.
        val manager = manager(profile(rpm = 0))

        assertTrue(manager.headroom("groq", "groq-y", 10, 10).blocked)
        val local = manager.headroom("groq", "groq-y", 10, 10, local = true)

        assertTrue(local.allowed)
        assertTrue(local.local)
        assertTrue(local.dimensions.isEmpty())

        val reservation = manager.reserve("groq", "groq-y", 10, 10, local = true)
        assertTrue(reservation.bypassed)
    }

    @Test
    fun `a blocked preflight carries the reason and the retry signal`() = runBlocking {
        val manager = manager(profile(rpm = 0))

        val headroom = manager.headroom("groq", "groq-y", 10, 10)

        assertTrue(headroom.blocked)
        assertFalse(headroom.reason.isNullOrBlank())
        assertEquals(RateLimitKind.REQUEST, headroom.limitingKind)
    }
}
