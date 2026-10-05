package com.agentx.app.model.ratelimit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A configured quota has to survive a restart, and it has to come back exactly as it
 * was written: a limit that is lost or altered on the way back would either leave a
 * provider unprotected or throttle it with a number nobody configured.
 */
class RateLimitProfileCodecTest {

    private val full = RateLimitProfile(
        providerId = "groq",
        modelId = "openai/gpt-oss-120b",
        accountId = "preset-7",
        requestsPerMinute = 30,
        requestsPerHour = 250,
        requestsPerDay = 1_000,
        tokensPerMinute = 6_000L,
        inputTokensPerMinute = 4_000L,
        outputTokensPerMinute = 1_000L,
        tokensPerDay = 500_000L,
        maxConcurrentRequests = 2,
        enabled = false,
        safetyMargin = 0.25,
        source = RateLimitSource.PROVIDER_REPORTED,
        reference = "https://console.groq.com/docs/rate-limits",
        updatedAtMillis = 1_234L,
    )

    @Test
    fun `every configured dimension survives a round trip`() {
        val restored = RateLimitProfileCodec.decodeAll(RateLimitProfileCodec.encodeAll(listOf(full)))

        assertEquals(listOf(full), restored)
    }

    @Test
    fun `a profile with no known limit comes back unknown rather than zero`() {
        val unknown = RateLimitProfile(
            providerId = "openai-compatible",
            modelId = "devstral-24b",
            accountId = "custom-a",
            source = RateLimitSource.APP_CONFIGURED,
        )

        val restored = RateLimitProfileCodec.decodeAll(RateLimitProfileCodec.encodeAll(listOf(unknown))).single()

        assertEquals(unknown, restored)
        assertNull(restored.requestsPerMinute)
        assertNull(restored.tokensPerMinute)
        assertTrue(!restored.hasKnownLimit)
    }

    @Test
    fun `a stored margin outside the allowed range is rejected rather than clamped`() {
        // Clamping would silently weaken configured headroom; the entry is dropped so
        // the provider simply keeps whatever configuration it legitimately has.
        val text = """
            [{"providerId":"groq","requestsPerMinute":30,"safetyMargin":0.9}]
        """.trimIndent()

        assertTrue(RateLimitProfileCodec.decodeAll(text).isEmpty())
    }

    @Test
    fun `an entry without a provider identity is dropped`() {
        assertTrue(RateLimitProfileCodec.decodeAll("""[{"requestsPerMinute":30}]""").isEmpty())
    }

    @Test
    fun `unreadable stored text yields nothing instead of throwing`() {
        assertTrue(RateLimitProfileCodec.decodeAll("not json at all").isEmpty())
        assertTrue(RateLimitProfileCodec.decodeAll("").isEmpty())
        assertTrue(RateLimitProfileCodec.decodeAll("""{"providerId":"groq"}""").isEmpty(), "not an array")
    }

    @Test
    fun `unknown fields written by a newer build are ignored`() {
        val text = """
            [{"providerId":"groq","requestsPerMinute":30,"somethingAddedLater":true}]
        """.trimIndent()

        val restored = RateLimitProfileCodec.decodeAll(text).single()

        assertEquals(30, restored.requestsPerMinute)
        assertEquals(0.0, restored.safetyMargin, "an absent field keeps its default, it is not guessed")
    }

    @Test
    fun `a written profile never contains a credential or an endpoint`() {
        val encoded = RateLimitProfileCodec.encodeAll(listOf(full))

        // "token" is a dimension name here, never a credential, so the forbidden list
        // names the things a secret is actually called.
        listOf("apikey", "api_key", "authorization", "bearer", "secret", "password", "credential", "x-goog").forEach { forbidden ->
            assertTrue(
                !encoded.lowercase().contains(forbidden),
                "a profile is configuration, never a secret: found '$forbidden'",
            )
        }
        // The only URL allowed is the documentation a limit was taken from.
        assertTrue(encoded.contains(full.reference!!))
    }

    @Test
    fun `an empty profile list round trips as empty`() {
        assertTrue(RateLimitProfileCodec.decodeAll(RateLimitProfileCodec.encodeAll(emptyList())).isEmpty())
    }
}
