package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.ModelFallbackReason
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.ratelimit.DefaultRateLimitManager
import com.agentx.app.model.ratelimit.RateLimitProfile
import com.agentx.app.model.ratelimit.RateLimitSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Preemptive headroom protection.
 *
 * The rule under test: a provider/model that AgentX already knows has no safe
 * quota headroom is *never asked*. Waiting for its 429 would make the failure
 * response the mechanism for discovering exhaustion, and would knowingly spend
 * the last usable capacity on a request the runtime knew was over the line.
 *
 * Every case uses the real [AgentModelResolver] eligibility system and the real
 * [DefaultRateLimitManager]; the model call is a recording lambda, so "was this
 * provider actually asked?" is observable and no network request is made.
 */
class PreemptiveQuotaSwitchTest {

    // --- fixtures ----------------------------------------------------------

    private fun config(
        provider: String,
        model: String,
        toolCalling: Boolean = true,
        local: Boolean = false,
        enabled: Boolean = true,
    ) = ModelConfig(
        providerId = provider,
        baseUrl = if (local) "http://localhost:11434/v1" else "https://$provider.example/v1",
        model = model,
        capabilities = ModelCapabilities(
            toolCalling = toolCalling,
            streaming = true,
            local = local,
            enabled = enabled,
        ),
    )

    /** A profile whose only allowance is already spent: the scope is exhausted. */
    private fun exhausted(providerId: String, modelId: String? = null) = RateLimitProfile(
        providerId = providerId,
        modelId = modelId,
        requestsPerMinute = 0,
        source = RateLimitSource.APP_CONFIGURED,
    )

    private fun manager(vararg profiles: RateLimitProfile): DefaultRateLimitManager {
        val manager = DefaultRateLimitManager()
        runBlocking { profiles.forEach { manager.updateProfile(it) } }
        return manager
    }

    private fun resolver(
        connections: Map<String, ModelConfig>,
        rateLimits: DefaultRateLimitManager,
    ) = AgentModelResolver(
        connections = { connections },
        capabilityRegistry = InMemoryModelCapabilityRegistry(),
        rateLimitManager = rateLimits,
    )

    private fun policy(
        vararg chain: RoleModelPreference,
        role: AgentRole = AgentRole.MAIN,
        maxAttempts: Int = ModelFallbackPolicy.DEFAULT_MAX_FALLBACK_ATTEMPTS,
    ) = ModelFallbackPolicy(
        automaticFallback = true,
        maxFallbackAttempts = maxAttempts,
        fallbacksByRole = mapOf(role to chain.toList()),
    )

    /** Records every configuration that was actually sent a request. */
    private class Calls {
        val attempted = mutableListOf<ModelConfig>()

        suspend fun invoke(config: ModelConfig): String {
            attempted += config
            return "ok:${config.providerId}"
        }

        fun providers(): List<String> = attempted.map { it.providerId }
    }

    private val primary = config("gemini", "gemini-x")
    private val groq = config("groq", "groq-y")
    private val cerebras = config("cerebras", "cerebras-z")

    // --- 1 / 3. the switch happens before the request ----------------------

    @Test
    fun `an exhausted primary is never asked and the request goes to a candidate`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras")) },
            resolver = resolver(
                connections = mapOf("groq" to groq, "cerebras" to cerebras),
                rateLimits = manager(exhausted("gemini", "gemini-x")),
            ),
        )

        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
            call = calls::invoke,
        )

        // The primary was never sent: not a 429, not a wasted request.
        assertEquals(listOf("groq"), calls.providers())
        assertEquals("ok:groq", result)

        val started = assertIs<AgentEvent.ModelFallbackStarted>(
            sink.events.first { it is AgentEvent.ModelFallbackStarted },
        )
        assertEquals(ModelFallbackReason.RATE_LIMITED, started.reason)
        assertEquals("gemini", started.fromProviderId)
        assertEquals("groq", started.toProviderId)
    }

    @Test
    fun `a primary with headroom is used and nothing is switched`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(
                connections = mapOf("groq" to groq),
                // Generous, known allowance: this request fits inside the usable quota.
                rateLimits = manager(
                    RateLimitProfile(
                        providerId = "gemini",
                        modelId = "gemini-x",
                        requestsPerMinute = 60,
                        tokensPerMinute = 100_000,
                        source = RateLimitSource.APP_CONFIGURED,
                    ),
                ),
            ),
        )

        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
            call = calls::invoke,
        )

        assertEquals(listOf("gemini"), calls.providers())
        assertEquals("ok:gemini", result)
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted })
    }

    // --- 4. capability requirements are never traded for capacity ----------

    @Test
    fun `a candidate that cannot satisfy the role is skipped for one that can`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        // MAIN requires tool calling; the first candidate cannot call tools, so it
        // must not be selected merely because its quota is free.
        val noTools = config("groq", "groq-y", toolCalling = false)
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras")) },
            resolver = resolver(
                connections = mapOf("groq" to noTools, "cerebras" to cerebras),
                rateLimits = manager(exhausted("gemini", "gemini-x")),
            ),
        )

        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
            call = calls::invoke,
        )

        assertEquals("ok:cerebras", result)
        assertTrue("groq" !in calls.providers(), "an incapable candidate must not be sent a request")
        assertEquals(listOf("cerebras"), calls.providers())
    }

    @Test
    fun `an ineligible primary that is not quota-blocked is still attempted`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        // The primary is disabled, which is not a quota problem: a model that merely
        // has quota available must not silently answer in its place.
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(
                connections = mapOf("groq" to groq),
                rateLimits = manager(exhausted("gemini", "gemini-x")),
            ),
        )

        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = config("gemini", "gemini-x", enabled = false),
            sink = sink,
            call = calls::invoke,
        )

        assertEquals(listOf("gemini"), calls.providers())
        assertEquals("ok:gemini", result)
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted })
    }

    // --- 10. nothing to switch to -----------------------------------------

    @Test
    fun `no candidate with headroom fails structured and sends nothing`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(
                connections = mapOf("groq" to groq),
                rateLimits = manager(exhausted("gemini", "gemini-x"), exhausted("groq", "groq-y")),
            ),
        )

        assertFailsWith<AgentModelResolutionException> {
            fallback.execute(
                role = AgentRole.MAIN,
                sessionId = "s",
                primary = primary,
                sink = sink,
                call = calls::invoke,
            )
        }

        // Neither the exhausted primary nor the exhausted candidate was asked, and no
        // provider was retried in a loop.
        assertTrue(calls.providers().isEmpty(), "no request may be sent when nothing has headroom")
    }

    @Test
    fun `the candidate walk is bounded by the policy and never repeats a candidate`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        val fallback = ModelFallback(
            // A chain that repeats the primary and a candidate, with room for one attempt.
            policy = {
                policy(
                    RoleModelPreference("gemini"),
                    RoleModelPreference("groq"),
                    RoleModelPreference("cerebras"),
                    maxAttempts = 1,
                )
            },
            resolver = resolver(
                connections = mapOf("groq" to groq, "cerebras" to cerebras),
                rateLimits = manager(exhausted("gemini", "gemini-x")),
            ),
        )

        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
            call = calls::invoke,
        )

        // The repeated primary is recognised as a duplicate and the budget stops after
        // the first candidate, so the chain cannot loop.
        assertEquals("ok:groq", result)
        assertEquals(listOf("groq"), calls.providers())
    }

    // --- 15. local models are not remote quota ----------------------------

    @Test
    fun `a local primary is not switched away from`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        val localPrimary = config("local-phone", "local-model", local = true)
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq")) },
            resolver = resolver(
                connections = mapOf("groq" to groq),
                // The same scope is exhausted remotely, but this endpoint is local and
                // therefore spends none of the remote allowance.
                rateLimits = manager(exhausted("local-phone", "local-model")),
            ),
        )

        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = localPrimary,
            sink = sink,
            call = calls::invoke,
        )

        assertEquals(listOf("local-phone"), calls.providers())
        assertEquals("ok:local-phone", result)
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted })
    }

    // --- 23. the protected reserve is never intentionally consumed ---------

    @Test
    fun `a candidate inside its protected zone is not selected`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        // Both candidates are exhausted; only a model with real headroom may be used,
        // and there is none, so the run fails structured rather than spending the
        // remainder of any provider.
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras")) },
            resolver = resolver(
                connections = mapOf("groq" to groq, "cerebras" to cerebras),
                rateLimits = manager(
                    exhausted("gemini", "gemini-x"),
                    exhausted("groq", "groq-y"),
                    exhausted("cerebras", "cerebras-z"),
                ),
            ),
        )

        assertFailsWith<AgentModelResolutionException> {
            fallback.execute(
                role = AgentRole.MAIN,
                sessionId = "s",
                primary = primary,
                sink = sink,
                call = calls::invoke,
            )
        }

        assertTrue(calls.providers().isEmpty())
    }
}
