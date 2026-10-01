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
        assertEquals(90, limits.requestsPerMinute)
        assertEquals(900L, limits.tokensPerMinute)
        assertEquals(900, limits.requestsPerDay)
    }

    @Test
    fun `a zero limit stays zero so it still denies admission`() {
        val profile = RateLimitProfile(providerId = "groq", requestsPerMinute = 0)

        assertEquals(0, profile.effectiveLimits().requestsPerMinute)
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
    fun `clamping does not invent a limit the provider did not report`() {
        val providerCeiling = RateLimitProfile(providerId = "groq", requestsPerMinute = 30)
        val configured = RateLimitProfile(providerId = "groq", tokensPerMinute = 5_000L)

        val clamped = configured.clamp(providerCeiling)
        assertEquals(30, clamped.requestsPerMinute)
        assertEquals(5_000L, clamped.tokensPerMinute)
    }

    @Test
    fun `a lower app limit is kept`() {
        val providerCeiling = RateLimitProfile(providerId = "groq", requestsPerMinute = 30)
        val configured = RateLimitProfile(providerId = "groq", requestsPerMinute = 5)

        assertEquals(5, configured.clamp(providerCeiling).requestsPerMinute)
    }
}
