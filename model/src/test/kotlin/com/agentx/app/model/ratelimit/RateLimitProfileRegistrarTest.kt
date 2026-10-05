package com.agentx.app.model.ratelimit

import com.agentx.app.model.preset.ModelProviderIds
import com.agentx.app.model.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A profile that exists in source but never reaches the live manager is not an
 * implemented quota. These tests are about the step in between: a connection coming
 * online publishing the limits its requests will be admitted against.
 */
class RateLimitProfileRegistrarTest {

    private val documentedModel = "openai/gpt-oss-120b"
    private val groqRpm = 30
    private val groqRpd = 1_000

    private fun manager() = DefaultRateLimitManager(
        clock = FakeRateLimitClock(),
        tracker = DefaultUsageTracker(),
    )

    /** No margin, so the assertions below compare against the provider's own numbers. */
    private fun registrar(manager: RateLimitManager) = CatalogRateLimitProfileRegistrar(
        manager = manager,
        policy = RateLimitPolicy(safetyMargin = 0.0),
    )

    private suspend fun RateLimitManager.exhaust(scope: String, times: Int) {
        repeat(times) {
            reserve(
                providerId = ModelProviderIds.GROQ,
                modelId = documentedModel,
                estimatedInputTokens = 10,
                estimatedOutputTokens = 10,
                accountId = scope,
                local = false,
            )
        }
    }

    @Test
    fun `a supported provider registers its profiles with the live manager`() = runSuspend {
        val manager = manager()
        assertEquals(0, manager.profiles().size, "the manager starts with no configured quota")

        registrar(manager).registerFor("groq-conn-1", ModelProviderIds.GROQ, local = false)

        val profiles = manager.profiles()
        assertTrue(profiles.isNotEmpty(), "a configured provider must produce profiles")
        assertTrue(profiles.all { it.providerId == ModelProviderIds.GROQ })
        assertTrue(profiles.all { it.source == RateLimitSource.PROVIDER_REPORTED })
        assertNotNull(
            manager.profile(ModelProviderIds.GROQ, documentedModel, "groq-conn-1"),
            "the profile must be readable at the scope requests are admitted under",
        )
    }

    @Test
    fun `a registered profile is what admission then enforces`() = runSuspend {
        val manager = manager()
        registrar(manager).registerFor("groq-conn-1", ModelProviderIds.GROQ, local = false)

        assertIs<RateLimitDecision.Allowed>(
            manager.canRequest(ModelProviderIds.GROQ, documentedModel, 10, 10, accountId = "groq-conn-1"),
        )

        manager.exhaust("groq-conn-1", groqRpm)

        val blocked = assertIs<RateLimitDecision.Blocked>(
            manager.canRequest(ModelProviderIds.GROQ, documentedModel, 10, 10, accountId = "groq-conn-1"),
        )
        assertEquals(RateLimitKind.REQUEST, blocked.kind)
        assertTrue(blocked.reason.contains("requests per minute"), blocked.reason)
    }

    @Test
    fun `a day limit registered from the catalog is enforced too`() = runSuspend {
        val manager = manager()
        registrar(manager).registerFor("groq-conn-1", ModelProviderIds.GROQ, local = false)
        // The rpm ceiling is raised by hand so the day ceiling is the one under test;
        // this is the app configuring *below* the provider, never above it.
        manager.updateProfile(
            RateLimitProfile(
                providerId = ModelProviderIds.GROQ,
                modelId = documentedModel,
                accountId = "groq-conn-1",
                requestsPerDay = groqRpd,
                source = RateLimitSource.APP_CONFIGURED,
            ),
        )

        manager.exhaust("groq-conn-1", groqRpd)

        val blocked = assertIs<RateLimitDecision.Blocked>(
            manager.canRequest(ModelProviderIds.GROQ, documentedModel, 10, 10, accountId = "groq-conn-1"),
        )
        assertTrue(blocked.reason.contains("requests per day"), blocked.reason)
    }

    @Test
    fun `the provider's number is registered and the policy margin is applied on top`() = runSuspend {
        val manager = manager()
        CatalogRateLimitProfileRegistrar(manager).registerFor("groq-conn-1", ModelProviderIds.GROQ, local = false)

        val registered = assertNotNull(manager.profile(ModelProviderIds.GROQ, documentedModel, "groq-conn-1"))
        assertEquals(groqRpm, registered.requestsPerMinute, "the provider's own ceiling is stored unchanged")
        assertEquals(groqRpd, registered.requestsPerDay)
        assertEquals(RateLimitPolicy.DEFAULT.safetyMargin, registered.safetyMargin)

        val enforced = assertNotNull(registered.effectiveLimits().requestsPerMinute.knownValue)
        assertTrue(enforced < groqRpm, "headroom is held back below the published ceiling")
        assertTrue(enforced > groqRpm / 2, "but the allowance is still used, not thrown away")
    }

    @Test
    fun `registering the same connection twice re-states its limits instead of stacking them`() = runSuspend {
        val manager = manager()
        val registrar = registrar(manager)

        registrar.registerFor("groq-conn-1", ModelProviderIds.GROQ, local = false)
        val once = manager.profiles().map { it.key }.toSet()
        registrar.registerFor("groq-conn-1", ModelProviderIds.GROQ, local = false)

        assertEquals(once, manager.profiles().map { it.key }.toSet(), "a reconnect must not create a second bucket")
    }

    @Test
    fun `a local connection is never given a remote quota`() = runSuspend {
        val manager = manager()

        registrar(manager).registerFor("local-ollama", ModelProviderIds.OPENAI_COMPATIBLE, local = true)

        assertTrue(manager.profiles().isEmpty(), "a local runtime consumes no remote quota")
        assertIs<RateLimitDecision.Allowed>(
            manager.canRequest(
                ModelProviderIds.OPENAI_COMPATIBLE,
                "qwen2.5-coder-14b",
                10,
                10,
                accountId = "local-ollama",
                local = true,
            ),
        )
    }

    @Test
    fun `a provider with no documented limits registers nothing and is still usable`() = runSuspend {
        val manager = manager()

        registrar(manager).registerFor("custom-a", ModelProviderIds.OPENAI_COMPATIBLE, local = false)

        assertTrue(manager.profiles().isEmpty())
        // Unknown must not become zero: the request is admitted normally.
        assertIs<RateLimitDecision.Allowed>(
            manager.canRequest(ModelProviderIds.OPENAI_COMPATIBLE, "devstral-24b", 100, 100, accountId = "custom-a"),
        )
    }

    @Test
    fun `two connections of one family never share a quota bucket`() = runSuspend {
        val manager = manager()
        val registrar = registrar(manager)
        registrar.registerFor("groq-conn-1", ModelProviderIds.GROQ, local = false)
        registrar.registerFor("groq-conn-2", ModelProviderIds.GROQ, local = false)

        val scopes = manager.profiles().mapNotNull { it.accountId }.toSet()
        assertEquals(setOf("groq-conn-1", "groq-conn-2"), scopes)

        // Exhausting one connection leaves the other untouched: they are two
        // accounts with two allowances, not one family-wide pool.
        manager.exhaust("groq-conn-1", groqRpm)

        assertIs<RateLimitDecision.Blocked>(
            manager.canRequest(ModelProviderIds.GROQ, documentedModel, 10, 10, accountId = "groq-conn-1"),
        )
        assertIs<RateLimitDecision.Allowed>(
            manager.canRequest(ModelProviderIds.GROQ, documentedModel, 10, 10, accountId = "groq-conn-2"),
        )
    }

    @Test
    fun `a connection with no distinct identity keeps the family scope`() = runSuspend {
        val manager = manager()
        // A draft configuration has no saved preset, so its connection id falls back
        // to the provider family and there is nothing to discriminate.
        registrar(manager).registerFor(ModelProviderIds.GROQ, ModelProviderIds.GROQ, local = false)

        assertTrue(manager.profiles().all { it.accountId == null })
        assertNotNull(manager.profile(ModelProviderIds.GROQ, documentedModel, null))
        assertEquals(quotaScopeOf(ModelProviderIds.GROQ, ModelProviderIds.GROQ), null)
        assertEquals(
            "groq-conn-1",
            quotaScopeOf("groq-conn-1", ModelProviderIds.GROQ),
            "a saved preset is a distinct connection",
        )
    }

    @Test
    fun `a headroom report reflects a registered profile instead of claiming ignorance`() = runSuspend {
        val manager = manager()
        registrar(manager).registerFor("groq-conn-1", ModelProviderIds.GROQ, local = false)

        val headroom = manager.headroom(
            providerId = ModelProviderIds.GROQ,
            modelId = documentedModel,
            estimatedInputTokens = 100,
            estimatedOutputTokens = 100,
            accountId = "groq-conn-1",
        )

        assertEquals(false, headroom.local)
        assertEquals(200L, headroom.estimatedRequestTokens)
        val rpm = headroom.dimensions.single { it.dimension == QuotaDimension.REQUESTS_PER_MINUTE }
        assertEquals(groqRpm.toLong(), rpm.limit)
        assertEquals(RateLimitSource.PROVIDER_REPORTED, rpm.source)
        assertNull(rpm.used, "nothing has been used yet")
        assertIs<RateLimitDecision.Allowed>(headroom.decision)
    }
}
