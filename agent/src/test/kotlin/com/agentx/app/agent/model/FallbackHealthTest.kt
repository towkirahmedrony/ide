package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.health.CandidateFailure
import com.agentx.app.model.health.CandidateHealthState
import com.agentx.app.model.health.CandidateHealthTracker
import com.agentx.app.model.ratelimit.DefaultRateLimitManager
import com.agentx.app.model.ratelimit.RateLimitProfile
import com.agentx.app.model.ratelimit.RateLimitSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Provider/model health as part of controlled fallback.
 *
 * A candidate that just failed must not be hammered again, and a failure must not
 * reach further than it really does. Both properties are checked through the real
 * resolver and the real fallback chain, with a recording lambda standing in for
 * the model call so no network request is made.
 */
class FallbackHealthTest {

    private var now = 1_000L

    private fun config(
        provider: String,
        model: String,
        toolCalling: Boolean = true,
    ) = ModelConfig(
        providerId = provider,
        baseUrl = "https://$provider.example/v1",
        model = model,
        capabilities = ModelCapabilities(toolCalling = toolCalling, streaming = true, local = false, enabled = true),
    )

    private fun exhausted(providerId: String, modelId: String) = RateLimitProfile(
        providerId = providerId,
        modelId = modelId,
        requestsPerMinute = 0,
        source = RateLimitSource.APP_CONFIGURED,
    )

    private fun manager(vararg profiles: RateLimitProfile): DefaultRateLimitManager {
        val rateLimits = DefaultRateLimitManager()
        runBlocking { profiles.forEach { rateLimits.updateProfile(it) } }
        return rateLimits
    }

    private fun resolver(
        connections: Map<String, ModelConfig>,
        rateLimits: DefaultRateLimitManager,
        health: CandidateHealthTracker,
    ) = AgentModelResolver(
        connections = { connections },
        capabilityRegistry = InMemoryModelCapabilityRegistry(),
        rateLimitManager = rateLimits,
        healthTracker = health,
    )

    private fun policy(
        vararg chain: RoleModelPreference,
        maxAttempts: Int = ModelFallbackPolicy.DEFAULT_MAX_FALLBACK_ATTEMPTS,
    ) = ModelFallbackPolicy(
        automaticFallback = true,
        maxFallbackAttempts = maxAttempts,
        fallbacksByRole = mapOf(AgentRole.MAIN to chain.toList()),
    )

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

    private fun cooledDownGroq(): CandidateHealthTracker {
        val health = CandidateHealthTracker(clock = { now })
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        return health
    }

    // --- health gates candidate selection ----------------------------------

    @Test
    fun `a cooled-down candidate is skipped for a healthy one`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        val health = cooledDownGroq()
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras")) },
            resolver = resolver(
                connections = mapOf("groq" to groq, "cerebras" to cerebras),
                rateLimits = manager(exhausted("gemini", "gemini-x")),
                health = health,
            ),
            health = health,
        )

        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
            call = calls::invoke,
        )

        // groq is inside its cooldown, so it is not even asked.
        assertEquals("ok:cerebras", result)
        assertEquals(listOf("cerebras"), calls.providers())
        assertTrue(sink.events.any { it is AgentEvent.ModelFallbackStarted })
    }

    @Test
    fun `a model-specific failure leaves the provider's other models eligible`() = runBlocking {
        val health = cooledDownGroq()
        val otherModel = config("groq", "groq-other")
        val resolver = resolver(
            connections = mapOf("groq" to otherModel),
            rateLimits = DefaultRateLimitManager(),
            health = health,
        )

        val cooling = resolver.eligibilityFor(AgentRole.MAIN, groq)
        val other = resolver.eligibilityFor(AgentRole.MAIN, otherModel)

        assertEquals(ModelEligibilityState.PROVIDER_UNHEALTHY, cooling.state)
        assertEquals(CandidateHealthState.TEMPORARILY_UNAVAILABLE, cooling.healthState)
        // One bad model is not a provider outage.
        assertTrue(other.eligible, "the provider's other model must stay usable")
    }

    @Test
    fun `rejected credentials make the whole provider ineligible`() = runBlocking {
        val health = CandidateHealthTracker(clock = { now })
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.AUTHENTICATION)
        val otherModel = config("groq", "groq-other")
        val resolver = resolver(
            connections = mapOf("groq" to otherModel),
            rateLimits = DefaultRateLimitManager(),
            health = health,
        )

        val first = resolver.eligibilityFor(AgentRole.MAIN, groq)
        val second = resolver.eligibilityFor(AgentRole.MAIN, otherModel)

        assertEquals(CandidateHealthState.AUTH_FAILED, first.healthState)
        // Credentials belong to the provider, so both of its models report the same
        // problem instead of each re-learning it.
        assertEquals(ModelEligibilityState.PROVIDER_UNHEALTHY, second.state)
        assertEquals(CandidateHealthState.AUTH_FAILED, second.healthState)
    }

    @Test
    fun `health does not override the role capability contract`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        val health = CandidateHealthTracker(clock = { now })
        // The first candidate is healthy but cannot call tools, which MAIN requires.
        val incapable = config("groq", "groq-y", toolCalling = false)
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras")) },
            resolver = resolver(
                connections = mapOf("groq" to incapable, "cerebras" to cerebras),
                rateLimits = manager(exhausted("gemini", "gemini-x")),
                health = health,
            ),
            health = health,
        )

        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = primary,
            sink = sink,
            call = calls::invoke,
        )

        assertEquals("ok:cerebras", result)
        assertEquals(listOf("cerebras"), calls.providers())
    }

    @Test
    fun `nothing safe to use fails structured and sends nothing`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        val health = CandidateHealthTracker(clock = { now })
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        health.recordFailure("cerebras", "cerebras-z", failure = CandidateFailure.TIMEOUT)
        health.recordFailure("cerebras", "cerebras-z", failure = CandidateFailure.TIMEOUT)
        val fallback = ModelFallback(
            policy = { policy(RoleModelPreference("groq"), RoleModelPreference("cerebras")) },
            resolver = resolver(
                connections = mapOf("groq" to groq, "cerebras" to cerebras),
                rateLimits = manager(exhausted("gemini", "gemini-x")),
                health = health,
            ),
            health = health,
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

        // Nothing was sent and no provider was retried: the chain is bounded and the
        // failure is reported rather than spending a candidate that is known bad.
        assertTrue(calls.providers().isEmpty())
    }

    @Test
    fun `the candidate walk stays bounded when every candidate is unhealthy`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls()
        val health = CandidateHealthTracker(clock = { now })
        listOf("groq" to "groq-y", "cerebras" to "cerebras-z").forEach { (provider, model) ->
            health.recordFailure(provider, model, failure = CandidateFailure.CONNECTION)
            health.recordFailure(provider, model, failure = CandidateFailure.CONNECTION)
        }
        val fallback = ModelFallback(
            policy = {
                policy(
                    RoleModelPreference("groq"),
                    RoleModelPreference("cerebras"),
                    RoleModelPreference("groq"),
                    maxAttempts = 2,
                )
            },
            resolver = resolver(
                connections = mapOf("groq" to groq, "cerebras" to cerebras),
                rateLimits = manager(exhausted("gemini", "gemini-x")),
                health = health,
            ),
            health = health,
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

    // --- decision metadata -------------------------------------------------

    @Test
    fun `fallback decision metadata carries the required fields and no credential`() {
        val decision = ModelFallbackDecision(
            role = AgentRole.MAIN,
            sessionId = "s",
            originalProviderId = "gemini",
            originalModelId = "gemini-x",
            ineligibleReason = ModelEligibilityState.RATE_LIMITED.name,
            consideredProviderId = "groq",
            consideredModelId = "groq-y",
            eligibilityState = ModelEligibilityState.AVAILABLE.name,
            selectedProviderId = "groq",
            selectedModelId = "groq-y",
            requestSent = true,
            beforeRequest = true,
            attempts = 1,
            trigger = "RATE_LIMITED",
        )

        val fields = decision.fields()

        listOf(
            "originalProvider",
            "originalModel",
            "ineligibleReason",
            "consideredProvider",
            "eligibility",
            "selectedProvider",
            "requestSent",
            "beforeRequest",
            "trigger",
        ).forEach { key ->
            assertTrue(fields.containsKey(key), "the decision must record '$key'")
        }
        // Runtime metadata, never a credential.
        assertTrue(fields.keys.none { it.contains("key", ignoreCase = true) })
        assertTrue(fields.keys.none { it.contains("token", ignoreCase = true) })
        assertTrue(fields.keys.none { it.contains("authorization", ignoreCase = true) })
    }
}
