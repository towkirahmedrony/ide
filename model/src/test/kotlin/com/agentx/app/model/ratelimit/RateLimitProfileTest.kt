package com.agentx.app.model.ratelimit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitProfileTest {

    @Test
    fun `a blank model id is provider scoped and a named one is model scoped`() {
        val provider = RateLimitProfile(providerId = "groq", modelId = null)
        val model = RateLimitProfile(providerId = "groq", modelId = "llama-3.3-70b-versatile")

        assertEquals(RateLimitScope.PROVIDER, provider.scope)
        assertEquals(RateLimitScope.MODEL, model.scope)
        assertNull(provider.key.modelId)
        assertEquals("llama-3.3-70b-versatile", model.key.modelId)
    }

    @Test
    fun `unknown limits are null rather than zero or unlimited`() {
        val profile = RateLimitProfile(providerId = "gemini")

        assertNull(profile.requestsPerMinute)
        assertNull(profile.tokensPerMinute)
        assertNull(profile.requestsPerDay)
        assertTrue(!profile.hasKnownLimit)
    }

    @Test
    fun `a safety margin reduces every configured limit`() {
        val profile = RateLimitProfile(
            providerId = "groq",
            requestsPerMinute = 100,
            tokensPerMinute = 1_000L,
            requestsPerDay = 1_000,
            safetyMargin = 0.1,
        )

        val limits = profile.effectiveLimits()
        assertEquals(Quota.Known(90L), limits.requestsPerMinute)
        assertEquals(Quota.Known(900L), limits.tokensPerMinute)
        assertEquals(Quota.Known(900L), limits.requestsPerDay)
        assertTrue(limits.tokensPerDay.isUnknown)
    }

    @Test
    fun `a zero limit stays zero so it still denies admission`() {
        val profile = RateLimitProfile(providerId = "groq", requestsPerMinute = 0)

        assertEquals(Quota.Known(0L), profile.effectiveLimits().requestsPerMinute)
    }

    @Test
    fun `clamping never exceeds the provider-reported ceiling`() {
        val providerCeiling = RateLimitProfile(
            providerId = "groq",
            requestsPerMinute = 30,
            tokensPerMinute = 6_000L,
            source = RateLimitSource.PROVIDER_REPORTED,
        )
        val configured = RateLimitProfile(
            providerId = "groq",
            requestsPerMinute = 500,
            tokensPerMinute = 100_000L,
            requestsPerDay = 200,
            source = RateLimitSource.APP_CONFIGURED,
        )

        val clamped = configured.clamp(providerCeiling)
        assertEquals(30, clamped.requestsPerMinute)
        assertEquals(6_000L, clamped.tokensPerMinute)
        assertEquals(200, clamped.requestsPerDay)
    }

    @Test
    fun `clamping only lowers configured values and never invents one`() {
        // The provider ceiling has an rpm the app profile does not configure; the
        // app profile keeps its own (null) rpm and its own token limit, because
        // the provider ceiling is enforced by its own provider-scoped profile.
        val providerCeiling = RateLimitProfile(providerId = "groq", requestsPerMinute = 30)
        val configured = RateLimitProfile(providerId = "groq", tokensPerMinute = 5_000L)

        val clamped = configured.clamp(providerCeiling)
        assertNull(clamped.requestsPerMinute)
        assertEquals(5_000L, clamped.tokensPerMinute)
    }

    @Test
    fun `a lower app limit is kept`() {
        val providerCeiling = RateLimitProfile(providerId = "groq", requestsPerMinute = 30)
        val configured = RateLimitProfile(providerId = "groq", requestsPerMinute = 5)

        assertEquals(5, configured.clamp(providerCeiling).requestsPerMinute)
    }
}
