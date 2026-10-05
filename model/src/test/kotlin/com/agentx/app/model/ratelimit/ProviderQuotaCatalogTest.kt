package com.agentx.app.model.ratelimit

import com.agentx.app.model.preset.ModelProviderIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catalog is the configuration source admission control was missing, so what it
 * says — and what it refuses to say — is the difference between a proactive limiter
 * and one that only reacts to a 429.
 */
class ProviderQuotaCatalogTest {

    @Test
    fun `a documented provider produces model-scoped profiles`() {
        val profiles = ProviderQuotaCatalog.profiles(ModelProviderIds.GROQ)

        assertTrue(profiles.isNotEmpty(), "a supported provider must be configurable")
        assertTrue(profiles.all { it.providerId == ModelProviderIds.GROQ })
        assertTrue(
            profiles.all { !it.modelId.isNullOrBlank() },
            "Groq publishes its free-plan limits per model, so each entry is model-scoped",
        )
        assertTrue(profiles.all { it.hasKnownLimit }, "a published entry carries a number")
    }

    @Test
    fun `a documented entry is provider-reported and cites its source`() {
        val profiles = ProviderQuotaCatalog.profiles(ModelProviderIds.GROQ)

        assertTrue(profiles.all { it.source == RateLimitSource.PROVIDER_REPORTED })
        assertTrue(
            profiles.all { !it.reference.isNullOrBlank() },
            "a provider-reported number must be auditable back to its documentation",
        )
        assertEquals(
            ProviderQuotaCatalog.reference(ModelProviderIds.GROQ),
            profiles.first().reference,
        )
    }

    @Test
    fun `the catalog carries provider facts and no policy`() {
        val profiles = ProviderQuotaCatalog.profiles(ModelProviderIds.GROQ)

        // Headroom is a decision AgentX makes, not something the provider published:
        // baking it into the catalog would misreport the provider's own ceiling.
        assertTrue(
            profiles.all { it.safetyMargin == 0.0 },
            "the safety margin belongs to the policy, not to the provider's number",
        )
    }

    @Test
    fun `a provider with no published limits has no entry rather than a guessed one`() {
        assertTrue(ProviderQuotaCatalog.profiles(ModelProviderIds.OPENAI_COMPATIBLE).isEmpty())
        assertTrue(ProviderQuotaCatalog.profiles("some-unlisted-provider").isEmpty())
        assertNull(ProviderQuotaCatalog.reference("some-unlisted-provider"))
    }

    @Test
    fun `an unpublished limit is explained instead of being silently absent`() {
        // The two look identical from the outside, and only one of them is a gap:
        // observability must be able to tell "the provider publishes nothing" from
        // "AgentX forgot to configure this".
        assertTrue(
            !ProviderQuotaCatalog.unpublishedReason(ModelProviderIds.GEMINI).isNullOrBlank(),
            "Gemini's limits are documented as unpublished",
        )
        assertTrue(
            !ProviderQuotaCatalog.unpublishedReason(ModelProviderIds.OPENAI_COMPATIBLE).isNullOrBlank(),
            "a custom endpoint has no provider to publish a limit",
        )
        assertNull(ProviderQuotaCatalog.unpublishedReason("some-unlisted-provider"))
        assertTrue(ModelProviderIds.GROQ in ProviderQuotaCatalog.configuredProviders())
    }

    // --- the catalog as a limit source -------------------------------------

    @Test
    fun `the limit source resolves a documented model`() {
        val source = CatalogRateLimitLimitSource()
        val documented = ProviderQuotaCatalog.profiles(ModelProviderIds.GROQ).first().modelId!!

        val limits = source.limitsFor(ModelProviderIds.GROQ, documented, null)

        assertTrue(limits.requestsPerMinute.knownValue != null, "the documented rpm reaches admission")
        assertTrue(limits.requestsPerDay.knownValue != null, "the documented rpd reaches admission")
        // A dimension the provider never published stays unknown and is not enforced.
        assertTrue(limits.tokensPerMinute.isUnknown)
    }

    @Test
    fun `the limit source has nothing for an undocumented model`() {
        val source = CatalogRateLimitLimitSource()

        val limits = source.limitsFor(ModelProviderIds.GROQ, "a-model-nobody-published-limits-for", null)

        assertTrue(limits.isEmpty, "an undocumented model must not be given a ceiling")
    }

    @Test
    fun `the limit source has nothing for a provider with no entry`() {
        val source = CatalogRateLimitLimitSource()

        assertTrue(source.limitsFor(ModelProviderIds.GEMINI, "gemini-3.5-flash", null).isEmpty)
        assertTrue(source.limitsFor(ModelProviderIds.OPENAI_COMPATIBLE, "qwen", null).isEmpty)
    }

    @Test
    fun `the limit source applies the policy margin without changing the provider fact`() {
        val documented = ProviderQuotaCatalog.profiles(ModelProviderIds.GROQ).first()
        val margin = 0.25

        val limits = CatalogRateLimitLimitSource(policy = RateLimitPolicy(margin))
            .limitsFor(ModelProviderIds.GROQ, documented.modelId!!, null)

        val expectedRpm = documented.requestsPerMinute!! * (1.0 - margin)
        assertEquals(expectedRpm.toLong(), limits.requestsPerMinute.knownValue)
        // The provider's own number is untouched in the catalog.
        assertEquals(
            documented.requestsPerMinute,
            ProviderQuotaCatalog.profiles(ModelProviderIds.GROQ).first().requestsPerMinute,
        )
    }

    @Test
    fun `the default policy reserves headroom below a published ceiling`() {
        assertTrue(
            RateLimitPolicy.DEFAULT.safetyMargin > 0.0,
            "scheduling exactly at a provider's ceiling turns a wait into a failed request",
        )
        assertTrue(RateLimitPolicy.DEFAULT.safetyMargin <= RateLimitProfile.MAX_SAFETY_MARGIN)
    }
}
