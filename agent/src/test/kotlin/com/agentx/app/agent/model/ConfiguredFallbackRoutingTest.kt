package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.ModelFallbackReason
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The end-to-end claim of this phase: a chain that was *persisted* is the chain the
 * runtime executes, the switch is visible in the event stream, and nothing else is
 * ever substituted for it.
 *
 * The fixture drives the real [AgentModelResolver] eligibility system and the real
 * [ModelFallback]; only the model call itself is a lambda, so no request is made. The
 * policy is never written inline — it always comes from
 * [AgentFallbackConfigRepository], i.e. from the configuration path the app actually
 * has, which is what separated "fallback exists" from "fallback is reachable".
 */
class ConfiguredFallbackRoutingTest {

    private fun config(
        provider: String,
        model: String,
        connectionId: String = provider,
        toolCalling: Boolean = true,
        enabled: Boolean = true,
    ) = ModelConfig(
        providerId = provider,
        baseUrl = "https://$provider.example/v1",
        model = model,
        stream = false,
        connectionId = connectionId,
        capabilities = ModelCapabilities(
            toolCalling = toolCalling,
            streaming = true,
            local = false,
            enabled = enabled,
        ),
    )

    private fun failure(code: ModelProviderErrorCode, status: Int? = null) = ModelProviderError(
        code = code,
        message = code.name,
        providerId = "test",
        httpStatus = status,
        retryable = true,
    )

    private fun resolver(connections: Map<String, ModelConfig>) = AgentModelResolver(
        connections = { connections },
        capabilityRegistry = InMemoryModelCapabilityRegistry(),
    )

    /** Records every configuration the runtime actually asked. */
    private class Calls(private val behaviour: (ModelConfig) -> String) {
        val attempted = mutableListOf<ModelConfig>()
        suspend fun invoke(config: ModelConfig): String {
            attempted += config
            return behaviour(config)
        }
        fun connectionIds(): List<String> = attempted.map { it.connectionId }
    }

    private val primary = config("gemini", "gemini-x", connectionId = "gemini-a")
    private val fallback = config("cerebras", "qwen-3-coder", connectionId = "cerebras-b")
    private val unrelated = config("groq", "openai/gpt-oss-120b", connectionId = "groq-c")

    private val connections = mapOf(
        primary.connectionId to primary,
        fallback.connectionId to fallback,
        unrelated.connectionId to unrelated,
    )

    /**
     * A repository whose saved chain is the fallback, enabled or not.
     *
     * The chain is declared for the same role the test then executes as: chains are
     * per role, so a chain saved under another role is simply not there, and a
     * "candidate was never used" assertion would pass for the wrong reason.
     */
    private fun repository(
        enabled: Boolean,
        chain: List<RoleModelPreference>,
        role: AgentRole = AgentRole.MAIN,
    ) = runBlocking {
        val repository = AgentFallbackConfigRepository(InMemoryAgentFallbackStore())
        repository.setChain(role, chain)
        repository.setEnabled(enabled)
        repository
    }

    private fun preference(config: ModelConfig) =
        RoleModelPreference(providerId = config.providerId, model = config.model, connectionId = config.connectionId)

    // --- B. a configured fallback is selected and reported ------------------

    @Test
    fun `a persisted chain is what the runtime executes and the switch is observable`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { config -> if (config == primary) throw failure(ModelProviderErrorCode.RATE_LIMITED) else "ok" }
        val repository = repository(enabled = true, chain = listOf(preference(fallback)))

        val result = ModelFallback(policy = repository.livePolicy(), resolver = resolver(connections)).execute(
            role = AgentRole.MAIN,
            sessionId = "s1",
            primary = primary,
            sink = sink,
        ) { config -> calls.invoke(config) }

        assertEquals("ok", result)
        assertEquals(listOf(primary.connectionId, fallback.connectionId), calls.connectionIds())
        assertTrue(unrelated.connectionId !in calls.connectionIds(), "only the configured chain may be used")

        val started = sink.events.filterIsInstance<AgentEvent.ModelFallbackStarted>().single()
        assertEquals(primary.providerId, started.fromProviderId)
        assertEquals(primary.model, started.fromModelId)
        assertEquals(fallback.providerId, started.toProviderId)
        assertEquals(fallback.model, started.toModelId)
        assertEquals(ModelFallbackReason.RATE_LIMITED, started.reason)
        assertEquals(1, started.attempt)

        val succeeded = sink.events.filterIsInstance<AgentEvent.ModelFallbackSucceeded>().single()
        assertEquals(fallback.connectionId, succeeded.toConnectionId)
        assertEquals(primary.connectionId, succeeded.fromConnectionId)
    }

    // --- A / J. no configured chain means no substitution ------------------

    @Test
    fun `a chain that was never enabled never substitutes an unrelated healthy model`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { throw failure(ModelProviderErrorCode.RATE_LIMITED) }
        val repository = repository(enabled = false, chain = listOf(preference(fallback)))

        val thrown = assertFailsWith<ModelProviderError> {
            ModelFallback(policy = repository.livePolicy(), resolver = resolver(connections)).execute(
                role = AgentRole.MAIN,
                sessionId = "s1",
                primary = primary,
                sink = sink,
            ) { config -> calls.invoke(config) }
        }

        assertEquals(ModelProviderErrorCode.RATE_LIMITED, thrown.code)
        assertEquals(listOf(primary.connectionId), calls.connectionIds(), "the primary failed clearly and nothing else ran")
        assertTrue(sink.events.filterIsInstance<AgentEvent.ModelFallbackStarted>().isEmpty())
    }

    @Test
    fun `an empty policy leaves a healthy unrelated connection untouched`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { throw failure(ModelProviderErrorCode.TIMEOUT) }

        assertFailsWith<ModelProviderError> {
            ModelFallback(policy = { ModelFallbackPolicy.DISABLED }, resolver = resolver(connections)).execute(
                role = AgentRole.MAIN,
                sessionId = "s1",
                primary = primary,
                sink = sink,
            ) { config -> calls.invoke(config) }
        }

        assertEquals(listOf(primary.connectionId), calls.connectionIds())
        // `unrelated` is connected, healthy and eligible — and still never used.
        assertTrue(unrelated.connectionId !in calls.connectionIds())
    }

    // --- C. an ineligible failure never falls back -------------------------

    @Test
    fun `an authentication failure ends the request even with a chain configured`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { throw failure(ModelProviderErrorCode.AUTHENTICATION_FAILED) }
        val repository = repository(enabled = true, chain = listOf(preference(fallback)))

        val thrown = assertFailsWith<ModelProviderError> {
            ModelFallback(policy = repository.livePolicy(), resolver = resolver(connections)).execute(
                role = AgentRole.MAIN,
                sessionId = "s1",
                primary = primary,
                sink = sink,
            ) { config -> calls.invoke(config) }
        }

        assertEquals(ModelProviderErrorCode.AUTHENTICATION_FAILED, thrown.code)
        assertEquals(listOf(primary.connectionId), calls.connectionIds(), "a rejected key would be rejected everywhere")
    }

    @Test
    fun `a malformed request ends the request instead of being replayed elsewhere`() = runBlocking {
        val calls = Calls { throw failure(ModelProviderErrorCode.INVALID_REQUEST) }
        val repository = repository(enabled = true, chain = listOf(preference(fallback)))

        assertFailsWith<ModelProviderError> {
            ModelFallback(policy = repository.livePolicy(), resolver = resolver(connections)).execute(
                role = AgentRole.MAIN,
                sessionId = "s1",
                primary = primary,
                sink = CollectingEventSink(),
            ) { config -> calls.invoke(config) }
        }

        assertEquals(listOf(primary.connectionId), calls.connectionIds())
    }

    // --- D. capability-ineligible candidates are refused -------------------

    @Test
    fun `a candidate without tool calling is never used even when configured`() = runBlocking {
        val calls = Calls { config -> if (config.connectionId == primary.connectionId) throw failure(ModelProviderErrorCode.TIMEOUT) else "ok" }
        val noTools = config("cerebras", "qwen-3-coder", connectionId = "cerebras-b", toolCalling = false)
        // Declared for CODER, the role the request runs as, so this test exercises
        // the capability rejection rather than an absent chain.
        val repository = repository(enabled = true, chain = listOf(preference(noTools)), role = AgentRole.CODER)

        assertFailsWith<ModelProviderError> {
            ModelFallback(policy = repository.livePolicy(), resolver = resolver(connections + (noTools.connectionId to noTools)))
                .execute(
                    role = AgentRole.CODER,
                    sessionId = "s1",
                    primary = primary,
                    sink = CollectingEventSink(),
                ) { config -> calls.invoke(config) }
        }

        assertEquals(listOf(primary.connectionId), calls.connectionIds(), "capability checks are not bypassed by a failure")
    }

    // --- F / G. quota-aware chain -----------------------------------------

    @Test
    fun `a fallback with no quota headroom is not attempted and the chain ends structured`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { throw failure(ModelProviderErrorCode.RATE_LIMITED, status = 429) }
        val rateLimits = com.agentx.app.model.ratelimit.DefaultRateLimitManager()
        rateLimits.updateProfile(
            com.agentx.app.model.ratelimit.RateLimitProfile(
                providerId = fallback.providerId,
                modelId = fallback.model,
                accountId = fallback.connectionId,
                requestsPerMinute = 0,
                source = com.agentx.app.model.ratelimit.RateLimitSource.APP_CONFIGURED,
            ),
        )
        val repository = repository(enabled = true, chain = listOf(preference(fallback)))

        assertFailsWith<ModelProviderError> {
            ModelFallback(
                policy = repository.livePolicy(),
                resolver = AgentModelResolver(
                    connections = { connections },
                    capabilityRegistry = InMemoryModelCapabilityRegistry(),
                    rateLimitManager = rateLimits,
                ),
            ).execute(
                role = AgentRole.MAIN,
                sessionId = "s1",
                primary = primary,
                sink = sink,
            ) { config -> calls.invoke(config) }
        }

        assertEquals(listOf(primary.connectionId), calls.connectionIds(), "a candidate with no headroom is not asked")
    }

    // --- H. loop prevention ------------------------------------------------

    @Test
    fun `a chain that names the primary again never re-runs it`() = runBlocking {
        val calls = Calls { throw failure(ModelProviderErrorCode.TIMEOUT) }
        // A → B → A: the primary is first and last in the chain.
        val repository = repository(
            enabled = true,
            chain = listOf(preference(fallback), preference(primary)),
        )

        assertFailsWith<ModelProviderError> {
            ModelFallback(policy = repository.livePolicy(), resolver = resolver(connections)).execute(
                role = AgentRole.MAIN,
                sessionId = "s1",
                primary = primary,
                sink = CollectingEventSink(),
            ) { config -> calls.invoke(config) }
        }

        assertEquals(
            1,
            calls.connectionIds().count { it == primary.connectionId },
            "the primary must not be executed twice in one fallback chain",
        )
    }

    // --- I. multi-connection identity --------------------------------------

    @Test
    fun `a chain entry resolves the connection it names, not a sibling with the same model id`() = runBlocking {
        // Two custom OpenAI-compatible connections exposing the same model id. The
        // chain names one of them, so that one must answer.
        val first = config("openai-compatible", "devstral-24b", connectionId = "custom-1")
        val second = config("openai-compatible", "devstral-24b", connectionId = "custom-2")
        val both = mapOf(first.connectionId to first, second.connectionId to second)
        val calls = Calls { config -> if (config.connectionId == primary.connectionId) throw failure(ModelProviderErrorCode.TIMEOUT) else "ok" }
        val repository = repository(enabled = true, chain = listOf(preference(second)), role = AgentRole.CODER)

        ModelFallback(policy = repository.livePolicy(), resolver = resolver(both + (primary.connectionId to primary))).execute(
            role = AgentRole.CODER,
            sessionId = "s1",
            primary = primary,
            sink = CollectingEventSink(),
        ) { config -> calls.invoke(config) }

        assertEquals(listOf(primary.connectionId, "custom-2"), calls.connectionIds())
        assertEquals("devstral-24b", calls.attempted.last().model)
    }

    @Test
    fun `a chain entry naming a disconnected connection is skipped rather than answered by its family`() = runBlocking {
        // `cerebras-other` is not connected while `cerebras-b` is: the entry names the
        // former, so it must be skipped, never satisfied by the sibling connection.
        val calls = Calls { config -> if (config.connectionId == primary.connectionId) throw failure(ModelProviderErrorCode.TIMEOUT) else "ok" }
        val repository = repository(
            enabled = true,
            chain = listOf(RoleModelPreference(providerId = "cerebras", model = "qwen-3-coder", connectionId = "cerebras-other")),
        )

        assertFailsWith<ModelProviderError> {
            ModelFallback(policy = repository.livePolicy(), resolver = resolver(connections)).execute(
                role = AgentRole.MAIN,
                sessionId = "s1",
                primary = primary,
                sink = CollectingEventSink(),
            ) { config -> calls.invoke(config) }
        }

        assertEquals(listOf(primary.connectionId), calls.connectionIds())
    }

    // --- K. cancellation ---------------------------------------------------

    @Test
    fun `a cancelled primary never starts a fallback`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = Calls { throw CancellationException("cancelled") }
        val repository = repository(enabled = true, chain = listOf(preference(fallback)))

        assertFailsWith<CancellationException> {
            ModelFallback(policy = repository.livePolicy(), resolver = resolver(connections)).execute(
                role = AgentRole.MAIN,
                sessionId = "s1",
                primary = primary,
                sink = sink,
            ) { config -> calls.invoke(config) }
        }

        assertEquals(listOf(primary.connectionId), calls.connectionIds(), "cancellation is not a fallback trigger")
        assertTrue(sink.events.filterIsInstance<AgentEvent.ModelFallbackStarted>().isEmpty())
    }

    // --- bounded depth -----------------------------------------------------

    @Test
    fun `the chain depth is bounded by the persisted attempt limit`() = runBlocking {
        val third = config("mistral", "codestral", connectionId = "mistral-d")
        val calls = Calls { throw failure(ModelProviderErrorCode.PROVIDER_ERROR, status = 503) }
        val repository = runBlocking {
            val created = AgentFallbackConfigRepository(InMemoryAgentFallbackStore())
            created.setChain(
                AgentRole.MAIN,
                listOf(preference(fallback), preference(third)),
            )
            created.setEnabled(true)
            created // default attempt limit is 2
        }

        assertFailsWith<ModelProviderError> {
            ModelFallback(policy = repository.livePolicy(), resolver = resolver(connections + (third.connectionId to third)))
                .execute(
                    role = AgentRole.MAIN,
                    sessionId = "s1",
                    primary = primary,
                    sink = CollectingEventSink(),
                ) { config -> calls.invoke(config) }
        }

        assertTrue(
            calls.connectionIds().size <= ModelFallbackPolicy.DEFAULT_MAX_FALLBACK_ATTEMPTS + 1,
            "primary plus a bounded number of candidates: ${calls.connectionIds()}",
        )
    }
}
